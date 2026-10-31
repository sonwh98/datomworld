Created-GMT: 2026-10-20 01:37:36 GMT
Created-Local: 2026-10-20 08:37:36 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Task: J0c round 1 fixes after review
Role: Compiler & AST / Stream engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 08:37:36 +07 | Status: active | Rationale: fix accepted review findings

Review (qwen/qwen3.8-max via cmd, SIGNED OFF WITH CHANGES): /Users/sto/workspace/datomworld/collab/1792459913000-reviewer-jolt-j0c-r1.qwen3.8-max.findings.md. Work ONLY in /Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0c; no stage/commit/push; foreground; same rules as round 1.
- F1 (REQUIRED): native/jolt-parser/jolt_parser.cpp:120-128 message() allocates inside the catch handlers (std::string, std::vector, malloc); under std::bad_alloc a throw from the handler would escape extern "C" (UB / terminate). Make the -2/-3 (and any catch) paths nonallocating: return the status with a null out buffer, or build the message with malloc/memcpy only and a static fallback; no handler may throw. Add a test that forces the failure path deterministically (for example an injectable allocation failure or a size bound that triggers the catch path) and show the process survives and jolt sees a failure status, not a crash. Keep the 7 probe checks green and add the new one(s).
- F3: add a one-line note in antlr/python3/manifest.edn near the :cpp helper entries that the Cpp helpers are fetched and digest-checked by every target's gen:antlr run, so a Cpp fetch failure now also blocks Java/JavaScript/Dart generation, and that all targets re-pin together.
- Verification cmd asked for: run the existing generator for the other targets once in this worktree and prove the Java, JavaScript and Dart outputs and recorded digests are byte-for-byte unchanged versus base (jolt-j0a): bb gen:python-antlr (check docs/agents/build-n-test.md for exact task names; do not run it concurrently with any other gen:antlr in this worktree; if it needs the network for pinned grammar fetches say so). Report the digests before/after. If anything differs, STOP and report; do not fix by editing recorded digests.
- F4 (orchestrator note): the probe has 7 PASS checks, not six; ignore the brief's count.
Re-run the probe and bb smoke:jolt in the foreground. Write a short report /Users/sto/workspace/datomworld/collab/1792460256000-engineer-jolt-j0c-r1-fix.claude-opus-5-5.findings.md (header fields, finding -> change table, commands and exit codes) and end with its path and the files changed.
