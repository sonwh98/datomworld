Completed-GMT: 2026-09-24 13:28:29 GMT
Completed-Local: 2026-09-24 20:28:29 ICT
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r8)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 2 review of r2 (REJECTED)
- Status-Event: 2026-09-24 19:24:44 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 3 review of r3 (REJECTED)
- Status-Event: 2026-09-24 19:33:09 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 4 review of r4 (REJECTED)
- Status-Event: 2026-09-24 19:39:54 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 5 review of r5 (REJECTED)
- Status-Event: 2026-09-24 20:07:50 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 6 review of r6 (REJECTED)
- Status-Event: 2026-09-24 20:19:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 7 review of r7 (REJECTED)
- Status-Event: 2026-09-24 20:28:29 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 8 consensus verification of Revision r8

Verdict: REJECTED

1. PARTIAL -- Module-store isolation:
   r8 explicitly removes ambient-store fallback, puts engine handles in private `:resources`, and clears the active module context after a terminal tail call (linker.md:1351, linker.md:1384). But the continuation-format contract still decodes stream references to handles under store keys and cursor references to store entries (UCF.md:566, UCF.md:699). r8 names only sections 4.1 and 7.6.2 for the resource amendment, and its M4 file box omits those decoding rules (linker.md:1397, linker.md:1919).
   Fix: specify that stream and cursor lowering installs handles/cells in private `:resources`, leaving only non-forgeable references in program values; update every affected continuation-format clause and the M4 checklist, then test lift/lower of a module export carrying a stream or cursor reference.

2. CLOSED -- AST-walker lambda provenance:
   The decoder now records the source row ID on the decoded lambda node (or in a side index); evaluation copies it into the closure, and lift checks the row's body and parameters (linker.md:1266). The M4 tests cover shared body nodes and fabricated closures (linker.md:1877).

The r8 file passes the pure-ASCII and 80-column checks. Consensus has not been reached; it is not ready to commit until the resource-lowering contract is reconciled.
