package com.example.voice

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class WakeRoutingSource(val displayLabel: String) {
    SPECIFIC_WAKE_WORD("Custom Wake Word Match"),
    CATEGORY_MATCH("Category Match"),
    CONTEXTUAL_AUTO("Smart Contextual Match"),
    FAVORITES_PROMPT("Favorite Chats Selection"),
    FALLBACK_DEFAULT("Fallback Default Chat")
}

/**
 * Contextual suggestion item scored by time-of-day, workday schedule, recent activity,
 * and favorite status (without requiring location permissions).
 */
data class ContextualChatSuggestion(
    val chat: ChatSession,
    val score: Int,
    val isFavorite: Boolean,
    val contextReason: String,
    val contextBadge: String
)

/**
 * Result of evaluating which chat to open (and what suggestions/favorites/categories to surface)
 * when a wake word is detected.
 */
data class WakeRoutingDecision(
    val targetChat: ChatSession,
    val routingSource: WakeRoutingSource,
    val routingReason: String,
    val matchedWakePhrase: String,
    val shouldPromptFavoritesSheet: Boolean,
    val favoriteChats: List<ChatSession>,
    val contextualSuggestions: List<ContextualChatSuggestion>,
    val fallbackChat: ChatSession,
    val matchedCategory: String? = null,
    val categoryChats: List<ChatSession> = emptyList(),
    val matchedActionType: com.example.util.WakeWordActionType = com.example.util.WakeWordActionType.OPEN_CHAT,
    val shouldAutoOpenDirectly: Boolean = false
)

/**
 * Result of searching across all chat conversation histories by keyword, including a
 * context snippet and character highlight offsets.
 */
data class ChatHistorySearchSnippet(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String,
    val chatCategory: String?,
    val sender: String,
    val timestamp: Long,
    val fullContent: String,
    val snippetText: String,
    val matchStartInSnippet: Int,
    val matchEndInSnippet: Int
)

/**
 * Structured summary of the most recent messages in a chat session when opened via wake word.
 */
