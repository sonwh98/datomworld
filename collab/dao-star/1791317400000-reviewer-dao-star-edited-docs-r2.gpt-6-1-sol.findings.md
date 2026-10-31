Completed-GMT: 2026-10-07 06:24:01 GMT
Completed-Local: 2026-10-07 13:24:01 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a11500-e74e-7aa2-a0d7-7a8a9561d334

| Finding | Status | Evidence |
|---|---|---|
| 1. Subscription consumes input | **Confirmed** | `dao.star.md:45–51` separates `/s/` subscriptions from `/k/` inputs and explicitly forbids stepping on subscription. |
| 2. Consumed tokens lose lineage | **Still wrong** | The lineage lease fixes release-on-consumption (`:87`), but the facts at `:53` contain neither token bindings nor a root-creation fact. The claimed token index cannot yet be reconstructed. |
| 3. Evicting ingress loses exchange state | **Still wrong** | Inventory and explicit subscription state are appropriate (`dao.stream.http.md:31–37`, `dao.star.md:69`). However, `dao.star.md:83` does not specify how a router-observed gap reaches drivers reading routed session media. |
| 4. Reclaim deletes historical records | **Confirmed** | `dao.star.md:87` distinguishes retiring the runnable resource from preserving history and lease records. |
| 5. Holder attribution unspecified | **Confirmed** | `dao.star.md:87` supplies holder identity, envelope attribution, grant observation, and the connected-but-idle policy. New lease-discipline issues remain below. |
| 6. Q6 fetch path missing | **Still wrong** | `dao.star.md:111` names the content service, but the notice at `:109` still supplies no request/answer descriptors or content-store coordinate. Content absence is also misclassified. |
| 7. Terminal/effect ordering | **Confirmed** | `dao.stream.http.md:62–68,99–112` removes cross-medium ordering and consistently ignores post-end effects. |
| 8. Invalid EDN examples | **Confirmed** | Independently parsed both blocks with `clojure.edn/read`: three forms each, six total. |
| 9. HTTP document names application technologies | **Confirmed** | `dao.stream.http.md:7–8` is application-agnostic; word-boundary search found no Datastar, SSE, or `yin.vm` mentions. |

The Architect’s partial rebuttal of finding 7 is sound: a performed write does not establish delivery. My original concern was the stronger claim that a terminal event identifies unperformed effects and precedes ignored effects across media. Those claims are now removed.

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| **High** | `docs/design/dao.star.md:53` | Root creation emits no specified fact, and successor facts contain no token. Token → lineage → current therefore requires unstated information. | Define root and token-binding facts, or explicitly make occurrence identity the unguessable token. Require successful publication before exposing each token. |
| **High** | `docs/design/dao.star.md:83` | The router reads ingress (`:31`); drivers read routed session media. An ingress gap need not produce a gap on any driver’s cursor, so drivers can keep renewing subscriptions whose terminal events were lost. | Define an explicit router loss event delivered to affected drivers, or give drivers their own ingress cursors. Specify terminal-event routing too. |
| **High** | `docs/design/dao.star.md:53` | Chain-preserving compaction is offered as an operative alternative, but `dao.stream.md:861–870` says compaction is unavailable and forbidden for complete-history transports. OD-4’s resolution is proposed. | Require the existing complete-history transport now. Mark compaction conditional on a settled contract and snapshot/replay protocol. |
| **Medium** | `docs/design/dao.star.md:87` | Session-facts is “written only by session drivers,” yet the possessing judge’s reclaim appends the retirement fact there. Author and authority are ambiguous. | Permit explicitly attributed judge-authored retirement facts, or specify stream-mediated delivery to the driver. Keep renewal attribution separate from retirement authority. |
| **Medium** | `docs/design/dao.star.md:87` | Renewal on a consumed input does not address an input arriving after the holder’s bound but before judge reclaim. `dao.lease.md:213–215` requires the holder to stop regardless of notification. | Require a valid holder bound before consumption or projection that relies on tenure. Specify the outcome for late input; renewal must not silently revive expired holder authority. |
| **Medium** | `docs/design/dao.star.md:73` | Opt-in forks create multiple successor branches, whereas `:39` defines current as “the one with no successor.” That ceases to be unique. | Define branch identity and subscription routing for opt-in forks, or explicitly defer fork operation pending those semantics. |
| **Medium** | `docs/design/dao.star.md:109` | A channel descriptor does not identify the two streams required by the content client. The remote implementation plan §1 specifies `:dao.jing/requests` and `:dao.jing/answers`. | Include the content-store coordinate in the notice, or specify an explicit service-name discovery convention. |
| **Medium** | `docs/design/dao.star.md:111` | Content absence is `:jing/found? false` under `dao.stream.remote.implementation-plan.md:17–24`; remote `not-found` means a missing served stream. Shared answer streams also expose responses to their readers (`dao.stream.remote.md:549–555`). | Distinguish missing content, missing service, and refusal. Specify lineage-scoped address authorization and private or equivalently protected answers. |
| **Low** | `docs/design/dao.star.md:83` | Granting previously unleased exchanges is inventory reconciliation, not `dao.lease.md`’s restart rule, which requires reclaim before re-grant (`:197–199`). | Name the reconciliation separately; reserve reclaim-and-regrant for actual lost-ledger recovery. |

`open-exchanges` itself is sound: it exposes owned resource inventory to the locally composed judge without adding a DaoStream operation or privileged node.

All six owner decisions remain honored, and Q6 remains explicitly unconfirmed. **The documents are not yet ready for owner sign-off.** The remaining defects need architectural corrections within those decisions, especially token facts, gap propagation, and the unsupported compaction alternative. Files were not edited.
