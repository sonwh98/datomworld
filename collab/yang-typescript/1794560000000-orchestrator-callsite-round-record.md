Created-Local: 2026-11-14 01:1x +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: not-applicable (interactive seat)

# Round record: ts callsite-provenance adoption — :yang/call-site v1 on production paths

Round: ts callsite adoption (attribution-as-query, Addendum A v3; design astra
`1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md`; equality-gate
ruling adopted per `1795010000000`; brief delegated to this seat per the steward's gate-closure
sync `1794481000000`; ordering relaxed by owner word — relay `1794514249000-orchestrator-tsphp-ordering-relaxed-ts-dispatched.md`).
Branch `callsite-ts-adoption`, base master `b134207f`, four commits, tip `5fd6e20d`:
- `86967ee7` feat: the adoption (emission at every production finalization point + 727-line test ns)
- `603d2d4e` fix: first-execution repairs (linked-prelude golden reference; design-aligned stage-envelope expectation, sol-citation-verified)
- `a231d7bd` fix: strengthened-pin repairs (set-inclusion direction; root keyword decomposition)
- `5fd6e20d` test: frozen reviewed root at the sol-APPROVED literal (Node/Dart must meet it)

Lineage (disclosed): implementer qwen3.8-max headless — **write-only lane** (no shell; zero
execution; routing-status entry filed); ALL execution, commits, and repairs beyond the two
delegate fix rounds were executed by this seat. Delegate authored blind; first execution
surfaced one compile error + two semantic self-conflicts (all repaired: 603d2d4e delegate +
a231d7bd/5fd6e20d seat, lineage cited in-file). Brief citation defects (2) caught BY the
delegate and verified by the seat.

## Execution evidence (all seat-run; delegate ran nothing by lane capability)

| Battery (laptop, focused) | Result |
| --- | --- |
| `yang.typescript-callsite-test` (final tip) | 14 tests / 279 assertions, **0F/0E** |
| exception regression REG6 | 6/48, **0F/0E** (landed round intact) |
| pins/manifest/module | 18/90, **0F/0E** |
| lower + lower-portable goldens | 28/777, **0F/0E** |
| source-call | 2/22, **0F/0E** |

## Remote gates (shiva/jupiter via rverify, SHA-pinned head, overlay-inspected)

| Gate | Host | Result |
| --- | --- | --- |
| G1 JVM slow (`bb test:sub:clj yang.typescript --slow`) | shiva | **rc=0**, 15615s |
| G1 Node slow (`bb test:sub:cljs yang.typescript --slow`) | shiva | **rc=0**, 43838s, 96 tests / 3017 assertions, 0F (first pass crashed WITH NO VERDICT mid-run — disclosed; re-run on warm build green) |
| Dart branch full (pre-VM-fix era) | jupiter | 94 tests, 50 F = master's own baseline (80/50) — attributed to the cljd forged-ref VM bug per master-green memo `1794536000000`; branch adds 14 dart tests all passing |
| Dart post-fix merged (callsite + prelude-parity + float-address) | jupiter | **All tests passed** (28) at master `a3f7ab8d` merged into branch — parity/float legs cleared by the VM fix as the memo predicted |
| Equality gate (frozen root `:segment/blake3-0233…`, sol-approved) | JVM / Node / Dart | **agree — tri-host coordinate identity measured** |

## Architect trail (independence: Qwen author → GPT reviewers)

- Step-7: sol gpt-6.1-sol, fresh thread `01a1d205` — **FIX-LIST**: P2 coordinate-pin independence
  + P2 hooks closed-set audit (both delivered) + **P1 INHERITED shared-encoder gap**
  (`encoder.cljc:115/:158` — derived-envelope re-projection silently drops C1 rows; OUTSIDE this
  diff; relayed for owner assignment: `collab/exception-track/1794529200000`; sol verified the
  escalation).
- Confirmation r2 (resumed thread): **all fix-list logic CLOSED** ("the strengthened assertions
  close both P2 test gaps… the TS-SP-C1 test also pins the documented row drop without claiming
  a core fix"), two verbatim closures executed by seat (root freeze at the approved literal;
  findings contradictions amended), test re-run green. **No new production defect found.**

## Named obligations and cross-track items

1. **TS-SP-C1** (sol ruling on judgement call (c)): derived-envelope C1 coverage is PARTIAL by
   named obligation; documenting test pins the drop and FLIPS when the shared encoder is fixed.
2. **Admission-freshness audit** (design :139): freshness-vs-retry audit remains open; negative
   fixture landed.
3. **Encoder P1**: owner assignment pending with the steward (crosslang owns the provenance VM
   machinery); the documenting test is the flip-site.
4. Shared-validator cleanup (sol (d)): lift the language-neutral validator into `yang.core` —
   follow-up round, not required here.

## For the php sibling round

The mirror source is now review-validated: `test/yang/typescript_callsite_test.cljc` (literal
paths + distinct-admission negative + hooks closed-set audit + renamed-wrapper negative + the
equality gate). Dynamic-case fixtures, same idiom. Cross-cite both diffs at sol step-7 per the
sibling relay `1794514249000-orchestrator-tsphp-ordering-relaxed-ts-dispatched.md`.

## Disposition

Merge-ready on: sol sign-off chain (r1 logic + r2 closures), complete tri-host G1 with the
equality gate measured on all three hosts, regressions green, disclosures complete.

— yang.typescript orchestrator seat
