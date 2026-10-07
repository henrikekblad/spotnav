// Home Assistant side of the app screenshots, against the throwaway instance on 127.0.0.1 only.
//
//   node ha_setup.mjs setup      onboard, add the demo devices, a SpotNav charger and a site; set up the camera
//                                and let "Family car" be identified at the charger by its charging cable
//   node ha_setup.mjs approve    approve the pairing request the app has just made
//   node ha_setup.mjs unplug     unplug the car (both cars' cables off)
//   node ha_setup.mjs plug-in    plug in again, once the unplug counts (about two minutes after it): identifying
//   node ha_setup.mjs city       "City car" says it is plugged in: decided by its charging cable
//   node ha_setup.mjs ask        a quick replug with both cars saying so: the question which car is plugged in
//
// Environment: HA_URL, HA_DRIVER (the Home Assistant tool's driver directory, whose ha.mjs and demo.mjs are
// reused), APP_HA_URL (the address the app is told and given back on approval), WORK (where the unplug's time
// is kept for plug-in).
import fs from "node:fs";
import path from "node:path";

const ha = await import(path.join(process.env.HA_DRIVER, "ha.mjs"));
const demo = await import(path.join(process.env.HA_DRIVER, "demo.mjs"));
const UNPLUGGED_AT = path.join(process.env.WORK || ".", "unplugged_at");
const APP_HA_URL = process.env.APP_HA_URL || "http://localhost:8123";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (text) => console.log("== " + text);

async function signedIn() {
  await ha.waitUp();
  await ha.onboard();
  return new ha.Api(await ha.login());
}

/** Answer a config flow form by form: each form gets the values `answer(step)` returns for it. */
async function flow(api, handler, answer) {
  let r = await api.post("/api/config/config_entries/flow", { handler, show_advanced_options: false });
  for (let i = 0; i < 20 && (r.type === "form" || r.type === "menu"); i++) {
    const reply = answer(r);
    if (reply === undefined) throw new Error(`${handler}: no answer for step ${r.step_id}: ${JSON.stringify(r).slice(0, 400)}`);
    r = await api.post(`/api/config/config_entries/flow/${r.flow_id}`, reply);
    if (r.errors && Object.keys(r.errors).length) throw new Error(`${handler} ${r.step_id}: ${JSON.stringify(r.errors)}`);
  }
  if (r.type !== "create_entry") throw new Error(`${handler}: flow ended with ${JSON.stringify(r).slice(0, 400)}`);
  return r;
}

async function waitForState(api, entityId, timeoutS = 90) {
  for (let i = 0; i < timeoutS * 2; i++) {
    const r = await fetch(`${ha.BASE}/api/states/${entityId}`, { headers: api.h });
    if (r.ok) return;
    await sleep(500);
  }
  throw new Error("no state for " + entityId);
}

async function setup() {
  const api = await signedIn();
  await ha.promoteHttp(api);
  await ha.setLocation(api);
  // The address the app pairs with: the approval hands it back, so it must be the one the emulator uses.
  await api.ws({ type: "config/core/update", internal_url: APP_HA_URL });
  for (const integration of ["ocpp", "sigen", "kia_uvo", "demo_vision"]) {
    if ((await api.entries(integration)).length === 0) await api.flow(integration);
  }
  for (const entry of await api.entries("spotnav")) {
    await fetch(`${ha.BASE}/api/config/config_entries/entry/${entry.entry_id}`, { method: "DELETE", headers: api.h });
  }
  await waitForState(api, "sensor.sigen_plant_grid_phase_c_active");
  const devices = await api.ws({ type: "config/device_registry/list" });
  const deviceId = (name) => {
    const found = devices.find((d) => d.name === name);
    if (!found) throw new Error("no demo device " + name);
    return found.id;
  };

  log("SpotNav charger");
  await flow(api, "spotnav", (s) => ({
    user: { entry_type: "charger" },
    charger: { mode: "detected" },
  }[s.step_id] ?? (s.data_schema?.some((f) => f.name === "device") ? { device: deviceId("Garage charger Connector 1") }
    : s.data_schema?.some((f) => f.name === "charger_phases") ? { charger_phases: "3" } : {})));
  log("camera and the first car");
  const garage = await chargerId(api);
  await demo.setupCamera(api, garage);
  await demo.decideFamilyCar(api, garage);

  log("SpotNav site");
  await flow(api, "spotnav", (s) => {
    if (s.step_id === "user") return { entry_type: "site" };
    const names = (s.data_schema || []).map((f) => f.name);
    if (names.includes("main_fuse_a")) return { name: "Home", main_fuse_a: 25 };
    if (names.includes("phases")) return { phases: 3 };
    return {};
  });
  for (const e of await api.entries("spotnav")) console.log(`   ${e.title}`);
}

async function chargerId(api) {
  const entry = (await api.entries("spotnav")).find((e) => e.title.startsWith("Garage charger"));
  if (!entry) throw new Error("no SpotNav charger");
  return entry.entry_id;
}

/** The identification steps, for the app's pictures of the car line, the status line and the question. */
async function identification(command) {
  const api = await signedIn();
  const garage = await chargerId(api);
  if (command === "unplug") {
    fs.writeFileSync(UNPLUGGED_AT, String(await demo.unplug(api)));
    log("unplugged");
  } else if (command === "plug-in") {
    await demo.plugIn(api, garage, Number(fs.readFileSync(UNPLUGGED_AT, "utf8")));
    log("plugged in: identifying");
  } else if (command === "city") {
    await demo.decideCityCar(api, garage);
    log("City car identified by its charging cable");
  } else {
    await demo.askBetweenBoth(api, garage);
    log("asking which car is plugged in");
  }
}

/** Approve the newest pending SpotNav pairing request, as a person tapping Approve would. */
async function approve() {
  const api = await signedIn();
  for (let i = 0; i < 60; i++) {
    const flows = await api.ws({ type: "config_entries/flow/progress" });
    const pairing = flows.filter((f) => f.handler === "spotnav" && f.step_id === "pairing");
    if (pairing.length) {
      const r = await api.post(`/api/config/config_entries/flow/${pairing[pairing.length - 1].flow_id}`, { next_step_id: "pair_approve" });
      if (r.reason !== "pairing_approved") throw new Error("approval: " + JSON.stringify(r));
      log("pairing approved");
      return;
    }
    await sleep(500);
  }
  throw new Error("no pairing request arrived");
}

const command = process.argv[2];
if (command === "setup") await setup();
else if (command === "approve") await approve();
else if (["unplug", "plug-in", "city", "ask"].includes(command)) await identification(command);
else throw new Error("usage: ha_setup.mjs setup|approve|unplug|plug-in|city|ask");
