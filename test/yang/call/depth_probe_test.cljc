(ns yang.call.depth-probe-test
  "The UNLINKED DEPTH PROBE (mob ruling (a'), 2/2). Measures the maximum
   continuation depth D(n) of pure iteration and exception fixtures on the
   three vector VMs (:semantic :stack :register), unlinked, for all four
   admitted frontends (Clojure, Python, TypeScript, PHP).

   Contract (sol's (C) + astra's two corrections):
   - CONSTANT matrix: 4 frontends x 4 variants x 3 backends x 3 sizes = 144
     completed measurements; assert results, iteration/catch/finalizer
     counts, caller-handler restoration, positive step counts, and
     D(16)=D(64)=D(256) within each frontend/backend/variant.
   - RECURSIVE characterization (recorded, NEVER asserted for growth):
     named self/mutual calls under an outer catch at 8/32/128, success and
     deepest failure, all four frontends, three backends.
   - Correction (a): empty K is not completion. Use the engine's halt/block
     predicates, require the expected result, reject pending scheduler work.
   - Correction (b): pure means no parking, not no effects. Synchronous cell
     effects complete inside the engine's effect handling and are preserved.

   The evidence artifact is `test/yang/call/depth_probe.edn`, written by
   `write-evidence!`; results are labelled `:unlinked` and the linked-warm
   follow-up stays a named obligation."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.stream :as stream]
            [yang.call.conformance :as kit]
            [yang.clojure :as clj-comp]
            [yang.php.antlr.cst :as php-cst]
            [yang.php.antlr.lower :as php-lower]
            [yang.php.antlr.portable-harness :as php-portable]
            [yang.php.antlr.prelude :as php-prelude]
            [yang.python.antlr.lower :as py-lower]
            [yang.python.antlr.parser :as py-parser]
            [yang.python.antlr.prelude :as py-prelude]
            [yang.typescript.antlr.cst :as ts-cst]
            [yang.typescript.antlr.lower :as ts-lower]
            [yang.typescript.antlr.prelude :as ts-prelude]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.integer :as integer]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Composition: host modules and per-frontend bundled registries
;; =============================================================================

(def frontends [:clojure :python :typescript :php])
(def backends [:semantic :stack :register])
(def variants [:plain :try-finally :caught-failure :outer-catch])
(def sizes [16 64 256])


(def ^:private step-budget
  "The per-measurement instruction budget. Low enough that a non-terminating
   fixture (a prelude `%capture` or recursion-unwind bug) is detected rather
   than spinning forever, high enough for the heaviest admitted fixture to
   complete: the exception-heavy PHP `caught-failure`/`try-finally` fixtures
   do one throw/catch/finally round per source iteration, and the de Bruijn
   `stack` backend runs ~1.7x more instructions per source iteration than
   `semantic`/`register`. The measured ceiling is ~6.8M steps (php
   `caught-failure` n=256 on `stack`), so 10M completes every fixture at
   n=256 with headroom while a genuine non-termination is still recorded as
   `:budget-exhausted`, never silently masked."
  10000000)


