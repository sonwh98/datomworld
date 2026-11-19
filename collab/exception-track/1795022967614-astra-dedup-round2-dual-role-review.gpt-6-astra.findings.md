Created-GMT: 2026-11-18 17:33:39 GMT
Created-Local: 2026-11-19 00:33:39 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1efb5-8c32-7113-9f8c-631c443ccb0f
# Task: yang-compilers-dedup-round2-dual-role-review
Role: Lead System Architect + Yin.VM Runtime Engineer (dual-lens review)

Completed-GMT: 2026-11-18 17:33:39 GMT
Completed-Local: 2026-11-19 00:33:39 +07

**Lead System Architect — findings**

Severity | File:line | Evidence | Correction
---|---|---|---
P2 | [Round-2 audit:333](/Users/sto/workspace/datomworld/collab/exception-track/1795020669393-architect-yang-dedup-round2-audit.gemini-4-argon-high.findings.md:333); [cst.clj:127](/Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj:127) | C5 proposes `export-tree [profile unit source errors ops-fn tree]`. Callers already compute code points and offsets before parsing because error listeners need them. Jolt also needs offsets to convert diagnostic records. Passing only `source` requires the exporter to repeat this source-sized computation and allocation on successful parses. | Pass the already-computed code points and offsets, directly or in one context map. Preserve the existing error-first behavior: a syntax-error result must not walk the recovered tree. Keep host-specific `ops` construction in the host files.

**Yin.VM Runtime Engineer — findings**

Severity | File:line | Evidence | Correction
---|---|---|---
P2 | [Review prompt:79](/Users/sto/workspace/datomworld/collab/exception-track/1795022967614-astra-dedup-round2-dual-role-review.prompt.md:79); [conformance.clj:23](/Users/sto/workspace/datomworld/test/yang/call/conformance.clj:23); [siteset_test.cljc:86](/Users/sto/workspace/datomworld/test/yang/python/antlr/siteset_test.cljc:86) | The four backends do not exercise identical admission paths. The linked conformance harness explicitly covers semantic/register/stack and excludes the AST walker; separate prelude parity tests cover all four. Also, existing site-address tests compare two publications produced by the same implementation, so a deterministic address change could pass. The amended plan supplies no explicit validation gate resolving these distinctions. | Require separate four-backend prelude parity and three-backend linked-module checks, plus before/after fixtures for canonical rows, site-set bytes, record bytes and addresses. Compare refusal messages and `ex-data` exactly. This is a validation-plan gap, not an observed runtime regression.

No P0/P1 defect found.

**Architect properties that passed**

**A1 — Layering and dependency direction.** The proposed admission namespace needs exactly:

```clojure
(:require [yin.vm :as vm]
          [yin.vm.module :as module])
```

Move the private `refusal` helper with the admission trio. None of these functions needs `core.prelude`, `core.module`, a target namespace, or a backend evaluator.

The eight added consumer edges are correctly identified: `core.module`, `core.call`, the Clojure/PHP/Python/TypeScript preludes, and Python/TypeScript safepoints. Final direct outgoing counts are:

| Namespace | Final direct dependencies |
|---|---|
| `core.prelude-admission` | `yin.vm`, `yin.vm.module` — 2 |
| `core.prelude` | `clojure.walk`, `core.uast` — 2 |
| `core.module` | `clojure.string`, `core.uast`, `core.prelude`, `core.prelude-admission` — 4 |
| `core.siteset-store` | `dao.jing`, `yin.vm` — 2 |
| `core.siteset-publication` | `dao.jing`, `yin.vm`, `yin.vm.encoder`, `yin.vm.linker.sign`, `yang.safepoint` — 5 |

A read-only static model of these edges found no reachable namespace cycle or target-specific dependency from the moved core seams.

Two accounting qualifications matter:

- Removing `core.prelude`’s direct VM requires does **not** eliminate its transitive VM dependency: [core.uast:20](/Users/sto/workspace/datomworld/src/cljc/yang/core/uast.cljc:20) still requires `yin.vm`.
- The eight consumer edges are gross additions. Including four removed edges and the new namespace’s two foundational edges, C2 plus the split adds **six net edges**. C6 separately adds one `eval-admission → eval-transport` edge.

These are acceptable architectural dependencies; reduced edge count alone is not the justification.

**A2 — Invariants.** All six requested invariants remain satisfied by the extraction boundaries:

- **No hidden globals:** registries, handles, medium identity, revisions and profiles remain explicit inputs.
- **No implicit control flow:** admission refusals, missing-address reports and publication order retain their existing contracts.
- **No application callbacks:** C1 removes the `load-tree` function parameters. Existing store operations and CST operations remain local implementation functions; none becomes a callback carried across a stream or invoked by portable guest code.
- **No shared mutable state:** `overlay` is acceptable under its current ownership. Its atom is shared by closures belonging to **one overlay**, but each staging invocation creates and synchronously consumes a fresh overlay. See [Python stage:469](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc:469) and [PHP stage:291](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc:291). Local allocation alone would not prove safety if the handle escaped to concurrent owners; current callers provide the necessary ownership evidence.
- **No layer collapsing:** projection, declaration construction and publication do not execute guest programs. C4 remains host-local file I/O; moving it into `.cljc` does not make it portable runtime I/O.
- **No assumed graphs:** tree edges come from grammar slots, module dependencies from explicit requirement maps, and CST edges from exported child references.

