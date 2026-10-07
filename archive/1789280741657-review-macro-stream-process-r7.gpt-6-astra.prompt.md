Created-GMT: 2026-09-13 06:25:41 GMT
Created-Local: 2026-09-13 13:25:41 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Round-7 Confirmation — Macro Expansion as a Stream Process (r7 folds in your round-6 P2s)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 13:25:41 +0700 | Status: active | Rationale: Same reviewer confirms its own round-6 findings.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (1084 lines, revision 7). Untracked; read it directly. Appendix C has a round-6 table (rows 33–34).

## What changed
- **P2-1 (traversal inverts `do`/`let` order):** §3.1 step 5 now ranks in a separately defined **declaration order**: for an `:application` whose operator is a `:lambda`, operands left to right then the lambda body; every other node, children in the fixed order. This recovers source sequence order for yang's `((fn [_] B) A)` and `((fn [x] body) v)` lowerings at any nesting. §3.2 states its operator-first order is for allocation determinism only. Phase 1 requires the ordering cases to run through real `compile-program`/`do`/`let` lowering with nested sequences, not flat lists.
- **P2-2 (stand-in identity lost through a macro boundary):** the stand-in is no longer a plain literal with a recorded eid. Step 3 replaces a macro definition with an **internal node type** `{:type :yin/macro-defined :name sym}`, recognised by *shape*; `valid-ast?` accepts it in operands and in output; `:replaced` is gone. A macro that returns or wraps it hands back the same shape. A fabricated stand-in is inert: step 5 only `assoc`es a source macro that step 2 harvested from *this* batch. Step 7 lowers every stand-in to `{:type :literal :value sym}` for `program-out`, so no evaluator sees the internal type. An ordinary `'m` literal has a different `:type` and declares nothing.

## What to do
1. Resolved / partially / not for round-6 P2-1 and P2-2.
2. Re-run the ten round-6 reductions under **declaration order** using yang's actual `compile-do`/`compile-let` shapes (nested `do` inside `let` inside `do` included), plus: identity macro preserving a `defmacro` operand; wrapper macro preserving it inside a larger result; a macro discarding it; a plain `'m` literal; a fabricated stand-in for an undefined name.
3. Probe declaration order for a hole: is there a yang-produced (or Python/PHP-produced) shape where sequence order is *not* "operands then body of an immediately-applied lambda"? Consider `compile-defn`'s native shape, `if` with definitions in branches, and a definition inside a non-lambda operator position.
4. Probe the shape-based stand-in: can a macro turn a stand-in into something step 5 misreads, or misread an ordinary node as one? Is accepting `:yin/macro-defined` in *output* (so wrappers can return it) safe given `:generated-macro` still rejects macro lambdas?
5. Anything new r7 introduced.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then the resolution status, the reduction table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.
