# EchoDrop - Demo Script

Recommended duration: 10-12 minutes
Audience: product review, architecture review, or QA walkthrough

---

## Prerequisites

| Item | Requirement |
|------|-------------|
| Devices | 2 Android phones (API 24+) |
| App install | Same EchoDrop build on both phones |
| Bluetooth | Enabled on both |
| Nearby permissions | Granted on both |
| Internet | Optional; demo still works with data and Wi-Fi off |

---

## Act 1 - First Launch and Permissions (2 min)

### Phone A
1. Open app.
2. On onboarding, tap primary CTA.
3. Grant required permissions on Permissions screen.
4. Arrive at Home inbox.

### Phone B
1. Repeat the same steps.

Talking point:
- First-run flow is deterministic: onboarding -> permissions -> home.

---

## Act 2 - Broadcast Post (1.5 min)

### Phone A
1. Tap compose FAB.
2. Post: `Free coffee near Lab A`.
3. Keep scope nearby and short TTL.
4. Confirm message appears in Phone A inbox.

Talking point:
- Message is persisted locally first, then shared over proximity transport.

---

## Act 3 - Propagation to Nearby Peer (1.5 min)

### Both phones
1. Keep phones close.
2. Wait for nearby indicator update.
3. Confirm Phone B receives the message.

Talking point:
- Delivery occurs through local mesh logic, not cloud dependency.

---

## Act 4 - Message Detail Actions (2 min)

### Phone B
1. Open message detail.
2. Highlight action order at the bottom:
   - Save
   - Report
   - Got it / Dismiss

### Save demo
1. Tap Save.
2. Observe save confirmation.
3. Tap again to unsave and observe toggle.

### Report demo
1. Re-open detail for a message with valid origin.
2. Tap Report.
3. Explain behavior:
   - sender origin is blocked
   - local messages from that origin are removed

Talking point:
- Moderation is local-first and immediate.

---

## Act 5 - Saved Screen (1 min)

### Phone A or B
1. Save at least one message from detail.
2. From Home toolbar, open Saved.
3. Show saved list and tap-through to message detail.
4. Unsave and show reactive list update.

Talking point:
- Saved state is persisted in Room (`saved` flag), not transient UI state.

---

## Act 6 - Settings Moderation Controls (1 min)

1. Open Settings.
2. Show blocked-device management section.
3. Demonstrate unblock flow from blocked list management.

Talking point:
- Report action writes to the same blocklist source managed in Settings.

---

## Act 7 - Reliability and Close (1-2 min)

1. Mention diagnostics and battery guidance in Settings.
2. Mention test/build status in current release cycle.

Close with:
- offline-first
- TTL-based retention
- saved + moderation workflows integrated with existing relay architecture

---

## Troubleshooting

| Symptom | Likely Cause | Quick Check |
|---------|--------------|-------------|
| No peer detected | Bluetooth/permissions | Verify nearby permissions and Bluetooth state |
| Message not appearing on peer | Short encounter window | Keep devices near each other longer |
| Report does not remove message | Missing origin metadata | Test with message that has non-empty origin |
| Saved list empty | Message not saved | Save from detail and reopen Saved screen |