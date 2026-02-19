(ns datomworld.examples.pi-calculus-patterns
  "Practical π-calculus communication patterns for DaoStream

  This module demonstrates how π-calculus primitives map to DaoStream operations.
  Each pattern shows the π-calculus notation alongside working Clojure code.

  π-Calculus Primer:
  - P | Q       : Parallel composition (processes run concurrently)
  - c̄⟨v⟩.P     : Send value v on channel c, then continue as P
  - c(x).Q      : Receive on channel c, bind to x, continue as Q
  - (νc)P       : Create new channel c (scope restriction)
  - !P          : Replication (infinite copies of P)
  "
  (:require
    [clojure.core.async :as async :refer
     [go go-loop chan <! >! <!! >!! close!]]
    [clojure.set :as set]))


;; =============================================================================
;; Pattern 1: Basic Send/Receive
;; π-calculus: c̄⟨42⟩.0 | c(x).x̄⟨x+1⟩.0
;; "Send 42 on c, in parallel receive on c and send result+1"
;; =============================================================================

(defn basic-send-receive
  "Simplest π-calculus pattern: one sender, one receiver"
  []
  (let [c (chan)] ; Create channel c
    ;; Sender process: c̄⟨42⟩
    (go (>! c 42) (println "Sent 42 on channel c"))
    ;; Receiver process: c(x).println(x)
    (go (let [x (<! c)] (println "Received" x "on channel c")))
    ;; Return channel for cleanup
    c))


;; Usage:
;; (basic-send-receive)
;; => Sent 42 on channel c
;; => Received 42 on channel c


;; =============================================================================
;; Pattern 2: Mobile Channels (π-calculus superpower)
;; π-calculus: c̄⟨d⟩.0 | c(x).x̄⟨"hello"⟩.0
;; "Send channel d over channel c, receiver uses it"
;; =============================================================================

(defn mobile-channels
  "Demonstrate channel mobility - sending channels as data"
  []
  (let [c (chan)  ; Meta-channel
        d (chan)] ; Channel to be sent
    ;; Process A: sends channel d over channel c
    ;; π-calculus: c̄⟨d⟩.d(reply).println(reply)
    (go (>! c d) ; Send the channel itself!
        (println "Sent channel d over channel c")
        (let [reply (<! d)] (println "Got reply on d:" reply)))
    ;; Process B: receives channel, uses it
    ;; π-calculus: c(x).x̄⟨"hello"⟩
    (go (let [received-channel (<! c)]
          (println "Received a channel!")
          (>! received-channel "hello from B")))
    {:c c, :d d}))


;; Usage:
;; (mobile-channels)
;; => Sent channel d over channel c
;; => Received a channel!
;; => Got reply on d: hello from B


;; =============================================================================
;; Pattern 3: Channel Restriction (νc)
;; π-calculus: (νc)(c̄⟨42⟩ | c(x).println(x))
;; "Create private channel c, use it locally"
;; =============================================================================

(defn channel-restriction
  "Private channels - scope limitation"
  []
  ;; (νc) creates new channel scoped to this function
  (let [c (chan)] ; Private channel
    ;; Both processes run in parallel, sharing private c
    (go (>! c 42) (println "Sent on private channel"))
    (go (let [x (<! c)] (println "Received on private channel:" x)))
    ;; Channel c is not exposed outside
    nil))


;; Usage:
;; (channel-restriction)
;; => Sent on private channel
;; => Received on private channel: 42


;; =============================================================================
;; Pattern 4: Replication (!P)
;; π-calculus: !c(x).x̄⟨x+1⟩
;; "Infinite server: always ready to receive and respond"
;; =============================================================================

(defn replicated-server
  "Persistent server using core.async go-loop (! operator)"
  []
  (let [c (chan)]
    ;; Replicated process: !c(x).respond(x+1)
    (go-loop []
      (when-let [x (<! c)]
        (println "Server received:" x)
        (>! c (inc x)) ; Send back x+1
        (recur))) ; Loop forever (replication)
    ;; Client interactions
    (go (>! c 1)
        (println "Client got:" (<! c))
        (>! c 10)
        (println "Client got:" (<! c)))
    c))


