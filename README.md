# Volta

An Android app for MG electric cars in India: your car's battery, range and charging on your phone, and trip plans
with charging and food stops worked out for the road ahead.

Volta talks to MG's i-SMART service with your own owner account, and to Google Maps Platform with your own API key.
There is no Volta server and no analytics: your login and key stay on your phone.

> Volta is an independent project. It is not made by, endorsed by or affiliated with MG Motor India or SAIC.
> "MG" and "i-SMART" are their trademarks, used here only to say what the app works with.

## What it does

- **Dashboard:** battery, the range you'll really get, charging, locks, doors, windows, tyres and the 12 V battery,
  read from your car. Alerts when something needs a look.
- **Remote controls:** lock, unlock, AC, find and windows, each confirmed with your fingerprint and your MG control PIN.
- **Trip planner:** routes with their climb, fast-charging stops with a backup for each, and meal stops at places
  that are open when you get there. Behind-plan alerts re-plan from where you are.
- **Energy:** every charging session priced on your home tariff or public rates.
- **Home charger:** start and stop an Exicom Spin home charger over Bluetooth, from the dashboard. Only the Exicom Spin
  is supported; other home chargers aren't.
- **Smart pre-cool:** suggests cooling the car before you walk out. It always asks first.
- **Android Auto** and a home-screen widget.

Only tested on the MG Windsor EV so far. **The Hector Tomahawk EV can't sign in yet:** it's on MG's newer i-SMART 3.0,
and MG's sign-in answers Volta with "the service is not available". Accounts with more than one car can pick which one
Volta uses in Settings → Account. The battery size is measured from the car after a few reads, so a Windsor Pro's larger pack is picked up on its own. Other MG EVs on i-SMART should sign in and show their data, but none has been
run yet. The Comet EV, ZS EV, M9, Cyberster and Hector Tomahawk EV have their own pictures, with only the 360° view still
pending, and anything else shows the Windsor. Their battery size is measured the same way; efficiency can be set in Settings → Car until model profiles arrive.

## What you need

- An MG EV with an i-SMART **owner** account (India). Shared accounts don't receive battery data.
- An Android phone, Android 10 or newer.
- A Google Maps Platform API key of your own, for trip planning. [How to create one and cap its cost](docs/GOOGLE-API-KEY.md).

The release APK already has the key for the map itself. It's restricted to the Maps SDK and to this app, so your own
key is only used for routes, places and elevation.

## First run

1. **Sign in** with your i-SMART mobile number and password.
   Signing in here signs the official MG app out (MG allows one session), and every wrong password counts against
   MG's daily limit, so Volta never retries a rejected password.
2. **Add your Google key.** Paste it and tap *Check key*. Volta makes one small request to each API it uses and
   tells you exactly what Google said if something is off (API not enabled, key restricted to another app, no
   billing). The key is saved only when every check passes. You can skip this and add it later.

## Your data stays on your phone

Volta has **no servers of its own**. There is no Volta account, no cloud sync, no analytics and no crash reporting.

- **Where your car data comes from:** MG's i-SMART servers, the same ones the official MG app uses, with your own
  login. Volta reads it there and shows it to you; it never sends it anywhere else.
- **What's stored, and where:** your MG login and session, your Google key and Volta's settings are kept only on
  your phone. The login and the key are encrypted (AES-256-GCM, Android Keystore key) and excluded from backups.
- **Who Volta talks to:** MG's i-SMART servers, and Google Maps Platform with your own key for trip planning.
  Nobody else.
- **Uninstalling deletes everything.** Nothing is kept anywhere, so after reinstalling (or on a new phone) you sign
  in and add your Google key again.

More detail in [docs/PRIVACY.md](docs/PRIVACY.md).

## Build from source

```bash
git clone https://github.com/irshadm/volta-android.git
cd volta-android
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests for every module
```

Needs JDK 21 and the Android SDK (Android Studio sets both up). No keys are needed to build: the Google key is
entered in the app. See [docs/BUILD.md](docs/BUILD.md).

## How it's built

Kotlin, Jetpack Compose, Material 3, Hilt, Coroutines and Flow, OkHttp, kotlinx.serialization, DataStore.
A multi-module, layered codebase: pure-Kotlin domain and API modules, Android data and UI modules on top.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) walks through it.

## Thanks

Volta's connection to the car is a Kotlin port of
[mg-ismart-india-client](https://github.com/john-lazarus/mg-ismart-india-client) by **John Lazarus**, who worked out
how the MG India app talks to MG's servers. None of the car features would exist without it. Thank you, John.

Icons are from [Lucide](https://lucide.dev). Full licence texts are in [NOTICE.md](NOTICE.md).

## Licence

[Apache License 2.0](LICENSE). Use it, change it, share it or build on it, commercially or not, as long as you keep
the licence and notices. Third-party code and icons keep their own licences; see [NOTICE.md](NOTICE.md).

Contributions are welcome under the terms in [CONTRIBUTING.md](CONTRIBUTING.md).
