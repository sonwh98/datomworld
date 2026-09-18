(ns yin.vm.content-test
  "U10: code content in `dao.jing` (D3 individual rows; D7's intake answer
   is the `dao.jing.stream` adapter, tested in its own namespace). The
   criteria here are the unit's own: materialize! → get → validate
   round-trips every corpus tree and vector on every host, and a
   metadata-bearing literal either round-trips (memory backend) or is
   refused before the write (file backend, which then still opens)."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.jing.mem :as jing-mem]
            [yin.vm :as vm]
            [yin.vm.content :as content]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.parity-test :as parity]
            [yin.vm-test :as vm-test]))


(def ^:private corpus-trees
  "Every corpus tree: the codec's every-tag corpus plus the parity corpus."
  (concat (map (fn [ast] [(pr-str ast) ast]) vm-test/semantic-bytecode-corpus)
          parity/corpus))


(defn- temp-path
  [prefix]
  (str "target/test-jing-content-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File. path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object :default Exception) _ nil))))


(deftest materialize-get-validate-round-trips-every-tree-and-vector
  (testing "every corpus tree and its lowered vector, memory backend"
    (let [h (jing-mem/create-content-mem)]
      (doseq [[name ast] corpus-trees]
        (let [tree (vm/ast->semantic-bytecode ast)
              root (content/materialize-tree! h tree)
              loaded (content/load-rows h root)]
          (is (= root (:root tree)) (str "the tree's address is its root row id: " name))
          (is (= tree loaded) (str "materialize! -> get -> validate round-trips: " name))
          (is (= (vm/semantic-bytecode->ast loaded)
                 (vm/semantic-bytecode->ast tree))
              (str "the fetched tree reconstructs to the same map AST: " name)))
        (let [vector (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast)))
              address (content/materialize-vector! h vector)
              fetched (content/fetch-vector h address)]
          (is (= (jing/segment-key vector) address)
              (str "a vector materializes under its own segment-key: " name))
          (is (= vector (:vector fetched))
              (str "fetch by address returns the exact vector: " name))))
      (jing/close! h))))


