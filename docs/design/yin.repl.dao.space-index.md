# yin.repl: automatic code indexing

Status: design seam; the owner has ruled automatic indexing on evaluation.
The `q` surface is user-require by owner ruling (2026-09-27).

## Composition

`yin.repl/make-session` creates `program-out` and keeps its writer as
`:row-stream` and the evaluator's independent reader as `:row-observer`
(`src/cljc/yin/repl.cljc:523-554`). The expander appends one expanded
`[root rows]` packet there for each forwarded program
(`yin.vm.macro.md`, section 2.4); `run-evaluator-stage` reads it at
`src/cljc/yin/repl.cljc:767-779`. Attach a second, independently
advanced observer to this same medium when building the session. After
`run-expander-stage` forwards a packet, advance the index observer for
that packet as part of the evaluation round. Do not put indexing in a
loader, runner, frontend, or the link pair. The link pair serves content
for `require`; it is not the evaluated-program stream.

The observer consumes packets whether the VM later returns, parks, or
raises. A failed expansion forwards no packet, so it contributes no
expanded code. Give the index reader the same gap and reset discipline as
the evaluator reader: a lost packet must be reported, not called indexed.
Reset and VM selection rebuild the session and its observer together.

## Indexed facts

The packet's root is the program's content address; each canonical flat
row `[id tag & slots]` has its own content-addressed node id
(`yin.vm.code-as-tuples.md`, sections 4 and 6.1). Maintain the `$ast`
row relation and its derived occurrence relation from these rows. This
is the direct input used by `dao.space.query/q` in the free-names tests
(`test/dao/space/query_test.cljc:1121-1161`). Do not relabel these
variable-arity rows as five-slot datoms.

For the `dao.space.index` transactor's covered indexes, project the same
expanded tree to local `[e a v t m]` AST facts as specified in
`yin.vm.code-as-tuples.md`, section 6.5. Its `e` is a transactor-local
entity id, with `:yin/address` pointing back to the node address; it is
not the node identity. Commit a program's facts as one atomic transaction
record, `{:dao.space/transaction {:t n :datoms [...]}}`. The transactor
allocates `t`; the shell never supplies a clock. Attribute queries and
`pull` use this projection; positional structural queries use `$ast`.

The session that minted the evaluation is provenance. Record its stable
shell token as a fact on a metadata entity and refer to that entity in
the datoms' `m` slot, following the metadata-entity convention; `m` is a
local integer, not a session string. Preserve the root/round association
outside the content-hashed rows. Index code and its claims only: printed
output, last-value history, and ephemeral evaluation results do not enter
the code index by default.

## Query surface: owner ruling (2026-09-27)

