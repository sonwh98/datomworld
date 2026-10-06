(ns yin.vm.ucf.holder.writer
  "The fenced writer of M-next D slice D11 (UCF 7.7.5, 7.7.8; r3 1.3 and
   1.11; linker-dht 14.2.2, 14.2.3 steps 3 to 5): the holder side of
   every write a task under custody performs.

   A write is a retained `:put` wait entry: a program append, or the
   request envelope of a retained FFI call.  `emit` is the whole write
   path over the machine value.  It assigns first, as one step before
   any request is sent: every write whose target's protection class is
   `:enrolled` and which carries no id takes `n` from the custody map's
   `:yin.k/next-op-seq` -- the root's one counter, which install
   children draw from -- stores `{:yin.k/occurrence P :yin.k/seq n}` as
   the entry's `:op-id` (the key D9's helper set by hand; the lift
   copies it to the pending's `:yin.k/op-id`), and sets the counter to
   `n+1`.  At 2^52-1 nothing is assigned and nothing is sent: the write
   stays an undischarged wait.  Then it sends: an `:yin.k/admit` request
   carrying the five-key fenced envelope, appended to the holder's
   inbound stream by the composition-supplied appender.  The request id
   is the tagged vector `[:yin.k/admit lease op-id]`, correlation only:
   the front dispatches a resent request again, and the authority's
   op-id namespace is the dedup.  A regrant changes the lease, so the
   request id changes and the op id does not.

   The three protection classes govern every write: `:enrolled` never
   goes bare, `:at-least-once` is appended by the writer with no id
   through the machine's own resource handle and its outcome applied at
   once, and a `:fail-stop` write ends the run before it is performed --
   the machine is gated `:ended` and nothing further is emitted.

   The id is retained while the write is unfinished: a `full` inbound
   stream, an append of unknown effect, an acceptance with no admission
   outcome yet, and a `:suspended` outcome all leave the id on the
   entry, and the next emit resends through the fenced boundary, which
   commits once or replays the result already held.  `discharge` is the
   pure decision over one outcome record: a reply counts only when its
   author is the arbitration identity (`front/reply-evidence`), its kind
   is `:yin.k/admit`, its request id is the entry's current one, and its
   answer's op id and incarnation match; a projected outcome counts when
   its op id and incarnation match, with no request wrapper.  An
   authenticated `:committed` or `:replayed` applies the recorded effect
   result through the engine's public applies; an authenticated
   `:intent-conflict` or `:stale` ends the run, leaving the occurrence
   quarantined or the tenure lost -- a release after it carries, and
   clears neither.  `drain` folds discharge over the composition's
   outcome reader.

   No transport is known here: the inbound-stream appender and the
   outcome reader are composition-supplied functions, and the only
   handle the writer touches is the machine's own resource."
  (:require [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.custody :as custody]))


;; =============================================================================
;; The writes a task under custody retains
;; =============================================================================

(defn- machines
  "`[path machine]` of the root and, recursively, each install child,
   children in install-name order, so a walk draws the same ids on every
   host.  `path` is the assoc-in path of the child inside the root."
  [root]
  (letfn [(walk
            [prefix m]
            (concat [[prefix m]]
                    (mapcat (fn [name]
                              (when-some [child (:vm (get (:installs m) name))]
                                (walk (into prefix [:installs name :vm])
                                      child)))
                            (sort-by str (keys (:installs m))))))]
    (walk [] root)))


(defn- write?
  "A retained write: a parked `:put` entry, a program append or a
   retained FFI request; or a `:link-request` entry."
  [entry]
  (or (= :put (:reason entry))
      (= :link-request (:reason entry))))


(defn- call-id
  "The call id when `entry` is a retained FFI request."
  [entry]
  (ffi/request-call-id entry))


(defn- writes
  "The `[path entry]` of every retained write: the root's wait set in
   order, then each child's."
  [root]
  (into []
        (mapcat (fn [[prefix m]]
                  (keep (fn [e] (when (write? e) [prefix e]))
                        (:wait-set m))))
        (machines root)))


