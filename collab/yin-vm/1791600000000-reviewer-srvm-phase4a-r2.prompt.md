Created-GMT: 2026-10-10 16:00:00 GMT
Created-Local: 2026-10-10 23:00:00 Asia/Bangkok

# Task: Confirm the slice-4a round-2 fixes (continuing your review session f8e3bfb9-ab41-46f4-9a50-18f7bf5fcfd0)

Role: Adversarial Reviewer / Lead System Architect

Same worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4a`.
READ-ONLY again: edit nothing except your findings file named below; no
`git add`/`git commit`. Beware zsh word-splitting: write each `-n ns`
literally.

Your review of `9a8e7915` returned CHANGES_REQUESTED with one required
test fix (acceptance item 8 could not fail; mutants M6, M7 survived), one
design amendment ("steps 2-3" -> "step 3") and carry-forwards for 4b.
Two commits answer it, on top of `9a8e7915`:

- `2877a493` (orchestrator): `docs/design/yin.vm.semantic-register-vm.evaluator.md`
  -- §1.1 `yin.vm.ucf` note, §5.1 "step 3 only", §5.1 item 8 replaced by
  the strengthened specification, new §5.3 listing what carries into 4b.
- `efcd653f` (codex, test-only; source untouched, `git diff 9a8e7915 HEAD
  -- src/` is empty): `vm_test.cljc` (`return-writes-rd-once`),
  `walker_trace_test.cljc` (`:main-tail-primitive` row),
  `parity_test.cljc` (`:parameter-shadowing` row, 4a-only).

Run `git diff 9a8e7915 HEAD --stat` and read both diffs and
`collab/1791580000000-vm-engineer-srvm-phase4a-r2.report.md`.

The orchestrator verified on this commit, independently of codex's report:
JVM 58 tests / 1568 assertions, Node 63 / 1628, Dart 7 files +58, all with
0 failures; kondo 0/0; cljstyle clean. The orchestrator also applied M6
(`(assoc W (:rd frame) value)`) and M7 (additionally writing the body's own
result register) to the source one at a time, restored it by checksum, and
saw `return-writes-rd-once` fail at `vm_test.cljc:167` and `:168` for both.

## Confirm, adversarially

1. **Item 8 can now fail.** Read the strengthened test. Does it assert the
   preconditions that make it discriminating (saved window non-empty, frame
   `rd` different from the body's result register, callee window with more
   than one key) so that a future change to the program cannot silently
   make it vacuous again? Does it assert the EXACT post-return window? Name
   one further mutation of the `:return` arm (beyond M6 and M7) that it
   would still miss, if any.
2. **The new trace row.** Derive `:main-tail-primitive`'s whole trace
   `[[:value :host-fn] [:value 1] [:value 2] [:value 3] [:halt 3]]`
   independently on both machines; does it now exercise the oracle's
   empty-`K0` tail branch (your M17)? Could the mutant `extract reads
   (get (:window after) 0)` now be caught?
3. **The shadowing row.** Does `((fn [x] ((fn [x] x) 2)) 1) -> 2` catch your
   M16 (reversed callee-env merge order)? Confirm by reasoning on
   `(merge (:env c) (bind-params ...))`: with the reversed order what value
   would the register machine return, and does the row compare it with the
   walker's? Is it in the right place (4a-only row in `parity_test`, shared
   corpus untouched)?
4. **The design amendments.** Do `2877a493`'s §5.1 item 8 text, "step 3
   only" wording and §5.3 faithfully represent YOUR findings (nothing
   overstated, nothing lost: the equivalent mutant M1, the extract arms,
   `active-continuation?` and `scheduler-round`, the materialization
   rule)? Is anything in §5.3 wrong or missing for the 4b brief?
5. **Anything new** in the three-file test diff that weakens an existing
   assertion, or any scope leak (nothing outside the three test files and
   the doc).

Write `collab/1791600000000-reviewer-srvm-phase4a-r2.claude-fable-5-1.findings.md`.
Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then findings (severity | file:line | evidence | correction), answers 1-5,
the updated survivor list, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim reads or runs that did not occur.
