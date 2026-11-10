package com.example.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.voice.WakeWordVoiceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Top-level Jetpack DataStore<Preferences> delegate for storing the user's custom wake word configuration.
 */
val Context.wakeWordDataStore: DataStore<Preferences> by preferencesDataStore(
    name = WakeWordDataStoreManager.DATASTORE_NAME
)

/**
 * Snapshot of the custom wake word configuration stored in Jetpack [DataStore].
 */
data class CustomWakeWordDataStoreConfig(
    val phrase: String = WakeWordDataStoreManager.DEFAULT_CUSTOM_WAKE_WORD,
    val sensitivity: Float = WakeWordVoiceService.DEFAULT_CUSTOM_WAKE_SENSITIVITY,
    val targetChatId: Long? = null,
    val screenOffBatterySaverEnabled: Boolean = false,
    val updatedAtEpochMs: Long = 0L
)

/**
 * Manages persisting and observing the user's custom wake word in Jetpack [DataStore] (`Preferences`),
 * while keeping [AppSettingsManager] and [WakeWordVoiceService] synchronized for immediate recognition.
 */
object WakeWordDataStoreManager {

    const val DATASTORE_NAME = "wake_word_preferences"
    const val DEFAULT_CUSTOM_WAKE_WORD = "Hey Gemini"
    const val DEFAULT_WAKE_WORD_SENSITIVITY = 0.78f

    val KEY_CUSTOM_WAKE_WORD_PHRASE = stringPreferencesKey("custom_wake_word_phrase")
    val KEY_CUSTOM_WAKE_WORD_SENSITIVITY = floatPreferencesKey("custom_wake_word_sensitivity")
    val KEY_WAKE_WORD_DETECTION_SENSITIVITY = floatPreferencesKey("wake_word_detection_sensitivity")
    val KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED = booleanPreferencesKey("screen_off_battery_saver_enabled")
    val KEY_CUSTOM_WAKE_WORD_TARGET_CHAT_ID = longPreferencesKey("custom_wake_word_target_chat_id")
    val KEY_SELECTED_FIRESTORE_WAKE_CHAT_DOC_ID = stringPreferencesKey("selected_firestore_wake_chat_doc_id")
    val KEY_SELECTED_FIRESTORE_WAKE_CHAT_TITLE = stringPreferencesKey("selected_firestore_wake_chat_title")
    val KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS = longPreferencesKey("custom_wake_word_updated_at_ms")

    private val _customWakeWordState = MutableStateFlow(DEFAULT_CUSTOM_WAKE_WORD)
    val customWakeWordState: StateFlow<String> = _customWakeWordState.asStateFlow()

    private val _wakeWordSensitivityState = MutableStateFlow(DEFAULT_WAKE_WORD_SENSITIVITY)
    val wakeWordSensitivityState: StateFlow<Float> = _wakeWordSensitivityState.asStateFlow()

    private val _screenOffBatterySaverState = MutableStateFlow(false)
    val screenOffBatterySaverState: StateFlow<Boolean> = _screenOffBatterySaverState.asStateFlow()

    private val _selectedWakeTargetChatIdState = MutableStateFlow<Long?>(null)
    val selectedWakeTargetChatIdState: StateFlow<Long?> = _selectedWakeTargetChatIdState.asStateFlow()

    private val _selectedWakeTargetDocIdState = MutableStateFlow<String?>(null)
    val selectedWakeTargetDocIdState: StateFlow<String?> = _selectedWakeTargetDocIdState.asStateFlow()

    private val _customWakeWordConfigState = MutableStateFlow(CustomWakeWordDataStoreConfig())
    val customWakeWordConfigState: StateFlow<CustomWakeWordDataStoreConfig> = _customWakeWordConfigState.asStateFlow()

    private val dataStoreLock = Any()
    @Volatile
    private var sharedDataStore: DataStore<Preferences>? = null

    private fun resolveDataStore(context: Context): DataStore<Preferences> {
        sharedDataStore?.let { return it }
        return synchronized(dataStoreLock) {
            sharedDataStore ?: (context.applicationContext ?: context).wakeWordDataStore.also {
                sharedDataStore = it
            }
        }
    }

