Created-GMT: 2026-09-27 16:40:00 GMT
Created-Local: 2026-09-27 23:40:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 4 fixes)

# Task: Slice 4 — Fix the gate's P2 (exact reply envelope)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 4 is
uncommitted). Read:
collab/1790500926405-architect-dao-stream-remote-slice4-gate.gpt-6-sol.findings.md

P2 (src/cljc/yin/vm/linker.cljc:1177): the linker's found-answer
acceptance checks :jing/bytes validity but tolerates extra keys, while
the old linker required the exact reply envelope and the new stepped
client requires the exact shape (content/step.cljc:327). A malformed
reply can change from :absent to a successful link.

Fix: require the exact #{:jing/request :jing/found? :jing/bytes} key
set in answer-text (both present and no extras). Add the gate's test:
a found answer carrying an extra key pins the malformed-reply outcome
(:absent), and the exact-shape answer still links.

Constraints: touch only src/cljc/yin/vm/linker.cljc and
test/yin/vm/linker_test.cljc (or wherever the answer-text tests live).
ASCII, <= 80 cols, cljstyle/kondo clean, no commit/stage/checkout/
reset/stash, no diagnostics. Verify all three lanes sequentially/solo,
exact counts (current: JVM 2,254/183,121/0; Node 2,163/49,781/0;
Dart 2,123).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
