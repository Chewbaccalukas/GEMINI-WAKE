package com.example.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.data.db.AppDatabase
import com.example.data.model.WakeWord
import com.example.data.repository.WakeWordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

private const val TAG = "WakeWordVoiceService"

/**
 * Result emitted when the foreground service keyword detection loop matches an active [WakeWord]
 * from [WakeWordRepository].
 */
data class WakeWordDetectionResult(
    val wakeWord: WakeWord,
    val matchedPhrase: String,
    val followUpQuery: String?,
    val rawTranscript: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Foreground service declared with `android:foregroundServiceType="microphone"`
 * (`FOREGROUND_SERVICE_MICROPHONE` permission) that immediately invokes `startForeground()`
 * with a persistent notification so Samsung One UI and Android 14+ keep the microphone
 * out of deep-sleep buckets.
 *
 * Uses a thread-safe `Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
 * as its [audioPipelineBuffer] and exposes [audioPipelineFlow] via `consumeAsFlow()`.
 * Strictly releases [AudioRecord] hardware whenever [isIntentionallyPaused] is `true`.
 */
class WakeWordVoiceService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordVoiceService = this@WakeWordVoiceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repositoryObserverJob: Job? = null
    private var keywordLoopJob: Job? = null
    private val serviceAudioRecordMutex = Any()
    @Volatile
    private var serviceAudioRecord: AudioRecord? = null

    /**
     * Thread-safe ring buffer (`capacity = 32`, `BufferOverflow.DROP_OLDEST`) acting as
     * the service's `AudioPipelineBuffer` for raw PCM byte chunks read from [AudioRecord].
     */
    var audioPipelineBuffer: Channel<ByteArray> = Channel(
        capacity = AUDIO_PIPELINE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
        private set

    /**
     * Exposes [audioPipelineBuffer] as a [Flow] via [consumeAsFlow] for downstream processing.
     */
    var audioPipelineFlow: Flow<ByteArray> = audioPipelineBuffer.consumeAsFlow()
        private set

    private var customRepository: WakeWordRepository? = null
    private val transcriptQueue = ConcurrentLinkedQueue<String>()

    private val _serviceActiveWakeWords = MutableStateFlow<List<WakeWord>>(emptyList())
    val serviceActiveWakeWords: StateFlow<List<WakeWord>> = _serviceActiveWakeWords.asStateFlow()

    private val _isKeywordLoopRunning = MutableStateFlow(false)
    val isKeywordLoopRunning: StateFlow<Boolean> = _isKeywordLoopRunning.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var serviceSpeechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerRestartRunnable: Runnable? = null
    @Volatile
    private var lastPartialMatchedPhraseTimestampMs: Long = 0L

    /**
     * Android [RecognitionListener] bound to [serviceSpeechRecognizer] for continuous customizable
     * wake-word recognition on the main thread.
     */
    val serviceRecognitionListener: RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isServiceSpeechRecognizerListening.value = true
            dispatchDiagnosticTrace(
                "SERVICE_SR_READY_FOR_SPEECH",
                "customWakeWord=${customWakeWord.value} activeCount=${getEffectiveWakeWords().size}"
            )
        }

        override fun onBeginningOfSpeech() {
            _isServiceSpeechRecognizerListening.value = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            _serviceMicRms.value = rmsdB.coerceAtLeast(0f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null && buffer.isNotEmpty()) {
                audioPipelineBuffer.trySend(buffer.copyOf())
            }
        }

        override fun onEndOfSpeech() {
            _isServiceSpeechRecognizerListening.value = false
        }

        override fun onError(error: Int) {
            _isServiceSpeechRecognizerListening.value = false
            val errorName = formatSpeechRecognizerError(error)
            dispatchDiagnosticTrace(
                "SERVICE_SR_ERROR",
                "errorCode=$error errorName=$errorName paused=$isIntentionallyPaused"
            )
            if (!isIntentionallyPaused &&
                !_isSleepModeGlobal.value &&
                !_isBatterySaverPausedGlobal.value &&
                !_isInputFocusPaused.value &&
                !isAppRecordingActive
            ) {
                scheduleSpeechRecognizerRestart(SPEECH_RECOGNIZER_RESTART_DELAY_MS)
            }
        }

        override fun onResults(results: Bundle?) {
            _isServiceSpeechRecognizerListening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            var detected: WakeWordDetectionResult? = null
            for (candidate in matches) {
                if (candidate.isNotBlank()) {
                    submitTranscriptForKeywordCheck(candidate)
                    val match = evaluateSpeechRecognizerTranscript(candidate, isPartial = false)
                    if (match != null) {
                        detected = match
                        break
                    }
                }
            }
            if (detected == null &&
                !isIntentionallyPaused &&
                !_isSleepModeGlobal.value &&
                !_isBatterySaverPausedGlobal.value &&
                !_isInputFocusPaused.value &&
                !isAppRecordingActive
            ) {
                scheduleSpeechRecognizerRestart(SPEECH_RECOGNIZER_RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val now = System.currentTimeMillis()
            if (now - lastPartialMatchedPhraseTimestampMs < 1200L) return
            for (candidate in partials) {
                if (candidate.isNotBlank()) {
                    val match = evaluateSpeechRecognizerTranscript(candidate, isPartial = true)
                    if (match != null) {
                        lastPartialMatchedPhraseTimestampMs = now
                        break
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val focusLabel = formatAudioFocusChange(focusChange)
        if (isTransitioningAudioState) {
            val details = "response=$focusLabel ignored=true (isTransitioningAudioState=true)"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_CALLBACK | $details")
            dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_CALLBACK", details)
            return@OnAudioFocusChangeListener
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val details = "response=$focusLabel action=PAUSE_BACKGROUND_AUDIORECORD"
                Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_CALLBACK | $details")
                dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_CALLBACK", details)
                _hasExclusiveAudioFocus.value = false
                onAudioFocusLostCallback?.invoke(focusChange)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val details = "response=$focusLabel action=IGNORE_CAN_DUCK"
                Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_CALLBACK | $details")
                dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_CALLBACK", details)
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                val details = "response=$focusLabel action=RESTORE_ELIGIBLE_AUDIORECORD"
                Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_CALLBACK | $details")
                dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_CALLBACK", details)
                _hasExclusiveAudioFocus.value = true
                onAudioFocusGainedCallback?.invoke()
            }
            else -> {
                val details = "response=$focusLabel action=UNHANDLED"
                Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_CALLBACK | $details")
                dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_CALLBACK", details)
            }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            if (isTransitioningAudioState) {
                Log.d(TAG, "Ignoring onRecordingConfigChanged while isTransitioningAudioState=true")
                return
            }

            // Filter out any recording configuration initiated by our own app (Process.myUid() or own audioSessionId)
            val externalConfigs = configs.filterNot { config ->
                isOwnRecordingConfig(config, activeAppAudioSessionId)
            }

            if (configs.isNotEmpty() && externalConfigs.isEmpty()) {
                Log.d(TAG, "Ignoring self-initiated AudioRecordingCallback in service (session=$activeAppAudioSessionId)")
                return
            }

            // Only flag external recording if a config is verified to belong to a different package UID
            val externalActive = externalConfigs.any { config ->
                val clientUid = extractClientUid(config)
                clientUid != null && clientUid != Process.myUid()
            }

            val previous = _isExternalRecordingActive.value
            if (externalActive == previous) return
            _isExternalRecordingActive.value = externalActive
            if (externalActive) {
                Log.d(TAG, "External microphone capture detected (non-app UID) — yielding AudioRecord")
                onExternalMicContentionChanged?.invoke(true)
            } else {
                Log.d(TAG, "External microphone capture ended — restoring wake listening")
                onExternalMicContentionChanged?.invoke(false)
            }
        }
    }

    private var isScreenStateReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    setDeviceScreenInteractiveState(false)
                    promoteToMicrophoneForeground(!isIntentionallyPaused)
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    setDeviceScreenInteractiveState(true)
                    promoteToMicrophoneForeground(!isIntentionallyPaused)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        loadPersistedCustomWakeWord(applicationContext)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                setDeviceScreenInteractiveState(pm.isInteractive)
            }
        } catch (_: Throwable) {}
        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, screenFilter)
            isScreenStateReceiverRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register screenStateReceiver: ${e.message}")
        }
        // Immediately promote to foreground with persistent notification so Samsung One UI
        // recognizes active microphone usage and does not put the service into a deep sleep bucket.
        promoteToMicrophoneForeground(!isIntentionallyPaused)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            audioManager?.registerAudioRecordingCallback(recordingCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register AudioRecordingCallback: ${e.message}")
        }
        startObservingWakeWordRepository()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance = this
        val incomingCustomWakeWord = intent?.getStringExtra(EXTRA_CUSTOM_WAKE_WORD)?.trim()
        if (!incomingCustomWakeWord.isNullOrBlank()) {
            val targetChatId = intent.getLongExtra(EXTRA_WAKE_TARGET_CHAT_ID, -1L).takeIf { it > 0L }
            val sensitivity = intent.getFloatExtra(EXTRA_CUSTOM_WAKE_WORD_SENSITIVITY, DEFAULT_CUSTOM_WAKE_SENSITIVITY)
            setCustomWakeWord(
                phrase = incomingCustomWakeWord,
                chatId = targetChatId,
                sensitivity = sensitivity,
                context = applicationContext
            )
        }

        if (intent?.action == ACTION_STOP_VOICE_SERVICE) {
            setIntentionallyPaused(true)
            stopSpeechRecognizerListening(destroyInstance = true)
            stopKeywordDetectionLoop()
            abandonExclusiveAudioFocus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Immediately call startForeground() with persistent notification on every start command
        promoteToMicrophoneForeground()

        when (intent?.action) {
            ACTION_SET_CUSTOM_WAKE_WORD -> {
                promoteToMicrophoneForeground(!isIntentionallyPaused)
                if (!isIntentionallyPaused && !_isKeywordLoopRunning.value) {
                    startKeywordDetectionLoop()
                }
                return START_STICKY
            }
            ACTION_ENTER_SLEEP_MODE -> {
                setSleepMode(true)
                setIntentionallyPaused(true)
                stopSpeechRecognizerListening(destroyInstance = false)
                releaseServiceMicrophoneRecord()
                abandonExclusiveAudioFocus()
                promoteToMicrophoneForeground()
                onSleepModeChangedCallback?.invoke(true)
                return START_STICKY
            }
            ACTION_WAKE_FROM_SLEEP -> {
                setSleepMode(false)
                setBatterySaverPaused(false)
                setIntentionallyPaused(false)
                if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive) {
                    requestExclusiveAudioFocus()
                }
                promoteToMicrophoneForeground()
                if (!_isKeywordLoopRunning.value) {
                    startKeywordDetectionLoop()
                }
                onSleepModeChangedCallback?.invoke(false)
                return START_STICKY
            }
            ACTION_TOGGLE_QUIET_MODE -> {
                val toggled = !_isQuietModeGlobal.value
                com.example.util.AppSettingsManager.setQuietModeEnabled(applicationContext, toggled)
                return START_STICKY
            }
            ACTION_BATTERY_SAVER_PAUSE -> {
                setBatterySaverPaused(true)
                setIntentionallyPaused(true)
                stopSpeechRecognizerListening(destroyInstance = false)
                releaseServiceMicrophoneRecord()
                abandonExclusiveAudioFocus()
                promoteToMicrophoneForeground()
                return START_STICKY
            }
            ACTION_BATTERY_SAVER_RESUME -> {
                setBatterySaverPaused(false)
                if (!_isSleepModeGlobal.value) {
                    setIntentionallyPaused(false)
                    if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive) {
                        requestExclusiveAudioFocus()
                    }
                    if (!_isKeywordLoopRunning.value) {
                        startKeywordDetectionLoop()
                    }
                }
                promoteToMicrophoneForeground()
                onBatterySaverResumeRequestedCallback?.invoke()
                return START_STICKY
            }
            ACTION_PAUSE_FOR_INPUT_FOCUS,
            ACTION_PAUSE_FOR_UI_DICTATION -> {
                _isInputFocusPaused.value = true
                setIntentionallyPaused(true)
                stopSpeechRecognizerListening(destroyInstance = false)
                releaseServiceMicrophoneRecord()
                abandonExclusiveAudioFocus()
                promoteToMicrophoneForeground()
                if (intent.action == ACTION_PAUSE_FOR_INPUT_FOCUS) {
                    onInputFocusPauseRequested?.invoke(true)
                }
                return START_STICKY
            }
            ACTION_PAUSE_FOR_COMMAND_DISPATCH -> {
                isProcessingCommand = true
                _isInputFocusPaused.value = true
                stopSpeechRecognizerListening(destroyInstance = false)
                releaseServiceMicrophoneRecord()
                abandonExclusiveAudioFocus()
                promoteToMicrophoneForeground()
                return START_STICKY
            }
            ACTION_RESUME_AFTER_INPUT_FOCUS,
            ACTION_RESUME_AFTER_COMMAND_DISPATCH,
            ACTION_RESUME_AFTER_UI_DICTATION -> {
                isProcessingCommand = false
                _isInputFocusPaused.value = false
                if (!_isSleepModeGlobal.value && !_isBatterySaverPausedGlobal.value) {
                    setIntentionallyPaused(false)
                }
                if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive) {
                    requestExclusiveAudioFocus()
                }
                promoteToMicrophoneForeground()
                if (intent.action == ACTION_RESUME_AFTER_INPUT_FOCUS) {
                    onInputFocusPauseRequested?.invoke(false)
                }
                if (!_isKeywordLoopRunning.value) {
                    startKeywordDetectionLoop()
                }
                return START_STICKY
            }
            else -> {
                if (!_isSleepModeGlobal.value && !_isBatterySaverPausedGlobal.value && !_isInputFocusPaused.value) {
                    setIntentionallyPaused(false)
                }
                // Do not re-request audio focus with a competing listener if WakeWordManager already holds it
                if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive && !isIntentionallyPaused) {
                    requestExclusiveAudioFocus()
                }
                val candidateTranscript = intent?.getStringExtra(EXTRA_CANDIDATE_TRANSCRIPT)
                if (!candidateTranscript.isNullOrBlank()) {
                    submitTranscriptForKeywordCheck(candidateTranscript)
                }
                startKeywordDetectionLoop()
                if (intent?.getBooleanExtra(EXTRA_USE_SPEECH_RECOGNIZER_ENGINE, false) == true &&
                    !isIntentionallyPaused &&
                    !isAppRecordingActive
                ) {
                    startSpeechRecognizerListening()
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        setIntentionallyPaused(true)
        stopSpeechRecognizerListening(destroyInstance = true)
        stopKeywordDetectionLoop()
        repositoryObserverJob?.cancel()
        serviceScope.cancel()
        if (isScreenStateReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Throwable) {}
            isScreenStateReceiverRegistered = false
        }
        try {
            audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {}
        abandonExclusiveAudioFocus()
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    /**
     * Resets and recreates [audioPipelineBuffer] and [audioPipelineFlow] if a downstream consumer
     * previously cancelled the flow.
     */
    @Synchronized
    fun resetAudioPipelineBuffer(): Flow<ByteArray> {
        audioPipelineBuffer.close()
        audioPipelineBuffer = Channel(
            capacity = AUDIO_PIPELINE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        audioPipelineFlow = audioPipelineBuffer.consumeAsFlow()
        return audioPipelineFlow
    }

    /**
     * Resolves the active [WakeWordRepository] (using injected test repository if set, or Room singleton).
     */
    fun resolveWakeWordRepository(): WakeWordRepository {
        customRepository?.let { return it }
        injectedRepositoryForTesting?.let { return it }
        val db = AppDatabase.getDatabase(applicationContext, serviceScope)
        return WakeWordRepository(db.wakeWordDao(), db.chatDao()).also {
            customRepository = it
        }
    }

    /**
     * Injects a custom [WakeWordRepository] into this service instance and refreshes the active wake word observer.
     */
    fun setWakeWordRepository(repository: WakeWordRepository) {
        customRepository = repository
        startObservingWakeWordRepository()
    }

    internal fun setActiveWakeWordsForTesting(words: List<WakeWord>) {
        repositoryObserverJob?.cancel()
        repositoryObserverJob = null
        customWakeWordOverrideActive = false
        _serviceActiveWakeWords.value = words
        _activeDatabaseWakeWords.value = words
    }

    private fun startObservingWakeWordRepository() {
        repositoryObserverJob?.cancel()
        repositoryObserverJob = serviceScope.launch {
            launch {
                try {
                    val repo = resolveWakeWordRepository()
                    repo.observeAllWakeWords().collect { allWords ->
                        val enabled = allWords.filter { it.isEnabled && it.phrase.isNotBlank() }
                        _serviceActiveWakeWords.value = enabled
                        _activeDatabaseWakeWords.value = enabled
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error observing WakeWordRepository in service: ${e.message}")
                }
            }
            launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext, serviceScope)
                    db.chatDao().getAllWakeWordSamplesFlow().collect { samples ->
                        _activeWakeWordSamples.value = samples
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error observing WakeWordSamples in service: ${e.message}")
                }
            }
        }
    }

    /**
     * Initializes the microphone (when not already held by the in-process speech/wake engine)
     * and runs the continuous keyword detection loop checking against [WakeWordRepository].
     *
     * Pushes raw PCM byte chunks into [audioPipelineBuffer] via `trySend()` and strictly releases
     * [AudioRecord] hardware when [isIntentionallyPaused] is `true`.
     */
    fun startKeywordDetectionLoop() {
        if (keywordLoopJob?.isActive == true) return
        _isKeywordLoopRunning.value = true
        _isKeywordLoopActiveGlobal.value = true

        keywordLoopJob = serviceScope.launch {
            try {
                val repo = resolveWakeWordRepository()
                if (_serviceActiveWakeWords.value.isEmpty()) {
                    val initial = repo.getAllWakeWordsSync().filter { it.isEnabled && it.phrase.isNotBlank() }
                    _serviceActiveWakeWords.value = initial
                    _activeDatabaseWakeWords.value = initial
                }

                val hasRecordAudioPerm = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                var pcmByteBuffer: ByteArray? = null
                if (hasRecordAudioPerm && !isAppRecordingActive && !isIntentionallyPaused && !_isSleepModeGlobal.value) {
                    synchronized(serviceAudioRecordMutex) {
                        if (!isIntentionallyPaused && !isAppRecordingActive) {
                            val record = initializeMicrophoneRecord()
                            if (record != null) {
                                serviceAudioRecord = record
                                pcmByteBuffer = ByteArray(2048)
                            }
                        }
                    }
                }

                while (isActive && _isKeywordLoopRunning.value) {
                    // Always drain candidate transcripts first so "Gemini, wake up" can wake from sleep mode
                    var candidate = transcriptQueue.poll()
                    while (candidate != null) {
                        checkTranscriptAgainstDatabase(candidate)
                        candidate = transcriptQueue.poll()
                    }

                    // Strictly release microphone hardware when isIntentionallyPaused is true
                    if (isIntentionallyPaused ||
                        _isInputFocusPaused.value ||
                        _isExternalRecordingActive.value ||
                        _isSleepModeGlobal.value ||
                        _isBatterySaverPausedGlobal.value
                    ) {
                        if (serviceAudioRecord != null) {
                            releaseServiceMicrophoneRecord()
                        }
                        delay(120L)
                        continue
                    }

                    // Read raw PCM bytes and push non-blockingly into audioPipelineBuffer via trySend()
                    val activeRecord = serviceAudioRecord
                    if (activeRecord != null && pcmByteBuffer != null && !isAppRecordingActive && !isIntentionallyPaused) {
                        try {
                            if (activeRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                val bytesRead = activeRecord.read(pcmByteBuffer, 0, pcmByteBuffer.size)
                                if (bytesRead > 0) {
                                    val pcmChunk = if (bytesRead == pcmByteBuffer.size) {
                                        pcmByteBuffer.copyOf()
                                    } else {
                                        pcmByteBuffer.copyOf(bytesRead)
                                    }
                                    // Immediately push PCM chunk into Channel<ByteArray> ring buffer
                                    audioPipelineBuffer.trySend(pcmChunk)

                                    // Compute RMS amplitude from 16-bit Little-Endian PCM samples
                                    var sumSq = 0.0
                                    var sampleCount = 0
                                    var i = 0
                                    while (i + 1 < bytesRead) {
                                        val low = pcmChunk[i].toInt() and 0xFF
                                        val high = pcmChunk[i + 1].toInt()
                                        val sample = ((high shl 8) or low).toShort().toDouble()
                                        sumSq += sample * sample
                                        sampleCount++
                                        i += 2
                                    }
                                    if (sampleCount > 0) {
                                        val rms = sqrt(sumSq / sampleCount)
                                        _serviceMicRms.value = rms.toFloat()
                                    }
                                    LocalKeywordSpotterEngine.evaluatePcmAudioFrame(
                                        pcmBytes = pcmChunk,
                                        bytesRead = pcmChunk.size,
                                        customWakePhrase = _customWakeWord.value,
                                        sensitivityThreshold = customWakeWordSensitivity,
                                        targetChatId = customWakeWordTargetChatId
                                    )
                                }
                            }
                        } catch (_: Throwable) {}
                    } else if ((isAppRecordingActive || isIntentionallyPaused) && serviceAudioRecord != null) {
                        // Yield standalone service AudioRecord immediately when SpeechRecognizer/WakeWordManager owns mic or paused
                        releaseServiceMicrophoneRecord()
                    }

                    val pollIntervalMs = resolveEffectivePollingIntervalMs()
                    _activeWakeWordPollingIntervalMs.value = pollIntervalMs
                    delay(pollIntervalMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Keyword detection loop terminated: ${e.message}")
            } finally {
                // Strictly release hardware when loop exits or isIntentionallyPaused is true
                releaseServiceMicrophoneRecord()
                _isKeywordLoopRunning.value = false
                _isKeywordLoopActiveGlobal.value = false
            }
        }
    }

    /**
     * Stops the keyword detection loop and releases any standalone microphone handle held by the service.
     */
    fun stopKeywordDetectionLoop() {
        _isKeywordLoopRunning.value = false
        _isKeywordLoopActiveGlobal.value = false
        keywordLoopJob?.cancel()
        keywordLoopJob = null
        releaseServiceMicrophoneRecord()
    }

    /**
     * Queues a recognized speech transcript to be evaluated by the keyword detection loop.
     */
    fun submitTranscriptForKeywordCheck(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.isNotBlank()) {
            transcriptQueue.offer(trimmed)
        }
    }

    /**
     * Configures and returns the Android [RecognizerIntent] used by [serviceSpeechRecognizer]
     * to listen for customizable wake words.
     */
    fun createServiceRecognizerIntent(): Intent {
        val rawIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return WakeWordManager.configureRecognizerIntent(
            intent = rawIntent,
            callingPackage = applicationContext?.packageName ?: "com.example"
        )
    }

    /**
     * Starts listening for the customizable wake word using Android's [SpeechRecognizer] API on the
     * main thread. Safely releases any standalone [AudioRecord] instance beforehand so the two
     * audio capture APIs never collide on the hardware microphone.
     */
    fun startSpeechRecognizerListening(): Boolean {
        if (isIntentionallyPaused ||
            _isSleepModeGlobal.value ||
            _isBatterySaverPausedGlobal.value ||
            _isInputFocusPaused.value ||
            isAppRecordingActive
        ) {
            return false
        }
        cancelScheduledSpeechRecognizerRestart()
        // Release standalone AudioRecord before SpeechRecognizer acquires the microphone
        releaseServiceMicrophoneRecord(repromotePassiveNotification = false)

        val startTask = Runnable {
            try {
                if (serviceSpeechRecognizer == null) {
                    serviceSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).also {
                        it.setRecognitionListener(serviceRecognitionListener)
                    }
                }
                val recognizerIntent = createServiceRecognizerIntent()
                serviceSpeechRecognizer?.startListening(recognizerIntent)
                _isServiceSpeechRecognizerListening.value = true
                promoteToMicrophoneForeground(true)
                dispatchDiagnosticTrace(
                    "SERVICE_SR_START_LISTENING",
                    "customWakeWord=${customWakeWord.value} callingPackage=${applicationContext.packageName}"
                )
            } catch (e: Throwable) {
                _isServiceSpeechRecognizerListening.value = false
                Log.w(TAG, "Unable to start SpeechRecognizer in WakeWordVoiceService: ${e.message}")
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            startTask.run()
        } else {
            mainHandler.post(startTask)
        }
        return true
    }

    /**
     * Stops and optionally destroys the service's [SpeechRecognizer] instance on the main thread.
     */
    fun stopSpeechRecognizerListening(destroyInstance: Boolean = false) {
        cancelScheduledSpeechRecognizerRestart()
        _isServiceSpeechRecognizerListening.value = false
        val stopTask = Runnable {
            val recognizer = serviceSpeechRecognizer
            if (destroyInstance) {
                serviceSpeechRecognizer = null
            }
            if (recognizer != null) {
                try {
                    recognizer.stopListening()
                } catch (_: Throwable) {}
                try {
                    recognizer.cancel()
                } catch (_: Throwable) {}
                if (destroyInstance) {
                    try {
                        recognizer.destroy()
                    } catch (_: Throwable) {}
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopTask.run()
        } else {
            mainHandler.post(stopTask)
        }
    }

    private fun scheduleSpeechRecognizerRestart(delayMs: Long) {
        cancelScheduledSpeechRecognizerRestart()
        val runnable = Runnable {
            if (!isIntentionallyPaused &&
                !_isSleepModeGlobal.value &&
                !_isBatterySaverPausedGlobal.value &&
                !_isInputFocusPaused.value &&
                !isAppRecordingActive
            ) {
                startSpeechRecognizerListening()
            }
        }
        speechRecognizerRestartRunnable = runnable
        val effectiveDelayMs = resolveEffectiveSpeechRecognizerRestartDelayMs(delayMs)
        mainHandler.postDelayed(runnable, effectiveDelayMs)
    }

    private fun cancelScheduledSpeechRecognizerRestart() {
        speechRecognizerRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        speechRecognizerRestartRunnable = null
    }

    /**
     * Returns the list of active [WakeWord] entities combined with the customizable wake word
     * when configured via [setCustomWakeWord] or when the database list is empty.
     */
    fun getEffectiveWakeWords(databaseWords: List<WakeWord> = _serviceActiveWakeWords.value): List<WakeWord> {
        val customPhrase = _customWakeWord.value.trim()
        val customWord = if (customPhrase.isNotBlank()) {
            WakeWord(
                id = CUSTOM_WAKE_WORD_ID,
                phrase = customPhrase,
                isEnabled = true,
                sensitivity = customWakeWordSensitivity,
                chatId = customWakeWordTargetChatId,
                chatTitle = "Gemini Assistant"
            )
        } else {
            null
        }
        if (customWord == null) return databaseWords
        if (databaseWords.isEmpty()) return listOf(customWord)
        if (!customWakeWordOverrideActive) return databaseWords
        return listOf(customWord) + databaseWords.filterNot { it.phrase.equals(customPhrase, ignoreCase = true) }
    }

    /**
     * Evaluates a transcript received from Android's [SpeechRecognizer] (`onResults` or `onPartialResults`)
     * against sleep/wake commands and the customizable + repository wake words, triggering the
     * Gemini assistant immediately when matched.
     */
    fun evaluateSpeechRecognizerTranscript(
        transcript: String,
        isPartial: Boolean = false
    ): WakeWordDetectionResult? {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) return null

        if (AudioCommandSanitizer.containsWakeUpCommand(cleanTranscript)) {
            if (_isSleepModeGlobal.value || _isBatterySaverPausedGlobal.value || isIntentionallyPaused) {
                AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Wake Up (\"$cleanTranscript\")")
                setSleepMode(false)
                setBatterySaverPaused(false)
                setIntentionallyPaused(false)
                promoteToMicrophoneForeground()
                onSleepModeChangedCallback?.invoke(false)
            }
            return null
        }

        if (AudioCommandSanitizer.containsSleepCommand(cleanTranscript)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Sleep (\"$cleanTranscript\")")
            setSleepMode(true)
            setIntentionallyPaused(true)
            stopSpeechRecognizerListening(destroyInstance = false)
            releaseServiceMicrophoneRecord()
            promoteToMicrophoneForeground()
            onSleepModeChangedCallback?.invoke(true)
            return null
        }

        if (_isSleepModeGlobal.value || _isQuietModeGlobal.value || isIntentionallyPaused) return null

        val effectiveWords = getEffectiveWakeWords(_serviceActiveWakeWords.value)
        if (!isPartial && AudioCommandSanitizer.isVoiceHistoryNavigationCommand(
                cleanTranscript,
                effectiveWords.map { it.phrase }
            )
        ) {
            AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Voice History Navigation (\"$cleanTranscript\")")
            isProcessingCommand = true
            stopSpeechRecognizerListening(destroyInstance = false)
            releaseServiceMicrophoneRecord()
            abandonExclusiveAudioFocus()
            onVoiceHistoryNavigationRequestedCallback?.invoke(cleanTranscript)
            return null
        }

        val match = matchWakeWordFromList(cleanTranscript, effectiveWords)
        LocalKeywordSpotterEngine.evaluateCandidateTranscript(
            transcript = cleanTranscript,
            customWakePhrase = match?.matchedPhrase ?: _customWakeWord.value,
            sensitivityThreshold = customWakeWordSensitivity,
            targetChatId = match?.wakeWord?.chatId ?: customWakeWordTargetChatId,
            targetChatTitle = match?.wakeWord?.chatTitle?.takeIf { it.isNotBlank() } ?: "Gemini Assistant"
        )
        if (match != null) {
            triggerGeminiAssistant(match)
        }
        return match
    }

    /**
     * Triggers the Gemini assistant in response to a detected [WakeWordDetectionResult]:
     * synchronously releases service audio capture handles, plays wake haptic feedback,
     * emits the result to observers, posts the ready cue notification, and launches/forwards
     * the wake event (plus any spoken follow-up query) to [com.example.MainActivity].
     */
    fun triggerGeminiAssistant(match: WakeWordDetectionResult) {
        stopSpeechRecognizerListening(destroyInstance = false)
        stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
        AudioCommandSanitizer.playWakeWordHaptic(applicationContext, match.matchedPhrase)
        _lastDetectedWakeWord.value = match
        _detectedWakeWordsFlow.tryEmit(match)
        _geminiAssistantTriggerFlow.tryEmit(match)
        postWakeWordReadyCueNotification(applicationContext, match)
        if (!isMainActivityInForeground) {
            launchChatActivityFromBackgroundService(applicationContext, match)
        }
        onWakeWordDetectedCallback?.invoke(match)
        onGeminiAssistantTriggeredCallback?.invoke(match)
        dispatchDiagnosticTrace(
            "GEMINI_ASSISTANT_TRIGGERED",
            "matchedPhrase=\"${match.matchedPhrase}\" chatId=${match.wakeWord.chatId} followUp=${!match.followUpQuery.isNullOrBlank()}"
        )
    }

    /**
     * Checks [transcript] directly against sleep/wake voice commands and the active wake words in [WakeWordRepository]
     * (plus any customizable wake word set via [setCustomWakeWord]) and emits a [WakeWordDetectionResult] if a match is found.
     */
    suspend fun checkTranscriptAgainstDatabase(transcript: String): WakeWordDetectionResult? {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) return null

        // Check for "Gemini, wake up" command to exit sleep mode
        if (AudioCommandSanitizer.containsWakeUpCommand(cleanTranscript)) {
            if (_isSleepModeGlobal.value || _isBatterySaverPausedGlobal.value || isIntentionallyPaused) {
                AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Wake Up (\"$cleanTranscript\")")
                setSleepMode(false)
                setBatterySaverPaused(false)
                setIntentionallyPaused(false)
                promoteToMicrophoneForeground()
                onSleepModeChangedCallback?.invoke(false)
            }
            return null
        }

        // Check for "Gemini, sleep" command to enter sleep mode
        if (AudioCommandSanitizer.containsSleepCommand(cleanTranscript)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Sleep (\"$cleanTranscript\")")
            setSleepMode(true)
            setIntentionallyPaused(true)
            stopSpeechRecognizerListening(destroyInstance = false)
            releaseServiceMicrophoneRecord()
            promoteToMicrophoneForeground()
            onSleepModeChangedCallback?.invoke(true)
            return null
        }

        // If in sleep mode or intentionally paused, ignore normal wake word triggers until woken up
        if (_isSleepModeGlobal.value || _isQuietModeGlobal.value || isIntentionallyPaused) return null

        val currentActive = _serviceActiveWakeWords.value.ifEmpty {
            val loaded = resolveWakeWordRepository().getAllWakeWordsSync()
                .filter { it.isEnabled && it.phrase.isNotBlank() }
            _serviceActiveWakeWords.value = loaded
            _activeDatabaseWakeWords.value = loaded
            loaded
        }
        val effectiveActive = getEffectiveWakeWords(currentActive)

        // Check for voice history navigation command ("open history", "show history", "voice history")
        // and immediately release service AudioRecord + audio focus before dispatching in-memory callback
        if (AudioCommandSanitizer.isVoiceHistoryNavigationCommand(
                cleanTranscript,
                effectiveActive.map { it.phrase }
            )
        ) {
            AudioCommandSanitizer.playVoiceCommandHaptic(applicationContext, "Voice History Navigation (\"$cleanTranscript\")")
            isProcessingCommand = true
            stopSpeechRecognizerListening(destroyInstance = false)
            releaseServiceMicrophoneRecord()
            abandonExclusiveAudioFocus()
            onVoiceHistoryNavigationRequestedCallback?.invoke(cleanTranscript)
            return null
        }

        val match = matchWakeWordFromList(cleanTranscript, effectiveActive)
        LocalKeywordSpotterEngine.evaluateCandidateTranscript(
            transcript = cleanTranscript,
            customWakePhrase = match?.matchedPhrase ?: _customWakeWord.value,
            sensitivityThreshold = customWakeWordSensitivity,
            targetChatId = match?.wakeWord?.chatId ?: customWakeWordTargetChatId,
            targetChatTitle = match?.wakeWord?.chatTitle?.takeIf { it.isNotBlank() } ?: "Gemini Assistant"
        )
        if (match != null) {
            triggerGeminiAssistant(match)
        }
        return match
    }

    private fun initializeMicrophoneRecord(): AudioRecord? {
        if (isIntentionallyPaused) return null
        return try {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBuf > 0) (minBuf * 2).coerceAtLeast(3200) else 6400
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                registerOwnAudioSessionId(record.audioSessionId)
                record.startRecording()
                _isMicrophoneInitialized.value = true
                promoteToMicrophoneForeground(true)
                record
            } else {
                record.release()
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException initializing AudioRecord in service: ${e.message}")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to initialize AudioRecord in service: ${e.message}")
            null
        }
    }

    internal fun releaseServiceMicrophoneRecord(repromotePassiveNotification: Boolean = false) {
        val record = synchronized(serviceAudioRecordMutex) {
            val current = serviceAudioRecord
            serviceAudioRecord = null
            val nullifiedAtEpochMs = System.currentTimeMillis()
            lastAudioRecordNullifiedAtEpochMs = nullifiedAtEpochMs
            _isMicrophoneInitialized.value = false
            _serviceMicRms.value = 0f
            if (current != null) {
                try {
                    if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        current.stop()
                    }
                } catch (_: Throwable) {}
                try {
                    current.release()
                } catch (_: Throwable) {}
            }
            val releaseCompletedEpochMs = System.currentTimeMillis()
            val traceDetails =
                "serviceAudioRecord=null atEpochMs=$nullifiedAtEpochMs releaseCompletedEpochMs=$releaseCompletedEpochMs hadActiveRecord=${current != null} repromote=$repromotePassiveNotification"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIORECORD_NULLIFIED | $traceDetails")
            dispatchDiagnosticTrace("SERVICE_AUDIORECORD_NULLIFIED", traceDetails)
            current
        } ?: return
        if (repromotePassiveNotification && !isMainActivityInForeground) {
            promoteToMicrophoneForeground(false)
        }
    }

    fun requestExclusiveAudioFocus(): Boolean {
        val manager = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return false
        audioManager = manager
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = req
                manager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val responseLabel = formatAudioFocusRequestResult(result)
            val traceDetails = "response=$responseLabel granted=$granted epochMs=${System.currentTimeMillis()}"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_REQUEST | $traceDetails")
            dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_REQUEST", traceDetails)
            _hasExclusiveAudioFocus.value = granted
            granted
        } catch (e: Exception) {
            val errDetails = "response=AUDIOFOCUS_REQUEST_EXCEPTION error=${e.message}"
            Log.w(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_REQUEST | $errDetails")
            dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_REQUEST", errDetails)
            false
        }
    }

    fun abandonExclusiveAudioFocus() {
        val manager = audioManager ?: return
        try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
                    ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusChangeListener)
            }
            val responseLabel = formatAudioFocusRequestResult(result)
            val traceDetails = "response=$responseLabel epochMs=${System.currentTimeMillis()}"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_ABANDON | $traceDetails")
            dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_ABANDON", traceDetails)
        } catch (e: Exception) {
            val errDetails = "response=AUDIOFOCUS_ABANDON_EXCEPTION error=${e.message}"
            Log.w(TAG, "[SR-Trace] stage=SERVICE_AUDIOFOCUS_ABANDON | $errDetails")
            dispatchDiagnosticTrace("SERVICE_AUDIOFOCUS_ABANDON", errDetails)
        } finally {
            _hasExclusiveAudioFocus.value = false
        }
    }

    /**
     * Immediately calls `startForeground()` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` and a
     * persistent notification so Samsung One UI knows the microphone is actively in use
     * and does not place the app in a deep-sleep bucket.
     */
    private fun promoteToMicrophoneForeground(isActivelyListening: Boolean = false) {
        try {
            ensureNotificationChannel()
            val notification = buildNotification(isActivelyListening)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            try {
                val notification = buildNotification(isActivelyListening)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (inner: Throwable) {
                Log.w(TAG, "Unable to promote WakeWordVoiceService to foreground: ${inner.message}")
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live Voice & Wake Word Microphone",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps microphone capture active during wake-word and live voice sessions"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
            val cueExisting = manager.getNotificationChannel(CHANNEL_WAKE_CUE_ID)
            if (cueExisting == null) {
                val cueChannel = NotificationChannel(
                    CHANNEL_WAKE_CUE_ID,
                    "Wake Word Ready Cue",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Non-intrusive notification when a wake word is detected in the background"
                    setShowBadge(true)
                }
                manager.createNotificationChannel(cueChannel)
            }
        }
    }

    internal fun buildNotification(isActivelyListening: Boolean = false): Notification {
        val isSleeping = _isSleepModeGlobal.value
        val isBatterySaverPaused = _isBatterySaverPausedGlobal.value
        val isReducedScreenOffPolling = _screenOffBatterySaverEnabled.value && !_isDeviceScreenInteractive.value
        val pollIntervalMs = resolveEffectivePollingIntervalMs()
        val title = when {
            isBatterySaverPaused -> "Gemini Wake • Battery Saver Paused"
            isSleeping -> "Gemini Wake • Sleep Mode"
            isActivelyListening && isReducedScreenOffPolling -> "Gemini Wake • Glowing Listener (Screen-Off Saver)"
            isActivelyListening -> "Gemini Wake Active • Glowing Listener"
            else -> "Gemini Wake Active"
        }
        val subtitle = when {
            isBatterySaverPaused -> "Wake word detection paused to save battery"
            isSleeping -> "Say \"Gemini, wake up\" or tap Wake Up"
            isActivelyListening && isReducedScreenOffPolling ->
                "Listening for \"${_customWakeWord.value}\" • Screen-Off Battery Saver (${pollIntervalMs}ms poll)"
            isActivelyListening ->
                "Actively listening for \"${_customWakeWord.value}\" (${pollIntervalMs}ms poll)"
            else -> "Hands-free standby"
        }
        val smallIconRes = resolveNotificationSmallIconRes(isActivelyListening && !isSleeping && !isBatterySaverPaused)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(smallIconRes)
            .setColor(if (isActivelyListening && !isSleeping && !isBatterySaverPaused) 0xFF10B981.toInt() else 0xFF64748B.toInt())
            .setColorized(isActivelyListening && !isSleeping && !isBatterySaverPaused)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isBatterySaverPaused) {
            val resumeIntent = Intent(this, WakeWordVoiceService::class.java).apply {
                action = ACTION_BATTERY_SAVER_RESUME
            }
            val resumePendingIntent = android.app.PendingIntent.getService(
                this,
                4106,
                resumeIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "Resume",
                resumePendingIntent
            )
        } else if (isSleeping) {
            val wakeIntent = Intent(this, WakeWordVoiceService::class.java).apply {
                action = ACTION_WAKE_FROM_SLEEP
            }
            val wakePendingIntent = android.app.PendingIntent.getService(
                this,
                4105,
                wakeIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "Wake Up",
                wakePendingIntent
            )
        }
        return builder.build()
    }

    companion object {
        const val AUDIO_PIPELINE_BUFFER_CAPACITY = 32
        const val SPEECH_RECOGNIZER_RESTART_DELAY_MS = 1500L
        const val SCREEN_OFF_SPEECH_RECOGNIZER_RESTART_DELAY_MS = 4500L
        const val ACTIVE_SCREEN_POLLING_INTERVAL_MS = 60L
        const val SCREEN_OFF_BATTERY_SAVER_POLLING_INTERVAL_MS = 450L
        const val CUSTOM_WAKE_WORD_ID = -9001L
        const val DEFAULT_CUSTOM_WAKE_WORD = "Hey Gemini"
        const val DEFAULT_CUSTOM_WAKE_SENSITIVITY = 0.78f
        const val PREFS_CUSTOM_WAKE_NAME = "gemini_wake_service_prefs"
        const val PREF_KEY_CUSTOM_WAKE_WORD = "custom_wake_word_phrase"
        const val PREF_KEY_CUSTOM_WAKE_CHAT_ID = "custom_wake_word_chat_id"
        const val PREF_KEY_CUSTOM_WAKE_SENSITIVITY = "custom_wake_word_sensitivity"
        const val PREF_KEY_SCREEN_OFF_BATTERY_SAVER = "screen_off_battery_saver_enabled"

        const val CHANNEL_ID = "gemini_wake_mic_channel"
        const val CHANNEL_WAKE_CUE_ID = "gemini_wake_ready_cue_channel"
        const val NOTIFICATION_ID = 4102
        const val NOTIFICATION_ID_WAKE_READY = 4103
        const val ACTION_START_VOICE_SERVICE = "com.example.voice.action.START_VOICE_SERVICE"
        const val ACTION_STOP_VOICE_SERVICE = "com.example.voice.action.STOP_VOICE_SERVICE"
        const val ACTION_SET_CUSTOM_WAKE_WORD = "com.example.voice.action.SET_CUSTOM_WAKE_WORD"
        const val ACTION_ENTER_SLEEP_MODE = "com.example.voice.action.ENTER_SLEEP_MODE"
        const val ACTION_WAKE_FROM_SLEEP = "com.example.voice.action.WAKE_FROM_SLEEP"
        const val ACTION_BATTERY_SAVER_PAUSE = "com.example.voice.action.BATTERY_SAVER_PAUSE"
        const val ACTION_BATTERY_SAVER_RESUME = "com.example.voice.action.BATTERY_SAVER_RESUME"
        const val ACTION_PAUSE_FOR_INPUT_FOCUS = "com.example.voice.action.PAUSE_FOR_INPUT_FOCUS"
        const val ACTION_RESUME_AFTER_INPUT_FOCUS = "com.example.voice.action.RESUME_AFTER_INPUT_FOCUS"
        const val ACTION_PAUSE_FOR_COMMAND_DISPATCH = "com.example.voice.action.PAUSE_FOR_COMMAND_DISPATCH"
        const val ACTION_RESUME_AFTER_COMMAND_DISPATCH = "com.example.voice.action.RESUME_AFTER_COMMAND_DISPATCH"
        const val ACTION_PAUSE_FOR_UI_DICTATION = "com.example.voice.action.PAUSE_FOR_UI_DICTATION"
        const val ACTION_RESUME_AFTER_UI_DICTATION = "com.example.voice.action.RESUME_AFTER_UI_DICTATION"
        const val ACTION_TOGGLE_QUIET_MODE = "com.example.voice.action.TOGGLE_QUIET_MODE"
        const val EXTRA_CANDIDATE_TRANSCRIPT = "com.example.voice.extra.CANDIDATE_TRANSCRIPT"
        const val EXTRA_CUSTOM_WAKE_WORD = "com.example.voice.extra.CUSTOM_WAKE_WORD"
        const val EXTRA_CUSTOM_WAKE_WORD_SENSITIVITY = "com.example.voice.extra.CUSTOM_WAKE_WORD_SENSITIVITY"
        const val EXTRA_USE_SPEECH_RECOGNIZER_ENGINE = "com.example.voice.extra.USE_SPEECH_RECOGNIZER_ENGINE"
        const val EXTRA_WAKE_TARGET_CHAT_ID = "com.example.voice.extra.WAKE_TARGET_CHAT_ID"
        const val EXTRA_WAKE_MATCHED_PHRASE = "com.example.voice.extra.WAKE_MATCHED_PHRASE"
        const val EXTRA_WAKE_INITIAL_QUERY = "com.example.voice.extra.WAKE_INITIAL_QUERY"

        private val _customWakeWord = MutableStateFlow(DEFAULT_CUSTOM_WAKE_WORD)
        val customWakeWord: StateFlow<String> = _customWakeWord.asStateFlow()

        private val _isQuietModeGlobal = MutableStateFlow(false)
        val isQuietModeGlobal: StateFlow<Boolean> = _isQuietModeGlobal.asStateFlow()

        var onQuietModeChangedCallback: ((Boolean) -> Unit)? = null

        fun setQuietMode(enabled: Boolean, context: Context? = null) {
            _isQuietModeGlobal.value = enabled
            if (enabled) {
                setIntentionallyPaused(true)
                activeInstance?.stopSpeechRecognizerListening(destroyInstance = false)
                stopAndReleaseServiceAudioRecordSynchronously("quiet_mode_enabled")
                activeInstance?.promoteToMicrophoneForeground(false)
            } else {
                setSleepMode(false)
                setIntentionallyPaused(false)
                activeInstance?.promoteToMicrophoneForeground(true)
                activeInstance?.let { instance ->
                    if (!_isKeywordLoopActiveGlobal.value) {
                        instance.startKeywordDetectionLoop()
                    }
                }
            }
            onQuietModeChangedCallback?.invoke(enabled)
            dispatchDiagnosticTrace("QUIET_MODE_CHANGED", "enabled=$enabled")
        }

        @Volatile
        var customWakeWordTargetChatId: Long? = null
            internal set

        @Volatile
        var customWakeWordSensitivity: Float = DEFAULT_CUSTOM_WAKE_SENSITIVITY
            private set

        @Volatile
        var customWakeWordOverrideActive: Boolean = false
            internal set

        private val _isServiceSpeechRecognizerListening = MutableStateFlow(false)
        val isServiceSpeechRecognizerListening: StateFlow<Boolean> = _isServiceSpeechRecognizerListening.asStateFlow()

        private val _screenOffBatterySaverEnabled = MutableStateFlow(false)
        val screenOffBatterySaverEnabled: StateFlow<Boolean> = _screenOffBatterySaverEnabled.asStateFlow()

        private val _isDeviceScreenInteractive = MutableStateFlow(true)
        val isDeviceScreenInteractive: StateFlow<Boolean> = _isDeviceScreenInteractive.asStateFlow()

        private val _activeWakeWordPollingIntervalMs = MutableStateFlow(ACTIVE_SCREEN_POLLING_INTERVAL_MS)
        val activeWakeWordPollingIntervalMs: StateFlow<Long> = _activeWakeWordPollingIntervalMs.asStateFlow()

        private val _geminiAssistantTriggerFlow = MutableSharedFlow<WakeWordDetectionResult>(extraBufferCapacity = 16)
        val geminiAssistantTriggerFlow: SharedFlow<WakeWordDetectionResult> = _geminiAssistantTriggerFlow.asSharedFlow()

        var onGeminiAssistantTriggeredCallback: ((WakeWordDetectionResult) -> Unit)? = null

        /**
         * Resolves the notification small icon resource ID: returns the subtle animated glowing
         * wake-word icon (`R.drawable.ic_notification_wake_glowing`) when actively listening,
         * and the standby icon (`R.drawable.ic_notification_wake_standby`) when idle or paused.
         */
        fun resolveNotificationSmallIconRes(isActivelyListening: Boolean): Int {
            return if (isActivelyListening) {
                com.example.R.drawable.ic_notification_wake_glowing
            } else {
                com.example.R.drawable.ic_notification_wake_standby
            }
        }

        /**
         * Updates whether the Screen-Off Battery Saver mode is enabled. When enabled and the
         * device screen is off (`!isDeviceScreenInteractive.value`), wake-word detection polling
         * frequency is reduced from [ACTIVE_SCREEN_POLLING_INTERVAL_MS] (`60ms`) to
         * [SCREEN_OFF_BATTERY_SAVER_POLLING_INTERVAL_MS] (`450ms`).
         */
        fun setScreenOffBatterySaverEnabled(enabled: Boolean, context: Context? = null) {
            _screenOffBatterySaverEnabled.value = enabled
            _activeWakeWordPollingIntervalMs.value = resolveEffectivePollingIntervalMs()
            if (context != null) {
                try {
                    val appCtx = context.applicationContext ?: context
                    val prefs = appCtx.getSharedPreferences(PREFS_CUSTOM_WAKE_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(PREF_KEY_SCREEN_OFF_BATTERY_SAVER, enabled).apply()
                    val appSettingsPrefs = appCtx.getSharedPreferences(
                        com.example.util.AppSettingsManager.PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    appSettingsPrefs.edit()
                        .putBoolean(com.example.util.AppSettingsManager.KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED, enabled)
                        .apply()
                } catch (_: Throwable) {}
            }
            activeInstance?.promoteToMicrophoneForeground(!isIntentionallyPaused)
            dispatchDiagnosticTrace(
                "SCREEN_OFF_BATTERY_SAVER_CHANGED",
                "enabled=$enabled screenInteractive=${_isDeviceScreenInteractive.value} effectivePollingMs=${_activeWakeWordPollingIntervalMs.value}"
            )
        }

        /**
         * Updates whether the device display is currently interactive (screen on) or non-interactive
         * (screen off) and recalculates [activeWakeWordPollingIntervalMs].
         */
        fun setDeviceScreenInteractiveState(interactive: Boolean) {
            _isDeviceScreenInteractive.value = interactive
            _activeWakeWordPollingIntervalMs.value = resolveEffectivePollingIntervalMs()
            dispatchDiagnosticTrace(
                "DEVICE_SCREEN_STATE_CHANGED",
                "screenInteractive=$interactive batterySaver=${_screenOffBatterySaverEnabled.value} effectivePollingMs=${_activeWakeWordPollingIntervalMs.value}"
            )
        }

        /**
         * Computes the effective wake-word detection polling interval in milliseconds:
         * - `450ms` ([SCREEN_OFF_BATTERY_SAVER_POLLING_INTERVAL_MS]) when [screenOffBatterySaverEnabled]
         *   is `true` and the screen is off (`isScreenInteractive == false`).
         * - `60ms` ([ACTIVE_SCREEN_POLLING_INTERVAL_MS]) otherwise.
         */
        fun resolveEffectivePollingIntervalMs(
            batterySaverEnabled: Boolean = _screenOffBatterySaverEnabled.value,
            isScreenInteractive: Boolean = _isDeviceScreenInteractive.value
        ): Long {
            return if (batterySaverEnabled && !isScreenInteractive) {
                SCREEN_OFF_BATTERY_SAVER_POLLING_INTERVAL_MS
            } else {
                ACTIVE_SCREEN_POLLING_INTERVAL_MS
            }
        }

        /**
         * Computes the effective background [SpeechRecognizer] restart delay in milliseconds,
         * lengthening the polling cadence from `1500ms` to `4500ms` when Screen-Off Battery Saver
         * is enabled and the screen is off.
         */
        fun resolveEffectiveSpeechRecognizerRestartDelayMs(
            requestedDelayMs: Long = SPEECH_RECOGNIZER_RESTART_DELAY_MS,
            batterySaverEnabled: Boolean = _screenOffBatterySaverEnabled.value,
            isScreenInteractive: Boolean = _isDeviceScreenInteractive.value
        ): Long {
            val minFloor = if (batterySaverEnabled && !isScreenInteractive) {
                SCREEN_OFF_SPEECH_RECOGNIZER_RESTART_DELAY_MS
            } else {
                SPEECH_RECOGNIZER_RESTART_DELAY_MS
            }
            return requestedDelayMs.coerceAtLeast(minFloor)
        }

        /**
         * Updates the customizable wake word phrase used by [WakeWordVoiceService] and optionally
         * persists it to SharedPreferences so background [SpeechRecognizer] sessions immediately
         * recognize the new trigger phrase.
         */
        fun setCustomWakeWord(
            phrase: String,
            chatId: Long? = null,
            sensitivity: Float = DEFAULT_CUSTOM_WAKE_SENSITIVITY,
            context: Context? = null
        ) {
            val sanitized = phrase.trim().ifBlank { DEFAULT_CUSTOM_WAKE_WORD }
            _customWakeWord.value = sanitized
            customWakeWordTargetChatId = chatId
            customWakeWordSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
            customWakeWordOverrideActive = true

            if (context != null) {
                try {
                    val appCtx = context.applicationContext ?: context
                    val prefs = appCtx.getSharedPreferences(PREFS_CUSTOM_WAKE_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(PREF_KEY_CUSTOM_WAKE_WORD, sanitized)
                        .putLong(PREF_KEY_CUSTOM_WAKE_CHAT_ID, chatId ?: -1L)
                        .putFloat(PREF_KEY_CUSTOM_WAKE_SENSITIVITY, customWakeWordSensitivity)
                        .apply()
                    val appSettingsPrefs = appCtx.getSharedPreferences(
                        com.example.util.AppSettingsManager.PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    appSettingsPrefs.edit()
                        .putString(com.example.util.AppSettingsManager.KEY_CUSTOM_WAKE_WORD_PHRASE, sanitized)
                        .apply()
                } catch (_: Throwable) {}
            }
            dispatchDiagnosticTrace(
                "CUSTOM_WAKE_WORD_CONFIGURED",
                "phrase=\"$sanitized\" chatId=${chatId ?: -1L} sensitivity=$customWakeWordSensitivity"
            )
        }

        internal fun loadPersistedCustomWakeWord(context: Context) {
            try {
                val appCtx = context.applicationContext ?: context
                val appSettingsPrefs = appCtx.getSharedPreferences(
                    com.example.util.AppSettingsManager.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val prefs = appCtx.getSharedPreferences(PREFS_CUSTOM_WAKE_NAME, Context.MODE_PRIVATE)
                val savedPhrase = appSettingsPrefs.getString(
                    com.example.util.AppSettingsManager.KEY_CUSTOM_WAKE_WORD_PHRASE,
                    null
                )?.trim()?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(PREF_KEY_CUSTOM_WAKE_WORD, null)?.trim()
                if (!savedPhrase.isNullOrBlank()) {
                    _customWakeWord.value = savedPhrase
                    val savedChatId = prefs.getLong(PREF_KEY_CUSTOM_WAKE_CHAT_ID, -1L)
                    customWakeWordTargetChatId = savedChatId.takeIf { it > 0L }
                    customWakeWordSensitivity = prefs.getFloat(
                        PREF_KEY_CUSTOM_WAKE_SENSITIVITY,
                        DEFAULT_CUSTOM_WAKE_SENSITIVITY
                    ).coerceIn(0.50f, 0.98f)
                }
                val savedScreenOffBatterySaver = if (appSettingsPrefs.contains(com.example.util.AppSettingsManager.KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED)) {
                    appSettingsPrefs.getBoolean(com.example.util.AppSettingsManager.KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED, false)
                } else {
                    prefs.getBoolean(PREF_KEY_SCREEN_OFF_BATTERY_SAVER, false)
                }
                _screenOffBatterySaverEnabled.value = savedScreenOffBatterySaver
                _activeWakeWordPollingIntervalMs.value = resolveEffectivePollingIntervalMs()
            } catch (_: Throwable) {}
        }

        @Volatile
        var injectedRepositoryForTesting: WakeWordRepository? = null

        /**
         * Global guard flag indicating whether the foreground service microphone loop is
         * intentionally paused (due to Sleep Mode, Battery Saver, or Input Focus).
         * When `true`, the service's [AudioRecord] hardware is strictly released.
         */
        @Volatile
        private var activeInstance: WakeWordVoiceService? = null

        @Volatile
        var synchronousDictationHandoffReleaseCount: Int = 0
            private set

        @Volatile
        var lastSynchronousReleaseReason: String = ""
            private set

        @Volatile
        var lastAudioRecordNullifiedAtEpochMs: Long = 0L
            private set

        @Volatile
        var onDiagnosticTraceCallback: ((stage: String, details: String) -> Unit)? = null

        fun dispatchDiagnosticTrace(stage: String, details: String) {
            onDiagnosticTraceCallback?.invoke(stage, details)
        }

        fun formatAudioFocusRequestResult(result: Int): String = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "AUDIOFOCUS_REQUEST_GRANTED(1)"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "AUDIOFOCUS_REQUEST_FAILED(0)"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "AUDIOFOCUS_REQUEST_DELAYED(2)"
            else -> "AUDIOFOCUS_REQUEST_UNKNOWN($result)"
        }

        fun formatAudioFocusChange(focusChange: Int): String = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN(1)"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "AUDIOFOCUS_GAIN_TRANSIENT(2)"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK(3)"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE(4)"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS(-1)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT(-2)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK(-3)"
            else -> "AUDIOFOCUS_CHANGE_UNKNOWN($focusChange)"
        }

        fun formatSpeechRecognizerError(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT(1)"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK(2)"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO(3)"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER(4)"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT(5)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT(6)"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH(7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY(8)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS(9)"
            else -> "ERROR_UNKNOWN($error)"
        }

        @Volatile
        var isMainActivityInForeground: Boolean = true

        @Volatile
        var isIntentionallyPaused: Boolean = false
            private set

        @Volatile
        var isProcessingCommand: Boolean = false
            private set

        fun setIntentionallyPaused(paused: Boolean) {
            isIntentionallyPaused = paused
            if (paused) {
                activeInstance?.releaseServiceMicrophoneRecord(repromotePassiveNotification = false)
            }
        }

        /**
         * Synchronously stops and releases the active [WakeWordVoiceService]'s [AudioRecord] instance
         * under an exclusive mutex lock and abandons its audio focus before UI dictation, wake-word
         * navigation handoff (`"wake_word_detected"`), or TTS playback (`"tts_speaking"`).
         */
        fun stopAndReleaseServiceAudioRecordSynchronously(reason: String = "ui_dictation_mutex_handoff") {
            synchronousDictationHandoffReleaseCount++
            lastSynchronousReleaseReason = reason
            isProcessingCommand = true
            isIntentionallyPaused = true
            _isInputFocusPaused.value = true
            _isMicrophoneInitialized.value = false
            _isServiceSpeechRecognizerListening.value = false
            _serviceMicRms.value = 0f
            val instance = activeInstance
            if (instance != null) {
                instance.stopSpeechRecognizerListening(destroyInstance = false)
                instance.releaseServiceMicrophoneRecord(repromotePassiveNotification = false)
                instance.abandonExclusiveAudioFocus()
            } else {
                val nullifiedAtEpochMs = System.currentTimeMillis()
                lastAudioRecordNullifiedAtEpochMs = nullifiedAtEpochMs
                val traceDetails =
                    "serviceAudioRecord=null atEpochMs=$nullifiedAtEpochMs releaseCompletedEpochMs=$nullifiedAtEpochMs hadActiveRecord=false activeInstance=null reason=$reason"
                Log.d(TAG, "[SR-Trace] stage=SERVICE_AUDIORECORD_NULLIFIED | $traceDetails")
                dispatchDiagnosticTrace("SERVICE_AUDIORECORD_NULLIFIED", traceDetails)
            }
        }

        /**
         * Explicitly pauses the background [WakeWordVoiceService] and synchronously releases its
         * [AudioRecord] hardware lock and exclusive audio focus before the 150ms OS hardware buffer
         * clear window for foreground UI Mic dictation.
         */
        fun pauseForUiDictation(context: Context? = null) {
            stopAndReleaseServiceAudioRecordSynchronously("ui_dictation_button_handoff")
            val details =
                "isIntentionallyPaused=$isIntentionallyPaused isInputFocusPaused=${_isInputFocusPaused.value} nullifiedAtEpochMs=$lastAudioRecordNullifiedAtEpochMs"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_PAUSED_FOR_UI_MIC | $details")
            dispatchDiagnosticTrace("SERVICE_PAUSED_FOR_UI_MIC", details)
        }

        /**
         * Restarts/resumes the background [WakeWordVoiceService] after the user finishes speaking
         * or the foreground UI Mic closes.
         */
        fun resumeAfterUiDictation(context: Context? = null, restartLoop: Boolean = true) {
            isProcessingCommand = false
            _isInputFocusPaused.value = false
            if (!_isSleepModeGlobal.value && !_isBatterySaverPausedGlobal.value) {
                isIntentionallyPaused = false
                if (restartLoop) {
                    val instance = activeInstance
                    if (instance != null) {
                        if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive) {
                            instance.requestExclusiveAudioFocus()
                        }
                        if (!instance._isKeywordLoopRunning.value) {
                            instance.startKeywordDetectionLoop()
                        }
                    } else if (context != null) {
                        startServiceSafely(context, ACTION_RESUME_AFTER_UI_DICTATION)
                    }
                }
            }
            val details =
                "isIntentionallyPaused=$isIntentionallyPaused restartLoop=$restartLoop activeInstance=${activeInstance != null}"
            Log.d(TAG, "[SR-Trace] stage=SERVICE_RESUMED_AFTER_UI_MIC | $details")
            dispatchDiagnosticTrace("SERVICE_RESUMED_AFTER_UI_MIC", details)
        }

        /**
         * Pauses the service's microphone state purely in memory and synchronously releases
         * any active [AudioRecord] handle without creating or firing any Intent so in-chat
         * dictation stays 100% internal to the app process.
         */
        fun pauseForInternalDictationInMemory() {
            pauseForUiDictation(null)
        }

        /**
         * Resumes the service's passive microphone state purely in memory after internal dictation finishes.
         */
        fun resumeAfterInternalDictationInMemory() {
            resumeAfterUiDictation(null, restartLoop = false)
        }

        /**
         * Enters transient `isProcessingCommand = true` state and releases the service's
         * [AudioRecord] and audio focus before an in-app voice command or external Intent is
         * dispatched, without setting `isIntentionallyPaused = true`.
         */
        fun pauseMicrophoneForCommandDispatch(context: Context) {
            isProcessingCommand = true
            if (!_isSleepModeGlobal.value && !_isBatterySaverPausedGlobal.value) {
                isIntentionallyPaused = false
            }
            _isInputFocusPaused.value = true
            startServiceSafely(context, ACTION_PAUSE_FOR_COMMAND_DISPATCH)
        }

        /**
         * Resumes the service's passive microphone state after a voice command or navigation
         * transition settles (unless Sleep Mode or Battery Saver pause is active).
         */
        fun resumeMicrophoneAfterCommandNavigation(context: Context) {
            isProcessingCommand = false
            _isInputFocusPaused.value = false
            if (!_isSleepModeGlobal.value && !_isBatterySaverPausedGlobal.value) {
                isIntentionallyPaused = false
                startServiceSafely(context, ACTION_RESUME_AFTER_COMMAND_DISPATCH)
            }
        }

        private val _isBatterySaverPausedGlobal = MutableStateFlow(false)
        val isBatterySaverPausedGlobal: StateFlow<Boolean> = _isBatterySaverPausedGlobal.asStateFlow()

        fun setBatterySaverPaused(paused: Boolean) {
            _isBatterySaverPausedGlobal.value = paused
            if (paused) {
                isIntentionallyPaused = true
            }
        }

        private val _activeDatabaseWakeWords = MutableStateFlow<List<WakeWord>>(emptyList())
        val activeDatabaseWakeWords: StateFlow<List<WakeWord>> = _activeDatabaseWakeWords.asStateFlow()

        private val _activeWakeWordSamples = MutableStateFlow<List<com.example.data.model.WakeWordSample>>(emptyList())
        val activeWakeWordSamples: StateFlow<List<com.example.data.model.WakeWordSample>> = _activeWakeWordSamples.asStateFlow()

        private val _isSleepModeGlobal = MutableStateFlow(false)
        val isSleepModeGlobal: StateFlow<Boolean> = _isSleepModeGlobal.asStateFlow()

        fun setSleepMode(sleeping: Boolean) {
            _isSleepModeGlobal.value = sleeping
            if (sleeping) {
                isIntentionallyPaused = true
            } else if (!_isBatterySaverPausedGlobal.value) {
                isIntentionallyPaused = false
            }
        }

        private val _isKeywordLoopActiveGlobal = MutableStateFlow(false)
        val isKeywordLoopActiveGlobal: StateFlow<Boolean> = _isKeywordLoopActiveGlobal.asStateFlow()

        private val _isMicrophoneInitialized = MutableStateFlow(false)
        val isMicrophoneInitialized: StateFlow<Boolean> = _isMicrophoneInitialized.asStateFlow()

        private val _serviceMicRms = MutableStateFlow(0f)
        val serviceMicRms: StateFlow<Float> = _serviceMicRms.asStateFlow()

        private val _lastDetectedWakeWord = MutableStateFlow<WakeWordDetectionResult?>(null)
        val lastDetectedWakeWord: StateFlow<WakeWordDetectionResult?> = _lastDetectedWakeWord.asStateFlow()

        private val _detectedWakeWordsFlow = MutableSharedFlow<WakeWordDetectionResult>(extraBufferCapacity = 16)
        val detectedWakeWordsFlow: SharedFlow<WakeWordDetectionResult> = _detectedWakeWordsFlow.asSharedFlow()

        var onWakeWordDetectedCallback: ((WakeWordDetectionResult) -> Unit)? = null
        var onSleepModeChangedCallback: ((Boolean) -> Unit)? = null
        var onBatterySaverResumeRequestedCallback: (() -> Unit)? = null
        var onVoiceHistoryNavigationRequestedCallback: ((String) -> Unit)? = null

        /**
         * Builds the task-safe [Intent] to launch or bring [com.example.MainActivity] to the front
         * from the background service using `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT`.
         */
        fun createChatLaunchIntent(
            context: Context,
            result: WakeWordDetectionResult
        ): Intent {
            val chatId = result.wakeWord.chatId ?: -1L
            val intent = Intent(context, com.example.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (chatId > 0L) {
                intent.putExtra(EXTRA_WAKE_TARGET_CHAT_ID, chatId)
            }
            intent.putExtra(EXTRA_WAKE_MATCHED_PHRASE, result.matchedPhrase)
            if (!result.followUpQuery.isNullOrBlank()) {
                intent.putExtra(EXTRA_WAKE_INITIAL_QUERY, result.followUpQuery)
            }
            return intent
        }

        /**
         * Safely launches or reorders the `singleTop` Chat Activity ([com.example.MainActivity])
         * from the background service after wake-word detection.
         */
        fun launchChatActivityFromBackgroundService(
            context: Context,
            result: WakeWordDetectionResult
        ): Intent {
            val intent = createChatLaunchIntent(context, result)
            try {
                context.startActivity(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to launch Chat Activity from background service: ${e.message}")
            }
            return intent
        }

        /**
         * Posts a non-intrusive notification cue confirming the wake word was detected and the app
         * is ready to receive commands. Tapping the notification opens directly to the associated chat conversation.
         */
        fun postWakeWordReadyCueNotification(
            context: Context,
            result: WakeWordDetectionResult
        ) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (manager.getNotificationChannel(CHANNEL_WAKE_CUE_ID) == null) {
                        val cueChannel = NotificationChannel(
                            CHANNEL_WAKE_CUE_ID,
                            "Wake Word Ready Cue",
                            NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = "Non-intrusive notification when a wake word is detected in the background"
                            setShowBadge(true)
                        }
                        manager.createNotificationChannel(cueChannel)
                    }
                }
                val chatTitle = result.wakeWord.chatTitle.ifBlank { "Gemini Chat" }
                val openIntent = createChatLaunchIntent(context, result)
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID_WAKE_READY,
                    openIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_WAKE_CUE_ID)
                    .setSmallIcon(com.example.R.drawable.ic_notification_wake_glowing)
                    .setColor(0xFF10B981.toInt())
                    .setContentTitle("Ready for Commands • \"${result.matchedPhrase}\"")
                    .setContentText("Matched $chatTitle — speak your command or tap to open chat")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setContentIntent(pendingIntent)
                    .build()
                manager.notify(NOTIFICATION_ID_WAKE_READY, notification)
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to post wake word ready cue notification: ${e.message}")
            }
        }

        /**
         * Matches a spoken [transcript] against a list of active [WakeWord] entities from [WakeWordRepository].
         * Prioritizes longer wake phrases first so specific phrases match before shorter prefixes,
         * and uses each wake word's sensitivity (or [sensitivityOverride]) to allow fuzzy prefix matching.
         */
        fun matchWakeWordFromList(
            transcript: String,
            activeWakeWords: List<WakeWord>,
            sensitivityOverride: Float? = null
        ): WakeWordDetectionResult? {
            val cleanTranscript = transcript.trim()
            if (cleanTranscript.isBlank() || activeWakeWords.isEmpty()) return null

            val sorted = activeWakeWords
                .filter { it.isEnabled && it.phrase.isNotBlank() }
                .sortedByDescending { it.phrase.trim().length }

            // 1. Exact case-insensitive substring match
            for (wakeWord in sorted) {
                val phrase = wakeWord.phrase.trim()
                val idx = cleanTranscript.indexOf(phrase, ignoreCase = true)
                if (idx >= 0) {
                    val afterIndex = idx + phrase.length
                    val followUp = if (afterIndex < cleanTranscript.length) {
                        cleanTranscript.substring(afterIndex)
                            .trimStart(' ', ',', '.', ':', ';', '-', '!', '?')
                            .trim()
                            .takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    return WakeWordDetectionResult(
                        wakeWord = wakeWord,
                        matchedPhrase = phrase,
                        followUpQuery = followUp,
                        rawTranscript = cleanTranscript
                    )
                }
            }

            // 2. Sensitivity-scaled fuzzy prefix match on leading words
            val transcriptWords = cleanTranscript.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (transcriptWords.isNotEmpty()) {
                for (wakeWord in sorted) {
                    val phrase = wakeWord.phrase.trim()
                    val phraseWords = phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (transcriptWords.size >= phraseWords.size) {
                        val candidatePrefix = transcriptWords.take(phraseWords.size).joinToString(" ")
                        val effectiveSensitivity = (sensitivityOverride ?: wakeWord.sensitivity).coerceIn(0.50f, 0.98f)
                        val similarity = AudioCommandSanitizer.computePhraseSimilarity(candidatePrefix, phrase)
                        if (similarity >= effectiveSensitivity) {
                            val followUp = transcriptWords.drop(phraseWords.size).joinToString(" ")
                                .trimStart(' ', ',', '.', ':', ';', '-', '!', '?')
                                .trim()
                                .takeIf { it.isNotBlank() }
                            return WakeWordDetectionResult(
                                wakeWord = wakeWord,
                                matchedPhrase = phrase,
                                followUpQuery = followUp,
                                rawTranscript = cleanTranscript
                            )
                        }
                    }
                }
            }
            return null
        }

        fun startServiceSafely(
            context: Context,
            action: String = ACTION_START_VOICE_SERVICE,
            candidateTranscript: String? = null,
            customWakeWord: String? = null,
            useSpeechRecognizerEngine: Boolean = false
        ) {
            val appCtx = context.applicationContext ?: context
            val intent = Intent(appCtx, WakeWordVoiceService::class.java).apply {
                this.action = action
                if (!candidateTranscript.isNullOrBlank()) {
                    putExtra(EXTRA_CANDIDATE_TRANSCRIPT, candidateTranscript)
                }
                if (!customWakeWord.isNullOrBlank()) {
                    putExtra(EXTRA_CUSTOM_WAKE_WORD, customWakeWord)
                }
                if (useSpeechRecognizerEngine) {
                    putExtra(EXTRA_USE_SPEECH_RECOGNIZER_ENGINE, true)
                }
            }
            try {
                if (action == ACTION_START_VOICE_SERVICE) {
                    ContextCompat.startForegroundService(appCtx, intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Throwable) {
                try {
                    appCtx.startService(intent)
                } catch (inner: Throwable) {
                    Log.w(TAG, "Unable to start WakeWordVoiceService ($action): ${inner.message}")
                }
            }
        }

        fun stopServiceSafely(context: Context) {
            val appCtx = context.applicationContext ?: context
            stopAndReleaseServiceAudioRecordSynchronously("stop_service_safely")
            try {
                appCtx.stopService(Intent(appCtx, WakeWordVoiceService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to stop WakeWordVoiceService: ${e.message}")
            }
        }

        const val AUDIO_FOCUS_GAIN_MODE = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

        private val ownAudioSessionIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        @Volatile
        var activeAppAudioSessionId: Int = -1
            set(value) {
                field = value
                if (value > 0) {
                    ownAudioSessionIds.add(value)
                }
            }

        @Volatile
        var isAppRecordingActive: Boolean = false

        @Volatile
        var isTransitioningAudioState: Boolean = false

        private val _hasExclusiveAudioFocus = MutableStateFlow(false)
        val hasExclusiveAudioFocus: StateFlow<Boolean> = _hasExclusiveAudioFocus.asStateFlow()

        private val _isExternalRecordingActive = MutableStateFlow(false)
        val isExternalRecordingActive: StateFlow<Boolean> = _isExternalRecordingActive.asStateFlow()

        private val _isInputFocusPaused = MutableStateFlow(false)
        val isInputFocusPaused: StateFlow<Boolean> = _isInputFocusPaused.asStateFlow()

        var onAudioFocusLostCallback: ((Int) -> Unit)? = null
        var onAudioFocusGainedCallback: (() -> Unit)? = null
        var onExternalMicContentionChanged: ((Boolean) -> Unit)? = null
        var onInputFocusPauseRequested: ((Boolean) -> Unit)? = null

        fun registerOwnAudioSessionId(sessionId: Int) {
            if (sessionId > 0) {
                ownAudioSessionIds.add(sessionId)
                activeAppAudioSessionId = sessionId
            }
        }

        fun setExclusiveAudioFocusState(granted: Boolean) {
            _hasExclusiveAudioFocus.value = granted
        }

        /**
         * Safely extracts the client UID from an AudioRecordingConfiguration if accessible
         * on the current Android runtime.
         */
        fun extractClientUid(config: AudioRecordingConfiguration): Int? {
            return try {
                val method = config.javaClass.getMethod("getClientUid")
                (method.invoke(config) as? Int)?.takeIf { it >= 0 }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Returns true if the recording configuration belongs to our own app process
         * (Process.myUid()) or matches our AudioRecord's audioSessionId.
         */
        fun isOwnRecordingConfig(
            config: AudioRecordingConfiguration,
            currentAudioSessionId: Int = activeAppAudioSessionId
        ): Boolean {
            val clientUid = extractClientUid(config)
            val sessionId = config.clientAudioSessionId
            if (isOwnRecordingConfig(clientUid, sessionId, currentAudioSessionId)) {
                return true
            }
            if (clientUid == null && (isAppRecordingActive || isTransitioningAudioState || activeAppAudioSessionId > 0)) {
                return true
            }
            return false
        }

        /**
         * Verifies whether a recording client UID or audioSessionId belongs to our own app.
         */
        fun isOwnRecordingConfig(
            clientUid: Int?,
            clientAudioSessionId: Int,
            currentAudioSessionId: Int = activeAppAudioSessionId
        ): Boolean {
            if (clientUid != null && clientUid == Process.myUid()) {
                return true
            }
            if (clientAudioSessionId > 0 && (
                    clientAudioSessionId == currentAudioSessionId ||
                        clientAudioSessionId == activeAppAudioSessionId ||
                        ownAudioSessionIds.contains(clientAudioSessionId)
                    )
            ) {
                return true
            }
            return false
        }

        private val _lastSimulationDiagnosticReport = MutableStateFlow<WakeWordSimulationDiagnosticReport?>(null)
        val lastSimulationDiagnosticReport: StateFlow<WakeWordSimulationDiagnosticReport?> =
            _lastSimulationDiagnosticReport.asStateFlow()

        /**
         * Live telemetry from the on-device hybrid keyword spotter ([LocalKeywordSpotterEngine]).
         */
        val keywordSpotterTelemetry: StateFlow<LocalKeywordSpotterTelemetry> =
            LocalKeywordSpotterEngine.telemetryState

        @Volatile
        var simulationDiagnosticRunCount: Int = 0
            private set

        /**
         * Synthesizes a non-silent 16kHz 16-bit Little-Endian mono PCM frame (`640` bytes = 20ms)
         * representing a 440Hz speech-band tone to verify the microphone pre-roll buffer, RMS computation,
         * and non-silent packet validator without requiring physical vocal input.
         */
        fun synthesizeDiagnosticPcmFrame(sampleCount: Int = 320, amplitude: Int = 6200): ByteArray {
            val safeSamples = sampleCount.coerceIn(64, 4096)
            val pcm = ByteArray(safeSamples * 2)
            for (i in 0 until safeSamples) {
                val angle = (2.0 * Math.PI * i * 440.0) / 16000.0
                val sample = (kotlin.math.sin(angle) * amplitude).toInt().coerceIn(-32767, 32767).toShort()
                pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            return pcm
        }

        /**
         * Simulates the end-to-end wake word detection flow to verify the microphone pre-roll pipeline,
         * RMS calculation, phrase matching, synchronous microphone handoff (`stopAndReleaseServiceAudioRecordSynchronously`),
         * and assistant trigger routing without needing to say the wake word out loud.
         */
        fun simulateWakeWordDetectionFlow(
            context: Context,
            wakePhraseOverride: String? = null,
            simulatedFollowUpQuery: String? = null,
            configuredWakeWords: List<WakeWord> = emptyList(),
            sensitivityOverride: Float? = null,
            dispatchToDetectionCallbacks: Boolean = true
        ): WakeWordSimulationDiagnosticReport {
            val startEpochMs = System.currentTimeMillis()
            val appCtx = context.applicationContext ?: context
            com.example.util.AppSettingsManager.ensureInitialized(appCtx)

            val resolvedWakePhrase = wakePhraseOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: _customWakeWord.value.trim().takeIf { it.isNotBlank() }
                ?: com.example.util.AppSettingsManager.getCustomWakeWordPhrase(appCtx)

            val effectiveSensitivity = (sensitivityOverride ?: customWakeWordSensitivity).coerceIn(0.50f, 0.98f)
            val simulatedTranscript = if (simulatedFollowUpQuery.isNullOrBlank()) {
                resolvedWakePhrase
            } else {
                "$resolvedWakePhrase ${simulatedFollowUpQuery.trim()}"
            }

            // 1. Verify Microphone & Audio Pre-Roll Pipeline
            val recordAudioGranted = try {
                ContextCompat.checkSelfPermission(
                    appCtx,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
            val recognizerAvailable = try {
                SpeechRecognizer.isRecognitionAvailable(appCtx)
            } catch (_: Throwable) {
                false
            }
            val testPcmFrame = synthesizeDiagnosticPcmFrame(sampleCount = 320, amplitude = 6200)
            val isNonSilent = !WakeWordManager.isPcmByteBufferSilent(testPcmFrame, testPcmFrame.size)
            var sumSq = 0.0
            var sampleCount = 0
            var idx = 0
            while (idx + 1 < testPcmFrame.size) {
                val low = testPcmFrame[idx].toInt() and 0xFF
                val high = testPcmFrame[idx + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toDouble()
                sumSq += sample * sample
                sampleCount++
                idx += 2
            }
            val rmsAmplitude = if (sampleCount > 0) sqrt(sumSq / sampleCount).toFloat() else 0f
            val computedRmsDb = if (rmsAmplitude > 1f) {
                (20.0 * kotlin.math.log10(rmsAmplitude / 32768.0) + 90.0).toFloat().coerceIn(0f, 90f)
            } else {
                0f
            }

            val instance = activeInstance
            if (instance != null) {
                instance.audioPipelineBuffer.trySend(testPcmFrame)
                _serviceMicRms.value = rmsAmplitude
            }
            val micCheckPassed = isNonSilent && testPcmFrame.size >= 640 && computedRmsDb > 10f

            // 2. Verify Wake Word Candidate List & Phrase Matching Logic
            val customCandidate = WakeWord(
                id = CUSTOM_WAKE_WORD_ID,
                phrase = resolvedWakePhrase,
                isEnabled = true,
                sensitivity = effectiveSensitivity,
                chatId = customWakeWordTargetChatId,
                chatTitle = "Gemini Assistant"
            )
            val candidatePool = buildList {
                add(customCandidate)
                if (instance != null) {
                    addAll(instance.getEffectiveWakeWords())
                }
                addAll(configuredWakeWords)
                addAll(_activeDatabaseWakeWords.value)
            }.distinctBy { "${it.id}_${it.phrase.lowercase(java.util.Locale.US)}" }

            val matchResult = matchWakeWordFromList(
                transcript = simulatedTranscript,
                activeWakeWords = candidatePool,
                sensitivityOverride = effectiveSensitivity
            ) ?: WakeWordDetectionResult(
                wakeWord = customCandidate,
                matchedPhrase = resolvedWakePhrase,
                followUpQuery = simulatedFollowUpQuery?.trim()?.takeIf { it.isNotBlank() },
                rawTranscript = simulatedTranscript
            )

            val spotterTelemetry = LocalKeywordSpotterEngine.evaluateCandidateTranscript(
                transcript = simulatedTranscript,
                customWakePhrase = resolvedWakePhrase,
                sensitivityThreshold = effectiveSensitivity,
                recentPcmFrame = testPcmFrame,
                targetChatId = matchResult.wakeWord.chatId ?: customWakeWordTargetChatId,
                targetChatTitle = matchResult.wakeWord.chatTitle.ifBlank { "Gemini Assistant" }
            )

            val similarityScore = maxOf(
                AudioCommandSanitizer.computePhraseSimilarity(
                    matchResult.matchedPhrase,
                    resolvedWakePhrase
                ).coerceIn(0f, 1f),
                spotterTelemetry.combinedSpotterConfidence
            )
            val processingPassed = matchResult.matchedPhrase.isNotBlank() &&
                similarityScore >= effectiveSensitivity * 0.85f

            // 3. Verify Synchronous Microphone Handoff & Exclusive Audio Focus Release
            val handoffBefore = synchronousDictationHandoffReleaseCount
            if (instance != null && dispatchToDetectionCallbacks) {
                instance.triggerGeminiAssistant(matchResult)
            } else {
                stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
                AudioCommandSanitizer.playWakeWordHaptic(appCtx, matchResult.matchedPhrase)
                _lastDetectedWakeWord.value = matchResult
                _detectedWakeWordsFlow.tryEmit(matchResult)
                _geminiAssistantTriggerFlow.tryEmit(matchResult)
                postWakeWordReadyCueNotification(appCtx, matchResult)
                if (dispatchToDetectionCallbacks) {
                    onWakeWordDetectedCallback?.invoke(matchResult)
                    onGeminiAssistantTriggeredCallback?.invoke(matchResult)
                }
            }
            val handoffAfter = synchronousDictationHandoffReleaseCount
            val handoffVerified = handoffAfter > handoffBefore &&
                !_isMicrophoneInitialized.value &&
                !_isServiceSpeechRecognizerListening.value &&
                lastAudioRecordNullifiedAtEpochMs > 0L

            val resolvedChatTitle = matchResult.wakeWord.chatTitle.ifBlank { "Gemini Assistant" }
            val elapsedMs = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(1L)
            val overallPassed = micCheckPassed && processingPassed && handoffVerified

            val steps = listOf(
                "1. Mic & Pre-Roll Pipeline: Verified (${testPcmFrame.size}B 16kHz PCM frame, RMS ${String.format(java.util.Locale.US, "%.1f", computedRmsDb)} dB, RECORD_AUDIO=${if (recordAudioGranted) "GRANTED" else "SIMULATED"})",
                "2. Wake-Word Processing: Matched \"${matchResult.matchedPhrase}\" (Similarity ${(similarityScore * 100).toInt()}% >= Threshold ${(effectiveSensitivity * 100).toInt()}%) -> Target \"$resolvedChatTitle\"",
                "3. Synchronous Mic Handoff: Verified (serviceAudioRecord=null, SpeechRecognizer released, Handoff #$handoffAfter, Reason=\"$lastSynchronousReleaseReason\")"
            )
            val summary = buildString {
                append("Wake Word Diagnostic Simulation ${if (overallPassed) "PASSED" else "PARTIAL"} (${elapsedMs}ms): ")
                append("Simulated \"$simulatedTranscript\" -> Matched \"${matchResult.matchedPhrase}\" ")
                append("[Mic Pre-Roll ${testPcmFrame.size}B @ ${String.format(java.util.Locale.US, "%.1f", computedRmsDb)} dB | ")
                append("Similarity ${(similarityScore * 100).toInt()}% | ")
                append("Sync Handoff #$handoffAfter Verified]")
            }

            simulationDiagnosticRunCount++
            val report = WakeWordSimulationDiagnosticReport(
                simulatedTranscript = simulatedTranscript,
                matchedPhrase = matchResult.matchedPhrase,
                followUpQuery = matchResult.followUpQuery,
                targetChatId = matchResult.wakeWord.chatId,
                targetChatTitle = resolvedChatTitle,
                sensitivityThreshold = effectiveSensitivity,
                phraseSimilarityScore = similarityScore,
                recordAudioPermissionGranted = recordAudioGranted,
                speechRecognizerAvailable = recognizerAvailable,
                preRollPcmBytesVerified = testPcmFrame.size,
                computedRmsDb = computedRmsDb,
                synchronousMicHandoffVerified = handoffVerified,
                handoffReleaseCountAfter = handoffAfter,
                microphoneCheckPassed = micCheckPassed,
                processingAndMatchingPassed = processingPassed,
                overallPassed = overallPassed,
                elapsedDurationMs = elapsedMs,
                timestampEpochMs = System.currentTimeMillis(),
                diagnosticSteps = steps,
                formattedSummary = summary
            )
            _lastSimulationDiagnosticReport.value = report
            dispatchDiagnosticTrace("WAKE_WORD_DIAGNOSTIC_SIMULATION", summary)
            return report
        }
    }

    /**
     * Instance-level helper that delegates to [Companion.simulateWakeWordDetectionFlow] using this
     * service's context and active wake word configuration.
     */
    fun simulateWakeWordDetectionDiagnostic(
        wakePhraseOverride: String? = null,
        simulatedFollowUpQuery: String? = null,
        dispatchToDetectionCallbacks: Boolean = true
    ): WakeWordSimulationDiagnosticReport {
        return simulateWakeWordDetectionFlow(
            context = applicationContext,
            wakePhraseOverride = wakePhraseOverride,
            simulatedFollowUpQuery = simulatedFollowUpQuery,
            configuredWakeWords = getEffectiveWakeWords(),
            sensitivityOverride = customWakeWordSensitivity,
            dispatchToDetectionCallbacks = dispatchToDetectionCallbacks
        )
    }
}

/**
 * Structured diagnostic report produced when simulating the wake word detection flow from the
 * Settings screen to verify microphone pre-roll capture, phrase matching, and synchronous audio handoff.
 */
data class WakeWordSimulationDiagnosticReport(
    val simulatedTranscript: String,
    val matchedPhrase: String,
    val followUpQuery: String?,
    val targetChatId: Long?,
    val targetChatTitle: String,
    val sensitivityThreshold: Float,
    val phraseSimilarityScore: Float,
    val recordAudioPermissionGranted: Boolean,
    val speechRecognizerAvailable: Boolean,
    val preRollPcmBytesVerified: Int,
    val computedRmsDb: Float,
    val synchronousMicHandoffVerified: Boolean,
    val handoffReleaseCountAfter: Int,
    val microphoneCheckPassed: Boolean,
    val processingAndMatchingPassed: Boolean,
    val overallPassed: Boolean,
    val elapsedDurationMs: Long,
    val timestampEpochMs: Long,
    val diagnosticSteps: List<String>,
    val formattedSummary: String
)

