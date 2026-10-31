Created-GMT: 2026-09-27 19:00:00 GMT
Created-Local: 2026-09-28 02:00:00 +0700
Coding-Agent: claude
Session-ID: generated at dispatch (record in your report)

# Task: dao.stream.remote Implementation — Slice 7 (pair channel, meeting, relay with leases)

Role: Stream & Network Engineer

Repository: /Users/sto/workspace/datomworld (branch master; slices 0-4
committed at 8894d81f; slices 5 and 6 IN FLIGHT in other sessions —
your file set is disjoint from theirs).

Implement Slice 7 exactly as the plan defines it. Read first:
- docs/design/dao.stream.remote.md section 3.3 (the pair channel: a
  pair's `in` gap ends that link's channel; outstanding appends report
  append-unknown; attach! on the same pair descriptor resumes), 
  section 4 (meeting and relay conventions: the meet-requests entry,
  the pair bound and refusing gate, lease-governed pair lifetime,
  relay-as-convention), sections 2.4-2.5 (reflection semantics,
  channel loss, resend-after) and docs/design/dao.lease.md (pair
  leases: served entries and relay pairs as lease subjects)
- docs/design/dao.stream.remote.implementation-plan.md slice-7 row:
  "Pair channel and the meeting and relay conventions with leases:
  dao.stream.remote.pair, dao.stream.remote.meet; a meeting gate
  refuses past the pair bound." Proof: "Two peers behind a simulated
  restricted NAT punch; behind a simulated symmetric NAT they relay; a
  pair whose holder stops renewing answers not-found after duration
  plus tolerance; a reconnect within it finds the pair intact; a
  meeting peer past its bound refuses, not silences." Plus the note:
  slice 7 also proves the pair rule of the spec's 3.3.

Work items:
1. NEW src/cljc/dao/stream/remote_pair.cljc (or pair.cljc — your
   choice, noted): the pair channel — a pair descriptor naming two
   ring buffers (or two remote descriptors), attach! semantics per
   3.3 (attach on the same pair descriptor resumes; a gap on the
   pair's in ends that link's channel with append-unknown reporting).
2. NEW src/cljc/dao/stream/remote_meet.cljc: the meeting board
   convention — a meet-requests entry served like the section-5
   service; a willing peer creates two ring buffers, enters them in
   its mirror table under a lease, and posts their remote descriptors
   to the asking peer; the meeting gate refuses requests past the
   pair bound (a present refusal, not silence).
3. Leases govern pair lifetime: per dao.lease.md, served entries and
   relay pairs are lease subjects; a pair whose holder stops
   renewing answers not-found after duration plus tolerance; a
   reconnect within tolerance finds the pair intact.
4. NEW tests proving the matrix: restricted-NAT punch (simulated),
   symmetric-NAT relay (simulated third peer), lease reclaim as
   not-found, reconnect-within-tolerance, meeting-gate refusal past
   the bound, and the 3.3 pair-gap rule.

Constraints:
- NEW files only (the two modules + their test files). Do NOT modify
  any existing file. Slices 5 (yin.repl/serve+connect rework,
  dao.stream.serving/rpc.ws deletions, rpc.cljc translation) and 6
  (dao.stream.udp*) are in flight in other sessions — their files and
  their mid-state lane failures are out of bounds; attribute and
  re-run once.
- The meeting board's discovery medium is a plain stream/table (no
  new wire shapes); ShiBi authorization is out of scope (the
  capability-agnostic seam stands).
- Pure ASCII, <= 80 columns on every added line; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM + Node + Dart lanes sequentially/solo via mise with
  exact counts; measure the true baseline at your start (the tree's
  baseline moves with the concurrent slices); attribute udp/serve/
  rpc-file failures to the concurrent sessions and re-run once.
- Genuine ambiguities: minimal reading, noted in your report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
