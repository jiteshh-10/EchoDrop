# EchoDrop — Iteration 2 Changelog

> Historical scope note: this file documents Iteration 2 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> **Iteration Name:** Local Persistence + Message Lifecycle  
> **Branch:** `iteration-2`  
> **Status:** ✅ Complete — `BUILD SUCCESSFUL` — 191 tests, 0 failures

---

## Summary

Iteration 2 replaces the in-memory Message POJO with a full Room persistence layer, adds SHA-256 content deduplication, WorkManager-based TTL cleanup, a MessageDetailFragment with TTL progress bar, and storage cap enforcement (200 rows).

**New files created:** 9 (7 production + 2 test)  
**Files updated:** 10 (6 production + 4 test)  
**Total unit tests:** 191 (up from 151)  
**Dependencies added:** Room 2.6.1, WorkManager 2.9.0, lifecycle-livedata 2.7.0

---

## New Production Files (7)

### `db/MessageEntity.java`
- **Path:** `app/src/main/java/com/dev/echodrop/db/MessageEntity.java`
- **Purpose:** Room `@Entity` replacing Message POJO as primary data class
- **Details:**
  - Table `"messages"` with indices on `content_hash` (unique), `expires_at`, `priority`
  - Fields: `id` (PK), `text`, `scope` (String), `priority` (String), `created_at`, `expires_at`, `read`, `content_hash`
  - Nested enums: `Scope` (LOCAL/ZONE/EVENT), `Priority` (ALERT/NORMAL/BULK)
  - Static factory `create()` auto-generates UUID + SHA-256 hash
  - `fromMessage()` converter for backward compatibility with legacy POJO
  - `computeHash()` — SHA-256 of `lowercase(text) + scope + hour_bucket`
  - TTL helpers: `getTtlProgress()`, `formatTtlRemaining()`, `isExpired()`
  - Enum helpers: `getScopeEnum()`, `getPriorityEnum()`

### `db/MessageDao.java`
- **Path:** `app/src/main/java/com/dev/echodrop/db/MessageDao.java`
- **Purpose:** Room DAO interface for all database operations
- **Key Queries:**
  - `getActiveMessages(now)` → `LiveData<List<MessageEntity>>` (WHERE expires_at > now ORDER BY created_at DESC)
  - `getMessageById()` (LiveData + sync variants)
  - `insert()` with `OnConflictStrategy.IGNORE` returns `long`
  - `deleteExpired(now)` returns count of deleted rows
  - `findByContentHash(hash)` for dedup lookup
  - `countAll()`, `countByPriority(priority)`
  - `deleteOldestBulk(limit)`, `deleteOldestNormal(limit)` — subquery pattern
  - `markAsRead(messageId)`, `deleteById(messageId)`

### `db/AppDatabase.java`
- **Path:** `app/src/main/java/com/dev/echodrop/db/AppDatabase.java`
- **Purpose:** Room database singleton
- **Details:**
  - `@Database(version = 1, exportSchema = false)`
  - `fallbackToDestructiveMigration()` for development
  - Double-checked locking singleton pattern
  - `setInstance()` / `destroyInstance()` for test injection of in-memory DB

### `repository/MessageRepo.java`
- **Path:** `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java`
- **Purpose:** Repository layer — single access point for message data
- **Details:**
  - `STORAGE_CAP = 200` rows maximum
  - `InsertCallback` interface: `onInserted()` / `onDuplicate()`
  - `insert()` with dedup check → insert → `enforceStorageCap()`
  - `enforceStorageCap()` deletes BULK first, then NORMAL (never ALERT)
  - `cleanupExpired()` (async) / `cleanupExpiredSync()` (for Worker)
  - `isDuplicateSync()` for external hash checks
  - Single-thread `ExecutorService` for all write operations
  - Constructor injection for `MessageDao` + `ExecutorService` (testing)

### `workers/TtlCleanupWorker.java`
- **Path:** `app/src/main/java/com/dev/echodrop/workers/TtlCleanupWorker.java`
- **Purpose:** WorkManager worker for periodic TTL cleanup
- **Details:**
  - `doWork()` creates `MessageRepo` and calls `cleanupExpiredSync()`
  - `schedule()` — `PeriodicWorkRequest` every 15 minutes, `KEEP` policy
  - `runOnce()` — `OneTimeWorkRequest` for immediate cleanup (e.g., on resume)

