Created-GMT: 2026-09-09 18:14:08 GMT
Created-Local: 2026-09-10 01:14:08 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126
# Task: plan the migration of dao.jing.remote to dao.stream
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-10 01:14:08 +0700 | Status: active | Rationale: Architect primary per team.md; authored the query, transactor/index and schema plans this one follows

**Planning task, no write authority except the one output file named below.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
clean at `62ed336`.

## The goal this serves

Every consumer moves off v1 `dao.stream` onto `dao.stream`, after which v2
is renamed `dao.stream` (`dao.stream.md`, *The v2 namespace is transient*).
**`dao.space.*` is now entirely on v2** — query, index, transactor, schema, all
landed. `dao.jing.remote` is the next row of `dao.stream.md`'s consumer list.

## Read first

- `docs/design/dao.jing.md` — the storage boundary this adapter serves.
- `docs/design/dao.stream.md` — the v2 contract. *Explicitly Absent* is
  load-bearing here: **there is no blocking take and no operation waits.**
- `src/cljc/dao/jing/remote.cljc` (135 lines) and
  `test/dao/jing/remote_test.cljc` (381 lines).
- `src/cljc/dao/stream/rpc.cljc` and `src/cljc/dao/stream/rpc/ws.cljc` —
  the v2 RPC core and its WebSocket decoder, already built and in use by
  `yin.repl`. **`yin.repl` is your worked example**: a v1 RPC consumer
  already migrated to this core. Read `src/cljc/yin/repl/connect.cljc`,
  `driver.cljc` and `v2_adapter.cljc` before deciding anything.
- `git show 6ea8bb5` and `git show bdbe6f9` — the schema migration, for the
  method and the standard of proof.

## Method

As before: an explicit **invariants list** is the contract, grouped by the test
that exercises each, marked `[D]` stated in the design, `[T]` pinned only by a
test, `[T→D]` test-pinned and worth promoting, `[T✗]` an accident of the v1
dressing dropped with its reason. The implementation and tests have no
authority beyond the invariants they pin. Nothing is in production. The plan
is transient and is deleted when nothing in it is owed — and per `62ed336`,
anything it carries that no other document does must be moved out *before*
that deletion, so say in the plan where each such thing belongs.

## Verified measurements (mine, at `62ed336` — correct me in a "corrections"
## section, as you have done on every plan so far)

`dao.jing.remote` is small and its v1 surface is four call sites in **two**
`#?(:clj ...)` requires:

| site | call | note |
| --- | --- | --- |
| `:11` | `[dao.stream.rpc.client :as rpc-client]` | clj-only require |
| `:12` | `[dao.stream.rpc.ws :as rpc-ws]` | clj-only require |
| `:121` | `rpc-ws/connect!` | inside `connect-content!` |
| `:122` | `rpc-client/call!`, `rpc-client/close!` | passed *as arguments* to `content-client` |
| `:129,:134` | `rpc-ws/start!`, `rpc-ws/stop!` | inside a `comment` block |

Its two real functions are **already transport-agnostic**: `default-handlers`
takes a `dao.jing` handle and returns a plain op map; `content-client` takes
`[client call-fn close-fn]` and never names a transport. Only
`connect-content!` (JVM-only) binds v1, by supplying `rpc-ws/connect!` and
`rpc-client/call!` as those arguments.

Consumers: `dao.jing.coordinate:39-41` opens `:dao.jing/remote` coordinates by
calling `connect-content!` (clj-only); `index_test:616` redefines
`connect-content!`; `stigmergy_test.clj` runs a real server-plus-client
round-trip through `rpc-ws/start!` and `connect-content!`.
`remote_test.cljc` (381 lines) uses `rpc-ws/start!`/`stop!` at `:40`, `:285`,
`:296` and drives the rest through fake `call-fn`s.

## The central question, and why this namespace is not like the last four

**v1 `call!` blocks.** Its docstring: *"Send an op/args request to a connected
client and wait for the response… Returns the result value directly on :clj
(blocking)."* And `dao.jing`'s handle contract is **synchronous by
construction**: `{:put-content-fn f :get-content-fn g :close-fn c}`, where
`jing/get` returns the stored value and `jing/materialize!` returns the address
"only after the backend reports durability."

**v2 has no blocking take and no operation waits** — `dao.stream.md`,
*Explicitly Absent*. Its RPC core is `request!` / `poll!` / `take-completed`
over deposited events.

So a synchronous storage-handle contract sits directly on top of a transport
that has removed waiting. That is this plan's real subject, and the previous
four migrations offer no precedent for it: they moved *values* onto v2, where
the thing being migrated had never actually waited. Settle it explicitly:

1. **Where does the waiting go?** Options, and there may be better ones:
   a JVM-side loop over `poll!` outside the stream contract (waiting in the
   *host*, which is where v2 puts every other policy); an asynchronous
   `dao.jing` handle variant, which changes the storage boundary for every
   backend; or the conclusion that a synchronous remote content handle is not
   a thing v2 can honestly offer, and `:dao.jing/remote` becomes something
   else. Say which, and what it costs.
2. **Is blocking in the host a contract violation or ordinary host policy?**
   `dao.stream.md` says no *operation* waits. A host that polls in a loop has
   not made an operation wait. Rule on whether that distinction is real or a
   rationalization — it decides the shape of the whole plan.
3. **What `yin.repl` did.** It is the one v1 RPC consumer already migrated.
   Did it face this and how? If it stayed asynchronous throughout, say so and
   say why `dao.jing.remote` cannot.
4. **`content-client`'s injected `call-fn`/`close-fn`.** They already make the
   namespace transport-agnostic. Is the migration therefore just a new
   `connect-content!`, with `content-client` untouched? If so, say plainly how
   small this is — a small honest plan beats a large one.
5. **The server side.** `default-handlers` returns an op map for
   `rpc-ws/start!`. What serves it under v2 (`dao.stream.rpc/serve-once!`,
   `v2.ws`), and does `default-handlers` change shape at all?
6. **`dao.jing.coordinate`.** `:dao.jing/remote` opens by calling
   `connect-content!` and returns a handle the caller closes. Does the
   coordinate survive unchanged?
7. **The tests.** `remote_test` drives most paths through fake `call-fn`s and
   should barely move; `stigmergy_test.clj` runs a real round trip and will.
   Say which tests are transport tests and which are contract tests.
8. **Whether `dao.jing.dht.node` comes along.** It reaches v1 through
   `dao.stream.transit`, and `dao.stream.md` lists the two together. Rule on
   whether they are one plan or two — do not silently absorb it.

## Constraints

- Do not plan work for `yin.vm.*`, `dao.runtime`, `yin.io`, the demo
  surfaces, or the v1 transports (they are deleted with v1, not migrated —
  `dao.stream.md` now says so).
- `public/demo.html` must keep working. Confirm whether `dao.jing.remote` is
  on its path rather than assuming.
- Each phase leaves clj, cljs (Node) and cljd green and deletes what it
  replaces in the phase that replaces it. Note that `connect-content!` and
  both v1 requires are already `#?(:clj ...)`-guarded, so cljs and cljd may
  have nothing to do — check rather than assume, and remember that
  `#?(:clj ...)` alone does **not** exclude code from the cljd build.

## Output

Write to `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: claude,
Session-ID: 95be8c08-c06c-41e6-9891-fa25ed546126. Structure it as your last four plans: corrections to this
brief first, then what the namespace is in one paragraph, the invariants list,
the decisions with their reasons, phases with explicit build/delete/prove
lists, a host matrix, the boundary (built here vs left owing by namespace),
and an end condition. Change no other file.
