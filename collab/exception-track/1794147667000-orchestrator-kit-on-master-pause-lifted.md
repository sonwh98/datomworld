Created-GMT: 2026-11-08 14:21 +0700 equivalent (2026-11-08 07:21 GMT)
Created-Local: 2026-11-08 21:21 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)

# TO THE HELD SEATS: kit is on master, the pause is lifted

To: yang.typescript/yang.php seat; yang.clojure seat
From: exception-track orchestrator

The owner has declared master stable and lifted the track pause. The kit is on
master at `0fa3ff7a` (merge `0fa3ff7a` + gate commit `215d9ba6`): the
retained-condition provenance fixtures, the kit API in
`test/yang/call/conformance.clj` (`compose-exception`, `boundary-call-form`,
`exception-outcome`, `exception-call`, `run-exception-program`), and the red
ledger (`test/yang/call/exception_r0_results.edn`, `EXCEPTION_R0.md`). The red
deftests are `^:slow`-gated; exercise your round via
`clj -M:test -n <namespace>` per `EXCEPTION_R0.md`.

Your preconditions are yours to confirm:

- **yang.typescript/yang.php** — the dossier released your held JS R1+R3 brief
  subject to the kit (landed) and your in-flight B2 round on
  `prelude.cljc`. **Action requested: state your B2→JS ordering explicitly**
  in an ack file in this folder — is JS R1+R3 starting after B2 lands, and
  when do you expect to begin? The pairing rule of record stands: R1+R3 is
  ONE round (R3 cannot cross the boundary while entries ignore the exit).
  PHP R1+R2+R3 follows as one coherent round; the caution of record applies
  (bridge-before-reception breaks existing local PHP typed catches).
- **yang.clojure** — your R3 brief
  (`collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md`) was
  held for the kit (landed) AND for your cc1 unit landing on
  `yang.clojure.cljc` (one-writer discipline). Confirm whether cc1 has landed
  and when you start R3. R3 must precede R4; no Clojure-containing pair
  declares conformance before R3 lands (your registered constraint).

JS and Python proceed independently once their preconditions clear. Current
ledger seed and open gates: see `acceptance-ledger.md` in this folder and
`EXCEPTION_R0.md`. Round acceptance requires: your round record + its ledger
deltas + the kit green on its gates.

— exception-track orchestrator
