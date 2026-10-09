> **Status (2026-10-09):** proposed. Architect design and migration plan, no
> implementation yet. Ruling and evidence in
> `collab/1791497000000-architect-subsystem-test-spec.claude-fable-5-1.findings.md`.

# Subsystem-First Tri-Host Test Architecture

## 1. Problem

Every verification today is a whole-repository run. `bb test` runs about 260
test namespaces on each of three hosts (JVM, Node, Dart), builds two
cross-host peers and the ANTLR parser first, and takes about 14 minutes when
nothing else is running. Three feature tracks now iterate in parallel
worktrees (Track A `yang.python`, Track B `dao.stream`, Track C
`yin.vm.ucf`), so a whole-repo run on each worktree contends for the same
cores and each engineer waits on tests they did not touch.

`bb test:changed` (`src/dev/affected.clj`) already narrows a run to the
reverse require closure of a diff. That is the right gate before landing,
but it is the wrong tool for iteration on a foundation subsystem: a change
to `dao.stream` reaches nearly every test in the repository, so for Track B
the closure is the whole suite again.

This document defines the subsystem taxonomy, the host matrix for a
subsystem run, the gates at which a subsystem run is sufficient and the
gates at which the whole-repo tri-host run stays mandatory, and the
migration from today's tasks. It changes no invariant of
`docs/design/datom.world.md`. The one rule it adds is in section 2.

## 2. Principles

**A subsystem is a selection, not an isolation.** The repository stays one
`deps.edn`, one shadow build, one cljd build. A subsystem run selects test
namespaces; the code those namespaces load is whatever the require graph
says. Nothing in this design partitions source paths, and nothing stops a
`dao.stream` test from loading `yang.python` fixtures if it does so today.

**Hosts are not a scope axis.** Subsystem scoping cuts the breadth of a run
(which namespaces). It never cuts hosts at a gate. Cross-host bugs show only
on Node or Dart (`docs/agents/build-n-test.md`), so every gate from a slice
checkpoint upward is tri-host. A one-lane subsystem run exists for the
inner iteration loop only.

**Derive, don't persist.** The taxonomy is one table of namespace prefixes.
Everything else about a subsystem (its test namespaces per lane, which
build prerequisites it needs, which lanes must serialize, what it reaches
across the boundary) is derived from the require graph at run time by the
code `affected.clj` already has. No subsystem declares its prerequisites.

**One runner.** `affected.clj` already turns a set of test namespaces into
`{lane [ns]}`, derives prerequisites, serializes the JVM and Dart lanes when
a JVM test runs the cljd compiler, runs the lanes in parallel and prints a
table. The subsystem selector is a second way to produce that set. The
runner is not duplicated.

## 3. Taxonomy

### 3.1 Canonical subsystems

A namespace belongs to the subsystem whose prefix is the longest match in
this table. `yin.vm.ucf` therefore wins over `yin.vm`, and `yang.antlr`
belongs to `yang.python` by an explicit extra prefix. Counts are from the
tree on 2026-10-09; the per-lane columns are the test namespaces
`affected/lane-tests` selects for that lane.

