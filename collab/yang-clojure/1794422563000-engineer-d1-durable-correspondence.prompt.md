Created-GMT: 2026-11-11 18:42:43 GMT
Created-Local: 2026-11-12 01:42:43 +0700
Coding-Agent: TBD-ON-DISPATCH
Session-ID: TBD-ON-DISPATCH

# Task: D1 — the durable correspondence record (:yang.correspondence/schema 1) + independent reconstruction verifier

Role: VM Engineer (compiler provenance)

Implementers:
- Model: TBD-ON-DISPATCH | Assigned: PARKED — this brief is DRAFTED, NOT DISPATCHED. Dispatch is gated on: (1) cc3 landed on master, (2) the owner's word (the adoption rounds 2-4 + D1 queue is parked per the exception-track registry's conditional pause and the steward's 1794401000000 note). When the owner lifts the park, fill the header and dispatch per the routing rules then in force.

**DISPATCH GATE — do not run this brief until the orchestrator fills the header above.**

## Where you work

Worktree: created at dispatch time from then-current `origin/master`, branch `d1-correspondence`. Work ONLY there. TDD: red first for every acceptance item. Commit NOTHING — the orchestrator commits after review.

## Goal and authority

D1 is the durable correspondence record binding the LaTTe oracle's evidence to real C1 occurrences, plus its independent reconstruction verifier. Authorities, in order:
- `collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md` §4 (the D1 envelope, verbatim-evidence rule, the recipe, independent reconstruction), §6.3 (bounds), §8 (acceptance sequence).
- `collab/yang-clojure/1794344088000-orchestrator-cc3-d1-mob-reconciliation.md` — the mob's Q2 ruling (3/3): **separate companion file `test/yang/latte_oracle/slice9_correspondence.edn` referencing the `:version 1` `slice9_site_tables.edn` VERBATIM — never upgrade those bytes in place; K-keyed access is a verified query through the `:origin-bindings` relation, not a second attribution table; integer site ids are scoped per compilation; historical runs without retained admissions never acquire invented origins** (owner ruling U: origins are never minted).
- `collab/yang-clojure/1794374077000-engineer-cc3-fix-r2.muse-wonder-1.0.findings.md` §P1-4 and cc3's landed code: the provenance side artifact cc3 publishes is D1's input; the **datom-lane entity→occurrence bridge is D1's stated dependency** (cc3 recorded the row-lane restriction — D1 either lands the bridge per design §2.4 or records the same restriction).
- The landed cc3 chain: `git log` on `cc3-lowering-provenance` (merged to master at landing) — the provenance side artifact outside the manifest hash, the oracle site→K binding, the caller-path threading.

## The work items (each = acceptance criterion)

1. **The D1 envelope** — `:yang.correspondence/schema 1` per design §4.1: closed key set; `:evidence` names the v1 tables VERBATIM (address, never a copy); `:recipe`; `:inputs` (retrievable closures, not commit strings); `:roots`; `:origin-bindings` (evidence-local site id → persisted K, unique within the claimed compilation, endpoints validated); `:artifacts`; `:metadata` (the C1 rows' address); `:instruction-provenance`. Content-addressed via dao.jing; no self-address field.
2. **The recipe record** — `:yang.correspondence.recipe/schema 1` per design §4.1: closed, versioned, naming the interpreter function, the exact reconstruction profile (frontend revision, grammar/options, expansion environment, recorded origins/attempts, transformations, canonicalization, site-table derivation, lowering rules), input roles, and the oracle's exact schema identity.
3. **The companion file** — `test/yang/latte_oracle/slice9_correspondence.edn` carrying the D1 record for the pinned corpus, beside (never replacing) `slice9_site_tables.edn`. The v1 file's bytes, hash, and `:version` are untouched; a test pins that.
4. **The independent reconstruction verifier** — per design §4.2's seven steps and the mob's Q1 conditions: verify addresses and schemas; verify each code image under its own execution contract; reconstruct the compilation under the exact recipe; re-derive the oracle site declarations from compiled AST argument expressions (not from the producer's serialized classifications); reconstruct every claimed binding with exact coverage and multiplicities; execute the attribution query over separately scoped relations; publish a separate verification result naming the verifier revision. **The verifier must not call the producer's derivation as its only check** (design §4.2's explicit warning about `latte_oracle_tables.clj`).
5. **The datom-lane decision** — inherit cc3's restriction: either land the entity→occurrence bridge (design §2.4) or record the restriction as D1's own; no half-state.
6. **Bounds** — declare the finite ceilings per §6.3 BEFORE the tagged verification run; the cc3 v2 bounded-run declaration (`1794390804000-cc3-bounded-run-declaration-v2.md`) is the precedent format; inherit its measured baselines and re-derive D1's own.

## Bounded file ownership (initial; the dispatch-time inventory refresh is the first deliverable)

MAY edit: `src/dev/latte_oracle_tables.clj`, `test/yang/latte_oracle_test.clj`, the new companion file + its validator, `test/yang/clojure_callsite_test.cljc`, `src/cljc/yin/vm/linker/publish.cljc` (only if a side-artifact handle is missing), and NEW files for the record/recipe/verifier under `src/cljc/yang/` (propose exact paths in findings).
MUST NOT: `slice9_site_tables.edn` (bytes frozen), `vm/macro.cljc`, `semantic.cljc`/`ast_walker.cljc` (continuation seat), `yang/clojure.cljc` (port's until it lands — check the registry at dispatch), `semantic/linearize.cljc` + `semantic/derive.cljc` (cc3's, unless the datom bridge is in-scope per item 5 and the registry is clear), `core/prelude.cljc`, `dht.cljc`. STOP and record if a fix seems to need more.

## Invariants

OracleEvidence verbatim; origins never minted; failed reconstruction refuses reliance independently of code loadability; `:trusted` is never substituted for `:verifying`; the verifier's result is a separate published record — D1 cannot mark itself verified.

## Verification (G0 laptop; suite-scale is shiva's at landing)

The round-trip test, the verifier against the pinned corpus, `yang.latte-oracle-test` affected deftests, kondo 0 errors on every touched file. The full oracle suite is shiva-scale — record, don't run.

## Deliverable

Findings per protocol; include the dispatch-time inventory refresh (design §8 step 2) as its own section: every changed contract, every read-only dependency, any stale citation found against then-current master.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
