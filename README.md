# QA SIM + GPS Test Harness (KernelSU + Zygisk + LSPosed)

**Purpose:** On a SIM-less device or emulator, present a fake "ready" SIM and a
deterministic GPS fix to *your own test app(s)* so QA flows that require a SIM or
a location can run. Scoped to whitelisted packages only.

## Architecture
- KernelSU = root + module host
- Zygisk (KSU built-in, or ZygiskNext) = process injection
- LSPosed (Zygisk module) = where the SIM/GPS hooks live  -> this project
- (optional) ksu-prop-module/ = legacy gsm.* props via resetprop

A pure KSU module cannot intercept TelephonyManager/LocationManager calls inside
an app — that requires framework hooking, hence LSPosed.

## Build
1. Open in Android Studio (or use Gradle). Xposed API is compileOnly — never bundled.
2. `./gradlew :app:assembleDebug` -> app/build/outputs/apk/debug/app-debug.apk
3. Install the APK, then enable it in **LSPosed Manager** and tick your test
   package(s) in its scope. Force-stop / relaunch the target app.

## Configure
- Edit `ScopeConfig.SCOPE` AND `res/values/arrays.xml` to list only your test packages.
- Edit the SIM identity constants / default coordinates in `SimGpsHook.java`.
- Move the GPS fix at runtime without rebuilding:
  `adb shell "echo '37.7749,-122.4194,10,5' > /data/local/tmp/qa_location.txt"`

## Install the optional prop module (legacy SIM checks)
```
cd ksu-prop-module && zip -r ../qa_sim_props.zip . 
# Flash qa_sim_props.zip in the KernelSU app -> Modules -> Install from storage, reboot
```

## Notes / scope discipline
- Keep it scoped to debug/QA package IDs; do not run system-wide.
- Test values use reserved test ranges (PLMN 001/01, +1 555 0100 numbers).
- The hook reports isFromMockProvider()=false so your fix reads as a normal fix
  for functional testing. If you are specifically testing your app's own
  mock-detection, flip those hooks off so you can verify both paths.
