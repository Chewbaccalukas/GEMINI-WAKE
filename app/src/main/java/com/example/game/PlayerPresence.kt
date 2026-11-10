package com.example.game

import android.content.Context
import android.util.Log
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.db.AppDatabase
import com.example.util.AppSettingsManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Multiplayer Presence & Friend Status data model (`com.example.game.PlayerPresence`) for
 * Gemini's Infinite Adventure Engine.
 *
 * Syncs local RPG progression ([level], [completedQuestCount]) and active conversation context
 * ([currentActiveChatTitle]) with both Room (`player_presence_cache`) and Cloud Firestore
 * (`presence/{uid}`).
 *
 * When [isStealthMode] ("Always Offline") is `true`, [enforceStealthPrivacy] forces
 * `isOnline = false`, `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L`
 * before persisting or broadcasting to Firestore.
 */
@Entity(
    tableName = "player_presence_cache",
    indices = [
        Index(value = ["lastHeartbeatEpochMs"]),
        Index(value = ["isOnline"])
    ]
)
data class PlayerPresence(
    @PrimaryKey
    val odinUid: String,
    val displayName: String = "Adventurer",
    val level: Int = 1,
    val completedQuestCount: Int = 0,
    val currentActiveChatTitle: String = "General Gemini",
    val isOnline: Boolean = true,
    val isStealthMode: Boolean = false,
    val sessionStartedEpochMs: Long = System.currentTimeMillis(),
    val lastHeartbeatEpochMs: Long = System.currentTimeMillis()
) {
    /**
     * Returns a privacy-hardened copy of this [PlayerPresence].
     * When [isStealthMode] is `true`, `isOnline` is forced to `false`,
     * `currentActiveChatTitle` is forced to `"Offline"`, and `sessionStartedEpochMs` is zeroed
     * so live session timers and chat titles are never exposed.
     */
    fun enforceStealthPrivacy(): PlayerPresence {
        return if (isStealthMode) {
            copy(
                level = level.coerceAtLeast(1),
                completedQuestCount = completedQuestCount.coerceAtLeast(0),
                isOnline = false,
                currentActiveChatTitle = OFFLINE_CHAT_TITLE,
                sessionStartedEpochMs = 0L
            )
        } else if (!isOnline) {
            copy(
                level = level.coerceAtLeast(1),
                completedQuestCount = completedQuestCount.coerceAtLeast(0),
                currentActiveChatTitle = OFFLINE_CHAT_TITLE,
                sessionStartedEpochMs = 0L
            )
        } else {
            copy(
                level = level.coerceAtLeast(1),
                completedQuestCount = completedQuestCount.coerceAtLeast(0),
                currentActiveChatTitle = currentActiveChatTitle.trim().ifBlank { "General Gemini" }
            )
        }
    }

    /**
     * Evaluates whether this player is currently online, accounting for [isStealthMode] and
     * the 2-minute ([PRESENCE_OFFLINE_TIMEOUT_MS]) heartbeat timeout window.
     */
    fun isEffectivelyOnline(
        nowEpochMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = PRESENCE_OFFLINE_TIMEOUT_MS
    ): Boolean {
        if (!isOnline || isStealthMode) return false
        if (lastHeartbeatEpochMs <= 0L) return false
        val ageMs = (nowEpochMs - lastHeartbeatEpochMs).coerceAtLeast(0L)
        return ageMs <= timeoutMs
    }

    /**
     * Returns the privacy-safe active chat context string (`"In: <Chat Title>"` when online,
     * or `"In: Offline"` when offline or in Stealth Mode).
     */
    fun formattedChatContext(nowEpochMs: Long = System.currentTimeMillis()): String {
        return if (isEffectivelyOnline(nowEpochMs)) {
            val cleanTitle = currentActiveChatTitle.trim().ifBlank { "General Gemini" }
            "In: $cleanTitle"
        } else {
            "In: $OFFLINE_CHAT_TITLE"
        }
    }

    /**
     * Formats the elapsed session duration (`"Online for Xh Ym"` or `"Online for Xm"`) when
     * effectively online, or `"Offline"` when offline or in Stealth Mode.
     */
    fun formattedElapsedSessionTime(nowEpochMs: Long = System.currentTimeMillis()): String {
        if (!isEffectivelyOnline(nowEpochMs) || sessionStartedEpochMs <= 0L) {
            return if (isStealthMode) "Always Offline (Stealth)" else OFFLINE_CHAT_TITLE
        }
        val elapsedMs = (nowEpochMs - sessionStartedEpochMs).coerceAtLeast(0L)
        val totalMinutes = (elapsedMs / 60_000L).toInt()
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (hours > 0) {
            "Online for ${hours}h ${mins}m"
        } else {
            "Online for ${mins.coerceAtLeast(1)}m"
        }
    }

    /**
     * Converts this [PlayerPresence] (with [enforceStealthPrivacy] applied) to a Firestore document map
     * suitable for writing to `presence/{uid}`.
     */
    fun toFirestoreMap(includeCreatedAt: Boolean = false): Map<String, Any> {
        val sanitized = enforceStealthPrivacy()
        return buildMap {
            put("odinUid", sanitized.odinUid)
            put("displayName", sanitized.displayName.trim().ifBlank { "Adventurer" }.take(120))
            put("level", sanitized.level.coerceAtLeast(1))
            put("completedQuestCount", sanitized.completedQuestCount.coerceAtLeast(0))
            put("currentActiveChatTitle", sanitized.currentActiveChatTitle.take(200))
            put("isOnline", sanitized.isOnline)
            put("isStealthMode", sanitized.isStealthMode)
            put("sessionStartedEpochMs", sanitized.sessionStartedEpochMs.coerceAtLeast(0L))
            put("lastHeartbeatEpochMs", sanitized.lastHeartbeatEpochMs.coerceAtLeast(0L))
            if (includeCreatedAt) {
                put("createdAt", FieldValue.serverTimestamp())
            }
            put("updatedAt", FieldValue.serverTimestamp())
        }
    }

    companion object {
        const val COLLECTION_PRESENCE = "presence"
        const val OFFLINE_CHAT_TITLE = "Offline"
        const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 seconds
        const val PRESENCE_OFFLINE_TIMEOUT_MS = 120_000L // 2 minutes

        fun fromFirestoreMap(uid: String, data: Map<String, Any?>): PlayerPresence {
            val resolvedUid = (data["odinUid"] as? String)?.trim()?.ifBlank { uid } ?: uid
            val rawName = (data["displayName"] as? String)?.trim().orEmpty().ifBlank { "Adventurer" }
            val rawLevel = (data["level"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            val rawQuests = (data["completedQuestCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            val rawStealth = (data["isStealthMode"] as? Boolean) ?: false
            val rawOnline = if (rawStealth) false else ((data["isOnline"] as? Boolean) ?: false)
            val rawTitle = if (rawStealth || !rawOnline) {
                OFFLINE_CHAT_TITLE
            } else {
                (data["currentActiveChatTitle"] as? String)?.trim().orEmpty().ifBlank { "General Gemini" }
            }
            val rawSessionStart = if (rawStealth || !rawOnline) {
                0L
            } else {
                (data["sessionStartedEpochMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            }
            val rawHeartbeat = (data["lastHeartbeatEpochMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L

            return PlayerPresence(
                odinUid = resolvedUid,
                displayName = rawName,
                level = rawLevel,
                completedQuestCount = rawQuests,
                currentActiveChatTitle = rawTitle,
                isOnline = rawOnline,
                isStealthMode = rawStealth,
                sessionStartedEpochMs = rawSessionStart,
                lastHeartbeatEpochMs = rawHeartbeat
            ).enforceStealthPrivacy()
        }
    }
}

/**
 * Coordinates local Room caching (`player_presence_cache`) and real-time Cloud Firestore
 * (`presence/{uid}`) synchronization, 30-second heartbeats, 2-minute offline detection, and
 * "Always Offline" Stealth Mode privacy enforcement.
 */
object PlayerPresenceCoordinator {
    private const val TAG = "PlayerPresenceCoord"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSessionStartedEpochMs: Long = System.currentTimeMillis()

    private val _localPlayerPresence = MutableStateFlow(
        PlayerPresence(
            odinUid = "local_adventurer",
            displayName = "Aether Vanguard",
            level = 1,
            completedQuestCount = 1,
            currentActiveChatTitle = "General Gemini",
            isOnline = true,
            isStealthMode = false,
            sessionStartedEpochMs = currentSessionStartedEpochMs,
            lastHeartbeatEpochMs = currentSessionStartedEpochMs
        )
    )
    val localPlayerPresence: StateFlow<PlayerPresence> = _localPlayerPresence.asStateFlow()

    private val _partyPresenceList = MutableStateFlow<List<PlayerPresence>>(emptyList())
    val partyPresenceList: StateFlow<List<PlayerPresence>> = _partyPresenceList.asStateFlow()

    private val _lastSyncedFirestorePayload = MutableStateFlow<Map<String, Any>?>(null)
    val lastSyncedFirestorePayload: StateFlow<Map<String, Any>?> = _lastSyncedFirestorePayload.asStateFlow()

    private var heartbeatJob: Job? = null
    private var firestoreListener: ListenerRegistration? = null

    /**
     * Resolves the current authenticated Firebase UID or a deterministic local adventurer ID.
     */
    fun resolveCurrentOdinUid(context: Context? = null): String {
        val firebaseUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (firebaseUid != null) return firebaseUid
        return if (context != null) {
            AppSettingsManager.getOrCreateLocalAdventurerUid(context)
        } else {
            "local_adventurer"
        }
    }

    /**
     * Resolves the display name from Firebase Auth or falls back to the Adventure character name.
     */
    fun resolveDisplayName(characterName: String): String {
        val authName = runCatching { FirebaseAuth.getInstance().currentUser?.displayName }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return authName ?: characterName.trim().ifBlank { "Aether Vanguard" }
    }

    /**
     * Builds the privacy-enforced [PlayerPresence] snapshot for the local player.
     * When [isStealthMode] is `true`, forces `isOnline = false`,
     * `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L`.
     */
    fun buildLocalPresenceSnapshot(
        odinUid: String,
        displayName: String,
        level: Int,
        completedQuestCount: Int,
        currentActiveChatTitle: String,
        isOnline: Boolean,
        isStealthMode: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PlayerPresence {
        if (isOnline && !isStealthMode && currentSessionStartedEpochMs <= 0L) {
            currentSessionStartedEpochMs = nowEpochMs
        } else if (!isOnline || isStealthMode) {
            currentSessionStartedEpochMs = 0L
        }
        val sessionStart = if (isOnline && !isStealthMode) {
            currentSessionStartedEpochMs.takeIf { it > 0L } ?: nowEpochMs.also { currentSessionStartedEpochMs = it }
        } else {
            0L
        }

        return PlayerPresence(
            odinUid = odinUid.trim().ifBlank { "local_adventurer" },
            displayName = displayName.trim().ifBlank { "Aether Vanguard" },
            level = level.coerceAtLeast(1),
            completedQuestCount = completedQuestCount.coerceAtLeast(0),
            currentActiveChatTitle = if (isStealthMode || !isOnline) {
                PlayerPresence.OFFLINE_CHAT_TITLE
            } else {
                currentActiveChatTitle.trim().ifBlank { "General Gemini" }
            },
            isOnline = if (isStealthMode) false else isOnline,
            isStealthMode = isStealthMode,
            sessionStartedEpochMs = sessionStart,
            lastHeartbeatEpochMs = nowEpochMs
        ).enforceStealthPrivacy()
    }

    /**
     * Immediately updates [AppSettingsManager] Stealth Mode preference, updates in-memory state,
     * and forces an immediate Room (`player_presence_cache`) + Firestore (`presence/{uid}`)
     * presence sync with `isStealthMode = enabled` and (when `enabled == true`)
     * `isOnline = false`, `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L`.
     */
    suspend fun setStealthModeAndForceSync(
        context: Context,
        enabled: Boolean,
        basePresence: PlayerPresence? = null,
        activeChatTitleOverride: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PlayerPresence {
        AppSettingsManager.setStealthModeEnabled(context, enabled)
        val source = basePresence ?: _localPlayerPresence.value
        val resolvedUid = source.odinUid.trim().ifBlank { resolveCurrentOdinUid(context) }
        val resolvedChatTitle = if (enabled) {
            PlayerPresence.OFFLINE_CHAT_TITLE
        } else {
            activeChatTitleOverride?.trim()?.takeIf { it.isNotEmpty() && it != PlayerPresence.OFFLINE_CHAT_TITLE }
                ?: source.currentActiveChatTitle.takeIf { it.isNotBlank() && it != PlayerPresence.OFFLINE_CHAT_TITLE }
                ?: "General Gemini"
        }

        val updated = buildLocalPresenceSnapshot(
            odinUid = resolvedUid,
            displayName = source.displayName,
            level = source.level,
            completedQuestCount = source.completedQuestCount,
            currentActiveChatTitle = resolvedChatTitle,
            isOnline = !enabled,
            isStealthMode = enabled,
            nowEpochMs = nowEpochMs
        )
        _localPlayerPresence.value = updated
        val firestoreMap = updated.toFirestoreMap()
        _lastSyncedFirestorePayload.value = firestoreMap

        withContext(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.getDatabase(context).adventureGameDao()
                dao.upsertPlayerPresence(updated)
                refreshPartyListFromRoom(context, nowEpochMs)
            }.onFailure { e ->
                Log.w(TAG, "Failed to upsert Stealth Mode presence in Room: ${e.message}")
            }

            pushPresenceDocumentToFirestore(updated, firestoreMap)
        }
        return updated
    }

    /**
     * Synchronous UI helper invoked by `FriendPresenceCard`'s eye toggle icon:
     * 1. Immediately toggles `isStealthMode` in [AppSettingsManager].
     * 2. Immediately updates [localPlayerPresence] and [lastSyncedFirestorePayload] on the caller thread
     *    (forcing `isOnline = false` and `currentActiveChatTitle = "Offline"` when stealth is enabled).
     * 3. Dispatches an immediate Room + Firestore (`presence/{uid}`) sync in the background scope.
     */
    fun toggleStealthModeFromUi(
        context: Context,
        currentPresence: PlayerPresence? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        onStealthChanged: ((Boolean) -> Unit)? = null
    ): PlayerPresence {
        AppSettingsManager.ensureInitialized(context)
        val currentStealth = currentPresence?.isStealthMode ?: AppSettingsManager.isStealthModeEnabled(context)
        val nextStealth = !currentStealth
        AppSettingsManager.setStealthModeEnabled(context, nextStealth)

        val source = currentPresence ?: _localPlayerPresence.value
        val resolvedUid = source.odinUid.trim().ifBlank { resolveCurrentOdinUid(context) }
        val resolvedTitle = if (nextStealth) {
            PlayerPresence.OFFLINE_CHAT_TITLE
        } else {
            source.currentActiveChatTitle.takeIf { it.isNotBlank() && it != PlayerPresence.OFFLINE_CHAT_TITLE }
                ?: "General Gemini"
        }

        val updated = buildLocalPresenceSnapshot(
            odinUid = resolvedUid,
            displayName = source.displayName,
            level = source.level,
            completedQuestCount = source.completedQuestCount,
            currentActiveChatTitle = resolvedTitle,
            isOnline = !nextStealth,
            isStealthMode = nextStealth,
            nowEpochMs = nowEpochMs
        )
        _localPlayerPresence.value = updated
        val firestoreMap = updated.toFirestoreMap()
        _lastSyncedFirestorePayload.value = firestoreMap

        onStealthChanged?.invoke(nextStealth)

        val appContext = context.applicationContext ?: context
        scope.launch {
            runCatching {
                val dao = AppDatabase.getDatabase(appContext).adventureGameDao()
                dao.upsertPlayerPresence(updated)
                refreshPartyListFromRoom(appContext, nowEpochMs)
            }
            pushPresenceDocumentToFirestore(updated, firestoreMap)
        }
        return updated
    }

    private suspend fun pushPresenceDocumentToFirestore(
        presence: PlayerPresence,
        firestoreMap: Map<String, Any> = presence.toFirestoreMap()
    ) {
        val authUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val targetDocId = authUser?.uid?.takeIf { it.isNotBlank() } ?: presence.odinUid
        if (targetDocId.isBlank()) return

        runCatching {
            val docRef = FirebaseFirestore.getInstance()
                .collection(PlayerPresence.COLLECTION_PRESENCE)
                .document(targetDocId)
            docRef.set(firestoreMap, SetOptions.merge()).await()
        }.onFailure { e ->
            Log.w(TAG, "Firestore presence sync deferred: ${e.message}")
        }
    }

    /**
     * Synchronizes the local player's stats (`level`, `completedQuestCount`, `currentActiveChatTitle`)
     * and privacy state (`isStealthMode`) to both Room (`player_presence_cache`) and Cloud Firestore
     * (`presence/{uid}`).
     */
    suspend fun syncLocalPresence(
        context: Context,
        adventureState: AdventureGameState,
        activeChatTitle: String,
        isAppInForeground: Boolean = true,
        overrideStealthMode: Boolean? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PlayerPresence {
        val stealth = overrideStealthMode ?: AppSettingsManager.isStealthModeEnabled(context)
        val odinUid = resolveCurrentOdinUid(context)
        val displayName = resolveDisplayName(adventureState.characterName)
        val shouldBeOnline = isAppInForeground && adventureState.isOnline && !stealth

        val presence = buildLocalPresenceSnapshot(
            odinUid = odinUid,
            displayName = displayName,
            level = adventureState.level,
            completedQuestCount = adventureState.completedQuestCount,
            currentActiveChatTitle = activeChatTitle,
            isOnline = shouldBeOnline,
            isStealthMode = stealth,
            nowEpochMs = nowEpochMs
        )
        _localPlayerPresence.value = presence
        val firestoreMap = presence.toFirestoreMap()
        _lastSyncedFirestorePayload.value = firestoreMap

        withContext(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.getDatabase(context).adventureGameDao()
                dao.upsertPlayerPresence(presence)
                refreshPartyListFromRoom(context, nowEpochMs)
            }.onFailure { e ->
                Log.w(TAG, "Failed to upsert PlayerPresence in Room: ${e.message}")
            }

            pushPresenceDocumentToFirestore(presence, firestoreMap)
        }
        return presence
    }

    /**
     * Refreshes the in-memory [partyPresenceList] from Room, sorting pinned friends first,
     * then effectively online players, then by level & recent heartbeat.
     */
    suspend fun refreshPartyListFromRoom(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<PlayerPresence> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).adventureGameDao()
        val cached = dao.getAllPlayerPresenceSync()
        val pinnedUids = AppSettingsManager.pinnedFriendUids.value
        val selfUid = _localPlayerPresence.value.odinUid

        // Ensure pinned UIDs that aren't in Room yet have a placeholder entry
        val existingUids = cached.map { it.odinUid }.toSet()
        val placeholders = pinnedUids
            .filter { it.isNotBlank() && it !in existingUids && it != selfUid }
            .map { friendUid ->
                PlayerPresence(
                    odinUid = friendUid,
                    displayName = "Adventurer #${friendUid.takeLast(5)}",
                    level = 1,
                    completedQuestCount = 0,
                    currentActiveChatTitle = PlayerPresence.OFFLINE_CHAT_TITLE,
                    isOnline = false,
                    isStealthMode = false,
                    sessionStartedEpochMs = 0L,
                    lastHeartbeatEpochMs = 0L
                )
            }

        val combined = (cached + placeholders)
            .map { it.enforceStealthPrivacy() }
            .sortedWith(
                compareByDescending<PlayerPresence> { it.odinUid == selfUid }
                    .thenByDescending { pinnedUids.contains(it.odinUid) }
                    .thenByDescending { it.isEffectivelyOnline(nowEpochMs) }
                    .thenByDescending { it.level }
                    .thenByDescending { it.lastHeartbeatEpochMs }
            )
        _partyPresenceList.value = combined
        combined
    }

    /**
     * Starts the 30-second periodic presence heartbeat and Firestore live lobby listener.
     */
    fun startHeartbeatAndLobbySync(
        context: Context,
        stateProvider: () -> Pair<AdventureGameState, String>
    ) {
        val appContext = context.applicationContext
        startFirestoreLobbyListener(appContext)

        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching {
                    val (advState, chatTitle) = stateProvider()
                    syncLocalPresence(
                        context = appContext,
                        adventureState = advState,
                        activeChatTitle = chatTitle,
                        isAppInForeground = true
                    )
                }
                delay(PlayerPresence.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the periodic heartbeat and marks the local player offline in Room and Firestore.
     */
    fun stopHeartbeatAndMarkOffline(
        context: Context,
        adventureState: AdventureGameState,
        activeChatTitle: String
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        firestoreListener?.remove()
        firestoreListener = null

        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                syncLocalPresence(
                    context = appContext,
                    adventureState = adventureState,
                    activeChatTitle = activeChatTitle,
                    isAppInForeground = false
                )
            }
        }
    }

    private fun startFirestoreLobbyListener(context: Context) {
        val authUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return
        if (firestoreListener != null) return

        runCatching {
            firestoreListener = FirebaseFirestore.getInstance()
                .collection(PlayerPresence.COLLECTION_PRESENCE)
                .limit(50)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteList = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        PlayerPresence.fromFirestoreMap(doc.id, data)
                    }
                    if (remoteList.isNotEmpty()) {
                        scope.launch {
                            runCatching {
                                val dao = AppDatabase.getDatabase(context).adventureGameDao()
                                dao.upsertAllPlayerPresence(remoteList)
                                refreshPartyListFromRoom(context)
                            }
                        }
                    }
                }
        }.onFailure { e ->
            Log.w(TAG, "Could not attach Firestore presence listener: ${e.message}")
        }
    }

    /**
     * Resets in-memory session state for isolated unit tests.
     */
    fun resetForTesting(nowEpochMs: Long = System.currentTimeMillis()) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        firestoreListener?.remove()
        firestoreListener = null
        currentSessionStartedEpochMs = nowEpochMs
    }
}
