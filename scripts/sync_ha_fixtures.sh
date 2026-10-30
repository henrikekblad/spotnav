#!/usr/bin/env bash
# Vendor the Home Assistant integration's contract fixtures into the app's test resources.
#
#   scripts/sync_ha_fixtures.sh [path/to/elpris-home-assistant/tests/fixtures]
#   scripts/sync_ha_fixtures.sh --ref <branch or commit> [path/to/elpris-home-assistant]
#
# The default source is ../elpris-home-assistant/tests/fixtures next to this repository. With --ref the
# fixtures and the catalogue are taken from that commit of the integration's repository instead of its
# working tree, so a branch can be vendored without checking it out. Only the
# parts the app reads are copied (dashboard/, settings/v1/, site_settings/v1/, vehicle/v1/, sessions/, webhook/,
# codes/dashboard_codes.json); the VendoredHaFixturesTest fails when a copy differs from the source,
# so run this after the HA side changes a fixture. The integration's area catalogue
# (custom_components/spotnav/pricing/areas.json) is copied to the app's assets as its bundled area list.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "${1:-}" = "--ref" ]; then
    ref="${2:?--ref needs a branch or commit}"
    repo="${3:-$root/../elpris-home-assistant}"
    work="$(mktemp -d)"
    trap 'rm -rf -- "$work"' EXIT
    git -C "$repo" archive "$ref" tests/fixtures custom_components/spotnav/pricing/areas.json | tar -x -C "$work"
    src="$work/tests/fixtures"
    echo "Taking the fixtures from $ref"
else
    src="${1:-$root/../elpris-home-assistant/tests/fixtures}"
fi
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

areas="$src/../../custom_components/spotnav/pricing/areas.json"
if [ -f "$areas" ]; then
    cp "$areas" "$root/app/src/main/assets/areas.json"
    echo "Copied the area catalogue into app/src/main/assets/areas.json"
else
    echo "No area catalogue at $areas; app/src/main/assets/areas.json left as it is" >&2
fi
