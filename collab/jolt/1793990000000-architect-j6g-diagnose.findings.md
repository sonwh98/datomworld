# J6g workload W diagnosis

## Disposition

**The relevant failure mechanism is synchronous work monopolizing the one
owner, before or inside a pump turn. Descriptor rotation does not provide a
wall-time bound on that work. There is a concrete, previously unaccounted-for
source of growing work: the harness submits a fresh program every round and
the REPL synchronously republishes the code index over its entire retained
history before returning to either peer.** This is a composition scheduling
problem, exercised by this harness, rather than evidence of a broken
descriptor rotation.

There is an essential limit on the attribution: **the supplied observations do
not identify the executing instruction in either failed run.** Full-history
publication is a confirmed source-level cost and the leading specific
explanation to measure, not a remotely observed stack sample. Neither “the
indexer definitely spent 69 minutes in this call” nor “the parser definitely
spun” follows from the banner, CPU utilization, and final sample total. This
report does not turn either into a fact. The original W brief and plan v3/v3.1
are absent here; the diagnostic brief and the harness supply the criterion.

No adapter, parser, production owner, or other shared source was edited.
The two permitted harness files now provide the missing attribution evidence
and fix several independently demonstrable measurement errors. The proposed
composition change below is prose for an Architect ruling, not an implemented
fix or a claim that W now passes. Per the brief's edits-only instruction, no
workload, build, or runtime test was executed locally.

## The causal chain, with source locations

Locations in the harness refer to the final files in this workspace; shared
source locations are unchanged.

1. `src/dev/jolt_workload_w.clj:230` submits the same finite source loop anew
   on **every** owner round. Its call at line 451 precedes A's send at line 474,
   B's send at line 505, and both pumps at lines 531 and 536. This is a full
   shell evaluation, not a VM instruction quantum. The loop's five iterations
   do not bound compilation, expansion, indexing, publication, or collection.
2. `src/cljc/yin/repl/driver.cljc:593` reduces `handle-line` over input.
   `handle-line:470` calls `evaluate-locally:172`, which calls
   `repl/eval-input` synchronously at line 174. There is no intervening socket
   poll or scheduler yield.
3. `src/cljc/yin/repl.cljc:1649` increments the shell round and appends a new
   program batch. Lines 1664-1666 run the code indexer and AST indexer before
   the evaluator at line 1673. The code index stage at line 1180 calls
   `yin.repl.index/step`.
4. `src/cljc/yin/repl/index.cljc:236` opens a new publication/transactor for
   the round; creation at line 250 derives the next transaction time by
   scanning the retained log. Each packet receives fresh occurrence ids and
   provenance (`packet->tx-data:96`, `commit:287`), so repeating identical
   source does not make this history disappear. `step:386` commits, writes
   rows, calls `publish` at line 395, then drops the publication. `publish:346`
   calls `transactor/publish!`, drains materialization, and reads back the
   manifest before it returns.
5. The cost is **explicitly documented** at
   `src/cljc/yin/repl/index.cljc:21`: publication rebuilds covered indexes over
   the whole retained history, transactor opening rescans it, round cost
   grows with session history, and total session cost grows quadratically.
   `src/cljc/dao/space/transactor.cljc:117` is the watermark scan;
   `src/cljc/dao/space/index.cljc:564` is the snapshot/build publication path.
   This is actual code on W's path, not a generic speculation about slow VMs.
6. The production composition has the same ordering boundary:
   `src/cljc/yin/repl/main.cljc:1088` steps the shell;
   `run-owner!:1302` completes `step-all` before `sleep!:1313`;
   the jolt sleep policy at line 1437 finally polls/pumps. Production also
   steps more interpreters than this manually assembled harness. This report
   therefore identifies an applicable scheduling risk, not benchmark
   equivalence between the two compositions.

While a synchronous call holds the owner, A's producer cannot send, the
server cannot receive, B's producer cannot dispatch at 10 Hz, B's client
cannot collect replies, and cooperative telemetry cannot print. CPU can be
fully busy doing useful interpretation/publication work throughout. A round
that eventually returns can rotate descriptors correctly and still have
missed every useful service deadline. **The runtime summary does not
contradict the pump's rotation code. It contradicts the inference that a
bounded number of visits implies a bounded service interval.**

