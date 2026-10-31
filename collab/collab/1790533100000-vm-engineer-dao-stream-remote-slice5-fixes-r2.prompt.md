Created-GMT: 2026-09-27 20:05:00 GMT
Created-Local: 2026-09-28 03:05:00 +0700
Coding-Agent: claude
Session-ID: resume-of-b571de74-4b8e-47ec-9343-cbfbdccfbd7f

# Task: Slice 5 — Fix the gate's two P1 findings

Role: Stream & Network Engineer

Repository: /Users/sto/workspace/datomworld (branch master; your
slice-5 rewrite is uncommitted). The slice-5 gate returned REQUEST
CHANGES with two P1s. The gate findings:
collab/1790523000000-architect-slice5-gate.gpt-6-sol.findings.md
(read first; both verified against the tree by the gate).

1. P1 (ws.cljc:415, :500, :563; serve.cljc:281): the live endpoint
   still requires a served-path table, sends :ws/disclaim for an
   unknown path, and sends :ws/accept. serve.cljc still constructs
   that table. The deferred WebSocket retirements required for this
   gate are incomplete. Fix per the prior ruling: remove the path
   table and the accept/disclaim wire exchange; let the mirror answer
   not-found per identity; update the affected Node tests.

   CAVEAT from your own earlier analysis: the client's WsHandle only
   leaves :connecting when deliver! receives the :ws/accept frame.
   The architect's slice-5 ruling and this gate both accept the
   retirement anyway (the mirror's own per-identity not-found
   replaces the admission frame) — so ALSO move the client's
   :connecting -> :open transition to connection establishment
   (host open/onOpen calls the :opened! adapter entry), per the
   slice-3 BLOCKED analysis's design, and migrate the tests that
   pinned the old handshake.

2. P1 (rpc.cljc:425, :500): cursor minting turns every result other
   than :dao.stream/ok into an unsettled anchor, then poll! reports
   idle indefinitely — a reflection's reasoned not-found or
   channel-gone errors during minting never reach the terminal
   translation. Fix: distinguish retryable mint results from terminal
   ones, translate terminal failures with the same logic as
   poll-read, and retain the cursor result for diagnostics.

Constraints: your slice-5 rewrite stands (the deletions, the
translation, the lease migration, the shutdown grace — all confirmed
except these two). ASCII, <= 80 cols, cljstyle/kondo clean, no
commit/stage/checkout/reset/stash, no diagnostics, foreground work.
Verify: JVM full suite + Node lane + Dart lane sequentially/solo via
mise, exact counts, 0 failures (embed/main must stay green). End with
Status: COMPLETE or Status: BLOCKED - <reason>.
