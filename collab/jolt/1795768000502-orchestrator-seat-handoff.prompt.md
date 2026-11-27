Created-GMT: 2026-11-27 08:26:40 GMT
Created-Local: 2026-11-27 15:26:40 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode seat, glm-5.3-flash; the Gambit host-lane orchestrator seat)
Session-ID: not-applicable (interactive seat)

# Task: take over the yin.repl-jolt host-lane seat

Role: Lead Engineering Orchestrator (a NEW seat; the Gambit seat hands off everything below)
Implementers:
- Model: assigned by whoever launches you (any orchestrator-capable model)
  | Assigned: <your launch time> | Status: active | Rationale: the outgoing seat ran low on credits

Coordinate in `/Users/sto/workspace/datomworld` (the private datom.world repo, main
checkout on `master`) and in the owner's jolt fork `sonwh98/jolt` (clone at
`/Users/sto/workspace/jolt`, remote `origin` is the fork, remote `upstream` is
`jolt-lang/jolt`; always pass `--repo sonwh98/jolt` to `gh`).

The seat is being handed off because it ran low on credits, not because the
work finished. Everything below is something the outgoing seat believed at
handoff time. The tree is canonical: re-derive state from `git log`,
`git status` and the real diff before acting on any of it.

Every claim in this brief, in the session logs, in `routing-status.md` and in
the referenced findings is to be verified, not trusted. Where this brief says
"verified", the outgoing seat ran the command itself; you did not.

--------------------------------------------------------------------------------
## 1. The owner's words that bind you
--------------------------------------------------------------------------------

Verbatim, most recent last. They are the authority for everything below.

- 2026-11-26: "yin.repl should be consistently feature-wise on all supported
  host. if it is not, then let me know".
- 2026-11-27: "write a instructions for another orchestrator to handle the
  Guile port. you're instructions should be detailed enough so that the
  orchestrator does not need my involvement. guile dependencies should use
  mise".
- 2026-11-27, about the red CI on fork `main`: "collaborate with agy to fix
  this".
- 2026-11-27: "you have permission to change my fork as long as the unit tests
  in my fork are invariant".

Consequences:
- For the Guile track, do not ask the owner anything. The decisions are made
  in advance in `collab/jolt/1795762587351-orchestrator-guile-port-handoff.prompt.md`
  (section 6 there), with decision rules for uncovered cases (section 6.13)
  and hard stops that still apply (section 7.3).
- For the fork: you may commit and push to `sonwh98/jolt`, `main` included.
  The fork's unit tests are the fixed point. The fix goes in non-test code
  only. The outgoing seat's check: `git status --short | grep -c ' test/'`
  prints 0.
