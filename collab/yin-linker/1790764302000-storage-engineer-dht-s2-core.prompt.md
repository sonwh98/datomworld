Created-GMT: 2026-09-30 10:31:42 GMT
Created-Local: 2026-09-30 17:31:42 +07 (+0700)
Coding-Agent: claude
Session-ID: eb3d451a-74ba-462d-8788-df45ee5c25f8
# Task: DHT epic S2 — caller-stepped DHT core on in-memory sockets (dao.jing.dht state/step, cookies, acks, facade)

Role: Storage & Indexing Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 17:31:42 +07 (+0700) | Status: active | Rationale: complex stepped-core implementation; runs in parallel with S1 (disjoint files per the contract)

WORK TREE: /Users/sto/workspace/datomworld-dht-s2 (branch dht-s2 from dac64b41). Edit ONLY there. Brief:
/Users/sto/workspace/datomworld/collab/1790764302000-storage-engineer-dht-s2-core.prompt.md. Report to
/Users/sto/workspace/datomworld/collab/1790764302000-storage-engineer-dht-s2-core.claude-opus-5-5.report.md.

OWNER DIRECTION (verbatim): "the dht should sit on the raw datagram layer but expose a dao.stream interface"; "udp
datagram api should be foundational and dao.stream on top and the dht is built on a udp base dao.stream". OWNER DECISIONS
(verbatim, items 1-7): /Users/sto/workspace/datomworld/collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.
GOVERNING CONTRACT (frozen, Architect-signed-off S0, commit dac64b41): docs/design/dao.stream.datagram.md and
docs/design/dao.jing.dht.md (esp. section 10 'Slice plan'). Implement ONLY your slice; do not reinterpret the contract —
if it is ambiguous or wrong, STOP and report the exact point (it goes back to the Architect).
Test first: write the acceptance tests, run them on the unmodified tree, record the failures, then implement; prove key
tests by temporary mutation (revert, grep). Portable CLJC: :cljd FIRST in mixed reader conditionals (#?(:cljd ... :clj
...)); no array-map; no cross-ns #'private; shadow-cljs fold trap: refusal helpers return the error object and assert
ex-message/ex-data at the call site. Run every check in the FOREGROUND in your worktree: clj -M:kondo --lint <changed
files>; cljstyle check (say if blocked); focused JVM over your namespaces; full clj -M:test; bb test:cljs; bb test:cljd.
Do not stage or commit.

