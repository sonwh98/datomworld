# Slice 6b task-transport review

Reviewer: GPT-6.1-sol, non-author. Reviewed branch `srvm-phase6b`, HEAD
`c3d37586`, `git diff master...HEAD`, the engineer report, and continuation
design §§3–8. No implementation or test changes remain. Temporary mutants
were restored with `cp`, checked against the backup with `diff`, and checked
with `git diff --quiet -- src/cljc/yin/vm/ucf/rows.cljc` after every run.

The reference transport works on the supplied corpus, but its pre-attachment
validation is incomplete and its dependency discharge can claim an unavailable
binding. These are merge blockers despite the green suites.

## Findings

| ID | Severity | File:line | Finding and required change |
|---|---|---|---|
| F1 | High | `src/cljc/yin/vm/ucf/rows.cljc:1535`, `:1608`, `:1681`, `:1726` | Carrier-derived store and closure refusals occur **after attachment**. A re-emitted row-1 carrier with store entry `yin/def → [:lit 42]` attaches once, then throws `{:rule :reserved-name :role :store-key}` from `engine/store-put`. Changing a carried closure's entry to `999999999` likewise attaches once, then throws `:origin-not-attached` from `module/lower-closure`. `check-task!` checks closure segment presence only. Preflight reserved keys in every root/module store and closure entry/binder agreement with A for the entire task tree before attachment; normalize carrier defects to the transport's named refusals. Add zero-attach tests for both cases. |
| F2 | High | `src/cljc/yin/vm/ucf/rows.cljc:1518`, `:1565`, `:1693` | Install records are not adequately checked before restoration. Replacing row 10's retained response with `{}` passes `decode`/`check-task!`, attaches once, then `spawn-child` rejects its nil image with `{:defect {:rule :nonempty :pc 0}}`. Setting its phase to `:bogus` is accepted and installed into the restored machine. Validate phase and the retained response/image contract, including the image needed by spawning, before attachment. Check their relationship to the carried child; restoration must not be the first interpreter to discover malformed install data. |
| F3 | High | `src/cljc/yin/vm/ucf/rows.cljc:1379`, `:1386` | Dependency discharge treats keys of **all** source module stores as globally available bindings. A halted `(fn [] ghost)` correctly refuses with missing obligation `ghost`. Adding `:module-stores {'unrelated {'ghost 42}}` makes the same lift and lower succeed, although the carrier has **zero** module-store rows and the closure has no store-of. Invoking the restored closure fails `Unable to resolve symbol: ghost in this context`. `engine/active-store` selects one store; `resolve-var` does not search their union. Discharge each free occurrence in its applicable environment/store context and require the answering state to travel. Add this negative case and a positive scoped module-store case. |
| F4 | Medium | `src/cljc/yin/vm/ucf/rows.cljc:1517` | Custody is checked before pending/operations and dependencies, contrary to §§3.3/7.2 steps 8, 9, 10. A carrier with both absent tree associations and non-nil custody reports `:yin.k/unsupported :custody`, masking the earlier dependency defect. Move custody after those checks, with stage ordering across the task tree, and add a combined-defect test. |
| F5 | Medium | `test/yin/vm/semantic_register/safepoint_harness_test.cljc:434`, `:462`, `:938` | The harness does not detect redundant attachment or skipped dependency discovery. The duplicate-attach and closure-before-walk mutants both pass all 357 quick assertions and all 609 matrix assertions. Assert successful attachment count equals the number of distinct stream identities, including shared identities across waiters/children. Add a carried closure whose only free obligation is missing, plus reachable/transitive dependency cases, so moving closure discovery ahead of the walk fails. |

### Reproduction method

The probes used the harness's public fixture functions without editing tests:
`[toy wire] = (h/lift-bare n)`, `task = (h/root-task wire)`, edit the abstract
task, then `(h/lowered toy (rows/emit edited-task))`. Thus row hashes and
numbering were recomputed, rather than bypassing a hash check.

Edits for F1/F2/F4:

```clojure
;; F1, row 1: append to the root store's pairs
(update-in task [:slots 6 :slots] into ['yin/def [:lit 42]])
;; F1, row 1: select a :closure node and change its entry slot
(assoc-in closure-node [:slots 1] 999999999)
;; F2, row 10
(assoc-in task [:slots 12 0 :slots 3] {})
(assoc-in task [:slots 12 0 :slots 1] :bogus)
;; F4, row 1
(-> task
    (assoc-in [:slots 10 :yin.k/trees] {})
    (assoc-in [:slots 16] {:custody :exclusive}))
```

F3 used a real evaluator-produced closure from
`(vm/run (h/load-program (h/machine) (s/lam [] (s/v 'ghost))))`, with the
unrelated module-store map added before lift. After lower, storing the returned
closure as `f` and running `(f)` reproduced the missing-name error. Probe code
and compact outcomes are in `/private/tmp/review-6b-probes.clj` and
`/private/tmp/review-6b-probes.log` for this session.

## Implementation assessment against §7

Lift checks ready queue, holds and control before its walk; reads retained
`:free-env` with explicit override; authenticates resource references; builds
all pending variants; collects addresses from waits, K, parked states, closure
values, nested values and module stores; and checks absent **or empty** source
tree associations. Module-store discovery reaches stores referenced by values
in previously discovered stores. Dependency discovery happens after this walk,
which is necessary, but its answering predicate has F3's scope error.

Lower's `decode` preserves gate → structure/graph → hash → canonical form →
numbering → frames. It then visits the task tree before calling the attacher,
but that visit omits the checks identified in F1/F2 and checks custody too
early (F4). It therefore does **not** satisfy “all refusals before any attach.”
The correct deduplication branch at lines 1548–1549 attaches once per stream
identity across the whole tree. That property is implemented but inadequately
tested (F5).

Code restoration attaches carried v4 vectors and maps frame/closure addresses
through the newly built aliases; source-local segment numbers do not travel.
Closures, reified continuations, stream refs and cursor refs are rebuilt for
the receiver. Tail entries have through-K delivery and no env/window/pc.
Install children are spawned and their carried state restored without
`vm/run` or a delivery; the spawn temporarily loads an image, and restore
replaces the initial control. Free env is installed as both `:env` and
`:free-env`. These successful-path properties are sound on the checked corpus.

## Engineer deviations 1–10

| # | Decision | Design section and wording hint |
|---|---|---|
| 1 | Accept. The source index belongs to the composition; the kernel has no such field. | §§6.1/7.1: explicitly document `:source-index {A #{tree-address}}` as a lift input and absence/empty-set refusal. No new persisted source-index slot is needed. |
| 2 | Accept. Consistency with the landed row codec is appropriate. | §§7.1/7.2: “Refusals throw `ex-info`; ex-data carries `:yin.k/status`, a kind when applicable, and defect data.” |
| 3 | Accept fork-only scope; object to custody's check position (F4). | §7.2/§8.4: “Phase 6 emits nil custody and refuses non-nil custody at step 10; exclusive/holder support is phase 7.” Preserve the step order. |
| 4 | Accept as a composition prerequisite, not a proof that arbitrary receivers can resolve linked names. No registry slot exists. | §§2.6/7.2: say linked registry entries do not travel, and the receiver supplies required registry bindings. Row 17 should expressly name that prerequisite. |
| 5 | Accept lift-only name discharge as the §7.1 scope, with lower checking carried dependencies and receiver primitives. Object to the discharge implementation (F3). | §7.1: require occurrence/context-sensitive discharge against state that is carried or explicitly supplied by composition. §7.2: state which receiver obligations are checked before attach and which registry prerequisites belong to composition; do not describe the current union of module-store keys as resolve-var order. |
| 6 | Accept whole root store and all parked records as the conservative slice. | §§2.6/7.1: “The phase-6b store slice is the complete root store; every parked record travels.” Keep module-store reachability separate. |
| 7 | Accept keeping the receiver's supplied registry. This companion design does not require isolated-receiver pruning. | §7.2: explicitly retain the supplied module registry, with the prerequisite from deviation 4. |
| 8 | Accept. Calling `validate-frames` before emit meets §3.3; a second decode is unnecessary. | §7.1: describe validation of the assembled abstract task, followed by emit; no design change to the invariant. |
| 9 | Accept naming previously unspecified kinds, provided defects get named transport outcomes and preserve stage order. Current late raw errors are not acceptable (F1/F2). | §7.2: add a refusal table for role, install completeness/shape/phase, task kind, parked ID, requires, segment, store keys and closure origin/binders. Custody remains step 10. |
| 10 | Accept the fixture mechanics, with explicit synthetic labeling for row 18's symbolic module-store key. Rows 13/20 follow the stated beside-resume and two-batch semantics. | §5.2 row 18: distinguish this hand-built store-key fixture from actual address-keyed installed stores; row 17 already exercises a real installation. No change needed for rows 13/20. |

