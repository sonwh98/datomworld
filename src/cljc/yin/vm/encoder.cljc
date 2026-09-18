(ns yin.vm.encoder
  "The encoder observer: the `run-on-stream` consumer the composition
   attaches over the program medium so the semantic VM never sees a map AST
   or a datom batch (§9.1, `docs/design/yin.vm.code-as-tuples.md`).

   It is a stage in its own right, not a loader arm. The composition hands
   its session the row medium's writer as the consumer; `run-on-stream`
   hands it each observed program batch; it projects the batch to the
   canonical row set and forwards it — appends it to the row medium. The
   evaluator observer the composition attaches to that row medium
   independently is the next stage: the two never call each other, they
   communicate through the row medium alone, so compiler and evaluator stay
   independently attachable (no direct function-to-function coupling
   without a stream boundary).

   Projection is this boundary's one job (§9.1): the map AST the frontends
   emit projects directly through `yin.vm/ast->semantic-bytecode`; a
   `:yin/*` datom batch projects through the explicit datom→row adapter
   below — §7.1's projection path, for media that carry datoms."
  (:require [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]))


(defn source-envelope
  "Construct the provenance-bearing input value ruled by D4. The admitting
   composition supplies its program medium's logical identity and mints a
   fresh batch token before append. `members` is ordered and `root` selects
   the member the evaluator runs. The constructor validates but never mints:
   retries of an unaccepted staged append must retain the caller's token."
  ([source-medium batch-token members]
   (source-envelope source-medium batch-token members 0))
  ([source-medium batch-token members root]
   (let [envelope {:yin/source-medium source-medium
                   :yin/batch-token batch-token
                   :yin/batch (vec members)
                   :yin/root root}]
     ;; Validating the root origin validates the two opaque coordinates too.
     (when-not (and (integer? root) (<= 0 root) (< root (count members)))
       (throw (ex-info "Malformed source batch envelope"
                       {:rule :batch-envelope, :envelope envelope})))
     (vm/source-origin source-medium batch-token root)
     envelope)))


(defn- datom-child-places
  [get-attr eid]
  (case (get-attr eid :yin/type)
    :lambda [[3 (get-attr eid :yin/body)]]
    :application (concat [[2 (get-attr eid :yin/operator)]]
                         (map-indexed (fn [i child] [[3 i] child])
                                      (get-attr eid :yin/operands)))
    :if [[2 (get-attr eid :yin/test)]
         [3 (get-attr eid :yin/consequent)]
         [4 (get-attr eid :yin/alternate)]]
    :dao.stream.apply/call
    (map-indexed (fn [i child] [[3 i] child])
                 (get-attr eid :yin/operands))
    :stream/put [[2 (get-attr eid :yin/target)]
                 [3 (get-attr eid :yin/val-node)]]
    (:stream/cursor :stream/next :stream/close)
    [[2 (get-attr eid :yin/source)]]
    :vm/resume [[3 (get-attr eid :yin/val-node)]]
    []))


