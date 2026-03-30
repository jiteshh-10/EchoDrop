# EchoDrop Phased Refactor Progress

## Phase 1 - Static UI and Navigation Cleanup
Date: 2026-03-30
Status: Completed

### Changes applied
- Forced dark mode globally at runtime via AppCompatDelegate.
- Set default base theme to dark parent in main values resources.
- Aligned default color tokens to black/cyan dark palette so style remains consistent even when non-night resources are loaded.

### Files modified
- app/src/main/java/com/dev/echodrop/MainActivity.java
- app/src/main/res/values/themes.xml
- app/src/main/res/values/colors.xml

### Verified behavior intent
- Dark mode is now non-optional.
- Splash and onboarding flow remain intact.
- Existing first-run logic and settings routing are preserved.

### Risks / notes
- No data-model migration needed in Phase 1.
- Existing values-night resources still exist; they now effectively match design direction.

## Next phase
- Phase 2: Replace fixed scope vocabulary with user-defined persistent scope labels and canonicalization.

## Phase 2 - Canonical Scope Labels and Composer Flow
Date: 2026-03-30
Status: Completed

### Changes applied
- Added canonical scope-id codec for safe slug normalization and legacy fallback labels.
- Updated post composer to require custom labels for Zone/Event scope and persist last-used labels locally.
- Stored canonical scope IDs on newly composed broadcast messages.
- Updated inbox and message detail badge rendering to display canonical scope tags (for example `#zone:building-a`).
- Switched detail visibility text to a generic scope-tag format while keeping legacy strings available for compatibility.

### Files modified
- app/src/main/java/com/dev/echodrop/util/ScopeLabelCodec.java
- app/src/main/java/com/dev/echodrop/components/PostComposerSheet.java
- app/src/main/java/com/dev/echodrop/adapters/MessageAdapter.java
- app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java
- app/src/main/res/layout/fragment_post_composer.xml
- app/src/main/res/values/strings.xml

### Verified behavior intent
- Transport compatibility remains intact because canonical labels are carried in existing `scopeId` field.
- Existing legacy bundles/messages with empty `scopeId` still render with deterministic fallback tags.
- Scope enum routing (`LOCAL/ZONE/EVENT`) is unchanged to avoid DTN relay regressions.

### Risks / notes
- Existing tests do not yet assert scope-label persistence UI behavior; consider adding dedicated UI/integration coverage.
- Legacy detail strings remain in resources and can be removed after downstream references are cleaned up.

## Next phase
- Phase 3: Promote chat code UX into explicit room/group semantics with join/manage affordances.

## Phase 3 - Room Semantics for Group Chat
Date: 2026-03-30
Status: Completed

### Changes applied
- Added room scope-id codec to encode room bundles as `room:xxxxxxxx` while accepting legacy raw codes.
- Updated chat bundle creation to write room-prefixed `scopeId` values.
- Updated incoming chat sync and notification deep-link logic to decode both legacy and room-prefixed scope IDs.
- Updated user-facing chat copy to room semantics (create/join/list/delete wording).
- Updated conversation toolbar code display to explicit room format.

### Files modified
- app/src/main/java/com/dev/echodrop/util/RoomCodeCodec.java
- app/src/main/java/com/dev/echodrop/db/MessageEntity.java
- app/src/main/java/com/dev/echodrop/repository/ChatRepo.java
- app/src/main/java/com/dev/echodrop/transfer/BundleReceiver.java
- app/src/main/java/com/dev/echodrop/screens/ChatConversationFragment.java
- app/src/main/java/com/dev/echodrop/screens/CreateChatFragment.java
- app/src/main/res/values/strings.xml
- app/src/test/java/com/dev/echodrop/db/MessageEntityChatTest.java
- app/src/test/java/com/dev/echodrop/repository/ChatRepoChatSyncTest.java
- app/src/test/java/com/dev/echodrop/transfer/TransferProtocolChatTest.java

### Verified behavior intent
- Multi-member room behavior remains keyed by the same 8-char room code.
- Room-prefixed scope IDs do not break older bundles because decode accepts both forms.
- Mesh relay, hop, and transport frame logic are unchanged outside scope-id value semantics.

### Risks / notes
- Existing documentation outside this phase file still references chat-code wording and can be harmonized in a follow-up pass.

## Next phase
- Phase 4: Continue room UX polish and management affordances (member clarity, leave/manage paths, and related docs/tests).

## Phase 4 - Room Management Affordances
Date: 2026-03-30
Status: Completed

