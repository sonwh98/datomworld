Created-GMT: 2026-11-07 12:5x GMT
Created-Local: 2026-11-07 19:5x +07
Coding-Agent: qwen
Session-ID: 93969a91-0eaf-4ea2-bb9a-f819aa55d0ab

# Task: complete fix 2 — the V8 stack sizing fails on the Linux hosts (recursion-error-test red)

Role: DaoStream and Distributed Protocol Engineer (this unit is V8/CLJS runtime
sizing — scoped to the recursion red ONLY; the DHT boundary refactor is under a
separate architect ruling; do NOT touch yin/repl/dht.cljc, dao.space, or any
frame-size/budget code)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-07 19:5x +07 | Status: active | Rationale: continuation of the master-green engineering lane; boundary-neutral unit

## Verified failure (orchestrator evidence)

Branch nightly-reds-fix @ 4f772d61, node on shiva+jupiter
(/tmp/nrf-fixtests.log): FAIL in (recursion-error-test)
(yang/python/antlr/safepoint_test.cljc:156:9) — expected [true false
{:py/out ["0" "rec" "1000"], :py/exception nil}] (f(999) completes inside the
guest's 1000-frame guard), actual [:thrown "Maximum call stack size exceeded"]
— V8 still exhausts its JS stack BEFORE the guest guard fires, despite fix 2
(213501ec5... commit 0053411a) re-execing node with --stack-size=6144. The
prepend works (verified: child processes run with the flag); 6 MiB is simply
insufficient on Linux for this recursion depth. The same test passes on the
engineer laptop (macOS, larger default stacks) — do not assume the laptop's
behavior transfers.

## Your job

1. MEASURE, do not guess: on shiva, determine the stack depth actually required
   for f(999) + the guest machinery. Method suggestion: a scratch script in the
   clone (never the repo) that recurses to increasing depths under controlled
   --stack-size values to find the threshold; or instrument the failing test
   run. Record the measured requirement.
2. Fix fix 2's constant in shadow-cljs.edn's :prepend on branch
   nightly-reds-fix: raise --stack-size to the measured requirement plus headroom,
   staying SAFELY under the Linux native thread stack (8 MiB default; consult
   8176 on shiva — verify, do not assume) and note the macOS parity case
   (the laptop must still pass; its runtime defaults differ).
3. If 6 MiB was failing because of something OTHER than size (e.g. the flag
   units — V8 --stack-size is in KB — or the re-exec dropping the flag on some
   path), fix that instead; the prepend's guard
   (process.execArgv.includes('--stack-size=6144')) must be updated to match any
   new value so the re-exec terminates.
4. Verify (scoped ruling; individual tests only):
   ssh shiva.stigmergy.systems; cd ~/ci/datomworld-nightly-node (at 4f772d61);
   apply your diff (scp + git apply), eval "$(mise env -s bash)",
   recompile: clj -M:cljs -m shadow.cljs.devtools.cli compile test
   --config-merge "{:autorun false}",
   then DATOM_SLOW_TESTS=1 node target/node-tests.js
   --test=yang.python.antlr.safepoint-test/recursion-error-test → green,
   AND --test=yang.python.antlr.safepoint-test/generator-rebase-test → green
   (same family), AND --test=yang.python.antlr.linked-safepoint-test → green.
   Also confirm the linked-prelude selector still passes (it ran green before;
   your stack change must not regress it).
   Cross-check jupiter once for the same selectors.
5. Commit nothing; leave the diff in the working tree of your worktree
   /tmp/nrf-branch-wt (currently detached at cb2cbe11 — leave it; apply your
   shadow-cljs.edn change as a diff for the orchestrator to carry). Report
   git diff --stat.

Constraints: work only in shadow-cljs.edn (+ scratch scripts outside the repo
tree, e.g. /tmp). Do not touch src/, test/, dao.*, yin/*. If the measured
requirement exceeds what Linux's 8 MiB native stack can host, STOP and report
— that would mean the guest guard depth itself must change, which is an
architect decision, not yours.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: the measured stack requirement and method, the new constant and
headroom math, exact verification outputs verbatim, and any incomplete work.
Do not claim runs that did not occur.
