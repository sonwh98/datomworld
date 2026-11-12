Created-GMT: 2026-11-10 22:35:00 GMT
Created-Local: 2026-11-11 05:35:00 +07
Coding-Agent: qwen
Model: qwen3.8-max
Role: Distributed Systems and Protocol Engineer
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition

# Task: Step 4 — Wire durable/DHT compositions, CLI, recovery and shutdown (§6, §7, §9 Exit condition 4)

## Governing Specification & Instructions
Read first:
- `docs/design/yin.repl.dht-composition.md` (§6 Coupling inventory, §7 Adapter dispositions, §9 Exit condition 4, §10 Swappability gates).
- `docs/design/datom.world.md`

## Mission & Exit Condition for Step 4:
> "Wire durable/DHT compositions, CLI, recovery and shutdown. HEAD ordering, hydration admission and resource ownership preserved on every host."

### Specific Targets to Implement in `/Users/sto/workspace/datomworld-yin-repl-dht`:
1. **Relocate DHT Composition to `yin.shell.dht` (`src/cljc/yin/shell/dht.cljc`):**
   - Move composition module from `yin.repl.dht` to `src/cljc/yin/shell/dht.cljc` (or compose cleanly in `yin.shell.*`).
   - Clean up remaining DHT references in `yin/repl/`:
     - `yin/repl/dht.cljc`: deprecate/relocate to `yin/shell/dht.cljc` or retain thin facade for backward compatibility only if strictly necessary. Target: Gate A audit (`rg 'dao\.jing\.dht' src/cljc/yin/repl/` must return nothing).
     - `yin/repl/store.cljc`: Remove DHT scheme redirect (`open-store` accepts generic `dao.jing` content store; DHT store selection moves to composition).
     - `yin/repl/main.cljc`: Wire the host entrypoint over `yin.shell.session` and `yin.shell.dht`. DHT CLI flags, help text, lifecycle, and shutdown stay composition-side.
2. **Hydration Admission & HEAD Ordering (§4, §6):**
   - Ensure hydration gates evaluation input.
   - Pinned hydration, follower startup, and head board updates run via composition.
   - Store HEAD ordering preserved: publication materializes blobs, reads manifest back, then records durable HEAD.
3. **Resource Ownership & Lifecycle (§6, §7):**
   - Shutdown closes resources once under composition ownership.
   - Reset retention policy provided explicitly to engine rather than probing concrete `:durable-dir`.
4. **Execution Discipline:**
   - Keep delimiter balance and syntax valid.
   - Do NOT run heavy test suites locally on the laptop (per `docs/agents/build-n-test.md`). Run only G0 syntax checks (`clj -M`).
   - Leave edits in working tree for remote verification on `shiva` and Architect sign-off.
