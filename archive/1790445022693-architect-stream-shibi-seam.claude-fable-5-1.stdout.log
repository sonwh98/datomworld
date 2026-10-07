Completed-GMT: 2026-09-26 17:51:16 GMT
Completed-Local: 2026-09-27 00:51:16 +07
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# The capability-agnostic seam

## S1. Boundary

Middleware and serve own the **slot, the step, the context plumbing and the refusal**. ShiBi owns **everything about what fills the slot**. Verified state of ShiBi: unimplemented (`docs/bootstrap.md:65`), Macaroon-style with attenuatable, offline-verifiable, revocable caveats (`docs/design/dao.space.security.md:33`), undecided between capability and currency (`docs/design/dao.stream.discovery.md:183-190`).

| Proposal from last round | Ruling | Why |
|---|---|---|
| Lease grant is the capability (deepseek) | **DROP** | `dao.lease.md:94-95` forbids any DaoStream operation consulting a lease and `:295-296` says it gates nothing; codex was right. |
| Open key carrying the token (fable) | **KEEP as generic seam**, renamed `:dao.stream/credential`, format opaque | The request map is open; a reserved slot is all serve needs to carry an unspecified value. |
| Issuer-equals-verifier HMAC (fable) | **MOVE to ShiBi** | Token construction and signature scheme are the capability system. |
| Attenuation and delegation | **MOVE to ShiBi** | The seam only guarantees the slot holds any value, including a chain. |
| Revocation | **MOVE to ShiBi**; seam keeps the context-stream plumbing | The seam offers explicit source streams a verifier folds; what a revocation fact is belongs to ShiBi. |
| Refusal outcome (`:dao.stream/refused` fable, `:dao.stream/unauthorized` codex) | **KEEP as `:dao.stream/refused`** | The contract must stay capability-free; "unauthorized" presupposes authorization; "refused" is what OD-1 already calls the safe fallback (`dao.stream.md:842-844`). |
| Bearer replay protection | **MOVE to ShiBi**; seam exposes request id and channel identity in context | Whether a token is bearer or bound is token design. |
| Token-to-lease binding | **MOVE to ShiBi**; seam lets a verifier fold the grantor's grants stream | Possible later without seam change. |
| Reference `require-cap` / `with-cap` | **DROP**, replaced by generic `gate` and `present` with a trivial policy | A capability system must not be smuggled in as a reference middleware. |

**RISK.** Low. Everything dropped or moved was addition on top of the seam; nothing in the converged serve shapes depended on it.

## S2. The seam

**RULING.** Four pieces, all composition data, no new wire shape.

**(a) Travel.** One reserved open key on the request map, `:dao.stream/credential`, value opaque to middleware and serve, present per request because the mirror is stateless. A per-channel credential is a convention: the reflection-side middleware attaches the same value to every request. Channel-level authentication of whole messages is the AEAD channel middleware from last round and is not a credential at all.

**(b) Authorize step.** A mirror-side middleware, `gate`, wrapped onto the table entry's handle. `apply-request` gains a context argument the mirror fills: `(apply-request h ctx req)`, and middleware transforms take it: `in (fn [ctx req] -> req | outcome)`, `out (fn [ctx req outcome] -> outcome)`. Serve defines the context keys it supplies: `:dao.stream.serve/channel`, the channel attachment identity the request arrived on, and nothing else in v1. Local callers pass `{}`.

```clojure
(gate {:dao.stream.mw/verify  (fn [state ctx req] -> nil | reason)   ; pure; nil allows
       :dao.stream.mw/fold    (fn [state fact] -> state)              ; pure
       :dao.stream.mw/sources [{:handle h :cursor c} ...]             ; explicit context streams
       :dao.stream.mw/state   s0})
;; in: drain each source to blocked with its own cursor, fold every fact into state,
;;     then (verify state ctx req); nil -> req passes; reason -> refusal outcome
```

**(c) Attach step.** A reflection-side middleware, `present`: `in` is `(fn [ctx req] (assoc req :dao.stream/credential ((:dao.stream.mw/present mw) ctx req)))`. The `present` function is composition-supplied and may consult the request, so a later ShiBi can mint a per-request attenuated token.

