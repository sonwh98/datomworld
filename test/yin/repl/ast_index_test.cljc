(ns yin.repl.ast-index-test
  "The shell's AST indexer: the session's `$ast` row relation and `$occ`
   occurrence relation, an observer on program-out beside the evaluator
   and the code indexer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.ast-index :as ast-index]
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


(defn- evaluate
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


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


(defn- literal-packet
  [v]
  (macro/ast->packet {:type :literal, :value v}))


(def ^:private lost-warning
  "Warning: the AST index lost a program batch; $ast and $occ are unavailable until (reset)")


(defn- refused-warning
  [cause]
  (str "Warning: the AST index refused a program (" cause
       "); $ast and $occ are unavailable until (reset)"))


(defn- relations
  [state]
  (ast-index/relations (:ast-indexer state)))


(defn- expected
  "The `$ast` rows and `$occ` tuples of every packet on the session's
   program-out, computed independently of the indexer."
  [state]
  (let [row-sets (map macro/packet->row-set
                      (stream-values (:row-stream state)))]
    {:ast (set (mapcat (comp vals :rows) row-sets))
     :occ (into #{} (mapcat vm/occurrences) row-sets)}))


(deftest every-vm-indexes-rows-and-occurrences
  (let [statuses
        (for [vm-type (keys repl/vm-constructors)]
          (let [[state [text]] (evaluate (repl/create-state {:vm-type vm-type})
                                         ["(+ 1 2)"])
                [packet] (stream-values (:row-stream state))
                root (first packet)
                {:keys [ast occ]} (relations state)]
            (testing (str vm-type)
              (is (= "3" text))
              (is (= (set (second packet)) (set ast))
                  "$ast holds the forwarded packet's canonical rows")
              (is (= (count ast) (count (set (map first ast))))
                  "one row per address")
              (is (= (vm/occurrences (macro/packet->row-set packet)) occ))
              (is (contains? occ [root [] root]) "the root's own place")
              (is (some #(and (= root (first %)) (seq (second %))) occ)
                  "and the places below it"))
            (:ast-index (repl/repl-state state))))]
    (is (= {:programs 1 :rows (:rows (first statuses))
            :occurrences (:occurrences (first statuses))
            :lost? false :gaps 0 :failure nil}
           (first statuses)))
    (is (pos? (:rows (first statuses))))
    (is (apply = statuses) "every VM reports the same :ast-index status")))


(deftest identical-rows-are-shared-across-roots-with-distinct-occurrences
  (let [[state _] (evaluate (repl/create-state) ["(+ 1 2)" "(* (+ 1 2) 3)"])
        [[inner inner-rows] [outer outer-rows]]
        (stream-values (:row-stream state))
        shared (disj (into #{} (map first) (filter (set inner-rows) outer-rows))
                     inner outer)
        {:keys [ast occ]} (relations state)
        places (fn [root node]
                 (set (keep (fn [[r p n]] (when (and (= root r) (= node n)) p))
                            occ)))]
    (is (not= inner outer))
    (is (seq shared) "the programs share rows: the + variable and literals")
    (is (= (expected state) {:ast (set ast) :occ occ}))
    (is (= (count ast) (count (into (set inner-rows) outer-rows)))
        "a row shared by both programs is held once")
    (is (< (count ast) (+ (count inner-rows) (count outer-rows))))
    (testing "its occurrences stay root-scoped"
      (doseq [node shared]
        (is (seq (places inner node)))
        (is (seq (places outer node)))
        (is (not= (places inner node) (places outer node))
            "the same node sits at different paths under each root")))))


(deftest repeating-an-evaluation-adds-no-rows-or-occurrences
  (let [[once _] (evaluate (repl/create-state) ["(+ 1 2)"])
        [twice texts] (evaluate once ["(+ 1 2)" "(+ 1 2)"])]
    (is (= ["3" "3"] texts))
    (is (= 3 (count (stream-values (:row-stream twice)))))
    (is (= (relations once) (relations twice)))
    (is (= 3 (get-in (repl/repl-state twice) [:ast-index :programs])))))


(deftest failed-expansions-add-nothing-raising-and-parking-programs-are-indexed
  (testing "a failed expansion forwards nothing, so nothing is indexed"
    (let [[defined _] (evaluate (repl/create-state)
                                ["(defmacro unless [c a b] (yin/if c b a))"])
          [failed [text]] (evaluate defined ["(unless 1 2)"])]
      (is (str/starts-with? text "Error: Macro expansion failed"))
      (is (= (relations defined) (relations failed)))
      (is (= 1 (get-in (repl/repl-state failed) [:ast-index :programs])))))
  (testing "a program whose VM raises is still indexed"
    (let [[state [raised]] (evaluate (repl/create-state) ["(nope 1)"])]
      (is (str/starts-with? raised "Error: "))
      (is (= 1 (get-in (repl/repl-state state) [:ast-index :programs])))
      (is (some #(= 'nope (nth % 2 nil)) (:ast (relations state))))))
  (testing "a program whose VM parks is still indexed"
    (let [[state [text]] (evaluate (repl/create-state {:vm-type :stack})
                                   ["(require (quote mod))"])]
      (is (str/includes? text "pending"))
      (is (some? (:pending-run state)))
      (is (= 1 (get-in (repl/repl-state state) [:ast-index :programs])))
      (is (= (expected state) (update (relations state) :ast set))))))


(deftest a-round-that-throws-drains-the-ast-reader-without-indexing
  (let [state (repl/create-state)
        expander (:expander state)
        _ (stream/append! (:row-stream state) (literal-packet 7))
        [failed text] (repl/eval-input (assoc state :expander {}) "(+ 1 2)")
        [after result] (repl/eval-input (assoc failed :expander expander)
                                        "(+ 2 3)")]
    (is (str/starts-with? text "Error"))
    (is (= 0 (get-in (repl/repl-state failed) [:ast-index :programs]))
        "the failed round's packet is consumed, not indexed")
    (is (= "5" result))
    (is (= 1 (get-in (repl/repl-state after) [:ast-index :programs])))
    (is (not-any? #(= 7 (nth % 2 nil)) (:ast (relations after)))
        "a later round never indexes the skipped packet"))
  (testing "skip still counts a gap"
    (let [{:keys [writer observer]} (small-medium 1)
          _ (doseq [v [1 2]] (stream/append! writer (literal-packet v)))
          ix (ast-index/skip (ast-index/make-indexer {:observer observer}))]
      (is (true? (:lost? ix)))
      (is (= 0 (:programs ix))))))


(deftest an-ast-gap-loses-the-indexer-evaluation-continues-and-reset-recovers
  (let [{:keys [writer observer]} (small-medium 1)
        state (repl/create-state)
        _ (doseq [v [1 2]] (stream/append! writer (literal-packet v)))
        lossy (assoc-in state [:ast-indexer :observer] observer)
        [lost text] (repl/eval-input lossy "(+ 1 2)")
        _ (stream/append! writer (literal-packet 3))
        [later text'] (repl/eval-input lost "(+ 2 3)")]
    (testing "evaluation and the code indexer continue; every round warns"
      (is (= (str "3\n" lost-warning) text))
      (is (= (str "5\n" lost-warning) text'))
      (is (false? (:ingress-loss? lost)))
      (is (= 0 (get-in (repl/repl-state lost) [:vm :in-stream :gaps])))
      (is (false? (get-in (repl/repl-state later) [:index :lost?]))))
    (testing "the AST indexer is lost, counted, and unavailable"
      (is (= {:programs 0 :rows 0 :occurrences 0 :lost? true :gaps 1
              :failure nil}
             (get-in (repl/repl-state lost) [:ast-index])))
      (is (nil? (relations lost)))
      (is (= 0 (get-in (repl/repl-state later) [:ast-index :programs]))
          "later packets are consumed without being indexed")
      (is (empty? (get-in later [:ast-indexer :rows]))))
    (testing "reset rebuilds it with the session"
      (let [[fresh message] (repl/eval-input later "(reset)")
            [indexed result] (repl/eval-input fresh "(+ 1 2)")]
        (is (= "SemanticVM reset" message))
        (is (= {:programs 0 :rows 0 :occurrences 0 :lost? false :gaps 0
                :failure nil}
               (get-in (repl/repl-state fresh) [:ast-index])))
        (is (= "3" result) "a healthy round carries no warning")
        (is (= (expected indexed) (update (relations indexed) :ast set)))))))


(deftest vm-selection-rebuilds-the-ast-indexer
  (let [[state _] (evaluate (repl/create-state) ["(+ 1 2)"])
        [switched _] (repl/eval-input state "(vm :ast-walker)")]
    (is (seq (:ast (relations state))))
    (is (= {:ast [] :occ #{}} (relations switched)))
    (is (not (identical? (get-in state [:ast-indexer :observer :stream])
                         (get-in switched [:ast-indexer :observer :stream]))))))


(defn- index-packets
  "Step a fresh AST indexer over `packets` on a medium of its own."
  [packets]
  (let [{:keys [writer observer]} (small-medium 16)]
    (doseq [p packets] (stream/append! writer p))
    (ast-index/step (ast-index/make-indexer {:observer observer}))))


(deftest a-malformed-packet-leaves-the-relations-unavailable
  (let [[root rows] (literal-packet 1)
        good (literal-packet 2)]
    (doseq [[label packet reason]
            [["not a packet" "garbage" :shape]
             ["rows not rows" [root [:x]] :shape]
             ["an unknown tag" [root [[root :no-such-tag]]] :invalid-rows]
             ["a missing root" [root []] :invalid-rows]
             ["one address, two rows"
              [root (conj rows (assoc (first rows) 2 :other))]
              :duplicate-address]
             ["one address, the same row twice"
              [root (conj rows (first rows))]
              :duplicate-address]
             ["a well-formed row under a forged address"
              (let [forged (first (literal-packet 3))]
                [forged [(assoc (first rows) 0 forged)]])
              :address-mismatch]]]
      (testing label
        (let [ix (index-packets [good packet good])]
          (is (= reason (get-in ix [:failure :reason])))
          (is (nil? (ast-index/relations ix)) "never a partial snapshot")
          (is (= 1 (:programs (ast-index/status ix)))
              "the packet before it was indexed, none after")
          (is (false? (:lost? (ast-index/status ix)))))))
    (testing "a row forged under an address already held"
      (let [[groot grows] good
            forged [groot (mapv #(if (= groot (first %)) (assoc % 2 99) %)
                                grows)]
            ix (index-packets [good forged])]
        (is (= :address-mismatch (get-in ix [:failure :reason])))
        (is (nil? (ast-index/relations ix)))))
    (testing "an address already holding a metadata-distinct row"
      ;; yin.vm.macro-test's fixture: the canonical encoding strips reader
      ;; positions, so bodies differing only there share one address
      (let [literal-row (fn [v]
                          (let [body [:literal v]]
                            (into [(jing/segment-key body)] body)))
            r1 (literal-row (with-meta 'x {:line 1}))
            r2 (literal-row (with-meta 'x {:line 2}))
            ix (index-packets [[(first r1) [r1]] [(first r2) [r2]]])]
        (is (= (first r1) (first r2)) "the address strips reader positions")
        (is (= :address-conflict (get-in ix [:failure :reason])))
        (is (= (first r2) (get-in ix [:failure :id])))
        (is (nil? (ast-index/relations ix)))
        (is (= {:line 1} (meta (nth (get-in ix [:rows (first r1)]) 2)))
            "the held row is not replaced")
        (is (nil? (:failure (index-packets [[(first r1) [r1]]
                                            [(first r1) [r1]]])))
            "an identical row observed again merges")
        (is (= :duplicate-address
               (get-in (index-packets [[(first r1) [r1 r2]]]) [:failure :reason]))
            "within one packet, the metadata-distinct pair is refused too")
        (testing "a difference nested inside metadata"
          (let [n1 (literal-row (with-meta 'x {:doc (with-meta 'd {:line 1})}))
                n2 (literal-row (with-meta 'x {:doc (with-meta 'd {:line 2})}))]
            (is (= (first n1) (first n2)))
            (is (= :address-conflict
                   (get-in (index-packets [[(first n1) [n1]] [(first n2) [n2]]])
                           [:failure :reason])))))))
    (testing "a well-formed stream stays available"
      (let [ix (index-packets [good (literal-packet 1) good])]
        (is (nil? (:failure ix)))
        (is (= 2 (count (:ast (ast-index/relations ix)))))
        (is (= 3 (:programs (ast-index/status ix))))))))


(deftest a-malformed-packet-in-the-session-is-reported-and-evaluation-continues
  (let [{:keys [writer observer]} (small-medium 4)
        _ (stream/append! writer "garbage")
        state (assoc-in (repl/create-state) [:ast-indexer :observer] observer)
        [state' text] (repl/eval-input state "(+ 1 2)")
        [state'' text'] (repl/eval-input state' "(+ 2 3)")
        [_ result] (repl/eval-input (first (repl/eval-input state'' "(reset)"))
                                    "(+ 1 2)")]
    (is (= (str "3\n" (refused-warning "shape")) text))
    (is (= (str "5\n" (refused-warning "shape")) text')
        "every round warns until (reset)")
    (is (= "3" result))
    (is (= :shape (get-in (repl/repl-state state') [:ast-index :failure :reason])))
    (is (nil? (relations state')))))


(deftest a-refused-packet-names-its-cause-in-the-warning
  (let [{:keys [writer observer]} (small-medium 4)
        [root rows] (literal-packet 1)
        _ (stream/append! writer [root (conj rows (first rows))])
        state (assoc-in (repl/create-state) [:ast-indexer :observer] observer)
        [_ text] (repl/eval-input state "(+ 1 2)")]
    (is (= (str "3\n" (refused-warning "duplicate-address")) text))))


(deftest healthy-rounds-carry-no-warning-on-every-vm
  (doseq [vm-type (keys repl/vm-constructors)]
    (let [[_ texts] (evaluate (repl/create-state {:vm-type vm-type})
                              ["(+ 1 2)" "(+ 2 3)"])]
      (is (= ["3" "5"] texts) (str vm-type)))))


(deftest the-lost-warning-is-identical-on-every-vm
  (doseq [vm-type (keys repl/vm-constructors)]
    (let [{:keys [writer observer]} (small-medium 1)
          _ (doseq [v [1 2]] (stream/append! writer (literal-packet v)))
          state (assoc-in (repl/create-state {:vm-type vm-type})
                          [:ast-indexer :observer] observer)
          [_ text] (repl/eval-input state "(+ 1 2)")]
      (is (= (str "3\n" lost-warning) text) (str vm-type)))))


(deftest both-indexers-unhealthy-warn-code-index-first
  (let [code (small-medium 1)
        ast (small-medium 1)
        _ (doseq [m [code ast]
                  v [1 2]]
            (stream/append! (:writer m) (literal-packet v)))
        state (-> (repl/create-state)
                  (assoc-in [:indexer :observer] (:observer code))
                  (assoc-in [:ast-indexer :observer] (:observer ast)))
        [_ text] (repl/eval-input state "(+ 1 2)")
        [head code-warning ast-warning & more] (str/split-lines text)]
    (is (= "3" head))
    (is (str/starts-with? code-warning "Warning: the code index lost"))
    (is (= lost-warning ast-warning))
    (is (nil? more))))