### Changes applied
- Added long-press room actions in room list: copy room code and leave room.
- Added explicit room code metadata to each room list card for faster visual identification.
- Added a dedicated Rooms section in Settings with live joined-room count.
- Added settings entry navigation to room management list.

### Files modified
- app/src/main/java/com/dev/echodrop/adapters/ChatListAdapter.java
- app/src/main/java/com/dev/echodrop/screens/PrivateChatListFragment.java
- app/src/main/java/com/dev/echodrop/screens/SettingsFragment.java
- app/src/main/res/layout/item_chat_list.xml
- app/src/main/res/layout/screen_settings.xml
- app/src/main/res/values/strings.xml

### Verified behavior intent
- Room management no longer depends on hidden flows; users can manage rooms directly from list/settings.
- Leave-room action maps to existing chat delete flow and preserves current data model.
- No transport or DTN protocol behavior changed in this phase.

### Risks / notes
- Long-press affordance is not yet discoverable via in-app hint text.
- No dedicated unit tests currently assert room long-press actions; covered by compile and manual flow validation.

## Next phase
- Phase 5: Add discoverability and robustness polish (room action hints, accessibility labels, and focused UI tests for manage/leave flows).

## Phase 5 - Discoverability and Accessibility Polish
Date: 2026-03-30
Status: Completed

### Changes applied
- Added an in-screen room management hint to make long-press actions discoverable.
- Added room item accessibility labels that include room name and room code.
- Added unread badge accessibility labels for assistive technologies.
- Added focused unit tests for room scope-id compatibility encoding/decoding.

### Files modified
- app/src/main/java/com/dev/echodrop/adapters/ChatListAdapter.java
- app/src/main/java/com/dev/echodrop/screens/PrivateChatListFragment.java
- app/src/main/res/layout/screen_chat_list.xml
- app/src/main/res/values/strings.xml
- app/src/test/java/com/dev/echodrop/util/RoomCodeCodecTest.java

### Verified behavior intent
- Users can discover room actions without guessing hidden gestures.
- Screen readers now announce clearer room metadata and unread context.
- Room scope-id codec behavior stays backward-compatible for prefixed and legacy code forms.

### Risks / notes
- Room manage actions are still dialog-driven and not yet available as visible inline buttons.

## Next phase
- Phase 6: QA hardening pass for room management flows (UI tests for long-press manage/leave and full wording sweep in docs).

## Phase 6 - Visual Consistency and Dialog Theming Fixes
Date: 2026-03-30
Status: Completed

### Changes applied
- Applied a brighter cyan-forward accent palette and raised secondary/muted text contrast for better readability on dark backgrounds.
- Added a shared Material AlertDialog overlay in theme resources to enforce dark dialog surface and text colors.
- Switched room list dialog implementation to AppCompat AlertDialog so theme overlays apply correctly.
- Preserved dark-only behavior across default and night resources.

### Files modified
- app/src/main/res/values/themes.xml
- app/src/main/res/values-night/themes.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values-night/colors.xml
- app/src/main/java/com/dev/echodrop/screens/PrivateChatListFragment.java

### Verified behavior intent
- Join-room and room-action dialogs render in dark theme with visible text and action labels.
- Text readability is improved throughout the app due to higher-contrast secondary/muted tokens.
- Existing button handlers and screen navigation flows remain intact.

### Risks / notes
- This pass prioritizes shared theme tokens; screen-by-screen micro-typography tuning can be done in a later polish pass.

## Next phase
- Phase 7: Full UI polish sweep against all provided reference screens (spacing/typography hierarchy and component-level refinements).

## Phase 7 - Primary CTA Contrast Hardening
Date: 2026-03-30
Status: Completed

### Changes applied
- Added a dedicated on-primary token for text/icon contrast on accent-filled controls.
- Remapped theme-level `colorOnPrimary` in default and night themes, including dialog theme primary contrast mapping.
- Updated the shared primary button style to use the new on-primary token.
- Updated accent CTA labels and primary FAB icon tint on onboarding, permissions, how-it-works, battery guide, post composer, and message detail screens.

### Files modified
- app/src/main/res/values/colors.xml
- app/src/main/res/values-night/colors.xml
- app/src/main/res/values/themes.xml
- app/src/main/res/values-night/themes.xml
- app/src/main/res/values/styles.xml
- app/src/main/res/layout/screen_permissions.xml
- app/src/main/res/layout/screen_onboarding_consent.xml
- app/src/main/res/layout/screen_how_it_works.xml
- app/src/main/res/layout/screen_battery_guide.xml
- app/src/main/res/layout/fragment_post_composer.xml
- app/src/main/res/layout/fragment_message_detail.xml
- app/src/main/res/layout/screen_home_inbox.xml

