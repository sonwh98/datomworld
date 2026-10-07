Created-GMT: 2026-09-19 20:24:00 GMT
Created-Local: 2026-09-20 04:24:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session, now covering the waitset unit)
# Task: adversarial review of dao.stream.waitset Phase W1 (the sweep)

New unit, same discipline as your lease reviews. The implementer was
glm-5.3-flash; the plan went through the full two-architect consensus and
was committed (`18664048`). The orchestrator verified the matrix: focused
11 tests / 116 assertions; JVM full 1461/168064/0; CLJS 1378/37933/0; CLJD
zero waitset failures.

Under review, in the MAIN repo working tree (`/Users/sto/workspace/datomworld`):
- `src/cljc/dao/stream/waitset.cljc` (new)
- `test/dao/stream/waitset_test.cljc` (new)

against:
- `docs/design/dao.stream.waitset.implementation-plan.md` — "What
  `dao.stream.waitset` is" and Phase W1 (the revised, consensus-governed
  text), D1–D7
- `docs/design/dao.stream.md` — outcomes and the non-blocking rule
- `src/cljc/yin/vm/engine.cljc:266-360` — the sweep being extracted
  (branch-for-branch fidelity where the plan requires it)

Priority checks:
1. The complete ordered sweep: every entry polled every call, no budget,
   no scan position; still-waiting entries retained EXACTLY as stored.
2. The never-augment law: no `:stream`, no injected cursor on any retained
   or returned entry; `:advance` invoked only when a poll returned a
   cursor, before the next entry resolves.
3. Terminal classification: `closed` terminal under its own keyword,
   never reader `end`; undeclared outcomes terminal under their own
   keyword; the two literal classification maps domain-equal to the
   declared outcome sets.
4. Diagnostics: `unresolved`/`unsupported-reason` perform no stream
   operation, skip `:advance`, leave the store unchanged.
5. The probe semantics: identity `:advance` called once, store identical,
   re-park wakes on the next value.
6. C6-style boundary: `dao.stream` byte-for-byte unchanged; waitset
   requires only `dao.stream`; no clock, no callback, no atom.

Do not edit. Do not rerun suites. Challenge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report findings as P0-P3 | file:line | evidence | concrete fix. State
explicitly whether W1 is ready for commit.
