(ns yin.repl.query-test
  "`(require 'dao.space.query)` activates `dao.space.query/q` over the
   session's code index (docs/design/yin.repl.dao.space-index.md, \"Query
   surface\"; yin.repl.query).  Every shell-level case runs on every VM the
   shell supports.  Input lines stay readable by every host's
   non-evaluating reader, so quoted forms are spelled `(quote ...)`."
  (:require [dao.test-slow :as slow] [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.observer :as observer]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.ast-index :as ast-index]
            [yin.repl.index :as repl.index]
            [yin.repl.query :as query]
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


(def ^:private unresolved
  "Error: Unable to resolve symbol: dao.space.query/q in this context")


(def ^:private names-query
  '[:find ?n :where [?e :yin/type :variable] [?e :yin/name ?n]])


;; =============================================================================
;; Activation
;; =============================================================================

(deftest q-is-unresolved-until-required-and-bare-q-is-never-bound
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [before required after bare]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [(q-line names-query)
                       require-line
                       (q-line '[:find ?e :where [?e :no/such 1]])
                       "(q (quote [:find ?e :where [?e :no/such 1]]))"])]
        (is (= unresolved before))
        (is (= "'dao.space.query" required))
        (is (= "#{}" after) "the require activated the qualified export")
        (is (= "Error: Unable to resolve symbol: q in this context" bare)
            "no import rule binds the bare name")))))


(deftest reset-removes-the-binding-and-a-new-require-restores-it
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ active _ removed _ again]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       (q-line '[:find ?e :where [?e :no/such 1]])
                       "(reset)"
                       (q-line '[:find ?e :where [?e :no/such 1]])
                       require-line
                       (q-line '[:find ?e :where [?e :no/such 1]])])]
        (is (= "#{}" active))
        (is (= unresolved removed))
        (is (= "#{}" again))))))


(deftest vm-selection-removes-the-binding
  (let [[_ [_ switched removed]]
        (evaluate (repl.frontends/create-state)
                  [require-line "(vm :stack)"
                   (q-line '[:find ?e :where [?e :no/such 1]])])]
    (is (str/starts-with? switched "Switched to"))
    (is (= unresolved removed))))


;; =============================================================================
;; Answers
;; =============================================================================

(deftest a-def-is-queryable-by-its-name-and-facts
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ _ named names kind absent]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       "(def answer (fn [x] (+ x 42)))"
                       (q-line '[:find ?e . :in $ ?n :where [?e :yin/value ?n]]
                               "(quote answer)")
                       (q-line names-query)
                       (q-line '[:find ?t . :in $ ?n
                                 :where [?e :yin/value ?n] [?e :yin/type ?t]]
                               "(quote answer)")
                       (q-line '[:find ?e :where [?e :no/such 1]])])]
        (is (re-matches #"\d+" named)
            "the defined name is an indexed literal, by default in the current view")
        (is (every? #(str/includes? names %) ["[x]" "[+]" "[yin/def]"])
            "the definition's body facts are indexed")
        (is (= ":literal" kind))
        (is (= "#{}" absent) "a query with no match is an empty relation")))))


(deftest the-history-view-reaches-session-and-round-through-m
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [_ _ provenance current]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       "(def answer 42)"
                       (q-line '[:find ?s ?r
                                 :where [?e :yin/root true ?t ?m]
                                 [?m :yin.repl/session ?s ?t1 ?m1]
                                 [?m :yin.repl/round ?r ?t2 ?m2]]
                               "{:view :history}")
                       (q-line '[:find ?s ?r
                                 :where [?e :yin/root true ?t ?m]
                                 [?m :yin.repl/session ?s ?t1 ?m1]
                                 [?m :yin.repl/round ?r ?t2 ?m2]])])
            token (:shell-token state)]
        (is (= 3 (count (re-seq #"\[\"[^\"]+\" \d+\]" provenance))))
        (is (every? #(str/includes? provenance (pr-str [token %])) [1 2 3])
            "every program, the calling one included, reaches its provenance")
        (is (= "#{}" current)
            "the current view binds [e a v]; its extra slots match nothing")))))


(def ^:private bump-lines
  "A session with three call sites of `bump`, then the require."
  ["(def bump (fn [i] (+ i 1)))" "(bump 41)" "(bump (bump 1))" require-line])


(def ^:private calls-of-f
  '[:find (count ?app) :in ?f :where [?app :yin/operator ?op] [?op :yin/name ?f]])


(deftest scalar-in-inputs-bind-beside-the-implicit-index
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ texts]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      (into bump-lines
                            [(q-line calls-of-f "(quote bump)")
                             (q-line calls-of-f "(quote bump)" "{:view :current}")
                             (q-line '[:find (count ?app) :in $ ?f
                                       :where [?app :yin/operator ?op]
                                       [?op :yin/name ?f]]
                                     "(quote bump)")
                             (q-line '[:find (count ?app) :in ?f
                                       :where [?app :yin/operator ?op ?t ?m]
                                       [?op :yin/name ?f ?t1 ?m1]]
                                     "(quote bump)" "{:view :history}")
                             (q-line calls-of-f)
                             (q-line calls-of-f "(quote bump)" "(quote +)")]))
            [bare with-options explicit history too-few too-many]
            (drop (count bump-lines) texts)]
        (is (= "#{[3]}" bare) "the index is implicit; ?f is the first input")
        (is (= "#{[3]}" with-options) "an options map follows the inputs")
        (is (= "#{[3]}" explicit) "a declared $ is the index")
        (is (= "#{[3]}" history) "a history view takes inputs too")
        (doseq [text [too-few too-many]]
          (is (str/includes? text "(:yin.repl.query/query-failed)") text)
          (is (str/includes? text "input arity") text))))))


