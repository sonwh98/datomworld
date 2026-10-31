Created-GMT: 2026-09-28 06:33:18 GMT
Created-Local: 2026-09-28 13:33:18 +0700 (timestamp from the artifact
filename; this header block added at dispatch time by the orchestrator
seat, session sess_e57e8e19-522c-4d8b-8cca-5ca89a8e5117)
Coding-Agent: glm
Session-ID: not-applicable (ZCode subagent; no CLI session id)

# Task: one-envelope migration slice 1 — rpc adopts apply envelopes (BREAKING)

Role: Stream & Network engineer (dao.stream)

Implementers:
- Status-Event: 2026-09-28 15:28:15 +0700 | Model: glm-5.3 | Status: failed | Rationale: two ZCode
  subagent dispatches by the orchestrator seat (2026-09-28 15:22-15:29
  +0700) died at launch with "model concurrency limit exceeded" (19-41s
  in; neither edited a file — tree verified clean both times). Third
  consecutive GLM subagent cap kill on this host including the prior
  seat's slice-2 dispatch; route abandoned for this unit.
- Model: claude-opus-5-5 | Assigned: 2026-09-28 15:28:15 +0700 | Status: active | Rationale: GLM
  subagent route systematically blocked by the host concurrency cap;
  rerouted to the claude CLI (flat Pro Max, authorized for
  implementation; strength table: code migration + complex agentic
  coding). Session-ID: ae26f3c6-a6a2-41ad-839e-3f5c615718d8.
  Gate must then be non-Claude (reviewer independence).

Role: dao.stream engineer. Implement migration slice 1 of the
one-envelope ruling — read it FIRST, it is the authority:
collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md
(sections "Ruling", "Target contract", "Errors", "Migration order"
item 1, and the closing "Do NOT" list).

Prerequisite already landed: slice 2 (absorb! identity check, pair
end on in-stream not-found/channel-gone) — remote.cljc /
remote_pair.cljc; do not touch those files.

## The breaking change (one commit, no compatibility shim)

Replace rpc's wire vocabulary with apply's at the boundary. rpc keeps
its name, its client state machine, event names, completion API, ID
allocator (random-safe-id + retry bound + safe-id? check), and cursor
minting — only the wire VALUES change:

1. Requests: rpc/request! constructs dao.stream.apply/request
   envelopes ({:dao.stream.apply/id :op :args}); received request
   values are validated with apply/request?.
2. Answers: success/error answers become apply's success-response/
   error-response shapes ({:dao.stream.apply/id :ok} /
   {:dao.stream.apply/id :error {:code :message}}); decode with
   apply/response?, then apply rpc's own safe-id? check at the client
   boundary. An invalid error body is REJECTED (rpc rejects, not
   passes); an unsafe reply id is a DIAGNOSTIC (consumed once, like
   the existing malformed path). Preserve the entire envelope map
   when forwarding or retaining (the open-envelope / verbatim rule).
3. rpc's request-value/request-value?/success-answer/error-answer/
   answer?/answer-id/answer-ok/answer-error/answer-ok? either become
   thin aliases of the apply equivalents or are retired if unused
   after the swap — keep public accessors that yin.repl actually
   calls working under the same names.
4. Diagnostics and completions stay rpc-local events: public output
   keys may stay rpc-namespaced, but every embedded request/answer
   VALUE must be an apply envelope.
5. Outstanding cap (ruling correction): client-state gains a
   caller-supplied positive outstanding limit with a documented
   finite default. At the limit, request! returns a local backpressure
   outcome — :dao.stream.rpc/backpressure — WITHOUT allocating an id
   or appending anything. Pin it.
6. Error reasons: transport-error-reason maps remote failures to
   DISTINCT stable apply-qualified words — :dao.stream.apply/not-found,
   /detached, /ended, /no-surface, /oversize, /transport-error.
   Map remote no-surface -> /no-surface and oversize -> /oversize
   (never collapsed into transport-error). Terminal for the binding:
   not-found, ended, no-surface, oversize; detached alone permits
   rebind; generic transport failure follows the existing terminal
   path.

## Wire consumers to migrate with the change

yin.repl.adapter (adapter.cljc ~:50, ~:169), yin.repl.serve
(serve.cljc ~:424, ~:503), yin.repl.connect (~:422, ~:516),
yin.repl.driver (~:383) — update assertions and value handling where
affected. dao.stream.observe documents state transitions, not wire
contracts — should need nothing; verify.

## Do NOT (ruling's protected list)

- Do not rename yin's dao.stream.apply/call syntax or the AST node.
- Do not touch yin.vm.* or dao.stream.apply.cljc (the contract side
  needs no change).
- Do not touch dao.stream.remote.cljc / remote_pair.cljc (slice 2's).
- Do not tighten apply's open predicates; do not add rpc event fields
  to the apply envelope.

## Tests

Migrate test/dao/stream/rpc_test.cljc and the yin.repl
adapter/serve/connect/driver tests together with the change. New
pins: at-limit backpressure (no id allocated, nothing appended, prior
outstanding unaffected); no-surface/oversize map to their own words
and are terminal; detached alone is rebindable; invalid error body
rejected; unsafe reply id -> diagnostic with the request still
outstanding; a request emitted by rpc/request! and an answer accepted
by rpc/poll! both pass the apply predicates.

## Constraints

- Run via mise: focused namespaces first, then the full JVM suite;
  then the Node lane (npx shadow-cljs compile test + node
  target/node-tests.js). All green except the ONE known pre-existing
  yin.repl.main-test intermittent (note it explicitly if it fires).
- cljstyle check on touched files.
- Do NOT git add or commit — orchestrator stages and commits after
  the gate.
- Report to collab/1790577198000-vm-engineer-one-envelope-slice1.claude-opus-5-5.report.md:
  changes with line refs, exact commands + counts, git status.
