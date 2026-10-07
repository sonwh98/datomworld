Completed-GMT: 2026-09-30 12:23:00 GMT  
Completed-Local: 2026-09-30 19:23:00 +07

**SIGN-OFF WITHHELD.** Expiry is now limited to selected operations, and `drop-owner` removes their queries. But deferring expiry can change a write’s acknowledgement.

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| High | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:779) | `on-reply` accepts a reply for any pending query without checking its query deadline. If budget leaves a write unselected at tick 500, its query remains pending. A reply arriving at tick 600 can then prove and freshen that peer; the write may send to it and acknowledge, whereas expiry at tick 500 would have discarded the query. This makes the verdict depend on advance scheduling. | Reject a reply whose query deadline has elapsed, then let that owner’s budgeted advance record the failed try. Test a late reply to an operation skipped by a budget-1 step and assert no acknowledgement from it. |
| Medium | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:1049) | Each selected operation filters the entire global query map to find its own queries. The number of operations expired is bounded by `budget`, but the scan’s work is not. | Index query keys by owner, or otherwise visit only that owner’s queries. |

The round robin cursor reaches persistent pending operations, and the operation removal paths reviewed use `drop-owner`, so I found no orphaned-query path. The §8 recovery text now matches the one-resend behavior. Earlier cookie, fetch-only, facade, acknowledgement, solo, and oversize findings remain addressed; the implementer reports passing JVM, Node, and Dart suites. The new expiry test checks how many writes record failures per step, but it does not cover the late-reply verdict case above.
