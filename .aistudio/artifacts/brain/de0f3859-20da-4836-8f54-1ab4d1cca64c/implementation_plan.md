# Prevent App Backgrounding & Inadvertent Hands-Free Disabling on Voice Action Dispatch

Hands-Free Radar mode will maintain a persistent user-controlled state across window focus transitions and background lifecycle events, execute all in-app navigation and category filtering strictly through internal Jetpack Compose state via a centralized voice command dispatcher, and automatically re-arm continuous speech recognition after every voice command or transient external intent.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Architectural Decisions (Aligned with Your Feedback)**
> 1. **Dedicated Command Dispatcher**: A centralized `VoiceCommandDispatcher` component will parse and route all wake-word and voice action phrases, separating internal state mutations from external intent execution.
> 2. **Continuous Background Radar Persistence**: When `handsFreeMode` is active, both `SpeechRecognizer` and the foreground wake-word service remain active across `onWindowFocusChanged(false)`, `onPause()`, and `onStop()` without emitting `RADAR_TOGGLE_OFF` or setting `isIntentionallyPaused = true`. Recognition only pauses transiently (`isProcessingCommand = true`, `isIntentionallyPaused = false`) when an external `Intent` is explicitly launched.
> 3. **Comprehensive Internal Command Routing**: Voice navigation covers all screens (`Home`, `History`, `Settings`, `Studio`, `Diagnostics`), category filter switching (`All`, `Work`, `Personal`, `Projects`, `Assistant`, `Code`, `Custom Personas`, plus custom categories), and in-chat actions (clear history, pin/unpin, read last reply, theme palette changes) strictly via internal Compose state, while wrapping external URL/share intents in the transient `isProcessingCommand` lifecycle.

---

## 1. Overview & Core Concept

Currently, four subtle lifecycle and routing interactions cause Hands-Free Radar to unexpectedly stop or push the app to the background during voice command dispatch:
1. **Window Focus Loss Aborts Listening**: When a system dialog, toast/overlay, or lifecycle transition triggers `onWindowFocusChanged(false)`, the speech coordinator cancels its restart job and aborts `startListening()` because `hasActiveWindowFocus()` returns `false`.
2. **Permanent Pause Lock on Command Dispatch**: Calling `pauseForCommandDispatch()` sets `isIntentionallyPaused = true` and invokes `stopListening(markIntentionallyPaused = true)`, locking out automatic re-arming until the user manually taps the Radar pill.
3. **Wake-Word Hijacking After Non-AI Voice Actions**: When a wake-word phrase contains an in-app voice command (e.g., *"Hey Gemini, open history"*), the command executes, but `wakeWordManager.onKeywordHit()` still fires immediately afterward. This sets `isUserVoiceTurn = true` and emits `triggerBanner`, which triggers a `LaunchedEffect` in the root activity that forces navigation back to `Screen.Chat` and leaves recognition permanently blocked because `sendMessage()`'s completion block never runs for non-AI commands.
4. **Uncoordinated External Intents**: Launching external browser links or system share sheets via `FLAG_ACTIVITY_NEW_TASK` causes window defocus without transitioning the speech coordinator into a transient command-processing state.

By introducing a clean separation between **User Radar Intent** (`isHandsFreeActive` / `isIntentionallyPaused`), **Transient Command Processing** (`isProcessingCommand`), and **Internal Compose Navigation**, the voice assistant will respond smoothly in-place and immediately resume listening after every command.

---

## 2. User Experience & Visual Design

- **Uninterrupted Radar Pill State**: The Hands-Free Radar status indicator and waveform visualizer remain active (`ON`) across screen transitions, category switches, multi-window focus shifts, and backgrounding. `RADAR_TOGGLE_OFF` haptics and audio cues only fire when the user explicitly taps the Radar toggle pill or issues an explicit sleep command.
- **Zero-Flicker In-App Navigation**: Speaking commands such as *"Hey Gemini, open history"*, *"open settings"*, *"open wake word studio"*, *"open diagnostics"*, or *"go home"* transitions the Compose screen stack directly without starting any Android `Activity` or flashing the system task switcher.
- **Voice-Driven Category Filtering**: Speaking *"filter by work"*, *"open personal category"*, *"show code category"*, or *"show all categories"* updates the active category filter chip on the Home and Chat screens in real time.
- **Immediate Re-Arm Feedback**: As soon as an in-app command finishes mutating state, a diagnostic log entry confirms re-arming and the microphone waveform returns to active listening within ~250ms.

