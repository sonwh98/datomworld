Completed-GMT: 2026-10-16 13:50:00 GMT
Completed-Local: 2026-10-16 20:50:00 +07
Coding-Agent: claude (claude-fable-5-1), Architect
Session-ID: pending

# Architect gap list: `yin.vm.semantic` against the design (master @ 6b001f87)

Brief: `./collab/1793420000000-architect-gap-list.prompt.md`. Worktree `datomworld-gap-review` = origin/master @ 6b001f87. Read-only; the only file written is this report. Every file:line below was read in this tree today. "Reported" marks lane numbers I could not reproduce here (no `node_modules`, no `lib/cljd-out`; the any-pc assessment `collab/1792430000000-architect-any-pc-snapshot.report.md` lives in the main checkout, which this session may not read, so §1.2 is my own feasibility reading of the code, not a restatement of that report).

Classification key: **(a)** accepted by an owner ruling, **(b)** open design work, **(c)** ledgered defect or debt, **(d)** doc-only.

## 0. Verdict

**The frozen design is complete.** Every clause of `docs/design/yin.vm.semantic.md` §§1-9, every §10 gate 1-8 and §§11-12 is met in the tree (spot-check in §2). Nothing the frozen design promises is unimplemented, and no row below is a blocker to calling `yin.vm.semantic` a finished kernel.

**The design around it is complete except X**, where X is:

- **X1 (c, P1)** the row carrier drops `:try?` from link waits, so a lifted `try-require` waiter resumes under the plain `require` contract (§4 row G1). The only P1 on the kernel's own carrier; the ledger assigns it to L-d-1b.
- **X2 (b)** the two de Bruijn backends silently hang, rather than refuse, a task carrying a retained FFI request (row 4): the exclusion is harness-only, not a pre-attach gate (§1.3). Half a day converts it to a named refusal without widening the fragment.
- **X3 (b, owner-gated)** three capability questions the owner has deferred: any-pc snapshots / `:running` roots under custody (slice 6d, §1.2), the REPL's own export into its custody composition (UCF §7.11 "yin.repl.core handoff composition", §1.1), and the backend fragment (retained-FFI seam, §1.3).
- **X4 (evidence)** no Dart slow-tier run is on record for phase 8; the 89/61/350 harness laws, the C4 execution oracle and the Stage E kill rows have JVM and Node evidence only (§4.2).

Not blockers: the P2/P3 ledger rows (§4.1), the lower-side design questions 6b Q1-Q10 (§3), and the doc residues (§5).

## 1. The seven questions

### 1.1 The REPL custody gap: closed, with one qualification

**What the gap was.** Until 8c the REPL's `:semantic` evaluator was the old stack-shaped v3 kernel and its two de Bruijn evaluators were fed through `packet->named-datoms` and the old lowerers (phase-8 plan §1.7, risk 5). The row carrier of phases 6-7 (`rows/lift-task`, `rows/lower-task`) lifts only the register kernel's frames, so nothing a REPL session ran could be exported into custody. The owner accepted that until phase 8 (orchestrator log :11250; continuation §8.4 :2200-2201; UCF §7.11 :3366).

**Closed in code (a, ruled; now met).**
- `src/cljc/yin/repl.cljc:138-144` `vm-constructors`: `:semantic semantic/create-vm` is the register kernel (`yin.vm.semantic`, `semantic.cljc:350`).
- `repl.cljc:154-156` `packet->vector`: the one projection, `linearize/project-rows` over the expanded packet.
- `repl.cljc:204-229` `program-loaders`: `:semantic` through `semantic/load-vector` under `vm/semantic-contract` (`"v4"`, `vm.cljc:266`); `:stack`/`:register` through `derive/stack-image`/`derive/register-image` over the same vector. `packet->named-datoms` is gone.
- Every machine the REPL runs is therefore a `SemanticVM` (or a backend over an A-derived image) that `rows/lift-task` (`ucf/rows.cljc:1737`) and `layout/lift-task` accept. Continuation §8.4 :2183-2186 records the closure at 8c; that text matches the code.

