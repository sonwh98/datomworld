# DaoSpace Design: Stigmergic Coordination via DaoStream

**Related documents:**
- `docs/design/daostream-design.md` — the stream transport foundation
- `docs/design/daodb-design.md` — queryable database that indexes the datom stream

## Overview

**DaoSpace** is datom.world's implementation of a tuple space: a shared store of datoms (facts) that agents can collectively query and modify to coordinate work. Unlike traditional message passing (explicit sender → receiver), DaoSpace enables **stigmergic coordination**: agents read shared facts, modify them based on local logic, and implicitly coordinate through the effects of those modifications.

This design builds DaoSpace on top of DaoStream (append-only datom log) and DaoDB (Datalog query engine). The result is:

- **Declarative coordination** — agents describe what they need, not who has it
- **Implicit messaging** — agents don't need to know about each other
- **Full queryability** — Datalog unlocks complex pattern matching
- **Persistent history** — the entire coordination log is available for replay, debugging, and auditing
- **Network-agnostic** — works in-process, across linked nodes, or over any DaoStream transport

## Core Concept: Tuple Space as a Shared Datom Log

```
Tuple Space = DaoStream of Datoms + DaoDB Query Engine
```

### What Agents See

```clojure
;; Agent perspective:
(let [space (open-tuple-space "work-queue")]

  ;; 1. Query: "What work is available?"
  (q '[:find ?id ?task
       :where [?id :work/status :todo]
              [?id :work/task ?task]]
    space)

  ;; 2. Claim work (write a fact)
  (put-tuple! space
    {:db/id work-id
     :work/status :in-progress
     :work/worker my-id})

  ;; 3. Complete work (update a fact)
  (put-tuple! space
    {:db/id work-id
     :work/status :completed
     :work/result result}))
```

### What Happens Internally

```
Agent 1 writes → Stream appends [e :work/status :in-progress] → DaoDB indexes it
Agent 2 queries → DaoDB scans indexed datoms → returns matching results
Agent 3 reads → Stream cursor advances → sees Agent 1's update
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Tuple Space (User-Facing API)                           │
│  - query(pattern)                                       │
│  - put-tuple!(fact)                                     │
│  - wait-for-pattern!(pattern)                           │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│ DaoDB (Queryable Index Layer)                           │
│  - run-query(query)                                     │
│  - datoms(index, components)                            │
│  - as-of(t)                                             │
│  - pull(pattern, eid)                                   │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│ DaoStream (Immutable Append-Only Log)                   │
│  - put! (append datom)                                  │
│  - next (cursor-based read)                             │
│  - close!                                               │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│ Transport (RingBuffer, WebSocket, File, Kafka, etc.)    │
└─────────────────────────────────────────────────────────┘
```

## Stigmergic Coordination Patterns

### Pattern 1: Work Queue

```clojure
;; Agents cooperate via a shared work queue

(defn producer [space]
  ;; Emit work
  (put-tuple! space
    {:db/id (random-id)
     :work/task "process payment"
     :work/status :todo}))

(defn worker [space worker-id]
  (loop []
    ;; Find available work
    (let [work (q '[:find ?id ?task
                   :where [?id :work/status :todo]
                          [?id :work/task ?task]]
               space)]
      (when (seq work)
        ;; Claim work (atomic-ish via transaction)
        (let [[id task] (first work)]
          (put-tuple! space
            {:db/id id
             :work/status :in-progress
             :work/assigned-to worker-id})

          ;; Do work
          (let [result (process task)]
            ;; Emit result
            (put-tuple! space
              {:db/id id
               :work/status :completed
               :work/result result})))

        ;; Repeat
        (recur)))))
```

### Pattern 2: Stigmergic Search (Ant Colony Optimization)

