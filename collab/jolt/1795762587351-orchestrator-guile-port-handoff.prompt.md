Created-GMT: 2026-11-27 06:56:27 GMT
Created-Local: 2026-11-27 13:56:27 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode seat, glm-5.3-flash; the Gambit host-lane orchestrator seat)
Session-ID: not-applicable (interactive seat)
# Task: the Guile port: yin.repl on jolt using the Guile backend, with Guile managed by mise

Role: Lead Engineering Orchestrator (a NEW seat; the Gambit seat hands off the Guile track only)
Implementers:
- Model: assigned by whoever launches you (the Gambit track ran on glm-5.3-flash; any orchestrator-capable model)
  | Assigned: <your launch time> | Status: active | Rationale: owner-requested handoff

You are taking over one track: making jolt (and through it yin.repl) run on GNU
Guile as a third host Scheme, next to Chez and Gambit. You coordinate in
`/Users/sto/workspace/datomworld` (the private datom.world repo) and in the
owner's jolt fork (`git@github.com:sonwh98/jolt.git`, clone at
`/Users/sto/workspace/jolt`).

Every claim in this brief is something the Gambit seat believed when it wrote
the brief. The tree is canonical: re-derive state from `git log`, `git status`
and the real diff before acting on any of it (section 3 lists what to check).

--------------------------------------------------------------------------------
## 1. The owner's words, and what "no involvement" means
--------------------------------------------------------------------------------

Owner instructions, verbatim, in order. They are the authority for this brief.

1. 2026-11-17: "also try with guile" (asked while making yin.repl run on jolt
   with other Scheme hosts).
2. 2026-11-18: chose "Probe-only for now" in a structured question. The probe
   is delivered (section 10).
3. 2026-11-18: "gambit FFI should be well documented" (a method ruling:
   documentation is the primary evidence; source reading only where the
   documentation leaves a gap).
4. 2026-11-18: "follow rules to test", naming `docs/agents/build-n-test.md`.
5. 2026-11-18: "if you get stuck in a loop, reroute to codex or agy".
6. 2026-11-18: "use mise to manage gambit dependency" (the precedent for the
   toolchain rule in this track).
7. 2026-11-26: "yin.repl should be consistently feature-wise on all supported
   host. if it is not, then let me know".
8. 2026-11-27: "write a instructions for another orchestrator to handle the
   Guile port. you're instructions should be detailed enough so that the
   orchestrator does not need my involvement. guile dependencies should use
   mise".

What instruction 8 means in practice:

- Do not ask the owner questions during this track.
- Decisions the Gambit seat could make in advance are made in section 6, each
  with its reason. Apply them; do not re-open them.
- For a situation section 6 does not cover, use the decision rules in section
  6.13 instead of asking. Record the decision and the reasoning in your work
  log and in the final report.
- The hard stops in section 7.3 are the one exception. They are never crossed.
  When one blocks progress, stop at a slice boundary, leave the tree readable,
  write an unfinished-work entry, and put the blocker in the final report.
  The owner reads that afterwards; that is a report, not a question.
- Superseded: on 2026-11-18 the owner said "use zcode glm-5.3-flash subagents
  to implement . use codex for review". The routing board replaced that the
  next day (codex and agy are the primary coding agents). Follow
  `docs/agents/routing-status.md` as it stands when you dispatch.

--------------------------------------------------------------------------------
## 2. Goal and definition of done
--------------------------------------------------------------------------------

Goal: yin.repl runs on jolt using the Guile backend. Guile is a mise-managed
dependency at an exact pin. Feature parity with the other hosts wherever
Guile's runtime can honestly provide it. Every capability it cannot provide
refuses with a documented message, and the session survives the refusal.

All seven criteria below are required. Each is evidenced by gate output that
you read yourself, never by a delegate's summary.

- D1 Toolchain. `mise.toml` declares Guile at an exact version through a
  mechanism that works on darwin-arm64 and on linux-x86_64. `bb
  bootstrap:jolt` checks it and exports `JOLT_GUILE` in
  `build/tools/jolt-env.sh`. Evidence: a fresh `mise install` plus `bb
  bootstrap:jolt` on both platforms.
- D2 Adapter. The Guile adapter satisfies
  `host/scheme-adapter/CONTRACT.txt`. `adaptercheck` passes against it and the
  `guilecheck` gate passes.
- D3 Boot and eval. `guilekernel`, `guileeval` and `guileboot` pass: jolt
  compiles and evaluates on Guile and the full-profile boot reaches BOOT-OK.
  Generated artifacts are byte-stable (`guilegencheck`, `guileseedcheck`).
- D4 REPL. `bb yin-repl --backend jolt-guile` reaches a real `yin>` prompt and
  evaluates. A `guileyinrepl` gate asserts an ordered state machine over real
  replies (the pattern of `test/gambit/yin-repl-session.sh`) and passes on
  both platforms.
- D5 FFI and stores. Each FFI, store, DHT and subprocess capability is either
  qualified by its own gate on Guile or refuses with the documented message.
  The matrix comes from the audit in phase 2, before any adapter code.
- D6 No regression. The Chez and Gambit lanes pass their gates on the exact
  SHA you propose to pin.
- D7 Paper trail. `bb yin-repl --help` and
  `src/cljc/yin/vm/docs/yin.repl.md` describe `jolt-guile` honestly. Every gap
  has a row in `docs/agents/known-gaps.md`. Every slice has a cross-family
  review with the reviewer's own gate re-run.

The datomworld pin advances to the Guile-capable SHA only when D1 to D6 hold
on that SHA (section 6.10).

Honest sizing. The 2026-11-18 probe estimated the adapter alone: roughly 8 to
10 thousand handwritten Scheme lines, about 10 Makefile gates, 1.0 to 1.3 times
the Gambit adapter. That estimate is a floor and it is out of date in one
important way. The Gambit lane proved that an existing adapter is not a
running REPL: an 11,000-line Gambit adapter already existed, and it still took
the slices P1a to P2b to get yin.repl, FFI, DHT, durable stores and a fast
start on Gambit. For Guile none of that exists. Plan for the adapter plus a
repeat of the P1c, P1f, P2 and P2b class of work, reduced by generalizing the
Gambit machinery rather than rewriting it. This is a multi-slice track that
runs for weeks. Land each slice on its own. Never leave the tree half-applied.

--------------------------------------------------------------------------------
## 3. State as the Gambit seat verified it on 2026-11-27
--------------------------------------------------------------------------------

Re-verify each item. Several seats are active and the tree moves daily.

### 3.1 Repositories, hosts, ownership

- datomworld: `sonwh98/datomworld`. Main checkout
  `/Users/sto/workspace/datomworld`, on `master`.
- Hosts: darkstar (this laptop), `shiva.stigmergy.systems`,
  `jupiter.stigmergy.systems` (ssh as `sto`).
