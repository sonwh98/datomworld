Completed-GMT: 2026-09-26 17:48:09 GMT
Completed-Local: 2026-09-27 00:48:09 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0debb-6229-79e1-890d-4d1e0b7d8565

## M1. What “ring-like” means

**RULING:** Define middleware as a host-composed wrapper that takes a declared policy and an inner DaoStream handle and returns a handle implementing the same DaoStream protocol:

```clojure
(wrap middleware-config inner-handle) ; => outer-handle
```

Its protocol methods may transform inputs before delegating and outcomes afterward. For example, an encryption wrapper encrypts values before `append!`, decrypts `:dao.stream/value` on successful `next`, and passes cursors and all other outcome data through unchanged. Handles are already host-local operational values, and operations/outcomes define the public surface. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:37) [stream.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream.cljc:144)

**WHY:** Higher-order composition is compatible with datom.world when it is host-side assembly: no callback crosses a stream boundary, each wrapper’s inner handle and configuration are explicit, and no hidden registry or global key state is used. The host adapter still only deposits plain-data events; it does not invoke application or middleware callbacks. [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:64) [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:83) [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:115) Explicit composition order avoids an assumed graph. [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:21)

**RISK:** Wrappers must preserve the declared DaoStream operations and their outcome meanings. A value-transforming wrapper may intentionally expose a transformed view; it must not claim byte-for-byte identity with an unwrapped handle.

## M2. Where middleware attaches

**RULING:** Support two attachment points because they protect different boundaries:

1. **Per-handle middleware** wraps a local handle before it enters the serve table, and can wrap a returned reflection. Use it for value transforms and per-stream policy.
2. **Per-channel middleware** wraps the channel’s encoded messages. Use it for peer authentication and end-to-end encryption of whole requests and replies.

Neither belongs in the stateless mirror’s core dispatch. The mirror operates on the table’s already-wrapped handle; channel middleware sits below the serve protocol and protects whole messages, including when a relay forwards them.

**WHY:** A value-preserving transform leaves cursor arguments and results, anchors, `gap` recovery cursors, and outcome kinds untouched. Lossless compression can do this while changing encoded size; declare its codec and bounds. Encryption can do it for a logical view if append/read are inverse transforms and the ciphertext itself is portable. A filter that drops elements changes positions, retention, and gaps; it cannot masquerade as the same logical stream. It must expose a derived stream with its own identity and declared semantics.

**RISK:** Middleware affects the stream’s declared nature. The DaoStream contract requires transports to declare their surfaces and outcomes; do not let a wrapper silently claim surfaces the inner handle lacks. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:390) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:446)

## M3. The three uses

### (a) Encryption

**RULING:** Use per-channel authenticated encryption for network confidentiality and integrity; optionally use per-handle value encryption for ciphertext-at-rest or a protected logical view.

With whole-message encryption, keep keys and nonce state in explicit channel configuration/state. Encrypt the complete encoded serve message, so identity, operation, request ID, cursor, and value are all hidden from a relay. The network address and traffic timing/size remain visible. A relay without keys can forward opaque bytes but cannot inspect or modify authenticated content.

Value-only encryption hides values but leaves stream identity, cursor, operation, and timing visible. It does **not** close the “relay sees everything” caveat. Key distribution and peer trust are composition policy; encryption cannot create shared trust from nothing.

### (b) Capabilities and authorization

**RULING:** Put a bearer capability in a qualified request extension attached by the reflection’s middleware; verify it at the mirror-side policy before invoking the table handle. A refusal must be a declared serve outcome such as `:dao.stream/unauthorized`, not a false `not-found` or `transport-error`. The contract currently says outcome sets are exhaustive, while OD-1 proposes safe handling for new outcomes; serve must declare its extension and consumers must follow that rule. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:98) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:811)

Each peer decides access to resources it owns and can refuse requests. That is local authority, not a protocol-level server privilege or a universal identity system. A DaoLease grant is **not** an authorization capability: DaoLease explicitly says it gates nothing, while a capability grants permission under a resource owner’s policy. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:92) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:291)

For lease renewal attribution, use one renewal medium per holder/lease, as the lease design requires attribution and only counts renewals attributed to that holder. Authenticated channel identity can strengthen the resolver; a self-asserted token or peer ID alone is spoofable. [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:75) [dao.lease.md](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:232)

### (c) Custom transformation

**RULING:** A metering wrapper counts operations and emits usage facts to an explicitly supplied meter stream. It keeps its counter in explicit per-handle state, passes cursors and outcomes through, and never emits through a hidden global meter.

## M4. Placement and documents

**RULING:** Define the middleware mechanism in a new `dao.stream.middleware.md`; keep channel encryption/authentication integration details in `dao.stream.serve.md`. Keep network, key, and capability conventions out of `dao.stream.md`.

Add only a short generic contract statement: a host-composed wrapper may implement a handle by delegating the declared DaoStream operations to another handle, but it must honor the same result-map and surface contract. This is consistent with the contract’s host-local handle and with its allowance for transport-owned result keys. [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:37) [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:109)

Opaque handles are not a blocker: wrappers call only public operations. Declared surfaces are not a blocker: wrappers preserve or narrow them. No-graph assumptions are not a blocker: composition supplies an ordered middleware chain explicitly.

## M5. Delta and verdict

**RULING: READY TO SPECIFY** the middleware attachment model and serve integration. Specify both attachment points in v1; keep cryptographic algorithms, key issuance, and application capability policy as separately selected middleware implementations.

**WHY:** The converged serve messages need no new core frame: handle middleware wraps table entries/reflections, and channel middleware transforms the encoded message bytes. Authorization metadata is a middleware-qualified request extension; replies remain correlated outcome messages. The serve document must declare how a denied request is represented and ensure middleware preserves source cursors, anchors, and `gap` behavior.

**RISK / OWNER-VISIBLE:** A relay can be kept from reading payloads and metadata only with end-to-end whole-message encryption; network addresses, traffic timing, and sizes still leak. Without a trusted issuer, pre-shared secret, or other trust bootstrap, a capability cannot establish who should receive it. No middleware can make a malicious resource owner honor a capability policy, or make two untrusted peers trust each other automatically.
