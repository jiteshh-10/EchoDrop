# EchoDrop Whole UI Revamp

Date: 2026-03-30
Branch Context: uirevamp (working state consolidated)
Status: Implemented, built, unit-tested, instrumented-tested, documented

## 1. Objective

This document is a deep technical and product record of the complete EchoDrop UI revamp and supporting logic hardening completed up to this point. It is intended as a long-term reference for:

- future feature development
- onboarding new contributors
- regression auditing
- architecture reviews
- QA traceability

The revamp scope included visual system modernization, onboarding flow correction, permissions UX hardening, settings expansion, notification behavior updates, and storage-cap controls.

## 2. Change Snapshot

Current uncommitted revamp set (at documentation time):

- Files changed: 46
- Net content shift: 2140 insertions, 1534 deletions

Core affected domains:

- Navigation and startup flow
- Onboarding, How It Works, Permissions screens
- Home and chat interaction polish
- Settings capabilities and persistence
- Notification behavior (incoming + outgoing)
- Storage cap policy and enforcement path coverage
- Theme tokens and reusable UI styles

## 3. Product Flow and UX Changes

## 3.1 Launch and First-Run Flow

First-run path was aligned to the intended sequence:

- Splash
- Onboarding
- How It Works
- Permissions
- Home

Implementation detail:

- Onboarding primary CTA now routes to How It Works (not directly to Permissions).
- How It Works onboarding CTA now routes to Permissions.
- Entering Home clears onboarding stack path to avoid return to setup screens.

Primary files:

- app/src/main/java/com/dev/echodrop/screens/OnboardingConsentFragment.java
- app/src/main/java/com/dev/echodrop/screens/HowItWorksFragment.java
- app/src/main/java/com/dev/echodrop/screens/PermissionsFragment.java
- app/src/main/java/com/dev/echodrop/MainActivity.java

## 3.2 Splash and Branding Consistency

- Splash icon geometry was centered symmetrically for more stable cross-device visual centering.
- Theme splash pipeline remains rooted in Theme.SplashScreen and post-splash app theme transition.

Primary resources:

- app/src/main/res/drawable/ic_splash_logo.xml
- app/src/main/res/values/themes.xml

## 3.3 How It Works Screen (Image-Matched Content Revamp)

The How It Works screen was rebuilt into a fully vertical, scrollable, sectioned format that mirrors the provided reference structure while maintaining the app design language:

- Hero icon block
- Delay-Tolerant Messaging headline and explainer
- The Process card with numbered steps
- Key Features vertical card stack
- Use Cases vertical card stack
- Technical Implementation card with implementation bullets

Design and behavior characteristics:

- Scrollable content in a single vertical read path
- Existing app typography and card styling reused
- Existing icon family reused where feasible
- CTA footer retained for context-aware navigation (back from settings, permissions from onboarding path)

Primary files:

- app/src/main/res/layout/screen_how_it_works.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/drawable/bg_icon_circle_surface.xml
- app/src/main/java/com/dev/echodrop/screens/HowItWorksFragment.java

## 3.4 Permissions UX Hardening

Permissions flow was upgraded to production-oriented behavior:

- Request only currently missing runtime permissions.
- Separate required permissions (BLE/location path) from optional notification permission.
- Retry dialog for recoverable denial.
- App settings dialog for permanent denial.
- Resume check to continue flow if permissions were enabled in settings.
- Start service only after required permissions are satisfied.

Primary file:

- app/src/main/java/com/dev/echodrop/screens/PermissionsFragment.java

## 3.5 Home Screen Cleanup

- Top broadcast toolbar icon removed (as requested).
- Bottom-left mini message/chat FAB removed (as requested).
- Associated fragment logic cleaned so removed UI controls no longer have orphan handlers.

Primary files:

- app/src/main/res/menu/home_menu.xml
- app/src/main/res/layout/screen_home_inbox.xml
- app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java

## 3.6 Settings Expansion

Settings was expanded while preserving existing rows and behavior:

Operation section:

- Background sync toggle retained and continues to control service lifecycle.
- Incoming alerts toggle added and fully wired.

Storage section:

- Storage cap card with persisted MB value and slider controls added.
- Slider applies policy and triggers cap enforcement immediately.

