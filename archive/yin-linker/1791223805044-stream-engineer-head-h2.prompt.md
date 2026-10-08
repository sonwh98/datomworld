Created-GMT: 2026-10-05 18:10:05 GMT
Created-Local: 2026-10-06 01:10:05 +07
Coding-Agent: claude
Session-ID: 3cb055e5-43cd-4274-9321-2f3c0542b34b

# Task: head-h2

Role: DaoStream and Network Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 01:10:05 +07 | Status: active | Rationale: owner directive 2026-10-06: route all Claude-side work to fable until the 04:00 reset; the slice amends a stream contract, which suits an Architect-grade model; a different-family reviewer (gpt-6.1-sol) gates it

Implement slice **H2 (name resolution, and the board over WebSocket on
loopback)** of the published head trace in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h2 (a git worktree stacked
on H0 and H1, both reviewed: `src/cljc/yin/vm/linker/head.cljc` holds the trace,
`judge` and the follower; do not weaken them).

## Read first

- docs/design/yin.vm.linker.dht.head.md: 5.1 (medium, the name resolution), 5.3,
  section 6 (the `yin.vm.linker.head.ws` row and the paragraph after the table),
  7, 8 (cross-machine: what stays transport-agnostic; do NOT build anything that
  section defers), 10, and section 11 slice H2 (**your acceptance criteria:
  implement EVERY bullet under H2**).
- docs/design/dao.stream.md (lines about 277-285: descriptor identity names one
  transport instance's sequence; **this document is NOT amended**),
  docs/design/dao.stream.remote.md (all of it: every sentence is a rule),
  docs/design/dao.stream.ws.md.
- src/cljc/dao/stream/remote.cljc (the mirror, `mirror-step`, the link, about
  lines 148-196 and 374-381, 509-519), src/cljc/dao/stream/ws_project.cljc
  (`make-acceptor`, `accept-step!`, `dial`, `dial-attach!`, `dial-reflect!`,
  `dial-step!`), src/cljc/yin/repl/serve.cljc and connect.cljc (the existing
  composition over ws: a two-entry table; note its fixed names are a recorded,
  out-of-scope defect, do not copy the alias pattern and do not fix it here),
  test/dao/stream/remote_test.cljc, the ws-project tests, and
  test/yin/vm/linker/head_follow_test.cljc (H1's cases, which must pass over a
  reflection unchanged).

## What to build (files from the design)

- `src/cljc/dao/stream/remote.cljc`: the named `descriptor` request in the
  mirror (a name map as composition data beside the table; a named request
  resolves to the entry's own identity and surface, or `not-found` carrying the
  name; a named request with any other op is malformed and dropped) and the
  link's `resolve`. An identity request is answered exactly as before.
- `src/cljc/dao/stream/ws_project.cljc`: a name map on the acceptor and the dial;
  `resolve` on a dial before any reflection.
- new `src/cljc/yin/vm/linker/head/ws.cljc` (`serve`, `serve-step`, `dial`,
  `dial-step`): the board's acceptor (a one-entry table and a one-entry name map)
  and the reader's dial (resolve the name, then attach), thin compositions of
  `dao.stream.ws-project`; the listener and the attacher are ARGUMENTS. It is the
  only namespace of the head trace that knows a transport. Loopback only: a bind
  host that is not a loopback literal composes no endpoint.
- Tests: `test/dao/stream/remote_test.cljc` and new
  `test/yin/vm/linker/head_ws_test.cljc`.
- **The contract amendments, all in `docs/design/dao.stream.remote.md`** exactly
  as H2 lists them: section 2 (the name map as composition data beside the
  table), 2.1 (the named request shape, and `not-found` carrying the name), 2.3
  (a step 0 that resolves a name or answers `not-found`, and a named request
  with another op as malformed), 2.4 (the link's `resolve`), section 5 (the head
  board as a convention, and the sentence that a name is lookup data and never a
  `:dao.stream/identity`), and its status line (it says "design target" of code
  that exists; `dao.stream.ws.md`'s status line too). Write them in that
  document's own style: every sentence a rule, ASCII, 80 columns. This contract
  text gets its own Architect and independent review before the slice lands, so
  keep the amendment minimal and exact.

## Hard rules

- **`docs/design/dao.stream.md` is not amended.** If you find the slice cannot be
  built without changing it, STOP and report; that becomes an owner question.
- A name is lookup data and NEVER a `:dao.stream/identity`: no value on the wire
  carries the name under that key, and the reader's reflection reports the ring's
  own identity.
- Owner invariants: no server/client privilege in the stream layer beyond what
  `dao.stream.remote` already has; `dao.stream.apply` stays independent of rpc
  (no rpc or transport words in apply code or docs); `dao.stream.remote` and
  `ws-project` learn nothing about heads.
- No UDP serving, no relaying, no multi-source scheduling, no off-loopback
  exposure: all deferred (section 12).
- Every existing `dao.stream.remote` and ws-project test passes UNCHANGED.

## Scope and process rules

- Work only in the files named above; if a dependency needs another file, stop and
  ask. Preserve unrelated changes; do not weaken any existing test.
- **No git commands.** No formatter or cljstyle. Verify in the **foreground** with
  focused JVM runs (for example `clj -M:test -n dao.stream.remote-test -n
  yin.vm.linker.head-ws-test -n yin.vm.linker.head-follow-test` plus the ws-project
  and `yin.repl` serve/connect test namespaces you find) and `clj -M:kondo --lint
  <files>`. No background processes. **No Node or Dart runs**; the orchestrator runs
  the three lanes at landing and reports host failures back. Host-specific listener
  and attacher code is NOT yours to write: reuse the seams `yin.repl.host` and the
  existing ws tests already use; if a host seam is missing, stop and report.
- Portable `.cljc`. ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd` FIRST
  for JVM-only code; no `0.0` literals in portable tests; EDN with no whitespace
  before a closer; `(- x)` is `0 - x` on Dart; no duplicate `_` protocol parameters;
  no `#'ns/private` across namespaces; a `for` over a seq of more than 32 elements
  can hand the body a nil on Dart, use `keep` or `mapv`.
- Nothing you add may throw on a value read from the wire: malformed named
  requests and unexpected answers are data (dropped or `not-found`), tested.
- If the design contradicts the code or itself, STOP that item and say so with
  file:line; do not silently diverge.

## If you cannot finish everything

Land a coherent prefix in this order and report exactly what remains: (1) the
mirror's named `descriptor` request and the link's `resolve` with
`remote_test.cljc`; (2) the `dao.stream.remote.md` amendments; (3) the name map
and `resolve` in `ws_project.cljc`; (4) `head/ws.cljc` with
`head_ws_test.cljc`.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 3cb055e5-43cd-4274-9321-2f3c0542b34b

Then report: changed files; the exact commands and outcomes with assertion counts;
every H2 criterion with done / partly / not done and the test that pins it; the
wire shape of the named request and its answers; every return shape you chose; the
exact sections of `dao.stream.remote.md` you amended; unresolved concerns; and any
incomplete work. Do not claim edits or tests that did not occur.
