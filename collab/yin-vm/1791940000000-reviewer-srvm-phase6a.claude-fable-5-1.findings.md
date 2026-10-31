Completed-GMT: 2026-10-11 09:05:00 GMT
Completed-Local: 2026-10-11 16:05:00 Asia/Bangkok

# Adversarial review of slice 6a (register frame validator and row codec)

Reviewer: claude-fable-5-1 (non-author family). Author: codex gpt-6.1-sol.
Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 4b0e735e, diff `master...HEAD` = exactly the four new files (frame.cljc 162 lines, rows.cljc 677, frame_test.cljc 281, rows_test.cljc 247). Nothing staged or committed by me; `git diff --quiet HEAD -- src/` exits 0 at the end of the review and both sources `cmp` equal to `collab/6a-reviewer-backups/{frame,rows}.cljc`.

## What I ran (all foreground, all in this worktree)

| Lane / check | Command | Outcome |
|---|---|---|
| JVM baseline | `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` | 21 tests, 481 assertions, 0 failures, 0 errors |
| **Dart** (nobody had run it) | `bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.frame-test,yin.vm.ucf.rows-test` | compile OK; **21 tests: 20 passed, 1 FAILED** (`yin.vm.ucf.rows-test/task-root-checks-carried-code-and-frame`); log `collab/6a-reviewer-dart.log` |
| Node | not re-run by me; the author's `collab/6a-node-final.log` shows `Testing yin.vm.semantic-register.frame-test` and `Testing yin.vm.ucf.rows-test`, 191 tests / 4760 assertions / 0 failures |
| Mutation campaign | `python3 -I collab/6a-reviewer-mutants.py` (44 mutants + 1 rerun), each `clojure -M:test -n <ns> -v <ns>/<var>` with literal names | 29 KILLED, 15 SURVIVED; per-mutant logs `collab/6a-reviewer-R*.log`, table `collab/6a-reviewer-mutants-main.json` (+ `6a-reviewer-mutants.json` for the R23 rerun) |
| Derivation probe | temporary print-probe appended to rows.cljc (`collab/6a-reviewer-probe.cljc`), run through `rows-test/row-ids-are-jing-default`, restored | output `collab/6a-reviewer-P00.log` (80 `RVPROBE` lines) |

Backups and restoration: my backups live in `collab/6a-reviewer-backups/`; the harness refuses to mutate unless each backup `cmp`-equals its source and the source is clean against HEAD, and after every mutant it restores from the backup and records `cmp` and `git diff --quiet HEAD -- <path>` (both 0 for all 45 runs). **Process incident, reported plainly:** my very first harness run applied the probe to rows.cljc before any reviewer backup existed (my earlier backup command had been refused by the sandbox as a whole, which I missed), and the harness crashed before restoring. I restored rows.cljc from the author's verified backup `collab/6a-verified-backups/rows.cljc` (`cmp` 0, `git diff --quiet HEAD -- src/` 0) before doing anything else; the guard above was added afterwards. The probe's output from that run is the one used below.

The sandbox refused every non-`clojure -M:test` way of evaluating project code (`clojure -Sdeps`, `clojure -M:cljs -i`, `java -cp`), so the "independent derivations" were done by hand on paper and compared against values printed by the probe; the probe computed Merkle ids for row sets I constructed by hand from the design tables, using only `jing/segment-key`.

## Findings (severity | file:line | evidence | correction)

1. **HIGH | test/yin/vm/ucf/rows_test.cljc:238 | Dart lane fails.** `task-root-checks-carried-code-and-frame` throws `{:yin.k/status :yin.k/undecodable :yin.k/kind :extra-cell :yin.k/path [9]}` on Dart only. Cause: `(assoc (:slots base) 2 :blocked 11 ft/worked-code 13 [wait] 16 {...})` is a multi-key `assoc` on a **vector**; ClojureDart inlines it to `(-conj v {2 … 11 … 13 … 16 …})`, which appends the map as an 18th slot and leaves slot 13 (frames) empty, so the census cell `:test/cursor` is never reached (generated `test/cljd-out/yin/vm/ucf/rows-test_test.dart:4820-4835` shows the `PersistentHashMap` being `-conj`ed). The same trap is recorded in memory for nil; it applies to vectors too. Correction: chain single-key assocs (`(-> slots (assoc 2 :blocked) (assoc 11 ft/worked-code) (assoc 13 [wait]) (assoc 16 {...}))`). Production code is free of this (frame_test.cljc:120 and :273 multi-key assocs are on maps; rows.cljc has none). Acceptance item 11 therefore FAILS on Dart with a concrete failure, not just "NOT RUN".

