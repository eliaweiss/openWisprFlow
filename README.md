# openWisprFlow

An open-source Android dictation app. Focus any text field, tap the floating bubble, talk, and the text is inserted at the cursor. Speech recognition and optional cleanup run through [Vercel AI Gateway](https://vercel.com/docs/ai-gateway) with your own API key. There is no backend and no account.

The design is in [docs/HLD.md](docs/HLD.md).

## Status

Build step 2 of 8. The bubble appears while a text field is focused and the keyboard is open. Tapping it starts a simulated dictation; after 3 seconds a test phrase is inserted only if the same field is still focused and unchanged, and otherwise it is shown with Copy. Tapping the bubble again cancels. Recording and transcription are not implemented yet.

## Requirements

- Android 13 (API 33) or later
- JDK 17 and the Android SDK (platform 35) to build

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open openWisprFlow and tap **Open accessibility settings**, and turn on **openWisprFlow dictation**.

If the switch is greyed out as a "restricted setting" (common for apps installed from a downloaded APK), open App info for openWisprFlow, use the ⋮ menu, choose **Allow restricted settings**, and try again.

## Privacy

The accessibility service can read the focused field and type into it. It is used only for dictation. Password, number and phone fields are excluded. Once transcription is added, audio and field context go to AI Gateway for the active dictation only, using the key you enter in the app.
