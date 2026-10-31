Completed-GMT: 2026-09-30 11:08:10 GMT
Completed-Local: 2026-09-30 18:08:10 +07
Coding-Agent: claude
Session-ID: eb3d451a-74ba-462d-8788-df45ee5c25f8
# Report: DHT epic S2 — caller-stepped DHT core on in-memory sockets

Worktree /Users/sto/workspace/datomworld-dht-s2 (branch dht-s2 from dac64b41). Nothing staged or committed.

## STOP ITEM FOR THE ARCHITECT (contract inconsistency, section 10)

The contract says `dao.jing.dht/IDhtNet`, `lookup` and `create-content-dht` are **deleted in S2**, and
`dao.jing.dht.node` is **deleted in S3**. But `src/cljc/dao/jing/dht/node.cljc` (S3's to delete) implements
`dht/IDhtNet` (line 254) and calls `dht/lookup` (331), `dht/create-content-dht` (344) and `dht/node-id` (312), and
`test/dao/jing/dht/node_test.cljc` calls `dht/self-peer`, `dht/fetch-content`, `dht/find-closer`,
`dht/store-content!`, `dht/close-net!`. Deleting the three in S2 breaks the JVM lane (`clj -M:test` loads node_test →
node) until S3 lands. I did **not** reinterpret this: the waiting surface is left in place, at the bottom of
`dao.jing.dht`, under a `LEGACY (pending an Architect ruling)` banner; everything else in S2 is done and does not
depend on it. Resolutions the Architect can pick from:
(a) move `dao.jing.dht.node` + `node_test` deletion into S2 (no UDP DHT between S2 and S3; dev repo, no fleet);
(b) keep the legacy surface until S3 deletes node, and amend the section-10 table;
(c) move the protocol and its helpers into `dao.jing.dht.node` in S2 (a small move, node self-contained until S3).
Once ruled, (a) or (b)/(c) is a ~10-minute follow-up. `dao.jing.md` states this pending status.

## What was built

- `src/cljc/dao/jing/dht.cljc` (portable CLJC): `state` (composition validation: ack-peers 2..k, handles, id,
  traffic/datagrams both or neither; performs no stream op), `step` (section 2 order: ticks → traffic → requests →
  advance → append answers/facts; each read stage bounded by budget; gap adopts recovery cursor and appends
  `/gap`). Pending writes (warm ack in the reading step after exactly ack-peers sends; cold lookup + pings;
  `/sent`, `/unacknowledged` with all six reasons, post-ack best-effort replication to k and `/replicated` with
  `/confirmed`), pending gets (local hit same step; lookup + fetch ≤ alpha; strict verify via
  `dao.jing/accept-bytes!`; cache in :local; `/miss` exhausted/deadline/solo/busy). Lookup as explicit state
  (candidates = table nearest + bootstrap + `:find` hints; answered/dead sets; per-peer tries; dead peers leave the
  table). Section 7 wire through `dao.stream.cbor`, single-datagram only; replies matched by `[observed host, port,
  :q]`; entries only from matched full replies or valid-cookie requests, observed address only. The whole section-8
  cookie protocol: issue on every reply, echo, stateless verify (current or previous epoch), the gate (no work, no
  entry, no storage), the size rule (need-cookie only if ≤ the causing datagram, else silence), first-contact padding
  to ≥256, need-cookie recovery (re-send once, no try consumed), timeout → discard cookie → padded ping, freshness.
  `cookie-for` is the S2 stand-in, public so tests predict it. `publish?` false: writes `/unpublished`, inbound
  store `:ok false`, fetch not found, still answers ping/find and fetches/caches. `store-handle`: validate, oversize
  throw (ex-data `:reason :dao.jing.dht/oversize`) before insert, local insert, one replicate append, local verdict;
  get :local only; close closes :local. Base64 only via `dao.jing/bytes->base64` / `base64->bytes`.
- `src/cljc/dao/jing/content/step.cljc`: `unacknowledged-code`; the exact answer `{:jing/request r
  :jing/unacknowledged reason}` completes as `{:id id :error {:code :dao.jing.content/unacknowledged :reason
  reason}}` (plain put and materialization put; extra keys stay malformed).
- `src/clj/dao/jing/dht/facade.clj` (JVM only): `start!` owns one daemon driver thread = the state's one step owner
  and tick cadence (monotonic ms since start); get miss → `dao.jing.content.driver` waits to its request deadline;
  put is the store handle's (local verdict, no wait); close stops/joins the thread, closes the pair and :local.
- `test/dao/jing/dht/mesh.cljc` (test support): ring-buffer traffic per node + a `MeshSocket` writer carrying raw
  datagram values (section 2 shapes), logging, loss via `:drop?`. No socket, no S1.
- Rewrites: `test/dao/jing/dht_test.cljc` (21 tests / 133 assertions), `test/yin/vm/linker_test.cljc` fake
  `GridNet` → `dht-runtime` (node 1's request/answer pair *is* the linker's content pair; peers seeded with forged
  or honest bytes), and **also `test/yin/vm/linker_step_test.cljc`**, whose DHT test used `lt/grid-handle` /
  `lt/any-address` (not named in the brief, but it consumed the deleted fake net, so it had to move with it).
- `docs/design/dao.jing.md`: backend list entry and status for `dao.jing.dht` (S2), and the Base64 sentence (DHT
  wire uses CBOR byte strings; Base64 only in stream-visible values).
- Not touched: dao.stream.datagram / dao.stream.udp / yin/repl/*, `dao.jing.dht.kad`, `dao.jing.dht.node`.

## Acceptance bullets → tests (dht_test unless noted)

- Acked write used each counted peer's fresh-reply cookie; missing/wrong cookie store causes no work:
  `warm-table-write-is-acknowledged-in-the-step-that-reads-it`, `the-gate-answers-need-cookie-or-silence`.
- Need-cookie ≤ first-contact-bytes; short cookie-less datagram → silence: `need-cookie-reply-never-exceeds-first-
  contact-bytes`, `the-gate-…`, `first-contact-is-a-padded-ping`.
- Exact `:jing/*` shapes, unmodified clients drive a DHT: `the-unmodified-content-client-materializes-through-a-dht`
  (`:inserted`, then `:present` via the verify read), `a-remote-read-is-verified-and-cached`, cold-bootstrap test
  (unacknowledged completion), linker tests; `content.step-test/an-unacknowledged-put-completes-with-its-reason`.
- Warm table: acked in the reading step after exactly ack-peers sends, `/sent`: warm test.
- put-bytes-fn no lookup/no wait; publish/readback against :local: `the-store-handle-returns-the-local-verdict-at-once`.
- Every `/unacknowledged` reason incl. cold bootstrap too-few-peers at the tick deadline then success:
  `every-at-once-unacknowledged-reason` (solo, unpublished, oversize ×2 modes, absent, busy),
  `cold-bootstrap-reaches-too-few-peers-then-succeeds`, `a-query-that-times-out-is-retried-then-its-peer-is-dead`.
- Solo composes no socket streams, holds no pending work: `every-at-once-…` (solo block).
- File-backed local verdict survives restart: `a-file-backed-local-verdict-survives-restart` (all three hosts).
- Time only by appended ticks; no test sleeps: `time-advances-only-by-appended-ticks`; no sleep anywhere in tests.
- JVM facade one step owner, respects deadlines: `dao.jing.dht.facade-test` (with-redefs counts stepping threads:
  exactly 2 facades → 2 threads, never the caller; 150 ms driver timeout returns well under get-ticks).
- dao.jing.md backend list and status updated.

## Red phase (unmodified tree, tests first)

- `clojure -M:test -n dao.jing.dht-test` → `Syntax error … No such var: dht/decode-message` (mesh.cljc).
- `-n dao.jing.content.step-test` → `FAIL in (an-unacknowledged-put-completes-with-its-reason) (step_test.cljc:720)`,
  18 tests, 1 failure.
- `-n dao.jing.dht.facade-test` → `Could not locate dao/jing/dht/facade…`.

## Mutation proofs (each applied, suite run, reverted; `grep` confirms 0 residue; `git diff --stat src` unchanged)

| Mutation | Caught by |
|---|---|
| M1 gate accepts any cookie | the-gate (6+ assertions) |
| M2 ack threshold dec (equivalent in warm case) | timeout/dead-peer test; sharper M2b below |
| M2b send only 1 store before ack | warm test (6), stale, timeout |
| M3 need-cookie ignores the size rule | the-gate (2) |
| M4 every peer fresh | stale-peer (2), timeout |
| M5 fetched bytes unverified | forged-payload test (error) |
| M6 no unacknowledged completion in content.step | step_test:720 |
| M7b store echoes a cookie no reply carried | warm test cookie assertions (4), stale |
(M7 as first written was overwritten by the real cookie, so a no-op; replaced by M7b.)

## Checks (all foreground, in the worktree)

- `clj -M:kondo --lint` on all 9 changed/new files: errors 0, warnings 0.
- `cljstyle check …`: **blocked** by the session permission gate (command required approval; not run).
- Focused JVM: dht-test 21/133, content.step-test, facade-test, linker-test, linker-step-test: 0 failures.
- Full `clojure -M:test`: Ran 2409 tests, 184884 assertions, 0 failures, 0 errors.
- `bb test:cljs`: Ran 2311 tests, 51247 assertions, 0 failures, 0 errors (dht-test, content.step-test, linker tests
  listed as "Testing …").
- `bb test:cljd`: `+2273: All tests passed!`; all 21 `dao.jing.dht-test` vars and the new content.step test ran.

## Interpretive choices the Architect should confirm (none changes the wire or the contract's rules)

1. A need-cookie reply stores the cookie but does not prove or freshen the peer; only a full matched reply records
   the entry and sets freshness. First contact is therefore ping → need-cookie → ping(cookie) → ok.
2. An operation (or query) created before any tick starts its clock at the first observed reading ("a step that has
   observed no tick expires nothing").
3. `cookie-for` hashes the UTF-8 string `"<epoch>|<host>|<port>"` (the contract's `|` read literally).
4. Freshness is `now - seen < cookie-epoch-ticks`.
5. After the ack the same step stops; replication (stores to the remaining k nearest, the finishing lookup) begins on
   the next step, so the reading step sends exactly ack-peers stores. The replication deadline reuses ack-ticks from
   the write's start.
6. A `:jing/put` joining a write already acknowledged is answered at once with its own local verdict.
7. `/local` is nil for replicate requests and for oversize (no insert); `/gap`'s `/stream` is `:requests`,
   `:traffic` or `:ticks`.
8. Tries are counted per (operation, peer); a peer out of tries is dead for that op and removed from the table. One
   outstanding query per (op, peer); pings for stale store targets capped at alpha.
9. Composition cursor keys are `:requests-cursor`, `:ticks-cursor`, `:traffic-cursor`, default `:dao.stream/oldest`.
10. The store handle ignores the replicate append's outcome (a ring only refuses when closed).
