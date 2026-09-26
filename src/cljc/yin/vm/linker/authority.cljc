(ns yin.vm.linker.authority
  "Section 8.2 of docs/design/yin.vm.linker.md: the fail-closed,
   proof-carrying name-authority policy behind the linker's name
   environment. `name-environment` is a pure function over plain data:
   it folds an authority map and a sequence of authority events
   (assertion and retraction envelopes with their proofs) into a
   name-environment snapshot plus diagnostics, and touches no stream,
   no format record, no fetch path, and no kernel. The only
   dependencies are the committed `dao.jing` content identities
   (`segment-key`, `canonical-bytes`) and the composition-supplied
   verify function the authority map carries: no cryptographic
   primitive is implemented here.

  Envelope shapes, the three per-principal passes, the retraction
  binding, and the per-name fold are exactly the section 8.2 policy:
  shape-validated envelopes by declared principals are honored only
  with a valid proof; duplicates collapse by content id; equal
  sequences from one principal are equivocation; sequences at or below
  the floor are replays; a retraction removes exactly the assertion
  its :yin.module/of names; and more than one remaining manifest
  address for a name refuses :ambiguous-name with no tie-break rule."
  (:require [dao.jing :as jing]))


;; =============================================================================
;; Envelopes and content ids (section 8.2)
;; =============================================================================

(defn assertion-id
  "The content address of an authority envelope: its `dao.jing`
   segment-key. This is the id a retraction's :yin.module/of names."
  [env]
  (jing/segment-key env))


(defn- envelope-defect
  "The first shape defect of env, or nil when env is a well-formed
   assertion or retraction envelope. Manifest and retraction target
   are checked by `dao.jing/segment-address?`, the authoritative
   closed-registry address check."
  [env]
  (if (map? env)
    (case (get env :yin.module/op)
      :assert (cond
                (not (symbol? (get env :yin.module/name))) :bad-name
                (not (jing/segment-address?
                       (get env :yin.module/manifest))) :bad-manifest
                (nil? (get env :yin.module/asserted-by)) :bad-asserted-by
                :else (if (int? (get env :yin.module/seq)) nil :bad-seq))
      :retract (cond
                 (not (jing/segment-address?
                        (get env :yin.module/of))) :bad-of
                 (nil? (get env :yin.module/asserted-by)) :bad-asserted-by
                 :else (if (int? (get env :yin.module/seq)) nil :bad-seq))
      :bad-op)
    :not-an-envelope))


;; =============================================================================
;; Datom ingestion (section 8.2)
;; =============================================================================

