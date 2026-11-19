// Home Assistant side of the app screenshots, against the throwaway instance on 127.0.0.1 only.
//
//   node ha_setup.mjs setup      onboard, add the demo devices, a SpotNav charger and a site; set up the camera
//                                and let "Family car" be identified at the charger by its charging cable
//   node ha_setup.mjs scene '{"sun":1.8,...}'   pin the demo house's live power (kW) for a picture
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

async function setup() {
  const api = await signedIn();
  // The same installation as the Home Assistant documentation pictures (the Home Assistant tool's `v3setup.mjs`):
  // the demo devices with a sun, a home battery, a heat pump and weeks of history, a charger ("Garage") with two
  // cars and a site ("Home"). The address the app pairs with is the internal URL: the approval hands it back.
  const v3 = await import(path.join(process.env.HA_DRIVER, "v3setup.mjs"));
  const ids = await v3.setupInstall(api, { appUrl: APP_HA_URL });
  for (const e of await api.entries("spotnav")) console.log(`   ${e.title}`);
  return ids;
}

/** Pin the live power sensors of the demo house (kW; battery charge positive), so a picture does not depend on the
 * hour of the day. Without values it is cleared. */
async function scene(values) {
  const api = await signedIn();
  await api.post("/api/services/demo_house/docs_scene", values);
  await sleep(7000);
  log("scene " + JSON.stringify(values));
}

async function chargerId(api) {
  const entry = (await api.entries("spotnav")).find((e) => e.title === "Garage");
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
else if (command === "scene") await scene(JSON.parse(process.argv[3] || "{}"));
else if (command === "approve") await approve();
else if (["unplug", "plug-in", "city", "ask"].includes(command)) await identification(command);
else throw new Error("usage: ha_setup.mjs setup|approve|unplug|plug-in|city|ask");
