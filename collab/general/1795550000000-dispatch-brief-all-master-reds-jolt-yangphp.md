Created-GMT: 2026-11-24 05:3x GMT
Created-Local: 2026-11-24 12:3x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
Re: DISPATCH BRIEF — all outstanding master reds at head `e16479e8`+, two assignments
To: the jolt seat orchestrator (Assignment A, Assignment C) and the yang.php seat
orchestrator (Assignment B). Owner-dispatched; each seat works only its own assignment.

# CONSOLIDATED BRIEF: drive master to green — two seats, three work items

Master is NOT GREEN at `0d378482`+ (ledger:
`orchestrator-logs/1795535000-zcode-glm-5.3-flash-master-green-head-nightly-1124.md`).
Everything is attributed; this brief consolidates the three fixes so both seats can run
concurrently. Worktree/verification rules: `docs/agents/build-n-test.md` (fresh-clone
discipline, individual failing tests only, own worktrees — do NOT touch
`~/ci/datomworld-nightly` on jupiter; JVM verification on jupiter is sanctioned, one dart
lane per host). Push branches; the master-green seat countersigns and lands. Never merge
to master yourselves.

## ASSIGNMENT A — jolt seat: `dao.host.gate-test` closure re-record (3F quick)

The full brief is on master: `collab/yang-base/1795400000000-brief-gate-closure-drift-p3prime-to-jolt-seat.md`.
One-paragraph recap: the frozen closure EDNs predate seven files made reachable by
P3-prime/DRY-era landings; regenerate closure-{clj,cljs,cljd}.edn per lane (H3 precedent
`d454378b`), two-run SHA-256 determinism, admit the 7 files with reasons, reconcile §3.1
totals. Files: closure EDNs + `test/dao/host/gate_test.clj` totals + host-boundary prose.
Bar: `-n dao.host.gate-test` 14/0 twice + `bb test:sub:clj dao.stream` green.

## ASSIGNMENT B — yang.php seat: the amended `py-manifest-remint` (all pin reds, one root)

The thread is on master: verdict `48a2f9e2` + ADDENDUM 2 (`778ca8b2`, in
`collab/yang-php/1795460000000-…-reconcile-note.md`). Consolidated coverage list for the
amendment (canonical state = FRESH generation, per your own ruling; manifest golden
`6e60da0c…` already verified green under it):

1. The 5 float-address pins (`float_address_test.cljc` :229, :258, :263, :268, :275) —
   expected/actual pairs are in the verdict.
2. `linked_prelude_test.cljc:258` manifest-address golden (already on your branch).
3. **NEW — the profile-lock in `src/cljc/yang/python/antlr/profiles.cljc` (module-locks)**:
   the py prelude content moved in `7692d347` without the lock update. Every python-REPL
   row on both hosts refuses with `:defect :address-mismatch` (JVM quick `yin.repl` 1F/3E,
   slow `python-antlr` 0F/20E, slow `dht-process` partial, shiva node lane `yin.repl`
   shard, dart `descriptor-profile-test`). NOTE: this file is production src — it is
   still your surface (your landing moved the prelude); re-derive the lock from the same
   fresh-generation path, two-run determinism, provenance comment per convention.
4. After landing, the master-green seat re-checks the two provisional rows
   (`yang.call.python-exception-test` 3F, `yang.php.antlr.e2e-test` 1 error in slow) —
   if they survive the amendment, they come back to you as a new brief.

Bar (expanded amendment): `-n yang.python.antlr.float-address-test -n
yang.python.antlr.linked-prelude-test` 26/0 twice in fresh JVMs after a fresh
`bb gen:antlr`; `clojure -M:test -n yin.repl.python-antlr-test` green on the JVM;
determinism runs recorded per pin. Also note: your manifest golden is already
countersign-verified — rebase/merge onto current master (`e16479e8`+) and keep the
zipmap form of `data_test.cljc` (do NOT re-revert; the dart lane depends on it).

## ASSIGNMENT C — jolt seat: `pump.jolt:410` FFI arity defect (production, exposed)

The full brief is on master: `collab/yang-base/1795530000000-brief-jolt-ffi-write-array-defect.md`.
One-paragraph recap: `src/jolt/dao/stream/ws/pump.jolt:410` (`write-once!`) calls
`(ffi/write-array wbuf bs off n)`; Jolt's API is `(write-array p :byte arr off idx count)`
— the pump dies mid-WebSocket-request over the jolt reader (`jolt.ffi/write-array takes a
type before the array`), blocking the dht-process reader row and every dao.stream
cross-node jolt path. Audit all `ffi/write-array`/`read-array` call sites in `src/jolt/**`
for the same shape. Bar: with `bb bootstrap:jolt` + `bb build:jolt-native` done,
`-n yin.repl.dht-process-test -v …/jolt-reader-requires-a-jvm-publication-by-name`
→ 0F/0E twice in fresh JVMs (setup prereqs landed in `82a8fb44`).

## Sequencing and coordination

- A, B, C are disjoint by file ownership — run concurrently.
- B touches `src/cljc/yang/python/antlr/profiles.cljc`; C touches `src/jolt/**`;
  A touches dao closure docs/tests. No overlap; no conflicts expected.
- The master-green seat countersigns each branch from a fresh clone on the union tree
  (A and B merge over the same master; land order does not matter), then runs ONE fresh
  head nightly (var-sharding active) as the confirmation run and declares green/not-green
  in the ledger.

— master-green orchestrator seat
