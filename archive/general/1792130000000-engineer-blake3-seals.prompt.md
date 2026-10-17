Created-GMT: 2026-10-12 11:00:00 GMT
Created-Local: 2026-10-12 18:00:00 Asia/Bangkok

# Task: move the capability seals and the profile digest from SHA-256 to BLAKE3

Role: engineer (claude-opus-5-5). Worktree `/Users/sto/workspace/datomworld-blake3`, branch `blake3-seals` (from master 7b6d6255). Do NOT `git add`/commit. Write each `-n ns` literally (zsh does not word-split a variable); foreground only (a command over 10 minutes: run it as a harness background task). Take a verified byte backup of each source file you edit into `collab/` first.

Owner ruling (2026-10-12): "BLAKE3 because it's faster", extended to "move the seals and profile digest to blake3 too". Jing's default digest is BLAKE3 (`dao.jing`); `:sha256` stays registered in `dao.jing` and untouched. NOT in this slice: the H and R image checksums and the descriptor hashes (`debruijn_code.cljc`, `debruijn_register_code.cljc`, `debruijn.cljc`): they move at the phase-8 cutover with their re-pin; do not touch them.

Change exactly these three production sites, and what depends on them:
1. `src/cljc/yin/vm/engine.cljc` ~line 302: the seal `(jing/content-hash [:yin.k/seal secret kind id] {:algorithm :sha256})` -> BLAKE3 (use Jing's default algorithm or `{:algorithm :blake3}`, whichever the codebase's other new-identity sites use). Every place that computes or verifies a seal must agree (grep `seal` in `src/`: `check-ref!`, `issue-ref`, the v2 handoff and remote carriers if they recompute or compare seals; report each reader). A sealed ref minted by the old code is never loaded by new code (no legacy, no stored refs), but check that no persisted fixture or golden carries a seal string; if one does, regenerate it.
2. `src/cljc/yin/vm/values.cljc` ~line 130: `(jing/content-hash [:yin.k/owner secret] {:algorithm :sha256})` -> BLAKE3, same care for readers.
3. `src/cljc/yin/vm.cljc` ~line 351: the profile digest `(jing/content-hash description {:algorithm :sha256})` and its keyword spelling `(keyword "yin.k.pp" (str "sha256-" digest))` -> BLAKE3 and `"blake3-"`. The keyword is hashed into identities and appears in tests and docs: grep `yin.k.pp` and `sha256-` across `src/`, `test/` and `docs/design/` and list every site; update the tests and pinned values; list the docs sites (do not edit the design documents, report them).

Check cross-host: BLAKE3 must work on JVM, Node and Dart (Jing's default does; confirm by running the affected tests on all three). Digest length differs only if the code assumes 64 hex characters (SHA-256) somewhere (a regex, a length check, a pinned example): grep and fix any such assumption, and report each.

Add a test for each of the three sites that FAILS if the algorithm reverts to SHA-256 (for example: assert the seal equals the BLAKE3 content-hash of the same input and differs from the SHA-256 one; the profile keyword starts with `blake3-`). Apply each mutant (revert one site to `:sha256`) and confirm a NAMED test fails; restore from backup byte for byte.

Verify (foreground): the affected test namespaces on the JVM (at least `yin.vm.engine-test`, `yin.vm.values-test` if present, `yin.vm-test`, `yin.vm.cell-test`, `yin.vm.data-test`, `yin.vm.integer-test`, `yin.vm.continuation-invoke-test`, `yin.vm.semantic-engine-test`, `yin.vm.semantic-register.effects-test`, `yin.vm.semantic-register.link-test`, `yin.vm.linker-test`, `yin.vm.linker-require-test`, `yin.vm.ucf.*` seal users you find by grep), then Node for the same, then kondo and cljstyle. Dart: the orchestrator runs it on shiva. Do not run the full suite.

Report to `collab/1792130000000-engineer-blake3-seals.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per site: the change, every reader and test touched, the failing-then-passing test, the mutant killed; every 64-hex or `sha256-` assumption found; docs sites to update; exact lane counts. Do not claim runs that did not occur.
