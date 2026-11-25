Created-GMT: 2026-11-25 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a20de0)
Session-ID: 01a20de0-f80b-7ff2-8885-de32daef9dcd (RESUME of the remint thread, jupiter POOL 2)

# Task: sentinel-fix r1 — implement the agy ruling 1795660000000 (:py/missing sentinel)

Worktree: ~/ci/dw-remint (branch fix/py-manifest-remint, now at ee74b7b7 — the r3 findings
fixes just landed on it). Your pin re-mints are on this branch; the sentinel fix rides the
SAME branch so code + pins land together (the master-green brief's watch-out). No commits.

## The ruling (READ FIRST)
collab/exception-track/1795660000000-architect-nil-condition-payload-ruling.gemini-4-argon-high.findings.md
(on origin/master — fetch first). The master-green brief:
collab/yang-php/1795670000000-brief-nil-condition-sentinel-fix.md (on origin/master).

## The mission (your surface: src/cljc/yang/python/antlr/prelude.cljc:741-766)
1. py/make-exc: the no-message sentinel becomes :py/missing (the prelude's standard internal
   omitted-argument sentinel) — the empty args tuple ONLY for :py/missing; every other message
   (including guest :py/None and raw malformed nil) builds a 1-element tuple.
2. py/assert: bare assert passes :py/missing; `assert test, None` still passes :py/None
   (1-element args); the message thunk stays lazy.
3. dsg__condition-exception and the test file: UNTOUCHED.

## Non-weakening obligations (all four, from the ruling — verbatim)
1. :garbage and every other non-nil non-map condition: :payload [:garbage], :provenance nil,
   all three backends — unchanged.
2. Fresh TypeError + no provenance retained for nil.
3. assert parity: bare assert -> empty args; `assert False, None` -> [:py/None];
   `assert True, f()` never forces the thunk.
4. The test file stays at :payload [malformed] for [nil :garbage].

## The watch-out (pin moves)
prelude.cljc content moves the prelude-derived pins. After the fix, check the py pins
(manifest golden, float-address set, descriptor profile root, module/revision locks — the
set freshly re-minted on THIS branch in r2/r3) and re-mint any that move in the SAME branch,
two-run determinism each.

## Verification bar (2026-11-07 merge bar)
1. DATOM_SLOW_TESTS=1 clojure -M:test -n yang.call.python-exception-test -> 0 failures,
   TWICE in fresh JVMs.
2. The assert pins: py/assert rows in the scanner/lower/e2e tests green (bare-assert
   empty-args, None-message 1-arg, thunk laziness).
3. The py pin set: run the pin namespaces; re-mint anything that moved (determinism x2).
4. float-address-test + linked-prelude-test green (or re-minted green) twice.
5. clj-kondo clean.
Log custody target/sentinel-r1/. Findings per convention (worktree root if blocked).
No commits — the seat publishes.