There is a second such boundary *inside* the pump. At
`src/jolt/dao/stream/ws/pump.jolt:659` a read synchronously enters parsing;
`parse-input!:622` calls the parser and line 626 handles its event before the
next descriptor. `src/cljc/dao/stream/ws/rfc6455.cljc:337` slices/unmasks the
incoming piece. At completion, `finish-frame:330` concatenates **the entire
message**, using `concat-chunks:250`. Thus even a final 64 KiB read can trigger
a 4 MiB allocation/copy and delivery. `src/jolt/dao/host/bytes.jolt` implements
slice/copy with element loops. This is real synchronous work; there is no
evidence that this particular call, rather than the shell path, was hot in
attempt 21. Importantly, assembly happens on message completion, not on every
visit to a partial message.

## Disposition of the proposed causes

| Candidate | Source evidence and conclusion |
| --- | --- |
| Broken rotation | `pump.jolt:809` rotates the ready handles after `last-id`; `pump!:834` visits each registered member of the snapshot once; `visit-connection!:678` reads once. This is count fairness. No demonstrated descriptor-selection defect; it does not establish time fairness. |
| Repeated 4 MiB codec decode | Not this W path. `jolt.jolt:189` delivers binary payload directly to `:binary!`. A's callback at harness line 297 records its length; B's server callback echoes bytes. Negotiating CBOR does not install `ws/adapter`'s codec decoder here. The real transport decoder is in `src/cljc/dao/stream/ws.cljc:400`, which these raw callbacks bypass. Final RFC message assembly remains a separate possible cost. |
| A's write retry spins | Not the post-banner large-frame leg. `write-all!` at harness line 90 is used for the already-completed HTTP upgrade. `write-some!:131` does one `send-chunk!`; the main loop returns to the other work on `:would-block`. Its no-progress branch has no inner retry loop. The guard remains intact. |
| Poll deadline never wakes relevant fds | `src/jolt/dao/stream/waitset/driver.jolt:176` collects interest, takes the minimum monotonic deadline, polls even when already due, then passes observed readiness to `pump!` at line 201. Each harness wait has a 5 ms cadence. No obvious deadline-only infinite loop explains CPU-bound work outside the wait. A native poll fault is not disproved by reading its caller; it needs a trace showing time spent in the pump/wait phase. |
| Fresh shell evaluation and history publication | Confirmed synchronous, increasing work before both pumps, as traced above. Leading specific composition hypothesis; not yet a measured attribution of the historical hot instruction. |

Seventy replies in 300 seconds also does **not** establish seventy lost
requests or a saturated eight-request window. The producer is on the same
owner and dispatches at most once per returning round. It may have dispatched
very few requests at all. Likewise `p99 ABSENT` is the watchdog's deliberate
invalid-run report after a cap, not evidence of zero replies. The failed-run
summary lacks the sent/outstanding counters, per-phase timing, last active
call in later rounds, and stack samples required to distinguish these cases.

## Harness corrections made

Only `src/dev/jolt_workload_w.clj` and
`src/dev/jolt_workload_w_run.clj` were changed, in addition to this requested
findings file. Existing uncommitted edits in the workspace were preserved.

- **Actual REPL work:** harness lines 450-458 now require the shell round to
  advance and the driver to publish the expected result `5`. Previously the
  harness incremented its own “step” counter after any returning driver call,
  then compared two counters incremented in the same loop. That could accept
  an error, an incomplete submission, or a non-evaluating driver call as
  progress. This is evidence of a completed finite loop, not an invented VM
  instruction counter.
- **Send timestamps and cadence:** lines 498-507 take a fresh clock at
  dispatch and just before send. Previously the timestamp preceded the entire
  synchronous REPL evaluation and A send, so the alleged RTT included time
  before submission to the socket seam. Dispatch uses absolute 100 ms slots,
  starting with a request in the first round; missed slots are skipped, not
  filled with bursts. A slow owner still fails to supply the required load;
  this fix does not pretend that a same-owner producer is independent.
