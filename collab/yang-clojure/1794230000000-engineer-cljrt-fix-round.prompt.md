Created-GMT: 2026-11-03 20:20:00 GMT
Created-Local: 2026-11-04 03:20:00 Asia/Ho_Chi_Minh
Coding-Agent: muse (muse-wonder-1.0, API lane)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: cljrt fix round — the three defects the quality gate found

Role: compiler engineer (muse, continuation of the runtime-module round)
Reviewer/landing: the ZCode orchestrator seat; a fresh codex gate re-reviews before landing.

## Context

Worktree `/Users/sto/workspace/datomworld-cljrt`, branch `yang-clojure-runtime-module`, HEAD `e267bcf3` (your own runtime-module commit — published module, clj/run-main, globals, the :clojure profile, both matrix legs; seat-verified matrix 9/11/0 and yang.base 122/0). The codex quality gate says DO NOT LAND YET — three correctness defects, full verdict in `collab/yang-clojure/1794230000000-reviewer-cljrt.codex.stdout.log` (read it first; the block beginning "Verdict: do not land yet").

## The three defects (with the gate's file/line references)

1. **Projected globals erased before the caller runs** — `src/cljc/yang/clojure/prelude.cljc:87` clears `clj.rt/globals` on every `clj/run-main`. The profile projects a foreign export into that table AND writes the task store; ordinary free reads use the second binding, so the passing leg does not demonstrate projection through the runtime globals. If the caller also defines that name, `lower-unit` (clojure.cljc:2778) rewrites its reads to `clj/global-get` — where the projected value was already cleared. Re-entering run-main also clears globals captured by an earlier clojure unit in the same task. Fix the lifecycle: projection must survive into the caller's run, and a caller-defined name must shadow the projected one (or the semantics you choose must be deliberate, documented, and consistent between the two bindings — the gate's point is that today they are not).

2. **A source map `{:type :cell-ref}` triggers an invalid cell dereference** — clojure.cljc:1308 routes any map with :type :cell-ref to admission; admission (:2104) calls cell/get immediately. A normal source map shaped that way no longer behaves as an invocable clojure map; it raises a dead-or-forged-reference VM failure. Not a witness bypass (the VM seal holds), but the structural test must distinguish source data from an authentic reference, or handle refusal without breaking map invocation.

3. **ts kind-of still misclassifies cell-backed foreign functions** — `src/cljc/yang/typescript/antlr/prelude.cljc:135` checks `yang.call/eligible?` only AFTER the cell branch, so a cell-backed foreign function (e.g. a python function: a cell with :py/type :function and a call entry) classifies as :object though it is callable. Cover admitted foreign cells too.

## Required regressions (each defect gets one)

1. Projection-through-runtime-globals: a leg where the CALLER also defines the projected name — assert the semantics you chose; and a leg proving the projected value is readable via clj/global-get inside the caller's run (not just via the task-store binding).
2. A source map literal `{:type :cell-ref}` compiled and invoked as ordinary data — asserts the pre-fix behavior (VM failure) is gone and the post-fix behavior is the deliberate one.
3. js/kind-of over a cell-backed foreign callable returns :function (python or clojure donor into js).

## Verification (real outputs in your report)

- The matrix namespace: all legs green including the three new regressions.
- `bb test:sub:clj yang.base`: 122+/0.
- kondo on touched files: 0 errors.

## Boundaries

Unchanged from the original brief: no `src/cljc/yin/vm/**`, no `yang/core/call.cljc`, no evidence/narrative files. §8.10 conformance binding (no surgery/wrappers/foreign-:code; admission not weakened). Commit on the branch; push; do NOT merge.
