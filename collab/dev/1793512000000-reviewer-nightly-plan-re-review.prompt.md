Reuse your session and re-review the previously-REVISE nightly plan (your
artifact: collab/1793466815000-reviewer-nightly-testing-plan.gpt-6.1-sol.findings.md,
verdict REVISE with blockers R1/R3/R4/R6). The implementation has changed since;
review the FIXES against your own findings. Do NOT re-review the plan abstractly;
verdict each "Required:" clause as CLOSED or STILL-OPEN with evidence.

Changed artifacts (all on master; repo: sonwh98/datomworld):
- src/dev/node_shard.clj (replaces the shell node-shard.sh you reviewed):
  one shadow compile (:autorun false); one `node --test` process per subsystem
  in parallel waves (PAR=6); validates every requested namespace against the
  compiled bundle's own --list inventory BEFORE running (unknown -> FAIL + rc=1);
  builds npm-ci through `mise exec`; both peers built; per-run immutable out
  dir node-shards-$STAMP with -latest symlink; expected-vs-completed shard
  check; nonzero exit on red/missing; `:extra-env` PATH (mise shims) for
  children — note a subtlety your review missed the first time: babashka's
  `:env` REPLACES the child env entirely (PATH-only killed HOME and every
  child failed ENOENT); the drivers use `:extra-env`. File redirection uses
  io/writer, never `:err :append` (not a babashka option; it corrupted the
  exec — see commit a601f1441/815b78233/f6d16921/4d9a84a80 and messages).
- src/dev/dart_shard.clj: one full cljd compile; per-subsystem shard dirs
  emitted via cljd_agg.clj --only/--shard-dir/--no-compile/--no-run; the
  UNCOVERED residual is emitted and run; ONE flutter invocation over every
  emitted directory (--concurrency 8) so the shared build/test_cache kernel
  is touched by exactly one process; emission failures fatal; same out-dir /
  exit discipline.
- Host wrappers (~/ci/nightly-full.sh on shiva, ~/ci/nightly-jvm.sh on
  jupiter, deployed from this change): shiva publishes {run-id, sha,
  timestamp} atomically (tmp+mv) with a Vietnam-date run-id; jupiter accepts
  ONLY a same-Vietnam-date run-id (refuses stale pins — your R4 race), verifies
  the object locally with one refetch retry, falls back to "unpinned
  (advisory)" with RC=1 otherwise; both wrappers exit nonzero on any red;
  jupiter's nightly now ALSO runs the full JVM slow tier (one JVM per
  ^:slow namespace, tree-derived list) plus the quick sharded tier; summaries
  are copied to timestamped files and pushed to the peer host (ssh/scp with
  ConnectTimeout=10, mkdir -p first).
- src/dev/cljd_agg.clj: --shard-dir/--no-compile/--no-run flags (write-shards!
  parameterized over shard-dir; default behavior unchanged).

Empirical evidence since your review:
1. The Node validation ran (v2 scripts): 10 of 14 shards green before an
   intentional kill — dao.stream 528 tests in 66 s, yin.vm.ucf 608 in 625 s,
   dao.space 485/40,494 assertions in 744 s. The remaining 4 shards were still
   running when killed. NOT a full validation yet.
2. The failure cluster you did NOT see: the sub:list header is
   `cljs: N namespaces` (with count) — the old awk parse for the header line
   was right, but the bb parser's take-while dropped the indented block, so
   every subsystem parsed empty and ALL 274 namespaces fell to the residual
   (run produced zero shards, exit rc=1 via the expected-vs-completed check
   doing its job). Fixed by parser state machine (skip header, take indented
   block); proven locally (yang.base -> 6 namespaces: exact).
3. The afternoon's "silent process deaths" on shiva were NOT an external
   reaper: my launcher shells were killed by their own pkill patterns, and the
   bb runs died from the option/env bugs above. dmesg/journal showed no OOM.

Explicitly check:
A. R1 residual (dart) — now present: verify from source.
B. R1 Node unknown-namespace validation — is the bundle's --list output the
   authoritative inventory, and is the pre-run check airtight?
C. R3 Flutter cache isolation — is one invocation over N dirs really
   single-writer for build/test_cache?
D. R4 pin protocol — stale-pin refusal, atomic publication, fallback
   labeling; new races?
E. R6 — per-run dirs, retention, expected-vs-completed, exit codes,
   timestamps (ts was shell-only; the bb drivers timestamp summary lines via
   the stamp, which you should evaluate against your own requirement).
F. Anything NEW the fixes introduced (e.g. mise-exec npm resolution,
   :extra-env semantics, host-scoped wrappers).

Verdict format: per "Required:"-clause CLOSED or STILL-OPEN with file/line
evidence, then OVERALL verdict for wiring into the nightly: READY /
READY-WITH-CONCERNS (name them) / STILL-REVISE. Reply via stdout; the
orchestrator promotes it.
