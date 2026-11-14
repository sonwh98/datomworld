# D1 bounds declaration — DRAFT (static per-fixture gates; prepares the r9 tagged run)

Created: 2026-11-14. Role: VM Engineer (compiler provenance), thread
1794422563000 (D1 correspondence), round r8 deliverable 3.

**Status: DRAFT ONLY — no run has been performed under these ceilings,
and nothing here authorizes a tagged run or reliance.** This declaration
is written AFTER the r8 static corpus run (its numbers are that run's
measurements) and BEFORE any r9 tagged verification run, per the partial
round's requirement that a finite D1 declaration precede any tagged
run. The r8 run itself was NOT a ceiling-governed run: it is the
measurement source. The ceilings in section 3 become binding for the
first run declared under them (r9's tagged run), which must re-state
or tighten them from its own measurements.

The r8 gates are STATIC: no C lanes were run, no guest fuel consumed, no
traces recorded. Lane-quantity ceilings (guest instructions, trace
records and capacity) are therefore NOT declared here; r9 must measure
and declare them before its run. cc3 v2.1 historical maxima remain
baselines only, not D1 numbers.

## 1. Pinned corpus, revision, host, and settings

| Item | Pinned value |
|---|---|
| Worktree / branch | /Users/sto/workspace/datomworld-d1, branch d1-correspondence @ 56cc5ca3 + the uncommitted r1-r8 diff |
| Tracked diff (git diff, all of it test/yang/latte_oracle_test.clj) | 33,521 B, SHA-256 55c766cb5eef958f26b3ed03cb9169022cb66869fb8734777477fd65cd89dff1 |
| New untracked code | src/cljc/yang/correspondence_batch.clj; test yang/correspondence_batch_test.clj (all other r1-r7 files unchanged this round) |
| Corpus (the whole workload) | test/yang/latte_oracle/kernel_gate.edn, SHA-256 8763e273e08259686477315f41956d7005981327b7965bf01e83f9cce487c3e3, 155 fixtures |
| Frozen v1 evidence | test/yang/latte_oracle/slice9_site_tables.edn, SHA-256 e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75 (unchanged since r1) |
| Run record | collab/yang-clojure/1794422563000-engineer-d1-correspondence-r8.outcomes.edn, SHA-256 7c2f9a6a17bdd144ba67be0326dc4b6ebdb6a83a7297ab08ac82e4ef8aef943a — 155 :pass, 0 :refused, 0 :not-run, with per-fixture admission origin, storage delta, elapsed, row counts, site count, mutation-refusal stage |
| Upstream oracle | latte-kernel 86fca0d1d054af005cb37395eae04500d45e6279 (deps.edn :test pin) |
| Host | macOS 26.6.2 arm64, MacBookPro18,3, 17,179,869,184 B RAM |
| JVM / Clojure | OpenJDK 17.0.18 (Homebrew, 64-Bit Server VM), Clojure 1.12.4, -Xss64m via the :test alias |
| Driver | d1-corpus-batch! + test-d1-corpus-static-gates in test/yang/latte_oracle_test.clj; per-invocation run config target/d1-corpus-run.edn |

## 2. Workload and retained object set

One fixture static gate, held one fixture at a time (maximum
simultaneously retained fixtures: 1; each holder released before the
next):

- the admission: a fresh recorded admission coordinate
  [:source :program [run-scope ordinal] 0] (distinct ordinal per fixture
  within a batch run scope; fresh scope per batch invocation);
- the compiled input: compile-program output, stamped AST, site table,
  reconstruction input (captured forms/call/program/options);
- the binding: producer C1 tree + rows, stamped tree + rows,
  correspondence map, site-to-occurrence bindings, and the independent
  reconstructions checked against them;
- the persisted set: the whole v1 evidence map (content-addressed,
  written once for the corpus), the profile (once), and per fixture the
  reconstruction input, the binding snapshot, and BOTH row closures as
  individually addressed rows (the 128-nesting workaround);
- the fetch side: both complete row closures re-fetched with a
  100,000-row bound, the replayed root recompilation, and the
  independent gate (source hash, site declarations, bindings, C1 marker
  coverage) plus the per-fixture declaration-mutation refusal.

One durable store handle per batch invocation (cross-process reopen per
batch: full content-log validate-and-replay at open). The store is the
shared accumulating measurement store; nothing else writes it.

## 3. Ceilings (separate, finite, measured maxima + headroom)

Measured maxima are from the r8 run recorded in the outcomes file
(maximum-based rule below).

| Quantity | r8 measured maximum | Ceiling | Headroom / justification |
|---|---|---|---|
| Storage, per fixture (store growth, content log) | 17,903,978 B | 24,000,000 B | +34 percent over the maximum; covers admission-scope/ordinal string growth and row-set jitter |
| Storage, aggregate full-corpus store (155 fixtures, content log incl. lock) | 734,451,961 B | 1,000,000,000 B | +36 percent; a HEAD manifest and companion records are additional but small (declared before the run that adds them) |
| Row-closure size, per tree (pre-stamp or stamped) | 12,193 rows | 20,000 rows | 1.64x; the fetch bound of 100,000 rows stays as the gate's own hard refusal limit |
| Sites per fixture | 451 | 700 | +55 percent; corpus total 20,805 sites (equals the r2 independent inventory — informational cross-check) |
| Elapsed, per fixture static gate | 136,513 ms (with per-fixture close/reopen protocol) | 300,000 ms | 2.2x; covers the slower early-protocol shape and store-replay growth |
| Elapsed, per batch invocation (25 or fewer fixtures, one cross-process reopen) | about 12 min | 60 min | 5x; the operated hard background limit was 2 h per invocation |
| Peak used heap | not durably recorded in r8 | NOT DECLARED | must be measured and declared by r9's own declaration before its run; cc3 v2.1 1 GiB figure is a baseline, not adopted |
| Guest instructions / trace records | not applicable (static) | NOT DECLARED | r9 (C lanes) must declare; cc3 precedent 4,000,000 / 1,000,000 is a baseline only |

## 4. Exact reproducible measurement procedure

From the worktree root, per batch (the outcomes file is the progress
record: recorded outcomes are final; an interrupted batch loses at most
the in-flight fixture, which re-runs under a fresh admission on resume):

1. Write target/d1-corpus-run.edn with keys :D1_CORPUS_OUTCOMES
   (collab/yang-clojure/1794422563000-engineer-d1-correspondence-r8.outcomes.edn),
   :D1_CORPUS_STORE (target/d1-r8-corpus-store), :D1_CORPUS_BATCH 25 and
   (first batch only) :D1_CORPUS_SPOT "1".
2. Run clojure -M:test -n yang.latte-oracle-test -v
   yang.latte-oracle-test/test-d1-corpus-static-gates — initializes the
   outcomes file with all 155 ids on the first invocation, then records
   the next 25-or-fewer not-yet-run fixtures atomically.
3. Repeat step 2 until the summary reads fixture-count 155, pass 155,
   refused 0, not-run 0.
4. Without the run config present, the same deftest runs scratch mode
   (3 fixtures, fresh scratch store, resumability + finality + spot-check
   asserts) — the standing-battery form.

Actual r8 batch history (all against one shared store): batch 1 was
killed at a 10-minute background limit after 17 recorded fixtures, and
its resume was stopped after 14 more (31 total) to remove the per-fixture
close/reopen (that reopen replays a growing content log: per-fixture wall
had reached 136 s); batches then ran with one store handle per invocation
(cross-process reopen per batch) at 25, 25, 25, 25 and 24 fixtures — 155
total. A killed batch's partial log tail is truncated at the next open
by the store's own validate-then-truncate recovery; the re-run
fixture's identical content dedupes, so per-fixture deltas remained
additive (sum of deltas = final store bytes, exactly).

## 5. Pass/fail rule

Maximum-based (cc3 v2 precedent): a ceiling is met only if the MAXIMUM
observed value is at or under it; any single sample over its ceiling is
EXCEEDED. Medians and totals are informational. The r8 run's samples
are recorded per fixture in the outcomes file and were not governed by
these ceilings (they precede this declaration).

## 6. Limits of these measurements (named, not hidden)

Elapsed figures are warm-JVM wall clock on this host class and mix two
reopen protocols (31 fixtures with per-fixture close/reopen, 124 with
per-batch); storage figures are content-log bytes including dedup across
fixtures (a per-fixture-fresh-store retention would be strictly larger);
the store has no HEAD manifest (a published retention artifact adds one
plus ownership records); heap sampling existed only transiently per
batch and no durable heap maximum was recorded.
