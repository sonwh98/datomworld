Created-GMT: 2026-10-03 09:57:21 GMT
Created-Local: 2026-10-03 16:57:21 +07 (+0700)
Coding-Agent: claude
Session-ID: 40e7eefa-ade3-464b-8fbe-c7b40fce9490

# Task: Test-suite audit — what is unnecessary or redundant

Role: QA, TDD & Verification Engineer (audit only)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-03 16:57 +07 | Status: active | Rationale: owner asked for fable to audit the whole suite; long-context, strict-boundary static analysis

Audit the test suite in /Users/sto/workspace/datomworld (master 1f8b7e37 plus uncommitted ^:slow tags). READ-ONLY:
do not edit, do not run suites (the machine is shared with other work), do not touch collab/ except to read.

## Why

Owner, verbatim: "these tests are still too slow to iterate quickly. can you ask fable to audit the whole tests
suite to see which are unnecessary or redundant?" The default lanes today: JVM about 5.5 min (2900 tests,
226562 assertions), Node about 7.5 min (2718 tests, 92053 assertions), Dart about 10 min (2673 tests). The
owner wants fast iteration, not less assurance: remove or shrink what buys no assurance.

## Inputs

- Per-test JVM timings (top 40 tests, top 25 namespaces; the full-suite total of test time was 768 s):
  /Users/sto/workspace/datomworld/collab/1791021441000-qa-test-suite-audit.jvm-profile.txt
  Already tagged ^:slow (excluded from default `bb test:clj`): the 21 tests in that profile with time >= 5 s,
  plus yang.python.antlr.e2e-test/long-loops-test (14 min).
- 220 test files (about 128,000 lines) under test/, excluding the generated test/cljd-out/. Read
  docs/agents/build-n-test.md (lanes) and docs/design/datom.world.md (axioms and invariants) first.
- Lane facts: JVM runs cognitect test-runner (`bb test:clj` = `clojure -M:test -e :slow`); Node runs
  shadow-cljs compile of the :test build; Dart runs `clojure -M:clojuredart:cljd test` over the same .cljc.
  A .cljc test therefore runs three times; a .clj test runs once (JVM only).

## What to find

For every candidate, give evidence, not suspicion. Categories:
1. REDUNDANT: two or more tests establishing the same property (name the other test(s) that already cover it,
   with file:line). Includes near-duplicate "gate-round-N-test" regression pins, per-VM copies where the VM
   matrix adds nothing, repeated setup that re-proves a lemma already proven in a dedicated test.
2. UNNECESSARY: pins behavior that was removed or superseded; asserts an implementation detail the design does
   not require; tautologies that cannot fail; tests for dead code (agent.tzu is unused dead code; check others);
   benchmarks living in the suite (for example yin.vm.debruijn-register-benchmark-test).
3. OVER-COST: correct and needed, but costs far more than its value: loop counts or sizes larger than needed to
   expose the failure it guards, full VM matrices (4 VMs x 3 hosts) where the property is host-independent, process
   spawning where an in-process peer suffices, fixed sleeps/timeouts. For these propose the cheaper form
   (shrink the parameter, run on one VM, run JVM-only, or tag ^:slow) and the estimated saving.
4. CROSS-HOST: a .cljc test that runs on all three hosts only needs to if its property is a cross-host
   parity claim. Cross-host parity is a core invariant: never recommend dropping a parity test; do identify
   .cljc tests whose property is host-independent and could become JVM-only (.clj), saving two lane runs.
5. KEEP-WITH-REASON: anything that looks redundant but is protecting an invariant, a past incident, or a
   frozen fixture (canonical bytes, addresses, goldens). Name these so nobody removes them later.

## Constraints

- Preserve every invariant in datom.world.md and every frozen contract (cbor fixtures, UCF goldens, canonical
  bytes). Mark any recommendation that touches a golden or fixture as HIGH RISK.
- Do not recommend a deletion unless you can name what still covers the property. "Slow" alone is not a reason
  to delete; it is a reason to shrink, tag ^:slow, or reduce its matrix.
- Work breadth-first: first sweep names and namespaces using the profile and file sizes to rank where the time
  and the duplication are, then read the highest-value files in full. You may delegate reading of disjoint
  namespaces to subagents if your harness allows; you remain responsible for the evidence in every finding.
- Be explicit about what you did NOT audit. A partial audit with honest coverage beats a claimed full one.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then a table, one row per recommendation, ordered by estimated seconds saved per lane:
test or namespace (file:line) | category (1-5) | evidence (what covers it instead, or why it is unneeded) |
action (delete / merge into X / shrink param to Y / JVM-only / tag ^:slow / keep) | est. seconds saved
(JVM, Node, Dart separately where it differs) | risk (low / medium / HIGH) .

Then: (a) the total achievable saving per lane if every LOW-risk row is taken, and if MEDIUM rows are added;
(b) the coverage you examined versus skipped; (c) a recommendation on how to keep the suite fast
(a time budget per test, a CI check, a convention for ^:slow). Do not write anything outside your final
response.