```clojure
;; Agents leave pheromone traces for others to follow

(defn ant [space colony-id]
  (loop [path [] node (random-node)]
    ;; 1. Emit pheromone: "I visited this node"
    (put-tuple! space
      {:db/id (random-id)
       :pheromone/colony colony-id
       :pheromone/node node
       :pheromone/strength 1.0
       :pheromone/time (now)})

    ;; 2. Query pheromones: "Where should I go next?"
    (let [neighbors (q '[:find ?next ?strength
                        :where [?node :graph/neighbor ?next]
                               [?p :pheromone/node ?next]
                               [?p :pheromone/strength ?strength]]
                      space)]

      ;; 3. Follow high-pheromone paths with probability
      (if-let [next (choose-by-weight neighbors)]
        (recur (conj path next) next)
        ;; Solution found
        (when (is-food? node)
          ;; Emit success trace
          (put-tuple! space
            {:db/id (random-id)
             :solution/path path
             :solution/cost (count path)}))))))
```

### Pattern 3: Watchdog / Exception Handling

```clojure
;; Agents monitor state and react to anomalies

(defn watchdog [space timeout-seconds]
  (loop []
    ;; Query: "Find work that's been in-progress too long"
    (let [stuck (q '[:find ?id ?started ?worker
                    :where [?id :work/status :in-progress]
                           [?id :work/assigned-to ?worker]
                           [?id :work/started-at ?started]
                           [(elapsed-seconds ?started) ?elapsed]
                           [(> ?elapsed ?timeout)]]
                  space)]

      ;; Reassign stuck work
      (doseq [[id started worker] stuck]
        (put-tuple! space
          {:db/id id
           :work/status :todo
           :work/assigned-to nil
           :work/reassigned-from worker
           :work/reassignment-reason :timeout}))

      ;; Check again soon
      (Thread/sleep (* timeout-seconds 1000))
      (recur))))
```

### Pattern 4: Collaborative Filtering

```clojure
;; Agents collectively build and improve models

(defn agent-observer [space agent-id]
  (loop [last-t 0]
    ;; 1. Observe recent events
    (let [events (q '[:find ?e ?action ?time
                     :where [?e :event/action ?action]
                            [?e :event/time ?time]
                            [(> ?time ?last-t)]]
                   space)]

      ;; 2. Update local belief
      (doseq [[e action time] events]
        (update-model! agent-id action))

      ;; 3. Emit shared insight if found
      (when-let [pattern (detect-pattern agent-id)]
        (put-tuple! space
          {:db/id (random-id)
           :pattern/discovered-by agent-id
           :pattern/rule pattern
           :pattern/confidence (calculate-confidence agent-id)}))

      ;; 4. Read and apply patterns from other agents
      (let [patterns (q '[:find ?rule ?author
                         :where [?p :pattern/rule ?rule]
                                [?p :pattern/discovered-by ?author]
                                [(> ?author ?agent-id)]]
                       space)]
        (doseq [[rule author] patterns]
          (apply-pattern! agent-id rule)))

      (recur (+ 1 last-t)))))
```

## API

### Core Operations

#### `open-tuple-space [name & {:keys [transport]}]`

Open a new tuple space.

```clojure
;; In-process (default)
(def space (open-tuple-space "work-queue"))

;; File-backed
(def space (open-tuple-space "audit-log"
  :transport {:type :file
              :path "/var/log/tuples.log"}))

;; Network-backed (future)
(def space (open-tuple-space "distributed"
  :transport {:type :websocket
              :url "ws://broker:8000"}))
```

#### `query [space datalog-query & inputs]`

Query the current state of the tuple space.

```clojure
;; Simple pattern
(query space '[:find ?id ?task
              :where [?id :work/status :todo]
                     [?id :work/task ?task]])

;; With bindings
(query space '[:find ?result
              :where [?e :work/id ?id]
                     [?e :work/result ?result]]
       {:id work-id})

;; Aggregation
(query space '[:find (count ?id)
              :where [?id :work/status :completed]])
```

#### `put-tuple! [space fact]`

Append a tuple (datom) to the tuple space.

```clojure
;; Single fact
(put-tuple! space
  {:db/id 42
   :work/status :completed
   :work/result "OK"})

;; Entity reference
(put-tuple! space
  {:db/id work-id
   :work/assigned-to agent-id})

;; Retractable fact (future)
(retract-tuple! space work-id :work/status)
```

