---
title: FAQ
description: Common questions about the SpotNav app.
order: 9
---

# FAQ

## Do I need Home Assistant?

No, but the app does much more with it. Without Home Assistant the app plans a charge from the day-ahead prices and
shows when to charge. It does not control a charger, a battery or the heating. With the SpotNav integration in Home
Assistant, the app shows and changes what SpotNav runs at home.

## Why do I need an ENTSO-E key?

Day-ahead prices may not be passed on from our server. Without Home Assistant, the app fetches them directly from
ENTSO-E with your own free key. See [Getting started](getting-started.md#use-the-app-without-home-assistant). Paired
with Home Assistant, the prices come from your Home Assistant, and the app needs no key.

## ENTSO-E has not answered my e-mail.

A new ENTSO-E account cannot create a key until ENTSO-E has granted it access. That is done by hand at ENTSO-E and
can take a while.

## I see only the charger page. Where are the house and the heat?

The house and heat pages need SpotNav 3.0 or newer in Home Assistant, and a charger that is on a site. The heat page
also needs the heating to have something to show: a heat pump, a heat pump meter or indoor sensors. With an older
SpotNav, the app shows the charger page alone. Update the integration in Home Assistant, then tap **Refresh** in
**Settings → Home Assistant**, or reopen the app.

## Can I use the app with a battery or heat pump but no charger?

Not yet. The app pairs through a charger. A home with no charger in SpotNav cannot pair the app.

## Does the app control my battery or heat pump?

The app does not turn control on. Battery control and **Control the heat** are switched on in Home Assistant, by an
administrator. Until then SpotNav only tests: the pages show **Test** and what SpotNav would do, and nothing is sent
to the battery or the heat pump. In the app you can change the everyday choices: the battery mode, the wanted
temperature, Home or Away, the cooler night, the setback when away and the offsets.

## Why does the house page say "Own control"?

The battery runs its own optimiser, for example a maker's AI mode. SpotNav plans beside it only to compare, and
writes nothing to the battery.

## Someone else changed a value at the same time. What happens?

The app writes a value only if it is still what the app last read. If someone else changed it meanwhile, the app says
so, shows the latest value and saves nothing. Try again on the latest value.

## The app says "Pair again".

SpotNav in Home Assistant no longer knows this phone, for example after the integration was set up again. Tap **Pair
again** and approve the phone in Home Assistant. See [Getting started](getting-started.md#pair-again).

## How much data does the app use?

The app reads only the page in view, and only while the app is open: the house's live flows every 10 seconds, the
rest every minute. Nothing is read in the background, apart from the notification checks you turn on.

## Which languages does the app have?

English, Swedish, Danish, Norwegian, Finnish, German, Dutch, Spanish and French. Choose under **Settings → General →
Language**.

## Where do I report a problem?

Tap **Report an issue** at the bottom of Settings.