**Qualification (b).** The REPL carries a custody composition only as data: `repl.cljc:880-886` ("the shell only carries it ... Nothing in the shell reads or steps it"), stepped by `repl/main.cljc:962-1004`. There is no REPL command that exports the session's evaluator machine into that composition (`command-heads`, `repl.cljc:239-240`: no `export`). So the gap is closed in the sense the owner ruled (the kernel the REPL runs is liftable) and UCF §7.11 :3381-3383 is still accurate: "still owed ... the `yin.repl.core` handoff composition". Effort if wanted: an `(export)` command plus `holder/export` wiring and one `dht_process_test` row, 2-3 days. Risk of leaving: none to the kernel; a product gap only.

### 1.2 Running roots: only waiting points enter custody; any-pc is feasible, not designed

**Current state (design and code agree).**
- UCF §7.4.1 :672-674: "a continuation may be lifted only at a safepoint"; frozen §4.1 table; continuation §0.2.
- `ucf/rows.cljc:1649-1660` `lift-task*`: `:yin.k/not-quiescent` when the ready queue is non-empty; `:yin.k/not-at-safepoint` when `:control` is non-nil (a machine between instructions); kind is `:parked`, `:blocked` or `:halted`, anything else `:not-at-safepoint`.
- `ucf/holder/export.cljc:107-112, 128-135` `first-refusal`/`enter`: the holder refuses to enter exporting unless every machine of the tree is quiescent.
- Tests: `semantic/safepoint_harness_test.cljc:1051` `a-nonempty-ready-queue-is-not-quiescent`, `:952` `refusals` (the `:not-at-safepoint` row: a kernel stepped one instruction past a `:const`).
- Owner default (log :11250): "whether `:running` roots enter custody (default: waiting-point tasks only)".

**Feasibility of slice 6d (b).** The kernel's between-instruction state is already a closed data shape: `vm-hot` with fuel returns `put-registers machine seg pc W E K` (`semantic.cljc:142-143, 50-55`), so a snapshot `{seg pc W E K}` exists at every instruction boundary and `analysis/live` (`analysis.cljc:86-89`) gives its window as `L(pc)` with no `rd` subtraction. What a slice must add:

| piece | where | note |
|---|---|---|
| a third state kind (`:run [A pc env window k]`, no `rd`, no pending) or `:act` with a `:none` delivery | `rows.cljc:49-69` row table, `pending-table` untouched | window = `L(pc)`; sorted keyed row as today |
| validator branch | `frame.cljc:133-140` (`:site`, `:destination`, `:deliver`, `window-defect`) | `window-self` does not apply; `window-extra/missing` against `L(pc)` |
| lift kind `:running` | `rows.cljc:1653-1660` | accept `:control` set when ready queue and wait set are empty; refuse mid-effect (never reachable: `vm-hot` only stops at boundaries) |
| lower | `rows.cljc:2087` `restore-task` | `put-registers` with control set, nothing to deliver |
| custody by kind | `rows.cljc:1701-1713` `header-custody`, `custody-baseline :1286`, holder `at-safepoint?` `driver.cljc:2147`, `export/enter` | a running root has no retained writes, so `protect!` is vacuous; the holder's `:safepoint` phase needs a `:running`-snapshot entry |
| backends | `layout.cljc` | register pairing is per pc (feasible as is); the stack kernel's `stack-layout` already models "values pushed and not yet consumed while control is inside a pc's expression" (`layout.cljc:11-12`), so a mid-expression slice is derivable |
| harness rows | `safepoint_harness_test.cljc` | one wrapped row per segment at a chosen pc, laws 1, 4, 5 |

Effort: canonical lane 3-4 days plus 2-3 review rounds; backends 2 more days. Prerequisite: the owner's ruling on whether `:running` roots are custody subjects (the gate semantics of UCF §7.7.4 assume a parked task). Risk of leaving it: none to correctness; a capability gap (no preemptive migration of a long pure loop; a program must reach a park or wait to move). Recommendation: leave it until a composition needs it.

