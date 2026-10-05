"""Small adb helpers for driving the app on the screenshot emulator: every command names its serial.

Elements are found in `uiautomator dump` output by text, content description or resource id.
"""

from __future__ import annotations

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass

SERIAL = os.environ.get("SERIAL", "emulator-5584")
ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
PACKAGE = "se.sensnology.spotnav"

if not SERIAL.startswith("emulator-"):
    raise SystemExit(f"refusing to drive {SERIAL}: the screenshot tool only drives an emulator")


def adb(*args: str, check: bool = True, binary: bool = False, timeout: float = 120):
    out = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    if check and out.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {out.stderr.decode(errors='replace').strip()}")
    return out.stdout if binary else out.stdout.decode(errors="replace")


def shell(command: str, check: bool = True) -> str:
    return adb("shell", command, check=check)


@dataclass
class Node:
    text: str
    desc: str
    rid: str
    cls: str
    bounds: tuple[int, int, int, int]
    clickable: bool
    scrollable: bool
    checked: bool

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def dump() -> list[Node]:
    """Every node on screen, in document order."""
    for _ in range(5):
        started = time.time()
        raw = shell("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", check=False)
        if time.time() - started > 15:
            print(f"   note: a screen dump took {time.time() - started:.0f} s", flush=True)
        if "<hierarchy" in raw:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("uiautomator dump failed")
    root = ET.fromstring(raw[raw.index("<?xml") if "<?xml" in raw else raw.index("<hierarchy"):])
    nodes = []
    for e in root.iter("node"):
        m = _BOUNDS.match(e.get("bounds", ""))
        if not m:
            continue
        nodes.append(Node(
            text=e.get("text", ""), desc=e.get("content-desc", ""), rid=e.get("resource-id", ""),
            cls=e.get("class", ""), bounds=tuple(int(v) for v in m.groups()),
            clickable=e.get("clickable") == "true", scrollable=e.get("scrollable") == "true",
            checked=e.get("checked") == "true",
        ))
    return nodes


def find(pattern: str, nodes: list[Node] | None = None, *, cls: str | None = None) -> Node | None:
    """The first visible node whose text or description matches the regular expression [pattern]."""
    rx = re.compile(pattern)
    for n in nodes if nodes is not None else dump():
        if cls and not n.cls.endswith(cls):
            continue
        x1, y1, x2, y2 = n.bounds
        if x2 <= x1 or y2 <= y1:
            continue
        if (n.text and rx.search(n.text)) or (n.desc and rx.search(n.desc)) or (n.rid and rx.fullmatch(n.rid)):
            return n
    return None


def tap_xy(x: int, y: int, pause: float = 0.8) -> None:
    shell(f"input tap {x} {y}")
    time.sleep(pause)


def tap(pattern: str, *, cls: str | None = None, pause: float = 1.0, scroll: bool = True, timeout: float = 10) -> Node:
    """Tap the node matching [pattern], scrolling down to it when it is not on screen yet."""
    deadline = time.time() + timeout
    while True:
        n = wait_for(pattern, cls=cls, timeout=1.5) if not scroll else find_scrolling(pattern, cls=cls)
        if n:
            tap_xy(*n.center, pause=pause)
            return n
        if time.time() > deadline:
            raise RuntimeError(f"nothing on screen matches {pattern!r}")


def wait_for(pattern: str, *, cls: str | None = None, timeout: float = 15) -> Node | None:
    deadline = time.time() + timeout
    while True:
        n = find(pattern, cls=cls)
        if n or time.time() > deadline:
            return n
        time.sleep(0.6)


def screen_size() -> tuple[int, int]:
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return int(m.group(1)), int(m.group(2))


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 400, pause: float = 0.8) -> None:
    shell(f"input swipe {x1} {y1} {x2} {y2} {ms}")
    time.sleep(pause)


def scroll_down(fraction: float = 0.5) -> None:
    w, h = screen_size()
    swipe(w // 2, int(h * 0.75), w // 2, int(h * (0.75 - fraction)), 500)


def scroll_to_top() -> None:
    w, h = screen_size()
    for _ in range(6):
        swipe(w // 2, int(h * 0.3), w // 2, int(h * 0.9), 200, pause=0.2)
    time.sleep(0.6)


def find_scrolling(pattern: str, *, cls: str | None = None, max_scrolls: int = 8) -> Node | None:
    """[find], scrolling down a screen at a time until it appears or the screen stops moving."""
    _, h = screen_size()
    last = None
    for _ in range(max_scrolls + 1):
        nodes = dump()
        n = find(pattern, nodes, cls=cls)
        # Something at the very bottom edge is under the navigation bar: scroll it up first.
        if n and n.bounds[3] < h - 140:
            return n
        signature = [(x.text, x.bounds) for x in nodes]
        if signature == last:
            return n
        last = signature
        scroll_down(0.45)
    return None


def screencap() -> bytes:
    return adb("exec-out", "screencap -p", binary=True)


def back(pause: float = 1.0) -> None:
    shell("input keyevent KEYCODE_BACK")
    time.sleep(pause)
