Completed-GMT: 2026-09-03 08:53:00 GMT
Completed-Local: 2026-09-03 15:53:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 82ACFFF4-F684-479B-B869-3DA23A491A07

All seven snapshot files under `/private/tmp/datom-staged-snapshot.BpY9uJ/docs/design/` were read in full. No working-tree file was read as a substitute, and nothing was edited, staged, or committed. Line numbers below refer to the snapshot files.

**Verdict: not ready for unconditional sign-off.** The contract core is sound and internally consistent. Four defects sit at the boundary between the contract and the WebSocket specification, and each one would produce a wrong runtime behaviour rather than a documentation wart. All four are cheap to fix in text. Sign-off should be conditional on findings 1 through 5.

## Architectural defects

1. **Identity and descriptor are conflated, and serving breaks the identity invariant.** `dao.stream.md:81-83` requires one identity that is "the same through every handle" on a logical stream. `dao.stream.md:200-203` then says that identity "is available as a descriptor". `dao.stream.ws.md:19-25` says serving a ring buffer makes its descriptor "carry an address", and `dao.stream.ws.md:38-42` makes the ws handles answer the served stream's descriptor. So the creator's ring-buffer handle and a ws client handle on the same logical stream answer different descriptors, and two endpoints serving one buffer on two ports answer two more. Either the invariant is false or a served stream is a different logical stream, which contradicts the "serving makes any stream remotely attachable" framing. Correction: separate a transport-independent identity value from the descriptor that carries reachability. The descriptor key-set gate at `dao.stream.implementation-plan.md:267-272` should settle that identity as its own key, and `cursor-mismatch` should compare that value, not the descriptor. Severity: high.

2. **The deposit model has an unstated mint-before-attach discipline, and the plans violate it.** A `:dao.stream/newest` cursor is positioned after the newest value at mint time, per `dao.stream.md:456-458`. `yin.repl.implementation-plan.md:518` mints the traffic cursor after `attach!` in the same list as the attach. On the JVM the open callback runs on another thread, so `:ws/opened` can land before the cursor exists and is never observed. The same race exists for the server's offer slots and for the lifecycle medium at `yin.repl.implementation-plan.md:570-574`, where http-kit binds synchronously and can deposit `:bind-succeeded` before the composition holds a cursor. Correction: the contract or ws spec should state that a reader anchored at `newest` must mint before the operation that can cause deposits, and both plans should reorder. Severity: high.

3. **The acceptance handoff introduces a second polling actor with no named driver, cadence, or clock.** `dao.stream.ws.md:205-270` has an "endpoint driver" that polls acknowledgement media, sends the wire accept, and applies "composition-selected admission expiry". Phase 4a at `dao.stream.implementation-plan.md:304-327` names no step function for it, and 4c at lines 423-446 lists lifecycle ownership but not this. If the driver self-schedules inside the transport it is the relocated callback that 4b at lines 359-364 rejects. Correction: 4a should deliver an explicit transport-owned `endpoint-step` taking `now`, called by the composition's driver, with expiry as a policy argument. Severity: medium-high.

4. **"Establishing" is ambiguous and a fast client will get itself killed.** `dao.stream.ws.md:466-469` says an attachment "still establishing" answers `full`. `dao.stream.ws.md:424-425` says the client is not open merely because the upgrade completed. `dao.stream.ws.md:521-523` makes a value frame before accept a protocol failure closed with 4002. If a client's `append!` returns `ok` after the upgrade but before the accept frame arrives, the server tears the connection down. Correction: define established as "the `:ws/accept` frame has been received", and state that `append!` answers `full` until then. Severity: medium-high.

5. **Locally initiated closes do not say whether `:ws/closed` is deposited locally.** `dao.stream.ws.md:486-487` says the peer deposits the departure. The decode-failure path at lines 523-533 deposits `:ws/error` then closes with 4002. The RPC client treats `:ws/error` as survivable and retains requests, per `yin.repl.implementation-plan.md:218-220`. If the local close deposits nothing, every outstanding request hangs. The same question applies to `close!`, to deposit-failure teardown at `dao.stream.ws.md:352-361`, and to the 4004 close after a disclaim at lines 537-541, where it is unclear whether `:ws/closed` follows `:ws/not-found`. Correction: state that every connection end, local or remote, deposits exactly one lifecycle event, and give the order for the disclaim case. Severity: medium.

