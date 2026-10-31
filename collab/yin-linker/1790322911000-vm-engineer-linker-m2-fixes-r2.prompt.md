Created-GMT: 2026-09-25 09:45:00 GMT
Created-Local: 2026-09-25 16:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M2 gate fixes r2)

# Task: yin.vm.linker — M2 Gate Fix Round 2 (four findings)

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; the M2 implementation and your fix round are
uncommitted in the working tree). The confirmation gate returned
REQUEST CHANGES with four findings. Read first:
- collab/1790322910000-architect-linker-m2-fixes-gate.gpt-6-sol.findings.md
  (the findings with file:line evidence)
- docs/design/yin.vm.linker.md sections 4.1, 4.2, and the spec line
  the gate cites for :use-before-definition (:547)

## Work items

1. P1 (linker.cljc:301) — the AST definition query recognizes only
   :vm/store-put. The spec (linker.md:413) also requires constant-key
   yin/def applications, which the macro expander produces
   (src/cljc/yin/vm/macro.cljc:691). Extend the query to scan those
   applications so their bindings are definitions, not false free-name
   obligations. Add the gate's test: a yin/def followed by a read.
2. P1 (linker.cljc:271) — prefix ordering places an application site at
   [] before definitions inside its operands, but the walker applies
   only after evaluating the operands (ast_walker.cljc:232). Fix:
   compare application sites at invocation time (the position where the
   application actually happens), so a definition inside an operand
   dominates an application of the enclosing form. Add the AST case of
   the define-then-apply fixture (the existing positive test covers
   only vectors, linker_test.cljc:1055).
3. P1 (linker.cljc:951) — an oversized payload with a mismatched
   address is decoded for refusal evidence before the :max-bytes check
   at :954. A hostile byte store can bypass the pre-decode limit. Fix:
   check the byte length before hashing or decoding, and do not decode
   mismatched bytes for evidence. Add the hostile-store test.
4. P2 (linker.cljc:1022) — :max-parts 0 still admits and fetches a
   single-part root; the quota is checked only when children are
   enqueued (:1000). Fix: reject an invalid zero quota at admission, or
   refuse the root as :parts-limit. Add the test.

## Constraints

- Touch only src/cljc/yin/vm/linker.cljc and test/yin/vm/linker_test.cljc.
- Pure ASCII, <= 80 columns on added/edited lines; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (baseline 2,052/180,898/0 plus your new
  tests), Node green, Dart green. Run them SEQUENTIALLY and solo (Dart
  owns its lane). Report exact counts for all three.
- If a finding conflicts with the spec, STOP and report BLOCKED with
  the exact conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
