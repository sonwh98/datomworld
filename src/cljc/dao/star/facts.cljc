(ns dao.star.facts
  "The session-facts grammar of dao.star Mode 1 (docs/design/dao.star.md,
   Sessions and request kinds), as pure functions.

   Three facts are written, only through the constructors here: a lineage's
   root, a consumption (one transition of one lineage), and the end of a
   lineage.  `valid-fact?` guards readers: anything else on the medium (lease
   renewals, foreign values) is noise to be ignored, never thrown on.

   `index-step` folds one fact, or one lineage-lease record, into the
   occurrence -> lineage -> current index a router materializes.  The index is
   derived state, rebuilt from the media; nothing here keeps a table.")


(defn root
  "A lineage's first fact: its id is its root occurrence's id."
  [lineage]
  {:dao.star/lineage lineage :dao.star/occurrence lineage})


(defn consumption
  "One transition: `successor` replaces `predecessor` as the lineage's
   current occurrence."
  [lineage successor predecessor]
  {:dao.star/lineage lineage
   :dao.star/occurrence successor
   :dao.star/predecessor predecessor})


(defn ended
  "The lineage's program finished (`reason`, a keyword such as :halted)."
  [lineage reason]
  {:dao.star/lineage lineage :dao.star/ended reason})


(defn- id?
  [x]
  (and (string? x) (not (empty? x))))


(defn fact-kind
  "`:root`, `:consumption`, `:ended`, or nil for anything else.  Facts have
   exactly the keys the constructors produce; a value with more is not one."
  [x]
  (when (map? x)
    (let [ks (set (keys x))
          lineage (:dao.star/lineage x)
          occurrence (:dao.star/occurrence x)]
      (cond
        (not (id? lineage)) nil

        (and (= ks #{:dao.star/lineage :dao.star/occurrence})
             (= lineage occurrence))
        :root

        (and (= ks #{:dao.star/lineage :dao.star/occurrence :dao.star/predecessor})
             (id? occurrence)
             (id? (:dao.star/predecessor x))
             (not= occurrence (:dao.star/predecessor x)))
        :consumption

        (and (= ks #{:dao.star/lineage :dao.star/ended})
             (keyword? (:dao.star/ended x)))
        :ended

        :else nil))))


(defn valid-fact?
  [x]
  (some? (fact-kind x)))


(def empty-index
  {:occurrence {}    ; occurrence -> lineage
   :current {}       ; lineage -> its current occurrence
   :ended #{}        ; lineages that are over (halted or lapsed)
   :leases {}})      ; lease id -> lineage, from lineage-lease grants


(defn- lineage-subject
  [fact]
  (let [s (:dao.lease/subject fact)]
    (when (and (map? s) (id? (:dao.star/lineage s)))
      (:dao.star/lineage s))))


(defn index-step
  "Fold one read value into the index.  A valid session fact advances it; a
   lineage-lease `:accepted` records which lineage a lease is for and a
   `:lapsed` ends that lineage for routing; anything else, and any fact that
   does not follow from the index (a consumption whose predecessor is not
   current), changes nothing."
  [index x]
  (case (fact-kind x)
    :root (let [s (:dao.star/lineage x)]
            (if (contains? (:current index) s)
              index
              (-> index
                  (assoc-in [:current s] s)
                  (assoc-in [:occurrence s] s))))

    :consumption (let [s (:dao.star/lineage x)
                       o2 (:dao.star/occurrence x)]
                   (if (and (= (:dao.star/predecessor x) (get-in index [:current s]))
                            (not (contains? (:occurrence index) o2)))
                     (-> index
                         (assoc-in [:current s] o2)
                         (assoc-in [:occurrence o2] s))
                     index))

    :ended (update index :ended conj (:dao.star/lineage x))

    (cond
      (and (map? x)
           (= :dao.lease/accepted (:dao.lease/status x))
           (lineage-subject x)
           (some? (:dao.lease/lease x)))
      (assoc-in index [:leases (:dao.lease/lease x)] (lineage-subject x))

      (and (map? x)
           (= :dao.lease/lapsed (:dao.lease/status x)))
      (if-let [s (get-in index [:leases (:dao.lease/lease x)])]
        (update index :ended conj s)
        index)

      :else index)))


(defn resolve-token
  "What a token names under `index`:
     {:kind :current :lineage S}   the lineage's current occurrence
     {:kind :consumed :lineage S}  an occurrence that has a successor
     {:kind :ended :lineage S}     a token of a halted or lapsed lineage
     {:kind :unknown}              never seen"
  [index token]
  (if-let [s (get-in index [:occurrence token])]
    (cond
      (contains? (:ended index) s) {:kind :ended :lineage s}
      (= token (get-in index [:current s])) {:kind :current :lineage s}
      :else {:kind :consumed :lineage s})
    {:kind :unknown}))
