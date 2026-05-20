# Coroutines in Yin VM

## Context

The AST Walker VM already has the primitives needed for coroutines. No new node types are required. Three models are implementable with existing primitives, each suited to different use cases.

## Existing Primitives

| Primitive | Location | What it does |
|---|---|---|
| `:vm/current-continuation` | `ast_walker.cljc:396-400` | Captures the current continuation as `{:type :reified-continuation, :k k, :env env}` |
| `:vm/park` | `ast_walker.cljc:402-413` | Suspends the current computation, stores continuation in `:parked` map, halts. Returns the parked continuation descriptor as the VM's value. |
| `:vm/resume` | `ast_walker.cljc:415-428` | Restores a parked continuation with a given value. |
| `stream/next` (parking) | `ast_walker.cljc:143-164` | Parks the continuation in the wait-set when a stream is empty. |
| `stream/put` (parking) | `ast_walker.cljc:104-126` | Parks the continuation in the wait-set when a stream is at capacity. |
| Cooperative scheduler | `ast_walker.cljc:469-612` | `check-wait-set` promotes entries to `run-queue` when stream conditions clear. `resume-from-run-queue` restores them as the active computation. |
| Multi-cycle state | `ast_walker.cljc:586-589` | `load-program` preserves store, wait-set, parked map across cycles. The VM is a value; each cycle transforms it. |

## Three Coroutine Models

### 1. Stream-based (fully in-VM, works today)

Streams are the natural yield mechanism. `put` on a full stream parks. `next` on an empty stream parks. The scheduler resumes parked computations when the stream condition clears. Two computations sharing streams form a coroutine pair.

**Pattern: producer/consumer**

```
stream S (capacity 1)

Coroutine A (producer):        Coroutine B (consumer):
  put 1 into S                   next from S -> gets 1
  put 2 into S (parks, full)     next from S -> gets 2
  put 3 into S                   next from S -> gets 3
```

A parks on `put` when S is full. B's `next` drains S, which unblocks A. B parks on `next` when S is empty. A's `put` fills S, which unblocks B. The scheduler interleaves them automatically.

**Pattern: symmetric coroutines (ping-pong)**

Two streams, each capacity 1:

```
stream A->B (capacity 1)
stream B->A (capacity 1)

Coroutine A:                   Coroutine B:
  put value into A->B            next from A->B -> gets value
  next from B->A (parks)         put response into B->A
  gets response                  next from A->B (parks)
  put next value into A->B       gets next value
  ...                            ...
```

Each coroutine yields by doing `put` then `next`. The stream capacity enforces turn-taking.

**Bootstrap:** The first coroutine must seed a value before blocking. If A does `next` on an empty stream as its first action, it parks, and nobody is running to start B. Solution: A puts first, then reads. Or: drive the bootstrap from the host via separate `load-program`/`run` cycles (see model 2).

**Strengths:**
- No special primitives beyond streams
- The scheduler handles all interleaving
- Backpressure is automatic (capacity controls yield frequency)
- Works entirely within a single VM program

### 2. Park/resume, host-driven (works today)

`:vm/park` suspends the current computation and returns the parked continuation descriptor as the VM's final value. The host (or a higher-level scheduler) inspects this value, extracts the park-id, and constructs a `:vm/resume` AST node for the next cycle. VM state carries across `load-program`/`run` cycles.

**Pattern:**

```
Cycle 1: load coroutine A, run
         -> A does work, parks
         -> VM halts, value = {:type :parked-continuation, :id :parked-0, ...}

Cycle 2: load {:type :vm/resume, :parked-id :parked-0, :val :go}
         -> A resumes with value :go, does more work, parks again
         -> VM halts, value = {:type :parked-continuation, :id :parked-1, ...}

Cycle 3: load coroutine B (separate AST), run
         -> B does work, parks
         -> VM halts, value = {:type :parked-continuation, :id :parked-2, ...}

Cycle 4: load {:type :vm/resume, :parked-id :parked-1, :val result-from-B}
         -> A resumes with B's result
```

