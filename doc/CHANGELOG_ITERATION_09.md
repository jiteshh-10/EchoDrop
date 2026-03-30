# Changelog — Iteration 09: Stability, UX Polish & Demo Readiness

> Historical scope note: this file documents Iteration 09 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

**Branch:** `iteration-9`  
**Date:** 2025-01-20  
**Tests:** 446 passing, 0 failures  

---

## Summary

No new features. This iteration hardens the app for demo readiness: UX polish
(typography, light mode, 8dp grid, pill badges), three bug fixes, stability
improvements (Timber, StrictMode, ProGuard), and documentation deliverables.

---

## UX Polish

### Typography
- Added `TextAppearance.EchoDrop.Display` (24sp/600) for onboarding headlines
- Added `TextAppearance.EchoDrop.Button` (14sp/500) for button labels
- Added `TextAppearance.EchoDrop.Badge` (11sp/500) for pill badges
- Updated `H1` to use `sans-serif-medium` (weight 500)
- Updated `H2` to use `sans-serif-medium` (weight 500)
- Fonts mapped: DM Sans → `sans-serif`, JetBrains Mono → `monospace`
- Global font applied via theme: `android:fontFamily=sans-serif`

### Light Mode + Dark Mode
- `values/colors.xml` now contains light mode palette (bg=#F4F5F7, card=#FFFFFF)
- Created `values-night/colors.xml` with dark mode overrides (bg=#0A0C0F, card=#111318)
- `values/themes.xml` changed to `Theme.Material3.Light.NoActionBar`
- `values-night/themes.xml` keeps `Theme.Material3.Dark.NoActionBar`
- Added color tokens: `echo_primary_border_tint`, `echo_alert_border_tint`, `echo_positive_border_tint`, `echo_btn_secondary_border`, `echo_white`

### 8dp Grid Spacing
- Added `spacing_5` (40dp), `spacing_6` (48dp)
- Changed `min_touch_target` 44dp → 48dp
- Changed `list_item_padding_vertical` 12dp → 16dp
- Changed `card_corner_radius` 10dp → 12dp
- Changed `input_corner_radius` 8dp → 12dp
- Changed `chat_input_height` 44dp → 48dp
- Changed `stat_card_size` 150dp → 152dp (divisible by 8)
- Changed `oem_card_padding` 14dp → 16dp
- Changed `step_indent` 12dp → 16dp
- Added `badge_height` (22dp), `badge_padding_h` (8dp)
- Added `button_height_primary` (52dp), `button_height_ghost` (44dp), `button_corner_radius` (12dp)
- Added `icon_size_default` (24dp), `icon_size_small` (16dp)
- Added `appbar_border_height` (1dp), `bottom_sheet_corner` (20dp)
- Added `input_border_width` (1dp), `input_border_focused` (2dp)

### Refined Components
- Badge drawables updated to pill shape (`badge_corner_radius` → 100dp)
- Badge stroke colors changed from hardcoded hex to color resources
- Badges now have fixed height (22dp) and horizontal padding (8dp)
- Search input focused border changed to 2dp (`input_border_focused`)
- Icon holder radius changed to use `card_corner_radius`
- Developer badge corner changed to pill shape (100dp)
- Added three button styles: `Widget.EchoDrop.Button.Primary`, `.Secondary`, `.Ghost`
- Added Snackbar style: `Widget.EchoDrop.Snackbar`

### App Icon
- Replaced default Android robot icon with EchoDrop 3-arc echo motif
- Background: dark surface (#0A0C0F)
- Foreground: origin dot + 3 concentric arcs in primary accent (#7C9EBF)
- Arcs fade from 100% → 70% → 40% opacity
- Monochrome variant uses same foreground

---

## Bug Fixes

| # | Bug | Fix |
|---|-----|-----|
| 1 | TTL countdown on message cards never refreshed after initial bind | Added periodic TTL refresh (60s interval) in HomeInboxFragment via `notifyDataSetChanged()` |
| 2 | _Reserved for scope display if symptoms reproduced_ | Scope label mapping verified correct (LOCAL→Nearby, ZONE→This area, EVENT→This event) |
| 3 | Left-side navigation icon on home toolbar was non-functional | Removed `setNavigationIcon` and `setNavigationContentDescription` calls |

---

## Stability & Power Management

### Timber Logging
- Added `com.jakewharton.timber:timber:5.0.1` dependency
- Initialized `Timber.DebugTree` in `MainActivity.onCreate()` for debug builds
- Replaced all `Log.i/e/w(TAG, ...)` with `Timber.i/e/w(...)` in:
  - `EchoService.java` (14 call sites)
  - `BootReceiver.java` (2 call sites)
- ProGuard strips `Timber.d()` and `Timber.v()` in release builds

### StrictMode
- Enabled `ThreadPolicy` (disk reads/writes, network) in debug builds
- Enabled `VmPolicy` (leaked SQLite, leaked Closeable) in debug builds
- Penalty: log only (no crash) to catch issues during development

### ProGuard / R8
- Enabled `minifyEnabled true` + `shrinkResources true` for release builds
- Added keep rules for: Room entities, BLE callbacks, WorkManager workers, services/receivers, transfer protocol, models
- Added `Timber` log stripping for release
- Preserved source file + line numbers for crash reports

### Build Configuration
- Enabled `buildConfig true` in `buildFeatures` (required for AGP 8.x)

---

## Accessibility

### Strings
- Added 14 accessibility strings (`a11y_*`) for contentDescription and announcements
- Format strings include: `a11y_badge_scope`, `a11y_ttl_remaining`, `a11y_sync_status`, `a11y_message_card`, `a11y_chat_item`, `a11y_device_count`

### Error Snackbars
- Added 5 error state strings: `error_ble_unavailable`, `error_permissions_missing`, `error_message_expired`, `error_post_empty`, `error_chat_code_invalid`

---

## Documentation

- Created `doc/DEMO_SCRIPT.md` — 10-minute walkthrough with 6 acts
- Created `doc/KNOWN_LIMITATIONS.md` — 21 documented limitations across networking, storage, security, UX, platform, and testing
- Created `doc/CHANGELOG_ITERATION_09.md` (this file)

---

## Files Changed

### New Files (3)
- `app/src/main/res/values-night/colors.xml`
- `doc/DEMO_SCRIPT.md`
- `doc/KNOWN_LIMITATIONS.md`

### Modified Files (17)
- `app/build.gradle` — Timber dep, minifyEnabled, shrinkResources, buildConfig
- `app/proguard-rules.pro` — Room, BLE, WorkManager, Timber keep rules
- `app/src/main/res/values/colors.xml` — Light mode palette + new tokens
- `app/src/main/res/values/themes.xml` — Material3.Light, global font
- `app/src/main/res/values-night/themes.xml` — Global font added
- `app/src/main/res/values/styles.xml` — New type scale, button/badge/snackbar styles
- `app/src/main/res/values/dimens.xml` — 8dp grid, new dimensions
- `app/src/main/res/values/strings.xml` — Error + accessibility strings
- `app/src/main/res/drawable/bg_badge_primary.xml` — Pill shape, resource colors
- `app/src/main/res/drawable/bg_badge_alert.xml` — Pill shape, resource colors
- `app/src/main/res/drawable/bg_badge_positive.xml` — Pill shape, resource colors
- `app/src/main/res/drawable/bg_search_input_focused.xml` — 2dp focused border
- `app/src/main/res/drawable/bg_icon_holder.xml` — card_corner_radius
- `app/src/main/res/drawable/ic_launcher_background.xml` — Dark surface
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — 3-arc echo motif
- `app/src/main/java/com/dev/echodrop/MainActivity.java` — Timber init, StrictMode
- `app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java` — TTL refresh, icon removal
- `app/src/main/java/com/dev/echodrop/service/EchoService.java` — Timber logging
- `app/src/main/java/com/dev/echodrop/service/BootReceiver.java` — Timber logging

---

## Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | System fonts (sans-serif/monospace) instead of bundled TTF | Downloadable Google Fonts caused crash (Bug #1, iter 0-1). System fonts are zero-risk. |
| D2 | `values/` = light, `values-night/` = dark | Standard Android night qualifier convention. |
| D3 | Periodic adapter refresh (60s) for TTL instead of custom view | Minimal code change; DiffUtil prevents unnecessary rebinds. |
| D4 | Remove left nav icon rather than add function | The icon had no designed purpose; removing is cleaner than inventing a function. |
| D5 | Timber init in Activity (not Application) | No custom Application class exists; init in first Activity is sufficient. |
| D6 | Log-only StrictMode penalty | Crash penalty would break demo; log penalty catches issues for dev review. |
| D7 | ProGuard strips Timber.d/v but keeps i/w/e | Release builds still have info/warning/error logs for crash diagnostics. |
| D8 | 100dp badge corner radius for pill shape | Android clips corners up to half-height; 100dp ensures pill on any badge height. |

---

## Post-Iteration Follow-up Note

This file is intentionally scoped to Iteration 09 only.

Subsequent additions (Mar 30, 2026) including:
- Message Detail Save/Report/Got-it action stack
- Saved messages persistence and dedicated Saved screen
- Animated toolbar bulb branding utility

are documented in:
- `doc/CHANGELOG_ITERATION_10.md`
- `CHANGELOG.md`
- `doc/PROJECT_STATUS_MAR30_2026.md`
