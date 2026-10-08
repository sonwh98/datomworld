Completed-GMT: 2026-09-10 06:05:00 GMT
Completed-Local: 2026-09-10 13:05:00 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a089e4-c4a4-7f62-a459-aad08544278b
# Task: dao.jing.dht.node off dao.stream.transit v1 (D8)
Role: Routine Review

## r1 verdict: Request changes (low implementation risk)

Production containment confirmed correct: node.cljc:158 catches the
ExceptionInfo thrown by ensure-portable!, returns nil, bypasses map? and
all handling. UUID is a faithful v1-accepted/v2-rejected tagged value. No
other production v1 dependency remained in dao.jing.dht.node.

Two findings:
- [P1] node_test.cljc:19 still required dao.stream.transit (v1), contradicting
  D8's explicit requirement that both the node and its test move off it;
  made the dao.stream.md consumer-list removal premature.
- [P2] The new test proved liveness only, not that the UUID datagram was
  actually rejected on decode (would also pass if the node handled it).

## r2 verdict: Approved — both findings resolved, no new findings

- P1: test ns now requires dao.stream.transit (portable path) plus
  cognitect.transit directly via a new raw-encode helper (bypasses v2's
  ensure-portable! on encode too, needed to construct the hostile
  datagram). No v1 dao.stream dependency remains anywhere in the
  namespace or its test. A focused codec probe (run by the reviewer)
  confirmed a UUID payload becomes a `~u...` Transit string, round-trips
  through raw cognitect transit to java.util.UUID, and causes
  dao.stream.transit/decode to throw ExceptionInfo with
  :error :non-portable-value.
- P2: test now opens the sending socket with a 200ms setSoTimeout and
  asserts .receive throws SocketTimeoutException before the separate
  liveness check. Reviewer confirmed the node replies to the packet's
  actual source address/port (node.cljc:181), so a missing/odd :from
  cannot misdirect a reply into missing the timeout window by accident.

Caveat recorded by the reviewer: "a timeout alone cannot exclude UDP loss
or scheduling delays, but the verified decode rejection, correct reply
destination, and subsequent successful forced fetch adequately cover this
change." Reviewer trusted the supplied suite results (1459/165540, kondo
clean) and independently ran only the focused codec probe.
