# EchoDrop — Iteration 0-1 Changelog

> **Iteration Name:** Foundations + Visual Baseline  
> **Branch:** `iteration_0_1_foundations`  
> **Status:** ✅ Complete — `BUILD SUCCESSFUL`

---

## Summary

Iteration 0-1 establishes the complete visual foundation and UI scaffold for EchoDrop. Every screen, component, animation, and design token was implemented from scratch. The app compiles, runs, and renders a fully interactive dark-themed prototype with seed data.

**Total files created:** ~35  
**Lines of Java code:** ~820  
**Lines of XML resources:** ~1,200+

---

## Files Created

### Java Source Files (9 files)

#### `MainActivity.java`
- **Path:** `app/src/main/java/com/dev/echodrop/MainActivity.java`
- **Lines:** ~55
- **Purpose:** Single-activity host for all fragments
- **Details:**
  - Extends `AppCompatActivity`
  - Sets `activity_main.xml` as content view
  - Loads `OnboardingConsentFragment` as initial fragment
  - Provides 3 public navigation methods: `showPermissions()`, `showHowItWorks()`, `showHomeInbox()`
  - All transitions use `setCustomAnimations()` with 4 animation resources
  - `showPermissions()` and `showHowItWorks()` add to back stack
  - `showHomeInbox()` replaces without back stack (prevents return to onboarding)

#### `Message.java`
- **Path:** `app/src/main/java/com/dev/echodrop/models/Message.java`
- **Lines:** ~65
- **Purpose:** Core DTN message data model
- **Details:**
  - Immutable POJO — all fields `private final`
  - `Scope` enum: `LOCAL`, `ZONE`, `EVENT`
  - `Priority` enum: `ALERT`, `NORMAL`, `BULK`
  - Two constructors: auto-UUID and explicit-ID
  - `UUID.randomUUID().toString()` for distributed deduplication
  - Epoch millis timestamps for TTL arithmetic

#### `MessageViewModel.java`
- **Path:** `app/src/main/java/com/dev/echodrop/viewmodels/MessageViewModel.java`
- **Lines:** ~60
- **Purpose:** Lifecycle-aware data holder with LiveData
- **Details:**
  - Extends `ViewModel` for configuration change survival
  - `MutableLiveData<List<Message>>` — private, exposed as `LiveData` (read-only)
  - `seedMessages()` creates 3 demo messages on construction
  - `addMessage()` inserts at index 0, creates defensive copy, sets new list
  - Seed messages: 1 ALERT/LOCAL (road closure), 1 NORMAL/ZONE (study group), 1 NORMAL/EVENT (campus event)
  - Varying `createdAt` offsets to simulate realistic temporal distribution

#### `MessageAdapter.java`
- **Path:** `app/src/main/java/com/dev/echodrop/adapters/MessageAdapter.java`
- **Lines:** ~125
- **Purpose:** RecyclerView adapter with DiffUtil integration
- **Details:**
  - Extends `ListAdapter<Message, MessageViewHolder>` (not raw `RecyclerView.Adapter`)
  - `DiffUtil.ItemCallback`: compares `id` for identity, 5 fields for content equality
  - Uses `ItemMessageCardBinding` (ViewBinding) in ViewHolder
  - Scope badge coloring: LOCAL → `echo_positive_accent`/`bg_badge_positive`, ZONE/EVENT → `echo_primary_accent`/`bg_badge_primary`
  - Priority handling: ALERT shows dot + "Alert" label in `echo_alert_accent`, NORMAL shows dot in `echo_primary_accent`, BULK shows dot in `echo_muted_disabled`
  - Unread border: 3dp left border visible when `!message.isRead()`
  - `formatTtl()`: computes remaining time as "Xh", "Xm", "<1m", or "Expired"
  - Uses `ContextCompat.getColor()` (not deprecated `getResources().getColor()`)

