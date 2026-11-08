# Synchronous Wake-Word Navigation Handoff & TTS Hardware Mutex

Eliminates microphone hardware collisions and `ERROR_INSUFFICIENT_PERMISSIONS(9)` crashes when detecting a wake word ("Hey Gemini"), transitioning to the Chat screen, and playing automatic Text-to-Speech (TTS) greetings or replies. Enforces a strict synchronous hardware mutex handoff (`"wake_word_detected"` and `"tts_speaking"`) before any navigation state update or TTS stream opens, prevents Chat screen initialization/resume hooks from prematurely grabbing the microphone during handoff, and applies a 500ms debounced delay after TTS completion before re-arming background wake-word listening.

## User Review & Critical Decisions

> [!IMPORTANT]
> This revised plan integrates the wake-word-to-Chat screen transition mutex alongside the TTS lifecycle handoff and your confirmed preferences:

- **Confirmed Decision 1 — Synchronous Release on Wake Word Detection (`"wake_word_detected"`)**: The exact moment a wake word is detected (across the foreground service, wake-word manager, and recognizer callbacks), the app immediately invokes `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")` before emitting any navigation event, notification intent, or Chat screen state update.
- **Confirmed Decision 2 — Guard Chat Screen Init / Resume Against Premature Mic Capture**: Entering the Chat screen (`Screen.Chat` composition, `onEnterChatScreen`, and activity resume/focus observers) is gated by an active wake-handoff lock so root-level and screen-level lifecycle effects cannot restart the background service or speech recognizer while the wake-word handoff or greeting TTS is initializing.
- **Confirmed Decision 3 — Synchronous Pre-TTS Microphone Release (`"tts_speaking"`)**: Every TTS speech entry point synchronously calls `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")` before invoking `tts.speak()`.
- **Confirmed Decision 4 — 500ms Debounced Re-Arming Across `onDone`, `onError`, and `onStop`**: An `UtteranceProgressListener` tracks active TTS chunks and applies a 500ms debounced delay across `onDone`, `onError`, and `onStop` before re-initializing `WakeWordVoiceService` and resuming recognition.
- **Confirmed Decision 5 — Ignore False-Positive `ERROR_INSUFFICIENT_PERMISSIONS(9)` When OS Permission Is Granted**: If Android's `SpeechRecognizer` emits a race-condition error 9 during wake-word handoff or TTS startup while OS `RECORD_AUDIO` permission is granted, it is handled as transient audio contention rather than revoking permission state.

---

## 1. Overview & Core Concept

- **What It Does**: Establishes a deterministic, collision-free audio handoff pipeline from the moment the user says `"Hey Gemini"` through Chat screen navigation, automatic TTS greeting playback, and follow-up voice capture.
- **Target Audience / Persona**: Hands-free voice users who trigger chats by voice and expect seamless transitions into the Chat screen without microphone lockups or crashes.
- **Key Value**: Guarantees that `WakeWordVoiceService` never holds an active `AudioRecord` lock when the Chat screen opens or when TTS and `SpeechRecognizer` initialize.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Wake Word Detection & Synchronous Teardown**: The instant `"Hey Gemini"` (or a custom wake phrase) is detected, the background service synchronously stops and releases its `AudioRecord` (`"wake_word_detected"`) and updates its notification to `"Hands-free standby"`.
  2. **Ordered Navigation to Chat Screen**: Only after the synchronous hardware release finishes does the app update the active chat selection, trigger banner, or navigation route to transition into the Chat screen.
  3. **Protected Chat Screen Initialization**: As the Chat screen loads and runs its entry/resume lifecycle hooks, the active handoff lock suppresses any automatic background mic re-initialization so the screen's TTS greeting or follow-up recognizer can claim audio focus cleanly.
  4. **Pre-TTS Yield & 500ms Post-TTS Re-Arming**: Before speaking a greeting or response, TTS synchronously verifies the service mic is released (`"tts_speaking"`). Once speech finishes (`onDone`, `onError`, or `onStop`), a 500ms debounce timer waits for the audio output stream to close before re-initializing `WakeWordVoiceService` (`"Listening for wake word..."`).
- **Visual Identity & Theme**:
  - Retains the existing Material 3 adaptive styling, wake-ready visual cues, and foreground notification status text.
- **Interactive Feedback & Motion**:
  - Smooth, crash-free transition from the Home/Radar view into the target Chat thread with accurate real-time voice status telemetry.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Release Hardware Before Emitting Wake-Word Navigation State**
  - *Chosen Approach*: Execute `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")` at the point of wake-word match before emitting trigger flows, posting wake notifications, calling `selectChat(...)`, or updating navigation state.
  - *Why*: Reactive UI collectors in Compose respond immediately on the main thread when chat selection or trigger banner state changes; releasing the hardware first guarantees the native `AudioRecord` is already closed before any Chat screen composable mounts.
  - *Alternatives Considered*: Releasing the microphone asynchronously inside a coroutine after `selectChat(...)`, which loses the race against Compose screen transitions and causes `ERROR_INSUFFICIENT_PERMISSIONS(9)`.
