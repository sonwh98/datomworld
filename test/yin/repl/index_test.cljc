(ns yin.repl.index-test
  "The shell's code indexer (docs/design/yin.repl.dao.space-index.md): a
   dao.space.index observer on program-out, one transaction per forwarded
   program, covered indexes published into dao.jing each round."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.file :as jing.file]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.index :as repl.index]
            [yin.repl.store :as store]
            [yin.vm :as vm]
            [yin.vm.macro :as macro]))


(defn- stream-values
  "Every value currently on a stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- transactions
  "The atomic transaction records the session's indexer committed."
  [state]
  (mapv :dao.space/transaction
        (stream-values (get-in state [:indexer :local]))))


(defn- evaluate
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(def ^:private ref-attrs
  (into #{}
        (keep (fn [[a spec]] (when (= :db.type/ref (:db/valueType spec)) a)))
        vm/schema))


(defn- modulo-ids
  "`[e a v]` of `datoms` with entity ids relabelled by first appearance, so
   two projections of one tree compare equal whatever ids they minted."
  [datoms]
  (let [ids (into {}
                  (map-indexed (fn [i e] [e i]))
                  (distinct (map first datoms)))
        relabel (fn [a v]
                  (cond (not (contains? ref-attrs a)) v
                        (vector? v) (mapv ids v)
                        :else (ids v)))]
    (mapv (fn [[e a v]] [(ids e) a (relabel a v)]) datoms)))


(deftest one-program-is-one-transaction-of-its-expanded-projection
  (let [state (repl/create-state)
        [state' result] (repl/eval-input state "(+ 1 2)")
        [packet] (stream-values (:row-stream state'))
        root (first packet)
        rows (:rows (macro/packet->row-set packet))
        txs (transactions state')
        {:keys [t datoms]} (first txs)
        meta-e (some (fn [[e a]] (when (= :db/op a) e)) datoms)
        meta-facts (filterv #(= meta-e (first %)) datoms)
        addresses (filterv #(= :yin/address (second %)) datoms)
        ast-facts (filterv #(and (not= meta-e (first %))
                                 (not= :yin/address (second %)))
                           datoms)]
    (is (= "3" result) "the evaluator's answer is unchanged")
    (is (= 1 (count txs)) "exactly one transaction for the program")
    (testing "its datoms are ast->datoms of the expanded tree, modulo ids"
      (is (= (modulo-ids (vm/ast->datoms (vm/semantic-bytecode->ast
                                           (macro/packet->row-set packet))))
             (modulo-ids ast-facts))))
    (testing "the transactor allocated t; every datom carries it"
      (is (integer? t))
      (is (every? #(= t (nth % 3)) datoms)))
    (testing "e is transactor-local, with :yin/address back to the row"
      (is (every? #(and (integer? (first %)) (<= 16 (first %))) datoms))
      (is (= (set (map first ast-facts)) (set (map first addresses)))
          "every node occurrence carries exactly one address fact")
      (is (= (count addresses) (count (set (map first addresses)))))
      (is (every? #(contains? rows (nth % 2)) addresses))
      (let [root-e (some (fn [[e a]] (when (= :yin/root a) e)) ast-facts)]
        (is (some #(= [root-e :yin/address root] (subvec % 0 3)) addresses)
            "the root occurrence names the program's content address")))
    (testing "m is a local metadata entity carrying the session provenance"
      (is (integer? meta-e))
      (is (every? #(= meta-e (nth % 4)) (concat ast-facts addresses)))
      (is (= {:db/op :db/assert
              :yin.repl/session (:shell-token state)
              :yin.repl/root root
              :yin.repl/round 1}
             (into {} (map (fn [[_ a v]] [a v])) meta-facts))))
    (testing "only code is indexed: no result or printed output"
      (is (not-any? #(= 3 (nth % 2))
                    (filter #(= :yin/value (second %)) ast-facts))
          "the literal operands are 1 and 2; the result 3 appears nowhere")
      (is (= #{1 2}
             (set (keep (fn [[_ a v]] (when (= :yin/value a) v)) ast-facts)))))))


(deftest the-round-publishes-covered-indexes-readable-from-dao-jing
  (let [[state _] (evaluate (repl/create-state)
                            ["(def inc2 (fn [x] (+ x 2)))" "(inc2 40)"])
        {:keys [manifest-address content-store]} (:indexer state)
        committed (into #{} (mapcat :datoms) (transactions state))]
    (is (some? manifest-address))
    (is (identical? content-store (:index-store state))
        "publication lands in the shell's dao.jing store")
    (testing "the manifest's EAVT index holds every committed datom"
      (is (= 2 (count (transactions state))))
      (is (= committed (set (index/read-datoms content-store manifest-address)))))
    (testing "each restored covered index holds the committed facts"
      (let [manifest (index/read-manifest content-store manifest-address)
            restored (index/restored-indexes content-store manifest)]
        (doseq [k [:eavt :aevt :avet :vaet]]
          (is (= committed (set (seq (get restored k)))) (str k)))))
    (testing "the datoms read back from the store answer queries"
      (let [published (query/relation
                        (index/read-datoms content-store manifest-address))
            names (query/collect
                    (query/q '[:find ?name
                               :where [$ ?e :yin/type :variable]
                               [$ ?e :yin/name ?name]]
                             (query/current published)))
            sessions (query/collect
                       ;; the history view exposes m; current binds [e a v]
                       (query/q '[:find ?session ?round
                                  :where [?e :yin/root true ?t ?m]
                                  [?m :yin.repl/session ?session ?t1 ?m1]
                                  [?m :yin.repl/round ?round ?t2 ?m2]]
                                (query/history published)))]
        (is (every? (set (map first names)) '[yin/def x + inc2]))
        (is (= #{[(:shell-token state) 1] [(:shell-token state) 2]}
               (set sessions))
            "each program's root reaches its provenance through m")))))


(deftest failed-expansions-add-nothing-raising-and-parking-programs-are-indexed
  (testing "a failed expansion forwards nothing, so nothing is committed"
    (let [[state [defined failed]]
          (evaluate (repl/create-state)
                    ["(defmacro unless [c a b] (yin/if c b a))"
                     "(unless 1 2)"])]
      (is (not (str/starts-with? defined "Error")))
      (is (str/starts-with? failed "Error: Macro expansion failed"))
      (is (= 1 (count (transactions state)))
          "only the defmacro program was committed")))
  (testing "a program whose VM raises is still indexed"
    (let [[state [raised]] (evaluate (repl/create-state) ["(nope 1)"])]
      (is (str/starts-with? raised "Error: "))
      (is (= 1 (count (transactions state))))
      (is (some? (get-in state [:indexer :manifest-address])))))
  (testing "a program whose VM parks is still indexed"
    (let [[state [text]] (evaluate (repl/create-state {:vm-type :stack})
                                   ["(require (quote mod))"])]
      (is (str/includes? text "pending"))
      (is (some? (:pending-run state)))
      (is (= 1 (count (transactions state)))))))


(deftest every-evaluator-is-indexed-the-same-way
  (doseq [vm-type (keys repl/vm-constructors)]
    (testing (str vm-type)
      (let [[state [text]] (evaluate (repl/create-state {:vm-type vm-type})
                                     ["(+ 1 2)"])]
        (is (= "3" text))
        (is (= 1 (count (transactions state))))))))


(defn- small-medium
  "A ring medium of `capacity` and an observer attached at its oldest
   position, as make-session composes program-out."
  [capacity]
  (let [writer (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key capacity}))
        descriptor (:dao.stream/descriptor (stream/descriptor writer))
        attach! (ring/make-attacher {(:dao.stream/identity descriptor) writer})]
    {:writer writer
     :observer (observer/attach attach! descriptor)}))


(defn- committed-values
  "The literal values of every program the indexer committed."
  [indexer]
  (into #{}
        (comp (map :dao.space/transaction)
              (mapcat :datoms)
              (keep (fn [[_ a v]] (when (= :yin/value a) v))))
        (stream-values (:local indexer))))


(defn- literal-packet
  [v]
  (macro/ast->packet {:type :literal, :value v}))


(deftest a-gap-on-the-index-reader-loses-the-indexer-until-rebuilt
  (let [{:keys [writer observer]} (small-medium 2)
        indexer (repl.index/make-indexer
                  {:observer observer
                   :session-token "token"
                   :content-store (:index-store (repl/create-state))})
        _ (stream/append! writer (literal-packet 1))
        indexed (repl.index/step indexer 1)
        _ (doseq [v [2 3 4]] (stream/append! writer (literal-packet v)))
        lost (repl.index/step indexed 2)
        _ (stream/append! writer (literal-packet 5))
        still-lost (repl.index/step lost 3)]
    (is (= {:transactions 1 :published? true :lost? false :gaps 0 :failure nil}
           (repl.index/status indexed)))
    (testing "the gap is counted and marks the indexer lost"
      (is (= 1 (:gaps (repl.index/status lost))))
      (is (true? (:lost? (repl.index/status lost))))
      (is (= #{1} (committed-values lost))
          "neither the evicted packet nor the retained ones after it are called indexed"))
    (testing "nothing further is indexed while lost"
      (is (= 1 (:transactions (repl.index/status still-lost))))
      (is (= #{1} (committed-values still-lost)))
      (is (some? (repl.index/notice lost still-lost))
          "every round while lost says its code was not indexed"))))


(deftest no-earlier-publication-is-retained-between-rounds
  (let [held-keys #{:observer :local :content-store :session-token
                    :publish-opts :after-publish :next-e :transactions
                    :published :published-payloads :manifest-address
                    :lost? :failure}
        rounds (reductions
                 (fn [[state _] line] (repl/eval-input state line))
                 [(repl/create-state) nil]
                 ["(+ 1 2)" "(def f (fn [x] (* x x)))" "(f 7)" "(+ 40 2)"])]
    (doseq [[i [state _]] (map-indexed vector (rest rounds))
            :let [ix (:indexer state)
                  records (stream-values (:local ix))]]
      (testing (str "after round " (inc i))
        (is (= held-keys (set (keys ix)))
            "no transactor, intake, or pool outlives its round")
        (is (every? #(contains? % :dao.space/transaction) records)
            "the local log holds transaction records only, no publication payload")
        (is (= (inc i) (count records)))
        (is (true? (:published? (repl.index/status ix))))
        (is (pos? (:published-payloads ix))
            "the round did publish; its payloads went to dao.jing, not the indexer")))
    (testing "transaction time continues across per-round transactors"
      (let [ix (:indexer (first (last rounds)))]
        (is (= [0 1 2 3]
               (mapv (comp :t :dao.space/transaction)
                     (stream-values (:local ix)))))))))


(deftest a-publication-beyond-the-old-intake-bound-completes
  (let [{:keys [writer observer]} (small-medium 16)
        store (:index-store (repl/create-state))
        indexer (repl.index/make-indexer
                  {:observer observer
                   :session-token "token"
                   :content-store store
                   ;; a small branching factor makes many node blobs
                   :publish-opts {:branching-factor 4}})
        program (fn [base]
                  (macro/ast->packet
                    {:type :application
                     :operator {:type :variable, :name '+}
                     :operands (mapv (fn [i] {:type :literal, :value (+ base i)})
                                     (range 300))}))
        _ (doseq [base [0 1000 2000 3000]]
            (stream/append! writer (program base)))
        ix (repl.index/step indexer 1)
        committed (into #{}
                        (mapcat (comp :datoms :dao.space/transaction))
                        (stream-values (:local ix)))
        address (:manifest-address ix)]
    (is (< 4096 (:published-payloads ix))
        "one publication exceeds the old 4096-payload ring intake")
    (is (= {:transactions 4 :published? true :lost? false :gaps 0 :failure nil}
           (repl.index/status ix)))
    (is (= (count committed) (:count (index/read-manifest store address))))
    (is (= committed (set (index/read-datoms store address)))
        "the manifest and every node blob are read back from the store")))


(deftest a-publication-failure-is-reported-in-the-round-and-repl-state
  (let [broken {:put-bytes-fn (fn [_address _bytes]
                                (throw (ex-info "store refused the write" {})))
                :get-bytes-fn (fn [_address not-found] not-found)}
        [state [text again]] (evaluate (repl/create-state {:index-store broken})
                                       ["(+ 1 2)" "(+ 2 3)"])
        status (get-in (repl/repl-state state) [:index])]
    (is (str/starts-with? text "3\nWarning: "))
    (is (str/includes? text "publish failed: store refused the write"))
    (is (str/starts-with? again "5\nWarning: ")
        "each round whose code is not published says so")
    (is (= 2 (:transactions status)) "the transactions were committed")
    (is (false? (:published? status)))
    (is (= :publish (get-in status [:failure :stage])))
    (is (false? (:lost? status)))))


(deftest an-index-gap-is-reported-evaluation-continues-and-reset-recovers
  (let [{:keys [writer observer]} (small-medium 1)
        state (repl/create-state)
        _ (doseq [v [1 2]] (stream/append! writer (literal-packet v)))
        lossy (assoc-in state [:indexer :observer] observer)
        [lost text] (repl/eval-input lossy "(+ 1 2)")
        [later text'] (repl/eval-input lost "(+ 2 3)")]
    (testing "the gap is reported in the round result; evaluation continues"
      (is (str/starts-with? text "3\nWarning: "))
      (is (str/includes? text "(reset)"))
      (is (false? (:ingress-loss? lost)) "the evaluator is not refused")
      (is (= 0 (get-in (repl/repl-state lost) [:vm :in-stream :gaps]))
          "the evaluator's own readers lost nothing"))
    (testing "the indexer is lost, and says so, until reset"
      (is (= {:transactions 0 :published? false :lost? true :gaps 1 :failure nil}
             (get-in (repl/repl-state lost) [:index])))
      (is (str/starts-with? text' "5\nWarning: "))
      (is (= 0 (get-in (repl/repl-state later) [:index :transactions]))))
    (testing "reset rebuilds the observer with the session"
      (let [[fresh message] (repl/eval-input later "(reset)")
            [indexed result] (repl/eval-input fresh "(+ 1 2)")]
        (is (= "SemanticVM reset" message))
        (is (not (identical? (:indexer later) (:indexer fresh))))
        (is (= {:transactions 0 :published? false :lost? false :gaps 0
                :failure nil}
               (get-in (repl/repl-state fresh) [:index])))
        (is (identical? (:index-store later) (:index-store fresh))
            "the dao.jing store outlives the session")
        (is (= "3" result) "a healthy round carries no notice")
        (is (= 1 (count (transactions indexed))))
        (is (true? (get-in (repl/repl-state indexed)
                           [:index :published?])))))))


(deftest vm-selection-rebuilds-the-indexer
  (let [[state _] (evaluate (repl/create-state) ["(+ 1 2)"])
        [switched _] (repl/eval-input state "(vm :ast-walker)")]
    (is (= 1 (count (transactions state))))
    (is (empty? (transactions switched)))
    (is (not (identical? (get-in state [:indexer :observer :stream])
                         (get-in switched [:indexer :observer :stream]))))))


;; =============================================================================
;; The durable round's HEAD write — only after the manifest is read back
;; (the durable-store design, section 2). The memory store has no
;; after-publish hook and writes no HEAD.
;; =============================================================================

(defn- temp-store-dir
  []
  (str "target/test-index-head-" (random-uuid)))


(defn- cleanup-store-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- head-text
  [dir]
  #?(:cljd (let [f (dart-io/File. (str dir "/HEAD"))]
             (when (.existsSync f) (.readAsStringSync f)))
     :clj (let [f (java.io.File. (str dir "/HEAD"))]
            (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")
                 path (str dir "/HEAD")]
             (when (.existsSync fs path)
               (.readFileSync fs path "utf8")))))


(deftest a-durable-round-writes-head-only-after-the-manifest-read-back
  (testing "a healthy round: blobs, read-back, then HEAD names the manifest"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [[state text] (repl/eval-input
                             (repl/create-state {:index-store opened})
                             "(+ 1 2)")
              manifest (get-in state [:indexer :manifest-address])]
          (is (= "3" text))
          (is (true? (get-in (repl/repl-state state) [:index :published?])))
          (is (str/includes? (str (head-text dir)) (str manifest))
              "HEAD names the manifest the round published"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir)))))
  (testing "a round whose manifest cannot be read back writes no HEAD"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [broken (assoc opened
                            :get-bytes-fn (fn [_address _not-found]
                                            (throw (ex-info
                                                     "store refused the read"
                                                     {}))))
              [state text] (repl/eval-input
                             (repl/create-state {:index-store broken})
                             "(+ 1 2)")]
          (is (str/starts-with? text "3\nWarning: ")
              "the round evaluates and says what its publication lost")
          (is (= :publish (get-in (repl/repl-state state)
                                  [:index :failure :stage])))
          (is (nil? (head-text dir))
              "no manifest read-back, no HEAD — never a HEAD naming an
               unreadable snapshot")
          (is (pos? (count (jing.file/records (store/content-path dir))))
              "the blobs themselves did land"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir)))))
  (testing "a round whose HEAD write fails reports the round unpublished"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [[first-state _] (repl/eval-input
                                (repl/create-state {:index-store opened})
                                "(+ 1 2)")
              first-manifest (get-in first-state [:indexer :manifest-address])
              failing (assoc opened
                             :head-fn (fn [_manifest]
                                        (throw (ex-info "disk gone" {}))))
              [second-state text] (repl/eval-input
                                    (repl/create-state {:index-store failing})
                                    "(+ 2 3)")]
          (is (str/starts-with? text "5\nWarning: "))
          (is (false? (get-in (repl/repl-state second-state)
                              [:index :published?])))
          (is (nil? (get-in second-state [:indexer :manifest-address]))
              "the round whose HEAD did not move reports no manifest")
          (is (str/includes? (str (head-text dir)) (str first-manifest))
              "the previous published snapshot keeps the HEAD"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir))))))