- Standing instructions from earlier in the session: the owner treats
  uncommitted `collab/` files as a defect ("you have collab files that are
  not committed"); worktrees and merged branches get cleaned up; delegates
  that loop get rerouted to codex or agy; `docs/agents/build-n-test.md` governs
  how tests are run.

--------------------------------------------------------------------------------
## 2. First job: the fix for the red CI on fork `main` (landed)
--------------------------------------------------------------------------------

STATUS, 2026-11-27 09:08 GMT: landed. `origin/main` is `00cb20b9`, a
fast-forward from `e457adc9`, pushed by the outgoing seat at 09:08:09 GMT.
The fix branch's CI run on the same commit (`45965692509`) passed all four
`test` shards and all three Android shards, plus clj-kondo, the macOS build
and the Windows dependency job. `main`'s own run is `45971171442`; its
result is under "What is NOT done" below. What is left of this job is step 4
(read `main`'s CI) and step 5 (record it).

This was the owner's live problem. Close it before starting the Guile track.

### What is wrong

After the 2026-11-26 upstream sync, fork `main` (`e457adc9`) fails its `tests`
workflow: `make unit` hangs forever on one row of `test/chez/unit.edn`.
Before the sync every shard passed. Upstream at the synced commit
(`17265497`) passes.

The cause, found by the outgoing seat (a two-row reproduction) and by agy (a
code trace). The fork's own commit `46d4090b` defines `sorted?` as
`(or (tree-sorted? x) (instance-check clojure.lang.Sorted x))`. Upstream
commit `2573c180`, which arrived with the sync, changed gate row 2442 so that
it registers a library class arm whose predicate is
`(fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false))`.
After that row, an `instance?` on a map walks the arm registry, the arm calls
`sorted?`, and the fork's `sorted?` calls `instance-check` again. Unbounded
mutual recursion. Row 2446 (`uis4-chk`) is the first to pass a map. Upstream
has no cycle because its `sorted?` never calls `instance-check`.

### The fix, already committed

Branch `fix/sync-restore-tree-sorted-dispatch` on the fork, commit `00cb20b9`,
parent `e457adc9`. Landing it on `main` is a fast-forward. Local worktree:
`/Users/sto/workspace/jolt-syncfix`.

- `jolt-core/clojure/core/25-sorted.clj`: `sorted?` now asks `instance-check`
  only for a value whose own type has `Sorted`'s `seqFrom` method:

      (defn sorted? [x]
        (or (tree-sorted? x)
            (and (jolt.host/jrec-method? x "seqFrom")
                 (instance-check clojure.lang.Sorted x))))

  The idiom is the one `empty` and `ifn?` already use (`22-coll.clj` lines
  185 and 245). Author: agy `gemini-4-argon-high`.
- `jolt-core/clojure/core/22-coll.clj`: two lines restored from the fork's own
  commit, which the sync's conflict resolution had dropped
  (`tree-sorted?` in `find-other` and in `empty`).
- Seeds re-minted (`host/chez/seed/*`, `host/gambit/seed/*`).
- No file under `test/` is touched.

### What the outgoing seat verified itself, on the tree that became `00cb20b9`

- `make unit`: `unit gate: 2828/2828 passed`, 175 s. Pure upstream at the
  synced commit: `2828/2828` in 192 s. Unfixed fork `main`: hangs at row 2446.
- `test/chez/sorted-test.ss`: `156/156 passed`.
- `gambitseedcheck`, `gambitgencheck`: current. `emittedheads`: OK.
  `deadhost`: passed. `gambitcheck`, `gambitkernel`, `gambiteval`: PASS.
- A third seed mint reproduced the same bytes.
- Single-expression probes in fresh processes: built-ins right
  (`(sorted? {})` false, `(sorted? (sorted-map 1 2))` true); a deftype
  implementing `Sorted` true; a reify implementing `Sorted` true; a plain
  defrecord false; the two-row reproduction returns `true` with no hang; an
  arm whose predicate calls `sorted?` followed by `instance?` on a defrecord
  returns normally.
- The cross-family reviewer re-ran `make unit` twice on the same bytes:
  `2828/2828` (152 s and 161 s). It did not run `sorted-test.ss`.
- Beyond darwin-arm64: the fix branch's CI run `45965692509` passed on Linux
  (all four `test` shards; `test (4/4)` took 40 minutes, where the unfixed
  `main` was shut down at 94) and on Android (all three shards;
  `Android (bionic 2/3)`, killed on the unfixed `main`, took 15 minutes).
  clj-kondo, the macOS build and the Windows dependency job passed too.
  Every job in that run:

      gh run view 45965692509 --repo sonwh98/jolt --json jobs --jq '.jobs[] | "\(.conclusion)\t\(.name)"'

### The cross-family review: done, ACCEPT-WITH-NOTES

Report, verbatim:
`collab/jolt/1795765315502-reviewer-fork-sorted-fix-glm.findings.md`
(glm-5.3-flash subagent, 2026-11-27 08:30 GMT, on the bytes of `00cb20b9`).
Its conclusion is to land the commit and close these four notes by
follow-up. Only note 2 changes what the fork answered before the fix.

1. A hang of the same kind remains for one type shape: a deftype, defrecord
   or reify that has a `seqFrom` method but does not declare
   `clojure.lang.Sorted`, asked through a registered arm whose predicate or
   check calls `sorted?`. The reviewer reproduced it six ways, and found no
   such type in the repo or in the local library sources. In its probes,
   values without `seqFrom`, types that declare `Sorted` and have
   `seqFrom`, and proxies did not hang. Suggested close: keep `sorted?` out
   of the arm walk with a host predicate that asks whether the type
   declares `Sorted` (what `own-type-declares?` computes,
   `host/chez/java/records-interop.ss` lines 116 to 130, plus a proxy's
   declared supers), or at minimum a re-entrancy flag. The declaration-based
   check would also close note 2. It is a new host primitive on two hosts:
   one engineer round and one cross-family review.
2. The JVM gap described below is wider than the outgoing seat told the
   owner. The comment at `jolt-core/clojure/core/25-sorted.clj` lines 219
   to 224 still says "Any deftype/reify declaring Sorted qualifies", which
   the guard made untrue. Correct it in the same round. Keep the line count
   if you want the seeds to stay byte-identical.
3. `reversible?` is `(or (vector? x) (sorted? x))` since the fork's own
   `46d4090b`. The JVM answers `instance? Reversible`. For a user type that
   is `Sorted` but not `Reversible`, jolt says true and the JVM says false,
   and `(rseq x)` then throws `ClassCastException`. Older than this fix.
4. The isolated-row probe script in the agy brief (its section 5) has a
   watchdog that never fires. The reviewer measured that `(sleep 25)`
   raises on this Chez, because `sleep` wants a time record, and that
   `(exit 3)` from a non-primordial thread is ignored while the main
   thread is busy. The script's "returns normally" results stand, because
   those processes did return. Do not reuse it for a hang. Copy the gate's
   watchdog, `host/chez/run-unit.ss` lines 71 to 84.

### What is NOT done

- The CI result on `main`. Run `45971171442` on `00cb20b9` was queued at
  2026-11-27 09:08 GMT and had not been read when this was written. Read it
  (step 4). It is the same commit as the green branch run `45965692509`. If
  it is red, suspect a flake or an infrastructure job first, and read the
  log before concluding anything.

Nothing else. The landing is done: `origin/main` moved from `e457adc9` to
`00cb20b9` at 2026-11-27 09:08:09 GMT, after the branch's CI had passed its
`test` and Android shards.

### Steps

Steps 1 to 3 are done; they stay as the record of how. Do steps 4 and 5.

1. Re-verify, about 5 minutes:

       export JOLT_CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme
       cd /Users/sto/workspace/jolt-syncfix
       git log --oneline -2                      # expect 00cb20b9 on e457adc9
       git status --short                        # expect clean
       make CHEZ="$JOLT_CHEZ" unit              # expect: unit gate: 2828/2828 passed

2. Check that `main` has not moved:

       git -C /Users/sto/workspace/jolt fetch origin
       git -C /Users/sto/workspace/jolt rev-parse --short origin/main   # expect e457adc9

   If it has moved, rebase the fix onto the new `main` and re-run step 1.
3. Land it:

       cd /Users/sto/workspace/jolt && git checkout main && git merge --ff-only origin/main
       git merge --ff-only fix/sync-restore-tree-sorted-dispatch
       git push origin HEAD:main
       git ls-remote origin refs/heads/main     # expect 00cb20b9

4. Watch CI. It takes 1 to 2 hours:

       gh run list --repo sonwh98/jolt --branch main --workflow tests --limit 3
       gh run view <id> --repo sonwh98/jolt --json jobs --jq '.jobs[] | "\(.conclusion)\t\(.name)"'

   The `test` shards and the Android shards must pass. Two jobs fail for
   infrastructure reasons and are not yours: the macOS job sometimes never
   starts (hosted-runner capacity) and the Windows job sometimes dies in an
   MSYS2 package-signature step. Rerun those once. If the `tests` job is red
   for a real reason, read the log before concluding anything (see section 5).

5. Record the outcome in your session log and in a short relay to the owner.

### Known difference from the JVM, deliberate

A type that declares `clojure.lang.Sorted` and implements none of its
methods gives `(sorted? x)` false, where the JVM says true. The reviewer ran
both sides for defrecord, deftype and reify; a proxy follows from the JVM
definition of `sorted?` and was not run. Before the fix the fork answered
true there (when it did not hang). On the JVM such a type throws
`AbstractMethodError` on its first `Sorted` call, so it is a broken type
either way, and no library instance was found: data.priority-map 1.1.0 and
1.2.0 implement `seqFrom`. jolt's own `ifn?` makes the same tradeoff for
`IFn`. The outgoing seat told the owner about the defrecord case only. Tell
them the full shape. It is their call if they want it closed; review note 1
says how.

--------------------------------------------------------------------------------
## 3. Second job: the Guile port
--------------------------------------------------------------------------------

The complete, self-contained instructions are in
`collab/jolt/1795762587351-orchestrator-guile-port-handoff.prompt.md`. Read it
in full before doing anything on that track. It holds the goal and definition
of done, the verified state of both repositories, the method, the phases, the
decisions made in advance, the authority, the dispatch pitfalls, and the
reference index.

Its section 3.3 describes the bug in section 2 above. It says to read the
outgoing seat's session log for the fix commit: that commit is `00cb20b9`.

--------------------------------------------------------------------------------
## 4. Everything else that is open, in priority order
--------------------------------------------------------------------------------

After section 2, and alongside the Guile track only where the Guile brief
says so.

1. The Gambit parity lineage carries an unreviewed change. Fork branch
   `origin/gambit-ffi-p2final` at `060d0d43` merges durable Yin stores
   (`gambit-ffi-p2`, `5c302a30`) with the compiled session boot
   (`gambit-ffi-p2b`, `4edb3460`). The consolidation's merge resolution and
   five finishing fixes (written by deepseek) have had no cross-family review.
   The compiled boot itself and the memory-backed DHT slice were reviewed and
   accepted. The Guile brief makes this its phase 0.4. The gates the outgoing
   seat ran on it: `gambitdht-durable` PASS, `gambityinrepl` PASS, and the
   stage-1 matrix green, on darwin-arm64 only.
2. Fork `main` follow-up from the cross-family review. Its four notes are in
   section 2 under the review: the residual hang for a type with `seqFrom`
   but no declared `Sorted`; the untrue comment and the JVM gap in
   `sorted?`; `reversible?` differing from the JVM; the probe watchdog.
   None blocks the landing. One engineer round on a branch off the landed
   `main`, then a cross-family review. The owner's constraint still holds:
   no file under `test/`, so the gap cannot be written into
   `test/conformance/known-divergences.edn` without the owner's word.
3. The datomworld pin is behind the work. `jolt-toolchain.edn` points at
   `25c3a8b7` (branch `gambit-ffi-p1-store-swap`), which has yin.repl on
   Gambit but not DHT sessions, durable stores or the compiled boot. The
   owner asked for the pin to be advanced and said "go"; the consolidation
   had to come first and was never finished. The Guile brief's section 6.10
   says when and how to advance it. Do not advance it before item 1 is
   reviewed.
4. Fork `main` is 86 or more commits behind upstream again. The owner asked
   once for a sync. Re-syncing is not authorized by this handoff; the Guile
   brief's section 6.2 explains why the host lineages are not based on
   `main`. If you do sync again: never resolve a whole file with `--ours` or
   `--theirs`, and run `make unit` yourself before pushing.
5. The two fork-only fixes are not upstream. The owner asked whether they are
   still needed: yes. Upstream's `sorted?` does not honor `clojure.lang.Sorted`
   for user types, and upstream lacks the fork's assoc-on-deftype fix
   (`dd605630` on the FFI lineage). Upstreaming them is the owner's decision.
6. Leftover worktrees from this seat, to remove once their branches are on
   origin and their trees are clean: `jolt-syncfix` (remove after section 2
   lands and `main`'s CI is green; then delete the remote branch with
   `git -C /Users/sto/workspace/jolt push origin --delete fix/sync-restore-tree-sorted-dispatch`),
   then `jolt-bisect` and `jolt-upstream-ref` (experiment trees, detached,
   safe to remove now). Others (`jolt-ffi-p1c`, `-p1e`, `-p1f`, `-p2`,
   `-p2b`, `-p2final`, `-allowfix`) belong to the FFI lineage; keep `-p2final`
   until the Guile brief's phase 0 is done. Check `git status` in each before
   removing any. Never remove one with uncommitted work that is not yours.

--------------------------------------------------------------------------------
## 5. How to work here, in short
--------------------------------------------------------------------------------

The full version is sections 6 to 8 of the Guile brief. The items below are
the ones that bit this seat hardest.

- Follow `docs/agents/roles/orchestrator.md`. Create your own session log at
  `orchestrator-logs/<unix-ms>-<agent>-<model>-<description>.md` and keep it
  current. The outgoing seat let its log fall eight days behind; its last
  two entries catch up, but reading the log alone is not enough to
  reconstruct that week.
- Evidence over inference. The outgoing seat told the owner twice that the
  red CI was a pre-existing upstream divergence. It was not. It had compared
  no run on upstream, and it had misread the certify oracle's informational
  `DIVERGENT n` lines as failures. Before attributing a red to anyone, run
  the same gate on the other side.
- A hang is not always where the watchdog says. The row named by a watchdog
  may hang only because of state left by earlier rows. Reproduce with a
  prefix of rows, then narrow. The driver is gone from `/tmp`, but its shape
  is simple: load what `host/chez/run-unit.ss` loads, evaluate rows 0..N with
  `jolt-compile-eval` and `zj-reset!` after each, then the target row under a
  watchdog thread started at the beginning, as the gate does. In a probe
  script, give that thread `(sleep (make-time 'time-duration 0 25))`: the
  reviewer measured that a bare `(sleep 25)` raises on this Chez, and the
  watchdog dies before it can fire.
- After any edit under `jolt-core/`, re-mint (`sh host/chez/remint.sh` with
  `JOLT_CHEZ` set) before trusting a gate.
- Background shells start without the node tree. Prefix delegate commands:

      export PATH="/Users/sto/.nvm/versions/node/v26.1.0/bin:/Users/sto/.local/bin:$PATH"

- Launch subagents with `run_in_background: true`. Put a delegate's brief
  inside its own worktree. Let the delegate create its worktree. Read a
  delegate's diff and gate output yourself.
- Commit messages: in datomworld, `docs/agents/format.md`. In the fork, a
  plain imperative subject. No AI attribution in any commit.
- `.md` tables are ASCII box tables, not pipe tables.
- Suite-scale tests run on shiva or jupiter, never on the laptop.

--------------------------------------------------------------------------------
## 6. Where things are
--------------------------------------------------------------------------------

- The outgoing seat's log (start at its last entry):
  `orchestrator-logs/1795015483992-zcode-glm-5.3-flash-gambit-ffi-orchestration.md`
- The hang: the agy brief and findings (`collab/jolt/1795763090696-*`), and
  the glm review brief and its report (`collab/jolt/1795765315502-*`)
- The Gambit lane's whole record is indexed in section 10 of the Guile brief.

--------------------------------------------------------------------------------
## Required first response
--------------------------------------------------------------------------------

Begin it exactly with:

Completed-GMT: <actual time GMT>
Completed-Local: <actual local time and zone>
Coding-Agent: <your agent and model>
Session-ID: <not-applicable (interactive seat)>

Then state which items in sections 2 and 4 you re-verified and which had
changed, the result of section 2 (landed, with the fork CI result, or blocked
and why), and what you started on the Guile track. Append the same as the
first entry of your session log.
