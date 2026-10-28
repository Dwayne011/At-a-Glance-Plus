# At a Glance Plus

A self-contained replacement for the content of Google's At a Glance on Pixel phones, with
everything built in: no plugins, no Shizuku.

Status: **milestone 1**. The app takes over the native At a Glance slot on the home screen,
lock screen and always-on display and shows one test card, drawn either as a Google template or
as the app's own layout. Its purpose is to prove the system hook and measure what the slot allows
on a real phone before the real features are built.

## Requirements

- Pixel phone, Android 16 or newer (the slot honours custom layouts from Android 15 QPR2 / 16).
- A computer with `adb`, for the one privileged step. A later milestone removes this.

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
2. Enable USB debugging and connect the phone.
3. Copy the activation command from the app and run it on the computer. SystemUI and the launcher
   restart.
4. The home screen and lock screen now show the test card. Switch between "Google template" and
   "Own layout" in the app to compare the two rendering tiers.
5. Use the "Back to Google" command to restore the stock provider.

## What to look for on the phone

These are the things that cannot be verified without a device:

- Does the launcher scale, crop or wrap the custom layout card, and at what size?
- Does the lock screen draw the custom layout, and does the always-on display?
- Does tapping the card open the app from the lock screen without an unlock?
- Does the card survive a launcher restart and a screen rotation?

## Roadmap

1. Milestone 1: system hook and test card (this).
2. Automatic activation over wireless debugging after boot, so the computer is no longer needed.
3. Generic sources: Live Updates from any app, media from any player, Wallet passes.
4. Rules engine: show a card when near a place, when navigating somewhere, at a time, on a network.
5. Place categories from OpenStreetMap.