(deftest a-map-input-is-an-input-and-one-more-map-is-options
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [by-key '[:find ?v . :in ?m :where [(get ?m :k) ?v]]
            [_ [_ bare with-options bad-view too-many]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       (q-line by-key "{:k 1}")
                       (q-line by-key "{:k 1}" "{:view :current}")
                       (q-line by-key "{:k 1}" "{:view :sideways}")
                       (q-line by-key "{:k 1}" "{:view :current}" "{:k 2}")])]
        (is (= "1" bare) "the declared :in arity makes the map an input")
        (is (= "1" with-options) "the map after the inputs is the options")
        (is (str/includes? bad-view "(:yin.repl.query/invalid-input)")
            "the options map is validated as before")
        (is (str/includes? too-many "(:yin.repl.query/query-failed)"))
        (is (str/includes? too-many "input arity"))))))


(deftest a-bare-symbol-in-in-or-find-refuses-the-query
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ texts]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      (into bump-lines
                            [(q-line '[:find (count ?app) :in f
                                       :where [?app :yin/operator ?op]
                                       [?op :yin/name f]]
                                     "(quote bump)")
                             (q-line '[:find op :where [?app :yin/operator ?op]])]))]
        (doseq [text (drop (count bump-lines) texts)]
          (is (str/includes? text "(:yin.repl.query/query-failed)") text)
          (is (str/includes? text "bare symbol") text))))))


(deftest host-functions-cannot-enter-a-query
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ unknown fns]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       (q-line '[:find ?x :in % :where (r ?x)]
                               "(quote [[(r ?x) [(no-such-fn 1) ?x]]])")
                       (q-line '[:find ?e :where [?e :no/such 1]]
                               "{:fns {}}")])]
        (is (str/includes? unknown "(:yin.repl.query/query-failed)") unknown)
        (is (str/includes? unknown "Unknown query fn") unknown)
        (is (str/includes? fns "(:yin.repl.query/invalid-input)") fns)))))


(deftest a-bare-symbol-in-a-pattern-is-the-symbol-constant
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ texts]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      (conj bump-lines
                            (q-line '[:find (count ?app)
                                      :where [?app :yin/operator ?op]
                                      [?op :yin/name bump]])))
            text (peek texts)]
        (is (= "#{[3]}" text) "bump names the three call sites of bump")))))


(deftest a-collection-binding-walks-the-operands-vector
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ _ literals]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      ["(defn inc [i] (+ i 1))"
                       require-line
                       (q-line '[:find ?v
                                 :where [?app :yin/operator ?op]
                                 [?op :yin/name yin/def]
                                 [?app :yin/operands ?ops]
                                 [(identity ?ops) [?arg ...]]
                                 [?arg :yin/type :literal]
                                 [?arg :yin/value ?v]])])]
        (is (= "#{[inc]}" literals)
            "the literal operands of yin/def, one binding per operand")))))


