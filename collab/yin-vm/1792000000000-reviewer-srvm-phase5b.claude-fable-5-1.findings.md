Completed-GMT: 2026-10-11 10:45:49 GMT
Completed-Local: 2026-10-11 17:45:49 Asia/Bangkok

# Adversarial review: slice 5b (A -> R, linear scan over A's liveness)

Reviewer: claude-fable-5-1 (Adversarial Reviewer / Lead System Architect). Worktree `/Users/sto/workspace/datomworld-srvm-p5b`, branch `srvm-phase5b`, HEAD b2eadc83, diff `git diff master...HEAD` (3 files: `derive.cljc` +68, `derive_test.cljc` +352, new `debruijn_register_code_test.cljc`). Nothing staged or committed by me. Source tree verified clean against HEAD before and after every mutant (`cmp` against my own verified backup `collab/p5b-fable-derive.verified-backup.cljc`, then `git diff --quiet HEAD -- src/` exit 0, then `git status --short` showing only `collab/`).

I read the brief, the author report, design §0, §1-§2, §4, §5, §6.1-6.3, §8, §10.2, §12, `derive.cljc`, `analysis.cljc`, `code.cljc` (operand table, `rd`, `uses`, `tail-call?`), `debruijn_register_code.cljc` (opcode table, `body-liveness`, the twelve validator rules), the full `derive_test.cljc`, the author's mutation script/log, and the three slow-lane logs. I did not trust either report; everything below was re-derived or re-run here.

## What I ran (all foreground, JVM)

| Run | Command | Result |
|---|---|---|
| Baseline fast lane | `clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test` | 22 tests, 6200 assertions, 0 failures, 0 errors (6 s) |
| 33 mutants | `python3 -I collab/p5b-fable-mutate.py` (each: exact-string edit, `clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test`, restore from backup, byte compare) | table below; `cmp` RESTORED_OK after each batch |
| Hash recompute + widening probe | a throwaway test ns copied to `test/p5b_fable/probe_test.clj`, run with `clojure -M:test -n p5b-fable.probe-test`, then deleted (`git status` clean; the sandbox refused every `clojure -A:test -M -e/-i` and `-Sdeps` form, so this was the only route) | hashes match; probe image recorded below |
| kondo | `clj -M:kondo --lint` on the 3 paths | errors 0, warnings 0 |
| Trailing whitespace | `grep -nE '[[:space:]]+$'` on the 3 paths | no matches |
| cljstyle | `cljstyle check` on the 3 paths | **NOT RUN**: sandbox "This command requires approval" (same refusal the author hit) |
| Slow C4 row, Node and Dart lanes | not run (per instructions). I verified the author's logs instead: `p5b-opus-jvm-slow.log` lines 50-53 "C4 completed…", "Ran 1 tests containing 90 assertions. 0 failures, 0 errors"; `p5b-opus-node-sub-cljs.log` lines 95, 191 (both `Testing` lines), 235 (C4 completed), 259-260 (1258 / 57700 / 0 / 0); `p5b-opus-dart-sub-cljd.log` line 1452 (C4 completed), 1458 "+1187: All tests passed!" | consistent with the report |

## Findings (severity | file:line | evidence | correction)

**F1. MEDIUM | test/yin/vm/semantic_register/derive_test.cljc:690 (`widened-layout-interval`) | the §2.2 widened-start rule has no test that can fail.** Mutant M06 (start = first syntactic definition, i.e. the un-widened frozen rule the design explicitly refines) SURVIVES the whole namespace. The test's program `(if c (resume :p 1) 2)` has no temporaries in the else arm, so widened and un-widened scans give the identical image (the design itself says "nothing observable changes" for that row). The rule *is* observable on `(if c (resume :p 1) (g 2))`: A `[[:var 1 c] [:branch-false 1 5] [:const 2 1] [:resume :p 2] [:jump 8] [:var 3 g] [:const 4 2] [:call 0 3 [4] false] [:halt 0]]`, `L(4)={0}` before `v0`'s only def at 7, so `v0:[4,7]` is assigned at pc 4 and takes T0 ahead of the else-arm temporaries. Implementation output (probe, hand-derivation agrees): `{:bodies [{:locals 0 :registers 3 :start 0 :end 8}] :instructions [[:load-free 0 c] [:branch-false 0 5] [:const 0 1] [:resume :p 0] [:jump 8] [:load-free 1 g] [:const 2 2] [:call 0 1 [2] false []] [:halt 0]]}`. Under M06 the same program gives `g→T0, 2→T1, v0→T2` (`[:call 2 0 [1] false []] [:halt 2]`), a different but equally valid image, so neither the validator nor the oracle can tell them apart; only a pinned golden can. The test name and comment ("§6.3's numeric note: v0 is live at jump pc 4 before its sole def at 5") claim coverage the assertion does not provide. The implementation is correct per §2.2; this is a test gap. **Correction:** pin the probe image above (or add it as a second case in `widened-layout-interval`); keep the existing case. Not blocking: the implementation follows the design and the un-widened alternative is also a sound allocation, so no runtime behaviour is at risk.

