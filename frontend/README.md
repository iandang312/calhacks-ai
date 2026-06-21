# Frontend — Android (Java)

Android app that streams microphone audio directly to Deepgram's real-time
Speech-to-Text API over a WebSocket (no backend in the loop).

## Setup

1. Open the `frontend/` folder in **Android Studio** (it will generate the
   Gradle wrapper and sync dependencies on first open).
2. Copy `local.properties.example` to `local.properties` and add your key:
   ```
   DEEPGRAM_API_KEY=dg_xxxxxxxxxxxxxxxxxxxxxxxx
   ```
   `local.properties` is gitignored, so the key stays out of version control.
   It is injected into `BuildConfig.DEEPGRAM_API_KEY` at build time.
3. Run the app on a device or emulator, tap **Start**, grant the mic
   permission, and speak. Interim words show in `[brackets]`; finalized text
   accumulates above.

## Layout

| Path | Role |
|------|------|
| `app/src/main/java/com/calhacks/ai/stt/DeepgramSttClient.java` | WebSocket client for Deepgram streaming STT |
| `app/src/main/java/com/calhacks/ai/stt/AudioStreamer.java` | Mic capture (16 kHz / 16-bit / mono PCM) |
| `app/src/main/java/com/calhacks/ai/stt/SttCallback.java` | Transcript / error callbacks |
| `app/src/main/java/com/calhacks/ai/MainActivity.java` | Demo wiring it all together |

## Note on the API key

Because the app calls Deepgram directly, the key is embedded in the APK and can
be extracted. That is fine for a hackathon demo. If this ever ships, move the
key behind a small proxy (the empty `backend/` folder is reserved for that) and
have the app talk to the proxy instead.
