Created-GMT: 2026-10-14 17:40:00 GMT
Created-Local: 2026-10-15 00:40:00 +07
Coding-Agent: fable
Session-ID: 71ff60b2-6c20-4e6c-9a77-6cfced87ce56 (RESUME — confirm round on the fix)

# Task: phase-8ab confirm review — verify the I4-a fix round, close or restate P1/P2

Role: Reviewer (standing owner routing — review to Fable; the slice author is
codex gpt-6.1-sol, so reviewer-family independence holds).

Resume session `71ff60b2-6c20-4e6c-9a77-6cfced87ce56`, which produced
`collab/1792630000000-reviewer-8ab-descriptors.claude-fable-5-1.stdout.log`
(8/8 check items confirmed; blocking P1 stale `dht-import-v1.edn`; P2 no
Node/Dart lanes; 2× P3 nits). The P1 has since been resolved on a different
axis than first expected — verify the new axis, do not re-litigate the old.

## What changed since your review

1. The yang.python seat regenerated the artifact on master as `72592bd9`
   (edn + `dht_import_test.cljc` only, over I4-a; their reply is at
   `collab/1792631000000-orchestrator-note-dht-import-regen.md` §Response;
   on master py = `:segment/blake3-6e003ac4…`).
2. The slice branch `srvm-phase8ab` was rebased onto `72592bd9` (now
   `2b9f2b47` + uncommitted fix). Work in the worktree at
   `/Users/sto/workspace/datomworld-srvm-p8ab` (read-only flags:
   `--permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)"`,
   stdin `< /dev/null`; run no tests, edit nothing).
3. Codex fix round 2 (session `01a13b5c-d88c-7a01-a2f8-f409ef355091`,
   report `collab/1792633000000-engineer-8ab-fix2.report.md`, main-tree copy
   present): ONLY `linked_prelude_test.cljc` (4+/3-) and
   `linked_safepoint_test.cljc` (3+/2-) changed. Pins moved: py manifest
   `577aabfb…` → `2e5aebac…`, pysp `6c39c4b9…` → `a28d246c…`, prelude
   runtime-key count 65 → 66 (I4-a's new `ModuleNotFoundError` builtin).
   H/R backend, descriptor, dimension/node, linker, parity, corpus (32),
   rows (20) pins verified byte-identical. JVM: focused quick 250
   tests/8,483 assertions green; kondo 0/0; `diff --check` clean; 4/4 prior
   mutants still caught. Slow `dht-import-test` leg: expected-red (66
   failures, all in-namespace) against the committed master edn (pins
   `6e003ac4…`, absent from the 8ab tree's prelude store — refusal
   `:absent`, documented per assertion line), and green (1 test / 92
   assertions) against a locally regenerated edn that was then reverted
   (`git status` for the edn empty at finish).

## Verify and rule (static review only)

1. The two-file diff is exactly what the report claims (manifest pins +
   count + provenance docstrings, nothing else), and every moved pin is
   prelude-content-dependent (recompute the two manifest addresses from the
   tree if you can do so read-only; else trace them to the saved
   `target/8ab-fix2/` evidence cited in the report).
2. The 65→66 count change is justified by I4-a (not a weakened assertion —
   the adjacent exact-keys assertion still holds).
3. The H/R STOP condition was honored (no backend/descriptor/corpus/rows
   pin moved) — spot-check at least the H/R descriptor hashes and linker H/R.
4. The expected-red-against-committed-edn posture is sound: the committed
   edn is yang.python-owned and correct for master; the 8ab tree's mismatch
   is inherent until integration, and the local-regen-green run proves tree
   coherence rather than masking a defect.
5. P2: the orchestrator already ran Node (93ns/1578/0) and Dart
   (93ns/1512/0) lanes green on shiva for the nine namespaces — but that was
   BEFORE this fix moved the two C4 golden pins. State whether the P2 is
   now (a) closed, (b) narrowed to re-running Node/Dart for ONLY the two
   changed C4 namespaces post-merge, or (c) still fully open, with reasons.
6. Restate your 2× P3 nits as still-open or overtaken.

Verdict adjudication: READY TO MERGE (into `srvm-phase8`, not master), or
CHANGES REQUIRED with numbered findings (P0/P1/P2/P3 severity as before).
Do not claim test runs you did not perform. Begin the final response with
the Completed-GMT / Completed-Local / Coding-Agent: fable / Session-ID
lines exactly as in prior rounds.