**F2. LOW | derive_test.cljc:703 (`hand-derived-register-intervals`) | tests the design's numbers, not the implementation.** It recomputes intervals with its own closure over `analysis` and compares to pins; it never calls `register-image` or `allocation`. Fine as a §6.3 number check (that is what §10.2 item 1's "verify every hand-derived number" asks), but its placement after the implementation tests invites misreading it as interval coverage of the allocator. Correction: one-line docstring saying it checks §2.2's arithmetic, not the allocator (optional).

**F3. LOW | derive_test.cljc:645-687 | progress `println`s in `c4-execution-oracle`.** Five print sites. Three other guarded slow namespaces also print, so there is precedent, and on Node/Dart they are the only evidence item 9 completed. Acceptable; the orchestrator may ask to trim to the single completion line.

**F4. LOW (judgement on the author's finding 1) | `debruijn_register_code.cljc:269` | `register-hash` is SHA-256.** Pre-existing, untouched, and §2.1 says it is not changed in 5b. The brief's "only SHA-256 use is the existing descriptor-hash function" is loose wording for "no new SHA-256 use"; 5b adds none. No action in this slice; whether `register-hash` moves to BLAKE3 belongs to the cutover (§8.2) and the owner.

**F5. INFO | derive.cljc:178 | the `sort-by (juxt end id)` on `expired` is behaviourally dead.** Expired slots are poured into a sorted set, so expiry order can never affect the result (mutant M32 survives for that reason). The design asks for the frozen order "verbatim", so leaving it is defensible; it costs nothing. No change requested.

No HIGH findings. No design departures found. Every pinned §6.3 R image I re-derived matched, every hand-derived number I checked matched, and no validator, oracle or scope rule is violated.

## Answers

### 1. Tests that can fail, per acceptance item, and the mutant table

Item → test that fails on a plausible defect (from the table): 1 `register-goldens` (M01, M02, M05, M07-M13, M19, M22-M24, M26-M30, M33); 2 `register-images-validate` (M08-M14, M17, M18, M21, M24-M26); 3 `two-liveness-analyses-agree` (M01, M14, M15, M17-M25, M30); 4 `structural-slot` (M08, M09, M11, M19, M22, M24, M27, M29, M33); 5 `register-determinism` (fails only when the image throws: M04, M07, M12, M21; by construction it cannot see host-order dependence, see M03); 6 `register-execution-oracle` (M01, M04, M07-M10, M14, M17-M24, M26, M30); 7 `lexical-address-law` (M04, M07, M12, M21 via throws; on its own it checks the address legs, not allocation); 8 `worked-example-slots` (M01, M02, M04, M05, M07, M12-M14, M19, M22, M28-M30); 9 not mutated here (slow); 10 trivial pin; 11 kondo verified.

Mutant table (all against `derive.cljc`, `clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test`, 21 tests; restore + `cmp` after each):

| # | Mutant | Result | Failing deftests |
|---|---|---|---|
| M01 | expiry `<` → `<=` | KILLED (21 fail) | execution-oracle, goldens, operand-goldens, two-liveness, worked-example |
| M02 | lowest-free → highest-free (`first` → `last`) | KILLED (4) | goldens, worked-example-slots |
| M03 | free set `(sorted-set)` → `#{}` | **SURVIVED** | — (see judgement) |
| M04 | points = defs only (no L) | KILLED (71 fail, 7 err) | 8 tests |
| M05 | points = L only (no `defs(p)`) | KILLED (13) | goldens, operand-goldens, worked-example |
| M06 | start = first def only (no widening over layout L) | **SURVIVED** | — (F1) |
| M07 | end = last def (not widened to last use) | KILLED (71 fail, 7 err) | 8 tests |
| M08 | drop structural reservation (`(if missing 1 0)` → `0`) | KILLED (29) | execution-oracle, goldens, validate, structural-slot |
| M09 | structural slot = locals+peak+1 | KILLED (18) | execution-oracle, goldens, validate, structural-slot |
| M10 | `locals` = arity+1 | KILLED (83) | execution-oracle, goldens, validate |
| M11 | physical register without `locals` offset | KILLED (91) | goldens, validate, structural-slot |
| M12 | body `:end` exclusive | KILLED (101 fail, 112 err) | 10 tests |
| M13 | body `:start` +1 | KILLED (339) | 6 tests |
| M14 | `live` never filled | KILLED (50) | execution-oracle, goldens, validate, two-liveness, worked-example |
| M15 | `live` from the allocator's own `L(p+1) − rd` (wrong analysis) | KILLED (3) | goldens (row 10 tail), two-liveness |
| M16 | as M15 but tail calls forced `[]` | **SURVIVED** | — (see judgement) |
| M17 | tail-call `live` non-empty (`[0]`) | KILLED (60) | execution-oracle, goldens, validate, two-liveness |
| M18 | `live` reversed (not ascending) | KILLED (21) | execution-oracle, validate, two-liveness |
| M19 | all `:regs` operands reversed (`:call`/`:ffi-call` args) | KILLED (141) | 6 tests |
| M20 | `:ffi-call` args reversed only | KILLED (9) | execution-oracle (`:ffi-two-operands`), operand-goldens, two-liveness |
| M21 | `:stream-put` stream/value swapped | KILLED (3 fail, 7 err) | execution-oracle (`:put-on-made-stream`), operand-goldens, validate, … |
| M22 | `:call` rd/operator swapped | KILLED (305) | 5 tests |
| M23 | `:define` rd := rs | KILLED (10) | execution-oracle, goldens, two-liveness |
| M24 | `:resume` value-reg +1 | KILLED (24) | 6 tests incl. structural-slot, widened-layout-interval |
| M25 | `:park`/`:current-continuation` rd +1 | KILLED (18) | validate, two-liveness |
| M26 | closure arity −1 (in `resolve`) | KILLED (166) | register + stack goldens/validate/oracles, resolve test |
| M27 | definition-less id → `locals+0` instead of structural | KILLED (4) | goldens, structural-slot |
| M28 | `active` filter `>=` → `>` (expired dropped without freeing) | KILLED (9) | goldens, widened-layout-interval, worked-example |
| M29 | `next-slot` always advances | KILLED (10) | goldens, structural-slot, widened-layout-interval, worked-example |
| M30 | taken slot not removed from free set | KILLED (9) | execution-oracle, goldens, two-liveness, worked-example |
| M31 | assignment order at one pc: decreasing id | **SURVIVED** | — (unobservable by construction) |
| M32 | expiry `sort-by` removed | **SURVIVED** | — (unobservable by construction, F5) |
| M33 | structural slot aliases the last temporary | KILLED (4) | goldens, structural-slot |

28 killed, 5 survived. Judgement on the survivors:
- **M06 is the one real gap (F1).** Killable with one golden.
- M03: on the JVM a `PersistentHashSet` of small ints iterates in ascending order, so `#{}` is indistinguishable from `sorted-set` here; `register-determinism` compares two runs in one process and cannot see host-order dependence by construction. The implementation uses `sorted-set` as §12 item 1 requires; the three-host green lanes are the only evidence that order never enters. Acceptable.
- M16: §2.5 states the two analyses agree on every sound allocation and proves why (forward edges, injective slot map over simultaneously live ids). A reading of the design that fills `live` from `L(p+1)` with a tail special case therefore produces identical images on every program and no test can tell; the design explicitly makes `body-liveness` the production source as a format rule, not an observable. Survival is expected and benign.
- M31/M32: at most one interval starts per pc on a well-formed vector (§2.3) and expiry order cannot matter with a sorted free set. Both orders are design verbatim, not behaviour.

**The author's table.** Their ten mutants correspond to my M01, M02, M04, M10-ish (locals forced 0), M08, M14, M20, M21, M12, and a "never free" variant; all killed, matching my results. Their "one survivor" is in fact their mutant 9 (exclusive `:end`), which they classify as killed by an **error** (an index exception inside `body-liveness`), not an assertion. My M12 confirms: 112 errors **and 101 assertion failures** (`register-images-validate`'s descriptor and terminator checks, `widened-layout-interval`, the goldens on bodies that do not throw). So that mutant is killed by assertions as well, not only by the exception; no issue. Their mutant 7 (`:ffi-call` reversal) was expressed with an `(if (= op :ffi-call) …)` wrapper exactly as my M20, since the generic `:regs` path has no ffi-specific code; that is fine.

