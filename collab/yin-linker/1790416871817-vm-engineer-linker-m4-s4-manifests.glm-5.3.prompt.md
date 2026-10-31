Created-GMT: 2026-09-26 10:01:11 GMT
Created-Local: 2026-09-26 17:01:11 +0700
Coding-Agent: glm
Session-ID: c5f7a3b4-9f48-4bc1-bb6e-58ba0d24b079

# Task: yin.vm.linker M4 slice S4, module manifests and derivation records

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 17:01:11 +0700 | Status: active | Rationale: owner directive "use glm for S4 and opus for S3"

Repository: the worktree /Users/sto/workspace/datomworld-m4-s4 (branch m4-s4, from master 9428c3d2, which holds
M1, Rule R, M2, M3, the authority policy and M4 slices S1 and S2). Collab files:
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has no collab/). Do NOT touch any other
worktree. A parallel worker (Opus) does slice S3 in ../datomworld-m4-s3: it owns engine.cljc and module.cljc.
YOU own src/cljc/yin/vm/linker.cljc and test/yin/vm/linker_test.cljc (plus a new test file if you need one) and
must NOT edit engine.cljc, module.cljc, the kernels or any UCF doc.

## Owner statement (verbatim quote)

"use glm for S4 and opus for S3"

## What to build (slice S4 of M4)
docs/design/yin.vm.linker.md section 8.1 (the module manifest), the passages on reading derivations from a
manifest and on the fallback pair (near lines 1612 to 1655), section 9 (the M4 paragraph), section 10 (the file
box for linker.cljc) and section 11 criteria 18 and 22. Implement in linker.cljc:
- the :yin.module/manifest and :yin.ledger/record format records (the existing four records in M2 are the
  pattern: :identity-fn, :parts-fn, :validate-fn, :obligations-fn and so on), with schema validation for ONE
  schema version;
- contract-before-content and a name check right after manifest verification (:module-name-mismatch for a
  manifest declaring a different name);
- derivation policies :verifying and :trusted using yin.vm.ledger: derive-record (ledger.cljc near line 53)
  and verify-derivation (near line 156) both exist; :unverified-derivation under :verifying, and trust
  :composition under :trusted;
- the :yin.module/index merge into :indexes;
- the two fallbacks (trusted-fallback and verifying-fallback) reading derivations from the manifest.
Tests, the S4 group of the M4 list (section 9, near lines 1946 to 1997): :module-name-mismatch; the three
swapped-image :derivation-mismatch tests plus the fourth, where the :yin.ast/code tree is not the manifest's tree;
:undeclared-free (a free name the manifest never declares); the four-way manifest through the four kernels
(criterion 22); :unverified-derivation under :verifying against trust :composition under :trusted (criterion 18).
By-name resolution needs the authority policy (already merged); by-address manifest fetch does not, so test
by-address first. Keep every M2 and M3 behavior: no existing test may change, the fetch pipeline order (byte cap,
address check, decode) and the admission checks stay exactly as they are, and no contract stamp is ever assigned
to external input.
Out of scope: require lowering and the install child (S3), the kernels, module.cljc, engine.cljc, and any UCF doc.

## References (unverified aids)
The scoping report, section B, S4: /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md
(you wrote it; it may be stale, so check it against the tree).

## Verification
GLM's weekly budget is small (resets 2026-09-27 01:26 +0700): be efficient, do not re-read what you can cite,
keep the report tight. Baseline at master (record yours first): JVM 2,138 tests / 181,898 assertions. Run the JVM
lane before your first edit and after; the orchestrator runs Node and Dart. If the JVM lane shows one failure,
rerun it and keep the FULL log: an intermittent single failure has been seen under load. kondo and cljstyle on
every changed file.

Rules: TDD (failing test first, per docs/agents/build-n-test.md). mise for everything. Lint: mise exec -- clojure -M:kondo --lint <files>; mise exec -- cljstyle check <files>. Pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Cross-host traps: #?(:clj ...) does NOT exclude code from the cljd build, use #?(:cljd nil :clj ...) with :cljd FIRST; #'ns/private-var cross-namespace reflection fails on CLJD, make helpers public; cljd ExceptionInfo, dart:core alias, cljs keyword identity and private mutable fields differ across hosts. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. Rule R is in force: yin/def is syntax, never a name; only yin/def is reserved; no contract stamp is ever assigned to external input. The orchestrator reruns every lane independently and sends the diff to a non-author reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790416871817-vm-engineer-linker-m4-s4-manifests.glm-5.3.report.md
(same header fields) and return it as your final response, with: the JVM counts before and after, files changed,
tests added, what is done and what is left, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
