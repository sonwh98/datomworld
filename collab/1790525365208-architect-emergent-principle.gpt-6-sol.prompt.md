Created-GMT: 2026-09-27 19:05:00 GMT
Created-Local: 2026-09-28 02:05:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Spell out the emergent-property principle in datom.world.md

Role: Lead System Architect

Owner direction, verbatim: "emergent property of interpreters of pure
data" should be a design principle spelled out in datom.world.md.
Context that prompted it: the owner imagined ShiBi as pure data where
"the capability is an emergent property of interpreter of pure data.
This is exactly like how dao.space is an emergent property of
dao.space.index and dao.space.query" (already recorded in
docs/design/dao.shibi.md's header and in the memory notes).

The principle is the sharpened form of Core Philosophy axiom 2
("Interpretation Creates Semantics: Data is syntax; semantics only
emerge through interpretation"). What the owner adds is the SYSTEM
level: a "system" in datom.world is never an object, service, or
stored artifact -- it is an emergent property of interpreters of pure
data. Verified instances across the current stack, to cite:

- dao.space = the emergent property of dao.space.index and
  dao.space.query over ordinary streams (the ur-example).
- ShiBi capability = emergent from interpreters of pure data; no
  token object exists (docs/design/dao.shibi.md, owner direction
  2026-09-28). Capability, metering, budget: different interpreters
  over the same tuples.
- The four VMs = four interpreters of the one Universal AST; "many
  syntaxes, one AST" is the same principle on the input side.
- The semantic AST in dao.space = queryable facts projected by the
  auto-index observer (docs/design/yin.repl.dao.space-index.md).
- Client/server itself: "one stigmergic behavior that interpreters
  implement" -- roles, not nodes.

Task: spell the principle out in docs/design/datom.world.md as a
NAMED design principle:
1. Extend or annotate Core Philosophy axiom 2 with the system-level
   formulation (a "system" is an emergent property of interpreters of
   pure data; nothing that looks like a stored service is one --
   find the interpreters and you have the system).
2. Add it to the Design Principles section (line ~129) as a named
   principle with the stack instances as one-line evidence each.
3. Keep it consistent with the existing invariants (no layer
   collapsing; one truth, many perspectives; streams as the
   composition medium). Do not rewrite unrelated text; minimal,
   precise insertions.

Write scope: docs/design/datom.world.md only. ASCII, <= 80 columns
on every added/edited line, matching the document's voice.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize what you added and where.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
