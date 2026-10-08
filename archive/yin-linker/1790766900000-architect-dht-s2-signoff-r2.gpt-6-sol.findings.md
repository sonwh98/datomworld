Completed-GMT: 2026-09-30 11:57:00 GMT  
Completed-Local: 2026-09-30 18:57:00 +07

**SIGN-OFF WITHHELD.** The r2 changes address the earlier findings, but the advance stage still has an unbounded pass before its new cursor is applied.

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| High | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:1057) | `advance` calls `expire-queries` before selecting its budgeted operations. `expire-queries` walks **every** pending query at [line 1032](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:1032), so one step can process more than `budget` pending work. The new `[2 2 1]` test counts sends and cannot detect this. | Bound query expiry with its own cursor and budget, or expire only queries belonging to the selected operations. Test expiry state changes across several budget-2 steps. |
| Low | [dao.jing.dht.md](/Users/sto/workspace/datomworld-dht-s2/docs/design/dao.jing.dht.md:459) | §8 still says a need-cookie reply makes the requester *store* the cookie. The approved r2 behavior carries it on one resend and stores it only after a full reply. | State that distinction in the recovery rule. |

The operation cursor itself advances round robin without an apparent skip or starvation case when operations are added or removed. A need-cookie cookie remains untrusted until a full reply, and a refused resend removes its pending query. The new unpublished-node test exercises routing through a hint, remote fetch, and local caching. The facade test measures concurrent entries per node id. Both documentation files apply ruling (b). The other S2 acceptance paths remain covered by the earlier tests; the implementer reports passing JVM, Node, Dart, and lint checks.
