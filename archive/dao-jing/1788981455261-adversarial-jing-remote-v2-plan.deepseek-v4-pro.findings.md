Completed-GMT: 2026-09-09 19:28:17 GMT
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72
Role: Adversarial Review | Model: deepseek-v4-pro

I've read the plan, the full `dao.jing.remote` source and tests, the v2 RPC core (`rpc.cljc`, `rpc/ws.cljc`, `apply.cljc`, `ws.cljc`, `ws/jvm.clj`, `serving.cljc`, `transit.cljc`), `dao.jing.cljc`, `coordinate.cljc`, `stigmergy_test.clj`, and `dht/node.cljc`. Here is the review, stdout only, ordered by severity.

---

# Adversarial review — dao.jing.remote → dao.stream

Verified against `62ed336`. Overall the plan is a sound, small migration and most of its correctness reasoning survives scrutiny; the defects below are concentrated in the timeout test, the retained-state bookkeeping, and a few documentation seams.

## Finding 1 (high) — Test 3, `request-timeout-throws-and-the-client-stays-usable`, contradicts S4 and cannot pass as specified.

The test (§5.2) serves `{:slow/op (fn [] (Thread/sleep 300) :late) :fast/op (fn [] :now)}` with a client at `:request-timeout-ms 50`, then asserts `call! :slow/op` throws `:timeout-ms` **and** `call! :fast/op` answers `:now`.

**The interleaving that breaks it.** S4 (and D5) state the handler runs in the server's **single ticker thread**. `serving/step!` → `poll-traffic!` → `:inbound-step` → `apply/dispatch-request` → `(apply handler args)` executes synchronously in that thread. So:

- t≈0: the ticker dispatches `:slow/op`, which blocks the ticker for 300 ms.
- t≈50: the slow call times out and throws (correct).
- t≈50: the fast request is deposited into the session's traffic medium, but the ticker is still parked in the slow handler until t≈300, so nothing reads or dispatches it.
- t≈100: the fast call's own 50 ms deadline fires; it throws `:timeout-ms` **too**, never `:now`.

The assertion `call! :fast/op answers :now` is unreachable under the plan's own S4. The intended demonstration — "the client stays usable, and the late completion is discarded rather than misrouted" — cannot be produced this way: a single-threaded server stalled for 300 ms cannot answer a 50 ms call. To pin N6 end-to-end the test needs either a second server/client with a longer timeout, or a slow handler that blocks without occupying the server driver (e.g. a `future`); and the discard itself is *already* pinned at the step level by Phase 1 test 2 (`a completion for a foreign id → discarded`), so the end-to-end discard claim here is redundant anyway.

## Finding 2 (medium) — "A timed-out request stays outstanding" retains full payloads for nothing, and the mechanism is unnecessary for N6.

§5.1 says a timed-out request "stays outstanding and its late completion is discarded by the next call's step." In `rpc.cljc`, `handle-event` already discards a response whose id is *not* in `:outstanding` — it becomes a `:unsolicited-response` diagnostic (`rpc.cljc:363-364`), which `take-diagnostics` drops. Ids are monotonic and never reused (`:next-id` never goes back), so a late response can never be misrouted to a later call regardless of whether the timed-out entry is retained.

The choice to retain instead of `(update :outstanding dissoc id)` therefore buys nothing for N6 and costs real memory: `:outstanding` holds `{:op op :args args}`, and for `:jing/put-content` `args` is `[address payload]` — the full content payload. A client that times out repeatedly (against a slow/dead server, or `materialize!` retries) accumulates every timed-out payload until the attachment goes terminal. The honest fix is to drop the entry on timeout and let the late response classify as unsolicited; N6's observable property is unchanged.

## Finding 3 (medium-low) — `close!` is outside the call lock, and the close-during-call interleaving is neither pinned nor stated.

`call!` runs "under the client's lock" (§5.1), which *does* enforce N5 (one call in flight: two concurrent `call!`s serialize on `:lock`). But `close!` is "`stream/close!` on the handle," guarded only by `content-client`'s **separate** `close-lock` (remote.cljc:84-85, 107-112). So `call!` and `close!` are not mutually serialized, and the plan's "under the client's lock" phrasing invites reading `close!` as covered when it is not.

The interleaving does resolve safely — `stream/close!` sets the handle's phase to `:closed`, the peer close eventually deposits `:ws/closed`→`/detached`, and the in-flight call's next `poll!` (or an `append!` answering `closed`) makes it throw N8/N9 — but no test pins close-during-call (the new tests cover timeout and bind-failure only; `close-idempotent-ops-throw` is sequential), and the lock's actual scope (calls vs calls, not calls vs close) is left implicit. Worth one sentence in D1/N5 plus, ideally, a pin.

## Finding 4 (low) — §9's "carried knowledge" list has one item with no home and is missing one item.