## Design bugs B1–B4

| Bug | Decision | Exact amendment target and wording hint |
|---|---|---|
| B1 | Accept the engine-state diagnosis. `advance-install` lines 1444–1454 assigns blocked children `:parked`, and routes halted/explicitly parked children through linking. The harness's row 11 is a manually changed phase, not the advertised executable park-child scenario. | §5.2 rows 10–12 and §8.2 acceptance 3: row 10 is child kind `:blocked`, phase `:parked`; row 12 uses that child with tail delivery. Rewrite row 11 as an explicitly synthetic phase-`:running` transport case, or obtain an owner-defined new reachable scenario. Do not continue to promise an executable kind-`:parked` install child on the current engine. |
| B2 | Accept. The wrapper's `x` shadows the free `x` before `f` is created. | §5.2 wrapper-results paragraph and row 21: state the exception: depth-3 returns `[3 [2 [1 1]]]`; bare, operand and captured retain the corresponding 99 result. Alternatively change wrapper binders in the design and harness together; the current expected exception is correct. |
| B3 | Accept wire refusal `:row-shape`; object to the old `:frame-shape` wording. | §5.2 refusal rows: “a fourth tail slot is `:row-shape` at structure step 2.” S1's abstract frame-shape rule remains valid. Separately note the pre-existing abstract emitter's extra-slot truncation; this review did not change 6a code. |
| B4 | Accept. Segment discovery depends on walking the task, including stored closure values and module stores. | §7.1: order as “preflight quiescence/holds/safepoint; classify kind; walk the task and discover segments/stores/cursor cells; discharge dependencies and check tree coverage; validate frames; canonical-number and emit.” Preserve the lower's distinct numbered-stage order. |

## Harness and portability

The matrix performs 89 actual labelled runs and asserts both case count and
completed-run count. Each builder receives the selected wrapper and is called
separately for reference and transport. Four wrappers construct different
programs/K shapes; the counter is not incremented without executing `laws`.
However, “89 distinct scenarios” would overstate it: rows **3 and 24 use the
same `sent-ffi` function**, so there are 85 distinct builder/wrapper setups.
This duplication is intentional in §5.2 (row 24 is row 3's parked bookkeeping
facet); the separate `act-ffi-bookkeeping` test checks its identity and shape.
Describe the count as 89 labelled row/segment executions, not 89 unique states.

`laws` asserts law 1 on completion, normalized result and effect trace; law 2
on canonical root equality; law 3 on the observed first wait's reason for the
blocked corpus; law 6 through recursive task/frame validation. The fixture's
blocked worlds have the intended single parent wait; the multiwaiter test is
separate. Law 6 also traverses parked/captured states and install children.

Law 2 is not vacuous: source correspondence comes from served-table identities,
and relift correspondence comes from the attacher's recorded reflection-handle
→ source-identity relation. It is not inferred from the two carriers being
compared or their traversal position. `canonical-under` rewrites stream
identity/descriptor and re-emits, retaining code, values, windows, pending data
and cursor alias topology. Its coverage check rejects absent correspondence
keys. The fixture could assert non-nil recorded origins more explicitly, but
its known attached reflections have recorded origins. Law 2's inability to
observe redundant attacher invocations explains M6, not a tautological root
oracle.

