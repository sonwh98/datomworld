Completed-GMT: 2026-10-05 09:18:39 GMT
Completed-Local: 2026-10-05 16:18:39 +07
Coding-Agent: claude-sonnet-5-5 (Claude Code)

# Findings: UCF M-next D1 (version-0 address/bytes mismatch)

Changed: src/cljc/yin/vm/ucf/handoff.cljc (new private bytes-address; export-task :address now mints from the emitted bytes), test/yin/vm/ucf/handoff_test.cljc (strengthened the existing "export is canonical bytes under their address" assertions).

Fix: :address was the hex of jing/content-hash over the body, via dao.jing.cbor, not the dao.stream.cbor bytes emitted. It is now the jing segment address (:segment/blake3-<hex>) of the exact :bytes. Body bytes are unchanged.

Red (before fix, JVM, clj -M:test -n yin.vm.ucf.handoff-test): 24 tests, 164 assertions, 2 failures, 1 error. The failure was jing/segment-bytes-match? rejecting the old address against :bytes; the error was parse-segment-address returning nil on the old bare-hex string.
Green (after fix): 24 tests, 164 assertions, 0 failures, 0 errors.
kondo on both files: 0 errors, 0 warnings.
Test asserts: address is a segment address; digest recomputed from :bytes equals the address digest; jing/segment-bytes-match? (the receiver verification predicate) accepts it; different bytes are rejected.

Concerns:
- :address changes shape from a bare hex string to a segment keyword. I did not grep every consumer; the test suite on the JVM lane passed for the handoff namespace only.
- The receiver path covered is jing/segment-bytes-match?, the predicate checkpoint/inspect uses. I did not call checkpoint/inspect: it also decodes with dao.jing.cbor and gates on version 1, so a version-0 body is a different test.
- Only the handoff test namespace on the JVM lane was run; the full JVM suite, cljs and cljd lanes are the orchestrator's. cljstyle not run.
- git status shows only the two edited files plus the staged brief; no git writes.