;; Usage:
;; (replicated-server)
;; => Server received: 1
;; => Client got: 2
;; => Server received: 10
;; => Client got: 11


;; =============================================================================
;; Pattern 5: Choice (π+ calculus extension)
;; Nondeterministic choice between channels
;; =============================================================================

(defn channel-choice
  "Select from multiple channels (like π-calculus choice)"
  []
  (let [c1 (chan)
        c2 (chan)
        result (chan)]
    ;; Process offers choice: c1(x).P + c2(y).Q
    (go (let [[value ch] (async/alts! [c1 c2])]
          (condp = ch
            c1 (println "Chose channel c1, got:" value)
            c2 (println "Chose channel c2, got:" value))
          (>! result value)))
    ;; One sender will win
    (go (<! (async/timeout 10)) (>! c1 "from c1"))
    (go (<! (async/timeout 5)) (>! c2 "from c2")) ; This will win (sent
    ;; first)
    result))


;; Usage:
;; (<!! (channel-choice))
;; => Chose channel c2, got: from c2


;; =============================================================================
;; Pattern 6: DaoStream Datom Flow
;; π-calculus: stream̄⟨[e a v t m]⟩.P | stream(datom).interpret(datom).Q
;; =============================================================================

(defn datom-stream
  "Model DaoStream as π-calculus channel"
  []
  (let [stream (chan 100)] ; Buffered stream
    ;; Producer: appends datoms
    ;; π-calculus: !stream̄⟨datom⟩
    (go-loop [tx-id 100]
      (<! (async/timeout 100))
      (let [datom [1 :person/name "Alice" tx-id {}]]
        (>! stream datom)
        (println "Appended datom:" datom)
        (recur (inc tx-id))))
    ;; Consumer 1: DaoDB (interprets as entities)
    ;; π-calculus: !stream(d).db-update(d)
    (go-loop [db {}]
      (when-let [[e a v t m] (<! stream)]
        (let [updated-db (assoc-in db [e a] {:value v, :tx t})]
          (println "DaoDB materialized entity" e)
          (recur updated-db))))
    ;; Consumer 2: DaoFlow (interprets as UI)
    ;; π-calculus: !stream(d).render(d)
    (go-loop []
      (when-let [[e a v t m] (<! stream)]
        (println "DaoFlow rendered:" v "at position" e)
        (recur)))
    stream))


;; Usage:
;; (datom-stream)
;; => Appended datom: [1 :person/name "Alice" 100 {}]
;; => DaoDB materialized entity 1
;; => DaoFlow rendered: Alice at position 1
;; ... continues


;; =============================================================================
;; Pattern 7: Synchronization Barrier
;; π-calculus: barrier(ready).barrier̄⟨go⟩ (n times)
;; =============================================================================

(defn sync-barrier
  "Wait for n processes to reach barrier"
  [n]
  (let [ready (chan n) ; Collect ready signals
        go-signal (chan)]
    ;; Barrier coordinator
    (go (dotimes [_ n]
          (<! ready)
          (println "Process ready"))
        (println "All processes ready! Broadcasting go signal")
        (dotimes [_ n] (>! go-signal :go)))
    ;; n worker processes
    (dotimes [i n]
      (go (<! (async/timeout (rand-int 100))) ; Random work
          (println "Process" i "reached barrier")
          (>! ready :ready)
          (<! go-signal) ; Wait for go
          (println "Process" i "proceeding")))
    {:ready ready, :go go-signal}))


;; Usage:
;; (sync-barrier 3)
;; => Process 1 reached barrier
;; => Process ready
;; => Process 0 reached barrier
;; => Process ready
;; => Process 2 reached barrier
;; => Process ready
;; => All processes ready! Broadcasting go signal
;; => Process 1 proceeding
;; => Process 0 proceeding
;; => Process 2 proceeding


