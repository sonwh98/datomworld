Created-GMT: 2026-11-13 16:30:00 GMT
Created-Local: 2026-11-13 23:30:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 2f6d5efe-aa4e-4a90-a350-b457887d069a (capture thread.started)
# Task: architect consult — the uniform depth probe: linked requirement vs instrumentation reality
Role: Architect (you designed the probe's requirements in the depth mod, Q4; the implementer found the linked requirement is a substantial instrumentation task — rule on the scope)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-13 23:30 +0700 | Status: active

## The finding (terra, Phase 3r's harness author — read their report:
`collab/exception-track/1794589000000-engineer-depth-probe.gpt-6.1-terra.stdout.log`)

The depth utility you specified measures **unlinked** runs. The mod's Q4 ruling required the
probe on **linked** backends (semantic/stack/register through full composition). The implementer
found: linked composition requires **scheduler, FFI bridge, and link-serving handling during
stepping** — the depth collection cannot simply wrap the existing utility; it is a substantial
instrumentation task (instrument K-length during scheduler stepping across link boundaries).

## The question

Your Q4 ruling's intent was: pin "iteration constant, handler-crossing linear" as a measured
contract, uniformly across the four frontends, with the constant case ASSERTED and the linear
case MEASURED-but-never-asserted. Rule on the scope:

**(A) Unlinked suffices.** The guest-code continuation-depth property is an ENGINE property —
the walker/semantic/de Bruijn lanes run the same engine semantics; linked composition adds
scheduler/link infrastructure frames that are not guest continuation. Unlinked probes on the
three lanes establish the contract; the linked-lane instrumentation is a recorded follow-up.
Name the exact contract language (what the deftest asserts, on which lanes).

**(B) Linked is required.** Then design the instrumentation: where K-length is measured during
scheduler stepping, how link boundaries are handled, what the implementer builds — scoped and
specified for a single round if possible.

**(C) Split.** Constant-case assertions on unlinked lanes NOW (the contract), linear-case
linked instrumentation as a scoped follow-up. Specify both.

## Constraints

The probe's purpose (your Q4): a uniform, measured contract across all four frontends —
iteration constant (ASSERTED), handler-crossing linear (MEASURED, never asserted as a
lower bound). The frontends' guest code is identical; the engines are the same; the question is
whether the measurement INSTRUMENT needs the full linked composition to measure honestly.

## Deliverable

Ruling with evidence; the exact probe specification for the implementer; end with one line:
**SCOPE RULED: (A)/(B)/(C) + <one-line contract language>**. Read-only. Begin with
Completed-GMT/Completed-Local.
