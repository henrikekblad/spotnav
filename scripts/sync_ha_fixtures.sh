#!/usr/bin/env bash
# Vendor the Home Assistant integration's contract fixtures into the app's test resources.
#
#   scripts/sync_ha_fixtures.sh [path/to/elpris-home-assistant/tests/fixtures]
#
# The default source is ../elpris-home-assistant/tests/fixtures next to this repository. Only the
# parts the app reads are copied (dashboard/, settings/v1/, site_settings/v1/, vehicle/v1/, sessions/, webhook/,
# codes/dashboard_codes.json); the VendoredHaFixturesTest fails when a copy differs from the source,
# so run this after the HA side changes a fixture.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="${1:-$root/../elpris-home-assistant/tests/fixtures}"
dst="$root/app/src/test/resources/ha-fixtures"

if [ ! -d "$src" ]; then
    echo "No fixtures at $src" >&2
    exit 1
fi

rm -rf "$dst/webhook" "$dst/dashboard" "$dst/codes" "$dst/settings" "$dst/site_settings" "$dst/vehicle" "$dst/sessions"
mkdir -p "$dst/webhook" "$dst/dashboard" "$dst/codes" "$dst/settings/v1" "$dst/site_settings/v1" "$dst/vehicle/v1" "$dst/sessions"
cp "$src"/webhook/*.json "$dst/webhook/"
cp "$src"/dashboard/*.json "$dst/dashboard/"
cp "$src"/settings/v1/*.json "$dst/settings/v1/"
cp "$src"/site_settings/v1/*.json "$dst/site_settings/v1/"
cp "$src"/vehicle/v1/*.json "$dst/vehicle/v1/"
cp "$src"/sessions/*.json "$dst/sessions/"
cp "$src"/codes/dashboard_codes.json "$dst/codes/"
echo "Vendored $(find "$dst" -type f | wc -l) fixtures into $dst"