- jolt fork: `sonwh98/jolt`, a fork of `jolt-lang/jolt`. Clone at
  `/Users/sto/workspace/jolt`. Remote `origin` is the fork; remote `upstream`
  is `jolt-lang/jolt`. For this clone `gh` resolves runs against the upstream
  repo, so always pass `--repo sonwh98/jolt`.
- Track authority (relay of 2026-11-19,
  `collab/jolt/1795105070000-relay-addendum-gambit-slice-delegation-structure.md`):
  the yin.repl jolt track seat owns the whole yin.repl-jolt track. A host-lane
  seat owns its slice's execution (dispatches, implementation rounds,
  per-slice gates). The track seat keeps slice-table bookkeeping, the
  cross-cutting toolchain files, master-push ordering and slice acceptance.
  In practice: file a relay note in `collab/jolt/` in the same batch that
  lands a change to a cross-cutting file, and honor any objection relayed
  back. Do not wait for a reply.

### 3.2 The fork carries three divergent lineages. This is the key fact.

- (a) fork `main`, `e457adc9`. Synced with upstream `jolt-lang/jolt` on
  2026-11-26 (647 upstream commits) and it carries the fork's own two
  commits, the "Sorted pair" (`46d4090b` plus seed regen `a6852ef8`). Upstream
  has since moved about 86 commits ahead again. No host-lane feature work is
  on `main`.
- (b) The production lineage, `origin/gambit-ffi-p1-store-swap` at
  `25c3a8b7`. This is what the datomworld pin points at. It is
  `gambit-ffi-p1c` (`f22df3c7`) plus two commits by the store-swap seat.
- (c) The parity lineage, `origin/gambit-ffi-p2final` at `060d0d43`. It
  contains memory-backed DHT sessions (`gambit-ffi-p1f`), durable stores
  (`gambit-ffi-p2`), the compiled session boot (`gambit-ffi-p2b`) and their
  consolidation. It is NOT in the pin.
- (b) and (c) share the ancestor `f22df3c7` and diverge from there. A
  `git merge-tree` dry run of (c) into (b) reports exactly one conflicting
  file, `host/gambit/yin/jolt/gambit/yin.jolt` (the store-spec parser).
- Neither (b) nor (c) contains fork `main`. They sit on the pre-sync lineage.
- Upstream `main` is green on CI. Neither fork lineage has Guile wiring of any
  kind.

### 3.3 A fork-specific bug to know about: `sorted?` and the arm walk

Recorded here because it cost two days, because the Gambit seat misreported
it twice before finding it, and because the same trap is on your base
lineage.

The symptom. After the 2026-11-26 upstream sync, `make unit` on fork `main`
hung forever on one row (`uis4-chk`, gate index 2446, an `instance?` row).
Before the sync fork `main` passed every shard. Upstream at the synced commit
passes (`unit gate: 2828/2828` locally in 192 s). CI showed it as `test (4/4)`
shut down by the runner at 94 minutes and `Android (bionic 2/3)` with `unit`
killed.

The cause, confirmed two ways (a two-row reproduction by the Gambit seat,
and a code trace by agy). The fork's own commit `46d4090b` defines
`sorted?` as `(or (tree-sorted? x) (instance-check clojure.lang.Sorted x))`.
Upstream commit `2573c180`, which arrived in the sync, changed gate row 2442
so that it registers a library class arm whose predicate is
`(fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false))`.
Once that row has run, an `instance?` on a map walks the arm registry
(`instance-kind-verdict` in `host/chez/java/records-interop.ss`,
`value-host-tags` in `host/chez/java/host-static-classes.ss`), the arm calls
`sorted?`, and the fork's `sorted?` calls `instance-check` again. Unbounded
mutual recursion. Upstream's `sorted?` never calls `instance-check`, which is
why upstream has no cycle. Old fork `main` had the fork's `sorted?` and the
row, but the row's predicate did not call `sorted?` until `2573c180`.

The fix (author agy, in `jolt-core/clojure/core/25-sorted.clj`):

    (defn sorted? [x]
      (or (tree-sorted? x)
          (and (jolt.host/jrec-method? x "seqFrom")
               (instance-check clojure.lang.Sorted x))))

Only a value whose own type has `Sorted`'s `seqFrom` method enters
`instance-check`, so built-in values and ordinary records never reach the
arm walk from `sorted?`. It follows the idiom `empty` and `ifn?` already use
with `jolt.host/jrec-method?`. A known difference from the JVM remains: a
type that declares `clojure.lang.Sorted` and implements none of its methods
gives `(sorted? x)` false where the JVM says true. jolt's own `ifn?` carries
the same tradeoff for `IFn`.

The same commit restores two lines the sync's conflict resolution had
dropped from the fork's commit: `(tree-sorted? m)` in `find-other` and
`(tree-sorted? coll)` in `empty` in `22-coll.clj`. Both branches read the
`:ops` of jolt's native sorted collections, which a user `Sorted` type does
not have.

Verification by the Gambit seat's own runs on the fixed tree, with no test
file changed: `unit gate: 2828/2828 passed` (175 s), `sorted-test.ss`
156/156, `gambitseedcheck`, `gambitgencheck`, `emittedheads`, `deadhost`,
`gambitcheck`, `gambitkernel`, `gambiteval` all green, and a third seed mint
reproducing the same bytes.

FIX COMMIT: `00cb20b9` on fork branch
`fix/sync-restore-tree-sorted-dispatch`, parent `e457adc9` (the pre-fix
`main`). Its cross-family review ended ACCEPT-WITH-NOTES (glm subagent;
report at
`collab/jolt/1795765315502-reviewer-fork-sorted-fix-glm.findings.md`). The
review leaves one narrower hang open: a type that has a `seqFrom` method but
does not declare `Sorted`, asked through a registered arm whose predicate
calls `sorted?`. No such type exists in the repo or the local library
sources. It is on fork `main` since 2026-11-27 09:08 GMT (`origin/main` is
`00cb20b9`). The same commit passed the fix branch's CI run `45965692509`
(all four `test` shards, all three Android shards). `main`'s own run `45971171442` completed green at 09:42:18 GMT on
2026-11-27 (every job success). Verify the remote yourself:

    git -C /Users/sto/workspace/jolt fetch origin
    git -C /Users/sto/workspace/jolt rev-parse --short origin/main   # expect 00cb20b9
    gh run view 45971171442 --repo sonwh98/jolt --json status,conclusion

What this means for you. Your base lineage (section 6.2) descends from
pre-sync fork `main`: it has the fork's old `sorted?` and it does NOT have
upstream `2573c180`, so it does not hang today. Two consequences:
- When that lineage is ever merged with upstream `2573c180` or later, the
  hang appears. Carry the fix (`00cb20b9`). Expect its seed hunks to
  conflict on a pre-sync base: take the change to `25-sorted.clj` and
  `22-coll.clj` and re-mint. Expect a conflict in `25-sorted.clj`
  when merging anything that touches it.
