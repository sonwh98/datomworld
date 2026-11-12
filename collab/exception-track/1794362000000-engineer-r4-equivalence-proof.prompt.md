Created-GMT: 2026-11-10 20:55:00 GMT
Created-Local: 2026-11-11 03:55:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated — capture thread.started.thread_id)
# Task: Clojure R4 — the private-vs-public equivalence proof at the §8.10.9 bar
Role: Engineer (highest-tier; this is the track's hardest invariant work)
Implementers:
- Model: gpt-6.1-sol (primary) | Assigned: 2026-11-11 03:55 +0700 | Status: staged | Rationale: team.md — "Complex implementation, advanced coding, architectural review"; near-Astra intelligence at workhorse cost. Independent counter-review by argon-high or astra.

## Preconditions (do not start before these hold)

1. Clojure R3 is MERGED to master (the R4 branch cuts from post-R3 master, never from
   exception-r3-impl). Check `git log --oneline -3 master` for the R3 merge commit.
2. `yang.clojure-test` and the kit contract test are green on that master per the R3
   acceptance record in `collab/exception-track/acceptance-ledger.md` (the Round deltas
   section must name R3 and its commit).

## The question R4 must answer (ruling v2 §R4, owner decision 2026-11-11)

> The intra-unit outcome-token channel may remain an optimization ONLY with an equivalence
> proof across caught/uncaught throws, protocol rethrow, binding failures, and higher-order
> re-entry (§8.10.9's bar). A single passing example is insufficient.

Owner decision of record: "(A) now, (B) if R4 proof fails" — i.e. if the proof FAILS, the
fallback to the continuation lane is owner-authorized and this brief is superseded by a
port brief. Your job is to make the failure IMPOSSIBLE to miss if it exists, not to make
the proof pass.

## Scope

- `src/cljc/yang/clojure.cljc` and `test/yang/clojure_test.clj` plus the kit
  (`test/yang/call/exception_contract.clj` `:clojure-private-public-equivalence`
  obligation, pre-registered at lines ~143-177). No other files.
- Branch `exception-r4` from post-R3 master. Worktree: create your own
  (`git worktree add /Users/sto/workspace/datomworld-r4 exception-r4`), never the shared
  main checkout.

## The three lanes that must be proven equivalent

1. **Static intra-unit packet call** — `dsg__intra_call` → `call-entry` vector branch
   (`clojure.cljc:1324-1336`, invoked `:1711-1717`, `:2554-2568`).
2. **Dynamic tail call** — `compile-guest-call` `:clj/outcome-token` branch → `call-entry`
   token branch (`:1346-1353`, `:1563-1572`).
3. **Dynamic non-tail / public boundary call** — `compile-guest-call` `public-bridge` via
   `u/capture` → `call-entry` `entry-body` (`:1300-1312`, `:1518-1562`).

Line numbers are from 778747ce; re-derive after R3 lands (the R3 fix round moves the escape
region substantially).

## The scenarios (each must produce IDENTICAL observable behavior on all three lanes)

- `:caught` — a throw caught by an enclosing try in the same unit.
- `:uncaught` — escapes to the unit's top level.
- `:protocol-rethrow` — a `yang.call` protocol condition caught and rethrown unchanged
  (reason + every evidence field + payload identity preserved, exactly one transfer).
- `:binding` — argument-binding failure delivered to the caller's raise exit.
- `:higher-order` — a throw from a lambda passed to a combinator, and re-entry into the
  callee after a catch.
- **Retention security (owner decision 2026-11-11: VM seam DECLINED; decisive)**: the protocol-condition carrier is the WITNESS mechanism (contents in a guest-admissible cell), whose known limitation is replay/mutation — a guest may `cell/set!` a caught wrapper's contents or copy them into a fresh cell. The proof must test exactly astra's replay/mutation probes across the three lanes AND state whether the continuation lane closes them (it is expected to: under raise-exit semantics there is no retention object at all). If the outcome channel cannot match the continuation lane here, that IS the equivalence failure and the (B) fallback triggers under the owner's standing authorization.
- **Cross-unit (argon's adjudication 17943389000; MANDATORY)**: a clojure callable
  compiled in a DIFFERENT unit is invoked (a) `clojure→clojure` across units, and
  (b) `clojure→python→clojure`. Prove `dsg__admitted?` and `:clj/outcomes?` cannot
  disagree when the callee's `dsg__call_token` was minted in a different unit
  (`:semantic`/`:stack`/`:register` segment differences,
  `yin/vm/semantic.cljc:164-169`), and that cross-unit calls route through
  `public-bridge`/`entry-body` while intra-unit calls keep the fast path.

## Method

1. Write the equivalence tests FIRST (TDD red phase) — they are the deliverable.
2. Run them; any divergence is a REAL finding: record it precisely (lane A vs lane B,
   scenario, observable) and STOP — do not paper over it.
3. If all green: the proof is the test set plus a short written argument of WHY the three
   lanes are equivalent (what state each carries, where the conversion happens, why no
   guest-observable difference exists). The argument is part of the deliverable.
4. Verify: `clojure -M:test -n yang.clojure-test` and the kit namespaces; `clj -M:kondo
   --lint` on your files. Laptop G0 only — suite-scale runs go to shiva by the orchestrator.

## Report

Begin the final response exactly with:
Completed-GMT / Completed-Local, then: the proof's structure, every test added with its
result, any divergence found (verbatim), the written equivalence argument, commands with
counts, and the commit SHA. End with one line: **PROOF HOLDS** or **PROOF FAILS: <reason>**.
