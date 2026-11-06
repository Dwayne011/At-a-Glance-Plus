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
| Calendar | The phone's visible calendars, less any switched off in Cards › Calendar | One card per event, from a chosen lead time (1 h by default) until it ends or is dismissed, for events of 4 h or less; several stack, soonest first; all-day events optional |
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

## Activation and the lock screen (0.22.2)

| Step | As built | Why |
|---|---|---|
| Restarting SystemUI | `am crash $(pidof com.android.systemui || echo com.android.systemui)`: the main process by its id | `am crash com.android.systemui` crashes only one of SystemUI's processes; once a screenshot had been taken it hit `com.android.systemui:screenshot`, so the lock screen never reconnected |
| adb from a computer | The command in single quotes | So the computer's shell leaves `$(pidof …)` for the phone's |

## Finance card rules (0.22.3)

| Rule | As built | Why |
|---|---|---|
| Big moves | Shows once a stock's move today (or outside regular hours since the close) reaches the threshold, and stays until the biggest move falls below three quarters of it | A stock hovering at the threshold made the card come and go with each price refresh |
| Dismissing | Hides only that showing: a dismissed big-moves card no longer hides a window or periodic showing | Before, the first matching rule hid the others for the rest of the day |
| Cards › Finance | A line saying why the card shows now, or that it is not, the next time by the clock, and the biggest move against the threshold | So the rules can be checked |
| Card order (0.22.4) | The finance card comes before upcoming, finished and next-game sports cards (live games still come first) | With four upcoming games it was the fifth page, and a periodic showing lasts only 10 minutes |

## Finance card always up (0.23.0)

| Area | As built | Why |
|---|---|---|
| When it shows | Always, while switched on and with prices, updating at "Refresh prices every". The "Every few hours" rule is gone | Chosen: it should stay up and update rather than come and go |
| Big moves, time windows | Optional; while one matches, the card moves ahead of upcoming sports cards (otherwise it sits after them) | Kept as ways to bring it forward |
| Dismissing | Hides it until the next price refresh, when it comes back with the new prices | Chosen: it should come back at the scheduled time |

## Calendar cards (0.24.0)

| Area | As built | Why |
|---|---|---|
| Several events | One card each, soonest first; an event under way stays while the next comes up | Before, the next event replaced the one under way, so a card went before its event ended |
| When a card goes | When its event ends, or earlier only if dismissed | Chosen |
| Which calendars | Cards › Calendar lists the phone's calendars with a switch each; new calendars are shown. Not in the settings backup (calendar ids differ between phones) | Chosen |
| Chips | On the first calendar card only | The rest stack without them |

## Wallet sign-in (0.24.1)

| Step | As built | Why |
|---|---|---|
| Finding the token | The "oauth_token" cookie is looked for on the page's own address and accounts.google.com as well as google.com, at each page start, finish and history change, and every second | Google sets it host-only on accounts.google.com, where google.com cannot see it, so sign-in stopped at the "Welcome" terms page |

## Navigation card and message pop-up (0.25.0)

| Area | As built | Why |
|---|---|---|
| Turn arrow | Chosen from the instruction's wording: turn, slight, sharp, U-turn (to the right where traffic keeps left, unless the text says), keep, merge, roundabout, straight on, arrival flag (tools/gen_nav_arrows.py). With no manoeuvre named (a bus or train leg), the maps app's own picture; otherwise straight on | Maps' notification gave no turn picture, so the card always showed its default left arrow |
| Trip bar | Drawn in code, 6dp: each public-transport leg in its line's colour (one leg: the card's green), grey legs dotted as walks, the part to go faded, a ring at each change, a white dot where you are, a flag at the end; under it the distance and time left and the arrival time, when the maps app's text gives them | Was a 3dp single-colour bar |
| Trip bar movement | Redrawn every 15 s while navigating with the screen on; between the maps app's updates the dot moves on at the pace the time left implies (at most 15% past the app's figure), and never steps back unless the app's figure drops by more than 10% (a reroute) | It only moved when the maps app updated its notification |
| Message pop-up | Ends as soon as its notification goes (read or cleared), as well as on swipe, tap, dismiss, a newer message or 10 minutes; the Reply button's space grows with its text | It stayed after the message had been read; "Reply" was cut off at larger font sizes |

## Formula 1 live timing and standings (0.26.0)

| Area | As built | Why |
|---|---|---|
| Live session card | While a followed F1 session is live, the screen is on and F1's live timing answers: the circuit with every car on it in its team's colour (top three larger and ringed; in the pits or out, faded), the Grand Prix and session, the track status (green, yellow, safety car, virtual safety car, red), the time left (practice, qualifying) or lap (race), and the top three with best lap or gaps. Redrawn at most every 3 s. Otherwise the race card as before | Chosen |
| Where the data comes from | F1's own live timing feed (livetiming.formula1.com, SignalR Core long polling), the one behind formula1.com's Live Timing and read by FastF1 and OpenF1. Not a published API; no account. If it stops answering, the card shows ESPN's data as before | The only free live source with gaps and flags; OpenF1's hosted API needs a paid key during sessions |
| Car positions on the map | By the last mini-sector each car crossed (about 22–24 a lap), along the circuit outline from the f1-circuits project, so the dots step rather than glide. The outline may not start at the start line or may run the other way on some circuits; those can be corrected per circuit | Exact positions (Position.z) need an F1 TV token |
| Dashboard Standings | Drivers' and constructors' championships with points and wins | From Jolpica (api.jolpi.ca), the open successor to the Ergast API that FastF1 uses; ESPN gives no points |
| Dashboard Results | Full classification of the race, sprint and qualifying (time or gap, points, fastest lap); before Jolpica has the weekend, ESPN's top three | From Jolpica |

## Activation timing (0.26.1)

| Step | As built | Why |
|---|---|---|
| Override length | 120 s, the most Android allows | More time for the lock screen and launcher to reconnect |
| After stopping the launcher | `am start -W -a android.intent.action.MAIN -c android.intent.category.HOME` opens the home screen | A stopped launcher only started again when the user next went home; after the override ran out it reconnected to Google's provider, so Activate seemed to do nothing |

## Wireless debugging on newer Android, finance hold ring (0.26.2)

| Area | As built | Why |
|---|---|---|
| Switching wireless debugging on | The app still tries (with the secure-settings permission from the first activation); if Android refuses, it remembers, stops trying, and asks the user to turn it on: a message and an "Open wireless debugging" button on the App tab, and after a restart a notification that opens the setting, with Activate | A system update made these settings need MANAGE_DEBUGGING, which only system apps can hold; the refusal crashed Activate and the after-restart activation |
| Finance hold ring | Each stock page carries its own ring and fills the card (the body has no side padding; the page has the usual 16 / 18dp inside) | Each page takes its own taps (to open that stock), so the card's ring never saw the press and did not animate |
| Extended chip wording (0.26.3) | A wide pill tries the whole story, then shorter forms of it (countdown: "3d · Holiday", then "3d · Mon 2 Nov"), then name and value, then the value | With a named countdown the whole story did not fit half the extra chips card, and it fell straight back to "3d" |

## F1 results (0.27.0)

| Area | As built | Why |
|---|---|---|
| Results tab | Every finished session of the weekend, newest first, each a full classification in the Standings style (position, team colour, driver code, name, figure, extra): practice — best lap, gaps, laps run; qualifying — best time in the last part reached and Q1/Q2/Q3; race and sprint — time, gaps, points, fastest lap | ESPN gives only the top three |
| Sources | Races and sprints from Jolpica (points and status); practice and qualifying from OpenF1's session results (api.openf1.org, open source, free between sessions); failing both, ESPN's top three | Jolpica has no practice; OpenF1 answers only paid keys while a session is live |

