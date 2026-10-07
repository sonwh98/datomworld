Completed-GMT: 2026-10-04 18:23:32 GMT
Completed-Local: 2026-10-05 01:23:32 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1080c-197c-7e41-8fcb-03414cea37b6

1. **Missing values — resolved.** [state.cljc:54](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:54) rejects incomplete saved and one-shot flags before resolution. Both the trailing `--port` and incomplete duplicate scenarios refuse before saving.

2. **Filesystem failures — partly resolved; residual P2.** [state.cljc:197](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:197) correctly converts thrown read errors into refusals naming the file and `--reset`. However, [fs.cljc:75](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/dao/space/store/fs.cljc:75) still conflates absent files with failed existence checks and, on Dart, directories.

   The residual is unacceptable under the explicit “never silently skipped” requirement. A subsequent warning is **not guaranteed**: on a bare start with no explicit flags, [main.cljc:638](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/main.cljc:638) skips writing altogether. The node can therefore start with defaults without any state warning. This consequence is confirmed from the code; permission-dependent existence failure remains plausible. Use a strict state reader that treats only actual absence as nil.

3. **Shared state directory — resolved through documentation, with a minor wording correction.** [yin.repl.md:95](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/vm/docs/yin.repl.md:95) now accurately explains the ordinary lock boundary and last-writer behavior. Documentation is sufficient for the chosen scope; an additional lock is not necessary to resolve my original finding. Change the final guidance to “give each its own node directory (`--name`, or distinct `--dir`)”: different names do not help when both processes explicitly use the same `--dir`.

4. **Non-keyword configuration keys — resolved.** [state.cljc:164](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:164) checks `keyword?` before calling `name`, so numeric and string keys receive the intended refusal.

**New P3 — legitimate relative key paths beginning with `--` are rejected. Confirmed.** [state.cljc:54](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:54) rejects `--dht-key --publisher.key`, `--dht-keygen --publisher.key`, and the expanded key value from `dht init --key --publisher.key`. Quoting does not help; `./--publisher.key` does. This is a small compatibility restriction worth documenting or testing.

The guard does not reject ordinary DHT pass-through flags with valid values, hexadecimal principals, or `--index-store dht:--directory`, because that value begins with `dht:`.

No files were edited and no tests were rerun. **Not ready to merge:** the remaining silent-skip path violates the required startup behavior.
