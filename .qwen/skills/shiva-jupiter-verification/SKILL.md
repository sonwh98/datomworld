---
name: shiva-jupiter-verification
description: Running this repo's test gates on the shared remote CI clones (shiva = node/cljs lane, jupiter = JVM lane) without contaminating other seats' in-flight work
source: auto-skill
extracted_at: '2026-11-07T15:57:35.349Z'
---

# Verifying on shiva / jupiter

| Host | Lane | Clone | Run form |
|---|---|---|---|
| `shiva.stigmergy.systems` | node / cljs | `~/ci/datomworld-nightly-node` | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge "{:autorun false}"` then `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=<ns>` |
| `jupiter.stigmergy.systems` | JVM | `~/ci/datomworld-nightly` | `DATOM_SLOW_TESTS=1 clojure -M:test -n <ns> [-v <ns>/<var>]` |

Both need `export PATH="$HOME/.local/bin:$PATH"; eval "$(mise env -s bash)"` first (in a fresh
`/tmp` worktree run `mise trust <dir>` before `mise env`, or the config is untrusted).

## These clones are SHARED and usually dirty

Other seats run and edit in them concurrently. **Before touching anything:**

```bash
ssh -n shiva.stigmergy.systems 'cd ~/ci/datomworld-nightly-node && git rev-parse --short HEAD && git status --porcelain'
```

- Never `git checkout .` / `git stash` — you would destroy another seat's uncommitted work.
  Apply only your own scoped patch (`git apply --check` first), and revert **only the files you
  changed** if you clean up.
- Record what else was modified: your recompile picks it up, so every result you report was
  produced with those changes present. Say so in the report (e.g. shiva carried an uncommitted
  `shadow-cljs.edn` `--stack-size` bump and a `yin/repl/dht.cljc` `default-repair-slots` retune).
- Check `pgrep -af "node target/node-tests.js"` — another seat's run may be in flight; recompiling
  `target/node-tests.js` underneath it is a side effect worth reporting (their already-started
  node process is unaffected; later ones are not).

## jupiter's clone is at master, not your branch

`~/ci/datomworld-nightly` typically sits **detached at `origin/master`**. A brief that says
"apply your diff there and run the JVM lane" would then test *master + fix*, not the branch under
test. Check divergence first:

```bash
git merge-base --is-ancestor <branch-sha> origin/master && echo ANCESTOR || echo DIVERGED
git diff --stat <branch-sha> origin/master -- <the files you touched>
```

If the touched files differ, build an isolated worktree at the branch SHA instead:

```bash
git -C ~/ci/datomworld-nightly worktree add --detach /tmp/<name> <branch-sha>
# generated ANTLR classes are gitignored but on deps.edn :paths — link them from the master clone
for l in python3 php typescript; do
  mkdir -p /tmp/<name>/build/antlr/$l
  ln -s ~/ci/datomworld-nightly/build/antlr/$l/classes /tmp/<name>/build/antlr/$l/classes
done
```

Without those links the JVM ns load fails: `yin.repl.frontends` requires `yang.python.antlr.parser`
and `yang.php.antlr.cst` under `#?@(:default …)`. Remove afterwards with
`git -C ~/ci/datomworld-nightly worktree remove --force /tmp/<name>` (or leave it and hand the
command back so QA can re-run).

## Which lane hosts a given test

`shadow-cljs.edn`'s `:test` build is `:node-test` with **no** `:ns-regex`/`:entries` filter, and
`test/` is in the `:cljs` alias's `:extra-paths` → **every `.cljc` test compiles into
`target/node-tests.js` and runs on both lanes**. Don't guess from the brief ("if it is JVM-only…"):
confirm by running `node target/node-tests.js --test=<ns>` and reading the summary line. Tests that
are JVM-only are the `.clj` ones; `#?(:cljd …)` conditionals do not exclude a ns from node.

## Long runs: detach, log, poll

`ssh host '… nohup … &'` **hangs the tool call** until its timeout (the channel stays open);
`run_shell_command` also **blocks `sleep 90`-style waits**. Pattern that works:

```bash
# start (per-ns logs + one runs.log with exit codes)
ssh -n shiva.stigmergy.systems 'cd ~/ci/datomworld-nightly-node && nohup bash -lc "
  export PATH=\$HOME/.local/bin:\$PATH; cd ~/ci/datomworld-nightly-node; eval \"\$(mise env -s bash)\"
  for t in <ns1> <ns2>; do
    echo \"=== RUN \$t ===\" >> /tmp/runs.log
    DATOM_SLOW_TESTS=1 node target/node-tests.js --test=\$t > /tmp/\$(echo \$t | tr . -).log 2>&1
    echo \"\$t EXIT=\$?\" >> /tmp/runs.log
  done; echo ALL_DONE >> /tmp/runs.log" > /tmp/runs.err 2>&1 &'
# poll with short, non-hanging calls
ssh -n shiva.stigmergy.systems 'cat /tmp/runs.log; tail -5 /tmp/*.log'
```

Use `ssh -n` for every poll. Slow tiers take many minutes (`yin.repl-test` ≈ 10 min on node).

## Reading a result

Test-runner summaries are the evidence — capture them verbatim, plus the `EXIT=` line:

```
Testing yang.clojure.stream-eval-test

Ran 13 tests containing 309 assertions.
0 failures, 0 errors.
```

Compare test/assertion **counts** against the pre-fix reproduction: identical counts with
`1 failures` → `0 failures` proves the same test set ran and the named var went green, which is
what "no additional normalization" acceptance requires. A dropped count means tests silently
stopped running (ns filter, compile exclusion), not that they passed.
