Created-GMT: 2026-09-26 00:18:10 GMT
Created-Local: 2026-09-26 07:18:10 +0700
Coding-Agent: deepseek
Session-ID: 3d870bd1-6a9e-4b99-a381-2fe5d583b3ce

# Task: gate for the section 8.2 authority policy (M4 entry criterion), slices A1 and A2

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-26 07:18:10 +0700 | Status: active | Rationale: owner directive "authorize deepseek"; GLM authored the code so a non-GLM reviewer is required and codex is down

You are a HEADLESS plan-mode reviewer. Produce the COMPLETE review in your final response
now. Do not wait for approval, do not ask questions, do not end with a plan or a promise of
a verdict. Read-only: do not edit files. Cite file:line. Do not rerun the test suites.

## Owner statements (verbatim quotes)

"GLM is at 86% and resets 2026-09-27 01:26 so don't let its credits go to waste . see if you can delegate some work to for any of the milestones"
"(b) new namespace"
"authorize deepseek"

## What is under review
Uncommitted work in /Users/sto/workspace/datomworld-ucf-authority (branch ucf-authority, base
0fc931fc = Rule R commit one; it deliberately contains no M2 or M3 code): NEW
src/cljc/yin/vm/linker/authority.cljc (namespace yin.vm.linker.authority, 329 lines), NEW
test/yin/vm/linker_authority_test.cljc (340 lines, 13 deftests), and an edit to
docs/design/yin.vm.linker.md (git diff there) limited to one section 10 file-box line and one
sentence in section 8.2. Read the new files directly. The specification is
docs/design/yin.vm.linker.md section 8.2 (about lines 1680 to 1800: the fail-closed,
proof-carrying name-authority policy) and the M4 entry criterion test list in section 9
(about line 1930). The implementer's report (untrusted):
/Users/sto/workspace/datomworld/collab/1790380597559-vm-engineer-linker-authority-a1a2.glm-5.3.report.md
A read-only scoping report that framed the slices (also untrusted):
/Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md
(section A). It is a PURE function over plain data (envelopes, proofs, an authority map)
returning a name-environment snapshot plus diagnostics; no stream, no format record, no
fetch path, no linker.cljc change. Slice A3 (datom ingestion) is out of scope.

## Orchestrator evidence
The implementer reports (GLM, unverified by me until my run finishes): JVM lane 2,051 tests /
181,068 assertions before and 2,064 / 181,135 after, 0 failures, reproduced twice; kondo 0/0;
cljstyle clean. My independent Node, Dart, JVM, kondo and cljstyle runs of this worktree are IN
PROGRESS and will be reported separately; do not rely on them.

## Orchestrator framing (my reading; challenge it)
GLM says every choice it made where section 8.2 fixes only a concept is fail-closed and
none contradicts the spec. Those are design decisions, so judge each: (1) event and
authority-map KEY NAMES it chose where 8.2 names only concepts; (2) three extra diagnostic
kinds (:malformed-envelope, :undeclared-principal, :no-proof-kind) beyond the four discard
kinds; (3) name entries exist, as :absent, for every shape-valid assertion envelope at the
snapshot; (4) a retraction binds to a honored same-principal assertion by content id; (5) on
equivocation it discards the pair AND every later envelope of that principal, with kind
:equivocation; (6) provenance carries vectors (:yin.module/asserted-by, :yin.link/proof-kind,
:yin.link/snapshot); (7) the result carries :honored-seq to seed the next sequence floor.
It also says a deliberate mutation of the equivocation clause failed exactly the two guarding
assertions, and that the retraction test caught a real bug. I have not read the code.

## What to produce
1. SPEC CONFORMANCE: does the code implement section 8.2 exactly (the two proof kinds, what
   the linker does with a proven, unproven or undeclared assertion, the three per-principal
   passes, retraction binding, equivocation, replay, the sequence floor, snapshot advance as
   a rebuilt state and never an ambient re-read, :ambiguous-name naming every address and
   asserter, provenance)? List any divergence or unimplemented requirement.
2. THE SEVEN INTERPRETIVE CHOICES above: for each, is it right, defensible, or a defect? Which
   are owner or Architect decisions that should be recorded in the spec?
3. FAIL-CLOSED AND SECURITY: any way a bare or forged assertion, a copied attested-log
   assertion, an undeclared principal, a replay, or an equivocating pair can be honored or can
   suppress a legitimate one? Any input-shape hazard (missing keys, non-map, huge input)?
   Determinism: the policy must give one answer for one input (sort keys, no host-dependent
   ordering).
4. PURITY AND CROSS-HOST: pure cljc, no atom, clock, random or host state; no reader-conditional
   trap; works on JVM, Node and Dart.
5. TESTS: do the 13 tests cover every case in the M4 entry list, and can each fail (do they
   assert the exact discard kind or outcome)? Anything in 8.2 that is untested? Is the
   signature stand-in in the tests faithful enough?
6. SCOPE: only the three files touched, linker.cljc untouched, the spec edit minimal and
   accurate.
7. Anything new. Distinguish defects from deferred work. Your overall verdict on whether the
   work is ready to commit as the M4 entry-criterion slices A1 and A2.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
(meaning: whether the work is ready to commit.)
