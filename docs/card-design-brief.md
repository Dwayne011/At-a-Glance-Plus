# Card design brief

What a designer needs to mock up cards for At a Glance Plus. Every card is drawn inside the
native At a Glance slot on a Pixel (Android 16 or newer) using Android's RemoteViews, the same
technology as home screen widgets. That sets firm limits, listed below. Anything outside them
cannot be built, however good it looks.

Verified on a Pixel running Android 17: custom cards draw on the home screen and the lock
screen. The current weather card is the reference implementation (`app/src/main/res/layout/card_weather.xml`).

## 1. Canvas

| Item | Value | Notes |
|---|---|---|
| Design frame | 412 × 88 dp | A standard Pixel screen is 412 dp wide. Design at 1×, in dp. |
| Card body | 380 × 76 dp, centred | Leaves the launcher's side margins and some breathing room top and bottom. |
| Corner radius | 24 dp | Matches the current cards and Pixel's own. |
| Inner padding | 16 dp left, 18 dp right | Text and icons stay inside this. |
| Height | Fixed | The slot is a single 4×1 widget row. Designs taller than 88 dp get squashed or cropped. |
| Width | Flexible | The launcher stretches the card to the screen width. Lay out with one flexible column (usually the text) so it works from 360 to 450 dp wide. |

The launcher decides the final size. Expect it to be within a few dp of the above; the first
build of each design is checked on the phone and tuned.

## 2. What the card can contain

Allowed building blocks only:

- **Text**: any number of text lines, but realistically 1 to 3 at these sizes.
- **Images**: icons and pictures (vector icons preferred, PNG for photos or album art).
- **Progress bars**: horizontal bars, including several side by side to make segments.
- **Buttons and tap areas**: several per card, each opening something different.
- **Shapes**: solid colours, gradients, rounded corners, strokes, all as backgrounds.
- **Live clock and timer text**: the system can tick a time or countdown without the app redrawing.

Not possible:

- Blur, frosted glass, drop shadows, glows, or any effect that needs to see what is behind the card.
- Scrolling, swiping inside the card, or lists. One card shows one fixed view.
- Custom-drawn shapes beyond rounded rectangles and circles (draw them as images instead).
- Animation, other than the system ticking a clock or timer.
- Text along a path, rotated text, or mixed fonts inside one line beyond bold/regular weight changes.

## 3. Type

- Pixel's system fonts are available: regular, light, medium, condensed. Name the weight in the mockup (for example "sans medium 17 sp").
- A custom font is possible if supplied as a licensed `.ttf` or `.otf` file. One family with two or three weights at most.
- Sizes are in sp and grow with the user's font-size setting. Leave about 30 % slack on every text line, and say which text should truncate with "…" when it does not fit.
- Current scale, for reference: 36 sp large number, 17 sp title, 13 sp detail, 12 sp small label.

## 4. Colour and contrast

- The card sits on the user's wallpaper, which can be bright or dark. Every card needs a background of its own (solid or gradient, can be partly transparent) so text stays readable on any wallpaper.
- Text contrast at least 4.5:1 against the card background.
- Optional: colours can follow the phone's Material You palette (taken from the wallpaper). Mark any colour that should do this as "dynamic accent" rather than a hex value.
- Give every colour as a hex value with alpha, for example `#E61B2550`.

## 5. Variants to mock for each card

| Variant | Why |
|---|---|
| Home screen | Can include the date (the launcher no longer shows it once we take over). |
| Lock screen | No date: the lock screen already shows it beside the clock. Taps first ask the user to unlock. |
| Always-on display | The same card is shown on the always-on display. Needs a low-power version: black background, thin dim text and outline icons, no large filled areas (they risk screen burn-in). |
| Empty or loading | What the card shows before data arrives or when something is missing (for example "Location needed for weather"). |
| Long content | The longest realistic text (long restaurant name, long song title) to prove truncation works. |
| Large font size | The home screen variant with the system font size at maximum. |

## 6. Interaction

- A tap target should be at least 48 × 48 dp. On a 76 dp card that allows up to about five buttons in a row.
- Say what each tap does. Whole-card tap is the default; buttons (for example play and pause) are extra.
- From the lock screen every tap asks for an unlock first. This is deliberate and not configurable per card.

## 7. Cards planned, and the data each can show

Design within these fields. Anything not listed needs checking before it is mocked up.

| Card | Data available |
|---|---|
| Weather (exists) | Condition, temperature, feels-like, today's high and low, day or night. Hourly or tomorrow's forecast can be added. |
| Live Update (any app's food order, taxi, delivery, flight) | App name and icon, title ("Rider is on the way"), short text, progress as a value or as up to about four segments with points, ETA or countdown, large app icon or picture if the app supplies one. Exact fields depend on what each app puts in its notification. |
| Media (any player) | Title, artist, album art, playing or paused, progress through the track, previous / play-pause / next buttons. |
| Wallet pass near a place | Pass name, merchant or event name, barcode or QR code, colour of the pass, distance to the place. |
| Navigating to a place | Destination name, ETA, distance left, next turn text and icon. Taken from Google Maps' navigation notification. |

## 8. Deliverables

- Mockups at 1× in dp (Figma, or SVG/PNG exports at 1× and 3×).
- Each element labelled with size (dp), font, weight and size (sp), colour (hex with alpha), and spacing (dp).
- Icons as simple SVGs: solid fills and strokes only, no masks, filters, blurs or embedded images. They are converted to Android vector icons.
- Backgrounds as colour or gradient values (angle, start and end colour), not as images, unless a picture is intended.
- Any custom font file and its licence.
