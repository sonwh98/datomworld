Created-GMT: 2026-11-24 03:4x GMT
Created-Local: 2026-11-24 10:4x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed dispatch)
Destination: codex exec -m gpt-6.1-terra on shiva (debug where the test failed)
Task-ID: 1795510000000-qa-engineer-shiva-node-py-profile-pin

# Task: diagnose (and fix if lane-local) the node-lane py profile pin red on shiva

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-24 10:4x +0700 | Status: active | Rationale: the red lives on the shiva node lane; debug on the failing host per owner directive

## Evidence of record (shiva node lane, run 20261123-2121, head `0d378482`)

`~/ci/datomworld-nightly-node/target-ci/node-shards-20261123-2121/yin.repl.log` — rows in
`yin.repl.python-antlr-test` fail with:

    Python profile artifact differs from its revision pin
    {:status :refused, :reason :yang.semantics/invalid-descriptor,
     :defect :address-mismatch, :address :segment/blake3-d7568c166dbcbf8ac0373bc2f1c38925c9a4f3e7c2a494a5103e164e3f9cd93a}

## Read first

1. `docs/agents/build-n-test.md`; `docs/agents/roles/qa-engineer.md`
2. The canonical-mint-state ruling: `collab/yang-php/1795440000000-…` + countersign thread
   `1795460000000-…` — canonical = FRESH generation; canonical manifest golden = `6e60da0c…`
3. The engineer round that already saw this shape on a checkout:
   `collab/general/1795470000000-engineer-test-runtime-attribution.codex-gpt-6.1-terra.findings.md` (row 7)

## Mission (diagnose FIRST; fix only if lane-local)

1. In your OWN worktree clone on shiva at `origin/master` (fresh, with the lane's
   prerequisite builds — follow the node lane's build steps), reproduce the red: run
   `yin.repl.python-antlr-test` on the NODE leg the way the lane does. Record rc/Ran.
2. Determine WHICH artifact differs: (a) the py profile artifact in the node lane's
   build/cache is stale relative to the committed pins (a lane-setup defect — the lane
   served a cached artifact from before the P3-prime/assert-thunk content moves), or
   (b) the committed pin values on master are the old-mint values awaiting the
   yang.php seat's amended `py-manifest-remint` branch (a pending branch, NOT yours).
3. **If (a)**: fix it on the lane — regenerate/rebuild the profile artifact the lane
   serves (the node lane's build steps), do NOT edit any pin value or test file, and
   verify the namespace goes green on the node leg. File ownership: lane build steps
   only (host-side), no test files, no production src.
4. **If (b)**: STOP — findings only. State that the red is the pending amendment's
   residual and the exact address expected vs actual. Do NOT re-mint pins (the
   amendment branch owns those files; a duplicate re-mint would collide).
5. Also check whether the same artifact staleness affects the dart leg on this host
   (quick check only, note it).

## Verification

1. Your reproduction line (rc/Ran) before and (if (a)) after the fix, on the node leg.
2. If (a): the fixed artifact's provenance (what command regenerated it, two-run check).

## Deliverables

Findings `collab/exception-track/1795510000000-qa-engineer-shiva-node-py-profile-pin.codex-gpt-6.1-terra.findings.md`
in your worktree (which-branch analysis with the address evidence, every rc/Ran line, head
sha). If (a), also the exact lane command the orchestrator should add to the lane build.
Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
