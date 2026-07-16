# Design: `dao.await`

## Summary

`dao.await` provides familiar async-style syntax for writing sequential stream
programs that run on the existing Yin VM and Dao runtime stack.

It does not introduce a new interpreter, scheduler, continuation model, or
blocking stream API. The implementation reuses the existing pipeline:

```text
(await/go <forms>)
-> yang.clojure compiles forms to Universal AST
-> yin.vm/ast->datoms emits semantic datoms
-> Yin VM executes semantic datoms
-> Yin VM owns continuations and parks on stream effects
-> dao.runtime schedules resumable VM task maps
-> dao.stream provides readiness-based transport
```

The name `go` is intentionally familiar to users who know `core.async`, but the
implementation is Dao-native. It must not use `core.async`, host threads,
callbacks, promises, or blocking calls as its core mechanism.

`dao.await` is a syntax and process-construction layer. It is not `dao.flow`.
`dao.flow` remains the datom coordination protocol over `dao.space`.

## Homomorphism with `core.async`

Structurally, `dao.await` is a *homomorphism* of `core.async`. It is a structure-preserving projection from the Clojure host domain into the Yin/Datom domain. The relationships between operations are preserved, allowing a developer's mental model to map perfectly from one to the other, even though the execution substrate is completely different:

- **The Medium:** `core.async` channels map to `dao.stream`
- **The Process:** `core.async/go` state machines map to Yin VM state maps
- **The Read Operation:** Parking on a channel callback maps to Yin parking on a `:stream/next` effect
- **The Write Operation:** Parking on a full channel maps to Yin parking on a `:stream/put` effect
- **The Scheduler:** Host Thread Pool / Event Loop maps to the `dao.runtime` task queue

Because the structure is preserved, developers gain the proven ergonomics of Communicating Sequential Processes (CSP) without violating the system invariant that **runtime state must be data**. While `core.async` compiles code into an opaque host-level state machine, `dao.await` compiles code into pure datoms interpreted by the Yin VM, leaving the entire execution state explicitly queryable and serializable.

## Layer Boundaries

The implementation must preserve these boundaries:

- `dao.stream` exposes readiness operations such as `next`, `put!`,
  `drain-one!`, and `close!`.
- `dao.runtime` schedules resumable task maps. It must remain unaware of
  continuations and must not gain await-specific control flow.
- `yin.vm` interprets semantic datoms, owns continuations, and handles parking
  and resumption for stream effects.
- `yang.clojure` compiles Clojure forms to Universal AST.
- `dao.await` supplies user-facing syntax and starts or describes a Yin-backed
  stream process.

The key invariant is:

```text
dao.await/go targets Universal AST, not dao.runtime.
```

Any continuation produced by an await block is a Yin VM continuation. It is not
a `dao.await` continuation and not a `dao.runtime` continuation.

## Public API

V1 should add a `dao.await` namespace with these public forms or functions:

```clojure
(await/go body...)
(await/cursor stream)
(await/<! cursor)
(await/>! stream value)
```

The recommended v1 user shape is:

```clojure
(require '[dao.await :as await])

(await/go
  (let [c (await/cursor in)
        x (await/<! c)]
    (await/>! out [:seen x])
    x))
```

V1 uses explicit cursors. Do not implement implicit cursor reuse in the first
version. It can be added later as sugar once the base semantics are stable.

### `await/go`

`await/go` accepts Clojure forms and delegates form compilation to
`yang.clojure`.

The body must be compiled as one expression. Multiple body forms should be
treated like a `do` block:

```clojure
(await/go expr1 expr2 expr3)
```

is compiled as:

```clojure
(do expr1 expr2 expr3)
```

The result of the last expression is the process result when the Yin program
halts.

`await/go` should not directly interpret body forms. It may analyze body forms
for host lexical capture and for compile-time lowering, but evaluation
semantics belong to Yang and Yin. If a form is valid for `yang.clojure`, it
should be valid inside `await/go`, subject to the available runtime module
bindings.

