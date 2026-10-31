Completed-GMT: 2026-10-01 10:15:07 GMT
Completed-Local: 2026-10-01 17:15:07 +07
Coding-Agent: claude
Session-ID: ba6d62ab-caeb-424c-a44e-4637d8333092

# Architect design: the safepoint interpreter

Read-only; no file edited, nothing executed. Evidence is master `60b60898`. The session was in plan mode with no plan-approval tool available, so this response is the deliverable and no plan file was written.

## Recommended design

**A generic stage that reads the canonical program, inserts ordinary `:application` rows calling per-kind hook functions at frontend-marked sites, and writes a derived program to its own stream. All semantics live in a per-language hook prelude. The VM gains one generic effect: a non-parking stream read.**

Four parts, each with one job:

| Part | Job | Knows about |
|---|---|---|
| Frontend (lowering) | Marks sites as `:yang/site` metadata on map-AST nodes. Metadata is stripped at projection into the frontend-metadata side table, so the naive rows are unchanged. | Which lambda is a loop, which is a function, where statements start |
| `yang.safepoint` stage | Rewrites tree `A` into tree `A'` by inserting hook applications at marked sites, per a profile `{kind hook-symbol}`. Pure function of tree, sites and profile. | Universal AST and the side table only |
| Hook prelude (per language) | Defines the hook functions: signal delivery, depth accounting, trace calls, thread switch. | The language's semantics |
| Engine | `stream/poll!`: like `stream/next!`, but `blocked` is a value, not a park. | Nothing about safepoints |

The evaluator never learns what a safepoint is. This is the same shape as "macros are stream topology".

## Q1. What a safepoint is, where it goes, how the stream is rewritten

**Row form.** A safepoint is an ordinary `:application` whose operator is a `:variable` naming a hook, for example `(py.sp/loop)`. No new tag and no dedicated effect kind.

- A new tag would change the grammar on four VMs and the codec for a fact that is only placement.
- A dedicated `:safepoint` effect would teach the engine about signals and threads, and effect handling cannot apply a guest closure. `cell/swap!` was rejected for that reason, and `settrace` needs exactly that.

**The one engine addition.** `stream/next!` parks on `blocked` (`engine.cljc:515-518`, `:2020-2028`), and the stream module has no other read (`module.cljc:288-290`). A hook that polled with it would stop the program at the first safepoint with no signal pending. `stream/poll!` returns `:dao.stream/blocked` as a value. The prelude's async and thread schedulers need the same primitive.

**Sites come from frontend marks, not structure.**

| Kind | Mark | Inserted |
|---|---|---|
| `:loop` | on the loop lambda (`w` in `lower.cljc:889-895`, `:915-927`) | hook at the head of the body |
| `:call` / `:return` | on the function code lambda (`lower.cljc:778-781`) | hook at the head; body wrapped so the exit hook runs after it |
| `:line` | on the node that begins a statement | hook before it, with the line as a literal operand |

I rejected deriving sites structurally ("every closure-valued lambda") for two reasons:

- It cannot tell a function from a loop, and both recursion accounting and `:return` need that distinction.
- The spike bundles the prelude into every tree (`lower.cljc:1094-1097`), so a structural rule would instrument the prelude. That breaks the atomicity §8.11 relies on: "a task switches only at park points". Marked sites leave the prelude untouched automatically, because it carries no marks.

A kind absent from the profile is not inserted. The `:line` literal lives only in the derived tree, so the canonical program stays insensitive to whitespace (owner decision 2 of the mappability ruling is preserved).

**Rewriting without breaking addressing.**

- The stage reconstructs the map AST from rows, inserts, and re-projects through `vm/ast->semantic-bytecode`. Every row id is recomputed, so `id = segment-key(body)` holds by construction.
- Ancestors of a site get new ids; untouched subtrees keep theirs and are shared with the canonical tree.
- The canonical program `A` is never modified. Names, the ledger, the index, publication, the linker and diffs all refer to `A`.
- The link is an existing `:derive` ledger record: input `A`, output `A'`, function `:yang.safepoint/insert`, profile pinning the hook map and the address of the sorted site set. It is deterministic, so a repeat writes the same record. No attempt identity is needed, unlike `:expand`.
- `A'` is admitted on the stage's own output medium with its own batch token, so its occurrence origin is an ordinary `[:source medium' batch' j]`. No new origin kind.
- Side tables are not copied or re-keyed. Insertion only prefixes and wraps, so the path map from `A'` back to `A` is a pure function of the site set. Positions for an `A'` node are a join through that function to `A`'s occurrence.
- Inserted binders use a reserved `yang.safepoint/` namespace, with no gensym counter.
- Tail marks must be stripped and recomputed over the whole derived tree (see findings).