;; =============================================================================
;; Pattern 8: Request-Response (Elementary Embedding)
;; π-calculus: request̄⟨query, reply-channel⟩ | request(q,r).r̄⟨answer(q)⟩
;; =============================================================================

(defn request-response
  "Client-server with reply channels (elementary embedding pattern)"
  []
  (let [request (chan)]
    ;; Server: processes requests π-calculus: !request(query,
    ;; reply-ch).reply-ch̄⟨compute(query)⟩
    (go-loop []
      (when-let [{:keys [query reply-ch]} (<! request)]
        (println "Server processing query:" query)
        (let [result (case (:op query)
                       :get-entity {:entity 42, :name "Alice"}
                       :count-datoms {:count 1000}
                       :error)]
          (>! reply-ch result))
        (recur)))
    ;; Client: makes request
    ;; π-calculus: (νr)(request̄⟨query, r⟩.r(result).use(result))
    (go (let [reply-ch (chan)] ; (νr) - create private reply channel
          (>! request {:query {:op :get-entity, :id 42}, :reply-ch reply-ch})
          (let [result (<! reply-ch)]
            (println "Client received:" result)
            (close! reply-ch))))
    request))


;; Usage:
;; (request-response)
;; => Server processing query: {:op :get-entity, :id 42}
;; => Client received: {:entity 42, :name Alice}


;; =============================================================================
;; Pattern 9: Multi-Party Sync (DaoDB Device Sync)
;; π-calculus: sync̄⟨local-state⟩ | sync̄⟨local-state⟩ | ... |
;; merge-coordinator
;; =============================================================================

(defn multi-device-sync
  "Model DaoDB sync as π-calculus multi-party interaction"
  [device-states]
  (let [sync-channel (chan)
        merge-result (chan)]
    ;; Each device sends its state
    ;; π-calculus: sync̄⟨device-state⟩
    (doseq [[device-id state] device-states]
      (go (<! (async/timeout (rand-int 50))) ; Network latency
          (println "Device" device-id "sending state")
          (>! sync-channel {:device device-id, :state state})))
    ;; Merge coordinator (elementary embedding)
    ;; π-calculus: !sync(s₁).sync(s₂)....mergē⟨merged⟩
    (go (let [states (loop [collected []
                            remaining (count device-states)]
                       (if (zero? remaining)
                         collected
                         (recur (conj collected (<! sync-channel))
                                (dec remaining))))]
          (println "Coordinator received all states")
          ;; CRDT merge operation
          (let [merged-state (reduce (fn [acc {:keys [device state]}]
                                       (merge-with set/union acc state))
                                     {}
                                     states)]
            (println "Merged state:" merged-state)
            (>! merge-result merged-state))))
    merge-result))


