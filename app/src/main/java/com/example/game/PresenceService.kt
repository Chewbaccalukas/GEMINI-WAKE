package com.example.game

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work. Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.util.AppSettingsManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * WorkManager-backed `PresenceService` (`CoroutineWorker`) that drives:
 * 1. **30-Second Heartbeat Ping (`MODE_HEARTBEAT_PING`)**:
 *    Triggers every 30 seconds while the app is active to update the player's
 *    `lastHeartbeatEpochMs`, `isOnline`, `level`, `completedQuestCount`, and
 *    `currentActiveChatTitle` in Room (`player_presence_cache`) and Cloud Firestore
 *    (`presence/{uid}`), while enforcing Stealth Mode (`isStealthMode = true` ->
 *    `isOnline = false`, `currentActiveChatTitle = "Offline"`).
 * 2. **2-Minute Offline Cleanup When Backgrounded (`MODE_OFFLINE_CLEANUP`)**:
 *    Scheduled with a 2-minute (`120_000L` ms) delay when the app is backgrounded
 *    (and cancelled automatically if the user returns to the foreground before 2 minutes).
 *    Transitions the player's status to `isOnline = false` and `currentActiveChatTitle = "Offline"`
 *    in Room and Firestore, and cleans up any cached peer presences whose
 *    `lastHeartbeatEpochMs` is older than 2 minutes.
 */