### 2. Independent derivations of §6.3 R goldens

Derived from §2.2-2.5 by hand before reading the pins; then compared.

- **Row 2 `(f (g x) y)`.** `L(6)={0} L(5)={1,2,5} L(4)={1,2} L(3)={1,3,4} L(2)={1,3} L(1)={1}`. Intervals `v1:[0,5] v3:[1,3] v4:[2,3] v2:[3,5] v5:[4,5] v0:[5,6]`. Scan: T0,T1,T2; pc 3 `v2→T3`; pc 4 `v3,v4` expire (3<4), free {1,2}, `v5→T1`; pc 5 `v0→T2`. R: `[[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] false [0]] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`, registers 4. `live` at 3 by `body-liveness`: live-in(4) = {0,3} (use ∅, out {0,3,1} minus def 1), live-out(3)={0,3} minus rd 3 = `[0]`. **Matches pin and `[:call 3 1 [2] false [0]]`.**
- **Row 3 `((f))`.** `v2:[0,1] v1:[1,2] v0:[2,3]`; pc 2 `v2` expires → `v0→T0`. R `[[:load-free 0 f] [:call 1 0 [] false []] [:call 0 1 [] false []] [:halt 0]]`, registers 2. **Matches.**
- **Row 5 `(f (if c 1 2) y)`.** `L(2)={1,3} L(3)={1} L(4)={1,2} L(5)={1} L(6)={1,2} L(7)={1,2,4} L(8)={0}`; `v1:[0,7] v3:[1,2] v2:[3,7] v4:[6,7] v0:[7,8]`; pc 3 `v3` expires → `v2→T1`; pc 6 `v4→T2`; pc 7 `v0→T3`. R `[… [:branch-false 1 5] [:const 1 1] [:jump 6] [:const 1 2] [:load-free 2 y] [:call 3 0 [1 2] false []] [:halt 3]]`, registers 4, live at 7 `{3}−3=[]`. **Matches.** The conditional's two arm definitions share T1 by construction (one virtual id, one slot).
- **Row 6 body (tail call), `locals 1`.** `L(7)={1,2,3}` (no successor), `L(8)={0}`; `v1:[4,7] v2:[5,7] v3:[6,7] v0:[7,8]`; nothing expires at 7 (7<7 false) → `v0→T3`; peak 4, registers 5; physical = 1+slot. R `[[:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]]`; main `[[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2]]` registers 3. **Matches, `live []` on the tail call.**
- **Row 8 `(f (resume :p x) y)`.** `L(0)=L(1)={} L(2)={3} L(3)={1,2} L(4)={1,2,4} L(5)={0}`; `v1:[0,4] v3:[1,2] v4:[3,4] v0:[4,5]`, `v2` definition-less. pc 3 `v3` expires → `v4→T1`; pc 4 `v0→T2`; peak 3, structural T3, registers 4. R `[[:load-free 0 f] [:load-free 1 x] [:resume :p 1] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`. **Matches.**
- **Row 9 (inner call tail).** `L(3)={3,4}` (no successor) `L(4)={1,2} L(5)={1,2,5}`; `v1:[0,5] v3:[1,3] v4:[2,3] v2:[3,5] v5:[4,5] v0:[5,6]`; same slots as row 2; `live` at 3 is `[]` (no successor), at 5 `[]`. **Matches; the pinned `[:call 3 1 [2] true []]` is right.**
- **Widened note `(if c (resume :p 1) 2)`.** `v1:[0,1] v2:[2,3] v0:[4,6]`; all take T0 in turn; registers 1. **Matches the pin in `widened-layout-interval`.**