---

## 3. Key Product Decisions & Trade-Offs

- **Separating `isProcessingCommand` from `isIntentionallyPaused`**:
  - *Decision*: Reserve `isIntentionallyPaused` exclusively for explicit user-initiated pauses (tapping the Radar pill off or manual mic mute). Introduce `isProcessingCommand` (synced with `isProcessing`) for transient command execution and external Intent handoffs.
  - *Trade-Off*: Requires explicit completion hooks (`completeCommandProcessingAndRearm()`) after command execution, which we guarantee via a `try / finally` wrapper inside the command dispatch pipeline.
- **Short-Circuiting `onKeywordHit` When an Action Command Matches**:
  - *Decision*: When a wake-word utterance resolves to an in-app navigation, category filter, or chat action command via `VoiceCommandDispatcher`, execute the command and immediately re-arm without invoking `wakeWordManager.onKeywordHit()` or emitting `triggerBanner`.
  - *Trade-Off*: Prevents the root `LaunchedEffect(wakeRetriggerEventId, triggerBanner)` from overriding the user's requested screen destination (such as `Screen.History` or `Screen.Settings`) with `Screen.Chat`.

---

## 4. Technical Architecture & Data Strategy

### Architecture & State Flow Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        SpeechRecognitionCoordinator                          │
│  ┌─────────────────────────┐  ┌──────────────────────────┐  ┌─────────────┐  │
│  │ isContinuousModeEnabled │  │ isProcessingCommand:T/F  │  │isIntentPause│  │
│  │   (Hands-Free Radar)    │  │  (Transient Dispatch)    │  │ (User Pill) │  │
│  └────────────┬────────────┘  └────────────┬─────────────┘  └──────┬──────┘  │
└───────────────┼────────────────────────────┼───────────────────────┼─────────┘
                │                            │                       │
                ▼                            ▼                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     MainViewModel Voice Dispatch Pipeline                    │
│  1. Receive Speech / One-Breath Wake Query                                   │
│  2. Enter Transient State: coordinator.pauseForCommandDispatch()             │
│     -> sets isProcessingCommand = true, isIntentionallyPaused = false        │
│  3. Route via VoiceCommandDispatcher.dispatch(sanitizedText)                 │
└───────────────┬────────────────────────────────────────────┬─────────────────┘
                │                                            │
    [Matched Voice Command]                      [Unmatched Conversational Query]
                │                                            │
                ▼                                            ▼
