(ns yin.vm.malformed-rows
  "The §7.4/§7.2 part 3 published set of malformed row sets."
  (:require [dao.jing :as jing]))


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
     :slot-kind-data [{:rule :slot-kind, :path [2]} (one-row-bc [:vm/store-put :key-ok (fn [])])]
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
