package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechRecognitionCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun onEndOfSpeech_doesNotTriggerImmediateStartListening() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var prepareHardwareCalls = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true },
            onPrepareHardwareBeforeStart = { prepareHardwareCalls++ }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.isListening.value)

        coordinator.recognitionListener.onEndOfSpeech()
        assertFalse(coordinator.isListening.value)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("onEndOfSpeech() must never call startListening()", 0, prepareHardwareCalls)
    }

    @Test
    fun onError_noMatchAndSpeechTimeout_debouncesAtLeast1500ms_andStopsAfterIdleThreshold() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var idleThresholdHitCount = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            maxIdleTimeoutRestarts = 3,
            isContinuousModeEnabled = { true },
            onIdleThresholdReached = { idleThresholdHitCount++ }
        )

        // 1st idle timeout (ERROR_NO_MATCH = 7)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(1, coordinator.consecutiveIdleTimeouts)
        assertFalse(coordinator.isIntentionallyPaused)

        // 2nd idle timeout (ERROR_SPEECH_TIMEOUT = 6)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(2, coordinator.consecutiveIdleTimeouts)
        assertFalse(coordinator.isIntentionallyPaused)

        // 3rd idle timeout hits maxIdleTimeoutRestarts = 3 -> pauses and aborts infinite loop
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(3, coordinator.consecutiveIdleTimeouts)
        assertTrue(
            "Coordinator must set isIntentionallyPaused=true when idle threshold is reached",
            coordinator.isIntentionallyPaused
        )
        assertEquals(1, idleThresholdHitCount)
    }

    @Test
    fun guards_isIntentionallyPaused_isAsleep_and_isProcessing_abortStartListening() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // 1. isIntentionallyPaused guard
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = false))

        // 2. isAsleep guard
        coordinator.setIntentionallyPaused(false)
        coordinator.setSleeping(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = true))

        // 3. isProcessing guard
        coordinator.setSleeping(false)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = true))

        // Clear all guards
        coordinator.setProcessingState(false)
        assertFalse(coordinator.shouldAbortListening())
    }

    @Test
    fun streamSystemMute_mutesBeforeStart_andRestoresOnReadyForSpeechOrOnError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 5, 0)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            muteSystemChimeOnStart = true,
            isContinuousModeEnabled = { true }
        )

        // Mute STREAM_SYSTEM around startListening()
        coordinator.muteSystemStreamForStartChime()
        assertTrue(coordinator.isSystemStreamCurrentlyMutedByCoordinator())

        // Restored on onReadyForSpeech()
        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertFalse(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_SYSTEM))

        // Mute again and verify onError() also restores STREAM_SYSTEM
        coordinator.muteSystemStreamForStartChime()
        assertTrue(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_SYSTEM))
    }

    @Test
    fun diagnosticTraceAndMutexSnapshot_recordsThreadAndMutexFlags_andParsesCorrectly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.mutexSnapshotFlow.value.isListening)
        assertTrue(coordinator.traceHistory.value.any { it.contains("CB_ON_READY_FOR_SPEECH") })

        val topRaw = coordinator.traceHistory.value.first()
        val parsed = com.example.ui.screens.DiagnosticTraceParser.parseSrTraceString(topRaw, 1)
        assertEquals("CB_ON_READY_FOR_SPEECH", parsed.stage)
        assertEquals(com.example.ui.screens.DiagnosticFilterCategory.CALLBACKS, parsed.category)
        assertEquals("true", parsed.mutexFlags["listening"])

        coordinator.pauseForCommandDispatch("test_nav")
        assertFalse(coordinator.mutexSnapshotFlow.value.isIntentionallyPaused)
        assertTrue(coordinator.mutexSnapshotFlow.value.isProcessing)
        assertTrue(coordinator.mutexSnapshotFlow.value.isProcessingCommand)

        coordinator.completeCommandProcessingAndRearm(delayMs = 1500L, reason = "test_nav_done")
        assertFalse(coordinator.mutexSnapshotFlow.value.isProcessing)
        assertFalse(coordinator.mutexSnapshotFlow.value.isProcessingCommand)
        assertFalse(coordinator.mutexSnapshotFlow.value.isIntentionallyPaused)

        coordinator.clearTraceHistory()
        assertTrue(coordinator.traceHistory.value.isEmpty())
    }

    @Test
    fun onError_errorClient_whenHandsFreeActive_doesNotSetIntentionallyPaused_recreatesRecognizer_andSchedulesDebouncedRestart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var handsFreeEnabled = true

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { handsFreeEnabled }
        )

        // Simulate a stale pause state prior to ERROR_CLIENT(5) callback while hands-free is ON
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.isIntentionallyPaused)
        val initialRecreations = coordinator.recognizerRecreationCount

        // Trigger ERROR_CLIENT (5) while handsFreeMode is active
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)

        // 1. Must NOT leave isIntentionallyPaused = true when hands-free is active
        assertFalse(
            "ERROR_CLIENT(5) must clear isIntentionallyPaused when hands-free mode is active",
            coordinator.isIntentionallyPaused
        )
        // 2. Must recreate/reset SpeechRecognizer instance before debounced restart
        assertEquals(initialRecreations + 1, coordinator.recognizerRecreationCount)
        // 3. Must schedule a debounced restart
        assertTrue(
            "ERROR_CLIENT(5) must schedule a debounced restart when hands-free is active",
            coordinator.hasPendingRestart
        )
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("CB_ON_ERROR_CLIENT_TRANSIENT_RESET") }
        )

        // When hands-free is OFF, ERROR_CLIENT should abort without scheduling a restart
        handsFreeEnabled = false
        coordinator.stopListening(markIntentionallyPaused = true)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse(coordinator.hasPendingRestart)
    }

    @Test
    fun setWindowFocused_whenTrueAndHandsFreeEnabledAndNotProcessing_clearsIntentionallyPausedAndRearms() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // When hands-free is active, losing window focus sets isTransientFocusLoss = true without setting isIntentionallyPaused
        coordinator.setWindowFocused(false)
        assertTrue(coordinator.isTransientFocusLoss)
        assertFalse(coordinator.isIntentionallyPaused)
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("focused=false isTransientFocusLoss=true") }
        )

        // Simulate a stale background pause
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.isIntentionallyPaused)

        // Re-focus window while hands-free is enabled and not processing a command
        coordinator.setWindowFocused(true)
        testScheduler.advanceTimeBy(SpeechRecognitionCoordinator.WINDOW_FOCUS_DEBOUNCE_MS + 100L)

        assertFalse(coordinator.isTransientFocusLoss)
        assertFalse(
            "setWindowFocused(true) must force isIntentionallyPaused = false when hands-free is active and not processing",
            coordinator.isIntentionallyPaused
        )
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("SET_WINDOW_FOCUSED_REARM") }
        )
    }

    @Test
    fun onErrorClient_whenIsTransientFocusLossTrue_returnsImmediatelyWithoutRecreatingOrQueueingRestart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // Queue a pending restart first, then lose window focus
        coordinator.scheduleDebouncedRestart(delayMs = 500L)
        assertTrue(coordinator.hasPendingRestart)

        coordinator.setWindowFocused(false)
        assertTrue(coordinator.isTransientFocusLoss)
        assertFalse(
            "Losing window focus must immediately cancel any pending restartJob",
            coordinator.hasPendingRestart
        )

        val initialRecreations = coordinator.recognizerRecreationCount

        // Fire 20 consecutive ERROR_CLIENT callbacks while isTransientFocusLoss == true
        repeat(20) {
            coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        }

        assertEquals(
            "onError(ERROR_CLIENT) must NEVER recreate SpeechRecognizer while isTransientFocusLoss is true",
            initialRecreations,
            coordinator.recognizerRecreationCount
        )
        assertFalse(
            "onError(ERROR_CLIENT) must NEVER queue a restart while isTransientFocusLoss is true",
            coordinator.hasPendingRestart
        )

        // Also verify WakeWordManager hard-kills onError(ERROR_CLIENT) during transient focus loss
        val wakeManager = WakeWordManager(context)
        wakeManager.setWindowFocused(false)
        assertTrue(wakeManager.isTransientFocusLoss)
        wakeManager.onError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(wakeManager.isTransientFocusLoss)
        wakeManager.destroy()
    }

    @Test
    fun wakeWordDetection_stopsSpeechRecognitionCoordinatorAndReleasesExclusiveAudioFocusBeforeTts() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val coordinator = viewModel.speechRecognitionCoordinator
        val wakeManager = viewModel.wakeWordManager

        // Trigger wake word with a spoken prompt routing to chat
        wakeManager.onKeywordHit("Gemini", initialQuery = "what is the weather today")

        assertFalse(
            "SpeechRecognitionCoordinator must NOT be listening after wake word handoff",
            coordinator.isListening.value
        )
        assertFalse(
            "SpeechRecognitionCoordinator must NOT have a pending restart during wake query processing",
            coordinator.hasPendingRestart
        )
        assertFalse(
            "WakeWordManager must release exclusive audio focus before Gemini / TTS begins",
            wakeManager.hasAudioFocus.value
        )
        assertEquals(0, wakeManager.activeAudioRecordCount)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun audioFocusChangeListener_onLossTransient_setsFlagAndCancelsWithoutRestart_andOnGain_restartsAfter500ms() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var startHardwareCalls = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true },
            onPrepareHardwareBeforeStart = { startHardwareCalls++ }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.isListening.value)
        assertFalse(coordinator.isTransientAudioFocusLoss)

        // 1. AUDIOFOCUS_LOSS_TRANSIENT (-2): sets transient loss flag, cancels recognizer, and does NOT restart immediately
        coordinator.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(
            "AUDIOFOCUS_LOSS_TRANSIENT (-2) must set isTransientAudioFocusLoss = true",
            coordinator.isTransientAudioFocusLoss
        )
        assertFalse(coordinator.isListening.value)
        assertFalse(
            "AUDIOFOCUS_LOSS_TRANSIENT (-2) must NOT queue an immediate restart",
            coordinator.hasPendingRestart
        )

        // Even if SpeechRecognizer emits an error after cancel(), it must NOT restart while isTransientAudioFocusLoss is true
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse(coordinator.hasPendingRestart)
        assertEquals(0, startHardwareCalls)

        // 2. AUDIOFOCUS_GAIN (1): checks transient loss flag, clears it, and schedules a 500ms debounced restart
        coordinator.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(
            "AUDIOFOCUS_GAIN (1) must clear isTransientAudioFocusLoss",
            coordinator.isTransientAudioFocusLoss
        )
        assertTrue(
            "AUDIOFOCUS_GAIN (1) must schedule a 500ms debounced restart",
            coordinator.hasPendingRestart
        )
        // Before 500ms elapses, must not have restarted yet
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0, startHardwareCalls)

        // After 500ms elapses, restart fires
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("AUDIOFOCUS_GAIN_REARM_FIRING") }
        )
    }

    @Test
    fun uiDictationMicButton_synchronouslyStopsAndReleasesWakeWordVoiceServiceAudioRecordBeforeStartListening() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java)
        )
        val service = serviceController.create().startCommand(0, 1).get()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        try {
            val initialHandoffs = WakeWordVoiceService.synchronousDictationHandoffReleaseCount
            var servicePausedWhenRecognizerPrepared = false
            var serviceMicReleasedWhenRecognizerPrepared = false

            // Tap UI dictation mic button
            val started = viewModel.toggleInChatDictation()
            assertTrue(started)
            assertTrue(
                "Tapping UI mic button must synchronously invoke stopAndReleaseServiceAudioRecordSynchronously()",
                WakeWordVoiceService.synchronousDictationHandoffReleaseCount > initialHandoffs
            )
            servicePausedWhenRecognizerPrepared = WakeWordVoiceService.isIntentionallyPaused
            serviceMicReleasedWhenRecognizerPrepared = !WakeWordVoiceService.isMicrophoneInitialized.value
            assertTrue(
                "WakeWordVoiceService must be marked paused before SpeechRecognizer.startListening()",
                servicePausedWhenRecognizerPrepared
            )
            assertTrue(
                "WakeWordVoiceService AudioRecord must be synchronously released before SpeechRecognizer.startListening()",
                serviceMicReleasedWhenRecognizerPrepared
            )
        } finally {
            viewModel.cleanShutdown(null)
            serviceController.destroy()
        }
    }

    @Test
    fun featureTurnOffAndAppBackgrounded_callsSpeechRecognizerDestroyAndReleasesProcessingMutex() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        val coordinator = viewModel.speechRecognitionCoordinator

        // Enable Hands-Free and simulate an active processing mutex lock
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.isProcessing)
        val destroyCountBeforeOff = coordinator.destroyCount

        // 1. Turning feature OFF must call SpeechRecognizer.destroy() and release the Processing Mutex
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = false)
        assertTrue(
            "Turning Hands-Free OFF must call SpeechRecognizer.destroy()",
            coordinator.destroyCount > destroyCountBeforeOff
        )
        assertFalse(
            "Turning Hands-Free OFF must release the Processing Mutex (isProcessing = false)",
            coordinator.isProcessing
        )
        assertFalse(coordinator.hasActiveRecognizerInstance)

        // 2. Backgrounding the app must also call SpeechRecognizer.destroy() and release the Processing Mutex
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.isProcessing)
        val destroyCountBeforeBg = coordinator.destroyCount

        viewModel.onAppBackgrounded()
        assertTrue(
            "Backgrounding the app must call SpeechRecognizer.destroy()",
            coordinator.destroyCount > destroyCountBeforeBg
        )
        assertFalse(
            "Backgrounding the app must release the Processing Mutex (isProcessing = false)",
            coordinator.isProcessing
        )
        assertFalse(coordinator.hasActiveRecognizerInstance)
        assertTrue(
            "Backgrounding must preserve isHandsFreeActive = true",
            viewModel.isHandsFreeActive.value
        )

        viewModel.cleanShutdown(null)
    }

    @Test
    fun ttsSpeak_synchronouslyReleasesWakeWordVoiceServiceAndRearmsAfter500msDebounce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(500L, TextToSpeechManager.ACOUSTIC_SETTLING_DELAY_MS)

        var settledCount = 0
        val ttsManager = TextToSpeechManager(
            context = app,
            scope = this,
            mainDispatcher = dispatcher,
            onPlaybackStarted = {
                WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
            },
            onPlaybackSettledAndReadyToListen = {
                settledCount++
            }
        )

        WakeWordVoiceService.setIntentionallyPaused(false)
        val releaseCountBefore = WakeWordVoiceService.synchronousDictationHandoffReleaseCount

        // 1. Yield Before Speech: speak() synchronously calls stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
        ttsManager.speak("Hello from Gemini chat greeting")
        assertTrue(
            "speak() must synchronously call stopAndReleaseServiceAudioRecordSynchronously(\"tts_speaking\")",
            WakeWordVoiceService.synchronousDictationHandoffReleaseCount > releaseCountBefore
        )
        assertEquals("tts_speaking", WakeWordVoiceService.lastSynchronousReleaseReason)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isMicrophoneInitialized.value)

        // 2. Simulate UtteranceProgressListener.onStart -> onDone and verify 500ms debounced delay
        ttsManager.utteranceProgressListener.onStart("utt_1")
        assertTrue(ttsManager.isSpeaking.value)
        WakeWordVoiceService.setIntentionallyPaused(true)

        ttsManager.utteranceProgressListener.onDone("utt_1")
        // At 300ms (< 500ms), must NOT have re-armed yet
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0, settledCount)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)

        // After 500ms total, WakeWordVoiceService is re-armed and onPlaybackSettledAndReadyToListen fires
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(settledCount >= 1)
        assertFalse(
            "After 500ms onDone debounce, WakeWordVoiceService must be unpaused",
            WakeWordVoiceService.isIntentionallyPaused
        )

        // 3. Verify onStop also enforces the 500ms debounced re-arming
        val settledBeforeStop = settledCount
        ttsManager.utteranceProgressListener.onStart("utt_2")
        WakeWordVoiceService.setIntentionallyPaused(true)
        ttsManager.utteranceProgressListener.onStop("utt_2", true)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(settledBeforeStop, settledCount)
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(settledBeforeStop + 1, settledCount)
        assertFalse(WakeWordVoiceService.isIntentionallyPaused)

        ttsManager.shutdown()
    }

    @Test
    fun wakeWordDetected_synchronouslyReleasesWakeWordVoiceServiceBeforeChatNavigationAndGuardsInit() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        try {
            WakeWordVoiceService.setIntentionallyPaused(false)
            val releaseCountBefore = WakeWordVoiceService.synchronousDictationHandoffReleaseCount

            // Detect wake word ("Hey Gemini") via SpeechRecognizer results
            val matched = viewModel.processSpeechRecognizerResults(
                candidates = listOf("Hey Gemini"),
                isFinal = true
            )
            assertTrue(matched)
            assertTrue(
                "Wake word detection must immediately invoke stopAndReleaseServiceAudioRecordSynchronously(\"wake_word_detected\")",
                WakeWordVoiceService.synchronousDictationHandoffReleaseCount > releaseCountBefore
            )
            assertEquals("wake_word_detected", WakeWordVoiceService.lastSynchronousReleaseReason)
            assertFalse(WakeWordVoiceService.isMicrophoneInitialized.value)

            // Verify that while TTS / processing mutex is active on ChatScreen load, non-user-initiated
            // startContinuousWakeWordListening(userInitiated = false) and onAppForegrounded() do not grab the mic
            viewModel.speechRecognitionCoordinator.setProcessingState(true)
            WakeWordVoiceService.setIntentionallyPaused(true)
            viewModel.startContinuousWakeWordListening(userInitiated = false)
            viewModel.onAppForegrounded()
            assertTrue(
                "Chat screen init/onResume must not unpause WakeWordVoiceService while handoff/processing mutex is active",
                WakeWordVoiceService.isIntentionallyPaused
            )
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun errorInsufficientPermissions_whenOsRecordAudioPermissionGranted_isIgnoredAsFalsePositive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var currentTime = 10_000L
        var permissionDeniedCallbackCount = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            scope = this,
            mainDispatcher = dispatcher,
            clockMs = { currentTime },
            muteSystemChimeOnStart = false,
            hasRecordAudioPermission = { true },
            isContinuousModeEnabled = { true },
            onInsufficientPermissions = { permissionDeniedCallbackCount++ }
        )

        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(
            "When OS RECORD_AUDIO permission is granted, false-positive ERROR_INSUFFICIENT_PERMISSIONS(9) must be ignored",
            0,
            permissionDeniedCallbackCount
        )
        assertFalse(
            "False-positive ERROR_INSUFFICIENT_PERMISSIONS(9) must not set isIntentionallyPaused = true",
            coordinator.isIntentionallyPaused
        )
    }
}



