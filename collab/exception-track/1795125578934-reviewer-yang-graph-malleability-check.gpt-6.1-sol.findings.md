Created-GMT: 2026-11-19 22:01:56 GMT
Created-Local: 2026-11-20 05:01:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1e592-2d0b-7fe2-9438-fae05bf926d6 (resumed)
# Task: yang-compilers-post-dry-graph-malleability-check
Role: Reviewer (step-7)

Completed-GMT: 2026-11-19 22:01:56 GMT
Completed-Local: 2026-11-20 05:01:56 +07

The landed architecture aligns with the governance principles. One analyzer inconsistency needs correction before its headline count can support governance decisions.

1. **Bounded complexity: qualified pass.** Admission’s 3 publics, eval transport’s 4, and the three 5-entry namespaces meet the target directly. Export’s 6, module’s 8, prelude’s 11, lower’s 12, and packet’s 15 exceed it. They remain review obligations; the document’s approximately 12-function trigger requires **evaluation**, not automatic splitting.

   The admission split and visibility demotions address demonstrated boundaries. The remaining surfaces have coherent responsibilities: packet contract operations, module construction, prelude emission, and shared lowering operations. Packet’s 15 is the strongest remaining interface-pressure signal, but this graph establishes no independently changing cluster that now mandates extraction. Further growth should reopen that evaluation.

2. **Coupling: predominantly architectural.** UAST, packet, prelude, and admission have the desirable foundational shape: high incoming degree with low outgoing degree. Core’s lack of target dependencies preserves directionality.

   PHP lower’s **out:14 is defensible, and still a review flag**. Its counted dependencies comprise eleven shared/foundation namespaces and three PHP namespaces—builtins, prelude, and scope. Those three are legitimate language-contract dependencies, although their stability should not be assumed. I found no unrelated feature dependency in this list.

   Target preludes, sitesets, publication modules, and eval coordinator compose several necessary responsibilities. Their outgoing counts warrant inspection, but do not establish accidental coupling. An incoming count of zero also does not establish that an entry point is unused.

3. **Self-loops: no architectural correction required.** These are explicit macro-phase self-references, not multi-namespace runtime dependency cycles. They should remain distinguishable in reporting. The absence of multi-node SCCs is the more relevant modularity result.

4. **The “119 high-degree namespaces” cannot establish proportionality.** That would require a consistent counting rule, the total namespace population, and a comparison baseline. Qualitatively, Yang’s contribution fits a compiler architecture with shared IR hubs and target composition layers.

   **P2 — measurement inconsistency:** [the predicate](/Users/sto/workspace/datomworld/bin/analyze-deps.clj:88), called with [threshold 5](/Users/sto/workspace/datomworld/bin/analyze-deps.clj:155), selects `in ≥ 5 OR out ≥ 5`. The documentation describes **combined degree ≥ 6**, and the printed label says “6+ connections.” These select different populations. The supplied individual degrees remain useful; the headline classification is misleading.

5. **Overall properties passed:** dependency direction, high cohesion of the extracted clusters, isolation of target policy, structural clustering, and problem-domain naming. Keeping host CST implementations separate remains appropriate. Graph structure supports this assessment; it cannot alone prove change cost or runtime invariants.

No further compiler split is justified solely to reduce counts. The highest-value next action is to make the analyzer implement and report one consistent high-degree definition, then recompute the summary.

GRAPH VERDICT: ALIGNS WITH ONE RESIDUAL ACTION — reconcile the analyzer’s high-degree predicate with its documented combined-degree ≥6 rule, then regenerate the headline count.