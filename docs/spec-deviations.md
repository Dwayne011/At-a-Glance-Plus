# Where the build differs from the master spec

The cards follow `At_a_Glance_Plus_Master_Prompt__Spec.md`. These are the places where Android,
or the way At a Glance is hosted, does not allow the spec exactly, and what was done instead.

## Platform limits

| Spec | What the build does | Why |
|---|---|---|
| Calendar title and location both marquee; `setBoolean(…, "setSelected", true)` | Long text ends in "…". | `View.setSelected` cannot be called through RemoteViews. Scrolling by focus only worked after returning to the home screen, because the launcher decides focus. |
| Equaliser as an `AnimatedVectorDrawable` in an `ImageView` | The animated drawable is hosted in an indeterminate `ProgressBar`. | An animated vector in a plain `ImageView` does not start inside RemoteViews; a `ProgressBar` starts it by itself. |
| 500 ms long-press to dismiss, with progress bar, scale and shake | The top bar fills while the card is held (red, amber on weather), timed to the touch-and-hold delay, then the launcher's menu offers Dismiss, which removes the card like the ✕. No scale, shake or exit animation. | RemoteViews has no long-press, and the app is not told about a press. The bar is the card's own pressed-state animation, which Android plays without the app. |
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

## Preview content

The weather card and the complication chips have live data (the rail-ticket chip on the
navigation preview stays a sample). The other cards show the spec's sample content until their
data sources are built. Where the spec gives a sub-state
without full text, the build reuses the spec's other sample text:

- Calendar "Happening now" and "All day" keep the sample title; only the time line and bar
  colour change.
- Navigation "Walking" and "Arriving" keep the sample destination line.
- Weather "Clear night" has no detail line, since the spec gives only the temperature.
- The media previous and next buttons reset the sample time to 0:00, so a tap visibly works.