- **Actual overlap:** `a-window` records the first positive write and first
  delivery. Pacing starts with one frame byte and then spreads the remainder
  across the configured duration from that positive write (lines 465-490).
  Only B round trips whose send and reply fall within that interval emit
  `W-SAMPLE` (lines 343-356). Previously replies after A's delivery could fill
  the sample target. The owner now stops after A finishes and the duration
  condition is met, rather than waiting for post-transfer replies to make the
  count look sufficient (line 548). The 500 validity floor, 600 default target,
  4 MiB payload, 250 ms threshold, and five-minute cap remain.
- **Attribution:** `timed-call!:207` records call count, total time and maximum
  time for the REPL, A send, B send, server pump/wait and client pump/wait.
  `W-PROGRESS` records owner rounds, verified loop results, code transactions,
  B sent/completed/outstanding counts, and A's window. Normal telemetry prints
  these every ten seconds when the owner returns.
- **A hung later round:** with `JOLT_WORKLOAD_W_TRACE=1`, every timed phase emits
  a flushed enter/leave pair identifying its round. `step-repl!:230` also
  wraps the public code-index and AST-index steps while that diagnostic
  evaluation runs. These wrappers live only in the harness on its sole owner.
  The supervisor's nested call stack (`jolt_workload_w_run.clj:34`) reports the
  current phase, round and age independently every ten seconds and on cap.
  The old first-round marker is retained under its explicitly historical
  name. Parent timings include child timings; do not sum both as separate CPU
  costs. Per-call output is deliberately diagnostic and a traced run cannot
  PASS (`jolt_workload_w.clj:593`).

The prior five-minute independent watchdog and bounded `:would-block`
handling are preserved. These changes repair measurement and make attribution
possible; they do not hide the expensive shell call by skipping evaluation,
resetting its history, moving pumping before it, shrinking A, or weakening the
registered targets.

## Proposed Architect ruling — no implementation in this round

**Do not amend descriptor rotation or deadline folding on the evidence
available. Rule explicitly that A1's transfer-count bounds do not bound an
owner round's synchronous interpretation, and require the next remote trace
before selecting a performance implementation.**

If `:code-index` dominates, the concrete target is
`yin.repl.index/open-publication:236`, `step:386`, and the
`dao.space.transactor/publish!` → `dao.space.index/publish-index!` path:

1. Replace repeated full-history construction/publication with an explicit
   composition-owned incremental materialization of the appended transaction
   suffix. Keep transaction-time allocation justified by the retained log;
   recovery still derives it from authoritative history. Any in-process
   watermark/index roots are derived state held by that composition, not
   hidden globals or new persisted datom fields. Publish covered roots and
   materialize/verify their referenced content before declaring the round
   published, as today. The accepted per-evaluation publication cadence in
   `yin.repl.index:21` is preserved, not silently changed to periodic publish.
2. If bounded owner service also requires yielding within publication, make
   the pending work an explicit successor value driven by the owner. That
   requires a ruling about what queries observe while a publication is
   pending. Do not silently make a query read an older coverage frontier or
   bypass an indexer. The same applies to converting `repl/make-runner:689`
   from run-to-completion to bounded VM execution: it is a composition
   scheduling change, not a pump fix and not implied authorization to change
   the workload from a fresh finite evaluation per round.

If instead `:server-pump` dominates near A's last bytes, target
`rfc6455/take-payload:333`, `finish-frame:312`, and `concat-chunks:250`.
Preserve the pure parser successor and owned payload semantics; improve byte
slice/unmask/copy throughput or make assembly an explicit bounded resumable
phase. In a resumable version retain offset/state, withhold the message event
until fully assembled, report runnable work through the pump's existing
deadline mechanism, and deliver exactly once. Do not move codec execution
into the pump or deliver partial binary messages. This branch is conditional
on the trace, not a recommendation to optimize both paths speculatively.

Preservation obligations for the ruling:

| Existing obligation | Why this proposal can preserve it / required check |
| --- | --- |
| A1 owner and bounded visits | All successors are owned and advanced by the same owner; no worker callbacks, new threads, polling inside interpretation, or extra descriptor visits. Synchronous interpretation must be budgeted separately from visit count. |
| A2 interest/deadline seam | Keep the existing `{:interest :deadline :pump!}` interface and monotonic clock; newly runnable held parser work makes the existing deadline due. Do not omit polling when a deadline is already due. |
| A3 ordered output | No queue bypass, priority write, send-result change or byte-accounting change. Completion events/echo output retain per-connection order. |
| A4 resource/control bounds | Preserve message-size checks before payload allocation, pong/close budgets, upgrade caps and deadlines. A resumable assembler retains at most the existing bounded message plus its explicit successor, not an unbounded side queue. |
| A5 close/terminal behavior | Preserve first-close ownership, FIFO close placement, expiry, and exactly one terminal. Cancelling pending assembly cannot produce a later message callback after close. |
| A6 lifecycle/isolation | Keep listener ownership, unbind grace, forced host cleanup and per-instance state; release pending derived work with its owning composition/connection. |
| A7 | Its authoritative wording is not present in the supplied plan artifacts or source docstrings. No new wire protocol, portable transport outcome, callback contract or canonical tuple field is proposed; require the Architect to check the actual A7 text before approving. This report does not invent a seventh ruling to claim complete conformance. |

There is no demonstrated logical inconsistency in W itself. With sufficiently
short rounds and a transfer interval admitting 600 exchanges, its numbers are
compatible. This particular composition has no source-level time guarantee
for those rounds; a larger timeout cannot establish 10 Hz or 250 ms. The
fresh-evaluation requirement and the synchronous all-history publication
policy may be incompatible with W on the measured host, but that is a
measurable composition result, not a proof that the registered workload is
universally unsatisfiable. “Within ~60 seconds” also lacks an exact upper
tolerance in the available brief; a five-minute safety cap must not become
that tolerance.

## Remote verification and decisive observations

First run attribution on the orchestrator's qualified remote checkout:

```sh
JOLT_WORKLOAD_W_TRACE=1 bb workload-w:jolt workload-w
```

Preserve stdout, stderr and exit status. Nonzero status in trace mode is
intentional. The watchdog still owns the 300,000 ms cap, including setup.

- Increasing `:code-index` durations and transaction counts identify the
  repeated-publication cost directly. A cap with active `:code-index` locates
  the retained synchronous call there; use a CPU stack sample to distinguish
  transactor scanning, building, encoding, hashing and GC inside it.
- Active `:repl-step` without a nested active index step points to the rest of
  frontend/expansion/evaluation or its surrounding work, not the socket pump.
- Active `:server-pump` near the final send calls for a stack sample in
  parsing/assembly/callbacks versus native poll. That phase intentionally
  includes both wait and pump; its name alone cannot distinguish them.
- Many short calls with `b-sent` low identify producer/owner cadence failure;
  `b-sent` increasing with eight outstanding identifies a different transport
  or response-collection failure. A send stuck at `:would-block` still yields
  each round, leaving the other phase records observable.

After attribution and any separately approved composition fix, run the
untraced criterion:

```sh
bb workload-w:jolt workload-w
```

Require the original targets and the actual transfer interval, rather than
accepting a PASS label alone. The same-owner load generator remains a
limitation: if the intended experiment requires an externally sustained
10 Hz offered load even while the server owner is busy, A/B need independent
producer owners/processes in a separate harness change. The new sent counters
make that limitation visible instead of calling missing submissions lost
network replies.

## Checks performed

- `cljstyle fix` followed by `cljstyle check` on the two permitted Clojure
  files: exit 0.
- `git diff --check -- src/dev/jolt_workload_w.clj src/dev/jolt_workload_w_run.clj`:
  exit 0.
- No workload, runtime tests, build, remote command, adapter edit, commit or
  staging operation. The checkout has no local `build/tools/jolt-env.sh`;
  no other worktree's build artifacts were reused. Runtime behavior of the
  new tracing and measurement guards remains for remote verification.
