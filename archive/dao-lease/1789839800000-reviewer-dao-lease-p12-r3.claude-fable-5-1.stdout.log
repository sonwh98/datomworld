Completed-GMT: 2026-09-19 17:07:54 GMT
Completed-Local: 2026-09-20 00:07:54 +07

# r3 confirmation: N1–N5

**Not ready to commit: one blocker remains, R1.** N1–N4 are confirmed fixed. N5 is fixed as prescribed, but the fix silently swallows a missing resolver (R1 below). I edited nothing and did not rerun suites.

| Finding | Disposition | Evidence | Remaining action |
|---|---|---|---|
| N1 magnitude bound | **Fixed** | `magnitude-limit` is 2⁵² (`lease.cljc:39-50`); the per-unit check uses division, so it cannot overflow (`base-over-bound`, 261-274, applied 290-292). Products stay ≤ 2⁵² and sums ≤ 2⁵³, exact on all three hosts. A defective tick on a tick cursor is now counted (807-808). Tests at 1662-1727. | Optional, see R3. |
| N2 proposal-id collision | **Fixed** | Answers are keyed `[proposer pid]` (755-758, 899-905). The proposer is the grant's holder on the drained, queued and hook paths (1043-1045, 1216, 1240-1242), and the hook names it for refusals. Two-holder test at 1728-1765 shows 0 dropped and both answers delivered. Repeat protection does not cover a refusal that was only drained, never delivered; that limit is documented (957-962). | None. |
| N3 grant registers a medium | **Fixed** | The `:accepted` branch returns `nil` for `counted` (948-954); only a holder's renewal or release registers the lease. Test at 1766. | None. |
| N4 tolerance not validated | **Fixed** | `initial-judge` checks `tolerance?` and that the unit is in the table (655-661). Test at 1795. | Optional, see R3. |
| N5 silent permanent abort | **Fixed, but introduces R1** | A throw while processing a value drops it, advances the cursor and continues (1087-1100). A throw from the read aborts with `:abort-error` (884-888, 1145-1148). Catches take `Exception` under `:clj`. Test at 1807. | R1 (required); R2 and R4 are minor. |

## Remaining defects

**R1 (P2, regression from the N5 fix; fix before commit) | `lease.cljc:785-794, 1088-1093` | A missing resolver is now swallowed silently.**
- `resolve-author` throws when `:resolver` is nil, and that throw happens inside the new per-element `try`.
- A judge assembled without a resolver now drops every lease fact as `:element-threw`. `initial-judge` does not check for one, and its docstring defers that check to a constructor that does not exist yet.
- No renewal is ever counted, so every lease falsely lapses for `:silence`. The only signal is a rising `:dropped` count. Before r3 the same mistake threw on the first fact.
- Remaining action:
  - In `initial-judge`, or at the top of `judge-step`, outside the `try`, throw when `:resolver` is not a function.
  - Add a test that a judge with no resolver throws and does not lapse its leases.

**R2 (P3) | `lease.cljc:1434` | `:abort-error` is never cleared.**
- `judge-step` resets `:abort` but leaves `:abort-error`, so an old message persists into later healthy passes. The key is also missing from the `initial-judge` map.
- Remaining action: `(assoc judge :abort nil :abort-error nil)`.

**R3 (P3) | `lease.cljc:655-661` | Tolerance gets the raw bound but not the per-unit bound.**
- A tolerance of `{:h 4503599627370496}` passes assembly, then overflows a long in `add-duration` mid-pass on the JVM.
- The value comes from trusted config, so this is low priority.
- Remaining action: apply the same `quot` check to the tolerance at assembly.

**R4 (P3) | `lease.cljc:838, 873-875` | The tick path does not match its docstring.**
- The `drain-ticks` docstring says a throw while processing a value is handled one level down by dropping it. `apply-tick-value` has no `try`.
- Nothing in that path can throw now that readings are bounded, so either correct the docstring or wrap the call for symmetry.
- The rest of `dao.stream` writes its catches with `:cljd` first (`#?(:cljd Object :clj … :cljs :default)`, as in `serving.cljc:42`). The new catches put `:clj` first. That is harmless in a function body, but it should follow the house order.

**R5 (P3, design note; no change needed for this commit) | A renewal dropped because the resolver threw still counts against the holder.**
- A transient resolver failure on a genuine renewal can therefore become a false lapse.
- Phase 4 should consider suppressing `:silence` for that medium in that pass, the same way a truncated drain does.
