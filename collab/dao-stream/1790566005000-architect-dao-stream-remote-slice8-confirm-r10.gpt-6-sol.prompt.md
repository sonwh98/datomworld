Created-GMT: 2026-09-28 03:26:45 GMT
Created-Local: 2026-09-28 10:26:45 +07
Coding-Agent: codex

# Task: Slice 8 Confirmation, Round 10 — the r9 finding fixed (verbatim envelope)

Role: Lead System Architect (confirmation gate)

Your round-9 confirm returned REQUEST CHANGES with exactly one unmet
ruling requirement: the retained FFI request envelope was not
preserved verbatim through lift->lower (lift kept only
:request-op/:request-args; lower rebuilt via apply2/request, losing
the additional keys apply.cljc's open request? explicitly permits).
Scope of THIS gate is unchanged -- exactly four files:
src/cljc/yin/vm/ucf/remote.cljc, test/yin/vm/ucf/remote_test.cljc,
src/cljc/yin/vm/semantic.cljc, test/yin/vm/semantic_test.cljc.
Implementer report (treat as untrusted):
collab/1790565038000-vm-engineer-dao-stream-remote-slice8-fixes-r5.glm-flash.report.md

The fix (verify each against the tree):
1. Lift (lift-retained-request): the pending carries
   :yin.k/request-envelope = the retained envelope verbatim;
   :request-op/:request-args remain as derived views only.
2. Lower (:ffi-request branch): the lowered entry's :datom IS the
   carried envelope (no reconstruction); a pend missing
   :yin.k/request-envelope refuses :yin.k/unsatisfied naming the
   request identity. No silent-reconstruction fallback anywhere.
3. Pins: envelopes dressed with an extra key (:producer/nonce) in the
   migration/round-trip/retained-entry tests; the lift pin asserts
   :yin.k/request-envelope = original incl. the extra key; the
   round-trip pin asserts the lowered :datom = original envelope; a
   refusal test covers the envelope-less pend. Test constructors
   updated to carry envelopes rather than weakening the refusal.

Adversarial focus:
1. Verbatim end-to-end: is there ANY remaining path where a retained
   request's additional keys can be dropped or rewritten (retry path,
   real-engine sweep, transport decode)?
2. The refusal: does the envelope-less pend refuse BEFORE stream
   resolution, naming the request identity? Do all other :ffi-request
   producers (real engine lifts) always set the envelope?
3. The r9 verified items remain intact (carried endpoints, fresh-key
   batch allocation, restore dispatch, the two r8 pins) -- spot-check,
   do not re-derive.
4. Hygiene on added/edited lines (ASCII, <= 80 columns).

Do not rerun suites. Orchestrator evidence on this tree: facade
namespace 22 tests / 212 assertions green on JVM and Node; full Node
2185/49907/0; full Dart 2147 all-pass (collab/slice8-r5-dart.log);
full JVM 2279/183279 with ONE failure in yin.repl.main-test
(collab/slice8-r5-jvm-full.log) -- a PRE-EXISTING intermittent in
slice 5's landed cross-process wire test, outside this gate's scope:
it reproduces on committed master (1 failure in 8 namespace runs,
working tree 2 in 9; the fix touches only the two ucf files, which
are not on that path). Judge the four in-scope files only.

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
