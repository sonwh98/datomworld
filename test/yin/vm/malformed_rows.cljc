(ns yin.vm.malformed-rows
  "The §7.4 published malformed row sets, one per rule, for
   §7.2 part 3's same-refusal conformance."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.rows :as rows]))


(defn- valid-row
  [body]
  (let [id (jing/segment-key body)]
    [id (into [id] body)]))


(defn- one-row-bc
  [body]
  (let [[id row] (valid-row body)]
    {:root id, :rows {id row}}))


(def ^:private fake-address-a (keyword "segment" (str "sha256-" (apply str (repeat 64 "a")))))
(def ^:private fake-address-b (keyword "segment" (str "sha256-" (apply str (repeat 64 "b")))))


(def malformed-row-sets
  "`{name [expected-defect bc]}`, one `{:root :rows}` value per §7.4 rule."
  (let [[missing-id] (valid-row [:literal 999])
        [lit1-id lit1-row] (valid-row [:literal 1])
        [lit2-id lit2-row] (valid-row [:literal 2])]
    {:tag [{:rule :tag, :path []} (one-row-bc [:not-a-tag])]
     :arity [{:rule :arity, :path []} (one-row-bc [:literal 1 2])]
     :slot-kind-str [{:rule :slot-kind, :path [1]} (one-row-bc [:vm/gensym :not-a-string])]
     :slot-kind-node [{:rule :slot-kind, :path [1]} (one-row-bc [:stream/next 123])]
     :slot-kind-nodes [{:rule :slot-kind, :path [2]} (one-row-bc [:application fake-address-a :not-a-vector false])]
     ;; a host function has no content address (dao.jing's canonical
     ;; encoding refuses it), so this row sits at a fake one: `:slot-kind`
     ;; is structural and never consults the address
     :slot-kind-data [{:rule :slot-kind, :path [2]}
                      {:root fake-address-a,
                       :rows {fake-address-a
                              [fake-address-a :vm/store-put :key-ok (fn [])]}}]
     :slot-kind-sym [{:rule :slot-kind, :path [1]} (one-row-bc [:variable "not-a-sym"])]
     :slot-kind-kw [{:rule :slot-kind, :path [1]} (one-row-bc [:vm/resume "not-a-kw" fake-address-a])]
     :slot-kind-int [{:rule :slot-kind, :path [1]} (one-row-bc [:stream/make :not-int])]
     :saturation [{:rule :saturation, :path [1]} (one-row-bc [:vm/gensym nil])]
     :id-resolves [{:rule :id-resolves, :path [1]} (one-row-bc [:application missing-id [] false])]
     :acyclic [{:rule :acyclic, :path []}
               {:root fake-address-a,
                :rows {fake-address-a [fake-address-a :stream/cursor fake-address-b],
                       fake-address-b [fake-address-b :stream/cursor fake-address-a]}}]
     :root-reachable [{:rule :root-reachable, :id lit2-id}
                      {:root lit1-id, :rows {lit1-id lit1-row, lit2-id lit2-row}}]}))


(def malformed-ucf-row-sets
  "`{name [expected-refusal carrier]}` for the UCF row codec, separate
   from the Universal AST row grammar in malformed-row-sets."
  (let [task (rows/node :task
                        [3 rows/profile :halted :root 0 (rows/node :bindings [])
                         (rows/node :store []) [] (rows/node :parked-table [])
                         (rows/node :cursor-cells []) {} {} [] [] nil
                         [:row (rows/node :map [[:lit 1] [:lit :a]
                                                [:lit (cbor/float64 1.0)] [:lit :b]])] nil])
        wire (rows/emit-numbered task {})
        id (first (first (filter #(= :map (second %)) (:yin.k/rows wire))))]
    {:equality-collapse [{:yin.k/status :yin.k/undecodable
                          :yin.k/kind :equality-collapse :yin.k/path [id]} wire]}))