#### `OnboardingConsentFragment.java`
- **Path:** `app/src/main/java/com/dev/echodrop/screens/OnboardingConsentFragment.java`
- **Lines:** ~106
- **Purpose:** Welcome screen with animated broadcasting visual
- **Details:**
  - ViewBinding with `ScreenOnboardingConsentBinding`
  - Pulse animation: `AnimationSet` (fill after, infinite repeat)
    - `ScaleAnimation(1.0f, 1.4f, 1.0f, 1.4f)` — center pivot
    - `AlphaAnimation(0.2f, 0.0f)`
    - Duration: 2000ms
  - Button press feedback: `OnTouchListener` scales to 0.97× on ACTION_DOWN (40ms), 1.0× on ACTION_UP/CANCEL (80ms)
  - Navigation: Continue → `showPermissions()`, How? → `showHowItWorks()`, Skip → `showHomeInbox()`
  - `binding = null` in `onDestroyView()` for memory safety

#### `PermissionsFragment.java`
- **Path:** `app/src/main/java/com/dev/echodrop/screens/PermissionsFragment.java`
- **Lines:** ~50
- **Purpose:** Permission explanation screen
- **Details:**
  - ViewBinding with `ScreenPermissionsBinding`
  - Toolbar with back navigation (`setNavigationOnClickListener`)
  - "Allow permissions" and "Later" both call `((MainActivity) requireActivity()).showHomeInbox()`
  - Permissions are not actually requested in this iteration (UI scaffold only)
  - `binding = null` in `onDestroyView()`

#### `HowItWorksFragment.java`
- **Path:** `app/src/main/java/com/dev/echodrop/screens/HowItWorksFragment.java`
- **Lines:** ~50
- **Purpose:** DTN explanation screen
- **Details:**
  - ViewBinding with `ScreenHowItWorksBinding`
  - Toolbar with back navigation
  - "Get Started" button navigates to `showHomeInbox()`
  - `binding = null` in `onDestroyView()`

#### `HomeInboxFragment.java`
- **Path:** `app/src/main/java/com/dev/echodrop/screens/HomeInboxFragment.java`
- **Lines:** ~320 (largest file)
- **Purpose:** Primary messaging inbox — core user-facing screen
- **Details:**
  - Implements `PostComposerSheet.OnPostListener` interface
  - ViewBinding with `ScreenHomeInboxBinding`
  - **RecyclerView Setup:**
    - `LinearLayoutManager` (vertical)
    - `DividerItemDecoration` with `echo_divider` color
    - MessageAdapter instance
    - `clipToPadding="false"`, bottom padding 80dp for FAB clearance
  - **ViewModel Observation:**
    - `ViewModelProvider(this).get(MessageViewModel.class)`
    - Observes with `getViewLifecycleOwner()` — lifecycle-safe
    - On data change: stores full list, applies current filter, updates badges
  - **Tab Bar:**
    - 3 custom TextViews (ALL, ALERTS, CHATS)
    - Click handlers toggle `isSelected`, text color, indicator visibility
    - ALL: shows all messages
    - ALERTS: filters `priority == Priority.ALERT`
    - CHATS: shows empty list (placeholder for future)
    - Alert badge shows count of ALERT messages, hidden when 0
  - **Search:**
    - `TextWatcher` on EditText
    - Case-insensitive `toLowerCase().contains()` filtering
    - `OnFocusChangeListener` swaps drawable: `bg_search_input` ↔ `bg_search_input_focused`
  - **Animations:**
    - Sync dot pulse: `ObjectAnimator` on alpha, 1.0→0.3, 2000ms, infinite REVERSE
    - FAB press: scale 0.95×/40ms down, 1.0×/80ms up (both FABs)
    - Secondary FAB entrance: scaleX/Y from 0→1, 180ms, `OvershootInterpolator(1.2f)`
    - Empty state: alpha 0→1, 400ms, `FastOutSlowInInterpolator`
  - **Menu:**
    - `home_menu.xml` inflated in `onCreateOptionsMenu()`
    - "Add post" opens PostComposerSheet
  - **Lifecycle Cleanup:**
    - `syncPulseAnimator.cancel()` in `onDestroyView()`
    - `binding = null` in `onDestroyView()`
  - **Callback:**
    - `onPost(Message)` calls `viewModel.addMessage(message)`

