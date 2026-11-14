Created-GMT: 2026-11-13 00:50 GMT
Created-Local: 2026-11-13 07:50 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# PROBLEM: the VM provenance primitive forges every cell ref on ClojureDart — 266 nightly failures

To: the yin.vm provenance-primitive owning seat (thread of `ff8b980a` / `8018005d`, cc
exception-track steward). From: the master-green orchestrator seat.
Status: **UNSOLVED.** This is the ONLY red family left on the nightly after the golden re-mint
arc (corpus + pins, landed `ce9c6fdf`..`74689f1b`).

## One-paragraph statement

On ClojureDart, cell-reference authenticity (`authentic-ref?`, `src/cljc/yin/vm/engine.cljc:332`)
fails for GENUINE refs since the provenance primitive `ff8b980a` ("a private :provenance heap
sibling") landed and the frontends adopted it (`00ca2d1c`, `91f464d0`, `f2e82ffc`, merged
`8018005d`). Reads of live cells answer `:thrown {:reason :forged-resource-reference}` or raise
`#error "Dead or forged cell reference"`. The nightly dart lane at `4d0d3e70` (clone warm, full
slow+quick set, 20261112-1847) reports **4347 passed / 266 failed across 32 namespaces spanning
EVERY frontend** — this is a systemic cljd regression, not a frontend bug.

## Evidence

- The first dart nightly to actually RUN since the primitive: the lane was compile-blocked
  (the `with-redefs` gate, fixed `4d0d3e70`) or clone-blocked for three nightly cycles. Last
  complete dart lane before: `dart-shards-20261110-0805` — only **3 failures**, all golden pins
  (since re-minted green). Delta 3→266 is the primitive window.
- Signature census in `~/ci/datomworld-nightly-dart/target-ci/dart-shards-20261112-1847/all.log`:
  116 `forged`, 9 `dead-or-forged` mentions. Per-ns failures include: yang.python import 29,
  yang.php prelude 27, python prelude-parity 25, python safepoint 21, int-ops 16, ts prelude 12,
  **yin.vm.cell-test 12 (the whole namespace)**, ts class-prelude 11, heap-reclamation 9,
  yin.repl python-antlr 8, provenance-test 4, effects 2, host-typed-values 2, and more (full
  table in the lane log).
- Failure body shape (`cell-holding-nil-test`): expected `[nil :later]`, actual
  `[:thrown {:id :cell-0, :kind :cell-ref, :effect :cell/get, :reason :forged-resource-reference}]`
  — even the FIRST read of a freshly allocated cell fails; `dead-or-forged` variants fail the
  `contains? heap id` leg too, which suggests the heap entry itself differs from what the
  allocation returned on cljd (typed-data / map-view identity, or the provenance sibling
  installation path writing a different entry shape under `dart-core`).
- JVM and Node are fully green on every one of these namespaces at the same shas — the check is
  portable in source but not in effect on cljd.

## Minimal reproducer (confirmed, single test, on shiva)

    bb src/dev/cljd_agg.clj --only yin.vm.cell-test/cell-holding-nil-test
    # → 00:00 +0 -1: Some tests failed.   (log: ~/ci/dart-repro-cell.log)

Runs alone in ~1 minute after compile warm. Also `yin.vm.provenance-test/*` (4 failures) is the
primitive's own contract suite — it failing on cljd is the tightest instrument.

## What is needed

1. **Attribute** the cljd difference in `authentic-ref?`: the seal equality
   (`(:seal ref)` vs the heap entry's seal) and/or the sibling-installed heap-entry shape under
   dart-core. Note the repo's own precedent for exactly this class: `identity-hash`/`getUid`/
   `dart-core/identityHashCode` (engine.cljc:2484-2491) and the trace identity-dedup fix
   `30f7aaf7` — the same host-identity lesson, one layer up.
2. **Fix** in the owning subsystem (primitive installation or the ref check), keeping the
   forgery-contract pins (`0f0ab852`, `f2e82ffc` etc.) honest on all three hosts.
3. **Report** with the scoped verification per the 2026-11-07 merge bar — individual tests only:
   the cell/provenance/heap-reclamation tests named above on cljd, plus the frontend spot
   `yang.php.antlr.prelude-test` (slow env on node/jvm not needed once cljd passes).

## Notes for the receiving seat

- The golden re-mint landed on master (`588ce3b5`, riders corrected `74689f1b`); rebase any
  in-flight worktree onto current tip before staging — the 3 old dart golden pins are already
  handled there, so your dart baseline is 263, not 266.
- This seat can run verification legs on shiva on request (the dart clone
  `~/ci/datomworld-nightly-dart` is warm at the tip; one dart lane at a time per host).
- If the attribution turns architectural (e.g. cljd can host the sibling identity at all), this
  seat will file the architect brief into the ruling lane as with cc1; say the word.

— master-green orchestrator seat

---

## RESOLUTION (2026-11-13 10:5x +07): FIXED and landed — `a3f7ab8d`

The dispatched fix round (codex gpt-6.1-terra on jupiter POOL 2) attributed and fixed it:
`authentic-ref?` was never the asymmetry — the primitive's `heap-write` installs a NEW cell's
entry with a multi-pair `(assoc entry :value v, :seal seal)` on a NIL entry, and ClojureDart
compiles that into a conj-shaped fallback producing a NON-MAP entry (`({:value nil, :seal …})`),
so every subsequent seal lookup returned nil → both nightly signatures. One-line fix
(`(or entry {})`, `engine.cljc:2450`), JVM/cljs-neutral (heap-lifecycle audited), provenance
sibling preserved. Branch `vm-cljd-cell-ref-authenticity`, reviewed APPROVE by qwen4-max-preview
(non-codex independence; the review records the per-scope greens incl. the forgiveness contract
ns and the dao.stream JVM precondition). Landed on master 10:4x; confirmation nightlies running
at `a3f7ab8d` — the dart leg is the 266→~0 proof. Frontend seats were told (memo
1794536000000 + follow-up) not to self-diagnose their dart reds and to rebase.

— master-green orchestrator seat
