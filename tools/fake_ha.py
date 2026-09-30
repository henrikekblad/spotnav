#!/usr/bin/env python3
"""A fake Home Assistant for looking at the app's screens.

The app reaches Home Assistant through exactly one door: a POST to
`<base>/api/webhook/<id>` carrying `{"version": 1, "action": ...}`. That is
the whole contract, so a stand-in for it needs nothing from Home Assistant
itself -- no install, no config entry, no real charger, no car. This serves
that door for several made-up chargers at once, each on its own webhook id,
so the screens that are hard to reach with one real charger (a site the
connection may or may not write, two cars, no settings record) can simply be
looked at.

It is a dev tool and deliberately dumb: no auth, no TLS, state in memory,
gone when it stops. Run it on the machine the phone shares a network with:

    python3 tools/fake_ha.py                 # serves on 127.0.0.1:8099
    python3 tools/fake_ha.py --host 0.0.0.0  # reachable from a phone on the same network

then add one charger per scenario in the app's settings, using this machine's
address as the base URL and the scenario name as the webhook id:

    base URL     http://<this machine's address>:8099
    webhook id   full          (or basic, nostop, conflict, novehicle, busy)

`python3 tools/fake_ha.py --list` prints every scenario and what it is for.

A debug build can also *find* this rather than being told where it is: it
broadcasts `SPOTNAV-MOCK-DISCOVER` on UDP <port> and gets back the catalogue
(`/spotnav-mock/scenarios` serves the same thing over HTTP, for a phone told
the address by hand). Both need the port open -- this machine runs ufw with a
default-deny INPUT policy, so until

    sudo ufw allow 8099

the phone's requests are dropped before they reach anything here, and so are
its discovery probes. That is also why a broadcast probe sent from this very
machine gets no answer while a unicast one to 127.0.0.1 does: the loopback
path is allowed, the rest is not.

The one read the app makes, `dashboard`, answers a contract fixture the
integration's own tests are pinned to (see DASHBOARD_FIXTURES), so what the
app parses here is what it parses in real life; each scenario picks the
fixture that puts the screen it is for on display.
"""

from __future__ import annotations

import argparse
import json
import html
import re
import secrets
import socket
import threading
import time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

#: The `dashboard` action answers a vendored contract fixture (`scripts/sync_ha_fixtures.sh` keeps
#: them equal to the integration's own), so the app parses here exactly what Home Assistant sends.
DASHBOARD_FIXTURES = Path(__file__).resolve().parent.parent / "app/src/test/resources/ha-fixtures/dashboard"
DASHBOARD_BY_SCENARIO = {
    "full": "target_soc_two_vehicles.json",
    "basic": "cheapest_no_site.json",
    "nostop": "cheapest_direct_site_read_only.json",
    "conflict": "cheapest_direct_site_admin.json",
    "novehicle": "no_settings.json",
    "busy": "stop_charging.json",
}


def dashboard_body(scenario: str) -> dict:
    name = DASHBOARD_BY_SCENARIO.get(scenario, "cheapest_no_site.json")
    return json.loads((DASHBOARD_FIXTURES / name).read_text(encoding="utf-8"))


class Charger:
    """One made-up charger: what it is called and what it is for, and the one
    little state the app can change through the commands it sends.

    `charging` is the charge-control switch, exactly as `controller.charging`
    is -- on, not "current is flowing". The dashboard the app reads is a
    fixture per scenario (see DASHBOARD_BY_SCENARIO), so the switch only
    records what was asked.
    """

    def __init__(self, name: str, *, why: str, charging: bool = False) -> None:
        self.name = name
        self.why = why
        self.charging = charging

    def command(self, action: str, payload: dict) -> None:
        if action == "start":
            self.charging = True
        elif action == "stop":
            self.charging = False
        else:
            raise ValueError("Unsupported action")


