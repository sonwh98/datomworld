# DaoSpace v1: The Pure Streaming Bus

## Overview

**A DaoSpace is a topological medium for streams.** It is the unstructured, underlying fabric through which agents and resources coordinate. Unlike traditional tuple spaces, `dao.space` has **no compute, no indexes, and no query engine**. It is purely a collection of independent, append-only `dao.stream` logs. 

The architecture strictly follows the principle of **"dumb pipes, smart endpoints."** 
- **The Space** provides only sequential cursors over raw n-tuples. Structurally and mathematically, it is just a **set** of active `dao.stream` instances.
- **Hosted Continuations (Mobile Code)**: While the space has no native compute, it **hosts** `yin.vm` running continuations. Because Yin continuations are encoded entirely as tuples, they live inside the streams. This acts like stored procedures in a database, but because the state is just tuples, computation can transparently migrate across the network.
- **The Interpreters (Agents)**: In `datom.world`, agents *are* the interpreters. An agent is a high-level concept, but technically it is just a unified `yin.vm` function/closure/continuation. These agents pull the tuples over the wire and provide the actual CPU cycles: running pattern matching, or evaluating their own continuation state and writing the results back.
- **The Query Engine** (like Datomic) is a downstream consumer where agents materialize the matched tuples into structured indexes for positional Datalog queries.

Membership is **dynamic**: opening a member log (`ds/open!` through the handle) *is* joining the space; closing it leaves. Every writer owns its **own** log, so writes never contend or need routing — there is no shared write cursor to merge.

This design builds DaoSpace on top of DaoStream (append-only datom logs) to yield a highly scalable, infinitely decoupled coordination bus:

- **Dumb Pipes** — The space doesn't bottleneck on CPU evaluating thousands of rules.
- **Declarative coordination** — Interpreters define what they need through local pattern matching.
- **Persistent and portable** — Member logs are append-only files; history survives restart and replays.

## Core Concept: A Set of Streams; a Medium for Resources and Interpreters

```
DaoSpace = a set of file-backed dao.streams (member logs) whose union of
           tuples is a catalog, opened as a handle, written by ds/put! to a member
           log and read sequentially by external interpreters.
```

The **unit of coordination is the n-tuple**. Structurally the space is the **collection** of
file-backed member logs whose union of tuples is the **catalog**. Tuples enter the catalog over a **dynamic membership** of
member logs (`1..n`, each opened by `ds/open!` through the handle, joining and
leaving at runtime), and are observed by **interpreters** (`1..m`) that tail those streams and locally pattern match them.

Two things keep "catalog" precise: it is **accreting**, not static — tuples
accumulate append-only with history, added by inputs over time; and it is
**typeless** — it is an unindexed ledger of tuples, waiting for an interpreter
to slice it into typed views.

## Evolution of the Tuple Space

DaoSpace is a modern evolution of the classic tuple-space (e.g., Linda). It perfectly preserves the three defining characteristics of a tuple space:
1. **Generative Communication:** Agents communicate by producing data into a shared medium, rather than sending messages to specific receivers.
2. **Spatial Decoupling:** Writers and readers do not need to know each other's identities or network addresses.
3. **Temporal Decoupling:** Writers and readers do not need to be active at the same time.

However, `dao.space` diverges from the strict Linda definition in three critical ways to support distributed, persistent systems:

1. **No Destructive Consumption (Immutable vs Mutable)**
   In a classic tuple space, the `in` verb atomically removes a tuple from the space (often used for locks or task queues). Because DaoSpace is built on append-only streams, there is no `in` operation. You cannot delete a tuple from history. Instead, DaoSpace relies on *stigmergy*—to "consume" a task, an agent appends a new tuple asserting it claimed the task.
2. **Smart Endpoints, Dumb Space (Matching Inversion)**
   In a classic tuple space, the space itself acts as a compute engine that evaluates templates. In DaoSpace, the space is completely dumb; it just provides sequential cursors. The interpreters pull the tuples over the wire and run the pattern matching locally, preventing the space from becoming a CPU bottleneck.
