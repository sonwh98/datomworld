(ns yin.vm.malformed-rows
  "The §7.4/§7.5 published sets: malformed row sets and malformed canonical
   instruction vectors, one per rule, for §7.2 part 3's same-refusal
   conformance."
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


(def malformed-vectors
  "`{name [expected-defect vector]}`, one canonical instruction vector per
   §7.5 rule (a rule with several judged kinds or encodings has several
   entries). The `[[:jump 9]]` entry is correctly hashed — its address is
   `(jing/segment-key [[:jump 9]])`, exactly the content hash of what it
   is — and refused anyway: a correct hash is never structural
   validation. The list-encoding entries pin the canonical positional
   form (UCF §7.3.2): the outer sequence and every tuple are vectors."
  {:nonempty [{:rule :nonempty, :pc 0} []]
   :nonempty-outer-list [{:rule :nonempty, :pc 0} '([:halt])]
   :mnemonic [{:rule :mnemonic, :pc 0} [[:frobnicate]]]
   :mnemonic-inner-list [{:rule :mnemonic, :pc 0} '[(:halt)]]
   :arity [{:rule :arity, :pc 0} [[:const 1 2] [:halt]]]
   :operand-kind [{:rule :operand-kind, :pc 0} [[:var "not-a-sym"] [:halt]]]
   :operand-kind-bool [{:rule :operand-kind, :pc 0} [[:call 0 :truthy] [:halt]]]
   :operand-kind-buffer [{:rule :operand-kind, :pc 0} [[:stream-make :bad] [:halt]]]
   :saturation [{:rule :saturation, :pc 0} [[:gensym nil] [:halt]]]
   :target-bounds [{:rule :target-bounds, :pc 0} [[:jump 9]]]
   :terminator [{:rule :terminator, :pc 0} [[:const 1]]]
   :argc [{:rule :argc, :pc 0} [[:call -1 false] [:halt]]]})
