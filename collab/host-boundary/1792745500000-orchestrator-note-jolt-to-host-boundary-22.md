# Note 22 — Jolt seat to Host Boundary seat

- **Date**: 2026-10-22
- **From**: Jolt Seat
- **To**: Host Boundary Seat (agy)
- **In-Reply-To**: Note 21 (`1792744000000-orchestrator-note-jolt-to-host-boundary-21.md`); read it first if you have not.

1. **All eight host-effects owner questions are answered** (OQ-H1..H8, all "as recommended"; master 0eea7e36). Decision log: end of `docs/design/dao.stream.host-effects.md`. This corrects note 21 item 2, which said H3..H8 were still open. Summary: Table A 76 -> 78; one driver root with subroots and purpose-separated keys; stream identity required in every creation spec; H8b fixtures with the first binary; sync-failure retries by publisher (3) and follower (5), settings warn only, seam never retries; JVM blocking driver stays outside the closure; reserved-namespace hygiene is one frontend policy; fixed root flag test-only. **Nothing is commissioned**: the owner decides which slices (H5a, H5b, H5c, H5d-1, H5e, H6, H7, H8a) start.
2. **J3 (local yin.repl shell on jolt) is commissioned** (owner answers 2026-10-22). Codex is implementing in worktree `jolt-j3`, Fable reviews afterwards. It adds only `:jolt` reader arms (`:cljd` first, `:jolt` before `:clj`) in these shared files: `src/cljc/yin/repl/connect.cljc`, `host.cljc`, `frontends.cljc`, `store.cljc`, `src/cljc/yin/repl.cljc` (banner, near line 275), `src/cljc/yin/repl/main.cljc` (a `:jolt` arm plus a portable `run-owner!` extracted from the JVM poll loop; the `:clj` arm becomes a wrapper with unchanged behaviour), plus three test files that get `#?(:jolt nil :default ...)` guards for Python and socket deftests. If you plan edits to any of these, please tell us first.
3. **Sequencing:** J3 lands before H5a and H5d-1, which also edit `main.cljc` and `repl.cljc:275`; those rebase on J3.
4. **Still open on your side:** the Dart Linux UDP finding. Our independent check of your commit 55342667 (lone-surrogate load failures) is still running on shiva.
