(ns yin.vm.linker.closure
  "The module closure walker (docs/design/yin.vm.linker.dht.md section
   4.2): a pure function over a `dao.jing` handle that answers whether
   every blob a module manifest needs -- the manifest, every row of its
   tree, its three derivation records, the three lowered images, and the
   closure of every required manifest -- is held locally and passes its
   shape and address checks.  It reads bytes through the handle with a
   sentinel, performs no stream operation, and never throws for absent or
   invalid content: the first absent blob ends the walk `:missing`, the
   first failed check `:invalid`.

   It verifies shape and address only.  It does not re-lower, discharge
   or judge names: `yin.vm.linker/link-manifest` does, afterwards, against
   the same store."
  (:require #?@(:cljd [["dart:typed_data" :as typed]])
            [dao.jing :as jing]
            [yin.vm.linker :as linker]))


(def lowered-formats
  "The derivation formats a manifest names, in walk order, each with its
   format record (for the image's identity check)."
  [[:yin.semantic/code linker/semantic-format]
   [:yin.debruijn.code linker/stack-format]
   [:yin.debruijn.register linker/register-format]])


(defn- byte-count
  [bs]
  #?(:cljd (.-length ^typed/Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn- stop
  "`st` ended by the terminal `outcome`."
  [st outcome]
  (assoc st :stop outcome))


(defn- missing
  [address role path]
  {:yin.link.closure/outcome :missing
   :address address :role role :path path})


(defn- invalid
  ([address role path code] (invalid address role path code nil))
  ([address role path code detail]
   {:yin.link.closure/outcome :invalid
    :address address :role role :path path
    :defect (cond-> {:code code} (some? detail) (assoc :detail detail))}))


(defn- read-blob
  "Read `address` as the walk's next blob: `[st value]`, or `[st' nil]`
   with `st'` stopped.  A blob read before is answered from the walk's
   own memory and counted once.  Order: absent, address, then the parts
   and bytes bounds, then decoding."
  [st address role path]
  (if (contains? (:seen st) address)
    [st (get (:seen st) address)]
    (let [{:keys [handle sentinel bounds parts bytes]} st
          bs (if (jing/segment-address? address)
               ((:get-bytes-fn handle) address sentinel)
               sentinel)]
      (cond
        (not (jing/segment-address? address))
        [(stop st (invalid address role path :address-mismatch)) nil]

        (identical? sentinel bs)
        [(stop st (missing address role path)) nil]

        (not (jing/segment-bytes-match? address bs))
        [(stop st (invalid address role path :address-mismatch)) nil]

        (>= parts (:max-parts bounds))
        [(stop st (invalid address role path :parts-limit {:bound :max-parts}))
         nil]

        (> (+ bytes (byte-count bs)) (:max-bytes bounds))
        [(stop st (invalid address role path :parts-limit {:bound :max-bytes}))
         nil]

        :else
        (let [value (try (jing/segment-value address bs)
                         (catch #?(:cljd Object :clj Throwable :cljs :default) _
                           sentinel))]
          (if (identical? sentinel value)
            [(stop st (invalid address role path :address-mismatch)) nil]
            [(-> st
                 (update :parts inc)
                 (update :bytes + (byte-count bs))
                 (assoc-in [:seen address] value))
             value]))))))


(defn- walk-rows
  "Step 2: a bounded worklist over the grammar's child slots from the
   tree root, each row checked row-locally before its children are
   followed."
  [st tree path]
  (loop [st st
         queue [[tree 0]]
         enqueued #{tree}]
    (if (empty? queue)
      st
      (let [[address depth] (first queue)]
        (if (> depth (get-in st [:bounds :max-depth]))
          (stop st (invalid address :row path :parts-limit {:bound :max-depth}))
          (let [[st body] (read-blob st address :row path)]
            (cond
              (:stop st) st

              (linker/row-local-defect body)
              (stop st (invalid address :row path :row-defect
                                (linker/row-local-defect body)))

              :else
              (let [kids (remove enqueued
                                 (distinct ((:parts-fn linker/ast-format) body)))]
                (recur st
                       (into (subvec queue 1)
                             (map (fn [k] [k (inc depth)]))
                             kids)
                       (into enqueued kids))))))))))


(defn- walk-records
  "Step 3: each derivation record the manifest names, checked as a
   record leading from the manifest's tree.  Answers `st` with the
   records under `:records`, keyed by format."
  [st manifest path]
  (reduce (fn [st [format-kw _]]
            (if-some [address (get (:yin.module/derivations manifest) format-kw)]
              (let [[st record] (read-blob st address :record path)]
                (cond
                  (:stop st) (reduced st)

                  (linker/record-defect record)
                  (reduced (stop st (invalid address :record path :record-defect
                                             (linker/record-defect record))))

                  (not= (:yin.module/tree manifest) (:yin.ledger/input record))
                  (reduced (stop st (invalid address :record path
                                             :derivation-mismatch
                                             {:expected (:yin.module/tree manifest)
                                              :actual (:yin.ledger/input record)})))

                  :else (assoc-in st [:records format-kw] record)))
              st))
          (assoc st :records {})
          lowered-formats))


(defn- walk-images
  "Step 4: the image each record's output identifies, at the identity
   itself for the semantic vector and at the manifest's index entry for H
   and R, held to its format's identity check."
  [st manifest path]
  (reduce (fn [st [format-kw record-format]]
            (if-some [record (get (:records st) format-kw)]
              (let [identity (:yin.ledger/output record)
                    address (if (= :yin.semantic/code format-kw)
                              identity
                              (get (:yin.module/index manifest) identity))]
                (if (nil? address)
                  (reduced (stop st (invalid nil :image path :index-entry-missing
                                             {:format format-kw
                                              :identity identity})))
                  (let [[st image] (read-blob st address :image path)]
                    (cond
                      (:stop st) (reduced st)

                      (not (try ((:identity-matches-fn record-format) identity image)
                                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                                  false)))
                      (reduced (stop st (invalid address :image path
                                                 :identity-mismatch
                                                 {:format format-kw
                                                  :identity identity})))

                      :else st))))
              st))
          st
          lowered-formats))


(declare walk-module)


(defn- walk-requires
  "Step 5: list every requirement of the module, then walk each required
   manifest not walked before, one level deeper."
  [st manifest-address manifest path depth]
  (reduce (fn [st [n pinned]]
            (let [st (update st :requires conj
                             {:module manifest-address :name n :manifest pinned})]
              (cond
                (contains? (:visited st) pinned) st

                (> (inc depth) (get-in st [:bounds :max-depth]))
                (reduced (stop st (invalid pinned :require (conj path pinned)
                                           :parts-limit {:bound :max-depth})))

                :else
                (let [st (walk-module st pinned :require path (inc depth))]
                  (if (:stop st) (reduced st) st)))))
          st
          (sort-by (fn [[n _]] (str n)) (:yin.module/requires manifest))))


(defn- walk-module
  [st manifest-address role path depth]
  (let [path (conj path manifest-address)
        [st manifest] (read-blob st manifest-address role path)]
    (cond
      (:stop st) st

      (linker/manifest-defect manifest)
      (stop st (invalid manifest-address role path :manifest-defect
                        (linker/manifest-defect manifest)))

      :else
      (let [st (-> st
                   (update :visited conj manifest-address)
                   (update :modules conj manifest-address)
                   (walk-rows (:yin.module/tree manifest) path))
            st (if (:stop st) st (walk-records st manifest path))
            st (if (:stop st) st (walk-images st manifest path))]
        (if (:stop st)
          st
          (walk-requires st manifest-address manifest path depth))))))


(defn walk
  "Walk the closure of `manifest-address` in `handle` (section 4.2), with
   `opts` `{:bounds {:max-parts n :max-depth d :max-bytes b}}`, every
   bound left out taking `yin.vm.linker/default-bounds`.  Answers exactly
   one of:

     {:yin.link.closure/outcome :missing
      :address a :role role :path [...]}
     {:yin.link.closure/outcome :complete
      :manifest m :blobs n :modules [m ...]
      :requires [{:module m :name n :manifest pinned} ...]}
     {:yin.link.closure/outcome :invalid
      :address a :role role :path [...] :defect {:code c :detail d}}

   `role` is `:manifest`, `:row`, `:record`, `:image` or `:require`;
   `path` the manifest addresses from the root to the module the blob
   belongs to.  `:code` is one of `:address-mismatch`, `:manifest-defect`,
   `:row-defect`, `:record-defect`, `:derivation-mismatch`,
   `:index-entry-missing`, `:identity-mismatch`, `:parts-limit`."
  ([handle manifest-address] (walk handle manifest-address nil))
  ([handle manifest-address opts]
   (let [bounds (into linker/default-bounds
                      (keep (fn [[k v]] (when (some? v) [k v])))
                      (:bounds opts))
         st (walk-module {:handle handle
                          :sentinel #?(:cljd (Object.) :clj (Object.) :cljs (js-obj))
                          :bounds bounds
                          :parts 0
                          :bytes 0
                          :seen {}
                          :visited #{}
                          :modules []
                          :requires []}
                         manifest-address :manifest [] 0)]
     (or (:stop st)
         {:yin.link.closure/outcome :complete
          :manifest manifest-address
          :blobs (count (:seen st))
          :modules (:modules st)
          :requires (:requires st)}))))
