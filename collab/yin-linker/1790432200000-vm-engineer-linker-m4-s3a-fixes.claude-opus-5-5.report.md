Completed-GMT: 2026-09-26 11:20:35 GMT
Completed-Local: 2026-09-26 18:20:35 +07
Created-GMT: 2026-09-26 11:50:00 GMT
Created-Local: 2026-09-26 18:50:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Report: S3a, codex gate findings 2 and 3 on M4 slice S3

Worktree: /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted).
Only findings 2 and 3 are addressed. The private `:resources` table and
sealed references (P0) are S3b and were not touched. The orchestrator's
ClojureDart `runtimeType` patch in `engine.cljc` (now line 478) is kept.

The clocks here disagree: `date` reported 11:20:35 GMT when I finished,
which is earlier than the brief's Created-GMT of 11:50:00. I have
recorded the time `date` gave.

## Finding 2 (P1): an install publication defect now refuses the install

In `src/cljc/yin/vm/engine.cljc`, `advance-install` now runs
`link-install` inside a catch. `link-install` covers validation, lift,
the act-2 origin check, attaching origin images, lowering the slice and
the store snapshots, and receiving dependencies.

A throw from any of those stages is turned into a refusal by
`refuse-install`. It is built from the state the round started with, so
nothing half-published remains. The refusal is `error-refusal` with
`:phase :linked` added:

- `:status :refused`;
- the error's own `:reason` if it has one, otherwise `:install-error`;
- the error's ex-data and message.

After such a refusal:

- the install entry is removed;
- every `:install` waiter is restored with the refusal, which the program
  sees as the require's error;
- no throw escapes the scheduler round.

Lift failures were already refused inside `link-install`, and still are.

## Finding 3 (P1): register offset-table validation

In `src/cljc/yin/vm/debruijn_register_effects.cljc`, the new private
`table-defect` requires:

- every row to be a 3-vector `[identity offset length]` with
  non-negative integers;
- the rows to be in offset order, contiguous, and non-overlapping, with
  the first at 0;
- the rows together to cover every instruction.

Its defects are:

| Defect | Meaning |
|---|---|
| `:image-table` | the table is malformed |
| `:image-row` | a row is out of place, naming that row |
| `:image-coverage` | instructions are left uncovered, with `:covered` and `:length` |

`code-space-defect` now does the following whenever a payload carries
`:images`:

1. Runs the table check.
2. Runs the whole-segment check, skipping only a `:terminator` defect.
3. Checks each image slice on its own. Every row's identity is checked
   against the slice, including zero-length rows (the empty base image).
   The structural check runs only for rows of positive length.

`continuation-defect` still reports all of these under the outer rule
`:continuation-segment`, keeping the detail keys.

## Tests

- `test/yin/vm/attach_image_test.cljc`:
  `register-entry-table-must-cover-the-code-space-exactly-test` covers:
  - a missing row: rejected, `:covered` equals the base length;
  - an overlapping row: rejected, naming the row;
  - reordered rows: rejected;
  - a forged zero-length row identity: rejected, while the genuine empty
    base row is accepted.
- `test/yin/vm/linker_require_test.cljc`:
  `a-publication-defect-refuses-the-install-test`. It uses a `FaultyTask`
  test kernel that delegates to the stack kernel and throws at one chosen
  stage. It drives the root through `engine/check-wait-set` alone and
  injects a failure at each stage:
  - attaching the origin image;
  - lowering the slice;
  - lowering a store snapshot (the export is a non-closure, so the first
    lower happens in the store);
  - receiving a dependency (the attach of `bar`'s image fails).

  For each stage it asserts:
  - the round returns;
  - `:installs` is empty and no `:install` waiter remains;
  - the woken entry carries `:link-refused` with `:phase :linked`,
    `:reason :injected`, and the stage;
  - neither `foo` nor `bar` is published and `:module-stores` is empty;
  - running the task raises `:injected`.

I wrote the tests after the fixes, so I did not see them fail against the
old code. Without the new catch, the injected throw would escape
`check-wait-set` and the test would error. Without the coverage check, the
missing-row, overlap, and reorder cases would pass validation.

## Checks run

- **JVM, touched namespaces only.** 136 tests / 926 assertions, 0
  failures, 0 errors:
  - `linker-require-test` and `attach-image-test`: 40 tests / 508
    assertions (rerun after cljstyle);
  - `debruijn-register-effects-test`, `debruijn.register-test`,
    `engine-test` and `debruijn-register-contract-test`: 72 tests / 418
    assertions.
- **clj-kondo** (via `mise exec -- clojure -M:kondo`) on the 4 touched
  files: 0 errors, 0 warnings.
- **cljstyle check**: clean, after `cljstyle fix` on the two test files.
- **ASCII and 80 columns** on all added lines: 0 violations.

## Files changed in this pass

- `src/cljc/yin/vm/engine.cljc`: `advance-install` catch and docstring.
- `src/cljc/yin/vm/debruijn_register_effects.cljc`: `table-defect`, and
  a rewritten `code-space-defect`.
- `test/yin/vm/attach_image_test.cljc`: one new deftest.
- `test/yin/vm/linker_require_test.cljc`: `FaultyTask`, the harness, and
  one new deftest.

## Unrun

- The full JVM, Node, and Dart lanes (the orchestrator runs them).
- The `:foreign-image` test the gate mentions. It is outside this brief
  and still missing.

Status: COMPLETE
