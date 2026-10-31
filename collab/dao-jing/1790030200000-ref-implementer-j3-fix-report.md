Completed-GMT: 2026-09-22 00:32:27 GMT
Completed-Local: 2026-09-22 07:32:27 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 6f2d9a14-7b03-4c58-8e1a-0d5b3c7f9e21

## Per finding

- **P2-2 (write-scan hardening): agreed, applied.** Added `py` to the scanned extensions; added the spell-concatenated Python markers `os.remove`, `os.unlink`, `os.rename`, `os.replace`, `write_text`, `write_bytes`, `shutil` and check them against `.py` files that name the corpus. For `.sh` files and `bb.edn`, added a line-level scan flagging any line that names the corpus together with `>`, `tee`, `cp `, `mv ` or `rm `; the scan now also asserts it reaches `bb.edn`. Added a self-test (`the-scan-flags-write-attempts-it-must-catch`) that proves the scan on throwaway files outside the repo (5 positive probes, 2 negative). Extended the generator check to cover the new Python markers and `pathlib`. Added a JVM `clojure.test/use-fixtures :once` after-hook that re-asserts the resource SHA-256 pin once after every test in the namespace has run, so a write inside this run's own process cannot both happen and pass silently. It is JVM-only (`#?(:cljd nil :clj ...)`); Node and Dart have no fixture hook here, noted rather than silently skipped, because a within-run write is not the threat those two hosts pose (they only read the resource in this namespace).
- **P3-1: agreed, applied.** The walker now enforces E3 (decimal exponent in `[-2147483647, 2147483648]`, checked on tag 4's first item, magnitude read from the raw major-0/1 node so no BigInt is needed) and E4 (depth limit 128, applied uniformly to both produced and frozen-byte walks, per the reviewer's own suggestion that the corpus "nests a handful of levels" so one limit is fine). Added 5 new bad vectors (exponent 2^31+1, exponent -2^31, a bignum exponent which now correctly still only fails shape not window, non-reduced ratio, nesting one past 128) and 3 new good vectors (exponent exactly 2^31, exponent exactly -(2^31-1), nesting exactly at 128, a reduced ratio).
- **P3-2: agreed, applied (doc only, no code change).** Added the limitation to the walker's banner comment: ratio reduction is checked only when both numerator and denominator are major 0/1 (below 2^32); a bignum numerator/denominator is checked for shape only, with the frozen digest as the correctness backstop.
- **P3-3: declined**, per the task's own instruction — no external tool available to me; the orchestrator already cross-checked the pin against `sha256sum` and it matched.
- **P3-4: agreed, applied.** `errata-rows` now closes the `:e6` section at the next `## ` heading (an `:after` section the errata-merged reader ignores), instead of running to end of file.
- **P3-5: agreed, applied.** Both skip-accounting deftests (`every-case-is-run-or-skipped-by-a-documented-id`, `documented-skips-match-the-errata-and-leave-no-case-unrun`) now use `check!` into a failures atom with one `assert-none!` at the end, matching the file's own banner promise; no direct `is` remains in either.
- **Errata E6 addendum: agreed, applied.** Appended one PENDING-ratification entry at the end of the E6 section of `cbor-v1.errata.md` recording that `coll/set-vector-list-collapse` is encode-unobservable on all three hosts, covered decode-side by `mal/collapse-vector-list-set` (`equality-collapse`), and must be reactivated on any host that gains a non-merging set construction. Kept pure ASCII and within 80 columns. E1–E5 untouched; the `[9 9]` row-count pin still holds (verified below) because the new text is prose, not a `| \`coll/...\`` row.

## Files changed
- `test/dao/jing/cbor_conformance_test.cljc` (939 → 1081 lines): the changes above.
- `test/resources/dao/jing/cbor-v1.errata.md` (149 → 159 lines): the one E6 addendum, appended at the end.
- `test/dao/jing/cbor_fixtures.cljc`: unchanged this turn (the `read-path` addition from J3 was already sufficient).

No production file, no frozen file (corpus, README, generator, `cbor_fixtures_test.cljc`), and no E1–E5 line was touched. Nothing staged or committed.

## What I ran, with exact counts
- `lint.sh fix` then `lint.sh`: errors 0, warnings 0 on all six listed files (both times).
- `git diff --check`: clean, no output.
- `python3 test/resources/dao/jing/cbor-v1.generate.py --check`: "cbor-v1.json matches the generator output byte for byte".
- `clojure -M:test -n dao.jing.cbor-conformance-test -n dao.jing.cbor-test -n dao.jing.cbor-fixtures-test`: 46 tests, 3226 assertions, 0 failures, 0 errors. Conformance digests: canonical `6d1c64719d96c11f79d575bd3122ad1bba1ba9efcc56dce63b195423575cf2cb` (n=208), refusal `1b929147af85f703c700a1f0d9cfbe74c55e5e28302a862086516fe749ec1b82` (n=154); skipped 5 ids (same set as before this fix turn).
- `bb test:cljs`: 1618 tests, 42306 assertions, 0 failures, 0 errors. Same two digests, node skipped 6 ids.
- `cljd.sh` (full lane): exit 0, "+1580: All tests passed!". Dart printed the same two digests, skipped 5 ids (matches JVM's set exactly, unlike node).

All three hosts' canonical and refusal digests are identical to each other and to the JVM run before this fix turn (the P3-1 walker changes affect only the independent-inspection tests, not the manifest signatures, so the digest values were expected to hold — confirmed).

## What I could not run
- No external SHA-256 tool (openssl/sha256sum) — declined per task instruction (P3-3), orchestrator already confirmed.
- No fixture-hook verification on Node or Dart (not applicable per the task's own allowance to explain rather than implement if not portable).
