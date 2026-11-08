# Microphone Hardware Collision & Processing Mutex Fix Plan (v10.0)

This plan resolves microphone hardware collisions and stuck processing mutexes identified in the latest diagnostic logs by: (1) updating `AudioManager.OnAudioFocusChangeListener` in the `SpeechRecognizer` coordinator to set a transient loss flag and call `SpeechRecognizer.cancel()` on `AUDIOFOCUS_LOSS_TRANSIENT (-2)` and only restart after a `500ms` debounced delay on `AUDIOFOCUS_GAIN (1)`, (2) enforcing a synchronous mutex handoff that stops and releases `WakeWordVoiceService`'s `AudioRecord` instance before the UI dictation button calls `SpeechRecognizer.startListening()`, and (3) calling `SpeechRecognizer.destroy()` and releasing the Processing Mutex (`isProcessing = false`) when the feature is turned off or the app is backgrounded.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Three Core Fixes Targeted by This Plan**:
> 1. **`AudioManager.OnAudioFocusChangeListener` Transient Loss & 500ms Debounced Gain (`SpeechRecognitionCoordinator` & `WakeWordManager`)**:
>    - On `AUDIOFOCUS_LOSS_TRANSIENT (-2)`: set `isTransientAudioFocusLoss = true` (and `isTransientFocusLoss = true`), cancel any pending restart timers, and call `SpeechRecognizer.cancel()` directly without calling `stopListening()` or restarting immediately.
>    - On `AUDIOFOCUS_GAIN (1)`: check the transient loss flag, clear it, and schedule a `500ms` debounced restart (`AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS = 500L`).
> 2. **Synchronous Mutex Handoff Between `WakeWordVoiceService` and UI Dictation (`WakeWordVoiceService` & `MainViewModel`)**:
>    - Synchronize `WakeWordVoiceService`'s `serviceAudioRecord` with an explicit mutex lock and expose `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously()`.
>    - When the user taps the UI mic/dictation button (`toggleInternalDictation` / `toggleInChatDictation`) or `onPrepareHardwareBeforeStart` runs, synchronously stop and release `WakeWordVoiceService`'s `AudioRecord` (alongside `AudioRecorder` and `WakeWordManager`) before invoking `SpeechRecognizer.startListening()`.
> 3. **Full `SpeechRecognizer.destroy()` & Processing Mutex Release on Turn-Off or Backgrounding (`SpeechRecognitionCoordinator` & `MainViewModel`)**:
>    - Update `SpeechRecognitionCoordinator.destroy(markIntentionallyPaused: Boolean = true)` to invoke `speechRecognizer?.cancel()` and `speechRecognizer?.destroy()`, null out `speechRecognizer`, and clear `isProcessing = false` (releasing the Processing Mutex).
>    - Call `destroy()` when Hands-Free / continuous listening is turned off (`toggleHandsFreeVoice(false)` / `stopContinuousWakeWordListening`) or when the app is backgrounded (`onAppBackgrounded()`), rather than only calling `stopListening()`.

---

## 1. Overview & Core Concept