### `res/layout/fragment_message_detail.xml`
- **Path:** `app/src/main/res/layout/fragment_message_detail.xml`
- **Purpose:** Message detail screen layout
- **Details:**
  - `ConstraintLayout` with Toolbar (back navigation)
  - `ScrollView` with scope badge, priority badge, message text, "Visible to" label
  - TTL card with `ProgressBar` (max=1000) + time range labels
  - Created timestamp at bottom
  - "Got it" `MaterialButton` at bottom

### `screens/MessageDetailFragment.java`
- **Path:** `app/src/main/java/com/dev/echodrop/screens/MessageDetailFragment.java`
- **Purpose:** Detail view with TTL progress bar and lifecycle actions
- **Details:**
  - Factory `newInstance(messageId)` with `Bundle` args
  - Observes message via `repo.getMessageById()` LiveData
  - TTL progress bar color animation:
    - Green (`echo_positive_accent`) when >50% remaining
    - Amber (`echo_amber_accent`) when 20–50% remaining
    - Red (`echo_alert_accent`) when <20% remaining
  - 30-second periodic TTL updates via `Handler`
  - "Got it" button deletes message from Room and navigates back
  - Auto marks message as read on first display

---

## Updated Production Files (6)

### `MessageViewModel.java`
- **Before:** Extended `ViewModel`, used `MutableLiveData`, seeded 3 demo messages
- **After:** Extends `AndroidViewModel`, delegates to `MessageRepo`, exposes `LiveData<List<MessageEntity>>` from Room
- **Changes:**
  - Constructor takes `Application` (required for Room context)
  - Package-private constructor for test injection of `MessageRepo`
  - `addMessage(entity, callback)` with dedup callback
  - `addMessage(entity)` fire-and-forget overload
  - `deleteMessage(id)`, `cleanupExpired()`
  - `getRepo()` accessor for direct repo access
  - No more seed data — database starts empty

### `MessageAdapter.java`
- **Before:** `ListAdapter<Message, ...>` with `Message` DiffUtil
- **After:** `ListAdapter<MessageEntity, ...>` with `MessageEntity` DiffUtil
- **Changes:**
  - Added `OnMessageClickListener` interface
  - `setOnMessageClickListener()` method
  - Click handling in `onBindViewHolder()` via `itemView.setOnClickListener()`
  - DiffUtil uses `getScope().equals()` / `getPriority().equals()` (String comparison)
  - Bind logic uses `getScopeEnum()` / `getPriorityEnum()` for type-safe branching

### `HomeInboxFragment.java`
- **Before:** Observed `List<Message>`, no click handling, `onPost(Message)`
- **After:** Observes `List<MessageEntity>`, click → detail navigation, dedup Snackbar
- **Changes:**
  - Uses `MessageEntity.Priority.ALERT` for filter logic
  - `onMessageClicked()` navigates to `MessageDetailFragment` via fragment transaction
  - `onPost(MessageEntity)` calls `viewModel.addMessage(entity, callback)`
  - `InsertCallback.onDuplicate()` shows dedup Snackbar on UI thread
  - Added imports for `MessageRepo`, `Snackbar`

### `PostComposerSheet.java`
- **Before:** `OnPostListener.onPost(Message)`, created `new Message(...)`
- **After:** `OnPostListener.onPost(MessageEntity)`, creates `MessageEntity.create(...)`
- **Changes:**
  - Uses `MessageEntity.Scope` and `MessageEntity.Priority` enums
  - `submit()` calls `MessageEntity.create(text, scope, priority, created, expires)`

### `MainActivity.java`
- **Before:** Only hosted onboarding and home fragments
- **After:** Schedules TTL cleanup worker, provides `showMessageDetail()` navigation
- **Changes:**
  - `onCreate()` → `TtlCleanupWorker.schedule(this)`
  - `onResume()` (new) → `TtlCleanupWorker.runOnce(this)`
  - `showMessageDetail(String messageId)` — fragment transaction with back stack

