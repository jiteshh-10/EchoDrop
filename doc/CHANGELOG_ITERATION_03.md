# Changelog — Iteration 3: Priority Handling + Inbox Semantics

> Historical scope note: this file documents Iteration 3 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> **Date:** 2026-02-25  
> **Branch:** `iteration-3`  
> **Status:** ✅ Complete  
> **Tests:** 214 total (23 new), 0 failures

---

## Updated Production Files

### 1. `MessageDao.java`
- **Changed:** `getActiveMessages()` query now sorts by priority (`CASE WHEN` — ALERT=0, NORMAL=1, BULK=2 ASC) then `created_at DESC`
- **Added:** `getAlertCount(long now)` — returns `LiveData<Integer>` of non-expired ALERT messages

### 2. `MessageAdapter.java`
- **Changed:** ALERT messages now show `priority_badge_urgent` string with `bg_badge_alert` background drawable
- **Unchanged:** Overall DiffUtil, click listener, and binding patterns

### 3. `MessageDetailFragment.java`
- **Added:** `showUrgentBanner()` — fade in + slide down 4dp over 180ms (FastOutSlowIn)
- **Changed:** `bindPriorityBadge()` — uses `priority_badge_urgent` string, toggles `detail_urgent_banner` visibility
- **Added:** import for `FastOutSlowInInterpolator`

### 4. `PostComposerSheet.java`
- **Added:** `animatePostButtonColor(boolean urgent)` — `ArgbEvaluator` transition between `echo_primary_accent` and `echo_alert_accent` over 180ms
- **Changed:** `setupUrgentToggle()` — calls `animatePostButtonColor()` on toggle change
- **Added:** imports for `ArgbEvaluator`, `ValueAnimator`

### 5. `HomeInboxFragment.java`
- **Added:** `updateAlertBadge(int alertCount)` — reactive badge with crossfade animation (90ms out + 90ms in)
- **Changed:** `setupViewModel()` — observes `viewModel.getAlertCount()` LiveData
- **Changed:** `applyFilters()` — removed manual alert counting loop, removed `updateTabBadges()` call
- **Removed:** `updateTabBadges()` method (replaced by reactive `updateAlertBadge()`)

### 6. `MessageViewModel.java`
- **Added:** `alertCount` field (`LiveData<Integer>`)
- **Added:** `getAlertCount()` method
- **Changed:** Both constructors now initialize `alertCount` from `repo.getAlertCount()`

### 7. `MessageRepo.java`
- **Added:** `getAlertCount()` — delegates to `dao.getAlertCount()`

---

## New Resource Files

### 1. `res/drawable/bg_urgent_banner.xml`
- Layer-list: 4dp left border (`echo_alert_accent`) + fill (`echo_alert_tint`) with 8dp corners

### 2. `res/values/strings.xml` (updated)
- Added: `detail_urgent_banner` ("Urgent message"), `priority_badge_urgent` ("URGENT")

### 3. `res/values/dimens.xml` (updated)
- Added: `priority_border_width` (3dp), `priority_banner_corner` (8dp), `priority_banner_border` (4dp), `priority_banner_icon_size` (20dp)

### 4. `res/layout/item_message_card.xml` (updated)
- `unread_border` width changed from `3dp` hardcoded to `@dimen/priority_border_width`
- `priority_label` now has `bg_badge_alert` background with padding

### 5. `res/layout/fragment_message_detail.xml` (updated)
- Added `detail_urgent_banner` LinearLayout between toolbar_divider and ScrollView
- ScrollView now constrained to `detail_urgent_banner` bottom

---

## New Test Files

### 1. `db/PriorityDaoTest.java` (13 tests)
- Priority sort: ALERT before NORMAL before BULK
- Same-priority sort: newest first
- Alert count: correct count, excludes expired, excludes non-ALERT
- Retention: BULK evicted first, ALERT never evicted

### 2. `adapters/PriorityRenderingTest.java` (10 tests)
- Priority enum values, ordinals, string representations
- Badge visibility rules per priority tier
- Priority immutability verification

---

## Validation Checklist

| Check | ✅/❌ | Note |
|-------|-------|------|
| URGENT appears at top regardless of post time | ✅ | DAO CASE sort verified in PriorityDaoTest |
| Alerts tab count badge reflects correct count | ✅ | LiveData<Integer> from getAlertCount() |
| URGENT message shows red banner in detail screen | ✅ | showUrgentBanner() with fade+slide animation |
| Urgent toggle changes Post button color to red | ✅ | ArgbEvaluator 180ms transition |
| 214 tests pass, 0 failures | ✅ | 12 test classes |
