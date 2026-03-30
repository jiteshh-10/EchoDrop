# EchoDrop Changelog

## [main] - 2026-03-30

### Summary
This release adds message curation capabilities and app-bar branding polish while preserving the existing BLE/GATT and DTN relay core.

### User-facing changes
- Added animated bulb logo in the top-left app bar on Home, Message Detail, and Saved screens.
- Message Detail bottom actions are now ordered as Save, Report, then Got it/Dismiss.
- Added Saved messages screen, reachable from Home toolbar.
- Save now persists at message level and can be toggled (save/unsave).
- Report now blocks the sender origin device ID and removes all local messages from that origin.

### Data and architecture updates
- Added saved state on messages (`saved` boolean in Room entity).
- Added DAO operations for curation and moderation:
  - `getSavedMessages(now)`
  - `setSaved(messageId, saved)`
  - `deleteByOrigin(originId)`
- Repository and ViewModel now expose saved-message data flow.
- Room database version bumped to 7 (destructive migration policy unchanged).

### Existing moderation integration
- Report action uses existing `BlockedDeviceStore`.
- Existing Settings unblock flow remains the source of truth for blocked origins.

### Key files
| File | Change |
|------|--------|
| `app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java` | Save/Report/Got it behavior and moderation hooks |
| `app/src/main/java/com/dev/echodrop/screens/SavedMessagesFragment.java` | New saved-message UI screen |
| `app/src/main/java/com/dev/echodrop/db/MessageEntity.java` | Added persisted `saved` flag |
| `app/src/main/java/com/dev/echodrop/db/MessageDao.java` | Added saved list, save toggle, delete-by-origin queries |
| `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java` | Added saved/reported operations |
| `app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java` | Added `savedMessages` LiveData |
| `app/src/main/java/com/dev/echodrop/util/ToolbarLogoAnimator.java` | New shared toolbar logo animation utility |
| `app/src/main/res/layout/fragment_message_detail.xml` | Save + Report actions above primary dismiss action |
| `app/src/main/res/layout/screen_saved_messages.xml` | New saved list layout |
| `app/src/main/res/drawable/anim_toolbar_logo.xml` | Animated bulb icon frames |

### Validation
- `:app:assembleDebug` -> PASS
- `:app:testDebugUnitTest` -> PASS

---

## [debug -> main] - 2026-02-27

### Summary
Major reliability and UX hardening for Wi-Fi Direct transport, notification deep-linking, and onboarding behavior.

### Highlights
- Stabilized Wi-Fi Direct state machine with timeout, cooldown, and stale-group handling.
- Improved bidirectional transfer reliability and orchestration gating.
- Added notification deep links and robust pending-intent handling.
- Hardened onboarding and permission handling flows.

### Validation
- Unit suite passed in that cycle (historical baseline for this branch merge).