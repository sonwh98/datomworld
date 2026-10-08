Created-GMT: 2026-09-27 14:45:00 GMT
Created-Local: 2026-09-27 21:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (ucf test-behavior pins)

# Task: Pin the five unpinned UCF test behaviors

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master, clean tracked
tree). The UCF Phase 1 review (collab/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md,
finding 4) listed five unpinned behaviors, assigned to the Phase 2
conformance harness. Pin them now, in the existing test files:

1. :reasons for :park and :ffi-call in ucf.cljc's static safepoint map
   (ucf_test.cljc).
2. The FFI-retained dynamic conformance row (:dao.stream/full) in the
   engine/ucf tests.
3. canonicalize's rethrow of non-UCF exceptions (ucf_test.cljc).
4. load-image error precedence for a claimed-hash batch that is
   well-formed but non-canonicalizable (ucf refusal, not hash-mismatch).
5. Multi-frame :yin.k/stack-bases ordering in activation-state
   (multiple k frames).

Read src/cljc/yin/vm/ucf.cljc and the existing ucf_test.cljc first; each
pin asserts the ACTUAL current behavior (these are characterization
tests documenting what is, not what should be — behavior changes belong
to their owners). Add the five test cases next to their siblings.

Constraints: touch only test/yin/vm/ucf_test.cljc and (if a pin needs
the engine harness) test/yin/vm/engine_test.cljc. ASCII, <= 80 cols,
cljstyle/kondo clean, no commit/stage/checkout/reset/stash, no
diagnostics. Verify: the JVM suite green (current baseline
2,272/183,324/0 plus your tests), Node green, Dart green. Sequential,
solo, exact counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
