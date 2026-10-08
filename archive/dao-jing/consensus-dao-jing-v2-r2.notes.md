# Round 2 — orchestrator notes, verified against the tree

Four facts neither round-1 position had, all checked. They are settled;
argue from them, do not re-derive them.

**N-a. The no-wait rule cuts against building a v2 append-log.**
`dao.stream.md:153-158` states it generally: "no operation waits. An operation
that cannot yet know its answer returns the outcome true at the moment it is
called." But `dao.jing.file` today acknowledges a put **only after the log is
flushed** (`dao.jing.md:299-301`), and `dao.jing/materialize!` returns the
address synchronously only after the backend answers `:inserted`. So a
conforming v2 append-log could not simply wrap synchronous file writes:
durability completion would have to arrive later, as data. gpt-6-astra already
conceded this ("merely renaming synchronous filesystem wrappers would not meet
the no-wait contract... a design prerequisite, not a mechanical five-call
replacement"). Both seats must now say what that does to C1: if option (a)
requires redesigning when a content put is acknowledged, it is not a
like-for-like alternative to option (b), and its cost must be stated in those
terms rather than as conformance-suite work.

**N-b. `datom.world.md:66-68` says, verbatim:** "An adapter that exposes a
function for portable code to call is not an interpreter. It isolates the host
library but keeps the coupling, and the effect never appears as an emission."
`:put-content-fn` is exactly such a function. claude-fable-5-1's C1 argument is
that this condemns DaoJing's content-handle map **today, on v1**, and would
still condemn it with a v2 append-log underneath — so the transport cures
nothing the axiom actually objects to. gpt-6-astra must answer that argument
directly: either the handle map is already outside Host Boundaries (in which
case C1 is not what fixes it), or explain what a v2 log beneath a synchronous
`:put-content-fn` changes about the emission.

**N-c. Correction to claude-fable-5-1's C2 count.** The v1 observer has **no
`src/` caller at all** — confirmed, `observer-state`/`observe-step!` appear
only in tests. But the repoint touches **seven** test files, not five: the five
`test/dao/space/` files (transactor, stigmergy, index, schema, query) plus
`test/dao/jing/mem_test.cljc:213` and `test/dao/jing/dht_test.cljc:402`, which
are not `dao.space` tests and would not travel with `jing_test.cljc`. Nine
`src/` namespaces require `dao.jing` (including `src/dev/psset_fixtures.clj`),
none for the observer.

**N-d. Scope for N1 is the orchestrator's call, not yours.** gpt-6-astra's
falsifier was "explicit orchestrator authorization extending Jing's scope",
and claude-fable-5-1 named "leave `log.cljc` standing" as its fallback if scope
is held closed. That ruling has not been made. Rule **conditionally**: state
what should happen if a consumer plan may delete a v1 file, and what should
happen if it may not. Do not assume either.

Also verified: `log_test.cljc:104` writes `(->bytes [11 22])`, which is not a
decodable `[address payload]` record — claude-fable-5-1's porting adaptation is
correct. `yin.repl.driver` calls `rpc/abandon-unsent` at driver.cljc:220,
258, 293, 305, including before every rebind.
