Completed-GMT: 2026-09-30 20:33:24 GMT  
Completed-Local: 2026-10-01 03:33:24 +07
Coding-Agent: codex
Session-ID: 01a0f404-65c3-78c3-8c38-8bbd38719685


- **P1 | [lower.cljc:364](/Users/sto/workspace/datomworld-py-spike/src/cljc/yang/python/antlr/lower.cljc:364), [prelude.cljc:165](/Users/sto/workspace/datomworld-py-spike/src/cljc/yang/python/antlr/prelude.cljc:165) |** A name assigned anywhere in the module is always lowered to `py/global-get`, which raises `NameError` before that assignment. Python falls back to builtins: `print(len([])); len = 1` must print `0`. **Fix:** perform runtime module lookup with builtin fallback; a present module key still takes precedence.
- **P2 | [scope.cljc:238](/Users/sto/workspace/datomworld-py-spike/src/cljc/yang/python/antlr/scope.cljc:238), [lower.cljc:361](/Users/sto/workspace/datomworld-py-spike/src/cljc/yang/python/antlr/lower.cljc:361) |** Class reads use a statically collected binding and `py/getattr`. In `x = 1; class C: print(x); x = 2`, Python reads the global `x`, while this lowering raises `AttributeError` because the class attribute has not yet been set. **Fix:** make class name reads check the class namespace at runtime, then fall back to enclosing lookup.

**Owner decisions**

- **Q1 — CI/offline:** Vendor the four pinned grammar and helper sources before landing. Digest checks make downloads reproducible, but `test:clj` now requires network access from a clean checkout.
- **Q2 — Per-unit prelude:** Acceptable for an isolated spike. **Owner decision:** confirm that units are independent and cross-unit builtin identity is outside this spike.
- **Q3 — Helper licences:** Record as unresolved; no redistribution is claimed, so I would not make this a separate landing blocker. **Owner decision:** obtain provenance before distributing generated artifacts.

This was a read-only code review. I did not rerun the orchestrator’s checks.

Verdict: REQUEST CHANGES  
Sign-off: WITHHELD
