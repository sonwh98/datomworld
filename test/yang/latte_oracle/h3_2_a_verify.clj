(ns yang.latte-oracle.h3-2-a-verify
  "H3-2 fixture-family A-verification (PREPARATION round; not part of the
   oracle suite and not loaded by yang.latte-oracle-test).

   Runs the proposed H3-2 proof fixtures against the ORIGINAL pinned
   upstream latte-kernel (git sha 86fca0d1, the deps.edn :test alias) on
   host Clojure -- the harness's A leg (kernel-a equivalent) -- and checks
   the exact outcomes predicted in h3_2_fixture_family.md. No B/C leg, no
   harness state, no evidence files are touched.

   Seat run (from the repository root, branch hardening3-h3-1):

     clj -M:test -e \"(do (load-file \\\"test/yang/latte_oracle/h3_2_a_verify.clj\\\") (yang.latte-oracle.h3-2-a-verify/-main))\"

   Exit code 0 means every prediction in the design document holds on A;
   each FAIL line names the fixture/case whose prediction must be revised
   before harness integration. Expected values are taken from the pinned
   upstream source (proof.cljc) and its own proof tests
   (test/latte_kernel/proof_test.cljc at the same sha); they are
   source-derived until this script has been run -- see the verification
   ledger in h3_2_fixture_family.md."
  (:require [latte-kernel.defenv :as defenv]
            [latte-kernel.presyntax :as parse]
            [latte-kernel.proof :as proof]))

(def ^:private failures (atom []))

(defn- check
  "Record one exact-equality prediction. `actual` is the A outcome."
  [label expected actual]
  (if (= expected actual)
    (println "PASS" label)
    (do (swap! failures conj label)
        (println "FAIL" label)
        (println "  expected:" (pr-str expected))
        (println "  actual:  " (pr-str actual)))))

(defn- check-thrown
  "Record one thrown-rejection prediction: ex-info message and data."
  [label f expected-message expected-data]
  (try
    (let [value (f)]
      (swap! failures conj label)
      (println "FAIL" label)
      (println "  expected: thrown" (pr-str expected-message) (pr-str expected-data))
      (println "  actual:   returned" (pr-str value)))
    (catch clojure.lang.ExceptionInfo e
      (check label [expected-message expected-data]
             [(ex-message e) (ex-data e)]))))

(defn- parsed
  "The parsed form of a presyntax expression, as upstream tests build it."
  ([expr] (parsed defenv/empty-env expr))
  ([def-env expr] (second (parse/parse-term def-env expr))))

(defn- fetched
  "The local Definition record fields a state assertion pins."
  [def-env dname]
  (let [[_ ddef] (defenv/fetch-definition def-env dname true)]
    (select-keys ddef [:name :params :arity :parsed-term :term-free-vars :type])))

;; ---------------------------------------------------------------------------
;; Shared upstream-canonical state (mirrors latte_kernel/proof_test.cljc):
;; declare A, B, f : (==> A B), x : A, threading [ctx var-deps] step by step.
;; ---------------------------------------------------------------------------

(def ^:private st-a (second (proof/elab-declare defenv/empty-env [] [] {} 'A '✳ {})))
(def ^:private st-b (second (proof/elab-declare defenv/empty-env (nth st-a 1) (nth st-a 2) {} 'B '✳ {})))
(def ^:private st-f (second (proof/elab-declare defenv/empty-env (nth st-b 1) (nth st-b 2) {} 'f (parsed '(==> A B)) {})))
(def ^:private st-x (second (proof/elab-declare defenv/empty-env (nth st-f 1) (nth st-f 2) {} 'x 'A {})))
(def ^:private ctx4 (nth st-x 1))
(def ^:private vdeps4 (nth st-x 2))