3. **Structured Tuples vs Anonymous Arrays**
   Classic tuples are often untyped positional arrays (e.g., `[1, "hello"]`). In DaoSpace, the unit of coordination is the datom (an n-tuple, usually `[e a v t m]`). Tuples have intrinsic semantic structure rather than relying purely on position.

### Comparison with JavaSpaces

JavaSpaces was an object-oriented evolution of Linda that introduced several concepts highly relevant to DaoSpace, but the two take fundamentally different paths regarding state and execution:

1. **Leases vs. Immutable History**
   JavaSpaces heavily relies on *leases* to manage state. If an agent writes a tuple (object) and doesn't renew its lease, the space eventually deletes it to prevent garbage accumulation. DaoSpace rejects mutable state entirely; its streams are append-only persistent logs. There is no garbage collection or leases because the history is the permanent ledger.
2. **Objects vs. Pure Data**
   In JavaSpaces, tuples are Java objects (`Entry`), meaning they are inextricably tied to the JVM and Java classloading (RMI). In DaoSpace, tuples are pure, language-agnostic data. 
3. **Mobile Code**
   JavaSpaces allowed mobile code by passing Java objects with methods through the space. DaoSpace achieves mobile code via **Hosted Continuations**: Yin.VM continuations are encoded entirely as pure tuples (AST datoms). They migrate safely and transparently without needing a shared classloader or RMI.
4. **Distributed Transactions**
   JavaSpaces provided complex, two-phase distributed transactions to lock the space while withdrawing (`in`) and inserting (`out`) objects. Because DaoSpace streams are independent and append-only, the space itself has no centralized transaction engine. Atomic coordination is handled by the downstream interpreters.

## The Model: Asymmetric Read/Write

### Writing

Writing is completely decoupled from reading. Agents write to their **own** member logs,
never to a shared channel.

```clojure
;; Agent joins the space by opening a member log
(def log (ds/open! {:space space :type :file :file/dir "..."}))

;; Agent asserts a claim
(ds/put! log [:work/claim work-id agent-id])

;; Agent retracts a status
(ds/put! log [:db/retract work-id :work/status])
```

Because streams are autonomous persistent files, an agent can write to a stream **before** it joins a space. The tuples will safely accumulate offline. When the stream is later `join!`ed to a space, interpreters tailing that space can observe the stream's full history (if reading from the beginning) or just the newly arriving data.

**Writing is streaming; reading is streaming.** DaoSpace has no read verbs of its own. It provides cursors. There is no space-level query engine. Member streams (`dao.stream.file`, opened via `ds/open!`) carry tuples **in**, and cursors stream tuples **out**. Evaluative logic (like template matching or Datalog joins) happens locally in the agent process or further downstream in a materialized index.

### What Happens Internally

```
Agent 1 writes → its member log appends a [e :a :work/claim ...] group (datom frames to file)
Agent 2 tails → Agent 2's interpreter fetches the new tuples, pattern matches them locally, and acts on them.
```

A write lands on the writer's own member log. A read simply advances a cursor over a member log, pulling tuples over the wire.

## Architecture

```
            dao.space/open! ──► SPACE HANDLE (set of member logs)
                                   │            ▲
            ds/open! {:space …}    │            │  (ds/next)
            opens a member log ◄───┘            │  External Interpreter tails streams,
                                                │  applies pattern matching locally.
┌──────────────────┐  ┌──────────────────┐      │
│ member log A     │  │ member log B     │ ...  │   (1..n member logs; each its
│ d5, strict       │  │ d10, open        │──────┘    own append-only file log,
│ ds/put! frames → │  │ ds/put! frames → │           typed by dimension, strict?)
└────────┬─────────┘  └────────┬─────────┘
         │ datom frames (self-delimiting byte records)
┌────────▼─────────┐  ┌────────▼─────────┐
│ dao.stream.file  │  │ dao.stream.file  │  ...  (byte stream: async disk,
│ (byte substrate) │  │ (byte substrate) │       non-blocking — see dao.stream.file.md)
└──────────────────┘  └──────────────────┘
```

