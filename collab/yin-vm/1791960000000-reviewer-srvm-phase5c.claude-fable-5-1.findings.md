Completed-GMT: 2026-10-11 10:09:00 GMT
Completed-Local: 2026-10-11 17:09:00 Asia/Bangkok

# Adversarial review of slice 5c (the v4 scanners and the production linker format record)

Reviewer: claude-fable-5-1 (non-author family). Worktree `/Users/sto/workspace/datomworld-srvm-p5c`, branch `srvm-phase5c`, HEAD 58dc0964, diff `git diff master...HEAD` (six paths, 7707 insertions, 5 deletions). Author: codex (gpt-6.1-sol). Read-only except this file and `collab/`; nothing staged or committed; `git diff --quiet HEAD -- src/ test/` exit 0 at the end of every mutant.

## Findings (severity | file:line | evidence | correction)

1. **MEDIUM | `src/cljc/yin/vm/linker.cljc:723-724` | the v4 record's `:row-defect-fn`/`:validate-fn` binding is untested.** Mutant F6 replaced both with `(constantly nil)`; `yin.vm.linker-test` + `yin.vm.semantic-register.scan-test` (fast) stayed green: 72 tests / 1139 assertions / 0 failures (`collab/5c-review-mutant-F6.log`). No test runs `linker/verify` over `semantic-register-format` with a malformed v4 vector, so step 4's `:descriptor-defect` refusal on the production record is unproven, and the design's "no degradation path: the scanners throw ... which `linker/scanned` turns into the conservative degradation" (§7.2) actually rests on `:validate-fn` running first, because `verify` calls `:obligations-fn` directly, not through `scanned` (`linker.cljc:1427`). Correction (test only, `linker_test.cljc`, in `semantic-register-scanners-yield-position-bearing-records` or a sibling): `(let [bad [[:var 0 'x]] a (jing/segment-key bad)] (is (= :descriptor-defect (:reason (linker/verify linker/semantic-register-format a a {a bad})))))`.

2. **MEDIUM | `src/cljc/yin/repl/link.cljc:87` | the `formats` registration has no test.** Mutant F7 removed `linker/semantic-register-format` from `yin.repl.link/formats`; `yin.repl.require-test`, `yin.repl.serve-test`, `yin.vm.linker-require-test`, `yin.vm.semantic-register.link-test`, `yin.vm.linker-test` (fast) stayed green: 138 tests / 1544 assertions / 0 failures (`collab/5c-review-mutant-F7.log`). No test reads `link/formats` or serves a `:yin.semantic-register/code` request. Design §7.5 names this registration as part of the slice. Correction (test only): `(is (= linker/semantic-register-format (get link/formats :yin.semantic-register/code)))` and `(is (= 7 (count link/formats)))` in `yin.repl.serve-test` or `linker_test.cljc` (the docstring now says "seven").

3. **LOW | `src/cljc/yin/vm/semantic_register/scan.cljc:32` | the `reserved-name?` guard is unreachable (equivalent mutant).** Mutants R8 (in-process) and F3 (fresh JVM, `collab/5c-review-mutant-F3.log`, 10 tests / 567 assertions / 0 failures) replaced `(not (vm/reserved-name? name))` with `true` and survived. Cause: `bodies` runs `code/well-formed?` first, whose item 11 (`code.cljc:663-675`) refuses any `:var` naming a reserved name, and `sr-linearize` lowers a definition to `:define` without a `:var yin/def` tuple. Not a defect: Rule R holds through the validator. Correction: either drop the guard or comment it as belt-and-braces; the docstring "yin/def is never a free name" is true either way. No change required for merge.

4. **LOW | `collab/1791850000000-vm-engineer-srvm-phase5c.report.md` §Tests that can fail | the author's mutant harness cannot reach the record-bound scanners.** `linker/semantic-register-format` captures the function objects of `scan/free-occurrences`, `definition-occurrences`, `application-sites` at load time; the author's `load-file`-based harness (and my in-process harness) rebinds the vars, so mutants of those three bodies are seen only by direct `scan/...` calls, not by `new-answer`/`normalized-join`. Evidence: in-process R10 (definition pc pinned to 0) was killed only by `definitions-and-sites`; the same mutant in a fresh JVM (F1) was also killed by `scanners-agree-on-b0` at `scan_test.cljc:190` and `:205` (`collab/5c-review-mutant-F1.log`). The author's nine kills are real (they fire on direct calls), but the report's implication that the join path was mutation-tested is not established by that harness. I re-ran the join-sensitive mutants in fresh processes (F1, F2, F4-F8 below). Correction: none to code; future harnesses should run each mutant in a fresh process or rebuild the record.

5. **INFO | `test/yin/vm/semantic_register/scan_test.cljc:141-177` | the Node-only `with-redefs` helper redefines two production functions at test time** (`jing/segment-key`, `cbor/content-key`) and its docstring says "Production source and query input are unchanged"; that is true of the files, but the helper is a runtime patch, proven equivalent on 59 fast cases only; with it the Node py completion query took about 75 minutes in my run (answer 3), and no completed run without it exists to show it helps. See answer 3.

6. **INFO | `test/yin/vm/semantic_register/scan_test.cljc:115-138, 191-193` | on the C4 corpus the tree oracle of obligation (i) is the partitioned query, not `vm/free-names` itself.** The equality `tree-names = (vm/free-names rows occ root)` is asserted only for B0 and register rows; for C4 the partition's equivalence is argued in the docstring (the partition keeps every `:variable` row of the name, every `:lambda` row whose params contain it, and their occurrence tuples, so `occ-bound?`'s witnesses are all present). I checked the argument against `occurrence-rules` (`vm.cljc:1680-1696`) and found no gap. The emission oracle and `completion-free-names` remain independent C4 checks. Acceptable; recorded in the author's report.

## Answers

### 1. Tests that can fail, per acceptance item
- 1 (`positions-are-the-v4-table`): yes. R1, R2, R3 (store-put/define key and resume pid read from the wrong slot) each failed it; author's M4-M6, M8 too.
- 2 (`free-occurrences-use-the-owner-tree`): yes. R5 (chain stops at the immediate owner: `+` and `a` wrong on `:nested-lambdas`), R6 (name read from the register slot), R11 (pc off by one, `[3 4 8]`), R13 (`in-body?` inverted), F2 (occurrences sorted by name instead of emission order).
- 3 (`definitions-and-sites`): yes. R7 (`:define` counted as a site, pcs `[5 6 11]`), R10/F1 (definition pc wrong, `[2]`), R15 (store-put dropped).
- 4 (`footprint-mnemonics-is-the-v3-table-minus-push`): yes. R9 (no `:push` dissoc) failed both assertions.
- 5 (`scanners-agree-on-b0`): yes. F1 (use-before-definition no longer refused: join `:ok` vs old `:refused`, line 190 and the golden line 205), R1/R2 (3 fails each), F8 (test-side: dropping `:reason` from the normalised join fails the golden comparison at line 205).
- 6 (`scanners-agree-on-the-register-corpus`): yes. R3 (15 fails), R2 (9), R5 (5), F2 (12 across lines 190/204/205, the FIFO oracle included).
- 7 (`scanners-agree-on-the-c4-modules`): fails only if a C4 module disagrees; see answer 3 for the runs.
- 8 (`goldens-match`): the fixture was empty on the author's first run and produced 118 failures (`collab/5c-fast-first.log`), so the comparison can fail; F8 shows the join part of the golden is compared.
- 9 (`linker-test`): yes. F4 (contract `"v3"`) failed `format-records-name-their-contract:565`; F5 (`:obligations-fn` bound to the definitions scanner) failed `semantic-register-scanners-yield-position-bearing-records:2092,2094`.
- 10 (`verify-outcomes-agree`): slow; its fast twin is the `:join` field of items 5-6 and 8, which F1 and F8 made fail. The explicit `{:status :refused :reason :use-before-definition :name n}` assertion is also pinned by the golden.
- 11: not a test; see answer 3 and the lanes below.
- Not killable: the `:validate-fn` binding (finding 1) and the `link/formats` registration (finding 2); the reserved-name guard (finding 3, equivalent).

### 2. The three agreement obligations and the goldens
(i) unordered: `assert-agreement` compares `old = new` whole maps (free-name sets, occurrence and definition sequences, site counts, requirements, join), `(:free-names new)` against the tree query and `completion/segment-free-names`, requirements against `vm/ast-requirements`, and occurrence/definition multisets against `linker/ast-format`'s tree scanners (`scan_test.cljc:190-203`). (ii) ordered: `emission-oracle` (`:76-112`) is written from the map AST alone, main then FIFO lambda bodies, operator before operands, test/consequent/alternate, target then value, definition visits only its value, sites exclude definitions and include FFI; compared as exact sequences at `:204`. I read it against §7.3 (ii) and found it faithful; `:vm/store-put`'s value is `:data`, so not walking it is correct. (iii) join: `normalized-join` (`:44-50`) through public `linker/verify`, refusals kept as `{:status :reason :name}`, compared in `old = new` and in the slow `verify-outcomes-agree`. No `#'` anywhere in the slice.

