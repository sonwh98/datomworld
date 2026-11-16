Created-GMT: 2026-11-14 05:5x GMT
Created-Local: 2026-11-14 12:5x +07
Dispatched-By: master-green orchestrator seat (interactive; owner-authorized concurrent dispatch)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2), session F-DEPTH
Task-ID: 1794634700000-engineer-depth-probe-caught-stack

# ENGINEER: depth-probe constant-matrix — the python caught-failure :stack row fails

## Evidence

JVM nightly at `23de41a9` (jupiter, 20261114-0709), lane `uncovered`, **26 failures**, all in
`constant-matrix-test` (`test/yang/call/depth_probe_test.clj:437/441` and siblings):

    python caught-failure
    :stack completed at n=256
    expected: (:completed? m)
    actual: false

The depth pillar's constant matrix measures that yin.vm (CESK, heap-allocated continuations)
guarantees recursion depth independent of native stack; the `caught-failure` python row — a
program that throws-and-catches at depth n — is failing its completion expectation. This
family was flagged pre-existing by the refactor seat's relay (scope note: `yang.call.depth-
probe-test`), and the probe was renamed JVM-only at `7320de98`.

## Mission

First DISCRIMINATE (cheap, scoped, jupiter): is this a VM regression (caught-failure at depth
broke in the provenance/decoupling window) or a harness expectation error (the probe's matrix
was authored against a behavior the VM never guaranteed — e.g. depth-parking interacting with
the catch path)? Run the single deftest scoped at 2-3 pins: current master, `4d0d3e70` (the
last JVM-green tip for the engine), and `7320de98` (probe rename). Read
`docs/agents/build-n-test.md`'s CESK/recursion notes and the depth harness docs before
deciding. Then:
- If VM regression: fix the VM path (ownership: `src/cljc/yin/vm/engine.cljc` or the py
  lowering — MINIMAL diff), preserving the CESK no-native-stack invariant.
- If harness expectation: propose the corrected matrix row (what the contract actually
  guarantees for caught-failure at depth) in the findings; implement it ONLY if the correction
  is a strict measurement fix, not a relaxation that hides a real break — otherwise STOP and
  report for a ruling.

## Verification (scoped only)

1. `clojure -M:test -n yang.call.depth-probe-test` (confirm ns; use the exact deftest via
   `-v ns/var-name` while iterating) → constant-matrix 0F.
2. Adjacent unchanged-evidence: one sibling depth/harness ns green.
3. Foundation: `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/depth-probe-caught-stack` from current origin/master; findings
`collab/exception-track/1794634700000-engineer-depth-probe-caught-stack.codex-gpt-6.1-terra.findings.md`
(discriminator table per sha, mechanism, diff, rc/Ran lines, head sha). Never merge to master;
rebase before push if master moves.
