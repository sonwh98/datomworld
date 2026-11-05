(ns yang.latte-oracle.h3-3-a-verify
  "H3-3 fixture-family A-verification (PREPARATION round; not part of the
   oracle suite and not loaded by yang.latte-oracle-test).

   Runs the proposed H3-3 fixtures for the 20 admitted-but-unexercised
   kernel arities against the ORIGINAL pinned upstream latte-kernel
   (git sha 86fca0d1, the deps.edn :test alias) on host Clojure -- the
   harness's A leg (kernel-a equivalent) -- and checks the exact outcomes
   predicted in h3_3_fixture_family.md. No B/C leg, no harness state, no
   evidence files are touched. The unparser fixtures run against an
   isolated registry atom, replicating the harness's :unparser-install
   A-leg handling; the readable-term- fixtures call the private upstream
   var through the (var ...) form the corpus :call will use, because a
   direct qualified call does not compile outside its own namespace.

   Seat run (from the repository root):

     clj -M:test -e \"(do (load-file \\\"test/yang/latte_oracle/h3_3_a_verify.clj\\\") (yang.latte-oracle.h3-3-a-verify/-main))\"

   Exit code 0 means every prediction in the design document holds on A;
   each FAIL line names the fixture/case whose prediction must be revised
   before harness integration. Expected values are taken from the pinned
   upstream source and, where they exist, its own tests
   (test/latte_kernel/*_test.cljc at the same sha); see the verification
   ledger in h3_3_fixture_family.md."
  (:require [latte-kernel.defenv :as defenv]
            [latte-kernel.nbe :as nbe]
            [latte-kernel.norm :as norm]
            [latte-kernel.presyntax :as parse]
            [latte-kernel.syntax :as stx]
            [latte-kernel.unparser :as unp]))

(def ^:private failures (atom []))
(def ^:private total (atom 0))

(defn- check
  "Record one exact-equality prediction. `actual` is the A outcome."
  [label expected actual]
  (swap! total inc)
  (if (= expected actual)
    (println "PASS" label)
    (do (swap! failures conj label)
        (println "FAIL" label)
        (println "  expected:" (pr-str expected))
        (println "  actual:  " (pr-str actual)))))

(defn- check-thrown
  "Record one thrown-rejection prediction: ex-info message and data."
  [label f expected-message expected-data]
  (swap! total inc)
  (try
    (let [value (f)]
      (swap! failures conj label)
      (println "FAIL" label)
      (println "  expected: thrown" (pr-str expected-message) (pr-str expected-data))
      (println "  actual:   returned" (pr-str value)))
    (catch clojure.lang.ExceptionInfo e
      (if (= [expected-message expected-data] [(ex-message e) (ex-data e)])
        (println "PASS" label)
        (do (swap! failures conj label)
            (println "FAIL" label)
            (println "  expected: thrown" (pr-str expected-message) (pr-str expected-data))
            (println "  actual:   thrown" (pr-str (ex-message e)) (pr-str (ex-data e))))))))

(defn- a-run
  "The harness A leg for an ordinary fixture: evaluate the fixture :call
   with `env` bound to upstream empty-env extended by `definitions`
   (each a ->Definition form), exactly as kernel-fixture-call does.
   The environment is built as a form, not embedded as a value, so
   Definition records never appear as eval literals."
  ([call] (a-run [] call))
  ([definitions call]
   (let [env-form (reduce (fn [e d] (list 'latte-kernel.defenv/register-definition e d))
                          'latte-kernel.defenv/empty-env definitions)]
     (eval (list 'let ['env env-form] call)))))

;; ---------------------------------------------------------------------------
;; defenv predicates
;; ---------------------------------------------------------------------------

(defn- run-defenv
  []
  (check "axiom?/1 on an Axiom record" true
         (a-run '(latte-kernel.defenv/axiom? (latte-kernel.defenv/->Axiom (quote a) [] 0 (quote T)))))
  (check "axiom?/1 on a Definition record" false
         (a-run '(latte-kernel.defenv/axiom? (latte-kernel.defenv/->Definition (quote d) [] 0 (quote body) #{} (quote T) {}))))
  (check "axiom?/1 on a plain map" false
         (a-run '(latte-kernel.defenv/axiom? {})))
  ;; The ambient (empty-env) 1-arity: the name is registered nowhere and
  ;; resolves in no namespace visible to the A leg, so fetch falls through
  ;; to its [:ko ...] result and the predicate is false.
  (check "registered-definition?/1 ambient, unregistered name" false
         (a-run '(latte-kernel.defenv/registered-definition? (quote gate-no-such-definition))))
  (check-thrown "registered-definition?/1 ambient, non-symbol name"
                #(a-run '(latte-kernel.defenv/registered-definition? 5))
                "Cannot fetch definition (please report)" {:dname 5}))

;; ---------------------------------------------------------------------------
;; nbe
;; ---------------------------------------------------------------------------

(defn- run-nbe
  []
  (check "evaluation/1 on a redex" 'b
         (a-run '(latte-kernel.nbe/evaluation (quote [(λ [a ✳] a) b]))))
  (check "evaluation/1 on a variable" 'a
         (a-run '(latte-kernel.nbe/evaluation (quote a))))
  (check-thrown "evaluation/1 on a missing reference"
                #(a-run '(latte-kernel.nbe/evaluation (quote (gate-missing))))
                "No such definition" {:term '(gate-missing) :def-name 'gate-missing})
  (check "nbe/instantiate-def/3 full application"
         '[[(λ [x ✳] (λ [y ✳] [x y])) a] b]
         (a-run '(latte-kernel.nbe/instantiate-def (quote [[x ✳] [y ✳]]) (quote [x y]) (quote [a b]))))
  (check-thrown "nbe/instantiate-def/3 with too many arguments"
                #(a-run '(latte-kernel.nbe/instantiate-def (quote [[x ✳]]) (quote x) (quote [a b])))
                "Not enough parameters (please report)" {:args '[a b]}))

;; ---------------------------------------------------------------------------
;; norm: beta family
;; ---------------------------------------------------------------------------

(defn- run-beta
  []
  (check "redex?/1 on a redex" true
         (a-run '(latte-kernel.norm/redex? (quote [(λ [x ✳] x) y]))))
  (check "redex?/1 on a non-lambda application" false
         (a-run '(latte-kernel.norm/redex? (quote [x y]))))
  (check "redex?/1 on a variable" false
         (a-run '(latte-kernel.norm/redex? (quote x))))
  (check "beta-reduction/1 (upstream test-beta-reduction case)" '[y y]
         (a-run '(latte-kernel.norm/beta-reduction (quote [(λ [x ✳] [x x]) y]))))
  (check-thrown "beta-reduction/1 on a non-redex"
                #(a-run '(latte-kernel.norm/beta-reduction (quote [x y])))
                "Cannot beta-reduce. Not a redex" {:term '[x y]})
  (check "beta-step/1 on a redex" '[y 1]
         (a-run '(latte-kernel.norm/beta-step (quote [(λ [x ✳] x) y]))))
  (check "beta-step/2 with an initial count of 5" '[y 6]
         (a-run '(latte-kernel.norm/beta-step (quote [(λ [x ✳] x) y]) 5)))
  (check "beta-step/1 under a binder" '[(λ [x ✳] x) 1]
         (a-run '(latte-kernel.norm/beta-step (quote (λ [x ✳] [(λ [y ✳] y) x])))))
  (check "beta-step/1 on a variable (no redex)" '[x 0]
         (a-run '(latte-kernel.norm/beta-step (quote x))))
  (check "beta-step-args/2 in parallel" '[[y z] 1]
         (a-run '(latte-kernel.norm/beta-step-args (quote ([(λ [x ✳] x) y] z)) 0))))

;; ---------------------------------------------------------------------------
;; norm: instantiate-def
;; ---------------------------------------------------------------------------

(defn- run-norm-instantiate
  []
  (check "norm/instantiate-def/3 full (upstream test-instantiate-def case)"
         '[[(λ [t ✳] t) t1] [[t2 t3] (λ [t ✳] t)]]
         (a-run '(latte-kernel.norm/instantiate-def (quote [[x ✳] [y ✳] [z ✳]])
                                                    (quote [[x y] [z x]])
                                                    (quote ((λ [t ✳] t) t1 [t2 t3])))))
  (check "norm/instantiate-def/3 partial (remaining parameter re-abstracted)"
         '(λ [t' ✳] [[(λ [t ✳] t) t1] [[t2 t3] t']])
         (a-run '(latte-kernel.norm/instantiate-def (quote [[x ✳] [y ✳] [z ✳] [t ✳]])
                                                    (quote [[x y] [z t]])
                                                    (quote ((λ [t ✳] t) t1 [t2 t3])))))
  (check-thrown "norm/instantiate-def/3 with too many arguments"
                #(a-run '(latte-kernel.norm/instantiate-def (quote [[x ✳]]) (quote x) (quote [a b])))
                "Not enough parameters (please report)" {:args '(b)}))

;; ---------------------------------------------------------------------------
;; norm: delta family (environment threading)
;; ---------------------------------------------------------------------------

(def ^:private delta-def
  '(latte-kernel.defenv/->Definition (quote d) [] 0 (quote ✳) #{} (quote □) {}))

(defn- run-delta
  []
  ;; local? true with the definition registered locally (the composed
  ;; register-definition call is the corpus's entry-delta-local shape).
  (check "delta-step/4 local-only, local definition" '[✳ 1]
         (a-run '(latte-kernel.norm/delta-step
                   (latte-kernel.defenv/register-definition env (latte-kernel.defenv/->Definition (quote d) [] 0 (quote ✳) #{} (quote □) {}) true)
                   [] (quote (d)) true)))
  (check "delta-step/4 global lookup, global definition" '[✳ 1]
         (a-run [delta-def] '(latte-kernel.norm/delta-step env [] (quote (d)) false)))
  (check "delta-step-args/5 over a reducible and an irreducible argument"
         '[[✳ [(λ [x ✳] x) y]] 1]
         (a-run [delta-def] '(latte-kernel.norm/delta-step-args env [] (quote ((d) [(λ [x ✳] x) y])) false 0)))
  (check-thrown "delta-step/4 on a missing definition"
                #(a-run '(latte-kernel.norm/delta-step env [] (quote (gate-missing)) false))
                "No such definition" {:term '(gate-missing) :def-name 'gate-missing}))

;; ---------------------------------------------------------------------------
;; presyntax
;; ---------------------------------------------------------------------------

(defn- run-parse
  []
  (check "parse/1 (ambient empty environment)" '(λ [x ✳] x)
         (a-run '(latte-kernel.presyntax/parse (quote (lambda [x :type] x)))))
  (check-thrown "parse/1 on an empty compound term"
                #(a-run '(latte-kernel.presyntax/parse (quote ())))
                "Parse error" {:msg "Compound term is empty" :term '()})
  (check-thrown "parse/1 on a reserved symbol"
                #(a-run '(latte-kernel.presyntax/parse (quote lambda)))
                "Parse error" {:msg "Symbol is reserved" :term 'lambda}))

;; ---------------------------------------------------------------------------
;; syntax
;; ---------------------------------------------------------------------------

(defn- run-syntax
  []
  (check "binderify/3 with two bindings" '(λ [x ✳] (λ [y A] [x y]))
         (a-run '(latte-kernel.syntax/binderify (quote λ) (quote [[x ✳] [y A]]) (quote [x y]))))
  (check "binderify/3 with no bindings" 'B
         (a-run '(latte-kernel.syntax/binderify (quote Π) (quote []) (quote B))))
  ;; mk-fresh is a pure suffix search: no gensym, no supply, deterministic.
  (check "mk-fresh/2 skips forbidden suffixes" 'x''
         (a-run '(latte-kernel.syntax/mk-fresh (quote x) (quote #{x x'}))))
  (check "mk-fresh/2 with nothing forbidden" 'x
         (a-run '(latte-kernel.syntax/mk-fresh (quote x) (quote #{}))))
  (check "mk-fresh/3 from level 3 (upstream test-mk-fresh shape)" 'x-4
         (a-run '(latte-kernel.syntax/mk-fresh (quote x) 3 (quote #{x x' x'' x'''}))))
  (check "mk-fresh/3 from level 0 with the base forbidden" 'x'
         (a-run '(latte-kernel.syntax/mk-fresh (quote x) 0 (quote #{x}))))
  (check "readable-term/1 renames a binder colliding with a free variable"
         '[(λ [x' ✳] x') x]
         (a-run '(latte-kernel.syntax/readable-term (quote [(λ [x ✳] x) x]))))
  (check "readable-term/1 on a variable" 'x
         (a-run '(latte-kernel.syntax/readable-term (quote x))))
  ;; readable-term- is a private var: the corpus :call reaches it through
  ;; (var ...), which is also how this script calls it.
  (check "readable-term-/2 with a colliding free set" '(λ [x' ✳] x')
         (a-run '((var latte-kernel.syntax/readable-term-) (quote #{x}) (quote (λ [x ✳] x)))))
  (check "readable-term-/3 with an explicit bound map" '(λ [x' ✳] [x' y])
         (a-run '((var latte-kernel.syntax/readable-term-) (quote #{x}) (quote {}) (quote (λ [x ✳] [x y]))))))

;; ---------------------------------------------------------------------------
;; unparser (side-effecting): isolated registry atom, as in kernel-a's
;; :unparser-install mode. The outcome is observed through unparse, never
;; through the registry value itself (it holds host functions).
;; ---------------------------------------------------------------------------

(defn- run-unparser
  []
  (let [registry-var (requiring-resolve 'latte-kernel.unparser/+unparser-registry+)
        fresh-registry {:fundamental [] :standard [] :user [] :unparsers {}}]
    (with-redefs-fn {registry-var (atom fresh-registry)}
      (fn []
        (check "unregister-unparser!/1 removes the unparser (observed via unparse)"
               '[shown x]
               (a-run '(let [_ (latte-kernel.unparser/register-unparser! :gate (fn [t] (if (= t (quote x)) [(quote shown) true] [t false])))]
                         [(latte-kernel.unparser/unparse (quote x))
                          (do (latte-kernel.unparser/unregister-unparser! :gate)
                              (latte-kernel.unparser/unparse (quote x)))])))
        (check-thrown "unregister-unparser!/1 on an absent ident"
                      #(a-run '(latte-kernel.unparser/unregister-unparser! :absent))
                      "No such unparser." {:ident :absent})
        ;; Conservative upstream behavior (unparser.cljc:35): unregister
        ;; rebuilds :user from the :standard list. Pin the observable
        ;; registry shape, not just the dissoc.
        (unp/register-unparser! :standard :std (fn [t] [t false]))
        (unp/register-unparser! :user :usr (fn [t] [t false]))
        (unp/unregister-unparser! :usr)
        (let [registry @@registry-var]
          (check "unregister rebuilds :user from :standard (conservative upstream)"
                 {:fundamental [] :standard '[:std] :user '[:std] :idents #{:std}}
                 {:fundamental (:fundamental registry) :standard (:standard registry)
                  :user (:user registry) :idents (set (keys (:unparsers registry)))}))))))

(defn -main
  [& _]
  (run-defenv)
  (run-nbe)
  (run-beta)
  (run-norm-instantiate)
  (run-delta)
  (run-parse)
  (run-syntax)
  (run-unparser)
  (let [passed (- @total (count @failures))]
    (if (empty? @failures)
      (println (str "H3-3-A-VERIFY: PASS (" passed "/" @total ")"))
      (println (str "H3-3-A-VERIFY: FAIL (" passed "/" @total ") prediction(s): " (pr-str @failures)))))
  (flush)
  (System/exit (if (empty? @failures) 0 1)))