(defn- stream-values
  "Every value currently on a stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(deftest a-failed-round-keeps-its-answers-answered
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [_ _ failed answered]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       "(def answer 42)"
                       (str "((fn [a] (nope a)) "
                            (q-line '[:find ?v :where [?e :yin/value ?v]])
                            ")")
                       (q-line '[:find ?v . :where [?e :yin/value 42]
                                 [?e :yin/value ?v]])])]
        (is (str/starts-with? failed "Error: Unable to resolve symbol: nope"))
        (is (= "42" answered)
            "the rolled-back VM's first call does not take the failed round's response")
        (is (= (mapv apply2/request-id
                     (stream-values (get-in state [:query-pair :call-in])))
               (mapv apply2/response-id
                     (stream-values (get-in state [:query-pair :call-out]))))
            "the interpreter answered each request exactly once")))))


(defn- spin-line
  "Define `spin`: `n` calls of `q`, then `end` evaluated in tail position."
  [end]
  (str "(def spin (fn [n] (if (= n 0) " end
       " ((fn [_] (spin (- n 1))) "
       (q-line '[:find ?v . :where [?e :yin/value 42] [?e :yin/value ?v]])
       "))))"))


(deftest ^:slow a-failed-round-of-more-calls-than-the-pair-holds-leaves-no-gap
  (slow/guard "a-failed-round-of-more-calls-than-the-pair-holds-leaves-no-gap"
              (fn []
                (doseq [vm-type vm-types]
                  (testing (str vm-type)
                    (let [n (+ repl/query-pair-capacity 6)
                          [_ [_ _ _ failed answered]]
                          (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                    [require-line
                                     "(def answer 42)"
                                     (spin-line "(nope)")
                                     (str "(spin " n ")")
                                     (q-line '[:find ?v . :where [?e :yin/value 42]
                                               [?e :yin/value ?v]])])]
                      (is (str/starts-with? failed "Error: Unable to resolve symbol: nope"))
                      (is (= "42" answered)
                          "the rolled-back VM reads past the abandoned answers, not into a gap")))))))


(deftest ^:slow a-program-calling-q-without-end-is-stopped-at-the-drive-budget
  (slow/guard "a-program-calling-q-without-end-is-stopped-at-the-drive-budget"
              (fn []
                (doseq [vm-type vm-types]
                  (testing (str vm-type)
                    (let [[state [_ _ _ stopped answered]]
                          (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                    [require-line
                                     "(def answer 42)"
                                     (spin-line ":done")
                                     (str "(spin " (inc (* 2 repl/query-drive-budget)) ")")
                                     (q-line '[:find ?v . :where [?e :yin/value 42]
                                               [?e :yin/value ?v]])])
                          requests (stream-values (get-in state [:query-pair :call-in]))]
                      (is (= (str "Error: " repl/query-call-limit-text) stopped))
                      (is (= "42" answered) "the session answers the next call")
                      (is (= (count requests) (count (distinct (map apply2/request-id requests))))
                          "every call id is unique across the stopped run and the next")))))))


;; =============================================================================
;; Refusals
;; =============================================================================

