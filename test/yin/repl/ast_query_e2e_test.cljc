(ns yin.repl.ast-query-e2e-test
  "End-to-end acceptance tests for the REPL AST indexer and query bridge
   ($ast and $occ) across all four supported VM models (ast-walker, semantic,
   stack, register)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.ast-index :as ast-index]
            [yin.vm :as vm]
            [yin.vm.macro :as macro]))


(def ^:private vm-types
  (keys repl/vm-constructors))


(defn- evaluate
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(defn- q-line
  "A `dao.space.query/q` call on `query`, with `more` argument source
   appended."
  [query & more]
  (str "(dao.space.query/q (quote " (pr-str query) ")"
       (apply str (map #(str " " %) more))
       ")"))


(def ^:private require-line
  "(require (quote dao.space.query))")


(defn- stream-values
  "Every value currently on a stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- lossy-observer
  "An observer that reports a gap on its first read."
  []
  (let [writer (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key 1}))
        descriptor (:dao.stream/descriptor (stream/descriptor writer))
        lossy (observer/attach
                (ring/make-attacher {(:dao.stream/identity descriptor)
                                     writer})
                descriptor)]
    (doseq [v [1 2]]
      (stream/append! writer (macro/ast->packet {:type :literal, :value v})))
    lossy))


(def ^:private lost-warning
  "Warning: the AST index lost a program batch; $ast and $occ are unavailable until (reset)")


;; =============================================================================
;; 2. $ / $ast / $occ join via :yin/address
;; =============================================================================

(deftest join-dollar-ast-and-occ-via-yin-address
  ;; Requirement 2: A $ / $ast / $occ join (e.g. a :yin/name from $ joined to
  ;; the $ast row of the same node address via :yin/address).
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [join-q '[:find ?name ?path
                     :in $ $ast $occ ?name
                     :where
                     [?e :yin/name ?name]
                     [?e :yin/address ?addr]
                     [$ast ?addr :variable ?name]
                     [$occ ?root ?path ?addr]]
            [_ [_ _ res]]
            (evaluate (repl/create-state {:vm-type vm-type})
                      [require-line
                       "(defn inc [i] (+ i 1))"
                       (q-line join-q "(quote i)")])]
        (is (= (pr-str '#{[i [[3 1] 3 [3 0]]]}) res)
            "the variable i in $ joins to $ast and $occ via its :yin/address")))))


;; =============================================================================
;; 3. Identical code under two different roots
;; =============================================================================

(deftest identical-code-under-different-roots-shares-ast-rows-with-distinct-occurrences
  ;; Requirement 3: Identical code evaluated under two different roots: shared
  ;; $ast rows, distinct $occ occurrences.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [root-paths-q '[:find ?path
                           :in $ast $occ ?op ?root
                           :where
                           [$ast ?node :variable ?op]
                           [$occ ?root ?path ?node]]
            occ-count-q '[:find (count ?path) .
                          :with ?root
                          :in $ast $occ ?op
                          :where
                          [$ast ?node :variable ?op]
                          [$occ ?root ?path ?node]]
            ast-count-q '[:find (count ?node) .
                          :in $ast ?op
                          :where
                          [$ast ?node :variable ?op]]
            [state _]
            (evaluate (repl/create-state {:vm-type vm-type})
                      [require-line
                       "(+ 1 2)"
                       "(* (+ 1 2) 3)"])
            packets (stream-values (:row-stream state))
            inner-root (first (nth packets 1))
            outer-root (first (nth packets 2))
            [_ [inner-res outer-res occ-count ast-count]]
            (evaluate state
                      [(q-line root-paths-q "(quote +)"
                               (str "(quote " (pr-str inner-root) ")"))
                       (q-line root-paths-q "(quote +)"
                               (str "(quote " (pr-str outer-root) ")"))
                       (q-line occ-count-q "(quote +)")
                       (q-line ast-count-q "(quote +)")])
            rel (ast-index/relations (:ast-indexer state))
            plus-nodes (set (keep (fn [[id tag n]]
                                    (when (and (= :variable tag) (= '+ n)) id))
                                  (:ast rel)))
            plus-places (set (keep (fn [[r p n]]
                                     (when (contains? plus-nodes n) [r p]))
                                   (:occ rel)))]
        (is (not= inner-root outer-root) "two different programs have distinct roots")
        (is (= 1 (count plus-nodes))
            "the shared + node is one $ast row")
        (is (= "1" ast-count)
            "the shared + node appears exactly once in $ast")
        (is (= #{[inner-root [2]] [outer-root [[3 0] 2]]} plus-places)
            "$occ holds exactly the + node's place under each captured root")
        (is (= "#{[[2]]}" inner-res)
            "under the inner root, + sits only at [2]")
        (is (= "#{[[[3 0] 2]]}" outer-res)
            "under the outer root, + sits only at [[3 0] 2]")
        (is (= "2" occ-count)
            "the query sees exactly the two root-scoped places of +")))))