- A lesson for the Guile adapter: jolt lets library code register class
  arms whose predicates are arbitrary Clojure. A core predicate that calls
  `instance-check` on every kind of value can be re-entered from inside one
  of those predicates. Guard any such call the way `sorted?`, `empty` and
  `ifn?` now do.

Lessons that also apply to your work:
- Never resolve a generated or heavily-changed file with a whole-file
  `--ours` or `--theirs`. Resolve hunk by hunk, then check that every line
  the other side added is still present.
- After any merge on the fork, run `make unit` yourself. Do not infer a green
  gate from one focused test passing, and do not attribute a red to the other
  side without running the same gate on that side.
- The `DIVERGENT n` and `CORPUS BUG` lines in a jolt CI log are the certify
  oracle's informational counters. They are not failures.

### 3.4 The Guile target today

- `host/scheme-adapter/guile.ss` is a 305-line design artifact, not loadable
  code: every entry is `UNIMPLEMENTED` (130 of them).
- Its annotations are wrong for current Guile. They assume SRFI-18 names
  (`fork-thread`, `make-thread-parameter`, `mutex-acquire`,
  `condition-wait`). On Guile 3.0.11 `(srfi srfi-18)` is gone. Do not start
  from its one-line hints.
- The real surface, probed on 3.0.11: `(ice-9 threads)` exports
  `call-with-new-thread`, `make-mutex`, `make-recursive-mutex`,
  `lock-mutex`, `unlock-mutex`, `try-mutex`, `make-condition-variable`,
  `wait-condition-variable`, `signal-condition-variable`,
  `broadcast-condition-variable`, `join-thread`, `current-thread`,
  `all-threads`, `yield`, `with-mutex`, `par-for-each`. Parameters are
  fluid-based; there is no `make-thread-parameter`.
- Native FFI exists: `(system foreign)` (`dynamic-link`, `dynamic-func`,
  `pointer->procedure`, `procedure->pointer`, `sizeof`).
- `call/cc` is genuinely multi-shot (probed: re-entered after the capturing
  call returned).
- The porting documents: `host/scheme-adapter/TARGET-CONTRACT.md` (the
  narrative), `host/scheme-adapter/CONTRACT.txt` (the machine-readable
  inventory: tiers `threads`, `system`, `capability-system`,
  `capability-introspect`, `capability-ffi`, `capability-continuations`,
  `eval`, `capability-native-compile`, `capability-image`,
  `capability-profile`, `capability-sample`, `capability-unchecked`, `misc`,
  `coroutines`, `capability-terminal`), `host/scheme-adapter/THREADS.md`.
- The pinned semantics in `TARGET-CONTRACT.md` are load-bearing. Getting one
  wrong produces silent misbehavior. Read them before designing anything.

### 3.5 What the Gambit lane built. This is your reference implementation.

On `origin/gambit-ffi-p2final`:

- Adapter core under `host/gambit/`: `scheme-adapter-runtime.ss` (the `sa-*`
  surface), `rt-core.ss`, `prelude-shims.ss`, `hasheq.ss`, `host-vars.ss`,
  `host-statics.ss`, `host-apply.ss`, `eval-fns.ss`, `boot.ss`,
  `profiles.ss`, `unicode-nfc.ss` plus its generator.
- Generated and minted: `gen-records.ss` produces `records-gambit.ss` from
  the four `host/chez` records files; `gen-seed.ss` mints `host/gambit/seed/`.
  The checks `gambitgencheck` and `gambitseedcheck` keep them current.
- Source loading and the REPL: `source-loader.ss` (loads Yin namespaces from
  source in the order overlays, datomworld source, stdlib, core, with the
  booted-namespace shortcut), `yin-repl-native.ss`, `yin-repl-driver.ss`,
  `yin-repl-boot.ss`, `yin-capture.ss`, `build-yin-boot.py`,
  `build-yin-session.py` (the compiled session boot with digest-stamped
  caches and an interpreted fallback).
- Overlays for datomworld host namespaces: `host/gambit/yin/**` (POSIX file
  operations on Gambit-native I/O, hashing, signing, randomness, the
  frontend manifest).
- The FFI bridge under `host/gambit/ffi/`: `shapes.json` (the ABI manifest),
  `generate-shims.py` producing `generated-shims.scm`, `joltffi-bridge.scm`,
  typed C shims, per-install toolchain provenance (`toolchain.py`,
  `record-toolchain.sh`, `verify-toolchain.sh`), `DIGESTS.txt`, `watchdog.sh`.
- Gates and harnesses under `test/gambit/`: `yin-repl-session.sh` (a 12-step
  ordered state machine over real replies), `yin-equivalence.py` (compiled
  against interpreted replies, byte for byte), `yin-dht-probe.sh`,
  `yin-dht-durable.py`, `unit.edn`.
- Makefile targets, all detection-gated through `GAMBIT_PREFIX` and
  `JOLT_REQUIRE_GAMBIT`: `gambitgen`, `gambitgencheck`, `gambitseedcheck`,
  `gambitcheck`, `gambitkernel`, `gambiteval`, `gambitsourceload`,
  `gambitunbound`, `gambitvars`, `gambitstatics`, `gambittwins`,
  `gambitprofile`, `gambitboot`, and the parity family `gambityinrepl`,
  `gambitdht`, `gambitdht-durable`, `gambityinequivalence`, `gambitffi`.
- Unreviewed: the consolidation merge in `060d0d43` and the five finishing
  fixes that followed it. Phase 0.4 closes that.

Measured on the Gambit lane, for your planning: the interpreted source load
of 139 Yin namespaces takes about 11 minutes; the compiled session boot brings
a warm start to `yin>` in 51 seconds; the durable two-node DHT gate needs more
than 2400 seconds, so give gate runs a 7200 second budget.

### 3.6 datomworld side

- `jolt-toolchain.edn`: the pin (`:sha`, currently `25c3a8b7`), the
  `:submodules` map, the `:chez`, `:gambit`, `:cmake` entries and
  `:os-packages`. `bb bootstrap:jolt` materializes and verifies exactly this.
- `mise.toml`: `chezscheme = "10.4.1"`, `gambit = "4.9.8"` through
  `[plugins]`, `cmake = "4.4.4"`. The `[plugins]` table must stay the last
  table in the file.
- `src/dev/jolt_bootstrap.clj`: `check-chez`, `check-gambit`,
  `check-os-packages`, and `-main`, which writes `build/tools/jolt-env.sh`.
  `JOLT_BOOTSTRAP_TEST_DIR` points the bootstrap at an existing clone; verify
  in the source before relying on it.
- `src/dev/jolt_launch.clj`: the target seam. `known-targets #{:chez
  :gambit}`, `resolve-target`, `launch-command`, `assert-child-target!`
  (fail-closed unless the child itself prints `jolt-target: <target>`).
