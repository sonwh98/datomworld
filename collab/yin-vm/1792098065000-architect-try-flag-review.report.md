Completed-GMT: 2026-10-15 21:05:00 GMT
Completed-Local: 2026-10-16 04:05:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending

# Architect review: G1 / L-d-1b, `:try?` carried on the three link pendings (uncommitted, srvm-try-flag @ 6b001f87)

Scope read: the 11-file working-tree diff, the engineer report
(`1792097807612-engineer-try-flag.report.md`), gap-list row G1, the engineer
brief, `engine.cljc` 1140-1180 / 1320-1345 / 1505-1560 / 1725-1760,
`module.cljc` 520-640 (`handle-require`), `rows.cljc` 25-75 / 743-760 /
860-900 / 955-968 / 1036-1070 / 1100-1145 / 1535-1548 / 1690-1700 / 2185-2205,
`remote.cljc` 360-432 / 640-690, `frame.cljc` 150-180, `layout.cljc`
(lift-state/lower-state), `debruijn/register.cljc` 340-365 / 895-915,
UCF 7.4.3 (:920-1000) and 7.5.4 (:268-278), `ucf-revisions.md` I-6 (:629-687),
`ucf-transport-tuples.md` §5.3 (:320-345) and diagnostics (:848-858),
the fixture diff (`git diff -U0`) and the known-gaps rows 36-37.

Independent evidence on this worktree (JVM only, nothing written to the tree):

| check | result |
|---|---|
| `clojure -M:test -e :slow -n yin.vm.ucf.rows-test -n yin.vm.ucf.rows-checkpoint-test -n yin.vm.ucf.remote-test -n yin.vm.semantic.frame-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.linker-test` | 178 tests, 3229 assertions, 0 failures, 0 errors; counterexample searches 0 failures |
| `clojure -M:test -e :slow -n yin.vm.ucf.rows-checkpoint-test` (fixture pins) | 17 tests, 179 assertions, 0 failures |
| `clj -M:kondo --lint` on the 3 source + 4 test files | 0 errors, 0 warnings |
| `git diff --check` (engineer) | passed; not re-run |

## 0. Verdict in one paragraph

The code change is minimal, correct and complete: one appended `:D` slot per
link pending, lift normalises an absent flag to `false`, restore carries the
boolean back, the frame validator refuses a non-boolean as `:pending-try`
before any resource attaches, the remote conversion carries it both ways for
all three variants, and the semantic kernel's resume paths honour the restored
flag. The fixture move is exactly the four installs-derived blocks and nothing
else. One documentation defect blocks the commit: the normative row-grammar
table of body version 3 (`yin.vm.ucf-transport-tuples.md` §5.3) still lists
the old slot sequences, so the published grammar and the codec disagree. That
is a three-row table edit plus one diagnostic name; no code changes.

## 1. Question by question

### (1) Grammar change minimal and correct: YES

- **Slot position.** `pending-table` appends `:D` as the final slot of
  `:link-request` (8 slots), `:link-response` (6) and `:install` (2); every
  pre-existing position is unchanged (`rows.cljc:44-46`, `pending-keys`
  `:1032-1034`). The lift builds the row by `conj`-ing `(boolean (:try? e))`
  after the existing `base`/envelope/op-id slots (`:1539-1546`), so the
  request's `envelope`/`op-id` keep positions 5 and 6.
- **Required boolean.** `:D` admits any data at the slot-kind gate
  (`structural?` `:756` falls to `:else true`), so the type rule lives where
  the design puts semantic rules: `frame/defect` (`frame.cljc:174-178`)
  refuses a missing or non-boolean flag as `:pending-try` at
  `[:yin.k/pending :yin.k/try?]`, only in `:wait` context and only for the
  three link reasons. Arity is already enforced by the row-shape count check
  (`:880 (= (count ds) (count slots))` → `:row-shape`).
- **Lift normalises absent to false.** `handle-require` sets `:try? true`
  only for try-require (`module.cljc:584`); a plain `require` entry has no
  key. Both `rows.cljc:1543/1546` and `remote.cljc:389/428` wrap in
  `boolean`, so the wire never carries nil.
- **Restore carries it back.** `rows.cljc:2193-2200`: `:try? (last xs)` for
  the pair (the flag is last in both variants, and `envelope`/`op-id` are
  now taken by `(drop 5 xs)` only for `:link-request`, which is cleaner than
  the previous blind 7-destructure), `(second xs)` for `:install`.
  `remote.cljc:670/682` copy `:yin.k/try?` verbatim.
