Created-GMT: 2026-10-05 12:33:59 GMT
Created-Local: 2026-10-05 19:33:59 +07
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

# Task: architect-head-trace

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 19:33:59 +07 | Status: active | Rationale: owner prefers fable for Architect design; author seat, so a different-family reviewer (gpt-6.1-sol) signs off afterwards

Author a design document for a **published head trace**: the publisher
deposits its index HEAD as a signed trace on a stream, and readers follow it,
so a reader's `(require 'alib)` resolves against the publisher's current HEAD.

## Edit authorization (the owner said "do it" to this exact scope)

You may create exactly ONE file: `docs/design/yin.vm.linker.dht.head.md`.
Edit no other file. Write no code, run no tests, run no git command. This is a
design document only. (The orchestrator reviews, commits and routes the
independent review.)

## The owner's words (quotes, kept apart from the orchestrator's reading)

- Owner, verbatim: "is it possible to start the yin.repl to always reference
  whatever the HEAD is? (require 'alib) should always use the index manifest of
  the HEAD"
- Owner, verbatim: "publishing a's head is a what stigmergy is for. a can
  publish its head and c can read it"

Orchestrator's reading (a paraphrase, not the owner's words): the head is a
trace A deposits in a shared medium and C perceives; it is not a mutable-pointer
service C calls. Challenge this reading if the invariants contradict it.

## Established facts (verified by the orchestrator in this tree, 2026-10-05)

1. **`announce!` is already the trace, privately.**
   `src/cljc/dao/space/dht.cljc` `announce!` (about line 387) appends
   `{::manifest <address>}` to the node's *ledger ring*, a `dao.stream` ring.
   `step` drains it to report `:published` / `:republished`. It is
   process-local, unsigned, and discarded by `close!`.
2. **HEAD** is a one-record file in the node directory,
   `{:version 1, :manifest :segment/blake3-...}`, replaced atomically. It moves
   on every round that adds rows, not only on `yin.link/publish`, when the
   round's blobs are local and the manifest reads back, before any network
   result (`docs/design/yin.vm.linker.dht.md` section 5.5.6).
3. **The join token today** is `yin:<host:port>/<principal>/<index-manifest>`,
   printed by A after each publication (`src/cljc/yin/repl/dht.cljc`
   `join-token`, `token-lines`). The principal is the 64-hex Ed25519 public key
   that signs name assertions; assertions carry a per-principal `seq`.
4. **Reader today (observed on the JVM):** `dht join` hydrates the token's
   manifest once and installs it as the reader's HEAD. The REPL host function
   `(dao.space.dht/load-index <manifest>)` loads a newer manifest, after which
   `(yin.link/names)` resolves a republished name to the new module address,
   but (a) a restarted reader reverts to the old index, so the load is not
   persisted, and (b) a name already linked in a session is not relinked by a
   repeat `(require ...)`; `(reset)` then `require` links the new code.
5. **The index manifest is not the module.** The token's manifest is the hash
   of the publisher's whole index (rows plus signed name assertions); the
   module manifest (`yin.link/publish`'s `:address`) is fetched only on
   `require`. A republished name retracts the standing assertion first.
6. **Saved state:** `yin.repl.state` saves flags to `<node dir>/state.edn`;
   `--dht-manifest` is one-shot and never saved.

## Owner rulings and invariants that bind the design (relayed; verify against the docs)

- `dao.stream` is the boundary; any stream may be exposed over ws or UDP,
  NAT-traversing and P2P; there is no server or client concept and no
  privileged node (owner, 2026-09-25).
- A fetch has no deadline; liveness belongs to the drive via `dao.lease`; the
  linker stays clock-free (owner, 2026-09-26, "(a) plus the lease pointer").
- `dao.stream.apply` is independent of rpc: no rpc or transport concepts in
  apply code, docs or `:dao.stream.apply/*` words (owner, 2026-09-29). Do not
  make the head trace an apply or rpc operation unless you prove it keeps this.
- Plain-Clojure API first: the REPL uses the same path as non-REPL clients
  (owner, 2026-10-01).
- No backward compatibility is needed; prefer clean breaks.
- `docs/design/datom.world.md`: host boundary is a stream boundary, callbacks
  are stream events, a stream has no privileged reader, agents are semantics
  and the substrate carries no policy, derive rather than persist.
- `docs/design/dao.agent.md` invariants 1 to 3 (single-writer provenance,
  zero naked host access, sparse continuation capture) and its stigmergic
  model (section 4).

## Read first

- docs/design/datom.world.md
- docs/design/yin.vm.linker.dht.md (publication rounds section 5, 5.5.6,
  principals and names, pending require, the later amendments)
- docs/design/dao.jing.dht.md
- docs/design/dao.stream.md, docs/design/dao.stream.ws.md,
  docs/design/dao.stream.datagram.md (what a stream exposure offers a reader)
- docs/design/dao.agent.md
- docs/design/dao.lease.md
- src/cljc/dao/space/dht.cljc (`announce!`, the ledger ring, `step`)
- src/cljc/yin/repl/dht.cljc and src/cljc/yin/repl/query.cljc (the REPL's DHT
  host functions, `join-token`, `token-lines`)
- src/cljc/yin/repl/state.cljc (what a saved node keeps)

## What the document must settle

Decide each, give the reason, and name the alternative you rejected:

1. **Medium.** Where the head trace lives so a reader needs no manifest to
   find it: on the publisher's own single-writer stream exposed over
   `dao.stream`, under a key in the DHT, or both. State the availability
   consequence when the publisher is offline, and whether a relay may serve
   the trace.
2. **Trace schema.** Fields, where the signature lives (the `m` slot of
   `[e a v t m]` is one candidate), the monotonic `seq`, and how `seq`
   survives a publisher restart. Honour "derive, do not persist" where a head
   or seq can be derived from data that already exists.
3. **Retention.** Whether a small ring (latest wins; a slow reader gets a gap
   and its recovery cursor) is enough, and what happens at first contact.
4. **Publisher behaviour.** When a trace is deposited (every HEAD move or
   coalesced), what it does at startup, and the relation to `announce!` and the
   ledger ring (reuse, wrap or replace).
5. **Reader behaviour.** How a node follows a head, which principals' traces it
   honours (the same declared-principal rule as names), rollback and replay
   protection (an old validly signed trace re-served by a relay), what it does
   on each new head, and what it keeps when the publisher is silent (no
   timeout: see the fetch ruling).
6. **When `(require 'alib)` refreshes.** On every require, on a head event, or
   on demand, and the pinning escape hatch (`--dht-manifest` or equivalent)
   for reproducible runs. State the consequence that a name may change meaning
   between two requires.
7. **Persistence and relink.** The loaded index must survive a restart, and a
   name whose resolved address changes under a live session needs a defined
   rule (relink, refuse with a message, or require `(reset)`). Pick one.
8. **Command line and saved state.** The join token should shrink to
   `yin:<host:port>/<principal>`; say how `dht join` obtains its first
   manifest, how it interacts with `--dht-manifest`, and what `state.edn` keeps.
9. **Failure and security.** A forged trace, a stale trace, a publisher that
   rewinds, a relay that withholds, a trace that names a manifest nobody holds.
10. **Portability.** CLJ, CLJS and CLJD: which parts are portable `.cljc`,
    which need a host seam, and any known ClojureDart trap you can foresee.

## Required structure

Status line with today's date, objective, the owner's words, the invariants it
rests on (each cited), the design (decisions 1 to 10), what is rejected and why,
failure modes, **implementation slices** (each independently landable, with the
tests that pin it and its completion criteria, ordered), what is deferred, and a
short list of **questions only the owner can answer**. Keep ASCII, lines at most
80 columns, and match the tone of `docs/design/yin.vm.linker.dht.md`.

Distinguish architectural defects in the current design from implementation gaps
and from intentionally deferred work. If anything above contradicts an invariant,
say so plainly instead of designing around it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

Then report: the file you wrote, the decisions in one line each, any invariant
contradiction or design defect you found (severity | file:line | evidence |
correction), and the questions for the owner.