6. **The master document contradicts the boundary rules the contract and ws spec rely on.** `datom.world.md:78-80` says an adapter entry "is handed nothing to invoke", but `dao.stream.md:412-414` grants the boundary one action, closing its host resource. `datom.world.md:100-102` says host events "need no bespoke envelope", but `dao.stream.ws.md:118-129` defines one and the REPL plan defines another at `yin.repl.implementation-plan.md:552-557`. `datom.world.md:89` and `dao.stream.md:416-417` say the boundary judges nothing, yet `dao.stream.ws.md:523-533` judges a frame malformed, withholds it, and closes. The ws behaviour is right. Correction: amend `datom.world.md` to carry the observably-gone exception and to permit transport-owned envelopes, and state in the ws spec that wire-protocol enforcement is transport machinery below the adapter, not adapter judgement. Severity: medium.

7. **Lifecycle vocabulary is owned in two places.** `yin.vm.implementation-plan.md:467-478` fixes the socket-free RPC client's transitions in terms of `:ws/opened`, `:ws/closed`, and the rest, and claims this needs no transport dependency. `yin.repl.implementation-plan.md:238-240` and `457-467` say the `:decode` in `dao.stream.rpc.ws` is where ws knowledge lives and that it "converts" those events. Either the core knows `:ws/` keywords or decode translates to a neutral vocabulary that nobody has named. Correction: name a transport-neutral lifecycle vocabulary owned by `dao.stream.apply`, and make decode the only place `:ws/` appears. Severity: medium.

8. **Dynamically received handles have no portable way to learn their surface.** `dao.stream.md:212-215` says an interpreter that receives a handle off a stream "simply uses it". `dao.stream.md:376-380` says calling an undeclared operation is the host's business, which on all three hosts means an exception at operation time. That sits uneasily with lines 109-111 reserving exceptions for assembly defects. Correction: either declare that handles passed across in-memory streams travel with their surface declaration as data, or state explicitly that dynamic handle receipt is composition-scoped and the composition must know the surface. Severity: medium, acceptable if stated.

## Implementation gaps

9. **Attachment freeze state must live in the ring buffer's single atom.** `dao.stream.md:565-567` freezes what a closed attachment can observe at the stream position at close time. `dao.stream.implementation-plan.md:184` promises one coherent state and one deref per `next`. If attachment lifecycle sits in a separate atom, `next` needs two derefs and the linearizability oracle at lines 130-159 has nothing coherent to check. Correction: state that attachment state and the frozen tail are part of the shared logical-stream state.

10. **No client deadline, so a server-side gap hangs the client forever.** `yin.vm.implementation-plan.md:440-445` has the server record skipped requests on a request-medium gap but it cannot answer ids it never saw. `yin.repl.implementation-plan.md:208-210` places timeouts with the driver, and R5 fact 2 at lines 588-589 says lost requests are reported "rather than timing out". Nothing in R3 through R5 sets a deadline. Correction: R3 must specify a driver deadline per outstanding request, or the shared request medium must be per attachment.

11. **Ended detection relies on custom close codes that the chosen JVM server library may not support.** `dao.stream.ws.md:293-298` and `537-541` make code 4000 the only signal that a served stream ended. Not-found has an in-band disclaim frame as its primary signal, but ended has none. Correction: verify per host library that codes 4000, 4002, and 4004 with reasons can be sent and received. Better, add an in-band `{:ws/frame :ws/end}` frame before the close, making the close code secondary, which is symmetric with disclaim.

12. **Additive registration does not exist on Dart.** `dao.stream.ws.md:148-155` builds a safety property on multi-listener registration. A Dart `WebSocket` is a single-subscription stream, so a second listener throws. The cljd ws transport is owned by the REPL plan at `yin.repl.implementation-plan.md:105-107`, which inherits this. Correction: restate the property as "the adapter is the sole subscriber and the raw socket never escapes the handle", which holds on every host.

