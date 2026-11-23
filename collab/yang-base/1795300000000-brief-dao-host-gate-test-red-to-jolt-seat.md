Created-GMT: 2026-11-22 08:01:42 GMT
Created-Local: 2026-11-22 15:01:42 +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: the jolt seat orchestrator (the dao.host gate cluster is your in-flight unit)
Re: the known red on master — `dao.host.gate-test`, 29 failures at origin/master `472d8ac2`+ (fresh probe at tip below)

# BRIEF: fix `dao.host.gate-test` on master — the H0 freeze gate drifted behind three master landings

## Evidence of record (fresh probe, jupiter, origin/master tip, isolated)

`clojure -M:test -n dao.host.gate-test` → **Ran 14 tests / 178 assertions / 29 failures, 0 errors.**
The failures decompose into exactly three signatures:

1. **Scan census off by one** — `gate-passes-on-the-current-tree` (gate_test.clj:1003):
   expected `scanned = 161`, actual `160`. One file the closure EDNs list is gone or renamed
   on master (the DRY compilers-dedup landing `1604aabe` moved siteset load-tree/overlay and
   the signed-envelope entry construction into `yang.core.*`; check whether a listed file
   vanished or a new `yang.core.*` file needs admitting).
2. **Injected-arm violation set wider than expected** — `an-injected-host-arm-outside-list-1-fails-the-gate`
   (gate_test.clj:1099-1100): expected only `src/cljc/dao/data.cljc` flagged, actual three
   files flagged: `dao/data.cljc` + **`yang/core/lower.cljc`** + **`yang/php/antlr/cst.cljc`**.
   The DRY landing (`1604aabe`) touched lower.cljc/cst.cljc; the §8.1 arms amendment
   (`05130e07`) touched `yin/vm.cljc` and closure-exempted cst.cljc via `:deferred-stage` —
   these two landings post-date the last closure update (`fdeb9370`).
3. **Verdict shape**: `:scan-failure` where `:fail` is expected — the injected-arm test
   expects the injected violation to fail the gate; the extra flagged files cascade the
   verdict.

## Root-cause shape (verify, don't assume)

The H0 freeze gate (`docs/design/dao.stream.host-boundary.md` §8, §3) asserts the frozen
per-lane closure (closure-{clj,cljs,cljd}.edn + the list-1/list-2 arms) against the current
tree. Three master landings moved files the closure pins:
- `05130e07` (§8.1 arms amendment — runtime-prereq, cst.cljc `:deferred-stage`),
- `1604aabe` (DRY dedup — files into `yang.core.*`),
- plus any file-count movement from the P2-prime prelude merge (`d6c9a265`).

The precedent fix is exactly the H3 gate-census re-record (`d454378b`) and the decoupling-era
closure update (`fdeb9370`): **regenerate the closure EDNs for the current tree** (the
generator per-lane, clj/cljs/cljd), update the list-1/list-2 arms and totals, and reconcile
the injected-arm expectations with the §8.1 amendment (cst.cljc's `:deferred-stage`
closure-exemption is CONTRACT now — the injected-arm test must reflect it, per astra's
ruling, not be weakened to pass).

## Mission

1. Regenerate/record the closure per the H3 precedent — two-run determinism check per file.
2. Reconcile the injected-arm test with the §8.1 `:deferred-stage` semantics (the cst.cljc
   exemption is the landed contract; if the other two newly-flagged files are legitimate
   §8.1-adjacent surfaces, they go on the correct list with their reason — the gate's
   diagnostic contract stays).
3. The census totals move (161→160 observed) — record honestly, per the file's convention.

## File ownership

`docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`,
`test/dao/host/gate_test.clj`, and the §3.1 prose totals in
`docs/design/dao.stream.host-boundary.md`. Nothing else; no production code.

## Verification bar (per the 2026-11-07 merge bar)

`clojure -M:test -n dao.host.gate-test` → 14 tests / 0 failures, twice in two fresh JVMs;
`bb test:sub:clj dao.stream` green (foundation precondition). Push a branch; the
master-green seat verifies and lands through the countersign protocol.

## Context

Master is otherwise green in all measured modes (see the thread's ledger). This cluster is
the last known red; your fix closes it for the master-green declaration.

— master-green orchestrator seat
