Created-GMT: 2026-09-27 18:20:00 GMT
Created-Local: 2026-09-28 01:20:00 +0700
Coding-Agent: claude
Session-ID: generated at dispatch (record in your report)

# Task: dao.stream.remote Implementation — Slice 8 (UCF facade)

Role: VM Runtime Engineer

Repository: /Users/sto/workspace/datomworld (branch master; slices 0-4
are committed; slices 5 and 6 are IN FLIGHT in other sessions — your
file set is disjoint from both, see Constraints).

Implement Slice 8 exactly as the plan defines it. Read first, in order:
- docs/design/yin.vm.universal-continuation-format.md section 7.4.3
  (the pending wait travels as data: the exhaustive pending-wait
  variants with everything a foreign resumer needs) and 7.5.3 (lift and
  lower through remote descriptors), plus the :dao.stream.remote/v1
  cursor profile references (:604, :957) and the acceptance matrix in
  yin.vm.universal-continuation-format.md section 7.11
- docs/design/dao.stream.remote.implementation-plan.md slice-8 row:
  "UCF facade: 7.4.3 and 7.5.3 lift and lower through remote
  descriptors with the :dao.stream.remote/v1 cursor profile." Proof:
  "The acceptance row: a string-backed stream migrates with a kept
  cursor and resumes; forced eviction yields gap with the source's
  cursor."
- docs/design/dao.stream.remote.md section 2.4 (the reflection:
  cursor/next semantics your profile maps) and src/cljc/dao/stream/
  remote.cljc (the committed implementation)
- src/cljc/yin/vm/semantic.cljc's parked-state shape and
  docs/design/yin.vm.semantic.md section 7 (the UCF sections: 7.3
  identity, 7.4 safepoints)

Work items:
1. The facade: lift a parked semantic-machine state into the UCF data
   form (per 7.4.3's exhaustive pending-wait variants, with
   :yin.k/cursor-profiles #{:dao.stream.remote/v1}), and lower UCF data
   back into a resumable state -- through remote descriptors: the
   cursors name positions on dao.stream.remote reflections, and a
   resume re-establishes equivalent waits on the local handles.
2. NEW module (you choose the home and justify it: e.g.
   src/cljc/dao/stream/remote_ucf.cljc or a yin.vm-side name -- note
   the layering: it bridges yin.vm parked state and dao.stream remote
   descriptors; the UCF data form is plain data, so prefer parameterized
   plain-data functions over hard namespace coupling) plus NEW tests.
3. The acceptance row as tests: a string-backed stream migrates with a
   kept cursor and resumes; forced eviction (the source evicts, e.g. a
   small ring) yields gap with the source's cursor.

Constraints:
- NEW files only (the facade module + its test file). Do NOT modify
  remote.cljc, udp*, yin.repl/*, serving/rpc files, or anything under
  dao/jing/ -- concurrent sessions own all of those. If you genuinely
  need a change there, STOP and report BLOCKED with the specific gap.
- The UCF doc's 7.4.3 variant set is exhaustive -- implement every
  variant's lift and lower, not just :stream-next.
- Pure ASCII, <= 80 columns on every added line; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (current baseline ~2,279/183,284/0 plus
  concurrent in-flight deltas -- measure the true baseline at your
  start), Node green, Dart green. Sequential, solo. Exact counts.
  Lane-sharing caveat: concurrent sessions are finishing slices 5 and
  6 -- failures in udp files or yin.repl/serve|connect files belong to
  them; attribute and re-run once.
- Genuine ambiguities: minimal reading, noted in your report. Design
  conflicts with the UCF doc: STOP and report BLOCKED with the exact
  conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
