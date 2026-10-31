Created-GMT: 2026-09-27 17:30:00 GMT
Created-Local: 2026-09-28 00:30:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: UCF Test Pins Gate — five characterization tests

Role: Lead System Architect (review + sign-off)

The UCF Phase 1 review's finding 4 assigned five unpinned behaviors to
be pinned. A GLM-5.3-Flash implementer added them to
test/yin/vm/ucf_test.cljc in the uncommitted working tree of
/Users/sto/workspace/datomworld (+5 deftests, +14 assertions). These
are characterization tests: each asserts the ACTUAL current behavior
(documenting what is; behavior changes belong to their owners).

The five pins (file:line per the implementer's report,
collab/1790492366251-vm-engineer-ucf-test-pins.claude.findings.md --
treat as untrusted):
1. safepoint-reasons-for-park-and-ffi-call-test (:437) -- pins
   parking-reasons (ucf.cljc:273-282): [[:park]] -> [:park];
   [[:ffi-call :op/add 0]] -> [:ffi :ffi-request].
2. ffi-call-retained-conformance-test (:651) -- pins semantic.cljc's
   :dao.stream/full retained branch (wait-set grows, :request-sent
   true, :reason :put) via a distinct second segment on the same vm.
3. canonicalize-rethrows-non-ucf-exceptions-test (:392) -- pins the
   (throw e) fallthrough (ucf.cljc:229-233): a raw non-ex-info
   exception is not swallowed.
4. load-image-refuses-before-checking-a-claimed-hash-test (:207) --
   pins semantic.cljc:628-643's canonicalize-before-hash-compare
   (:yin.k/non-portable :segment-attribute, not :hash-mismatch).
5. activation-state-multi-frame-stack-bases-test (:722) -- pins
   in-order [0 2 5] stack-bases (ucf.cljc:392).

Verify: each pin asserts actual current behavior correctly (read the
pins and the pinned source), the tests would fail if the behavior
changed, hygiene on added lines. Note the tree also carries parallel
slice-4 and yin.repl link-policy work -- not under this gate.

Orchestrator evidence: a JVM namespace run of yin.vm.ucf-test passed
(23 tests / 123 assertions) mid-development, and later full-lane runs
were green with the pins present (counts moved with parallel work:
latest JVM 2,266-2,278 range across parallel edits).

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
