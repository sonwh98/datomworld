(ns dao.stream.remote
  "DaoStream remote: any stream, reachable as itself
   (docs/design/dao.stream.remote.md, sections 2 through 2.5).

   A peer that holds a handle answers the handle's own operations as
   data over a channel; a peer at the other end holds a handle -- the
   reflection -- whose operations are those questions asked across the
   channel, with the source's outcome maps returned verbatim.

   `mirror-step` is the pure answering step a peer runs over a channel
   against its table. It never constructs, parses or rewrites a cursor,
   an anchor or an outcome, and its steps 1 to 4 are its only contacts
   with the entry's handle: every cursor, next and append! runs through
   `dao.stream.middleware/apply-request` with the channel context, so
   nothing bypasses the entry's middleware or its declared surface.
   `attacher` is the composition's :dao.stream/attach entry for
   :dao.stream/remote descriptors; it keeps one link per channel
   descriptor, shared by every reflection through that channel, and
   answers ok at once -- the contract's deferred remote confirmation.
   There is no :dao.stream/create entry: attaching through this
   transport never creates.

   A name is lookup data and never a :dao.stream/identity. A peer's
   composition may hold, beside its table, a name map {name identity};
   a named descriptor request carries :dao.stream.remote/name in place
   of :dao.stream/identity and the mirror answers the mapped entry's
   own identity and surface. `links` is the asking side: the attach
   entry and `resolve` over the same per-channel links, so a name is
   resolved and its stream then attached through one link."
  (:require [dao.stream :as stream]
            [dao.stream.middleware :as middleware]))


;; =============================================================================
;; Wire shapes (2.1)
;; =============================================================================

(def ^:private wire-ops
  "The four wire operations. close! never crosses -- the mirror holds
   nothing per remote party to close and the owner's close is never
   remote -- and attach! never crosses, because it consumes a
   descriptor and produces a local handle."
  #{:dao.stream/descriptor :dao.stream/cursor :dao.stream/next
    :dao.stream/append!})


(def ^:private protocol-errors
  "The three protocol errors, one answer shape for every operation,
   a key instead of an outcome map because no operation's outcome set
   can carry them."
  #{:dao.stream.remote/not-found
    :dao.stream.remote/no-surface
    :dao.stream.remote/oversize})


(defn- named?
  "True when the map `v` carries a name: a named request, or the
   answer to one."
  [v]
  (contains? v :dao.stream.remote/name))


(defn- well-formed-request?
  "True when `v` is a well-formed request: a map naming its identity
   and one of the four wire ops, carrying an args vector and the
   asker-minted id; or a named request, which carries a name in place
   of the identity and may ride the descriptor op only, with empty
   args. A request that carries an identity is an identity request
   whatever else it carries; a named request with any other op, or
   with args that are not empty, is malformed. Anything else is
   dropped as malformed wire input."
  [v]
  (and (map? v)
       (vector? (:dao.stream.remote/args v))
       (contains? v :dao.stream.remote/id)
       (if (contains? v :dao.stream/identity)
         (contains? wire-ops (:dao.stream.remote/op v))
         (and (named? v)
              (= :dao.stream/descriptor (:dao.stream.remote/op v))
              (empty? (:dao.stream.remote/args v))))))


(defn- well-formed-answer?
  "True when `v` is a well-formed answer: a map carrying the id and
   the identity and either a protocol error or an outcome map. The
   answer to a named request carries the name; its not-found error
   carries the name and no identity, because an unmapped name names no
   stream, and so does the oversize error that replaces an answer that
   carried none."
  [v]
  (and (map? v)
       (contains? v :dao.stream.remote/id)
       (or (contains? v :dao.stream/identity)
           (and (named? v)
                (contains? #{:dao.stream.remote/not-found
                             :dao.stream.remote/oversize}
                           (:dao.stream.remote/error v))))
       (or (contains? protocol-errors (:dao.stream.remote/error v))
           (stream/outcome-map? v))))


(defn- required-surface
  "The declared surface `op` needs, under the fixed mapping: cursor
   and next need :reader, append! needs :writer, descriptor needs no
   surface and is always answerable."
  [op]
  (case op
    (:dao.stream/cursor :dao.stream/next) :reader
    :dao.stream/append! :writer
    nil))


(defn- error-answer
  "The protocol-error answer shape, with the id and identity the
   request carried."
  [identity id e]
  {:dao.stream/identity identity
   :dao.stream.remote/id id
   :dao.stream.remote/error e})


;; =============================================================================
;; The mirror step (2.3)
;; =============================================================================

(defn- valid-budget?
  "True when the request carries the optional next-only budget as a
   positive integer. Anything else ignores the budget, which is always
   correct for a mirror."
  [req]
  (let [k (:dao.stream.remote/budget req)]
    (and (integer? k) (pos? k))))


(defn- chase
  "The budget chase: `outcome` is an ok from next; follow the successor
   cursor through the same apply-request path at most k - 1 further
   times, collecting the further outcome maps in order. The vector ends
   at and includes the first non-ok outcome, so blocked, end and gap
   reach the reader with the source's own recovery cursor."
  [h ctx k outcome]
  (loop [more [] cur (:dao.stream/cursor outcome) n 1]
    (if (or (>= n k) (nil? cur))
      more
      (let [r (middleware/apply-request h ctx
                                        {:dao.stream.remote/op
                                         :dao.stream/next
                                         :dao.stream.remote/args [cur]})]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (conj more r) (:dao.stream/cursor r) (inc n))
          (conj more r))))))


