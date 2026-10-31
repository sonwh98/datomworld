Created-GMT: 2026-10-05 14:26:11 GMT
Created-Local: 2026-10-05 21:26:11 +07
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

# Task: head-h0 (round 3: the judge crash found in review)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 21:05:56 +07 | Status: active | Rationale: same engineer, resumed to fix a P1 an independent reviewer found in its code

## The finding (reproduced by the orchestrator)

An independent reviewer (gpt-6.1-sol) reviewed your H0 code
(`collab/1791210056503-reviewer-head-h0.gpt-6.1-sol.findings.md`). One P1:

`src/cljc/yin/vm/linker/head.cljc` (about line 135): `judge` destructures the
envelope before `well-formed?` has checked it. With
`{:yin.head/envelope '(1 2 3) :yin.head/proof {:yin.head/signature <128 hex>}}`
the binding throws `IllegalArgumentException` on the JVM instead of answering
`:yin.head/malformed`. The orchestrator reproduced it and bounded it: an
ODD-LENGTH list (a seq) envelope throws, because map destructuring of an
odd-length sequence is `(apply hash-map ...)`; an even-length list, a vector, a
number, a string, `nil` and every other odd shape already answer
`:yin.head/malformed`, and `verify` never throws. Your malformed-case tests omit
sequential envelopes.

This matters beyond tidiness: a trace comes off a channel, so `judge` must never
throw, whatever the bytes decode to. The design records the new requirement in
H0's criteria ("Neither `judge` nor `verify` ever throws", including odd-length
lists) in `docs/design/yin.vm.linker.dht.head.md` section 11.

## What to do

- Fix `judge` (and any other entry point that destructures an input before
  validating it: check `verify`, `sign-trace` / `verify-trace` in `sign.cljc`, and
  `trace`) so that no shape of input can make `judge` or `verify` throw. Validate
  with `map?` (and the closed key sets) BEFORE any destructuring or `get`-ing of
  nested values, for the trace, the envelope and the proof alike. A host that
  decodes a map as something other than a `map?` must also answer malformed.
- Add regression cases to `test/yin/vm/linker/head_test.cljc`, portable on all
  hosts: a trace whose envelope is, and separately whose proof is, each of: an
  odd-length list, an even-length list, a vector, a number, a string, `nil`, a set,
  a keyword; and a non-map trace (nil, a number, a vector, a list, a string). Assert
  `judge` answers `:yin.head/malformed` and `verify` answers `false` for each, and
  that none throws. Keep a case that would have failed before the fix (the
  odd-length list envelope).
- Do not weaken any test. Do not change the outcome of any case that already
  worked.

## Scope and process rules (unchanged)

Files: `src/cljc/yin/vm/linker/head.cljc`, `src/cljc/yin/vm/linker/sign.cljc` (only
if the audit above finds an unvalidated destructure there) and
`test/yin/vm/linker/head_test.cljc`. No git commands, no formatter, no Node or Dart
runs, no background processes. Verify in the foreground:
`clj -M:test -n yin.vm.linker.head-test -n yin.vm.linker.sign-test` and
`clj -M:kondo --lint` on the touched files, with assertion counts. Portable `.cljc`
and the ClojureDart traps from the first brief still apply (note: ClojureScript and
ClojureDart destructure differently from the JVM, so do not rely on a JVM-specific
exception type; validate first).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

Then report: what you changed and why each entry point is now safe, the exact
commands and outcomes with assertion counts, the regression case list, and anything
unresolved.