- `src/dev/yin_repl_launch.clj`: the uniform launcher. `backends`,
  `gambit-capabilities`, `gambit-session`.
- `src/dev/gambit_ffi_cache.clj`, `src/dev/gambit_yin_progress.clj`: the
  bridge cache and the per-namespace load progress.
- `bb.edn` tasks: `bootstrap:jolt`, `smoke:jolt`, `smoke:jolt-target`,
  `yin-repl`.
- `docs/design/yin.repl-jolt-implementation.md`: the track design. Section 6.1
  is the toolchain file, section 6.4 is the upgrade gate, section 10.1 is the
  slice table.
- `qualify:jolt-upgrade` is named in the design but does not exist in
  `bb.edn`. The upgrade gate is the manual checklist in section 6.4.

### 3.7 Toolchain facts

- mise on darkstar: 2026.2.19. `mise registry` has no `guile`.
- The asdf plugin `mise-plugins/mise-guile` builds Guile from the signed
  source tarball and verifies the GPG signature. HEAD is
  `fc5bbf3e48f5ae76045b99d99c096cf72d8db20a` (unchanged at 2026-11-27). It
  builds serially, with plain `make`, taking 15 to 25 minutes or more.
- The plugin needs environment it does not set itself:
  `GUILE_CONFIG_OPTIONS`, `BDW_GC_CFLAGS`, `BDW_GC_LIBS`, `PKG_CONFIG_PATH`,
  plus pkg-config, gmp, libunistring, libffi and bdw-gc. On macOS it needs a
  one-time import of the signer key
  `4FD4D288D445934E0A14F9A5A8803732E4436885` from `keyserver.ubuntu.com`.
- The pin must be 3.0.11. 3.0.9 does not build against libgc 8.2.
- Open blocker, the reason this is phase 1 and not a one-line change: on
  2026-11-18 every from-source Guile built on darwin-arm64 segfaulted before
  printing `--version`, including with `--disable-jit`, while Homebrew's
  3.0.11 runs. Linux was never probed. Observed on 2026-11-27: Homebrew's
  `guile` links `/usr/lib/libffi.dylib` (the system one), Homebrew's `bdw-gc`,
  `libunistring` and `gmp`. The probe had pointed the plugin at Homebrew's
  libffi. That is a lead, not a diagnosis.
- On this mise, `mise ls-remote brew:guile` answers "brew is not a valid
  plugin name". There is no Homebrew backend to fall back on.

--------------------------------------------------------------------------------
## 4. Method rules (each one was learned from a defect on the Gambit lane)
--------------------------------------------------------------------------------

- Doc first. Cite the Guile manual and the documented API before reading
  implementation source. Read source only where the docs leave the pinned
  semantics undetermined, and say which row it resolves.
- Decisive witnesses before implementation. Every "must verify" row gets a
  small executable program with a watchdog. A deadlock is a recorded failure.
  Include a negative control that proves the witness can fail.
- An absent capability raises a message-carrying error. It never fakes a
  value and never returns a constant that reads as success.
- Gates assert on output content, state-derived, watchdogged. A verdict that
  is printed unconditionally is not a gate. Exit code 0 is not evidence: two
  real defects on the Gambit lane passed every exit-code check and were caught
  only by content checks.
- A delegate's report is a claim. You read the diff and the gate output
  yourself before reporting anything as verified.
- After a merge, run the gate yourself. Focused tests do not prove a merge.
- Never resolve a whole file with `--ours` or `--theirs` (section 3.3).
- Reproduce a failure with the failing test only, never the full suite
  (`build-n-test.md`, gate G0). Suite-scale runs go to shiva or jupiter.
- Commit messages follow `docs/agents/format.md`: `<type>[(<scope>)]: <lowercase
  imperative summary>`. No AI attribution in any commit. jolt CI has a job
  that rejects it, and the fork's `AI_POLICY.md` puts ownership on the
  submitter.
- `.md` tables are ASCII box tables, not pipe tables (`format.md`).

--------------------------------------------------------------------------------
## 5. Phases
--------------------------------------------------------------------------------

Phases 1 and 2 run concurrently and both start after phase 0. Each phase ends
with a landed, reviewed slice. If the track stops, it stops on a boundary.

### Phase 0. Take the seat and establish the base (about a day)

0.1 Follow `docs/agents/roles/orchestrator.md` workflow steps 1 and 2. Create
    your session log at `orchestrator-logs/<unix-ms>-<agent>-<model>-guile-port.md`.
    Read everything in section 10.

0.2 Normalize the fork clone. At handoff `/Users/sto/workspace/jolt` was
    checked out on the Gambit seat's `sync/upstream-2026-11-22` branch. Put it
    back: `git checkout main && git merge --ff-only origin/main`. Seven stale
    worktrees were registered (`jolt-ffi-allowfix`, `-p1c`, `-p1e`, `-p1f`,
    `-p2`, `-p2b`, `-p2final`). Remove the ones that are clean and whose
    branch is on origin. Keep `jolt-ffi-p2final` until 0.5 passes.

0.3 Create the base branch `guile/integration`: branch from
    `origin/gambit-ffi-p2final`, merge `origin/gambit-ffi-p1-store-swap`.
    Resolve the one conflict in `host/gambit/yin/jolt/gambit/yin.jolt` as the
    union: the store-swap seat's `(use-store ...)` support and p2final's
    durable store admission both stay. Re-mint the seeds
    (`sh host/chez/remint.sh` with `JOLT_CHEZ` set to the mise Chez).

0.4 Review the unreviewed delta in `060d0d43`: the consolidation merge
    resolution and the five finishing fixes (exclusive-create `write-new`,
    symlink-bounded `realpath` with lock-seam keying, the TOCTOU bound, the
    DIGESTS re-verification). The authors were codex (GPT) and deepseek, so
    the reviewer must be agy (Gemini) or glm. One dispatch, adversarial
    flavor, with the reviewer re-running the gates in 0.5. ACCEPT required.

0.5 Gate the integration branch on darwin-arm64. Run the Gambit battery
    yourself: `gambitffi gambitcheck gambitkernel gambiteval gambitsourceload
    gambitseedcheck gambitgencheck emittedheads deadhost gambityinrepl
    gambitdht gambitdht-durable gambityinequivalence`. Then the Chez side for
    the compiler the merge touched: at minimum `unit`, `selfhost`,
    `sorted-test.ss`, `emittedheads`, `deadhost`. Verify the target names in
    the Makefile of that branch. A fresh clone fails closed on
    `gambitffi-verify-digests` until you run
    `sh host/gambit/ffi/record-toolchain.sh <mise gambit prefix>`.

0.6 Push `guile/integration` to the fork. It is the base for every Guile
    slice. Record its SHA in your log.

Fallback: if 0.3 to 0.5 cannot be made green within two engineer rounds, base
the Guile slices on `origin/gambit-ffi-p2final` alone, record the
store-swap delta as an open integration item, and continue. Do not spend more
than that on it.

