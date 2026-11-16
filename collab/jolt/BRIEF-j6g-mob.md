# J6g workload W harness — clean implementation

Rewrite `src/dev/jolt_workload_w.clj` from scratch implementing the
pre-registered Workload W benchmark for the jolt WebSocket adapter. The
existing file is a tangled placeholder — replace it entirely. Also add the
bb.edn task `workload-w:jolt` that runs it under jolt.

## The design

One jolt process serves two WebSocket peers on 127.0.0.1 while stepping
a submitted REPL loop each owner round. The adapter uses a single owner
thread with cooperative multiplexing (the pump visits ready descriptors
in rotation, one chunk per visit) — race-free by construction, but
serialized. The test measures whether peer B's latency stays bounded
while peer A transfers a heavy message.

## The protocol

- **Server**: binds 127.0.0.1:0 via `dao.stream.ws.jolt/bind!` with
  `:codecs [cbor/profile]`, `:ws/max-frame-bytes 4194304`. Two paths:
  `/peer-a` (raw echo for the heavy transfer, binary frames only) and
  `/peer-b` (echo for the request/reply peer).
- **Peer A**: connects over a raw TCP socket (not through the adapter's
  client path — it uses the adapter's own protocol shape), upgrades, and
  sends one 4,194,304-byte binary message paced over 120 seconds
  (scheduled writes, never burst-then-wait).
- **Peer B**: connects through `dao.stream.ws.jolt/connect!`, sends 1 KiB
  request/reply messages — independently scheduled on a dedicated thread
  (a JVM Thread in the test process, NOT on the owner loop), offering one
  request every 100 ms, at most 8 outstanding, on its own absolute 100 ms
  clock grid. Three timestamps per request: scheduled-at, sent-at,
  received-at. Missed offers tracked in a counter; resetting the schedule
  after a slow owner round is prohibited.
- **REPL**: `yin.repl.main/boot` with the jolt adapter (`yin.repl.host`),
  a submitted loop submitted once, stepped each owner round via
  `driver/repl-step` + `driver/take-outbox`.

## The criterion (pre-registered, implement honestly)

- Peer A: delivered exactly once.
- Peer B: the prospective cohort is the first 600 scheduled requests
  after A's first positive write. Round-trip p99 (send-to-receive) ≤
  250 ms AND scheduled-arrival-to-reply p99 ≤ 250 ms. No reply lost.
- The 500-sample validity floor: fewer is an invalid run.
- Statistical confidence: the exact one-sided 95% binomial upper bound
  q_U = 1 - 0.05^(1/n) ≤ 1% (at n=600: at most 1 violation).
- The REPL step count advances every owner round.

## The guards

- **Watchdog**: a 300-second whole-run cap that terminates with a FAILURE
  report even if the owner thread hangs. Implemented as an independent
  monitor (src/dev/jolt_workload_w_run.clj or equivalent).
- **No-progress guard**: if A's write makes no progress for N consecutive
  attempts, report TRANSFER STALLED and abort the transfer leg.
- **Sampler flush**: flush the running p99/sample-count to stdout every
  10 s so a stuck run leaves evidence.

## Telemetry

W-PROGRESS flushed every 10 s: owner rounds, B
sent/completed/outstanding/missed, A sent bytes + %, A's window
(start/delivery timestamps), the REPL step count, and the index
materialization cursor (path: [:repl :indexer :materialization :cursor]
on the driver state, [:index-publication :cursor] on the shell summary).

## The constraints

- The adapter files (src/jolt/**, src/cljc/yin/repl.cljc,
  src/cljc/yin/repl/*.cljc, src/cljc/yin/repl/main.cljc) are FROZEN —
  read them for the API shapes but do NOT modify them.
- No JVM type hints (^Runnable, ^long etc.) — jolt cannot parse them.
  Use plain fn/Thread/doto as in jolt's own stdlib (io_poller.clj line
  687: `(doto (Thread. wsapoll-loop "jolt-io-poller") (.setDaemon true)
  (.start))`).
- Every namespace in the require block must exist under jolt's source
  roots (src/jolt/ or src/cljc/) — `clojure.tools.reader` and other mvn
  deps are NOT loadable under jolt.
- `tcp/send-chunk!` takes a native buffer pointer (from `ffi/alloc` or
  `with-native`), not a JVM byte array.
- cljstyle clean; no collab/ path in comments.

## Reporting

Write findings to collab/jolt/ (the topic subfolder, not the flat root):
the implementation summary, the gate-by-gate local results (cljstyle
only — the orchestrator runs the workload remotely), and any deviation
from this spec.
