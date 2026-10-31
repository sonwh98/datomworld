Created-GMT: 2026-10-05 21:26:40 GMT
Created-Local: 2026-10-06 04:26:40 +07
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

# Task: architect-h2-contract

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 04:26:40 +07 | Status: active | Rationale: owner prefers fable for Architect sign-off; the design requires an Architect check of the dao.stream.remote.md amendment before slice H2 lands (a different-family reviewer gates the code separately)

Perform a read-only architecture review of the contract amendment slice H2 makes
to `docs/design/dao.stream.remote.md` (and the status line of
`docs/design/dao.stream.ws.md`), and rule on six questions the H2 engineer raised.
The tree is /Users/sto/workspace/datomworld/.claude/worktrees/head-h2; the H2
change is UNCOMMITTED (`git diff` shows the amendment and the code; new files
`src/cljc/yin/vm/linker/head/ws.cljc`, `test/yin/vm/linker/head_ws_test.cljc`).
The engineer's report is untrusted:
`collab/1791234348881-stream-engineer-head-h2-r2.claude-opus-5-5.stdout.log`.
Do not edit any file.

Read first: docs/design/datom.world.md, docs/design/dao.stream.md (lines about
277-285, the logical-identity rule; it must stay unamended),
docs/design/dao.stream.remote.md (whole; every sentence is a rule),
docs/design/yin.vm.linker.dht.head.md (5.1, 6, 8, section 11 H2, which lists the
amendments H2 must carry), src/cljc/dao/stream/remote.cljc,
src/cljc/dao/stream/ws_project.cljc, src/cljc/yin/vm/linker/head/ws.cljc.

## Part A: the contract text

For each amended place (status line; section 2 name map; 2.1 named request and its
answers; 2.3 step 0 and the five-argument mirror step; 2.4 Resolve; section 5
Stream names), decide: correct and minimal, or defective. Check that:
- every amended sentence is a rule the code obeys (cite `file:line`), and no rule
  the code obeys in this area is missing;
- a name is lookup data and never a `:dao.stream/identity`, with no sentence that
  could be read otherwise, and `dao.stream.md`'s identity rule is not contradicted;
- nothing gives the stream layer knowledge of heads, an rpc or apply dependency, a
  server/client privilege beyond what the document already had, or a clock;
- the amendment matches what section 11 H2 of the head design required, no more.

## Part B: the engineer's six questions (rule on each; decision first)

1. `head.ws/dial` takes the host `:connect!` rather than a ready-made attacher,
   because a ws attacher is tied to its own traffic medium and every redial after
   `:source-lost` needs a fresh one. The design says "the listener and the attacher
   are arguments". Accept, or require the attacher as the argument?
2. Identity precedence: a request that carries `:dao.stream/identity` is an
   identity request whatever else it carries (so existing identity requests are
   answered byte-for-byte as before, even with a stray name key). The inherited
   draft made "both a name and an identity" malformed. Which is the rule, and does
   the amended 2.1 say it exactly?
3. The ws channel descriptor's identity is `"ws://host:port/head"`: a fixed address
   string that never crosses the wire and is not the board's name. Is that an
   instance of the alias defect (a fixed name as a logical identity), or legitimate
   as a channel (transport instance) identity?
4. Ephemeral bind: the endpoint descriptor carries port 1 as a placeholder until
   `:bind-succeeded` reports the real port. Acceptable, or a defect?
5. `head.ws` has no stop function; tests stop the listener with the host's
   `:unbind!`. Must H2 add `stop`, or is that H3's?
6. A resolve's `not-found` is reported with the same reason as a gone reflection
   and marks nothing; a named request is re-sent only by repeated `resolve` calls,
   never by a drain. Sound?

Also say whether anything in H2 forces a change to `dao.stream.md`. If it does, the
slice stops and it becomes an owner question.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

First paragraph: sign-off on the contract text, or the blockers. Then Part A
findings as severity | file:line | invariant/evidence | recommended correction
(with exact replacement text for any sentence that must change), then the six
rulings.
