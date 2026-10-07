Created-GMT: 2026-09-09 19:29:07 GMT
Created-Local: 2026-09-10 02:29:07 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126 (resumed)
# Task: revise the dao.jing.remote plan — round 2
Role: Lead System Architect

Both reviews are in. **Revise your plan in place** at
`collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`
(rewrite the file; no changelog section). Write no other file.

- Routine, `gpt-6-astra`: `collab/1788981455261-review-jing-remote-v2-plan.gpt-6-astra.findings.md`
- Adversarial, `deepseek-v4-pro`: `collab/1788981455261-adversarial-jing-remote-v2-plan.deepseek-v4-pro.findings.md`

They ran in parallel without coordinating and **converged on three items**. I
verified everything below against the tree; treat it as established.

## Blocking

1. **The JVM transport blocks inside the operation, so D1's claim does not
   hold as written.** `src/clj/dao/stream/ws/jvm.clj:157` is
   `(.join (.sendText ^WebSocket socket message true))`. The path is
   `rpc/request!` → `stream/append!` → that `send!`, so a pending send parks
   **inside** the operation, before your host loop can check its deadline;
   retrying an `:unsent` envelope through `call-step` reaches the same path.
   No request timeout can bound it.

   This is an **existing defect in shipped v2 code**, not something your plan
   introduces — and it is live today for `yin.repl` through
   `src/clj/yin/repl/host/jvm.clj:3`, plus `slice_test`,
   `slice_peer.cljc` and `v2/host/jvm_test.clj`. Name it as a **prerequisite
   repair with its own phase**, ahead of the migration: acknowledge host
   acceptance without joining, report subsequent send failure through the
   adapter's stream events, account for overlapping asynchronous sends, and
   pin it with a controllable incomplete send future rather than a
   real-network timing test. Say explicitly whether the repair is
   independently committable ahead of this plan — my reading is that it
   should be, since it fixes a live defect for a consumer that is not yours.

2. **The prescribed timeout test cannot pass** (both reviewers, independently
   and with the same interleaving). §5.2's
   `request-timeout-throws-and-the-client-stays-usable` serves `:slow/op`
   (300 ms) and `:fast/op` on a client with `:request-timeout-ms 50`. Per your
   own S4/D5 the handler runs in the server's **single ticker thread**, so at
   t≈50 the slow call times out correctly, but the fast request sits
   undispatched until t≈300 and times out too. The assertion is unreachable.
   deepseek adds that the discard property is **already** pinned at step level
   by Phase 1's test 2 (a completion for a foreign id is discarded), so the
   end-to-end claim here is partly redundant. Rewrite it: release the slow
   handler explicitly after the first timeout, or block it off the server
   driver (a `future`), and give the second call deadline headroom; pin late
   correlation separately with scripted media.

## Must fix before an implementer is briefed

3. **Retaining timed-out requests buys nothing and leaks payloads.** You
   specify that a timed-out request stays `:outstanding` so its late
   completion is discarded by the next step. deepseek verified that
   `rpc.cljc:363-364` already classifies a response whose id is not
   outstanding as `:unsolicited-response`, which `take-diagnostics` drops, and
   ids are monotonic and never reused — so retention is unnecessary for N6.
   It costs real memory: `:outstanding` holds `{:op :args}`, and for
   `:jing/put-content` `args` is `[address payload]` — **the whole content
   payload**, retained per timeout until the attachment goes terminal. Drop
   the entry on timeout. astra adds: if `:unsent` remains, abandon it
   explicitly before accepting another call, or `request!` retries the old
   operation while ignoring the new arguments; and document that a timeout
   does not cancel remote execution, so "one call in flight" means one
   *locally awaited* call.
4. **The server drops `invalid-value` on the response path.** `ws.cljc:172`
   returns `:dao.stream/invalid-value` for a non-portable value, and D5's
   `inbound-step` ignores every response-append outcome; your justification
   covers `full`, `closed` and `transport-error` only. A handler returning a
   locally stored payload outside the portable domain is refused, the socket
   stays open, and the client sees a **timeout** instead of a diagnosis. N7
   covers outgoing requests, not this direction. Specify response-domain
   validation, a correlated portable error for an unencodable result, and
   explicit retirement or reporting when delivery fails.
5. **`network-invalid-url-test` does not pin D2.** `remote_test.cljc:265`
   uses port `99999`, out of range, so it can fail at URL validation without
   ever attaching or waiting. Keep it as a validation test and add a real
   establishment-failure test on a valid but unreachable endpoint, plus a
   timeout-cleanup pin with an attachment that never establishes — so
   returning a handle immediately would fail the suite.
6. **`close!` is not under the call lock, and the plan implies it is.**
   N5 is genuinely enforced for call-versus-call (`locking` on `:lock`), but
   `close!` is guarded only by `content-client`'s separate `close-lock`
   (`remote.cljc:84-85,107-112`). deepseek traced the interleaving and it
   resolves safely — `stream/close!` sets phase `:closed`, the in-flight
   call's next `poll!` or `append!` throws N8/N9 — but nothing pins it and
   the lock's real scope is left implicit. State the scope; add the pin.
7. **§9 has one item with no home and is missing one.** The homeless item is
   the ClojureDart fact ("the owner's call"), which is exactly the failure §9
   exists to prevent. **Resolve it: it is already recorded in the project
   memory `project-cljd-clj-reader-conditional`, with your measurement, the
   requires-versus-bodies distinction and the `#?@(:cljd [] :clj [[…]])`
   splice form.** Say so and drop the open question. Missing: **D2's
   cursor-before-`attach!` ordering** — N2 records that establishment is
   awaited but not *why* the cursor is minted first (minting after opens a
   window where `/established` lands before the cursor and the wait hangs to
   `:connect-timeout-ms`). It is the client twin of what `serving.cljc:62-64`
   documents server-side. Add it, with a home.
8. **D8 understates the DHT split.** deepseek verified the reach —
   `transit/encode`/`decode` at `node.cljc:59,65` and `node_test:139` — and
   agrees splitting is right. But it is not a require swap: v2
   `transit/decode` runs `ensure-portable!` (`v2/transit.cljc:121`) and
   **rejects on decode** tagged values v1's cognitect accepted (uuid, bigint,
   bigdec, uri, quoted, link). The inbound direction is the real work. Say so.

## Confirmed sound by both — do not relitigate

**D1's core reasoning** (v1 already polled; each `poll!`/`request!` returns;
the JVM loop is the contract's sanctioned driver) — subject to finding 1.
**No collapse of distinctions**: absence, stored `nil`, malformed envelope,
timeout, terminal loss and server error stay pairwise distinct across the
whole v2 path, pinned by tests kept verbatim. **D2's establishment wait** and
its cursor discipline. **N5 enforced, not merely stated.** **The 13/6 test
split.** **`content-client`, `default-handlers` and `coordinate` untouched.**

One framing correction, deepseek, not a defect: calling `call-step` "the seed
of the stepped client" overstates it — its per-id filter is the blocking
driver's shape, while a non-blocking stepped client needs multi-id dispatch.
Call it the seed of the blocking driver's loop.

## Output

Rewrite the plan in place, keeping structure and decision numbering stable so
the review record still reads against it. Where a decision changes, say what
it now rules and why. If a finding is wrong, say so with your reason — you
corrected my brief on eight points and were right on all eight. Update the
header block.
