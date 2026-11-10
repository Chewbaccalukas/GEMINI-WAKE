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

    @Test
    fun v10_3_foregroundMicrophoneServiceType_singleTopManifest_andChatLaunchIntentFlags() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        // 1. Verify AndroidManifest.xml declarations
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestXml = manifestFile!!.readText()
        assertTrue(
            "AndroidManifest.xml must declare FOREGROUND_SERVICE_MICROPHONE permission",
            manifestXml.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE")
        )
        assertTrue(
            "WakeWordVoiceService must declare android:foregroundServiceType=\"microphone\"",
            manifestXml.contains("android:foregroundServiceType=\"microphone\"")
        )
        assertTrue(
            "MainActivity must declare android:launchMode=\"singleTop\"",
            manifestXml.contains("android:launchMode=\"singleTop\"")
        )

        // 2. Verify WakeWordVoiceService startForeground() passes ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java).apply {
                action = WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            }
        )
        val service = serviceController.create().startCommand(0, 1).get()
        try {
            val shadowService = org.robolectric.Shadows.shadowOf(service)
            assertEquals(WakeWordVoiceService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                assertEquals(
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    service.foregroundServiceType
                )
            }

            // 3. Verify Chat launch Intent includes FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT
            val detection = com.example.voice.WakeWordDetectionResult(
                wakeWord = com.example.data.model.WakeWord(
                    phrase = "Hey Gemini",
                    triggerType = com.example.data.model.TriggerType.CHAT,
                    chatId = 42L,
                    chatTitle = "Samsung Voice Chat"
                ),
                matchedPhrase = "Hey Gemini",
                followUpQuery = null,
                rawTranscript = "Hey Gemini"
            )
            val launchIntent = WakeWordVoiceService.createChatLaunchIntent(app, detection)
            val expectedFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            assertEquals(
                "Chat launch intent must include FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT",
                expectedFlags,
                launchIntent.flags and expectedFlags
            )
            assertEquals(42L, launchIntent.getLongExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, -1L))
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun v10_4_samsungAndroid14_noSilentMinimizeOnForegroundSpeechRecognizerActivation() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // 1. Verify AndroidManifest.xml <queries> visibility for Android 14 / Samsung One UI speech services
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestXml = manifestFile!!.readText()
        assertTrue(manifestXml.contains("android.speech.RecognitionService"))
        assertTrue(manifestXml.contains("android.speech.action.RECOGNIZE_SPEECH"))
        assertTrue(manifestXml.contains("com.google.android.tts"))
        assertTrue(manifestXml.contains("com.samsung.android.bixby.agent"))

        // 2. Verify foreground SpeechRecognizer intent strips FLAG_ACTIVITY_NEW_TASK and task flags
        val dirtyIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val sanitizedRecognizerIntent = WakeWordManager.configureRecognizerIntent(dirtyIntent, app.packageName)
        assertEquals(
            "Foreground RecognizerIntent must NEVER carry FLAG_ACTIVITY_NEW_TASK",
            0,
            sanitizedRecognizerIntent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        )

        // 3. Verify WakeWordVoiceService does NOT fire startActivity(FLAG_ACTIVITY_NEW_TASK) when MainActivity is already in foreground
        val serviceController = org.robolectric.Robolectric.buildService(WakeWordVoiceService::class.java)
        val service = serviceController.create().get()
        try {
            service.setActiveWakeWordsForTesting(
                listOf(
                    com.example.data.model.WakeWord(
                        phrase = "Hey Gemini",
                        triggerType = com.example.data.model.TriggerType.CHAT,
                        chatId = 77L,
                        chatTitle = "Note 20 Chat"
                    )
                )
            )
            while (shadowApp.nextStartedActivity != null) {
                // drain any prior test intents
            }
            WakeWordVoiceService.setIntentionallyPaused(false)
            WakeWordVoiceService.setSleepMode(false)
            WakeWordVoiceService.isMainActivityInForeground = true
            val fgMatch = service.checkTranscriptAgainstDatabase("Hey Gemini")
            org.junit.Assert.assertNotNull("Wake word should match in foreground", fgMatch)
            assertEquals(
                "When MainActivity is already in the foreground, WakeWordVoiceService must NOT fire background startActivity(FLAG_ACTIVITY_NEW_TASK)",
                null,
                shadowApp.nextStartedActivity
            )

            // When app is backgrounded, WakeWordVoiceService MUST fire startActivity(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT)
            WakeWordVoiceService.setIntentionallyPaused(false)
            WakeWordVoiceService.setSleepMode(false)
            WakeWordVoiceService.isMainActivityInForeground = false
            val bgMatch = service.checkTranscriptAgainstDatabase("Hey Gemini")
            org.junit.Assert.assertNotNull("Wake word should match in background", bgMatch)
            val startedFromBg = shadowApp.nextStartedActivity
            org.junit.Assert.assertNotNull(
                "When MainActivity is in the background, WakeWordVoiceService MUST launch MainActivity",
                startedFromBg
            )
            val expectedFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            assertEquals(expectedFlags, startedFromBg!!.flags and expectedFlags)
        } finally {
            WakeWordVoiceService.isMainActivityInForeground = true
            serviceController.destroy()
        }
    }

    @Test
    fun srTraceLogging_debounceWindow_audioFocusResponses_intentSilenceExtras_andServiceHandoffNullification() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java)
        )
        val service = serviceController.create().get()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onWindowFocusChanged(true)

        try {
            viewModel.clearDiagnosticTraceLogs()

            // 1. Trigger manual dictation -> logs SERVICE_PAUSED_FOR_UI_MIC, SERVICE_AUDIORECORD_NULLIFIED, and UI_MIC_DEBOUNCE_START (500ms) immediately
            val started = viewModel.toggleInternalDictation()
            assertTrue(started)
            assertEquals(500L, viewModel.UI_MIC_HARDWARE_CLEAR_DELAY_MS)

            val tracesBeforeDelay = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=SERVICE_PAUSED_FOR_UI_MIC in [SR-Trace] when UI mic is tapped",
                tracesBeforeDelay.any { it.contains("stage=SERVICE_PAUSED_FOR_UI_MIC") }
            )
            assertTrue(
                "Expected stage=UI_MIC_DEBOUNCE_START with delayMs=500 in [SR-Trace] before 500ms delay completes",
                tracesBeforeDelay.any { it.contains("stage=UI_MIC_DEBOUNCE_START") && it.contains("delayMs=500") }
            )
            assertTrue(
                "Expected stage=SERVICE_AUDIORECORD_NULLIFIED with exact millisecond in [SR-Trace]",
                tracesBeforeDelay.any { it.contains("stage=SERVICE_AUDIORECORD_NULLIFIED") && it.contains("serviceAudioRecord=null atEpochMs=") }
            )
            assertTrue(
                "lastAudioRecordNullifiedAtEpochMs must be > 0",
                WakeWordVoiceService.lastAudioRecordNullifiedAtEpochMs > 0L
            )

            // Advance the Robolectric paused main looper clock by 550ms so delay(500L) completes
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(550))

            val tracesAfterDelay = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=UI_MIC_DEBOUNCE_END in [SR-Trace] after 500ms delay",
                tracesAfterDelay.any { it.contains("stage=UI_MIC_DEBOUNCE_END") && it.contains("delayMs=500") && it.contains("elapsedMs=") }
            )

            // Step 4: Closing the UI mic resumes WakeWordVoiceService
            val stopped = viewModel.toggleInternalDictation()
            assertFalse(stopped)
            val tracesAfterClose = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=SERVICE_RESUMED_AFTER_UI_MIC when UI mic closes",
                tracesAfterClose.any { it.contains("stage=SERVICE_RESUMED_AFTER_UI_MIC") }
            )

            // 2. Verify AudioFocus request, abandon, and callback logs include exact OS response names
            service.requestExclusiveAudioFocus()
            service.abandonExclusiveAudioFocus()
            viewModel.speechRecognitionCoordinator.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            viewModel.speechRecognitionCoordinator.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)

            val focusTraces = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected SERVICE_AUDIOFOCUS_REQUEST with AUDIOFOCUS_REQUEST_GRANTED or FAILED in [SR-Trace]",
                focusTraces.any {
                    it.contains("stage=SERVICE_AUDIOFOCUS_REQUEST") &&
                        (it.contains("AUDIOFOCUS_REQUEST_GRANTED") || it.contains("AUDIOFOCUS_REQUEST_FAILED"))
                }
            )
            assertTrue(
                "Expected SERVICE_AUDIOFOCUS_ABANDON in [SR-Trace]",
                focusTraces.any { it.contains("stage=SERVICE_AUDIOFOCUS_ABANDON") }
            )
            assertTrue(
                "Expected AUDIOFOCUS_YIELD with response=AUDIOFOCUS_LOSS_TRANSIENT(-2) in [SR-Trace]",
                focusTraces.any {
                    it.contains("stage=AUDIOFOCUS_YIELD") &&
                        it.contains("response=AUDIOFOCUS_LOSS_TRANSIENT(-2)")
                }
            )

            // 3. Verify createRecognizerIntent & configureRecognizerIntent dump exact 3000ms/5000ms/3000ms EOU silence extras
            val coordIntent = viewModel.speechRecognitionCoordinator.createRecognizerIntent()
            assertEquals(
                3000L,
                coordIntent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1L)
            )
            assertEquals(
                5000L,
                coordIntent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1L)
            )
            assertEquals(
                3000L,
                coordIntent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, -1L)
            )
            val intentTraces = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=RECOGNIZER_INTENT_CONFIGURED with 5000ms complete & 3000ms possibly-complete/min-length silence values in [SR-Trace]",
                intentTraces.any {
                    it.contains("stage=RECOGNIZER_INTENT_CONFIGURED") &&
                        it.contains("EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS=5000ms") &&
                        it.contains("EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS=3000ms") &&
                        it.contains("EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS=3000ms")
                }
            )
        } finally {
            viewModel.cleanShutdown(null)
            serviceController.destroy()
        }
    }

    @Test
    fun wakeWordVoiceService_speechRecognizerCustomWakeWord_triggersGeminiAssistantAndReleasesMic() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // Start WakeWordVoiceService with a customizable wake word via Intent extra
        val startIntent = android.content.Intent(app, WakeWordVoiceService::class.java).apply {
            action = WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            putExtra(WakeWordVoiceService.EXTRA_CUSTOM_WAKE_WORD, "Hey Nebula")
            putExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, 88L)
            putExtra(WakeWordVoiceService.EXTRA_CUSTOM_WAKE_WORD_SENSITIVITY, 0.80f)
        }
        val serviceController = org.robolectric.Robolectric.buildService(WakeWordVoiceService::class.java, startIntent)
        val service = serviceController.create().startCommand(0, 1).get()

        var triggeredResult: WakeWordDetectionResult? = null
        WakeWordVoiceService.onGeminiAssistantTriggeredCallback = { result ->
            triggeredResult = result
        }

        try {
            // 1. Verify custom wake word was configured and persisted
            assertEquals("Hey Nebula", WakeWordVoiceService.customWakeWord.value)
            assertEquals(88L, WakeWordVoiceService.customWakeWordTargetChatId)

            // 2. Verify createServiceRecognizerIntent configures Android SpeechRecognizer properly
            val srIntent = service.createServiceRecognizerIntent()
            assertEquals(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH, srIntent.action)
            assertEquals(
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                srIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL)
            )
            assertTrue(srIntent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            assertEquals(
                0,
                srIntent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )

            // 3. Simulate background mode and feed a SpeechRecognizer final result bundle containing the custom wake word + query
            while (shadowApp.nextStartedActivity != null) {
                // drain prior intents
            }
            WakeWordVoiceService.setIntentionallyPaused(false)
            WakeWordVoiceService.setSleepMode(false)
            WakeWordVoiceService.isMainActivityInForeground = false

            val resultsBundle = android.os.Bundle().apply {
                putStringArrayList(
                    android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf("Hey Nebula summarize my quest log")
                )
            }
            service.serviceRecognitionListener.onResults(resultsBundle)

            // 4. Verify custom wake word matched, extracted follow-up query, released mic synchronously, and launched Gemini assistant
            org.junit.Assert.assertNotNull("Expected Gemini assistant trigger from custom wake word", triggeredResult)
            assertEquals("Hey Nebula", triggeredResult!!.matchedPhrase)
            assertEquals("summarize my quest log", triggeredResult!!.followUpQuery)
            assertEquals("wake_word_detected", WakeWordVoiceService.lastSynchronousReleaseReason)
            assertTrue(WakeWordVoiceService.isIntentionallyPaused)

            val launchedIntent = shadowApp.nextStartedActivity
            org.junit.Assert.assertNotNull("Expected MainActivity launch intent from background service", launchedIntent)
            assertEquals(88L, launchedIntent!!.getLongExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, -1L))
            assertEquals("Hey Nebula", launchedIntent.getStringExtra(WakeWordVoiceService.EXTRA_WAKE_MATCHED_PHRASE))
            assertEquals("summarize my quest log", launchedIntent.getStringExtra(WakeWordVoiceService.EXTRA_WAKE_INITIAL_QUERY))

            // 5. Dynamically update customizable wake word at runtime via setCustomWakeWord and verify partial result trigger
            triggeredResult = null
            WakeWordVoiceService.setCustomWakeWord(
                phrase = "Computer",
                chatId = 99L,
                sensitivity = 0.75f,
                context = app
            )
            WakeWordVoiceService.setIntentionallyPaused(false)
            val partialBundle = android.os.Bundle().apply {
                putStringArrayList(
                    android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf("Computer what is the weather")
                )
            }
            service.serviceRecognitionListener.onPartialResults(partialBundle)
            org.junit.Assert.assertNotNull("Expected partial result to trigger custom wake word 'Computer'", triggeredResult)
            assertEquals("Computer", triggeredResult!!.matchedPhrase)
            assertEquals("what is the weather", triggeredResult!!.followUpQuery)
        } finally {
            WakeWordVoiceService.onGeminiAssistantTriggeredCallback = null
            WakeWordVoiceService.customWakeWordOverrideActive = false
            WakeWordVoiceService.isMainActivityInForeground = true
            serviceController.destroy()
        }
    }

    @Test
    fun customWakeWordSharedPreferences_saveAndReset_syncsWithAppSettingsManagerAndWakeWordVoiceService() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Save custom wake word via MainViewModel -> AppSettingsManager -> SharedPreferences
            val savedPhrase = viewModel.saveCustomWakeWordToPrefs("Hey Starlight", targetChatId = 55L)
            assertEquals("Hey Starlight", savedPhrase)
            assertEquals("Hey Starlight", viewModel.customWakeWordPhrase.value)
            assertEquals("Hey Starlight", com.example.util.AppSettingsManager.getCustomWakeWordPhrase(app))
            assertEquals("Hey Starlight", WakeWordVoiceService.customWakeWord.value)

            // 2. Verify raw SharedPreferences file ("gemini_wake_settings") holds the saved value
            val prefs = app.getSharedPreferences(com.example.util.AppSettingsManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            assertEquals(
                "Hey Starlight",
                prefs.getString(com.example.util.AppSettingsManager.KEY_CUSTOM_WAKE_WORD_PHRASE, null)
            )

            // 3. Reset to default ("Hey Gemini") and verify both SharedPreferences and StateFlows update
            val resetPhrase = viewModel.resetCustomWakeWordInPrefs()
            assertEquals("Hey Gemini", resetPhrase)
            assertEquals("Hey Gemini", viewModel.customWakeWordPhrase.value)
            assertEquals(
                "Hey Gemini",
                prefs.getString(com.example.util.AppSettingsManager.KEY_CUSTOM_WAKE_WORD_PHRASE, null)
            )
        } finally {
            WakeWordVoiceService.customWakeWordOverrideActive = false
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun customWakeWordDataStore_defineUpdateAndReset_persistsInJetpackDataStoreAndSyncsService() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Define a custom wake word in Jetpack DataStore
            val definedPhrase = viewModel.saveCustomWakeWordToDataStoreSuspend("Hey Aurora", targetChatId = 101L)
            assertEquals("Hey Aurora", definedPhrase)
            assertEquals("Hey Aurora", com.example.util.WakeWordDataStoreManager.getCustomWakeWord(app))
            assertEquals("Hey Aurora", viewModel.dataStoreCustomWakeWordPhrase.value)
            assertEquals("Hey Aurora", WakeWordVoiceService.customWakeWord.value)

            // 2. Update the custom wake word in Jetpack DataStore
            val updatedPhrase = com.example.util.WakeWordDataStoreManager.saveCustomWakeWord(
                context = app,
                phrase = "Hey Nova",
                chatId = 202L,
                sensitivity = 0.85f
            )
            assertEquals("Hey Nova", updatedPhrase)
            assertEquals("Hey Nova", com.example.util.WakeWordDataStoreManager.getCustomWakeWord(app))
            val config = com.example.util.WakeWordDataStoreManager.customWakeWordConfigState.value
            assertEquals("Hey Nova", config.phrase)
            assertEquals(202L, config.targetChatId)
            assertEquals(0.85f, config.sensitivity, 0.001f)
            assertTrue(config.updatedAtEpochMs > 0L)

            // 3. Reset in Jetpack DataStore back to "Hey Gemini"
            val resetPhrase = viewModel.resetCustomWakeWordInDataStoreSuspend()
            assertEquals("Hey Gemini", resetPhrase)
            assertEquals("Hey Gemini", com.example.util.WakeWordDataStoreManager.getCustomWakeWord(app))
        } finally {
            WakeWordVoiceService.customWakeWordOverrideActive = false
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun simulateWakeWordDetectionDiagnostic_verifiesMicrophonePreRollPhraseMatchingAndSynchronousHandoff() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            viewModel.saveCustomWakeWordToPrefs("Hey Nebula", targetChatId = 77L)
            val handoffBefore = WakeWordVoiceService.synchronousDictationHandoffReleaseCount

            val report = viewModel.simulateWakeWordDetectionDiagnostic(
                wakePhraseOverride = "Hey Nebula",
                followUpQuery = "diagnostic system check"
            )

            assertTrue("Expected overall diagnostic simulation to pass", report.overallPassed)
            assertTrue("Expected microphone & pre-roll check to pass", report.microphoneCheckPassed)
            assertTrue("Expected processing & phrase matching check to pass", report.processingAndMatchingPassed)
            assertTrue("Expected synchronous mic handoff check to pass", report.synchronousMicHandoffVerified)
            assertEquals("Hey Nebula diagnostic system check", report.simulatedTranscript)
            assertEquals("Hey Nebula", report.matchedPhrase)
            assertEquals("diagnostic system check", report.followUpQuery)
            assertTrue("Expected >= 640B 16kHz PCM pre-roll frame", report.preRollPcmBytesVerified >= 640)
            assertTrue("Expected non-zero RMS dB from synthesized PCM frame", report.computedRmsDb > 10f)
            assertTrue(
                "Expected synchronous handoff count to increment",
                report.handoffReleaseCountAfter > handoffBefore
            )
            assertEquals("Hey Nebula", viewModel.lastDetectedWakePhrase.value)
            assertEquals(report.matchedPhrase, WakeWordVoiceService.lastSimulationDiagnosticReport.value?.matchedPhrase)
            assertEquals(3, report.diagnosticSteps.size)
        } finally {
            WakeWordVoiceService.customWakeWordOverrideActive = false
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun wakeWordDetectionSensitivitySlider_savesThresholdInDataStoreAndSyncsService() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Adjust sensitivity threshold to 0.90f (Strict Match) and persist to Jetpack DataStore
            val savedStrict = viewModel.saveWakeWordSensitivityToDataStoreSuspend(0.90f)
            assertEquals(0.90f, savedStrict, 0.001f)
            assertEquals(0.90f, com.example.util.WakeWordDataStoreManager.getWakeWordSensitivity(app), 0.001f)
            assertEquals(0.90f, viewModel.wakeWordSensitivity.value, 0.001f)
            assertEquals(0.90f, viewModel.dataStoreWakeWordSensitivity.value, 0.001f)
            assertEquals(0.90f, WakeWordVoiceService.customWakeWordSensitivity, 0.001f)
            assertEquals(0.90f, viewModel.wakeWordManager.wakeWordSensitivity, 0.001f)

            // 2. Adjust sensitivity threshold to 0.62f (Permissive) directly via WakeWordDataStoreManager
            val savedPermissive = com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(app, 0.62f)
            assertEquals(0.62f, savedPermissive, 0.001f)
            assertEquals(0.62f, com.example.util.WakeWordDataStoreManager.getWakeWordSensitivity(app), 0.001f)
            assertEquals(0.62f, com.example.util.AppSettingsManager.getCustomWakeWordSensitivity(app), 0.001f)
            assertEquals(0.62f, WakeWordVoiceService.customWakeWordSensitivity, 0.001f)

            // 3. Verify out-of-range values are safely clamped to [0.50f, 0.98f]
            val clampedLow = com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(app, 0.25f)
            assertEquals(0.50f, clampedLow, 0.001f)
            val clampedHigh = com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(app, 1.25f)
            assertEquals(0.98f, clampedHigh, 0.001f)
        } finally {
            com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(app, 0.78f)
            WakeWordVoiceService.customWakeWordOverrideActive = false
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun screenOffBatterySaver_reducesPollingFrequencyWhenScreenOff_andGlowingNotificationIconVerifies() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Default: Screen interactive (on) and Screen-Off Battery Saver disabled -> 60ms polling
            WakeWordVoiceService.setDeviceScreenInteractiveState(true)
            viewModel.saveScreenOffBatterySaverToDataStoreSuspend(false)
            assertEquals(
                WakeWordVoiceService.ACTIVE_SCREEN_POLLING_INTERVAL_MS,
                WakeWordVoiceService.resolveEffectivePollingIntervalMs()
            )
            assertEquals(
                WakeWordVoiceService.SPEECH_RECOGNIZER_RESTART_DELAY_MS,
                WakeWordVoiceService.resolveEffectiveSpeechRecognizerRestartDelayMs()
            )

            // 2. Enable Screen-Off Battery Saver in Settings (persisted to DataStore & SharedPreferences)
            val savedToggle = viewModel.saveScreenOffBatterySaverToDataStoreSuspend(true)
            assertTrue(savedToggle)
            assertTrue(com.example.util.WakeWordDataStoreManager.getScreenOffBatterySaver(app))
            assertTrue(com.example.util.AppSettingsManager.isScreenOffBatterySaverEnabled(app))
            assertTrue(WakeWordVoiceService.screenOffBatterySaverEnabled.value)

            // While screen is still ON, polling remains fast (60ms)
            assertEquals(
                60L,
                WakeWordVoiceService.resolveEffectivePollingIntervalMs()
            )

            // 3. Turn screen OFF -> polling interval reduces frequency to 450ms & SR restart delay lengthens to 4500ms
            WakeWordVoiceService.setDeviceScreenInteractiveState(false)
            assertEquals(
                WakeWordVoiceService.SCREEN_OFF_BATTERY_SAVER_POLLING_INTERVAL_MS,
                WakeWordVoiceService.resolveEffectivePollingIntervalMs()
            )
            assertEquals(
                450L,
                WakeWordVoiceService.activeWakeWordPollingIntervalMs.value
            )
            assertEquals(
                WakeWordVoiceService.SCREEN_OFF_SPEECH_RECOGNIZER_RESTART_DELAY_MS,
                WakeWordVoiceService.resolveEffectiveSpeechRecognizerRestartDelayMs()
            )

            // 4. Turn screen back ON -> immediately restores 60ms active polling
            WakeWordVoiceService.setDeviceScreenInteractiveState(true)
            assertEquals(
                WakeWordVoiceService.ACTIVE_SCREEN_POLLING_INTERVAL_MS,
                WakeWordVoiceService.activeWakeWordPollingIntervalMs.value
            )

            // 5. Verify subtle glowing notification icon resource when actively listening vs standby
            assertEquals(
                com.example.R.drawable.ic_notification_wake_glowing,
                WakeWordVoiceService.resolveNotificationSmallIconRes(isActivelyListening = true)
            )
            assertEquals(
                com.example.R.drawable.ic_notification_wake_standby,
                WakeWordVoiceService.resolveNotificationSmallIconRes(isActivelyListening = false)
            )
        } finally {
            WakeWordVoiceService.setDeviceScreenInteractiveState(true)
            com.example.util.WakeWordDataStoreManager.saveScreenOffBatterySaver(app, false)
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun microphoneManifestPermissions_andRuntimeRequestFlow_enablesVoiceListeningWhenGranted() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // 1. Verify AndroidManifest.xml declares required microphone & foreground service permissions
        // and adheres to Google Play policy by NOT declaring prohibited broad storage permissions.
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestXml = manifestFile!!.readText()
        assertTrue(
            "AndroidManifest.xml must declare RECORD_AUDIO permission",
            manifestXml.contains("android.permission.RECORD_AUDIO")
        )
        assertTrue(
            "AndroidManifest.xml must declare MODIFY_AUDIO_SETTINGS permission",
            manifestXml.contains("android.permission.MODIFY_AUDIO_SETTINGS")
        )
        assertTrue(
            "AndroidManifest.xml must declare FOREGROUND_SERVICE permission",
            manifestXml.contains("android.permission.FOREGROUND_SERVICE")
        )
        assertTrue(
            "AndroidManifest.xml must declare FOREGROUND_SERVICE_MICROPHONE permission",
            manifestXml.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE")
        )
        assertFalse(
            "AndroidManifest.xml must NOT declare prohibited READ_EXTERNAL_STORAGE",
            manifestXml.contains("android.permission.READ_EXTERNAL_STORAGE")
        )
        assertFalse(
            "AndroidManifest.xml must NOT declare prohibited WRITE_EXTERNAL_STORAGE",
            manifestXml.contains("android.permission.WRITE_EXTERNAL_STORAGE")
        )
        assertFalse(
            "AndroidManifest.xml must NOT declare prohibited MANAGE_EXTERNAL_STORAGE",
            manifestXml.contains("android.permission.MANAGE_EXTERNAL_STORAGE")
        )

        // 2. Simulate runtime state where RECORD_AUDIO is initially denied
        shadowApp.denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val viewModel = com.example.ui.MainViewModel(app)
        try {
            assertFalse(
                "checkRecordAudioPermission() must return false when RECORD_AUDIO is denied",
                viewModel.checkRecordAudioPermission()
            )
            assertFalse(
                "ensureMicrophonePermission() must return false and emit requiresAudioPermission when denied",
                viewModel.ensureMicrophonePermission(startWakeListeningWhenGranted = true)
            )
            assertTrue(
                "requiresAudioPermission state must be true so UI launches runtime permission flow",
                viewModel.requiresAudioPermission.value
            )

            // 3. Consume permission request as MainActivity does before launching RequestPermission
            viewModel.consumeAudioPermissionRequest()
            assertFalse(viewModel.requiresAudioPermission.value)

            // 4. Simulate user denying the runtime dialog -> rationale state is surfaced
            viewModel.onAudioPermissionResult(granted = false)
            assertFalse(viewModel.hasRecordAudioPermission.value)
            assertTrue(
                "Status message must guide user to enable Microphone permission",
                viewModel.wakeWordManager.statusMessage.value.contains("Microphone permission", ignoreCase = true)
            )

            // 5. Simulate user granting RECORD_AUDIO at runtime -> voice listening is enabled
            shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)
            assertTrue(viewModel.ensureMicrophonePermission(startWakeListeningWhenGranted = true))
            viewModel.onAudioPermissionResult(granted = true)
            assertTrue(
                "hasRecordAudioPermission must be true after runtime grant",
                viewModel.hasRecordAudioPermission.value
            )
            assertFalse(
                "requiresAudioPermission must be false after runtime grant",
                viewModel.requiresAudioPermission.value
            )
        } finally {
            shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun firestoreWakeChatMultiSync_andLocalKeywordSpotter_selectsTargetAndScoresWakeWord() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Seed two Firestore Gemini chat sessions
            val chatDocAlpha = com.example.data.firebase.FirestoreChatDoc(
                id = "chat_101",
                localChatId = 101L,
                title = "Alpha Architecture Review",
                wakeWord = "Hey Alpha",
                isDefaultWakeChat = true,
                isPersistent = true
            )
            val chatDocNebula = com.example.data.firebase.FirestoreChatDoc(
                id = "chat_202",
                localChatId = 202L,
                title = "Nebula Deep Research",
                wakeWord = "Hey Nebula",
                isDefaultWakeChat = false,
                isPersistent = true
            )
            viewModel.seedCloudBackupStateForTest(
                chats = listOf(chatDocAlpha, chatDocNebula)
            )

            // 2. Select the second Firestore chat session as the active wake-word target with Multi-Sync
            val syncedChatId = viewModel.selectFirestoreChatAsWakeTargetSuspend(
                chatDoc = chatDocNebula,
                multiSyncToCloud = true
            )
            assertTrue("Selected Firestore chat must be synced into local Room with a valid ID", syncedChatId > 0L)

            // Verify cloudBackupChats state marks only Nebula as default wake target
            val updatedCloudChats = viewModel.cloudBackupChats.value
            val alphaAfter = updatedCloudChats.first { it.id == "chat_101" }
            val nebulaAfter = updatedCloudChats.first { it.id == "chat_202" }
            assertFalse("Previous Firestore default wake chat must be demoted", alphaAfter.isDefaultWakeChat)
            assertTrue("Selected Firestore wake chat must be marked isDefaultWakeChat = true", nebulaAfter.isDefaultWakeChat)

            // Verify DataStore & WakeWordVoiceService in-memory states are updated
            org.junit.Assert.assertEquals(
                "WakeWordVoiceService target chat ID must match selected Firestore session",
                syncedChatId,
                WakeWordVoiceService.customWakeWordTargetChatId
            )
            org.junit.Assert.assertEquals(
                "WakeWordDataStoreManager selectedWakeTargetChatIdState must match selected session",
                syncedChatId,
                com.example.util.WakeWordDataStoreManager.selectedWakeTargetChatIdState.value
            )

            // 3. Verify LocalKeywordSpotterEngine (VoiceService hybrid keyword spotter)
            LocalKeywordSpotterEngine.syncConfiguration(
                customWakePhrase = "Hey Nebula",
                sensitivityThreshold = 0.78f
            )
            LocalKeywordSpotterEngine.resetPeakConfidence()

            // Feed a synthesized 16 kHz speech-like PCM frame to prime acoustic VAD confidence
            val pcmBytes = WakeWordVoiceService.synthesizeDiagnosticPcmFrame()
            val frameEval = LocalKeywordSpotterEngine.evaluatePcmAudioFrame(
                pcmBytes = pcmBytes,
                bytesRead = pcmBytes.size,
                customWakePhrase = "Hey Nebula",
                sensitivityThreshold = 0.78f
            )
            assertTrue("Acoustic energy score must be positive on speech frame", frameEval.acousticEnergyScore > 0.35f)

            // Evaluate matching wake word phrase against threshold 0.78f
            val matchTelemetry = LocalKeywordSpotterEngine.evaluateCandidateTranscript(
                transcript = "hey nebula open session",
                customWakePhrase = "Hey Nebula",
                sensitivityThreshold = 0.78f,
                recentPcmFrame = pcmBytes
            )
            assertTrue(
                "Matching wake phrase must exceed 0.78 sensitivity threshold (was ${matchTelemetry.combinedSpotterConfidence})",
                matchTelemetry.isAboveThreshold
            )
            assertTrue(
                "Combined spotter confidence must be >= 0.78f",
                matchTelemetry.combinedSpotterConfidence >= 0.78f
            )

            // Evaluate non-matching phrase against strict threshold 0.88f
            val nonMatchTelemetry = LocalKeywordSpotterEngine.evaluateCandidateTranscript(
                transcript = "what is the weather outside today",
                customWakePhrase = "Hey Nebula",
                sensitivityThreshold = 0.88f
            )
            assertFalse(
                "Unrelated transcript must not exceed wake-word sensitivity threshold",
                nonMatchTelemetry.isAboveThreshold
            )
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun wakeWordActivationOverlay_andCustomWakeWordSuggestions_ranksWorkAndFamilyContextAndOpensPreselectedChat() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        val viewModel = com.example.ui.MainViewModel(app)
        try {
            // 1. Verify common wake word suggestions & automatic fallback when user does not provide one
            assertTrue(
                "COMMON_WAKE_WORD_SUGGESTIONS must include common wake phrases",
                WakeWordRoutingEngine.COMMON_WAKE_WORD_SUGGESTIONS.containsAll(
                    listOf("Hey Gemini", "OK Gemini", "Hey Nebula", "Computer")
                )
            )
            val fallbackWhenBlank = WakeWordRoutingEngine.resolveWakeWordOrSuggestedFallback(
                inputPhrase = "   ",
                preferredSuggestion = "Hey Nebula"
            )
            org.junit.Assert.assertEquals("Hey Nebula", fallbackWhenBlank)
            val defaultFallbackWhenBlank = WakeWordRoutingEngine.resolveWakeWordOrSuggestedFallback(
                inputPhrase = "",
                preferredSuggestion = null
            )
            org.junit.Assert.assertEquals("Hey Gemini", defaultFallbackWhenBlank)

            // 2. Verify contextual chat suggestions during Business Hours vs. Evening
            val workChat = com.example.data.model.ChatSession(
                id = 301L,
                title = "Work Chat",
                wakeWord = "Hey Work",
                isDefaultWakeChat = false,
                lastUpdatedAt = 1_700_000_000L
            )
            val familyChat = com.example.data.model.ChatSession(
                id = 302L,
                title = "Family Chat",
                wakeWord = "Hey Family",
                isDefaultWakeChat = false,
                lastUpdatedAt = 1_700_000_000L
            )
            val defaultPreselectedChat = com.example.data.model.ChatSession(
                id = 303L,
                title = "Pre-Selected Assistant",
                wakeWord = "Hey Nebula",
                isDefaultWakeChat = true,
                lastUpdatedAt = 1_700_000_000L
            )
            val testChats = listOf(workChat, familyChat, defaultPreselectedChat)

            // Business hours: Wednesday 10:30 AM local time
            val businessCal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.WEDNESDAY)
                set(java.util.Calendar.HOUR_OF_DAY, 10)
                set(java.util.Calendar.MINUTE, 30)
            }
            val businessSuggestions = WakeWordRoutingEngine.rankContextualChatSuggestions(
                chats = testChats,
                nowMillis = businessCal.timeInMillis
            )
            org.junit.Assert.assertEquals(
                "Work Chat must rank #1 during business hours",
                "Work Chat",
                businessSuggestions.first().chat.title
            )
            assertTrue(
                "Business hours badge must highlight Work context",
                businessSuggestions.first().contextBadge.contains("Work", ignoreCase = true)
            )

            // Evening hours: Wednesday 8:15 PM local time
            val eveningCal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.WEDNESDAY)
                set(java.util.Calendar.HOUR_OF_DAY, 20)
                set(java.util.Calendar.MINUTE, 15)
            }
            val eveningSuggestions = WakeWordRoutingEngine.rankContextualChatSuggestions(
                chats = testChats,
                nowMillis = eveningCal.timeInMillis
            )
            org.junit.Assert.assertEquals(
                "Family Chat must rank #1 in the evening",
                "Family Chat",
                eveningSuggestions.first().chat.title
            )
            assertTrue(
                "Evening badge must highlight Family context",
                eveningSuggestions.first().contextBadge.contains("Family", ignoreCase = true)
            )

            // 3. Save custom wake word (with blank input falling back to suggestion) bound to a pre-selected chat
            val savedPhrase = viewModel.saveCustomWakeWordToPrefs(
                phrase = "",
                targetChatId = 303L,
                preferredFallbackSuggestion = "Hey Nebula"
            )
            org.junit.Assert.assertEquals("Hey Nebula", savedPhrase)
            org.junit.Assert.assertEquals(
                "Custom wake word must bind to pre-selected target chat ID",
                303L,
                WakeWordVoiceService.customWakeWordTargetChatId
            )

            // 4. Trigger wake-word activation overlay and verify contextual suggestions + pre-selected chat handoff
            val overlayState = viewModel.triggerWakeActivationWithContextualOverlay(
                detectedPhrase = "Hey Nebula",
                explicitTargetChatId = 303L,
                nowMillis = businessCal.timeInMillis
            )
            org.junit.Assert.assertNotNull(
                "pendingWakeActivationOverlay must be populated before opening chat",
                viewModel.pendingWakeActivationOverlay.value
            )
            org.junit.Assert.assertEquals("Hey Nebula", overlayState.detectedWakePhrase)

            // Confirm selecting a chat from the overlay clears the overlay and selects that chat
            viewModel.confirmWakeActivationChatSelection(workChat)
            org.junit.Assert.assertNull(
                "pendingWakeActivationOverlay must clear after user selects a chat or proceeds",
                viewModel.pendingWakeActivationOverlay.value
            )
            org.junit.Assert.assertEquals(
                "currentChatId must update to the selected chat from the overlay",
                workChat.id,
                viewModel.currentChatId.value
            )

            // 5. Verify spoken chat-switching & chat-opening commands ("Hey Gemini, switch to Family Chat", "open my work chat")
            val learningChat = com.example.data.model.ChatSession(
                id = 304L,
                title = "Kotlin Learning Lab",
                wakeWord = "Hey Study",
                isDefaultWakeChat = false,
                lastUpdatedAt = 1_700_000_000L
            )
            val allTestChats = listOf(workChat, familyChat, defaultPreselectedChat, learningChat)
            val categories = mapOf(
                301L to "Work",
                302L to "Personal",
                303L to "Work",
                304L to "Learning"
            )
            val priorities = mapOf(
                301L to com.example.util.ChatPriorityLevel.HIGH,
                302L to com.example.util.ChatPriorityLevel.NORMAL,
                303L to com.example.util.ChatPriorityLevel.LOW,
                304L to com.example.util.ChatPriorityLevel.CRITICAL
            )

            val switchedFamily = WakeWordRoutingEngine.parseSpokenChatSwitchOrOpenCommand(
                rawTranscript = "Hey Gemini, switch to Family Chat",
                chats = allTestChats,
                chatCategoryMap = categories,
                chatPriorityMap = priorities
            )
            org.junit.Assert.assertNotNull("Spoken 'switch to Family Chat' must resolve Family Chat", switchedFamily)
            org.junit.Assert.assertEquals("Family Chat", switchedFamily!!.title)

            val openedWorkByCategory = WakeWordRoutingEngine.parseSpokenChatSwitchOrOpenCommand(
                rawTranscript = "Hey Gemini, open my work chat",
                chats = allTestChats,
                chatCategoryMap = categories,
                chatPriorityMap = priorities
            )
            org.junit.Assert.assertNotNull("Spoken 'open my work chat' must resolve highest-priority Work chat", openedWorkByCategory)
            org.junit.Assert.assertEquals("Work Chat", openedWorkByCategory!!.title)

            // 6. Verify 4-tier priority level + Campus/Learning location profile ranks Critical Learning chat #1
            val learningSuggestions = WakeWordRoutingEngine.rankContextualChatSuggestions(
                chats = allTestChats,
                chatCategoryMap = categories,
                chatPriorityMap = priorities,
                locationProfile = com.example.util.ContextLocationProfile.LEARNING,
                nowMillis = eveningCal.timeInMillis
            )
            org.junit.Assert.assertEquals(
                "Critical priority Learning chat must rank #1 in LEARNING location context",
                "Kotlin Learning Lab",
                learningSuggestions.first().chat.title
            )

            // 7. Verify Quiet Mode disables wake word detection and resumes cleanly when toggled off
            viewModel.setQuietModeEnabled(true)
            assertTrue("Quiet Mode state must be enabled", viewModel.quietModeEnabled.value)
            assertTrue("WakeWordVoiceService global Quiet Mode flag must be true", WakeWordVoiceService.isQuietModeGlobal.value)
            viewModel.toggleQuietMode()
            assertFalse("Toggling Quiet Mode must re-enable wake word detection", viewModel.quietModeEnabled.value)
            assertFalse("WakeWordVoiceService global Quiet Mode flag must be false", WakeWordVoiceService.isQuietModeGlobal.value)

            // 8. Verify Configurable Context-to-Chat Trigger Rules & Category Wake Word routing
            viewModel.setContextTriggerRule(
                contextType = com.example.util.ContextTriggerType.WORK_HOURS,
                targetChatId = 301L,
                autoOpenDirectly = true
            )
            val contextRuleDecision = WakeWordRoutingEngine.resolveTargetChatForWakeWord(
                spokenWakePhrase = "Hey Gemini",
                chats = allTestChats,
                configuredWakeWords = emptyList(),
                chatCategoryMap = categories,
                chatPriorityMap = priorities,
                locationProfile = com.example.util.ContextLocationProfile.WORK,
                contextTriggerRules = viewModel.contextTriggerRules.value,
                categoryWakeWordMap = viewModel.categoryWakeWordMap.value,
                wakeWordActionMap = viewModel.wakeWordActionMap.value,
                nowMillis = businessCal.timeInMillis
            )
            org.junit.Assert.assertNotNull(contextRuleDecision)
            org.junit.Assert.assertEquals("Work Chat", contextRuleDecision!!.targetChat.title)
            assertTrue("Context trigger rule with autoOpenDirectly=true must set shouldAutoOpenDirectly", contextRuleDecision.shouldAutoOpenDirectly)

            // Category wake word assignment ("Study Mode" -> Learning category -> highest priority Learning chat)
            viewModel.setCategoryWakeWord("Learning", "Study Mode")
            viewModel.setWakeWordAction("Study Mode", com.example.util.WakeWordActionType.SUMMARIZE_RECENT)
            val categoryWakeDecision = WakeWordRoutingEngine.resolveTargetChatForWakeWord(
                spokenWakePhrase = "Study Mode",
                chats = allTestChats,
                configuredWakeWords = emptyList(),
                chatCategoryMap = categories,
                chatPriorityMap = priorities,
                locationProfile = com.example.util.ContextLocationProfile.AUTO_DETECT,
                contextTriggerRules = emptyMap(),
                categoryWakeWordMap = viewModel.categoryWakeWordMap.value,
                wakeWordActionMap = viewModel.wakeWordActionMap.value,
                nowMillis = eveningCal.timeInMillis
            )
            org.junit.Assert.assertNotNull(categoryWakeDecision)
            org.junit.Assert.assertEquals("Kotlin Learning Lab", categoryWakeDecision!!.targetChat.title)
            org.junit.Assert.assertEquals(
                com.example.util.WakeWordActionType.SUMMARIZE_RECENT,
                categoryWakeDecision.matchedActionType
            )

            // 9. Verify Cross-Chat History Search returns highlighted conversation snippets
            val sampleMessages = listOf(
                com.example.data.model.ChatMessage(
                    id = 9001L,
                    chatId = 301L,
                    sender = "user",
                    content = "We finalized the Q4 architecture roadmap and API latency budget.",
                    timestamp = 1_700_000_500L
                ),
                com.example.data.model.ChatMessage(
                    id = 9002L,
                    chatId = 304L,
                    sender = "gemini",
                    content = "Kotlin coroutines and Flow simplify asynchronous architecture patterns.",
                    timestamp = 1_700_000_900L
                )
            )
            val snippets = WakeWordRoutingEngine.searchAcrossChatHistories(
                rawQuery = "architecture",
                chats = allTestChats,
                messages = sampleMessages,
                chatCategoryMap = categories
            )
            org.junit.Assert.assertEquals("Search for 'architecture' must match 2 messages across chats", 2, snippets.size)
            assertTrue(
                "Matched snippet must contain valid highlight range for 'architecture'",
                snippets.first().matchStartInSnippet >= 0 && snippets.first().matchEndInSnippet > snippets.first().matchStartInSnippet
            )
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun playerPresence_roomAndFirestoreSync_stealthModeEnforcesOfflineAndHidesChatTitleAndTimer() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        com.example.game.PlayerPresenceCoordinator.resetForTesting(nowEpochMs = 1_700_000_000_000L)
        com.example.util.AppSettingsManager.setStealthModeEnabled(app, false)

        val viewModel = com.example.ui.MainViewModel(app)
        try {
            val baseEpochMs = 1_700_000_000_000L
            val currentElapsedEpochMs = baseEpochMs + (95L * 60_000L) // 1h 35m later

            // 1. Verify active online PlayerPresence syncs local level, completed quests, chat title, and elapsed time
            val onlinePresence = com.example.game.PlayerPresence(
                odinUid = "odin_hero_01",
                displayName = "Valkyrie Prime",
                level = 7,
                completedQuestCount = 14,
                currentActiveChatTitle = "Shattered Spire Campaign",
                isOnline = true,
                isStealthMode = false,
                sessionStartedEpochMs = baseEpochMs,
                lastHeartbeatEpochMs = currentElapsedEpochMs - 15_000L // 15s ago (within 2m window)
            )
            assertTrue("Player with fresh heartbeat must be effectively online", onlinePresence.isEffectivelyOnline(currentElapsedEpochMs))
            org.junit.Assert.assertEquals("In: Shattered Spire Campaign", onlinePresence.formattedChatContext(currentElapsedEpochMs))
            org.junit.Assert.assertEquals("Online for 1h 35m", onlinePresence.formattedElapsedSessionTime(currentElapsedEpochMs))

            // 2. Verify 2-minute (120s) stale heartbeat timeout transitions player to effectively offline
            val staleEpochMs = currentElapsedEpochMs + 130_000L // > 120s without heartbeat
            assertFalse("Player whose heartbeat is older than 2 minutes must be marked offline", onlinePresence.isEffectivelyOnline(staleEpochMs))
            org.junit.Assert.assertEquals("In: Offline", onlinePresence.formattedChatContext(staleEpochMs))

            // 3. Verify Room persistence & Stealth Mode ("Always Offline") privacy enforcement
            val syncedOnline = viewModel.syncAdventurePlayerPresenceSuspend(
                overrideStealthMode = false,
                nowEpochMs = currentElapsedEpochMs
            )
            assertTrue("When Stealth Mode is false, local presence must be online", syncedOnline.isOnline)
            assertFalse("When Stealth Mode is false, isStealthMode must be false", syncedOnline.isStealthMode)

            // Enable Stealth Mode ("Always Offline") via MainViewModel / AppSettingsManager
            viewModel.setAdventureStealthMode(true)
            assertTrue(
                "AppSettingsManager must persist isStealthMode = true",
                com.example.util.AppSettingsManager.isStealthModeEnabled(app)
            )

            val stealthPresence = viewModel.syncAdventurePlayerPresenceSuspend(
                overrideStealthMode = true,
                nowEpochMs = currentElapsedEpochMs
            )
            assertTrue("Stealth presence must record isStealthMode = true", stealthPresence.isStealthMode)
            assertFalse("Stealth Mode must force isOnline = false", stealthPresence.isOnline)
            org.junit.Assert.assertEquals(
                "Stealth Mode must force currentActiveChatTitle = 'Offline'",
                "Offline",
                stealthPresence.currentActiveChatTitle
            )
            org.junit.Assert.assertEquals(
                "Stealth Mode must zero out sessionStartedEpochMs to ignore live session timers",
                0L,
                stealthPresence.sessionStartedEpochMs
            )

            // Verify Firestore payload map also enforces isOnline = false and currentActiveChatTitle = "Offline"
            val firestoreMap = stealthPresence.toFirestoreMap()
            org.junit.Assert.assertEquals(false, firestoreMap["isOnline"])
            org.junit.Assert.assertEquals("Offline", firestoreMap["currentActiveChatTitle"])
            org.junit.Assert.assertEquals(0L, firestoreMap["sessionStartedEpochMs"])

            // Verify Room cache row matches the stealth-sanitized state
            val cachedInRoom = com.example.data.db.AppDatabase.getDatabase(app)
                .adventureGameDao()
                .getPlayerPresenceByUidSync(stealthPresence.odinUid)
            org.junit.Assert.assertNotNull("PlayerPresence must be persisted in Room player_presence_cache", cachedInRoom)
            org.junit.Assert.assertEquals(false, cachedInRoom!!.isOnline)
            org.junit.Assert.assertEquals("Offline", cachedInRoom.currentActiveChatTitle)

            // 4. Verify FriendPresenceCard eye toggle helper (toggleStealthModeFromUi & setStealthModeAndForceSync)
            // First reset to Online (Stealth = false)
            val restoredOnline = com.example.game.PlayerPresenceCoordinator.setStealthModeAndForceSync(
                context = app,
                enabled = false,
                basePresence = onlinePresence,
                nowEpochMs = currentElapsedEpochMs
            )
            assertFalse("Stealth Mode must be false after disabling", com.example.util.AppSettingsManager.isStealthModeEnabled(app))
            assertTrue("Player must be online when Stealth Mode is disabled", restoredOnline.isOnline)
            org.junit.Assert.assertEquals("Shattered Spire Campaign", restoredOnline.currentActiveChatTitle)

            // Now invoke toggleStealthModeFromUi (the exact handler wired to FriendPresenceCard's eye icon)
            var callbackStealthState: Boolean? = null
            val toggledToStealth = com.example.game.PlayerPresenceCoordinator.toggleStealthModeFromUi(
                context = app,
                currentPresence = restoredOnline,
                nowEpochMs = currentElapsedEpochMs + 5_000L,
                onStealthChanged = { callbackStealthState = it }
            )
            org.junit.Assert.assertEquals(true, callbackStealthState)
            assertTrue(
                "Toggling eye icon must immediately update AppSettingsManager.isStealthMode to true",
                com.example.util.AppSettingsManager.isStealthMode.value
            )
            assertTrue("Returned presence must have isStealthMode = true", toggledToStealth.isStealthMode)
            assertFalse("Toggling Stealth Mode on must force isOnline = false", toggledToStealth.isOnline)
            org.junit.Assert.assertEquals("Offline", toggledToStealth.currentActiveChatTitle)

            val latestFirestorePayload = com.example.game.PlayerPresenceCoordinator.lastSyncedFirestorePayload.value
            org.junit.Assert.assertNotNull("Firestore payload must be immediately prepared on eye icon toggle", latestFirestorePayload)
            org.junit.Assert.assertEquals(true, latestFirestorePayload!!["isStealthMode"])
            org.junit.Assert.assertEquals(false, latestFirestorePayload["isOnline"])
            org.junit.Assert.assertEquals("Offline", latestFirestorePayload["currentActiveChatTitle"])
        } finally {
            com.example.util.AppSettingsManager.setStealthModeEnabled(app, false)
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun presenceService_workManagerHeartbeatPingAnd2MinuteOfflineCleanup_updatesStatusAndCleansStalePeers() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.example.util.AppSettingsManager.setStealthModeEnabled(app, false)
        val t0 = 1_700_500_000_000L
        com.example.game.PlayerPresenceCoordinator.resetForTesting(nowEpochMs = t0)

        val advDao = com.example.data.db.AppDatabase.getDatabase(app).adventureGameDao()

        // Seed a peer whose heartbeat is already 150s old (> 2-minute timeout)
        val stalePeer = com.example.game.PlayerPresence(
            odinUid = "peer_stale_99",
            displayName = "AFK Ranger",
            level = 4,
            completedQuestCount = 6,
            currentActiveChatTitle = "Dungeon Delve",
            isOnline = true,
            isStealthMode = false,
            sessionStartedEpochMs = t0 - 300_000L,
            lastHeartbeatEpochMs = t0 - 150_000L
        )
        advDao.upsertPlayerPresence(stalePeer)

        // 1. Execute PresenceService in MODE_HEARTBEAT_PING at t1 = t0 + 30_000L via TestListenableWorkerBuilder
        val t1 = t0 + com.example.game.PresenceService.HEARTBEAT_INTERVAL_MS
        val heartbeatInput = androidx.work.Data.Builder()
            .putString(com.example.game.PresenceService.KEY_WORK_MODE, com.example.game.PresenceService.MODE_HEARTBEAT_PING)
            .putBoolean(com.example.game.PresenceService.KEY_CHAIN_NEXT_HEARTBEAT, false)
            .putLong(com.example.game.PresenceService.KEY_OVERRIDE_NOW_EPOCH_MS, t1)
            .build()

        val heartbeatWorker = androidx.work.testing.TestListenableWorkerBuilder<com.example.game.PresenceService>(app)
            .setInputData(heartbeatInput)
            .build()
        val heartbeatResult = heartbeatWorker.doWork()
        org.junit.Assert.assertEquals(androidx.work.ListenableWorker.Result.success(), heartbeatResult)
        org.junit.Assert.assertEquals(t1, com.example.game.PresenceService.lastHeartbeatPingEpochMs.value)

        val localUid = com.example.game.PlayerPresenceCoordinator.resolveCurrentOdinUid(app)
        val localAfterPing = advDao.getPlayerPresenceByUidSync(localUid)
        org.junit.Assert.assertNotNull("Heartbeat ping must persist local player presence", localAfterPing)
        assertTrue("Local player must be online after 30s heartbeat ping", localAfterPing!!.isOnline)
        org.junit.Assert.assertEquals("lastHeartbeatEpochMs must equal ping timestamp t1", t1, localAfterPing.lastHeartbeatEpochMs)

        // Verify stale peer (>2m old) was cleaned up to Offline during heartbeat sweep
        val stalePeerAfterPing = advDao.getPlayerPresenceByUidSync("peer_stale_99")
        org.junit.Assert.assertNotNull(stalePeerAfterPing)
        assertFalse("Peer older than 2 minutes must be marked isOnline = false", stalePeerAfterPing!!.isOnline)
        org.junit.Assert.assertEquals("Offline", stalePeerAfterPing.currentActiveChatTitle)

        // 2. Execute PresenceService in MODE_OFFLINE_CLEANUP (simulating 2-minute background timeout at t2 = t1 + 120_000L)
        val t2 = t1 + com.example.game.PresenceService.OFFLINE_CLEANUP_TIMEOUT_MS
        val cleanupInput = androidx.work.Data.Builder()
            .putString(com.example.game.PresenceService.KEY_WORK_MODE, com.example.game.PresenceService.MODE_OFFLINE_CLEANUP)
            .putBoolean(com.example.game.PresenceService.KEY_CHAIN_NEXT_HEARTBEAT, false)
            .putLong(com.example.game.PresenceService.KEY_OVERRIDE_NOW_EPOCH_MS, t2)
            .build()

        val cleanupWorker = androidx.work.testing.TestListenableWorkerBuilder<com.example.game.PresenceService>(app)
            .setInputData(cleanupInput)
            .build()
        val cleanupResult = cleanupWorker.doWork()
        org.junit.Assert.assertEquals(androidx.work.ListenableWorker.Result.success(), cleanupResult)
        org.junit.Assert.assertEquals(t2, com.example.game.PresenceService.lastOfflineCleanupEpochMs.value)

        val localAfterCleanup = advDao.getPlayerPresenceByUidSync(localUid)
        org.junit.Assert.assertNotNull(localAfterCleanup)
        assertFalse("2-minute background offline cleanup must set local player isOnline = false", localAfterCleanup!!.isOnline)
        org.junit.Assert.assertEquals("Offline", localAfterCleanup.currentActiveChatTitle)
        org.junit.Assert.assertEquals(0L, localAfterCleanup.sessionStartedEpochMs)
    }
}