(defn- target
  "The portable identity of `entry`'s target stream, through the owning
   machine's own resource binding.  A `:link-request` targets the link
   request stream."
  [machine entry]
  (let [id (if (= :link-request (:reason entry))
             module/link-request-resource
             (:stream-id entry))]
    (:dao.stream/identity
      (stream/descriptor (get (:resources machine) id)))))


(defn- class-of
  "The protection class of `entry`'s target, or nil when the composition
   declared none: an undeclared stream is unsatisfied, never a default."
  [custody machine path entry]
  (get (:protection custody)
       (target (get-in machine path) entry)))


(defn- put-entry
  "`machine` with the wait entry equal to `entry` (at `path`) replaced by
  `(f entry)`.  An entry the wait set no longer holds is a torn walk, a
  defect of the caller, not a refusal."
  [machine path entry f]
  (let [ws (:wait-set (get-in machine path))
        i (first (keep-indexed (fn [n e] (when (= entry e) n)) ws))]
    (when (nil? i)
      (throw (ex-info "No such wait entry" {:yin.k/hint :torn-walk})))
    (assoc-in machine (into path [:wait-set i]) (f (get ws i)))))


;; =============================================================================
;; The envelope and the request (UCF 7.7.8)
;; =============================================================================

(defn envelope
  "The five-key fenced envelope of `entry`'s write under `custody`: the
   program value verbatim, the lease as the incarnation, the epoch of
   the grant binding, and the entry's assigned id."
  [custody entry]
  {:yin.k/envelope :yin.k/fenced-v1
   :yin.k/incarnation (:dao.lease/lease custody)
   :yin.k/epoch (:yin.k/epoch custody)
   :yin.k/op-id (:op-id entry)
   :yin.k/value (if (= :link-request (:reason entry))
                  (:envelope entry)
                  (:datom entry))})


(defn request-id
  "The tagged vector correlation handle of `entry`'s admit request: the
   request kind, the lease, the op id.  Canonical data, so it is equal
   on every host; it names no dedup -- the authority's op-id namespace
   is the dedup, and a regrant changes the lease, so the id."
  [custody entry]
  [:yin.k/admit (:dao.lease/lease custody) (:op-id entry)])


(defn admit-request
  "The `:yin.k/admit` request of `entry`'s write at target `t`, in the
   front's closed request shape."
  [custody t entry]
  {:yin.k/request :yin.k/admit
   :yin.k/request-id (request-id custody entry)
   :yin.k/target t
   :yin.k/fenced-envelope (envelope custody entry)})


;; =============================================================================
;; Assign: one step, before any request is sent
;; =============================================================================

(defn- assign
  "The one-step counter take of UCF 7.7.8 over `machine`: every enrolled
   write that carries no id takes `n`, the root custody's
   `:yin.k/next-op-seq`, stores the id on the entry and sets the counter
   to `n+1` -- the three as one step.  A write that already carries an
   id (a retry's, a lower's carried one) keeps it: no replacement is
   assigned.  At 2^52-1 the counter is exhausted: nothing is assigned,
   and the write stays an undischarged wait."
  [machine]
  (let [occurrence (get-in machine [:yin.k/custody :yin.k/occurrence])]
    (reduce (fn [m [path entry]]
              (if (or (some? (:op-id entry))
                      (not= :enrolled (class-of (:yin.k/custody m)
                                                m path entry)))
                m
                (let [n (get-in m [:yin.k/custody :yin.k/next-op-seq])]
                  (if (= custody/max-exact n)
                    m
                    (-> m
                        (put-entry path entry
                                   #(assoc % :op-id
                                           {:yin.k/occurrence occurrence
                                            :yin.k/seq n}))
                        (assoc-in [:yin.k/custody :yin.k/next-op-seq]
                                  (inc n)))))))
            machine
            (writes machine))))


;; =============================================================================
;; Emit: assign, then send -- and the class behavior of every write
;; =============================================================================

(defn- ended
  "`machine` gated `:ended` at the root and at every install child: the
   root owns the mode, and nothing applies a late result after it."
  [machine]
  (reduce (fn [m [prefix _]]
            (assoc-in m (conj prefix :yin.k/gate) :ended))
          (assoc machine :yin.k/gate :ended)
          (machines machine)))


