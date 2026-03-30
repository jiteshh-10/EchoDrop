# EchoDrop Lite Phase 3 Validation Matrix

Date: 2026-03-30
Scope: Android 11 through latest, BLE + GATT only, DTN store-carry-forward

## 1. Baseline Assumptions

- BLE effective range: 5 m to 30 m depending on walls/interference.
- Transport path: BLE scan/advertise + GATT manifest/transfer only.
- No internet required.
- Relay enabled with hop and TTL controls.

## 2. Build and Deployment Prerequisites

1. Build debug APK:

```powershell
./gradlew assembleDebug
```

2. APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

3. Install on each test phone:

```powershell
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

4. Grant required permissions (or through UI prompt):

```powershell
adb -s <SERIAL> shell pm grant com.dev.echodrop android.permission.ACCESS_FINE_LOCATION
adb -s <SERIAL> shell pm grant com.dev.echodrop android.permission.BLUETOOTH_SCAN
adb -s <SERIAL> shell pm grant com.dev.echodrop android.permission.BLUETOOTH_CONNECT
adb -s <SERIAL> shell pm grant com.dev.echodrop android.permission.BLUETOOTH_ADVERTISE
```

5. Launch app:

```powershell
adb -s <SERIAL> shell am start -n com.dev.echodrop/.MainActivity
```

## 3. Required Log Markers

The following markers are considered authoritative for Phase 3 checks:

- `GATT_CONNECTED`
- `MANIFEST_EXCHANGED`
- `MESSAGE_RECEIVED`
- `DB_INSERT`
- `RELAY_TRIGGERED`
- `RELAY_SENT`
- `RELAY_RECEIVED`
- `DEDUP_SKIPPED`

Useful stability markers:

- `ED:SERVICE_ALREADY_RUNNING`
- `ED:GATT_COOLDOWN`
- `ED:BT_STATE_OFF -> pause_ble_stack`
- `ED:BT_STATE_ON -> resume_ble_stack`
- `ED:BLE_SCAN_START`
- `ED:BLE_ADV_START`

## 4. Core Functional Matrix

### TC-1 Direct 1-hop

Setup:
- Keep A and B at 2 m to 5 m.

Action:
- Send message from A.

Expected:
- B receives in 1 s to 3 s.
- B logs `MESSAGE_RECEIVED` and `DB_INSERT`.
- Connection path logs `GATT_CONNECTED`, `MANIFEST_EXCHANGED`.

Pass criteria:
- End-to-end latency <= 3 s for at least 5/5 attempts.

### TC-2 Delayed encounter DTN

Setup:
- A and B online.
- C offline/out of range.

Action:
- A sends to B, then later bring C close to B.

Expected:
- C receives A-origin message via B.
- `RELAY_TRIGGERED`, `RELAY_SENT`, `RELAY_RECEIVED` appear.
- `hop_count` increments and stays <= max.

Pass criteria:
- C receives delayed relay in all 5/5 trials.

### TC-3 Multi-hop chain

Setup:
- Sequential encounter A -> B -> C -> D.

Expected:
- D eventually receives A message.
- Message dropped only when exceeding hop limit.

Pass criteria:
- 3/3 chain runs complete without crash.

### TC-4 Simultaneous broadcast

Setup:
- 5 devices in same room.

Action:
- All devices send messages repeatedly for 10 minutes.

Expected:
- No crash/ANR.
- Dedup works (`DEDUP_SKIPPED` occurs when duplicate path appears).

Pass criteria:
- Zero app crashes and no unbounded duplicate growth.

## 5. Edge Case Matrix

### EC-1 Duplicate flood

Action:
- Force repeated relay opportunities for same bundle.

Expected:
- Single insertion per device.
- `DEDUP_SKIPPED` logged for repeats.

### EC-2 TTL expiry

Action:
- Wait until message expiry before encounter.

Expected:
- No relay for expired message.
- No `DB_INSERT` for expired bundle.

### EC-3 Device restart persistence

Action:
- Receive message, kill app/process, reopen.

Expected:
- Message persists in DB and UI.

### EC-4 Bluetooth OFF then ON

Action:
- Toggle Bluetooth OFF then ON.

Expected:
- Service pauses/resumes BLE stack automatically.
- Logs: `ED:BT_STATE_OFF -> pause_ble_stack`, then `ED:BT_STATE_ON -> resume_ble_stack`.

### EC-5 Permission denial

Action:
- Deny BLE permission and retry.

Expected:
- No crash.
- User guided to app settings on permanent denial.

### EC-6 Rapid movement short contact

Action:
- Enter/leave range within <2 seconds repeatedly.

Expected:
- No corruption, no crash.
- Partial sessions recover on next encounter.

### EC-7 Same text collision

Action:
- Two origins send same text.

Expected:
- Different IDs preserved.
- Both entries appear if IDs/hashes differ by creation bucket logic.

## 6. Real-World Scenarios

- Classroom random mobility (10 devices).
- Home relay A-room1, B-hall, C-room2.
- Dead zone sparse encounter pattern.
- Dense cluster 20+ devices (if available).

## 7. OEM Compatibility Matrix

Minimum required physical coverage:

- Samsung Android 13+
- Realme or Xiaomi
- Android 11 device

Record per OEM:

- Permission prompt behavior.
- Background survival over 30+ minutes.
- Bluetooth toggle recovery.
- Relay success rate.

## 8. Failure Diagnosis Map

- No peer discovered: scan/permission/Bluetooth state.
- Peer discovered but no data: GATT/manifest path.
- Received but not relayed: storage/relay filters (TTL, hop, blocklist).
- Crash/freeze: lifecycle/threading.

## 9. Final Exit Criteria

All must pass:

1. A -> B direct messaging.
2. A -> B -> C delayed relay.
3. No duplicate persistence.
4. App restart persistence.
5. Works on at least 3 OEM/device classes above.
6. Works without internet.

## 10. Evidence Template

Capture for each case:

- Device serials and model names.
- Start/end timestamps.
- Relevant log snippets (with marker lines).
- Pass/fail and defect ID if fail.

Suggested attachment names:

- `logs/tc1_direct_A_B.txt`
- `logs/tc2_delayed_A_B_C.txt`
- `logs/toggle_bt_recovery.txt`
- `logs/duplicate_flood.txt`

## 11. Message Curation and Moderation Matrix (Mar 30, 2026)

### MC-1 Save toggle persistence

Setup:
- Ensure at least one active broadcast message exists.

Action:
- Open message detail -> tap Save -> navigate back -> open Saved screen.

Expected:
- Message appears in Saved list.
- Re-open detail from Saved and confirm button state is "unsave".

Pass criteria:
- Saved list updates reactively with no app restart required.

### MC-2 Unsave path

Action:
- From message detail, tap Unsave.

Expected:
- Message disappears from Saved list on next observer update.
- Inbox visibility remains unaffected unless separately dismissed/deleted.

Pass criteria:
- Saved query output matches expected post-toggle state.

### MC-3 Report to blocked-origin flow

Setup:
- Use a message containing non-empty `origin` metadata.

Action:
- Open detail -> tap Report.

Expected:
- Origin ID is added to blocked store.
- Local messages with that origin are removed.
- Return navigation to previous screen occurs.

Pass criteria:
- Reported origin no longer appears in inbox from existing local rows.

### MC-4 Settings unblock interoperability

Action:
- After MC-3, open Settings blocked-device management and unblock the same origin.

Expected:
- Unblock action succeeds and blocked summary updates.
- Subsequent receive-path filtering behavior follows updated blocklist state.

Pass criteria:
- Blocklist add/remove lifecycle remains consistent across Message Detail and Settings surfaces.
