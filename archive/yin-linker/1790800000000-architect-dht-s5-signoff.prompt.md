# Architect sign-off: DHT S5 (REPL integration via a plain Clojure API)

Role: Architect (docs/agents/roles/architect.md). This is a read-only review.
- Worktree: /Users/sto/workspace/datomworld-dht-s5, branch dht-s5, from master 1dfbc8ca. S5 is uncommitted; use `git diff` and `git status`.
- The author is claude-opus-5-5.
- Brief, including the owner's scope amendment: collab/1790795000000-repl-engineer-dht-s5-repl-integration.prompt.md.
- Report: collab/1790795000000-repl-engineer-dht-s5-repl-integration.claude-opus-5-5.report.md.
- Contract: docs/design/dao.jing.dht.md §4, §4.4, §8, §10 S5; docs/design/yin.repl.dao.space-index.md.
- Owner decisions: collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.
- Owner direction (2026-10-01), verbatim: "plain clojure code should be able to query for code in the dht. the yin.repl should use the same path as the clojure repl via host-functions".

Verify adversarially:
1. Every S5 acceptance bullet:
   - mem is the default;
   - dht:<dir> is explicit;
   - solo opens no socket;
   - publishing has its own flag and the REPL says what it will share first;
   - per-publication ack status, and no round waits on the network;
   - two locked processes exchange content;
   - a remote manifest hydrates and is queried.
2. The owner direction:
   - `dao.space.dht` is a genuine plain-Clojure path with no yin.repl dependency.
   - yin.repl composes through it, and its host functions (load-index, load-status, q) are thin wrappers over the SAME API, with no REPL-only plumbing left in yin.repl.dht.
   - Is `dao.space` the right placement?
3. The secret is minted per process, in memory only. The bind defaults to loopback.
4. The engineer changed S1/S4 files so the browser build works, making `node:crypto` and `dgram` load lazily. Is that sound on every host?
5. Would the tests fail if each property broke? Check portability traps.

RULE on the engineer's open points:
- (a) "No peers means solo" is read literally: --dht-port or --dht-bind without a peer is refused, so a first node must know its peers' addresses in advance. Is that right, or should a listening node without peers be allowed? Weigh the owner's P2P, no-privileged-node invariant.
- (b) `dao.space.dht/join {:dir …}` takes no directory lock, because the lock lives in yin.repl.store.fs. Should the lock move under dao.*?
- (c) The Node leg of the process test needs a manually built target/yin-repl.js, and skips with a notice when it is absent.
- (d) A main-test Dart-client failure happened once, with a stale peer binary.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD. Give findings as a Severity | file:line | issue | fix table, then the rulings.
