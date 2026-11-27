Created-GMT: 2026-11-27 07:04:50 GMT
Created-Local: 2026-11-27 14:04:50 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (capture conversation_id from the JSON output)
# Task: root-cause and fix the `unit` gate hang on jolt fork main (row 2446)

Role: VM Runtime Engineer (diagnosis and fix)
Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-27 14:04:50 +0700 | Status: active | Rationale: the owner directed "collaborate with agy to fix this"; complex runtime debugging

You are working WITH the orchestrator, not for it. The orchestrator (the
author of this brief) is running two independent experiments in parallel and
will cross-check your conclusion with gate runs of its own. You each bring
evidence. Say plainly what you measured and what you are inferring.

--------------------------------------------------------------------------------
## 1. What is broken
--------------------------------------------------------------------------------

The jolt fork `sonwh98/jolt` (a fork of `jolt-lang/jolt`) has a red `tests`
workflow on `main` (`e457adc9`) after an upstream sync on 2026-11-26. The
`make unit` gate never finishes: it hangs on one row of
`test/chez/unit.edn`. Before the sync, fork `main` (`a6852ef8`) passed every
shard. Upstream `main` at the exact commit that was synced
(`172654976906b42eb7bfab65edd62b96562fb4bc`) is green on the same CI.

The job: find the real root cause with evidence, and fix it so that all of
these hold together: the gate passes, the upstream code stays as synced, and
the fork's own behavior change stays intact.

--------------------------------------------------------------------------------
## 2. What you are given
--------------------------------------------------------------------------------

- YOUR worktree: `/Users/sto/workspace/jolt-syncfix`. Branch
  `fix/sync-restore-tree-sorted-dispatch`, based on fork `main` `e457adc9`.
  It currently holds two uncommitted changes by the orchestrator (section 4).
  Edit only here. Build artifacts may go under its `build/` directory.
- Reference, read-only: `/Users/sto/workspace/jolt-upstream-ref`, a checkout
  of pure upstream at `17265497` with its shipped seeds.
- Do not touch: `/Users/sto/workspace/jolt-bisect` (the orchestrator's
  experiment tree), `/Users/sto/workspace/jolt` (the shared clone),
  `/Users/sto/workspace/jolt-ffi-*`, and anything under
  `/Users/sto/workspace/datomworld` except the findings file in section 8.
- Chez 10.4.1, the mise install:
  `/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme`.
  Never a `scheme` from `PATH`.
- Gambit, for the seed freshness checks:
  `GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8`.

Useful commands (run from your worktree):

    export JOLT_CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme

    # The failing gate. Reproduces in about 4.5 minutes: the per-row watchdog
    # (120 s, override with JOLT_UNIT_ROW_TIMEOUT) names the row and exits 2.
    make CHEZ="$JOLT_CHEZ" unit

    # After ANY edit under jolt-core/ or the clojure.core overlay: re-mint
    # (about 45 s), or the gate loads a stale seed and lies to you.
    sh host/chez/remint.sh

    # The fork's whole source difference from upstream at this commit (three
    # files, plus seeds and ci/metrics-allow.edn):
    git -C /Users/sto/workspace/jolt diff 172654976906b42eb7bfab65edd62b96562fb4bc e457adc9 -- jolt-core test/chez/sorted-test.ss

    # The fork's own change (the thing that must keep working):
    git -C /Users/sto/workspace/jolt show 46d4090b

    # The upstream commit that introduced the hanging row:
    git -C /Users/sto/workspace/jolt show 8faa785f --stat

    # The gate that protects the fork's change. Expect 156/156 on a good tree.
    "$JOLT_CHEZ" --script test/chez/sorted-test.ss

    # Freshness and structural gates that must stay green after a re-mint:
    make CHEZ="$JOLT_CHEZ" GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8 gambitseedcheck gambitgencheck emittedheads deadhost

--------------------------------------------------------------------------------
## 3. Evidence established so far. Each item says how it was obtained.
--------------------------------------------------------------------------------

