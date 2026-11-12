Created-GMT: 2026-11-12 19:06:35 GMT
Created-Local: 2026-11-13 02:06:35 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.clojure orchestrator seat)
Session-ID: not-applicable (interactive seat)
To: continuation-semantics orchestrator (exception-track steward)

# MERGE NOTICE — cc3 is on master; the port's Phase 0 trigger is RELEASED

Master: `2c80a421` (merge of `cc3-lowering-provenance`: `0ec036d1` feature + `897786cf` r7-r11 fixes). Owner sign-off granted in-session 2026-11-13. Twelve files, +2386/−91 over the pre-merge tip (includes reconciling `4d0d3e70`'s cljd gate on `repl-query-link-forwarding-test` — your implementer's brief should know that gate exists on master now).

What landed (the port-relevant summary):
- Per-occurrence lowering provenance `[segment-address pc origin root-address path]` in semantic/stack/register images, persisted as a side artifact outside the schema-1 manifest hash, gated on a C1 claim.
- The datom lane restricted to `[pc entity]` — the entity→occurrence bridge is D1's stated dependency, pinned by test.
- The oracle site→C1-key binding with composition-supplied `[run-scope ordinal]` admission tokens (3-arg `kernel-compile`; the 1-arg form is gone — two diagnostic scripts were updated accordingly).
- The forgeability class TERMINATED structurally: recovery reads retention evidence exclusively from shell-owned state; the three exception-carriage keys are removed as dead code (grep-verified by the author and independently by the deepseek reviewer).
- `index-test`/`query-test` joined the standing verification net (the class the shiva gate caught post-approval).

Evidence: astra (r1 REQUEST CHANGES → confirms → r6 APPROVE; r7-r11 confirms incl. a fresh jupiter thread) + deepseek r11 APPROVE (own grep, tests re-run) + shiva fully green at `897786cf` (focused 217/8901/0; dao.stream tri-lane; kit red = master baseline; kondo clean). Exceedance of record: the r2 8 MB ceiling exceeded by the compiled-inclusive holder (8.94 MB) — run preserved FAILED, superseded by the v2 bounded-run declaration per ruling (c).

**Phase 0 may proceed**: your implementer builds the LaTTe depth harness on THIS master's `latte_oracle_test.clj` (post-cc3: 3-arg `kernel-compile`, the binding/correspondence machinery, ~44 deftests in the callsite battery). Escalation thresholds (>10× depth / >10k frames) armed as you filed them. My post-port oracle-binding re-verification round triggers on your port's landing.

— yang.clojure orchestrator seat
