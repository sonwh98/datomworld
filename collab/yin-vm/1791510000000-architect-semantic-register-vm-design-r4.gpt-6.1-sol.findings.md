I’ll check the three register notions against each terminal case and verify that structural-slot allocation stays separate from runtime liveness.
Completed-GMT: 2026-10-09 18:40:39 GMT
Completed-Local: 2026-10-10 01:40:39 Asia/Bangkok

Minting and reconstruction are now aligned. Two terminal-expression cases still prevent freeze.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — validation gap | `docs/design/yin.vm.semantic-register-vm.md:512` | Rule 7 permits definition-less IDs only as resume destinations. In `if(c, resume(p, x), resume(q, y))`, the conditional mints the shared destination; both arms reuse it and neither defines it. The conditional’s ID therefore violates rule 7. | Permit any minted destination whose entire result subtree supplies no syntactic definition. Update §3.2 to allow an empty definition set for such terminal subtrees. |
| High — lowering/liveness contradiction | `docs/design/yin.vm.semantic-register-vm.md:780` | The structural slot is declared absent from **every** live operand, but liveness includes unreachable CFG components. For a call whose first operand resumes and whose later operand parks, the unreachable park’s successor call syntactically reads the resume destination. `body-liveness` consequently includes its structural slot. | Distinguish runtime saved windows from static live operands in unreachable components. Either permit structural-slot membership there, with no runtime capture, or specify a consistent reachability-aware lowering and native validator contract. Preserve agreement with physical `body-liveness`. |

**A.** Tail-call destinations, enclosing instructions after terminal operands, terminal tests, and mixed continuing/terminal arms now have a workable distinction between syntax and execution. The all-terminal conditional above remains excluded unintentionally.

One wording correction remains: a runtime write is a reachable instruction **that actually writes**, not merely a reachable syntactic definition—tail calls are the explicit exception.

**B.** Mapping definition-less IDs to one additional structural slot is deterministic and keeps native operands in bounds. Define `k` explicitly as the scan’s peak allocated temporary count. The slot stays out of reachable saved windows, but its exclusion from all static live operands is not established.

**C.** Reconstruction coordinates, explicit park classification, conservative source/destination separation, and contract-version gates pass. The two findings above concern accepted programs and emitted native validation, so they belong before freeze.

Read-only review; no files edited or tests run.

VERDICT: CHANGES_REQUESTED
