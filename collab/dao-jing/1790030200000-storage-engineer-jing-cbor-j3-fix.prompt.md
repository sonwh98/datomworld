Created-GMT: 2026-09-22 00:50:00 GMT
Created-Local: 2026-09-22 07:50:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 6f2d9a14-7b03-4c58-8e1a-0d5b3c7f9e21 (resumed: your J3 turn)
# Task: jing-cbor-j3-fix: harden the conformance gate per the reviewer's findings
Role: QA / Verification Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 07:50:00 +07 | Status: active | Rationale: same implementer fixes its own gate (resume rule); reviewer was qwen3.8-max (different family), READY WITH CHANGES

Same worktree (/Users/sto/workspace/worktree-jing-cbor), same rules and same wrapper
scripts as your J3 turn (each run ALONE as one simple command). Do NOT stage or commit.
Input: collab/1790029900000-ref-j3-review-qwen.findings.md (read in full). File box:
test/dao/jing/cbor_conformance_test.cljc, the `read-path` area of cbor_fixtures.cljc if
needed, and ONE addition to the END of the E6 section of
test/resources/dao/jing/cbor-v1.errata.md (see item 6). Nothing else; frozen files stay
untouched, and errata E1-E5 stay untouched.

## Do

1. **P2-2 (hardening the write scan).** Add `py` to the scanned file types; add the
   Python markers `os.remove`, `os.unlink`, `os.rename`, `os.replace`, `write_text`,
   `write_bytes`, `shutil` (spell-concatenated like the existing markers so the test
   file does not match itself); for `.sh` files and bb.edn, flag any line that names the
   corpus path together with `>`, `tee`, `cp `, `mv ` or `rm `. Extend the generator
   check to cover those markers. Keep the test robust against false positives on the
   generator itself (the generator must remain allowed to write to stdout only; assert
   that it opens no file for writing). Add a JVM teardown-style re-assertion of the
   digest pin (a `use-fixtures :once` after-hook is acceptable) so one run cannot both
   modify the resource and pass, or explain in the report why it is not portable.
2. **P3-1.** Make the independent walker enforce E3 (tag 4 exponent within
   [-2147483647, 2147483648]) and E4 (depth limit 128 for PRODUCED bytes; keep the
   frozen-byte walk consistent: no corpus payload nests beyond a handful of levels, so
   applying 128 to both is fine). Add self-test vectors for both.
3. **P3-2.** Document the bignum-denominator gcd limitation in the walker's banner
   comment (no code change).
4. **P3-4.** Close the `:e6` section of `errata-rows` at the next `## ` heading.
5. **P3-5.** Convert the direct `is` calls in the skip-accounting deftests to the
   collect-then-assert pattern (`check!` and one `assert-none!`), so a Dart failure does
   not abort the rest of the deftest.
6. **Errata E6 (pending) addition, append only at the end of the E6 section:** one
   short entry recording that `coll/set-vector-list-collapse` is encode-unobservable
   on all three hosts (the host set constructor merges the list and the vector before
   the codec sees the value), is covered decode-side by its twin
   `mal/collapse-vector-list-set` (`equality-collapse`, runs on all three hosts), and must
   be reactivated on any host that gains a non-merging set construction; the
   conformance gate pins it as the single never-run case. Pure ASCII, 80 columns.
   Declared PENDING architect ratification like the rest of E6. Keep the gate's
   errata-row parsing correct after the addition (the `[9 9]` row-count pin must still
   hold; the new text must not create fake rows).
7. Decline P3-3 (no external tool available to you; the orchestrator already compared
   the pin with `sha256sum`: it equals c8f5ef38...7351).
8. Re-run: lint.sh (fix, then check), `git diff --check`, generator `--check`, the
   focused JVM trio, `bb test:cljs`, the full cljd.sh. Report counts you observe only.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 6f2d9a14-7b03-4c58-8e1a-0d5b3c7f9e21
then: per finding (agree or declined with reason), files changed, what you ran with
exact counts, what you could not run.