## F1 sessions in the dashboard (0.28.0)

Replaces the live race card of 0.26.0 and the Results tab of 0.27.0, as chosen: the race card is as before, and the detail lives in the dashboard.

| Area | As built | Why |
|---|---|---|
| Race card | As before 0.26.0 (ESPN), no live timing | Chosen: the card stays simple |
| Dashboard tabs | Weekend and Standings; tapping a session on the Weekend list opens it (back returns to the weekend) | Chosen |
| A live session | F1's live timing while the screen is open (connected only then): the flag and the time or lap (ticking between updates), the circuit with every car (by mini-sector), and the full order with gap (practice: to the fastest; race: to the car ahead), best or last lap, tyre and its age, in pit / out | Speed, throttle and exact positions need an F1 TV token |
| A finished session | The full classification: races and sprints from Jolpica, practice and qualifying from OpenF1, ESPN's top three failing both | |
| An upcoming session | Countdown, start in the user's time and at the track, the forecast at the circuit for the start (Open-Meteo), the circuit, and last year's winner and pole there (Jolpica) | |

## 0.28.1 — F1 sessions: live highlight, moving map, richer results

| Area | Now | Why |
|---|---|---|
| Weekend list | The live session is highlighted (red, "● LIVE", "On now · tap for live timing"); "Up next" is highlighted only while nothing is live | User request |
| Live map | Fixed: mini-sector status 2048 is a normal ("yellow") time, not "not crossed", so cars only moved on personal-best segments. Cars are now placed at their last mini-sector and moved on at their lap pace (never past the next one), redrawn about 20 times a second | User report: dots not moving |
| Live tower | Column titles (Pos, Driver, Best lap / Gap or Interval / Last lap, Tyre · laps); every car shows its best lap; tyre age no longer wraps; a one-line tyre legend | User: icons and numbers unclear |
| Finished session | A summary (fastest lap or winner, top speed, laps run or pit stops) and the full table with titled columns (practice: best lap, gap, laps; qualifying: best, gap, part reached; race: time/gap, best lap, points). Tap a driver for best sectors (session-fastest in purple), speed trap, tyres per stint, and grid/stops or Q1–Q3 | User: "bare bones" |
| Result sources | Kept on the phone once read. When a session ends (and when a finished one is opened) the app reads F1's live timing feed once, which holds the last session's final state until the next starts; then OpenF1 (now with laps and stints); races and sprints keep Jolpica's official order and points, filled in from these | OpenF1 is closed while any session is live, which left only ESPN's top three |
| Source text | Removed from the session screens and Standings | User request |

## 0.28.2 — F1 tables: last column lined up

| Area | Now | Why |
|---|---|---|
| F1 tables | The last column (Laps, Points, Out in, Tyre · laps) is 64dp wide and centred, title and figures alike; a tyre's age has a set width so the rings line up whether it has one digit or two | User: Laps not in line with its heading |

## 0.28.3 — Dashboards open the one tapped

| Area | Now | Why |
|---|---|---|
| Dashboards | Each dashboard's intent carries its kind in the data, and opening one clears any dashboard left open (FLAG_ACTIVITY_CLEAR_TASK). Before, Android matched the open dashboard's task (it ignores extras) and brought the old dashboard back | User report: tapping another card's dashboard showed the previous one |

## 0.28.4 — Live tower: Tyre and Laps in their own columns

| Area | Now | Why |
|---|---|---|
| Live tower | The last column is split in two, "Tyre" and "Laps", each centred over its own figure (the ring and the laps on that set), instead of the ring and number centred together under one "Tyre · laps" title | User: the laps numbers did not line up with their heading |

## 0.29.0 — Stock and eToro dashboards in the sports look; drag and zoom; Wallet sync status

| Area | Now | Why |
|---|---|---|
| Stock and eToro dashboards | The sports/F1 dashboards' dark screen: 22sp title, pill tabs for the stocks (scrolling past four) and ranges, a hero panel (name, value large, moves in lifted green/red on a green or red wash), 16dp rounded panels; dialogs and buttons take the same dark colours | User request; chose the Sports/F1 style for both |
| Candle chart | Tap or drag along it to read any candle (a sideways drag holds the page still; an up-and-down one still scrolls); pinch to zoom in and out, two fingers to move along; the price scale and the time labels fit the candles on screen; "Reset zoom" shows the whole range; the chosen candle has a guide, a line at its close and a ring | User request |
| eToro value chart | The same drag and zoom | User chose both |
| Other line charts (health) | Drag along to read values (tap as before); no zoom | Shared chart code |
| Wallet sync | "Sync now" shows "Syncing…" and then how the last sync went: Google refusing the Wallet token (its HTTP code and error), Wallet's own HTTP error, or what came back (passes received, unreadable, inactive, shown) | User report: signed in but nothing synced, with no reason shown |

## 0.29.1 — Wallet sync: passes kept

| Area | Now | Why |
|---|---|---|
| Wallet sync | Each pass's hash is read and sent as the int64 it is (SmartspacerPlugins' SyncValuables protos); before, it was read as a string, came back empty, and every pass was taken as deleted. A hash of 0 now means deleted, as in the plugin. An empty result says how many entries Google sent | User report: synced, "no passes", though the Wallet has some |

## 0.29.2 — Wallet: archived passes left out

| Area | Now | Why |
|---|---|---|
| Wallet passes | A pass counts as current only when its metadata says is_active (as SmartspacerPlugins reads it). An archived pass carries no is_active field at all (proto3 omits false), which was being read as active | User report: archived passes listed with current ones |

## 0.30.0 — Wallet passes as settings screens (mockup approved)

| Area | Now | Why |
|---|---|---|
| Wallet settings | Cards › Wallet passes, once signed in, is one row ("n of m on the card") opening its own screen, laid out per Material's settings pattern: Show Wallet passes; Account (Google account with Sign out in its menu, Sync now with the last result); "Passes on the card" and "Not on the card", each pass a row whose name opens it and whose switch shows or hides it | User: the inline layout was confusing; follow the settings guidelines |
| Choosing passes | A pass shows only once switched on (new passes start off); rules saved before this with places or times stay on | User: select the passes to see |
| A pass's page | The pass in its colour, "Show on the card", then **Where** (any number of places, each with its radius; tap to change it, ✕ to remove, "Add a place") and **When** (time windows with days; "Add a time"). With neither, it shows all the time while on | User: multiple locations |
| Boarding passes, event and transit tickets | No places or times: they show by their own times only, as Google Wallet does, and their page says so | User: mirror Google Wallet |
| Add a place | Suggestions as you type (Photon, OpenStreetMap, nearest first), "Use where I am now", and "Any <chain>" when the results share a business name (e.g. any Tesco), with how many are within about 10 km. The radius (100/150/300/500 m) is chosen on adding | User: suggestive search; a place name like Tesco, not one place |
| Any branch of a chain | Its branches within about 11 km are looked up and kept on the phone, again after a move of 2 km or a day, and watched with geofences (nearest 95, the Play services limit is 100) | As above |
| Pass pop-up | A white card headed in the pass's colour with the code, over the screen behind blurred and dimmed (window blur), brightness up; "tap anywhere to close" | User: frosted background |

## 0.30.1 — Wallet: real names, only passes, A–Z

