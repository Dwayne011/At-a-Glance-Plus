# At a Glance Plus

A self-contained replacement for the content of Google's At a Glance on Pixel phones, with
everything built in: no plugins, no Shizuku.

Status: **milestone 2**. The app takes over the native At a Glance slot on the home screen,
lock screen and always-on display and shows one test card, drawn either as a Google template or
as the app's own layout. It does the privileged step itself over the phone's own wireless
debugging, with no computer, and repeats it after every reboot.

## Requirements

- Pixel phone, Android 16 or newer (the slot honours custom layouts from Android 15 QPR2 / 16).
- Developer options with Wireless debugging available (any Pixel). A computer with `adb` is an
  optional fallback.

## How it works

Pixel Launcher and SystemUI do not produce At a Glance content themselves. They ask the hidden
`SmartspaceManager` system service, which forwards to one configured provider: Android System
Intelligence on a stock Pixel. This app contains its own provider (`AtAGlanceService`) and is made
the active one with a shell command the system exposes for testing:

```
cmd smartspace set temporary-service <userId> <component> <durationMs>
```

The override is temporary, but the system caches its binding to whichever provider was active
when a client opened its session. So the activation command sets the override and restarts
SystemUI and the launcher; they reconnect to this app and stay connected until the phone reboots
or the app's process is killed.

The hidden system classes are compiled against the stubs in `systemstubs/` (signatures from the
Android Open Source Project) and provided by the framework at runtime.

## Build

```
./gradlew :app:assembleDebug
```

Needs the Android SDK; Gradle fetches platform 37 (needed by the current Compose libraries) and build tools on first run. The APK lands in `app/build/outputs/apk/debug/`.

## Try it

1. Install the debug APK and open the app. The device checks at the top should all read OK.
2. Connect to Wi-Fi and turn on Wireless debugging in Developer options.
3. Tap "Pair with this phone". Developer options opens; choose "Pair device with pairing code"
   and keep that dialog open. A notification from the app asks for the six-digit code; type it
   there. The app pairs, grants itself the secure-settings permission, and activates: SystemUI and
   the launcher restart.
4. The home screen and lock screen now show the weather card, plus a card for whatever is going
   on (music, a timer, the next event, a delivery, directions) while it is going on. The Cards
   tab chooses which kinds may appear; the Chips tab sets up the complication chips.
5. "Back to Google" restores the stock provider. "Activate" switches back.
6. After a reboot the app waits for Wi-Fi, switches wireless debugging on and re-activates by
   itself (switch in the app).

## How the wireless debugging path works

The app contains an ADB client (derived from Shizuku, Apache 2.0, see NOTICE). Pairing uses
adb's protocol: TLS, then SPAKE2 with the pairing code and the exported TLS keying material as
the password, then an encrypted exchange of public keys. After that adbd trusts the app's
certificate, and the app can connect to `127.0.0.1` on the port adbd advertises over multicast
DNS and run shell commands. Wireless debugging only runs on Wi-Fi, so activation needs a Wi-Fi
connection.

## Verified on a device

Pixel on Android 17 (API 37), Pixel Launcher:

- Pairing and activation over wireless debugging work with no computer.
- The custom RemoteViews layout renders in the native slot on the home screen and the lock screen.
- SystemUI opens five sessions with the provider, all asking for up to five targets:
  `home` (Pixel Launcher), `lockscreen`, `ambientcue`, `glanceable_hub` and `dream` (SystemUI).
  The app currently answers only `home` and `lockscreen`.
- Replacing Google's provider also removes Google's date and weather line, so the app supplies
  its own from Open-Meteo (https://open-meteo.com), free and keyless. Open-Meteo picks each
  country's own high-resolution model where one exists (Met Office 2 km in the UK) and global
  models elsewhere. Only city-level coordinates are sent.

Still to check: the always-on display, tapping the card from the lock screen, and re-activation
after a reboot.

## Roadmap

1. Milestone 1: system hook and test card.
2. Milestone 2: pairing and activation over wireless debugging, including after boot.
3. Date and weather line (Open-Meteo, worldwide) and unlock-before-open for every card tap.
4. Every card layout from the design spec, with a preview mode filled with the spec's sample
   content; complication chips; always-on display look. See docs/spec-deviations.md.
5. Live complication chips: Gmail per account, missed calls, texts, alarm, screen time, steps and
   sleep (Health Connect), sunrise or sunset, battery, earbuds (experimental), countdown, app
   notifications; each chip's tap target can be changed.
6. Real, contextual cards: media, calendar, timers and alarms, Live Updates, navigation, and a
   pop-up for new messages (this).
7. Wallet passes by place.
8. Rules engine: show a card when near a place, when navigating somewhere, at a time, on a network.
9. Place categories from OpenStreetMap.
