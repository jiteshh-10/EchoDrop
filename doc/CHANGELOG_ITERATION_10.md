# Changelog - Iteration 10: Message Curation, Moderation, and Saved Flow

Branch context: main
Date: 2026-03-30
Validation baseline: assembleDebug PASS, testDebugUnitTest PASS

---

## Summary

Iteration 10 introduces user-facing message curation and moderation controls while preserving the existing mesh relay core:

- Message Detail action order finalized to Save, Report, Got it/Dismiss.
- Saved message persistence added and exposed through a dedicated Saved screen.
- Report action integrated with blocked-origin moderation and local origin cleanup.
- Animated app-bar branding standardized across key screens via shared utility.

---

## User-facing Features

### 1. Message Detail action stack
- Added Save action.
- Added Report action.
- Retained Got it/Dismiss action.
- Enforced explicit order: Save -> Report -> Got it.

### 2. Saved screen
- Added Saved toolbar entry from Home.
- Added `SavedMessagesFragment` with:
  - reactive list from Room-backed LiveData
  - empty state UX
  - detail tap-through behavior

### 3. Report moderation path
- Report now performs:
  1. block sender origin in `BlockedDeviceStore`
  2. delete local messages for that origin
  3. return to previous screen

### 4. Toolbar branding utility
- Added `ToolbarLogoAnimator` and animated bulb drawable sequence.
- Applied to Home, Message Detail, and Saved toolbars.

---

## Data and Architecture Changes

### Room schema
- `MessageEntity` now includes persisted `saved` boolean.
- `AppDatabase` version increased to 7.

### DAO additions (`MessageDao`)
- `getSavedMessages(long now)`
- `setSaved(String messageId, boolean saved)`
- `deleteByOrigin(String originId)`

### Repository additions (`MessageRepo`)
- `getSavedMessages()`
- `setSaved(messageId, saved)`
- `deleteByOrigin(originId)`

### ViewModel additions (`MessageViewModel`)
- `savedMessages` LiveData member
- `getSavedMessages()` accessor

---

## New Files

- `app/src/main/java/com/dev/echodrop/screens/SavedMessagesFragment.java`
- `app/src/main/java/com/dev/echodrop/util/ToolbarLogoAnimator.java`
- `app/src/main/res/layout/screen_saved_messages.xml`
- `app/src/main/res/drawable/anim_toolbar_logo.xml`
- `app/src/main/res/drawable/ic_toolbar_logo_off.xml`
- `app/src/main/res/drawable/ic_toolbar_logo_on.xml`
- `app/src/main/res/drawable/ic_saved.xml`

---

## Modified Files (Key)

- `app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java`
- `app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java`
- `app/src/main/java/com/dev/echodrop/MainActivity.java`
- `app/src/main/java/com/dev/echodrop/db/MessageEntity.java`
- `app/src/main/java/com/dev/echodrop/db/MessageDao.java`
- `app/src/main/java/com/dev/echodrop/db/AppDatabase.java`
- `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java`
- `app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java`
- `app/src/main/res/layout/fragment_message_detail.xml`
- `app/src/main/res/layout/screen_home_inbox.xml`
- `app/src/main/res/menu/home_menu.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/styles.xml`

---

## Design Decisions Applied

1. Persist save state in Room instead of SharedPreferences to keep message lifecycle data in one source.
2. Keep unblock flow centralized in Settings to avoid split moderation ownership.
3. Report by origin ID and local purge to provide immediate moderation effect without backend dependency.
4. Use shared toolbar utility for animated branding consistency across fragments.

---

## Validation

Commands re-run for this iteration:

```bash
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Outcome:
- Build: PASS
- Unit tests: PASS

---

## Follow-up Recommendations

1. Add dedicated integration tests for Save/Report/Saved flows.
2. Add non-destructive migration path before production release.
3. Expand moderation metadata (reason/timestamp) if policy tracking becomes required.

---

## Post-Iteration Hotfix: Broadcast Duplicate Guard (Mar 30, 2026)

Issue addressed:
- Some devices could receive duplicate broadcast entries when self-origin bundles looped back through relay paths.

Fix implemented:
- Added early self-origin drop in GATT receive path before DB insert.
- Added early self-origin drop in socket receive path (`BundleReceiver.processOneMessage`) before dedupe/insert.
- Preserved chat room behavior; guard is applied at transport receive boundaries to prevent local broadcast reinserts.

Files updated:
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`
- `app/src/main/java/com/dev/echodrop/transfer/BundleReceiver.java`

Verification:
- `:app:testDebugUnitTest` -> PASS
- `:app:assembleDebug` -> PASS
