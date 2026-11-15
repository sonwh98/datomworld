# Eval bridge r3: stopped at required oracle evidence gap

No implementation or tests were changed in this slice. The branch is at part 2's `e549f826` when this investigation began.

The binding byte-domain ruling (`1794605712000-architect-bytedomain-ruling.findings.md`, section C) requires an uncatchable guest compile-fatal for the recorded eval class collision and says to reuse terminal machinery only with an **oracle-confirmed lifecycle policy**. It expressly states that its supplied record does not settle declaration ordering or cleanup, and requires collision probes covering shutdown functions, destructors, output buffers, terminal status and partial declaration visibility. The recorded oracle summary (`1794605584000-oracle-eval-probes.txt`) establishes collision classification, but does not establish those lifecycle and ordering facts. `docs/design/yang.php.md` §3.12 likewise says cleanup and ordering require their pinned oracle fixtures.

The requested slice includes the ruled declaration/fatal behavior and explicitly says “Design gap -> stop + record”; the oracle pass is assigned to the orchestrator and Docker is prohibited here. Choosing the existing guest-fatal cleanup policy, the operational root-fatal policy, or a declaration ordering would therefore invent behavior the ruling reserves for oracle evidence. The six already accepted observations remain binding; this gap does not reopen them.

The part 2 handoff also reports that no compiler worker invokes admission and that no eval compiler coordinator exists. `src/cljc/yang/php/antlr/cst.cljc` currently starts at `htmlDocument`, and both eval lowering refusal paths remain. None of the requested integration, emission, runtime, retrieval, or verification gates is claimed complete. No tests or oracle probes were run in this investigation.

## Handoff

The orchestrator should run and record the targeted collision lifecycle and ordering fixtures enumerated in the ruling, with separate fatal processes and the pinned PHP environment, and obtain a design decision if the results do not select a clear guest terminal policy. Then implement the complete r3 scope from the part 2 handoff, including fresh per-execution declaration provenance, and run the requested sequential gates (600 seconds for `lower-portable-test`). Preserve the byte-domain ruling's distinction between oracle observations and compiler conformance tests.