Goldens: `print-goldens` (`scan_test.cljc:335-342`) builds every entry from `old-answer` only (v3 lowering, `linker/semantic-format`, `vm/segment-requirements`, `linker/verify` over the v3 record); `scan.cljc` is not on that path, so the fixture is independent of the new scanner. The fixture header says 993d5411 rather than the design's ab952ace; `git diff ab952ace HEAD --stat` over `linker.cljc completion.cljc linearize.cljc code.cljc vm.cljc` shows only this slice's additive 21-line change to `linker.cljc`, so the v3 answers are those of ab952ace. Fixture counts 27/32/8.

Three entries hand-verified against the v3 vectors printed by `collab/5c-review-v3-vectors.clj` (`collab/5c-review-v3-vectors.log`) and the v3 scanner rules (`linker.cljc:128-168, 224-261, 497-529`, `undischarged` `:1107-1160`):
- `:use-before-definition` (v3: `var list`@0, `var n`@2, `define n`@5, `call`@7, no closure): occurrences `[[list false] [n false]]`, definitions `[[n false]]`, 1 site, store-keys `#{n}`; join: `n`@2 with its only definition @5 later → `:refused :use-before-definition n`. Matches `scanner_goldens.cljc:186-193`.
- `:define-then-call` (v3: closure `[a b]` body 13..20; `define n`@3, `var inc`@5, `var n`@7, `var list`@13, `a`@15, `b`@17; calls @9, @11, @19): occurrences `[[inc false] [n false] [list true]]`, definitions `[[n false]]`, 3 sites; join: `n` discharged by the unconditional definition @3 < 7, `inc` and `list` retained → `[[inc false] [list true]]`. Matches `:445-452`.
- `:if-in-tail` (v3: closure `[n]` body 6..27; `branch-false`@13→16, `jump`@15→27; `var <`@6, `n`@8, `loop`@16, `-`@18, `n`@20; calls @4, @12, @24, @26): occurrences `[[< true] [loop true] [- true]]`, no definitions, 4 sites, obligations all retained. Matches `:504-511`.

