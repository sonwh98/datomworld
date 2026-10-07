# M2 gate-fix round 2 report (GLM-5.3-Flash subagent, verbatim extract)

All four gate findings fixed, touching only src/cljc/yin/vm/linker.cljc
and test/yin/vm/linker_test.cljc.

1. P1 constant-key yin/def definitions — linker.cljc:301-316:
   tree-definition-query extended with an or-join branch recognizing the
   yin/def application shape the macro expander produces (macro.cljc:691,
   yin/make-def; same recognition as macro.cljc definition-at): an
   :application row whose operator row is [:variable yin/def], with
   exactly two operands whose first is a :literal key, each binding
   joined with its occurrence path. New test
   a-constant-key-yin-def-application-is-a-definition (linker_test.cljc:1048)
   over fixture yin-def-then-read (:133): the definition at [[3 0]], the
   read discharged, only yin/def remaining an obligation the receiver
   resolves through primitives.
2. P1 prefix-ordering dominance — linker.cljc:320-329 and 421-432:
   tree-application-query carries each application row's operand count,
   and tree-application-sites records each site at its invocation
   position: the row's path extended one step past its operands
   (conj path [3 n)), which sorts after every operand step [3 i] and
   before the parent's next step — the position where the walker
   actually applies (ast_walker.cljc:232). A definition inside an
   operand now dominates an application of the enclosing form. The AST
   case added to a-definition-dominating-every-application-discharges-
   a-body-occurrence (linker_test.cljc:1123): the tree verifies closed
   with [] obligations; an-application-before-the-definition-retains-
   the-obligation still refuses; its stale comment corrected.
3. P1 decode-before-byte-cap bypass — linker.cljc:959-988: fetch-one
   checks the byte length against the remaining budget before
   jing/segment-bytes-match? and before any decode, and refuses
   mismatched bytes with the address only — no decoded-value evidence.
   The fetch docstring's step-2 order line updated. New test
   a-hostile-byte-store-cannot-bypass-the-byte-cap (linker_test.cljc:953):
   oversized mismatched bytes refuse :parts-limit/:max-bytes under a
   tight cap and :address-mismatch with no :value under the defaults.
   The two tests asserting the old decoded evidence
   (local-storage-corruption-is-an-address-mismatch,
   tree-with-one-corrupt-child-row-is-an-address-mismatch) now assert
   the value's absence, per the prescribed fix.
4. P2 :max-parts 0 quota gap — linker.cljc:1043-1045: fetch-parts
   refuses a non-positive parts quota as :parts-limit naming :max-parts
   and the root address before the worklist reads anything. New test
   a-zero-parts-budget-refuses-the-root-unread (linker_test.cljc:998).

No finding conflicted with the spec (linker.md sections 4.1, 4.2, :413,
:547).

Verification (sequential, solo, all under mise):
- cljstyle clean; clj-kondo 0 errors / 0 warnings on both files; pure
  ASCII; every line <= 80 columns; no diagnostics; no commit/stage/
  checkout/reset/stash (git state unchanged: the same four working-tree
  modifications as at task start).
- JVM full suite: 2,055 tests / 180,908 assertions / 0 failures
  (baseline 2,052/180,898/0 plus the 3 new tests).
- Node: 1,971 tests / 47,935 assertions / 0 failures.
- Dart: 1,933 passed, "All tests passed!", exit 0.

Status: COMPLETE