The return value of `await/go` should be an explicit process value or task
descriptor, not a hidden global side effect. The exact host integration can be
minimal in v1, but it should expose enough data to inspect or enqueue the
process. A suitable shape is:

```clojure
{:type :dao.await/process
 :ast  <universal-ast>
 :datoms <semantic-datoms>
 :vm <yin-vm-or-nil>
 :task <runtime-task-or-nil>}
```

If the existing VM constructors make storing both `:vm` and `:task`
unnecessary, keep the value smaller. The important requirement is that the
process is explicit data and can be driven by the existing VM/runtime path.

### Host Lexical Capture

`await/go` must bridge host lexical scope into the Yin environment. A naive
macro such as:

```clojure
(defmacro go
  [& body]
  `(go* {} '~body))
```

is not sufficient. It passes symbols to Yang, but does not capture host lexical
values:

```clojure
(let [my-val 42]
  (await/go
    (await/>! out my-val)))
```

In this example, the await program must receive the value of host binding
`my-val`, not just the symbol `my-val`.

The macro should analyze the body for free variables, emit host code that
captures those values, and pass the captured environment to `go*`:

```clojure
;; Conceptual expansion only.
(go* {:env {'my-val my-val
            'out out}}
     '(await/>! out my-val))
```

`go*` must then seed the Yin program environment or store so that those symbols
resolve to the captured host values during execution. The exact representation
should follow existing Yin VM environment/store conventions. Do not use hidden
globals for captured values.

Free-variable analysis must treat locally bound symbols inside the await body
as bound, not captured. At minimum, v1 must understand bindings introduced by:

- `let`
- `fn`
- `do` as sequencing with no new bindings
- quote forms, where quoted symbols are data and must not be captured

If implementation cannot provide robust free-variable analysis immediately, v1
must require an explicit environment form instead of silently compiling wrong
programs:

```clojure
(await/go {:env {'my-val my-val
                 'out out}}
  (await/>! out my-val))
```

The implicit capture macro can then be added after the explicit environment
path is tested.

### `await/cursor`

`await/cursor` creates a stream cursor in the Yin program.

It should lower through the same semantics as existing stream cursor support:

```clojure
(await/cursor s)
```

is equivalent to the existing stream cursor operation:

```clojure
(stream/cursor s)
```

At Universal AST level this may remain a normal application resolved through
the module system, or it may lower directly to a `:stream/cursor` AST node if
that is the local compiler convention chosen during implementation. Prefer the
least invasive path that matches existing `yang.clojure` stream handling.

### `await/<!`

`await/<!` reads the next value from an explicit cursor.

```clojure
(await/<! c)
```

is equivalent to:

```clojure
(stream/next c)
```

If the stream has a value at the cursor position, Yin returns that value and
advances the cursor. If the stream is empty and open, Yin parks the current
continuation using existing stream effect handling. When a later write satisfies
the wait condition, the stream wakeup resumes the Yin task through
`dao.runtime`.

On stream end, v1 should preserve the existing Yin stream behavior. Do not
invent a new end-of-stream contract in `dao.await`.

### `await/>!`

`await/>!` writes a value to a stream.

```clojure
(await/>! out value)
```

is equivalent to:

```clojure
(stream/put out value)
```

If the stream accepts the value, the expression returns the written value under
the existing stream effect behavior. If the stream is full, Yin parks the
current continuation. When space becomes available, the parked Yin task resumes
through `dao.runtime`.

## Compilation Strategy

V1 should avoid a second compiler. Reuse `yang.clojure` for form compilation.

There are two acceptable lowering routes. The implementation should choose the
route that creates the least coupling after inspecting the current module
resolver and Yang compiler paths. Do not add a new `:await/*` node family unless
there is a concrete need.

### Compile-Time vs Runtime Compilation

Avoid compiling the same await body every time the host code executes.

Preferred implementation:

```text
macro expansion time:
  body forms + free-variable metadata
  -> yang.clojure compile
  -> Universal AST literal
  -> optional yin.vm/ast->datoms literal

runtime:
  capture host free-variable values
  -> initialize process from precompiled AST or datoms
  -> run through Yin VM/runtime
```

If `yang.clojure` and `yin.vm/ast->datoms` are usable from macro-expansion
time on the host platform, the `await/go` macro should emit precompiled
Universal AST, and may also emit precomputed semantic datoms. Runtime `go*`
then only receives the compiled program plus captured values.

If compile-time compilation is not portable for CLJC/CLJS/CLJD, implement
runtime compilation as a fallback and cache by the quoted body form plus
compiler options. The fallback must be explicit in code and tests so repeated
spawns do not accidentally pay full compiler cost without visibility.

The macro must still capture host lexical values at runtime. Compile-time
lowering decides the program shape; it cannot know runtime values of local
bindings.

### Route 1: Module Aliases

Teach the Yin module environment that the await names produce existing stream
effect descriptors:

```clojure
await/cursor -> {:effect :stream/cursor ...}
await/<!     -> {:effect :stream/next ...}
await/>!     -> {:effect :stream/put ...}
```

With this route, `yang.clojure` can compile await calls as ordinary
applications:

```clojure
{:type :application
 :operator {:type :variable, :name 'await/<!}
 :operands [{:type :variable, :name 'c}]}
```

Runtime module resolution then maps the call to the existing stream effect.

This route minimizes changes to `yang.clojure`, but couples await semantics to
runtime module resolution. Use it if the module system already cleanly supports
aliasing `await/cursor`, `await/<!`, and `await/>!` to existing stream effect
descriptors without special cases in the VM.

### Route 2: Yang Special Lowering

If direct stream AST nodes are preferred, add targeted lowering in
`yang.clojure`:

```clojure
(await/cursor s)  -> {:type :stream/cursor, :source <compiled-s>}
(await/<! c)      -> {:type :stream/next, :source <compiled-c>}
(await/>! s v)    -> {:type :stream/put, :target <compiled-s>, :val <compiled-v>}
```

Do not add a new `:await/*` node family to Universal AST unless there is a
concrete need. The existing stream AST nodes already express the behavior.

This route treats await operations as compiler intrinsics over existing stream
AST nodes. Use it if it reduces runtime module coupling or makes generated
semantic datoms clearer. It is especially attractive because Universal AST
already has `:stream/cursor`, `:stream/next`, and `:stream/put` node types.

The implementation decision should be documented in the first patch that adds
`dao.await`, with a short note explaining why the selected route has less
coupling in the current codebase.

## Process Construction

`await/go` must produce or start a Yin-backed process from the compiled AST.

An implementation agent should first inspect the current VM constructors and
test helpers. Existing paths include:

- `yin.vm/eval`, which compiles AST through `ast->datoms`.
- VM-specific load-program functions in the AST walker, stack VM, register VM,
  and semantic VM.
- `dao.runtime` task maps with a `:resume` function.

V1 should choose the smallest integration that allows tests to drive an await
program through an existing VM implementation. Avoid adding a global default VM
or global scheduler.

A reasonable v1 shape is:

```clojure
(defn go*
  [{:keys [env ast datoms forms] :as opts}]
  ;; 1. prefer precompiled ast/datoms supplied by the macro
  ;; 2. if absent, compile `(do ~@forms)` with yang.clojure and cache it
  ;; 3. seed captured env into the Yin execution context
  ;; 4. load datoms into the VM supplied in opts, or return a process descriptor
  ;; 5. leave scheduling to the caller or explicit runtime driver
  )
```

The macro form can wrap this:

```clojure
(defmacro go
  [& body]
  ;; Conceptual only:
  ;; - compute free symbols in body
  ;; - compile body to AST/datoms if possible
  ;; - emit runtime captures for free symbols
  `(go* {:env <captured-env>
         :ast <precompiled-ast>
         :datoms <optional-precompiled-datoms>
         :forms '~body}))
```

This is illustrative, not final code. The implementation must account for CLJC
macro constraints and the existing compiler API. The important point is that
the source forms are compiled by Yang, not interpreted by `dao.await`, and host
lexical values are represented explicitly.

## Runtime Semantics

The await block runs as a Yin program.

When it reaches a stream operation:

- `await/cursor` creates a cursor value in the VM store.
- `await/<!` executes the existing `:stream/next` effect.
- `await/>!` executes the existing `:stream/put` effect.

If a stream operation can complete immediately, execution continues in the same
VM run.

If a read is blocked or a write is full:

- Yin parks the current continuation.
- The park entry is wrapped as a `dao.runtime` task using the existing VM
  adapter path.
- `dao.runtime` records the task in the wait set or registers a transport-local
  waiter when the stream supports `IDaoStreamWaitable`.
- Later stream writes, drains, or closes wake the task.
- `dao.runtime` invokes the task's `:resume` function.
- The VM restores the Yin continuation and continues evaluating the await body.

`dao.await` must not implement these steps itself. It relies on the existing
Yin and runtime machinery.

## Non-Goals

V1 must not include:

- `core.async` channels or go blocks.
- Host thread or virtual-thread blocking semantics.
- JVM-only `!!` operations as the primary behavior.
- A new await interpreter with its own program counter, local environment, or
  continuation data structure.
- A new `dao.runtime` continuation abstraction.
- Implicit cursor creation or cursor reuse.
- Cross-process migration or durable flow scheduling.

These may be explored later if needed, but they are outside the first
implementation.

## Tests

Add tests before implementation.

### Yang / AST Tests

Cover compilation of the public await forms:

- `(await/cursor s)` compiles to either an await module application or a
  `:stream/cursor` AST node, depending on the selected route.
- `(await/<! c)` compiles to either an await module application or a
  `:stream/next` AST node.
- `(await/>! s 42)` compiles to either an await module application or a
  `:stream/put` AST node.
- `(await/go expr1 expr2)` compiles the body as `(do expr1 expr2)` and preserves
  the final expression as the process result.
- A host lexical binding referenced inside `await/go` is captured and resolves
  to the host value at Yin execution time.
- Symbols bound inside the await body are not captured as free variables.
- Quoted symbols inside the await body are data and are not captured.
- Repeated execution of the same `await/go` body uses precompiled AST/datoms or
  an explicit runtime compilation cache.

### VM Behavior Tests

Use an existing VM implementation and ringbuffer streams.

Test cases:

- Prefilled input stream: `await/<!` returns the first value.
- Output write: `await/>!` appends to the output stream.
- Sequencing: a go body can read, transform, write, and return the final value.
- Empty read: the VM blocks, then resumes after `ds/append!` writes a value.
- Full write: the VM blocks, then resumes after `ds/drain-one!` frees space.
- Closed stream behavior follows the existing `:stream/next` and `:stream/put`
  contracts without await-specific translation.

### Runtime Boundary Tests

Verify that `dao.runtime` remains generic:

- Runtime queue entries are ordinary task maps with `:resume`.
- No await-specific continuation fields are required by `dao.runtime`.
- Existing Yin VM scheduling tests continue to pass.

## Future Work

After v1 is stable, consider adding sugar:

```clojure
(await/<! stream)
```

where `dao.await` creates and reuses an implicit cursor inside the lexical
`await/go` body.

That feature requires a clear cursor policy:

- whether repeated reads from the same stream expression share a cursor,
- whether sharing is lexical or identity-based,
- how generated cursor bindings are named in the emitted forms,
- how cursor state appears in semantic datoms and provenance.

Do not add this policy until explicit-cursor await programs are implemented and
tested.

## Implementation Checklist

1. Add tests for the selected lowering route.
2. Add `dao.await` namespace.
3. Register await module functions or add Yang special lowering.
4. Implement `await/go` as a thin wrapper around `yang.clojure` compilation and
   the existing Yin AST-to-datoms path.
5. Drive await programs through an existing VM implementation in tests.
6. Verify blocking read/write behavior through `dao.runtime` and ringbuffer
   wakeups.
7. Run targeted tests:

```text
clj -M:test
clj -M:cljs -m shadow.cljs.devtools.cli compile test && node target/node-tests.js
```
