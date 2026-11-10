# Implementation Plan: Multiplayer Presence & Friend Status System (Adventure Engine)

## 1. Overview
We will implement a real-time **Multiplayer Presence & Friend Status System** for the Adventure Engine that synchronizes the player's RPG progression (`level`, `completedQuestCount`), active chat context (`currentActiveChatTitle`), and session timers with Firestore (`presence/{uid}`) and a local Room cache, while enforcing one-tap **"Always Offline" (Stealth Mode)** privacy and rendering a pinned Party & Friend Status header with expandable `FriendPresenceCard` items in `GameDrawerSheet.kt`.

---

## 2. Data Model & Persistence (`com.example.game.PlayerPresence`)

### 2.1 `PlayerPresence` Model (`/app/src/main/java/com/example/game/PlayerPresence.kt`)
- Create `com.example.game.PlayerPresence` (also annotated as a Room `@Entity(tableName = "player_presence_cache")` with `@PrimaryKey val odinUid: String`):
  - `odinUid: String` — Player's unique identifier (Firebase Auth UID or local fallback adventurer UID).
  - `displayName: String` — Player's display name or adventurer handle.
  - `level: Int` — Synced from local `AdventureGameState.characterLevel`.
  - `completedQuestCount: Int` — Synced from local `AdventureGameState.quests.count { it.isCompleted }`.
  - `currentActiveChatTitle: String` — Active chat session title (forced to `"Offline"` when `isStealthMode == true` or `!isOnline`).
  - `isOnline: Boolean` — Whether the user is actively online (forced to `false` when `isStealthMode == true`).
  - `isStealthMode: Boolean` — Whether Stealth Mode ("Always Offline") is enabled.
  - `sessionStartedEpochMs: Long` — Epoch timestamp when the current online session started (`0L` when in Stealth Mode or offline).
  - `lastHeartbeatEpochMs: Long` — Epoch timestamp of the latest presence heartbeat.
- Helper methods on `PlayerPresence`:
  - `fun isEffectivelyOnline(nowEpochMs: Long = System.currentTimeMillis(), timeoutMs: Long = 120_000L): Boolean` — Returns `isOnline && !isStealthMode && (nowEpochMs - lastHeartbeatEpochMs) <= timeoutMs`.
  - `fun formattedElapsedSessionTime(nowEpochMs: Long = System.currentTimeMillis()): String` — Computes `"Online for Xh Ym"` (or `"Online for Xm"` / `"Offline"` when not effectively online or when `isStealthMode == true`).
  - `fun sanitizedForStealthOrBroadcast(): PlayerPresence` — Returns a copy where if `isStealthMode` is `true`, `isOnline = false`, `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L`.
  - Firestore serialization helpers (`toFirestoreMap()` and `fromFirestoreDoc(doc)`).

### 2.2 Room & Firestore Schema + Security Rules
- **Room Database (`AppDatabase.kt` & `PlayerPresenceDao`)**:
  - Add `PlayerPresenceDao` to query, upsert, and observe cached `PlayerPresence` rows (`observeAllPresence()`, `upsertPresence(...)`, `upsertAllPresence(...)`, `getPresenceByUidSync(odinUid)`).
- **Firestore Blueprint (`firebase-blueprint.json`) & Security Rules (`firestore.rules`)**:
  - Add `PlayerPresenceDoc` schema at `/presence/{uid}`.
  - Allow signed-in users (`isSignedIn()`) to `get` and `list` `/presence/{uid}` for the live global lobby and friend status cards.
  - Allow only `isOwner(uid)` to `create`, `update`, and `delete` `/presence/{uid}`, validating all required fields (`odinUid == uid`, `displayName`, `level >= 1`, `completedQuestCount >= 0`, `currentActiveChatTitle`, `isOnline`, `isStealthMode`, `sessionStartedEpochMs >= 0`, `lastHeartbeatEpochMs >= 0`) and enforcing that when `isStealthMode == true`, `isOnline == false` and `currentActiveChatTitle == 'Offline'`.

---

## 3. Privacy / "Always Offline" (Stealth Mode) & Presence Heartbeat Coordinator

### 3.1 `AppSettingsManager` Stealth Mode & Pinned Friend UIDs (`/app/src/main/java/com/example/util/AppSettingsManager.kt`)
- Add preference keys:
  - `KEY_ADVENTURE_STEALTH_MODE = "adventure_stealth_mode_enabled"`
  - `KEY_PINNED_FRIEND_UIDS = "adventure_pinned_friend_uids"`