E1. On fork `main` `e457adc9`, `make unit` fails deterministically on
    darwin-arm64 after about 285 s:

        unit gate: row 2446 did not finish within 120s — hung?
          (do (defn uis4-chk [x] [(instance? clojure.lang.IPersistentMap x) (instance? java.util.Map x) (instance? Object x)]) [(uis4-chk {}) (uis4-chk {}) (uis4-chk nil) (uis4-chk [])])

    Reproduced twice by the orchestrator with this exact outcome.

E2. In CI on the same commit the same gate does not finish on either Linux
    runner: `test (4/4)` received a runner shutdown at 94 minutes with `unit`
    still running, and `Android (bionic 2/3)` ended with
    `make[1]: *** [Makefile:497: unit] Killed`.

E3. The hanging row evaluated alone in a fresh process returns instantly and
    correctly: `[[true true true] [true true true] [false false false] [false
    false true]]`. Each of its parts too. So the hang depends on state left by
    earlier rows. The probe script is in section 5. (Correction made after
    dispatch: the first version of this brief printed the last triple as
    `[true true true]`, which the orchestrator filled in from a truncated
    output. The value above is the measured one, from the real harness setup
    with no earlier rows.)

E4. Row 2446 is not new. It is present in old fork `main` `a6852ef8` (CI
    green on 2026-11-19: all four `test` shards and all Android shards
    passed) and in upstream. Upstream commit `8faa785f` ("Stop resolving
    types and methods by name on every call") introduced it.

E5. The fork's only non-seed source difference from upstream `17265497` is
    three files: `jolt-core/clojure/core/20-coll.clj` (1 line),
    `jolt-core/clojure/core/25-sorted.clj` (22 lines),
    `test/chez/sorted-test.ss` (2 lines). They are the fork's commit
    `46d4090b`: `sorted?` becomes
    `(or (tree-sorted? x) (instance-check clojure.lang.Sorted x))` so that
    user types implementing `clojure.lang.Sorted` count as sorted in
    `sorted?`, `subseq` and `rsubseq`. The seeds (`host/chez/seed/*`,
    `host/gambit/seed/*`) were re-minted from the merged sources by the
    orchestrator during the sync.

E6. Rows 1 to 2445 pass before the hang. The run prints the expected noisy
    output of earlier rows and then sits on one core.

From E4 and E5: the hang is caused by the interaction of the fork's Sorted
change with code upstream added between `a6852ef8` and `17265497` (647
commits), or by the re-minted seeds. It is not caused by the upstream code
alone and not by the fork's change alone.

--------------------------------------------------------------------------------
## 4. Already ruled out
--------------------------------------------------------------------------------

The sync had resolved a conflict in `jolt-core/clojure/core/22-coll.clj` by
taking the whole file from upstream, which reverted two edits of the fork's
commit (it had replaced `sorted?` with the cheap `tree-sorted?` in
`find-other` and in `empty`). The orchestrator suspected that and restored
both lines in YOUR worktree, then re-minted. `make unit` still hung at row
2446, identical output. So those two lines are not the cause.

They are still in your worktree, uncommitted, with the re-minted seeds. They
are the fork's own lines and were restored for that reason. Keep them unless
you find a reason not to, and say so if you do.

--------------------------------------------------------------------------------
## 5. The isolated-row probe the orchestrator used
--------------------------------------------------------------------------------

Save as `/tmp/jolt-probe-row.scm`, run from a worktree root with
`"$JOLT_CHEZ" --script /tmp/jolt-probe-row.scm '<expr>'`. It mirrors the
gate's own setup (`host/chez/run-unit.ss`) and has a 25 s watchdog. It does
NOT run `run-case-isolation.ss`, so it has no `zj-reset!` and no earlier rows.

    (import (chezscheme))
    (load "host/chez/run-gate-harness.ss")
    (load "host/chez/loader.ss")
    (load "host/chez/java/ffi.ss")
    (set-source-roots! ldr-install-roots)
    (load-namespace "jolt.time.base")
    (load "host/chez/gate-private-tmpdir.ss")
    (define src (list-ref (command-line) 1))
    (fork-thread (lambda () (sleep 25) (printf "HANG (>25s): ~a\n" src) (flush-output-port) (exit 3)))
    (printf "OK: ~a => ~a\n" src (jolt-repl-str (jolt-compile-eval (string-append "(do " src ")") "user")))
    (flush-output-port)
    (exit 0)

--------------------------------------------------------------------------------
## 6. Hypotheses. These are guesses to test, not findings.
--------------------------------------------------------------------------------

H1. Upstream's newer type and method resolution (site caches, inline caches;
    see commit `8faa785f` and what came after it) has state that degenerates
    when one `instance-check` site sees too many kinds of value. The fork's
    `sorted?` performs `(instance-check clojure.lang.Sorted x)` on every kind
    of value, from many call sites, where upstream's `sorted?` never calls
    `instance-check` at all. A fix in this story: only run the instance check
    for values that can be a user-defined type at all (a jolt record, deftype
    or reify instance), so built-in values never reach that site.
