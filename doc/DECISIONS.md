# EchoDrop — Design Decisions Log

> Architectural and implementation decisions documented per iteration.

---

## Decision: Priority sort in DAO, not adapter — iter-3
Context: Messages need to appear ordered by priority (ALERT > NORMAL > BULK).
Decided: `ORDER BY CASE priority` clause in the DAO query is the single source of ordering truth.
Why: Keeps ordering logic in one place; adapter never manually reorders, reducing bugs and complexity.
Impact: Any future sort changes happen in one SQL query, not scattered across UI code.

## Decision: BULK not user-selectable in MVP — iter-3
Context: Spec defines three priority tiers but BULK is reserved for system/forwarded messages.
Decided: Post Composer only exposes Normal and Urgent toggles; BULK is not available to users.
Why: Prevents user confusion; BULK semantics only make sense when multi-hop forwarding exists (Iteration 6+).
Impact: Enum has three values but UI surfaces two; future iterations can expose BULK when relay logic is built.

## Decision: Reactive alert count via LiveData — iter-3
Context: Alerts tab badge needs to update when ALERT messages are inserted or expire.
Decided: New DAO query `getAlertCount(now)` returns `LiveData<Integer>`, observed in HomeInboxFragment.
Why: Avoids manual counting in `applyFilters()` loop; Room invalidation tracker updates automatically.
Impact: Badge is always accurate; no need to recount on every filter pass.

## Decision: Priority immutable after creation — iter-3
Context: Should users be able to change a message's priority after posting?
Decided: No. Priority is set at creation time and cannot be changed via UI.
Why: DTN bundles have fixed headers; changing priority after propagation would create inconsistency across devices.
Impact: No edit UI needed for priority; simplifies data model and forwarding logic.

## Decision: Urgent banner animation in detail screen — iter-3
Context: Spec calls for a banner below AppBar for URGENT messages with entrance animation.
Decided: `LinearLayout` with `bg_urgent_banner` drawable, fade in + slide down 4dp over 180ms.
Why: Matches spec exactly (180ms, FastOutSlowIn); provides visual urgency without being distracting.
Impact: Banner only appears for ALERT messages; NORMAL/BULK see no banner.

## Decision: PBKDF2 key derivation from chat code — iter-4
Context: Encrypted chat needs a symmetric key. Options: (a) generate random key and share it out-of-band, (b) derive key from shared chat code.
Decided: Derive AES-256 key via PBKDF2WithHmacSHA256 (10 000 iterations, fixed salt "EchoDrop-ChatKey-v1") from the 8-character chat code.
Why: No separate key exchange step; anyone who knows the code can derive the same key. Simpler UX — share one code, not code + key.
Impact: Security relies on code secrecy + code entropy (32^8 ≈ 1.1 trillion). Future iterations can upgrade to ephemeral key exchange.

## Decision: AES-256-GCM with prepended IV — iter-4
Context: Need authenticated encryption. Options: AES-CBC+HMAC, AES-GCM, ChaCha20-Poly1305.
Decided: AES-256-GCM with 96-bit random IV prepended to ciphertext, stored as Base64 NO_WRAP.
Why: GCM provides both confidentiality and integrity in a single pass; natively supported on Android (javax.crypto). No external crypto library needed.
Impact: 12-byte IV overhead per message; GCM tag validates integrity on decrypt; wrong-key decryption throws immediately.

