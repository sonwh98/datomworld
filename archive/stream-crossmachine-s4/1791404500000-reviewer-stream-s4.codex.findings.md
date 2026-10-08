# Track B Slice S4 — Independent Adversarial Review

Reviewer: Codex (gpt-6.1-sol). Date: 2026-10-08.
Branch: `stream-crossmachine-s4`; baseline: `66756d204d3257afc90712c336e2ddf3e1e4ccd0`.

**Verdict: ACCEPT.** No blocking defects found in the working-tree diff.

## Scope and findings

Reviewed all 20 tracked files changed against the baseline, including the
architecture documentation, implementation, added tests, and the additional
`yin.vm.linker.head.board/stopped?` change. Read the architectural specification,
engineer report, and relevant surrounding transport, link, driver, and host code.
No implementation files were modified during this review.

1. **D1: projection terminal facts.** Attachment identity gates both opened and
   terminal observations. The first terminal closes the ring and records its
   cause; subsequent steps cannot replace it. Traffic-medium end records
   `:dao.stream/end`. Opened remains a historical boolean, independently of
   closure. The two accessors do not expose host objects.

2. **D2: neutral causes and expiry precedence.** The five mappings are correct.
   Lost values are terminal and returned unchanged on subsequent ticks, so a
   close caused by expiry cannot overwrite the recorded expiry. The engineer's
   projection-first deviation is justified by the current implementation:
   `remote/channel-loss!` is called by channel-reader end and deadline expiry;
   `:channel-gone?` alone consequently cannot distinguish them. Projection runs
   before the link step. A closed projection supplies the causal terminal fact;
   a lost link with an open projection identifies expiry. Resolving-phase
   deadline expiry explicitly records `:expired`. Immediate failed attachments
   retain their outcome and map only transport errors to `:unreachable`.
   Name-resolution not-found is correctly distinct from transport disclaimer.

3. **D3: RPC remains the terminal trigger.** Refinement is restricted to
   `:dao.stream.rpc/detached`; nil and other RPC terminals cannot be overridden
   by the dial. The driver's order is connection step, RPC poll, terminal
   observation, then input handling. Thus the observed connection status is
   available before routing input or deciding to reattach. Both driver gates and
   `connect/reattach` use the two-argument predicate. Ended and unreachable
   bindings cannot inherit a detach queue through reattachment.

4. **D4–D6: ephemeral listeners.** Port 0 is admitted only on the serving side;
   `descriptor?` and the attacher still reject it. Positive advertised ports
   retain their meaning when bound on port 0. Advertised port 0 beside a positive
   bind port is refused before binding. A successful ephemeral bind finalizes
   server spec, descriptor, and endpoint descriptor before the acceptor step.
   Missing or nonpositive reported ports release connections, request unbind,
   and return `::port-unreported`. The REPL adopts the finalized port before
   rendering its Serving notice; its provisional URL is nil. The host's bind
   address never replaces the advertised host.

5. **D7: DHT exit.** Stop initiates once, closes follow dials, clears links,
   prevents subsequent dial composition, and prevents a late bound event from
   composing a board. The board is advanced until stopped/refused; its stop line
   is emitted only on transition. `main/stop-tick` advances both compositions
   and requires both completion predicates. JVM, Node, and Dart carry the
   resulting state through the drain and into store closure. `close!` remains
   the fallback and skips an already completed board.

6. **D8–D9: fixture and boundary.** The loopback allocator skips listener ports,
   reports the allocated port, and returns that same port for unbinding. Both
   architectural boundary searches independently returned zero matches: the six
   specified portable source files, and `test/yin/repl/` excluding `host/`.
   This gate is scoped to those paths; transport code/tests legitimately retain
   their transport vocabulary.

## Nonblocking limitations

- Handles accepted before descriptor finalization retain the provisional port
  0, because their descriptor is captured at construction. This is explicitly
  deferred in specification section 6; `endpoint-bound!` promises later handles,
  not retroactive rewriting. Future consumers of early session descriptors must
  address this window. It is not an undisclosed deviation in this slice.
- A stopping follower can still render a loss line promising another dial,
  although the stopping guard prevents that dial. This is an existing wording
  limitation identified by the engineer, not a resource leak or stop failure.
- The projection-first expiry argument depends on the current ownership of the
  channel ring. A future transport that ends it independently must preserve
  explicit expiry provenance rather than infer expiry from `:gone?`.

## Verification and evidence provenance

`git diff --check 66756d20` passed. The boundary checks above were independently
run. `handoff-v2.txt` is absent from the tracked resource set, consistent with
the documented unrelated baseline defect.

The dispatch supplies the completed multi-host evidence: JVM fast 3818 tests /
241717 assertions with zero unexpected failures; JVM slow main 2 tests / 16
assertions passing; Node focused 163 tests / 1033 assertions passing; Dart fast
3625 passing with zero unexpected failures; clean Cljstyle and Kondo checks.
These are supplied results, not claims that this reviewer reran every lane.
The older `target/s4-clj.log` still records the two subsequently corrected board
test failures, and the engineer report predates the supplied Node/style checks;
those older artifacts must not be mistaken for the final dispatch evidence.

An independent focused JVM run initially stopped at a main-test socket bind
blocked by the filesystem/network sandbox, with no assertion failures reported
before the interruption. The rerun with socket permission completed successfully:
**209 tests, 1439 assertions, 0 failures, 0 errors**, across `dao.stream.ws-test`,
`dao.stream.ws-project-test`, `dao.stream.remote-channel-test`,
`yin.repl.connect-test`, `yin.repl.serve-test`, `yin.repl.dht-head-test`,
`yin.repl.main-test`, and `yin.vm.linker.head-board-test` (excluding slow tests).

**Final disposition: ACCEPT. No required revisions.**