H2. Something in the re-minted seeds differs from what a correct mint of
    this tree produces (an environment-dependent mint, or a fixpoint not
    reached). Test by comparing against a mint in the reference tree.
H3. An infinite recursion between the fork's `sorted?` and some new upstream
    dispatch path that only closes after a particular earlier row has
    installed state (a class registration, a var, an arm).

The orchestrator is separately (a) running `make unit` on pure upstream
locally as a baseline and (b) bisecting which earlier rows are needed for row
2446 to hang. Do not duplicate those two.

--------------------------------------------------------------------------------
## 7. Constraints
--------------------------------------------------------------------------------

Must:
- Preserve the fork's behavior: `sorted?`, `subseq` and `rsubseq` honor
  `clojure.lang.Sorted` for user types. That is the purpose of the fork's
  commits. Find the rows that protect it before you touch `25-sorted.clj`.
- Keep the upstream code as synced. The fix belongs where the defect is.
- Reproduce a failure with the failing thing only. Do not run the fork's
  whole `make ci` or `make test`.
- Every command in the foreground with an explicit timeout. Leave no process
  running. If something hangs, find its PID and kill that PID.
- After any edit under `jolt-core/` or the overlay, re-mint before you
  trust a gate.

Must not:
- Delete, weaken, skip or in any way edit the fork's unit tests. The owner's
  words: "you have permission to change my fork as long as the unit tests in
  my fork are invariant". The tests are the fixed point. The fix goes in
  non-test code only. The orchestrator will reject any change to a file under
  `test/`. Row 2446 stays exactly as it is, and the row watchdog stays.
- Revert the sync, reset `main`, or rewrite history.
- Commit or push. The orchestrator verifies your change with its own gate
  runs and then commits.
- Edit any file outside your worktree.

--------------------------------------------------------------------------------
## 8. Deliverable
--------------------------------------------------------------------------------

1. The fix, as uncommitted edits in `/Users/sto/workspace/jolt-syncfix`, with
   re-minted seeds if the edit requires them.
2. A findings file. Preferred path:
   `/Users/sto/workspace/datomworld/collab/jolt/1795763090696-engineer-fork-main-unit-hang.gemini-4-argon-high.findings.md`.
   If the sandbox refuses that path, write
   `/Users/sto/workspace/jolt-syncfix/build/hang-findings.md` and say so in
   your final message.

   The findings start with this brief's header fields and contain:
   - Root cause, with file and line evidence and the mechanism explained so
     someone can follow it. State which hypothesis it was, or that it was
     none of them.
   - The smallest reproduction you achieved, with the exact command and
     verbatim output.
   - What you changed and why it is the right place. Alternatives you
     rejected, and why.
   - Verification you ran: the exact commands and their outcomes. At
     minimum `make unit` passing in full (the gate prints
     `unit gate: <n>/<n> passed`), `sorted-test.ss` at 156/156, and the four
     freshness and structural gates green. Say explicitly which of these you
     did not run.
   - Residual risks, and whether the fix could affect the Gambit host
     (`host/gambit/`) or anything else that shares the changed code.
   - Whether the two restored `tree-sorted?` lines should stay.

Your final message is the same content in brief, ending with
`ROOT-CAUSE-CONFIRMED` or `ROOT-CAUSE-UNCERTAIN` and then `FIX-VERIFIED` or
`FIX-UNVERIFIED`. Do not claim a label your output does not support.