The host decides which parked continuation to resume and what value to pass. This is the most explicit model: the scheduling policy lives outside the VM.

**Strengths:**
- Host has full control over scheduling order
- No bootstrap problem (host drives each cycle)
- Parked continuations are values in the VM store, inspectable and serializable
- Natural fit for externally-driven systems (event loops, network handlers)

**Trade-off:** `:vm/resume` currently takes static `:parked-id` and `:val` fields baked into the AST node. The host constructs these after seeing the park result, so this works. For in-VM resume with runtime-computed park-ids, `:vm/resume` would need to evaluate its arguments as sub-expressions (same pattern as `:stream/put` evaluating `:target` and `:val`). This is a behavioral change to an existing node type, not a new node type.

### 3. call/cc via current-continuation + park/resume (works today)

`:vm/current-continuation` captures the current continuation as a reified value. Combined with park and resume, this gives call/cc semantics without a dedicated call/cc node type.

**Pattern:**

```
;; Capture continuation, store it, park
(let [k (current-continuation)]    ;; k = {:type :reified-continuation, ...}
  (store-put :saved-k k)           ;; save to VM store
  (park))                          ;; halt, return parked-cont to host

;; Later, from another computation or host cycle:
(let [k (store-get :saved-k)]      ;; retrieve saved continuation
  (resume k some-value))           ;; resume where we left off
```

The continuation is a first-class value. It can be stored, passed through streams, or sent to another node (via datom migration). The combination of capture + store + park + resume gives the same power as Scheme's `call/cc`.

**Invoking reified continuations as functions:** Currently the application dispatch (`ast_walker.cljc:325`) throws "Cannot apply non-function" for `:reified-continuation` values. Adding one `cond` clause to the application dispatch would make captured continuations callable as functions, enabling the classic pattern:

```
(call/cc (lambda (k)
  ;; k is callable, invoking it jumps back to the call/cc site
  (k 42)))
;; -> 42
```

This is a single `cond` branch in existing code, not a new node type.

**Strengths:**
- Maximum flexibility (full continuation control)
- Continuations are values, composable with all other primitives
- Enables advanced patterns: exceptions, backtracking, coroutine libraries

## Comparison

| Property | Stream-based | Park/resume | call/cc |
|---|---|---|---|
| Scheduling | Automatic (VM scheduler) | External (host) | Manual (user code) |
| Yield mechanism | Stream capacity/emptiness | Explicit park | Continuation invocation |
| Bootstrap | Seed initial value | Host drives cycles | Host or self-bootstrapping |
| Communication | Stream values | Resume values | Continuation arguments |
| Topology | Fixed at stream creation | Host-determined | Dynamic |
| Complexity | Simplest | Medium | Most flexible |

## Relationship to Pi-Calculus

All three models connect to the pi-calculus. Streams are channels. Putting a stream-ref through a stream is channel mobility (name passing). Coroutines communicating via streams are pi-calculus processes communicating via channels.

The stream-based model maps directly: each coroutine is a process, each stream is a channel, `put`/`next` are send/receive, capacity is synchronization.

Park/resume adds explicit process suspension, analogous to the pi-calculus restriction operator (creating a private channel/continuation).

call/cc adds first-class continuations as mobile values, enabling dynamic process topology changes (new communication patterns created at runtime).

## Implementation Notes

All three models work today with the AST Walker VM. The other VMs (Stack, Register, Semantic) need the primitives added first (see `docs/continuation-stream-feature-parity-across-vms.md`).

Optional ergonomic improvements (none required, none are new node types):
- Make `:vm/resume` evaluate its arguments as sub-expressions (enables in-VM resume with runtime-computed park-ids)
- Add a `:reified-continuation` clause in application dispatch (makes captured continuations callable as functions)
- Add `:do` node type (evaluate N expressions in sequence, return the last, avoids nested `((fn [_] ...) effect)` for sequencing)
- Add `:let` node type (sequential bindings + body, avoids nested immediately-invoked lambdas)
