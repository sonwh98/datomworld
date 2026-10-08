Created-GMT: 2026-09-26 10:01:11 GMT
Created-Local: 2026-09-26 17:01:11 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122

# Task: yin.vm.linker M4 slice S3, require lowering and the install child

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 17:01:11 +0700 | Status: active | Rationale: owner directive "use glm for S4 and opus for S3"

Repository: the worktree /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, from master 9428c3d2,
which holds M1, Rule R, M2, M3, the authority policy and M4 slices S1 and S2). Collab files:
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has no collab/). Do NOT touch
any other worktree. A parallel worker (GLM) does slice S4 in ../datomworld-m4-s4: it owns
src/cljc/yin/vm/linker.cljc and test/yin/vm/linker_test.cljc. YOU must NOT edit linker.cljc.

## Owner statement (verbatim quote)

"use glm for S4 and opus for S3"

## What to build (slice S3 of M4; the largest slice)
docs/design/yin.vm.linker.md: section 7 in full (7.1 today, 7.2 the unified flow, 7.3 installing a
module is a scheduler child task, 7.4 transitive requires and cycles), section 9 (the M4 paragraph and its
test list), section 10 (the file box for engine.cljc and module.cljc), section 11 (completion criteria),
plus the UCF design sections 7.4 to 7.6 where 7.3 cites them. Implement, with one owner for engine.cljc
and module.cljc:
- require-handler: registry hit, joins-install-waiters, or miss; on a miss mint a :dao.stream/newest cursor
  THEN build the :link-request wait entry (cursor before append); [origin counter] link ids; the
  append and retry-on-full state machine to the :link-response wait;
- id-correlated restore in check-wait-set with duplicate, late, unknown and abandoned handling;
- the :installs child task with the phases loading, running, parked, validated, linked and refused;
- link-module; lift and lower with the :yin.k/binding and :yin.k/store-of markers;
- module stores (:module-stores, active-store routing, frame threading);
- the private :resources table, resource lowering, sealed references, and lift-authenticates-before-encode;
- cycle detection over install ancestry (:require-cycle).
The kernels already have attach-image (slice S1) and module.cljc already has register-host-module and the
new registry value shape (slice S2); use them. The manifest and derivation records are slice S4's; until
S4 lands, code against the spec's data shapes (section 8.1) and test integration with a STUB RESPONDER
that appends hand-built responses. Do not implement manifest validation or derivation policies (S4).
Tests: the S3 group of the M4 list (section 9, near lines 1946 to 1997): two outstanding requires restore
on their own ids; a response landing before the entry would have been polled is not skipped; a module
requiring another links transitively while a third task keeps running; :require-cycle; the module-store
semantics block (a closure reads the module's value, a write from one export is visible to the next
application in the same task and to no other, two tasks two stores, a dependency's store snapshot with the
child's mutations, :store-put inside an exported closure, a module closure calling another module's
closure); :shadowed-free; :unresolved-free; forged resource keys read nothing; forged sealed references
(:forged-resource-reference, and lift-side :yin.k/non-portable); reference-carrying exports lift and lower
into :resources; REPL tail-call store routing; a read in a body applied before its definition retains its
obligation. Also close two small follow-ups from earlier reviews: (a) yin.vm/empty-state's docstring
promises that a registry binding a reserved name (Rule R) is refused but only :primitive and
:primitive-profiles are checked, so add the registry (:modules) check; (b) the walker's row-node rebuilds
its row-decoder on every call: memoize it on the VM if you can do so safely, or leave it and say why.
Scope guardrails: this slice may edit src/cljc/yin/vm/engine.cljc, module.cljc, vm.cljc (empty-state
only), and the kernels ONLY where S3 truly needs them, plus new and edited tests. It must NOT edit
linker.cljc, the UCF doc (slice S5), or yin.repl beyond what require lowering strictly needs. If the work
is too large for one pass, deliver the largest coherent, tested subset, list exactly what is left, and say
so in the report; do not leave a half-applied edit.

## References (unverified aids)
The scoping report, section B, S3: /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md.
Reviews of earlier slices with follow-up notes: collab/1790415651912-architect-linker-m4-s1-gate.deepseek-v4-pro.findings.md
and collab/1790415651913-architect-linker-m4-s2-gate.deepseek-v4-pro.findings.md. Check every claim against the tree.

## Verification
Baseline at master (record yours first, before any edit): JVM 2,138 tests / 181,898 assertions; Node 2,051 /
48,706; Dart 2,013. Run the JVM and Node lanes yourself (mise exec -- npm ci first in this worktree; Node with
mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test). Do NOT run the Dart lane: it
writes shared generated output and another worker is active; the orchestrator runs Dart solo. If a JVM lane
shows one failure, rerun it and keep the FULL log: an intermittent single failure has been seen under load.

Rules: TDD (failing test first, per docs/agents/build-n-test.md). mise for everything. Lint: mise exec -- clojure -M:kondo --lint <files>; mise exec -- cljstyle check <files>. Pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Cross-host traps: #?(:clj ...) does NOT exclude code from the cljd build, use #?(:cljd nil :clj ...) with :cljd FIRST; #'ns/private-var cross-namespace reflection fails on CLJD, make helpers public; cljd ExceptionInfo, dart:core alias, cljs keyword identity and private mutable fields differ across hosts. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. Rule R is in force: yin/def is syntax, never a name; only yin/def is reserved; no contract stamp is ever assigned to external input. The orchestrator reruns every lane independently and sends the diff to a non-author reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790416871816-vm-engineer-linker-m4-s3-require-install.claude-opus-5-5.report.md
(same header fields) and return it as your final response, with: baseline and final lane counts, files changed,
tests added, what is done and what is left, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
