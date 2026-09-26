(ns yin.vm.content-test
  "U10: code content in `dao.jing` (D3 individual rows; D7's intake answer
   is the `dao.jing.stream` adapter, tested in its own namespace). The
   criteria here are the unit's own: materialize! -> fetch round-trips
   every corpus tree and vector on every host, and a metadata-bearing
   literal either round-trips (memory backend) or is refused before the
   write (file backend, which then still opens). The read side is
   `yin.vm.linker/fetch` with the storage-derived format records: M2
   retired the `load-rows`/`fetch-vector` loaders and moved their
   callers to `fetch` (section 9 of `docs/design/yin.vm.linker.md`),
   which since M3 reads over a ring-buffer content pair served from the
   store (`yin.vm.linker-test/fetch-local`)."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.jing.mem :as jing-mem]
            [yin.vm :as vm]
            [yin.vm.content :as content]
            [yin.vm.linearize :as linearize]
            [yin.vm.linker :as linker]
            [yin.vm.linker-test :as lt]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as semantic]
            [yin.vm-test :as vm-test]))


(def ^:private corpus-trees
  "Every corpus tree: the codec's every-tag corpus plus the parity corpus."
  (concat (map (fn [ast] [(pr-str ast) ast]) vm-test/semantic-bytecode-corpus)
          parity/corpus))


(def ^:private corpus-free-names
  "Every free name the corpus itself carries: the every-tag corpus
   includes a bare `:variable` row and call sites of an unbound `f`, so
   the fetch receiver must bind them."
  (into #{} (comp (map second)
                  (map vm/ast->semantic-bytecode)
                  (map (:obligations-fn linker/ast-format))
                  cat)
        corpus-trees))


(def ^:private receiver
  "The fetch receiver for the corpus: the full primitive registry plus
   the corpus's own deliberately-unbound names (the every-tag corpus
   carries a bare `:variable` row and call sites of an unbound `f`) as
   bare legacy entries -- a free-env binding would refuse them
   :shadowed-free, and discharge is by presence in the registry."
  (let [free (remove #(contains? vm/primitives %) corpus-free-names)]
    {:primitives (into vm/primitives
                       (map (fn [n] [n (fn [_] n)]))
                       free)}))


(defn- requested
  "The fetch options of a requester that runs `format`'s own contract:
   the fetch requires it (section 4.2 step 0)."
  [format]
  {:contract (:contract format)})


(defn- fetch-tree
  "The fetched tree at `root` in `handle`, through the linker's AST
   format record."
  [handle root]
  (let [res (lt/fetch-local handle {root root} linker/ast-format root
                            receiver (requested linker/ast-format))]
    (assert (linker/ok? res) (pr-str res))
    (:value res)))


(defn- fetch-vector
  "The fetched vector at `address` in `handle`, through the linker's
   semantic format record."
  [handle address]
  (let [res (lt/fetch-local handle {address address}
                            linker/semantic-format address receiver
                            (requested linker/semantic-format))]
    (assert (linker/ok? res) (pr-str res))
    (:value res)))


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
              loaded (fetch-tree h root)]
          (is (= root (:root tree)) (str "the tree's address is its root row id: " name))
          (is (= tree loaded) (str "materialize! -> get -> validate round-trips: " name))
          (is (= (vm/semantic-bytecode->ast loaded)
                 (vm/semantic-bytecode->ast tree))
              (str "the fetched tree reconstructs to the same map AST: " name)))
        (let [vector (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast)))
              address (content/materialize-vector! h vector)
              fetched (fetch-vector h address)]
          (is (= (jing/segment-key vector) address)
              (str "a vector materializes under its own segment-key: " name))
          (is (= vector fetched)
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
              (is (= tree (fetch-tree h2 root)) "tree after reopen")
              (is (= vector (fetch-vector h2 address)) "vector after reopen")
              (finally (jing/close! h2)))))
        (finally (cleanup-file path))))))


(def ^:private metadata-literal-ast
  "A literal whose value carries non-position metadata -- the payload class
   the canonical CBOR encoding carries and the old `pr-str` codec dropped."
  {:type :literal, :value (with-meta [1 2] {:meaning "retained"})})


