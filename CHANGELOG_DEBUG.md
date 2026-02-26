# EchoDrop — Debug Branch Changelog

> **Branch:** `debug`
> **Base:** `main` @ `e836ab0` (iter-9-complete, 446 tests)
> **Date:** February 2026
> **Summary:** Transport pipeline fixes for real-device reliability, bidirectional sync, in-app diagnostics, splash screen, and Settings UX improvements.

---

## Commits

| # | Hash | Description |
|---|------|-------------|
| 1 | `e044ad9` | Timber migration, BLE LOW_LATENCY, EchoService retry, onboarding persistence |
| 2 | `c692d4b` | Bidirectional sync, CHAT filter, GO address retry, diagnostics, permissions |
| 3 | `361c759` | senderAlias, splash screen, Settings links (How it works + Diagnostics) |

**26 files changed, +995 / −161 lines**

---

## Problems Addressed

Real-device testing on Samsung + Realme revealed:

1. **Chat messages appeared as base64 strings on the home feed** — encrypted CHAT bundles were not filtered from the broadcast inbox.
2. **One-way syncing** — only the initiating device sent messages; the server never responded with its own.
3. **Unreliable discovery** — devices showed "No devices nearby" most of the time; the `discovering` guard prevented re-discovery after system timeout.
4. **Alerts one-directional** — related to one-way sync; the receiving device had no mechanism to push its messages back.
5. **WiFi Direct GO address null** — `groupOwnerAddress` was sometimes null on connection, causing NPE.
6. **Missing NEARBY_WIFI_DEVICES permission** — Android 13+ requires this for WiFi Direct; was not requested.
7. **No on-device diagnostic visibility** — Timber logs only visible via `adb logcat`.

---

## Changes by Area

### 1. Transport Pipeline — Bidirectional Sync

**Files:** `BundleSender.java`, `BundleReceiver.java`, `EchoService.java`

- **BundleSender:** Added `BidirectionalCallback` interface extending `SendCallback` with `onResponseReceived(List<MessageEntity>)`. After writing a session the sender now reads a response session (15 s socket timeout) from the server.
- **BundleReceiver:** After reading and processing incoming messages, the receiver queries the local DAO for its own active messages, filters out what the peer just sent (hash dedup), creates forwarding copies (incremented hop count, stamped `seenByIds`), and writes a response session back on the same TCP connection.
- **BundleReceiver:** Extracted `processOneMessage()` helper. Added public `processReceivedMessages(List<MessageEntity>)` for the sender side to process the response.
- **EchoService:** `sendAllMessagesWithRetry()` now uses `BidirectionalCallback`; on response, calls `bundleReceiver.processReceivedMessages()` to insert peer messages.

### 2. Chat Bundle Filtering (Base64 Fix)

**File:** `MessageDao.java`

- `getActiveMessages(long now)` now appends `AND type != 'CHAT'` — encrypted chat bundles are excluded from the home broadcast feed.
- Added `getAllActiveMessages(long now)` that returns **all** messages including CHAT for DTN forwarding purposes.

### 3. WiFi Direct Reliability

**File:** `WifiDirectManager.java`

- **GO address retry:** When `groupOwnerAddress` is null in `onConnectionInfoAvailable()`, retries `requestConnectionInfo()` up to 3 times with 500 ms delay before disconnecting.
- **Re-discovery:** Removed the `if (discovering) return` guard from `discoverPeers()` so discovery can be re-initiated after the system's internal timeout fires.
- Added `isInitialized()` accessor.

### 4. EchoService Improvements

**File:** `EchoService.java`

- **Init order fix:** WiFi Direct + BundleReceiver now initialize **before** BLE advertiser + scanner start, preventing a race where BLE finds peers before WiFi Direct is ready.
- **NEARBY_WIFI_DEVICES permission:** `hasBlePermissions()` and `getBlePermissions()` now include `NEARBY_WIFI_DEVICES` on API 33+.
- **Reconnect cooldown:** After a transfer completes and disconnects, re-discovery is scheduled after a 15-second cooldown (`REDISCOVERY_COOLDOWN_MS`) if BLE peers are still present.
- **Shared Handler:** Replaced per-use `Handler` instances with a single `mainHandler` field.

### 5. BLE Improvements

**Files:** `BleAdvertiser.java`, `BleScanner.java`

- Migrated all logging from `android.util.Log` to Timber with structured `ED:` tag prefixes.
- Scanner now uses `ScanSettings.SCAN_MODE_LOW_LATENCY` for faster peer detection.

### 6. Onboarding Persistence

**File:** `MainActivity.java`

- Added `SharedPreferences`-backed `isOnboardingComplete()` / `markOnboardingComplete()`.
- On launch, skips onboarding and goes directly to `HomeInboxFragment` if already completed.

