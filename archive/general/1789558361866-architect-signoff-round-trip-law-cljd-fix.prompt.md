Created-GMT: 2026-09-16 11:32:41 GMT
Created-Local: 2026-09-16 18:32:41 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: e1ffeba8-b74a-432c-b926-d6f718966d36

# Task: Architect sign-off on the ClojureDart content-addressing round-trip-law fix

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 18:32:41 +07 | Status: active | Rationale: architecture sign-off gate before commit, per this branch's convention tonight

Perform a read-only architecture review of the current uncommitted
working-tree diff.

Read first:
- `docs/design/datom.world.md`
- `docs/design/dao.jing.md` (especially the new Open Items entry)
- `git diff src/cljc/dao/jing.cljc src/cljc/yin/vm.cljc
  src/cljd/dao/stream/transit/cljd.cljd src/cljd/dao/stream/transit.cljd
  docs/design/dao.jing.md test/dao/jing_test.cljc test/yin/vm_test.cljc
  test/dao/stream/transit_test.cljc test/dao/stream/transit_test.cljc`
- `collab/1789556877627-review-round-trip-law-cljd-fix.gpt-6-astra.findings.md`
  and `collab/1789556877627-review-round-trip-law-cljd-fix-r2.gpt-6-astra.findings.md`
  (an independent adversarial review, two rounds — r1 found a real gap
  (two unfixed Transit decoder sites leaking the same defect onto a live
  wire-decoding path), r2 confirms the follow-up fix closes it. Treat both
  as claims to verify, not authority.)

## Context

Root cause: `test/yin/vm_test.cljc`'s `semantic-bytecode-round-trip-law`
deftest passed on JVM but failed on ClojureDart. Diagnosis: ClojureDart's
`(list ...)`/`(apply list ...)` mints a list carrying `cljd.core`'s own
reader metadata (`{:line … :tag PersistentList …}`), unlike JVM Clojure's
`list`. This leaked into `dao.jing`'s content-address hash at four
independent call sites that all construct a list via `apply list`:
`dao.jing.cljc`'s canonical encoder (`order-normalize`), `yin.vm.cljc`'s
`strip-reader-positions`, and two Dart Transit wire-decoders
(`dao.stream.transit.cljd` — live, behind `dao.stream.ws`'s incoming
frames — and the older `dao.stream.transit`). All four are fixed with
`(with-meta (apply list ...) nil)`, clearing only the newly-minted
wrapper's own metadata while preserving element and transmitted metadata.

Verified locally by the orchestrator (not just trusted from delegate
reports), at each stage: `clj -M:kondo --lint` on every touched file (zero
NEW errors — pre-existing cross-host `.cljd` lint noise independently
confirmed unchanged via `git show HEAD:<file>` comparison). `clj -M:test`
on `dao.jing-test`, `yin.vm-test`, `dao.stream.transit-test`,
`dao.stream.transit-test` → 0 failures, 468 assertions. `bb test:cljd`
(full suite, run twice — once after the first two fixes, once after the
Transit follow-up) → the round-trip-law failure is gone after the first
pass; after the follow-up, 1341 tests run with exactly one unrelated,
pre-existing failure remaining (`yin.repl.core-test/a-failed-input-is-
consumed-exactly-once`, being investigated separately, out of scope here).

## Task

Evaluate foundational invariants, ownership boundaries, explicit state and
control flow, host isolation, CLJ/CLJS/CLJD portability, migration risk,
completion criteria, and design contradictions — specifically:

1. Is fixing four independent call sites (rather than centralizing the
   `apply list` + metadata-clear pattern into one shared helper) the right
   architectural call, or does this create a maintenance hazard — a fifth
   site doing the same thing in the future without the same fix? Weigh
   against "don't reimplement the same abstraction multiple times," the
   governing theme of tonight's `dao.data` work. Should there be a shared
   helper, or is four independently-commented sites acceptable given each
   one already had good reason to construct a list its own way?
2. Does the `dao.jing.md` Open Items entry correctly and completely scope
   the residual risk (any Dart code that builds a payload with `list`
   outside these four fixed sites still leaks the defect)? Is that residual
   risk acceptable to leave open, or does it need a stronger guard (e.g. a
   lint rule, a runtime assertion) before this is truly closed?
3. CLJS portability was flagged by both review rounds as unverified (`bb
   test:cljd` only covers Dart). Is that an acceptable gap for this fix, or
   should CLJS verification happen before commit?
4. Anything else — concurrency, addressing-model integrity implications
   (this bug affects content-address hashing, the foundation §4 of
   `yin.vm.code-as-tuples.md` depends on), or migration risk given other
   uncommitted work on this branch.

Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction. Also confirm the requested properties that passed review. End
with an explicit APPROVE / APPROVE-WITH-FINDINGS / REJECT verdict — this
governs whether the orchestrator is authorized to stage and commit this
diff.