#### `wait-for-pattern [space query & {:keys [timeout]}]`

Block until a pattern matches (convenience for polling).

```clojure
;; Wait for any completed work
(wait-for-pattern space
  '[:find ?id ?result
    :where [?id :work/status :completed]
           [?id :work/result ?result]])

;; With timeout
(wait-for-pattern space pattern :timeout 5000)
```

#### `bounded-stream->db [stream cursor]`

Materialize a stream prefix into a queryable DaoDB snapshot.

```clojure
;; Get queryable DB up to current position
(let [db (bounded-stream->db (tuple-space->stream space) cursor)]
  (q '[:find ?e :where [?e :work/status :completed]] db))
```

#### `as-of-time [space timestamp]`

Query the tuple space state at a specific historical point.

```clojure
;; "What was the work queue state at 2024-01-01?"
(query (as-of-time space (instant "2024-01-01"))
  '[:find ?id ?status
    :where [?id :work/status ?status]])
```

## Coordination Semantics

### Consistency

**Strong consistency within a single-threaded agent:**

```clojure
;; Agent's view
(put-tuple! space {:db/id id :work/status :in-progress})
;; Immediately visible in subsequent queries
(= :in-progress (:work/status (query space '[...])))
```

**Eventual consistency across agents:**

```clojure
;; Agent 1 writes
(put-tuple! space {:db/id id :work/status :in-progress})

;; Agent 2's view depends on stream position
(if (>= agent-2-cursor (position-of-write))
  ;; Agent 2 sees the write
  (assert (= :in-progress (query space '[...])))
  ;; Agent 2 hasn't caught up yet
  (assert (not= :in-progress (query space '[...]))))
```

### Ordering

**Within an agent's timeline:**
- Writes are totally ordered (append-only stream)
- Reads of a materialized DB snapshot are consistent
- Re-querying at a later cursor position includes later writes

