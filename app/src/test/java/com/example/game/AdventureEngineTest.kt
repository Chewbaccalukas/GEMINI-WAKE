package com.example.game

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.ui.MainViewModel
import com.example.voice.VoiceCommandAction
import com.example.voice.VoiceCommandDispatcher
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdventureEngineTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppDatabase.clearInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppDatabase.clearInstanceForTesting()
    }

    @Test
    fun proceduralLoot_isDeterministicForSeed_andProducesValidRarityColors() {
        val itemA1 = AdventureEngine.generateProceduralLoot(424242L)
        val itemA2 = AdventureEngine.generateProceduralLoot(424242L)
        assertEquals("Same seed must produce identical loot name", itemA1.name, itemA2.name)
        assertEquals("Same seed must produce identical rarity hex", itemA1.rarityColorHex, itemA2.rarityColorHex)
        assertEquals("Same seed must produce identical quantity", itemA1.quantity, itemA2.quantity)

        val validHexes = setOf(
            InventoryItem.RARITY_COMMON_HEX,
            InventoryItem.RARITY_RARE_HEX,
            InventoryItem.RARITY_EPIC_HEX,
            InventoryItem.RARITY_LEGENDARY_HEX
        )
        for (seed in 1L..50L) {
            val item = AdventureEngine.generateProceduralLoot(seed * 9973L)
            assertTrue("Generated item name must not be blank", item.name.isNotBlank())
            assertTrue(
                "Generated rarity hex ${item.rarityColorHex} must be one of the 4 rarity tiers",
                item.rarityColorHex in validHexes
            )
        }
    }

    @Test
    fun inlineMarkers_parseQuestAddQuestCompleteAndLootDrop_andMutateAdventureStateWithAutoSave() = runBlocking {
        val db = AppDatabase.buildInMemory(app)
        val dao = db.adventureGameDao()

        val rawResponse = """
            You step through the shimmering portal into the Vault of Echoes.
            [QUEST_ADD: {"id":"quest_echo_vault","title":"Seal the Echo Breach","description":"Stabilize the three harmonic pylons.","xpReward":350}]
            [QUEST_COMPLETE: {"id":"quest_astral_beacon","title":"Awaken the Astral Beacon"}]
            [LOOT_DROP: {"seed":777888}]
        """.trimIndent()

        val parsed = AdventureEngine.parseInlineMarkers(rawResponse)
        assertFalse("Cleaned prose must strip inline marker tags", parsed.cleanedText.contains("[QUEST_ADD"))
        assertEquals("Expected 3 parsed inline RPG events", 3, parsed.events.size)

        val initialState = AdventureGameState(chatId = 10L, isAdventureModeEnabled = true)
        val (updatedState, questCompleted) = AdventureEngine.applyInlineEventsToState(initialState, parsed.events)

        assertTrue("Completing quest_astral_beacon must set questCompleted = true", questCompleted)
        assertTrue(
            "New quest quest_echo_vault must be added to quests list",
            updatedState.quests.any { it.id == "quest_echo_vault" && it.xpReward == 350 }
        )
        assertTrue(
            "quest_astral_beacon must be marked completed",
            updatedState.quests.first { it.id == "quest_astral_beacon" }.isCompleted
        )
        assertTrue(
            "Total XP must increase by quest_astral_beacon reward (250 XP)",
            updatedState.totalXp >= initialState.totalXp + 250
        )
        assertTrue(
            "Procedural loot from seed 777888 must be added to inventory",
            updatedState.inventory.size > initialState.inventory.size
        )

        // Verify Room DAO persistence & round-trip restoration
        dao.upsertState(updatedState.toEntity(targetChatId = 10L))
        val loadedEntity = dao.getStateForChatSync(10L)
        assertNotNull(loadedEntity)
        val restored = loadedEntity!!.toDomainState()
        assertEquals(updatedState.totalXp, restored.totalXp)
        assertEquals(updatedState.quests.size, restored.quests.size)
        assertEquals(updatedState.inventory.size, restored.inventory.size)
        db.close()
    }

    @Test
    fun documentLoreExtraction_andSystemPromptInjection_andSaveSentinel20Minutes() {
        val viewModel = MainViewModel(app)
        try {
            // 1. Create a temporary Markdown lore file and attach it to Adventure
            val loreFile = File(app.cacheDir, "elder_star_lore.md")
            loreFile.writeText("# The Elder Star Concordat\nThe Citadel of Vaelor is guarded by the Chrono-Wardens.")
            val loreUri = Uri.fromFile(loreFile)

            viewModel.attachDocumentToAdventure(loreUri, "text/markdown")
            Thread.sleep(150L)
            shadowOf(Looper.getMainLooper()).idle()

            val stateAfterLore = viewModel.adventureState.value
            assertTrue(
                "Attaching lore document must inject text into GameSettings.loreContext",
                stateAfterLore.settings.loreContext.contains("Citadel of Vaelor")
            )

            // 2. Verify Game Master System Prompt includes difficulty, quests, inventory, and lore
            val gmPrompt = AdventureEngine.buildGameMasterSystemPrompt(
                baseSystemInstruction = "You are Gemini.",
                state = stateAfterLore.copy(isAdventureModeEnabled = true)
            )
            assertTrue(gmPrompt.contains("GEMINI'S INFINITE ADVENTURE ENGINE"))
            assertTrue(gmPrompt.contains("Citadel of Vaelor"))
            assertTrue(gmPrompt.contains("[QUEST_ADD:"))
            assertTrue(gmPrompt.contains("[LOOT_DROP:"))

            // 3. Verify 20-minute Save Sentinel triggers snackbar state after 1200+ seconds
            assertFalse(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            viewModel.advanceAdventurePlayTimerSecondsForTest(1205L)
            assertTrue(
                "20+ minutes of unsaved play must activate Save Sentinel alert",
                viewModel.adventureState.value.isSaveSentinelAlertVisible
            )

            // Manual save resets unsaved timer and clears Save Sentinel alert
            viewModel.saveAdventureGameManually()
            assertFalse(
                "Manual save must clear Save Sentinel alert",
                viewModel.adventureState.value.isSaveSentinelAlertVisible
            )
            assertEquals(0L, viewModel.adventureState.value.unsavedPlaySeconds)
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun voiceCommandDispatcher_parsesAndExecutesAdventureDrawerAndSaveCommands() {
        val viewModel = MainViewModel(app)
        try {
            val dispatcher = viewModel.voiceCommandDispatcher

            // 1. "open inventory" / "show inventory"
            val openInv = dispatcher.parseCommand("Hey Gemini open inventory")
            assertEquals(VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.INVENTORY), openInv)
            assertTrue(viewModel.executeInChatVoiceActionCommand("open inventory"))
            assertTrue(viewModel.adventureState.value.isDrawerOpen)
            assertEquals(AdventureDrawerTab.INVENTORY, viewModel.adventureState.value.activeDrawerTab)

            // 2. "check quests" / "show quests"
            val checkQuests = dispatcher.parseCommand("check quests")
            assertEquals(VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.QUESTS), checkQuests)
            assertTrue(viewModel.executeInChatVoiceActionCommand("show quests"))
            assertTrue(viewModel.adventureState.value.isDrawerOpen)
            assertEquals(AdventureDrawerTab.QUESTS, viewModel.adventureState.value.activeDrawerTab)

            // 3. "save game" / "save adventure"
            viewModel.advanceAdventurePlayTimerSecondsForTest(1300L)
            assertTrue(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            val saveAction = dispatcher.parseCommand("save adventure")
            assertEquals(VoiceCommandAction.SaveAdventureGame, saveAction)
            assertTrue(viewModel.executeInChatVoiceActionCommand("save game"))
            assertFalse(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            assertTrue(viewModel.adventureState.value.lastSavedAt > 0L)

            // 4. "close drawer"
            val closeAction = dispatcher.parseCommand("close drawer")
            assertEquals(VoiceCommandAction.CloseAdventureDrawer, closeAction)
            assertTrue(viewModel.executeInChatVoiceActionCommand("close drawer"))
            assertFalse(viewModel.adventureState.value.isDrawerOpen)
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun questFilteringToggle_andVisualRarityIndicatorSystem_workAsExpected() = runBlocking {
        // 1. Verify RarityTier color mapping and pulsing aura behavior (Common: Grey, Rare: Blue, Epic: Purple, Legendary: Orange)
        val commonItem = InventoryItem(
            id = "item_common",
            name = "Iron Ration",
            category = "Consumable",
            iconName = "Bolt",
            rarityColorHex = InventoryItem.RARITY_COMMON_HEX,
            description = "Standard ration.",
            quantity = 3
        )
        val rareItem = commonItem.copy(id = "item_rare", rarityColorHex = InventoryItem.RARITY_RARE_HEX)
        val epicItem = commonItem.copy(id = "item_epic", rarityColorHex = InventoryItem.RARITY_EPIC_HEX)
        val legendaryItem = commonItem.copy(id = "item_legendary", rarityColorHex = InventoryItem.RARITY_LEGENDARY_HEX)

        assertEquals(RarityTier.COMMON, commonItem.rarityTier)
        assertFalse("Common items must use static glow (hasPulsingAura = false)", commonItem.rarityTier.hasPulsingAura)
        assertEquals("#94A3B8", commonItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.RARE, rareItem.rarityTier)
        assertFalse("Rare items must use static glow (hasPulsingAura = false)", rareItem.rarityTier.hasPulsingAura)
        assertEquals("#38BDF8", rareItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.EPIC, epicItem.rarityTier)
        assertTrue("Epic items must use pulsing aura (hasPulsingAura = true)", epicItem.rarityTier.hasPulsingAura)
        assertEquals("#C084FC", epicItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.LEGENDARY, legendaryItem.rarityTier)
        assertTrue("Legendary items must use pulsing aura (hasPulsingAura = true)", legendaryItem.rarityTier.hasPulsingAura)
        assertEquals("#F59E0B", legendaryItem.rarityTier.primaryColorHex)

        // 2. Verify QuestFilterMode ('All', 'Active', 'Completed') filtering and Room persistence
        val viewModel = MainViewModel(app)
        try {
            viewModel.addCustomAdventureQuest("Explore the Sunken Spire", "Locate the ancient astrolabe.", 300)
            val initialQuests = viewModel.adventureState.value.quests
            val completedBefore = viewModel.adventureState.value.completedQuestCount
            assertTrue("Expected at least 3 quests", initialQuests.size >= 3)

            // Mark the newly added active quest completed
            val targetQuestId = initialQuests.first { !it.isCompleted }.id
            viewModel.toggleAdventureQuestCompleted(targetQuestId)

            val stateAfterComplete = viewModel.adventureState.value
            val totalCount = stateAfterComplete.quests.size
            val completedCount = stateAfterComplete.completedQuestCount
            val activeCount = stateAfterComplete.activeQuestCount
            assertEquals(completedBefore + 1, completedCount)
            assertEquals(totalCount - completedCount, activeCount)

            // Filter by ALL
            viewModel.setAdventureQuestFilter(QuestFilterMode.ALL)
            assertEquals(QuestFilterMode.ALL, viewModel.adventureState.value.questFilter)
            assertEquals(totalCount, viewModel.adventureState.value.filteredQuests.size)

            // Filter by ACTIVE
            viewModel.setAdventureQuestFilter(QuestFilterMode.ACTIVE)
            assertEquals(QuestFilterMode.ACTIVE, viewModel.adventureState.value.questFilter)
            assertEquals(activeCount, viewModel.adventureState.value.filteredQuests.size)
            assertTrue(viewModel.adventureState.value.filteredQuests.none { it.isCompleted })

            // Filter by COMPLETED
            viewModel.setAdventureQuestFilter(QuestFilterMode.COMPLETED)
            assertEquals(QuestFilterMode.COMPLETED, viewModel.adventureState.value.questFilter)
            assertEquals(completedCount, viewModel.adventureState.value.filteredQuests.size)
            assertTrue(viewModel.adventureState.value.filteredQuests.all { it.isCompleted })

            // Verify GameSettings JSON serialization & Room entity round-trip preserves questFilter
            val entity = viewModel.adventureState.value.toEntity(targetChatId = 77L)
            val restoredState = entity.toDomainState()
            assertEquals(QuestFilterMode.COMPLETED, restoredState.questFilter)
            assertEquals(completedCount, restoredState.filteredQuests.size)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun questEnhancements_chimeAndHaptics_multiStepProgress_dailyChallengeRarePlus_sortDropdown_andShareImageSummary() {
        val viewModel = MainViewModel(app)
        try {
            val addedCountBefore = AdventureEngine.questAddedFeedbackCount
            val completedCountBefore = AdventureEngine.questCompletedFeedbackCount

            // 1. Add a 3-step HIGH priority quest -> triggers subtle chime + haptic feedback
            viewModel.addCustomAdventureQuest(
                title = "Calibrate the Quantum Relay",
                description = "Align all 3 phase emitters across the spire.",
                xpReward = 400,
                totalSteps = 3,
                priority = QuestPriority.HIGH
            )
            assertTrue(
                "Adding a quest must trigger subtle chime + haptic vibration feedback",
                AdventureEngine.questAddedFeedbackCount > addedCountBefore
            )

            val addedQuest = viewModel.adventureState.value.quests.first { it.title == "Calibrate the Quantum Relay" }
            assertEquals(0, addedQuest.currentStep)
            assertEquals(3, addedQuest.totalSteps)
            assertEquals(0, addedQuest.completionPercent)
            assertEquals(QuestPriority.HIGH, addedQuest.priority)

            // 2. Advance step manually via +1 Step button -> Step 1/3 (33%)
            viewModel.incrementAdventureQuestStep(addedQuest.id, stepDelta = 1)
            val afterStep1 = viewModel.adventureState.value.quests.first { it.id == addedQuest.id }
            assertEquals(1, afterStep1.currentStep)
            assertEquals(33, afterStep1.completionPercent)
            assertFalse(afterStep1.isCompleted)

            // Advance remaining 2 steps via inline AI marker [QUEST_STEP: ...] -> auto-completes at Step 3/3 (100%)
            val markerText = """
                The final two emitters lock into resonance!
                [QUEST_STEP: {"id":"${addedQuest.id}","stepDelta":2}]
            """.trimIndent()
            val parsedStep = AdventureEngine.parseInlineMarkers(markerText)
            assertEquals(1, parsedStep.events.size)
            viewModel.applyInlineAdventureEvents(parsedStep.events)

            val afterStep3 = viewModel.adventureState.value.quests.first { it.id == addedQuest.id }
            assertEquals(3, afterStep3.currentStep)
            assertEquals(100, afterStep3.completionPercent)
            assertTrue("Reaching final step must auto-complete the quest", afterStep3.isCompleted)
            assertTrue(
                "Completing a multi-step quest must trigger completion chime + haptic feedback",
                AdventureEngine.questCompletedFeedbackCount > completedCountBefore
            )

            // 3. Daily Challenge ("Quest of the Day") generation & guaranteed Rare+ loot reward
            val dailyQuest = viewModel.ensureDailyChallengeQuest(
                interactionHistoryOverride = listOf("Starlight Citadel", "Chrono Warden Protocol", "Void Relic")
            )
            assertTrue(dailyQuest.isDailyChallenge)
            assertTrue(dailyQuest.title.startsWith("Quest of the Day:"))
            val invSizeBeforeDaily = viewModel.adventureState.value.inventory.size
            val xpBeforeDaily = viewModel.adventureState.value.totalXp

            // Complete the daily challenge
            viewModel.toggleAdventureQuestCompleted(dailyQuest.id)
            val stateAfterDaily = viewModel.adventureState.value
            assertTrue(
                "Completing Quest of the Day must award bonus XP",
                stateAfterDaily.totalXp >= xpBeforeDaily + dailyQuest.xpReward
            )
            assertTrue(
                "Completing Quest of the Day must grant a guaranteed Rare/Epic/Legendary loot item",
                stateAfterDaily.inventory.size >= invSizeBeforeDaily
            )
            val topLoot = stateAfterDaily.inventory.first()
            assertTrue(
                "Daily Challenge reward loot must be Rare, Epic, or Legendary (never Common)",
                topLoot.rarityTier in setOf(RarityTier.RARE, RarityTier.EPIC, RarityTier.LEGENDARY)
            )

            // 4. Sort Dropdown Menu ('Newest', 'Oldest', 'High Priority')
            viewModel.setAdventureQuestFilter(QuestFilterMode.ALL)
            viewModel.setAdventureQuestSortOrder(QuestSortOrder.NEWEST)
            val newestSorted = viewModel.adventureState.value.filteredQuests
            assertTrue(newestSorted.first().createdAt >= newestSorted.last().createdAt)

            viewModel.setAdventureQuestSortOrder(QuestSortOrder.OLDEST)
            val oldestSorted = viewModel.adventureState.value.filteredQuests
            assertTrue(oldestSorted.first().createdAt <= oldestSorted.last().createdAt)

            viewModel.setAdventureQuestSortOrder(QuestSortOrder.HIGH_PRIORITY)
            val prioritySorted = viewModel.adventureState.value.filteredQuests
            assertTrue(prioritySorted.first().priority.sortWeight >= prioritySorted.last().priority.sortWeight)

            // 5. Export & Share Active Quest Progress as Image Summary PNG
            val exportedPng = viewModel.shareAdventureQuestProgressImage(app)
            assertNotNull("Exported quest progress summary PNG must not be null", exportedPng)
            assertTrue("Exported PNG file must exist and be non-empty", exportedPng!!.exists() && exportedPng.length() > 0L)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun questSearchBar_andOfflineRoomQuestHistoryQueueAutoSync_andConnectivityBadge() {
        val viewModel = MainViewModel(app)
        try {
            // 1. Verify Online connectivity indicator badge default state
            viewModel.setAdventureNetworkOnlineForTest(true)
            assertTrue(viewModel.adventureState.value.isOnline)
            assertEquals(AdventureSyncStatus.SYNCED, viewModel.adventureState.value.syncStatus)
            assertEquals("Online • Synced", viewModel.adventureState.value.connectivityBadgeLabel)

            // 2. Simulate going OFFLINE and performing quest mutations (add quest, step update, daily challenge)
            viewModel.setAdventureNetworkOnlineForTest(false)
            assertFalse(viewModel.adventureState.value.isOnline)
            assertEquals(AdventureSyncStatus.PENDING_OFFLINE, viewModel.adventureState.value.syncStatus)
            assertTrue(viewModel.adventureState.value.connectivityBadgeLabel.startsWith("Offline • Queued"))

            viewModel.addCustomAdventureQuest(
                title = "Decrypt the Starlight Vault",
                description = "Use the obsidian keycard in the sunken archive.",
                xpReward = 350,
                totalSteps = 2,
                priority = QuestPriority.HIGH
            )
            val vaultQuest = viewModel.adventureState.value.quests.first { it.title == "Decrypt the Starlight Vault" }
            viewModel.incrementAdventureQuestStep(vaultQuest.id, stepDelta = 1)
            val dailyQuest = viewModel.ensureDailyChallengeQuest(
                dateKeyOverride = "2026-11-09",
                interactionHistoryOverride = listOf("Nebula Sanctum")
            )

            // Verify offline mutations are queued with PENDING_OFFLINE in state and questHistory
            val offlineState = viewModel.adventureState.value
            assertFalse(offlineState.isOnline)
            assertEquals(AdventureSyncStatus.PENDING_OFFLINE, offlineState.syncStatus)
            assertTrue("Offline mutations must increment pendingOfflineSyncCount", offlineState.pendingOfflineSyncCount >= 3)
            assertEquals(
                "Offline • Queued (${offlineState.pendingOfflineSyncCount})",
                offlineState.connectivityBadgeLabel
            )
            assertTrue(
                "Offline quest history log must record PENDING_OFFLINE entries",
                offlineState.questHistory.any {
                    it.questId == vaultQuest.id && it.syncStatus == AdventureSyncStatus.PENDING_OFFLINE.name
                }
            )

            // 3. Verify Multi-Field Quest Search Bar (Title, Description, Priority, and Daily Badge)
            viewModel.setAdventureQuestFilter(QuestFilterMode.ALL)

            // Search by Title keyword
            viewModel.setAdventureQuestSearchQuery("Starlight Vault")
            assertEquals(1, viewModel.adventureState.value.filteredQuests.size)
            assertEquals(vaultQuest.id, viewModel.adventureState.value.filteredQuests.first().id)

            // Search by Description keyword
            viewModel.setAdventureQuestSearchQuery("obsidian keycard")
            assertEquals(1, viewModel.adventureState.value.filteredQuests.size)
            assertEquals(vaultQuest.id, viewModel.adventureState.value.filteredQuests.first().id)

            // Search by Priority badge / name
            viewModel.setAdventureQuestSearchQuery("HIGH")
            assertTrue(
                "Searching 'HIGH' must return only HIGH priority quests",
                viewModel.adventureState.value.filteredQuests.isNotEmpty() &&
                    viewModel.adventureState.value.filteredQuests.all { it.priority == QuestPriority.HIGH }
            )

            // Search by Daily Challenge badge keyword ("daily" / "quest of the day")
            viewModel.setAdventureQuestSearchQuery("daily")
            assertTrue(
                "Searching 'daily' must match the Daily Challenge quest",
                viewModel.adventureState.value.filteredQuests.any { it.id == dailyQuest.id && it.isDailyChallenge }
            )

            // Clear search query -> restores full quest list
            viewModel.setAdventureQuestSearchQuery("")
            assertEquals(viewModel.adventureState.value.quests.size, viewModel.adventureState.value.filteredQuests.size)

            // 4. Transition back to ONLINE -> verify queued offline changes auto-sync
            viewModel.setAdventureNetworkOnlineForTest(true)
            val deadline = System.currentTimeMillis() + 3_000L
            while (viewModel.adventureState.value.pendingOfflineSyncCount > 0 && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25L)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val syncedState = viewModel.adventureState.value
            assertTrue(syncedState.isOnline)
            assertEquals(0, syncedState.pendingOfflineSyncCount)
            assertEquals(AdventureSyncStatus.SYNCED, syncedState.syncStatus)
            assertEquals("Online • Synced", syncedState.connectivityBadgeLabel)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun testLowPowerModeDisablesWakeWordListenerPreservesUiMicAndCalculatesBatteryHours() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.example.util.AppSettingsManager.setLowPowerModeEnabled(app, false)
        val viewModel = MainViewModel(app)
        try {
            viewModel.setPermissionGrantedOverrideForTest(true)
            viewModel.onWindowFocusChanged(true)

            // 1. Verify UI_MIC_HARDWARE_CLEAR_DELAY_MS is 500L
            assertEquals(500L, viewModel.UI_MIC_HARDWARE_CLEAR_DELAY_MS)

            // 2. Verify dynamic battery drain calculation when wake-word listener is active vs disabled
            val activeWakeEstimate = viewModel.refreshBatteryDrainEstimate(
                batteryPercentOverride = 80,
                measuredCurrentMaOverride = 220f
            )
            assertFalse(activeWakeEstimate.lowPowerModeEnabled)
            assertTrue(activeWakeEstimate.formattedLabel.startsWith("Estimated Hours Remaining:"))

            // 3. Enable Low Power Mode -> background wake-word listener is disabled, estimated hours remaining increases
            viewModel.setLowPowerModeEnabled(true)
            assertTrue(viewModel.lowPowerModeEnabled.value)

            val lowPowerEstimate = viewModel.refreshBatteryDrainEstimate(
                batteryPercentOverride = 80,
                measuredCurrentMaOverride = 152f
            )
            assertTrue(lowPowerEstimate.lowPowerModeEnabled)
            assertTrue(
                "Disabling wake-word listener in Low Power Mode must extend estimated hours remaining",
                lowPowerEstimate.estimatedHoursRemaining > activeWakeEstimate.estimatedHoursRemaining
            )
            assertTrue(lowPowerEstimate.bonusHoursSaved > 0f)
            assertTrue(lowPowerEstimate.formattedLabel.contains("Estimated Hours Remaining:"))
            assertTrue(lowPowerEstimate.formattedLabel.contains("saved"))

            // 4. Attempting to start continuous background wake-word listener in Low Power Mode is blocked
            viewModel.startContinuousWakeWordListening(userInitiated = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(
                "Constant background wake-word listener must remain disabled in Low Power Mode",
                viewModel.wakeWordManager.isListening.value
            )

            // 5. UI microphone button (toggleInChatDictation / toggleInternalDictation) remains functional
            val uiMicStarted = viewModel.toggleInChatDictation()
            assertTrue("UI microphone button must remain functional in Low Power Mode", uiMicStarted)
            assertTrue(viewModel.isInChatDictationActive.value)

            // Stop UI mic dictation -> background wake listener still stays off
            viewModel.toggleInChatDictation()
            assertFalse(viewModel.isInChatDictationActive.value)
            assertFalse(viewModel.wakeWordManager.isListening.value)
        } finally {
            com.example.util.AppSettingsManager.setLowPowerModeEnabled(app, false)
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun testGlobalOfflineQuestQueueingToggleTopBarSyncModeAndHapticFeedback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.example.util.AppSettingsManager.setOfflineQuestQueueingEnabled(app, false)
        val viewModel = MainViewModel(app)
        try {
            viewModel.setAdventureNetworkOnlineForTest(true)
            viewModel.setOfflineQuestQueueingEnabled(false, triggerHaptic = false)
            assertEquals("Online Sync", viewModel.adventureState.value.topBarSyncModeLabel)

            // 1. Switch global toggle to Offline Cache mode -> verify top bar status & subtle haptic feedback
            val hapticsBefore = AdventureEngine.syncModeSwitchHapticCount
            viewModel.toggleOfflineQuestQueueingMode()
            assertTrue(viewModel.offlineQuestQueueingEnabled.value)
            assertTrue(viewModel.adventureState.value.offlineQueueingModeEnabled)
            assertEquals("Offline Cache", viewModel.adventureState.value.topBarSyncModeLabel)
            assertEquals(hapticsBefore + 1, AdventureEngine.syncModeSwitchHapticCount)

            // 2. Mutate quest progress while in Offline Cache mode (even though network is online)
            //    -> verify quest progress is cached in Room queue (PENDING_OFFLINE) for later synchronization
            viewModel.addCustomAdventureQuest(
                title = "Cache the Astral Beacon",
                description = "Store telemetry in the offline Room queue.",
                xpReward = 250,
                totalSteps = 2,
                priority = QuestPriority.HIGH
            )
            val cachedState = viewModel.adventureState.value
            assertEquals(AdventureSyncStatus.PENDING_OFFLINE, cachedState.syncStatus)
            assertTrue(cachedState.pendingOfflineSyncCount >= 1)
            assertTrue(cachedState.connectivityBadgeLabel.startsWith("Offline • Queued"))

            // 3. Switch global toggle back to Online Sync mode -> verify haptic feedback & immediate Room queue flush
            val hapticsBeforeOnline = AdventureEngine.syncModeSwitchHapticCount
            viewModel.toggleOfflineQuestQueueingMode()
            assertFalse(viewModel.offlineQuestQueueingEnabled.value)
            assertEquals("Online Sync", viewModel.adventureState.value.topBarSyncModeLabel)
            assertEquals(hapticsBeforeOnline + 1, AdventureEngine.syncModeSwitchHapticCount)

            val deadline = System.currentTimeMillis() + 3_000L
            while (viewModel.adventureState.value.pendingOfflineSyncCount > 0 && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25L)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val syncedState = viewModel.adventureState.value
            assertEquals(0, syncedState.pendingOfflineSyncCount)
            assertEquals(AdventureSyncStatus.SYNCED, syncedState.syncStatus)
            assertEquals("Online • Synced", syncedState.connectivityBadgeLabel)
        } finally {
            com.example.util.AppSettingsManager.setOfflineQuestQueueingEnabled(app, false)
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

