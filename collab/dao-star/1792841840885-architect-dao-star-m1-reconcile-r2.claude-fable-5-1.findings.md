Completed-GMT: 2026-10-24 11:39:19 GMT
Completed-Local: 2026-10-24 18:39:19 +0700

I edited nothing. I read `memory_log.cljc`, `lease.cljc` (the medium declarations), `driver.cljc:330-350`, and the retention declaration in `dao/star/lease.cljc:217`. I did not read the codex findings file itself. Both rulings rest on your prompt's account of the findings.

## Finding 3: the reviewer is right, and my prior claim was wrong

`tick-subscriptions!` keeps a subscription in `:subs` until it sees `:lapsed` or `:gapped`. On http-kit no `:http/closed` is ever deposited. So the driver renews the exchange lease every tick, and the retained subscription renews the lineage lease too (`driver.cljc:343`). Neither lapses by silence. My sentence "the responder's own control flow stops renewing for it" described a stop that nothing performs, and the amended Sizing sentence ("the duration is also the dead-peer detection bound") has no support.

### Ruling: (iii), a tenure cap. The lease's `:dao.lease/max` is the bound, and (ii) is the honest fallback for anything beyond it.

**Why not (i), buffer exhaustion.**
- It is an unverified hypothesis. Nobody has driven a write to exhaustion on http-kit.
- Even if it holds, the bound is a function of socket buffer sizes, kernel settings and chunk size. That is not a number a composition can size against.
- It would make the keep-alive chunk the detector, which is the conflation the spec's "neither stands in for the other" sentence exists to prevent.
- It may be worth a later experiment, but a spec bound cannot rest on it.

**Why a cap works where renewal does not.**
- `dao.lease.md` says the cap bounds tenure from the first grant, and renewals do not move it. A holder that renews forever still reaches `at-bound?` and stops. That is the mechanism the lineage lease already uses ("stops at its bound").
- Giving the *exchange* lease a `:dao.lease/max` bounds how long any one SSE exchange can stay open, whether or not its peer is alive. When the max is reached the driver stops renewing (`at-bound?`), the lease lapses, reclaim runs `close-exchange`, and the adapter deposits `:http/closed`. A live Datastar client sees the stream end and reconnects with `/s/<token>`, which is a new subscribe and a re-projection of the current occurrence. A dead peer is released.
- Cost: every healthy subscription reconnects once per max. That is harmless, because subscribe consumes nothing and the projection is a derivation.
- The bound is real, sized by the composition, and independent of the host's buffering behavior.

**Check before relying on this.** I read the lease functions in `lease.cljc` only by name and docstring. `dao.star.md` says the lineage grant takes an optional `:dao.lease/max`, so the grant supports it. The implementer must confirm that `author-grant`/`grant` accepts `:dao.lease/max` for the *exchange* grant in the same way, and that the driver's exchange holders honor `at-bound?`. If they don't, the replacement text below overstates it. This is the one place I'm asserting something I haven't traced.

**What stays unbounded.** A dead peer that holds an exchange *under its max* is still undetected until the max. Within the max there is no detection on a host that hides closure. That is the honest limit.

### Replacement text

**`dao.star.md`, Liveness, "Keep-alive is the responder's"** (replace the whole paragraph):

> **Keep-alive is the responder's.** On its tick it may append an `:http/chunk` carrying an SSE comment line. That chunk is bytes for the wire, so a host that reports write failure discovers a dead peer by writing; the renewal is evidence for the judge. Neither stands in for the other. A peer's close or a reported write failure is deposited as `:http/closed`; the responder reads it as data and drops the exchange, and the judge ends the exchange lease with cause `:policy`. A host whose send buffers and whose close notification is silent for an idle exchange (http-kit is one) reports neither, so a chunk written to a dead peer is not evidence of anything, and a responder that keeps renewing a subscription it believes open keeps the lease alive. Such an exchange ends at its lease's `:dao.lease/max`, not before.

