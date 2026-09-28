(ns yin.vm.ffi.remote-serve
  "The FFI export binding: the exporter side of
   `docs/design/dao.stream.remote.md` S2.3 for a machine's served
   handles, composed as one binding value with one drive owner.

   `dao.stream.remote` stays the generic transport -- its mirror
   answers from a plain table and holds nothing between steps -- and
   `yin.vm.ucf.remote` stays a lift/lower facade parameterized by a
   `serve!` callback. This namespace owns what neither does: the
   registry of served handles, the published table the mirror reads,
   the channel end and the mirror's own cursor on it, the retirement of
   served identities, and the lease each served identity is held
   under.

   Policy is injected, never decided here. `open!` takes the channel
   end and its descriptor, the per-handle surface policy, the capacity
   of live exports, the handler authority gate and the lease wiring as
   required options and refuses an incomplete assembly before anything
   is published.

   The registry is keyed by the live handle's REFERENCE identity
   (`identical?`), never by descriptor or value equality: two equal
   values are two exports. Each entry records the handle, its minted
   served identity, the channel descriptor, the declared surface, its
   lease and renewal medium, and its status. Repeated `serve!` for one
   live handle returns the one identity -- `lift-frame` asks for the
   same handle while minting a cell and again while lifting its entry.
   A served identity is never reused: a retired handle served again
   gets a new one.

   Leases (`dao.stream.remote.md` S6, `dao.lease.md`). Every served
   identity is a table entry served for a remote party -- a stream a
   migrated continuation still reads or writes -- so it is leased: this
   peer possesses it, so this binding grants and judges. Serving a
   handle grants, unsolicited, a lease whose subject is the served
   identity and whose holder is a fresh renewal medium: a ring buffer
   entered in the table with surface #{:writer} under its own identity,
   attributed to the holder by the medium (per-author media). The
   remote holder appends renewals and its release there; the judge
   reads it with a local cursor. The judge is `dao.lease/make-judge`
   over the composition's tick and fact media and writer; `step` runs
   one judge pass after its mirror pass, reading ticks the host drive
   deposits as data -- nothing here reads a clock. A reclaim is the
   same `retire!` transition: the subject and its renewal entry leave
   the table first, so later remote operations answer not-found, and
   only then does the procedure report success and the judge record
   `:lapsed`. A retirement not caused by the judge -- explicit
   `retire!`, `close!`, a refused lift -- ends the lease by the
   grantor's own policy (:policy) at the next pass. A detached channel
   retires nothing: `reattach!` rebinds the channel end, and within the
   live tenure the same identities, source cursors and leases answer.

   `retire!` is the one retirement transition: it removes the identity
   from the published table first, so a later remote operation answers
   not-found, and only then releases the entry. The binding owns its
   channel end outright: `open!` requires the composition to declare it
   exclusive (no other reflection or mirror uses it), so `close!`
   retires every entry and closes the channel writer, and a remote
   peer's unresolved appends end through the transport's own close/loss
   path as append-unknown. Nothing here resends a remote request: an
   append in flight when its identity is reclaimed is answered
   not-found, or ends as append-unknown when its channel goes first --
   never retried against a new tenure.

   The binding's state cell is private to the binding value: no
   namespace-level registry exists, and the one drive owner that holds
   the binding serializes serve!, step, retire!, reattach! and close!."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.remote :as ucf.remote]))


;; =============================================================================
;; Assembly
;; =============================================================================

(def surfaces
  "The surfaces a served entry may declare (`dao.stream.remote.md`
   S2.3): the mirror maps cursor and next to :reader, append! to
   :writer."
  #{:reader :writer})


(def lease-units
  "The lease unit table: `dao.stream.remote.md` S6 fixes `{:ms 1 :s
   1000}` for leases on served streams. Every duration, tolerance,
   cadence and tick reading the lease wiring carries is in these
   units."
  {:ms 1 :s 1000})


(def grantor
  "The author value the judge's per-author attribution returns for this
   grantor's own facts (`dao.lease` :self). No fact medium the
   composition wires may use it as its source."
  ::grantor)


(defn- channel-end?
  [end]
  (and (map? end)
       (stream/reader? (:reader end))
       (stream/descriptor? (:reader end))
       (stream/writer? (:writer end))))


(defn- capacity?
  [n]
  (and (integer? n) (pos? n)))


(defn- codec?
  [c]
  (and (map? c) (fn? (:encode c)) (fn? (:decode c))))


(defn- lease-duration?
  "A positive duration in `lease-units`, within the per-unit bound."
  [d]
  (and (lease/duration? d)
       (let [u (first (keys d))]
         (and (contains? lease-units u)
              (<= (get d u) (quot lease/magnitude-limit (get lease-units u)))))))


(defn- non-empty-vector?
  [v]
  (and (vector? v) (seq v)))


(def ^:private required
  "Each required option, the check its value must pass, and the reason
   a present value that fails it is refused with (::malformed unless
   named)."
  [[::channel channel-end?]
   [::channel-exclusive? true? ::not-exclusive]
   [::channel-descriptor stream/valid-descriptor?]
   [::surface fn?]
   [::admit? fn?]
   [::capacity capacity?]
   [::step-budget capacity?]
   [::lease-duration lease-duration?]
   [::lease-tolerance lease/tolerance?]
   [::lease-cadence lease/tolerance?]
   [::lease-ticks non-empty-vector?]
   [::lease-media non-empty-vector?]
   [::lease-writer stream/writer?]
   [::lease-renewal-capacity capacity?]])


(def ^:private optional
  "Each optional option and the check its value must pass when given."
  [[::codec codec?]
   [::lease-max lease-duration?]
   [::lease-drain-budget capacity?]])


(defn- claims-grantor?
  "True when a standing fact medium names `grantor` as its source: the
   per-author attribution would make every fact on it the grantor's own,
   able to seed tenure or record a lapse."
  [media]
  (and (non-empty-vector? media)
       (boolean (some #(and (map? %) (= grantor (:source %))) media))))


(defn- refusals
  "The required options `opts` lacks, and any option it carries
   malformed, in order; then a standing fact medium claiming the
   grantor's authorship (::grantor-source)."
  [opts]
  (into []
        (concat
          (keep (fn [[k ok? reason]]
                  (cond
                    (not (contains? opts k)) {::option k, ::reason ::missing}
                    (not (ok? (get opts k)))
                    {::option k, ::reason (or reason ::malformed)}))
                required)
          (keep (fn [[k ok?]]
                  (when (and (contains? opts k) (not (ok? (get opts k))))
                    {::option k, ::reason ::malformed}))
                optional)
          (when (claims-grantor? (::lease-media opts))
            [{::option ::lease-media, ::reason ::grantor-source}]))))


(def ^:private judge-refusal-options
  "The option `make-judge`'s named refusal (`:refused`) falls on."
  {:cadence ::lease-cadence
   :tolerance ::lease-tolerance
   :ticks ::lease-ticks
   :media ::lease-media
   :medium-entry ::lease-media
   :medium ::lease-media
   :attribution ::lease-media
   :resolver-bindings ::lease-media
   :host-values ::lease-media
   :writer ::lease-writer
   :drain-budget ::lease-drain-budget})


(defn binding?
  "True when `b` is a binding `open!` assembled."
  [b]
  (and (map? b) (contains? b ::state)))


(defn- live-by-identity
  "The live registry entry served under `identity`."
  [s identity]
  (some #(when (and (= identity (:identity %)) (= :live (:status %))) %)
        (:registry s)))


(defn- retire-in!
  "The retirement transition over the state cell; true when `identity`
   was live. Unpublish first -- the served identity and its renewal
   entry leave the table together, so no mirror step after this reaches
   either -- then release the entry and close its renewal medium."
  [state identity]
  (if-some [e (live-by-identity @state identity)]
    (do
      ;; unpublish: no mirror step after this line can reach the handle
      (swap! state
             (fn [s]
               (-> s
                   (update :table dissoc identity (:renewal e))
                   (update :registry
                           (fn [r]
                             (mapv #(if (= identity (:identity %))
                                      (assoc % :status :retiring)
                                      %)
                                   r))))))
      ;; release: the entry lets go of its handle and renewal medium
      (swap! state update :registry
             (fn [r] (filterv #(not= identity (:identity %)) r)))
      (stream/close! (:renewal-handle e))
      true)
    false))


(defn- judge-config
  "The `dao.lease/make-judge` config for `opts`, its reclaim and policy
   closed over the binding's state cell. Attribution is per-author
   media (`dao.stream.remote.md` S7): a fact's author is the source its
   medium was wired with."
  [opts state]
  (cond-> {:cadence (::lease-cadence opts)
           :units lease-units
           :tolerance (::lease-tolerance opts)
           :resolver (fn [source _fact] source)
           :resolver-bindings #{:per-author-media}
           ;; idempotent and reporting: after the call the subject is
           ;; certainly out of the table, whether this call or an
           ;; earlier one removed it
           :reclaim (fn [subject] (retire-in! state subject) true)
           ;; a served identity already retired by the binding itself
           ;; ends its lease by the grantor's own policy
           :policy (fn [entry _now]
                     (nil? (live-by-identity @state (:subject entry))))
           :writer (::lease-writer opts)
           :self grantor
           :ticks (::lease-ticks opts)
           :media (::lease-media opts)}
    (contains? opts ::lease-drain-budget)
    (assoc :drain-budget (::lease-drain-budget opts))))


(defn- make-judge
  "The composed judge, or the refusal naming the option it fell on."
  [opts state]
  (try
    (lease/make-judge (judge-config opts state))
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      {::status ::refused,
       ::refusals [{::option (get judge-refusal-options
                                  (:refused (ex-data e))
                                  ::lease-media),
                    ::reason ::malformed,
                    ::lease-refusal (ex-data e)}]})))


(defn open!
  "Assemble one export binding from `opts`, or refuse.

   Required options, all policy the composition decides:
     ::channel            the binding's channel end {:reader r :writer w}:
                          r carries remote requests toward this peer,
                          w carries answers back
     ::channel-exclusive? exactly true: the composition's declaration of
                          the binding's ownership contract -- this
                          channel end, and every end later passed to
                          `reattach!`, is dedicated to this binding, and
                          no other reflection or mirror reads or writes
                          it. A handle cannot prove exclusivity, so the
                          composition must declare it; anything but
                          true refuses as ::not-exclusive. `close!` and
                          `reattach!` close a writer on the strength of it
     ::channel-descriptor the channel's portable descriptor, stamped on
                          every served descriptor
     ::surface            handle -> a non-empty subset of #{:reader
                          :writer}, or nil to refuse the handle
     ::admit?             handle -> truthy when the handler authority
                          gate admits exporting it; there is no default
                          -- a composition that admits everything says
                          so with (constantly true)
     ::capacity           the positive bound on live exports
     ::step-budget        the positive bound on channel reads one
                          `step` makes: the drive pass is bounded

   Required lease wiring (`dao.lease.md` Composition duties), every
   duration in `lease-units`:
     ::lease-duration     the duration each grant carries
     ::lease-tolerance    the judge's tolerance, possibly zero ({:ms 0}).
                          Sizing it is the composition owner's duty,
                          which no option here can check: beyond flight
                          time and tick skew it must cover the mirror's
                          read budget -- a renewal or release still
                          unread on the channel when a pass runs (past
                          ::step-budget, or not yet arrived) counts one
                          pass late, so at the deployment's request rate
                          the tolerance must span the passes a backlog
                          of ::step-budget reads per step takes to drain
     ::lease-cadence      the declared maximum interval between completed
                          judge passes: the host drive calls `step` at it
     ::lease-ticks        the judge's tick streams, `[{:handle :cursor}]`:
                          the host drive deposits `dao.lease/tick`
                          readings there as data
     ::lease-media        the judge's standing fact media, `[{:handle
                          :cursor :source :medium}]` as
                          `dao.lease/make-judge` takes them, each
                          declared :per-author-media (a grantor's
                          lease-proposals medium, S6); each grant's
                          renewal medium is wired beside them. A
                          medium whose :source is `grantor` refuses as
                          ::grantor-source: per-author attribution
                          would make its facts the grantor's own
     ::lease-writer       the grantor's stream: grants and :lapsed
     ::lease-renewal-capacity the ring-buffer capacity of each renewal
                          medium (it evicts oldest at that bound)

   Optional:
     ::codec              the channel codec {:encode f :decode f} a
                          served reader's cursors must round-trip
                          through; `yin.vm.ucf.remote/cursor-codec`
                          when absent
     ::lease-max          the cap on total tenure each grant carries
     ::lease-drain-budget the judge's per-cursor drain budget

   Returns the binding, or `{::status ::refused, ::refusals [...]}`
   naming each missing, malformed, not-exclusive or grantor-claiming
   option -- and a
   lease wiring `dao.lease/make-judge` itself refuses, under the option
   it fell on. A refusal publishes nothing and mints nothing: the
   judge is assembled, and the mirror's cursor minted through the
   channel reader, only after every option passes, and a reader that
   will not mint it refuses too."
  [opts]
  (let [rs (refusals opts)]
    (if (seq rs)
      {::status ::refused, ::refusals rs}
      (let [state (atom nil)
            composed (make-judge opts state)
            end (::channel opts)]
        (if (= ::refused (::status composed))
          composed
          (let [c (stream/cursor (:reader end) :dao.stream/oldest)]
            (if-not (= :dao.stream/ok (:dao.stream/outcome c))
              {::status ::refused,
               ::refusals [{::option ::channel, ::reason ::no-cursor,
                            ::outcome c}]}
              (do (reset! state {:registry []
                                 :table {}
                                 :channel end
                                 :cursor (:dao.stream/cursor c)
                                 :detached? false
                                 :judge (:judge composed)
                                 :renewals {}
                                 :closed? false})
                  {::policy (merge {::codec ucf.remote/cursor-codec}
                                   (select-keys opts [::surface ::admit?
                                                      ::capacity ::step-budget
                                                      ::codec ::lease-duration
                                                      ::lease-max
                                                      ::lease-renewal-capacity]))
                   ::channel-descriptor (::channel-descriptor opts)
                   ::lease-step (:step composed)
                   ::state state}))))))))


;; =============================================================================
;; The registry
;; =============================================================================

(defn- live-entry
  "The live registry entry for `h`, by reference identity."
  [state h]
  (some #(when (and (identical? h (:handle %)) (= :live (:status %))) %)
        (:registry state)))


(defn- live-count
  [state]
  (count (filter #(= :live (:status %)) (:registry state))))


(defn- round-trips?
  "True when cursor `c` survives `codec` -- encoded, decoded back, and
   equal: the rule `yin.vm.ucf.remote`'s lift applies to a kept cursor,
   plain data in the channel's portable domain."
  [codec c]
  (try (= c ((:decode codec) ((:encode codec) c)))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ false)))


(defn- portable-cursors?
  "True when `h`'s cursors survive the channel codec: the cursor it
   mints at each standard anchor answers ok and round-trips. A handle
   whose cursors hold a host object cannot be served, and is never
   entered into the table (`dao.stream.remote.md` S2.3)."
  [codec h]
  (every? (fn [anchor]
            (let [r (stream/cursor h anchor)]
              (and (= :dao.stream/ok (:dao.stream/outcome r))
                   (round-trips? codec (:dao.stream/cursor r)))))
          [:dao.stream/oldest :dao.stream/newest]))


(defn- servable-surface
  "The surface policy's answer for `h` when it is a non-empty subset of
   `surfaces` that `h` itself implements and `h` answers descriptor --
   the mirror answers descriptor from the handle directly -- and, for a
   reader, when its cursors are portable; else nil."
  [policy h]
  (let [s ((::surface policy) h)]
    (when (and (set? s)
               (seq s)
               (every? surfaces s)
               (stream/descriptor? h)
               (or (not (contains? s :reader))
                   (and (stream/reader? h)
                        (portable-cursors? (::codec policy) h)))
               (or (not (contains? s :writer)) (stream/writer? h)))
      s)))


(defn- served
  [entry]
  {:dao.stream/identity (:identity entry)
   :dao.stream/channel (:channel entry)})


(defn table
  "The published table as the mirror reads it: `{served-identity
   {:handle h :surface S :dao.lease/lease L}}` (`dao.stream.remote.md`
   S2.3, S6) -- each served identity and, beside it, its renewal
   medium's entry under the same lease. A snapshot, plain data;
   retiring an identity removes both here first."
  [binding]
  (:table @(::state binding)))


(defn entries
  "The registry's entries, oldest first: each `{:handle h :identity id
   :channel cd :surface S :lease L :renewal rid :renewal-handle R
   :status s}`."
  [binding]
  (:registry @(::state binding)))


(defn lease-of
  "The lease a live served `identity` is held under: `{:dao.lease/lease
   L ::renewal {:dao.stream/identity rid :dao.stream/channel cd}}` --
   the lease identity and the served renewal medium a remote holder
   renews and releases on. The grant itself is on the grantor's
   writer. Nil when `identity` is not live in this binding."
  [binding identity]
  (when-some [e (live-by-identity @(::state binding) identity)]
    {:dao.lease/lease (:lease e)
     ::renewal {:dao.stream/identity (:renewal e)
                :dao.stream/channel (:channel e)}}))


(defn judge
  "The judge's state, plain data (`dao.lease/judge-step`'s): its ledger,
   queue and wired media."
  [binding]
  (:judge @(::state binding)))


(defn- renewal-medium
  "A fresh renewal medium: a ring buffer of the declared capacity and
   the judge's own cursor on it, or nil when none can be made."
  [capacity]
  (let [r (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                               ringbuffer/capacity-key capacity})
        h (:dao.stream/handle r)
        c (when h (stream/cursor h :dao.stream/oldest))]
    (when (= :dao.stream/ok (:dao.stream/outcome c))
      {:handle h :cursor (:dao.stream/cursor c)})))


(defn renewal-declaration
  "The explicit medium declaration of a renewal medium of `capacity`
   (`dao.lease.md` Composition duties): a ring buffer evicting its
   oldest at that bound, carrying portable values, attributing each fact
   to its holder by the medium itself."
  [capacity]
  {:retention :evict-oldest
   :capacity capacity
   :value-domain :portable-values
   :attribution :per-author-media})


(defn- grant-for
  "The unsolicited grant for served identity `identity`, held by the
   renewal medium `rid`."
  [policy lease-id identity rid]
  (lease/grant lease-id identity rid (::lease-duration policy)
               (when-some [cap (::lease-max policy)]
                 {:dao.lease/max cap})))


(defn serve!
  "The UCF `serve!` callback (`yin.vm.ucf.remote`): `handle ->
   {:dao.stream/identity id :dao.stream/channel descriptor}`, or nil
   when this binding cannot serve `handle` -- the binding is closed,
   the authority gate refuses, the surface policy refuses or names a
   surface the handle lacks, or the capacity of live exports is
   reached. A handle already live in this binding returns its one
   identity and channel, with no second entry and no second lease. A
   newly served handle gets a freshly minted identity, never one this
   binding or any other has used, a fresh renewal medium entered in the
   table with surface #{:writer}, and a lease granting the identity to
   that medium's holder: the grant is queued on the judge, delivered to
   the grantor's writer and seeded at the next pass, and the renewal
   medium is wired to the judge under its own identity."
  [binding handle]
  (let [state (::state binding)
        policy (::policy binding)
        s @state]
    (when-not (:closed? s)
      (if-some [e (live-entry s handle)]
        (served e)
        (when-let [surface (and ((::admit? policy) handle)
                                (servable-surface policy handle))]
          (when (< (live-count s) (::capacity policy))
            (when-some [medium (renewal-medium
                                 (::lease-renewal-capacity policy))]
              (let [lease-id (lease/mint-lease-id)
                    rid (str (random-uuid))
                    e {:handle handle
                       :identity (str (random-uuid))
                       :channel (::channel-descriptor binding)
                       :surface surface
                       :lease lease-id
                       :renewal rid
                       :renewal-handle (:handle medium)
                       :status :live}
                    g (grant-for policy lease-id (:identity e) rid)
                    ;; wired through dao.lease's declared wiring, which
                    ;; validates the declaration as make-judge does and
                    ;; keeps it on the entry; computed before anything
                    ;; is published, so a refusal publishes nothing
                    j' (-> (:judge s)
                           (lease/author-grant g)
                           (lease/wire-declared-facts
                             {:handle (:handle medium)
                              :cursor (:cursor medium)
                              :source rid
                              :medium (renewal-declaration
                                        (::lease-renewal-capacity policy))}))]
                (swap! state
                       #(-> %
                            (update :registry conj e)
                            (assoc-in [:table (:identity e)]
                                      {:handle handle
                                       :surface surface
                                       :dao.lease/lease lease-id})
                            (assoc-in [:table rid]
                                      {:handle (:handle medium)
                                       :surface #{:writer}
                                       :dao.lease/lease lease-id})
                            (assoc-in [:renewals rid]
                                      {:lease lease-id
                                       :handle (:handle medium)})
                            (assoc :judge j')))
                (served e)))))))))


(defn serve-fn
  "`serve!` closed over `binding`, the one-argument shape
   `yin.vm.ucf.remote` takes."
  [binding]
  (fn [handle] (serve! binding handle)))


;; =============================================================================
;; Retirement
;; =============================================================================

(defn retire!
  "The one retirement transition for served identity `identity` -- the
   lease reclaim procedure is this same transition. First the identity
   and its renewal entry leave the published table, so any later mirror
   step answers a remote operation on either not-found; only then is
   its entry released from the registry and its renewal medium closed.
   Idempotent: an identity not live in this binding -- already retired,
   or never served here -- retires nothing. A lease still in the
   judge's ledger ends with cause :policy at its next pass. Returns
   `{:dao.stream/outcome :dao.stream/ok ::retired? bool}`."
  [binding identity]
  {:dao.stream/outcome :dao.stream/ok
   ::retired? (retire-in! (::state binding) identity)})


(defn close!
  "Retire every live entry, then close the channel writer when it is
   closable: the remote peer's channel reader answers end, and its link
   reports every append! still outstanding as append-unknown -- the
   transport's own close/loss path, nothing settled silently. Closing
   the writer is the binding's to do only because of the ownership
   contract `open!` required (::channel-exclusive?): the channel end is
   dedicated to this binding, so no other reflection or mirror loses
   its channel with it. The leases are not recorded here: each ends
   with cause :policy at the next judge pass, which `step` still runs
   after close. Idempotent; afterwards `serve!` answers nil and `step`
   answers closed."
  [binding]
  (let [state (::state binding)]
    (when-not (:closed? @state)
      (swap! state assoc :closed? true)
      (doseq [e (:registry @state)]
        (retire-in! state (:identity e)))
      (let [w (:writer (:channel @state))]
        (when (stream/closable? w)
          (stream/close! w))))
    {:dao.stream/outcome :dao.stream/ok}))


;; =============================================================================
;; The drive
;; =============================================================================

(defn- budgeted-reader
  "A view of channel reader `r` that passes at most `budget` next calls
   through to `r` and answers blocked, without reading, once they are
   spent. Every outcome passed through is `r`'s own, verbatim, and the
   view never constructs or rewrites a cursor, so the mirror holds
   exactly the successor of the last value it read: blocked leaves it
   there, and the next pass resumes from it. descriptor and cursor are
   `r`'s own. `ended` is set when `r` itself answers end."
  [r budget ended]
  (let [left (volatile! budget)]
    (reify
      stream/IDaoStreamDescriptor

      (descriptor [_] (stream/descriptor r))


      stream/IDaoStreamReader

      (cursor [_ anchor] (stream/cursor r anchor))

      (next
        [_ c]
        (if (pos? @left)
          (do (vswap! left dec)
              (let [o (stream/next r c)]
                (when (= :dao.stream/end (:dao.stream/outcome o))
                  (vreset! ended true))
                o))
          {:dao.stream/outcome :dao.stream/blocked})))))


(defn- prune-renewals
  "Unwire the renewal media of leases that are over: the served entry
   is retired and the lease has left the judge's ledger and queue. A
   lease still pending keeps its medium wired. Unwiring is
   `dao.lease/unwire-facts`'s: nothing here edits the judge's media."
  [s]
  (let [j (:judge s)
        held (into (set (keys (:ledger j)))
                   (map :dao.lease/lease)
                   (:queue j))
        live (into #{} (map :renewal) (:registry s))
        over (into {}
                   (remove (fn [[rid {l :lease}]] (or (live rid) (held l))))
                   (:renewals s))]
    (if (empty? over)
      s
      (-> s
          (update :renewals #(apply dissoc % (keys over)))
          (update :judge
                  #(reduce lease/unwire-facts % (map :handle (vals over))))))))


(defn- lease-pass!
  "One judge pass over the binding's judge: the ticks the host drive
   deposited are now; renewals and releases read on the renewal media
   are evidence; due leases are reclaimed through `retire!` and
   recorded. The reclaim procedure itself swaps the state cell, so the
   judge is threaded back after the pass. Returns the pass's abort
   outcome, or nil."
  [binding]
  (let [state (::state binding)
        j' ((::lease-step binding) (:judge @state))]
    (swap! state #(prune-renewals (assoc % :judge j')))
    (:abort j')))


(defn- mirror-pass!
  [binding]
  (let [state (::state binding)
        s @state
        end (:channel s)
        c (:cursor s)
        ended (volatile! false)
        reader (budgeted-reader (:reader end)
                                (::step-budget (::policy binding))
                                ended)
        c' (remote/mirror-step (:table s) reader c (:writer end))]
    (swap! state assoc :cursor c' :detached? (boolean @ended))
    (not= c c')))


(defn step
  "One bounded drive pass: one `dao.stream.remote/mirror-step` against
   one snapshot of the published table, from the binding's own mirror
   cursor, over at most `::step-budget` reads of the channel reader;
   then one judge pass. The mirror pass ends where the channel answers
   blocked or end, or where the budget is spent; either way the binding
   keeps the successor of the last value read, and the next step
   resumes there -- nothing skipped, nothing answered twice. A channel
   that answers end is detached: nothing is retired, the judge still
   runs, and `reattach!` rebinds. The judge pass follows the mirror
   pass, so a renewal the mirror just appended is evidence in the same
   pass; a reclaim's unpublishing is seen by the next mirror pass. The
   host drive calls `step` at the declared ::lease-cadence. A renewal
   or release the budget leaves unread on the channel is evidence only
   at a later pass: sizing ::lease-tolerance to cover that delay at the
   deployment's request rate is the composition owner's duty (see
   `open!`), not something this step can check.

   Returns `{:dao.stream/outcome :dao.stream/ok ::advanced? bool
   ::detached? bool}`, with `::lease-abort outcome` when the judge pass
   aborted before classifying. Once closed it answers
   `{:dao.stream/outcome :dao.stream/closed}` and runs the judge pass
   alone, so the closed binding's leases still end and are recorded.
   Like serve!, retire! and close!, step belongs to the binding's one
   serialized drive owner: its read and write of the state cell are not
   atomic against a concurrent caller."
  [binding]
  (if (:closed? @(::state binding))
    (do (lease-pass! binding)
        {:dao.stream/outcome :dao.stream/closed})
    (let [advanced? (mirror-pass! binding)
          abort (lease-pass! binding)]
      (cond-> {:dao.stream/outcome :dao.stream/ok
               ::advanced? advanced?
               ::detached? (:detached? @(::state binding))}
        (some? abort) (assoc ::lease-abort abort)))))


(defn reattach!
  "Rebind the binding's channel to the new end `end` {:reader r
   :writer w} -- a detached channel's replacement. Nothing is retired:
   every live identity, its source cursors and its lease answer through
   the new end, the mirror reading `r` from its oldest. `end` falls
   under the ownership contract `open!` declared (::channel-exclusive?),
   so the replaced writer, when closable and not `w` itself, is closed:
   a remote link still reading it answers end, and its unresolved
   appends end as append-unknown -- none is carried to the new end.
   Returns `{:dao.stream/outcome :dao.stream/ok}`, `{:dao.stream/outcome
   :dao.stream/closed}` once closed, or a refusal when `end` is not a
   channel end or its reader will not mint the mirror's cursor."
  [binding end]
  (let [state (::state binding)]
    (cond
      (:closed? @state)
      {:dao.stream/outcome :dao.stream/closed}

      (not (channel-end? end))
      {::status ::refused,
       ::refusals [{::option ::channel, ::reason ::malformed}]}

      :else
      (let [c (stream/cursor (:reader end) :dao.stream/oldest)]
        (if-not (= :dao.stream/ok (:dao.stream/outcome c))
          {::status ::refused,
           ::refusals [{::option ::channel, ::reason ::no-cursor,
                        ::outcome c}]}
          (let [old (:writer (:channel @state))]
            (swap! state assoc
                   :channel end
                   :cursor (:dao.stream/cursor c)
                   :detached? false)
            (when (and (not (identical? old (:writer end)))
                       (stream/closable? old))
              (stream/close! old))
            {:dao.stream/outcome :dao.stream/ok}))))))


;; =============================================================================
;; Frame lift with refusal cleanup
;; =============================================================================

(defn lift-frame
  "`yin.vm.ucf.remote/lift-frame` with this binding's `serve!`, and
   the cleanup a refused whole-frame lift needs: every identity the
   lift itself minted is retired when the frame refuses, so no
   provisional export outlives a lift that published nothing. Handles
   already served before the lift stay served."
  ([binding resources pending]
   (lift-frame binding resources pending (::codec (::policy binding))))
  ([binding resources pending codec]
   (let [before (into #{} (map :identity) (entries binding))
         r (ucf.remote/lift-frame (serve-fn binding) resources pending codec)]
     (when (contains? r :yin.k/status)
       (doseq [e (entries binding)]
         (when-not (contains? before (:identity e))
           (retire! binding (:identity e)))))
     r)))
