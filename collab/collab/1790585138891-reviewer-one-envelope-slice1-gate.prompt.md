Created-GMT: 2026-09-28 08:45:38 GMT
Created-Local: 2026-09-28 15:45:38 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
Dispatch authorization: the owner's go for the one-envelope migration
(docs/orchestrator-log.md, 2026-09-28 13:40 +0700 entry) prescribes
the pipeline: implement => local verify => gate => commit only on
GRANTED. This is the slice-1 gate.

# Task: Gate review — one-envelope migration slice 1 (rpc adopts apply envelopes, BREAKING)

Role: Review (gate). You are the independent pre-commit gate for an
uncommitted working-tree diff on master of
/Users/sto/workspace/datomworld, HEAD 84bfb74d.

Reviewer independence: the diff was authored by claude-opus-5-5 (the
claude CLI; the GLM subagent route was abandoned after three
consecutive host concurrency-cap kills, recorded in the brief's
Implementers history). You are GPT-family. Your family is not the
author's.

Authoritative documents (read first):
- collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md
  -- the ruling (yours). Governing sections for this diff: "Ruling",
  "Target contract", "Errors", "Migration order" item 1, the closing
  "Do NOT" list, and your two corrections inside "Ruling" (the
  outstanding cap; the lower remote protocol still envelopes stream
  operations and carries the apply value verbatim).
- collab/1790577198000-vm-engineer-one-envelope-slice1.prompt.md --
  the implementation brief with the required pins.
- collab/1790577198000-vm-engineer-one-envelope-slice1.claude-opus-5-5.report.md
  -- the implementer's report (claims; the orchestrator re-verified
  all of them locally).

The diff under review (uncommitted, `git diff`):
- src/cljc/dao/stream/rpc.cljc       -- the vocabulary swap + cap + reasons
- src/cljc/yin/repl/adapter.cljc     -- backpressure outcome mapping
- src/cljc/yin/repl/serve.cljc       -- request-id accessor at the malformed-request branch
- test/dao/stream/rpc_test.cljc      -- five new pins
- test/yin/repl/adapter_test.cljc    -- wire-envelope assertion
- test/yin/repl/serve_test.cljc      -- apply error-body keys

Scope guard: the brief authorizes exactly these six files plus the
yin.repl connect/driver tests "where affected" (they needed nothing).
Protected per the ruling's Do-NOT list: dao.stream.apply.cljc,
dao.stream.remote.cljc, dao.stream.remote_pair.cljc, anything under
yin.vm, yin's dao.stream.apply/call syntax/AST. Flag any violation.

Specific scrutiny the orchestrator wants your independent eyes on:
1. Boundary predicates: emitted requests are apply requests; accepted
   answers are apply/response? AND rpc safe-id?; an invalid error body
   is rejected (malformed-response diagnostic, request stays
   outstanding, consumed once); an unsafe reply id is its own
   unsafe-response-id diagnostic, consumed once. Nothing may complete
   a request without passing both checks.
2. Open-envelope verbatim rule: the whole accepted answer (extra keys
   included) is what lands in :dao.stream.rpc/response and in
   completions; nothing rebuilds an envelope from fields.
3. rpc-local vs wire: events/diagnostics/completions keep
   :dao.stream.rpc/* output keys with apply envelopes as embedded
   VALUES. The remaining :dao.stream.rpc/id reads in adapter.cljc
   (~:170) and driver.cljc (~:383) read rpc-LOCAL completion records
   -- confirm none of them read a WIRE value.
4. Cap semantics: positive-integer validation (throws otherwise),
   finite documented default 64, backpressure answers before any id
   allocation or append, prior outstanding untouched; and the
   unsent-retry path (a previous :full) must NOT be cap-blocked since
   retrying allocates nothing. Verify the cond ordering in request!.
5. Reason translation: six distinct apply-qualified words;
   no-surface/oversize keep their own terminal words (never collapsed
   into transport-error); channel-gone maps to detached and detached
   alone rebinds; not-found/no-surface/oversize/ended terminal;
   unrecognized reasons fall to generic transport-error; ended comes
   from :dao.stream/end (rpc.cljc ~:455). Confirm no remote reason
   that should be distinct got swallowed by the default branch.
6. Predicates not tightened: apply's open predicates stay open (the
   old rpc request-value? safe-id check is deliberately gone from the
   request predicate; safe-id? is enforced on answers at rpc's client
   boundary and on received requests in yin.repl.serve). Confirm
   nothing now rejects a valid apply producer's opaque id at the
   apply layer, and the safe-id policy remains enforced where the
   ruling demands it.
7. Test quality: do the five new pins actually pin the ruled behavior
   (would each fail on the pre-diff code), and are the assertions
   tight?

Orchestrator-verified evidence (do NOT rerun suites; running them is
not your quota's job):
- Focused JVM (rpc, adapter, serve, connect, driver, embed, observe):
  77 tests, 386 assertions, 0 failures.
- Full JVM suite: 2287 tests, 183331 assertions, 0 failures, 0 errors
  (the yin.repl.main-test intermittent did not fire; it is proven
  pre-existing -- see the 15:22:20 +0700 log entry).
- Node lane: 2193 tests, 49957 assertions, 0 failures.
- cljstyle check on the six files: clean.
- CLJD: the implementer rebuilt the stale build/yin-repl-peer
  (gitignored) with bb build:yin-repl-peer, confirming rpc.cljc
  compiles under ClojureDart; the dart TEST lane is not part of this
  slice's required pipeline and its result is not yet available
  (orchestrator is running it in parallel; the peer-paired JVM tests
  pass). Weigh this as you see fit; it is not a reason to block on
  its own unless you see a CLJD-specific hazard in the diff.
- One JVM test skips for a missing build/ws-project-peer binary
  (a-dart-dialer-reads-a-jvm-server) -- pre-existing skip, unrelated.

You may read anything in the repo and run read-only git commands.
Do not edit files. Do not run test suites.

Produce your complete findings in this response (you are headless;
nobody will prompt you again): findings with file:line evidence,
each graded P1 (blocks commit) / P2 (should fix soon) / P3 (note),
then end with EXACTLY two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