### 1.3 Fork custody slot and the retained-FFI seam

**Fork custody slot nil (a, owner 2026-10-14).**
- Code: `rows.cljc:1763-1769` a nil header leaves the root's custody slot nil; `:1701-1713` `header-custody` writes the five-key slot only from a header; `custody-baseline` (`:1286`) refuses operation ids on a fork tree; `holder/driver.cljc:1026` `fork-step`; `holder/export.cljc:168-179` `check-header!`, `:387-399` `header-agrees?` (nil header ⇔ fork baseline).
- Tests: `ucf/rows_custody_test.cljc:35` (slot by kind), `:58` (what custody forbids), `:248` `a-fork-is-refused-under-exclusive`.
- Design: continuation §2.5-2.6 (amended after 7), UCF §7.2.1 :305-313 ("version 0 is fork only").
- What would trigger a change: a composition that wants a fork root later promoted to exclusive custody, or fork provenance (an origin chain on forks). Effort then: slot grammar, baseline, three tests, about one day. Risk of leaving: none; two resumers of a fork value diverge into distinct incarnations as UCF §7.10 :2835 states.

**Retained-FFI seam not built (a, owner 2026-10-13, 6c Q2 "open" but ruled not to build).**
- Code: both backends' `:request-sent` re-park build the response wait over the composition's own call pair: `debruijn/register.cljc:511`, `debruijn/stack.cljc:567` (`ffi/response-wait-entry entry (:call-id entry)`); the semantic kernel honours a carried pair (`semantic.cljc:105-109`). The harness keeps row 4 out of law 5 by `unlowerable #{[4 :stack] [4 :register]}` (`safepoint_harness_test.cljc:1711-1719`).
- Design: continuation §4.3 :917-948, §4.6 limit 5, §11 6c Q2.
- **Risk of leaving it, and the one real finding here.** Continuation §4.6 (:1097-1109) says plainly that rows 4, 7-9 and 17 "are excluded by the harness only, not by a general pre-attach fragment gate". `layout/lower-defect` refuses `:ambiguous-image`, `:installs`, `:free-env-value` and `:continuation-registers`, not a retained FFI pending. A real composition that lowers a row-4-shaped task into a backend receiver attaches it and it never wakes (codex probe 3 reproduced the hang on all four such pairs). UCF §7.11 :2923-2924 requires backends "to refuse the unsupported remainder before attach". The owner's "refuse-the-remainder requirement is not evidence of a general gate" (continuation :1109) records the discrepancy but leaves the hazard.
- What a future need would trigger: the four-line seam itself (half a day, raises law 5 to 366/96) if any composition migrates retained-FFI tasks into backends. Independent of that: a pre-attach refusal `:yin.k/unsupported :retained-ffi` in `lower-defect` (half a day, one refusal test per backend) is compliant with "not widened" and closes the silent-hang hazard. Recommended (§6 item 2).

### 1.4 Section 10 items 1-8, spot-checked in the tree

