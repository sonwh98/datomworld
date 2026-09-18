(ns yin.vm.macro-test
  "U16 Phase 1 acceptance (`docs/design/yin.vm.macro.md` §11): the
   row-native expander over canonical tree packets, its bounded body runner
   and prelude, tail recomputation, event rows, and the observer's staging
   and error draining."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.macro :as m]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Batch construction
;; =============================================================================

(defn- c
  "Compile a Clojure form to the Universal AST (tail marks are recomputed by
   the expander, so the frontend's are irrelevant here)."
  [form]
  (yang/compile form))


(defn- call
  "An application AST of `op` (a symbol) on operand ASTs: how a frontend
   writes a macro call, which is an ordinary application."
  [op & operands]
  {:type :application, :operator {:type :variable, :name op}, :operands (vec operands)})


(def ^:private grammar vm/semantic-bytecode-grammar)


(defn- def-paths
  "Every `(yin/def <literal-symbol> v)` occurrence path of a packet, in the
   §3.5 declaration order a producer writes: an application whose operator
   is a lambda visits its operands and then the lambda body; everything else
   visits child slots in grammar order."
  [[root rows]]
  (let [index (into {} (map (fn [r] [(first r) r])) rows)
        def? (fn [row]
               (and (= :application (nth row 1))
                    (= [:variable 'yin/def] (subvec (get index (nth row 2)) 1))
                    (= 2 (count (nth row 3)))
                    (let [n (get index (first (nth row 3)))]
                      (and (= :literal (nth n 1)) (symbol? (nth n 2))))))]
    (letfn [(walk [a path]
              (let [row (get index a)
                    kids (mapcat (fn [i [_ kind]]
                                   (let [pos (+ i 2)]
                                     (case kind
                                       :node [[pos (nth row pos)]]
                                       :nodes (map-indexed (fn [j x] [[pos j] x]) (nth row pos))
                                       nil)))
                                 (range) (get grammar (nth row 1)))]
                (concat (when (def? row) [path])
                        (if (and (= :application (nth row 1))
                                 (= :lambda (nth (get index (nth row 2)) 1)))
                          (concat (mapcat (fn [[coord x]] (walk x (conj path coord)))
                                          (rest kids))
                                  (walk (nth (get index (nth row 2)) 3) (conj path 2 3)))
                          (mapcat (fn [[coord x]] (walk x (conj path coord))) kids)))))]
      (vec (walk root [])))))


(defn- batch
  "A batch of ASTs. `opts`: `:run` (default 0), `:declare` a predicate on
   `[tree path]` (default: every definition whose value is a lambda — in these
   tests, `def` of a `fn` is a macro unless stated), and `:order` a function
   reordering the `[tree path]` occurrences before ordinals are assigned."
  ([asts] (batch asts {}))
  ([asts {:keys [run declare order], :or {run 0, order identity}}]
   (let [trees (mapv m/ast->packet asts)
         occs (order (vec (for [[j t] (map-indexed vector trees)
                                p (def-paths t)]
                            [j p])))
         lambda-at? (fn [[j p]]
                      (let [[root rows] (nth trees j)
                            index (into {} (map (fn [r] [(first r) r])) rows)
                            a (reduce (fn [a coord]
                                        (let [row (get index a)]
                                          (if (vector? coord)
                                            (nth (nth row (first coord)) (second coord))
                                            (nth row coord))))
                                      root p)
                            value (second (nth (get index a) 3))]
                        (= :lambda (nth (get index value) 1))))
         declared? (or declare lambda-at?)]
     [:yin.program/batch
      trees
      run
      (vec (for [[j p] occs :when (declared? [j p])] [:yin.macro/definition j p]))
      (vec (map-indexed (fn [h [j p]] [:yin.macro/harvest h j p]) occs))])))


(def ^:private token #uuid "00000000-0000-0000-0000-000000000016")


(defn- ctx
  ([] (ctx {}))
  ([opts] (m/make-ctx (merge {:token token, :source-medium :program-in} opts))))


(defn- seeded
  "A context whose store holds `defn` and the given `name -> fn form` macros."
  ([] (seeded {} {}))
  ([macros] (seeded macros {}))
  ([macros opts]
   (let [base (ctx opts)
         defs (for [[nm form] macros] (list 'def nm form))]
     (assoc base :store
            (m/seed-store base (cond-> [m/stdlib-forms]
                                 (seq defs) (conj (batch [(c (cons 'do defs))]))))))))


(defn- expected
  "The canonical packet of an AST as the expander emits it: tails recomputed."
  [ast]
  (m/mark-tail (m/ast->packet ast)))


(defn- same-tree?
  [a b]
  (and (= (first a) (first b)) (= (set (second a)) (set (second b)))))


(defn- tags
  [[_ rows]]
  (set (map second rows)))


(defn- macro-root
  "The lambda root the store holds for `nm`."
  [store nm]
  (first (get store nm)))


(defn- lambda-root
  [form]
  (first (m/ast->packet (c form))))


(def ^:private transformers
  {'ident '(fn [x] x),
   'wrap '(fn [x] (yin/application (yin/lambda (quote [_]) (yin/literal nil))
                                   (conj [] x))),
   'discard '(fn [x] (yin/literal nil)),
   'reorder '(fn [a b] (yin/sequence-body (conj (conj [] b) a))),
   'duplicate '(fn [x] (yin/sequence-body (conj (conj [] x) x))),
   'keep-first '(fn [a b] a),
   'keep-second '(fn [a b] b),
   'twice '(fn [x] (yin/application (yin/variable (quote +)) (conj (conj [] x) x))),
   'get-twice '(fn [] (yin/variable (quote twice))),
   'forever '(fn [] (yin/application (yin/variable (quote forever)) [])),
   'into-test '(fn [x] (yin/if x (yin/literal 1) (yin/literal 2))),
   'swap2 '(fn [a b] (yin/sequence-body (conj (conj [] b) a))),
   'fresh '(fn [] (yin/variable (yin/gensym-sym "g")))})


;; =============================================================================
;; Standard forms and harvest
;; =============================================================================

(deftest standard-defn-equals-direct-definition
  (let [cx (seeded)]
    (testing "one body form"
      (let [out (m/expand (batch [(call 'defn {:type :variable, :name 'f}
                                        (c '[x]) (c '(+ x 1)))])
                          cx)]
        (is (same-tree? (expected (c '(def f (fn [x] (+ x 1))))) out))))
    (testing "several body forms lower through the reference do"
      (let [out (m/expand (batch [(call 'defn {:type :variable, :name 'g}
                                        (c '[x]) (c '(print x)) (c '(+ x 1)))])
                          cx)]
        (is (same-tree? (expected (c '(def g (fn [x] (do (print x) (+ x 1))))))
                        out))))
    (testing "stdlib seeding leaves defn in the store and emits the name"
      (let [r (m/expand-batch m/stdlib-forms (ctx))]
        (is (= :ok (:status r)))
        (is (contains? (:store (:ctx r)) 'defn))
        (is (same-tree? (m/ast->packet (c ''defn)) (:tree r)))))))


(deftest definitions-in-unexecuted-and-disconnected-trees-are-harvested
  (testing "a declaration in a branch that never runs is active now"
    (let [out (m/expand (batch [(c '(do (if false (def m (fn [x] x)) nil)
                                        (m 5)))])
                        (ctx))]
      (is (same-tree? (expected (c '(do (if false 'm nil) 5))) out))))
  (testing "a disconnected tree admits a definition for the run tree"
    (let [out (m/expand (batch [(c '(m 5)) (c '(def m (fn [x] x)))]) (ctx))]
      (is (same-tree? (expected (c 5)) out)))))


(deftest harvest-ordinal-decides-last-wins
  (let [a '(fn [x] (yin/literal :a))
        b '(fn [x] (yin/literal :b))
        trees [(c '(m 0)) (c (list 'def 'm a)) (c (list 'def 'm b))]]
    (is (same-tree? (expected (c :b)) (m/expand (batch trees) (ctx)))
        "catalogue order is tree order here")
    (is (same-tree? (expected (c :a)) (m/expand (batch trees {:order (comp vec reverse)})
                                                (ctx)))
        "the same trees with reversed ordinals let the first tree win")
    (testing "a later plain definition removes the macro for the whole batch"
      (let [out (m/expand (batch [(c '(m 0)) (c (list 'def 'm a)) (c '(def m 1))])
                          (ctx))]
        (is (same-tree? (expected (c '(m 0))) out))))))


(deftest declared-definitions-are-replaced-and-never-reach-output
  (let [r (m/expand-batch (batch [(c '(do (def m (fn [x] x)) (m 7)))]) (ctx))]
    (is (= :ok (:status r)))
    (is (not (contains? (tags (:tree r)) :yin.macro/defined)))
    (is (same-tree? (expected (c '(do 'm 7))) (:tree r)))
    (is (= (lambda-root '(fn [x] x)) (macro-root (:store (:ctx r)) 'm))
        "the declaration survives in the next store")))


(deftest plain-redefinition-removes-a-macro
  (let [cx (:ctx (m/expand-batch (batch [(c '(def m (fn [x] x)))]) (ctx)))
        _ (is (contains? (:store cx) 'm))
        r (m/expand-batch (batch [(c '(do (def m 1) (m 2)))]) cx)]
    (is (same-tree? (expected (c '(do (def m 1) (m 2)))) (:tree r))
        "the removal governs the current batch")
    (is (not (contains? (:store (:ctx r)) 'm)) "and the next")))


;; =============================================================================
;; Stand-ins and post-harvest
;; =============================================================================

(deftest transformations-preserve-the-specified-catalogue-entry
  (let [cx (seeded transformers)
        a '(fn [x] (yin/literal :a))
        b '(fn [x] (yin/literal :b))
        next-m (fn [form]
                 (let [r (m/expand-batch (batch [(c form)]) cx)]
                   (is (= :ok (:status r)) (pr-str form))
                   (is (not (contains? (tags (:tree r)) :yin.macro/defined)))
                   (macro-root (:store (:ctx r)) 'm)))]
    (is (= (lambda-root a) (next-m (list 'ident (list 'def 'm a)))) "identity")
    (is (= (lambda-root a) (next-m (list 'wrap (list 'def 'm a)))) "wrap")
    (is (nil? (next-m (list 'discard (list 'def 'm a)))) "discard")
    (is (= (lambda-root a) (next-m (list 'duplicate (list 'def 'm a)))) "duplicate")
    (is (= (lambda-root a) (next-m (list 'reorder (list 'def 'm a) (list 'def 'm b))))
        "reorder: the moved first definition is now last")
    (is (= (lambda-root a) (next-m (list 'keep-first (list 'def 'm a) (list 'def 'm b))))
        "keep-first keeps the first body")
    (is (= (lambda-root b) (next-m (list 'keep-second (list 'def 'm a) (list 'def 'm b))))
        "keep-second keeps the second body")
    (testing "a discarded declaration is still active in its own batch"
      (let [out (m/expand (batch [(c (list 'do (list 'discard (list 'def 'm a)) '(m 0)))])
                          cx)]
        (is (same-tree? (expected (c '(do nil :a))) out))))))


(defn- standin-packet
  [nm k]
  (let [body [:yin.macro/defined nm k]
        a (jing/segment-key body)]
    [a [(into [a] body)]]))


(defn- fabricating-eval
  "A body runner that answers `fab` with a fabricated result and runs every
   other macro normally."
  [fab-root result]
  (fn [{:keys [macro-tree] :as req}]
    (if (= fab-root (first macro-tree))
      {:value result}
      (m/bounded-row-evaluator req))))


(deftest fabricated-standins-resolve-only-when-valid
  (let [a '(fn [x] (yin/literal :a))
        b '(fn [x] (yin/literal :b))
        fab-form '(fn [] (yin/literal :fab))
        fab-root (lambda-root fab-form)
        run (fn [result]
              (let [cx (seeded {'fab fab-form} {:eval (fabricating-eval fab-root result)})
                    r (m/expand-batch
                        (batch [(c (list 'do (list 'def 'm a) (list 'def 'm b) '(fab)))])
                        cx)]
                (is (= :ok (:status r)))
                (is (not (contains? (tags (:tree r)) :yin.macro/defined)))
                (macro-root (:store (:ctx r)) 'm)))]
    (is (= (lambda-root b) (run (standin-packet 'm 7))) "unknown k: no store effect")
    (is (= (lambda-root b) (run (standin-packet 'other 0))) "mismatched name: no effect")
    (is (= (lambda-root a) (run (standin-packet 'm 0)))
        "a valid stand-in moved after B makes A win at its final position")))


;; =============================================================================
;; Expansion order, shadowing, and recognition
;; =============================================================================

(deftest outermost-first-binders
  (let [cx (seeded transformers)
        out (m/expand (batch [(call 'defn {:type :variable, :name 'f}
                                    (c '[twice]) (c '(twice 1)))])
                      cx)]
    (is (same-tree? (expected (c '(def f (fn [twice] (twice 1))))) out)
        "defn establishes the parameter before its body is inspected")))


(deftest operator-rewritten-to-a-macro-name-is-reconsidered
  (let [r (m/expand-batch (batch [(c '((get-twice) 5))]) (seeded transformers))]
    (is (same-tree? (expected (c '(+ 5 5))) (:tree r)))
    (is (= 2 (count (second (:log r)))) "one event for the operator, one for the call")))


(deftest lexical-shadowing
  (let [out (m/expand (batch [(c '((fn [twice] (twice 1)) (twice 2)))])
                      (seeded transformers))]
    (is (same-tree? (expected (c '((fn [twice] (twice 1)) (+ 2 2)))) out)
        "a parameter shadows its body only, never the sibling operand")))


(deftest macro-names-in-operand-position-are-ordinary-variables
  (let [out (m/expand (batch [(c '(f twice))]) (seeded transformers))]
    (is (same-tree? (expected (c '(f twice))) out))))


;; =============================================================================
;; Guards and invalid data
;; =============================================================================

(defn- error-of
  [form cx]
  (let [r (m/expand-batch (batch [(c form)]) cx)]
    (is (= :error (:status r)))
    (is (nil? (:tree r)))
    (:error r)))


(deftest guards
  (testing "depth"
    (is (= {:kind :depth-guard, :depth 3, :path []}
           (error-of '(forever) (seeded transformers {:guards {:max-depth 3}})))))
  (testing "rows per expansion"
    (is (= {:kind :row-guard, :scope :expansion, :rows 3}
           (error-of '(twice 1)
                     (seeded transformers {:guards {:max-rows-per-expansion 2}})))))
  (testing "rows per batch"
    ;; (twice 1) admits 3 rows; its result adds (+ 1 1) and + for 5
    (is (= {:kind :row-guard, :scope :batch, :rows 5}
           (error-of '(twice 1)
                     (assoc-in (seeded transformers) [:guards :max-rows-per-batch] 4)))))
  (testing "fuel"
    (is (= :fuel-guard
           (:kind (error-of '(twice 1) (seeded transformers {:guards {:max-steps 3}}))))))
  (testing "arity"
    (is (= {:kind :arity, :macro (lambda-root (transformers 'twice)), :expected 1, :got 2}
           (error-of '(twice 1 2) (seeded transformers))))
    (is (= :arity (:kind (error-of '(reorder 1) (seeded transformers))))))
  (testing "suspension and closed effects"
    (let [parker {:type :lambda, :params [], :body {:type :vm/park}}
          streamer {:type :lambda, :params [], :body {:type :stream/make, :buffer 1}}
          base (ctx)
          cx (assoc base :store (m/seed-store base [(batch [{:type :application,
                                                               :operator {:type :variable, :name 'yin/def},
                                                               :operands [{:type :literal, :value 'p} parker]}])
                                                    (batch [{:type :application,
                                                               :operator {:type :variable, :name 'yin/def},
                                                               :operands [{:type :literal, :value 's} streamer]}])]))]
      (is (= :suspended (:kind (error-of '(p) cx))))
      (is (= {:kind :effect-guard, :tag :stream/make}
             (dissoc (error-of '(s) cx) :macro)))))
  (testing "a body that throws is a body error, never a throw"
    (is (= :body-error (:kind (error-of '(boom) (seeded {'boom '(fn [] (nope))})))))))


(defn- literal-row
  [v]
  (let [body [:literal v]] (into [(jing/segment-key body)] body)))


(deftest admission-rejects-invalid-input
  (let [host-row (literal-row (fn [] 1))
        marker-row (literal-row [:yin.macro/defined 'm 0])
        err (fn [b] (:error (m/expand-batch b (ctx))))]
    (is (= :host-value (:reason (err [:yin.program/batch [[(first host-row) [host-row]]] 0 [] []]))))
    (is (= :marker-in-payload
           (:reason (err [:yin.program/batch [[(first marker-row) [marker-row]]] 0 [] []]))))
    (is (= {:kind :malformed-input, :reason :batch-shape} (err [:yin.program/batch [] 0 [] []])))
    (let [[root rows] (m/ast->packet (c '(f 1)))
          lit (literal-row 9)]
      (is (= :address-mismatch
             (:reason (err [:yin.program/batch [[root (conj rows [(first lit) :literal 8])]]
                            0 [] []]))))
      (is (= :unreachable-row
             (:reason (err [:yin.program/batch [[root (conj rows lit)]] 0 [] []]))))
      (is (= :dangling-child
             (:reason (err [:yin.program/batch [[root (subvec rows 0 1)]] 0 [] []]))))
      (is (= :unknown-tag
             (:reason (err [:yin.program/batch
                            [(standin-packet 'm 0)] 0 [] []])))))
    (testing "trees that are each valid may not disagree on one address"
      (let [r1 (literal-row (with-meta 'x {:tag 1}))
            r2 (literal-row (with-meta 'x {:tag 2}))]
        (is (= (first r1) (first r2)) "the address ignores scalar metadata")
        (is (nil? (m/valid-tree? [(first r2) [r2]])))
        (is (= {:kind :malformed-input, :reason :address-conflict, :tree 1,
                :address (first r2)}
               (err [:yin.program/batch [[(first r1) [r1]] [(first r2) [r2]]] 0 [] []])))
        (is (= :ok (:status (m/expand-batch [:yin.program/batch
                                             [[(first r1) [r1]] [(first r1) [r1]]]
                                             0 [] []]
                                            (ctx))))
            "identical shared rows merge")))
    (testing "harvest catalogue"
      (let [t (m/ast->packet (c '(def m (fn [x] x))))]
        (is (= {:kind :malformed-input, :reason :harvest-catalogue, :ordinal nil,
                :tree 0, :path []}
               (err [:yin.program/batch [t] 0 [] []])) "missing")
        (is (= :harvest-catalogue
               (:reason (err [:yin.program/batch [t] 0 []
                              [[:yin.macro/harvest 0 0 []] [:yin.macro/harvest 1 0 []]]])))
            "duplicate")
        (is (= {:kind :malformed-input, :reason :harvest-catalogue, :ordinal 1}
               (err [:yin.program/batch [t] 0 [] [[:yin.macro/harvest 1 0 []]]]))
            "non-contiguous")
        (is (= :harvest-catalogue
               (:reason (err [:yin.program/batch [t] 0 [] [[:yin.macro/harvest 0 0 [3]]]])))
            "non-definition")
        (is (= {:kind :malformed-input, :reason :stray-macro-declaration, :tree 0, :path [3]}
               (err [:yin.program/batch [t] 0 [[:yin.macro/definition 0 [3]]]
                     [[:yin.macro/harvest 0 0 []]]])))
        (let [plain (m/ast->packet (c '(def m 1)))]
          (is (= :stray-macro-declaration
                 (:reason (err [:yin.program/batch [plain] 0 [[:yin.macro/definition 0 []]]
                                [[:yin.macro/harvest 0 0 []]]])))
              "a declaration on a non-lambda definition"))))))


(deftest invalid-output-is-an-error
  (let [fab-form '(fn [] (yin/literal :fab))
        fab-root (lambda-root fab-form)
        run (fn [result input]
              (let [cx (seeded {'fab fab-form} {:eval (fabricating-eval fab-root result)})]
                (:error (m/expand-batch (batch [(c input)]) cx))))
        host (literal-row (fn [] 1))
        marker (literal-row [:yin.macro/defined 'm 0])]
    (is (= {:kind :invalid-output, :macro fab-root, :path [], :reason :host-value}
           (run [(first host) [host]] '(fab))))
    (is (= :marker-in-payload (:reason (run [(first marker) [marker]] '(fab)))))
    (is (= :packet-shape (:reason (run 42 '(fab)))) "not a packet")
    (let [[root rows] (m/ast->packet (c '(f 1)))]
      (is (= :dangling-child (:reason (run [root (subvec rows 0 1)] '(fab))))))
    (testing "an address already holding a metadata-distinct body"
      (let [x1 (with-meta 'x {:tag 1})
            x2 (with-meta 'x {:tag 2})
            row (literal-row x2)]
        (is (= (first row) (first (literal-row x1))) "the address ignores scalar metadata")
        (is (= :address-conflict
               (:reason (run [(first row) [row]]
                             (list 'do (list 'quote x1) '(fab))))))))))


;; =============================================================================
;; Tail marks
;; =============================================================================

(defn- tail-of
  "The tail? slot of the application whose operator is the variable `f`."
  [[_ rows] f]
  (let [index (into {} (map (fn [r] [(first r) r])) rows)]
    (set (for [r rows
               :when (and (= :application (nth r 1))
                          (= [:variable f] (subvec (get index (nth r 2)) 1)))]
           (nth r 4)))))


(deftest tail-marks-are-recomputed-after-syntax-moves
  (let [cx (seeded transformers)]
    (testing "a tail application moved into a test is cleared"
      (let [in {:type :application,
                :operator {:type :variable, :name 'into-test},
                :operands [{:type :application, :operator {:type :variable, :name 'f},
                            :operands [], :tail? true}]}
            out (m/expand (batch [in]) cx)]
        (is (= #{false} (tail-of out 'f)))))
    (testing "an operand moved to the last sequence position is set"
      (let [out (m/expand (batch [(c '(swap2 (f 1) (g 2)))]) cx)]
        (is (= #{true} (tail-of out 'f)))
        (is (= #{false} (tail-of out 'g)))))))


;; =============================================================================
;; Determinism and events
;; =============================================================================

(deftest equal-input-and-context-yield-equal-trees-and-events
  (let [cx (seeded transformers)
        b (batch [(c '(do (fresh) (twice (fresh))))])
        r1 (m/expand-batch b cx)
        r2 (m/expand-batch b cx)]
    (is (= :ok (:status r1)))
    (is (= (:tree r1) (:tree r2)))
    (is (= (:log r1) (:log r2)))
    (is (= (:ctx r1) (:ctx r2)))))


(deftest attempts-share-outputs-but-never-events
  (let [cx (seeded transformers)
        r (m/expand-batch (batch [(c '(do (twice 1) (twice 1)))]) cx)
        [tag events produced] (:log r)]
    (is (= :yin.macro/log tag))
    (is (= 2 (count events)))
    (is (apply = (map #(nth % 9) events)) "one output root")
    (is (apply distinct? (map first events)) "two event addresses")
    (is (= 1 (count produced)) "produced trees deduplicate by root")
    (is (= [[token 0] [token 1]] (map #(nth % 2) events)) "attempt order")
    (testing "event shape"
      (let [[e-addr tag attempt t origin parent input call-path macro out error] (first events)]
        (is (= e-addr (jing/segment-key (subvec (first events) 1))))
        (is (= :yin.macro/expand tag))
        (is (= 0 t))
        (is (= [:source :program-in 0 0] origin))
        (is (nil? parent))
        (is (some? input))
        (is (vector? call-path))
        (is (= (lambda-root (transformers 'twice)) macro))
        (is (some? out))
        (is (nil? error))))
    (testing "a call recognized after an operator rewrite is a source occurrence"
      (let [r (m/expand-batch (batch [(c '((get-twice) 5))]) cx)
            [e1 e2] (second (:log r))]
        (is (= [:source :program-in 0 0] (nth e1 4)))
        (is (= [2] (nth e1 7)))
        (is (= [:source :program-in 0 0] (nth e2 4)))
        (is (nil? (nth e2 5)) "the operator expansion is not the call's parent")
        (is (= [] (nth e2 7)))))
    (testing "a nested event names its parent and no origin"
      (let [r (m/expand-batch (batch [(c '(ident (twice 1)))]) cx)
            [e1 e2] (second (:log r))]
        (is (nil? (nth e2 4)))
        (is (= (first e1) (nth e2 5)))
        (is (= (nth e1 9) (nth e2 6)) "the child's input root is the parent's output")
        (is (= [] (nth e2 7)))))
    (testing "an admission failure consumes an attempt and records its error"
      (let [r (m/expand-batch [:yin.program/batch [] 0 [] []] cx)
            [e] (second (:log r))]
        (is (= [token 0] (nth e 2)))
        (is (= [:source :program-in 0 nil] (nth e 4)))
        (is (= [nil nil nil nil nil] (subvec e 5 10)))
        (is (= {:kind :malformed-input, :reason :batch-shape} (nth e 10)))
        (is (= 1 (:attempt (:ctx r))))
        (is (= 1 (:t (:ctx r))))))))


(deftest gensyms-are-attempt-scoped
  (let [cx (seeded transformers)
        [_ rows] (m/expand (batch [(c '(do (fresh) (fresh)))]) cx)
        names (set (keep #(when (= :variable (nth % 1)) (nth % 2)) rows))]
    (is (contains? names (symbol (str "g__" token "_0_0"))))
    (is (contains? names (symbol (str "g__" token "_1_0"))))))


;; =============================================================================
;; Observer: staging, retries, draining
;; =============================================================================

(defn- scripted-writer
  "A writer answering the queued outcomes, then `ok`; it records every
   accepted value."
  [outcomes]
  (let [accepted (atom [])
        queue (atom outcomes)]
    {:accepted accepted,
     :writer (reify stream/IDaoStreamWriter
               (append! [_ v]
                 (let [o (or (first @queue) :dao.stream/ok)]
                   (swap! queue rest)
                   (when (= :dao.stream/ok o) (swap! accepted conj v))
                   {:dao.stream/outcome o})))}))


(defn- session
  [cx out log]
  (let [{:keys [writer observer]} (tu/make-attachment 8)]
    {:program writer,
     :session {:observer observer, :consumer (m/make-expander cx out log)}}))


(defn- throws-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (ex-data e))))


(deftest staged-retry-reuses-attempt-identity-and-gensyms
  (let [cx (seeded transformers)
        out (scripted-writer [:dao.stream/full])
        log (scripted-writer [])
        {:keys [program session]} (session cx (:writer out) (:writer log))
        _ (stream/append! program (batch [(c '(fresh))]))
        s1 (m/step session)
        staged (get-in s1 [:consumer :out-staged])
        attempts (get-in s1 [:consumer :ctx :attempt])]
    (is (some? staged) "full retains the exact payload")
    (is (= [] @(:accepted out)))
    (is (= 1 (count @(:accepted log))) "the log destination cleared independently")
    (is (not (m/ready? (:consumer s1))))
    (let [s2 (m/step s1)]
      (is (= [staged] @(:accepted out)) "the retry publishes the staged tree unchanged")
      (is (= attempts (get-in s2 [:consumer :ctx :attempt])) "no new attempt")
      (is (= 1 (count @(:accepted log))) "the cleared log is not republished")
      (is (= {:errors [], :forwarded 1} (second (m/drain-errors s2)))))))


(deftest per-destination-retry-after-full
  (let [cx (seeded transformers)
        out (scripted-writer [])
        log (scripted-writer [:dao.stream/full :dao.stream/full])
        {:keys [program session]} (session cx (:writer out) (:writer log))
        _ (stream/append! program (batch [(c '(twice 1))]))
        _ (stream/append! program (batch [(c '(twice 2))]))
        s1 (m/step session)]
    (is (= 1 (count @(:accepted out))) "out=ok publishes once")
    (is (nil? (get-in s1 [:consumer :out-staged])))
    (is (some? (get-in s1 [:consumer :log-staged])) "log=full retries only the log")
    (is (= 1 (get-in s1 [:consumer :ctx :t])) "no further input is read")
    (let [s2 (m/step s1)
          s3 (m/step s2)]
      (is (= 2 (count @(:accepted out))))
      (is (= 2 (count @(:accepted log))))
      (is (= 2 (get-in s3 [:consumer :ctx :t])))
      (is (= {:errors [], :forwarded 2} (second (m/drain-errors s3)))))))


(deftest expansion-error-advances-the-cursor
  (let [cx (seeded transformers)
        out (scripted-writer [])
        log (scripted-writer [])
        {:keys [program session]} (session cx (:writer out) (:writer log))
        _ (stream/append! program (batch [(c '(twice 1 2))]))
        _ (stream/append! program (batch [(c '(twice 3))]))
        s (m/step session)
        [s' summary] (m/drain-errors s)]
    (is (= [:arity] (map :kind (:errors summary))))
    (is (= 1 (:forwarded summary)))
    (is (= 1 (count @(:accepted out))))
    (is (= 2 (count @(:accepted log))) "the failure's log still flushes")
    (is (= {:errors [], :forwarded 0} (second (m/drain-errors s'))) "drain resets")))


(deftest forwarder-defects-do-not-silently-advance-input
  (testing "a defect in load carries the cursor before the batch"
    (let [defect (fn [_] (throw (ex-info "runner defect" {})))
          cx (seeded transformers {:eval defect})
          {:keys [program session]} (session cx (:writer (scripted-writer [])) nil)
          _ (stream/append! program (batch [(c '(twice 1))]))
          data (throws-data #(m/step session))]
      (is (= (get-in session [:observer :cursor])
             (get-in data [:session :observer :cursor])))
      (is (= 0 (get-in data [:session :consumer :ctx :t])))))
  (testing "a closed destination throws with both slots preserved"
    (let [cx (seeded transformers)
          out (scripted-writer [:dao.stream/closed])
          log (scripted-writer [])
          {:keys [program session]} (session cx (:writer out) (:writer log))
          _ (stream/append! program (batch [(c '(twice 1))]))
          data (throws-data #(m/step session))
          carried (:session data)]
      (is (= :dao.stream/closed (:dao.stream/outcome data)))
      (is (some? (get-in carried [:consumer :out-staged])))
      (is (some? (get-in carried [:consumer :log-staged])))
      (is (not= (get-in session [:observer :cursor])
                (get-in carried [:observer :cursor]))
          "the accepted batch is not re-read")
      (let [s (m/step carried)]
        (is (= 1 (count @(:accepted out))))
        (is (= 1 (count @(:accepted log))))
        (is (= 1 (get-in s [:consumer :ctx :t])) "recovery expands nothing again"))))
  (testing "a log failure after a published program carries the cleared slot"
    (let [cx (seeded transformers)
          out (scripted-writer [])
          log (scripted-writer [:dao.stream/transport-error])
          {:keys [program session]} (session cx (:writer out) (:writer log))
          _ (stream/append! program (batch [(c '(twice 1))]))
          carried (:session (throws-data #(m/step session)))]
      (is (nil? (get-in carried [:consumer :out-staged])))
      (is (some? (get-in carried [:consumer :log-staged])))
      (m/step carried)
      (is (= 1 (count @(:accepted out))) "the program is not republished"))))


;; =============================================================================
;; Prelude and invoke
;; =============================================================================

(deftest prelude-and-invoke
  (let [cx (ctx)
        lam (fn [form] (m/ast->packet (c form)))
        v (m/ast->packet (c 'x))]
    (is (= :variable (m/invoke (lam '(fn [t] (yin/tag t))) [v] cx))
        "invoke returns the body's value, unvalidated")
    (is (same-tree? (m/ast->packet (c ''x))
                    (m/invoke (lam '(fn [t] (yin/literal (yin/name-of t)))) [v] cx)))
    (is (same-tree? (m/ast->packet (call 'f {:type :variable, :name 'x}))
                    (m/invoke (lam '(fn [app] (yin/slot app 2)))
                              [(m/ast->packet (c '((f x) 1)))] cx))
        "yin/slot at a node position returns the child packet")
    (is (= 'x (m/invoke (lam '(fn [t] (yin/slot t 2))) [v] cx)))
    (is (= :arity (:kind (throws-data #(m/invoke (lam '(fn [a] a)) [] cx)))))
    (is (= :body-error (:kind (throws-data #(m/invoke (lam '(fn [t] (yin/slot t 1))) [v] cx))))
        "positions 0 and 1 are invalid")
    (is (same-tree? (m/ast->packet (c '(fn [a b] (+ a b))))
                    (m/invoke (lam '(fn [p b] (yin/make-lambda p b)))
                              [(m/ast->packet (c '[a b])) (m/ast->packet (c '(+ a b)))]
                              cx)))))


;; =============================================================================
;; Occurrences and the evaluator boundary
;; =============================================================================

(deftest shared-definition-rows-remain-distinct-occurrences
  (let [a '(fn [x] (yin/literal :a))
        r (m/expand-batch (batch [(c (list 'do
                                           (list 'def 'm a)
                                           (list 'discard (list 'def 'm a))
                                           '(m 0)))])
                          (seeded transformers))]
    (is (= :ok (:status r)))
    (is (same-tree? (expected (c '(do 'm nil :a))) (:tree r))
        "one shared row at two paths is two occurrences with two stand-ins")
    (is (= (lambda-root a) (macro-root (:store (:ctx r)) 'm))
        "the kept occurrence installs; the discarded one only leaves")))


(deftest expanded-output-runs-on-an-evaluator-that-knows-no-macros
  (let [program {:type :application,
                 :operator {:type :lambda, :params '[_], :body (c '(inc1 41))},
                 :operands [(call 'defn {:type :variable, :name 'inc1}
                                  (c '[x]) (c '(+ x 1)))]}
        tree (m/expand (batch [program]) (seeded))
        vm (vm/run (ast-walker/vm-load-rows (tu/create-vm) (m/packet->row-set tree)))]
    (is (not (contains? (tags tree) :yin.macro/defined)))
    (is (= 42 (vm/value vm)))))