- **Validator before attach.** `lower-task` decodes through `decode*`, which
  runs `validate-frames` on every task (`rows.cljc:963-965`) before the
  attach step; `validate-frames` judges every wait frame in `:wait` context
  (`:1138`). The engineer's test asserts `attached` stays 0 on all four
  malformed values for all three variants; confirmed by reading the order.
  The lift side also runs `validate-frames` on its own output (`:1694`), so a
  kernel that ever produced a non-boolean would be refused as
  `:inconsistent-state` at export rather than shipped.
- **Frame bijection.** `frame->rows` passes non-`:V` slots raw (`:1055`), so
  the keyed frame round-trips the boolean; `link-pending-frame-bijection`
  covers all three variants × both values.

### (2) UCF versioning: no bump; the dated record is sufficient, but misplaced

- The pending row is positional and the decoder refuses a count mismatch as
  `:row-shape` → `:yin.k/undecodable`. An old pack with a link pending is
  therefore refused loudly, never misread. Under the no-backward-compat rule
  (dev-only, no deployed stores) a `:yin.k/version` bump buys nothing and
  would move every fixture; the 8c precedent (I-6, "8c regenerated ... three
  child segment addresses moved") re-minted with a dated record only. Same
  treatment here is right.
- UCF 7.4.3's added sentence ("required boolean `:yin.k/try?` (the final
  pending-row slot in version 3)") states the rule correctly. The three
  example maps directly below it (:948-968) still omit the key, so the prose
  says "required" and the examples show it absent (P3-1).
- The `ucf-revisions.md` record is inserted inside I-6 between its "First"
  and "Second" paragraphs (:655), splitting a numbered three-part record about
  phase 8. It belongs in its own entry, I-7, after "Third" (P3-2).
- 7.5.4's lift-kind list (:273-277) and the transport-tuples diagnostics list
  (:848-858, "a `:yin.k/kind` where one is named above") do not name
  `:pending-try`. The new kind is otherwise undocumented (folded into P2-1).

### (3) Fixture move: confirmed, exactly the four blocks

`git diff -U0` on `rows-checkpoint-v3.txt` touches: line 451 (the `installs`
pack address), 454-456 (the embedded root task address inside the installs
pack), 572-799 (the installs pack body; same line count, rows re-sorted by
address because one `:pending`, one `:wait` and one `:task` row re-hash), and
the single address lines 913 (`child-header`), 916 (`install-without-entry`)
and 922 (`child-op-id-out-of-range`). The neighbours `custody-not-map` (:910),
`op-id-at-counter` (:919), `duplicate-op-id` and `parked` (:799) are
untouched, as are `first-park` and `successor`. Old/new addresses match the
engineer's table. No pinned code A, descriptor hash or golden outside this
file changed (the diff stat lists no other fixture or pinned literal, and the
fixture suite's address-vs-bytes pins pass). I did not decode the CBOR
myself; the engineer's "exactly one removed/added row of each kind" matches
the hunk shape and is the only decomposition consistent with a single
appended slot on one install pending.

### (4) Semantics: the restored flag is honoured on every resume path

- `engine.cljc:1156-1173` (`refused-entry`, `linked-entry`) is the only
  branch on `(:try? entry)` for delivery; `:1528` (`settle`) reads it to
  decide whether the `:install` waiter keeps a `:link-id`. `false` and absent
  behave identically there, so restored plain entries (now explicit `false`)
  are unchanged.
- `:link-response` wait resumed after restore: `apply-link-read`/`settle`
  → `refused-entry entry response` (:1531) or `linked-entry` (:1534), both
  on the restored entry. `:install` wait resumed after restore:
  `wake-installed` (:1326-1341) maps every install waiter through
  `refused-entry`/`linked-entry`; `abandon-installs` → `refuse-install` →
  same path. `settle` builds the install waiter by `dissoc` of the link
  keys, so `:try?` survives the `:link-response → :install` transition
  (:1524-1528); `apply-link-sent` (:1697) and `append-link-request`
  (`module.cljc:549`) likewise keep it on `:link-request → :link-response`.
- Other builders: `module/handle-require` is the only producer of link
  entries in `src/` (grep for `:reason :link-request|:link-response|:install`
  hits only `module.cljc`, `engine.cljc` transitions, and the two UCF
  codecs). The de Bruijn backends' `:link-request`/`:link-response` are the
  link-pair resource keys of `vm/empty-state`, not pendings
  (`register.cljc:347-356, :904-907`; `stack.cljc` mirrors); `layout/lift-state`
  and `lower-state` dissoc only kernel/canonical keys and merge the rest, so
  `:try?` passes through unchanged, and `layout/lower-defect` refuses
  installs outright (`:726`). yin.repl builds no link entries. No omission
  found.
- The engineer's `link-refusal-keeps-each-return-contract-after-handoff`
  exercises try × tail × retained (8 cases) end to end through
  `lift-task`/`lower-task` and asserts the data value for try and the raised
  `:absent` for plain; `install-return-contract-and-link-flag-validation`
  does the same for `:install` via `abandon-installs`. Both read the outcome,
  not the entry, so they test the contract, not the slot.

### (5) Mutant proof: meaningful

`(boolean (:try? (dissoc e :try?)))` is a shape-preserving mutant: the row
keeps its arity (so `:row-shape` cannot catch it) and the validator sees a
legal `false`, which is precisely the pre-G1 behaviour. The new handoff test
failing with "Module link refused: absent" on the try cases is the targeted
observation. The restore side (`(last xs)`/`(second xs)`) is not covered by
that mutant but is pinned by the `false` branch of the install test (raise
expected) together with the frame bijection; a hard-coded `true` on restore
would fail both. Using a `/tmp` classpath shadow rather than editing the
tree was the right discipline.

### (6) Known-gaps row on install waiters' `:link-id`: stays open, separate

Correct to leave it. One observation for that row, not for this slice: an
`:install` row carries `name` and `try?` only, so after a lift/lower a
try-joiner's loss datum has `:link-id nil` (`refused-entry` falls back to
`(:link-id entry)`, now absent), where before the handoff it carried the
install's parent id. The engineer's install test selects only
`[:status :reason]`, which is honest about that. This strengthens the open
row's recommendation to drop `:link-id` from install waiters and carry
`:module` in the loss datum (P3-3, recorded against the existing row, no
change here).

