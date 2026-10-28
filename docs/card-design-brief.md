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
- Moving between cards: on the home screen the launcher pages horizontally, with dots under the
  card. Vertical swiping is not possible, because the launcher owns the gesture and a card cannot
  detect swipes. Two optional alternatives can be mocked: small up and down arrows on the card
  that cycle cards on tap, or a "peek" line showing the next card's headline (for example
  "Next: Gym at 18:00").

## 7. Complications

Complications are small chips (icon plus a count or a few characters) that sit inside any card,
in the style of Action Launcher's. The user chooses which ones appear and on which cards. They are
drawn in our own layout, so their look is entirely up to the design, within the limits above.

### 7.1 Where they go: two slot layouts

Each card design picks one of these. Mock both for the weather card so we can compare.

| Layout | Space | Holds | Main content keeps |
|---|---|---|---|
| **Right column** | 84 dp wide, full card height, right edge inside the padding | Up to 2 chips stacked | About 260 dp of width, all 3 text lines |
| **Bottom strip** | Full content width, 24 dp tall, 6 dp above the bottom padding | Up to 4 chips in a row | 2 text lines above the strip |

Content area inside the card body, after padding: about 346 × 76 dp. Both layouts must still
work when the user has chosen no complications (the space is given back to the main content).

### 7.2 The chip

| Item | Value |
|---|---|
| Visual height | 24 dp |
| Corner radius | 12 dp (pill) |
| Icon | 16 dp, simple SVG |
| Text | 12 to 13 sp, medium weight, at most 6 characters ("99+", "07:30", "3h 12m") |
| Padding | 8 dp left and right, 4 dp between icon and text |
| Gap between chips | 6 dp |
| Width | Fits its content; 80 dp maximum |

Tapping: in the right column each chip is its own tap zone (about 84 × 36 dp, opening the
related app). In the bottom strip the chips are too small to tap reliably, so a tap anywhere on
the card opens the card's own target. Say in the mockup if you would rather trade content space
for tappable chips.

States to mock for the chip: normal, needs attention (for example a missed call), large number
("99+"), and the always-on display version (outline only, dim). A complication with nothing to
report (0 emails) is hidden rather than shown as zero.

### 7.3 Complications available, with sample values for mockups

| Complication | Sample | Source |
|---|---|---|
| Gmail unread (per label) | ✉ 4 | Gmail's own unread count |
| Missed calls | ☎ 2 | Call log |
| Unread texts | 💬 3 | Messages |
| Next alarm | ⏰ 07:30 | System alarm clock |
| Screen time today | 3h 12m | Android usage stats |
| Steps today | 6,214 | Health Connect |
| Sleep last night | 7h 40m | Health Connect |
| Sunrise or sunset (whichever is next) | 16:42 | Calculated from location |
| Phone battery | 67% | Battery service |
| Earbuds battery | 100% | Bluetooth, if the earbuds report it |
| Countdown to a date | 12 days | Set in the app |
| Any app's notifications (WhatsApp, Outlook, Instagram…) | App icon + 5 | Notifications currently showing |

The emoji above stand in for icons only; the design supplies its own.

## 8. Cards to mock up

Mock each card in the variants from section 5. Design only with the fields listed; anything else
needs checking first. Sample content is given so mockups look realistic.

### 8.1 Weather (exists, needs the complication space)

- **Purpose:** the first card most of the time, replacing Google's date and weather line.
- **Fields:** condition and icon, temperature, feels-like, today's high and low, day or night,
  date (home screen only). Optional additions on request: next few hours, tomorrow.
- **Sample:** Cloudy, 13°C, H 14° · L 8° · Feels 11°, Wed 28 Oct.
- **States:** each condition (clear day, clear night, partly cloudy, cloudy, fog, rain, snow,
  thunderstorm), and "Weather: location needed" when there is no data.
- **Complications:** yes, either layout.

### 8.2 Live Update (any app)

- **Purpose:** a food order, taxi, parcel or flight in progress, from any app that posts an
  Android Live Update or progress notification.
- **Fields:** app name and small icon, title, one line of text, progress (a value, or up to four
  segments), ETA or a live countdown, optional large picture from the app.
- **Sample:** Deliveroo · "Rider is on the way" · "Order #4821" · 3 of 4 steps · 12 min.
- **States:** in progress, nearly there, arrived or done, and an app that sends text only
  without progress (the card must still look right).
- **Complications:** optional; the progress usually needs the space.

### 8.3 Media (any player)

- **Purpose:** what is playing, with controls.
- **Fields:** title, artist, album art, playing or paused, track progress, and three buttons:
  previous, play or pause, next.
- **Sample:** "Dreams" · Fleetwood Mac · Spotify · 1:42 of 4:14.
- **States:** playing, paused, no album art, very long title.
- **Complications:** not recommended; the buttons need the room.

### 8.4 Next calendar event

- **Purpose:** what Google used to show: your next event, shortly before it starts.
- **Fields:** event title, start time or "in 25 min", location, calendar colour.
- **Sample:** "Gym – Upper Body B" · 18:00 · in 25 min · PureGym Holborn.
- **States:** starting soon, happening now, all-day event, no location.
- **Complications:** yes.

### 8.5 Timer and next alarm

- **Purpose:** a running timer or stopwatch, or tomorrow morning's alarm in the evening.
- **Fields:** label, live countdown or count-up, alarm time.
- **Sample:** "Pasta" · 6:42 left. Or: "Alarm 07:30 tomorrow".
- **States:** running, paused, finished, alarm only.
- **Complications:** yes.

### 8.6 Wallet pass near a place

- **Purpose:** a loyalty card, ticket or pass that appears when you are near the place it belongs to.
- **Fields:** pass name, merchant or event, barcode or QR code, pass colour, distance.
- **Sample:** "Tesco Clubcard" · Tesco Extra · 120 m · barcode.
- **States:** barcode, QR code, no code (a ticket opened in Wallet).
- **Complications:** not recommended; the code needs the room and must stay scannable.

### 8.7 Navigating to a place

- **Purpose:** while Google Maps is navigating, shows the trip, and can pair with a rule
  (for example "show my rail ticket when navigating to Piccadilly").
- **Fields:** destination, ETA, distance left, next turn text and icon.
- **Sample:** Manchester Piccadilly · ETA 15:10 · 2.4 mi · "Turn left onto A57".
- **States:** driving, walking, arriving.
- **Complications:** optional.

### 8.8 Complication chip

The chip from section 7.2 as its own component sheet: every complication's icon, the four chip
states, and the always-on display version.

## 9. Deliverables

- Mockups at 1× in dp (Figma, or SVG/PNG exports at 1× and 3×).
- Each element labelled with size (dp), font, weight and size (sp), colour (hex with alpha), and spacing (dp).
- Icons as simple SVGs: solid fills and strokes only, no masks, filters, blurs or embedded images. They are converted to Android vector icons.
- Backgrounds as colour or gradient values (angle, start and end colour), not as images, unless a picture is intended.
- Any custom font file and its licence.