┌───────────────────────────────────────────┐  ┌───────────────────────────────┐
│          VoiceCommandDispatcher           │  │     Standard AI Query Flow    │
│  • NavigateScreen(Home/History/Settings/  │  │  • wakeWordManager.onHit()    │
│    WakeWordStudio/DiagnosticLog)          │  │  • Emit triggerBanner         │
│  • SelectCategory(All/Work/Personal/...)  │  │  • Navigate to Screen.Chat    │
│  • InChatAction(Clear/Pin/Read/Theme)     │  │  • sendMessage() -> finally   │
│  • ExternalUrlIntent(url)                 │  │    re-arms listener           │
└───────────────┬───────────────────────────┘  └───────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────┐
│   Re-Arm Hook (No triggerBanner Hijack)   │
│  • _isUserVoiceTurn = false               │
│  • completeCommandProcessingAndRearm()    │
│  • sets isProcessingCommand = false       │
│  • resumes continuous listening           │
└───────────────────────────────────────────┘
```

### Data Model & State

1. **`SpeechRecognitionCoordinator` State Properties**:
   - `isProcessingCommand: Boolean`: Backed by the coordinator's transient processing flag; set to `true` during `pauseForCommandDispatch()` and reset to `false` on `completeCommandProcessingAndRearm()` / `resumeAfterCommandNavigation()`.
   - `isIntentionallyPaused: Boolean`: Remains `false` during window focus loss (`setWindowFocused(false)`), lifecycle backgrounding (`onPause` / `onStop`), and voice command dispatch whenever `isContinuousModeEnabled()` is `true`.
   - `shouldAbortListening()`: Updated so that `!isWindowFocused` or `!hasActiveWindowFocus()` does **not** abort listening when `isContinuousModeEnabled() && !isAsleep && !isProcessingCommand`.

2. **`VoiceCommandDispatcher` Action Hierarchy**:
   - `sealed interface VoiceCommandAction`:
     - `data class NavigateToScreen(val route: VoiceScreenDestination, val label: String)` (`HOME`, `HISTORY`, `SETTINGS`, `STUDIO`, `DIAGNOSTICS`)
     - `data class SelectCategoryFilter(val category: String?, val label: String)` (`null` for All, or `"Work"`, `"Personal"`, `"Projects"`, `"Assistant"`, `"Code"`, `"Custom Personas"`, or any existing user session category)
     - `data object ClearChatHistory`
     - `data object ReadLastReply`
     - `data class SetChatPinned(val pinned: Boolean)`
     - `data class SetThemeMode(val mode: String)`
     - `data class SetThemePalette(val palette: String)`
     - `data class OpenExternalUrl(val url: String)`
     - `data object None`

3. **`MainViewModel` Reactive Navigation & Category State**:
   - `selectedCategoryFilter: StateFlow<String?>`: Exposed from `MainViewModel` and observed by `HomeScreen` and `ChatDetailScreen` so voice category commands immediately filter visible sessions without launching any external Intent.
   - `inAppNavigationEvent: SharedFlow<VoiceScreenDestination>`: Emits internal screen transitions handled directly by `MainActivity`'s Compose `navigateInApp(Screen)` state callback.

### Interactive Component & State Mapping

| Trigger / Component | State Mutated | Side-Effect / Guardrail |
| :--- | :--- | :--- |
| **`onWindowFocusChanged(false)` / `onPause()` / `onStop()`** | `isWindowFocused = false`; `isHandsFreeActive` remains `true`; `isIntentionallyPaused` remains `false` | Never emits `RADAR_TOGGLE_OFF`; keeps `SpeechRecognizer` and `WakeWordVoiceService` active in background when `handsFreeMode == true`. |
| **`VoiceCommandDispatcher` In-App Screen Command** (`"open history"`, `"open settings"`, etc.) | `isProcessingCommand = true` -> mutates `currentScreen: Screen` via `navigateInApp` -> `isProcessingCommand = false` | Uses pure Compose state (`Screen.History`, `Screen.Settings`, `Screen.Home`, `Screen.WakeWordStudio`, `Screen.DiagnosticLog`); skips `onKeywordHit` so `triggerBanner` never redirects back to `Screen.Chat`. |
| **`VoiceCommandDispatcher` Category Command** (`"filter by work"`, `"show all categories"`, etc.) | `isProcessingCommand = true` -> updates `selectedCategoryFilter` (`StateFlow<String?>`) and navigates to `Screen.Home` if needed -> `isProcessingCommand = false` | Updates `HomeScreen` and `ChatDetailScreen` category selection in-place and immediately re-arms recognition. |
| **`VoiceCommandDispatcher` In-Chat Action** (`"clear chat"`, `"pin chat"`, `"read last reply"`, `"ocean theme"`) | `isProcessingCommand = true` -> executes repository/settings mutation -> `isProcessingCommand = false` | Resets `_isUserVoiceTurn = false` and calls `completeCommandProcessingAndRearm()` in `finally`. |
| **External Browser / Share Intent Dispatch** | `isProcessingCommand = true`, `isIntentionallyPaused = false` -> launches `Intent` -> re-arms on return / timeout | Prevents permanent pause lock while an external browser or share sheet temporarily holds window focus. |
| **User Taps Radar Pill Off** | `_isHandsFreeActive = false`, `isIntentionallyPaused = true` | Emits `RADAR_TOGGLE_OFF` haptic/audio cue and stops both `SpeechRecognizer` and foreground wake-word listening. |