2. **MEDIUM | src/cljc/yin/vm/ucf/rows.cljc:119 | malformed census key escapes as a host exception.** `ordered-slots :cells` sorts by `(edn/read-string (subs (name k) 2))`; this runs at decode step 5 (`:row-order`, line 473) on wire data before numbering. Probe: a `:cells` row keyed `:x` (rehashed consistently) gives `StringIndexOutOfBoundsException`, not `:yin.k/undecodable`. `emit-numbered` with a partial numbering fails the same way. Correction: validate `:C` slots of the census against `#"c-[1-9][0-9]*"` in `structural?` (refuse `:slot-kind`), and parse digits with a guard instead of the EDN reader.

3. **MEDIUM | src/cljc/yin/vm/ucf/rows.cljc:424 | gate reads an unvalidated slot.** `gate` does `(doseq [install (nth slots 12)] …)` before the schema step checked slot kinds. Probe: installs slot `5` → `IllegalArgumentException: Don't know how to create ISeq from: java.lang.Long`. (The frames slot set to `5` is correctly refused `:slot-kind`.) Correction: in `gate`, require `(and (vector? installs) (every? keyword? installs))`, refusing `:task-shape`.

4. **MEDIUM | test/yin/vm/ucf/rows_test.cljc:143-156 | acceptance item 10's "unordered-container permutation" is vacuous.** Mutant R26 (emit `:set` slots unsorted) SURVIVES the whole suite. Fixture `b` swaps the stream *identities* and the correspondence map "accordingly", so after substitution the set's elements are in the **same abstract order** as in `a`; the cursor-keyed map is resolved by refinement (`'one → x`), so its order is identical too. `refinement-search-preserves-unordered-multiplicity` cannot see it either: when every element is a cell, the exhaustive search picks the bytewise-min leaf and so compensates for an unsorted set. Correction: emit one task whose `:set`/`:map` slots are listed in reversed abstract order under the **identity** correspondence and assert equal roots (streams, not only cells, in the set).

5. **MEDIUM | test/yin/vm/ucf/rows_test.cljc | the canonical procedure's specific output is unpinned.** Mutants R21 (number classes n…1 instead of 1…n) and R17 (search keeps the first explored leaf instead of the bytewise-min) SURVIVE. No test pins a root id, and no fixture leaves a non-automorphic class after refinement (the draft §6 triangle/square case), so an implementation that is deterministic but disagrees with the design's procedure (or depends on hash-map traversal order in `search`) passes. Correction: pin at least one root id (probe values: fixture `(fixture :s1 :s2 :x :y)` → `:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937`; `(frame->rows ft/worked-frame)` → `:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d`) and add a fixture with two refinement-equivalent but non-automorphic cells whose chosen leaf is asserted.

6. **MEDIUM (6b boundary, decide now) | src/cljc/yin/vm/ucf/rows.cljc:534-536, 541-544 | in-band value markers.** The default `value-node`/`decoded-value` use plain maps `{:yin.k/row n}`/`{:yin.k/cell id}` as markers, so a *guest* map with exactly that key is misread. Probe: window value `{:yin.k/row 1}` round-trips to a `:row-shape` refusal; a map-shaped payload would be silently reinterpreted. §2.7's bijection does not hold on such frames. 6b's lift supplies its own classifier, but the default codec path (and `rows->frame`) should either use a distinct marker type (record/`deftype`) or refuse those maps as `:yin.k/non-portable`.

7. **LOW | src/cljc/yin/vm/semantic_register/frame.cljc:32 + rows.cljc:552-554, 604-608 | `store-of nil` breaks the identity.** A keyed frame with `:yin.k/store-of nil` present passes S4 (`closed?` allows the optional key with any value) but encodes to `[:env b nil]` and decodes without the key: `frame->rows ∘ rows->frame ≠ id` (probe: `false`). Correction: S4 refuses a present nil `store-of` (`:env-shape`), or the design states canonical frames omit it.

8. **LOW | rows.cljc:119 vs draft §3/§5 | census ordering ambiguity not recorded by the author.** Draft §3 says the census is "sorted by n"; draft §5's "one ordering rule" says sorted by canonical bytes of the key as spelled. They differ from ten cells on (`c-10` sorts before `c-2` bytewise). The implementation follows §3 (probe: ten cells emit `c-1 … c-10` numerically and decode). Record the reading; the suite has no ≥10-cell case.

