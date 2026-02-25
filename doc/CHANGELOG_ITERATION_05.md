# Changelog — Iteration 5: Offline Discovery + Manifest Exchange (Control Plane Only)

> **Date:** 2026-02-27  
> **Branch:** `iteration-5`  
> **Status:** ✅ Complete  
> **Tests:** 319 total (62 new), 0 failures

---

## New Production Files

### 1. `service/EchoService.java`
- ForegroundService with type `connectedDevice`
- IMPORTANCE_LOW persistent notification (channel "EchoDrop")
- Creates and manages BleAdvertiser + BleScanner lifecycle
- Static helpers: `isBackgroundEnabled()`, `setBackgroundEnabled()`, `startService()`, `stopService()`, `syncServiceState()`
- `hasBlePermissions()` / `getBlePermissions()` — runtime permission guard (API 31+)
- SharedPreferences storage ("echodrop_prefs" / "bg_enabled")

### 2. `service/BootReceiver.java`
- BroadcastReceiver for `BOOT_COMPLETED`
- Conditionally starts EchoService if background discovery was previously enabled
- Null-safe for both intent and action

### 3. `ble/BleAdvertiser.java`
- BLE advertising with custom Service UUID `ed000001-0000-1000-8000-00805f9b34fb`
- Payload: 6 bytes — device_id (4 bytes, big-endian) + manifest_size (2 bytes, big-endian)
- `ADVERTISE_MODE_LOW_POWER`, connectable, no timeout, medium TX power
- `buildPayload()` / `parsePayload()` — static, testable methods
- `updateManifestSize()` — restarts advertising with new size

### 4. `ble/BleScanner.java`
- Periodic BLE scanning: 10s on / 20s off (33% duty cycle)
- `ConcurrentHashMap<Integer, PeerInfo>` for thread-safe peer tracking
- 2-minute stale peer pruning
- `PeerUpdateListener` callback for scan results
- Filters exclusively for EchoDrop Service UUID

### 5. `mesh/ManifestManager.java`
- Compact binary manifest serialization: 3-byte header + N×28-byte entries
- Wire format: version (1B) + entry_count (2B) + entries (UUID + checksum + priority + reserved + expires_at)
- Max 18 entries (507 bytes fits BLE GATT characteristic)
- SHA-256 truncated to 4 bytes for message checksums
- Static `parse()`, `peekEntryCount()`, `manifestSizeBytes()` utilities
- Null-safe constructor for unit testing without Android context

### 6. `screens/SettingsFragment.java`
- Uses `ScreenSettingsBinding` (ViewBinding)
- SwitchMaterial toggle with AlertDialog confirmation on disable
- `ActivityResultLauncher<String[]>` — requests BLE permissions before enabling service
- Battery guide link → navigates to BatteryGuideFragment
- 7-tap easter egg on version text → navigates to DiscoveryStatusFragment
- Toast countdown feedback starting from 4th tap

### 7. `screens/BatteryGuideFragment.java`
- Uses `ScreenBatteryGuideBinding` (ViewBinding)
- 4 collapsible OEM sections: Samsung, Xiaomi, OnePlus, Stock Android
- Arrow rotation animation (0°↔180°, 200ms, fillAfter=true)
- Done button pops back stack

### 8. `screens/DiscoveryStatusFragment.java`
- Uses `ScreenDiscoveryStatusBinding` (ViewBinding)
- 2×2 stats grid: nearby nodes, last exchange, manifest size, messages stored
- BLE/Wi-Fi status indicator dots
- Peer list RecyclerView + empty state
- 5-second periodic refresh via Handler
- Manifest built on background Executor thread
- LiveData observer for message count

---

## Updated Production Files

### 1. `AndroidManifest.xml`
- Added 11 permissions: BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS
- Added `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`
- Added `<service>` for EchoService (foregroundServiceType=connectedDevice, not exported)
- Added `<receiver>` for BootReceiver with BOOT_COMPLETED intent filter

### 2. `MainActivity.java`
- Added imports: SettingsFragment, BatteryGuideFragment, DiscoveryStatusFragment
- Added `showSettings()` — fragment transaction with slide animations
- Added `showBatteryGuide()` — fragment transaction with slide animations
- Added `showDiscoveryStatus()` — fragment transaction with slide animations

### 3. `screens/HomeInboxFragment.java`
- Added `action_settings` handler in `onMenuItemClicked()` callback
- Added `navigateToSettings()` method delegating to `MainActivity.showSettings()`

### 4. `db/MessageDao.java`
- Added `getActiveMessagesDirect(long now)` — sync query filtering by expiration timestamp
- Added `getActiveMessagesDirect()` — sync query using SQLite `strftime('%s','now')` for current time

### 5. `res/menu/home_menu.xml`
- Added settings menu item with `ic_settings` icon, showAsAction=always

