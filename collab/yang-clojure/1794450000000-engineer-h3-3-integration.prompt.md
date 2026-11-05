Created-GMT: 2026-11-04 19:05:00 GMT
Created-Local: 2026-11-05 02:05:00 Asia/Ho_Chi_Minh
Coding-Agent: muse (muse-wonder-1.0, API lane, fresh session)
Session-ID: not-applicable (orchestrator-dispatched)
# Task: H3-3 harness integration — the 20-arity family joins the kernel-gate corpus

Role: harness engineer (muse). Reviewer/landing: the orchestrator seat + a fresh codex gate. Suite-scale runs are REMOTE (the seat does them on jupiter); individual focused tests are fine on this laptop.

## Context

Worktree /Users/sto/workspace/datomworld-h32, branch h3-3-prep at 004246b4 (master merged in — the landed cljrt ruling + h3-2 are in). The family DESIGN is done and reviewed: test/yang/latte_oracle/h3_3_fixture_family.md (dual accounting: §7 designated routes 16+3+1; §2 the precedence rule; §5.1 the refusal audit) + h3_3_a_verify.clj (43/43 PASS, seat-verified). The codex design reviews accepted the design with corrections applied (00573c52).

## The deliverable

Integrate the family into the kernel-gate corpus EXACTLY per the family doc's §7 designated routes and fixture definitions:
1. The fixtures (F2/F3 completion shapes + the refusal companions R1-R10 per the doc) into test/yang/latte_oracle/kernel_gate.edn as named integrations / direct rows, matching the doc's calls verbatim.
2. slice9_declarations.edn: version 3 at provenance = the current master revision (004246b4); the historical declarations carried byte-identical; the validator wiring intact.
3. slice9_evidence.edn refreshed via the real writer.
4. The `registered-definition?/1` treatment per the doc's §8: HONEST NON-CREDITING default (the gate stays :blocked on that one arity) — NO trace-table mapping (the re-review withdrew the unsafe +1). If you find a safe treatment is trivially available, propose it in the report instead of implementing — the gate decides.
5. The credit-rule decision (doc §2's accept-vs-tighten): IMPLEMENT THE ACCEPT side — negative rows that pass and trace the head credit normally (the harness is untouched); the doc's integration-notes section already presents the tighten option for a future ruling. Do NOT modify kernel-exercise-entries.

## The expected outcome (from the design's dual accounting)

Predicted ACTUAL classification: 17 :direct + 2 :named-integration + 1 :unexercised (registered-definition?/1) — the gate's unexercised count drops 20 -> 1, and the gate REMAINS :blocked (honestly) on that single arity. The evidence counts should show the new fixtures passing (109 -> ~109+N where N = the family's passing rows per the doc) with blocked 0. Report the exact before/after counts.

## Boundaries

- test/yang/** only (corpus, declarations, evidence, latte_oracle_test.clj registration). NO src/cljc/** (the VM/compiler are freshly landed and gated — do not touch). The cell-ref routing, D2 machinery, and kernel-attempt/evidence-data stay untouched.
- The family doc and A-verify script are REVIEWED artifacts — do not modify them; integrate what they specify. If integration reality contradicts a doc prediction, STOP that fixture, record it honestly in the report (do not force it), and let the gate adjudicate.

## Verification

- Local (individual): the focused gate deftest + the new H3-3 deftest (the H3-2 pattern), kondo. Real outputs.
- Remote (the seat runs on jupiter): full yang.latte-oracle-test namespace + the before/after counts + the unexercised-1 confirmation.
- Commit on the branch; push; do NOT merge.
