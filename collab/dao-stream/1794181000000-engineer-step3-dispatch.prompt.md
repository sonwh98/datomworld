Created-GMT: 2026-11-10 20:55:00 GMT
Created-Local: 2026-11-11 03:55:00 +07
Coding-Agent: qwen
Model: qwen3.8-max
Role: Storage and Distributed Systems Engineer
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition

# Task: Step 3 — Generic byte-budget enforcement across bulk, incremental, restore, and reuse (§5)

## Governing Specification & Instructions
Read first:
- `docs/design/yin.repl.dht-composition.md` (§5: Publication policy and frame budgets, §8, §9 Exit condition 3).
- `docs/design/datom.world.md`

## Mission & Requirements for Step 3:
1. **Thread Immutable `:publication-policy` (§5):**
   - A generic publication policy carries `{:branching-factor n, :max-payload-bytes b}` where `nil` means no declared byte ceiling.
   - Thread `:publication-policy` consistently across:
     - bulk publication (`dao.space.index/publish!`),
     - session indexer (`yin.repl.index/make-indexer`, `yin.repl/create-state`),
     - restore (`dao.space.index/restore`, `yin.repl.index/restore-session`),
     - incremental publication.
   - Kill hardcoded branching factor `64` in `yin.repl.dht` (or replace with generic policy default).

2. **Policy Compatibility & Reuse Protection (§5):**
   - The manifest schema remains strictly `{:indexes … :count … :branching-factor …}`.
   - A reused graph or clean subgraph must be certified against the publication policy: reuse is permitted ONLY under the same policy; if the policy changed or is unknown, verify or reconstruct before publishing.
   - Reused recorder dirty blobs must not bypass a newly lowered byte budget.

3. **Oversize Refusal & Terminating B-Tree Semantics (§5, §8):**
   - If an unrepresentable tree or single datom/separator exceeds the payload budget, ensure terminating refusal rather than infinite recursion or invalid state.
   - Ensure underfull-node mutation terminates cleanly.

4. **Preserve Synchronous Seam & Gates:**
   - Keep delimiter balance and syntax valid.
   - Do NOT run heavy test suites locally on the laptop (per `docs/agents/build-n-test.md`). Run only G0 syntax checks (`clj -M`).
   - Leave edits in working tree for remote verification on `shiva` and Architect sign-off.
