Created-GMT: 2026-11-14 05:5x GMT
Created-Local: 2026-11-14 12:5x +07
Dispatched-By: master-green orchestrator seat (interactive; owner-authorized concurrent dispatch)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2), session E-AUDIT
Task-ID: 1794634600000-engineer-store-write-allowlist

# ENGINEER: yin.vm store-write audit red — unregistered mutation sites in the landing window

## Evidence

JVM nightly at `23de41a9` (jupiter, 20261114-0709), lane `yin.vm`, ONE failure:

    FAIL in (every-store-mutation-is-on-the-allowlist) (store_write_audit_test.clj:407)
    a new store mutation must go through engine/store-put or join the
    allowlist with its reason
    actual: (not (= allowlist (source-sites)))
    … "src/cljs/datomworld/demo/continuation_stream.cljs" {["vm->cesk" :map] 1},
       "src/cljc/yin/vm/ucf/rows.cljc" {["row-table" :map] 1, …

New store mutation sites (raw map writes into the VM store) appeared in
`src/cljs/datomworld/demo/continuation_stream.cljs` and `src/cljc/yin/vm/ucf/rows.cljc` and
are not on the audit's allowlist.

## Mission

For each flagged site, decide honestly: (a) route the mutation through `engine/store-put` (the
sanctioned path — preferred when the write is a genuine store mutation), or (b) if the write is
NOT semantically a store mutation (e.g. a local literal that merely matches the audit's regex),
join the allowlist WITH its reason, per the test's own contract text. Do not edit the audit
test. Read the audit to understand what its scan matches before touching anything.

## File ownership

`src/cljs/datomworld/demo/continuation_stream.cljs`, `src/cljc/yin/vm/ucf/rows.cljc` only.

## Verification (scoped only)

1. `clojure -M:test -n yin.vm.store-write-audit-test` (confirm exact ns from the file) → 0F/0E.
2. Unchanged-evidence: `clojure -M:test -n yin.vm.ucf.rows-test` → same counts as master.
3. Foundation: `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/store-write-allowlist` from current origin/master; findings
`collab/exception-track/1794634600000-engineer-store-write-allowlist.codex-gpt-6.1-terra.findings.md`
(per-site disposition (a)/(b) with the reason, diff, rc/Ran lines, head sha). Never merge to
master; rebase before push if master moves.
