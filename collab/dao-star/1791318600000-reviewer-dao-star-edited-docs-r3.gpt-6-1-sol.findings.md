Completed-GMT: 2026-10-07 06:28:37 GMT
Completed-Local: 2026-10-07 13:28:37 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a11500-e74e-7aa2-a0d7-7a8a9561d334

| Round-2 finding | Status | Evidence |
|---|---|---|
| 1. Missing root/token facts — High | **Confirmed** | `dao.star.md:39–49` makes token = occurrence id and lineage id = root id, supplies root/successor facts, and requires successful publication before exposing ids. |
| 2. Ingress gap never reaches drivers — High | **Still wrong** | `dao.star.md:89` explicitly propagates the gap, fixing the missing topology. Successful delivery and handling of a session medium’s own gap remain unspecified; see below. |
| 3. Unsupported compaction — High | **Confirmed** | `dao.star.md:49,153` requires complete history and defers compaction to OD-4. |
| 4. Judge writes driver-only medium — Medium | **Confirmed** | `dao.star.md:49,93` uses the judge’s lease-medium lapse record; session-facts remains driver-authored. |
| 5. Acting after holder bound — Medium | **Confirmed** | `dao.star.md:93` requires observing the grant, stopping at the bound, and treating subsequent input as begin. |
| 6. Forks make current ambiguous — Medium | **Confirmed** | `dao.star.md:79,151` makes Mode 1 one-shot and defers fork semantics. |
| 7. Missing content coordinate — Medium | **Confirmed** | `dao.star.md:115` supplies request/answer descriptors in the content coordinate. |
| 8. Absence classification/private answers — Medium | **Confirmed** | `dao.star.md:117` distinguishes content absence, missing service, and refusal; specifies a gated pair per lineage and address-scoped authorization. |
| 9. Misnamed restart rule — Low | **Confirmed** | `dao.star.md:89` distinguishes inventory reconciliation from lost-ledger reclaim-and-regrant. |

The three High rulings are architecturally sound choices. Token identity removes redundant bindings; explicit gap events preserve stream-mediated control flow; complete-history retention respects the current contract. Gap delivery needs the remaining guarantee below.

| Severity | File:line | Invariant/evidence | Recommended correction | Classification |
|---|---|---|---|---|
| **High** | `docs/design/dao.star.md:41–59` | Successful append establishes publication, not observation by the router’s independent session-facts cursor. The browser can receive a newly published token and request `/s/` before the router indexes its root fact; line 59 then incorrectly treats it as unknown and starts another lineage. | Before classifying a token as unknown, bring the facts projection through an explicitly established publication position, or use a stream-mediated readiness acknowledgement before exposing the token. Apply equivalent coordination to successor classification. | **Blocking** |
| **High** | `docs/design/dao.star.md:89` | Appending a gap event does not guarantee that a driver observes it. Session-medium admission/retention and failed-append handling are unspecified. An evicting session medium can lose the notification or terminal events and return its own `gap`, for which no driver policy is stated. The driver can consequently retain and renew forgotten subscriptions, contradicting criterion `:159`. | Specify ordered notification delivery with checked append outcomes. Require drivers to treat their session medium’s own `gap` as subscription loss too; define recovery or teardown when forwarding cannot succeed. | **Blocking** |

No additional non-blocking defects found. The unchanged HTTP boundary remains satisfactory for this review. All fenced EDN examples parse independently: three forms in `dao.star.md`, six in `dao.stream.http.md`.

The six owner decisions remain honored. The explicitly deferred content-service, compaction, and other external dependencies are not reopened here.

**Readiness verdict: not ready for owner sign-off.** Two blocking causality/delivery gaps remain. The owner can review the architectural choices, but the text should not yet claim Mode 1 is fully specified and buildable. Files unchanged.
