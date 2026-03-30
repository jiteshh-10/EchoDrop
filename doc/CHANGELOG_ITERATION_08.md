# Changelog — Iteration 8: Private Chat Sync (Proximity-Based)

> Historical scope note: this file documents Iteration 8 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> **Branch:** `iteration-8`
> **Date:** 2026
> **Tests:** 446 (41 new) — 0 failures — 100% pass rate

---

## Summary

Iteration 8 adds **encrypted private chat synchronisation over the DTN mesh**. Chat messages are wrapped as DTN bundles (`type=CHAT`, `scope=LOCAL`, `scope_id=chat_code`) and flow through the same store-carry-forward pipeline as broadcast messages. Non-member devices carry the ciphertext but cannot decrypt it. When two chat members meet, messages exchange automatically — outgoing sync state updates to double-tick, and incoming messages animate into the conversation with a fade/slide transition.

---

## New Files

| File | Purpose |
|------|---------|
| `db/MessageEntityChatTest.java` | 27 tests for type/scopeId fields, `createChatBundle()` factory, `isChatBundle()` helper |
| `transfer/TransferProtocolChatTest.java` | 9 tests for ED08 wire format with type/scopeId serialisation |
| `repository/ChatRepoChatSyncTest.java` | 5 tests for `processIncomingChatBundle()` flow (non-member, duplicate, decrypt failure, type checking) |

---

## Modified Files

### Schema & Data Layer

| File | Change |
|------|--------|
| `db/MessageEntity.java` | Added `TYPE_BROADCAST="BROADCAST"` and `TYPE_CHAT="CHAT"` constants; `type` column (`defaultValue="BROADCAST"`) and `scope_id` column (`defaultValue=""`); `createChatBundle()` factory; `getType()`, `setType()`, `getScopeId()`, `setScopeId()`, `isChatBundle()` helpers |
| `db/AppDatabase.java` | Version bumped 3 → 4 (destructive migration) |
| `db/ChatDao.java` | Added `markOutgoingSynced(chatId)`, `getMessagesForChatSync(chatId)`, `chatMessageExists(messageId)` queries |

### Wire Protocol

| File | Change |
|------|--------|
| `transfer/TransferProtocol.java` | Magic bumped "ED07" → "ED08"; `serialize()` writes `type` and `scopeId` after seenByIds; `deserialize()` reads them back |

### Transfer Logic

| File | Change |
|------|--------|
| `transfer/BundleSender.java` | `sendForForwarding()` preserves `type` and `scopeId` on forwarded copies |
| `transfer/BundleReceiver.java` | After inserting received message, calls `chatRepo.processIncomingChatBundle()` for CHAT type bundles |

### Repository

| File | Change |
|------|--------|
| `repository/ChatRepo.java` | Added `CHAT_BUNDLE_TTL_MS` (24h); `SyncEventListener` interface; `MessageDao` integration; `sendMessage()` now creates a DTN bundle via `MessageEntity.createChatBundle()`; `processIncomingChatBundle()` decrypts, inserts, updates preview/unread, marks outgoing synced |

### UI

| File | Change |
|------|--------|
| `screens/ChatConversationFragment.java` | Sync bar with dynamic "Last synced X ago" text; incoming message fade/slide animation (250ms); `SyncEventListener` integration; handler-based periodic refresh |
| `res/layout/screen_chat_conversation.xml` | Added `sync_dot` and `sync_text` IDs to sync bar views |

### Resources

| File | Change |
|------|--------|
| `res/values/strings.xml` | Added `chat_sync_just_now`, `chat_sync_minutes_ago`, `chat_sync_hours_ago` |

### Tests (Modified)

| File | Change |
|------|--------|
| `transfer/TransferProtocolTest.java` | Updated `magic_isED07` → `magic_isED08` |

---

## Design Decisions

1. **Chat bundles use existing DTN pipeline** — No separate transport. Chat messages ride the same BLE discovery → Wi-Fi Direct transfer → store-carry-forward pipeline. Simplifies architecture and ensures chat benefits from multi-hop propagation.
2. **`type` + `scope_id` on MessageEntity** — Clean separation of broadcast vs chat bundles. `scope_id` carries the chat code, enabling lookup without exposing the encryption key.
3. **Wire format versioned "ED08"** — Adding type/scopeId changes the frame structure. Clean break from ED07 prevents deserialization errors.
4. **Non-members carry but cannot read** — Devices without the chat code store the encrypted bundle and forward it normally. Only the AES-256-GCM key (derived from the 8-char code via PBKDF2) can decrypt. This maximises delivery probability.
5. **DTN bundle created on send** — `ChatRepo.sendMessage()` creates both the local `ChatMessageEntity` and a `MessageEntity` DTN bundle. The bundle enters the message table and flows through the normal send pipeline.
6. **24-hour chat bundle TTL** — Matches the "ephemeral" philosophy. Chat bundles expire after 24 hours like broadcast messages.
7. **Sync bar with elapsed time** — UI shows "Last synced just now" / "Xm ago" / "Xh ago" to give users confidence that sync is working. Updates every 30 seconds via Handler.
8. **Incoming message animation** — 250ms fade+slide from top communicates that messages "arrived" rather than were always there.

---

## Test Summary

| Test Class | Tests | Status |
|------------|-------|--------|
| MessageEntityChatTest | 27 | ✅ Pass |
| TransferProtocolChatTest | 9 | ✅ Pass |
| ChatRepoChatSyncTest | 5 | ✅ Pass |
| TransferProtocolTest (updated) | 29 | ✅ Pass |
| **All existing tests** | 405 | ✅ Pass |
| **Total** | **446** | **✅ 100%** |