(deftest an-unavailable-index-refuses-the-call
  (testing "a lost indexer"
    (doseq [vm-type vm-types]
      (testing (str vm-type)
        (let [writer (:dao.stream/handle
                       (ring/create! {:dao.stream/type ring/transport-type
                                      ring/capacity-key 1}))
              descriptor (:dao.stream/descriptor (stream/descriptor writer))
              lossy (observer/attach
                      (ring/make-attacher {(:dao.stream/identity descriptor)
                                           writer})
                      descriptor)
              _ (doseq [v [1 2]]
                  (stream/append! writer
                                  (macro/ast->packet {:type :literal
                                                      :value v})))
              [state _] (repl/eval-input (repl.frontends/create-state {:vm-type vm-type})
                                         require-line)
              [_ text] (repl/eval-input (assoc-in state [:indexer :observer]
                                                  lossy)
                                        (q-line names-query))]
          (is (str/starts-with? text "Error: FFI call failed: the code index is unavailable"))
          (is (str/includes? text "(:yin.repl.query/index-unavailable)"))))))
  (testing "a failed publication"
    (doseq [vm-type vm-types]
      (testing (str vm-type)
        (let [broken {:put-bytes-fn (fn [_address _bytes]
                                      (throw (ex-info "store refused" {})))
                      :get-bytes-fn (fn [_address not-found] not-found)}
              [_ [_ text]]
              (evaluate (repl.frontends/create-state {:vm-type vm-type
                                            :index-store broken})
                        [require-line (q-line names-query)])]
          (is (str/includes? text "(:yin.repl.query/index-unavailable)"))
          ;; a store refusing every write fails the round's first write:
          ;; the program's rows (yin.vm.linker.dht.md 5.1)
          (is (str/includes? text "materialize failed"))))))
  (testing "committed code that is not published"
    (let [[state _] (evaluate (repl.frontends/create-state) ["(+ 1 2)"])
          unpublished (assoc (:indexer state) :published 0)
          response (query/answer unpublished
                                 {:row-limit 10, :byte-limit 1000}
                                 (apply2/request 7 query/op [names-query]))]
      (is (false? (:published? (repl.index/status unpublished))))
      (is (nil? (:failure (repl.index/status unpublished))))
      (is (= :yin.repl.query/index-unavailable
             (:dao.stream.apply/code (apply2/response-error response))))))
  (testing "a published index its store can no longer answer"
    (let [[state _] (evaluate (repl.frontends/create-state) ["(+ 1 2)"])
          unreadable (assoc (:indexer state) :content-store
                            {:put-bytes-fn (fn [_address _bytes] nil)
                             :get-bytes-fn (fn [_address not-found]
                                             not-found)})
          error (apply2/response-error
                  (query/answer unreadable
                                {:row-limit 10, :byte-limit 1000}
                                (apply2/request 7 query/op [names-query])))]
      (is (true? (:published? (repl.index/status unreadable))))
      (is (= :yin.repl.query/index-unavailable (:dao.stream.apply/code error)))
      (is (str/includes? (:dao.stream.apply/message error)
                         "could not be read")))))


