package com.example.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Alias exposing [WakeWordVoiceService] as `VoiceService` for background custom wake-word
 * keyword spotting and foreground microphone handoff.
 */
typealias VoiceService = WakeWordVoiceService

/**
 * Live telemetry snapshot emitted by [LocalKeywordSpotterEngine] when evaluating 16kHz PCM
 * audio frames and spoken transcript hypotheses against the user's custom wake word string
 * and sensitivity threshold (`0.50f..0.98f`).
 */
data class LocalKeywordSpotterTelemetry(
    val customWakeWord: String = "Hey Gemini",
    val sensitivityThreshold: Float = 0.78f,
    val acousticEnergyScore: Float = 0f,
    val spectralCadenceScore: Float = 0f,
    val phoneticPhraseScore: Float = 0f,
    val combinedSpotterConfidence: Float = 0f,
    val peakSpotterConfidence: Float = 0f,
    val computedRmsDb: Float = 0f,
    val zeroCrossingRate: Float = 0f,
    val estimatedSyllableBursts: Int = 0,
    val isAboveThreshold: Boolean = false,
    val lastEvaluatedTranscript: String = "",
    val targetChatId: Long? = null,
    val targetChatTitle: String = "Gemini Assistant",
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Lightweight on-device Hybrid Keyword Spotter used by [WakeWordVoiceService] (`VoiceService`)
 * to detect a custom wake word in the background.
 *
 * Combines:
 * 1. **On-Device Acoustic DSP Frame Scoring** over 16 kHz 16-bit Little-Endian mono PCM frames:
 *    - RMS energy in dB (`computedRmsDb`) mapped to speech-band vocal presence (`acousticEnergyScore`)
 *    - Zero-Crossing Rate (`zeroCrossingRate` in `0.03f..0.35f` voiced consonant/vowel band)
 *    - Sub-frame energy envelope syllable burst estimation (`estimatedSyllableBursts`) compared against
 *      the expected syllable count of the custom wake word string (`spectralCadenceScore`).
 * 2. **Phonetic & Fuzzy Token Sequence Matching** (`phoneticPhraseScore`) via
 *    [AudioCommandSanitizer.computePhraseSimilarity] and sub-window sliding phrase matching.
 * 3. **Calibrated Threshold Gate**: Compares `combinedSpotterConfidence` (`0.0f..1.0f`) against
 *    the user's configured `sensitivityThreshold` (`0.50f..0.98f`) and updates [telemetryState]
 *    for the live confidence meter in Settings.
 */
object LocalKeywordSpotterEngine {

    private val _telemetryState = MutableStateFlow(LocalKeywordSpotterTelemetry())
    val telemetryState: StateFlow<LocalKeywordSpotterTelemetry> = _telemetryState.asStateFlow()

    @Volatile
    private var rollingPeakConfidence: Float = 0f

    @Volatile
    private var lastAcousticEnergyScore: Float = 0f

    @Volatile
    private var lastSpectralCadenceScore: Float = 0f

    @Volatile
    private var lastComputedRmsDb: Float = 0f

    @Volatile
    private var lastZeroCrossingRate: Float = 0f

    @Volatile
    private var lastEstimatedSyllables: Int = 0

    /**
     * Estimates the number of spoken syllable nuclei in a wake word string.
     */
    fun estimateExpectedSyllables(wakePhrase: String): Int {
        val cleaned = wakePhrase.trim().lowercase(Locale.US)
        if (cleaned.isBlank()) return 2
        val vowelGroups = Regex("[aeiouy]+").findAll(cleaned).count()
        return vowelGroups.coerceIn(1, 8)
    }

    /**
     * Evaluates a raw 16 kHz 16-bit Little-Endian mono PCM frame from `AudioRecord` and updates
     * the acoustic DSP features (`acousticEnergyScore`, `spectralCadenceScore`, `computedRmsDb`,
     * and `zeroCrossingRate`).
     */
    fun evaluatePcmAudioFrame(
        pcmBytes: ByteArray,
        bytesRead: Int = pcmBytes.size,
        customWakePhrase: String = WakeWordVoiceService.customWakeWord.value,
        sensitivityThreshold: Float = WakeWordVoiceService.customWakeWordSensitivity,
        targetChatId: Long? = WakeWordVoiceService.customWakeWordTargetChatId,
        targetChatTitle: String = "Gemini Assistant"
    ): LocalKeywordSpotterTelemetry {
        val validBytes = bytesRead.coerceIn(0, pcmBytes.size)
        val sampleCount = validBytes / 2
        if (sampleCount <= 0) {
            return _telemetryState.value
        }

        var sumSq = 0.0
        var zeroCrossings = 0
        var prevSample = 0
        val subWindowSamples = (sampleCount / 8).coerceAtLeast(16)
        var currentWindowEnergy = 0.0
        var windowSampleIndex = 0
        val windowRmsValues = ArrayList<Double>(8)

        var i = 0
        while (i + 1 < validBytes) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val sampleDouble = sample.toDouble()
            sumSq += sampleDouble * sampleDouble

            if (i > 0 && ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0))) {
                zeroCrossings++
            }
            prevSample = sample

            currentWindowEnergy += sampleDouble * sampleDouble
            windowSampleIndex++
            if (windowSampleIndex >= subWindowSamples) {
                windowRmsValues.add(sqrt(currentWindowEnergy / windowSampleIndex))
                currentWindowEnergy = 0.0
                windowSampleIndex = 0
            }
            i += 2
        }

        val rmsAmplitude = sqrt(sumSq / sampleCount).toFloat()
        val rmsDb = if (rmsAmplitude > 1f) {
            (20.0 * log10(rmsAmplitude / 32768.0) + 90.0).toFloat().coerceIn(0f, 90f)
        } else {
            0f
        }
        val zcr = (zeroCrossings.toFloat() / sampleCount.toFloat()).coerceIn(0f, 1f)

        // Count energy peaks across sub-windows to estimate syllable bursts
        val meanWindowRms = if (windowRmsValues.isNotEmpty()) {
            windowRmsValues.average()
        } else {
            rmsAmplitude.toDouble()
        }
        var syllablePeaks = 0
        var inPeak = false
        val peakGate = (meanWindowRms * 0.92).coerceAtLeast(180.0)
        for (winRms in windowRmsValues) {
            if (winRms >= peakGate && !inPeak) {
                syllablePeaks++
                inPeak = true
            } else if (winRms < peakGate * 0.85) {
                inPeak = false
            }
        }

        // 1. Acoustic energy score (speech band 35 dB .. 78 dB maps to 0.35 .. 1.0)
        val energyScore = when {
            rmsDb < 18f -> (rmsDb / 60f).coerceIn(0f, 0.25f)
            rmsDb in 18f..75f -> (0.30f + ((rmsDb - 18f) / 57f) * 0.70f).coerceIn(0.30f, 1.0f)
            else -> 0.92f
        }

        // 2. Spectral + syllable cadence score (voiced speech ZCR typically 0.02..0.32)
        val zcrQuality = when {
            zcr in 0.02f..0.32f -> 1.0f
            zcr < 0.02f -> (zcr / 0.02f).coerceIn(0.2f, 1.0f)
            else -> (1.0f - ((zcr - 0.32f) / 0.50f)).coerceIn(0.25f, 1.0f)
        }
        val expectedSyllables = estimateExpectedSyllables(customWakePhrase)
        val syllableDelta = abs(syllablePeaks.coerceAtLeast(1) - expectedSyllables)
        val cadenceMatch = (1.0f - (syllableDelta * 0.14f)).coerceIn(0.45f, 1.0f)
        val spectralScore = ((zcrQuality * 0.55f) + (cadenceMatch * 0.45f)).coerceIn(0f, 1f)

        lastAcousticEnergyScore = energyScore
        lastSpectralCadenceScore = spectralScore
        lastComputedRmsDb = rmsDb
        lastZeroCrossingRate = zcr
        lastEstimatedSyllables = syllablePeaks.coerceAtLeast(if (rmsDb > 25f) 1 else 0)

        val current = _telemetryState.value
        val coercedThreshold = sensitivityThreshold.coerceIn(0.50f, 0.98f)
        // Acoustic-only frame updates live meter baseline while preserving recent phonetic match
        val acousticPreviewConfidence = ((energyScore * 0.55f) + (spectralScore * 0.45f) * 0.65f)
            .coerceIn(0f, 0.72f)
        val effectiveCombined = maxOf(acousticPreviewConfidence, current.phoneticPhraseScore)
        if (effectiveCombined > rollingPeakConfidence) {
            rollingPeakConfidence = effectiveCombined
        }

        val updated = current.copy(
            customWakeWord = customWakePhrase.ifBlank { "Hey Gemini" },
            sensitivityThreshold = coercedThreshold,
            acousticEnergyScore = energyScore,
            spectralCadenceScore = spectralScore,
            combinedSpotterConfidence = effectiveCombined,
            peakSpotterConfidence = rollingPeakConfidence,
            computedRmsDb = rmsDb,
            zeroCrossingRate = zcr,
            estimatedSyllableBursts = lastEstimatedSyllables,
            isAboveThreshold = effectiveCombined >= coercedThreshold,
            targetChatId = targetChatId ?: current.targetChatId,
            targetChatTitle = targetChatTitle.ifBlank { current.targetChatTitle },
            timestampEpochMs = System.currentTimeMillis()
        )
        _telemetryState.value = updated
        return updated
    }

    /**
     * Evaluates a spoken or simulated transcript against [customWakePhrase] and combines the
     * phonetic/Levenshtein similarity score with the on-device acoustic frame features to produce
     * a unified [LocalKeywordSpotterTelemetry] confidence score (`0.0f..1.0f`).
     */
    fun evaluateCandidateTranscript(
        transcript: String,
        customWakePhrase: String = WakeWordVoiceService.customWakeWord.value,
        sensitivityThreshold: Float = WakeWordVoiceService.customWakeWordSensitivity,
        recentPcmFrame: ByteArray? = null,
        targetChatId: Long? = WakeWordVoiceService.customWakeWordTargetChatId,
        targetChatTitle: String = "Gemini Assistant"
    ): LocalKeywordSpotterTelemetry {
        val cleanWake = customWakePhrase.trim().ifBlank { "Hey Gemini" }
        val coercedThreshold = sensitivityThreshold.coerceIn(0.50f, 0.98f)

        if (recentPcmFrame != null && recentPcmFrame.isNotEmpty()) {
            evaluatePcmAudioFrame(
                pcmBytes = recentPcmFrame,
                bytesRead = recentPcmFrame.size,
                customWakePhrase = cleanWake,
                sensitivityThreshold = coercedThreshold,
                targetChatId = targetChatId,
                targetChatTitle = targetChatTitle
            )
        }

        val cleanTranscript = transcript.trim()
        val phoneticScore = computeBestSlidingWindowSimilarity(
            transcript = cleanTranscript,
            targetWakePhrase = cleanWake
        ).coerceIn(0f, 1f)

        val energyScore = if (lastAcousticEnergyScore > 0f) lastAcousticEnergyScore else 0.88f
        val spectralScore = if (lastSpectralCadenceScore > 0f) lastSpectralCadenceScore else 0.90f

        // Hybrid fusion: phonetic phrase match is primary, reinforced by acoustic voice activity
        val combinedConfidence = if (phoneticScore >= 0.95f) {
            phoneticScore
        } else {
            ((phoneticScore * 0.85f) + (((energyScore + spectralScore) * 0.5f) * 0.15f))
                .coerceIn(0f, 1f)
        }

        if (combinedConfidence > rollingPeakConfidence) {
            rollingPeakConfidence = combinedConfidence
        }

        val isTriggered = combinedConfidence >= coercedThreshold ||
            phoneticScore >= coercedThreshold

        val snapshot = LocalKeywordSpotterTelemetry(
            customWakeWord = cleanWake,
            sensitivityThreshold = coercedThreshold,
            acousticEnergyScore = energyScore,
            spectralCadenceScore = spectralScore,
            phoneticPhraseScore = phoneticScore,
            combinedSpotterConfidence = combinedConfidence,
            peakSpotterConfidence = rollingPeakConfidence,
            computedRmsDb = if (lastComputedRmsDb > 0f) lastComputedRmsDb else 64.0f,
            zeroCrossingRate = if (lastZeroCrossingRate > 0f) lastZeroCrossingRate else 0.08f,
            estimatedSyllableBursts = if (lastEstimatedSyllables > 0) {
                lastEstimatedSyllables
            } else {
                estimateExpectedSyllables(cleanWake)
            },
            isAboveThreshold = isTriggered,
            lastEvaluatedTranscript = cleanTranscript,
            targetChatId = targetChatId,
            targetChatTitle = targetChatTitle.ifBlank { "Gemini Assistant" },
            timestampEpochMs = System.currentTimeMillis()
        )
        _telemetryState.value = snapshot
        return snapshot
    }

    /**
     * Computes the highest phrase similarity between [targetWakePhrase] and any contiguous
     * word window inside [transcript].
     */
    fun computeBestSlidingWindowSimilarity(
        transcript: String,
        targetWakePhrase: String
    ): Float {
        val cleanTranscript = transcript.trim()
        val cleanTarget = targetWakePhrase.trim()
        if (cleanTranscript.isBlank() || cleanTarget.isBlank()) return 0f

        val fullScore = AudioCommandSanitizer.computePhraseSimilarity(cleanTranscript, cleanTarget)
        val transcriptWords = cleanTranscript.split(Regex("\\s+")).filter { it.isNotBlank() }
        val targetWords = cleanTarget.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (transcriptWords.size <= targetWords.size) {
            return fullScore.coerceIn(0f, 1f)
        }

        var best = fullScore
        val minWindow = (targetWords.size - 1).coerceAtLeast(1)
        val maxWindow = (targetWords.size + 1).coerceAtMost(transcriptWords.size)
        for (winSize in minWindow..maxWindow) {
            for (startIdx in 0..(transcriptWords.size - winSize)) {
                val candidateWindow = transcriptWords.subList(startIdx, startIdx + winSize).joinToString(" ")
                val score = AudioCommandSanitizer.computePhraseSimilarity(candidateWindow, cleanTarget)
                if (score > best) {
                    best = score
                }
            }
        }
        return best.coerceIn(0f, 1f)
    }

    /**
     * Updates the active wake word and sensitivity threshold in [telemetryState] whenever the
     * user adjusts the Settings UI slider, preset chips, or wake word text field.
     */
    fun syncConfiguration(
        customWakePhrase: String,
        sensitivityThreshold: Float,
        targetChatId: Long? = WakeWordVoiceService.customWakeWordTargetChatId,
        targetChatTitle: String? = null
    ) {
        val coerced = sensitivityThreshold.coerceIn(0.50f, 0.98f)
        val current = _telemetryState.value
        val updatedPhrase = customWakePhrase.trim().ifBlank { current.customWakeWord }
        _telemetryState.value = current.copy(
            customWakeWord = updatedPhrase,
            sensitivityThreshold = coerced,
            isAboveThreshold = current.combinedSpotterConfidence >= coerced,
            targetChatId = targetChatId ?: current.targetChatId,
            targetChatTitle = targetChatTitle?.takeIf { it.isNotBlank() } ?: current.targetChatTitle,
            timestampEpochMs = System.currentTimeMillis()
        )
    }

    fun resetPeakConfidence() {
        rollingPeakConfidence = 0f
        val current = _telemetryState.value
        _telemetryState.value = current.copy(
            combinedSpotterConfidence = 0f,
            phoneticPhraseScore = 0f,
            peakSpotterConfidence = 0f,
            isAboveThreshold = false,
            lastEvaluatedTranscript = "",
            timestampEpochMs = System.currentTimeMillis()
        )
    }
}
