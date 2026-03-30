# EchoDrop - Known Limitations

Current state as of 2026-03-30.

---

## Networking and Transport

| # | Limitation | Impact | Notes |
|---|------------|--------|-------|
| 1 | Proximity-dependent delivery | Messages only move when devices encounter each other | Core DTN behavior, not a defect |
| 2 | OEM Bluetooth and background variance | Discovery cadence differs across manufacturers | Mitigated by battery guidance and diagnostics |
| 3 | Single active peer-transfer constraints | Throughput can vary in dense environments | Subsequent cycles continue propagation |
| 4 | Physical-world relay uncertainty | Sparse movement can delay delivery significantly | TTL and repeated encounters are expected controls |

## Persistence and Storage

| # | Limitation | Impact | Notes |
|---|------------|--------|-------|
| 5 | Destructive DB migration in pre-release | Schema upgrades clear local data | Uses `fallbackToDestructiveMigration` intentionally |
| 6 | Saved state is local to installation | Uninstall/reinstall clears saved and blocked-device state | Privacy-aligned local-first model |
| 7 | No cloud backup/export workflow | User cannot restore message history across devices | Intentional for current privacy posture |

## Moderation and Safety

| # | Limitation | Impact | Notes |
|---|------------|--------|-------|
| 8 | Report is local-only moderation | Block/report state does not federate to other peers | No backend moderation plane by design |
| 9 | Report depends on origin metadata | Messages with missing origin cannot be origin-blocked from detail flow | UI handles this with user feedback |
| 10 | Blocklist is identifier-based only | No reason codes or incident history yet | Candidate for future policy extension |

## UX and Product

| # | Limitation | Impact | Notes |
|---|------------|--------|-------|
| 11 | No sender-side recall after propagation | Posted broadcast cannot be remotely revoked | TTL and local dismiss are current controls |
| 12 | Saved view is message-level only | No folders/tags/search in Saved screen yet | Current scope is fast retrieval |
| 13 | Room identity remains lightweight | No user profile layer for broadcast personas | Privacy-first anonymous model |

## Validation and Testing

| # | Limitation | Impact | Notes |
|---|------------|--------|-------|
| 14 | Most validation is unit-test heavy | Physical transport behavior still needs real-device runs | Unit suite is green; field validation remains required |
| 15 | Connected test coverage is narrow | Instrumented suite is minimal compared with JVM suite | Expansion recommended for transport-heavy scenarios |

---

## Recommended Next Improvements

1. Add non-destructive Room migration path before production release.
2. Add integration tests for Save/Report/Saved workflows.
3. Add optional local moderation audit metadata (reason + timestamp).
4. Expand connected-device matrix for OEM-specific background behavior.