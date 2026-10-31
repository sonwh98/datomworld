Coding-Agent: claude
Model: claude-fable-5-1
Role: Architect
Completed: 2026-10-20
Task: Record the F0 baseline-exceptions amendment in docs/design/dao.stream.codec.md §7.3

## Decision

Amendment APPROVED and recorded. The frozen fixture suite is now defined as two
parts per profile, in separate files, so the strong claim (clean corpora decode
everywhere and CBOR re-encodes byte-exact) and the weak claim (baseline
exceptions keep their recorded per-host outcome) are never asserted by one test.

## What changed

One edit, in place, to docs/design/dao.stream.codec.md §7.3. The paragraph that
began "The fixture test, run on every slice from F0 on" (formerly line 754) is
replaced by:

1. Clean corpora (`envelopes.edn`): must decode on every lane under the §4.4
   equality rule; CBOR entries must re-encode to the fixture bytes exactly.
2. Baseline exceptions (`exceptions.edn`): pre-change cross-host divergence and
   domain incompatibilities present in master@212c45dd before any slice, with
   the exact per-host outcome (`:decodes` / `:throws`) recorded and asserted:
   - Transit list and nested list: JVM throws ("outside portable domain"),
     Node and Dart decode to a vector.
   - CBOR 1.0 (`f93c00`): JVM and Dart decode; Node rejects as non-canonical
     (its encoder emits `01`).

The text states explicitly that exceptions pin pre-existing behaviour against
silent change in either direction and claim nothing about round trip, and that
fixing an exception is a separate, named behaviour change that moves the entry
to `envelopes.edn`.

No other section was touched. The claims table and the "what this does and does
not claim" prose remain valid: the CBOR byte-level claim already said "holds for
the fixture corpus", which now reads as the clean corpus.

## Verification against the worktree

Checked the actual fixture files in /Users/sto/workspace/datomworld-codec-f0:

- test/fixtures/codec/transit/exceptions.edn: `list` and `nested-list`, clj
  :throws, cljs :decodes, cljd :decodes. Matches the doc.
- test/fixtures/codec/cbor/exceptions.edn: `integral-double-one` ending in
  f93c00, clj :decodes, cljs :throws, cljd :decodes. Matches the doc.

## Checks requested by the brief

- `grep -ci jolt docs/design/dao.stream.codec.md` = 0
- em dashes in the file = 0
- lines over 170 columns = 0

## For the orchestrator

The reviewer's only open item (P1 acceptance amendment) is now closed. Per the
reviewer's recommendation: commit the thirteen test files from codec-f0 together
with this doc edit, tag and record the F0 old-peer commit as §7.1 requires, then
run the post-merge G3 gate. Do not include worktree collab reports in the commit.
