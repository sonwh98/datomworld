(ns yang.correspondence-verification
  "A bounded fixture attribution gate. This is not the complete D1 verifier."
  (:require [dao.jing :as jing]
            [yang.clojure :as yang]
            [yang.correspondence-text :as text]
            [yang.correspondence-lane :as lane]
            [yang.correspondence-measure :as measure]
            [yang.correspondence-verifier :as verifier]
            [yin.vm :as vm]))


(defn- check-configuration!
  [lane-limits {:keys [trace-size-fn fixture-limit-ms tree-limits]}]
  (when-not (and (nat-int? fixture-limit-ms)
                 (= #{:walker :semantic} (set (keys lane-limits)))
                 (= #{:rows :occurrences :applications :depth :application-path-bytes}
                    (set (keys tree-limits)))
                 (every? (fn [[_ v]] (nat-int? v)) tree-limits)
                 (every? (fn [[_ v]]
                           (and (= #{:steps :elapsed-ms :heap-bytes :trace-records
                                     :trace-record-bytes :trace-bytes :trace-heap-bytes}
                                   (set (keys v)))
                                (every? (fn [[_ x]] (nat-int? x)) v))) lane-limits))
    (throw (ex-info "Incomplete fixture bounds" {:stage :bound-declaration})))
  (when-not (ifn? trace-size-fn)
    (throw (ex-info "Trace heap sizer missing" {:stage :trace-memory}))))


(defn verify-fixture!
  "Independently check static fixture data, require a mutated declaration
   refusal, run both bounded lanes and execute each scoped attribution query.
   Exceptions other than a measured bound exceedance propagate. A pass here
   does not verify the D1 recipe, image derivations or provenance closure."
  [input claims lane-limits modules clock heap & [{:keys [trace-size-fn fixture-limit-ms tree-limits started] :as options}]]
  (let [started (or started (clock))
        elapsed (fn [t] (quot (+ (- (clock) t) 999999) 1000000))
        phase-times (atom {})
        timed! (fn [phase f]
                 (let [t (clock) result (f)]
                   (swap! phase-times assoc phase (elapsed t))
                   result))
        check-fixture-time! (fn []
                              (when (some? fixture-limit-ms)
                                (measure/check-bounds! {:fixture-ms fixture-limit-ms}
                                                       {:fixture-ms (elapsed started)})))]
    (try
      (when-not (and (vector? claims) (seq claims)
                     (every? (fn [claim]
                               (and (map? claim) (symbol? (:guest-symbol claim))
                                    (some? (:requested-arity claim))
                                    (sequential? (:carriers claim)))) claims))
        (throw (ex-info "Missing or malformed attribution claims" {:stage :claims})))
      (check-configuration! lane-limits options)
      (timed! :tree-check-ms
              #(doseq [k [:before :stamped]]
                 (measure/bounded-tree-statistics! (vm/ast->semantic-bytecode (get input k)) tree-limits)))
      (timed! :static-check-ms #(verifier/check-fixture! input))
      (check-fixture-time!)
      (let [site (first (keys (:site-table input)))
            _ (when-not site (throw (ex-info "No declaration to mutate" {:stage :mutation})))
            changed (assoc-in input [:site-table site :arg-shapes] [:r11-forged])
            mutation (timed! :mutation-check-ms
                             #(try (verifier/check-fixture! changed) nil
                                   (catch clojure.lang.ExceptionInfo e (:stage (ex-data e)))))
            _ (when-not (= :site-declarations mutation)
                (throw (ex-info "Mutated declaration was not refused" {:stage :mutation})))
            _ (check-fixture-time!)
            root (:root (vm/ast->semantic-bytecode (:before input)))
            lanes (into {}
                        (for [[backend lane-id] [[:walker :c-walker] [:semantic :c-semantic]]]
                          (try
                            (let [limits (get lane-limits backend)
                                  runtime-limits (dissoc limits :trace-heap-bytes)
                                  _ (when (and (contains? limits :trace-heap-bytes) (not (ifn? trace-size-fn)))
                                      (throw (ex-info "Trace heap sizer missing" {:stage :trace-memory})))
                                  lane-start (clock)
                                  checker (measure/runtime-checker runtime-limits lane-start clock heap)
                                  {:keys [machine steps]} (lane/run-measured!
                                                            (:stamped input) modules backend
                                                            (:steps limits) {:check! checker})
                                  run-ms (elapsed lane-start)
                                  _ (checker steps machine)
                                  ref (get-in machine [:store 'gate-execution-trace])
                                  h (get-in machine [:resources (:id ref)])
                                  _ (when-not (and (vm/halted? machine) h)
                                      (throw (ex-info "Lane did not halt with trace"
                                                      {:stage :lane-completion :lane backend})))
                                  graph-bytes (when (contains? limits :trace-heap-bytes)
                                                (lane/trace-reachable-heap! h trace-size-fn))
                                  _ (when (some? graph-bytes)
                                      (measure/check-bounds! {:trace-heap-bytes (:trace-heap-bytes limits)}
                                                             {:trace-heap-bytes graph-bytes}))
                                  trace (lane/read-trace! h)
                                  _ (check-fixture-time!)]
                              [lane-id (cond-> {:status :completed :complete? true
                                                :executed-calls (:records trace)
                                                :steps steps :run-ms run-ms :record-count (:record-count trace)
                                                :serialized-bytes (:serialized-bytes trace)
                                                :max-record-bytes (:max-record-bytes trace)}
                                         (some? graph-bytes) (assoc :trace-heap-bytes graph-bytes))])
                            (catch clojure.lang.ExceptionInfo e
                              (throw (ex-info (ex-message e) (assoc (ex-data e) :lane backend) e))))))
            attributed (timed! :query-ms
                               #(mapv (fn [claim]
                                        (verifier/attributed?
                                          (merge claim {:site-table (:site-table input)
                                                        :bindings (:bindings input) :origin (:origin input)
                                                        :root root :lanes lanes}))) claims))
            _ (check-fixture-time!)]
        {:status :pass :fixture (:fixture input) :mutation-refusal mutation
         :attributed attributed :claims claims :lanes lanes :elapsed-ms (elapsed started)
         :gate-phases @phase-times
         :not-a-d1-verification-result true})
      (catch clojure.lang.ExceptionInfo e
        (if (= :bound-exceeded (:stage (ex-data e)))
          (merge {:status :failed-bound :fixture (:fixture input) :gate-phases @phase-times
                  :not-a-d1-verification-result true}
                 (select-keys (ex-data e) [:stage :quantity :observed :ceiling :lane]))
          (throw e))))))


