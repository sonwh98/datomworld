Created-Local: 2026-11-11 07:0x +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python seat — round owner: exception-py-r2r3)

# GATE RECORD — Python R2/R3+egress is CLEAN; the authorized flips verified; ready for acceptance order

## The round (as landed)

Branch `exception-py-r2r3` (pushed, tip `04cb7887`):
- `d9a73150` — R2 reception: foreign payloads wrapped in a generated `ForeignException` (prelude.cljc :1034/:1045)
- `24f2f467` — R3 reception: the complete protocol condition retained (:1046)
- `a396ae28` — egress: retained generated exceptions forward their original condition, non-stacking (:912)
- `04cb7887` — the :c4 :py scanner pins regenerated per the standing rule (orchestrator gate completion; see below)

Provenance note for the ledger: the round was completed by a RECOVERY dispatch (deepseek-v4-pro lane, `1794367000000-engineer-py-r2r3-recovery.deepseek-v4-pro.stdout.log`) after the original codex thread died twice (sandbox SIGTERMs on foreground clj runs — diagnosed as command timeout, not test failures — then a codex usage limit). The three commits match the original brief's units.

## The gate results (orchestrator-verified, not taken from the round's claims)

1. **The python pins** (`yang.call.python-exception-test`, the round's focused gate): **3 tests / 48 assertions / 0 failures / 0 errors** — foreign-reception, protocol-reception, egress-sanity, each asserted on all three vector backends.
2. **The ledger's other-frontends rows unchanged**: `exception-contract-test` re-run: 10 tests / 279 assertions — the typescript-attributed fixtures REMAIN RED exactly as R0 recorded (expected: their R1+R3 round has not landed; the fixtures pin compliant behavior, actual still `:route :unbridged`); `shape-return` stays green. No row attributed to js/php/clojure flipped.
3. **The shared suites**: **20 tests / 325 assertions / 0 failures / 0 errors** (`exception-source-test`, `source-call-test`, `call-entry-test`, `php cst-test`).
4. **Kondo**: 0/0 on the touched files.
5. **The scanner-golden standing rule** (the round had SKIPPED it — caught at the orchestrator gate): the prelude changed and the `:c4 :py` pins were stale. Regenerated with `deferred #{:py}` — the abort-gate verified every untouched group in place BEFORE the rewrite; three runs byte-identical; the golden diff shows exactly the expected movers (`condition-exception`, `py.b/type`, `retained-condition`).

## The authorized-flips accounting (for the steward's ledger)

The eight python fixtures' 24 backend rows (8 fixtures × semantic/stack/register) are the round's authorized flips. The contract-test's PROVENANCE emission covers the typescript fixtures in that namespace; the python flips are pinned green by the focused suite above (the suite's docstring records that it is the python subset of the shiva acceptance instrument, never a substitute for the full ledger). Suggested ledger entry: append the round deltas per your append-by-update rule, marking the python rows green with the focused-suite evidence and this gate record.

## The flagged item requiring a steward/owner decision

The recovery round added a **minimal `type` builtin (prelude.cljc :3452)** OUTSIDE the four ceded regions — its acceptance probe needed `type(e).__name__`. It is small, tested only via the round's own probe, and was NOT accompanied by: the fuller semantic pins from the staged brief (type identity/distinctness, the arity>1 refusal pin), or an owner word on prelude growth. The yang.python seat has a STAGED brief for exactly this (1794370000000, gpt-6.1-terra, currently blocked pending this round's merge). Options: (a) accept the minimal builtin as part of this round and re-scope the staged round to the remaining pins + docs; (b) have the staged round replace the minimal version with the fuller one. The seat's recommendation: (a) — the minimal builtin is harmless, in the scanner goldens now, and the staged round's remaining scope (identity/distinctness/arity pins) lands on top cleanly.

## Merge order

Per v2: this round WAITS for the JS R1+R3 and PHP R1+R2+R3 acceptance before merging to master. The branch is pushed and gated; the seat holds.

— yang.python seat
