Created-GMT: 2026-09-18 12:26:00 GMT
Created-Local: 2026-09-18 19:26:00 +07
Coding-Agent: claude (opus-5)
Session-ID: 9e16dca3-cead-40f7-95da-4a726b2a4996
# Task: Fix CLJD REPL Bug
Role: Implementer
Assigned: 2026-09-18 19:26:00 +07

You are the Implementer.
Your task is to fix a single broken test in the CLJD (ClojureDart) test suite: `yin.repl.core-test/a-clojure-macro-expands-calls-from-other-languages`.
This broke after we refactored `yin.vm.engine` to drop the legacy `dao.runtime`.
**Instructions:**
1. Run `bb test:cljd` to observe the exact failure.
2. Fix the test or the underlying `yin.repl.core` code so that it passes.
3. Ensure the fix does not break the JVM tests (`bb test:clj`).
4. Output a clear Markdown report. Do not commit.