### Phase 1. Toolchain: Guile 3.0.11 through mise, on both platforms

Deliverables, on branch `guile/toolchain` off `guile/integration` for the
fork side and a datomworld worktree for the datomworld side:

- The mise declaration in `mise.toml` (section 6.1 decides the mechanism).
- A `:guile` entry in `jolt-toolchain.edn` mirroring `:gambit`.
- `check-guile` in `src/dev/jolt_bootstrap.clj`: resolve the binary through
  mise at the exact pin, require `guile --version` to report the pin, and
  smoke the interpreter. Export `JOLT_GUILE` in `build/tools/jolt-env.sh`.
  Bootstrap never installs anything (section 6.4).
- In the fork's Makefile: `GUILE_PREFIX`, `GUILE_GUILE`, `GUILE-SKIP`
  with `JOLT_REQUIRE_GUILE`, mirroring the `GAMBIT_*` variables.

Acceptance: on darkstar and on one Linux host, from a clean mise state,
`mise install` succeeds and `mise exec guile@3.0.11 -- guile --version`
prints 3.0.11. `bb bootstrap:jolt` passes and its output names the Guile
binary.

### Phase 2. Semantics audit and design (concurrent with phase 1)

2a Audit. One engineer round, doc first, witnesses committed on
   `guile/audit`, report in `host/guile/AUDIT.md`. Mirror the method of the
   Gambit audit (`p1a/AUDIT.md` and `p1a/witnesses/` on fork branch
   `gambit-ffi-p1`). Answer each question with an executable program and give
   GO, PARTIAL or NO-GO per capability:

   - Reader and emit parity. Which reader options must be set at boot for
     jolt's emitted Scheme to read identically (case sensitivity, square
     brackets, datum and block comments, string escapes, numbers,
     bytevector literals). Guile's reader is case-insensitive by default;
     verify and set it before any emitted form is read.
   - Threads. The contract names over `(ice-9 threads)`. Thread-parameter
     fork-inheritance, including under nested `parameterize`. Non-recursive
     mutex re-acquire. No lost wakeups on `condition-wait`. A numeric thread
     id that is stable for a thread's lifetime (Guile gives thread objects).
     Whether `memory-order-acquire` and `memory-order-release` need a real
     fence on Guile's one-OS-thread-per-Scheme-thread model.
   - Continuations. A one-shot escape wrapper with a spent flag: a second use
     raises, and the escape unwinds `dynamic-wind` so `finally` runs. Whether
     the fibers tier can be built on it; otherwise it degrades to OS threads
     as the contract allows.
   - FFI. Errno captured atomically at the call boundary (concurrent calls
     with distinct errnos; a post-capture clobber must not leak). Whether a
     collection completes on another thread while one thread sits in a C
     call (the handshake pattern of witness W2). Whether a foreign pthread
     can enter Scheme, or must refuse. Callback lifetime under GC.
     `dlopen` and `dlsym` resolution order and error reporting.
   - GC and pinning. With a non-moving collector both `sa-lock-object` and
     `sa-unlock-object` may be no-ops; prove it with a GC during a held
     bytevector pointer. `sa-gc-collect` must be real.
   - I/O. What the durable stores need: real `realpath` through symlinks,
     exclusive create, advisory locking, directory sync, mtime. Subprocess
     support. Non-blocking sockets, UDP and a poller; this decides DHT and
     WebSocket parity.
   - Native compile. `compile-file` to `.go`, AOT cache mapping, the
     auto-compile behavior and its cache directory, and `sa-make-boot-file`
     raising.
   - Cryptography. BLAKE3, SHA-256, Ed25519 and randomness on Guile.
   - Performance. Boot and eval wall times, interpreted and compiled, on
     both platforms. CI time budget.
   - Platform. Every darwin-arm64 against linux-x86_64 difference, including
     the from-source crash that feeds section 6.1.

   The audit output is the Guile capability matrix. Nothing in phase 3 may
   contradict it.

2b Design. One Architect round. Layout under `host/guile/`; which Gambit-lane
   pieces are generalized to take a target parameter and which are mirrored;
   the gate list and CI wiring; the emitter target (`:guile` in `target-prims`
   with `:unsafe-prefix ""`, and an audit of every `:gambit` and `:chez`
   conditional so `:guile` takes the generic path); the degradation matrix;
   the slice plan for phases 3 to 5; a risk register. Reviewed by a model of
   a different family than the designer. The accepted design is the ruling
   for the rest of the track, the role astra's plan played on the Gambit lane.

### Phase 3. The adapter (the largest phase; three slices)

- 3a `guile/adapter-core`: the runtime. `scheme-adapter-runtime.ss`
  equivalents for the threads, system, eval, misc, unchecked and degraded
  capability tiers. Milestone: `adaptercheck` passes against the Guile adapter
  and the gate-time assertion pass loads on Guile.
- 3b `guile/adapter-gates`: `gen-records.ss` to `records-guile.ss`, the seed
  mint to `host/guile/seed/`, and the gates `guilegen`, `guilegencheck`,
  `guileseed`, `guileseedcheck`, `guilecheck`, `guilekernel`, `guileeval`,
  `guileboot`, `guileunbound`, `guilevars`, `guilestatics`, `guiletwins`,
  `guileprofile`, with their allowlists. The allowlists may name only the
  target-owned files.
- 3c `guile/adapter-ci`: the `:guile` emitter target and the CI wiring in
  `.github/workflows/tests.yml` and `.github/actions/setup-chez`.

Acceptance for the phase: every Guile gate green on Guile 3.0.11 on
darwin-arm64 and linux-x86_64; the Gambit and Chez gates still green; the
fork's CI green on its code jobs for the branch. A gate must never read as
green while skipping: a job that is meant to exercise Guile sets
`JOLT_REQUIRE_GUILE=1`.

### Phase 4. yin.repl on Guile

Branch `guile/repl`. Port or generalize the source loader, the REPL driver
and the compiled session boot. On the datomworld side add `jolt-guile` to
`src/dev/yin_repl_launch.clj` and `:guile` to `jolt_launch.clj`, with honest
capability notes. Gate `guileyinrepl`: an ordered state machine over real
`yin>` replies covering evaluation, an evaluation error, a frontend refusal,
a qualified FFI call, an inline refusal with the session surviving, and a
clean quit. Where a compiled boot exists, an equivalence gate diffs its
replies against the interpreted path.

Acceptance: D4 on both platforms. From this slice on, `check-guile` is fatal
in bootstrap (section 6.4).

### Phase 5. FFI, stores and DHT, exactly as the audit's matrix allows

Branches `guile/ffi` and `guile/stores`. For each capability the matrix marks
GO: implement, and gate it. For each capability it marks NO-GO: the
documented refusal, with a gate row asserting the exact message and the
survival of the session. Nothing in between. The Gambit lane's refusals are
the pattern to copy: `blocking ffi is unsupported on the gambit target` is
asserted by name in the session gate.

