Completed-GMT: 2026-09-02 19:00:31 GMT
Completed-Local: 2026-09-03 02:00:31 Asia/Ho_Chi_Minh

This is a proposal only. No files were modified.

## A1 — Server-side attachment identity

Proposed amendment text

Document: `docs/design/dao.stream.ws.md`  
Anchor: `## The Duplex Model`, immediately after the existing “Attachment identity” paragraphs at lines 87–100.

Insert:

> **Server-minted attachment identity.** A server-side accepted-connection handle is an attachment handle even though no `attach!` call produced it. Before exposing the connection or enabling inbound delivery, the serving transport mints an opaque, structurally serializable attachment value under the same uniqueness rule as client-side `attach!`: it is unique among attachments that the boundary can distinguish and is not reused during that boundary’s lifetime.
>
> The value appears under `:ws/attachment` on every event deposited for that accepted connection. Because there is no server-side `attach!` result, the server’s acceptance event is the authoritative place where the serving composition receives it; see Serving. The handle, the acceptance event, and all later events for that connection denote the same attachment. Client-side handles continue to receive the value under `:dao.stream/attachment` in the `attach!` success map.

Rationale

The contract requires a transport-distinguishable attachment to have an opaque, serializable identity and requires displaced events to correlate with that identity (`dao.stream.md:254–261`). It does not require `attach!` to be the only minting occasion. A server-side socket is still an attachment, and its independent close lifecycle makes it distinguishable from other sockets.

Minting before exposure also removes a race in which payload or close events could be deposited before their correlation identity exists.

Invalidated elsewhere

- `dao.stream.implementation-plan.md:240–241` says every `:ws/attachment` value is “the same value `attach!` returns.” That is false for server-minted handles. Replace that fragment with:
  > “the client-side value returned by `attach!`, or the server-side value carried by the acceptance event”
- `yin.repl.implementation-plan.md:359–361` says the server-side identity rule does not exist. It becomes stale once this amendment lands.

Decision status

No unresolved decision. The representation remains transport-owned, as required by the contract. A UUID-shaped string is a sensible implementation, but must not become a contract requirement.

---

## A2 — Accept notification

Proposed amendment text

Document: `docs/design/dao.stream.ws.md`

### 1. Extend the deposited-event example

Anchor: lines 102–111. Replace the example with:

```clojure
{:ws/attachment <id>
 :ws/event      :ws/payload  ; or :ws/accepted :ws/opened :ws/closed
                             ;    :ws/ended :ws/error :ws/not-found
                             ;    :ws/transport-error
 :ws/value      <decoded>    ; payload events only
 :ws/handle     <handle>}    ; accepted events only; host-local
```

### 2. Replace the event-group paragraph

Anchor: lines 113–122. Replace with:

> The event kinds divide into four groups. **Acceptance** — `:ws/accepted` is deposited once on the serving boundary after the requested stream has been resolved and a server-side attachment handle has been constructed. It carries that writer+closable handle under `:ws/handle`; the composition starts a session by observing this event. **Resolution** — how a client attachment turned out: `:ws/opened` when the endpoint accepted the stream, `:ws/not-found` when the endpoint disclaimed it, and `:ws/transport-error` when the endpoint could not be reached. Exactly one resolution event is deposited per client attachment. **Lifecycle** — `:ws/closed` when the connection went away and `:ws/ended` when the served stream itself ended. **Traffic** — `:ws/payload`, carrying `:ws/value`, and `:ws/error` for a boundary or protocol error observed after an attachment identity exists.
>
> `:ws/accepted` is local serving-side data and never crosses the serialization boundary. Its `:ws/handle` is permitted because an in-memory stream may carry a live DaoStream handle intact; it must not be written to a codec-backed medium.

### 3. Extend `### Serving`

Anchor: after lines 173–176. Insert:

> Acceptance is notification by stream, never by callback. Once the request path resolves, the boundary mints the attachment identity and handle and deposits:
>
> ```clojure
> {:ws/attachment <id>
>  :ws/event      :ws/accepted
>  :ws/handle     <server-side-writer-handle>}
> ```
>
> onto the serving boundary’s host-composed medium. This event is the only way the composition discovers the connection and obtains its reply handle; there is no `:on-connect` option or transport callback.
>
> For a given attachment, `:ws/accepted` precedes every payload or lifecycle event. The transport must not enable inbound message delivery or send the wire acceptance frame until this deposit returns `:dao.stream/ok`. If the deposit returns any other outcome, the transport tears down the socket and exposes no accepted session. The medium carrying acceptance events must therefore be host-local: a live handle cannot cross a serialization boundary.

Rationale

This follows the foundational callback rule: a host-initiated accept becomes data on a stream. The handle itself may ride an in-memory stream intact under `dao.stream.md:211–218`, while the explicit prohibition on codec-backed acceptance media protects the serialization invariant.

Requiring acceptance to be first gives the composition a session and writer before it can observe requests for that session.

Invalidated elsewhere

- `dao.stream.ws.md:107–110` omits `:ws/accepted` and `:ws/handle`; the replacement above resolves it.
- `dao.stream.ws.md:113–122` currently has only three event groups; replace as above.
- `yin.repl.implementation-plan.md:362–364` says accept notification is undefined. It becomes stale.
- No contradiction with `dao.stream.ws.md:140–143`: the proposed acceptance event is precisely the boundary-level announcement required when attachments need discovery.

Decision status

No unresolved decision.

The rejected alternative is returning accepted handles from a polling endpoint operation. No such operation exists in the contract, and adding it would create a transport-specific discovery surface where an ordinary deposited event suffices.

---

## A3 — Wire contract

Proposed amendment text

Document: `docs/design/dao.stream.ws.md`

### 1. Replace the final sentence of the wrapping paragraph

Anchor: lines 133–136. Replace:

> Nothing of this crosses the wire: the receiving side already knows which socket delivered a message, so correlation is added locally, on both ends independently, and the wire carries bare payloads.

With:

> Deposited-event envelopes do not cross the wire: attachment correlation is added locally because each side already knows which socket delivered a frame. Wire frames have their own minimal envelope, specified under The Handshake and Elements and Serialization, so protocol control cannot be confused with an application value.

### 2. Replace `## The Handshake`

Anchor: lines 292–304. Replace the section body with:

> Attaching presents the served identity as the WebSocket HTTP request-target path. `:ws/path` is a non-empty absolute path beginning with `/`; query and fragment components are not part of stream identity. The client offers the WebSocket subprotocol `dao.stream.transit-json`, and the server refuses the upgrade when that subprotocol is absent.
>
> After upgrade, the first WebSocket message sent by the server is exactly one Transit-JSON text frame:
>
> ```clojure
> {:ws/frame :ws/accepted}
> ```
>
> or:
>
> ```clojure
> {:ws/frame :ws/not-found}
> ```
>
> `:ws/accepted` authoritatively accepts the path and causes the client boundary to deposit `:ws/opened`. `:ws/not-found` authoritatively disclaims it and causes the client boundary to deposit `:ws/not-found`; the server then closes with code `4004` and reason `dao.stream/not-found`. The client does not deposit `:ws/opened` merely because the HTTP upgrade completed.
>
> No value frame may be sent before `:ws/accepted`. A frame of any other shape in the first position is a protocol failure.

### 3. Replace `## Elements and Serialization`

Anchor: lines 367–371. Replace with:

> The wire codec is **Transit JSON encoded as one UTF-8 WebSocket text message per value**. Every application value is framed as:
>
> ```clojure
> {:ws/frame :ws/value
>  :ws/value <value>}
> ```
>
> The portable value domain is `nil`, booleans, strings, qualified or unqualified keywords and symbols, safe integers in `[-9007199254740991, 9007199254740991]`, finite doubles, vectors, lists, sets, and maps recursively composed from that domain. No custom Transit handlers or metadata participate in this protocol. A sender unable to encode a value in this domain returns `:dao.stream/invalid-value` and sends nothing.
>
> All protocol keywords use exactly one namespace separator—for example `:ws/frame`, never a multi-slash spelling such as `:ws/wire/frame`—so the vocabulary reads on clj, cljs, and cljd.
>
> A text message that is not valid Transit JSON, a binary message, an unknown `:ws/frame` value, a missing required key, or a value outside the portable domain is a protocol failure. Once an attachment identity exists, the receiver first deposits:
>
> ```clojure
> {:ws/attachment <id>
>  :ws/event      :ws/error
>  :ws/reason     :ws/decode-failure}
> ```
>
> and then closes the connection with code `4002` and reason `dao.stream/protocol-error`. The malformed input and host error object are not deposited. If the error deposit itself fails, the ordinary deposit-admission teardown rule applies.
>
> Close code `4000`, reason `dao.stream/ended`, means that the served logical stream ended and maps to `:ws/ended`. Code `4004` is the authoritative not-found close following the disclaimer frame. Code `4002` is a protocol-error teardown. Every other peer close or connection loss maps to `:ws/closed`, subject to the resolution rules above.

