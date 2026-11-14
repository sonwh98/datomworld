Created-GMT: 2026-11-12 19:05:00 GMT
Created-Local: 2026-11-13 02:05:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 7a76b652-c38e-46db-9d66-1e134d72c6d5
# Task: engineer — PORT Phase 1: the continuation family in the clojure prelude
Role: Engineer (port phase 1 of the convergence; Phase A rebase landed at f37bc62b)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-13 02:05 +0700 | Status: active | Rationale: continuity — you rebased the branch and know the mid-refactor red state (38F/13E: the compiler emits cell/retained? doors the seam removal deleted).

## The state (your Phase A report, confirmed)

Branch `exception-r3-impl` @ `f37bc62b`, rebased onto post-cc3 master; engine/module
byte-identical to master (primitive preserved, declined seam gone); `clojure.cljc` still emits
`cell/retained?` / `cell/dsg__new-retained` in `condition-wrapper-form` (:945) /
`condition-retained-form` (:957) — the 38F/13E red. `yang.clojure` compiles (test suite loads).

## Phase 1 — the continuation family

Add to `src/cljc/yang/clojure/prelude.cljc` the continuation scheme family, mirroring
py/ts/php (their preludes are the reference implementations — read `py/try`/`py/try-finally`/
`py/raise`/`py/call-ec`/`py/unwind-to`/`py/frame`/`py/restore!`/`py/set-handlers!` and the
`py.rt/ctx` cell pattern):

- `clj/rt-ctx` (the runtime cell), `clj/ctx`, `clj/frame`, `clj/set-handlers!`, `clj/handlers`,
  `clj/restore!`, `clj/unwind-to`, `clj/raise`, `clj/throw` (guest form), `clj/try`,
  `clj/try-finally`, `clj/call-ec`
- Preserve the intra-unit private tail lane (deepseek's remedy): direct intra-unit calls stay
  direct tail application; only published/cross-unit entries take the bridge frame. The port
  does NOT touch the tail-lane machinery — bridge frames come from published entries only.
- **No VM changes** (`src/cljc/yin/vm/**` must diff EMPTY vs master).

## Scope

`src/cljc/yang/clojure/prelude.cljc` ONLY this phase (the family must exist before lower-tail
can reference it). Verify: the prelude compiles (`yang.clojure` loads; the test suite still
loads — it may stay red, the consumer wiring is Phase 2); kondo on the file (binary available?
if not, say so). Commit + push `exception-r3-impl`.

End with Completed-GMT/Completed-Local and: **PHASE 1 LANDED: <sha>** or **BLOCKED: <what>**.