## Decision: Ambiguity-free code charset — iter-4
Context: Chat codes are shared verbally or on paper. Characters like O/0 and I/1 cause confusion.
Decided: Charset `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (28 chars): no O, 0, I, or 1.
Why: Reduces transcription errors when users share codes in person or over voice. 28^8 ≈ 377 billion combinations still provides strong entropy.
Impact: Codes are always uppercase; `stripCode()` uppercases and trims input for matching.

## Decision: Foreign key CASCADE on chat_messages — iter-4
Context: When a chat is deleted, orphan messages would remain without CASCADE.
Decided: `@ForeignKey(onDelete = CASCADE)` from `chat_messages.chat_id` → `chats.id`.
Why: Automatic cleanup — deleting a chat removes all its messages in one Room operation. No manual message deletion code needed.
Impact: Tested in `ChatDaoTest.deleteChat_cascadesToMessages`. Single `deleteChat()` call cleans up everything.

## Decision: Plaintext preview stored in chat entity — iter-4
Context: Chat list needs to show last message preview. Options: (a) decrypt last message on bind, (b) store plaintext preview in ChatEntity.
Decided: Store truncated (≤50 chars) plaintext preview in `ChatEntity.lastMessagePreview`.
Why: Avoids re-deriving the key and decrypting just to show a preview. Chat list loads faster. Preview is already in-memory when sending.
Impact: Preview is plain text in the DB; acceptable since the device DB is the trust boundary. Full messages are encrypted.

## Decision: Key held in memory only, cleared on navigation — iter-4
Context: How long should the derived SecretKey persist?
Decided: Key derived in `ChatConversationFragment.onViewCreated()`, cleared in `onDestroyView()`. Never persisted to DB or SharedPrefs.
Why: Minimizes window of key exposure. Key exists only while the conversation is on screen. Re-derived on re-entry (PBKDF2 is fast enough for UI).
Impact: No key storage attack surface. If the app is killed, key is gone — re-derived from code on next open.

## Decision: No sync in v0.4 — local-only chat — iter-4
Context: Spec says "No Sync" — chat messages stay on-device. `syncState` field is a forward-declaration.
Decided: Messages default to `SYNC_SENT` (1). Sync bar shows "Messages stay on this device" hint. No WorkManager job for chat sync.
Why: DTN relay (Iteration 6) will use syncState to track propagation. Adding the field now avoids a Room migration later.
Impact: UI shows tick/double-tick icons keyed to syncState, but all messages stay at SENT until relay is implemented.

## Decision: Custom Service UUID for BLE discovery — iter-5
Context: BLE advertising requires a service UUID to filter scan results. Options: (a) use a standard profile UUID, (b) create a custom UUID.
Decided: Custom UUID `ed000001-0000-1000-8000-00805f9b34fb` (EchoDrop-specific, reserved under Bluetooth SIG base).
Why: No standard BLE profile matches our use case. Custom UUID ensures only EchoDrop devices respond to scan filters.
Impact: All EchoDrop instances must use this exact UUID. Scanner filters exclusively for it.

## Decision: Compact binary manifest format — iter-5
Context: Manifests must fit within BLE GATT characteristic payloads (~512 bytes). Options: (a) JSON, (b) protobuf, (c) custom binary.
Decided: Custom binary format: 3-byte header (version + entry_count) + 28 bytes per entry (16 UUID + 4 checksum + 1 priority + 3 reserved + 4 expires_at). Max 18 entries per manifest.
Why: JSON is too verbose for BLE constraints. Protobuf adds a dependency. Custom binary gives precise control over byte layout with no overhead.
Impact: 18 entries × 28 bytes + 3 header = 507 bytes max — fits within a single BLE characteristic. Forward-compatible via version byte.

## Decision: 10s scan / 20s pause BLE duty cycle — iter-5
Context: Continuous BLE scanning drains battery rapidly. Need a balance between discovery responsiveness and power consumption.
Decided: Scan for 10 seconds, pause for 20 seconds, repeat. Stale peers pruned after 2 minutes of no re-detection.
Why: 33% duty cycle is aggressive enough to detect nearby devices within 30 seconds while being much friendlier to battery life than continuous scanning.
Impact: Peer list may have up to 20-second latency for new device detection. Acceptable for DTN where messages are not time-critical.

## Decision: BLE permissions guarded at service start — iter-5
Context: Android 14+ (targetSdk 35) requires granted BLE runtime permissions before starting a connectedDevice foreground service.
Decided: `EchoService.startService()` checks `hasBlePermissions()` and silently returns if not granted. `SettingsFragment` requests permissions via `ActivityResultLauncher` before enabling the toggle.
Why: Prevents `SecurityException` crash. Permissions must be requested from an Activity/Fragment context, not from EchoService directly.
Impact: Service only starts after user grants at least one BLE permission. Boot receiver also safely skips start if permissions were revoked.

## Decision: 7-tap easter egg for dev screen — iter-5
Context: Discovery Status is a developer/debug screen. Should it be in the main nav?
Decided: Hidden behind a 7-tap easter egg on the Settings version text (same pattern as Android's "Developer options").
Why: Regular users don't need to see BLE debug info. Power users and developers can access it via the familiar tap pattern.
Impact: No visual clutter in the main UI. Toast counting from 4th tap provides discoverability feedback.

## Decision: Foreground service type connectedDevice — iter-5
Context: Android 14 requires foreground service type declarations. Options: `connectedDevice`, `location`, `dataSync`.
Decided: `foregroundServiceType="connectedDevice"` — matches BLE advertising and scanning operations.
Why: Most accurate type for what the service does (BLE device discovery). Android requires at least one of the associated permissions to be granted.
Impact: Requires at least one of BLUETOOTH_ADVERTISE/CONNECT/SCAN to be granted at runtime before `startForeground()` is called.

## Decision: TCP port 9876 for Wi-Fi Direct transfer — iter-6
Context: BundleReceiver needs a fixed port for the ServerSocket. Options: (a) well-known port, (b) ephemeral port with discovery, (c) fixed application port.
Decided: Fixed port 9876. Defined as `TransferProtocol.PORT` constant shared by sender and receiver.
Why: Simple, no port negotiation needed. Port 9876 is not assigned by IANA for any common service. Both peers know the port at compile time.
Impact: Two EchoDrop instances on the same device would conflict; acceptable since Wi-Fi Direct connects two separate physical devices.

## Decision: "ED06" magic header for wire format — iter-6
Context: Need to validate incoming TCP streams are actual EchoDrop transfer sessions, not stray connections.
Decided: 4-byte ASCII magic "ED06" at the start of every session. Reader validates before parsing.
Why: Prevents cross-version incompatibility and rejects garbage connections immediately. "ED" = EchoDrop, "06" = iteration 6 protocol version.
Impact: Future protocol changes can bump the version suffix (e.g., "ED07") for backward-incompatible changes.

## Decision: Priority-sorted session writes — iter-6
Context: TCP sessions may be interrupted mid-transfer. Which messages should arrive first?
Decided: `writeSession()` sorts messages ALERT → NORMAL → BULK before writing frames.
Why: If the connection drops mid-transfer, the highest-priority messages have already been sent and received. ALERT messages are most time-sensitive.
Impact: Receiver inserts messages in priority order. Partial transfers still deliver the most valuable content.

## Decision: Checksum validation on receive, not send — iter-6
Context: How to detect message corruption during transfer.
Decided: Receiver recomputes `MessageEntity.computeHash()` and compares with the transferred `contentHash`. Mismatched messages are silently discarded.
Why: Sender may have valid data but bit flips during transfer could corrupt content. Receiver-side validation is the last line of defense before DB insertion.
Impact: Corrupted messages are dropped rather than stored. No retransmission protocol — DTN will eventually re-receive the message from another peer.

## Decision: Partial transfer discard (no resume) — iter-6
Context: If a TCP connection drops mid-session, should we resume?
Decided: No resume. Already-inserted messages remain; remaining messages are lost for this transfer.
Why: DTN store-carry-forward means the same messages will be offered again by other peers. Resume protocol adds significant complexity for minimal benefit.
Impact: A dropped connection means some messages may need to be re-transferred, but deduplication prevents double-insertion.

## Decision: fitsSystemWindows on root layout — iter-6
Context: Screenshot showed settings gear icon overlapping with the system status bar on some devices.
Decided: Added `android:fitsSystemWindows="true"` to the root `FrameLayout` in `activity_main.xml`.
Why: Single-point fix that propagates system window insets (status bar, navigation bar) to all child fragments. No need to add it to every individual layout file.
Impact: All toolbar content is now pushed below the status bar on every device form factor, resolving the overlap issue.

## Decision: Transfer-aware sync pulse speed — iter-6
Context: UI should indicate when a data transfer is actively happening.
Decided: Sync dot pulse speed changes from 2000ms (idle) to 500ms (active transfer) via `EchoService.TransferStateListener`.
Why: Faster pulse gives users visual feedback that data is being received without requiring a new UI element. Reuses existing sync dot infrastructure.
Impact: Static interface avoids service binding complexity. Listener set/cleared in fragment lifecycle to prevent leaks.

## Decision: ActivityResultLauncher for permissions — hotfix-01
Context: PermissionsFragment was cosmetic — "Allow" button navigated home without requesting permissions. Need a reliable runtime permission mechanism.
Decided: Use `ActivityResultLauncher<String[]>` with `RequestMultiplePermissions` contract. Request all needed permissions (BLE, location, Wi-Fi, notifications) in a single batch.
Why: Modern AndroidX API replaces deprecated `requestPermissions()`/`onRequestPermissionsResult()`. Single launcher handles the entire permission flow. Works correctly across API levels.
Impact: One permission dialog covers all needs. On grant → service auto-starts immediately. On deny → user can enable later in Settings.

## Decision: BLE → Wi-Fi Direct auto-connect pipeline — hotfix-01
Context: BLE scanner, WifiDirectManager, and BundleSender/Receiver existed as isolated components. No code connected them into a working pipeline.
Decided: Wire the pipeline in `EchoService.onCreate()`: BLE peer detection → `discoverPeers()` → auto-connect first peer → client sends via `sendAllMessages()`, owner receives via `BundleReceiver`.
Why: Simplest orchestration that achieves end-to-end transfer. Auto-connect to first peer is acceptable for a mesh network where any peer is a valid target.
Impact: Transfer happens automatically without user interaction. Disconnect after transfer allows re-discovery on next BLE cycle.

## Decision: hasBlePermissions uses AND not OR — hotfix-01
Context: `hasBlePermissions()` checked ADVERTISE || CONNECT || SCAN, allowing service start with only 1 of 3 permissions.
Decided: Changed to ADVERTISE && CONNECT && SCAN — all 3 must be granted.
Why: The service uses all three BLE operations. Starting with partial permissions causes crashes or silent failures in the missing operations.
Impact: Service will not start until all BLE permissions are granted. More restrictive but correct.

## Decision: Auto-start service in MainActivity.onCreate — hotfix-01
Context: EchoService only started from SettingsFragment toggle. After onboarding, if the user killed the app and reopened it, the service would not restart.
Decided: Added `EchoService.startService(this)` in `MainActivity.onCreate()` if permissions are granted and background sharing is enabled.
Why: Service must survive app restarts. `START_STICKY` handles OS restarts, but cold launches need explicit start.
Impact: Service is always running when the app is open and permissions are granted. No user action required after initial onboarding.

## Decision: Real peer count via PeerCountListener — hotfix-01
Context: `HomeInboxFragment` hardcoded `updateSyncIndicator(3)` — fake peer count.
Decided: New static `PeerCountListener` interface on EchoService. BLE scanner's `setPeerUpdateListener` feeds real count → `notifyPeerCount()` → UI. Fragment registers in `onViewCreated`, clears in `onDestroyView`.
Why: Same static-listener pattern as `TransferStateListener`. Consistent, no service binding needed, lifecycle-safe.
Impact: Sync indicator now shows actual nearby peer count (0 when alone, N when peers detected).

## Decision: MAX_HOP_COUNT = 5 for loop prevention — iter-7
Context: Multi-hop forwarding needs a propagation depth limit to prevent flooding.
Decided: `MessageEntity.MAX_HOP_COUNT = 5`. Messages at or above this limit are not forwarded.
Why: 5 hops covers a large venue (A→B→C→D→E→F = 6 devices). Higher limits increase network load without proportional coverage gain. Easy to tune later.
Impact: Messages organically die after 5 hops. Combined with TTL expiry, prevents unbounded propagation.

## Decision: Comma-separated seen_by_ids — iter-7
Context: Need to track which devices have seen a message to prevent loops. Options: (a) JSON array, (b) comma-separated string, (c) separate join table.
Decided: Comma-separated string stored in a TEXT column on MessageEntity. Split/join on comma for membership checks.
Why: Simple, compact, no JSON parsing overhead. Max 5 hops = max ~50 chars. Room stores as plain TEXT with default "". Wire format uses writeString/readString.
Impact: `hasBeenSeenBy()` and `addSeenBy()` do string split/join. O(n) lookup but n ≤ 5, so negligible.

## Decision: Persistent DeviceIdHelper vs BLE device ID — iter-7
Context: BLE advertiser uses a 4-byte integer device ID. Seen-by-ids needs a string identifier.
Decided: New `DeviceIdHelper` utility generates an 8-char hex ID from UUID, persisted in SharedPreferences. Separate from BLE's integer ID.
Why: String-based IDs are easier to serialize in comma-separated lists. 8 hex chars = 4 billion possibilities, sufficient for local mesh uniqueness. SharedPreferences ensures persistence across app restarts.
Impact: Two device identity systems coexist. BLE uses integer ID for compact advertising; transfer layer uses hex string for loop prevention.

## Decision: Wire protocol version bump "ED06" → "ED07" — iter-7
Context: Adding hop_count and seen_by_ids fields to the wire format changes the frame structure.
Decided: Bump magic header to "ED07". Old clients reject new sessions; new clients reject old sessions.
Why: Clean break. Prevents deserialization errors from field mismatch. Follows the versioning convention established in iter-6.
Impact: Devices running iter-6 and iter-7 cannot exchange messages directly. Acceptable since all devices should be on the same version.

## Decision: Forwarded copy pattern — iter-7
Context: When forwarding, should we modify the original message entity or create a copy?
Decided: `sendForForwarding()` creates a new `MessageEntity` copy with hop+1 and updated seen_by_ids. Original entity in the local DB is not mutated.
Why: Mutation of the local entity would corrupt the DB copy's hop count (it should reflect how many hops it took to reach *this* device, not the outgoing count). Copy pattern preserves data integrity.
Impact: Slight memory overhead from creating copies, but messages are small (<1KB) and the list is bounded by storage cap (200).

## Decision: Empty peerDeviceId accepted for Wi-Fi Direct — iter-7
Context: Wi-Fi Direct doesn't expose the peer's EchoDrop device ID before the TCP connection.
Decided: Pass empty string "" as peerDeviceId when calling `sendForForwarding()`. The seen-by filter is effectively skipped for unknown peers.
Why: Content hash dedup in Room prevents double-insertion regardless. Seen-by-ids is a best-effort optimization, not a correctness requirement.
Impact: Some redundant transfers may occur to the same peer, but dedup ensures no duplicate storage.

## Decision: Chat bundles reuse existing DTN pipeline — iter-8
Context: Chat messages need to sync between members over the mesh. Options: (a) separate chat transport, (b) piggyback on existing DTN pipeline.
Decided: Chat messages are wrapped as `MessageEntity` bundles with `type=CHAT`, `scope=LOCAL`, `scope_id=chat_code`. They flow through the same BLE discovery → Wi-Fi Direct transfer → store-carry-forward pipeline.
Why: No separate transport needed. Chat bundles benefit from multi-hop forwarding, hop limits, and seen-by-ids loop prevention for free. Non-members carry ciphertext without any special handling.
Impact: All existing pipeline logic (BundleSender filtering, BundleReceiver dedup, EchoService scheduling) works for chat bundles without modification.

## Decision: type + scope_id columns on MessageEntity — iter-8
Context: Need to distinguish chat bundles from broadcast bundles and route them to the correct chat.
Decided: Added `type` (TEXT, default "BROADCAST") and `scope_id` (TEXT, default "") columns. Chat bundles have `type="CHAT"` and `scope_id=<chat_code>`.
Why: Clean separation without a separate table. Default values ensure backward compatibility — all existing messages are implicitly BROADCAST with empty scope_id. The chat code in scope_id enables lookup without exposing the encryption key.
Impact: DB version bumped 3→4 (destructive migration). Wire protocol includes type/scopeId in serialisation.

## Decision: Wire protocol version bump "ED07" → "ED08" — iter-8
Context: Adding type and scopeId fields to the wire frame changes the serialisation format.
Decided: Bump magic header to "ED08". Old clients reject new sessions; new clients reject old sessions.
Why: Clean break. Follows the versioning convention established in iter-6 and iter-7.
Impact: Devices running iter-7 and iter-8 cannot exchange messages directly.

## Decision: Non-members carry encrypted bundles blindly — iter-8
Context: When a device receives a CHAT bundle for a chat it hasn't joined, what should happen?
Decided: Store the encrypted bundle in the messages table and forward it normally. Never attempt to decrypt or display.
Why: Maximises delivery probability. Any device in the mesh can carry the bundle toward the intended recipient. The AES-256-GCM encryption ensures only members with the chat code can read the content.
Impact: Non-member devices accumulate some encrypted bundles that they cannot read. These expire naturally via TTL (24h).

## Decision: Chat bundle TTL of 24 hours — iter-8
Context: How long should chat bundles live in the DTN?
Decided: `CHAT_BUNDLE_TTL_MS = 24 * 60 * 60 * 1000` (24 hours).
Why: Matches the ephemeral philosophy of broadcast messages. Long enough for store-carry-forward to deliver across a venue over a day. Short enough to prevent indefinite accumulation.
Impact: Chat messages older than 24h are cleaned up by the existing WorkManager TTL job.

## Decision: DTN bundle created on send — iter-8
Context: When a user sends a chat message, when should the DTN bundle be created?
Decided: `ChatRepo.sendMessage()` creates both the local `ChatMessageEntity` and a `MessageEntity` DTN bundle in the same operation.
Why: Ensures the bundle enters the message table immediately and is available for the next send cycle. No separate sync job needed.
Impact: Each outgoing chat message creates two database rows: one in chat_messages (for local display) and one in messages (for DTN transport).

## Decision: System fonts over bundled TTF — iter-9
Context: Spec calls for DM Sans / JetBrains Mono. Downloadable Google Fonts caused crash in iter-0-1.
Decided: Use `sans-serif` (DM Sans proxy) and `monospace` (JetBrains Mono proxy) set at theme level.
Why: Zero crash risk, no asset download, minimal APK size. Sans-serif on modern Android (Roboto) maps closely to DM Sans metrics.
Impact: Typography does not match spec pixel-perfectly but is visually cohesive and crash-free.

## Decision: values/ = light, values-night/ = dark — iter-9
Context: App was dark-only. Spec requires light mode as default with dark mode via night qualifier.
Decided: `values/colors.xml` holds light palette; `values-night/colors.xml` overrides for dark. Themes use `Material3.Light.NoActionBar` and `Material3.Dark.NoActionBar`.
Why: Standard Android resource qualifier system. Avoids runtime logic; system handles mode switching automatically.
Impact: All color tokens resolved via resources; no Java-side mode switching needed.

## Decision: Periodic adapter refresh for TTL countdown — iter-9
Context: TTL label on message cards only computed at bind time — stale until scroll recycle.
Decided: 60-second `Handler.postDelayed` loop calls `adapter.notifyDataSetChanged()` in HomeInboxFragment.
Why: Simplest fix. 60s is low-cost (single adapter rebind) and acceptable for display that shows "2h 15m" granularity.
Impact: Minor CPU/battery cost (~negligible). Handler is lazy-initialized to avoid Looper dependency in unit tests.

## Decision: Remove non-functional icon vs add drawer — iter-9
Context: Home toolbar had `setNavigationIcon` but no click listener or drawer.
Decided: Remove the icon entirely rather than adding a navigation drawer.
Why: No spec for drawer/navigation menu. Dead UI is worse than absent UI.
Impact: Toolbar shows only title and overflow menu.

## Decision: Timber planted in Activity, not Application — iter-9
Context: Timber.plant() typically goes in Application.onCreate(). EchoDrop has no custom Application class.
Decided: Plant DebugTree in `MainActivity.onCreate()` guarded by `BuildConfig.DEBUG`.
Why: Avoids creating a new Application subclass + manifest changes for a single line. All code paths go through MainActivity first.
Impact: If future iterations add an Application class, move the plant call there.

## Decision: Log-only StrictMode penalties — iter-9
Context: StrictMode can crash (penaltyDeath) or log (penaltyLog).
Decided: penaltyLog only, debug builds only.
Why: Crash penalty would make development painful with Room's synchronous convenience methods. Log-only surfaces violations without blocking.
Impact: Violations appear in Logcat under StrictMode tag. No user-visible effect.

## Decision: Strip Timber.d/v in release, keep i/w/e — iter-9
Context: ProGuard `assumenosideeffects` can remove log calls entirely.
Decided: Strip `Timber.d()` and `Timber.v()` in release builds. Keep `Timber.i()`, `Timber.w()`, `Timber.e()`.
Why: Debug/verbose logs are high-volume and developer-only. Info/warn/error logs are sparse and useful for post-release diagnostics.
Impact: Release APK has no debug logging overhead.

## Decision: 100dp pill corners for badges — iter-9
Context: Spec says "pill shape" badges. Previously used 20dp corners.
Decided: Set badge corner radius to 100dp.
Why: 100dp on a 22dp-high badge guarantees fully rounded ends regardless of width. Overdrawing corners is cropped by the view bounds.
Impact: Badges visually match pill specification.

## Decision: Persist saved state in Room message table — iter-10
Context: Saved messages must survive app process restarts and be queryable without additional local stores.
Decided: Added boolean `saved` column on `messages` with DAO methods `setSaved` and `getSavedMessages(now)`.
Why: Reuses existing message lifecycle and indexing semantics, avoids split state between DB and SharedPreferences.
Impact: Saved UI becomes fully reactive via existing LiveData/Room observer path.

## Decision: Report action blocks by origin ID and purges local origin rows — iter-10
Context: Report should immediately reduce harmful or noisy content from the same sender.
Decided: Report flow writes to `BlockedDeviceStore` and deletes rows by `origin` through `deleteByOrigin(originId)`.
Why: Fast local moderation with deterministic behavior and no extra moderation backend dependency.
Impact: Reported origin content disappears immediately; future receives are filtered by existing blocked-origin checks.

## Decision: Keep unblock control centralized in Settings — iter-10
Context: Multiple unblock surfaces can create inconsistent state ownership.
Decided: Message detail supports block/report only; unblock remains in Settings blocked-device management.
Why: Single ownership model for reversal actions improves discoverability and reduces duplicated UI logic.
Impact: Users always manage unblock actions from one predictable location.

## Decision: Add dedicated Saved screen instead of overloading inbox tabs — iter-10
Context: Reusing inbox tabs for saved state would mix semantic filters and persistence intent.
Decided: Implemented `SavedMessagesFragment` with its own toolbar entry and empty state.
Why: Clear mental model: inbox is live flow, saved is explicit user curation.
Impact: Lower coupling in `HomeInboxFragment` filter logic and cleaner future expansion path.

## Decision: Centralize animated toolbar logo setup in utility class — iter-10
Context: App bar animation was needed on multiple fragments and risked copy-paste drift.
Decided: Added `ToolbarLogoAnimator.apply(Toolbar)` utility to apply drawable + start animation.
Why: Shared helper keeps branding behavior consistent across Home, Detail, and Saved screens.
Impact: Future toolbar branding changes are localized to one utility path.
