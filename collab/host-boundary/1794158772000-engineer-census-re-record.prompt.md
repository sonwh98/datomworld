Created-GMT: 2026-11-09 03:4x GMT (real header below)
Coding-Agent: muse
Session-ID: 414f971f-6030-4cf0-abfb-dc0b4196e7c9

# Task: implement the sanctioned H3 gate-census re-record (exact specification)

Role: Engineer (mechanical implementation of a final architect ruling)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-09 | Status: active

## Governing ruling (read fully; it is exact)

collab/host-boundary/1794164000000-architect-census-flags-adjudication.findings.md

Supporting analysis: collab/host-boundary/1794158152000-engineer-gate-census-diff.muse-wonder-1.0.stdout.log

## The sanctioned changes (verbatim from the ruling)

1. docs/design/dao.stream.host-boundary.md:453 area — record the §3.1
   amendment explicitly (the yang.php.antlr. prefix row), and update the
   generator's prefix-row documentation (src/dev/host_boundary/closure.clj:176
   area): add [:deferred-stage "yang.php.antlr."].
2. Regenerate all three EDNs (closure-{clj,cljs,cljd}.edn) via the generator;
   byte-for-byte agreement across all three is a verification requirement
   (generator --check must exit successfully).
3. test/dao/host/gate_test.clj:1010-1011 — union/gate files 175 (was 168).
   Per-lane totals 163/161/159, shared 152 (closure-*.edn or the constants the
   ruling names). Update the descriptive census text at the assertions.
4. List-1: 73 → 72. Delete "src/clj/yang/php/antlr/cst.clj" (:seam-driver).
   Add NO cst.cljc entry. Retain lower.cljc and quoted.cljc as
   :deferred-scope :stays. Add none for builtins/prelude/prelude_module/
   prelude_p9/scope.
5. List-2: unchanged. Exceptions: unchanged (8 files, 12 bare rows).

## Where

Work in a scratch worktree on jupiter (git worktree add --detach /tmp/census-rr
origin/master — origin/master is 186583d9; verify). No commits.

## Verification (the ruling's, verbatim requirements)

- The generator --check exits successfully with byte-for-byte EDN agreement.
- THEN tell the orchestrator (your report) — the orchestrator runs the single
  namespace remotely: clojure -M:test -n dao.host.gate-test on shiva,
  requiring ZERO failures/errors, traversal equality on every lane, the
  stated totals, all 175 files scanned, exact observed-arm equality with the
  72-entry list-1, no category problems.
- Report git diff --stat (expect: generator, 3 EDNs, gate_test.clj, the design
  doc — nothing else) and the generator --check output verbatim.

Also: your sandbox cannot ssh; do NOT run the test yourself, do NOT run
anything slow locally (owner ruling). Report when the worktree is ready.
Begin the final response exactly with Completed-GMT / Completed-Local.
