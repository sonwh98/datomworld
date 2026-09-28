(ns yin.vm.ffi.remote-serve
  "The FFI export binding: the exporter side of
   `docs/design/dao.stream.remote.md` S2.3 for a machine's served
   handles, composed as one binding value with one drive owner.

   `dao.stream.remote` stays the generic transport -- its mirror
   answers from a plain table and holds nothing between steps -- and
   `yin.vm.ucf.remote` stays a lift/lower facade parameterized by a
   `serve!` callback. This namespace owns what neither does: the
   registry of served handles, the published table the mirror reads,
   the channel end and the mirror's own cursor on it, and the
   retirement of served identities.

   Policy is injected, never decided here. `open!` takes the channel
   end and its descriptor, the per-handle surface policy, the capacity
   of live exports and the handler authority gate as required options
   and refuses an incomplete assembly before anything is published.

   The registry is keyed by the live handle's REFERENCE identity
   (`identical?`), never by descriptor or value equality: two equal
   values are two exports. Each entry records the handle, its minted
   served identity, the channel descriptor, the declared surface and
   its status. Repeated `serve!` for one live handle returns the one
   identity -- `lift-frame` asks for the same handle while minting a
   cell and again while lifting its entry. A served identity is never
   reused: a retired handle served again gets a new one.

   `retire!` is the one retirement transition (lease reclaim, 3b,
   calls it too): it removes the identity from the published table
   first, so a later remote operation answers not-found, and only then
   releases the entry. The binding owns its channel end outright:
   `open!` requires the composition to declare it exclusive (no other
   reflection or mirror uses it), so `close!` retires every entry and
   closes the channel writer, and a remote peer's unresolved appends
   end through the transport's own close/loss path as append-unknown.

   The binding's state cell is private to the binding value: no
   namespace-level registry exists, and the one drive owner that holds
   the binding serializes serve!, step, retire! and close!."
  (:require [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [yin.vm.ucf.remote :as ucf.remote]))


;; =============================================================================
;; Assembly
;; =============================================================================

(def surfaces
  "The surfaces a served entry may declare (`dao.stream.remote.md`
   S2.3): the mirror maps cursor and next to :reader, append! to
   :writer."
  #{:reader :writer})


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
   [::step-budget capacity?]])


(def ^:private optional
  "Each optional option and the check its value must pass when given."
  [[::codec codec?]])


(defn- refusals
  "The required options `opts` lacks, and any option it carries
   malformed, in order."
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
                optional))))


(defn binding?
  "True when `b` is a binding `open!` assembled."
  [b]
  (and (map? b) (contains? b ::state)))


