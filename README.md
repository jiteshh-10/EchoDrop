# EchoDrop — Project Documentation

> **Delay-Tolerant Networking (DTN) Mesh Messaging for Android**

EchoDrop is an Android application that enables hyperlocal, ephemeral communication between nearby devices using Delay-Tolerant Networking principles. Messages ("echoes") propagate through proximity-based mesh networking, expire automatically, and require no internet connectivity.

---

## Table of Contents

- [1. Project Overview](#1-project-overview)
- [2. Technology Stack](#2-technology-stack)
- [3. Project Structure](#3-project-structure)
- [4. Architecture & Design Patterns](#4-architecture--design-patterns)
- [5. Design System](#5-design-system)
  - [5.1 Color Palette](#51-color-palette)
  - [5.2 Typography Scale](#52-typography-scale)
  - [5.3 Spacing & Dimensions](#53-spacing--dimensions)
  - [5.4 Animation Specification](#54-animation-specification)
- [6. Screen-by-Screen Documentation](#6-screen-by-screen-documentation)
  - [6.1 Onboarding Consent](#61-onboarding-consent)
  - [6.2 Permissions](#62-permissions)
  - [6.3 How It Works](#63-how-it-works)
  - [6.4 Home Inbox](#64-home-inbox)
  - [6.5 Post Composer](#65-post-composer)
- [7. Data Model](#7-data-model)
- [8. Component Documentation](#8-component-documentation)
- [9. Build & Setup](#9-build--setup)
- [10. Iteration 0-1 Completion Status](#10-iteration-0-1-completion-status)
- [11. Iteration 2 Completion Status](#11-iteration-2-completion-status)
- [12. Iteration 3 Completion Status](#12-iteration-3-completion-status)

---

## 1. Project Overview

| Attribute        | Value                                        |
|------------------|----------------------------------------------|
| **App Name**     | EchoDrop                                     |
| **Package**      | `com.dev.echodrop`                           |
| **Min SDK**      | 24 (Android 7.0 Nougat)                      |
| **Compile SDK**  | 35                                           |
| **Language**     | Java 11                                      |
| **Theme**        | Material 3 — Dark Only                       |
| **Branch**       | `main` (latest: `iteration-3`)               |

### Concept

EchoDrop operates on a store-carry-forward paradigm. Users create short-lived messages scoped to geographic ranges (Local / Zone / Event). Messages propagate between devices in physical proximity without requiring traditional internet infrastructure. Each message has a time-to-live (TTL) after which it self-destructs.

- **Iteration 0-1** established the complete visual foundation and UI scaffold.
- **Iteration 2** added Room persistence, SHA-256 deduplication, storage cap enforcement, WorkManager TTL cleanup, and a message detail screen.
- **Iteration 3** added priority-aware inbox ordering (ALERT > NORMAL > BULK), visual priority treatment, urgent banner in detail screen, reactive alert count badge, and Post button color transition on urgent toggle.

---

## 2. Technology Stack

| Component             | Library / Version                                  |
|-----------------------|----------------------------------------------------|
| UI Framework          | AndroidX AppCompat (via BOM)                       |
| Material Components   | Material 3 (via `libs.versions.toml`)              |
| Layout Binding        | ViewBinding (`buildFeatures { viewBinding true }`) |
| List Rendering        | RecyclerView 1.3.2                                 |
| Architecture          | ViewModel + LiveData (`lifecycle-viewmodel-ktx:2.7.0`) |
| Persistence           | Room 2.6.1 (SQLite ORM)                           |
| Background Work       | WorkManager 2.9.0 (TTL cleanup)                   |
| Layout Constraint     | ConstraintLayout (via `libs.versions.toml`)        |
| Typography            | System fonts (`sans-serif` / `monospace`)           |
| Build System          | Gradle 8.x with Kotlin DSL catalog                 |

### Dependencies (`app/build.gradle`)

```groovy
dependencies {
    implementation libs.appcompat
    implementation libs.material
    implementation libs.activity
    implementation libs.constraintlayout
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata:2.7.0'
    implementation 'androidx.room:room-runtime:2.6.1'
    annotationProcessor 'androidx.room:room-compiler:2.6.1'
    implementation 'androidx.work:work-runtime:2.9.0'
    // Testing
    testImplementation libs.junit
    testImplementation 'org.mockito:mockito-core:5.11.0'
    testImplementation 'org.mockito:mockito-inline:5.2.0'
    testImplementation 'androidx.arch.core:core-testing:2.2.0'
    testImplementation 'org.robolectric:robolectric:4.12.1'
    testImplementation 'androidx.test:core:1.5.0'
    testImplementation 'androidx.test.ext:junit:1.1.5'
    testImplementation 'androidx.room:room-testing:2.6.1'
    testImplementation 'androidx.work:work-testing:2.9.0'
    androidTestImplementation libs.ext.junit
    androidTestImplementation libs.espresso.core
}
```

---

## 3. Project Structure

```
EchoDrop/
├── doc/                              ← You are here
│   ├── README.md                     ← This file (project overview)
│   ├── ARCHITECTURE.md               ← Architecture deep-dive
│   ├── CHANGELOG_ITERATION_01.md     ← Iteration 0-1 changelog
│   └── CHANGELOG_ITERATION_02.md     ← Iteration 2 changelog
│
├── app/
│   ├── build.gradle                  ← Module build config
│   └── src/main/
│       ├── AndroidManifest.xml       ← App manifest
│       ├── java/com/dev/echodrop/
│       │   ├── MainActivity.java           ← Single Activity host
│       │   ├── models/
│       │   │   └── Message.java            ← Legacy data model (POJO)
│       │   ├── db/
│       │   │   ├── MessageEntity.java       ← Room entity (primary data class)
│       │   │   ├── MessageDao.java          ← Room DAO
│       │   │   └── AppDatabase.java         ← Room database singleton
│       │   ├── repository/
│       │   │   └── MessageRepo.java         ← Repository (dedup + cap + cleanup)
│       │   ├── workers/
│       │   │   └── TtlCleanupWorker.java     ← WorkManager TTL cleanup
│       │   ├── viewmodels/
│       │   │   └── MessageViewModel.java   ← LiveData ViewModel
│       │   ├── adapters/
│       │   │   └── MessageAdapter.java     ← DiffUtil ListAdapter
│       │   ├── screens/
│       │   │   ├── OnboardingConsentFragment.java
│       │   │   ├── PermissionsFragment.java
│       │   │   ├── HowItWorksFragment.java
│       │   │   ├── HomeInboxFragment.java
│       │   │   └── MessageDetailFragment.java  ← Message detail + TTL progress
│       │   └── components/
│       │       └── PostComposerSheet.java  ← BottomSheet dialog
│       │
│       └── res/
│           ├── anim/                       ← Fragment transition animations
│           │   ├── fragment_enter.xml
│           │   ├── fragment_exit.xml
│           │   ├── fragment_pop_enter.xml
│           │   └── fragment_pop_exit.xml
│           ├── drawable/                   ← Shape drawables & vectors
│           │   ├── bg_search_input.xml
│           │   ├── bg_search_input_focused.xml
│           │   ├── bg_badge_primary.xml
│           │   ├── bg_badge_alert.xml
│           │   ├── bg_badge_positive.xml
│           │   ├── bg_urgent_banner.xml    ← Urgent banner drawable (iter-3)
│           │   ├── bg_circle.xml
│           │   ├── bg_icon_holder.xml
│           │   └── ic_wifi.xml
│           ├── layout/                     ← Screen layouts
│           │   ├── activity_main.xml
│           │   ├── screen_onboarding_consent.xml
│           │   ├── screen_permissions.xml
│           │   ├── screen_how_it_works.xml
│           │   ├── screen_home_inbox.xml
│           │   ├── item_message_card.xml
│           │   ├── fragment_post_composer.xml
│           │   └── fragment_message_detail.xml  ← Message detail layout
│           ├── menu/
│           │   └── home_menu.xml
│           └── values/
│               ├── colors.xml
│               ├── dimens.xml
│               ├── integers.xml
│               ├── strings.xml
│               ├── styles.xml
│               ├── themes.xml
│               └── themes.xml
│
├── build.gradle                      ← Root build config
├── settings.gradle                   ← Module declarations
├── gradle.properties                 ← Build properties
└── gradle/
    ├── libs.versions.toml            ← Version catalog
    └── wrapper/
        └── gradle-wrapper.properties
```

---

## 4. Architecture & Design Patterns

> Full architecture documentation is in [`ARCHITECTURE.md`](ARCHITECTURE.md).

### Summary

| Pattern                | Implementation                                               |
|------------------------|--------------------------------------------------------------|
| **MVVM**               | `MessageViewModel` + `LiveData` observed by `HomeInboxFragment` |
| **Single Activity**    | `MainActivity` hosts all fragments via `FragmentContainerView` |
| **Fragment Navigation**| Manual `FragmentTransaction` with custom animations            |
| **ViewBinding**        | All fragments and activity use generated binding classes        |
| **DiffUtil**           | `MessageAdapter` extends `ListAdapter` for efficient updates   |
| **Callback Pattern**   | `PostComposerSheet.OnPostListener` interface                   |
| **BottomSheet**        | `BottomSheetDialogFragment` with themed rounded corners        |

### Navigation Flow

```
OnboardingConsent
    ├── "Continue"  → Permissions
    ├── "How?"      → HowItWorks
    └── "Skip"      → HomeInbox

Permissions
    ├── "Allow"     → HomeInbox
    └── "Later"     → HomeInbox

HowItWorks
    └── "Get Started" → HomeInbox

HomeInbox
    └── FAB / Menu  → PostComposerSheet (BottomSheet overlay)
```

All forward transitions use `addToBackStack(null)` except the final `HomeInbox` navigation, which replaces the stack to prevent back-navigation to onboarding.

---

## 5. Design System

### 5.1 Color Palette

EchoDrop uses a 15-token dark-first color system. All colors are defined in `res/values/colors.xml`.

| Token                  | Hex       | Usage                                  |
|------------------------|-----------|----------------------------------------|
| `echo_bg_main`         | `#0A0C0F` | App-wide background                    |
| `echo_card_surface`    | `#111318` | Card and surface backgrounds           |
| `echo_elevated_surface`| `#070809` | Elevated containers, Snackbar bg       |
| `echo_primary_accent`  | `#7C9EBF` | Primary interactive elements, links    |
| `echo_alert_accent`    | `#C0616A` | Alert state, urgent indicators         |
| `echo_positive_accent` | `#5E9E82` | Success state, LOCAL scope badge       |
| `echo_amber_accent`    | `#C8935A` | Warning state, char counter at 200+    |
| `echo_primary_tint`    | `#197C9EBF`| 15% alpha tint for chip backgrounds   |
| `echo_alert_tint`      | `#19C0616A`| 15% alpha alert background tint       |
| `echo_text_primary`    | `#E8E8E8` | Primary text color                     |
| `echo_text_secondary`  | `#8A8F98` | Secondary / muted text                 |
| `echo_muted_disabled`  | `#3A3F47` | Disabled states, BULK priority dot     |
| `echo_divider`         | `#1A1D24` | List dividers, separators              |
| `echo_border`          | `#252830` | Input borders, card outlines           |
| `echo_amber_accent`    | `#C8935A` | Warning threshold for char counter     |

### 5.2 Typography Scale

Defined in `res/values/styles.xml` using Android system fonts.

| Style Name                        | Font         | Size  | Letter Spacing | Usage                     |
|-----------------------------------|-------------|-------|----------------|---------------------------|
| `TextAppearance.EchoDrop.H1`     | sans-serif   | 20sp  | -0.01em        | Screen titles, headlines   |
| `TextAppearance.EchoDrop.H2`     | sans-serif   | 18sp  | -0.01em        | Section headers            |
| `TextAppearance.EchoDrop.BodyLarge`| sans-serif  | 16sp  | default        | Large body text            |
| `TextAppearance.EchoDrop.Body`   | sans-serif   | 14sp  | default        | Default body text          |
| `TextAppearance.EchoDrop.Small`  | sans-serif   | 12sp  | +0.01em        | Captions, metadata, badges |
| `TextAppearance.EchoDrop.Mono`   | monospace    | 13sp  | default        | Monospace / technical text |

### 5.3 Spacing & Dimensions

Defined in `res/values/dimens.xml` using an 8dp base grid.

| Token           | Value  | Usage                              |
|-----------------|--------|------------------------------------|
| `spacing_1`     | 8dp    | Tight inner padding, small gaps    |
| `spacing_1_5`   | 12dp   | Component internal padding         |
| `spacing_2`     | 16dp   | Standard padding, card padding     |
| `spacing_3`     | 24dp   | Section spacing                    |
| `spacing_4`     | 32dp   | Large section gaps                 |
| `app_bar_height` | 56dp  | Toolbar height                     |
| `fab_size`      | 56dp   | Primary FAB diameter               |
| `fab_mini_size`  | 48dp  | Secondary FAB diameter             |
| `min_touch_target`| 44dp | Accessibility minimum touch target |
| `card_corner_radius`| 12dp| Card corner radius                |
| `badge_corner_radius`| 4dp| Badge corner radius               |
| `input_corner_radius`| 8dp| Input field corner radius         |

### 5.4 Animation Specification

Timing constants are defined in `res/values/integers.xml`. Animation XMLs are in `res/anim/`.

| Constant        | Value  | Usage                                          |
|-----------------|--------|------------------------------------------------|
| `anim_press`    | 40ms   | Button/FAB press scale-down duration           |
| `anim_fast`     | 180ms  | Exit animations, fast transitions              |
| `anim_standard` | 250ms  | Enter animations, standard transitions         |
| `anim_slow`     | 400ms  | Emphasis animations, empty state fade-in       |

#### Fragment Transitions

| File                    | Effect                      | Duration | Interpolator |
|-------------------------|-----------------------------|----------|--------------|
| `fragment_enter.xml`    | Fade in + slide up 12dp     | 250ms    | Decelerate   |
| `fragment_exit.xml`     | Fade out                    | 180ms    | Accelerate   |
| `fragment_pop_enter.xml`| Fade in + slide from -12dp  | 250ms    | Decelerate   |
| `fragment_pop_exit.xml` | Fade out + slide down 12dp  | 180ms    | Accelerate   |

#### Micro-Interactions

| Animation              | Implementation                              | Duration  | Details                           |
|------------------------|---------------------------------------------|-----------|-----------------------------------|
| Pulse ring             | `ScaleAnimation` + `AlphaAnimation` in set  | 2000ms    | Scale 1.0→1.4, alpha 0.2→0.0, infinite loop |
| Button press           | `OnTouchListener` with `animate().scaleX/Y` | 40ms/80ms | Scale to 0.97 on ACTION_DOWN, 1.0 on UP     |
| FAB press              | `OnTouchListener` with `animate().scaleX/Y` | 40ms/80ms | Scale to 0.95 on ACTION_DOWN, 1.0 on UP     |
| FAB entrance           | `animate().scaleX/Y` with overshoot         | 180ms     | Scale 0→1, `OvershootInterpolator(1.2f)`     |
| Sync dot pulse         | `ObjectAnimator` on alpha                   | 2000ms    | Alpha 1.0→0.3→1.0, infinite, auto-reverse    |
| Empty state fade       | `animate().alpha` with interpolator         | 400ms     | Alpha 0→1, `FastOutSlowInInterpolator`       |

---

## 6. Screen-by-Screen Documentation

### 6.1 Onboarding Consent

**File:** `screens/OnboardingConsentFragment.java`  
**Layout:** `screen_onboarding_consent.xml`  
**Purpose:** Welcome screen introducing EchoDrop's core value proposition

#### UI Composition

```
ScrollView
└── LinearLayout (vertical, center)
    ├── FrameLayout (160dp × 160dp)
    │   ├── View — Pulse ring (primary_tint, circular)
    │   └── MaterialCardView (128dp × 128dp, 64dp corners)
    │       └── ImageView — WiFi icon (ic_wifi, 64dp, white)
    ├── TextView — Headline ("Drop a message...") 24sp bold
    ├── TextView — Subheading, 16sp, secondary color
    ├── 3 × MaterialCardView — Feature cards
    │   └── Each: icon string + title + description
    ├── MaterialButton — "Continue" (primary, 56dp height)
    ├── MaterialButton — "How does this work?" (text style)
    └── MaterialButton — "Skip for now" (text, muted)
```

#### Behavior

- **Pulse Animation:** On `onViewCreated`, a looping `AnimationSet` scales the pulse ring from 1.0→1.4 and fades alpha from 0.2→0.0 over 2000ms, creating a "broadcasting signal" effect.
- **Button Press Feedback:** The primary "Continue" button scales to 0.97× on `ACTION_DOWN` (40ms) and returns to 1.0× on `ACTION_UP` (80ms) via `OnTouchListener`.
- **Navigation:** Continue → `PermissionsFragment`, How? → `HowItWorksFragment`, Skip → `HomeInboxFragment`.

---

### 6.2 Permissions

**File:** `screens/PermissionsFragment.java`  
**Layout:** `screen_permissions.xml`  
**Purpose:** Explains required and optional permissions before requesting them

#### UI Composition

```
LinearLayout (vertical)
├── MaterialToolbar — Back arrow + "Permissions" title
├── 3 × MaterialCardView — Permission cards
│   ├── Nearby connections (Required badge — alert_accent)
│   ├── Location (Required badge)
│   └── Notifications (Optional badge — primary_accent)
├── MaterialCardView — Privacy note (3dp left border, primary_accent)
│   └── "Your data never leaves..." text
└── Footer
    ├── MaterialButton — "Allow permissions" (primary)
    └── MaterialButton — "Later" (text style)
```

#### Behavior

- Toolbar back navigation calls `requireActivity().onBackPressed()`.
- Both "Allow" and "Later" navigate to `HomeInboxFragment` (permissions not yet wired in iteration 0-1).

---

### 6.3 How It Works

**File:** `screens/HowItWorksFragment.java`  
**Layout:** `screen_how_it_works.xml`  
**Purpose:** Explains DTN mesh networking to the user

#### UI Composition

```
ScrollView
└── LinearLayout (vertical)
    ├── MaterialToolbar — Back arrow + "How EchoDrop Works"
    ├── MaterialCardView — Step-by-step explanation
    │   ├── Step 1: Someone nearby drops a message
    │   ├── Step 2: Your phone picks it up via mesh
    │   └── Step 3: Messages expire after their TTL
    ├── 2×2 GridLayout — Feature highlights
    │   ├── "No Internet" / "Works offline..."
    │   ├── "Auto-Expire" / "Messages vanish..."
    │   ├── "Anonymous" / "No accounts needed..."
    │   └── "Local First" / "Only nearby people..."
    ├── HorizontalScrollView — Use case cards with emoji icons
    │   ├── Campus Alerts 🎓
    │   ├── Event Chat 🎵
    │   ├── Neighborhood 🏡
    │   └── Transit Updates 🚌
    └── MaterialButton — "Get Started" (primary)
```

#### Behavior

- Toolbar back navigation.
- "Get Started" navigates to `HomeInboxFragment`.

---

### 6.4 Home Inbox

**File:** `screens/HomeInboxFragment.java` (320 lines)  
**Layout:** `screen_home_inbox.xml`  
**Purpose:** The primary screen — displays message feed with filtering, search, and post creation

This is the most complex screen in the application. It implements the `PostComposerSheet.OnPostListener` callback interface.

#### UI Composition

```
ConstraintLayout
├── MaterialToolbar
│   ├── Navigation: hamburger menu icon
│   └── Menu: "Add post" action item (always shown)
├── Search Container (LinearLayout, horizontal)
│   ├── ImageView — Search icon (ic_search)
│   ├── EditText — Search input (bg_search_input drawable)
│   └── Sync Indicator (LinearLayout)
│       ├── View — Pulsing dot (8dp, bg_circle, primary_accent)
│       └── TextView — Sync count ("3 nearby")
├── Tab Bar (LinearLayout, horizontal, equal weight)
│   ├── "All" tab (with 2dp indicator below, selected by default)
│   ├── "Alerts" tab (with badge showing alert count)
│   └── "Chats" tab
├── RecyclerView — Message list
│   ├── LinearLayoutManager (vertical)
│   ├── DividerItemDecoration
│   └── Adapter: MessageAdapter
├── Empty State (LinearLayout, centered)
│   ├── View — Circle background (80dp)
│   ├── TextView — Headline
│   └── TextView — Subtitle (max 280dp width)
├── FloatingActionButton — Primary (56dp, primary_accent)
└── FloatingActionButton — Secondary (48dp, card_surface, 1dp border)
```

#### Key Behaviors

| Feature                | Implementation Details                                                |
|------------------------|-----------------------------------------------------------------------|
| **Tab Switching**      | Custom click listeners toggle `isSelected` state, text/indicator colors, and filter the message list. ALL=all messages, ALERTS=priority==ALERT only, CHATS=empty list (placeholder). |
| **Search Filtering**   | `TextWatcher` performs case-insensitive `contains()` on message text. Updates list in real-time. |
| **Focus Border**       | Search `EditText` uses `OnFocusChangeListener` to swap between `bg_search_input` (border color) and `bg_search_input_focused` (primary accent border). |
| **Sync Dot Pulse**     | `ObjectAnimator` animates alpha between 1.0 and 0.3 over 2000ms, `INFINITE` repeat with `REVERSE` mode. Cleaned up in `onDestroyView()`. |
| **FAB Press Scale**    | Both FABs use `OnTouchListener`: scale to 0.95× in 40ms on press, 1.0× in 80ms on release. |
| **Secondary FAB Entry**| Animated from `scaleX/Y=0` to `1` over 180ms with `OvershootInterpolator(1.2f)` for a bouncy entrance. |
| **Empty State Fade**   | Initially `alpha=0`, animates to `1` over 400ms with `FastOutSlowInInterpolator`. Shown when filtered list is empty. |
| **Alert Badge**        | Tab badge text shows count of messages with `Priority.ALERT`. Badge visibility toggles based on count > 0. |
| **RecyclerView Config**| `scrollbars="none"`, bottom padding 80dp for FAB clearance, `clipToPadding="false"`. |
| **Menu Action**        | Toolbar menu "Add post" opens `PostComposerSheet` bottom sheet. |
| **ViewModel**          | Observes `MessageViewModel.getMessages()` LiveData. On update, reapplies current filter and updates badges. |

---

### 6.5 Post Composer

**File:** `components/PostComposerSheet.java` (212 lines)  
**Layout:** `fragment_post_composer.xml`  
**Purpose:** BottomSheet for composing and posting new messages

#### UI Composition

```
LinearLayout (vertical)
├── Header
│   ├── TextView — "New Post" title
│   └── ImageButton — Close (ic_close)
├── Divider (1dp)
├── ScrollView
│   ├── EditText — Post input (maxLength=240, multiline)
│   ├── TextView — Character counter ("0 / 240")
│   ├── Label — "Scope"
│   ├── ChipGroup — Scope selection (single)
│   │   ├── Chip — "Nearby" (default selected)
│   │   ├── Chip — "Area"
│   │   └── Chip — "Event"
│   ├── Urgent Toggle Row
│   │   ├── SwitchMaterial — Urgent toggle
│   │   ├── TextView — Label ("Mark as urgent")
│   │   └── TextView — Hint ("This reaching more people")
│   ├── Label — "Expires in"
│   └── ChipGroup — TTL selection (single)
│       ├── Chip — "1 hour"
│       ├── Chip — "4 hours" (default selected)
│       ├── Chip — "12 hours"
│       └── Chip — "24 hours"
└── Footer (horizontal)
    ├── MaterialButton — "Cancel" (text style)
    └── MaterialButton — "Post" (filled, primary)
```

#### Key Behaviors

| Feature              | Implementation Details                                               |
|----------------------|----------------------------------------------------------------------|
| **Theme Override**   | `getTheme()` returns `R.style.Theme_EchoDrop_BottomSheet` — 20dp top-rounded corners, 60% backdrop dim amount. |
| **Chip Styling**     | Programmatic `ColorStateList` for checked/unchecked states. Checked: primary_tint bg + primary_accent stroke/text. Unchecked: transparent bg + border stroke + text_secondary. |
| **Character Counter**| Updates on every keystroke. Color transitions: secondary (0-199) → amber (200-239) → red (240, hard limit via `maxLength`). |
| **Submit Validation**| Post button enabled only when text is non-empty AND a scope chip is selected. Disabled state renders at 50% alpha. |
| **Urgent Toggle**    | `SwitchMaterial` with custom track tint: checked=alert_accent, unchecked=muted_disabled. Label changes on toggle. Hint text visibility toggles. |
| **Message Creation** | On submit: creates `Message` with selected scope, priority (ALERT if urgent, else NORMAL), calculated TTL millis, current timestamp. |
| **Callback**         | Calls `OnPostListener.onPost(message)` which `HomeInboxFragment` implements. Fragment dismisses itself after posting. |
| **Snackbar**         | Shown on activity's root view (`android.R.id.content`) so it remains visible after sheet dismissal. Styled with `echo_card_surface` bg and `echo_text_primary` text, 3000ms duration. |

---

## 7. Data Model

### `Message.java`

An immutable Plain Old Java Object (POJO) representing a single message bundle in the DTN network.

```java
public class Message {
    // Enums
    enum Scope    { LOCAL, ZONE, EVENT }
    enum Priority { ALERT, NORMAL, BULK }

    // Fields (all private final)
    String   id;         // UUID v4, auto-generated or explicit
    String   text;       // Message content (max 240 chars)
    Scope    scope;      // Geographic reach
    Priority priority;   // Urgency level
    long     createdAt;  // Epoch millis
    long     expiresAt;  // Epoch millis (createdAt + TTL)
    boolean  read;       // Read/unread state
}
```

#### Scope Enum

| Value   | UI Label  | Badge Color     | Meaning                            |
|---------|-----------|------------------|------------------------------------|
| `LOCAL` | "Local"   | positive_accent  | Immediate proximity (~100m)        |
| `ZONE`  | "Zone"    | primary_accent   | Extended area (~1km)               |
| `EVENT` | "Event"   | primary_accent   | Event-specific broadcast           |

#### Priority Enum

| Value    | Dot Color       | Label Visible | Border Color    | Meaning                |
|----------|-----------------|---------------|-----------------|------------------------|
| `ALERT`  | alert_accent    | Yes ("Alert") | alert_accent    | Urgent, time-sensitive |
| `NORMAL` | primary_accent  | No            | primary_accent  | Standard message       |
| `BULK`   | muted_disabled  | No            | muted_disabled  | Low-priority broadcast |

### `MessageViewModel.java`

Holds the in-memory message list as `MutableLiveData<List<Message>>`. Follows the Android MVVM lifecycle pattern.

```java
public class MessageViewModel extends ViewModel {
    MutableLiveData<List<Message>> messages;  // Observable list

    void seedMessages();     // Creates 3 demo messages on init
    void addMessage(Message); // Inserts at position 0 (newest first)
}
```

**Seed Data (3 messages):**

| # | Text                        | Scope | Priority | TTL | Created     |
|---|-----------------------------|-------|----------|-----|-------------|
| 1 | "Road closure on Main St…"  | LOCAL | ALERT    | 1h  | 20 min ago  |
| 2 | "Study group forming…"      | ZONE  | NORMAL   | 4h  | 35 min ago  |
| 3 | "Campus event starting…"    | EVENT | NORMAL   | 12h | 10 min ago  |

### `MessageAdapter.java`

Extends `ListAdapter<Message, MessageViewHolder>` with full `DiffUtil.ItemCallback` support for efficient, animated list updates.

#### DiffUtil Comparison

| Method             | Fields Compared                                    |
|--------------------|----------------------------------------------------|
| `areItemsTheSame`  | `id` equality                                      |
| `areContentsTheSame`| `text`, `scope`, `priority`, `expiresAt`, `read`  |

#### Binding Logic

| Element        | Source                           | Formatting                     |
|----------------|----------------------------------|--------------------------------|
| Preview text   | `message.getText()`              | Max 2 lines, ellipsize end    |
| Scope badge    | `message.getScope().name()`      | Colored by scope (see table)  |
| TTL label      | `message.getExpiresAt()`         | `formatTtl()` — hours/minutes |
| Priority dot   | `message.getPriority()`          | 8dp circle, colored by enum   |
| Priority label | `message.getPriority()`          | "Alert" if ALERT, else GONE   |
| Unread border  | `message.isRead()`               | 3dp left border if unread     |

#### TTL Formatting (`formatTtl`)

```
remaining >= 1 hour  →  "Xh"    (e.g., "3h")
remaining >= 1 min   →  "Xm"    (e.g., "45m")
remaining < 1 min    →  "<1m"
expired              →  "Expired"
```

---

## 8. Component Documentation

### `MainActivity.java`

The single host Activity for the entire application. Uses `FragmentContainerView` (`@+id/fragment_container`) as the navigation target.

#### Navigation Methods

```java
public void showPermissions()  // addToBackStack(null)
public void showHowItWorks()   // addToBackStack(null)
public void showHomeInbox()    // no back stack (terminal destination)
```

All three methods apply custom animations:

```java
transaction.setCustomAnimations(
    R.anim.fragment_enter,      // enter
    R.anim.fragment_exit,       // exit
    R.anim.fragment_pop_enter,  // popEnter
    R.anim.fragment_pop_exit    // popExit
);
```

### Downloadable Fonts

EchoDrop uses Google Downloadable Fonts to avoid bundling font files in the APK.

**Configuration Files:**

| File                     | Purpose                                        |
|--------------------------|------------------------------------------------|
| `res/font/inter.xml`    | Font request for Inter (weight 400)            |
| `res/font/roboto_mono.xml`| Font request for Roboto Mono (weight 400)    |
| `res/values/font_certs.xml`| Google Fonts provider certificate hashes    |
| `res/values/preloaded_fonts.xml`| Fonts to preload at app startup        |
| `AndroidManifest.xml`   | `<meta-data>` referencing `preloaded_fonts`    |

### Theme System

Defined in `res/values/themes.xml` (and mirrored in `res/values-night/themes.xml`):

```xml
<style name="Theme.EchoDrop" parent="Theme.Material3.Dark.NoActionBar">
    <item name="colorPrimary">@color/echo_primary_accent</item>
    <item name="colorOnPrimary">@color/echo_text_primary</item>
    <item name="colorSurface">@color/echo_card_surface</item>
    <item name="colorOnSurface">@color/echo_text_primary</item>
    <item name="android:colorBackground">@color/echo_bg_main</item>
    <item name="android:statusBarColor">@color/echo_bg_main</item>
    <item name="android:navigationBarColor">@color/echo_bg_main</item>
</style>
```

The `Theme.EchoDrop.BottomSheet` overlay adds:

```xml
<style name="Theme.EchoDrop.BottomSheet" parent="Theme.Material3.Dark.NoActionBar">
    <item name="bottomSheetStyle">@style/Widget.EchoDrop.BottomSheet</item>
    <item name="android:backgroundDimAmount">0.6</item>
</style>

<style name="Widget.EchoDrop.BottomSheet" parent="Widget.Material3.BottomSheet">
    <item name="shapeAppearanceOverlay">@style/ShapeAppearance.EchoDrop.BottomSheet</item>
</style>

<style name="ShapeAppearance.EchoDrop.BottomSheet" parent="">
    <item name="cornerSizeTopLeft">20dp</item>
    <item name="cornerSizeTopRight">20dp</item>
</style>
```

---

## 9. Build & Setup

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 11+**
- **Android SDK 35** installed via SDK Manager
- **Gradle 8.x** (bundled via wrapper)

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Run on connected device
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

### Build Configuration Highlights

```groovy
android {
    namespace 'com.dev.echodrop'
    compileSdk 35

    defaultConfig {
        applicationId "com.dev.echodrop"
        minSdk 24
        targetSdk 35
        versionCode 1
        versionName "1.0"
    }

    buildFeatures {
        viewBinding true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}
```

---

## 10. Iteration 0-1 Completion Status

> Full changelog with all implemented items is in [`CHANGELOG_ITERATION_01.md`](CHANGELOG_ITERATION_01.md).

### Summary

| Category            | Items | Status |
|---------------------|-------|--------|
| Resource XMLs       | 22    | ✅ Complete |
| Layout XMLs         | 7     | ✅ Complete |
| Java Files          | 9     | ✅ Complete |
| Animation XMLs      | 4     | ✅ Complete |
| Typography Styles   | 6     | ✅ Complete |
| Micro-Interactions  | 6     | ✅ Complete |
| Build Verification  | —     | ✅ `BUILD SUCCESSFUL` |

### Validation Checklist

- [x] Dark theme renders correctly across all screens
- [x] All 15 color tokens applied consistently
- [x] Typography scale (H1/H2/Body/Small/Mono) used appropriately
- [x] 8dp spacing grid followed in all layouts
- [x] Fragment transitions animate smoothly
- [x] FAB press feedback is tactile and immediate
- [x] Pulse animation loops indefinitely on onboarding
- [x] Search focus border transitions between states
- [x] Tab switching filters messages correctly
- [x] Character counter color thresholds (200/240) work
- [x] Post composer validates before allowing submit
- [x] Snackbar appears after posting
- [x] Sync dot pulses with correct timing
- [x] Empty state fades in gracefully
- [x] DiffUtil enables efficient list updates
- [x] ViewBinding used throughout (no `findViewById`)
- [x] ViewModel survives configuration changes
- [x] No lint errors or build warnings

---

*Documentation for EchoDrop Iteration 0-1 (Foundations + Visual Baseline)*  
*Last updated: 2025*

---

## 11. Iteration 2 Completion Status

> Full changelog with all implemented items is in [`CHANGELOG_ITERATION_02.md`](CHANGELOG_ITERATION_02.md).

### Summary

| Category                  | Items | Status |
|---------------------------|-------|--------|
| New Production Files      | 7     | ✅ Complete |
| Updated Production Files  | 6     | ✅ Complete |
| New Test Files            | 2     | ✅ Complete |
| Updated Test Files        | 4     | ✅ Complete |
| Dependencies Added        | 3     | ✅ (Room, WorkManager, lifecycle-livedata) |
| Build Verification        | —     | ✅ `BUILD SUCCESSFUL` |
| Unit Tests                | 191   | ✅ 0 failures (100% pass rate) |

### Key Features

- [x] Room persistence layer (MessageEntity, MessageDao, AppDatabase)
- [x] SHA-256 content deduplication (text + scope + hour bucket)
- [x] 200-row storage cap with priority-aware eviction (BULK → NORMAL, never ALERT)
- [x] Repository pattern (MessageRepo) with InsertCallback
- [x] WorkManager periodic TTL cleanup (every 15 minutes)
- [x] MessageDetailFragment with TTL progress bar (green/amber/red)
- [x] Dedup Snackbar feedback on duplicate post attempt
- [x] Auto mark-as-read on detail view
- [x] "Got it" button deletes message and navigates back

---

*Documentation for EchoDrop — Iterations 0-1 + 2 + 3*  
*Last updated: 2026*

---

## 12. Iteration 3 Completion Status

> Priority Handling + Inbox Semantics

### Summary

| Category                  | Items | Status |
|---------------------------|-------|--------|
| Updated Production Files  | 6     | ✅ Complete |
| New Drawables             | 1     | ✅ Complete |
| New Test Files            | 2     | ✅ Complete |
| Build Verification        | —     | ✅ `BUILD SUCCESSFUL` |
| Unit Tests                | 214   | ✅ 0 failures (100% pass rate) |

### Key Features

- [x] Priority-aware DAO query: `ORDER BY CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC, created_at DESC`
- [x] URGENT messages: left border `echo_alert_accent` (3dp), URGENT badge with `bg_badge_alert` background
- [x] NORMAL messages: left border `echo_primary_accent` when unread, no badge
- [x] BULK messages: left border `echo_muted_disabled` when unread
- [x] Reactive alert count badge on Alerts tab (fade animation 180ms)
- [x] Urgent banner in MessageDetailFragment (fade in + slide down 4dp over 180ms)
- [x] Post button color transitions from `echo_primary_accent` to `echo_alert_accent` when urgent toggle is ON (180ms ArgbEvaluator)
- [x] `getAlertCount()` LiveData in DAO, Repo, and ViewModel
- [x] Priority immutable after creation — no UI to change priority
- [x] BULK not user-selectable (reserved for system/forwarded messages)
