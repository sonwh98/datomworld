Created-GMT: 2026-09-07 14:54:20 GMT
Created-Local: 2026-09-07 21:54:20 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
# Task: review P1 — dao.jing.file rebuilt without a stream — and two plan fixes it exposed
Role: Routine Reviewer (correctness, invariants, portability)
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-07 21:54:20 +07 | Status: active | Rationale: resumed session; it performed the invariant-completeness review these tests were written from, and is GPT-family, independent of the GLM implementer

**Code review of an uncommitted working-tree change**, implemented by
`glm-5.3`. Nothing is staged. Read the real diff:

```
git diff src/cljc/dao/jing/file.cljc test/dao/jing/file_test.cljc test/dao/data/btree_durability_test.cljc
git show HEAD:src/cljc/dao/stream/log.cljc     # deleted by this change
```

5 files, +536/−691: `dao.jing.file` rebuilt (213→366 lines),
`file_test.cljc` rewritten from the invariants (251→365, 13 tests),
`dao.stream.log` and its 7-test suite deleted, one comment fixed in
`btree_durability_test`.

## Context you already have

You reviewed the invariants list these tests were written from, and you lifted
the gate: "the list is now complete enough to delete the 54 old tests
against." P1 is the first phase to act on that. The old tests are gone; the
new ones were written from **F1–F6 and D1–D5**, not ported.

The implementer's own report is at
`collab/storage-jing-file-p1-r2.glm-5.3.findings.md` — read it. It is unusually
candid: its run was killed by the host mid-verification, it declares P1
"code-complete, Dart-unverified", it maps all 13 tests to invariants, and it
documents two bugs of its own and a rejected design alternative.

## Verification already done by the orchestrator — do not rerun

- `bb test:clj` 1430 / 165264, `bb test:cljs` 1349 / 34882 with
  `Testing dao.jing.file-test` confirmed, `bb test:cljd` 1294, **all green**.
- The delta is **−6 tests on every host**: −7 from the deleted `log_test`,
  +1 from `file_test` 12→13. Consistent across all three.
- `clj -M:kondo` clean on all changed files.
- The six cljd failures the implementer could not attribute before being
  killed were **orphaned generated Dart** for the deleted namespace, not its
  code; removing `test/cljd-out/dao/stream/log-test_test.dart` and
  `lib/cljd-out/dao/stream/log.dart` (both gitignored build output) makes the
  lane green.
- The torn-tail helper writes a valid record before the raw torn bytes, so
  the v1-fixture trap the plan warned about was avoided.

Spend your budget on the code and the invariants, not on reruns. You have no
authority to run tests.

## What to judge

1. **Does the new `file_test` actually cover F1–F6 and D1–D5?** You mapped the
   54 old tests to invariants; this is the other direction. Is any invariant
   now unpinned, or pinned more weakly than the old suite pinned it? The
   implementer names what it deliberately did not re-test — D1 and B1–B3 as
   `jing_test`'s, D4 as the plan's `[T✗]`, the E-group as P2's. Are those
   omissions right?
2. **The framing, chosen not inherited.** 4-byte big-endian signed length +
   UTF-8 EDN of `[address payload]`, no magic, no version header. The
   docstring argues it and rejects a header on the grounds that the canonical
   encoding lands by changing every minted address wholesale. Is that
   reasoning sound, and is the framing correct on all three hosts —
   particularly the signed length, the EDN read, and byte handling in the
   Dart branch?
3. **The three host branches.** `RandomAccessFile`, Node synchronous `fs`,
   `dart:io`. Every reader conditional carries all three arms — check that,
   and check for the `#?(:clj …)`-without-`:cljd` form, which does *not*
   exclude code from the cljd build.
4. **F3's truncation code**, which is the only thing standing between a torn
   file and a fail-closed open: the sub-prefix tail, the negative length, and
   the length past EOF, on each host.
5. **Anything the diff shows that the implementer's report does not explain.**

## Two plan fixes to rule on

The plan is committed at `5898bfb`
(`docs/design/dao.jing.implementation-plan.md`). Both of these came out of
implementing it, and I would rather have your judgement than write them myself.

- **F2's collision case may be unreachable by construction.** F2 says "an
  unequal record at an existing address is a collision and the open fails."
  The implementer reports it cannot be reached through valid frames: B6 forces
  a frame's address to hash its payload, so two *individually valid* frames at
  one address are necessarily equal, and the second frame's hash check (F4)
  fires first. The check is retained as defensive depth. Is that right? If so,
  F2's wording asserts a testable property that is not testable, and should
  say what the implementer's test says instead.
- **Deleting a namespace orphans its generated Dart.** `test/cljd-out/` is
  generated per namespace and is not cleaned when a source namespace
  disappears, so the lane keeps running stale compiled code and reports
  failures for code that no longer exists — exactly what cost this phase a
  verification round. P2 deletes more namespaces. Should the plan's deletion
  steps carry an explicit instruction, and where?

Report `P0-P3 | file:line | evidence | concrete fix`, or "no actionable
findings", then the two plan rulings. Say plainly whether P1 is ready to
commit.

Do not edit any file. Produce the complete response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
