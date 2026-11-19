---
title: Privacy
description: What the app sends, where it goes, and what it stores on the phone.
order: 8
---

# Privacy

SpotNav has no accounts, no analytics, no advertising and no third-party tracking. The developer receives no data
from the app.

The full privacy policy, in all the app's languages, is at <https://spotnav.sensnology.se/privacy>. This page is a
summary.

## What the app sends, and where

- **Your own Home Assistant.** A paired app talks only to the Home Assistant address you gave it, with the webhook
  IDs it got when you approved the phone. It never gets a Home Assistant password or token. Prices, settings,
  commands, the house and the heating all go between the app and your Home Assistant.
- **ENTSO-E.** Without Home Assistant, the app fetches day-ahead prices from the ENTSO-E Transparency Platform with
  your own key. ENTSO-E sees the price area, the dates, your key and your IP address. The key is stored encrypted on
  the phone and is never sent to the developer.
- **European Central Bank.** Without Home Assistant, the app downloads the ECB's public exchange rates. No personal
  data is sent.
- **Octopus Energy.** Only when you use **Find my region** for a Great Britain price area: the postcode you type is
  sent to Octopus Energy's public lookup. It is not saved.
- **Instant notifications** (off by default). The app gets a push token from Google Firebase and sends it to the
  SpotNav server, which answers with an encrypted reference and stores nothing. Your Home Assistant uses that
  reference to send an empty wake-up. Nothing about your charging passes through the server or Google. Turning it off
  deletes the token.
- **Your local network.** The app may look for Home Assistant on your local network (mDNS). This stays on your
  network.

## What is stored on the phone

Language, theme, price area, taxes and fees, widget settings, chart choices, cached prices and exchange rates, and,
without Home Assistant, your ENTSO-E key (encrypted). When paired: the Home Assistant address, the webhook IDs and
the charger and vehicle settings. Android backup is off, so none of this goes to a cloud backup. Uninstalling the app
removes it.
