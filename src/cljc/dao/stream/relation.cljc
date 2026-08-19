(ns dao.stream.relation
  "DaoStream relation descriptor implementation for read-only closed exact-bound inline relations."
  (:require [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


(defn relation-bound
  "Explicit deterministic plain-data bound constructor for relation stream descriptors.
   Includes exact tuples vector and count."
  [tuples]
  {:dao.stream.bound/type :exact-tuples,
   :tuples (vec tuples),
   :count (count tuples)})


(declare validate-relation-descriptor!)


(defn relation-descriptor
  "Constructs a valid :dao.stream/relation descriptor with explicit deterministic bound."
  ([tuples]
   (let [t-vec (vec tuples)]
     {:dao.stream/type :dao.stream/relation,
      :tuples t-vec,
      :dao.stream/bound (relation-bound t-vec)}))
  ([tuples bound]
   (let [t-vec (vec tuples)
         descriptor {:dao.stream/type :dao.stream/relation,
                     :tuples t-vec,
                     :dao.stream/bound bound}]
     (validate-relation-descriptor! descriptor)
     descriptor)))


(defn validate-relation-descriptor!
  "Validates that descriptor has a non-nil :dao.stream/bound matching its :tuples."
  [descriptor]
  (let [bound (:dao.stream/bound descriptor)]
    (when (nil? bound)
      (throw (ex-info "Missing :dao.stream/bound for relation stream descriptor"
                      {:descriptor descriptor})))
    (let [tuples (:tuples descriptor)
          expected (relation-bound tuples)]
      (when-not (= bound expected)
        (throw (ex-info "Bound mismatch for relation stream descriptor"
                        {:descriptor descriptor,
                         :expected expected,
                         :actual bound}))))))


(defrecord RelationStream
  [descriptor tuples]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [pos (or (:position cursor) 0)]
      (if (and (integer? pos) (>= pos 0) (< pos (count tuples)))
        {:ok (nth tuples pos), :cursor {:position (inc pos)}}
        :end)))


  ds/IDaoStreamBound

  (close! [_this] {:woke []})


  (closed? [_this] true))


(defn make-relation-stream
  "Validates and realizes a :dao.stream/relation descriptor into an operational RelationStream."
  [descriptor]
  (validate-relation-descriptor! descriptor)
  (let [tuples (vec (:tuples descriptor))]
    (with-meta (->RelationStream descriptor tuples)
      {:dao.stream/descriptor descriptor})))


(ds/defopen :dao.stream/relation [descriptor] (make-relation-stream descriptor))