### `strings.xml`
- **Before:** Only onboarding and home screen strings
- **After:** Added 8 new strings for detail screen and dedup feedback
- **Added strings:** `detail_title`, `detail_got_it`, `detail_ttl_expires_in`, `detail_created_at`, `detail_visible_nearby`, `detail_visible_area`, `detail_visible_event`, `post_dedup_snackbar`

---

## New Test Files (2)

### `db/MessageEntityTest.java`
- **Tests:** 28
- **Coverage:** `create()` factory, SHA-256 hash (deterministic, 64-char hex, case insensitive, trimmed, hour-bucketed), enum helpers (all 6 values), TTL progress (full/expired/zero), TTL formatting (hours+minutes, exact hours, minutes only, expired), expiry detection, `fromMessage()` conversion, `setRead()`

### `repository/MessageRepoTest.java`
- **Tests:** 14
- **Coverage:** Insert with dedup (onInserted/onDuplicate callbacks), storage cap enforcement (BULK first, then NORMAL, never ALERT), `deleteById`, `markAsRead`, `cleanupExpiredSync`, `isDuplicateSync`, insert without callback

---

## Updated Test Files (4)

### `MessageViewModelTest.java`
- **Before:** 17 tests — tested seed data, `new MessageViewModel()` no-arg constructor
- **After:** 10 tests — uses Robolectric + in-memory Room DB, tests insert/dedup/delete/cleanup/LiveData contract
- **Note:** Tests were reduced because seed data no longer exists; all tests now exercise the real Room persistence stack

### `MessageFilterLogicTest.java`
- **Before:** Used `Message` POJO with `Message.Scope`/`Message.Priority` enums
- **After:** Uses `MessageEntity` with `getScopeEnum()`/`getPriorityEnum()` accessors
- **Tests:** 14 (unchanged count)

### `MessageAdapterTest.java`
- **Before:** Used `Message` POJO, `DiffUtil.ItemCallback<Message>`
- **After:** Uses `MessageEntity`, `DiffUtil.ItemCallback<MessageEntity>`, added click listener tests
- **Tests:** 16 (up from 14)

### `PostComposerLogicTest.java`
- **Before:** Used `Message.Scope`/`Message.Priority`, `new Message(...)` constructors
- **After:** Uses `MessageEntity.Scope`/`MessageEntity.Priority`, `MessageEntity.create(...)` factory
- **Tests:** 27 (up from 24)

---

## Dependencies Added

```groovy
// Room Persistence
implementation 'androidx.room:room-runtime:2.6.1'
annotationProcessor 'androidx.room:room-compiler:2.6.1'
testImplementation 'androidx.room:room-testing:2.6.1'

// WorkManager
implementation 'androidx.work:work-runtime:2.9.0'
testImplementation 'androidx.work:work-testing:2.9.0'

// LiveData (for Transformations / MediatorLiveData)
implementation 'androidx.lifecycle:lifecycle-livedata:2.7.0'
```

---

## Architecture Changes

### Before (Iteration 0-1)
```
Fragment → ViewModel (LiveData<List<Message>>) → In-memory MutableLiveData
```

### After (Iteration 2)
```
Fragment → ViewModel → MessageRepo → MessageDao → Room SQLite
                                  ↓
                           WorkManager (TtlCleanupWorker)
```

### New Layers
| Layer          | Component             | Responsibility                       |
|----------------|-----------------------|--------------------------------------|
| **Database**   | `AppDatabase`         | Room DB singleton                    |
| **DAO**        | `MessageDao`          | SQL queries, LiveData exposure       |
| **Repository** | `MessageRepo`         | Business logic (dedup, cap, cleanup) |
| **Worker**     | `TtlCleanupWorker`    | Periodic + one-time TTL cleanup      |
| **Screen**     | `MessageDetailFragment`| Message detail with TTL progress     |

---

## Known Limitations

1. **Message.java retained** — Legacy POJO kept for `MessageEntity.fromMessage()` backward compatibility
2. **No migration strategy** — `fallbackToDestructiveMigration()` used (acceptable for pre-release)
3. **WorkManager 15-min minimum** — Android enforces minimum periodic interval of 15 minutes
4. **No instrumented tests** — Room integration tested via Robolectric in-memory DB (sufficient for current scope)