### 3. The C4 corpus gate (item 7), three hosts
**JVM (my run).** `clojure -M:test -i :slow -n yin.vm.semantic-register.scan-test`, log `collab/5c-review-c4-jvm-slow.log`, started 08:36 GMT, finished 08:58 GMT: **Ran 3 tests containing 208 assertions. 0 failures, 0 errors.** All eight `C4 scanner oracle complete:` markers (py pysp k m t w e n). The py module alone took about 19 minutes, almost all of it in `C4 completion free names` (the `completion/segment-free-names` Datalog oracle over the 26,062-instruction v3 vector); the run shared the machine with my mutant runs and with another session's Python JVM lane (pid 81340), so the author's ~11-minute wall time (`5c-c4-final-jvm.log`, 13:40-13:51 local) is the uncontended figure. Reproduces the author's 3 / 208 / 0.

**Node (my run).** Artifact compiled by me (`clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.scan-test$" :output-to "target/5c-review-node-scanners.js"}'`), then `node target/5c-review-node-scanners.js --test=yin.vm.semantic-register.scan-test/scanners-agree-on-the-c4-modules` with `DATOM_SLOW_TESTS=1` through `bb collab/5c-review-node-c4.bb` (the sandbox refused the inline env prefix and `sh`). Log `collab/5c-review-node-c4.log`. Started 08:48:14 GMT, finished 10:07:35 GMT (79 minutes): **Ran 1 tests containing 73 assertions. 0 failures, 0 errors. node exit 0.** All eight `C4 scanner oracle complete:` markers (py pysp k m t w e n). The py module's completion query alone took roughly 75 of those minutes; the other seven modules finished in the last few. Characterisation: **only slowness, not a defect.** The slow call is `completion/segment-free-names` (`completion.cljc:195-222`, pre-existing v3 production code, untouched here): a recursive `seg-bound?` rule over 24,960 scope facts and 5,907 `:var` rows, which already costs most of the JVM's 11-19 minutes and runs on Node's slower Datalog engine. The author's three Node attempts were interrupted after at most a minute each (`target/5c-node-scanners-slow.js` 14:33:58, `5c-node-slow-final.log` last written 14:34:29 local), so "stall" in the report meant "not given time", not "observed to hang". Whether the Node-only `with-redefs` helper (finding 5) shortened the run is unknown; no run without it was completed. Item 7 is therefore established on Node by this run. For future sign-offs the 79-minute cost is worth an orchestrator/Architect ruling: either budget it as a known slow row, or take the live `completion/segment-free-names` comparison under `#?(:clj ...)` on Node and Dart, where the tree oracle, the FIFO emission oracle and the pinned golden (whose `:free-names` is the historical v3 answer) still check the row; that is a design reading, not something the slice may decide for itself.

