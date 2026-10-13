(ns yin.vm.linker.admission
  "The admission pair (yin.vm.linker.dht.md section 7.5): the trust
   question a DHT link source asks before it loads a name another
   principal asserts, and the interpreter that answers it.

   Two ring media sit beside the link pair.  The composition appends a
   question -- plain data bound to the resolution it was asked about,
   carrying no session and no subject -- to the question medium and
   parks the link; one interpreter reads the questions and appends one
   answer each to the answer medium; the composition drains the answers
   with a persisted cursor and keeps those that match a question it
   still holds.  Neither side calls the other: each knows only the two
   media.

   The composition's half is a value, `make-state`: the pair, the
   questions it has asked keyed by link id, the matching answers it has
   drained keyed by admission id `[link-id serial]`, and the serial it
   mints the next id from, never reused.  The interpreter's half is
   another value, `interpreter`: its question cursor and the decision
   snapshot it last read.  No clock, no atom, no global: every outcome
   is plain data, and every read persists its cursor.

   The interpreter answers with `(verify decision context question)`,
   the ShiBi verify signature (dao.shibi.md): nil allows, a reason map
   refuses.  The default policy's decision is the composition's own
   principals and an explicit trusted set; it allows a question one of
   whose asserters is in it.  Trust is not safety: the linker checks
   closure origin, store ownership and dependency pins whatever the
   answer says, and conditions must be empty."
  (:require [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.vm.linker.sign :as sign]))


(def reasons
  "Every reason the admission seam refuses with: the default policy's,
   the composition's fail-closed refusals of an answer it cannot honor,
   and the refusal of a DHT source composed with no admission state."
  #{:yin.admit/untrusted-principal
    :yin.admit/decision-ended
    :yin.admit/malformed-answer
    :yin.admit/unsupported-condition
    :yin.admit/unconfigured})


(defn- medium
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- ok?
  [r]
  (= :dao.stream/ok (:dao.stream/outcome r)))


;; =============================================================================
;; The composition's half
;; =============================================================================

(defn make-state
  "The composition's admission state over a fresh pair of `capacity`
   elements each: the question and answer media, the answer cursor
   minted at the answer medium's oldest end before any answer exists,
   nothing asked, nothing answered, serial 0.  An unread answer is
   evicted when the unread backlog exceeds `capacity`: the host bounds
   that backlog by its production rate and drain cadence, and an
   evicted answer's question stays open until its request is retired."
  [capacity]
  (let [questions (medium capacity)
        answers (medium capacity)]
    {:pair {:questions questions
            :answers answers
            :cursor (oldest answers)}
     :asked {}
     :answers {}
     :serial 0}))


(defn- answer-link-id
  "The link id component of answer `a`'s admission id, or nil when the
   answer carries no `[link-id serial]`."
  [a]
  (let [id (when (map? a) (:yin.admit/id a))]
    (when (and (vector? id) (= 2 (count id)))
      (first id))))


(defn- keep-answer
  "`admission` with answer `a` retained when its id is exactly the id of
   the question asked for its link id and no answer is held for it yet:
   the first answer wins, and a duplicate, a stale serial, or an answer
   for an unknown or retired id correlates to nothing and is dropped."
  [admission a]
  (let [id (:yin.admit/id a)
        asked (get-in admission [:asked (answer-link-id a)])]
    (if (and (some? asked)
             (= id (:yin.admit/id asked))
             (not (contains? (:answers admission) id)))
      (assoc-in admission [:answers id] a)
      admission)))


(defn drain
  "Consume at most `budget` elements of the answer medium from the
   composition's persisted cursor, keeping the answers that match a
   question still asked (`keep-answer`), and answer the successor state
   with the cursor past everything read.  A gap adopts the recovery
   cursor the transport answers -- the earliest retained position,
   never an evicted one -- and continues within the budget; blocked or
   end stops.  The same function serves a link round and the host's
   recheck hook."
  [admission budget]
  (let [answers (get-in admission [:pair :answers])]
    (loop [admission admission
           left budget]
      (if (<= left 0)
        admission
        (let [r (stream/next answers (get-in admission [:pair :cursor]))
              admission' (assoc-in admission [:pair :cursor]
                                   (:dao.stream/cursor r))]
          (case (:dao.stream/outcome r)
            :dao.stream/ok (recur (keep-answer admission'
                                               (:dao.stream/value r))
                                  (dec left))
            :dao.stream/gap (recur admission' (dec left))
            admission))))))


