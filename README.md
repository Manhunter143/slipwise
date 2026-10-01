# Slipwise

A monthly budget app for Android. Four pots: Groceries, Baby Fund and Huis Kaffee each have a limit, End of month payments just add up. Snap a till slip, Claude reads the total and suggests the pot, and the photo is kept with the slip. Everything is stored on the phone.

## Get the APK

Every change pushed to `main` builds a new APK automatically.

1. Open the **Releases** section of this repository (right-hand side on GitHub).
2. Download **Slipwise.apk** from the newest release.
3. Send it to the phone (WhatsApp, email or USB) and open it. Android will ask to allow installing apps from that source. Allow it once.

Installing a newer APK updates the app and keeps all slips and photos.

## First run

- Enter the monthly limit and the day the month starts (e.g. payday).
- Paste a Claude API key from [console.anthropic.com](https://console.anthropic.com/settings/keys) to have slips read automatically. Without a key, photos are still saved and totals are typed by hand.
- Settings › Back up saves slips and photos to `Download/Slipwise`. Keep a copy somewhere safe.

## Where things live

| Path | What it is |
| --- | --- |
| `app/src/main/assets/index.html` | The whole app (screens, budget logic, slip reading) |
| `app/src/main/java/za/slipwise/app/MainActivity.java` | Android shell: camera, saving files, back button |
| `.github/workflows/build.yml` | Builds and publishes the APK |
| `app/slipwise.keystore` | Signing key. Keep it so updates install over the old app. |

The same `assets` folder also works as a web app: upload it to any static host and use "Add to Home screen".
