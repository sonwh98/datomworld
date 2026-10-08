I’ll check R1–R5 against current HEAD, then review the shared-checkpoint boundary and the coupling introduced by `coverage` and `checkpoint`. I’ll keep the review read-only.
Completed-GMT: 2026-09-13 09:32:43 GMT
Completed-Local: 2026-09-13 16:32:43 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac
Role: Lead System Architect (review) | Model: gpt-6-astra

Reviewed HEAD `a4395d8`. Read-only; no files written. All locations below refer to `docs/design/dao.space.index.as-observer.md`.

| Finding | Status | Resolution |
|---|---|---|
| R1 — access to observer state | **Resolved** | §2.1:145–155 gives `coverage` and `checkpoint` the whole session. §4.2:495–515 binds capture to the publication boundary; 541–559 separates consumer restoration from observer reattachment. |
| R2 — completeness survives draining | **Resolved** | §2.2:174–187 separates monotonic `:rejected` from drainable diagnostics. The candidate carries `:rejected`, and restoration reinstates it. |
| R3 — reserved-id partition and parity | **Resolved** | §3.1:308–326 defines disjoint categories and admits reserved `e` in resolved mode, preserving existing behavior for that case. |
| R4 — empty batches and watermark | **Resolved** | §2.2 defines empty batches as ordinal-only progress, initializes `:max-t` to `nil`, and specifies within-row allocation order. §4.2:561–569 derives watermark zero for empty history. |
| R5 — shared allocator rewind | **Resolved by explicit scope restriction** | §4.2:541–549 rejects shared-allocator restoration. §8:776–780 defers coordinated recovery explicitly; the sibling-allocation rejection test is specified. |

**[P3 — suggestion/alignment] Phase 3 retains two stale assertions.**

Location: `docs/design/dao.space.index.as-observer.md:750–753`.

The test paragraph still says watermark is always `max-t + 1` and that `:defects` survives restoration. The authoritative contract now uses zero when `:max-t` is `nil` and persists `:rejected`, not drainable diagnostics.

**Correction:** Update those assertions to the conditional watermark rule and `:ingress-gaps`/`:rejected`. This is test-description cleanup; the corrected contracts and Phase 0′ tests already establish the intended behavior.

**Shared-domain checkpoint boundary**

Refusing `:shared` in `restore` is the right mandatory safety boundary. It prevents allocation-domain corruption even if a caller supplies a previously captured record.

A shared session may still publish its trees and capture a candidate for inspection or future composition-level coordination. However, that candidate should not be advertised or selected as an independently resumable checkpoint under the current contract. I recommend rejecting promotion for `:shared`, or explicitly labeling the captured value as snapshot-only until coordinated recovery exists. There is no need to forbid publication or observational capture.

The present scope restriction is sufficient for approval; coordinated recovery is intentionally deferred rather than implicitly promised.

**Placement of `coverage` and `checkpoint`**

I have no architectural objection to keeping them in `dao.space.index`. They consume the generic observer’s public session shape in the permitted dependency direction. They neither expose index semantics to the observer loop nor introduce knowledge of sibling evaluators.

Reading an opaque cursor and gap count is ordinary composition over protocol state. It does not collapse interpretation and execution. A helper outside both namespaces would be an organizational alternative, not an invariant improvement. Keep these functions data-only: no cursor inspection, sibling discovery or hidden stream operations.

The adjacent publication/capture calls are also acceptable as an explicit composition contract, provided the caller installs the returned consumer before capture and runs no intervening observer round.

The strengthened promotion rule correctly checks the manifest and every reachable address across all four roots through the durable read path, including leaves. Checkpoint-record availability and retained-suffix availability are now correctly separate obligations.

No blocking architectural findings remain. Approval covers the design with its stated implementation prerequisites, including partial-session exception handling, kept-cursor attachment, and the macro provenance amendment.

**Verdict: APPROVE.**
