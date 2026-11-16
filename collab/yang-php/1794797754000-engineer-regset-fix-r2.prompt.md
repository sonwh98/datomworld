Created-GMT: 2026-11-16 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a1e212-82f3-7931-86ae-6541ed0ef8d8)

# regset-fix r2 — environment unblocked; implement per your r1 attributions

Prepared for you since r1:
- Full `bb gen:antlr` has been run in ~/ci/dw-regfix by the seat (all grammars, TypeScript included);
  the JVM test loading now works. Do not re-run the generator unless you change grammars.
- Your findings file is committed on fix/yangphp-regset and pushed. The `.git` common dir stays
  outside your writable roots: DO NOT attempt commits — leave edits in the worktree and say so;
  the seat commits and publishes.
- Docker is unavailable on jupiter (no PHP oracle locally). The permutation corpus carries the
  php-src expected outputs inline — validate against those; the seat will run a shiva oracle
  spot-check on the final diff.

## Work items (from your r1)
1. SHUTDOWN ORDER (d9e8731e): implement your candidate — remove `arrayCreation` from
   `fresh-value?` so array literals acquire a reference — then validate: cleanup-semantics
   namespaces (finalization, p5b2-lifecycle, request-context, scope) focused-green, and the full
   `bb test:sub:clj yang.php` gate. If the full gate shows the candidate breaks anything else,
   record and stop this item (do not broaden the change without a ruling).
2. PERMUTATION CORPUS (unattributed): the TypeScript lexer now exists — run
   e2e-test/permutation-corpus-quick-test, attribute the mismatches (the corpus's expected
   outputs are the php-src record), and fix or stop per failure with the corpus evidence.
3. CLASS REDECLARATION (053ae9a8): NOT yours this round — the seat has routed the contract
   question (guest-fatal vs the old catchable diagnostic) to the architect. Skip it.

## Gates and deliverables
- `bb test:sub:clj yang.php` at the end (expect exactly the class-model failure to remain; 0 new
  failures). Per-run log custody: copy target/profile/sub-*.log to target/regset-r2/ before any rerun.
- Findings: collab/yang-php/<ts>-engineer-regset-fix-r2.gpt-6.1-terra.findings.md (or worktree
  root if blocked) — per-item verdicts, gate numbers, the exact diff of each change.
- No commits, no pushes; no touching include-test or the r2 include-fix files (siteset.cljc).
