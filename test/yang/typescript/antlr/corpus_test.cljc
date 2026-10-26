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
    [clojure.string :as str]
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


(def ^:private corpus-names
  "The committed corpora, each `<name>_corpus.edn`, `<name>_oracle.edn` and
   `<name>_packets.edn`."
  ["t1" "t2"])


(def ^:private enabled-steps
  "The steps whose corpus entries run (R14): an entry with a `:step` outside
   this set is pending its step; an entry without one always runs."
  #{:S6 :S5b :S6b :S8})


(def ^:private fixtures*
  (delay (into {}
               (map (fn [nm]
                      [nm {:corpus (read-fixture (str nm "_corpus.edn")),
                           :oracle (read-fixture (str nm "_oracle.edn")),
                           :packets (read-fixture (str nm "_packets.edn"))}]))
               corpus-names)))


(defn- enabled?
  [{:keys [step]}]
  (or (nil? step) (contains? enabled-steps step)))


(defn- by-id
  [fixture]
  (into {} (map (juxt :id identity)) (:entries fixture)))


(defn- actual-lines
  "Node's view of a rendered run: the logged lines, then the uncaught line."
  [{:js/keys [out uncaught]}]
  (cond-> (vec (mapcat #(str/split % #"\n" -1) out))
    uncaught (conj (str "Uncaught " uncaught))))


(defn- attempt
  [f]
  (try (actual-lines (render/output (f)))
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         [:thrown (ex-message e)])))


(deftest fixtures-agree-test
  (doseq [nm corpus-names
          :let [{:keys [corpus oracle packets]} (get @fixtures* nm)]]
    (testing nm
      (testing "the corpus is 80 to 120 entries with unique ids"
        (is (<= 80 (count corpus) 120))
        (is (= (count corpus) (count (set (map :id corpus))))))
      (testing "the oracle and the packets cover exactly the corpus"
        (is (= (map :id corpus) (map :id (:entries oracle))))
        (is (= (map :id corpus) (map :id (:entries packets)))))
      (testing "the oracle header names the pinned Node, and every run exited 0"
        (is (= pinned-node (get-in oracle [:header :node])))
        (is (= pinned-node (get-in packets [:header :node])))
        (is (every? #(zero? (:exit %)) (:entries oracle))))
      (testing "a departure entry names its reason and a pinned output"
        (is (every? (fn [e]
                      (or (nil? (:departure e))
                          (and (vector? (get-in e [:departure :out]))
                               (string? (get-in e [:departure :reason])))))
                    corpus))))))


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


(defn- expected-lines
  "Node's lines, or the pinned deviation of a departure entry."
  [entry oracle-entry]
  (or (get-in entry [:departure :out]) (:out oracle-entry)))


(defn- progress!
  "One flushed line per entry, so a long leg shows how far it got."
  [label nm id]
  (println label nm id)
  #?(:clj (do (flush)
              (spit "target/corpus-progress.log"
                    (str label " " nm " " id "\n")
                    :append true))))


(defn- disagreements
  "Every `[corpus id profile vm expected actual]` where a VM's output differs
   from Node's (or from a departure's pinned output), over the enabled
   entries of the corpora `names`."
  [names profile lower-opts run]
  (vec (for [nm names
             :let [{:keys [corpus oracle packets]} (get @fixtures* nm)
                   oracle (by-id oracle)
                   packets (by-id packets)]
             {:keys [id] :as entry} corpus
             :when (enabled? entry)
             :let [_ (progress! "run" nm id)
                   expected (expected-lines entry (get oracle id))
                   ast (try (lower/lower-packet (:packet (get packets id))
                                                lower-opts)
                            (catch #?(:cljd Object :clj Exception :cljs :default) e
                              e))]
             [k actual] (if (map? ast)
                          (run ast)
                          {:lowering [:thrown (ex-message ast)]})
             :when (not= expected actual)
             :let [_ (progress! "MISMATCH" nm id)]]
         [nm id profile k expected actual])))


(defn- leg
  [names profile lower-opts run]
  (is (= [] (disagreements names profile lower-opts run))))


;; One leg per corpus and profile: each runs the enabled entries on every
;; VM of the profile and is slow on its own (t2 is about 120 entries).
(deftest ^:slow t2-bundled-matches-node-test
  (slow/guard "t2-bundled-matches-node-test"
              #(leg ["t2"] :bundled {} run-bundled)))


(deftest ^:slow t2-linked-matches-node-test
  (slow/guard "t2-linked-matches-node-test"
              #(leg ["t2"] :linked {:prelude :linked} run-linked)))


(deftest ^:slow t1-bundled-matches-node-test
  (slow/guard "t1-bundled-matches-node-test"
              #(leg ["t1"] :bundled {} run-bundled)))


(deftest ^:slow t1-linked-matches-node-test
  (slow/guard "t1-linked-matches-node-test"
              #(leg ["t1"] :linked {:prelude :linked} run-linked)))


(deftest pending-entries-are-named-test
  (testing "every entry outside the enabled steps names a known pending step"
    (is (every? (fn [e] (or (enabled? e) (keyword? (:step e))))
                (mapcat (comp :corpus val) @fixtures*)))))


#?(:cljd nil
   :clj
   (deftest packets-are-current-test
     (let [worker (cst/make-worker :ts)]
       (is (= []
              (vec (for [nm corpus-names
                         :let [{:keys [corpus packets]} (get @fixtures* nm)
                               packets (by-id packets)]
                         {:keys [id source]} corpus
                         :when (not= (:packet (get packets id))
                                     (cst/parse-source worker [:unit 0]
                                                       source))]
                     [nm id])))))))
