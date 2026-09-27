(ns dao.jing.content.step
  "The stepped client half of the content service (docs/design/
   dao.stream.remote.md section 5, the Linda request medium;
   dao.jing.content holds the vocabulary). The portable interface, per
   dao.stream.md OD-5: a pure client the caller steps, with the
   request-put / request-get / request-materialize / step / abandon
   shape the deleted dao.jing.remote.step carried, over a requests
   writer and an answers reader that are reflections or local handles.

   Everything here is a pure function over explicit data. No socket,
   atom, promise, callback, or scheduler. The caller owns the state,
   attaches it to whatever pair it has, and chooses every cadence.

   The state is {:requests w :answers r :cursor c :unsent u
   :outstanding {s request} :completed [...] :diagnostics [...]
   :terminal nil :materializations {put-id record} :routes {live-id
   put-id}}. A cursor is either one the answers reader handed out or an
   anchor keyword -- :dao.stream/newest, the Linda mint -- which the
   first poll resolves through the reader, retrying while the reader
   answers a retryable mint (a reflection's cursor ask). A materialization
   record is {:phase :put|:verify-unissued|:verify-issued :address a
   :put-id s :get-id m?} -- an address, never a payload: nothing this
   layer retains or publishes carries a payload. :routes maps every
   answer id a record currently claims to the record's put id, which is
   the identity a materialization's one published completion carries.

   step advances in one fixed order, and the order is load-bearing:

   1. re-append the retained :unsent request (a writer that answered
      full), folding the outcome under :attempt;
   2. poll at most `budget` answers, correlating each by :jing/request,
      filing unknown or malformed values as diagnostics;
   3. on a terminal reader, lose the retained request with the terminal
      reason, so its loss surfaces on the ordinary completion path;
   4. issue the unissued verify reads of :verify-unissued records, in
      the order their puts were issued, until the writer answers full
      or the reader ends;
   5. route every completion: a found get answer and a :present verify
      pass through the one ingress check dao.jing/accept-bytes! before
      any value is published.

   The verify hop is the one obligation the client carries for
   materialization: a put answering :present has promised that equal
   content sits at the address, and a remote server is not trusted to
   have kept that promise. So :present publishes nothing; the record
   moves to :verify-unissued, a correlated read is issued by order 4,
   and only a read-back that verifies against the address completes
   :result :present. The record's removal is the exactly-once guard:
   one completion per materialization, carrying the put id.

   Reattachment stays the caller's: after a terminal reason every
   further request answers :terminal, and a new pair is a new client.
   abandon retires only the retained :unsent request -- the one delivery
   this binding still owes."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]))


;; =============================================================================
;; Vocabulary
;; =============================================================================


(def get-op
  "The read operation this client speaks."
  :jing/get)


(def put-op
  "The write operation this client speaks."
  :jing/put)


(def malformed-response-code
  "The error code for an answer that does not conform to its request's
   wire vocabulary -- a get not answering the exact found?/bytes shape,
   a put not answering :inserted or :present."
  :dao.jing.content/malformed-response)


(def integrity-failure-code
  "The error code for content that fails the ingress check on arrival:
   a found read-back that does not hash to the address it was read from,
   or bytes that are not one canonical payload."
  :dao.jing.content/integrity-failure)


(def present-but-absent-code
  "The error code for a put that answered :present and a verify read
   that then found nothing at the address."
  :dao.jing.content/present-but-absent)


(def abandoned-code
  "The default abandon reason: this layer's own word for a request given
   up by policy rather than lost by the medium."
  :dao.jing.content/abandoned)


(def ^:private integrity-failure
  "The error map a failed ingress check publishes, by code."
  {:code integrity-failure-code
   :message "the remote content does not hash to its content address"})


(def ^:private malformed-response
  "The error map for an answer outside its request's vocabulary."
  {:code malformed-response-code
   :message "the answer does not conform to the request's vocabulary"})


(def ^:private present-but-absent
  {:code present-but-absent-code
   :message "the remote reported :present but the content address is absent"})


(defn- mint-id
  "A self-minted random request id, unique against this client's live
   ids so callers sharing one answers stream do not collide."
  [state]
  (loop []
    (let [id (str (random-uuid))]
      (if (or (contains? (:outstanding state) id)
              (= id (get-in state [:unsent :id])))
        (recur)
        id))))


(defn- answer?
  "True for any well-formed answer value: the request id key, whatever
   body follows it."
  [v]
  (and (map? v) (contains? v :jing/request)))


;; =============================================================================
;; State
;; =============================================================================


(defn client-state
  "The stepped client's state over one content pair: `requests` the
   writer the client appends its requests to, `answers` the reader it
   polls, `cursor` either a cursor `answers` handed out or an anchor
   keyword (:dao.stream/newest, the Linda mint, resolved on the first
   poll -- a reflection's mint is answered across the channel, so the
   first steps may retry it). The caller owns the result and every step
   of it."
  [requests answers cursor]
  {:requests requests
   :answers answers
   :cursor cursor
   :unsent nil
   :outstanding {}
   :completed []
   :diagnostics []
   :terminal nil
   :next-seq 0
   :materializations {}
   :routes {}})


(defn- segment-address!
  "Throw unless `address` is a segment address: arbitrary keys and
   mutable roots are refused before the wire."
  [address]
  (when-not (jing/segment-address? address)
    (throw (ex-info "a stepped content request names a non-segment address"
                    {:address address}))))


(defn- register
  "Claim `id` for a materialization: the record (carrying its issue
   order under :seq -- the ids are random, so the order the verify reads
   issue in is the record's own) and its route."
  [state id address]
  (-> state
      (assoc-in [:materializations id]
                {:phase :put, :address address, :put-id id
                 :seq (:next-seq state)})
      (update :next-seq (fnil inc 0))
      (assoc-in [:routes id] id)))


(defn- submit
  "The shared body of the request entry points: answer :terminal on a
   client whose answers reader has ended (a new pair is a new client),
   refuse with :busy while an unsent request is owed (only step's order
   1 clears it), append the request value, and register a
   materialization record at any id the writer's answer carries. A
   writer refusal completes at once -- the record routes that loss at
   the next drain."
  [state request materialize?]
  (cond
    (:terminal state)
    {:outcome :terminal, :state state}

    (:unsent state)
    {:outcome :busy, :state state}

    :else
    (let [id (mint-id state)
          value (assoc request :jing/request id)
          result (stream/append! (:requests state) value)
          outcome (:dao.stream/outcome result)
          address (get request put-op)]
      (cond
        (= :dao.stream/ok outcome)
        {:outcome :requested
         :state (-> (assoc-in state [:outstanding id] request)
                    (cond-> materialize? (register id address)))
         :id id}

        (= :dao.stream/full outcome)
        {:outcome :pending-request
         :state (-> (assoc state :unsent {:id id, :value value})
                    (cond-> materialize? (register id address)))
         :id id}

        :else
        ;; closed, invalid-value, transport-error: the append never
        ;; happened; the loss completes on the ordinary path
        {:outcome :request-undeliverable
         :state (-> (update state :completed
                            conj {:id id, :request request, :lost outcome})
                    (cond-> materialize? (register id address)))
         :id id
         :reason outcome}))))


;; =============================================================================
;; The request entry points
;; =============================================================================


(defn request-get
  "Request one remote read of `address`. A non-segment address throws
   before the wire. While an unsent request is owed the answer is
   {:outcome :busy :state s} and nothing was submitted; otherwise the
   outcome is returned with the minted id under :id, and the read's
   completion arrives through step as {:id s :found? b :value v} (or an
   :error, or a :lost tail)."
  [state address]
  (segment-address! address)
  (submit state {get-op address} false))


(defn request-put
  "Request one remote put of `payload` at `address`. A non-segment
   address throws before the wire; address-against-payload validation is
   the server's at its own door, and a mismatch is the server's silence.
   A plain put publishes :result :inserted or :result :present with no
   verify read: the read-back is materialization's obligation."
  [state address payload]
  (segment-address! address)
  (submit state {put-op address
                 :jing/bytes (jing/bytes->base64
                               (jing/canonical-bytes payload))}
          false))


(defn request-put-bytes
  "The bytes-level twin of request-put for a caller already holding the
   canonical payload bytes -- the byte-store handle's own put. The bytes
   must hash to `address`; the server's ingress check refuses them
   otherwise, and a refused request is the server's silence."
  [state address bs]
  (segment-address! address)
  (submit state {put-op address
                 :jing/bytes (jing/bytes->base64 bs)}
          false))


(defn request-materialize
  "Request one remote materialization of `payload`: the address is
   derived here, the put is submitted, and the returned :id is the put
   id the one completion will carry. Supports an explicit algorithm via
   opts: {:algorithm :sha256}."
  ([state payload] (request-materialize state payload {}))
  ([state payload opts]
   (let [bs (jing/canonical-bytes payload)
         algo (get opts :algorithm jing/default-hash-algorithm)
         reg (get jing/registry algo)]
     (when-not reg
       (throw (ex-info (str "unsupported hash algorithm: " algo)
                       {:algorithm algo})))
     (let [address (keyword "segment"
                            (str (:address-id reg)
                                 "-" (jing/digest-bytes algo bs)))]
       (submit state {put-op address
                      :jing/bytes (jing/bytes->base64 bs)}
               true)))))


(def ^:private refused
  "The ingress-refusal sentinel for the strict receipt decode below: one
   opaque host object held by this single var, so every reference to it
   is the same instance on every host. A keyword literal cannot serve:
   on cljs each literal site compiles to a fresh Keyword object, so an
   `identical?` across two sites misses, and a refused decode escaped
   into the value arm. The closed CBOR profile never decodes a host
   object, so no decoded value can be this; the sentinel itself is what
   distinguishes a refused reply from a stored nil, which decodes to the
   value nil."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defn- accepted-value
  "The value a found get answer carries, admitted through the one
   ingress check (dao.jing/accept-bytes!) and decoded, or `refused` when
   the check refuses it."
  [address b64]
  (try
    (cbor/decode (jing/accept-bytes! address b64))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      refused)))


