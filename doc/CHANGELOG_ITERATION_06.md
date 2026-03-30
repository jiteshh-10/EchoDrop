# Changelog — Iteration 6: Payload Transfer — Data Plane (Wi-Fi Direct)

> Historical scope note: this file documents Iteration 6 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> Date: 2026  
> Branch: iteration-6  
> Status: ✅ Complete  
> Tests: 361 total (42 new), 0 failures

---

## New Production Files (4 files)

1. **transfer/TransferProtocol.java** — Wire protocol for framed TCP sessions. 4-byte length-prefixed frames with "ED06" magic header. Serialize/deserialize MessageEntity fields. Priority-sorted session writes (ALERT → NORMAL → BULK). SHA-256 checksum validation. MAX_FRAME_SIZE = 512KB, PORT = 9876.

2. **transfer/WifiDirectManager.java** — Wi-Fi P2P lifecycle management. BroadcastReceiver for P2P state/peers/connection/device changes. Peer discovery, group formation, connection/disconnection. API 33+ receiver registration, API 27+ channel close. ConnectionCallback interface for UI notification.

3. **transfer/BundleSender.java** — Outbound TCP message transfer. Single-thread daemon executor. Filters expired messages before sending. 5-second connection timeout. SendCallback with onSendComplete/onSendFailed.

4. **transfer/BundleReceiver.java** — Inbound TCP message receiver. ServerSocket on port 9876. AtomicBoolean thread-safe running state. Reads sessions, validates checksums, deduplicates via repo.isDuplicateSync(), inserts to DB. Posts notifications on "New Messages" channel. ReceiveCallback with onTransferStarted/onTransferEnded for UI pulse.

## Updated Production Files (4 files)

1. **service/EchoService.java** — Added WifiDirectManager and BundleReceiver fields. Initialize/start in onStartCommand, teardown in onDestroy. Static TransferStateListener interface for UI pulse speed. BundleReceiver.ReceiveCallback wired to notify transfer state changes via Handler on main looper. Added getters for all subcomponents.

2. **screens/HomeInboxFragment.java** — Added transferActive boolean field. EchoService.setTransferStateListener() in onViewCreated. Sync dot pulse: 500ms during active transfer, 2000ms normal. restartSyncDotPulse() method. Cleans up listener in onDestroyView.

3. **AndroidManifest.xml** — Added 6 Wi-Fi Direct permissions: ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, INTERNET, CHANGE_NETWORK_STATE, ACCESS_NETWORK_STATE, NEARBY_WIFI_DEVICES. Added `<uses-feature android:name="android.hardware.wifi.direct" android:required="false"/>`.

4. **res/layout/activity_main.xml** — Added `android:fitsSystemWindows="true"` to root FrameLayout. Fixes toolbar/status bar overlap on all devices.

## Updated Resource Files

- **res/values/strings.xml** — Added 6 new transfer strings: transfer_channel_name ("New Messages"), transfer_notification_title, transfer_notification_body, transfer_error_checksum, transfer_error_socket, transfer_complete.

## New Test Files (4 files, 42 tests)

1. **TransferProtocolTest.java (29 tests)** — Serialize/deserialize round-trips (normal, empty, unicode, long). Frame and session I/O. Priority sorting. Checksum validation (valid, tampered). Transfer checksum determinism. Constants verification. Full field preservation.

2. **BundleSenderTest.java (4 tests)** — Creation, expired message filtering, unreachable address failure, shutdown cleanup. Uses Robolectric for android.util.Log.

3. **WifiDirectManagerTest.java (4 tests)** — Class existence, empty peers contract, PORT constant, ConnectionCallback interface implementation.

4. **BundleReceiverTest.java (5 tests)** — ReceiveCallback interface existence, onReceiveComplete/onReceiveFailed callbacks, transfer start/end lifecycle, PORT cross-reference.

## Bug Fixes

- **Toolbar/status bar overlap** — Settings gear icon and other toolbar elements overlapped with system status bar on devices with notches or tall status bars. Fixed by adding `fitsSystemWindows="true"` to root layout, which propagates system insets to all child fragments.

## Validation Checklist

| Check | Status |
|-------|--------|
| TransferProtocol wire format implemented | ✅ |
| Priority-sorted session writes | ✅ |
| SHA-256 checksum validation on receive | ✅ |
| WifiDirectManager P2P lifecycle | ✅ |
| BundleSender with expired message filtering | ✅ |
| BundleReceiver with dedup and notifications | ✅ |
| EchoService integration (init/teardown) | ✅ |
| Transfer-aware sync pulse speed | ✅ |
| Wi-Fi Direct permissions in manifest | ✅ |
| fitsSystemWindows UI fix | ✅ |
| Build verification (`assembleDebug`) | ✅ |
| All 361 tests pass (0 failures) | ✅ |
| Documentation updated (5 files) | ✅ |
