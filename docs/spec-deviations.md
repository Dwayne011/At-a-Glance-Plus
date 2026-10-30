# Where the build differs from the master spec

The cards follow `At_a_Glance_Plus_Master_Prompt__Spec.md`. These are the places where Android,
or the way At a Glance is hosted, does not allow the spec exactly, and what was done instead.

## Platform limits

| Spec | What the build does | Why |
|---|---|---|
| Calendar title and location both marquee; `setBoolean(…, "setSelected", true)` | Long text ends in "…". | `View.setSelected` cannot be called through RemoteViews. Scrolling by focus only worked after returning to the home screen, because the launcher decides focus. |
| Equaliser as an `AnimatedVectorDrawable` in an `ImageView` | The animated drawable is hosted in an indeterminate `ProgressBar`. | An animated vector in a plain `ImageView` does not start inside RemoteViews; a `ProgressBar` starts it by itself. |
| 500 ms long-press to dismiss, with progress bar, scale and shake | A ring traces the card's edge while it is held (red, amber on weather), closing just before the launcher's menu opens (Pixel Launcher opens it at 3/4 of the touch-and-hold delay, so 0.3 s by default; the ring is visible for about 0.2 s) with Dismiss, which removes the card like the ✕. No scale, shake or exit animation. The ring is drawn for a 380 × 76 dp card; on other widths its corners stretch slightly. | RemoteViews has no long-press, and the app is not told about a press. The ring is the card's own pressed-state animation, which Android plays without the app. |
| Bottom-strip text (28sp temperature, 15sp and 12sp lines, 36dp strip), calendar card | 24sp, 14sp and 11sp, a 34dp strip and 2dp clear above the text. | At full size the text touched the card's top edge on the phone. |
| Weather card (A+C hybrid spec): fixed slate background, date on line 1 | The background still follows the weather, as chosen: a plain colour (Standard), or a picture of the weather and time of day (Scenes, the default), still or moving (Animated scenes, which uses more battery). On the lock screen line 1 is the condition, since the date is beside the clock. The left pill is the first chip in the user's list. Since 0.18.0 only the Material You layout is offered; the three hybrid variants and "No chips" were removed. | User choices. |
| Timer reads "6:42" | Reads "06:42". | The live countdown is the system Chronometer, which always pads minutes; the paused value matches it. |
| Message toast with 340 ms morph in, 280 ms out, in a `ViewFlipper` inside every card | The toast is pushed as its own card in first place and stays until it is swiped past (the launcher reports another page shown), tapped, dismissed or replaced by a newer message, for at most 10 minutes. No countdown bar. The avatar animates instead: it pops in with a ring bursting out until the card has been seen on that surface, and a halo pulses in the app's colour while it stays (not on the always-on display). | The launcher draws the change between cards, including the swipe away; the app cannot set the curve or animate the card as it is dragged. Re-pushing to step a countdown made the card flash. |
| Toast countdown bar animates smoothly | Steps every 0.5 s. | Each step is a new push; the app cannot animate a bar inside the card. |
| Thin `sans-serif-light` text on the always-on display | Colour changes to `#FF9AA0A6`; the weight stays. | The font family cannot be changed through RemoteViews. |
| Large font size variant at 1.24–1.30× | Works at those sizes. | Android 14 and later allow font scale up to 2×; at the largest settings, text in the fixed 76 dp body can clip. |
| 16 dp side margins around the card | None added. | The launcher and lock screen already inset the At a Glance area. |

## Not verified yet

- Whether SystemUI redraws the lock-screen cards while the phone dozes. The app pushes the
  always-on look when the screen turns off. The "Always-on look on the lock screen" switch shows
  the look with the screen on.

## Where each card's data comes from

Every card except weather appears only while it has something real to show.