(deftest file-backend-round-trips-trees-and-vectors
  (testing "the durable backend round-trips the same content, across close and reopen"
    (let [path (temp-path "u10-roundtrip")
          tree (vm/ast->semantic-bytecode
                 {:type :lambda, :params '[x],
                  :body {:type :application,
                         :operator {:type :variable, :name 'x},
                         :operands [{:type :literal, :value 1}] :tail? true}})
          vector (:vector (linearize/lower-rows tree))
          h (jing-file/create-content-file path)]
      (try
        (let [root (content/materialize-tree! h tree)
              address (content/materialize-vector! h vector)]
          (jing/close! h)
          (let [h2 (jing-file/create-content-file path)]
            (try
              (is (= tree (content/load-rows h2 root)) "tree after reopen")
              (is (= vector (:vector (content/fetch-vector h2 address))) "vector after reopen")
              (finally (jing/close! h2)))))
        (finally (cleanup-file path))))))


(def ^:private metadata-literal-ast
  "A literal whose value carries non-position metadata — the payload class
   the transitional encoder addresses and `pr-str` drops."
  {:type :literal, :value (with-meta [1 2] {:meaning "retained"})})


(deftest metadata-bearing-literal-round-trips-or-is-refused
  (testing "the memory backend carries it: full round trip"
    (let [h (jing-mem/create-content-mem)
          tree (vm/ast->semantic-bytecode metadata-literal-ast)
          root (content/materialize-tree! h tree)
          loaded (content/load-rows h root)
          lit-row (some (fn [row] (when (= :literal (nth row 1)) row))
                        (vals (:rows loaded)))]
      (is (= tree loaded)
          "metadata survives materialize! -> get -> validate")
      (is (= {:meaning "retained"} (meta (nth lit-row 2)))
          "the fetched literal's value carries its metadata")
      (jing/close! h)))

  (testing "the file backend refuses it before the write, and stays openable"
    (let [path (temp-path "u10-metadata")
          h (jing-file/create-content-file path)]
      (try
        (let [refusal (try
                        (content/materialize-tree!
                          h (vm/ast->semantic-bytecode metadata-literal-ast))
                        ::no-refusal
                        (catch #?(:cljd Object :clj Exception :cljs :default) e (ex-message e)))]
          (is (re-find #"does not survive this backend's text codec" (str refusal))
              "refused, loudly, naming the codec rule"))
        (jing/close! h)
        (is (= [] (jing-file/records path))
            "nothing was written: the store reopens with no frames")
        (finally (cleanup-file path))))))


(deftest shared-subtrees-are-stored-once-across-trees
  (testing "D3's cross-tree deduplication: one row per distinct subtree"
    (let [h (jing-mem/create-content-mem)
          shared {:type :application,
                  :operator {:type :variable, :name '+},
                  :operands [{:type :literal, :value 1}
                             {:type :literal, :value 2}]}
          wrap (fn [extra]
                 {:type :lambda, :params '[x],
                  :body {:type :if,
                         :test shared,
                         :consequent {:type :variable, :name 'x},
                         :alternate {:type :literal, :value extra}}})
          tree-a (vm/ast->semantic-bytecode (wrap 0))
          tree-b (vm/ast->semantic-bytecode (wrap 1))
          shared-root (:root (vm/ast->semantic-bytecode shared))]
      (content/materialize-tree! h tree-a)
      (content/materialize-tree! h tree-b)
      (is (= (count (merge (:rows tree-a) (:rows tree-b)))
             (count (jing-mem/entries h)))
          "the store holds each distinct row once, trees merged by address")
      (is (contains? (jing-mem/entries h) shared-root)
          "the shared subtree's row is stored under its own address")
      (is (= tree-b (content/load-rows h (:root tree-b)))
          "the second tree loads from rows the first tree already stored")
      (jing/close! h))))


(deftest load-rows-and-fetch-vector-refuse-bad-content
  (let [h (jing-mem/create-content-mem)]
    (testing "an absent root throws naming the address"
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"resolves to no payload"
            (content/load-rows h (jing/segment-key [:literal 1])))))
    (testing "a stored row set that fails §7.4 throws its defect"
      ;; a body with the wrong arity for its tag, stored under its own true
      ;; address so only the validator can catch it
      (let [bad-root (jing/materialize! h [:literal 1 :extra])
            defect (try (content/load-rows h bad-root) nil
                        (catch #?(:cljd Object :clj Exception :cljs :default) e (:rule (ex-data e))))]
        (is (= :arity defect))))
    (testing "fetch-vector on a stored row body is refused by §7.5, not
              reinterpreted (it hashes to its own address — a legal payload,
              just not a vector)"
      (let [row-address (jing/materialize! h [:literal 7])]
        (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"Cannot load vector: mnemonic"
              (content/fetch-vector h row-address)))))
    (testing "a correctly-hashed malformed vector is refused by §7.5"
      (let [malformed [[:jump 9]]           ; U5's own refusal case
            address (jing/materialize! h malformed)]
        (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"target-bounds"
              (content/fetch-vector h address)))))
    (jing/close! h))

  (testing "materialize-vector! refuses a malformed vector before the write"
    (let [h (jing-mem/create-content-mem)]
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"fails validation"
            (content/materialize-vector! h [[:jump 9]])))
      (is (not (contains? (jing-mem/entries h) (jing/segment-key [[:jump 9]]))))
      (jing/close! h))))


(deftest fetched-vectors-carry-their-address-into-the-vm
  (testing "the UCF §7.3.4 lane: a fetched vector loads with its
            :yin.code/hash recorded in the image and alias column"
    (let [h (jing-mem/create-content-mem)
          tree (vm/ast->semantic-bytecode
                 {:type :lambda, :params '[x],
                  :body {:type :application,
                         :operator {:type :variable, :name 'x},
                         :operands [{:type :literal, :value 1}]}})
          vector (:vector (linearize/lower-rows tree))
          address (content/materialize-vector! h vector)
          fetched (content/fetch-vector h address)
          loaded (semantic/load-vector (semantic/create-vm) (:vector fetched))
          seg (get-in loaded [:code-aliases address])]
      (is (= address (:address fetched)))
      (is (number? seg) "the address is aliased to a local segment id")
      (is (= address (get-in loaded [:code seg :address]))
          ":yin.code/hash is the image's recorded address")
      (jing/close! h))))
