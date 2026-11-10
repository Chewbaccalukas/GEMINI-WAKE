package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-bar animated audio waveform canvas that uses spring-based physics (`spring()`)
 * to react fluidly and organically in real-time to fluctuating [audioRmsDb] amplitudes
 * when [isListening] is true.
 */
@Composable
fun VoiceWaveformVisualizer(
    isListening: Boolean,
    audioRmsDb: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    height: Dp = 56.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_ambient")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Primary spring-physics amplitude tracker (bouncy, responsive attack & organic decay)
    val springRms by animateFloatAsState(
        targetValue = if (isListening) audioRmsDb.coerceIn(0f, 10f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "spring_rms_primary"
    )

    // Secondary trailing spring envelope for fluid harmonic ripple across outer bars
    val trailingSpringRms = remember { Animatable(0f) }
    LaunchedEffect(audioRmsDb, isListening) {
        val target = if (isListening) audioRmsDb.coerceIn(0f, 10f) else 0f
        trailingSpringRms.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.58f,
                stiffness = 260f
            )
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .testTag("voice_waveform_canvas")
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val safeBars = barCount.coerceAtLeast(3)
        val centerY = canvasHeight / 2f
        val barWidth = (canvasWidth / (safeBars * 1.65f)).coerceIn(2.5f, 10f)
        val gap = if (safeBars > 1) {
            ((canvasWidth - (safeBars * barWidth)) / (safeBars - 1)).coerceAtLeast(1.5f)
        } else {
            0f
        }

        val activeGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8),
                Color(0xFFA855F7),
                Color(0xFF38BDF8)
            )
        )

        val listeningAmbientGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8).copy(alpha = 0.85f),
                Color(0xFF818CF8).copy(alpha = 0.75f)
            )
        )

        val idleGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF64748B).copy(alpha = 0.65f),
                Color(0xFF475569).copy(alpha = 0.55f)
            )
        )

        for (i in 0 until safeBars) {
            val normalizedIndex = i.toFloat() / safeBars
            // Envelope peaks gently toward the center of the bar array
            val centerEnvelope = 0.52f + 0.48f * sin(normalizedIndex * 3.14159f)
            val wave = sin(phase + (normalizedIndex * 5.2f))
            val secondaryHarmonic = cos(phase * 1.5f - (normalizedIndex * 8.0f))
            val combinedWave = ((wave * 0.7f + secondaryHarmonic * 0.3f) + 1f) / 2f

            val currentHeight = if (isListening) {
                // Blend primary spring amplitude near center with trailing spring near edges for fluid wave propagation
                val blendedRms = (springRms * centerEnvelope) + (trailingSpringRms.value * (1f - centerEnvelope * 0.5f))
                val rmsFactor = (blendedRms / 10f).coerceIn(0f, 1.15f)
                val subtleListeningFloor = canvasHeight * (0.20f + 0.16f * combinedWave * centerEnvelope)
                val dynamicVoiceSurge = (canvasHeight * 0.72f) * rmsFactor * (0.45f + 0.55f * combinedWave) * centerEnvelope
                (subtleListeningFloor + dynamicVoiceSurge).coerceIn(4f, canvasHeight)
            } else {
                (canvasHeight * 0.14f * combinedWave + 3f).coerceIn(3f, canvasHeight * 0.28f)
            }

            val x = i * (barWidth + gap)
            val top = centerY - (currentHeight / 2f)

            val barBrush = when {
                isListening && springRms > 0.35f -> activeGradient
                isListening -> listeningAmbientGradient
                else -> idleGradient
            }

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, top),
                size = Size(barWidth, currentHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Compact, subtle spring-physics animated waveform pill designed for headers and top status bars.
 * Reacts fluidly in real-time to [audioRmsDb] whenever [SpeechRecognizer] is actively listening for the wake word.
 */
@Composable
fun SubtleWakeWordWaveformPill(
    isListening: Boolean,
    audioRmsDb: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    barCount: Int = 9,
    waveformWidth: Dp = 44.dp,
    waveformHeight: Dp = 16.dp
) {
    val borderColor = if (isListening) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }
    val containerColor = if (isListening) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("wake_word_waveform_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (isListening) Color(0xFF38BDF8)
                    else MaterialTheme.colorScheme.outline
                )
        )
        VoiceWaveformVisualizer(
            isListening = isListening,
            audioRmsDb = audioRmsDb,
            barCount = barCount,
            height = waveformHeight,
            modifier = Modifier.width(waveformWidth)
        )
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                ),
                color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Top status banner that appears when [SpeechRecognizer] is actively listening for a wake word,
 * displaying the spring-physics animated waveform visualizer, active wake phrase hint, and live transcript preview.
 */