| § 10 item | what I checked | verdict |
|---|---|---|
| 1 Identity | `2daa17fb` exists; `linker.cljc:2123-2134` `relowered` recomputes H/R from the tree's projection, `:2203` verifies against it; the native format records use `image-hash`/`register-hash` only as `:identity-matches-fn` for an image against its own checksum (`linker.cljc:664-665, 682-684`), while the semantic record's identity is `jing/segment-key` (`:697`); final report rows 6/13 | **met** |
| 2 Freeze | the header of `yin.vm.semantic.md` :3-11 records the codex sign-off; amendments dated and listed :43-64 | met (doc) |
| 3 Linearizer, validator, analysis | `semantic/code.cljc:876-880` `load-vector` returns `{:vector :address :bodies [{:start :end :owner :registers}]}`, `:end` exclusive (`:331` last-pc is `(dec (:end …))`); rules 6-13 with first-defect order `:845`, kinds listed `:854-862`; refusal rows for every rule including 13 `code_test.cljc:47-85`, `every-item-has-a-refusal-row :112-121`, `item-13-compares-literal-operands-by-content :178`; `L` against an independent worklist reference `analysis_test.cljc:8, :93-99`; both AST lanes and the direct load to one image `load_paths_test.cljc:40, :52`; analysis computed once per admitted image `semantic.cljc:300` | **met**; three-host determinism is reported (log :11275), the tests are `.cljc` |
| 4 Evaluator | transitions of §2.4 as code: `semantic.cljc:149-268` (saved window on a non-tail call `:186-188`; tail call through `K` `:206-210`; abortive continuation `:213-218` with ownership via `engine/operator-kind` `engine.cljc:77-90`; `:ffi-call :234-254`; `:park :259-261`; `:resume` accepts a halted result `:265`); restore helper with both delivery modes `:101-126` and the deferred-tail halt environment from `base` `:123-124`; parity `parity_test.cljc:80-228` (B0 values, corpus outcomes, Rule R, continuations, FFI, streams), trace alignment `walker_trace_test.cljc:113-152`, `vm_test.cljc:116-209` (tail grows no frame, `rd` written once, tail primitive through `K`, define through the module store) | **met** |
| 5 Derivations | `derive_test.cljc:348, :565` lexical-address law (stack, register), `:238-294` and `:421-574` goldens, validation, determinism, `:324, :543` execution oracles, `:619` `^:slow c4-execution-oracle`; scanner agreement and pinned goldens `scan_test.cljc:231-264` (`^:slow`) | **met** (old-scanner answers are now historical pins, as §10 says) |
| 6 Continuation format | `frame.cljc:80-181` implements every rule §4.2 lists (representation `:105-108`, body scope `:58`, membership `:59-61`, site `:135-137`, destination `:138`, delivery mode `:139/:143` with `tail` carrying no window or env `:89, :97`, return-frame sites `:148-151`, pending/reason `:162-173`, free-env `:155-161`); row shapes `rows.cljc:58-61` are §4.2's (`:act [A pc env window k rd]`, `:tail [A site k]`, keyed `:window`, `:kframe`), root `free-env` slot `:67`; harness 89 cases `safepoint_harness_test.cljc:656-669`, 61/350 on the native lanes `:1691-1703, :1778-1789` | **met at the accepted matrix** (a: 61/350) |
| 7 Stage D/E | `ucf/stage_e_rows_test.cljc:36-105`, `stage_e_process_test.clj:257-309` (JVM-only process matrix), `crash_cut_test`, `durability_test`, `fencing_rows_test`, `compose_rows_test`; jupiter four-pair matrix with 196 kills reported (log :11248) | **met**; UCF §7.11 clause 10 :3363-3365 still reads "does not close Node/Dart or killed four-pair JVM/Node acceptance ... pending the orchestrator's host matrix", stale against the log (d) |
| 8 Cutover | my final report §1 rows 1-18 and closing report; re-checked today: `semantic.cljc` is the register kernel, `yin.vm.ucf` is only `code-address` (`scan.cljc:7` requires it for that), REPL table as in §1.1 | **met** |

### 1.5 datom.world.md invariants: no violation found, two notes

