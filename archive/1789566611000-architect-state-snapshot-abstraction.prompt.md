Created-GMT: 2026-09-16 07:10:11 GMT
Created-Local: 2026-09-16 14:10:11 +07 (Asia/Ho_Chi_Minh)

# Task: Discover and propose a shared "observer emits bounded state as a stream value" abstraction

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 14:10:11 +07 | Status: active | Rationale: architecture discovery + design proposal, matching tonight's "Derive, don't persist" principle-discovery precedent
Session-ID: 77014c18-6933-498c-8e8e-4d676f497e02

## Context

The owner made an architectural observation while reviewing the
`yin.vm.v1-retirement.implementation-plan.md`'s telemetry decision (D2):
v1's VM telemetry (`src/cljc/yin/vm/telemetry.cljc`) is not a special
subsystem. It is a VM emitting plain data describing its own internal
state onto a `dao.stream` — the same `append!` primitive
(`src/cljc/dao/stream.cljc`'s `IDaoStreamWriter/append!`) every other
observer in this codebase already uses. Per
`docs/design/datom.world.md`'s foundational axioms ("Everything is a
Stream", "Side effects must appear as stream emissions") and the Host
Boundary section ("A callback is an event on a stream... an adapter is a
map of functions that deposit plain data"), this is not a gap needing a
bespoke feature plan — it's an instance of an existing pattern that
should be named and reused, not reinvented per-subsystem.

Verified tonight, read `src/cljc/yin/vm/telemetry.cljc` in full first:
the actual reusable substance is NOT the stream mechanics (`emit-snapshot`
ends in a trivial `(doseq [datom (event-datoms ...)] (append! stream
datom))`) — it's the BOUNDED SUMMARIZATION half: `type-tag` (classify an
arbitrary value, including opaque handles, without walking into them),
`summarize*` (a depth-limited, size-limited walk turning arbitrary
internal state into safe, bounded plain data, `max-coll-items`/
`max-summary-depth` bounded), and `cursor-map?`/opaque-handle detection.
That's the genuinely reusable primitive: "take some possibly-large,
possibly-opaque-handle-containing internal state and safely turn it into
bounded plain data suitable for appending to a stream." The emission
half is already fully generic and needs no new abstraction.

## Task

1. **Discover, don't assume.** Search the codebase for OTHER places that
   already do (or clearly need, but currently duplicate or omit) this
   same pattern: bounding/summarizing potentially-large or
   opaque-handle-containing internal state into safe plain data for
   observability, logging, or stream emission. Candidates to check, but
   don't stop at this list — search broadly:
   - `dao.space.index` (does it ever need to summarize what it indexes
     for its own observability?)
   - Any stream driver or transport adapter that logs/reports its own
     state
   - `yin.vm`'s own telemetry stub — does its docstring or the
     divergence register hint at where else this was expected to be
     needed?
   - Any existing ad-hoc "debug print" or "inspect" style function
     anywhere in `src/` that does informal depth/size bounding by hand
     (a sign of the same need being solved locally, worth finding and
     citing as evidence for the pattern's recurrence)
   - `docs/design/*.md` — does any existing design doc already gesture
     at this need without naming it as a shared thing?
2. **Propose the minimal shared abstraction.** Based on what you find
   (not preemptively from this brief's framing — verify whether the v1
   shape is actually the right one to generalize, or whether it's
   AST-walker-specific in ways that don't generalize cleanly). At
   minimum, evaluate whether the abstraction is:
   - A small, portable (CLJ/CLJS/CLJD) namespace exposing something like
     a `type-tag`-equivalent classifier and a bounded summarizer, with
     the depth/size bounds as explicit, caller-supplied parameters (not
     hardcoded module constants like v1's `max-coll-items`/
     `max-summary-depth`, since different observers may need different
     bounds).
   - Whether "emit" deserves its own tiny wrapper function at all (e.g.
     `(when stream (append! stream value))`) for discoverability/grep-
     ability and consistency, or whether that's trivial enough to just
     be written inline everywhere and a wrapper would be over-
     abstraction (weigh this against `docs/agents/malleability.md`'s
     guidance and the project's own "avoid cleverness" / "prefer simple
     data over rich types" code style rules).
   - Where it should live (a new `dao.observe`-style namespace? Under
     `dao.stream`? Somewhere else? — name it per the project's existing
     naming conventions, check `docs/agents/vocabulary.md` for
     precedent).
3. **Do NOT implement VM telemetry itself** — that's separate
   implementation work for the v1-retirement plan's D2/U3, informed by
   whatever you propose here but not written by this task. This task's
   deliverable is the abstraction's design and where it lives, not its
   application to telemetry.

## Deliverable

Write a short design document (a new file under `docs/design/`, name it
per the project's conventions — something like `dao.observe.md` or
similar, your call based on what you actually design) proposing the
abstraction, citing every place you found that needs it or already
duplicates a piece of it, and the API surface (function signatures,
portable across CLJ/CLJS/CLJD, no reader-conditional traps — this
project's memory notes a specific ClojureDart `#?(:clj ...)` trap, be
careful if you touch reader conditionals in any example code). If you
determine after real investigation that NO shared abstraction is
actually warranted (e.g., if VM telemetry turns out to be the only real
instance and generalizing now would be premature abstraction against
`docs/design/datom.world.md`'s "Do not optimize prematurely" /
`CLAUDE.md`'s "don't design for hypothetical future requirements"), say
so clearly and explain why, rather than inventing structure to satisfy
the brief. This is a design proposal, not yet reviewed or approved —
mark it as such in the document's own status line.

Report back a summary of what you found and proposed (or why you
concluded no abstraction is warranted). Do not implement anything
beyond this design document. Do not stage or commit.
