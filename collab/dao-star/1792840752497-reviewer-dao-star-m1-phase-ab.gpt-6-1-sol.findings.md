Created-GMT: 2026-10-24 11:19:12 GMT  
Reviewed-GMT: 2026-10-24 11:23:16 GMT  
Coding-Agent: codex  
Session-ID: unavailable  
Role: Reviewer (adversarial)  
Scope: uncommitted Phase A+B, branch `dao-star-m1`, stated base `f6856bbc`  
Method: static review; no edits, staging, commits, or suite reruns.

**Verdict: NOT SIGNED OFF**

Findings use `severity | file:line | evidence | recommended correction`.

1. **HIGH — implementation defect | `src/cljc/dao/stream/http.cljc:148` | Concurrent claims can produce duplicate terminal events.** `claim!` sets `claimed` inside a retryable `swap!` function but never resets it on the absent-entry branch. Two closers can both observe the entry; one commits, while the other retries against its removal and nevertheless returns its previously captured entry. Both then close and finish it. This violates exactly one terminal event. | Derive ownership from the committed transition, using a CAS loop or committed old/new state. Add a deterministic competing-claims test.

2. **HIGH — implementation defect | `src/cljc/dao/stream/http.cljc:133` | The `:awaiting` handshake can lose a terminal event.** `finish!` can set `pending?` true during an unsuccessful swap attempt. Meanwhile, `accept!` removes the awaiting slot after observing nil. The retry sees no slot but leaves `pending?` true, suppressing the direct deposit. Neither side publishes the terminal event. | Base the deposit decision on the successful atomic transition. Test this exact interleaving, including request-before-terminal ordering.

3. **HIGH — architectural defect | `docs/design/dao.star.md:95`; `src/cljc/dao/star/driver.cljc:343` | The amended dead-peer detection bound is unsupported.** When http-kit reports neither disconnect nor failed write, the subscription remains in `:subs`. Every tick continues renewing its exchange lease; the retained subscription also drives lineage renewal. Consequently, neither lease need lapse by `:silence`. The architect’s assertion that responder control flow stops renewing lacks a mechanism here. | Supply an observable condition that stops renewal, or revise the architectural guarantee. Lease duration alone cannot bound detection while the holder keeps renewing. A forced boundary close does not establish this property.

4. **HIGH — architectural conflict and assembly gap | `docs/design/dao.star.md:50`; `test/dao/star/driver_test.clj:88` | Session facts are wired to an evicting ring buffer despite requiring complete history.** The architect’s ring-buffer ruling conflicts with both `dao.star.md` and the controlling stream contract, which explicitly says sizing cannot substitute for complete-history retention. Router and lineage-grantor drains continue after fact gaps. Evicted roots can prevent grants; replaying a retained suffix cannot reconstruct the occurrence chain. | Use a declared complete-history transport, such as an appropriate non-evicting memory log, and require that declaration at assembly. Resolve the conflicting architect ruling. Compaction may remain deferred; retaining history cannot.

5. **HIGH — implementation defect | `src/cljc/dao/star/driver.cljc:193`; `:283`; `:300` | Acting-before-grant and fail-open holder checks remain.** Begin publishes HTML immediately after the root fact, before the lineage judge can grant and before the driver observes that grant. Subscribe projects before constructing and observing its exchange holder. Normal handling checks `granted?` and `past-bound?`, rather than `holding?`; an invalid tick makes the underlying `at-bound?` unanswerable/false and can therefore permit consumption or projection. | Stage initial responses until the relevant grants are observed. Gate leased actions through the fail-closed holder discipline, with explicit treatment of invalid readings and gaps.

6. **HIGH — implementation defect | `src/cljc/dao/star/driver.cljc:356` | Pending publication bypasses the lineage bound.** After a consumption-fact append fails, a later session pass updates lease state and then calls `publish!` unconditionally. If publication recovers after the holder reaches its bound or observes lapse, it still appends the transition, invokes projection, answers the input, and patches subscriptions. The existing publication test recovers before expiration. | Recheck tenure before publishing or projecting pending work. Define retirement of an unpublished transition and test recovery after bound, lapse, and gap.

7. **HIGH — implementation defect | `src/cljc/dao/star/driver.cljc:263` | Input consumption depends incorrectly on model emission.** A valid program can read an input and park again without emitting a view. This branch resumes the continuation but appends no consumption fact, mints no successor, leaves the token current, and does not renew the lineage. Replaying that token can execute another continuation step. This violates “one input, one successor.” | Record every consumed input independently of view emission, retaining the previous model when appropriate. Test a program that consumes multiple inputs between model emissions.