(defn verify-retained-fixture!
  "Fetch, replay and check one recorded admission before the bounded fixture
   gate. A pass is only this gate, not a D1 verification or recipe claim."
  [handle fixture outcome claims lane-limits modules clock heap options]
  (check-configuration! lane-limits options)
  (let [started (clock)
        duration (fn [t] (quot (+ (- (clock) t) 999999) 1000000))
        elapsed (fn [] (duration started))
        check-time! (fn []
                      (measure/check-bounds!
                        {:fixture-ms (:fixture-limit-ms options)}
                        {:fixture-ms (elapsed)}))]
    (try
      (let [addresses (:addresses outcome)
            rows-start (clock)
            {:keys [before stamped]} (measure/check-retained-trees!
                                       handle fixture outcome
                                       {:max-rows (get-in options [:tree-limits :rows])})
            rows-ms (duration rows-start)
            _ (check-time!)
            content-start (clock)
            missing ::missing
            capture (jing/get handle (:input addresses) missing)
            snapshot (jing/get handle (:snapshot addresses) missing)
            evidence (jing/get handle (:evidence addresses) missing)
            profile (jing/get handle (:profile addresses) missing)
            _ (when-not (= text/profile profile)
                (throw (ex-info "Unknown or incompatible capture profile" {:stage :profile})))
            row (get-in evidence [:fixtures fixture])
            _ (when-not (and (= 1 (:version evidence)) (map? row))
                (throw (ex-info "Missing or unsupported evidence" {:stage :evidence})))
            content-ms (duration content-start)
            _ (check-time!)
            replay-start (clock)
            program (text/read-one! fixture (:program-edn capture))
            compiler-options (text/read-one! fixture (:options-edn capture))
            _ (verifier/check-replay-root!
                fixture (yang/compile-program program true compiler-options) (:root snapshot))
            replay-ms (duration replay-start)
            _ (check-time!)
            gate-start (clock)
            input (assoc snapshot
                         :before (vm/semantic-bytecode->ast before)
                         :stamped (vm/semantic-bytecode->ast stamped)
                         :forms (text/read-one! fixture (:forms-edn capture))
                         :call (text/read-one! fixture (:call-edn capture))
                         :source-hash (:canonical-source-hash row)
                         :site-table (:site-table row))
            result (verify-fixture! input claims lane-limits modules clock heap
                                    (assoc options :started started))
            gate-ms (duration gate-start)]
        (if (= :failed-bound (:status result))
          result
          (do (check-time!)
              (assoc result :admission-origin (:admission-origin outcome)
                     :fixture-elapsed-ms (elapsed)
                     :phases {:fetch-rows-ms rows-ms :fetch-content-ms content-ms
                              :replay-ms replay-ms :gate-ms gate-ms}))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :bound-exceeded (:stage (ex-data e)))
          (merge {:status :failed-bound :fixture fixture :not-a-d1-verification-result true}
                 (select-keys (ex-data e)
                              [:stage :quantity :observed :ceiling :lane :tree :observed-kind]))
          (throw e))))))
