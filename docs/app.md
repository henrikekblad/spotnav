# SpotNav for Android

A tour of the app's screens. The pictures are taken by `tools/app_screenshots/run.sh` against a demo
Home Assistant with made-up devices (an OCPP charger, a Kia called "Family car", a site called "Home")
and recorded relay prices, so names and numbers are examples.

## Without Home Assistant

<img src="images/app-standalone.png" alt="SpotNav main screen without Home Assistant: charging current, phases, consumption, departure, charging periods, the energy slider and the charging plan over today's and tomorrow's prices" width="360">

Before anything is paired, the app plans on its own: choose charging current and phases, the car's
consumption, a departure time, how many charging periods may be used and how much energy to add. The
plan picks the cheapest quarter-hours before departure and shows them shaded on the price chart, with
cost and range.

## Pairing with Home Assistant

<img src="images/app-pairing-code.png" alt="The Home Assistant section in Settings showing a six-digit pairing code and Waiting for approval" width="480">

In **Settings → Home Assistant**, tap **Find Home Assistant on the network** (or type its address when
nothing answers). The app shows a six-digit code; Home Assistant shows the same code in **Settings →
Devices & services** and asks you to approve the phone. Every charger on that instance is paired at
once. The app gets one webhook ID per charger, never a Home Assistant password or token.

## Main screen

<img src="images/app-main.png" alt="Main screen paired with Home Assistant: the charger card with its status line, charging current and charge history, Start and Pause buttons; the vehicle card with charge level, limit and capacity; and the top of the planning card" width="360">
&nbsp;
<img src="images/app-planning.png" alt="Planning card with charging strategy, departure, charging periods and the energy slider with its full mark, above the charging plan card with the price chart, cost, energy, range and the planned period" width="360">

With Home Assistant paired, the main screen is the charger as Home Assistant runs it:

- **Charger**: what is happening and why, the connection, charging current and phases, this month's
  charged energy (tap it for the history), and **Charge now** / **Pause** for the schedule.
- **Vehicle**: charge level, charge limit, battery capacity, consumption and onboard charger, as the
  car reports them or as you set them.
- **Planning**: the strategy, departure, number of charging periods, and the energy to add (kWh) or a
  target charge level. The slider marks where the battery is full.
- **Charging plan**: the plan Home Assistant has installed.

<img src="images/app-planning-target.png" alt="Planning card set to Target: the target charge level slider, with the range below the car's charge level and above its charge limit shaded" width="420">

With **Target** instead of **kWh**, you set the charge level to reach; the app works out the energy from
the car's charge level and battery.

## Plan chart

<img src="images/app-plan-chart.png" alt="Charging plan card: today's and tomorrow's prices as dots, the planned period shaded, the current time marked, with cost, energy, range and the period's start and end" width="480">

Today's prices and tomorrow's (grey) on one 24-hour axis, green below the day's average and red above
it, with the highest, lowest and current price on top. The shaded band is the planned charging; the line
is now. The table button opens the price table.

## Price table

<img src="images/app-price-table.png" alt="Price table with time, today's and tomorrow's price per quarter-hour, coloured by price, the current quarter outlined" width="360">

Every quarter-hour (or hour) of today and tomorrow, coloured from cheap to dear, starting at the
current one.

## Home-screen widget

<img src="images/app-widget.png" alt="SpotNav widget on the home screen: the price chart for today and tomorrow with the planned period shaded and the charging status line underneath" width="480">

The widget shows the same chart and, when a charger is paired, its status line. Tap it to open the app.
Each widget has its own settings.

## Settings

<img src="images/app-settings-general.png" alt="General settings: language and colour theme" width="420">

**General**: the app's language and colour theme.

<img src="images/app-settings-price.png" alt="Electricity price settings: resolution, price area, VAT, energy tax and grid fee, with a button to change area and taxes" width="420">

**Electricity price**: 15-minute prices or hourly averages, and the price area and additions. Once
paired, area and taxes are Home Assistant's and are changed through it.

<img src="images/app-settings-widget.png" alt="Widget settings: show charging status at the bottom of the widget" width="420">

**Widget**: settings of the widget the app was opened from.

<img src="images/app-settings-vehicle.png" alt="Vehicle card in settings: charge level, battery capacity reported by the vehicle, consumption, onboard charger, and Change vehicle" width="420">

**Vehicle**: the car this charger plans for, and its properties.

<img src="images/app-settings-charger.png" alt="Charger card in settings: how start and stop, charging current and the energy register are controlled" width="420">

**Charger**: how Home Assistant starts and stops the charger, sets its current and reads its energy.

<img src="images/app-settings-site.png" alt="Site card in settings: main fuse, measurement mode, battery, active load balancing and priority" width="420">

**Site**: the main fuse and how the site's load is measured, load balancing and the charger's priority.

<img src="images/app-settings-notifications.png" alt="Notifications card: via the Home Assistant app and via the SpotNav app" width="420">

**Notifications**: charging notifications through the Home Assistant companion app, or from this app.

<img src="images/app-settings-home-assistant.png" alt="Home Assistant card in settings showing the paired address with Refresh and Remove" width="420">

**Home Assistant**: the paired instance, with **Refresh** (ask it again; a charger added there since is offered for pairing) and **Remove**.

## Charge history

<img src="images/app-history.png" alt="Charge history for a month: energy, cost, average price, number of charges and estimated saving, a bar per day coloured by price, and the list of charges" width="360">

A paired charger's charges month by month: totals, an estimated saving against each day's average
price, a bar per day (greener is cheaper) and every charge with its energy, cost and vehicle. **Export
CSV** shares the month as a file.
