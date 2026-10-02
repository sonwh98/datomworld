(ns yang.python.antlr.e2e-c2-test
  "Phase C2 slice S1, generators core, end to end on all four VMs over the
   real cell and data modules, through the same stream topology as
   `yang.python.antlr.e2e-test`. Expected output is CPython 3.9.6's for the
   same source. The continuation-length check reads the final VM's heap,
   so it is JVM-only."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.e2e-test :as e2e]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]
    [yin.vm.values :as values]))


(defn- prints
  [& lines]
  {:py/out (vec lines), :py/exception nil})


(defn- every-vm=
  [expected source]
  (let [results (e2e/run-python (e2e/host-registry) source)]
    (is (not (contains? results :diagnostics)) (pr-str results))
    (doseq [k [:ast-walker :semantic :stack :register]]
      (is (= expected (get results k)) (str k)))))


(defn- lines
  [& ls]
  (apply str (map #(str % "\n") ls)))


;; =============================================================================
;; S1 acceptance
;; =============================================================================

(deftest generator-while-loop-test
  (testing "a generator function yielding 0..n-1 from a while loop, drained
            by list() and walked by for"
    (every-vm= (prints "[0, 1, 2]" "0" "1")
               (lines "def g(n):"
                      "    i = 0"
                      "    while i < n:"
                      "        yield i"
                      "        i += 1"
                      "print(list(g(3)))"
                      "for x in g(2):"
                      "    print(x)"))))


(deftest generator-body-is-lazy-test
  (testing "calling a generator function runs none of its body; each next
            runs it to the following yield; completion is StopIteration
            with value None"
    (every-vm= (prints "[]" "1 ['start']" "stop None ['start', 'end']")
               (lines "log = []"
                      "def g():"
                      "    log.append('start')"
                      "    yield 1"
                      "    log.append('end')"
                      "it = g()"
                      "print(log)"
                      "print(next(it), log)"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print('stop', e.value, log)"))))


(deftest generator-return-value-test
  (testing "return 7 completes with StopIteration(7); a further next on the
            exhausted generator gives StopIteration() with value None"
    (every-vm= (prints "1" "7 (7,)" "None ()" "d")
               (lines "def g():"
                      "    yield 1"
                      "    return 7"
                      "it = g()"
                      "print(next(it))"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print(e.value, e.args)"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print(e.value, e.args)"
                      "print(next(it, 'd'))"))))


(deftest yield-expression-send-test
  (testing "in [a(), (yield 1), b()] the evaluated a() survives the
            suspension and b() runs after the resume; the list holds the
            value sent"
    (every-vm= (prints "1 ['a']" "2 ['a', 'b', ['A', 's', 'B']]")
               (lines "log = []"
                      "def a():"
                      "    log.append('a')"
                      "    return 'A'"
                      "def b():"
                      "    log.append('b')"
                      "    return 'B'"
                      "def g():"
                      "    x = [a(), (yield 1), b()]"
                      "    log.append(x)"
                      "    yield 2"
                      "it = g()"
                      "print(next(it), log)"
                      "print(it.send('s'), log)"))))


(deftest yield-in-nested-default-test
  (testing "a default is evaluated in the enclosing scope, so its yield is the
            enclosing generator's: the value sent becomes the default"
    (every-vm= (prints "1 d")
               (lines "def g():"
                      "    def h(x=(yield 1)):"
                      "        return x"
                      "    yield h()"
                      "it = g()"
                      "print(next(it), it.send('d'))"))))


(deftest long-generator-with-break-test
  (testing "3000 items from a while True generator, consumed by a for that
            breaks: no growth per item on any VM"
    (every-vm= (prints "2999 2999")
               (lines "def g():"
                      "    i = 0"
                      "    while True:"
                      "        yield i"
                      "        i += 1"
                      "n = 0"
                      "for x in g():"
                      "    if x == 2999:"
                      "        break"
                      "    n += 1"
                      "print(n, x)"))))


;; =============================================================================
;; Continuation length (JVM-only: reads the final VM's heap)
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module)})


(def ^:private load-semantic
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private final-vms
  "Each VM's final state for a lowered program."
  {:ast-walker (fn [ast _] (vm/eval (tu/create-vm opts) ast)),
   :semantic (fn [_ datoms] (vm/run (load-semantic (semantic/create-vm opts) datoms))),
   :stack (fn [_ datoms]
            (vm/run (dvm/create-vm (:image (dl/adapt datoms))
                                   (assoc opts :contract vm/stack-contract)))),
   :register (fn [_ datoms]
               (vm/run (rvm/create-vm (:image (rc/adapt datoms))
                                      (assoc opts :contract vm/register-contract))))})


(defn- size
  "Node count of a continuation payload; closures, continuations and cell
   refs held in it count one each, not their contents."
  [x]
  (cond
    (values/host-typed? x) 1
    (map? x) (reduce + 1 (map size (concat (keys x) (vals x))))
    (coll? x) (reduce + 1 (map size x))
    :else 1))


(defn- suspended-resume-size
  "The size of the one suspended generator's `:resume` after the module
   consumed `n` items from a global generator, on VM `k`."
  [k n]
  (let [ast (lower/lower-packet
              (parser/parse-source (parser/make-worker) [:u 1]
                                   (lines "def g():"
                                          "    i = 0"
                                          "    while True:"
                                          "        yield i"
                                          "        i += 1"
                                          "it = g()"
                                          "for x in it:"
                                          (str "    if x == " n ":")
                                          "        break")))
        final ((get final-vms k) ast (vm/ast->datoms ast))
        gens (keep (fn [[_ {:keys [value]}]]
                     (when (= :generator (:py/type value)) value))
                   (:heap final))]
    (is (= 1 (count gens)) (str k " holds one generator"))
    (is (= :suspended (:state (first gens))) (str k))
    (is (= #{:py/type :name :state :resume :ctx} (set (keys (first gens))))
        (str k " a suspended generator holds only its resume and context"))
    (size (values/payload (:resume (first gens))))))


(deftest resume-continuation-length-is-stable-test
  (testing "the suspended generator's :resume has the same size after 10 and
            after 1000 items: no per-yield growth, the stale base fixed"
    (doseq [k [:ast-walker :semantic :stack :register]]
      (testing (str k)
        (is (= (suspended-resume-size k 10) (suspended-resume-size k 1000)))))))
