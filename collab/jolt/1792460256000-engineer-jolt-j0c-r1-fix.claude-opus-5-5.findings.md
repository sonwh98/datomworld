Created-GMT: 2026-10-20 01:37:36 GMT
Created-Local: 2026-10-20 08:37:36 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0c round 1 fixes after review (F1, F3, cross-target check)
Role: Compiler & AST / Stream engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 08:37:36 +07 | Status: done | Rationale: fix accepted review findings

Review: `collab/1792459913000-reviewer-jolt-j0c-r1.qwen3.8-max.findings.md`.
Worktree: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0c`. Logs are in its `build/j0c/logs/`.
Changes are uncommitted and nothing was staged.

## Finding -> change

| finding | change |
|---|---|
| **F1 (required)**: the catch handlers allocated | `native/jolt-parser/jolt_parser.cpp` `message()` is now `noexcept`. It sets `*out = NULL` and `*out_len = 0` first, then copies `e.what()` (or `"unknown exception"`) using only `strlen`/`malloc`/`memcpy`. If that `malloc` fails, the buffer stays NULL and only the status (−2/−3) reports the failure. No `std::string`/`std::vector` remain in any failure path, and nothing in a handler can throw. The header comment documents this. |
| F1 test | New exported **test seam** `int32_t jolt_parser_inject_fault(int32_t mode)`. It is off (0) unless called, and answers the previous mode. Mode 1 throws `std::bad_alloc` inside the parse's `try`; mode 2 throws a non-`std::exception` (`42`); mode 3 throws `bad_alloc` and also makes the handler's own `malloc` fail. The probe adds four checks (below). The seam is always compiled in: it is one atomic int, disclosed here, and gating it behind a build option is a later-round decision. |
| F2 (info) | No change, as the review asked. DEDENT at index 0 wrapping and text semantics stay under the deferred JVM-oracle span parity. |
| F3 (info) | `antlr/python3/manifest.edn`, beside the `Cpp/` `:files` entries: "Every target's gen:antlr run fetches and digest-checks ALL of these, so a Cpp helper fetch failure also blocks the Java, JavaScript and Dart generation; all targets re-pin together." |
| F4 (orchestrator note) | Acknowledged. The probe had 7 checks and now has **11**. |

## F1 evidence: `src/dev/jolt_parser_probe.clj`, 11/11 PASS (`fix-probe.log`, `fix-probe-final.log`, both exit 0)

The 7 round-1 checks are unchanged and still pass. The new checks:

| check | result |
|---|---|
| `[injected]` `std::bad_alloc` inside the parse | `{:status -2, :message "std::bad_alloc"}` PASS |
| `[injected]` non-std exception | `{:status -3, :message "unknown exception"}` PASS |
| `[injected]` `bad_alloc`, and the handler's own malloc fails | `{:status -2, :buffer-bytes 0}` (NULL buffer, no throw) PASS |
| after the injected failures, the same process parses `"1 + 2\n"` again | `{:status 0, :buffer-bytes 3196}` PASS |

- The jolt process survives every injected failure and reads the failure status. Each injection is reset to 0 in a `finally`.
- **Rebuild:** `bb build:jolt-native` exit 0, 49.62 s (`fix-build.log`).
- New dylib SHA-256: `00c27f065ced03717ac378d560b2a781fbd2aabd3232efdf5aa9eadc9c515419`. It changed because the source changed.
- `nm -gU` now shows four C entries: `jolt_parser_parse`, `jolt_parser_free`, `jolt_parser_layout_version`, `jolt_parser_inject_fault`.

## Cross-target verification (Java, JavaScript, Dart unchanged vs base `jolt-j0a`)

- **Task name:** `docs/agents/build-n-test.md:120,254,407` names `bb gen:python-antlr`, but `bb.edn` defines only `gen:antlr`. That is a doc/task naming mismatch, pre-existing and not changed here. I ran **`bb gen:antlr`** (every target, foreground, with no other generation running in this worktree).
  - Result: exit 0, 3.65 s (`fix-gen-all.log`). "generated 8 cpp / 4 dart / 4 java / 4 javascript files".
  - The generator throws on any difference from a target's recorded `:generated` digests, so a zero exit already proves each output matches its record.
- **Network:** none was needed. `build/antlr/python3/upstream/` already held all 12 pinned files from round 1. The log's "fetched" label is printed for cache hits as well, and the generator downloads only when a file is absent.
- **Recorded digests:** the `:java`, `:javascript` and `:dart` target maps, including `:generated`, are `=` to `jolt-j0a`'s `antlr/python3/manifest.edn` (read with `clojure.edn`).
- **Before/after:** before is `jolt-j0a`'s recorded digests; after is this run's `generated-digests.edn` (`fix-gen-compare.log`, first 16 hex digits shown). All equal.

| file | before | after |
|---|---|---|
| Python3Lexer.java | bb61a463af3d2c5d | bb61a463af3d2c5d |
| Python3LexerBase.java | 1c8ac0a2bd1cc149 | 1c8ac0a2bd1cc149 |
| Python3Parser.java | 8ba1f2398e52994e | 8ba1f2398e52994e |
| Python3ParserBase.java | 332745797ab6423d | 332745797ab6423d |
| Python3Lexer.js | 855f47de669d2f04 | 855f47de669d2f04 |
| Python3LexerBase.js | 4724a9da8856cf08 | 4724a9da8856cf08 |
| Python3Parser.js | 37f27bdb7036ae99 | 37f27bdb7036ae99 |
| Python3ParserBase.js | a569e60fdf199334 | a569e60fdf199334 |
| Python3Lexer.dart | a29f3f8cceb6ec2b | a29f3f8cceb6ec2b |
| Python3LexerBase.dart | e3b11f882d64f64d | e3b11f882d64f64d |
| Python3Parser.dart | fa00271187b2ba97 | fa00271187b2ba97 |
| Python3ParserBase.dart | e4c4693a640e53b9 | e4c4693a640e53b9 |

Nothing differs.
- Generated output went to gitignored `build/antlr/python3/` and `lib/antlr/python3/`. Nothing was staged.

## Commands and exit codes (foreground, in the worktree)

| command | exit |
|---|---|
| `bb build:jolt-native` | 0 (49.62 s) |
| `. build/tools/jolt-env.sh && "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_parser_probe.clj` (×2) | 0, 11/11 PASS |
| `bb gen:antlr` | 0 (3.65 s) |
| digest comparison vs `git show jolt-j0a:antlr/python3/manifest.edn` | all equal |
| `bb smoke:jolt` | 0, `SUMMARY ok` (`fix-smoke.log`) |
| `mise exec -- cljstyle check` on the changed files | clean. `manifest.edn` still shows only the pre-existing JS-patch indentation hunks that base has |

## Files changed this round (worktree `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0c`)
- `native/jolt-parser/jolt_parser.cpp` (F1: nonallocating `noexcept` failure path, fault-injection test seam)
- `src/dev/jolt_parser_probe.clj` (F1: four new checks, the seam binding)
- `antlr/python3/manifest.edn` (F3 note)
