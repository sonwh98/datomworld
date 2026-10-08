Created-GMT: 2026-09-12 14:17:22 GMT
Created-Local: 2026-09-12 21:17:22 +0700 (+07)
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3

# Task: Unified Compile-Time & Runtime Macro Architecture on Yin VM v2
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-12 21:17:22 +0700 | Status: active | Rationale: Lead System Architect per team.md; authored the semantic VM v2 specification, storage cutover, and query/transactor plans.

**Architecture and Design Task. Plan and specify the unified macro system for `yin.vm`.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.
Write your complete architectural specification and findings to:
`collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md`.
Change no other repository files.

---

## 1. The Problem & Context

In v1, user-defined macros were executed by the experimental VM models (`semantic`, `register`, `stack`, `space`) using `src/cljc/yin/vm/macro.cljc`. When those experimental backends were cleaned up in commit `d8b27a5`, the macro engine was deleted alongside them because `yin.vm.ast-walker` had no macro-expand branch, leaving the v2 corpus "macro-free by construction" (`yin.vm.divergence-register.md`).

However, two foundational design documents exist in the repository:
1. `docs/design/macro-design.md`: Unified compile-time and runtime macros operating on canonical datom streams, with explicit phase policy (`:compile | :runtime | :both`), expansion events, provenance (`m = <event-eid>`), and recursion guards.
2. `docs/cross-language-macro.md`: Cross-language macro execution strategy enabling homoiconic macro definitions in Clojure (`yang.clojure`) to be invoked seamlessly from non-homoiconic languages like Python (`yang.python`) and PHP via Universal AST argument normalization.

With the return of the **Semantic VM on DaoStream v2** (specified in `collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`) and the multi-evaluator architecture (`ast-walker`, `semantic`, and future `stack`), we now need a unified, comprehensive architectural design to re-introduce macros into `yin.vm`.

The system must support:
- **Compile-time macros**: Pure stream/AST expansion producing 100% macro-free code before execution or lowering, providing zero runtime overhead for all evaluators (`ast-walker`, `semantic`, and `stack`).
- **Runtime macros**: Explicit, capability-governed macro expansion executed at runtime across all evaluators (`ast-walker`, `semantic`, and `stack`).
- **Cross-language interoperability**: Macro authoring in Clojure callable from Python/PHP through canonical Universal AST normalization.

---

## 2. Read First

- `docs/design/datom.world.md` — Foundational axioms and the 6 non-negotiable invariants (no hidden globals, no implicit control flow, no callbacks, no shared mutable state, no layer collapsing, no assumed graphs).
- `docs/design/macro-design.md` — Canonical macro model v1 (phase policies, `:yin/macro-expand`, expansion events, provenance `m`, hygiene, guards).
- `docs/cross-language-macro.md` — Cross-language macro execution strategy (discovery, Universal AST argument normalization, multi-phase pipeline).
- `docs/design/dao.stream.md` — DaoStream v2 specification (opaque cursors, non-blocking outcomes, stream transducers).
- `collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md` — The brand-new specification for `yin.vm.semantic` over linear code segments.
- `src/cljc/yin/vm/ast_walker.cljc` — The current AST walker evaluator.
- `docs/design/yin.vm.divergence-register.md` — Record of macro divergence in v2 (§2).
- `src/cljc/yang/clojure.cljc` and `src/cljc/yang/python.cljc` — Compiler frontends emitting AST forms.

---

## 3. Required Architectural Specification

Produce a rigorous, end-to-end architectural specification covering:

### §1. Foundational Axioms & Invariant Governance
- How the macro system adheres to:
  1. *Interpretation Creates Semantics*: Macros are AST/datom-to-AST/datom transformation functions; the expander is an explicit interpreter of `:yin/macro-expand` forms.
  2. *Code and State are Datoms*: Macro calls, expansion events, and expanded outputs are immutable datoms.
  3. *Everything is a Stream*: Compile-time expansion as a stream-transducer pass; runtime expansion as explicit boundary effects.
- Compliance with the 6 non-negotiable invariants (especially *No Layer Collapsing* between expansion and execution, and *No Shared Mutable State*).