About/other sections:

- Existing battery guide, how-it-works route, diagnostics, rooms, blocked device controls, and version tap developer shortcut remain intact.

Primary files:

- app/src/main/res/layout/screen_settings.xml
- app/src/main/java/com/dev/echodrop/screens/SettingsFragment.java
- app/src/main/res/values/strings.xml

## 4. Notification Architecture Updates

## 4.1 Incoming Notification Governance

Incoming message notifications now respect user settings:

- Incoming alert toggle state is persisted.
- Notification posting path checks both app-level preference and platform-level permission availability before posting.

Primary file:

- app/src/main/java/com/dev/echodrop/transfer/BundleReceiver.java

## 4.2 Outgoing Notification Behavior (Broadcast and Room)

Local post actions now trigger immediate user-visible notifications (WhatsApp/Instagram style expectation for local send feedback):

- Broadcast post success -> outgoing broadcast notification.
- Chat send success -> outgoing room message notification (chat-aware tap target extras).

New helper centralizes channel and posting rules:

- app/src/main/java/com/dev/echodrop/util/MessageNotificationHelper.java

Integration points:

- app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java
- app/src/main/java/com/dev/echodrop/repository/ChatRepo.java

## 4.3 Channel and Permission Practices

- Notification channel creation is idempotent.
- Runtime POST_NOTIFICATIONS handled for API 33+.
- System-level app notifications disabled state handled from settings with route to system notification settings.

## 5. Storage Cap Architecture and Enforcement

## 5.1 New User Setting

Storage cap is now user-configurable and persisted:

- Preference defaults: 60 MB
- Range: 20 MB to 200 MB
- Step: 10 MB

Primary file:

- app/src/main/java/com/dev/echodrop/util/AppPreferences.java

## 5.2 Byte-Estimate Strategy

DAO now exposes approximate local bundle footprint:

- content text length
- key metadata lengths (hash/scope/origin/seen_by/sender alias)
- fixed per-row overhead estimate

Primary file:

- app/src/main/java/com/dev/echodrop/db/MessageDao.java

## 5.3 Enforcement Policy

Enforcement remains aligned with existing priority safety model:

- Evict BULK first
- Then evict NORMAL
- Preserve ALERT as long as possible

New manager:

- app/src/main/java/com/dev/echodrop/util/MessageStorageCapManager.java

## 5.4 Full Insert-Path Coverage

Critical hardening: cap enforcement was attached to all major insert entry points, not just one repository path.

Covered paths:

- MessageRepo insert flow
- EchoService GATT receive path
- BundleReceiver receive path
- ChatRepo outgoing DTN chat-bundle path

Primary files:

- app/src/main/java/com/dev/echodrop/repository/MessageRepo.java
- app/src/main/java/com/dev/echodrop/service/EchoService.java
- app/src/main/java/com/dev/echodrop/transfer/BundleReceiver.java
- app/src/main/java/com/dev/echodrop/repository/ChatRepo.java

## 6. Design System and Resource Modernization

The revamp includes broad resource-level polishing and consistency updates.

Token and style files:

- app/src/main/res/values/colors.xml
- app/src/main/res/values-night/colors.xml
- app/src/main/res/values/dimens.xml
- app/src/main/res/values/styles.xml
- app/src/main/res/values/themes.xml
- app/src/main/res/values/strings.xml

Drawable/system component refinements include:

- card surfaces and border consistency
- chat bubble and input visuals
- icon holder and info note surfaces
- search input and focus state styling
- send button treatment

Representative files:

- app/src/main/res/drawable/bg_stat_card.xml
- app/src/main/res/drawable/bg_bubble_incoming.xml
- app/src/main/res/drawable/bg_bubble_outgoing.xml
- app/src/main/res/drawable/bg_chat_input.xml
- app/src/main/res/drawable/bg_search_input.xml
- app/src/main/res/drawable/bg_search_input_focused.xml
- app/src/main/res/drawable/bg_send_button.xml

## 7. Screen-Level Resource Coverage

Layouts adjusted as part of the revamp:

