Created-GMT: 2026-11-13 00:25:00 GMT
Created-Local: 2026-11-13 07:25:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1d248-92d2-7d13-a728-4c8553e8523a (CAPTURED from thread.started — per the codex ID rule)
# Task: architect consult — LaTTe oracle context-reconstruction vs continuation-handler transport
Role: Architect (design consult; you resolved the carrier knot; this is the next knot in the same
port, and the first true collision between the convergence directive and a non-exception gate)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-13 07:25 +0700 | Status: active | Rationale: continuity of
  the encoding-tradeoff line; independent of the implementers.

## The knot (from the corpus round's FINDING, `1794564000000-...terra.stdout.log`)

The port (`exception-r3-impl` @ `18870268`, pushed, focused suites green) retired the outcome
channel for the `%capture` continuation encoding. `yang.latte-oracle-test`'s
`test-oracle-slice8-context-reconstruction`:
1. exceeds its **100,000-visit machine-data validation bound** (and this is NOT cc3's ruled
   bounded-run condition — that declaration measured retained bytes on a different fixture;
   the cc3 merge is `2c80a421`);
2. beyond the bound, **UCF transport refuses the active continuation handler's heap cell** —
   under the continuation encoding an active oracle context embeds a live handler cell; the
   outcome model's contexts contained none, so the transport never met this before.

Constraints in force: **no `src/cljc/yin/vm/` changes** (the owner's declined-seam boundary;
the approved provenance primitive is already landed shared infra — not a new seam), no kit
changes, the validation/reconstruction ASSERTIONS stay intact (test intent preserved), the
owner's FINAL DIRECTIVE stands (convergence), and the LaTTe oracle gate is a named acceptance
gate of the port.

## Questions (rule with evidence)

1. **Re-expressible fixture-side?** Reconstruction's intent is "recover a proof context." Could
   the test re-establish handler scope AFTER transport (transport the DATA, rebuild the
   continuation state on arrival via the clj/ family's own mint sites) rather than transporting
   the live handler cell? Is that faithful to the oracle's contract, or does slice8's purpose
   REQUIRE transporting an active continuation (in which case the outcome model was
   accidentally smuggling it and the gate is genuinely broken by convergence)?
2. **The bound**: does the continuation encoding legitimately need more visits for the same
   reconstruction (more state to walk), making the 100k bound stale-for-the-new-encoding? If
   so: who owns the bound (machine-data validation policy), and what's the honest value?
3. **If neither**: this is DIRECTIVE-vs-GATE conflict: convergence (FINAL DIRECTIVE) vs a LaTTe
   oracle transport that assumes no live continuations. Frame the question FOR THE OWNER
   precisely — options, what each sacrifices, recommendation.
4. Whatever the answer: the exact implementer instruction (shape, sites, pins) for the round
   that follows.

## Deliverable

Ruling per question with file:line evidence (read the reconstruction test + the transport refusal
path + what py/js/php donors' contexts contain for comparison). End with one line:
**RESOLVED FIXTURE-SIDE: <shape>** / **RESOLVED BOUND: <owner+value>** /
**DIRECTIVE-VS-GATE — OWNER DECISION REQUIRED: <framed>**. Read-only. Begin with
Completed-GMT/Completed-Local.