---

## New Resource Files

### Drawables (12 files)
| File | Description |
|------|-------------|
| `ic_settings.xml` | 24dp gear icon, tint echo_text_primary |
| `ic_chevron_right.xml` | 24dp chevron for list navigation |
| `ic_bluetooth.xml` | 24dp Bluetooth icon for status display |
| `ic_clock.xml` | 24dp clock icon for timestamps |
| `ic_database.xml` | 24dp database icon for storage stats |
| `ic_activity.xml` | 24dp activity/pulse icon for exchange stats |
| `ic_battery.xml` | 24dp battery icon for guide link |
| `ic_expand.xml` | 24dp expand/collapse arrow for OEM sections |
| `bg_dev_badge.xml` | Rounded rectangle badge for dev debug screen |
| `bg_status_dot.xml` | Small oval for BLE/Wi-Fi status indicators |
| `bg_stat_card.xml` | Rounded card background for stats grid items |
| `bg_oem_card.xml` | Rounded card background for OEM collapsible sections |

### Layouts (3 files)
| File | Description |
|------|-------------|
| `screen_settings.xml` | Settings toggle + battery guide link + version text (easter egg target) |
| `screen_battery_guide.xml` | ScrollView with 4 collapsible OEM sections + done button |
| `screen_discovery_status.xml` | Stats grid + status dots + peer list RecyclerView + empty state |

### Updated Resources
| File | Changes |
|------|---------|
| `values/strings.xml` | ~50 new strings: settings_*, battery_guide_*, discovery_*, oem_*, service_* |
| `values/dimens.xml` | 7 new dimensions: settings_icon_size, card_corner_radius, stat_card_padding, etc. |

---

## New Test Files

### 1. `mesh/ManifestManagerTest.java` (30 tests)
- Empty manifest: wire format, parse round-trip, size calculation
- Single/multi entry round-trips with UUID, checksum, priority, expiration
- Wire format byte-level verification: version byte, entry count endianness, field offsets
- Max capacity: 18-entry cap, oversized list truncation
- Priority encoding: ALERT=0, NORMAL=1, BULK=2
- UUID conversion: big-endian byte ordering, round-trip fidelity
- Checksum: SHA-256 truncated to 4 bytes, determinism
- Utility methods: peekEntryCount, manifestSizeBytes
- Error handling: null/empty/short byte arrays, corrupted headers
- Integration: real MessageEntity objects through full pipeline

### 2. `ble/BleAdvertiserTest.java` (18 tests)
- Payload structure: exactly 6 bytes, correct field positions
- Endianness: big-endian device_id and manifest_size encoding
- Round-trip: build → parse yields same values
- Size clamping: manifest_size fits unsigned short (0–65535)
- Parse errors: null input, short buffer, exact boundary lengths
- Constants: UUID format validation, service UUID accessibility
- Device ID: random generation, field extraction verification
- Initial state: not advertising before start()

### 3. `ble/BleScannerTest.java` (8 tests)
- Initial state: empty peer map, not scanning
- Duty cycle constants: SCAN_PERIOD=10000, PAUSE_PERIOD=20000
- PeerInfo: data class field population, timestamp tracking
- Operations: clear peers, immutable snapshot from getPeers()

### 4. `service/EchoServiceTest.java` (6 tests)
- Preferences: default background disabled, set/get round-trip
- Permission helpers: getBlePermissions returns correct array for API level
- BootReceiver: null intent safety, null action safety
- Isolation: separate SharedPreferences instances don't cross-contaminate

---

## Validation Checklist

| Check | ✅/❌ | Note |
|-------|-------|------|
| BLE advertising payload is exactly 6 bytes | ✅ | BleAdvertiserTest verifies byte-level |
| Manifest wire format round-trips correctly | ✅ | ManifestManagerTest: single, multi, max entries |
| Max 18 manifest entries (507-byte cap) | ✅ | ManifestManagerTest.buildManifest_maxCapacity |
| SHA-256 checksum truncated to 4 bytes | ✅ | ManifestManagerTest.checksum verification |
| BLE scan duty cycle is 10s/20s | ✅ | BleScannerTest constant verification |
| Stale peers pruned after 2 minutes | ✅ | BleScanner.PEER_TIMEOUT_MS = 120000 |
| BLE permissions checked before service start | ✅ | EchoService.startService() guard |
| Runtime permission request before toggle ON | ✅ | SettingsFragment ActivityResultLauncher |
| BootReceiver null-safe | ✅ | EchoServiceTest: null intent + null action |
| 7-tap easter egg reveals debug screen | ✅ | SettingsFragment tap counter + threshold |
| ForegroundService type is connectedDevice | ✅ | AndroidManifest.xml declaration |
| 319 tests pass, 0 failures | ✅ | 19 test classes across 12 packages |
