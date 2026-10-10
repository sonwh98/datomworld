(ns yin.vm.semantic-register.analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.corpus :as corpus]))


;; =============================================================================
;; An independent reference: a naive worklist over an explicit edge list
;; =============================================================================
;; Written from §2.3 and §8.1 alone, reusing nothing of `analysis` or
;; `code`: its own operand positions, its own edge construction, and a
;; worklist driven by predecessors rather than reverse sweeps.

(def ^:private ref-positions
  "mnemonic → [destination index or nil, register-use indexes, register-
   vector-use indexes]."
  {:const [1 [] []],
   :var [1 [] []],
   :closure [1 [] []],
   :call [1 [2] [3]],
   :branch-false [nil [1] []],
   :jump [nil [] []],
   :return [nil [1] []],
   :halt [nil [1] []],
   :gensym [1 [] []],
   :store-get [1 [] []],
   :store-put [1 [] []],
   :stream-make [1 [] []],
   :stream-put [1 [2 3] []],
   :stream-cursor [1 [2] []],
   :stream-next [1 [2] []],
   :stream-close [1 [2] []],
   :ffi-call [1 [] [3]],
   :current-continuation [1 [] []],
   :park [1 [] []],
   :resume [nil [2] []],
   :define [1 [3] []]})


(defn- ref-def
  [t]
  (let [[d] (get ref-positions (first t))]
    (if d #{(nth t d)} #{})))


(defn- ref-use
  [t]
  (let [[_ singles vectors] (get ref-positions (first t))]
    (into (set (map #(nth t %) singles))
          (mapcat #(nth t %))
          vectors)))


(defn- ref-edges
  "Every CFG edge `[from to]` of the vector, built explicitly."
  [v]
  (vec (mapcat (fn [pc]
                 (let [t (nth v pc)]
                   (case (first t)
                     (:return :halt :resume) []
                     :jump [[pc (nth t 1)]]
                     :branch-false [[pc (inc pc)] [pc (nth t 2)]]
                     :call (if (true? (nth t 4)) [] [[pc (inc pc)]])
                     [[pc (inc pc)]])))
               (range (count v)))))


(defn- ref-live
  "Live-in sets by a worklist: start with every pc, recompute a pc from
   its successors, and requeue its predecessors when its set grows."
  [v]
  (let [edges (ref-edges v)
        succ (reduce (fn [m [a b]] (update m a (fnil conj []) b)) {} edges)
        pred (reduce (fn [m [a b]] (update m b (fnil conj []) a)) {} edges)]
    (loop [live (vec (repeat (count v) #{}))
           work (vec (range (count v)))]
      (if (empty? work)
        live
        (let [p (peek work)
              work (pop work)
              t (nth v p)
              out (reduce #(into %1 (nth live %2)) #{} (get succ p []))
              in (into (ref-use t) (remove (ref-def t)) out)]
          (if (= in (nth live p))
            (recur live work)
            (recur (assoc live p in) (into work (get pred p [])))))))))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest live-agrees-with-the-reference-on-every-golden
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (is (= (ref-live vector) (:live (analysis/analyze vector)))))))


(deftest def-and-use-agree-with-the-reference
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (let [a (analysis/analyze vector)]
        (is (= (mapv ref-def vector) (:def a)))
        (is (= (mapv ref-use vector) (:use a)))))))


(deftest live-at-a-terminator-is-its-operand
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (let [a (analysis/analyze vector)]
        (doseq [pc (range (count vector))
                :let [t (nth vector pc)]
                :when (contains? #{:return :halt} (first t))]
          (is (= #{(nth t 1)} (analysis/live a pc))))))))


(deftest saved-excludes-the-destination
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (let [a (analysis/analyze vector)]
        (doseq [pc (range (dec (count vector)))
                :let [t (nth vector pc)
                      rd (when (contains? (set (keys ref-positions)) (first t))
                           (first (ref-def t)))]
                :when rd]
          (is (= (disj (analysis/live a (inc pc)) rd)
                 (analysis/saved a (inc pc) rd)))
          (is (not (contains? (analysis/saved a (inc pc) rd) rd))))))))


(deftest saved-window-of-a-non-tail-call
  ;; (f (g x) y): while (g x) runs, f's register 1 must survive the call;
  ;; at the resume pc 4, L = {1 2} and saved(4, 2) = {1}.
  (let [v (:vector (get corpus/goldens :worked-example))
        a (analysis/analyze v)]
    (is (= #{1 2} (analysis/live a 4)))
    (is (= #{1} (analysis/saved a 4 2)))
    (is (= #{1 2 5} (analysis/live a 5)))
    (is (= #{} (analysis/live a 0)))))


(deftest structural-slot-of-a-definition-less-id
  (testing "a wholly terminal body: the structural :return names an id
            no reachable instruction writes or reads"
    (let [v (:vector (get corpus/goldens :resume-lambda-body))
          a (analysis/analyze v)
          reach (analysis/reachable v 4)]
      (is (= [:return 0] (nth v 6)))
      (is (= #{4 5} reach) "the :return is layout syntax")
      (is (= #{0} (analysis/live a 6)))
      (is (not-any? #(contains? (analysis/live a %) 0) reach))
      (is (not-any? #(contains? (get (:def a) %) 0) (range 4 7)))))
  (testing "a definition-less operand: the enclosing :call is layout
            syntax, its static L names the structural id"
    (let [v (:vector (get corpus/goldens :resume-operand))
          a (analysis/analyze v)
          reach (analysis/reachable v 0)]
      (is (= #{0 1 2} reach))
      (is (= #{1 2} (analysis/live a 3))
          "L over the stated edges, in an unreachable component")
      (is (not-any? #(contains? (analysis/live a %) 2) reach))))
  (testing "both arms terminal: the conditional's id is definition-less"
    (let [v (:vector (get corpus/goldens :all-terminal-arms))
          a (analysis/analyze v)
          reach (analysis/reachable v 0)]
      (is (= #{0 1 2 3 5 6} reach))
      (is (= #{0} (analysis/live a 4) (analysis/live a 7)))
      (is (not-any? #(contains? (analysis/live a %) 0) reach)))))


(deftest unreachable-components
  (testing "both arms tail calls: the arm's :jump and the :return have no
            reachable predecessor, and their L is still computed"
    (let [v (:vector (get corpus/goldens :all-tail-arms))
          a (analysis/analyze v)
          reach (analysis/reachable v 4)]
      (is (= #{4 5 6 7 8 10 11} reach))
      (is (= [] (analysis/successors v 8) (analysis/successors v 11)))
      (is (= #{0} (analysis/live a 9) (analysis/live a 12)))
      (is (= #{4} (analysis/live a 11)) "a tail call's L is its uses alone")))
  (testing "a join reachable from a continuing arm though not from the
            tail arm"
    (let [v (:vector (get corpus/goldens :if-in-tail))
          reach (analysis/reachable v 4)]
      (is (contains? reach 17))
      (is (= [] (analysis/successors v 16))))))


(deftest analysis-is-per-body
  (let [v (:vector (get corpus/goldens :closure-in-arm-in-body))
        a (analysis/analyze v)]
    (is (= [{:start 0, :end 4} {:start 4, :end 10} {:start 10, :end 12}
            {:start 12, :end 14}]
           (:bodies a)))
    (is (= #{1} (analysis/live a 5)))
    (is (= #{0} (analysis/live a 9)))))
