Completed-GMT: 2026-09-02 19:14:42 GMT
Completed-Local: 2026-09-03 02:14:42 Asia/Ho_Chi_Minh

## Applied edits

1. **Envelope ownership — introduction and “The v2 RPC layer”**  
   Before: R1 claimed an envelope under `:dao.stream.rpc/…`.  
   After: V1’s `dao.stream.apply` is the sole owner; RPC consumes `:dao.stream.apply/id`, `/op`, `/args`, `/ok`, and `/error`. Ownership wording was reconciled in the introduction, prerequisites, and R1. See [implementation plan](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:3) and [envelope contract](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:276).

2. **RPC transition algebra — “The v2 RPC layer”**  
   Added the ID allocator and collision/exhaustion rule; exhaustive client `append!` and `next` transitions; malformed response policy; conservative loss; distinct `:ws/closed`, `:ws/ended`, and survivable `:ws/error`; unknown-event forwarding; server-side pending response plus exact successor retention; malformed-request handling; and exhaustive terminal outcomes. See [client algebra](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:165) and [server algebra](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:242).

3. **Single state owner — “The REPL driver”**  
   Before: JVM input and Node/Dart handlers could compete with the poller.  
   After: `repl-step` alone reads queued input, evaluates, calls `request!`/`poll!`, prints completions, and updates prompts. All hosts hand input through a capacity-1024 ring buffer; JVM `read-line` and Node/Dart handlers only append lines. See [driver ownership](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:299).

4. **Endpoint lifecycle — Phase R4**  
   Chose to specify it in-plan because it belongs to R4’s serving composition, not the subordinate WebSocket transport. Added a composition-owned capacity-256 lifecycle medium, event envelope and closed event set, ownership, error classification, unknown-event policy, gap semantics, and asynchronous `:stopped` completion. See [Phase R4](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:519).

5. **Capacities — D3 and Phase R2**  
   Declared the VM ingress capacity as 4096; an ingress `gap` is fatal to the evaluation and requires reset. Declared `/repl` capacity as 1; because it carries no ordinary values, any gap is a fatal assembly-policy violation. See [D3](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:363) and [Phase R2](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:468).

6. **Build exceptions — introduction, “Namespaces and files,” R2, boundary**  
   Before: `shadow-cljs.edn` was incorrectly called the sole existing-file exception.  
   After: the plan honestly declares two additive exceptions: the Shadow `:yin-repl` build and four `deps.edn` aliases. Existing aliases and builds remain unchanged. See [build configuration](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:109) and [boundary](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:598).

7. **Boundary evaluators — “Boundary of this plan”**  
   Added `:space` beside `:semantic`, `:register`, and `:stack`, matching the settled four-evaluator count. See [boundary exclusions](/Users/sto/workspace/datomworld/docs/design/yin.repl.implementation-plan.md:611).

## Follow-up

The VM plan’s current V1 wording remains incomplete at [lines 398–422](/Users/sto/workspace/datomworld/docs/design/yin.vm.implementation-plan.md:398). It must add this contract:

> Client state includes a monotonic, never-reused safe-integer `:next-id`. Allocation reserves and increments it before append. Collision with any unsent, outstanding, or completed request—or exhaustion—is a terminal allocator error and never overwrites a request. `request!` handles every `append!` outcome: `ok` moves the request to outstanding; `full` retains the identical encoded request and allocated ID; `closed`, `invalid-value`, and `transport-error` complete it terminally.
>
> `poll!` handles every `next` outcome. `ok` advances to the exact returned successor before decoding; `blocked` changes nothing; `gap` advances to the recovery cursor and reports all outstanding requests lost; `end`, `cursor-mismatch`, `invalid-cursor`, and `transport-error` terminate the reader binding without changing its cursor and report all outstanding requests lost. Malformed and unsolicited responses are consumed once as diagnostics.
>
> Server state includes `:request-cursor`, `:pending-response`, `:pending-request-id`, `:pending-successor`, and `:terminal`. `serve-once!` retries a pending response before reading another request. After a successful `next`, it retains the exact successor and runs the handler at most once. `append!` `ok` advances once to that successor; `full` retains both response and successor without advancing or rerunning; `invalid-value`, `closed`, and `transport-error` advance once, report the response undeliverable, and terminate. Request-side `blocked`, `gap`, `end`, `cursor-mismatch`, `invalid-cursor`, and `transport-error` follow their corresponding unchanged, recovery-cursor, or terminal transitions.
>
> A malformed request never reaches a handler. With a usable ID it receives a correlated malformed-request error; without one it produces a local diagnostic and advances once. `dao.stream.apply` exclusively owns request and response constructors, predicates, validation, correlation IDs, and all `:dao.stream.apply/…` keys.
>
> The WebSocket decoder maps reconnectable `:ws/closed`, terminal `:ws/ended`, survivable non-terminal `:ws/error`, `:ws/opened`, `:ws/not-found`, and `:ws/transport-error` to client transitions. Unknown current or future event kinds are forwarded as non-terminal diagnostics.

No additional defective cluster echo was found: `docs/orchestrator-handoff.md` already records the correct envelope owner, and the current WebSocket vocabulary matches the amended plan.

The implementation plan was the only substantive repository file modified. The required heartbeat file was also maintained. No tests were run, and nothing was staged or committed.