;; =============================================================================
;; 4. Re-evaluating identical code
;; =============================================================================

(deftest re-evaluating-identical-code-adds-no-ast-rows-and-no-occ-tuples
  ;; Requirement 4: Re-evaluating identical code adds no $ast rows and no $occ tuples.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[s1 _] (evaluate (repl/create-state {:vm-type vm-type})
                             ["(+ 1 2)"])
            rel1 (ast-index/relations (:ast-indexer s1))
            status1 (repl/repl-state s1)
            [s2 texts2] (evaluate s1 ["(+ 1 2)"])
            rel2 (ast-index/relations (:ast-indexer s2))
            status2 (repl/repl-state s2)
            [s3 texts3] (evaluate s2 ["(+ 1 2)"])
            rel3 (ast-index/relations (:ast-indexer s3))
            status3 (repl/repl-state s3)]
        (is (= ["3"] texts2))
        (is (= ["3"] texts3))
        (is (= #{[:variable '+] [:literal 1] [:literal 2]}
               (set (keep (fn [[_ tag v]]
                            (when (#{:variable :literal} tag) [tag v]))
                          (:ast rel1))))
            "the first evaluation populates $ast with the program's rows")
        (is (seq (:occ rel1))
            "the first evaluation populates $occ")
        (is (= (set (map first (:ast rel1)))
               (set (map #(nth % 2) (:occ rel1))))
            "every $ast row has an occurrence, and every occurrence a row")
        (is (= 1 (count (set (map first (:occ rel1)))))
            "the first evaluation's occurrences sit under its one root")
        (is (= rel1 rel2 rel3)
            "repeated evaluation of identical code produces identical AST relations")
        (is (= (count (:ast rel1)) (count (:ast rel2)) (count (:ast rel3)))
            "no $ast rows are added")
        (is (= (count (:occ rel1)) (count (:occ rel2)) (count (:occ rel3)))
            "no $occ tuples are added")
        (is (= (get-in status1 [:ast-index :rows])
               (get-in status2 [:ast-index :rows])
               (get-in status3 [:ast-index :rows])))
        (is (= (get-in status1 [:ast-index :occurrences])
               (get-in status2 [:ast-index :occurrences])
               (get-in status3 [:ast-index :occurrences])))
        (is (= 1 (get-in status1 [:ast-index :programs])))
        (is (= 2 (get-in status2 [:ast-index :programs])))
        (is (= 3 (get-in status3 [:ast-index :programs]))
            "program counter advances while rows and occurrences remain deduplicated")))))


;; =============================================================================
;; 5. (reset) and (vm ...) lifecycle
;; =============================================================================

(deftest reset-and-vm-selection-clear-ast-relations-fresh-eval-repopulates
  ;; Requirement 5: (reset) and (vm ...) clear the AST relations; a fresh evaluation repopulates them.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [names-q '[:find ?name :in $ast :where [$ast ?id :variable ?name]]
            [s-init _] (evaluate (repl/create-state {:vm-type vm-type})
                                 [require-line
                                  "(+ 1 2)"])
            [s-before [before]] (evaluate s-init [(q-line names-q)])
            [s-reset [reset-msg]] (evaluate s-before ["(reset)"])
            [_ [_ after]] (evaluate s-reset [require-line (q-line names-q)])
            [s-vm [switch-msg]] (evaluate s-before ["(vm :ast-walker)"])
            [_ [_ after-vm]] (evaluate s-vm [require-line (q-line names-q)])
            [_ [_ _ repop]] (evaluate s-reset [require-line "(+ 1 2)" (q-line names-q)])]
        (is (str/includes? before "[+]") "+ is present in $ast before reset")
        (is (= {:ast [] :occ #{}} (ast-index/relations (:ast-indexer s-reset)))
            "reset clears the AST relations in state")
        (is (str/includes? reset-msg "reset"))
        (is (not (str/includes? after "[+]")) "+ is absent from $ast after reset")
        (is (= {:ast [] :occ #{}} (ast-index/relations (:ast-indexer s-vm)))
            "vm selection clears the AST relations in state")
        (is (str/starts-with? switch-msg "Switched to"))
        (is (not (str/includes? after-vm "[+]")) "+ is absent from $ast after vm switch")
        (is (str/includes? repop "[+]") "fresh evaluation repopulates the AST relations")))))


;; =============================================================================
;; 6. Lost AST indexer
;; =============================================================================

(deftest lost-ast-indexer-refuses-ast-queries-while-evaluation-and-datoms-continue
  ;; Requirement 6: A lost AST indexer (forced reader gap, as ast_index_test does)
  ;; refuses $ast queries with index-unavailable while evaluation and $-only
  ;; queries continue, and the round shows the AST warning line.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state _] (evaluate (repl/create-state {:vm-type vm-type})
                                [require-line])
            lossy (assoc-in state [:ast-indexer :observer] (lossy-observer))
            [_ [round-text ast-res occ-res datom-res]]
            (evaluate lossy
                      ["(+ 1 2)"
                       (q-line '[:find ?name :in $ast :where [$ast ?id :variable ?name]])
                       (q-line '[:find ?p :in $occ :where [$occ ?r ?p ?n]])
                       (q-line '[:find ?v . :where [?e :yin/value 1] [?e :yin/value ?v]])])]
        (is (= (str "3\n" lost-warning) round-text)
            "evaluation completes and the round includes the lost warning")
        (is (str/starts-with? ast-res "Error: FFI call failed: $ast and $occ are unavailable")
            ast-res)
        (is (str/includes? ast-res "(:yin.repl.query/index-unavailable)")
            ast-res)
        (is (str/starts-with? occ-res "Error: FFI call failed: $ast and $occ are unavailable")
            occ-res)
        (is (str/includes? occ-res "(:yin.repl.query/index-unavailable)")
            occ-res)
        (is (= (str "1\n" lost-warning) datom-res)
            "a $-only query still succeeds and includes the round's warning")))))


;; =============================================================================
;; 1. Occurrence rules as portable data through q
;; =============================================================================

(def ^:private free-names-q
  "The free variables of one root, chosen by address."
  '[:find [?name ...]
    :in $ast $occ % ?root
    :where
    [$occ ?root ?path ?v]
    [$ast ?v :variable ?name]
    (not (occ-bound? ?root ?path ?name))])


(def ^:private round-free-names-q
  "The free variables of one round's root, chosen by the round-join."
  '[:find [?name ...]
    :in $ $ast $occ % ?round
    :where
    [?m :yin.repl/round ?round]
    [?m :yin.repl/root ?root]
    [$occ ?root ?path ?v]
    [$ast ?v :variable ?name]
    (not (occ-bound? ?root ?path ?name))])


(def ^:private round-root-q
  '[:find ?root . :in $ ?round
    :where [?m :yin.repl/round ?round] [?m :yin.repl/root ?root]])


(def ^:private rules-src
  "`yin.vm/occurrence-rules` passed as quoted data."
  (str "(quote " (pr-str vm/occurrence-rules) ")"))


(def ^:private user-rules-line
  "The rule set as the user types it, in their own `(def ...)` line."
  (str "(def occurrence-rules (quote "
       "[[(occ-bound? ?root ?path ?name) "
       "[$occ ?root ?lam-path ?lam] "
       "[(count ?lam-path) ?n] "
       "[(count ?path) ?m] "
       "[(< ?n ?m)] "
       "[(subvec ?path 0 ?n) ?lam-path] "
       "[$ast ?lam :lambda ?params _] "
       "[(identity ?params) [?name ...]]]]))"))


(defn- quoted
  [x]
  (str "(quote " (pr-str x) ")"))


(defn- session-roots
  "Evaluate `lines` after the require; answer the state and the root of
   each line's program, in round order (the require's first)."
  [vm-type lines]
  (let [[state _] (evaluate (repl/create-state {:vm-type vm-type})
                            (into [require-line] lines))]
    [state (mapv first (stream-values (:row-stream state)))]))


