Completed-GMT: 2026-09-30 14:14:19 GMT
Completed-Local: 2026-09-30 21:14:19 +07

# Architect mob, ROUND 2, fable seat

Read-only; no file edited and nothing executed this round. I read astra's round 1 in full. I moved on D4, D5, D9 and the shape of D10, and I hold on D6, D7 and the order of D10.

## Final table

| D# | Decision | Changed from round 1? | If still differing, one-line reason |
|---|---|---|---|
| D1 | Confirm copy-on-lift; aliases and cycles preserved within one transferred value graph; slice 1 refuses cell-bearing lifts. | No (adopts astra's "whole transferred graph" wording). | Agreed. |
| D2 | `cell/new`, `cell/get`, `cell/set!`; state `:heap`; portable section `:yin.k/heap`. | No. | Agreed. |
| D3 | Amend §8.1 to a task-owned cell heap updated through persistent VM-state transitions; state threading stays an allowed per-frontend choice. | No (adopts astra's wording; avoid "mutable heap"). | Agreed. |
| D4 | Fix before the spike, as a separate commit before cell slice 1. **Both mechanisms, layered:** effects are a host type minted only by `module/make-effect`, and when a result *is* an effect, the engine checks its kind against the callee's declared effect set. | **Yes, converged.** | None if astra accepts the layering; the type test alone closes guest forgery, and astra's check then costs nothing on ordinary calls. |
| D5 | **Box every function-local binding and every parameter**, allocated at function entry with an unbound sentinel. Un-boxing is a separately attached interpreter, not part of the lowering. | **Yes, conceded to astra.** | None. |
| D6 | No separate validation work before the spike; the qualified refusal arrives with D7. | No. | Validating shapes that D7 makes unconstructible is throwaway work across four VMs. |
| D7 | Not before the spike. Direction decided now: host-typed in-machine values for closures and continuations together, on its own track, required before multi-author code reaches one evaluator. | No, but the deadline is sharper (see owner direction). | A payload table retains every capture forever under continuation-lowered control flow, and leaves forged closures open. |
| D8 | Remove worktree and branch now. | No. | The "differing" file differs in one header line, recorded verbatim below, so nothing unique is lost. |
| D9 | `git rm --cached` the 11 files; **no ignore rule, no exclude entry**; no history rewrite. | **Yes, I withdraw the ignore rule.** | It violated `docs/agents/roles/orchestrator.md:110`. Astra's "add an appropriate ignore rule" violates it too. |
| D10 | D4 → cell slice 1 → Python spike **built as a stream topology**. D7 is a parallel track, not a gate. | Shape changed, order not. | Astra's order puts a leak-prone table and a four-VM validation pass in front of a single-author spike. |

## How the owner direction applies

**D5 and D10: the pipeline is the design.** The spike has two required stages and any number of optional ones.

- **Required stage 1, parser interpreter (JVM host):** ANTLR parse → CST data on a stream, one complete compilation unit per chunk (`yang.antlr.md` §5.3, §5.5).
- **Required stage 2, lowering interpreter (portable `cljc`):** reads the CST stream, writes Universal AST onto the program stream the evaluators already observe (`test/yin/vm/test_utils.cljc:115-202`).
- **Optional attachments:** un-boxing and liveness, linting, indexing. Each reads a stream and writes its own.

Two consequences follow.

- **"JVM-only" now confines only the parser.** The lowering is portable and can run on any host that reads the CST stream.
- **The lowering stays naive.** Box-all is the reference output. An un-boxing interpreter writes a different tree with a different address to a different stream, and the composition chooses which stream the evaluator reads. Correctness never depends on an optional observer.

**One challenge to the orchestrator's reading.** Binding collection (which names are local to a function) is not an optional attachment. It decides what gets boxed and where `global` and `nonlocal` apply, so it is part of the required lowering stage. Only liveness and un-boxing are optional. Box-all needs binding collection, not liveness.

**D4, D6, D7: these are evaluator guarantees and cannot live in an attached interpreter.**
- An attached interpreter sees syntax on a stream. The forgeries are runtime values (`assoc` results, literals applied as functions) that no stream observer ever sees.
- Observers are optional by commitment (`datom.world.md:130`), so a guarantee cannot depend on one being attached.

**The direction raises D7's stakes but not its timing for the spike.** "Any number of interpreters can attach" means a program stream is multi-writer by design, so code reaching an evaluator is not single-author in general. That makes D7 mandatory before any composition wires a foreign interpreter's output to an evaluator. The spike wires only our own two.

## Responses to astra

- **D5 forward capture: astra is right.** `def f(): return x` followed by `x = 1` has one assignment, so my "reassigned" rule leaves `x` unboxed and `f` captures an environment without it. Parameters need it too: `def f(a): g = lambda: a; a = 2; return g()`.
- **Objection 4, cycle safety: astra is right.** I called the heap lift a mirror of `cell-for!`, but `cell-for!` builds the payload before recording the mapping (`engine.cljc:609-618`). The heap lift must reserve the id before traversing contents. I also accept that the repeated-link behaviour at `engine.cljc:847` is a correctness gate for slice 2.
- **Objection 5, F1 authority: partly right.** Replacing `effect?` with a type test does fix guest forgery, because guest data can never be the host type. What it leaves is a host function returning an effect outside its profile. Astra's check covers that, which is why D4 layers both. It needs an identity-keyed callable→profile map built at registration (`module.cljc:179-187`); a per-effect registry scan would land on every cell operation.
- **D7 payload table.** It is the existing `:parked` table by another name (`engine.cljc:1701-1717`). Parked entries are removed on resume; a multi-shot continuation cannot be. With every `try`, early return and loop exit capturing, the table retains each capture's frames and environment for the life of the task. Astra's own risk column names both this and the open closure hole.
- **D8.** The only difference is line 4: the worktree copy has `Session-ID: pending (provider-generated)` and main's has `Session-ID: 01a0f227-f33c-79b3-86ff-34de56a9afe4 (captured)`. Main's is the later edit of the same prompt.

## New evidence

- `docs/agents/roles/orchestrator.md:110`: `collab/` is never staged or committed and never ignored or excluded. The 11 tracked files already violate it; untracking restores conformance and returns them to the untracked view.
- D9 residual risk under that rule: nothing mechanical stops a re-add. Commit with explicit paths, never `git add -A`. A pre-commit hook rejecting `collab/` paths is compatible with the rule; whether to install one is an **owner decision**. Checking out an older branch that still tracks those files may hit "untracked files would be overwritten".
- `src/cljc/yin/vm/engine.cljc:1701-1717`: park and resume, the precedent for astra's table and the source of its retention problem.
- `src/cljc/yin/vm/engine.cljc:609-618`: `cell-for!` records the mapping after building the payload.
- `test/yin/vm/test_utils.cljc:115-202`: evaluators already consume a program stream, so the spike's second stage has an existing sink.

## For the owner to pick between

- **D6/D7 timing.**
  - Astra: seal continuations through a private payload table and add qualified defects, both before the spike.
  - Fable: no gate on the spike; host-typed closures and continuations as a parallel track, required before foreign code reaches an evaluator.
- **D9 hook.** Whether to install a pre-commit hook that rejects `collab/` paths.