(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(defn- clj-registry
  "The clojure host surface: default registry + stream + cell + data, exactly
   the `av-modules` composition the clojure fixtures run over."
  []
  (data/register-data-module
    (module/register-cell-module
      (module/register-stream-module (module/default-registry)))))


(defn- ts-registry
  "The TypeScript bundled composition (prelude-parity's `opts` modules)."
  []
  (ts-prelude/admit
    (ts-prelude/register-integer-module
      (data/register-data-module
        (module/register-cell-module (module/empty-registry))
        {::data/max-items 1048576})
      {::integer/max-bits 100000, ::integer/max-digits 4300})))


(defn- py-registry
  "The Python bundled composition (e2e-test's `host-registry`)."
  []
  (py-prelude/admit
    (py-prelude/register-integer-module
      (data/register-data-module
        (module/register-cell-module (module/empty-registry))
        {::data/max-items 1048576})
      {::integer/max-bits 100000, ::integer/max-digits 4300})))


(defn- php-registry
  "The PHP bundled composition (portable-harness's `host-registry`)."
  []
  (php-portable/host-registry))


(defn- registry-for
  [frontend]
  (case frontend
    :clojure (clj-registry)
    :python (py-registry)
    :typescript (ts-registry)
    :php (php-registry)))


;; =============================================================================
;; Lowering: bundled (unlinked) production lowerings
;; =============================================================================

(def ^:private clj-library-opts
  {:convention :arg-vector, :outcomes true
   :adapters clj-comp/library-adapters
   :native vm/primitive-profiles})


(defn- source-events
  [unit text]
  [{:yang.source/event :yang.source/chunk,
    :yang.source/unit unit,
    :yang.source/ordinal 0,
    :yang.source/text text}
   {:yang.source/event :yang.source/seal,
    :yang.source/unit unit,
    :yang.source/chunk-count 1}])


(defn- run-member
  "The run member of a source envelope: the map AST the lowering emitted."
  [{:yin/keys [batch root]}]
  (nth batch root))


(defn- lower-ts
  "Bundled TypeScript lowering of `source`: source events -> CST -> program."
  [source]
  (let [program (tu/new-memory-log)
        src (tu/new-memory-log)
        cst-log (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (doseq [e (source-events [:depth 0] source)] (stream/append! src e))
    (ts-cst/step-stage (ts-cst/open-stage src cst-log))
    (ts-lower/step-stage (ts-lower/open-stage cst-log program diagnostics
                                              {:medium ts-lower/program-medium,
                                               :prelude :bundled}))
    (if (seq (tu/drain diagnostics))
      {:diagnostics (tu/drain diagnostics)}
      {:ast (run-member (first (tu/drain program)))})))


(defn- lower-py
  "Bundled Python lowering of `source`."
  [source]
  (let [program (tu/new-memory-log)
        src (tu/new-memory-log)
        cst (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (doseq [e (source-events [:depth 0] source)] (stream/append! src e))
    (py-parser/step-stage (py-parser/open-stage src cst))
    (py-lower/step-stage (py-lower/open-stage cst program diagnostics
                                              {:medium py-lower/program-medium,
                                               :prelude :bundled}))
    (if (seq (tu/drain diagnostics))
      {:diagnostics (tu/drain diagnostics)}
      {:ast (run-member (first (tu/drain program)))})))


(defn- lower-php
  "Bundled PHP lowering of `source`."
  [source]
  (let [cst-log (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (stream/append! cst-log (php-cst/parse-source source))
    (php-lower/step-stage (php-lower/open-stage cst-log program diagnostics
                                                {:medium php-lower/program-medium}))
    (if (seq (tu/drain diagnostics))
      {:diagnostics (tu/drain diagnostics)}
      {:ast (run-member (first (tu/drain program)))})))


(defn- lower-clj
  "Bundled clojure lowering of `forms` (a vector of top-level forms): the
   self-contained compiled program (the exception machinery — `clj/try`/
   `clj/throw` — is inlined by `compile-program`)."
  [forms]
  {:ast (clj-comp/compile-program (into (clj-comp/library-for forms) (vec forms))
                                  true clj-library-opts)})


(defn- lower-source
  "Bundled production lowering of a fixture `source` (a string for py/ts/php,
   a vector of forms for clojure) to `{:ast ...}` or `{:diagnostics ...}`."
  [frontend source]
  (case frontend
    :clojure (lower-clj source)
    :python (lower-py source)
    :typescript (lower-ts source)
    :php (lower-php source)))


;; =============================================================================
;; The depth driver: step to completion, sample the continuation, never run
;; =============================================================================

(def ^:private load-semantic
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(defn- pending-scheduler-work?
  "True when the VM still holds scheduler work that must be serviced before
   the run is a genuine completion (astra correction (a): empty K is not
   completion; parking can clear active K)."
  [machine]
  (boolean
    (or (seq (or (:ready-queue machine) []))
        (seq (or (:wait-set machine) []))
        (seq (or (:parked machine) [])))))


(defn- step-depth
  "Step `machine` one instruction at a time to completion, recording the
   maximum continuation depth (semantic `:k`, stack/register `:continuation`)
   and the executed step count. Returns
   `{:value :depth :steps :completed? :blocked? :pending?}`. Never calls
   `vm/run`; a blocked machine or budget exhaustion is an explicit failure."
  [machine budget]
  (loop [v machine n 0 mx (count (vm/continuation v))]
    (cond
      (vm/halted? v)
      {:value (vm/value v) :depth mx :steps n
       :completed? true :blocked? false :pending? (pending-scheduler-work? v)}

      (vm/blocked? v)
      {:value nil :depth mx :steps n
       :completed? false :blocked? true :pending? (pending-scheduler-work? v)}

      (>= n budget)
      {:value nil :depth mx :steps n :completed? false :blocked? false
       :pending? (pending-scheduler-work? v) :budget-exhausted true}

      :else
      (let [v' (vm/step v)]
        (recur v' (inc n) (max mx (count (vm/continuation v'))))))))


(defn- run-depth
  "Run `ast` on the three vector VMs, stepping to completion and sampling
   depth. Returns `{backend {:value :depth :steps :completed? :blocked?
   :pending?}}`."
  [registry ast budget]
  (let [opts (assoc base-opts :modules registry)
        datoms (vec (vm/ast->datoms ast))
        projected #(:vector (sr-linearize/project ast))]
    {:semantic (step-depth (load-semantic (semantic/create-vm opts) datoms)
                           budget)
     :stack (step-depth (dvm/create-vm
                          (:image (derive/stack-image (projected)))
                          (assoc opts :contract vm/stack-contract))
                        budget)
     :register (step-depth (rvm/create-vm
                             (derive/register-image (projected))
                             (assoc opts :contract vm/register-contract))
                           budget)}))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- sum
  [n]
  (quot (* n (dec n)) 2))


(defn- sub-n
  "Substitute the size into a string template (`__N__`) or a clojure form
   template (the symbol `N`)."
  [template n]
  (if (string? template)
    (str/replace template "__N__" (str n))
    (walk/postwalk-replace {'N n} template)))


(def fixture-templates
  "`{variant {frontend template}}` where a template is a string (py/ts/php)
   or a quoted form (clojure), with `__N__`/`N` as the size placeholder.
   Every fixture is pure: loops, try/finally, caught failures, an outer
   catch — no FFI, no parking. Each returns/prints a single integer that
   proves `n` correct iterations/finalizations/catches."
  {:plain
   {:clojure
    '[(loop [i 0 s 0] (if (< i N) (recur (inc i) (+ s i)) s))]
    :python "s = 0\nfor i in range(__N__):\n    s += i\nprint(s)\n"
    :typescript "let s = 0;\nfor (let i = 0; i < __N__; i++) { s += i; }\nconsole.log(s);\n"
    :php "<?php $s = 0; for ($i = 0; $i < __N__; $i++) { $s += $i; } echo $s;\n"}

   :try-finally
   {:clojure
    '[(let [finals (cell/new 0)]
        (loop [i 0]
          (if (< i N)
            (do (try :body (finally (cell/set! finals (+ 1 (cell/get finals)))))
                (recur (inc i)))
            (cell/get finals))))]
    :python "finals = 0\nfor i in range(__N__):\n    try:\n        pass\n    finally:\n        finals += 1\nprint(finals)\n"
    :typescript "let finals = 0;\nfor (let i = 0; i < __N__; i++) { try { } finally { finals++; } }\nconsole.log(finals);\n"
    :php "<?php $finals = 0; for ($i = 0; $i < __N__; $i++) { try { } finally { $finals++; } } echo $finals;\n"}

   :caught-failure
   {:clojure
    '[(let [catches (cell/new 0) finals (cell/new 0)]
        (loop [i 0]
          (if (< i N)
            (do (try (throw :boom)
                     (catch Exception e (cell/set! catches (+ 1 (cell/get catches))))
                     (finally (cell/set! finals (+ 1 (cell/get finals)))))
                (recur (inc i)))
            ;; caller-handler restoration probe: a fresh throw/catch after the
            ;; loop returns the counts only if the handler context was restored.
            (try (throw :restore)
                 (catch Exception e (+ (cell/get catches) (cell/get finals)))))))]
    :python "catches = 0\nfinals = 0\ni = 0\nwhile i < __N__:\n    try:\n        raise ValueError\n    except ValueError:\n        catches += 1\n    finals += 1\n    i += 1\ntry:\n    raise ValueError\nexcept ValueError:\n    print(catches + finals)\n"
    :typescript "let catches = 0, finals = 0;\nfor (let i = 0; i < __N__; i++) { try { throw new Error('x'); } catch (e) { catches++; } finally { finals++; } }\ntry { throw new Error('restore'); } catch (e) { console.log(catches + finals); }\n"
    :php "<?php $catches = 0; $finals = 0; for ($i = 0; $i < __N__; $i++) { try { throw new Exception('x'); } catch (Throwable $e) { $catches++; } finally { $finals++; } } try { throw new Exception('restore'); } catch (Throwable $e) { echo ($catches + $finals); }\n"}

   :outer-catch
   {:clojure
    '[(try (loop [i 0 s 0] (if (< i N) (recur (inc i) (+ s i)) s))
           (catch Exception e -1))]
    :python "s = 0\ntry:\n    for i in range(__N__):\n        s += i\nexcept ValueError:\n    s = -1\nprint(s)\n"
    :typescript "let s = 0;\ntry { for (let i = 0; i < __N__; i++) { s += i; } } catch (e) { s = -1; }\nconsole.log(s);\n"
    :php "<?php $s = 0; try { for ($i = 0; $i < __N__; $i++) { $s += $i; } } catch (Throwable $e) { $s = -1; } echo $s;\n"}})


(defn- expected-result
  "The integer each fixture returns/prints when correct."
  [variant n]
  (case variant
    :plain (sum n)
    :try-finally n
    :caught-failure (* 2 n)
    :outer-catch (sum n)))


(defn- canon-str
  "A canonical string for a result value: a number's trailing `.0` is
   stripped (Python's `range`/`+=` arithmetic can answer a float)."
  [x]
  (if (number? x)
    (let [s (str x)]
      (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s))
    (str x)))


(defn- observe
  "Normalize a VM value to a comparable (string) result for `frontend`."
  [frontend value]
  (case frontend
    :clojure (canon-str (if (and (vector? value) (= :yin.k/ok (first value)))
                          (second value)
                          value))
    :python (canon-str (ffirst (:py/out value)))
    :typescript (canon-str (ffirst (:js/out value)))
    :php (php-portable/render value)))


(defn- expected-observed
  "The comparable (string) form of `result` for `frontend`."
  [frontend result]
  (if (= frontend :php) (str result) (canon-str result)))


;; =============================================================================
;; The constant matrix (144 completed measurements)
;; =============================================================================

(defn- measure-one
  "Lower and run one (frontend, variant, n) fixture on all three backends.
   Returns `{:diagnostics d}` or `{backend {:value :depth :steps ...}}`."
  [frontend variant n]
  (try
    (let [src (sub-n (get-in fixture-templates [variant frontend]) n)
          lowered (lower-source frontend src)]
      (if (:diagnostics lowered)
        {:diagnostics (:diagnostics lowered)}
        (run-depth (registry-for frontend) (:ast lowered) step-budget)))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (str (name frontend) " " (name variant) " n=" n ": "
                           (ex-message e))
                      (assoc (ex-data e) :frontend frontend :variant variant :n n)
                      e)))))


(defn- measure-matrix
  "Every (frontend, variant, size) -> backend results, keyed for the evidence
   artifact. Returns a nested map; each leaf is one backend's measurement."
  []
  (into (sorted-map)
        (for [f frontends]
          [f (into (sorted-map)
                   (for [v variants]
                     [v (into (sorted-map)
                              (for [n sizes]
                                [n (measure-one f v n)]))]))])))


(defn- assert-constant-case
  "Assert the constant contract for one (frontend, variant) across sizes."
  [frontend variant]
  (let [by-size (into {} (for [n sizes] [n (measure-one frontend variant n)]))]
    (testing (str (name frontend) " " (name variant))
      (doseq [n sizes]
        (let [r (get by-size n)]
          (is (not (:diagnostics r))
              (str "no lowering diagnostics at n=" n ": " (pr-str (:diagnostics r))))
          (doseq [b backends]
            (let [m (get r b)]
              (is m (str "backend " b " produced a measurement at n=" n))
              (is (:completed? m) (str b " completed at n=" n))
              (is (not (:blocked? m)) (str b " did not block at n=" n))
              (is (not (:pending? m)) (str b " has no pending scheduler work at n=" n))
              (is (pos? (:steps m)) (str b " executed instructions at n=" n))
              (is (= (expected-observed frontend (expected-result variant n))
                     (observe frontend (:value m)))
                  (str b " result correct at n=" n))))))
      ;; D(16)=D(64)=D(256) within this frontend/backend/variant.
      (doseq [b backends]
        (let [ds (map (fn [n] (get-in by-size [n b :depth])) sizes)]
          (is (apply = ds)
              (str b " D(16)=D(64)=D(256): " (pr-str (vec ds)))))))))


;; =============================================================================
;; Recursive characterization (recorded, never asserted for growth)
;; =============================================================================

(def recursive-templates
  "Named self/mutual recursion under an outer catch. Templates carry `__N__`/
   `N`; results are recorded, never asserted for growth or a minimum slope."
  {:self
   {:clojure
    '[(defn f
        [i acc]
        (if (= i 0) acc (f (dec i) (+ acc i))))
      (try (f N 0) (catch Exception e -1))]
    :python "def f(i, acc):\n    return acc if i == 0 else f(i - 1, acc + i)\ntry:\n    print(f(__N__, 0))\nexcept RecursionError:\n    print(-1)\n"
    :typescript "function f(i, acc) { return i === 0 ? acc : f(i - 1, acc + i); }\ntry { console.log(f(__N__, 0)); } catch (e) { console.log(-1); }\n"
    :php "<?php function f($i, $acc) { return $i <= 0 ? $acc : f($i - 1, $acc + $i); } try { echo f(__N__, 0); } catch (Throwable $e) { echo -1; }\n"}

   :mutual
   {:clojure
    '[(defn dpeven
        [i]
        (if (= i 0) true (dpodd (dec i))))
      (defn dpodd
        [i]
        (if (= i 0) false (dpeven (dec i))))
      (try (dpeven N) (catch Exception e false))]
    :python "def even(i):\n    return True if i == 0 else odd(i - 1)\ndef odd(i):\n    return False if i == 0 else even(i - 1)\ntry:\n    print('true' if even(__N__) else 'false')\nexcept RecursionError:\n    print('false')\n"
    :typescript "function even(i) { return i === 0 ? true : odd(i - 1); }\nfunction odd(i) { return i === 0 ? false : even(i - 1); }\ntry { console.log(even(__N__) ? 'true' : 'false'); } catch (e) { console.log('false'); }\n"
    :php "<?php function even($i) { return $i <= 0 ? true : odd($i - 1); } function odd($i) { return $i <= 0 ? false : even($i - 1); } try { echo (even(__N__) ? 'true' : 'false'); } catch (Throwable $e) { echo 'false'; }\n"}})


