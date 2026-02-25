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