(defn- pending-closes
  "The `[path close]` of every pending close in the root and children."
  [root]
  (into []
        (mapcat (fn [[prefix m]]
                  (keep (fn [c] [prefix c]) (:yin.k/closes m))))
        (machines root)))


(defn- close-resolvable?
  "A close is resolvable if no waiting put on the same stream has a lower
   issue number."
  [machine path close]
  (let [m (get-in machine path)]
    (not (some (fn [e]
                 (and (= :put (:reason e))
                      (= (:stream-id close) (:stream-id e))
                      (some? (:yin.k/issue e))
                      (< (:yin.k/issue e) (:yin.k/issue close))))
               (:wait-set m)))))


(defn- write-emittable?
  "A put is emittable if no pending close on the same stream has a lower
   issue number. A link request is emittable only if it has a cursor."
  [machine path entry]
  (cond
    (= :link-request (:reason entry))
    (contains? entry :cursor)

    (and (= :put (:reason entry)) (some? (:yin.k/issue entry)))
    (let [m (get-in machine path)]
      (not (some (fn [c]
                   (and (= (:stream-id c) (:stream-id entry))
                        (< (:yin.k/issue c) (:yin.k/issue entry))))
                 (:yin.k/closes m))))

    :else
    true))


(defn- apply-effect
  "The write `entry` (at `path`) woken by the append `outcome`, through
   the engine's public applies: a program put's continuation by
   `apply-put`, a retained FFI request's by `apply-ffi-outcome`, a link
   request's by `apply-link-sent`."
  [machine path entry outcome]
  (let [m (get-in machine path)
        applied (cond
                  (= :link-request (:reason entry))
                  (case (:dao.stream/outcome outcome)
                    :dao.stream/ok
                    (engine/apply-link-sent m (:link-id entry))
                    :dao.stream/full
                    m
                    ;; the ungated append-link-request's own disposition:
                    ;; a terminal outcome is a failure naming the link,
                    ;; never a retryable wait
                    (throw (ex-info "Link request could not be appended"
                                    {:outcome (:dao.stream/outcome outcome)
                                     :link-id (:link-id entry)})))
                  (some? (call-id entry))
                  (engine/apply-ffi-outcome m (call-id entry) outcome)
                  :else
                  (engine/apply-put m entry outcome))]
    (if (seq path) (assoc-in machine path applied) applied)))