SCENARIOS: dict[str, Charger] = {
    "full": Charger(
        "Garaget",
        why="target-SoC planning with two cars: the vehicle picker, capacities and the plan card",
    ),
    "basic": Charger(
        "Gamla laddaren",
        why="the plainest charger: cheapest strategy, no site and no car to speak of",
    ),
    "nostop": Charger(
        "Utan stopp",
        why="a site the connection may only read: the read-only site section",
        charging=True,
    ),
    "conflict": Charger(
        "Fel faser",
        why="a site the connection may write: the solar priority and forecast controls",
    ),
    "novehicle": Charger(
        "Inga bilar",
        why="a charger Home Assistant has no settings record for: the empty paired states",
    ),
    "busy": Charger(
        "Laddar nu",
        why="the charge switch is on with a plan installed: the Stop action and its status line",
        charging=True,
    ),
}

_lock = threading.Lock()
_PATH = re.compile(r"^/api/webhook/([^/?#]+)$")

# --- the pairing handshake ------------------------------------------------
#
# The same three-step exchange the integration implements (`pairing.py`), so
# the app's "Log in to Home Assistant" button can be driven end to end without
# touching a real Home Assistant. What a real instance puts in front of a
# person as a config-flow card, this puts on a web page at /pairing: the
# device, the six digits it is showing, and Approve / Deny.
#
# The semantics are copied deliberately, including the unfriendly ones -- an
# approval is delivered once, and unknown/delivered/expired all answer
# "expired" -- because a mock that is more forgiving than the real thing
# teaches the app to be wrong.

PAIRING_WEBHOOK_ID = "spotnav_pairing"
PAIRING_TIMEOUT_S = 300.0
MAX_PENDING_REQUESTS = 8


class PairingRegister:
    """Pending pairing requests, in memory, exactly as the integration keeps them."""

    def __init__(self) -> None:
        self._open: dict[str, dict] = {}
        self._decided: dict[str, str] = {}

    def open(self, code: str, device: str) -> str | None:
        self._prune()
        if len(self._open) >= MAX_PENDING_REQUESTS:
            return None
        request_id = secrets.token_urlsafe(32)
        self._open[request_id] = {
            "code": code,
            "device": device,
            "created_at": time.monotonic(),
        }
        return request_id

    def pending(self) -> list[tuple[str, dict]]:
        self._prune()
        return [(rid, record) for rid, record in self._open.items() if rid not in self._decided]

    def decide(self, request_id: str, verdict: str) -> bool:
        self._prune()
        if request_id not in self._open:
            return False
        self._decided[request_id] = verdict
        return True

    def take(self, request_id: str) -> str:
        """`pending`, then `approved`/`denied` exactly once, then `expired`."""
        self._prune()
        verdict = self._decided.pop(request_id, None)
        if verdict is not None:
            self._open.pop(request_id, None)
            return verdict
        return "pending" if request_id in self._open else "expired"

    def _prune(self) -> None:
        cutoff = time.monotonic() - PAIRING_TIMEOUT_S
        for request_id in [r for r, rec in self._open.items() if rec["created_at"] <= cutoff]:
            self._open.pop(request_id, None)
            self._decided.pop(request_id, None)


PAIRING = PairingRegister()


def paired_chargers() -> list[dict]:
    """What an approval hands over: every scenario, as the integration hands
    over every charger config entry -- `id`, `name` and that one's own webhook.
    """
    return [
        {"id": key, "name": charger.name, "webhook": key}
        for key, charger in SCENARIOS.items()
    ]

# What a debug build broadcasts to find this, and what comes back. Deliberately
# nothing like Home Assistant's own discovery: this announces a *mock*, and a
# release build must never look for one.
DISCOVERY_PROBE = b"SPOTNAV-MOCK-DISCOVER"
DISCOVERY_SERVICE = "spotnav-mock"
DISCOVERY_VERSION = 1


def catalogue(base_url: str) -> dict:
    """Every scenario this server can be, and where to reach it.

    `webhook_id` is the whole address of a scenario -- the app builds
    `<base_url>/api/webhook/<webhook_id>` exactly as it does for a real
    charger -- so a picker needs nothing from here but this list.
    """
    return {
        "service": DISCOVERY_SERVICE,
        "version": DISCOVERY_VERSION,
        "base_url": base_url,
        "scenarios": [
            {"webhook_id": key, "name": charger.name, "why": charger.why}
            for key, charger in SCENARIOS.items()
        ],
    }


