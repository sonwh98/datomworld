Created-GMT: 2026-10-26 19:15:00 GMT
Created-Local: 2026-10-27 02:15:00 +07

# Task: J6 prep — the Autobahn conformance harness (design-time, not implementation)

Role: Engineer (jolt lane, J6 preparation slice). J6 is native WebSocket +
network REPL peers; the codec track's C0–C4 landed, so its dependency is
satisfied. J5 (the acceptance run) is in flight on another seat — your scope
is DISJOINT: the Autobahn harness only. Do not touch src/jolt's parser/
frontends files, src/cljs, src/cljd, or anything the J5 seat owns.

## The design contract

docs/design/yin.repl-jolt-implementation.md §J6 (the slice row) and
docs/design/dao.stream.ws.md (the WebSocket contract): "RFC 6455 host
adapter; the Autobahn release, case list and exception list pinned in the
build; would-block/ENOBUFS is transport-error plus send-failed; no `full`,
no send wait inside `append!`". The J6 row also requires: the pinned
Autobahn release, the case list, the exception list, real jolt↔JVM/Node/
Dart peer fixtures.

## Scope — this slice is DESIGN + HARNESS SKELETON, not the adapter

1. **Pin the Autobahn release**: the upstream Autobahn|Testsuite
   (crossbario/autobahn-testsuite) is wstest-based, Python; pin the version,
   its Docker/venv packaging, and the run command. Record the case list
   (the 500+ RFC 6455 cases) and the exception list format. If the
   upstream suite is unmaintained/dead, evaluate the alternatives (the
   Autobahn|Testsuite fork ecosystem, or a table-driven RFC 6455 harness)
   and recommend one with reasons.
2. **The harness skeleton**: the driver that runs a jolt WebSocket adapter
   (once J6 lands it) against the pinned suite — the adapter's contract is
   `dao.stream.ws` + the codec track's C0–C4 landed contract. Build the
   HARNESS (the case runner, the report format, the exception-list
   machinery) so the adapter plugs in when J6 lands; do not implement the
   adapter itself.
3. **The client-server split**: which side runs wstest (the server) vs the
   jolt adapter (the client), or the reverse — decide and record.
4. **The case triage**: the ~500 Autobahn cases partition into must-pass /
   exception-listed / not-applicable for a dao.stream adapter. Produce the
   triage table with per-case reasons.
5. **Acceptance for the PREP slice**: the harness runs end-to-end against a
   STUB adapter (a trivial echo WS server in the jolt host), the report
   format renders, the exception list loads, and the triage table is
   committed. No jolt adapter, no RFC 6455 conformance claims.
6. **The J6 gate definition**: with the harness real, write J6's
   falsifiable acceptance (which case classes must pass, which are
   exception-listed, the report threshold) into the design doc's J6 row as
   an amendment proposal — for the owner's ruling, not landed unilaterally.

## Constraints

- Branch j6-autobahn-prep off master; commits in repo style; merging is the
  orchestrator's.
- Do not touch src/jolt, src/cljc/yang, src/cljs, or the J5 seat's files.
- The harness lives under native/jolt-file's sibling or a new tools
  directory — propose the path in the findings.
- The design's ruling 7 (§13.5): the mixed Node/OS-lock limitation is
  documented; the J6 row's preconditions are satisfied (C0-C4 landed);
  do not reopen either.
- Remote execution per build-n-test.md (shiva/jupiter are free; the
  safepoint tmux session on jupiter is FINISHED — its slot is free).

Deliverable: branch + findings to
collab/1793096000000-engineer-j6-autobahn-harness-prep.codex.findings.md
(the pin, the packaging, the triage table, the skeleton, the J6 gate
proposal, the repro commands). Begin now.
