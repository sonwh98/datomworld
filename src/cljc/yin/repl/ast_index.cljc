(ns yin.repl.ast-index
  "The shell's AST indexer: the `$ast` row relation and the `$occ`
   occurrence relation over every program the session expanded
   (collab/1790708845000-architect-repl-ast-row-relation, §1-2).

   It is a peer observer on `program-out`, attached beside the evaluator's
   and `yin.repl.index`'s and advanced by the evaluation round: the three
   share only the medium, and none reads another.  Each forwarded tree
   packet `[root rows]` adds its canonical rows to `:rows`, keyed by node
   address — one row per distinct address, so `$ast` is its values — and
   its `[root path node]` places, derived by `yin.vm/occurrences`, to
   `:occurrences` — one tuple per distinct place.  An identical root or
   row observed again multiplies neither relation.

   Both relations are in-memory session projections: rebuildable from the
   medium, published nowhere, carrying no origin or clock slot, and the
   same under the `:current` and `:history` views, since observed code is
   only ever added.  Reset and VM selection rebuild them with the session.

   Loss is reported, never hidden, and never stops the evaluator: a gap is
   counted on the observer (`:ingress-gaps`) and marks the indexer
   `:lost?`; a malformed packet, a row whose address is not the content
   address of its body, a row that differs (metadata included) from the
   one already held at its address, or a failed derivation is recorded
   under `:failure`.
   Either leaves the relations unavailable (`relations` answers nil) —
   never a partial snapshot — and later packets are consumed without being
   indexed until the session is rebuilt.  A packet is merged only once it
   is wholly derived."
  (:require [dao.jing :as jing]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]))


(defn make-indexer
  "The AST indexer for one session over `observer`, attached to
   `program-out` beside the evaluator's."
  [{:keys [observer]}]
  {:observer observer
   :rows {}
   :occurrences #{}
   :programs 0
   :lost? false
   :failure nil})


(defn- same-value?
  "`=` that also compares metadata at every depth, as `yin.vm.macro`'s
   packet validator does: the canonical encoding strips reader positions,
   so two bodies may share an address while differing there."
  [a b]
  (and (= a b)
       (= (meta a) (meta b))
       (or (nil? (meta a)) (same-value? (meta a) (meta b)))
       (cond (map? a) (every? (fn [[k v]]
                                (let [[k' v'] (find b k)]
                                  (and (same-value? k k') (same-value? v v'))))
                              a)
             (set? a) (every? (fn [e] (same-value? e (some #(when (= e %) %) b)))
                              a)
             (sequential? a) (every? true? (map same-value? a b))
             :else true)))


(defn- derive-packet
  "`{:root r :rows {id row} :occurrences #{[root path node]}}` for one
   packet, or `{:failure {...}}` when it is malformed or disagrees with
   `held`, the rows already indexed.  Every row's address is verified
   against its body (`dao.jing/segment-matches?`); a row whose body is `=`
   to the held one yet differs in metadata at any depth shares its address
   and is refused as `:address-conflict`, never merged over it."
  [held packet]
  (if-not (and (vector? packet)
               (= 2 (count packet))
               (sequential? (second packet))
               (every? #(and (vector? %) (<= 2 (count %))) (second packet)))
    {:failure {:stage :packet :reason :shape}}
    (let [[root packet-rows] packet
          rows (into {} (map (fn [r] [(first r) r])) packet-rows)]
      (if (< (count rows) (count packet-rows))
        {:failure {:stage :packet :root root :reason :duplicate-address
                   :id (some (fn [[id n]] (when (< 1 n) id))
                             (frequencies (map first packet-rows)))}}
        (if-let [defect (vm/validate-rows {:root root :rows rows})]
          {:failure {:stage :packet :root root :reason :invalid-rows
                     :defect defect}}
          (if-let [forged (some (fn [[id row]]
                                  (when-not (jing/segment-matches? id
                                                                   (subvec row 1))
                                    id))
                                rows)]
            {:failure {:stage :packet :root root :reason :address-mismatch
                       :id forged}}
            (if-let [conflict (some (fn [[id row]]
                                      (when (and (contains? held id)
                                                 (not (same-value? row
                                                                   (get held id))))
                                        id))
                                    rows)]
              {:failure {:stage :packet :root root :reason :address-conflict
                         :id conflict}}
              {:root root
               :rows rows
               :occurrences (vm/occurrences {:root root :rows rows})})))))))


(defn- index-packet
  "Merge one packet's rows and occurrences, or record why it could not be
   and leave both relations as they were."
  [ix packet]
  (let [derived (try
                  (derive-packet (:rows ix) packet)
                  (catch #?(:cljd Object :clj Exception :cljs js/Error) e
                    {:failure {:stage :derive :message (ex-message e)}}))]
    (if-let [failure (:failure derived)]
      (assoc ix :failure failure)
      (-> ix
          (update :rows into (:rows derived))
          (update :occurrences into (:occurrences derived))
          (update :programs inc)))))


(defn- consume
  "Advance the observer past every packet still on the medium, handing each
   to `f` while the indexer is available; a gap marks it lost."
  [indexer f]
  (loop [ix indexer]
    (let [{:keys [status batch] observer' :observer}
          (observer/observe-next (:observer ix))
          ix (assoc ix :observer observer')]
      (case status
        :ok (recur (if (or (:lost? ix) (:failure ix)) ix (f ix batch)))
        :gap (recur (assoc ix :lost? true))
        ix))))


(defn step
  "The round's AST index step: index every packet `program-out` still
   holds for this observer."
  [indexer]
  (consume indexer index-packet))


(defn skip
  "Advance the observer past every packet still on the medium without
   indexing it: a round that threw before its program ran indexes nothing.
   A gap still marks the indexer lost."
  [indexer]
  (consume indexer (fn [ix _packet] ix)))


(defn available?
  "Whether the relations are complete for every packet observed."
  [indexer]
  (not (or (:lost? indexer) (:failure indexer))))


(defn relations
  "`{:ast [row ...] :occ #{[root path node] ...}}` — the `$ast` rows, one
   per distinct address, and the `$occ` tuples — or nil when the indexer
   is lost or failed."
  [indexer]
  (when (available? indexer)
    {:ast (vec (vals (:rows indexer)))
     :occ (:occurrences indexer)}))


(defn notice
  "The warning every round must carry while the indexer is unavailable, or
   nil when it is healthy: a gap, or the refused packet's `:reason` (a
   failed derivation names its `:stage`).  The text never includes a host
   exception message, so it is identical on every host and VM."
  [indexer]
  (let [{:keys [reason stage]} (:failure indexer)]
    (cond
      (:lost? indexer)
      (str "Warning: the AST index lost a program batch;"
           " $ast and $occ are unavailable until (reset)")

      (:failure indexer)
      (str "Warning: the AST index refused a program ("
           (name (or reason stage))
           "); $ast and $occ are unavailable until (reset)"))))


(defn status
  "The AST indexer's state as the shell reports it.  It depends only on the
   expanded programs, never on which evaluator ran them, so every VM
   reports the same status for the same inputs."
  [indexer]
  {:programs (:programs indexer)
   :rows (count (:rows indexer))
   :occurrences (count (:occurrences indexer))
   :lost? (:lost? indexer)
   :gaps (:ingress-gaps (:observer indexer) 0)
   :failure (:failure indexer)})