**`dao.star.md`, Liveness, Sizing sentence** (replace the sentence "A false lapse is possible" paragraph's detection claim, and wherever the duration was named the detection bound):

> The duration bounds silence, not death. The detection bound for a peer that disconnects while an exchange is idle is the exchange lease's `:dao.lease/max` on a host that does not report the close, and the host's own close notification, when it gives one, on a host that does. The composition sizes `:dao.lease/max` against the longest it will tolerate a dead subscription holding a socket and a driver slot, and accepts that every healthy subscription reconnects at that interval.

The exchange grant in Liveness should also say it carries `:dao.lease/max` (a duration), parallel to the lineage grant's optional cap. For exchanges, make it required for subscription exchanges, so that no subscription is uncapped.

**`dao.stream.http.md`, Honest Limit** (replace the entry I proposed last time):

> **A host may not report a peer that disconnects while an exchange is idle.** A host whose send buffers and whose close notification does not fire for an idle streaming exchange (http-kit, observed) gives the boundary no failed write and no close to deposit. The boundary invents none. The composition's lease is the detector, and for a responder that renews, only the lease's `:dao.lease/max` ends such an exchange. The boundary takes no clock and does not add one.

### Honest test for the criterion

Two tests, as before, with the second corrected:
1. Host-reported close deposits exactly one `:http/closed`.
2. A silent subscription with a cap: the peer goes half-dead (the driver keeps renewing), the clock advances past the exchange lease's `:dao.lease/max`, and the test asserts that `close-exchange` ran, `:http/closed` was deposited and `:lapsed` was recorded. This replaces the "lapses by `:silence`" claim for subscriptions. The test must include a driver that renews on every tick, because that is the case the reviewer found.

Keep the separate silent-exchange test for a non-renewing, never-answered exchange; that one still lapses by `:silence` as specified.

**`jvm.clj`:** unchanged. The change is in the exchange grant (add the cap) and the specs.

## Finding 4: confirmed. The session-facts medium must be complete history

**Ruling:** reject my earlier ring-buffer ruling for session-facts. The spec says the medium is a complete-history transport and `dao.stream.md` says sizing cannot substitute for retention. Eviction would lose roots, and a lost root makes a live lineage unknown, which the router would answer as begin. That is a correctness failure, not a sizing one. Compaction stays deferred to OD-4.

**Constructor:** `dao.stream.memory-log/create!` with the spec `{:dao.stream/type :dao.stream/memory-log}`. It must carry no `:dao.stream.memory-log/…` key, because that namespace is owned and empty, and a capacity key is rejected with `:dao.stream/invalid-spec`. Retention is complete by construction: the state is one vector dense from position 0, no expression removes an element, and `gap` is excluded.

**Declaration to the lease constructors:** `make-judge`'s `:media` entry and the holder's `:fact` entry use `:medium {:retention :complete ...}` with no `:capacity`. `check-medium-declaration!` refuses a `:complete` medium that declares a capacity and an `:evict-oldest` one that declares none. Add `:value-domain` and `:attribution :envelope-key` as the existing declarations at `dao/star/lease.cljc:217` do for the evicting case. I only saw the `:retention :evict-oldest :capacity` line there, so the implementer should mirror the other keys from that helper.

**Assembly requirement:** the driver and router constructors must refuse a session-facts medium whose declaration is not `:retention :complete`, throwing ex-info with `:refused :session-facts`, parallel to how the boundary refuses a reject-mode ingress. The check is on the declaration, not on handle introspection, which is the lease namespace's own rule.

**Tests:** the Phase B tests create session-facts with `memory-log/create!` and declare it `:complete`. Add one test that the constructor refuses an `:evict-oldest` declaration, and one that drives past what the old ring-buffer capacity would have been and asserts the root fact is still readable from the origin.

**Consequence worth noting:** with no `gap` possible on session-facts, the router and driver never take the gap path on that medium. Their gap handling for it becomes unreachable code, and the tests for it should use the lease medium or ingress instead. The lease medium is a separate question; I'd leave it evict-oldest, as the spec's Sizing paragraph treats it.

**Not covered:** I did not check what else assumed a ring buffer in `dao/star/lease.cljc` beyond line 217, or how the two constructors share the declaration. The implementer should grep for `ringbuffer` and `:evict-oldest` under `src/cljc/dao/star/` and the tests.
