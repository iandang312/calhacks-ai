# android/ — EchoMind Android Sandbox Environment

## What this module is

A pure **sandbox executor**: it receives a structured command from the Python agent, executes it on the Android device via the Accessibility API, and returns. It has no voice input, no intent parsing, no conversation state. All of that lives in the Python layer.

**Do not add** STT, LLM calls, or intent logic here. SRP: this module's only job is device control.

---

## Data flow

```
Python agent (agent/server.py)
  ↓  POST /agent/run  {"task": "..."}
  ↓  Python calls uiautomator2 directly (bypasses Android HTTP layer for now)

DaisyService  ← receives ParsedCommand from external caller
  ↓
DaisyAccessibilityService  ← executes the action on-device
  ↓
Real Android UI (Accessibility API gestures / node actions)
```

The Python agent currently drives the device via `uiautomator2` (ATX agent on the device). `DaisyService` is the future Java/HTTP path when the Java side needs direct control.

---

## Key classes

| Class | File | Owns |
|-------|------|------|
| `MainActivity` | `MainActivity.kt` | Permission setup, service launch, setup-status UI |
| `DaisyService` | `DaisyService.kt` | Foreground service, TTS, overlay orb, command dispatch |
| `DaisyAccessibilityService` | `DaisyAccessibilityService.kt` | All on-device UI actions (tap, type, scroll, open app, scan) |
| `DaisyOrbView` | `DaisyOrbView.kt` | Animated flower orb — pure visual feedback |
| `OrbStyles` | `OrbStyles.kt` | Color/caption mapping from `DaisyState` to drawable |
| `DaisyState` | `DaisyState.kt` | `STANDBY` / `PROCESSING` enum — only two states |
| `ParsedCommand` / `AgentAction` | `Command.kt` | Data types for commands flowing into `DaisyService.execute()` |

---

## Permissions

| Permission | How granted | Why needed |
|------------|------------|------------|
| `RECORD_AUDIO` | Runtime dialog (MainActivity asks on first launch) | Required even though Android is not doing STT — reserved for future direct input |
| `SYSTEM_ALERT_WINDOW` | Settings → Apps → Special app access → Appear on top | Overlay orb must float above other apps |
| Accessibility service | Settings → Accessibility → Downloaded apps → EchoMind | `DaisyAccessibilityService` cannot run without this toggle |
| `POST_NOTIFICATIONS` | Runtime dialog (Android 13+) | Foreground service notification |

Overlay and accessibility cannot be granted programmatically — the user must toggle them in Settings. `MainActivity.refreshSetupStatus()` checks all three and shows the correct prompt.

---

## Adding a new device action

1. Add the low-level implementation to `DaisyAccessibilityService.kt` as a private method, expose it via a companion object `fun`.
2. Add the `AgentAction` enum value to `Command.kt`.
3. Add the dispatch branch to `DaisyService.execute()`.
4. Mirror the tool schema in `agent/tools.py` so the Python agent can invoke it.

Example: adding a "take screenshot" action → `DaisyAccessibilityService.takeScreenshot()`, `AgentAction.SCREENSHOT`, dispatch in `DaisyService`, tool entry in `agent/tools.py`.

---

## Build

```bash
# From android/
export ANDROID_HOME=/opt/homebrew/Caskroom/android-commandlinetools/14742923
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug          # compile check
./gradlew installDebug           # push to connected emulator/device
```

Requires `adb devices` to show a connected device before `installDebug`.

After install:
1. Open EchoMind on the device.
2. Grant microphone and notification permissions (runtime dialog).
3. Tap the overlay prompt → grant "Appear on top" in Settings.
4. Tap the accessibility prompt → enable EchoMind in Accessibility Settings.

---

## Known TODOs

- `DaisyService.execute()` is currently called by test code only — wire the HTTP/IPC receiver so the Java side (or Python via REST) can invoke it.
- `RECORD_AUDIO` permission is requested but unused — remove once the Python-side STT path is confirmed as the only path.
- `DaisyOrbView` has an `else ->` branch in `onDraw` that handles states that no longer exist — clean up after `DaisyState` is confirmed stable.
