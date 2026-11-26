Created-GMT: 2026-11-26 02:0x GMT
Created-Local: 2026-11-26 09:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1; the lanes and receipts live here)
Task-ID: 1795700000000-qa-engineer-shiva-wall-optimization

# Task: why does the shiva head-nightly take ~12.6h, and what cuts it most?

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-26 09:0x +0700 | Status: active | Rationale: the run artifacts and both lanes are on shiva; debug where the time went

## Evidence of record (head-nightly run 20261123-2121 at `0d378482`, shiva)

- Node lane: **45,428s total (12.6h)** — ended rc=143 (SIGTERM at teardown while
  `uncovered` was still running `yang.typescript.antlr.source-call-test`).
- Dart lane: **35,756s (9.9h)**, `flutter rc=1 wall=34873s`.
- The agy cross-review
  (`collab/general/1795590000000-architect-agy-crossreview.gemini-4-argon-high.findings.md`)
  already established, from `all.log` timestamps:
  - **Dart**: every test except `yang.typescript.antlr.corpus-test` finished by 134:37
    (8,077s); `corpus-test` then ran ONE isolate for 29,066s (`92:27`→`576:53`) because
    `dart_shard.clj:161` appends `"uncovered"` LAST in `@dirs` (started 5,547s late).
    Its 6 slow deftests decompose: t1-bundled 7,691s, t1-linked 8,182s, t2-bundled
    6,788s, t2-linked 2,501s, t3 3,904s — a per-var isolate split cuts dart wall to
    ~9,000-10,000s WITHOUT a second Flutter driver (R3 `build/test_cache` constraint
    respected: one driver, multiple emitted shard files).
  - **Node**: the `yin.vm` subsystem group = 31,901s in ONE serial node process
    (74 namespaces) vs Dart running the same 74 namespaces in 8 cljd-agg shards in
    5,494s on the same host. `yang.python` group 25,244s vs 7,173s on Dart. The landed
    `857c297d` redistribution fixed the residual only; the monolithic subsystem groups
    remain. `node_shard.clj:27` hardcodes the legacy 13-subsystem list instead of
    reading `src/dev/subsystems.edn`.

## Mission

Produce a MEASURED optimization plan for the shiva wall, then implement the safe parts:

1. **Decompose the last run's wall precisely** from the receipts (no re-runs needed for
   this part): per-job start/end from `node-shards-20261123-2121/` and
   `dart-shards-20261123-2121/all.log`, Gantt-style, showing where slots idled and which
   job was critical path at every moment. Identify the top 5 wall contributors with
   seconds.
2. **Implement (driver-only, both safe by prior rulings):**
   a. `dart_shard.clj`: order emitted shard files by DESCENDING historical weight
      (corpus-test/uncovered, yin.vm, yang.python, yin.repl first) so the heavy isolate
      starts at t=0 instead of 92:27. (~1.5h back with zero risk.)
   b. `cljd_agg.clj`: emit per-var wrapper `_test.dart` files for
      `yang.typescript.antlr.corpus-test` (and any namespace whose slow deftests exceed
      ~2,000s each) so the SINGLE Flutter driver runs its 6 tiers as concurrent
      isolates. (~5.5h back; still one driver, no `build/test_cache` sharing.)
   c. `node_shard.clj`: replace the hardcoded `all-subs` with `src/dev/subsystems.edn`
      + explicit sub-shard splits of `yin.vm` (74 ns → 4-6 weighted groups, isolating
      semantic.derive/safepoint-harness/scan/linker.dht-end-to-end/linker) and
      `yang.python` (22 ns → 3-4 groups) on the existing work-stealing queue with
      measured weights.
   d. Correct the residual weight table: the uniform 2163 estimates become measured
      values after one run; note the refresh mechanism in comments.
3. **STOP conditions**: if any split requires editing a test file, or breaks the
   one-Flutter-driver constraint, or changes a namespace's inventory membership — STOP
   that item, findings only. Non-weakening: every namespace runs exactly once; pin
   publish untouched; failures fail the lane.

## Verification (scoped only)

1. Schedule listings before/after: total namespace/var set equality for node AND dart;
   new Gantt model (from the measured decomposition) projecting the new wall.
2. One split node group green scoped (`node target/node-tests.js --test=<group>`).
3. Dart: the corpus per-var wrapper files compile (`cljd compile` of the affected ns)
   and ONE wrapper shard runs green scoped (pick the shortest, t2-linked ~2,501s… prefer
   emitting-and-compiling all 6 wrappers but running only `t3` end-to-end if time
   demands; record which).
4. clj-kondo clean on touched files.

## Deliverables

Branch `fix/shiva-wall-optimization` pushed; findings
`collab/general/1795700000000-qa-engineer-shiva-wall-optimization.codex-gpt-6.1-terra.findings.md`
(Gantt decomposition, per-item implemented/stopped status, projected vs measured walls,
every rc line, head sha). Orchestrator countersigns; the next shiva head-nightly
measures. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
