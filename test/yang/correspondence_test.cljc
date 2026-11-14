(ns yang.correspondence-test
  (:require [clojure.test :refer [deftest is testing]]
            [yang.correspondence :as correspondence]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [dao.jing :as jing])
            #?(:clj [dao.jing.mem :as mem])
            #?(:clj [yin.repl.store :as file-store])))


(def address-a :segment/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)
(def address-b :segment/sha256-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb)


(def recipe
  {:yang.correspondence.recipe/schema 1
   :yang.correspondence.recipe/function 'example/reconstruct
   :yang.correspondence.recipe/profile address-a
   :yang.correspondence.recipe/input-roles [[:source 0]]
   :yang.correspondence.recipe/evidence-contract [:slice9-site-tables 1]
   :yang.correspondence.recipe/coverage address-b})


(def record-value
  {:yang.correspondence/schema 1
   :yang.correspondence/evidence address-a
   :yang.correspondence/recipe address-b
   :yang.correspondence/inputs [address-a]
   :yang.correspondence/roots [address-b]
   :yang.correspondence/origin-bindings [[0 [[:source :program ["run" 0] 0] address-b []]]]
   :yang.correspondence/artifacts []
   :yang.correspondence/metadata address-a
   :yang.correspondence/instruction-provenance []})


(deftest closed-structural-contracts
  (is (correspondence/recipe? recipe))
  (is (correspondence/correspondence? record-value))
  (testing "unknown keys and schema revisions are refused"
    (is (not (correspondence/recipe? (assoc recipe :extra true))))
    (is (not (correspondence/correspondence? (assoc record-value :verified true))))
    (is (not (correspondence/correspondence? (assoc record-value :yang.correspondence/schema 2)))))
  (testing "addresses, unique declarations, and persisted origin shapes are required"
    (is (not (correspondence/correspondence? (assoc record-value :yang.correspondence/evidence "commit"))))
    (is (not (correspondence/correspondence? (assoc record-value :yang.correspondence/inputs [address-a address-a]))))
    (is (not (correspondence/correspondence? (assoc record-value :yang.correspondence/origin-bindings [[0 [[:ephemeral :scope :run 0] address-b []]]]))))
    (is (not (correspondence/correspondence? (assoc record-value :yang.correspondence/origin-bindings (vec (repeat 2 (first (:yang.correspondence/origin-bindings record-value))))))))))


#?(:clj
   (deftest frozen-evidence-content-addressed-round-trip
     (let [file (io/resource "yang/latte_oracle/slice9_site_tables.edn")
           bytes (java.nio.file.Files/readAllBytes (.toPath (io/file file)))
           hash (format "%064x" (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))
           evidence (edn/read-string (slurp file))
           store (mem/create-content-mem)
           address (correspondence/store-evidence! store evidence)]
       (is (= "e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75" hash))
       (is (= 1 (:version evidence)))
       (is (jing/segment-address? address))
       (is (= evidence (jing/get store address ::missing)))
       (is (thrown? Exception (correspondence/store-evidence! store (assoc evidence :version 2)))))))


#?(:clj
   (deftest addressed-record-references-must-be-retrievable
     (let [store (mem/create-content-mem)
           put #(jing/materialize! store %)
           evidence (correspondence/store-evidence! store {:version 1 :fixtures {}})
           profile (put {:frontend :test})
           coverage (put {:scope :tree-only})
           input (put {:source '(example)})
           root (put [:test-root])
           metadata (put [])
           r (assoc recipe :yang.correspondence.recipe/profile profile
                    :yang.correspondence.recipe/coverage coverage)
           r-address (correspondence/store-recipe! store r)
           d (assoc record-value :yang.correspondence/evidence evidence
                    :yang.correspondence/recipe r-address
                    :yang.correspondence/inputs [input]
                    :yang.correspondence/roots [root]
                    :yang.correspondence/metadata metadata
                    :yang.correspondence/origin-bindings [])
           address (correspondence/store-record! store d)]
       (is (= r (jing/get store r-address ::missing)))
       (is (= d (jing/get store address ::missing)))
       (is (= address (jing/segment-key d)))
       (is (thrown? Exception (correspondence/store-record! store (assoc d :yang.correspondence/inputs [address-a]))))
       (is (thrown? Exception (correspondence/store-recipe! store (assoc r :yang.correspondence.recipe/profile address-a))))
       (is (thrown? Exception (correspondence/store-record! store (assoc d :yang.correspondence/inputs []))))
       (let [origin [:source :program ["live" 0] 0]
             site [origin root []]
             marker [origin root [] :yang/call-site 1]
             bound (assoc d :yang.correspondence/metadata (put [marker])
                          :yang.correspondence/origin-bindings [[1 site]])]
         (is (jing/segment-address? (correspondence/store-record! store bound)))
         (is (thrown? Exception (correspondence/store-record! store (assoc bound :yang.correspondence/metadata metadata))))
         (is (thrown? Exception (correspondence/store-record! store (assoc bound :yang.correspondence/roots [input])))))
       (let [wrong (put {:version 2 :fixtures {}})]
         (is (thrown? Exception (correspondence/store-record! store (assoc d :yang.correspondence/evidence wrong)))))
       (let [other-coverage (put {:scope :images})
             other-recipe (correspondence/store-recipe! store (assoc r :yang.correspondence.recipe/coverage other-coverage))]
         (is (thrown? Exception (correspondence/store-record! store (assoc d :yang.correspondence/recipe other-recipe))))))))


#?(:clj
   (defn- write-durable-fixture!
     [handle evidence]
     (let [put #(jing/materialize! handle %)
           evidence-address (correspondence/store-evidence! handle evidence)
           profile (put {:frontend :test})
           coverage (put {:scope :tree-only})
           input (put {:source '(example)})
           root (put [:test-root])
           metadata (put [])
           recipe-value (assoc recipe :yang.correspondence.recipe/profile profile
                               :yang.correspondence.recipe/coverage coverage)
           recipe-address (correspondence/store-recipe! handle recipe-value)
           d1 (assoc record-value :yang.correspondence/evidence evidence-address
                     :yang.correspondence/recipe recipe-address
                     :yang.correspondence/inputs [input]
                     :yang.correspondence/roots [root]
                     :yang.correspondence/metadata metadata
                     :yang.correspondence/origin-bindings [])
           d1-address (correspondence/store-record! handle d1)]
       (mapv (fn [[address value]]
               [address value ((:get-bytes-fn handle) address nil)])
             [[evidence-address evidence] [recipe-address recipe-value] [d1-address d1]]))))


#?(:clj
   (deftest file-backed-roundtrip
     (let [dir (str "target/test-d1-correspondence-" (random-uuid))
           file (io/resource "yang/latte_oracle/slice9_site_tables.edn")
           evidence (edn/read-string (slurp file))
           handle (file-store/open {:type :file :dir dir})]
       (try
         (let [written (try (write-durable-fixture! handle evidence)
                            (finally (file-store/close! handle)))
               reopened (file-store/open {:type :file :dir dir})]
           (try
             (doseq [[address value bytes] written]
               (is (= value (jing/get reopened address ::missing)))
               (is (java.util.Arrays/equals ^bytes bytes
                                            ^bytes ((:get-bytes-fn reopened) address nil))))
             (finally (file-store/close! reopened))))
         (finally
           (let [f (io/file dir)]
             (when (.exists f)
               (run! #(.delete %) (reverse (file-seq f))))))))))