(defn datom-entity-occurrences
  "The §9.1 datom adapter's path/entity relation for one admitted member:
   `[member-index path entity-id]` once per structural occurrence. Shared
   entities therefore appear at every path while retaining one entity
   identity, which is the information U16's harvest catalogue derives from.
   Cyclic or dangling legacy graphs fail at this boundary."
  [datoms member-index]
  (let [{:keys [root-id get-attr error]} (vm/index-datoms datoms)]
    (when error
      (throw (ex-info "Cannot adapt a datom batch with a dangling root" error)))
    (when (nil? root-id)
      (throw (ex-info "Cannot adapt a datom batch with no root"
                      {:rule :root})))
    (letfn [(walk
              [eid path active]
              (when (contains? active eid)
                (throw (ex-info "Cannot adapt a cyclic datom AST"
                                {:rule :cycle, :entity eid, :path path})))
              (when (nil? (get-attr eid :yin/type))
                (throw (ex-info "Cannot adapt a dangling datom AST reference"
                                {:rule :dangling-ref, :entity eid, :path path})))
              (into [[member-index path eid]]
                    (mapcat (fn [[step child]]
                              (walk child (conj path step) (conj active eid))))
                    (datom-child-places get-attr eid)))]
      (vec (walk root-id [] #{})))))


(defn datoms->rows
  "§7.1's projection path as an explicit adapter: a `:yin/*` datom batch
   reconstructs its map AST (`vm/datoms->ast`), then projects like every
   other batch — the datom→row half of the §9.1 codec pair."
  [datoms]
  (vm/ast->semantic-bytecode (vm/datoms->ast datoms)))


(defn- project-member
  [member origin member-index]
  (cond
    (and (map? member) (contains? member :type))
    (let [tree (vm/ast->semantic-bytecode member)]
      (merge {:tree tree, :entity-occurrences []}
             (vm/ast-side-tables member tree origin)))

    (and (map? member) (contains? member :root) (contains? member :rows))
    {:tree member, :source-positions [], :frontend-metadata [],
     :entity-occurrences []}

    (sequential? member)
    (let [datoms (vec member)
          ast (vm/datoms->ast datoms)
          tree (vm/ast->semantic-bytecode ast)]
      (merge {:tree tree
              :entity-occurrences (datom-entity-occurrences datoms member-index)}
             (vm/ast-side-tables ast tree origin)))

    :else
    (throw (ex-info "Unsupported source batch member"
                    {:rule :batch-member, :member member,
                     :member-index member-index}))))


(defn- project-envelope
  [{:yin/keys [source-medium batch-token batch root] :as envelope}]
  (when-not (and (vector? batch)
                 (seq batch)
                 (integer? root)
                 (<= 0 root)
                 (< root (count batch)))
    (throw (ex-info "Malformed source batch envelope"
                    {:rule :batch-envelope, :envelope envelope})))
  (let [projected (mapv (fn [j member]
                          (project-member member
                                          (vm/source-origin source-medium
                                                            batch-token j)
                                          j))
                        (range) batch)]
    (assoc envelope
           :yin/batch (mapv :tree projected)
           :yin/source-positions (into [] (mapcat :source-positions) projected)
           :yin/frontend-metadata (into [] (mapcat :frontend-metadata) projected)
           :yin/entity-occurrences (into [] (mapcat :entity-occurrences) projected))))


(defn project
  "One program-medium batch to its canonical row set `{:root id, :rows …}`
   (§6.1). A batch that is neither a map AST nor a datom batch is a defect
   of the medium and throws here, at the boundary, naming where it failed."
  [batch]
  (cond
    (and (map? batch) (contains? batch :yin/batch))
    (project-envelope batch)

    (map? batch) (vm/ast->semantic-bytecode batch)
    :else (datoms->rows batch)))


(defn ready?
  "The encoder accepts a batch whenever its row medium can take one. A v2
   ring-buffer writer evicts rather than blocking, so it is always ready."
  [_writer]
  true)


(defn load
  "The encoder's `load`: project one observed batch and forward it — append
   the canonical `{:root … :rows …}` set to the row medium whose writer the
   composition handed the session. The consumer is that writer, so it is
   returned unchanged. A non-`ok` append throws naming the outcome, so a
   batch is never dropped silently."
  [writer batch]
  (let [append (stream/append! writer (project batch))]
    (when-not (= :dao.stream/ok (:dao.stream/outcome append))
      (throw (ex-info "Encoder could not forward a batch to the row medium"
                      {:dao.stream/outcome (:dao.stream/outcome append)})))
    writer))


(defn run
  "The encoder's `run`: forwarding is the whole of a projected batch's
   work, and it happened in `load`, so the writer is returned as is."
  [writer]
  writer)


(defn forward-on-stream
  "Coordinate one encoder session — `{:observer o, :consumer row-writer}` —
   over the program medium: every observed batch is projected and forwarded
   to the row medium, and the successor session is returned for the
   composition to thread. The row medium is this stage's output, never its
   input; the evaluator attached there is the composition's next stage."
  [session]
  (observer/run-on-stream session ready? load run))
