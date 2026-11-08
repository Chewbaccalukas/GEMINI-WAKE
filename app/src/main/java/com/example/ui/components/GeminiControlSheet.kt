package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cache.BufferMetrics
import com.example.data.model.ChatSession
import com.example.ui.theme.AppTheme
import com.example.voice.HandsFreeVoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiControlSheet(
    chat: ChatSession?,
    bufferMetrics: BufferMetrics,
    appTheme: AppTheme,
    onSetAppTheme: (AppTheme) -> Unit,
    isHandsFreeActive: Boolean,
    handsFreeVoiceState: HandsFreeVoiceState,
    onToggleHandsFree: (Boolean) -> Unit,
    wakeWordSensitivity: Float,
    onSetWakeWordSensitivity: (Float) -> Unit,
    stopwatchSeconds: Long,
    isStopwatchRunning: Boolean,
    onToggleStopwatch: () -> Unit,
    onResetStopwatch: () -> Unit,
    onOpenCoupler: () -> Unit,
    onOpenBufferBridge: () -> Unit,
    onOpenSettingsScreen: () -> Unit = {},
    onOpenDiagnosticLog: () -> Unit = {},
    onEditChat: () -> Unit,
    onExportConversation: () -> Unit,
    onShareLogsAsJson: () -> Unit = {},
    onShareLogsAsCsv: () -> Unit = {},
    notifyActiveChatReplies: Boolean = true,
    onToggleNotifyActiveChatReplies: (Boolean) -> Unit = {},
    permissionsResetCount: Int = 0,
    onResetPermissions: () -> Unit = {},
    onRequestRequiredPermissions: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header with Close "X" button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Gemini Control & Settings",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("control_sheet_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. Hands-Free & Wake Word Section
        ControlSection(title = "Voice & Hands-Free", icon = Icons.Default.Hearing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-Free Standby", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Auto-resume listening after Gemini speaks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isHandsFreeActive,
                    onCheckedChange = onToggleHandsFree,
                    modifier = Modifier.testTag("control_sheet_hands_free_switch")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Wake Word Sensitivity",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${(wakeWordSensitivity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = wakeWordSensitivity,
                onValueChange = onSetWakeWordSensitivity,
                valueRange = 0.5f..0.99f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_sheet_sensitivity_slider")
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onOpenSettingsScreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_sheet_open_settings_button")
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Customize Wake Words & Sensitivity")
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenDiagnosticLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_sheet_open_diagnostics_button")
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Speech & Mutex Diagnostic Log")
            }
        }

        // 2. Chat Export & Session Tools
        ControlSection(title = "Chat History & Export", icon = Icons.Default.Description) {
            if (chat != null) {
                Text(
                    text = "Active: \"${chat.title}\" (Trigger: \"${chat.wakeWord}\")",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onShareLogsAsJson,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_json_logs_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Interaction Logs (.json)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onShareLogsAsCsv,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_csv_logs_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Chat & Usage Data (.csv)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onExportConversation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_conversation_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Conversation (.txt)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onEditChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_chat_samples_button")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Chat & Wake Samples")
                }
            } else {
                Text(
                    text = "Export all Room database chat interaction logs and share via temporary FileProvider URI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onShareLogsAsJson,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_json_logs_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Interaction Logs (.json)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onShareLogsAsCsv,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_csv_logs_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Chat & Usage Data (.csv)")
                }
            }
        }

        // 3. Telemetry: Clock & Stopwatch
        ControlSection(title = "Session Telemetry", icon = Icons.Default.Timer) {
            ScreenClockCard(
                stopwatchSeconds = stopwatchSeconds,
                isStopwatchRunning = isStopwatchRunning,
                accentColor = MaterialTheme.colorScheme.primary,
                onToggleStopwatch = onToggleStopwatch,
                onResetStopwatch = onResetStopwatch
            )
        }

        // 4. Infrastructure: Coupler & Bridge
        ControlSection(title = "Infrastructure & Data", icon = Icons.Default.Storage) {
            OutlinedButton(
                onClick = onOpenCoupler,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_sheet_coupler_btn")
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gemini Coupler & Piggyback")
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenBufferBridge,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_sheet_bridge_btn")
            ) {
                Icon(Icons.Default.Memory, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Memory Buffer & Chrome Bridge")
            }
            Spacer(modifier = Modifier.height(10.dp))
            UsageLimitVsCacheComparisonCard(
                metrics = bufferMetrics,
                showComparison = true,
                onToggleComparison = {}
            )
        }

        // 5. Personalization / Appearance
        ControlSection(title = "Appearance", icon = Icons.Default.Palette) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppTheme.values().forEach { theme ->
                    val isSelected = appTheme == theme
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSetAppTheme(theme) },
                        label = { Text(theme.displayName) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 6. Notifications & Permissions
        ControlSection(title = "Notifications & Permissions", icon = Icons.Default.Security) {
            PermissionsAndNotificationSettingsCard(
                notifyActiveChatReplies = notifyActiveChatReplies,
                onToggleNotifyActiveChatReplies = onToggleNotifyActiveChatReplies,
                permissionsResetCount = permissionsResetCount,
                onResetPermissions = onResetPermissions,
                onRequestRequiredPermissions = onRequestRequiredPermissions
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun ControlSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
        HorizontalDivider(
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}
