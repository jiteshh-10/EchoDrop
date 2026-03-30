# EchoDrop Architecture

Last updated: 2026-03-30

This document describes the current production architecture of EchoDrop after the Mar 30, 2026 feature cycle.

---

## 1. Architectural Summary

EchoDrop uses a single-activity Android architecture with fragment-based screens, repository-backed data access, and Room persistence.

Primary design goals:
- work offline
- tolerate intermittent connectivity
- prevent relay loops and duplicate storage
- keep moderation and curation local-first

At a glance:
- Activity host: `MainActivity`
- UI: Fragments + RecyclerView adapters + ViewBinding
- State: ViewModels with LiveData
- Data: Repositories over Room DAO
- Runtime: foreground service + transfer/discovery layers

---

## 2. Layered System Model

### 2.1 View layer
- Fragments render state and emit user intents.
- Adapters handle list diffing and card-level presentation.
- Toolbar menu actions route to Saved, Settings, and detail screens.

### 2.2 State layer
- `MessageViewModel` exposes:
  - active message stream
  - saved message stream
  - alert count stream
- Screen logic remains lifecycle-aware through LiveData observation.

### 2.3 Data layer
- `MessageRepo` coordinates message insert/read/update/delete and policy hooks.
- `ChatRepo` coordinates encrypted room messaging and chat-bundle processing.
- Repo methods encapsulate threading via executor-backed DAO calls.

### 2.4 Persistence layer
- Room schema is authoritative for message and chat state.
- `AppDatabase` version is 7.
- Migration strategy remains destructive in pre-release (`fallbackToDestructiveMigration`).

### 2.5 Runtime transport layer
- BLE and transfer components support proximity exchange and DTN behavior.
- Relay metadata (`origin`, `hop_count`, `seen_by_ids`, `ttl_ms`) controls forwarding.
- Runtime filtering applies blocked-origin constraints.

---

## 3. Data Architecture (Room)

### 3.1 Message entity responsibilities

`MessageEntity` combines transport and UI concerns intentionally for local-first operation.

Critical fields:
- identity: `id`, `content_hash`
- message body: `text`
- visibility semantics: `scope`, `priority`
- lifecycle: `created_at`, `expires_at`, `ttl_ms`, `read`
- relay safety: `origin`, `hop_count`, `seen_by_ids`
- chat transport compatibility: `type`, `scope_id`, `sender_alias`
- user curation: `saved`

### 3.2 DAO contracts that shape behavior

`MessageDao` key queries:
- `getActiveMessages(now)` for inbox (non-chat feed)
- `getSavedMessages(now)` for saved screen
- `setSaved(messageId, saved)` for curation toggle
- `deleteByOrigin(originId)` for report/moderation cleanup
- dedup and storage-support queries (`findByContentHash`, cap helpers)

### 3.3 Persistence guarantees
- Dedup remains hash-driven at insert boundary.
- Saved state is persisted, not transient UI state.
- Report cleanup is local and immediate through origin-based deletion.

---

## 4. Core Runtime Flows

### 4.1 Broadcast send path
1. User composes message from UI.
2. ViewModel delegates insert to repository.
3. Repo normalizes metadata (origin/ttl defaults as needed).
4. DAO inserts if not duplicate.
5. LiveData updates inbox/saved observers.

### 4.2 Receive and relay path
1. Runtime transport receives bundles.
2. Message metadata validated and dedup applied.
3. Blocked-origin filter drops disallowed content.
4. Accepted messages are persisted.
5. Relay-forwarding logic evaluates hop limit, seen-by, TTL, and scope.

### 4.3 TTL and cleanup
- Runtime/UI display uses TTL-derived progress.
- Cleanup workers and repository cleanup paths remove expired rows.

---

## 5. Curation and Moderation Architecture (Mar 30 additions)

### 5.1 Save behavior
Source: Message Detail action.

Flow:
1. Detail screen toggles local save state.
2. Repository writes `saved` flag through DAO.
3. Saved screen observes `getSavedMessages(now)` and updates reactively.

Properties:
- deterministic per-message persistence
- reversible (`save`/`unsave`)
- no transport-side behavior change

### 5.2 Report behavior
Source: Message Detail action.

Flow:
1. Extract `origin` from current message.
2. Add origin to `BlockedDeviceStore`.
3. Delete local messages by origin (`deleteByOrigin`).
4. Return to previous screen.

Properties:
- moderation is local-first and immediate
- existing unblock path in Settings remains authoritative
- runtime receive filters continue honoring blocked origins

### 5.3 Saved screen
- New `SavedMessagesFragment` reuses `MessageAdapter` for consistency.
- Empty state communicates when no saved content exists.
- Toolbar includes same animated branding pattern as Home and Detail.

---

## 6. UI and Navigation Contracts

### 6.1 Activity role
`MainActivity` remains the navigation orchestrator for screen transitions:
- onboarding, permissions, how-it-works
- inbox/detail/saved
- settings and diagnostics surfaces
- chat/room flows

### 6.2 Onboarding contract (current)
- Primary CTA from onboarding routes to permissions.
- Secondary CTA routes to how-it-works.
- How-it-works onboarding CTA routes to permissions.
- Permissions completion (or defer path) routes to home.

### 6.3 Home contract
- Toolbar actions: Saved, Settings.
- Message tap: detail.
- Detail actions: Save, Report, Got it.

---

## 7. Branding and Shared UI Utilities

`ToolbarLogoAnimator` centralizes animated toolbar logo setup.

Why this utility exists:
- avoids duplicated toolbar logo setup code
- ensures consistent animation behavior across screens
- keeps fragment setup methods focused on screen concerns

Current usage:
- Home inbox toolbar
- Message detail toolbar
- Saved messages toolbar

---

## 8. Reliability and Safety Design Decisions

- Repository-centered writes to keep policy enforcement centralized.
- Blocklist persistence in SharedPreferences for immediate availability.
- LiveData for reactive UI updates without manual refresh plumbing.
- Defensive null/empty origin handling in report/moderation path.
- Destructive migration accepted pre-release to optimize delivery speed.

---

## 9. Testing and Validation Alignment

Validated in latest cycle:
- `:app:assembleDebug`
- `:app:testDebugUnitTest`

Architecture-level expectations under test:
- DAO/repo contracts and dedup behavior
- relay metadata integrity
- transfer protocol stability
- chat-bundle processing compatibility

---

## 10. Known Architectural Constraints

- Room schema migration is destructive in current pre-release mode.
- Saved/moderation state is local to app installation lifecycle.
- Physical-device variability (OEM background policies) still affects long-run mesh reliability.
- Offline design intentionally avoids server reconciliation semantics.

---

## 11. Extension Points

Recommended next architecture increments:
1. Add non-destructive Room migrations for schema evolution.
2. Add targeted tests for Save/Report user journeys at integration level.
3. Expand moderation model to optional reason codes and local audit timestamps.
4. Introduce optional export-safe diagnostics snapshot format for QA automation.

---

## 12. Key Source Map

Core files:
- `app/src/main/java/com/dev/echodrop/MainActivity.java`
- `app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/SavedMessagesFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/SettingsFragment.java`
- `app/src/main/java/com/dev/echodrop/db/MessageEntity.java`
- `app/src/main/java/com/dev/echodrop/db/MessageDao.java`
- `app/src/main/java/com/dev/echodrop/db/AppDatabase.java`
- `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java`
- `app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java`
- `app/src/main/java/com/dev/echodrop/util/BlockedDeviceStore.java`
- `app/src/main/java/com/dev/echodrop/util/ToolbarLogoAnimator.java`