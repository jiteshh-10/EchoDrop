# EchoDrop — Architecture Document

> Deep-dive into the architectural decisions, design patterns, and code organization of the EchoDrop Android application.

---

## Table of Contents

- [1. Architectural Overview](#1-architectural-overview)
- [2. MVVM Pattern Implementation](#2-mvvm-pattern-implementation)
- [3. Navigation Architecture](#3-navigation-architecture)
- [4. ViewBinding Strategy](#4-viewbinding-strategy)
- [5. RecyclerView & DiffUtil](#5-recyclerview--diffutil)
- [6. BottomSheet Dialog Architecture](#6-bottomsheet-dialog-architecture)
- [7. Animation Architecture](#7-animation-architecture)
- [8. Resource Architecture](#8-resource-architecture)
- [9. Thread Safety & Lifecycle](#9-thread-safety--lifecycle)
- [10. Design Decisions & Rationale](#10-design-decisions--rationale)
- [11. Known Limitations & Future Work](#11-known-limitations--future-work)
- [12. Room Persistence Layer (Iteration 2)](#12-room-persistence-layer-iteration-2)
- [13. Priority Handling (Iteration 3)](#13-priority-handling-iteration-3)
- [14. Private Chat System (Iteration 4)](#14-private-chat-system-iteration-4)
- [15. BLE Discovery Layer (Iteration 5)](#15-ble-discovery-layer-iteration-5)
- [16. Transfer Layer (Iteration 6)](#16-transfer-layer-iteration-6)

---

## 1. Architectural Overview

EchoDrop follows a **Single-Activity, Multi-Fragment** architecture with the **MVVM** (Model-View-ViewModel) pattern. This is the recommended architecture for modern Android applications.

### High-Level Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                        MainActivity                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │               FragmentContainerView                    │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │  OnboardingConsentFragment                       │  │  │
│  │  │  PermissionsFragment                             │  │  │
│  │  │  HowItWorksFragment                              │  │  │
│  │  │  HomeInboxFragment ←── PostComposerSheet          │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

┌────────────────────────────────┐
│         ViewModel Layer        │
│  ┌──────────────────────────┐  │
│  │    MessageViewModel      │  │
│  │    LiveData<List<Msg>>   │  │
│  └──────────────────────────┘  │
└────────────────────────────────┘

┌────────────────────────────────┐
│          Model Layer           │
│  ┌──────────────────────────┐  │
│  │    Message (POJO)        │  │
│  │    Scope / Priority      │  │
│  └──────────────────────────┘  │
└────────────────────────────────┘

┌────────────────────────────────┐
│        Adapter Layer           │
│  ┌──────────────────────────┐  │
│  │    MessageAdapter        │  │
│  │    ListAdapter + DiffUtil│  │
│  └──────────────────────────┘  │
└────────────────────────────────┘
```

### Layer Responsibilities

| Layer      | Components                    | Responsibility                              |
|------------|-------------------------------|---------------------------------------------|
| **View**   | Fragments, Layouts, Adapter   | Render UI, capture user input, observe data |
| **ViewModel** | `MessageViewModel`         | Hold UI state, survive config changes       |
| **Model**  | `Message`                     | Represent domain data, enforce invariants   |

---

## 2. MVVM Pattern Implementation

### Model (`Message.java`)

The `Message` class is an **immutable POJO** — all fields are `private final` with only getters exposed. This prevents accidental mutation of shared state.

```java
public class Message {
    private final String id;          // UUID — unique identity
    private final String text;        // Immutable after creation
    private final Scope scope;        // Enum — type-safe scope
    private final Priority priority;  // Enum — type-safe priority
    private final long createdAt;     // Epoch millis — monotonic
    private final long expiresAt;     // Epoch millis — deterministic TTL
    private final boolean read;       // Read state
}
```

**Design Choices:**

1. **Immutability:** Fields cannot be modified after construction. To update a message (e.g., mark as read), a new instance would be created. This aligns with the unidirectional data flow of MVVM.

2. **UUID Identity:** Each message gets a `UUID.randomUUID().toString()` on construction. This guarantees unique identity across devices in the DTN network — critical for deduplication in future iterations.

3. **Epoch Millis for Time:** Using `long` instead of `Date` or `Calendar` avoids timezone complexity and enables simple arithmetic for TTL calculation (`expiresAt - currentTimeMillis`).

4. **Enums for Scope/Priority:** Type-safe enums prevent invalid states. The adapter uses exhaustive `if/else` chains to handle each enum value with specific styling.

### ViewModel (`MessageViewModel.java`)

```java
public class MessageViewModel extends ViewModel {
    private final MutableLiveData<List<Message>> messages = new MutableLiveData<>();

    public MessageViewModel() {
        seedMessages();  // Initialize with demo data
    }

    public LiveData<List<Message>> getMessages() {
        return messages;  // Expose as read-only LiveData
    }

    public void addMessage(Message message) {
        List<Message> current = messages.getValue();
        if (current == null) current = new ArrayList<>();
        List<Message> updated = new ArrayList<>(current);
        updated.add(0, message);  // Newest first
        messages.setValue(updated);
    }
}
```

**Design Choices:**

1. **`MutableLiveData` internalized:** The `MutableLiveData` is private; only `LiveData` (read-only) is exposed. This prevents fragments from directly mutating the data store — they must go through `addMessage()`.

2. **Defensive Copy:** `addMessage()` creates a new `ArrayList` from the current list, adds the new message, and sets the entire new list. This triggers LiveData observers and ensures the `DiffUtil` in the adapter has old vs. new list references to compare.

3. **`ViewModelProvider` Scoping:** In `HomeInboxFragment`:
   ```java
   viewModel = new ViewModelProvider(this).get(MessageViewModel.class);
   ```
   The ViewModel is scoped to the fragment's lifecycle. It survives configuration changes (rotation, etc.) but is cleared when the fragment is finally destroyed.

### View Layer (Fragments)

The `HomeInboxFragment` observes the ViewModel:

```java
viewModel.getMessages().observe(getViewLifecycleOwner(), allMessages -> {
    this.allMessages = allMessages;
    applyFilter();
    updateBadges();
});
```

**Key:** `getViewLifecycleOwner()` (not `this`) is used as the lifecycle owner. This ensures the observer is automatically removed when the fragment's view is destroyed, preventing memory leaks and stale updates to a detached view hierarchy.

---

## 3. Navigation Architecture

### Why Manual Fragment Transactions?

EchoDrop uses manual `FragmentTransaction` instead of Jetpack Navigation Component. This decision was made for iteration 0-1 to:

1. **Minimize dependencies** — No nav graph XML or SafeArgs plugin needed
2. **Direct control** — Full control over animation timing and backstack behavior
3. **Simplicity** — With only 4 fragments and a linear flow, the Navigation Component would be over-engineering

### Transaction Pattern

All navigation is centralized in `MainActivity`:

```java
public void showPermissions() {
    getSupportFragmentManager().beginTransaction()
        .setCustomAnimations(
            R.anim.fragment_enter,     // New fragment enters
            R.anim.fragment_exit,      // Current fragment exits
            R.anim.fragment_pop_enter, // Previous fragment re-enters (back)
            R.anim.fragment_pop_exit   // Current fragment exits (back)
        )
        .replace(R.id.fragment_container, new PermissionsFragment())
        .addToBackStack(null)          // Enable back navigation
        .commit();
}
```

### Back Stack Strategy

| Transition                     | Back Stack? | Rationale                                  |
|--------------------------------|-------------|---------------------------------------------|
| Onboarding → Permissions       | Yes         | User may want to go back                   |
| Onboarding → How It Works      | Yes         | User may want to go back                   |
| Any → Home Inbox               | No          | Terminal destination; prevent going back    |

When navigating to `HomeInboxFragment`, no `addToBackStack()` is called. This means pressing Back on the home screen exits the app rather than returning to onboarding.

### Fragment ↔ Activity Communication

Fragments communicate with `MainActivity` by casting `getActivity()`:

```java
// In OnboardingConsentFragment
((MainActivity) requireActivity()).showPermissions();
```

This is a standard Android pattern. The `requireActivity()` call throws `IllegalStateException` if the fragment is detached, providing fail-fast behavior.

---

## 4. ViewBinding Strategy

### Setup

ViewBinding is enabled in `app/build.gradle`:

```groovy
buildFeatures {
    viewBinding true
}
```

This generates a binding class for every layout XML. For example, `screen_home_inbox.xml` generates `ScreenHomeInboxBinding`.

### Fragment Binding Pattern

All fragments follow this lifecycle-safe pattern:

```java
public class SomeFragment extends Fragment {
    private ScreenSomeBinding binding;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        binding = ScreenSomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(View view, Bundle savedState) {
        super.onViewCreated(view, savedState);
        // Access views via binding.viewId
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;  // Prevent memory leak
    }
}
```

**Critical:** Setting `binding = null` in `onDestroyView()` is essential. Fragments can outlive their views (e.g., on the back stack). Without nulling the binding, the fragment would hold a reference to a destroyed view hierarchy, causing memory leaks.

### Benefits Over `findViewById()`

| Aspect         | `findViewById()`              | ViewBinding                          |
|----------------|-------------------------------|--------------------------------------|
| Null Safety    | Returns `null` if ID missing  | Compile-time guaranteed non-null     |
| Type Safety    | Requires explicit cast        | Automatically typed                  |
| Performance    | View tree traversal each call | Direct field reference               |
| Refactoring    | Runtime crash if ID changes   | Compile error if ID changes          |

---

## 5. RecyclerView & DiffUtil

### Adapter Architecture

`MessageAdapter` extends `ListAdapter<Message, MessageViewHolder>` — the modern recommended approach replacing `RecyclerView.Adapter`.

```
ListAdapter<Message, MessageViewHolder>
    │
    ├── submitList(List<Message>)    ← Called by fragment when filter changes
    │       │
    │       └── DiffUtil.ItemCallback<Message>
    │               ├── areItemsTheSame(old, new)    → id comparison
    │               └── areContentsTheSame(old, new) → field comparison
    │
    └── onBindViewHolder(holder, position)
            ├── Scope badge coloring
            ├── Priority dot/label/border
            ├── TTL countdown calculation
            └── Unread state border
```

### DiffUtil Efficiency

When `submitList()` is called with a new list:

1. **`areItemsTheSame`** checks if an item at a position represents the same logical entity (by UUID `id`). This tells DiffUtil which items were added, removed, or moved.

2. **`areContentsTheSame`** checks if an existing item's visual state changed (text, scope, priority, expiresAt, read). Only items that fail this check get `onBindViewHolder()` called again.

This means if a list of 100 messages changes by 1 message:
- Without DiffUtil: All 100 items re-bound → 100 `onBindViewHolder()` calls
- With DiffUtil: Only the changed item re-bound → 1 `onBindViewHolder()` call + animated insert/remove

### ViewHolder Pattern

```java
static class MessageViewHolder extends RecyclerView.ViewHolder {
    final ItemMessageCardBinding binding;

    MessageViewHolder(ItemMessageCardBinding binding) {
        super(binding.getRoot());
        this.binding = binding;
    }
}
```

ViewBinding is used within the ViewHolder — `ItemMessageCardBinding` is inflated in `onCreateViewHolder()` and stored for reuse in `onBindViewHolder()`.

---

## 6. BottomSheet Dialog Architecture

### Theme Hierarchy

```
Theme.Material3.Dark.NoActionBar
    └── Theme.EchoDrop.BottomSheet
            ├── bottomSheetStyle → Widget.EchoDrop.BottomSheet
            │       └── shapeAppearanceOverlay → ShapeAppearance.EchoDrop.BottomSheet
            │               ├── cornerSizeTopLeft: 20dp
            │               └── cornerSizeTopRight: 20dp
            └── backgroundDimAmount: 0.6
```

### Implementation

`PostComposerSheet` extends `BottomSheetDialogFragment` and overrides `getTheme()`:

```java
@Override
public int getTheme() {
    return R.style.Theme_EchoDrop_BottomSheet;
}
```

This approach is preferred over setting the theme in the constructor because it:
1. Keeps the theme declaration in XML (separation of concerns)
2. Allows the theme to inherit from the app theme
3. Is the officially documented approach for Material BottomSheets

### Communication Pattern

The BottomSheet communicates back to the hosting fragment via a callback interface:

```java
// In PostComposerSheet
public interface OnPostListener {
    void onPost(Message message);
}

// In HomeInboxFragment
public class HomeInboxFragment extends Fragment implements PostComposerSheet.OnPostListener {
    @Override
    public void onPost(Message message) {
        viewModel.addMessage(message);  // Update ViewModel → triggers LiveData → UI updates
    }
}
```

**Data Flow:**

```
User types → PostComposerSheet → OnPostListener.onPost() → HomeInboxFragment
    → viewModel.addMessage() → LiveData.setValue() → Observer triggered
    → applyFilter() → adapter.submitList() → DiffUtil → UI updated
```

This ensures unidirectional data flow: the BottomSheet never directly modifies the RecyclerView or adapter.

---

## 7. Animation Architecture

### Categories

EchoDrop's animations fall into three categories:

#### 1. Fragment Transitions (XML-based)

Defined as `<set>` resources in `res/anim/`. Applied via `setCustomAnimations()` on `FragmentTransaction`.

```xml
<!-- fragment_enter.xml -->
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:interpolator="@android:anim/decelerate_interpolator">
    <alpha android:fromAlpha="0.0" android:toAlpha="1.0" android:duration="250" />
    <translate android:fromYDelta="12dp" android:toYDelta="0" android:duration="250" />
</set>
```

**Design:** 12dp slide distance is subtle enough to feel natural without being distracting. Decelerate interpolator gives a "settling" feel on entrance; accelerate gives a "departing" feel on exit.

#### 2. Continuous Animations (Programmatic)

Long-running animations that loop indefinitely:

- **Pulse Ring:** `AnimationSet` with `ScaleAnimation` + `AlphaAnimation`. Uses the legacy `android.view.animation` API because `AnimatorSet` doesn't support infinite repeat on the set level.

- **Sync Dot:** `ObjectAnimator` on the `alpha` property. Uses the modern Property Animation API with `REVERSE` repeat mode for smooth back-and-forth pulsing.

#### 3. Touch Feedback (Programmatic)

Immediate response animations triggered by `OnTouchListener`:

```java
view.setOnTouchListener((v, event) -> {
    switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            v.animate().scaleX(0.95f).scaleY(0.95f)
                .setDuration(40).start();    // Instant feedback
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            v.animate().scaleX(1f).scaleY(1f)
                .setDuration(80).start();    // Smooth return
            break;
    }
    return false;  // Don't consume — let click listener fire
});
```

**Critical:** Returning `false` from `OnTouchListener` is essential. If `true` were returned, the touch event would be consumed and `OnClickListener`s would never fire.

### Animation Lifecycle Management

The sync dot `ObjectAnimator` is stored as a field and explicitly cancelled in `onDestroyView()`:

```java
private ObjectAnimator syncPulseAnimator;

@Override
public void onDestroyView() {
    if (syncPulseAnimator != null) {
        syncPulseAnimator.cancel();
        syncPulseAnimator = null;
    }
    super.onDestroyView();
    binding = null;
}
```

This prevents:
- Memory leaks (animator holds reference to view)
- Crashes (animation updating a detached view)
- Resource waste (animation running in background)

---

## 8. Resource Architecture

### String Resources

All user-facing text is externalized to `res/values/strings.xml`. This enables:
- Future localization (L10n)
- Single source of truth for copy
- Compile-time validation of format strings

#### Format Strings

```xml
<string name="post_char_counter">%1$d / 240</string>
<string name="sync_count">%1$d nearby</string>
<string name="ttl_format">Expires in %1$s</string>
```

Accessed via `getString(R.string.post_char_counter, length)` which provides compile-time format validation.

#### Special Characters

| Character | Encoding in XML   | Reason                               |
|-----------|-------------------|---------------------------------------|
| Apostrophe| `\'`              | AAPT requires escaping in strings.xml |
| Emoji     | Literal UTF-8     | Surrogate pairs (`\uD83C\uDF93`) cause AAPT failures |
| Ampersand | `&amp;`           | XML entity requirement                |

### Drawable Resources

All drawables are XML shape definitions — no raster images in the project:

| File                       | Type     | Shape        | Key Properties              |
|----------------------------|----------|--------------|------------------------------|
| `bg_search_input.xml`      | Shape    | Rectangle    | 8dp corners, border stroke  |
| `bg_search_input_focused.xml`| Shape  | Rectangle    | 8dp corners, accent stroke  |
| `bg_badge_*.xml`           | Shape    | Rectangle    | 4dp corners, tint fill      |
| `bg_circle.xml`            | Shape    | Oval         | Circle for dots/indicators  |
| `bg_icon_holder.xml`       | Shape    | Oval         | Circular icon container     |
| `ic_wifi.xml`              | Vector   | Path         | Custom WiFi icon drawable   |

### Dimension Tokens

All dimensions follow an 8dp base grid:

```
8dp → 12dp → 16dp → 24dp → 32dp
 1      1.5    2      3      4
```

This creates visual rhythm and consistency across the UI. Every spacing value is a multiple (or 1.5×) of the base unit.

---

## 9. Thread Safety & Lifecycle

### LiveData Thread Safety

`LiveData.setValue()` is called on the main thread (as required by the API). Since all message operations are currently in-memory, no background threading is needed. When the DTN networking layer is added in future iterations, `postValue()` will be used from background threads.

### Fragment Lifecycle Awareness

| Concern                  | Solution                                    |
|--------------------------|---------------------------------------------|
| View references after destroy | `binding = null` in `onDestroyView()`  |
| LiveData observation leak     | `getViewLifecycleOwner()` as owner     |
| Animator leak                 | Cancel in `onDestroyView()`            |
| Activity reference after detach| `requireActivity()` for fail-fast     |

### Configuration Change Survival

The `MessageViewModel` survives configuration changes (screen rotation, locale change, etc.) because:

1. `ViewModelProvider` caches the instance per lifecycle owner
2. `LiveData` re-emits the last value to new observers
3. Fragment re-subscribes in `onViewCreated()` with the fresh view lifecycle owner

---

## 10. Design Decisions & Rationale

### Why Java Instead of Kotlin?

Iteration 0-1 uses Java for maximum accessibility. The project may migrate to Kotlin in a future iteration as the team grows. All patterns used (ViewBinding, LiveData, ViewModel) work identically in both languages.

### Why No Navigation Component?

With only 4 fragments in a mostly-linear flow, the Navigation Component adds complexity (nav graph XML, SafeArgs plugin, dependency) without proportional benefit. If the app grows beyond 8 screens, migration to the Navigation Component is recommended.

### Why Manual Tab Bar Instead of `TabLayout`?

The custom tab bar in `HomeInboxFragment` uses simple `TextView` click listeners instead of `TabLayout` + `ViewPager2`. This was chosen because:
1. Only 3 tabs with no swipe gesture needed
2. Tab switching filters a single list (not separate fragments)
3. Badge positioning requires custom views anyway
4. Avoids ViewPager2 dependency

### Why `ListAdapter` Instead of `RecyclerView.Adapter`?

`ListAdapter` provides:
1. Built-in `DiffUtil` support — no manual `notifyDataSetChanged()` calls
2. Animated insertions/removals/moves automatically
3. Background thread diffing via `AsyncListDiffer`
4. Cleaner API — `submitList()` instead of manual list management

### Why 240 Character Limit?

The 240-character limit mirrors the constraints of DTN payloads:
1. Messages must fit in low-bandwidth mesh transmissions
2. Encourages concise, actionable content
3. Reduces storage overhead on relay devices
4. Visual: entire message visible without scrolling in the inbox card

### Why Immutable `Message` Objects?

Immutability provides:
1. **Thread safety** — No synchronization needed when sharing across threads
2. **DiffUtil correctness** — Old and new lists can be compared safely
3. **Predictability** — Once created, a message never changes unexpectedly
4. **Future-proofing** — DTN relay nodes should not modify messages in transit

---

## 11. Known Limitations & Future Work

### Current Limitations (Iteration 0-1)

| Limitation                       | Planned Resolution                          |
|----------------------------------|---------------------------------------------|
| No actual DTN networking         | Nearby Connections API in future iteration   |
| Permissions not requested        | Runtime permissions in future iteration      |
| No message encryption            | End-to-end encryption in future iteration    |
| Hardcoded destructive migration  | Proper migration strategy for production     |

### Architecture Evolution Path

```
Iteration 0-1 (Complete)
    └── MVVM + In-memory LiveData + Manual nav

Iteration 2 (Complete)
    └── + Room Database + Repository Pattern + WorkManager
    └── + SHA-256 Dedup + Storage Cap + TTL Cleanup
    └── + MessageDetailFragment with TTL progress

Future Iterations
    └── + Nearby Connections API + Background Service
    └── + Encryption + Runtime Permissions + Compose UI
```

---

## 12. Room Persistence Layer (Iteration 2)

### Database Schema

```
┌──────────────────────────────────────────────────────────────────┐
│                        messages (table)                           │
├──────────────────┬───────────┬───────────────────────────────────┤
│ Column           │ Type      │ Constraints                       │
├──────────────────┼───────────┼───────────────────────────────────┤
│ id               │ TEXT      │ PRIMARY KEY                       │
│ text             │ TEXT      │ NOT NULL                          │
│ scope            │ TEXT      │ NOT NULL (LOCAL/ZONE/EVENT)       │
│ priority         │ TEXT      │ NOT NULL (ALERT/NORMAL/BULK)      │
│ created_at       │ INTEGER   │ NOT NULL (epoch ms)               │
│ expires_at       │ INTEGER   │ NOT NULL (epoch ms)               │
│ read             │ INTEGER   │ NOT NULL (0/1)                    │
│ content_hash     │ TEXT      │ NOT NULL, UNIQUE                  │
├──────────────────┴───────────┴───────────────────────────────────┤
│ Indices: content_hash (unique), expires_at, priority             │
└──────────────────────────────────────────────────────────────────┘
```

### Data Flow

```
┌─────────┐     ┌──────────────┐     ┌────────────┐     ┌──────────┐
│ Fragment │────▶│ MessageRepo  │────▶│ MessageDao │────▶│ Room DB  │
│   (UI)  │     │ (Business    │     │ (SQL)      │     │ (SQLite) │
│         │◀────│  Logic)      │◀────│            │◀────│          │
└─────────┘     └──────────────┘     └────────────┘     └──────────┘
     │                │
     │                ▼
     │          ┌──────────────┐
     │          │ WorkManager  │
     │          │ TTL Cleanup  │
     │          └──────────────┘
     │
     ▼
┌─────────────────┐
│ MessageViewModel│
│ (AndroidViewModel│
│  + LiveData)    │
└─────────────────┘
```

### Deduplication Strategy

Messages are deduplicated using SHA-256 hashing:

```
hash = SHA-256(lowercase(text) + "|" + scope + "|" + hour_bucket)
```

- **Text** is lowercased and trimmed before hashing
- **Hour bucket** = `createdAt / 3600000` — groups messages within the same hour
- Hash is stored in `content_hash` column with UNIQUE constraint
- Insert uses `OnConflictStrategy.IGNORE` as safety net

### Storage Cap Enforcement

When total message count exceeds 200:

1. **Phase 1:** Delete oldest BULK messages (by `created_at` ASC)
2. **Phase 2:** If still over cap, delete oldest NORMAL messages
3. **ALERT messages are never evicted** by storage cap

### TTL Cleanup

- **Periodic:** WorkManager `PeriodicWorkRequest` every 15 minutes (`ExistingPeriodicWorkPolicy.KEEP`)
- **On-demand:** `OneTimeWorkRequest` fired on every `Activity.onResume()`
- **Query:** `DELETE FROM messages WHERE expires_at <= :now`

---

*Architecture document for EchoDrop — Iterations 0-1 + 2 + 3*  
*Last updated: 2026*

---

## 13. Priority Handling (Iteration 3)

### Priority Classes

| Class   | Ordinal | DAO Sort | Eviction Order | User-Selectable |
|---------|---------|----------|----------------|-----------------|
| ALERT   | 0       | First    | Never evicted  | Yes (urgent toggle) |
| NORMAL  | 1       | Second   | After BULK     | Yes (default)   |
| BULK    | 2       | Last     | First evicted  | No (system/forward only) |

### Priority-Aware DAO Query

```sql
SELECT * FROM messages
WHERE expires_at > :now
ORDER BY
  CASE priority WHEN 'ALERT' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END ASC,
  created_at DESC
```

This is the **single source of ordering truth** — the adapter never manually reorders.

### Alert Count Query

```sql
SELECT COUNT(*) FROM messages WHERE priority = 'ALERT' AND expires_at > :now
```

Exposed as `LiveData<Integer>` through `MessageDao.getAlertCount()` → `MessageRepo.getAlertCount()` → `MessageViewModel.getAlertCount()`.

### Visual Treatment

| Priority | Left Border     | Badge              | Detail Banner |
|----------|-----------------|--------------------|---------------|
| ALERT    | 3dp `echo_alert_accent` | "URGENT" with `bg_badge_alert` | Fade+slide banner with AlertCircle icon |
| NORMAL   | 3dp `echo_primary_accent` (unread) | None | None |
| BULK     | 3dp `echo_muted_disabled` (unread) | None | None |

### Post Composer Behavior

- Users choose Normal (default) or Urgent only
- BULK is not exposed in the Post Composer UI
- When urgent toggle is ON, Post button transitions from `echo_primary_accent` → `echo_alert_accent` over 180ms using `ArgbEvaluator`
- Priority is immutable after message creation

### Retention Policy (unchanged from Iteration 2)

When storage cap (200 rows) is reached:
1. Delete oldest BULK messages first
2. If still over cap, delete oldest NORMAL messages
3. ALERT messages are **never evicted** before their TTL expires

---

## 14. Private Chat System (Iteration 4)

### Overview

Private Chat adds local-only, encrypted 1:1 messaging. Chats are identified by shareable 8-character codes. All message payloads are encrypted with AES-256-GCM before storage; the key is derived from the chat code and never persisted.

### Database Schema

**ChatEntity** (`chats` table):

| Column               | Type    | Notes                     |
|----------------------|---------|---------------------------|
| `id`                 | TEXT PK | UUID                      |
| `code`               | TEXT    | UNIQUE, 8-char code       |
| `name`               | TEXT    | Nullable display name     |
| `created_at`         | INTEGER | Epoch millis              |
| `last_message_preview`| TEXT   | Plaintext snippet (≤50 chars) |
| `last_message_time`  | INTEGER | For sort ordering         |
| `unread_count`       | INTEGER | 0 by default              |

**ChatMessageEntity** (`chat_messages` table):

| Column      | Type    | Notes                        |
|-------------|---------|------------------------------|
| `id`        | TEXT PK | UUID                         |
| `chat_id`   | TEXT FK | References `chats.id`, CASCADE |
| `text`      | TEXT    | Base64(IV ‖ ciphertext ‖ tag) |
| `is_outgoing`| INTEGER| 0 = incoming, 1 = outgoing   |
| `created_at`| INTEGER | Epoch millis                 |
| `sync_state`| INTEGER | 0=PENDING, 1=SENT, 2=SYNCED  |

### Encryption Pipeline

```
Chat Code (8 chars)
    │
    ▼
PBKDF2WithHmacSHA256
  salt: "EchoDrop-ChatKey-v1"
  iterations: 10,000
  key length: 256 bits
    │
    ▼
AES-256-GCM
  IV: 96-bit random (prepended)
  tag: 128-bit
    │
    ▼
Base64.NO_WRAP → stored in `text` column
```

- Key derivation happens once per fragment lifecycle (`ChatConversationFragment.onViewCreated`)
- Key is cleared on `onDestroyView` (set to null)
- Key is never written to disk or SharedPreferences

### Code Generation

Character set: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (30 chars, excludes O/0/I/1 for visual clarity)

- 8 characters → ~$30^8$ (≈6.56 × 10¹¹) possible codes
- Generated via `SecureRandom`
- Displayed as `XXXX-XXXX` format
- QR code generated via ZXing `QRCodeWriter`

### Navigation Flow

```
HomeInboxFragment (Chats tab / FAB)
    │
    ▼
PrivateChatListFragment
    ├── FAB → CreateChatFragment
    ├── Join button → AlertDialog
    └── Chat item click → ChatConversationFragment
```

### MVVM Layer

```
ChatDao ← ChatRepo ← ChatViewModel → Fragment
                                      ├── ChatListAdapter
                                      └── ChatMessageAdapter
```

- `ChatViewModel` is scoped to the Activity (shared between chat fragments)
- `ChatRepo` uses a single-thread `ExecutorService` for all writes
- `ChatDao` exposes `LiveData<List<>>` for reactive updates

---

## 15. BLE Discovery Layer (Iteration 5)

### Overview

Iteration 5 introduces offline peer discovery via Bluetooth Low Energy (BLE). The BLE layer is a **control plane only** — it discovers nearby devices and exchanges manifests describing available messages. Actual message transfer is deferred to a future iteration.

### Component Architecture

```
EchoService (ForegroundService)
    ├── BleAdvertiser (advertise custom UUID + payload)
    └── BleScanner (10s scan / 20s pause duty cycle)
            └── peer map (ConcurrentHashMap<deviceId, PeerInfo>)

ManifestManager
    └── Room DB → MessageDao.getActiveMessagesDirect()
            → serialize to compact binary (28 bytes/entry)
```

### BLE Advertising

- **Service UUID:** `ed000001-0000-1000-8000-00805f9b34fb`
- **Payload (6 bytes):** `device_id` (4 bytes, big-endian) + `manifest_size` (2 bytes, big-endian)
- **Mode:** `ADVERTISE_MODE_LOW_POWER`, connectable, no timeout
- **TX Power:** `ADVERTISE_TX_POWER_MEDIUM`

### BLE Scanning

- **Duty cycle:** 10 seconds scan, 20 seconds pause (33% duty)
- **Filter:** Only EchoDrop Service UUID
- **Peer timeout:** 2 minutes without re-detection → pruned
- **Thread safety:** `ConcurrentHashMap` for peer storage; scan callbacks process on BLE thread

### Manifest Wire Format

Each manifest is a compact binary payload designed to fit within a single BLE GATT characteristic (~512 bytes):

```
Header (3 bytes):
  version     : 1 byte  (0x01)
  entry_count : 2 bytes (big-endian unsigned short)

Entry (28 bytes each, max 18 entries):
  bundle_id   : 16 bytes (UUID, big-endian)
  checksum    :  4 bytes (first 4 bytes of SHA-256 of message text)
  priority    :  1 byte  (0=ALERT, 1=NORMAL, 2=BULK)
  reserved    :  3 bytes (alignment padding)
  expires_at  :  4 bytes (Unix epoch seconds, big-endian)
```

Maximum manifest size: 3 + 18 × 28 = **507 bytes**.

### Foreground Service Lifecycle

```
Boot / User Toggle ON
    → BootReceiver / SettingsFragment
        → EchoService.startService(context)
            → hasBlePermissions() check
                → startForeground(notification)
                    → BleAdvertiser.start()
                    → BleScanner.start()

User Toggle OFF / Service Destroyed
    → BleAdvertiser.stop()
    → BleScanner.stop()
```

### Permission Model (API 31+)

The `connectedDevice` foreground service type requires at least one of:
- `BLUETOOTH_ADVERTISE`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_SCAN`

Permissions are requested via `ActivityResultLauncher<String[]>` in `SettingsFragment` before the service is started. `EchoService.startService()` silently returns if permissions are not granted.

### Navigation Flow

```
HomeInboxFragment (Settings icon)
    │
    ▼
SettingsFragment
    ├── Background toggle → EchoService start/stop
    ├── Battery guide → BatteryGuideFragment (collapsible OEM sections)
    └── 7-tap version → DiscoveryStatusFragment (dev debug screen)
```

---

## 16. Transfer Layer (Iteration 6)

The transfer layer implements the **data plane** for DTN message propagation over Wi-Fi Direct. It complements the control plane (BLE manifest exchange) from Iteration 5.

### Components

| Class              | Responsibility                                                 |
|--------------------|---------------------------------------------------------------|
| `TransferProtocol` | Wire format: framed TCP sessions with magic header "ED06"      |
| `WifiDirectManager`| Wi-Fi P2P lifecycle: discover, connect, disconnect, teardown   |
| `BundleSender`     | Outbound TCP transfer with expired message filtering            |
| `BundleReceiver`   | Inbound TCP server on port 9876, dedup, notification posting   |

### Wire Protocol

```
Session Format:
  "ED06"           (4 bytes — magic)
  message_count    (4 bytes — int)
  frame[0..N-1]    (variable — serialized messages)

Frame Format:
  payload_length   (4 bytes — int, max 512KB)
  payload          (variable — serialized MessageEntity fields)

Field Serialization:
  Each string field: 2-byte length prefix + UTF-8 bytes
  Long fields: 8 bytes (DataOutputStream.writeLong)
  Fields: id, text, scope, priority, createdAt, expiresAt, contentHash
```

### Transfer Flow

```
BLE Discovery (iter-5)
    → Manifest exchange identifies missing messages
        → WifiDirectManager.discoverPeers()
            → WifiDirectManager.connect(device)
                → onConnected(groupOwnerAddress, isGroupOwner)
                    ├── Group Owner: BundleReceiver.start() (ServerSocket on 9876)
                    └── Client: BundleSender.send(address, messages, callback)

BundleSender:
    filter expired → sort by priority → writeSession() → TCP → callback

BundleReceiver:
    accept() → readSession() → for each message:
        skip expired → validateChecksum() → isDuplicateSync() → dao.insert()
        → post notification → ReceiveCallback
```

### Priority Ordering

Sessions are written with messages sorted by priority:
1. **ALERT** (ordinal 0) — sent first, never evicted
2. **NORMAL** (ordinal 1) — standard messages
3. **BULK** (ordinal 2) — lowest priority, evicted first

### Checksum Validation

Received messages are validated using `MessageEntity.computeHash()`:
- `SHA-256(lowercase(text) + "|" + scope + "|" + hour_bucket)`
- Mismatched checksums cause the message to be silently discarded

### EchoService Integration

```
EchoService (Foreground Service)
    ├── BleAdvertiser (iter-5)
    ├── BleScanner (iter-5) ─── setPeerUpdateListener ──┐
    ├── WifiDirectManager (iter-6) ◄────────────────────┘
    │       ├── discoverPeers() triggered by BLE peer detection
    │       ├── auto-connect to first available peer
    │       └── onConnected → client: sendAllMessages() / owner: BundleReceiver
    ├── BundleSender (hotfix-01) — outbound TCP transfer
    └── BundleReceiver (iter-6) — inbound TCP server on port 9876
```

#### End-to-End Orchestration Pipeline (Hotfix 01)

The full discovery → connection → transfer pipeline is wired in `EchoService.onCreate()`:

```
BLE Scanner detects peers
    → notifyPeerCount() → HomeInboxFragment sync indicator
    → WifiDirectManager.discoverPeers() (if not connected)
        → onPeersAvailable → auto-connect to first peer
            → onConnected(address, isGroupOwner):
                ├── Client: sendAllMessages(groupOwnerAddress)
                │   └── queries DAO → BundleSender.send() → disconnect
                └── Group Owner: BundleReceiver already listening on :9876
            → onDisconnected: reset flag, await next BLE cycle
```

#### Auto-Start Behavior (Hotfix 01)

The service starts automatically in two scenarios:
1. **Onboarding:** `PermissionsFragment` calls `EchoService.startService()` immediately after permissions are granted
2. **App relaunch:** `MainActivity.onCreate()` calls `EchoService.startService()` if `hasBlePermissions() && isBackgroundEnabled()`

#### UI Observers

- `TransferStateListener` (static interface) enables `HomeInboxFragment` to adjust the sync dot pulse speed: 500ms during active transfer, 2000ms otherwise.
- `PeerCountListener` (static interface) enables `HomeInboxFragment` to display the real BLE peer count in the sync indicator.

### Permissions (Wi-Fi Direct)

| Permission                       | Purpose                           |
|----------------------------------|-----------------------------------|
| `ACCESS_WIFI_STATE`              | Query Wi-Fi P2P state             |
| `CHANGE_WIFI_STATE`              | Enable Wi-Fi P2P                  |
| `INTERNET`                       | TCP socket communication          |
| `CHANGE_NETWORK_STATE`           | Network state management          |
| `ACCESS_NETWORK_STATE`           | Check network connectivity        |
| `NEARBY_WIFI_DEVICES` (API 33+)  | Wi-Fi Direct device discovery     |
