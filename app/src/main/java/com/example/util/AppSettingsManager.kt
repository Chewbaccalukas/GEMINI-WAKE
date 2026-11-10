package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.bridge.LocalSyncRelayServer
import com.example.cache.ChatMemoryBufferManager
import com.example.coupler.ChromeBridgeSocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "AppSettingsManager"

/**
 * Result summary of a [AppSettingsManager.forceResetAndClear] system hygiene execution.
 */
data class HygieneResetResult(
    val relayServerStopped: Boolean,
    val bridgeSocketPortReset: Int,
    val audioBufferCleared: Boolean,
    val memoryBufferDetached: Boolean,
    val flushedCacheFilesCount: Int,
    val roomTablesPreserved: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val summaryMessage: String
        get() = "System hygiene complete: relay socket stopped, audio & memory buffers cleared, $flushedCacheFilesCount cache file(s) flushed (Room tables preserved)."
}

/**
 * Central manager for application-wide settings, system hygiene (`forceResetAndClear`),
 * favorite chat sessions, contextual wake-word routing preferences, and 30-day backup
 * export timestamp tracking.
 */
object AppSettingsManager {

    const val PREFS_NAME = "gemini_wake_settings"
    const val KEY_LAST_BACKUP_EXPORT_MS = "last_backup_export_epoch_ms"
    const val KEY_LAST_BACKUP_REMINDER_SHOWN_MS = "last_backup_reminder_shown_ms"
    const val KEY_FAVORITE_CHAT_IDS = "favorite_chat_ids_csv"
    const val KEY_PINNED_CHAT_IDS = "pinned_chat_ids_csv"
    const val KEY_PINNED_TIMESTAMPS_MAP = "pinned_chat_timestamps_map_entries"
    const val KEY_CONTEXTUAL_ROUTING_ENABLED = "contextual_wake_routing_enabled"
    const val KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE = "prompt_favorites_on_general_wake"
    const val KEY_FIRST_INSTALL_TIMESTAMP_MS = "first_install_timestamp_ms"
    const val KEY_CHAT_CATEGORY_MAP = "chat_category_map_entries"
    const val KEY_MESSAGE_CATEGORY_MAP = "message_category_map_entries"
    const val KEY_CHAT_TAGS_MAP = "chat_tags_map_entries"
    const val KEY_CUSTOM_CATEGORIES_CSV = "custom_chat_categories_csv"
    const val KEY_LOW_POWER_MODE_ENABLED = "low_power_mode_enabled"
    const val KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED = "screen_off_battery_saver_enabled"
    const val KEY_OFFLINE_QUEST_QUEUEING_ENABLED = "offline_quest_queueing_enabled"
    const val KEY_CUSTOM_WAKE_WORD_PHRASE = "custom_wake_word_phrase"
    const val KEY_CUSTOM_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
    const val KEY_CHAT_PRIORITY_MAP = "chat_priority_map_entries"
    const val KEY_ACTIVE_LOCATION_PROFILE = "active_location_profile_name"
    const val KEY_QUIET_MODE_ENABLED = "quiet_mode_enabled"
    const val KEY_CONTEXT_TRIGGER_RULES = "context_trigger_rules_entries"
    const val KEY_CHAT_IMPORTANCE_ORDER_MAP = "chat_importance_order_map_entries"
    const val KEY_CHAT_AVATAR_MAP = "chat_avatar_map_entries"
    const val KEY_CHAT_INITIAL_PROMPT_MAP = "chat_initial_prompt_map_entries"
    const val KEY_WAKE_WORD_ACTION_MAP = "wake_word_action_map_entries"
    const val KEY_CATEGORY_WAKE_WORD_MAP = "category_wake_word_map_entries"
    const val KEY_ADVENTURE_STEALTH_MODE = "adventure_stealth_mode_enabled"
    const val KEY_PINNED_FRIEND_UIDS = "adventure_pinned_friend_uids_csv"
    const val KEY_LOCAL_ADVENTURER_UID = "adventure_local_adventurer_uid"
    const val DEFAULT_CUSTOM_WAKE_WORD_PHRASE = "Hey Gemini"
    const val DEFAULT_CUSTOM_WAKE_WORD_SENSITIVITY = 0.78f

    const val DEFAULT_BATTERY_CAPACITY_MAH = 4300f
    const val BASE_IDLE_APP_DRAIN_MA = 145f
    const val WAKE_WORD_LISTENER_OVERHEAD_MA = 68f

    val PRIMARY_SIDE_FILTER_CATEGORIES = listOf("Work", "Personal", "Learning", "Projects")
    val DEFAULT_CHAT_CATEGORIES = listOf("Work", "Personal", "Learning", "Family", "Projects", "Research")
    val DEFAULT_SUGGESTED_TAGS = listOf("urgent", "daily", "ideas", "code", "notes", "voice")
    val BUILT_IN_PROFILE_AVATARS = listOf("Sparkles", "Briefcase", "GraduationCap", "Heart", "Rocket", "Code", "Shield", "Globe")

    const val BACKUP_REMINDER_THRESHOLD_DAYS = 30L
    const val BACKUP_REMINDER_THRESHOLD_MS = BACKUP_REMINDER_THRESHOLD_DAYS * 24L * 60L * 60L * 1000L

    private val _favoriteChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteChatIds: StateFlow<Set<Long>> = _favoriteChatIds.asStateFlow()

    private val _pinnedChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val pinnedChatIds: StateFlow<Set<Long>> = _pinnedChatIds.asStateFlow()

    private val _pinnedTimestampsMap = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pinnedTimestampsMap: StateFlow<Map<Long, Long>> = _pinnedTimestampsMap.asStateFlow()

    private val _contextualRoutingEnabled = MutableStateFlow(true)
    val contextualRoutingEnabled: StateFlow<Boolean> = _contextualRoutingEnabled.asStateFlow()

    private val _promptFavoritesOnWake = MutableStateFlow(false)
    val promptFavoritesOnWake: StateFlow<Boolean> = _promptFavoritesOnWake.asStateFlow()

    private val _chatCategoryMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatCategoryMap: StateFlow<Map<Long, String>> = _chatCategoryMap.asStateFlow()

    private val _messageCategoryMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val messageCategoryMap: StateFlow<Map<Long, String>> = _messageCategoryMap.asStateFlow()

    private val _chatTagsMap = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val chatTagsMap: StateFlow<Map<Long, Set<String>>> = _chatTagsMap.asStateFlow()

    private val _chatPriorityMap = MutableStateFlow<Map<Long, ChatPriorityLevel>>(emptyMap())
    val chatPriorityMap: StateFlow<Map<Long, ChatPriorityLevel>> = _chatPriorityMap.asStateFlow()

    private val _activeLocationProfile = MutableStateFlow(ContextLocationProfile.AUTO_DETECT)
    val activeLocationProfile: StateFlow<ContextLocationProfile> = _activeLocationProfile.asStateFlow()

    private val _quietModeEnabled = MutableStateFlow(false)
    val quietModeEnabled: StateFlow<Boolean> = _quietModeEnabled.asStateFlow()

    private val _contextTriggerRules = MutableStateFlow<Map<ContextTriggerType, ContextChatTriggerRule>>(emptyMap())
    val contextTriggerRules: StateFlow<Map<ContextTriggerType, ContextChatTriggerRule>> = _contextTriggerRules.asStateFlow()

    private val _chatImportanceOrderMap = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val chatImportanceOrderMap: StateFlow<Map<Long, Int>> = _chatImportanceOrderMap.asStateFlow()

    private val _chatAvatarMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatAvatarMap: StateFlow<Map<Long, String>> = _chatAvatarMap.asStateFlow()

