Created-GMT: 2026-09-28 04:03:08 GMT
Created-Local: 2026-09-28 11:03:08 +07
Coding-Agent: claude (claude-fable-5-1)
Dispatch authorization: the OWNER directed this review ("finally have
fable review the dao.stream.remote implementation for sign-off") --
the standing reservation on fable is lifted for exactly this task.

# Task: Final sign-off review — the complete dao.stream.remote implementation

Role: Final reviewer (highest capability; owner-reserved). You are
signing off an epic: the mirror/reflection remote for dao.stream,
implemented as 9 slices on master of /Users/sto/workspace/datomworld,
branch master, HEAD 19c44279. Every slice already passed an
adversarial gpt-6-sol gate (findings in collab/); your job is the
WHOLE against the design -- coherence, completeness, and the places
slice-local gates go blind: seams between slices, vocabulary drift,
invariants that only break in composition.

Authoritative documents (read first):
- docs/design/dao.stream.remote.md -- the design (the contract)
- docs/design/dao.stream.remote.implementation-plan.md -- the 9
  slices and their acceptance criteria

The implementation commits, oldest first:
- 431a269c slice 0: the refused outcome + unrecognized-outcome rule
- ea660cd1 slice 1: ring middleware
- 803c9004 slice 2: the remote core (mirror-step, link, reflection,
  remote descriptors, protocol errors)
- 83cc8bcd slice 3: ws-project + the ws channel composition
- 8894d81f slice 4: dao.jing.remote replaced by dao.jing.content
- 8b5d907b slice 5: yin.repl service over mirror/reflection; the
  copy path (serving, rpc/ws, apply wire envelope) retired
- f692e826 slice 6: the UDP channel
- 31b69653 slice 7: the pair channel, meeting board, relay
- 19c44279 slice 8: the UCF remote facade (yin.vm.ucf.remote) +
  carried-route semantic restore

Owner rulings that shaped the build (all recorded in
docs/agents/routing-status.md and docs/orchestrator-log.md):
- yin.vm.ffi STAYS on dao.stream.apply; remote-FFI-by-composition is
  DEFERRED (revisit post-epic) -- absence is not a gap.
- The retained FFI envelope travels verbatim; an envelope-less
  :ffi-request pend refuses before any attachment (a refusal mints
  no reflection); no receiver-handler fallback
  (collab/1790533100000-architect-ffi-migration-semantics ruling).

Known outstanding item (do NOT count against the sign-off; it is
pre-existing and queued): yin.repl.main-test
killing-the-connection-is-observable-and-requests-are-lost is an
intermittent under load (reproduces 1-in-8 on this same committed
tree, cross-process wire timing). Diagnosed, logged, queued.

Your review, in order of weight:
1. DESIGN COVERAGE: walk dao.stream.remote.md section by section;
   for each requirement, point at the implementing namespace and the
   pinning test -- or at the recorded deferral ruling. Anything
   neither implemented nor deferred is a finding.
2. SEAMS: the composition paths -- yin.repl serve/connect over
   ws-project over the remote core; rpc's reflection-failure
   translation; the UDP channel vs the pair channel's meeting board;
   the UCF facade lifting REAL engine wait shapes (not toy pends)
   and the semantic restore dispatch. Do the slices actually fit?
3. INVARIANTS: identity discipline (self-minted random ids, no
   correlation by guessable keys); error vocabulary consistency
   (not-found / no-surface / oversize / channel-gone vs the
   translated dao.stream.apply/detached vocabulary); bounded
   admission as a live bound (slots returned on every death path);
   the UCF fresh-key allocation vs the receiver's fixed keys.
4. TEST EVIDENCE: the claimed tri-host state is JVM full
   2279/183279 (1 pre-existing intermittent above), Node
   2185/49908/0, Dart 2147 all-pass. Judge whether the test mass
   actually covers the design's promises -- name the uncovered
   promise if you find one.

You may read anything in the repo and run read-only git commands.
Do not run test suites (the evidence above is orchestrator-verified;
running them is not your quota's job). Do not edit files.

Produce your complete findings in this response (you are headless;
nobody will prompt you again): findings with file:line evidence,
each graded P1 (blocks sign-off) / P2 (should fix soon) / P3 (note),
then end with EXACTLY two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
