Completed-GMT: 2026-10-15 20:56:47 GMT
Completed-Local: 2026-10-16 03:56:47 +07
Coding-Agent: codex
Session-ID: 01a14147-dc34-7901-ba78-b7599f415c2c

# G1 / L-d-1b — preserve the link waiter's return contract

Implemented on `srvm-try-flag` at base `6b001f87`, uncommitted and unstaged.

Changes:

- `ucf/rows.cljc`: append one required data slot, `:yin.k/try?`, to `:link-request`, `:link-response`, and `:install`; lift normalizes an absent runtime flag to false and restore carries the boolean back to the waiter. Existing stream, cursor and operation-id slot positions stay fixed. `pending-keys` also carries the slot through standalone frame conversion.
- `semantic/frame.cljc`: require a boolean on each link pending; missing/non-boolean flags yield `:pending-try`. The row gate rejects malformed flags before resource attachment.
- `ucf/remote.cljc`: preserve the same flag in both directions for all three link variants.
- Portable tests in `rows_test.cljc`, `frame_test.cljc`, `safepoint_harness_test.cljc`, and `remote_test.cljc`: true/false frame bijection; request/response handoff refusal under try/plain require, tail/non-tail and retained/sent requests; install abandonment return contracts; pack round trips; missing/non-boolean validator checks and pre-attachment rejection. Existing row and remote install-shape expectations gain the false flag.
- Allowed documentation only: UCF §7.4.3 grammar sentence, dated I-6 fixture record, and closure of the G1 known-gap row.

Cross-engine audit:

- `module/handle-require` builds each waiter's `:try?` flag outside backend payload construction, falling back to the backend's `:module/require` payload builder for try-require.
- `semantic/layout` lift-state/lower-state remove only their respective register/state keys and merge the remaining engine fields, including `:try?`. No backend loss of this flag was found.
- `debruijn/register.cljc` register-restore and `debruijn/stack.cljc` stack-restore receive already-settled link outcomes from the shared engine. Neither reconstructs link pending metadata. No backend or engine edit was necessary.
- Existing limitations on cross-engine link/install handoff remain outside this one-slot slice; no new cross-engine link support is claimed.
- The separate known-gap row about install waiters' `:link-id` remains open. This slice only carries the requested boolean.

Fixture evidence:

- Inspected fixture history at `19528b7c` (phase 8c) and I-6. Used the documented JVM `yin.vm.ucf.rows-checkpoint-fixtures/render` route, writing a temporary file first and comparing every block before replacing the one fixture file.
- Only four blocks move, all derived from the base with the install pending:

| Block | Old pack address | New pack address |
|---|---|---|
| installs | `segment/blake3-1d3897a10b81a5e58ca71d1f249cf8f0002a64004f2636cbcad8749305f8614b` | `segment/blake3-d56bfa712ecfb2c8a6115b854f5ce48648468e8dcefd9096dbeadfbb31b15164` |
| child-header | `segment/blake3-91d1f4e782116f4d172b3d7da11129c20c8e16eee2c24850ca72b7a425905f01` | `segment/blake3-767ec0379e4ba4d8885b7e956c241fff73fac495cbe6a2db5826ae1d4dda13d9` |
| install-without-entry | `segment/blake3-3c2e318e87e3f381719c93ed8d2b303b9cb137bb94d3d91928c0c23659e8dea0` | `segment/blake3-d8786a650510c9ae020a81924bc2540ea9cf0a0327222a459440060da317ea09` |
| child-op-id-out-of-range | `segment/blake3-dee0988a36e9a00e10861bbe1512eae34093d48b7885bda65ba2ce0cfe5a8861` | `segment/blake3-32de289b7ed1ae55c3ade7283cb67474e25d377f41a1f446e4832d05811772a4` |

- The installs task root moves from `segment/blake3-d93e4037a2375dfb8a741b53356b1d10b29442dde471c81a1f415437879d2eed` to `segment/blake3-678f4bff18681357982b3ed9bea529121709bf4fd5fca5fba1fd1771ff358364`. Raw CBOR comparison finds exactly one removed/added row of each kind `:pending`, `:wait`, `:task`. All other rows, including code references, streams and descriptors, are identical; all other anchor bytes and fixture blocks are unchanged. No pinned code A, descriptor hash, or other golden was edited.

Validation:

- Changed-source/test kondo: 0 errors, 0 warnings (0 new).
- Required focused command (`clojure -M:test -e :slow -n yin.vm.ucf.rows-test -n yin.vm.ucf.rows-custody-test -n yin.vm.semantic.frame-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.semantic.effects-test -n yin.vm.linker-test`): 193 tests, 3,206 assertions; 0 failures, 0 errors.
- Additional rows-checkpoint fixture suite: 17 tests, 179 assertions; 0 failures, 0 errors.
- Remote conversion suite: 23 tests, 225 assertions; 0 failures, 0 errors.
- Full JVM quick tier `clojure -M:test -e :slow`, unrestricted loopback: 4,013 tests, 249,779 assertions; 0 failures, 0 errors; exit 0.
- `git diff --check`: passed.
- No direct Node or Dart lane was run.

Mutant proof:

- A temporary copy of `ucf/rows.cljc` under `/tmp/try-flag-mutant` replaced both lift expressions `(boolean (:try? e))` with `(boolean (:try? (dissoc e :try?)))`. This discards the source flag while preserving the new row's shape, recreating the old plain-require semantics rather than merely causing a row-arity error.
- Ran only the new `link-refusal-keeps-each-return-contract-after-handoff` test with that copy first on the classpath. It exited 1: 1 test, 1 passing assertion, 0 assertion failures, 1 error, `Module link refused: absent`, with `:link-module host.mod`. The intact implementation passes the focused suite. The working-tree source was never mutated for this check.

Run notes:

- Initial focused development run exposed two test-expectation issues (loss data includes a link-id field, and lower-task throws validation refusals). Corrected the tests; final counts above are clean.
- The checkpoint suite failed against the old fixture as expected, before regeneration; it passed afterward.
- The first complete quick run had 4,011 tests / 249,746 assertions, 2 failures / 0 errors: both were remote install shape assertions expecting the old flagless map. Updated those shape assertions for the required field, added remote true/false round-trip coverage, and reran the whole quick tier. No pinned literal or golden hash was involved.
- Initial full quick attempt stopped at namespace loading because this fresh worktree lacked `yang.python.antlr.gen.Python3Lexer`. Built the repository's pinned prerequisite with `clojure -M:antlr-gen` (four Java files; digests verified; output only under ignored `build/`), then reran the required quick command with unrestricted loopback.
- An initial fixture-generation command incorrectly inherited the test alias's main opts and invoked the test runner; it did not modify the fixture. Corrected generation used a separate alias adding the test classpath, with the normal clojure.main entry point.
- Logs are under `/tmp/try-flag-{focused-final,fixture-final,kondo-final,quick,mutant,fixture-evidence,antlr}.log`.
- No src/yang or test/yang edits, no other documentation edits, no staging, no commit, no collab commit. Pre-existing untracked collab inputs/logs were left untouched.

READY