    private val _chatInitialPromptMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatInitialPromptMap: StateFlow<Map<Long, String>> = _chatInitialPromptMap.asStateFlow()

    private val _wakeWordActionMap = MutableStateFlow<Map<String, WakeWordActionType>>(emptyMap())
    val wakeWordActionMap: StateFlow<Map<String, WakeWordActionType>> = _wakeWordActionMap.asStateFlow()

    private val _categoryWakeWordMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val categoryWakeWordMap: StateFlow<Map<String, String>> = _categoryWakeWordMap.asStateFlow()

    private val _isStealthMode = MutableStateFlow(false)
    val isStealthMode: StateFlow<Boolean> = _isStealthMode.asStateFlow()

    private val _pinnedFriendUids = MutableStateFlow<Set<String>>(emptySet())
    val pinnedFriendUids: StateFlow<Set<String>> = _pinnedFriendUids.asStateFlow()

    private val _availableCategories = MutableStateFlow<List<String>>(DEFAULT_CHAT_CATEGORIES)
    val availableCategories: StateFlow<List<String>> = _availableCategories.asStateFlow()

    private val _lastBackupExportTimestampMs = MutableStateFlow(0L)
    val lastBackupExportTimestampMs: StateFlow<Long> = _lastBackupExportTimestampMs.asStateFlow()

    private val _lastHygieneResetResult = MutableStateFlow<HygieneResetResult?>(null)
    val lastHygieneResetResult: StateFlow<HygieneResetResult?> = _lastHygieneResetResult.asStateFlow()

    private val _lastHygieneResetMessage = MutableStateFlow<String?>(null)
    val lastHygieneResetMessage: StateFlow<String?> = _lastHygieneResetMessage.asStateFlow()

    private val _lowPowerModeEnabled = MutableStateFlow(false)
    val lowPowerModeEnabled: StateFlow<Boolean> = _lowPowerModeEnabled.asStateFlow()

    private val _screenOffBatterySaverEnabled = MutableStateFlow(false)
    val screenOffBatterySaverEnabled: StateFlow<Boolean> = _screenOffBatterySaverEnabled.asStateFlow()

    private val _offlineQuestQueueingEnabled = MutableStateFlow(false)
    val offlineQuestQueueingEnabled: StateFlow<Boolean> = _offlineQuestQueueingEnabled.asStateFlow()

    private val _customWakeWordPhrase = MutableStateFlow(DEFAULT_CUSTOM_WAKE_WORD_PHRASE)
    val customWakeWordPhrase: StateFlow<String> = _customWakeWordPhrase.asStateFlow()