(defn- answers
  "The texts `lines` answer, each evaluated in the state the previous one
   left: a session's streams are live, so a state is used once."
  [state lines]
  (second (evaluate state lines)))


(defn- name-set
  "The set of names a `[?name ...]` answer prints (the shell prints a
   vector's symbols quoted, `['+ 'y]`), or the text itself when it is not
   such an answer."
  [text]
  (if (str/starts-with? text "[")
    (set (map symbol (re-seq #"[^\s\[\]']+" text)))
    text))


(deftest occurrence-rules-as-data-answer-free-names-through-q
  ;; §4 acceptance 4: the §2 query, with the rule set passed as quoted
  ;; data, on real input lines.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[s1 [_ open-root]] (session-roots vm-type ["((fn [x] (+ x y)) 1)"])
            [s2 [_ mixed-root]] (session-roots vm-type ["((fn [x] x) x)"])
            [open] (answers s1 [(q-line free-names-q rules-src
                                        (quoted open-root))])
            [mixed] (answers s2 [(q-line free-names-q rules-src
                                         (quoted mixed-root))])]
        (is (= #{'+ 'y} (name-set open))
            "+ and y are free; the parameter x is bound")
        (is (= #{'x} (name-set mixed))
            "the operand x is free although the body x is bound")))))


(deftest occurrence-rules-classify-each-root-by-its-own-binder
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [_ root-a root-b]] (session-roots vm-type
                                                     ["(fn [x] x)" "(fn [y] x)"])
            [a b] (answers state
                           [(q-line free-names-q rules-src (quoted root-a))
                            (q-line free-names-q rules-src (quoted root-b))])]
        (is (not= root-a root-b))
        (is (= #{} (name-set a)) "A's x is bound by A's own lambda")
        (is (= #{'x} (name-set b))
            "B's x stays free; A's binder at the same path never classifies it")))))


