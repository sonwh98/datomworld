(ns yin.vm.semantic-register.scan
  "Dependency facts derived from admitted semantic register vectors."
  (:require [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.debruijn :as debruijn]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.ucf :as ucf]))


(defn- bodies
  [v]
  (when-let [defect (code/well-formed? v)]
    (throw (ex-info "Cannot scan malformed semantic register vector" defect)))
  (code/parse v))


(defn- chain
  [v bs i]
  (if-let [owner (:owner (nth bs i))]
    (cons (nth (nth v owner) 2) (chain v bs (code/body-index bs owner)))
    []))


(defn free-occurrences
  "Free names with exact pc and closure enclosure, in emission order."
  [v]
  (let [bs (bodies v)]
    (vec (keep-indexed
           (fn [pc t]
             (when (= :var (first t))
               (let [name (nth t 2) i (code/body-index bs pc)]
                 (when (and (not (vm/reserved-name? name))
                            (contains? (debruijn/resolve-name (chain v bs i) name) :free))
                   {:name name :at pc :in-body? (pos? i)})))) v))))


(defn free-names
  "The set of free names."
  [v]
  (set (map :name (free-occurrences v))))


(defn definition-occurrences
  "Store writes with pc and conditional enclosure."
  [v]
  (let [bs (bodies v)
        ranges (into #{} (mapcat (fn [[pc target]]
                                   (range (inc (min pc target)) (max pc target))))
                     (keep-indexed (fn [pc t]
                                     (case (first t)
                                       :jump [pc (nth t 1)]
                                       :branch-false [pc (nth t 2)]
                                       nil)) v))]
    (vec (keep-indexed
           (fn [pc t]
             (when (contains? #{:store-put :define} (first t))
               {:name (nth t 2) :at pc
                :conditional? (or (contains? ranges pc)
                                  (pos? (code/body-index bs pc)))})) v))))


(defn application-sites
  "Call and FFI sites in pc order."
  [v]
  (bodies v)
  (vec (keep-indexed (fn [pc t]
                       (when (contains? #{:call :ffi-call} (first t)) {:at pc})) v)))


(def footprint-mnemonics
  "Effect normalization under the current register vector table."
  (dissoc (get-in vm/footprint-table [vm/semantic-contract :mnemonics]) :push))


(defn segment-rows
  "Segment-qualified tuples addressed by A."
  [v]
  (bodies v)
  (let [a (ucf/code-address v)]
    (mapv (fn [pc t] (into [a pc] t)) (range) v)))


(defn requirements
  "Datalog extraction of store, FFI, parked and effect requirements."
  [v]
  (let [db (query/relation (segment-rows v))
        extract (fn [q] (set (map first (query/collect (query/q q db)))))
        mnemonics (extract '[:find ?mn :in $code :where [$code _ _ ?mn & _]])]
    {:store-keys (extract '[:find ?key :in $code
                            :where (or [$code _ _ :store-get _ ?key]
                                       [$code _ _ :store-put _ ?key _]
                                       [$code _ _ :define _ ?key _])])
     :ffi-ops (extract '[:find ?op :in $code :where [$code _ _ :ffi-call _ ?op _]])
     :parked-ids (extract '[:find ?pid :in $code :where [$code _ _ :resume ?pid _]])
     :effects (into #{} (mapcat footprint-mnemonics) mnemonics)}))
