Completed-GMT: 2026-10-16 06:24:00 GMT
Completed-Local: 2026-10-16 13:24:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Role: Architect (read-only; worktree datomworld-srvm-pins, branch srvm-module-pins @ 6df441d0, 13 uncommitted files)
Brief: collab/1793570000000-architect-module-pins-r2a-confirm.prompt.md
Inputs: collab/1792131636522-engineer-module-pins-r2a.report.md, collab/1793550000000-architect-module-pins-review.report.md (Part A, Part C steps 1-7), the full `git diff` of the 13 files, `git diff --check` (clean), and the sources cited below.

# Module pins commit 1 (R4 alone): confirmation after the P1-1 fix

## Steps verified against the diff

**(1) Lift strips at `install-node`; restore-side dissoc gone.** `rows.cljc:1637-1639` rebinds `response` with the four `:yin.admit/*` keys removed, before the `cbor/encode` portability check (`:1642`) and before the row (`:1645`). The rest of the function uses the rebound name only. The restore reduce (`rows.cljc:2234-2239`) now carries `response` into `:installs` untouched; `grep dissoc rows.cljc` shows no `:yin.admit` dissoc anywhere near `restore-task`. Restored response equals the carried one, so fenced reproduction and the relift law hold by construction. Correct.

**(2) `check-install!` refuses carried `:yin.admit` keyword keys.** `rows.cljc:1849` adds `(not-any? #(= "yin.admit" (namespace %)) (filter keyword? (keys response)))` inside the existing `and`, so the refusal is `:yin.k/undecodable :install-response` with `{:yin.k/name mn}`. `check-install!` runs from `check-pendings!` (`:1883`), one of the three step passes over the whole tree (`:2298-2300`) before custody and before the first attach. Judgement on scope:
- Keyword-only is correct. The only consumer of the sender's record is `engine/link-install` (`engine.cljc:1437-1443`), which reads by keyword (`(:yin.admit/decision response)` and `select-keys` with keyword keys). A string key `"yin.admit/id"` or a nested map carrying `:yin.admit/*` is inert plain data that nothing reads as admission. Refusing them would reject legitimate payload data for no protection gained. Top-level keyword keys are exactly the attack surface; the test matches it.
- Refusing the whole `yin.admit` namespace rather than the four keys is right: a real `:ok` response carries exactly those four (`interpreter.cljc:417-422`), a refusal carries `:yin.admit/id` and `:yin.admit/decision :refuse` (`:390-395`) and already fails `(= :ok (:status response))`. No producer outside the interpreter stamps `:yin.admit/*` onto a response (grep over `src/cljc/yin/vm/linker/`, `module.cljc`).
- One asymmetry, recorded as P3-1 below: the lift strips four named keys, the lower refuses the namespace.

**(3) Rename complete; ledger/driver untouched.** `:yin.k/admitted-modules` appears in `rows.cljc:1893, 2279`, `export.cljc:499, 539`, `rows_support.cljc:301`, `rows_test.cljc:1390-1400`, `rows_custody_test.cljc:322`, `export_test.cljc:507`, `safepoint_harness_test.cljc:351` and all six docs. Plain `:yin.k/admitted` survives only as the ledger fact kind (`ledger.cljc:22, 115, 553`, `authority/admission.cljc:283`, `authority/seam.cljc:43, 51`) and the driver not-holder field (`driver.cljc:743, 1655`) with their tests; none of those files is in the diff. No doc in the diff uses the old name for the lower option.

