(ns dao.jing.remote.step
  "The stepped client: dao.jing.remote's non-blocking remote handle.

   `dao.jing.remote`'s blocking driver (`connect-content!` + `call!`) is JVM
   host policy over `dao.stream.rpc`'s non-waiting core: a thread that parks
   between advances of one awaited call.  On a host with nothing to wait
   with — cljs, cljd — the same core is driven by the caller stepping it,
   and that is this namespace.  It is the unit `dao.jing.md`'s *Async
   hydration* open item names: a client the caller steps, with the
   request-put / request-get / request-materialize / step / abandon shape
   the deleted `dao.jing.implementation-plan.md`'s Decision 3 recorded
   (retrievable from history beside this arc), owed to the B-tree hydration
   work (`dao.data.btree.md` §5.4) and not to the blocking driver it
   generalizes.

   Everything here is a pure function over explicit data: one wrapped
   `dao.stream.rpc` client state plus the materialization records this
   layer adds.  No socket, atom, promise, callback, or scheduler.  The
   caller owns the state, attaches it to whatever media and transport it
   has (`rpc.ws/init-client` after a WebSocket `attach!`, ring buffers in a
   test), and chooses every cadence.

   The state is `{:rpc rpc-state :materializations {put-id record}
   :routes {live-id put-id}}`.  A materialization record is
   `{:phase :put|:verify-unissued|:verify-issued :address a :put-id n
   :get-id m?}` — an address, never a payload: nothing this layer retains
   or publishes carries a payload (the N11 discipline the blocking driver
   owes, held here by construction).  `:routes` maps every wire id a
   record currently claims — its put id while `:put`, its verify get id
   while `:verify-issued` — to the record's put id, which is the identity
   a materialization's one published completion carries.

   `step` advances in one fixed order, and the order is load-bearing:

   1. re-attempt the retained `:unsent` envelope through `rpc/request!`
      (which retries it and ignores the operation it is given), folding
      the outcome under `:attempt`;
   2. `rpc/poll!` at most `budget` response-medium elements;
   3. if the state is terminal and an `:unsent` envelope remains, abandon
      it with the terminal reason, so its loss surfaces on the ordinary
      completion path rather than surviving into a dead binding;
   4. issue the unissued verify reads of `:verify-unissued` records, in
      put-id order, until the writer answers full — one envelope is all
      `rpc` retains — or the state is terminal, in which case each is
      completed `:lost` with the terminal reason synthesized here because
      no wire completion will ever bear its claim;
   5. take completions and diagnostics exactly once, routing each raw
      completion whose id a record claims through the materialization
      transition and decoding every other totally.

   The verify hop is the one obligation the client carries for
   `materialize!`'s contract (B3): a put answering `:present` has promised
   that equal content sits at the address, and a remote server is not
   trusted to have kept that promise.  So `:present` publishes nothing;
   the record moves to `:verify-unissued`, a correlated `:jing/get-content`
   is issued by order 4, and only a read-back that hashes to the address —
   `jing/materialize!`'s own rule at HEAD, `content-hash` against
   `segment-hash`, which holds metadata the address distinguishes and `=`
   does not — completes `:result :present`.  A mismatch is
   `/integrity-failure`; an absent read-back is `/present-but-absent`;
   neither ever overwrites anything.  The record's removal is the
   exactly-once guard: one completion per materialization, carrying the
   put id.

   Published completions are plain data, decoded totaly: a get's ok
   response as `{:id … :op … :found? b :value v}`, a put's as
   `{:id … :op … :address a :result :inserted|:present}`, an error
   response as `:error` carrying `dao.stream.apply`'s portable error map,
   any `:reason` as `{:lost reason}`, a non-conforming ok value as
   `:error /malformed-response`, and a materialization's completion as
   `{:id put-id :materialized? true :address a …}` with the same
   `:result`/`:error`/`:lost` tails.  There is no shape a completion can
   take that this decode does not answer.

   Reattachment stays the caller's, as the blocking driver's D4 ruling
   holds: after a terminal reason every further request answers
   `:dao.stream.rpc/terminal`, and opening a new attachment is a new
   client.  `abandon` retires only the retained `:unsent` envelope — the
   one request whose delivery this binding still owes — and leaves
   outstanding requests to their completions."
  (:require [dao.jing :as jing]
            [dao.stream.apply :as apply]
            [dao.stream.rpc :as rpc]))