(def ^:private st-ha (second (proof/elab-have defenv/empty-env ctx4 vdeps4 {} '<a> (parsed '(==> A B)) 'f {})))
;; Parsed in st-ha's environment, where <a> is registered -- exactly as
;; upstream's test state6 does (parsing a reference requires its definition).
(def ^:private st-hb (second (proof/elab-have (nth st-ha 0) (nth st-ha 1) (nth st-ha 2) (nth st-ha 3)
                                              '<b> 'B (parsed (nth st-ha 0) '(<a> x)) {})))

(def ^:private expected-proof-type
  '(Π [A ✳] (Π [B ✳] (Π [f (Π [⇧ A] B)] (Π [x A] (Π [⇧ A] B))))))

(defn- run-f1
  "F1: check-proof over the assume/have/qed script (upstream test-proof)."
  []
  (check "F1 check-proof result"
         [:ok ['(<a>) expected-proof-type]]
         (proof/check-proof defenv/empty-env [] 'composition (parsed '(forall [A B :type] (==> (==> A B) (==> A A B))))
                            '[[:assume {:line 1}
                               [A :type B :type f (==> A B) x A]
                               [:have <a> (==> A B) f {:line 2}]
                               [:have <b> B (<a> x) {:line 3}]]
                              [:qed <a> {:line 4}]])))

(defn- run-f3-states
  "F3: the canonical declare/have/discharge chain, state by state."
  []
  (check "F3 ctx after declares"
         '([x A] [f (Π [⇧ A] B)] [B ✳] [A ✳]) ctx4)
  (check "F3 var-deps after declares"
         '([x #{}] [f #{}] [B #{}] [A #{}]) vdeps4)
  ;; Conservative upstream update-var-deps: the have name is added to EVERY
  ;; var-deps entry, not only to entries whose variable occurs in the type
  ;; (proof.cljc:109-119; the filtered version is commented out upstream).
  (check "F3 var-deps after <a> (conservative, all entries)"
         '[[x #{<a>}] [f #{<a>}] [B #{<a>}] [A #{<a>}]] (nth st-ha 2))
  (check "F3 def-uses after <a>" '{<a> #{}} (nth st-ha 3))
  (check "F3 <a> after have"
         {:name '<a> :params [] :arity 0 :parsed-term 'f
          :term-free-vars '#{f} :type '(Π [⇧ A] B)}
         (fetched (nth st-ha 0) '<a>))
  (check "F3 var-deps after <b> (conservative, all entries)"
         '[[x #{<a> <b>}] [f #{<a> <b>}] [B #{<a> <b>}] [A #{<a> <b>}]] (nth st-hb 2))
  (check "F3 def-uses after <b> (<b> references <a>)"
         '{<a> #{<b>} <b> #{}} (nth st-hb 3))
  (check "F3 <b> after have"
         {:name '<b> :params [] :arity 0 :parsed-term '[(<a>) x]
          :term-free-vars '#{x f} :type 'B}
         (fetched (nth st-hb 0) '<b>))
  (let [st7 (proof/elab-discharge (nth st-hb 0) (nth st-hb 1) (nth st-hb 2) (nth st-hb 3) 'x {})]
    (check "F3 ctx after discharge x"
           '([f (Π [⇧ A] B)] [B ✳] [A ✳]) (nth st7 1))
    (check "F3 <a> abstracted over x"
           {:name '<a> :params '[[x A]] :arity 1 :parsed-term 'f
            :term-free-vars '#{f} :type '(Π [⇧ A] B)}
           (fetched (nth st7 0) '<a>))
    (check "F3 <b> abstracted over x, local call rewritten"
           {:name '<b> :params '[[x A]] :arity 1 :parsed-term '[(<a> x) x]
            :term-free-vars '#{x f} :type 'B}
           (fetched (nth st7 0) '<b>))
    (let [st8 (proof/elab-discharge (nth st7 0) (nth st7 1) (nth st7 2) (nth st7 3) 'f {})
          st9 (proof/elab-discharge (nth st8 0) (nth st8 1) (nth st8 2) (nth st8 3) 'B {})
          st10 (proof/elab-discharge (nth st9 0) (nth st9 1) (nth st9 2) (nth st9 3) 'A {})]
      (check "F3 ctx empty after discharging x f B A" '() (nth st10 1))
      (check "F3 var-deps empty after discharging x f B A" '() (nth st10 2))
      (check "F3 <a> fully abstracted"
             {:name '<a> :params '[[A ✳] [B ✳] [f (Π [⇧ A] B)] [x A]] :arity 4
              :parsed-term 'f :term-free-vars '#{f} :type '(Π [⇧ A] B)}
             (fetched (nth st10 0) '<a>))
      (check "F3 <b> fully abstracted, local calls rewritten"
             {:name '<b> :params '[[A ✳] [B ✳] [f (Π [⇧ A] B)] [x A]] :arity 4
              :parsed-term '[(<a> A B f x) x] :term-free-vars '#{x f} :type 'B}
             (fetched (nth st10 0) '<b>))
      (check "F3 elab-qed on the fully discharged state"
             [:ok [(nth st10 0) '(<a>) expected-proof-type]]
             (proof/elab-qed (nth st10 0) (nth st10 1) '(<a>) {}))))
  (let [result (proof/elab-proof defenv/empty-env []
                                 '[[:declare A :type {}]
                                   [:declare B :type {}]
                                   [:declare f (==> A B) {}]
                                   [:declare x A {}]
                                   [:have <a> (==> A B) f {}]
                                   [:have <b> B (<a> x) {}]
                                   [:discharge x {}]
                                   [:discharge f {}]
                                   [:discharge B {}]
                                   [:discharge A {}]
                                   [:qed <a> {}]])
        [_ [def-env proof-term proof-type]] result]
    (check "F3 elab-proof end to end (status/term/type)"
           [:ok ['(<a>) expected-proof-type]]
           [(first result) [proof-term proof-type]])
    (check "F3 elab-proof end to end (<a> fully abstracted)"
           {:name '<a> :params '[[A ✳] [B ✳] [f (Π [⇧ A] B)] [x A]] :arity 4
            :parsed-term 'f :term-free-vars '#{f} :type '(Π [⇧ A] B)}
           (fetched def-env '<a>))
    (check "F3 elab-proof end to end (<b> fully abstracted)"
           {:name '<b> :params '[[A ✳] [B ✳] [f (Π [⇧ A] B)] [x A]] :arity 4
            :parsed-term '[(<a> A B f x) x] :term-free-vars '#{x f} :type 'B}
           (fetched def-env '<b>))))

(defn- run-f2
  "F2: inferred-type named have plus anonymous have, completed by qed."
  []
  (let [result (proof/elab-proof defenv/empty-env []
                                 '[[:declare A :type {}]
                                   [:declare x A {}]
                                   [:have <a> _ x {}]
                                   [:have _ A x {}]
                                   [:qed <a> {}]])
        [_ [def-env proof-term proof-type]] result]
    (check "F2 elab-proof status/term/type" [:ok ['(<a>) 'A]]
           [(first result) [proof-term proof-type]])
    (check "F2 only the named have is recorded (anonymous have registers nothing)"
           '#{<a>} (set (keys (second def-env))))
    (check "F2 inferred have records the synthesized type"
           {:name '<a> :params [] :arity 0 :parsed-term 'x
            :term-free-vars '#{x} :type 'A}
           (fetched def-env '<a>))))

(defn- run-helper-probes
  "Direct helper probes: diagnostics for the design's call-graph claims.
   These confer no H3-2 credit by themselves (see the design document)."
  []
  (check "probe update-var-deps adds the name to every entry (conservative)"
         '[[x #{<a>}] [f #{<a>}] [B #{<a>}] [A #{<a>}]]
         (proof/update-var-deps vdeps4 '<a> 'f))
  (check "probe ref-uses-in-term finds the referenced local"
         '#{<a>} (proof/ref-uses-in-term '[(<a>) x]))
  (check "probe ref-uses-in-term on a plain variable" '#{} (proof/ref-uses-in-term 'f))
  (check "probe gen-local-calls supplies the discharged variable"
         '[(<a> f) x] (proof/gen-local-calls '[(<a>) x] '#{<a>} 'f)))

(defn- run-refusals
  "R1-R3: source-backed refusal companions (expected A rejections)."
  []
  (check "R1 duplicate local definition"
         [:ko {:msg "Have step elaboration failed: local definition already registered"
               :have-name '<a> :meta {}}]
         (proof/elab-proof defenv/empty-env []
                           '[[:declare A :type {}]
                             [:declare x A {}]
                             [:have <a> A x {}]
                             [:have <a> A x {}]]))
  (let [result (proof/elab-proof defenv/empty-env [] '[[:declare y (==> A) {}]])]
    (check "R2a declare with an unparseable type expression"
           [:ko "Proof failed at declare step: cannot parse type expression." 'y]
           [(first result) (get (second result) :msg) (get (second result) :var)]))
  (check "R2b have with a mismatched declared type"
         [:ko {:msg "Have step elaboration failed: synthetized term type and expected type do not match"
               :have-name '<a> :expected-type '✳ :synthetized-type 'A :meta {}}]
         (proof/elab-proof defenv/empty-env []
                           '[[:declare A :type {}]
                             [:declare x A {}]
                             [:have <a> :type x {}]]))
  (check-thrown "R3a discharge of the wrong variable"
                #(proof/elab-proof defenv/empty-env []
                                   '[[:declare A :type {}]
                                     [:declare x A {}]
                                     [:discharge y {}]])
                "Incorrect discharge name." {:discharge-name 'y :var 'x :meta {}})
  (check-thrown "R3b discharge on an empty context"
                #(proof/elab-proof defenv/empty-env [] '[[:discharge x {}]])
                "Context is empty (please report)." {:discharge-name 'x :meta {}}))

(defn -main
  [& _]
  (run-f1)
  (run-f3-states)
  (run-f2)
  (run-helper-probes)
  (run-refusals)
  (if (empty? @failures)
    (println "H3-2-A-VERIFY: PASS (all predictions hold on pinned upstream A)")
    (println "H3-2-A-VERIFY: FAIL" (count @failures) "prediction(s):" (pr-str @failures)))
  (flush)
  (System/exit (if (empty? @failures) 0 1)))