| Card | Source | Shown |
|---|---|---|
| Message pop-up | New conversation notifications (any app) | 4.5 s in first place. On the lock screen only the sender shows, unless Android confirms private content is allowed there. |
| Navigation | Ongoing navigation notification (Google Maps or another maps app); platform, travel mode and trip progress when the notification gives them | While navigating |
| Live update | Android 16 Live Updates (promoted ongoing or progress-style notifications) from any app | While the app keeps it up |
| Timer / alarm | A countdown timer notification from a clock app; otherwise the next alarm | Timer while it exists; alarm within 12 h |
| Media | The playing media session | While playing, and 5 min after pausing |
| Calendar | The phone's visible calendars | From a chosen lead time (1 h by default) until the end, for events of 4 h or less; all-day events optional |
| Finance | Yahoo Finance's public chart feed for the user's tickers (Google Finance has no public data) | On big moves, every few hours for 10 minutes, and/or in time windows, as chosen |
| Sports | ESPN's public scoreboard feed (unofficial) for followed teams and Formula 1; live scores refresh every 30 s | During a game, for a chosen time before and after it, and the next fixture if switched on; one card per game. Tapping opens Google's page for the game (ESPN's if chosen); Next on the F1 card steps through the weekend's sessions for a minute |
| Torch | The phone's torch, however it was turned on | While it is on. A tap turns it off; brightness by tapping a step of the bar or − / + (cards cannot follow a drag, so there is no slider) |
| Extra chips | Chips that have something to report but no room on the cards on show | Only then |
| Wallet | Google Wallet passes, synced over a Google sign-in workaround (see below) | Near a pass's places, in its time windows, or — for flights, events and transit — around its own time; tapping shows the code full-screen (after unlocking) at full brightness |

Card text and layout read notification contents as each app posts them, so wording can differ
between apps (for example the split between a distance and an instruction on navigation).

## Material You weather, chips and sports spec (0.13.0)

| Spec | As built | Why |
|---|---|---|
| Forecast hours 32dp wide with 30 x 15dp time pills | 34dp hours with 32 x 15dp pills, 3dp inside | "02:00" touched the 30dp pill's edges |
| Right column 74dp | 78dp | The spec's stacked chip is 78dp wide |
| Right column pills in navy (#1E2D42) | Each chip in its own tonal palette | Chosen: tonal chips everywhere |
| Missed calls and texts: light or dark variant | Dark variants | Chosen |
| Weather steps pill: mint-teal fill (1.2) | Dark green with a 2dp white progress line (2.2) | Chosen |
| eToro standard: "+$412 · +6.3%" | "+6.3%" when the whole does not fit one slot | 13 characters cannot fit an 84dp pill legibly |
| eToro in a fall | The spec has no loss colours: the chip uses a red tonal palette (#4A2626 fill) | So a fall does not show in green |
| Sports team circles: team colours (Hawks red, Cavaliers wine) | Each team's ESPN colour darkened to 60%, rim in its second colour, the logo made for dark backgrounds | The spec's examples are darkened team colours; ESPN's "on primary colour" logos are 4096px files, too heavy for a card |
| Sports background: fixed team-tinted gradients | The arena slate washed with each team's real colour | Chosen: real team colours |
| F1 card: always "Next ›" | "‹ Back" on the last session | Next steps through the weekend and back |
| Standard look: the spec's surface | Same, drawn behind the content with its wave; the scenes are the same surface tinted for the weather | The Standard look follows the spec; Scenes and Animated redrawn to match |

## Google Wallet passes (0.15.0)

Google gives apps no supported way to read a user's Wallet passes. To show them without the
user re-entering anything, the app signs in the way Google Play services does — the same
workaround SmartspacerPlugins' Google Wallet plugin uses (credited in `NOTICE`; this is why the
app is GPL-3.0). This is against Google's terms, so the user is shown a clear warning before the
sign-in and must agree.

| Area | As built | Why |
|---|---|---|
| Sign-in | Google's own `EmbeddedSetup` page in a WebView; the `oauth_token` cookie is traded, as Play services, for the account master token, then a `tapandpay` token | The only way to read Wallet passes |
| Token storage | Master token encrypted with an Android Keystore key, on the phone only; sign-out deletes it; a 403 (revoked) signs out automatically | Keep the powerful token safe |
| Sync | `syncvaluables` protobuf, parsed by a hand-written wire-format reader (no generated classes); every ~6 h, on screen-on when stale, and after signing in | Avoids the protobuf Gradle plugin, which is fragile on AGP 9.3.2 |
| Where/when a pass shows | The user's rules: any number of places (Play services geofences, with a radius each) and time windows; flights, events and transit also use their own times | Passes carry no location, and Wallet never surfaces some passes (e.g. a Clubcard) |
| The code | Rendered on the phone with ZXing (QR, Aztec, PDF417, the 1-D formats, or text) | No network needed to show a code |

## Sports & F1 in-app dashboards (0.15.0)

The sports and F1 detail screens (opened by tapping their cards) were rebuilt to the uploaded
Material You dashboard spec: a dark full-screen layout with a pinned hero and a swipeable
segmented-pill tab pager, instead of one long vertical scroll.

| Spec | As built | Why |
|---|---|---|
| Fixed tabs (Leaders, Team Stats, Box Score, Schedule) | Tabs appear only for the data a sport actually has (Leaders / Team Stats / Box Score for the stat sports; Line-ups / Timeline for football; Schedule always) | The app covers every ESPN sport, not just the NBA; it shows what the live feed gives and hides the rest |
| Hardcoded Hawks–Cavaliers / Mexico GP sample data | Live ESPN data (`GameDetail`, `RaceSession`); real team colours in the hero and tug-of-war bars | The app is live, not a mock-up |
| Quarter splits in the NBA hero | Left out | The scoreboard feed doesn't carry period splits for every sport |
| F1 Circuit Info and championship Standings tabs (circuit length, altitude, DRS zones, lap record, points) | Left out; the F1 hub shows the circuit, an up-next countdown, the weekend's sessions and results | The public feed doesn't provide circuit telemetry or championship points, and the app doesn't invent data |

## Deck size and card height (0.16)

| Spec | As built | Why |
|---|---|---|
| As many cards as the launcher asks for (5) | Every card with something to show; "Show all cards" (Cards tab, on by default) turns the limit back on | Chosen; Smartspacer's default ("Force unlimited pages") does the same |
| At most two live updates, two Wallet passes | No cap on either | Chosen |
| 76dp card body in the 88dp slot | 104dp slot and body (`@dimen/card_body_height`); a "Card height" slider (76 to 140dp) changes it at run time | Chosen: 104dp fits comfortably on the smallest launcher grid (the launcher's page dots overlap the card's bottom-left a little); taller is cut off. The hold ring, weather scenes and sports washes are drawn for 104dp (stretched slightly at other slider heights) |

## Content at 104dp (0.17.0)

The cards were resized for the 104dp body ("Mix": about 10 to 15% larger, plus one extra line
where real data exists).

| Card | As built | Why |
|---|---|---|
| All cards | Text, icons, badges, buttons and chips about 10 to 15% larger | Chosen (option C) |
| Material You weather | A summary line under the main row: when the weather changes, rain on the way and the wind (mph in the UK and US, km/h elsewhere), beside the icon of the weather coming next; hidden when there is nothing to say | Uses the extra height with real forecast data |
| Hybrid weather variants (Columns, Split, Grid, No chips) | Removed in 0.18.0; Material You is the only layout, and the other cards always keep their chips on the right | Chosen |
| Calendar | "Then HH:MM · title" for the next timed event later today; the colour bar spans the text exactly | Real data from the same calendars |
| Media | The album, when the app reports one and it differs from the title | Real data from the media session |
| Sports | Larger, but no extra line | The scoreboard feed has nothing more without fetching each game's details |
| Gmail and other chips beside a wide chip | The wide chip takes only the room left, so a count such as "11" is never cut to "1"; counts above 99 read "99+" | A squeezed pill hid digits |

## Backup (0.17.0)

| Area | As built | Why |
|---|---|---|
| Google account backup | Android Auto Backup with a backup agent that saves an allowlisted settings snapshot only | The preferences file also holds secrets |
| Export / import | App tab: a JSON file of the same settings; importing replaces them after a confirmation | Moving settings by hand |
| Never included | The wireless-debugging key and pairing, eToro keys, Wallet sign-in, caches, the last location | Secrets and phone-specific state; Wallet and wireless debugging are set up again on a new phone |

## Settings (0.18.0)

| Area | As built | Why |
|---|---|---|
| Tabs | App, Cards and Chips follow a swipe as well as a tap | Chosen |
| App tab | A status line ("Showing on the home screen and lock screen") with Activate / Back to Google (Activate greyed out only while both the home and lock screens are connected, since 0.22.1); Problems and failed device checks only when there are any; weather location only while it is missing; Backup. Connection details, wireless-debugging checks, the command and crash logs, chip fitting numbers and the adb commands are in a collapsed Advanced section | Less to read day to day |
| Cards tab | One row per card; tapping a card with options (Weather's look, Calendar, Media, Finance, Sports, Wallet) opens them, one at a time | The inline panels made the tab very long |
| Chips tab | Only the chips; the weather layout picker is gone and its look moved to Cards › Weather | Material You only |

## Card fixes (0.19.0)

| Card | As built | Why |
|---|---|---|
| Weather, right column | Two pills the column's full width (78dp), 26dp tall, 10dp apart, like the other cards' right column; a stacked chip fills the column (78 x 72dp) | Aligned pills; no unused space |
| Weather, under the temperature | Up to 3 chips where they fit | There was room for a third |
| eToro chip, stacked | A fourth line: "Today +$38", the value's move since the last reading before midnight (or the first today). Deposits and withdrawals count as moves | eToro's API keeps no history; the app records its own |
| Sports HOME / AWAY tags | 15dp tall, text centred by the font (0.22.1: the 2dp nudge down from 0.19.0 removed; it only applied to the browser preview and put the text low on the phone) | A little more room around the text |
| F1 | After the first Next, Back (one session back) sits over Next; on the weekend's last session only Back | Chosen |
| Message pop-up | Up to 3 lines of the message, then "…" | Uses the card's height |
| Wallet barcode | 156 x 52dp in a 176 x 80dp box (QR codes 70dp in 80dp) | Easier to scan |

## Stocks outside regular hours and VWAP (0.20.0)

| Area | As built | Why |
|---|---|---|
| Pre-market, after hours, overnight | For listings with extended trading (US ones), Yahoo's full-day price while it differs from the close, labelled Pre-market, After hours or Overnight (20:00 to 04:00 New York time, Sunday night to Friday morning). Its change is from the regular close, as eToro shows it. The finance card, the dashboard headline and the Cards tab show it; a big move outside regular hours counts towards "big moves" | ONDS and NOK fell overnight without the app showing it |
| Dashboard chart outside regular hours | The 1D and 5D charts can include pre-market and after-hours candles (VWAP › Extended hours, on by default since 0.21.1); Yahoo has no candles for the overnight session, so the chart ends at 20:00 New York time | The feed's limit |
| VWAP | eToro's inputs: Source (Open, High, Low, Close, (H + L)/2, (H + L + C)/3, (O + H + L + C)/4) and Anchor period (Session, Week, Month, Quarter, Year, or the start of the range), in the exchange's own time. Defaults (H + L + C)/3 and Session, as in eToro. Before, ranges of a month or more were anchored to the start of the range | To match eToro's chart |

## Weather right column (0.21.0)

| Spec | As built | Why |
|---|---|---|
| Right column 72dp tall, in the main row | The card's full height (94dp inside the 104dp card), beside the main row and the summary line. Two pills: 26dp in 38dp zones, centred on the card, 12dp apart, as on the other cards. A stacked chip fills the column (card height − 14dp) with larger text (15sp value, 11.5sp lines, 14sp eToro value) | The pills sat high, and the space under a stacked chip went unused |

## Animated scenes (0.22.0)

| Scene | Moves | Why |
|---|---|---|
| Sunny | Soft rays fan down from the sun, turning slowly (±6° over 11 s) and brightening and dimming out of step with its glow | Chosen: subtle sun rays |
| Clear night | The stars twinkle under thin wisps of cloud drifting both ways (new layer, also in the still Scenes look) | Chosen: subtly moving clouds |
| Cloud (light) | Each cloud drifts on its own (±22dp / 10 s and ±16dp / 7.5 s) | Clouds seemed still at ±14dp |
| Cloud (heavy), rain, snow, thunder | The back and front rows drift at different speeds and opposite ways (±12dp / 13 s, ±22dp / 9 s) | Depth |
| Snow | Flakes in front 4.4dp across (were 2.6dp), drawn heavier, with smaller flakes among the round ones behind | Chosen: bigger flakes |

## Calmer animations and team colours (0.22.1)

| Area | As built | Why |
|---|---|---|
| Animated scenes | Everything slower and smaller: rain falls at half the speed (1.1 s per step) and fainter; snow at about half speed with a gentler sway; clouds drift ±7 to ±12dp over 14 to 22 s; wisps ±10 to ±14dp over 20 to 24 s; fog ±14dp over 16 s; the sun's rays turn ±3° over 16 s and pulse over 5 s | 0.22.0 moved too much; it should be barely noticeable |
| Sports dashboard colours | The hero and Team Stats bars use each team's ESPN colour, looked up for teams the game gave none (as on the card). A colour too dark for the dark screen becomes the team's strong second colour (Cavaliers' gold) or is lifted towards white (Bucks' green); if the teams still look alike, the away team takes its second colour | Bars showed a fixed red and gold |
