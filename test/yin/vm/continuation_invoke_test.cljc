(ns yin.vm.continuation-invoke-test
  "Applying a `:reified-continuation` (src/cljc/yin/vm/docs/co-routines.md,
   call/cc): abortive, multi-shot, store not rolled back, one argument. Each
   program runs on all four VMs -- the AST walker, the semantic VM, and the
   de Bruijn stack and register VMs -- with the same expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  {:ast-walker tu/compile-and-run,
   :semantic (fn [ast]
               (vm/value (vm/run (load-semantic-ast (semantic/create-vm opts)
                                                    (vm/ast->datoms ast)))))
   :stack (fn [ast]
            (vm/value
              (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                     (assoc opts
                                            :contract vm/stack-contract)))))
   :register (fn [ast]
               (vm/value
                 (vm/run
                   (rvm/create-vm
                     (:image (rc/adapt
                               (second (vm/ast->datoms-with-root ast))))
                     (assoc opts :contract vm/register-contract)))))})


(defn- on-every-vm
  "`[vm-key result]` for each VM; a throw becomes `[:thrown message]`."
  [ast]
  (into {}
        (map (fn [[k run]]
               [k (try (run ast)
                       (catch #?(:clj Exception
                                 :cljs :default
                                 :cljd Object)
                              e
                         [:thrown (ex-message e)]))]))
        runners))


(defn- every-vm=
  [expected ast]
  (let [results (on-every-vm ast)]
    (doseq [[k result] results]
      (is (= expected result) (str k)))))


;; =============================================================================
;; AST helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(def ^:private capture {:type :vm/current-continuation})


(defn- def!
  [sym val]
  (app (v 'yin/def) (lit sym) val))


(defn- then
  "Evaluate `first-node` for effect, then `next-node`."
  [first-node next-node]
  (app (lam ['_] next-node) first-node))


(defn- with-k
  "Bind the capture point's value to `r` and evaluate `body`: on the first
   pass `r` is the continuation, on every re-entry the delivered value."
  [body]
  (app (lam ['r] body) capture))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest escape-discards-pending-frames-test
  (testing "invoked under (+ 1000 ...), the continuation delivers 7 to the
            capture point: the +1000 frame is discarded, the captured +1
            frame is not"
    (every-vm= 9
               (app (v '+)
                    (lit 1)
                    (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                     (app (v '+) (v 'r) (lit 1))
                                     (app (v '+)
                                          (lit 1000)
                                          (app (v 'r) (lit 7)))))))))


(deftest tail-position-invocation-test
  (testing "the continuation invoked as a tail call"
    (every-vm= 8
               (app (v '+)
                    (lit 1)
                    (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                     (v 'r)
                                     (tail (app (v 'r) (lit 7)))))))))


(deftest re-entry-after-return-test
  (testing "a continuation captured inside a returned call, kept in the
            store, re-enters its capture point once per invocation; the
            counter bounds the loop"
    ;; count <- 0
    ;; x <- ((fn [] (current-continuation)))   ; returns before any invoke
    ;; when count = 0: saved <- x
    ;; if count < 3: count <- count + 1, (saved (* 10 count)) else [x count]
    (every-vm=
      [30 3]
      (then
        (def! 'count (lit 0))
        (app (lam ['x]
                  (then (if-node (app (v '=) (v 'count) (lit 0))
                                 (def! 'saved (v 'x))
                                 (lit nil))
                        (if-node (app (v '<) (v 'count) (lit 3))
                                 (then (def! 'count
                                         (app (v '+) (v 'count) (lit 1)))
                                       (app (v 'saved)
                                            (app (v '*)
                                                 (lit 10)
                                                 (v 'count))))
                                 (app (v 'conj)
                                      (app (v 'conj) (lit []) (v 'x))
                                      (v 'count)))))
             (app (lam [] capture)))))))


(deftest store-not-rolled-back-test
  (testing "a store write made before the invocation is visible after it"
    (every-vm= [1 :written]
               (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                (app (v 'conj)
                                     (app (v 'conj) (lit []) (v 'r))
                                     (v 'mark))
                                (then (def! 'mark (lit :written))
                                      (app (v 'r) (lit 1))))))))


(deftest arity-error-identical-across-vms-test
  (doseq [argc [0 2]]
    (testing (str argc " arguments")
      (every-vm= [:thrown "Continuation expects exactly one argument"]
                 (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                  (v 'r)
                                  (apply app (v 'r)
                                         (repeat argc (lit 1)))))))))
