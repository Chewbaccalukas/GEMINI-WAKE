package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.AppDatabase
import com.example.data.model.ChatSession
import com.example.util.AppSettingsManager
import com.example.util.ChatPriorityLevel
import com.example.voice.ChatHistorySearchSnippet
import com.example.voice.WakeWordRoutingEngine

/**
 * Interactive visual dashboard on the Home screen providing:
 * 1. **Quiet Mode Quick Toggle** (`home_quiet_mode_toggle_button`) to temporarily disable/resume wake word detection.
 * 2. **Cross-Chat History Search Bar** (`home_cross_chat_search_input`) with keyword matching and highlighted conversation snippets.
 * 3. **Active Chats Priority & Category Dashboard** (`home_priority_dashboard_card`) displaying all active chats,
 *    their assigned categories (`Work`, `Personal`, `Learning`), a 4-tier priority indicator (`Critical`, `High`, `Normal`, `Low`),
 *    and Move Up / Move Down importance reordering controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveChatsPriorityAndSearchDashboardCard(
    chats: List<ChatSession>,
    chatCategoryMap: Map<Long, String>,
    onSelectChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val quietModeEnabled by AppSettingsManager.quietModeEnabled.collectAsState()
    val chatPriorityMap by AppSettingsManager.chatPriorityMap.collectAsState()
    val chatImportanceOrderMap by AppSettingsManager.chatImportanceOrderMap.collectAsState()
    val categoryWakeWordMap by AppSettingsManager.categoryWakeWordMap.collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isPriorityBoardExpanded by rememberSaveable { mutableStateOf(true) }

    val orderedChats = remember(chats, chatPriorityMap, chatImportanceOrderMap) {
        chats.filter { !it.isDeleted }.sortedWith(
            Comparator { a, b ->
                val orderA = chatImportanceOrderMap[a.id]
                val orderB = chatImportanceOrderMap[b.id]
                when {
                    orderA != null && orderB != null && orderA != orderB -> orderA.compareTo(orderB)
                    else -> {
                        val prioA = (chatPriorityMap[a.id] ?: ChatPriorityLevel.NORMAL).weight
                        val prioB = (chatPriorityMap[b.id] ?: ChatPriorityLevel.NORMAL).weight
                        if (prioA != prioB) {
                            prioB.compareTo(prioA)
                        } else {
                            b.lastUpdatedAt.compareTo(a.lastUpdatedAt)
                        }
                    }
                }
            }
        )
    }

    var searchSnippets by remember { mutableStateOf<List<ChatHistorySearchSnippet>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(searchQuery, chats, chatCategoryMap) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            searchSnippets = emptyList()
        } else {
            searchSnippets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.getDatabase(context)
                    val allMessages = db.messageDao().getAllActiveMessagesChronologicalSync()
                    WakeWordRoutingEngine.searchAcrossChatHistories(
                        rawQuery = q,
                        chats = chats,
                        messages = allMessages,
                        chatCategoryMap = chatCategoryMap,
                        maxResults = 25
                    )
                }.getOrDefault(emptyList())
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_priority_dashboard_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Quiet Mode Quick Toggle + Priority Dashboard Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (quietModeEnabled) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            AppSettingsManager.toggleQuietMode(context)
                        }
                        .testTag("home_quiet_mode_toggle_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (quietModeEnabled) {
                                Icons.Default.DoNotDisturbOn
                            } else {
                                Icons.Default.Hearing
                            },
                            contentDescription = if (quietModeEnabled) "Quiet Mode Active" else "Wake Word Listening Active",
                            tint = if (quietModeEnabled) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (quietModeEnabled) {
                                "Quiet Mode ON • Wake Paused"
                            } else {
                                "Quiet Mode OFF • Wake Ready"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (quietModeEnabled) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isPriorityBoardExpanded = !isPriorityBoardExpanded }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("home_toggle_priority_board_button")
                ) {
                    Text(
                        text = "Priority Board (${orderedChats.size})",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (isPriorityBoardExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Priority Dashboard",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Cross-Chat History Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_cross_chat_search_input"),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search across all chat histories by keyword...",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search chat histories",
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.testTag("home_cross_chat_search_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search query",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )

            // Search Results Snippets
            if (searchQuery.trim().isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_search_snippets_section"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (searchSnippets.isEmpty()) {
                            "No conversation snippets matched \"${searchQuery.trim()}\""
                        } else {
                            "${searchSnippets.size} matching conversation snippet(s)"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    searchSnippets.take(6).forEach { snippet ->
                        val highlightColor = MaterialTheme.colorScheme.primary
                        val annotatedSnippet = remember(snippet, highlightColor) {
                            buildAnnotatedString {
                                append(snippet.snippetText)
                                if (snippet.matchStartInSnippet in 0 until snippet.matchEndInSnippet &&
                                    snippet.matchEndInSnippet <= snippet.snippetText.length
                                ) {
                                    addStyle(
                                        style = SpanStyle(
                                            color = highlightColor,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        start = snippet.matchStartInSnippet,
                                        end = snippet.matchEndInSnippet
                                    )
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectChat(snippet.chatId) }
                                .testTag("search_snippet_item_${snippet.messageId}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${snippet.chatTitle} • ${if (snippet.sender.equals("user", true)) "You" else "Gemini"}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!snippet.chatCategory.isNullOrBlank()) {
                                        Text(
                                            text = snippet.chatCategory,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = annotatedSnippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Visual Priority & Category Dashboard
            AnimatedVisibility(visible = isPriorityBoardExpanded && orderedChats.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    // Category Wake Word Summary Chips (Work, Personal, Learning)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("Work", "Personal", "Learning").forEach { cat ->
                            val countInCat = orderedChats.count {
                                (chatCategoryMap[it.id] ?: "Work").equals(cat, ignoreCase = true)
                            }
                            val catWake = categoryWakeWordMap[cat]
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.testTag("dashboard_category_pill_$cat")
                            ) {
                                Text(
                                    text = if (!catWake.isNullOrBlank()) {
                                        "$cat ($countInCat) • \"$catWake\""
                                    } else {
                                        "$cat ($countInCat)"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Active Chat Importance & 4-Tier Priority Rows
                    val orderedIds = remember(orderedChats) { orderedChats.map { it.id } }
                    orderedChats.take(8).forEachIndexed { idx, chat ->
                        val priority = chatPriorityMap[chat.id] ?: ChatPriorityLevel.NORMAL
                        val assignedCat = chatCategoryMap[chat.id] ?: "Work"
                        val priorityColor = when (priority) {
                            ChatPriorityLevel.CRITICAL -> Color(0xFFEF4444)
                            ChatPriorityLevel.HIGH -> Color(0xFFF59E0B)
                            ChatPriorityLevel.NORMAL -> Color(0xFF38BDF8)
                            ChatPriorityLevel.LOW -> Color(0xFF94A3B8)
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectChat(chat.id) }
                                .testTag("dashboard_chat_row_${chat.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${chat.title}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$assignedCat • Wake: \"${chat.wakeWord}\"",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // 4-Tier Priority Indicator (tappable to cycle tier)
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = priorityColor.copy(alpha = 0.18f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                val nextTier = priority.nextCycledTier()
                                                AppSettingsManager.setChatPriorityLevel(context, chat.id, nextTier)
                                            }
                                            .testTag("dashboard_priority_badge_${chat.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(priorityColor)
                                            )
                                            Text(
                                                text = priority.shortBadge,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = priorityColor
                                            )
                                        }
                                    }

                                    // Move Up button
                                    IconButton(
                                        onClick = {
                                            AppSettingsManager.moveChatImportanceOrder(
                                                context = context,
                                                chatId = chat.id,
                                                currentOrderedChatIds = orderedIds,
                                                moveUp = true
                                            )
                                        },
                                        enabled = idx > 0,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("dashboard_move_up_${chat.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowUpward,
                                            contentDescription = "Move chat importance up",
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    // Move Down button
                                    IconButton(
                                        onClick = {
                                            AppSettingsManager.moveChatImportanceOrder(
                                                context = context,
                                                chatId = chat.id,
                                                currentOrderedChatIds = orderedIds,
                                                moveUp = false
                                            )
                                        },
                                        enabled = idx < orderedChats.lastIndex,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("dashboard_move_down_${chat.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = "Move chat importance down",
                                            modifier = Modifier.size(15.dp)
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
