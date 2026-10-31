Created-GMT: 2026-09-25 12:36:00 GMT
Created-Local: 2026-09-25 19:36:00 +0700
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

# dao.stream.serve Q1-Q7 mob consensus (orchestrator's mechanical summary)

Owner instruction (quote): "have codex and GLM-5.3 mob on Q1-Q7 to reach a concenus"
Participants: codex gpt-6-sol (thread 01a0d870-7702-7890-bb87-299065a201b8),
GLM-5.3 (session ce9476ea-84c0-4d54-aef5-4c38a20bb22f). Six rounds
(r1-r6 files under collab/1790337700000..1790339200000). Advice to the
owner only; nothing has been applied to any spec.

| Q | Consensus position | vs Fable's recommendation |
|---|---|---|
| Q1 | Keep the event-medium design; amend UCF 7.4.3 so a served :put resumes on the observed source outcome; an append-unknown leaves the wait undischarged | same, plus append-unknown |
| Q2 | Defer held reads from v1; keep 3.7 additive | differs (Fable: include) |
| Q3 | Opaque composition-assigned peer ids in trusted v1; duplicate :serve/register defined as relay policy; self-certifying later; public relay needs channel auth | same direction, plus duplicate rule and tripwire |
| Q4 | Relay admission is composition-supplied policy, open only in trusted deployments; peer/link lifecycle and caps required; postage a future policy | differs (Fable: accept any) |
| Q5 | Accept OD-2 and OD-3 (a)+(2); accept OD-1 with the write fallback corrected to "effect unknown; no automatic retry"; amend two contract definitions (blocked, anchors) plus a serve 4 declaration | differs (Fable: accept all as drafted) |
| Q6 | Lease-governed via dao.lease; composition retirement is an explicitly weaker pre-grant stopgap; renewals count only on observed source ok; UCF migration acceptance still needs adequate lifetime | same direction, phased |
| Q7 | dao.stream.serve; rename dao.stream.serving to a copy-serving name; fix ws prose | same |

## Final draft contract amendments (codex r6, GLM's three edits applied)

Reading, blocked: "No observation is available through this handle at this
cursor now; the cursor does not advance, and the reader may retry later. A
handle declaring deferred remote observation may return blocked while
fetching even if the source already holds a value or has evicted that
position. If a source observation completes, its outcome is supplied."

Cursors, anchors: "A handle declaring deferred remote observation may
return a source-minted anchor from its last completed source observation.
Its :newest may precede the source's current tail but must never follow it;
its :oldest may precede the currently retained head, in which case a
subsequent next reports the source's gap."

Also settled: gap is only ever relayed, never synthesized; end is relayed or
local (attachment exhausted), per dao.stream.md:603.

## Doc edits this implies (none made)
UCF 7.4.3 (put resume, single :dao.stream.serve/v1 cursor profile); dao.stream.md
(OD-1 corrected, OD-2, OD-3, the two definitions above); dao.stream.ws.md
18-26 prose and outbound-serving prose; dao.stream.serving rename;
dao.lease.md 286-289; daostream-udp-design.md marked superseded.