(defn emit
  "One write step over `machine`, a task under custody: assign every
   eligible enrolled write as one step, then perform every write the
   machine retains, in wait order, root first and children in install
   name order. Interleaves close resolution.

   `append-request!` is the composition-supplied appender of the
   holder's inbound stream: `(fn [request] append-outcome)`.
   `append-diagnostic!` is the composition-supplied appender for
   enrolled closes: `(fn [diagnostic] ...)`."
  ([machine append-request!]
   (emit machine append-request! (fn [_] nil)))
  ([machine append-request! append-diagnostic!]
   (when-not (map? (:yin.k/custody machine))
     (throw (ex-info "Emit over a task that holds no custody"
                     {:yin.k/hint :no-custody})))
   (if (not= :running (vm/gate-mode machine))
     {:machine machine :appended [] :bare [] :run-end nil}
     (let [assigned (assign machine)
           classes (fn [m path entry]
                     (class-of (:yin.k/custody assigned) m path entry))
           close-class (fn [m path close]
                         (get (:protection (:yin.k/custody assigned))
                              (:dao.stream/identity
                                (stream/descriptor (get (:resources (get-in m path)) (:stream-id close))))))]
       (loop [m assigned
              ws (seq (writes assigned))
              appended []
              bare []]
         (let [all-closes (pending-closes m)
               c-pair (first (filter (fn [[path c]] (close-resolvable? m path c)) all-closes))]
           (if c-pair
             (let [[path close] c-pair
                   h (get (:resources (get-in m path)) (:stream-id close))
                   t (:dao.stream/identity (stream/descriptor h))
                   class (close-class m path close)]
               (cond
                 (= :fail-stop class)
                 {:machine (ended m) :appended appended :bare bare :run-end {:cause :fail-stop :dao.stream/identity t}}

                 (= :at-least-once class)
                 (let [_ (stream/close! h)
                       m-closed (if (seq path)
                                  (assoc-in m path
                                            (engine/apply-close (get-in m path)
                                                                (:stream-id close)
                                                                (:yin.k/issue close)))
                                  (engine/apply-close m (:stream-id close)
                                                      (:yin.k/issue close)))]
                   (recur m-closed ws appended bare))

                 (nil? class)
                 {:machine m :appended appended :bare bare :run-end nil
                  :unsatisfied {:yin.k/status :yin.k/unsatisfied :dao.stream/identity t}}

                 :else
                 (let [m-closed (if (seq path)
                                  (assoc-in m path
                                            (engine/apply-close (get-in m path)
                                                                (:stream-id close)
                                                                (:yin.k/issue close)))
                                  (engine/apply-close m (:stream-id close)
                                                      (:yin.k/issue close)))]
                   (when append-diagnostic!
                     (append-diagnostic! {:yin.k/status :yin.k/refused, :yin.k/hint :enrolled-close, :dao.stream/identity t}))
                   (recur m-closed ws appended bare))))
             (if-not ws
               {:machine m :appended appended :bare bare :run-end nil}
               (let [[path entry] (first ws)
                     t (target (get-in m path) entry)
                     class (classes m path entry)]
                 (if-not (write-emittable? m path entry)
                   (recur m (next ws) appended bare)
                   (cond
                     (nil? class)
                     {:machine m :appended appended :bare bare :run-end nil
                      :unsatisfied {:yin.k/status :yin.k/unsatisfied
                                    :dao.stream/identity t}}

                     (= :fail-stop class)
                     {:machine (ended m)
                      :appended appended
                      :bare bare
                      :run-end {:cause :fail-stop :dao.stream/identity t}}

                     (= :at-least-once class)
                     (let [stream-id (if (= :link-request (:reason entry)) module/link-request-resource (:stream-id entry))
                           h (get (:resources (get-in m path)) stream-id)
                           o (stream/append! h (if (= :link-request (:reason entry)) (:envelope entry) (:datom entry)))]
                       (recur (apply-effect m path entry o)
                              (next ws)
                              appended
                              (conj bare {:path path :entry entry :outcome o})))

                     (nil? (:op-id entry))
                     (recur m (next ws) appended bare)

                     :else
                     (let [r (admit-request (:yin.k/custody m) t entry)
                           a (append-request! r)]
                       (recur m (next ws)
                              (conj appended {:request r :append a})
                              bare)))))))))))))


;; =============================================================================
;; Discharge: one outcome record, matched and applied
;; =============================================================================

(defn- arbitration-id
  [custody]
  (:dao.stream/identity (:yin.k/arbitration custody)))


(defn- entry-for
  "The `[path entry]` of the unfinished write carrying `op-id`, or nil.
   An id-less write matches nothing: only an assigned id correlates."
  [machine op-id]
  (some (fn [[path entry]]
          (when (and (some? op-id) (= op-id (:op-id entry)))
            [path entry]))
        (writes machine)))


(defn- retained
  [machine reason]
  {:machine machine ::retain reason})


(defn- settle
  "The matched admission `answer` of `entry` (at `path`): an
   authenticated `:committed` or `:replayed` applies the recorded effect
   result; `:intent-conflict` ends the run with the occurrence
   quarantined; `:stale` ends it with the tenure lost.  The entry keeps
   its id through `:suspended`."
  [machine path entry answer]
  (case (get answer :yin.k/admission)
    (:committed :replayed)
    {:machine (apply-effect machine path entry
                            (get answer :yin.k/effect-result))
     :discharged {:path path
                  :op-id (:op-id entry)
                  :admission (get answer :yin.k/admission)
                  :effect-result (get answer :yin.k/effect-result)}}
    :intent-conflict
    {:machine (ended machine)
     :run-end {:cause :intent-conflict :op-id (:op-id entry)}}
    :stale
    {:machine (ended machine)
     :run-end {:cause :stale :op-id (:op-id entry)}}
    :suspended (retained machine :suspended)
    (retained machine :unknown-admission)))


