# App screenshots

Takes the app's documentation pictures (`docs/images/app-*.png`) and the Google Play store pictures
(`fastlane/metadata/android/<locale>/images/phoneScreenshots/`) on a headless emulator, against a
throwaway Home Assistant that runs only on this machine. No phone, real Home Assistant, relay or account
is involved.

    tools/app_screenshots/run.sh                    # everything: docs in English, store set in five languages
    tools/app_screenshots/run.sh --sv               # also Swedish docs pictures, in docs/images/sv/
    tools/app_screenshots/run.sh --langs "en sv"    # store set for these languages only
    tools/app_screenshots/run.sh app-main store-widget   # only the named pictures
    tools/app_screenshots/run.sh --keep             # leave Home Assistant and the emulator up afterwards

Needs the Home Assistant repository next to this one (`../elpris-home-assistant`, or `HA_REPO=...`),
`node` 22 or newer, `python3`, and the Android SDK in `~/Android/Sdk` (or `ANDROID_SDK_ROOT`) with the
emulator and the `android-36` `google_apis_playstore` `x86_64` system image. A run takes about
twenty minutes, most of it the five language passes.

## What it does

1. Starts the Home Assistant repository's own demo pieces from `tools/docs_screenshots` without changing
   that repository: `relay_stub.py` on 127.0.0.1:8130 and `ha_launch.py` (Home Assistant with the relay
   redirected to the stub and synthetic charge history) on 127.0.0.1:8129, with a configuration built
   from nothing in `.work/ha-config` (the demo OCPP charger, Sigenergy plant, two Kia cars, a camera and an
   AI Task entity, and the integration linked in). Its Python environment is reused, or made here from its pinned requirements.
2. `ha_setup.mjs setup` onboards the instance (reusing that tool's `driver/ha.mjs` and `driver/demo.mjs`), adds a
   SpotNav charger ("Garage charger Connector 1") and a site ("Home"), and sets the instance's internal URL to
   `http://localhost:8123`, the address the app is given. Two cars ("Family car", "City car") can charge at the
   charger, identification is **Automatic**, the camera is chosen with its parking bay cropped and a day reference
   picture of each car (the camera's picture is drawn by the demo: no photograph, no number plate), and "Family
   car" is identified by its charging cable.
3. Creates the `SpotNavDocs` emulator once (`make_avd.sh`; the other AVDs are never touched): 1080x2160
   at 420 dpi, because Play wants the long side of a screenshot at most twice the short one. Every run
   boots it wiped, headless, in Europe/Stockholm time.
4. Builds the debug app as the Play variant without Firebase, pointed at the relay stub:
   `./gradlew -Pstore=play -Ppush=false -PrelayBaseUrl=http://10.0.2.2:8130 assembleDebug` (the
   emulator reaches the host's 127.0.0.1 as 10.0.2.2). `-PrelayBaseUrl` is for debug builds only: the
   build refuses it for any other task, a release build compiles in the official address whatever is
   given, and `RelayAddress` ignores a configured address outside a debug build (`RelayAddressTest`,
   and `RelayAddressReleaseTest` under `testReleaseUnitTest`).
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

Logs are in `.work/logs`.

## Pictures

Documentation (English; `--sv` adds `docs/images/sv/`): `app-standalone`, `app-pairing-code`,
`app-main`, `app-planning`, `app-planning-target`, `app-plan-chart`, `app-price-table`, `app-widget`, `app-settings-general`,
`app-settings-price`, `app-settings-widget`, `app-settings-vehicle`, `app-settings-charger`,
`app-settings-site`, `app-settings-notifications`, `app-settings-home-assistant`, `app-camera-frame`,
`app-reference-picture`, `app-history`, `app-identifying`, `app-identified`, `app-identify-question`.
Whole screens lose the status and gesture bars; settings sections are cropped to their card.

Store (each of en-US, sv-SE, nb-NO, da-DK, fi-FI, with the app in that language; whole 1080x2160
screens): `1_main`, `2_plan`, `3_price_table`, `4_widget`, `5_settings`, `6_history`, `7_identify` (the question
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
- Prices are the Home Assistant repository's recorded relay fixture moved to today and tomorrow, so the
  curves are the same every day; the "now" line, the price table's first rows and the plan's times move
  with the clock. The pairing code is random.
- The launcher steps (long press, "Widgets", "Add") follow the Pixel launcher of the android-36 image
  and its English text.
- The home screen's date line is the launcher's, in English in every language.