### 7. Chat Name Propagation (senderAlias)

**Files:** `MessageEntity.java`, `TransferProtocol.java`, `ChatRepo.java`, `BundleReceiver.java`, `AppDatabase.java`

- **MessageEntity:** New `sender_alias` column (default `""`). Added getter/setter. `createChatBundle()` overload accepts `chatName`.
- **TransferProtocol:** `serialize()` / `deserialize()` now include `senderAlias` as an additional 2-byte-len + UTF-8 field after `scopeId`.
- **ChatRepo.sendMessage():** Looks up the chat name and embeds it in the outgoing DTN bundle.
- **ChatRepo.processIncomingChatBundle():** If the local chat has no name but the received bundle carries one, adopts it.
- **BundleReceiver:** Copies `senderAlias` when creating forwarding copies in the response session.
- **AppDatabase:** Version bumped from 4 → 5 (destructive migration).

### 8. In-App Diagnostics

**Files:** `DiagnosticsLog.java` *(new)*, `DiagnosticsFragment.java` *(new)*, `screen_diagnostics.xml` *(new)*

- **DiagnosticsLog:** Thread-safe in-memory ring buffer (500 entries max). Timestamped entries formatted as `HH:mm:ss.SSS [tag] message`. Static API: `log()`, `getEntries()`, `getAllText()`, `clear()`, `size()`.
- **DiagnosticsLog.DiagTree:** Custom `Timber.Tree` that captures all `ED:`-prefixed log messages into the ring buffer.
- **DiagnosticsFragment:** Auto-refreshes every 2 s, monospace display, auto-scroll, copy-to-clipboard, clear buttons.
- **MainActivity:** Plants `DiagTree` alongside `DebugTree`.
- **Accessible via:** Settings → Diagnostics log **and** Discovery Status → Logs button.

### 9. Splash Screen

**Files:** `build.gradle`, `themes.xml`, `AndroidManifest.xml`, `MainActivity.java`

- Added `androidx.core:core-splashscreen:1.0.1` dependency.
- New `Theme.EchoDrop.Splash` style (background `echo_bg_main`, icon `ic_launcher`, post-splash `Theme.EchoDrop`).
- Activity theme set to splash in manifest; `SplashScreen.installSplashScreen(this)` called before `super.onCreate()`.

### 10. Settings UX

**Files:** `screen_settings.xml`, `SettingsFragment.java`, `HowItWorksFragment.java`, `MainActivity.java`, `strings.xml`

- **How it works row:** New settings entry navigates to `HowItWorksFragment` in "from settings" mode — shows "Got it" button that pops back instead of navigating to HomeInbox.
- **Diagnostics log row:** New settings entry navigates directly to `DiagnosticsFragment`.
- `HowItWorksFragment.newInstance(boolean fromSettings)` factory method added.
- `MainActivity.showHowItWorksFromSettings()` navigation method added.

### 11. Other

- `PermissionsFragment.java`: Added missing permission request.
- `ManifestManager.java`: Removed unused import.
- `DiscoveryStatusFragment.java`: Added live connection/transfer state indicators, wired "Logs" button.
- `screen_discovery_status.xml`: Added "Logs" button to toolbar.

---

## Test Status

| Metric | Value |
|--------|-------|
| Total tests | **446** |
| Failures | **0** |
| Test suites | 30 |
| Build | ✅ `assembleDebug` passes |

All existing tests continue to pass with no modifications required.

---

## Checklist Cross-Reference

| # | Item | Status |
|---|------|--------|
| 1 | NEARBY_WIFI_DEVICES permission (manifest + runtime + service) | ✅ Done |
| 2 | PermissionsFragment requests all permissions | ✅ Done |
| 3 | BLE legacy advertising | ✅ Already using `startAdvertising` (legacy) |
| 4 | WiFi Direct GO address retry when null | ✅ Done (3×500 ms) |
| 5 | In-app diagnostics logging | ✅ Done (DiagnosticsLog + Fragment) |
| 6 | Chat name metadata in DTN bundle | ✅ Done (senderAlias) |
| 7 | WiFi Direct init race fix | ✅ Done (init before BLE) |
| 8 | Bidirectional sync | ✅ Done (response session) |
| 9 | CHAT bundles filtered from home feed | ✅ Done (DAO filter) |
| 10 | Onboarding persistence | ✅ Done (SharedPreferences) |
| 11 | Move "How it works" to Settings | ✅ Done |
| 12 | Splash screen | ✅ Done (AndroidX SplashScreen) |
| 13 | WiFi Direct reconnect strategy | ✅ Done (15 s cooldown) |
| 14 | Discovery flag reset | ✅ Done (removed guard) |
