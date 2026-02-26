# EchoDrop — Demo Script

A step-by-step walkthrough for a 10-minute live demo of EchoDrop's offline mesh messaging.

---

## Prerequisites

| Item | Detail |
|------|--------|
| Devices | 2 Android phones, API 24+ |
| Connectivity | Turn **off** Wi-Fi and cellular data on both |
| Bluetooth | Enabled on both |
| App | EchoDrop (debug or release APK) installed on both |

---

## Act 1 — First Launch (2 min)

### Phone A

1. Open EchoDrop → **Onboarding** screen appears.
2. Tap **"Get Started"** → Permission screen.
3. Grant **Nearby Devices** and **Location** permissions.
4. Optionally grant **Notifications**.
5. Tap **"Allow Permissions"** → Home Inbox screen.
6. Notice: Empty inbox with *"No messages nearby"*.

### Phone B

Repeat steps 1–6.

> **Talking point:** No sign-up, no email, no phone number. Zero cloud dependency.

---

## Act 2 — Post a Message (2 min)

### Phone A

1. Tap the **blue FAB** (bottom-right) → Post Composer opens.
2. Type: `Free coffee in Building 2 lobby`.
3. Under **"Who sees this?"** leave **"Nearby"** selected.
4. Under **"Expires in"** pick **"1 hour"**.
5. Tap **"Post"** → Snackbar confirms message saved.
6. The message appears in Phone A's inbox.

> **Talking point:** The message is stored locally. It will spread to nearby phones via BLE + Wi-Fi Direct.

---

## Act 3 — Mesh Propagation (2 min)

### Both Phones

1. Place phones within ~10 metres of each other.
2. Wait 15–30 seconds. The sync indicator (top-right, near search bar) changes from *"No devices nearby"* to *"1 device nearby"* with a pulsing green dot.
3. **Phone B's inbox** now shows the *"Free coffee…"* message.

> **Talking point:** No internet involved. BLE discovered the peer, Wi-Fi Direct formed an ad-hoc link, and the message was transmitted using the EchoDrop DTN wire protocol (ED08).

---

## Act 4 — Message Detail & TTL (1 min)

### Phone B

1. Tap the message card.
2. Detail screen shows: full text, **"Nearby"** scope badge, TTL progress bar (green = lots of time left), *"Expires in 58m"*.
3. Note the **"Posted"** timestamp and the **"Forwarded 1 time"** label.
4. Tap **"Got it"** → returns to inbox, message removed.

> **Talking point:** TTL counts down in real-time. When it hits zero, the message self-destructs across every phone that carries it.

---

## Act 5 — Urgent Alert (1 min)

### Phone A

1. Open Post Composer again.
2. Type: `⚠️ Fire alarm test in 5 minutes`.
3. Toggle **"Mark as urgent?"** → switch turns red, Post button changes color.
4. Pick **"4 hours"** TTL.
5. Tap **"Post"**.

### Phone B

1. Within seconds, an urgent message arrives with a red **URGENT** badge and red accent border.
2. Tap to see the urgent banner at the top of the detail screen.

> **Talking point:** Urgent messages are forwarded first by the mesh. Useful for emergency comms when cell towers are down.

---

## Act 6 — Private Chat (2 min)

### Phone A

1. Tap the **chat FAB** (bottom-left) → Chat list.
2. Tap **"+"** → Create Chat.
3. Name it `Demo Group`.
4. A chat code is generated (e.g. `AB12CD34`).
5. Tap **"Copy"** to copy the code.

### Phone B

1. Navigate to Chats tab → Tap **"+"** → "Join Chat".
2. Enter the code from Phone A.
3. Both phones are now in the same private chat.

### Both Phones

1. **Phone A** types: `Can you see this?` → Send.
2. **Phone B** receives the message within moments.
3. **Phone B** replies: `Loud and clear!`
4. Both phones show the conversation with sent/received bubbles.

> **Talking point:** Chat messages are encrypted with the shared chat code, wrapped as DTN bundles, and synced over the same BLE→Wi-Fi Direct mesh. No server ever sees the content.

---

## Wrap-Up (30 sec)

- Show the **Settings** screen (gear icon): background sharing toggle, battery optimization guide.
- Show **light mode** (or toggle to dark mode if starting in light).
- Mention: all data is local, expires automatically, nothing stored online.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Sync stays at "No devices nearby" | Ensure BT is on, permissions granted, phones within 10m |
| Message doesn't appear on Phone B | Wait 30s for BLE scan cycle (10s on / 20s off) |
| App killed in background | Follow battery optimization guide in Settings |
| Chat code rejected | Verify uppercase, 8 characters, no spaces |