    private val _customWakeWordSensitivity = MutableStateFlow(DEFAULT_CUSTOM_WAKE_WORD_SENSITIVITY)
    val customWakeWordSensitivity: StateFlow<Float> = _customWakeWordSensitivity.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Hydrates reactive state flows from SharedPreferences on app startup.
     */
    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val sp = prefs(context)
            val now = System.currentTimeMillis()
            if (!sp.contains(KEY_FIRST_INSTALL_TIMESTAMP_MS)) {
                sp.edit().putLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, now).apply()
            }
            _favoriteChatIds.value = parseFavoriteIds(sp.getString(KEY_FAVORITE_CHAT_IDS, ""))
            _pinnedChatIds.value = parseFavoriteIds(sp.getString(KEY_PINNED_CHAT_IDS, ""))
            _pinnedTimestampsMap.value = parseTimestampMap(sp.getString(KEY_PINNED_TIMESTAMPS_MAP, ""))
            _contextualRoutingEnabled.value = sp.getBoolean(KEY_CONTEXTUAL_ROUTING_ENABLED, true)
            _promptFavoritesOnWake.value = sp.getBoolean(KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE, false)
            _lowPowerModeEnabled.value = sp.getBoolean(KEY_LOW_POWER_MODE_ENABLED, false)
            _screenOffBatterySaverEnabled.value = sp.getBoolean(KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED, false)
            _offlineQuestQueueingEnabled.value = sp.getBoolean(KEY_OFFLINE_QUEST_QUEUEING_ENABLED, false)
            _quietModeEnabled.value = sp.getBoolean(KEY_QUIET_MODE_ENABLED, false)
            val savedWakePhrase = sp.getString(KEY_CUSTOM_WAKE_WORD_PHRASE, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CUSTOM_WAKE_WORD_PHRASE
            _customWakeWordPhrase.value = savedWakePhrase
            val savedWakeSensitivity = sp.getFloat(
                KEY_CUSTOM_WAKE_WORD_SENSITIVITY,
                DEFAULT_CUSTOM_WAKE_WORD_SENSITIVITY
            ).coerceIn(0.50f, 0.98f)
            _customWakeWordSensitivity.value = savedWakeSensitivity
            _lastBackupExportTimestampMs.value = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
            val parsedCategoryMap = parseCategoryMap(sp.getString(KEY_CHAT_CATEGORY_MAP, ""))
            _chatCategoryMap.value = parsedCategoryMap
            _messageCategoryMap.value = parseCategoryMap(sp.getString(KEY_MESSAGE_CATEGORY_MAP, ""))
            _chatTagsMap.value = parseTagsMap(sp.getString(KEY_CHAT_TAGS_MAP, ""))
            _chatPriorityMap.value = parsePriorityMap(sp.getString(KEY_CHAT_PRIORITY_MAP, ""))
            _activeLocationProfile.value = ContextLocationProfile.fromName(
                sp.getString(KEY_ACTIVE_LOCATION_PROFILE, ContextLocationProfile.AUTO_DETECT.name)
            )
            _contextTriggerRules.value = parseContextTriggerRules(sp.getString(KEY_CONTEXT_TRIGGER_RULES, ""))
            _chatImportanceOrderMap.value = parseIntMap(sp.getString(KEY_CHAT_IMPORTANCE_ORDER_MAP, ""))
            _chatAvatarMap.value = parseCategoryMap(sp.getString(KEY_CHAT_AVATAR_MAP, ""))
            _chatInitialPromptMap.value = parseCategoryMap(sp.getString(KEY_CHAT_INITIAL_PROMPT_MAP, ""))
            _wakeWordActionMap.value = parseWakeWordActionMap(sp.getString(KEY_WAKE_WORD_ACTION_MAP, ""))
            _categoryWakeWordMap.value = parseStringKeyValueMap(sp.getString(KEY_CATEGORY_WAKE_WORD_MAP, ""))
            _isStealthMode.value = sp.getBoolean(KEY_ADVENTURE_STEALTH_MODE, false)
            _pinnedFriendUids.value = parseStringSetCsv(sp.getString(KEY_PINNED_FRIEND_UIDS, ""))
            val customCats = parseCustomCategories(sp.getString(KEY_CUSTOM_CATEGORIES_CSV, ""))
            _availableCategories.value = (DEFAULT_CHAT_CATEGORIES + customCats + parsedCategoryMap.values)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
            isInitialized = true
        }
    }

    private fun parseFavoriteIds(csv: String?): Set<Long> {
        if (csv.isNullOrBlank()) return emptySet()
        return csv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .toSet()
    }

    private fun parseTimestampMap(serialized: String?): Map<Long, Long> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, Long>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val ts = parts[1].trim().toLongOrNull()
                if (id != null && id > 0L && ts != null && ts > 0L) {
                    result[id] = ts
                }
            }
        }
        return result
    }

    private fun serializeTimestampMap(map: Map<Long, Long>): String {
        return map.entries
            .filter { it.key > 0L && it.value > 0L }
            .joinToString(";;") { "${it.key}::${it.value}" }
    }

    private fun parseCategoryMap(serialized: String?): Map<Long, String> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val category = parts[1].trim()
                if (id != null && id > 0L && category.isNotBlank()) {
                    result[id] = category
                }
            }
        }
        return result
    }

    private fun serializeCategoryMap(map: Map<Long, String>): String {
        return map.entries
            .filter { it.key > 0L && it.value.isNotBlank() }
            .joinToString(";;") { "${it.key}::${it.value.replace(";;", " ").replace("::", " ").trim()}" }
    }

    private fun parseTagsMap(serialized: String?): Map<Long, Set<String>> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, Set<String>>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val tags = parts[1].split("|")
                    .map { it.trim().removePrefix("#").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (id != null && id > 0L && tags.isNotEmpty()) {
                    result[id] = tags
                }
            }
        }
        return result
    }

    private fun serializeTagsMap(map: Map<Long, Set<String>>): String {
        return map.entries
            .filter { it.key > 0L && it.value.isNotEmpty() }
            .joinToString(";;") { (id, tags) ->
                val cleanTags = tags.map { it.replace("|", "").replace(";;", "").replace("::", "").trim() }
                    .filter { it.isNotBlank() }
                "$id::${cleanTags.joinToString("|")}"
            }
    }

    private fun parseCustomCategories(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getChatCategory(context: Context, chatId: Long): String? {
        ensureInitialized(context)
        return _chatCategoryMap.value[chatId]
    }

    fun setChatCategory(context: Context, chatId: Long, category: String?) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val cleanCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val updated = _chatCategoryMap.value.toMutableMap().apply {
            if (cleanCategory == null) remove(chatId) else put(chatId, cleanCategory)
        }.toMap()
        _chatCategoryMap.value = updated
        if (cleanCategory != null) {
            addCustomCategory(context, cleanCategory)
        }
        prefs(context).edit()
            .putString(KEY_CHAT_CATEGORY_MAP, serializeCategoryMap(updated))
            .apply()
    }

    /**
     * Automatically applies the conversation session's pre-assigned category to a message in that session.
     */
    fun applyCategoryToMessage(
        context: Context,
        messageId: Long,
        chatId: Long,
        explicitCategory: String? = null
    ): String? {
        if (messageId <= 0L && chatId <= 0L) return null
        ensureInitialized(context)
        val resolvedCategory = explicitCategory?.trim()?.takeIf { it.isNotBlank() }
            ?: _chatCategoryMap.value[chatId]?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (messageId > 0L) {
            val updatedMsgMap = _messageCategoryMap.value.toMutableMap().apply {
                put(messageId, resolvedCategory)
            }.toMap()
            _messageCategoryMap.value = updatedMsgMap
            prefs(context).edit()
                .putString(KEY_MESSAGE_CATEGORY_MAP, serializeCategoryMap(updatedMsgMap))
                .apply()
        }
        return resolvedCategory
    }

    /**
     * Updates the category for a chat session and propagates it to all message IDs belonging to that session.
     */
    fun applyCategoryToChatAndMessages(
        context: Context,
        chatId: Long,
        category: String?,
        messageIds: Collection<Long> = emptyList()
    ) {
        if (chatId <= 0L) return
        setChatCategory(context, chatId, category)
        val cleanCat = category?.trim()?.takeIf { it.isNotBlank() }
        if (messageIds.isNotEmpty()) {
            val updatedMsgMap = _messageCategoryMap.value.toMutableMap()
            for (msgId in messageIds) {
                if (msgId > 0L) {
                    if (cleanCat == null) updatedMsgMap.remove(msgId) else updatedMsgMap[msgId] = cleanCat
                }
            }
            val finalMap = updatedMsgMap.toMap()
            _messageCategoryMap.value = finalMap
            prefs(context).edit()
                .putString(KEY_MESSAGE_CATEGORY_MAP, serializeCategoryMap(finalMap))
                .apply()
        }
    }

    fun getEffectiveMessageCategory(messageId: Long, chatId: Long): String? {
        return _messageCategoryMap.value[messageId] ?: _chatCategoryMap.value[chatId]
    }

    fun addCustomCategory(context: Context, category: String) {
        val clean = category.trim()
        if (clean.isBlank()) return
        ensureInitialized(context)
        val merged = (_availableCategories.value + clean)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        _availableCategories.value = merged
        val customOnly = merged.filter { cat ->
            DEFAULT_CHAT_CATEGORIES.none { it.equals(cat, ignoreCase = true) }
        }
        prefs(context).edit()
            .putString(KEY_CUSTOM_CATEGORIES_CSV, customOnly.joinToString("|"))
            .apply()
    }

    fun getChatTags(context: Context, chatId: Long): Set<String> {
        ensureInitialized(context)
        return _chatTagsMap.value[chatId] ?: emptySet()
    }

    fun setChatTags(context: Context, chatId: Long, tags: Set<String>) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val cleanTags = tags.map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val updated = _chatTagsMap.value.toMutableMap().apply {
            if (cleanTags.isEmpty()) remove(chatId) else put(chatId, cleanTags)
        }.toMap()
        _chatTagsMap.value = updated
        prefs(context).edit()
            .putString(KEY_CHAT_TAGS_MAP, serializeTagsMap(updated))
            .apply()
    }

    fun toggleChatTag(context: Context, chatId: Long, tag: String): Set<String> {
        ensureInitialized(context)
        val normalized = tag.trim().removePrefix("#").trim()
        if (normalized.isBlank()) return getChatTags(context, chatId)
        val current = getChatTags(context, chatId).toMutableSet()
        val existingMatch = current.firstOrNull { it.equals(normalized, ignoreCase = true) }
        if (existingMatch != null) {
            current.remove(existingMatch)
        } else {
            current.add(normalized)
        }
        setChatTags(context, chatId, current)
        return current
    }

    fun isFavoriteChat(context: Context, chatId: Long): Boolean {
        ensureInitialized(context)
        return _favoriteChatIds.value.contains(chatId)
    }

    fun toggleFavoriteChat(context: Context, chatId: Long): Set<Long> {
        ensureInitialized(context)
        val updated = _favoriteChatIds.value.toMutableSet().apply {
            if (contains(chatId)) remove(chatId) else add(chatId)
        }.toSet()
        _favoriteChatIds.value = updated
        prefs(context).edit()
            .putString(KEY_FAVORITE_CHAT_IDS, updated.joinToString(","))
            .apply()
        return updated
    }

    fun setFavoriteChats(context: Context, chatIds: Set<Long>) {
        ensureInitialized(context)
        val cleaned = chatIds.filter { it > 0L }.toSet()
        _favoriteChatIds.value = cleaned
        prefs(context).edit()
            .putString(KEY_FAVORITE_CHAT_IDS, cleaned.joinToString(","))
            .apply()
    }

    fun isPinnedChat(context: Context, chatId: Long): Boolean {
        ensureInitialized(context)
        return _pinnedChatIds.value.contains(chatId)
    }

    /**
     * Toggles whether [chatId] is pinned to the top of the chat list regardless of recency.
     * Returns the updated set of pinned chat IDs.
     */
    fun togglePinnedChat(context: Context, chatId: Long, nowMs: Long = System.currentTimeMillis()): Set<Long> {
        if (chatId <= 0L) return _pinnedChatIds.value
        ensureInitialized(context)
        val updatedTimestamps = _pinnedTimestampsMap.value.toMutableMap()
        val updatedPinned = _pinnedChatIds.value.toMutableSet().apply {
            if (contains(chatId)) {
                remove(chatId)
                updatedTimestamps.remove(chatId)
            } else {
                add(chatId)
                updatedTimestamps[chatId] = nowMs
            }
        }.toSet()
        val finalTimestamps = updatedTimestamps.toMap()
        _pinnedChatIds.value = updatedPinned
        _pinnedTimestampsMap.value = finalTimestamps
        prefs(context).edit()
            .putString(KEY_PINNED_CHAT_IDS, updatedPinned.joinToString(","))
            .putString(KEY_PINNED_TIMESTAMPS_MAP, serializeTimestampMap(finalTimestamps))
            .apply()
        return updatedPinned
    }

    fun unpinChat(context: Context, chatId: Long) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        if (!_pinnedChatIds.value.contains(chatId)) return
        val updatedPinned = (_pinnedChatIds.value - chatId)
        val updatedTimestamps = (_pinnedTimestampsMap.value - chatId)
        _pinnedChatIds.value = updatedPinned
        _pinnedTimestampsMap.value = updatedTimestamps
        prefs(context).edit()
            .putString(KEY_PINNED_CHAT_IDS, updatedPinned.joinToString(","))
            .putString(KEY_PINNED_TIMESTAMPS_MAP, serializeTimestampMap(updatedTimestamps))
            .apply()
    }

    /**
     * Orders [chats] so that all pinned chats appear at the top of the list regardless of recency,
     * followed by unpinned chats ordered by recency (`lastUpdatedAt` descending).
     */
    fun sortChatsWithPinnedFirst(
        chats: List<com.example.data.model.ChatSession>,
        pinnedIds: Set<Long> = _pinnedChatIds.value,
        pinnedTimestamps: Map<Long, Long> = _pinnedTimestampsMap.value
    ): List<com.example.data.model.ChatSession> {
        if (chats.size <= 1) return chats
        return chats.sortedWith(
            Comparator { a, b ->
                val aPinned = a.isPinned || pinnedIds.contains(a.id)
                val bPinned = b.isPinned || pinnedIds.contains(b.id)
                when {
                    aPinned && !bPinned -> -1
                    !aPinned && bPinned -> 1
                    aPinned && bPinned -> {
                        val aPinTime = pinnedTimestamps[a.id] ?: 0L
                        val bPinTime = pinnedTimestamps[b.id] ?: 0L
                        if (aPinTime != bPinTime && aPinTime > 0L && bPinTime > 0L) {
                            bPinTime.compareTo(aPinTime)
                        } else {
                            b.lastUpdatedAt.compareTo(a.lastUpdatedAt)
                        }
                    }
                    else -> b.lastUpdatedAt.compareTo(a.lastUpdatedAt)
                }
            }
        )
    }

    fun setContextualRoutingEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _contextualRoutingEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_CONTEXTUAL_ROUTING_ENABLED, enabled).apply()
    }

    fun setPromptFavoritesOnWake(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _promptFavoritesOnWake.value = enabled
        prefs(context).edit().putBoolean(KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE, enabled).apply()
    }

    /**
     * Records a successful JSON or CSV backup export timestamp so the 30-day backup reminder
     * resets its countdown window.
     */
    fun recordBackupExportTimestamp(
        context: Context,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        ensureInitialized(context)
        _lastBackupExportTimestampMs.value = timestampMs
        prefs(context).edit()
            .putLong(KEY_LAST_BACKUP_EXPORT_MS, timestampMs)
            .apply()
    }

    fun getLastBackupExportTimestamp(context: Context): Long {
        ensureInitialized(context)
        return prefs(context).getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
    }

    /**
     * Returns true if the user has not exported a backup in over [thresholdDays] (default 30 days).
     * Uses the earliest known chat creation timestamp or first install timestamp when no export
     * has ever been performed yet.
     */
    fun isBackupOverdue(
        context: Context,
        earliestChatCreatedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        thresholdDays: Long = BACKUP_REMINDER_THRESHOLD_DAYS
    ): Boolean {
        ensureInitialized(context)
        val sp = prefs(context)
        val lastExportMs = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
        val referenceMs = when {
            lastExportMs > 0L -> lastExportMs
            earliestChatCreatedAtMs != null && earliestChatCreatedAtMs > 0L -> earliestChatCreatedAtMs
            else -> sp.getLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, nowMs)
        }
        val thresholdMs = thresholdDays * 24L * 60L * 60L * 1000L
        return (nowMs - referenceMs) >= thresholdMs
    }

    fun daysSinceLastBackup(
        context: Context,
        earliestChatCreatedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        ensureInitialized(context)
        val sp = prefs(context)
        val lastExportMs = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
        val referenceMs = when {
            lastExportMs > 0L -> lastExportMs
            earliestChatCreatedAtMs != null && earliestChatCreatedAtMs > 0L -> earliestChatCreatedAtMs
            else -> sp.getLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, nowMs)
        }
        return ((nowMs - referenceMs).coerceAtLeast(0L)) / (24L * 60L * 60L * 1000L)
    }

    /**
     * Pillar 3 — System Hygiene:
     * Stops the loopback relay socket (`LocalSyncRelayServer` and `ChromeBridgeSocketManager`),
     * clears the in-memory audio & message hot buffers, and flushes ephemeral cache files
     * (`exports/`, staged camera/screenshot files, and optional disk message cache buffers)
     * **without** wiping any Room database tables.
     */
    fun forceResetAndClear(
        context: Context,
        relayServer: LocalSyncRelayServer? = null,
        bufferManager: ChatMemoryBufferManager? = null,
        clearAudioBufferCallback: (() -> Unit)? = null,
        preserveDiskCache: Boolean = false
    ): HygieneResetResult {
        ensureInitialized(context)

        // 1. Stop LocalSyncRelayServer (127.0.0.1:8765) and clear deduplication cache
        var relayStopped = false
        try {
            if (relayServer != null) {
                relayServer.stop()
                relayServer.clearTurnDeduplicationCache()
                relayStopped = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping LocalSyncRelayServer during forceResetAndClear", e)
        }

        // 2. Force reset ChromeBridgeSocketManager
        val newBridgePort = try {
            ChromeBridgeSocketManager.forceResetAndClear(
                context = context.applicationContext,
                preserveDiskCache = preserveDiskCache
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting ChromeBridgeSocketManager", e)
            -1
        }

        // 3. Clear active PCM/voice audio buffer
        var audioCleared = false
        try {
            clearAudioBufferCallback?.invoke()
            audioCleared = true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing audio buffer during forceResetAndClear", e)
        }

        // 4. Detach in-memory hot window in ChatMemoryBufferManager
        var memoryDetached = false
        try {
            bufferManager?.detachChat()
            memoryDetached = true
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching ChatMemoryBufferManager", e)
        }

        // 5. Flush ephemeral cache files in cacheDir without touching Room SQLite database files
        val flushedCount = flushEphemeralCacheFiles(
            cacheDir = context.applicationContext.cacheDir,
            preserveChatDiskBuffers = preserveDiskCache
        )

        val result = HygieneResetResult(
            relayServerStopped = relayStopped,
            bridgeSocketPortReset = newBridgePort,
            audioBufferCleared = audioCleared,
            memoryBufferDetached = memoryDetached,
            flushedCacheFilesCount = flushedCount,
            roomTablesPreserved = true
        )
        _lastHygieneResetResult.value = result
        _lastHygieneResetMessage.value = result.summaryMessage
        Log.i(
            TAG,
            "forceResetAndClear complete: relayStopped=$relayStopped, flushedFiles=$flushedCount, roomTablesPreserved=true"
        )
        return result
    }

    /**
     * Deletes temporary export files (`cacheDir/exports`), staged camera/screenshot files,
     * and (unless [preserveChatDiskBuffers] is true) temporary disk message buffers.
     * Never touches the application's Room database directory.
     */
    fun flushEphemeralCacheFiles(
        cacheDir: File,
        preserveChatDiskBuffers: Boolean = false
    ): Int {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0
        var deletedCount = 0

        // Flush cacheDir/exports
        val exportsDir = File(cacheDir, "exports")
        if (exportsDir.exists() && exportsDir.isDirectory) {
            exportsDir.listFiles()?.forEach { file ->
                try {
                    if (file.isFile && file.delete()) {
                        deletedCount++
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // Flush cacheDir/chat_disk_buffers if not preserving across sessions
        if (!preserveChatDiskBuffers) {
            val diskBuffersDir = File(cacheDir, "chat_disk_buffers")
            if (diskBuffersDir.exists() && diskBuffersDir.isDirectory) {
                diskBuffersDir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile && file.delete()) {
                            deletedCount++
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        // Flush top-level ephemeral staged files (staged_camera_*, temp_*, screenshot_*)
        cacheDir.listFiles()?.forEach { file ->
            try {
                if (file.isFile && (
                        file.name.startsWith("staged_camera_") ||
                            file.name.startsWith("temp_") ||
                            file.name.startsWith("shared_") ||
                            file.name.endsWith(".tmp")
                        )
                ) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return deletedCount
    }

    /**
     * Enables or disables Low Power Mode, which stops the constant background wake-word
     * listener while keeping the UI microphone button functional to extend battery life on older devices.
     */
    fun setLowPowerModeEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_LOW_POWER_MODE_ENABLED, enabled).apply()
        _lowPowerModeEnabled.value = enabled
    }

    fun isLowPowerModeEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _lowPowerModeEnabled.value
    }

    /**
     * Enables or disables Screen-Off Battery Saver mode, which reduces the wake word detection
     * polling frequency (from 60ms to 450ms) when the device screen is off.
     */
    fun setScreenOffBatterySaverEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED, enabled).apply()
        _screenOffBatterySaverEnabled.value = enabled
        com.example.voice.WakeWordVoiceService.setScreenOffBatterySaverEnabled(enabled, context)
    }

    fun isScreenOffBatterySaverEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _screenOffBatterySaverEnabled.value
    }

    /**
     * Enables or disables the global offline Room-backed queueing mechanism (`'Offline Cache'` mode
     * when `true`, `'Online Sync'` mode when `false`).
     */
    fun setOfflineQuestQueueingEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_OFFLINE_QUEST_QUEUEING_ENABLED, enabled).apply()
        _offlineQuestQueueingEnabled.value = enabled
    }

    fun isOfflineQuestQueueingEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _offlineQuestQueueingEnabled.value
    }

    /**
     * Persists a user-defined custom wake word phrase in [SharedPreferences] (`gemini_wake_settings`)
     * and synchronizes it with [com.example.voice.WakeWordVoiceService] so background and foreground
     * speech recognizers immediately listen for the saved phrase.
     */
    fun saveCustomWakeWordPhrase(
        context: Context,
        phrase: String,
        chatId: Long? = null,
        sensitivity: Float = com.example.voice.WakeWordVoiceService.DEFAULT_CUSTOM_WAKE_SENSITIVITY
    ): String {
        ensureInitialized(context)
        val sanitized = phrase.trim().ifBlank { DEFAULT_CUSTOM_WAKE_WORD_PHRASE }
        val coercedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        prefs(context).edit()
            .putString(KEY_CUSTOM_WAKE_WORD_PHRASE, sanitized)
            .putFloat(KEY_CUSTOM_WAKE_WORD_SENSITIVITY, coercedSensitivity)
            .apply()
        _customWakeWordPhrase.value = sanitized
        _customWakeWordSensitivity.value = coercedSensitivity
        com.example.voice.WakeWordVoiceService.setCustomWakeWord(
            phrase = sanitized,
            chatId = chatId,
            sensitivity = coercedSensitivity,
            context = context
        )
        return sanitized
    }

    /**
     * Persists the wake word detection sensitivity threshold in [SharedPreferences] and updates
     * [com.example.voice.WakeWordVoiceService].
     */
    fun saveCustomWakeWordSensitivity(context: Context, sensitivity: Float): Float {
        ensureInitialized(context)
        val coerced = sensitivity.coerceIn(0.50f, 0.98f)
        prefs(context).edit()
            .putFloat(KEY_CUSTOM_WAKE_WORD_SENSITIVITY, coerced)
            .apply()
        _customWakeWordSensitivity.value = coerced
        com.example.voice.WakeWordVoiceService.setCustomWakeWord(
            phrase = _customWakeWordPhrase.value,
            chatId = com.example.voice.WakeWordVoiceService.customWakeWordTargetChatId,
            sensitivity = coerced,
            context = context
        )
        return coerced
    }

    /**
     * Reads the currently saved wake word detection sensitivity threshold from [SharedPreferences].
     */
    fun getCustomWakeWordSensitivity(context: Context): Float {
        ensureInitialized(context)
        val stored = prefs(context).getFloat(
            KEY_CUSTOM_WAKE_WORD_SENSITIVITY,
            DEFAULT_CUSTOM_WAKE_WORD_SENSITIVITY
        ).coerceIn(0.50f, 0.98f)
        if (stored != _customWakeWordSensitivity.value) {
            _customWakeWordSensitivity.value = stored
        }
        return _customWakeWordSensitivity.value
    }

    /**
     * Reads the currently saved custom wake word phrase from [SharedPreferences].
     */
    fun getCustomWakeWordPhrase(context: Context): String {
        ensureInitialized(context)
        val stored = prefs(context).getString(KEY_CUSTOM_WAKE_WORD_PHRASE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (stored != null && stored != _customWakeWordPhrase.value) {
            _customWakeWordPhrase.value = stored
        }
        return _customWakeWordPhrase.value
    }

    /**
     * Resets the custom wake word phrase in [SharedPreferences] back to [DEFAULT_CUSTOM_WAKE_WORD_PHRASE] ("Hey Gemini").
     */
    fun resetCustomWakeWordPhrase(context: Context): String {
        return saveCustomWakeWordPhrase(context, DEFAULT_CUSTOM_WAKE_WORD_PHRASE)
    }

    /**
     * Dynamic battery drain calculation for the Low Power Mode settings menu.
     * Queries Android [android.os.BatteryManager] for live battery percentage, charge counter (uAh),
     * and instantaneous discharge current (`BATTERY_PROPERTY_CURRENT_NOW`), falling back to
     * realistic device profiles when running on a virtualized/test battery.
     */
    fun calculateEstimatedBatteryRemaining(
        context: Context,
        lowPowerModeEnabled: Boolean = _lowPowerModeEnabled.value,
        batteryPercentOverride: Int? = null,
        measuredCurrentMaOverride: Float? = null
    ): BatteryDrainEstimate {
        val appContext = context.applicationContext
        val batteryManager = runCatching {
            appContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        }.getOrNull()

        val rawPercent = batteryPercentOverride ?: runCatching {
            val prop = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (prop in 1..100) {
                prop
            } else {
                val sticky = appContext.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val level = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else 78
            }
        }.getOrDefault(78).coerceIn(5, 100)

        val chargeCounterUah = runCatching {
            batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1
        }.getOrDefault(-1)

        val remainingChargeMah = if (chargeCounterUah > 100_000) {
            (chargeCounterUah / 1000f).coerceIn(150f, 6000f)
        } else {
            (DEFAULT_BATTERY_CAPACITY_MAH * (rawPercent / 100f)).coerceAtLeast(150f)
        }

        val rawCurrentNowUa = runCatching {
            batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        }.getOrDefault(0)

        val measuredMa = measuredCurrentMaOverride ?: run {
            val absVal = kotlin.math.abs(rawCurrentNowUa).toFloat()
            val converted = if (absVal > 2500f) absVal / 1000f else absVal
            if (converted in 45f..1600f) converted else 0f
        }

        val lowPowerDrainMa = if (measuredMa > 0f) {
            if (lowPowerModeEnabled) {
                measuredMa.coerceAtLeast(60f)
            } else {
                (measuredMa - WAKE_WORD_LISTENER_OVERHEAD_MA).coerceAtLeast(75f)
            }
        } else {
            BASE_IDLE_APP_DRAIN_MA
        }

        val activeWakeDrainMa = (lowPowerDrainMa + WAKE_WORD_LISTENER_OVERHEAD_MA).coerceAtLeast(lowPowerDrainMa + 25f)
        val effectiveDrainMa = if (lowPowerModeEnabled) lowPowerDrainMa else activeWakeDrainMa

        val hoursWithLowPower = (remainingChargeMah / lowPowerDrainMa).coerceIn(0.4f, 72f)
        val hoursWithWakeActive = (remainingChargeMah / activeWakeDrainMa).coerceIn(0.3f, 65f)
        val estimatedHoursRemaining = if (lowPowerModeEnabled) hoursWithLowPower else hoursWithWakeActive
        val bonusHoursSaved = (hoursWithLowPower - hoursWithWakeActive).coerceAtLeast(0.2f)

        val roundedEst = kotlin.math.round(estimatedHoursRemaining * 10f) / 10f
        val roundedBonus = kotlin.math.round(bonusHoursSaved * 10f) / 10f
        val roundedDrain = kotlin.math.round(effectiveDrainMa).toInt()

        val formattedLabel = if (lowPowerModeEnabled) {
            "Estimated Hours Remaining: ${roundedEst}h (+${roundedBonus}h saved • ~${roundedDrain} mA drain at $rawPercent%)"
        } else {
            "Estimated Hours Remaining: ${roundedEst}h (${roundedEst + roundedBonus}h with Low Power Mode • ~${roundedDrain} mA drain)"
        }

        return BatteryDrainEstimate(
            batteryPercent = rawPercent,
            remainingChargeMah = remainingChargeMah,
            currentDrainMa = effectiveDrainMa,
            activeWakeDrainMa = activeWakeDrainMa,
            lowPowerDrainMa = lowPowerDrainMa,
            estimatedHoursRemaining = roundedEst,
            bonusHoursSaved = roundedBonus,
            lowPowerModeEnabled = lowPowerModeEnabled,
            formattedLabel = formattedLabel
        )
    }

    private fun parsePriorityMap(raw: String?): Map<Long, ChatPriorityLevel> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, ChatPriorityLevel>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                if (id != null && id > 0L) {
                    result[id] = ChatPriorityLevel.fromName(parts[1].trim())
                }
            }
        }
        return result
    }

    private fun serializePriorityMap(map: Map<Long, ChatPriorityLevel>): String {
        return map.entries.joinToString(";") { "${it.key}=${it.value.name}" }
    }

    fun getChatPriorityLevel(context: Context, chatId: Long): ChatPriorityLevel {
        ensureInitialized(context)
        return _chatPriorityMap.value[chatId] ?: ChatPriorityLevel.NORMAL
    }

    fun setChatPriorityLevel(
        context: Context,
        chatId: Long,
        priority: ChatPriorityLevel
    ): Map<Long, ChatPriorityLevel> {
        ensureInitialized(context)
        if (chatId <= 0L) return _chatPriorityMap.value
        val updated = _chatPriorityMap.value.toMutableMap().apply {
            put(chatId, priority)
        }.toMap()
        _chatPriorityMap.value = updated
        prefs(context).edit()
            .putString(KEY_CHAT_PRIORITY_MAP, serializePriorityMap(updated))
            .apply()
        return updated
    }

    fun setActiveLocationProfile(
        context: Context,
        profile: ContextLocationProfile
    ): ContextLocationProfile {
        ensureInitialized(context)
        _activeLocationProfile.value = profile
        prefs(context).edit()
            .putString(KEY_ACTIVE_LOCATION_PROFILE, profile.name)
            .apply()
        return profile
    }

    /**
     * Resolves the user's current contextual place/location profile by checking:
     * 1. Explicit user-selected [activeLocationProfile] if not [ContextLocationProfile.AUTO_DETECT].
     * 2. Coarse location via Android `LocationManager.NETWORK_PROVIDER` when `ACCESS_COARSE_LOCATION` is granted.
     * 3. Smart schedule/time-of-day fallback (`WORK` during workday business hours, `LEARNING` in late afternoon,
     *    `HOME` in the evening/weekend).
     */
    fun resolveCurrentLocationContext(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): ResolvedLocationContext {
        ensureInitialized(context)
        val explicitProfile = _activeLocationProfile.value
        val hasCoarsePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        var coarseLatLonLabel: String? = null
        if (hasCoarsePermission) {
            runCatching {
                val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val lastLoc = locManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    ?: locManager?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                if (lastLoc != null) {
                    val latRounded = kotlin.math.round(lastLoc.latitude * 100.0) / 100.0
                    val lonRounded = kotlin.math.round(lastLoc.longitude * 100.0) / 100.0
                    coarseLatLonLabel = "Coarse GPS ($latRounded, $lonRounded)"
                }
            }
        }

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val isWorkday = day in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY

        val effectiveProfile = if (explicitProfile != ContextLocationProfile.AUTO_DETECT) {
            explicitProfile
        } else {
            when {
                isWorkday && hour in 9..16 -> ContextLocationProfile.WORK
                isWorkday && hour in 17..18 -> ContextLocationProfile.LEARNING
                hour >= 19 || hour < 7 || !isWorkday -> ContextLocationProfile.HOME
                else -> ContextLocationProfile.ON_THE_GO
            }
        }

        val sourceLabel = when {
            explicitProfile != ContextLocationProfile.AUTO_DETECT -> "Place Profile: ${effectiveProfile.displayLabel}"
            coarseLatLonLabel != null -> "${effectiveProfile.displayLabel} • $coarseLatLonLabel"
            else -> "${effectiveProfile.displayLabel} (Smart Schedule Fallback)"
        }

        return ResolvedLocationContext(
            effectiveProfile = effectiveProfile,
            hasCoarseLocationPermission = hasCoarsePermission,
            coarseLocationSummary = coarseLatLonLabel,
            displaySummary = sourceLabel
        )
    }

    // -------------------------------------------------------------------------
    // Quiet Mode (Temporary Wake-Word Detection Suspension)
    // -------------------------------------------------------------------------

    fun isQuietModeEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _quietModeEnabled.value
    }

    fun setQuietModeEnabled(context: Context, enabled: Boolean): Boolean {
        ensureInitialized(context)
        _quietModeEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_QUIET_MODE_ENABLED, enabled).apply()
        com.example.voice.WakeWordVoiceService.setQuietMode(enabled, context)
        return enabled
    }

    fun toggleQuietMode(context: Context): Boolean {
        ensureInitialized(context)
        return setQuietModeEnabled(context, !_quietModeEnabled.value)
    }

    // -------------------------------------------------------------------------
    // User-Configurable Context-to-Chat Trigger Rules
    // -------------------------------------------------------------------------

    private fun parseContextTriggerRules(raw: String?): Map<ContextTriggerType, ContextChatTriggerRule> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<ContextTriggerType, ContextChatTriggerRule>()
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size >= 2) {
                val type = ContextTriggerType.fromName(parts[0].trim())
                val chatId = parts[1].trim().toLongOrNull()
                val autoOpen = parts.getOrNull(2)?.trim()?.toBooleanStrictOrNull() ?: false
                val enabled = parts.getOrNull(3)?.trim()?.toBooleanStrictOrNull() ?: true
                if (type != null && chatId != null && chatId > 0L) {
                    result[type] = ContextChatTriggerRule(
                        contextType = type,
                        targetChatId = chatId,
                        autoOpenDirectly = autoOpen,
                        isEnabled = enabled
                    )
                }
            }
        }
        return result
    }

    private fun serializeContextTriggerRules(map: Map<ContextTriggerType, ContextChatTriggerRule>): String {
        return map.values
            .filter { it.targetChatId > 0L }
            .joinToString(";;") {
                "${it.contextType.name}::${it.targetChatId}::${it.autoOpenDirectly}::${it.isEnabled}"
            }
    }

    fun setContextTriggerRule(
        context: Context,
        contextType: ContextTriggerType,
        targetChatId: Long,
        autoOpenDirectly: Boolean = false,
        isEnabled: Boolean = true
    ): Map<ContextTriggerType, ContextChatTriggerRule> {
        ensureInitialized(context)
        val updated = _contextTriggerRules.value.toMutableMap().apply {
            if (targetChatId <= 0L) {
                remove(contextType)
            } else {
                put(
                    contextType,
                    ContextChatTriggerRule(
                        contextType = contextType,
                        targetChatId = targetChatId,
                        autoOpenDirectly = autoOpenDirectly,
                        isEnabled = isEnabled
                    )
                )
            }
        }.toMap()
        _contextTriggerRules.value = updated
        prefs(context).edit()
            .putString(KEY_CONTEXT_TRIGGER_RULES, serializeContextTriggerRules(updated))
            .apply()
        return updated
    }

    fun removeContextTriggerRule(
        context: Context,
        contextType: ContextTriggerType
    ): Map<ContextTriggerType, ContextChatTriggerRule> {
        return setContextTriggerRule(context, contextType, targetChatId = -1L)
    }

    /**
     * Evaluates active user-configured [ContextChatTriggerRule]s against the current
     * time of day, location profile, and recent chat activity.
     */
    fun resolveConfiguredContextTriggerMatch(
        chats: List<com.example.data.model.ChatSession>,
        rules: Map<ContextTriggerType, ContextChatTriggerRule> = _contextTriggerRules.value,
        locationProfile: ContextLocationProfile = _activeLocationProfile.value,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<ContextChatTriggerRule, com.example.data.model.ChatSession>? {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty() || rules.isEmpty()) return null

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val isWorkday = day in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY

        val activeContextTypes = buildList {
            when (locationProfile) {
                ContextLocationProfile.WORK -> add(ContextTriggerType.LOCATION_WORK)
                ContextLocationProfile.HOME -> add(ContextTriggerType.LOCATION_HOME)
                ContextLocationProfile.LEARNING -> add(ContextTriggerType.LOCATION_LEARNING)
                else -> Unit
            }
            if (isWorkday && hour in 9..17) add(ContextTriggerType.WORK_HOURS)
            if (hour in 5..11) add(ContextTriggerType.MORNING_ROUTINE)
            if (hour >= 18 || hour < 5) add(ContextTriggerType.EVENING_RELAX)
            if (!isWorkday) add(ContextTriggerType.WEEKEND)
            add(ContextTriggerType.RECENTLY_ACTIVE)
        }

        for (ctxType in activeContextTypes) {
            val rule = rules[ctxType] ?: continue
            if (!rule.isEnabled) continue
            val matchedChat = activeChats.firstOrNull { it.id == rule.targetChatId }
            if (matchedChat != null) {
                return rule to matchedChat
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Chat Importance Reordering & Custom Chat Profile Metadata
    // -------------------------------------------------------------------------

    private fun parseIntMap(serialized: String?): Map<Long, Int> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, Int>()
        serialized.split(";").forEach { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val order = parts[1].trim().toIntOrNull()
                if (id != null && id > 0L && order != null) {
                    result[id] = order
                }
            }
        }
        return result
    }

    private fun serializeIntMap(map: Map<Long, Int>): String {
        return map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun moveChatImportanceOrder(
        context: Context,
        chatId: Long,
        currentOrderedChatIds: List<Long>,
        moveUp: Boolean
    ): Map<Long, Int> {
        ensureInitialized(context)
        val mutableIds = currentOrderedChatIds.distinct().toMutableList()
        val index = mutableIds.indexOf(chatId)
        if (index == -1) return _chatImportanceOrderMap.value
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in mutableIds.indices) return _chatImportanceOrderMap.value

        val swapped = mutableIds[targetIndex]
        mutableIds[targetIndex] = chatId
        mutableIds[index] = swapped

        val updatedMap = mutableIds.mapIndexed { idx, id -> id to idx }.toMap()
        _chatImportanceOrderMap.value = updatedMap
        prefs(context).edit()
            .putString(KEY_CHAT_IMPORTANCE_ORDER_MAP, serializeIntMap(updatedMap))
            .apply()
        return updatedMap
    }

    fun setChatProfileAvatar(context: Context, chatId: Long, avatarPresetOrUri: String?) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val clean = avatarPresetOrUri?.trim()?.takeIf { it.isNotBlank() }
        val updated = _chatAvatarMap.value.toMutableMap().apply {
            if (clean == null) remove(chatId) else put(chatId, clean)
        }.toMap()
        _chatAvatarMap.value = updated
        prefs(context).edit()
            .putString(KEY_CHAT_AVATAR_MAP, serializeCategoryMap(updated))
            .apply()
    }

    fun getChatProfileAvatar(context: Context, chatId: Long): String? {
        ensureInitialized(context)
        return _chatAvatarMap.value[chatId]
    }

    fun setChatInitialPrompt(context: Context, chatId: Long, initialPrompt: String?) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val clean = initialPrompt?.trim()?.takeIf { it.isNotBlank() }
        val updated = _chatInitialPromptMap.value.toMutableMap().apply {
            if (clean == null) remove(chatId) else put(chatId, clean)
        }.toMap()
        _chatInitialPromptMap.value = updated
        prefs(context).edit()
            .putString(KEY_CHAT_INITIAL_PROMPT_MAP, serializeCategoryMap(updated))
            .apply()
    }

    fun getChatInitialPrompt(context: Context, chatId: Long): String? {
        ensureInitialized(context)
        return _chatInitialPromptMap.value[chatId]
    }

    // -------------------------------------------------------------------------
    // Wake Word Action Mappings & Category Wake Words
    // -------------------------------------------------------------------------

    private fun parseWakeWordActionMap(raw: String?): Map<String, WakeWordActionType> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, WakeWordActionType>()
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val phrase = parts[0].trim().lowercase(java.util.Locale.ROOT)
                val action = WakeWordActionType.fromName(parts[1].trim())
                if (phrase.isNotBlank()) {
                    result[phrase] = action
                }
            }
        }
        return result
    }

    private fun serializeWakeWordActionMap(map: Map<String, WakeWordActionType>): String {
        return map.entries
            .filter { it.key.isNotBlank() }
            .joinToString(";;") { "${it.key.trim().lowercase(java.util.Locale.ROOT)}::${it.value.name}" }
    }

    fun setWakeWordAction(
        context: Context,
        phrase: String,
        actionType: WakeWordActionType
    ): Map<String, WakeWordActionType> {
        ensureInitialized(context)
        val key = phrase.trim().lowercase(java.util.Locale.ROOT)
        if (key.isBlank()) return _wakeWordActionMap.value
        val updated = _wakeWordActionMap.value.toMutableMap().apply {
            put(key, actionType)
        }.toMap()
        _wakeWordActionMap.value = updated
        prefs(context).edit()
            .putString(KEY_WAKE_WORD_ACTION_MAP, serializeWakeWordActionMap(updated))
            .apply()
        return updated
    }

    fun getWakeWordAction(context: Context, phrase: String): WakeWordActionType {
        ensureInitialized(context)
        return _wakeWordActionMap.value[phrase.trim().lowercase(java.util.Locale.ROOT)]
            ?: WakeWordActionType.OPEN_CHAT
    }

    private fun parseStringKeyValueMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.isNotBlank() && v.isNotBlank()) {
                    result[k] = v
                }
            }
        }
        return result
    }

    private fun serializeStringKeyValueMap(map: Map<String, String>): String {
        return map.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString(";;") { "${it.key.trim()}::${it.value.trim()}" }
    }

    fun setCategoryWakeWord(context: Context, category: String, wakePhrase: String?): Map<String, String> {
        ensureInitialized(context)
        val cleanCategory = category.trim()
        if (cleanCategory.isBlank()) return _categoryWakeWordMap.value
        val cleanPhrase = wakePhrase?.trim()?.takeIf { it.isNotBlank() }
        val updated = _categoryWakeWordMap.value.toMutableMap().apply {
            if (cleanPhrase == null) remove(cleanCategory) else put(cleanCategory, cleanPhrase)
        }.toMap()
        _categoryWakeWordMap.value = updated
        prefs(context).edit()
            .putString(KEY_CATEGORY_WAKE_WORD_MAP, serializeStringKeyValueMap(updated))
            .apply()
        return updated
    }

    // -------------------------------------------------------------------------
    // Adventure Engine Multiplayer Presence & "Always Offline" (Stealth Mode)
    // -------------------------------------------------------------------------

    private fun parseStringSetCsv(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun isStealthModeEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _isStealthMode.value
    }

    /**
     * Persists the "Always Offline" ([isStealthMode]) privacy toggle in [SharedPreferences].
     * When `true`, [com.example.game.PlayerPresenceCoordinator] pushes `isOnline = false` and
     * `currentActiveChatTitle = "Offline"` to Firestore (`presence/{uid}`).
     */
    fun setStealthModeEnabled(context: Context, enabled: Boolean): Boolean {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_ADVENTURE_STEALTH_MODE, enabled).apply()
        _isStealthMode.value = enabled
        return enabled
    }

    fun toggleStealthMode(context: Context): Boolean {
        ensureInitialized(context)
        return setStealthModeEnabled(context, !_isStealthMode.value)
    }

    fun addPinnedFriendUid(context: Context, odinUid: String): Set<String> {
        ensureInitialized(context)
        val clean = odinUid.trim()
        if (clean.isBlank()) return _pinnedFriendUids.value
        val updated = _pinnedFriendUids.value + clean
        _pinnedFriendUids.value = updated
        prefs(context).edit()
            .putString(KEY_PINNED_FRIEND_UIDS, updated.joinToString(","))
            .apply()
        return updated
    }

    fun removePinnedFriendUid(context: Context, odinUid: String): Set<String> {
        ensureInitialized(context)
        val clean = odinUid.trim()
        val updated = _pinnedFriendUids.value - clean
        _pinnedFriendUids.value = updated
        prefs(context).edit()
            .putString(KEY_PINNED_FRIEND_UIDS, updated.joinToString(","))
            .apply()
        return updated
    }

    fun getOrCreateLocalAdventurerUid(context: Context): String {
        ensureInitialized(context)
        val sp = prefs(context)
        val existing = sp.getString(KEY_LOCAL_ADVENTURER_UID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val generated = "odin_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        sp.edit().putString(KEY_LOCAL_ADVENTURER_UID, generated).apply()
        return generated
    }
}

