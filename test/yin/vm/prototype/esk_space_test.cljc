(ns yin.vm.prototype.esk-space-test
  "Contract for the ESK-as-datoms prototype (src/cljc/yin/vm/prototype/esk_space.cljc).

  The prototype's claim is not merely \"it computes\": it is that representing
  E, S and K as datoms in a dao.space makes time-travel, introspection and
  migration fall out of the substrate. These tests assert exactly those four
  properties over the running machine, using the real dao.space.query API to
  interrogate the machine's own state."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn])
            [dao.space.query :as q]
            [yin.vm.prototype.esk-space :as m]))


(def ^:private factorial
  '(letrec [fact (lambda (n) (if (< n 2) 1 (* n (fact (- n 1)))))] (fact 5)))


(deftest evaluates-the-language
  (testing "literals, primitives, if/let, closures, and recursion"
    (is (= 42 (:value (m/run '42))))
    (is (= 5 (:value (m/run '(+ 2 3)))))
    (is (= 20 (:value (m/run '(let [x 10] (if (< x 5) 1 (* x 2)))))))
    (is (= 49 (:value (m/run '((lambda (x) (* x x)) 7)))))
    (is (= 120 (:value (m/run factorial)))
        "letrec closes over its own binding, so recursion terminates at 120")))


(deftest cesk-time-is-datom-time
  (testing
    "every step appends datoms under a fresh t; the highest recorded
            :cfg/step equals the machine's step count"
    (let [{:keys [st steps]} (m/run factorial)
          space (:space st)
          highest-step (apply max
                              (map peek
                                   (q/q '[:find ?s :where [_ :cfg/step ?s]] space)))]
      ;; steps counts transitions; each transition stamps one config at its
      ;; t.
      (is (= steps (inc highest-step))
          "the datom log's t-axis IS the machine's step counter"))))


(deftest introspection-is-datalog
  (testing
    "live machine state answers datalog queries with no bespoke traversal"
    (let [space (:space (:st (m/run factorial)))]
      (is
        (= 6 (ffirst (q/q '[:find (count ?e) :where [?e :cell/value _]] space)))
        "one recursion cell per fact frame (5 downto 1) plus the fact binding")
      (is (= [1 2 3 4 5]
             (sort (map first
                        (q/q '[:find ?v :in $ ?name :where [?b :bind/name ?name]
                               [?b :bind/addr ?a] [?a :cell/value ?v]]
                             space
                             'n))))
          "the whole live recursion stack's bindings of n, read by query")
      (is (contains? (set (map first
                               (q/q '[:find ?t :where [_ :k/tag ?t]] space)))
                     :letrec)
          "the continuation is reified — its frame tags are queryable data"))))


(deftest time-travel-is-as-of
  (testing "a recursion cell is absent before it is set and present after"
    (let [space (:space (:st (m/run factorial)))
          ;; the first-allocated cell bound to n
          cell (first (sort (map first
                                 (q/q '[:find ?a :in $ ?name :where
                                        [?b :bind/name ?name] [?b :bind/addr ?a]
                                        [?a :cell/value _]]
                                      space
                                      'n))))
          set-t (nth (first (q/match space [cell :cell/value '_])) 3)]
      (is (nil? (:cell/value
                  (q/pull space cell [:cell/value] {:as-of (dec set-t)})))
          "as-of just before the set, the cell holds no value")
      (is (= 5 (:cell/value (q/pull space cell [:cell/value] {:as-of set-t})))
          "as-of the set, the value (5, the outermost fact arg) is visible"))))


(deftest migration-is-the-space
  (testing
    "the machine is nothing but its datoms: serialize mid-flight to
            EDN text, revive, and resume to the same answer"
    (let [seed (m/inject factorial)
          ;; run a few steps by hand to reach a mid-flight configuration
          [st cfg] (nth (iterate (fn [[s c]] (m/step s c)) seed) 20)]
      (is (number? cfg) "mid-flight: not yet halted")
      (is (map? st) "the whole machine is one value — {:space :eid :t}")
      #?(:clj
         (let [wire (pr-str st) ; the entire machine, as text
               revived (edn/read-string wire)]
           (is
             (= 120 (:value (m/drive revived cfg 100000)))
             "a machine revived from text resumes to the correct value"))))))
