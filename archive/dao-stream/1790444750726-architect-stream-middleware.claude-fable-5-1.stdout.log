Completed-GMT: 2026-09-26 17:46:34 GMT
Completed-Local: 2026-09-27 00:46:34 +07
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# Ring-like middleware for dao.stream

## M1. What "ring-like" means here

**RULING.** Middleware is a **map of two pure transforms over the request map and the outcome map**, and one generic `wrap` that turns an inner handle plus a chain into an outer handle on the same logical stream. Not a closure-over-handler, not an interpreter. The request map is the one the converged serve design already puts on the wire; the outcome map is the contract's own. Ring's request/response pair maps exactly onto them.

```clojure
;; a middleware is a value: an adapter-shaped map of transforms (datom.world.md:83-86)
{:dao.stream.mw/in  (fn [req] -> req' | outcome-map)      ; returning an outcome short-circuits, as Ring auth does
 :dao.stream.mw/out (fn [req outcome] -> outcome')}
;; req = {:dao.stream/op :dao.stream/next :dao.stream/args [c] ...open keys}

(apply-request h req)      -> outcome     ; op + args applied to a handle; the mirror already is this
(wrap h [mw1 mw2 ...])     -> handle      ; each protocol call builds req, runs `in` chain outward-in,
                                          ; applies to h, runs `out` chain inward-out
```

Worked example, value encryption: `in` on `:dao.stream/append!` replaces `(first args)` with its AEAD ciphertext; `out` on `:dao.stream/next` with outcome `ok` replaces `:dao.stream/value` with the decrypted plaintext, or, when decryption fails, with `{:dao.stream.mw/undecodable true :dao.stream.mw/raw v}` so the position stays real and the reader decides (data is syntax, `datom.world.md:16`). Every other op and every other key passes untouched.

**WHY.** The contract already sanctions host-composed closures as the way state reaches a transport (`dao.stream.md:370-375`) and calls a map of transform functions "all an adapter is" (`datom.world.md:83-86`); the same rules apply. Against the six invariants: no callback, because a transform is called downward by the caller in its own control flow, returns a value, and nothing is registered to be invoked later; no implicit control flow, provided a transform never loops, retries, waits or advances a cursor; no hidden global state, because keys and policies are arguments to `wrap`, like a deposit medium is to a transport constructor. The one function-shaped contact a wrapper makes with its inner is a handle operation, the one contact `datom.world.md:65-67` sanctions.

**RISK.** The closure form invites a transform that "just retries once" or reads a clock. The spec must state the four prohibitions: no loop, no wait, no cursor construction, no op other than an append to a side stream the middleware was composed with.

## M2. Where it attaches

**RULING.** Both, and the two are the same mechanism because in the converged design a channel is itself two handles. Three attachment points, all `wrap`:

| Point | What it wraps | What it sees | Use |
|---|---|---|---|
| Mirror side, table entry | the served handle, before it enters the table | the full request map including open keys the remote sent, and the outcome | authorization, value encryption for serving, redaction, metering |
| Reflection side | the reflection `attach!` returns, inside the host-composed attach closure | requests before they leave, outcomes after they are filed | attaching a capability, value decryption, metering |
| Channel | the channel's writer and reader handles at each end | whole request and answer messages as opaque values | message encryption and authentication against a relay |

The mirror becomes `(apply-request (:handle entry) req)` where the entry's handle is already wrapped; the mirror itself stays stateless and value-blind.

**What a middleware must not do.** It must not touch `:dao.stream/op`, `:dao.stream/identity`, `:dao.stream/id`, any cursor, any anchor, or the `:dao.stream/outcome` kind, and it must not skip positions: a served stream stays the original only because the source's cursors and outcomes cross verbatim. Preserving transformations are value-to-value at one position: ciphers and compressors of any size ratio, since an element is a whole value with no byte offsets (`dao.stream.md:1096-1098`); redaction to a tombstone value; annotation of open keys. Non-preserving: a filter that drops elements, a merge, a re-order. Those are interpreters that forward into a new stream with its own identity (`dao.stream.md:743-749`, OD-3 (a) at `:922-931`), never middleware. Declared rule for the spec: "a middleware maps position p of the inner to position p of the outer, for every p".

**RISK.** Someone writes a filter as middleware by looping `next` inside `out`. That is the implicit-control-flow violation; the position rule catches it.

## M3. The three named uses

**(a) Encryption.** Two layers, same middleware shape, both AEAD from a library, never hand-rolled. *Value-level*, at table entry and reflection: hides values from the relay and from any peer without the key; leaves in plaintext identity, op, cursors, positions, outcome kinds, request ids, anchors, sizes and timing. *Message-level*, on the channel handles at both ends: the relay sees only opaque blobs in a pair of inboxes; it still learns which pair talks, when, and how much. Keys are composition data handed to `wrap`, exactly as a deposit medium is handed to a transport; distribution is out of scope of dao.stream and rides a convention (out of band, or a key agreement over the meeting board later). Message-level AEAD with a shared key also authenticates the channel: a message that fails to authenticate is malformed wire input, dropped below the transform (`datom.world.md:106-108`). Together they close the "relay sees everything" caveat for **contents**; they cannot close it for **metadata**, and a relay can still drop or delay.

