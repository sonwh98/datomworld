Created-GMT: 2026-11-12 23:02:35 GMT
Created-Local: 2026-11-13 06:02:35 +0700
Coding-Agent: glm
Session-ID: 633eb09f-30b6-41f0-a5e9-fec49dcdce00

# Task: Post-port oracle-binding re-verification (the committed obligation, note 1794396000000)

Role: VM Engineer (compiler provenance)

Implementers:
- Model: glm-5.3 | Assigned: 2026-11-13 21:2x +0700 | Status: active | Rationale: probe-verified live on the laptop; POOL 1 (codex) exhausted and deepseek credits low per the board; glm carried cc3 r9/r10 on this very machinery — continuity where it matters. (If a code-fix round follows from this verification, it needs a NON-GLM reviewer for family independence.)
- Status-Event: 2026-11-13 06:0x +0700 | Model: TBD-ON-DISPATCH | Status: superseded | Rationale: draft placeholder; dispatch fired on the steward's verified merge notice (port `309fe842` + kit `ba3cc407` on origin/master)

**DISPATCH GATE SATISFIED: merge independently verified from `git log origin/master`; header filled; dispatching.**

## Where you work

Worktree: `/Users/sto/workspace/datomworld-postport`, branch `post-port-oracle-reverify`, created from then-current `origin/master` @ `ba3cc407` (post-port, post-kit). Work ONLY there. This is a VERIFICATION round: the expected result is "everything green, no changes" — any code you find yourself wanting to change is a DEFECT REPORT, not a fix. Commit NOTHING.

## The obligation (your committed round)

cc3 landed the oracle site→C1 occurrence-key binding on the PRE-port shape of `yang/clojure.cljc` (outcome channel). The port has now converged yang.clojure's exception lowering onto the continuation encoding (the owner's directive; Phases 1-4, `8e1dab6d`→`35d09aaa`: continuation family in the clj prelude, consumer wiring, outcome machinery retired net −418 lines, coverage preservation restored, test updates). The port's design preserved the **intra-unit private tail lane** (deepseek's remedy: direct intra-unit calls stay direct tail application; only published/cross-unit entries take a bridge frame) — and that preserved tail lane is exactly the shape cc3's binding targets. The steward's depth measurements cleared the thresholds (155/155 overlap, max ratio 1.20×, max depth 28 frames; `test/yang/latte_oracle/depth_comparison.md`), so budget work is not at risk. This round proves the **binding** still holds.

## The verification checklist (each = a recorded outcome: PASS / DEFECT + evidence)

1. **The binding test**: `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-cc3-oracle-site-c1-binding` — green at the post-port shape. If red: capture the divergence (which site, which key) — that is the bijection-breaking evidence the escalation path needs.
2. **The full cc3 battery**: `clojure -M:test -n yang.clojure-callsite-test` (43+ deftests incl. the r8-r11 forgery/observability/survival families) — green. Also `yin.repl.index-test`, `yin.repl.query-test` (the r7 net).
3. **The bijection under the new lowering**: the oracle-site→selected-compiled-occurrence bijection must still hold AFTER the port's lowering changed which applications are direct tail vs bridged. The concrete check: the binding test's per-lane K sets equal, AND independently recompute the expected occurrence set from the post-port compiled trees (the cc3 verifier machinery in `latte_oracle_test.clj` does this) — a site whose compiled shape moved (e.g. a tail call now emits no bridge frame) must still resolve to its recorded K or the binding must have been re-derived in the port's Phase 4 — say which.
4. **Provenance side-artifact integrity**: the published C1 side tables and provenance relation are byte-comparable to their pre-port expectations where the source is unchanged (the port retuned EXCEPTION paths; ordinary compiled applications' provenance must not shift). Spot-check the pinned cohort digests from cc4's planned matrix if present, else recompute and record.
5. **Canonical invariance**: zero canonical code bytes/addresses changed by the port for non-exception sources (the port's changes are in the encoding; the CC1/canonical-row contract is untouched). `clojure -M:test -n yang.clojure-callsite-test -v .../canonical-invariance-test` explicitly.
6. **The kit contract test** on shiva (this namespace does not complete on the laptop — three measured non-completions; route to shiva per the gate ladder): `yang.call.exception-contract-test` at the post-port tip — this is the port's own acceptance instrument, re-run once to confirm the merge didn't regress it (expected: the port's green kit state per their review, NOT the pre-port 87F/21E baseline).

## Escalation rule (your recorded commitment, 1794396000000 item 3)

If ANY item above shows the bijection broken (sites resolving to wrong/missing keys after the port's tail-lane shape), do NOT fix the binding or the port: record the evidence (which fixture, which site, pre/post compiled trees, both C lanes) and file a relay to the steward (`collab/exception-track/`), then to the owner if they don't resolve it — the escalation path both seats committed to. If item 3 passes but reveals the binding *had* to be re-derived by the port (a legitimate rebind, not a break), record that as PASS-with-note and list what changed so D1's evidence import knows which revision to pin.

## Environment facts (orchestrator-established)

- ANTLR prerequisites: the orchestrator is running `bb gen:antlr` in your worktree at dispatch time; if it has not landed by your first run (check `build/antlr/classes/yang/php/antlr/gen/PhpLexer.class`), fix the environment yourself: `export PATH="$HOME/.local/share/mise/shims:$PATH"`, `mise trust` (this worktree's mise.toml starts untrusted — the fresh-worktree gap), `eval "$(mise env -s bash)"`, then `bb gen:antlr`. The reference's "bb not on PATH" means non-login shells omit the mise shims dir; `bb deps:npm` the same way if the node-side leg needs it.
- The main checkout's collab path may be outside your writable roots — use the worktree mirror + say-so convention for the deliverable, as cc2/cc3 did.
- For item 6 (kit on shiva): ssh `shiva.stigmergy.systems`; a scratch clone may exist at `~/ci/datomworld-cc3` etc. — if you create/reuse one, verify `git remote get-url origin` is the GitHub URL, fetch, checkout `origin/master`, merge-base-verify, activate mise (`export PATH="$HOME/.local/bin:/snap/bin:$PATH"`, `cd`, `mise trust`, `eval "$(mise env -s bash)"`), `bb deps:npm` before the cljs side. Kill only exact PIDs after `readlink /proc/<pid>/cwd`.

## Verification commands

All G0 laptop scope except item 6 (shiva, individual namespace, exact-PID discipline). kondo on any file the port's merge touched that this round reads (no edits — lint only as a health check).

## Deliverable

Findings per protocol: the six checklist items with recorded outcomes and exact counts; the escalation decision (or the PASS-with-note rebind description); any DEFECT's evidence bundle. Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