### 4. Remove settled deferrals

Anchor: `## Deferred`, lines 375–382. Delete:

- the handshake-wire-format bullet;
- the value-codec/decode-failure bullet.

Keep the descriptor key-set, liveness, resumption, flow-control, and RPC bullets.

Rationale

The outer wire envelope is necessary because DaoStream assigns no reserved meaning to application values. A bare application map could otherwise be indistinguishable from an acceptance or disclaimer map.

The first server frame separates WebSocket upgrade from logical-stream acceptance, works uniformly for JVM, Node, Dart, and a future browser client, and preserves non-waiting `attach!`. Transit JSON already has implementations on all three hosts, but the restricted portable domain avoids host-specific tagged representations and JavaScript integer loss.

The close codes occupy WebSocket’s application-defined range and make ended-stream, disclaimer, and malformed-peer teardown distinct.

Invalidated elsewhere

- `dao.stream.ws.md:133–136`: “the wire carries bare payloads.”
- `dao.stream.ws.md:202–204`: says the ended code remains to be settled.
- `dao.stream.ws.md:302–304`: says the handshake form is unspecified.
- `dao.stream.ws.md:375–382`: defers the handshake, close code, codec, and decode behavior.
- `dao.stream.implementation-plan.md:194–205`: describes the wire gate as unsettled. Replace its first two sentences with:
  > “**Decision gate — wire contract, settled in `dao.stream.ws.md`.** Phase 4a implements that wire verbatim and its wire-level conformance tests are a prerequisite to Phase 5.”
- `yin.repl.implementation-plan.md:348`: the wire-contract gate remains a prerequisite, but becomes a satisfied specification gate rather than an open design decision.
- `yin.repl.implementation-plan.md:355–371`: the claim that the wire answers do not exist becomes stale.

Decision options

This is genuinely contested:

- Recommended: an explicit envelope around every wire value, as proposed. It is collision-free and host-neutral.
- Alternative: bare Transit payloads plus special first-message control maps. This has less overhead but reserves application values implicitly and therefore violates the transport’s “values mean nothing here” discipline.
- Alternative: infer acceptance/disclaimer solely from HTTP 101/404. This is simpler for server libraries but does not give browser clients a portable authoritative disclaimer.

The recommendation trades away bare Transit-over-WebSocket interoperability and adds a small per-message envelope.

---

## B1 — Ring-buffer `attach!` ownership

Proposed amendment text

Document: `docs/design/dao.stream.implementation-plan.md`  
Anchor: `## Phase 2 — Ring buffer reference`, after line 153.

Insert:

> Phase 2 also implements the ring buffer’s attachment entry. It is not a namespace-global resolver: `make-attacher` receives a host-owned directory or resolver and returns the unary `attach!` function that a host places in its dispatch table. The returned function validates the descriptor, resolves its logical-stream identity against that captured composition state, and returns `ok`, `invalid-descriptor`, or `not-found`.
>
> A successful attachment is a fresh attachment handle over the same logical-stream state, not the creator handle reused by reference. Its own `close!` closes only that attachment; `descriptor` and cursors still denote the underlying logical stream. Where these attachment lifecycles are distinguishable, the success map carries `:dao.stream/attachment` as the contract requires.
>
> Phase 2 tests the entry with a minimal supplied resolver. Phase 3 owns the real test composition’s directory population, Transit round trip, and kept-cursor proof; it does not first implement attachment there.

Also replace Phase 3 line 181:

> `attach!` against that composition

