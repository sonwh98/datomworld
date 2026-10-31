Created-GMT: 2026-09-25 08:35:00 GMT
Created-Local: 2026-09-25 15:35:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0d7a1-1303-72f0-857f-753f4bf04799

# Task: Fold In the Owner Stream-Facade Directive + the Feasibility Verdict Amendments

Role: Lead System Architect (continuation of your matrix turn)

Two inputs arrived after your matrix landed. Fold both into section 7.11
(and cross-reference lines in 7.3-7.7 where needed).

## Input 1: Owner directive (verbatim)

"dao.stream is an abstraction boundary. it does not assume a network. a
string can have a dao.stream interface. dao.stream that are not network
accessible needs to be network accessible. for a UCF to work well it
needs a way to mechanically make any dao.stream implementation accessible
when a continuation is migrated and resume on a network node"

Fold this as the MECHANISM behind the cross-host kept-cursor rows: at
migration, every carried stream cell is exported through the standard
stream-over-network facade derived from the dao.stream contract itself
(the pattern dao.stream.rpc.ws already proves over ring buffers); the cell
descriptor carries the facade endpoint and the kept cursor; the receiver
binds a proxy dao.stream implementation; the cursor profile declares what
the facade must honor; implementations that cannot honor it refuse via
the existing refusal paths. Add the acceptance row: migrate a
continuation holding a NON-network stream (a string-backed stream
fixture), resume on another node, prove the stream binds to the generated
facade with kept-cursor semantics and :dao.stream/gap outcomes preserved.
This upgrade may move the cross-host kept-cursor proof earlier than
post-M5 — judge and say so.

## Input 2: The feasibility verdict (your sibling round,
collab/*-architect-ucf-feasibility.gpt-6-sol.stdout.log, Feasibility:
VIABLE WITH CHANGES). Fold its three amendments to the matrix:
1. Add the early, independent two-engine round-trip test: nontrivial call
   stack, closure, and blocked effect, lifted from one engine and lowered
   in another.
2. Add the crash re-grant test against an enrolled transactional
   consumer (epoch check, operation-intent check, dedup record, and
   side-effect commitment share one atomic boundary); without
   enrollment, the contract declares at-least-once or fail-stop.
3. Note the 7.3 normalization amendment (the falsy-prefix case: one
   normalization rule in both paths, or narrow admissible v2 input and
   publish the decision) as a gate on the code-identity rows.

Rules: write ONLY docs/design/yin.vm.universal-continuation-format.md.
ASCII, <= 80 columns on added/edited lines. Do not rerun tests.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize what was added or re-ordered per row, and where the
kept-cursor proof now lands.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