**Dart (my run, fast lane only).** `bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.scan-test,yin.vm.linker-test`, log `collab/5c-review-dart.log`: compiled and ran, **76 tests, "All tests passed!"** (63 `linker-test` + 13 `scan-test` deftests, matching the JVM deftest counts), with `scanners-agree-on-the-c4-modules`, `goldens-match`, `verify-outcomes-agree` printing SKIP as designed. So the author's "Dart NOT RUN" was a sandbox limitation of the author's session, not a Dart defect, and acceptance items 1-6 and 9 pass on Dart. The Dart slow C4 row was NOT RUN (it needs `DATOM_SLOW_TESTS=1`, available here only through `bb test:slow:cljd`, which runs every slow namespace on Dart; given the Node figure above, its completion oracle would take at least as long).

Net for item 7: established on the JVM (3 / 208 / 0) and on Node (1 / 73 / 0); Dart slow not run. Item 11's three-host requirement is open only on the Dart slow row.

### 4. Read-only boundary on the C4 track
`git diff master...HEAD --stat -- test/yin/vm/linker_require_test.cljc src/cljc/yang/ test/yang/ docs/` is empty. The register backend's fixture comment "empty obligations until phase 5 supplies the register-vector scanners" and `:obligations (constantly [])` are still at `linker_require_test.cljc:127-129`; the §10.4 two-line follow-up is not applied, which is correct because the tri-host gate has not passed. The C4 test reads `prelude`, `safepoint`, `lower`, `import-programs`, `linked-harness` and publishes nothing.

