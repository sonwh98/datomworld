Completed-GMT: 2026-10-04 19:49:59 GMT
Completed-Local: 2026-10-05 02:49:59 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1080c-197c-7e41-8fcb-03414cea37b6

**Both round-3 scenarios are resolved on ordinary Unix filesystems across all three hosts.** One cross-platform gap remains.

1. **P2 — Dart’s typed catch is broader than true absence on Windows. Confirmed.** [state.cljc:201](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:201) treats every `PathNotFoundException` as absence unless a link exists. Dart maps Windows errors including invalid names, invalid drives, bad network paths, and excessive filename length to that exception. Those errors can therefore silently start the node with defaults. On Unix, the mapping distinguishes `ENOENT` from access failures correctly. Check the underlying OS error rather than accepting the entire exception class. [Dart SDK exception mapping](https://github.com/dart-lang/sdk/blob/main/sdk/lib/io/file.dart).

2. **P3 — Node’s secondary check suppresses all errors. Plausible failure scenario; confirmed suppression.** [state.cljc:212](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:212) converts every `lstatSync` failure into “no entry.” If the initial read returns `ENOENT` but the subsequent check fails with access or I/O trouble, startup silently skips state. Return false only for `ENOENT`; propagate other errors. Stable, accessible dangling links are correctly refused.

The JVM change at [state.cljc:204](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:204) is correct: `NOFOLLOW_LINKS` recognizes dangling links as entries. Normal files and links to existing files proceed to `slurp`, which reads normally. Indeterminate checks proceed to reading and refusal.

Dart now propagates access errors, directories, and other non-`PathNotFoundException` failures. Its dangling-link check handles the reported scenario correctly. I continue to accept the documented `--` guard.

No files were edited or tests rerun. **Not ready to merge for an unrestricted three-host portability claim:** narrow Dart’s Windows absence classification and Node’s secondary catch.
