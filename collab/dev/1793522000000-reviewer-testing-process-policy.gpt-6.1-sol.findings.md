I’ll read the project rules, versioned drivers, and available run artifacts, then assess the policy against that evidence. I’ll keep all inspection read-only and leave runs untouched.


===

One policy claim is already off: 03:00 and 17:00 are 14 hours apart, then 10 hours apart. Detection also includes test runtime, so the stated 12-hour worst case is too low. I’m checking whether the scheduled runs and coverage records support the other guarantees.


===

The hourly cron entries are installed on both hosts. The run evidence is less reassuring: the completed JVM slow run has seven red namespaces, and the latest paired JVM run has quick and slow failures. Shiva’s paired run is still blocked in Node, with Dart queued behind it.

There is also a pairing risk: both hosts fire at the same minute, but Jupiter accepts any pin dated today. At 17:00 it could read the 03:00 pin before Shiva publishes the new one.


===

The artifacts confirm the main slow and Node timings, with two corrections: 976 seconds is the longest quick shard; the whole quick phase took 1,023 seconds. Also, `dao.space` and `yin.vm.ucf` took 109–110 seconds, so the “all other shards ≤37 seconds” claim is incorrect.

The full-coverage claim remains unproven. Node still has an unfinished residual shard and two terminated shards. Dart has only a completed six-namespace subset. Its current driver also drops the `DATOM_SLOW_TESTS=1` setting before launching Flutter, which could let a future green run skip slow bodies.


===

**A — Merge gate: reasonable selection strategy, insufficient as the sole safeguard.**

I support removing the full-suite requirement from every merge. Reverse-require closure, fixture references, walkers, and broad-change routing provide useful coverage at a sensible cost. Repeating hours of unrelated tests for each small change would impede development.

But static reach is an approximation of impact. It can miss:

- Runtime registration, multimethod/protocol implementations, dynamic resolution, and interpreted programs loaded as data.
- Stream producers and consumers coupled through message formats, content addresses, DHT publication, or persistence contracts.
- Spawned Node/Dart programs whose dependency relationship is absent from the namespace graph.
- Computed resource paths, environment configuration, indirect directory walkers, and generators or goldens whose relationship the scanner cannot reconstruct.
- Interactions between independently tested branches after they meet on master.
- Slow-only regressions when the landing gate executes quick tests. The documented affected slow-test supplement must remain explicit.

These are particularly relevant here: the architecture deliberately lets interpreters communicate through data without knowing about each other. **A require graph cannot fully describe that architecture’s behavioral dependencies.** [Architecture]( /Users/sto/workspace/datomworld/docs/design/datom.world.md), [selector]( /Users/sto/workspace/datomworld/src/dev/affected.clj:306).

I would add a small, always-run contract smoke set: codec/content-address stability, stream append/read, publication and rehydration, and an actual JVM/Node/Dart transfer-and-execute round trip. Set a **2–5 minute warm execution budget**, then measure build and startup overhead separately. That is a proposed budget, not an established cost.

I would also make `test:routed` the canonical landing entry point: it already escalates non-document changes with no selected tests, whereas direct `test:changed` can return success after selecting nothing. Foundation JVM feedback must remain feedback until the selected Node and Dart checks finish. [Routing]( /Users/sto/workspace/datomworld/src/dev/test_routed.clj:42).

Running the same selected tests on both physical machines would add less value initially: it duplicates the selector’s blind spots. All three runtimes plus a deliberately independent smoke set is the better first expenditure.

Delayed detection is tolerable for a development master when the consequence is recoverable rework. I would require stronger evidence at releases and changes to durable data or interoperability contracts.

**B — Cadence: twice daily is defensible; the claimed latency is incorrect.**

03:00 → 17:00 is **14 hours**; 17:00 → 03:00 is **10 hours**. Detection also includes execution and reporting time. A regression landing just after the 03:00 snapshot could therefore remain undetected until roughly **19:00** for a two-hour lane. Node and Dart currently have no demonstrated completion bound.

The inspected evidence supports these measurements:

| Measurement | Assessment |
|---|---|
| JVM slow: 52 dispatched namespaces, 5,645 seconds | Confirmed in Jupiter’s completed `20261031-1538` artifacts; **seven namespaces red** |
| Longest three: 5,064 / 4,523 / 3,460 seconds | Confirmed |
| Top twelve elapsed namespace times total approximately 402 minutes | Confirmed: 24,127 seconds |
| JVM quick: 976 seconds | Confirmed as the **longest shard**; complete quick phase was **1,023 seconds** |
| All other quick shards ≤37 seconds | Incorrect: `dao.space` was 109 seconds and `yin.vm.ucf` 110 seconds |
| Node: 5,527 / 899 / 824 seconds | Confirmed in Shiva’s `node-shards-20261101-0146/runs.txt` |

The 402-minute figure sums elapsed times measured during concurrent execution. It is neither a measured serial benchmark nor CPU consumption. The demonstrated parallel slow-tier wall cost is approximately **94 minutes**.

I would retain twice daily provisionally and describe its actual exposure honestly. More frequent runs reduce the number of intervening merges, diagnosis effort, and work built upon broken behavior. They also consume capacity and can overlap unfinished runs. Frequency alone cannot repair a lane that does not complete.

I would split the longest namespaces by independent test vars or corpus partitions, preserving an explicit inventory. Moving intact 84-minute namespaces between hosts cannot remove that lower bound. Start expensive jobs early and balance using recorded durations.

The reported low-GC profile supports investigating query/unification work before increasing heaps. It describes one focused reproduction; it does not explain every long shard.

Finally, schedule around **result availability**. A 03:00 start yielding a 05:00 result may miss the intended “before stopping coding” window.

**C — Host/lane split: useful parallelism, unjustified fixed allocation.**

Two machines can reduce feedback time even when both are idle much of the day. Average utilization is less important than elapsed time from snapshot to actionable result.

Ignoring contention, the present arrangement takes approximately:

`max(JVM, Node + Dart)`

One machine running sequentially takes:

`JVM + Node + Dart`

The split therefore has real potential value. However, putting Dart behind an unbounded Node lane is a poor dependency. During inspection, Shiva’s paired run was still waiting for Node; an older Dart invocation was also present. Jupiter’s paired JVM invocation had finished in approximately 127 minutes.

I would provisionally try **Node on Shiva, JVM plus Dart on Jupiter**, using separate clones and serialized Dart build ownership. Measure before making that permanent.

The deciding measurements are:

- Same SHA and toolchain: complete lane duration on each host, including prerequisites.
- Peak RSS, active swapping, CPU utilization, and I/O during the expensive portions.
- Warm versus clean build cost.
- Time until every required lane finishes and until the first actionable failure appears.
- Contention when JVM and Dart overlap versus run sequentially.

Two identical Linux machines running different lanes do **not** establish distributed behavior between those machines. That requires tests which actually communicate across them.

There is also avoidable scheduling imbalance: Node and Dart enumerate thirteen subsystem names, while the canonical registry contains additional subsystems. Those tests fall into the residual, concentrating work there. The residual protects membership; scheduling should derive directly from the registry. [Node]( /Users/sto/workspace/datomworld/src/dev/node_shard.clj:27), [Dart]( /Users/sto/workspace/datomworld/src/dev/dart_shard.clj:28).

**D — Coverage/evidence integrity: insufficient to claim a completed full suite.**

The residual is valuable. It establishes that omissions from subsystem assignment need not omit tests. It does **not** independently establish that discovery, compilation, selection, execution, and slow-body activation were complete.

The evidence currently establishes:

- An older complete JVM execution, with failures.
- A newer JVM quick plan reporting **316 selected and covered namespaces**, followed by execution with one error; its slow phase also reported seven red namespaces.
- Node partial completion, including `yin.repl` passing 389 tests. `yin.vm` and `yang.python` ended with **rc=143** after approximately 104 minutes; the residual remained unfinished.
- A successful Dart **six-namespace subset**, with 34 tests passing. No completed full Dart execution was found.

Several process guarantees remain weaker than stated:

1. **The paired snapshot is vulnerable to a race.** Both cron entries fire at minute zero. Jupiter reads once and accepts a pin dated today, rather than requiring the current 03:00 or 17:00 slot. At 17:00 it can accept the morning pin before Shiva publishes. At 03:00 it can reject yesterday’s pin and run an advisory snapshot. A bounded wait for an exact shared slot identifier, followed by aggregate SHA comparison, is needed. [Pin protocol]( /Users/sto/workspace/datomworld/src/dev/nightly.clj:189).

2. **Separate evidence directories do not isolate execution.** Both inspected clones had advanced to `a3ac56bb` while older run records named earlier revisions. That does not prove their loaded code changed, but demonstrates that pinning does not prevent subsequent checkout or generated-output mutation. Overlapping runs need exclusive clone ownership or separate worktrees and outputs.

3. **Node inventory validation permits unexplained omissions.** Requested namespaces absent from the bundle are automatically classified as lane exclusions. This accommodates legitimate JVM-only tests, but can also conceal discovery mistakes. Bundle-only namespaces are not independently reconciled against assignment. Coverage needs justified lane exclusions and checks in both directions. [Inventory handling]( /Users/sto/workspace/datomworld/src/dev/node_shard.clj:143).

4. **Dart currently drops its slow-test switch.** Its process helper replaces caller `:extra-env` with PATH alone, discarding the `DATOM_SLOW_TESTS=1` supplied to Flutter. Unless inherited independently, guarded slow bodies skip. A future green Dart execution would therefore still fail to establish full coverage. [Helper]( /Users/sto/workspace/datomworld/src/dev/dart_shard.clj:42), [Flutter invocation]( /Users/sto/workspace/datomworld/src/dev/dart_shard.clj:162).

5. **“52 slow namespaces” is dispatch evidence, not execution coverage.** One entry is `dao.test-slow`, whose log reports zero tests. Textual discovery of `^:slow` is not a complete test-var inventory.

I would require a durable run manifest connecting expected tests, justified exclusions, compiled inventory, executed tests, skips, and terminal status. Missing or timed-out work must remain visibly incomplete. Add a deadline/missing-result alert: cron attempts alone cannot guarantee twice-daily completed evidence.

These are process-significant observations, rather than another general harness code review.

**E — Opinion: the direction is sound; operational confidence is premature.**

For an owner-plus-agents project, selective landing gates and scheduled broad verification are sensible. Versioned drivers, residual assignment, prerequisite builds, and preserved logs are worthwhile engineering.

The imbalance is that substantial scheduling machinery exists before there is a trustworthy completed baseline across all three lanes. Meanwhile, the essential feedback contract remains unsettled: how long can a lane take, how is missing evidence noticed, and what happens after master is red?

Seven red slow namespaces are not inherently an indictment of the process—finding failures is its purpose. They become a process failure if subsequent runs merely repeat them without ownership and resolution. Assign failures promptly; halt dependent landings where appropriate; keep any accepted baseline failures explicit, narrow, and time-bounded. A permanently red aggregate cannot support confident regression detection.

**Overall verdict: REVISE.** Keep the selective-gate direction and provisional twice-daily cadence. Revise the coverage claims, completion guarantees, and operating rules before relying on the scheduled suite as compensation for reduced merge coverage.

The first three changes I would make are:

1. **Establish trustworthy full-run evidence.** Fix slow-body activation, exact-slot pairing, execution isolation, and expected-versus-executed reconciliation. Obtain one complete three-lane baseline and assign every failure. Alert on missing completion.
2. **Add bounded contract smoke coverage to landing gates.** Cover the stream/data/runtime boundaries the selector cannot infer. Use fail-closed routing for unexplained empty selections and test the candidate against current master.
3. **Reduce the longest execution units and rebalance Dart.** Partition pathological namespaces/corpora, schedule from measured durations, derive subsystem assignment from the registry, and benchmark both host allocations before adding scheduling complexity.

I verified the live crontabs, versioned drivers, and cited existing remote artifacts. I did not start, stop, or modify runs. I could not verify a completed full Node or Dart lane, clean-build equivalence, repeated-run timing distributions, exercised cross-host network coverage, or the raw CPU profile underlying the reported 2.25% GC measurement.