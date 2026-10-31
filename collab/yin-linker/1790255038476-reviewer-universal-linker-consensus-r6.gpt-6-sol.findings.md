Completed-GMT: 2026-09-24 13:07:50 GMT
Completed-Local: 2026-09-24 20:07:50 ICT
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r6)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 2 review of r2 (REJECTED)
- Status-Event: 2026-09-24 19:24:44 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 3 review of r3 (REJECTED)
- Status-Event: 2026-09-24 19:33:09 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 4 review of r4 (REJECTED)
- Status-Event: 2026-09-24 19:39:54 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 5 review of r5 (REJECTED)
- Status-Event: 2026-09-24 20:07:50 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 6 consensus verification of Revision r6

Verdict: REJECTED

1. CLOSED -- Dominance and scanners:
   The four format records now use position-bearing scanners, and lambda-body reads retain obligations unless a definition dominates every possible application site. The unsafe "it will error at runtime" assumption is withdrawn (linker.md:280, linker.md:450, linker.md:594).

2. PARTIAL -- Hash-safe attachment and per-task lowering:
   Parked-entry restore and per-task lowering are now specified. Ordinary *register return* remains a separate path: its current transition writes a saved `:segment` and `:hash` back into the VM. If a nested `require` attaches an image during a call, returning discards that image despite r6's grow-only inventory rule. Amend return frames/transitions as well as parked restores, and test attach-during-call followed by return (linker.md:1128, linker.md:1152, register.cljc:184).

3. PARTIAL -- Module-local stores:
   Carrying the child's store fixes a module's own exported closure, but the stated write rule is not covered by the proposed kernel amendment: stack, register, and semantic `:store-put` instructions write directly to ambient `:store`, while r6 says the only kernel behavioral amendment is free-name resolution. Specify module-store routing for direct store instructions and save/restore the active store context across nested calls. Also, a re-exported dependency closure carries `:store-of` for that dependency, yet the portable slice carries only the current child's halted store; a new receiving task may have no dependency store. Include and verify transitively referenced module-store snapshots, including mutations made in the child (linker.md:1274, linker.md:1295, linker.md:1817, stack.cljc:477).

4. Additional relocation edge:
   The walker assumes a body node belongs to exactly one `:lambda` row. Shared, content-addressed body rows can be referenced by lambdas with different parameters, so a `node -> row id` lookup alone cannot select the lambda. Include the closure's parameters or source lambda ID in the lookup (linker.md:1243, vm.cljc:1189).

The 2,257-line file is pure ASCII with no lines over 80 columns. Consensus has not been reached; r6 is not ready to commit.
