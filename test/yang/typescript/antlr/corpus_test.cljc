(ns yang.typescript.antlr.corpus-test
  "T1 step S7: the committed corpus against the Node oracle, on every host.
   `t1_packets.edn` holds the JVM parser's CST packet for each corpus entry
   (there is no TypeScript parser off the JVM), `t1_oracle.edn` what Node
   prints for the source (an uncaught throw as one `Uncaught <name>:
   <message>` line, in order). Each host lowers the pinned packet itself,
   runs it bundled on the four VMs and linked on the three vector VMs, and
   compares the rendered output line for line with Node's. A disagreement is
   a frontend defect, not a fixture to edit.

   On the JVM the same test also parses every corpus source and asserts the
   packet equals the pinned one, so the pins cannot rot. Both fixtures are
   written by `clojure -M:dev -m yang-ts-oracle` on the JVM with the mise
   Node recorded in their header, and never regenerated on another host or
   another Node."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]
    [dao.test-slow :as slow]
    [yang.typescript.antlr.linked-harness :as linked]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]
    [yin.vm :as vm]
    #?@(:cljd [["dart:io" :as dart-io]]
        :clj [[yang.typescript.antlr.cst :as cst]])))


(def ^:private pinned-node
  "The Node the oracle was generated under (owner decision A12.2 open: the
   mise Node, not an LTS). Moving it is a regeneration plus a diff review."
  "v25.6.1")


(defn- read-text
  [path]
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(defn- read-fixture
  [nm]
  (edn/read-string (read-text (str "test/resources/yang/typescript/" nm))))


(def ^:private corpus* (delay (read-fixture "t1_corpus.edn")))
(def ^:private oracle* (delay (read-fixture "t1_oracle.edn")))
(def ^:private packets* (delay (read-fixture "t1_packets.edn")))


(defn- by-id
  [fixture]
  (into {} (map (juxt :id identity)) (:entries fixture)))


(defn- actual-lines
  "Node's view of a rendered run: the logged lines, then the uncaught line."
  [{:js/keys [out uncaught]}]
  (cond-> (vec out)
    uncaught (conj (str "Uncaught " uncaught))))


(defn- attempt
  [f]
  (try (actual-lines (render/output (f)))
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         [:thrown (ex-message e)])))


(deftest fixtures-agree-test
  (let [corpus @corpus*
        oracle @oracle*
        packets @packets*]
    (testing "the corpus is 80 to 120 entries with unique ids"
      (is (<= 80 (count corpus) 120))
      (is (= (count corpus) (count (set (map :id corpus))))))
    (testing "the oracle and the packets cover exactly the corpus"
      (is (= (map :id corpus) (map :id (:entries oracle))))
      (is (= (map :id corpus) (map :id (:entries packets)))))
    (testing "the oracle header names the pinned Node, and every run exited 0"
      (is (= pinned-node (get-in oracle [:header :node])))
      (is (= pinned-node (get-in packets [:header :node])))
      (is (every? #(zero? (:exit %)) (:entries oracle))))))


(defn- run-bundled
  [ast]
  (into {}
        (map (fn [[k run]] [k (attempt #(vm/value (run ast)))]))
        parity/vm-runners))


(defn- run-linked
  [ast]
  (into {}
        (map (fn [k]
               [k (attempt #(vm/value (linked/run-linked k ast)))]))
        (keys linked/backends)))


(defn- disagreements
  "Every `[id profile vm expected actual]` where a VM's output differs from
   Node's, over the corpus entries `run` is applied to."
  [profile lower-opts run]
  (let [oracle (by-id @oracle*)
        packets (by-id @packets*)]
    (vec (for [{:keys [id]} @corpus*
               :let [expected (:out (get oracle id))
                     ast (lower/lower-packet (:packet (get packets id))
                                             lower-opts)]
               [k actual] (run ast)
               :when (not= expected actual)]
           [id profile k expected actual]))))


(defn- bundled-matches-node
  []
  (is (= [] (disagreements :bundled {} run-bundled))))


(deftest ^:slow bundled-matches-node-test
  (slow/guard "bundled-matches-node-test" bundled-matches-node))


(defn- linked-matches-node
  []
  (is (= [] (disagreements :linked {:prelude :linked} run-linked))))


(deftest ^:slow linked-matches-node-test
  (slow/guard "linked-matches-node-test" linked-matches-node))


#?(:cljd nil
   :clj
   (deftest packets-are-current-test
     (let [worker (cst/make-worker :ts)
           packets (by-id @packets*)]
       (is (= []
              (vec (for [{:keys [id source]} @corpus*
                         :when (not= (:packet (get packets id))
                                     (cst/parse-source worker [:unit 0]
                                                       source))]
                     id)))))))