@Composable
fun WakeWordListeningStatusBanner(
    isListening: Boolean,
    audioRmsDb: Float,
    activeWakePhrase: String = "Hey Gemini",
    lastHeardText: String = "",
    totalVoiceCommands: Int = 0,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isListening,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .testTag("wake_word_top_status_banner"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    SubtleWakeWordWaveformPill(
                        isListening = isListening,
                        audioRmsDb = audioRmsDb,
                        barCount = 11,
                        waveformWidth = 52.dp,
                        waveformHeight = 16.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lastHeardText.isNotBlank()) {
                            "Heard: \"$lastHeardText\""
                        } else {
                            "Listening for \"$activeWakePhrase\" or \"Hey Jimmy\"..."
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (totalVoiceCommands > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$totalVoiceCommands voice cmd${if (totalVoiceCommands == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("banner_total_voice_commands")
                    )
                }
            }
        }
    }
}

/**
 * Subtle animated visual indicator and floating overlay pill that displays when the app is
 * actively listening for the user's customizable wake word.
 *
 * Features a continuous pulsing sonar aura around an active microphone status beacon, a
 * spring-physics mini waveform reactive to [audioRmsDb], and the currently configured
 * [activeWakePhrase] from DataStore/SharedPreferences.
 */
@Composable
fun ActiveWakeWordListeningOverlay(
    isActivelyListening: Boolean,
    activeWakePhrase: String,
    audioRmsDb: Float = 0f,
    lastHeardText: String = "",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wake_overlay_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.60f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )
    val borderGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_glow_alpha"
    )

    AnimatedVisibility(
        visible = isActivelyListening,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
        modifier = modifier
    ) {
        val displayPhrase = activeWakePhrase.trim().ifBlank { "Hey Gemini" }
        val statusLabel = if (lastHeardText.isNotBlank()) {
            "Heard: \"${lastHeardText.trim()}\""
        } else {
            "Listening for \"$displayPhrase\""
        }

        Surface(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            tonalElevation = 4.dp,
            shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = Color(0xFF38BDF8).copy(alpha = borderGlowAlpha)
            ),
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("active_wake_word_listening_overlay")
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing sonar beacon
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag("active_wake_word_pulse_dot"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(
                            color = Color(0xFF38BDF8).copy(alpha = pulseAlpha),
                            radius = (size.minDimension / 2f) * pulseScale
                        )
                        drawCircle(
                            color = Color(0xFF10B981),
                            radius = size.minDimension * 0.28f
                        )
                    }
                }

                // Live spring-physics mini waveform
                VoiceWaveformVisualizer(
                    isListening = isActivelyListening,
                    audioRmsDb = audioRmsDb,
                    barCount = 7,
                    height = 14.dp,
                    modifier = Modifier.width(32.dp)
                )

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("active_wake_word_overlay_label")
                )
            }
        }
    }
}

/**
 * Animated visual indicator in the UI that displays a dual-ring concentric sonar animation,
 * a dynamically scaled microphone icon, and a real-time audio waveform equalizer when the
 * microphone is active and listening for the wake word.
 */