;; =============================================================================
;; Completion decode
;; =============================================================================


(defn- get-answer?
  "True only for the exact read answer {:jing/request r :jing/found? b
   :jing/bytes b64}."
  [v]
  (and (map? v)
       (= #{:jing/request :jing/found? :jing/bytes} (set (keys v)))
       (let [f (get v :jing/found?)] (or (true? f) (false? f)))))


(defn- put-answer?
  "True only for the exact write answer {:jing/request r
   :jing/result v}."
  [v]
  (and (map? v) (= #{:jing/request :jing/result} (set (keys v)))))


(defn- decode-plain-completion
  "Total decode of one completion no materialization claims: the answer
   conforms to its request's vocabulary or the completion carries an
   :error; a loss carries :lost."
  [completion]
  (let [{:keys [id request lost answer]} completion]
    (cond
      lost {:id id, :lost lost}

      (contains? request get-op)
      (cond
        (not (get-answer? answer)) {:id id, :error malformed-response}
        (not (get answer :jing/found?))
        {:id id, :found? false, :value nil}
        :else
        (let [value (accepted-value (get request get-op)
                                    (get answer :jing/bytes))]
          (if (identical? refused value)
            {:id id, :error integrity-failure}
            {:id id, :found? true, :value value})))

      :else
      (if (and (put-answer? answer)
               (#{:inserted :present} (get answer :jing/result)))
        {:id id, :address (get request put-op),
         :result (get answer :jing/result)}
        {:id id, :error malformed-response}))))


;; =============================================================================
;; The materialization transition
;; =============================================================================


(defn- materialization-entry
  "The published tail of one materialization completion: the put id, the
   marker, and the address -- never the payload."
  [record]
  {:id (get record :put-id)
   :materialized? true
   :address (get record :address)})


(defn- materialize-on-completion
  "Route one completion a materialization record claims, returning
   [published records routes]. A :put-phase completion carrying :present
   publishes nothing and moves the record to :verify-unissued -- the put
   id's route dies with the completion, because no future completion can
   bear an id already answered -- while every other transition removes
   the record (the exactly-once guard) and publishes its one entry."
  [completion record published records routes]
  (let [{:keys [id lost answer]} completion
        finish
        (fn [tail]
          [(conj published (merge (materialization-entry record) tail))
           (dissoc records (get record :put-id))
           (dissoc routes id)])]
    (cond
      lost (finish {:lost lost})

      (contains? (:request completion) get-op)
      (cond
        (not (get-answer? answer)) (finish {:error malformed-response})
        (not (get answer :jing/found?)) (finish {:error present-but-absent})
        :else
        (let [value (accepted-value (get record :address)
                                    (get answer :jing/bytes))]
          (if (identical? refused value)
            (finish {:error integrity-failure})
            (finish {:result :present}))))

      :else
      (let [verdict (and (put-answer? answer)
                         (get answer :jing/result))]
        (cond
          (= :inserted verdict) (finish {:result :inserted})
          (= :present verdict)
          [published
           (assoc records (get record :put-id)
                  (assoc record :phase :verify-unissued))
           (dissoc routes id)]
          :else (finish {:error malformed-response}))))))


(defn- route-completions
  "Order 5's routing: fold the drained completions against the live
   records, then return the published entries and the successor records
   and routes."
  [completions records routes]
  (let [[published records routes]
        (reduce (fn [[published records routes] completion]
                  (if-some [put-id (get routes (:id completion))]
                    (if-some [record (get records put-id)]
                      (materialize-on-completion
                        completion record published records routes)
                      [(conj published (decode-plain-completion completion))
                       records
                       routes])
                    [(conj published (decode-plain-completion completion))
                     records
                     routes]))
                [[] records routes]
                completions)]
    {:published published :records records :routes routes}))


;; =============================================================================
;; Orders 2 to 4 -- the polls and the verify reads
;; =============================================================================


(defn- lose-outstanding
  "Complete every outstanding request with `reason` -- each loss routes
   through its claim at order 5, so a materialization's record finishes
   exactly once. A terminal reason also ends the binding."
  [state reason terminal?]
  (let [state (reduce (fn [s [id request]]
                        (update s :completed
                                conj {:id id, :request request,
                                      :lost reason}))
                      state
                      (:outstanding state))]
    (cond-> (assoc state :outstanding {})
      terminal? (assoc :terminal reason))))


(defn- mint-cursor
  "Resolve an anchor-keyword cursor through the answers reader: the
   Linda mint (:dao.stream/newest) on a reflection is answered across
   the channel, so a retryable answer leaves the anchor in place for
   the next step. Returns [cursor settled?]."
  [answers anchor]
  (let [r (stream/cursor answers anchor)]
    (if (= :dao.stream/ok (:dao.stream/outcome r))
      [(:dao.stream/cursor r) true]
      [anchor false])))


(defn- poll
  "Order 2: read at most `budget` answers, correlating each by its
   :jing/request id. A blocked read stops the step; a gap loses the
   outstanding requests without ending the binding; a reader that ends,
   errs, or answers an unrecognized outcome ends it. Non-answer values
   and answers nobody claims are filed as diagnostics, taken exactly
   once."
  [state budget]
  (loop [state state
         left budget]
    (cond
      (:terminal state) state

      (contains? stream/standard-anchors (:cursor state))
      (let [[cursor settled?] (mint-cursor (:answers state)
                                           (:cursor state))]
        (if settled? (recur (assoc state :cursor cursor) left) state))

      (zero? left) state

      :else
      (let [r (stream/next (:answers state) (:cursor state))
            outcome (:dao.stream/outcome r)]
        (cond
          (= :dao.stream/ok outcome)
          (let [v (:dao.stream/value r)
                state (assoc state :cursor (:dao.stream/cursor r))]
            (if-not (answer? v)
              (recur (update state :diagnostics
                             conj {:code :dao.jing.content/malformed-answer
                                   :value v})
                     (dec left))
              (let [id (get v :jing/request)]
                (if-some [request (get (:outstanding state) id)]
                  (recur (-> state
                             (update :outstanding dissoc id)
                             (update :completed
                                     conj {:id id, :request request,
                                           :answer v}))
                         (dec left))
                  (recur (update state :diagnostics
                                 conj {:code
                                       :dao.jing.content/unsolicited-answer
                                       :value v})
                         (dec left))))))

          (= :dao.stream/blocked outcome) state

          (= :dao.stream/gap outcome)
          (recur (lose-outstanding
                   (assoc state :cursor (:dao.stream/cursor r))
                   :dao.stream/gap false)
                 left)

          :else
          (recur (lose-outstanding state
                                   (if (keyword? outcome)
                                     outcome
                                     :dao.stream/transport-error)
                                   true)
                 left))))))


(defn- issue-pending-verifies
  "Order 4: issue the correlated verify reads of every :verify-unissued
   record, in the order their puts were issued, until the writer
   answers full (an unsent
   request is all this client retains, so nothing else can be submitted
   behind it) or the state is terminal -- a terminal state completes
   each remaining record's loss here, because no wire completion will
   ever bear its claim."
  [state]
  (loop [state state
         pending (seq (sort-by (fn [[_id record]] (:seq record))
                               (filter (fn [[_id record]]
                                         (= :verify-unissued
                                            (:phase record)))
                                       (:materializations state))))]
    (if (nil? pending)
      state
      (let [[put-id record] (first pending)]
        (cond
          (:terminal state)
          (recur (-> state
                     (update :completed
                             conj (merge (materialization-entry record)
                                         {:lost (:terminal state)}))
                     (update :materializations dissoc put-id))
                 (next pending))

          (:unsent state) state

          :else
          (let [id (mint-id state)
                request {get-op (:address record)}
                result (stream/append! (:requests state)
                                       (assoc request :jing/request id))
                outcome (:dao.stream/outcome result)
                state (assoc-in state
                                [:materializations put-id :get-id] id)]
            (cond
              (= :dao.stream/ok outcome)
              (recur (-> state
                         (assoc-in [:outstanding id] request)
                         (assoc-in [:routes id] put-id)
                         (update :materializations assoc-in
                                 [put-id :phase] :verify-issued))
                     (next pending))

              (= :dao.stream/full outcome)
              ;; retained unsent; order 1 of the next step retries it
              (-> state
                  (assoc :unsent {:id id,
                                  :value (assoc request :jing/request id)})
                  (update :materializations assoc-in
                          [put-id :phase] :verify-issued)
                  (assoc-in [:routes id] put-id))

              :else
              ;; closed, invalid-value, transport-error: the loss is
              ;; already outboxed and order 5 routes it this same step
              (recur (-> state
                         (update :completed
                                 conj {:id id, :request request,
                                       :lost outcome})
                         (assoc-in [:routes id] put-id))
                     (next pending)))))))))


;; =============================================================================
;; step and abandon
;; =============================================================================


(defn step
  "One non-waiting advance of the stepped client: the five fixed orders
   of the namespace docstring. Returns {:state s' :attempt k?
   :completions [...] :diagnostics [...]} -- :attempt only when an
   unsent request was re-appended; :completions the published,
   totally-decoded entries (materialization entries carrying their put
   id and :materialized? true); :diagnostics the diagnostic outbox,
   taken exactly once. Liveness and terminality are the state's, not
   the result's: read (:terminal state') to learn whether the answers
   reader has ended, and keep stepping while you have work owed.

   This is an interpreter step -- it performs stream operations -- under
   a single-owner precondition: one caller, one state thread."
  [state budget]
  (let [attempt (when (and (:unsent state) (not (:terminal state)))
                  (stream/append! (:requests state)
                                  (:value (:unsent state))))
        outcome (:dao.stream/outcome attempt)
        state (if attempt
                (let [{:keys [id]} (:unsent state)]
                  (cond
                    (= :dao.stream/ok outcome)
                    (-> (assoc-in state [:outstanding id]
                                  (dissoc (:value (:unsent state))
                                          :jing/request))
                        (assoc :unsent nil))

                    (= :dao.stream/full outcome) state

                    :else
                    (-> (update state :completed
                                conj {:id id,
                                      :request (dissoc (:value
                                                         (:unsent state))
                                                       :jing/request),
                                      :lost outcome})
                        (assoc :unsent nil))))
                state)
        polled (poll state budget)
        state (if (and (:terminal polled) (:unsent polled))
                (-> polled
                    (update :completed
                            conj {:id (get-in polled [:unsent :id]),
                                  :request (dissoc (:value
                                                     (:unsent polled))
                                                   :jing/request),
                                  :lost (:terminal polled)})
                    (assoc :unsent nil))
                polled)
        issued (issue-pending-verifies state)
        routed (route-completions (:completed issued)
                                  (:materializations issued)
                                  (:routes issued))]
    (cond-> {:state (-> issued
                        (assoc :completed [])
                        (assoc :diagnostics [])
                        (assoc :materializations (:records routed))
                        (assoc :routes (:routes routed)))
             :completions (vec (:published routed))
             :diagnostics (:diagnostics issued)}
      attempt (assoc :attempt outcome))))


(defn retire
  "Give up on the outstanding request `id` with `reason` (the blocking
   driver's deadline or interruption): the id leaves :outstanding, every
   route claiming one of its record's ids dies -- so any late answer is
   dropped as unsolicited -- and a materialization record the id still
   claims is completed :lost with it, so exactly one completion per
   materialization survives the retirement."
  ([state id] (retire state id abandoned-code))
  ([state id reason]
   (let [put-id (get (:routes state) id)
         record (get (:materializations state) put-id)]
     (if record
       (let [claimed (remove nil? [put-id (get record :get-id)])]
         (-> (reduce (fn [s i]
                       (-> s
                           (update :outstanding dissoc i)
                           (update :routes dissoc i)))
                     state
                     claimed)
             (update :completed
                     conj (merge (materialization-entry record)
                                 {:lost reason}))
             (update :materializations dissoc put-id)))
       (-> state
           (update :outstanding dissoc id)
           (update :routes dissoc id))))))


(defn abandon
  "Give up on the retained unsent request with `reason` (or this
   layer's abandoned-code): the composition that is about to change what
   the writer is -- an operator disconnect, a rebind onto a fresh
   attachment -- owns the decision that the retained request no longer
   belongs to the new binding. The loss is appended as an ordinary
   completion and surfaces at the next step's drain, routed by its id.
   Records with nothing on the wire are untouched: abandon retires the
   one delivery this binding still owes, not the medium's in-flight
   answers."
  ([state] (abandon state abandoned-code))
  ([state reason]
   (if-some [unsent (:unsent state)]
     (-> state
         (update :completed conj {:id (:id unsent),
                                  :request (dissoc (:value unsent)
                                                   :jing/request),
                                  :lost reason})
         (assoc :unsent nil))
     state)))
