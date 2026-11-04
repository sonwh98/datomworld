Created-GMT: 2026-11-02 04:05:00 GMT
Created-Local: 2026-11-02 11:05:00 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: resume of 01a199e5-6c7e-7263-821d-f78529f35b7c (architect-yang-h2-reconcile r1)
# Round 2: both r1 blockers fixed — revised diff + library-enabled regression evidence

Requested-by: the ZCode glm orchestrator seat (yang.clojure track). The worktree is unchanged: `/Users/sto/workspace/datomworld-yang-h2` (branch yang-hardening2 @ 0bf8bc4e, two files modified, uncommitted). `git -C /Users/sto/workspace/datomworld-yang-h2 diff` shows the revised change; `/tmp/h2_reconcile_v2.diff` is the same diff.

## Blocker 1 (dispatcher re-entry) — fixed by refusal, not by a private apply path

`invoke-value` no longer applies anything. Its `(data/callable? f) → (apply f args)` and `:else → (apply f args)` branches are deleted; the `:else` branch now throws the convention's own condition shape:

    (throw (yang.call/condition :yang.call/not-callable {:yang.call/callee f}))

So a scalar callee, a projected raw closure, or any shape the collection branches do not claim terminates with `:yang.call/not-callable` — the adoption's refusal, as you required. There is no `apply` inside the invoker, so no re-entry into `compile-guest-call` exists. Your exact probes now measure (walker lane, library-enabled):

- `(let [five 5 v [five]] (five 1))` → `[:yin.k/error #:yang.call{:reason :yang.call/not-callable, :data #:yang.call{:callee 5}}]` — terminal, well inside the budget.
- `(let [raw (yang.call/entry f) v [raw]] (raw {})` → `:yin.k/error`, reason `:yang.call/not-callable` — terminal.
- Correction to my own r1 probe, measured: `(yang.call/entry f)` itself is NOT refused — the linker compiles module fns as admitted descriptors, so `(let [e yang.call/entry v [e]] (e f))` goes through admission and legitimately returns f's entry (`:yin.k/ok`). The raw closure it PROJECTS is what refuses when called.

## Blocker 2 (truthiness vs presence) — fixed with a map-guarded key-presence test

The routing test is now `(if (data/map? callee) (data/contains? callee :yang.call/entry) false)` — presence, not truthiness:

- `(let [m {:yang.call/entry nil :x 42} v [m]] (m :x))` → `[:yin.k/error #:yang.call{:reason :yang.call/not-callable, :data #:yang.call{:callee {:yang.call/entry nil, :x 42}}}]`, same with `false` — reaches admission and refuses, both lanes.

## Library-enabled regressions (your requirement)

New `test-value-invocation-refusals-are-terminating` in `test/yang/clojure_test.clj`, running through `run-library` (the library invoker IS in the compiled set; a `[five]` vector binder forces the dynamic-call selection so the routing is genuinely exercised), walker AND semantic lanes:

1. scalar callee → exact not-callable condition shape (asserted in full);
2. module-fn callee → `:yin.k/ok` through admission (the corrected semantics above);
3. projected raw closure → error, reason `:yang.call/not-callable`;
4. `{:yang.call/entry nil}` and `{:yang.call/entry false}` callees → error, reason `:yang.call/not-callable` (presence, not truthiness);
5. the refusal survives a tail-position value call (`(defn tail-refusal [] ...)` invoked as the final form);
6. operands before the callee check, both positions: `(five (boom) 2)` and `(five 1 (boom))` with `(defn boom [] (throw (ex-info "boom" {})))` → the error is boom's (`(:yin.k/message payload)` = `"boom"`), not the refusal. (A native-refusal probe via `(nth [1] 5)` was tried and discarded: outside an adapter seam the data refusal surfaces as a host exception, so guest throws are the honest observable.)

## Verification (this seat, post-fix)

- Focused JVM yang.base lane: **122 tests / 0 failures** (was 121/0 pre-r1-fixes; the +1 is the new regression test).
- clj-kondo on both changed files: 0 errors, 0 warnings.
- Full JVM suite (clojure test-runner minus :slow) is in flight at dispatch time; its result is recorded in the session log before any commit lands.

## Ask

Confirm both blockers are closed and rule on the sign-off for `master-base..0bf8bc4e + this working-tree reconciliation`. If you grant: state any conditions (the standing ones on this track have been final-commit shiva Node/Dart lanes). If you deny: name the defect.
