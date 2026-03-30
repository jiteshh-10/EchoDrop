# EchoDrop - Debug Branch Changelog

> Branch: `debug`
> Baseline parent: `main` at iteration-9 hardening
> Scope: reliability fixes, transport hardening, diagnostics additions, and follow-up merge context

---

## Post-debug follow-up on main (2026-03-30)

The following items were delivered after the debug-branch hardening and are now part of mainline behavior:

- Message detail action stack updated to Save, Report, Got it.
- Saved-message persistence added in Room and exposed through a dedicated Saved screen.
- Report action integrated with blocked-device moderation (`BlockedDeviceStore`) and origin-level local cleanup.
- Animated app-bar bulb logo introduced and reused via shared toolbar utility.
- Documentation and status artifacts refreshed for Mar 30, 2026 release context.

This file remains the canonical record of the debug-branch reliability tranche below.

---

## Debug branch commits (historical)

| # | Hash | Description |
|---|------|-------------|
| 1 | `e044ad9` | Timber migration, BLE scan tuning, service retry handling, onboarding persistence wiring |
| 2 | `c692d4b` | Bidirectional sync path, chat bundle filtering, GO address retry, diagnostics support |
| 3 | `361c759` | senderAlias propagation, splash integration, settings routing for docs/diagnostics |

---

## Problems solved in debug branch

1. Chat ciphertext occasionally appeared in broadcast feed due to missing type filter.
2. One-way transfer sessions left response payloads unsynchronized.
3. Wi-Fi Direct group-owner address race caused intermittent failures.
4. Discovery cadence and reconnect behavior were unstable on real OEM devices.
5. Permission coverage for modern Android Wi-Fi/BLE combinations was incomplete.
6. Diagnostics visibility depended on external logcat rather than in-app tooling.

---

## Key technical outcomes from debug branch

### Transport and sync
- Added bidirectional transfer callback path and response-session handling.
- Added receiver-side response generation and dedup-safe processing.
- Improved Wi-Fi Direct state transition reliability and reconnect behavior.

### Data routing
- Excluded CHAT bundles from public home feed query path.
- Preserved all-message query path for DTN forwarding semantics.
- Improved sender alias propagation through forwarding copies.

### Platform hardening
- Added splash setup and improved launch-time consistency.
- Added in-app diagnostics ring buffer and diagnostics screen entry points.
- Expanded settings routes for How It Works and diagnostics access.

---

## Validation status

Debug-branch validation baseline:
- Unit tests: PASS in that cycle
- Build: PASS in that cycle

Current project (main, Mar 30 2026) should be validated against:
- `:app:assembleDebug`
- `:app:testDebugUnitTest`