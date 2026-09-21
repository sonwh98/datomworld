(ns yin.vm.pipeline
  "D5 of the de Bruijn projection (docs/design/yin.vm.debruijn-projection.md
   §6-§7-D5, §9): the post-emission orchestration that wires Yang's output
   to projected persistence.

   `persist-compiled!` is the composition adapter the architect's D5
   clearance named: it emits the named :yin/* datom batch itself (or takes
   a pre-emitted one), persists the NAMED batch first through the caller's
   named writer, and only after named persistence succeeds materializes
   the canonical projected envelope into a separate DaoJing content store.
   The two sides report independently: a projection failure is a
   :diagnostic on the projected side and never fails the named side; a
   named failure means projected persistence is not attempted.

   Ordering: only one COMPLETE root-framed batch is ever projected — never
   a mid-emission prefix — and framing and index state are per batch, so
   adjacent calls reusing the emitter's -16-based tempids project
   independently. The batch passes the stream path's own framing gate,
   `debruijn/frame-datoms`, before either side persists: a partial frame,
   a malformed or retract datom, or anything but exactly one complete
   frame rejects the whole call.

   Dedupe is DaoJing's write idempotence alone: `dao.jing/materialize!`
   inserts or finds :present, verifies the read-back, and never
   overwrites; there is no pre-write lookup, and named artifacts are never
   deduplicated by fingerprint. The result carries both identities — the
   physical DaoJing segment address of the envelope and the semantic
   Merkle fingerprint inside it. The physical address comes from
   DaoJing's transitional print-based hash and is portable only between
   implementations sharing that print rule; the Merkle fingerprint is the
   cross-host identity.

   Nothing here edits or is consumed by named consumers or runtime
   layers: no yang.* compiler, emitter, encoder, VM, linearizer,
   dao.stream, lease, or waitset code reads anything this namespace
   writes."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.debruijn :as debruijn]))


(defn projected-envelope
  "The canonical projected persistence value: the Merkle fingerprint and
   the projected d5 datoms as one plain-data envelope, the exact value
   `dao.jing/materialize!` content-addresses. The fingerprint inside is
   the semantic identity; the segment address the store returns for this
   envelope is the physical one."
  [{:keys [fingerprint records]}]
  {:yin.debruijn/fingerprint fingerprint,
   :yin.debruijn/datoms (debruijn/projected->datoms {:fingerprint fingerprint,
                                                     :records records})})


(defn- batch-of
  "The complete named batch for one request: emitted from :ast through
   `vm/ast->datoms-with-root` — always complete, the root marker last —
   or taken pre-emitted from :datoms, the path on which the complete-batch
   rule is observable (an :ast cannot emit a partial batch). Exactly one
   of the two; both or neither is a caller defect."
  [request]
  (let [ast? (some? (:ast request))
        datoms? (some? (:datoms request))]
    (cond
      (and ast? datoms?)
      (throw (ex-info "persist-compiled! takes :ast or :datoms, not both"
                      {:rule :bad-request, :request-keys (vec (keys request))}))

      ast? (second (vm/ast->datoms-with-root (:ast request)))
      datoms? (vec (:datoms request))
      :else (throw (ex-info "persist-compiled! requires :ast or :datoms"
                            {:rule :bad-request, :request-keys (vec (keys request))})))))


(defn- framing-defect
  "The batch's framing defect under the stream path's own gate
   (`debruijn/frame-datoms`), or nil when it is exactly one complete
   root-framed graph. The defect is {:rule …}: frame-datoms' own rule —
   :partial-frame, :malformed-datom, :retract — :missing-root for a batch
   with no frame at all, or :multiple-frames for several."
  [datoms]
  (try
    (let [frames (debruijn/frame-datoms datoms)]
      (case (count frames)
        1 nil
        0 {:rule :missing-root}
        {:rule :multiple-frames, :frames (count frames)}))
    (catch #?(:cljd Object :clj Throwable :cljs :default) t
      ;; an internal defect in the gate itself carries :rule :internal-error
      ;; and nothing more, like every other rejection
      {:rule (:rule (:diagnostic (debruijn/exception-diagnostic t)))})))


(defn- persist-projected!
  "The projected side in isolation: project the complete batch, then
   materialize the canonical envelope. A projection diagnostic is
   {:outcome :diagnostic, :diagnostic …}; an internal defect in the
   projection or in building its envelope — both run before any store
   call — is {:outcome :internal-error, :diagnostic …}; a store
   failure is a :diagnostic with :rule :projected-write-failed and only
   the store's :result and :address as :cause, never the envelope. None
   is thrown across the boundary the named side already crossed."
  [datoms projected-store]
  (let [projection (try
                     (let [p (debruijn/project-datoms datoms)]
                       (assoc p :envelope (projected-envelope p)))
                     (catch #?(:cljd Object :clj Throwable :cljs :default) t
                       (debruijn/exception-diagnostic t)))]
    (if-let [status (:status projection)]
      {:outcome (if (= :internal-error status) :internal-error :diagnostic),
       :diagnostic (:diagnostic projection)}
      (try
        (let [address (jing/materialize! projected-store (:envelope projection))]
          {:outcome :ok,
           :fingerprint (:fingerprint projection),
           :address address})
        (catch #?(:cljd Object :clj Throwable :cljs :default) t
          {:outcome :diagnostic,
           :diagnostic {:rule :projected-write-failed,
                        :message (ex-message t),
                        :cause (select-keys (ex-data t) [:result :address])}})))))


(defn persist-compiled!
  "Persist one compiled program's two artifacts, named first.

   Takes {:ast <map AST> | :datoms <pre-emitted named batch>
          :named-writer (fn [datoms] -> outcome map)
          :projected-store <dao.jing content-store handle>
          :provenance <opaque caller data>}.

   :named-writer receives the complete named :yin/* batch and answers a
   dao.stream outcome map; its :dao.stream/outcome decides the named side
   and its whole receipt rides under :receipt. A writer that throws
   propagates: that is a defect in the caller's own argument, exactly as
   dao.space.transactor treats malformed input.

   Returns {:named {...} :projected {...} :provenance <echo>} where the
   two sides report independently:

   | named :outcome          | when                             | projected :outcome                       |
   |-------------------------|----------------------------------|------------------------------------------|
   | :ok                     | writer answered :dao.stream/ok   | :ok (with :fingerprint and :address),    |
   |                         |                                  | :diagnostic — projection or the store —, |
   |                         |                                  | or :internal-error — a projection defect |
   | <writer's non-ok kw>    | writer answered another keyword  | :not-attempted                           |
   | :invalid-writer-answer  | answer carries no keyword outcome| :not-attempted                           |
   | :rejected               | batch fails the framing gate     | :not-attempted (same :rule)              |

   :provenance is opaque caller data echoed for correlation; it is never
   interpreted and never enters either artifact."
  [{:keys [named-writer projected-store provenance] :as request}]
  (let [datoms (batch-of request)]
    (if-let [{:keys [rule]} (framing-defect datoms)]
      {:named {:outcome :rejected, :rule rule},
       :projected {:outcome :not-attempted, :rule rule},
       :provenance provenance}
      (let [named-receipt (named-writer datoms)
            answered (when (map? named-receipt)
                       (get named-receipt :dao.stream/outcome))
            named-outcome (if (keyword? answered) answered :invalid-writer-answer)]
        (if-not (= :dao.stream/ok named-outcome)
          {:named {:outcome named-outcome, :receipt named-receipt},
           :projected {:outcome :not-attempted, :named-outcome named-outcome},
           :provenance provenance}
          {:named {:outcome :ok, :receipt named-receipt},
           :projected (persist-projected! datoms projected-store),
           :provenance provenance})))))
