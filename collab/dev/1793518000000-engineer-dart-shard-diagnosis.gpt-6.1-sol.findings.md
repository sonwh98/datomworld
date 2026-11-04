I’ll read the repo rules and harness, then reproduce the compile failure on jupiter and inspect `allns`. I’ll keep edits confined to `src/dev/dart_shard.clj` and won’t use shiva.


===

Jupiter is idle, and its CI checkout is now at `origin/master` (`120ff322`) with the output-capture fix. I also found that the lane continues after a compile failure, so I’ll use a diagnostic wrapper to stop there and avoid launching Flutter against stale output.


===

The old artifact reproduces a malformed `allns`: it has 50 entries—17 namespace strings and 33 individual characters—instead of 269 namespace strings. The `mapcat` result is already flat; `apply concat` then expands those strings into characters. The fresh run is still building the Dart peer before reaching compile.


===

The patch uses `concat` directly, handles a missing `ns-uncovered.txt` when there are no residual namespaces, and exits immediately if compile fails. The fresh run has now reached the compiler and confirms the same malformed arguments.


===

The captured error is:

```text
Compilation error - You’re gonna need a bigger boat! 🦈
compiling namespace t
=== Could not locate t.cljd or t.cljc ===
```

`t` is one of the characters produced by the harness expression. I’m now checking the patched expression against every manifest and running the corrected 269-namespace compile.


===

The patched `allns` contains exactly 269 strings and matches the union of all manifests. Its namespace arguments total 7,429 bytes, so argument length does not explain this failure. Checks also passed for absent, empty, and populated residual files, duplicate removal, and blank lines. The corrected compile is still running.


===

The corrected compile has progressed into the test namespaces and is still running; so far its output shows compiler warnings without an error. The fail-fast check also passed: a failed compile exits with code 1 before shard emission or Flutter. Once the full compile finishes, I’ll run the six-namespace `yang.base` subset through the patched lane.


===

The corrected compile passed all 269 namespaces in **204.33 seconds**. I’ve started the six-namespace `yang.base` run through the patched lane to measure compile, shard emission, and Flutter tests end to end.


===

The local and jupiter copies of `dart_shard.clj` match exactly. The subset run is still in its prerequisite stage; the peer’s ClojureDart compile has passed. After the peer executable finishes, the lane will compile the six selected namespaces and run Flutter tests.


===

The subset passed: six namespaces compiled in **34 seconds**, then Flutter passed **34 tests** in **14 seconds**. The end-to-end lane run took **134.71 seconds**, including the peer build and namespace discovery. I’m collecting the final artifact paths and checking the diff.


===

Confirmed a harness bug. Fixed only [src/dev/dart_shard.clj](/Users/sto/workspace/datomworld/src/dev/dart_shard.clj:119), without committing. Shiva was untouched.

1. **Real compiler error**, reproduced on jupiter at `origin/master` (`120ff322`):

   ```text
   Compilation error - You’re gonna need a bigger boat! 🦈
   compiling namespace t
   === Could not locate t.cljd or t.cljc ===
   ```

   Captured in `jupiter:~/ci/datomworld-nightly/target-ci/dart-shards-20261101-0133/compile.log`.

2. **`allns` was malformed:** 50 entries consisting of the 17 residual namespace strings followed by 33 characters:

   ```clojure
   \y \a \n \g \. \t \l \r \p \c \k \e \- \s \h \o \3
   \x \d \i \m \f \v \u \w \b \q \2 \j \z \6 \4 \5
   ```

   `mapcat` had already flattened the subsystem lists. `apply concat` then split their namespace strings into characters. The complete argv is preserved in `compile-argv.edn` beside the log.

3. **Fix diff:**

   ```diff
   -                     (apply concat (some->> (str out "/ns-uncovered.txt")
   -                                            slurp
   -                                            str/split-lines
   -                                            (remove str/blank?)))
   +                     ;; mapcat already flattened the subsystem lists. apply
   +                     ;; concat would split each namespace string into chars.
   +                     (concat (when (fs/exists? (str out "/ns-uncovered.txt"))
   +                               (->> (slurp (str out "/ns-uncovered.txt"))
   +                                    str/split-lines
   +                                    (remove str/blank?))))
   ```

   ```diff
   -      (when-not (zero? exit) (fail! "cljd compile failed")))
   +      (when-not (zero? exit)
   +        (fail! "cljd compile failed")
   +        (System/exit @rc)))
   ```

   This also handles an absent residual file and prevents Flutter execution after failed compilation. File-based output capture remains intact.

4. **Validation passed.**

   The corrected vector contains **269 strings**, exactly matching all manifests; namespace arguments occupy **7,429 bytes**. Compiling all 269 namespaces succeeded in **204.33 seconds**.

   Subset command on jupiter:

   ```sh
   cd ~/ci/datomworld-nightly
   export PATH="$HOME/.local/bin:/snap/bin:$PATH"
   eval "$(mise env -s bash)"
   /usr/bin/time -f "subset-total-wall=%e seconds" \
     bb /tmp/dart-shard-yang-base.clj
   ```

   That diagnostic wrapper restricts subsystem selection and the residual scan to `yang.base`, then invokes the actual patched lane.

   - Six namespaces compiled successfully: **34 seconds**.
   - Six test files emitted into six shards.
   - Flutter: **34 tests passed**, **14 seconds** wall time.
   - Total subset wall time: **134.71 seconds**, including prerequisites.

   Subset artifacts: `jupiter:~/ci/datomworld-nightly/target-ci/dart-shards-20261101-0140/`.

   Residual-file, duplicate, blank-line, and compile-failure exit checks passed; `git diff --check` passed.

5. **Not verified:** the full Flutter suite and a clean-output rebuild. Existing generated directories were retained. No product compile error remained, so no namespace-specific JVM handoff was needed.