/**
 * 4-tier per-chat priority level (`Critical`, `High`, `Normal`, `Low`) weighted with
 * contextual signals (location, time of day, and recent activity) when a general wake word is used.
 */
enum class ChatPriorityLevel(
    val weight: Int,
    val displayLabel: String,
    val shortBadge: String
) {
    CRITICAL(weight = 40, displayLabel = "Critical Priority", shortBadge = "Critical"),
    HIGH(weight = 25, displayLabel = "High Priority", shortBadge = "High"),
    NORMAL(weight = 10, displayLabel = "Normal Priority", shortBadge = "Normal"),
    LOW(weight = 0, displayLabel = "Low Priority", shortBadge = "Low");

    fun nextCycledTier(): ChatPriorityLevel = when (this) {
        CRITICAL -> HIGH
        HIGH -> NORMAL
        NORMAL -> LOW
        LOW -> CRITICAL
    }

    companion object {
        fun fromName(raw: String?): ChatPriorityLevel {
            if (raw.isNullOrBlank()) return NORMAL
            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.shortBadge.equals(raw.trim(), ignoreCase = true)
            } ?: NORMAL
        }
    }
}

/**
 * Location/place context profile used alongside coarse location (`ACCESS_COARSE_LOCATION`),
 * time of day, and recent activity to rank relevant chats.
 */
