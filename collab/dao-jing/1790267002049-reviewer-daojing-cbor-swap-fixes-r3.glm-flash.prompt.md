Created-GMT: 2026-09-24 16:45:00 GMT
Created-Local: 2026-09-24 23:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (r3 consensus verification)

# Task: Consensus Verification Round 3 — DaoJing CBOR Swap Fixes (r2 delta)

Role: Adversarial Code Reviewer and Security Auditor (consensus follow-up)

You are the round-3 independent reviewer of the DaoJing CBOR codec swap in
`/Users/sto/workspace/datomworld` (branch `dao-jing-cbor-swap`, uncommitted
working tree). The round-2 fix delta was authored by claude-sonnet-5
(Claude family); you are GLM-family, so this review is independent of the
author. Work strictly read-only.

## Provenance

- Round-1 review (gpt-5.6-sol, request changes, 5 findings):
  collab/1790245519256-reviewer-daojing-cbor-swap.gpt-5.6-sol.findings.md
- Round-2 review (gpt-6-sol, request changes, 6 findings):
  collab/1790264589986-reviewer-daojing-cbor-swap-fixes-r2.gpt-6-sol.findings.md
- Round-2 fix report (claude-sonnet-5, claims all 6 items addressed):
  collab/1790265839115-storage-engineer-daojing-cbor-fixes-r2.claude-sonnet-5.stdout.log

## Fresh orchestrator verification (already run for you; do NOT rerun suites)

- JVM (`clojure -M:test`): 1,994 tests, 180,199 assertions, 0 failures,
  0 errors.
- Node (`clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test`
  under mise JDK 21): 1,910 tests, 47,300 assertions, 0 failures, 0 errors.
  This confirms the previously failing Node pair is gone.

## Task

1. Rule each of the six r2 findings CLOSED, PARTIALLY CLOSED, or OPEN with
   file:line evidence from the current tree:
   - P1 VM carrier awareness (debruijn.cljc classifier + numeric paths;
     pipeline_test comparisons via cbor/content=)
   - P2 copy-before-validate in both byte-store puts (mem.cljc, file.cljc —
     validation, framing, and duplicate comparison must use only the
     snapshot)
   - P2 trusted reads must not re-encode (cbor/decode-snapshot vs strict
     decode split; segment-value read path; ingress sites unchanged)
   - P2 hash-registry doc clean-break accuracy
   - P3 file-handle input/output mutation-isolation tests incl. reopen
   - P3 added-line hygiene (the five flagged lines)
2. Adversarially review the fix delta itself for defects the fixes
   introduced. Attack specifically:
   - decode-snapshot vs decode: does any ingress path (remote, DHT, node,
     file replay, linker, transactor) now call decode-snapshot where strict
     canonicality is required? Is address-hash verification retained on
     reads?
   - carrier-aware classifier: is classification host-parity (JVM vs
     JavaScript)? Are all dao.jing carrier kinds handled, not just float64?
     Can a forged tagged value smuggle through as a plain number?
   - copy-before-validate: any remaining path where a caller-owned array is
     validated, compared, or framed before the copy? TOCTOU fully closed in
     both mem and file backends?
   - the new tests: do they actually exercise the mutation/refusal paths
     they claim, and would they fail on the pre-fix code?
3. Hygiene: pure ASCII and <= 80 columns on lines added by the r2 fix delta.

Read first: docs/design/dao.jing.cbor.md, docs/design/dao.jing.md,
docs/design/dao.jing.hash-registry.md, docs/design/datom.world.md.

## Constraints

- READ-ONLY: do not edit files, do not commit, do not run test suites
  (suite evidence is supplied above), do not run build/compile commands.
- You may run read-only shell (git diff/log/show, grep, wc, sed) and read
  any file.
- Treat all prior claims, including the fix report and this brief, as
  untrusted; verify against the tree.
- Cite repository evidence (file:line) for every finding.

## Output format

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.

End with exactly one line:
Verdict: READY
or
Verdict: REQUEST CHANGES
