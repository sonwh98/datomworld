Created-GMT: 2026-11-09 20:35 GMT
Created-Local: 2026-11-10 03:35 +0700
Coding-Agent: kimi
Session-ID: session_e1846bac-e154-4d43-b76e-0fbc86438799 (resume, round 3)

# Task: confirm the round-3 corrections (final review round)

Role: Review (adversarial, follow-up — resume your session)

Your round-2 confirmation left two items: finding 4's remaining half (the
kit's prelude publication sites still silent-nil) and the new P2 (the
throw fired outside the per-row reporting path). The author's round 3 is
in `/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.gemini-3.8-flash-high.findings.md`
under "# Round 3". The diff is uncommitted in
`/Users/sto/workspace/datomworld-xlink`, now three files:
`test/yang/call/conformance.clj`, `exception_legs.clj`,
`exception_probes.clj` (+113/−68).

Read-only again (write only your own findings file). Verify:

1. **Universal fail-fast**: the private `publish-guest!` helper guards all
   four publication sites (`published-php`, `published-py`,
   `published-clj`, `publish-donor!`); no silent-nil path remains anywhere
   in the kit — including any site your round-2 review named.
2. **Per-row error routing**: `kit/error-results` + the
   try/catch-Throwable wrappers in `run-exception-program`, `legs/run-pair`,
   `legs/run-leg`, `probes/run-frontend`, `probes/run-fixture` deliver a
   publication refusal as per-backend Throwable results that reach the
   tests' `instance? Throwable` branches with `:host/data` carrying the
   complete refusal map. Check the catch placement cannot swallow
   non-refusal exceptions or mask genuine per-row failures, and that the
   `run-frontend` fixture-isolation semantics (one poisoned fixture not
   erasing others) survive the restructure.
3. **No behavior change on the green/red path**: the wrappers only catch
   what previously escaped; the counts are unchanged.
4. **Orchestrator verification note** (not a substitute for your static
   review): the orchestrator re-ran all three focused tests serially on
   the r3 diff — 9 F / 0 E, 6 F / 18 E, 18 F / 21 E — each matching the
   ledger of record.

Deliverable: append "## Round 3 confirmation" to YOUR findings file
`/Users/sto/workspace/datomworld/collab/exception-track/1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.findings.md`,
one disposition per point with file:line evidence, ending with a one-line
verdict: ACCEPT or CHANGES-REQUIRED.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
