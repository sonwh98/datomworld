Created-GMT: 2026-11-18 17:16:19 GMT
Created-Local: 2026-11-19 00:16:19 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: b6f5ac15-ed18-491a-8ac1-2e43ddfdf0eb (resumed)
# Task: yang-compilers-dedup-round2-malleability-conformance
Role: Architect (malleability conformance judgment)

I will now read [1795022027297-architect-dedup-round2-malleability-conformance.prompt.md](file:///Users/sto/workspace/datomworld/collab/exception-track/1795022027297-architect-dedup-round2-malleability-conformance.prompt.md) to understand the requirements for this review.
I will now verify whether any test references `yang.core.siteset-store` directly and capture the timestamps for the conformance report.
Completed-GMT: 2026-11-18 17:16:19 GMT
Completed-Local: 2026-11-19 00:16:19 +07

## 1. Per-Batch Malleability Evaluation (Against [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md))

### Batch 1
- **C1 (`siteset-store` 2→5 publics; `siteset-publication` 3→5 publics):**
  - **Bounded complexity — PASSED:** Both [yang.core.siteset-store](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_store.cljc) (`read-at`, `absent?`, `missing`, `load-tree`, `overlay`) and [yang.core.siteset-publication](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_publication.cljc) (`site-set-of`, `publish!`, `signed-entry`, `entry-datoms`, `signed-entries`) finish at **5 public entry points**, holding the 2–5 interface bound ([malleability.md:74](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L74)).
  - **High cohesion & loose coupling — IMPROVED:** Moving `read-at` and `absent?` into `siteset-store` eliminates the higher-order callback parameters `(store/load-tree read-at absent? handle root)` across the namespace boundary, simplifying `load-tree` to `[handle root]`. Moving `site-set-of` and `publish!` into `siteset-publication` removes the direct `[yin.vm.encoder :as encoder]` dependency from both [php/antlr/siteset.cljc:12](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc#L12) and [python/antlr/siteset.cljc:38](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc#L38) (`out` drops by 1 in each consumer), replacing duplicated target maintenance with stable architectural coupling to foundational modules (`dao.jing`, `yin.vm`, `yin.vm.encoder`, `yang.safepoint`).
- **C2 (`yang.core.module` delegates `declare-free-name`/`free-primitives`):**
  - **Loose coupling & high cohesion — IMPROVED (subject to the Batch 1/2 ordering fix in §2):** Eliminates duplicated primitive and host-export profile resolution inside `yang.core` and removes `[yin.vm :as vm]` and `[yin.vm.module :as module]` from [yang.core.module](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L13-L14), dropping its outgoing degree `out` from **5 → 3**.
- **C3 (Wire 6 missed Round-1 call sites):**
  - **High cohesion & bounded complexity — IMPROVED:** Adds **0 public functions** and **0 `:require` edges** (all four files — [php/antlr/lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc), [python/antlr/lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc), [python/antlr/prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc), and [core/call.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc) — already require the target namespaces). Finishes converging callers onto Round 1's shared contracts (`module-tree`, `build-vector`, `definitions->uast`).

### Batch 2
- **C4 (`src/clj/yang/io.clj` + `src/cljs/yang/io.cljs` → `src/cljc/yang/io.cljc`):**
  - **High cohesion & evolutionary fitness — IMPROVED:** Things that change together live together ([malleability.md:15](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L15)). Unifying the two files eliminates 4 verbatim duplicated functions while keeping the 5-function public API (`read-source`, `compile-file`, `compile-string`, `write-ast`, `compile-and-save`) at the 5-entry-point bound.
- **C5 (`own-lexer!`/`own-parser!` in `cst.clj`; `lexer-span`/`export-tree` in `yang.antlr.export`):**
  - **High cohesion & structural clustering — IMPROVED (with required visibility demotion in §4):** `own-lexer!` and `own-parser!` are private (`defn-`) in [cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj), adding 0 public API while aligning JVM with `.cljs` and `.cljd`. Lifting `lexer-span` and `export-tree` into [yang.antlr.export](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc) centralizes host-free offset and packet assembly across all 4 hosts (`clj`, `cljs`, `cljd`, `jolt`).
- **C6 (`correlation`/`rejected` → `yang.php.antlr.eval-transport`):**
  - **Bounded complexity & local cohesion — IMPROVED:** [eval_transport.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_transport.cljc) grows from 2 to **4 public functions** (`portable-data?`, `request-defect`, `correlation`, `rejected`, plus `request-fields` def — within the 2–5 bound) with `out: 0`, eliminating 6 repeated rejection-envelope constructions across [eval_admission.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_admission.cljc) and [eval_coordinator.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc) without adding any dependency edge (`eval-admission` adds 1 local edge to `eval-transport`; `eval-coordinator` already requires it).
- **P2-1 hygiene (`defn-` demotions + `core.prelude-admission` split):**
  - **Bounded complexity & interface minimization — IMPROVED:** Demoting the 6 caller-less public functions (`strip-form`, `placeholder` in `yang.core.prelude`; `evidence-record`, `evidence-datom` in `yang.core.claims`; `utf8-length`, `source-error` in `yang.antlr.packet`) directly enforces [malleability.md:82](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L82). Extracting the admission trio (`host-declaration`, `module-spec`, `admit` — 3 public entry points) into `yang.core.prelude-admission` removes `yin.vm` and `yin.vm.module` from [yang.core.prelude](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc) (`out: 4 → 2`) and drops `yang.core.prelude` from **16 → 11** public functions.

---

## 2. Ordering Hazards Between Batch 1 and Batch 2

1. **C2 (Batch 1) vs. `core.prelude-admission` split (Batch 2) — REAL ORDERING HAZARD:**
   - Executing C2 in Batch 1 points [yang.core.module/declare-free-name](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L37) at `yang.core.prelude/host-declaration`.
   - Executing the `core.prelude-admission` split in Batch 2 then moves `host-declaration` out of `yang.core.prelude` into `yang.core.prelude-admission`, forcing Batch 2 to **re-edit `yang.core.module` and `yang.core.prelude` a second time** to repoint `declare-free-name` at `prelude-admission/host-declaration` (and adding `:require [yang.core.prelude-admission ...]` to `yang.core.module`, moving its `out` degree `5 → 3 → 4`).
   - **Resolution:** Combine C2, the `yang.core.prelude` `defn-` demotions (`strip-form`, `placeholder`), and the `yang.core.prelude-admission` extraction into **one atomic unit** (either all in Batch 1 or all in Batch 2) so `yang.core.prelude` and `yang.core.module` are touched once.
2. **C1 (Batch 1) vs. `core.prelude-admission` (Batch 2) — NO HAZARD:**
   - `yang.core.siteset-store` and `yang.core.siteset-publication` do not depend on `yang.core.prelude` or `yang.core.module`; C1 is completely independent of the prelude split.
3. **Graph degree disclosure for `core.prelude-admission`:**
   - Because all 7 callers of `module-spec`/`admit` (`yang.core.call`, `clojure/prelude`, `php/antlr/prelude`, `python/antlr/prelude`, `typescript/antlr/prelude`, `python/antlr/safepoint`, `typescript/antlr/safepoint`) still require `yang.core.prelude` for `module-definitions`, `module-tree`, `exports`, and `free-name-vec`, extracting `yang.core.prelude-admission` adds `+1` outgoing `:require` edge to each of those 7 namespaces and to `yang.core.module` (+8 architectural edges to a stable `out: 2` leaf, in exchange for decoupling `yang.core.prelude` from `yin.vm` and `yin.vm.module`).

---

## 3. Conformance with the Emergence Rule and Naming Rule

- **Emergence Rule ([malleability.md:131](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L131)) — CONFORMS:**
  - Every extraction in Batch 1 and Batch 2 formalizes code already duplicated verbatim across 2 to 6 sites.
  - Keeping C6 (`eval-transport`) PHP-local and deferring C7 (`core.literals`) until `core.lower` is next extended explicitly honors *"You're just formalizing what exists, not designing from scratch."*
- **Naming Rule ([malleability.md:133](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L133)) — CONFORMS:**
  - `siteset-store`, `siteset-publication`, `prelude-admission`, `eval-transport`, `io`, `export`, and deferred `literals` are all problem-domain names that bound their responsibilities; none is a solution-domain `utils` or `helpers` bag.

---

## 4. What Is Missing from the Plan (Required in the Same Pass per `malleability.md`)

1. **In C5 (`yang.antlr.export`), demote the 3 subsumed public functions (`offset-at`, `export-nodes`, `result`) to `defn-` in the same pass:**
   - [yang.antlr.export](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc) currently exposes **7 public functions** (`byte-offsets`, `offset-at`, `token-span`, `syntax-error`, `export-nodes`, `result`, `failure-kind`).
   - Adding `lexer-span` and `export-tree` without demoting the functions they replace would grow `yang.antlr.export` from **7 → 9 public functions**, violating bounded complexity.
   - Once [cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj), [cst.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs), [cst.cljd](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd), and [cst.jolt](file:///Users/sto/workspace/datomworld/src/jolt/yang/antlr/cst.jolt) call `lexer-span` and `export-tree`, `offset-at`, `export-nodes`, and `result` have **zero external callers** in `src/` or `test/`. Marking those 3 functions `defn-` in C5 **shrinks** `yang.antlr.export`'s public surface from **7 → 6** (`byte-offsets`, `token-span`, `lexer-span`, `syntax-error`, `export-tree`, `failure-kind`) instead of growing it to 9.
2. **In C1 (`yang.core.siteset-store`), replace (do not overload) the 4-argument `load-tree` signature:**
   - No test calls `yang.core.siteset-store/load-tree` directly; only `php/antlr/siteset` and `python/antlr/siteset` call it. In C1, change `load-tree` directly from `[read-at absent? handle root]` to `[handle root]` rather than retaining a dead 4-argument arity.
3. **Execute C2 + `core.prelude` `defn-` hygiene + `core.prelude-admission` split as one unit:**
   - Per [malleability.md:114](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L114) (*"Extract them together to keep related changes local"*), do not split C2 across Batch 1 and Batch 2.

CONFORMANCE VERDICT: ALIGNS WITH AMENDMENTS — Merge C2 and the `core.prelude-admission` split into one atomic unit to avoid re-wiring `yang.core.module` twice, drop the dead 4-arg `load-tree` arity in C1, and mark the 3 subsumed functions (`offset-at`, `export-nodes`, `result`) `defn-` in C5 so `yang.antlr.export` shrinks from 7 to 6 public functions instead of growing to 9.
