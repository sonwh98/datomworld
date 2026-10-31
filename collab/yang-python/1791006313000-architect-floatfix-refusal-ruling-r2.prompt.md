Created-GMT: 2026-10-03 05:45:13 GMT
Created-Local: 2026-10-03 12:45:13 +07 (+0700)
Coding-Agent: claude (fable-5-1, resume 1379a095-acd5-4d9b-a645-75ab92207d1c) and codex (gpt-6-astra, resume 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 1379a095-acd5-4d9b-a645-75ab92207d1c (fable); 01a0f878-281b-7253-ac44-ff2402583d35 (astra)

# Task: Float-fix — cross-reading round on the generic-arithmetic carrier refusal

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-03 12:45 +07 | Status: active | Rationale: round 2 of the standing mob; resumes its round-1 session
- Model: gpt-6-astra | Assigned: 2026-10-03 12:45 +07 | Status: active | Rationale: round 2 of the standing mob; resumes its round-1 thread

Read-only. Do not edit. This round exists to converge.

## Where round 1 landed (read both; they are your peer's reasoning)

- fable: collab/1790998541000-architect-floatfix-refusal-ruling.claude-fable-5-1.stdout.log — SHIP-WITH-CHANGES
- astra: collab/1790998541000-architect-floatfix-refusal-ruling.gpt-6-astra.stdout.log — REMOVE the wrappers
  (the astra file is codex JSONL; its final `agent_message` item holds the verdict)

They agree that the refusal was beyond the 8.5.5 text and that 8.5.5 must record whatever is decided, and
that a uniform host-representation-based refusal is not implementable (on JVM/Dart float64 content is a
plain double). They split on whether decoded carriers reaching generic `+ - * / < > <= >=` on JavaScript
is a hazard this slice must close (fable) or one it explicitly leaves open (astra).

Concrete case: a continuation saved on the JVM holds `acc = 2.0`; Node resumes it and runs a bare
`(+ acc 1)` (not Python-lowered). Today JVM gives 3.0; on Node the `Float64` carrier's `toString`
(`(str v)`, src/cljc/dao/jing/cbor.cljc:341-346) is the only coercion hook found, so JS yields the string
`"21"`; both lift to valid but different addresses.

## New proposal from the orchestrator (this is the orchestrator's idea, not the owner's)

Move the refusal out of yin.vm and into the Jing carrier: make the JavaScript `Float64` carrier itself
refuse implicit coercion (a throwing `valueOf` / `Symbol.toPrimitive`), so any native JS operator that
touches it fails loudly, in generic yin and in raw host code, while `toString`/printing and the Python
`data/float-value` seam keep working. Then yin.vm needs no `carrier-refusing` wrappers and no profile
awareness, and the hazard is closed at its source.

## Questions (answer each; give a recommendation, not a survey)

1. Does the proposal satisfy your own round-1 objection? fable: is the hazard closed without wrapping the
   primitives? astra: does it stay out of generic-evaluator dispatch and avoid a new numeric contract for
   the standard primitives?
2. What does it break? Consider `pr-str`/`str`/string building, `=`/hash/sort comparisons, `<` on the
   carrier (`valueOf` throwing vs. a comparison that needs it), JSON/CBOR encode paths, and the
   `dao.jing.cbor.md` "reject carrier operands loudly" sentence. Which of those are in this slice?
3. Does it belong in the float-fix slice, in a separate Jing change, or not at all? If separate, is it
   acceptable for float-fix to ship with astra's removal plus a recorded "arithmetic over decoded carriers
   is not established" until that Jing change lands?
4. If you still disagree with your peer after reading the above, state the single remaining point.

Begin the final response with a one-line joint-position proposal (a verdict both of you could sign), then
the answers. Start exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