(defn ask
  "Append the question for link `request` over the resolved `entry` of
   its name, bound to that resolution: the manifest address, every
   asserter, and the entry's provenance verbatim, with the request's own
   format and contract.  Answers `{:admission a :id id}` with the
   question retained under its link id and the serial advanced, or
   `{:admission admission}` unchanged when the medium refused it."
  [admission request entry]
  (let [link-id (:yin.link/id request)
        id [link-id (:serial admission)]
        provenance (:yin.link/provenance entry)
        question {:yin.admit/id id
                  :yin.link/id link-id
                  :yin.admit/name (:yin.link/name request)
                  :yin.admit/address (:address entry)
                  :yin.admit/asserters (vec (:yin.module/asserted-by
                                              provenance))
                  :yin.admit/provenance provenance
                  :yin.link/format (:yin.link/format request)
                  :yin.link/contract (:yin.link/contract request)}]
    (if (ok? (stream/append! (get-in admission [:pair :questions]) question))
      {:admission (-> admission
                      (assoc-in [:asked link-id] question)
                      (update :serial inc))
       :id id}
      {:admission admission})))


(defn asked
  "The question `admission` holds for `link-id`, or nil."
  [admission link-id]
  (get-in admission [:asked link-id]))


(defn answer-to
  "The retained answer to `question`, or nil."
  [admission question]
  (get-in admission [:answers (:yin.admit/id question)]))


