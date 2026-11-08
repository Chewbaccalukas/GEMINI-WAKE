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
}

