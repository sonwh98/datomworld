(ns yang.correspondence-lane
  "Measurement-only JVM composition for the D1 C lanes. Not a verifier."
  (:require [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.engine :as engine]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.test-utils :as tu]))


(defn run-measured!
  "Run the observer composition with one aggregate step budget. Answers the
   terminal machine and exact number of vm/step calls, including across
   multiple observer run callbacks. A blocked machine is not a completed lane."
  [ast modules lane step-limit & [{:keys [check!]}]]
  (when-not (and (contains? #{:walker :semantic} lane) (nat-int? step-limit)
                 (or (nil? check!) (ifn? check!)))
    (throw (ex-info "Invalid lane or budget" {:stage :configuration})))
  (let [steps (atom 0)
        run (fn [machine]
              (loop [v machine]
                (when check! (check! @steps v))
                (cond
                  (or (vm/halted? v) (vm/blocked? v)) v
                  (= @steps step-limit)
                  (throw (ex-info "Guest instruction budget exhausted"
                                  {:stage :instruction-limit :lane lane
                                   :steps @steps :consumer v}))
                  :else (let [v' (vm/step v)]
                          (swap! steps inc)
                          (recur v')))))
        semantic? (= lane :semantic)
        machine (if semantic?
                  (semantic/create-vm {:make-stream tu/make-stream
                                       :capability-secret tu/secret :modules modules})
                  (tu/create-vm {:modules modules}))
        load-program (if semantic?
                       (fn [v rows stamp]
                         (vm/check-contract! vm/ast-contract stamp)
                         (semantic/load-vector v (:vector (linearize/project-rows rows))
                                               vm/semantic-contract))
                       walker/vm-load-program)
        session ((if semantic? tu/queue-rows! tu/queue-ast!)
                 (tu/make-observer-session machine) ast)
        answer (observer/run-on-stream session engine/ready-for-ingress?
                                       (vm/fresh-code-loader load-program vm/ast-contract)
                                       run)]
    (when-not (zero? (get-in answer [:observer :ingress-gaps]))
      (throw (ex-info "Program stream gap" {:stage :ingress-gap})))
    {:machine (:consumer answer) :steps @steps}))


(defn read-trace!
  "Read a halted lane's ring trace, refusing any evicted prefix or incomplete
   drain. Byte size is UTF-8 pr-str of the vector, not in-memory occupancy."
  [handle]
  (let [old (stream/cursor handle stream/anchor-oldest)
        new (stream/cursor handle stream/anchor-newest)
        start (:dao.stream/cursor old)
        end (:dao.stream/cursor new)
        first-position (:dao.stream.ringbuffer/position start)
        tail (:dao.stream.ringbuffer/position end)]
    (when-not (and (= :dao.stream/ok (:dao.stream/outcome old))
                   (= :dao.stream/ok (:dao.stream/outcome new))
                   (integer? first-position) (integer? tail)
                   (= (:dao.stream.ringbuffer/identity start)
                      (:dao.stream.ringbuffer/identity end)))
      (throw (ex-info "Unrecognized trace cursor" {:stage :trace-cursor})))
    (when-not (zero? first-position)
      (throw (ex-info "Trace prefix evicted" {:stage :trace-loss :lost first-position})))
    (loop [cursor start, records []]
      (let [result (stream/next handle cursor)]
        (case (:dao.stream/outcome result)
          :dao.stream/ok (recur (:dao.stream/cursor result)
                                (conj records (:dao.stream/value result)))
          (:dao.stream/blocked :dao.stream/end)
          (if (and (= cursor end) (= tail (count records)))
            {:records records :record-count (count records)
             :serialized-bytes (alength (.getBytes (pr-str records) "UTF-8"))
             :max-record-bytes (reduce max 0 (map #(alength (.getBytes (pr-str %) "UTF-8")) records))
             :lost 0 :drain-outcome (:dao.stream/outcome result)}
            (throw (ex-info "Incomplete trace drain" {:stage :trace-drain})))
          (throw (ex-info "Trace read refused" {:stage :trace-drain
                                                :outcome (:dao.stream/outcome result)})))))))


(defn trace-reachable-heap!
  "Measure the trace handle's reachable heap using a supplied host sizer.
   This counts shared objects reachable from the trace and is not retained size."
  [handle size-fn]
  (let [n (size-fn handle)]
    (when-not (and (integer? n) (pos? n))
      (throw (ex-info "Invalid trace heap measurement" {:stage :trace-memory :value n})))
    n))


(defn measure-trace!
  "Capture the graph size before draining, then validate and collect the trace."
  [handle size-fn]
  (let [bytes (trace-reachable-heap! handle size-fn)]
    (assoc (read-trace! handle) :reachable-heap-bytes bytes)))