;; =============================================================================
;; Vocabulary
;; =============================================================================


(def put-content-op
  "The server operation a remote put speaks (H1's handler map)."
  :jing/put-content)


(def get-content-op
  "The server operation a remote read speaks (H1's handler map)."
  :jing/get-content)


(def malformed-response-code
  "The error code for an ok response whose value does not conform to its
   operation's wire vocabulary — a get not answering the exact presence
   envelope, a put not answering :inserted or :present."
  :dao.jing.remote/malformed-response)


(def integrity-failure-code
  "The error code for a verify read that returned content not hashing to
   the address it was read from (materialize!'s read-back rule, over the
   wire)."
  :dao.jing.remote/integrity-failure)


(def present-but-absent-code
  "The error code for a put that answered :present and a verify read that
   then found nothing at the address."
  :dao.jing.remote/present-but-absent)


(def abandoned-code
  "The default `abandon` reason: this layer's own word for an envelope
   given up by policy rather than lost by the medium."
  :dao.jing.remote/abandoned)


;; =============================================================================
;; State
;; =============================================================================


(defn client-state
  "Wrap one `dao.stream.rpc` client state as the stepped client's state.
   The rpc state supplies the writer, reader, response cursor, decoder and
   id allocator; this layer adds the materialization records and their id
   routes.  The caller owns the result and every step of it."
  [rpc-state]
  {:rpc rpc-state
   :materializations {}
   :routes {}})


(defn- presence-envelope?
  "True only for the exact wire envelope `{:found? boolean :value v}` (H4),
   the shape `:jing/get-content` answers.  Mirrors `dao.jing.remote`'s
   private validator; this namespace depends on the rpc core, not on the
   blocking driver's namespace."
  [x]
  (and (map? x)
       (= #{:found? :value} (set (keys x)))
       (let [f (:found? x)] (or (true? f) (false? f)))))


(defn- segment-address!
  "Throw unless `address` is a segment address (C1: arbitrary keys and
   mutable roots are refused before the backend — here, before the wire)."
  [address]
  (when-not (jing/segment-address? address)
    (throw (ex-info "a stepped content request names a non-segment address"
                    {:address address}))))


;; =============================================================================
;; The request entry points
;; =============================================================================


(defn- submit
  "The shared body of the three request entry points: refuse with `:busy`
   while an unsent envelope is owed (only `step`'s order 1 clears it —
   `rpc/request!` would retry the retained envelope and ignore this
   operation), submit through `rpc/request!`, register a materialization
   record at any id-bearing outcome, and return the rpc outcome verbatim
   under this layer's plain `:outcome`/`:state`/`:id` keys plus whatever
   the rpc result carried beside them (`:dao.stream.rpc/reason`,
   `:dao.stream.rpc/diagnostic`, `:dao.stream.rpc/append`).

   A materialization registers at `:requested`, `:pending-request` and
   `:request-undeliverable` alike — the last has its completion already
   outboxed, and the record is what routes that loss at the next drain.
   `:invalid-request`, `:allocator-error` and `:terminal` carry no id, so
   nothing is registered and nothing is owed a completion."
  [state op args materialize?]
  (let [rpc-state (:rpc state)]
    (if (rpc/unsent? rpc-state)
      {:outcome :busy
       :state state}
      (let [result (rpc/request! rpc-state op args)
            outcome (:dao.stream.rpc/outcome result)
            next-rpc (:dao.stream.rpc/state result)
            id (:dao.stream.rpc/id result)
            next-state
            (if (and materialize? (some? id))
              (-> state
                  (assoc :rpc next-rpc)
                  (assoc-in [:materializations id]
                            {:phase :put :address (first args) :put-id id})
                  (assoc-in [:routes id] id))
              (assoc state :rpc next-rpc))
            base (assoc (dissoc result :dao.stream.rpc/outcome :dao.stream.rpc/state)
                        :outcome outcome
                        :state next-state)]
        (if (some? id)
          (assoc base :id id)
          base)))))