(defn- write-answer!
  "Append one answer value toward the asker. A value the channel cannot
   carry (invalid-value) is answered with the oversize protocol error
   in place of that element, the read not skipped and no cursor
   advanced past it; any other refused write leaves the request
   unanswered. Returns the writer's outcome of the last append."
  [chan-writer answer]
  (let [r (stream/append! chan-writer answer)]
    (if (= :dao.stream/invalid-value (:dao.stream/outcome r))
      (stream/append! chan-writer
                      (assoc (select-keys answer [:dao.stream/identity
                                                  :dao.stream.remote/id
                                                  :dao.stream.remote/name])
                             :dao.stream.remote/error
                             :dao.stream.remote/oversize))
      r)))


(defn- descriptor-answer
  "The descriptor answer of a table entry: the entry's handle's own
   descriptor outcome, with the id, the served identity and the
   entry's declared surface."
  [entry identity id]
  (-> (stream/descriptor (:handle entry))
      (assoc :dao.stream.remote/id id
             :dao.stream/identity identity
             :dao.stream.remote/surface (:surface entry))))


(defn- answer-named!
  "The mirror's step 0 for one well-formed named request: look the
   name up in the name map and the identity it maps to in the table.
   An unmapped name, and a name mapped to an identity the table lacks,
   are answered not-found with the name and no identity. Otherwise the
   answer is the descriptor answer of the mapped entry -- its own
   identity, its declared surface -- with the name echoed. The name
   never rides :dao.stream/identity. Returns the writer's outcome."
  [table names chan-writer req]
  (let [n (:dao.stream.remote/name req)
        id (:dao.stream.remote/id req)
        identity (when (map? names) (get names n))
        entry (when (and (map? names) (contains? names n))
                (get table identity))]
    (write-answer!
      chan-writer
      (if-not entry
        {:dao.stream.remote/id id
         :dao.stream.remote/name n
         :dao.stream.remote/error :dao.stream.remote/not-found}
        (assoc (descriptor-answer entry identity id)
               :dao.stream.remote/name n)))))


