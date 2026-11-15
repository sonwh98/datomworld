Created-GMT: 2026-11-15 19:41:15 UTC
Created-Local: 2026-11-16 02:41:15 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: restore the jolt lane — :jolt arms for the H1/H2 per-host arms in dao.host
Role: engineer
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-16 02:41 +0700 | Status: active | Rationale: precise kernel-adjacent repair; codex did the workload W wire-format fix well

## Context

yin.repl does not load on jolt at master head. Load chain:
yin.repl -> dao.jing -> dao.jing.cbor -> dao.host.float -> float.cljc:152
`(def ^:private zero (integer/from-native 0))` executes at LOAD TIME; `from-native`'s `:clj` arm
(dao/host/integer.cljc:147) is raw interop: `(BigInteger/valueOf (long n))`. jolt selects the
`:clj` arms (no `:jolt` arm exists) and cannot lower the interop. `promote` (integer.cljc:135-138)
has the same exposure at call time. Introduced by H1/H2 (6b21521c/4b520bb8). Diagnosis records:
collab/jolt/ (the relay + the facade-chain addendum, filed 2026-11-15).

Owner intent: yin.repl depends only on the dao.jing facade; on jolt the default store is mem.

## Scope

1. Add `:jolt` arms to `from-native` and `promote` in `src/cljc/dao/host/integer.cljc`. Study how
   jolt represents big integers first (the bootstrapped jolt's integer kernel / Chez bignums) and
   match what the rest of dao.host.integer's jolt-exercised code expects. Do NOT change the
   :clj/:cljs/:cljd arms.
2. Audit every namespace reachable from the dao.jing facade on jolt — dao.host.{integer,bytes,
   text,hash,float,number?}, dao.jing.cbor, dao.stream (+ base64, observe) — for OTHER H1/H2-era
   per-host arms lacking :jolt coverage that would fail at load time or at the facade's exercised
   paths. Report each finding; fix the ones that block loading the facade and yin.repl.
3. HARD BOUNDARY: do not touch yin.repl sources, dao.jing.dht*, or any test's semantics. No
   top-level conditional requires anywhere (the a6c25106 lesson). Small, surgical arms.

## Acceptance (run in this worktree; mise env first: eval "$(mise env -s bash)")

- `bb bootstrap:jolt` and, if needed, `bb build:jolt-native`.
- With build/tools/jolt-env.sh sourced and $JOLT_HOME/bin on PATH:
  a) `(require 'dao.jing)` loads on jolt;
  b) `(require 'yin.repl)` loads and resolves yin.repl/main;
  c) a mem-store smoke: construct a dao.jing mem store through the facade, put/get one value.
- Report (do not gate on): whether `(require 'dao.jing.dht)` loads with your arms, and if not,
  the exact first failure — for the record only.
- JVM lane unchanged: `bb run-clj -M -e "(require 'dao.jing) (require 'yin.repl) (println :jvm-ok)"`
  or the equivalent test you prefer; no JVM regression.

## Report

Write findings to collab/jolt/<timestamp>-engineer-dao-host-jolt-arms.findings.md in this
worktree: the arms added, the audit table (namespace / arm / disposition), the jolt load
evidence (verbatim), the dht load outcome, and the JVM check. Commit on this branch
(fix/dao-host-jolt-arms) with a message naming the regression and the acceptance evidence.
