Completed-GMT: 2026-10-06 16:32:27 GMT
Completed-Local: 2026-10-06 23:32:27 +07
Model: claude-fable-5-1 | Coding-Agent: claude | Session-ID: 642b5698-f626-4028-9068-0704d093d269
Role: Lead System Architect, transport boundary mob, round 1

Read-only review; nothing was run or edited. I did not re-read `datom.world.md` or `yin/repl/serve.cljc` this round; I rely on my reading from the section 8.3 review.

## Position summary

| D | Astra proposal | My position |
|---|---|---|
| D1 | Move ws descriptors, seams, sessions, supervision below `dao.stream`; yin keeps name, table, retry | **Agree**, with two limits |
| D2 | TCP WebSocket now; UDP must preserve outcomes and loss semantics | **Agree**; UDP stays deferred for one specific reason |
| D3 | Bounds and supervision inside the stream layer; correlated liveness; reject at cap | **Agree on ownership and correlation; conditional on reject-at-cap; disagree that outbound byte bounds gate the lift** |
| D4 | Auto-advertise for wildcard, override on ambiguity | **Agree**; this changes my earlier recommendation |
| D5 | Contract first, then four slices, byte limits before lift | **Agree with a size limit on the contract and a reorder** |

## D1: transport-independent boundary

**Agree.** The owner's invariant holds today for the linker and fails for the REPL.

- **The follower is clean.** `yin/vm/linker/head.cljc` holds a reader handle and names no transport.
- **The REPL composition is not.** `yin/repl/dht.cljc` calls `head.ws/serve`, `serve-step`, `dial`, `dial-step`, `handle`, `close!` and `loopback?` directly (lines 267, 441-444, 588-616, 684, 726-738). It also carries a `::ws` seam and prints `:yin.head.ws/*` reasons. Swapping TCP for UDP would edit this file.
- **`head.ws` is almost entirely generic.** Only the board name, the `/head` path and the one-entry table are head-specific (`head/ws.cljc:36-61, 213-214`). The rest is "serve a table and name map at an address" and "dial, resolve a name, attach".

**Limits:**
1. No registry or protocol for the neutral composition. `dao/stream.cljc:352-367` forbids growing one back; use the existing host dispatch map keyed on `:dao.stream/type`.
2. Do not migrate `yin.repl.serve` onto it in this epic. It is the second consumer that justifies the generalisation, but moving it is a separate change.

The token grammar and the choice of which descriptor to build stay in yin, in one function.

## D2: TCP now, guarantees for UDP

**Agree: TCP WebSocket now.** UDP is closer than the design implies, but not safe yet.

- **The UDP channel already exists.** `dao/stream/udp.cljc` has `make-attacher`, `projection`, `step!` and `reading-cursor`, the same shape as `ws-project`.
- **The link contract is already channel-neutral.** `dao.stream.remote.md` section 3 says a channel declares only a value domain, a frame budget and whether it is ordered and reliable. Resend, `append-unknown` and "no exactly-once" are in sections 2.4 and 2.5.
- **What blocks UDP is the return-path proof.** Without it, a spoofed source address fills a session table and makes the mirror send answers to a victim. That is design 8.2's point and it stands.

**What the TCP work must not bake in, so UDP swaps later:**
- Status upward is only resolving, attached, lost. Nothing above the boundary may wait for a "closed" event, because UDP has none.
- Sessions are keyed on attachment identity, not on a socket object.
- Loss detection must work without a close event (see D3).
- Budgets are counted in requests and bytes, never in connections.

## D3: ownership of bounds and liveness

**Ownership: agree.** Session cap, step budgets, byte limits and half-open supervision belong in the stream layer. Yin keeps only domain retry (redial after `:source-lost`).

**Correlated liveness: agree, and I withdraw my earlier S3 placement.** I had proposed watching `ws-project/reading-cursor` from `yin.repl.dht`. Astra's objection is right: that puts supervision in yin and counts any inbound frame.

The link already holds the correlated counter. Each outstanding request has an `:asks` count (`remote.cljc:377-390`). I propose one link policy beside `resend-after`:
- `:dao.stream.remote/give-up-after k`: an outstanding request asked `k` times with no answer makes the link run its existing channel-loss path (`remote.cljc:542-555`).
- The reflection then answers the existing `transport-error` with reason `channel-gone`. No new outcome.
- It reads no clock; the caller's polling is the cadence, as spec 2.4 requires.
- It works unchanged on UDP.
- A withholding source still answers `blocked`, so design section 10 is untouched and `dao.lease` keeps source liveness.

**Reject at cap: conditional agreement.** Rejection alone is wrong. The server only answers, so it has nothing outstanding to correlate, and half-open clients are never noticed. A cap that only rejects fills with dead sessions and locks the board until restart.

