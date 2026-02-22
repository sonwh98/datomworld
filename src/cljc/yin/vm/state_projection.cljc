(ns yin.vm.state-projection
  "Projects in-memory VM state into a queryable datom stream.
   Zero cost during execution; projection computed only when queried."
  (:require [yin.content :as content]))


(defn- project-env
  "Project an environment map into datoms.
   Returns [datoms next-eid]."
  [env eid-start hash-cache]
  (let [id-counter (atom eid-start)
        gen-id #(let [id @id-counter]
                  (swap! id-counter dec)
                  id)
        datoms (atom [])]
    (doseq [[name val] env]
      (let [eid (gen-id)]
        (swap! datoms conj [eid :env/name name 0 0])
        (swap! datoms conj
          [eid :env/value
           (if (and (map? val) (= :closure (:type val)))
             {:type :closure, :body-hash (get hash-cache (:body-node val))}
             val) 0 0])))
    [@datoms @id-counter]))


(defn- project-frame
  "Project a single continuation frame into datoms.
   Returns [datoms next-eid]."
  [frame eid-start hash-cache]
  (let [eid eid-start
        datoms (atom [[eid :cont/type (:type frame) 0 0]])
        next-eid (dec eid)]
    (case (:type frame)
      :app-op (do (doseq [[i operand-id] (map-indexed vector (:operands frame))]
                    (swap! datoms conj
                      [eid :cont/pending-arg
                       [i (get hash-cache operand-id operand-id)] 0 0]))
                  [@datoms next-eid])
      :app-args (do
                  (doseq [[i pending-id] (map-indexed vector (:pending frame))]
                    (swap! datoms conj
                      [eid :cont/pending-arg
                       [i (get hash-cache pending-id pending-id)] 0 0]))
                  (swap! datoms conj
                    [eid :cont/evaluated-count (count (:evaluated frame)) 0 0])
                  [@datoms next-eid])
      :if (do (swap! datoms conj
                [eid :cont/consequent-ref
                 (get hash-cache (:cons frame) (:cons frame)) 0 0])
              (swap! datoms conj
                [eid :cont/alternate-ref
                 (get hash-cache (:alt frame) (:alt frame)) 0 0])
              [@datoms next-eid])
      :restore-env [@datoms next-eid]
      ;; Default: just the type
      [@datoms next-eid])))


(defn state->datom-stream
  "Project VM state into a datom stream.
   vm must have :control, :env, :stack, :datoms keys (SemanticVM shape).
   Returns vector of [e a v t m] datoms with entity IDs starting at -2048."
  [vm]
  (let [{:keys [control env stack datoms halted value parked]} vm
        hash-cache (when (seq datoms) (content/compute-content-hashes datoms))
        hash-cache (or hash-cache {})
        id-counter (atom -2048)
        gen-id #(let [id @id-counter]
                  (swap! id-counter dec)
                  id)
        result (atom [])]
    ;; Project control state
    (let [ctrl-eid (gen-id)]
      (swap! result conj [ctrl-eid :cont/type :control 0 0])
      (when control
        (swap! result conj [ctrl-eid :cont/state (:type control) 0 0])
        (case (:type control)
          :node (swap! result conj
                  [ctrl-eid :cont/node-ref
                   (get hash-cache (:id control) (:id control)) 0 0])
          :value (swap! result conj [ctrl-eid :cont/value (:val control) 0 0])
          nil))
      (when halted
        (swap! result conj [ctrl-eid :cont/halted true 0 0])
        (swap! result conj [ctrl-eid :cont/result value 0 0])))
    ;; Project continuation stack frames
    (doseq [frame stack]
      (let [frame-eid (gen-id)
            [frame-datoms _] (project-frame frame frame-eid hash-cache)]
        (swap! result into frame-datoms)))
    ;; Project environment
    (let [[env-datoms _] (project-env env @id-counter hash-cache)]
      (swap! result into env-datoms))
    ;; Project parked continuations
    (doseq [[park-id parked-cont] (or parked {})]
      (let [park-eid (gen-id)]
        (swap! result conj [park-eid :cont/type :parked 0 0])
        (swap! result conj [park-eid :cont/parked-id park-id 0 0])
        (doseq [frame (:stack parked-cont)]
          (let [frame-eid (gen-id)
                [frame-datoms _] (project-frame frame frame-eid hash-cache)]
            (swap! result into frame-datoms)))))
    @result))