- **Decision 2: Gate Root & Chat Screen Lifecycle Listeners During Handoff**
  - *Chosen Approach*: Maintain an explicit wake-handoff state flag (`isWakeHandoffInProgress`) alongside the coordinator's processing and TTS speaking locks, and check it inside the root `LaunchedEffect(..., currentScreen)` observer, intent handler, and Chat screen entry hooks.
  - *Why*: Changing `currentScreen` to `Screen.Chat` previously re-triggered the root `LaunchedEffect` in the activity, which immediately called `WakeWordVoiceService.startServiceSafely` and `startContinuousWakeWordListening` while the Chat screen was mid-handoff.
- **Decision 3: Synchronous Pre-Speak Yield & 500ms Utterance Completion Debounce**
  - *Chosen Approach*: Synchronously invoke `stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")` before `tts.speak()`, track all utterance chunks via `UtteranceProgressListener`, and debounce re-arming by 500ms across `onDone`, `onError`, and `onStop`.
  - *Why*: Ensures the background microphone never spins up until the TTS audio stream is completely closed.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 1. Wake Word Detection ("Hey Gemini")                    │
│  • Synchronously calls:                                                  │
│    WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously(   │
│        "wake_word_detected"                                              │
│    )                                                                     │
│  • Locks isWakeHandoffInProgress = true & Coordinator isProcessing = true│
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ (Only AFTER synchronous release completes)
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│              2. Chat Selection & Navigation State Update                 │
│  • Selects target chat & emits navigation state to open Chat screen      │
│  • Root & Chat screen onResume/init hooks see handoff lock and DO NOT    │
│    aggressively restart WakeWordVoiceService or SpeechRecognizer         │
└─────────────────┬───────────────────────────────────────┬────────────────┘
                  │ (If Chat has Greeting / Auto-TTS)     │ (If Follow-Up Listen)
                  ▼                                       ▼
┌─────────────────────────────────────────┐ ┌──────────────────────────────┐
│       3a. TextToSpeechManager           │ │ 3b. Debounced Follow-Up Mic  │
│  • Calls stopAndReleaseServiceAudio...  │ │  • Waits for handoff settle  │
│    ("tts_speaking") before tts.speak()  │ │    before starting recognizer│
│  • UtteranceProgressListener tracks     │ └──────────────┬───────────────┘
│    onDone / onError / onStop            │                │
└─────────────────┬───────────────────────┘                │
                  │ 4. 500ms Debounced Delay               │
                  ▼                                        ▼
┌──────────────────────────────────────────────────────────────────────────┐
│             5. Safe Re-Arming of WakeWordVoiceService                    │
│  • Clears handoff & processing locks after 500ms acoustic settle         │
│  • Re-initializes WakeWordVoiceService & resumes hands-free listening    │
└──────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State

- **Wake Handoff Lock (`isWakeHandoffInProgress`)**: Volatile/StateFlow guard set to `true` the instant a wake word is detected (before navigation) and cleared only after the Chat screen handoff (and any greeting TTS playback or follow-up recognizer startup) has settled.
- **Hardware AudioRecord Mutex (`serviceAudioRecordMutex`)**: Synchronized lock inside `WakeWordVoiceService` ensuring `stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")` and `stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")` deterministically stop and release `AudioRecord` before returning.
- **500ms Post-TTS Debounce (`ACOUSTIC_SETTLING_DELAY_MS = 500L`)**: Coroutine job triggered when `activeUtterances` empties in `onDone`, `onError`, or `onStop`.

### Interactive Component & State Mapping

1. **Wake Word Match Callback (Service, Manager & Recognizer)**:
   - Immediately sets `isWakeHandoffInProgress = true` and invokes `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")`.
   - Synchronously stops any active `AudioRecorder`, `WakeWordManager` PCM capture, and `SpeechRecognitionCoordinator` session.
   - Only after synchronous teardown returns does it invoke `selectChat(resolvedChatId)`, update `_triggerBanner`, emit `_triggerEvents`, or trigger `Screen.Chat` navigation.
2. **Chat Screen Entry & Root Lifecycle Guards**:
   - Root `LaunchedEffect(..., currentScreen)` and `onAppForegrounded` / `onWindowFocusChanged` check `!isWakeHandoffInProgress`, `!speechRecognitionCoordinator.isProcessing`, and `!textToSpeechManager.isSpeaking` before starting `WakeWordVoiceService` or `startContinuousWakeWordListening`.
   - `onEnterChatScreen` clears stale pending wake queries without triggering premature microphone capture.
3. **TTS Greeting & Response Playback**:
   - Calls `WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")` before `tts.speak()`.
   - Attaches `UtteranceProgressListener` and debounces re-initialization by 500ms on `onDone`, `onError`, and `onStop`.
4. **False-Positive Error 9 Filtering**:
   - If `SpeechRecognizer` reports `ERROR_INSUFFICIENT_PERMISSIONS(9)` while OS `RECORD_AUDIO` permission is granted, suppresses permission revocation and defers recovery to the active handoff/TTS settle timer.
