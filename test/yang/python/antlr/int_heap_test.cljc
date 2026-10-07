(ns yang.python.antlr.int-heap-test
  "S6: Python integer leaves in the heap, streams and kernel images."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [dao.test-slow :as slow]
    [yang.python.antlr.int-contract-fixtures :as f]
    [yang.python.antlr.int-ops-test :refer [canon-ints]]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as walker]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
    [yin.vm.integer.host :as integer-host]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]
    [yin.vm.ucf.handoff :as handoff]
    [yin.vm.values :as values]))


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :gc-threshold 2,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (prelude/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300}))})


(defn- boundaries
  []
  (mapv f/value ["2^53" "2^63" "2^64" "-2^64-1" "2^100"]))


(defn- boundary-node
  "Build carriers at execution, rather than placing them in code rows."
  []
  (u/sexp->uast
    (reduce (fn [acc n]
              (list 'conj acc
                    (list 'integer/parse (f/hex-text n) 16)))
            [] (boundaries))))


(defn- b64
  [x]
  (jing/bytes->base64 (jing/canonical-bytes x)))


(defn- boundary-leaves
  [x]
  (filter #(and (or (number? %) (integer-host/big-carrier? %))
                (some #{(b64 %)} (map b64 (boundaries))))
          (tree-seq coll? seq x)))


(def ^:private kernels
  "Per VM: compile an AST to the image the kernel loads, and load it."
  {:ast-walker {:image vm/ast->semantic-bytecode,
                :load (fn [image o]
                        (walker/vm-load-rows (walker/create-vm o) image
                                             vm/ast-contract))},
   :semantic {:image (fn [ast]
                       (:vector (linearize/lower-rows
                                  (vm/ast->semantic-bytecode ast)))),
              :load (fn [image o]
                      (semantic/load-vector (semantic/create-vm o) image
                                            vm/semantic-contract))},
   :stack {:image (fn [ast] (:image (dl/adapt (vm/ast->datoms ast)))),
           :load (fn [image o]
                   (dvm/create-vm image
                                  (assoc o :contract vm/stack-contract)))},
   :register {:image (fn [ast]
                       (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                   ast))))),
              :load (fn [image o]
                      (rvm/create-vm image
                                     (assoc o :contract
                                            vm/register-contract)))}})


(defn- define
  [sym val]
  (u/app (u/v 'yin/def) (u/lit sym) val))


(deftest big-integer-images-test
  (testing "a continuation whose environment holds every boundary integer:
            the machine payload admits it and keeps their bytes (a reified
            continuation does not lift: UCF refuses it as
            :non-canonicalizable, whatever it holds)"
    (doseq [[k {:keys [image load]}] kernels]
      (let [capture-in-env (u/app (u/lam '[c]
                                         {:type :vm/current-continuation})
                                  (boundary-node))
            a (vm/run (load (image (define 'k capture-in-env)) opts))
            c (get (:store a) 'k)]
        (is (values/continuation? c) (str k))
        (is (vm/machine-data? c) (str k))
        (is (= (set (map b64 (boundaries)))
               (set (map b64 (boundary-leaves (values/payload c)))))
            (str k)))))
  (testing "a closure whose environment holds every boundary integer: the
            machine payload admits it, a lift keeps their bytes, and the
            closure lowered into another task returns them unchanged, on
            every VM"
    (let [fs (boundaries)
          task-a (assoc opts :capability-secret "task-a")
          task-b (assoc opts :capability-secret "task-b")
          module-ast (define 'f (u/app (u/lam '[c] (u/lam [] (u/v 'c)))
                                       (boundary-node)))]
      (doseq [[k {:keys [image load]}] kernels]
        (let [module-image (image module-ast)
              a (vm/run (load module-image task-a))
              f (get (:store a) 'f)]
          (is (values/closure? f) (str k))
          (is (vm/machine-data? f) (str k))
          (let [lifted (engine/lift-slice a :segment/own ['f])
                integers (boundary-leaves lifted)]
            (is (= (set (map b64 fs)) (set (map b64 integers))) (str k))
            (let [b (load (image (u/app (u/v 'f))) task-b)
                  received (engine/receive-module
                             b 'm (assoc lifted :images
                                         {(module/image-identity
                                            a module-image)
                                          module-image}))
                  b (assoc-in received [:store 'f]
                              (get-in (module/resolve-module
                                        (:modules received) 'm)
                                      [:bindings 'f]))]
              (is (= (canon-ints fs)
                     (canon-ints (vm/value (vm/run b))))
                  (str k)))))))))


(defn- guest
  [form]
  (u/mark-tails (u/then prelude/functions-uast (u/sexp->uast form))))


(defn- run-all
  [ast check]
  (doseq [[k {:keys [image load]}] kernels]
    (testing (str k)
      (check (vm/run (load (image ast) opts))))))


(defn- collection
  []
  (let [form '(let [xs (py/list [])]
                (do
                  (yin/def (quote build)
                    (fn [i total]
                      (if (= i 40)
                        (py/tuple
                          (py/conj (py/conj [] (get (cell/get xs) :items))
                                   (py/str (integer/format total))))
                        (do
                          ((fn [] (do (py/list []) nil)))
                          (let [n (py/add
                                    (py/int-lit "10000000000000000") i)]
                            (do (py/list-append xs n)
                                (build (+ i 1) (py/add total n))))))))
                  (build 0 0)))
        m (f/module 100000 4300)
        expected (mapv #(f/call m 'add (f/value "2^64") %) (range 40))
        total (f/call m 'add (f/call m 'mul (f/value "2^64") 40) 780)]
    (run-all
      (guest form)
      (fn [final]
        (is (= (canon-ints expected)
               (canon-ints (first (:items (vm/value final)))))
            (pr-str (select-keys final [:value :error :status])))
        (is (= {:py/str (f/call m 'format total)}
               (second (:items (vm/value final)))))
        (is (<= 41 (:id-counter final)) "41 list cells were allocated")
        ;; Collection runs once allocations pass the threshold, so a final
        ;; state may hold up to a threshold's garbage beside the live set.
        (is (<= (count (:heap final))
                (+ 2 (count (:heap (engine/collect final)))))
            "the heap holds at most a threshold's garbage")))))


(deftest ^:slow collection-test
  (slow/guard "collection-test" collection))


(defn- pinning
  []
  (let [put-value (guest
                    '(py/tuple
                       (py/conj
                         (py/conj [] (py/int-lit "10000000000000000"))
                         (py/list [42]))))
        garbage (guest '((fn [] (do (py/list []) (py/list []) nil))))
        read-value (guest
                     '(py/tuple
                        (py/conj
                          (py/conj [] (get (get x :items) 0))
                          (get (cell/get (get (get x :items) 1)) :items))))
        ast (u/let1
              's {:type :stream/make, :buffer 8}
              (u/let1
                'c {:type :stream/cursor, :source (u/v 's)}
                (u/then
                  {:type :stream/put, :target (u/v 's), :val put-value}
                  (u/then
                    garbage
                    (u/let1 'x {:type :stream/next, :source (u/v 'c)}
                            read-value)))))]
    (run-all
      (u/mark-tails ast)
      (fn [final]
        (is (vm/machine-data? (vm/value final)))
        (is (= (canon-ints [(f/value "2^64") [42]])
               (canon-ints (:items (vm/value final))))
            (pr-str (select-keys final [:value :error :status])))))))


(deftest ^:slow pinning-test
  (slow/guard "pinning-test" pinning))


(defn- cell-refusal
  []
  (run-all
    (guest '(py/list (py/conj [] (py/int-lit "10000000000000000"))))
    (fn [final]
      (let [r (handoff/export-task final (fn [_h] nil))]
        (is (= :yin.k/non-portable (:yin.k/status r)))
        (is (= :cell (:yin.k/kind r))))))
  (run-all
    (guest '(yin/def (quote held) (py/dict-new)))
    (fn [final]
      (let [r (try (engine/lift-slice final :segment/own ['held])
                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                     (ex-data e)))]
        (is (= :yin.k/non-portable (:yin.k/status r)))
        (is (= :cell (:yin.k/kind r))))))
  (run-all
    (guest '(yin/def (quote held)
              (py/tuple (py/conj [] (py/int-lit "10000000000000000")))))
    (fn [final]
      (let [r (engine/lift-slice final :segment/own ['held])]
        (is (= {} (:cells r)))
        (is (= (canon-ints [(f/value "2^64")])
               (canon-ints
                 (filter integer-host/big-carrier?
                         (tree-seq coll? seq (get-in r [:slice 'held]))))))))))


(deftest ^:slow cell-refusal-test
  (slow/guard "cell-refusal-test" cell-refusal))
