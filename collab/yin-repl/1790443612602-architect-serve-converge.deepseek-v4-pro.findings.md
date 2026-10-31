Completed-GMT: 2026-09-26 17:50:00 GMT
Completed-Local: 2026-09-27 00:50:00 +0700
Coding-Agent: deepseek
Session-ID: 7aec5601-a998-45b8-a314-235fd90bcf23

## Q1 — NO-WAITING

**RULING: The reflection satisfies the rule; the objection is incorrect.** A handle that drains, answers `:dao.stream/blocked` while a request is in flight, and files the answer for a later `next` is not waiting — `blocked` *is* the contract's "outcome true now" for "not yet." The contract states the model directly: `:dao.stream/blocked` "is `EAGAIN` — this position holds nothing yet" (`docs/design/dao.stream.md:146`), and the general rule: "no operation waits … the eventual answer arrives the way every other fact does — as data, observed later, through an ordinary operation" (`:153-158`). The Reading table defines `blocked` as "Nothing at this position yet; retry later" (`:544`). A reflection answering `blocked` and filing the answer for a later call is *this model*, not an exception to it.

The one genuine gap is `cursor`, whose outcome set has **no** `blocked` (`:493-494`). There the reflection answers `transport-error` with `:dao.stream/retry? true` — which is exactly the key OD-1 drafts (`:805-846`) — and returns the relayed `ok` once answered. No separate asynchronous interface is required.

**Smallest contract change (if any):** none beyond the drafted OD-1 (`:dao.stream/retry?`), OD-2, OD-3. The "impossible" claim (codex) reads "no operation waits" as "no operation may defer," but the contract explicitly permits deferral as `blocked`/observed-later.

**RISK:** without OD-1's retry key, `transport-error` for "cursor not yet answered" slightly misdescribes a "not yet" as a failure. Low, and already drafted.

## Q2 — UDP AND LARGE VALUES

**RULING: Narrowing is consistent; fragmentation is NOT required in v1.** The invariant says "any implementation … exposed via udp," not "any value carried over udp." A stream whose values exceed a datagram is still exposed — the exposure reports `invalid-value`/`:oversize` honestly rather than truncating. This is the contract's *own* rule: "Large media travel as content addresses in the payload, resolved through `dao.jing`" (`dao.stream.md:1096-1098`). The 1200-byte budget is the DHT's existing figure (`src/cljc/dao/jing/dht/node.cljc:46`).

**What the owner must be told:** UDP carries one value per datagram up to ~1200 bytes; larger values are `invalid-value` on append and `:oversize` (non-advancing) on read, and travel as `dao.jing` addresses. Content services (whose code images routinely exceed one datagram) are **WebSocket-only** until fragmentation exists.

**Smallest fragmentation mechanism, if ever required:** channel-internal, not serve-layer. One logical message splits into N datagrams each `{:frag id :n i :of k :bytes b}` (a message id + index + count), reassembled by the UDP adapter before it deposits *one* value; duplicate suppression and the message-id budget live in the adapter. Invisible to the serve layer.

## Q3 — ONE DESIGN

**Baseline: the "mirror and reflection" (Claude/fable).** It is the fewest concepts of the three. Adopt/reject element by element:

| element | ruling |
|---|---|
| per-stream channel vs **identity-in-request** | **identity-in-request.** One channel carries any number of streams; this removes `attach`/`opened`/`disclaim` and all session state entirely. |
| `attach`/`opened`/`disclaim`/`ping` frames (deepseek) | **reject all four.** Identity rides in every request; absence is `not-found` per request; ping is a channel/liveness datum, not a frame. |
| peer id in protocol (deepseek, codex) | **reject.** Peer names exist only inside conventions as self-minted payload values; a protocol peer id risks a "server" identity. |
| request id + response id | **adopt.** Asker-minted, opaque, unique per channel, echoed in the outcome. Correlation only; no result retention. |
| `:more` batching + anchor piggyback (fable, optional) | **reject from core** (open maps permit them later). `cursor` handles anchors as a normal request; one round-trip per mint is a cost, not a correctness issue. |
| `:resend-after` loss policy (fable) vs nothing (deepseek) | **adopt** `:resend-after k` as one composition datum (∞ on reliable, small on UDP). Reads are idempotent-by-cursor; `append!` is never re-sent (OD-2). |
| fate of `dao.stream.apply` | **retired** — its `{:id :op :args}` + `{:id ok/error}` wrapper is replaced by the request + verbatim outcome; the outcome map needs no error wrapper; `mirror-step` *is* its dispatch. |
| fate of `dao.stream.rpc` | **convention-over** — correlation-by-id over two served streams is the §4 request/response convention. |
| wire op set | **`cursor`, `next`, `append!`** only. `descriptor` and `close!` are local (reflection returns the remote descriptor; close! is channel teardown). |
| UDP fragmentation (codex) | **reject for v1** (see Q2). |

