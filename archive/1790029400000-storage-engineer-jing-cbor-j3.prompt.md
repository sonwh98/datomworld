Created-GMT: 2026-09-21 23:55:00 GMT
Created-Local: 2026-09-22 06:55:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: jing-cbor-j3: the three-host conformance gate for the Jing CBOR codec
Role: QA / Verification Engineer (implementing the gate; you did not write the codec)
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 06:55:00 +07 | Status: active | Rationale: the architect's routing table assigns J3 to Sonnet 5, a fresh implementer distinct from the J0-J2 author (claude-opus-5), so the gate is not written by the codec's author

You are a fresh implementer with no prior context. Work ONLY in the git worktree
/Users/sto/workspace/worktree-jing-cbor (branch jing-cbor; your launch directory).
J0 (f5f71e95), J1 (7968884b, 4f928dbd) are committed and architect-signed; the J2 Dart
work is present but UNCOMMITTED in the working tree (the orchestrator commits it
before or after your work; do not stage it). Do NOT stage, commit, merge or push.

## What exists (read these first)

- docs/design/dao.jing.cbor.md ("Encoding contract", "Numeric identity", "Implementation
  sequence and validation").
- The frozen corpus: test/resources/dao/jing/cbor-v1.json (372 cases), cbor-v1.README.md
  (rulings A1-A17), cbor-v1.errata.md (E1-E6; E1-E5 ratified, E6 the Dart column pending
  ratification), cbor-v1.generate.py (independent generator; never edit these).
- The codec (shared by JVM, Node and Dart): src/cljc/dao/jing/cbor.cljc, cbor/boring.cljc,
  src/cljd/dao/jing/cbor/cljd.cljd. Existing tests: test/dao/jing/cbor_test.cljc (the
  per-host corpus runner), cbor_fixtures.cljc (loader and input builders),
  cbor_fixtures_test.cljc (frozen self-tests; never edit).
- The architect's J3 spec: collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md
  section J3 (read it; restated below).

## Your task

The per-host runner already checks each host against the resource. J3 must prove the
THREE hosts agree with each other and with an independent reading of the bytes, so
three internally consistent but mutually incompatible implementations cannot pass.
Deliver, as NEW test files only (file box: test/dao/jing/cbor_conformance_test.cljc
and, if a Dart-only branch needs its own file, test/dao/jing/cbor_conformance_test.cljd;
at most test-only helper additions to test/dao/jing/cbor_fixtures.cljc; NO production
edits, NO edits to any frozen file, no deps.edn/bb.edn/pubspec edits):

1. **Per-host manifest keyed by fixture id.** Each host builds every case it can from
   the shared resource and records, per id: canonical hex, SHA-256, and a normalized
   semantic signature (design it: for example the outcome kind plus, for accepted
   values, a host-neutral canonical description obtained WITHOUT calling the codec's
   encoder, and for refusals the frozen class). Each host must assert equality with the
   immutable resource for every case it runs, and the manifest must enumerate the
   cases it skipped by id with the documented reason (errata E1/E6, README). A
   conformance test then asserts: every one of the 372 cases is covered by at least one
   host, the union of documented skips per host matches errata E1 and E6 exactly, and the
   canonical cases run on all three hosts except the documented skips.
   To compare hosts with each other, not merely each with the resource, have every host
   compute one manifest digest (SHA-256 over the sorted `id \t hex \t sha \t signature`
   lines for the SAME set of cases: the canonical cases run on all three hosts, i.e.
   minus the union of documented canonical skips) and assert it equals a value derived
   from the resource, so a disagreement between any two hosts fails a lane. Print the
   digest on each host so the orchestrator can compare the three logs by eye too.
2. **Independent raw CBOR-tree inspection.** A small test-only CBOR item walker
   (independent of the codec; it may share nothing with `dao.jing.cbor`; keep it host-
   neutral) that, on the bytes each host PRODUCES, confirms: tag/name/payload shape of
   every tag 27 frame (exactly `[name-string payload]` with one of the five names),
   definite lengths and shortest heads, no native floats (major 7 ai 25/26/27),
   no tag 39, and unsigned bytewise ordering (proper prefix first) of every map's keys
   and every tag 258 set's elements. It must NOT call the Jing decoder.
3. **Pairwise injectivity and equivalence groups** hold on the bytes each host produces
   (the per-host runner has similar checks; re-assert them in the conformance
   namespace over the manifest so a regression in any one host is named).
4. **Immutability.** A test that the resource file is byte-identical to its digest
   recorded in the README or JSON header (or an equivalent check that does not depend
   on the codec), and that no test namespace writes to the resource (grep-style checks
   are acceptable as a static test on the JVM).
5. Wire the new namespace into the existing lanes so `clojure -M:test -n
   dao.jing.cbor-conformance-test`, `bb test:cljs` and the CLJD lane all run it (check how
   test namespaces are discovered; :cljd needs its own discovery rules, see the CLJD
   traps below).

## Environment and lanes

Kondo, cljstyle and the CLJD lane are available ONLY through three wrapper scripts
(each run ALONE as one simple command; no pipes, no `&&`, no env prefixes):
- /private/tmp/claude-501/-Users-sto-workspace-datomworld/a9895f5c-8978-4964-9887-e3f9f05e8caf/scratchpad/lanes/lint.sh   (`lint.sh fix` first, then `lint.sh`)
- .../lanes/cljd-fast.sh  (iteration; `clojure -M:cljd test` only)
- .../lanes/cljd.sh       (the full lane; run once at the end)
You are the single owner of the CLJD lane and its generated output while you work.
The JVM (`clojure -M:test -n dao.jing.cbor-conformance-test -n dao.jing.cbor-test
-n dao.jing.cbor-fixtures-test`) and the Node lane (`bb test:cljs`) you may run
directly; the default PATH gives Java 21 and the mise clojure and bb. Full logs of the
wrapper scripts are written next to them (cljd-last.log, cljd-fast-last.log).

CLJD traps: `#?(:cljd X :clj Y)` with :cljd FIRST; a failing `is` THROWS on Dart, so
collect failures into a per-test list and assert once (see the `guarded`, `check!`,
`assert-none!` helpers in cbor_test.cljc); a Dart list literal is refused by
dao.jing.file; keep files pure ASCII, no em dashes, cljstyle-style Clojure.

## Never

Never edit the corpus, README, errata, generator or cbor_fixtures_test.cljc. If a
frozen expectation looks wrong, stop and report it. Never report a count you did not
observe. If a host cannot run something, say exactly which command was denied.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
then: files created; the manifest and digest design; how each of the five points is
met; the digest value printed by each of the three hosts; skipped ids per host; what
you ran with exact counts; what you could not run; every deviation. Facts only.
