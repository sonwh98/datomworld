(ns yin.vm.content
  "Code content in `dao.jing` (U10, `docs/design/yin.vm.code-as-tuples.md`
   §2.1/§4.1; D3: individual rows, canonical — no pack format).

   A tree's rows materialize individually, each body `[tag & slots]` under
   its own row id, so shared subtrees are shared across trees at one
   address; a canonical instruction vector materializes as the exact
   vector under its `(jing/segment-key v)` address — per UCF §7.3.4 the
   payload at a code address *is* the vector, and the segment's
   `:yin.code/hash` is that address, recorded in the VM's alias column when
   `yin.vm.semantic/load-vector` loads what `fetch-vector` returned.

   `load-rows` is D3's BFS loader (`handle root → {:root :rows}`), the
   inverse of `materialize-tree!`; `fetch-vector` is the by-address loader.
   Both validate what they load — `vm/validate-rows` (§7.4) and
   `code/well-formed-vector?` (§7.5) respectively — and both verify every
   fetched payload hashes to the address it was read at. A metadata-bearing
   literal is either carried through by the backend (the memory backend
   stores values verbatim) or refused by it before the write (the file
   backend fails closed when its own text codec would not hash back); no
   store is left unopenable."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.code :as code]))


(def ^:private absent
  "Read sentinel, distinct from every storable payload (a conforming
   backend can neither store nor return it)."
  ::absent)


(defn- body-child-ids
  "The child row ids of one fetched body's `node`/`nodes` slots, by the
   grammar's own positions. Only valid segment addresses are followed: a
   slot holding something else stays in the row for `validate-rows` to name
   as its `:slot-kind` defect, exactly as it would in a hand-supplied row
   set."
  [body]
  (let [slots (get vm/semantic-bytecode-grammar (first body))]
    (when (and slots (= (count slots) (dec (count body))))
      (mapcat (fn [[_field kind] v]
                (case kind
                  :node (when (jing/segment-address? v) [v])
                  :nodes (filter jing/segment-address? v)
                  nil))
              slots (rest body)))))


(defn materialize-tree!
  "Materialize every row of one projected tree `{:root id, :rows {id row}}`
   individually under its own address (D3): each row's body
   `(subvec row 1)` through `jing/materialize!`, which derives exactly the
   row's id. The tree is validated (§7.4) first, so an invalid tree is
   refused before the first write. Returns the root row id — the tree's
   address (§4.1)."
  [handle {:keys [root rows] :as tree}]
  (when-let [{:keys [rule path id]} (vm/validate-rows tree)]
    (throw (ex-info "Cannot materialize rows that fail validation"
                    (cond-> {:rule rule}
                      path (assoc :path path)
                      id (assoc :id id)))))
  (doseq [row (vals rows)]
    (jing/materialize! handle (subvec row 1)))
  root)


(defn load-rows
  "D3's BFS loader: fetch the row set of the tree rooted at `root`, one
   `jing/get` per row, first-encounter breadth-first from the root row.
   Returns `{:root root, :rows {id [id tag & slots]}}` — the id is the
   address envelope the store hands back, prepended to each fetched body
   (§2.1).

   Every fetched body must hash to the address it was read at; an absent
   address in the closure throws naming it; the fetched set is then
   validated (§7.4), and a defect throws `ex-info` carrying its `:rule`
   and `:path` (or `:id`) exactly as `vm/semantic-bytecode->ast` reports
   one."
  [handle root]
  (loop [queue [root]
         index 0
         rows {}]
    (if (= index (count queue))
      (let [tree {:root root, :rows rows}]
        (when-let [{:keys [rule path id]} (vm/validate-rows tree)]
          (throw (ex-info "Semantic bytecode row set failed validation"
                          (cond-> {:rule rule}
                            path (assoc :path path)
                            id (assoc :id id)))))
        tree)
      (let [id (nth queue index)]
        (if (contains? rows id)
          (recur queue (inc index) rows)
          (let [body (jing/get handle id absent)]
            (cond
              (identical? body absent)
              (throw (ex-info "dao.jing content address resolves to no payload"
                              {:id id}))

              (not= id (jing/segment-key body))
              (throw (ex-info "dao.jing content does not hash to its address"
                              {:id id, :body body}))

              :else
              (recur (into queue (if (vector? body) (body-child-ids body) []))
                     (inc index)
                     (assoc rows id (if (vector? body)
                                      (into [id] body)
                                      [id body]))))))))))


(defn materialize-vector!
  "Materialize one canonical instruction vector as the exact payload at its
   own `(jing/segment-key v)` address (UCF §7.3.4: nothing else may hash
   there). The vector is validated (§7.5) first, so a malformed vector is
   refused before the write. Returns the address — the segment's
   `:yin.code/hash`."
  [handle v]
  (when-let [defect (code/well-formed-vector? v)]
    (throw (ex-info "Cannot materialize a vector that fails validation"
                    {:defect defect})))
  (jing/materialize! handle v))


(defn fetch-vector
  "Load one canonical instruction vector by address: `jing/get`, the
   payload verified to hash to the address it claims, then validated
   (§7.5). Returns `{:vector v, :address address}`; hand the vector to
   `yin.vm.semantic/load-vector` and the address is written to the image
   and its alias column — the segment's `:yin.code/hash`, earned."
  [handle address]
  (let [v (jing/get handle address absent)]
    (when (identical? v absent)
      (throw (ex-info "dao.jing content address resolves to no payload"
                      {:address address})))
    (when-not (= address (jing/segment-key v))
      (throw (ex-info "dao.jing content does not hash to its address"
                      {:address address, :value v})))
    (when-let [{:keys [rule pc]} (code/well-formed-vector? v)]
      (throw (ex-info (str "Cannot load vector: " (name rule)
                           " (pc " pc ")")
                      {:defect {:rule rule, :pc pc}})))
    {:vector v, :address address}))
