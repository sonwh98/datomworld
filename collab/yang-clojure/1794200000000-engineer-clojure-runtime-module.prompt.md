Created-GMT: 2026-11-03 17:05:00 GMT
Created-Local: 2026-11-04 00:05:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3); muse subscription exhausted until 2026-11-09
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: publish yang.clojure as a runtime module — join the cross-language conformance matrix

Role: compiler engineer (glm). NOTE: a kimi code-quality review follows before landing — write for review.
Reviewer/landing: the ZCode glm orchestrator seat; the kimi quality gate follows; the architect rules on any §8.10 deviation.

## Governing instructions

Read in order:
1. `collab/yang-call/1794370000000-orchestrator-to-yangclojure-runtime-module-skip.md` — the peer orchestrator's relay naming this exact deliverable.
2. `test/yang/call/conformance.clj` — the shared helper; the three existing frontend profiles (:typescript, :php, :python) are the shape to mirror.
3. `docs/design/yang.antlr.md` §8.10 — the convention; §8.10.11's gate (no call-node surgery, no foreign `:code` extraction, no per-language wrappers) is enforced by construction in the helper.
4. The donor-execution reference patterns: `test/yang/typescript/antlr/source_call_test.cljc` and the real-lowered tests in `test/yang/php/antlr/e2e_test.clj`.
5. `src/cljc/yang/core/call.cljc` and the existing published-module machinery: `grep -rn 'module-spec' src/cljc/yang/` shows how js/php/py publish.

Work in `/Users/sto/workspace/datomworld-cljrt`, branch `yang-clojure-runtime-module`, base `7eb7fc9e` (master). You HAVE shell (verified: headless muse with --approval-mode never runs commands truthfully) — use it to iterate: compile, run the focused tests, re-check. Commit on the branch; push; do NOT merge.

## The deliverable (the relay's minimal list)

1. **A published yang.clojure runtime module** — the module-spec with the convention's call-address, mirroring how js/php/py publish their runtimes (`:address` in the profiles reads `@h/published` etc.). The D2-era machinery on the reference branch (hardening3-h3-1) shows the descriptor/admission approach — but BUILD ON MASTER's compiler (the D2 arc is reference-only and NOT part of this round; do not merge or cherry-pick it).
2. **A `clj/run-main` entry lowering** — mirroring `js/run-main` / `php/run-main`: takes the lowered unit, runs it, yields the outcome.
3. **A task-local runtime global namespace** — `clj/define-global!` and `clj/global-get` (mirroring the js/php primitives) so the helper's export projection can read named exports.
4. **The `:clojure` profile in `test/yang/call/conformance.clj`** — every key filled: :runtime 'clj, :address, :refusal [:yang.clojure/refusal ...] (match the compiler's actual refusal shape), :parse (clojure source reads directly — the reader IS the parser), :unit, :run-main 'clj/run-main, :read-export, :project, :render.
5. **The matrix legs**: `yang.clojure <-> typescript`, both directions, as ~2 test functions against the existing helper (the relay says these are the only new tests needed).

## §8.10 conformance (binding)

- Donor programs run UNMODIFIED under their own entry — no call-node surgery, no foreign `:code` extraction, no per-language wrappers.
- The published module follows the convention's call-address and admission rules (the adoption's own admission tests on master are the reference).
- The runtime namespace is task-local; define-global!/global-get are real primitives in the clojure runtime's published module, not host-side mutations of the test harness.

## Verification (real output in your report)

1. `bb test:sub:clj yang.base` — zero new failures vs master (122+ green at last landing; the gen:antlr prerequisite may need `bb gen:antlr` first in a fresh worktree).
2. The new matrix legs: both clojure↔typescript directions green.
3. The EXISTING 8 matrix tests stay green.
4. clj-kondo on every touched file: 0 errors.
5. The admission/refusal tests on master stay green (the runtime module must not weaken the convention).

## Headless notes

- Shell works (verified). The sandbox is on; stay inside the worktree.
- If `bb gen:antlr` is needed, run it before the suites.
- Do not touch `src/cljc/yin/vm/**`, `src/cljc/yang/core/call.cljc`, the evidence files, or the narrative records. `yang.clojure.cljc` changes are expected (the entry lowering + publish), as are the new module files.
- A kimi code-quality review follows before landing; write for review.

## Report

Findings + exact diff + verification outputs. Commit on the branch; push; do not merge.
