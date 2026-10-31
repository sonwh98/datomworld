Completed-GMT: 2026-10-04 18:52:28 GMT
Completed-Local: 2026-10-05 01:52:28 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1080c-197c-7e41-8fcb-03414cea37b6

**The round-2 residual is partly resolved.**

- **JVM:** [state.cljc:203](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:203) correctly passes an empty `LinkOption[]`. An indeterminate existence check returns false, so the code attempts reading and produces the wrapped refusal. There is no varargs defect. [Java documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#notExists(java.nio.file.Path,java.nio.file.LinkOption...)).
- **Node:** [state.cljc:208](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:208) correctly returns nil for `ENOENT` and rethrows other errors for `load-flags` to wrap.
- **Dart — residual P2:** [state.cljc:199](/Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state/src/cljc/yin/repl/state.cljc:199) fixes directories and detectable links, but still silently skips an inaccessible path when all three existence checks return false. A bare start can still use defaults without a warning. This is acknowledged in the code, not resolved.

The Dart limitation is a limitation of this implementation’s existence checks. Direct reads expose `FileSystemException.osError`, including an OS error code, allowing absence and access errors to be distinguished with appropriate platform handling. [Dart exception documentation](https://api.dart.dev/dart-io/FileSystemException-class.html), [OS error documentation](https://api.dart.dev/dart-io/OSError-class.html).

**Additional strictness gap — dangling links:** JVM’s empty options array follows symbolic links, and Node reads follow them too. Consequently, `state.edn` pointing to a missing target is treated as absent on those hosts, whereas Dart’s link check leads to a refusal. This is confirmed from the code and API behavior, though it predates this correction. If “true absence” means no directory entry exists, use a non-following check before accepting absence.

**I accept keeping the `--` guard.** The restriction is now explicit and tested, and `./--name` preserves access to such paths. Ordinary DHT pass-through values, principals, and `dht:<dir>` store specifications remain supported. The shared-directory guidance is also corrected.

No files were edited or tests rerun. **Not ready to merge under the stated “never silently skipped” requirement:** the Dart access-error path remains unresolved.
