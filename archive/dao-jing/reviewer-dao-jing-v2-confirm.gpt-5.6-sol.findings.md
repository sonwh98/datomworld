Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7

I’ll verify each prior finding against the revised plan’s concrete phase criteria and API sequencing, then separately inspect only the newly added material for regressions. I’ll keep this read-only and won’t run the suite.
Completed-GMT: 2026-09-06 17:07:34 GMT
Completed-Local: 2026-09-07 00:07:34 Asia/Bangkok
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7

P1 Decision 2 vs Axiom 1 | REJECTED-AND-I-DISSENT | The revision names the host-boundary violation as a pre-existing open item, but does not resolve it: `dao.jing.file` still performs direct filesystem IO behind `:put-content-fn` (`docs/design/dao.jing.v2.implementation-plan.md:226-244`), while the superior architecture says a host boundary is a stream boundary and rejects callable adapters (`docs/design/datom.world.md:53-68`). The no-wait rule does not establish that a v2 append-log is impossible: `append!` may report local acceptance while remote answers arrive later (`docs/design/dao.stream.md:537-553`). The revision therefore documents, but does not address, the rejected architectural defect.

P1 stream-free shared core / transitive dependency | ADDRESSED | Moving the v1 observer to `dao.jing.observer` and removing the v1 require from `dao.jing` makes the v2 observer transitively v1-free (`docs/design/dao.jing.v2.implementation-plan.md:493-512,578-583`). The explicit end-condition check now tests direct and transitive absence (`:609-611`).

P1 `busy` progress defect | ADDRESSED | `step` now retries `:unsent` through `rpc/request!`, polls, abandons unsent work on terminal state, then publishes (`docs/design/dao.jing.v2.implementation-plan.md:317-339`). J3a includes both successful clearing and a persistent-busy-without-step test (`:628-632`).

P1 `request-put` is not `materialize!` | ADDRESSED | J3c adds `request-materialize`, derives the address, performs put, performs conditional get/read-back on `:present`, and emits integrity failures as data (`docs/design/dao.jing.v2.implementation-plan.md:362-380`). The end condition now requires materialization round-trips (`:838-842`).

P2 `step` drops diagnostics | ADDRESSED | `:diagnostics` is now part of the result and is specified as exactly-once publication (`docs/design/dao.jing.v2.implementation-plan.md:298-305,340-358`). J3a tests allocator diagnostics, unsolicited responses, decode errors, and one-shot clearing (`:642-651`).

P2 golden-record compatibility gap | ADDRESSED | J1 adds overlong-length, sub-four-byte, and negative-length torn-tail cases, followed by append and reopen checks (`docs/design/dao.jing.v2.implementation-plan.md:514-546`). This is materially stronger than the original single-record fixture.

P2 J5 naming unsettled | ADDRESSED | The three-namespace end state is now explicitly decided in Decision 4, independently of the stream namespace decision, and J5 executes the renames (`docs/design/dao.jing.v2.implementation-plan.md:395-421,719-735`).

Defects introduced by the revision

P2 | J1.3 / Boundary of this plan | The primary route now deletes `src/cljc/dao/stream/log.cljc` and its tests from a DaoJing plan (`docs/design/dao.jing.v2.implementation-plan.md:548-569,770-785`). This is outside the stated `dao.jing*` boundary and conflicts with the stream plan’s ownership of transport migration. The “scope-contingent” alternative leaves two materially different execution plans. | Make legacy append-log retirement an explicitly stream-plan-owned dependency, coordinated atomically with J1; keep the Jing plan responsible for integration/recovery evidence only.

P2 | J3c stepped materializer | `request-materialize` issues an internal verify `request-get`, but the plan does not define how that verify intent is retried if another unsent request causes `busy`; it only says “busy applies as for any request” (`docs/design/dao.jing.v2.implementation-plan.md:368-379,674-684`). The public operation has no separate verify-resume API. | Specify the materializer’s per-id pending-verify state and how its driver retries after `busy`; add a test with an unrelated unsent request occupying the RPC slot.

P2 | J1.1 scope gate | The revision makes moving the v1 observer and repointing seven tests conditional on authorization (`docs/design/dao.jing.v2.implementation-plan.md:15-18,493-512`). That leaves J1 without one settled executable path, despite the brief requiring architectural decisions to be settled before phases. | Decide the scope route before implementation authorization, or choose the documentation route and make its temporary transitive dependency an explicit phase criterion.

No other newly introduced defects found.
