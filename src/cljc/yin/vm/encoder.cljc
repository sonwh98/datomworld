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


(defn datoms->rows
  "§7.1's projection path as an explicit adapter: a `:yin/*` datom batch
   reconstructs its map AST (`vm/datoms->ast`), then projects like every
   other batch — the datom→row half of the §9.1 codec pair."
  [datoms]
  (vm/ast->semantic-bytecode (vm/datoms->ast datoms)))


(defn project
  "One program-medium batch to its canonical row set `{:root id, :rows …}`
   (§6.1). A batch that is neither a map AST nor a datom batch is a defect
   of the medium and throws here, at the boundary, naming where it failed."
  [batch]
  (if (map? batch)
    (vm/ast->semantic-bytecode batch)
    (datoms->rows batch)))


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
