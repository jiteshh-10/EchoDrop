# EchoDrop Implementation Status (Mar 30, 2026)

## Overview
This document consolidates all delivered work up to Mar 30, 2026 for EchoDrop Lite, including Phase 1, Phase 2, Phase 3 hardening, validation assets, test updates, and known remaining risks.

Current transport model is BLE advertise/scan + GATT transfer for DTN store-carry-forward behavior.

## Completed Scope

### Phase 1 (BLE + GATT Core)
- Enforced BLE + GATT transfer path as primary runtime behavior.
- Added/standardized structured logs for connection and transfer visibility.
- Foreground service orchestration active with notification controls.
- Added permission-aware service start guards.

Key files:
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`
- `app/src/main/java/com/dev/echodrop/ble/BleScanner.java`
- `app/src/main/java/com/dev/echodrop/ble/BleAdvertiser.java`
- `app/src/main/java/com/dev/echodrop/ble/GattServer.java`

### Phase 2 (Relay DTN)
- Added relay metadata and controls:
  - `origin`
  - `ttl_ms`
  - `hop_count` and max-hop enforcement
- Added relay gate logic and dedup protection.
- Added relay visibility logs (`RELAY_TRIGGERED`, `RELAY_SENT`, `RELAY_RECEIVED`, `DEDUP_SKIPPED`).

Key files:
- `app/src/main/java/com/dev/echodrop/db/MessageEntity.java`
- `app/src/main/java/com/dev/echodrop/repository/MessageRepo.java`
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`
- `app/src/main/java/com/dev/echodrop/ble/GattServer.java`

### Phase 3 (Stability + UX)
- Bluetooth OFF/ON stack pause-resume handling in service lifecycle.
- BLE power profile tuned for better battery behavior:
  - scan duty: 8s/30s
  - balanced scan mode
  - advertiser low-power + medium TX
- GATT cooldown/backoff improvements.
- Added blocked device ID support in settings and runtime relay filters.
- Added OEM battery guide enhancements (including Realme section).
- Added diagnostics in-app log ring buffer and increased retention to 1000 entries.

Key files:
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`
- `app/src/main/java/com/dev/echodrop/ble/BleScanner.java`
- `app/src/main/java/com/dev/echodrop/ble/BleAdvertiser.java`
- `app/src/main/java/com/dev/echodrop/util/BlockedDeviceStore.java`
- `app/src/main/java/com/dev/echodrop/util/DiagnosticsLog.java`
- `app/src/main/java/com/dev/echodrop/screens/SettingsFragment.java`
- `app/src/main/res/layout/screen_settings.xml`
- `app/src/main/java/com/dev/echodrop/screens/BatteryGuideFragment.java`
- `app/src/main/res/layout/screen_battery_guide.xml`
- `app/src/main/res/values/strings.xml`

## Recent Reliability Fixes (Post Validation Logs)

### 1. Stable BLE Identity Across Restarts
Issue:
- Device IDs in BLE advertisement could change after service restarts on some Android versions due to restricted adapter MAC behavior.

Fix:
- BLE advertiser device ID now derives from persistent `DeviceIdHelper` value.

File:
- `app/src/main/java/com/dev/echodrop/ble/BleAdvertiser.java`

### 2. Private Chat Processing on GATT Receive Path
Issue:
- Incoming `CHAT` bundles were inserted into messages table but chat conversation processing was not consistently executed in GATT receive path.

Fix:
- GATT receive path now calls `ChatRepo.processIncomingChatBundle(...)` for `CHAT` bundles.

File:
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`

### 3. Outgoing Chat Bundle Origin Metadata
Issue:
- Some relayed chat bundles appeared with `origin=unknown`.

Fix:
- Outgoing chat bundles now explicitly set `origin` from persistent device ID.

File:
- `app/src/main/java/com/dev/echodrop/repository/ChatRepo.java`

### 4. Service Resilience on Task Removal
Fix:
- Added restart path on `onTaskRemoved(...)` when background mode is enabled and permissions are granted.

File:
- `app/src/main/java/com/dev/echodrop/service/EchoService.java`

## Validation Assets Added
- Structured matrix: `doc/PHASE3_VALIDATION_MATRIX.md`
- Multi-device capture script: `tools/phase3_relay_validation.ps1`

## Test and Build Status
- Full unit tests previously validated after updates:
  - `:app:testDebugUnitTest` passed.
- Current packaging:
  - `:app:assembleDebug` passed.
- Latest debug APK path:
  - `app/build/outputs/apk/debug/app-debug.apk`

## Key Markers Verified in Field Logs
Observed in device logs:
- `GATT_CONNECTED`
- `MANIFEST_EXCHANGED`
- `RELAY_TRIGGERED`
- `RELAY_SENT`
- `RELAY_RECEIVED`
- `MESSAGE_RECEIVED`
- `DB_INSERT`
- `DEDUP_SKIPPED`

## Known Remaining Risks
- Long-run OEM-specific background process behavior still requires broader physical-device regression cycles.
- Real-world dense and sparse mobility stress should continue using the provided matrix and script.

## Branch and Merge Intent
This status reflects code prepared on `debug` and intended to be merged into `main` with complete documentation of work up to Mar 30, 2026.