(defn open!
  "Assemble one export binding from `opts`, or refuse.

   Required options, all policy the composition decides:
     ::channel            the binding's channel end {:reader r :writer w}:
                          r carries remote requests toward this peer,
                          w carries answers back
     ::channel-exclusive? exactly true: the composition's declaration of
                          the binding's ownership contract -- this
                          channel end is dedicated to this binding, and
                          no other reflection or mirror reads or writes
                          it. A handle cannot prove exclusivity, so the
                          composition must declare it; anything but
                          true refuses as ::not-exclusive. `close!`
                          closes the writer on the strength of it
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

   Optional:
     ::codec              the channel codec {:encode f :decode f} a
                          served reader's cursors must round-trip
                          through; `yin.vm.ucf.remote/cursor-codec`
                          when absent

   Returns the binding, or `{::status ::refused, ::refusals [...]}`
   naming each missing, malformed or not-exclusive option. A refusal publishes
   nothing and mints nothing: the mirror's cursor is minted through the
   channel reader only after every option passes, and a reader that
   will not mint it refuses too."
  [opts]
  (let [rs (refusals opts)]
    (if (seq rs)
      {::status ::refused, ::refusals rs}
      (let [end (::channel opts)
            c (stream/cursor (:reader end) :dao.stream/oldest)]
        (if-not (= :dao.stream/ok (:dao.stream/outcome c))
          {::status ::refused,
           ::refusals [{::option ::channel, ::reason ::no-cursor,
                        ::outcome c}]}
          {::policy (merge {::codec ucf.remote/cursor-codec}
                           (select-keys opts [::surface ::admit? ::capacity
                                              ::step-budget ::codec]))
           ::channel end
           ::channel-descriptor (::channel-descriptor opts)
           ::state (atom {:registry []
                          :table {}
                          :cursor (:dao.stream/cursor c)
                          :closed? false})})))))


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
   {:handle h :surface S}}` (`dao.stream.remote.md` S2.3). A snapshot,
   plain data; retiring an identity removes it here first."
  [binding]
  (:table @(::state binding)))


(defn entries
  "The registry's entries, oldest first: each `{:handle h :identity id
   :channel cd :surface S :status s}`."
  [binding]
  (:registry @(::state binding)))


(defn serve!
  "The UCF `serve!` callback (`yin.vm.ucf.remote`): `handle ->
   {:dao.stream/identity id :dao.stream/channel descriptor}`, or nil
   when this binding cannot serve `handle` -- the binding is closed,
   the authority gate refuses, the surface policy refuses or names a
   surface the handle lacks, or the capacity of live exports is
   reached. A handle already live in this binding returns its one
   identity and channel, with no second entry. A newly served handle
   gets a freshly minted identity, never one this binding or any
   other has used."
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
            (let [e {:handle handle
                     :identity (str (random-uuid))
                     :channel (::channel-descriptor binding)
                     :surface surface
                     :status :live}]
              (swap! state
                     #(-> %
                          (update :registry conj e)
                          (assoc-in [:table (:identity e)]
                                    {:handle handle :surface surface})))
              (served e))))))))


(defn serve-fn
  "`serve!` closed over `binding`, the one-argument shape
   `yin.vm.ucf.remote` takes."
  [binding]
  (fn [handle] (serve! binding handle)))


;; =============================================================================
;; Retirement
;; =============================================================================

(defn retire!
  "The one retirement transition for served identity `identity`. First
   the identity leaves the published table, so any later mirror step
   answers a remote operation on it not-found; only then is its entry
   released from the registry. Idempotent: an identity not live in this
   binding -- already retired, or never served here -- retires nothing.
   Returns `{:dao.stream/outcome :dao.stream/ok ::retired? bool}`."
  [binding identity]
  (let [state (::state binding)
        live? (some #(and (= identity (:identity %)) (= :live (:status %)))
                    (:registry @state))]
    (when live?
      ;; unpublish: no mirror step after this line can reach the handle
      (swap! state
             (fn [s]
               (-> s
                   (update :table dissoc identity)
                   (update :registry
                           (fn [r]
                             (mapv #(if (= identity (:identity %))
                                      (assoc % :status :retiring)
                                      %)
                                   r))))))
      ;; release: the entry lets go of its handle
      (swap! state update :registry
             (fn [r] (filterv #(not= identity (:identity %)) r))))
    {:dao.stream/outcome :dao.stream/ok
     ::retired? (boolean live?)}))


(defn close!
  "Retire every live entry, then close the channel writer when it is
   closable: the remote peer's channel reader answers end, and its link
   reports every append! still outstanding as append-unknown -- the
   transport's own close/loss path, nothing settled silently. Closing
   the writer is the binding's to do only because of the ownership
   contract `open!` required (::channel-exclusive?): the channel end is
   dedicated to this binding, so no other reflection or mirror loses
   its channel with it. Idempotent; afterwards `serve!` answers nil and `step` answers
   closed."
  [binding]
  (let [state (::state binding)]
    (when-not (:closed? @state)
      (swap! state assoc :closed? true)
      (doseq [e (:registry @state)]
        (retire! binding (:identity e)))
      (let [w (:writer (::channel binding))]
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
   `r`'s own."
  [r budget]
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
              (stream/next r c))
          {:dao.stream/outcome :dao.stream/blocked})))))


(defn step
  "One bounded drive pass: one `dao.stream.remote/mirror-step` against
   one snapshot of the published table, from the binding's own mirror
   cursor, over at most `::step-budget` reads of the channel reader.
   The pass ends where the channel answers blocked or end, or where the
   budget is spent; either way the binding keeps the successor of the
   last value read, and the next step resumes there -- nothing skipped,
   nothing answered twice. Returns `{:dao.stream/outcome :dao.stream/ok
   ::advanced? bool}`, or `{:dao.stream/outcome :dao.stream/closed}`
   once closed. Like serve!, retire! and close!, step belongs to the
   binding's one serialized drive owner: its read and write of the
   state cell are not atomic against a concurrent caller."
  [binding]
  (let [state (::state binding)
        s @state]
    (if (:closed? s)
      {:dao.stream/outcome :dao.stream/closed}
      (let [end (::channel binding)
            c (:cursor s)
            reader (budgeted-reader (:reader end)
                                    (::step-budget (::policy binding)))
            c' (remote/mirror-step (:table s) reader c (:writer end))]
        (swap! state assoc :cursor c')
        {:dao.stream/outcome :dao.stream/ok
         ::advanced? (not= c c')}))))


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
