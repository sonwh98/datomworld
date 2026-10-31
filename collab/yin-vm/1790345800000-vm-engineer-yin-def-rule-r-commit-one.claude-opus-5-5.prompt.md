Created-GMT: 2026-09-25 15:05:00 GMT
Created-Local: 2026-09-25 22:05:00 +0700
Coding-Agent: claude
Session-ID: 078d0a96-daf0-4cef-b994-076601d800d6

# Task: implement Rule R, commit one (yin/def is syntax, never a name)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-25 22:05 +0700 | Status: active | Rationale: owner directive "implement with opus"

Repository: the worktree /Users/sto/workspace/datomworld-ucf-rule-r
(branch ucf-rule-r, base 96657a4f = M1, clean). Collab artifacts live in
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has
no collab/). Do NOT touch /Users/sto/workspace/datomworld or
/Users/sto/workspace/datomworld-ucf-phase2 (the latter holds uncommitted M2
work that this change must not include).

## Owner statements (verbatim quotes)

"But i like Rule R: yin/def is syntax never a name"
"yes to 1-3, implement with opus" (1 = require stays out of the reserved set per the next quote; 2 = the four contract bumps may invalidate all stored images with no migration; 3 = M2 stays uncommitted until this commit lands)
"let's stick with B because that is clojure's convention to keep require as an ordinary function"

## The specification (authoritative)

Read first, in full:
- /Users/sto/workspace/datomworld/collab/1790345200000-architect-yin-def-rule-r-final.claude-fable-5-1.findings.md
  (the final consolidated design, signed off READY by codex:
  /Users/sto/workspace/datomworld/collab/1790344600000-architect-yin-def-rule-r-rereview2.gpt-6-sol.findings.md)
- docs/design/datom.world.md, docs/agents/build-n-test.md (TDD; commands)
- the code and docs the design cites (engine.cljc resolve-var 54-73 and
  handle-effect; ast_walker.cljc; vm.cljc; semantic.cljc; debruijn/stack.cljc;
  debruijn/register.cljc; code.cljc; macro.cljc; the specs it lists)

Implement COMMIT ONE (atomic Rule R) exactly as the design's "Commit one"
paragraph states, and nothing more:
- resolve-var refuses yin/def before consulting env or store; the definition
  transition in all four engines never resolves its operator; :define opcodes
  (semantic on the value register, stack popping the value, register with
  destination and source); yin/def removed from the registry and profiles.
- engine/store-put (refusing the reserved key) used by the dispatcher and the
  four direct store instructions, plus the exact-allowlist store-write audit
  as a test/gate check.
- every load-time validator and loader in the design, including the semantic
  datom loader and the walker datom loader; the stack loader starts
  validating; transition-time refusal for raw control.
- required contract stamps: AST v3, semantic v3, stack b2, register r2, in
  yin.vm, the ledger and the UCF constant; every persistent-code loader
  requires and compares a stamp (:contract-missing, :contract-mismatch);
  fresh-code producers (yang, linearizers, the expander, vm/eval, the
  transformer runner) supply the current constant explicitly.
- expander refusals (make-ctx, expand-batch, harvest); constructor checks;
  free-names excludes the definition operator.
- the docs the design lists for commit one.
Reserved set is ONLY yin/def. require stays an ordinary primitive and must not
change.

OUT OF SCOPE (do not do): the M2 linker format records and the linker fetch
contract requirement and deleting the M2 guards (commit two, in another
worktree); anything in the design's M4 bucket. This base has M1's linker, not
M2's; changes in the linker are limited to what is needed to keep existing tests
passing (for example fixtures that used the yin/def call shape).

## How to work
- TDD per build-n-test.md: write the failing test first, then the minimum code.
- FIRST record a baseline: run the three lanes once at the unchanged base and
  report the counts. Then implement. Run lanes sequentially, solo, under mise:
  JVM  mise exec -- clojure -M:test
  Node mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test
  Dart rm -rf test/cljd-out && mise exec -- bb test:cljd   (one process only)
  Lint: mise exec -- clojure -M:kondo --lint <files>; mise exec -- cljstyle check <files>
- Cross-host traps: #?(:clj ...) does NOT exclude code from the cljd build, use
  #?(:cljd nil :clj ...) with :cljd FIRST; #'ns/private-var cross-namespace
  reflection fails on CLJD, make helpers public; cljd ExceptionInfo and
  dart:core alias, cljs keyword identity, and private mutable fields differ across hosts.
- Pure ASCII and <= 80 columns on every line you add or edit; no em dashes.
- Do NOT commit, stage, checkout, reset, stash, or merge. Leave changes in the
  working tree. No leftover diagnostics or debug files.
- If the design conflicts with the tree or is ambiguous, STOP and report
  BLOCKED with the exact conflict. Do not improvise a different design. Note any
  place you deviate or find the design wrong, even if you finish.
- The orchestrator will rerun all lanes independently, then a codex gate reviews.

## Report

Write your final report to
/Users/sto/workspace/datomworld/collab/1790345800000-vm-engineer-yin-def-rule-r-commit-one.claude-opus-5-5.report.md
(same header fields as this prompt) and return it as your final response, with:
baseline and final lane counts, files changed, tests added, the store-write
allowlist, deviations, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