| Area | Now | Why |
|---|---|---|
| Pass names | The name Wallet lists a pass under (GroupingInfo.grouping_title, each type's own field: loyalty/gift 17, offer 18, event 15, flight 13, transit/generic 5), then the issuer's title or name, as SmartspacerPlugins does. Entries that are none of Wallet's pass types (payment entries, "UCPV2:…" ids), health cards and entries with no name are left out | User: some passes were just letters and numbers |
| Order | Passes in alphabetical order | User request |

## 0.31.0 — Settings as one list (Material settings pattern, mockup approved)

| Area | Now | Why |
|---|---|---|
| Structure | The App, Cards and Chips tabs are replaced by one settings list with pages, as Android's own Settings: Home, then a page per card, Chips (and a page per chip), Backup and restore, Advanced. Back goes up a level | User: follow the Material settings guidelines across the whole settings page |
| Home | A status box on top (showing or not, with the one button that matters: Pair, Activate, Back to Google, or Open wireless debugging when Android blocks it); "Needs attention" only when something does (notification access for a card that is on, weather location, calendar access, an unsupported phone, recorded errors); **Cards** (Weather; Calendar, Finance, Sports, Wallet passes and Media as rows whose name opens the page and whose switch turns the card on or off; More cards); **Chips**; **Display** (card height in a dialog, show all cards, always-on look, bring back dismissed cards); **System** (re-activate after a restart, Backup and restore, Advanced); the version | As above |
| Card pages | "Show the … card", then grouped rows: values open a radio dialog (lead time, refresh, big move, before/after a game, what a tap opens, weather style, media look); lists are rows with add and remove (stocks, time windows, teams, calendars). Adding a stock or a team is its own search page. Finance gains the VWAP row (the same dialog as the stock dashboard's) | As above |
| Chips | The chosen chips with their icon in a tile, value or what they need, and the ≡ handle; "Add a chip (n of 8)". A chip's settings are a page instead of a dialog: account, size (dialog), daily goal (dialog), eToro keys, count down to (date, from the calendar, what it's for), Gmail labels, what a tap opens, remove | As above |
| Advanced | Connection, device, wireless debugging (checks, pair again, activate now, open the setting), diagnostics (crash log, command log and errors, tap to copy; clear errors), chip fitting, adb commands (tap to copy) | Kept, out of the way |

## 0.31.1 — Hide results for F1 too

| Area | Now | Why |
|---|---|---|
| Hide results | Every followed team and Formula 1 has "Hide results" (Sports › tap it). For F1 it now also hides the standings on the weekend dashboard, and the weekend and each finished or live session show a "Reveal" bar (as the match screen does) instead of pointing to settings. The race card's podium was already hidden | User: all sports/teams, F1 included, should have the option to hide results |

## 0.32.0 — Weather card in two panels (layout C), scenes redrawn, forecast card, round icon, coordinates

| Area | Now | Why |
|---|---|---|
| Weather card | Two panels (mockups approved): the weather on the left (icon, temperature, "Cloudy · H12° L9°", and the date on the home screen or "Feels 9°" on the lock screen); the chips on the right as tiles in two rows, two normal chips side by side or an extended chip across a row (its whole story with a progress line, or its value over a second line). No chips: the next 4 hours fill the right panel. The hourly row and summary line under the old layout are gone from this card (the hours are on the extra card) | User: the old card looked messy; chose C |
| Colours | Standard: the wallpaper's colours (Material You system palette: neutral frame, light accent panel, tiles), like the calendar widget. Scenes and Animated scenes: the weather scene behind see-through panels, the chips on a deeper panel. Always-on: outlines. Every chip is one style (no per-chip colours on these cards) | User: wallpaper colours unless using the scenes |
| Scenes | Redrawn for two panels by tools/gen_weather_scenes.py: sun, moon glow and lightning over the weather panel; clouds, fog, rain, snow and the snow bank wider than the card so nothing shows an end; puffy cloud bottoms (no straight edge); clouds drift much further (light ±60, heavy ±30/±45); about a quarter less snow; the sun's rays sway ±8° and pulse more. Long paths are split (lint VectorPath) | User feedback on the animated mockups |
| Extra card | Same two panels: the chips that did not fit (one or two beside the next 4 hours; otherwise a full panel of tiles with their second lines); with none, a forecast card: "Today" in a few words with rain chance and wind, and the next four days | User: today with a summary and the following days |
| Forecast data | Open-Meteo now gives 6 days with each day's weather and chance of rain; kept in the saved reading | For the next days |
| App icon | Adaptive icon (background, foreground in the safe zone, monochrome): round on Pixel, and themed with the wallpaper when Themed icons is on | User request |
| Wallet places | "Add a place" also reads typed coordinates ("51.6535, -0.3965" → "Use these coordinates"), and adds Android's own geocoder's results to Photon's (duplicates within 80m dropped) | User: coordinates when a place does not come up; another free source |
| Removed | The old layout's forecast pills, card edge, chips-card background, the Standard sky and an unused chip layout (and their generator entries) | No longer used |

## 0.32.1 — Forecast as rows, today first (option E)

| Area | Now | Why |
|---|---|---|
| Forecast card (no extra chips) | Left: "Now", what the weather does next, and the wind. Right: rows for today (highlighted, in the tiles' colour) and the next three days: day, weather, chance of rain (10% or more, with a drop), low, a bar from the low to the high on one scale shared by the four rows, and the high. Bars are native views: a tinted track and a fill whose start and length are set in dp from the panel's width | User chose E after the capsule and column mockups |
| Removed | The day columns layout and code (the hours keep their columns) | Replaced by rows |

## 0.32.2: chip tiles fit their space

- The two-panel cards' chip tiles (weather card and extra card) no longer let text run out of
  the tile. The value and its second line share the tile's height by weight (3:2) and size their
  text to fit both height and width (value 16sp down to 7sp, detail 11.5sp down to 7sp), so they
  fit at any card height or system text size. Values are left-aligned beside the icon, and the
  icon is now 16dp.
- A wide tile whose chip has no second line shows the chip's name under the value, so it is not
  left with a lone number.
- The extra card's "Next hours" panel shows three hours, not four, because four times ran
  together in the narrow panel.

## 0.33.0: chip tiles at your size, compact only when needed

- Mockup approved. Each chip in the two-panel cards shows at the size the user chose: Normal
  takes half a row, Extended a whole row. A row turns compact (three small tiles) only when the
  chips that are switched on do not all fit. The card compacts as few chips as it can, later
  rows first, so full-size and compact rows can sit together. A chip alone in its row spans it.
- Every tile uses one grid: line 1 (19dp) has the icon and value, line 2 (13dp) the detail or a
  bar. Each kind of tile (full, wide, compact) shares one text size across the card, the size its
  longest value needs, set in dp so the fixed lines hold it at any system font size. Below 33dp a
  row (a short card height) shows line 1 only.
- Bars say what each end means. Steps run from 0 to the goal and battery from 0 to 100%. Sleep is
  drawn on a 22:00 to 10:00 night, widened if needed. Screen time shows the top app's share of
  all apps. The sun bar runs from sunrise to sunset (or sunset to sunrise), filled up to now.
- New chip data from existing sources:
  - the alarm's day and time left;
  - Gmail's labels with unread mail;
  - the notifying app's name and its count;
  - names for countdowns, Bluetooth devices and health metrics.
- The left weather panel's temperature is 30sp (was 34) and shrinks to fit its row; its icon is
  22dp (was 26).
- Both two-panel cards' views are built apart from the target that wraps them, so they can be drawn
  off the phone. Checked by rendering the real layouts (Robolectric, not kept in the repo):
  nothing clipped or outside its tile, and rows aligned, at 100% and 130% text.

## 0.34.0: the other cards' chips are tiles too

- Mockup approved. The alarm, timer, calendar and navigation cards now show their chips as the
  tiles from the weather and extra cards, in place of the outlined, colour-coded pills. The tiles
  use the same grid, corners and text sizes. Two Normal chips stack in a 100dp column, each with
  its second line or labelled bar. A lone Normal chip is a row high and centred. An Extended chip
  is one tall tile in a 128dp column showing the value, its title, a note and its detail or bar.
  The note and then the title drop out when the card is set too short for them.
- The tiles are a light see-through layer over each card's own colour, so the per-chip tones
  (green steps, blue sleep and so on) are gone. The always-on display shows grey outlines with
  the tiles' 12dp corners.
- The column sits 10dp from the card's top, bottom and right edges. At that inset a 12dp tile
  corner stays inside the card's 24dp corner. The column reaches into the card body's 18dp end
  padding, so these three cards no longer clip to their padding.
- The old pill drawing code was removed, along with the 32 tonal backgrounds and their generator
  (tools/gen_chip_tones.py), the old chip layouts and the chip text style.
- Checked by rendering the real cards (Robolectric, not kept in the repo). Every tile's corners
  fall inside the card's, nothing is clipped by a parent, and rows are aligned, including on the
  always-on display and at 130% text.

## 0.35.0: stock chart redrawn, with the overnight session

- Mockups approved (the Clean style). The stock dashboard's candle chart was redrawn:
  - Candles are grouped by the clock (15 minutes, an hour…) until each bar is at least 3.5dp
    wide, so the whole 1D chart with extended hours shows 15-minute candles. Zooming in brings
    back the 5-minute ones.
  - Softer green and red, rounded bodies and lighter wicks.
  - The pre-market, after-hours and overnight sessions are shaded and named.
  - A price scale on the right, the previous close dashed (1D), and a tag on the latest price
    (the price at the top of the screen; purple overnight).
  - Volume bars, with each feed on its own scale.
  - Times at whole hours, with the day where it changes; 5D shows days.
  - A small gap after the last candle, and the readout as a compact box (time, session, open,
    high, low, close, VWAP).
- The overnight session (20:00–04:00 New York time), which Yahoo's feed does not carry, comes
  from eToro's public candle feed (the one its web charts use; no keys). It applies to US
  listings with extended hours on. The ticker is matched through eToro's public instrument
  list, read once a month into a small index on the phone. eToro's prices are its own quotes:
  on AAPL they were within 3¢ of Yahoo's in regular hours on average (wider in pre-market).
- Data fixes:
  - Yahoo reports no volume outside regular hours, so the VWAP used to run flat through
    pre-market and after hours. It now leaves out candles with no reported volume. It also
    starts again where the overnight candles (another feed) begin and end, and the overnight
    session from 20:00 counts as the next trading day, so the VWAP does not restart at midnight.
  - The price tag shows the official close (or the latest extended-hours price), not the last
    5-minute candle's close, which misses the closing auction (344.80 against 344.63 on AAPL).
  - With extended hours off, Yahoo's final 16:00 closing-print candle counts as the regular
    session, not after hours.
- Checked with live AAPL data in a temporary Robolectric test (not kept). Candles are strictly
  in time order. Pre-market 66 and after hours 49 (no VWAP), regular 78 and overnight 55 candles
  (with VWAP). The dashboard was rendered with extended hours on and off.

## 0.36.0: Weather wash look

- Mockup approved (style 2 of four). A new weather card look, Weather wash, chosen under the
  weather card's Style beside Standard, Scenes and Animated scenes:
  - The whole card takes the weather's colours as a top-to-bottom gradient, one for each kind of
    weather by day and by night. Examples: warm orange to gold for a clear day, grey for cloudy,
    blue-grey for rain, indigo for a clear night.
  - The animated scenes' moving weather goes over the gradient: sun, clouds, rain, snow, fog,
    lightning, stars.
  - The panels and tiles are frosted glass over it. Light washes (clear, partly cloudy, cloudy,
    fog and snow by day) get lighter glass with dark text; the others get fainter glass with white
    text, made a little more solid after a render showed stars behind the numbers.
  - The current weather icons are kept. The extra card shares the look.
  - The gradients and glass come from tools/gen_weather_wash.py.
- Android has no live blur for these cards, so the glass is a plain see-through white.
- The Standard look's description now says what it is (your wallpaper's colours) instead of the
  old slate-navy gradient.
- Checked by rendering the real card (Robolectric, not kept): sunny, cloudy, rain and a clear
  night, and the extra card in rain. No text was cut off or outside its tile.

## 0.36.1: extended hours at a tap; 1D before the open

- The stock chart's extended hours (pre-market, after hours and overnight) can now be switched
  on and off with a switch right under the range pills on 1D and 5D, not only in the VWAP dialog
  (where it was easy to miss that it was off). It is still on by default.
- With extended hours on, the 1D chart asks Yahoo for two days. Before the regular session
  opens, it shows the last full trading day, the overnight session after it and today's
  pre-market. Once the session opens, it shows today, starting with the overnight session that
  led into it (from 20:00 the evening before).
- Checked with live AAPL data during Monday's pre-market (Robolectric, not kept). The chart ran
  from Friday 04:00 New York time to 07:03 today: pre-market 104, regular 78, after hours 48 and
  overnight 94 candles, in time order, ending at the live pre-market price of 344.57.

## 0.37.0: "What if" for eToro positions

- Mockup approved. On a stock's screen, when the user holds that stock on eToro and it is priced
  in US dollars, two panels follow the chart:
  - **What if:** pick a price with a slider (its ends on round steps), the ±1¢ and ±10¢ buttons,
    by typing (tap the price) or with presets. The presets are:
    - now;
    - the break-even of all the positions together;
    - the biggest position's break-even;
    - the close;
    - the day's high;
    - the user's own targets, added with "+ Target", removed with a long press, and kept per stock.

    The panel then shows the value, the profit/loss (amount and percentage of the margin), the
    units, and what each 1¢ and each $1 is worth.
  - **Your positions:** each position with BUY/SELL, Real or CFD, its leverage and its date, its
    units and open price, and its value and profit/loss at the chosen price.
- The positions come from the user's eToro account (the existing read-only keys). EtoroSource now
  keeps each position's units, open rate, leverage, margin, current P/L, open date and real or CFD
  (eToro's isSettled flag), not only per-stock totals. They refresh when over a minute old.
- The maths (live/WhatIf.kt): each position starts from eToro's own current P/L, so at today's
  price it matches eToro exactly. It then moves by its units for each dollar of price, up for a
  buy and down for a sell. A position is never worth less than nothing. Overnight fees, which
  grow with time, are not added.
- Analyst targets were left out because Yahoo gives them only to signed-in users. Robinhood comes
  later: it has no official API for stocks or options.
- Checked with the ONDS positions from the user's Gemini calculator:
  - at $10, P/L +124,238.58 (+257.86%), each position matching the calculator;
  - break-even $6.5975, where P/L comes back to exactly zero;
  - 1¢ worth $365.14.

  The real stock screen was rendered with them (Robolectric, not kept).

## 0.37.1: Finance has its own section; eToro sign-in there

- The main settings page has a Finance section of its own, after Cards. It holds the stocks card
  (the switch row, now titled "Stocks") and an eToro row. The eToro row shows whether it is
  connected and, once it is, how many positions.
- Finance › eToro is a page for connecting the account with a read-only key: the steps, the two
  fields, Test connection and Remove keys. It explains that the account feeds the eToro chip, the
  eToro dashboard and "What if" on a stock's screen, and that the app cannot trade. The Finance
  page links to it under Accounts, and the eToro chip's settings point to it too. Back returns to
  whichever page opened it.

## 0.38.0: candle sizes, live refresh and volume on the stock screen

- **Candle size** on 1D and 5D: Auto · 5m · 30m · 1h, under the range buttons.
  - Auto groups the candles to fit the width, as before.
  - The fixed sizes stay put however far you zoom.
  - 5m on 5D asks Yahoo and eToro for 5-minute candles over the five days. The default is 30-minute
    candles.
  - Sizes coarser than the fetched candles (30m and 1h on 1D, 1h on 5D) are built on the chart by
    grouping candles of the same session. Each grouped bar has the group's open, high, low and
    close, and the sum of its volumes.
  - Checked with live AAPL data:
    - 5D at 5m has the four overnight sessions in the range, 376 eToro candles, as at 30m (64).
    - The candles are strictly in time order.
- **Live refresh:** the price, the chart and the eToro positions are read again every 15 seconds.
  This happens only while the stock screen is open and in front (Lifecycle RESUMED). It stops once
  the user leaves the screen, switches app or turns off the screen; nothing runs in the
  background. Back on the screen, it reads at once.
  - A refresh keeps:
    - the zoom (following the newest candles when they were in view);
    - the chosen candle (moving on to the newest when the latest was chosen);
    - a "What if" price the user picked. Until the user picks one, or after "Now", that price
      follows the live price.
  - If the feed does not answer, the chart already on screen stays.
- **Volume per candle:** the readout above the chart has a Volume line, with:
  - the shares traded in the chosen candle or bar, in full;
  - a short form after it (1.24M);
  - "on eToro" for overnight candles, whose volume is eToro's own, not the exchange's.

  Yahoo reports no volume for most pre-market and after-hours candles, so those read "None
  reported".

## 0.38.1: activation no longer overlaps the SystemUI and launcher restarts

- Reported: sometimes after Activate (in the app or on the after-update notification) the whole
  screen ignored every touch. The home screen, swipe-up and the notification shade all stopped
  responding, with gesture navigation, until the phone was restarted.
- Likely cause, not reproduced here:
  - Activation crashed SystemUI and then, at once, force-stopped the Pixel Launcher and opened the
    home screen.
  - On current Android, the code that animates switches between apps (WM Shell) runs inside
    SystemUI. So the launcher's restart and the switch to home could begin while SystemUI was still
    starting again.
  - The system could then be left waiting for an animation that never finished, holding back all
    input.
- New order, one at a time:
  1. Set the override.
  2. Force-stop the launcher.
  3. Open the home screen.
  4. Wait for the launcher to be running (up to 10 s), plus 2 s for the switch to finish.
  5. Crash SystemUI's main process.
  6. Wait for its new process (up to 15 s), plus 2 s.

  Both still connect within the two-minute override. Activation takes about 5 to 20 seconds
  longer, and the "adb shell" command shown for a computer follows the same order.
- The wait loops were checked in a POSIX shell with stand-ins for pidof and am. The real check is
  on the phone.

## 0.38.2: eToro key guide matches eToro's current screens

- eToro now labels the two keys Public Key and Private key. Its documentation calls them the Public
  API Key and the User Key. The guide and the fields on Finance › eToro now use the screen's
  labels: "Public Key" (sent as x-api-key) and "Private key (User key)" (sent as x-user-key).
- The steps follow eToro's current pages:
  1. Open API Key Management and tap Create Your API Key.
  2. Choose a name and the Main account.
  3. Tap Quick select: Read all, and leave every Write box unticked.
  4. Confirm by SMS if asked.
  5. Copy the Private key, which is shown once.
  6. Copy the Public Key, which is also shown at the top of API Key Management.
- No change to how the keys are stored (encrypted with the Android Keystore) or used (sent only to
  eToro, read-only requests).

## 0.39.0: "What if" on its own tab; alignment at large text sizes

- The stock screen has two tabs under the headline: **Overview** (price chart and the day's
  figures) and **What if** (the What if and Your positions panels). They appear only when the user
  has eToro positions in the stock and it is priced in US dollars; otherwise the screen is as
  before. The chosen tab survives rotation.
- Fixes from the user's screenshot, at a large text size:
  - **Price tiles** (Now, Break-even, Biggest B/E, targets, Close, Day high):
    - Prices were cut off at the bottom; the tiles now grow to fit (at least 52dp) and every tile
      in a row is the same height.
    - Each label and price stays on one line and shrinks to fit ("Biggest B/E" ran over the edge).
    - "+ Target" is laid out like the others: "Target" over "+ Add".
  - **Slider scale:**
    - Each price sits under its place on the track; spread evenly, the middle ones were off their
      marks.
    - Whole-dollar steps are shown without cents ($0, $5…), so the labels fit.
  - **Chosen price:** "now"/"if" and the move sit on the price's baseline.
  - **Your positions:**
    - "units @ price" became "3,828.48 @ 6.53" (explained in the panel's subtitle), so each row
      fits on one line at the same size.
    - The P/L shares the baseline.
  - **Overview:**
    - The Extended hours text no longer runs into its switch.
    - The candle readout's time stays on one line, shrinking if needed.
- Checked:
  - Both tabs were rendered at 1.3× text on a 384dp-wide screen (Robolectric, with Compose's test
    tools to tap the tab, not kept), using the ONDS positions from the user's calculator.

## 0.39.1: the finance card follows the stock screen's price

- Reported: the At a Glance finance card and the stock screen showed different prices. The card
  read "Pre-market 6.39" after the market had opened; the screen read 6.23.
- Each time the stock screen reads a stock's price, the card's copy of that stock is replaced
  with it (FinanceSource.publish). The service already redraws the card whenever its prices
  change, so the card updates straight away. This happens:
  - when the screen opens;
  - on pull-to-refresh;
  - every 15 seconds while the screen is open.
- Stocks the card does not follow are left out, and an unchanged price causes no redraw. The
  card's own refresh interval is unchanged.
- Checked: a unit test replaced ONDS in a two-stock list (matching regardless of letter case),
  left NOK as it was, and ignored a stock the card does not follow.

## 0.39.2: battery audit fixes

The user asked for a check for battery drain. The background work was reviewed: the service's
timers, location requests, network polling, redraws and the animations on each surface.

**Fixed:**
- **F1 live timing:**
  - Its connection to F1's feed (a long-poll that never rests) stayed open after leaving the app
    while the app stayed in memory, for the rest of the session.
  - It is now connected only while the screen is in front (Lifecycle STARTED).
- **Finance card on the always-on display:**
  - With two or more stocks its pages took turns every 4 seconds with a fade, keeping the
    always-on display redrawing.
  - The always-on display now shows the first stock alone. When a big move shows the card, that
    is the biggest mover.
- **Redraws with the screen off:**
  - Every redraw (the minute check for calendar countdowns, sports, Wallet and alarms; chips;
    prices…) also rebuilt the home screen's cards, which cannot be seen with the screen off.
  - Only the lock screen is redrawn while the screen is off. Turning the screen on redraws
    everything at once, as before.
- **Media track bar:**
  - It was nudged every 30 seconds while music played, even with the screen off.
  - It is now nudged only with the screen on; the always-on display shows no moving track bar.
- **Wallet pass location:**
  - A location was requested at every unlock while signed in to Google.
  - It is now requested at most every 15 minutes.

**Checked and left as they were:**
- Weather location is requested only when the last fix is over 30 minutes old, at balanced
  accuracy.
- The animated weather looks and the equaliser are hidden or still on the always-on display.
- The message pulse is not shown on the always-on display.
- Battery changes redraw only when the chip's value changes.

**Open for the user to decide:**
- Prices and live sports scores are still fetched with the screen off, to keep the lock screen
  current.
- A running timer ticks every second on the always-on display.
- The Animated and Wash weather looks animate while the home screen is in view.

## 0.40.0: Wallet places fixed, scores pause with the screen off, Debug page

**Wallet places** were reported never to show their pass on arrival. The causes found in the code:
1. **Precise location:** geofences were registered when any location permission was held, but
   Play services needs precise location (and "all the time"). With approximate location the
   registration failed in silence.
2. **Unchecked registration:** Play services' answer was never checked. The usual refusal,
   "geofencing not available", comes when Google Location Accuracy is off.
3. **Strict match:** a pass showed only if the position reported on arrival was inside the
   place's radius. That position is often just outside it, so an arrival could still show
   nothing.

Fixes:
- **What places need, checked and shown:** precise location, all the time, location on. The
  Wallet page shows the one thing missing as a row that fixes it, or else "Places are watched" or
  Play services' refusal in words, with a link to location settings.
- **Play services' answer is checked:** success or failure goes in the history.
- **Re-registered after changes:** places are registered again after any permission answer, and
  when the screen comes on while they are not watched.
- **Arrival and departure tracked:** the app keeps which places the phone is inside from Play
  services' events. A pass shows while the phone is inside one of its places, or while a fix
  under 30 minutes old is within the radius. Older fixes no longer count, so a pass does not
  linger.
- **Geofence size:** geofences are at least 100 m, with one-minute responsiveness.
- **No location indicator for the app:** Play services watches the places, so Android's
  indicator names it, not this app. The Debug page says so.

**Live scores** are no longer fetched while the screen is off. The sports loop waits for the
screen and fetches at once when it comes on.

**Debug** (Advanced › Debug) shows:
- **Now:** the connections, with each surface and when it was last drawn.
- **Why Android last stopped the app:** from ApplicationExitInfo, such as an update, low memory,
  a crash or a force stop.
- **Battery and background:** battery optimisation, background restriction and the standby
  bucket.
- **Location for Wallet places:**
  - the permissions and the location switch;
  - Play services' status;
  - the last fix's age and accuracy;
  - for each pass with places, whether it shows, how far its places were at the last fix, and
    whether Play services says the phone is inside.
- **The system's side**, over wireless debugging:
  - dumpsys smartspace;
  - why the Pixel Launcher and SystemUI last stopped;
  - the standby bucket;
  - background appops;
  - the battery allowlist.
- **History**, newest first.
- **Report:** "Share debug report" and "Copy debug report" put all of it, with the errors and the
  last command log, into one text.

The history (DebugLog, files/debug_history.log, at most about 800 lines) records:
- each app start, with Android's reasons for each stop since;
- the service starting and stopping;
- the home screen and lock screen connecting and disconnecting;
- phone restarts and app updates;
- each activation step's exit code;
- places' registration results, arrivals and departures;
- location fixes that failed;
- changes in which passes the Wallet card shows;
- every recorded error.

It holds no keys, tokens, account names or coordinates, and is not in backups.

Checked:
- The Debug page was rendered (Robolectric, reached by tapping Advanced › Debug).
- The report was printed in the test: no coordinates, keys or accounts.

## 0.41.0: What if maths, finance card with chips, candle menu, eToro volume, and fixes

**Fixes from the user's report:**
- **What if was off:**
  - It took eToro's current P/L for each position and moved it by the gap between the chosen
    price and Yahoo's price. eToro's figure is at eToro's own price, so any difference between
    the two feeds was multiplied by the units (about $365 a cent on 36,500 units).
  - P/L is now units × (price − open price), up for a buy, down for a sell, the same as the
    user's calculator. Break-even is the open prices weighted by units.
  - Checked against the calculator: +124,238.61 at $10 (calculator +124,238.58, which rounds each
    position first), and break-even 6.5975, the same.
  - eToro's own figure can differ by its spread and fees.
- **"Fetching live scores … JobCancellationException" errors:** pausing scores with the screen
  off restarts the sports job when the screen comes on, and each stop was recorded as an error.
  A stop on purpose (a coroutine cancellation) is no longer recorded.
- **Running in the background:**
  - The At a Glance service stays running because the launcher and lock screen are connected to
    it. That is how the cards work.
  - A pairing started and never finished kept a foreground service searching the network
    indefinitely, and Android restarted it. Pairing now stops after 10 minutes and is not
    restarted.
- **Live Updates:**
  - Uber Eats posts a plain ongoing notification, not a marked Live Update, so it was not shown.
  - Ongoing notifications from delivery and ride apps now show as Live Update cards: Uber, Uber
    Eats, Deliveroo, Just Eat, Bolt, Bolt Food, Lyft, DoorDash, Grubhub, FREENOW, Citymapper and
    Domino's (UK).
  - Each new Live Update is noted in the Debug history, with whether the app marked it.

**Stocks:**
- **At most four stocks:**
  - Adding stops at four, and the Finance page says so.
  - Settings with more keep the first four.
- **Swipe between stocks:** swiping the price card left or right on the stock screen moves to the
  next or previous stock, the card following the finger. Dots under it show which stock is open.
- **Candle sizes:**
  - The sizes are Auto, 5m, 15m, 30m, 1h and 4h (the user's mockup D).
  - They are a "Candles: Auto ▾" menu beside the Price title on 1D and 5D, replacing the second
    row of pills, which looked like a second copy of the range setting.
  - 15m on 5D fetches 15-minute candles from Yahoo, and eToro's 5-minute candles grouped into
    15.
  - 1h and 4h are grouped on the chart.
- **Time labels when zoomed:** the labels step from 6 hours down to 1 hour, 30, 15, 10 and 5
  minutes, the finest that fits for the candles on screen. They are spaced by their measured
  width, so 12-hour labels ("10:15 AM") never overlap.
- **Volume:**
  - Yahoo reports no volume for most pre-market and after-hours candles. Those candles now take
    eToro's volume for the same time, as eToro's chart shows (labelled "on eToro" and drawn on
    eToro's scale).
  - The VWAP starts again where the volume's source changes.
  - eToro's public candle feed returns at most about 1000 candles per request (checked with
    1500, 2000 and 3000). So with 5- or 15-minute candles on 5D, the first day's pre-market has
    no eToro volume. 1D, and 5D at 30 minutes or more, are complete.
  - Checked with live ONDS and AAPL data: for example, 88 of 91 ONDS pre-market candles on 1D
    now have volume.
- **Card refresh:** the home screen card keeps its own interval, which the user can choose,
  unchanged. The stock screen still refreshes every 15 seconds while open.

**Finance card with chips (the user's mockup C):**
- With chips, the finance card puts the chips' column on the right, as the calendar card does.
- Each stock's day's line and the area under it are drawn faintly across the lower part of the
  card, with a dotted line at the previous close. Over them sit the ticker and name, then the
  price, its move and the change pill.
- The line is an alpha-only image tinted green or red (about 70 KB a stock), so four stocks stay
  well inside the card size limit.
- Beside chips the price drops its currency code ("6.39"), so the line fits.
- The card takes its chips from the user's chips after the navigation, timer or alarm, and
  calendar cards. Without chips it is as before.
- Rendered:
  - rising and falling stocks;
  - without chips;
  - on the always-on display;
  - with one tall chip;
  - at 1.3× text.

  The layout audit found no clipping.

## 0.41.1: Advanced tidied (mockup approved)

**Advanced** now holds only:
- **At a Glance:** one status row ("Showing on the home and lock screen · Connected since 09:41",
  or "Not showing" in red) and Activate now. Activate now's line shows the last activation's
  result.
- **Wireless debugging:** one row, either "Ready" or the one thing wrong (not paired, not on
  Wi-Fi, or wireless debugging off), then Pair again and Open wireless debugging.
- **Help:**
  - "N errors recorded", in red, only when there are any;
  - Debug.

A missing Pixel Launcher or Google provider appears as a red row only when it is missing.

**Moved to Debug:**
- the device checks (Pixel Launcher, Google provider);
- the four wireless debugging checks;
- the errors, as when, where and what (Copy gives them in full with stack traces), with Clear;
- reading the phone's crash log and the command log;
- the adb commands for a computer, folded under one row.

The chip-fitting report left the screens and is in the shareable debug report. Sessions now
record when they connected, for the status row. Home's "Errors recorded" row opens Debug.

Checked: both pages were rendered by tapping through Settings › Advanced › Debug (Robolectric).

## 0.41.2: chips go to the cards in front of the weather card first

- Reported: the finance card showed no chips. The weather card took all five of the user's chips
  before the cards in front of it were given any.
- **New order:** chips are handed out in the order the cards appear. First the cards in front of
  the weather card that hold chips, in turn (navigation, then timer or alarm, then the first
  calendar card, then stocks). Then the weather card fits what it can of the rest. Then the extra
  chips cards take what is left.
- **Hidden cards take none:** a card the launcher has no room for (when "Show all cards" is off)
  takes no chips.
- **Dismissed cards:** a dismissed card is not shown, so it takes none. Its chips go back to the
  weather card at once (dismissing redraws), and whatever the weather card cannot fit goes to the
  extra chips card.
- Checked with the real deck builder (Robolectric), using the user's five chips:
  - with the stocks card showing, stocks got Steps and Gmail, and weather got the other three;
  - with it dismissed, weather got all five;
  - with eight chips and the stocks card dismissed, weather took six and the extra chips card
    took the other two.

## 0.41.3: finance card text clear of the chart

- Reported, from the user's phone (large text): the price line overlapped the chart and its
  dotted previous-close line, and the change pill was cut short ("-3.6").
- **Text moved up:** the text sits higher (9dp from the top, was 17dp).
- **The chart follows the text size:** the chart's band starts 5dp below where the text block
  ends at the phone's text size. The block measures 12dp plus 44sp (the title's 23sp line, plus
  the pill's 19sp line and padding). The band ends 5% above the bottom.
- **The pill is never cut:** the price line takes the room the pill leaves.
- **The price line shrinks to fit:** it is measured at the phone's text size against that room
  and made smaller (13.5sp down to 10sp, with 4dp to spare) until it fits.
  - Android's built-in auto-size text was tried first. It cannot be checked in the renders, so
    it was replaced with this measurement, which can.
- Checked by rendering at 1.0× and 1.3× text, with one wide chip or two chips, using a stock
  falling from the previous close (the reported case). In the 1.3× wide-chip case the price
  measures 169dp of the 174dp room.

## 0.41.4: when dismissed sports and F1 cards come back

The user asked what brings each dismissed card back, and chose new timings.

**Before:**
- Dismissals were stored per stage ("sports:<event>:BEFORE", ":LIVE", ":AFTER"). A team's card
  came back at every stage: at kick-off, then for the result, then for the next fixture.
- F1 came back for every session's start, live session and result, many times a weekend.

**Now:**
- **Team card:** dismissing it at any stage snoozes the team. It comes back 30 minutes before the
  next kick-off that is still more than 30 minutes away. So dismissing a fixture 90 minutes off
  brings it back 30 minutes before kick-off. Dismissing the live game or the result hides it
  until 30 minutes before the following game. That includes the "next game" card when "Next game
  when nothing's on" is on.
- **F1 (racing series):** the same, 1 hour before the next session that is still more than an
  hour away.
- **Stocks card:** unchanged. It comes back at the next price refresh, as the user chose.
- **How it is stored:** snoozes are kept per followed team or series with the time of the
  dismissal (sports_snoozed), and checked against the schedule each minute. A snooze ends once
  its time comes. If the team's schedule is not loaded when the card is dismissed, it falls back
  to the old per-stage dismissal.
- **Settings:**
  - "Bring back dismissed cards" counts and clears these snoozes too.
  - The Sports page explains the timings.
- Checked with a Robolectric test of the real rules:
  - a fixture 90 minutes off: hidden after dismissing, back at 29 minutes before kick-off;
  - dismissed live: hidden through the result, and with the next game three days off;
  - F1 qualifying 100 minutes off: hidden, back at 59 minutes before.

## 0.41.5: prices that stop updating, and a notice when the cards disconnect

**Report:** the stock screen and the finance card stopped updating. The screen showed a 17:00–17:15
candle at 18:45. The debug report showed the app running and drawing the cards, and no errors.

**Findings:**
- Every Yahoo request had time limits, so a stuck connection was ruled out.
- The likely cause is Yahoo refusing requests (its free feed rate-limits apps that ask often; the
  stock screen asked every 15 seconds). On a refusal the app quietly kept the last prices and
  recorded nothing.
- Not confirmed, as no refusal was logged.

**Changes:**
- **One path to Yahoo:** all Yahoo chart requests go through one function. It tries
  query1.finance.yahoo.com, then query2 if the first refuses or fails. It does not cache.
- **Failures are logged:** each change between answering and failing goes in the Debug history,
  with Yahoo's reply ("HTTP 429, too many requests", "HTTP 403, refused", a network error…). A
  404 (no such stock) is an answer, not a failure.
- **Backing off:** the stock screen's 15-second refresh waits longer after refusals (30 seconds,
  then 1, 2, 4 and at most 5 minutes), and is back to 15 seconds once Yahoo answers.
- **Shown on the screen:** while prices are not updating, the stock screen says so under the
  price card, with Yahoo's reply, the time of the last prices and the current retry interval.
- Checked against the live feed (Robolectric):
  - ONDS fetched;
  - the 1D chart current to the minute;
  - an unknown symbol not counted as a refusal;
  - the waits 30, 60, 120, 240 and 300 seconds.

  A refusal from Yahoo could not be produced from here.

**Disconnected notice** (the user chose "Notify me"):
- When the launcher and lock screen all let go of the service, the service is destroyed and the
  phone shows Google's cards. The report showed this at 04:35, after the launcher restarted at
  04:16.
- A notification now says so ("At a Glance Plus disconnected · Your home and lock screen went
  back to Google's at 04:35"), with Activate.
- It is not shown during the app's own activation or reset (wireless debugging busy). It is
  removed when a surface connects again.

## 0.41.6: fewer, gentler requests to Yahoo

The user shared community notes on Yahoo's unofficial limits: about 2,000–2,500 requests an hour
per IP address, sooner for bursts, refusals as HTTP 403, 999 or 429. The advice was to batch
tickers into one call and space requests out.

**The app's usage:**
- The stock screen while open makes 2 requests every 15 seconds, about 480 an hour.
- The card made one request per stock at its interval, back to back.

So the hourly total is under the limit. Bursts, and the address being shared with other phones
on mobile data (carrier-grade NAT), are the likelier triggers.

**Changes:**
- **The card's refresh is one request:** Yahoo's spark feed (/v7/finance/spark?symbols=…) gives
  every followed stock in one call. It carries the same meta and day's closes as the single
  chart call, read by the same code (quoteFrom). A stock the batch leaves out is asked for on
  its own. When Yahoo refused the batch, none are, so the app does not ask again at once.
- **The stock screen spaces its two requests:** the quote and the chart are now 400 ms apart
  rather than back to back.
- Refusals of any code (403, 999, 429…) are already treated alike since 0.41.5: logged, shown,
  and backed off.

**Checked against the live feed** (ONDS, NOK, AAPL, VOD.L): the batch and the single calls agree
on price, previous close, currency and name. The only differences were a fraction of a cent and
a couple of chart points, from being seconds apart. The card's refresh through the batch reads
"Updated".

## 0.41.7: after Android kills the app

**Reported:** the user's debug report showed "Killed (signal 9) (was foreground service)" at
01:02:38. The process started again 12 s later, but the launcher and lock screen stayed on
Google's cards and no notice came up. A signal-9 kill skips onDestroy, so the "disconnected"
notification from 0.41.5 never ran. The report does not say what sent the signal. A low-memory
kill by lmkd is normally reported as "Low memory", not as a signal.

**Changes:**
- **Check after a restart:** when the process starts again (not after an update or just after
  the phone started), the app waits 45 s. If no surface has connected and no activation is
  running, it posts the "disconnected" notification with Activate.
- **Only when wanted:** a new wants_active flag is set by a successful Activate and cleared when
  At a Glance is handed back to Google, so the notice does not appear after a reset. It is not
  backed up.
- **Stop reasons show memory:** each stop reason now includes the app's PSS and RSS when Android
  recorded them, and the report's "Now" section gives the memory the app is using.
- **System check reads the kill records:** the events log lines (am_kill, am_proc_died) for this
  app and the low-memory killer's lines, while the phone still holds them.

## 0.42.0: Finish setup asks for precise location and Unrestricted battery use

**Asked for:** the app should ask for precise location and to be set to Unrestricted. The user
chose both a card on Home and asking after Activate, and Android's own battery dialog rather
than setting it silently over wireless debugging.

**Found:** the Wallet page's "Use precise location" button asked for ACCESS_FINE_LOCATION on its
own. Android 12 and later ignore that request, so tapping it showed nothing. Precise location is
now asked for together with approximate. Android shows that as the "change to precise" dialog
when approximate is already allowed.

**Changes:**
- **Finish setup card on Home** (under the status card), listing only what is missing:
  - precise location, and then location all the time, when a Wallet pass that is switched on
    has places;
  - Unrestricted battery use, through Android's "let app always run in background?" dialog
    (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; new manifest permission
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).

  The card goes once nothing is missing.
- **After a successful Activate**, the app asks for each missing item in turn, the next time it
  is in front (Activate leaves for the home screen). Each item is asked this way only once; the
  card stays for anything still turned down.
- **After two refusals** Android no longer shows the precise-location dialog. From then on, the
  button opens the app's settings page, with directions to turn on Use precise location.
- The counts of refusals and of what was asked are not backed up.

**Checked:** rendered at 1.3× text; the card's text wraps beside the button and nothing is cut.

## 0.43.0: dark mode, Weather wash removed, precise location label, extended-hours volume

**Asked for:** cards that follow the phone's dark mode and Android theming; the weather's
location row said Approximate after precise was allowed; Weather wash dropped in favour of
Animated scenes; volume "None reported" on some stocks.

**Changes:**
- **Dark mode:** the Standard look uses Material You's dark tones in dark mode: weather panel
  accent1 800, chips panel accent2 800, tiles accent1 700, text accent1 50 and 200. Daytime
  scenes (Scenes and Animated scenes) get a deep blue veil (#80101A2E). Night scenes are
  unchanged. The service redraws the cards when the theme changes. The user approved a preview
  rendered from the card code.
- **Weather wash removed:** its look, drawables and generator are gone. A saved Weather wash
  choice reads as Animated scenes.
- **Location label:** the weather's location row says Precise or Approximate, matching the
  permission. Either way, only coordinates rounded to two decimals (about 1 km) are sent to
  Open-Meteo.
- **Volume:**
  - **Found:** Yahoo reports no pre-market or after-hours volume. eToro only has it for the
    stocks it trades around the clock (not NOK, which it trades in regular hours only).
  - **Fill from Nasdaq:** those candles now take the exchanges' volume from Nasdaq's public
    intraday chart (api.nasdaq.com …/chart?charttype=rs). It covers 04:00 to 20:00 New York
    time, minute by minute, for the latest day only. It is asked for at most once a minute per
    stock, and only when a candle still lacks volume.
  - **Same scale:** the volume is on the same scale as regular hours, so the VWAP does not
    restart for it.
  - **Forming candle:** the candle still forming (stamped with its latest trade's time) is
    matched to eToro's and Nasdaq's candles by the start of its step.

**Checked against the live feeds:**
- NOK: all 112 extended-hours candles of the latest day are filled. The previous day's 98 stay
  empty, because Nasdaq has only the latest day.
- ONDS: 2 candles stay empty.
- AAPL: 1 candle stays empty, the 04:00 candle.

## 0.43.1: Pixel Buds battery for Widgets Plus, faster stock screen

**Asked for:** Widgets Plus (the user's own app, com.widgetsplus) shows Pixel Buds' left, right
and case battery. Android only prints those in `dumpsys bluetooth_manager`, which needs DUMP, a
permission only the shell can grant. The user agreed that At a Glance Plus grants it during
activation (no broadcast receiver), and that System check shows the Buds' battery lines.

**Changes:**
- **Grant during activation:** when com.widgetsplus is installed and does not hold DUMP, the
  activation adds `pm grant com.widgetsplus android.permission.DUMP`.
  - The package and the command are fixed in the code.
  - The installed app must have exactly one signer, with SHA-256
    A6:5E:88:7A:4C:DD:8B:1B:AC:2B:7E:49:13:01:8F:BA:17:A1:FE:B4:27:4E:64:1F:FC:02:6F:E2:28:6C:D5:91.
    Otherwise nothing is granted and the history says so.
  - The step's result goes in the history ("Let Widgets Plus read Pixel Buds' battery").
  - Nothing outside the app can trigger it.
- **System check shows the Buds' battery:** it adds the Bluetooth model names and
  untethered_left, right and case battery levels from `dumpsys bluetooth_manager`, with no
  addresses.
- **System check shows the menu's launches:** it adds the last activity launches by the
  launcher, SystemUI or Google's At a Glance. This finds what the press-and-hold menu's Settings
  item opens.
- **Stock screen no longer waits for Nasdaq:**
  - **Found:** 0.43.0's Nasdaq volume request takes 2 to 3 seconds and held up the chart.
  - **Fix:** it now runs in the background; the screen uses the last answer, and the next 15 s
    refresh picks up a new one.
  - **Asked less often:** it is only asked for when the latest day has a candle without volume.

## 0.43.2: System check, a closer look

**Found:**
- **Settings in the press-and-hold menu:** the user's System check showed the home screen's
  Settings item opening com.android.launcher3.proxy.ProxyActivityStarter, the launcher's way of
  starting another app's PendingIntent. The page it then opened was not in the filtered lines.
- **Buds battery:** the filter for model_name and untethered_*_battery found nothing in
  `dumpsys bluetooth_manager` (exit 1).

**Changes:**
- **Wider launch filter:** the launch lines now also take Android System Intelligence
  (com.google.android.as), and leave out ordinary app launches from the home screen.
- **Where the Bluetooth metadata is:** a second Bluetooth line shows any metadata or battery
  lines, with addresses masked, cut to 240 characters, at most 15.

## 0.43.3: System check, Bluetooth dump sections

**Changes:** two more System check lines, to tell why the Buds' battery search found nothing:
- the Bluetooth dump's length in lines, since a few dozen means the adapter's part is missing;
- whether its "Enabled Profile Services", "ScanMode" and "Metadata:" sections are there.

Neither prints addresses.
