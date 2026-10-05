"""Drive the app on the screenshot emulator and write the documentation and store screenshots.

    drive.py [--langs en sv ...] [--docs-langs en ...] [-- shot-name ...]

Environment: SERIAL (an emulator), ADB, APK, OUT (docs/images), STORE_OUT (fastlane/metadata/android),
HA_PORT, APP_HA_PORT, and what ha_setup.mjs needs. Each run starts from a freshly wiped emulator: it
installs the app, takes the unpaired picture, pairs with the demo Home Assistant (approving the request
there through its config flow), confirms the suggested settings once, adds the widget, and then takes
one pass per language. English (and with --docs-langs more) writes docs/images/app-*.png; every
language writes the Play store set into fastlane's layout.
"""

from __future__ import annotations

import argparse
import base64
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb_ui import (  # noqa: E402
    PACKAGE, adb, back, dump, find, find_scrolling, scroll_to_top, screencap, shell, swipe, tap, tap_xy,
    wait_for,
)

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = Path(os.environ.get("OUT", REPO / "docs" / "images"))
STORE_OUT = Path(os.environ.get("STORE_OUT", REPO / "fastlane" / "metadata" / "android"))
HA_PORT = os.environ.get("HA_PORT", "8129")
APP_HA_PORT = os.environ.get("APP_HA_PORT", "8123")
APP_HA_URL = f"http://localhost:{APP_HA_PORT}"
RES = REPO / "app" / "src"

#: The app's languages and the fastlane locale folder each one's store pictures go in.
STORE_LOCALES = {"en": "en-US", "sv": "sv-SE", "nb": "nb-NO", "da": "da-DK", "fi": "fi-FI"}

#: The screen's fixed parts: the status bar above the app and the gesture bar below it.
STATUS_BAR = 63
NAV_BAR = 63

written: list[str] = []
failed: list[str] = []
only: set[str] = set()


START = time.time()


def step(text: str) -> None:
    print(f"== {text} ({time.time() - START:.0f} s)", flush=True)


# ------------------------------------------------------------------------------------------- strings

_strings_cache: dict[str, dict[str, str]] = {}


def strings(lang: str) -> dict[str, str]:
    """The app's strings in [lang] (English underneath), so the driver finds things in every language."""
    if lang in _strings_cache:
        return _strings_cache[lang]
    table: dict[str, str] = {}
    folders = ["values"] + ([] if lang == "en" else [f"values-{lang}"])
    for folder in folders:
        for source in ("main", "play"):
            path = RES / source / "res" / folder / "strings.xml"
            if not path.exists():
                continue
            for e in ET.parse(path).getroot().iter("string"):
                text = "".join(e.itertext())
                table[e.get("name")] = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    _strings_cache[lang] = table
    return table


def exact(text: str) -> str:
    return "^" + re.escape(text) + "$"


# ------------------------------------------------------------------------------------------- pictures

def wanted(name: str) -> bool:
    return not only or name in only


