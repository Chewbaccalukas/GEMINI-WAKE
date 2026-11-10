package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firebase.FirestoreChatDoc
import com.example.data.firebase.FirestoreMessageDoc
import com.example.data.model.ChatSession
import com.example.ui.components.getIconForName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Composable that displays the list of available Gemini chat sessions fetched from
 * Cloud Firestore (`/users/{userId}/chats`), allowing the user to select which session opens
 * automatically upon wake-word detection.
 *
 * Uses **Option 1 Multi-Sync**: selecting a chat updates `isDefaultWakeChat = true` across
 * Cloud Firestore, local Room (`ChatDao`), and Jetpack `DataStore` (`WakeWordDataStoreManager`)
 * so all signed-in devices stay synchronized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirestoreWakeChatSelectorScreen(
    cloudChats: List<FirestoreChatDoc>,
    cloudMessages: List<FirestoreMessageDoc> = emptyList(),
    localChats: List<ChatSession> = emptyList(),
    selectedWakeTargetChatId: Long? = null,
    selectedWakeTargetDocId: String? = null,
    activeCustomWakeWord: String = "Hey Gemini",
    wakeWordSensitivity: Float = 0.78f,
    cloudSyncStatus: String = "Synced with Cloud Firestore",
    isCloudOperationInProgress: Boolean = false,
    onSelectWakeTargetChat: (FirestoreChatDoc) -> Unit,
    onOpenFirestoreChat: (FirestoreChatDoc) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    var searchQuery by rememberSaveable { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("firestore_wake_selector_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Firestore Wake-Word Target Chats",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Multi-Sync wake target across Firestore & Room",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSyncNow,
                        enabled = !isCloudOperationInProgress,
                        modifier = Modifier.testTag("firestore_wake_selector_sync_button")
                    ) {
                        if (isCloudOperationInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Sync Firestore Chat Sessions",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("firestore_wake_chat_selector_screen")
        ) {
            FirestoreWakeChatSelectorContent(
                cloudChats = cloudChats,
                cloudMessages = cloudMessages,
                localChats = localChats,
                selectedWakeTargetChatId = selectedWakeTargetChatId,
                selectedWakeTargetDocId = selectedWakeTargetDocId,
                activeCustomWakeWord = activeCustomWakeWord,
                wakeWordSensitivity = wakeWordSensitivity,
                cloudSyncStatus = cloudSyncStatus,
                isCloudOperationInProgress = isCloudOperationInProgress,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSelectWakeTargetChat = onSelectWakeTargetChat,
                onOpenFirestoreChat = onOpenFirestoreChat,
                onSyncNow = onSyncNow,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Reusable Composable content displaying the active multi-synced wake-word target banner,
 * search bar, and list of available Gemini chat sessions fetched from Firestore.
 */
@Composable
fun FirestoreWakeChatSelectorContent(
    cloudChats: List<FirestoreChatDoc>,
    cloudMessages: List<FirestoreMessageDoc> = emptyList(),
    localChats: List<ChatSession> = emptyList(),
    selectedWakeTargetChatId: Long? = null,
    selectedWakeTargetDocId: String? = null,
    activeCustomWakeWord: String = "Hey Gemini",
    wakeWordSensitivity: Float = 0.78f,
    cloudSyncStatus: String = "Synced with Cloud Firestore",
    isCloudOperationInProgress: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onSelectWakeTargetChat: (FirestoreChatDoc) -> Unit,
    onOpenFirestoreChat: (FirestoreChatDoc) -> Unit = {},
    onSyncNow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember {
        SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
    }

    // Merge remote Firestore chats with any local persistent chats that are being synced so the user
    // always sees all available Gemini chat sessions.
    val effectiveFirestoreChats = remember(cloudChats, localChats) {
        if (cloudChats.isNotEmpty()) {
            cloudChats
        } else {
            localChats.map { session ->
                FirestoreChatDoc.fromChatSession(
                    userId = session.userId ?: "local_sync",
                    chat = session
                )
            }
        }
    }

    val resolvedActiveDoc = remember(
        effectiveFirestoreChats,
        localChats,
        selectedWakeTargetChatId,
        selectedWakeTargetDocId
    ) {
        effectiveFirestoreChats.firstOrNull { doc ->
            (!selectedWakeTargetDocId.isNullOrBlank() && doc.id == selectedWakeTargetDocId) ||
                (selectedWakeTargetChatId != null && selectedWakeTargetChatId > 0L && doc.localChatId == selectedWakeTargetChatId)
        } ?: effectiveFirestoreChats.firstOrNull { it.isDefaultWakeChat }
            ?: run {
                val localDefaultId = localChats.firstOrNull { it.isDefaultWakeChat }?.id
                if (localDefaultId != null && localDefaultId > 0L) {
                    effectiveFirestoreChats.firstOrNull { it.localChatId == localDefaultId }
                } else {
                    effectiveFirestoreChats.firstOrNull()
                }
            }
    }

    val filteredDocs = remember(effectiveFirestoreChats, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        if (q.isBlank()) {
            effectiveFirestoreChats
        } else {
            effectiveFirestoreChats.filter { doc ->
                doc.title.lowercase(Locale.US).contains(q) ||
                    doc.wakeWord.lowercase(Locale.US).contains(q) ||
                    doc.systemInstruction.lowercase(Locale.US).contains(q)
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Active Multi-Sync Wake Target Summary Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .testTag("firestore_wake_selector_active_banner"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Opens on Wake Word: \"${resolvedActiveDoc?.title ?: "Gemini Assistant"}\"",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("firestore_wake_selector_active_title")
                            )
                            Text(
                                text = "Wake Phrase: \"${resolvedActiveDoc?.wakeWord?.ifBlank { activeCustomWakeWord } ?: activeCustomWakeWord}\" • Threshold: ${(wakeWordSensitivity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("firestore_wake_selector_multisync_badge")
                    ) {
                        Text(
                            text = "MULTI-SYNC ACTIVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "$cloudSyncStatus • Selecting a session below syncs your wake-word target across Cloud Firestore, local Room, and DataStore.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Search & Filter Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search Firestore Gemini chat sessions or wake words...") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Firestore Chats"
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.testTag("firestore_wake_selector_clear_search")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search"
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("firestore_wake_selector_search_input")
        )

        if (filteredDocs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("firestore_wake_selector_empty_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No Matching Firestore Chat Sessions",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Sync with Cloud Firestore or clear your search filter to select a Gemini chat session for wake-word detection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = !isCloudOperationInProgress
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Firestore Sessions")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("firestore_wake_selector_list"),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredDocs,
                    key = { doc -> doc.id.ifBlank { "wake_target_${doc.localChatId}" } }
                ) { cloudDoc ->
                    val resolvedSession = remember(cloudDoc) { cloudDoc.toChatSession() }
                    val targetLocalId = if (cloudDoc.localChatId > 0L) cloudDoc.localChatId else resolvedSession.id
                    val docKey = cloudDoc.id.ifBlank { targetLocalId.toString() }

                    val isSelectedWakeTarget = remember(
                        cloudDoc,
                        resolvedActiveDoc,
                        selectedWakeTargetChatId,
                        selectedWakeTargetDocId
                    ) {
                        (resolvedActiveDoc != null &&
                            ((cloudDoc.id.isNotBlank() && cloudDoc.id == resolvedActiveDoc.id) ||
                                (targetLocalId > 0L && targetLocalId == resolvedActiveDoc.localChatId))) ||
                            cloudDoc.isDefaultWakeChat
                    }

                    val messageCount = remember(cloudMessages, targetLocalId) {
                        cloudMessages.count { it.localChatId == targetLocalId && it.content.isNotBlank() }
                    }
                    val accentColor = remember(cloudDoc.themeColorHex) {
                        try {
                            Color(android.graphics.Color.parseColor(cloudDoc.themeColorHex.trim()))
                        } catch (_: Throwable) {
                            Color(0xFF38BDF8)
                        }
                    }
                    val updatedTimeStr = remember(cloudDoc.updatedAt, cloudDoc.createdAt) {
                        val ts = cloudDoc.updatedAt?.toDate() ?: cloudDoc.createdAt?.toDate() ?: Date()
                        dateFormatter.format(ts)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectWakeTargetChat(cloudDoc) }
                            .testTag("firestore_wake_chat_card_$docKey"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelectedWakeTarget) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        border = BorderStroke(
                            width = if (isSelectedWakeTarget) 1.5.dp else 1.dp,
                            color = if (isSelectedWakeTarget) {
                                Color(0xFF10B981)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(accentColor.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = getIconForName(cloudDoc.iconName),
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = cloudDoc.title.ifBlank { "Gemini Chat" },
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isSelectedWakeTarget) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF10B981).copy(alpha = 0.18f),
                                                modifier = Modifier.testTag("firestore_wake_target_selected_badge_$docKey")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Active wake target",
                                                        tint = Color(0xFF10B981),
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(
                                                        text = "OPENS ON WAKE",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = Color(0xFF10B981)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Mic,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "\"${cloudDoc.wakeWord.ifBlank { "Hey Gemini" }}\"",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Text(
                                            text = "$messageCount msg${if (messageCount == 1) "" else "s"} • $updatedTimeStr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = if (isSelectedWakeTarget) {
                                        Icons.Default.RadioButtonChecked
                                    } else {
                                        Icons.Default.RadioButtonUnchecked
                                    },
                                    contentDescription = if (isSelectedWakeTarget) {
                                        "Selected as wake-word target"
                                    } else {
                                        "Select as wake-word target"
                                    },
                                    tint = if (isSelectedWakeTarget) {
                                        Color(0xFF10B981)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { onSelectWakeTargetChat(cloudDoc) },
                                    enabled = !isCloudOperationInProgress,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelectedWakeTarget) {
                                            Color(0xFF10B981)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .testTag("select_firestore_wake_target_button_$docKey")
                                ) {
                                    Icon(
                                        imageVector = if (isSelectedWakeTarget) {
                                            Icons.Default.CheckCircle
                                        } else {
                                            Icons.Default.Hearing
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isSelectedWakeTarget) {
                                            "Active Wake Target (Synced)"
                                        } else {
                                            "Select for Wake-Word (Multi-Sync)"
                                        },
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onOpenFirestoreChat(cloudDoc) },
                                    enabled = !isCloudOperationInProgress,
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .testTag("open_firestore_wake_chat_button_$docKey")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Open Chat",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact card embedded in [SettingsScreen] that displays the list of available Gemini chat sessions
 * fetched from Firestore and allows selecting which session opens upon wake-word detection (Multi-Sync).
 */
@Composable
fun FirestoreWakeChatSelectorCard(
    cloudChats: List<FirestoreChatDoc>,
    localChats: List<ChatSession>,
    onSelectFirestoreWakeChat: (FirestoreChatDoc) -> Unit,
    onSelectLocalWakeChat: (Long) -> Unit,
    onOpenChat: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val effectiveDocs = remember(cloudChats, localChats) {
        if (cloudChats.isNotEmpty()) {
            cloudChats
        } else {
            localChats.map { session ->
                FirestoreChatDoc.fromChatSession(
                    userId = session.userId ?: "local_sync",
                    chat = session
                )
            }
        }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_firestore_wake_chat_selector_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.26f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
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
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Firestore Gemini Chat Wake Selector",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Select which Firestore chat session opens upon wake-word detection (Multi-Sync)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("settings_firestore_wake_selector_toggle_button")
                ) {
                    Text(
                        text = if (expanded) "Hide (${effectiveDocs.size})" else "Show (${effectiveDocs.size})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (effectiveDocs.isEmpty()) {
                        Text(
                            text = "No Gemini chat sessions available yet. Create or sync a chat to select it as your wake-word target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        effectiveDocs.forEach { doc ->
                            val resolved = doc.toChatSession()
                            val localId = if (doc.localChatId > 0L) doc.localChatId else resolved.id
                            val docKey = doc.id.ifBlank { localId.toString() }
                            val isSelected = doc.isDefaultWakeChat ||
                                localChats.any { it.id == localId && it.isDefaultWakeChat }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    Color(0xFF10B981).copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) {
                                        Color(0xFF10B981).copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSelectFirestoreWakeChat(doc)
                                        if (localId > 0L) {
                                            onSelectLocalWakeChat(localId)
                                        }
                                    }
                                    .testTag("settings_firestore_wake_chat_row_$docKey")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = doc.title.ifBlank { "Gemini Chat" },
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                            if (isSelected) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF10B981).copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "WAKE TARGET",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = Color(0xFF10B981),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "Wake Word: \"${doc.wakeWord.ifBlank { "Hey Gemini" }}\" • Multi-Synced in Firestore",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (localId > 0L) {
                                            IconButton(
                                                onClick = { onOpenChat(localId) },
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .testTag("settings_open_firestore_wake_chat_$docKey")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "Open Chat",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (isSelected) {
                                                Icons.Default.RadioButtonChecked
                                            } else {
                                                Icons.Default.RadioButtonUnchecked
                                            },
                                            contentDescription = if (isSelected) {
                                                "Selected wake-word target"
                                            } else {
                                                "Select as wake-word target"
                                            },
                                            tint = if (isSelected) {
                                                Color(0xFF10B981)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