8. **HIGH — implementation defect | `src/cljc/dao/star/lease.cljc:99`; `src/cljc/dao/star/driver.cljc:338` | Gap handling does not cover every medium the driver reads.** Holder stepping ignores tick gaps despite its docstring. A lineage lease-medium gap marks its agent gapped but does not clear session subscriptions; exchange agents can continue renewing them. An exchange lease-medium gap removes only the affected subscription. View and begin drains also discard gap information. The passing driver-gap test exercises only the routed session medium. | Propagate gap information to driver-level handling and forget the required subscription set before further renewal. Cover tick, lineage-lease, exchange-lease, view, and begin cursors.

9. **MEDIUM — implementation defect | `src/cljc/dao/star/lease.cljc:103` | Grants can be permanently skipped before the holder’s first tick.** The fact cursor advances even when `reading` is nil and no grant can be observed. Judges and holders have separate tick streams, so a judge can publish a grant before the holder receives its first reading. Later ticks cannot recover the skipped grant. | Retain pending grants or defer advancing their observation until a usable holder reading exists. Test independently scheduled judge and holder ticks.

10. **MEDIUM — implementation gap | `src/cljc/dao/star/lease.cljc:177`; `src/cljc/dao/star/driver.cljc:249` | Failed halt releases are retained but never retried by the driver.** `release!` stores the release on non-`ok`, but subsequent session passes do not call it again. Reclamation eventually depends on silence instead of completing the intended release path. | Drive pending release publication on subsequent passes, respecting the transport’s failure/retry semantics. Test one failed release append followed by recovery.

11. **MEDIUM — test adequacy | `test/dao/star/router_test.clj:70`; `test/dao/star/driver_test.clj:405` | The claimed observation-order coverage would pass with the critical order reversed.** The router test publishes the fact before starting the pass, so draining facts before ingress would also pass. The chase tests use a three-round pump, allowing classification state to catch up before the next request. Likewise, the “under half” renewal test checks only that some renewal exists. | Introduce a controlled interleaving that publishes a fact and deposits its request during the ingress read. Assert renewal positions against tick readings and the strict half-duration bound.

12. **MEDIUM — portability implementation gap | `src/cljc/dao/star/signals.cljc:18` | CLJD decoding is explicitly absent, beyond merely untested compilation.** Its JSON parser and URL decoder return nil, so valid client signals always become `{}`. JVM behavior does not establish portable behavior. | Implement the CLJD decoding seam or explicitly scope this delivery to JVM support. Require the new namespaces in target-lane checks before claiming CLJS/CLJD support.

The delegate’s flagged choices resolve as follows:

- **Lifetime declaration:** acceptable assembly vocabulary. Requiring an explicit outlives-boundary declaration expresses the specification’s existing requirement.
- **Incarnation from configuration or UUID:** acceptable when configuration supplies a portable, fresh incarnation. Reusing configured incarnations violates the documented caller obligation.
- **Stricter malformed-effect closes:** acceptable. Integer status and string/bytes chunks enforce the stated effect shape; ending before the mandatory first head is out of order.
- **Draining egress to `blocked`:** conforms. Continuous production can prevent completion, so this alone establishes no bounded pass duration.
- **Reclaim success based on absence afterward:** correct; an already-closed exchange is successfully reclaimed.
- **Inventory absence classified as `:policy`:** correct for completed exchanges and reconciliation.
- **Co-location declaration plus JVM reflection check:** acceptable under the architect’s explicit declaration-based assembly ruling. It relies on honest composition declarations.
- **`clojure.data.json`:** acceptable on JVM; it is already declared in `deps.edn:8`.

The loopback close test honestly identifies its boundary-side `close-exchange` trigger. It establishes reclaim-to-terminal-to-driver cleanup, **not client-disconnect detection or silence-based detection of an existing subscription**. Its comment at `loopback_test.clj:141` incorrectly says EOF; the actual assertion checks the chunked-response terminator. Rename and correct that wording.

The host-boundary gate remains an integration gap, not a demonstrated failing check. Its scan is limited to the committed closure and profile additions; a passing run alone would not classify these new namespaces. CLJS/CLJD compilation remains unproven. Missing non-JVM listeners, Mode 2, and history compaction are intentionally deferred work.

Requested properties that passed static review, within the qualifications above:

- HTTP request envelope normalization, body/open limits, newest egress cursor, ordered single-step effect interpretation, and ignoring effects for absent exchanges.
- Sequential head/chunk/end handling and observable shutdown on reported write failure, egress gap, or egress termination.
- Actual router implementation reads ingress before draining both classification media and then classifies.
- Five request kinds, reconnect through an original token, and stale one-shot re-projection on the tested model-emitting path.
- Fact acceptance precedes successor projection on the normal publication path.
- A real per-session `dao.await`/`yin.vm` continuation supplies sequential program execution.
- Signals remain decoded input claims rather than routing authority or persisted continuation state.
- Inventory reconciliation, absent-resource reclaim, and underlying judge handling of live versus retired renewals.
- Routed-session-gap cleanup and observed-exchange-lapse cleanup on their specifically tested paths.
- JVM loopback response/resume and explicit boundary-reclaim cleanup, as reported by the orchestrator’s completed checks.