| subsystem | ns prefixes | src ns | test ns | clj | cljs | cljd | layer |
|---|---|---|---|---|---|---|---|
| `dao.stream` | `dao.stream` | 39 | 38 | 32 | 32 | 30 | 0 |
| `dao.jing` | `dao.jing` | 17 | 15 | 15 | 13 | 13 | 0 |
| `dao.data` | `dao.data` | 5 | 6 | 6 | 6 | 6 | 0 |
| `dao.base` | `dao.await` `dao.lease` `dao.pretty` `dao.datom` `dao.test-slow` | 5 | 4 | 4 | 4 | 4 | 0 |
| `dao.space` | `dao.space` | 8 | 11 | 11 | 10 | 10 | 1 |
| `dao.gui` | `dao.gui` | 17 | 15 | 14 | 13 | 12 | 1 |
| `dao.postgraphics` | `dao.postgraphics` | 13 | 11 | 5 | 8 | 8 | 1 |
| `yin.vm` | `yin.vm` (minus `yin.vm.ucf`) | 44 | 63 | 63 | 60 | 60 | 2 |
| `yin.vm.ucf` | `yin.vm.ucf` | 24 | 40 | 40 | 40 | 40 | 2 |
| `yin.repl` | `yin.repl` | 21 | 21 | 20 | 17 | 16 | 3 |
| `yang.python` | `yang.python` `yang.antlr` | 15 | 21 | 21 | 12 | 12 | 3 |
| `yang.base` | `yang.clojure` `yang.php` `yang.frontend` `yang.safepoint` `yang.stage` `yang.tails` `yang.io` `yang.docs` | 8 | 5 | 5 | 3 | 3 | 3 |
| `demo` | `datomworld` `yin.demo` | 24 | 10 | 9 | 10 | 9 | 4 |
| `world` | `world` | 3 | 0 | 0 | 0 | 0 | 4 |
| `dev` | `src/dev/` (bb-only) | 9 | 1 | bb | – | – | – |
| `bench` | `bench` | 1 | 0 | – | – | – | – |

The feature tracks map to `yang.python` (A), `dao.stream` (B) and
`yin.vm.ucf` (C). A track owns a subsystem; it does not own the subsystem's
dependents.

### 3.2 Membership rules

1. **Longest prefix wins.** The table is the only source of truth. A
   namespace that matches no prefix is an orphan and the orphan check
   (section 9, Phase 0) fails the `dev` lane.
2. **Test helpers follow their home.** The 29 non-test namespaces under
   `test/` (fixtures, peers, `dao.test-slow`, `yin.vm.test-utils`) belong to
   the subsystem their prefix names. They are never selected as tests; they
   are loaded by requires as today.
3. **Residual buckets are explicit.** `dao.base` and `yang.base` collect the
   single-file namespaces of their trees. They are named so that no run can
   silently drop them. Splitting them later is a table edit.
4. **Host files do not split a subsystem.** `dao.stream.cbor-test` exists as
   `.cljc` and `.cljd`; it is one namespace in one subsystem, and lane
   membership comes from the file extension exactly as `affected/lane-file?`
   decides it today.
5. **A subsystem is not a directory.** Membership is by namespace, so
   `src/clj/yang/antlr` and `src/cljc/yang/python` both land in
   `yang.python`, and `test/bench` stays out of every suite.

### 3.3 Layers and cross-subsystem dependencies

The layer column is derived from the source require graph (test namespaces
excluded): a subsystem sits one layer above the highest layer it requires.
The graph on 2026-10-09 gives four clean layers plus three leaks:

| edge | count | class |
|---|---|---|
| `dao.jing` → `dao.stream` | 18 | within layer 0 (one cluster) |
| `dao.stream` → `dao.jing` | 3 | within layer 0 (one cluster) |
| `dao.stream.journal.file` → `dao.space.store.fs` | 1 | upward leak, layer 0 → 1 |
| `yin.vm.semantic`, `yin.vm.ffi.remote-serve` → `yin.vm.ucf` | 2 | within layer 2 (one cluster) |
| `yang.python.antlr.linked-harness` → `yin.repl.link` | 1 | lateral, layer 3 |

Every other source edge points downward. `dao.stream` and `dao.jing` are
one cluster in the sense of `docs/agents/malleability.md` (structural
clustering), and so are `yin.vm` and `yin.vm.ucf`. They stay separate
subsystems because they are separate ownership tracks, not because the
graph separates them.

Cross-subsystem test dependencies fall into four classes. The class
decides how a test is treated by selection; it does not move any file.

**Class A, intra.** A test requires only its own subsystem and lower
layers. The large majority. Selected by its home subsystem, run by the
home's suite.

**Class B, seam.** A test in a lower subsystem requires source of a higher
one. Inventory on 2026-10-09:

| test | reaches | note |
|---|---|---|
| `dao.space.query-test` | `yin.vm`, `yin.vm.code`, `yin.vm.linearize` | Datalog over a program stream: the peer-observer contract |
| `dao.stream.ws.dart-test` | `yin.repl.host` | |
| `dao.stream.journal.file-test` | `dao.space.store.fs` | mirrors the source leak above |
| `yin.vm.linker.*-test` (6 ns), `yin.vm.linker-require-test`, `yin.vm.host-typed-values-test` | `yin.repl`, `yin.repl.store`, `.dht`, `.index`, `.host`, `.link` | linker over the REPL store |
| `yang.python.antlr.int-contract-test`, `int-heap-test` | `yin.vm.ucf.handoff`, `lift-support` | Python integers over UCF handoff |

A seam test is selected by its home subsystem. It is also the reason the
home's one-subsystem run is not a landing gate: a seam test passing proves
the seam from the home side only. Section 7 handles the other side through
`test:changed`.

**Class C, shared fixture.** A test requires a fixture namespace that lives
in another subsystem's tree but carries no behaviour:
`yang.python.antlr.int-contract-fixtures` is required by
`dao.jing.stream-test`, `dao.stream.journal-test` and
`yin.vm.ucf.scalar-round-trip-test`. The fixture reaches no ANTLR class
(the prerequisite derivation confirms this), so the cost is cohesion, not a
build. Recommendation, out of scope here: move shared fixtures to a neutral
home under `test/` so that no layer-0 test names a layer-3 namespace.

**Class D, process seam.** A test spawns a built program rather than
requiring a namespace: `yin.repl.dht-process-test` spawns
`target/yin-repl.js`, `yin.repl.main-test` spawns `build/yin-repl-peer`,
eight `yang.python.antlr.*` tests load `build/antlr/python3/classes`. These
are the only prerequisites in the repository, and all three are derived
from text reach by `affected/prerequisites`. Note what follows: Track C
(`yin.vm.ucf`) needs none of them, although today's `bb test:clj` builds
the Node REPL and generates the parser unconditionally through `:depends`.

### 3.4 What the taxonomy does not do

It does not define an allowed-dependency matrix. The leaks in 3.3 are
recorded, not banned; banning them is an Architect ruling per edge, with
ADR 0001 (dao.space as storage boundary) the first thing to consult for the
`journal.file` edge. It does not split `deps.edn`, `shadow-cljs.edn` or the
cljd build. It does not move any test file.

## 4. Subsystem-First Host Matrix

### 4.1 Command shape

One generic task with arguments, not one static task per subsystem. bb
task names are static, and a table-driven selector needs no edit to
`bb.edn` when the table grows.

```sh
bb test:sub <subsystem>...                   # fast form, three lanes in parallel
bb test:sub:clj <subsystem>...               # one lane
bb test:sub:cljs <subsystem>...
bb test:sub:cljd <subsystem>...
bb test:sub:list <subsystem>...              # selection, prerequisites, seams; runs nothing
bb test:sub <subsystem>... --slow            # slow form (section 4.4)
bb test:changed --within <subsystem>...      # section 7.2, optional
```

Several subsystems in one call select their union; `bb test:sub dao.stream
dao.jing` runs the layer-0 cluster. The underlying script is
`bb src/dev/affected.clj --subsystem NAME [--lane L] [--list] [--slow]`,
mirroring the existing `--lane` and `--list` flags, so a subsystem run and a
changed run share one argument grammar.

Everything after selection is the existing runner: `lane-tests` per lane,
`prerequisites` and `serialized-pairs` from reach, `run-lanes!` in
parallel, logs at `target/profile/sub-<lane>.log`, the summary table and
the exit code (0 pass, 1 a lane failed or bad input). There is no exit 2:
a subsystem run does not look at the diff, so there is no wide change.

### 4.2 Lane commands

Unchanged from `affected/lane-command`:

| lane | command |
|---|---|
| clj | `clojure -M:test -e :slow -n ns...` |
| cljs | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge {:ns-regexp "^(ns|...)$"}` |
| cljd | `bb src/dev/cljd_agg.clj --only ns,...` |

The Dart lane compiles only the selected namespaces (`cljd_agg --only`),
which is where subsystem scoping saves the most: the whole-repo Dart lane
compiles about 166 generated files before sharding.

### 4.3 Prerequisites are derived, never declared

`affected/prerequisites` already answers "does any selected test, or a
namespace it requires, name the artifact a build task produces?" The
subsystem selector feeds it the same way the changed selector does. The
result on today's tree:

| subsystem | `build:yin-repl-node` | `build:yin-repl-peer` | `gen:python-antlr` | clj↔cljd serialized |
|---|---|---|---|---|
| `yin.repl` | yes (`dht-process-test`) | yes (`main-test`) | no | no |
| `yang.python` | no | no | yes (8 ns) | no |
| `dao.stream` | no | no | no | yes (`ws-project-cross-jvm-test`) |
| `yin.vm` | no | no | no | yes (`linker.cross-host-transfer-test`) |
| `yin.vm.ucf` | no | no | no | no |
| all others | no | no | no | no |

The table is informative only; the code recomputes it on every run, so a
new test that spawns the peer gets its build without a table edit. The
runner keeps printing `Prerequisite <task>` or `Skipping <task>` per build
so a log shows why a build ran. The existing failure modes stay: a missing
Node REPL fails `dht-process-test`; a missing Dart peer makes the R5 tests
print a skip notice. Neither passes silently.

A declared per-subsystem prerequisite list would be a second authority over
a fact the graph already states. It is rejected on the derive-don't-persist
rule.

### 4.4 Slow tests

The fast form is the default, as for every existing lane: `-e :slow` on the
JVM, guarded bodies print SKIP on Node and Dart. `--slow` selects the slow
form for the same namespaces: `-i :slow` on the JVM, `DATOM_SLOW_TESTS=1`
on Node and Dart. The existing Python landing rule, "run `clojure -M:test
-i :slow -n <ns>` for the changed Python namespaces", becomes
`bb test:sub:clj yang.python --slow`. Twelve of the sixteen guard
namespaces are in `yang.python`; the others are in `dao.space`,
`yang.base`, `yin.repl` and `yin.vm`.

### 4.5 What is deliberately not parameterized

- **Host-specific subsystems.** `world` is JVM only and `dao.postgraphics`
  has more Node and Dart tests than JVM ones. The lane filter handles this
  by file extension; a lane with zero selected namespaces prints `skipped`
  in the table, as `test:changed` does today.
- **Per-subsystem build aliases.** One `:test`, one `:cljs`, one
  `:clojuredart:cljd`. A subsystem does not get its own classpath.
- **Cross-worktree scheduling.** The rule "one Dart lane at a time
  repo-wide" is about `lib/cljd-out` and CPU, not about subsystems. Each
  worktree owns its own `cljd-out`, so the remaining contention is cores.
  Subsystem scoping shortens each worktree's Dart lane; it does not
  schedule them.

## 5. Gates

### 5.1 The ladder

| gate | when | command | hosts | sufficient for |
|---|---|---|---|---|
| G0 iterate | every edit | `bb test:sub:clj <own>` or `clojure -M:test -n <ns>` | 1 | nothing beyond the edit loop |
| G1 checkpoint | a slice's tests go green on the JVM | `bb test:sub <own>` | 3 | a worktree commit on the feature branch |
| G2 land | before merge into master, in the worktree | `bb test:changed` (three lanes), plus `bb test:sub <own> --slow` if the subsystem has slow tests | 3 | Architect sign-off and the merge commit |
| G3 master | the merge commit on master, and any wide change | `bb test` | 3 | fast-forward and push |
| G4 release | before a big merge or a commit touching many parts | `bb test:slow` or `bb test:all` | 3 | unchanged policy |

G0 is the only single-host step, and it matches the existing memory rule
"per-iteration verification is JVM only". G1 is where Node- and Dart-only
failures surface, before review rather than at landing. G2 is the existing
`test:changed` gate; it covers the dependents a subsystem run cannot see
(section 5.2). G3 is unchanged and stays mandatory: `test:changed` exits 2
on a wide change and documents its blind spots (dynamic loads, run-time
paths, spawned programs that changed), and the subsystem selector adds one
more (dependents). The whole-repo run is the only step with no blind spot
of its own.

### 5.2 Invariants of the ladder

1. **No gate above G0 runs fewer than three lanes.** A subsystem is a
   breadth cut, never a host cut.
2. **A subsystem green is never a landing claim.** G1 proves the home
   suite; it proves nothing about dependents. Landing needs G2 at minimum.
3. **G3 is not replaced.** Not by G2, not by a union of subsystem runs.
   `bb test` on master after the merge commit stays the auto-push
   condition.
4. **Foundation changes land through the closure.** For `dao.stream`,
   `dao.jing` and `dao.data`, G2 degrades to nearly the whole suite.
   That is correct, not a defect: a foundation change is gated by its
   dependents. Subsystem scoping gives these tracks G0 and G1; it does not
   shorten G2 for them.
5. **A prerequisite never passes silently.** A build the selection needs
   either runs or the run fails or prints a skip notice. Unchanged.
6. **One lane set at a time per worktree.** G1 and G2 runs are not
   overlapped on one machine where avoidable, as today.

### 5.3 Who runs what

Engineer delegates: G0 while iterating, G1 at the end of a slice, G2 in
the worktree before reporting ready. Briefs say the subsystem name. The
orchestrator: G3 after the merge commit, as the standing commit-on-sign-off
rule already requires. Architect review stays read-only and runs nothing,
so a review report that cites a green G1 must say G1, not "tests pass".

## 6. Relationship to `src/dev/affected.clj`

### 6.1 Two selectors, one runner

| | changed selector (today) | subsystem selector (this design) |
|---|---|---|
| input | a diff (git or `--changed`) | a subsystem name |
| question | what can this change break? | what is this unit's contract? |
| direction | reverse closure over the require graph | prefix membership of test namespaces |
| size for a foundation change | nearly everything | the home suite only |
| size for a leaf change | a handful | the home suite |
| wide change | exit 2, run `bb test` | not applicable |
| role | landing gate G2 | iteration G0 and checkpoint G1 |

They answer different questions and both are needed. Neither replaces
the other, and neither replaces G3.

### 6.2 Intersection mode (optional)

`bb test:changed --within <subsystem>` restricts the reverse closure to the
subsystem's home tests: "the `dao.stream` tests my change reaches". It is a
set intersection after both selections, costs a few lines, and gives the
foundation tracks a cheaper G0 than the whole home suite when a diff is
small. It is not a gate.

### 6.3 Blind spots, side by side

| not seen by | changed selector | subsystem selector |
|---|---|---|
| dynamic loads (`requiring-resolve`) | yes | n/a (no closure) |
| run-time paths and walk roots | yes | n/a |
| changed spawned programs | yes | yes (same prerequisite derivation) |
| dependents of the subsystem | no | yes, by design |
| seam tests in other homes | no | yes, by design |

`bb test:sub:list` prints the seams: every selected test's reach into
higher-layer source (class B) and the subsystems whose tests reach into
the selected one (the reverse question). The second list is what G2 will
run; printing it at G1 time tells an engineer how heavy their G2 is going
to be.

## 7. Migration Plan

No phase edits `deps.edn`, `shadow-cljs.edn`, the cljd build or any test
file. Each phase lands alone through the normal gate ladder, and every
phase touches `src/dev/`, which is a wide change: each lands under G3.

**Phase 0: table and baseline.**
Add `src/dev/subsystems.edn` holding the table of section 3.1 (prefix
vector per subsystem, layer as documentation only). Add to
`src/dev/affected_test.clj` an orphan check: every test namespace in the
scanned index maps to exactly one subsystem, and every prefix in the table
matches at least one namespace. Record a baseline: `bb test:sub:list` for
each subsystem, and one timed three-lane run per track subsystem, in
`docs/agents/build-n-test.md` next to the existing lane timings.
Acceptance: the orphan check passes on master; the three track baselines
are recorded.

**Phase 1: selector and tasks.**
Add `--subsystem NAME` (repeatable) to `affected/parse-args`; in `-main`,
when present, replace `select` with the prefix selection and skip the
git query, the wide check and `input-errors`'s changed-list clauses.
Reuse `lane-tests`, `prerequisites`, `serialized-pairs`, `run-lanes!` and
`print-table` unchanged; log files take the `sub-` prefix. Add the bb
tasks `test:sub`, `test:sub:clj`, `test:sub:cljs`, `test:sub:cljd`,
`test:sub:list` in the same shape as the `test:changed` family. Document
them in `build-n-test.md`. Acceptance: `bb test:sub yin.vm.ucf` runs no
build (table 4.3); `bb test:sub yin.repl` runs both peer builds;
`bb test:sub dao.stream` serializes clj then cljd; `bb test:sub nosuch`
exits 1 naming the subsystem; `bb test:changed` behaves exactly as before.

**Phase 2: slow form and intersection.**
`--slow` flips the three lane commands as in 4.4. `--within` adds the
intersection of 6.2. `test:sub:list` prints the seam lists of 6.3.
Acceptance: `bb test:sub:clj yang.python --slow` runs the same tests as the
current Python landing rule; the rule in `build-n-test.md` is rewritten to
name the task.

**Phase 3: gate policy.**
Rewrite the "While iterating" paragraph of `build-n-test.md` as the ladder
of section 5.1. Add the subsystem name to the Engineer brief template in
`docs/agents/roles/` and the G-level to the review report format. Add the
G3 reminder to the orchestrator's landing checklist. Acceptance: the next
slice on each track reports its gate level by name.

**Phase 4, deferred: runner extraction.**
When a third selector appears (for example a lane-health run or a CI
matrix), extract the runner (`scan-index`, `lane-tests`, `prerequisites`,
`serialized-pairs`, `run-lanes!`, `print-table`) into its own namespace
under `src/dev/` and leave `affected.clj` and the subsystem selector as two
small front ends. Not before: two selectors in one file is the minimal
diff, and the extraction trigger of `malleability.md` (about 12 functions)
is not reached by Phase 1.

## 8. Non-Goals and Open Questions

Non-goals: splitting the repository or its build configuration; a
per-subsystem allowed-dependency matrix; moving test files; any change to
what `bb test`, `bb test:all` or `bb test:slow` run; a CI definition.

Open, for an Architect ruling each and none blocking Phase 0 to 3:

1. Whether `dao.stream.journal.file` → `dao.space.store.fs` is a sanctioned
   edge under ADR 0001 or a leak to reverse.
2. Whether the shared `int-contract-fixtures` and the three seam fixtures
   named in class C move to a neutral `test/` home.
3. Whether `dao.stream` and `dao.jing` should be one subsystem for
   selection while remaining two ownership tracks. The table makes either
   answer a one-line edit.
4. Whether a seam test (class B) should be counted in both homes for G1,
   so that `bb test:sub yin.repl` also runs the `yin.vm.linker` tests that
   reach into it. This is derivable (reverse reach into the subsystem's
   source) and would be a flag, not a table entry.

## 9. Measurement

The counts and edges in this document were produced by loading
`src/dev/affected.clj` under babashka, scanning the index with
`affected/scan-index`, mapping every namespace to a subsystem by longest
prefix, and counting require edges between subsystems for source and test
namespaces separately; prerequisite reach used `affected/prerequisites`
on each test namespace alone. Phase 0 turns this ad hoc script into the
orphan check and the `test:sub:list` output so the numbers stay derivable.
