Completed-GMT: 2026-10-08 07:14:44 GMT
Completed-Local: 2026-10-08 14:14:44 ICT

# Track B Slice S4 — Architectural Sign-Off

Role: Lead System Architect. Reviewer: Codex.
Branch: `stream-crossmachine-s4`; baseline: `66756d204d3257afc90712c336e2ddf3e1e4ccd0`.
Scope: the 20 tracked working-tree changes, the six requested design/review inputs,
and surrounding link, driver, lifecycle, and host binding code. No implementation,
test, or design files were edited, staged, or committed by this review.

## Architectural evaluation — foundational invariants

| Invariant | Evaluation |
|---|---|
| No hidden global state | PASS. Terminal facts belong to the composed projection/dial; the finalized descriptor belongs to the endpoint; stopping belongs to the shell's node. The fixture allocator reads its explicitly supplied network value. No singleton registry is added. |
| No implicit control flow | PASS. Closure, binding, and shutdown advance through explicit state transitions and stream observations. The caller retains and supplies each stepped state. |
| No callbacks into application interpretation | PASS. Existing host adapters deposit lifecycle data. S4 interprets that data in driver steps; it adds no application callback or waiter. Host socket seams remain below the channel boundary. |
| No shared mutable application state | PASS. S4 extends existing explicitly owned transport state atoms and immutable composition values; it creates no cross-interpreter mutable coordination store. Endpoint descriptor mutation stays inside the existing endpoint resource, and upper layers receive data. |
| Interpretation and execution remain separate | PASS. Hosts report binding/closure; the channel interprets transport facts into neutral causes; RPC determines terminality; the REPL chooses its notice and reattachment policy. |
| Graphs constructed explicitly from tuples | PASS. No implicit graph model, graph assumption, or new graph storage is introduced. |

**Driver-paced, clock-free execution: PASS.** The changed projection, channel,
connect, serve, and DHT interpreters read no clock and schedule no work. Deadlines
and shutdown grace use supplied `now`. Clock reads and cadence remain in the
existing host branches of `main`; the DHT stop API neither waits nor sleeps.

**Sole higher-level channel boundary: PASS.** `dao.stream.remote-channel` remains
the transport composition seam for the six specified portable consumers. The
neutral causes add explanatory data without exposing close codes or transport
events. Source cursors, anchors, outcomes, and gap semantics are unchanged;
`rpc.cljc`, `apply.cljc`, and `remote.cljc` are unchanged. No server/client
privilege is added, and channel release does not close table-owned media.

## Architectural evaluation — D1 through D9

| Decision | Evaluation |
|---|---|
| D1 — projection facts | ACCEPT. Attachment identity gates observations. The first terminal records `:cause` and closes the ring; the closed guard prevents replacement. Medium end records `:dao.stream/end`. `:opened?` is historical and remains independent of closure. Both accessors expose plain data. |
| D2 — neutral dial causes | ACCEPT, including the projection-first deviation discussed below. All five mappings are present. Immediate transport attach failure is `:unreachable`; non-transport failure may have nil cause. Named not-found retains its actual outcome without falsely claiming unreachability. Lost dials are returned unchanged on later ticks. |
| D3 — RPC refinement | ACCEPT. Only RPC `detached` is refined: ended becomes ended; unreachable and unopened expiry become transport-error; opened expiry remains detached. Nil and other RPC terminals cannot be overridden. The connection steps before RPC polling and terminal observation, before input handling. Reattachment and both driver gates use the connection/client predicate. |
| D4 — ephemeral composition | ACCEPT. Advertised port 0 is provisional, binds through the existing host seam, and finalizes spec and descriptor from the positive integer port in `:bind-succeeded`. Advertised port 0 beside a positive bind port is refused. Missing/nonpositive reported port releases connections, requests unbind, and yields `::port-unreported`. The advertised host is preserved. |
| D5 — endpoint admission/finalization | ACCEPT. `servable-descriptor?` admits port 0 only for serving; the dial descriptor gate remains unchanged. The endpoint stores its descriptor in existing state, and later accepted handles use the finalized descriptor. Changes to `ws.cljc` are confined to D5. |
| D6 — REPL exposure | ACCEPT. The unsupported-ephemeral refusal is removed. URL is nil while port 0 is provisional, and the bound port is adopted before the Serving notice. The startup banner describes an ephemeral bind without advertising port 0. |
| D7 — DHT board shutdown | ACCEPT. Stop initiates once, closes follow dials, clears links, prevents redial and late board composition, and continues stepping the board. Completion and its notice derive from status. `stop-tick` advances both board and endpoint and requires both completion predicates. JVM, Node, and Dart retain the returned state through store closure; `close!` is the bounded drain's fallback and skips completed boards. |
| D8 — fixture allocation | ACCEPT. The allocator chooses an unused listener port from explicit fixture state, registers and reports it, and returns the same port for unbinding. Existing positive-port behavior is preserved. |
| D9 — strict boundary gate | PASS. Independent searches found zero `:ws/`, `ws-project`, or `dao.stream.ws` matches in the six specified portable source files and in `test/yin/repl/` excluding `host/`. This is the specification's scoped gate; transport implementation/tests legitimately retain transport vocabulary. |