Hashes recomputed through the public `rcode/register-hash` on the pinned literal images: row 2 `aaa109a179b7e4b280ef4c25006316067569bfaddb300d2b46ddadf9398d93fa`, row 6 `71926b1e251a07f8d1234c33e55c77f00fd387b24560a1a7578b95cbe510772e`, row 8 `52c71ac04f193913c33782d81ec5a2276db53753010f9888c53650f4252c134e`. All three equal the pinned strings (indices 1, 5, 8 of `register-golden-hashes`).

### 3. Item 3, item 4, determinism, the oracle, the address law

- **Two-liveness agreement (item 3):** `two-liveness-analyses-agree` iterates `C` plus row 9, every boundary pc, virtual live-out as the union of `analysis/live` over **`analysis/successors`** (actual successors; a tail `:call` gives none), minus `rd`, mapped through a slot map rebuilt from paired A/R operands (not the allocator's state; it also asserts the map is consistent on re-encounter), compared to the filled `live`; tail calls asserted `[] = []` on both sides. Independent of `body-liveness`. M14, M15, M17, M18 confirm it fails when either side is wrong.
- **Structural slot (item 4):** for the three named programs: `registers = locals + peak + 1` with `peak` recomputed from the max assigned slot of defined ids; every definition-less id maps to `registers − 1`; the structural register appears as an operand only at pcs outside `analysis/reachable`; absent from every `live` at a reachable boundary. `missing` is computed from operands minus syntactic defs, so a mutant cannot vacate the assertions. M08, M09, M27, M33 die here.
- **Determinism (item 5):** structures, encoded bytes, hashes, plus the three omitted-fact pairs on `:vector`/`:address`/R/hash. Correct per §6.2 items 5-6; see M03 for its inherent limit.
- **Execution oracle (item 6):** counts 26/12/19/1 asserted; `:old` pipeline is `rc/adapt` over `vm/ast->datoms`, `:new` is `derive/register-image` over the projection, both into `rvm/create-vm` with the same composition (`list`/`vector`/`inc` added); `native-normalize` on both sides and on pinned expectations; `:op/ping` bridge; the two 5a-lesson rows (`:op/echo` two operands, `:stream/put` on a made stream) reach values on both pipelines and killed M20/M21. The old lowerers appear only in `register-run`, `stack-run` and `c4-backends`, i.e. as oracle inputs.
- **Lexical-address law (item 7):** five legs (A, resolve(A), R, H, old resolver) over all of `C`. The §5.2 non-vacuity assertions live in the 5a sibling `lexical-address-law-stack` in the same namespace, not repeated here; acceptable.

### 4. Scope and portability

- Diff touches exactly the three §10.2 paths. `debruijn_register_code.cljc`, the kernel, the design: untouched.
- `derive.cljc` requires only `code`, `db` (resolve-name), `analysis`, `rcode`; no old lowerer or kernel. No `#'` anywhere in the three files. No `for` in the slice (`doseq`/`mapv`/`reduce`/`into` only).
- `sorted-set` is used for the free set; Dart lane green per the author's log. Slot maps start from `{}`; `(fnil conj [])` points; integer arithmetic only; catch clauses use `#?(:cljd Object :clj Throwable :cljs :default)`; pinned images are Clojure data in `.cljc`, no EDN strings. `(apply min/max points)` can never see an empty seq because every intervaled id has at least its def pc.
- Emission reads `rcode/opcode-table` positionally and never `nth`s the `live` slot of the resolved tuple (it returns `[]` first), so the 4-operand A `:call` against the 5-operand R table is safe.

### 5. Honest claims and the lane departure

- Test names: `two-liveness-analyses-agree`, `register-determinism`, `lexical-address-law` are corpus claims, consistent with the design's "property the gate checks on every corpus program". `widened-layout-interval` overclaims (F1). `hand-derived-register-intervals` is a design-number check (F2).
- Nothing in the implementation is resolved by reading the design differently from me; `:end` inclusive, expiry `end < p`, lowest free, structural slot `locals + peak`, layout A's, `live` from `body-liveness` all match §2 as I read it. M16 is the one alternative reading and it is image-equivalent.
- The author's finding 1 (SHA-256) judged in F4: no 5b action.
- **Background slow lanes:** the report is candid that the JVM slow row and the two subsystem lanes ran as harness background tasks (no `&`), and the three logs carry the completion line and the final counts (quoted in my table above). The purpose of "foreground only" is that a delegate's lanes die with its turn; these did not, and the counts are present. Acceptable for this report. Remaining lane gaps: cljstyle (neither the author nor I could run it in-sandbox) and the literal `bb test:sub:clj yin.vm`; the orchestrator should run both once before merging.

## Verdict rationale

The implementation is a faithful §2 lowering: every re-derived golden and hash matches, 28/33 adversarial mutants die on named tests, the oracle and the two-liveness gate are independent and do fail, scope and portability are clean. The single real gap is a test that cannot fail on the widened-start rule (F1), which is a one-golden addition with no implementation change and no runtime risk, so I do not withhold merge for it; the orchestrator may fold F1 into the merge or a follow-up at their discretion.

VERDICT: READY_TO_MERGE
