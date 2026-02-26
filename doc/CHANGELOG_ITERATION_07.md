# Changelog — Iteration 7: Store-Carry-Forward (Multi-Hop DTN)

> **Branch:** `iteration-7`
> **Date:** 2026
> **Tests:** 405 (44 new) — 0 failures — 100% pass rate

---

## Summary

Iteration 7 adds multi-hop message propagation to the EchoDrop mesh network. Messages now carry a **hop count** and a **seen-by device list**, enabling store-carry-forward delivery across intermediary devices (A→B→C) while preventing infinite loops and enforcing a maximum propagation depth of 5 hops.

---

## New Files

| File | Purpose |
|------|---------|
| `util/DeviceIdHelper.java` | Persistent 8-char hex device ID via SharedPreferences |
| `db/MessageEntityHopTest.java` | 30 tests for hop_count, seen_by_ids helpers |
| `transfer/TransferProtocolHopTest.java` | 11 tests for ED07 wire format with hop fields |
| `transfer/BundleSenderForwardingTest.java` | 7 tests for forwarding filter logic |
| `util/DeviceIdHelperTest.java` | 4 tests for device ID generation |

---

## Modified Files

### Schema & Data Layer

| File | Change |
|------|--------|
| `db/MessageEntity.java` | Added `hop_count` (int, default 0), `seen_by_ids` (String, default ""), `MAX_HOP_COUNT = 5`, helpers: `isAtHopLimit()`, `hasBeenSeenBy(id)`, `addSeenBy(id)` |
| `db/AppDatabase.java` | Version bumped 2 → 3 (destructive migration) |

### Wire Protocol

| File | Change |
|------|--------|
| `transfer/TransferProtocol.java` | Magic bumped "ED06" → "ED07"; serialize/deserialize now includes `hopCount` (int) and `seenByIds` (String) |

### Transfer Logic

| File | Change |
|------|--------|
| `transfer/BundleSender.java` | Added `sendForForwarding()` with filtering: expired, hop limit, seen-by-ids, LOCAL scope. Creates forwarded copies with hop+1 and local device ID in seen list |
| `transfer/BundleReceiver.java` | Stamps local device ID into each received message's `seenByIds` |
| `service/EchoService.java` | `sendAllMessages()` now uses `sendForForwarding()` with DeviceIdHelper |

### UI

| File | Change |
|------|--------|
| `screens/MessageDetailFragment.java` | Displays "Forwarded N times" or "Direct (not forwarded)" below timestamp |
| `res/layout/fragment_message_detail.xml` | Added `detail_forwarded_count` TextView |
| `screens/DiscoveryStatusFragment.java` | Added `updateHopStats()` — computes average hops and forwarded message count |
| `res/layout/screen_discovery_status.xml` | Added "Multi-Hop Stats" section with Avg Hops and Forwarded count cards |

### Resources

| File | Change |
|------|--------|
| `res/values/strings.xml` | Added 5 new strings: `detail_forwarded_count`, `detail_forwarded_none`, `discovery_avg_hops`, `discovery_forwarded_count`, `discovery_hop_section_title` |

### Tests (Modified)

| File | Change |
|------|--------|
| `transfer/TransferProtocolTest.java` | Updated `magic_isED06` → `magic_isED07` |

---

## Design Decisions

1. **MAX_HOP_COUNT = 5** — Prevents flood storms; 5 hops covers a large venue without unbounded propagation.
2. **Comma-separated seen_by_ids** — Simple, compact, no JSON parsing overhead. Room stores as TEXT with default "".
3. **DeviceIdHelper** — Persistent 8-char hex ID from UUID. Stored in SharedPreferences, separate from BLE's integer device ID.
4. **Wire format versioned "ED07"** — Prevents old clients from misinterpreting new fields. Clean break from iteration 6.
5. **Forwarded copy pattern** — `sendForForwarding()` creates a copy of each message with hop+1, never mutating the local DB copy.
6. **Empty peerDeviceId accepted** — Wi-Fi Direct doesn't expose the peer's EchoDrop device ID; content hash dedup handles the rest.

---

## Test Summary

| Test Class | Tests | Status |
|------------|-------|--------|
| MessageEntityHopTest | 30 | ✅ Pass |
| TransferProtocolHopTest | 11 | ✅ Pass |
| BundleSenderForwardingTest | 7 | ✅ Pass |
| DeviceIdHelperTest | 4 | ✅ Pass |
| TransferProtocolTest (updated) | 29 | ✅ Pass |
| **All existing tests** | 361 | ✅ Pass |
| **Total** | **405** | **✅ 100%** |
