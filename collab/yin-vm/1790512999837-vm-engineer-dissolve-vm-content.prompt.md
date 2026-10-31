Created-GMT: 2026-09-27 18:00:00 GMT
Created-Local: 2026-09-28 01:00:00 +0700
Coding-Agent: claude
Session-ID: generated at dispatch (record in your report)

# Task: Dissolve yin.vm.content — the mint helpers move beside the grammar they enforce

Role: Compiler & AST Engineer

Repository: /Users/sto/workspace/datomworld (branch master; NOTE: a
concurrent session is finishing slice 6 in src/cljc/dao/stream/udp* and
test/dao/stream/udp_test.cljc — those files are out of bounds and their
mid-edit state may color full-lane runs; attribute and re-run once if
a udp failure appears).

Owner principle (verbatim): "yin.vm content is not special content.
they are fundamentally just tuples." The namespace name implies a
content category the architecture denies. Dissolve it.

Current state: src/cljc/yin/vm/content.cljc is 60 lines holding exactly
two public functions — materialize-tree! and materialize-vector! (the
mint side: vm/validate-rows gate, then jing/materialize! per row; the
fetch half was retired into the linker at M2).

Work items:
1. Locate where vm/validate-rows is defined. Move the two functions
   THERE (beside the grammar they enforce), adjusted to the home
   namespace's requires and conventions. Keep the docstrings and the
   validation gate byte-for-byte in meaning.
2. Update every consumer's require (grep yin.vm.content across src/
   and test/: completion.cljc, linker.cljc, dao/jing/content/driver.clj,
   require_test.cljc, linker_step_test.cljc, linker_test.cljc,
   linker_manifest_test.cljc at minimum). No behavior change anywhere.
3. Move test/yin/vm/content_test.cljc's tests to the new home's test
   file (or rename it to match the new home), and DELETE
   src/cljc/yin/vm/content.cljc and the old test file.
4. grep-verify no yin.vm.content reference remains anywhere.

Constraints:
- Do NOT touch dao/jing/content.cljc or anything under dao/jing/
  content/ — the network service layer stays AST-ignorant; that
  separation is the entire point.
- Do NOT touch dao/stream/udp* (concurrent session) or anything else
  outside the yin.vm move.
- ASCII, <= 80 cols on added/edited lines; cljstyle and kondo clean;
  no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (expect the same counts as before —
  this is a pure move; baseline ~2,276/183,262 plus whatever the
  concurrent udp work has added — measure and report), Node green,
  Dart green. Sequential, solo. Exact counts. Attribute any udp-file
  failures to the concurrent session and prove your namespaces green
  with focused runs.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
