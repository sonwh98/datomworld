Created-GMT: 2026-09-23 18:58:44 GMT
Completed-Local: 2026-09-24 01:58:44 +0700 ICT
Coding-Agent: codex
Session-ID: (provider-generated, see stdout.log)

# Findings: Architect Final Sign-Off — De Bruijn Type Preservation

Role: Lead System Architect
Model: gpt-6-astra

## Verdict: APPROVED

All three prior findings are resolved. No new P0-P3 findings.

## Finding 1 — Resolved

`canonical-value-table` contains no `:javascript` policy. Safe-integer
classification resides exclusively in the CLJS-guarded `js-number-class`.
The updated descriptor hash passes its pinned fixture.

## Finding 2 — Resolved

The reader checks ambiguous integral numbers before throwing generic
`:hash-mismatch`. Recursive detection covers map keys, values, and
collections. Valid hashes still pass; unverifiable records remain rejected.
Bare and nested foreign-double fixtures pass.

## Finding 3 — Resolved

The worktree design document consistently uses `:dim/contract-version`; the
incorrect keyword `:yin.debruijn/contract-version` is absent.

## Additional Confirmations

- Pipeline assertion (pipeline_test.cljc:552) widened to accept
  `:unsupported-value` is architecturally justified. In-memory reproduction
  confirmed that the JS text codec converts `-0.0` to `0`, triggering the
  ambiguity refusal. Exact-round-trip requirements remain enforced for
  supported hosts.
- Contract version remains 1.
- Blog no longer claims numeric lossiness by design.
- Projection remains separate from execution; no new architectural concerns.

## Validation

JVM: 79 tests / 358 assertions, all passing.
Node: 79 tests / 337 assertions, all passing.
(Dart and file-writing pipeline tests not rerun under read-only review.)

No files edited.
