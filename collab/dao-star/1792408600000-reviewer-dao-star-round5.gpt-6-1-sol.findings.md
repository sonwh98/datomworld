Completed-GMT: 2026-10-19 10:50:20 GMT
Completed-Local: 2026-10-19 17:50:20 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a11500-e74e-7aa2-a0d7-7a8a9561d334

| Round-4 item | Status | Assessment |
|---|---|---|
| Reflected-read observation race | **Confirmed** | `dao.star.md:51–53` requires direct classification reads. The fact is published locally before the UI exposes its token; reading ingress before draining local facts therefore closes the race. Reflected ingress can delay the request without invalidating that causal order. |
| Immediate no-renewal criterion | **Still wrong — non-blocking** | `dao.star.md:163` correctly makes stopping observation-dependent, but its final clause treats all in-flight renewals as belonging to an already removed lease. After a driver’s own gap, the lease may still be live. |

The co-location rule is a **composition requirement, not a privileged node role**. Any peer can host that composition; its streams remain exposable to other peers. It restricts which handles this router may use for classification, without changing DaoStream or granting a server/client node special protocol authority.

No blocking contradiction found with `dao.stream.remote.md`, `dao.lease.md`, or `dao.stream.http.md`.

Non-blocking corrections:

| Severity | File:line | Evidence | Correction | Classification |
|---|---|---|---|---|
| **Low** | `docs/design/dao.star.md:163` | A driver’s gap does not itself remove the lease. Eligible renewals arriving before reclaim still count (`dao.lease.md:148–150`); only renewals for a retired lease are ignored. | Say: “In-flight renewals may count while the lease remains live; renewals observed after retirement do not revive it.” | **Non-blocking** |
| **Low** | `docs/design/dao.star.md:53` | “Only … ingress and egress may be reflections” is broader than the classification constraint. A driver separated from the HTTP boundary also needs to observe the boundary’s exchange-lease medium (`:91–93`). | Scope the restriction explicitly to classification media; permit reflected observation of exchange grants and lapses. | **Non-blocking** |

All fenced EDN examples parse: three forms in `dao.star.md`, six in `dao.stream.http.md`. Files unchanged.

**Ready for owner review. No blocking defects remain; two wording corrections remain.**
