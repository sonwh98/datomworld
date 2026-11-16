Created-GMT: 2026-11-15 01:3x GMT
Created-Local: 2026-11-15 08:3x +07
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, -c sandbox_mode="danger-full-access")
Task-ID: 1794785000000-engineer-cljd-compile-break-prelude-lower

# ENGINEER: cljd compile of `yang.php.antlr.prelude-test` breaks on master — swallowed exception in `yang.php.antlr.lower`

## Evidence (measured, shiva `~/ci/datomworld-nightly-dart`)

`clojure -M:clojuredart:cljd compile yang.php.antlr.prelude-test`:

- GREEN (rc 0): `4d0d3e70`, `a3f7ab8d`, `42440574`, `7320de98`
- RED (rc 1): `53871f52`, `f94649e3`, `11699dfa` (current origin/master)

The failure: `Compilation error - Something horrible happened!` while
`=== compiling namespace yang.php.antlr.lower ===`; the "Faulty form" printed is the ENTRY
namespace's `(ns …)` (misleading — the real fault is inside lower or its deps). No stack trace
is printed; the exception is swallowed by the cljd driver. The whole dart lane dies here
(nightly `compile-all rc=1`, dart-shards-20261114-0709).

The breaking window is `a3f7ab8d → 53871f52` — i.e. the decoupling-seat shell reconciliations
(`8c1a506d`..`700a6e25` family: `yin.shell.{query,session,store,main}`, `yin.repl`,
`yin.repl.main`, `dao.space.dht` repairs) and the dart-gate fix are in it; `lower.cljc` itself
was not touched (confirm with `git log 23de41a9..11699dfa -- src/cljc/yang/php/antlr/lower.cljc`).

## Mission

1. Reproduce scoped on jupiter (own scratch clone from origin/master `11699dfa`; follow the
   toolchain recipe — mise trust AFTER checkout, eval mise env):
   `clojure -M:clojuredart:cljd compile yang.php.antlr.prelude-test` → red.
2. Extract the swallowed exception: try compiling `yang.php.antlr.lower` alone; then bisect
   `a3f7ab8d..11699dfa` (≈10 commits — use `git bisect run` with the single-ns compile as the
   script, foreground, explicit timeout; a green run is ~1-3 min so the bisect is cheap) to the
   exact breaking commit.
3. Root-cause: what in that commit's sources breaks cljd's compiler (a reader conditional? a
   macro the cljd emitter can't handle? a spec/instrument? a cyclic require? Look at the diff
   of the breaking commit for anything `lower.cljc` transitively requires — remember cljd
   compiles the whole require closure).
4. Fix minimally in the breaking sources (NOT in lower.cljc unless that IS the breaking
   surface; NOT in the cljd compiler). If it turns out to be a cljd compiler defect (the
   `tengstrand` cljd repo), STOP at the attribution and report — that routes to a ruling, and
   the interim remedy would be a narrow source-level workaround ONLY if it does not change JVM
   semantics.
5. Verify scoped: the single-ns compile green at your branch tip; then the FULL dart compile
   pass once (`bb src/dev/cljd_agg.clj --only yang.php.antlr.prelude-test` or the compile-all
   step of the lane) green on shiva; JVM unchanged-evidence: `clojure -M:test -n
   yang.php.antlr.prelude-test` same counts as master; `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/cljd-prelude-lower-compile`; findings
`collab/exception-track/1794785000000-engineer-cljd-compile-break-prelude-lower.codex-gpt-6.1-terra.findings.md`
(bisect table, the extracted exception verbatim, mechanism, diff, rc/Ran lines, head sha).
Never merge to master; rebase before push.