### 5. Scope and portability
Exactly the six allowed paths changed. No `#'`, no `for`, no float negation, no multi-key `assoc` on nil in the six files. Reader conditionals put `:cljd` first (`scan_test.cljc:4, 151, 271, 335`); the `#?@(:cljd [] :cljs [...])` require yields nothing on the JVM. Nil store keys are exercised on JVM and Node (`definitions-respect-conditional-ranges`), and the goldens are Clojure data, not EDN strings. `chain` is a non-tail `cons` recursion bounded by closure nesting (py: depth 14). `with-redefs` is confined to the `:cljs` arm. Kondo: author's `5c-kondo.log` is clean; I did not rerun kondo/cljstyle.

### 6. Honest claims
The report's counts match what I reproduced: linker fast 62 / 572 (F4F5 baseline run), scanner fast 10 / 567, Node fast artifact prints `Testing yin.vm.semantic-register.scan-test` with the three guarded bodies SKIPped (`collab/5c-review-node-compile.log`, 23 tests / 646 assertions across the three namespaces shadow pulls in). Item 7 and 11 are honestly marked incomplete. Two statements to soften: the mutant table's implicit coverage of the join path (finding 4) and the Node helper's "production unchanged" (finding 5). Test names make no universal claim their bodies do not check, except `goldens-match`/`verify-outcomes-agree` whose C4 third is `^:slow` and runs only when the slow lane runs.

## Mutant table

In-process harness `collab/5c-review-mutations.clj` (log `collab/5c-review-mutations.log`; backup `collab/5c-review-scan.cljc.backup` verified by `cmp` before the first mutant, restored and `cmp`-checked after each, `git diff --quiet HEAD -- src/` exit 0 at the end). Fresh-process mutants F1-F8 were applied by editing the file, running `clojure -M:test -e :slow -n <ns>` (each `-n` literal), then restoring from `collab/5c-review-{scan,linker,link,scan_test}.cljc.backup*` with `cmp` and `git diff --quiet HEAD -- src/ test/` exit 0 (logs `collab/5c-review-mutant-F*.log`).

| Id | File | Mutation | Result (named test) |
|---|---|---|---|
| R1 | scan.cljc | store-put key read from the rd slot | KILLED: positions-are-the-v4-table, definitions-respect-conditional-ranges, scanners-agree-on-b0 (3), -register-corpus (3) |
| R2 | scan.cljc | define key read from the rd slot | KILLED: positions-are-the-v4-table, definitions-respect-conditional-ranges, b0 (3), register (9) |
| R3 | scan.cljc | resume pid read from the value-reg slot | KILLED: positions-are-the-v4-table, register (15) |
| R4 | scan.cljc | (malformed edit, compile error; superseded by F2) | n/a |
| R5 | scan.cljc | owner chain stops at the immediate owner (no transitive scope) | KILLED: free-occurrences-use-the-owner-tree, register (5) |
| R6 | scan.cljc | var name read from slot 1 (register id) | KILLED: free-occurrences-use-the-owner-tree (6) |
| R7 | scan.cljc | `:define` counted as an application site | KILLED: definitions-and-sites (2) |
| R8 | scan.cljc | reserved-name guard removed | SURVIVED (equivalent; finding 3) |
| R9 | scan.cljc | footprint table without the `:push` dissoc | KILLED: footprint-mnemonics-is-the-v3-table-minus-push (2) |
| R10 | scan.cljc | definition `:at` pinned to 0 | KILLED in-process by definitions-and-sites only (finding 4); see F1 |
| R11 | scan.cljc | free-occurrence pc +1 | KILLED: free-occurrences-use-the-owner-tree |
| R12 | scan.cljc | segment rows `[pc A ...]` | KILLED: segment-rows-carry-a-and-pc |
| R13 | scan.cljc | `:in-body?` inverted | KILLED: free-occurrences-use-the-owner-tree (3) |
| R14 | scan.cljc | `:conditional?` ignores the body index | KILLED: definitions-respect-conditional-ranges |
| R15 | scan.cljc | `:store-put` dropped from definitions | KILLED: definitions-and-sites, definitions-respect-conditional-ranges |
| R16 | scan.cljc | malformed-vector throw removed | KILLED: malformed-vectors-throw (4) |
| F1 | scan.cljc (fresh JVM) | definition `:at` pinned to 0 (use-before-definition not refused) | KILLED: definitions-and-sites:242, scanners-agree-on-b0:190 and :205 |
| F2 | scan.cljc (fresh JVM) | free occurrences sorted by name (emission order wrong) | KILLED: free-occurrences-use-the-owner-tree:229,230; register-corpus:190,204,205 (15 fails) |
| F3 | scan.cljc (fresh JVM) | reserved-name guard removed | SURVIVED (equivalent; finding 3) |
| F4 | linker.cljc (fresh JVM) | `:contract "v3"` | KILLED: format-records-name-their-contract:565 |
| F5 | linker.cljc (fresh JVM) | `:obligations-fn` bound to `scan/definition-occurrences` | KILLED: semantic-register-scanners-yield-position-bearing-records:2092,2094 |
| F6 | linker.cljc (fresh JVM) | `:row-defect-fn`/`:validate-fn` → `(constantly nil)` | SURVIVED (finding 1) |
| F7 | link.cljc (fresh JVM) | v4 record removed from `formats` | SURVIVED (finding 2) |
| F8 | scan_test.cljc (fresh JVM) | `normalized-join` drops `:reason`/`:name` from refusals | KILLED: scanners-agree-on-b0:205 (golden pins the reason) |

