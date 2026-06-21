# Android Environment Setup (macOS, no Android Studio)

Sets up the Android SDK, emulator, and `adb` using only command-line tools.
No Android Studio required.

**Requirements:** macOS, Homebrew, ~3 GB disk space, ~15 minutes.

---

## Step 1: Install command-line tools and adb

```bash
brew install --cask android-commandlinetools
brew install android-platform-tools
```

Find the exact install path:

```bash
brew info --cask android-commandlinetools
# look for "Installed to: /path/to/..." in the output
```

The SDK root is typically one level above the `cmdline-tools` folder, e.g.:
`/usr/local/share/android-commandlinetools` → SDK root is that same path.

---

## Step 2: Set ANDROID_HOME

Add to `~/.zshrc` (replace the path with what you found in Step 1):

```bash
export ANDROID_HOME="/usr/local/share/android-commandlinetools"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Reload:

```bash
source ~/.zshrc
```

Verify:

```bash
echo $ANDROID_HOME
sdkmanager --version
adb version
```

---

## Step 3: Install SDK platform, build tools, and emulator packages

The `android/` module targets API 35. Install:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
sdkmanager "emulator"
sdkmanager "system-images;android-35;google_apis;x86_64"
```

Accept the license prompts (`y`).

---

## Step 4: Create local.properties for Gradle

Gradle needs to know where the SDK is. Android Studio writes this file automatically — do it manually:

```bash
# From the repo root
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

---

## Step 5: Verify the build

```bash
cd android
./gradlew assembleDebug
```

First run downloads Gradle 8.11.1 and all dependencies — takes 3–5 minutes.
Expected: `BUILD SUCCESSFUL`.

---

## Step 6: Create and start the emulator

```bash
avdmanager create avd \
  --name "calhacks_avd" \
  --package "system-images;android-35;google_apis;x86_64" \
  --device "pixel_6"

$ANDROID_HOME/emulator/emulator -avd calhacks_avd -no-snapshot-load &
```

Wait ~30 seconds for boot, then verify:

```bash
adb devices
# expected:
# List of devices attached
# emulator-5554   device
```

`device` (not `offline` or `unauthorized`) means it is ready.

---

## Step 7: Install the app on the emulator

```bash
cd android
./gradlew installDebug
```

---

## Step 8: Install uiautomator2 agent on the emulator (one-time)

```bash
source .venv/bin/activate
python -m uiautomator2 init
```

Expected: `success`. If it fails, confirm `adb devices` shows `device` first.

---

## Step 9: Enable the accessibility service on the emulator

The Python agent drives the device via `DaisyAccessibilityService`. It must be
enabled manually once after install:

1. On the emulator: Settings → Accessibility → Installed apps → Daisy → turn ON
2. Confirm with `adb shell settings get secure enabled_accessibility_services`
   — expected output includes `com.calhacks.daisy/.DaisyAccessibilityService`

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `sdkmanager: command not found` | `cmdline-tools/latest/bin` not on PATH — re-check Step 2 |
| `adb: command not found` | `brew install android-platform-tools` not done, or `platform-tools` not on PATH |
| `adb devices` shows `unauthorized` | Tap "Allow USB debugging" on the emulator screen |
| `adb devices` shows `offline` | `adb kill-server && adb start-server`, restart emulator |
| `./gradlew assembleDebug` fails: SDK not found | Check `android/local.properties` — `sdk.dir` must point to `$ANDROID_HOME` |
| `./gradlew assembleDebug` fails: license not accepted | `sdkmanager --licenses` and accept all |
| Emulator won't start: no HAXM | Run `sdkmanager "extras;intel;Hardware_Accelerated_Execution_Manager"` and install HAXM, or use `arm64` system image if on Apple Silicon |
