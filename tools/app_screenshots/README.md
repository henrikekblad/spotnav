# App screenshots

Takes the app's documentation pictures (`docs/images/app-*.png`) and the Google Play store pictures
(`fastlane/metadata/android/<locale>/images/phoneScreenshots/`) on a headless emulator, against a
throwaway Home Assistant that runs only on this machine. No phone, real Home Assistant, relay or account
is involved, and nothing is fetched from the network (after the first run's Python install).

    tools/app_screenshots/run.sh                    # everything: docs in English, store set in nine languages
    tools/app_screenshots/run.sh --sv               # also Swedish docs pictures, in docs/images/sv/
    tools/app_screenshots/run.sh --langs "en sv"    # store set for these languages only
    tools/app_screenshots/run.sh app-main store-widget   # only the named pictures
    tools/app_screenshots/run.sh --keep             # leave Home Assistant and the emulator up afterwards
    tools/app_screenshots/run.sh --fresh app-main   # force the slow path: wiped emulator, new demo installation
    tools/app_screenshots/run.sh --workers 1        # the store languages one after another on one emulator

Needs the Home Assistant repository next to this one (`../elpris-home-assistant`, or `HA_REPO=...`, which a
run from a git worktree elsewhere needs),
`node` 22 or newer, `python3`, and the Android SDK in `~/Android/Sdk` (or `ANDROID_SDK_ROOT`) with the
emulator and the `android-36` `google_apis_playstore` `x86_64` system image. Every phase's start and end goes to
stderr as `[timing] ...`. See [Faster runs](#faster-runs) for what a run costs: about a quarter of an hour for
everything, about three minutes for a few named pictures.

## What it does

1. Starts the Home Assistant repository's own demo pieces from `tools/docs_screenshots` without changing
   that repository: `rates_stub.py` (the ECB's exchange rates, from a recorded file) on 127.0.0.1:8130 and
   `ha_launch.py` (Home Assistant with the ECB's address redirected to the stub and synthetic charge history)
   on 127.0.0.1:8129, with a configuration built from nothing in `.work/ha-config` (the demo OCPP charger,
   Sigenergy plant, two Kia cars, a camera, an AI Task entity and a Nord Pool integration that serves the
   recorded SE4 day `tests/fixtures/relay/day_SE4_2026-09-22_96.json` of that repository as today and
   tomorrow, and the integration linked in). Its Python environment is reused, or made here from its pinned requirements.
2. `ha_setup.mjs setup` onboards the instance (reusing that tool's `driver/ha.mjs` and `driver/demo.mjs`), adds a
   SpotNav charger ("Garage", the connector of the demo's "Garage charge point"; adding it also adds the
   "Electricity price SE4" price device on the demo Nord Pool integration) and a site ("Home"), and sets the instance's internal URL to
   `http://localhost:8123`, the address the app is given. Two cars ("Family car", "City car") can charge at the
   charger, identification is **Automatic**, the camera is chosen with its parking bay cropped and a day reference
   picture of each car (the camera's picture is drawn by the demo: no photograph, no number plate), and "Family
   car" is identified by its charging cable. Every "Get started" row left is answered "Not needed", so the app
   shows a fully set-up installation.
3. Creates the `SpotNavDocs` emulator once (`make_avd.sh`; the other AVDs are never touched): 1080x2160
   at 420 dpi, because Play wants the long side of a screenshot at most twice the short one. It boots headless, in
   Europe/Stockholm time, either wiped (the full path) or from the snapshot `docs-paired` (the kept path), see
   [Faster runs](#faster-runs).
4. Builds the debug app as the Play variant without Firebase:
   `./gradlew -Pstore=play -Ppush=false assembleDebug`. The app asks the relay for nothing here: its prices
   are the paired Home Assistant's, and push is off. (`-PrelayBaseUrl=...` can point a debug build's push
   registration at a stub of one's own; it is for debug builds only: the build refuses it for any other
   task, a release build compiles in the official address whatever is given, and `RelayAddress` ignores a
   configured address outside a debug build, `RelayAddressTest` and `RelayAddressReleaseTest` under
   `testReleaseUnitTest`.) Without a `local.properties` (a fresh worktree), give the SDK as `ANDROID_HOME`.
5. `drive.py` drives the app with `adb` (always `-s emulator-5584`) and `uiautomator dump`, finding
   things by the app's own strings in each language: dark theme, demo status bar (12:00, full battery
   and signal, no notifications);
   - the unpaired price view;
   - pairing: Settings, Find Home Assistant, the typed address (`adb reverse` makes the emulator's
     localhost:8123 the demo instance), the code; `ha_setup.mjs approve` then approves the request
     through the integration's pairing config flow, as tapping Approve in Home Assistant does;
   - changes the charging current and back once, a person's edit, which confirms the first-run
     suggestions so the status line does not ask to check them;
   - adds the widget from the launcher's widget list and widens it to the screen;
   - then one pass per language: main screen, planning and plan, price table, widget, settings (with the
     crop editor and a car's reference pictures in the documentation languages), charge history (the month
     before early in a month, so the bar chart has days);
   - last, which car is plugged in: `ha_setup.mjs unplug`, then `plug-in` once the unplug counts (it waits about
     two minutes: SpotNav takes a shorter unplug for the same plug-in) for "identifying…", `city` for the car line
     "identified by the car's charging cable ⇄", and `ask` (a quick replug with both cars saying they are plugged
     in) for the question, which stays open for every language's store picture.

   - after the language passes, the 3.0 pages (English only): the demo house's live power is pinned
     (`ha_setup.mjs scene`, so the pictures do not depend on the hour of the day), then the house page (its top, the
     Now box, the period picker with the Energy flow box and the Earnings box, for a month) and the heat page (its top,
     the dial with Home and Away, the Use box for a month). The page icons in the top row are tapped to get there, and
     the charger page is chosen again afterwards, since the app opens on the page it was left on.

The Home Assistant side is the Home Assistant tool's 3.0 installation (`driver/v3setup.mjs`: a demo house with a sun, a
home battery, a heat pump and weeks of history, see its README). The earnings need the past days' prices, which the demo has within seconds of its start.
`HA_PORT` and `STUB_PORT` move the demo instance's ports, so that it can run beside another one.

Logs are in `.work/logs`.

## Faster runs

A run is one of two paths, said on its first line:

- **Full path** (no picture names, or `app-standalone` or `app-pairing-code` named, or `--fresh`, or nothing kept from
  today): a wiped emulator cold boots, the demo installation is built, the app is installed, takes the unpaired
  pictures, pairs and gets its widget. Then **the result is kept**: the emulator's snapshot `docs-paired` (booted,
  installed, paired, widget on the home screen) and a copy of the demo configuration (`.work/ha-golden`, stamped with the
  day: the demo house's weeks end at the day it was built), saved after the site had read for a while. The slow work
  that only has to happen once a day.
- **Kept path** (picture names given and a snapshot and a copy from today): the copy is laid out again (the integration
  linked in, the demo components refreshed), Home Assistant starts from it, the emulator loads the snapshot (about a
  second) and the debug app is installed over the one in it, which keeps its pairing. No onboarding, no site set-up, no
  cold boot, no pairing, no widget. Use `--fresh` after a change that the paired state would hide (the pairing, the
  widget, the app's stored preferences).

What else is quick (the demo's own pace, see the Home Assistant tool's README, `demo_fast.py`):

- The past days' prices (the earnings, the month's cost) are there within seconds of the start; the rates stub serves
  ninety days of exchange rates, which the past days need to be priced at all. No hour-long wait for a price cycle. (A
  question to the site about a month is kept for the hour, so the tool asks about single past days to find out when the
  prices are in, never the month itself.)
- The home battery's check takes about two minutes on a new installation (not twenty), and a kept one has passed it.
- Only what the named pictures need is done: the language passes skip the steps (planning, price table, widget,
  settings, history) none of the names belong to, and a name that is only on the house or heat page or in the
  identification skips the passes. Name pictures to take a subset (`store-main`, `app-settings-battery`,
  `app-house-earnings`): there is no "changed only" mode, since which pictures a change touches is a judgement.
- The store languages run on **several emulators at once** (`--workers N`, default 3; only for a full set of three
  languages or more). One emulator takes the first language, its documentation pictures, the house and heat pages and
  the identification; the others, started from the same snapshot (all emulators of the AVD run `-read-only`, ports
  5586 and 5588), share the rest and take their languages' pictures; when all have finished, the identification's
  Home Assistant steps run once and each emulator takes the question's picture of its languages (the question is the
  same for all). The app is switched to a language by writing its language preference and restarting it, inside the
  running emulator, as before: nothing is reinstalled or rebooted per language.

Measured on a machine with 32 cores (one named picture in two languages, `--langs "en sv" app-main store-main`):
before, 8 s Home Assistant set-up + 36 s emulator boot + 110 s install, pairing and widget + 2 x 85 s language
passes, 6 min in all; after, on the kept path, 6 s + no boot + no pairing + 2 x 85 s: 3 min (a language pass whose
steps are not named is skipped, so `app-house-earnings` alone takes about a minute and a half). A full set of four
languages on three emulators, including the pairing, the house and heat pages and the identification: 12 min.

## Pictures

Documentation (English; `--sv` adds `docs/images/sv/`): `app-standalone`, `app-pairing-code`,
`app-main`, `app-planning`, `app-planning-target`, `app-plan-chart`, `app-price-table`, `app-widget`, `app-settings-general`,
`app-settings-display`, `app-settings-price`, `app-settings-vehicle`, `app-settings-charger`,
`app-settings-site`, `app-settings-battery`, `app-settings-heat`, `app-settings-notifications`, `app-settings-home-assistant`, `app-camera-frame`,
`app-reference-picture`, `app-history`, `app-house`, `app-house-now`, `app-house-flow`,
`app-house-earnings`, `app-heat`, `app-heat-now`, `app-heat-use`, `app-identifying`, `app-identified`, `app-identify-question`.
Whole screens lose the status and gesture bars; settings sections are cropped to their card.

Store (each of en-US, sv-SE, nb-NO, da-DK, fi-FI, de-DE, nl-NL, es-ES, fr-FR, with the app in that
language; whole 1080x2160 screens): `1_main`, `2_plan`, `3_price_table`, `4_widget`, `5_settings`, `6_history`, `7_identify` (the question
which car is plugged in). A full run first
removes the numbered pictures already in those folders, so a folder holds exactly one set.

## Uploading the store pictures

Look at the pictures first; then run the **Upload store screenshots** workflow by hand (GitHub → Actions →
Upload store screenshots → Run workflow, or `gh workflow run store-screenshots.yml`). It runs
`upload_store.py` with the release workflow's Play service account: one Play edit in which each locale's phone
screenshots are replaced by the files in its folder, in name order, committed **without being sent for review**,
so they wait in Play Console's store listing until they are sent for review there by hand. Nothing else in the
listing changes, and a language the listing does not have is skipped and said so.

    python3 tools/app_screenshots/upload_store.py --dry-run          # check against Play's limits, contact nothing
    gh workflow run store-screenshots.yml -f dry_run=true            # the same check on GitHub
    gh workflow run store-screenshots.yml -f locales="sv-SE en-US"   # upload two locales

Play names two of the languages differently from these folders, as the release workflow's changelog step does:
en-US is uploaded as en-GB and nb-NO as no-NO. The release workflow itself never uploads pictures.

## Things that can break

- Pairing types `http://localhost:8123`; the app accepts plain HTTP for loopback, and the approval hands
  back the same address because the setup sets it as Home Assistant's internal URL.
- Prices are the Home Assistant repository's recorded SE4 day moved to today and tomorrow by its demo Nord
  Pool integration (Home Assistant's, sent to the app by `price_days` and the dashboard), so the
  curves are the same every day; the "now" line, the price table's first rows and the plan's times move
  with the clock. The pairing code is random.
- The launcher steps (long press, "Widgets", "Add") follow the Pixel launcher of the android-36 image
  and its English text.
- The home screen's date line is the launcher's, in English in every language.
