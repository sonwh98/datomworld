(ns yin.repl.publication
  "The engine's generic durable-publication medium
   (docs/design/yin.repl.dht-composition.md sections 3-4).

   A round that materialized its content and read its manifest back does
   not publish by calling anything: it appends a correlated request
   record `{:op :publish :id :round :manifest}` to `:out`, and the
   frontier it prepared is promoted only by the matching
   `{:op :publication-result ... :status :published}` record read from
   `:in`.  Stream append success means the request was accepted, never
   that publication completed, and a network dissemination result is a
   different outcome from local durability: only the latter settles a
   round.

   This namespace owns the medium and its record shapes.  It knows no
   storage backend: `local-media` builds the pair a composition services
   locally and `service-local!` is that service — the durable HEAD writer
   it calls for one request, and the result it appends — while a
   composition with transport of its own supplies the same two media and
   services the requests itself.

   The medium holds no socket, atom, promise, clock or namespace global.
   The two halves are separate values: an indexer is handed `engine-media`,
   which carries no writer, so the engine emits and reads and never
   executes a HEAD write, while the composition keeps the whole medium and
   preserves synchronous local publication by servicing the requests its
   indexer reports outstanding, in the round that emitted them
   (docs/design/yin.repl.dht-composition.md sections 3-4)."
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]))


(def capacity
  "Declared capacity of each publication medium, in elements.  A round
   emits one request and reads one result, and a composition that
   services locally leaves nothing unread, so the bound is generous; the
   eviction it guards against is a host assembly defect, not an
   operating condition."
  1024)


(defn- medium
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn request
  "The section 4 publication request for one prepared frontier: emitted
   only after the round's content materialized and its manifest read
   back successfully."
  [id round manifest]
  {:op :publish :id id :round round :manifest manifest})


(defn result
  "The section 4 local publication result answering `request`.  `status`
   is `:published` or `:refused` — a refusal carrying `:reason` — and
   `durability` is the composition's local claim alone, `:persistent` or
   `:memory`, never a network acknowledgement."
  [request status durability & [reason]]
  (cond-> {:op :publication-result
           :id (:id request)
           :round (:round request)
           :manifest (:manifest request)
           :status status
           :durability durability}
    reason (assoc :reason reason)))


(defn emit!
  "Append `request` to the medium's request side.  True when the medium
   accepted it; a refusal leaves the caller to report the round
   unpublished, never to promote a frontier nobody was asked to
   publish."
  [media request]
  (= :dao.stream/ok (:dao.stream/outcome (stream/append! (:out media) request))))


(defn service-local!
  "Service one request through the medium's own `:local` durable HEAD
   writer and append the result it produced, answering that result.  The
   ordering contract is the writer's: content materialized and read back
   before this is reached, so a HEAD it moves names a manifest the store
   already answers.  A writer that throws is a `:refused` result carrying
   its message — the prepared frontier is preserved, not promoted — and
   the throw does not escape, because a failed HEAD write is an outcome
   the round reports rather than an error it raises.

   This is the composition's call, never the engine indexer's: an indexer
   holds `engine-media`, which carries no writer, and emits its request
   for whoever owns the medium to service (`serve`).  A medium with no
   `:local` writer is serviced by its composition over its own transport;
   this answers nil and appends nothing."
  [media request]
  (when-let [{:keys [head-fn durability]} (:local media)]
    (let [answer (try
                   (when head-fn (head-fn (:manifest request)))
                   (result request :published (or durability :persistent))
                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                     (result request :refused (or durability :persistent)
                             (or (ex-message e) "the durable HEAD write failed"))))]
      (stream/append! (:in media) answer)
      answer)))


(defn engine-media
  "The engine's view of `media`: the request side it appends to, the
   result side it reads, and its result cursor, with the composition's
   `:local` durable HEAD writer stripped off it.  This is what an indexer
   holds, so no callback hook sits inside the engine
   (docs/design/yin.repl.dht-composition.md sections 3-4)."
  [media]
  (dissoc media :local))


(defn local-media
  "A publication medium pair a composition services locally: `local` is
   `{:head-fn (fn [manifest-address]) :durability :persistent}`, or nil
   for a composition that declares no durable HEAD.  `:cursor` is minted
   at the result side's newest end, so a result of an earlier session is
   never read as this one's.

   The composition keeps this value and services it — `service-local!` of
   each request its indexer reports outstanding — while the engine is
   handed `engine-media` of it.  A composition with transport of its own
   reads the request side with `dao.stream/next` and appends its results
   with `dao.stream/append!`: both are the generic stream operations the
   records of this namespace travel on."
  ([] (local-media nil))
  ([local]
   (let [in (medium)]
     {:out (medium)
      :in in
      :cursor (:dao.stream/cursor (stream/cursor in stream/anchor-newest))
      :local local})))


(defn media
  "A publication medium pair over supplied request and result handles —
   the shape a composition with transport of its own injects, where the
   engine emits and reads but never services.  `cursor` is the minted
   result cursor the engine reads from."
  [out in cursor]
  {:out out :in in :cursor cursor})


(defn next-result
  "Read one publication result the composition appended, answering
   `[result cursor']` or nil when the result side has nothing new.  The
   caller advances the medium's `:cursor` to `cursor'` only for a result
   it consumed, so a medium that blocks is re-read on a later round."
  [media]
  (let [r (stream/next (:in media) (:cursor media))]
    (when (= :dao.stream/ok (:dao.stream/outcome r))
      [(:dao.stream/value r) (:dao.stream/cursor r)])))


(defn advance
  "`media` with its result cursor moved to `cursor`."
  [media cursor]
  (assoc media :cursor cursor))