### Resolution of the engineer's architectural question

**Approve closed-projection-first cause selection.** `remote/channel-loss!` sets
`:channel-gone?` for both reader end and deadline expiry. Therefore interpreting
every `:gone?` as expiry, as the brief's sample did, would erase ended, dropped,
and unreachable causes. `ws-project/dial-step!` projects first, then steps the
link. With current dialing-ring ownership, a closed projection supplies the
observed cause; a gone link with an open projection identifies expiry. Resolving
deadline expiry is explicitly stamped `:expired`. Later close events cannot
rewrite an already lost dial. This implements D2's causal intent correctly.

Routing resolving failures through the same helper is also approved. The test
blackhole is justified because it actually misses the RPC end answer and tests
the refinement. The stopping guards' placement, updated port-zero expectations,
three-argument stop tests, nil-safe URL guard, and D5 docstring-driven line-count
overrun are consistent with the specified behavior and scope. No compatibility
shim or unrelated abstraction is introduced.

## Multi-host portability and verification

The portable changes use maps, keywords, booleans, explicit state, and existing
stream operations. They add no host object to a portable result. The JVM host
reports `http/server-port`, Node reports its bound address port, and Dart reports
`HttpServer.port`. The host-specific shutdown branches preserve the existing
CLJD-before-CLJ reader-conditional discipline where applicable.

Independent checks in this review: `git diff --check` passed; D9 searches passed;
the removed `ephemeral-port-unsupported` and `queueable-terminals` vocabulary has
no remaining source/test matches. The tracked UCF resources contain no
`handoff-v2.txt`, consistent with the unrelated baseline defect.

Fresh focused JVM execution across `ws-test`, `ws-project-test`,
`remote-channel-test`, `connect-test`, `serve-test`, `dht-head-test`, and
`head-board-test` (excluding slow tests) completed **175 tests / 1135 assertions,
0 failures / 1 error**. The sole error was `Operation not permitted` while the
existing `the-board-crosses-a-real-loopback-socket` test tried to bind in the
sandbox. Rerunning `yin.vm.linker.head-board-test` with socket permission passed
**14 tests / 123 assertions, 0 failures / 0 errors**. Thus the only failure of
execution in this review was environmental and was resolved by that rerun.

The adversarial report independently records **209 JVM tests / 1439 assertions,
0 failures / 0 errors**, including fast `main-test`. Its dispatch supplies these
additional results, which this sign-off treats as supplied evidence rather than
as fresh runs:

| Lane | Supplied evidence |
|---|---|
| JVM fast | 3818 tests / 241717 assertions; zero unexpected failures, with the known missing UCF v2 fixture defect. |
| JVM slow main | 2 tests / 16 assertions; zero failures/errors; also corroborated by `target/s4-main-slow.log`. |
| Node focused | 163 tests / 1033 assertions; zero failures/errors. This is focused coverage, not a full Node-suite result. |
| Dart fast | 3625 passing; zero unexpected failures, with the documented UCF fixture limitation. |
| Formatting/lint | Later dispatch reports clean Cljstyle/Kondo; the engineer independently reports changed-file Kondo 0/0 and unrelated repository-wide findings. |

The older `target/s4-clj.log` still contains two subsequently corrected board
expectation failures, and `target/s4-cljs.log` contains the earlier missing-module
failure. Neither proves the later supplied result. This review does not assert
that every full repository lane is unconditionally green or independently rerun.

## Defect / gap findings

**No blocking S4 architectural defect found.** Accepted limitations:

1. Handles minted before driver-observed descriptor finalization can retain port
   0. The specification explicitly defers that window; finalization promises
   later handles, not retroactive descriptor mutation. Process tests that discover
   an OS-assigned port from stdout are also deferred.
2. A stopping follower can print a loss notice promising another dial even though
   stopping guards prevent that dial. This is a nonblocking diagnostic wording
   defect, not a redial or resource leak.
3. Cause precedence depends on the current dialing-ring ownership and stepping
   order. A future transport that closes/projects the ring independently must
   preserve explicit expiry provenance and re-establish the ordering argument.
4. Multi-host evidence includes supplied results and known baseline failures;
   full Node-suite success is not established by the focused result. Architectural
   acceptance does not certify completion of every repository-wide landing gate.

This sign-off covers S4's scoped architecture. It does not lift transport host
bounds gates or resolve other deferred host-seam limitations in `dao.stream.ws.md`.

## Sign-Off Verdict: ACCEPTED

D1–D9 and all six foundational invariants are satisfied within S4's stated scope.
The substantive D2 deviation is approved. No architectural revision is required.
