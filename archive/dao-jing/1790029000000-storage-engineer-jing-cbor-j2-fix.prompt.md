Created-GMT: 2026-09-21 23:45:00 GMT
Created-Local: 2026-09-22 06:45:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J2 turn)
# Task: jing-cbor-j2-fix: apply the J2 reviewers' agreed findings
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 06:45:00 +07 | Status: active | Rationale: same implementer fixes its own code (resume rule); reviewers were glm-5.3 (READY) and deepseek-v4-pro (READY WITH CHANGES), different families

Work ONLY in /Users/sto/workspace/worktree-jing-cbor (HEAD 4f928dbd; J2 uncommitted).
Do NOT stage, commit, merge or push. Same lane wrapper scripts as your J2 turn
(cljd.sh, cljd-fast.sh, lint.sh, each run ALONE as one simple command). Frozen files
(corpus, README, generator, cbor_fixtures_test.cljc) and the RATIFIED errata text stay
untouched EXCEPT the one additive entry below. Inputs in this worktree's collab/:
1790028600000-ref-j2-review-glm.findings.md and
1790028600000-ref-j2-review-deepseek.findings.md (after the two banner lines).

## Do

1. **GLM P3-1 (agree; deepseek judged the runners guarded, but GLM traced that on Dart
   a failing `is` inside the catch handler throws again and escapes the doseq).**
   Make EVERY corpus runner deftest robust on Dart the way the encode-refusal runner
   already is: `guarded` must not call `is` inside its catch; it must record `[id
   failure]` into a per-deftest collector and the deftest asserts once at the end that
   the collector is empty (print the first several failures). Apply to:
   canonical-cases-encode-and-round-trip, decode-refusal-cases, groups-hold-on-produced-
   bytes, encoding-ignores-ambient-print-settings, and the refusal-class `is` branches in
   the encode-refusal runner. Behavior on JVM and Node must stay equivalent (a failing
   case still turns the lane red and names the case id). To prove the change, run a
   quick MUTATION of your own on Dart (for example temporarily corrupt one canonical
   expected byte in the test's in-memory case, run cljd-fast.sh, confirm it fails and
   names the id, and that later cases still ran), then REVERT the mutation; report that
   you did it and that the tree is clean of it.
2. **GLM P3-2.** Add a `:cljd` evidence deftest mirroring the `:cljs` evidence test:
   assert the Dart merges the E1 Dart column relies on, so the column fails loudly if
   ClojureDart ever changes: a set of `0.0` and `-0.0` holds ONE member, and a set of `[1]`
   and a list `(1)` (use `fresh-list`) holds ONE member; also assert the live rows do
   not merge (int 1 vs 1.0 keeps two members; two NaN values keep two).
3. **Errata Dart column (deepseek P2).** The ratified errata has no Dart column. Do NOT
   edit E1. ADD a new entry at the END of test/resources/dao/jing/cbor-v1.errata.md:
   `## E6. Dart column of the E1 table (PENDING architect ratification at J3)` with the
   Dart column: signed-zero and vector-list N/A (Dart's set constructor merged the
   members); the other seven live and refused with the frozen class; host-collapse never
   raised on Dart (slash-crossed identifiers are field-equal and stay distinct); the two
   Dart-only observations (BigInt.compareTo returns any sign-magnitude and is normalized;
   the Dart UTF-8 decoder drops a leading BOM and the reader restores it). Pure ASCII,
   80 columns. The two J2 skip reasons in the test may cite E6.
4. Re-run: lint.sh (fix then check), generator `--check`, the focused JVM pair,
   `bb test:cljs`, and the full cljd.sh. Report counts you observe only.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
then: per finding (agree or declined with reason), files changed, the mutation check
result, what you ran with exact counts, what you could not run.