(deftest an-empty-session-is-an-empty-database
  (let [indexer (:indexer (repl.frontends/create-state))
        response (query/answer indexer
                               {:row-limit 10, :byte-limit 1000}
                               (apply2/request 1 query/op
                                               ['[:find ?e ?a ?v
                                                  :where [?e ?a ?v]]]))]
    (is (zero? (:transactions (repl.index/status indexer))))
    (is (= #{} (apply2/response-ok response)))))


(deftest ^:slow results-over-a-limit-refuse-naming-it
  (slow/guard "results-over-a-limit-refuse-naming-it"
              (fn []
                (testing "the shell's row limit, on every VM"
                  (doseq [vm-type vm-types]
                    (testing (str vm-type)
                      (let [[_ [_ _ text]]
                            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                      [require-line
                                       (str "(+ " (str/join " " (range 1100)) ")")
                                       (q-line '[:find ?v :where [?e :yin/value ?v]])])]
                        (is (str/includes? text "(:yin.repl.query/result-limit)"))
                        (is (str/includes? text (str "over the limit of " repl/query-row-limit)))))))
                (let [[state _] (evaluate (repl.frontends/create-state)
                                          ["(def answer \"a long string literal\")"])
                      ask (fn [limits query & inputs]
                            (apply2/response-error
                              (query/answer (:indexer state) limits
                                            (apply2/request 1 query/op
                                                            (into [query] inputs)))))
                      by-value '[:find ?v . :in $ ?v :where [?e :yin/value ?v]]]
                  (testing "the row limit is checked on the collected rows"
                    (let [error (ask {:row-limit 2, :byte-limit 100000}
                                     '[:find ?e ?a ?v :where [?e ?a ?v]])]
                      (is (= :yin.repl.query/result-limit (:dao.stream.apply/code error)))
                      (is (= {:rows 2} (:yin.repl.query/limit error)))))
                  (testing "the byte limit is checked on the encoded answer"
                    (let [error (ask {:row-limit 1000, :byte-limit 8}
                                     by-value "a long string literal")]
                      (is (= :yin.repl.query/result-limit (:dao.stream.apply/code error)))
                      (is (= {:bytes 8} (:yin.repl.query/limit error)))
                      (is (str/includes? (:dao.stream.apply/message error)
                                         "over the limit of 8"))))
                  (testing "an answer within both limits is returned"
                    (is (nil? (ask {:row-limit 1000, :byte-limit 100000}
                                   by-value "a long string literal"))))))))


(deftest unsupported-input-is-refused
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [_ host-fn view not-a-query]]
            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                      [require-line
                       (q-line '[:find ?e :in $ ?x :where [?e :yin/value ?x]]
                               "+")
                       (q-line '[:find ?e :where [?e :no/such 1]]
                               "{:view :sideways}")
                       "(dao.space.query/q 42)"])]
        (doseq [text [host-fn view not-a-query]]
          (is (str/starts-with? text "Error: FFI call failed: "))
          (is (str/includes? text "(:yin.repl.query/invalid-input)")))
        (is (str/includes? host-fn "portable Yin data only"))))))


;; =============================================================================
;; $ast and $occ
;; =============================================================================

(def ^:private ast-names
  '[:find ?name :in $ast :where [$ast ?id :variable ?name]])


(def ^:private inc-lines
  ["(defn inc [i] (+ i 1))" require-line])


(defn- evaluate-after-inc
  "The texts of `lines` evaluated after `inc-lines` on `vm-type`, and the
   final state."
  [vm-type lines]
  (let [[state texts] (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                (into inc-lines lines))]
    [state (vec (drop (count inc-lines) texts))]))


(deftest ast-and-occ-are-supplied-when-named-in-in
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state [names history map-form tag i-path joined sources-late
                    occ-count]]
            (evaluate-after-inc
              vm-type
              [(q-line ast-names)
               (q-line ast-names "{:view :history}")
               (q-line '{:find [?name] :in [$ast]
                         :where [[$ast ?id :variable ?name]]})
               (q-line '[:find ?tag . :in $ast ?n :where [$ast ?id ?tag ?n]]
                       "(quote inc)")
               (q-line '[:find ?path :in $occ $ast
                         :where [$ast ?node :variable i]
                         [$occ ?root ?path ?node]])
               (q-line '[:find ?path :in $ $ast $occ ?n
                         :where [?e :yin/type :variable] [?e :yin/name ?n]
                         [$ast ?node :variable ?n] [$occ ?root ?path ?node]]
                       "(quote i)")
               (q-line '[:find ?path :in ?n $occ $ast
                         :where [?e :yin/name ?n]
                         [$ast ?node :variable ?n] [$occ ?root ?path ?node]]
                       "(quote i)")
               (q-line '[:find (count ?path) . :with ?root :in $occ
                         :where [$occ ?root ?path ?node]])])
            occurrences (:occ (ast-index/relations (:ast-indexer state)))]
        (is (every? #(str/includes? names (pr-str [%])) '[yin/def + i])
            "the variable rows of (defn inc ...) are $ast rows")
        (is (= names history map-form)
            "$ast is the same relation under either view and either query form")
        (is (= ":literal" tag) "a caller input follows the named source")
        (is (= (str (count occurrences)) occ-count)
            "$occ holds every [root path node] the session observed, the
             asking program's own included")
        (is (= "#{[[[3 1] 3 [3 0]]]}" i-path)
            "the one place of i, joined through $ast")
        (is (= i-path joined) "$, $ast and $occ join in one query")
        (is (= i-path sources-late)
            "sources declared after a caller input are still the session's")))))