**(4) The new tests pin what they claim.**
- Decoded root install row has none of the keys: `rows_test.cljc:1381` asserts over slot 3 of the first row of task slot 12; slot 3 is the response (`check-install!` destructures `[mn phase parent response child]`, `rows.cljc:1843`). The source machine's response carries all four keys (`:1375-1376`), so the assertion fails whenever the lift carries them.
- Relift equals the wire: `rows_test.cljc:1406` compares the whole `:install` row (name, phase, parent, response and the child task row) of the relift against the decoded wire's row. This is the install row rather than `canonical-under` over the root, which step 4(b) allowed. It is the row the P1-1 defect changed, and the harness's own relift law (`safepoint_harness_test.cljc:629`) still runs over the full root for every world now lowered with `install-lower-opts`. Sufficient.
- Spliced pack refused with zero attaches: `rows_test.cljc:1383-1389` splices the four keys back into the decoded row, re-emits, lowers with a correct admitted map, and asserts `[:yin.k/undecodable :install-response]` and `(zero? @count)`. The admitted map being correct matters: it proves the refusal comes from `check-install!`, not from the admission gate.
- Frozen parked-installer with an explicit sender record (`export_test.cljc:485-497`): the source machine's `[:installs 'host.mod :response]` is merged with the record before `frozen-of`, which runs `export/enter`, `export/prepare` (the lift) and `export/freeze`; `rehydrate` runs `rehydrate-fenced` with `installer-lower-opts`; `reproduces?` compares the published pack bytes against a fresh `export/encode` of the restored machine. Under the earlier restore-side design the pack would have carried the four keys, the restore would have dropped them, the re-encode would differ, and `rehydrate-fenced` would answer `:recovery-inconsistent`, failing the `:ok` and byte-equality assertions. Under the fixed design the pack never carries them and the relift reproduces. This is the P1-1 regression pin, and it starts from a response that truly carries the record.
- The negative recovery test (`export_test.cljc:500-511`) passes the admitted map explicitly rather than through the helper's default, so both `nil` and a wrong address refuse with the §4.4 shape and zero attaches.
- Existing assertions kept: the four-case refusal loop, `(pos? @count)` on the admitted lower, `(not (contains? installed :admission))`, the `:value 42` run.

**(5) Mutants.** m1 (lift keeps the keys) is killed by the decoded-row assertion and by the recovery pin, and also makes the admitted lower refuse (`:install-response`), which explains the 11 failures and 1 error. m2 (`check-install!` accepts the keys) lets the spliced lower succeed, so `error` answers nil and both the status/kind and zero-attach assertions fail: 2 failures, as recorded. m3 (gate bypass) is the round-1 mutant, still killed by the refusal loop. All three are meaningful and each has a distinct named killer.

**(6) Docs.** `grep "dropped on restore\|restore-side"` over `src/ test/ docs/` is empty. All six amendments say the lift never carries the fields and a carried one is `:yin.k/undecodable :install-response` (continuation `:1819-1824`, `:2760-2764`; linker.dht `:2333-2335`; transport-tuples `:417-420`; UCF `:2872-2875`; revisions I-8). The P2-1 known-gaps row is present (`known-gaps.md:45`). The P3-2 docstring note is in `lower-task` (`rows.cljc:2280-2282`). No doc claims the pin work is implemented: the Q5 row says "blocked", continuation Q5 says "implementation blocked", UCF says "blocked pending", I-8 says "partial; R4 only". See P3-2 for the staleness those four share.

**(7) Rest of the diff.** `rehydrate-fenced` forwards `(select-keys opts [:yin.k/admitted-modules])` under `:fenced true` and documents the option; the harness defines `install-lower-opts` after `module-response` and `blocking-body` (`safepoint_harness_test.cljc:320, 337, 349`), so load order is right on all three hosts; `rows_custody_test` lowers through `(merge s/installer-lower-opts opts)` so an explicit admitted map still wins; the derivation test at `:322` admits the re-minted manifest address it builds. Every new expression is portable plain data: `namespace` on keywords, `not-any?`, `update-in` on vector slots, `merge` on a map (none of the recorded ClojureDart traps apply: no `for`, no multi-key `assoc` on nil, no unary minus, no private var-quote). `git diff --check` clean; kondo 0/0 per the engineer.

## Findings

No P1. No P2.

**P3-1. Lift and lower disagree on the predicate.** The lift strips four named keys (`rows.cljc:1638-1639`); the lower refuses any keyword key in the `yin.admit` namespace (`:1849`). Today the two coincide, since an `:ok` response carries exactly those four. If the interpreter ever adds a fifth `:yin.admit/*` key to an `:ok` body, every real pack with an install child would be refused by the receiver. Not blocking: this is my own step 1/step 2 wording. Fold into commit 2 (step 8 edits `install-node`'s neighbourhood anyway): strip by the same namespace predicate at lift, one test that a fifth `:yin.admit/other` key is also absent from the decoded row.

**P3-2. Four doc statements are stale against Part B.** Known-gaps Q5 row "blocked: host-profile carrier decision", continuation Q5 "The report specifies no carrier slot for those host profiles", UCF "blocked pending the host-export-profile slot/schema decision", revisions I-8 "blocked pending the Architect's decision". Part B of my review (collab, 2026-10-16) has made that decision (`:yin.k/host-exports` beside `:yin.k/modules` in `requires`). They were true when codex wrote them and false at commit time. Not blocking: commit 2 step 15 rewrites all four. If the orchestrator prefers accuracy at commit 1, the minimal edit is "blocked" to "ruled 2026-10-16 (Architect Part B); implementation is the next slice" in those four places, no other change.

**P3-3. Known-gaps R4 row says "pending Architect review".** `known-gaps.md:43`. At commit time it should read "Architect signed off 2026-10-16; lands with module-pins commit 1". Orchestrator edit at commit, or leave for commit 2's "landed" rewrite.

**P3-4. `lower-task` docstring readability.** The new sentence is spliced into the middle of the `opts:` enumeration (`rows.cljc:2279-2283`), so `:exclusive` follows a semicolon after a prose sentence. Cosmetic; tidy in commit 2.

**P3-5. Trivially true assertion.** `(is (empty? (:installs recv)))` at `rows_test.cljc:1396` tests an immutable fresh machine; it cannot fail. Harmless; drop or leave.

## What Node and Dart must show

Run `bb test:cljs` and `bb test:cljd` once each, foreground, one lane at a time, from the worktree (`npm ci` and `mise trust` are done there).
- Node output must contain `Testing yin.vm.ucf.rows-test`, `Testing yin.vm.ucf.rows-custody-test`, `Testing yin.vm.ucf.holder.export-test` and `Testing yin.vm.semantic.safepoint-harness-test`, each with 0 failures and 0 errors. The pre-existing host-collapse test runs its body on Node, so no `SKIP host-collapse` line appears there. The new tests carry no `dao.test-slow/guard`, so no new SKIP lines anywhere.
- Dart output must run the same four namespaces and print `SKIP host-collapse: slash-crossed identifiers remain distinct on Dart` once (pre-existing). The JVM lane prints the matching `on JVM` line. Missing Dart-peer executable skips (WS-project, REPL peers) are unrelated to these namespaces and acceptable.
- Anything else, in particular a `:recovery-inconsistent` status from `recovery-of-installer-with-sender-admission-is-reproducible-test` or a non-`:install-response` kind from the spliced case on one host only, is a cross-host difference and stops the commit.

## Exact commit file list

Code (2):
- `src/cljc/yin/vm/ucf/rows.cljc`
- `src/cljc/yin/vm/ucf/holder/export.cljc`

Tests and support (5):
- `test/yin/vm/ucf/rows_test.cljc`
- `test/yin/vm/ucf/rows_support.cljc`
- `test/yin/vm/ucf/rows_custody_test.cljc`
- `test/yin/vm/ucf/holder/export_test.cljc`
- `test/yin/vm/semantic/safepoint_harness_test.cljc`

Docs (6):
- `docs/agents/known-gaps.md`
- `docs/design/yin.vm.semantic.continuation.md`
- `docs/design/yin.vm.ucf-transport-tuples.md`
- `docs/design/yin.vm.universal-continuation-format.md`
- `docs/design/yin.vm.linker.dht.md`
- `docs/design/yin.vm.ucf-revisions.md`

Nothing under `collab/` is staged. No fixture, golden, `src/yang`, `test/yang`, `ledger.cljc` or `driver.cljc` change.

Commit message (format.md, no Co-Authored-By):
`fix(yin.vm.ucf): carried install children need receiver admission; lift never carries sender admission`

Conditions: Node and Dart lanes green as described above before the commit. P3-2 and P3-3 are the orchestrator's choice to touch now or in commit 2; neither reopens this review.

SIGN-OFF
