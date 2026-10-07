[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
Completed-GMT: 2026-09-23 08:18:02 GMT
Completed-Local: 2026-09-23 15:18:02 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: not exposed by this harness to the agent (review executed in worktree `/Users/sto/workspace/worktree-debruijn-b4`, read-only; no files edited, no git commands beyond read-only inspection)

# Verdict: **READY**

B4 is implemented within its file box, the three engine edits are exactly the additive changes `yin.vm.engine.md` §7 specifies, operand order is in exact alignment with both lowerers, the restore protocol enforces the same-model/same-image rule before any register is written, parked/wait entries are pure data surviving EDN round-trips, and all three host lanes plus kondo are green. No P1 or P2 defect survived the hunt. Three P3 observations follow.

## What was reviewed

Working tree vs `ba88f769` (the stated master base; note local `master` has since advanced to `b0e21970`, so `git diff master` mixes in unrelated dao.jing deletions — the review target is the working-tree diff vs HEAD): `src/cljc/yin/vm/debruijn/stack.cljc` (+474/−84), `src/cljc/yin/vm/engine.cljc`, `src/cljc/yin/vm/ffi.cljc`, the new `test/yin/vm/debruijn/stack_effects_test.cljc`, and the three test edits. Read in full: design doc §4.1/§6, engine doc §7, datom.world axioms/invariants, the engine, ffi, module, semantic, both linearizers, and the B1 validator's saturation rule.

## Mandate findings

**1. Engine seam bounds & protocol — clean.**
- `engine.cljc` diff is exactly two hunks: the `resume-from-run-queue` call becomes `(restore-fn base entry (:value entry))` (plus docstring), and the new `scheduler-round [state restore-fn]`, verbatim `yin.vm.semantic/scheduler-round` with the restore parameterized. Nothing else in the engine moved; no outcome or entry key changed.
- 2-arity compatibility holds: `semantic-restore` (semantic.cljc:129) and `ast-walker-restore` (ast_walker.cljc:500-501) both carry the `([base entry] …)` shim, so the 3-arity convention cannot arity-error either live VM — confirmed by the full walker/semantic/completion suites passing.
- `ffi/response-wait-entry` dissocs exactly `:request-sent :op :datom` and adds exactly `:call-id`, `:reason :next`, `:cursor-ref`, `:stream-id`; `call-response-wait-entry` (walker shape) untouched. The engine test pins an arbitrary foreign payload (including `:k`/`:env`) riding verbatim.
- `:status` is fully eliminated (record, `load-image`, `reset`, all step paths); `halted?`/`blocked?`/`value` delegate to `engine/halted-with-empty-queue?`/`engine/vm-blocked?`/`engine/vm-value`. No other src consumer of the namespace exists, so the key removal is safe.
- Must-not-change list verified: `yin.vm.semantic`, `yin.vm.ast_walker`, dao.stream/lease/waitset, the merged projection, and all named effect rules are absent from the diff.

**2. Stack layout & operand order — exact alignment, and not by coincidence.**
- `:stream-put`: `debruijn_linearize.cljc:139-142` emits target, `:push`, val-node, `:stream-put` (identical pc-for-pc to `linearize.cljc:119-122`); with the no-op `:push` convention the stack is `[… target val]`, and the VM takes `val` from top, `target` beneath, popping both. The test pins the layout directly and the "Invalid stream reference" fixture uses a distinct ref vs value `1`, so an inversion cannot pass silently.
- `:stream-cursor`/`:stream-next`/`:stream-close`: the lowerers emit the source's value-producing instruction immediately before the op (no `:push`), leaving exactly one ref on top; each VM case pops exactly one. The parked-entry test asserts `(:stack entry) = []`.
- `:ffi-call`: `args = (subvec stack (- total argc))`, `stack'` below — identical to semantic.cljc:414-416, and the operand order matches `flatten-resolved`'s per-operand `:push` emission. The `[1 2 3] → [1 2 3]` `vector` probe is non-commutative, so arg inversion would fail.

**3. Restoration protocol — correct and correctly ordered.**
- `stack-restore` refuses first (`:rule :continuation-format` unless `:format = :yin.debruijn.code` and entry `:hash` = the loaded image's H), before either FFI branch — tested against a named-shaped record, a reloaded-image wake out of the ready queue, and a direct call.
- `:request-sent` → re-park as call-out reader via `response-wait-entry`, machine stays blocked (`:value :yin/blocked`), parked record retained; `:call-id` → `ffi/call-result` unwrap + correlation check + `:parked` dissoc; standard path conjes `val` onto the restored stack and clears `:halted?` (needed for direct `engine/resume-continuation` on a parked machine; `:blocked?` is already false on every reachable base). This mirrors `semantic-restore` placement exactly, per engine.md §4.
- Single-image resumption holds: `load-image` recomputes H; stale `:parked`/`:wait-set`/`:ready-queue` survivors are refused by hash on resume (the mismatch test exercises precisely this). Cross-model transport is refused by the missing `:format`.

**4. Pure data & serialization safety — verified.**
Register payload is `{:segment :pc :frames :stack :continuation :format :hash}` only; builders attach `:reason` + resource ids and nothing else (no handle, no `:restore-fn` — the engine ignores the walker's legacy `:restore-fn` key, which B4 correctly did not copy). Tests prove EDN round-trip of the stream-reader wait set, the FFI response reader, and the parked record, plus `host-value?` absence on the fixtures. Host fns can only appear in a payload via `:load-free` results sitting on the operand stack — the identical property the semantic VM's `St` has, so parity oracles agree.

**5. Tests — true behavioral parity, no coincidence modes found.**
30 tests / 201 assertions confirmed. Parity fixtures run one AST through `adapt` vs `linearize/lower` on fresh instances (D4-compliant: the cross-program park/resume fixture carries state only in the store) and compare under the reused B0 normalizer, for values, errors (unknown ref, closed-stream append), blocked/woken, end/gap, FFI (including parked-map cleanup), and the store slice. Gensym ids are order-pinned by distinct prefixes and the shared counter. One scope note: B4's parity is focused fixtures, not the full parity/content/completion corpora — that is correctly B5's box, not a gap here.

**The B4 completion clause** ("lift frames … or run the frame-aware completion adapter so `:yin.k/requires` is not under-approximated") is honored vacuously and safely: B4 exports no continuation through any UCF/completion path (no src consumer of `yin.vm.completion` exists; UCF is deferred by design), and the single-image refusal blocks cross-model consumption. See P3-3.

## Verification runs (this worktree)

- JVM full suite: 1831 tests, 175652 assertions, 0 failures (effects suite alone: 30/201).
- Node/CLJS full suite (shadow node-test, Java 21 + mise node via ProcessBuilder per the worktree-lane workaround): 1748 tests, 45506 assertions, 0 failures.
- ClojureDart full suite (peers built, then `clojure -M:cljd test`): +1710, all tests passed.
- kondo on all changed files: 0 errors, 0 warnings (one benign pre-existing info: unused excluded `eval`, required for the IVM `eval` method).
- cljstyle: **not run** — the binary is sandbox-blocked in this delegated worktree (known environment limitation, disclosed); recommend the committer run `cljstyle check` on the six files before commit.

## Categorized findings

**P1:** none.

**P2:** none.

**P3 (non-blocking; doc/hardening):**

1. **`create-vm` docstring misdescribes the named default** (stack.cljc:120-123). It claims the `{}` defaults "match the named VM's own empty defaults except `:primitives`" — but `vm/empty-state` defaults the named VMs to the *full* `primitives` registry (yin.vm.cljc:1622), which this VM explicitly overrides to `{}`. The divergence is deliberate and the test harness always passes `v2/primitives`, but the sentence could lead a B5 parity harness to construct a mismatched pair. One-line docstring fix.
2. **`load-image` survivor list omits `:ready-queue`, and no load-time validation** (stack.cljc:95-114). `:ready-queue` also survives a reload (only `:blocked?` is cleared) and is protected solely by the deferred hash refusal — behavior the tests pin as designed, but the docstring enumerates store/parked/wait-set/id-counter and skips it. Relatedly, `load-image` never runs `dcode/image-defect`, so a hand-built `[:gensym nil]` image — rejected by B1's saturation rule — would mint `:-0` where the named decoder saturates to `"id"` (semantic.cljc:584). Unreachable via `adapt` (the emitter saturates `:yin/prefix`, yin.vm.cljc:596) and consistent with B3's committed kernel; noting as a hardening/doc gap only.
3. **`completion/complete` has no foreign-VM refusal** (completion.cljc:684-717). It documents `:vm` as "a quiescent SemanticVM" but does not validate shape; handed a quiescent `DebruijnVM` it would silently walk `:k`-absent state and under-approximate `:yin.k/requires`. Out of B4's file box and unreachable today (no consumer), but B5/UCF work should not assume it understands positional frames — worth a refusal or an adapter when that path is built.

Recommendation: merge as-is or after the two one-line doc fixes; the cljstyle check should be run by the committer in the trusted environment.