(defn request-get
  "Request one remote read of `address`.  A non-segment address throws
   before the wire (C1).  While an unsent envelope is owed the answer is
   `{:outcome :busy :state s}` and nothing was submitted; otherwise the
   rpc outcome is returned verbatim with the allocated id under `:id`, and
   the read's completion arrives through `step` as
   `{:id … :op :jing/get-content :found? b :value v}` (or `:error`, or
   `:lost reason`)."
  [state address]
  (segment-address! address)
  (submit state get-content-op [address] false))


(defn request-put
  "Request one remote put of `payload` at `address`.  A non-segment
   address throws before the wire (C1); address-against-payload validation
   is the server's at its own door (H2), exactly as for the blocking
   client, and a mismatch comes back as an `:error` completion.  A plain
   put publishes `:result :inserted` or `:result :present` with no verify
   read: the read-back is materialize!'s obligation, not put's."
  [state address payload]
  (segment-address! address)
  (submit state put-content-op [address payload] false))


(defn request-materialize
  "Request one remote materialization of `payload`: the address is derived
   here (B1 — the caller never supplies it), the put is submitted, and the
   returned `:id` is the put id the one completion will carry.  On
   `:inserted` the completion is `{:id … :materialized? true :address a
   :result :inserted}`; on `:present` nothing publishes until the verify
   read (see the namespace docstring's order 4) hashes back to the
   address, completing `:result :present`, or reports
   `/integrity-failure` / `/present-but-absent` / the server's `:error`.
   Any loss publishes `:lost reason`."
  [state payload]
  (submit state put-content-op [(jing/segment-key payload) payload] true))


;; =============================================================================
;; Completion decode
;; =============================================================================


(defn- malformed-response
  [op]
  {:dao.stream.apply/code malformed-response-code
   :dao.stream.apply/message (str "the response does not conform to "
                                  (name op) "'s wire vocabulary")})


(defn- decode-plain-completion
  "Total decode of one raw completion no materialization claims: ok
   responses by op, an error response as `:error` with apply's portable
   error map, any `:reason` as `:lost reason`.  The put branch carries the
   address out of the request's args and never the payload."
  [completion]
  (let [id (:dao.stream.rpc/id completion)
        op (:dao.stream.rpc/op completion)
        response (:dao.stream.rpc/response completion)]
    (if (some? response)
      (if-let [error (apply/response-error response)]
        {:id id :op op :error error}
        (let [value (apply/response-ok response)]
          (cond
            (= put-content-op op)
            (if (#{:inserted :present} value)
              {:id id :op op :address (first (:dao.stream.rpc/args completion))
               :result value}
              {:id id :op op :error (malformed-response op)})

            (= get-content-op op)
            (if (presence-envelope? value)
              {:id id :op op :found? (:found? value) :value (:value value)}
              {:id id :op op :error (malformed-response op)})

            :else
            ;; No such request leaves this client; the decode stays total
            ;; anyway and passes the value through untouched.
            {:id id :op op :value value})))
      {:id id :op op :lost (:dao.stream.rpc/reason completion)})))


;; =============================================================================
;; The materialization transition
;; =============================================================================


(defn- materialization-entry
  "The published tail of one materialization completion: the put id, the
   marker, and the address — never the payload."
  [record]
  {:id (:put-id record)
   :materialized? true
   :address (:address record)})


(defn- materialize-on-completion
  "Route one raw completion a materialization record claims, returning
   `[published-tail records routes]`.  A `:put`-phase completion carrying
   `:present` publishes nothing and moves the record to
   `:verify-unissued` — the put id's route dies with the completion,
   because no future completion can bear an id already answered — while
   every other transition removes the record (the exactly-once guard) and
   publishes its one entry."
  [completion record published records routes]
  (let [response (:dao.stream.rpc/response completion)
        id (:dao.stream.rpc/id completion)
        finish
        (fn [tail]
          [(conj published (merge (materialization-entry record) tail))
           (dissoc records (:put-id record))
           (dissoc routes id)])]
    (if (nil? response)
      ;; A loss, in every phase: undeliverable, abandoned, terminal,
      ;; gap — the reason passes through untouched.
      (finish {:lost (:dao.stream.rpc/reason completion)})
      (if-let [error (apply/response-error response)]
        (finish {:error error})
        (let [value (apply/response-ok response)]
          (if (= :put (:phase record))
            (cond
              (= :inserted value)
              (finish {:result :inserted})

              (= :present value)
              [published
               (assoc records (:put-id record)
                      (assoc record :phase :verify-unissued))
               (dissoc routes id)]

              :else
              (finish {:error (malformed-response put-content-op)}))
            ;; :verify-issued — the verify read answered.
            (cond
              (not (presence-envelope? value))
              (finish {:error (malformed-response get-content-op)})

              (not (:found? value))
              (finish {:error {:dao.stream.apply/code present-but-absent-code
                               :dao.stream.apply/message
                               "the remote reported :present but the content address is absent"}})

              (= (jing/content-hash (:value value))
                 (jing/segment-hash (:address record)))
              (finish {:result :present})

              :else
              (finish {:error {:dao.stream.apply/code integrity-failure-code
                               :dao.stream.apply/message
                               "the remote content does not hash to its content address"}}))))))))


(defn- route-completions
  "Order 5's routing: fold the drained raw completions against the live
   records, then return the published entries and the successor records
   and routes."
  [completions records routes]
  (let [[published records routes]
        (reduce (fn [[published records routes] completion]
                  (if-let [put-id (get routes (:dao.stream.rpc/id completion))]
                    (materialize-on-completion completion
                                               (get records put-id)
                                               published records routes)
                    [(conj published (decode-plain-completion completion))
                     records
                     routes]))
                [[] records routes]
                completions)]
    {:published published :records records :routes routes}))


;; =============================================================================
;; Order 4 — the verify reads
;; =============================================================================


(defn- issue-pending-verifies
  "Issue the correlated verify reads of every `:verify-unissued` record,
   in put-id order, until the writer answers full (an unsent envelope is
   all `rpc` retains, so nothing else can be submitted behind it) or the
   state is terminal — a terminal state completes each remaining record
   `:lost` with the terminal reason, synthesized here because no wire
   completion will ever bear a claim this record still holds.  An
   allocation failure is terminal in the rpc layer and reaches the same
   branch on the next iteration.  Returns
   `{:rpc r :records r' :routes t' :synthesized [...]}`."
  [rpc-state records routes]
  (loop [rpc-state rpc-state
         records records
         routes routes
         synthesized []
         pending (seq (sort-by first
                               (filter (fn [[_id record]]
                                         (= :verify-unissued (:phase record)))
                                       records)))]
    (if (nil? pending)
      {:rpc rpc-state :records records :routes routes :synthesized synthesized}
      (let [[put-id record] (first pending)
            rest-pending (next pending)]
        (cond
          (:terminal rpc-state)
          (recur rpc-state
                 (dissoc records put-id)
                 routes
                 (conj synthesized
                       (merge (materialization-entry record)
                              {:lost (:terminal rpc-state)}))
                 rest-pending)

          (rpc/unsent? rpc-state)
          ;; A writer still holding an envelope owes it a delivery; no
          ;; read can be issued this step.  Order 1 of the next step
          ;; clears it.
          {:rpc rpc-state :records records :routes routes :synthesized synthesized}

          :else
          (let [result (rpc/request! rpc-state get-content-op
                                     [(:address record)])
                outcome (:dao.stream.rpc/outcome result)
                next-rpc (:dao.stream.rpc/state result)
                id (:dao.stream.rpc/id result)
                registered (assoc records put-id
                                  (assoc record
                                         :phase :verify-issued
                                         :get-id id))]
            (case outcome
              (:dao.stream.rpc/requested
               :dao.stream.rpc/request-undeliverable)
              ;; Requested: the read is on the wire; undeliverable: its
              ;; loss is already outboxed and order 5 routes it this same
              ;; step.  Either way the record claims the get id now.
              (recur next-rpc
                     registered
                     (assoc routes id put-id)
                     synthesized
                     rest-pending)

              :dao.stream.rpc/pending-request
              ;; Full: the envelope is retained unsent and issuing stops
              ;; this step, registered, for order 1 to retry.
              {:rpc next-rpc
               :records registered
               :routes (assoc routes id put-id)
               :synthesized synthesized}

              :dao.stream.rpc/allocator-error
              ;; Terminal: recur on the same head so the terminal branch
              ;; synthesizes this record's loss with it, then the rest.
              (recur next-rpc records routes synthesized pending)

              ;; Anything else (:invalid-request, :terminal) cannot arise
              ;; from this call's arguments and the guards above; stop,
              ;; holding state, rather than guess.
              {:rpc next-rpc :records records :routes routes
               :synthesized synthesized})))))))


;; =============================================================================
;; step and abandon
;; =============================================================================


(defn step
  "One non-waiting advance of the stepped client: the five fixed orders of
   the namespace docstring.  Returns
   `{:state s' :attempt k? :completions [...] :diagnostics [...]}` —
   `:attempt` only when an unsent envelope was re-attempted, carrying that
   retry's rpc outcome; `:completions` the published, totally-decoded
   entries (materialization entries carrying their put id and
   `:materialized? true`); `:diagnostics` the rpc diagnostic outbox taken
   exactly once.  Liveness and terminality are the state's, not the
   result's: read `(:terminal (:rpc state'))` to learn whether the
   attachment has ended, and keep stepping while you have work owed.

   This is an interpreter step — it performs stream operations — under a
   single-owner precondition: one caller, one state thread, as the rpc
   core's own docstring requires."
  [state budget]
  (let [rpc-state (:rpc state)
        attempt (when (and (rpc/unsent? rpc-state)
                           (not (:terminal rpc-state)))
                  (rpc/request! rpc-state nil nil))
        retried (if attempt (:dao.stream.rpc/state attempt) rpc-state)
        polled (:dao.stream.rpc/state (rpc/poll! retried budget))
        abandoned (if (and (:terminal polled) (rpc/unsent? polled))
                    (rpc/abandon-unsent polled (:terminal polled))
                    polled)
        issued (issue-pending-verifies abandoned
                                       (:materializations state)
                                       (:routes state))
        drained-rpc (:rpc issued)
        [raw rpc-with-outboxes-taken] (rpc/take-completed drained-rpc)
        [diagnostics final-rpc] (rpc/take-diagnostics rpc-with-outboxes-taken)
        routed (route-completions raw (:records issued) (:routes issued))]
    (cond-> {:state {:rpc final-rpc
                     :materializations (:records routed)
                     :routes (:routes routed)}
             :completions (vec (concat (:synthesized issued)
                                       (:published routed)))
             :diagnostics diagnostics}
      attempt (assoc :attempt (:dao.stream.rpc/outcome attempt)))))


(defn abandon
  "Give up on the retained unsent envelope with `reason` (or this layer's
   `abandoned-code`): the composition that is about to change what the
   writer is — an operator disconnect, a rebind onto a fresh attachment —
   owns the decision that the retained envelope no longer belongs to the
   new binding.  The loss is appended as an ordinary completion and
   surfaces at the next `step`'s drain, routed by its id.  Records with
   nothing on the wire — `:verify-unissued`, or a `:verify-issued` read
   already outstanding — are untouched: abandon retires the one delivery
   this binding still owes, not the medium's in-flight answers."
  ([state]
   (abandon state abandoned-code))
  ([state reason]
   (update state :rpc rpc/abandon-unsent reason)))
