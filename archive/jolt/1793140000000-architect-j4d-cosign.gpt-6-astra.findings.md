Completed-GMT: 2026-10-27 20:53:51 GMT
Completed-Local: 2026-10-28 03:53:51 +07
Coding-Agent: codex
Session-ID: 01a17f20-dbc8-7882-a1e9-275bc708bd4c

Reviewed `671cd21f..origin/jolt-j4d`, six commits ending at `f35df68a2ec75ec64d1cacd0c894d209e1544232`. Read-only; no suites run or files edited. I formed my assessment before reading the Claude review. All line references below refer to the reviewed tip.

No P0/P1 findings. Two nonblocking follow-ups:

- **P2 | [src/cljc/yin/repl/main.cljc:186](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:186) | Evidence:** Invalid UTF-8 in a key file throws before `load-key` constructs its path-bearing refusal. `fs.cljc:89` delegates directly to `text/utf8-decode`, whose error at `text.jolt:284` contains no path. This remains fail-closed and exposes no seed, but misses the documented path diagnostic. The new test at `main_test.cljc:459` covers malformed EDN, not malformed bytes. **Concrete fix:** wrap read/decode failures with a sanitized key-file refusal containing `:path`, and add an invalid-UTF-8 fixture.

- **P2 | [native/jolt-file/probes/run.clj:587](/Users/sto/workspace/datomworld/native/jolt-file/probes/run.clj:587) | Evidence:** The `--no-state` probe runs after `--reset` saved an empty configuration and supplies its store explicitly. It therefore cannot distinguish “never read state” from “read state, then override it.” The unit test at `state_test.cljc:187` similarly overrides the saved VM. Production code correctly bypasses loading at `main.cljc:844–849`; this is a coverage gap. **Concrete fix:** place malformed state on disk, start with `--no-state`, and assert successful startup plus byte-identical state afterward.

The requested properties otherwise hold by inspection:

- **Store composition:** `parse-arg` accepts `file:`; `checked-spec` refuses only `:dht`; `open` dispatches `:file` to the shared durable store. [store.cljc:156](/Users/sto/workspace/datomworld/src/cljc/yin/repl/store.cljc:156)
- **Saved flags:** jolt `-main` enables persistent startup. State reads distinguish absence from dangling links, nonregular entries and decode failures. [main.cljc:1400](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:1400), [state.cljc:238](/Users/sto/workspace/datomworld/src/cljc/yin/repl/state.cljc:238)
- **Key security:** creation uses `O_CREAT|O_EXCL|O_CLOEXEC` with mode `0600`; umask can only restrict it further. Writes use the acquired descriptor, avoiding a create/reopen race. Existing final-path symlinks cannot be followed by this exclusive create. Seeds stay out of the inspected diagnostics and subprocess arguments. [jolt_file.c:366](/Users/sto/workspace/datomworld/native/jolt-file/jolt_file.c:366), [file.jolt:620](/Users/sto/workspace/datomworld/src/jolt/dao/posix/file.jolt:620)
- **Failure handling:** layout mismatch refuses before binding the new exports. The shared writer completes partial writes, propagates write/sync failures and closes once; existing J4b probes inject write, fsync and close failures through this helper. J4c append poisoning remains unchanged. Partial key creation can leave a private file that subsequent keygen refuses; no rollback or power-loss durability is implied. [file.jolt:75](/Users/sto/workspace/datomworld/src/jolt/dao/posix/file.jolt:75), [file.jolt:586](/Users/sto/workspace/datomworld/src/jolt/dao/posix/file.jolt:586)
- **Portability and invariants:** production changes select jolt arms; JVM/Node/Dart algorithms remain unchanged. No new registry, interpreter duplication or stream-contract change appears.

I independently confirmed zero J4-owned manifest/lane entries and compared restored tests against their pre-guard revisions. Spot-checks include index publication failure handling, linker-head recovery and custody-journal reopening. The implementer’s “3 Node-only” accounting is incorrect: five retired store tests are Node-only and one is Dart-only; the Claude reviewer’s correction is supported by the source.

The cited J4d worktree logs are no longer present at their recorded paths. Consequently, platform counts and cross-host execution results remain reported evidence, not independently verified execution in this review.

**Verdict: APPROVE. Branch `jolt-j4d` at `f35df68a` is ready to merge; the two follow-ups above are nonblocking.**