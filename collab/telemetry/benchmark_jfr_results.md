# JFR Benchmark: v1 vs v2 Semantic VM

Status: **BLOCKED — no benchmark was run; no metrics collected.**

## Why

This session was non-interactive, and every command that runs code needed an
approval nobody could give:

- `../datomworld-master` is outside the session's allowed directory, so it can't be read or `cd`'d into.
- Every `clojure -J…` / `java` command was denied.
- `git worktree add --detach target/v1-master master` and `git archive master` were both denied. These were attempts to get the v1 tree inside the repo.

Allowed: `git show master:<path>`, `git grep`, `clj -M:test …`. I chose not to route
the benchmarks through the `-M:test` prefix with a custom alias, because that would get
around the approval rule.

## What was found

| | v1 (`master` @ b3a4be7) | v2 (`dao.stream-redesign-v2` @ 1af3b73) |
|---|---|---|
| Bench | `src/clj/yin/vm/bytecode_bench.clj` (ns `yin.vm.bytecode-bench`) | `test/bench/yin_vm_bench.cljc` (ns `bench.yin-vm-bench`) |
| Workload | tail-recursive countdown, default n=1000 | same AST, n from args |
| Timed region | `vm/run` on a queued VM | `vm/run` on a loaded VM |
| Semantic VM | `yin.vm.semantic`, `--semantic-only` | `yin.vm.v2.semantic` (bench also times the v2 AST walker) |

Both benches use the same workload, so their Criterium means can be compared directly.

## Commands to run once approved

Use the same n (1000) for both. Run them one after the other, not in parallel.

```sh
# v1 — semantic VM (fast path) + AST walker for reference
cd /Users/sto/workspace/datomworld-master && \
clojure -J-XX:StartFlightRecording=filename=/Users/sto/workspace/datomworld/profiles/v1.jfr,settings=profile,dumponexit=true \
  -M:bench --fast-only --semantic-only 1000

# v2 — semantic VM + AST walker
cd /Users/sto/workspace/datomworld && \
clojure -J-XX:StartFlightRecording=filename=/Users/sto/workspace/datomworld/profiles/v2.jfr,settings=profile,dumponexit=true \
  -Sdeps '{:aliases {:bench {:extra-paths ["test"]}}}' \
  -M:bench -m bench.yin-vm-bench 1000
```

Notes:
- v1's `:bench` alias already sets `-m yin.vm.bytecode-bench`.
- `dumponexit=true` without `duration` records the whole run. A `duration=120s` cap could cut off v1 if it runs longer than that.

## Results

| VM | v1 mean (ms/run) | v2 mean (ms/run) | v2/v1 |
|---|---|---|---|
| Semantic | — | — | — |
| AST Walker | — | — | — |