enum class ContextLocationProfile(
    val displayLabel: String,
    val preferredCategory: String
) {
    AUTO_DETECT("Auto (Coarse Location + Schedule)", "Work"),
    WORK("Work / Office", "Work"),
    HOME("Home / Family", "Personal"),
    LEARNING("Campus / Study Hub", "Learning"),
    ON_THE_GO("On the Go / Commute", "Personal");

    companion object {
        fun fromName(raw: String?): ContextLocationProfile {
            if (raw.isNullOrBlank()) return AUTO_DETECT
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: AUTO_DETECT
        }
    }
}

/**
 * User-configurable context trigger condition that maps a time-of-day, location profile,
 * or recent usage signal to a specific target chat.
 */
enum class ContextTriggerType(
    val displayLabel: String,
    val shortBadge: String
) {
    WORK_HOURS("Business Hours (Mon–Fri, 9 AM – 5 PM)", "Business Hours"),
    EVENING_RELAX("Evening & Family (6 PM – 11 PM)", "Evening"),
    MORNING_ROUTINE("Morning Routine (5 AM – 11 AM)", "Morning"),
    WEEKEND("Weekend Schedule (Sat–Sun)", "Weekend"),
    LOCATION_WORK("Location: Work / Office", "At Work"),
    LOCATION_HOME("Location: Home / Family", "At Home"),
    LOCATION_LEARNING("Location: Campus / Study Hub", "Campus"),
    RECENTLY_ACTIVE("Recent App Usage (Last Active Chat)", "Recent Usage");

    companion object {
        fun fromName(raw: String?): ContextTriggerType? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

data class ContextChatTriggerRule(
    val contextType: ContextTriggerType,
    val targetChatId: Long,
    val autoOpenDirectly: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * Configurable action executed when a wake word is detected.
 */
enum class WakeWordActionType(
    val displayLabel: String,
    val shortBadge: String
) {
    OPEN_CHAT("Open Linked Chat", "Open Chat"),
    SUMMARIZE_RECENT("Open Chat & Summarize History", "Summarize"),
    START_PROFILE_PROMPT("Open Chat & Run Initial Profile Prompt", "Profile Prompt"),
    TOGGLE_QUIET_MODE("Toggle Quiet Mode", "Quiet Mode"),
    OPEN_SEARCH("Open Cross-Chat Search", "Search Chats");

    companion object {
        fun fromName(raw: String?): WakeWordActionType {
            if (raw.isNullOrBlank()) return OPEN_CHAT
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: OPEN_CHAT
        }
    }
}

data class ResolvedLocationContext(
    val effectiveProfile: ContextLocationProfile,
    val hasCoarseLocationPermission: Boolean,
    val coarseLocationSummary: String?,
    val displaySummary: String
)

/**
 * Snapshot of the dynamic battery life calculation displayed in the Low Power Mode settings menu.
 */
data class BatteryDrainEstimate(
    val batteryPercent: Int,
    val remainingChargeMah: Float,
    val currentDrainMa: Float,
    val activeWakeDrainMa: Float,
    val lowPowerDrainMa: Float,
    val estimatedHoursRemaining: Float,
    val bonusHoursSaved: Float,
    val lowPowerModeEnabled: Boolean,
    val formattedLabel: String
)


