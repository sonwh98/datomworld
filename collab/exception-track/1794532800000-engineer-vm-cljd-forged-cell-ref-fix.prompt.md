Created-GMT: 2026-11-13 01:05 GMT
Created-Local: 2026-11-13 08:05 +0700
Dispatched-By: master-green orchestrator seat (interactive, owner-authorized "dispatch")
Destination: codex exec -m gpt-6.1-terra (POOL 1, laptop; execution legs via ssh shiva/jupiter)
Task-ID: 1794532800000-engineer-vm-cljd-forged-cell-ref-fix

# ENGINEER: fix the provenance primitive's cell-ref authenticity on ClojureDart

## Read first (in order)

1. `collab/exception-track/1794531000000-orchestrator-provenance-primitive-forged-cell-refs-on-cljd.md`
   — the PROBLEM statement: evidence, failure bodies, minimal repro, scope.
2. `docs/agents/build-n-test.md` — G0 focus rule (individual tests only; NEVER a full suite),
   remote-hosts rules (toolchain ordering, kill by exact PID, one dart lane per host), the
   dao.stream merge precondition, and the "run only the failing tests" merge bar
   (owner ruling 2026-11-07).
3. The primitive: `git show ff8b980a` (heap sibling installation) and the ref check
   `src/cljc/yin/vm/engine.cljc:332-354` (authentic-ref?) + :2468-2530 (heap-ref-id,
   identity-hash — the portability precedent `30f7aaf7` for the exact same class of bug on
   the trace path).

## Mission

On ClojureDart, genuine cell refs fail `authentic-ref?` since the primitive landed: reads answer
`:forged-resource-reference`, some ids aren't even in `:heap` (`dead-or-forged`). JVM and Node
are green on identical code. Attribute the cljd difference — candidate mechanisms: (a) the seal
stored in the heap entry vs the seal on the ref compare unequal because one side round-trips
through a Dart typed-data / map view where `=` is identity; (b) the sibling installation path
writes an entry shape whose `:seal`/key lookup differs under `dart-core` interop; (c) the heap
`contains?` sees a boxed key mismatch. Fix in `src/cljc/yin/vm/engine.cljc` (and the primitive's
installation code if that's where the asymmetry is) so authenticity is portable without
weakening the forgery contract.

## Rules of engagement

- File ownership: `src/cljc/yin/vm/engine.cljc` + whatever file defines the primitive's
  installation (from `ff8b980a`'s diff) + at most ONE new helper cljc if strictly needed.
  Do NOT touch test expectations, goldens, or `test/yang/call/conformance.clj`. If you find
  yourself needing a test change, STOP and report — the tests are the contract.
- Reproduce first: on shiva, in `~/ci/datomworld-nightly-dart` (warm at `74689f1b` — pull
  origin/master first and recompile):
  `bb src/dev/cljd_agg.clj --only yin.vm.cell-test/cell-holding-nil-test` → must fail as
  described BEFORE your fix; that is your red phase.
- Instrumentation runs inside cljd are expensive (compile ~6 min); prefer adding a temporary
  `prn` diagnostic in a throwaway branch commit, never committed.
- Verification after fix (ALL scoped, all on the remote hosts; one dart lane at a time on
  shiva):
  1. cljd: `--only yin.vm.cell-test` (whole ns), `--only yin.vm.provenance-test`,
     `--only yin.vm.heap-reclamation-test` → green.
  2. cljd frontend spot: `--only yang.php.antlr.prelude-test` and
     `--only yang.python.antlr.import-test` → green (these were 27/29-failure frontends).
  3. Non-regression JVM (jupiter scratch clone at your branch):
     `clojure -M:test -n yin.vm.cell-test -n yin.vm.provenance-test` and the forgery contract
     ns from `0f0ab852` (`clojure -M:test -n yang.call.provenance-forgery-test` — verify actual
     ns name from that commit before running).
  4. Non-regression Node (shiva): `node target/node-tests.js
     --test=yin.vm.cell-test,yin.vm.provenance-test` (rebuild bundle after checkout).
  5. Merge precondition: `bb test:sub:clj dao.stream` on jupiter (foundation rule).
- Branch: push `vm-cljd-cell-ref-authenticity` from your worktree (worktree from current
  origin/master `74689f1b`+). Never push master, never rsync.

## Deliverables

- Findings: `collab/exception-track/1794532800000-engineer-vm-cljd-forged-cell-ref-fix.codex-gpt-6.1-terra.findings.md`
  — attribution paragraph (the exact mechanism), diff summary, every verification command with
  rc + the `Ran`/`+N -M` line, branch head sha.
- If the fix cannot be made portable (e.g. cljd cannot honor the identity semantics at all),
  STOP at the attribution step and write that finding — that routes to the architect ruling
  lane, not to a workaround.

## Merge bar

Items 1–2 green on cljd + 3–5 green unchanged-evidence. That is what lands; the nightly
confirms the wider set later.

## HOST NOTE (dispatch moved to jupiter, POOL 2, per owner relay)

You run on jupiter.stigmergy.systems. The repo for your worktree: fetch origin in
`~/ci/datomworld-nightly` and `git worktree add` from origin/master there (or create your own
scratch clone per the build-n-test recipe; NEVER work in ~/workspace/datomworld). Both context
files above were copied to `~/ci/` on this host — read them from there if the repo paths are
absent. shiva.stigmergy.systems is reachable from here by ssh (verified) — the dart/node legs
run there as written. When done, leave your findings file at
`~/ci/1794532800000-engineer-vm-cljd-forged-cell-ref-fix.codex-gpt-6.1-terra.findings.md`
(the orchestrator pulls it back).