- **What It Does**: Eliminates concurrent hardware microphone access between `WakeWordVoiceService`'s `AudioRecord` and `SpeechRecognizer`, prevents immediate restart storms during `AUDIOFOCUS_LOSS_TRANSIENT (-2)`, and guarantees the `SpeechRecognizer` binder and `isProcessing` mutex are completely torn down when turned off or backgrounded.
- **Target Audience / Persona**: Users switching between passive wake-word listening, in-chat UI microphone dictation, transient audio focus interruptions, and background/foreground app transitions.
- **Key Value**: Zero `AudioFlinger` / `ERROR_CLIENT(5)` / `ERROR_AUDIO(3)` microphone collisions when tapping the dictation mic button, clean 500ms recovery after transient audio focus loss, and no stuck `isProcessing=true` locks after backgrounding or toggling off.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Transient Audio Focus Interruption (`-2` -> `1`)**: When a system sound or transient audio stream takes focus (`AUDIOFOCUS_LOSS_TRANSIENT`), `SpeechRecognizer.cancel()` immediately aborts recognition and sets `isTransientAudioFocusLoss = true` with zero immediate restarts. When `AUDIOFOCUS_GAIN` (`1`) arrives, the coordinator verifies the transient flag and waits a `500ms` debounce before restarting listening.
  2. **UI Dictation Mic Button Tap**: Tapping the chat mic button synchronously stops and releases `WakeWordVoiceService`'s `AudioRecord` hardware on the spot (updating its foreground notification to `"Hands-free standby"`) before `SpeechRecognizer.startListening()` is called on the main thread.
  3. **Feature Turn-Off & App Backgrounding**: Turning off Hands-Free voice or backgrounding the app calls `SpeechRecognizer.destroy()` and resets `isProcessing = false`, completely releasing the `SpeechRecognizer` instance and Processing Mutex.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: `SpeechRecognizer.cancel()` Instead of `stopListening()` on `AUDIOFOCUS_LOSS_TRANSIENT (-2)`**
  - *Chosen Approach*: In `SpeechRecognitionCoordinator.audioFocusChangeListener` / `handleAudioFocusChange()`, handle `AudioManager.AUDIOFOCUS_LOSS_TRANSIENT` (`-2`) by setting `isTransientAudioFocusLoss = true`, cancelling pending restart jobs, and invoking `speechRecognizer?.cancel()` directly. On `AudioManager.AUDIOFOCUS_GAIN` (`1`), check `isTransientAudioFocusLoss` and debounce restart by `500ms`.
  - *Why*: Calling `stopListening()` tells Android's recognizer service to finalize audio and fire `onResults()` or `onError()`, which can trigger immediate restart paths while audio focus is still lost. Calling `cancel()` with a transient loss flag halts capture immediately and waits for `AUDIOFOCUS_GAIN`.
- **Decision 2: Synchronous `WakeWordVoiceService` `AudioRecord` Release Mutex**
  - *Chosen Approach*: Track the active `WakeWordVoiceService` instance and guard `serviceAudioRecord` with `serviceAudioRecordMutex`. Call `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously()` inside `pauseForInternalDictationInMemory()`, `releaseAllNativeAudioRecordTracksForSpeechRecognizer()`, and `toggleInternalDictation()`.
  - *Why*: Previously, `pauseForInternalDictationInMemory()` only flipped boolean flags in memory while the service's background coroutine took up to 60–120ms to release `serviceAudioRecord`, racing against `SpeechRecognizer.startListening()`.
- **Decision 3: `SpeechRecognizer.destroy()` + Processing Mutex Reset on Turn-Off and Background**
  - *Chosen Approach*: Call `speechRecognitionCoordinator.destroy(markIntentionallyPaused = ...)` in `toggleHandsFreeVoice(false)`, `stopContinuousWakeWordListening(markIntentionallyPaused = true)`, and `onAppBackgrounded()`, and ensure `destroy()` always clears `isProcessing = false`.
  - *Why*: Repeatedly calling `stopListening()` leaves the `SpeechRecognizer` binder connected and can leave `isProcessing = true` locked if a turn was interrupted on backgrounding or toggle-off.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│             1. AudioFocus Listener (SpeechRecognitionCoordinator)            │
