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
