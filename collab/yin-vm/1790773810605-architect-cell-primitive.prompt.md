Created-GMT: 2026-09-30 13:10:10 GMT
Created-Local: 2026-09-30 20:10:10 +0700
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
# Task: A mutable cell primitive for assignment to captured locals (ANTLR frontends)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-30 20:10:10 +0700 | Status: active | Rationale: owner directive 2026-09-30 "use fable more ... as the architect"; design question, no code under review

Perform a read-only architecture design review. Do not edit files. Work in /Users/sto/workspace/datomworld.

OWNER (verbatim): "ask the architect about the cell primitive".
Owner framing earlier in the thread (verbatim): "integrating antlr should be straight forward. antlr will construct an AST
that gets mapped to yin.vm universal AST." and "if yin.vm universal AST has continuations, all control flow can be mapped
to continuations". Captured continuations became invocable today (branch vm-continuation-invoke, commit 8f9f90b0,
abortive, multi-shot, store NOT rolled back, on all four VMs), so return/break/continue/loops/try/raise/generators lower to
continuation capture+invoke with no new tags.

Orchestrator's analysis to challenge (not authority):
- Assignment to globals/module variables lowers to (yin/def x v): literal key, evaluated value, writes the active store.
- Assigned locals NOT captured by any closure lower to lambda rebinding (SSA-style; loop-carried vars become parameters of
  the loop's recursive lambda). No store involved.
- Locals that are BOTH mutated AND captured (Python nonlocal, JS closures over let, PHP use (&$x)) need a per-activation
  mutable cell ("assignment conversion"). Nothing expresses that today:
  * yin/def: key must be a literal non-reserved symbol (src/cljc/yin/vm.cljc:161-182, Rule R); two activations share it.
  * :vm/store-put: key literal, value slot is data, not evaluated (docs/design/yin.vm.code-as-tuples.md:312).
  * :vm/gensym mints a runtime id (ast_walker.cljc:~495) but no store operation accepts a runtime key.
  * Dependency completion extracts a static store-slice requirement from literal keys: "every definition key is literal"
    (code-as-tuples.md:1372-1381; docs/design/yin.vm.dependency-completion.md).

Read first:
- docs/design/datom.world.md (axioms, six invariants, "Derive, don't persist")
- docs/design/yin.vm.code-as-tuples.md §2.3 table, §2.6, §7 requirement table ~1360-1390
- docs/design/yin.vm.dependency-completion.md
- docs/design/yin.vm.linker.md §7.3 (module stores; ast_walker.cljc:~498 routes module-closure store nodes)
- docs/design/yang.antlr.md §8.1 (state threading vs store cells tension), §9.4
- collab/1790345200000-architect-yin-def-rule-r-final.claude-fable-5-1.findings.md (your Rule R ruling: yin/def is an
  unshadowable special form)
- src/cljc/yin/vm/engine.cljc (put-active, env-store-of, resolve-var, authentic-ref?), src/cljc/yin/vm/ast_walker.cljc,
  src/cljc/yin/vm/docs/co-routines.md
- src/cljc/yin/vm/docs/streams? only if you consider streams as cells.

Questions (rule on each; mark owner decisions):
Q1. Is a cell primitive needed at all, or can captured-mutable locals be expressed with what exists (e.g. threading an
    explicit heap value, streams as cells, closures over a store key derived from a gensym'd prefix, CPS with state)?
    Weigh cost to the lowering and to evaluation on all four VMs.
Q2. If needed, what shape: (a) a new tag trio (make/get/set with evaluated key and value), (b) extend yin/def or
    :vm/store-put to accept a runtime key, (c) cells as ordinary values (a ref value like stream refs, with
    authenticity), (d) other. Must not weaken Rule R or yin/def unshadowability.
Q3. Store-slice / dependency completion: how does a runtime-keyed cell coexist with static requirement extraction and
    content addressing? Is a cell part of the store, of a separate heap, or of the continuation? What happens on
    park/migration/serialization of a continuation that references a cell (engine.cljc non-portable! on reified
    continuations today)?
Q4. Semantics with multi-shot continuations: cells are shared across re-entries (Scheme box semantics) since the store is
    not rolled back. Confirm or correct; note generator and exception lowering consequences.
Q5. Interaction with module stores (linker §7.3), peer observers (yin.vm and dao.space observing one stream), and the
    "no hidden global state / no shared mutable state" invariants.
Q6. Minimal first slice to unblock a Python/PHP ANTLR spike, and what can wait.

Evaluate foundational invariants, ownership boundaries, explicit state and control flow, CLJ/CLJS/CLJD portability,
four-VM parity, and design contradictions. Distinguish architectural defects from implementation gaps.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c
Then: a recommendation (one design), answers Q1-Q6, owner decisions listed separately, and any
severity | file:line | invariant/evidence | recommended correction items.
