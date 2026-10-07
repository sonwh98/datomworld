Created-GMT: 2026-09-27 19:00:00 GMT
Created-Local: 2026-09-28 02:00:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 8 Confirmation — your three P1s applied

Role: Lead System Architect (confirmation gate)

Your slice-8 gate's three P1s are applied in the uncommitted working
tree of /Users/sto/workspace/datomworld. The fix report:
collab/1790522280000-vm-engineer-dao-stream-remote-slice8-fixes.prompt.md
-- the applied files are src/cljc/yin/vm/ucf/remote.cljc and
test/yin/vm/ucf/remote_test.cljc (treat the report as untrusted).

Claimed fixes:
1. The facade now lifts the engine's REAL parked shapes (:stream-id,
   :cursor-ref, private :resources per semantic.cljc:106,
   engine.cljc:522): the lift resolves :cursor-ref through :resources
   (cursor-cell), the lower installs fresh resource entries per stream
   reflection and one fresh cursor entry per UCF cell keyed so refs
   remap and share, and rebuilds wait-set entries with the caller's
   register context. All seven 7.4.3 variants remapped to real engine
   shapes (:ffi as :next with :call-id; :ffi-request as the
   :request-sent put with its :datom envelope; link variants carry
   pair resource ids + kept cursor + settle's :name).
2. portable-cursor (:115) publishes a cell only after the kept cursor
   survives a codec round trip (encode/decode/equal); host objects
   refuse :yin.k/unsatisfied before any mint; cursor-codec defaults to
   dao.stream.cbor, composition may override.
3. mint-cell (:196) mints once per distinct engine cell across the
   WHOLE entry set (lift-frame :379): shared engine cells lift to one
   UCF cell and lower back shared; independent cells stay independent.

Tests rewritten (16 tests / 106 assertions) including:
migrates-a-real-parked-machine-and-resumes (real engine park via
yin.vm.semantic/linearize, a :stream-next wait, lift, lower into a
fresh engine state, resume equivalence); forced-eviction gap with the
source's cursor; codec-refusal; shared/independent cell tests.

Verify all three against the tree, hunt for new defects at the seams,
and issue the verdict on slice 8's two files. NOTE: the tree also
carries parallel slice-5 (yin.repl/serve rework, serving/rpc_ws
deletions) and slice-6 (committed) work -- not under this gate.

Orchestrator evidence (do not rerun suites): the facade namespace
16 tests / 106 assertions green on JVM and CLJD; full-lane runs during
its pass showed failures ATTRIBUTED to the concurrent slice-5 rewrite
(embed-test/main-test) and the slice-peer deletion.

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
