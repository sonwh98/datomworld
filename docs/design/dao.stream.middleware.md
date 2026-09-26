# DaoStream Middleware

Status: design target, not implemented. Subordinate to
[`dao.stream.md`](./dao.stream.md), which wins on any disagreement. Every
sentence below is a rule. This document names no network; the network
attachment points are in [`dao.stream.remote.md`](./dao.stream.remote.md),
section 7.

## What a middleware is

A middleware is a value: a map of two transforms, one over the operation
map on the way in and one over the outcome map on the way out. Each
transform is a total function from its input map to its output map, with
three bounded exceptions named below: the gate's read on its composed
decision medium (a handle-local read cursor and cached decision, advanced
only by the gate itself), a metering emission to a composed side stream,
and the gate's one recovery re-read after a gap. These are stream
emissions and handle-local reads, never a retry of the wrapped operation
and never a wait: the wrapped operation is issued exactly once. It
is the adapter shape of `datom.world.md` (a map of transform functions)
applied to a handle rather than to a host boundary. Ring's request and
response pair maps onto the operation map and the contract's outcome map.

```clojure
{:dao.stream.middleware/in  (fn [ctx req] -> req | outcome-map)
 :dao.stream.middleware/out (fn [ctx req outcome] -> outcome)}
```

`in` returning an outcome map short-circuits: the inner handle is not
consulted and that map is the operation's outcome, after the `out`
transforms of the middlewares outside it. `ctx` is a plain map the caller
supplies and no middleware may fabricate; a local caller supplies `{}`.

## The operation map

An operation as data. Its vocabulary is `dao.stream.remote.md`'s so that the
same chain runs on either side of a channel without translation:

```clojure
{:dao.stream.remote/op    :dao.stream/cursor | :dao.stream/next |
                          :dao.stream/append!
 :dao.stream.remote/args  [anchor] | [cursor] | [value]
 ...}                     ; open: further qualified keys pass through untouched
```

`descriptor` and `close!` are not operations a middleware sees: `descriptor`
is a name and `close!` is a lifecycle transition, and neither has an outcome
a policy may decline (`dao.stream.md`, Close; Envelopes).

`(apply-request h ctx req) -> outcome` applies one operation map to a
handle. It is the one place an operation map becomes a protocol call.

## wrap

`(wrap h chain) -> handle`, where `chain` is a vector of middlewares
applied outermost first. The returned handle implements the protocols the
inner handle implements and no others. On each protocol call it builds the
operation map, runs every `in` from the outermost inward, applies the result
to `h` with `apply-request`, and runs every `out` from the innermost
outward. Its `descriptor` and `close!` delegate to `h` unchanged.

The wrapped handle is a handle on the same logical stream as `h`: it
declares `h`'s identity, passes cursors through, and may present values
under its own interpretation (`dao.stream.md`, Surfaces).

## The position rule

A middleware maps position `p` of the inner handle to position `p` of the
outer handle, for every `p`. It therefore never:

- constructs, parses or rewrites a cursor, an anchor or a `gap` recovery
  cursor;
- changes `:dao.stream/outcome`, `:dao.stream/identity`,
  `:dao.stream.remote/op` or `:dao.stream.remote/id`;
- skips, merges or reorders elements.

It may replace `(first args)` on `append!` and `:dao.stream/value` on an
`ok` from `next` with any value, of any size; add or read open keys; and
short-circuit with an outcome from the operation's exhaustive set. A cipher,
a compressor, a redactor and an annotator preserve the rule. A filter that
drops elements does not: it is an interpreter that forwards into a new
stream with its own identity (`dao.stream.md`, Composition), never a
middleware.

## The four prohibitions

A transform is total and returns at once. It never loops over any handle,
never retries the wrapped operation, never waits, and never touches the
inner handle: the only handle operations it may perform are the decision
read `gate` defines below (a bounded read on a composed decision medium,
whose gap recovery re-reads that medium and whose cursor re-minting after
a non-`ok` outcome mints afresh next operation; neither retries the
wrapped operation) and one `append!` to a
side stream it was composed with. It reads no host clock. A transform that
needs facts from a stream is not a middleware: folding a stream is an
interpreter's work, and what a middleware reads is a decision an
interpreter already published onto a medium.

## Failure inside a transform

