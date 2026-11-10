package com.example.game

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.MainActivity
import com.example.R
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Difficulty tiers for Gemini's Infinite Adventure Engine.
 */
enum class DifficultyLevel(
    val displayName: String,
    val badgeColorHex: String,
    val promptDirective: String
) {
    STORY(
        displayName = "Story",
        badgeColorHex = "#10B981",
        promptDirective = "STORY MODE: Emphasize cinematic world-building, forgiving skill checks, rich character dialogue, and frequent rewarding discoveries."
    ),
    BALANCED(
        displayName = "Balanced",
        badgeColorHex = "#38BDF8",
        promptDirective = "BALANCED MODE: Provide tactical decision-making, fair consequences, meaningful resource management, and steady quest progression."
    ),
    HARDCORE(
        displayName = "Hardcore",
        badgeColorHex = "#F43F5E",
        promptDirective = "HARDCORE MODE: Enforce high-stakes tactical peril, scarce supplies, unforgiving traps, and earned victories requiring clever strategy."
    );

    companion object {
        fun fromString(raw: String?): DifficultyLevel {
            if (raw.isNullOrBlank()) return BALANCED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: BALANCED
        }
    }
}

/**
 * Active tab inside the right-side swipable [com.example.ui.components.GameDrawerSheet].
 */
enum class AdventureDrawerTab(val title: String) {
    INVENTORY("Inventory"),
    QUESTS("Quests"),
    CHARACTER("Character"),
    LORE("Lore & Controls")
}

/**
 * Filter modes for the Quests list in the Adventure Drawer ('All', 'Active', 'Completed').
 */
enum class QuestFilterMode(val displayName: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed");

    companion object {
        fun fromString(raw: String?): QuestFilterMode {
            if (raw.isNullOrBlank()) return ALL
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: ALL
        }
    }
}

/**
 * Sort orders for the Quests list header dropdown ('Newest', 'Oldest', 'High Priority').
 */
enum class QuestSortOrder(val displayName: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    HIGH_PRIORITY("High Priority");

    companion object {
        fun fromString(raw: String?): QuestSortOrder {
            if (raw.isNullOrBlank()) return NEWEST
            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.displayName.equals(raw.trim(), ignoreCase = true)
            } ?: NEWEST
        }
    }
}

/**
 * Priority tiers for quests in the Adventure Engine.
 */
enum class QuestPriority(
    val displayName: String,
    val badgeLabel: String,
    val weight: Int,
    val colorHex: String
) {
    HIGH("High Priority", "HIGH", 3, "#F43F5E"),
    MEDIUM("Medium", "MED", 2, "#38BDF8"),
    LOW("Low", "LOW", 1, "#94A3B8");

    val sortWeight: Int
        get() = weight

    companion object {
        fun fromString(raw: String?): QuestPriority {
            if (raw.isNullOrBlank()) return MEDIUM
            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.badgeLabel.equals(raw.trim(), ignoreCase = true) ||
                    it.displayName.equals(raw.trim(), ignoreCase = true)
            } ?: MEDIUM
        }
    }
}

/**
 * Visual rarity tiers for the Inventory Grid glowing border indicator system:
 * - Common: Grey (#94A3B8), static glow
 * - Rare: Blue (#38BDF8), static glow
 * - Epic: Purple (#C084FC), animated pulsing aura
 * - Legendary: Orange (#F59E0B), animated pulsing aura
 */
enum class RarityTier(
    val displayName: String,
    val shortBadge: String,
    val colorHex: String,
    val secondaryGlowHex: String,
    val hasPulsingAura: Boolean,
    val baseGlowAlpha: Float,
    val peakGlowAlpha: Float,
    val glowSpreadDp: Float,
    val borderWidthDp: Float
) {
    COMMON(
        displayName = "Common",
        shortBadge = "COMMON",
        colorHex = InventoryItem.RARITY_COMMON_HEX,
        secondaryGlowHex = "#CBD5E1",
        hasPulsingAura = false,
        baseGlowAlpha = 0.24f,
        peakGlowAlpha = 0.24f,
        glowSpreadDp = 4f,
        borderWidthDp = 1.5f
    ),
    RARE(
        displayName = "Rare",
        shortBadge = "RARE",
        colorHex = InventoryItem.RARITY_RARE_HEX,
        secondaryGlowHex = "#60A5FA",
        hasPulsingAura = false,
        baseGlowAlpha = 0.38f,
        peakGlowAlpha = 0.38f,
        glowSpreadDp = 6.5f,
        borderWidthDp = 1.8f
    ),
    EPIC(
        displayName = "Epic",
        shortBadge = "EPIC",
        colorHex = InventoryItem.RARITY_EPIC_HEX,
        secondaryGlowHex = "#E879F9",
        hasPulsingAura = true,
        baseGlowAlpha = 0.32f,
        peakGlowAlpha = 0.76f,
        glowSpreadDp = 10f,
        borderWidthDp = 2.2f
    ),
    LEGENDARY(
        displayName = "Legendary",
        shortBadge = "LEGEND",
        colorHex = InventoryItem.RARITY_LEGENDARY_HEX,
        secondaryGlowHex = "#FDE047",
        hasPulsingAura = true,
        baseGlowAlpha = 0.42f,
        peakGlowAlpha = 0.90f,
        glowSpreadDp = 13f,
        borderWidthDp = 2.6f
    );

    val primaryColorHex: String
        get() = colorHex

    companion object {
        fun fromHex(hex: String?): RarityTier {
            return when (hex?.trim()?.uppercase(Locale.US)) {
                InventoryItem.RARITY_LEGENDARY_HEX, "#FB923C", "#F97316", "#FFA500" -> LEGENDARY
                InventoryItem.RARITY_EPIC_HEX, "#A855F7", "#9333EA" -> EPIC
                InventoryItem.RARITY_RARE_HEX, "#3B82F6", "#60A5FA" -> RARE
                else -> COMMON
            }
        }
    }
}

/**
 * Represents an active or completed RPG quest in the Adventure Engine with multi-step progress,
 * priority tier, creation timestamp, and optional Daily Challenge ("Quest of the Day") metadata.
 */