| invariant | evidence | risk |
|---|---|---|
| Peer observers, one stream (yin.vm ignorant of dao.space) | `semantic.cljc:3-13` and `engine.cljc:30-40` require no `dao.space.*`; the kernel resolves names through `engine/resolve-var` (env, store, primitives, modules) only | none. **Note (d):** `semantic/scan.cljc:3, :90-91` and `vm.cljc:29, :1649, :1738` use `dao.space.query` as a Datalog library over a relation built from the vector or AST rows in hand, never over a published index; this is linker-side admission ("derive, don't persist"), not evaluation. One docstring sentence in `scan.cljc` would stop a reader mistaking it for the kernel consulting an index |
| Macros are stream topology | `linearize.cljc:27-30` refuses `:yin/macro-expand` at projection; the kernel's `case` table (`semantic.cljc:148-268`) has no macro arm; the REPL expands upstream and "No evaluator learns that an expander ran" (`repl.cljc:204-211`) | none |
| dao.jing is syntax | the kernel calls only `jing/segment-key`/`segment-matches?` (`semantic.cljc:431-432`); the carrier emits content rows under Jing's default digest; nothing in `dao.jing` learned a row shape | none |
| A is the only code identity | requests and records name A; H/R are recomputed from the tree (`linker.cljc:2123-2134`); backends key their local offset tables by H/R (`repl.cljc:174, :191`; `layout.cljc:12-14` "checksums: A is the only code identity") | none. **Note (d):** the words "identified by H" / "image identities" survive in `linker.cljc:657, :675` and `layout.cljc:13`; they mean checksum verification, and should say so |
| Unitarity, tree always available | `rows.cljc:1682-1688` refuses a carried segment with no `:yin.k/trees` entry; derivations §9.2's amendments landed in `yin.vm.linker.md:30-33, :234-255, :1905` | none |
| The six invariants (§12) | windows are immutable maps; `entry-builders` (`semantic.cljc:71-78`) are in-process constructors handed to the engine, not callbacks across a stream; no global state | none |

### 1.6 What the old kernel did that the new one cannot, and uncovered host risk

Behaviour lost, all by ruling and all recorded:
- Versions 0-2 bodies: refused `:yin.k/profile-mismatch` (`rows_test.cljc:1045`); no-legacy rule.
- The walker's `:vm/store-update` arm: refused at projection (`linearize.cljc:30`); frozen §1 item 2 puts it outside the shared vocabulary.
- `yin.vm.completion`'s footprint report (`yin.vm.linker.dht.md:3400` still names it as an acceptance line) and the old inspector: died un-ported (8f-1 R2/R3), ledger row 27.
- The datom-batch loader `vm-load-program`: replaced by `load-ast` over datoms (`semantic.cljc:322-326`); not a loss.

Ledger status today (`docs/agents/known-gaps.md`): see §4.1.

Regression risk on Node/Dart without a test:
1. **Dart slow tier (X4).** The phase-8 record (log :11275) lists JVM quick/slow, Node fast, Node slow C4, Dart fast only. Dart runs slow bodies only under `DATOM_SLOW_TESTS=1` (`build-n-test.md:17-26`), so on Dart the 89-case harness, the 61/350 cross-engine laws, `c4-execution-oracle`, the scan goldens and the Stage E kill rows have never been exercised at the phase-8 head. The last Dart slow record (log :11229, pre-phase 8) had "4 unattributed failures". Action: one `bb test:slow:cljd` run on a server, attribute, no code until read.
2. **JVM-only files** `stage_e_process_test.clj`, `linker/cross_host_transfer_test.clj`, `production_pipeline_test.clj`, `store_write_audit_test.clj`, `layering_test.clj`, `macro_build_test.clj`: process drivers and audits; the Node/Dart peers they drive are exercised through them. Acceptable.
3. **Host-guarded bodies in the semantic tests** (`frame_test.cljc:169` ns-purity audit, `derive_test.cljc:195` reflection audit, `load_paths_test.cljc:28` JS refusal branch, `frame_test.cljc:241` signed-zero on JS): audits or host-correct branches, not behaviour holes.
4. **Numeric edge cases**: `decode*` host-`distinct` on map/set keys (ledger P2: 0.0/-0.0 asymmetry everywhere, 1/1.0 refusal Dart-only) is the one known host divergence left in the carrier.

### 1.7 Order and verdict: see §0 and §6.

## 2. Gap rows (each against a design clause)