(defn- discharge-reply
  "The reply arm of `discharge`: five fields must hold -- the
   authenticated author, the `:yin.k/admit` kind, the entry's current
   request id, and the answer's op id and incarnation.  One failing
   discharges nothing."
  [machine custody record]
  (let [rid (get record :yin.k/request-id)]
    (if (not= :yin.k/admit (get record :yin.k/reply))
      (retained machine :kind)
      (if-not (and (vector? rid) (= 3 (count rid))
                   (= :yin.k/admit (nth rid 0)))
        (retained machine :request-id)
        (if-some [[path entry] (entry-for machine (nth rid 2))]
          (let [answer (get record :yin.k/answer)]
            (cond
              (not= rid (request-id custody entry))
              (retained machine :request-id)

              (not= (:op-id entry) (get answer :yin.k/op-id))
              (retained machine :op-id)

              (not= (:dao.lease/lease custody)
                    (get answer :yin.k/incarnation))
              (retained machine :incarnation)

              :else (settle machine path entry answer)))
          (retained machine :no-entry))))))


(defn- discharge-projected
  "The projected-outcome arm of `discharge`: no request wrapper, so the
   op id locates the entry and the incarnation must match.  A projected
   `:committed` of another incarnation is not this holder's answer; this
   holder's retry gets `:replayed` by reply."
  [machine custody record]
  (if-some [[path entry] (entry-for machine (get record :yin.k/op-id))]
    (if-not (= (:dao.lease/lease custody)
               (get record :yin.k/incarnation))
      (retained machine :incarnation)
      (settle machine path entry record))
    (retained machine :no-entry)))


(defn discharge
  "Outcome `record`, read from a stream the composition attributed to
   `author`, against `machine`'s unfinished writes.  The record counts
   only when `front/reply-evidence` authenticates it -- the author is
   the arbitration identity, by identity equality -- and then, for a
   reply, when the five match fields hold, or, for a projected
   admission outcome, when its op id and incarnation match the entry.
   Anything else, from any other author a forged `:committed` or
   `:intent-conflict` included, discharges nothing.

   Answers `{:machine m :discharged {...} | nil :run-end {...} | nil}`
   with `::retain reason` naming why nothing was applied: the five
   fields' own reasons, `:no-entry` (no unfinished write carries the op
   id), `:suspended`, or `:not-running` (the run has ended; every apply
   refuses a late result).  A machine that holds no custody map is a
   caller defect."
  [machine author record]
  (when-not (map? (:yin.k/custody machine))
    (throw (ex-info "Discharge over a task that holds no custody"
                    {:yin.k/hint :no-custody})))
  (if (not= :running (vm/gate-mode machine))
    (retained machine :not-running)
    (let [custody (:yin.k/custody machine)
          evidence (front/reply-evidence (arbitration-id custody)
                                         author record)]
      (if (contains? evidence ::front/no-evidence)
        (retained machine (::front/no-evidence evidence))
        (if (contains? record :yin.k/reply)
          (discharge-reply machine custody record)
          (discharge-projected machine custody record))))))


;; =============================================================================
;; Drain: the composition's outcome reader
;; =============================================================================

(defn drain
  "Fold `discharge` over `read-outcome!`, the composition-supplied
   outcome reader: each call answers the next `[author record]` -- a
   reply of the front or a projected admission outcome, attributed by
   the composition's resolver -- or nil at the tail.  Answers
   `{:machine m :discharged [...] :retained [reasons] :run-end {...} |
   nil}`; a record that retains changes nothing, and draining continues
   past a run end, for the reader is read dry either way."
  [machine read-outcome!]
  (loop [m machine
         discharged []
         retained []
         run-end nil]
    (if-some [pair (read-outcome!)]
      (let [r (discharge m (first pair) (second pair))]
        (recur (:machine r)
               (if-some [d (:discharged r)] (conj discharged d) discharged)
               (if-some [x (::retain r)] (conj retained x) retained)
               (or run-end (:run-end r))))
      {:machine m
       :discharged discharged
       :retained retained
       :run-end run-end})))
