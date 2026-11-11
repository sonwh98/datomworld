Created-GMT: 2026-11-11 10:0x GMT
Created-Local: 2026-11-11 17:0x +0700
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: step-7 architect review — the exception-py-r2r3 branch (READ-ONLY)

Role: independent architect reviewer. You edit NOTHING; the deliverable is your final message (findings format, verdict line).

## What to review

Branch `exception-py-r2r3` in worktree `/Users/sto/workspace/datomworld-f12`, the three runtime commits against master `4cfa609d`:

- `d9a73150` — R2 reception: foreign payloads wrapped in a generated `ForeignException` (prelude.cljc, the :1034/:1045 regions)
- `24f2f467` — R3 reception: protocol conditions retained with qualified reason + evidence (:1046)
- `a396ae28` — egress: retained generated exceptions forward the original condition, non-stacking (:912)

Also in scope, informational: `be87df74`/`16c6294f` (the parity pins + the overlay diagnosis) and `04cb7887` (the scanner-golden regen, deferred #{:py}, three-run byte-identical).

## The contract you review against

- Ruling v2: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — §R2 (reception rules 1-3, ForeignException, retained-condition lifetime: identity-owned, outside source-writable attributes, no ambient registry, wrapper non-stacking, formatting never invokes foreign methods), §R3 (qualified reasons retain the complete condition; unchanged rethrow forwards), §R5 (no shape-routing; a returned lookalike is data, a thrown lookalike is a fresh payload).
- Decision (A): the internal continuation-handler encoding is NOT to be converged — flag any scope creep.
- The ceded-surface registry: python regions :912/:1034/:1045/:1046 only.

## Prior art to beat (the JS review found a real P1 — look for the python analogues)

The JS round's step-7 found: admitted Clojure source could MINT a cell carrying `:js/class :error` + the retained-condition key, and the JS bridge would forward it — forged provenance. For python, interrogate:

1. **Retention authenticity**: where does the python wrapper's retained condition live, and can admitted guest source (python, clojure via cells, any frontend) forge a value that passes the reception/egress identity check without the runtime having created it? (attribute writes, cell contents, subclassing the generated exception class, re-raising a caught wrapper from a different activation)
2. **The non-stacking rule**: does a wrapper re-raised through the egress path forward the ORIGINAL condition (no second wrapper)? Does a wrapper caught and re-raised through a source `raise e` stay single-layered?
3. **Typed catches**: does `py/exc-matches` match the wrapper against the LOCAL hierarchy only? Can a foreign class name leak into matching? Does the catch-all vs typed distinction hold?
4. **R5**: can reception/egress route on the condition map's SHAPE anywhere (a lookalike map crossing the boundary being treated as a condition)? Is the condition-delivery-by-call path (the injected `:yang.call/raise` invocation) distinguishable from data by anything other than the runtime's own provenance?
5. **Lifetime**: does the retained association survive the creating activation and die with the wrapper (no ambient registry, no leak)?

## Format

Findings table (severity P0/P1/P2, file:line, invariant, recommended correction), then a verdict line: ACCEPT / FIX-LIST / REJECT. If you supply test-focused corrections, describe the pin that would fail before and pass after.
