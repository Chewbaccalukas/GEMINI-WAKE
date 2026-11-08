package com.example.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Android [TextToSpeech] engine manager for speaking completed Gemini responses aloud:
 *
 * 1. **High-Quality English Synthesis**:
 *    - Initializes [TextToSpeech] with [Locale.US] (falling back to [Locale.ENGLISH] if needed)
 *      and selects a high-quality English voice when available.
 * 2. **Automatic Post-Stream Playback**:
 *    - Speaks the completed assistant response text aloud via `QUEUE_FLUSH` (or chunked `QUEUE_ADD`
 *      for long multi-paragraph replies).
 * 3. **Explicit 300ms Acoustic Settling Delay**:
 *    - Listens for [UtteranceProgressListener.onDone] and enforces a mandatory
 *      [ACOUSTIC_SETTLING_DELAY_MS] (`300ms`) delay after playback finishes before invoking
 *      [onPlaybackSettledAndReadyToListen] (`SpeechRecognitionCoordinator.startListening()`),
 *      preventing speaker tail audio from feeding back into the microphone.
 * 4. **Instant Barge-In Support**:
 *    - If the user begins speaking while TTS is active, [bargeInAndStop] immediately calls
 *      `tts.stop()`, cancels any pending settling timer, and switches back to listening.
 */
class TextToSpeechManager(
    context: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackSettledAndReadyToListen: () -> Unit = {},
    private val onBargeInStopped: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeechManager"

        /**
         * Explicit 300ms acoustic settling delay after TTS playback completes before
         * restarting `SpeechRecognitionCoordinator.startListening()`.
         */
        const val ACOUSTIC_SETTLING_DELAY_MS = 300L

        /**
         * Maximum character length per single `TextToSpeech.speak()` utterance chunk.
         */
        private const val MAX_UTTERANCE_CHARS = 3500

        /**
         * Strips markdown formatting symbols (`**`, `*`, `` ` ``, `#`) so spoken synthesis sounds natural.
         */
        fun sanitizeMarkdownForSpeech(rawText: String): String {
            if (rawText.isBlank()) return ""
            return rawText
                .replace(Regex("```[\\s\\S]*?```"), " code block ")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
                .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var settlingJob: Job? = null
    private var pendingTextBeforeInit: String? = null
    private var currentVolume: Float = 1.0f

    @Volatile
    private var activeFinalUtteranceId: String? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech initialization failed in environment: ${e.message}")
            _isInitialized.value = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val localeResult = engine.setLanguage(Locale.US)
            if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                localeResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.ENGLISH)
            }
            // Select a high-quality English voice when available
            try {
                val bestVoice = engine.voices
                    ?.filter { voice ->
                        voice.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                            !voice.isNetworkConnectionRequired
                    }
                    ?.maxByOrNull { it.quality }
                if (bestVoice != null) {
                    engine.voice = bestVoice
                }
            } catch (_: Exception) {
            }
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.0f)
            engine.setOnUtteranceProgressListener(utteranceProgressListener)
            _isInitialized.value = true

            val queued = pendingTextBeforeInit
            pendingTextBeforeInit = null
            if (!queued.isNullOrBlank()) {
                speak(queued)
            }
        } else {
            Log.w(TAG, "TextToSpeech onInit returned status=$status")
            _isInitialized.value = false
        }
    }

    private val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            settlingJob?.cancel()
            settlingJob = null
            _isSpeaking.value = true
            scope.launch(mainDispatcher) {
                onPlaybackStarted()
            }
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null || utteranceId == activeFinalUtteranceId) {
                scheduleAcousticSettlingBeforeListening()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            scheduleAcousticSettlingBeforeListening()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            scheduleAcousticSettlingBeforeListening()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _isSpeaking.value = false
            if (!interrupted) {
                scheduleAcousticSettlingBeforeListening()
            }
        }
    }

    /**
     * Enforces an explicit 300ms acoustic settling delay after `UtteranceProgressListener.onDone`
     * before invoking [onPlaybackSettledAndReadyToListen] (`SpeechRecognitionCoordinator.startListening()`).
     */
    private fun scheduleAcousticSettlingBeforeListening() {
        settlingJob?.cancel()
        settlingJob = scope.launch(mainDispatcher) {
            _isSpeaking.value = false
            delay(ACOUSTIC_SETTLING_DELAY_MS)
            onPlaybackSettledAndReadyToListen()
        }
    }

    /**
     * Speaks [text] aloud using Android [TextToSpeech].
     * When playback completes, waits [ACOUSTIC_SETTLING_DELAY_MS] (300ms) before notifying
     * [onPlaybackSettledAndReadyToListen].
     */
    fun speak(text: String) {
        val cleanText = sanitizeMarkdownForSpeech(text)
        if (cleanText.isBlank()) {
            scheduleAcousticSettlingBeforeListening()
            return
        }
        _lastSpokenText.value = cleanText
        settlingJob?.cancel()
        settlingJob = null

        val engine = tts
        if (engine == null || !_isInitialized.value) {
            pendingTextBeforeInit = cleanText
            // If TTS is unavailable in headless/test container, still fire the 300ms settling hook
            if (engine == null) {
                scheduleAcousticSettlingBeforeListening()
            }
            return
        }

        try {
            val chunks = chunkTextForUtterances(cleanText)
            if (chunks.isEmpty()) {
                scheduleAcousticSettlingBeforeListening()
                return
            }
            _isSpeaking.value = true
            onPlaybackStarted()

            chunks.forEachIndexed { index, chunk ->
                val utteranceId = "gemini_tts_${UUID.randomUUID()}"
                if (index == chunks.lastIndex) {
                    activeFinalUtteranceId = utteranceId
                }
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume.coerceIn(0f, 1f))
                }
                val result = engine.speak(chunk, queueMode, params, utteranceId)
                if (result == TextToSpeech.ERROR && index == chunks.lastIndex) {
                    scheduleAcousticSettlingBeforeListening()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during TextToSpeech.speak: ${e.message}")
            scheduleAcousticSettlingBeforeListening()
        }
    }

    /**
     * Barge-in handler: if the user begins speaking while TTS is active, immediately calls
     * `tts.stop()`, cancels any pending settling timer, and invokes [onBargeInStopped] so
     * recognition can immediately capture the user's speech.
     */
    fun bargeInAndStop() {
        settlingJob?.cancel()
        settlingJob = null
        pendingTextBeforeInit = null
        activeFinalUtteranceId = null
        val wasSpeaking = _isSpeaking.value
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS during barge-in: ${e.message}")
        }
        if (wasSpeaking) {
            scope.launch(mainDispatcher) {
                onBargeInStopped()
            }
        }
    }

    /**
     * Stops active TTS synthesis immediately.
     */
    fun stop() {
        settlingJob?.cancel()
        settlingJob = null
        pendingTextBeforeInit = null
        activeFinalUtteranceId = null
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        _isInitialized.value = false
    }

    private fun chunkTextForUtterances(text: String): List<String> {
        if (text.length <= MAX_UTTERANCE_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_UTTERANCE_CHARS) {
                chunks.add(remaining.trim())
                break
            }
            val splitIdx = remaining.lastIndexOf(". ", MAX_UTTERANCE_CHARS)
                .takeIf { it > 200 }
                ?: remaining.lastIndexOf(' ', MAX_UTTERANCE_CHARS).takeIf { it > 200 }
                ?: MAX_UTTERANCE_CHARS
            chunks.add(remaining.substring(0, splitIdx + 1).trim())
            remaining = remaining.substring(splitIdx + 1).trim()
        }
        return chunks.filter { it.isNotBlank() }
    }
}