9. **LOW | rows.cljc:393 | step-5 literal rules skipped for frame-rooted packs.** `check-abstract!` runs only for `:task` roots, so `rows->frame` accepts a `:vec` row of pure literals or a non-portable `[:lit obj]`. Acceptable for a test device; note it so 6b never routes a frame-rooted pack to `rows->frame` as a trust boundary.

10. **LOW | rows.cljc:151 vs 210 | child budget.** `emit-node` numbers an install child with `(canonical-numbering x)` (default budget) while `ce` passes the caller's `budget`; a tightened `:search`/`:work` bound does not reach children on emit.

11. **LOW | rows.cljc:656-658, 674-676 | defect path vocabulary.** Frame defects are reported as `[root-id :frames i …keyed-path]`, not the draft §8's `[row-id slot-index …]`. Harmless, but two path dialects now exist in one refusal family.

12. **LOW | test gaps shown by surviving mutants** (none is a code defect; the probe confirmed the code refuses correctly where it could be checked): R06 non-vector `:yin.k/k` (code gives `:k-shape`), R07 `pc 0` (code gives `:pc-bounds`), R10 A5-before-A6 order (code gives `:window-extra` for `{3 :x}`), R12 P2's empty admit set for `:park`/`:current-continuation`, R24 extra top-level wire key (code refuses `:row-shape`), R25 X1-before-frames order, R29 gate cycle check (redundant with `walk`), R30 malformed carried code (`:code`), R33 non-portable `[:lit …]` (`:literal`), R35 decoded-equality duplicate keys, R45 `:yin.k/hash-mismatch` (a stale id is still caught later as `:noncanonical-cells`, but the step-4 kind is never asserted), R46 `:reference-kind`. Each needs one assertion.

13. **INFO | honesty of claims.** (a) The author's report is honest about Dart (NOT RUN) and about the resource-refusal spelling and the `debruijn/resolve-name` deviation; my hand derivation of row 21's chain agrees with `lexical-names` (`#{x f cat}`), so the deviation is sound. (b) Acceptance item 2 says the task context is "decoded from the machine's own tables"; the test takes `:free-env` from the fixture's setup `env` and a constant `:ffi-call-ids #{}` (frame_test.cljc:20, 124). Accurate for stage-1 rows (no FFI), but not what the sentence says. (c) `refinement-search-preserves-unordered-multiplicity` does not exercise duplicate multiplicity; the name overclaims. (d) `canonical-under-is-renaming-invariant` tests identity renaming, not container permutation (finding 4). (e) The author did not record the census-ordering ambiguity (finding 8) or the marker ambiguity (finding 6).

## Answers to the six questions

**1. Can each acceptance item's test fail?** Yes for items 1-9 (named killing mutants in the table): item 3's `each-rule-refuses-its-tamper` killed 12 rule mutants (R08, R11, R13, R14, R37-R44) and `first-defect-is-structural` killed the S2/S3 reorder (R02); item 4 killed R01 and R31; item 5 killed R09; item 6 killed R03 and R04; item 7 killed R19 and R31; item 8 killed R15 and R23; item 9 killed R27. Item 10: only R36 (missing correspondence) is killed; R17, R21 and R26 survive, so its permutation and canonicality clauses are not actually tested (findings 4, 5). Item 11 FAILS on Dart (finding 1). The author's nine mutants all map onto killed mutants of mine (M1≈R01/R31, M2≈R03, M4≈R13, M6≈R08, M7≈R28, M8≈R36, M9≈R32); M3 and M5 I did not replicate.

**2. Rule coverage.** The tamper table has 24 frame rows plus X1 through `task-defect` = 25, each refused with its own kind (T1 and A1 legitimately share `:site`, A2/T1 share `:deliver`, as the design names them). The published input contract is plain data `{:free-env :parked-key :ffi-call-ids}` (frame.cljc:61-65). The `ns` form requires `yin.vm`, `.code`, `.analysis` only; no kernel, no `yin.vm.engine` (`yin.vm` is permitted by §3.1 and transitively loads dao.stream/dao.space.query, not the engine). Design order S→C→A/T→K→E→P is implemented; the only ordering mutant tried (R02) was killed.