    /**
     * Observes the custom wake word phrase from Jetpack [DataStore].
     */
    fun observeCustomWakeWord(context: Context): Flow<String> {
        val appContext = context.applicationContext ?: context
        return resolveDataStore(appContext).data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    emit(emptyPreferences())
                }
            }
            .map { preferences ->
                val phrase = preferences[KEY_CUSTOM_WAKE_WORD_PHRASE]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: _customWakeWordState.value.takeIf { it.isNotBlank() }
                    ?: AppSettingsManager.getCustomWakeWordPhrase(appContext)
                _customWakeWordState.value = phrase
                phrase
            }
    }

    /**
     * Observes the wake word detection sensitivity threshold (`0.50f..0.98f`) from Jetpack [DataStore].
     */
    fun observeWakeWordSensitivity(context: Context): Flow<Float> {
        val appContext = context.applicationContext ?: context
        return resolveDataStore(appContext).data
            .catch { _ ->
                emit(emptyPreferences())
            }
            .map { preferences ->
                val sensitivity = (preferences[KEY_WAKE_WORD_DETECTION_SENSITIVITY]
                    ?: preferences[KEY_CUSTOM_WAKE_WORD_SENSITIVITY]
                    ?: AppSettingsManager.getCustomWakeWordSensitivity(appContext)).coerceIn(0.50f, 0.98f)
                _wakeWordSensitivityState.value = sensitivity
                sensitivity
            }
    }

    /**
     * Observes the full [CustomWakeWordDataStoreConfig] from Jetpack [DataStore].
     */
    fun observeCustomWakeWordConfig(context: Context): Flow<CustomWakeWordDataStoreConfig> {
        val appContext = context.applicationContext ?: context
        return resolveDataStore(appContext).data
            .catch { _ ->
                emit(emptyPreferences())
            }
            .map { preferences ->
                val phrase = preferences[KEY_CUSTOM_WAKE_WORD_PHRASE]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: AppSettingsManager.getCustomWakeWordPhrase(appContext)
                val sensitivity = (preferences[KEY_WAKE_WORD_DETECTION_SENSITIVITY]
                    ?: preferences[KEY_CUSTOM_WAKE_WORD_SENSITIVITY]
                    ?: AppSettingsManager.getCustomWakeWordSensitivity(appContext)).coerceIn(0.50f, 0.98f)
                val targetChatId = preferences[KEY_CUSTOM_WAKE_WORD_TARGET_CHAT_ID]?.takeIf { it > 0L }
                val screenOffSaver = preferences[KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED]
                    ?: AppSettingsManager.isScreenOffBatterySaverEnabled(appContext)
                val updatedAt = preferences[KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS] ?: 0L
                val config = CustomWakeWordDataStoreConfig(
                    phrase = phrase,
                    sensitivity = sensitivity,
                    targetChatId = targetChatId,
                    screenOffBatterySaverEnabled = screenOffSaver,
                    updatedAtEpochMs = updatedAt
                )
                _customWakeWordState.value = phrase
                _wakeWordSensitivityState.value = sensitivity
                _screenOffBatterySaverState.value = screenOffSaver
                _customWakeWordConfigState.value = config
                config
            }
    }

    /**
     * Persists the wake word detection sensitivity threshold (`0.50f..0.98f`) into Jetpack [DataStore]
     * and synchronizes it with [AppSettingsManager] and [WakeWordVoiceService].
     */
    suspend fun saveWakeWordSensitivity(
        context: Context,
        sensitivity: Float
    ): Float {
        val appContext = context.applicationContext ?: context
        val coercedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        val now = System.currentTimeMillis()
        var currentPhrase = _customWakeWordState.value
        var currentChatId = _customWakeWordConfigState.value.targetChatId

        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                resolveDataStore(appContext).edit { preferences ->
                    preferences[KEY_WAKE_WORD_DETECTION_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_CUSTOM_WAKE_WORD_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS] = now
                    currentPhrase = preferences[KEY_CUSTOM_WAKE_WORD_PHRASE]
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: currentPhrase
                    currentChatId = preferences[KEY_CUSTOM_WAKE_WORD_TARGET_CHAT_ID]?.takeIf { it > 0L }
                        ?: currentChatId
                }
            }
        }

        _wakeWordSensitivityState.value = coercedSensitivity
        _customWakeWordConfigState.value = _customWakeWordConfigState.value.copy(
            phrase = currentPhrase,
            sensitivity = coercedSensitivity,
            targetChatId = currentChatId,
            updatedAtEpochMs = now
        )

        AppSettingsManager.saveCustomWakeWordSensitivity(
            context = appContext,
            sensitivity = coercedSensitivity
        )
        return coercedSensitivity
    }

    /**
     * Reads the current wake word detection sensitivity threshold from Jetpack [DataStore].
     */
    suspend fun getWakeWordSensitivity(context: Context): Float {
        return kotlinx.coroutines.withTimeoutOrNull(1500L) {
            observeWakeWordSensitivity(context).first()
        } ?: _wakeWordSensitivityState.value
    }

    /**
     * Persists a new or updated custom wake word phrase into Jetpack [DataStore] and synchronizes it
     * with [AppSettingsManager] and [WakeWordVoiceService].
     */
    suspend fun saveCustomWakeWord(
        context: Context,
        phrase: String,
        chatId: Long? = null,
        sensitivity: Float = _wakeWordSensitivityState.value
    ): String {
        val appContext = context.applicationContext ?: context
        val sanitized = phrase.trim().ifBlank { DEFAULT_CUSTOM_WAKE_WORD }
        val coercedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        val now = System.currentTimeMillis()

        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                resolveDataStore(appContext).edit { preferences ->
                    preferences[KEY_CUSTOM_WAKE_WORD_PHRASE] = sanitized
                    preferences[KEY_CUSTOM_WAKE_WORD_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_WAKE_WORD_DETECTION_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_CUSTOM_WAKE_WORD_TARGET_CHAT_ID] = chatId ?: -1L
                    preferences[KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS] = now
                }
            }
        }

        _customWakeWordState.value = sanitized
        _wakeWordSensitivityState.value = coercedSensitivity
        _customWakeWordConfigState.value = CustomWakeWordDataStoreConfig(
            phrase = sanitized,
            sensitivity = coercedSensitivity,
            targetChatId = chatId?.takeIf { it > 0L },
            screenOffBatterySaverEnabled = _screenOffBatterySaverState.value,
            updatedAtEpochMs = now
        )

        // Keep SharedPreferences and WakeWordVoiceService in sync for immediate background/foreground recognition
        AppSettingsManager.saveCustomWakeWordPhrase(
            context = appContext,
            phrase = sanitized,
            chatId = chatId,
            sensitivity = coercedSensitivity
        )
        return sanitized
    }

    /**
     * Observes the Screen-Off Battery Saver setting (`true` = reduce wake word detection polling
     * frequency when the screen is off) from Jetpack [DataStore].
     */
    fun observeScreenOffBatterySaver(context: Context): Flow<Boolean> {
        val appContext = context.applicationContext ?: context
        return resolveDataStore(appContext).data
            .catch { _ ->
                emit(emptyPreferences())
            }
            .map { preferences ->
                val enabled = preferences[KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED]
                    ?: AppSettingsManager.isScreenOffBatterySaverEnabled(appContext)
                _screenOffBatterySaverState.value = enabled
                enabled
            }
    }

    /**
     * Persists the Screen-Off Battery Saver toggle in Jetpack [DataStore] and synchronizes it
     * with [AppSettingsManager] and [WakeWordVoiceService].
     */
    suspend fun saveScreenOffBatterySaver(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext ?: context
        val now = System.currentTimeMillis()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                resolveDataStore(appContext).edit { preferences ->
                    preferences[KEY_SCREEN_OFF_BATTERY_SAVER_ENABLED] = enabled
                    preferences[KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS] = now
                }
            }
        }
        _screenOffBatterySaverState.value = enabled
        _customWakeWordConfigState.value = _customWakeWordConfigState.value.copy(
            screenOffBatterySaverEnabled = enabled,
            updatedAtEpochMs = now
        )
        AppSettingsManager.setScreenOffBatterySaverEnabled(appContext, enabled)
        return enabled
    }

    suspend fun getScreenOffBatterySaver(context: Context): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(1500L) {
            observeScreenOffBatterySaver(context).first()
        } ?: _screenOffBatterySaverState.value
    }

    /**
     * Reads the current custom wake word phrase from Jetpack [DataStore].
     */
    suspend fun getCustomWakeWord(context: Context): String {
        return kotlinx.coroutines.withTimeoutOrNull(1500L) {
            observeCustomWakeWord(context).first()
        } ?: _customWakeWordState.value
    }

    /**
     * Persists the selected Firestore Gemini chat session as the active wake-word target in Jetpack [DataStore]
     * and synchronizes it with [AppSettingsManager] and [WakeWordVoiceService].
     */
    suspend fun saveSelectedWakeTargetChat(
        context: Context,
        localChatId: Long,
        remoteDocId: String,
        chatTitle: String,
        wakePhrase: String? = null,
        sensitivity: Float = _wakeWordSensitivityState.value
    ): Long {
        val appContext = context.applicationContext ?: context
        val resolvedPhrase = wakePhrase?.trim()?.takeIf { it.isNotBlank() }
            ?: _customWakeWordState.value.ifBlank { DEFAULT_CUSTOM_WAKE_WORD }
        val coercedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        val now = System.currentTimeMillis()

        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                resolveDataStore(appContext).edit { preferences ->
                    preferences[KEY_CUSTOM_WAKE_WORD_TARGET_CHAT_ID] = localChatId
                    preferences[KEY_SELECTED_FIRESTORE_WAKE_CHAT_DOC_ID] = remoteDocId
                    preferences[KEY_SELECTED_FIRESTORE_WAKE_CHAT_TITLE] = chatTitle
                    preferences[KEY_CUSTOM_WAKE_WORD_PHRASE] = resolvedPhrase
                    preferences[KEY_CUSTOM_WAKE_WORD_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_WAKE_WORD_DETECTION_SENSITIVITY] = coercedSensitivity
                    preferences[KEY_CUSTOM_WAKE_WORD_UPDATED_AT_MS] = now
                }
            }
        }

        _selectedWakeTargetChatIdState.value = localChatId.takeIf { it > 0L }
        _selectedWakeTargetDocIdState.value = remoteDocId.takeIf { it.isNotBlank() }
        _customWakeWordState.value = resolvedPhrase
        _wakeWordSensitivityState.value = coercedSensitivity
        _customWakeWordConfigState.value = CustomWakeWordDataStoreConfig(
            phrase = resolvedPhrase,
            sensitivity = coercedSensitivity,
            targetChatId = localChatId.takeIf { it > 0L },
            screenOffBatterySaverEnabled = _screenOffBatterySaverState.value,
            updatedAtEpochMs = now
        )

        AppSettingsManager.saveCustomWakeWordPhrase(
            context = appContext,
            phrase = resolvedPhrase,
            chatId = localChatId.takeIf { it > 0L },
            sensitivity = coercedSensitivity
        )
        com.example.voice.LocalKeywordSpotterEngine.syncConfiguration(
            customWakePhrase = resolvedPhrase,
            sensitivityThreshold = coercedSensitivity,
            targetChatId = localChatId.takeIf { it > 0L },
            targetChatTitle = chatTitle
        )
        return localChatId
    }

    /**
     * Resets the custom wake word in Jetpack [DataStore] back to [DEFAULT_CUSTOM_WAKE_WORD] ("Hey Gemini").
     */
    suspend fun resetCustomWakeWord(context: Context): String {
        return saveCustomWakeWord(
            context = context,
            phrase = DEFAULT_CUSTOM_WAKE_WORD,
            chatId = null,
            sensitivity = DEFAULT_WAKE_WORD_SENSITIVITY
        )
    }
}


