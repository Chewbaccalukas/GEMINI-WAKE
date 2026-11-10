package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.example.game.PlayerPresence
import com.example.game.PlayerPresenceCoordinator
import com.example.util.AppSettingsManager
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.AdventureDrawerTab
import com.example.game.AdventureGameState
import com.example.game.AdventureInlineEvent
import com.example.game.AdventureQuest
import com.example.game.AdventureSyncStatus
import com.example.game.DifficultyLevel
import com.example.game.InventoryItem
import com.example.game.QuestFilterMode
import com.example.game.QuestPriority
import com.example.game.QuestSortOrder
import com.example.game.RarityTier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves an RPG item icon name to a Material [ImageVector].
 */
fun resolveAdventureItemIcon(iconName: String): ImageVector {
    return when (iconName.trim().lowercase(Locale.US)) {
        "shield", "armor" -> Icons.Default.Shield
        "explore", "compass", "relic" -> Icons.Default.Explore
        "bolt", "potion", "consumable", "elixir" -> Icons.Default.Bolt
        "menubook", "book", "tome", "scroll" -> Icons.Default.MenuBook
        "vpnkey", "key", "keycard" -> Icons.Default.VpnKey
        "public", "gadget", "beacon" -> Icons.Default.Public
        "diamond", "gem", "artifact", "soulstone" -> Icons.Default.Diamond
        "star" -> Icons.Default.Star
        else -> Icons.Default.AutoAwesome
    }
}

/**
 * Parses a hex string like `#38BDF8` into a Compose [Color] with a safe fallback.
 */
fun parseHexColorSafe(hex: String, fallback: Color = Color(0xFF38BDF8)): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Throwable) {
        fallback
    }
}

/**
 * Compact visual connectivity indicator badge displayed in the Quest Drawer header showing whether
 * the app is currently `'Online'` (synced) or `'Offline'` (queued changes in local Room cache).
 */
@Composable
fun AdventureConnectivityStatusBadge(
    adventureState: AdventureGameState,
    testTag: String = "adventure_connectivity_status_badge",
    modifier: Modifier = Modifier
) {
    val isOnline = adventureState.isOnline
    val pendingCount = adventureState.pendingOfflineSyncCount
    val badgeColor = when {
        !isOnline -> Color(0xFFF59E0B) // Amber for Offline / Queued changes
        adventureState.syncStatus == AdventureSyncStatus.SYNCING || pendingCount > 0 -> Color(0xFF38BDF8)
        else -> Color(0xFF10B981) // Emerald for Online / Synced
    }
    val statusText = adventureState.connectivityBadgeLabel

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = badgeColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f)),
        modifier = modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = badgeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Swipable right-side Adventure Engine drawer sheet (`GameDrawerSheet.kt`) containing:
 * - Top Game Controls Bar (Manual Save, Difficulty Selector, Advanced Lore Settings, Adventure Mode toggle)
 * - Character / Visual Portrait card with Level & XP progress bar
 * - 3-Column Inventory Grid with rarity borders & animated floating tooltip card ("Use/Inspect")
 * - Interactive Expandable Quest Cards with milestone progress bars
 * - Document Lore Context Attachment (`.txt`, `.md`, `.pdf`) & Global Preset Save/Load controls
 */
/**
 * Renders a multi-layered glowing border around an inventory item based on its [RarityTier]:
 * - Common (`#94A3B8` Grey) & Rare (`#38BDF8` Blue): Static outer glow halo + gradient border.
 * - Epic (`#C084FC` Purple) & Legendary (`#F59E0B` Orange): Animated pulsing outer aura + dual-tone gradient border.
 */
fun Modifier.rarityGlowBorder(
    rarityTier: RarityTier,
    isSelected: Boolean,
    pulseFraction: Float,
    cornerRadius: Dp = 14.dp
): Modifier {
    val primaryColor = parseHexColorSafe(rarityTier.primaryColorHex)
    val secondaryColor = parseHexColorSafe(rarityTier.secondaryGlowHex, primaryColor)
    val effectivePulse = if (rarityTier.hasPulsingAura) pulseFraction.coerceIn(0f, 1f) else 0f
    val glowAlpha = rarityTier.baseGlowAlpha +
        (rarityTier.peakGlowAlpha - rarityTier.baseGlowAlpha) * effectivePulse +
        (if (isSelected) 0.16f else 0f)
    val spreadFactor = 1f + (0.35f * effectivePulse) + (if (isSelected) 0.25f else 0f)

    return this
        .drawBehind {
            val cornerPx = cornerRadius.toPx()
            val outerSpreadPx = rarityTier.glowSpreadDp.dp.toPx() * spreadFactor
            val midSpreadPx = outerSpreadPx * 0.55f

            // Outer soft halo ring
            drawRoundRect(
                color = primaryColor.copy(alpha = (glowAlpha * 0.42f).coerceIn(0f, 0.85f)),
                topLeft = Offset(-outerSpreadPx * 0.35f, -outerSpreadPx * 0.35f),
                size = Size(size.width + outerSpreadPx * 0.7f, size.height + outerSpreadPx * 0.7f),
                cornerRadius = CornerRadius(cornerPx + outerSpreadPx * 0.35f, cornerPx + outerSpreadPx * 0.35f),
                style = Stroke(width = outerSpreadPx)
            )

            // Inner high-intensity neon rim glow
            drawRoundRect(
                color = secondaryColor.copy(alpha = (glowAlpha * 0.72f).coerceIn(0f, 0.95f)),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = midSpreadPx.coerceAtLeast(2f))
            )
        }
        .border(
            width = (if (isSelected) rarityTier.borderWidthDp + 0.7f else rarityTier.borderWidthDp).dp,
            brush = Brush.linearGradient(
                colors = if (rarityTier.hasPulsingAura) {
                    listOf(primaryColor, secondaryColor, primaryColor)
                } else {
                    listOf(primaryColor, secondaryColor.copy(alpha = 0.85f))
                }
            ),
            shape = RoundedCornerShape(cornerRadius)
        )
}