**3. Row codec.** Bijection holds on all 49 fixtures' frames (bijection test + R19/R31 killed) with classified values and a substituted pending; the evaluator's real pending fields are 6b's. Hand Merkle re-derivation of row 22's frame equals the codec root (below). Numbering: I reviewed `ce`, `positions`, `refine`, `search` against draft §6 steps 1-5 and found no encounter-order dependence (class order by label bytes, positions sorted, leaf chosen by min root body bytes; `ids` order only affects which automorphic cell gets which number, unobservable in the rows); renaming invariance confirmed by the probe (`{:x c-1 :y c-2}` vs `{:y c-1 :x c-2}` with equal roots). BLAKE3 only: no `sha` in either source; R23 (sha256) killed by the algorithm assertion. §5.5 re-emission is correspondence-based (R36 killed) but the permutation test is vacuous (finding 4). The exhaustive search's min-selection is untested (finding 5).

**4. Independent derivations.** (a) Worked example `[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false] [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]`: L(5) = {1 2 5}, L(4) = use(4) ∪ (L(5) − def(4)) = {1 2}, saved(4,2) = {1}; matches the test and `analysis_test`. (b) Row 22 bare: act at pc 8 after `[:stream-next 3 4]`, rd 3, body registers 5, window keys {1 (the `cat` closure), 2 (the nil operand p)}, saved = {1 2}; design form `[:window 1 V 2 [:lit nil]]`. With opaque values I hand-built every row from the §2.3 tables (`:bindings` sorted by key bytes, `:env [bindings nil]`, `:window`, `:kframe [A pc env window rd]`, `:k`, `:act [A pc env window k rd]`, `:pending [:next :test/cursor]`, `:wait [act pending]`), hashed each with `jing/segment-key`, and got root `:segment/blake3-8c14cb536dd98a1c62cd00c3b577f11c23ed936d0d599186857d43fa3fb329d2` = the codec's root; the hand window row `[:window 1 [:lit :v] 2 [:lit nil]]` has id `…169e5b7a…` present in the wire, and the kframe's empty window is `[:window]` as §2.3 requires. (c) Row 21: body-ranges owners 13→`[x]`, 4→`[f]`, 0→`[cat]`, so C = {x f cat}; E = {cat f x 1 y 7}, free-env {x 99 y 7}; off C both are {y 7}; matches `lexical-names`. The tests pin no hash values (finding 5), so these were compared structurally and via the probe's ids.

**5. Scope and portability.** Only the four new files changed; nothing of 4b/5 touched. No `#'` in sources or tests. Reader conditionals are `:cljd`-first. No `for`. Nil-valued keys preserved (R19/R31 killed). The one portability defect is the multi-key vector `assoc` (finding 1). `clojure.edn` is available on cljd (used elsewhere), but its use on wire data is finding 2.

**6. Honest claims.** See finding 13. Nothing in the author's report claims a run that did not occur; my Dart run shows the sandbox block was environmental.

## Mutant table

