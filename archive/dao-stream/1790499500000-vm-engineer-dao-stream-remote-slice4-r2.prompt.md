Created-GMT: 2026-09-27 15:05:00 GMT
Created-Local: 2026-09-27 22:05:00 +0700
Coding-Agent: glm (glm-5.3)
Session-ID: 9a672963-9a21-46d6-8eee-9dac8613bf37

# Task: dao.stream.remote Slice 4, Round 2 — finish dao.jing.content

Role: Stream & Network Engineer

Repository: /Users/sto/workspace/datomworld (branch master @ 83cc8bcd).
The original slice-4 brief is collab/1790492250459-vm-engineer-dao-stream-
remote-slice4.prompt.md — read it fully; it defines the design (plan
section 1), the work items, and the constraints. The previous implementer
(a ZCode subagent) died mid-flight on a model error, leaving PARTIAL work
in the uncommitted tree. Your job: audit that work, finish it, and bring
the slice to the plan's proof.

State audit (verify, then complete):
- src/cljc/dao/jing/content.cljc EXISTS (6 defns; serve-step believed
  present but untested). src/cljc/dao/jing/content/step.cljc EXISTS
  (28KB; the earlier (jing/cbor/decode ...) invalid symbol at :288 has
  been fixed to (cbor/decode ...); audit the whole file for others).
  content/driver.cljc and content/async.cljc EXIST (unaudited).
- src/cljc/dao/jing.cljc: accept-bytes! added (:474; +31 lines).
- src/cljc/yin/vm/linker.cljc: migrated to dao.jing/accept-bytes!
  (+105/-92) — verify the linker M3/M4 tests still pass unchanged in
  outcome.
- NOT DONE: the coordinate rewrite (coordinate.cljc modified but audit
  against section 1's two-descriptor design), the old remote tests are
  NOT ported (no test/dao/jing/content* exists; old
  test/dao/jing/remote_test.cljc and remote/step_test.cljc still
  present), the old module files are NOT deleted
  (src/cljc/dao/jing/remote.cljc, remote/step.cljc, remote/async.cljc
  still exist), and consumers may not all be migrated (yin.repl/link,
  dao.data.btree.storage).

Work: audit every existing new file against the design; fix what is
wrong; port the old tests over the new module; migrate every consumer;
delete the old module files and their tests; prove "no require of
dao.jing.remote remains" by grep. Then the original brief's verification:
JVM/Node/Dart green, sequential, solo, exact counts (the linker
M3/M4, dao.space.index, and btree hydration tests unchanged in outcome).

Constraints unchanged from the original brief (allowed files now include
everything slice 4 owns: content.* files, coordinate.cljc, jing.cljc's
accept-bytes! area, linker.cljc's ingress area, btree/storage.cljc's
async calls, yin.repl/link.cljc if needed, old remote files for deletion,
their test files for porting/deletion). ASCII, <= 80 cols, cljstyle/
kondo clean, no commit/stage/checkout/reset/stash, no diagnostics.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
