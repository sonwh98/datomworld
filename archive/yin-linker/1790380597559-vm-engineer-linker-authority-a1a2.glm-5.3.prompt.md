Created-GMT: 2026-09-25 23:56:37 GMT
Created-Local: 2026-09-26 06:56:37 +0700
Coding-Agent: glm
Session-ID: f234a52a-3ca3-4b49-80f4-566ca39afcea

# Task: yin.vm.linker section 8.2 authority policy, slices A1 and A2 (M4 entry criterion)

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 06:56:37 +0700 | Status: active | Rationale: owner directive to use GLM's remaining credits on milestone work; owner ruling "(b) new namespace"

Repository: the worktree /Users/sto/workspace/datomworld-ucf-authority (branch
ucf-authority, base 0fc931fc = Rule R commit one; it does NOT contain M2 or M3, and
you must not need them). Collab files: /Users/sto/workspace/datomworld/collab/
(absolute paths; this worktree has no collab/). Do NOT touch any other worktree.

## Owner statements (verbatim quotes)

"GLM is at 86% and resets 2026-09-27 01:26 so don't let its credits go to waste . see if you can delegate some work to for any of the milestones"
"(b) new namespace"

## What to build
The section 8.2 fail-closed, proof-carrying name-authority policy of
docs/design/yin.vm.linker.md (read section 8.2, around lines 1680 to 1800, and the M4
entry criterion test list in section 9, near line 1930, verbatim). It is M4's ENTRY
criterion: no linker may resolve a name from dao.space assertions before it exists
and is tested. It is a PURE function over plain data (assertion and retraction
envelopes, proofs, an authority map) that returns a name-environment snapshot plus
diagnostics. It touches no stream, no format record, no fetch path and no kernel. Its
only repo dependencies are committed: segment-key and canonical-bytes (find them with
grep), and a composition-supplied verify function (no crypto is built here).
Slice A1: envelope shape validation, content-id computation, the signature verify
call-through, the attested-log identity check, the three per-principal passes (dedup by
content id; equivocation detection on equal seq; order and honor against the sequence
floor), and retraction binding by assertion id (:dangling-retraction otherwise).
Slice A2: fold the honored assertions into the per-name outcome (:absent for zero, the
unique manifest address for one, :ambiguous-name naming every remaining address and
asserter for more than one), the provenance data (:yin.link/provenance) on successful
resolution, and snapshot-advance semantics (a rebuilt state, never an ambient re-read).
Discard kinds: :unauthenticated (:no-proof or :bad-proof), :dangling-retraction,
:replay, :equivocation.
Out of scope: A3 (datom ingestion from a dao.space source), the format records, fetch,
the stepped core, require lowering, and any edit to src/cljc/yin/vm/linker.cljc.

## Files (exactly these)
- NEW src/cljc/yin/vm/linker/authority.cljc (namespace yin.vm.linker.authority)
- NEW test/yin/vm/linker_authority_test.cljc (the spec's file box names this test file)
- EDIT docs/design/yin.vm.linker.md: ONLY the section 10 file box, adding the new source
  file line, and one sentence in section 8.2 saying the policy lives in that namespace.
  No other doc edit.

## Tests (write them FIRST, TDD)
The M4 entry test list, verbatim from section 9: a signed assertion by a declared
principal resolves; a bare :asserted-by with no proof is :unauthenticated; a bad signature
is :unauthenticated; an attested log's assertion copied onto another stream is
:unauthenticated; an undeclared principal is ignored; a signed retraction bound to an
assertion's id removes that assertion only, and a retraction naming no assertion or
signed by another principal is discarded; an exact duplicate envelope is honored once and
never counted as equivocation; a replayed sequence and a distinct equivocating pair are
discarded; two proven assertions refuse :ambiguous-name; snapshot advance. Each test must
be able to fail (assert the exact discard kind or outcome, not just that something
happened). The code must be pure cljc that works on JVM, Node and Dart.

## Rules
Read docs/agents/build-n-test.md first. mise for everything: JVM lane
mise exec -- clojure -M:test (run this yourself; Node and Dart lanes are run by the
orchestrator, so you may skip them to save budget); lint mise exec -- clojure -M:kondo
--lint <files>; mise exec -- cljstyle check <files>. Pure ASCII, <= 80 columns on every
line you add or edit, no em dashes. Cross-host traps: #?(:clj ...) does NOT exclude code
from the cljd build (use #?(:cljd nil :clj ...) with :cljd FIRST); avoid cross-namespace
#'private-var access; keyword identity and ExceptionInfo differ across hosts. Do NOT
commit, stage, checkout, reset, stash or merge. If the spec is ambiguous or two readings
conflict, STOP and report BLOCKED with the exact question; do not guess. GLM's weekly
budget is small: be efficient, do not re-read what you can cite, keep your report tight.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790380597559-vm-engineer-linker-authority-a1a2.glm-5.3.report.md
(same header fields) and return it as your final response, with: the JVM lane counts
before and after, files changed, the test list, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
