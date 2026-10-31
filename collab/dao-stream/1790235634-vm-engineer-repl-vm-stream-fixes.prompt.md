Created-GMT: 2026-09-24 07:40:34 GMT
Created-Local: 2026-09-24 14:40:34 +0700
Coding-Agent: claude
Session-ID: feb934cf-3962-4aed-b4bc-f7cbc2d1605d

# Task: Fix P1/P2 findings from adversarial review of yin.repl dao.stream boundary

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-24 14:40:34 +0700 | Status: active | Rationale: Complex REPL/stream correctness fix; claude-opus-5-5 is the standard implementation tier

Implement the two P1 fixes and the P2 improvement identified by the
adversarial reviewer (gpt-6-sol, session 01a0d255-1830-7f30-ab85-0840da6aed72)
in the worktree at `/Users/sto/workspace/worktree-yin-repl-stream`
(branch `yin-repl-vm-stream`).

Read first:
- `docs/design/dao.stream.md`
- `docs/design/datom.world.md`
- `src/cljc/yin/repl/core.cljc` (the full file as currently modified)
- `test/yin/repl_test.cljc`
- `test/yin/repl/core_test.cljc`
- `collab/1790234868-reviewer-repl-vm-stream.gpt-6-sol.findings.md`
  (the reviewer's full findings)

## Findings to fix

### P1-A: Gap budget starvation (core.cljc around line 328)

`drain-output` uses a flat budget of 4,096 reads for the entire drain loop.
A `:dao.stream/gap` consumes one read slot. If the ring holds 4,096 prints +
1 result token, the drain reads the gap and 4,095 print elements; the result
token is never reached and `finalize-eval` falsely reports result loss.

Fix: gaps must not consume the useful-item budget. Continue draining past
`:dao.stream/gap` without decrementing `remaining` (or give gaps their own
bounded counter separate from the element counter). The result token must
always be reachable regardless of how many gaps precede it, as long as the
total ring size is within the declared capacity.

### P1-B: Stale result ambiguity (core.cljc around line 595, finalize-eval)

`finalize-eval` takes the newest observed `:repl/result` token without any
round identity. If the caller supplies an output stream that already contains
a result from a prior evaluation and the current `emit-result!` call is
refused (`:dao.stream/full` or `:dao.stream/closed`), the stale result is
silently accepted as the current round's value and recorded in `*1`.

Fix: stamp each result token with a round ID so `finalize-eval` can accept
only the token for the current round. A simple monotonic counter on the REPL
state map suffices. The result map becomes
`{:type :repl/result :round <id> :value v}`; `emit-result!` receives the
current round ID; `drain-output` passes through all result tokens unchanged;
`finalize-eval` filters for the current round before taking `peek results`.
Treat a missing or wrong-round result as loss (same error path as today).

Also: detect and surface `emit-result!` failure (`:dao.stream/full`,
`:dao.stream/closed`, etc.) so the caller knows the result was not written.
Currently `stream/append!` is called but its outcome is discarded.

Test: add a test that supplies a preloaded output stream (containing a
prior-round result) and verifies the correct current-round value is reported.

### P2: Quadratic hashing on incremental de Bruijn loaders

`append-stack-image` and `append-register-image` (core.cljc around lines
88-130) recompute H or R over the entire concatenated image after every
input. With N inputs this is O(N^2) hashing work and O(N) retained code
even when no live closures reference earlier segments.

Fix: use a stable image reference per loaded segment rather than
re-hashing the whole concatenation. The simplest safe approach is to hash
only the new segment and keep the prior hash as a cached value on the VM
state (e.g. `:segment-hash`), combining them only when a combined identity
is actually needed. Alternatively, track the image only as the new segment
(the REPL already constructs a fresh VM on `(reset)`), provided existing
cross-input closure calls still resolve correctly. Document the chosen
approach and its invariant.

## Acceptance criteria

1. `drain-output` does not count gap reads against the element budget; a
   program that emits exactly 4,096 print items followed by a halt still
   produces the correct result via the stream.
2. `finalize-eval` accepts only the current round's result token; a
   preloaded output stream with a prior result does not corrupt `*1`.
3. `emit-result!` surfaces append failure to its caller.
4. `append-stack-image` and `append-register-image` do not recompute H/R
   over the full concatenated image on every incremental load.
5. All new and existing tests pass on JVM (`clj -M:test -n yin.repl-test
   -n yin.repl.core-test`).
6. `clj -M:kondo --lint src/cljc/yin/repl/core.cljc
   test/yin/repl_test.cljc test/yin/repl/core_test.cljc`: 0 errors,
   0 warnings.
7. `cljstyle check src/cljc/yin/repl/core.cljc`: clean.
8. Every modified line: pure ASCII, <= 80 columns.

Work only in:
- `src/cljc/yin/repl/core.cljc`
- `test/yin/repl_test.cljc`
- `test/yin/repl/core_test.cljc`

If a required fix demands changes outside these files, stop and report
before editing. Do not commit. Do not run `bb test:cljs` or `bb test:cljd`
(the orchestrator will run cross-host suites after review).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, and any unresolved concerns.
Do not claim edits or tests that did not occur.
