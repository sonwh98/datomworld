Created-GMT: 2026-09-27 23:00:00 GMT
Created-Local: 2026-09-28 06:00:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (slice-8 test pins)

# Task: Slice 8 — add the two missing test pins from confirm r8

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 8 is
uncommitted). The r8 confirmation verified the implementation
structurally but flagged two missing test pins required by the
FFI-migration ruling. Read:
collab/1790552000000-architect-dao-stream-remote-slice8-confirm-r8.gpt-6-sol.findings.md

1. P1 (test gap, remote_test.cljc:1157 area): the lower-refusal test
   rejects only the RESPONSE attachment. Add: a failed REQUEST
   attachment (the request reflection unservable) returns
   :yin.k/unsatisfied naming the REQUEST identity.
2. P2 (test gap, remote_test.cljc:753 area): the shared-cell test
   asserts a common cursor key and one cursor resource but not that
   co-waiters ADVANCE IN ORDER. Add: two co-waiters on one shared
   cell advance in order (first waiter's value, then second's, the
   shared cursor advancing between them).

Constraints: touch only test/yin/vm/ucf/remote_test.cljc. ASCII,
<= 80 cols, cljstyle/kondo clean, no commit/stage/checkout/reset/
stash, no diagnostics. Verify: the facade namespace green on
JVM+Node+Dart (focused via mise), exact counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