**Across agents:**
- No global ordering of operations
- Agents see facts in append order but may process them at different rates
- Causality is preserved via timestamps in tuples (application's responsibility)

**Example: two agents updating the same entity**

```clojure
;; Agent A at t=100
(put-tuple! space {:db/id 42 :counter 1})

;; Agent B at t=101
(put-tuple! space {:db/id 42 :counter 2})

;; Both updates are in the stream, in order
;; Agents see both; interpretation is application-defined
;; (Last-write-wins? Sum them? Conflict detection?)
```

### Atomicity

**Single tuples are atomic:**
```clojure
(put-tuple! space {:db/id id :work/status :in-progress :worker agent-id})
;; Appears atomically to other agents
```

**Multi-tuple transactions need application-level coordination:**
```clojure
;; NOT atomic across the space:
(put-tuple! space {:db/id id :work/status :in-progress})
(put-tuple! space {:db/id id :work/assigned-to agent-id})

;; Remedies:
;; 1. Single tuple with multiple attributes
(put-tuple! space {:db/id id :work/status :in-progress :work/assigned-to agent-id})

;; 2. Transaction envelope (application-defined)
(put-tuple! space
  {:db/id (random-id)
   :transaction/id tx-id
   :transaction/committed true
   :transaction/datoms [{:db/id id :work/status :in-progress}
                        {:db/id id :work/assigned-to agent-id}]})

;; 3. Causality tracking (application-defined)
(put-tuple! space {:db/id id :version 1 :work/status :in-progress})
(put-tuple! space {:db/id id :version 2 :work/assigned-to agent-id
                   :depends-on 1})
```

## Implementation

### What's Already Available

- **DaoStream** (`src/cljc/dao/stream.cljc`): append-only datom log
- **DaoDB** (`src/cljc/dao/db.cljc`): queryable index with Datalog
- **IDaoStreamWaitable**: transport-local notifications when data arrives
- **Cursors**: independent read positions for multiple agents

### What Needs to Be Built

New namespace: `src/cljc/dao/tuple-space.cljc`

```clojure
(ns dao.tuple-space
  "Tuple space: stigmergic coordination via DaoStream + DaoDB")

;; Core functions (to implement)
(defn open-tuple-space [name & opts])
(defn query [space datalog-query & inputs])
(defn put-tuple! [space fact])
(defn retract-tuple! [space id attr])
(defn wait-for-pattern [space query & opts])
(defn bounded-stream->db [stream cursor])
(defn as-of-time [space timestamp])

;; Internal helpers
(defn- stream-datoms [stream from-cursor to-cursor])
(defn- materialize-db [datoms])
(defn- cursor-position [stream])
(defn- apply-datoms-to-db [db datoms])
```

### Reference Implementation Pattern

```clojure
(defrecord TupleSpace [stream db cursor-ref]

  ;; Query current state
  IDaoDB
  (run-query [this query inputs]
    ;; 1. Advance to latest stream position
    (let [latest-cursor (advance-cursor stream cursor-ref)]
      ;; 2. Load any new datoms since last query
      (let [new-datoms (read-since cursor-ref stream)]
        ;; 3. Update DB with new datoms
        (let [db' (apply-datoms-to-db db new-datoms)]
          ;; 4. Execute query against updated DB
          (run-query db' query inputs)))))

  ;; Append to stream
  (put-tuple! [this fact]
    (ds/put! stream fact)))
```

## Use Cases

### 1. Distributed Work Queue
- **Agents**: workers
- **Tuples**: `{:work/id :work/status :work/assigned-to :work/result}`
- **Patterns**: "find todo items", "find my completed items", "find overdue items"

### 2. Agent Swarm Coordination
- **Agents**: independent agents (bots, particles, etc.)
- **Tuples**: `{:agent/id :agent/position :agent/state}`
- **Patterns**: "neighbors within radius", "highest-scoring agents", "consensus decisions"

### 3. Event Sourcing
- **Agents**: domain services, projections
- **Tuples**: domain events `{:event/type :event/aggregate-id :event/data}`
- **Patterns**: "events for aggregate X", "events since timestamp", "by event type"

### 4. Pub-Sub / Fan-Out
- **Agents**: subscribers
- **Tuples**: published messages `{:message/id :message/topic :message/data}`
- **Patterns**: "all messages on topic X", "unread messages for agent Y"

### 5. Audit Log
- **Agents**: services, compliance systems
- **Tuples**: audit events `{:audit/actor :audit/action :audit/resource :audit/timestamp}`
- **Patterns**: "all actions by user X", "all resource modifications", "compliance queries"

### 6. Collaborative Filtering
- **Agents**: recommendation engines, data collectors
- **Tuples**: observations, models, patterns
- **Patterns**: "recent observations", "high-confidence models", "novelty detection"

## Verification and Testing

### Unit Tests

```clojure
(deftest open-tuple-space-test
  (testing "Open in-memory tuple space"
    (let [space (open-tuple-space "test")]
      (is (some? space)))))

(deftest put-query-test
  (testing "Put and query round trip"
    (let [space (open-tuple-space "test")]
      (put-tuple! space {:db/id 1 :work/status :todo})
      (let [result (query space '[:find ?status
                                 :where [1 :work/status ?status]])]
        (is (= [[:todo]] result))))))

(deftest multiple-agents-test
  (testing "Two agents coordinate via tuple space"
    (let [space (open-tuple-space "test")]
      ;; Agent 1 writes
      (put-tuple! space {:db/id 1 :work/task "task-1"})
      ;; Agent 2 reads
      (let [tasks (query space '[:find ?task :where [?id :work/task ?task]])]
        (is (= [["task-1"]] tasks))))))

(deftest as-of-time-test
  (testing "Historical query returns state at point in time"
    (let [space (open-tuple-space "test")
          t0 (now)]
      (put-tuple! space {:db/id 1 :work/status :todo})
      (let [t1 (now)]
        (put-tuple! space {:db/id 1 :work/status :in-progress})
        (let [past-state (as-of-time space t0)
              result (query past-state '[:find ?status
                                        :where [1 :work/status ?status]])]
          (is (= [[:todo]] result)))))))
```

### Integration Tests

```clojure
(deftest work-queue-pattern-test
  (testing "Producer-worker coordination"
    (let [space (open-tuple-space "work-queue")]
      ;; Producer emits work
      (future (dotimes [i 10]
                (put-tuple! space {:db/id i :work/status :todo})
                (Thread/sleep 10)))

      ;; Worker processes work
      (let [completed (atom [])]
        (dotimes [i 10]
          (wait-for-pattern space '[:find ?id :where [?id :work/status :todo]])
          (let [[[id]] (query space '[:find ?id :where [?id :work/status :todo]])]
            (put-tuple! space {:db/id id :work/status :completed})
            (swap! completed conj id)))

        ;; Verify all work completed
        (is (= 10 (count @completed)))))))
```

## Design Lessons from Linda and JavaSpace

Tuple spaces have a rich history (Linda 1986, JavaSpace 2000s). This design incorporates hard-won lessons from their successes and mistakes, creating a modernized approach that addresses their fundamental limitations.

### Quick Comparison

| Aspect | Linda | JavaSpace | Tuple-Space |
|--------|-------|-----------|-------------|
| **Data Model** | Untyped positional tuples | Java objects | Named attributes (datoms) |
| **Pattern Matching** | Wildcards only | Field-based matching | Full Datalog queries |
| **Read Operations** | Blocking `in()` / `rd()` | Blocking `read()` / `take()` | Non-blocking `query()` + optional `wait-for-pattern()` |
| **History** | Lost (destructive) | Lost | Immutable append-only log |
| **Observability** | None (opaque space) | Limited | Full Datalog queries over state |
| **Transport** | Tightly coupled | Java/Jini specific | DaoStream-agnostic (ringbuffer, WebSocket, Kafka, etc.) |
| **Expiration** | None | Leasing (complex, fragile) | Application-controlled |
| **Multi-tuple Atomicity** | Per-tuple only | Per-object only | Via envelopes or multi-attribute tuples |
| **Debugging** | Blind (no visibility) | Blind | Full queryable history |
| **Multi-reader Fan-Out** | Manual copying | Manual copying | Automatic (independent cursors) |

---

### ✅ Good Ideas We Adopt

1. **Pattern matching as coordination primitive** — but improved with full Datalog instead of wildcards
2. **Non-destructive read** — agents can observe without consuming
3. **Asynchronous coordination** — no agent blocks another; coordination happens via shared state
4. **Declarative queries** — agents describe what they need, not how to get it

### ❌ Critical Problems We Avoid

**Problem 1: Blocking Operations Cause Deadlock**

Linda/JavaSpace mistake: `in(tuple)` and `take(tuple)` block agents, leading to circular waits and deadlock.

Tuple-space fix: All operations are non-blocking.
```clojure
;; Non-blocking query (always returns immediately)
(query space '[:find ?worker :where [?worker "ready"]])
;; → [] if no match (no deadlock possible)

;; Optional blocking is explicit and interruptible
(wait-for-pattern space pattern :timeout 5000)
```

**Problem 2: Untyped/Loosely Structured Data Causes Confusion**

Linda mistake: Tuples are positional, untyped arrays. Agents can't coordinate reliably because schema is implicit.
```clojure
[42 "task" :status]  ;; What does 42 mean? What's the order?
["task" 42 :status]  ;; Same semantics? Different?
```

Tuple-space fix: Named attributes enable schema clarity and querying.
```clojure
{:db/id task-id
 :work/status :todo
 :work/task "process payment"}
;; Clear, queryable, validatable, self-describing
```

**Problem 3: Limited Pattern Matching Restricts Coordination Logic**

Linda limitation: Can only match on specific values or wildcards. Can't express OR, arithmetic, joins, or aggregations.

Tuple-space fix: Full Datalog unlocks complex coordination patterns.
```clojure
;; Linda can't express this:
(query space '[:find ?id ?task
              :where (or [?id :work/status :todo]
                         [?id :work/status :in-progress])
                     [?id :work/timeout ?t]
                     [(> ?t 3600)]])
```

**Problem 4: No Causality / History**

Linda/JavaSpace mistake: Destructive read (take) means tuples vanish. No audit trail, no debugging visibility, no replay capability.

Tuple-space fix: Append-only immutable log preserves complete history.
```clojure
;; Time travel: see state at any point
(as-of-time space (instant "2024-01-01"))

;; Full audit trail
(query space '[:find ?who ?action ?when
              :where [?log :action/who ?who]
                     [?log :action/type ?action]
                     [?log :action/time ?when]])

;; Debugging: "what happened to task X?" Answer: see full history
```

**Problem 5: Tight Coupling to Runtime**

JavaSpace mistake: Tightly bound to Java serialization, Java Jini protocol, and complex leasing semantics. Hard to use from other languages, fragile on network failures.

Tuple-space fix: Transport-agnostic via DaoStream abstraction.
```clojure
;; Same API, different transport
(open-tuple-space "work" :transport {:type :ringbuffer})   ;; in-process
(open-tuple-space "work" :transport {:type :websocket :url "..."})  ;; remote
(open-tuple-space "work" :transport {:type :kafka :broker "..."})  ;; distributed

;; All use same query language, same semantics
```

**Problem 6: Complex Leasing/Expiration**

JavaSpace mistake: Tuples auto-expire when leases expire. Agents must renew leases, and leases can expire while processing (losing work). Complex state machine, fragile behavior.

Tuple-space fix: Explicit application-controlled expiration.
```clojure
;; No implicit expiration. Agents explicitly manage work lifecycle.
(put-tuple! space {:db/id task-id
                  :work/status :in-progress
                  :work/claimed-by agent-id
                  :work/claimed-at (now)})

;; Watchdog agent explicitly detects timeouts (no risk of silent loss)
(query space '[:find ?id ?claimed-at
              :where [?id :work/status :in-progress]
                     [?id :work/claimed-at ?claimed-at]
                     [(> (elapsed ?claimed-at) 3600)]])
```

**Problem 7: No Transactional Semantics**

Linda/JavaSpace limitation: Coordination across multiple tuples is not atomic. Agent can crash between operations, losing work or leaving inconsistent state.

Tuple-space fix: Multiple options for atomic coordination.
```clojure
;; Option 1: Multi-attribute tuple (atomic write)
(put-tuple! space {:db/id task-id
                   :work/status :in-progress
                   :work/assigned-to worker-id})

;; Option 2: Transaction envelope (application-defined)
(put-tuple! space {:transaction/id tx-id
                   :transaction/datoms [{:db/id task-id :status :in-progress}
                                        {:db/id worker-id :current-task task-id}]})

;; Option 3: Idempotent operations (can safely retry)
(put-tuple! space {:db/id task-id :work/status :completed :timestamp (now)})
```

**Problem 8: Destructive Operations Limit Patterns**

Linda/JavaSpace mistake: `take()` is destructive. Only one agent can process a tuple. Fan-out requires manual copying. No re-processing capability.

Tuple-space fix: Non-destructive reads with independent cursors enable multi-agent processing.
```clojure
;; All agents see all events, independently
;; No contention, no copying needed
;; Multiple workers can process same event

(defn worker-1 [space]
  ;; Processes events at its own pace
  (loop [cursor {:position 0}]
    ...))

(defn worker-2 [space]
  ;; Processes same events independently
  (loop [cursor {:position 0}]
    ...))
```

**Problem 9: No Visibility into Coordination**

Linda/JavaSpace opacity: Can't query the space itself. No visibility into pending work, who's processing what, historical trends. Debugging is blind guessing.

Tuple-space fix: Datalog makes the coordination space itself observable.
```clojure
;; How much work is waiting?
(query space '[:find (count ?id) :where [?id :work/status :todo]])

;; Who is processing what?
(query space '[:find ?worker (count ?id)
              :where [?id :work/assigned-to ?worker]
                     [?id :work/status :in-progress]])

;; Historical trends
(query (as-of-time space one-hour-ago)
  '[:find (count ?id) :where [?id :work/status :completed]])

;; Debug: What happened to task X?
(query space '[:find ?who ?action ?when
              :where [?log :entity ?task-id]
                     [?log :action ?action]
                     [?log :who ?who]
                     [?log :when ?when]])
```

## Design Rationale

### Why Build on DaoStream + DaoDB?

1. **Immutability** — coordination history is preserved, enabling replay and auditing
2. **Queryability** — full Datalog power instead of limited pattern matching (Linda)
3. **Scalability** — cursor-based reads allow independent agent throughput
4. **Transport Agnosticity** — agents work in-process, across nodes, or over any DaoStream transport
5. **Causality** — append-only log preserves causality naturally

### Why Stigmergic (Not Linda)?

Linda's tuple space suffers from fundamental design choices:
- **Untyped data** prevents schema-aware coordination
- **Blocking operations** (in, take) cause deadlocks
- **Destructive reads** prevent replay and multi-reader patterns
- **Limited pattern matching** restricts coordination logic
- **No history** makes debugging and auditing impossible

Stigmergic coordination fixes these issues:
- **Declarative queries** (Datalog) instead of imperative blocking
- **Non-destructive reads** via independent cursors
- **Named attributes** (datoms) instead of positional tuples
- **Complete history** for audit, replay, and time-travel queries
- **Implicit coordination** through shared immutable log
- **Observable** state via full Datalog queries
- **Composable** patterns that can be layered and refined

### Relationship to Event Sourcing

Tuple spaces are **event sourcing + queryability**.

- **Event sourcing**: replay history to reconstruct state
- **Tuple space**: replay history + full query power at any point in time

A tuple space is essentially "event sourcing where the 'events' are datoms and the 'store' is queryable."

## Design Philosophy: Modern Tuple Spaces

This design is **Linda for the 2020s**: it preserves the elegant core idea (agents coordinate via shared facts) while fixing the fundamental problems that plagued Linda and JavaSpace.

### Core Principles

1. **Immutability First** — The tuple space is an append-only log, not a mutable heap. This prevents races, enables replay, and preserves history.

2. **Non-Blocking by Default** — Queries never block. Blocking is optional, explicit, and interruptible. This prevents deadlocks and makes failure modes clear.

3. **Declarative Coordination** — Agents describe what they need (via Datalog), not what to do (imperative blocking). This enables complex patterns without tight coupling.

4. **Full Queryability** — The space itself is queryable via Datalog. Observability, debugging, auditing, and historical analysis are built-in, not bolted on.

5. **Transport Agnostic** — Built on DaoStream abstraction. Same API works in-process, cross-process, or over any transport. No binding to specific serialization, language, or protocol.

6. **Schema-Aware** — Named attributes replace positional tuples. Agents can understand and validate the data they coordinate on.

7. **Explicit Over Implicit** — No hidden expiration, no automatic cleanup, no magic. Application logic drives coordination policy.

### Why This Matters

Linda and JavaSpace failed because they treated the tuple space as a **mutable shared heap**. This led to:
- **Blocking semantics** (one agent blocks another)
- **Opacity** (can't see what's in the space)
- **Fragility** (deadlocks, lost work, mysterious failures)
- **Limited expressiveness** (pattern matching is weak)

By building on **append-only streams + Datalog queries**, tuple-space achieves:
- **Safety** (no deadlocks, clear failure modes)
- **Clarity** (full observability and queryability)
- **Expressiveness** (full Datalog power)
- **Debuggability** (complete immutable history)
- **Auditability** (all coordination actions are recorded)

## Deferred

- Retract/update semantics (currently append-only; retractions require new datoms)
- Subscription filtering (notify agents only on matching patterns)
- Distributed consensus for multi-agent decisions
- Conflict resolution strategies (last-write-wins, CRDTs, application-defined)
- Tuple space garbage collection / archival
- Schema validation (cardinality, value types)
- Full-text search integration
- Temporal query operators (before, after, during)
