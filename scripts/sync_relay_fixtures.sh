#!/usr/bin/env bash
# Vendor the relay's upstream captures (ENTSO-E documents, the ECB's rates) and one frozen day file into the
# app's test resources.
#
#   scripts/sync_relay_fixtures.sh [path/to/spotnav-relay/testdata/upstream]
#
# The default source is ../spotnav-relay/testdata/upstream next to this repository. Only the files the
# app's parsers are tested against are copied; the VendoredRelayFixturesTest fails when a copy differs
# from the source, so run this after the relay changes one of them.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="${1:-$root/../spotnav-relay/testdata/upstream}"
dst="$root/app/src/test/resources/relay-upstream"

if [ ! -d "$src" ]; then
    echo "No captures at $src" >&2
    exit 1
fi

rm -rf "$dst"
mkdir -p "$dst"
cp "$src"/ecb_daily.xml "$dst/"
cp "$src"/entsoe_*.xml "$src"/synthetic_a44_*.xml "$src"/synthetic_ack_*.xml "$dst/"
echo "Vendored $(find "$dst" -type f | wc -l) captures into $dst"

# The relay's frozen day file for the SE4 capture: what the app's own day document must equal byte for byte.
golden="$root/app/src/test/resources/relay-golden"
rm -rf "$golden"
mkdir -p "$golden"
cp "$src"/../golden/v1_day_SE4.json "$golden/"
