Created-GMT: 2026-11-10 21:0x GMT
Created-Local: 2026-11-11 04:0x +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: py-builtin-type — add the `type` builtin to the Python prelude (dispatch after exception-py-r2r3 lands and merges)

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-terra | Assigned: 2026-11-11 04:00 +07 | Status: STAGED (do not start until the orchestrator says exception-py-r2r3 has merged to master)

WORKTREE: to be cut from master when released; branch `py-builtin-type`. You are the only writer.

## Context

The S11-python conformance round found Python's builtins table has no `type` entry: an exploratory `type()` check raised NameError, and the round's opaque-value legs had to assert identity instead (`collab/yang-python/1794870000000-orchestrator-s11-python-legs-merged.md`). CPython's `type(x)` returns the argument's type object; the prelude already has `py/type-of`-adjacent machinery (see the exception work's `exc-matches`/`type-of` sites around prelude.cljc:3440-3500 and the make-class machinery).

## What to build

1. **`type` as a builtin function** in the builtins table (the :3440-3500 region): one-arg form returns the argument's type representation — reuse whatever the runtime already computes for type dispatch (the class/ns machinery, `type-of`), wrapped as the builtin the same way neighboring builtins are. Do NOT attempt the three-arg dynamic class-creation form of CPython's `type(name, bases, dict)` — out of scope; raise the documented guest error (TypeError-ish) if arity > 1, pinned by a test.
2. **Semantics to pin by focused tests**: `type(x) is type(x)` for the same value; distinct types for distinct builtin classes (int vs str vs the exception classes); the returned type object's own behavior minimal but honest — at minimum, two values with the same type compare `=` under `type()` and values of different types do not; foreign values (a JS function through the boundary) answer with the wrapper class, not a guess.
3. **The scanner-golden rule**: check `docs/agents/known-gaps.md`'s standing rule — any slice touching the prelude runs the generator (`test/resources/yang/python/…`/scanner-golden) in the same slice, byte-identical double-run.
4. **The exception-track ledger**: prelude.cljc is ceded to exception-py-r2r3 UNTIL IT MERGES. Your G0 must re-verify the ledger rows (via `clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test`): if any of the eight py-authorized rows regressed from G, STOP and report — do not build on a broken base.

## Rules

- Regions :912/:1034/:1045/:1046 of prelude.cljc are off-limits even after the round merges, unless master has moved them (they will have shifted — locate by content, not line numbers).
- JVM lane only; no host runs. Focused namespaces: the builtins/prelude tests + the new type tests + the scanner golden.
- Commit: (a) the builtin + tests, (b) the regenerated goldens.

## Deliverable

Final agent message: the builtin's semantics as landed, the test counts, the golden regen evidence, and the ledger re-verification result.
