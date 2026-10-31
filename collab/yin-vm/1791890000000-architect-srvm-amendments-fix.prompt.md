Created-GMT: 2026-10-11 06:50:00 GMT
Created-Local: 2026-10-11 13:50:00 Asia/Bangkok

# Task: fix the residual inconsistencies codex found in the amendment application

You are an Architect session; you have no memory of the previous one. Worktree `/Users/sto/workspace/datomworld-srvm-amend`, branch `srvm-amend`, HEAD = the amendment commit. Read your predecessor's report `collab/1791860000000-architect-srvm-amendments.report.md`, then codex's review (read all) `collab/1791880000000-architect-srvm-amendments-review.gpt-6.1-sol.findings.md`, and the two source designs the amendments came from (`docs/design/yin.vm.semantic-register-vm.derivations.md` §9, `docs/design/yin.vm.semantic-register-vm.continuation.md` §3.3, §7.1, §10). DOCUMENTS ONLY; no code. No git add/commit.

Fix, in codex's order, verifying each claim against the documents before writing:
1. HIGH (frozen design §7, UCF §7.3.2): frozen §7 still preserves "two load paths" and vector projection to datoms, and UCF §7.3.2 still specifies the stack-shaped vector, old opcode table and batch-loader normalization as canonical. Remove the frozen §7 projection claim; add explicit precedence at UCF §7.3.2: for "v4" the canonical vector and validator are frozen §§2.3/3.3/3.4; mark the old stack specification historical.
2. MEDIUM (carrier step 7, ~671): add the once-per-body `frame/task-defect` check (continuation §3.3 X1) on each body's decoded task context before validating its frames, including halted bodies (no frames).
3. HIGH (carrier census root list ~683; UCF fixed-point roots ~1459): include the initial free environment (`free-env`) in both root lists, consistently with continuation §7.1; preserve exact census agreement and the complete tree-association check.
4. MEDIUM (code-as-tuples §7.1 ~1095, §10 impact table ~2008-2011; UCF matrix ~2956): keep the AST datom lane; remove the retired instruction-vector projection path and its acceptance requirements; update the impact table and the UCF matrix to two AST lanes plus the direct vector load.
5. LOW: rule "A7" does not exist: refer to continuation §3.2 A2 in the carrier, AND correct the same typo in `docs/design/yin.vm.semantic-register-vm.continuation.md` (this one edit to that companion design is permitted, nothing else in it); qualify frozen §8.4's unqualified §6.1.3/§6.1.4 references as derivations-design sections; code-as-tuples' SHA-256 row-id examples (~55, ~175) should use BLAKE3 spellings for consistency.
After the fixes, grep all five amended documents again for every residue class the two designs' inventories name; list remaining legitimate occurrences (U7 historical clauses, native stack-kernel `stack-base`, instruction operand-kind `:regs`).

Report to `collab/1791890000000-architect-srvm-amendments-fix.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: what changed, where, verification; anything disputed or unfixed.
