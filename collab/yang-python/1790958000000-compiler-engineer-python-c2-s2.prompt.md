Created-GMT: 2026-10-02 15:56:00 GMT
Created-Local: 2026-10-02 22:56:00 +0700
Coding-Agent: claude
Session-ID: aa90648c-54e8-47fa-8bc9-61f7ca34fac8

# Task: Python C2-S2 Generators — send, throw, close & dynamic context

Role: Compiler & AST Engineer (docs/agents/roles/compiler-engineer.md)

Worktree: `/Users/sto/workspace/datomworld-py-c2gen1` (branch `yang-python-c2-s2` rebased on `master@cf6ed9ad`).

Governing Contracts:
- `docs/design/yang.antlr.md` §8.5.3 (Generators, Phase C2, Slice S2)
- `collab/1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md`
- `collab/1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md`

Scope of Slice S2:
1. `GeneratorExit`:
   - New builtin exception class under `BaseException` in `src/cljc/yang/python/antlr/prelude.cljc`.
2. Generator methods in `py/getattr` (:generator arm):
   - `send(value)`:
     * If generator state is `:created` and `value` is not `None`: raise `TypeError("can't send non-None value to a just-started generator")`.
     * Otherwise resume generator with `[:send value]`.
     * If generator returns `[:yield v]`, return `v`.
     * If generator returns `[:return v]`, raise `StopIteration(v)`.
   - `throw(typ, val=None, tb=None)`:
     * Raises exception at yield site on the generator's stack.
     * If generator state is `:created`, mark generator `:closed` and raise in the caller.
     * If generator catches it and yields, return the yielded value.
   - `close()`:
     * Sends `[:throw (GeneratorExit)]` into the generator.
     * If generator raises `GeneratorExit` or exits with `[:return _]`, return `None`.
     * If generator yields a value instead of terminating, raise `RuntimeError("generator ignored GeneratorExit")` and leave generator suspended.
   - `__next__()`:
     * Calls `send(None)`.
   - `__iter__()`:
     * Returns `self`.
3. Generator already executing check:
   - State moves to `:running` during execution.
   - If `send`, `throw`, or `next` is invoked while state is `:running`, raise `ValueError("generator already executing")` in caller before any switch.
4. PEP 479:
   - If `StopIteration` escapes the generator body (uncaught inside generator), wrap/convert it to `RuntimeError("generator raised StopIteration")`.
5. `finally` and `with` around `yield`:
   - Verify handler stack frames saved in `:ctx` at suspend and reinstalled at resume work seamlessly across yields, with `GeneratorExit` or thrown exceptions properly running `finally` cleanup blocks.

Verification Requirements:
- Write comprehensive test coverage in `test/yang/python/antlr/e2e_c2_test.clj` and/or `test/yang/python/antlr/e2e_test.clj`.
- Verify across all three hosts:
  * JVM: `mise exec -- bb test:clj`
  * Node: `mise exec -- bb test:cljs`
  * Dart: `mise exec -- bb test:cljd`
- Linters & style: `clj -M:kondo` 0/0, `cljstyle check` clean, pure ASCII, max 80 columns.
- Do NOT commit or stage code. Report results clearly.
