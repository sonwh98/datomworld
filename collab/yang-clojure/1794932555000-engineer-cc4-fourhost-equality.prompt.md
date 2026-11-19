Created-GMT: 2026-11-12 19:11:51 GMT
Created-Local: 2026-11-13 02:11:51 +0700
Coding-Agent: codex
Session-ID: (captured from the run's thread.started event at dispatch — codex accepts no caller-selected ID)

# Task: cc4 — the four-host Clojure C1 replay-equality gate & budget record

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-17 23:22 +0700 | Status: active | Rationale: execution-capable POOL 1 laptop lane; continuity with the post-port lowering rounds. Dispatch note: the D1 gate is DISSOLVED — D1 was stopped by the owner (2026-11-14); the owner released cc4 directly (2026-11-17). Worktree: /Users/sto/workspace/datomworld-cc4, branch cc4-fourhost-equality @ origin/master (2687ab0c). ANTLR: run `bb gen:antlr` first with mise shims on PATH (`export PATH=$HOME/.local/share/mise/shims:$PATH`; `mise trust` if asked).

**DISPATCH GATE — do not run this brief until the orchestrator fills the header above.**

## Where you work

Worktree: created at dispatch time from then-current `origin/master`, branch `cc4-fourhost-equality`. Work ONLY there. TDD: red first for every acceptance item. Commit NOTHING — the orchestrator commits after review.

## POST-CC3 / PORT / REFACTOR AMENDMENT (2026-11-13 — supersedes stale context below)

Three landings since this brief was drafted; your pinned-digest cohort and budget record measure THIS master:

1. **cc3 landed** (`2c80a421`) — the callsite battery on master is 44+ deftests (the equality-gate upgrade base is `equality-gate-test` at ~:115 plus the r7-r11 retention-state families). Pin digests against THIS battery's output.
2. **The port landed** (`309fe842` + kit `ba3cc407` + cljd fix `a3f7ab8d`) — yang.clojure's exception lowering is the continuation encoding (`finally` expressible, one-catch pinned; the outcome machinery retired, net −418 lines). Your pinned cohort's expectations must be recorded from POST-port compilation; per the attribution rule above, any budget shift vs the pre-port measurement attributes to the port's Phase 2 (`d60038ef`).
3. **The shell refactor landed** (`c62266ad..6445f37a`): `yin.shell.*` namespaces exist; `repl-query-link-forwarding-test` reads `yin.shell.query` on master (its `:cljd` gate from `4d0d3e70` preserved through the merge). Your J2-lane work (item 2) and the battery's cljd gates reconcile against THIS layout.
4. **Jolt lane**: `pair.cljc` (+675 lines) landed in the linker directory from the same refactor — the J2 manifest check (item 2) runs against that tree; the jolt seat's exclusions were relayed as unchanged.

## Goal and authority

cc4 closes the Clojure frontend's own adoption acceptance: prove that the same Clojure source, compiled on every host, yields **byte-identical C1 side tables and identical occurrence keys**, and record the measured budgets. Authorities:
- `collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md` §5.1–5.3 (the mandatory host sets, the cross-host equality without collapsing admissions, the frontend work package), §6 (measurement and budgets).
- `collab/yang-clojure/1794344088000-orchestrator-cc3-d1-mob-reconciliation.md` — the mob's Q3 ruling (cc4-final after D1) and the equal-gate-is-per-frontend rule.
- `collab/yang-clojure/1794505595000-to-steward-cc3-merged-port-phase-0-released.md` + the landed cc3 chain (`git log` on master from `2c80a421`) — what exists to test against.
- The landed battery: `test/yang/clojure_callsite_test.cljc` (44+ deftests; `equality-gate-test` at :115 is today's single-host self-consistency — cc4 upgrades it to cross-host equality).

## The work items (each = acceptance criterion)

1. **Pinned cross-host expectations.** Extend `equality-gate-test` (or add a sibling deftest) to assert, for the pinned portable cohort (design §5.2: same source, same grammar/options, same supplied admission coordinates; include floats, metadata, generated temporaries, duplicate trees, macro-generated calls, and the wrapper/prelude surfaces): the canonical root address, the full sorted C1 `[origin root-address path]` vector, and the published `clj`/`yang.call` side-table addresses are IDENTICAL to recorded values — recorded once on the dispatch host, then proven identical on every other host. The cohort must also include the negative fixture (design §5.2: identical text admitted twice with distinct supplied tokens → distinct origins — cross-host equality is replay equality, never content identity).
2. **Four-host execution.** Run the strengthened battery on: JVM (the dispatch host), Node (`bb test:cljs` / shadow), Dart (`bb test:cljd` / the cljd aggregator — respecting the existing cljd gates in the file, e.g. `#?(:cljd nil ...)` regions stay gated), and Jolt (`src/dev/jolt_j2_lane.clj` — add the callsite namespace to the J2 lane if absent, per the lane's manifest rules; coordinate with the jolt seat only if the lane's exclusions need a change). Each host's result recorded in findings with exact commands and counts. Where a host cannot run a region (documented cljd `with-redefs` gates), the recorded refusal is the acceptance — gates stay honest, no silent skips.
3. **Budget record — MEASURE BOTH (steward coordination 1794484000000, sol-consult-endorsed).** Per design §6.1–6.3 and the cc3 v2-declaration precedent (`1794390804000-cc3-bounded-run-declaration-v2.md` — read it for the format): measure and record, for the pinned corpus on each host, the static side-artifact bytes (C1 rows + provenance relation), the retained-memory and peak-heap figures with the named method and comparability limits, pipeline elapsed times, and the max-based pass/fail verdicts against declared finite ceilings. Declare the ceilings BEFORE measuring (declare from the cc3 v2 baselines + headroom; a measured exceedance is recorded as a failed bound, never a silently raised ceiling). Separate configurations: B (trace disabled) vs S (static annotation) per design §6.2 — S−B is the overhead record.
   **Attribution (binding at dispatch):** cc4 measures the POST-port lowering (the encoding all four frontends will run — the port merges before cc4 lands). If any budget shifts vs a pre-port measurement, attribute the shift to the port's Phase 2 (`d60038ef`), not a cc4 regression. Measure BOTH: pre-port baseline = the master commit preceding the port's merge; post = the port branch/merged master. The port's Phase 3 depth harness (on its branch) exists for exactly this comparison.
4. **The acceptance bundle.** Assemble per design §5.3's Clojure brief: the artifact checklist, host matrix results, the equality report, the budget tables, legacy-load behavior (unannotated artifacts still load), and the refusal inventory. This bundle is what D1's verifier and future consumers cite.

## Bounded file ownership

MAY edit: `test/yang/clojure_callsite_test.cljc`, `src/dev/jolt_j2_lane.clj` (lane manifest only), new fixture files under `test/resources/yang/clojure/` if the cohort needs them, and NEW files for the budget record under `test/yang/latte_oracle/` or `collab/` (propose in findings).
MUST NOT: any `src/cljc` compiler file (cc4 is a test/acceptance round — if a host divergence is found, STOP and report it as a defect with the diverging artifacts; fixing it is a new round), `slice9_site_tables.edn`, the forbidden set from cc3 (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc).

## Invariants

The pinned cohort's expectations are recorded data (digests), not recomputed per host at test time — the test compares each host's output to the SAME recorded values. A host that cannot run a region records the refusal; nothing is silently skipped. Origins never minted. No canonical change.

## Verification

JVM/Node/Dart battery runs per the build-n-test gate ladder (G1 laptop where allowed; the four-host matrix is inherently shiva/jupiter work — coordinate hosts per the lane-overlap rules; one Dart lane at a time). kondo 0 errors on touched files. The budget measurements on the dispatch host + at least one cross-check host.

## Deliverable

Findings per protocol; include the host matrix (per-host commands, counts, digests matched), the budget tables, and any divergence found. Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