| ID | File | Mutation | Named test | Result |
|---|---|---|---|---|
| R01 | frame | A6 `window-missing` dropped | frame-test/nil-valued-register-is-a-member | KILLED (1/3 assertions, :208) |
| R02 | frame | S2 and S3 reordered (window before K) | frame-test/first-defect-is-structural | KILLED (:224) |
| R03 | frame | E2 lexical presence not required | frame-test/free-env-mismatch | KILLED (:218) |
| R04 | frame | X1 reserved names unchecked | frame-test/free-env-mismatch | KILLED (:220) |
| R05 | frame | P4 bookkeeping key membership dropped | frame-test/tail-bookkeeping-and-delivery | KILLED (:274) |
| R06 | frame | S2 non-vector K accepted | frame-test/each-rule-refuses-its-tamper | SURVIVED |
| R07 | frame | C2 `pc > 0` bound dropped | frame-test/each-rule-refuses-its-tamper | SURVIVED |
| R08 | frame | C3 pc-body unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R09 | frame | A4 `window-self` dropped | frame-test/worked-example-saved | KILLED (:197) |
| R10 | frame | A5/A6 order swapped | frame-test/each-rule-refuses-its-tamper | SURVIVED |
| R11 | frame | E1 reserved binding unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R12 | frame | P2: `:park`/`:current-continuation` admit `:next` | frame-test/each-rule-refuses-its-tamper | SURVIVED |
| R13 | frame | A2 destination equality dropped | frame-test/each-rule-refuses-its-tamper | KILLED |
| R14 | frame | K1 return-site unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R15 | rows | row id hashed without its first child slot | rows-test/row-ids-are-jing-default | KILLED (10 failures) |
| R16 | rows | census extra cell not refused | rows-test/schema-and-literal-canonicality | KILLED (:202) |
| R17 | rows | search keeps first explored leaf (input-order tie break) | rows-test/canonical-under-is-renaming-invariant | SURVIVED |
| R19 | rows | window nil-valued key dropped on encode | rows-test/bijection | KILLED (4 failures) |
| R21 | rows | numbering reversed (cells swapped) | rows-test/canonical-under-is-renaming-invariant | SURVIVED |
| R22 | rows | `:rows` budget charge removed | rows-test/schema-and-literal-canonicality | KILLED (:208) |
| R23 | rows | digest changed to sha256 (`{:algorithm :sha256}`; first attempt with a bare keyword was a no-op and is discounted) | rows-test/row-ids-are-jing-default | KILLED (26 failures) |
| R24 | rows | extra top-level wire key accepted | rows-test/gates-and-graph-before-hashes | SURVIVED |
| R25 | rows | X1 judged after waits | rows-test/numbering-precedes-frame-defect | SURVIVED |
| R26 | rows | `:set` slots not sorted on emit | rows-test/canonical-under-is-renaming-invariant | SURVIVED |
| R27 | rows | row-order check removed | rows-test/window-sorted-and-unique | KILLED (:140) |
| R28 | rows | `:noncanonical-cells` comparison removed | rows-test/numbering-precedes-frame-defect | KILLED (:195) |
| R29 | rows | gate cycle check removed | rows-test/child-scope-and-shared-child-dag | SURVIVED |
| R30 | rows | carried code well-formedness unchecked | rows-test/task-root-checks-carried-code-and-frame | SURVIVED |
| R31 | rows | `[:lit nil]` decoded as absence | rows-test/bijection | KILLED (8 failures) |
| R32 | rows | search bound removed | rows-test/refinement-search-preserves-unordered-multiplicity | KILLED (2 failures) |
| R33 | rows | non-portable literal accepted | rows-test/schema-and-literal-canonicality | SURVIVED |
| R34 | rows | reference-free collection row accepted | rows-test/schema-and-literal-canonicality | KILLED (:203) |
| R35 | rows | decoded-equality duplicate key unchecked | rows-test/schema-and-literal-canonicality | SURVIVED |
| R36 | rows | missing stream correspondence guessed | rows-test/canonical-under-is-renaming-invariant | KILLED (:155) |
| R37 | frame | P3 captured-site unchecked | frame-test/each-rule-refuses-its-tamper | KILLED (2) |
| R38 | frame | P1 pending presence unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R39 | frame | T1 tail site unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R40 | frame | S1 closed key set unchecked | frame-test/each-rule-refuses-its-tamper | KILLED (2) |
| R41 | frame | K3 return window unchecked | frame-test/each-rule-refuses-its-tamper | KILLED (2) |
| R42 | frame | K2 return destination unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R43 | frame | C1 segment membership unchecked | frame-test/each-rule-refuses-its-tamper | KILLED |
| R44 | frame | S3 window keys not required to be registers | frame-test/each-rule-refuses-its-tamper | KILLED |
| R45 | rows | row hash equality unchecked on decode | rows-test/window-sorted-and-unique | SURVIVED |
| R46 | rows | structural reference kind unchecked | rows-test/schema-and-literal-canonicality | SURVIVED |

44 mutants: 29 killed, 15 survived. Every run: one named test, foreground, restored from the reviewer backup, `cmp` 0 and `git diff --quiet HEAD -- <path>` 0.

## Required before merge

1. Fix rows_test.cljc:238 (single-key assocs) and re-run the Dart lane; the three-host gate is otherwise within reach (frame-test is fully green on Dart; the other 11 rows tests pass on Dart).
2. Turn the two host exceptions in `decode` into refusals (findings 2, 3).
3. Make item 10's permutation test actually permute (finding 4) and pin at least one root id plus a non-automorphic search fixture (finding 5).
4. Decide the marker representation (finding 6); record the census-ordering reading (finding 8).
The remaining low items and the surviving-mutant assertions (finding 12) can land with the same follow-up.

VERDICT: CHANGES_REQUESTED
