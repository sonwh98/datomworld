Created-GMT: 2026-09-18 10:04:00 GMT
Created-Local: 2026-09-18 17:04:00 +07
Coding-Agent: claude-opus
Session-ID: new
# Task: U16 - Macro Expander (Phase 2 - REPL and builds)
Role: Implementation
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-18 17:04:00 +07 | Status: active | Rationale: Delegated U16 Phase 2 implementation.

You are the Implementer. Your task is to implement Phase 2 of the U16 macro expander based on `docs/design/yin.vm.macro.md`.

**Instructions:**
1. Read `docs/design/yin.vm.macro.md` and focus ONLY on `### Phase 2 — REPL and builds` (around line 830).
2. The core expander engine (`macro.cljc`) is fully completed and tested (Phase 1). Your job is to wire it up!
3. Implement the following:
   - Route shell evaluation through encoder → input rows → expander → output rows → evaluator.
   - Seed standard forms through the row-native store.
   - Render input rows, expanded rows, and events in `(compile ...)`.
   - Show macro names and addresses in `repl-state`.
   - Verify reset, cross-language calls, file-backed media, and continued evaluation after expansion failure.
4. When finished, do NOT commit. Leave your work unstaged in the working tree and emit a Markdown report of your findings.
