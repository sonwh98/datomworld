Created-GMT: 2026-10-12 06:20:00 GMT
Created-Local: 2026-10-12 13:20:00 Asia/Bangkok

# Task: 6a round 4 -- fix the label-byte regression (fresh codex session)

You are a NEW engineer session (gpt-6.1-sol) with no memory of the prior work. Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 6b7e65ed. Do NOT `git add`/commit. Source edits in `src/cljc/yin/vm/ucf/rows.cljc` are authorized; report each as a source change. Foreground only; write each `-n ns` literally; no slow tests. Take a verified byte backup of `rows.cljc` into `collab/` before any edit and before any mutant; restore from it.

Read finding 1 and 3 of `collab/1792040000000-reviewer-srvm-phase6a-r3.claude-fable-5-1.findings.md` (the reviewer, and the round-3 report `collab/1792030000000-vm-engineer-srvm-phase6a-r3.report.md`).

1. MEDIUM, a regression you introduced in round 3 (rows.cljc ~351): `search` builds the leaf key from `(checked-key (checked-encode (subvec (get rows id) 1)))`, so the root task row's own inline slots (profile, status, custody, code map, the result literal in slot 15) are charged to `:label-bytes`. The input's own bytes are not an intermediate label. Probe: `(rows/emit (task-node [[:x (cell-body :s 0)]] [:lit (vec (range 400000))]))` is refused `:resource-limit [:label-bytes]`, while the same task with no cell emits. Fix: apply the `:label-bytes` bound to intermediate labels, signatures, class keys and comparison representations only; bytes that belong to the input row bodies must not count against it (bound them by the existing input-size means or not at all, with a comment saying why), WITHOUT reopening the seven-cell heap exhaustion: `intermediate-label-bytes-are-bounded` must still pass at `-J-Xmx256m`, and the three pinned root ids must not move (`:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937`, `...c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d`, `...8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9`). Add the regression test: the probe above emits and round-trips (decode equal), and the same with a large custody slot (the reviewer's custody variant `(assoc slots 16 (vec (range 4000...)))`), with a cell present. Apply a mutant that charges the root row body again and confirm the new test fails; restore.
2. LOW (rows.cljc ~608-619): `rows->frame` with the default decoder yields in-band markers that the default encoder refuses. Document it in the `rows->frame` docstring (identity holds only when both sides are given a classifier); do not change behaviour.
3. Record in the report, as KNOWN LIMITATIONS (do not fix): the `:work` bound does not bound time (reviewer finding 2: ten equal-body map keys take about 12 minutes before `:work` refuses; a design question for the Architect: charge work by label size); `bytes-key` boxing is about 8x the bound (finding 4); the marker refusal reaches carried code and stream descriptors (finding 6).

Verify (foreground): `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test`, plus the same rows-test run once under `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test` equivalent (use the namespace name `yin.vm.ucf.rows-test` exactly as in the project); Node for rows-test; kondo and cljstyle on changed files. Dart: the orchestrator runs it.

Report to `collab/1792050000000-vm-engineer-srvm-phase6a-r4.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
What changed (source vs test), the regression test failing-then-passing, the mutant killed, exact lane counts, and the known limitations. Do not claim runs that did not occur.