**User require.** `dao.space.query/q` is not bound in the session; the
user's own `(require 'dao.space.query)` binds it, running over the
index this observer maintains. Ruled for `yin.repl`'s explicit, minimal
shell contract. Binding `q` at the first prompt (the IDE posture) was
considered and is a composition a host can still make; the shell does
not. Automatic indexing is a session composition
policy; exposing an extra language binding is a separate policy. The
owner can choose the first-prompt posture without changing the medium,
transaction format, or index observer.

## Cost and boundary

Startup adds one observer attachment and a local index/transactor value;
there is no eager scan of program history or remote fetch. An idle
session performs no indexing work. Each forwarded evaluation pays a
linear pass over its expanded rows, one atomic local transaction for the
datom projection, and the index update; `q` pays its own query cost when
called. This work is bounded by that program's size and is acceptable as
the cost of making every evaluated program queryable. Publication of
covered indexes can follow the index's normal cadence, outside the
evaluation critical path.

This is neither ShiBi authorization nor the `dao.jing.content` service
in `dao.stream.remote.implementation-plan.md`, section 1. It adds no
wire shape. It composes beside that epic's middleware, remote channel,
content service, REPL service, UDP, pair, and UCF slices; their transport
and content-service file sets need no change for this local observer.

## Durable store: startup, HEAD, and the directory lock

The store is picked once, at startup: `--index-store mem | file:<dir>`
through the one shared argument parser every host's `-main` uses, or
`:index-store-spec` (`:mem`, `{:type :file :dir dir}`) to
`yin.repl/create-state`. Omission means `mem`, today's behaviour. A
missing value, an unknown scheme, an empty directory, a directory that
cannot be opened, a host build without file support, a handle and a spec
together — each refuses before any shell or server composes, with its
reason; nothing falls back to memory silently. There is no runtime
switching.

The durable directory store is `dao.space.store` (its host file
operations, the lock among them, are `dao.space.store.fs`): the shell is
one consumer through `yin.repl.store`, and `dao.space.dht/join {:dir …}`
is another, so the lock below holds between REPLs and plain Clojure
nodes alike.

A durable directory holds three things: `content.jing` — the
`dao.jing.file` content log the indexer's publications materialize into,
exactly as the memory store receives them; `HEAD` — a versioned record
(`{:version 1 :manifest <address>}`, EDN) naming the latest published
manifest; and `lock` — the exclusive lock. Opening is exclusive and
validating: the lock is acquired before anything else in the directory
is touched and held until the store closes; a second owner is refused
naming the directory, and the first is unaffected. The JVM and Dart take
an operating-system lock on `lock` (released when the holding process
dies). Node, which has no `flock` in its core, uses claim entries: each
contender creates its own uniquely named `lock.<pid>.<nonce>` and only
then reads the others. An entry naming a live process — an owner or a
rival contender — refuses, and the contender withdraws its own. An entry
naming a dead process — an owner that crashed, or a contender that
crashed at any point in its own claim — is removed, and that removal is
race-free: a unique name is never created again, so it can never be a
newer live claim. A crashed process never bricks the directory and no
operator step is needed; since every contender creates before it reads,
two contenders never both own it (two simultaneous starts may both
refuse). That holds for worker threads of one Node process too: they
share its pid but not its in-process record, so an entry naming this
pid is live unless it is the contender's own entry by exact name.

Contract, pid reuse (Node only): a claim is as live as its pid, so a
crashed owner's claim whose pid the OS has since given to an unrelated
live process reads as live and startup refuses. That is the safe
direction, accepted rather than engineered around: a claim is never
read as dead while its owner lives. The refusal names the claim entries
it saw. Operator remedy: confirm that no REPL uses the directory, then
delete the named `<dir>/lock.<pid>.<nonce>` entries (or wait until the
process now holding that pid exits); the next start opens normally. The
JVM and Dart locks are held by the operating system and have no
pid-reuse case.

Those operating-system locks are per process
(a POSIX `fcntl` lock never refuses its own process), so the store also
records the directories this process holds and refuses a second owner
inside the one process before it opens any handle on the lock file.
That record is process-global mutable state, and it is an explicit
host-ownership exception to the no-hidden-global-state invariant: it
mirrors a fact the host already keeps per process — which files this
process has locked — so it can be no narrower than the process, and it
holds nothing but the canonical paths of the directories this process
currently owns. (On Node each worker thread has its own record; across
workers the claim entries decide.)

Publication moves HEAD only after the round's blobs are drained and its
manifest read back: the indexer's `after-publish` hook (`:head-fn` on
the store handle) writes the whole record to a temp file beside HEAD —
every byte, however many writes the host takes (Node's `writeSync` may
store fewer bytes than asked) — syncs it, renames it over HEAD — rename
is the atomicity, so a torn, short, or interrupted write is never
observed as HEAD — and syncs the directory
where the host can (Node and the JVM on POSIX systems). There, a failed
directory sync fails the replacement; a host that cannot sync a
directory at all (Dart's core, Windows) is not a failed sync. Only then
is the round reported durably published; a HEAD that cannot be written
or made durable leaves the round reporting a publication failure. (After
a failed directory sync the renamed HEAD may already be visible; it
still names a manifest the store answered, so a later open recovers
either snapshot, never a torn one.) The memory mode has no hook and
writes no HEAD.

On open, an absent HEAD means an empty index. A malformed HEAD, a
missing or invalid manifest, or an unreadable index node — the full
`read-manifest` traversal over the opened store, walking every index
root the manifest names (EAVT, AEVT, AVET, VAET), each of which must
cover exactly the manifest's `:count` datoms — refuses startup rather
than starting empty. An unreferenced blob after a
crash is harmless, and a crash before the rename keeps the previous
published snapshot: both are HEAD naming an older valid manifest, which
opens and recovers.

What the open recovered — `{:manifest <address or nil> :datoms <the
walked snapshot or nil>}` — is `:recovery` on the store handle and
`:index-recovery` on the shell state, and the shell installs it before
it admits any evaluation (`yin.repl.index/rehydrate`). The indexer's
log becomes a fresh complete-retention memory log holding the recovered
datoms as transaction records, one per original `t` in ascending order,
each datom and its `t` preserved, so the round's transactor keeps
deriving the next `t` from it. Entity allocation resumes one past the
greatest restored entity or metadata id (never below
`datom/first-user-id`), so restored and new ids never collide. The
recovered manifest is the indexer's published one and its transaction
counts are the restored ones, so `q` answers the previous run's facts
before any new evaluation, and the first publication after a restart
covers old and new facts — HEAD moves to a manifest holding both.

Every process start mints a new shell token. Restored facts keep their
original session tokens and root and round metadata, since they are the
datoms as committed; new facts carry the new token, so `q` over the
history view tells the runs apart.

With a durable store, `(reset)` and VM selection rebuild the VM, the
expander, and their observers, but the new indexer continues the old
one's log, entity allocation, counts, and published manifest
(`yin.repl.index/carry-over`): they never reset `t` or entity
allocation, and the published index stays queryable once
`dao.space.query` is required again. With the default memory store they
start an empty index, exactly as before.

## DHT store: `dht:<dir>` (DHT epic S5)

`--index-store dht:<dir>` (or `{:type :dht :dir dir ...}` as
`:index-store-spec`) is an explicit third choice; `mem` stays the
default. It opens `<dir>` exactly as `file:<dir>` does (lock, content
log, HEAD, validated recovery) and joins a `dao.space.dht` node with that
locked store as the node's local store (`dao.jing.dht.md`, "The plain
Clojure path"). The indexer publishes through the node's store: each put
inserts locally and asks the node to replicate, and the manifest reads
back locally, so no round waits on the network. The HEAD write then
announces the publication to the node.

Its options are their own flags, each refused without `dht:<dir>`:

| Flag | Default | Meaning |
|---|---|---|
| `--dht-peer host:port` (repeatable; `[v6]:port`) | none | Bootstrap contacts, IP literals only. None means solo: no socket is opened, no secret minted, nothing sent. |
| `--dht-publish` | off | The separate publication declaration. Peers alone declare nothing: without it the node fetches only. |
| `--dht-bind ip` | `127.0.0.1` | The socket's bind address; anything but loopback only by this flag. Refused when solo. |
| `--dht-port p` | 0 (ephemeral) | The socket's port. Refused when solo. |
| `--dht-max-inbound-bytes n` | 67108864 (64 MiB) | The inbound `:store` bound a publishing node enforces. |
| `--dht-manifest address` | none | A remote index to hydrate before the first evaluation. Needs a peer. |

The root secret is minted per process at startup (32 CSPRNG bytes), held
only in the node value, never written or reported.

Before the node steps once, the startup banner states what will be
shared: with `--dht-publish`, everything in `<dir>/content.jing` (every
program recovered from HEAD and every program evaluated from now on) to
any peer that asks; without it, that the node is fetch-only; solo, that
no socket is opened.

The host's single ticker steps the node first in each tick
(`yin.repl.main/step-all`) with its clock reading, and prints its lines:
where the socket bound, and for every publication `dht: published
<manifest> (<n> blobs) — acknowledged: sent to N peers`, or `— NOT
acknowledged: <why>` (solo, publication off, too few peers with how many
were reached, oversize, busy); the local copy is durable either way.

A reader started with `--dht-manifest` loads that index through
`dao.space.dht/load-index` before it admits any evaluation, exactly as a
restart installs its recovery: typed lines wait in the input medium, and
once the whole index is local and validated it becomes the directory's
HEAD and the indexer is rehydrated from it, so `q` answers the remote
run's facts. A directory whose HEAD already names another manifest is
refused. A load no peer can complete, or a socket that cannot bind,
stops the shell with its reason and a failing exit status; it never
starts over an empty index.

At the prompt, `(require 'dao.space.dht)` binds the same plain path as
host functions over the shell's node, on the query call pair:
`(dao.space.dht/load-index m)` starts a load and answers its status at
once (`:loading`, `:loaded`, `:failed`); `(dao.space.dht/load-status m)`
answers the status map; `(dao.space.dht/q m query & inputs)` answers
`dao.space.dht/q`, refused until the index is loaded. The node prints
`dht: loaded <m>` when a load completes.