@Composable
fun GameDrawerSheet(
    adventureState: AdventureGameState,
    accentColor: Color,
    onSelectTab: (AdventureDrawerTab) -> Unit,
    onToggleAdventureMode: (Boolean) -> Unit,
    onManualSave: () -> Unit,
    onSelectDifficulty: (DifficultyLevel) -> Unit,
    onToggleAutoSaveOnQuest: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onUpdateLoreContext: (String) -> Unit,
    onAttachLoreDocument: (Uri, String?) -> Unit,
    onUpdateCharacterProfile: (String, String) -> Unit,
    onUpdateCharacterPortrait: (Uri) -> Unit,
    onUseOrInspectItem: (InventoryItem) -> Unit,
    onRollProceduralLoot: () -> Unit,
    onToggleQuestCompleted: (String) -> Unit,
    onAddCustomQuest: (String, String, Int) -> Unit,
    onSaveGlobalPreset: () -> Unit,
    onLoadGlobalPreset: () -> Unit,
    onDismissStatusBanner: () -> Unit,
    onCloseDrawer: () -> Unit,
    onSelectQuestFilter: (QuestFilterMode) -> Unit = {},
    onSelectQuestSortOrder: (QuestSortOrder) -> Unit = {},
    onQuestSearchQueryChange: (String) -> Unit = {},
    onIncrementQuestStep: (String) -> Unit = {},
    onShareQuestProgressImage: () -> Unit = {},
    onRefreshDailyChallenge: () -> Unit = {},
    onAddCustomQuestDetailed: ((String, String, Int, Int, QuestPriority) -> Unit)? = null,
    onToggleStealthMode: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTooltipItem by remember { mutableStateOf<InventoryItem?>(null) }
    var showAddQuestFields by rememberSaveable { mutableStateOf(false) }
    var newQuestTitle by rememberSaveable { mutableStateOf("") }
    var newQuestDesc by rememberSaveable { mutableStateOf("") }
    var newQuestXp by rememberSaveable { mutableStateOf("200") }
    var newQuestSteps by rememberSaveable { mutableStateOf("3") }
    var newQuestPriority by remember { mutableStateOf(QuestPriority.MEDIUM) }

    // Photo Picker for Character Portrait (PNG, JPEG, WEBP)
    val portraitPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onUpdateCharacterPortrait(uri)
        }
    }

    // OpenDocument launcher for Lore Context documents (.txt, .md, .pdf)
    val loreDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
            onAttachLoreDocument(uri, mimeType)
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0F172A),
        drawerContentColor = Color(0xFFF8FAFC),
        drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 310.dp, max = 372.dp)
            .testTag("adventure_game_drawer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 1. Header & Top-Bar Game Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(accentColor, Color(0xFFC084FC))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Adventure Engine",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Infinite Adventure Engine",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val unsavedMins = adventureState.unsavedPlaySeconds / 60L
                        val saveSubtitle = if (adventureState.lastSavedAt > 0L) {
                            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            "Saved ${fmt.format(Date(adventureState.lastSavedAt))} • ${unsavedMins}m unsaved"
                        } else {
                            "Per-Chat RPG Campaign • ${unsavedMins}m session"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AdventureConnectivityStatusBadge(
                                adventureState = adventureState,
                                testTag = "adventure_connectivity_status_badge"
                            )
                            Text(
                                text = saveSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = adventureState.isAdventureModeEnabled,
                        onCheckedChange = onToggleAdventureMode,
                        modifier = Modifier.testTag("adventure_mode_master_switch")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("adventure_close_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Adventure Drawer",
                            tint = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Top Game Control Action Bar: Manual Save, Difficulty Selector, Advanced Lore Settings
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onManualSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color(0xFF0F172A)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("adventure_manual_save_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Manual Save",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Manual Save",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        FilledTonalButton(
                            onClick = { onSelectTab(AdventureDrawerTab.LORE) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("adventure_lore_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Advanced Lore Settings",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Lore Setup",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Difficulty Selector Row (Story, Balanced, Hardcore)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DifficultyLevel.entries.forEach { diff ->
                            val isSelected = adventureState.settings.difficulty == diff
                            val diffColor = parseHexColorSafe(diff.badgeColorHex, accentColor)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) diffColor.copy(alpha = 0.22f) else Color(0xFF0F172A),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) diffColor else Color(0xFF334155)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectDifficulty(diff) }
                                    .testTag("adventure_difficulty_${diff.name.lowercase(Locale.US)}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = diff.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) diffColor else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Optional Status / Event Feedback Toast Banner inside Drawer
            AnimatedVisibility(
                visible = !adventureState.statusBannerMessage.isNullOrBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("adventure_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = adventureState.statusBannerMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFFA7F3D0),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissStatusBanner,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status banner",
                                tint = Color(0xFFA7F3D0),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Compact Character Portrait & Level Summary Card
            CharacterSummaryBannerCard(
                adventureState = adventureState,
                accentColor = accentColor,
                onPickPortrait = {
                    portraitPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onOpenCharacterTab = { onSelectTab(AdventureDrawerTab.CHARACTER) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2b. Multiplayer Presence & Friend Status Pinned Party Header + Expandable List
            AdventurePartyPresenceDrawerSection(
                adventureState = adventureState,
                accentColor = accentColor,
                onToggleStealthMode = onToggleStealthMode
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Segmented Tab Bar (Inventory, Quests, Character, Lore)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdventureDrawerTab.entries.forEach { tab ->
                    val isSelected = adventureState.activeDrawerTab == tab
                    val tabTag = "adventure_tab_${tab.name.lowercase(Locale.US)}"
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.22f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accentColor else Color(0xFF334155)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectTab(tab) }
                            .testTag(tabTag)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = when (tab) {
                                    AdventureDrawerTab.INVENTORY -> "Bag (${adventureState.inventory.size})"
                                    AdventureDrawerTab.QUESTS -> "Quests (${adventureState.activeQuestCount})"
                                    AdventureDrawerTab.CHARACTER -> "Hero"
                                    AdventureDrawerTab.LORE -> "Lore"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (isSelected) accentColor else Color(0xFFCBD5E1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Active Tab Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (adventureState.activeDrawerTab) {
                    AdventureDrawerTab.INVENTORY -> {
                        InventoryGridSection(
                            inventory = adventureState.inventory,
                            selectedItem = selectedTooltipItem,
                            accentColor = accentColor,
                            onSelectItem = { clicked ->
                                selectedTooltipItem = if (selectedTooltipItem?.id == clicked.id) null else clicked
                            },
                            onDismissTooltip = { selectedTooltipItem = null },
                            onUseOrInspectItem = { item ->
                                onUseOrInspectItem(item)
                                selectedTooltipItem = null
                            },
                            onRollProceduralLoot = onRollProceduralLoot
                        )
                    }

                    AdventureDrawerTab.QUESTS -> {
                        QuestsListSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            showAddQuestFields = showAddQuestFields,
                            newQuestTitle = newQuestTitle,
                            newQuestDesc = newQuestDesc,
                            newQuestXp = newQuestXp,
                            newQuestSteps = newQuestSteps,
                            newQuestPriority = newQuestPriority,
                            onSelectQuestFilter = onSelectQuestFilter,
                            onSelectQuestSortOrder = onSelectQuestSortOrder,
                            onQuestSearchQueryChange = onQuestSearchQueryChange,
                            onToggleShowAddQuest = { showAddQuestFields = !showAddQuestFields },
                            onNewQuestTitleChange = { newQuestTitle = it },
                            onNewQuestDescChange = { newQuestDesc = it },
                            onNewQuestXpChange = { newQuestXp = it },
                            onNewQuestStepsChange = { newQuestSteps = it },
                            onNewQuestPriorityChange = { newQuestPriority = it },
                            onConfirmAddQuest = {
                                if (newQuestTitle.isNotBlank()) {
                                    val xpVal = newQuestXp.filter { it.isDigit() }.toIntOrNull() ?: 200
                                    val stepsVal = (newQuestSteps.filter { it.isDigit() }.toIntOrNull() ?: 1).coerceIn(1, 50)
                                    val cleanTitle = newQuestTitle.trim()
                                    val cleanDesc = newQuestDesc.trim().ifBlank { "Complete this objective in your adventure." }
                                    if (onAddCustomQuestDetailed != null) {
                                        onAddCustomQuestDetailed(cleanTitle, cleanDesc, xpVal, stepsVal, newQuestPriority)
                                    } else {
                                        onAddCustomQuest(cleanTitle, cleanDesc, xpVal)
                                    }
                                    newQuestTitle = ""
                                    newQuestDesc = ""
                                    newQuestSteps = "3"
                                    newQuestPriority = QuestPriority.MEDIUM
                                    showAddQuestFields = false
                                }
                            },
                            onToggleQuestCompleted = onToggleQuestCompleted,
                            onIncrementQuestStep = onIncrementQuestStep,
                            onShareQuestProgressImage = onShareQuestProgressImage,
                            onRefreshDailyChallenge = onRefreshDailyChallenge
                        )
                    }

                    AdventureDrawerTab.CHARACTER -> {
                        CharacterPortraitAndStatsSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            onPickPortrait = {
                                portraitPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onUpdateCharacterProfile = onUpdateCharacterProfile
                        )
                    }

                    AdventureDrawerTab.LORE -> {
                        LoreAndAdvancedSettingsSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            onToggleAutoSaveOnQuest = onToggleAutoSaveOnQuest,
                            onToggleNotifications = onToggleNotifications,
                            onUpdateLoreContext = onUpdateLoreContext,
                            onLaunchLoreFilePicker = {
                                loreDocumentLauncher.launch(
                                    arrayOf(
                                        "text/plain",
                                        "text/markdown",
                                        "application/pdf",
                                        "application/json",
                                        "*/*"
                                    )
                                )
                            },
                            onSaveGlobalPreset = onSaveGlobalPreset,
                            onLoadGlobalPreset = onLoadGlobalPreset
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterSummaryBannerCard(
    adventureState: AdventureGameState,
    accentColor: Color,
    onPickPortrait: () -> Unit,
    onOpenCharacterTab: () -> Unit
) {
    val context = LocalContext.current
    val portraitBitmap = remember(adventureState.characterPortraitUri) {
        try {
            adventureState.characterPortraitUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    val animatedXpProgress by animateFloatAsState(
        targetValue = adventureState.levelProgressFraction,
        animationSpec = spring(),
        label = "xp_progress"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenCharacterTab() }
            .testTag("adventure_character_summary_card")
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Character Portrait Slot (supports PNG, JPEG, WEBP)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E293B), accentColor.copy(alpha = 0.45f))
                        )
                    )
                    .border(1.5.dp, accentColor, RoundedCornerShape(12.dp))
                    .clickable { onPickPortrait() }
                    .testTag("adventure_character_portrait_slot"),
                contentAlignment = Alignment.Center
            ) {
                if (portraitBitmap != null) {
                    Image(
                        bitmap = portraitBitmap,
                        contentDescription = "Character Portrait",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Upload Character Portrait",
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${adventureState.characterName} • ${adventureState.characterClass}",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "LVL ${adventureState.level}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { animatedXpProgress },
                    color = accentColor,
                    trackColor = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${adventureState.totalXp} XP Total",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${adventureState.xpIntoCurrentLevel}/${AdventureGameState.XP_PER_LEVEL} to Next Lvl",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
private fun InventoryGridSection(
    inventory: List<InventoryItem>,
    selectedItem: InventoryItem?,
    accentColor: Color,
    onSelectItem: (InventoryItem) -> Unit,
    onDismissTooltip: () -> Unit,
    onUseOrInspectItem: (InventoryItem) -> Unit,
    onRollProceduralLoot: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rarity_aura_pulse")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rarity_aura_fraction"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "3-Column Tactical Inventory",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
            )
            OutlinedButton(
                onClick = onRollProceduralLoot,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("adventure_roll_loot_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Casino,
                    contentDescription = "Roll Procedural Loot",
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Roll Loot",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Visual Rarity Indicator Legend Bar (Common: Grey, Rare: Blue, Epic: Purple, Legendary: Orange)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_rarity_legend_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RarityTier.entries.forEach { tier ->
                val tierColor = parseHexColorSafe(tier.primaryColorHex)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tierColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, tierColor.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("adventure_rarity_legend_${tier.name.lowercase(Locale.US)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(tierColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tier.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = tierColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Animated Floating Tooltip Card when an item in the 3-column grid is tapped
        AnimatedVisibility(
            visible = selectedItem != null,
            enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = 0.75f)),
            exit = fadeOut() + scaleOut()
        ) {
            selectedItem?.let { item ->
                val rarityTier = item.rarityTier
                val rarityColor = parseHexColorSafe(rarityTier.primaryColorHex, accentColor)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .rarityGlowBorder(
                            rarityTier = rarityTier,
                            isSelected = true,
                            pulseFraction = pulseFraction,
                            cornerRadius = 16.dp
                        )
                        .testTag("adventure_item_tooltip_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(rarityColor.copy(alpha = 0.2f))
                                        .border(1.dp, rarityColor, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = resolveAdventureItemIcon(item.iconName),
                                        contentDescription = item.name,
                                        tint = rarityColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    )
                                    Text(
                                        text = "${rarityTier.displayName} ${item.category} • Qty: x${item.quantity}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = rarityColor
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismissTooltip,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Item Tooltip",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { onUseOrInspectItem(item) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = rarityColor,
                                    contentColor = Color(0xFF0F172A)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("adventure_item_use_inspect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Use / Inspect in Chat",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3-Column Scrollable Grid of Inventory Items with Glowing Rarity Border System
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("adventure_inventory_grid")
        ) {
            items(inventory, key = { it.id }) { item ->
                val rarityTier = item.rarityTier
                val rarityColor = parseHexColorSafe(rarityTier.primaryColorHex, accentColor)
                val isSelected = selectedItem?.id == item.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) rarityColor.copy(alpha = 0.24f) else Color(0xFF1E293B),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .rarityGlowBorder(
                            rarityTier = rarityTier,
                            isSelected = isSelected,
                            pulseFraction = pulseFraction,
                            cornerRadius = 14.dp
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectItem(item) }
                        .testTag("adventure_inventory_item_${item.id}")
                ) {
                    Box(modifier = Modifier.padding(7.dp)) {
                        // Rarity Tier Badge in top-left corner
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = rarityColor.copy(alpha = 0.2f),
                            border = BorderStroke(0.5.dp, rarityColor.copy(alpha = 0.75f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .testTag("adventure_item_rarity_badge_${item.id}")
                        ) {
                            Text(
                                text = when (rarityTier) {
                                    RarityTier.LEGENDARY -> "LEGEND"
                                    RarityTier.EPIC -> "EPIC"
                                    RarityTier.RARE -> "RARE"
                                    RarityTier.COMMON -> "COMMON"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = rarityColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        // Quantity Pill Badge in top-right corner
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.85f),
                            border = BorderStroke(0.5.dp, rarityColor.copy(alpha = 0.7f)),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "x${item.quantity}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = rarityColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(rarityColor.copy(alpha = 0.20f))
                                    .border(1.dp, rarityColor.copy(alpha = 0.65f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = resolveAdventureItemIcon(item.iconName),
                                    contentDescription = item.name,
                                    tint = rarityColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestsListSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    showAddQuestFields: Boolean,
    newQuestTitle: String,
    newQuestDesc: String,
    newQuestXp: String,
    newQuestSteps: String,
    newQuestPriority: QuestPriority,
    onSelectQuestFilter: (QuestFilterMode) -> Unit,
    onSelectQuestSortOrder: (QuestSortOrder) -> Unit,
    onQuestSearchQueryChange: (String) -> Unit,
    onToggleShowAddQuest: () -> Unit,
    onNewQuestTitleChange: (String) -> Unit,
    onNewQuestDescChange: (String) -> Unit,
    onNewQuestXpChange: (String) -> Unit,
    onNewQuestStepsChange: (String) -> Unit,
    onNewQuestPriorityChange: (QuestPriority) -> Unit,
    onConfirmAddQuest: () -> Unit,
    onToggleQuestCompleted: (String) -> Unit,
    onIncrementQuestStep: (String) -> Unit,
    onShareQuestProgressImage: () -> Unit,
    onRefreshDailyChallenge: () -> Unit
) {
    var expandedQuestIds by remember { mutableStateOf(setOf(adventureState.quests.firstOrNull()?.id.orEmpty())) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    val animatedMilestone by animateFloatAsState(
        targetValue = adventureState.questCompletionProgressFraction,
        animationSpec = spring(),
        label = "quest_milestone_progress"
    )
    val visibleQuests = adventureState.filteredQuests
    val dailyQuest = adventureState.dailyChallengeQuest

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("adventure_quests_list")
    ) {
        // 1. Campaign Milestone Progress Card + Share Active Quest Progress Image Button
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
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
                            text = "Campaign Quest Milestones",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "${adventureState.completedQuestCount} / ${adventureState.quests.size} Completed (${(adventureState.questCompletionProgressFraction * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = accentColor
                        )
                    }
                    FilledTonalButton(
                        onClick = onShareQuestProgressImage,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("adventure_share_quest_progress_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Quest Progress Image Summary",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Share Image",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedMilestone },
                    color = Color(0xFF10B981),
                    trackColor = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Daily Challenge System ("Quest of the Day") Spotlight Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E1B4B).copy(alpha = 0.72f),
            border = BorderStroke(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFFF59E0B), Color(0xFFC084FC))
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_daily_challenge_card")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Quest of the Day",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "QUEST OF THE DAY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFBBF24)
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.22f),
                        border = BorderStroke(0.8.dp, Color(0xFFF59E0B))
                    ) {
                        Text(
                            text = "BONUS XP + RARE+ LOOT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = Color(0xFFFDE68A),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (dailyQuest != null) {
                    Text(
                        text = dailyQuest.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = dailyQuest.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE2E8F0)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Step ${dailyQuest.safeCurrentStep}/${dailyQuest.safeTotalSteps} • ${dailyQuest.completionPercent}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (dailyQuest.isCompleted) Color(0xFF10B981) else Color(0xFFFBBF24)
                        )
                        Text(
                            text = "+${dailyQuest.xpReward} XP",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color(0xFFC084FC)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { dailyQuest.completionFraction },
                        color = if (dailyQuest.isCompleted) Color(0xFF10B981) else Color(0xFFF59E0B),
                        trackColor = Color(0xFF0F172A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!dailyQuest.isCompleted && dailyQuest.safeTotalSteps > 1) {
                            OutlinedButton(
                                onClick = { onIncrementQuestStep(dailyQuest.id) },
                                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("adventure_daily_challenge_step_button")
                            ) {
                                Text(
                                    text = "+1 Step",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFBBF24)
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        FilledTonalButton(
                            onClick = { onToggleQuestCompleted(dailyQuest.id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("adventure_daily_challenge_complete_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (dailyQuest.isCompleted) "Completed (Claimed)" else "Complete Daily",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Generate today's personalized challenge from your chat & voice history.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRefreshDailyChallenge,
                            border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("adventure_generate_daily_challenge_button")
                        ) {
                            Text(
                                text = "Generate",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24)
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Quest List Header with Online/Offline Visual Indicator, Sort Dropdown Menu & New Quest Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Adventure Objectives",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                )
                Spacer(modifier = Modifier.height(3.dp))
                AdventureConnectivityStatusBadge(
                    adventureState = adventureState,
                    testTag = "adventure_quest_header_connectivity_badge"
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Sort Dropdown Menu ('Newest', 'Oldest', 'High Priority')
                Box {
                    OutlinedButton(
                        onClick = { sortDropdownExpanded = true },
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("adventure_quest_sort_dropdown_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort Quests",
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = adventureState.questSortOrder.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0)
                            )
                        )
                    }

                    DropdownMenu(
                        expanded = sortDropdownExpanded,
                        onDismissRequest = { sortDropdownExpanded = false }
                    ) {
                        QuestSortOrder.entries.forEach { sortOrder ->
                            val isSelected = adventureState.questSortOrder == sortOrder
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = sortOrder.displayName,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected Sort Order",
                                                tint = accentColor,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    sortDropdownExpanded = false
                                    onSelectQuestSortOrder(sortOrder)
                                },
                                modifier = Modifier.testTag(
                                    "adventure_quest_sort_option_${sortOrder.name.lowercase(Locale.US)}"
                                )
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onToggleShowAddQuest,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("adventure_add_quest_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Custom Quest",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Quest", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3b. Multi-Field Quest Search Bar (filters by title, description, priority, or daily badge)
        OutlinedTextField(
            value = adventureState.questSearchQuery,
            onValueChange = onQuestSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search title, lore, priority, or daily...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Quests",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (adventureState.questSearchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onQuestSearchQueryChange("") },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("adventure_quest_search_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Quest Search",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_quest_search_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 4. 3-Way Quest Filter Toggle Row ('All', 'Active', 'Completed') with Live Count Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_quest_filter_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuestFilterMode.entries.forEach { mode ->
                val isSelected = adventureState.questFilter == mode
                val countForMode = when (mode) {
                    QuestFilterMode.ALL -> adventureState.quests.size
                    QuestFilterMode.ACTIVE -> adventureState.activeQuestCount
                    QuestFilterMode.COMPLETED -> adventureState.completedQuestCount
                }
                val activeChipColor = when (mode) {
                    QuestFilterMode.COMPLETED -> Color(0xFF10B981)
                    QuestFilterMode.ACTIVE -> Color(0xFF38BDF8)
                    QuestFilterMode.ALL -> accentColor
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) activeChipColor.copy(alpha = 0.22f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) activeChipColor else Color(0xFF334155)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectQuestFilter(mode) }
                        .testTag("adventure_quest_filter_${mode.name.lowercase(Locale.US)}")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = if (isSelected) activeChipColor else Color(0xFFCBD5E1),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) activeChipColor.copy(alpha = 0.28f) else Color(0xFF0F172A)
                        ) {
                            Text(
                                text = countForMode.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) activeChipColor else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showAddQuestFields) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    OutlinedTextField(
                        value = newQuestTitle,
                        onValueChange = onNewQuestTitleChange,
                        label = { Text("Quest Title") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("adventure_new_quest_title_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newQuestDesc,
                        onValueChange = onNewQuestDescChange,
                        label = { Text("Quest Objective Description") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("adventure_new_quest_desc_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newQuestXp,
                            onValueChange = onNewQuestXpChange,
                            label = { Text("XP Reward") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newQuestSteps,
                            onValueChange = onNewQuestStepsChange,
                            label = { Text("Total Steps") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("adventure_new_quest_steps_input")
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            QuestPriority.entries.forEach { prio ->
                                val selected = newQuestPriority == prio
                                val prioColor = parseHexColorSafe(prio.colorHex, accentColor)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) prioColor.copy(alpha = 0.22f) else Color(0xFF0F172A),
                                    border = BorderStroke(1.dp, if (selected) prioColor else Color(0xFF334155)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNewQuestPriorityChange(prio) }
                                        .testTag("adventure_new_quest_priority_${prio.name.lowercase(Locale.US)}")
                                ) {
                                    Text(
                                        text = prio.badgeLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (selected) prioColor else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = onConfirmAddQuest,
                            modifier = Modifier.testTag("adventure_confirm_add_quest_button")
                        ) {
                            Text("Add Quest")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (visibleQuests.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("adventure_quests_empty_filter_state")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (adventureState.questFilter == QuestFilterMode.COMPLETED) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Explore
                        },
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (adventureState.questFilter) {
                            QuestFilterMode.ACTIVE -> "No active quests remaining"
                            QuestFilterMode.COMPLETED -> "No completed quests yet"
                            QuestFilterMode.ALL -> "No quests in campaign log"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (adventureState.questFilter) {
                            QuestFilterMode.ACTIVE -> "Switch to 'Completed' or add a new quest objective to continue your journey."
                            QuestFilterMode.COMPLETED -> "Complete an active quest objective to earn XP and record it here."
                            QuestFilterMode.ALL -> "Tap 'New Quest' above or explore in chat to discover new objectives."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        visibleQuests.forEach { quest ->
            val isExpanded = expandedQuestIds.contains(quest.id)
            val statusColor = if (quest.isCompleted) Color(0xFF10B981) else accentColor
            val priorityColor = parseHexColorSafe(quest.priority.colorHex, accentColor)
            val animatedQuestProgress by animateFloatAsState(
                targetValue = quest.completionFraction,
                animationSpec = spring(),
                label = "quest_progress_${quest.id}"
            )
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        quest.isCompleted -> Color(0xFF10B981).copy(alpha = 0.5f)
                        quest.isDailyChallenge -> Color(0xFFF59E0B).copy(alpha = 0.65f)
                        else -> Color(0xFF334155)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        expandedQuestIds = if (isExpanded) {
                            expandedQuestIds - quest.id
                        } else {
                            expandedQuestIds + quest.id
                        }
                    }
                    .testTag("adventure_quest_card_${quest.id}")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = when {
                                    quest.isCompleted -> Icons.Default.CheckCircle
                                    quest.isDailyChallenge -> Icons.Default.Star
                                    else -> Icons.Default.Explore
                                },
                                contentDescription = null,
                                tint = if (quest.isDailyChallenge && !quest.isCompleted) Color(0xFFF59E0B) else statusColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = quest.title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        ),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = priorityColor.copy(alpha = 0.2f),
                                        border = BorderStroke(0.6.dp, priorityColor.copy(alpha = 0.7f))
                                    ) {
                                        Text(
                                            text = if (quest.isDailyChallenge) "DAILY" else quest.priority.badgeLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            ),
                                            color = priorityColor,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (quest.isCompleted) "Completed • +${quest.xpReward} XP Earned" else "Active • +${quest.xpReward} XP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor
                                    )
                                    Text(
                                        text = "Step ${quest.safeCurrentStep}/${quest.safeTotalSteps} • ${quest.completionPercent}%",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color(0xFFCBD5E1),
                                        modifier = Modifier.testTag("adventure_quest_progress_label_${quest.id}")
                                    )
                                }
                            }
                        }

                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse quest" else "Expand quest",
                            tint = Color(0xFF94A3B8)
                        )
                    }

                    // Linear Progress Indicator directly below each quest title showing completion % for multi-step goals
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { animatedQuestProgress },
                        color = if (quest.isDailyChallenge && !quest.isCompleted) Color(0xFFF59E0B) else statusColor,
                        trackColor = Color(0xFF0F172A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .testTag("adventure_quest_progress_bar_${quest.id}")
                    )

                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = quest.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!quest.isCompleted && quest.safeTotalSteps > 1) {
                                    OutlinedButton(
                                        onClick = { onIncrementQuestStep(quest.id) },
                                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .testTag("adventure_quest_step_button_${quest.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Advance Quest Step",
                                            tint = accentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "+1 Step (${quest.safeCurrentStep}/${quest.safeTotalSteps})",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = accentColor
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                FilledTonalButton(
                                    onClick = { onToggleQuestCompleted(quest.id) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("adventure_quest_toggle_${quest.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (quest.isCompleted) "Mark Active" else "Complete (+${quest.xpReward} XP)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Local Room Offline Quest History Cache Log
        if (adventureState.questHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.78f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("adventure_offline_history_section")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offline Room Quest History (${adventureState.questHistory.size})",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0)
                            )
                        )
                        AdventureConnectivityStatusBadge(
                            adventureState = adventureState,
                            testTag = "adventure_history_connectivity_badge"
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    adventureState.questHistory.take(5).forEach { entry ->
                        val isQueued = entry.syncStatus.equals(AdventureSyncStatus.PENDING_OFFLINE.name, ignoreCase = true)
                        val entryColor = if (isQueued) Color(0xFFF59E0B) else Color(0xFF10B981)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "[${entry.actionType}] ${entry.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isQueued) "Queued" else "Synced",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = entryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterPortraitAndStatsSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    onPickPortrait: () -> Unit,
    onUpdateCharacterProfile: (String, String) -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember(adventureState.characterName) { mutableStateOf(adventureState.characterName) }
    var classInput by remember(adventureState.characterClass) { mutableStateOf(adventureState.characterClass) }

    val portraitBitmap = remember(adventureState.characterPortraitUri) {
        try {
            adventureState.characterPortraitUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large Visual Portrait Card (PNG, JPEG, WEBP)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(2.dp, accentColor),
            modifier = Modifier
                .size(168.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable { onPickPortrait() }
                .testTag("adventure_character_large_portrait")
        ) {
            if (portraitBitmap != null) {
                Image(
                    bitmap = portraitBitmap,
                    contentDescription = "Character Visual Portrait",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Upload Portrait",
                        tint = accentColor,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to Upload Portrait\n(PNG / JPEG / WEBP)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onPickPortrait,
            modifier = Modifier.testTag("adventure_upload_portrait_button")
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Choose Character Portrait")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                onUpdateCharacterProfile(it, classInput)
            },
            label = { Text("Character Name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_character_name_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = classInput,
            onValueChange = {
                classInput = it
                onUpdateCharacterProfile(nameInput, it)
            },
            label = { Text("Class / Archetype") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_character_class_input")
        )
    }
}

@Composable
private fun LoreAndAdvancedSettingsSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    onToggleAutoSaveOnQuest: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onUpdateLoreContext: (String) -> Unit,
    onLaunchLoreFilePicker: () -> Unit,
    onSaveGlobalPreset: () -> Unit,
    onLoadGlobalPreset: () -> Unit
) {
    var loreText by remember(adventureState.settings.loreContext) {
        mutableStateOf(adventureState.settings.loreContext)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("adventure_lore_settings_section")
    ) {
        Text(
            text = "Game Master Lore & Persistence",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Auto-Save on Quest & Save Sentinel Notification Toggles
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
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
                            text = "Auto-Save on Quest Completion",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Automatically persists campaign state to Room SQLite whenever a quest completes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = adventureState.settings.autoSaveOnQuest,
                        onCheckedChange = onToggleAutoSaveOnQuest,
                        modifier = Modifier.testTag("adventure_autosave_quest_switch")
                    )
                }

                HorizontalDivider(
                    color = Color(0xFF334155),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "20-Min Save Sentinel Alerts",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Alerts via Quick Save snackbar & status bar notification after 20m unsaved play.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = adventureState.settings.notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        modifier = Modifier.testTag("adventure_sentinel_notifications_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Attach Document Lore Context (.txt, .md, .pdf)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "World Lore & Attached Documents",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
            )
            FilledTonalButton(
                onClick = onLaunchLoreFilePicker,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("adventure_attach_lore_doc_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Attach Lore Document",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Attach .TXT/.MD/.PDF",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        if (adventureState.attachedLoreFileNames.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Attached Lore: ${adventureState.attachedLoreFileNames.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = loreText,
            onValueChange = {
                loreText = it
                onUpdateLoreContext(it)
            },
            label = { Text("Custom World Lore & Rules Context") },
            placeholder = { Text("Paste world rules, factions, or attach a .txt / .md / .pdf lore book...") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_lore_context_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Global Preset Save / Load Option (Phase 1 Confirmed Choice)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Global Campaign Preset",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = "Save this chat's hero, lore, and inventory as your Global Preset or load it into any chat.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSaveGlobalPreset,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adventure_save_global_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Preset", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onLoadGlobalPreset,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adventure_load_global_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load Preset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Renders styled RPG event badges inside a chat message bubble when inline markers
 * (`[QUEST_ADD: ...]`, `[QUEST_COMPLETE: ...]`, `[LOOT_DROP: ...]`) are present.
 */
@Composable
fun AdventureInlineEventBadges(
    events: List<AdventureInlineEvent>,
    onOpenDrawerToTab: (AdventureDrawerTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("adventure_inline_event_badges")
    ) {
        events.forEachIndexed { idx, event ->
            when (event) {
                is AdventureInlineEvent.QuestAdded -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.QUESTS) }
                            .testTag("adventure_badge_quest_add_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "New Quest Added",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "NEW QUEST: ${event.quest.title}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF38BDF8)
                                    )
                                )
                                Text(
                                    text = "${event.quest.description} (+${event.quest.xpReward} XP)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                is AdventureInlineEvent.QuestCompleted -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color(0xFF10B981)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.QUESTS) }
                            .testTag("adventure_badge_quest_complete_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Quest Completed",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "QUEST COMPLETED: ${event.questIdOrTitle}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF10B981)
                                    )
                                )
                                Text(
                                    text = "Campaign progress auto-saved • Tap to view Quest Log",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA7F3D0)
                                )
                            }
                        }
                    }
                }

                is AdventureInlineEvent.QuestStepUpdated -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color(0xFFC084FC)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.QUESTS) }
                            .testTag("adventure_badge_quest_step_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Quest Step Advanced",
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "QUEST PROGRESS (+${event.stepDelta} STEP): ${event.questIdOrTitle}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFC084FC)
                                    )
                                )
                                Text(
                                    text = "Multi-step milestone updated • Tap to view Quest Log",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE9D5FF)
                                )
                            }
                        }
                    }
                }

                is AdventureInlineEvent.LootDropped -> {
                    val rarityColor = parseHexColorSafe(event.item.rarityColorHex, Color(0xFFF59E0B))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.2.dp, rarityColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.INVENTORY) }
                            .testTag("adventure_badge_loot_drop_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = resolveAdventureItemIcon(event.item.iconName),
                                contentDescription = "Loot Dropped",
                                tint = rarityColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LOOT ACQUIRED: ${event.item.name} (x${event.item.quantity})",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = rarityColor
                                    )
                                )
                                Text(
                                    text = event.item.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact visual status indicator for the Top Navigation Bar showing whether the app is currently
 * in `'Online Sync'` mode or `'Offline Cache'` mode based on the user's global toggle setting.
 * Tapping the indicator toggles the global setting and triggers subtle haptic feedback via
 * [com.example.game.AdventureEngine.playSyncModeSwitchHaptic].
 */
@Composable
fun TopBarSyncModeStatusIndicator(
    isOfflineCacheMode: Boolean,
    pendingQueuedCount: Int = 0,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "top_bar_sync_mode_indicator"
) {
    val badgeColor = if (isOfflineCacheMode) {
        Color(0xFFF59E0B)
    } else {
        Color(0xFF10B981)
    }
    val modeLabel = if (isOfflineCacheMode) {
        if (pendingQueuedCount > 0) {
            "Offline Cache ($pendingQueuedCount)"
        } else {
            "Offline Cache"
        }
    } else {
        "Online Sync"
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = badgeColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f)),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onToggleMode() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Icon(
                imageVector = if (isOfflineCacheMode) Icons.Default.CloudUpload else Icons.Default.CheckCircle,
                contentDescription = "Top navigation sync mode: $modeLabel",
                tint = badgeColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                ),
                color = badgeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Pinned Multiplayer Presence & Friend Status section inside [GameDrawerSheet] featuring:
 * - Quick "Always Offline" (Stealth Mode) eye toggle icon in the header.
 * - Live local player [FriendPresenceCard] and expandable party/friend list.
 * - Optional quick "Add Friend by UID" input row.
 */
@Composable
fun AdventurePartyPresenceDrawerSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    onToggleStealthMode: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isStealthModePref by AppSettingsManager.isStealthMode.collectAsState()
    val effectiveStealth = adventureState.isStealthMode || isStealthModePref
    val coordinatorLocal by PlayerPresenceCoordinator.localPlayerPresence.collectAsState()
    val coordinatorParty by PlayerPresenceCoordinator.partyPresenceList.collectAsState()
    val pinnedFriendUids by AppSettingsManager.pinnedFriendUids.collectAsState()

    var isPartyExpanded by rememberSaveable { mutableStateOf(false) }
    var friendUidInput by rememberSaveable { mutableStateOf("") }
    val nowMs = System.currentTimeMillis()

    val localPresence = remember(
        adventureState.localPresence,
        coordinatorLocal,
        adventureState.characterName,
        adventureState.level,
        adventureState.completedQuestCount,
        adventureState.isOnline,
        effectiveStealth
    ) {
        val base = adventureState.localPresence ?: coordinatorLocal
        base.copy(
            displayName = adventureState.characterName.ifBlank { base.displayName },
            level = adventureState.level,
            completedQuestCount = adventureState.completedQuestCount,
            isOnline = if (effectiveStealth) false else adventureState.isOnline,
            isStealthMode = effectiveStealth
        ).enforceStealthPrivacy()
    }

    val partyList = remember(adventureState.partyPresences, coordinatorParty, localPresence) {
        val raw = if (adventureState.partyPresences.isNotEmpty()) {
            adventureState.partyPresences
        } else {
            coordinatorParty
        }
        val others = raw.filter { it.odinUid != localPresence.odinUid }
        listOf(localPresence) + others
    }

    val onlineCount = partyList.count { it.isEffectivelyOnline(nowMs) }

    val handleToggleStealth: () -> Unit = {
        PlayerPresenceCoordinator.toggleStealthModeFromUi(
            context = context,
            currentPresence = localPresence,
            nowEpochMs = nowMs,
            onStealthChanged = { nextStealth ->
                onToggleStealthMode?.invoke(nextStealth)
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(
            1.dp,
            if (effectiveStealth) Color(0xFF64748B) else accentColor.copy(alpha = 0.45f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("adventure_party_presence_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row with Online Party Counter + "Always Offline" Eye Toggle + Expand Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (effectiveStealth || !localPresence.isEffectivelyOnline(nowMs)) {
                                    Color(0xFF94A3B8) // Gray for Offline / Stealth
                                } else {
                                    Color(0xFF10B981) // Green for Online
                                }
                            )
                            .testTag("adventure_party_header_status_dot")
                    )
                    Column {
                        Text(
                            text = if (effectiveStealth) {
                                "Party Presence • Always Offline"
                            } else {
                                "Party Presence • $onlineCount Online"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (effectiveStealth) {
                                "Stealth Mode active — appearing Offline to friends"
                            } else {
                                "${localPresence.formattedChatContext(nowMs)} • ${localPresence.formattedElapsedSessionTime(nowMs)}"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = handleToggleStealth,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("adventure_stealth_mode_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (effectiveStealth) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (effectiveStealth) {
                                "Disable Always Offline Stealth Mode"
                            } else {
                                "Enable Always Offline Stealth Mode"
                            },
                            tint = if (effectiveStealth) Color(0xFFF59E0B) else Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { isPartyExpanded = !isPartyExpanded },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("adventure_friend_drawer_expand_button")
                    ) {
                        Icon(
                            imageVector = if (isPartyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isPartyExpanded) "Collapse Friend Status" else "Expand Friend Status",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Local Player's FriendPresenceCard (always visible in header or expanded)
            FriendPresenceCard(
                presence = localPresence,
                isLocalPlayer = true,
                nowEpochMs = nowMs,
                onToggleStealthMode = {
                    onToggleStealthMode?.invoke(AppSettingsManager.isStealthMode.value)
                }
            )

            AnimatedVisibility(
                visible = isPartyExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adventure_friend_presence_expanded_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF334155))

                    // Add Friend by UID Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = friendUidInput,
                            onValueChange = { friendUidInput = it },
                            placeholder = {
                                Text(
                                    text = "Add friend by Odin UID...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B)
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("adventure_add_friend_uid_input")
                        )
                        FilledTonalButton(
                            onClick = {
                                val cleanUid = friendUidInput.trim()
                                if (cleanUid.isNotEmpty()) {
                                    AppSettingsManager.addPinnedFriendUid(context, cleanUid)
                                    friendUidInput = ""
                                    scope.launch {
                                        PlayerPresenceCoordinator.refreshPartyListFromRoom(context)
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("adventure_add_friend_uid_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Friend UID",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Add", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    val otherFriends = partyList.filter { it.odinUid != localPresence.odinUid }
                    if (otherFriends.isEmpty()) {
                        Text(
                            text = "No other adventurers in lobby yet. Add a friend UID above or sign in to sync global presence.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        otherFriends.take(8).forEach { friendPresence ->
                            FriendPresenceCard(
                                presence = friendPresence,
                                isLocalPlayer = false,
                                isPinnedFriend = pinnedFriendUids.contains(friendPresence.odinUid),
                                nowEpochMs = nowMs,
                                onRemoveFriend = if (pinnedFriendUids.contains(friendPresence.odinUid)) {
                                    {
                                        AppSettingsManager.removePinnedFriendUid(context, friendPresence.odinUid)
                                        scope.launch {
                                            PlayerPresenceCoordinator.refreshPartyListFromRoom(context)
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Friend Status Card (`FriendPresenceCard`) displaying:
 * - Online status dot (Green `#10B981` for Online, Gray `#94A3B8` for Offline/Stealth).
 * - Level badge (`Lv. X`) and Total Quests Completed counter (`Y Quests`).
 * - Active chat context (`"In: <Chat Title>"`) and elapsed session time (`"Online for Xh Ym"`).
 * - An `"Always Offline"` eye toggle icon in the card/header connected to [AppSettingsManager]
 *   and [PlayerPresenceCoordinator] for immediate Firestore `isStealthMode` + `'Offline'` status sync.
 */
@Composable
fun FriendPresenceCard(
    presence: PlayerPresence,
    isLocalPlayer: Boolean = true,
    isPinnedFriend: Boolean = false,
    nowEpochMs: Long = System.currentTimeMillis(),
    onToggleStealthMode: (() -> Unit)? = null,
    onRemoveFriend: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember(context) {
        AppSettingsManager.ensureInitialized(context)
        true
    }
    val persistedStealth by AppSettingsManager.isStealthMode.collectAsState()
    var localStealthOverride by remember(presence.odinUid, presence.isStealthMode) {
        mutableStateOf<Boolean?>(null)
    }

    val isControllableStealthCard = isLocalPlayer || onToggleStealthMode != null
    val effectivePresence = remember(presence, persistedStealth, localStealthOverride, isControllableStealthCard) {
        if (isControllableStealthCard) {
            val resolvedStealth = localStealthOverride ?: (presence.isStealthMode || persistedStealth)
            presence.copy(
                isStealthMode = resolvedStealth,
                isOnline = if (resolvedStealth) false else presence.isOnline
            ).enforceStealthPrivacy()
        } else {
            presence.enforceStealthPrivacy()
        }
    }

    val sanitized = effectivePresence
    val isOnlineEffective = sanitized.isEffectivelyOnline(nowEpochMs)
    val statusDotColor = if (isOnlineEffective) {
        Color(0xFF10B981) // Green for Online
    } else {
        Color(0xFF94A3B8) // Gray for Offline / Stealth
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(
            1.dp,
            if (isOnlineEffective) Color(0xFF10B981).copy(alpha = 0.45f) else Color(0xFF334155)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("friend_presence_card_${sanitized.odinUid}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Online Status Dot (Green for Online, Gray for Offline/Stealth)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                        .testTag("friend_presence_status_dot_${sanitized.odinUid}")
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isLocalPlayer) "${sanitized.displayName} (You)" else sanitized.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("friend_presence_name_${sanitized.odinUid}")
                        )

                        // Level Badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF38BDF8).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("friend_presence_level_badge_${sanitized.odinUid}")
                        ) {
                            Text(
                                text = "Lv. ${sanitized.level}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }

                        // Total Quests Completed Counter
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFC084FC).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFFC084FC).copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("friend_presence_quest_count_${sanitized.odinUid}")
                        ) {
                            Text(
                                text = "${sanitized.completedQuestCount} Quests",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFFE879F9),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Active Chat Context ("In: <Chat Title>") & Elapsed Session Time ("Online for Xh Ym")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = sanitized.formattedChatContext(nowEpochMs),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isOnlineEffective) Color(0xFFA7F3D0) else Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("friend_presence_chat_context_${sanitized.odinUid}")
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = sanitized.formattedElapsedSessionTime(nowEpochMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("friend_presence_session_time_${sanitized.odinUid}")
                        )
                    }
                }
            }

            // "Always Offline" Eye Toggle Icon for quick privacy control (or Unpin button for added friends)
            if (isControllableStealthCard) {
                IconButton(
                    onClick = {
                        val synced = PlayerPresenceCoordinator.toggleStealthModeFromUi(
                            context = context,
                            currentPresence = presence.copy(isStealthMode = sanitized.isStealthMode),
                            nowEpochMs = nowEpochMs
                        )
                        localStealthOverride = synced.isStealthMode
                        onToggleStealthMode?.invoke()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("friend_presence_stealth_toggle")
                ) {
                    Icon(
                        imageVector = if (sanitized.isStealthMode) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (sanitized.isStealthMode) {
                            "Always Offline Enabled (Tap to go Online)"
                        } else {
                            "Always Offline Disabled (Tap for Stealth Mode)"
                        },
                        tint = if (sanitized.isStealthMode) Color(0xFFF59E0B) else Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else if (isPinnedFriend && onRemoveFriend != null) {
                IconButton(
                    onClick = onRemoveFriend,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("friend_presence_remove_${sanitized.odinUid}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove pinned friend",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}



