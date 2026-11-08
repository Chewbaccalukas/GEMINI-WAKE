# Dynamic Foreground Notification Status & Background Service Lifecycle Plan

This plan updates `WakeWordVoiceService.promoteToMicrophoneForeground(isActivelyListening: Boolean = false)` to dynamically toggle the foreground notification text between `"Listening for wake word..."` and `"Hands-free standby"` as the microphone hardware is initialized or released, and removes the redundant background service re-trigger inside `MainViewModel.onAppBackgrounded()`.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Key Changes Included in This Plan**:
> 1. **`WakeWordVoiceService.promoteToMicrophoneForeground(isActivelyListening: Boolean = false)`**:
>    - Change visibility and signature to `private fun promoteToMicrophoneForeground(isActivelyListening: Boolean = false)`.
>    - Update `.setContentText(...)` in `buildNotification(isActivelyListening)` to conditionally display `"Listening for wake word..."` when `isActivelyListening == true`, and `"Hands-free standby"` when `isActivelyListening == false`.
>    - Call `promoteToMicrophoneForeground(true)` inside `initializeMicrophoneRecord()` once `AudioRecord` starts recording.
>    - Call `promoteToMicrophoneForeground(false)` inside `releaseServiceMicrophoneRecord()` when `AudioRecord` is stopped and released.
> 2. **`MainViewModel.onAppBackgrounded()` Cleanup**:
>    - Completely remove the `if ((_isHandsFreeActive.value || ...) && !_isSleepMode.value)` block that calls `WakeWordVoiceService.startServiceSafely(..., ACTION_START_VOICE_SERVICE)` from `onAppBackgrounded()`, since a foreground service started in the foreground remains alive automatically without manual background re-triggering.

---

## 1. Overview & Core Concept

- **What It Does**: Synchronizes the persistent foreground notification with the actual hardware state of `WakeWordVoiceService`'s `AudioRecord` instance (`"Listening for wake word..."` vs. `"Hands-free standby"`), and prevents `MainViewModel.onAppBackgrounded()` from re-issuing `startForegroundService` intents from the background.
- **Target Audience / Persona**: Users monitoring the persistent notification on Android 14+ / Samsung One UI while Hands-Free mode transitions between active microphone listening and standby.
- **Key Value**: Accurate real-time notification feedback tied directly to microphone hardware acquisition/release, and cleaner Android 14+ background service compliance.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Service Startup / Standby**: When `WakeWordVoiceService` starts before `AudioRecord` is active (or after `AudioRecord` yields), `promoteToMicrophoneForeground(false)` displays `"Hands-free standby"`.
  2. **Active Microphone Capture**: As soon as `initializeMicrophoneRecord()` initializes and starts `AudioRecord`, `promoteToMicrophoneForeground(true)` updates the notification text to `"Listening for wake word..."`.
  3. **Microphone Release**: When `releaseServiceMicrophoneRecord()` stops and releases `AudioRecord`, `promoteToMicrophoneForeground(false)` immediately updates the notification text back to `"Hands-free standby"`.
  4. **App Backgrounding**: When the app enters `onStop()` -> `onAppBackgrounded()`, the already-running foreground service stays alive naturally without a redundant `startServiceSafely` call from the background.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hardware-Driven Notification Text via `promoteToMicrophoneForeground(isActivelyListening: Boolean = false)`**
  - *Chosen Approach*: Pass `isActivelyListening: Boolean = false` into `promoteToMicrophoneForeground` and `buildNotification`, setting `.setContentText(if (isActivelyListening) "Listening for wake word..." else "Hands-free standby")`, and invoke `promoteToMicrophoneForeground(true)` in `initializeMicrophoneRecord()` and `promoteToMicrophoneForeground(false)` in `releaseServiceMicrophoneRecord()`.
  - *Why*: Tying the notification update directly to `initializeMicrophoneRecord()` and `releaseServiceMicrophoneRecord()` guarantees the user-visible notification always reflects whether the service is actively holding the microphone or waiting in standby.
- **Decision 2: Remove Background Service Re-Trigger in `onAppBackgrounded()`**
  - *Chosen Approach*: Remove the `WakeWordVoiceService.startServiceSafely(..., ACTION_START_VOICE_SERVICE)` block inside `MainViewModel.onAppBackgrounded()`.
  - *Why*: Android 12+/14+ restricts starting foreground services from backgrounded contexts, and a service already promoted to foreground while the app was visible remains alive automatically.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         WakeWordVoiceService.kt                              │
│                                                                              │
│  • private fun promoteToMicrophoneForeground(                                │
│        isActivelyListening: Boolean = false                                  │
│    )                                                                         │
│      └─► buildNotification(isActivelyListening)                              │
│            └─► .setContentText(                                              │
│                    if (isActivelyListening) "Listening for wake word..."     │
│                    else "Hands-free standby"                                 │
│                )                                                             │
│                                                                              │
│  • initializeMicrophoneRecord():                                             │
│      record.startRecording()                                                 │
│      _isMicrophoneInitialized.value = true                                   │
│      promoteToMicrophoneForeground(true)                                     │
│                                                                              │
│  • releaseServiceMicrophoneRecord():                                         │
│      record.stop() / record.release()                                        │
│      _isMicrophoneInitialized.value = false                                  │
│      promoteToMicrophoneForeground(false)                                    │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                    MainViewModel.onAppBackgrounded()                         │
│  • Updates window focus & BatterySaverMonitorWorker / ChromeBridge           │
│  • Does NOT call WakeWordVoiceService.startServiceSafely(...)                │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Interactive Component & State Mapping

1. **`WakeWordVoiceService.promoteToMicrophoneForeground(isActivelyListening: Boolean = false)`**:
   - Change `internal fun promoteToMicrophoneForeground()` to `private fun promoteToMicrophoneForeground(isActivelyListening: Boolean = false)`.
   - Pass `isActivelyListening` to `buildNotification(isActivelyListening: Boolean = false)`.
   - In `buildNotification(isActivelyListening: Boolean = false)`, configure `.setContentText(if (isActivelyListening) "Listening for wake word..." else "Hands-free standby")`.
2. **`WakeWordVoiceService.initializeMicrophoneRecord()` & `releaseServiceMicrophoneRecord()`**:
   - In `initializeMicrophoneRecord()`, call `promoteToMicrophoneForeground(true)` right after `_isMicrophoneInitialized.value = true`.
   - In `releaseServiceMicrophoneRecord()`, call `promoteToMicrophoneForeground(false)` after stopping and releasing `record`.
3. **`MainViewModel.onAppBackgrounded()`**:
   - Remove the `if ((_isHandsFreeActive.value || wakeWordManager.isListening.value || speechRecognitionCoordinator.isListening.value) && !_isSleepMode.value)` block that calls `WakeWordVoiceService.startServiceSafely(...)`.