- app/src/main/res/layout/screen_onboarding_consent.xml
- app/src/main/res/layout/screen_permissions.xml
- app/src/main/res/layout/screen_how_it_works.xml
- app/src/main/res/layout/screen_home_inbox.xml
- app/src/main/res/layout/screen_settings.xml
- app/src/main/res/layout/screen_battery_guide.xml
- app/src/main/res/layout/screen_chat_list.xml
- app/src/main/res/layout/screen_create_chat.xml
- app/src/main/res/layout/screen_chat_conversation.xml
- app/src/main/res/layout/screen_diagnostics.xml
- app/src/main/res/layout/screen_discovery_status.xml
- app/src/main/res/layout/fragment_post_composer.xml
- app/src/main/res/layout/fragment_message_detail.xml
- app/src/main/res/layout/item_message_card.xml
- app/src/main/res/layout/item_chat_list.xml
- app/src/main/res/layout/item_chat_message_incoming.xml
- app/src/main/res/layout/item_chat_message_outgoing.xml

## 8. Software Architecture Practices Applied

This revamp intentionally follows production-grade engineering practices:

1. Single-responsibility helper classes.

- Preferences and notification logic extracted into dedicated utility classes.
- Storage cap enforcement separated from UI and repository orchestration.

2. Centralized policy enforcement.

- Storage policy enforced from all ingress points to avoid path-specific data drift.

3. Runtime permission correctness.

- Only missing permissions requested.
- Permanent denial and rationale states are explicitly handled.

4. Lifecycle-safe UI wiring.

- Fragment view binding patterns retained.
- Settings state refreshed in onResume for post-settings-return correctness.

5. Defensive platform compatibility.

- API-level checks for notification runtime permission.
- System notification disabled state addressed.

6. Navigation contract clarity.

- First-run sequence is deterministic.
- Home transition clears setup back stack to prevent accidental regressions.

7. Build/test gate discipline.

- Regression test failures were fixed before final validation.

## 9. Test and Validation Record

Commands executed in this revamp cycle:

1. Build + unit tests

- .\\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
- Initial run outcome: 3 failures in DesignSystemTest (tab label string expectations)
- Fix applied: tab string resources changed to title case (All, Alerts, Chats)
- Re-run outcome: SUCCESS

2. Instrumented tests

- .\\gradlew.bat :app:connectedDebugAndroidTest
- Outcome: SUCCESS
- Device/emulator output: 1 test on Pixel_8a_API_35-ext14(AVD) passed

Validation status:

- AssembleDebug: PASS
- Unit tests: PASS
- Connected Android tests: PASS

## 10. Regression Fixes During Validation

Issue observed:

- DesignSystemTest expected title-case tab labels.

Resolution:

- Updated string resources:
  - tab_all -> All
  - tab_alerts -> Alerts
  - tab_chats -> Chats

File:

- app/src/main/res/values/strings.xml

## 11. File Inventory by Layer

Java/Kotlin logic files in this consolidated revamp:

- app/src/main/java/com/dev/echodrop/MainActivity.java
- app/src/main/java/com/dev/echodrop/db/MessageDao.java
- app/src/main/java/com/dev/echodrop/repository/ChatRepo.java
- app/src/main/java/com/dev/echodrop/repository/MessageRepo.java
- app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java
- app/src/main/java/com/dev/echodrop/screens/HowItWorksFragment.java
- app/src/main/java/com/dev/echodrop/screens/OnboardingConsentFragment.java
- app/src/main/java/com/dev/echodrop/screens/PermissionsFragment.java
- app/src/main/java/com/dev/echodrop/screens/SettingsFragment.java
- app/src/main/java/com/dev/echodrop/service/EchoService.java
- app/src/main/java/com/dev/echodrop/transfer/BundleReceiver.java
- app/src/main/java/com/dev/echodrop/util/AppPreferences.java
- app/src/main/java/com/dev/echodrop/util/MessageNotificationHelper.java
- app/src/main/java/com/dev/echodrop/util/MessageStorageCapManager.java

Resource files in this consolidated revamp:

