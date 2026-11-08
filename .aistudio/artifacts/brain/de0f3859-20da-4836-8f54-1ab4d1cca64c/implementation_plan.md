# Critical Bug Fix Plan: Lifecycle Debounce, Internal Mic Dictation, Top Bar Layout & Chat Spinner Recovery

## Root Causes Identified
1. **Window Focus Loop (Rapid Dinging on Samsung One UI)**:
   - `SpeechRecognitionCoordinator.setWindowFocused()`, `WakeWordManager.setWindowFocused()`, `MainViewModel.onWindowFocusChanged()`, and the root `LaunchedEffect(hasWindowFocus, ...)` in `MainActivity.kt` react immediately (0ms debounce) whenever `onWindowFocusChanged` flips `false` $\leftrightarrow$ `true` (e.g., pulling down the notification shade or opening a dialog).
   - This causes `stopListening()` and `startListening()` to cycle rapidly on transient focus changes.
2. **Chat Mic Button & Intent Calls**:
   - `MainViewModel.toggleInChatDictation()` constructs a `WakeWordVoiceService` `Intent` to call `stopService(...)` and can trigger permission launcher intents or abort when `_hasWindowFocus` transiently flips.
3. **Top App Bar "Hands-Free" Pill Overlap**:
   - `TopBarHandsFreeRadarPill` in `CategoryNavigationComponents.kt` and `ChatDetailScreen.kt` lacks a maximum width constraint (`widthIn(max = ...)`) and start padding, crowding the `TopAppBar` and overlapping the navigation back arrow on compact screens.
4. **Infinite Chat Spinner on Typed Messages**:
   - In `MainViewModel.sendMessage()` (line 3933 & line 4021), `activeGenerationJob = viewModelScope.launch { ... }` sets `_isGenerating.value = true` at line 4018 and then calls `activeGenerationJob?.cancel()` at line 4021—**cancelling its own outer coroutine** right before launching the inner streaming coroutine. As a result, the inner coroutine launches into a cancelled scope and never executes its `try/finally` block, leaving `_isGenerating.value = true` (`isLoading = true`) indefinitely.
   - Additionally, exceptions thrown during generation only updated `_errorMessage` rather than inserting a visible error message bubble into the active chat thread.

---

## Proposed Fixes

### 1. Fix the Window Focus Loop (1500ms Debounce & No Transient Recognizer Recreation/Restart)
- **`SpeechRecognitionCoordinator.kt`**:
  - Add `const val WINDOW_FOCUS_DEBOUNCE_MS = 1500L` and `private var windowFocusDebounceJob: Job? = null`.
  - In `setWindowFocused(focused: Boolean)`, debounce focus changes by a strict `1500ms` before acting on focus loss or gain.
  - If window focus is lost and regained within `1500ms` (e.g., notification shade pull-down or dialog open/close), cancel the pending debounce job and keep the existing `SpeechRecognizer` session running without calling `stopListening()`, `startListening()`, or recreating the recognizer instance.
- **`WakeWordManager.kt`**:
  - Add `const val WINDOW_FOCUS_DEBOUNCE_MS = 1500L` and debounce `setWindowFocused(focused: Boolean)` by `1500ms` via `mainHandler` so transient focus changes never release or restart `AudioRecord` / recognizer state.
- **`MainViewModel.kt` & `MainActivity.kt`**:
  - Debounce `MainViewModel.onWindowFocusChanged(hasFocus: Boolean)` by `1500ms` and remove raw `hasWindowFocus` from triggering immediate un-debounced restarts in `MainActivity.kt`.

### 2. Nuke External Mic Intents (`viewModel.toggleInternalDictation()`)
- **`MainViewModel.kt`**:
  - Add `fun toggleInternalDictation(manualDraftText: String = ""): Boolean` (and route `toggleInChatDictation` to it).
  - Remove all `Intent(...)` / `stopService(...)` / external activity launches from the dictation toggle path; pause background service state purely in memory (`WakeWordVoiceService.pauseForInternalDictationInMemory()`) and use the app's existing audio permissions (`checkRecordAudioPermission()`) to toggle `speechRecognitionCoordinator` directly.
- **`ChatDetailScreen.kt` & `FloatingMovableChatInput.kt`**:
  - Wire the chat microphone buttons (`start_voice_button` and `floating_voice_button`) exclusively to `viewModel.toggleInternalDictation()` with zero `Intent` or `startActivity` calls.

### 3. Fix Top App Bar UI Overlap
- **`CategoryNavigationComponents.kt` (`TopBarHandsFreeRadarPill`) & `ChatDetailScreen.kt`**:
  - Add start padding (`Modifier.padding(start = 8.dp)`) and constrain maximum width (`Modifier.widthIn(max = 104.dp)`).
  - Reduce the icon size from `14.dp` to `11.dp`, status dot from `12.dp` to `9.dp`, and horizontal spacing/padding so the pill is compact.
  - Ensure the `TopAppBar` navigation back arrow (`chat_back_button`) and title row have clean spacing and `TextOverflow.Ellipsis` so the back button is 100% unobstructed.

### 4. Fix Infinite Chat Spinner in `MainViewModel.sendMessage()`
- **`MainViewModel.kt`**:
  - Remove the self-cancelling nested `activeGenerationJob?.cancel()` / inner `launch` inside `sendMessage()` so a single coroutine job manages the entire turn lifecycle.
  - Wrap the Gemini API call and turn preparation in a strict `try / catch (e: Throwable) / finally` block with a timeout guard (`withTimeout(45_000L)`), bypassing any blocking audio mutex when sending text messages.
  - In the `catch` block (and if the response stream returns empty on failure), append a visible error message (`ChatMessage(chatId = resolvedChatId, sender = "gemini", content = "⚠️ Error: ...")`) into Room and `ChatMemoryBufferManager` so the failure prints directly into the chat thread.
  - In `finally` (with `NonCancellable` cleanup), immediately set `_isGenerating.value = false` and `_streamingText.value = null` so the loading spinner always recovers.

---

## Verification Plan
- Compile the APK with `compile_applet`.
- Run Robolectric unit tests verifying:
  1. Transient `onWindowFocusChanged(false)` followed by `onWindowFocusChanged(true)` within `< 1500ms` does not stop, recreate, or restart `SpeechRecognizer`.
  2. `viewModel.toggleInternalDictation()` toggles internal dictation without firing any external `Intent` or `startActivity`.
  3. `MainViewModel.sendMessage()` immediately clears `isGenerating = false` and appends an error message to the chat thread when the Gemini API throws or times out.
- Re-scan after fixes and output the full error & fix summary in chat.
