Created-GMT: 2026-11-18 17:29:27 GMT
Created-Local: 2026-11-19 00:29:27 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-compilers-dedup-round2-dual-role-review
Role: Lead System Architect + Yin.VM Runtime Engineer (dual-lens review)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-19 00:29:27 +0700 | Status: active | Rationale: owner-directed dual-role review; astra is independent of both the argon audit family and the codex terra/sol implement/review family

Review the AMENDED round-2 deduplication plan for the yang.* compilers, in TWO roles:
1. **As Lead System Architect** (docs/agents/roles/architect.md): foundational invariants
   (the 6 non-negotiables), subsystem boundaries, layering, design contradictions,
   malleability governance coherence.
2. **As Yin.VM Runtime Engineer** (docs/agents/roles/vm-engineer.md): CESK machine
   integrity, execution parity across the four backends, continuation portability, and the
   runtime coupling the plan moves.

Read-only. Do not edit files. Your final response is the deliverable.

## Read first

- `docs/design/datom.world.md` — master architecture, non-negotiable invariants.
- `docs/agents/malleability.md` — governance criteria.
- `docs/agents/roles/architect.md` and `docs/agents/roles/vm-engineer.md` — the two role
  definitions whose lenses you wear.
- `collab/exception-track/1795020669393-architect-yang-dedup-round2-audit.gemini-4-argon-high.findings.md`
  — the round-2 audit (7 clusters C1-C7, leave-alone rulings, P2-1 split judgments).
- `collab/exception-track/1795022027297-architect-dedup-round2-malleability-conformance.gemini-4-argon-high.findings.md`
  — the conformance verdict (ALIGNS WITH AMENDMENTS: C2+prelude-split atomic; C1 replaces
  the 4-arg load-tree; C5 demotes offset-at/export-nodes/result).
- The landed round-1 state: commit 1604aabe (git show --stat) — the five extractions,
  `yang.core.siteset-store` (requires `yin.vm` for `vm/semantic-bytecode-grammar`),
  `yang.core.siteset-publication` (requires `yin.vm.linker.sign`).

## The amended plan under review

**Batch 1:** C1 (lift `read-at`/`absent?`/`missing` → `siteset-store`, REPLACE its
`load-tree` signature with `[handle root]`; lift `site-set-of`/`publish!` →
`siteset-publication`; both namespaces land at exactly 5 publics; consumers drop their
`yin.vm.encoder` requires) + C3 (6 unwired call sites → landed helpers, zero new API) +
**C2-and-the-prelude-split as ONE atomic unit** (`core.module` delegates to
`prelude/host-declaration`; the admission trio `host-declaration`/`module-spec`/`admit`
extracts to NEW `yang.core.prelude-admission` — 3 publics — removing `yin.vm` +
`yin.vm.module` from `core.prelude`; the 6 caller-less publics → `defn-`; disclosed
tradeoff: +1 require edge to each of the 7 consumers of module-spec/admit).

**Batch 2:** C4 (`io.clj`+`io.cljs` → `io.cljc` reader conditionals) + C5 (`own-lexer!`/
`own-parser!` in cst.clj; `lexer-span`/`export-tree` → `yang.antlr.export`; demote
`offset-at`/`export-nodes`/`result` → export shrinks 7→6 publics) + C6 (`correlation`/
`rejected` → `yang.php.antlr.eval-transport`, stays php-owned) + remaining `defn-` hygiene.

**Deferred:** C7 (`core.literals` extraction) until `core.lower` is next extended.

## Questions (answer per role, then combined)

**Architect lens:**
A1. Do the moved edges preserve the layered architecture (core → foundation only, no
    target requires in core, no cycles)? Check the NEW `yang.core.prelude-admission`'s
    requires and the +8 consumer edges disclosed in the conformance verdict.
A2. Do the 6 invariants (no hidden global state, no implicit control flow, no raw
    callbacks, no shared mutable state, no layer collapsing, no assumed graphs) hold for
    every extraction? (Note `siteset-store/overlay` mutates a LOCAL atom per invocation —
    is that still "no shared mutable state"?)
A3. Is the atomic-unit merge (C2+split) architecturally sound, or does bundling hide a
    boundary decision that deserves its own review?
A4. Naming and boundary coherence of the NEW namespace `yang.core.prelude-admission`.

**VM-engineer lens:**
V1. `siteset-store` consumes `vm/semantic-bytecode-grammar` for tree traversal; the C1
    change moves `read-at`/`absent?` INTO that namespace. Does anything about grammar-slot
    traversal or the `{:status :missing, :address a}` shape touch CESK correspondence or
    the semantic→AST lowering parity?
V2. `core.module` dropping its `yin.vm`/`yin.vm.module` requires (C2) and `core.prelude`
    dropping them (split): is admission (primitive profiles, host-export profiles,
    callable-effects) semantics-preserving when routed through the shared
    `host-declaration`? The refusal shape `{:refusal-key refusal, :name n}` for
    "the module reads an undeclared name" — does delegation preserve it exactly?
V3. Execution parity: all four backends (semantic/AST/register/stack) consume these
    prelude/module admission paths. Does any extraction risk backend divergence?
V4. Content-address sensitivity: `site-set-of`/`publish!` construct `:yang.site/*` records
    whose bytes are content-addressed. The C1 parameterization passes `medium` identity
    in. Any risk the record bytes change?

**Combined:**
Z1. Would you dispatch this plan as-is? Any defect severity [P0/P1/P2] per finding?
Z2. Anything the plan should ADD or REORDER?

## Report format (mandatory)

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then findings per role with severity | file:line | evidence | correction, then the
properties that passed, ending with exactly one line:
DUAL-ROLE VERDICT: DISPATCH AS-IS | DISPATCH WITH AMENDMENTS — <one line> | HOLD — <why>