**(d) Context without hidden state.** A verifier reads nothing ambient. Whatever it needs arrives as facts on streams named in `:dao.stream.mw/sources`, drained with the gate's own cursors and folded into the gate's own state: the grantor's grants stream for lease liveness (`dao.lease.md:243-244`; the judge's ledger is private, `:134-135`, so it is folded from the stream, not read), a tick stream for time as readings (`dao.lease.md:103-106`), a revocation stream once ShiBi defines one. Same drain-then-answer pattern the reflection uses; no operation waits.

**Worked example, allow-list keyed on channel, no ShiBi.**

```clojure
(gate {:dao.stream.mw/verify (fn [_ ctx _] (when-not (contains? allowed (:dao.stream.serve/channel ctx))
                                             :dao.stream.mw/not-allowed))
       :dao.stream.mw/fold identity :dao.stream.mw/sources [] :dao.stream.mw/state nil})
```

A `next` request from an unlisted channel is answered `{:dao.stream/outcome :dao.stream/refused :dao.stream.mw/reason :dao.stream.mw/not-allowed :dao.stream/id n ...}` and the source handle is never touched. Credential ignored, seam exercised end to end.

**RISK.** The gate drains its sources on every operation, so a busy entry with a chatty grants stream pays a fold per request. Bounded by a per-drain budget like every other drain in the design.

## S3. What the seam must not foreclose

| ShiBi need | Status |
|---|---|
| Attenuation and delegation, a credential that is a chain | **Supported.** The slot holds any value; `verify` is pure over it. |
| Offline verification | **Supported.** `verify` sees only credential, folded state, request and context; it has no network. |
| Revocation | **Supported.** A revocation stream is one more source; `fold` maintains the revoked set. |
| Caveats on op and identity | **Supported.** Both are in the request map. |
| Caveats on time | **Supported** via a tick stream in sources, as readings; an absolute-time caveat ("until T", `dao.space.security.md:35`) is ShiBi's to reconcile with `dao.lease.md:97`, not the seam's. |
| Budget or metered use, stateful, without hidden state | **Needs one seam extension: decision emission.** The gate appends its own decisions `{:dao.stream.mw/decision allow|refuse :dao.stream/id n ...}` to a composed stream that is also one of its sources, so spend state is derived from a stream the composition can read, not held privately. This is last round's metering middleware given a name. |
| Tokens issued by any peer, no privileged issuer | **Supported.** The verifier is per table entry on the peer that owns the resource; who may issue is ShiBi's. |
| Spendable across peers, double-spend | **Unresolved**, depends on capability versus currency (`dao.stream.discovery.md:183-190`). The seam does not depend on it: a budget spent at one verifier is decision emission; a budget spent across verifiers needs consensus the seam neither offers nor precludes. |
| Migration credentials for agents | **Supported.** A credential is a value; it travels in the continuation's stream cell, and an agent's effect may carry `:dao.stream/credential` directly, since the reflection passes open keys through. |

**RISK.** None of these can be tested until ShiBi exists; the trivial policy tests only the plumbing.

## S4. Refusal

**RULING.** `:dao.stream/refused`: "a policy composed on this handle declined the operation; nothing observed, nothing appended; not retryable unless `:dao.stream/retry?`". Valid on `cursor`, `next`, `append!`. Not on `descriptor` or `close!`, whose outcome set is `ok` alone (`dao.stream.md:213-214`, `:583-584`): a name carries no authorization (`:285-286`), so the reflection's attach probe can never be refused and gating begins at the first `cursor`. It needs its own outcome under OD-1: the contract names squeezing it into `not-found` a misdescription (`:826-829`) and OD-1's fallback rule already describes an unrecognized outcome as exactly this refusal (`:842-844`), so the row is the rule made explicit. Reason travels under `:dao.stream.mw/reason`, optional and opaque; ShiBi defines its vocabulary. The reflection files and returns it verbatim like every outcome, and does not mark the reflection gone: a refusal is per operation.

**RISK.** One outcome row added to three tables in `dao.stream.md`. The word "capability" appears nowhere in it.