**Which stream the evaluator reads.** Exactly one, chosen by the composition: the canonical stream for the naive run, or the stage's output. Never both (`yang.antlr.md` §1.1).

## Q2. Semantics per consumer

None of the four semantics belongs in the stage. It only places hooks.

| Consumer | Where | How |
|---|---|---|
| Signals, `KeyboardInterrupt` | Hook prelude, at `:loop` and `:call` | A host adapter appends a plain event to a stream the composition supplies. The hook calls `stream/poll!`; on an event it runs the registered Python handler, or by default calls `py/raise` with `KeyboardInterrupt`. The raise is an explicit continuation invoke at an explicit program point (`prelude.cljc:97-100`). |
| `settrace`, `setprofile` | Hook prelude, at `:call`, `:line`, `:return` | The trace function is a guest value in a cell. The hook reads the cell and applies it with `py/call`. This is guest code applying a guest function through an ordinary row; nothing on the host calls back, so the callback invariant is not touched. It needs frame records and a re-entrancy flag. Observation-only profilers and coverage should stay telemetry stream observers. |
| Green threads | Hook prelude, at `:loop` and `:call` | A run queue of continuations in a cell. The hook counts safepoints and switches every N, capturing with the flag-cell pattern of `py/call-ec` (`prelude.cljc:115-124`). All threads live in one task and one heap. The `:exception` trace event is emitted by `py/raise` itself, not by a site. |
| `RecursionError` | Hook prelude, at `:call` and `:return`, plus the base prelude's escapes | Entry increments and checks; normal exit decrements. See the correction below. |

**Correction to "decremented by escapes".** An exception can unwind many frames, so decrement-by-one is wrong. Depth is part of the dynamic context of a capture point: the escape must restore the depth saved at capture. `py/try` and `py/call-ec` already do exactly this for the handler stack (`prelude.cljc:103`, `:112`, `:117`, `:122`). Generalise `py.rt/handlers` into one dynamic-context record (handlers, depth, current frame) in one cell. Escapes then restore depth at no extra effect cost, and a thread switch swaps the whole record. Without that, thread B's raise would invoke thread A's handler.

## Q3. Cost and composition

- **Naive stays correct.** The canonical program is never touched, and hooks are absent from it.
- **On and off.** Three switches: which stream the evaluator observes, which kinds the profile maps, and whether the hook prelude is loaded. An empty profile is the identity (`A' = A`). A derived program run without its hook prelude fails closed on an unresolved symbol.
- **Cost.** A `:loop` site with signals on is one application plus one effect dispatch. `:call` and `:return` with depth accounting are a cell get and set each. `:line` sites cost one cell read per statement, so they are inserted only under a trace profile. None of this has been measured.
- **Continuations.** Covered by the dynamic-context record. The same rule holds for multi-shot re-entry in other languages. Hooks never test the continuation representation, so D7 does not affect them.
- **Cells and GC.** Safepoints are not collection points. The trigger stays in the `:cell/new` arm, as the reclamation design ruled. Hooks allocate flag cells, which are ordinary garbage. A run queue of continuations in a cell is traced through `gc-children`.
- **Determinism.** Insertion is pure and host-independent (sites processed in sorted path order). Depth, tracing and count-based switching are deterministic. Signals are the one nondeterministic input: `stream/poll!` is the only place where "was it blocked" becomes program-visible, where today it is not (`engine.cljc:392`). It is its own effect kind, so a profile that omits it cannot observe timing. A signal is an operational event (`yang.antlr.md` §11), not a deterministic language error.
- **Open, out of slice.** A task parked on a blocking read cannot receive a signal, and several threads waiting on different streams need an any-of park.

## Q4. Language-agnostic or per-language

The stage is generic over the Universal AST and the frontend-metadata side table, and belongs at `yang.safepoint`, not under `yang.python`. Per language: the marks, the hook prelude and the profile map.

- **PHP** reuses it directly: `declare(ticks=N)`, `register_tick_function` and `pcntl_signal` are statement-level safepoints.
- **JavaScript** uses `:loop` and `:call` for interruption and debugging only. Its jobs run to completion, so it must never switch at a safepoint.
- **Go and Java** green threads use the same sites.

## Q5. First slice and acceptance tests

**Slice 1: the mechanism plus signals, end to end.**

1. Engine: `stream/poll!` export and `:stream/poll` arm in the shared `handle-effect`, with a declared profile.
2. Lowering: emit `:yang/site` marks for `:loop` and `:call`, and emit an `encoder/source-envelope` with the map AST as its member, so marks reach the side table.
3. `yang.safepoint`: pure `insert`, tail re-marking, the stage over the row medium, the derive record.
4. Python hook prelude: `py.sp/loop` and `py.sp/call` polling a signal cursor; a `KeyboardInterrupt` class under `BaseException`.

