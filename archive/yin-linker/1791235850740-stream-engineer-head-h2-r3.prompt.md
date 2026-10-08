Created-GMT: 2026-10-05 21:30:50 GMT
Created-Local: 2026-10-06 04:30:50 +07
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

# Task: head-h2 (round 3: Architect rulings and review findings)

Role: DaoStream and Network Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 04:05:48 +07 | Status: active | Rationale: same engineer, resumed to apply an Architect sign-off's conditions and an independent review

Your H2 is verified (127 tests green) and was checked by an Architect
(claude-fable-5-1, read-only:
`collab/1791235600539-architect-h2-contract.claude-fable-5-1.stdout.log`) and an
independent reviewer (gpt-6.1-sol:
`collab/1791235600539-reviewer-head-h2.gpt-6.1-sol.findings.md`). Both are
untrusted: check every citation. Seven items, all small. The orchestrator has
already amended `docs/design/yin.vm.linker.dht.head.md` section 6; do NOT edit
that file.

## From the Architect (contract text signed, conditional on A1 to A3)

- **A1** `docs/design/dao.stream.remote.md` about line 292: replace the sentence
  that says "a name outlives what it named" with: "`descriptor` still answers
  `ok`: a descriptor outlives what it described."
- **A2** about lines 140-142: replace the two sentences with: "A request that
  carries `:dao.stream/identity` is an identity request whatever else it carries,
  a `:dao.stream.remote/name` included, which is then ignored. A request that
  carries `:dao.stream.remote/name` and no `:dao.stream/identity` is a named
  request; with any op other than `descriptor` it is malformed." (Your identity
  precedence is confirmed as the rule.)
- **A3** about lines 312-325 (the Resolve paragraph): after "which marks
  nothing." add: "A filed `oversize` is returned the same way, with its own name
  as the reason." After the channel-loss sentence add: "On a channel descriptor
  this peer holds no channel for, `resolve` answers `not-found`, as `attach!`
  does." Make sure both sentences are true of the code after the fixes below.
- **Ruling 4, a code defect: the ephemeral-bind placeholder.** Your claim that
  `accept-connection!` ignores the descriptor is false: it stamps it on every
  accepted handle (`src/cljc/dao/stream/ws.cljc` about 502 and 188-197), so those
  handles report port 1, against `dao.stream.ws.md` 459-461; and after
  `:bind-succeeded` the server's descriptor has the real `:ws/port` but still the
  identity `"ws://host:1/head"` (`head/ws.cljc` about 244-246). Fix: `serve`
  REQUIRES a positive `:bind-port` and refuses otherwise as data (a refusal
  reason of its own, for example `:yin.head.ws/no-port`); remove the placeholder.
  The JVM real-socket test then needs a concrete free port. Tests: zero, nil and
  a negative port each refuse and compose no endpoint; with a positive port the
  accepted handles and the server descriptor carry that port and its identity
  string from the start.
- Rulings 1, 3, 5, 6: accepted as you built them (`:connect!` as the argument;
  the ws channel descriptor string is a legitimate channel identity; no `stop` in
  H2; resolve `not-found` handling is sound). No change.

## From the reviewer (three findings in `remote.cljc`)

1. **P2 about line 90: a resolve can hang forever.** If a named `not-found` answer
   is refused with `invalid-value`, `write-answer!` falls back to
   `{id, name, error oversize}` with no identity, which `well-formed-answer?`
   drops, leaving the resolve outstanding indefinitely. Accept a correlated named
   `oversize` error without an identity; add a writer test that rejects the
   `not-found` but accepts its fallback, and show `resolve` returns the
   `transport-error` with that reason (this is what A3's first sentence states).
2. **P2 about line 852: an unsendable name retries forever.** `resolve-name`
   discards `send-named!`'s outcome, so a permanently uncarryable name or request
   returns retry on every call, minting another id and attempting another send
   each time. Keep retry for transient backpressure; return a terminal error for
   a permanent send refusal such as `invalid-value`; test an oversized request
   against a capped channel (terminal, no id minted on later calls beyond what the
   contract's k-asks resend allows).
3. **P3 about line 77: loose args.** A named `descriptor` request with args `[1]`
   or `[1 2]` passes validation and performs the lookup, though the wire contract
   says `[]`. Require empty args in the NAMED branch only (identity-request
   validation stays exactly as it was); add the non-empty-vector cases. If the
   contract text of 2.1 does not already say the args are empty, add the words.

## Scope and process rules (unchanged)

Files: `src/cljc/dao/stream/remote.cljc`, `src/cljc/yin/vm/linker/head/ws.cljc`,
`test/dao/stream/remote_test.cljc`, `test/yin/vm/linker/head_ws_test.cljc`,
`docs/design/dao.stream.remote.md` (and `src/cljc/dao/stream/ws_project.cljc`
only if a fix truly needs it). Every existing test passes unchanged; an identity
request is answered exactly as before. No git commands, no formatter, no Node or
Dart runs, no background processes (do not use `clj -M:test -e`; it hung last
time). Verify in the foreground with the same combined run you used and
`clj -M:kondo --lint` on the touched files, with assertion counts. Keep the
contract document ASCII and at most 80 columns. `docs/design/dao.stream.md` stays
unamended; stop and report if a fix seems to need it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

Then report, per item (A1, A2, A3, ruling 4, findings 1 to 3): what changed and the
test that pins it; the exact commands and outcomes with assertion counts; anything
unresolved.
