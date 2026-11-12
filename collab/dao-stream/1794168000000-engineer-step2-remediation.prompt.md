Created-GMT: 2026-11-09 18:59:00 GMT
Created-Local: 2026-11-10 01:59:00 +07
Coding-Agent: qwen
Model: qwen3.8-max
Role: DaoStream and Distributed Protocol Engineer
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition

# Task: Step 2 Remediation — Resolve Architect Review Findings 1–6

## Governing Specification & Instructions
Read first:
- `docs/design/yin.repl.dht-composition.md` (§2, §3, §4, §6, §7, §9, §10).
- `collab/dao-stream/1794165600000-architect-step2-review.gpt-6-astra.findings.md`

## The 6 Findings to Fix in `/Users/sto/workspace/datomworld-yin-repl-dht`:

1. **[P1] Dependency Closure (§2):**
   `yin.repl` must NOT transitively load `dao.space.dht` or `dao.jing.dht` through `yin.vm.linker.interpreter`.
   - In `src/cljc/yin/vm/linker/interpreter.cljc`: isolate or decouple the DHT-specific dependencies (`dao.space.dht`, `yin.vm.linker.dht`, `yin.vm.linker.head`).
   - Specifically: ensure that when a fresh JVM requires `yin.repl`, neither `dao.space.dht` nor `dao.jing.dht` is loaded.

2. **[P1] In-Engine HEAD Execution (§3, §4):**
   - Publication must NOT synchronously call `:head-fn` from inside `index.cljc` via `service-local!`.
   - The engine indexer emits `{:op :publish :id :round :manifest}` to `:out` and records `:pending-publication`.
   - The composition (or local runner outside the indexer's private `emit-publication`) is what consumes requests, calls the store HEAD writer, and injects `{:op :publication-result ...}` back into the result medium, which `accept-publication-result` / `settle-publication` consumes.
   - Clean up `create-state` / `make-indexer` so no callback hook sits inside the engine indexer.

3. **[P1] Query Deferral Wiring (§6, §7):**
   - In `src/cljc/yin/repl.cljc` (at the `query/serve` call site around line 1448): pass `:registered-ops` from `(:host-modules state)` (or query registered ops) into `query/serve`.
   - This ensures registered composition queries like `(yin.head/heads)` or `dao.space.dht` are recognized as registered foreign operations and properly deferred, while unregistered unknown operations refuse immediately with `:dao.stream.apply/unknown-operation`.

4. **[P1] Parked VM Resume on Composition Progress (§7):**
   - In `src/cljc/yin/shell/session.cljc` (in `recheck-on-tick` and related handlers): when `shell.query/serve-services` or `shell.linker/serve-links` makes progress (answers a request), pass initial progress `true` into `repl/recheck-pending` (e.g. `(repl/recheck-pending state nil true)` or expose public arity/function) so the driver immediately runs the VM instead of requiring another round of internal progress.

5. **[P1] Outstanding Publication Queueing & Serialization (§3, §4):**
   - In `src/cljc/yin/repl/index.cljc`: do not unconditionally overwrite an unsettled `:pending-publication` on subsequent commits.
   - Settle pending results first, or track pending publications such that a queued success is not discarded when another commit arrives.

6. **[P1] Signed Publication Completion (§7):**
   - In `src/cljc/yin/shell/query.cljc` (`link-answer` for `publish`): do NOT return `{:ok ...}` immediately before publication completes.
   - Ensure the publication is settled (or correlated) so that failure/refusal of the publication reaches the caller.

## Execution Rules:
- Keep all edits in `/Users/sto/workspace/datomworld-yin-repl-dht`.
- Do NOT run heavy test suites locally on the laptop (per `docs/agents/build-n-test.md`). Run only focused G0 single-var syntax checks locally (`clj -M`).
- Preserve delimiter balance and Clojure syntax.
- Do NOT commit; leave edits in the working tree for verification on `shiva` and Architect sign-off.