### Verified behavior intent
- Primary accent buttons now render readable label contrast in dark mode.
- Primary accent FAB icon contrast is preserved on the home inbox screen.
- No IDs, event handlers, navigation wiring, or business logic were changed.

### Risks / notes
- This pass targeted known accent CTA contrast surfaces only; additional palette updates may require another token audit for less-common accent usages.

## Next phase
- Phase 8: Continue full-screen visual refinement pass against the reference style system (component states, spacing rhythm, and typography consistency).

## Phase 8 - UI Rhythm and Minimal Icon System Polish
Date: 2026-03-30
Status: Completed

### Changes applied
- Added micro-spacing tokens (`2dp`, `4dp`, `6dp`, `12dp`) and replaced scattered hardcoded spacing values in core chat/inbox surfaces.
- Standardized primary CTA presentation by reusing shared button styles across permissions, onboarding, how-it-works, battery guide, composer, and detail confirmation actions.
- Replaced all layout-level Android built-in icons with app-owned vector icons for consistent visual language.
- Added a minimal icon set for search, compose, chat, lock, alert, location, close, check-circle, and bell.
- Improved icon centering with `centerInside` scaling and constrained FAB icon sizing; updated permission icon holders to circular, minimal chips.

### Files modified
- app/src/main/res/values/dimens.xml
- app/src/main/res/drawable/bg_icon_holder.xml
- app/src/main/res/layout/screen_home_inbox.xml
- app/src/main/res/layout/screen_chat_conversation.xml
- app/src/main/res/layout/item_chat_list.xml
- app/src/main/res/layout/screen_permissions.xml
- app/src/main/res/layout/screen_onboarding_consent.xml
- app/src/main/res/layout/screen_how_it_works.xml
- app/src/main/res/layout/screen_battery_guide.xml
- app/src/main/res/layout/fragment_post_composer.xml
- app/src/main/res/layout/fragment_message_detail.xml
- app/src/main/res/layout/screen_chat_list.xml
- app/src/main/res/layout/item_message_card.xml
- app/src/main/res/drawable/ic_search.xml
- app/src/main/res/drawable/ic_compose.xml
- app/src/main/res/drawable/ic_chat_bubble.xml
- app/src/main/res/drawable/ic_lock.xml
- app/src/main/res/drawable/ic_alert_outline.xml
- app/src/main/res/drawable/ic_location.xml
- app/src/main/res/drawable/ic_close.xml
- app/src/main/res/drawable/ic_check_circle.xml
- app/src/main/res/drawable/ic_bell.xml

### Verified behavior intent
- Icon visuals now use a single, minimal vector style and remain centered in icon containers and FABs.
- Legacy built-in icon dependency in layouts is removed, reducing OEM/device icon variance.
- Existing handlers, IDs, routing, and data/transport logic remain unchanged.

### Risks / notes
- This pass focuses on visual consistency of existing icon affordances; future UX iterations may still introduce dedicated semantic icons for new features.

## Next phase
- Phase 9: Final visual QA sweep (touch targets, state colors, and per-screen accessibility contrast checks).

## Phase 9 - Messaging Service Smoothing and Log De-dup
Date: 2026-03-30
Status: Completed

### Changes applied
- Added one-time Timber initialization guard in activity startup to prevent duplicate tree planting across activity recreations.
- Added service start-request coalescing and early-return guard when already running to avoid repeated foreground service start calls.
- Throttled repeated `ED:SERVICE_ALREADY_RUNNING` logs to reduce diagnostics noise.
- Added BLE self-peer filtering by local device ID to prevent self-connect churn.
- Throttled repetitive `ED:BLE_PEER_FOUND` logs per peer while still logging new peers and manifest-size changes.

### Files modified
- app/src/main/java/com/dev/echodrop/MainActivity.java
- app/src/main/java/com/dev/echodrop/service/EchoService.java
- app/src/main/java/com/dev/echodrop/ble/BleScanner.java

### Verified behavior intent
- Log volume no longer multiplies when activity recreates.
- Duplicate service start attempts are coalesced before entering `onStartCommand`.
- Mesh transfer path remains unchanged, but with reduced self-noise and lower startup churn.

### Risks / notes
- `ED:BLE_PEER_FOUND` is now rate-limited for repeat sightings; diagnostics still capture all peer additions and manifest changes.

## Next phase
- Phase 10: Field validation pass on two-device sessions (peer count parity, transfer latency, and diagnostics consistency).
