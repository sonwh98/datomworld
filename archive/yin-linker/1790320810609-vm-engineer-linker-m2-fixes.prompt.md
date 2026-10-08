Created-GMT: 2026-09-25 07:30:00 GMT
Created-Local: 2026-09-25 14:30:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M2 gate fixes)

# Task: yin.vm.linker — Address the M2 Gate Findings (three P1s)

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; M2 is uncommitted in the working tree). The M2 gate
returned REQUEST CHANGES with three P1 findings. Read first:
- collab/1790315000000-architect-linker-m2-gate.gpt-6-sol.findings.md
  (the findings; verify each against the tree before fixing)
- docs/design/yin.vm.linker.md section 4.1 (the format-record slots,
  especially the three position-bearing scanners and their result
  shapes), section 4.2 (the six steps), section 5 (record shapes)

## Work items

1. P1 (linker.cljc:241) — implement the section 4.1 scanner contract for
   ALL FOUR format records: :definitions-fn and :applications-fn as
   position-bearing scanners with the documented shared result shapes
   ({:name sym :at position :conditional? b} / {:at position}), and
   :obligations-fn upgraded from name collections to position-bearing
   records ({:name sym :at position :in-body? b}). Position: a tree
   occurrence path [root-address path] for :yin.ast/code; a pc for the
   vector formats. Then adapt step 5 (linker half) to consume the
   records: join free-name occurrences with declarations respecting
   defined-before-use, :conditional? (if branches, jump target ranges,
   lambda bodies), and dominance between application and definition
   positions as section 4.1 step 5a specifies. The conservative
   degradation rule stands: a scanner returning nil positions retains
   every occurrence as an obligation and discharges nothing inside
   lambda bodies. AST scanners are Datalog over the rows (extend or
   wrap yin.vm/free-names to return occurrence paths); vector-format
   scanners are operand scans by pc (:var against :store-put,
   :load-free against :yin/def call sites, call opcodes for
   applications) with conditional ranges from :jump-if and body
   operands.
2. P1 (linker.cljc:393) — the CLJD branch uses typed/Uint8List without
   the dart:typed_data alias (the Dart lane fails: "Can't resolve type
   typed/Uint8List"). Add the conditional require exactly as
   test/yin/vm/file_test.cljc does (#?@(:cljd [["dart:typed_data" :as
   typed]])) or use an already-imported type; the Dart lane must go
   green.
3. P1 (linker.cljc:525, :489) — worklist bounds: require finite
   composition bounds for every fetch path (or safe finite defaults
   the spec allows); check :max-bytes BEFORE decode, not after; enforce
   the parts budget at enqueue time (a child address exceeding the
   remaining budget is refused :parts-limit naming the bound and the
   address, before it is fetched).
4. Tests for the new scanner semantics: dominance (a definition
   dominates an application after it), early application (application
   before definition retains the obligation), conditional definitions
   (if branches, jump ranges, lambda bodies), per the gate brief.

## Constraints

- Touch only src/cljc/yin/vm/linker.cljc and
  test/yin/vm/linker_test.cljc.
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage; no checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (baseline 2,036/180,792/0 plus your new
  tests), Node suite green, Dart suite green. Run them SEQUENTIALLY and
  solo (Dart owns its lane). Report exact counts for all three.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