## S5. Lease attribution

**RULING.** Closed by per-author media alone, independent of ShiBi. `dao.lease.md:237-241` names per-author media as a valid resolver, `:274-276` recommends per-attachment media as the isolation, and `yin.repl.link-policy.md:200-208` states the same conclusion for the shell case: a capability "would add nothing until leases guard something that untrusted writers can reach". The lease round's per-lease renewal stream is that resolver. The residual caveat, that anyone who learns the renewal identity can renew, is confidentiality of a name, not attribution, and is exactly what a ShiBi `gate` on the renewal entry later closes through the seam, additively.

**RISK.** None new.

## S6. Delta and verdict

**Delta against the middleware-round design.**

- **Removed.** `:dao.stream/cap`, `require-cap`, `with-cap`, the HMAC issuer scheme, token-to-lease binding, and the claim that caps and channel encryption "go together" as a design rule.
- **Added.** Reserved open key `:dao.stream/credential`, opaque. Context argument on `apply-request` and on both middleware transforms; serve supplies `:dao.stream.serve/channel`. Two generic middlewares, `gate` with pure `verify` and `fold` over explicit sources, and `present` with a pure `present` function. Decision emission named as the one anticipated extension.
- **Kept.** `wrap`, the position rule, the four prohibitions, AEAD at value and channel level, metering, the `:dao.stream/refused` row, the composed-handle sentence.
- **Unchanged.** Request, answer and fragment shapes; descriptors; mirror statelessness; relay pair; meeting board; lease streams.

**Serve ships in v1:** the seam, the channel allow-list policy as the trivial stand-in, AEAD channel and value middleware, metering. **Deferred to ShiBi:** credential format, issuance, attenuation, revocation facts, replay binding, reason vocabulary, budgets, and the capability-versus-currency decision.

**Documents.** `dao.stream.middleware.md`: `wrap`, `apply-request`, context, `gate`, `present`, decision emission, use of `refused`. `dao.stream.serve.md`: attachment points, context keys, the credential key on the wire. `docs/design/shibi.md` as a stub stating only its obligations to the seam: produce a `verify`, a `present`, a `fold` with its source streams, a reason vocabulary, and record the capability-versus-currency decision. `dao.stream.md`: OD-1, OD-2, OD-3 decision 2, the `refused` row, the composed-handle sentence.

**Verdict: READY TO SPECIFY.** No blocker.

**Owner-visible.** Until ShiBi is specified, gating is allow-lists and pre-shared secrets. The seam cannot decide who may attach, only where that decision runs. Budgets across peers wait on capability versus currency. Encryption key distribution stays a convention. Recommended order: contract amendments, then middleware and serve specs, then implementation with the trivial policy, then the capability-versus-currency decision, then ShiBi against the seam.

## S7. Name

**RULING: `dao.stream.mirror`**, in the optical sense stated in the spec's first sentence: the same stream seen from elsewhere, never a copy. It names the mechanism, both halves of it are symmetric, and the mirror step and reflection handle are already its two parts.

The orchestrator's challenge is right on both counts. **Reject `serve`** and **`expose`**: they name one role and one direction, the thing the invariant forbids. **Reject `peer`**: the design has no protocol-level peer, and the word pulls in identity, membership and discovery, which `dao.stream.discovery.md` owns. **Reject `remote`**: it names only the far side, and `dao.jing.remote` keeps `remote.step` after its transport half retires. **Reject `link`**: `yin.repl.link` exists and the de Bruijn stream-linker work already uses the word. **Reject `channel`**: the channel is one of four concepts and `ws` already is one. **Reject `wire`**: the wire is Transit and CBOR. One caveat on the winner: in storage vocabulary a mirror is a replica, which is exactly what OD-3 (a) says this is not; the first sentence of the spec must disarm that reading. Collisions: none. `src/cljc/dao/stream/` holds no `mirror`; `dao.stream.serving` retires; `yin.repl.serve` and `dao.jing.remote` are untouched. The descriptor type `:dao.stream/remote` may stay, since it names what the descriptor reaches, not the module.