│                                                                              │
│  • AUDIOFOCUS_LOSS_TRANSIENT (-2):                                           │
│      isTransientAudioFocusLoss = true                                        │
│      restartJob?.cancel()                                                    │
│      speechRecognizer?.cancel()  // Do NOT restart immediately               │
│                                                                              │
│  • AUDIOFOCUS_GAIN (1):                                                      │
│      if (isTransientAudioFocusLoss) {                                        │
│          isTransientAudioFocusLoss = false                                   │
│          scheduleAudioFocusGainRearm(delayMs = 500L)                         │
│      }                                                                       │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│       2. Exclusive Mutex Handoff: WakeWordVoiceService -> UI Dictation       │
│                                                                              │
│  User taps Mic Button (toggleInternalDictation / toggleInChatDictation)      │
│      ├─► WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously()│
│      │     └─► synchronized(serviceAudioRecordMutex) {                       │
│      │             serviceAudioRecord?.stop()                                │
│      │             serviceAudioRecord?.release()                             │
│      │             serviceAudioRecord = null                                 │
│      │         }                                                             │
│      ├─► audioRecorder.stopAndReleaseHardware()                              │
│      ├─► wakeWordManager.stopAndReleaseAudioRecordSynchronously()            │
│      └─► speechRecognitionCoordinator.startListening(userInitiated = true)   │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│          3. SpeechRecognizer.destroy() & Processing Mutex Release            │
│                                                                              │
│  • toggleHandsFreeVoice(false) / stopContinuousWakeWordListening():          │
│      speechRecognitionCoordinator.destroy(markIntentionallyPaused = true)    │
│        └─► isProcessing = false; speechRecognizer?.destroy(); null           │
│                                                                              │
│  • onAppBackgrounded():                                                      │
│      speechRecognitionCoordinator.destroy(markIntentionallyPaused = false)   │
│        └─► isProcessing = false; speechRecognizer?.destroy(); null           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Interactive Component & State Mapping

1. **`SpeechRecognitionCoordinator.kt`**:
   - Add `@Volatile var isTransientAudioFocusLoss: Boolean = false` and `val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange -> handleAudioFocusChange(focusChange) }`.
   - In `handleAudioFocusChange(focusChange: Int)`:
     - On `AudioManager.AUDIOFOCUS_LOSS_TRANSIENT` (`-2`) and `AudioManager.AUDIOFOCUS_LOSS` (`-1`): set `isTransientAudioFocusLoss = true`, cancel `restartJob` and `trailingSilenceDebounceJob`, set `_isListening.value = false`, restore `STREAM_SYSTEM`, and invoke `speechRecognizer?.cancel()` on the main thread without restarting.
     - On `AudioManager.AUDIOFOCUS_GAIN` (`1`, `AUDIOFOCUS_GAIN_TRANSIENT`, `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`): check `isTransientAudioFocusLoss` (or transient focus state), clear `isTransientAudioFocusLoss = false`, and call `scheduleAudioFocusGainRearm(delayMs = AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS)` (`500ms`).
     - In `onError()`, if `isTransientAudioFocusLoss` is `true`, return immediately without scheduling any restart.
   - Update `destroy(markIntentionallyPaused: Boolean = true)` to reset `isProcessing = false`, `setInChatDictationMode(false)`, cancel all jobs, call `speechRecognizer?.cancel()` and `speechRecognizer?.destroy()`, set `speechRecognizer = null`, and refresh `mutexSnapshotFlow`.
2. **`WakeWordVoiceService.kt`**:
   - Guard `serviceAudioRecord` operations with `serviceAudioRecordMutex` and track `activeInstance: WakeWordVoiceService?`.
   - Add `stopAndReleaseServiceAudioRecordSynchronously()` in `companion object` (and invoke it inside `pauseForInternalDictationInMemory()`) so `serviceAudioRecord` is stopped and released synchronously on the caller's thread before returning.
3. **`MainViewModel.kt`**:
   - In `releaseAllNativeAudioRecordTracksForSpeechRecognizer()` and `toggleInternalDictation()`, call `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously()` before `speechRecognitionCoordinator.startListening(userInitiated = true)`.
   - In `stopContinuousWakeWordListening(markIntentionallyPaused: Boolean)`, `toggleHandsFreeVoice(false)`, and `onAppBackgrounded()`, call `destroySpeechRecognizerInternal(markIntentionallyPaused = ...)` (`speechRecognitionCoordinator.destroy(...)`) to destroy the `SpeechRecognizer` and release the Processing Mutex (`isProcessing = false`).