## Q4 — BLOCKERS

No element of the merged design breaks the owner invariant, the contract, or any of the six `datom.world.md` invariants (no hidden global state — the table is host-composed data; no implicit control flow — draining is the caller's polling; no callbacks — the channel adapter deposits and returns; no shared mutable state — the table and link are single-owner; no layer collapse — mirror interprets ops never values; no assumed graphs — reachability is descriptor data).

**Contract preconditions (the only blockers).** The design is un-implementable until three already-drafted Open Decisions are accepted, not merely proposed: **OD-1** (retry key), **OD-2** (append effect may be unknown; dedup/correlation are the payload's — the one rule that makes UDP writes safe, `:883-886`), **OD-3** (cursors are plain data that survive the codec — without it "the original, not a copy" is impossible, `:934-943`). These are text moves, not new work.

**VERDICT: READY TO SPECIFY**, contingent on accepting OD-1/2/3. The 1,197-line draft is replaced, not amended; it is kept only for its NAT/relay reasoning and the verbatim-cursor/outcome rule, which the mirror already preserves.

---

## CONVERGED DESIGN

**One sentence.** A peer holding a handle answers that handle's own operations as data over any two-ended channel; the far end gets a handle whose operations are those questions sent across the channel, with the source's outcome maps returned verbatim.

**Concepts (4).** (1) **channel** — a writer handle + a reader handle (a WebSocket attachment, or a UDP datagram adapter); codec-agnostic, carries plain-data maps. (2) **table** — host-composed data `{identity -> {:handle h :surface S}}`; never a registry. (3) **mirror-step** — the serving side's one pure transition. (4) **reflection** — the handle `attach!` returns.

**Frames (2, both plain-data maps).**

```clojure
;; request
{:dao.stream/identity id
 :dao.stream/op        op      ; :dao.stream/cursor | :dao.stream/next | :dao.stream/append!
 :dao.stream/args      [...]   ; [anchor] | [cursor] | [value]
 :dao.stream/id        n}      ; asker-minted, unique per channel

;; outcome — the contract outcome map, verbatim, plus correlation
{:dao.stream/outcome  ...      ; e.g. {:dao.stream/outcome :dao.stream/ok
                               ;       :dao.stream/value v :dao.stream/cursor c2}
 :dao.stream/identity id
 :dao.stream/id        n}

;; absent identity, or op not in the declared surface:
{:dao.stream/outcome :dao.stream/not-found :dao.stream/identity id :dao.stream/id n}
```

No `attach`, `opened`, `disclaim`, `close`, `ping`, `session`, or `hold` frame.

**Serving side (`mirror-step`).** Read a request; look up `identity`; absent or op ∉ surface → `not-found`; else apply the op to `h` with `args` and append the outcome verbatim + id + identity. Stateless: reads are positioned by the cursor in the request; `append!` correlates by payload (OD-2); the owner's `close!` is observed as `end` from `next`, never called remotely.

**Far side (`reflection`).** `attach!` on `{:dao.stream/type :dao.stream/remote :dao.stream/identity id :dao.stream/surface S :dao.stream/channel <channel-desc>}` dials and returns `ok` + handle immediately (deferred confirmation, `dao.stream.md:323`). Every op first **drains** the channel reader, filing answers by id (this is where the asynchrony goes, `:455-471`), then: `descriptor` local (the remote descriptor, total); `cursor` → relayed, or `transport-error` + `retry?` while in flight; `next` → filed answer, else send + return `blocked`; `append!` → send, return the channel writer's outcome (`ok` = outbound-path acceptance, `:570-576`); `close!` → local (forget ids).

**Loss.** One datum `:resend-after k`: resend an unanswered read after `k` asks (∞ on reliable, small on UDP). Reads idempotent — the cursor is the sequence number; `append!` never re-sent; late answers whose id is not outstanding are dropped.

**Wire format.** The channel's codec: Transit-JSON text or CBOR on WebSocket (`src/cljc/dao/stream/ws.cljc:323-342`), one CBOR value per 1200-byte UDP datagram. Large values travel as `dao.jing` addresses.

**Reachability / NAT / relay.** Direct (dial an acceptor), or relayed through a third peer `M` that runs the *ordinary* `mirror-step` over two ring buffers plus a forwarder — `M` has no frame of its own and no privilege; the relay is literally `dao.stream.forward` on a reachable peer. Punching is a convention: a meet board republishes each datagram's source address (`:meet/seen`), both peers send ping/pong from the same socket. Symmetric↔symmetric, CGNAT↔CGNAT, and two browsers cannot meet without `M`; this is a network fact, not a protocol role.

**Left out.** Sessions, protocol peer ids, batching, anchor piggyback, held reads, a proxy window/cache (the reflection polls through), ping frames, fragmentation, authentication/encryption. Each is an additive convention or channel-internal concern, and none is needed to meet the invariant.
