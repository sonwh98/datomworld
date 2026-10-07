Created-GMT: 2026-10-05 21:35:10 GMT
Created-Local: 2026-10-06 04:35:10 +07
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

# Task: architect-h2-contract (round 2: confirm the conditions)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 04:26:40 +07 | Status: active | Rationale: same Architect, resumed to confirm its conditional sign-off

Read-only, in /Users/sto/workspace/datomworld/.claude/worktrees/head-h2 (still
UNCOMMITTED). Do not edit. The engineer's round-3 report is untrusted:
`collab/1791235850740-stream-engineer-head-h2-r3.claude-opus-5-5.stdout.log`.

Your sign-off was conditional on A1, A2, A3 and the ruling-4 fix. The engineer
applied them and, to cover three independent-review findings in `remote.cljc`
(`collab/1791235600539-reviewer-head-h2.gpt-6.1-sol.findings.md`), ADDED contract
sentences of its own that you have not seen:

- 2.1, the A2 clause now ends "with any op other than `descriptor`, or with args
  other than `[]`, it is malformed."
- 2.1's `oversize` bullet: for a named request the error carries the name, and
  carries an identity only when the answer it replaces had one.
- 2.4 Resolve: a sentence that a `full` refusal is retried by the next `resolve`,
  any other refusal is returned as the writer's own outcome, and a name refused
  with `invalid-value` or `closed` gets that outcome again with no further send
  (the link remembers it per name as unsendable).

Also: `serve` in `src/cljc/yin/vm/linker/head/ws.cljc` now refuses a `:bind-port`
that is not a positive integer (`:yin.head.ws/no-port`) and the port-1 placeholder
is gone; the orchestrator amended `docs/design/yin.vm.linker.dht.head.md` section 6
to say the listener and the host's connect seam are the arguments and that `serve`
requires a positive bind port.

Confirm, citing lines: (1) A1, A2, A3 are in as you wrote them; (2) each ADDED
sentence is a correct, minimal rule the code obeys, consistent with the rest of
`dao.stream.remote.md` (in particular 2.5's resend rule and the existing `oversize`
and `closed` semantics) and with `dao.stream.md`; give exact replacement text for
any that is not; (3) the ruling-4 fix is right and the head design sentence is
accurate; (4) the one open question the engineer left: a writer `transport-error`
on the named send is returned verbatim but NOT remembered as terminal (it may be
transient). Rule on it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

First line after the header: "Contract text signed off." or the specific blockers.