(deftest ast-sources-are-not-caller-inputs
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[_ [too-few too-many unnamed]]
            (evaluate-after-inc
              vm-type
              [(q-line '[:find ?tag . :in $ast ?n :where [$ast ?id ?tag ?n]])
               (q-line '[:find ?tag . :in $ast ?n :where [$ast ?id ?tag ?n]]
                       "(quote inc)" "(quote i)")
               (q-line '[:find ?name :where [$ast ?id :variable ?name]])])]
        (doseq [text [too-few too-many]]
          (is (str/includes? text "(:yin.repl.query/query-failed)") text)
          (is (str/includes? text "input arity") text))
        (is (= "#{}" unnamed)
            "a query without :in has only the implicit $; $ast binds nothing")))))


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


(deftest an-unavailable-ast-index-refuses-only-ast-queries
  (doseq [[label break] [["a lost AST indexer"
                          #(assoc-in % [:ast-indexer :observer]
                                     (lossy-observer))]
                         ["a failed AST indexer"
                          #(assoc-in % [:ast-indexer :failure]
                                     {:stage :packet, :reason :shape})]]
          vm-type vm-types]
    (testing (str label " " vm-type)
      (let [[state _] (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                inc-lines)
            [_ [ast occ datoms]]
            (evaluate (break state)
                      [(q-line ast-names)
                       (q-line '[:find ?p :in $occ :where [$occ ?r ?p ?n]])
                       (q-line '[:find ?e . :where [?e :yin/value inc]])])]
        (doseq [text [ast occ]]
          (is (str/starts-with? text "Error: FFI call failed: $ast and $occ are unavailable")
              text)
          (is (str/includes? text "(:yin.repl.query/index-unavailable)") text))
        (is (re-matches #"\d+\nWarning: the AST index .*" datoms)
            "a datom-only query still answers, carrying the round's warning")))))


(deftest ^:slow ast-results-over-a-limit-refuse-naming-it
  (slow/guard "ast-results-over-a-limit-refuse-naming-it"
              (fn []
                (testing "the shell's row limit, on every VM"
                  (doseq [vm-type vm-types]
                    (testing (str vm-type)
                      (let [[_ [_ _ text]]
                            (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                      [require-line
                                       (str "(+ " (str/join " " (range 1100)) ")")
                                       (q-line '[:find ?v :in $ast
                                                 :where [$ast ?id :literal ?v]])])]
                        (is (str/includes? text "(:yin.repl.query/result-limit)"))
                        (is (str/includes? text (str "over the limit of " repl/query-row-limit)))))))
                (let [[state _] (evaluate (repl.frontends/create-state) ["(defn inc [i] (+ i 1))"])
                      ask (fn [limits query]
                            (apply2/response-error
                              (query/answer (:indexer state) (:ast-indexer state) limits
                                            (apply2/request 1 query/op [query]))))
                      mixed '[:find ?n ?path :in $ $ast $occ
                              :where [?e :yin/name ?n] [$ast ?node :variable ?n]
                              [$occ ?root ?path ?node]]]
                  (is (= {:rows 2}
                         (:yin.repl.query/limit (ask {:row-limit 2, :byte-limit 100000} mixed)))
                      "a mixed-source result is bound by the row limit")
                  (is (= {:bytes 8}
                         (:yin.repl.query/limit (ask {:row-limit 1000, :byte-limit 8} mixed)))
                      "and by the byte limit")
                  (is (nil? (ask {:row-limit 1000, :byte-limit 100000} mixed)))))))


(deftest ast-sources-are-read-when-the-call-is-answered
  (let [[state _] (evaluate (repl.frontends/create-state) ["(defn inc [i] (+ i 1))"])
        ask (fn [ast-indexer]
              (query/answer (:indexer state) ast-indexer
                            {:row-limit 1000, :byte-limit 100000}
                            (apply2/request 1 query/op [ast-names])))
        [later _] (evaluate state ["(def x y)"])]
    (is (contains? (apply2/response-ok (ask (:ast-indexer later))) '[y])
        "the answer reflects the AST indexer it is handed")
    (is (not (contains? (apply2/response-ok (ask (:ast-indexer state))) '[y])))
    (is (= :yin.repl.query/index-unavailable
           (:dao.stream.apply/code
             (apply2/response-error
               (query/answer (:indexer state)
                             {:row-limit 1000, :byte-limit 100000}
                             (apply2/request 1 query/op [ast-names])))))
        "a bridge handed no AST indexer refuses $ast")))


;; =============================================================================
;; The interpreter
;; =============================================================================

(deftest the-interpreter-answers-each-request-once
  (let [pair (query/make-pair 8)
        indexer (:indexer (repl.frontends/create-state))
        limits {:row-limit 10, :byte-limit 1000}
        responses (fn [pair]
                    (loop [cursor (:out-cursor pair)
                           acc []]
                      (let [r (stream/next (:call-out pair) cursor)]
                        (if (= :dao.stream/ok (:dao.stream/outcome r))
                          (recur (:dao.stream/cursor r)
                                 (conj acc (:dao.stream/value r)))
                          acc))))
        _ (apply2/put-request! (:call-in pair)
                               (apply2/request [:c 0] query/op [names-query]))
        _ (apply2/put-request! (:call-in pair)
                               (apply2/request [:c 1] :some/other-op []))
        served (query/serve {:pair pair, :indexer indexer, :limits limits,
                             :budget 8})
        again (query/serve {:pair (:pair served), :indexer indexer,
                            :limits limits, :budget 8})]
    (is (true? (:progress? served)))
    (is (false? (:progress? again)) "a consumed request is not answered twice")
    (is (= [[:c 0] [:c 1]] (mapv apply2/response-id (responses pair))))
    (is (= #{} (apply2/response-ok (first (responses pair)))))
    (is (= :dao.stream.apply/unknown-operation
           (:dao.stream.apply/code
             (apply2/response-error (second (responses pair))))))))
