# EchoDrop Changelog

## [debug → main] — 2026-02-27

### Summary

Major stability overhaul for the WiFi Direct transport layer, notification system,
and onboarding UX. These changes fix real-world device-to-device transfer failures
observed in field logs and streamline the first-launch experience.

---

### Transport & WiFi Direct (WifiDirectManager, EchoService)

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| Connections never succeed | `GROUP_DISSOLVED` broadcast kills `CONNECTING` state — Android dissolves stale groups *during* new group formation | Preserve `CONNECTING` on `GROUP_DISSOLVED`; only `CONNECTED` resets to `IDLE` |
| Both devices compete for Group Owner | Default `groupOwnerIntent` (unset) causes role negotiation failures | Set `config.groupOwnerIntent = 0` so initiator prefers client role |
| Stale groups block new connections | Lingering P2P groups from prior sessions cause spurious disconnects | Call `removeGroup()` before `discoverPeers()` |
| Discovery stuck forever | OS silently stops peer discovery; `DISCOVERING` state never expires | 30 s `DISCOVERY_TIMEOUT_MS` resets to `IDLE` |
| Connect stuck forever | Group never forms; `CONNECTING` has no timeout | 45 s `CONNECT_TIMEOUT_MS` with `removeGroup` cleanup |
| Rapid retry loop on connect failure | Generic `reason=0` failures return to `IDLE` immediately | 3 s short cooldown before next attempt |
| Duplicate broadcast receivers | `initialize()` re-registered on every `onStartCommand` | Made `initialize()` idempotent with `already_init` guard |
| Log spam on P2P disabled | Multiple identical `WIFI_P2P_DISABLED` log entries per event | Added `wasEnabled != p2pEnabled` guard |

**State machine**: Full `P2pState` enum (`IDLE` → `DISCOVERING` → `CONNECTING` → `CONNECTED` → `COOLDOWN`) with enforced transitions, exponential backoff on `BUSY` errors (2 s → 4 s → 8 s → 15 s), and adaptive rediscovery cooldowns (15 s productive / 60 s already-synced).

**Orchestration gating**: BLE → WiFi Direct → Transfer pipeline now gates on both Bluetooth enabled *and* P2P enabled before triggering discovery.

---

### Notifications (BundleReceiver, EchoService, MainActivity)

- **Chat deep-link**: Tapping a chat notification navigates directly to the conversation via `navigate_to=chat_conversation` intent extras.
- **Unique PendingIntents**: Each notification uses a unique `requestCode` so tapping different notifications opens the correct chat.
- **Monochrome icon**: New `ic_notification.xml` vector drawable (concentric ripple circles) replaces `ic_launcher` mipmap — renders correctly on all OEMs.
- **High priority**: Chat notifications use `IMPORTANCE_HIGH` / `PRIORITY_HIGH` for heads-up display.
- **Foreground service notification** (bitchat-inspired):
  - Title: **"Mesh running — X peers"** with dynamic peer count updates.
  - Action button: **"Quit EchoDrop"** stops the service.
- **`onNewIntent()` handling**: `MainActivity` processes notification deep-links on both cold start and warm re-open.

---

### Onboarding & UX

- **No more "How It Works" or Permissions screens** after first launch:
  - `OnboardingConsentFragment`: "Get Started" goes straight to `HomeInboxFragment`.
  - "How It Works" link and "Skip" removed from onboarding.
  - `showPermissions()` / `showHowItWorks()` in `MainActivity` now redirect to `showHomeInbox()`.
- **Inline permission requests**: `HomeInboxFragment` auto-requests BLE, location, and notification permissions on first view via `ActivityResultLauncher`. No separate permissions screen.
- **Prerequisite warnings**: Amber sync indicator in `HomeInboxFragment` when Bluetooth or WiFi P2P is off.

---

### Bug Fixes

- **`BundleSender.sendForForwarding()`**: Fixed missing `setSenderAlias()` on forwarding copies — chat names were lost during DTN multi-hop forwarding.
- **Auto-connect scope**: WiFi Direct auto-connect now works from both `DISCOVERING` and `IDLE` states (peers can arrive after `GROUP_DISSOLVED`).
- **Post-transfer hold**: Increased from 500 ms to 5000 ms to reduce P2P group churn.
- **GO address retries**: Increased from 3 × 500 ms to 10 × 250 ms for more reliable group owner address resolution.

---

### Files Changed

| File | Change |
|------|--------|
| `WifiDirectManager.java` | P2P state machine, timeouts, GROUP_DISSOLVED fix, groupOwnerIntent, stale group cleanup |
| `EchoService.java` | Orchestration gating, bitchat-style notification, peer count updates, quit action |
| `BundleReceiver.java` | Chat deep-link notifications, monochrome icon, IMPORTANCE_HIGH |
| `BundleSender.java` | senderAlias fix in forwarding copy |
| `MainActivity.java` | onNewIntent deep-link handling, onboarding redirects |
| `HomeInboxFragment.java` | Inline permission requests, prerequisite warnings |
| `OnboardingConsentFragment.java` | Simplified to single "Get Started" → HomeInbox path |
| `ic_notification.xml` | New monochrome vector drawable for notifications |
| `strings.xml` | Notification strings, peer count format |
| `WifiDirectManagerTest.java` | Updated callback impl for new interface |

### Test Results

All **446 unit tests** pass across all three commits.