(deftest metadata-bearing-literal-round-trips-or-is-refused
  (testing "the memory backend carries it: full round trip"
    (let [h (jing-mem/create-content-mem)
          tree (vm/ast->semantic-bytecode metadata-literal-ast)
          root (content/materialize-tree! h tree)
          loaded (fetch-tree h root)
          lit-row (some (fn [row] (when (= :literal (nth row 1)) row))
                        (vals (:rows loaded)))]
      (is (= tree loaded)
          "metadata survives materialize! -> get -> validate")
      (is (= {:meaning "retained"} (meta (nth lit-row 2)))
          "the fetched literal's value carries its metadata")
      (jing/close! h)))

  (testing "the file backend carries it too: full round trip across reopen"
    (let [path (temp-path "u10-metadata")
          h (jing-file/create-content-file path)
          tree (vm/ast->semantic-bytecode metadata-literal-ast)]
      (try
        (let [root (content/materialize-tree! h tree)]
          (jing/close! h)
          (let [h2 (jing-file/create-content-file path)
                loaded (fetch-tree h2 root)
                lit-row (some (fn [row] (when (= :literal (nth row 1)) row))
                              (vals (:rows loaded)))]
            (try
              (is (= tree loaded)
                  "metadata survives the CBOR frame across close and reopen")
              (is (= {:meaning "retained"} (meta (nth lit-row 2)))
                  "the replayed literal's value carries its metadata")
              (finally (jing/close! h2)))))
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
      (is (= tree-b (fetch-tree h (:root tree-b)))
          "the second tree loads from rows the first tree already stored")
      (jing/close! h))))


(deftest fetch-refuses-bad-content
  (let [h (jing-mem/create-content-mem)]
    (testing "an absent address is refused naming it"
      (is (= :absent
             (:reason (lt/fetch-local h {(jing/segment-key [:literal 1])
                                         (jing/segment-key [:literal 1])}
                                      linker/ast-format
                                      (jing/segment-key [:literal 1])
                                      receiver
                                      (requested linker/ast-format))))))
    (testing "a stored row set that fails the row-local rules is
              refused with its defect"
      ;; a body with the wrong arity for its tag, stored under its own
      ;; true address so only the validator can catch it
      (let [bad-root (jing/materialize! h [:literal 1 :extra])
            res (lt/fetch-local h {bad-root bad-root} linker/ast-format
                                bad-root receiver
                                (requested linker/ast-format))]
        (is (= :descriptor-defect (:reason res)))
        (is (= :arity (:rule (:defect res))))))
    (testing "a stored row body is refused by the vector grammar, not
              reinterpreted (it hashes to its own address -- a legal
              payload, just not a vector)"
      (let [row-address (jing/materialize! h [:literal 7])
            res (lt/fetch-local h {row-address row-address}
                                linker/semantic-format row-address receiver
                                (requested linker/semantic-format))]
        (is (= :descriptor-defect (:reason res)))
        (is (= :mnemonic (:rule (:defect res))))))
    (testing "a correctly-hashed malformed vector is refused by the
              vector grammar"
      (let [malformed [[:jump 9]]           ; U5's own refusal case
            address (jing/materialize! h malformed)
            res (lt/fetch-local h {address address} linker/semantic-format
                                address receiver
                                (requested linker/semantic-format))]
        (is (= :descriptor-defect (:reason res)))
        (is (= :target-bounds (:rule (:defect res))))))
    (jing/close! h))

  (testing "materialize-vector! refuses a malformed vector before the write"
    (let [h (jing-mem/create-content-mem)]
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"fails validation"
            (content/materialize-vector! h [[:jump 9]])))
      (is (not (contains? (jing-mem/entries h) (jing/segment-key [[:jump 9]]))))
      (jing/close! h))))


(deftest fetched-vectors-carry-their-address-into-the-vm
  (testing "the UCF section 7.3.4 lane: a fetched vector loads with its
            :yin.code/hash recorded in the image and alias column"
    (let [h (jing-mem/create-content-mem)
          tree (vm/ast->semantic-bytecode
                 {:type :lambda, :params '[x],
                  :body {:type :application,
                         :operator {:type :variable, :name 'x},
                         :operands [{:type :literal, :value 1}]}})
          vector (:vector (linearize/lower-rows tree))
          address (content/materialize-vector! h vector)
          res (lt/fetch-local h {address address} linker/semantic-format
                              address receiver
                              (requested linker/semantic-format))
          loaded (semantic/load-vector (semantic/create-vm) (:value res)
                                       (:contract linker/semantic-format))
          seg (get-in loaded [:code-aliases address])]
      (is (= address (:address res)))
      (is (number? seg) "the address is aliased to a local segment id")
      (is (= address (get-in loaded [:code seg :address]))
          ":yin.code/hash is the image's recorded address")
      (jing/close! h))))