#### `PostComposerSheet.java`
- **Path:** `app/src/main/java/com/dev/echodrop/components/PostComposerSheet.java`
- **Lines:** ~212
- **Purpose:** BottomSheet dialog for composing messages
- **Details:**
  - Extends `BottomSheetDialogFragment`
  - `getTheme()` returns `R.style.Theme_EchoDrop_BottomSheet` (20dp rounded top corners)
  - `OnPostListener` callback interface for communication with hosting fragment
  - **Chip Styling:** Programmatic `ColorStateList` for each chip
    - Checked state: `echo_primary_tint` bg, `echo_primary_accent` stroke/text
    - Unchecked state: transparent bg, `echo_border` stroke, `echo_text_secondary` text
    - `setChipStrokeWidth(1f)` for crisp borders
  - **Default Selections:** Scope → Nearby, TTL → 4 hours
  - **Urgent Toggle:**
    - `SwitchMaterial` with custom track tint ColorStateList
    - Checked: `echo_alert_accent`, Unchecked: `echo_muted_disabled`
    - Label text changes: "Mark as urgent" ↔ "Marked urgent"
    - Hint visibility toggles on check change
  - **Character Counter:**
    - TextWatcher updates on every keystroke
    - Color thresholds: default (`echo_text_secondary`), 200+ (`echo_amber_accent`), 240 (`echo_alert_accent`)
    - Hard limit: `android:maxLength="240"` on EditText
    - Format: `getString(R.string.post_char_counter, length)` → "X / 240"
  - **Submit Validation:**
    - Enabled when: text non-empty AND scope chip selected
    - Disabled state: 50% alpha on Post button
    - Guard clause: returns early if button not enabled
  - **Message Construction:**
    - Text: trimmed input
    - Scope: mapped from checked chip ID (Nearby→LOCAL, Area→ZONE, Event→EVENT)
    - Priority: ALERT if urgent toggle checked, else NORMAL
    - TTL: mapped from checked chip ID (1h/4h/12h/24h → millis)
    - Timestamps: `System.currentTimeMillis()` for created, + TTL for expires
  - **Post-Submit:**
    - Calls `listener.onPost(message)`
    - Dismisses the sheet
    - Shows Snackbar on `activity.findViewById(android.R.id.content)` — ensures visibility after sheet dismissal
    - Snackbar styled: `echo_card_surface` bg, `echo_text_primary` text, 3000ms duration
  - **Lifecycle:** `binding = null` in `onDestroyView()`

---

### Layout XML Files (7 files)

#### `activity_main.xml`
- FrameLayout wrapper with `FragmentContainerView` (`@+id/fragment_container`)
- `match_parent` width/height, `echo_bg_main` background

#### `screen_onboarding_consent.xml`
- ScrollView → vertical LinearLayout (center gravity)
- Pulse ring: FrameLayout 160dp, circular `echo_primary_tint` background
- Icon card: MaterialCardView 128dp, 64dp corner radius, `echo_card_surface` bg
- WiFi icon: ImageView 64dp, `ic_wifi` drawable, white tint
- Headline: 24sp bold, `echo_text_primary`
- Subheading: 16sp, `echo_text_secondary`, max 300dp width
- 3 feature cards: MaterialCardView with icon + title + description
- Primary button: 56dp height, `echo_primary_accent` bg
- Secondary link: text style button, `echo_primary_accent` text
- Skip button: text style button, `echo_text_secondary` text

#### `screen_permissions.xml`
- MaterialToolbar with back navigation icon
- 3 permission cards with Required/Optional badges
- Privacy note card with 3dp left border (`echo_primary_accent`)
- Footer: Allow button (primary) + Later link (text)

#### `screen_how_it_works.xml`
- MaterialToolbar with back navigation
- Steps card: numbered 1-3 with descriptions
- 2×2 GridLayout: feature highlights (No Internet, Auto-Expire, Anonymous, Local First)
- HorizontalScrollView: use case cards with emoji icons from string resources
- "Get Started" primary button

#### `screen_home_inbox.xml`
- ConstraintLayout root
- MaterialToolbar: hamburger menu + "Add post" action
- Search container: LinearLayout with search icon + EditText (`bg_search_input`) + sync indicator (pulse dot + count text)
- Tab bar: 3 equal-weight TextViews with 2dp indicator Views below
- RecyclerView: `scrollbars="none"`, `paddingBottom="80dp"`, `clipToPadding="false"`
- Empty state: centered LinearLayout (80dp circle + headline + subtitle)
- Primary FAB: 56dp, `echo_primary_accent`, `ic_add` icon
- Secondary FAB: 48dp, `echo_card_surface`, `ic_send` icon, `borderWidth="1dp"`

