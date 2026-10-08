Completed-GMT: 2026-09-06 18:30:00 GMT
Completed-Local: 2026-09-07 01:30:00 +07 (Asia/Bangkok)
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

# Defect report: `dao.stream.rpc` allocator-error strands outstanding requests

Filed by the Lead Engineering Orchestrator, found during the `dao.jing.v2`
plan review by `glm-5.3` and verified against the tree by this seat. **Not
fixed here — no authorization to change `src/`, and the user was away.** This
is a work item for Stream & Network (`claude-opus-5` primary per `team.md`),
reviewed cross-family.

## The defect

`dao.stream.rpc` states that loss is conservative: every outstanding
request is reported lost. It honors that on five terminal/loss paths
(`rpc.cljc:371, 377, 403, 410, 415`, all via `lose-outstanding`).

`allocation-failure` (`rpc.cljc:155-161`) is the sixth and does not:

```clojure
(defn- allocation-failure
  [state code]
  (let [state (-> state
                  (assoc :terminal :dao.stream.rpc/allocator-error)
                  (append-diagnostic code (:next-id state)))]
    (rpc-result :dao.stream.rpc/allocator-error state
                :dao.stream.rpc/diagnostic (last (:diagnostics state)))))
```

It sets `:terminal` and appends a diagnostic, and never calls
`lose-outstanding`. Because `poll!` returns immediately on a terminal state
(`rpc.cljc:430`) and `rebind` refuses any terminal that is not
`/detached` (`rpc.cljc:465-476`), every request already in `:outstanding` at
that moment is stranded: no completion ever arrives for its id, and there is
no recovery path.

## Reachability

Low. `allocate-request` fails only on `id-exhausted` (`:next-id` past
`max-safe-id`, ~9e15 allocations) or `id-collision` (`ids-in-use?` true for
`:next-id`, which monotonic allocation plus drained completions prevents in
single-driver use). This is a correctness hole, not an operational one.

## Affected consumers today

- **`yin.repl`** — shipped. `src/cljc/yin/repl_adapter.cljc:117` maps
  `:dao.stream.rpc/allocator-error` to `:yin.repl.adapter/terminal`;
  any outstanding REPL request at that moment never completes.
- **`dao.jing.v2.remote` J3c** — planned. Both architects ruled that the
  consumer must not compensate; J3c is gated on this fix instead. See
  `docs/design/dao.jing.v2.implementation-plan.md`, Decision 3's lifecycle
  table (the `dependent` row) and J3c.

## Test coverage

**None.** `test/dao/stream/rpc_test.cljc` contains no occurrence of
`allocator-error`, `id-exhausted` or `id-collision`.

## Proposed fix (from `claude-fable-5-1`, endorsed by `gpt-6-astra`)

`allocation-failure` calls `(lose-outstanding state
:dao.stream.rpc/allocator-error true)` before returning, keeping its
diagnostic. Every outstanding request then completes with reason
`allocator-error` through the ordinary outbox; `:terminal` is set as today;
`rebind` still refuses. Two lines.

Test in `rpc_test.cljc`: one outstanding request; force `:next-id` onto an
in-use id; assert one completion with reason `allocator-error`, `:outstanding`
empty, `:terminal` set. Add `id-exhausted` and `id-collision` cases, which the
suite lacks entirely.

## Provenance

- `collab/adversarial-dao-jing-v2-delta-r2.glm-5.3.findings.md` — the finding
  (glm-5.3, session 50d48a71-9ff9-44b7-8dc0-b334e5f42aac)
- `collab/architect-dao-jing-v2-allocator.claude-fable-5-1.findings.md`
  (session e425d8bd-ad4c-44f7-aaed-54cb3196fd0f)
- `collab/consensus-dao-jing-v2-allocator.gpt-6-astra.findings.md`
  (session 01a0779a-7a9d-79e0-930a-ac19af7164b4)