- app/src/main/res/drawable/bg_bubble_incoming.xml
- app/src/main/res/drawable/bg_bubble_outgoing.xml
- app/src/main/res/drawable/bg_chat_avatar.xml
- app/src/main/res/drawable/bg_chat_input.xml
- app/src/main/res/drawable/bg_icon_holder.xml
- app/src/main/res/drawable/bg_icon_circle_surface.xml
- app/src/main/res/drawable/bg_info_note.xml
- app/src/main/res/drawable/bg_oem_card.xml
- app/src/main/res/drawable/bg_search_input.xml
- app/src/main/res/drawable/bg_search_input_focused.xml
- app/src/main/res/drawable/bg_send_button.xml
- app/src/main/res/drawable/bg_stat_card.xml
- app/src/main/res/layout/fragment_message_detail.xml
- app/src/main/res/layout/fragment_post_composer.xml
- app/src/main/res/layout/item_chat_list.xml
- app/src/main/res/layout/item_chat_message_incoming.xml
- app/src/main/res/layout/item_chat_message_outgoing.xml
- app/src/main/res/layout/item_message_card.xml
- app/src/main/res/layout/screen_battery_guide.xml
- app/src/main/res/layout/screen_chat_conversation.xml
- app/src/main/res/layout/screen_chat_list.xml
- app/src/main/res/layout/screen_create_chat.xml
- app/src/main/res/layout/screen_diagnostics.xml
- app/src/main/res/layout/screen_discovery_status.xml
- app/src/main/res/layout/screen_home_inbox.xml
- app/src/main/res/layout/screen_how_it_works.xml
- app/src/main/res/layout/screen_onboarding_consent.xml
- app/src/main/res/layout/screen_permissions.xml
- app/src/main/res/layout/screen_settings.xml
- app/src/main/res/menu/home_menu.xml
- app/src/main/res/values-night/colors.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values/dimens.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/styles.xml
- app/src/main/res/values/themes.xml

## 12. Known Constraints and Follow-Ups

1. Storage-cap estimate is approximate (string-length based), not exact file-size accounting at SQLite page level.

2. Notification style currently provides immediate post feedback; if future product direction prefers only remote-delivery notifications, toggle strategy can be adjusted in MessageNotificationHelper.

3. Additional instrumentation depth is still recommended for multi-device relay/notification scenarios under OEM background policies.

## 13. Suggested Future Enhancements

1. Add dedicated UI/instrumentation tests for:

- settings incoming alerts toggle behavior by API level
- storage cap slider persistence and immediate trimming behavior
- onboarding route assertions for first-run flow
- how-it-works content contract snapshot tests

2. Add repository-level tests for MessageStorageCapManager byte-threshold behavior.

3. Add analytics-safe internal diagnostics counters for:

- capped deletions by priority class
- notifications suppressed by user toggle
- notifications suppressed by platform permission state

## 14. Post-Revamp Addendum (Mar 30, 2026)

After the primary UI revamp was stabilized, a targeted curation and moderation expansion was added:

1. Message detail action stack now follows Save, Report, then Got it/Dismiss.

2. Saved flow is now persisted and navigable:

- Added boolean `saved` persistence to message storage.
- Added Saved list screen with toolbar entry and empty-state handling.
- Added repository and ViewModel read/write paths for saved-message state.

3. Report flow now integrates directly with existing moderation controls:

- Report blocks the message `origin` through `BlockedDeviceStore`.
- Local messages from the same origin are removed immediately.
- Existing unblock management remains centralized in Settings.

4. Branding consistency was extended via shared animated app-bar logo utility:

- Home toolbar
- Message detail toolbar
- Saved screen toolbar

Primary files for this addendum:

- app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java
- app/src/main/java/com/dev/echodrop/screens/SavedMessagesFragment.java
- app/src/main/java/com/dev/echodrop/db/MessageEntity.java
- app/src/main/java/com/dev/echodrop/db/MessageDao.java
- app/src/main/java/com/dev/echodrop/repository/MessageRepo.java
- app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java
- app/src/main/java/com/dev/echodrop/util/ToolbarLogoAnimator.java
- app/src/main/res/layout/fragment_message_detail.xml
- app/src/main/res/layout/screen_saved_messages.xml

## 15. Final Note

This document intentionally captures both UX-facing and infrastructure-facing revamp details. It should be treated as the canonical reference for this revamp checkpoint and used alongside:

- doc/ARCHITECTURE.md
- doc/TEST_REPORT.md
- doc/PROJECT_STATUS_MAR30_2026.md

for historical continuity.