- **On the list but uncommitted:** item 4, "correction 1's measured fact" (`#?(:clj …)` requires become Dart imports, bodies do not), is given "the owner's call" as its destination, and it is **not** in §10's end condition. That is precisely the failure §9 exists to prevent: the plan gets deleted, the owner never acts, and the fact is lost. It needs a concrete home (e.g. the sibling note in `dht/node.cljc:31-37`, or the project memory) and an §10 entry.
- **Missing from the list:** D2's cursor-before-`attach!` ordering. N2 (which §5.4 does move into `dao.jing.md`) only says "waits for `/established`," not *why the cursor is minted before `attach!`* — minting after `attach!` opens a window where `/established` is deposited before the cursor and the wait hangs until `:connect-timeout-ms`. This is the client-side twin of the ordering `serving.cljc:62-64` documents on the server side ("minted the reader cursor before acknowledgement"), and it is exactly the rediscover-the-hard-way knowledge §9 is meant to preserve.

## Finding 5 (low) — D3's "seed of the stepped client" overstates; `call-step` is blocking-shaped.

`call-step` filters completions for one id (`(first (filter #(= id …) completions))`), which is the correct shape for a *blocking* client with one call in flight — the JVM loop's shape. The non-blocking stepped client (deferred to async hydration, §8/§9) needs multi-id dispatch (`step` delivering completions for *all* outstanding requests), not a per-id filter. So `call-step` is genuinely portable and genuinely tested on all three hosts (Phase 1 drives it by hand), but its production consumer on cljs/cljd is absent in this plan, and "the seed of the stepped client" is a generous label for a step the stepped client would not reuse as-is. Not a defect — the plan is honest about the deferral — but the framing should say "seed of the blocking driver's loop."

## Finding 6 (low) — D8's split is right, but "a require swap in two files" understates a decode-side semantic change.

Verified: `dht.node`'s v1 reach is exactly `transit/encode`/`decode` at `node.cljc:59,65` and `node_test:139` (requires at `node.cljc:30`, `node_test:19`); no other `dao/jing/dht` file touches transit. Splitting is correct — it's codec coupling, not stream coupling, and this plan should not inherit a UDP test suite.

But it is not a drop-in swap. v1 `dao.stream.transit/encode`/`decode` delegate straight to cognitect (`transit.clj`), whereas `dao.stream.transit` runs `ensure-portable!` on **both** encode and decode. On decode, v2 **rejects** tagged values (`uuid`/`bigint`/`bigdec`/`uri`/`quoted`/`link`) that v1's cognitect would have **accepted**. So D8's "check that every message map is portable" must cover the *inbound* direction — a peer sending a `~z` bigint or `~#u` UUID for `:rpc` would newly throw under v2 — not just the messages `dht.node` itself sends. That check is the real work of D8; "require swap" framing risks it being done on the happy path only.

---

## Where the plan is right

- **Hunt 2 — no collapse.** The absence/nil/timeout/transport-error distinctions survive the whole v2 path: `valid-presence-envelope?` demands exactly `#{:found? :value}`; `apply/response?` demands exactly one of `:ok`/`:error` (with nil a valid `:ok` value); `call-step`'s id filter is key-based, not value-based. A stored `nil` (`{:found? true :value nil}`), absence (`{:found? false :value nil}`), malformed envelope (throw), timeout (N6 map), terminal loss (N8 reason), and server error (N9 map) are pairwise distinct and pinned by `network-presence-envelope-test`, `local-malformed-envelope-test`, `local-present-then-absent-test` — all kept verbatim.
- **Hunt 3 — D2's establishment wait is sound.** The wait consumes only lifecycle events (`/established` or terminal), which the first call would otherwise skip past as non-response traffic; the cursor-before-`attach!` mint closes the accept race. The one nuance — the wait *can* succeed against a server that completes the WS acceptance handshake but never serves content — is the same failure mode as v1 and falls correctly to `:request-timeout-ms`, not a defect.
- **N5 is enforced, not merely stated:** the `:lock` on the client is real JVM `locking`, and `call!` runs under it.
- **D1's "waiting in the host" is honest**, not a rationalization: v1 already polled (`rpc.client:96`), each `poll!`/`request!` returns, and the JVM loop is the `dao.stream.md`-sanctioned driver.
- **The eight corrections are accurate**, including the cljd reader-conditional split (`#?(:clj …)` requires → Dart imports, bodies not emitted), the 13+6 test split, the four `stigmergy_test` v1 sites, and the DHT reach measurement.

**Net:** the migration's design — blocking driver over a portable step, no rebind, `default-handlers`/`content-client`/`coordinate` untouched, `serving` + `:inbound-step` for the server — is right. The load-bearing issues are the unwritable timeout test (Finding 1), the unnecessary retention leak (Finding 2), and three smaller documentation/test seams.