(def ^:private allow-keys
  #{:yin.admit/id :yin.admit/decision :yin.admit/conditions})


(defn judge
  "nil when `answer` allows with empty conditions; otherwise the refusal
   it amounts to, as `{:yin.admit/reason r ...}`.  A refusal keeps the
   answer's reason and open keys.  An allow's key set is closed: exactly
   id, decision and conditions.  Non-empty conditions refuse
   `:yin.admit/unsupported-condition` and anything else that is not such
   an allow or a refusal with a keyword reason refuses
   `:yin.admit/malformed-answer`: both fail closed."
  [answer]
  (let [decision (:yin.admit/decision answer)
        conditions (:yin.admit/conditions answer)
        allow? (and (= :allow decision)
                    (= allow-keys (set (keys answer)))
                    (map? conditions))]
    (cond
      (and allow? (empty? conditions)) nil

      allow?
      {:yin.admit/reason :yin.admit/unsupported-condition
       :yin.admit/conditions conditions}

      (and (= :refuse decision) (keyword? (:yin.admit/reason answer)))
      (dissoc answer :yin.admit/id :yin.admit/decision)

      :else {:yin.admit/reason :yin.admit/malformed-answer})))


(defn settle
  "`admission` with the question for `link-id` and its answer dropped:
   the link's response has landed."
  [admission link-id]
  (let [q (asked admission link-id)]
    (cond-> admission
      q (-> (update :asked dissoc link-id)
            (update :answers dissoc (:yin.admit/id q))))))


(defn forget
  "`admission` with every question and answer of the retired link ids
   `link-ids` dropped.  A later answer for one correlates to nothing and
   `drain` drops it."
  [admission link-ids]
  (let [gone (set link-ids)]
    (-> admission
        (update :asked #(reduce dissoc % gone))
        (update :answers
                #(into {} (remove (fn [[id _]] (contains? gone (first id)))) %)))))


;; =============================================================================
;; The interpreter's half
;; =============================================================================

(defn context
  "The composition-owned context `verify` receives, from the session:
   the subject, the principal of the composition's own key (nil without
   one), and the session token.  Authorization vocabulary stays here and
   in the policy; the question and the linker carry none."
  [{:keys [key session]}]
  {:yin.admit/subject (when key (sign/principal (:public key)))
   :yin.admit/session session})


(defn trusted-asserter
  "The default verify: allow when some asserter of `question` is in the
   `decision` set, else refuse `:yin.admit/untrusted-principal` naming
   the asserters."
  [decision _context question]
  (let [asserters (vec (:yin.admit/asserters question))]
    (when-not (some #(contains? decision %) asserters)
      {:yin.admit/reason :yin.admit/untrusted-principal
       :yin.admit/asserters asserters})))


(defn- composed
  "One verify of `verify`: a function, or a sequence of them composed so
   the first reason wins."
  [verify]
  (cond
    (nil? verify) trusted-asserter
    (fn? verify) verify
    :else (fn [decision context question]
            (some #(% decision context question) verify))))


(defn policy
  "The interpreter's policy from a composition's `:admission` option:
   `:own` the composition's own principals, `:trusted` an explicit set
   of principal ids (`ed25519:<hex>`), `:verify` a verify function or a
   sequence of them, and the decision snapshot: a static `:decision`, or
   the newest value of a capacity-1 `:decision-medium`, or by default
   the union of own and trusted principals."
  [{:keys [own trusted verify decision decision-medium] :as opts}]
  (let [principals (into (set own) trusted)]
    (cond-> {:own (set own)
             :trusted (set trusted)
             :verify (composed verify)}
      decision-medium (assoc :decision-medium decision-medium)
      (contains? opts :decision) (assoc :decision decision)
      (not (or decision-medium (contains? opts :decision)))
      (assoc :decision principals))))


(defn interpreter
  "The in-process interpreter's state over `admission`'s pair under
   `policy`: the two media, its question cursor minted at the oldest
   end, and its decision-medium cursor when the policy names one."
  [admission policy]
  (let [{:keys [questions answers]} (:pair admission)
        decisions (:decision-medium policy)]
    (cond-> {:questions questions
             :answers answers
             :cursor (oldest questions)}
      decisions (assoc :decision-cursor (oldest decisions)))))


(defn- read-decisions
  "`interp` after reading the decision medium to its present end: the
   newest value read is the snapshot, an ended medium is `:ended?`."
  [interp medium]
  (loop [interp interp]
    (let [r (stream/next medium (:decision-cursor interp))]
      (case (:dao.stream/outcome r)
        :dao.stream/ok (recur (assoc interp
                                     :decision-cursor (:dao.stream/cursor r)
                                     :snapshot (:dao.stream/value r)
                                     :snapshot? true))
        :dao.stream/gap (recur (assoc interp
                                      :decision-cursor
                                      (:dao.stream/cursor r)))
        :dao.stream/end (assoc interp :ended? true)
        interp))))


(defn- snapshot
  "`interp` with this round's decision snapshot, read once."
  [interp policy]
  (if-let [medium (:decision-medium policy)]
    (read-decisions interp medium)
    (assoc interp :snapshot (:decision policy) :snapshot? true)))


(defn- decide
  [interp policy context question]
  (let [base {:yin.admit/id (:yin.admit/id question)}]
    (if (:ended? interp)
      (assoc base
             :yin.admit/decision :refuse
             :yin.admit/reason :yin.admit/decision-ended)
      (let [r ((:verify policy) (:snapshot interp) context question)]
        (cond
          (nil? r) (assoc base
                          :yin.admit/decision :allow
                          :yin.admit/conditions {})
          (map? r) (merge r base {:yin.admit/decision :refuse})
          :else (assoc base :yin.admit/decision :refuse))))))


(defn serve
  "One round of the interpreter: read the decision snapshot once, then
   answer at most `budget` questions in order, one answer each.  With no
   snapshot yet and the decision medium not ended, nothing is answered
   and the questions stay.  `verify` runs synchronously on the one
   snapshot; it opens, fetches and waits for nothing.  An answer the
   medium refuses stops the round with its question unread.  Returns
   `{:interpreter i :progress? bool :answered n}`."
  [{:keys [interpreter policy context]} budget]
  (let [interp (snapshot interpreter policy)]
    (if-not (or (:ended? interp) (:snapshot? interp))
      {:interpreter interp, :progress? false, :answered 0}
      (loop [interp interp
             n 0
             left budget]
        (let [done {:interpreter interp, :progress? (pos? n), :answered n}]
          (if (<= left 0)
            done
            (let [r (stream/next (:questions interp) (:cursor interp))]
              (case (:dao.stream/outcome r)
                :dao.stream/ok
                (let [a (decide interp policy context (:dao.stream/value r))]
                  (if (ok? (stream/append! (:answers interp) a))
                    (recur (assoc interp :cursor (:dao.stream/cursor r))
                           (inc n)
                           (dec left))
                    done))

                :dao.stream/gap
                (recur (assoc interp :cursor (:dao.stream/cursor r))
                       n
                       (dec left))

                done))))))))