### §2. Macro Representation & Language Integration
- Define the canonical macro representation:
  - Lambda entity with `:yin/macro? true` and `:yin/phase-policy (:compile | :runtime | :both)`.
  - Expansion call node: `{:type :yin/macro-expand, :operator <macro-ast>, :operands [<ast-nodes>]}`.
- Cross-Language Frontends:
  - How `yang.clojure` defines macros (`defmacro`) and emits macro definitions.
  - How non-homoiconic frontends (`yang.python`, `yang.php`) discover macros and normalize arguments into canonical Universal AST shapes before emitting `:yin/macro-expand`.
  - Local vs. cross-unit macro discovery mechanisms.

### §3. The Compile-Time Macro Expander (`yin.vm.macro`)
- Specification of the compile-time expansion pipeline:
  - Input: AST datom stream containing `:yin/macro-expand` nodes.
  - Execution mechanism: How does the expander execute the macro lambda? (Using a lightweight Yin VM evaluation instance).
  - Fixpoint expansion algorithm: Recursive expansion until no `:yin/macro-expand` nodes remain.
  - Recursion & safety guards: Depth limits (default 100) and max emitted datom limits (default 10,000).
  - Output: 100% macro-free Universal AST datom stream.
  - How the macro-free output cleanly feeds:
    1. `yin.vm.ast-walker` (direct evaluation of macro-free AST).
    2. `yin.vm.linearize` → `yin.vm.semantic` (lowering macro-free AST to linear `:yin.code/*` segments).
    3. `yin.vm.stack` (compiling macro-free AST to stack bytecode).

### §4. Runtime Macro Execution Semantics Across Evaluators
- For macros with `:phase-policy :runtime` or `:both`, specify the exact execution mechanics for each v2 evaluator:
  1. **`ast_walker.cljc`**:
     - Addition of `:yin/macro-expand` in `step-eval-node`.
     - CESK state transition: resolving the macro function, evaluating unevaluated AST operands, producing the expanded AST node, and returning `(cesk-return state expanded-ast env k val)`.
  2. **`yin.vm.semantic` (Linear Code Segments)**:
     - How runtime macros are represented in linear datoms (e.g. `:yin.code/op :macro-call`).
     - Dynamic lowering: How runtime expansion evaluates the macro, lowers the expanded AST into an ephemeral code segment via `yin.vm.linearize`, and links/splices execution via continuation frame return.
  3. **`yin.vm.stack` (Stack Bytecode VM)**:
     - Opcode representation (`OP_MACRO_EXPAND`) and boundary compilation/execution.

### §5. Provenance, Causality & Capability Security
- Non-destructive expansion: Original call datoms remain immutable and persistent.
- Expansion event entity (`:yin/type :macro-expand-event`):
  - Attributes: `:yin/source-call`, `:yin/macro`, `:yin/phase`, `:yin/expansion-root`, `:yin/timestamp`.
  - Metadata linking: All expanded datoms set transaction metadata slot `m = <expansion-event-eid>`.
- Security & Authority:
  - Compile-time: Permitted in trusted build contexts.
  - Runtime: Requires Shibi capability token scoped to `macro/expand`, target program root, and allowed namespace.

### §6. Coexistence & Migration
- Updating `docs/design/yin.vm.divergence-register.md` to transition macros from "deleted" to "v2 unified stream model".
- REPL integration: How `yin.repl` handles `defmacro` and macro expansion.
- Cross-platform portability across JVM (CLJ), Node.js/Browser (CLJS), and Dart/Flutter (CLJD).

### §7. Phased Implementation Roadmap
- Concrete phases (Phase 0: Spec & Contract, Phase 1: Compile-Time Expander, Phase 2: AST-Walker Runtime Integration, Phase 3: Semantic VM Runtime Integration, Phase 4: Cross-Language Verification & Tests).
- Specific deliverables, target files, and acceptance criteria.

---

## 4. Output Formatting Rules

Begin your output in `collab/1789222642509-architect-macro-system-v2-design.claude-fable-5-1.findings.md` exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3
```
Follow with the structured architectural specification, invariant proofs, and implementation plan.