SCOPE = contract section 10 'S2 — DHT core on in-memory sockets': dao.jing.dht state and step; lookup as state; pending
writes and gets; the store handle (put-bytes-fn returns the local verdict immediately, never an ack); solo mode; the JVM
blocking facade; the :jing/unacknowledged completion in dao.jing.content.step; the whole section-8 cookie protocol with the
S2 stand-in cookie-for (no keyed MAC — that is S4); single-datagram wire only (chunks are S3); peers wired through ring
buffers carrying raw datagram values (no sockets, no S1 dependency); Base64 via the existing dao.jing/bytes->base64 and
dao.jing/base64->bytes (frozen seam; add no codec); delete IDhtNet/lookup/create-content-dht per the contract; rewrite
test/dao/jing/dht_test.cljc and the fake net in test/yin/vm/linker_test.cljc as the contract says; update dao.jing.md's
backend list and status. Meet every S2 acceptance bullet (ack-peers sends with fresh cookies, silence rule, exact :jing/*
shapes driven by the unmodified content clients, each /unacknowledged reason incl. cold bootstrap too-few-peers then
success, solo mode holds no pending work, file-backed local verdict survives restart, time only by appended ticks — no
sleeps, facade has one step owner). Do not touch dao.stream.datagram / dao.stream.udp / yin/repl/*.

Final response begins exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: eb3d451a-74ba-462d-8788-df45ee5c25f8

## Fix round 1 (2026-09-30 18:15 +07, orchestrator): Architect sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790766900000-architect-dht-s2-signoff.gpt-6-sol.findings.md. Its rulings bind:
- On the §10 contradiction, ruling **(b)**: keep the legacy IDhtNet/lookup/create-content-dht and node until S3, which deletes them together. Amend the §10 table in docs/design/dao.jing.dht.md and the dao.jing.md status text to match, and reword the "LEGACY (pending an Architect ruling)" banner to "deleted in S3".
- The ten open choices are all APPROVED as implemented.

Fix every finding (test first; show that each new test FAILS on the current code):
1. HIGH | dht.cljc ~1042 | the advance stage walks every pending write and get, unbounded by step's `budget` (contrary to §2). Carry an advance cursor and cap the work per call. Test with more pending operations than the budget.
2. MEDIUM | dht.cljc ~785 | a matched need-cookie reply can supply any 16-byte cookie. Treat that cookie as untrusted until the full reply. A resend that the socket refuses must count as a failed send, not leave the query pending. Add a refusing-writer test.
3. MEDIUM | dht_test.cljc ~411 | add one explicit fetch-only test: an unpublished node still routes, fetches and caches on a remote miss.
4. MEDIUM | facade_test.clj ~13 | instrument step entry and exit per facade, and assert that its maximum concurrent step count is one.

Run every check in the FOREGROUND: kondo on the changed files; full `clj -M:test`; `bb test:cljs`; `bb test:cljd`.
Report: collab/1790764302000-storage-engineer-dht-s2-core.claude-opus-5-5.report-r2.md

## Fix round 2 (2026-09-30 19:00 +07, orchestrator): r2 sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790766900000-architect-dht-s2-signoff-r2.gpt-6-sol.findings.md.
1. HIGH | dht.cljc ~1057 / ~1032 | `advance` calls `expire-queries` before it selects its budgeted operations, and `expire-queries` walks EVERY pending query, so one step can still process more than `budget` pending work. The `[2 2 1]` test counts only sends. Fix: bound query expiry, either by expiring only the queries that belong to the selected operations or with its own cursor and budget. Test that expiry state changes across several budget-2 steps, and show the test FAILS on the current code.
2. LOW | docs/design/dao.jing.dht.md ~459 (§8) | the text still says a need-cookie reply makes the requester store the cookie. State the approved behaviour: the cookie rides one resend and is stored only after a full reply.
Run every check in the FOREGROUND: kondo; the focused dht, facade and linker tests; full `clj -M:test`; `bb test:cljs`; `bb test:cljd`. The machine is heavily loaded: if a lane is cut off, report it as not finished. Never report a result you did not see.
Report: ...claude-opus-5-5.report-r3.md

## Fix round 3 (2026-09-30 19:25 +07, orchestrator): r3 sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790766900000-architect-dht-s2-signoff-r3.gpt-6-sol.findings.md.
1. HIGH | dht.cljc ~779 | `on-reply` accepts a reply for any pending query without checking the query's deadline. When a write is skipped by the budget at tick 500, its query stays pending. A reply at tick 600 can then prove and freshen the peer, and the write may acknowledge, so the verdict depends on advance scheduling.
   - Fix: reject a reply whose query deadline has elapsed. That owner's budgeted advance then records the failed try.
   - Test: a late reply to an operation skipped by a budget-1 step causes no acknowledgement from that reply (and does not prove or freshen the peer).
2. MEDIUM | dht.cljc ~1049 | each selected operation filters the whole global query map. Index query keys by owner, or otherwise visit only that owner's queries, so a step's work is bounded.
Write tests first and show each one FAILS on the current code; include a mutation proof. Run every lane one at a time in the FOREGROUND: kondo; focused dht, facade and linker tests; full `clj -M:test`; `bb test:cljs`; `bb test:cljd`. Never report a result you did not see.
Report: ...claude-opus-5-5.report-r4.md