with:

> the Phase 2 `make-attacher` closure over that composition’s directory

Rationale

`attach!` is a transport entry function, so its behavior belongs with the ring-buffer transport. The directory is composition state, so its instance belongs in Phase 3. A constructor returning a closure preserves both ownership rules and avoids an ambient registry.

A fresh attachment handle is required by the contract’s handle-relative close semantics (`dao.stream.md:543–566`).

Invalidated elsewhere

None found. The existing Phase 3 wording is incomplete rather than contradictory.

Decision status

No unresolved decision.

Assigning all of `attach!` to Phase 3 was considered but rejected because it would put transport lifecycle behavior inside test composition code.

---

## B2 — Transit codec identity

Proposed amendment text

Document: `docs/design/dao.stream.implementation-plan.md`  
Anchor: Phase 3, replace lines 173–174.

Replace with:

> - `descriptor` on a ring-buffer handle yields a plain-data envelope that survives the **DaoStream v2 portable codec** structurally unchanged. That codec is Transit JSON text, implemented behind `dao.stream.transit`, with the portable value domain and no-custom-handler profile specified by `dao.stream.ws.md`.
> - There is **one value codec, not two**: Phase 3 encodes the descriptor directly as a Transit value, while WebSocket frames encode their control or value envelope with the same codec. Framing differs; value encoding does not.
> - Phase 3 owns the cross-host codec conformance corpus and round trip on clj, cljs, and cljd. The v2 namespace must not depend on the legacy `dao.stream.transit` namespace merely because it uses the same Transit format.

Rationale

Descriptors and WebSocket values are both required to cross serialization boundaries structurally unchanged. Separate codecs would allow Phase 3 to prove a descriptor that Phase 4 cannot actually carry.

“One codec, two framing contexts” preserves the distinction between serialization and protocol framing while preventing host drift.

Invalidated elsewhere

None found. Existing references say only “the transit codec” or “the codec” without naming it.

Decision options

- Recommended: one restricted Transit-JSON codec.
- Alternative: Transit for descriptors and a separate WebSocket codec. This permits later wire-specific optimization but doubles the cross-host semantic surface and weakens the descriptor-round-trip evidence.

The recommendation trades away independent codec evolution. A future binary codec would require an explicit protocol-version addition rather than an unnoticed ws-only substitution.

---

## B3 — Conformance concurrency oracle

Proposed amendment text

Document: `docs/design/dao.stream.implementation-plan.md`  
Anchor: Phase 1, after lines 123–128.

Insert:

> **Concurrency oracle.** The conformance harness uses bounded, offline linearizability checking. Each invocation records its operation, arguments, invocation time, completion time, and returned outcome. Values appended by the harness are unique tokens. After a run, the checker searches for a sequential history accepted by a pure abstract model of the declared transport while preserving real-time precedence: if operation A completed before operation B was invoked, A must precede B.
>
> For the ring buffer, the model contains logical-stream identity, ordered positions, the bounded evict-oldest window, immutable cursors, attachment lifecycle, and logical-stream close. It linearizes each operation against that abstract sequence—not against atom swap order, wall-clock start order, thread scheduling, or a particular implementation field.
>
> The Phase 2 concurrency cases include concurrent append/append, append/next, append/close, cursor/close, and independent readers, with bounded histories small enough for exhaustive search. A history for which no legal sequentialization exists fails conformance.
>
> This oracle detects duplicate or lost successful appends, impossible outcomes, cursor corruption, non-atomic append-versus-close behavior, and destructive interference between readers. It cannot prove liveness or fairness, detect failures in schedules the generator did not produce, validate WebSocket host-library ordering, or prove correctness for unbounded executions. Stress repetition supplements it but is not itself the oracle.

Rationale

The contract guarantees that each operation reflects the sequence at one moment (`dao.stream.md:580–586`) but intentionally defines no stronger writer coordination. Linearizability against the declared abstract transport is therefore the strongest faithful oracle.

Checking implementation atom order would merely test the chosen implementation, while final-state-only checks miss impossible intermediate outcomes.

Invalidated elsewhere

None found. Phase 2’s “single atom; one deref per `next`” at lines 152–153 remains an implementation strategy, not the oracle.