(deftest the-round-join-selects-the-same-root-as-the-address
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [_ _ root-b]] (session-roots vm-type
                                                ["(fn [x] x)" "(fn [y] x)"])
            [selected by-address by-round other-round]
            (answers state
                     [(q-line round-root-q "3")
                      (q-line free-names-q rules-src (quoted root-b))
                      (q-line round-free-names-q rules-src "3")
                      (q-line round-free-names-q rules-src "2")])]
        (is (= (pr-str root-b) selected)
            "round 3 is the (fn [y] x) program")
        (is (= #{'x} (name-set by-address)))
        (is (= by-address by-round)
            "the round-join form answers what the address form answers")
        (is (= #{} (name-set other-round))
            "and round 2 names the other root, whose x is bound")))))


(deftest a-user-defined-rule-set-gives-the-same-answer
  ;; The user's own (def ...) line: %, ... and _ survive the reader.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [_ defined open-root mixed-root]]
            (session-roots vm-type [user-rules-line
                                    "((fn [x] (+ x y)) 1)"
                                    "((fn [x] x) x)"])
            [open-def mixed-def open-data mixed-data]
            (answers state
                     [(q-line free-names-q "occurrence-rules" (quoted open-root))
                      (q-line free-names-q "occurrence-rules" (quoted mixed-root))
                      (q-line free-names-q rules-src (quoted open-root))
                      (q-line free-names-q rules-src (quoted mixed-root))])]
        (is (some? defined) "the def line is a program too")
        (is (= #{'+ 'y} (name-set open-def)))
        (is (= #{'x} (name-set mixed-def)))
        (is (= open-data open-def) "the defined rules answer what the data answers")
        (is (= mixed-data mixed-def))))))


;; =============================================================================
;; 7. Query forms, caller input arity, and row limit
;; =============================================================================

(deftest vector-and-map-forms-arity-errors-and-result-limits
  ;; Requirement 7: Both query vector and map forms; caller input arity errors;
  ;; a result over the row limit.
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ _ vec-res map-res arity-few arity-many]]
            (evaluate (repl/create-state {:vm-type vm-type})
                      [require-line
                       "(+ 1 2)"
                       (q-line '[:find ?name :in $ast :where [$ast ?id :variable ?name]])
                       (q-line '{:find [?name] :in [$ast] :where [[$ast ?id :variable ?name]]})
                       (q-line '[:find ?t . :in $ast ?n :where [$ast ?id ?t ?n]])
                       (q-line '[:find ?t . :in $ast ?n :where [$ast ?id ?t ?n]] "1" "2")])
            [_ [_ _ limit-res]]
            (evaluate (repl/create-state {:vm-type vm-type})
                      [require-line
                       (str "(+ " (str/join " " (range 1100)) ")")
                       (q-line '[:find ?v :in $ast :where [$ast ?id :literal ?v]])])]
        (is (= vec-res map-res)
            "vector and map query forms produce identical answers over $ast")
        (is (str/includes? vec-res "[+]")
            "the variable + is returned in the answer")
        (is (str/includes? arity-few "(:yin.repl.query/query-failed)")
            arity-few)
        (is (str/includes? arity-few "query input arity must match :in, 1 :in inputs and an optional options map expected, got 0 arguments")
            arity-few)
        (is (str/includes? arity-many "(:yin.repl.query/query-failed)")
            arity-many)
        (is (str/includes? arity-many "query input arity must match :in, 1 :in inputs and an optional options map expected, got 2 arguments")
            arity-many)
        (is (str/includes? limit-res "(:yin.repl.query/result-limit)")
            limit-res)
        (is (str/includes? limit-res (str "over the limit of " repl/query-row-limit))
            limit-res)))))