def _local_address_towards(peer: tuple[str, int], port: int) -> str:
    """This machine's address *on the network the probe came from*.

    A dev laptop commonly has several (wifi, docker bridges, a VPN), and the
    only one that is any use to the phone is the one that can reach it. A
    connected UDP socket picks it by the kernel's own routing decision rather
    than by guessing at the first non-loopback interface.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(peer)
        return f"http://{probe.getsockname()[0]}:{port}"
    finally:
        probe.close()


def advertise_mdns(port: int) -> None:
    """Announce this as a Home Assistant over mDNS, so the app's own
    "Log in to Home Assistant" button finds it.

    The app looks for `_home-assistant._tcp`, which is what a real instance
    advertises -- so being discoverable means advertising exactly that rather
    than something of our own. Optional on purpose: `zeroconf` is not in the
    standard library, and everything else here is, so without it the server
    still runs and the app's typed-address fallback still reaches it. That
    fallback is worth exercising anyway; this just makes the happy path
    demonstrable.
    """
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        print("  (no zeroconf module: the app will not discover this by itself;")
        print("   use its typed-address fallback instead)")
        return
    address = _local_address_towards(("10.255.255.255", 1), port).split("//")[1].split(":")[0]
    info = ServiceInfo(
        "_home-assistant._tcp.local.",
        "SpotNav mock._home-assistant._tcp.local.",
        addresses=[socket.inet_aton(address)],
        port=port,
        properties={"base_url": f"http://{address}:{port}", "version": "mock"},
        server=f"spotnav-mock-{port}.local.",
    )
    zeroconf = Zeroconf()
    zeroconf.register_service(info)
    print(f"  advertising _home-assistant._tcp as {address}:{port}")
    # Registered for the life of the process; the OS releases it on exit.


def serve_discovery(port: int) -> None:
    """Answer broadcast probes with the catalogue, forever.

    UDP on the same port number as the HTTP server, which is a different
    port -- they cannot collide. The reply is unicast straight back to the
    asker, so the phone hears it on its own ephemeral socket and needs no
    multicast lock.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))
    while True:
        try:
            data, sender = sock.recvfrom(1024)
        except OSError:
            return
        if not data.startswith(DISCOVERY_PROBE):
            continue
        body = catalogue(_local_address_towards(sender, port))
        sock.sendto(json.dumps(body).encode(), sender)
        print(f"  discovery -> {sender[0]} ({len(body['scenarios'])} scenarios)")


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeHA/1.0"

    def do_GET(self) -> None:  # noqa: N802 -- BaseHTTPRequestHandler's spelling
        """The catalogue, and the page where a pairing request is approved.

        Discovery is the convenience; the catalogue is the fallback that always
        works, and what `curl` reaches for. `/pairing` is what a real Home
        Assistant shows as a card in Settings -> Devices & Services: the device,
        the code it claims to be showing, and two buttons.
        """
        path = self.path.split("?")[0]
        if path == "/spotnav-mock/scenarios":
            self._json(200, catalogue(self._base_url()))
            return
        if path == "/pairing":
            self._pairing_page()
            return
        decision = re.match(r"^/pairing/(approve|deny)/([A-Za-z0-9_-]+)$", path)
        if decision:
            with _lock:
                PAIRING.decide(decision.group(2), "approved" if decision.group(1) == "approve" else "denied")
            self.send_response(303)
            self.send_header("Location", "/pairing")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._json(404, {"ok": False, "error": "Not found"})

    def _base_url(self) -> str:
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        return f"http://{host}"

    def _pairing_page(self) -> None:
        """The approval screen, standing in for Home Assistant's own.

        The code is shown large because its whole job is to be compared with
        the one on the phone -- approving without looking is the one mistake
        this design exists to make unlikely.
        """
        with _lock:
            waiting = PAIRING.pending()
        rows = "".join(
            f"<li><b>{html.escape(record['device'])}</b> shows "
            f"<span class=code>{html.escape(record['code'])}</span>"
            f" &nbsp; <a href='/pairing/approve/{rid}'>Approve</a>"
            f" &nbsp; <a href='/pairing/deny/{rid}'>Deny</a></li>"
            for rid, record in waiting
        ) or "<li>Nothing is waiting. Press <i>Log in to Home Assistant</i> in the app.</li>"
        body = (
            "<!doctype html><meta charset=utf-8><meta name=viewport "
            "content='width=device-width,initial-scale=1'>"
            "<title>Fake Home Assistant pairing</title>"
            "<style>body{font:16px system-ui;margin:2rem;max-width:40rem}"
            ".code{font-size:2rem;letter-spacing:.2em;font-family:monospace}"
            "li{margin:1.5rem 0}a{margin-right:.5rem}</style>"
            "<h1>Pairing requests</h1><p>Approve only if the same code is on the "
            "phone's screen.</p><ul>" + rows + "</ul>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, body: dict) -> None:
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:  # noqa: N802 -- BaseHTTPRequestHandler's spelling
        match = _PATH.match(self.path)
        if not match:
            # Home Assistant answers an unknown webhook with 200 and no body,
            # deliberately, so the id cannot be probed. Not worth emulating in
            # a dev tool, where a 404 is the more useful answer.
            self._json(404, {"ok": False, "error": "No such webhook"})
            return
        if match.group(1) == PAIRING_WEBHOOK_ID:
            self._pairing(self._body())
            return
        charger = SCENARIOS.get(match.group(1))
        if charger is None:
            self._json(404, {"ok": False, "error": f"Unknown scenario {match.group(1)!r}"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
            if payload.get("version") != 1:
                raise ValueError("Unsupported payload version")
            action = payload.get("action")
            with _lock:
                if action == "dashboard":
                    self._json(200, dashboard_body(match.group(1)))
                    return
                charger.command(action, payload)
        except (KeyError, TypeError, ValueError) as error:
            self._json(400, {"ok": False, "error": str(error)})
            return
        self._json(200, {"ok": True, "action": action})

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def _pairing(self, payload: dict) -> None:
        """The handshake, with the integration's own answers -- including the
        unfriendly ones. A mock that is more forgiving than the real thing
        teaches the app to be wrong.
        """
        try:
            if not isinstance(payload, dict):
                raise ValueError("Payload must be a JSON object")
            if payload.get("version") != 1:
                raise ValueError("Unsupported payload version")
            action = payload.get("action")
            if action == "request":
                code = payload.get("code")
                if not isinstance(code, str) or len(code) != 6 or not code.isdigit():
                    raise ValueError("A six-digit code is required")
                device = payload.get("device")
                label = device.strip() if isinstance(device, str) and device.strip() else "(unknown device)"
                with _lock:
                    request_id = PAIRING.open(code, label)
                if request_id is None:
                    raise ValueError("Too many pairing requests are awaiting approval")
                print(f"\n  *** {label} wants to pair, showing code {code}")
                print(f"  *** approve at {self._base_url()}/pairing\n")
                self._json(200, {"ok": True, "request_id": request_id})
                return
            if action == "poll":
                request_id = payload.get("request_id")
                if not isinstance(request_id, str) or not request_id:
                    raise ValueError("A request id is required")
                with _lock:
                    verdict = PAIRING.take(request_id)
                if verdict == "approved":
                    self._json(200, {
                        "status": "approved",
                        "url": self._base_url(),
                        "chargers": paired_chargers(),
                    })
                    return
                self._json(200, {"status": verdict})
                return
            raise ValueError("Unsupported action")
        except (KeyError, TypeError, ValueError) as error:
            self._json(400, {"ok": False, "error": str(error)})

    def log_message(self, fmt: str, *args) -> None:
        print(f"  {self.address_string()} {fmt % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--list", action="store_true", help="print the scenarios and exit")
    args = parser.parse_args()

    if args.list:
        width = max(len(name) for name in SCENARIOS)
        for name, charger in SCENARIOS.items():
            print(f"{name:<{width}}  {charger.name} -- {charger.why}")
        return

    print(f"Fake Home Assistant on http://{args.host}:{args.port}")
    print(f"Discovery listening on UDP {args.port}; catalogue at /spotnav-mock/scenarios")
    print(f"Pairing: approve requests at http://<this machine>:{args.port}/pairing")
    advertise_mdns(args.port)
    print("Add one charger per scenario, webhook id = scenario name:\n")
    width = max(len(name) for name in SCENARIOS)
    for name, charger in SCENARIOS.items():
        print(f"  {name:<{width}}  {charger.name} -- {charger.why}")
    print()
    threading.Thread(target=serve_discovery, args=(args.port,), daemon=True).start()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
