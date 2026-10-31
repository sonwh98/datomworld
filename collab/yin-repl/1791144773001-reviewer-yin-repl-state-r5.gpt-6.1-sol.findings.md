Completed-GMT: 2026-10-04 20:13:22 GMT
Completed-Local: 2026-10-05 03:13:22 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1080c-197c-7e41-8fcb-03414cea37b6

**Both round-4 items are resolved.**

- **Dart:** [state.cljc:207](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:207) rethrows when `osError` is nil or its code is outside `{2 3}`. Windows invalid-drive, invalid-name, network-path, and filename-length errors are therefore refused. Unix access failures bypass this typed catch; Unix error 3 does not become `PathNotFoundException` through Dart’s standard mapping. [Dart SDK mapping](https://github.com/dart-lang/sdk/blob/main/sdk/lib/io/file.dart).
- **Node:** [state.cljc:222](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:222) now propagates every secondary `lstatSync` error except `ENOENT`, allowing `load-flags` to produce the designed refusal.

Codes 2/3 can describe a missing symlink target rather than a missing entry; the retained link check handles that case. A missing parent also means no state-file entry exists at the requested path. I found no further actionable misclassification in this delta.

The Dart dynamic warning is not a correctness blocker given the typed catch, available property, and reported runtime verification. I identified no remaining cross-platform blocker or residual requiring a supported-host exception.

No files were edited or tests rerun.

**Sign-off: the whole change through 04379079 is ready to merge.**