13. **Server-side descriptors need an advertised address.** `dao.stream.ws.md:38-42` has accepted-connection handles answer the served stream's descriptor, and lines 384-387 require host and port. A server bound on all interfaces has no reachable host to put there. Correction: the endpoint constructor takes an advertised host and port as composition data.

14. **Phase 3 is not host-agnostic.** `dao.stream.implementation-plan.md:477-480` says Phases 1 to 3 have nothing host-specific, but Phase 3 at lines 241-252 needs a Transit implementation per host. `yin.repl.implementation-plan.md:615-616` points at the legacy cljd codec, which lines 250-252 of the stream plan forbid depending on. Correction: mark Phase 3 as the first host-specific phase and name the cljd codec that `dao.stream.transit` will use.

15. **Admission declaration omits the value domain.** `dao.stream.ws.md:344-348` requires the destination to carry every envelope, including a host-local handle in offers. The assembly check at `dao.stream.implementation-plan.md:61-69` receives only the retention nature. Correction: the declaration carries retention mode and whether the medium holds references or encodes.

16. **Where a failing ws `close!` surfaces is unnamed.** `dao.stream.md:542-546` requires a transport to name the channel. `dao.stream.ws.md:58` declares the outcome set and says nothing about the channel.

17. **Undelivered `:ws/path` semantics.** `dao.stream.ws.md:380-382` says the key set is TBD, while lines 402-403 define `:ws/path` normatively. Exact-match versus normalisation of trailing slashes and percent-encoding is unstated. `yin.repl.implementation-plan.md:384` adds a REPL-level normalisation on top.

18. **Stale cross-references.** `yin.repl.implementation-plan.md:155-156` cites `dao.stream.ws.md:160` for the hidden-inbox rule, which is at lines 185-190. Line 517 cites a "boundary-level announcement stream" at `dao.stream.ws.md:140-143`, which does not exist in the snapshot. `yin.vm.implementation-plan.md:328` cites plan lines 139-141 for content now at 170-174. These are evidence the three plans were revised against different versions of the ws spec.

19. **D3's fatal gap is unobservable.** `yin.repl.implementation-plan.md:378-381` says a gap on the `/repl` stream is fatal, but nothing reads that stream, so no reader can ever observe the gap.

## Accepted or deferred risks

- **Hidden unbounded outbound buffering.** `yin.repl.implementation-plan.md:607-613` admits `full` may be undetectable on some hosts. Needs one sentence in the ws spec naming this as an accepted asymmetry.
- **Cross-attachment denial of service on shared media.** One flooding client evicts other clients' requests from the shared 8192 medium. Acceptable behind loopback, but should be in the divergence register.
- **A lost request may still execute on the shared shell.** R5 reports requests lost on disconnect while D4 uses a single shell. State it.
- **Gap livelock under fast eviction**, cursor forgery, coexistence drift, a practically clj-only concurrency oracle, process-restart attachment-id reuse, and the exploratory note's "no unbounded ring" overclaim should be recorded or corrected as later work.

## Properties that pass

- The seven-operation outcome algebra is closed, and every consumer plan is total over it.
- No operation waits on any host, and no promise leaks.
- Eviction never waits on readers, and reject mode is correctly shown to deadlock without destructive take.
- The acceptance handoff cannot lose a capability: capacity-one slots and identity-matched acknowledgements close that hole.
- Ownership at teardown points is explicit and named.
- No registry, no load-time side effects, and host-composed dispatch is ordinary data.
- The ws handle has no reader surface and the hidden inbox is gone.
- Duplex correctness holds; client writes do not reach other attachments by default.
- Step-shaped drivers and the `:make-stream` capability design are sound.
- Exhaustive-and-declared conformance via manifests is checkable.

Findings 1, 2, 4, and 5 change runtime behaviour and should be fixed in text before Phase 4a starts. Finding 3 should be settled before 4a is scoped. The rest can ride the descriptor and wire gates already in the plan.
