# Step 3 Design & Implementation Plan: Generic Byte-Budget Enforcement (§5)

## 1. Specification Requirements (`docs/design/yin.repl.dht-composition.md` §5, §8, §9)
- **Exit Condition (Commit 3):**
  > "Generic byte-budget enforcement across bulk, incremental, restore, and reuse. Oversize refusal, terminating branch construction, policy compatibility and unchanged manifest proved."
- **Key Invariants:**
  1. **Payload Budget Derivation:** Derived from transport composition, not hardcoded. The hardcoded branching factor `64` in `yin.repl.dht:367-371` dies when generic byte-bounded publication activates.
  2. **Immutable `:publication-policy`:** Carries `{:branching-factor n, :max-payload-bytes b}` where `nil` means no declared byte ceiling.
  3. **Manifest Unchanged:** Schema remains strictly `{:indexes ... :count ... :branching-factor ...}`. Count cap continues to mean branching factor.
  4. **Policy Compatibility & Reuse:** Reuse is permitted ONLY under the same policy; a changed or unknown policy requires verification or reconstruction before publishing. Reused clean subgraphs must respect the budget.
  5. **Policy Threading:** Threaded through bulk, session, restore, and incremental publication so both paths see the same number.
  6. **B-Tree Underfull/Separator Semantics:** Ensure large separator datoms and underfull-node mutation terminate with a clear refusal for unrepresentable trees rather than looping.

## 2. Team Delegation Plan
- **Primary Implementer:** `qwen3.8-max` (or `glm-5.3` for storage/B-tree if needed; using `qwen3.8-max` for continuity and verified execution).
- **Independent Reviewer (Different Family):** `gpt-6-astra` (or `deepseek-4.1-flash` if quota tight; `gpt-6-astra` / `gpt-6.1-sol` on codex).
- **Orchestrator (`agy`):** Mechanical coordination, syntax validation, syncing to `shiva.stigmergy.systems` for remote test runs, log recording.
- **Master Status:** `master` remains completely locked. All work stays on `yin-repl-dht-composition`.