### Phase 6. Parity audit, documentation, pin

- A feature matrix across all six backends (`jvm`, `node`, `dart`,
  `jolt-chez`, `jolt-gambit`, `jolt-guile`): stores, DHT, frontends, FFI,
  WebSocket serving, startup time. One row per capability, with the evidence
  path for each cell.
- Every gap gets a row in `docs/agents/known-gaps.md`. That ledger is
  append-only row by row; never resolve a conflict in it by taking one side.
- Update `bb yin-repl --help` and `src/cljc/yin/vm/docs/yin.repl.md`.
- Relay to the yin.repl jolt track seat for the section 10.1 slice-table row.
- Advance the pin (section 6.10).

--------------------------------------------------------------------------------
## 6. Decisions made in advance
--------------------------------------------------------------------------------

Each decision has its reason. Do not re-open one; record in the final report
if evidence shows it was wrong.

### 6.1 Toolchain mechanism (phase 1)

Rule: mise is the single declaration point for Guile, at the exact pin
3.0.11, on every host. A system Guile outside mise is not acceptable.

Decide by evidence, in this order. Stop at the first that works.

1. Fix the from-source build so the mise-built binary passes a smoke on
   darwin-arm64, and keep the unmodified plugin at its pinned SHA with the
   needed variables declared in `mise.toml`. Leads, each a hypothesis to test:
   the probe linked Homebrew's libffi where Homebrew's own build uses the
   system one; a libgc header and library mismatch between `BDW_GC_CFLAGS`
   and `BDW_GC_LIBS`; the plugin's serial build. Compare against Homebrew's
   recipe (`brew cat guile`) and its linkage (`otool -L`). Budget: two
   engineer rounds.
2. A plugin fork that applies the fix in the plugin itself, pushed as
   `sonwh98/mise-guile` and pinned in `[plugins]` by full SHA. Allowed only
   if route 1 shows the fix must live in the plugin. It is a public fork of an
   open-source plugin under the owner's account. Record it in the report.
3. If neither works on darwin-arm64: stop phase 1 at that boundary, proceed
   with everything that runs on Linux, and report the darwin blocker with the
   evidence. Do not fall back to a bare Homebrew install.

Pin 3.0.11. Any later bump goes through the upgrade gate in section 6.4 of the
jolt design. The `[plugins]` table stays the last table in `mise.toml`.

System libraries the build needs are declared, not silently assumed: on
Debian-family hosts in `:os-packages :debian :required` of
`jolt-toolchain.edn`, which bootstrap checks; on macOS in the toolchain
comment and `docs/agents/build-n-test.md` next to the gambit notes. Bootstrap
checks and never installs.

### 6.2 Base lineage: the production lineage, not fork `main`

Build on `guile/integration` (phase 0.3), which sits on the lineage the
datomworld pin already uses. Reason: advancing the pin to a SHA must not
bundle a 700-commit jolt upgrade with the Guile change. The fork's `main` is
86 or more upstream commits ahead of that lineage; moving the pin there would
change jolt's behavior across datomworld for reasons unrelated to Guile, and
the D6 check could not attribute a failure to either change.

Consequences: the Guile work is written against the pre-sync jolt-core. The
reconciliation of the fork's `main` with the host lineages is a separate
upgrade-gate item. Record it as an open item in the final report. Do not do
it in this track.

### 6.3 Fork `main` is not moved in this track

Push `guile/*` branches. Do not merge them into fork `main`, do not re-sync
`main` with upstream, and do not rewrite anything already on `main`. Merging
to `main` is the owner's. Nothing in the definition of done needs it: the
datomworld pin can point at a branch SHA, as it does today.

### 6.4 Bootstrap checks, never installs, and fails closed once consumed

`bb bootstrap:jolt` never installs a tool. This was ruled deliberately for
gambit and stands. `check-guile` is introduced in phase 1 as a warning. It
becomes fatal in the same commit that lands the first feature that consumes
`JOLT_GUILE` (phase 4). Reason: a warning that leaves `JOLT_GUILE` unset is
the "reads as green while dead" mode, but requiring Guile before anything
uses it taxes every seat for nothing.

### 6.5 Naming

Target keyword `:guile`. Environment `JOLT_GUILE`. Makefile `GUILE_PREFIX`,
`GUILE_GUILE`, `JOLT_REQUIRE_GUILE`. Gates `guile*` with the same suffixes as
the `gambit*` family. Launcher backend `jolt-guile`. Branches
`guile/integration`, `guile/toolchain`, `guile/audit`, `guile/adapter-core`,
`guile/adapter-gates`, `guile/adapter-ci`, `guile/repl`, `guile/ffi`,
`guile/stores`, `guile/parity`. Fork `guile/<slice>` branches only.

Never call a bare `guile` from `PATH`. Always the absolute path that
bootstrap resolved. The fork has the same rule for `gsi` and `gsc`, because
`gsc` on `PATH` is Ghostscript.

### 6.6 Scope limits

- No `jolt` command-line build on Guile. The Gambit lane has none either.
- No browser or web-REPL target for Guile. `gambitweb` is a Gambit JS-target
  feature with no Guile analog.
- No Windows or Android qualification. Those CI shards skip the Guile gates
  by detection, and no claim is made for them.
- No upstreaming to `jolt-lang/jolt`. Recording it as a possible follow-up in
  the report is enough.
- No Guile version other than 3.0.11.

### 6.7 FFI and capability scope

Qualify only what a witness proves. A capability whose witness fails or
cannot be written refuses with a documented message and gets a known-gaps row.
The two hardest rows, where Guile is most likely to refuse honestly: a
collection completing while a thread sits in a C call, and a foreign pthread
enterering Scheme. Chez's `__collect_safe` semantics have no documented Guile
equivalent. Do not translate the name and assume the meaning.

The owner's parity directive (instruction 7) means: push each capability as
far as the runtime honestly allows, then report the gaps. It does not mean
qualify everything.

### 6.8 Where work runs

- Implementation and focused verification on darkstar, in worktrees.
- Suite-scale runs (the full quick tri-host `bb test`, subsystem lane sets,
  the slow tier) on shiva or jupiter, never on the laptop. Check `uptime` on
  both and use the lower-loaded one, measured as load against core count.
- Scratch clones on the remote hosts live under `~/ci/`. Never touch
  `~/workspace/datomworld` on those hosts. Run long jobs in `tmux`, one log
  directory per run, and kill remote processes by exact PID, never by name
  pattern.
- Gate runs of the durable or session families: 7200 second budget.

### 6.9 Landing on datomworld `master`

Three conditions, all required, per the coordination contract:

1. Cross-family review ACCEPT, with the reviewer's own re-run of the gates.
2. An Architect sign-off recorded in writing, from a model of a different
   family than the implementer. One dispatch can serve both when the reviewer
   is Architect-tier and the brief asks for sign-off.