Decision options

- Recommended: bounded model-based linearizability checking.
- Alternative: barrier-controlled deterministic schedules. Easier to debug, but covers only enumerated interleavings.
- Alternative: stress plus final multiset/order assertions. Cheap, but cannot establish that individual outcomes were true at any moment.

The recommendation trades harness simplicity and test throughput for a materially stronger concurrency judgment.

---

## B4 — Ring-buffer exclusion reasons

Proposed amendment text

Document: `docs/design/dao.stream.implementation-plan.md`  
Anchor: Phase 2, immediately before “The Phase 1 conformance suite runs here…”

Insert:

> **Ring-buffer manifest and exclusions.** The manifest records the following exact subsets and reasons:
>
> | Operation | Produces | Excluded, and why |
> |---|---|---|
> | `create!` | `ok`, `invalid-spec` | `not-found`: host dispatch answers absence before this handler is selected. `transport-error`: allocation and state transition use only in-memory values and have no operational failure channel after validation. |
> | `attach!` | `ok`, `invalid-descriptor`, `not-found` | `transport-error`: lookup against the supplied in-memory resolver has no operational failure channel. |
> | `descriptor` | `ok` | None; this is the contract’s complete set. |
> | `cursor` | `ok`, `invalid-anchor`, `closed` | `transport-error`: cursor minting reads only coherent in-memory state. `closed` is induced through a closed attachment handle; the creator handle remains on the logical stream. |
> | `next` | `ok`, `blocked`, `end`, `gap`, `cursor-mismatch`, `invalid-cursor` | `transport-error`: reading coherent in-memory state has no operational failure channel. |
> | `append!` | `ok`, `closed` | `full`: evict-oldest answers retention pressure by eviction, reported later as `gap`, so it never refuses for capacity. `invalid-value`: an in-memory reference stream performs no encoding and can carry every host value admitted as a stream element. `transport-error`: its in-memory state transition has no operational failure channel. |
> | `close!` | `ok` | None; this is the contract’s complete set. |
>
> **Reject mode is not a deferred ring-buffer variant.** It is intentionally absent because v2 has no destructive drain: once such a buffer reached capacity, no operation could free a slot, so `full` would be permanent. Reintroducing reject mode would recreate the withdrawn deadlock rather than add usable backpressure. Backpressure, if later required, belongs in the deferred interpreter/lease path, not in this reference transport.

Rationale

The contract requires a reason for every exclusion (`dao.stream.md:417–424`). These reasons distinguish:

- conditions impossible by the in-memory transport’s nature;
- pressure answered by the named evict-oldest policy;
- outcomes belonging to host dispatch rather than the selected handler.

The reject-mode paragraph records the already-settled architectural reason instead of leaving its absence to be mistaken for unfinished work. The cited legacy mechanism is confirmed at `src/cljc/dao/stream/ringbuffer.cljc:153–175`, where destructive drain advances `:head`; ordinary `next` at lines 109–118 does not.

Invalidated elsewhere

None found.

The following existing statements agree and should remain:

- `dao.stream.implementation-plan.md:139–143`
- `yin.vm.implementation-plan.md:335–351`
- `yin.repl.implementation-plan.md:74–78`
- `collab/architect-yin-vm-rotation-brief.md:17–22`

Decision status

No unresolved decision. Reject mode remains withdrawn.

## Summary

| Item | Settles | Blocks until applied |
|---|---|---|
| A1 | Identity for server-minted attachment handles | REPL R3–R5, especially server session correlation |
| A2 | Stream-native acceptance notification carrying the server writer handle | REPL R4–R5 |
| A3 | Handshake, disclaimer, close codes, codec, framing, and decode failure | Transport 4a and REPL R3–R5 |
| B1 | Phase 2 ownership of ring-buffer `attach!`; Phase 3 supplies directory | Transport Phases 2–3 |
| B2 | One restricted Transit-JSON codec for descriptors and ws values | Transport Phases 3–4 |
| B3 | Bounded abstract-model linearizability oracle | Phase 1 harness and Phase 2 concurrency sign-off |
| B4 | Complete ring-buffer outcome manifest and permanent reject-mode exclusion | Phase 2 conformance sign-off |
