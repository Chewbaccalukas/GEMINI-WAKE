package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.voice.WakeSensitivityLevel
import java.util.Locale

/**
 * Full-screen Settings screen allowing users to:
 * 1. Adjust the sensitivity of the speech recognition & wake-word trigger (global slider + presets + per-wake-word sliders).
 * 2. Customize their wake words (add new custom wake words, edit existing phrases inline, toggle active state, bind to chats, or delete).
 * 3. Customize hands-free voice command triggers (Dictate, Send, Heartbeat) and test live microphone recognition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    chats: List<ChatSession>,
    configuredWakeWords: List<WakeWord>,
    wakeWordSensitivity: Float,
    isListening: Boolean,
    isHandsFreeActive: Boolean,
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    audioRmsDb: Float = 0f,
    statusMessage: String = "",
    lastHeardText: String = "",
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    onBack: () -> Unit,
    onSetWakeWordSensitivity: (Float) -> Unit,
    onSetWakeWordSensitivityLevel: (WakeSensitivityLevel) -> Unit = { level ->
        onSetWakeWordSensitivity(level.sensitivityValue)
    },
    onToggleListening: () -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onExitSleepMode: () -> Unit = {},
    onAddCustomWakeWord: (chatId: Long, phrase: String, sensitivity: Float) -> Unit = { _, _, _ -> },
    onAddCustomWakeWordWithNewChat: (phrase: String, newChatTitle: String, sensitivity: Float) -> Unit = { _, _, _ -> },
    onUpdateWakeWordPhrase: (id: Long, newPhrase: String) -> Unit = { _, _ -> },
    onToggleWakeWordEnabled: (id: Long, enabled: Boolean) -> Unit = { _, _ -> },
    onDeleteWakeWord: (id: Long) -> Unit = {},
    onSetIndividualWakeWordSensitivity: (id: Long, sensitivity: Float) -> Unit = { _, _ -> },
    onReassignWakeWordToChat: (wakeWordId: Long, targetChatId: Long) -> Unit = { _, _ -> },
    onUpdateChatSessionWakeWord: (ChatSession, String) -> Unit = { _, _ -> },
    onUpdateDictateTrigger: (String) -> Unit = {},
    onUpdateSendTrigger: (String) -> Unit = {},
    onUpdateHeartbeatTrigger: (String) -> Unit = {},
    onResetTriggersToDefaults: () -> Unit = {},
    onOpenWakeWordStudio: () -> Unit = {},
    onOpenDiagnosticLog: () -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    onSetDefaultWakeChat: (Long) -> Unit = {},
    onCreateNewWakeTargetChat: (title: String, wakePhrase: String) -> Unit = { _, _ -> },
    sessionStatisticsState: com.example.ui.SessionStatisticsUiState? = null,
    onSaveBackupToDevice: (com.example.util.BackupExportFormat) -> Unit = {},
    onShareBackupFile: (com.example.util.BackupExportFormat) -> Unit = {},
    relayServerState: com.example.bridge.RelayServerState? = null,
    onToggleRelayServer: (Boolean) -> Unit = {},
    onRotateRelaySessionToken: () -> Unit = {},
    onCopyRelaySessionToken: (String) -> Unit = {},
    onForceResetAndClearHygiene: () -> Unit = {},
    lastHygieneResetMessage: String? = null,
    favoriteChatIds: Set<Long> = emptySet(),
    onToggleFavoriteChat: (Long) -> Unit = {},
    contextualRoutingEnabled: Boolean = true,
    onToggleContextualRouting: (Boolean) -> Unit = {},
    promptFavoritesOnWake: Boolean = false,
    onTogglePromptFavoritesOnWake: (Boolean) -> Unit = {},
    allSamples: List<com.example.data.model.WakeWordSample> = emptyList(),
    activeRecordingSlot: Int? = null,
    recordingDurationMs: Long = 0L,
    currentlyPlayingPath: String? = null,
    onStartSnippetRecording: (chatId: Long, slotIndex: Int) -> Unit = { _, _ -> },
    onStopSnippetRecording: (chatId: Long, slotIndex: Int) -> Unit = { _, _ -> },
    onPlaySnippetPreview: (String) -> Unit = {},
    onStopSnippetPreview: () -> Unit = {},
    onDeleteSampleById: (chatId: Long, sampleId: Long, filePath: String) -> Unit = { _, _, _ -> },
    voiceEngineMode: com.example.voice.VoiceEngineMode = com.example.voice.VoiceEngineMode.GEMINI_ORION,
    onSetVoiceEngineMode: (com.example.voice.VoiceEngineMode) -> Unit = {},
    masterVolume: Float = 1.0f,
    onSetMasterVolume: (Float) -> Unit = {},
    autoStopLimitSec: Int = 3,
    onSetAutoStopLimit: (Int) -> Unit = {},
    defaultPersistenceEnabled: Boolean = true,
    onToggleDefaultPersistence: (Boolean) -> Unit = {},
    preserveCacheAcrossSessions: Boolean = true,
    onTogglePreserveCacheAcrossSessions: (Boolean) -> Unit = {},
    bufferMetrics: com.example.cache.BufferMetrics = com.example.cache.BufferMetrics(),
    onClearActiveInMemoryBuffer: () -> Unit = {},
    onPurgeStagedDiskCache: () -> Unit = onForceResetAndClearHygiene,
    onCreateGeneralLogFile: (() -> String)? = null,
    lowPowerModeEnabled: Boolean? = null,
    onToggleLowPowerMode: ((Boolean) -> Unit)? = null,
    batteryDrainEstimate: com.example.util.BatteryDrainEstimate? = null,
    offlineQuestQueueingEnabled: Boolean? = null,
    onToggleOfflineQuestQueueing: ((Boolean) -> Unit)? = null,
    pendingOfflineSyncCount: Int = 0,
    savedCustomWakeWordPhrase: String? = null,
    onSaveCustomWakeWordToPrefs: ((String) -> Unit)? = null,
    onResetCustomWakeWordInPrefs: (() -> Unit)? = null,
    onSimulateWakeWordDiagnostic: ((String) -> com.example.voice.WakeWordSimulationDiagnosticReport)? = null,
    screenOffBatterySaverEnabled: Boolean? = null,
    onToggleScreenOffBatterySaver: ((Boolean) -> Unit)? = null,
    activeWakeWordPollingIntervalMs: Long? = null,
    cloudBackupChats: List<com.example.data.firebase.FirestoreChatDoc> = emptyList(),
    onSelectFirestoreWakeChat: (com.example.data.firebase.FirestoreChatDoc) -> Unit = {}
) {
    BackHandler { onBack() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(context) {
        com.example.util.AppSettingsManager.ensureInitialized(context)
    }
    val globalLowPowerMode by com.example.util.AppSettingsManager.lowPowerModeEnabled.collectAsState()
    val effectiveLowPowerMode = lowPowerModeEnabled ?: globalLowPowerMode
    val globalOfflineQueueing by com.example.util.AppSettingsManager.offlineQuestQueueingEnabled.collectAsState()
    val effectiveOfflineQueueing = offlineQuestQueueingEnabled ?: globalOfflineQueueing
    val globalScreenOffBatterySaver by com.example.util.AppSettingsManager.screenOffBatterySaverEnabled.collectAsState()
    val dataStoreScreenOffBatterySaverFlow = remember(context) {
        com.example.util.WakeWordDataStoreManager.observeScreenOffBatterySaver(context)
    }
    val dataStoreScreenOffBatterySaver by dataStoreScreenOffBatterySaverFlow.collectAsState(
        initial = globalScreenOffBatterySaver
    )
    val effectiveScreenOffBatterySaver = screenOffBatterySaverEnabled ?: (globalScreenOffBatterySaver || dataStoreScreenOffBatterySaver)
    val globalActivePollingIntervalMs by com.example.voice.WakeWordVoiceService.activeWakeWordPollingIntervalMs.collectAsState()
    val isDeviceScreenInteractive by com.example.voice.WakeWordVoiceService.isDeviceScreenInteractive.collectAsState()
    val effectivePollingIntervalMs = activeWakeWordPollingIntervalMs
        ?: com.example.voice.WakeWordVoiceService.resolveEffectivePollingIntervalMs(
            batterySaverEnabled = effectiveScreenOffBatterySaver,
            isScreenInteractive = isDeviceScreenInteractive
        ).takeIf { it > 0L }
        ?: globalActivePollingIntervalMs
    val globalCustomWakeWord by com.example.util.AppSettingsManager.customWakeWordPhrase.collectAsState()
    val dataStoreWakeWordFlow = remember(context) {
        com.example.util.WakeWordDataStoreManager.observeCustomWakeWord(context)
    }
    val dataStoreCustomWakeWord by dataStoreWakeWordFlow.collectAsState(initial = globalCustomWakeWord)
    val effectiveSavedCustomWakeWord = savedCustomWakeWordPhrase ?: dataStoreCustomWakeWord
    val dataStoreSensitivityFlow = remember(context) {
        com.example.util.WakeWordDataStoreManager.observeWakeWordSensitivity(context)
    }
    val dataStoreWakeWordSensitivity by dataStoreSensitivityFlow.collectAsState(
        initial = wakeWordSensitivity.coerceIn(0.50f, 0.98f)
    )
    val latestServiceSimReport by com.example.voice.WakeWordVoiceService.lastSimulationDiagnosticReport.collectAsState()
    var localSimulationReport by remember {
        mutableStateOf<com.example.voice.WakeWordSimulationDiagnosticReport?>(null)
    }
    val activeSimulationReport = localSimulationReport ?: latestServiceSimReport
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val resolvedBatteryEstimate = remember(effectiveLowPowerMode, batteryDrainEstimate) {
        batteryDrainEstimate ?: com.example.util.AppSettingsManager.calculateEstimatedBatteryRemaining(
            context = context,
            lowPowerModeEnabled = effectiveLowPowerMode
        )
    }
    var generalLogPreviewText by remember { mutableStateOf<String?>(null) }

    val handleCreateGeneralLogFile: () -> Unit = {
        val generatedLog = onCreateGeneralLogFile?.invoke()?.takeIf { it.isNotBlank() } ?: buildString {
            val nowFormatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))
            val audioPermGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val cameraPermGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            appendLine("=== GEMINI WAKE GENERAL SESSION DIAGNOSTIC LOG FILE ===")
            appendLine("Log Generated: $nowFormatted")
            appendLine("Active Chats: ${chats.size} | Configured Wake Words: ${configuredWakeWords.size}")
            appendLine("Voice Engine: ${voiceEngineMode.displayName} | Sensitivity: ${(wakeWordSensitivity * 100).toInt()}%")
            appendLine("Memory Buffer: Hot RAM=${bufferMetrics.hotMemoryCount}/${bufferMetrics.hotBufferSizeLimit} | Total Disk=${bufferMetrics.totalDiskCount}")
            appendLine()
            appendLine("--- 1. NETWORK & CONNECTIVITY STATE ---")
            appendLine("Local Sync Relay: ${relayServerState?.bindHost ?: "127.0.0.1"}:${relayServerState?.port ?: 8765} | Running=${relayServerState?.isRunning == true}")
            appendLine("Relay Status: ${relayServerState?.statusText ?: "Standby"}")
            appendLine()
            appendLine("--- 2. PERMISSION & AUDIO FOCUS STATES ---")
            appendLine("RECORD_AUDIO Permission: ${if (audioPermGranted) "GRANTED" else "DENIED"}")
            appendLine("CAMERA Permission: ${if (cameraPermGranted) "GRANTED" else "DENIED"}")
            appendLine("Hands-Free Radar Active: $isHandsFreeActive | Mic Listening: $isListening | Sleep Mode: $isSleepMode")
            appendLine()
            appendLine("--- 3. RECENT ERROR LOGS & WARNINGS ---")
            appendLine("Status Message: ${statusMessage.ifBlank { "Normal" }}")
            appendLine("Last Recognized Transcript: \"${lastHeardText.ifBlank { "None" }}\"")
            appendLine("Haptic Confirmations Triggered: ${com.example.voice.AudioCommandSanitizer.hapticTriggerCount} | Last Pattern: ${com.example.voice.AudioCommandSanitizer.lastHapticPattern?.name ?: "NONE"}")
        }
        try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Gemini Wake General Session Log", generatedLog)
            clipboard?.setPrimaryClip(clip)
            android.widget.Toast.makeText(
                context,
                "General session log copied to clipboard!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {}
        generalLogPreviewText = generatedLog
    }

    if (generalLogPreviewText != null) {
        val logText = generalLogPreviewText!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { generalLogPreviewText = null },
            modifier = Modifier.testTag("settings_general_log_preview_dialog"),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "General Session Diagnostic Log",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Copied to clipboard • Network, permissions & session error telemetry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_general_log_copy_box")
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(12.dp)
                        ) {
                            item {
                                Text(
                                    text = logText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Gemini Wake General Session Log", logText)
                            clipboard?.setPrimaryClip(clip)
                            android.widget.Toast.makeText(
                                context,
                                "General session log copied to clipboard!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.testTag("settings_general_log_copy_button")
                ) {
                    Text("Copy to Clipboard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { generalLogPreviewText = null },
                    modifier = Modifier.testTag("settings_general_log_close_button")
                ) {
                    Text("Close")
                }
            }
        )
    }

    val clampedGlobalSensitivity = wakeWordSensitivity.coerceIn(0.50f, 0.98f)
    var localGlobalSensitivity by remember(clampedGlobalSensitivity) {
        mutableFloatStateOf(clampedGlobalSensitivity)
    }
    LaunchedEffect(dataStoreWakeWordSensitivity) {
        val coerced = dataStoreWakeWordSensitivity.coerceIn(0.50f, 0.98f)
        if (kotlin.math.abs(localGlobalSensitivity - coerced) > 0.001f) {
            localGlobalSensitivity = coerced
        }
    }
    val activePreset = remember(localGlobalSensitivity) {
        WakeSensitivityLevel.fromSensitivity(localGlobalSensitivity)
    }

    // State for adding a new custom wake word
    var newPhraseInput by rememberSaveable { mutableStateOf("") }
    var newChatTitleInput by rememberSaveable { mutableStateOf("") }
    var createNewChatForWakeWord by rememberSaveable(chats.isEmpty()) { mutableStateOf(chats.isEmpty()) }
    val defaultChatId = remember(chats) {
        chats.firstOrNull { it.isDefaultWakeChat }?.id ?: chats.firstOrNull()?.id ?: 0L
    }
    var selectedTargetChatId by rememberSaveable(defaultChatId) { mutableStateOf(defaultChatId) }
    var newWakeSensitivity by rememberSaveable { mutableFloatStateOf(clampedGlobalSensitivity) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    // Voice-recorded phrase dictation target ("new_wake", "wake_<id>", or "chat_<id>")
    var voiceDictationTarget by remember { mutableStateOf<String?>(null) }

    // State for inline editing existing custom wake words
    var editingWakeWordId by remember { mutableStateOf<Long?>(null) }
    var editingWakePhraseDraft by remember { mutableStateOf("") }

    // State for inline editing primary chat wake word when no custom WakeWord row exists yet
    var editingChatSessionId by remember { mutableStateOf<Long?>(null) }
    var editingChatWakeDraft by remember { mutableStateOf("") }

    // Automatically populate the active wake word input when voice recognition captures speech
    LaunchedEffect(lastHeardText, voiceDictationTarget) {
        val spoken = lastHeardText.trim()
        val target = voiceDictationTarget
        if (spoken.isNotBlank() && target != null) {
            when {
                target == "new_wake" -> {
                    newPhraseInput = spoken
                    validationMessage = null
                    voiceDictationTarget = null
                }
                target.startsWith("wake_") -> {
                    editingWakePhraseDraft = spoken
                    voiceDictationTarget = null
                }
                target.startsWith("chat_") -> {
                    editingChatWakeDraft = spoken
                    voiceDictationTarget = null
                }
            }
        }
    }

    // State for hands-free command trigger words
    var dictateDraft by remember(dictateTrigger) { mutableStateOf(dictateTrigger) }
    var sendDraft by remember(sendTrigger) { mutableStateOf(sendTrigger) }
    var heartbeatDraft by remember(heartbeatTrigger) { mutableStateOf(heartbeatTrigger) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Wake Word & Speech Settings",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Customize wake phrases & trigger sensitivity",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    com.example.ui.components.TopBarSyncModeStatusIndicator(
                        isOfflineCacheMode = effectiveOfflineQueueing,
                        pendingQueuedCount = pendingOfflineSyncCount,
                        onToggleMode = {
                            val next = !effectiveOfflineQueueing
                            if (onToggleOfflineQuestQueueing != null) {
                                onToggleOfflineQuestQueueing(next)
                            } else {
                                com.example.util.AppSettingsManager.setOfflineQuestQueueingEnabled(context, next)
                                com.example.game.AdventureEngine.playSyncModeSwitchHaptic(context, next)
                            }
                        },
                        testTag = "settings_top_bar_sync_mode_indicator"
                    )
                    IconButton(
                        onClick = onOpenDiagnosticLog,
                        modifier = Modifier.testTag("settings_open_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Open Diagnostic Trace Log",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(
                        onClick = onOpenWakeWordStudio,
                        modifier = Modifier.testTag("settings_open_studio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Open Voice Studio",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Voice Studio", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isSleepMode) {
                item {
                    SleepModeVisualBanner(
                        isSleepMode = true,
                        sleepReason = sleepModeReason,
                        onWakeUpTap = onExitSleepMode
                    )
                }
            }

            // Runtime Microphone (RECORD_AUDIO) & SAF File Access Permission Card
            item {
                com.example.ui.components.MicrophonePermissionStatusBanner(
                    hideWhenGranted = false,
                    onActivateVoiceListening = onToggleListening,
                    testTag = "settings_microphone_permission_banner"
                )
            }

            // Custom Wake Word DataStore & SharedPreferences Configuration Card
            item {
                val runWakeWordSimulation: (String) -> com.example.voice.WakeWordSimulationDiagnosticReport = { phraseToTest ->
                    val cleanedPhrase = phraseToTest.trim().ifBlank { effectiveSavedCustomWakeWord }
                    val report = onSimulateWakeWordDiagnostic?.invoke(cleanedPhrase)
                        ?: com.example.voice.WakeWordVoiceService.simulateWakeWordDetectionFlow(
                            context = context,
                            wakePhraseOverride = cleanedPhrase,
                            configuredWakeWords = configuredWakeWords,
                            sensitivityOverride = clampedGlobalSensitivity,
                            dispatchToDetectionCallbacks = false
                        )
                    localSimulationReport = report
                    report
                }

                CustomWakeWordPreferencesCard(
                    savedWakeWordPhrase = effectiveSavedCustomWakeWord,
                    wakeWordSensitivity = localGlobalSensitivity,
                    onSetWakeWordSensitivity = { updatedSens ->
                        val coerced = updatedSens.coerceIn(0.50f, 0.98f)
                        localGlobalSensitivity = coerced
                        coroutineScope.launch {
                            com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(
                                context = context,
                                sensitivity = coerced
                            )
                        }
                        onSetWakeWordSensitivity(coerced)
                    },
                    isActivelyListening = (isListening || isHandsFreeActive) && !isSleepMode && !effectiveLowPowerMode,
                    audioRmsDb = audioRmsDb,
                    lastHeardText = lastHeardText,
                    screenOffBatterySaverEnabled = effectiveScreenOffBatterySaver,
                    pollingIntervalMs = effectivePollingIntervalMs,
                    simulationDiagnosticReport = activeSimulationReport,
                    onSimulateWakeWordDetection = runWakeWordSimulation,
                    availableChats = chats,
                    preselectedChatId = selectedTargetChatId,
                    onSelectPreselectedChat = { chosenChatId ->
                        selectedTargetChatId = chosenChatId
                        onSetDefaultWakeChat(chosenChatId)
                    },
                    onSaveWakeWord = { phraseToSave ->
                        val cleaned = com.example.voice.WakeWordRoutingEngine.resolveWakeWordOrSuggestedFallback(
                            inputPhrase = phraseToSave,
                            preferredSuggestion = com.example.util.AppSettingsManager.DEFAULT_CUSTOM_WAKE_WORD_PHRASE
                        )
                        coroutineScope.launch {
                            com.example.util.WakeWordDataStoreManager.saveCustomWakeWord(
                                context = context,
                                phrase = cleaned,
                                chatId = selectedTargetChatId.takeIf { it > 0L },
                                sensitivity = localGlobalSensitivity
                            )
                        }
                        if (onSaveCustomWakeWordToPrefs != null) {
                            onSaveCustomWakeWordToPrefs(cleaned)
                        } else {
                            com.example.util.AppSettingsManager.saveCustomWakeWordPhrase(
                                context = context,
                                phrase = cleaned,
                                chatId = selectedTargetChatId.takeIf { it > 0L },
                                sensitivity = localGlobalSensitivity
                            )
                        }
                    },
                    onResetDefault = {
                        localGlobalSensitivity = com.example.util.WakeWordDataStoreManager.DEFAULT_WAKE_WORD_SENSITIVITY
                        onSetWakeWordSensitivity(com.example.util.WakeWordDataStoreManager.DEFAULT_WAKE_WORD_SENSITIVITY)
                        coroutineScope.launch {
                            com.example.util.WakeWordDataStoreManager.resetCustomWakeWord(context)
                        }
                        if (onResetCustomWakeWordInPrefs != null) {
                            onResetCustomWakeWordInPrefs()
                        } else {
                            com.example.util.AppSettingsManager.resetCustomWakeWordPhrase(context)
                        }
                    }
                )
            }

            // Firestore Gemini Chat Sessions Selector Card (Multi-Sync Wake Target)
            item {
                FirestoreWakeChatSelectorCard(
                    cloudChats = cloudBackupChats,
                    localChats = chats,
                    onSelectFirestoreWakeChat = onSelectFirestoreWakeChat,
                    onSelectLocalWakeChat = onSetDefaultWakeChat,
                    onOpenChat = onOpenChat
                )
            }

            // 0. Low Power Mode (with Dynamic Estimated Hours Remaining) & Offline Room Queueing Global Toggle Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_low_power_and_offline_sync_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // A. Low Power Mode Row + Dynamic Estimated Hours Remaining
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Low Power Mode",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Disables the constant background wake-word listener while keeping the UI microphone button functional to extend battery life on older devices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = effectiveLowPowerMode,
                                onCheckedChange = { enabled ->
                                    if (onToggleLowPowerMode != null) {
                                        onToggleLowPowerMode(enabled)
                                    } else {
                                        com.example.util.AppSettingsManager.setLowPowerModeEnabled(context, enabled)
                                    }
                                },
                                modifier = Modifier.testTag("settings_low_power_mode_switch")
                            )
                        }

                        // Dynamic Estimated Hours Remaining Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (effectiveLowPowerMode) {
                                Color(0xFF10B981).copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (effectiveLowPowerMode) Color(0xFF10B981).copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = resolvedBatteryEstimate.formattedLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (effectiveLowPowerMode) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_estimated_hours_remaining_label")
                                )
                                Text(
                                    text = if (effectiveLowPowerMode) "Wake Listener Off" else "Wake Listener Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (effectiveLowPowerMode) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // A2. Screen-Off Battery Saver Toggle (Reduces Wake-Word Polling Frequency When Screen Is Off)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Screen-Off Battery Saver (Reduced Polling)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (effectiveScreenOffBatterySaver) {
                                            Color(0xFF10B981).copy(alpha = 0.18f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Text(
                                            text = if (effectiveScreenOffBatterySaver) {
                                                "450ms Screen-Off / ${effectivePollingIntervalMs}ms Active"
                                            } else {
                                                "60ms Full Polling"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (effectiveScreenOffBatterySaver) {
                                                Color(0xFF10B981)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .testTag("settings_screen_off_polling_interval_label")
                                        )
                                    }
                                }
                                Text(
                                    text = "Reduces wake word detection polling frequency from 60ms to 450ms when the screen is off to conserve battery while keeping hands-free detection active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = effectiveScreenOffBatterySaver,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        com.example.util.WakeWordDataStoreManager.saveScreenOffBatterySaver(
                                            context = context,
                                            enabled = enabled
                                        )
                                    }
                                    if (onToggleScreenOffBatterySaver != null) {
                                        onToggleScreenOffBatterySaver(enabled)
                                    } else {
                                        com.example.util.AppSettingsManager.setScreenOffBatterySaverEnabled(
                                            context = context,
                                            enabled = enabled
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("settings_screen_off_battery_saver_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // B. Global Offline Room-Backed Quest Queueing Toggle ('Online Sync' vs 'Offline Cache')
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Offline Quest Room Queueing",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (effectiveOfflineQueueing) Color(0xFFF59E0B).copy(alpha = 0.18f)
                                        else Color(0xFF10B981).copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = if (effectiveOfflineQueueing) "Offline Cache" else "Online Sync",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (effectiveOfflineQueueing) Color(0xFFF59E0B) else Color(0xFF10B981),
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .testTag("settings_sync_mode_status_pill")
                                        )
                                    }
                                }
                                Text(
                                    text = if (effectiveOfflineQueueing) {
                                        "Offline Cache mode active: quest progress is cached in Room for later synchronization"
                                    } else {
                                        "Online Sync mode active: quest progress is synced immediately when connected"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = effectiveOfflineQueueing,
                                onCheckedChange = { enabled ->
                                    if (onToggleOfflineQuestQueueing != null) {
                                        onToggleOfflineQuestQueueing(enabled)
                                    } else {
                                        com.example.util.AppSettingsManager.setOfflineQuestQueueingEnabled(context, enabled)
                                        com.example.game.AdventureEngine.playSyncModeSwitchHaptic(context, enabled)
                                    }
                                },
                                modifier = Modifier.testTag("settings_offline_quest_queue_switch")
                            )
                        }
                    }
                }
            }

            // 1. Speech Recognition Trigger Sensitivity & Live Mic Monitor Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_sensitivity_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Speech Recognition Trigger Sensitivity",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Controls how strictly wake phrases & acoustic triggers match",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "${(localGlobalSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .testTag("settings_global_sensitivity_value_text")
                                )
                            }
                        }

                        // Preset Buttons (Low 65%, Medium 78%, High 90%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WakeSensitivityLevel.entries.forEach { preset ->
                                val isSelected = activePreset == preset
                                OutlinedButton(
                                    onClick = {
                                        val presetVal = preset.sensitivityValue.coerceIn(0.50f, 0.98f)
                                        localGlobalSensitivity = presetVal
                                        coroutineScope.launch {
                                            com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(
                                                context = context,
                                                sensitivity = presetVal
                                            )
                                        }
                                        onSetWakeWordSensitivityLevel(preset)
                                    },
                                    colors = if (isSelected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_sensitivity_preset_${preset.name.lowercase(Locale.US)}")
                                ) {
                                    Text(
                                        text = "${preset.label} (${(preset.sensitivityValue * 100).toInt()}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }

                        // Continuous Fine-Tuning Sensitivity Slider
                        Column {
                            Slider(
                                value = localGlobalSensitivity,
                                onValueChange = { newValue ->
                                    val coerced = newValue.coerceIn(0.50f, 0.98f)
                                    localGlobalSensitivity = coerced
                                    coroutineScope.launch {
                                        com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(
                                            context = context,
                                            sensitivity = coerced
                                        )
                                    }
                                    onSetWakeWordSensitivity(coerced)
                                },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_global_sensitivity_slider")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "50% (More Permissive)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activePreset.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "98% (Strict Match)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = activePreset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Live Microphone & Trigger Test Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isListening) "Live Trigger Monitor: Listening" else "Live Trigger Monitor: Paused",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (statusMessage.isNotBlank()) {
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    val willActivate = !isListening
                                    runCatching {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
                                        context = context,
                                        isActivating = willActivate,
                                        reason = if (willActivate) "Settings Live Trigger Mic Activated" else "Settings Live Trigger Mic Paused"
                                    )
                                    onToggleListening()
                                },
                                colors = if (isListening) {
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else {
                                    ButtonDefaults.filledTonalButtonColors()
                                },
                                modifier = Modifier.testTag("settings_toggle_listening_button")
                            ) {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isListening) "Pause Listening" else "Test Trigger",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isListening) "Pause Mic" else "Test Mic")
                            }
                        }

                        VoiceWaveformVisualizer(
                            isListening = isListening,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (lastHeardText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Last recognized: \"$lastHeardText\"",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                val report = onSimulateWakeWordDiagnostic?.invoke(effectiveSavedCustomWakeWord)
                                    ?: com.example.voice.WakeWordVoiceService.simulateWakeWordDetectionFlow(
                                        context = context,
                                        wakePhraseOverride = effectiveSavedCustomWakeWord,
                                        configuredWakeWords = configuredWakeWords,
                                        sensitivityOverride = clampedGlobalSensitivity,
                                        dispatchToDetectionCallbacks = false
                                    )
                                localSimulationReport = report
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_sensitivity_simulate_wake_word_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Simulate Wake Word Detection Flow")
                        }

                        OutlinedButton(
                            onClick = onOpenDiagnosticLog,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_diagnostic_log_card_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View SpeechRecognizer Thread & Mutex Trace Log")
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { handleCreateGeneralLogFile() }
                                .testTag("settings_create_general_log_file_link")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Create a general log file",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Collects session network, permissions & error diagnostics to clipboard",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Copy general session log to clipboard",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Add New Custom Wake Word Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_add_wake_word_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Add Custom Wake Word",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Assign a custom wake phrase and trigger sensitivity to any chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newPhraseInput,
                                onValueChange = {
                                    newPhraseInput = it
                                    validationMessage = null
                                },
                                label = { Text("Custom Wake Word Phrase") },
                                placeholder = { Text("e.g. Hey Atlas, Code Master, Morning Brief") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_new_wake_word_input")
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (lastHeardText.isNotBlank() && voiceDictationTarget != "new_wake") {
                                        newPhraseInput = lastHeardText.trim()
                                        validationMessage = null
                                    } else {
                                        voiceDictationTarget = if (voiceDictationTarget == "new_wake") null else "new_wake"
                                        if (!isListening) {
                                            onToggleListening()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("settings_voice_record_new_wake_phrase_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice Record Custom Wake Word Phrase",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (voiceDictationTarget == "new_wake") "Listening…" else "Voice Input",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Optional acoustic voice sample recording for the new wake word target chat
                        val targetChatForRecording = selectedTargetChatId.takeIf { it > 0L } ?: defaultChatId
                        if (targetChatForRecording > 0L) {
                            val isRecordingNewSample = activeRecordingSlot == 90
                            val targetChatSamples = remember(allSamples, targetChatForRecording) {
                                allSamples.filter { it.chatId == targetChatForRecording }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recorded voice samples for target chat: ${targetChatSamples.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (isRecordingNewSample) {
                                            onStopSnippetRecording(targetChatForRecording, 90)
                                        } else {
                                            onStartSnippetRecording(targetChatForRecording, 90)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("settings_record_new_wake_sample_button")
                                ) {
                                    Icon(
                                        imageVector = if (isRecordingNewSample) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isRecordingNewSample) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isRecordingNewSample) {
                                            "Stop (${recordingDurationMs / 1000}s)"
                                        } else {
                                            "Record Voice Sample"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        if (chats.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !createNewChatForWakeWord,
                                    onClick = { createNewChatForWakeWord = false },
                                    label = { Text("Link to Existing Chat") },
                                    modifier = Modifier.testTag("settings_link_existing_chat_chip")
                                )
                                FilterChip(
                                    selected = createNewChatForWakeWord,
                                    onClick = { createNewChatForWakeWord = true },
                                    label = { Text("Create New Chat") },
                                    modifier = Modifier.testTag("settings_create_new_chat_chip")
                                )
                            }
                        }

                        if (createNewChatForWakeWord || chats.isEmpty()) {
                            OutlinedTextField(
                                value = newChatTitleInput,
                                onValueChange = { newChatTitleInput = it },
                                label = { Text("New Chat Title") },
                                placeholder = { Text("e.g. Research Assistant") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_chat_title_input")
                            )
                        } else {
                            Text(
                                text = "Select Target Chat:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chats.take(4).forEach { chatItem ->
                                    val selected = selectedTargetChatId == chatItem.id
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedTargetChatId = chatItem.id },
                                        label = {
                                            Text(
                                                text = chatItem.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier.testTag("settings_target_chat_chip_${chatItem.id}")
                                    )
                                }
                            }
                        }

                        // Per-wake-word initial sensitivity slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Wake Phrase Trigger Sensitivity",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "${(newWakeSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = newWakeSensitivity.coerceIn(0.50f, 0.98f),
                                onValueChange = { newWakeSensitivity = it.coerceIn(0.50f, 0.98f) },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_sensitivity_slider")
                            )
                        }

                        if (!validationMessage.isNullOrBlank()) {
                            Text(
                                text = validationMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("settings_wake_word_validation_text")
                            )
                        }

                        Button(
                            onClick = {
                                val cleanedPhrase = newPhraseInput.trim()
                                if (cleanedPhrase.isBlank()) {
                                    validationMessage = "Please enter a wake word phrase."
                                    return@Button
                                }
                                val sens = newWakeSensitivity.coerceIn(0.50f, 0.98f)
                                if (createNewChatForWakeWord || chats.isEmpty()) {
                                    val resolvedChatTitle = newChatTitleInput.trim()
                                        .ifBlank { "$cleanedPhrase Chat" }
                                    onAddCustomWakeWordWithNewChat(cleanedPhrase, resolvedChatTitle, sens)
                                } else {
                                    val targetId = selectedTargetChatId.takeIf { it > 0L } ?: defaultChatId
                                    onAddCustomWakeWord(targetId, cleanedPhrase, sens)
                                }
                                newPhraseInput = ""
                                newChatTitleInput = ""
                                validationMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_add_wake_word_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Custom Wake Word")
                        }
                    }
                }
            }

            // 3. Configured Custom Wake Words Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configured Custom Wake Words (${configuredWakeWords.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            if (configuredWakeWords.isEmpty() && chats.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No custom wake words configured yet. Add one above to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // List each configured WakeWord with inline phrase editing, toggle, and individual sensitivity slider
            items(
                items = configuredWakeWords,
                key = { item -> "settings_wake_${item.id}" }
            ) { wakeItem ->
                val linkedChat = chats.firstOrNull { it.id == wakeItem.effectiveTargetChatId }
                val isEditing = editingWakeWordId == wakeItem.id
                val itemSensitivity = wakeItem.sensitivity.coerceIn(0.50f, 0.98f)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wake_word_item_${wakeItem.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (wakeItem.isEnabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "\"${wakeItem.phrase}\"",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (wakeItem.isEnabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = "Target Chat: ${linkedChat?.title ?: wakeItem.chatTitle.ifBlank { "Default Chat" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (isEditing) {
                                            editingWakeWordId = null
                                        } else {
                                            editingWakeWordId = wakeItem.id
                                            editingWakePhraseDraft = wakeItem.phrase
                                        }
                                    },
                                    modifier = Modifier.testTag("settings_edit_wake_word_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                                        contentDescription = "Edit Wake Word Phrase",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteWakeWord(wakeItem.id) },
                                    modifier = Modifier.testTag("settings_delete_wake_word_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Wake Word",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Switch(
                                    checked = wakeItem.isEnabled,
                                    onCheckedChange = { enabled ->
                                        onToggleWakeWordEnabled(wakeItem.id, enabled)
                                    },
                                    modifier = Modifier.testTag("settings_wake_word_switch_${wakeItem.id}")
                                )
                            }
                        }

                        AnimatedVisibility(visible = isEditing) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = editingWakePhraseDraft,
                                    onValueChange = { editingWakePhraseDraft = it },
                                    label = { Text("Wake Word Phrase") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_edit_wake_word_input_${wakeItem.id}")
                                )
                                FilledTonalButton(
                                    onClick = {
                                        val targetKey = "wake_${wakeItem.id}"
                                        if (lastHeardText.isNotBlank() && voiceDictationTarget != targetKey) {
                                            editingWakePhraseDraft = lastHeardText.trim()
                                        } else {
                                            voiceDictationTarget = if (voiceDictationTarget == targetKey) null else targetKey
                                            if (!isListening) {
                                                onToggleListening()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("settings_voice_dictate_wake_phrase_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Speak Wake Phrase",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (voiceDictationTarget == "wake_${wakeItem.id}") "Speak…" else "Speak",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Button(
                                    onClick = {
                                        val trimmed = editingWakePhraseDraft.trim()
                                        if (trimmed.isNotBlank()) {
                                            onUpdateWakeWordPhrase(wakeItem.id, trimmed)
                                            editingWakeWordId = null
                                        }
                                    },
                                    modifier = Modifier.testTag("settings_save_wake_word_phrase_${wakeItem.id}")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Save")
                                }
                            }
                        }

                        // Compact Individual Recorded Wake-Word Detection Sensitivity Slider
                        var localWakeItemSensitivity by remember(wakeItem.id, itemSensitivity) {
                            mutableFloatStateOf(itemSensitivity)
                        }
                        val wakePresetLabel = remember(localWakeItemSensitivity) {
                            when {
                                localWakeItemSensitivity >= 0.85f -> "Strict Guard (Low False Triggers)"
                                localWakeItemSensitivity >= 0.72f -> "Balanced Threshold"
                                else -> "Permissive (Quiet Environment)"
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recorded Wake-Word Sensitivity • $wakePresetLabel",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = "${(localWakeItemSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = localWakeItemSensitivity,
                                onValueChange = { updated ->
                                    val coerced = updated.coerceIn(0.50f, 0.98f)
                                    localWakeItemSensitivity = coerced
                                    onSetIndividualWakeWordSensitivity(wakeItem.id, coerced)
                                },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .testTag("settings_wake_word_sensitivity_slider_${wakeItem.id}")
                            )
                        }

                        // Voice-Recorded Input & Acoustic Snippet Controls for this Custom Wake Word
                        val effectiveChatIdForSample = wakeItem.effectiveTargetChatId ?: defaultChatId
                        val wakeWordSamples = remember(allSamples, wakeItem.id, wakeItem.phrase, effectiveChatIdForSample) {
                            allSamples.filter { sample ->
                                sample.wakeWordId == wakeItem.id ||
                                    sample.wakePhrase.equals(wakeItem.phrase, ignoreCase = true) ||
                                    (sample.chatId == effectiveChatIdForSample && effectiveChatIdForSample > 0L)
                            }
                        }
                        val slotForWakeItem = ((wakeItem.id % 50L).toInt()).coerceAtLeast(1) + 10
                        val isRecordingThisWakeItem = activeRecordingSlot == slotForWakeItem
                        val latestSample = wakeWordSamples.maxByOrNull { it.recordedAt }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_wake_word_voice_samples_row_${wakeItem.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Voice Recorded Input: ${wakeWordSamples.size} sample(s)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (wakeWordSamples.isNotEmpty()) {
                                        "Acoustic voice fingerprint active to prevent accidental triggers"
                                    } else {
                                        "Record your voice saying \"${wakeItem.phrase}\" to reduce false triggers"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (latestSample != null) {
                                    val isPlayingSample = currentlyPlayingPath == latestSample.samplePath
                                    OutlinedButton(
                                        onClick = {
                                            if (isPlayingSample) {
                                                onStopSnippetPreview()
                                            } else {
                                                onPlaySnippetPreview(latestSample.samplePath)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("settings_play_wake_sample_${wakeItem.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (isPlayingSample) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "Preview Recorded Wake Word Sample",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isPlayingSample) "Stop" else "Play",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val targetId = effectiveChatIdForSample.takeIf { it > 0L } ?: 1L
                                        if (isRecordingThisWakeItem) {
                                            onStopSnippetRecording(targetId, slotForWakeItem)
                                        } else {
                                            onStartSnippetRecording(targetId, slotForWakeItem)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("settings_record_wake_sample_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isRecordingThisWakeItem) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = "Record Voice Input for ${wakeItem.phrase}",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isRecordingThisWakeItem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isRecordingThisWakeItem) {
                                            "Stop (${recordingDurationMs / 1000}s)"
                                        } else {
                                            "Record Voice"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        if (chats.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    chats.take(3).forEach { chatOption ->
                                        val isAssigned = wakeItem.effectiveTargetChatId == chatOption.id
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isAssigned) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    onReassignWakeWordToChat(wakeItem.id, chatOption.id)
                                                }
                                        ) {
                                            Text(
                                                text = chatOption.title.take(12),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isAssigned) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                val targetChatId = wakeItem.effectiveTargetChatId
                                if (targetChatId != null && targetChatId > 0L) {
                                    TextButton(
                                        onClick = { onOpenChat(targetChatId) },
                                        modifier = Modifier.testTag("settings_open_wake_chat_${wakeItem.id}")
                                    ) {
                                        Text("Open Chat", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Primary Chat Session Wake Words (Quick inline editor per chat)
            if (chats.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_chat_wake_words_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Chat Session Primary Wake Words",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            chats.forEach { chatSession ->
                                val isEditingChat = editingChatSessionId == chatSession.id
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = chatSession.title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                                Text(
                                                    text = "Wake Word: \"${chatSession.wakeWord}\"",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (isEditingChat) {
                                                        editingChatSessionId = null
                                                    } else {
                                                        editingChatSessionId = chatSession.id
                                                        editingChatWakeDraft = chatSession.wakeWord
                                                    }
                                                },
                                                modifier = Modifier.testTag("settings_edit_chat_wake_${chatSession.id}")
                                            ) {
                                                Icon(
                                                    imageVector = if (isEditingChat) Icons.Default.Close else Icons.Default.Edit,
                                                    contentDescription = "Edit Chat Wake Word",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        AnimatedVisibility(visible = isEditingChat) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = editingChatWakeDraft,
                                                    onValueChange = { editingChatWakeDraft = it },
                                                    label = { Text("Wake Word for ${chatSession.title}") },
                                                    singleLine = true,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("settings_chat_wake_input_${chatSession.id}")
                                                )
                                                FilledTonalButton(
                                                    onClick = {
                                                        val targetKey = "chat_${chatSession.id}"
                                                        if (lastHeardText.isNotBlank() && voiceDictationTarget != targetKey) {
                                                            editingChatWakeDraft = lastHeardText.trim()
                                                        } else {
                                                            voiceDictationTarget = if (voiceDictationTarget == targetKey) null else targetKey
                                                            if (!isListening) {
                                                                onToggleListening()
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.testTag("settings_voice_dictate_chat_wake_${chatSession.id}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Mic,
                                                        contentDescription = "Speak Chat Wake Word",
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(
                                                        text = if (voiceDictationTarget == "chat_${chatSession.id}") "Speak…" else "Speak",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                                Button(
                                                    onClick = {
                                                        val trimmed = editingChatWakeDraft.trim()
                                                        if (trimmed.isNotBlank()) {
                                                            onUpdateChatSessionWakeWord(chatSession, trimmed)
                                                            editingChatSessionId = null
                                                        }
                                                    },
                                                    modifier = Modifier.testTag("settings_save_chat_wake_${chatSession.id}")
                                                ) {
                                                    Text("Save")
                                                }
                                            }
                                        }

                                        // Per-Chat Primary Wake Word Sensitivity Slider & Voice Sample Recording
                                        val linkedWakeRow = configuredWakeWords.firstOrNull {
                                            it.effectiveTargetChatId == chatSession.id &&
                                                it.phrase.equals(chatSession.wakeWord, ignoreCase = true)
                                        } ?: configuredWakeWords.firstOrNull { it.effectiveTargetChatId == chatSession.id }
                                        val chatInitialSens = (linkedWakeRow?.sensitivity ?: clampedGlobalSensitivity).coerceIn(0.50f, 0.98f)
                                        var localChatSens by remember(chatSession.id, chatInitialSens) {
                                            mutableFloatStateOf(chatInitialSens)
                                        }
                                        val chatSampleSlot = ((chatSession.id % 30L).toInt()).coerceAtLeast(1) + 60
                                        val isRecordingChatSample = activeRecordingSlot == chatSampleSlot
                                        val chatSamplesCount = remember(allSamples, chatSession.id) {
                                            allSamples.count { it.chatId == chatSession.id }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Detection Threshold: ${(localChatSens * 100).toInt()}% • $chatSamplesCount voice sample(s)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    if (isRecordingChatSample) {
                                                        onStopSnippetRecording(chatSession.id, chatSampleSlot)
                                                    } else {
                                                        onStartSnippetRecording(chatSession.id, chatSampleSlot)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.testTag("settings_record_chat_wake_sample_${chatSession.id}")
                                            ) {
                                                Icon(
                                                    imageVector = if (isRecordingChatSample) Icons.Default.Stop else Icons.Default.Mic,
                                                    contentDescription = "Record Voice Sample for ${chatSession.title}",
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isRecordingChatSample) "Stop" else "Record Voice",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        Slider(
                                            value = localChatSens,
                                            onValueChange = { updated ->
                                                val coerced = updated.coerceIn(0.50f, 0.98f)
                                                localChatSens = coerced
                                                if (linkedWakeRow != null) {
                                                    onSetIndividualWakeWordSensitivity(linkedWakeRow.id, coerced)
                                                } else {
                                                    onAddCustomWakeWord(chatSession.id, chatSession.wakeWord, coerced)
                                                }
                                            },
                                            valueRange = 0.50f..0.98f,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(30.dp)
                                                .testTag("settings_chat_wake_sensitivity_slider_${chatSession.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Hands-Free Voice Command Triggers Card (Dictate, Send, Heartbeat)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_hands_free_triggers_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Hands-Free Action Wake Words",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Spoken commands for dictation, sending, and heartbeat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isHandsFreeActive,
                                onCheckedChange = { enabled ->
                                    runCatching {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
                                        context = context,
                                        isActivating = enabled,
                                        reason = if (enabled) "Settings Hands-Free Switch ON" else "Settings Hands-Free Switch OFF"
                                    )
                                    onToggleHandsFree(enabled)
                                },
                                modifier = Modifier.testTag("settings_hands_free_switch")
                            )
                        }

                        OutlinedTextField(
                            value = dictateDraft,
                            onValueChange = {
                                dictateDraft = it
                                onUpdateDictateTrigger(it)
                            },
                            label = { Text("Dictate Wake Word") },
                            placeholder = { Text("e.g. Dictate") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_dictate_trigger_input")
                        )

                        OutlinedTextField(
                            value = sendDraft,
                            onValueChange = {
                                sendDraft = it
                                onUpdateSendTrigger(it)
                            },
                            label = { Text("Send Trigger Word") },
                            placeholder = { Text("e.g. Send") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_send_trigger_input")
                        )

                        OutlinedTextField(
                            value = heartbeatDraft,
                            onValueChange = {
                                heartbeatDraft = it
                                onUpdateHeartbeatTrigger(it)
                            },
                            label = { Text("Heartbeat Trigger Word") },
                            placeholder = { Text("e.g. Heartbeat") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_heartbeat_trigger_input")
                        )

                        OutlinedButton(
                            onClick = {
                                dictateDraft = "Dictate"
                                sendDraft = "Send"
                                heartbeatDraft = "Heartbeat"
                                onResetTriggersToDefaults()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_reset_triggers_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Action Triggers to Defaults")
                        }
                    }
                }
            }

            // =========================================================================
            // 5. Target Chat to Open on Wake Word Detection (Existing or New Chat)
            // =========================================================================
            item {
                var createNewWakeTargetMode by rememberSaveable { mutableStateOf(false) }
                var newWakeTargetChatTitle by rememberSaveable { mutableStateOf("") }
                var newWakeTargetPhrase by rememberSaveable { mutableStateOf("Hey Gemini") }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wake_target_chat_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Target Chat on Wake Word Detection",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Select an existing chat to open when a wake word is detected, or specify a new one",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !createNewWakeTargetMode,
                                onClick = { createNewWakeTargetMode = false },
                                label = { Text("Choose Existing Chat") },
                                modifier = Modifier.testTag("settings_choose_existing_wake_chat_chip")
                            )
                            FilterChip(
                                selected = createNewWakeTargetMode,
                                onClick = { createNewWakeTargetMode = true },
                                label = { Text("Specify New Chat") },
                                modifier = Modifier.testTag("settings_specify_new_wake_chat_chip")
                            )
                        }

                        if (!createNewWakeTargetMode) {
                            chats.forEach { chat ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSetDefaultWakeChat(chat.id) }
                                        .testTag("settings_wake_target_option_${chat.id}"),
                                    color = if (chat.isDefaultWakeChat) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chat.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (chat.isDefaultWakeChat) FontWeight.Bold else FontWeight.Medium
                                                )
                                            )
                                            Text(
                                                text = "Wake word: \"${chat.wakeWord}\" • ${chat.totalVoiceCommands} voice turns",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (chat.isDefaultWakeChat) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active default wake target chat",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = newWakeTargetChatTitle,
                                onValueChange = { newWakeTargetChatTitle = it },
                                label = { Text("New Target Chat Title") },
                                placeholder = { Text("e.g. Hands-Free Assistant") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_target_title_input")
                            )
                            OutlinedTextField(
                                value = newWakeTargetPhrase,
                                onValueChange = { newWakeTargetPhrase = it },
                                label = { Text("Wake Word Phrase") },
                                placeholder = { Text("e.g. Hey Gemini") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_target_phrase_input")
                            )
                            Button(
                                onClick = {
                                    val cleanTitle = newWakeTargetChatTitle.trim().ifBlank { "Hands-Free Assistant" }
                                    val cleanPhrase = newWakeTargetPhrase.trim().ifBlank { "Hey Gemini" }
                                    onCreateNewWakeTargetChat(cleanTitle, cleanPhrase)
                                    newWakeTargetChatTitle = ""
                                    createNewWakeTargetMode = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_create_new_wake_target_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Create Chat & Set as Wake Target")
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 6. Backup & Usage Statistics Export (JSON / CSV)
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_backup_export_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Backup Chat History & Usage Statistics",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Export all chat turns, voice commands, and 7-day wake-word telemetry as JSON or CSV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (sessionStatisticsState != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "7-Day Wake Sessions: ${sessionStatisticsState.wakeTriggeredSessions7d} / ${sessionStatisticsState.totalSessions7d} (${sessionStatisticsState.wakeSessionPercentage7d}%)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "All-Time: ${sessionStatisticsState.allTimeSessionsCount} chats • ${sessionStatisticsState.allTimeMessagesCount} turns • ${sessionStatisticsState.allTimeWakeTriggerCount} wake triggers",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSaveBackupToDevice(com.example.util.BackupExportFormat.JSON) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_save_json_backup_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save JSON")
                            }
                            Button(
                                onClick = { onSaveBackupToDevice(com.example.util.BackupExportFormat.CSV) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_save_csv_backup_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save CSV")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onShareBackupFile(com.example.util.BackupExportFormat.JSON) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_share_json_backup_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share JSON")
                            }
                            OutlinedButton(
                                onClick = { onShareBackupFile(com.example.util.BackupExportFormat.CSV) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_share_csv_backup_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share CSV")
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 7. Smart Contextual Wake Routing, Favorite Chats & Fallback Chat
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_smart_routing_and_favorites_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Contextual Auto-Selection, Favorites & Fallback Chat",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Priority: 1) Specific custom wake word → 2) Contextual schedule/activity or Favorite Chats prompt → 3) Fallback default chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Smart Contextual Chat Routing",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Auto-selects relevant chat using time of day, workday schedule & recent activity",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = contextualRoutingEnabled,
                                onCheckedChange = onToggleContextualRouting,
                                modifier = Modifier.testTag("settings_contextual_routing_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prompt Favorite Chats on General Wake Word",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Shows a quick picker of your starred favorite chats when a general wake word is detected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = promptFavoritesOnWake,
                                onCheckedChange = onTogglePromptFavoritesOnWake,
                                modifier = Modifier.testTag("settings_prompt_favorites_switch")
                            )
                        }

                        if (chats.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text(
                                text = "Select Favorite Chats (${favoriteChatIds.size} starred):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            chats.forEach { chat ->
                                val isFav = favoriteChatIds.contains(chat.id)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onToggleFavoriteChat(chat.id) }
                                        .testTag("settings_favorite_chat_chip_${chat.id}"),
                                    color = if (isFav) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chat.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isFav) FontWeight.Bold else FontWeight.Medium
                                                )
                                            )
                                            Text(
                                                text = buildString {
                                                    append("Wake: \"${chat.wakeWord}\"")
                                                    if (chat.isDefaultWakeChat) append(" • Fallback Default")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilterChip(
                                            selected = isFav,
                                            onClick = { onToggleFavoriteChat(chat.id) },
                                            label = { Text(if (isFav) "★ Favorite" else "Star") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 8. Pillar 3: Local Loopback Sync Relay (127.0.0.1:8765) & System Hygiene
            // =========================================================================
            item {
                val activeToken = relayServerState?.sessionToken?.ifBlank { "Generating..." } ?: "Generating..."
                val bindEndpoint = "${relayServerState?.bindHost ?: "127.0.0.1"}:${relayServerState?.port ?: 8765}"
                val isRelayRunning = relayServerState?.isRunning == true

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_local_sync_relay_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Sync Relay ($bindEndpoint)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = relayServerState?.statusText ?: "Loopback HTTP/SSE Relay (127.0.0.1:8765)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRelayRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isRelayRunning,
                                onCheckedChange = onToggleRelayServer,
                                modifier = Modifier.testTag("settings_relay_server_switch")
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Ephemeral Session Token (X-Session-Token / ?token=)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activeToken,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("settings_relay_session_token_text")
                                )
                                Text(
                                    text = "Deduplicated Turns: ${relayServerState?.deduplicatedTurnCount ?: 0} • Connected Clients: ${relayServerState?.connectedClientsCount ?: 0}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onCopyRelaySessionToken(activeToken) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_copy_relay_token_button")
                            ) {
                                Text("Copy Session Token")
                            }
                            OutlinedButton(
                                onClick = onRotateRelaySessionToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_rotate_relay_token_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rotate Token")
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = "System Hygiene (forceResetAndClear)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Stops the relay socket, clears the audio & hot memory buffers, and flushes ephemeral cache files without wiping Room database tables.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!lastHygieneResetMessage.isNullOrBlank()) {
                            Text(
                                text = lastHygieneResetMessage,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("settings_hygiene_reset_status_text")
                            )
                        }

                        OutlinedButton(
                            onClick = onForceResetAndClearHygiene,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_force_reset_and_clear_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Force Reset Relay, Audio Buffer & Ephemeral Cache")
                        }
                    }
                }
            }

            // =========================================================================
            // 9. Consolidated Voice Output, Recording Duration & Session Persistence
            // =========================================================================
            item {
                var localMasterVolume by remember(masterVolume) {
                    mutableFloatStateOf(masterVolume.coerceIn(0f, 1f))
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_consolidated_voice_and_persistence_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Unified Voice Output & Session Persistence",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Consolidated audio synthesis, snippet recording auto-stop, and session storage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Voice Engine Mode Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.voice.VoiceEngineMode.entries.forEach { mode ->
                                val selected = voiceEngineMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSetVoiceEngineMode(mode) },
                                    label = { Text(mode.displayName) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_voice_engine_chip_${mode.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        // Master Volume Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Voice Playback Master Volume",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "${(localMasterVolume * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = localMasterVolume,
                                onValueChange = { v ->
                                    localMasterVolume = v.coerceIn(0f, 1f)
                                    onSetMasterVolume(localMasterVolume)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .testTag("settings_master_volume_slider")
                            )
                        }

                        // Voice Snippet Auto-Stop Duration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Wake Snippet Recording Limit",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 5).forEach { sec ->
                                    FilterChip(
                                        selected = autoStopLimitSec == sec,
                                        onClick = { onSetAutoStopLimit(sec) },
                                        label = { Text("${sec}s") },
                                        modifier = Modifier.testTag("settings_auto_stop_${sec}s_chip")
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Persist New Chats by Default",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Automatically save new chat sessions & category mappings to Room & Cloud",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = defaultPersistenceEnabled,
                                onCheckedChange = onToggleDefaultPersistence,
                                modifier = Modifier.testTag("settings_default_persistence_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Preserve Disk Cache Across Sessions",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Retain paged message history on disk when switching categories or chats",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preserveCacheAcrossSessions,
                                onCheckedChange = onTogglePreserveCacheAcrossSessions,
                                modifier = Modifier.testTag("settings_preserve_cache_switch")
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 10. Two-Tier Memory Buffer Settings (Chat Memory & Buffer Settings)
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_memory_buffer_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Two-Tier Memory Buffer Settings",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Chat Memory & Buffer Settings • Hot RAM Window & Staged Ephemeral Disk Cache",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Hot Window Limit: ${bufferMetrics.hotBufferSizeLimit} messages • Active Resident Count: ${bufferMetrics.hotMemoryCount} messages",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("settings_buffer_telemetry_text")
                                )
                                Text(
                                    text = "Total Persisted Messages: ${bufferMetrics.totalDiskCount} • Incoming AI Turns: ${bufferMetrics.incomingDiskCount} • Outgoing User Turns: ${bufferMetrics.outgoingDiskCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onClearActiveInMemoryBuffer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_active_ram_window_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Active RAM Window (Clear Active In-Memory Buffer)")
                        }

                        OutlinedButton(
                            onClick = onPurgeStagedDiskCache,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_purge_ephemeral_disk_cache_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Purge Ephemeral Disk Cache (Purge Staged Disk Cache)")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compose Settings card allowing users to define, preview presets, and save/update their custom wake word
 * in Jetpack [androidx.datastore.core.DataStore] (`WakeWordDataStoreManager`) and [android.content.SharedPreferences]
 * (`gemini_wake_settings` & `WakeWordVoiceService`), alongside a live [com.example.ui.components.ActiveWakeWordListeningOverlay].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomWakeWordPreferencesCard(
    savedWakeWordPhrase: String,
    onSaveWakeWord: (String) -> Unit,
    onResetDefault: () -> Unit,
    wakeWordSensitivity: Float = com.example.util.WakeWordDataStoreManager.DEFAULT_WAKE_WORD_SENSITIVITY,
    onSetWakeWordSensitivity: (Float) -> Unit = {},
    isActivelyListening: Boolean = false,
    audioRmsDb: Float = 0f,
    lastHeardText: String = "",
    screenOffBatterySaverEnabled: Boolean = false,
    pollingIntervalMs: Long = 60L,
    simulationDiagnosticReport: com.example.voice.WakeWordSimulationDiagnosticReport? = null,
    onSimulateWakeWordDetection: ((String) -> com.example.voice.WakeWordSimulationDiagnosticReport)? = null,
    availableChats: List<ChatSession> = emptyList(),
    preselectedChatId: Long? = null,
    onSelectPreselectedChat: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var draftPhrase by rememberSaveable(savedWakeWordPhrase) { mutableStateOf(savedWakeWordPhrase) }
    var lastSelectedCommonSuggestion by rememberSaveable {
        mutableStateOf(com.example.voice.WakeWordRoutingEngine.COMMON_WAKE_WORD_SUGGESTIONS.first())
    }
    val clampedSensitivity = wakeWordSensitivity.coerceIn(0.50f, 0.98f)
    var localDataStoreSensitivity by remember(clampedSensitivity) {
        mutableFloatStateOf(clampedSensitivity)
    }
    val sensitivityTierLabel = remember(localDataStoreSensitivity) {
        when {
            localDataStoreSensitivity >= 0.85f -> "Strict Match (Low False Triggers)"
            localDataStoreSensitivity >= 0.72f -> "Balanced Threshold"
            else -> "More Permissive (Quiet Rooms)"
        }
    }
    var confirmationMessage by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var internalSimReport by remember {
        mutableStateOf<com.example.voice.WakeWordSimulationDiagnosticReport?>(null)
    }
    val effectiveSimReport = internalSimReport ?: simulationDiagnosticReport

    val quickSuggestions = remember {
        com.example.voice.WakeWordRoutingEngine.COMMON_WAKE_WORD_SUGGESTIONS
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_custom_wake_word_prefs_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Custom Wake Word (DataStore & Prefs)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Define and update your custom wake phrase stored in Jetpack DataStore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_datastore_wake_word_badge")
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "Active: \"$savedWakeWordPhrase\"",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("settings_saved_custom_wake_word_badge")
                    )
                }
            }

            // Visual pulse animation that triggers on the screen when actively listening for the wake word
            com.example.ui.components.WakeWordScreenPulseAnimation(
                isActivelyListening = isActivelyListening,
                activeWakePhrase = savedWakeWordPhrase,
                audioRmsDb = if (effectiveSimReport != null && audioRmsDb <= 0f) effectiveSimReport.computedRmsDb else audioRmsDb,
                pollingIntervalMs = pollingIntervalMs,
                testTag = "settings_wake_word_screen_pulse_animation",
                modifier = Modifier.fillMaxWidth()
            )

            // Custom glowing status widget indicating the wake word listener & notification icon are active
            com.example.ui.components.WakeWordGlowingStatusWidget(
                isActivelyListening = isActivelyListening,
                activeWakePhrase = savedWakeWordPhrase,
                screenOffBatterySaverEnabled = screenOffBatterySaverEnabled,
                pollingIntervalMs = pollingIntervalMs,
                hideWhenInactive = false,
                testTag = "settings_wake_word_glowing_status_widget",
                modifier = Modifier.fillMaxWidth()
            )

            // Animated visual indicator showing when the microphone is active and listening for the wake word
            com.example.ui.components.MicrophoneWakeWordListeningIndicator(
                isMicrophoneActiveAndListening = isActivelyListening,
                activeWakePhrase = savedWakeWordPhrase,
                audioRmsDb = if (effectiveSimReport != null && audioRmsDb <= 0f) effectiveSimReport.computedRmsDb else audioRmsDb,
                lastHeardText = lastHeardText.ifBlank { effectiveSimReport?.simulatedTranscript.orEmpty() },
                hideWhenInactive = false,
                testTag = "settings_microphone_wake_word_listening_indicator",
                modifier = Modifier.fillMaxWidth()
            )

            // Subtle animated visual indicator showing when the app is actively listening for the wake word
            com.example.ui.components.ActiveWakeWordListeningOverlay(
                isActivelyListening = isActivelyListening,
                activeWakePhrase = savedWakeWordPhrase,
                audioRmsDb = if (effectiveSimReport != null && audioRmsDb <= 0f) effectiveSimReport.computedRmsDb else audioRmsDb,
                lastHeardText = lastHeardText.ifBlank { effectiveSimReport?.simulatedTranscript.orEmpty() }
            )

            OutlinedTextField(
                value = draftPhrase,
                onValueChange = { updated ->
                    draftPhrase = updated
                    validationError = null
                },
                label = { Text("Custom Wake Word Phrase") },
                placeholder = { Text("e.g., Hey Gemini, Hey Nebula, Computer") },
                singleLine = true,
                isError = validationError != null,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Wake Word Input"
                    )
                },
                trailingIcon = {
                    if (draftPhrase.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                draftPhrase = ""
                                validationError = null
                            },
                            modifier = Modifier.testTag("settings_clear_custom_wake_word_prefs_input")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear wake word input"
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_custom_wake_word_prefs_input")
            )

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings_custom_wake_word_prefs_error")
                )
            }

            // Quick preset suggestion chips (with fallback when custom input is left blank)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Common Wake Word Suggestions (tap to apply, or leave input blank to auto-use):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_common_wake_word_suggestions_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickSuggestions.forEach { suggestion ->
                        val slug = suggestion.lowercase(Locale.US).replace(" ", "_")
                        val isSelected = draftPhrase.trim().equals(suggestion, ignoreCase = true) ||
                            (draftPhrase.isBlank() && lastSelectedCommonSuggestion.equals(suggestion, ignoreCase = true))
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                lastSelectedCommonSuggestion = suggestion
                                draftPhrase = suggestion
                                validationError = null
                                com.example.voice.LocalKeywordSpotterEngine.syncConfiguration(
                                    customWakePhrase = suggestion,
                                    sensitivityThreshold = localDataStoreSensitivity
                                )
                            },
                            label = {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("settings_custom_wake_word_chip_$slug")
                        )
                    }
                }
            }

            // Pre-Selected Chat, 4-Tier Priority Level & Location Context Selector
            val chatPriorityMap by com.example.util.AppSettingsManager.chatPriorityMap.collectAsState()
            val activeLocProfile by com.example.util.AppSettingsManager.activeLocationProfile.collectAsState()
            var hasCoarseLocation by remember {
                mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            val coarseLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasCoarseLocation = granted
            }

            if (availableChats.isNotEmpty()) {
                val activePreselectedChat = remember(availableChats, preselectedChatId) {
                    availableChats.firstOrNull { it.id == preselectedChatId }
                        ?: availableChats.firstOrNull { it.isDefaultWakeChat }
                        ?: availableChats.first()
                }
                val currentChatPriority = chatPriorityMap[activePreselectedChat.id]
                    ?: com.example.util.ChatPriorityLevel.NORMAL

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_custom_wake_preselected_chat_selector")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Pre-Selected Chat on Wake-Word Detection: ${activePreselectedChat.title}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            availableChats.take(6).forEach { chatItem ->
                                val isSelectedChat = chatItem.id == activePreselectedChat.id
                                FilterChip(
                                    selected = isSelectedChat,
                                    onClick = { onSelectPreselectedChat(chatItem.id) },
                                    label = {
                                        Text(
                                            text = chatItem.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_preselected_chat_chip_${chatItem.id}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        Text(
                            text = "Priority Level for \"${activePreselectedChat.title}\": ${currentChatPriority.displayLabel}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_chat_priority_level_row"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.example.util.ChatPriorityLevel.entries.forEach { prio ->
                                FilterChip(
                                    selected = currentChatPriority == prio,
                                    onClick = {
                                        com.example.util.AppSettingsManager.setChatPriorityLevel(
                                            context = context,
                                            chatId = activePreselectedChat.id,
                                            priority = prio
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "${prio.shortBadge} (+${prio.weight})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_chat_priority_chip_${prio.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Location & Place Context: ${activeLocProfile.displayLabel}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (!hasCoarseLocation) {
                                TextButton(
                                    onClick = {
                                        coarseLocationLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                                    },
                                    modifier = Modifier.testTag("settings_grant_coarse_location_button")
                                ) {
                                    Text("Enable Coarse GPS", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_location_profile_row"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.example.util.ContextLocationProfile.entries.forEach { locProfile ->
                                FilterChip(
                                    selected = activeLocProfile == locProfile,
                                    onClick = {
                                        com.example.util.AppSettingsManager.setActiveLocationProfile(
                                            context = context,
                                            profile = locProfile
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = locProfile.displayLabel,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_location_profile_chip_${locProfile.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Quiet Mode Toggle in Settings
                        val quietModeEnabled by com.example.util.AppSettingsManager.quietModeEnabled.collectAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_quiet_mode_row"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Quiet Mode (Pause Wake Word Detection)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (quietModeEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (quietModeEnabled) {
                                        "Wake words are currently muted across background & foreground listeners"
                                    } else {
                                        "Active — wake words respond immediately"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = quietModeEnabled,
                                onCheckedChange = { enabled ->
                                    com.example.util.AppSettingsManager.setQuietModeEnabled(context, enabled)
                                    com.example.voice.WakeWordVoiceService.setQuietMode(enabled, context)
                                },
                                modifier = Modifier.testTag("settings_quiet_mode_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Wake Word Action Selector for current custom wake phrase
                        val wakeWordActionMap by com.example.util.AppSettingsManager.wakeWordActionMap.collectAsState()
                        val effectivePhraseKey = draftPhrase.trim().ifBlank { savedWakeWordPhrase }.lowercase(Locale.US)
                        val selectedWakeAction = wakeWordActionMap[effectivePhraseKey]
                            ?: com.example.util.WakeWordActionType.OPEN_CHAT
                        Text(
                            text = "Action When \"${draftPhrase.ifBlank { savedWakeWordPhrase }}\" Is Spoken:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_wake_word_action_row"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.example.util.WakeWordActionType.entries.forEach { actionType ->
                                FilterChip(
                                    selected = selectedWakeAction == actionType,
                                    onClick = {
                                        com.example.util.AppSettingsManager.setWakeWordAction(
                                            context = context,
                                            phrase = draftPhrase.ifBlank { savedWakeWordPhrase },
                                            actionType = actionType
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = actionType.displayLabel,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_wake_action_chip_${actionType.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Category Wake Word Assignments (Work, Personal, Learning)
                        val categoryWakeMap by com.example.util.AppSettingsManager.categoryWakeWordMap.collectAsState()
                        Text(
                            text = "Category Wake Words (Opens Highest-Priority Chat in Category):",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_category_wake_words_row"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Work", "Personal", "Learning").forEach { categoryName ->
                                val assignedCatPhrase = categoryWakeMap[categoryName] ?: "Hey Gemini"
                                FilterChip(
                                    selected = draftPhrase.trim().equals(assignedCatPhrase, ignoreCase = true),
                                    onClick = {
                                        val phraseToAssign = draftPhrase.trim().ifBlank { savedWakeWordPhrase }
                                        com.example.util.AppSettingsManager.setCategoryWakeWord(
                                            context = context,
                                            category = categoryName,
                                            wakePhrase = phraseToAssign
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "$categoryName: \"$assignedCatPhrase\"",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_category_wake_chip_${categoryName.lowercase(Locale.US)}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Configurable Context-to-Chat Trigger Rules
                        val contextRules by com.example.util.AppSettingsManager.contextTriggerRules.collectAsState()
                        Text(
                            text = "Context-to-Chat Trigger Rules (Tap to bind active target chat):",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_context_trigger_rules_row"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.example.util.ContextTriggerType.entries.forEach { triggerType ->
                                val existingRule = contextRules[triggerType]
                                val boundChatTitle = existingRule?.let { rule ->
                                    availableChats.firstOrNull { it.id == rule.targetChatId }?.title
                                }
                                FilterChip(
                                    selected = existingRule != null,
                                    onClick = {
                                        val targetId = activePreselectedChat.id
                                        if (existingRule != null && existingRule.targetChatId == targetId) {
                                            com.example.util.AppSettingsManager.removeContextTriggerRule(
                                                context = context,
                                                contextType = triggerType
                                            )
                                        } else if (targetId > 0L) {
                                            com.example.util.AppSettingsManager.setContextTriggerRule(
                                                context = context,
                                                contextType = triggerType,
                                                targetChatId = targetId,
                                                autoOpenDirectly = true
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = if (boundChatTitle != null) {
                                                "${triggerType.displayLabel} → $boundChatTitle"
                                            } else {
                                                triggerType.displayLabel
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("settings_context_rule_chip_${triggerType.name.lowercase(Locale.US)}")
                                )
                            }
                        }
                    }
                }
            }

            // Wake Word Detection Sensitivity Threshold Slider (Saved in Jetpack DataStore)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_datastore_wake_word_sensitivity_section")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wake Word Detection Sensitivity Threshold (DataStore)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Saved in Jetpack DataStore • $sensitivityTierLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "${(localDataStoreSensitivity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("settings_datastore_wake_word_sensitivity_value_text")
                            )
                        }
                    }

                    Slider(
                        value = localDataStoreSensitivity,
                        onValueChange = { updatedValue ->
                            val coerced = updatedValue.coerceIn(0.50f, 0.98f)
                            localDataStoreSensitivity = coerced
                            coroutineScope.launch {
                                com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(
                                    context = context,
                                    sensitivity = coerced
                                )
                            }
                            onSetWakeWordSensitivity(coerced)
                        },
                        valueRange = 0.50f..0.98f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_datastore_wake_word_sensitivity_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "50% (More Permissive)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "78% (Balanced)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "98% (Strict Match)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Quick Sensitivity Preset Chips (Synced to DataStore & Keyword Spotter)
                    val sensitivityPresets = remember {
                        listOf(
                            "Permissive" to 0.62f,
                            "Balanced" to 0.78f,
                            "Strict" to 0.88f,
                            "Ultra-Precise" to 0.94f
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sensitivityPresets.forEach { (presetLabel, presetValue) ->
                            val presetSlug = presetLabel.lowercase(Locale.US).replace("-", "_")
                            val isSelectedPreset = kotlin.math.abs(localDataStoreSensitivity - presetValue) < 0.025f
                            FilterChip(
                                selected = isSelectedPreset,
                                onClick = {
                                    localDataStoreSensitivity = presetValue
                                    coroutineScope.launch {
                                        com.example.util.WakeWordDataStoreManager.saveWakeWordSensitivity(
                                            context = context,
                                            sensitivity = presetValue
                                        )
                                    }
                                    com.example.voice.LocalKeywordSpotterEngine.syncConfiguration(
                                        customWakePhrase = draftPhrase.ifBlank { savedWakeWordPhrase },
                                        sensitivityThreshold = presetValue
                                    )
                                    onSetWakeWordSensitivity(presetValue)
                                },
                                label = {
                                    Text(
                                        text = "$presetLabel ${(presetValue * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag("settings_sensitivity_preset_chip_$presetSlug")
                            )
                        }
                    }

                    // Live Hybrid Keyword Spotter Confidence vs. Threshold Meter
                    val spotterTelemetry by com.example.voice.WakeWordVoiceService.keywordSpotterTelemetry.collectAsState()
                    val displayConfidence = if (effectiveSimReport != null && spotterTelemetry.combinedSpotterConfidence <= 0f) {
                        effectiveSimReport.phraseSimilarityScore
                    } else {
                        spotterTelemetry.combinedSpotterConfidence
                    }
                    val isAboveThresholdNow = displayConfidence >= localDataStoreSensitivity
                    val meterColor = if (isAboveThresholdNow) {
                        Color(0xFF10B981)
                    } else {
                        Color(0xFF38BDF8)
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, meterColor.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .testTag("settings_keyword_spotter_confidence_meter")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Keyword Spotter Live Confidence: ${(displayConfidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = meterColor,
                                    modifier = Modifier.testTag("settings_keyword_spotter_confidence_value_text")
                                )
                                Text(
                                    text = if (isAboveThresholdNow) {
                                        "ABOVE THRESHOLD (${(localDataStoreSensitivity * 100).toInt()}%)"
                                    } else {
                                        "THRESHOLD ${(localDataStoreSensitivity * 100).toInt()}%"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = meterColor
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(displayConfidence.coerceIn(0.02f, 1f))
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(meterColor)
                                )
                            }

                            Text(
                                text = "Acoustic Energy: ${(spotterTelemetry.acousticEnergyScore * 100).toInt()}% • Phonetic Match: ${(spotterTelemetry.phoneticPhraseScore * 100).toInt()}% • Peak: ${(spotterTelemetry.peakSpotterConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val trimmed = draftPhrase.trim()
                        val resolvedToSave = if (trimmed.isBlank()) {
                            com.example.voice.WakeWordRoutingEngine.resolveWakeWordOrSuggestedFallback(
                                inputPhrase = trimmed,
                                preferredSuggestion = lastSelectedCommonSuggestion
                            )
                        } else {
                            trimmed
                        }
                        if (resolvedToSave.length < 2) {
                            validationError = "Please enter a wake word with at least 2 characters."
                        } else {
                            validationError = null
                            draftPhrase = resolvedToSave
                            onSaveWakeWord(resolvedToSave)
                            confirmationMessage = if (trimmed.isBlank()) {
                                "No custom phrase entered — applied suggested wake word \"$resolvedToSave\""
                            } else {
                                "Saved \"$resolvedToSave\" to Jetpack DataStore & SharedPreferences"
                            }
                            try {
                                android.widget.Toast.makeText(
                                    context,
                                    "Saved custom wake word: \"$resolvedToSave\"",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_save_custom_wake_word_prefs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Wake Word")
                }

                OutlinedButton(
                    onClick = {
                        validationError = null
                        draftPhrase = com.example.util.AppSettingsManager.DEFAULT_CUSTOM_WAKE_WORD_PHRASE
                        onResetDefault()
                        confirmationMessage =
                            "Reset to default \"${com.example.util.AppSettingsManager.DEFAULT_CUSTOM_WAKE_WORD_PHRASE}\" in DataStore & SharedPreferences"
                    },
                    modifier = Modifier.testTag("settings_reset_custom_wake_word_prefs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset Default")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Diagnostic Button to Simulate Wake Word Detection Flow without speaking out loud
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Simulates the wake word detection flow to verify microphone pre-roll, phrase matching, and synchronous handoff without speaking out loud",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_simulate_wake_word_diagnostic_subtitle")
                )

                FilledTonalButton(
                    onClick = {
                        val targetPhrase = draftPhrase.trim().ifBlank { savedWakeWordPhrase }
                        val report = onSimulateWakeWordDetection?.invoke(targetPhrase)
                            ?: com.example.voice.WakeWordVoiceService.simulateWakeWordDetectionFlow(
                                context = context,
                                wakePhraseOverride = targetPhrase,
                                dispatchToDetectionCallbacks = false
                            )
                        internalSimReport = report
                        try {
                            android.widget.Toast.makeText(
                                context,
                                "Simulated wake word \"${report.matchedPhrase}\" — Diagnostic Passed!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_simulate_wake_word_diagnostic_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Simulate Wake Word Detection",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Simulate Wake Word Detection",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (effectiveSimReport != null) {
                    WakeWordSimulationDiagnosticResultBanner(
                        report = effectiveSimReport
                    )
                }
            }

            AnimatedVisibility(visible = confirmationMessage != null) {
                if (confirmationMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = confirmationMessage!!,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF10B981),
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("settings_custom_wake_word_saved_confirmation")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays the structured diagnostic verification results from [com.example.voice.WakeWordSimulationDiagnosticReport]
 * after simulating the wake word detection flow in Settings.
 */
