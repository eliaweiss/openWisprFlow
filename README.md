# openWisprFlow

An open-source Android dictation app. Focus any text field, tap the floating bubble, talk, and the text is inserted at the cursor. Speech recognition and optional cleanup run through [Vercel AI Gateway](https://vercel.com/docs/ai-gateway) with your own API key. There is no backend and no account.

The design is in [docs/HLD.md](docs/HLD.md).

## Status

Build step 4 of 8. The bubble appears while a text field is focused and the keyboard is open. Tap it to record, tap again to stop, long-press to cancel. The take is transcribed by `google/gemini-3.5-transcribe` through AI Gateway with your own key, and the transcript is inserted only if the same field is still focused and unchanged; otherwise it is shown with Copy. Cleanup with a second model comes in step 6.

## Requirements

- Android 13 (API 33) or later
- JDK 17 and the Android SDK (platform 35) to build

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open openWisprFlow, tap **Open accessibility settings** and turn on **openWisprFlow dictation**, tap **Allow microphone**, and paste your [AI Gateway API key](https://vercel.com/docs/ai-gateway/authentication-and-byok), then **Save** and **Test**.

To type a long key without retyping it, focus the key field on the phone and run (adjust the variable name and path):

```bash
adb shell input text "$(grep '^AI_GATEWAY_API_KEY=' path/to/.env | cut -d= -f2-)"
```

If the switch is greyed out as a "restricted setting" (common for apps installed from a downloaded APK), open App info for openWisprFlow, use the ⋮ menu, choose **Allow restricted settings**, and try again.

## Privacy

The accessibility service can read the focused field and type into it. It is used only for dictation. Password, number and phone fields are excluded. Audio goes to AI Gateway for the active dictation only, using the key you enter in the app. The key is stored encrypted with an Android Keystore key and is never logged.