@Composable
fun MicrophoneWakeWordListeningIndicator(
    isMicrophoneActiveAndListening: Boolean,
    activeWakePhrase: String = "Hey Gemini",
    audioRmsDb: Float = 0f,
    lastHeardText: String = "",
    hideWhenInactive: Boolean = true,
    onToggleMicrophone: (() -> Unit)? = null,
    testTag: String = "microphone_wake_word_listening_indicator",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_wake_indicator_transition")

    // Primary outer concentric ripple ring
    val primaryRippleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "primary_ripple_progress"
    )

    // Secondary staggered concentric ripple ring
    val secondaryRippleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, delayMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondary_ripple_progress"
    )

    // Breathing microphone icon scale modulated by live audioRmsDb energy
    val normalizedRms = remember(isMicrophoneActiveAndListening, audioRmsDb) {
        if (!isMicrophoneActiveAndListening) 0f else ((audioRmsDb + 2f) / 12f).coerceIn(0.05f, 1f)
    }
    val smoothedRms by animateFloatAsState(
        targetValue = normalizedRms,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "mic_indicator_rms"
    )
    val ambientBreathing by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_ambient_breathing"
    )

    val shouldShow = isMicrophoneActiveAndListening || !hideWhenInactive

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
        modifier = modifier
    ) {
        val displayPhrase = activeWakePhrase.trim().ifBlank { "Hey Gemini" }
        val activeColor = Color(0xFF10B981)
        val accentCyan = Color(0xFF38BDF8)

        Surface(
            onClick = { onToggleMicrophone?.invoke() },
            enabled = onToggleMicrophone != null,
            shape = RoundedCornerShape(16.dp),
            color = if (isMicrophoneActiveAndListening) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (isMicrophoneActiveAndListening) {
                    activeColor.copy(alpha = 0.55f + smoothedRms * 0.35f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(testTag)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Animated dual-ring concentric sonar + Microphone icon
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("${testTag}_ripple_box"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isMicrophoneActiveAndListening) {
                            Canvas(
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("${testTag}_ripple_canvas")
                            ) {
                                val maxRadius = size.minDimension / 2f
                                val r1 = maxRadius * (0.45f + 0.55f * primaryRippleProgress)
                                val a1 = (1f - primaryRippleProgress) * (0.55f + smoothedRms * 0.30f)
                                drawCircle(
                                    color = activeColor.copy(alpha = a1.coerceIn(0f, 1f)),
                                    radius = r1,
                                    style = Stroke(width = 2.dp.toPx())
                                )

                                val r2 = maxRadius * (0.45f + 0.55f * secondaryRippleProgress)
                                val a2 = (1f - secondaryRippleProgress) * (0.45f + smoothedRms * 0.25f)
                                drawCircle(
                                    color = accentCyan.copy(alpha = a2.coerceIn(0f, 1f)),
                                    radius = r2,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        }

                        val iconScale = if (isMicrophoneActiveAndListening) {
                            ambientBreathing + (smoothedRms * 0.18f)
                        } else {
                            1f
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .scale(iconScale)
                                .clip(CircleShape)
                                .background(
                                    if (isMicrophoneActiveAndListening) {
                                        Brush.linearGradient(listOf(activeColor, accentCyan))
                                    } else {
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.outline,
                                                MaterialTheme.colorScheme.outlineVariant
                                            )
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMicrophoneActiveAndListening) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isMicrophoneActiveAndListening) {
                                    "Microphone active and listening for wake word"
                                } else {
                                    "Microphone inactive"
                                },
                                tint = Color.White,
                                modifier = Modifier
                                    .size(14.dp)
                                    .testTag("${testTag}_icon")
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isMicrophoneActiveAndListening) {
                                "Microphone Active • Listening for \"$displayPhrase\""
                            } else {
                                "Microphone Standby • Wake Word Paused"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isMicrophoneActiveAndListening) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_label")
                        )
                        Text(
                            text = when {
                                !isMicrophoneActiveAndListening -> "Tap to enable hands-free wake-word listening"
                                lastHeardText.isNotBlank() -> "Heard: \"${lastHeardText.trim()}\""
                                else -> "Say \"$displayPhrase\" to trigger the Gemini assistant"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isMicrophoneActiveAndListening) {
                                activeColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_subtext")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Live animated multi-bar waveform equalizer
                VoiceWaveformVisualizer(
                    isListening = isMicrophoneActiveAndListening,
                    audioRmsDb = audioRmsDb,
                    barCount = 9,
                    height = 22.dp,
                    modifier = Modifier
                        .width(48.dp)
                        .testTag("${testTag}_waveform")
                )
            }
        }
    }
}

/**
 * Visual pulse animation that triggers on the screen when the app is actively listening for the
 * wake word to provide immediate feedback to the user.
 *
 * Renders a multi-ring expanding sonar pulse and top-edge ambient aura modulated in real time by
 * [audioRmsDb] whenever [isActivelyListening] is `true`.
 */
@Composable
fun WakeWordScreenPulseAnimation(
    isActivelyListening: Boolean,
    activeWakePhrase: String = "Hey Gemini",
    audioRmsDb: Float = 0f,
    pollingIntervalMs: Long = 60L,
    testTag: String = "wake_word_screen_pulse_animation",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "screen_pulse_transition")
    val wave1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "screen_pulse_wave_1"
    )
    val wave2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, delayMillis = 530, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "screen_pulse_wave_2"
    )
    val wave3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, delayMillis = 1060, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "screen_pulse_wave_3"
    )
    val edgeGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "screen_pulse_edge_glow"
    )

    val rmsBoost by animateFloatAsState(
        targetValue = if (isActivelyListening) (audioRmsDb / 10f).coerceIn(0f, 1f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "screen_pulse_rms_boost"
    )

    AnimatedVisibility(
        visible = isActivelyListening,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
        modifier = modifier
    ) {
        val displayPhrase = activeWakePhrase.trim().ifBlank { "Hey Gemini" }
        val emerald = Color(0xFF10B981)
        val cyan = Color(0xFF38BDF8)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            emerald.copy(alpha = 0.14f + 0.10f * edgeGlowAlpha),
                            cyan.copy(alpha = 0.18f + 0.12f * rmsBoost),
                            emerald.copy(alpha = 0.14f + 0.10f * edgeGlowAlpha)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            emerald.copy(alpha = edgeGlowAlpha),
                            cyan.copy(alpha = (edgeGlowAlpha + 0.15f).coerceAtMost(1f)),
                            emerald.copy(alpha = edgeGlowAlpha)
                        )
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .testTag(testTag)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("${testTag}_canvas_box"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("${testTag}_canvas")
                        ) {
                            val maxRadius = (size.minDimension / 2f) * (1f + rmsBoost * 0.2f)
                            listOf(wave1 to emerald, wave2 to cyan, wave3 to emerald).forEach { (progress, ringColor) ->
                                val radius = maxRadius * (0.30f + 0.70f * progress)
                                val alpha = ((1f - progress) * (0.65f + 0.25f * rmsBoost)).coerceIn(0f, 1f)
                                drawCircle(
                                    color = ringColor.copy(alpha = alpha),
                                    radius = radius,
                                    style = Stroke(width = 1.8.dp.toPx())
                                )
                            }
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFA7F3D0),
                                        emerald
                                    )
                                ),
                                radius = size.minDimension * (0.20f + 0.06f * rmsBoost)
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Screen Pulse Active • Listening for \"$displayPhrase\"",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_label")
                        )
                        Text(
                            text = "Immediate visual feedback • ${pollingIntervalMs}ms wake-word polling cadence",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = emerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_subtext")
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = emerald.copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, emerald.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "LIVE PULSE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp
                        ),
                        color = emerald,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("${testTag}_badge")
                    )
                }
            }
        }
    }
}

