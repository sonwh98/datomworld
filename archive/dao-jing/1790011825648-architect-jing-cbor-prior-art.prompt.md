Created-GMT: 2026-09-21 17:30:25 GMT
Created-Local: 2026-09-22 00:30:25 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed — your jing-cbor work-package turn)
# Task: architect follow-up — dao.stream.cbor already exists; revise the work package
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:30:25 +07 | Status: active | Rationale: same architect continues the same subject (resume rule); the owner supplied a fact that changes the work package

Read-only. Work in /Users/sto/workspace/datomworld (master, 0dc06197). Give the
complete revised deliverable now; do not wait for approval and do not promise
one. Edit no file.

## New fact from the owner

The owner said: "I thought that dao.jing.cbor was already implemented." The
orchestrator checked. Verified on master and origin/master:

- `docs/design/dao.jing.cbor.md` is still only a plan (its own status:
  "implementation plan; not yet implemented"). No `dao/jing/cbor` source or
  test exists on any local or remote branch. Jing still hashes through the
  transitional print encoder (`canonical-print` in src/cljc/dao/jing.cljc).
- BUT a sibling CBOR implementation IS built and merged:
  - `dao.stream.cbor` (src/cljc/dao/stream/cbor.cljc, cbor/boring.cljc,
    src/cljd/dao/stream/cbor/cljd.cljd; tests test/dao/stream/cbor_test.cljc
    and .cljd), commit 4cdd924e "U10a: Stream Codec Parameterization &
    dao.stream.cbor". It is "the DaoStream v2 CBOR wire codec: the binary twin
    of dao.stream.transit". Boring 0.1.30 is pinned in deps.edn and the pub.dev
    `cbor` package 6.5.1 in pubspec.yaml; the Dart wrapper is "no hand-rolled
    binary parser", re-encodes and byte-compares on decode to catch duplicate
    map keys.
  - Its docstrings state it is deliberately NOT Jing's profile: no identifier
    component frames, no :line/:column stripping, no canonical re-encoding for
    content addressing, no numeric carriers beyond safe portable numbers. It
    does provide: Boring `:canonical` with string references, shaped arrays and
    index frames disabled; tag 39 identifiers, tag 258 sets, the
    `clojure/with-meta` tag 27 frame for metadata, a named `dao.stream/list`
    frame keeping lists distinct from vectors, host bytes as CBOR major type 2.
  - `dao.jing.stream` (src/cljc/dao/jing/stream.cljc, test/dao/jing/
    stream_test.cljc) is Jing's boundary adapter and rides Jing's "canonical
    bytes" as a CBOR byte string on the dao.stream.cbor profile
    (docs/design/dao.jing.md around line 475).

The cbor tests already include a frozen-fixtures decode/re-encode test, a
determinism test, refusal of non-canonical and malformed frames, list/vector
distinction and byte strings. Verify all of this yourself before relying on it.

## What I need (a REVISED deliverable; keep it to steps 1-2 of the plan)

1. **Overlap map.** For each deliverable in the plan's steps 1-2 (Boring
   dependency and pin, JVM/JS wrapper, Dart codec and carriers, normalization,
   named extensions, numeric constructors, frozen fixtures and how they are
   frozen, proof of Boring's effective options, cross-host fixture exchange),
   say: ALREADY EXISTS in dao.stream.cbor (cite file), REUSABLE as is, NEEDS
   Jing-specific work, or NOT STARTED. Be precise about what "canonical bytes"
   dao.jing.stream carries today, since it rides on an encoder that is still the
   print encoder.
2. **Architecture ruling.** Should the Jing profile (`dao.jing.cbor`) extend or
   compose dao.stream.cbor's Boring/Dart wrappers, or be a sibling namespace
   that shares only small helpers? Weigh the owner's minimal-diff and
   reuse-libraries preferences against the risk of two subtly different
   "profiles" of the same bytes (for example `dao.stream/list` vs `dao.jing/
   list`, and the plan's identifier component frames vs Boring's native tag 39).
   State the invariants that must hold between the two (what a stream consumer
   and Jing must agree on) and where a divergence would be a bug.
3. **Revised phase split, file box, completion criteria and verification lanes**
   for steps 1-2, replacing the earlier proposal where reuse changes it. Keep
   the additive constraint: no behaviour change to existing Jing, no backend, no
   dao.space, no remote/DHT change.
4. **Dart route.** With dao.stream.cbor as prior art, confirm or change your
   earlier Dart ruling.
5. **Doc status problem.** The owner's belief that dao.jing.cbor was implemented
   suggests the plan's "not yet implemented" header, or the surrounding docs, do
   not say clearly that the STREAM codec landed while Jing's STORAGE encoding did
   not. Say whether dao.jing.cbor.md's status needs a sentence that separates
   them (give exact 80-column wording; do not apply it) and whether the plan's
   step list should now reference dao.stream.cbor as landed prior art.
6. **Gate checklist and routing.** Update your owner-gate checklist and model
   routing from the first turn if any of it changes (the gates and the
   rebuild-readiness question are unchanged unless you say otherwise).

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d
then the six numbered sections. Findings and plan only; edit no file.
