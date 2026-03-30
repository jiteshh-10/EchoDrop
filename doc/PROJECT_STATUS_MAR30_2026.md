# EchoDrop Implementation Status (Mar 30, 2026)

## Overview
This document summarizes the current state of EchoDrop after the Mar 30, 2026 feature cycle, including message curation, moderation integration, and documentation alignment.

---

## Current Delivery State

### Core transport and relay
- Offline mesh behavior remains active through existing discovery/transfer stack.
- Relay metadata (`origin`, `hop_count`, `seen_by_ids`, `ttl_ms`) remains integrated in message lifecycle.
- Existing blocked-origin receive filters remain active in runtime paths.

### New curation and moderation features
- Message Detail bottom actions now follow:
  1. Save
  2. Report
  3. Got it / Dismiss
- Saved message persistence added at DB level (`messages.saved`).
- New Saved screen added and connected from Home toolbar.
- Report action now:
  - blocks sender origin in `BlockedDeviceStore`
  - removes local messages from that origin
- Existing unblock controls in Settings remain the canonical reversal path.

### UI/branding alignment
- Animated bulb app-bar logo added to Home, Message Detail, and Saved screens.
- Shared toolbar animation behavior centralized in `ToolbarLogoAnimator`.

---

## Data and Schema State

### Room
- `AppDatabase` version: 7
- Migration mode: destructive (pre-release)

### Message table behavior additions
- Added `saved` persistence field.
- Added DAO methods:
  - `getSavedMessages(now)`
  - `setSaved(messageId, saved)`
  - `deleteByOrigin(originId)`

### ViewModel/Repository alignment
- `MessageRepo` and `MessageViewModel` now expose saved-message stream and update operations.

---

## Key Files Updated in Current Cycle

- `app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/SavedMessagesFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java`
- `app/src/main/java/com/dev/echodrop/MainActivity.java`
- `app/src/main/java/com/dev/echodrop/db/MessageEntity.java`
- `app/src/main/java/com/dev/echodrop/db/MessageDao.java`
- `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java`
- `app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java`
- `app/src/main/java/com/dev/echodrop/util/ToolbarLogoAnimator.java`
- `app/src/main/res/layout/fragment_message_detail.xml`
- `app/src/main/res/layout/screen_saved_messages.xml`
- `app/src/main/res/menu/home_menu.xml`

---

## Validation Status

Latest execution in this cycle:
- `:app:assembleDebug` -> PASS
- `:app:testDebugUnitTest` -> PASS

Historical full-cycle context remains in `doc/TEST_REPORT.md`.

---

## Active Risks

1. Destructive Room migration is still enabled and will reset local data on schema changes.
2. OEM background policy differences still require ongoing real-device validation.
3. Moderation remains local-only; no distributed enforcement plane exists.

---

## Recommended Near-Term Follow-ups

1. Add targeted tests for Save/Report/Saved flows.
2. Introduce non-destructive migration strategy before production release.
3. Expand connected-device and OEM matrix for long-run transport reliability.
4. Consider optional moderation audit metadata for local troubleshooting.