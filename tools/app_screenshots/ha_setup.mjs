// Home Assistant side of the app screenshots, against the throwaway instance on 127.0.0.1 only.
//
//   node ha_setup.mjs setup      onboard, add the demo devices, a SpotNav charger and a site
//   node ha_setup.mjs approve    approve the pairing request the app has just made
//
// Environment: HA_URL, HA_DRIVER (the Home Assistant tool's driver directory, whose ha.mjs is reused),
// APP_HA_URL (the address the app is told and given back on approval).
import path from "node:path";

const ha = await import(path.join(process.env.HA_DRIVER, "ha.mjs"));
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
  for (const demo of ["ocpp", "sigen", "kia_uvo"]) {
    if ((await api.entries(demo)).length === 0) await api.flow(demo);
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
else throw new Error("usage: ha_setup.mjs setup|approve");
