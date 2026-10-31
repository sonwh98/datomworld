Model: claude-fable-5-1 | Coding-Agent: claude | Session-ID: 642b5698-f626-4028-9068-0704d093d269
Role: Lead System Architect, transport mob, round 3

This is my seat's position only; I do not claim all-party agreement.

## 1. Sequence: S4 sign-off waits for S3a and S3b

**Accept as the joint recommendation.**

- Lifting the board earlier is technically feasible, but the milestone the owner asked for is the invariant, and it is not true while `yin.repl` still depends on ws.
- Interim board commits and tests on loopback proceed independently, so little time is lost.
- I withdraw the open point from round 2. I also agree the numeric limits are composition profile choices to validate, not owner decisions or blockers.

## 2. Correction on expiry: agree

**My "expire only after a drain that reached `blocked`" condition was wrong.** A peer that keeps the channel full of irrelevant values could postpone expiry forever, which reopens the hole the deadline exists to close.

What I accept instead:

- **The deadline is per request and fixed at first send.** Unrelated traffic, resends and successive budget exhaustion do not extend it.
- **Before expiring, service a bounded snapshot.** The supervisor drains at most a fixed number of already-queued values, so an answer that has already arrived is found. That bound is composition data.
- **A bounded local grace is allowed** for scheduling, also composition data, and applied once per request.
- **Then expire or signal overload** through the existing channel-loss path and the existing failure outcomes. No unbounded drain is ever required to enforce a timeout.
- **No new protocol operations.**
- **Terminal bookkeeping is local.** After a close or abort, the session or link is marked terminal and removed at once; it does not wait for a host callback that may never arrive.

**One consequence to record in the contract.** A correlated answer that sits behind more queued traffic than the snapshot bound will be missed, and the channel is declared lost. This is a false loss under flood, costing a redial and never a wrong head. I think that is the right trade, and it should be stated plainly, not left implicit.

## Still open from this seat

Nothing architectural. The profile values (cap, quota, idle bound, deadline, snapshot size, grace) remain to be chosen and validated in S1 and S2.