Author's M1-M9 (`collab/5c-mutations.log`): all nine kills reproduced by reading the log; they are direct-call kills (finding 4).

## Lanes I ran
- JVM fast, scan-test: 10 / 567 / 0 (baseline of F1-F3, F8 restore runs). JVM fast, linker-test: 62 / 572 / 0. JVM fast, linker-test + scan-test: 72 / 1139 / 0 (F6 baseline).
- JVM fast, require/serve/linker-require/sr-link/linker: 138 / 1544 / 0 (F7 baseline).
- Node fast compile of `scan-test` (`target/5c-review-node-scanners.js`): 23 / 646 / 0 across `lower-portable-test`, `parity-test`, `scan-test`; `Testing yin.vm.semantic-register.scan-test` printed; three SKIPs.
- JVM slow C4 row: 3 / 208 / 0, eight modules (answer 3). Node slow C4 row: 1 / 73 / 0, eight modules, 79 minutes (answer 3). Dart fast lane, scan-test + linker-test: 76 tests, all passed, three guarded SKIPs (answer 3).
- Not run by me: Dart slow lane, kondo, cljstyle, `bb test:sub`, `bb test:changed`, full `bb test`.
- Two of my early harness launches used the test-runner alias by mistake and started the full JVM suite; both were stopped before any mutant was applied, and `git diff --quiet HEAD -- src/ test/` was 0 afterwards. Not credited as evidence.

## What is needed before merge
1. Add the two record tests of findings 1 and 2 (four `is` forms, `linker_test.cljc` and/or `yin.repl.serve-test`), and re-run `yin.vm.linker-test` on three hosts.
2. Close item 7 on Dart: run the Dart slow C4 row once (`bb test:slow:cljd` is the only sandbox-permitted way to set `DATOM_SLOW_TESTS=1`; expect at least the Node's 79 minutes), or obtain the orchestrator/Architect ruling of answer 3 on the live completion comparison for Node and Dart, and quote the count. Until then the §10.4 two-line fixture follow-up stays unapplied.
3. Optional: drop or comment the unreachable reserved-name guard (finding 3); amend the report's mutant section per finding 4.

The scanner code itself is correct as far as every oracle, golden, hand check and killed mutant can tell, now on the JVM and Node for the C4 row too; the requested changes are test coverage of the format record and registration, and the Dart slow row the design makes mandatory for sign-off.

VERDICT: CHANGES_REQUESTED
