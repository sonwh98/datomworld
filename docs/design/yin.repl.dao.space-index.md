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