#### `item_message_card.xml`
- Horizontal LinearLayout root
- Unread border: View 3dp width, `echo_primary_accent` bg
- Priority dot: View 8dp × 8dp, `bg_circle` drawable
- Content block: vertical LinearLayout
  - Preview text: 14sp, max 2 lines, ellipsize end
  - Metadata row: scope badge + priority label + dot separator + TTL text
- Chevron: ImageView `ic_chevron_right`, `echo_muted_disabled` tint

#### `fragment_post_composer.xml`
- LinearLayout root
- Header: "New Post" title + close ImageButton
- Divider: 1dp, `echo_divider`
- ScrollView body:
  - EditText: multiline, `maxLength="240"`, hint "What's happening nearby?"
  - Character counter: 12sp, `echo_text_secondary`
  - Scope ChipGroup: singleSelection, 3 chips (Nearby/Area/Event)
  - Urgent toggle: SwitchMaterial + label + conditional hint
  - TTL ChipGroup: singleSelection, 4 chips (1h/4h/12h/24h)
- Footer: Cancel (text) + Post (filled, 52dp height)

---

### Resource Value Files

#### `colors.xml` — 15 color tokens
| Token | Hex | Category |
|-------|-----|----------|
| `echo_bg_main` | `#0A0C0F` | Background |
| `echo_card_surface` | `#111318` | Surface |
| `echo_elevated_surface` | `#070809` | Elevated |
| `echo_primary_accent` | `#7C9EBF` | Primary |
| `echo_alert_accent` | `#C0616A` | Alert |
| `echo_positive_accent` | `#5E9E82` | Positive |
| `echo_amber_accent` | `#C8935A` | Warning |
| `echo_primary_tint` | `#197C9EBF` | 15% alpha |
| `echo_alert_tint` | `#19C0616A` | 15% alpha |
| `echo_text_primary` | `#E8E8E8` | Text |
| `echo_text_secondary` | `#8A8F98` | Text |
| `echo_muted_disabled` | `#3A3F47` | Disabled |
| `echo_divider` | `#1A1D24` | Divider |
| `echo_border` | `#252830` | Border |

#### `themes.xml` + `values-night/themes.xml`
- Parent: `Theme.Material3.Dark.NoActionBar`
- Maps Material tokens to EchoDrop colors
- Includes `android:colorBackground` (with `android:` prefix — required)
- `android:statusBarColor` and `android:navigationBarColor` set to `echo_bg_main`

#### `styles.xml`
- 6 `TextAppearance.EchoDrop.*` styles (H1, H2, BodyLarge, Body, Small, Mono)
- `Theme.EchoDrop.BottomSheet` with 20dp rounded corners and 0.6 backdrop dim
- `Widget.EchoDrop.BottomSheet` with shape appearance overlay
- `ShapeAppearance.EchoDrop.BottomSheet` for top corner rounding

#### `dimens.xml`
- 8dp spacing scale: `spacing_1` (8dp) through `spacing_4` (32dp)
- Component dimensions: `app_bar_height`, `fab_size`, `fab_mini_size`, `min_touch_target`
- Corner radii: `card_corner_radius` (12dp), `badge_corner_radius` (4dp), `input_corner_radius` (8dp)
- Additional: `card_padding` (16dp)

#### `integers.xml`
- Animation timing constants: `anim_press` (40), `anim_fast` (180), `anim_standard` (250), `anim_slow` (400)

#### `strings.xml` — ~90+ string resources
- App name, screen titles, button labels, descriptions
- Feature card content for onboarding
- Permission descriptions (Nearby, Location, Notifications)
- How-it-works step descriptions
- Feature grid labels and descriptions
- Use case titles (Campus Alerts, Event Chat, Neighborhood, Transit Updates)
- Emoji icon strings as literal UTF-8 characters
- Home inbox labels (tabs, search, sync, empty state)
- Post composer labels, hints, chip labels
- Format strings with positional arguments (`%1$d`, `%1$s`)
- Properly escaped apostrophes (`\'`)

#### `font_certs.xml`
- Google Fonts provider certificate hash for font validation

#### `preloaded_fonts.xml`
- References `@font/inter` and `@font/roboto_mono` for preloading

---

### Font Configuration (2 files)

#### `res/font/inter.xml`
- Downloadable font config for Inter (weight 400)
- Provider: `com.google.android.gms.fonts`
- Query: `name=Inter&weight=400`

