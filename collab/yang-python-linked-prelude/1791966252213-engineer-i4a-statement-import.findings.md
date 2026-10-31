# I4-a statement-time imports — findings

- Read the slice brief, master architecture and local build/test rules. Binding architecture and mob artifacts are absent from this worktree; read the originals in `/Users/sto/workspace/datomworld/collab/`.
- Scope: keep eager `py` delivery and concrete static dependency pins; move guest delivery into cache-first statement-time thunks. Distinguish name absence (`ModuleNotFoundError`) from address absence and other refusals (`ImportError`), retaining structured diagnostics.
- Implementation and verification in progress. JVM-only testing follows the slice brief; cross-host landing gates remain for the orchestrator.

## Implementation checkpoint

- Linked wrappers eagerly require only `py`; each guest import passes a thunk that calls bare `try-require` and reads qualified exports only in the success branch. Removed the obsolete eager-import helper.
- Prelude imports check `sys.modules` before calling delivery. Name absence raises builtin `ModuleNotFoundError` (subclass of `ImportError`); content misses and other refusals raise `ImportError`. Complete refusal data is retained as `e.refusal`; address-miss text points to that attribute because the runtime has no general host keyword-to-string primitive.
- Portable tests cover lowered thunk shape, primitive profile declaration, Python handler matching, retry requests, diagnostic preservation and cache hits skipping delivery. Existing I1/I2 programs retained.
- Fast import suite: 16 tests, 90 assertions, 0 failures/errors.
- Changed-file clj-kondo: 0 errors/warnings. Full `clj -M:kondo --lint src test` reported 27 errors/68 warnings initially, including one new obsolete-helper warning (removed). Remaining reports concern unchanged files, including Dart symbols and `yin.repl.host`.
- Full `mise exec -- cljstyle check` initially reported 27 incorrectly formatted files, including two changed files. Formatted those two; changed-file check passes. The other 25 files are unchanged baseline formatting issues and remain outside this slice.
- `bb test:sub:clj yang.python` needed elevated execution for Babashka process management and parser-generation downloads; the retry generated the parser successfully and is running the JVM suite. Prelude/bundled golden address changes are being updated from observed JVM output.

## Verification follow-up

- Initial full JVM subsystem run completed: 233 tests / 2654 assertions, seven failures and no errors. Five failures were expected bundled address goldens; the other two were the builtin runtime-key count (65 to 66) and existing half-bound depth-headroom assertion.
- Kept the depth-headroom assertion intact. Grouped builtin class/function/method initialization and builtin dictionary seeding into ordered `do` phases. This preserves evaluation order and the final ready flag while shortening occurrence paths. Focused depth test now passes.
- Added a published-module acceptance with a concrete pin whose dependency is absent from the reader, proving installation does not fail before its body can catch the import.
- The earlier full import run reached only three failures: the lost-link test expected the stub map without the engine's added `:link-id`. Updated the expectation and asserted that the added correlation diagnostic is retained. Obsolete runs stopped to avoid CPU contention; final source import/subsystem reruns are in progress.

- All import acceptance runs use the existing harness's semantic, stack and register backends. The repository's linked-prelude suite still explicitly excludes the AST walker for its pre-existing sibling-read `:undeclared-free` limitation (L-b); this slice does not change that gate.
- Final full repository formatting check reports 25 unchanged baseline files; changed-file formatting passes. Cross-host testing and I4-b DHT fixture regeneration remain outside this JVM-only implementation brief.

## Final fast gates

- `bb test:sub:clj yang.python`: PASS, 24 namespaces, 233 tests / 2654 assertions, zero failures/errors (246 seconds; `target/profile/sub-clj.log`). Includes the unchanged linked import-free program root, all bundled address goldens, runtime-key count and depth-headroom check.
- Changed-file `clj -M:kondo --lint ...`: PASS, zero errors/warnings across the six Clojure source/test files (including linked safepoint before its golden edit).
- Final repository-wide `clj -M:kondo --lint src test`: 27 errors / 67 warnings, all outside the changed files. Full repository formatting: 25 unchanged files reported. These pre-existing repository-wide lint failures prevent claiming a globally clean gate; no unrelated files edited.
- Full import suite and linked manifest slow checks remain running. Observed final `py` manifest: `:segment/blake3-6e003ac4ac8edae670ff7eb46816134dec4982ffb42e74ceac3bbfd0eed53662`.

- Linked prelude/safepoint slow suites: 15 tests / 99 assertions, with only the two expected manifest-golden failures. Updated both manifests, then reran the exact two tests with `-v`: PASS, 2 tests / 13 assertions, zero failures/errors (`/tmp/i4a-manifests-verified.log`). Remaining slow tests passed in the preceding run.
- Final `pysp` manifest: `:segment/blake3-b2cfec75bcee891c554cd620c50158fac3191db0e22f3520f5be8fd42e11d630`.
- Final changed-file clj-kondo (after all golden edits): PASS, zero errors/warnings. Final changed-file cljstyle: PASS. `git diff --check`: PASS.