- Expose reactive `StateFlow`s & mutators:
  - `isStealthMode: StateFlow<Boolean>`
  - `setStealthModeEnabled(context: Context, enabled: Boolean): Boolean`
  - `toggleStealthMode(context: Context): Boolean`
  - `pinnedFriendUids: StateFlow<Set<String>>`
  - `addPinnedFriendUid(context: Context, odinUid: String)` / `removePinnedFriendUid(context: Context, odinUid: String)`

### 3.2 `PlayerPresenceRepository` & 30s Heartbeat Loop (`/app/src/main/java/com/example/game/PlayerPresence.kt`)
- Synchronizes local `PlayerPresence` (derived from `AdventureGameState.characterLevel`, completed quests count, active chat title, and `AppSettingsManager.isStealthMode`) to both Room (`player_presence_cache`) and Firestore (`presence/{uid}`).
- **Stealth Mode Privacy Enforcement**:
  - Whenever `isStealthMode == true`, `buildLocalPlayerPresence(...)` and `pushPresenceToFirestore(...)` strictly set `isOnline = false`, `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L`, ignoring live session timers.
- **Heartbeat & Lifecycle Hooks**:
  - Runs a 30-second coroutine heartbeat while the app is in the foreground and signed in.
  - Triggers an immediate presence push whenever `level`, `completedQuestCount`, `currentActiveChatTitle`, or `isStealthMode` changes.
  - Marks `isOnline = false` on foreground pause/stop or sign-out, and treats any remote presence document with `now - lastHeartbeatEpochMs > 120_000L` (2 minutes) as offline.

---

## 4. Friend Status Drawer UI (`GameDrawerSheet.kt` & `FriendPresenceCard`)

### 4.1 Pinned Party Header & Stealth Toggle in `GameDrawerSheet.kt`
- Add a pinned **Multiplayer Party & Presence Banner** below the top controls bar in `GameDrawerSheet.kt`:
  - Shows the local player's own status pill (`Online` green dot vs. `Always Offline (Stealth)` gray dot), current `Lv. X • Y Quests`, and an **"Always Offline" eye icon button** (`adventure_stealth_mode_toggle_button`) for one-tap privacy control.
  - Displays a live counter of online friends/adventurers (`"X Online"`), an expand/collapse toggle (`adventure_friend_drawer_expand_button`) to view the full party/lobby list, and an optional quick **"Add Friend by UID"** input row (`adventure_add_friend_uid_input`, `adventure_add_friend_uid_button`).

### 4.2 `FriendPresenceCard` Composable
- Create `FriendPresenceCard` (`friend_presence_card_<odinUid>`) displaying:
  - **Online Status Dot**: Glowing Emerald Green (`#10B981`) for effectively online players, Neutral Slate Gray (`#94A3B8`) for Offline or Stealth players (`friend_presence_status_dot_<odinUid>`).
  - **Player Identity & Badges**: `displayName`, **Level Badge** (`Lv. <level>`), and **Total Quests Completed Counter** (`<completedQuestCount> Quests`).
  - **Active Chat Context & Session Timer**:
    - Shows `"In: <currentActiveChatTitle>"` and `"Online for Xh Ym"` (or `"Online for Xm"`) when online.
    - Shows `"In: Offline"` and `"Offline"` when offline or in Stealth Mode.
  - **Header / Card Eye Toggle**: Quick `"Always Offline"` eye toggle icon on the local player's card/header (`friend_presence_stealth_toggle`) and a pin/unpin toggle for fellow adventurers.

---

## 5. Verification & Error Checking Plan
1. Add unit & Robolectric tests verifying:
   - `PlayerPresence` model fields, `isEffectivelyOnline` 2-minute timeout, and `formattedElapsedSessionTime`.
   - `isStealthMode = true` persists in `AppSettingsManager` and forces `isOnline = false`, `currentActiveChatTitle = "Offline"`, and `sessionStartedEpochMs = 0L` before Room/Firestore sync.
   - Firestore `/presence/{uid}` security rules validation in `FirebaseSyncRepositoryRuleTest`.
2. Run `compile_applet` and `gradle :app:testDebugUnitTest` sequentially, fix any discovered issues, re-scan until 0 errors remain, and output the complete Errors & Fixes log in chat.