@Composable
fun WakeWordSimulationDiagnosticResultBanner(
    report: com.example.voice.WakeWordSimulationDiagnosticReport,
    modifier: Modifier = Modifier
) {
    val accentColor = if (report.overallPassed) Color(0xFF10B981) else Color(0xFFF59E0B)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_wake_word_simulation_diagnostic_result")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (report.overallPassed) "DIAGNOSTIC PASSED" else "DIAGNOSTIC PARTIAL",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor,
                        modifier = Modifier.testTag("settings_wake_word_simulation_status_badge")
                    )
                }
                Text(
                    text = "${report.elapsedDurationMs}ms • Handoff #${report.handoffReleaseCountAfter}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Simulated: \"${report.simulatedTranscript}\" → Matched: \"${report.matchedPhrase}\" (${report.targetChatTitle})",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("settings_wake_word_simulation_summary_text")
            )

            Text(
                text = report.diagnosticSteps.getOrElse(0) {
                    "1. Mic & Pre-Roll: Verified (${report.preRollPcmBytesVerified}B PCM @ ${String.format(Locale.US, "%.1f", report.computedRmsDb)} dB)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_wake_word_simulation_mic_check")
            )

            Text(
                text = report.diagnosticSteps.getOrElse(1) {
                    "2. Wake-Word Processing: Matched \"${report.matchedPhrase}\" (${(report.phraseSimilarityScore * 100).toInt()}% similarity)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_wake_word_simulation_processing_check")
            )

            Text(
                text = report.diagnosticSteps.getOrElse(2) {
                    "3. Synchronous Mic Handoff: Verified (serviceAudioRecord=null, Handoff #${report.handoffReleaseCountAfter})"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_wake_word_simulation_handoff_check")
            )
        }
    }
}