/**
 * Custom status widget with a subtle glowing animation that indicates the wake word listener and
 * notification icon are active and listening, while also displaying the current wake-word polling
 * cadence (`60ms` active vs `450ms` screen-off battery saver).
 */
@Composable
fun WakeWordGlowingStatusWidget(
    isActivelyListening: Boolean,
    activeWakePhrase: String = "Hey Gemini",
    screenOffBatterySaverEnabled: Boolean = false,
    pollingIntervalMs: Long = 60L,
    hideWhenInactive: Boolean = false,
    onToggleBatterySaver: (() -> Unit)? = null,
    testTag: String = "wake_word_glowing_status_widget",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowing_widget_transition")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "widget_glow_alpha"
    )
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "widget_halo_scale"
    )

    val shouldDisplay = isActivelyListening || !hideWhenInactive
    AnimatedVisibility(
        visible = shouldDisplay,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        modifier = modifier
    ) {
        val displayPhrase = activeWakePhrase.trim().ifBlank { "Hey Gemini" }
        val emerald = Color(0xFF10B981)
        val cyan = Color(0xFF38BDF8)

        Surface(
            onClick = { onToggleBatterySaver?.invoke() },
            enabled = onToggleBatterySaver != null,
            shape = RoundedCornerShape(14.dp),
            color = if (isActivelyListening) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
            },
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (isActivelyListening) {
                    emerald.copy(alpha = glowAlpha.coerceIn(0.30f, 0.85f))
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("${testTag}_halo"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isActivelyListening) {
                            Canvas(modifier = Modifier.size(30.dp)) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            emerald.copy(alpha = glowAlpha * 0.70f),
                                            cyan.copy(alpha = glowAlpha * 0.35f),
                                            Color.Transparent
                                        )
                                    ),
                                    radius = (size.minDimension / 2f) * haloScale
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActivelyListening) {
                                        Brush.linearGradient(listOf(emerald, cyan))
                                    } else {
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.outline,
                                                MaterialTheme.colorScheme.outlineVariant
                                            )
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isActivelyListening) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isActivelyListening) {
                                    "Glowing wake word listener active"
                                } else {
                                    "Wake word listener standby"
                                },
                                tint = Color.White,
                                modifier = Modifier
                                    .size(12.dp)
                                    .testTag("${testTag}_icon")
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isActivelyListening) {
                                "Glowing Listener Active • \"$displayPhrase\""
                            } else {
                                "Wake Listener Standby"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_label")
                        )
                        Text(
                            text = if (screenOffBatterySaverEnabled) {
                                "Notification icon glowing • Screen-Off Battery Saver enabled (450ms off / ${pollingIntervalMs}ms now)"
                            } else {
                                "Notification icon glowing • Full-speed ${pollingIntervalMs}ms wake polling"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isActivelyListening) emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTag}_subtext")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (screenOffBatterySaverEnabled) {
                        cyan.copy(alpha = 0.16f)
                    } else {
                        emerald.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = if (screenOffBatterySaverEnabled) {
                            "SAVER 450ms"
                        } else {
                            "${pollingIntervalMs}ms POLL"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = if (screenOffBatterySaverEnabled) cyan else emerald,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("${testTag}_polling_badge")
                    )
                }
            }
        }
    }
}