(defn- answer!
  "The mirror's steps 1 to 4 for one well-formed request: look the
   identity up in the table (not-found); check the op against the
   entry's declared surface under the fixed mapping (no-surface);
   answer descriptor from the entry's handle directly, adding the
   declared surface, and apply cursor, next and append! through
   apply-request with the channel context; append the answer with the
   id and identity. For next with a budget k, and an ok first outcome,
   the chase places the further outcomes under more, k clamped to the
   local chase-max when one is given. Returns the writer's outcome."
  [table chan-writer channel chase-max req]
  (let [identity (:dao.stream/identity req)
        id (:dao.stream.remote/id req)
        op (:dao.stream.remote/op req)
        entry (get table identity)]
    (if-not entry
      (write-answer! chan-writer
                     (error-answer identity id
                                   :dao.stream.remote/not-found))
      (let [h (:handle entry)]
        (if (= :dao.stream/descriptor op)
          (write-answer! chan-writer (descriptor-answer entry identity id))
          (if-not (contains? (into #{} (:surface entry))
                             (required-surface op))
            (write-answer! chan-writer
                           (error-answer identity id
                                         :dao.stream.remote/no-surface))
            (let [ctx {:dao.stream.remote/channel channel}
                  outcome (middleware/apply-request h ctx req)
                  more (when (and (= :dao.stream/next op)
                                  (valid-budget? req)
                                  (= :dao.stream/ok
                                     (:dao.stream/outcome outcome)))
                         (let [k (:dao.stream.remote/budget req)]
                           (chase h ctx
                                  (if chase-max (min k chase-max) k)
                                  outcome)))
                  answer (cond-> (assoc outcome
                                        :dao.stream.remote/id id
                                        :dao.stream/identity identity)
                           (seq more) (assoc :dao.stream.remote/more
                                             more))]
              (write-answer! chan-writer answer))))))))


(defn- pos-int-or-nil?
  [x]
  (or (nil? x) (and (integer? x) (pos? x))))


(defn mirror-step
  "The pure step a peer runs over a channel to answer requests against
   its table: `(mirror-step table chan-reader cursor chan-writer) ->
   cursor'`. For each request read from chan-reader at `cursor`, in
   order, the four steps of 2.3 run; the mirror holds no state between
   calls beyond the returned cursor. A value that is neither a
   well-formed request nor a well-formed answer is dropped as malformed
   wire input. The step stops when the channel reader answers blocked
   or end; a gap adopts the reader's own recovery cursor, the lost wire
   values being lost. The mirror never constructs, parses or rewrites
   a cursor, an anchor or an outcome: the served stream is the
   original, its cursors, its positions, its gap, its end.

   The five-argument form takes the composition's name map `names`,
   {name identity}, each value a key of `table`: a named descriptor
   request is answered from it (step 0). Without a name map every
   name is unmapped.

   The six-argument form takes `bounds`, nil or a map of optional
   positive integers: :dao.stream.remote/mirror-budget bounds the
   wire values read per call -- each ok read, well-formed or not, and
   each gap counts one -- returning the cursor to continue from;
   :dao.stream.remote/chase-budget clamps a peer-requested next
   budget. Any other bounds is a composition error. A writer that
   answers full on the answer of a descriptor, cursor, next or named
   descriptor request stops the step at the cursor preceding that
   request, so it is re-read and re-answered next call; a full on an
   append! answer is dropped, the source append having run."
  ([table chan-reader cursor chan-writer]
   (mirror-step table nil chan-reader cursor chan-writer nil))
  ([table names chan-reader cursor chan-writer]
   (mirror-step table names chan-reader cursor chan-writer nil))
  ([table names chan-reader cursor chan-writer bounds]
   (when-not (or (nil? bounds)
                 (and (map? bounds)
                      (every? pos-int-or-nil? (vals bounds))))
     (throw (ex-info "invalid DaoStream remote mirror bounds"
                     {:bounds bounds})))
   (let [channel (:dao.stream/identity (stream/descriptor chan-reader))
         budget (:dao.stream.remote/mirror-budget bounds)
         chase-max (:dao.stream.remote/chase-budget bounds)]
     (loop [cursor cursor remaining budget]
       (if (and remaining (zero? remaining))
         cursor
         (let [r (stream/next chan-reader cursor)
               remaining' (when remaining (dec remaining))]
           (case (:dao.stream/outcome r)
             :dao.stream/ok
             (let [v (:dao.stream/value r)
                   w (when (well-formed-request? v)
                       (if (contains? v :dao.stream/identity)
                         (answer! table chan-writer channel chase-max v)
                         (answer-named! table names chan-writer v)))]
               (if (and (= :dao.stream/full (:dao.stream/outcome w))
                        (not= :dao.stream/append!
                              (:dao.stream.remote/op v)))
                 cursor
                 (recur (:dao.stream/cursor r) remaining')))
             :dao.stream/blocked cursor
             :dao.stream/end cursor
             :dao.stream/gap (recur (:dao.stream/cursor r) remaining')
             cursor)))))))


;; =============================================================================
;; The link (2.4, 2.5)
;; =============================================================================

(defn- new-link
  "One link per channel descriptor, shared by every reflection through
   that channel. A link holds the channel writer; the channel reader
   and the link's own cursor on it; the outstanding request ids with
   the request each was sent for; the attach descriptor probes the
   writer has refused, kept unsent with the reflection each was sent
   for; filed answers keyed by id; the more outcomes installed at
   their served identity and the cursor that precedes each; an
   optional event writer; and the policy data -- the resend-after k,
   the budget k stamped on next requests, and the bounds on reads per
   drain, on outstanding plus kept requests and on filed plus
   installed answers, all composition data."
  [cd chan policy]
  (atom {:channel cd
         :reader (:reader chan)
         :writer (:writer chan)
         :cursor (:dao.stream/cursor
                   (stream/cursor (:reader chan) :dao.stream/oldest))
         :outstanding {}
         :pending {}
         :filed {}
         :filed-cursors {}
         :filed-seq 0
         :events (:dao.stream.remote/events policy)
         :resend-after (:dao.stream.remote/resend-after policy)
         :budget (:dao.stream.remote/budget policy)
         :drain-budget (:dao.stream.remote/drain-budget policy)
         :max-outstanding (:dao.stream.remote/max-outstanding policy)
         :max-filed (:dao.stream.remote/max-filed policy)
         :next-id 0
         :channel-gone? false}))


(defn- mint-id!
  "The next asker-minted id, unique per channel."
  [link]
  (let [n (:next-id @link)]
    (swap! link assoc :next-id (inc n))
    n))


(defn- emit!
  "Append one event to the link's event writer when one is composed;
   without an event writer the event is dropped."
  [link v]
  (when-some [w (:events @link)]
    (stream/append! w v)))


(defn- register!
  "Record `req` as outstanding under its id, with the request it was
   sent for, its ask count, and the reflection that sent it."
  [link refl req]
  (let [id (:dao.stream.remote/id req)]
    (swap! link assoc-in [:outstanding id]
           {:req req :asks 0 :reflection refl})
    (swap! refl update :ids conj id)))


(defn- stamped-budget
  "The budget a next request carries: the link's budget, no more than
   max-filed when both are composed -- the link could retain no more
   outcomes than that, so asking for more would only be dropped."
  [link]
  (let [{:keys [budget max-filed]} @link]
    (if (and (integer? budget) max-filed)
      (min budget max-filed)
      budget)))


(defn- wire-request
  "One request map for the channel, its id asker-minted; a next
   request carries the link's stamped budget when one is composed."
  [link refl op args]
  (let [k (stamped-budget link)]
    (cond-> {:dao.stream/identity (:identity @refl)
             :dao.stream.remote/op op
             :dao.stream.remote/args args
             :dao.stream.remote/id (mint-id! link)}
      (and (= :dao.stream/next op) (some? k))
      (assoc :dao.stream.remote/budget k))))


(defn- room?
  "True when the link holds fewer than max-outstanding requests,
   outstanding or kept, not counting `id` itself, or has no such bound.
   Sends and kept probes are both admitted only with room, so
   outstanding plus kept never exceeds the bound, and a kept probe's
   own retry -- which excludes itself -- always has room."
  [link id]
  (let [{:keys [max-outstanding outstanding pending]} @link]
    (or (nil? max-outstanding)
        (< (+ (count outstanding) (count (dissoc pending id)))
           max-outstanding))))


(defn- send!
  "Append `req` to the channel writer, unless the link has no room
   for it: that send is refused locally as though the writer answered
   full, nothing crossing."
  [link req]
  (if (room? link (:dao.stream.remote/id req))
    (stream/append! (:writer @link) req)
    {:dao.stream/outcome :dao.stream/full}))


(defn- send-request!
  "Append one request map to the channel writer and register it as
   outstanding only when the writer accepted the send: a send the
   writer refuses with full leaves the request unsent and nothing
   outstanding, so the operation answers as though unanswered and the
   next ask sends again. A send over max-outstanding is refused the
   same way (send!). An attach descriptor probe refused with full is
   kept on the link instead, unsent, one retry per drain until a send
   is accepted -- when the link has room for it; otherwise it waits on
   its own reflection, unadmitted, and that reflection's next operation
   offers it again (admit-probe!). Returns the writer's outcome."
  [link refl req]
  (let [r (send! link req)
        id (:dao.stream.remote/id req)]
    (cond
      (= :dao.stream/ok (:dao.stream/outcome r))
      (do (register! link refl req)
          (swap! link update :pending dissoc id)
          (swap! refl dissoc :probe))

      (and (= :dao.stream/full (:dao.stream/outcome r))
           (= :dao.stream/descriptor (:dao.stream.remote/op req)))
      (if (room? link id)
        (do (swap! link assoc-in [:pending id]
                   {:req req :reflection refl})
            (swap! refl dissoc :probe))
        (swap! refl assoc :probe req)))
    r))


(defn- count-ask!
  "Count one further ask of the outstanding request `id`; on reaching
   the link's resend-after k, re-send it once -- the same id, a
   duplicate of an idempotent operation, which recomputes the same or a
   later, equally true answer -- and start counting afresh. append! is
   never re-sent: it never stays outstanding beyond its send."
  [link id]
  (when-some [entry (get (:outstanding @link) id)]
    (let [k (:resend-after @link)
          asks (inc (:asks entry))]
      (swap! link assoc-in [:outstanding id :asks] asks)
      (when (and (integer? k) (pos? k) (<= k asks))
        (stream/append! (:writer @link) (:req entry))
        (swap! link assoc-in [:outstanding id :asks] 0)))))


(defn- outstanding-for
  "The id of the link's outstanding request for `op` with `arg` sent by
   `refl`, if any; the request each outstanding id was sent for makes
   the correlation."
  [link refl op arg]
  (when-some [[id _]
              (first
                (filter (fn [[_ e]]
                          (let [r (:req e)]
                            (and (= refl (:reflection e))
                                 (= op (:dao.stream.remote/op r))
                                 (= arg (first
                                          (:dao.stream.remote/args r))))))
                        (:outstanding @link)))]
    id))


(defn- filed-for
  "The first filed answer for `op` with `arg` sent by `refl`, in id
   order, as [id entry]. A filed answer keeps the request it was sent
   for and the reflection that sent it -- an answer belongs to the
   operation that caused it -- so it is found whether or not its
   outstanding id was abandoned."
  [link refl op arg]
  (let [filed (:filed @link)]
    (when-some [id
                (first
                  (filter (fn [i]
                            (let [e (get filed i)
                                  r (:req e)]
                              (and (= refl (:reflection e))
                                   (= op (:dao.stream.remote/op r))
                                   (= arg (first
                                            (:dao.stream.remote/args r))))))
                          (sort (keys filed))))]
      [id (get filed id)])))


(defn- bare-outcome
  "The source's own outcome map, verbatim: the correlation keys the
   answer envelope added are removed, nothing else."
  [ans]
  (dissoc ans :dao.stream.remote/id :dao.stream/identity))


(defn- translated
  "A filed protocol error at the reflection: transport-error with the
   error's name as the reason. not-found marks the reflection gone at
   filing; no-surface and oversize mark nothing."
  [e]
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream.remote/reason e})


(defn- learn!
  "The attach probe's ok: record the source's descriptor and declared
   surface, plus :closable."
  [refl answer]
  (swap! refl assoc
         :source-descriptor (:dao.stream/descriptor answer)
         :surface (conj (into #{} (:dao.stream.remote/surface answer))
                        :closable)))


(defn- next-age!
  "The next arrival age on the link: filed answers and installed
   outcomes are aged in the order the link retained them."
  [link]
  (let [n (:filed-seq @link)]
    (swap! link assoc :filed-seq (inc n))
    n))


(defn- evict-filed!
  "Bound the link's filed answers and installed outcomes together by
   max-filed, evicting the oldest retained until within the bound. The
   entry just filed is the newest, so filing an answer never evicts
   that answer. Everything filed is idempotent-recomputable, so the
   operation an evicted entry answered asks again; no outcome is
   invented."
  [link]
  (when-some [m (:max-filed @link)]
    (loop []
      (let [{:keys [filed filed-cursors]} @link]
        (when (< m (+ (count filed) (count filed-cursors)))
          (let [aged (concat (map (fn [[id e]] [(:age e) :filed id]) filed)
                             (map (fn [[k e]] [(:age e) :filed-cursors k])
                                  filed-cursors))
                [_ where k] (apply min-key first aged)]
            (swap! link update where dissoc k)
            (recur)))))))


(defn- file!
  "File answer `v` under `id` with the request it was sent for and the
   reflection that sent it, as the newest retained entry, then bound
   the retained entries by max-filed."
  [link id req v refl]
  (swap! link assoc-in [:filed id]
         {:req req :ans v :reflection refl :age (next-age! link)})
  (evict-filed! link))


(defn- install-more!
  "Install each more outcome under the answer's served identity and at
   the cursor that precedes it, so a later next at that cursor by a
   reflection of the same stream returns the source's own outcome
   without a request. The identity is half of the key: two served
   identities can carry equal cursor values, and an outcome belongs to
   its own stream. At most the link's stamped budget k less one are
   installed -- the answer itself is the first of k -- and none when
   the link stamped no budget: the surplus a peer sends is dropped.
   Prefetch only fills free retained capacity: with max-filed, no more
   are installed than fit beside what is already retained, so a
   prefetch never evicts anything, its own answer included."
  [link answer]
  (let [identity (:dao.stream/identity answer)
        k (stamped-budget link)
        asked (if (and (integer? k) (pos? k)) (dec k) 0)
        {:keys [max-filed filed filed-cursors]} @link
        fits (if max-filed
               (max 0 (- max-filed (count filed) (count filed-cursors)))
               asked)]
    (loop [cur (:dao.stream/cursor answer)
           more (take (min asked fits) (:dao.stream.remote/more answer))]
      (when (and cur (seq more))
        (let [[o & further] more]
          (swap! link assoc-in [:filed-cursors [identity cur]]
                 {:outcome o :age (next-age! link)})
          (recur (:dao.stream/cursor o) further))))))


(defn- absorb-named!
  "A resolve's answer, read off the channel: taken only when it echoes
   the name its outstanding request asked, and filed under its id for
   the resolve that asked. No reflection exists for a name, so nothing
   is marked gone and nothing is emitted. An answer that does not echo
   the name never completes the request, which stays outstanding."
  [link id entry v]
  (let [req (:req entry)]
    (when (and (named? v)
               (= (:dao.stream.remote/name req)
                  (:dao.stream.remote/name v)))
      (swap! link update :outstanding dissoc id)
      (file! link id req v nil))))


(defn- absorb!
  "Consume one value read off the channel. A well-formed answer whose
   id is outstanding and whose identity is the outstanding entry's
   reflection's own identity is taken: a not-found error marks the
   reflection gone; the attach probe's ok records the source's
   descriptor and declared surface; an append!'s source outcome is
   emitted on the event writer, correlated by id; any other answer is
   filed under its id and its more outcomes installed. Answers whose
   id is not outstanding, answers whose identity is not the
   reflection's own -- a mismatch never completes a request, which
   stays outstanding, and an answer that carries no identity
   completes no identity request -- and values that are not
   well-formed answers,
   are dropped as diagnostics. The answer to a named request is the
   resolve's (absorb-named!)."
  [link v]
  (when (well-formed-answer? v)
    (let [id (:dao.stream.remote/id v)]
      (when-some [entry (get (:outstanding @link) id)]
        (if (named? (:req entry))
          (absorb-named! link id entry v)
          (let [refl (:reflection entry)
                req (:req entry)
                op (:dao.stream.remote/op req)
                e (:dao.stream.remote/error v)]
            (when (and (contains? v :dao.stream/identity)
                       (= (:dao.stream/identity v) (:identity @refl)))
              (swap! link update :outstanding dissoc id)
              (swap! refl update :ids disj id)
              (cond
                (= :dao.stream.remote/not-found e)
                (do (swap! refl assoc :gone? true)
                    (when (or (= :dao.stream/descriptor op)
                              (= :dao.stream/append! op))
                      (emit! link v)))

                (= :dao.stream/descriptor op)
                ;; The attach probe's confirmation. ok records; a
                ;; no-surface or oversize error marks nothing.
                (when (nil? e)
                  (learn! refl v)
                  (emit! link v))

                (= :dao.stream/append! op)
                (emit! link v)

                :else
                (do (file! link id req v refl)
                    (install-more! link v))))))))))


(defn- channel-loss!
  "The channel reader answered end: the channel is gone. The link
   abandons its outstanding ids, reporting each abandoned append! on
   the event writer as append-unknown; filed answers are kept and
   still returned; reattachment is the caller's policy."
  [link]
  (doseq [[id entry] (:outstanding @link)]
    (when (= :dao.stream/append! (:dao.stream.remote/op (:req entry)))
      (emit! link {:dao.stream.remote/event
                   :dao.stream.remote/append-unknown
                   :dao.stream.remote/id id}))
    (when-some [refl (:reflection entry)]
      (swap! refl update :ids disj id)))
  (swap! link assoc :outstanding {} :channel-gone? true))


(defn- retry-pending!
  "One more try of each probe the writer has refused, once per drain,
   until a send is accepted; the accepted send is registered as the
   outstanding probe and the normal filing path confirms it."
  [link]
  (doseq [[_ e] (:pending @link)]
    (send-request! link (:reflection e) (:req e))))


(defn- admit-probe!
  "Offer `refl`'s unadmitted attach probe to its link again: a probe
   the link had no room to keep waits on its own reflection, so the
   link's kept state stays within max-outstanding, and is sent, kept,
   or left waiting by send-request! as on attach."
  [refl]
  (let [{:keys [probe link closed?]} @refl]
    (when (and probe (not closed?) (not (:channel-gone? @link)))
      (send-request! link refl probe))))


(defn- drain!
  "Read the link's channel reader to blocked, filing each answer: the
   caller's own polling is the cadence and no driver step exists. An
   end is the channel's loss; a gap adopts the reader's own recovery
   cursor, the lost wire values being lost. Each drain is also one
   further ask of an outstanding descriptor probe -- the probe's
   caller never asks it remotely, where a named request's caller asks
   by calling resolve again -- so resend-after re-sends it, and
   one more try of each probe the writer has refused, until a send
   is accepted. With a drain-budget, at most that many reads -- ok and
   gap alike -- are taken per drain, the link's cursor kept where it
   stopped for the next operation's drain."
  [link]
  (when-not (:channel-gone? @link)
    (doseq [[_ e] (:outstanding @link)]
      (when (and (= :dao.stream/descriptor (:dao.stream.remote/op (:req e)))
                 (not (named? (:req e))))
        (count-ask! link (:dao.stream.remote/id (:req e)))))
    (retry-pending! link)
    (loop [remaining (:drain-budget @link)]
      (when-not (and remaining (zero? remaining))
        (let [r (stream/next (:reader @link) (:cursor @link))]
          (case (:dao.stream/outcome r)
            :dao.stream/ok
            (do (absorb! link (:dao.stream/value r))
                (swap! link assoc :cursor (:dao.stream/cursor r))
                (recur (when remaining (dec remaining))))
            :dao.stream/blocked nil
            :dao.stream/end (channel-loss! link)
            :dao.stream/gap
            (do (swap! link assoc :cursor (:dao.stream/cursor r)) nil)
            nil))))))


;; =============================================================================
;; The reflection (2.4)
;; =============================================================================

(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- refl-descriptor
  "Local: ok with the remote descriptor the reflection was attached
   with and the identity -- the same identity as the source's own
   descriptor, different reachability. A name outlives what it named:
   descriptor still answers ok once gone, once closed, and after
   channel loss."
  [refl]
  (drain! (:link @refl))
  (admit-probe! refl)
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/descriptor (:descriptor @refl)
   :dao.stream/identity (:identity @refl)})


(defn- surface-lacks?
  "True when the source's declared surface is learned and lacks the
   surface `op` needs. Before the probe's answer the reflection
   declares #{:reader :writer :closable}, so every operation is
   attempted; an operation the source turns out to lack is answered as
   the no-surface case."
  [refl op]
  (let [s (:surface @refl)]
    (and (some? s) (not (contains? s (required-surface op))))))


(defn- filed-answer-outcome
  "The outcome a filed answer yields: a protocol error translated per
   the rules, otherwise the source's outcome map verbatim."
  [ans]
  (or (some-> (:dao.stream.remote/error ans) translated)
      (bare-outcome ans)))


(defn- filed-cursor
  "A filed answer for anchor `a` sent by `refl`, returned without
   being forgotten: minting is idempotent, so the answer stays as true
   as when it was minted."
  [link refl a]
  (when-some [[_ entry] (filed-for link refl :dao.stream/cursor a)]
    (filed-answer-outcome (:ans entry))))


(defn- filed-next!
  "A filed answer for cursor `c` sent by `refl`: the outcome an
   installed more element put at `c` for the stream `refl` serves,
   else the filed answer of a next request for `c`; returned and
   forgotten."
  [link refl c]
  (let [k [(:identity @refl) c]]
    (or (when-some [o (get (:filed-cursors @link) k)]
          (swap! link update :filed-cursors dissoc k)
          (filed-answer-outcome (:outcome o)))
        (when-some [[id entry]
                    (filed-for link refl :dao.stream/next c)]
          (swap! link update :filed dissoc id)
          (filed-answer-outcome (:ans entry))))))


(defn- retry-read
  "The answer a just-sent cursor request gives: the source's map,
   invalid-anchor included, arrives later as an answer."
  []
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream/retry? true})


(defn- send-or-count!
  "Send the request for `op` with `arg` when none of this reflection's
   own is outstanding; otherwise count one further ask of the
   outstanding request, which re-sends it at the link's resend-after
   k."
  [link refl op arg]
  (if-some [id (outstanding-for link refl op arg)]
    (count-ask! link id)
    (send-request! link refl (wire-request link refl op [arg]))))


(defn- refl-cursor
  "The cursor operation: a filed answer for the anchor is returned,
   and a filed protocol error translated; otherwise a cursor request
   is sent if none is outstanding, and the answer is transport-error
   with :dao.stream/retry? true. Once closed, cursor answers closed;
   once gone, transport-error naming not-found."
  [refl a]
  (let [link (:link @refl)]
    (drain! link)
    (admit-probe! refl)
    (cond
      (:closed? @refl) (result :dao.stream/closed)
      (:gone? @refl) (translated :dao.stream.remote/not-found)
      (surface-lacks? refl :dao.stream/cursor)
      (translated :dao.stream.remote/no-surface)
      :else
      (or (filed-cursor link refl a)
          (when-not (:channel-gone? @link)
            (send-or-count! link refl :dao.stream/cursor a)
            (retry-read))
          (translated :dao.stream.remote/channel-gone)))))


(defn- refl-next
  "The next operation: a filed answer for the cursor is returned and
   forgotten; otherwise a next request is sent if none is outstanding,
   and the answer is blocked -- nothing is observable at this position
   through this handle yet. After close, filed outcomes then end. The
   reflection never judges a cursor: cursor-mismatch and
   invalid-cursor are only ever relayed from the source."
  [refl c]
  (let [link (:link @refl)]
    (drain! link)
    (admit-probe! refl)
    (cond
      (:closed? @refl)
      (or (filed-next! link refl c) (result :dao.stream/end))

      (:gone? @refl) (translated :dao.stream.remote/not-found)

      (surface-lacks? refl :dao.stream/next)
      (translated :dao.stream.remote/no-surface)

      :else
      (or (filed-next! link refl c)
          (when-not (:channel-gone? @link)
            (send-or-count! link refl :dao.stream/next c)
            (result :dao.stream/blocked))
          (translated :dao.stream.remote/channel-gone)))))


(defn- refl-append
  "The append! operation: send the request and return the channel
   writer's outcome -- ok is acceptance on the outbound path, exactly
   the contract's Writing rule for a transport that carries values
   toward a stream elsewhere. The source's outcome is emitted on the
   event writer when it is filed, correlated by id; append! never
   answers the source's full. Only an accepted send is in flight: a
   refused append! never crossed, so its effect is not unknown; a send
   over max-outstanding answers full, nothing crossing."
  [refl v]
  (let [link (:link @refl)]
    (drain! link)
    (admit-probe! refl)
    (cond
      (:closed? @refl) (result :dao.stream/closed)
      (:gone? @refl) (translated :dao.stream.remote/not-found)
      (surface-lacks? refl :dao.stream/append!)
      (translated :dao.stream.remote/no-surface)
      :else
      (let [req (wire-request link refl :dao.stream/append! [v])
            r (send! link req)]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (register! link refl req))
        r))))


(defn- refl-close
  "The close! operation, local: the reflection forgets its
   outstanding ids and its kept, unsent probe, reporting each
   outstanding append! on the event writer as append-unknown -- an
   append whose answer never arrives has unknown effect; afterwards
   append! answers closed, cursor answers closed, next answers filed
   outcomes then end, and nothing crosses the wire. Idempotent, per
   the contract's Close."
  [refl]
  (let [link (:link @refl)]
    (swap! link update :pending
           (fn [kept]
             (into {}
                   (remove (fn [[_ e]]
                             (= refl (:reflection e)))
                           kept))))
    (swap! refl dissoc :probe)
    (drain! link)
    (when-not (:closed? @refl)
      (doseq [id (:ids @refl)]
        (when-some [entry (get (:outstanding @link) id)]
          (when (= :dao.stream/append! (:dao.stream.remote/op (:req entry)))
            (emit! link {:dao.stream.remote/event
                         :dao.stream.remote/append-unknown
                         :dao.stream.remote/id id}))
          (swap! link update :outstanding dissoc id)))
      (swap! refl assoc :closed? true :ids #{}))
    (result :dao.stream/ok)))


;; =============================================================================
;; The link's resolve (2.4)
;; =============================================================================

(defn- named-id
  "The least id in `entries` -- the link's outstanding or filed map --
   whose request is a named request for the name `n`, if any."
  [entries n]
  (first
    (sort
      (keep (fn [[id e]]
              (let [r (:req e)]
                (when (and (named? r) (= n (:dao.stream.remote/name r)))
                  id)))
            entries))))


(defn- send-named!
  "Append one named descriptor request for `n` to the channel writer,
   outstanding only when the writer accepted the send: a refused send
   leaves the request unsent and the next resolve sends again. The
   request carries the name in place of :dao.stream/identity."
  [link n]
  (let [req {:dao.stream.remote/name n
             :dao.stream.remote/op :dao.stream/descriptor
             :dao.stream.remote/args []
             :dao.stream.remote/id (mint-id! link)}
        r (send! link req)]
    (when (= :dao.stream/ok (:dao.stream/outcome r))
      (swap! link assoc-in [:outstanding (:dao.stream.remote/id req)]
             {:req req :asks 0 :reflection nil}))
    r))


(defn- resolved
  "The outcome a filed answer to a named request yields: ok with the
   remote descriptor of the stream the name mapped to -- its own
   identity, over this link's channel -- and the entry's declared
   surface; a protocol error translated per the reflection's rules,
   not-found included; any other outcome the source's own map."
  [link ans]
  (cond
    (some? (:dao.stream.remote/error ans))
    (translated (:dao.stream.remote/error ans))

    (= :dao.stream/ok (:dao.stream/outcome ans))
    (let [identity (:dao.stream/identity ans)]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/descriptor {:dao.stream/type :dao.stream/remote
                               :dao.stream/identity identity
                               :dao.stream/channel (:channel @link)}
       :dao.stream/identity identity
       :dao.stream.remote/surface (:dao.stream.remote/surface ans)})

    :else
    (dissoc (bare-outcome ans)
            :dao.stream.remote/name :dao.stream.remote/surface)))


(defn- resolve-name
  "The link's resolve: drain, then a filed answer for the name is
   returned and forgotten -- a name is looked up afresh each time it
   is asked, never remembered. Otherwise a named descriptor request is
   sent if none is outstanding, and the answer is transport-error with
   :dao.stream/retry? true, as cursor answers. A send the writer
   refuses with full is retried by the next resolve; any other refusal
   is the writer's own outcome, leaves nothing outstanding and is
   remembered nowhere -- the writer is the source of truth for what it
   can carry -- so each resolve of that name attempts the send again.
   After channel loss the answer is transport-error naming
   channel-gone, not retryable."
  [link n]
  (drain! link)
  (if-some [id (named-id (:filed @link) n)]
    (let [ans (:ans (get (:filed @link) id))]
      (swap! link update :filed dissoc id)
      (resolved link ans))
    (cond
      (:channel-gone? @link)
      (translated :dao.stream.remote/channel-gone)

      :else
      (if-some [id (named-id (:outstanding @link) n)]
        (do (count-ask! link id) (retry-read))
        (let [r (send-named! link n)]
          (if (contains? #{:dao.stream/ok :dao.stream/full}
                         (:dao.stream/outcome r))
            (retry-read)
            r))))))


(deftype ReflectionHandle
  [state]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    (refl-descriptor state))


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (refl-cursor state anchor))


  (next
    [_ c]
    (refl-next state c))


  stream/IDaoStreamWriter

  (append!
    [_ v]
    (refl-append state v))


  stream/IDaoStreamClosable

  (close!
    [_]
    (refl-close state)))


;; =============================================================================
;; The remote descriptor dispatch (2.2)
;; =============================================================================

(defn- valid-remote-descriptor?
  "A remote descriptor names a stream and a channel: the generic
   envelope rules, the :dao.stream/remote type, and a channel
   descriptor. It carries no authorization, no surface and no
   anchors; the mirror answers those."
  [d]
  (and (stream/valid-descriptor? d)
       (= :dao.stream/remote (:dao.stream/type d))
       (contains? d :dao.stream/channel)))


(defn links
  "The asking side over one set of per-channel links: {:attach f
   :resolve g}. `f` is `attacher`'s entry. `(g channel-descriptor
   name)` is the link's resolve over the link `f` keeps for that
   channel, created here when none exists yet: a filed answer for the
   name is returned and forgotten -- ok with the remote descriptor of
   the stream the name maps to, its own identity over that channel,
   and the entry's declared surface under :dao.stream.remote/surface;
   the not-found error as transport-error with reason not-found;
   otherwise the named descriptor request is sent when none is
   outstanding and the answer is transport-error with
   :dao.stream/retry? true, as cursor answers. After channel loss the
   answer is transport-error naming channel-gone. A channel this peer
   does not reach is not-found. `opts` is `attacher`'s; a bound among
   them (:dao.stream.remote/drain-budget, max-outstanding, max-filed)
   that is neither nil nor a positive integer is a composition error."
  [opts]
  (let [channels (:dao.stream.remote/channels opts)
        policy (select-keys opts [:dao.stream.remote/events
                                  :dao.stream.remote/resend-after
                                  :dao.stream.remote/budget
                                  :dao.stream.remote/drain-budget
                                  :dao.stream.remote/max-outstanding
                                  :dao.stream.remote/max-filed])
        _ (when-not (every? #(pos-int-or-nil? (get policy %))
                            [:dao.stream.remote/drain-budget
                             :dao.stream.remote/max-outstanding
                             :dao.stream.remote/max-filed])
            (throw (ex-info "invalid DaoStream remote link policy"
                            {:policy policy})))
        by-channel (atom {})
        link-for! (fn [cd]
                    (swap! by-channel
                           (fn [m]
                             (if (contains? m cd)
                               m
                               (assoc m cd
                                      (new-link cd (get channels cd)
                                                policy)))))
                    (get @by-channel cd))]
    {:attach
     (fn [descriptor]
       (if-not (valid-remote-descriptor? descriptor)
         (result :dao.stream/invalid-descriptor)
         (let [cd (:dao.stream/channel descriptor)]
           (if-not (contains? channels cd)
             (result :dao.stream/not-found)
             (let [link (link-for! cd)
                   refl (atom {:link link
                               :descriptor descriptor
                               :identity (:dao.stream/identity descriptor)
                               :surface nil
                               :source-descriptor nil
                               :gone? false
                               :closed? false
                               :ids #{}})]
               (send-request!
                 link refl
                 (wire-request link refl :dao.stream/descriptor []))
               {:dao.stream/outcome :dao.stream/ok
                :dao.stream/handle (ReflectionHandle. refl)
                :dao.stream/attachment (str (random-uuid))})))))
     :resolve
     (fn [cd n]
       (if-not (contains? channels cd)
         (result :dao.stream/not-found)
         (resolve-name (link-for! cd) n)))}))


(defn attacher
  "The composition's :dao.stream/attach entry for :dao.stream/remote
   descriptors; a dynamic dispatch table has this entry and no
   :dao.stream/create one, because attaching through this transport
   never creates. `opts` carries the composition data:
   :dao.stream.remote/channels maps each channel descriptor this peer
   reaches to its own end {:reader r :writer w} -- two ring buffers in
   one process today, the ws, udp and pair channels of section 3
   later; :dao.stream.remote/events is the optional event writer the
   links emit on; :dao.stream.remote/resend-after is the links'
   resend-after k, unbounded on an ordered reliable channel and small
   on udp; :dao.stream.remote/budget is the budget stamped on every
   next request; :dao.stream.remote/drain-budget bounds the reads per
   drain, :dao.stream.remote/max-outstanding the requests outstanding
   or kept per link, and :dao.stream.remote/max-filed the filed answers
   and installed outcomes per link, each nil for unbounded. One link is kept per channel descriptor, shared by
   every reflection through that channel. attach! answers ok at once
   with a reflection handle and a local, opaque
   :dao.stream/attachment -- the contract's deferred remote
   confirmation -- and the link sends one descriptor probe for the
   identity: its answer is the reflection's confirmation, not-found
   marking the reflection gone, ok recording the source's descriptor
   and declared surface. `links` gives the same entry with the link's
   resolve beside it."
  [opts]
  (:attach (links opts)))
