Created-GMT: 2026-10-08 19:58:30 GMT
Created-Local: 2026-10-09 02:58:30 ICT
Coding-Agent: glm
Session-ID: 7e73e6ee-06c4-4002-9587-93ebdc6ac3a5

# Task: Track A Phase C4 Slice P3 (`pysp` linked & safepoint hooks)
Role: Compiler Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-10-09 02:58:30 ICT | Status: active | Rationale: Implementation engineer for Track A Phase C4 Slice P3

You are the Compiler Engineer implementing Track A Phase C4 Slice P3 in `/Users/sto/workspace/datomworld-p3`.

## Context & Authorities
- Architecture & Plan: `docs/design/yang.antlr.md` (§8.5.6 Imports, the linked prelude, and the frontend catalog; §8.11 Safepoints)
- Prior landed slice P2: `archive/yang-python-linked-prelude/` and `src/cljc/yang/python/antlr/prelude.cljc`, `src/cljc/yang/python/antlr/lower.cljc`, `test/yang/python/antlr/linked_prelude_test.cljc`
- Safepoint base & tests: `src/cljc/yang/python/antlr/safepoint.cljc`, `test/yang/python/antlr/safepoint_test.cljc`

## Scope of Slice P3
1. `pysp` linked module:
   - Invert load order per §8.5.6: linked `pysp` requires `py`.
   - Its cursor cannot be created at install time and a module closure cannot read an ambient signal stream; the linked entry wrapper or initializer passes the stream reference to `(pysp/attach! signals)`.
   - Module emitter & spec for `pysp` (`yin/def spec {:name "pysp" ...}`).
   - Host module profiles: `pysp` exports and requirements (signals stream, cell, data).
2. Wire `pysp` hooks with linked programs:
   - Ensure `A'` transformation over linked Python programs correctly references `(pysp/loop)`, `(pysp/call)`, and `(pysp/return body)`.
   - Preserve site marks and verify recursion depth tracking (`py.rt/limit`, `py.rt/ctx`) across linked module boundaries.
   - Verify signal delivery (`KeyboardInterrupt` raised via `py/raise` continuation invoke) under `:prelude :linked`.
3. Multi-VM acceptance tests in `test/yang/python/antlr/`:
   - Acceptance test suite proving linked execution with safepoints enabled across semantic, stack, and register VMs on JVM, Node, and Dart.
   - Verify determinism and absence of site marks in prelude bodies.

Complete the implementation, verify tests pass across JVM (`bb test:clj`) and Node (`bb test:cljs`), check linter (`clj -M:kondo`), and report your findings in:
`collab/1791489508540-compiler-engineer-c4-p3.glm-5-3.findings.md`