3. The `build-n-test.md` gate for the change class. `mise.toml`, `bb.edn` and
   `src/dev/` are wide inputs: the full quick tri-host `bb test` on a remote
   host.

Master carries known reds owned by the master-green seat. Before a merge,
classify each failure by running only its namespace at the current master tip
in the same clone. A failure that reproduces at the tip is pre-existing:
record it in the merge message and move on. One that does not reproduce is
yours: fix it against that namespace alone before merging. Read the latest
master-green ledger under `orchestrator-logs/` for the current red list.

Landing mechanics: work in a worktree; `git branch --show-current` must print
`master` in the main checkout before the merge; stage only this track's
files; `git push origin HEAD:master`; verify the remote ref and the ancestry.

### 6.10 Advancing the pin

Advance `jolt-toolchain.edn :sha` to the Guile-capable tip only when, on that
exact SHA:

- D1 to D5 hold.
- The Chez lane passes: `unit`, `selfhost`, and datomworld's `bb smoke:jolt`
  plus the jolt probe tasks that the pinned tree supports.
- The Gambit lane passes its full battery on all three platforms.
- Immediately before landing, re-merge `origin/gambit-ffi-p1-store-swap` and
  `origin/gambit-ffi-p2final`, because other seats advance them.
- A fresh `bb bootstrap:jolt` on a clean clone verifies the new pin, including
  the submodule map.
- The section 6.9 gate passes.

Record the pin's scope in the manifest comment, as the gambit entries do:
what works on each host, what refuses, and the evidence paths.

### 6.11 Model routing and reviewer independence

- Read `docs/agents/routing-status.md` at every dispatch. It changes daily.
- At handoff: codex pool 1 (darkstar and shiva share one meter) was exhausted
  until about 06:30 +07 on 2026-11-28; codex pool 2 (jupiter, a separate
  meter) was available, with `codex exec resume` read-only there; agy argon
  was available again; glm-5.3 works; qwen is unavailable until 2026-12-05;
  check the board for deepseek's balance before using it.
- Families: GPT (codex), Gemini (agy), GLM, DeepSeek, Kimi, Mistral, Meta,
  Qwen. The reviewer is always a different family than the implementer.
- Sequence dispatches within one codex pool; two concurrent codex rounds on
  one pool drain it and, in one worktree, trample each other.
- If a quota runs out mid-round, the partial work is on disk. Recover by
  resume-by-state in a fresh session with a brief that describes the state.
  Do not redispatch from scratch as if the round never happened.
- After two failed rounds on one unit, stop dispatching it, record both
  attempts, and treat it as blocked for the report.

### 6.12 What counts as evidence for "both platforms"

darwin-arm64 on darkstar and linux-x86_64 on shiva or jupiter, each with the
gate output captured to a file that you read. A result on only one platform
is reported as one platform.

### 6.13 Decision rules for cases this brief does not cover

Apply in order and record which rule you used.

1. A rule document decides (`orchestrator.md`, `team.md`,
   `build-n-test.md`, the jolt design, `TARGET-CONTRACT.md`). Follow it.
2. A precedent from the Gambit lane decides (section 10). Follow the
   precedent.
3. Neither decides, and the choice is reversible and inside this track's
   scope: choose the smaller, more conservative change; the one that refuses
   honestly over the one that might be wrong; the one that keeps existing
   behavior unchanged. Record it.
4. The choice touches a file another seat owns or is editing: minimize the
   diff, land it in a small batch, and file the relay in the same batch.
5. The choice is on the hard-stop list (7.3): stop (section 1).
6. None of the above and the track cannot proceed: stop at the slice
   boundary and report the options with your recommendation.

--------------------------------------------------------------------------------
## 7. Authority
--------------------------------------------------------------------------------

### 7.1 You may, without asking

- Create worktrees and branches under the names in 6.5, in both repos.
- Commit on those branches and push them to `sonwh98/jolt` and
  `sonwh98/datomworld`.
- Force-push with `--force-with-lease` a `guile/*` branch that this track
  created and that nobody else has built on, after a rebase.
- Dispatch delegates per the routing board.
- Run suites on shiva and jupiter in `~/ci/` scratch clones.
- Land on datomworld `master` under 6.9, and advance the pin under 6.10.
- Remove a worktree this track created once its branch is on origin and its
  tree is clean.

### 7.2 Consent that does not carry over

Delegate dispatch sends repository content to an outside provider. The
authorization of record is the owner's routing directive on the board (codex
and agy as primary coding agents). Use the delegate CLIs the reference names,
pass prompt paths rather than diffs in arguments, and never place a credential
in a prompt file or an environment you control. A delegate that needs a
credential is a hard stop.

### 7.3 Hard stops. Never, under this brief.

- Rewriting pushed history other than 7.1's force-with-lease case.
- Moving, rebasing or force-pushing fork `main`, or datomworld `master`.
- Deleting a worktree, branch or artifact that another seat created or that
  holds uncommitted work that is not yours.
- Waiving review, or reviewing your own work.
- Committing another seat's in-flight files, or `git add -A` in a shared
  checkout.
- Creating a public repository other than 6.1 route 2.
- Claiming a capability the gates did not prove.

--------------------------------------------------------------------------------
## 8. Dispatch recipes and the pitfalls that cost the Gambit seat days
--------------------------------------------------------------------------------

Read `docs/agents/delegate-invocation-reference.md` at dispatch time. What
follows only adds what that file does not say.

PATH. Every background shell starts without the node tree, and `codex` and
`agy` live in it. Prefix every dispatch command:

    export PATH="/Users/sto/.nvm/versions/node/v26.1.0/bin:/Users/sto/.local/bin:$PATH"

A `command not found: codex` with exit 127 means this, nothing else.

Subagents. Launch the Agent tool with `run_in_background: true`, always. A
foreground agent is lost on any interruption. When resuming one, give it a
tight scope and say "do not background anything; run gates in the foreground
with timeouts". A subagent that idles with "waiting for background tasks" is
stuck: kill it by PID and resume by state.

Worktrees. Delegates cannot see files outside their sandbox. Put the brief
inside the worktree (`build/<slice>/brief.md`) and let the delegate create its
own worktree as its first step. Dispatch from the main clone, never by `cd`
into a worktree that a cleanup may have removed. The Gambit seat lost three
dispatches to a missing worktree.

codex. `-s read-only` cannot write a findings file: ask for the findings in
the final message and promote it yourself. `codex exec resume <thread> --json
-m <model> -` keeps the thread's sandbox; pass `-m` or the model can switch
silently. After a quota death the thread may be gone: resume by state in a
fresh session. POOL 2 on jupiter mounts resumes read-only.

agy. With `--output-format json` it writes one JSON line at the end of the
turn, so an empty log while it runs is normal. Do not pass `--mode
accept-edits`: it auto-denies commands and the turn ends "SUCCESS" with no
work done. `--sandbox` with no mode flag and `permissions.allow` for commands
is the working implementation form. Resume with `--conversation <id>`.
Argon's quota is separate from flash's; check per model.

