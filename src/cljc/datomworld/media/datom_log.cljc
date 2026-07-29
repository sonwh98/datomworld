(ns datomworld.media.datom-log
  "Projects player stream values into an ephemeral canonical d5 feed.")


(def ^:private excluded-attributes
  #{:media/object-url :media/file :media/bytes})


(defn- scalar
  [value]
  (cond
    (nil? value) :datom/nil
    (or (string? value)
        (number? value)
        (keyword? value)
        (boolean? value))
    value
    :else nil))


(defn- facts
  [attribute value]
  (cond
    (contains? excluded-attributes attribute)
    []

    (map? value)
    (mapcat (fn [[nested-attribute nested-value]]
              (facts nested-attribute nested-value))
            (sort-by (comp str key) value))

    (sequential? value)
    (mapcat #(facts attribute %) value)

    :else
    (if-some [value* (scalar value)]
      [[attribute value*]]
      [])))


(defn value->datoms
  "Convert a stream value into immutable [e a v t m] facts.
   Entity IDs are local tempids and m=1 means assertion."
  [transaction origin value]
  (let [entity (- (+ 1025 transaction))
        fact-pairs (if (map? value)
                     (mapcat (fn [[attribute fact-value]]
                               (facts attribute fact-value))
                             (sort-by (comp str key) value))
                     (facts :stream/value value))]
    (mapv (fn [[attribute fact-value]]
            [entity attribute fact-value transaction 1])
          (cons [:stream/origin origin] fact-pairs))))


(defn changes->datoms
  "Emit only the player facts changed by one Yin.VM transition."
  [transaction before after]
  (let [changed (into {}
                      (filter (fn [[attribute value]]
                                (not= value (get before attribute))))
                      after)]
    (value->datoms transaction :yin-state changed)))
