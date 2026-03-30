# Changelog — Hotfix 01: Runtime Permissions & End-to-End Transfer Pipeline

> Historical scope note: this file documents Hotfix 01 only. For current release behavior, see `README.md`, `CHANGELOG.md`, `doc/CHANGELOG_ITERATION_10.md`, and `doc/PROJECT_STATUS_MAR30_2026.md`.

> Date: 2026-02-26  
> Branch: main  
> Status: ✅ Complete  
> Tests: 361 total, 0 failures (no regressions)

---

## Problem Statement

After deploying the Iteration 6 APK to two physical devices, two critical issues were identified:

1. **No Bluetooth permission dialog** — The PermissionsFragment "Allow" button navigated to the home screen without ever calling `requestPermissions()`. The entire onboarding permission screen was cosmetic.
2. **Posts not visible across devices** — BLE discovery, Wi-Fi Direct connection, and TCP transfer components existed as isolated modules but were never wired together. No end-to-end pipeline connected discovery → connection → message transfer.

### Root Causes

| Issue | Root Cause |
|-------|-----------|
| No permission dialog | `PermissionsFragment.permissionsAllowButton` click called `navigateToHome()` directly — zero runtime permission requests |
| `hasBlePermissions()` false positives | Method used `\|\|` (any-of) instead of `&&` (all-of) — service could start with only 1 of 3 BLE permissions |
| Service not auto-started | `EchoService.startService()` only invoked from `SettingsFragment` toggle — never during onboarding or app relaunch |
| No cross-device transfer | `BleScanner`, `WifiDirectManager`, and `BundleSender` were standalone components with no orchestration — scanner output was never fed into Wi-Fi Direct discovery |
| Fake peer count | `HomeInboxFragment` hardcoded `updateSyncIndicator(3)` — UI showed 3 peers regardless of actual BLE discovery |

---

## Updated Production Files (4 files, +200 / -5 lines)

### 1. screens/PermissionsFragment.java — Full Rewrite

**Before:** "Allow" and "Later" buttons both called `navigateToHome()`. No `requestPermissions()` call anywhere.

**After:** Complete rewrite with `ActivityResultLauncher<String[]>`:

- **Permissions requested:** BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN (API 31+), ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES (API 33+), POST_NOTIFICATIONS (API 33+)
- **On grant:** `EchoService.setBackgroundEnabled(true)` → `EchoService.startService()` → toast → navigate home
- **On deny:** Toast "you can enable later in Settings" → navigate home
- **Pre-S devices:** No BLE runtime permissions needed — directly enables service and navigates

### 2. service/EchoService.java — Pipeline Orchestration + Bug Fix

**Bug fix:** `hasBlePermissions()` changed `||` to `&&` — all 3 BLE permissions must be granted.

**New fields:**
- `BundleSender bundleSender` — outbound transfer client
- `boolean wifiDirectConnected` — tracks Wi-Fi Direct connection state

**New interfaces:**
- `PeerCountListener` + `onPeerCountChanged(int count)` — UI observes real BLE peer count
- `setPeerCountListener(@Nullable PeerCountListener)` — static setter

**New methods:**
- `notifyPeerCount(int count)` — posts peer count to main thread via Handler
- `sendAllMessages(InetAddress)` — queries `dao.getActiveMessagesDirect()`, sends via BundleSender on background thread, disconnects after completion

**Orchestration pipeline wired in `onCreate()`:**

```
BLE Scanner finds peers
    → Log count + notifyPeerCount() to UI
    → Start Wi-Fi Direct discovery (if not already connected)
        → onPeersAvailable: auto-connect to first peer
            → onConnected:
                ├── Client side: sendAllMessages(groupOwnerAddress)
                └── Group Owner: BundleReceiver already listening on :9876
            → onDisconnected: reset wifiDirectConnected flag
```

**Updated `onDestroy()`:** Added `bundleSender.shutdown()` for clean teardown.

### 3. MainActivity.java — Auto-start Service

Added service auto-start in `onCreate()`:

```java
if (EchoService.hasBlePermissions(this) && EchoService.isBackgroundEnabled(this)) {
    EchoService.startService(this);
}
```

Ensures the mesh service restarts on every app launch without requiring the user to toggle settings.

### 4. screens/HomeInboxFragment.java — Real Peer Count

- Changed `updateSyncIndicator(3)` → `updateSyncIndicator(0)` (start at zero, not fake count)
- Added `EchoService.setPeerCountListener()` in `onViewCreated` — receives real BLE peer counts
- Added `EchoService.setPeerCountListener(null)` cleanup in `onDestroyView()` to prevent leaks

---

## Validation Checklist

| Check | Status |
|-------|--------|
| PermissionsFragment requests BLE permissions (API 31+) | ✅ |
| PermissionsFragment requests ACCESS_FINE_LOCATION | ✅ |
| PermissionsFragment requests NEARBY_WIFI_DEVICES (API 33+) | ✅ |
| PermissionsFragment requests POST_NOTIFICATIONS (API 33+) | ✅ |
| EchoService auto-starts after onboarding grant | ✅ |
| EchoService auto-starts on app relaunch | ✅ |
| hasBlePermissions() requires ALL 3 BLE permissions | ✅ |
| BLE scanner → Wi-Fi Direct discovery wired | ✅ |
| Wi-Fi Direct → auto-connect to first peer | ✅ |
| Client side → sendAllMessages via BundleSender | ✅ |
| Group owner side → BundleReceiver on port 9876 | ✅ |
| HomeInboxFragment shows real peer count | ✅ |
| PeerCountListener cleaned up in onDestroyView | ✅ |
| Build: assembleDebug passes | ✅ |
| Tests: 361 tests, 0 failures | ✅ |
