# EchoDrop — Known Limitations

Current state as of **Iteration 9** (Stability, UX Polish & Demo Readiness).

---

## Networking

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 1 | **BLE payload size ≤ 31 bytes** | Only device ID + manifest size fits in advertisement; full messages require Wi-Fi Direct transfer. | By design — BLE is for discovery only. |
| 2 | **Wi-Fi Direct auto-connect picks first peer** | In a multi-peer environment, not all peers may be reached in a single cycle. | Subsequent scan cycles will discover and connect to other peers. |
| 3 | **No Internet relay** | Messages can only propagate to devices within physical BLE range (~10 m). | This is a core design constraint, not a bug. |
| 4 | **Single concurrent transfer** | Only one Wi-Fi Direct peer-to-peer link is possible at a time. | Transfer completes quickly; next peer is connected on next cycle. |
| 5 | **Peer device ID unavailable over Wi-Fi Direct** | BundleSender passes empty string for peer ID, relying on content hash dedup. | Works correctly; minor inefficiency in checking already-seen messages. |

## Storage

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 6 | **200-row storage cap** | Oldest NORMAL/BULK messages are evicted when full. ALERT messages are never evicted. | Sufficient for the target use case; cap prevents unbounded growth. |
| 7 | **Destructive migration on schema change** | Upgrading the DB version drops all existing data. | Acceptable for pre-release; proper migration scripts needed before Play Store release. |
| 8 | **No backup/export** | Messages cannot be exported or backed up. | By design — ephemeral messaging. |

## Security & Privacy

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 9 | **Chat encryption uses chat code as key** | Anyone who knows the 8-character code can decrypt chat messages. | Users should share codes in person; codes are not broadcast. |
| 10 | **No end-to-end encryption for broadcast messages** | Public messages are plaintext on every carrier device. | By design — broadcasts are meant to be public. |
| 11 | **Device ID is a random 4-byte integer** | Not cryptographically unique; collisions theoretically possible. | Probability is very low for the expected peer density. |

## UX / UI

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 12 | **System fonts only** | DM Sans and JetBrains Mono are proxied by system `sans-serif` and `monospace`. Exact typefaces vary by device manufacturer. | Consistent weights and sizes are enforced via styles; visual difference is minimal. |
| 13 | **No push notifications for chat messages** | Chat messages only appear when the app syncs; there is no real-time notification for individual chats. | The foreground service notification indicates the app is running. |
| 14 | **No message editing or deletion (by sender)** | Once posted, a message propagates and cannot be recalled. | TTL ensures automatic expiry; "Got it" allows local deletion. |
| 15 | **No profile or identity** | Users are anonymous; there is no display name or avatar for broadcast messages. | Intentional for privacy; chat names are local nicknames only. |

## Platform

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 16 | **Android only** | No iOS, web, or desktop client. | BLE + Wi-Fi Direct APIs are Android-specific. |
| 17 | **minSdk 24 (Android 7.0)** | Devices below Android 7.0 are not supported. | < 3% of active Android devices as of 2024. |
| 18 | **OEM battery restrictions** | Samsung, Xiaomi, OnePlus and others aggressively kill background services. | Battery optimization guide provided in Settings; user must manually whitelist. |
| 19 | **BLE permissions on Android 12+** | BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, and BLUETOOTH_CONNECT are required at runtime. | Permission request flow handles this; service will not start without grants. |

## Testing

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 20 | **No instrumented (on-device) tests** | All 446 tests are local JVM unit tests (JUnit + Robolectric + Mockito). | Real BLE/Wi-Fi Direct behavior can only be verified on physical devices. |
| 21 | **Downloadable fonts crash in Robolectric** | Any test that inflates Activity/Fragment with Google Fonts provider will fail in local JVM. | Tests avoid full Activity inflation; fragment logic is tested independently. |
