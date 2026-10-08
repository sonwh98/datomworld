Completed-GMT: 2026-09-24 12:33:09 GMT
Completed-Local: 2026-09-24 19:33:09 ICT
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r4)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 2 review of r2 (REJECTED)
- Status-Event: 2026-09-24 19:24:44 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 3 review of r3 (REJECTED)
- Status-Event: 2026-09-24 19:33:09 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 4 consensus verification of Revision r4

Verdict: REJECTED

1. PARTIAL -- Defined-before-use and exports:
   The static export refusal is gone, but source order is not enough to prove a definition ran: a definition in an untaken `:if` branch can precede a read without binding it. Also, the format-record examples still return name sets, while the new rule requires occurrence positions; the cited `yin.vm/free-names` currently returns a set. Specify position-bearing scanners and use control-flow dominance, or conservatively retain an obligation where execution is uncertain (linker.md:257, linker.md:390, vm.cljc:1353).

2. PARTIAL -- Correlation-ID typo:
   Completion examples use pairs, but the `:link-response` wait-entry example still uses scalar `7`. Change it to `[:t0 7]` (linker.md:754, linker.md:890).

3. PARTIAL -- Cross-image closure relocation:
   Origin tracking and positional markers are specified, but the cited "ordinary loaders" reset the parked parent's execution state; the REPL append helpers are designed to *start* a newly appended program, not attach code to a suspended task. The walker mapping also describes a segment/entry closure, whereas its runtime closure holds an AST `:body`. Define non-destructive image attachment that preserves parked state and restore identity, plus an actual walker-body lift/lower rule (linker.md:1015, linker.md:1057, stack.cljc:95, semantic.cljc:719, ast_walker.cljc:381).

4. PARTIAL -- Authority replay:
   Distinct signed operations and assertion-bound retractions close the original forgery path. But an exact duplicate of one signed envelope has the same sequence: one rule calls it a replay, while the equal-sequence rule discards *both* as equivocation. That lets copying a valid event suppress its claim on a stream. Deduplicate by content ID before treating distinct envelopes with equal sequences as equivocation (linker.md:1263, linker.md:1291).

5. CLOSED -- Per-format contracts:
   Schema version and execution contracts are separate, and the manifest comparison selects the requested format's entry (linker.md:1135, linker.md:1171).

The 1,859-line file remains pure ASCII with no lines over 80 columns. Consensus has not been reached; r4 is not ready to commit.
