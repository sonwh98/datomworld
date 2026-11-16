Created-GMT: 2026-11-13 03:40 GMT
Created-Local: 2026-11-14 10:40 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# RELAY: three JVM nightly red families at `a3f7ab8d` — attributed to the decoupling refactor landing (steps 2–4) and the dao.li rename

To: the yin.repl→dao.jing composition refactor seat (thread of
`collab/dao-stream/1794113310000-orchestrator-handoff-yin-repl-dht-refactor.md`); cc: the
dao.li rename seat; from: the master-green orchestrator seat.

## Context

The last all-green JVM nightly was at `4d0d3e70` (20261112, red-lanes=1 = the python goldens,
since fixed). The next nightly (20261113-1035, pinned `a3f7ab8d`) has 3 red lanes. Everything
that changed in the window: your steps 2–4 + the port/kit/php-adoption merges + the dao.li
rename + my two golden re-mints and the engine one-liner (both scoped-green and reviewed; the
python lane is now GREEN — that family is closed).

## Family A — yin.vm: `yin.vm.linker.admission-test` — 184 failures / 7 errors (reproduced scoped)

`clojure -M:test -n yin.vm.linker.admission-test` at `a3f7ab8d` on jupiter → 184F/7E. The
nightly lane adds more (`tick-rollbacks-carry-the-entire-linker-successor-test` ×42,
`a-trusted-import-records-its-admission-on-every-vm-test` ×24, …). Body: `an-install-child-inherits-the-admission-of-a-preinstalled-entry-test`
(admission_test.cljc:728) — `(:yin.admit/address recorded)` is NIL where the child install
must inherit the preinstalled admission; the recorded entry is not being made/found at all.
The deftests are old and unchanged in the window; your `c0216b9f` rewrote
`src/cljc/yin/vm/linker/interpreter.cljc` (−729 lines) and `6445f37a` added the shell wiring —
the admission recording path is the prime suspect surface.

## Family B — yin.repl: `yin.repl.dht-head-test` — 29 failures / 1 error (reproduced scoped)

Same command form, same host/commit. Signatures: refusal reporting shows the raw segment
address where the contract expects the manifest NAME (`dht: the head trace of <name> was not
deposited: :yin.head/seq-regression`), and `a-refused-deposit-is-reported-once-and-fails-nothing`
(dht_head_test.cljc:976/981) — "the HEAD write did not fail — the evaluation answered". Your
`c0216b9f` ("decouple engine from DHT and extract stream composition adapters") touches this
test file and the composition path directly.

## Family C — uncovered lane: `dao.host.gate-test` — 23 failures (reproduced scoped) — ROUTES TO THE dao.li SEAT

Two signatures: (1) `gate-passes-on-the-current-tree` expects `scanned = 175`, actual 174 —
the `dao.li` rename (`6707eb92`) moved `yang.cic`/`yin.cic` out of the scanned set; the census
pins need the re-record per the H3 precedent (`d454378b`-style). (2)
`an-injected-host-arm-outside-list-1-fails-the-gate` expects `:fail`, got `:scan-failure` —
this one is NOT explained by file counts alone; the rename seat should check whether a moved
file changed what the gate scans/fails on, or whether a port/kit commit changed the
`scan-failure` verdict path. `src/dao/host` is untouched in the window, so the input changed,
not the gate.

## What is needed

Per the 2026-11-07 merge bar (previously-failing tests green, individual tests only, no
suites): Families A+B belong to the refactor seat — land the follow-up on your branch, scoped
verification = the two namespaces above (JVM) and your own changed-set discipline; Family C
belongs to the dao.li seat (census re-record + the scan-failure attribution). The master-green
seat will not fix inside your composition surfaces; if you need verification legs run on
shiva/jupiter, ask and they run here.

Note to the refactor seat, per the owner's standing direction: the refactor is the priority,
not a regression to revert — these are landing debts of steps 2–4, to be paid the same way as
any fix branch (scoped green, then land). The confirmation nightlies otherwise show: node lane
relaunched after an environment hang (killed by PID), dart lane re-running on a clean
generated tree (the stale `cljd-out` incident), JVM slow tier in flight.

— master-green orchestrator seat
