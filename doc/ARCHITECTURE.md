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
| No persistent storage            | Room database in iteration 1                |
| No actual DTN networking         | Nearby Connections API in iteration 2       |
| Permissions not requested        | Runtime permissions in iteration 1          |
| In-memory seed data only         | Database seeding + real message reception   |
| No message encryption            | End-to-end encryption in iteration 3        |
| Single-thread data operations    | Coroutines/RxJava for I/O operations        |
| No configuration change testing  | Espresso UI tests in iteration 1            |
| Hardcoded 3 seed messages        | Dynamic message reception via mesh          |

### Architecture Evolution Path

```
Iteration 0-1 (Current)
    └── MVVM + In-memory LiveData + Manual nav

Iteration 1 (Planned)
    └── + Room Database + Repository Pattern + Runtime Permissions

Iteration 2 (Planned)
    └── + Nearby Connections API + Background Service + WorkManager

Iteration 3 (Planned)
    └── + Encryption + Message Deduplication + TTL Enforcement

Iteration 4 (Planned)
    └── + Kotlin Migration + Navigation Component + Compose UI
```

---

*Architecture document for EchoDrop Iteration 0-1*  
*Last updated: 2025*
