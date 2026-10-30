<p align="center">
  <img src="assets/spotnav-social-preview.png" alt="SpotNav: spot prices and smart EV charging">
</p>

SpotNav is an Android home-screen widget and EV charging planner. It shows today's and tomorrow's electricity spot prices on one 24-hour chart, finds the cheapest charging periods and can control a charger through Home Assistant. It covers the European day-ahead price areas, with prices from your own Home Assistant or straight from ENTSO-E, and is available in English, Swedish, Norwegian, Danish, Finnish, German, Dutch, Spanish and French.

<p align="center">
  <img src="docs/images/app-main.png" alt="SpotNav main screen paired with Home Assistant: charger status, vehicle and planning" width="360">
</p>

A tour of every screen, with pictures, is in [docs/app.md](docs/app.md).

## Features

- Today and tomorrow overlaid on one chart, with the current interval, minimum, maximum and current price highlighted.
- Day-ahead prices for 26 European countries, shown as each area publishes them (15, 30 or 60 minutes), in the local currency (EUR, SEK, NOK, DKK, PLN, CZK, HUF, RON, CHF, GBP).
- Optional VAT, electricity tax and grid fee.
- Colour-coded price table.
- EV charging planner: charging current, phases, energy, vehicle consumption, departure time and up to eight charging periods, with the chosen periods shaded in the widget.
- Optional charging control through Home Assistant.
- Resizable widget, local cache for network failures and automatic checks for tomorrow's prices.

## Get it

1. Install SpotNav from [Google Play](https://play.google.com/store/apps/details?id=se.sensnology.spotnav), or download the APK from [Releases](../../releases).
2. Install it and add **SpotNav** from the Android widget picker.
3. Choose language, price area and any taxes or fees. Tap the widget to open the price table and the EV planner.

## EV charging planner

Choose single-phase or three-phase charging (6–16 A), consumption, energy to add, up to eight charging periods and optionally a departure time. The app picks the cheapest combination of quarter-hours and shows cost and range.

The plan uses published prices only. If the charging window runs past them, the app waits for the next day's prices and plans then; when the departure cannot wait that long, it plans now only what cannot wait and the rest once the prices are out. Calculations assume ideal power at 230 V single phase or 400 V three phase; charging losses and the vehicle's charging curve are not included.

## Home Assistant pairing

With the [SpotNav integration](https://github.com/henrikekblad/spotnav-home-assistant) Home Assistant plans and runs the charging schedule itself, so it does not depend on the phone staying online.

1. Install the integration through HACS and pick your charger during setup.
2. In SpotNav, open **Settings → Home Assistant** and tap **Find Home Assistant on the network** (or type its address).
3. The app shows a six-digit code, and Home Assistant shows the same code under **Settings → Devices & services**. Check that they match and approve the phone there; every charger on that instance is paired at once.

The app receives one secret webhook ID per charger, never a Home Assistant password or token. HTTPS is required for internet addresses; local addresses may use HTTP. Details, entities and configuration are in the [integration repository](https://github.com/henrikekblad/spotnav-home-assistant).

## Prices

The app gets prices in one of two ways and shows them in the area's own currency:

- **Paired with Home Assistant**: from the SpotNav integration, which gets them through your Home Assistant's own price integrations (Nord Pool, Spain's PVPC, Octopus Energy for Great Britain's Agile) or your ENTSO-E key there. The app needs no key.
- **Without Home Assistant**: day-ahead spot prices straight from the [ENTSO-E Transparency Platform](https://transparency.entsoe.eu/) with your own free ENTSO-E key, converted with the ECB's daily exchange rates. The app shows step by step how to get a key, and stores it encrypted on the phone. Areas ENTSO-E does not publish (Spain's PVPC, Great Britain's Agile) need Home Assistant.

A spot price has no VAT, tax or grid fee. The enabled additions are applied as:

```text
(spot price + electricity tax + grid fee) × (1 + VAT)
```

A part the price already includes (PVPC's network charges, everything in Agile) is shown as "Included in the price" and not added again. Suggested VAT and tax are per price area and editable, and a few areas also suggest a grid fee; grid fees depend on your network operator and agreement, and fixed monthly charges are not included, so check the values against your contract. More detail is in [SpotNav for Home Assistant's price documentation](https://github.com/henrikekblad/spotnav-home-assistant/blob/main/docs/prices.md).

When the app is paired with Home Assistant, the price settings are the integration's, and the app shows the same prices and plan as the dashboard card.

## Build locally

Requires JDK 17 and Android SDK 36.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. See [PRIVACY.md](PRIVACY.md) for what the app stores and sends.

## Disclaimer

SpotNav is not affiliated with ENTSO-E, the ECB, Nord Pool, electricity retailers or grid operators. Prices and charging calculations are guidance only; always verify them against your agreement and invoice.

## License

[MIT License](LICENSE).
