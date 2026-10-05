(ns yin.vm.linker.head
  "The published head trace and its rule (docs/design/
   yin.vm.linker.dht.head.md, slice H0): a publisher's index HEAD as a
   signed trace, and the pure judgement a reader makes of one.

   A trace is exactly the closed shape of 5.2:

     {:yin.head/envelope {:yin.head/principal \"ed25519:<64 hex>\"
                          :yin.head/manifest  :segment/...
                          :yin.head/seq       n}
      :yin.head/proof    {:yin.head/signature \"<128 hex>\"}}

   signed over `yin.head/trace:v1\\n` and the envelope's canonical bytes
   (`yin.vm.linker.sign/sign-trace`).  The sequence is derived, never
   stored: the greatest transaction `t` among the datoms of the index the
   manifest names (`seq-of`).

   Return shapes this namespace chose where the design leaves them open:
   - `judge` answers one keyword, the outcome of the 5.5 table:
     `:yin.head/malformed`, `:yin.head/wrong-principal`,
     `:yin.head/bad-proof`, `:yin.head/stale`, `:yin.head/equivocation`,
     `:duplicate` or `:candidate`.  Its `principal` is the followed
     principal id, `\"ed25519:\"` and 64 lowercase hex; `floor` is a
     sequence or nil; `installed` is the installed head's manifest
     address, or nil.
   - A sequence is a nonnegative integer no greater than 2^53 - 1, the
     greatest integer every host holds exactly (5.10: the sequence stays
     within 2^53); beyond it a trace is `:yin.head/malformed`.
   - `trace` throws `ex-info` when the key or the trace it would build is
     not well formed; it never answers a malformed trace.

   Slice H1 adds the board and the follower (5.1, 5.3 to 5.7, section 6),
   over any `dao.stream` reader handle: the follower calls only `cursor`
   and `next` on it.  Return shapes chosen where the design leaves them
   open:
   - `deposit!` answers `{:status :ok :trace t}` (a trace equal to the
     board's current one is not appended again) or `{:status :refused
     :reason r ...}`, `r` one of `:yin.head/seq-collision`,
     `:yin.head/seq-regression`, `:yin.head/empty`, `:yin.head/no-key`.
   - `follow` answers `[follower node]`: the node records the installed
     head of each persisted record (`yin.vm.linker.dht/installed-heads`).
     A record that fails, or more than 64 principals, throws `ex-info`
     with `:yin.head/refused` naming the reason and the principal.
   - `step` and `install` answer `[follower node events]`.  An event is
     `{:yin.head/event e :principal p ...}`: `:confirmed` and
     `:installed` with `:trace`; `:refused` with `:reason` and `:trace`
     (`install` of a trace that is not the confirmed one is refused
     `:yin.head/not-confirmed`, follower and node unchanged);
     `:unloadable` with `:trace` and `:failure`; `:source-lost` with
     `:outcome`.
   - `moved` answers `[{:name n :linked a :resolved b} ...]` by name."
  (:require [clojure.string :as str]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.dht :as dht]
            [dao.space.index :as index]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.sign :as sign]))


(def ^:private max-seq
  "2^53 - 1: the greatest integer exact on every host."
  9007199254740991)


(def ^:private hex-digits "0123456789abcdef")


(defn- hex?
  [n s]
  (and (string? s)
       (= n (count s))
       (every? #(some? (str/index-of hex-digits (str %))) s)))


(defn- valid-seq?
  [n]
  (and (integer? n) (<= 0 n max-seq)))


(defn- public-of-principal
  "The 64 hex digits of an `ed25519:` principal id, or nil."
  [principal]
  (when (and (string? principal) (str/starts-with? principal "ed25519:"))
    (let [public (subs principal (count "ed25519:"))]
      (when (hex? 64 public) public))))


(defn- well-formed?
  "The closed shape of 5.2, with a sequence that is a nonnegative
   integer."
  [trace]
  ;; Each value is checked `map?` with its closed key set before anything
  ;; is read from it: a trace comes off a channel and may decode to any
  ;; shape.
  (try
    (boolean
      (and (map? trace)
           (= #{:yin.head/envelope :yin.head/proof} (set (keys trace)))
           (let [envelope (get trace :yin.head/envelope)
                 proof (get trace :yin.head/proof)]
             (and (map? envelope)
                  (= #{:yin.head/principal :yin.head/manifest :yin.head/seq}
                     (set (keys envelope)))
                  (map? proof)
                  (= #{:yin.head/signature} (set (keys proof)))
                  (public-of-principal (get envelope :yin.head/principal))
                  (jing/segment-address? (get envelope :yin.head/manifest))
                  (valid-seq? (get envelope :yin.head/seq))
                  (hex? 128 (get proof :yin.head/signature))))))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn trace
  "Build and sign the trace of `manifest` at sequence `n` under `key`,
   `{:seed hex :public hex}`.  The principal is the key's own."
  [key manifest n]
  (let [principal (sign/principal (sign/public-of (:seed key)))
        envelope {:yin.head/principal principal
                  :yin.head/manifest manifest
                  :yin.head/seq n}
        built {:yin.head/envelope envelope
               :yin.head/proof (sign/sign-trace (:seed key) envelope)}]
    (when-not (well-formed? built)
      (throw (ex-info "Malformed head trace"
                      {:reason :yin.head/malformed :envelope envelope})))
    built))


(defn- proven?
  [trace]
  (sign/verify-trace (public-of-principal
                       (get-in trace [:yin.head/envelope :yin.head/principal]))
                     (jing/canonical-bytes (:yin.head/envelope trace))
                     (get-in trace [:yin.head/proof :yin.head/signature])))


(defn verify
  "True when `trace` has the closed shape of 5.2 and its signature is its
   principal's head proof over its envelope; false otherwise.  Never
   throws."
  [trace]
  (try
    (boolean (and (well-formed? trace) (proven? trace)))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn seq-of
  "The greatest transaction `t` among `datoms`; nil for no datoms."
  [datoms]
  (when (seq datoms)
    (reduce max (map index/datom-t datoms))))


(defn judge
  "The rule of 5.5 for one observed trace: a pure function of the
   followed `principal`, the `floor` (the installed head's sequence, or
   nil), the `installed` manifest and the `trace`.  It takes no source
   and no candidate: its answer is the same whoever carried the trace,
   in whatever order, and whatever is loading."
  [principal floor installed trace]
  ;; The shape is checked before any field is read: `judge` never throws,
  ;; whatever a channel decoded the trace to.
  (if-not (well-formed? trace)
    :yin.head/malformed
    (let [{:yin.head/keys [manifest] n :yin.head/seq}
          (:yin.head/envelope trace)]
      (cond
        (not= principal (:yin.head/principal (:yin.head/envelope trace)))
        :yin.head/wrong-principal
        (not (verify trace)) :yin.head/bad-proof
        (nil? floor) :candidate
        (< n floor) :yin.head/stale
        (and (= n floor) (= manifest installed)) :duplicate
        (= n floor) :yin.head/equivocation
        :else :candidate))))


;; =============================================================================
;; The board and the deposit (5.1, 5.2, 5.4)
;; =============================================================================

(defn board
  "A head board: a `dao.stream.ringbuffer` of capacity 1, the latest head
   winning.  Answers the ring's handle, which a reader may be handed
   directly."
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key 1})))


(defn- current
  "The trace on `board` now, or nil."
  [board]
  (let [c (stream/cursor board :dao.stream/oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome c))
      (let [r (stream/next board (:dao.stream/cursor c))]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (:dao.stream/value r))))))


(defn- refusal
  [reason data]
  (merge {:status :refused :reason reason} data))


(defn deposit!
  "Sign the trace of `manifest` under `key` at the sequence `datoms`
   derive (`seq-of`), and append it to `board`.  The board's current
   trace is the last this process signed: a sequence equal to its with
   another manifest is refused `:yin.head/seq-collision`, a lower one
   `:yin.head/seq-regression`.  No datoms is `:yin.head/empty`, no key
   `:yin.head/no-key`.  A refusal appends nothing."
  [board key manifest datoms]
  (let [n (seq-of datoms)
        prior (current board)
        {last-n :yin.head/seq last-m :yin.head/manifest}
        (:yin.head/envelope prior)]
    (cond
      (not (and (map? key) (:seed key)))
      (refusal :yin.head/no-key {:manifest manifest})

      (nil? n) (refusal :yin.head/empty {:manifest manifest})

      (and prior (= n last-n) (= manifest last-m)) {:status :ok :trace prior}

      (and prior (= n last-n))
      (refusal :yin.head/seq-collision {:manifest manifest :seq n
                                        :signed last-m})

      (and prior (< n last-n))
      (refusal :yin.head/seq-regression {:manifest manifest :seq n
                                         :signed-seq last-n})

      :else
      (let [t (trace key manifest n)
            appended (stream/append! board t)]
        (if (= :dao.stream/ok (:dao.stream/outcome appended))
          {:status :ok :trace t}
          (refusal :yin.head/unappended {:manifest manifest :seq n
                                         :outcome (:dao.stream/outcome
                                                    appended)}))))))


;; =============================================================================
;; The follower (5.3, 5.5, 5.7)
;; =============================================================================

(def candidate-kind
  "The `dao.space.dht` load kind of a candidate head: never an index
   load, so `loaded-indexes` never lists it."
  :yin.head/candidate)


(def defaults
  "The follower's defaults (section 7)."
  {:poll-ticks 5000
   :max-follow 64
   :read-budget 64})


(defn- startup-refused
  [message reason data]
  (ex-info (str "yin.vm.linker.head/follow: " message)
           (assoc data :yin.head/refused reason)))


(defn- manifest-of
  [trace]
  (get-in trace [:yin.head/envelope :yin.head/manifest]))


(defn- seq-of-trace
  [trace]
  (get-in trace [:yin.head/envelope :yin.head/seq]))


(defn- local-datoms
  "The datoms of the index `manifest` names, walked in the node's local
   store: `{:datoms d}` or `{:failure f}`."
  [node manifest]
  (let [w ((dht/index-walk manifest) (dht/local node))]
    (if (= :complete (::dht/walk w))
      {:datoms (:value w)}
      {:failure (dissoc w ::dht/walk)})))


(defn- index-seq
  "The sequence of an index's `datoms`: `{:seq n}` (`n` nil for no
   datoms), or `{:invalid true}` when any row is not a persisted local
   datom (`dao.datom/local-datom?`: exactly `[e a v t m]`, `e` and `t`
   nonnegative integers, `a` a namespaced keyword, `m` an integer).
   Total over whatever a store yields: the covered-index walk checks
   counts, not rows."
  [datoms]
  (try
    (if (and (sequential? datoms) (every? datom/local-datom? datoms))
      {:seq (seq-of datoms)}
      {:invalid true})
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:invalid true})))


(defn- checked-record
  "The persisted record of `principal`, verified again, against the
   followed set, and walked in the local store; throws naming it."
  [node followed principal trace]
  (let [fail (fn [why reason]
               (throw (startup-refused (str "the head of " principal " " why)
                                       reason {:principal principal})))]
    (when-not (contains? followed principal)
      (fail "is recorded but not followed" :yin.head/unfollowed))
    (when-not (verify trace)
      (fail "does not verify" :yin.head/bad-record))
    (when-not (= principal (get-in trace [:yin.head/envelope
                                          :yin.head/principal]))
      (fail "is another principal's trace" :yin.head/wrong-principal))
    (let [{:keys [datoms failure]} (local-datoms node (manifest-of trace))
          {:keys [invalid] n :seq} (when-not failure (index-seq datoms))]
      (when failure
        (fail "is not in the local store" :yin.head/unloadable))
      (when invalid
        (fail "names a malformed index" :yin.head/index-invalid))
      (when-not (= (seq-of-trace trace) n)
        (fail "does not match its index's sequence" :yin.head/seq-mismatch)))
    trace))


(defn- new-principal
  [installed]
  {:reader nil
   :cursor nil
   :due nil
   :answered nil
   :installed installed
   :candidate nil
   :rejected nil
   :refusal nil
   :observed nil})


(defn follow
  "A follower on `node` from `opts`: `:follow` the followed principal ids
   (`\"ed25519:\"` and 64 lowercase hex, at most 64), `:heads` the
   persisted records (`records`' value, `{:version 1 :heads {principal
   trace}}`) or `:volatile`.  Optional `:poll-ticks` (5000), and
   `:repair-ticks` and `:repair-max-ticks` (the node's bounds by
   default).  Each record is verified again, checked against the
   followed set and its index walked in the local store; a record that
   fails refuses startup naming it.  Answers `[follower node]`, the node
   holding each record's head as installed."
  [node {:keys [follow heads] :as opts}]
  (let [followed (set follow)
        limit #(or (get opts %) (get-in node [:limits %]) (get dht/defaults %))]
    (when-not (and (coll? follow) (every? public-of-principal follow))
      (throw (startup-refused "followed principals must be ed25519 ids"
                              :yin.head/malformed {:follow follow})))
    (when (> (count followed) (:max-follow defaults))
      (throw (startup-refused (str "at most " (:max-follow defaults)
                                   " principals may be followed")
                              :yin.head/too-many {:count (count followed)})))
    (when-not (or (= :volatile heads)
                  (and (map? heads) (= 1 (:version heads))
                       (map? (:heads heads))))
      (throw (startup-refused "heads must be :volatile or a version 1 record"
                              :yin.head/malformed {})))
    (let [recorded (if (= :volatile heads) {} (:heads heads))
          installed (into {}
                          (map (fn [[p t]]
                                 [p (checked-record node followed p t)]))
                          recorded)]
      [{:principals (into (sorted-map)
                          (map (fn [p] [p (new-principal (get installed p))]))
                          followed)
        :owners {}
        :volatile? (= :volatile heads)
        :poll-ticks (or (:poll-ticks opts) (:poll-ticks defaults))
        :repair-ticks (limit :repair-ticks)
        :repair-max-ticks (limit :repair-max-ticks)}
       (assoc node ld/installed-key
              (into {} (map (fn [[p t]] [p (manifest-of t)])) installed))])))


(defn- followed!
  [follower principal]
  (when-not (contains? (:principals follower) principal)
    (throw (ex-info (str principal " is not followed")
                    {:principal principal}))))


(defn attach
  "Give the follower a `dao.stream` reader handle for `principal`'s board:
   any handle.  Its next step mints `:dao.stream/oldest` on it."
  [follower principal reader]
  (followed! follower principal)
  (update-in follower [:principals principal]
             assoc :reader reader :cursor nil :due nil))


;; -----------------------------------------------------------------------------
;; Candidate records and their owners
;; -----------------------------------------------------------------------------

(defn- acquire
  "`principal` takes the candidate record at `m`: it joins the owners, and
   the load starts when no record exists.  A record of another kind is
   read, never owned."
  [follower node principal m]
  (let [status (dht/load-status node m)]
    (cond
      (nil? status)
      [(update-in follower [:owners m] (fnil conj #{}) principal)
       (dht/load node m {:kind candidate-kind :walk (dht/index-walk m)})]

      (= candidate-kind (:kind status))
      [(update-in follower [:owners m] (fnil conj #{}) principal) node]

      :else [follower node])))


(defn- release
  "`principal` releases `m`; the last release removes the record:
   abandoned while loading, forgotten once terminal."
  [follower node principal m]
  (let [owners (get-in follower [:owners m])]
    (cond
      (not (contains? owners principal)) [follower node]

      (< 1 (count owners))
      [(update-in follower [:owners m] disj principal) node]

      :else
      (let [status (dht/load-status node m)
            follower (update follower :owners dissoc m)]
        [follower
         (cond
           (not= candidate-kind (:kind status)) node
           (= :loading (:status status)) (dht/abandon node m)
           :else (dht/forget node m))]))))


;; -----------------------------------------------------------------------------
;; Observing one trace (5.5)
;; -----------------------------------------------------------------------------

(defn- refused-event
  [principal reason trace]
  {:yin.head/event :refused :principal principal :reason reason :trace trace})


(defn- envelope-id
  [trace]
  (jing/segment-key (:yin.head/envelope trace)))


(defn- observe
  "Judge one observed `trace` of `principal`: `[follower node events]`."
  [follower node principal trace]
  (let [st (get-in follower [:principals principal])
        installed (:installed st)
        verdict (judge principal (seq-of-trace installed)
                       (manifest-of installed) trace)
        follower (assoc-in follower [:principals principal :observed]
                           {:verdict verdict :trace trace})
        cand (:candidate st)]
    (case verdict
      :duplicate [follower node []]

      :candidate
      (if (or (and cand (= trace (:trace cand)))
              (= (:rejected st) (envelope-id trace)))
        [follower node []]
        (let [[follower node] (if cand
                                (release follower node principal
                                         (:manifest cand))
                                [follower node])
              m (manifest-of trace)
              [follower node] (acquire follower node principal m)]
          [(assoc-in follower [:principals principal :candidate]
                     {:trace trace
                      :manifest m
                      :seq (seq-of-trace trace)
                      :state :loading
                      :delay (:repair-ticks follower)
                      :due nil
                      :failure nil})
           node
           []]))

      [(assoc-in follower [:principals principal :refusal]
                 {:reason verdict :trace trace})
       node
       [(refused-event principal verdict trace)]])))


;; -----------------------------------------------------------------------------
;; Polling the source (5.3)
;; -----------------------------------------------------------------------------

(defn- call
  "One stream operation on a handle that may be anything: a throw, or an
   answer that is not a map, is a transport error that is not
   retryable."
  [f]
  (try (let [r (f)]
         (if (map? r) r {:dao.stream/outcome :dao.stream/transport-error}))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         {:dao.stream/outcome :dao.stream/transport-error})))


(defn- retry?
  [r]
  (and (= :dao.stream/transport-error (:dao.stream/outcome r))
       (true? (:dao.stream/retry? r))))


(defn- read-source
  "Read a source once from `cursor` (nil mints `:dao.stream/oldest`), at
   most `budget` operations: `{:values [...] :cursor c :answered? b}`,
   with `:lost outcome` for a lost source and `:more? true` when the
   budget ran out."
  [reader cursor budget]
  (loop [cursor cursor
         values []
         answered? false
         left budget]
    (cond
      (zero? left)
      {:values values :cursor cursor :answered? answered? :more? true}

      (nil? cursor)
      (let [r (call #(stream/cursor reader :dao.stream/oldest))]
        (cond
          (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (:dao.stream/cursor r) values true (dec left))

          (retry? r) {:values values :cursor nil :answered? answered?}
          :else {:values values :lost (:dao.stream/outcome r)
                 :answered? answered?}))

      :else
      (let [r (call #(stream/next reader cursor))]
        (case (:dao.stream/outcome r)
          :dao.stream/ok (recur (:dao.stream/cursor r)
                                (conj values (:dao.stream/value r))
                                true
                                (dec left))
          :dao.stream/gap (recur (:dao.stream/cursor r) values true (dec left))
          :dao.stream/blocked {:values values :cursor cursor :answered? true}
          (if (retry? r)
            {:values values :cursor cursor :answered? answered?}
            {:values values :lost (:dao.stream/outcome r)
             :answered? answered?}))))))


(defn- poll
  "Poll `principal`'s source when it is due, judging every value it
   yields in order: `[follower node events]`.  A lost source is dropped
   with its cursor and reported once."
  [follower node principal now]
  (let [{:keys [reader cursor due]} (get-in follower [:principals principal])]
    (if (or (nil? reader) (and due (< now due)))
      [follower node []]
      (let [{:keys [values lost answered? more?] :as read}
            (read-source reader cursor (:read-budget defaults))
            follower (update-in follower [:principals principal]
                                (fn [st]
                                  (cond-> (assoc st
                                                 :cursor (:cursor read)
                                                 :due (if more?
                                                        now
                                                        (+ now (:poll-ticks
                                                                 follower))))
                                    answered? (assoc :answered now)
                                    lost (assoc :reader nil :cursor nil
                                                :due nil))))
            [follower node events]
            (reduce (fn [[f n es] v]
                      (let [[f n more] (observe f n principal v)]
                        [f n (into es more)]))
                    [follower node []]
                    values)]
        [follower node
         (cond-> events
           lost (conj {:yin.head/event :source-lost :principal principal
                       :outcome lost}))]))))


;; -----------------------------------------------------------------------------
;; The candidate's load: confirmed, rejected or unloadable (5.5)
;; -----------------------------------------------------------------------------

(defn- foreign?
  "Whether a record of `kind` is neither a candidate nor an index load:
   the follower never reads, forgets or restarts it."
  [kind]
  (not (contains? #{candidate-kind dht/index-kind} kind)))


(defn- walk-of
  "The walk a failed record of `kind` at `m` is started again with: only
   a candidate or an index load is.  nil for any other kind, whose failed
   record stays its owner's until it is forgotten."
  [kind m]
  (when (contains? #{candidate-kind dht/index-kind} kind)
    (dht/index-walk m)))


(defn- reject
  "Drop `principal`'s candidate as `reason`, releasing its record and
   remembering its envelope as the last rejected: `[follower node
   events]`."
  [follower node principal reason data]
  (let [{:keys [trace manifest]} (get-in follower [:principals principal
                                                   :candidate])
        [follower node] (release follower node principal manifest)]
    [(update-in follower [:principals principal] assoc
                :candidate nil
                :rejected (envelope-id trace)
                :refusal (merge {:reason reason :trace trace} data))
     node
     [(merge (refused-event principal reason trace) data)]]))


(defn- unloadable
  [follower principal cand now failure]
  [(assoc-in follower [:principals principal :candidate]
             (assoc cand :state :failed :failure failure
                    :due (+ now (:delay cand))))
   [{:yin.head/event :unloadable :principal principal :trace (:trace cand)
     :failure failure}]])


(defn- settle
  "Advance `principal`'s candidate against its record: `[follower node
   events]`."
  [follower node principal now]
  (let [{:keys [candidate]} (get-in follower [:principals principal])
        m (:manifest candidate)
        status (when candidate (dht/load-status node m))]
    (cond
      (or (nil? candidate) (= :confirmed (:state candidate)))
      [follower node []]

      ;; the record was removed under the follower: start it again
      (nil? status)
      (let [[follower node] (acquire follower node principal m)]
        [(assoc-in follower [:principals principal :candidate :state] :loading)
         node []])

      ;; another kind's record, whatever its status: reported once, left
      ;; untouched and unread; the address is acquired once it is removed
      (foreign? (:kind status))
      (if (= :failed (:state candidate))
        [follower node []]
        (let [[follower events]
              (unloadable follower principal candidate now
                          (if (= :failed (:status status))
                            (:reason status)
                            {:yin.head/foreign-kind (:kind status)
                             :status (:status status)}))]
          [follower node events]))

      (= :loading (:status status))
      [(assoc-in follower [:principals principal :candidate :state] :loading)
       node []]

      (= :loaded (:status status))
      (let [{:keys [invalid] n :seq} (index-seq (:value status))]
        (cond
          invalid (reject follower node principal :yin.head/index-invalid {})

          (= (:seq candidate) n)
          [(assoc-in follower [:principals principal :candidate :state]
                     :confirmed)
           node
           [{:yin.head/event :confirmed :principal principal
             :trace (:trace candidate)}]]

          :else
          (reject follower node principal :yin.head/seq-mismatch
                  {:index-seq n})))

      ;; the load failed: report it once, then start it again after the
      ;; delay, under the kind the record had
      (not= :failed (:state candidate))
      (let [[follower events] (unloadable follower principal candidate now
                                          (:reason status))]
        [follower node events])

      (< now (:due candidate)) [follower node []]

      :else
      (if-some [walk (walk-of (:kind status) m)]
        [(update-in follower [:principals principal :candidate] assoc
                    :state :loading
                    :due nil
                    :delay (min (* 2 (:delay candidate))
                                (:repair-max-ticks follower)))
         (-> (dht/forget node m)
             (dht/load m {:kind (:kind status) :walk walk}))
         []]
        [follower node []]))))


(defn step
  "One pass after `dao.space.dht/step` at the owner's reading `now`: poll
   each followed source that is due, judge what it shows, keep one
   candidate per principal, and advance each candidate's load.  It
   confirms; it never installs.  Answers `[follower node events]`."
  [follower node now]
  (reduce (fn [[f n es] p]
            (let [[f n polled] (poll f n p now)
                  [f n settled] (settle f n p now)]
              [f n (-> es (into polled) (into settled))]))
          [follower node []]
          (keys (:principals follower))))


(defn install
  "Install `principal`'s confirmed head `trace` (5.7 step 3): it enters
   the snapshot set, the floor moves to its sequence, its candidate
   record is released, and `:installed` is emitted.  Any other trace is
   refused `:yin.head/not-confirmed`, follower and node unchanged.
   Answers `[follower node events]`."
  [follower node principal trace]
  (followed! follower principal)
  (let [cand (get-in follower [:principals principal :candidate])]
    (if-not (and cand (= :confirmed (:state cand)) (= trace (:trace cand)))
      [follower node [(refused-event principal :yin.head/not-confirmed trace)]]
      (let [[follower node] (release follower node principal (:manifest cand))]
        [(update-in follower [:principals principal] assoc
                    :installed trace :candidate nil)
         (assoc-in node [ld/installed-key principal] (:manifest cand))
         [{:yin.head/event :installed :principal principal :trace trace}]]))))


;; =============================================================================
;; Status, records and moved names (5.6, 5.7)
;; =============================================================================

(defn heads
  "Per followed principal: what is installed (`:installed` the trace,
   `:floor`, `:manifest`), the candidate and its state, the last
   refusal, the last observation (`:observed`, with its verdict), and
   whether a source is attached and the reading it last answered at."
  [follower]
  (into (sorted-map)
        (map (fn [[p {:keys [installed candidate] :as st}]]
               [p {:installed installed
                   :floor (seq-of-trace installed)
                   :manifest (manifest-of installed)
                   :candidate (when candidate
                                (select-keys candidate [:trace :manifest :seq
                                                        :state :failure]))
                   :refusal (:refusal st)
                   :observed (:observed st)
                   :source? (some? (:reader st))
                   :answered (:answered st)}]))
        (:principals follower)))


(defn records
  "The value `heads.edn` holds once `trace` is written in `principal`'s
   place: `{:version 1 :heads {principal trace}}`, every other installed
   head kept."
  [follower principal trace]
  {:version 1
   :heads (assoc (into {}
                       (keep (fn [[p st]]
                               (when (:installed st) [p (:installed st)])))
                       (:principals follower))
                 principal trace)})


(defn moved
  "The linked names whose resolved address differs: `names` is
   `yin.vm.linker.dht/names`' environment, `registry` `{name address}`
   of what a session linked.  A name that no longer resolves has not
   moved."
  [names registry]
  (vec (sort-by (comp str :name)
                (keep (fn [[n linked]]
                        (let [entry (get-in names [:names n])]
                          (when (and (= :ok (:status entry))
                                     (not= linked (:address entry)))
                            {:name n :linked linked
                             :resolved (:address entry)})))
                      registry))))
