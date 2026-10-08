Created-GMT: 2026-09-13 05:33:04 GMT
Created-Local: 2026-09-13 12:33:04 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61 (resumed)

# Task: Architecture & Invariant Review of Revised Macro Architecture (Macro Expansion as a Stream Process)
Role: Routine Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 12:33:04 +0700 | Status: active | Rationale: Independent routine & architecture review per team.md; high precision on invariants, failure modes, and stream protocol semantics.

**Read-only architecture review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target Document Under Review
- `docs/design/yin.vm.macro.md` (692 lines, revised 2026-09-13).

## Context & Architectural Pivot
In round 1 (`collab/1789272850107-review-macro-system-v2.gpt-6-astra.findings.md`), you reviewed an evaluator-integrated macro design where macro expansion happened inside the evaluators (`ast-walker`, `semantic`, `stack`), with opcode 24, `:macro-call`, ephemeral segments, VM ledger `:macro-expansions`, inlined hot-loop Twin sites, and `:macro-authorize`.

The author has completely restructured the macro design around a foundational architectural axiom:
> "Every `yin.vm` evaluator is an observer of a `dao.stream` medium. The writer to that medium is just another process. Macro expansion is such a process."

Key architectural decisions in this revision:
1. **Evaluators know nothing about macros** (Decision 1): No evaluator has a macro transition, opcode 24, `:macro-call` instruction, closure flags, hot-loop hooks, or expansion ledger. Evaluators load only pure semantic Universal AST datoms.
2. **Expansion is a stream forwarder between two media** (Decision 2 & §5):
   `yang ──▶ program-in ──observe──▶ expander ──append──▶ program-out ──observe──▶ evaluator`
                                        `│`
                                        `└──append──▶ log (events, m-tagged output)`
   The expander is driven by `stream-observer/run-on-stream`.
3. **No Compile/Runtime Phase** (Decision 3): `:yin/phase-policy` and `:yin/phase` are retired.
4. **Call sites are ordinary applications** (Decision 4): `:yin/macro-expand` is retired from Universal AST.
5. **Definitions never reach an evaluator** (Decision 5): Macro definitions are syntactically harvested into `:store` and replaced with literal name symbols.
6. **Macros are closed syntax transformers** (Decision 6): Throwaway AST-walker sandbox under a fuel budget (`:max-steps 100,000`).
7. **Dual guards & tail marking** (Decisions 6-7, §3.4, §3.5): Depth (100), nodes per expansion (10,000), recursive plain-data validation, and an expander-side `mark-tail` pass.
8. **Self-contained output batches** (Decision 8 & §4.2): Unchanged operands/ancestors are copied with fresh entity IDs, not cross-referenced unless durable.
9. **Provenance on a third (log) medium** (Decision 9 & §4.1): Events and `m`-tagged datoms are routed to `log`, keeping `program-out` pristine.
10. **Authority is write authority on the media** (Decision 10 & §4.3).

## Review Checklist
Evaluate the revised document across these critical areas:

1. **Foundational Invariants & Layering**:
   - Does this stream-process design strictly satisfy *No Layer Collapsing*, *No Hidden Global State*, and *No Shared Mutable State*?
   - Is it strictly superior to the evaluator-integrated design regarding VM simplicity, verification surface, and failure boundaries?
2. **Stream Forwarder Semantics & Coordination (§5)**:
   - Does `expander = {:ctx ctx :out writer :log writer|nil :staged nil}` with `ready?`, `load-program`, and `run-vm` correctly integrate with `yin.vm.stream-observer/run-on-stream`?
   - Trace the retry behavior on `full` (staged batch retained, `ready?` stays false) and on throwing exceptions. Are there any edge cases where batches could be duplicated, dropped, or reordered?
3. **Expander Algorithm, Shadowing & Fixpoint (§3.1, §3.2)**:
   - Is the outermost-first traversal sound for macro hygiene and nested expansions?
   - Is lexical shadowing via enclosing `:lambda :params` sufficient to retire frontend shadow hints without leaking macro expansion into local variables?
   - Does the replacement of definition nodes with `{:type :literal :value <sym>}` preserve valid batch roots and top-level expressions?
4. **Throwaway Sandbox Evaluation & Guards (§3.3, §3.5)**:
   - Evaluate `run-bounded` using `ast-walker/vm-load-program` and step-counting loop.
   - Is checking `:parked`, `:ready-queue`, and `:blocked?` sufficient to reject side effects, async operations, and hanging bodies?
   - Evaluate `valid-ast?`: does the closed per-node vocabulary and recursive plain-data check adequately safeguard against host leakage and corrupted AST shapes?
5. **Tail Marking & Provenance (§3.4, §4.1, §4.2)**:
   - Is the `mark-tail` pass over expansion output sufficient to preserve O(1) continuation stack depth on the semantic VM and stack VM?
   - Does the separation between `program-out` and the `log` medium resolve cross-media identity issues without requiring tempid resolution across uncommitted streams?
6. **Roadmap & Appendix B Risks (§7, Appendix B)**:
   - Are the four implementation phases and test fixtures realistic, complete, and properly sequenced?
   - Are all risks identified in Appendix B soundly addressed?

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra
```
Then classify findings by severity: `[P1 — blocking]`, `[P2 — must address]`, `[P3 — suggestion/alignment]`, or explicitly state approval if ready.
