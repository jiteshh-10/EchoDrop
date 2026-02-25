# EchoDrop — Design Decisions Log

> Architectural and implementation decisions documented per iteration.

---

## Decision: Priority sort in DAO, not adapter — iter-3
Context: Messages need to appear ordered by priority (ALERT > NORMAL > BULK).
Decided: `ORDER BY CASE priority` clause in the DAO query is the single source of ordering truth.
Why: Keeps ordering logic in one place; adapter never manually reorders, reducing bugs and complexity.
Impact: Any future sort changes happen in one SQL query, not scattered across UI code.

## Decision: BULK not user-selectable in MVP — iter-3
Context: Spec defines three priority tiers but BULK is reserved for system/forwarded messages.
Decided: Post Composer only exposes Normal and Urgent toggles; BULK is not available to users.
Why: Prevents user confusion; BULK semantics only make sense when multi-hop forwarding exists (Iteration 6+).
Impact: Enum has three values but UI surfaces two; future iterations can expose BULK when relay logic is built.

## Decision: Reactive alert count via LiveData — iter-3
Context: Alerts tab badge needs to update when ALERT messages are inserted or expire.
Decided: New DAO query `getAlertCount(now)` returns `LiveData<Integer>`, observed in HomeInboxFragment.
Why: Avoids manual counting in `applyFilters()` loop; Room invalidation tracker updates automatically.
Impact: Badge is always accurate; no need to recount on every filter pass.

## Decision: Priority immutable after creation — iter-3
Context: Should users be able to change a message's priority after posting?
Decided: No. Priority is set at creation time and cannot be changed via UI.
Why: DTN bundles have fixed headers; changing priority after propagation would create inconsistency across devices.
Impact: No edit UI needed for priority; simplifies data model and forwarding logic.

## Decision: Urgent banner animation in detail screen — iter-3
Context: Spec calls for a banner below AppBar for URGENT messages with entrance animation.
Decided: `LinearLayout` with `bg_urgent_banner` drawable, fade in + slide down 4dp over 180ms.
Why: Matches spec exactly (180ms, FastOutSlowIn); provides visual urgency without being distracting.
Impact: Banner only appears for ALERT messages; NORMAL/BULK see no banner.