Portability read: no new `for` comprehension over long sequences, private-var
access, unary-minus call, or multi-key `assoc` with a nil initial target was
found. Restore targets are maps/records; optional maps are handled through
`cond->` or `merge`. Catches use the correct reader-conditional throwable type.
Dart `fn?` accepts Dart `Function` or the `Fn` marker (pinned local
`cljd/core.cljd:468`); `dao.jing.cbor/numeric?` handles the portable numeric
carriers, including its Dart branches. Their new uses are consistent with
those implementations, not merely justified by precedent in handoff.
Node/Dart execution remains unverified, as requested by the review brief.

The alleged `handoff.cljc` latent symbol bug is **not a bug**. Its actual
lines 1483–1484 use `(symbol (str (name m) "/" (name x)))`. JVM evaluation
produced `host.mod/f`, namespace `"host.mod"`, equality with `'host.mod/f`
true. The pinned Dart `symbol` implementation (`cljd/core.cljd:1970`) also
splits the one-string argument at `/`; ClojureScript's symbol has the same
qualified-symbol contract. The new two-argument form is fine, but the
engineer's claim that the old form has a nil namespace should be withdrawn.
No handoff edit was made.

The store-write-audit allowlist additions are structurally justified: the
root store is rebuilt through `engine/store-put` and assigned to the fresh
receiver once, with no new program-write bypass. The comment correctly
identifies reserved-key refusal, but this does not prove its timing. F1 shows
that the refusal currently happens too late. Module-store restoration also
needs the preflight key validation described in F1.

## Independent mutation results

All nine mutants were run against the quick harness. The two survivors were
also run against the slow matrix. Mutations were sequential, foreground, and
restored with `cp` even when a test failed. No audit allowlist was changed to
accommodate mutants.

| Mutant | Concrete change | Result |
|---|---|---|
| M1 lower attaches before the last refusal | Obtain handles before `check-task!`, preserving decode first. | Killed: missing-tree refusal observes one attach (`refusals:807`). |
| M2 lift serves before safepoint refusal | Serve the first available resource inside the control-refusal branch. | Killed: safepoint refusal observes one serve (`refusals:860`). |
| M3 free-env override ignored | Use machine free-env in the explicit-override branch. | Killed: `free-env-shadowing` errors with inconsistent state; quick total 354 assertions, 1 error. |
| M4 tail retains a window | Add `:window {}` to restored tail payload. | Killed: three failures in `tail-states-lower-to-through-k-delivery`. |
| M5 pending ready queue need not be empty | Disable the initial ready-queue refusal. | Killed: `a-nonempty-ready-queue-is-not-quiescent`, 1 failure. |
| M6 duplicate attach for one identity | Invoke `remote/lower-marker` again in the already-present-identity branch. | **Survives quick and slow**: 357 + 609 assertions, no failures/errors. |
| M7 install child not restored | Keep the newly spawned initial child instead of calling recursive restore. | Killed: 9 failures/2 errors, including non-nil child control, missing wait and blocked status. |
| M8 values not re-sealed | Return a raw reified-continuation payload instead of `values/continuation`. | Killed: 4 failures/2 errors, including capture/round-trip failures. This mutant targets the continuation branch; it does not establish separate owner-change coverage for every value kind. |
| M9 closure computed before walk | Run discharge against the accumulator's still-empty segment set before the walk; remove the later discharge. | **Survives quick and slow**: 357 + 609 assertions, no failures/errors. |

Mutation logs are `/private/tmp/review-6b-M*.log` and
`/private/tmp/review-6b-slow-M*.log`. Backup:
`/private/tmp/review-6b-rows.backup`. Final restored source matches both the
backup and HEAD; the saved pre-mutation working diff was empty.

## Verification

- `clojure -M:test -e :slow -n yin.vm.semantic-register.safepoint-harness-test -n yin.vm.ucf.rows-test -n yin.vm.store-write-audit-test`: **45 tests, 939 assertions, 0 failures, 0 errors**.
- Same command with `-i :slow`: **2 tests, 617 assertions, 0 failures, 0 errors**. The matrix alone contributes 609 assertions; the other slow test is the codec counterexample search.
- `clj -M:kondo --lint` on the three changed files: **0 errors, 0 warnings**.
- Neither Node nor Dart was run; §8.2 acceptance 9's three-host gate is not established by this review.
- No commits, merges or implementation fixes were made. Only this findings file is a permanent review addition.

VERDICT: CHANGES_REQUESTED