Later slices, in order: recursion (dynamic-context record, `:return` wrap), tracing (frame records, `:line` marks), threads.

**Acceptance tests, four VMs:**

- Transparency: the e2e corpus through the stage with no-op hooks gives the naive output.
- Identity: an empty profile yields the same root and rows.
- Canonical untouched: the input batch is unchanged; every derived row validates; prelude rows keep their ids.
- Insertion determinism: the same input gives the same `A'` and record address on CLJ, CLJS and CLJD.
- Interrupt: `while True: pass` with one pre-appended signal ends with `KeyboardInterrupt`; wrapped in `try/except KeyboardInterrupt` it prints from the handler.
- No park: with an empty signal stream the derived program finishes without ever blocking, with the same output as naive.
- Tail preservation: a 100,000-iteration safepointed loop grows no continuation on the VMs that honour tail marks.
- Atomicity: no hook application appears under any prelude definition.
- Fail closed: the derived program without the hook prelude reports the unresolved hook name.
- `stream/poll!`: on an empty stream it returns `:dao.stream/blocked` without parking; `ok` advances the cursor; a callee without the declared effect is refused.

## Owner decisions

1. **One new generic effect, `:stream/poll`.** It exposes `blocked` to guest code. The alternative is a dedicated safepoint effect, which puts safepoint knowledge in the engine. I recommend the generic poll.
2. **Sites from frontend marks** rather than structural derivation. I recommend marks; revisit when the prelude becomes a linked module.
3. **Identity.** Names, ledger and publication refer to the canonical tree; the evaluator runs the derived one; any guest-visible code identity reports the canonical address through the derivation. I recommend this.
4. **Signal replay.** Treat delivery timing as an operational event and do not journal it in slice 1 (recommended), or journal each delivery with a safepoint ordinal.
5. **Thread switch policy.** Count-based every N safepoints, never time-based. I recommend count-based.
6. **`sys.settrace` under a profile without tracing.** Raise an explicit unsupported error (recommended) or accept silently.
7. **Slice order:** signals, recursion, tracing, threads.

## Findings

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| high | `src/cljc/yin/vm/engine.cljc:515-518`, `:2020-2028`; `module.cljc:288-290` | The only guest read parks on `blocked`. | Add `stream/poll!` and `:stream/poll`. |
| high, trap | `src/cljc/yin/vm/semantic.cljc:236`; `yang/python/antlr/uast.cljc:102-127` | A tail call pushes no frame. A body wrapped by an exit hook while its applications keep `tail? true` skips the hook. | Strip and recompute tail marks over the whole derived tree; move `mark-tails` to a shared namespace. |
| high | `src/cljc/yang/python/antlr/lower.cljc:1094-1097`; `docs/design/yang.antlr.md:1496-1497` | Prelude and module body are one tree; instrumenting the prelude breaks get/set atomicity. | Insert only at marked sites. |
| high | `lower.cljc:1120`; `uast.cljc:22-44` | The program port carries a bare `vm/ast->datoms` batch; the constructors emit no positions, marks or origin. | Emit `encoder/source-envelope` with the map AST (`encoder.cljc:25-43`, `:103-109`; `vm.cljc:1189-1192`, `:1215`). |
| medium | `prelude.cljc:97-124`, `:648-649` | The handler stack is one global cell that escapes save and restore; depth and frame need the same, and threads need it per thread. | One dynamic-context record, restored whole. |
| medium | `docs/design/yang.antlr.md:1266-1267` | "Decremented by escapes" is wrong for multi-frame unwinds. | Restore the depth saved at capture. |
| medium | `prelude.cljc:607-622` | No `KeyboardInterrupt` or `RecursionError` class. | Add them in the hook prelude; `KeyboardInterrupt` under `BaseException`. |
| medium | `src/cljc/yang/python/antlr/stage.cljc:1-21` | Generic stage machinery lives under the Python frontend. | Move to a language-neutral namespace before the second user. |
| low | `docs/design/yin.vm.code-as-tuples.md:1555` | `:derive` is documented as tree to segment only. | Note tree-to-tree derivations distinguished by `:yin.ledger/function`; no new op. |
| low | `docs/design/yang.antlr.md:1336-1338` | The decision records only "attached interpreter". | Record marks, hooks, the poll effect and the identity rule after sign-off. |
| info | `engine.cljc:2034-2036` | Allocation is the only collection trigger. | Keep it; safepoints are not collection points. |
