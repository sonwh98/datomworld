(ns yin.vm.ucf.authority.front
  "The authority's front of M-next C slice C11: plain functions over
   plain DaoStreams that carry the authority's transitions to holders
   outside its process (UCF 7.7.8, 7.9; docs/design/yin.vm.linker.dht.md
   14.2.2 to 14.2.4).

   Shape.  Each holder has an inbound stream, where its request values
   arrive, and a reply stream, where the front appends its answers.
   `front` makes the state of one holder's front from the authority
   value and the streams the composition supplies; `step` reads at most
   n requests from the held inbound cursor, answers each, and returns
   the next state.  No clock, no threads: a composition calls `step`
   when it chooses, and nothing here loops past n reads or past the
   tail.

   Requests.  A request is a map dispatching on `:yin.k/request`.  Every
   request carries a holder-chosen `:yin.k/request-id`, opaque to the
   front and echoed unchanged in its reply; it is the only correlation
   handle a reply carries, and correlation itself is the driver's (stage
   D).  The set (`requests`) is closed:

     {:yin.k/request :yin.k/offer :yin.k/request-id r
      :yin.k/id address :yin.k/bytes bs :yin.k/medium m}
                          grant/offer! into the composition's store
     {:yin.k/request :yin.k/proposal :yin.k/request-id r
      :dao.lease/proposal pid :yin.k/occurrence o}
                          the lease proposal, appended to the holder's
                          lease-fact stream, where the judge drains it
                          on its next grant/step!
     {:yin.k/request :yin.k/resumed :yin.k/request-id r
      :yin.k/report resumed-fact :yin.k/bytes bs}
                          completion/report!
     {:yin.k/request :yin.k/release :yin.k/request-id r
      :dao.lease/lease l} the holder's release, appended to its
                          lease-fact stream; the judge's release lapse
                          completes or reclaims (slice C8)
     {:yin.k/request :yin.k/renewal :yin.k/request-id r
      :dao.lease/lease l} the holder's renewal, appended to its
                          lease-fact stream; the judge counts it as
                          evidence of the holder when it drains it
     {:yin.k/request :yin.k/input :yin.k/request-id r
      :yin.k/input input-request}
                          input/record-input!
     {:yin.k/request :yin.k/admit :yin.k/request-id r
      :yin.k/target i :yin.k/fenced-envelope e}
                          admission/admit! at enrolled target i, with
                          the same store (an inherited id's accepted
                          checkpoint is read from it)

   Attribution in.  The composition's resolver, `(fn [inbound-identity
   value] -> author or nil)`, names the author of each delivered
   request; an author field inside the request is never consulted.  A
   request whose required keys are missing, or whose kind is not in
   `requests`, is `:malformed`; a well-formed request with no resolved
   author (nil, or a throwing resolver) is `:wrong-author`.  Either
   commits nothing, carries nothing, gets no reply, and appends exactly
   one diagnostic to the composition's diagnostic stream (C7's
   contract):

     {:yin.k/diagnostic :yin.k/defective-request
      :yin.k/defect     :malformed | :wrong-author
      :yin.k/inbound    the inbound stream's identity
      :yin.k/author     the resolved author, when there is one
      :yin.k/claimed    {:yin.k/request k :yin.k/request-id r}}

   A failed diagnostic append is returned as data.  An author resolved
   to a non-holder reaches the landed function, which refuses it as it
   would refuse a direct call.  A request whose landed function throws
   is answered as `:malformed`: nothing was committed, and the throw is
   an argument defect or an implementation fault; the diagnostic's
   append result is returned beside it.

   Replies.  Every other request gets one reply,

     {:yin.k/reply k :yin.k/request-id r :yin.k/answer answer}

   where answer is exactly the landed function's answer: a
   `:yin.k/admission` outcome for an admit, a `:yin.k/status` map for
   the rest, and `{:yin.k/status :carried}` once a proposal, a release
   or a renewal is on the holder's lease-fact stream.  An admit that
   admission
   answers with a diagnostic, or at a boundary it never enrolled, gets
   no reply: admission produced no outcome.  While the authority serves
   no projection, poisoned or closed, a proposal, release or renewal is
   not carried and answers `{:yin.k/status :suspended :yin.k/reason
   :unavailable}`; the landed functions answer the other kinds
   themselves.  A holder with no lease-fact stream is refused
   `:no-lease-medium`; a lease-fact stream that cannot be named (its
   function throws) or that does not take the append answers
   `:suspended :uncarried`.  A `:committed` admission is also on the
   authority's outcome projection (admission/outcome-reader), read with
   a kept cursor.  A lost reply is recovered by sending the request
   again: offers, reports, inputs and admissions answer `:replayed`
   through the ledger's dedup, and the judge answers a proposal or a
   release once whatever the number of copies.

   Attribution out.  Replies and the outcome projection are authored by
   the authority: they are derived from its ledger.  `reply-evidence`
   is the pure rule a holder's reader uses: a record counts only when
   the author its composition attributes to the stream it was read from
   is the arbitration identity, by identity equality; descriptors are
   never compared.  A `:committed` or `:intent-conflict` from any other
   author discharges nothing."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.custody :as custody]))


(def ^:private required
  {:yin.k/offer [:yin.k/id :yin.k/bytes :yin.k/medium]
   :yin.k/proposal [:dao.lease/proposal :yin.k/occurrence]
   :yin.k/resumed [:yin.k/report :yin.k/bytes]
   :yin.k/release [:dao.lease/lease]
   :yin.k/renewal [:dao.lease/lease]
   :yin.k/input [:yin.k/input]
   :yin.k/admit [:yin.k/target :yin.k/fenced-envelope]})


(def requests
  "The closed request set."
  (set (keys required)))


(defn front
  "The state of one holder's front.  `config` holds
     :authority    the authority value
     :inbound      the holder's inbound stream (a reader)
     :reply        the holder's reply stream (a writer)
     :diagnostics  the composition's diagnostic stream (a writer)
     :resolver     (fn [inbound-identity value] -> author or nil)
     :store        the composition's content store: offers put bodies
                   in it (grant/offer!) and admission reads accepted
                   checkpoints from it (admission/admit!)
     :lease-media  (fn [author] -> writer or nil), the author's lease-fact
                   stream, which the judge drains as that author's
     :cursor       optional, the inbound cursor to resume from; the
                   inbound's oldest by default"
  [{:keys [authority inbound reply diagnostics resolver store lease-media
           cursor]}]
  {::authority authority
   ::inbound inbound
   ::inbound-identity (:dao.stream/identity (stream/descriptor inbound))
   ::reply reply
   ::diagnostics diagnostics
   ::resolver resolver
   ::store store
   ::lease-media lease-media
   ::cursor (or cursor
                (:dao.stream/cursor
                  (stream/cursor inbound :dao.stream/oldest)))
   ::handled []
   ::halted nil})


;; =============================================================================
;; Defective requests
;; =============================================================================

(defn- well-formed?
  [r]
  (and (map? r)
       (contains? requests (get r :yin.k/request))
       (some? (get r :yin.k/request-id))
       (every? #(some? (get r %)) (get required (get r :yin.k/request)))
       (or (not= :yin.k/proposal (get r :yin.k/request))
           (custody/occurrence? (get r :yin.k/occurrence)))))


(defn- append-data!
  "Append `v` to writer `w`, answering the stream's own result, or
   `{::threw true}` when the append throws."
  [w v]
  (try
    (stream/append! w v)
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {::threw true})))


(defn- diagnose!
  [f author r d]
  (let [m (cond-> {:yin.k/diagnostic :yin.k/defective-request
                   :yin.k/defect d
                   :yin.k/inbound (::inbound-identity f)}
            (some? author) (assoc :yin.k/author author)
            :always (assoc :yin.k/claimed
                           (if (map? r)
                             (select-keys r [:yin.k/request
                                             :yin.k/request-id])
                             {})))]
    {::diagnostic m ::appended (append-data! (::diagnostics f) m)}))


(defn- resolve-author
  [f r]
  (try
    ((::resolver f) (::inbound-identity f) r)
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      nil)))


;; =============================================================================
;; Answers
;; =============================================================================

(defn- carry!
  "Append lease fact `fact` to `author`'s lease-fact stream."
  [f author fact]
  (let [w (try
            (when-let [media (::lease-media f)] (media author))
            (catch #?(:cljd Object :clj Throwable :cljs :default) _
              ::threw))]
    (cond
      (nil? (authority/projection (::authority f)))
      {:yin.k/status :suspended :yin.k/reason :unavailable}
      (nil? w) {:yin.k/status :refused :yin.k/reason :no-lease-medium}
      (= ::threw w) {:yin.k/status :suspended :yin.k/reason :uncarried}
      :else
      (let [r (append-data! w fact)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          {:yin.k/status :carried}
          {:yin.k/status :suspended :yin.k/reason :uncarried})))))


(defn- answer
  "The landed function's answer to well-formed request `r` from
   `author`; an admission that produced no outcome is `{::silent x}`."
  [f author r]
  (let [a (::authority f)]
    (case (get r :yin.k/request)
      :yin.k/offer
      (grant/offer! a (::store f) (get r :yin.k/id) (get r :yin.k/bytes)
                    (get r :yin.k/medium))
      :yin.k/proposal
      (carry! f author (lease/proposal (get r :dao.lease/proposal)
                                       (custody/subject
                                         (get r :yin.k/occurrence))))
      :yin.k/resumed
      (completion/report! a author (get r :yin.k/report)
                          (get r :yin.k/bytes))
      :yin.k/release
      (carry! f author (lease/release (get r :dao.lease/lease)))
      :yin.k/renewal
      (carry! f author (lease/renewal (get r :dao.lease/lease)))
      :yin.k/input
      (input/record-input! a author (get r :yin.k/input))
      :yin.k/admit
      (let [x (admission/admit! a (::store f) (get r :yin.k/target) author
                                (get r :yin.k/fenced-envelope)
                                (::diagnostics f))]
        (if (contains? x :yin.k/admission) x {::silent x})))))


(defn- handle!
  "Answer one delivered request value `r`: the record of what was done."
  [f r]
  (let [author (resolve-author f r)]
    (cond
      (not (well-formed? r)) (diagnose! f author r :malformed)
      (nil? author) (diagnose! f nil r :wrong-author)
      :else
      (let [x (try
                (answer f author r)
                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                  ::threw))]
        (cond
          (= ::threw x) (diagnose! f author r :malformed)
          (contains? x ::silent) {::answer (::silent x)}
          :else
          (let [reply {:yin.k/reply (get r :yin.k/request)
                       :yin.k/request-id (get r :yin.k/request-id)
                       :yin.k/answer x}]
            {::answer x ::replied (append-data! (::reply f) reply)}))))))


;; =============================================================================
;; The step
;; =============================================================================

(defn step
  "Advance front `f` by at most `n` reads of its inbound stream from the
   held cursor, answering each request read.  Answers the next state:
   `::cursor` past the last read, `::handled` the records of this step
   only (each a diagnostic, an answer with the reply append's result, or
   `{::gap recovery-cursor}` for requests the inbound lost, counted as
   one read), and `::halted` the inbound outcome that ended the step
   early (blocked at the tail, end, or a cursor or stream defect), nil
   when n reads were made."
  [f n]
  (loop [f (assoc f ::handled [] ::halted nil)
         k 0]
    (if-not (< k n)
      f
      (let [r (try
                (stream/next (::inbound f) (::cursor f))
                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                  {::threw true}))
            o (:dao.stream/outcome r)
            c (:dao.stream/cursor r)]
        (cond
          (= :dao.stream/ok o)
          (recur (-> f
                     (update ::handled conj
                             (handle! f (:dao.stream/value r)))
                     (assoc ::cursor c))
                 (inc k))
          (and (= :dao.stream/gap o) (some? c) (not= c (::cursor f)))
          (recur (-> f
                     (update ::handled conj {::gap c})
                     (assoc ::cursor c))
                 (inc k))
          :else (assoc f ::halted (or o r)))))))


;; =============================================================================
;; Attribution out
;; =============================================================================

(defn- outcome?
  [x]
  (and (map? x)
       (or (contains? admission/admissions (get x :yin.k/admission))
           (and (contains? requests (get x :yin.k/reply))
                (map? (get x :yin.k/answer))))))


(defn reply-evidence
  "Whether `record`, read from a stream the composition attributes to
   `author`, is an outcome authored by the authority whose identity is
   `arbitration`: a reply of this front, or a `:yin.k/admission` outcome
   of the outcome projection.  Answers the record when `author` equals
   `arbitration` and the record is an outcome, else `{::no-evidence
   reason}`, reason :other-author or :not-an-outcome.  Identity
   equality only: a descriptor is never compared, and a record from any
   other author discharges nothing, whatever it claims."
  [arbitration author record]
  (cond
    (not (and (some? author) (= arbitration author)))
    {::no-evidence :other-author}
    (not (outcome? record)) {::no-evidence :not-an-outcome}
    :else record))
