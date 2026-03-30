# Changelog — Iteration 4: Private Chat — Local Only, Encrypted, No Sync

> Historical scope note: this file documents Iteration 4 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> **Date:** 2026-02-26  
> **Branch:** `iteration-4`  
> **Status:** ✅ Complete  
> **Tests:** 257 total (43 new), 0 failures

---

## New Production Files

### 1. `db/ChatEntity.java`
- Room entity for `chats` table: id (PK UUID), code (unique 8-char), name (nullable), createdAt, lastMessagePreview, lastMessageTime, unreadCount
- `generateCode()` — SecureRandom, charset `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no O/0/I/1)
- `formatCode()` / `stripCode()` — XXXX-XXXX display formatting
- `getDisplayName()` — returns name or formatted code
- `getInitial()` — first char of display name, uppercased

### 2. `db/ChatMessageEntity.java`
- Room entity for `chat_messages` table: id (PK), chatId (FK→chats.id CASCADE), text (Base64 ciphertext), isOutgoing, createdAt, syncState
- Sync state constants: SYNC_PENDING=0, SYNC_SENT=1, SYNC_SYNCED=2
- Factory `createOutgoing(chatId, cipherText)` with sensible defaults

### 3. `db/ChatDao.java`
- Full CRUD: insertChat (REPLACE), updateChat, deleteChat, getChatById, getChatByCode
- `getAllChats()` — LiveData ordered by MAX(last_message_time, created_at) DESC
- Message operations: insertMessage, deleteMessage, getMessagesForChat (ASC), getMessageCount
- Aggregations: updateLastMessage, incrementUnread, clearUnread

### 4. `crypto/ChatCrypto.java`
- `deriveKey(chatCode)` — PBKDF2WithHmacSHA256, 10 000 iterations, salt "EchoDrop-ChatKey-v1", 256-bit output
- `encrypt(plaintext, key)` — AES-256-GCM, 96-bit random IV, returns Base64(IV‖ciphertext‖tag)
- `decrypt(cipherToken, key)` — reverses encryption, throws RuntimeException on wrong key

### 5. `repository/ChatRepo.java`
- Wraps ChatDao with single-thread ExecutorService
- `createChat()`, `joinChat()` (with JoinCallback for async result), `deleteChat()`
- `sendMessage()` — derives key, encrypts, stores, updates preview (≤50 chars)
- LiveData: `getChats()`, `getMessages(chatId)`

### 6. `viewmodels/ChatViewModel.java`
- AndroidViewModel wrapping ChatRepo
- Exposes: chats LiveData, activeChatId MutableLiveData, getMessages(), sendMessage(), createChat(), joinChat(), deleteChat(), clearUnread()

### 7. `adapters/ChatListAdapter.java`
- ListAdapter with DiffUtil (compares id, lastMessageTime, unreadCount, preview)
- Displays avatar initial, display name, preview, relative timestamp, unread badge
- OnChatClickListener interface for item clicks

### 8. `adapters/ChatMessageAdapter.java`
- ListAdapter with two view types: TYPE_OUTGOING (right-aligned) and TYPE_INCOMING (left-aligned with accent border)
- Decrypts ciphertext on bind via SecretKey
- Sync indicator icons: SYNCED→double_tick green, SENT→tick secondary, PENDING→tick muted

### 9. `screens/PrivateChatListFragment.java`
- RecyclerView + FAB (new chat) + Join button (AlertDialog with code input)
- Empty state with icon and descriptive text
- Observes ChatViewModel.getChats() LiveData
- Back navigation to HomeInboxFragment

### 10. `screens/CreateChatFragment.java`
- Name input, code display (monospace 28sp XXXX-XXXX)
- Copy to clipboard, Show QR toggle (ZXing QRCodeWriter 512px bitmap), New Code button
- Info note about code sharing
- Create Chat button → navigates to conversation

### 11. `screens/ChatConversationFragment.java`
- Toolbar with avatar initial, chat name, formatted code
- Sync status bar ("Messages stay on this device")
- RecyclerView (stackFromEnd) with ChatMessageAdapter
- Input bar: pill EditText + send button (alpha toggle on text change)
- Key derived in onViewCreated, cleared in onDestroyView

---

## Updated Production Files

### 1. `app/build.gradle`
- Added: `implementation 'com.google.zxing:core:3.5.3'`

### 2. `db/AppDatabase.java`
- Entities: added ChatEntity, ChatMessageEntity (version 1→2)
- Added: `abstract ChatDao chatDao()`

### 3. `MainActivity.java`
- Added: `showChatList()`, `showCreateChat()`, `showChatConversation(chatId, chatCode, chatName)` navigation methods
- Added: imports for PrivateChatListFragment, CreateChatFragment, ChatConversationFragment

### 4. `screens/HomeInboxFragment.java`
- Changed: Chats tab and fabChats onClick → `navigateToChatList()` instead of `selectTab(Tab.CHATS)`
- Added: `navigateToChatList()` method delegating to `MainActivity.showChatList()`

---

## New Resource Files

### Drawables (9 files)
| File | Description |
|------|-------------|
| `bg_bubble_outgoing.xml` | Rounded rectangle (16/16/16/4dp), echo_primary_tint fill |
| `bg_bubble_incoming.xml` | Rounded rectangle (16/16/4/16dp), echo_card_surface fill |
| `bg_chat_input.xml` | Pill shape (24dp radius), echo_card_surface fill |
| `bg_info_note.xml` | Layer-list: 3dp left accent border + elevated_surface fill |
| `bg_send_button.xml` | Oval, echo_primary_accent fill |
| `ic_tick.xml` | 12dp checkmark vector |
| `ic_double_tick.xml` | 16dp double checkmark vector |
| `ic_back.xml` | 24dp back arrow, tint echo_text_primary |
| `ic_send.xml` | 24dp send arrow, tint echo_bg_main |

### Layouts (6 files)
| File | Description |
|------|-------------|
| `item_chat_list.xml` | Avatar + name/code + preview + time + unread badge |
| `item_chat_message_outgoing.xml` | Right-aligned bubble + time + sync indicator |
| `item_chat_message_incoming.xml` | Left-aligned bubble + 2dp accent border + time |
| `screen_chat_list.xml` | Toolbar + back/join buttons + RecyclerView + FAB + empty state |
| `screen_create_chat.xml` | ScrollView: toolbar + name input + code card + QR + info note + create button |
| `screen_chat_conversation.xml` | Toolbar + sync bar + RecyclerView + empty state + input bar |

### Updated Resources
| File | Changes |
|------|---------|
| `values/strings.xml` | ~40 new chat strings (chat_list_title, chat_empty_*, chat_join_*, chat_create_*, etc.) |
| `values/dimens.xml` | 12 new dimensions: chat_avatar_size, bubble_corner_radius, bubble_padding_*, chat_input_*, etc. |
| `values/colors.xml` | Added `echo_text_muted` (#555D69) |
| `values/themes.xml` | Added `Theme.EchoDrop.Dialog` (Material3 Dark Dialog) |
| `values-night/themes.xml` | Added `Theme.EchoDrop.Dialog` (matching dark variant) |

---

## New Test Files

### 1. `crypto/ChatCryptoTest.java` (12 tests)
- Key derivation: determinism, different codes → different keys, 256-bit, AES algorithm
- Round-trip: plain text, empty string, unicode, long message
- Security: random IV uniqueness, wrong key throws RuntimeException
- Format: Base64 output validation, PBKDF2 salt constant

### 2. `db/ChatEntityTest.java` (19 tests)
- Code generation: 8 chars, valid charset, no ambiguous chars, statistical uniqueness
- Code formatting: hyphen insertion, stripping, uppercasing
- Entity factory: defaults, null name, unique IDs
- Display logic: name vs code fallback, initial character derivation
- ChatMessageEntity: factory defaults, unique IDs, sync state constants

### 3. `db/ChatDaoTest.java` (12 tests)
- CRUD: insert/retrieve by ID, get by code, delete
- Cascade: delete chat removes child messages
- Messages: ordered ASC, correct count
- Aggregations: updateLastMessage, incrementUnread, clearUnread
- Ordering: getAllChats by MAX(last_message_time, created_at) DESC
- Conflict: REPLACE strategy updates existing row

---

## Validation Checklist

| Check | ✅/❌ | Note |
|-------|-------|------|
| Chat code is 8 chars, no O/0/I/1 | ✅ | ChatEntityTest verifies charset (50 runs) |
| AES-256-GCM round-trip preserves plaintext | ✅ | ChatCryptoTest: plain, empty, unicode, long |
| Different codes derive different keys | ✅ | ChatCryptoTest.deriveKey_differentCodes |
| Wrong key decryption fails with exception | ✅ | ChatCryptoTest.decrypt_wrongKey_throwsException |
| Chat deletion cascades to messages | ✅ | ChatDaoTest.deleteChat_cascadesToMessages |
| Messages appear in chronological order | ✅ | ChatDaoTest ordered ASC by created_at |
| QR code generation works | ✅ | ZXing QRCodeWriter in CreateChatFragment |
| Join dialog validates code format | ✅ | PrivateChatListFragment strips and checks length |
| Key cleared on fragment destroy | ✅ | ChatConversationFragment.onDestroyView nulls key |
| 257 tests pass, 0 failures | ✅ | 15 test classes across 9 packages |