def save(image: Image.Image, path: Path, name: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    written.append(str(path.relative_to(REPO)))
    print(f"   {path.relative_to(REPO)} {image.width}x{image.height}", flush=True)


def grab() -> Image.Image:
    time.sleep(0.8)
    return Image.open(io.BytesIO(screencap())).convert("RGB")


def docs_full(name: str, lang: str) -> None:
    """The app's whole screen, without the status and gesture bars."""
    if not wanted(name):
        return
    image = grab()
    image = image.crop((0, STATUS_BAR, image.width, image.height - NAV_BAR))
    save(image, docs_path(name, lang), name)


def docs_path(name: str, lang: str) -> Path:
    return OUT / (f"{name}.png" if lang == "en" else f"{lang}/{name}.png")


def docs_crop(name: str, lang: str, box: tuple[int, int, int, int], margin: int = 26) -> None:
    if not wanted(name):
        return
    image = grab()
    x1, y1, x2, y2 = box
    save(image.crop((max(0, x1 - margin), max(0, y1 - margin), min(image.width, x2 + margin),
                     min(image.height, y2 + margin))), docs_path(name, lang), name)


def store_shot(index: int, name: str, lang: str) -> None:
    """A whole screen for the Play listing: 1080x2160, status bar in demo mode."""
    if not wanted(f"store-{name}"):
        return
    demo_status_bar()
    folder = STORE_OUT / STORE_LOCALES[lang] / "images" / "phoneScreenshots"
    save(grab(), folder / f"{index}_{name}.png", name)


# ------------------------------------------------------------------------------------------- the device

def prefs(name: str, values: dict[str, str]) -> None:
    """Write one of the app's preference files while it is stopped (debug builds allow run-as)."""
    body = "".join(f'<string name="{k}">{v}</string>' for k, v in values.items())
    xml = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>{body}</map>\n"
    data = base64.b64encode(xml.encode()).decode()
    shell(f"am force-stop {PACKAGE}")
    shell(f"echo {data} | base64 -d | run-as {PACKAGE} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/{name}.xml'")


def demo_status_bar() -> None:
    """A fixed clock, full battery and full signal, and no notification icons."""
    shell("settings put global sysui_demo_allowed 1")
    for command in (
        "-e command enter",
        "-e command clock -e hhmm 1200",
        "-e command battery -e level 100 -e plugged false",
        "-e command network -e wifi show -e level 4 -e fully true -e mobile hide",
        "-e command notifications -e visible false",
    ):
        shell(f"am broadcast -a com.android.systemui.demo {command} >/dev/null")


def prepare_device(apk: str) -> None:
    step("preparing the emulator")
    shell("cmd uimode night yes")
    # No animations: uiautomator waits for an idle screen, and a running animation (a spinning
    # refresh icon) can hold every dump for its full timeout.
    for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        shell(f"settings put global {scale} 0")
    shell("settings put system screen_off_timeout 1800000")
    shell("svc power stayon true")
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard", check=False)
    adb("install", "-r", "-g", apk, timeout=300)
    # The app asks Home Assistant on localhost:8123; that is the host's demo instance.
    adb("reverse", f"tcp:{APP_HA_PORT}", f"tcp:{HA_PORT}")
    demo_status_bar()


def launch(lang: str) -> None:
    prefs("app_language", {"language": lang})
    prefs("app_appearance", {"theme": "dark"})
    shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1 >/dev/null")
    time.sleep(4)


def home() -> None:
    shell("input keyevent KEYCODE_HOME")
    time.sleep(1.5)


# ------------------------------------------------------------------------------------------- scrolling to a card

def card_of(title_pattern: str, nodes) -> tuple[int, int, int, int] | None:
    """The card a heading belongs to: the smallest full-width block around it."""
    heading = find(title_pattern, nodes)
    if heading is None:
        return None
    hx1, hy1, hx2, hy2 = heading.bounds
    best = None
    for n in nodes:
        x1, y1, x2, y2 = n.bounds
        if x1 > 60 or x2 < 1020 or x1 < 40 or x2 > 1040:
            continue
        if not (y1 <= hy1 and y2 >= hy2) or n.cls.endswith("ScrollView"):
            continue
        if best is None or (y2 - y1) < (best[3] - best[1]):
            best = n.bounds
    return best


def bring_card(title_pattern: str, top: int = 250, *, whole: bool = True) -> tuple[int, int, int, int]:
    """Scroll until the card titled [title_pattern] starts near [top], and return its bounds."""
    find_scrolling(title_pattern)
    for _ in range(5):
        nodes = dump()
        box = card_of(title_pattern, nodes)
        if box is None:
            raise RuntimeError(f"no card titled {title_pattern!r}")
        delta = box[1] - top
        if abs(delta) < 12:
            break
        # A slow drag moves the content by the drag's length less the touch slop; a few rounds settle it.
        distance = max(-1300, min(1300, delta))
        start = 1700 if distance > 0 else 400
        swipe(540, start, 540, start - distance - (20 if distance > 0 else -20), 1500, pause=1.0)
        after = card_of(title_pattern, dump())
        if after == box:
            break
    box = card_of(title_pattern, dump())
    if whole and box[3] > 2160 - NAV_BAR - 10:
        print(f"   note: the card {title_pattern!r} runs past the screen", flush=True)
    return box


# ------------------------------------------------------------------------------------------- phases

def standalone() -> None:
    step("unpaired price view")
    launch("en")
    wait_for(exact(strings("en")["card_charging_plan_title"]), timeout=30)
    time.sleep(2)
    docs_full("app-standalone", "en")


def ha(command: str) -> None:
    subprocess.run(["node", str(HERE / "ha_setup.mjs"), command], check=True,
                   env={**os.environ, "APP_HA_URL": APP_HA_URL})


def pair() -> None:
    step("pairing with the demo Home Assistant")
    s = strings("en")
    tap(exact(s["settings"]))
    time.sleep(1.5)
    tap(exact(s["home_assistant_log_in"]))
    field = wait_for(exact(s["instance_address_label"]), timeout=30)
    if field is None:
        raise RuntimeError("the address field did not appear")
    tap_xy(*field.center)
    shell(f"input text {APP_HA_URL}")
    shell("input keyevent KEYCODE_ESCAPE")
    time.sleep(0.6)
    tap(exact(s["instance_use_address"]))
    code = wait_for(r"^\d{6}$", timeout=30)
    if code is None:
        raise RuntimeError("no pairing code appeared")
    box = card_of(exact(s["home_assistant"]), dump())
    docs_crop("app-pairing-code", "en", box)
    ha("approve")
    deadline = time.time() + 60
    while find_scrolling(exact(APP_HA_URL)) is None:
        if time.time() > deadline:
            raise RuntimeError("the app did not show the paired instance")
        time.sleep(2)
    time.sleep(3)
    back()


def confirm_suggestions() -> None:
    """Change the charging current and back, as a person's edit: that confirms the first-run defaults."""
    step("confirming the suggested settings")
    time.sleep(3)
    scroll_to_top()
    for _ in range(2):
        value = wait_for(r"^\d+ A · ", timeout=30)
        if value is None:
            raise RuntimeError("no charging current on the charger card")
        tap_xy(*value.center, pause=1.5)
        nodes = dump()
        seek = next((n for n in nodes if n.cls.endswith("SeekBar")), None)
        if seek is None:
            raise RuntimeError("no slider for the charging current")
        x1, y1, x2, y2 = seek.bounds
        y = (y1 + y2) // 2
        swipe(x2 - 20, y, x2 - 120, y, 600)   # one step down ...
        swipe(x2 - 120, y, x2 + 40, y, 600)   # ... and back to the most
        tap(r"^OK$", scroll=False)
        time.sleep(3)


def add_widget() -> None:
    step("adding the widget")
    home()
    shell("input swipe 540 1300 540 1300 1500")
    time.sleep(1.5)
    tap(r"^Widgets$", scroll=False)
    time.sleep(2)
    entry = find_scrolling(r"^SpotNav$")
    if entry is None:
        raise RuntimeError("SpotNav is not in the widget list")
    tap_xy(*entry.center, pause=1.5)
    cell = find(r"^SpotNav widget")
    if cell is None:
        raise RuntimeError("no SpotNav widget preview")
    tap_xy(*cell.center, pause=1.2)
    tap(r"^Add$", scroll=False)
    time.sleep(4)
    # Full width: a long press shows the resize frame; drag its right handle to the screen's edge.
    home()
    host = widget_box()
    if host is None:
        raise RuntimeError("the widget did not land on the home screen")
    x1, y1, x2, y2 = host
    if x2 < 1000:
        shell(f"input swipe {(x1 + x2) // 2} {(y1 + y2) // 2} {(x1 + x2) // 2} {(y1 + y2) // 2} 1500")
        time.sleep(1.5)
        swipe(x2 + 4, (y1 + y2) // 2, 1065, (y1 + y2) // 2, 1200)
        tap_xy(540, 1400, pause=1.0)
        home()
    if (widget_box() or host)[2] < 1000:
        print("   note: the widget kept its default width", flush=True)


def widget_box():
    return next((n.bounds for n in dump() if n.cls.endswith("AppWidgetHostView")), None)


def redraw_widget() -> None:
    shell(f"am broadcast -a {PACKAGE}.CHART_BOUNDARY -n {PACKAGE}/.widget.PriceWidgetProvider >/dev/null")
    time.sleep(3)


def language_pass(lang: str, docs: bool) -> None:
    s = strings(lang)
    step(f"pictures in {lang}")
    launch(lang)
    redraw_widget()
    launch(lang)
    plan_title = exact(s["card_charging_plan_title"])
    if wait_for(exact(s["card_planning_title"]), timeout=30) is None:
        raise RuntimeError("the main screen did not open")
    time.sleep(3)
    scroll_to_top()
    if docs:
        docs_full("app-main", lang)
    store_shot(1, "main", lang)

    # The planning card's title just under the status bar, so no part of the card above shows cut off.
    bring_card(exact(s["card_planning_title"]), top=120)
    if docs:
        docs_full("app-planning", lang)
    store_shot(2, "plan", lang)
    if docs:
        box = bring_card(plan_title, top=300)
        docs_crop("app-plan-chart", lang, box)

    step("price table")
    tap(exact(s["table"]))
    time.sleep(2.5)
    if docs:
        docs_full("app-price-table", lang)
    store_shot(3, "price_table", lang)
    back()

    step("widget")
    home()
    redraw_widget()
    box = widget_box()
    if box is None:
        failed.append(f"{lang}: no widget on the home screen")
    else:
        if docs:
            docs_crop("app-widget", lang, box, margin=40)
        store_shot(4, "widget", lang)

    step("settings")
    launch(lang)
    scroll_to_top()
    tap(exact(s["settings"]))
    time.sleep(3)
    store_shot(5, "settings", lang)
    if docs:
        sections = [
            ("app-settings-general", exact(s["section_general"])),
            ("app-settings-price", exact(s["section_electricity_price"])),
            ("app-settings-widget", exact(s["section_widget"])),
            ("app-settings-vehicle", r"^Family car$"),
            ("app-settings-charger", exact(s["section_charger"])),
            ("app-settings-site", r"^Home$"),
            ("app-settings-notifications", exact(s["notify_section"])),
            ("app-settings-home-assistant", exact(s["home_assistant"])),
        ]
        for name, title in sections:
            if not wanted(name):
                continue
            try:
                docs_crop(name, lang, bring_card(title))
            except Exception as error:  # noqa: BLE001
                failed.append(f"{lang} {name}: {error}")
    back()

    step("charge history")
    scroll_to_top()
    tap(exact(s["history_row_label"]))
    time.sleep(3)
    # Early in a month the current month has only a few days to show: look at the month before then.
    if time.localtime().tm_mday < 12:
        tap(exact(s["history_previous_month"]), scroll=False)
        time.sleep(3)
    if docs:
        docs_full("app-history", lang)
    store_shot(6, "history", lang)
    back()

    if docs and wanted("app-planning-target"):
        # The planning card with a target charge level instead of an amount, then back to kWh, which
        # the other pictures show.
        step("planning by target")
        scroll_to_top()
        bring_card(exact(s["card_planning_title"]), top=230)
        tap(exact(s["driver_target_soc"]), scroll=False)
        time.sleep(4)
        docs_crop("app-planning-target", lang, bring_card(exact(s["card_planning_title"]), top=230))
        tap(exact(s["driver_kwh"]), scroll=False)
        time.sleep(4)


def clear_store_sets(langs: list[str]) -> None:
    """The store folder holds exactly this run's set: older numbered pictures would be uploaded with it."""
    for lang in langs:
        folder = STORE_OUT / STORE_LOCALES[lang] / "images" / "phoneScreenshots"
        if folder.exists():
            for old in folder.iterdir():
                if re.match(r"^\d+_", old.name):
                    old.unlink()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--langs", nargs="+", default=list(STORE_LOCALES))
    parser.add_argument("--docs-langs", nargs="+", default=["en"])
    parser.add_argument("shots", nargs="*")
    args = parser.parse_args()
    only.update(args.shots)
    if not only:
        clear_store_sets(args.langs)
    prepare_device(os.environ["APK"])
    standalone()
    pair()
    confirm_suggestions()
    add_widget()
    for lang in args.langs:
        try:
            language_pass(lang, docs=lang in args.docs_langs)
        except Exception as error:  # noqa: BLE001
            failed.append(f"{lang}: {error}")
            print(f"   FAILED {lang}: {error}", flush=True)
            back()
            back()
    print(f"\nwritten: {len(written)}  failed: {len(failed)}")
    for f in failed:
        print("FAILED " + f)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
