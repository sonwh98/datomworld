Created-GMT: 2026-10-12 00:00:00 GMT
Created-Local: 2026-10-12 07:00:00 Asia/Bangkok

# Task: adversarial review of the BLAKE3 slice (capability seal, owner tag, profile digest)

Role: Adversarial Reviewer (claude-fable-5-1). Worktree `/Users/sto/workspace/datomworld-blake3`, branch `blake3-seals`, HEAD 492ff50b (diff `git diff master...HEAD`; master has since moved but does not overlap). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground; no slow tests. Before applying any mutant take a verified byte backup of the file you mutate into `collab/` and restore from it (`cmp`).

Author: claude-opus-5-5 (report `collab/1792130000000-engineer-blake3-seals.report.md`, brief `collab/1792130000000-engineer-blake3-seals.prompt.md`). Owner ruling: the capability seal (`engine.cljc` `seal-of`), the owner tag (`values.cljc` `owner-tag`) and the primitive-profile digest (`vm.cljc`, keyword `:yin.k.pp/blake3-<digest>`) move from SHA-256 to BLAKE3 (explicit `{:algorithm :blake3}`); the H and R image checksums and descriptor hashes are OUT of scope (they move at the phase-8 cutover). Verified by the orchestrator: JVM full suite on jupiter, 13 shards, 4118 tests, all exit 0 (this branch has no tier tags).

Check, adversarially:
1. Every place that computes OR verifies a seal, owner tag or profile name: grep `seal`, `owner`, `yin.k.pp`, `sha256` across `src/` including the v2 handoff, remote, holder, linker, and the demo; any reader that recomputes with the old algorithm, any stored or hard-coded SHA-256 seal, any comparison across code paths that would now disagree (for example a profile name minted by one path and checked by another).
2. The 64-hex assumption: any regex, length check, pinned example or documentation of a 64-character digest that BLAKE3 (32 bytes, also 64 hex) happens to satisfy by accident versus by design; any place that depends on the algorithm name embedded in the keyword (the `blake3-` spelling) and parses it.
3. The three new tests: can each fail? Apply at least eight mutants: revert each site to `:sha256` one at a time (must be KILLED by a named test); change the seal input order; drop the secret from the seal; make `owner-tag` ignore the secret; change `blake3-` to `sha256-` only in the keyword; change the algorithm in the digest but not the keyword; seal differs per kind; plus your own. KILLED or SURVIVED, with the killing test.
4. Cross-host: BLAKE3 on JVM, Node and Dart (Jing's default). Spot-check that the Node and Dart paths exist for `:blake3` in `dao.jing`; note the author did not run Dart.
5. Scope: only 3 source lines and tests changed; the docs sites the report lists (dao.jing.hash-registry.md 366-384, dao.jing.call-site-classification.md:50, UCF ~1285, linker.md ~1729) still describe the SHA-256 profile spelling (the orchestrator updates them in a docs batch; confirm the list is complete by grep).

Write `collab/1792190000000-reviewer-blake3-seals.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.