deepseek. Its sandbox is its working directory and `acceptEdits` denies
`make` and `git commit`. Use it for analysis and edits, and run the gates
yourself.

Waiting. The harness blocks `sleep N; command` chains. Use
`until <condition>; do sleep N; done` with `run_in_background: true` and let
the completion notification wake you.

Gate output. Write gate output to a file and grep the file. A pipe such as
`make ... | grep PASS | tail` reports the exit of `tail`, and an empty result
can mean the run never reached a verdict. Check `make`'s own exit code.

Generated files. After changing `jolt-core` or the `clojure.core` overlay,
re-mint (`sh host/chez/remint.sh`, `JOLT_CHEZ` set) and confirm
`gambitseedcheck` and `gambitgencheck`. A gate that loads a stale seed
passes for the wrong reason.

Mise. `[plugins]` must be the last table in `mise.toml`. On a fresh clone the
FFI bridge fails closed until its per-install provenance is recorded.

Fork CI. Read `--repo sonwh98/jolt`. macOS arm64 jobs sometimes never start
(hosted-runner capacity) and the Windows job sometimes dies in an MSYS2
package-signature step. Both are infrastructure. Rerun the failed jobs once;
if they fail the same way again, record it and rely on the local darwin
evidence.

Local shell. Darkstar has twice become unable to spawn any command
(`spawn /bin/zsh ENOENT`) under high load from another seat. If that
happens, stop, report what is safe on origin and what is only on disk, and
resume when the shell returns.

--------------------------------------------------------------------------------
## 9. Reports and artifacts
--------------------------------------------------------------------------------

- Session log: `orchestrator-logs/<unix-ms>-<agent>-<model>-guile-port.md`.
  One entry per coherent unit, append-only, in the schema of `orchestrator.md`.
  Do not fall behind: the Gambit seat's log is eight days behind its work,
  and reconstructing it cost more than writing it would have.
- Delegation artifacts under `collab/jolt/`:
  `<timestamp>-<role>-<task>.prompt.md` and
  `<timestamp>-<role>-<task>.<model>.findings.md`, with the standard headers.
  Keep them committed: the owner treats uncommitted collab files as a defect.
- A relay in `collab/jolt/` with every change to `jolt-toolchain.edn`,
  `mise.toml`, `src/dev/jolt_bootstrap.clj`, `src/dev/jolt_launch.clj` or
  `src/dev/yin_repl_launch.clj`.
- Final report, as `<timestamp>-orchestrator-guile-port-final-report.findings.md`
  in `collab/jolt/` and as the closing entry of your session log:
  - the parity matrix across the six backends, with the evidence path in each
    cell, and the list of what is not consistent (instruction 7: "let me
    know");
  - the pins: Guile version and mise mechanism, the fork branch SHAs, the
    datomworld pin before and after;
  - how to run it: the exact `bb yin-repl --backend jolt-guile` line and the
    gate commands;
  - every decision taken under 6.13, with its reasoning;
  - what was not done and why; unresolved risks; known-gaps rows added;
  - delegate rounds with models, sessions and artifact paths.
  Start the report with `Completed-GMT`, `Completed-Local`, `Coding-Agent`,
  `Session-ID`, as the seat handoff template requires.

--------------------------------------------------------------------------------
## 10. Reference index
--------------------------------------------------------------------------------

Rules. Read first, in this order.

- `docs/agents/roles/orchestrator.md` (workflow, coordination contract,
  branch discipline, artifact protocol)
- `docs/agents/delegate-invocation-reference.md`
- `docs/agents/build-n-test.md`
- `docs/agents/team.md` and `docs/agents/routing-status.md`
- `docs/agents/format.md`
- `docs/design/yin.repl-jolt-implementation.md` (sections 6.1, 6.4, 10.1)
- In the fork: `AI_POLICY.md`, `host/scheme-adapter/TARGET-CONTRACT.md`,
  `host/scheme-adapter/CONTRACT.txt`, `host/scheme-adapter/THREADS.md`

Guile.

- The probe: `collab/jolt/1794971000000-engineer-guile-probe.glm-5.3-flash.findings.md`.
  Section 2 has the install failures verbatim, section 3 the tier
  classification and the ten "must verify" rows, section 5 the risks R1 to
  R10. Its estimate predates the Gambit parity work; section 2 of this brief
  supersedes the sizing.

The Gambit lane, as precedent for each kind of round.

- The Gambit seat's log:
  `orchestrator-logs/1795015483992-zcode-glm-5.3-flash-gambit-ffi-orchestration.md`
- Design ruling (astra):
  `collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md`
- Audit method and its review:
  `collab/jolt/1795016000000-engineer-p1a-gambit-runtime-audit.glm-5.3-flash.findings.md`,
  `collab/jolt/1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md`,
  `collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md`
  (the standing ruling and its "must not assert" list)
- FFI construction-site inventory:
  `collab/jolt/1795020000000-engineer-p1b-inventory.glm-5.3-flash.findings.md`
  and `p1b-inv/` on fork branch `gambit-ffi-inv`
- Implementation and review:
  `collab/jolt/1795042000000-engineer-p1b-stage1-codex-gpt-6.1-sol.findings.md`,
  `collab/jolt/1795062317148-reviewer-p1b-stage1-argon.gemini-4-argon.findings.md`
- yin.repl on Gambit:
  `collab/jolt/1795110000000-engineer-p1c-yin-repl-gambit.gpt-6.2-sol.findings.md`,
  `collab/jolt/1795135000000-reviewer-p1c-glm.glm-5.3-flash.findings.md`
- DHT capability probe and its fix:
  `collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md`,
  `collab/jolt/1795215000000-engineer-p1f-dht-gambit-fix.gpt-6.2-sol.findings.md`,
  `collab/jolt/1795220000000-reviewer-p1f-agyflash.gemini-3.8-flash.findings.md`
- Compiled session boot and its review:
  `collab/jolt/1795250000000-engineer-p2b-compiled-session-boot.gpt-6.2-sol.findings.md`,
  `collab/jolt/1795254000000-reviewer-p2b-agyflash.gemini-3.8-flash.findings.md`
- Uniform launcher:
  `collab/jolt/1795187823308-engineer-p1d-uniform-launcher.gpt-6.2-sol.findings.md`
- Stage 2 (callbacks), designed and not implemented, for the Gambit target:
  `docs/design/yin.repl-jolt-gambit-ffi-stage2.md`
- Track authority relays:
  `collab/jolt/1795104953000-orchestrator-relay-lane-coordination-and-obligations.md`,
  `collab/jolt/1795105070000-relay-addendum-gambit-slice-delegation-structure.md`

Begin your first log entry by stating which items in section 3 you re-verified
and which had changed.
