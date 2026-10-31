Created-GMT: 2026-09-25 10:35:00 GMT
Created-Local: 2026-09-25 17:35:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M2 gate fixes r3)

# Task: yin.vm.linker — M2 Gate Fix Round 3 (two P1 findings)

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; M2 + fix rounds 1-2 are uncommitted). The round-2
confirmation gate confirmed three of your four fixes but returned two
new P1s introduced by the yin/def recognition. Read first:
- collab/1790331800000-architect-linker-m2-fixes-r2-gate.gpt-6-sol.findings.md
- docs/design/yin.vm.linker.md sections 4.1, 4.2

## Work items

1. P1 (linker.cljc:409) — the yin/def binding is recorded at the
   application's ENTRY path, but the walker evaluates the value operand
   before calling yin/def (ast_walker.cljc:232). So (yin/def 'x x)
   incorrectly discharges the read of x. Fix: record the binding at the
   INVOCATION position, exactly as application sites now are (the path
   extended one step past the operands), so a read inside the value
   operand precedes the definition and stays an obligation. Add the
   gate's test: a read-before-write case (yin/def 'x x) — the read must
   remain an obligation (:use-before-definition), and the plain
   (yin/def 'x 5) case must still discharge.
2. P1 (linker.cljc:313) — the query counts every syntactic yin/def call
   as a store, but runtime resolution checks the active store before
   primitives (engine.cljc:54): a prior module write can replace
   yin/def, making a later apparent definition of x a non-store call.
   Fix: discharge from a yin/def call only when its operator is proven
   to resolve to the required primitive without module-store shadowing
   — statically: when the module footprint/manifest context marks
   yin/def as shadowed or the obligation context cannot prove the
   primitive binding, RETAIN the obligation (conservative, fail-closed;
   the reviewer's wording: "otherwise retain the obligation"). Add the
   gate's test: a module that rebinds yin/def — a subsequent apparent
   definition keeps the read as an obligation.
   Constraint: work within the linker's existing obligation/discharge
   data; if the receiver-side context needed for the proof does not
   exist yet (it may be an M4 manifest concern), implement the
   conservative rule (retain unless provably the primitive) and note
   the M4 refinement in a comment. Do NOT invent a new manifest API.

## Constraints

- Touch only src/cljc/yin/vm/linker.cljc and test/yin/vm/linker_test.cljc.
- Pure ASCII, <= 80 columns on added/edited lines; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Preserve all round-2 fixes (they are confirmed) and all existing
  tests.
- Verify: JVM full suite green (baseline 2,055/180,908/0 plus your new
  tests), Node green, Dart green. Sequential, solo. Report exact
  counts.
- If a finding conflicts with the spec, STOP and report BLOCKED with
  the exact conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