(defn events-from-datoms
  "The section 8.2 authority event sequence, assembled from the
   assertion datoms a composition read at its snapshot: every
   `[ev :yin.module/envelope env]` datom yields one event whose proof is
   the one `[ev :yin.module/proof proof]` value of the same entity. An
   entity carrying two distinct proof values has no proof: none is
   picked, so the event is the no-proof case, whatever the datom order.
   An envelope datom with no proof datom is the no-proof case the policy
   discards as :unauthenticated; a proof datom with no envelope datom
   names no event and is ignored -- section 8.2's diagnostic kinds are a
   closed set, and an orphan proof is no envelope at all. `carrier` is
   the :dao.stream/identity of the one stream the composition read at
   the snapshot, marked on every event for the attested check; nil means
   the read names no stream, and an attested proof fails :bad-proof
   there. Events keep the handed datoms' order, and the datoms are
   already snapshot-bounded by the reader: nothing here reads or
   advances a snapshot itself."
  ([datoms] (events-from-datoms datoms nil))
  ([datoms carrier]
   (let [proof-of (reduce-kv
                    (fn [acc e vs]
                      (cond-> acc
                        (= 1 (count vs)) (assoc e (first vs))))
                    {}
                    (reduce (fn [acc [e a v]]
                              (cond-> acc
                                (= a :yin.module/proof)
                                (update e (fnil conj #{}) v)))
                            {}
                            datoms))]
     (into []
           (keep (fn [[e a v]]
                   (when (= a :yin.module/envelope)
                     (cond-> {:yin.module/envelope v}
                       (contains? proof-of e)
                       (assoc :yin.module/proof (get proof-of e))
                       (some? carrier)
                       (assoc :dao.stream/identity carrier)))))
           datoms))))


;; =============================================================================
;; Authentication: the two proof kinds (section 8.2)
;; =============================================================================

(defn- proof-state
  "nil when the event's proof is valid for the principal's declaration,
   else the authentication failure: :no-proof, :bad-proof, or
   :no-proof-kind when the declaration admits neither proof kind and
   the principal cannot assert anything. The verify function's answer
   is taken as given; it is composition-supplied host code and is
   expected not to throw."
  [decl event]
  (let [proof (get event :yin.module/proof)]
    (case (get decl :proof)
      :yin.module/signature
      (if (map? proof)
        (let [sig (get proof :yin.module/signature)]
          (if (nil? sig)
            :no-proof
            (let [verify (get decl :verify)]
              (when-not (and verify
                             (verify (get decl :key)
                                     (jing/canonical-bytes
                                       (get event :yin.module/envelope))
                                     sig))
                :bad-proof))))
        :no-proof)
      :yin.module/attested
      (if (map? proof)
        (let [declared (get decl :dao.stream/identity)]
          (if (nil? (get proof :yin.module/attested))
            :no-proof
            ;; the envelope must have been read from the declared log:
            ;; copied onto another stream it carries no proof
            (if (or (not= declared (get proof :yin.module/attested))
                    (not= declared (get event :dao.stream/identity)))
              :bad-proof
              nil)))
        :no-proof)
      :no-proof-kind)))


(defn- discard-reason
  "The section 8.2 discard reason of an authentication failure kind."
  [kind]
  (case kind
    :no-proof :unauthenticated
    :bad-proof :unauthenticated
    :no-proof-kind :undeclared-principal
    kind))


(defn- env-diagnostic
  "The diagnostic entry for one discarded envelope."
  [kind reason env extra]
  (merge {:kind kind
          :reason reason
          :id (when (some? env) (assertion-id env))
          :op (when (map? env) (get env :yin.module/op))
          :principal (when (map? env) (get env :yin.module/asserted-by))
          :seq (when (map? env) (get env :yin.module/seq))}
         extra))


;; =============================================================================
;; The three per-principal passes (section 8.2)
;; =============================================================================

(defn- honor-passes
  "The three passes over one principal's proven envelopes: deduplicate
   by content id (duplicate appearances beyond the first drop
   silently), detect equivocation on equal sequence (every envelope of
   the principal from the first equivocating sequence on is discarded
   as equivocation), then order by sequence and honor each envelope
   only past the floor and every sequence honored before it. Returns
   {:honored [...] :diagnostics [...]}."
  [decl proven]
  (let [deduped (::kept
                  (reduce (fn [acc cand]
                            (if (contains? (::seen acc) (:id cand))
                              acc
                              (-> acc
                                  (update ::kept conj cand)
                                  (update ::seen conj (:id cand)))))
                          {::kept [] ::seen #{}}
                          proven))
        by-seq (group-by :seq deduped)
        eq-min (when-let [eqs (seq (keep (fn [[s cands]]
                                           (when (> (count cands) 1) s))
                                         by-seq))]
                 (reduce min eqs))
        {kept :kept equated :equated}
        (if eq-min
          (group-by (fn [cand]
                      (if (>= (:seq cand) eq-min)
                        :equated :kept))
                    deduped)
          {:kept deduped})
        floor (or (get decl :seq-floor) 0)
        done (reduce (fn [acc cand]
                       (if (> (:seq cand) (::last-honored acc))
                         (-> acc
                             (assoc ::last-honored (:seq cand))
                             (update ::honored conj cand))
                         (update acc ::replayed conj cand)))
                     {::last-honored floor ::honored [] ::replayed []}
                     (sort-by :seq (or kept [])))]
    {:honored (::honored done)
     :diagnostics
     (into (mapv (fn [cand]
                   (env-diagnostic :equivocation :equivocation
                                   (:env cand) nil))
                 (or equated []))
           (mapv (fn [cand]
                   (env-diagnostic :replay :replay (:env cand) nil))
                 (::replayed done)))}))


;; =============================================================================
;; The per-name fold (section 8.2)
;; =============================================================================

(defn- name-entry
  "The outcome for one name: :absent for zero remaining assertions, a
   successful resolution with provenance for exactly one distinct
   manifest address, :ambiguous-name naming every remaining address
   and asserter for more than one. Entries are ordered by address, so
   the outcome never depends on carrier order, recency, or sequence."
  [decls snapshot cands]
  (let [entries (sort-by (fn [e] (str (:address e) " " (:asserter e)))
                         (distinct
                           (map (fn [cand]
                                  {:address (get (:env cand)
                                                 :yin.module/manifest)
                                   :asserter (:principal cand)})
                                cands)))
        addresses (distinct (map :address entries))
        asserters (distinct (map :asserter entries))]
    (case (count addresses)
      0 {:status :refused :reason :absent}
      1 {:status :ok
         :address (first addresses)
         :yin.link/provenance
         {:yin.module/asserted-by (vec asserters)
          :yin.link/proof-kind
          (vec (distinct (map (fn [p] (get (get decls p) :proof))
                              asserters)))
          :yin.link/snapshot snapshot}}
      {:status :refused
       :reason :ambiguous-name
       :addresses (vec addresses)
       :asserters (vec asserters)})))


(defn name-environment
  "Fold `events` under `authority` into the name-environment snapshot
   of section 8.2.

   `authority` is a map with the snapshot under :snapshot (an index
   manifest address or a stream cursor; it is echoed and never
   dereferenced here) and the accepted principals under :principals.
   A signature principal declares {:proof :yin.module/signature
   :key k :verify f}; f is composition-supplied and is called as
   (f key canonical-bytes sig). An attested principal declares
   {:proof :yin.module/attested :dao.stream/identity id} and every
   envelope must have been read from that stream. Either may carry
   :seq-floor, the highest sequence already honored for a re-declared
   principal (0 for a new one).

   Each event is a map: :yin.module/envelope, :yin.module/proof, and,
   for the attested check, the :dao.stream/identity the envelope was
   read from. An envelope whose proof is absent or fails is
   :unauthenticated and is discarded before counting.

   Returns {:names {name entry} :diagnostics [...] :honored-seq
   {principal highest-honored-sequence} :snapshot s}. Advancing the
   snapshot is a fresh call with the advanced authority and events
   (the next floor is :honored-seq), never an ambient re-read."
  [authority events]
  (let [decls (:principals authority)
        {stage1 :diagnostics proven :proven name-set :names}
        (reduce
          (fn [acc event]
            (let [env (when (map? event)
                        (get event :yin.module/envelope))]
              (if-some [defect (envelope-defect env)]
                (update acc :diagnostics conj
                        (env-diagnostic :malformed-envelope
                                        :malformed-envelope
                                        env {:defect defect}))
                (let [principal (get env :yin.module/asserted-by)
                      acc (if (= :assert (get env :yin.module/op))
                            (update acc :names conj
                                    (get env :yin.module/name))
                            acc)]
                  (if-not (contains? decls principal)
                    (update acc :diagnostics conj
                            (env-diagnostic :undeclared-principal
                                            :undeclared-principal
                                            env nil))
                    (if-some [failed (proof-state (get decls principal)
                                                  event)]
                      (update acc :diagnostics conj
                              (env-diagnostic failed
                                              (discard-reason failed)
                                              env nil))
                      (update acc :proven conj
                              {:env env
                               :id (assertion-id env)
                               :principal principal
                               :seq (get env :yin.module/seq)})))))))
          {:diagnostics [] :proven [] :names #{}}
          events)
        per-principal
        (mapv (fn [principal]
                (let [mine (filter (fn [cand]
                                     (= principal (:principal cand)))
                                   proven)
                      {:keys [honored diagnostics]}
                      (honor-passes (get decls principal) mine)]
                  {:principal principal
                   :honored honored
                   :diagnostics diagnostics}))
              (distinct (mapv :principal proven)))
        honored (mapcat :honored per-principal)
        {assertions true retractions false}
        (group-by (fn [cand]
                    (= :assert (get (:env cand) :yin.module/op)))
                  honored)
        by-principal (group-by :principal assertions)
        {retracted true dangling false}
        (group-by (fn [cand]
                    (contains?
                      (set (map :id (get by-principal
                                         (:principal cand))))
                      (get (:env cand) :yin.module/of)))
                  (or retractions []))
        retracted-ids (set (map (fn [cand]
                                  (get (:env cand) :yin.module/of))
                                (or retracted [])))
        surviving (remove (fn [cand]
                            (contains? retracted-ids (:id cand)))
                          (or assertions []))
        by-name (group-by (fn [cand]
                            (get (:env cand) :yin.module/name))
                          surviving)]
    {:names (into {}
                  (map (fn [nm]
                         [nm (name-entry decls (:snapshot authority)
                                         (get by-name nm))]))
                  name-set)
     :diagnostics (vec (concat stage1
                               (mapcat :diagnostics per-principal)
                               (map (fn [cand]
                                      (env-diagnostic
                                        :dangling-retraction
                                        :dangling-retraction
                                        (:env cand)
                                        {:of (get (:env cand)
                                                  :yin.module/of)}))
                                    (or dangling []))))
     :honored-seq (into {}
                        (keep (fn [pr]
                                (when (seq (:honored pr))
                                  [(:principal pr)
                                   (reduce max (map :seq (:honored pr)))])))
                        per-principal)
     :snapshot (:snapshot authority)}))