data class WakeConversationSummary(
    val chatId: Long,
    val chatTitle: String,
    val matchedWakeWord: String,
    val routingSource: WakeRoutingSource,
    val routingReason: String,
    val headline: String,
    val instantRecapBullets: List<String>,
    val keyTopics: List<String>,
    val aiExpandedSummary: String? = null,
    val isGeneratingAiSummary: Boolean = false,
    val recentMessageCount: Int = 0,
    val generatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Multi-tier wake-word routing engine implementing:
 * 1. **Specific Wake Word Match** -> Opens the chat explicitly associated with the spoken wake word.
 * 2. **Category / Folder & Specific Chat in Category Match** -> When a user specifies a category
 *    (e.g., "Work", "Personal", "Projects", "Research") or a specific chat within a category,
 *    opens that chat directly or prompts the user to pick among the chats in that category if multiple exist.
 * 3. **Contextual Suggestion / Auto-Selection & Favorites Prompt** -> Uses time-of-day, workday vs.
 *    weekend schedule, recent activity, and starred favorite chats (no location permission needed).
 * 4. **Fallback Default Chat** -> Ensures a default/general chat always opens if a wake word is
 *    unrecognized or unlinked.
 * 5. **Wake-Word Conversation Summary Generator** -> Builds an instant local recap of the most
 *    recent messages (with optional AI expansion) when a chat opens via wake word.
 */
object WakeWordRoutingEngine {

    /**
     * Curated common wake word suggestions surfaced as quick-pick preset chips
     * and used as automatic fallback when the user does not provide a custom phrase.
     */
    val COMMON_WAKE_WORD_SUGGESTIONS: List<String> = listOf(
        "Hey Gemini",
        "OK Gemini",
        "Gemini Assistant",
        "Hey Nebula",
        "Wake Gemini",
        "Hey Nova",
        "Computer",
        "Hey Atlas"
    )

    /**
     * Resolves a user-entered custom wake word string or automatically falls back
     * to [preferredSuggestion] (or the primary common suggestion `"Hey Gemini"`) when blank.
     */
    fun resolveWakeWordOrSuggestedFallback(
        inputPhrase: String?,
        preferredSuggestion: String? = null
    ): String {
        val cleaned = inputPhrase?.trim().orEmpty()
        if (cleaned.isNotBlank()) return cleaned
        val fallbackCandidate = preferredSuggestion?.trim().orEmpty()
        return if (fallbackCandidate.isNotBlank()) {
            fallbackCandidate
        } else {
            COMMON_WAKE_WORD_SUGGESTIONS.first()
        }
    }

    /**
     * Returns a concise human-readable label describing the active time-of-day context
     * used to rank wake-word chat suggestions.
     */
    fun getCurrentTimeContextLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWorkday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
        return when {
            isWorkday && hourOfDay in 9..17 -> "Business Hours • Work Context"
            hourOfDay >= 18 || hourOfDay < 5 -> "Evening • Family & Personal Context"
            hourOfDay in 5..11 -> "Morning Routine Context"
            !isWorkday -> "Weekend • Family & Creative Context"
            else -> "Smart Contextual Suggestions"
        }
    }

    private val GENERIC_WAKE_PHRASES = setOf(
        "hey gemini",
        "gemini",
        "ok gemini",
        "hi gemini",
        "wake up",
        "hey assistant"
    )

    private val WORKDAY_KEYWORDS = listOf(
        "work", "work chat", "business", "client", "code", "project", "dev", "engineering",
        "meeting", "sprint", "task", "bug", "architecture", "sql", "kotlin", "review", "office", "study"
    )

    private val MORNING_KEYWORDS = listOf(
        "morning", "brief", "daily", "plan", "schedule", "todo", "news", "agenda", "standup"
    )

    private val EVENING_CREATIVE_KEYWORDS = listOf(
        "family", "family chat", "home", "evening", "dinner", "kids", "parents", "personal",
        "creative", "idea", "brainstorm", "story", "journal", "design", "music", "spark", "hobby"
    )

    private val LEARNING_KEYWORDS = listOf(
        "learning", "learn", "study", "course", "class", "lecture", "research", "math", "science",
        "language", "tutor", "exam", "flashcard", "reading", "book"
    )

    /**
     * Parses spoken chat-switching or chat-opening commands such as:
     * - `"Hey Gemini, switch to Family Chat"`
     * - `"Hey Gemini, open my work chat"`
     * - `"switch to Learning"`
     * - `"open Family Chat"`
     * - `"go to Work Chat"`
     *
     * Matches against active chat titles, assigned categories (`Work`, `Personal`, `Learning`, `Family`),
     * and custom wake words, prioritizing higher [com.example.util.ChatPriorityLevel] when multiple chats
     * share a category.
     */
    fun parseSpokenChatSwitchOrOpenCommand(
        rawTranscript: String,
        chats: List<ChatSession>,
        customWakeWords: Collection<String> = emptyList(),
        chatCategoryMap: Map<Long, String> = emptyMap(),
        chatPriorityMap: Map<Long, com.example.util.ChatPriorityLevel> = emptyMap()
    ): ChatSession? {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty() || rawTranscript.isBlank()) return null

        val actionVerbs = setOf("open", "switch", "launch", "select", "go")
        val wakePrefixes = (customWakeWords.map { it.trim() } + GENERIC_WAKE_PHRASES + COMMON_WAKE_WORD_SUGGESTIONS)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotBlank() && it !in actionVerbs }
            .distinct()
            .sortedByDescending { it.length }

        var stripped = rawTranscript.trim()
        for (prefix in wakePrefixes) {
            val regex = Regex("(?i)^[,\\s.!?-]*(?:(?:hey|ok|okay|hi)\\s+)?${Regex.escape(prefix)}\\b[,:\\s.!?;-]*")
            if (regex.containsMatchIn(stripped)) {
                stripped = stripped.replaceFirst(regex, "").trim()
                break
            }
        }
        val candidateText = stripped.ifBlank { rawTranscript.trim() }.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val switchRegex = Regex(
            "^(?:please\\s+)?(?:switch\\s+to|open(?:\\s+up)?|go\\s+to|launch|jump\\s+to|select)\\s+(?:my\\s+|the\\s+)?(.+?)(?:\\s+chat|\\s+session|\\s+thread|\\s+conversation)?$"
        )
        val match = switchRegex.find(candidateText) ?: return null
        val targetQuery = match.groupValues[1].trim()
            .removeSuffix(" chat")
            .removeSuffix(" session")
            .removeSuffix(" thread")
            .trim()
        if (targetQuery.isBlank() || targetQuery in setOf("home", "settings", "history", "studio", "diagnostics")) {
            return null
        }

        // 1. Exact or substring match against chat title (with or without "chat" suffix)
        val sortedByPriority = activeChats.sortedWith(
            compareByDescending<ChatSession> { (chatPriorityMap[it.id] ?: com.example.util.ChatPriorityLevel.NORMAL).weight }
                .thenByDescending { it.isPinned }
                .thenByDescending { it.lastUpdatedAt }
        )

        sortedByPriority.firstOrNull { chat ->
            val cleanTitle = chat.title.lowercase(Locale.ROOT).trim()
            val titleWithoutChat = cleanTitle.removeSuffix(" chat").trim()
            cleanTitle == targetQuery ||
                titleWithoutChat == targetQuery ||
                cleanTitle == "$targetQuery chat"
        }?.let { return it }

        sortedByPriority.firstOrNull { chat ->
            val cleanTitle = chat.title.lowercase(Locale.ROOT).trim()
            cleanTitle.contains(targetQuery) || targetQuery.contains(cleanTitle.removeSuffix(" chat").trim())
        }?.let { return it }

        // 2. Match against assigned chat category (e.g., "open my work chat" -> category "Work")
        val categoryMatches = sortedByPriority.filter { chat ->
            val cat = chatCategoryMap[chat.id]?.lowercase(Locale.ROOT)?.trim().orEmpty()
            cat.isNotBlank() && (cat == targetQuery || targetQuery.contains(cat))
        }
        if (categoryMatches.isNotEmpty()) {
            return categoryMatches.first()
        }

        // 3. Fuzzy similarity fallback against chat title or wakeWord
        val bestFuzzy = sortedByPriority
            .map { chat ->
                val simTitle = AudioCommandSanitizer.computePhraseSimilarity(targetQuery, chat.title)
                val simShort = AudioCommandSanitizer.computePhraseSimilarity(
                    targetQuery,
                    chat.title.lowercase(Locale.ROOT).removeSuffix(" chat").trim()
                )
                chat to maxOf(simTitle, simShort)
            }
            .maxByOrNull { it.second }
        if (bestFuzzy != null && bestFuzzy.second >= 0.68f) {
            return bestFuzzy.first
        }

        return null
    }

    /**
     * Resolves the best target chat and contextual suggestions when [spokenWakePhrase] is detected.
     *
     * Priority order:
     * 1. Spoken chat-switching / chat-opening command (e.g., `"Hey Gemini, open my work chat"` or `"switch to Family Chat"`).
     * 2. Specific wake word linked to a chat (supports multiple custom wake words per chat).
     * 3. Category / Folder match (or specific chat within a category; weighted by 4-tier priority).
     * 4. Context-aware + Priority-weighted auto-selection (Location, Time of Day, Recent Activity, 4-tier Priority).
     * 5. Fallback default chat.
     */
    fun resolveTargetChatForWakeWord(
        spokenWakePhrase: String,
        chats: List<ChatSession>,
        configuredWakeWords: List<WakeWord>,
        favoriteChatIds: Set<Long> = emptySet(),
        chatCategoryMap: Map<Long, String> = emptyMap(),
        chatTagsMap: Map<Long, Set<String>> = emptyMap(),
        chatPriorityMap: Map<Long, com.example.util.ChatPriorityLevel> = emptyMap(),
        locationProfile: com.example.util.ContextLocationProfile = com.example.util.ContextLocationProfile.AUTO_DETECT,
        contextTriggerRules: Map<com.example.util.ContextTriggerType, com.example.util.ContextChatTriggerRule> = emptyMap(),
        categoryWakeWordMap: Map<String, String> = emptyMap(),
        wakeWordActionMap: Map<String, com.example.util.WakeWordActionType> = emptyMap(),
        spokenContextOrTranscript: String = "",
        contextualRoutingEnabled: Boolean = true,
        promptFavoritesOnGeneralWake: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): WakeRoutingDecision? {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty()) return null

        val fallbackChat = resolveFallbackChat(activeChats, chatPriorityMap)
        val favoriteChats = activeChats.filter { favoriteChatIds.contains(it.id) }
        val suggestions = rankContextualChatSuggestions(
            chats = activeChats,
            favoriteChatIds = favoriteChatIds,
            chatCategoryMap = chatCategoryMap,
            chatPriorityMap = chatPriorityMap,
            locationProfile = locationProfile,
            nowMillis = nowMillis
        )

        val normalizedSpoken = spokenWakePhrase.trim().lowercase(Locale.ROOT)
        val combinedSpoken = "$normalizedSpoken ${spokenContextOrTranscript.trim().lowercase(Locale.ROOT)}".trim()
        val isGenericPhrase = normalizedSpoken.isBlank() || normalizedSpoken in GENERIC_WAKE_PHRASES
        val resolvedActionType = wakeWordActionMap[normalizedSpoken]
            ?: com.example.util.WakeWordActionType.OPEN_CHAT

        // 0. Check for explicit spoken "open my <X> chat" / "switch to <X>" command in phrase or transcript
        val spokenSwitchTarget = parseSpokenChatSwitchOrOpenCommand(
            rawTranscript = if (spokenContextOrTranscript.isNotBlank()) {
                "$spokenWakePhrase $spokenContextOrTranscript"
            } else {
                spokenWakePhrase
            },
            chats = activeChats,
            customWakeWords = configuredWakeWords.map { it.phrase } + categoryWakeWordMap.values,
            chatCategoryMap = chatCategoryMap,
            chatPriorityMap = chatPriorityMap
        )
        if (spokenSwitchTarget != null) {
            return WakeRoutingDecision(
                targetChat = spokenSwitchTarget,
                routingSource = WakeRoutingSource.SPECIFIC_WAKE_WORD,
                routingReason = "Voice switch/open command → ${spokenSwitchTarget.title}",
                matchedWakePhrase = spokenWakePhrase.trim().ifBlank { "Hey Gemini" },
                shouldPromptFavoritesSheet = false,
                favoriteChats = favoriteChats,
                contextualSuggestions = suggestions,
                fallbackChat = fallbackChat,
                matchedActionType = resolvedActionType,
                shouldAutoOpenDirectly = true
            )
        }

        // 1. Check for a specific custom wake word match first (or category-assigned wake word)
        if (normalizedSpoken.isNotBlank()) {
            val matchedConfigured = configuredWakeWords
                .filter { it.isEnabled && !it.isDeleted && it.triggerType == TriggerType.CHAT }
                .firstOrNull { it.phrase.trim().equals(spokenWakePhrase.trim(), ignoreCase = true) }

            val linkedFromConfigured = matchedConfigured?.effectiveTargetChatId?.let { targetId ->
                activeChats.firstOrNull { it.id == targetId }
            } ?: matchedConfigured?.chatTitle?.takeIf { it.isNotBlank() }?.let { title ->
                activeChats.firstOrNull { it.title.equals(title, ignoreCase = true) }
            }

            val linkedFromChatEntity = activeChats.firstOrNull { chat ->
                chat.wakeWord.trim().equals(spokenWakePhrase.trim(), ignoreCase = true)
            }

            val specificChat = linkedFromConfigured ?: linkedFromChatEntity
            if (specificChat != null && (!isGenericPhrase || !contextualRoutingEnabled)) {
                return WakeRoutingDecision(
                    targetChat = specificChat,
                    routingSource = WakeRoutingSource.SPECIFIC_WAKE_WORD,
                    routingReason = "Matched wake word \"${spokenWakePhrase.trim()}\" → ${specificChat.title}",
                    matchedWakePhrase = spokenWakePhrase.trim(),
                    shouldPromptFavoritesSheet = promptFavoritesOnGeneralWake && isGenericPhrase && favoriteChats.size > 1,
                    favoriteChats = favoriteChats,
                    contextualSuggestions = suggestions,
                    fallbackChat = fallbackChat,
                    matchedActionType = resolvedActionType
                )
            }

            // 1b. Check if the spoken wake word is assigned to a Chat Category (e.g. "Hey Gemini" for Work, "Wake Up" for Personal)
            val matchedCategoryEntry = categoryWakeWordMap.entries.firstOrNull { (_, catPhrase) ->
                catPhrase.trim().equals(spokenWakePhrase.trim(), ignoreCase = true)
            }
            if (matchedCategoryEntry != null) {
                val categoryName = matchedCategoryEntry.key
                val chatsInCat = activeChats.filter { chat ->
                    chatCategoryMap[chat.id]?.equals(categoryName, ignoreCase = true) == true
                }.sortedWith(
                    compareByDescending<ChatSession> { (chatPriorityMap[it.id] ?: com.example.util.ChatPriorityLevel.NORMAL).weight }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.lastUpdatedAt }
                )
                if (chatsInCat.isNotEmpty()) {
                    val chosenCatChat = chatsInCat.first()
                    return WakeRoutingDecision(
                        targetChat = chosenCatChat,
                        routingSource = WakeRoutingSource.CATEGORY_MATCH,
                        routingReason = "Category wake word \"${spokenWakePhrase.trim()}\" ($categoryName) → ${chosenCatChat.title}",
                        matchedWakePhrase = spokenWakePhrase.trim(),
                        shouldPromptFavoritesSheet = chatsInCat.size > 1,
                        favoriteChats = favoriteChats,
                        contextualSuggestions = suggestions,
                        fallbackChat = fallbackChat,
                        matchedCategory = categoryName,
                        categoryChats = chatsInCat,
                        matchedActionType = resolvedActionType
                    )
                }
            }
        }

        // 2. Check for Category / Folder or Tag specification in spoken phrase or trailing utterance
        if (combinedSpoken.isNotBlank() && chatCategoryMap.isNotEmpty()) {
            val distinctCategories = chatCategoryMap.values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val matchedCategoryName = distinctCategories.firstOrNull { cat ->
                val catLower = cat.lowercase(Locale.ROOT)
                Regex("\\b${Regex.escape(catLower)}\\b").containsMatchIn(combinedSpoken)
            }
            if (matchedCategoryName != null) {
                val chatsInCategory = activeChats.filter { chat ->
                    chatCategoryMap[chat.id]?.equals(matchedCategoryName, ignoreCase = true) == true
                }
                if (chatsInCategory.isNotEmpty()) {
                    // Check if the user also named a specific chat inside that category
                    val namedChatInCat = chatsInCategory.firstOrNull { chat ->
                        combinedSpoken.contains(chat.title.trim().lowercase(Locale.ROOT)) ||
                            combinedSpoken.contains(chat.wakeWord.trim().lowercase(Locale.ROOT))
                    }
                    val chosenTarget = namedChatInCat
                        ?: suggestions.firstOrNull { s -> chatsInCategory.any { it.id == s.chat.id } }?.chat
                        ?: chatsInCategory.first()
                    val promptDisambiguation = namedChatInCat == null && chatsInCategory.size > 1

                    return WakeRoutingDecision(
                        targetChat = chosenTarget,
                        routingSource = WakeRoutingSource.CATEGORY_MATCH,
                        routingReason = if (namedChatInCat != null) {
                            "Category \"$matchedCategoryName\" → ${namedChatInCat.title}"
                        } else if (chatsInCategory.size > 1) {
                            "Category \"$matchedCategoryName\" (${chatsInCategory.size} chats) • Choose or continue in ${chosenTarget.title}"
                        } else {
                            "Category \"$matchedCategoryName\" → ${chosenTarget.title}"
                        },
                        matchedWakePhrase = spokenWakePhrase.ifBlank { matchedCategoryName },
                        shouldPromptFavoritesSheet = promptDisambiguation,
                        favoriteChats = favoriteChats,
                        contextualSuggestions = suggestions,
                        fallbackChat = fallbackChat,
                        matchedCategory = matchedCategoryName,
                        categoryChats = chatsInCategory
                    )
                }
            }
        }

        // 3. If user enabled Favorites prompt and has favorite chats, prompt or route to top favorite
        if (promptFavoritesOnGeneralWake && favoriteChats.isNotEmpty()) {
            val primaryFav = suggestions.firstOrNull { it.isFavorite }?.chat ?: favoriteChats.first()
            return WakeRoutingDecision(
                targetChat = primaryFav,
                routingSource = WakeRoutingSource.FAVORITES_PROMPT,
                routingReason = "Choose from your ${favoriteChats.size} favorite chat(s) or contextual suggestions",
                matchedWakePhrase = spokenWakePhrase.ifBlank { "Hey Gemini" },
                shouldPromptFavoritesSheet = true,
                favoriteChats = favoriteChats,
                contextualSuggestions = suggestions,
                fallbackChat = fallbackChat
            )
        }

        // 4. Check user-configured Context-to-Chat Trigger Rules first, then Contextual Auto-Selection
        if (contextualRoutingEnabled) {
            val configuredContextMatch = com.example.util.AppSettingsManager.resolveConfiguredContextTriggerMatch(
                chats = activeChats,
                rules = contextTriggerRules,
                locationProfile = locationProfile,
                nowMillis = nowMillis
            )
            if (configuredContextMatch != null) {
                val (rule, ruleChat) = configuredContextMatch
                return WakeRoutingDecision(
                    targetChat = ruleChat,
                    routingSource = WakeRoutingSource.CONTEXTUAL_AUTO,
                    routingReason = "Context Rule (${rule.contextType.shortBadge}) → ${ruleChat.title}",
                    matchedWakePhrase = spokenWakePhrase.ifBlank { ruleChat.wakeWord },
                    shouldPromptFavoritesSheet = false,
                    favoriteChats = favoriteChats,
                    contextualSuggestions = suggestions,
                    fallbackChat = fallbackChat,
                    matchedActionType = resolvedActionType,
                    shouldAutoOpenDirectly = rule.autoOpenDirectly
                )
            }

            if (suggestions.isNotEmpty()) {
                val topSuggestion = suggestions.first()
                if (topSuggestion.score > 20) {
                    return WakeRoutingDecision(
                        targetChat = topSuggestion.chat,
                        routingSource = WakeRoutingSource.CONTEXTUAL_AUTO,
                        routingReason = "${topSuggestion.contextBadge}: ${topSuggestion.contextReason}",
                        matchedWakePhrase = spokenWakePhrase.ifBlank { topSuggestion.chat.wakeWord },
                        shouldPromptFavoritesSheet = false,
                        favoriteChats = favoriteChats,
                        contextualSuggestions = suggestions,
                        fallbackChat = fallbackChat,
                        matchedActionType = resolvedActionType
                    )
                }
            }
        }

        // 5. Fallback Default Chat
        return WakeRoutingDecision(
            targetChat = fallbackChat,
            routingSource = WakeRoutingSource.FALLBACK_DEFAULT,
            routingReason = "Fallback chat opened (unrecognized or general wake trigger)",
            matchedWakePhrase = spokenWakePhrase.ifBlank { fallbackChat.wakeWord },
            shouldPromptFavoritesSheet = false,
            favoriteChats = favoriteChats,
            contextualSuggestions = suggestions,
            fallbackChat = fallbackChat
        )
    }

    /**
     * Resolves the designated fallback chat (`isDefaultWakeChat == true`, highest priority chat, or first available chat).
     */
    fun resolveFallbackChat(
        chats: List<ChatSession>,
        chatPriorityMap: Map<Long, com.example.util.ChatPriorityLevel> = emptyMap()
    ): ChatSession {
        val active = chats.filter { !it.isDeleted }
        val highestPriority = active
            .filter { (chatPriorityMap[it.id]?.weight ?: 0) >= com.example.util.ChatPriorityLevel.HIGH.weight }
            .maxByOrNull { chatPriorityMap[it.id]?.weight ?: 0 }
        return active.firstOrNull { it.isDefaultWakeChat }
            ?: highestPriority
            ?: active.firstOrNull()
            ?: ChatSession(
                id = 1L,
                title = "General Gemini",
                wakeWord = "Hey Gemini",
                isDefaultWakeChat = true
            )
    }

    /**
     * Ranks all active chats by contextual relevance using:
     * - 4-Tier Per-Chat Priority Level (`Critical +40`, `High +25`, `Normal +10`, `Low +0`)
     * - Location / Place Profile (`WORK`, `HOME`, `LEARNING`, `ON_THE_GO`)
     * - Time of Day (Morning 5–11, Business Hours / Workday Focus Mon–Fri 9–17, Evening/Family/Creative 18–23)
     * - Workday vs. Weekend schedule & assigned category (`Work`, `Personal`, `Learning`, `Family`, `Projects`)
     * - Pinned status (`chat.isPinned`) & Starred Favorite status (`favoriteChatIds`)
     * - Recent conversation activity (`lastUpdatedAt` within 2 hours or 24 hours)
     */
    fun rankContextualChatSuggestions(
        chats: List<ChatSession>,
        favoriteChatIds: Set<Long> = emptySet(),
        chatCategoryMap: Map<Long, String> = emptyMap(),
        chatPriorityMap: Map<Long, com.example.util.ChatPriorityLevel> = emptyMap(),
        locationProfile: com.example.util.ContextLocationProfile = com.example.util.ContextLocationProfile.AUTO_DETECT,
        nowMillis: Long = System.currentTimeMillis()
    ): List<ContextualChatSuggestion> {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty()) return emptyList()

        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWorkday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
        val isWorkHours = isWorkday && hourOfDay in 9..17
        val isMorning = hourOfDay in 5..11
        val isEvening = hourOfDay >= 18 || hourOfDay < 5

        val mostRecentChatId = activeChats.maxByOrNull { it.lastUpdatedAt }?.id

        return activeChats.map { chat ->
            val assignedCategory = chatCategoryMap[chat.id].orEmpty().trim()
            val priorityLevel = chatPriorityMap[chat.id] ?: com.example.util.ChatPriorityLevel.NORMAL
            val searchable = "${chat.title} ${chat.wakeWord} ${chat.systemInstruction} $assignedCategory".lowercase(Locale.ROOT)
            val isFav = favoriteChatIds.contains(chat.id)
            var score = 0
            val reasons = mutableListOf<String>()
            var badge = "Suggested"

            // 1. 4-Tier Per-Chat Priority Level weighting
            if (priorityLevel != com.example.util.ChatPriorityLevel.LOW) {
                score += priorityLevel.weight
                if (priorityLevel == com.example.util.ChatPriorityLevel.CRITICAL ||
                    priorityLevel == com.example.util.ChatPriorityLevel.HIGH
                ) {
                    badge = priorityLevel.displayLabel
                    reasons.add(priorityLevel.displayLabel)
                }
            }

            // 2. Location / Place Profile Context match
            val isWorkCategory = assignedCategory.equals("Work", ignoreCase = true) ||
                assignedCategory.equals("Projects", ignoreCase = true)
            val isLearningCategory = assignedCategory.equals("Learning", ignoreCase = true) ||
                assignedCategory.equals("Research", ignoreCase = true)
            val isFamilyOrPersonalCategory = assignedCategory.equals("Family", ignoreCase = true) ||
                assignedCategory.equals("Personal", ignoreCase = true)

            when (locationProfile) {
                com.example.util.ContextLocationProfile.WORK -> {
                    if (isWorkCategory || WORKDAY_KEYWORDS.any { searchable.contains(it) }) {
                        score += 36
                        badge = "At Work • Work Chat"
                        reasons.add("Matches Work location context")
                    }
                }
                com.example.util.ContextLocationProfile.HOME -> {
                    if (isFamilyOrPersonalCategory || EVENING_CREATIVE_KEYWORDS.any { searchable.contains(it) }) {
                        score += 36
                        badge = "At Home • Family & Personal"
                        reasons.add("Matches Home location context")
                    }
                }
                com.example.util.ContextLocationProfile.LEARNING -> {
                    if (isLearningCategory || LEARNING_KEYWORDS.any { searchable.contains(it) } || chat.iconName.equals("Book", ignoreCase = true)) {
                        score += 36
                        badge = "Campus • Learning"
                        reasons.add("Matches Learning location context")
                    }
                }
                else -> Unit
            }

            // 3. Business Hours / Workday Focus match (e.g. "Work Chat", "Work Projects", "Code Mode")
            if (isWorkHours && (WORKDAY_KEYWORDS.any { searchable.contains(it) } || isWorkCategory || chat.iconName.equals("Code", ignoreCase = true))) {
                score += 48
                badge = "Business Hours • Work"
                reasons.add("Relevant for business hours")
            }

            // 4. Learning / Study category match
            if (isLearningCategory || LEARNING_KEYWORDS.any { searchable.contains(it) } || chat.iconName.equals("Book", ignoreCase = true)) {
                score += 22
                if (badge == "Suggested") badge = "Learning & Study"
                reasons.add("Learning category")
            }

            // 5. Morning Briefing match
            if (isMorning && MORNING_KEYWORDS.any { searchable.contains(it) }) {
                score += 40
                if (badge == "Suggested") badge = "Morning Routine"
                reasons.add("Matches morning schedule")
            }

            // 6. Evening / Family / Personal / Weekend match (e.g. "Family Chat", "Evening", "Creative")
            if ((isEvening || !isWorkday) && (EVENING_CREATIVE_KEYWORDS.any { searchable.contains(it) } || isFamilyOrPersonalCategory || chat.iconName.equals("Lightbulb", ignoreCase = true))) {
                score += 46
                val isFamilyMatch = searchable.contains("family") || searchable.contains("home") || searchable.contains("dinner") || assignedCategory.equals("Family", ignoreCase = true)
                badge = when {
                    isFamilyMatch && isEvening -> "Evening • Family Chat"
                    isFamilyMatch -> "Family & Home"
                    !isWorkday -> "Weekend Creative"
                    else -> "Evening Focus"
                }
                reasons.add(
                    if (isFamilyMatch) "Relevant for evening & family time"
                    else if (!isWorkday) "Great for weekend brainstorming"
                    else "Matches evening hours"
                )
            }

            // 7. Pinned chat boost
            if (chat.isPinned) {
                score += 28
                if (badge == "Suggested") badge = "Pinned Chat"
                reasons.add("Pinned session")
            }

            // 8. Recent conversation activity
            val ageMinutes = ((nowMillis - chat.lastUpdatedAt).coerceAtLeast(0L)) / 60_000L
            when {
                chat.id == mostRecentChatId && ageMinutes <= 120L -> {
                    score += 30
                    if (badge == "Suggested") badge = "Recent Activity"
                    reasons.add("Active ${ageMinutes.coerceAtLeast(1)}m ago")
                }
                ageMinutes <= 24L * 60L -> {
                    score += 15
                    reasons.add("Used today")
                }
            }

            // 9. Favorite boost
            if (isFav) {
                score += 25
                if (badge == "Suggested") badge = "Favorite"
                reasons.add("Starred favorite")
            }

            // 10. Voice command usage boost
            if (chat.totalVoiceCommands > 0) {
                score += (chat.totalVoiceCommands * 3).coerceAtMost(15)
            }

            // 11. Default wake chat baseline
            if (chat.isDefaultWakeChat) {
                score += 12
                if (reasons.isEmpty()) {
                    badge = "Pre-Selected Default"
                    reasons.add("Default wake-word chat")
                }
            }

            val reasonSummary = if (reasons.isNotEmpty()) {
                reasons.distinct().joinToString(" • ")
            } else {
                "General conversation"
            }

            ContextualChatSuggestion(
                chat = chat,
                score = score,
                isFavorite = isFav,
                contextReason = reasonSummary,
                contextBadge = badge
            )
        }.sortedWith(
            compareByDescending<ContextualChatSuggestion> { it.score }
                .thenByDescending { (chatPriorityMap[it.chat.id] ?: com.example.util.ChatPriorityLevel.NORMAL).weight }
                .thenByDescending { it.chat.isPinned }
                .thenByDescending { it.isFavorite }
                .thenByDescending { it.chat.lastUpdatedAt }
        )
    }

    /**
     * Generates an instant local summary of the most recent messages in [chat] when opened
     * via a wake word so the user can immediately grasp the conversation context without scrolling.
     */
    fun buildInstantWakeConversationSummary(
        chat: ChatSession,
        messages: List<ChatMessage>,
        matchedWakeWord: String = chat.wakeWord,
        routingSource: WakeRoutingSource = WakeRoutingSource.SPECIFIC_WAKE_WORD,
        routingReason: String = "Opened via wake word \"$matchedWakeWord\""
    ): WakeConversationSummary {
        val validMessages = messages
            .filter { !it.isDeleted && it.hasPersistableContent }
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

        val recentSlice = validMessages.takeLast(6)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        val bullets = if (recentSlice.isEmpty()) {
            listOf(
                "No prior messages in ${chat.title} yet.",
                "Speak your prompt or tap the microphone to start this session."
            )
        } else {
            val lastUserMsg = recentSlice.lastOrNull { it.sender.equals("user", ignoreCase = true) }
            val lastGeminiMsg = recentSlice.lastOrNull { !it.sender.equals("user", ignoreCase = true) }
            val earliestInSlice = recentSlice.first()

            buildList {
                if (lastUserMsg != null) {
                    val snippet = lastUserMsg.content.replace("\n", " ").trim().take(110)
                    val ellipsis = if (lastUserMsg.content.length > 110) "…" else ""
                    add("Last You (${timeFormat.format(Date(lastUserMsg.timestamp))}): \"$snippet$ellipsis\"")
                }
                if (lastGeminiMsg != null) {
                    val snippet = lastGeminiMsg.content.replace("\n", " ").trim().take(130)
                    val ellipsis = if (lastGeminiMsg.content.length > 130) "…" else ""
                    add("Last Gemini (${timeFormat.format(Date(lastGeminiMsg.timestamp))}): \"$snippet$ellipsis\"")
                }
                if (recentSlice.size > 2) {
                    val wakeCount = recentSlice.count { it.triggeredByWakeWord }
                    add("Recent context: ${recentSlice.size} recent turns (${wakeCount} voice-triggered) since ${timeFormat.format(Date(earliestInSlice.timestamp))}.")
                }
            }
        }

        val topics = extractKeyTopics(recentSlice, chat.title)
        val headline = if (validMessages.isEmpty()) {
            "Fresh conversation • Ready for \"${matchedWakeWord.ifBlank { chat.wakeWord }}\""
        } else {
            "Recap of last ${recentSlice.size} of ${validMessages.size} message(s) in ${chat.title}"
        }

        return WakeConversationSummary(
            chatId = chat.id,
            chatTitle = chat.title,
            matchedWakeWord = matchedWakeWord.ifBlank { chat.wakeWord },
            routingSource = routingSource,
            routingReason = routingReason,
            headline = headline,
            instantRecapBullets = bullets,
            keyTopics = topics,
            recentMessageCount = validMessages.size
        )
    }

    private fun extractKeyTopics(messages: List<ChatMessage>, fallbackTitle: String): List<String> {
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "your", "what",
            "when", "where", "which", "would", "could", "should", "about", "into", "just",
            "like", "some", "more", "than", "then", "them", "they", "were", "been", "will",
            "hello", "gemini", "please", "help", "can", "you", "are", "how", "make", "tell"
        )
        val counts = mutableMapOf<String, Int>()
        messages.forEach { msg ->
            msg.content
                .lowercase(Locale.ROOT)
                .split("[^a-z0-9]+".toRegex())
                .filter { it.length in 4..18 && it !in stopWords }
                .forEach { word ->
                    counts[word] = (counts[word] ?: 0) + 1
                }
        }
        val topWords = counts.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { entry -> entry.key.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        return if (topWords.isNotEmpty()) topWords else listOf(fallbackTitle)
    }

    /**
     * Searches across all non-deleted chat messages for [rawQuery] using case-insensitive keyword
     * matching and returns matching [ChatHistorySearchSnippet] items with context snippets and
     * character highlight ranges.
     */
    fun searchAcrossChatHistories(
        rawQuery: String,
        chats: List<ChatSession>,
        messages: List<ChatMessage>,
        chatCategoryMap: Map<Long, String> = emptyMap(),
        maxResults: Int = 40
    ): List<ChatHistorySearchSnippet> {
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()

        val chatById = chats.filter { !it.isDeleted }.associateBy { it.id }
        val lowerQuery = query.lowercase(Locale.ROOT)

        return messages
            .asSequence()
            .filter { !it.isDeleted && it.hasPersistableContent && chatById.containsKey(it.chatId) }
            .mapNotNull { msg ->
                val cleanContent = msg.content.replace('\n', ' ').trim()
                val matchIndex = cleanContent.lowercase(Locale.ROOT).indexOf(lowerQuery)
                if (matchIndex < 0) return@mapNotNull null

                val parentChat = chatById[msg.chatId] ?: return@mapNotNull null
                val radius = 52
                val windowStart = (matchIndex - radius).coerceAtLeast(0)
                val windowEnd = (matchIndex + query.length + radius).coerceAtMost(cleanContent.length)
                val prefixEllipsis = if (windowStart > 0) "…" else ""
                val suffixEllipsis = if (windowEnd < cleanContent.length) "…" else ""
                val snippetBody = cleanContent.substring(windowStart, windowEnd)
                val snippetText = "$prefixEllipsis$snippetBody$suffixEllipsis"
                val highlightStart = prefixEllipsis.length + (matchIndex - windowStart)
                val highlightEnd = (highlightStart + query.length).coerceAtMost(snippetText.length)

                ChatHistorySearchSnippet(
                    messageId = msg.id,
                    chatId = msg.chatId,
                    chatTitle = parentChat.title,
                    chatCategory = chatCategoryMap[msg.chatId],
                    sender = msg.sender,
                    timestamp = msg.timestamp,
                    fullContent = msg.content,
                    snippetText = snippetText,
                    matchStartInSnippet = highlightStart,
                    matchEndInSnippet = highlightEnd
                )
            }
            .sortedByDescending { it.timestamp }
            .take(maxResults)
            .toList()
    }
}
