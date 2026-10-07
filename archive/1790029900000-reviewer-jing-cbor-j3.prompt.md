Created-GMT: 2026-09-22 00:20:00 GMT
Created-Local: 2026-09-22 07:20:00 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max) | deepseek (shared brief)
Session-ID: pending
# Task: jing-cbor-j3: independent adversarial review of the three-host conformance gate
Role: Adversarial Review (Storage & Encoding, QA)
Implementers:
- Model: qwen/qwen3.8-max or deepseek-v4-pro | Assigned: 2026-09-22 07:20:00 +07 | Status: active | Rationale: the gate was written by claude-sonnet-5 (Claude family); reviewers must differ; both owner-authorized

Read-only. Work ONLY in /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor,
HEAD 4f928dbd; J2 and the J3 file are UNCOMMITTED in the working tree). Edit or create
nothing; do not run tests (the orchestrator ran all lanes: JVM, Node, Dart green, kondo
0/0). You may read files and run read-only git commands. Give the complete review now
as your final response; do not wait for approval.

## What you are reviewing

test/dao/jing/cbor_conformance_test.cljc (new, about 940 lines) and the one added
function `read-path` in test/dao/jing/cbor_fixtures.cljc (`git diff`). The
implementer's report: collab/1790029400000-ref-implementer-j3-report.md. The architect's
J3 spec: collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md
section J3 (completion criteria: every host reads the ONE shared corpus; identical bytes
and digests across JVM, Node and Dart for every supported case; an independent raw
CBOR-tree inspection of tag/name/payload shape and unsigned bytewise ordering WITHOUT
calling the Jing decoder; pairwise injectivity and equivalence groups on all hosts; no
test can rewrite the fixture resource). Also read the frozen resource README, the
ratified errata E1-E5 and the pending E6 (test/resources/dao/jing/cbor-v1.errata.md).

## Judge (cite file:line and case ids)

1. **Independence of the walker.** Does it share ANY code, table or helper with
   dao.jing.cbor or the Boring/cbor adapters? Is it correct: shortest heads (including
   the 24, 256, 65536, 2^32 boundaries), definite lengths, native-float detection (major
   7 ai 25/26/27, and 20/21/22/23), tag 27 frame shapes and the five names, tag 258
   ordering, unsigned bytewise ordering by comparing HEX TEXT (is that equivalent for
   proper prefixes and all byte values?), bignum/decimal/ratio shapes, with-meta rules.
   Mutation thinking: name five plausible codec bugs (for example a signed-byte sort, a
   non-shortest head, a native float slipped in, a tag 39 identifier, a set out of
   order) and say whether the walker or the digest would catch each.
2. **The cross-host comparison is real.** The canonical digest (208 cases) and the
   refusal digest (154 cases) are asserted against a value derived from the resource AND
   a pinned literal on each host. Could three hosts each pass while disagreeing with each
   other (for example: the signature field derived per host in a host-dependent way; the
   digest computed over a different set on one host; a host silently dropping a case
   from the sorted lines)? Is the "same set of cases" for the digest derived
   identically on all hosts from the documented skips, or could a host-specific skip
   change the set undetected?
3. **Skip accounting.** The per-host skip lists match errata E1 and E6 and are parsed
   from the errata file. Verify against the errata text. The implementer's FINDING:
   `coll/set-vector-list-collapse` is N/A on JVM, Node AND Dart on the encode side, so it
   runs on no host; only its decode twin `mal/collapse-vector-list-set` covers the
   class. Is the claim right? Is it acceptable, or should a host-independent construction
   (for example building the set through the wire-node level) cover it? Give a
   recommendation.
4. **Immutability tests.** The pinned SHA-256 of the corpus file
   (c8f5ef38124110bca48d0c7e5596eef00028ecc66c7d032e4e12bb1477f97351) is asserted on all
   hosts; the JVM-only static scans fail if a file naming the corpus also contains a
   write/delete/rename/copy marker. Are the scans robust or trivially bypassed or
   over-broad (false positives on comments, on the generator)? Can any test still
   overwrite the resource?
5. **Portability of the test code.** Dart traps (a failing `is` throws: are failures
   collected and asserted once; list literals; BigInt), Node traps (file read path), JVM
   only sections correctly gated with `:cljd` FIRST.
6. **Safety.** No production edits; frozen files untouched; ASCII only.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <cmd|deepseek>
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1 (the gate can
pass while hosts disagree, or the resource can be overwritten), P2 (significant gap),
P3 (minor), each with file:line and the smallest fix; your recommendation on item 3;
what you checked and found clean. Findings only.
