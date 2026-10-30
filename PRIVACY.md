# Privacy

Last updated: 2026-10-31

SpotNav has no accounts, no analytics, no advertising and no third-party tracking. The developer receives no data from the app.

The full privacy policy, in all the app's languages, is at <https://spotnav.sensnology.se/privacy>. This file is a summary.

## What the app sends, and where

- **Your own Home Assistant**: if you pair a charger, the app talks to the Home Assistant address you entered, using the webhook IDs it received when you approved the pairing. Your Home Assistant supplies the prices for the paired chargers, and charging settings and commands go only there.
- **ENTSO-E (`web-api.tp.entsoe.eu`)**: without Home Assistant, the app fetches day-ahead prices directly from the ENTSO-E Transparency Platform with your own ENTSO-E key. Requests contain the price area, the dates and your key, so ENTSO-E sees your IP address and your key. The key is stored encrypted on the phone and is never sent to the developer or the SpotNav relay.
- **European Central Bank (`www.ecb.europa.eu`)**: without Home Assistant, the app downloads the ECB's public exchange rates to show prices in the area's own currency. No personal data is sent; as with any web request, the ECB sees your IP address.
- **Octopus Energy (`api.octopus.energy`)**: only when you use "Find my region" for a Great Britain price area, the postcode you type is sent to Octopus Energy's public lookup to find the region. It is sent nowhere else (never to the SpotNav relay), and it is neither saved nor logged by the app.
- **Instant notifications (opt-in, off by default)**: only if you turn on instant notifications in the app's notification settings (offered only with Google Play services), the app gets a push token from Google Firebase Cloud Messaging and sends it to the SpotNav relay (`spotnav.sensnology.se`), which answers with an encrypted reference and stores nothing. The app gives that reference to your own Home Assistant. When an event you chose happens, Home Assistant asks the relay to send an empty wake-up through Firebase Cloud Messaging, and the app then reads your Home Assistant itself; nothing about your charging passes through the relay or Google. Turning it off deletes the token. This is the only thing the app uses the relay for.
- Home Assistant instances on your local network may be discovered with local network discovery (mDNS); this stays on your network.

## What is stored on the device

Language, theme, price area, taxes and fees, widget settings, cached prices and exchange rates, and, if you use the app without Home Assistant, your ENTSO-E key, encrypted with a key held in the phone's Android Keystore. If paired, the Home Assistant address, webhook ID and the charger and vehicle settings, and, if instant notifications are on, the relay's reference for this phone. Android backup is disabled, so none of this is copied to a cloud backup or transferred to a new device. Uninstalling the app removes it.

Android's system log may contain request paths, HTTP status codes and price counts for troubleshooting; it never contains the ENTSO-E key and holds no personal data from the app.
