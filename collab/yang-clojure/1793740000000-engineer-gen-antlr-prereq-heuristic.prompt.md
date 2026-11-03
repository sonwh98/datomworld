Created-GMT: 2026-11-02 08:35:00 GMT
Created-Local: 2026-11-02 15:35:00 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: fix the gen:antlr prerequisite heuristic for non-JVM lanes

Role: Engineer (codex)
Reviewer/landing: the ZCode glm orchestrator seat (yang.clojure track)

## Governing instructions

Read `docs/agents/build-n-test.md` (the subsystem lanes and the scratch-clone rules) and `docs/design/test.subsystems.md` if you need lane background. Work in the worktree `/Users/sto/workspace/datomworld-runner-fix`, branch `fix/gen-antlr-prereq-heuristic`, base `e85c582f` (current master). Push the branch when done; do NOT merge to master — the seat lands after verification.

## Writable files

- `src/dev/affected.clj` (the only source file this round may change)
- a focused test for the fixed behavior if one fits the file's existing test layout (`test/dev`-style tests beside the existing affected.clj tests — search for them; if none exist, a `bb`-scriptable check in the round report is acceptable)

Forbidden: everything else. In particular do not touch `bb.edn` task definitions, `deps.edn`, the ANTLR manifests, or any compiler source.

## The defect (measured, two independent sightings)

`prerequisites` in `src/dev/affected.clj` (~line 514) decides `:gen:antlr` from `(any? clj-texts #"Python3Parser|Python3Lexer|build/antlr")` — the **JVM lane's** reach only, per the comment "ANTLR classes are JVM only". That assumption is stale: python keeps its multi-target parsers (the JS/Dart parser retirement applied to yang.typescript/yang.php only — see the known-gaps row "parsing stays JVM-only; the JS/Dart parser slice retired" — python's manifest declares `:java/:javascript/:dart/:cpp`), so the CLJS lane's shadow build requires `yang/antlr/gen/python3/Python3Lexer.js` when a selected cljs test reaches `yang.python.antlr.parser` (measured chain: `yang.clojure.stream-eval-test` → `yin.repl.frontends` → `yang.python.antlr.parser`). With no JVM lane selected, `clj-texts` is empty, gen:antlr is skipped with "Skipping gen:antlr (no selected test reaches its output)", and the cljs compile dies with `The required JS dependency "/yang/antlr/gen/python3/Python3Lexer.js" is not available`.

Sightings: (1) the php/ts seat's p4 shiva lane (fresh clone, PhpLexer ClassNotFoundException — their log records "the subsystem runner's Skipping gen:antlr heuristic is wrong for fresh clones"); (2) this seat's shiva yang.base cljs lane at yang-hardening2 @ ea18c8e4 (the exact JS dependency error above).

## Required fix

Trigger `:gen:antlr` from the ALL-lane reach `texts` instead of (or in addition to, if you find a reason the JVM-only distinction matters for the JAVA classes specifically — investigate and document) `clj-texts`. Keep the `:build:yin-repl-node` / `:build:yin-repl-peer` triggers exactly as they are. Note: gen:antlr is cached and idempotent ("antlr cache hit (inputs and outputs verified)"), so over-triggering costs a cache check, not a rebuild — but do not trigger it for selections that genuinely never reach the artifacts (the full-suite run skips it legitimately; keep that working).

## Acceptance cases (all must hold)

1. Reproduction: in a FRESH clone (or after `rm -rf build/antlr`), `bb test:sub:cljs yang.base` must now RUN gen:antlr as a prerequisite and compile the cljs lane without the JS dependency error. (On the laptop, the cljs lane needs the node REPL build only if a selected test reaches it — yang.base's selection does not; the shadow compile itself is the check.)
2. No regression: `bb test:sub:clj yang.base` on a warm tree still passes with "antlr cache hit" or a correct run; a selection that reaches nothing ANTLR-related (e.g. `bb test:sub:clj dao.stream`) still prints the skip for gen:antlr.
3. The full-suite path (`bb test:clj` without the node build) must still behave as today — gen:antlr triggered only when the JVM selection reaches the artifacts.

## Focused checks

- Whatever harness exists for affected.clj (search `test` for its namespace); otherwise: `bb --eval`-style invocations of `affected.clj`'s `prerequisites` with constructed selections proving cases 2-3, recorded verbatim in the round report.
- clj-kondo on the changed file: zero errors, no new warnings.

## Report

Write findings to `collab/1793740000000-engineer-gen-antlr-prereq-heuristic.findings.md` (root cause, the exact diff, the acceptance-case evidence with real command output). Commit on the branch with a `fix(dev):` message; push; do not merge.
