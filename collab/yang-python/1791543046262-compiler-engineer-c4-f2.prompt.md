Created-GMT: 2026-10-09 10:50:46 GMT
Created-Local: 2026-10-09 17:50:46 ICT
Coding-Agent: claude
Session-ID: d30e62c3-3a3d-4f32-aceb-5e1e7fbba847

# Task: yang.python Phase C4 Slice F2 (REPL dynamic frontend catalog selection)
Role: Compiler Engineer / VM Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-09 17:50:46 ICT | Status: active | Rationale: Lead Engineer for Track 2 (Slice F2 REPL Catalog Decoupling)

You are the Engineer implementing Track 2 (Phase C4 Slice F2) in the dedicated worktree:
`/Users/sto/workspace/datomworld/.claude/worktrees/yang-python-c4-f2`

## Governing Architecture & Rules
- Governing Specification: `docs/design/yang.antlr.md` (§8.5.6 Imports, the linked prelude, and the frontend catalog; Ruling 16).
- Catalog Foundation: `src/cljc/yang/frontend.cljc` (Landed in Slice F1).
- REPL Implementation: `src/cljc/yin/repl.cljc`.

## Scope of Slice F2 (Section 8.5.6)
1. **Decouple `yin.repl` from hardcoded frontends**:
   - `yin.repl` today has a closed `case` over three languages (`:clojure`, `:python`, `:php`) with static namespace requires.
   - Per Ruling 16:
     - The REPL takes the frontend catalog from its composition as a `create-state` option (defaulting to the standard catalog).
     - A session pins an immutable catalog snapshot.
     - `compile-source` / `compile-command-ast` dispatches through the catalog's installed binding for the selected `[id revision]`, calling parse and lower stages.
     - Language-specific parsing and completeness logic stay out of REPL core.
     - Drop static requires of `yang.python`, `yang.php`, etc., from `yin.repl.cljc` where they are replaced by catalog bindings.
2. **Catalog Selection Semantics**:
   - Support selecting frontends by keyword/id (e.g. `:clojure`, `:yang.python/legacy`, `:yang.python/antlr`).
   - If an unconfigured or unavailable frontend is selected, answer with `unavailable-parser` diagnostic without legacy fallback.
3. **Tests (`test/yin/repl/frontend_catalog_test.cljc`)**:
   - A toy frontend defined in a test namespace installs, is selected, and evaluates on every VM with no `src` edit.
   - A newer revision installed under a live session leaves it unchanged (session immutability).
   - On Node and Dart with no parser or configured service, an unavailable parser answers with diagnostic and no fallback.
   - Run tests across JVM (`bb test:clj -n yin.repl.frontend-catalog-test`), Node (`bb test:cljs`), and Dart (`bb test:cljd`).
   - Clean linter (`clj -M:kondo`).

## Reporting
Deliver the complete implementation, verify across all three hosts, and record your findings in:
`collab/1791543046262-compiler-engineer-c4-f2.claude-sonnet-5-5.findings.md`
