Completed-GMT: 2026-10-07 21:23:25 GMT
Completed-Local: 2026-10-08 04:23:25 ICT

# Track B Slice S3b Architectural Sign-Off

Role: Lead System Architect, Codex.
Repository: `/Users/sto/workspace/datomworld-stream-s3a`.
Branch: `stream-crossmachine-s3b`; HEAD/base `802d9ee2dfb32ff1c3552427d8e2963225ae4edc`.
Scope: the current uncommitted S3b implementation, including remediation and the new `test/yin/repl/net_fixture.cljc`. Review was read-only apart from this requested report; no implementation, tests, design documents, staging or commits were changed.

**Sign-Off Verdict: ACCEPTED.** The slice preserves the architectural boundary and satisfies D1–D10 under the clarifications below. This is architectural acceptance of the reviewed working tree, not certification that every repository landing check is green.

## Architectural Evaluation — foundational invariants

| Invariant | Evaluation |
|---|---|
| No hidden global state | PASS. Endpoint, dial, table, bounds, cursors and REPL state are explicitly composed and threaded. Namespace constants are immutable policy data; no new global registry or mutable singleton is introduced. |
| No implicit control flow | PASS. `serve-step`, `dial-step`, REPL `step` and `connect/step!` expose advancement. Driver `poll-remote` retains the returned connection and supplies `now`. Refusals and lifecycle conclusions are data. |
| No application callbacks; callbacks become stream events | PASS. REPL code installs no transport callback. Below the boundary, host acceptance remains endpoint machinery, and the lifecycle deposit closure appends facts for later interpretation. It does not invoke REPL evaluation. |
| No shared mutable application state | PASS. The shared shell is a serially threaded value, not a shared atom. Communication uses stream handles. Existing resource-local mutation in stream/projection internals remains below the boundary; this is not a claim that stream implementations contain no mutation. |
| Interpretation and execution remain separate | PASS. Channel composition moves and mirrors values; the REPL interpreter reads requests and emits correlated answers. Neither transport nor mirror acquires eval policy. The existing VM/host execution boundary is unchanged. |
| Graphs constructed explicitly from tuples | PASS. This slice introduces no implicit graph, graph authority, or inferred topology. Its table and stream composition are explicit data. |

Driver-paced, clock-free execution also passes: `remote-channel`, `serve` and `connect` read no wall clock and install no scheduler. Liveness and stop bounds use caller-supplied `now`; clock reads remain in host runners. A missing dial-time `now` deliberately disables initial probe expiry and is documented/tested; production driver open/reattach paths supply `:last-tick`.

`dao.stream` remains the communication abstraction, with `dao.stream.remote-channel` the sole transport-composition entry point for these REPL consumers. URL syntax is REPL policy; concrete descriptors, endpoint acceptance, projections and connection teardown stay below that entry point. RPC/apply envelopes remain transport-free. The mirror preserves source cursors and outcomes; channel loss is not fabricated as a source gap. Serve/dial are establishment roles, not protocol authority. Stable REPL identities and name-based head-board lookup remain distinct conventions.

## Architectural Evaluation — D1 through D10

| Decision | Evaluation and evidence |
|---|---|
| D1 — table surfaces | PASS. `valid-entry?` requires a non-empty set contained in `#{:reader :writer}` and checks each declared nature against the handle. `table-refusal` returns `::invalid-table` with identity/surface detail. REPL requests are writer-only and answers reader-only; a narrower surface and the full two-nature surface are supported. |
| D2 — identity dialing | PASS. `valid-target?` requires a non-empty vector of distinct, non-nil identities. Invalid targets are refused before connection. `attach-identities` terminates on sequence exhaustion, not the truthiness of an identity, so no suffix silently disappears. One initial attachment and subsequent reflections share one channel. `now` is recorded before establishment/probes. Immediate `:attached` means deferred confirmation; absent identities subsequently report not-found. Partial failure closes partial handles and the connection. Single- and two-arity handle accessors preserve the intended distinction. |
| D3 — writable-append ambiguity | PASS. An accepted append means outbound acceptance, not evaluation. RPC retains `full` requests for retry and confirms evaluation only through a correlated answer read from answers. Connect composes no event writer. The five-case reflection test proves acceptance-before-source-progress, refusal, and timeout after an append actually crossed. Unknown effect remains unknown; no exactly-once or automatic replay claim is introduced. |
| D4 — draining stop | PASS. Generic drain defaults to 0; REPL composes 500 ms. Stop closes answers then requests, marks the channel stop `:ended?`, and ceases request evaluation. Accepted sessions keep answering until grace expiry or early departure. Release uses `ws/close-ended!` (code 4000) before generic idempotent close, and records `:released`; host confirmation grace starts at release. Gap/end during drain releases sessions and requests unbind once before terminal `::unconfirmed`, preserving `::unbind-failed` on failure. Regression tests pin +499/+500, end delivery during drain, early/no-session release, and gap/end teardown. |
| D5 — detach versus close | PASS. `detach!` closes only the connection, leaves reflections open and the dial steppable, and marks detaching. Later stepping exposes channel-gone. `connect/close!` uses detach; reattach fully closes the old dial before composing a fresh pair. RPC rebind retains the response cursor and allocator. |
| D6 — bind versus advertise | PASS. REPL spec carries both `:bind-host` and `:bind-port`; channel `serve` passes each override to the host, falling back to advertised host/port. `descriptor-of` uses advertised values only. Port 0 survives the bind fallback when an explicit positive advertised port is supplied. Unequal-port and wildcard-host regressions preserve the distinction. |
| D7 — diagnostics and attachment | PASS. `diagnose` increments the count independently of the bounded last-eight vector. Notices use count deltas. `attachment` is nil before attachment and after full close; retaining the identifier after detach/loss is correct historical observability, not a claim of connectivity. |
| D8 — serve interpreter | PASS. `serve` owns local media, serial REPL interpretation and notices; channel composition owns acceptance, lifecycle and stop. Status derives from channel status, and `advance-requests` runs only while `:running`. Pending-answer retry does not re-evaluate the request. |
| D9 — connect spec and value stepping | PASS. URL parsing returns `{:host :port :path}`. Open dials the two identities with `now`; step returns an updated connection, retained by the driver. Terminal notices derive from RPC `:terminal`; lower-level dial loss is diagnostic and cannot override it. Reattachment is limited to detached clients. |
| D10 — strict boundary gate | PASS. Firsthand production and recursive REPL-test scans found zero `:ws/`, `dao.stream.ws` or `ws-project` matches outside `test/yin/repl/host/`. Node adapter tests now reside under that host directory; DHT tests use the shared lower-layer loopback fixture. No exemption by arbitrary filename is needed. |