#### `res/font/roboto_mono.xml`
- Downloadable font config for Roboto Mono (weight 400)
- Provider: `com.google.android.gms.fonts`
- Query: `name=Roboto Mono&weight=400`

---

### Drawable XML Files (8 files)

| File | Type | Shape | Fill | Stroke | Corners |
|------|------|-------|------|--------|---------|
| `bg_search_input.xml` | Shape | Rectangle | `echo_card_surface` | 1dp `echo_border` | 8dp |
| `bg_search_input_focused.xml` | Shape | Rectangle | `echo_card_surface` | 1dp `echo_primary_accent` | 8dp |
| `bg_badge_primary.xml` | Shape | Rectangle | `echo_primary_tint` | — | 4dp |
| `bg_badge_alert.xml` | Shape | Rectangle | `echo_alert_tint` | — | 4dp |
| `bg_badge_positive.xml` | Shape | Rectangle | `#195E9E82` | — | 4dp |
| `bg_circle.xml` | Shape | Oval | (inherits) | — | — |
| `bg_icon_holder.xml` | Shape | Oval | `echo_primary_tint` | — | — |
| `ic_wifi.xml` | Vector | Path | white | — | — |

---

### Animation XML Files (4 files)

| File | Alpha | Translate | Duration | Interpolator |
|------|-------|-----------|----------|--------------|
| `fragment_enter.xml` | 0→1 | Y: 12dp→0 | 250ms | Decelerate |
| `fragment_exit.xml` | 1→0 | — | 180ms | Accelerate |
| `fragment_pop_enter.xml` | 0→1 | Y: -12dp→0 | 250ms | Decelerate |
| `fragment_pop_exit.xml` | 1→0 | Y: 0→12dp | 180ms | Accelerate |

---

### Menu XML (1 file)

#### `res/menu/home_menu.xml`
- Single item: "Add post" with `app:showAsAction="always"` (AppCompat namespace)

---

## Bug Fixes Applied

| # | Issue | Root Cause | Fix |
|---|-------|-----------|-----|
| 1 | `textStyle="500"` build error | Android only supports `normal`/`bold`/`italic` | Changed to `"bold"` |
| 2 | Emoji encoding crash | Surrogate pairs (`\uD83C\uDF93`) invalid in Android XML | Replaced with literal UTF-8 emoji characters |
| 3 | `showAsAction` namespace error | `android:showAsAction` incompatible with AppCompat Toolbar | Changed to `app:showAsAction` |
| 4 | Deprecated `getColor()` warning | `getResources().getColor(int)` deprecated in API 23 | Changed to `ContextCompat.getColor(context, int)` |
| 5 | Apostrophe AAPT error | `&#39;` entity not accepted by AAPT in strings.xml | Changed to `\'` backslash escape |
| 6 | `colorBackground` theme error | Missing `android:` prefix on framework attribute | Fixed to `android:colorBackground` |
| 7 | Missing system drawable | `android:drawable/stat_sys_wifi` not available | Created custom `ic_wifi.xml` vector drawable |
| 8 | Null bytes in layout XML | UTF-8 emoji encoding artifacts in how_it_works.xml | Removed via binary cleanup |
| 9 | Snackbar not visible | Snackbar attached to BottomSheet root (destroyed on dismiss) | Moved to `activity.findViewById(android.R.id.content)` |
| 10 | Wrong interpolator import | `android.view.animation.FastOutSlowInInterpolator` doesn't exist | Changed to `androidx.interpolator.view.animation.FastOutSlowInInterpolator` |
| 11 | Downloadable font crash | `font_certs.xml` contained invalid Base64; Google Fonts provider requires Play Services not available on all emulators | Replaced downloadable fonts (`@font/inter`, `@font/roboto_mono`) with system fonts (`sans-serif`, `monospace`). Deleted `font/inter.xml`, `font/roboto_mono.xml`, `font_certs.xml`, `preloaded_fonts.xml`, and `preloaded_fonts` meta-data from manifest. |

---

## Build Verification

```
> Task :app:assembleDebug
BUILD SUCCESSFUL
```

Verified via `./gradlew assembleDebug` with zero errors and zero warnings.

---

*Changelog for EchoDrop Iteration 0-1 (Foundations + Visual Baseline)*  
*Last updated: 2026*
