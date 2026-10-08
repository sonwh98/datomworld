Created-GMT: 2026-09-25 13:45:00 GMT
Created-Local: 2026-09-25 20:45:00 +0700
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

# dao.stream.serve invariant review: codex + GLM-5.3 consensus (orchestrator's summary)

Owner invariant (quote): "any implementation of dao.stream can mechanically be
exposed as a websocket or UDP and be able to traverse NAT in a P2P use case.
There should be no concept of a server or client. its P2P but client/server
model can be implemented by convention. there is no priviledge server or client"
Owner rulings: "Q1 its not privilege . i agree with fable"; "Q2 i agree with fable".
Participants: codex gpt-6-sol (thread 01a0d870-7702-7890-bb87-299065a201b8),
GLM-5.3 (session ce9476ea-84c0-4d54-aef5-4c38a20bb22f), reviewing Fable's
revision (collab/1790339700000-...-invariant-review.claude-fable-5-1.findings.md).
Rounds: r1 parallel, r2 parallel (crossed), r3 codex, r4 GLM (sequenced).
Advice only; nothing is applied to any spec.

## Outcome vs Fable's revision
- Q1, Q5, Q7: unchanged (with notes). Q4: policy moves to the meeting peer's
  composition; caps, lifecycle, admission remain. Q6: operationally affected
  (the meeting peer may retire an inbox pair a migrated task needs).
- Q2: Fable said include held reads in v1. Consensus differs: :serve/hold is
  OPTIONAL and additive in v1, off by default, enabled per deployment, never a
  condition of meeting reachability or acceptance.
- Q3: consensus agrees with Fable's direction, sharpened: the peer's own
  composition mints an opaque, unauthenticated id (128+ random bits, or the
  public-key-hash form); nothing authenticates a peer until key-control and
  channel-binding verification exists.

## Final merged spec-edit list
Codex's 10-row table in
collab/1790341500000-architect-dao-stream-serve-invariant-mob-r3.gpt-6-sol.findings.md
is the list; GLM marked all 10 AGREE in
collab/1790342000000-architect-dao-stream-serve-invariant-mob-r4.glm-5.3.findings.md
with two additions:
1. Row 2 (hold wording): insert "off by default" before "enabled per deployment".
   Codex has not seen this edit.
2. Row 4 (section 7.1): the candidate shape must also carry the REQUEST-stream
   descriptor (Fable's draft has only the announcement descriptor), needed to
   append :meet/call. GLM says row 4 already covers it. Codex has not seen this.
Substance of the ten rows: peer id minting; optional bounded holds; narrower
served surfaces for meeting streams; identity split (:dao.stream/identity names
the channel's logical stream, :serve/peer separate); stable request ids with
meeting-peer dedup and one active pair per peer id; a serving-boundary
observation tying each accepted request to its session and channel attachment;
stated address-exposure and bounded meeting work; punch from the same UDP
socket with a nonce-bearing pong and a restricted-cone test; outer inbox gap
is channel loss, not a final-stream gap, with a stated size limit under double
framing; pin the meeting peer's inbox lifetime during migration acceptance.