## Architectural clarifications and defect / gap findings

No blocking implementation defect was found.

1. **D2 optional nil keys:** the original specification says “both keys present” are refused; implementation treats a nil optional target as absent. I accept the value-based contract: exactly one meaningful target, with every requested identity non-nil. Thus `:name nil` beside valid identities is allowed. This resolves the prior review's optional suggestion explicitly; it does not permit nil elements or simultaneous non-nil targets.
2. **D4 lifecycle loss:** immediate teardown on gap/end during drain is approved. Continuing to drain after losing lifecycle authority could leave resources owed by a terminal server. `::unconfirmed` accurately distinguishes local teardown/requested unbind from observed host completion; `::unbind-failed` never certifies external listener release.
3. **D6/D10 specification alignment:** this sign-off accepts bind-port alongside bind-host, and exempts host-adapter tests under `test/yin/repl/host/` on every host, as the current task specifies. Older architect prose naming only JVM host tests and omitting bind-port is superseded for this slice. Prior documents remain unchanged in this read-only review.
4. **Known terminal-cause limitation:** code 4000 is emitted, but the unchanged projection collapses ended/closed events to channel loss. A client that misses the source end during the finite drain can still observe detached; 500 ms is a composed opportunity, not a delivery guarantee. Cause propagation remains the explicitly deferred S4 work, not an S3b blocker.
5. **Validation gap:** full repository green status is not established. Engineer evidence reports the missing `handoff-v2.txt` fixture failing JVM/Dart full lanes and baseline tree-wide lint findings; full Node validation was not established there. The later re-review closes focused Node/Dart and formatting gaps, not the full-lane landing obligation. These limits must remain visible when landing.

## Multi-host portability and test evidence

The changed core is `.cljc`, with host-specific exception forms ordered `:cljd`, `:clj`, `:cljs`. Transport adapters remain host-owned. No new Java/JavaScript/Dart API leaks into the portable REPL logic. Reviewed loops and state construction preserve portable collection/value behavior.

Firsthand verification in this sign-off:

- `clojure -M:test -n dao.stream.remote-channel-test -n yin.repl.connect-test -n yin.repl.serve-test -n yin.repl.dht-head-test`: **91 tests, 655 assertions, 0 failures, 0 errors**.
- Recursive D10 test scan and production scan: **zero matches**.
- `git diff --check`: **clean**.
- Confirmed no diff in `ws.cljc`, `ws_project.cljc`, `remote.cljc`, `rpc.cljc` or `apply.cljc`.

Evidence attributed to the independent adversarial re-review (`1791400000000-reviewer-stream-s3b-rereview.codex.findings.md`), not rerun here:

| Lane/check | Recorded result |
|---|---|
| JVM focused set including real-wire test | 92 tests, 667 assertions, zero failures/errors |
| Node focused set including relocated host.node tests | 92 tests, 662 assertions, zero failures/errors |
| Dart focused portable set | 89 tests passed, exit 0 |
| Changed-file cljstyle / clj-kondo | clean / zero errors and warnings |

The engineer additionally reports 158 affected JVM tests / 1,135 assertions and slow main tests 2 / 16 passing before remediation. Those support integration continuity but are not fresh certification of the final tree's process-level tests. The focused three-host re-review is adequate evidence for the portability of the changed channel and REPL behavior; it does not establish every host pairing over a real network.

Read and evaluated the master architecture, remote design, original architect specification, engineer report including remediation, and ACCEPT re-review; traced the channel state machine, REPL consumers, driver updates, relevant tests, and unchanged projection/RPC seams.

## Sign-Off Verdict: ACCEPTED

Architectural acceptance covers the inspected S3b working tree and the explicit clarifications above. No architecture revision is required. Repository-wide landing checks and documented baseline failures remain separate obligations; this review neither commits nor authorizes an unverified claim of a fully green repository.
