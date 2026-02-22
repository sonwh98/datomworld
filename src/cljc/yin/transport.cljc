(ns yin.transport
  "Parallel transport: move AST datoms and continuations between DaoDB instances
   via content hashes. Entity IDs are local gauge; content hashes are invariant."
  (:require [yin.content :as content]))


;; =============================================================================
;; AST Transport
;; =============================================================================

(defn- find-root-eid
  "Find the single root entity: the entity not referenced by any other entity.
   Throws on zero or multiple roots (disconnected datoms)."
  [by-entity]
  (let [all-eids (set (keys by-entity))
        referenced
          (->> (vals by-entity)
               (mapcat (fn [entity-datoms]
                         (->> entity-datoms
                              (filter (fn [[_e _a _v _t m]] (= 0 m)))
                              (mapcat (fn [[_e a v _t _m]]
                                        (cond
                                          (contains? content/vector-ref-attrs a)
                                            (if (vector? v) v [v])
                                          (contains? content/ref-attrs a) [v]
                                          :else nil))))))
               (filter #(contains? all-eids %))
               set)
        roots (vec (remove referenced all-eids))]
    (when (not= 1 (count roots))
      (throw (ex-info "Expected exactly one root entity"
                      {:root-count (count roots), :roots roots})))
    (first roots)))


(defn export-ast
  "Export AST datoms as a gauge-invariant bundle.
   Returns {:bundle {hash -> {:av-pairs sorted-map :refs #{hash...}}}
            :root-hash \"sha256:...\"}."
  [datoms]
  (let [hashes (content/compute-content-hashes datoms)
        by-entity (group-by first datoms)
        root-eid (find-root-eid by-entity)
        bundle
          (reduce-kv
            (fn [b eid hash]
              (let [entity-datoms (get by-entity eid)
                    ;; Normalize: accumulate cardinality-many into vectors
                    ;; (handles both raw vector and materialized scalar
                    ;; shapes)
                    av-map (reduce (fn [m [_e a v _t _m]]
                                     (if (contains? content/vector-ref-attrs a)
                                       (let [vals (if (vector? v) v [v])]
                                         (update m a (fnil into []) vals))
                                       (assoc m a v)))
                             {}
                             (filter (fn [[_e _a _v _t m]] (= 0 m))
                               entity-datoms))
                    ;; Resolve refs to content hashes
                    resolved (into (sorted-map)
                                   (map (fn [[a v]]
                                          [a
                                           (content/resolve-value a v hashes)]))
                                   av-map)
                    ;; Extract ref hashes for dependency tracking
                    refs (->> resolved
                              (mapcat (fn [[a v]]
                                        (cond
                                          (contains? content/vector-ref-attrs a)
                                            (if (vector? v) v [v])
                                          (contains? content/ref-attrs a) [v]
                                          :else [])))
                              (filter string?)
                              set)]
                (assoc b hash {:av-pairs resolved, :refs refs})))
            {}
            hashes)]
    {:bundle bundle, :root-hash (get hashes root-eid)}))


(defn import-ast
  "Import an AST bundle into local entity space.
   existing-hash->eid: map of {hash -> eid} for hashes already present locally.
   Returns {:datoms [...] :root-eid int :hash->eid {hash -> eid}}."
  [{:keys [bundle root-hash]} id-start existing-hash->eid]
  (let [existing (or existing-hash->eid {})
        hash->eid (atom (select-keys existing (keys bundle)))
        id-counter (atom id-start)
        gen-id #(let [id @id-counter]
                  (swap! id-counter dec)
                  id)
        result-datoms (atom [])
        remaining (atom bundle)
        resolved (atom (set (keys existing)))]
    ;; Topo-sort: process nodes whose refs are all resolved
    (loop []
      (when (seq @remaining)
        (let [ready (into []
                          (filter (fn [[_hash {:keys [refs]}]]
                                    (every? @resolved refs)))
                          @remaining)]
          (when (empty? ready)
            (throw (ex-info "Cyclic dependency in AST bundle"
                            {:remaining (keys @remaining)})))
          (doseq [[hash {:keys [av-pairs]}] ready]
            (if (contains? existing hash)
              ;; Already exists locally, just mark resolved
              nil
              ;; Create new entity
              (let [eid (gen-id)]
                (swap! hash->eid assoc hash eid)
                (doseq [[a v] av-pairs]
                  (let [resolved-v (cond (contains? content/vector-ref-attrs a)
                                           (if (vector? v)
                                             (mapv #(get @hash->eid % %) v)
                                             (get @hash->eid v v))
                                         (contains? content/ref-attrs a)
                                           (get @hash->eid v v)
                                         :else v)]
                    (swap! result-datoms conj [eid a resolved-v 0 0])))))
            (swap! remaining dissoc hash)
            (swap! resolved conj hash)))
        (recur)))
    {:datoms @result-datoms,
     :root-eid (get @hash->eid root-hash),
     :hash->eid @hash->eid}))


;; =============================================================================
;; Continuation Transport
;; =============================================================================

(declare replace-frame-refs restore-frame-refs)


(defn- replace-closure-refs
  "Walk a value, replacing closure :body-node entity refs with content hashes."
  [val hash-cache]
  (cond (and (map? val) (= :closure (:type val)))
          (let [body-hash (get hash-cache (:body-node val))]
            (-> val
                (assoc :body-hash body-hash)
                (dissoc :body-node :datoms)
                (update :env
                        #(reduce-kv (fn [m k v]
                                      (assoc m
                                        k (replace-closure-refs v hash-cache)))
                                    {}
                                    %))))
        (and (map? val) (= :reified-continuation (:type val)))
          (-> val
              (update :stack
                      #(mapv (fn [f] (replace-frame-refs f hash-cache)) %))
              (update :env
                      #(reduce-kv (fn [m k v]
                                    (assoc m
                                      k (replace-closure-refs v hash-cache)))
                                  {}
                                  %)))
        :else val))


(defn- replace-env-refs
  "Rewrite closures in an environment map, replacing body-node refs with hashes."
  [env hash-cache]
  (reduce-kv (fn [m k v] (assoc m k (replace-closure-refs v hash-cache)))
             {}
             env))


(defn- replace-frame-refs
  "Replace entity refs in a continuation frame with content hashes."
  [frame hash-cache]
  (let [resolve-eid #(get hash-cache % %)
        resolve-eids #(mapv resolve-eid %)
        rewrite-env #(replace-env-refs % hash-cache)]
    (case (:type frame)
      :app-op (-> frame
                  (update :operands resolve-eids)
                  (update :env rewrite-env))
      :app-args (-> frame
                    (update :fn #(replace-closure-refs % hash-cache))
                    (update :pending resolve-eids)
                    (update :env rewrite-env))
      :if (-> frame
              (update :cons resolve-eid)
              (update :alt resolve-eid)
              (update :env rewrite-env))
      :restore-env (update frame :env rewrite-env)
      ;; Stream frames: :stream-put-target has :val-node (AST ref)
      :stream-put-target (-> frame
                             (update :val-node resolve-eid)
                             (update :env rewrite-env))
      ;; Stream frames with :env only (no AST refs)
      (:stream-put-val :stream-cursor-source
                       :stream-next-cursor
                       :stream-close-source)
        (update frame :env rewrite-env)
      ;; Unknown frames pass through
      frame)))


(defn export-continuation
  "Export a parked continuation with AST refs replaced by content hashes.
   parked-cont: {:type :parked-continuation, :id id, :stack [...], :env {...}}
   hash-cache: {eid -> \"sha256:...\"} from compute-content-hashes."
  [parked-cont hash-cache]
  (let [{:keys [stack env]} parked-cont]
    {:type :exported-continuation,
     :stack (mapv #(replace-frame-refs % hash-cache) stack),
     :env (reduce-kv (fn [m k v]
                       (assoc m k (replace-closure-refs v hash-cache)))
                     {}
                     env)}))


(defn- restore-closure-refs
  "Walk a value, restoring closure :body-node from content hashes."
  [val hash->eid datoms]
  (cond (and (map? val) (= :closure (:type val)))
          (let [body-eid (get hash->eid (:body-hash val))]
            (-> val
                (assoc :body-node body-eid
                       :datoms datoms)
                (dissoc :body-hash)
                (update :env
                        #(reduce-kv
                           (fn [m k v]
                             (assoc m
                               k (restore-closure-refs v hash->eid datoms)))
                           {}
                           %))))
        (and (map? val) (= :reified-continuation (:type val)))
          (->
            val
            (update :stack
                    #(mapv (fn [f] (restore-frame-refs f hash->eid datoms)) %))
            (update :env
                    #(reduce-kv
                       (fn [m k v]
                         (assoc m k (restore-closure-refs v hash->eid datoms)))
                       {}
                       %)))
        :else val))


(defn- restore-env-refs
  "Restore closures in an environment map, resolving body hashes to local eids."
  [env hash->eid datoms]
  (reduce-kv (fn [m k v] (assoc m k (restore-closure-refs v hash->eid datoms)))
             {}
             env))


(defn- restore-frame-refs
  "Restore entity refs in a continuation frame from content hashes."
  [frame hash->eid datoms]
  (let [resolve-hash #(get hash->eid % %)
        resolve-hashes #(mapv resolve-hash %)
        rewrite-env #(restore-env-refs % hash->eid datoms)]
    (case (:type frame)
      :app-op (-> frame
                  (update :operands resolve-hashes)
                  (update :env rewrite-env))
      :app-args (-> frame
                    (update :fn #(restore-closure-refs % hash->eid datoms))
                    (update :pending resolve-hashes)
                    (update :env rewrite-env))
      :if (-> frame
              (update :cons resolve-hash)
              (update :alt resolve-hash)
              (update :env rewrite-env))
      :restore-env (update frame :env rewrite-env)
      ;; Stream frames
      :stream-put-target (-> frame
                             (update :val-node resolve-hash)
                             (update :env rewrite-env))
      (:stream-put-val :stream-cursor-source
                       :stream-next-cursor
                       :stream-close-source)
        (update frame :env rewrite-env)
      frame)))


(defn import-continuation
  "Import an exported continuation, resolving content hashes to local entity IDs.
   exported: result of export-continuation
   hash->eid: {hash -> eid} from import-ast
   datoms: local datom set for closures."
  [exported hash->eid datoms]
  (let [{:keys [stack env]} exported]
    {:type :parked-continuation,
     :stack (mapv #(restore-frame-refs % hash->eid datoms) stack),
     :env (reduce-kv (fn [m k v]
                       (assoc m k (restore-closure-refs v hash->eid datoms)))
                     {}
                     env)}))
