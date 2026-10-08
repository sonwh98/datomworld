# Brief: DHT epic S5 — REPL integration

Role: Engineer (docs/agents/roles/).
- Implementer: claude-opus-5-5. Sign-off: gpt-6-sol, with gemini-3.1-pro-high as fallback.
- Worktree: /Users/sto/workspace/datomworld-dht-s5, branch dht-s5. It is master 1dfbc8ca, which holds DHT S1–S4 and the durable index store slices 1–3.
- Do not stage or commit.

Contract (binding):
- docs/design/dao.jing.dht.md: §10 S5 acceptance, §4 (writes, the ack, "No REPL round stalls"), §4.4 (publish?), solo mode, §8 (the secret).
- docs/design/yin.repl.dao.space-index.md: the durable store spec, HEAD, lock, recovery, rehydration.
- Owner decisions, verbatim: collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md. In particular:
  - publication is explicit opt-in, and the REPL states what will be shared;
  - ack = sent to at least 2 peers;
  - fetch-only when not publishing;
  - solo never acks.
- Standing owner decision: the store is picked at startup; mem is the default; there is no runtime switching.

Build every S5 acceptance bullet in §10:
1. The default stays `mem`. `dht:<dir>` is an explicit `--index-store` choice, alongside `mem` and `file:<dir>`. With no peers it runs solo and opens NO socket.
2. Publishing needs its own flag, separate from configuring peers. Before sharing anything, the REPL states what will be shared.
3. For each publication, the REPL shows whether it was acknowledged (sent to N peers) or not, and why, from the `:dao.jing.dht/sent` / `unacknowledged` facts. No round waits on the network: publish and read-back complete against `:local`.
4. Two processes with separate locked directories exchange content. That means real processes and real loopback sockets, at least JVM↔JVM. Add JVM↔Node or JVM↔Dart if the peer build allows. Live cross-host sockets were deferred here from S3.
5. A reader given a manifest address hydrates and queries a remote index.

Also:
- The root secret: at least 32 CSPRNG bytes minted per process at startup, in memory only, never persisted (§8 as amended by S4).
- The inbound storage bound: a sensible CLI default, stated in the docs.
- The bind host defaults to loopback. Non-loopback only by explicit option.
- Document the CLI and REPL behaviour in the design docs.

Rules:
- Write tests first and show each FAILS before its fix. Give at least one mutation per core property.
- Time advances by ticks in the core. Real-process tests may wait on sockets, with bounded polling.
- CLJC portability:
  - `:cljd` goes FIRST in mixed reader conditionals.
  - No array-map. No cross-namespace #'private.
  - Refusal helpers return the error object.
- Invariants: dao.stream is P2P with no server/client and no privileged node; apply stays independent of rpc.
- Run every check in the FOREGROUND and poll long lanes to their verdict before ending your turn:
  - kondo on the changed files
  - full `clj -M:test`
  - `bb test:cljs`
  - `bb build:yin-repl-peer`
  - `bb test:cljd` (delete test/cljd-out first)
- Never report a result you did not see.

Report: collab/1790795000000-repl-engineer-dht-s5-repl-integration.claude-opus-5-5.report.md, mapping each acceptance bullet to its evidence.

## Owner direction, scope amendment (2026-10-01, orchestrator)
Owner, verbatim: "plain clojure code should be able to query for code in the dht. the yin.repl should use the same path as the clojure repl via host-functions"

Binding for S5:
1. **One plain-Clojure path (CLJC, callable from JVM, Node and Dart).** A public namespace or API, outside yin.repl, that ordinary Clojure code calls to:
   - (a) join the DHT from a small options map: dir, peers, bind host, publish flag, secret and bound defaults per §8 and S4. The secret is minted per process, in memory only.
   - (b) load a published code index from a manifest address, using dao.space.index read-manifest and restored-indexes.
   - (c) query it with dao.space.query/q.
   No yin.repl dependency. Prefer dao.* placement, since yin.repl is a consumer. Justify your namespace choice in the report.
2. **yin.repl reuses that exact path.** The REPL's `dht:<dir>` store is composed by calling that API. Loading and querying a remote index from inside the REPL goes through host functions over the SAME API, the way `dao.space.query/q` is exposed on require. No REPL-only DHT or index plumbing.
3. **Tests:**
   - A plain Clojure test, with no yin.repl, joins a DHT, loads a remote index published by another process or node, and queries it.
   - A REPL test does the same through the host functions and gets identical results.
   - Both tests share the API.
Everything else in this brief stands.

## Fix round 1 (2026-10-01 03:40 +07, orchestrator): sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790800000000-architect-dht-s5-signoff.gpt-6-sol.findings.md.
Rulings:
- (a) Solo refusing --dht-bind/--dht-port is correct. A first node may list peers that are not up yet.
- (b) The lock moves under dao.* (the blocker).
- (c) The Node leg must be a required, built gate.
- (d) The Dart flake is inconclusive; it is not an S5 finding.
Fix:
1. HIGH | dao/space/dht.cljc ~173 | Public `join {:dir …}` opens content.jing without the directory lock.
   - Move the reusable locked directory store (the lock, HEAD and atomic-replace machinery now in yin.repl.store.fs / yin.repl.store) under dao.*. Choose the namespace and justify it, e.g. dao.jing.store.dir or dao.space.store.
   - `join {:dir …}` uses it. `:local` stays for callers that supply an already-locked store.
   - yin.repl.store becomes a consumer of the dao.* one: no duplicated lock code, and every slice-2 lock property is kept (per-worker claims on Node, OS locks on JVM and Dart, the in-process registry).
   - Carry every existing slice-2 lock test over, or keep it green.
2. MEDIUM | dht_process_test.clj ~247/251 |
   - Add a competing-open assertion: a second process or `join` on the same dir is refused, naming the directory.
   - Make the Node leg required: add a bb task (or extend an existing one) that builds target/yin-repl.js, and make the test fail, not skip, when the file is missing. Document the task in docs/agents/build-n-test.md.
3. MEDIUM | dht_process_test.clj ~34 | Remove the reserve-then-close port race. Listeners bind ephemeral port 0 and report their bound address, which the next processes use (or retry on collision).
Write tests first and show each FAILS before its fix. Run every lane in the FOREGROUND and poll to the verdict, including the new Node build task.
Report: ...claude-opus-5-5.report-r2.md
