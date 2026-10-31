Completed-GMT: 2026-10-24 11:16:31 GMT
Completed-Local: 2026-10-24 18:16:31 +0700

I edited nothing. I read `jvm.clj` (lines 23–63) and the context in your prompt. I did not re-run the http-kit repro.

## Ruling: (a), with a small wording correction. `jvm.clj` does not change.

### What "a failed socket write closes the exchange" means when the host cannot tell

The sentence in `dao.stream.http.md` is conditional: the boundary acts on a failure the host reports. The seam reports failure only by throwing or answering false. An http-kit `kit/send!` that buffers and answers true is not a failure report, so the boundary has nothing to act on.

This is the contract's irreducible corner, not a violation. The boundary cannot invent an outcome the host never gave it, and the document already says a performed write is not a delivery ("the step appends no per-effect outcome"). A write that succeeds into a buffer is a performed write, not a delivered one.

What the sentence promises is detection when the host can detect. Where it cannot, `dao.star.md` already names the backstop: the judge's `:silence` lapse and the `close-exchange` reclaim.

### Why (a)

- **It changes no code and almost no text.** The designed liveness path covers the case, and the spec calls a one-shot wire write "bytes for the wire", not evidence.
- **The lease is the detector the spec already built.** Keep-alive chunks and renewals were deliberately made two separate things, and "neither stands in for the other." Under (a) the keep-alive chunk is still wire bytes, and the renewal is still the judge's evidence. A dead peer that http-kit hides simply stops being useful to anyone, so the responder's own control flow stops renewing for it and the lease lapses. In practice the responder drops an exchange whose `:http/closed` it reads, and an unread dead exchange lapses.
- **Latency is bounded and sizeable by the composition.** It is the exchange lease duration plus the judge cadence. The Sizing paragraph already makes the duration the composition's choice, so the keep-alive interval and duration can be sized to the latency it wants.

### Rejected alternatives

- **(b) Block on the `kit/send!` promise per write.**
  - A bounded wait puts a timeout, which is a clock, into `egress-step`. The boundary's design says the step "takes no clock and holds no timer."
  - Waiting on delivery also contradicts "a performed write is not a delivery."
  - I also can't confirm that dereferencing the promise would surface a closed peer. The repro showed buffered sends answering true 40 times, so a deref might still return true. You would be adding a stall with no evidence it detects anything.
  - It would reopen a verified Phase A file for a speculative fix.
- **(c) A third "unknown" answer from `:write!`.**
  - Core already treats "no failure reported" as "unknown," because it never learns delivery. A third value would make explicit what the behavior already is, at the cost of changing the seam contract in `http.cljc` and every host that implements it.
  - It would add an outcome nothing consumes.
  - It is the largest text change of the three, so it fails your "least text" criterion.

### Does the criterion "a closed socket is observed as `:http/closed`" pass?

It passes for a closure the host does report, and it does not pass for an undetected client disconnect on http-kit. The criterion should be read as "when the host reports a close or failed write, it is deposited as `:http/closed`." The honest test splits in two:

1. **Host-reported close.** A seam-level test where the fake or real host triggers the `on-close` path, `peer-closed!`, and asserts exactly one `:http/closed` is deposited and the exchange is dropped by the driver. This covers the boundary's behavior. If the Phase B loopback can trigger an actual `on-close` another way (for example, the server closes, or a client closes mid-request before the response head), use that; the repro shows only the idle-streaming case is silent.
2. **Silent peer.** The reclaim-path test the delegate already wrote: the exchange lapses by `:silence`, `close-exchange` runs, the adapter deposits `:http/closed`, and then the judge records `:lapsed`. This is the honest end-to-end test for a half-dead exchange on http-kit.

The owner should be told plainly that item 2 is the test for a real client disconnect on this host. It is not a real-disconnect test. The completion criteria text for the clause can stay, with the Honest Limit below.

### What must change

**`jvm.clj`:** nothing. The `:write!` at line 29 and the `:on-close` handler at lines 61–63 are as the contract intends.

**Docs, minimal:** one Honest Limit entry, in `dao.stream.http.md` beside "Observably gone" or in a Limits section, and a matching sentence in `dao.star.md`'s Liveness: "A host whose send buffers (http-kit) may not report a peer that disconnects while an exchange is idle; the exchange lapses by `:silence` and is closed by reclaim, with latency bounded by the lease duration plus judge cadence." Name http-kit as the instance, not as the rule, so another host (cljs or cljd) is not presumed to behave the same.

**Optional, no code:** if the owner wants faster detection later, the right place is a host that exposes real socket state to `:write!` (Netty, or http-kit with a TCP-level probe). That is a host change under the existing seam, not a seam change.

### Residual risk to record

- A false negative window exists: between a client's disconnect and the lapse, the responder may keep projecting patches to a dead exchange. Buffered writes are bounded by http-kit's own buffering, not by dao.star. That is a resource cost, bounded by the lease duration.
- If someone later sets a very long exchange duration for a quiet subscription, detection latency grows with it. Sizing guidance should say the duration is also the dead-peer detection bound on hosts like this.
