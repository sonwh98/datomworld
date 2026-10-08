Created-GMT: 2026-09-26 06:17:31 GMT
Created-Local: 2026-09-26 13:17:31 +0700
Coding-Agent: claude
Session-ID: e966b9b5-d035-48cd-b629-4274c327e3e0

# Task: yin.vm.linker M4 slice S1, kernel attach-image in the four backends

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 13:17:31 +0700 | Status: active | Rationale: owner directive "use opus for S1 and glm for S2"

Repository: the worktree /Users/sto/workspace/datomworld-m4-s1 (branch m4-s1, from master
b3d0b5a1, which contains M1, Rule R, M2, M3 and the authority policy). Collab files:
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has no collab/). Do NOT
touch any other worktree. A parallel worker (GLM) is doing slice S2 in ../datomworld-m4-s2.

## Owner statement (verbatim quote)

"use opus for S1 and glm for S2"

## What to build (slice S1 of M4)
docs/design/yin.vm.linker.md section 7.3 (installing a module is a scheduler child task; find
the passages on attach-image near lines 1160 to 1240, 1338 and 1523), section 9 (the M4 paragraph),
section 10 (the file box entries for ast_walker.cljc, semantic.cljc, debruijn/stack.cljc,
debruijn/register.cljc and repl.cljc) and section 11 criterion 17. Implement:
- attach-image per kernel: semantic (a fresh local id plus the alias column write); stack and
  register (relocate by the held length, append, one [identity offset length] offset-table row,
  recompute :hash); walker (add rows keyed by id and the [node params] row index);
- hash-safe restore (table-membership check, no :segment write-back from entries);
- grow-only returns (the register frame drops :segment and :hash; return-transition restores
  pc, frames, registers and continuation only);
- the walker :lambda row-id annotation at decode;
- yin.repl append-stack-image and append-register-image (near lines 102 to 141) rewritten over
  attach-image, with the REPL setting :pc itself after attach-image.
Tests (the S1 group of the M4 list, and criterion 17): a parked entry recorded before an attach
restores after it at the same pcs with :segment never assigned from the entry; a register call in
flight while a nested require attaches an image returns into the grown code space with the
attachment intact; two tasks holding images of different lengths require the same module and each
applies its export correctly; relocation of exported closures; a continuation lifted after attach
is rebased by the receiving offset table; a walker closure whose body node is shared by two
:lambda rows with different params lifts from its recorded row and a fabricated pair matching no
row is :unrooted-body. The kernels must stay behavior-neutral for everything that does not call
attach-image: no existing test may change.
Out of scope: engine.cljc changes (require lowering, the install child, module-store routing,
sealed references are slice S3), module.cljc (slice S2), the manifest records (S4), any UCF doc
edit (S5), and yin.repl changes other than the append rewrite. If the spec needs an engine
change for attach-image to work, STOP and report BLOCKED with the exact reason.

## References (unverified aids)
A read-only scoping report describes S1 in its section B: /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md. Check its claims against the tree.

## Files
Expected: src/cljc/yin/vm/ast_walker.cljc, semantic.cljc, debruijn/stack.cljc, debruijn/register.cljc,
src/cljc/yin/repl.cljc (append functions only), and new or edited tests under test/yin/vm/ and
test/yin/. Tell me if you must touch anything else.

## Verification
Baseline at master (record yours first, before any edit): JVM 2,118 tests / 181,744 assertions;
Node 2,031 / 48,576; Dart 1,993 passed. Run the JVM lane and the Node lane yourself (run
mise exec -- npm ci first in this worktree, and Node with mise exec -- clj -M:cljs -m
shadow.cljs.devtools.cli compile slice-peer test). Do NOT run the Dart lane: it writes shared
generated output and another worker is active; the orchestrator runs Dart solo. Report exact counts.

Rules: TDD (failing test first, per docs/agents/build-n-test.md). mise for everything. Lint: mise exec -- clojure -M:kondo --lint <files>; mise exec -- cljstyle check <files>. Pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Cross-host traps: #?(:clj ...) does NOT exclude code from the cljd build, use #?(:cljd nil :clj ...) with :cljd FIRST; #'ns/private-var cross-namespace reflection fails on CLJD, make helpers public; cljd ExceptionInfo, dart:core alias, cljs keyword identity and private mutable fields differ across hosts. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. Rule R is in force: yin/def is syntax, never a name; only yin/def is reserved; no contract stamp is ever assigned to external input. The orchestrator reruns every lane independently and sends the diff to a non-author reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790403451133-vm-engineer-linker-m4-s1-attach-image.claude-opus-5-5.report.md
(same header fields) and return it as your final response, with: baseline and final lane counts,
files changed, tests added, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
