Created-Local: 2026-11-15 13:1x +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: not-applicable (interactive seat)

# Round record: T2 bare-global-write refusal — ReferenceError naming the variable

Branch `t2-bare-global-write`, base origin/master (Nov 14 morning), tip `5fd6e20d`-lineage
commits: `50d31bc9` (the fix + RED pins) + `4fb3eff7` (fix2 per sol's step-7 verdict +
re-derived float-address goldens). Pushed; sol SIGN-OFF chain: r1 (logic — my seat-authored
swap was ruled WRONG on ES2022 module semantics) → fix2 by the qwen session per sol's
corrected contract → r2 confirmation **SIGN-OFF** (all items CLOSED, address goldens
independently re-derived matching, no new findings; Node/Dart noted downstream).

## The defect (design-backed)

`assign-name`'s non-cell branch threw an unconditional `TypeError "Cannot assign to read
only property 'nm' of object"` for binding-less names. Design §3.1.4 (strict always on,
implicit globals refused) + ES2022 module semantics require a **ReferenceError naming the
variable**. The kept `js/global-get` read is ES-correct for truly-absent names. §3.7
script-goal declared-global writes are the separate follow-up slice (recorded for the
owner's priority call) — sol ruled §3.7 is declaration placement, not a module-goal write
restriction, and REQUIRED the refusal for the binding-less case.

## Execution evidence (seat-run)

- Pins (portable tri-backend): undeclared → catchable ReferenceError GREEN (correct
  pre-fix via the LHS read); declared-globalThis-property assignment → RED ×3 pre-fix,
  GREEN post-fix; `undefined = 7` → TypeError preserved.
- Regressions at fix2: REG6 6/48, pins/manifest/module 18/90, lower suites 28/777,
  source-call 2/22 — all 0F/0E.
- Float-address goldens re-derived post-fix2 (prelude content moved with the helper):
  JVM-measured, split corrected after a 33/31 off-by-one in my first re-derivation.

## Remote gates (rverify, SHA-pinned, overlay-clean)

| Gate | Host | Result |
| --- | --- | --- |
| G1 JVM slow (`yang.typescript --slow`) | shiva | **rc=0**, 24275s |
| G1 Node slow | shiva | **rc=0**, 39270s — 100 tests / 3033 assertions, 0F (first pass died silently mid-run with NO verdict after 7.8h concurrent-compile — disclosed; warm-build re-run green) |
| G1 Dart slow | jupiter | **compile-blocked ON MASTER** (`source-call-test`, the routed cc3-dart-gate shape, yang.clojure-owned; fix brief on file) — NOT branch-attributable; the branch's own dart run compiled and ran 94 tests (50 F = the disclosed cross-track VM-bug baseline per master-green memo `1794536000000`, now FIXED at `a3f7ab8d` — post-fix dart confirmation of the callsite ns measured green on the merged tree separately) |

## Named obligations

1. **§3.7 script-goal declared-global writes** — separate feature slice; owner priority call.
2. **Encoder P1 (inherited)** — from the callsite round's review, owner assignment with the
   steward (`1794529200000`); documenting test pinned in the callsite round's suite.
3. **Admission-freshness audit** (design :139) — negative fixture landed; audit open.

## Disposition

Merge-ready: sol SIGN-OFF + tri-host G1 (dart disclosed as master-owned block, with the
branch's own measured dart evidence + post-fix confirmation). The seat-authored-first-fix
mislabel and its correction are part of the lineage (sol r2 verified the corrected
contract); the qwen session's authorship and the seat's execution role are disclosed.

— yang.typescript orchestrator seat
