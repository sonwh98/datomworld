Created-GMT: 2026-11-16 18:33:17 GMT
Created-Local: 2026-11-17 01:33:17 +07 (Asia/Ho_Chi_Minh)

# Task: yang-compilers-dedup-malleability-review

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-17 01:33:17 +0700 | Status: active | Rationale: continuation of the same subsystem's step-7 review thread (01a1e592-2d0b-7fe2-9438-fae05bf926d6, your APPROVED verdict 18:18 GMT); resuming you for the design-level question — you already hold the full diff in context

This is a SECOND, different question on the same change: not code fidelity (you approved
that) but **malleability governance** — whether the DRY refactoring improves the codebase's
evolutionary fitness per `docs/agents/malleability.md` (bounded complexity, loose coupling,
high cohesion), and whether the new `yang.core.*` shapes will survive future change
(new target languages, policy divergences, landing of the still-parked items 2/4/5).

Perform a read-only architecture review. Do not edit files.

## Read first (in the repo root = your cwd)

- `docs/agents/malleability.md` — the governance doc: coupling measured via
  `bin/analyze-deps.clj` (in/out degrees, cycles, SCCs), bounded complexity = 2-5 public
  entry points, public/private discipline, extraction triggers, naming.
- `docs/design/datom.world.md` — master architecture (foundational axioms, non-negotiable
  invariants).
- `collab/exception-track/1794852821344-dry-r2-full.patch.txt` — the full 13-path diff you
  already reviewed.
- `collab/exception-track/1794796441580-dry-orchestrator-fixture-inventory-and-equivalence-plan.md`
  — the equivalence plan (includes the conflict-resolution rule for future target adoption).

## Measured evidence (orchestrator-run, for you to trust or re-derive)

From `bin/analyze-deps.clj` on the CURRENT master (f32a4392, pre-landing of the diff):
- `yang.core.prelude (in:9, out:3)` — foundational shape (high in, low out).
- `yang.php.antlr.siteset (in:3, out:8)`, `yang.python.antlr.siteset (in:1, out:7)`.
- CIRCULAR (3): all three are SELF-loops (A→A) in `php.antlr.quoted`, `python.antlr.prelude`,
  `typescript.antlr.prelude` — pre-existing, not introduced by this diff.
- No SCCs > 1.
Public-API counts on the APPLIED tree (grep, applied at /tmp/yang-rebase-test on jupiter):
- `yang.core.siteset-store`: 2 public (`load-tree`, `overlay`).
- `yang.core.siteset-publication`: 3 public (`signed-entry`, `entry-datoms`,
  `signed-entries`).
- `yang.core.prelude` additions: +2 public (`balanced-definitions->uast`,
  `definitions->uast`).
- `yang.core.lower` additions: +2 public (`code-point->str`, `build-vector`).
Note the analyzer's graph is from PRE-diff master — the diff ADDS the two new siteset
namespaces and +2/+2 public functions; in/out degrees for the new nodes are known from the
requires (store: 1 out; publication: 1 out; each consumer +2 out).

## Evaluate (per malleability.md's own criteria)

1. **Bounded complexity**: do the new namespaces' public surfaces (2 and 3 entry points)
   meet the doc's 2-5 target? Are the wrappers in consumers justified public API, or should
   they be private (`defn-`)? Did this round grow `core.prelude`/`core.lower`'s public
   surface toward the "namespace is doing too many things" threshold?
2. **Loose coupling / high cohesion**: moving store+publication into `yang.core.*` — does
   it cluster things that change together (store semantics, envelope format) and isolate
   things that change independently (per-target discovery policy, admission)? Or does it
   create a premature abstraction that will accrete policy flags?
3. **Structural clustering**: php/python sitesets shared an identical dependency shape —
   the doc names exactly this as an extraction signal. Did the round extract the right
   cluster, and is there a NEXT cluster visible (e.g. the safepoint module emitters, the
   per-target `yang.safepoint` roots) that this round should have taken or explicitly left?
4. **Evolutionary fitness**: a new target language (the yang.ruby scenario) now writes
   ~10 wrapper lines instead of ~170 copied lines. Does the shape hold under the realistic
   variation space (a target with a genuinely different store protocol? different envelope
   needs? a target without sitesets at all, like typescript)?
5. **Malleability risks in the still-parked items 2/4/5** (prelude/lower emitters, waiting
   on the php prelude-clear): does the parameterization chosen (vconj-as-data, reader/
   absent-predicate seam) match the doc's "parameterize on what varies" instinct, or does
   it anticipate variation that won't happen?
6. **Naming** (`siteset-store`, `siteset-publication`): problem-domain names per the doc's
   naming rule, or solution-domain leakage?

## Report format (mandatory per the architect template)

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction — for any
defect or risk. Distinguish architectural defects from implementation gaps or intentionally
deferred work. Also confirm explicitly which malleability properties PASSED review (the
doc's own vocabulary: bounded complexity, loose coupling, high cohesion, structural
clustering, naming). End with one line:

MALLEABILITY VERDICT: IMPROVED | NEUTRAL | DEGRADED — <one line of justification>