A value that cannot be transformed on the way out (undecodable ciphertext,
a corrupt compressed value) is presented as
`{:dao.stream.middleware/undecodable true :dao.stream.middleware/raw v}`
under `:dao.stream/value`, with the outcome and cursor untouched: the
position is real and the reader decides. A value that cannot be transformed
on the way in answers `:dao.stream/invalid-value`.

## Two generic middlewares

**gate**, the authorize step. Composition data only; the policy is not
specified here.

```clojure
(gate {:dao.stream.middleware/verify   verify
       :dao.stream.middleware/decision h})
```

The decision medium is written by an interpreter the composition runs over
its fact streams (the index): it folds facts with its own cursors, at its
own cadence, and appends each derived decision. Neither the gate nor the
index knows of the other; the medium is their only coupling. A decision is
whatever value the index publishes; the gate reads none of it. It may be an
immutable query value, a relation value or an opened published index, in
which case `verify` is a query over it with the request as bindings: the
shape `dao.space.query/q` has (a query over immutable inputs, opening
nothing), and the required adapter contract for a query interpreter that
plugs into the gate (`dao.shibi.md`, How it plugs in).

`wrap` calls `cursor` with `:dao.stream/oldest` once when it constructs
the gate. The medium must be open and readable; it has capacity one and
evicts its older value. If cursor minting is not `ok`, the gate has no read
cursor or decision. Each operation retries `cursor` once; if it is still
not `ok`, `next` is skipped and `verify` receives the none marker. A
successful mint is its read cursor.

On each operation, the gate calls `next` once at its read cursor. On `ok`,
it stores the successor cursor and value. On `blocked`, it keeps the cursor
and last value. On `end`, it clears the value and becomes ended. On `gap`,
it clears the value, adopts the recovery cursor, and calls `next` once more.
If that call is `ok`, it stores the successor and value; if `blocked`, it
keeps the recovery cursor without a value; if `end`, it becomes ended; if
`gap`, it adopts that recovery cursor and remains without a value. It does
not read a third time in this operation. On any other non-`ok` outcome,
it clears the value and cursor, then retries cursor minting next operation.

`verify` receives the last value observed, or
`{:dao.stream.middleware/none true}` when there is none, or
`{:dao.stream.middleware/ended true}` once the gate is ended (the medium
closed; no later operation reads). That value is a bounded snapshot, not a
promise that it is the newest concurrent write.
`nil` passes the request through; a reason short-circuits with
`{:dao.stream/outcome :dao.stream/refused
:dao.stream.middleware/reason reason}`. A policy that must fail closed
refuses the marker. The cursor and value are state of the
wrapped handle, supplied to `wrap`, not global state. The composition must
serialize calls on one wrapped handle; the gate has no queue, lock or wait.
The decision medium is not the wrapped stream, so the position rule on the
inner handle is untouched.

A gate that must account for its own decisions appends each as
`{:dao.stream.middleware/decision :allow | :refuse ...}` to a side stream
it was composed with: this is **decision emission**, and an index that
observes that stream is how metered use keeps its state on a stream
rather than in the gate.

**present**, the credential step.

```clojure
(present {:dao.stream.middleware/present (fn [ctx req] -> credential)})
```

Its `in` associates the result under `:dao.stream.remote/credential`. Its
`out` is identity.

Neither middleware knows what a credential is. The trivial policy this
repository ships with is an allow-list keyed on `:dao.stream.remote/channel`
in `ctx`, ignoring the credential. Everything else belongs to
[`dao.shibi.md`](./dao.shibi.md).

## Exemplars

Value encryption: `in` on `:dao.stream/append!` replaces the value with its
authenticated ciphertext; `out` on an `ok` from `:dao.stream/next` replaces
`:dao.stream/value` with the plaintext or the undecodable marker. Cursors
untouched. The key is an argument to the constructor that built the
middleware, exactly as a deposit medium is an argument to a transport
constructor. Ciphers come from libraries.

Metering: `out` appends `{:meter/identity id :meter/op op :meter/outcome
kind}` to a composed metering stream and returns the outcome unchanged. The
side effect is a stream emission (`datom.world.md`, Streams).

## Invariant compliance

No callback: a transform is called downward by the caller in the caller's
control flow and returns a value; nothing is registered to be invoked
later. No implicit control flow: the four prohibitions. No hidden global
state: keys, policies and decision media are arguments to `wrap`. No shared
mutable state beyond what any handle holds. Interpretation and execution
stay separate: `apply-request` judges no value. No assumed graph: a chain is
an explicit vector.