data class AdventureQuest(
    val id: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val xpReward: Int = 100,
    val currentStep: Int = if (isCompleted) 1 else 0,
    val totalSteps: Int = 1,
    val priority: QuestPriority = QuestPriority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val isDailyChallenge: Boolean = false,
    val dailyDateKey: String? = null
) {
    val safeTotalSteps: Int
        get() = totalSteps.coerceAtLeast(1)

    val safeCurrentStep: Int
        get() = if (isCompleted) safeTotalSteps else currentStep.coerceIn(0, safeTotalSteps)

    val completionFraction: Float
        get() = if (isCompleted) {
            1f
        } else {
            (safeCurrentStep.toFloat() / safeTotalSteps.toFloat()).coerceIn(0f, 1f)
        }

    val completionPercent: Int
        get() = (completionFraction * 100f).toInt().coerceIn(0, 100)

    /**
     * Evaluates whether this quest matches the user's drawer search query across
     * title, description, priority (`HIGH`, `MEDIUM`, `LOW`, `High Priority`, `MED`),
     * and daily challenge badge (`daily`, `quest of the day`, `dailyDateKey`).
     */
    fun matchesSearchQuery(rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        val tokens = query.lowercase(Locale.US)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        val searchableFields = buildString {
            append(title.lowercase(Locale.US)).append(' ')
            append(description.lowercase(Locale.US)).append(' ')
            append(priority.name.lowercase(Locale.US)).append(' ')
            append(priority.displayName.lowercase(Locale.US)).append(' ')
            append(priority.badgeLabel.lowercase(Locale.US)).append(' ')
            if (isDailyChallenge) {
                append("daily challenge quest of the day ")
                if (!dailyDateKey.isNullOrBlank()) {
                    append(dailyDateKey.lowercase(Locale.US)).append(' ')
                }
            }
        }
        return tokens.all { token -> searchableFields.contains(token) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("isCompleted", isCompleted)
        put("xpReward", xpReward)
        put("currentStep", safeCurrentStep)
        put("totalSteps", safeTotalSteps)
        put("priority", priority.name)
        put("createdAt", createdAt)
        put("isDailyChallenge", isDailyChallenge)
        if (!dailyDateKey.isNullOrBlank()) {
            put("dailyDateKey", dailyDateKey)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): AdventureQuest {
            val title = obj.optString("title", "Uncharted Objective").trim().ifBlank { "Uncharted Objective" }
            val rawId = obj.optString("id", "").trim()
            val id = rawId.ifBlank {
                "quest_" + title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            }
            val completed = obj.optBoolean("isCompleted", false)
            val totalSteps = obj.optInt("totalSteps", 1).coerceAtLeast(1)
            val defaultStep = if (completed) totalSteps else 0
            val currentStep = if (completed) {
                totalSteps
            } else {
                obj.optInt("currentStep", defaultStep).coerceIn(0, totalSteps)
            }
            return AdventureQuest(
                id = id,
                title = title,
                description = obj.optString("description", "Explore the unknown frontier and uncover the truth."),
                isCompleted = completed,
                xpReward = obj.optInt("xpReward", 100).coerceAtLeast(10),
                currentStep = currentStep,
                totalSteps = totalSteps,
                priority = QuestPriority.fromString(obj.optString("priority", QuestPriority.MEDIUM.name)),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                isDailyChallenge = obj.optBoolean("isDailyChallenge", false),
                dailyDateKey = obj.optString("dailyDateKey", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Connectivity & offline Room synchronization status for the Quest Drawer header badge.
 */
enum class AdventureSyncStatus(val displayLabel: String) {
    SYNCED("Online • Synced"),
    PENDING_OFFLINE("Offline • Queued"),
    SYNCING("Syncing...");

    companion object {
        fun fromString(raw: String?): AdventureSyncStatus {
            if (raw.isNullOrBlank()) return SYNCED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: SYNCED
        }
    }
}

/**
 * Room SQLite Entity (`adventure_quest_history`) caching individual quest mutations and history
 * locally so users can view and interact with their quest history offline, queuing mutations
 * with `syncStatus = "PENDING_OFFLINE"` until connectivity returns.
 */
@Entity(tableName = "adventure_quest_history")
data class AdventureQuestHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val historyId: Long = 0L,
    val chatId: Long,
    val questId: String,
    val title: String,
    val description: String,
    val priority: String = QuestPriority.MEDIUM.name,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val xpReward: Int = 100,
    val isDailyChallenge: Boolean = false,
    val actionType: String = "UPDATED",
    val syncStatus: String = AdventureSyncStatus.SYNCED.name,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a procedural or story item stored in the player's 3-column RPG inventory grid.
 */
data class InventoryItem(
    val id: String,
    val name: String,
    val category: String,
    val iconName: String,
    val rarityColorHex: String,
    val description: String,
    val quantity: Int = 1
) {
    val rarityTier: RarityTier
        get() = RarityTier.fromHex(rarityColorHex)

    val rarityLabel: String
        get() = rarityTier.displayName

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("iconName", iconName)
        put("rarityColorHex", rarityColorHex)
        put("description", description)
        put("quantity", quantity)
    }

    companion object {
        const val RARITY_COMMON_HEX = "#94A3B8"
        const val RARITY_RARE_HEX = "#38BDF8"
        const val RARITY_EPIC_HEX = "#C084FC"
        const val RARITY_LEGENDARY_HEX = "#F59E0B"

        fun fromJson(obj: JSONObject): InventoryItem {
            val name = obj.optString("name", "Mysterious Relic").trim().ifBlank { "Mysterious Relic" }
            val rawId = obj.optString("id", "").trim()
            val id = rawId.ifBlank {
                "item_" + name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            }
            return InventoryItem(
                id = id,
                name = name,
                category = obj.optString("category", "Artifact").ifBlank { "Artifact" },
                iconName = obj.optString("iconName", "AutoAwesome").ifBlank { "AutoAwesome" },
                rarityColorHex = obj.optString("rarityColorHex", RARITY_COMMON_HEX).ifBlank { RARITY_COMMON_HEX },
                description = obj.optString("description", "An artifact recovered from the shifting realms."),
                quantity = obj.optInt("quantity", 1).coerceAtLeast(1)
            )
        }
    }
}

/**
 * Configurable campaign settings for the Adventure Engine.
 */
data class GameSettings(
    val difficulty: DifficultyLevel = DifficultyLevel.BALANCED,
    val autoSaveOnQuest: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val loreContext: String = "",
    val questFilter: QuestFilterMode = QuestFilterMode.ALL,
    val questSortOrder: QuestSortOrder = QuestSortOrder.NEWEST
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("difficulty", difficulty.name)
        put("autoSaveOnQuest", autoSaveOnQuest)
        put("notificationsEnabled", notificationsEnabled)
        put("loreContext", loreContext)
        put("questFilter", questFilter.name)
        put("questSortOrder", questSortOrder.name)
    }

    companion object {
        fun fromJson(obj: JSONObject?): GameSettings {
            if (obj == null) return GameSettings()
            return GameSettings(
                difficulty = DifficultyLevel.fromString(obj.optString("difficulty", DifficultyLevel.BALANCED.name)),
                autoSaveOnQuest = obj.optBoolean("autoSaveOnQuest", true),
                notificationsEnabled = obj.optBoolean("notificationsEnabled", true),
                loreContext = obj.optString("loreContext", ""),
                questFilter = QuestFilterMode.fromString(obj.optString("questFilter", QuestFilterMode.ALL.name)),
                questSortOrder = QuestSortOrder.fromString(obj.optString("questSortOrder", QuestSortOrder.NEWEST.name))
            )
        }
    }
}

/**
 * Complete reactive UI + persistence state for Gemini's Infinite Adventure Engine.
 */
data class AdventureGameState(
    val chatId: Long = 0L,
    val isAdventureModeEnabled: Boolean = false,
    val quests: List<AdventureQuest> = defaultStarterQuests(),
    val inventory: List<InventoryItem> = defaultStarterInventory(),
    val settings: GameSettings = GameSettings(),
    val characterName: String = "Aether Vanguard",
    val characterClass: String = "Chrono-Wayfarer",
    val characterPortraitUri: String? = null,
    val attachedLoreFileNames: List<String> = emptyList(),
    val totalXp: Int = 150,
    val isDrawerOpen: Boolean = false,
    val activeDrawerTab: AdventureDrawerTab = AdventureDrawerTab.INVENTORY,
    val unsavedPlaySeconds: Long = 0L,
    val lastSavedAt: Long = 0L,
    val isSaveSentinelAlertVisible: Boolean = false,
    val statusBannerMessage: String? = null,
    val questSearchQuery: String = "",
    val isOnline: Boolean = true,
    val offlineQueueingModeEnabled: Boolean = false,
    val syncStatus: AdventureSyncStatus = AdventureSyncStatus.SYNCED,
    val pendingOfflineSyncCount: Int = 0,
    val questHistory: List<AdventureQuestHistoryEntity> = emptyList(),
    val isStealthMode: Boolean = false,
    val localPresence: PlayerPresence? = null,
    val partyPresences: List<PlayerPresence> = emptyList()
) {
    val level: Int
        get() = (1 + (totalXp / XP_PER_LEVEL)).coerceAtLeast(1)

    val xpIntoCurrentLevel: Int
        get() = totalXp.coerceAtLeast(0) % XP_PER_LEVEL

    val levelProgressFraction: Float
        get() = (xpIntoCurrentLevel.toFloat() / XP_PER_LEVEL.toFloat()).coerceIn(0f, 1f)

    val completedQuestCount: Int
        get() = quests.count { it.isCompleted }

    val activeQuestCount: Int
        get() = quests.count { !it.isCompleted }

    val questFilter: QuestFilterMode
        get() = settings.questFilter

    val questSortOrder: QuestSortOrder
        get() = settings.questSortOrder

    val dailyChallengeQuest: AdventureQuest?
        get() = quests.firstOrNull { it.isDailyChallenge }

    val isEffectiveOfflineState: Boolean
        get() = !isOnline || offlineQueueingModeEnabled || syncStatus == AdventureSyncStatus.PENDING_OFFLINE || pendingOfflineSyncCount > 0

    val topBarSyncModeLabel: String
        get() = if (offlineQueueingModeEnabled || !isOnline) "Offline Cache" else "Online Sync"

    /**
     * Human-readable connectivity & sync status badge label for the Quest Drawer header.
     */
    val connectivityBadgeLabel: String
        get() = when {
            !isOnline || offlineQueueingModeEnabled -> {
                if (pendingOfflineSyncCount > 0) {
                    "Offline • Queued ($pendingOfflineSyncCount)"
                } else {
                    "Offline • Queued"
                }
            }
            syncStatus == AdventureSyncStatus.SYNCING -> "Online • Syncing..."
            pendingOfflineSyncCount > 0 -> "Online • Syncing ($pendingOfflineSyncCount)"
            else -> "Online • Synced"
        }

    val filteredQuests: List<AdventureQuest>
        get() {
            val byFilter = when (settings.questFilter) {
                QuestFilterMode.ALL -> quests
                QuestFilterMode.ACTIVE -> quests.filter { !it.isCompleted }
                QuestFilterMode.COMPLETED -> quests.filter { it.isCompleted }
            }.filter { quest ->
                quest.matchesSearchQuery(questSearchQuery)
            }
            return when (settings.questSortOrder) {
                QuestSortOrder.NEWEST -> byFilter.sortedByDescending { it.createdAt }
                QuestSortOrder.OLDEST -> byFilter.sortedBy { it.createdAt }
                QuestSortOrder.HIGH_PRIORITY -> byFilter.sortedWith(
                    compareByDescending<AdventureQuest> { it.priority.weight }
                        .thenByDescending { it.isDailyChallenge }
                        .thenByDescending { it.createdAt }
                )
            }
        }

    val questCompletionProgressFraction: Float
        get() = if (quests.isEmpty()) 0f else (completedQuestCount.toFloat() / quests.size.toFloat()).coerceIn(0f, 1f)

    fun toEntity(targetChatId: Long = chatId): AdventureGameEntity {
        val questsArray = JSONArray().apply {
            quests.forEach { put(it.toJson()) }
        }
        val inventoryArray = JSONArray().apply {
            inventory.forEach { put(it.toJson()) }
        }
        val loreFilesArray = JSONArray().apply {
            attachedLoreFileNames.forEach { put(it) }
        }
        return AdventureGameEntity(
            chatId = targetChatId,
            isAdventureModeEnabled = isAdventureModeEnabled,
            questsJson = questsArray.toString(),
            inventoryJson = inventoryArray.toString(),
            settingsJson = settings.toJson().toString(),
            characterName = characterName,
            characterClass = characterClass,
            characterPortraitUri = characterPortraitUri,
            attachedLoreFilesJson = loreFilesArray.toString(),
            totalXp = totalXp,
            lastSavedAt = if (lastSavedAt > 0L) lastSavedAt else System.currentTimeMillis(),
            syncStatus = syncStatus.name,
            pendingOfflineMutations = pendingOfflineSyncCount.coerceAtLeast(0)
        )
    }

    companion object {
        const val XP_PER_LEVEL = 500
        const val GLOBAL_PRESET_CHAT_ID = -1L
        const val SAVE_SENTINEL_THRESHOLD_SECONDS = 20L * 60L // 20 minutes

        fun defaultStarterQuests(): List<AdventureQuest> = listOf(
            AdventureQuest(
                id = "quest_astral_beacon",
                title = "Awaken the Astral Beacon",
                description = "Locate the dormant relay spire in the Shattered Archive and restore its three resonance pylons.",
                isCompleted = false,
                xpReward = 250,
                currentStep = 1,
                totalSteps = 3,
                priority = QuestPriority.HIGH,
                createdAt = 1_700_000_300_000L
            ),
            AdventureQuest(
                id = "quest_cipher_codex",
                title = "Decipher the Obsidian Codex",
                description = "Use an ancient key or attach a lore scroll to translate the Vault's two encrypted sigil tablets.",
                isCompleted = false,
                xpReward = 300,
                currentStep = 0,
                totalSteps = 2,
                priority = QuestPriority.MEDIUM,
                createdAt = 1_700_000_200_000L
            ),
            AdventureQuest(
                id = "quest_first_horizon",
                title = "Cross the First Horizon",
                description = "Survive the opening encounter and establish your camp at the Nexus Crossroads.",
                isCompleted = true,
                xpReward = 150,
                currentStep = 1,
                totalSteps = 1,
                priority = QuestPriority.LOW,
                createdAt = 1_700_000_100_000L
            )
        )

        fun defaultStarterInventory(): List<InventoryItem> = listOf(
            InventoryItem(
                id = "item_chrono_compass",
                name = "Chrono-Astrolabe",
                category = "Relic",
                iconName = "Explore",
                rarityColorHex = InventoryItem.RARITY_EPIC_HEX,
                description = "A brass-and-iridium astrolabe whose rings spin toward timeline anomalies and hidden rifts.",
                quantity = 1
            ),
            InventoryItem(
                id = "item_aether_vial",
                name = "Luminous Aether Tonic",
                category = "Consumable",
                iconName = "Bolt",
                rarityColorHex = InventoryItem.RARITY_RARE_HEX,
                description = "Restores vitality and sharpens neural focus during perilous encounters.",
                quantity = 3
            ),
            InventoryItem(
                id = "item_plasma_blade",
                name = "Sol-Forged Arcblade",
                category = "Weapon",
                iconName = "AutoAwesome",
                rarityColorHex = InventoryItem.RARITY_LEGENDARY_HEX,
                description = "Hums with concentrated starfire capable of cleaving both enchanted armor and synth-alloy bulkheads.",
                quantity = 1
            ),
            InventoryItem(
                id = "item_wayfarer_cloak",
                name = "Drifter's Phase Cloak",
                category = "Armor",
                iconName = "Shield",
                rarityColorHex = InventoryItem.RARITY_COMMON_HEX,
                description = "Woven from adaptive stealth fibers that dampen footsteps and deflect desert embers.",
                quantity = 1
            )
        )
    }
}

/**
 * Room SQLite Entity backing [AdventureGameState] for 100% offline persistence.
 * `chatId > 0L` stores per-chat campaign state; `chatId == -1L` stores the Global Preset template.
 */
@Entity(tableName = "adventure_game_states")
data class AdventureGameEntity(
    @PrimaryKey
    val chatId: Long,
    val isAdventureModeEnabled: Boolean = false,
    val questsJson: String = "[]",
    val inventoryJson: String = "[]",
    val settingsJson: String = "{}",
    val characterName: String = "Aether Vanguard",
    val characterClass: String = "Chrono-Wayfarer",
    val characterPortraitUri: String? = null,
    val attachedLoreFilesJson: String = "[]",
    val totalXp: Int = 150,
    val lastSavedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = AdventureSyncStatus.SYNCED.name,
    val pendingOfflineMutations: Int = 0
) {
    fun toDomainState(
        isDrawerOpen: Boolean = false,
        activeDrawerTab: AdventureDrawerTab = AdventureDrawerTab.INVENTORY,
        unsavedPlaySeconds: Long = 0L,
        isOnline: Boolean = true,
        offlineQueueingModeEnabled: Boolean = false,
        questHistory: List<AdventureQuestHistoryEntity> = emptyList()
    ): AdventureGameState {
        val parsedQuests = runCatching {
            val arr = JSONArray(questsJson)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(AdventureQuest.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList()).ifEmpty { AdventureGameState.defaultStarterQuests() }

        val parsedInventory = runCatching {
            val arr = JSONArray(inventoryJson)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(InventoryItem.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList()).ifEmpty { AdventureGameState.defaultStarterInventory() }

        val parsedSettings = runCatching {
            GameSettings.fromJson(JSONObject(settingsJson))
        }.getOrDefault(GameSettings())

        val parsedLoreFiles = runCatching {
            val arr = JSONArray(attachedLoreFilesJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i, "").trim()
                    if (name.isNotEmpty()) add(name)
                }
            }
        }.getOrDefault(emptyList())

        val resolvedSyncStatus = AdventureSyncStatus.fromString(syncStatus)
        val pendingFromHistory = questHistory.count {
            it.syncStatus.equals(AdventureSyncStatus.PENDING_OFFLINE.name, ignoreCase = true)
        }
        val effectivePendingCount = maxOf(pendingOfflineMutations.coerceAtLeast(0), pendingFromHistory)

        return AdventureGameState(
            chatId = chatId,
            isAdventureModeEnabled = isAdventureModeEnabled,
            quests = parsedQuests,
            inventory = parsedInventory,
            settings = parsedSettings,
            characterName = characterName.ifBlank { "Aether Vanguard" },
            characterClass = characterClass.ifBlank { "Chrono-Wayfarer" },
            characterPortraitUri = characterPortraitUri,
            attachedLoreFileNames = parsedLoreFiles,
            totalXp = totalXp.coerceAtLeast(0),
            isDrawerOpen = isDrawerOpen,
            activeDrawerTab = activeDrawerTab,
            unsavedPlaySeconds = unsavedPlaySeconds,
            lastSavedAt = lastSavedAt,
            isOnline = isOnline,
            offlineQueueingModeEnabled = offlineQueueingModeEnabled,
            syncStatus = if (effectivePendingCount > 0 && (!isOnline || offlineQueueingModeEnabled)) {
                AdventureSyncStatus.PENDING_OFFLINE
            } else {
                resolvedSyncStatus
            },
            pendingOfflineSyncCount = effectivePendingCount,
            questHistory = questHistory
        )
    }
}

/**
 * Room DAO for offline persistence of [AdventureGameState] per chat session, global preset,
 * and offline quest history mutation log (`adventure_quest_history`).
 */
@Dao
interface AdventureGameDao {
    @Query("SELECT * FROM adventure_game_states WHERE chatId = :chatId LIMIT 1")
    fun observeStateForChat(chatId: Long): Flow<AdventureGameEntity?>

    @Query("SELECT * FROM adventure_game_states WHERE chatId = :chatId LIMIT 1")
    suspend fun getStateForChatSync(chatId: Long): AdventureGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: AdventureGameEntity)

    @Query("DELETE FROM adventure_game_states WHERE chatId = :chatId")
    suspend fun deleteStateForChat(chatId: Long)

    @Query("SELECT * FROM adventure_game_states ORDER BY lastSavedAt DESC")
    suspend fun getAllSavedStatesSync(): List<AdventureGameEntity>

    @Query("SELECT * FROM adventure_game_states WHERE syncStatus = 'PENDING_OFFLINE' OR pendingOfflineMutations > 0")
    suspend fun getPendingOfflineStatesSync(): List<AdventureGameEntity>

    @Query("UPDATE adventure_game_states SET syncStatus = 'SYNCED', pendingOfflineMutations = 0 WHERE chatId = :chatId")
    suspend fun markStateSynced(chatId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestHistory(entry: AdventureQuestHistoryEntity): Long

    @Query("SELECT * FROM adventure_quest_history WHERE chatId = :chatId ORDER BY updatedAt DESC LIMIT 100")
    fun observeQuestHistoryForChat(chatId: Long): Flow<List<AdventureQuestHistoryEntity>>

    @Query("SELECT * FROM adventure_quest_history WHERE chatId = :chatId ORDER BY updatedAt DESC LIMIT 100")
    suspend fun getQuestHistoryForChatSync(chatId: Long): List<AdventureQuestHistoryEntity>

    @Query("SELECT * FROM adventure_quest_history WHERE syncStatus = 'PENDING_OFFLINE' ORDER BY updatedAt ASC")
    suspend fun getPendingOfflineQuestHistorySync(): List<AdventureQuestHistoryEntity>

    @Query("UPDATE adventure_quest_history SET syncStatus = 'SYNCED' WHERE chatId = :chatId AND syncStatus = 'PENDING_OFFLINE'")
    suspend fun markQuestHistorySyncedForChat(chatId: Long)

    @Query("UPDATE adventure_quest_history SET syncStatus = 'SYNCED' WHERE syncStatus = 'PENDING_OFFLINE'")
    suspend fun markAllQuestHistorySynced()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayerPresence(presence: PlayerPresence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllPlayerPresence(presences: List<PlayerPresence>)

    @Query("SELECT * FROM player_presence_cache WHERE odinUid = :odinUid LIMIT 1")
    suspend fun getPlayerPresenceByUidSync(odinUid: String): PlayerPresence?

    @Query("SELECT * FROM player_presence_cache ORDER BY isOnline DESC, lastHeartbeatEpochMs DESC")
    suspend fun getAllPlayerPresenceSync(): List<PlayerPresence>

    @Query("SELECT * FROM player_presence_cache ORDER BY isOnline DESC, lastHeartbeatEpochMs DESC")
    fun observeAllPlayerPresence(): Flow<List<PlayerPresence>>
}

/**
 * Inline RPG state update events parsed from Gemini's response markers:
 * `[QUEST_ADD: ...]`, `[QUEST_STEP: ...]`, `[QUEST_COMPLETE: ...]`, and `[LOOT_DROP: ...]`.
 */
sealed class AdventureInlineEvent {
    data class QuestAdded(val quest: AdventureQuest) : AdventureInlineEvent()
    data class QuestStepUpdated(val questIdOrTitle: String, val stepDelta: Int = 1) : AdventureInlineEvent()
    data class QuestCompleted(val questIdOrTitle: String) : AdventureInlineEvent()
    data class LootDropped(val item: InventoryItem, val seed: Long) : AdventureInlineEvent()
}

data class ParsedAdventureMessage(
    val cleanedText: String,
    val events: List<AdventureInlineEvent>
)

/**
 * Procedural RNG Loot Generator, Document Lore Context Parser, System Prompt Context Engine,
 * Inline RPG Marker Parser, and Save Sentinel Notification Helper.
 */
object AdventureEngine {

    private data class LootArchetype(
        val baseName: String,
        val category: String,
        val iconName: String,
        val loreTemplate: String
    )

    private val FANTASY_SCIFI_PREFIXES = listOf(
        "Astral",
        "Chrono",
        "Void-Touched",
        "Sol-Forged",
        "Quantum",
        "Obsidian",
        "Aetherial",
        "Cybernetic",
        "Runic",
        "Starlight",
        "Cryo-Core",
        "Valkyrie",
        "Nebula",
        "Eldritch",
        "Hyperion",
        "Arcane"
    )

    private val FANTASY_SCIFI_SUFFIXES = listOf(
        "of the First Dawn",
        "of Singularity",
        "Mk. IV",
        "of the Void Walker",
        "of Resonance",
        "Prime",
        "of the Shattered Spire",
        "of Eternal Echoes",
        "Omega",
        "of the Celestials",
        "of Neural Overdrive",
        "of the Horizon"
    )

    private val LOOT_ARCHETYPES = listOf(
        LootArchetype(
            baseName = "Phaseblade",
            category = "Weapon",
            iconName = "AutoAwesome",
            loreTemplate = "An edge of condensed photons and ancient runes that hums when danger approaches."
        ),
        LootArchetype(
            baseName = "Aegis Matrix",
            category = "Armor",
            iconName = "Shield",
            loreTemplate = "Projects a hexagonal kinetic barrier calibrated against both spellfire and plasma bolts."
        ),
        LootArchetype(
            baseName = "Chrono-Compass",
            category = "Relic",
            iconName = "Explore",
            loreTemplate = "Points toward timeline fractures, concealed vaults, and forgotten leyline conduits."
        ),
        LootArchetype(
            baseName = "Neural Elixir",
            category = "Consumable",
            iconName = "Bolt",
            loreTemplate = "Distilled stardust and bio-nanites that mend wounds and heighten tactical reflexes."
        ),
        LootArchetype(
            baseName = "Graviton Grimoire",
            category = "Tome",
            iconName = "MenuBook",
            loreTemplate = "Contains encrypted star-charts and forbidden incantations written in luminous ink."
        ),
        LootArchetype(
            baseName = "Rift Keycard",
            category = "Key",
            iconName = "VpnKey",
            loreTemplate = "Unlocks sealed quantum bulkheads and ancient sanctum gates across the frontier."
        ),
        LootArchetype(
            baseName = "Warp Beacon",
            category = "Gadget",
            iconName = "Public",
            loreTemplate = "Anchors a localized spatial fold for emergency extraction or orbital supply drops."
        ),
        LootArchetype(
            baseName = "Soulstone Core",
            category = "Artifact",
            iconName = "Diamond",
            loreTemplate = "Pulses with the preserved memories and arcane energy of a fallen civilization."
        )
    )

    /**
     * Generates a deterministic, seeded RPG [InventoryItem] across fantasy and sci-fi pools
     * with weighted dynamic rarity colors (`Common`, `Rare`, `Epic`, `Legendary`).
     */
    fun generateProceduralLoot(seed: Long): InventoryItem {
        val rng = Random(seed)
        val prefix = FANTASY_SCIFI_PREFIXES[rng.nextInt(FANTASY_SCIFI_PREFIXES.size)]
        val archetype = LOOT_ARCHETYPES[rng.nextInt(LOOT_ARCHETYPES.size)]
        val suffix = FANTASY_SCIFI_SUFFIXES[rng.nextInt(FANTASY_SCIFI_SUFFIXES.size)]

        val rarityRoll = rng.nextInt(100)
        val (rarityHex, rarityTierName, quantity) = when {
            rarityRoll < 50 -> Triple(InventoryItem.RARITY_COMMON_HEX, "Common", rng.nextInt(1, 4))
            rarityRoll < 80 -> Triple(InventoryItem.RARITY_RARE_HEX, "Rare", rng.nextInt(1, 3))
            rarityRoll < 95 -> Triple(InventoryItem.RARITY_EPIC_HEX, "Epic", 1)
            else -> Triple(InventoryItem.RARITY_LEGENDARY_HEX, "Legendary", 1)
        }

        val fullName = "$prefix ${archetype.baseName} $suffix"
        val deterministicId = "loot_${kotlin.math.abs(seed)}_${archetype.baseName.lowercase(Locale.US)}"
        val description = "[$rarityTierName ${archetype.category}] ${archetype.loreTemplate} (Seed #$seed)"

        return InventoryItem(
            id = deterministicId,
            name = fullName,
            category = archetype.category,
            iconName = archetype.iconName,
            rarityColorHex = rarityHex,
            description = description,
            quantity = quantity
        )
    }

    /**
     * Extracts readable lore text from plain text, Markdown (`.md`), JSON, or PDF documents
     * so it can be dynamically injected into [GameSettings.loreContext].
     */
    fun extractLoreFromDocument(context: Context, uri: Uri, mimeType: String?): String {
        val resolvedMime = (mimeType ?: context.contentResolver.getType(uri) ?: "text/plain").lowercase(Locale.US)
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        if (rawBytes.isEmpty()) return ""

        val isPdf = resolvedMime.contains("pdf") ||
            (rawBytes.size >= 4 &&
                rawBytes[0] == '%'.code.toByte() &&
                rawBytes[1] == 'P'.code.toByte() &&
                rawBytes[2] == 'D'.code.toByte() &&
                rawBytes[3] == 'F'.code.toByte())

        val extracted = if (isPdf) {
            extractReadableTextFromPdfBytes(rawBytes)
        } else {
            BufferedReader(InputStreamReader(rawBytes.inputStream(), StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }

        return extracted
            .replace("\r\n", "\n")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), " ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(12_000)
    }

    /**
     * Extracts text literals (`(...)`) and decompressed FlateDecode streams from PDF bytes
     * without requiring external native PDF libraries.
     */
    internal fun extractReadableTextFromPdfBytes(pdfBytes: ByteArray): String {
        val rawAscii = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val collectedChunks = mutableListOf<String>()

        // 1. Extract uncompressed PDF text literal blocks (...) inside BT ... ET sections
        extractPdfParenthesizedLiterals(rawAscii, collectedChunks)

        // 2. Also scan compressed stream ... endstream blocks using zlib Inflater
        val streamMarker = "stream"
        val endStreamMarker = "endstream"
        var searchIdx = 0
        while (searchIdx < rawAscii.length) {
            val start = rawAscii.indexOf(streamMarker, searchIdx)
            if (start == -1) break
            var dataStart = start + streamMarker.length
            if (dataStart < rawAscii.length && rawAscii[dataStart] == '\r') dataStart++
            if (dataStart < rawAscii.length && rawAscii[dataStart] == '\n') dataStart++
            val end = rawAscii.indexOf(endStreamMarker, dataStart)
            if (end == -1) break

            val length = (end - dataStart).coerceAtMost(256 * 1024)
            if (length > 8) {
                runCatching {
                    val slice = pdfBytes.copyOfRange(dataStart, dataStart + length)
                    val decompressed = inflateZlibBytes(slice)
                    if (decompressed.isNotEmpty()) {
                        val decompressedText = String(decompressed, StandardCharsets.ISO_8859_1)
                        extractPdfParenthesizedLiterals(decompressedText, collectedChunks)
                    }
                }
            }
            searchIdx = end + endStreamMarker.length
        }

        if (collectedChunks.isNotEmpty()) {
            return collectedChunks.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        }

        // Fallback: extract printable ASCII words of length >= 4
        return Regex("[A-Za-z0-9.,;:'\"?!\\-()\\[\\]\\s]{12,}")
            .findAll(rawAscii)
            .map { it.value.trim() }
            .filter { candidate ->
                !candidate.startsWith("%PDF") &&
                    !candidate.contains("/Type") &&
                    !candidate.contains("/Filter") &&
                    !candidate.contains("endobj")
            }
            .joinToString("\n")
            .take(8_000)
    }

    private fun extractPdfParenthesizedLiterals(source: String, out: MutableList<String>) {
        val literalRegex = Regex("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)")
        for (match in literalRegex.findAll(source)) {
            val unescaped = match.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
                .trim()
            if (unescaped.length >= 2 && unescaped.any { it.isLetter() }) {
                out.add(unescaped)
            }
        }
    }

    private fun inflateZlibBytes(compressed: ByteArray): ByteArray {
        return try {
            InflaterInputStream(compressed.inputStream(), Inflater(false)).use { it.readBytes() }
        } catch (_: Exception) {
            try {
                InflaterInputStream(compressed.inputStream(), Inflater(true)).use { it.readBytes() }
            } catch (_: Exception) {
                byteArrayOf()
            }
        }
    }

    /**
     * Dynamically prefixes outgoing Gemini system instructions during active Adventure Mode
     * with the structured Game Master context engine.
     */
    fun buildGameMasterSystemPrompt(
        baseSystemInstruction: String,
        state: AdventureGameState
    ): String {
        if (!state.isAdventureModeEnabled) return baseSystemInstruction

        val activeQuestsSummary = state.quests
            .filter { !it.isCompleted }
            .joinToString("; ") { "${it.title} (id=${it.id}, XP=${it.xpReward}): ${it.description}" }
            .ifBlank { "None active — introduce a compelling new quest hook!" }

        val completedQuestsSummary = state.quests
            .filter { it.isCompleted }
            .joinToString(", ") { it.title }
            .ifBlank { "None yet" }

        val inventorySummary = state.inventory
            .joinToString(", ") { "${it.name} x${it.quantity} [${it.rarityLabel} ${it.category}]" }
            .ifBlank { "Empty pack" }

        val customLoreBlock = if (state.settings.loreContext.isNotBlank()) {
            """
            [WORLD LORE & ATTACHED DOCUMENT CONTEXT]
            ${state.settings.loreContext.trim()}
            """.trimIndent()
        } else {
            "[WORLD LORE & ATTACHED DOCUMENT CONTEXT]\nDynamic Sci-Fi & Arcane Frontier Universe."
        }

        return """
            $baseSystemInstruction

            === GEMINI'S INFINITE ADVENTURE ENGINE (GAME MASTER DIRECTIVE) ===
            You are the interactive Game Master running an open-ended, non-linear RPG campaign.
            - Character: ${state.characterName} (${state.characterClass}) • Level ${state.level} (${state.totalXp} XP)
            - Difficulty: ${state.settings.difficulty.displayName} — ${state.settings.difficulty.promptDirective}
            - Active Quests: $activeQuestsSummary
            - Completed Quests: $completedQuestsSummary
            - Current Inventory: $inventorySummary
            $customLoreBlock

            [INLINE RPG STATE UPDATE MARKERS — MANDATORY FORMAT]
            Whenever narrative events introduce a new quest, advance a multi-step quest, complete an active quest, or award loot, emit one or more inline markers at the end of your response using these exact tags:
            1. Add a new quest (supports multi-step goals & priority HIGH/MEDIUM/LOW):
               [QUEST_ADD: {"id":"quest_unique_id","title":"Quest Title","description":"Objective details","xpReward":250,"totalSteps":3,"priority":"HIGH"}]
            2. Advance a multi-step quest objective:
               [QUEST_STEP: {"id":"quest_unique_id","stepDelta":1}]
            3. Mark an active quest completed:
               [QUEST_COMPLETE: {"id":"quest_unique_id","title":"Quest Title"}]
            4. Award a procedural loot drop (provide any integer seed):
               [LOOT_DROP: {"seed":749201}]

            Keep story progression non-linear, vivid, and responsive to player choices, inventory items used, and voice commands. Always conclude each turn with 2–3 distinct tactical or roleplay choices while allowing freeform actions.
        """.trimIndent()
    }

    private val MARKER_REGEX = Regex(
        pattern = "\\[(QUEST_ADD|QUEST_STEP|QUEST_PROGRESS|QUEST_COMPLETE|LOOT_DROP)\\s*:\\s*([^\\]]+)\\]",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Parses inline RPG markers (`[QUEST_ADD: ...]`, `[QUEST_STEP: ...]`, `[QUEST_COMPLETE: ...]`, `[LOOT_DROP: ...]`)
     * from a Gemini message, returning both the cleaned prose text and the structured list of
     * [AdventureInlineEvent] items to render as styled RPG event badges in chat and apply to [AdventureGameState].
     */
    fun parseInlineMarkers(rawContent: String): ParsedAdventureMessage {
        if (!rawContent.contains("[QUEST_ADD", ignoreCase = true) &&
            !rawContent.contains("[QUEST_STEP", ignoreCase = true) &&
            !rawContent.contains("[QUEST_PROGRESS", ignoreCase = true) &&
            !rawContent.contains("[QUEST_COMPLETE", ignoreCase = true) &&
            !rawContent.contains("[LOOT_DROP", ignoreCase = true)
        ) {
            return ParsedAdventureMessage(cleanedText = rawContent, events = emptyList())
        }

        val events = mutableListOf<AdventureInlineEvent>()
        val cleaned = MARKER_REGEX.replace(rawContent) { matchResult ->
            val tag = matchResult.groupValues[1].uppercase(Locale.US).trim()
            val payload = matchResult.groupValues[2].trim()
            parseSingleMarkerPayload(tag, payload)?.let { events.add(it) }
            ""
        }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return ParsedAdventureMessage(
            cleanedText = cleaned.ifBlank { rawContent },
            events = events
        )
    }

    private fun parseSingleMarkerPayload(tag: String, payload: String): AdventureInlineEvent? {
        return try {
            when (tag) {
                "QUEST_ADD" -> {
                    if (payload.startsWith("{")) {
                        val json = JSONObject(payload)
                        AdventureInlineEvent.QuestAdded(AdventureQuest.fromJson(json))
                    } else {
                        val parts = payload.split("|").map { it.trim() }
                        val title = parts.getOrNull(0)?.trim('"', '\'').orEmpty().ifBlank { "New Frontier Quest" }
                        val desc = parts.getOrNull(1)?.trim('"', '\'').orEmpty()
                            .ifBlank { "Fulfill the objective revealed by the Game Master." }
                        val xp = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 200
                        val steps = parts.getOrNull(3)?.filter { it.isDigit() }?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val id = "quest_" + title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        AdventureInlineEvent.QuestAdded(
                            AdventureQuest(
                                id = id,
                                title = title,
                                description = desc,
                                isCompleted = false,
                                xpReward = xp.coerceAtLeast(25),
                                currentStep = 0,
                                totalSteps = steps
                            )
                        )
                    }
                }
                "QUEST_STEP", "QUEST_PROGRESS" -> {
                    if (payload.startsWith("{")) {
                        val json = JSONObject(payload)
                        val idOrTitle = json.optString("id").ifBlank { json.optString("title") }
                        val delta = json.optInt("stepDelta", json.optInt("delta", 1)).coerceAtLeast(1)
                        if (idOrTitle.isNotBlank()) {
                            AdventureInlineEvent.QuestStepUpdated(idOrTitle, delta)
                        } else null
                    } else {
                        val parts = payload.split("|").map { it.trim() }
                        val idOrTitle = parts.getOrNull(0)?.trim('"', '\'').orEmpty()
                        val delta = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        if (idOrTitle.isNotBlank()) {
                            AdventureInlineEvent.QuestStepUpdated(idOrTitle, delta)
                        } else null
                    }
                }
                "QUEST_COMPLETE" -> {
                    val identifier = if (payload.startsWith("{")) {
                        val json = JSONObject(payload)
                        json.optString("id").ifBlank { json.optString("title") }
                    } else {
                        payload.trim('"', '\'', ' ')
                    }
                    if (identifier.isNotBlank()) {
                        AdventureInlineEvent.QuestCompleted(identifier)
                    } else null
                }
                "LOOT_DROP" -> {
                    val seed = if (payload.startsWith("{")) {
                        val json = JSONObject(payload)
                        if (json.has("seed")) {
                            json.optLong("seed", System.currentTimeMillis())
                        } else {
                            payload.hashCode().toLong()
                        }
                    } else {
                        payload.filter { it.isDigit() || it == '-' }.toLongOrNull()
                            ?: payload.hashCode().toLong()
                    }
                    val item = if (payload.startsWith("{") && JSONObject(payload).has("name")) {
                        val json = JSONObject(payload)
                        if (json.has("rarityColorHex")) {
                            InventoryItem.fromJson(json)
                        } else {
                            val generated = generateProceduralLoot(seed)
                            generated.copy(
                                name = json.optString("name", generated.name),
                                description = json.optString("description", generated.description)
                            )
                        }
                    } else {
                        generateProceduralLoot(seed)
                    }
                    AdventureInlineEvent.LootDropped(item = item, seed = seed)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates a procedural loot item guaranteed to roll at **Rare (`#38BDF8`), Epic (`#C084FC`), or Legendary (`#F59E0B`)**
     * rarity tier — used as the bonus reward for completing the Daily Challenge ("Quest of the Day").
     */
    fun generateGuaranteedRarePlusLoot(seed: Long): InventoryItem {
        val rng = Random(seed)
        val prefix = FANTASY_SCIFI_PREFIXES[rng.nextInt(FANTASY_SCIFI_PREFIXES.size)]
        val archetype = LOOT_ARCHETYPES[rng.nextInt(LOOT_ARCHETYPES.size)]
        val suffix = FANTASY_SCIFI_SUFFIXES[rng.nextInt(FANTASY_SCIFI_SUFFIXES.size)]

        val roll = rng.nextInt(100)
        val rarityHex = when {
            roll < 20 -> InventoryItem.RARITY_LEGENDARY_HEX // 20% Legendary
            roll < 55 -> InventoryItem.RARITY_EPIC_HEX // 35% Epic
            else -> InventoryItem.RARITY_RARE_HEX // 45% Rare
        }
        val rarityName = RarityTier.fromHex(rarityHex).displayName
        val fullName = "$prefix ${archetype.baseName} $suffix"
        val deterministicId = "item_daily_${seed.toULong().toString(16)}"
        return InventoryItem(
            id = deterministicId,
            name = fullName,
            category = archetype.category,
            iconName = archetype.iconName,
            rarityColorHex = rarityHex,
            description = "[$rarityName Daily Challenge Reward] ${archetype.loreTemplate}",
            quantity = 1
        )
    }

    /**
     * Generates one unique "Quest of the Day" daily challenge for the given [dateKey] (`yyyy-MM-dd`)
     * tailored to the user's recent chat/voice [interactionHistory].
     */
    fun generateDailyChallengeQuest(
        dateKey: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
        interactionHistory: List<String> = emptyList()
    ): AdventureQuest {
        val cleanedHistory = interactionHistory
            .map { it.trim() }
            .filter { it.length >= 3 }
        val primaryTopic = cleanedHistory.firstOrNull()
            ?.replace(Regex("[^A-Za-z0-9\\s-]"), "")
            ?.trim()
            ?.take(28)
            ?.takeIf { it.isNotBlank() }
            ?: "Astral Nexus"

        val seed = (dateKey.hashCode().toLong() shl 16) xor cleanedHistory.joinToString("|").hashCode().toLong()
        val rng = Random(seed)
        val templates = listOf(
            Triple(
                "Quest of the Day: Echoes of $primaryTopic",
                "Complete 3 tactical exchanges or lore discoveries inspired by your recent \"$primaryTopic\" activity to unlock a guaranteed Rare+ Relic.",
                3
            ),
            Triple(
                "Quest of the Day: Vanguard of $primaryTopic",
                "Investigate 2 anomalous signals tied to \"$primaryTopic\" and report your findings to the Game Master for bonus XP and Rare+ loot.",
                2
            ),
            Triple(
                "Quest of the Day: The $primaryTopic Convergence",
                "Synthesize insights from \"$primaryTopic\" across 3 campaign steps to stabilize the Chrono-Spire and claim a Rare+ cache.",
                3
            )
        )
        val chosen = templates[rng.nextInt(templates.size)]
        val bonusXp = 350 + (rng.nextInt(4) * 50)
        return AdventureQuest(
            id = "daily_quest_$dateKey",
            title = chosen.first,
            description = chosen.second,
            isCompleted = false,
            xpReward = bonusXp,
            currentStep = 0,
            totalSteps = chosen.third,
            priority = QuestPriority.HIGH,
            createdAt = System.currentTimeMillis() + 60_000L,
            isDailyChallenge = true,
            dailyDateKey = dateKey
        )
    }

    /**
     * Applies a list of [AdventureInlineEvent] items to the given [AdventureGameState],
     * returning the updated state and whether any quest was newly marked completed (for auto-save).
     * Completing a Daily Challenge quest automatically awards bonus XP + guaranteed Rare+ loot.
     */
    fun applyInlineEventsToState(
        current: AdventureGameState,
        events: List<AdventureInlineEvent>
    ): Pair<AdventureGameState, Boolean> {
        if (events.isEmpty()) return current to false

        var quests = current.quests.toMutableList()
        var inventory = current.inventory.toMutableList()
        var xp = current.totalXp
        var questCompleted = false
        var dailyChallengeCompleted = false
        var lastBanner: String? = current.statusBannerMessage

        for (event in events) {
            when (event) {
                is AdventureInlineEvent.QuestAdded -> {
                    val exists = quests.any {
                        it.id.equals(event.quest.id, ignoreCase = true) ||
                            it.title.equals(event.quest.title, ignoreCase = true)
                    }
                    if (!exists) {
                        quests.add(0, event.quest)
                        lastBanner = "New Quest Added: ${event.quest.title} (+${event.quest.xpReward} XP)"
                    }
                }
                is AdventureInlineEvent.QuestStepUpdated -> {
                    val target = event.questIdOrTitle.trim()
                    quests = quests.map { q ->
                        val matches = !q.isCompleted && (
                            q.id.equals(target, ignoreCase = true) ||
                                q.title.equals(target, ignoreCase = true) ||
                                q.title.contains(target, ignoreCase = true) ||
                                target.contains(q.title, ignoreCase = true)
                            )
                        if (matches) {
                            val nextStep = (q.safeCurrentStep + event.stepDelta).coerceAtMost(q.safeTotalSteps)
                            val nowCompleted = nextStep >= q.safeTotalSteps
                            if (nowCompleted) {
                                questCompleted = true
                                if (q.isDailyChallenge) dailyChallengeCompleted = true
                                xp += q.xpReward
                                lastBanner = "Quest Completed: ${q.title} (+${q.xpReward} XP)!"
                            } else {
                                lastBanner = "Quest Progress: ${q.title} (Step $nextStep/${q.safeTotalSteps})"
                            }
                            q.copy(currentStep = nextStep, isCompleted = nowCompleted)
                        } else {
                            q
                        }
                    }.toMutableList()
                }
                is AdventureInlineEvent.QuestCompleted -> {
                    val target = event.questIdOrTitle.trim()
                    var matchedAny = false
                    quests = quests.map { q ->
                        val matches = !q.isCompleted && (
                            q.id.equals(target, ignoreCase = true) ||
                                q.title.equals(target, ignoreCase = true) ||
                                q.title.contains(target, ignoreCase = true) ||
                                target.contains(q.title, ignoreCase = true)
                            )
                        if (matches) {
                            matchedAny = true
                            questCompleted = true
                            if (q.isDailyChallenge) dailyChallengeCompleted = true
                            xp += q.xpReward
                            lastBanner = "Quest Completed: ${q.title} (+${q.xpReward} XP)!"
                            q.copy(isCompleted = true, currentStep = q.safeTotalSteps)
                        } else {
                            q
                        }
                    }.toMutableList()
                    if (!matchedAny && target.isNotBlank()) {
                        val bonusQuest = AdventureQuest(
                            id = "quest_${UUID.randomUUID().toString().take(8)}",
                            title = target,
                            description = "Completed during live campaign encounter.",
                            isCompleted = true,
                            xpReward = 200,
                            currentStep = 1,
                            totalSteps = 1
                        )
                        quests.add(0, bonusQuest)
                        xp += bonusQuest.xpReward
                        questCompleted = true
                        lastBanner = "Quest Completed: ${bonusQuest.title} (+${bonusQuest.xpReward} XP)!"
                    }
                }
                is AdventureInlineEvent.LootDropped -> {
                    val existingIndex = inventory.indexOfFirst {
                        it.id.equals(event.item.id, ignoreCase = true) ||
                            it.name.equals(event.item.name, ignoreCase = true)
                    }
                    if (existingIndex >= 0) {
                        val existing = inventory[existingIndex]
                        inventory[existingIndex] = existing.copy(quantity = existing.quantity + event.item.quantity)
                    } else {
                        inventory.add(0, event.item)
                    }
                    lastBanner = "Loot Acquired: ${event.item.name} (${event.item.rarityLabel})"
                }
            }
        }

        if (dailyChallengeCompleted) {
            val dailyLoot = generateGuaranteedRarePlusLoot(System.currentTimeMillis())
            inventory.add(0, dailyLoot)
            lastBanner = "Daily Challenge Complete! Earned Bonus XP + ${dailyLoot.rarityLabel} ${dailyLoot.name}!"
        }

        val updated = current.copy(
            quests = quests,
            inventory = inventory,
            totalXp = xp,
            statusBannerMessage = lastBanner
        )
        return updated to questCompleted
    }

    @Volatile
    var questAddedFeedbackCount: Int = 0
        private set

    @Volatile
    var questCompletedFeedbackCount: Int = 0
        private set

    @Volatile
    var syncModeSwitchHapticCount: Int = 0
        private set

    @Volatile
    var lastSyncModeSwitchedToOfflineCache: Boolean? = null
        private set

    /**
     * Plays a subtle, crisp haptic vibration pulse when the app switches between
     * 'Online Sync' (single crisp 30ms pulse) and 'Offline Cache' (gentle double-tap pulse) modes.
     */
    fun playSyncModeSwitchHaptic(context: Context, isOfflineCacheMode: Boolean) {
        syncModeSwitchHapticCount++
        lastSyncModeSwitchedToOfflineCache = isOfflineCacheMode
        if (isOfflineCacheMode) {
            triggerHapticPulse(
                context = context,
                timings = longArrayOf(0L, 25L, 45L, 35L),
                amplitudes = intArrayOf(0, 125, 0, 165)
            )
        } else {
            triggerHapticPulse(
                context = context,
                timings = longArrayOf(0L, 30L),
                amplitudes = intArrayOf(0, 150)
            )
        }
    }

    /**
     * Plays a subtle ascending chime + crisp single-pulse haptic vibration when a new quest is added.
     * Uses `STREAM_NOTIFICATION` without requesting AudioFocus so active microphone listening is never disrupted.
     */
    fun playQuestAddedFeedback(context: Context) {
        questAddedFeedbackCount++
        triggerHapticPulse(context, timings = longArrayOf(0L, 35L), amplitudes = intArrayOf(0, 140))
        playNonBlockingTone(ToneGenerator.TONE_PROP_BEEP, durationMs = 70)
    }

    /**
     * Plays a celebratory two-tone chime + double-pulse haptic fanfare when a quest status changes to Completed.
     * Uses `STREAM_NOTIFICATION` without requesting AudioFocus so active microphone listening is never disrupted.
     */
    fun playQuestCompletedFeedback(context: Context) {
        questCompletedFeedbackCount++
        triggerHapticPulse(context, timings = longArrayOf(0L, 45L, 55L, 75L), amplitudes = intArrayOf(0, 180, 0, 230))
        playNonBlockingTone(ToneGenerator.TONE_PROP_ACK, durationMs = 130)
    }

    private fun triggerHapticPulse(context: Context, timings: LongArray, amplitudes: IntArray) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings.sum().coerceAtLeast(35L))
                }
            }
        } catch (_: Throwable) {
            // Safe no-op in headless/unit test environments
        }
    }

    private fun playNonBlockingTone(toneType: Int, durationMs: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            toneGen.startTone(toneType, durationMs)
        } catch (_: Throwable) {
            // Safe no-op in headless/unit test environments
        }
    }

    /**
     * Renders the user's current active quest progress, Hero level/XP, and Quest of the Day
     * into a high-resolution `1080x1350` RPG summary card [Bitmap].
     */
    fun renderQuestProgressSummaryBitmap(state: AdventureGameState): Bitmap {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Deep Obsidian Slate Gradient Background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(0xFF0F172A.toInt(), 0xFF1E293B.toInt(), 0xFF0F172A.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Outer Neon Card Frame
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = 0xFF38BDF8.toInt()
        }
        canvas.drawRoundRect(RectF(36f, 36f, width - 36f, height - 36f), 42f, 42f, framePaint)

        // 3. Header Title & Hero Summary
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF8FAFC.toInt()
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF38BDF8.toInt()
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textSize = 26f
        }
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 24f
        }

        canvas.drawText("INFINITE ADVENTURE ENGINE", 84f, 120f, subtitlePaint)
        canvas.drawText("${state.characterName} • ${state.characterClass}", 84f, 180f, titlePaint)
        canvas.drawText(
            "Level ${state.level} (${state.totalXp} XP)  |  Difficulty: ${state.settings.difficulty.displayName}  |  ${state.completedQuestCount}/${state.quests.size} Quests Completed",
            84f,
            230f,
            bodyPaint
        )

        // 4. Overall Campaign Progress Bar
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF10B981.toInt()
            style = Paint.Style.FILL
        }
        val barRect = RectF(84f, 260f, width - 84f, 290f)
        canvas.drawRoundRect(barRect, 15f, 15f, trackPaint)
        val fillWidth = (barRect.width() * state.questCompletionProgressFraction).coerceAtLeast(18f)
        canvas.drawRoundRect(
            RectF(barRect.left, barRect.top, barRect.left + fillWidth, barRect.bottom),
            15f,
            15f,
            fillPaint
        )

        // 5. Active Quests & Multi-Step Progress List
        var yCursor = 360f
        canvas.drawText("ACTIVE CAMPAIGN QUESTS (${state.activeQuestCount})", 84f, yCursor, subtitlePaint)
        yCursor += 30f

        val questsToRender = state.quests.filter { !it.isCompleted }.ifEmpty { state.quests }.take(5)
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            style = Paint.Style.FILL
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF334155.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (quest in questsToRender) {
            if (yCursor + 170f > height - 90f) break
            val cardRect = RectF(84f, yCursor, width - 84f, yCursor + 150f)
            canvas.drawRoundRect(cardRect, 24f, 24f, cardFillPaint)
            cardBorderPaint.color = try {
                android.graphics.Color.parseColor(quest.priority.colorHex)
            } catch (_: Throwable) {
                0xFF38BDF8.toInt()
            }
            canvas.drawRoundRect(cardRect, 24f, 24f, cardBorderPaint)

            val qTitle = if (quest.title.length > 42) quest.title.take(39) + "..." else quest.title
            canvas.drawText(
                "[${quest.priority.badgeLabel}] $qTitle",
                114f,
                yCursor + 52f,
                titlePaint.apply { textSize = 32f }
            )
            val stepStatus =
                "Step ${quest.safeCurrentStep}/${quest.safeTotalSteps} (${quest.completionPercent}%) • +${quest.xpReward} XP"
            canvas.drawText(stepStatus, 114f, yCursor + 94f, captionPaint)

            // Quest Multi-Step Linear Progress Bar
            val qBarRect = RectF(114f, yCursor + 112f, width - 114f, yCursor + 130f)
            canvas.drawRoundRect(qBarRect, 9f, 9f, trackPaint)
            val qFillW = (qBarRect.width() * quest.completionFraction).coerceAtLeast(12f)
            fillPaint.color = if (quest.isCompleted) 0xFF10B981.toInt() else 0xFF38BDF8.toInt()
            canvas.drawRoundRect(
                RectF(qBarRect.left, qBarRect.top, qBarRect.left + qFillW, qBarRect.bottom),
                9f,
                9f,
                fillPaint
            )

            yCursor += 172f
        }

        // 6. Footer Timestamp
        val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(Date())
        canvas.drawText("Exported from Gemini's Infinite Adventure Engine • $dateStr", 84f, height - 64f, captionPaint)
        return bitmap
    }

    /**
     * Exports the current active quest progress summary as a PNG file in `cacheDir/exports/`
     * and launches the Android system share sheet (`Intent.ACTION_SEND`) via [FileProvider].
     */
    fun shareQuestProgressSummaryImage(context: Context, state: AdventureGameState): File? {
        return try {
            val bitmap = renderQuestProgressSummaryBitmap(state)
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val imageFile = File(exportDir, "adventure_quest_summary_${state.chatId.coerceAtLeast(1L)}.png")
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            try {
                val authority = "${context.packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(context, authority, imageFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "${state.characterName} (Lvl ${state.level}) — ${state.completedQuestCount}/${state.quests.size} Quests Completed in Gemini's Infinite Adventure Engine!"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Active Quest Progress").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (_: Throwable) {
                // In Robolectric/headless unit tests without FileProvider activity chooser, file creation still succeeds
            }
            imageFile
        } catch (_: Throwable) {
            null
        }
    }

    private const val SAVE_SENTINEL_CHANNEL_ID = "adventure_save_sentinel_channel"
    private const val SAVE_SENTINEL_NOTIFICATION_ID = 42020

    /**
     * Posts a local Android status bar notification when active gameplay exceeds 20 minutes without a save.
     */
    fun postSaveSentinelNotification(context: Context, state: AdventureGameState) {
        if (!state.settings.notificationsEnabled) return
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SAVE_SENTINEL_CHANNEL_ID,
                    "Adventure Save Sentinel",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Reminders to save your Infinite Adventure Engine progress"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                SAVE_SENTINEL_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val minutesPlayed = (state.unsavedPlaySeconds / 60L).coerceAtLeast(20L)
            val notification = NotificationCompat.Builder(context, SAVE_SENTINEL_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Unsaved Adventure Progress (${minutesPlayed}m)")
                .setContentText(
                    "Level ${state.level} ${state.characterName} has ${state.unsavedPlaySeconds / 60}m of unsaved campaign progress. Tap or say \"save game\"."
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(SAVE_SENTINEL_NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
            // Ignore notification errors in headless/test environments
        }
    }
}
