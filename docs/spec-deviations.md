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
| Weather card (A+C hybrid spec): fixed slate background, date on line 1 | The background still follows the weather, as chosen: a plain colour (Standard), or a picture of the weather and time of day (Scenes, the default), still or moving (Animated scenes, which uses more battery). On the lock screen line 1 is the condition, since the date is beside the clock. The left pill is the first chip in the user's list. All three variants are offered, plus "No chips". | User choices. |
| Timer reads "6:42" | Reads "06:42". | The live countdown is the system Chronometer, which always pads minutes; the paused value matches it. |
| Message toast with 340 ms morph in, 280 ms out, in a `ViewFlipper` inside every card | The toast is pushed as its own card in first place for 4.5 s, then removed. | The launcher draws the change between cards; the app cannot set the curve. |
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
| Live update | Android 16 Live Updates (promoted ongoing or progress-style notifications) from any app | While the app keeps it up, two at most |
| Timer / alarm | A countdown timer notification from a clock app; otherwise the next alarm | Timer while it exists; alarm within 12 h |
| Media | The playing media session | While playing, and 5 min after pausing |
| Calendar | The phone's visible calendars | From a chosen lead time (1 h by default) until the end, for events of 4 h or less; all-day events optional |
| Finance | Yahoo Finance's public chart feed for the user's tickers (Google Finance has no public data) | On big moves, every few hours for 10 minutes, and/or in time windows, as chosen |
| Sports | ESPN's public scoreboard feed (unofficial) for followed teams and Formula 1; live scores refresh every 30 s | During a game, for a chosen time before and after it, and the next fixture if switched on; one card per game. Tapping opens Google's page for the game (ESPN's if chosen); Next on the F1 card steps through the weekend's sessions for a minute |
| Torch | The phone's torch, however it was turned on | While it is on. A tap turns it off; brightness by tapping a step of the bar or − / + (cards cannot follow a drag, so there is no slider) |
| Extra chips | Chips that have something to report but no room on the cards on show | Only then |
| Wallet | None yet (needs the place rules engine) | Never, for now |

Card text and layout read notification contents as each app posts them, so wording can differ
between apps (for example the split between a distance and an instruction on navigation).
