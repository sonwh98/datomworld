Created-GMT: 2026-09-27 18:40:00 GMT
Created-Local: 2026-09-28 01:40:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: ShiBi capability-vs-currency -- owner decision brief

Role: Lead System Architect (decision brief for the owner)

The longest-standing open design decision:
docs/design/dao.stream.discovery.md:181-190 poses it; dao.shibi.md (the
seam doc, 76 lines) inherits it; the owner has twice deferred ruling.
Now that dao.stream.remote's implementation is nearly complete and
ShiBi's integration seam is fixed (mirror-side gate with pure
verify/fold over an index-published decision, opaque credential slot,
capability-free :dao.stream/refused; the owner has ruled ShiBi itself
is "a tuple space that emerges from two interpreters:
dao.space.index and dao.space.query"), the decision deserves a proper
brief.

Read first: docs/design/dao.stream.discovery.md:170-200,
docs/design/dao.shibi.md, docs/design/dao.space.security.md (the
macaroon-style reference, :33), docs/bootstrap.md:65 (ShiBi
unimplemented), and the memory notes project_shibi_tuple_space.

Write (in your final response, no files) the decision brief:

1. What capability vs currency actually MEANS here, concretely:
   capability = attenuatable verifiable grants (a peer receives a
   right it can delegate narrowed); currency = spendable budget (a
   peer receives metered allowance it consumes). What each looks like
   as dao.space tuples flowing through the two interpreters.
2. What each choice costs/buys for the THREE consumers that will meet
   ShiBi first: (a) relay-pair leases (who may claim a meeting peer's
   pair), (b) the dao.stream.remote credential slot (what a mirror
   gate verifies), (c) REPL session authority (whose eval requests a
   served shell honors).
3. Reversibility: can the seam host BOTH (decide later, start
   capability-only), or does the choice contaminate the tuple shapes?
4. Your recommendation with rationale.

Keep it under 90 lines of response. The owner rules from this brief.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