(def recursive-sizes [8 32 128])


(defn- measure-recursive
  "Lower and run one recursive (shape, frontend, n) fixture on all backends.
   Returns `{:diagnostics d}` or `{backend measurement}`."
  [shape frontend n]
  (try
    (let [src (sub-n (get-in recursive-templates [shape frontend]) n)
          lowered (lower-source frontend src)]
      (if (:diagnostics lowered)
        {:diagnostics (:diagnostics lowered)}
        (run-depth (registry-for frontend) (:ast lowered) step-budget)))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (str "recursive " (name shape) " " (name frontend) " n=" n ": "
                           (ex-message e))
                      (assoc (ex-data e) :shape shape :frontend frontend :n n)
                      e)))))


;; =============================================================================
;; The evidence artifact
;; =============================================================================

(defn- revision
  "The short git revision, or :unknown."
  []
  (try
    (let [sha (str/trim (:out (shell/sh "git" "rev-parse" "--short" "HEAD")))]
      (if (empty? sha) :unknown sha))
    (catch Exception _ :unknown)))


(defn- dirty?
  "True when the worktree has uncommitted changes."
  []
  (try
    (not (empty? (str/trim (:out (shell/sh "git" "status" "--porcelain")))))
    (catch Exception _ false)))


(defn- evidence
  "The complete evidence map: revision, dirty-state, the constant matrix with
   depths/steps/completion/budgets, and the recursive characterization."
  []
  (let [matrix (measure-matrix)
        recursive (into (sorted-map)
                        (for [shape [:self :mutual]]
                          [shape (into (sorted-map)
                                       (for [f frontends]
                                         [f (into (sorted-map)
                                                  (for [n recursive-sizes]
                                                    [n (measure-recursive shape f n)]))]))]))]
    {:revision (revision),
     :dirty-state (dirty?),
     :composition :unlinked,
     :label :unlinked,
     :budget step-budget,
     :constant-matrix matrix,
     :recursive recursive,
     :linked-warm-follow-up
     {:status :pending,
      :scope "real published compositions, blocking-aware sampling including
              scheduler-held continuations, recursive characterization, and
              constant-fixture reruns"}}))


(defn- measurement-summary
  "A compact evidence row for one backend measurement."
  [m]
  (cond
    (:diagnostics m) {:diagnostics true}
    (nil? m) {:missing true}
    :else {:depth (:depth m),
           :steps (:steps m),
           :completion (if (:completed? m) :complete
                           (if (:blocked? m) :blocked :incomplete)),
           :pending? (:pending? m),
           :budget-exhausted? (boolean (:budget-exhausted m))}))


(defn- matrix-evidence
  "The constant matrix reduced to its evidence fields, ready for EDN."
  [matrix]
  (into (sorted-map)
        (for [[f vs] matrix]
          [f (into (sorted-map)
                   (for [[v ns] vs]
                     [v (into (sorted-map)
                              (for [[n bs] ns]
                                [n (into (sorted-map)
                                         (for [[b m] bs]
                                           [b (measurement-summary m)]))]))]))])))


(defn- recursive-evidence
  [recursive]
  (into (sorted-map)
        (for [[shape fs] recursive]
          [shape (into (sorted-map)
                       (for [[f ns] fs]
                         [f (into (sorted-map)
                                  (for [[n bs] ns]
                                    [n (into (sorted-map)
                                             (for [[b m] bs]
                                               [b (measurement-summary m)]))]))]))])))


(defn write-evidence!
  "Write the reproducible evidence artifact `test/yang/call/depth_probe.edn`."
  ([] (write-evidence! "test/yang/call/depth_probe.edn"))
  ([path]
   (let [ev (evidence)]
     (spit path
           (str ";; GENERATED unlinked depth-probe evidence, revision "
                (:revision ev) ".\n"
                (pr-str {:revision (:revision ev),
                         :dirty-state (:dirty-state ev),
                         :composition (:composition ev),
                         :label (:label ev),
                         :budget (:budget ev),
                         :constant-matrix (matrix-evidence (:constant-matrix ev)),
                         :recursive (recursive-evidence (:recursive ev)),
                         :linked-warm-follow-up (:linked-warm-follow-up ev)})
                "\n"))
     ev)))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest constant-matrix-test
  "4 frontends x 4 variants x 3 backends x 3 sizes = 144 completed
   measurements, with D(16)=D(64)=D(256) within each frontend/backend/variant."
  (doseq [f frontends, v variants]
    (assert-constant-case f v)))


(deftest recursive-characterization-test
  "Record named self/mutual recursion depths at 8/32/128; assert completion
   and correct outcome, never growth or a minimum slope."
  (doseq [shape [:self :mutual], f frontends]
    (testing (str (name shape) " " (name f))
      (doseq [n recursive-sizes]
        (let [r (measure-recursive shape f n)]
          (is (not (:diagnostics r)) (str "no diagnostics at n=" n))
          (doseq [b backends]
            (let [m (get r b)]
              (is m (str b " measured at n=" n))
              (is (:completed? m) (str b " completed at n=" n))
              (is (not (:blocked? m)) (str b " did not block at n=" n))
              (is (pos? (:steps m)) (str b " executed steps at n=" n))
              ;; outcome is correct: the recursion terminates to a real value
              (is (some? (observe f (:value m))) (str b " produced an outcome")))))))))


(deftest evidence-artifact-test
  "Write the reproducible evidence artifact `test/yang/call/depth_probe.edn`
   (revision, dirty-state, the constant matrix, and the recursive
   characterization), then verify it was written."
  (let [ev (write-evidence!)]
    (is (:revision ev) "the evidence records a revision")
    (is (map? (:constant-matrix ev)) "the constant matrix is present")
    (is (map? (:recursive ev)) "the recursive characterization is present")))
