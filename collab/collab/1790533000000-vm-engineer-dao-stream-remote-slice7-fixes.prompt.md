Created-GMT: 2026-09-27 20:00:00 GMT
Created-Local: 2026-09-28 03:00:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 7 fixes)

# Task: Slice 7 — Fix the gate's four findings

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 7 is
uncommitted: remote_pair.cljc, remote_meet.cljc + their tests). The
gate returned REQUEST CHANGES with four findings. Read:
collab/1790523000001-architect-slice7-gate.gpt-6-sol.findings.md

1. P1 (remote_meet.cljc:228): capacity is published before draining,
   then every queued pair is granted -- several requests accepted
   against one count can exceed max-pairs. Fix: enforce capacity at
   GRANT time; refuse each excess request through the reflection (a
   present refusal). Test: multiple queued requests at the boundary.
2. P1 (remote_meet.cljc:231): the interpreter drains to blocked with
   no per-step fanout limit, violating the bounded meeting-work
   contract. Fix: a configured per-step limit; preserve the cursor;
   remaining requests handled on later steps. Test: more queued
   requests than the limit, remainder handled next step.
3. P1 (remote_meet_test.cljc:206, :272): the NAT proofs don't
   simulate. Fix: model restricted NAT (outbound-only: a peer's
   requests reach the meeting peer but direct peer-to-peer is
   refused by the simulation) and symmetric NAT (forced relay), and
   assert the resulting sequences: direct punch after the meeting,
   relay exchange through the granted pair, not just board postings
   and one-way reachability.
4. P1 (remote_meet.cljc:202, :192 + the reconnect test :370): the
   board posts a minted lease ID without a lease grant or judging
   path, and pair grants enter the judge directly -- the holder
   cannot obtain a complete grant and renew through the convention.
   Fix: carry a COMPLETE grant to the holder (per the ruling on the
   lease_composition sketch) and wire renewal carriage into the
   judge; test a reconnect and renewal through that path.

Constraints: touch only remote_meet.cljc, remote_pair.cljc, and their
test files. Preserve everything the gate confirmed (the 3.3 pair-gap
rule test, channel loss, append-unknown, attachment on the same
descriptor). ASCII, <= 80 cols, cljstyle/kondo clean, no commit/stage/
checkout/reset/stash, no diagnostics. Verify JVM+Node+Dart
sequentially/solo with exact counts (the tree carries concurrent
slice-5 work -- attribute yin.repl/serve-file failures and re-run
once).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
