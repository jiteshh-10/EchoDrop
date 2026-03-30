# EchoDrop

Offline, delay-tolerant mesh messaging for Android.

EchoDrop enables nearby devices to exchange short-lived messages without internet. It uses store-carry-forward DTN behavior, expiry-based cleanup, and local-first persistence.

---

## Current Release Snapshot (Mar 30, 2026)

| Item | Value |
|------|-------|
| Package | `com.dev.echodrop` |
| Platform | Android (minSdk 24, targetSdk 35) |
| Language | Java 11 |
| UI stack | AndroidX + Material 3 + ViewBinding |
| Persistence | Room 2.6.1 (`AppDatabase` version 7) |
| Runtime mode | Offline mesh transport with relay-safe local persistence |
| Build status | `:app:assembleDebug` PASS |
| Unit tests | `:app:testDebugUnitTest` PASS |

---

## What EchoDrop Does

EchoDrop is designed for proximity-first messaging where internet may be unavailable or unreliable.

- Broadcast messages propagate through nearby phones.
- Messages include TTL and expire automatically.
- Relay metadata (`origin`, `hop_count`, `seen_by_ids`) prevents loops and supports multi-hop delivery.
- Private room messages are encrypted and can be carried by non-members without decryption.

---

## Key Capabilities

### Messaging and Relay
- Public broadcast feed with scope and priority semantics.
- Priority-aware ordering (`ALERT` > `NORMAL` > `BULK`).
- DTN relay controls with hop limits and dedup.
- TTL cleanup via worker + runtime cleanup paths.

### Private Rooms
- Room-based encrypted messaging.
- Chat bundles sync through the same DTN transport path.
- Sender alias propagation for better room context.

### Curation and Moderation (Latest)
- Message Detail now provides three bottom actions in this exact order:
  1. Save
  2. Report
  3. Got it / Dismiss
- Save persists message state in Room (`messages.saved`) and feeds the Saved screen.
- Report blocks `origin` device ID using `BlockedDeviceStore` and removes local messages from that origin.
- Existing unblock flow in Settings remains authoritative.

### UX and Branding
- Animated top-left bulb logo on Home, Message Detail, and Saved screens.
- Dedicated Saved messages screen with empty-state handling.
- Settings include moderation controls, diagnostics access, and battery guidance.

---

## High-Level Architecture

EchoDrop follows a single-activity, multi-fragment architecture with repository-backed data access.

### Layers
- View: Fragments, adapters, XML layouts, toolbar/menu actions.
- State: ViewModels exposing LiveData.
- Data: Repositories coordinating DAO operations and policy checks.
- Persistence: Room entities/DAO with explicit schema evolution.
- Runtime services: Discovery/transfer orchestration and notification pathways.

### Core data paths
1. Compose/send path
- UI -> ViewModel -> Repo -> DAO/DB insert -> transport pipeline.

2. Receive path
- Transport receiver -> validation/dedup -> DAO insert -> LiveData refresh.

3. Curation path
- Message Detail Save -> `setSaved(messageId, true/false)`.
- Saved screen -> `getSavedMessages(now)`.

4. Moderation path
- Message Detail Report -> `BlockedDeviceStore.addBlockedId(origin)` -> `deleteByOrigin(origin)`.

---

## Current Navigation Flows

### First launch (onboarding intent)
- Onboarding primary CTA -> Permissions
- Onboarding secondary CTA -> How It Works
- How It Works CTA (onboarding mode) -> Permissions
- Permissions success or defer -> Home

### Main app
- Home toolbar:
  - Saved
  - Settings
- Home message tap -> Message Detail
- Message Detail:
  - Save (persist)
  - Report (block origin + remove local origin messages)
  - Got it (delete local message)
- Settings:
  - Block/unblock device IDs
  - Diagnostics and battery guide

---

## Data Model Notes

`MessageEntity` includes fields for transport, presentation, and moderation:

- Identity and payload: `id`, `text`, `content_hash`
- Lifetime and status: `created_at`, `expires_at`, `read`, `saved`
- Routing: `scope`, `priority`, `hop_count`, `ttl_ms`
- Relay safety: `origin`, `seen_by_ids`
- Chat transport: `type`, `scope_id`, `sender_alias`

Room version is currently 7 with destructive migration fallback in pre-release development.

---

## Build and Test

### Build
```bash
./gradlew assembleDebug
```

### Unit tests
```bash
./gradlew :app:testDebugUnitTest
```

### Optional connected tests
```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## Repository Structure (Relevant)

```text
app/src/main/java/com/dev/echodrop/
  MainActivity.java
  screens/
    HomeInboxFragment.java
    MessageDetailFragment.java
    SavedMessagesFragment.java
    SettingsFragment.java
  db/
    MessageEntity.java
    MessageDao.java
    AppDatabase.java
  repository/
    MessageRepo.java
  viewmodels/
    MessageViewModel.java
  util/
    BlockedDeviceStore.java
    ToolbarLogoAnimator.java
```

---

## Documentation Map

### Root
- `CHANGELOG.md` - release-level change history
- `CHANGELOG_DEBUG.md` - debug-branch reliability tranche and merge context

### In `doc/`
- `ARCHITECTURE.md` - implementation architecture and data flow
- `DECISIONS.md` - decision log by iteration/release
- `PROJECT_STATUS_MAR30_2026.md` - current project status snapshot
- `TEST_REPORT.md` - test evidence and historical matrix
- `DEMO_SCRIPT.md` - live demo walkthrough
- `KNOWN_LIMITATIONS.md` - active constraints and risk notes
- `CHANGELOG_ITERATION_*.md` - iteration archives
- `wholeuirevamp.md` - UI revamp record

---

## Release Notes and Historical Scope

- Iteration changelog files in `doc/` are historical records and intentionally preserved.
- Current behavior should be interpreted from:
  - `CHANGELOG.md`
  - `PROJECT_STATUS_MAR30_2026.md`
  - `ARCHITECTURE.md`

---

## License and Privacy Posture

EchoDrop is local-first and ephemeral by design:
- no mandatory account system
- no cloud dependency for core transport
- automatic expiry to reduce long-term data retention