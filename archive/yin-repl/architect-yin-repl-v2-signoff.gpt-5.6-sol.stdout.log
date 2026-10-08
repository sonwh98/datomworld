Completed-GMT: 2026-09-02 19:02:30 GMT
Completed-Local: 2026-09-03 02:02:30 Asia/Ho_Chi_Minh

SIGN-OFF: WITHHELD.

Findings:

- BLOCKER | docs/design/yin.repl.implementation-plan.md:225-229 | The plan assigns the canonical envelope to R1 under `:dao.stream.rpc/…` keys. This contradicts the settled ownership recorded correctly at lines 328-336 and 374-377 and in `yin.vm.implementation-plan.md:375-396`: V1’s `dao.stream.apply` owns the envelope, and RPC consumes its keys. The defect was relocated. | Replace this paragraph with V1/`dao.stream.apply` ownership and `:dao.stream.apply/…` keys.

- HIGH | docs/design/yin.repl.implementation-plan.md:189-205,379-382 | RPC decoding handles `:ws/opened`, `:ws/closed`, `:ws/not-found`, and `:ws/transport-error`, but omits `:ws/ended` and survivable `:ws/error`. R5 nevertheless requires `:ws/ended` at lines 470-471. | Define `:ws/ended` as terminal, distinguish it from reconnectable `:ws/closed`, report outstanding requests lost, and state the non-terminal handling of `:ws/error`.

- HIGH | docs/design/yin.repl.implementation-plan.md:159-185,210-229 | The “explicit” client state has no correlation-ID allocator despite claiming identical immutable states mint identical IDs. The server state retains neither the successor request cursor nor a complete transition record, so it cannot keep the cursor unchanged across `full`, avoid re-running the handler, and later advance to the exact successor. Total handling of malformed requests and terminal `next`/`append!` outcomes is also unstated. The incomplete pending-response discipline is repeated in `yin.vm.implementation-plan.md:417-422`. | Put the complete RPC transition contract in canonical V1: ID allocator/collision rule, pending response plus successor cursor, malformed-input policy, and exhaustive outcome transitions; mirror only the dependency here.

- HIGH | docs/design/yin.repl.implementation-plan.md:243-276 | “One driver owns client state” conflicts with the JVM arrangement: `read-line` owns input while a background thread runs `repl-step`. No input handoff is defined, and `repl-step` does not consume the stated input queue. Node/Dart line handlers also call `request!` directly. | Define one owner of REPL/RPC state on every host. Line callbacks/readers deposit or enqueue input; `repl-step` alone dequeues, evaluates, calls `request!`/`poll!`, and updates prompts and completions.

- HIGH | docs/design/yin.repl.implementation-plan.md:449-454 | R4 says the locations and forms of bind success/failure, upgrade failure, listener error, and asynchronous close completion “must be defined here,” but does not define them or mark them as another specification blocker. An implementer must invent the endpoint lifecycle channel and vocabulary. | Specify the lifecycle medium, event shapes, ownership, and stop completion, or explicitly assign them to an amendment gate before R4.

- HIGH | docs/design/yin.repl.implementation-plan.md:299-305,397-409,440-448 | Required capacities are absent for the VM ingress ring buffer and `/repl` service-lifetime ring buffer. The signed sibling explicitly calls ingress capacity and eviction meaning correctness parameters at `yin.vm.implementation-plan.md:358-365`. | Declare both capacities and state what eviction/gap means. The already-declared output capacity 4096 and boundary-medium capacities 8192 are sufficient.

- HIGH | docs/design/yin.repl.implementation-plan.md:16-22,106-116,414-419,499-503 | `shadow-cljs.edn` is declared the sole existing-file exception, but the plan also modifies `deps.edn` with four absent aliases. The contradiction recurs in R2; the boundary protects existing aliases but not the file. | Preserve the user’s single-exception ruling by removing the `deps.edn` edits and defining launch/build entry points through new files and existing aliases, or obtain a separate explicit ruling before describing `deps.edn` as a second exception.

- MEDIUM | docs/design/yin.repl.implementation-plan.md:507-511 | The boundary lists only `semantic`, `register`, and `stack` as excluded evaluators, contradicting the settled four-evaluator count at lines 66-68. | Add `space`; do not classify it as a subsystem.

Shortest changes that would earn GRANTED:

1. Restore `dao.stream.apply` as the sole envelope owner everywhere.
2. Complete the RPC transition algebra, including IDs, successor-cursor retention, all websocket events, malformed requests, and terminal outcomes.
3. Make `repl-step` the single state owner and define input handoff.
4. Settle or gate endpoint lifecycle events, and declare the two missing capacities/loss policies.
5. Resolve the `deps.edn` contradiction under the single-exception ruling and add `space` to the boundary.

Requested properties that passed review:

- The plan correctly restricts the VM to `ast-walker`; four evaluators remain.
- `:make-stream` is host-supplied with no default.
- Telemetry is absent, with CLI telemetry options rejected.
- Destructive `:stream/take` is removed.
- Reject-mode retention is not reintroduced; VM totality and composition retention remain distinct.
- Flow control remains deferred.
- The three websocket-spec amendments are explicitly marked as blockers for R3-R5. The four transport-plan items transitively gate R3 through the Phase 4a prerequisite.
- The approved `shadow-cljs.edn` exception is recorded with its additive-build reason, although the separate `deps.edn` edit breaks its claimed uniqueness.
- No multi-slash keyword appears in the portable examples.
- The v1 semantic hazards—`:position`, `closed?`, destructive drains, waiters, and host promise/future polling—are identified for adaptation rather than copied as a namespace-only closure.
- All cited working-tree file/line references checked were substantively accurate.
- None of the three non-blocking VM r7 notes is required to resolve the REPL-specific findings above.