Three layers: **`dao.stream.file`** (a non-blocking byte stream over a file) →
**datom framing** (each datom serialized as a self-delimiting byte record, written
through `put!`) → **`dao.space`** (the collection). Interpreters and Datalog engines exist strictly downstream of `dao.space`.

## API

The surface is extremely small: **`dao.space/open!`** returns a handle;
**`dao.stream/open!`** (through the handle) opens member write logs you `ds/put!`
into. Member-log realization rides the same `dao.stream/open!` multimethod as every other
stream. `dao.space` does not provide native `q` or `match` functions. Instead, it provides `(dao.space/members space)` to allow external interpreters to retrieve the set of streams and tail them directly.

### Opening a space

```clojure
(require '[dao.space :as space])

;; A space is initialized with a descriptor map.
(def space (space/open! {:space/id :my-space}))
```

### Joining a space (Writing)

```clojure
;; An agent joins by opening a member log in that space.
(def my-log (ds/open! {:space space
                       :type :file
                       :file/dir "path/to/my/log"}))

(ds/put! my-log [123 :work/status :todo])
```

### Fault Tolerance: Agent Crashes

Because `dao.space` relies on persistent `dao.stream` files, it inherits robust "crash-only" semantics:
1. **Data Safety**: If an agent crashes, all tuples flushed to its stream prior to the crash are completely safe.
2. **Interpreter Behavior**: Interpreters currently tailing that crashed agent's stream simply reach the end of the file and yield (`ds/next` returns `:blocked`). They do not crash; they simply wait for new data.
3. **Seamless Recovery (Writing)**: `dao.stream` files are append-only. When the crashed agent restarts and calls `ds/open!` on its file, the stream opens in append mode, automatically placing the write head at the end of the file. The next `ds/put!` safely appends immediately after the last successfully flushed tuple.
4. **Seamless Recovery (Reading)**: If the agent was acting as an interpreter reading from streams, it can resume exactly where it left off by using persistent cursors. By periodically checkpointing its read offsets (e.g., writing its cursor state into its own output stream), a restarted agent can simply call `(ds/next stream {:position saved-offset})` to continue reading without skipping or reprocessing data.

### Leaving a space (Detaching)

```clojure
;; An agent leaves the space
(space/leave! space my-log)
```

When a stream is detached from the space, its underlying data remains completely intact in the persistent file. Leaving the space simply removes the stream from the topological set, meaning interpreters will no longer discover it when querying `(dao.space/members space)`.

A stream can be **re-joined** to the space at any time by calling `join!` again. Because the space itself holds no data (only references to streams), re-joining immediately re-exposes the stream's entire history and future data to any interpreters observing the space.

### Stream Lifecycle

Because streams are autonomous, they have their own independent lifecycle managed by the `IDaoStreamBound` protocol (`ds/close!`, `ds/closed?`). 
- Detaching a stream from a space (`space/leave!`) **does not** close the stream. It remains open and appendable by its owning agent.
- Closing a space **does not** necessarily close the autonomous streams, as they may be attached to other spaces or written to offline.

### Observing a space (Reading)

Reads never fold the space. The space provides a set of stream objects, and interpreters explicitly tail those streams using `ds/next`.

```clojure
;; An interpreter iterating over the space's member logs
(let [streams (dao.space/members space)]
  (doseq [s streams]
    (let [tuple (ds/next s cursor)]
      ;; Local pattern matching evaluated entirely in the interpreter process
      (when (matches-pattern? tuple [_ :work/status :todo])
        (handle-tuple tuple)))))
```
