package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.importer.FileProcessingService

/**
 * Modal rationale dialog shown when RECORD_AUDIO permission is denied or needs user confirmation.
 * Explains necessity for wake word detection, voice messages, and snippet training,
 * and provides direct actions to retry the runtime permission prompt, grant zero-permission SAF file access,
 * or open system app settings.
 */
@Composable
fun AudioPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onRetryPermissionRequest: (() -> Unit)? = null,
    onRequestFileAccess: (() -> Unit)? = null
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("audio_permission_rationale_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        title = {
            Text(
                text = "Microphone & File Access",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Gemini Wake requires microphone access (RECORD_AUDIO) to listen for custom wake words (\"Hey Gemini\", \"Code Mode\"), dictate voice messages, and capture audio snippets for training.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Voice audio is processed on-device for wake word matching. File imports and backups use Android's zero-permission Storage Access Framework (SAF) document picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                if (onRetryPermissionRequest != null || onRequestFileAccess != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onRetryPermissionRequest != null) {
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onRetryPermissionRequest()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("retry_microphone_permission_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Request Microphone Permission Again")
                            }
                        }
                        if (onRequestFileAccess != null) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onRequestFileAccess()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rationale_grant_file_access_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Grant File Access (SAF Document Picker)")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onOpenSettings()
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                    onDismiss()
                },
                modifier = Modifier.testTag("open_settings_permission_button")
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_permission_dialog_button")
            ) {
                Text("Not Now")
            }
        }
    )
}

/**
 * Interactive runtime permission banner for Microphone (`RECORD_AUDIO`) and zero-permission
 * Storage Access Framework (`OpenDocument`) file access.
 *
 * Automatically refreshes permission state on lifecycle `ON_RESUME` and provides:
 * - One-tap runtime `RECORD_AUDIO` request (`ActivityResultContracts.RequestPermission()`)
 * - One-tap SAF Document Picker launch (`ActivityResultContracts.OpenDocument()`) with persistable URI permission grant
 * - Direct shortcut to Android System App Settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`)
 */
@Composable
fun MicrophonePermissionStatusBanner(
    modifier: Modifier = Modifier,
    hideWhenGranted: Boolean = false,
    onMicrophonePermissionChanged: (Boolean) -> Unit = {},
    onActivateVoiceListening: (() -> Unit)? = null,
    onFileAccessGranted: ((Uri) -> Unit)? = null,
    testTag: String = "microphone_permission_status_banner"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun countPersistedSafUris(): Int = try {
        context.contentResolver.persistedUriPermissions.size
    } catch (_: Exception) {
        0
    }

    var isMicGranted by remember { mutableStateOf(checkMicGranted()) }
    var persistedFileUriCount by remember { mutableIntStateOf(countPersistedSafUris()) }
    var showLocalRationaleDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = checkMicGranted()
                if (grantedNow != isMicGranted) {
                    isMicGranted = grantedNow
                    onMicrophonePermissionChanged(grantedNow)
                } else {
                    isMicGranted = grantedNow
                }
                persistedFileUriCount = countPersistedSafUris()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val runtimeMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isMicGranted = granted
        onMicrophonePermissionChanged(granted)
        if (granted) {
            onActivateVoiceListening?.invoke()
        } else {
            showLocalRationaleDialog = true
        }
    }

    val safFileAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            persistedFileUriCount = countPersistedSafUris()
            onFileAccessGranted?.invoke(uri)
        }
    }

    if (showLocalRationaleDialog) {
        AudioPermissionRationaleDialog(
            onDismiss = { showLocalRationaleDialog = false },
            onRetryPermissionRequest = {
                runtimeMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRequestFileAccess = {
                safFileAccessLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES)
            }
        )
    }

    AnimatedVisibility(visible = !hideWhenGranted || !isMicGranted) {
        val statusAccent = if (isMicGranted) {
            Color(0xFF10B981)
        } else {
            MaterialTheme.colorScheme.error
        }

        Card(
            modifier = modifier
                .fillMaxWidth()
                .testTag(testTag),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isMicGranted) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                }
            ),
            border = BorderStroke(
                width = 1.dp,
                color = statusAccent.copy(alpha = 0.45f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(statusAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMicGranted) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = if (isMicGranted) {
                                    "Microphone permission granted"
                                } else {
                                    "Microphone permission required"
                                },
                                tint = statusAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isMicGranted) {
                                    "Microphone & File Permissions Ready"
                                } else {
                                    "Microphone Permission Required for Voice Listening"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isMicGranted) {
                                    "RECORD_AUDIO granted • SAF persisted files: $persistedFileUriCount"
                                } else {
                                    "Grant RECORD_AUDIO to enable wake word detection & hands-free voice listening."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusAccent.copy(alpha = 0.16f),
                        modifier = Modifier.testTag("${testTag}_status_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isMicGranted) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = statusAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = if (isMicGranted) "GRANTED" else "REQUIRED",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = statusAccent
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isMicGranted) {
                        Button(
                            onClick = {
                                runtimeMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("${testTag}_grant_mic_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Grant Microphone",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    } else if (onActivateVoiceListening != null) {
                        FilledTonalButton(
                            onClick = onActivateVoiceListening,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.1f)
                                .testTag("${testTag}_grant_mic_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Verify Mic Ready",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            safFileAccessLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("${testTag}_grant_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "File Access (SAF)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("${testTag}_open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open App Permission Settings",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
