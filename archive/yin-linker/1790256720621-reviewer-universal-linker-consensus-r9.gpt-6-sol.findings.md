Completed-GMT: 2026-09-24 13:33:55 GMT
Completed-Local: 2026-09-24 20:33:55 ICT
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r9)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 2 review of r2 (REJECTED)
- Status-Event: 2026-09-24 19:24:44 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 3 review of r3 (REJECTED)
- Status-Event: 2026-09-24 19:33:09 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 4 review of r4 (REJECTED)
- Status-Event: 2026-09-24 19:39:54 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 5 review of r5 (REJECTED)
- Status-Event: 2026-09-24 20:07:50 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 6 review of r6 (REJECTED)
- Status-Event: 2026-09-24 20:19:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 7 review of r7 (REJECTED)
- Status-Event: 2026-09-24 20:28:29 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 8 review of r8 (REJECTED)
- Status-Event: 2026-09-24 20:33:55 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 9 consensus verification of Revision r9

Verdict: REJECTED

- CLOSED -- r8 resource-lowering finding:
  r9 directs stream handles and cursor cells into private `:resources`, remaps references without creating store keys, and adds an export round-trip test (linker.md:1403, linker.md:1895).

- OPEN, P1 -- Reference forgery remains unspecified:
  r9 calls program-held references "unforgeable" while retaining IDs "exactly as a running program holds them" (linker.md:1415). Today those references are ordinary maps with predictable IDs (`{:type :stream-ref, :id :stream-0}`), and stream effects look up a handle using only the supplied `:id` (engine.cljc:76, engine.cljc:172, engine.cljc:185); the AST walker accepts a literal value as an effect target (ast_walker.cljc:296). Moving the lookup to `:resources` alone would let a fabricated map literal reach an arbitrary resource.
  Fix: specify capability-sealed or task-scoped token validation at every effect boundary; define its lift/lower remapping and test forged stream and cursor references, including FFI resource IDs.

- PARTIAL, P2 -- Migration references:
  Criterion 12 requires amendments to UCF 7.6.2, but the M4 file box still omits it (linker.md:2008, linker.md:1944). It also names "UCF 4.1," which is not a section of the referenced UCF document.
  Fix: correct the section citation and include every required amendment in the M4 file box.

The r9 file passes pure-ASCII and 80-column checks. Consensus has not been reached; it is not ready to commit.
