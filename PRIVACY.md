# Privacy

SpotNav has no accounts, no analytics, no advertising and no third-party tracking. The developer receives no data from the app.

The full privacy policy, in all the app's languages, is at <https://spotnav.sensnology.se/privacy>. This file is a summary.

## What the app sends, and where

- **SpotNav relay (`spotnav.sensnology.se`)**: the app downloads public electricity prices and the list of price areas. Requests contain the price area and date; no personal data is sent. As with any web request, the server sees your IP address.
- **Octopus Energy (`api.octopus.energy`)**: only when you use "Find my region" for a Great Britain price area, the postcode you type is sent to Octopus Energy's public lookup to find the region. It is sent nowhere else (never to the SpotNav relay), and it is neither saved nor logged by the app.
- **Your own Home Assistant**: if you pair a charger, the app talks to the Home Assistant address you entered, using the webhook IDs it received when you approved the pairing. Charging settings and commands go only there.
- Home Assistant instances on your local network may be discovered with local network discovery (mDNS); this stays on your network.

## What is stored on the device

Language, theme, price area, taxes and fees, widget settings, cached prices, and, if paired, the Home Assistant address, webhook ID and the charger and vehicle settings. Android backup is disabled, so none of this is copied to a cloud backup or transferred to a new device. Uninstalling the app removes it.

Android's system log may contain request URLs, HTTP status codes and price counts for troubleshooting; it holds no personal data from the app.