| id | design clause | current state in code | class | effort / risk of leaving |
|---|---|---|---|---|
| G1 | UCF §7.4.3 "the pending wait travels as data, complete"; `yin.vm.linker.md` try-require return contract | `engine.cljc:1159-1171` branches on `(:try? entry)` for link waits; `rows.cljc:39-46` `pending-table` and `:1027-1034` `pending-keys` carry no try flag for `:link-request`/`:link-response`/`:install`; after a lift/lower the waiter resumes as plain `require` (raises on refusal instead of returning `{:status …}`) | (c) P1, ledger row 36, L-d-1b | 1 day: one slot on the three link pendings, restore, two tests; packs with link pendings re-mint (`rows-checkpoint-v3.txt` carries installs, so expect one fixture move, to be recorded in `ucf-revisions.md` I-6). Leaving it: a migrated `try-require` fails loudly where it should report |
| G2 | UCF §7.11 :2923-2924 backends "refuse the unsupported remainder before attach"; continuation §4.6 | `layout/lower-defect` gates four of the five limits; retained FFI (row 4) is excluded by the harness `unlowerable` only (`safepoint_harness_test.cljc:1711`); a backend receiver attaches and never wakes | (b); the seam itself is (a) rejected | half a day for a `:retained-ffi` refusal; leaving it: silent hang in any real backend composition |
| G3 | continuation §11 Q1/Q10: install `:running` wire phase | `rows.cljc:1637, :1843` admit `#{:running :parked}`; `engine.cljc:1491, :1506` assign `:running` only inside one sweep; row 11 is hand-built (`safepoint_harness_test.cljc` row 11) | (b) | narrow to `:parked`: half a day, removes a synthetic row; leaving it: a wire state no engine reaches is accepted |
| G4 | continuation §11 Q2 (exact census), Q5 (re-discharge registry names on lower), Q6 (edge-only liveness), Q7 (host-class primitive behind a shadow), Q8 (`attaches?` double validation), Q9 (law 2 outside a fresh receiver) | as the design states them; unchanged since 6b (`check-dependencies!` `rows.cljc:1897`, `attaches?` `:1821`) | (b), owner answers pending | each half a day to a day; leaving them: Q5 is the only one with a runtime symptom (a receiver lacking a registry entry accepts the carrier and fails at `Unable to resolve symbol`) |
| G5 | frozen §10 phase 6 / UCF §7.11 safepoint harness "for every engine" | native lanes 61/350 of 89/534 | (a) owner 2026-10-13 | leaving it: rows 7-12, 17 never lower into a backend (link/install), rows 4 as G2 |
| G6 | UCF §7.11 "Authority grounding and wiring": "the first composition to ship UCF is `yin.repl.core`'s handoff demo" | REPL carries a custody composition as data only (§1.1) | (b) | 2-3 days; product gap |
| G7 | UCF §7.4.1 safepoint-only lift vs the owner's open question on `:running` roots | refused at `rows.cljc:1653` (§1.2) | (b) owner-deferred | 3-4 days + 2 for backends; capability gap only |
| G8 | continuation §2.5-2.6 fork custody | nil slot, refused under exclusive (§1.3) | (a) | none |
| G9 | frozen §10 item 7 / UCF §7.11 clause 10 Node/Dart acceptance | JVM and Node evidence on record; Dart slow absent (§1.6 item 1) | evidence gap | one lane run |
| G10 | derivations §1379 `:tree-unavailable` composition defect | landed as text in `yin.vm.linker.md:31, :242`; no `:tree-unavailable` symbol in `src` (grep) — the lift refuses `:yin.k/unsatisfied {:yin.k/missing {:yin.k/trees …}}` instead (`rows.cljc:1684-1685`) | (d) | align the linker doc's word with the code's outcome, or add the linker-side kind when a native image is served with no known tree; today publication always records the tree (`publish.cljc`) |
| G11 | ucf-revisions §6 :481-488 "contract-pinned AST and semantic identities ... named, not landed" | not landed | (b) low | out of this kernel's scope; would amend UCF §7.3.2 address semantics |
| G12 | known-gaps rows 21 (binder refusal defence), 25 (`decode*` distinct), 22 (8d successor tests), 26 (`malformed-vectors` dead fixture), 28-29 (historical citations, prose) | as the ledger says; verified `malformed_rows.cljc` still present, `scan_test.cljc:214` does not read it | (c) P2/P3 | one test slice, one doc slice (§6) |
| G13 | datom.world.md "Peer observers" wording vs `scan.cljc`/`vm.cljc` Datalog use; "identified by H" wording | §1.5 notes | (d) | two docstring sentences |

