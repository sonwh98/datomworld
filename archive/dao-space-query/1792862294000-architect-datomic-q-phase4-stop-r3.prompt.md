Created-GMT: 2026-10-24 17:23:34 GMT
Created-Local: 2026-10-25 00:23:34 +07
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9 (resume — the datomic-compat design thread)

# Task: r3 — Phase 4 STOP escalation: decide the state-threading route and the checked-read seam design

Role: Lead System Architect (design decision; the contract's own STOP rule routes here)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-25 00:23:34 +07 | Status: active | Rationale: contract author; the compat doc's Phase 4 row says the checked-read seam "requires architecture review before landing"; the implementer invoked the brief's STOP rule.

## Where the track stands

Phases 0 and 1 are committed on master (3d77ed52, 1d29cdcf) — the contract
doc, the 81-case corpus, and the adapter boundary, all glm-review-signed
ENDORSE. Phase 4 was dispatched per the ratified 0→1→4→2→3→5 sequence and
the implementer (claude-opus-5-5) has DELIVERED the generic machinery and
STOPPED at the integration boundary, exactly per the brief. Read its full
report: collab/1792862294000-qa-engineer-datomic-q-phase4.claude-opus-5-5.findings.md

Delivered and verified (orchestrator-rerun): execution.cljc (immutable
invocation state; start/unbounded/charge/checkpoint; read-step/run-reads
one-in-flight guarantee; step-units table as data; IDriver + fn-driver;
exhaustion as terminal data {:kind :timeout|:step-budget-exhausted :phase
:steps}; no atoms/volatiles/dynamic vars/global clock in src) and
execution_test.cljc (13 tests / 60 assertions: before/at/after boundaries,
work-vs-time independence, no clock reads when unbounded or terminal,
state-threaded-not-forked, the overshoot demo over an explicit read
sequence, one JVM-only 5s-margin smoke). Core untouched: query-test 72/282,
index-test 56/531, transactor-test 16/100 all 0 failures.

## The two decisions you own

1. **State-threading route into the core evaluator.** The implementer
   reports the evaluator cannot thread immutable state as written: every
   eval-* returns a lazy seq of bindings with no channel to return the next
   state (eval-where is reduce-over-mapcat; eval-rule/eval-not/eval-or build
   lazy content-distinct/mapcat chains; relation-result and aggregates are
   lazy). The ::active-rules precedent threads downward only, never returns
   progress. The contract bans atoms/volatiles/dynamic vars. Options as
   framed:
   - Route A: strict state-passing rewrite — every eval-* returns
     [bindings state]; re-force pull/grouping/projection that way; ~600
     lines rewritten under 72 passing tests; one evaluator.
   - Route B: keep the lazy legacy evaluator for legacy q; add a strict
     bounded evaluator for adapter/timed calls; two evaluators to keep in
     agreement (the conformance corpus + the lazy/eager parity tests are
     the existing agreement-forcing mechanism — judge whether they
     suffice).
   - Or a route C of your own (e.g. state-passing only at boundaries the
     contract's checkpoint list requires, with an explicit argument for why
     the lazy interior cannot diverge between checkpoints beyond one
     indivisible operation). The contract's own text says checks "only
     around q, collect, a clause, or the construction of a range sequence"
     are insufficient — any route must satisfy the forcing-discipline
     subsection as written, or you must amend the contract explicitly with
     the reasoning.
   Also decide: the exact-boundary expiry rule (implementer chose
   observation >= deadline means expired; confirm or fix), and whether
   Route A/B changes the Phase 4 effort estimate (currently 12-22 h; 4-6 h
   already spent).
2. **The checked-read seam.** select-by-index calls bt/slice (opaque lazy
   seq); node fetches go through the storage baked into the borrowed
   restored tree, so there is no per-fetch step to checkpoint. The
   contract permits leaving the timed path over lazy published indexes
   UNQUALIFIED for now ("explicitly leave the timed path unqualified") and
   the implementer kept unchecked state out of the adapter. Decide:
   (a) design the dao.data.btree stepped-traversal or invocation-scoped
     restore-with-wrapping-IStorage seam now (the contract's "minimal
     generic traversal machinery" — specify it for the engineer), or
   (b) rule that Phase 4 lands with the timed path qualified over
     in-memory/current-view sources only, lazy published indexes explicitly
     unqualified, seam deferred to a named later slice (say which — Phase 5
     or a new Phase 4b), with the compat doc's qualification matrix amended
     accordingly.
   Constraint either way: no mutation of a borrowed shared tree; storage
   must not learn query semantics (no layer collapsing).

## What to produce

Read the implementer's report, execution.cljc, execution_test.cljc, the
relevant query.cljc sections (eval-where :1455-1469, ::active-rules
:1413-1416, relation-result :1605-1640, select-by-index / the :1042-1044
range seam, planning-never-forces :1472-1485), and dao.data.btree's slice
path. Then rule:

1. The route decision with reasoning (and the contract amendment if any
   rule must change — mark the exact edited sections; the orchestrator
   transcribes doc edits verbatim, you do not edit files).
2. The seam decision (a or b) and, if (a), the seam spec for the engineer;
   if (b), the doc amendment text for the qualification matrix.
3. The expiry exact-boundary rule.
4. Revised Phase 4 remaining effort + any knock-on to Phases 2/3/5 estimates.
5. One paragraph for the implementer's resume-prompt: the ruling in
   execution-ready terms.

Read-only as before. Begin the final response exactly with the header
format (Completed-GMT / Completed-Local / Coding-Agent: codex / Session-ID).
The orchestrator promotes it to
collab/1792862294000-architect-datomic-q-phase4-stop-r3.gpt-6-astra.findings.md
and resumes the implementer with your ruling.