;; Usage:
;; (<!! (multi-device-sync {:A {:entity-1 #{:name :age}}
;;                          :B {:entity-1 #{:email}
;;                              :entity-2 #{:name}}}))
;; => Device :A sending state
;; => Device :B sending state
;; => Coordinator received all states
;; => Merged state: {:entity-1 #{:email :name :age}, :entity-2 #{:name}}


;; =============================================================================
;; Pattern 10: Causal Ordering (Vector Clocks in π-Calculus)
;; Maintain happens-before relation across distributed events
;; =============================================================================

(defn causal-ordering
  "Track causal dependencies using vector clocks"
  [n-processes]
  (let [event-stream (chan 100)]
    ;; Each process maintains vector clock
    (dotimes [i n-processes]
      (go-loop [clock (vec (repeat n-processes 0))
                counter 0]
        (when (< counter 5)
          (<! (async/timeout (rand-int 100)))
          ;; Increment own clock
          (let [new-clock (update clock i inc)
                event {:process i,
                       :clock new-clock,
                       :event (str "Event-" i "-" counter)}]
            ;; Send event with vector clock
            (>! event-stream event)
            (println "Process" i "emitted:" (:event event) "clock:" new-clock)
            ;; Simulate receiving another event (merge clocks)
            (let [other-event (<! event-stream)]
              (when other-event
                (let [merged-clock (vec
                                     (map max new-clock (:clock other-event)))]
                  (println "Process" i "merged clock:" merged-clock)
                  (recur merged-clock (inc counter)))))))))
    event-stream))


;; Usage:
;; (causal-ordering 3)
;; => Process 0 emitted: Event-0-0 clock: [1 0 0]
;; => Process 1 emitted: Event-1-0 clock: [0 1 0]
;; => Process 2 emitted: Event-2-0 clock: [0 0 1]
;; => Process 1 merged clock: [1 1 0]
;; => Process 0 merged clock: [1 1 1]
;; ...


;; =============================================================================
;; Advanced Pattern: Mobile Process (Continuation Migration)
;; π-calculus: c̄⟨λx.P⟩ | c(k).k(v)
;; "Send continuation as data, remote execution"
;; =============================================================================

(defn mobile-continuation
  "Send executable code across channels (Yin VM migration)"
  []
  (let [migration-channel (chan)]
    ;; Source node: creates continuation and sends it
    (go (let [continuation
              (fn [x] (println "Continuation executing with" x) (* x x))] ; Closure
          ;; captures behavior
          (println "Sending continuation to remote node")
          (>! migration-channel continuation)))
    ;; Destination node: receives and executes continuation
    (go (let [k (<! migration-channel)]
          (println "Received continuation, executing...")
          (let [result (k 42)] ; Execute received function!
            (println "Continuation returned:" result))))
    migration-channel))


;; Usage:
;; (mobile-continuation)
;; => Sending continuation to remote node
;; => Received continuation, executing...
;; => Continuation executing with 42
;; => Continuation returned: 1764


;; =============================================================================
;; Real-World Example: DaoDB Query with Reflection
;; Combines multiple patterns: request-response, mobile channels, choice
;; =============================================================================

(defn daodb-distributed-query
  "Complete example: query with local/remote reflection"
  []
  (let [local-db (atom {:entity-1 {:name "Alice", :age 30}})
        remote-channel (chan)
        result-channel (chan)]
    ;; Local query attempt
    (go (let [query {:find '?name, :where '[?e :name ?name]}
              local-result (get-in @local-db [:entity-1 :name])]
          (if local-result
            ;; Local reflection succeeded
            (do (println "Query answered locally:" local-result)
                (>! result-channel {:status :local, :result local-result}))
            ;; Need remote (critical point crossed)
            (do (println "Query requires remote sync")
                (let [reply-ch (chan)]
                  (>! remote-channel {:query query, :reply reply-ch})
                  (let [remote-result (<! reply-ch)]
                    (println "Got remote result:" remote-result)
                    (>! result-channel
                        {:status :remote, :result remote-result})))))))
    ;; Remote node (simulated)
    (go (when-let [{:keys [query reply-ch]} (<! remote-channel)]
          (println "Remote node processing query")
          (<! (async/timeout 50)) ; Simulate latency
          (>! reply-ch {:name "Bob", :age 35})))
    result-channel))


;; Usage:
;; (<!! (daodb-distributed-query))
;; => Query answered locally: Alice
;; => {:status :local, :result "Alice"}


(comment
  ;; Run examples:
  (basic-send-receive)
  (mobile-channels)
  (channel-restriction)
  (replicated-server)
  (<!! (channel-choice))
  (datom-stream)
  (sync-barrier 3)
  (request-response)
  (<!! (multi-device-sync {:A {:entity-1 #{:name :age}},
                           :B {:entity-1 #{:email}, :entity-2 #{:name}}}))
  (causal-ordering 3)
  (mobile-continuation)
  (<!! (daodb-distributed-query)))