class PresenceService(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mode = inputData.getString(KEY_WORK_MODE) ?: MODE_HEARTBEAT_PING
        val overrideNowMs = inputData.getLong(KEY_OVERRIDE_NOW_EPOCH_MS, -1L)
        val nowEpochMs = if (overrideNowMs > 0L) overrideNowMs else System.currentTimeMillis()
        val chainNextHeartbeat = inputData.getBoolean(KEY_CHAIN_NEXT_HEARTBEAT, true)

        return@withContext try {
            when (mode) {
                MODE_OFFLINE_CLEANUP -> {
                    performOfflineCleanup(
                        context = applicationContext,
                        nowEpochMs = nowEpochMs
                    )
                }
                else -> {
                    performHeartbeatPing(
                        context = applicationContext,
                        nowEpochMs = nowEpochMs
                    )
                    if (chainNextHeartbeat && isForegroundSessionActive) {
                        scheduleNext30sHeartbeatPing(applicationContext)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "PresenceService ($mode) failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PresenceService"

        const val UNIQUE_HEARTBEAT_WORK_NAME = "adventure_presence_30s_heartbeat_work"
        const val UNIQUE_OFFLINE_CLEANUP_WORK_NAME = "adventure_presence_2m_offline_cleanup_work"

        const val WORK_TAG_HEARTBEAT = "tag_adventure_presence_30s_heartbeat"
        const val WORK_TAG_OFFLINE_CLEANUP = "tag_adventure_presence_2m_offline_cleanup"

        const val KEY_WORK_MODE = "presence_work_mode"
        const val KEY_OVERRIDE_NOW_EPOCH_MS = "presence_override_now_epoch_ms"
        const val KEY_CHAIN_NEXT_HEARTBEAT = "presence_chain_next_heartbeat"

        const val MODE_HEARTBEAT_PING = "HEARTBEAT_PING"
        const val MODE_OFFLINE_CLEANUP = "OFFLINE_CLEANUP"

        const val HEARTBEAT_INTERVAL_SECONDS = 30L
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val OFFLINE_CLEANUP_DELAY_MINUTES = 2L
        const val OFFLINE_CLEANUP_TIMEOUT_MS = 120_000L // 2 minutes

        @Volatile
        var isForegroundSessionActive: Boolean = true
            internal set

        private val _lastHeartbeatPingEpochMs = MutableStateFlow(0L)
        val lastHeartbeatPingEpochMs: StateFlow<Long> = _lastHeartbeatPingEpochMs.asStateFlow()

        private val _lastOfflineCleanupEpochMs = MutableStateFlow(0L)
        val lastOfflineCleanupEpochMs: StateFlow<Long> = _lastOfflineCleanupEpochMs.asStateFlow()

        /**
         * Builds a [OneTimeWorkRequest] for the 30-second heartbeat ping.
         */
        fun buildHeartbeatWorkRequest(
            initialDelaySeconds: Long = HEARTBEAT_INTERVAL_SECONDS,
            chainNextHeartbeat: Boolean = true,
            overrideNowEpochMs: Long = -1L
        ): OneTimeWorkRequest {
            val input = Data.Builder()
                .putString(KEY_WORK_MODE, MODE_HEARTBEAT_PING)
                .putBoolean(KEY_CHAIN_NEXT_HEARTBEAT, chainNextHeartbeat)
                .putLong(KEY_OVERRIDE_NOW_EPOCH_MS, overrideNowEpochMs)
                .build()

            val builder = OneTimeWorkRequestBuilder<PresenceService>()
                .setInputData(input)
                .addTag(WORK_TAG_HEARTBEAT)

            if (initialDelaySeconds > 0L) {
                builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            }
            return builder.build()
        }

        /**
         * Builds a [OneTimeWorkRequest] for the 2-minute background offline cleanup.
         */
        fun buildOfflineCleanupWorkRequest(
            delayMinutes: Long = OFFLINE_CLEANUP_DELAY_MINUTES,
            overrideNowEpochMs: Long = -1L
        ): OneTimeWorkRequest {
            val input = Data.Builder()
                .putString(KEY_WORK_MODE, MODE_OFFLINE_CLEANUP)
                .putBoolean(KEY_CHAIN_NEXT_HEARTBEAT, false)
                .putLong(KEY_OVERRIDE_NOW_EPOCH_MS, overrideNowEpochMs)
                .build()

            val builder = OneTimeWorkRequestBuilder<PresenceService>()
                .setInputData(input)
                .addTag(WORK_TAG_OFFLINE_CLEANUP)

            if (delayMinutes > 0L) {
                builder.setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            }
            return builder.build()
        }

        /**
         * Called when the app enters or resumes the foreground:
         * 1. Marks [isForegroundSessionActive] = `true`.
         * 2. Cancels any pending 2-minute background offline cleanup work.
         * 3. Enqueues an immediate heartbeat ping (or 30-second scheduled ping) via [WorkManager].
         */
        fun onAppForegrounded(context: Context, immediatePing: Boolean = true) {
            isForegroundSessionActive = true
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(UNIQUE_OFFLINE_CLEANUP_WORK_NAME)
                val delaySeconds = if (immediatePing) 0L else HEARTBEAT_INTERVAL_SECONDS
                val request = buildHeartbeatWorkRequest(
                    initialDelaySeconds = delaySeconds,
                    chainNextHeartbeat = true
                )
                workManager.enqueueUniqueWork(
                    UNIQUE_HEARTBEAT_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }.onFailure { e ->
                Log.w(TAG, "Unable to enqueue foreground heartbeat work: ${e.message}")
            }
        }

        /**
         * Schedules the next 30-second heartbeat ping in the WorkManager self-chaining loop.
         */
        fun scheduleNext30sHeartbeatPing(context: Context) {
            if (!isForegroundSessionActive) return
            runCatching {
                val request = buildHeartbeatWorkRequest(
                    initialDelaySeconds = HEARTBEAT_INTERVAL_SECONDS,
                    chainNextHeartbeat = true
                )
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE_HEARTBEAT_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }.onFailure { e ->
                Log.w(TAG, "Unable to schedule next 30s heartbeat ping: ${e.message}")
            }
        }

        /**
         * Called when the app is backgrounded (`MainActivity.onStop()` / `MainViewModel.onAppBackgrounded()`):
         * 1. Marks [isForegroundSessionActive] = `false`.
         * 2. Cancels the 30-second foreground heartbeat WorkManager loop.
         * 3. Schedules the 2-minute (`120_000L` ms) offline cleanup work in [WorkManager].
         */
        fun onAppBackgrounded(
            context: Context,
            cleanupDelayMinutes: Long = OFFLINE_CLEANUP_DELAY_MINUTES
        ) {
            isForegroundSessionActive = false
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(UNIQUE_HEARTBEAT_WORK_NAME)
                val cleanupRequest = buildOfflineCleanupWorkRequest(
                    delayMinutes = cleanupDelayMinutes
                )
                workManager.enqueueUniqueWork(
                    UNIQUE_OFFLINE_CLEANUP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    cleanupRequest
                )
            }.onFailure { e ->
                Log.w(TAG, "Unable to enqueue 2-minute offline cleanup work: ${e.message}")
            }
        }

        /**
         * Executes a single 30-second heartbeat ping:
         * - Reads the player's latest RPG stats & active chat session from Room.
         * - Updates `lastHeartbeatEpochMs = nowEpochMs` and online status (respecting `isStealthMode`).
         * - Persists the updated [PlayerPresence] to Room (`player_presence_cache`) and pushes to
         *   Firestore (`presence/{uid}`).
         * - Sweeps any peer presences in Room whose `lastHeartbeatEpochMs` is older than 2 minutes.
         */
        suspend fun performHeartbeatPing(
            context: Context,
            nowEpochMs: Long = System.currentTimeMillis()
        ): PlayerPresence = withContext(Dispatchers.IO) {
            AppSettingsManager.ensureInitialized(context)
            val db = AppDatabase.getDatabase(context)
            val advDao = db.adventureGameDao()
            val chatDao = db.chatDao()

            val activeChats = runCatching { chatDao.getAllChatsSync().filter { !it.isDeleted } }
                .getOrDefault(emptyList())
            val latestChat = activeChats.maxByOrNull { it.lastUpdatedAt }
            val activeChatTitle = latestChat?.title?.takeIf { it.isNotBlank() }
                ?: PlayerPresenceCoordinator.localPlayerPresence.value.currentActiveChatTitle
                    .takeIf { it.isNotBlank() && it != PlayerPresence.OFFLINE_CHAT_TITLE }
                ?: "General Gemini"

            val savedStates = runCatching { advDao.getAllSavedStatesSync() }.getOrDefault(emptyList())
            val primaryEntity = savedStates.firstOrNull { it.chatId > 0L } ?: savedStates.firstOrNull()
            val resolvedAdvState = primaryEntity?.toDomainState()
                ?: AdventureGameState(
                    characterName = PlayerPresenceCoordinator.localPlayerPresence.value.displayName,
                    isOnline = true
                )

            val stealth = AppSettingsManager.isStealthModeEnabled(context)
            val updatedPresence = PlayerPresenceCoordinator.syncLocalPresence(
                context = context,
                adventureState = resolvedAdvState,
                activeChatTitle = activeChatTitle,
                isAppInForeground = true,
                overrideStealthMode = stealth,
                nowEpochMs = nowEpochMs
            )

            // Also clean up any cached peer presences older than 2 minutes
            cleanupStalePeerPresences(
                context = context,
                nowEpochMs = nowEpochMs,
                excludeOdinUid = updatedPresence.odinUid
            )

            _lastHeartbeatPingEpochMs.value = nowEpochMs
            updatedPresence
        }

        /**
         * Executes the 2-minute background offline cleanup logic:
         * 1. Transitions the local player's presence to `isOnline = false`,
         *    `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L` in Room and
         *    Firestore (`presence/{uid}`).
         * 2. Sweeps all cached presences in Room (`player_presence_cache`) whose
         *    `nowEpochMs - lastHeartbeatEpochMs > 120_000L` (2 minutes) and marks them offline.
         */
        suspend fun performOfflineCleanup(
            context: Context,
            nowEpochMs: Long = System.currentTimeMillis()
        ): PlayerPresence = withContext(Dispatchers.IO) {
            AppSettingsManager.ensureInitialized(context)
            val db = AppDatabase.getDatabase(context)
            val advDao = db.adventureGameDao()

            val odinUid = PlayerPresenceCoordinator.resolveCurrentOdinUid(context)
            val existingLocal = advDao.getPlayerPresenceByUidSync(odinUid)
                ?: PlayerPresenceCoordinator.localPlayerPresence.value
            val stealth = AppSettingsManager.isStealthModeEnabled(context)

            val offlineLocal = existingLocal.copy(
                odinUid = odinUid,
                isOnline = false,
                isStealthMode = stealth,
                currentActiveChatTitle = PlayerPresence.OFFLINE_CHAT_TITLE,
                sessionStartedEpochMs = 0L,
                lastHeartbeatEpochMs = nowEpochMs
            ).enforceStealthPrivacy()

            advDao.upsertPlayerPresence(offlineLocal)

            // Sync offline cleanup to Firestore presence/{uid} when authenticated
            val authUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
            if (authUser != null && authUser.uid == offlineLocal.odinUid) {
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection(PlayerPresence.COLLECTION_PRESENCE)
                        .document(authUser.uid)
                        .set(offlineLocal.toFirestoreMap(), SetOptions.merge())
                        .await()
                }.onFailure { e ->
                    Log.w(TAG, "Firestore offline cleanup deferred: ${e.message}")
                }
            }

            // Sweep all stale peer presences older than 2 minutes (120,000 ms)
            cleanupStalePeerPresences(
                context = context,
                nowEpochMs = nowEpochMs,
                excludeOdinUid = null
            )

            PlayerPresenceCoordinator.syncLocalPresence(
                context = context,
                adventureState = AdventureGameState(
                    characterName = offlineLocal.displayName,
                    totalXp = ((offlineLocal.level - 1).coerceAtLeast(0)) * AdventureGameState.XP_PER_LEVEL,
                    isOnline = false,
                    isStealthMode = stealth
                ),
                activeChatTitle = PlayerPresence.OFFLINE_CHAT_TITLE,
                isAppInForeground = false,
                overrideStealthMode = stealth,
                nowEpochMs = nowEpochMs
            )

            _lastOfflineCleanupEpochMs.value = nowEpochMs
            offlineLocal
        }

        /**
         * Marks any cached [PlayerPresence] rows in Room whose `lastHeartbeatEpochMs` is older than
         * [OFFLINE_CLEANUP_TIMEOUT_MS] (2 minutes) as `isOnline = false` and
         * `currentActiveChatTitle = "Offline"`.
         */
        suspend fun cleanupStalePeerPresences(
            context: Context,
            nowEpochMs: Long = System.currentTimeMillis(),
            timeoutMs: Long = OFFLINE_CLEANUP_TIMEOUT_MS,
            excludeOdinUid: String? = null
        ): Int = withContext(Dispatchers.IO) {
            val advDao = AppDatabase.getDatabase(context).adventureGameDao()
            val allCached = advDao.getAllPlayerPresenceSync()
            val staleUpdates = allCached.mapNotNull { entry ->
                if (excludeOdinUid != null && entry.odinUid == excludeOdinUid) {
                    return@mapNotNull null
                }
                val isStale = (nowEpochMs - entry.lastHeartbeatEpochMs) > timeoutMs
                if ((entry.isOnline && isStale) || entry.isStealthMode) {
                    entry.copy(
                        isOnline = false,
                        currentActiveChatTitle = PlayerPresence.OFFLINE_CHAT_TITLE,
                        sessionStartedEpochMs = 0L
                    )
                } else {
                    null
                }
            }
            if (staleUpdates.isNotEmpty()) {
                advDao.upsertAllPlayerPresence(staleUpdates)
                PlayerPresenceCoordinator.refreshPartyListFromRoom(context, nowEpochMs)
            }
            staleUpdates.size
        }
    }
}
