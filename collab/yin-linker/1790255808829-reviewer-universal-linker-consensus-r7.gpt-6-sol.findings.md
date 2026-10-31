Completed-GMT: 2026-09-24 13:19:57 GMT
Completed-Local: 2026-09-24 20:19:57 ICT
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r7)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 2 review of r2 (REJECTED)
- Status-Event: 2026-09-24 19:24:44 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 3 review of r3 (REJECTED)
- Status-Event: 2026-09-24 19:33:09 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 4 review of r4 (REJECTED)
- Status-Event: 2026-09-24 19:39:54 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 5 review of r5 (REJECTED)
- Status-Event: 2026-09-24 20:07:50 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 6 review of r6 (REJECTED)
- Status-Event: 2026-09-24 20:19:57 +0700 | Model: gpt-6-sol | Status: completed | Rationale: Round 7 consensus verification of Revision r7

Verdict: REJECTED

1. CLOSED -- Grow-only returns:
   r7 explicitly removes saved code-space values from register return frames and forbids returns from replacing the attached image. It adds an attach-during-call-then-return test (linker.md:1176, linker.md:1983).

2. PARTIAL -- Module-store routing:
   Direct store operations and transitive snapshots are now specified, but the `:store-get` exception exposes the receiver's ambient store to module code. Resource keys are predictable -- `stream-0` is minted from a counter -- and `:store-get` accepts a program-supplied key. A linked module can name that key and read a host handle despite the isolation rule. Put engine resources in a private table inaccessible to language store instructions; do not fall back to ambient store. Also specify clearing the active module-store context when a tail call returns with no caller frame, before the VM runs another top-level input (linker.md:1316, linker.md:1344, engine.cljc:76, engine.cljc:162).

3. PARTIAL -- Walker lambda ID:
   Recording a source row ID resolves the shared-body ambiguity in principle. As written, the decoder is told to record it "in the closure when it builds it," but the decoder builds AST nodes; the walker creates the closure later during `:lambda` evaluation, when it has the environment. Specify an annotation or side-index from decoded lambda node to row ID and require the runtime transition to copy and verify it (linker.md:1266, vm.cljc:1263, ast_walker.cljc:381).

The 2,399-line file is pure ASCII with no lines over 80 columns. Consensus has not been reached; r7 is not ready to commit.
