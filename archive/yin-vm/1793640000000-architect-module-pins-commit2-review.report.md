Completed-GMT: 2026-10-16 07:26:00 GMT
Completed-Local: 2026-10-16 14:26:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Role: Architect (read-only; worktree datomworld-srvm-pins, branch srvm-module-pins @ 62121c7e, 15 uncommitted files)
Brief: collab/1793630000000-architect-module-pins-commit2-review.prompt.md
Inputs: engineer reports r2b/r2c/r2d, my Part B ruling (collab/1793550000000-…), the fetch threat model, the full `git diff`, the codex proof scripts and logs under /tmp (`module-pins-strip.clj`, `module-pins-r2d-mint.clj`, `-inline-old.clj`, `-inline-new.clj`, `-mutants.py`, their logs), and one independent JVM run.

# Module pins commit 2: review of carrier, check-modules!, driver seam, re-mint, docs

## 0. Verdicts on the two orchestrator decisions

**D1 (both pin maps always emitted, `{}` when empty; a missing key is `:yin.k/undecodable :requires`): CONFIRMED.**
My B.1.3 sentence was self-contradictory; the orchestrator resolved it the way the existing `requires` keys behave (`:yin.k/segments`, `:yin.k/trees`, `:yin.k/cursor-profiles` are emitted when empty, `rows.cljc:1717-1719`). On the merits: determinism is identical under either choice (both are functions of the lift's state); the size cost is two keyword keys and two empty maps per task row, a few dozen bytes, negligible beside the frames. Always-emit buys one real property: the decoder can *require* the keys, so a pack minted by a pre-pins lift is refused as a grammar error instead of being accepted as "no pins", which would have been a silent downgrade of exactly the check this slice adds. No backward compatibility is owed (dev-only repo). The re-mint was owed either way.

**D2 (the pin carries `(:address entry)` unchanged, a keyword, `=` equality): CONFIRMED.**
"string-or-nil" in B.1.3 was my typing slip. `module/link-module` assigns `(jing/segment-key manifest)`, a `:segment/blake3-…` keyword, and every other address in the carrier (`:yin.k/root`, `:yin.k/segments`, `:yin.k/trees` keys, `:yin.k/host-exports` values) is already a keyword. A string would have been the one inconsistent slot and would have needed a specified conversion on both sides. CBOR tags keywords and Stage E already pins their cross-host round trip for segments, so a keyword is correct for the canonical encoding and for JVM/Node/Dart agreement; T8 now pins it for the two new maps as well. The validator accepts any keyword (see P3-3); correct.

## 1. Lift collector (`discharge!`, `rows.cljc:1610-1656`)

- Order is resolve-var's: reserved name, `E`, the selected store, primitive profile, installing name, then the registry (`:1626-1643`). A pin is recorded only in the registry branch, so names answered earlier record nothing. T1 (`lift-pins-only-registry-answered-live-names`) covers all four earlier answers with the same registry present; the red run in `/tmp/module-pins-r2d-pin-tests.log` shows this assertion failing before the fix, so the test is load-bearing.
- Linked entry: `{ns address}` only. Host entry: `{ns nil}` plus `{ns/f profile}` for the *used* export only, via `module/host-export-profile` (`module.cljc:266-279`, answers nil for linked entries by design). Nil profile → `:yin.k/non-portable :host-export-profile {:yin.k/name sym}`; tested by removing `:yin.module/primitives` from the manifest.
- The collector is a side effect inside a predicate, but the predicate runs over every live occurrence of every context (`into` over a 2-arity `mapcat`, eager), and `assoc-in` is idempotent, so repeated occurrences and multiple contexts are fine. Child tasks get their own `discharge!` through `lift-task*`.
- The `install-node` change (strip every `yin.admit`-namespaced key instead of four literal keys) matches the lower's refusal predicate (`check-install!`, `:1877`); the regression adds `:yin.admit/other`. Correct closure of r2a P3-1.

## 2. Decoder / validator (`pin-map?`, `rows.cljc:839-846`, used at `:928-932` and `:1966-1968`)

- Both keys required (`(map? (:yin.k/modules …))` fails on absence), module keys unqualified symbols with nil-or-keyword values, host-export keys qualified symbols with keyword values. The cardinality guard rebuilds each key from its string and compares counts: the only way two distinct symbols rebuild to one is the slash-crossed JS collapse, which is exactly the `built` precedent; the `qualified?` test already excludes it for the module map, the guard catches it for the export map. Count-based, so it does not depend on how `symbol` splits on a given host.
- Nine malformed cases (`malformed_rows.cljc:58-84`) with the exact path `[root 10]` and kind `:requires`; the existing `:equality-collapse` case is untouched. The decode-time check is in the hash/canonical pass; `check-dependencies!` repeats it, which is harmless and keeps the dependency stage self-contained.

## 3. `check-modules!` (`rows.cljc:1914-1941`)

- Whole tree via `task-tree` (pre-order, root and every install child, `:1835-1838`). For each task: module pins, then host-export pins, then install children.
- Terminal first: `outcome!` throws on the first unequal address (`:module-pin-mismatch {:yin.k/name :yin.k/pinned :yin.k/linked}`) or unequal profile (`:host-export-mismatch {:yin.k/name :yin.k/pinned :yin.k/held}`); the collected refusal is emitted only after the whole walk, so a terminal anywhere in the tree wins over discovery anywhere else. Keys are iterated in `str` order, so which terminal is reported first is deterministic across hosts.
- Collected: absent module (including a nil pin for a host module the receiver lacks), nil `host-export-profile` (absent export, absent module, or linked instead of host), and any install child not admitted at exactly the carried address, all into one `{:yin.k/status :yin.k/unsatisfied :yin.k/discovery :complete :yin.k/missing {…}}` with only non-empty maps (`assoc-in` on an empty atom only creates keys that receive entries). This is my 4.2/4.4 shape with the §4.4 refusal folded in, as ruled.
- Subset satisfaction: a receiver exporting more, or exporting a changed *unused* profile, lowers and attaches (`host-export-pins-compare-profiles-and-ignore-unused-exports`, last `doseq`). By design, since only used exports are pinned.
- Position: after `check-custody!`, before `attach-streams!` (`:2352-2354`). Every refusal test asserts zero attaches through `pin-lower-refusal`; the attach-before-check mutant fails 9 assertions.
- One mismatch class is collected rather than terminal: an install child whose name *is* in `:yin.k/admitted-modules` but at another address. That follows §4.4 and commit 1 exactly (the composition is told what to admit); I leave it as is. Observation only.
- Dart traps checked: no `for` over a chunked seq, single-key `assoc-in` on `{}`, no multi-key `assoc` on nil, protocol params not duplicated. Nothing host-specific in the gate.

## 4. Driver seam (`driver.cljc:214, 264-266, 1569-1571, 2463`)

`bare-state` keeps `(select-keys config [:yin.k/admitted-modules])`; both `lowered` and the fenced-recovery `rehydrate-fenced` call forward it; `rehydrate-fenced` already passes it to the lower (`export.cljc:539`). Absent config forwards nil, which the lower treats as empty (fail closed). The test (`parked-installer-needs-composition-admission-test`) is real: it lifts the parked installer, builds a world and a driver through `driver/initial`, drives with the real judge to `:running` (admitted: install present, attaches positive) or `:failed` (absent: `:yin.k/unsatisfied`, missing map equals the composition's admission map, zero attaches). This closes P2-1.

## 5. Fixture re-mint

- `git diff --stat -- test/resources`: exactly `rows-checkpoint-v3.txt` and `ledger-v2.txt`. No other golden, A address, descriptor or scanner pin moved.
- Method (read from `/tmp/module-pins-strip.clj` and `module-pins-r2d-mint.clj`, both present on this machine): take the *new* pack, strip the two keys from each task row's `requires` (body index 11 = slot 10, correct), re-hash rows bottom-up in dependency order (a row is ready when every row it references is already mapped), rewrite references, restore the *old* row order, canonical-encode, and compare hex to the pre-slice pack saved before any edit (`module-pins-old-hex.edn`, 53 entries). Unchanged rows keep their id after a `content=` check. This is a sound proof that each new pack differs from its old pack only by the two keys and the hashes they induce. Logs: `:raw-cbor-proofs 53`, `:inline-raw-cbor-proofs 19` (inline roots captured with the *original* `rows.cljc` and `rows_test.cljc`, then re-proved under the new code), `:ledger-raw-cbor-proofs 4 :render :exact`, `:old-ledger-reproduction :exact`.
- Fixture diff: `frame-root` unchanged (no task), `hash-mismatch` carries `first-park`'s new address (its deliberate wrong pin, as before), the non-canonical fixture goes through `patch-non-canonical`. The rows-checkpoint tests themselves verify canonical bytes and every refusal class, and they pass.

## 6. Tests

- T1–T4, T5 (`:complete` added, the trivially-true fresh-receiver assertion removed, nothing substantive lost), T8 (Stage E: byte-identical re-encode, exact maps, `=` and canonical-byte equality of the three refusals; not slow-guarded, so it runs on all three hosts), T10 (exact `Unable to resolve symbol` message, both link streams empty). Each refusal is compared as a whole map, so no key can leak in unnoticed.
- T9 (`layering_test.clj:161-173`) scans the `ns` form of `engine.cljc`, `semantic.cljc`, `semantic/*` and `rows.cljc` with reader conditionals preserved, for `yin.vm.linker.interpreter`, `yin.vm.linker.dht`, `dao.lease`. Direct requires only, the same method as the file's `yin.repl` gate. I checked the transitive closure by hand: rows → engine → `yin.vm.linker` → `yin.vm.ledger` (dao.space, no lease), rows → custody (lease *vocabulary* only, no require), rows → remote → module. Clean today. Not too strong (the lower owns no liveness: fetch-has-no-deadline stance). Slightly weak because transitive: see P3-2.
- Mutants: the five are the ones I asked for and each is killed with a named test and recorded counters (`/tmp/module-pins-r2d-mutant-*.log`); the all-exports mutant is killed by two tests, not only the named one, which is fine.
- Independent JVM run (this review): `clojure -M:test -n` over rows-test, stage-e-rows-test, rows-checkpoint-test, ledger-fixture-test, holder.driver-test, holder.export-test, rows-custody-test, linker.layering-test, engine-test, vm-test: **323 tests, 4828 assertions, 0 failures, 0 errors.** Codex's whole quick tier: 4,033 tests, 249,981 assertions, 0 failures. Kondo clean on changed files (`module-pins-r2d-kondo.log`).

## 7. Docs

Transport grammar (`requires` slot, both maps, the D2 correction recorded), continuation §2.5 (binding facts travel), §7.1 step 6 and 8, §7.2 new `modules` row and the restore paragraph (the old "the lower does not re-discharge" sentence is gone in both places), Q3 unchanged and still true, Q5 closed as C with fetch/lease explicitly outside the slice; UCF §7.6.4 (carrier sentence), §7.6.5 (`:yin.k/host-exports` in the outcome), §7.11 row; I-8 completed and dated with the full old→new table; known-gaps: Q5 and driver-seam rows closed, R4 row cites ce76c846, the composition fetch-loop row stays open. `yin.vm.linker.dht.md` §7.5.11 already carries the commit-1 wording; no edit needed. No overclaim found: nothing says the fetch loop, T6 or T7 exist. Two wording items, P3-1.

## 8. Findings

No P1. No P2.

**P3-1 (docs, fix in the docs commit).** known-gaps Q5 row says "implemented in uncommitted module-pins commit 2" and I-8 says "completed in the uncommitted slice"; after landing both are stale. Use the R4 row's pattern: "landed as module-pins commit 2" with the sha filled in at merge. I-8 also cites `/tmp/module-pins-old-hex.edn`; a /tmp path is not durable evidence in a design doc. Say "the pre-slice packs' raw CBOR, saved before any edit" and keep the proof description.

**P3-2 (test, follow-up, no change now).** T9 checks direct requires only. Transitive closure is clean today (checked above). If a later slice makes `yin.vm.linker` or `custody` require `dao.lease`, T9 stays green. A closure walk over `src/cljc/yin/vm` is a small follow-up; add a known-gaps row or do it in the next layering touch.

**P3-3 (validator, no change).** `pin-map?` accepts any keyword as a module address, not only `segment`-namespaced ones. The receiver compares by `=` against its own entry's address, so a foreign keyword can only match if the receiver linked such an entry itself; no security consequence. Tests rely on sentinel keywords (`:root`, `:linked`, `:segment/sender`), so tightening would cost more than it buys.

## 9. What Node and Dart must show

Node (`bb test:cljs`): the lines `Testing yin.vm.ucf.rows-test`, `Testing yin.vm.ucf.stage-e-rows-test` (its guarded slow bodies print SKIP; the new `module-pin-carrier-and-refusals-agree-on-every-host` is not guarded and must run), `Testing yin.vm.ucf.holder.driver-test`, `Testing yin.vm.engine-test`, `Testing yin.vm-test` (malformed corpus consumer), `Testing yin.vm.ucf.rows-checkpoint-test`, `Testing yin.vm.ucf.ledger-fixture-test`; 0 failures, 0 errors. `yin.vm.linker.layering-test` is `.clj` and is absent from Node and Dart by design. Dart (`bb test:cljd`): the same namespaces, same SKIP pattern for stage-e slow bodies, 0 failures. The Stage E row is the cross-host pin for D2 (keyword addresses) and for the three refusal shapes; if it fails on either host the finding is P1 and the slice does not land.

## 10. Commit split

Code, tests and fixtures cannot be separated without a red commit (the pins in `rows_test` and the two fixture files are the new carrier's bytes). Three commits, in this order:

1. `feat(yin.vm.ucf): module pins and host-export pins in requires; lower re-discharges by equal address and profile`
   `src/cljc/yin/vm/ucf/rows.cljc`, `test/yin/vm/ucf/rows_test.cljc`, `test/yin/vm/ucf/stage_e_rows_test.cljc`, `test/yin/vm/malformed_rows.cljc`, `test/yin/vm/engine_test.cljc`, `test/yin/vm/linker/layering_test.clj`, `test/resources/yin/vm/ucf/rows-checkpoint-v3.txt`, `test/resources/yin/vm/ucf/ledger-v2.txt`.
2. `feat(yin.vm.ucf.holder): admitted-modules config forwarded to the lower`
   `src/cljc/yin/vm/ucf/holder/driver.cljc`, `test/yin/vm/ucf/holder/driver_test.cljc`.
3. `docs(yin.vm.ucf): module pins I-8 completed; continuation, UCF, transport grammar and known-gaps amended`
   `docs/design/yin.vm.ucf-revisions.md`, `docs/design/yin.vm.semantic.continuation.md`, `docs/design/yin.vm.universal-continuation-format.md`, `docs/design/yin.vm.ucf-transport-tuples.md`, `docs/agents/known-gaps.md` (after the P3-1 wording).

No `collab/` path staged. No Co-Authored-By (format.md). Merge to master after Node and Dart are green as in §9.

SIGN-OFF (D1 confirmed, D2 confirmed; P3-1 wording to apply in the docs commit; Node and Dart lanes per §9 are the remaining gate)
