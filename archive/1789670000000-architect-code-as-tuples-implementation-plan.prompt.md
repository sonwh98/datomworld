Created-GMT: 2026-09-17 15:33:20 GMT
Created-Local: 2026-09-17 22:33:20 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: a916698e-a0c1-4859-8bcb-bbebfb2ec3a5
Role: Lead System Architect

# Task: Draft docs/design/yin.vm.code-as-tuples.implementation-plan.md

`docs/design/yin.vm.code-as-tuples.md` (2094 lines) proposes a flat-row
tuple grammar for the AST/code media that `yin.vm` currently represents
as map ASTs and instruction vectors. Its own §10 ("Acceptance blockers and
open items") lists 14 numbered items, most explicitly marked "design work
with stated mechanisms, not yet implementable" or "no design answer in
this document." The orchestrator surveyed the codebase and confirmed: no
map↔rows tuple codec exists anywhere in `src/`, no `yin.vm/macro.cljc`
exists (item 4's own text already says so), and the existing `linearize.cljc`
`lower`/`lower-ast` functions serve the *current* map-AST/instruction-vector
path, not this design's proposed row grammar. This is essentially all
unbuilt.

Read `docs/design/yin.vm.code-as-tuples.md` in full, especially §2 (the
tuple grammar), §5.2.1 (the lowering profile), §6.5 (the codec boundary),
§7 (the VM around it, especially §7.2's conformance obligation and
§7.7.1-7.7.3's effect/dependency analysis), §8.4-8.5 (occurrence identity
and macro batch preservation), §9 (migration surface), and §10 in full.
Then read `docs/design/yin.vm.macro.md` (1321 lines — the expander this
design's macro-batch item builds on), `docs/design/yin.vm.universal-continuation-format.md`
(1331 lines — UCF, which this document amends in several places and
explicitly cannot edit directly), and `docs/design/yin.vm.semantic.md`
(748 lines — whose §5.3 instruction table this design's lowering profile
must pin). Survey the current implementation:
`src/cljc/yin/vm/{ast_walker,code,engine,ffi,linearize,module,
runtime_adapter,semantic,telemetry}.cljc`, and confirm or correct the
orchestrator's claim that no macro expander and no row codec exist yet.

## Task

Produce `docs/design/yin.vm.code-as-tuples.implementation-plan.md`,
following the structure of tonight's retirement plans (a census of what
exists vs. what's missing, phased units ordered by real dependency, an
explicit split between "design work still owed" and "implementable now,"
owner-decision points flagged where you can't decide unilaterally).
Concretely:

1. **Classify all 14 §10 items** by what's actually blocking what. Several
   depend on decisions this document explicitly says it cannot make
   itself (item 8: "This document cannot edit UCF"; item 3: "the medium
   identity coordinate... this document does not fix its shape, and that
   shape must be fixed before an origin can be written"). Identify which
   items have a genuinely fixable design gap you can close in this plan
   itself, versus which need a decision from `yin.vm.macro.md`/UCF/the
   owner before any code can start.
2. **Find the true bottom of the dependency graph** — what is the
   smallest, most self-contained piece of code that could be built first
   with no further design decisions, if anything qualifies. The document
   claims item 13 (the round-trip law) is "testable now" because the map-
   AST corpus exists — verify whether that's actually true given no row
   codec exists yet, or whether it's testable only once §2/§6.5's codec
   is built, in which case building *that* codec is the true first
   buildable unit and the round-trip test is its own acceptance criterion,
   not a standalone starting point.
3. **Estimate real scope and risk per phase**, the way tonight's
   `dao.stream.v1-retirement.implementation-plan.md` did for its two real
   design units — don't understate how much of this is genuinely
   speculative/unbuilt architecture versus mechanical implementation of
   an already-settled design.
4. **State plainly whether any part of this can start immediately**, and
   if the honest answer is "no, several prerequisite design decisions
   must be made first (and name exactly which, and by whom)," say so
   rather than manufacturing a false first step.
5. Do NOT implement anything. Do NOT touch any file except the new plan
   doc. This is a design/planning task only.

Write the file directly. When done, report back a short summary: how many
phases, which items are genuinely ready to build vs. still blocked on a
design decision, and your one-line recommendation on whether to proceed
now or resolve specific open decisions first.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
