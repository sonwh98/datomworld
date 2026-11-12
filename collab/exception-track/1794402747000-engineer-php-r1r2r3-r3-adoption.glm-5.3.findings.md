Created-GMT: 2026-11-12 05:4x:00 GMT
Created-Local: 2026-11-12 12:4x:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm (glm-5.3)
Session-ID: 6e2967a3-3d6a-4604-b3f0-2a61a74bb22f (r3 adoption; resumes the r2 session whose
r1 predecessor was 1a2f7c30)
Round: PHP r3 — the provenance-primitive ADOPTION turn — branch exception-php-r1r2r3, on the
post-rebase tree (r1 1757aae7, r2 e4f271ff; base master 0e666a3f carrying the primitive
8c10c5b7 and the python adoption 8018005d)
Status: adoption complete, committed, NOT pushed (the orchestrator pushes after review
coordination, per this round's brief)

# Findings — r3: wrapper mint/read onto the VM provenance primitive

## The adoption (one commit on top of e4f271ff)

`src/cljc/yang/php/antlr/prelude.cljc`:

- **Mint** (`php.rt/mint-retained`, prelude.cljc :4598-4633): the generated reception
  wrappers (ForeignException for otherwise-foreign payloads, the documented local Error for
  protocol reasons) are minted through the reserved door `cell/dsg__new-provenance [value
  provenance]` — the wrapper's state cell is allocated WITH the complete original condition
  installed in its private `:provenance` heap sibling, atomically at allocation. The
  construction mirrors the membered branch of `php/make-error` (register-final!, then the
  message property stamped through the ordinary cell write, which preserves the sibling), so
  the wrapper catches, formats and destructs like any local error of its class. There is
  deliberately NO map fallback in the mint: a representation without a cell cannot
  authenticate retention (the ruling; sol verified the old fallback at :299 cannot) — a
  memberless or missing class is refused in place ("Cannot mint a retained <cls>") rather
  than silently minting an unmarkable wrapper. Nil provenance is refused by the door itself;
  the php prelude adds no pre-guard, the same documented behavior split the python adoption's
  step-7 review deferred as a P3 follow-up.
- **Read** (`php.rt/retained-condition`, :4634-4647): the complete original condition of a
  generated wrapper, authenticated by `cell/dsg__provenance [cell]` over the CELL backing the
  wrapper's identity — the provenance of a live marked cell, nil for everything else (an
  unmarked cell, a forged or dead reference, a non-reference, or any value whose
  representation carries no cell). Never fatal, never mutating; the entry bridge and
  `php/receive-condition` consult it unchanged — the php.rt/* INTERFACE is the r2 one, the
  mechanism under it is the primitive (the addendum's "upgraded, not replaced").
- **Retired**: the r2 lexical identity store is gone entirely — `retained-store-install`
  (the ~90-line def), its `php/init!` hook, the pre-init stub trio and their install dance.
  This also closes r2's residual obligations #2 verbatim: no task-lifetime growth of
  completed-call associations (the condition is reachable exactly while the wrapper's cell
  is, and reclamation is the heap's), and no O(n²) linear-walk lookup.
- **Declared doors** (`host-names`): `cell/dsg__new-provenance` and `cell/dsg__provenance`
  are declared host dependencies of the php module, the same shape as the python adoption —
  the free names resolve against the composition's cell module; guest code can neither spell
  nor construct them.
- **Export surface**: every retention name stays under `php.rt/*` (mint-retained,
  retained-condition, raise-foreign, raise-protocol), which `core/exports` never takes — no
  module export, projection or alias can hand a guest the mint (the exported-helper bypass
  class sol's point 5 names; a guest able to call the mint could forge provenance of its own
  choosing). Comments that described the old store were updated to the primitive.

`test/yang/call/php_exception_test.clj`: the NEW `php-provenance-adoption-test` —
host-side, the php twin of the export-surface audit pin the python adoption's step-7 review
asked for. RED-first: the two door-declaration assertions failed against e4f271ff before the
implementation (verified), and pin for the future that the mint helper and every php.rt/*
name stay outside `module-exports`.

## Why the r2 pins carry the behavior spec (TDD)

The r2 pins are mechanism-agnostic identity/forgery assertions; the adoption swaps the
mechanism UNDER them, so their staying green is the spec: `php-copied-wrapper-forgery-test`
(a contents-clone into a fresh cell carries no provenance — exactly the primitive's
clone-carries-none guarantee), `php-mutated-wrapper-forwarding-test` (guest-visible mutation
writes the cell and preserves the sibling — the ORIGINAL condition still forwards),
`php-forged-retention` (a guest-minted Exception has no marked cell), the saved-protocol
fixtures (retain → later forwarding through the marked cell across activations), and
`php-kit-parity-test` (the hard-rule pin: reason classification + condition-identical at the
admitted boundary). All green after the swap, unchanged.

## Gate evidence (all firsthand this round, JVM, laptop lane; contract test NEVER local)

| Gate | Result |
| --- | --- |
| php-provenance-adoption-test (NEW; RED-first) | 1 test / 4 assertions, 0F/0E (pre-implementation: 2 failures, the door declarations) |
| php-kit-parity-test (must stay green) | 1 test / 4 assertions, 0F/0E — `{:reason :yang.call/not-callable :condition-identical true}` ×3 backends |
| php-copied-wrapper-forgery-test | 1 test / 3 assertions, 0F/0E |
| php-mutated-wrapper-forwarding-test | 1 test / 3 assertions, 0F/0E |
| php-retention-store-surface-test | 1 test / 4 assertions, 0F/0E |
| php-fresh-egress-directed-test | 1 test / 3 assertions, 0F/0E |
| php-local-reception-directed-test | 1 test / 3 assertions, 0F/0E |
| php-kit-batch-parity-test | 53 assertions, 9 failures = the SAME 3 lane-blocked fixtures × 3 backends, failure signature BYTE-IDENTICAL to r2 (§3a rows unchanged, as the brief required) — the other 12 fixtures green in the batch path, including foreign-reception / retained-foreign-lifetime / saved-protocol ×3 / forged-retention |
| directed legs (×3 backends each) | [:python :php :typed-catch] (green on retry; the first attempt hit the r2-documented dao.jing lane stall — same leg, same shape as r2), [:python :php :unchanged-rethrow], [:python :php :retained-lifetime], [:clojure :php :catch-all], [:php :typescript :catch-all] — all GREEN |
| yang.php.antlr.exceptions-test | 6 tests / 25 assertions, 0F/0E |
| yang.php.antlr.prelude-test | 33 tests / 373 assertions, 0F/0E |
| yang.php.antlr.class-model-test | 8 tests / 587 assertions, 0F/0E |
| yang.php.antlr.objects-test | 3 tests / 538 assertions, 0F/0E |
| yang.php.antlr.generators-test | 3 tests / 76 assertions, 0F/0E |
| yang.php.antlr.builtin-surfaces-test | 4 tests / 225 assertions, 0F/0E (matches the post-rebase verification baseline) |
| yang.php.antlr.e2e-test (fast set, `-e :slow`) | 21 tests / 1116 assertions, 0F/0E |
| yang.php.antlr.e2e-test/fixtures-on-every-vm-test (:slow) | UNRUN locally — exceeds the laptop foreground budget (unchanged from r2; shiva lane) |
| yang.call.exception-source-test | 3 tests / 113 assertions, 0F/0E |
| yang.call.conformance-test | 3 tests / 18 assertions, 0F/0E |
| clj-kondo (both touched files) | 0 errors, 0 warnings |
| cljstyle (both touched files) | clean |

## Residual gaps / open obligations

1. The nil-provenance behavior split (door refuses; no php pre-guard) — documented here as
   the python adoption's step-7 review did; a shared-core decision if it is ever paid down.
2. r2's §3a instrument/lane conflict (php run-main shutdown vs the kit's composed
   observations) — UNCHANGED, not this round's; the failure signature is byte-identical to
   r2's disclosure. The directed pins keep carrying those assertions green.
3. r2's §3b dao.jing concurrent stall on the laptop lane — still present (one typed-catch
   attempt timed out, green on retry); shiva remains the acceptance lane.
4. r2's other residuals (suspension/raise-exit-lifetime probe; per-call result cell) carry
   over; r2 #2 (store growth + O(n²) lookup) is CLOSED by this adoption.
5. Remote lanes (the orchestrator's): the contract test (shiva-only), `bb test:changed`
   across the three lanes, the 12-directed-pair acceptance matrix, and the Node/Dart
   prelude-test harnesses.

## Process disclosures

- The r3 brief, the r3 addendum and the steward's adoption spec are committed with the round
  (they bind it); stdout logs stay untracked per the r1/r2 convention.
- Diagnosis and lint used temporary scaffolding namespaces under test/ (deleted before
  commit; never committed). No assertions were weakened at any point; the three §3a
  lane-blocked fixtures keep their kit assertions red in the pin namespace exactly as r2
  disclosed them.
