Completed-GMT: 2026-09-24 08:06:18 GMT
Completed-Local: 2026-09-24 15:06:18 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0d255-1830-7f30-ab85-0840da6aed72

# Findings: Consensus Follow-up (Round 2) — yin.repl universal dao.stream boundary & 4-VM wiring

Role: Adversarial Code Reviewer and Security Auditor
Model: gpt-6-sol

| Finding | Final disposition | Evidence | Remaining action |
|---|---|---|---|
| Gap budget starvation | Fixed | Gap handling (`core.cljc:378`) decrements `gaps` while leaving the element budget intact; the full-ring test covers the reported case. | None. |
| Stale result selection | Fixed for stale rounds | Finalization (`core.cljc:648`) filters by the current round, and the test covers a prior token plus a refused append. | None for the reported case. |
| P1: canonical image identity | **New blocking finding** | `chain-hash` (`core.cljc:106`) deliberately sets VM `:hash` to a value different from H/R over its loaded `:segment`; the new test asserts that difference. The stack design (`yin.vm.debruijn.stack.md:204`) makes H the sole executable identity and requires continuation `:hash` to be the loaded image's H; the register design (`yin.vm.debruijn.register.md:631`) requires R likewise. Both VM records document the same contract. | Keep a separate REPL chain identifier if useful, but preserve canonical H/R in VM `:hash`, or change the image representation and governing contract explicitly. Add a test that verifies the loaded image's canonical identity. |
| P2: long-session image retention | Partially fixed | Both appenders (`core.cljc:124`) now hash only the new segment, but still retain every earlier instruction, including inputs with no surviving closure. | Reclaim unreachable prior code while retaining images referenced by live closures and continuations. |
| P3: documentation sync | Partially fixed | The updated VM list is correct, but the next paragraph (`yin.repl.md:72`) still says datom input uses an ast-walker-owned ingress buffer; `make-session` (`core.cljc:510`) owns the program media for all four VMs. | Correct that paragraph. |

**Ready to commit: No.** The chain hash changes a specified VM identity contract. After correcting it, run CLJS and CLJD verification as well as the supplied JVM checks.
