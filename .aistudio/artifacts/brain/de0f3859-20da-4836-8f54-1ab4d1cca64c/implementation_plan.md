# Implementation Plan — Strip Remaining Widget & Dual/Debug Launcher Entries (v13.0)

## Goal
Ensure the `v13.0` codebase retains all `v10.2`/`v10.3` wake-word audio handoff and foreground microphone service fixes while guaranteeing that both the source and merged debug `AndroidManifest.xml` contain **zero widgets**, **zero `<activity-alias>` entries**, **strictly one `MAIN`/`LAUNCHER` activity (`.MainActivity`)**, and **no exported debug activities** (`androidx.activity.ComponentActivity` and `androidx.compose.ui.tooling.PreviewActivity`).

## Planned Changes

### 1. Strip Exported Debug Activities & Enforce Single Launcher (`app/src/main/AndroidManifest.xml`)
- Preserve all `v10.3` permissions, services, and `MainActivity` configuration (`FOREGROUND_SERVICE_MICROPHONE`, `WakeWordVoiceService` with `android:foregroundServiceType="microphone"`, `MainActivity` with `android:launchMode="singleTop"`, and `<category android:name="android.intent.category.LAUNCHER" tools:node="remove" />` on the secondary `VIEW` intent-filter).
- Add explicit manifest merger removal directives inside `<application>` so Compose debug artifacts cannot inject exported secondary activities into the debug APK:
  ```xml
  <activity
      android:name="androidx.activity.ComponentActivity"
      tools:node="remove" />
  <activity
      android:name="androidx.compose.ui.tooling.PreviewActivity"
      tools:node="remove" />
  ```
- Confirm no `AppWidgetProvider` (`<receiver>` with `android.appwidget.action.APPWIDGET_UPDATE`) or `<activity-alias>` exists in `AndroidManifest.xml` or `res/xml/`.

### 2. Expand Manifest & Single-Launcher Verification (`app/src/test/java/com/example/ExampleRobolectricTest.kt`)
- Update `ExampleRobolectricTest` to assert that:
  1. `PackageManager.queryIntentActivities(MAIN + LAUNCHER)` resolves to strictly 1 activity (`com.example.MainActivity`).
  2. Both source and merged debug `AndroidManifest.xml` contain strictly 1 active `android.intent.category.LAUNCHER`, zero `<activity-alias>` tags, and zero `android.appwidget.action.APPWIDGET_UPDATE` widget receivers.
  3. The source `AndroidManifest.xml` includes `tools:node="remove"` for `androidx.activity.ComponentActivity` and `androidx.compose.ui.tooling.PreviewActivity`, and the merged debug `AndroidManifest.xml` does not contain either debug activity.

### 3. Build & Multi-Pass Verification
- Run `compile_applet` to regenerate the merged debug manifest and verify clean compilation.
- Run Robolectric unit tests (`ExampleRobolectricTest`, `SpeechRecognitionCoordinatorTest`, `HandsFreeVoiceCommandTest`) to confirm all manifest assertions and `v10.2`/`v10.3` wake-word handoff behaviors pass with zero errors.
- Output a complete summary of errors checked and fixes applied in chat for review.
