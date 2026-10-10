(ns yin.vm.semantic-register.analysis
  "Static facts per pc derived from A
   (`docs/design/yin.vm.semantic-register-vm.md` §8.1): per body, the
   explicit control-flow graph, `def`, `use`, the live-in set `L` by
   backward liveness to a fixed point, and the saved-window rule
   `saved(p, rd) = L(p) − {rd}`.

   Pure functions of a vector that passed
   `yin.vm.semantic-register.code/well-formed?`. Nothing here is persisted
   in A; a receiver recomputes it from the vector alone."
  (:require [clojure.set :as set]
            [yin.vm.semantic-register.code :as code]))


(defn successors
  "§8.1's successor relation for the instruction at `pc`: none for
   `:return`, `:halt`, `:resume` and a tail `:call` (runtime
   terminators); the target for `:jump`; `pc+1` and the target for
   `:branch-false`; `pc+1` otherwise. Every edge stays inside the body
   (§3.4 item 6)."
  [v pc]
  (let [t (nth v pc)]
    (case (nth t 0)
      (:return :halt :resume) []
      :jump [(nth t 1)]
      :branch-false [(inc pc) (nth t 2)]
      :call (if (code/tail-call? t) [] [(inc pc)])
      [(inc pc)])))


(defn defs
  "`def(pc)`: `#{rd}` for an instruction with a destination slot, `#{}`
   otherwise. A tail `:call` names its `rd` and has no successor, so its
   `def` never reaches an `L`."
  [v pc]
  (if-let [r (code/rd (nth v pc))] #{r} #{}))


(defn uses
  "`use(pc)`: the instruction's register operands, as a set."
  [v pc]
  (set (code/uses (nth v pc))))


(defn- body-live
  "Backward liveness over one body `[start, end)` to a fixed point:
   `L(p) = use(p) ∪ (⋃ L(s) for s in succ(p)) − def(p)`, swept in
   decreasing pc order until no set changes. Returns a vector of sets
   indexed by `pc - start`."
  [v start end]
  (let [ps (range (dec end) (dec start) -1)
        du (mapv (fn [pc] [(uses v pc) (defs v pc) (successors v pc)])
                 (range start end))]
    (loop [live (vec (repeat (- end start) #{}))]
      (let [swept (reduce (fn [live pc]
                            (let [[u d ss] (nth du (- pc start))
                                  out (reduce (fn [acc s]
                                                (into acc
                                                      (nth live (- s start))))
                                              #{}
                                              ss)]
                              (assoc live
                                     (- pc start)
                                     (into u (set/difference out d)))))
                          live
                          ps)]
        (if (= swept live) live (recur swept))))))


(defn analyze
  "The §8.1 facts of a well-formed vector. Returns `{:bodies [{:start s
   :end e} …] :successors [succ …] :def [set …] :use [set …] :live [set
   …]}`, the last four indexed by pc, `:live` being `L` computed per
   body over the stated edges, so instructions unreachable from a
   terminal path (layout syntax) have an `L` like any other node."
  [v]
  (let [bodies (mapv #(select-keys % [:start :end]) (code/body-ranges v))]
    {:bodies bodies,
     :successors (mapv #(successors v %) (range (count v))),
     :def (mapv #(defs v %) (range (count v))),
     :use (mapv #(uses v %) (range (count v))),
     :live (into [] (mapcat (fn [{:keys [start end]}] (body-live v start end)))
                 bodies)}))


(defn live
  "`L(p)` from an `analyze` result."
  [analysis p]
  (nth (:live analysis) p))


(defn saved
  "The saved-window rule (§2.4, §8.1): the registers a frame, wait,
   parked record or capture resuming at `p` with delivery `{:deliver :rd
   :rd rd}` carries, `L(p) − {rd}`. The subtraction is explicit."
  [analysis p rd]
  (disj (live analysis p) rd))


(defn reachable
  "The pcs reachable from the start of the body holding `pc`, over the
   stated successor edges."
  [v pc]
  (let [bodies (code/body-ranges v)
        start (:start (nth bodies (code/body-index bodies pc)))]
    (loop [seen #{start}
           todo [start]]
      (if-let [p (peek todo)]
        (let [fresh (remove seen (successors v p))]
          (recur (into seen fresh) (into (pop todo) fresh)))
        seen))))
