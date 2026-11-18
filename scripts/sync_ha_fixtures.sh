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
# grid_tariff/, power_month/, house/, house_now/, battery_control/, heat_card/, codes/dashboard_codes.json); the VendoredHaFixturesTest fails when a copy differs from the source,
# so run this after the HA side changes a fixture. The commit the copy was taken from is written to ha-fixtures/REF,
# and the test compares only against a checkout at that commit. The integration's area catalogue
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
    commit="$(git -C "$repo" rev-parse "$ref^{commit}")"
    echo "Taking the fixtures from $ref"
else
    src="${1:-$root/../elpris-home-assistant/tests/fixtures}"
    # The commit the working tree is at; empty when the source is no git checkout.
    commit="$(git -C "$src" rev-parse HEAD 2>/dev/null || true)"
fi
dst="$root/app/src/test/resources/ha-fixtures"

if [ ! -d "$src" ]; then
    echo "No fixtures at $src" >&2
    exit 1
fi

rm -rf "$dst/webhook" "$dst/dashboard" "$dst/codes" "$dst/settings" "$dst/site_settings" "$dst/vehicle" "$dst/sessions" "$dst/grid_tariff" "$dst/power_month" "$dst/house" "$dst/house_now" "$dst/battery_control" "$dst/heat_card"
mkdir -p "$dst/webhook" "$dst/dashboard" "$dst/codes" "$dst/settings/v1" "$dst/site_settings/v1" "$dst/vehicle/v1" "$dst/sessions"
cp "$src"/webhook/*.json "$dst/webhook/"
cp "$src"/dashboard/*.json "$dst/dashboard/"
cp "$src"/settings/v1/*.json "$dst/settings/v1/"
cp "$src"/site_settings/v1/*.json "$dst/site_settings/v1/"
cp "$src"/vehicle/v1/*.json "$dst/vehicle/v1/"
cp "$src"/sessions/*.json "$dst/sessions/"
cp "$src"/codes/dashboard_codes.json "$dst/codes/"
# The grid tariff's examples (the demand charge since 2.4); an older integration has none.
if [ -d "$src/grid_tariff" ]; then
    mkdir -p "$dst/grid_tariff"
    cp "$src"/grid_tariff/*.json "$dst/grid_tariff/"
fi
# One month of a site's power, with its hours and without (since 2.4).
if [ -d "$src/power_month" ]; then
    mkdir -p "$dst/power_month"
    cp "$src"/power_month/*.json "$dst/power_month/"
fi
# The house page's documents (since 3.0): the house (`get_house`) and its live flows (`get_house_now`), as the
# WebSocket sends them; their webhook twins are in webhook/. The battery control's status (`battery_control/`) and the
# heat card's view (`heat_card/`) are the richer examples of the documents the Settings screen reads.
for part in house house_now battery_control heat_card; do
    if [ -d "$src/$part" ]; then
        mkdir -p "$dst/$part"
        cp "$src/$part"/*.json "$dst/$part/"
    fi
done
# The integration commit the copy was taken from: VendoredHaFixturesTest compares the copy with the integration's
# checkout next to this repository only while that checkout is at this commit.
if [ -n "$commit" ]; then
    echo "$commit" > "$dst/REF"
else
    rm -f "$dst/REF"
fi
echo "Vendored $(find "$dst" -type f -name "*.json" | wc -l) fixtures into $dst"

areas="$src/../../custom_components/spotnav/pricing/areas.json"
if [ -f "$areas" ]; then
    cp "$areas" "$root/app/src/main/assets/areas.json"
    echo "Copied the area catalogue into app/src/main/assets/areas.json"
else
    echo "No area catalogue at $areas; app/src/main/assets/areas.json left as it is" >&2
fi