I accept reject-at-cap if the acceptor also reaps idle sessions: a session with no inbound request for N driver ticks is closed, N being composition data. With that in place I drop my idlest-first eviction. A slow reader that is reaped redials and loses nothing.

**Byte bounds: partial disagreement.**
- Inbound limits at the host edge: agree, before the lift.
- Outbound byte bounds as a gate: disagree. The JVM and Node adapters declare no high-water signal (`ws/jvm.clj:8-10`, `ws/node.cljs:26-31`), and Dart's `.add` buffers. Requiring this blocks the lift indefinitely.
- Substitute that is implementable: a per-session answer budget per tick, an aggregate request budget per step, and idle reaping. Outbound growth on JVM and Dart is then a stated limit.

## D4: advertised host for a wildcard bind

**Agree; I change my earlier recommendation** (require a concrete literal, defer the flag). The owner does not want to handle this detail, so the default should work on a LAN without a flag.

- **Concrete bind:** advertise it.
- **Wildcard bind:** a host effect lists interface addresses. Exactly one address of the bind family that is neither loopback nor link-local: advertise it. Otherwise print no token and name the override flag.
- **Why enumeration over route selection:** route selection needs a connected UDP socket. I believe Dart's `RawDatagramSocket` has no connect, so enumeration is the portable choice; this is unchecked.
- **Shape:** the effect's result arrives as data on a stream, not a callback. Dart's interface listing is asynchronous.
- **Placement:** the shell and the token, not the stream contract and not the linker.
- **Safety:** the address is unsigned and not in the trace. A wrong guess gives an unreachable token, never a wrong head.
- **Wording:** the banner says "advertising", never "reachable at".

## D5: refined slices

| Slice | Where | What |
|---|---|---|
| S0 | `dao.stream.remote.md` section 3 | One page: what every channel composition offers (serve, dial, step, stop, three statuses) and which bounds are composition data. No code. |
| S1 | `dao.stream.ws-project` | `:max-sessions` with reject, idle reap in ticks, per-session catch, per-session and aggregate step budgets |
| S2 | `dao.stream.remote`, `dao.stream.ws` | `mirror-step` budget arity, `give-up-after`, cap on `:pending-frames` |
| S3 | new neutral composition; `yin.repl.dht` | Move the generic part of `head.ws` below the boundary; add stop; adopt the lifecycle gap; bracket IPv6 in the three `socket-url`s |
| S4 | host listeners, shell, design doc | Inbound frame limits on each host; then remove the loopback gates, add auto-advertise and the override, amend the design |

**Reorder note:** S1 and S2 do not depend on the move in S3, so they can run concurrently with it. S0 must stay one page.

## Strongest risks

1. **The move delays the bounds.** If S3's relocation lands first and grows, the board stays unbounded longer. S1 and S2 should not wait on it.
2. **UDP parity is assumed rather than tested.** The neutral composition could acquire connection-shaped assumptions with nothing to catch them.
3. **`give-up-after` false positives.** The count depends on the caller's poll rate (follower default 5000 ticks). Too small a `k` redials a slow but live source. Cost is a redial, not a wrong head.
4. **Dart.** Its real-socket crossing is excluded from the tests today (`head_ws_test.cljc:703`), and it has no built-in frame size limit that I know of.
5. **Outbound growth** remains on JVM and Dart after all four slices.

## Acceptance criteria

- **D1:** `yin.repl.dht` and `yin.vm.linker.*` contain no `ws` namespace, keyword or seam name. The transport is chosen by one descriptor-building function.
- **D1/D2:** the neutral composition's contract tests pass over a second channel with no socket (two rings in one process), proving the composition is not ws-shaped.
- **D2:** no code above the boundary branches on a close event.
- **D3 cap:** connection 65 at a cap of 64 is refused and `:sessions` never exceeds the cap.
- **D3 reap:** a session silent for N ticks is closed and removed, and a new reader can then attach.
- **D3 isolation:** a session whose pass throws is closed; the other sessions are served in the same tick.
- **D3 budget:** total requests answered in one step never exceed the aggregate budget, under a flood on all three hosts.
- **D3 liveness:** an attached dial over a channel that accepts sends and returns nothing yields `:source-lost` after `k` asks, then a redial. A source that answers `blocked` forever is never declared lost.
- **D3 inbound:** an oversize frame is refused at the host edge on JVM and Node; Dart's behaviour is stated.
- **D4:** one eligible interface prints a token with it; zero or several print none and name the flag; the override always wins.
- **Every slice:** `bb test:clj` per iteration, one full three-host `bb test` at landing.

## Consensus trail from this seat

- **Moved toward Astra:** supervision inside the stream layer, correlated progress instead of a traffic cursor, reject at cap, automatic advertise.
- **Held:** idle reaping is mandatory if the cap rejects; outbound byte bounds do not gate the lift; no registry; `yin.repl.serve` is not migrated now; the contract is one page.
- **Open for round 2:** the value of `k`, the idle bound N, and the cap default.