### (7) Node/Dart expectations

No Node or Dart lane has run on this diff (engineer brief: orchestrator's
lane). Everything touched is portable `.cljc` using `boolean?`, `last`,
`drop`, `conj`, `assoc` on vectors and `doseq`, none of the recorded
ClojureDart traps (no multi-key `assoc` on nil, no `for` over a long seq,
no reader-conditional ordering, no unary minus). The checkpoint fixture
reader is unchanged. Expectation: both lanes green with the same counts the
JVM shows plus the SKIP lines for guarded slow bodies. Per the test-tier
cadence this slice lands only after one 3-host quick run; this worktree has
no `node_modules`, so the Node lane needs `npm ci` first.

## 2. Findings

**P1** — none.

**P2-1 (blocking, docs only)** `docs/design/yin.vm.ucf-transport-tuples.md`
§5.3 (:336-338) is the normative row grammar of body version 3 (I-6 names it
"the one carrier") and still reads
`:link-request | link-id-D name-S request-stream-cref response-stream-cref cursor-cell-C envelope-D op-id-D`,
`:link-response | ... cursor-cell-C`, `:install | name-S`. Required edit:
append `try?-D` to each of the three rows, add one sentence under the table
("`try?-D` is a required boolean; a non-boolean refuses as `:pending-try`
at `[:yin.k/pending :yin.k/try?]`"), and add `:pending-try` to the named
`:yin.k/undecodable` kinds in the diagnostics paragraph (:853). Rationale:
the engineer's brief allowed "the UCF 7.4.3/7.7 row grammar sentence if it
enumerates pending keys"; this table is the enumeration, it was simply not
on the brief's file list. Shipping a grammar change with the published
grammar stale is the defect the ledger exists to prevent.

**P3-1** UCF 7.4.3 examples (:948-968): add `:yin.k/try? false` to the three
example maps so the examples match the "required" sentence above them.

**P3-2** `ucf-revisions.md`: move the 2026-10-16 record out of the middle of
I-6 into its own entry `I-7. G1 / L-d-1b (2026-10-16): ...` after I-6's
"Third" paragraph; keep the text as written.

**P3-3** Known-gaps row "try joiner's `:link-id` is the install's parent id"
(row 37): add the evidence that an install row carries no link id, so the
loss datum's `:link-id` is nil after any handoff. No code change in this
slice.

**P3-4** `rows.cljc:2193` keeps the destructure `[link-id mn _ _ c]` plus a
separate `(drop 5 xs)`; fine, but a one-line comment that the flag is
deliberately last in both variants would save the next reader the
`pending-table` lookup. Optional.

## 3. Decision

CHANGES-REQUIRED, scoped to P2-1 (three table rows, one sentence, one kind
name in `yin.vm.ucf-transport-tuples.md`). P3-1 and P3-2 may ride in the same
docs round; P3-3 and P3-4 are optional. No source or test change is
requested; do not re-mint anything. With P2-1 applied, this review stands as
SIGN-OFF on the code without a further review round, and the commit may
proceed once the orchestrator has one green Node and Dart quick lane on the
branch (kondo and the JVM focused and checkpoint suites are already verified
here).

CHANGES-REQUIRED