The master architecture also now states **information preservation** as a seventh invariant. These extractions introduce no deletion of canonical ASTs, provenance records or retained program data.

**A3 — Atomic C2 plus split.** Sound. This is one coherent boundary change: pure emission remains in `core.prelude`; registry resolution and admission move together. Atomic implementation avoids an intermediate public forwarding API and repeated rewiring.

The final call must resolve to **`prelude-admission/host-declaration`**, not the old namespace’s var. The plan’s `prelude/host-declaration` wording should be clarified accordingly. No separate batch or further architectural review is needed for that boundary.

**A4 — Naming and governance.** `yang.core.prelude-admission` is coherent with the existing prelude-spec and host-admission responsibilities. Document that it constructs declarations/specifications and checks registries; it does not publish, link or execute modules.

The proposed public counts are consistent. `core.prelude` at 11 and `antlr.export` at 6 remain above the preferred 2–5 range, but that preference does not justify another artificial split. Keeping C6 PHP-owned, retaining host-specific CST implementations and deferring C7 are appropriate.

**VM properties that passed**

**V1 — Grammar traversal and CESK correspondence.** [semantic-bytecode-grammar:1039](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:1039) is the canonical AST row-slot table, explicitly distinguished from executable bytecode. `load-tree` follows only `:node` and `:nodes` slots and reconstructs addressed rows; it changes no CESK transition, frame, environment or continuation representation.

Moving the reader preserves that result provided it retains:

- Absent bytes → the shared sentinel → `{:status :missing, :address a}`.
- Invalid address or mismatched bytes → `nil`.
- Existing traversal order and first-missing selection.

Move every sentinel comparison with the reader: the auto-qualified `::absent` keyword changes namespace. This is a local sentinel change, not a wire-format change. Loading also remains distinct from subsequent row validation.

**V2 — Admission semantics.** The duplicated branches match: primitive profiles take precedence; otherwise host-export profiles supply the address, and effects are looked up using the resolved callable with `#{}` as the default.

Keep `core.module`’s covered-module check **before** delegation. Keep its throw locally:

```clojure
(ex-info "the module reads an undeclared name"
         {refusal-key refusal, :name n})
```

The key is the **value of `refusal-key`**, not the literal keyword `:refusal-key`. Delegating only resolution preserves this refusal exactly. Do not replace it with the prelude admission namespace’s differently constructed refusal vocabulary.

`admit` must retain its existing `resolve-module` checks, sorted missing-name report and return of the supplied registry. It should not be rewritten to use `host-declaration`.

**V3 — Backend parity and continuation portability.** No extraction introduces backend-specific dispatch or changes continuation serialization. C3’s wide applications preserve operand order and parameter names; vector construction preserves left-to-right evaluation; definition emission preserves the linear tree and tail marking. Do not substitute the balanced emitter.

Thus the source comparison supports semantic preservation, subject to the distinct validation paths identified above. It does not establish new linked-AST support or prove arbitrary cross-backend continuation migration.

**V4 — Content addressing.** Passing the existing medium keyword explicitly is safe. Preserve each target’s medium, `[:site-set 0]` batch token, selected root, revision, profile address and canonical site ordering.

The published record remains the same five `:yang.site/*` fields. Medium identity participates in occurrence selection; it is not an additional record field. The helper’s returned `:tree` likewise must not enter the serialized record. Set-before-record materialization and signing inputs remain unchanged.

**Combined disposition — Z1/Z2**

Dispatch with the two P2 amendments above. Keep the existing batch order and C2/split atomicity.

Add concrete acceptance checks before implementation:

1. Capture baseline rows and publication bytes/addresses before Batch 1; compare after extraction.
2. Check primitive precedence, covered names, host effects, exact refusals, missing versus corrupt rows, and no buffered writes on unsuccessful staging.
3. Run four-backend prelude parity separately from linked semantic/register/stack checks.
4. For Batch 2, verify all four CST hosts, including Unicode spans, synthetic tokens and syntax-error packets; verify JVM/Node I/O branches and PHP rejection-envelope equality. Preserve the special sanitization of invalid transport requests at [eval_coordinator.cljc:46](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc:46).

Review was read-only. I inspected the requested documents, round-1 commit statistics, source contracts and relevant tests; no files were edited and no runtime tests were executed.

DUAL-ROLE VERDICT: DISPATCH WITH AMENDMENTS — Reuse prepared source offsets in C5 and add explicit address-preservation and separate AST/linked-VM validation gates.