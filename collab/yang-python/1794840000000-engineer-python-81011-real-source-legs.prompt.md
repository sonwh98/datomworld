Created-GMT: 2026-11-06 05:50:00 GMT
Created-Local: 2026-11-06 12:50:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: S11-python — the §8.10.11 real-source legs for the Python frontend

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-06 12:50 +07 | Status: active | Rationale: one bounded conformance-evidence round in the Python frontend's test surface

WORKTREE: /Users/sto/workspace/datomworld-f12 (at da883518 == origin/master; ANTLR
parsers generated; JVM lane is the only runnable lane here — the Node/Dart lanes are
compile-broken on master by a6c25106, unrelated to this round; JVM-only is fine, the
portability gate is NOT this round's job). Commit to a new branch `s11-python-legs`.
You are the only writer.

## The requirement (docs/design/yang.antlr.md §8.10.11)

Conformance evidence executes REAL lowered source bodies, not synthetic functions with
the expected slot. Composition must be explicit published-export composition: a harness
may compose published exports and namespace projections explicitly; it must NOT rewrite
call nodes, extract foreign `:code`, or insert test-local language-pair wrappers.

Current state:
- `test/yang/call/conformance.clj` already has a `:python` profile (runtime `py`,
  `py/run-main`, the `__main__` module-dict export reader, `yin/def` projection). Donor
  lowering exists (`py-parser/parse-source`, `python-unit`).
- `test/yang/typescript/antlr/source_call_test.cljc` is the S11 template: real source of
  one frontend composed through the shared convention into another, no rewrites.
- `test/yang/python/antlr/call_entry_test.cljc` is the ONLY python call surface today and
  its docstring disclaims conformance status explicitly. That is the gap.

## What to do

Build `test/yang/python/antlr/source_call_test.cljc` (mirroring the TS S11 structure,
reusing the shared fixtures where they are genuinely shared — do not copy TS-specific
fixtures):

1. **Python donor**: a real Python source module (def + export) published through the
   `:python` profile's composition, called from another admitted frontend's real source
   caller. Start with the pair the TS test already proves works (PHP or TS caller), so
   the composition machinery is known-good and the new variable is Python alone.
2. **Python caller**: real Python source calling another frontend's published export
   (same counterpart as (1), reverse direction).
3. **Higher-order leg** (§8.10.11): pass a foreign function into Python source, have the
   Python body call it back; assert identity and captures. The clj_apply-style matrix
   legs in `test/yang/call/conformance.clj` show the composition shape.
4. **Opaque-value round-trip leg**: foreign object/function/string/float64/exact-integer
   through Python and back unchanged; assert aliasing and no silent conversion.

§8.10.11 required evidence is wider (exceptions, references, context lifetime,
publication/custody, ...). Do NOT try to cover everything — this round is the first
legs: direct calls both directions + higher-order + opaque round-trip. Record in your
report which §8.10.11 evidence rows remain uncovered for Python, so the next round
picks up from a written list.

## Rules

- Real source only: the Python bodies are Python text lowered via the profile's
  `:parse`/`:unit` path; the counterpart bodies are that frontend's real source.
- No edits outside the new test file + genuinely-shared fixture files it requires.
  If a composition primitive is MISSING in `yang/python/antlr/**` runtime code (e.g. a
  prelude function the projection needs), report it with file:line and STOP that leg —
  runtime changes are a separate decision, do not add them unilaterally.
- JVM lane only. Run: `clj -M:test -n <your namespace>` (the `:test` alias carries
  `-Xss64m`; use `mise exec java@21` if the default JVM trips the UnsupportedClassVersionError
  on shadow deps — plain clj test lanes do not load shadow, so bare `clj -M:test` should work).
- No master work; the branch only.

## Report

Final agent message: what legs ran and their counts, the composition mechanics used per
leg, any missing runtime primitives found (file:line), and the explicit list of
remaining §8.10.11 evidence rows for Python.