**(b) Capabilities.** The token rides as an open key in the request map, `:dao.stream/cap`, attached by a reflection-side middleware and verified by a mirror-side middleware on the table entry that checks the token authorizes `(identity, op)` and otherwise short-circuits with a refusal outcome. Verifier and issuer are the same peer, the one whose table holds the entry, so no trusted third party exists or is needed: an HMAC under the issuer's own secret suffices, and attenuation or delegation is a later addition. **Refusal needs one contract row**: `:dao.stream/refused`, "the operation exists and a policy composed on this handle declined it; nothing observed or appended; not retryable unless `:dao.stream/retry?`". The contract reserved exactly this: gating "arrives as an addition" (`dao.stream.md:285-292`) and squeezing unauthorized into `not-found` is named as a misdescription (`:826-829`); OD-1 is what makes the addition safe for older consumers. **No privileged node** survives refusal because a peer refuses only operations on entries in its own table, a power every peer has equally over its own resources and none has over another's; "restrictions are a feature" (`datom.world.md:12`). **Lease versus capability**: a lease grant is tenure over a resource, bounded attention; a capability is permission to operate. They compose by one field: a token names the `:dao.lease/lease` under which it was issued, and the verifier refuses once its ledger no longer holds that lease, which gives revocation without absolute time (`dao.lease.md:97`). **Attribution closed**: the per-lease renewal stream from the lease round is gated by a token bound to the holder, so a renewal counts by medium and by capability; the "anyone who knows the identity can renew" caveat is gone. Bearer tokens are secrets: over a plaintext relay the relay can replay them, so tokens travel only inside a message-encrypted channel, or bound by HMAC over `:dao.stream/id`.

**(c) Metering.** `out` appends `{:meter/identity id :meter/op op :meter/outcome kind}` to a metering stream composed into the middleware, then returns the outcome unchanged. The side effect appears as a stream emission (`datom.world.md:42`), the shape generalizes to audit logs and rate accounting, and compression is the trivial value-to-value case of (a) without a key.

## M4. Placement and documents

**RULING.** A new `dao.stream.middleware.md` owns `wrap`, `apply-request`, the middleware map, the position rule and the four prohibitions; it names no network. `dao.stream.serve.md` owns the three attachment points, the `:dao.stream/cap` key, channel wrapping and the bearer-token rule. `dao.stream.md` receives two things only: the `:dao.stream/refused` row under OD-1's rule, and one sentence in *Surfaces*: "A handle may be composed over another handle on the same logical stream; it declares that stream's identity, passes cursors through, and may present values under its own interpretation." Neither is a network concept, since a local capability check on a ring buffer needs both.

**In the way?** Nothing structural. Handles are protocol values already implemented by `deftype` (`src/cljc/dao/stream/ws.cljc:189`), so a wrapper is one more implementation; a wrapper declares exactly the inner's surface; "no assumed graphs" is untouched. One tension to write down: `dao.stream.md:403-419` forbids contract-generic code from reaching for transport-owned operations, so a middleware receives everything it needs through `wrap` and never inspects the inner's type.

**RISK.** Low. Two contract sentences, one new outcome that OD-1 already anticipated.

## M5. Delta and verdict

**Delta against the lease-integrated converged design.**

- **Mirror.** Becomes `apply-request` on the table entry's handle; a table entry may hold a pre-wrapped handle. Mirror still holds no state and reads no value.
- **Request map.** Gains one conventional open key, `:dao.stream/cap`, ignored by peers that gate nothing. No other shape change; UDP fragment and answer shapes unchanged.
- **Outcome set.** `:dao.stream/refused` may be answered for `attach!`, `cursor`, `next`, `append!`; the reflection files and returns it verbatim like any outcome.
- **Reflection.** The attach closure returns `(wrap reflection chain)`; chain and keys are composition data, never descriptor fields. Everything in the descriptor stays a name, per `dao.stream.md:285-292`.
- **Channel.** Channel constructors accept a chain for their writer and reader; the relay pair, meeting board and lease streams need none, since they are addressed to the relay itself.
- **Lease streams.** The per-lease renewal stream's entry is wrapped with `require-cap` bound to the holder; the grant carries the token beside `:dao.lease/holder`. Attribution is closed.
- **Documents.** New `dao.stream.middleware.md`; two sentences plus one row in `dao.stream.md`; attachment points and token rules in `dao.stream.serve.md`.

**Ship with middleware in v1.** The attachment points, `wrap`, `apply-request`, and two reference middlewares: `require-cap` with `with-cap`, and AEAD at value and channel level. The plaintext-and-ungated caveat was the owner's only open objection to the design, and these are what answer it; a third reference middleware, metering, is cheap and proves the shape.

**Owner-visible.** Encryption hides contents, not metadata: sizes, timing, positions and which pair of inboxes talk remain visible to a relay, which can also drop or delay. Key distribution is out of scope and must arrive as a convention. Capabilities need no trusted issuer because the resource owner both issues and verifies, but a token is a bearer secret and is stealable over a plaintext relay, so caps and message encryption go together. A peer can refuse any operation on its own entries; nothing can compel a peer to serve, and that is the invariant, not a defect.

**Verdict: READY TO SPECIFY.** No blocker. The only prerequisites are the ones already named in earlier rounds: OD-1, OD-2, OD-3 decision 2 accepted into `dao.stream.md`, now with the `:dao.stream/refused` row and the composed-handle sentence added in the same edit.