## 3. Open design questions needing an owner answer before code

In priority order: G2 (refuse vs. seam), G3/G4 Q1-Q10 of continuation §11 (recommend: narrow the install phase to `:parked`; keep edge-only liveness; keep the narrower census check; require re-discharge of registry names on lower, since that one has a runtime symptom), G7 (6d), G6 (REPL export). None blocks the kernel.

## 4. Ledger and evidence

### 4.1 Ledger rows owned by this seat, status today
| row | severity | status |
|---|---|---|
| `:try?` not carried (36) | P1 | open; G1; UCF seat, L-d-1b |
| `decode*` host-`distinct` keys (25) | P2 | open |
| binder refusal collapsed to the projection (21) | P2 | accepted as design for the merge; follow-up slice unscheduled |
| 8d partial mappings need two successor tests (22) | P3 | open |
| `malformed-vectors` dead fixture (26) | P3 | open |
| historical v3 section citations (28), prose residues (29) | P3 | open |
| `frame.cljc` NaN (24) | P3 | closed on master (bindings= in `frame.cljc:64-77`) |
| scan golden (18), `rows.cljc` lint (19), register doc prose (20), phase-8 deaths (27) | — | closed/recorded |

### 4.2 Lane evidence
| lane | status |
|---|---|
| JVM focused run here (`vm`, `code`, `analysis`, `frame`, `parity`, `walker-trace`, `load-paths`, `safepoint-harness`, `layout`, `derive`, `effects`, `link`, `rows`, `rows-custody`, `semantic-engine`, `semantic-ffi`) | **verified**: 254 tests, 15302 assertions, 0 failures, 0 errors; counterexample searches 0 failures |
| JVM quick tier 4005-4007 / slow 22 namespaces, Node fast, Node slow C4, Dart fast | reported (log :11275; my closing report) |
| Dart slow | **no record at the phase-8 head** (G9) |

## 5. Doc-only residues found today (d)
UCF §7.11 clause 10 :3363-3365 (Stage E host matrix now run); `yin.vm.linker.dht.md:3400` (`yin.vm.completion` acceptance line); `linker.cljc:657, :675` and `layout.cljc:13` "identity" wording; `scan.cljc` docstring on its Datalog use; `vm/semantic-bytecode-grammar` (`vm.cljc:998`) is the AST row-slot table, not bytecode; the ledger's P3 citation rows.

## 6. Recommended order of follow-up slices

| # | slice | effort | gate |
|---|---|---|---|
| 1 | G1 `:try?` on link pendings (L-d-1b) | 1 day codex, 2 rounds | rows/harness/link tests; fixture re-mint recorded |
| 2 | G2 backend pre-attach refusal `:retained-ffi` | 0.5 day, 1-2 rounds | one refusal test per backend; fragment unchanged |
| 3 | G9 Dart slow-tier run and attribution | lane time only | read before any code |
| 4 | G12 P2 pair: `decode*` key equality + binder-refusal defence | 1 day, 2 rounds | rows_test, debruijn kernel tests |
| 5 | G12 P3 test slice: 8d successor tests, delete `malformed-vectors`, `parity_test` `:refer` tidy | 0.5 day | quick tier |
| 6 | §5 doc pass + G10/G13 wording + UCF clause 10 record | 0.5 day, 1 round | Architect read |
| 7 | owner rulings on G3/G4 (then 0.5-1 day each), G6, G7 (6d) | as §1.2 | after rulings only |

**Verdict: the design is complete except X1-X4 of §0.** `yin.vm.semantic` as the frozen design specifies it is finished and verified in the tree; the remaining work is one P1 on the carrier's link pendings, one half-day refusal that closes a silent-hang hazard in the backends, one missing Dart slow-tier run, and owner-gated capability questions that the design deliberately left outside the kernel.

GAP-LIST-READY
