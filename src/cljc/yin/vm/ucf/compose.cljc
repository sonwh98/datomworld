(ns yin.vm.ucf.compose
  "The composition of M-next D slice D15 (r3 1.5, 1.11 and 1.12; UCF
   7.11's wiring; linker-dht 14.2.1's exclusivity gate): the one entry
   point stage E drives -- plain functions over the landed holder and
   authority namespaces, loadable and drivable with no yin.repl
   namespace anywhere below it.

   The authority side.  `open!` opens the arbitration authority over
   the caller's journal backend and applies the exclusivity gate: an
   exclusive handoff is offered only when `authority/exclusive-capable?`
   holds for the composition's required failure model, and an authority
   that cannot carry it -- a memory backend, a weaker failure model, no
   lock -- answers :yin.k/unsatisfied, never a silent downgrade to
   fork.  Fork is offered only when the caller selects fork, and every
   composition is labelled with the policy it runs (:yin.k/exclusive,
   :yin.k/fork).  Beside the authority the composition owns the judge
   (dao.lease over the authority, wired to its tick log and every
   holder's lease-fact medium), one front per holder, the target and
   outcome readers, and the diagnostic stream.

   The holder side.  `holder` and `source` assemble the landed driver
   (D13/D14/D15a's phases) over the composition-supplied seams -- the
   inbound appender, the durable positional reply and outcome inboxes
   (dao.stream.journal, the complete-retention substrate the D15a
   assembly contract requires; never an evicting ring), the
   authenticated ledger reader, the lease clock, the progress journal
   and the protection declarations -- beside the caller's receiver,
   attach, observe and serve seams.  The carrier for bodies and code is
   the node's existing content store (:store); no second loader, no
   second DHT step owner, lives here.

   The journals.  Every holder's progress journal and every reply
   journal comes from the composition's substrate (:journals at open!):
   `file-journals` over one directory is the production substrate, and
   `memory-journals` over one kept atom the test one.  An exclusive
   composition requires a durable substrate: it refuses at open with a
   data answer -- never a silent substitution of memory storage -- when
   :journals is nil, empty, partial, or declares a failure model that
   does not cover the composition's own (a memory journal declares
   :none, so it is test and fork only; the file journal declares
   :process-crash).  The substrate is the stable recovery configuration:
   the same value handed to a fresh composition reopens the same
   histories under the same identities, and a holder whose progress
   journal already stands is recovered by `driver/reopen` at its
   addition -- a restarted process never mints a second occurrence or a
   second reply journal, the fresh identity the D15a assembly refuses.
   The declaration and the storage cannot disagree: each supplier's
   answered backend is validated against the composition's failure
   model when the composition opens it for a holder, so a substrate
   that declares file durability and supplies a memory backend is a
   refused addition -- the backends it opened for that holder released,
   the way `close!` releases -- never an activated holder journalling
   to storage weaker than the declared model.
   `close!` retires the authority and releases every journal backend the
   composition opened; it never throws, so the exit path can always run
   it.

   The steps.  `control-step` is the custody control plane -- every
   holder's `driver/control-step` (renewals, pending releases, request
   retries), then every front, then the judge, in holder order -- and
   `program-step` is the custody program step (`driver/program-step`
   per holder).  `stop` latches every holder's local stop and the
   composition's own latch: no program work runs once it is set --
   including for a holder added after the latch, which arrives already
   stopped -- while the control plane keeps running for the bounded
   drain.  `owed-control-write?` is the aggregate cadence bit: the
   holders' owed control writes, the fronts' unread requests and the
   judge's undelivered queue.  No thread, timer or step owner lives
   here: the REPL's tick, or a test, or stage E calls the steps."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file-journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ring]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.holder.driver :as driver]))


;; =============================================================================
;; Assembly
;; =============================================================================


(def default-inbound-capacity
  "The composition's default inbound ring capacity, in elements.  The
   backpressure a holder's request stream gives its front; a test or
   stage E overrides it per holder with :inbound."
  64)


(def front-budget
  "How many requests one front step reads, the same bound the driver
   suites drive their fronts with."
  16)


(defn- check!
  [ok what data]
  (when-not ok
    (throw (ex-info (str "The custody composition needs " what)
                    (into {} (cons {:yin.k/hint :assembly} data))))))


(defn- check-interval!
  "The renewal interval gate the driver runs at its own assembly, run
   here so an unsizable composition refuses at open, before any medium
   is minted."
  [units interval]
  (check! (lease/duration? interval)
          "a renewal interval: a single-entry {unit positive-integer} map"
          {:renewal-interval interval})
  (let [[unit magnitude] (first interval)]
    (check! (some? (get units unit))
            "a renewal interval whose unit is in the unit table"
            {:renewal-interval interval :units units})
    (check! (<= magnitude (quot lease/magnitude-limit (get units unit)))
            "a renewal interval within the per-unit bound"
            {:renewal-interval interval})))


(defn- check-substrate!
  "The shape gate for a fork composition's optional substrate (an
   exclusive composition validates :journals as a data refusal instead,
   in exclusive-journals-refusal): nil, or a map whose two suppliers are
   functions."
  [{:keys [journals]}]
  (check! (or (nil? journals)
              (and (map? journals)
                   (every? fn? (keep journals [:progress-backend! :reply-backend!]))))
          "a journal substrate: nil, or {:progress-backend! f :reply-backend! f} over (fn [holder] backend) -- file-journals for production, memory-journals for tests"
          {:journals journals}))


(def ^:private failure-rank
  {:none 0 :process-crash 1 :power-loss 2})


(defn- journal-substrate-capable?
  "True only when a journal substrate's declared durability can carry an
   exclusive composition that requires surviving `required`: its backend
   is :file and its declared failure model covers `required` (a file
   journal declares :process-crash, a memory journal :none).  Nil
   durability -- a substrate that declares nothing -- is never capable."
  [durability required]
  (and (contains? #{:process-crash :power-loss} required)
       (= :file (:dao.stream.journal/backend durability))
       (<= (failure-rank required)
           (get failure-rank (:dao.stream.journal/failure-model durability)
                0))))


(defn- exclusive-journals-refusal
  "The data refusal for an exclusive composition whose :journals cannot
   carry its declared failure model: nil, an empty or partial substrate
   (missing either supplier), or a substrate whose declared durability is
   insufficient -- a memory substrate (:none) or a file substrate asked
   to survive :power-loss.  Nil when the substrate is acceptable.  This
   is the composition's data refusal, beside :exclusive-uncapable, never
   a silent substitution of memory storage."
  [{:keys [journals failure-model]}]
  (let [durability (when (map? journals)
                     (:dao.stream.journal/durability journals))]
    (when (or (nil? journals)
              (not (and (fn? (:progress-backend! journals))
                        (fn? (:reply-backend! journals))))
              (not (journal-substrate-capable? durability failure-model)))
      {:yin.k/status :yin.k/unsatisfied
       :yin.k/reason :journals-uncapable
       :yin.k/policy :yin.k/exclusive
       :yin.k/failure-model failure-model
       :dao.stream.journal/durability durability})))


(defn- check-common!
  [config]
  (check-interval! (:units config) (:renewal-interval config))
  (check! (and (map? (:store config))
               (fn? (:put-bytes-fn (:store config)))
               (fn? (:get-bytes-fn (:store config))))
          "a content store: a dao.jing byte-store handle" {})
  (check! (some? (:diagnostics config)) "a diagnostic stream: a writer" {})
  (check! (some? (:medium config)) "a carrier medium name" {})
  (check! (fn? (:clock config)) "a lease clock: (fn [] reading)" {}))


(defn- log-handle
  "A fresh memory log: the composition's tick and lease-fact media."
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- ring-handle
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- close-backend!
  "Release the journal backend of `entry` when its owner can: a file
   backend closes its content file and releases its directory lock, a
   memory backend owns nothing.  A failure never blocks the next close
   -- the exit path and a refused addition release through this."
  [entry]
  (when-some [backend (:backend entry)]
    (when (contains? backend ::file-journal/close!)
      (try
        (file-journal/close! backend)
        (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))))


(defn- backend-durability
  "The declaration a journal backend answers for itself, as data: the
   same shape the substrate map declares at open, read through the
   backend's own durability seam.  Nil for a backend that declares
   nothing -- which is never capable -- or whose seam cannot be read."
  [backend]
  (when (and (map? backend) (fn? (:dao.stream.journal/durability backend)))
    (try
      ((:dao.stream.journal/durability backend))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn- substrate-journal
  "The journal `supplier` answers for holder `h`, `kind` naming it: the
   backend -- kept, so `close!` releases it -- and its open handle.  A
   composition without a substrate mints a fresh memory journal; a
   substrate journal over frames that already stand reopens that history
   under its own identity, which is the whole of the restart contract.
   An exclusive composition (`required`, its failure model) validates
   the backend itself: the substrate's open-time declaration is not the
   backend's own, so a supplier that answers a backend whose durability
   does not cover `required` -- memory storage beneath a file
   declaration, a backend that declares nothing -- is a refused
   addition, the backend it opened released first, the way `close!`
   releases."
  [supplier h kind required]
  (let [backend (if (fn? supplier)
                  (supplier h)
                  (journal/memory-backend (atom []) nil))
        r (journal/open! backend nil)]
    (check! (= :dao.stream/ok (:dao.stream/outcome r))
            (str "a " (name kind)
                 " journal the substrate opens for the holder")
            {:holder h :dao.stream.journal/defect (:dao.stream.journal/defect r)})
    (let [d (backend-durability backend)]
      (when (and (some? required)
                 (not (journal-substrate-capable? d required)))
        (close-backend! {:backend backend})
        (check! false
                (str "a " (name kind)
                     " journal backend whose declared durability covers"
                     " the composition's failure model")
                {:holder h
                 :yin.k/reason :journals-uncapable
                 :yin.k/policy :yin.k/exclusive
                 :yin.k/failure-model required
                 :dao.stream.journal/durability d})))
    {:backend backend :journal (:dao.stream/handle r)}))


(defn memory-journals
  "The composition's journal substrate over one atom the caller keeps:
   every journal its own frame vector under [holder kind].  The
   in-process substrate -- the same atom handed to a fresh composition
   reopens the same histories, so a crash between the two is the
   fresh-journal/reopened-journal pattern the driver's own rows use.  It
   declares journal/memory-durability (:failure-model :none): the test and
   fork substrate, refused by an exclusive composition."
  [frames]
  (let [frame-at! (fn [h kind]
                    (or (get @frames [h kind])
                        (do (swap! frames assoc [h kind] (atom []))
                            (get @frames [h kind]))))]
    {:dao.stream.journal/durability journal/memory-durability
     :progress-backend! (fn [holder]
                          (journal/memory-backend (frame-at! holder :progress) nil))
     :reply-backend! (fn [holder]
                       (journal/memory-backend (frame-at! holder :reply) nil))}))


(defn file-journals
  "The composition's journal substrate over one directory (production):
   each holder's progress and reply journal lives in its own
   subdirectory of `root`, one dao.stream.journal.file backend each.
   The substrate is the stable recovery configuration -- the same `root`
   handed to a fresh composition reopens the same histories under the
   same identities, so a restarted process never mints the fresh reply
   journal the driver's assembly refuses.  A holder identity names one
   path segment; one that cannot is a refused addition.  The
   composition closes the backends it opens at `close!`.  It declares
   dao.stream.journal.file/durability (:failure-model :process-crash):
   the production substrate, capable of an exclusive composition that
   requires :process-crash (not :power-loss)."
  [root]
  (let [open! (fn [h kind]
                (let [segment (str h)]
                  (check! (not (re-find #"[\\/]" segment))
                          "a holder identity that names one path segment"
                          {:holder h})
                  (let [r (file-journal/backend! (str root "/" segment "-"
                                                      (name kind)))]
                    (check! (= :dao.stream/ok (:dao.stream/outcome r))
                            (str "a " (name kind)
                                 " journal directory the substrate can lock")
                            {:holder h
                             :dao.stream.journal/defect
                             (:dao.stream.journal/defect r)})
                    (get r ::file-journal/backend))))]
    {:dao.stream.journal/durability file-journal/durability
     :progress-backend! (fn [holder] (open! holder :progress))
     :reply-backend! (fn [holder] (open! holder :reply))}))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


;; =============================================================================
;; The durable positional inboxes (D15a's production adapters)
;; =============================================================================


(defn inbox-over-journal
  "The version-1 positional inbox descriptor over journal handle `h`,
   its records attributed to `author`: complete retention, stable
   identity, non-destructive reads at dense exact positions -- the
   substrate D15a's assembly requires and the reply-transport research
   named (a ring or a bare memory log is disqualified: one evicts, both
   mint a fresh identity per process).  Answers the closed result union
   the driver's assembly validates: :record, :empty at the tail,
   :unavailable for inaccessible history.  A journal never gaps."
  [h author]
  (let [identity (:dao.stream/identity (stream/descriptor h))]
    {:version 1
     :identity identity
     :read-at!
     (fn [position]
       (let [answer (stream/next
                      h
                      {:dao.stream.memory-log/identity identity
                       :dao.stream.memory-log/position position})]
         (case (:dao.stream/outcome answer)
           :dao.stream/ok {:status :record :position position
                           :author author
                           :record (:dao.stream/value answer)}
           (:dao.stream/blocked :dao.stream/end) {:status :empty}
           {:status :unavailable})))}))


(defn inbox-over-outcomes
  "The version-1 positional inbox descriptor over the authority's
   outcome reader `r`, its outcomes attributed to `author` (the
   arbitration identity): positions dense from zero in ledger t order,
   stable across reopen -- the projection's own cursor form, read
   positionally without minting."
  [r author]
  (let [identity (:dao.stream/identity (stream/descriptor r))]
    {:version 1
     :identity identity
     :read-at!
     (fn [position]
       (let [answer (stream/next
                      r
                      {:yin.vm.ucf.authority.admission/outcomes identity
                       :yin.vm.ucf.authority.admission/position position})]
         (case (:dao.stream/outcome answer)
           :dao.stream/ok {:status :record :position position
                           :author author
                           :record (:dao.stream/value answer)}
           :dao.stream/blocked {:status :empty}
           {:status :unavailable})))}))


;; =============================================================================
;; The authenticated ledger reader (r3 1.5's third path)
;; =============================================================================


(defn- ledger-reader
  "The holder's ledger read over the authority's own journal: every
   record from the origin, attributed to the arbitration identity, or
   nil when the medium cannot be attached at all.  A read that fails
   mid-history answers the stream outcome in the record's place, which
   the holder's own history check reads as the transport error it is --
   unavailable, never an empty prefix."
  [a arb]
  (fn []
    (let [h (:journal a)
          origin (stream/cursor h :dao.stream/oldest)]
      (when (= :dao.stream/ok (:dao.stream/outcome origin))
        (loop [cursor (:dao.stream/cursor origin) records []]
          (let [answer (stream/next h cursor)]
            (case (:dao.stream/outcome answer)
              :dao.stream/ok (recur (:dao.stream/cursor answer)
                                    (conj records [arb (:dao.stream/value answer)]))
              (:dao.stream/blocked :dao.stream/end) records
              (conj records [arb answer]))))))))


;; =============================================================================
;; Open, and the exclusivity gate
;; =============================================================================


(defn arbitration
  "The arbitration medium map this composition's exclusive exports
   carry.  Nil for a fork, which arbitrates nothing."
  [c]
  (when-some [i (:identity c)]
    {:dao.stream/identity i
     :dao.stream/descriptor {:dao.stream/type :dao.stream/journal}}))


(defn- bare-composition
  [{:keys [mode failure-model renewal-interval store diagnostics medium
           clock units export-version inbound-capacity journals]}]
  {:mode mode
   :yin.k/policy (case mode :exclusive :yin.k/exclusive :yin.k/fork)
   :failure-model failure-model
   :renewal-interval renewal-interval
   :store store
   :diagnostics diagnostics
   :medium medium
   :clock clock
   :units units
   :export-version export-version
   :inbound-capacity (or inbound-capacity default-inbound-capacity)
   :journals journals
   :authority nil
   :identity nil
   :judge nil
   :ticks nil
   :outcome-reader nil
   :outcome-inbox nil
   :stopped? false
   :fronts {}
   :holders {}
   :holder-configs {}
   :inbounds {}
   :lease-media {}
   :reply-journals {}
   :progress {}})


(defn- open-exclusive
  "The exclusive composition opens its authority through the grantor
   reopen protocol (grant/reopen!): a fresh ledger opens as it is, and a
   ledger that already stands -- a restarted composition's -- has every
   tenure it still shows live reclaimed (:policy lapses, the epoch
   raised) before the authority is handed out.  The judge is rebuilt
   from the projection over the same protocol (grant/rebuild-judge), so
   a recovered holder's release and re-proposals meet a judge that knows
   the ledger's history; without the reclaim a live tenure the crashed
   judge granted would stand outside the rebuilt judge's ledger forever,
   its holder's release never landing."
  [config]
  (let [r (grant/reopen! (:backend config) nil)
        a (::authority/authority r)]
    (cond
      (nil? a)
      {:yin.k/status :yin.k/unsatisfied
       :yin.k/reason :authority-refused
       :yin.k/defect (:yin.k/defect r)}

      ;; the gate (r3 1.11): exclusive only on a capable authority --
      ;; refused with the declaration beside it, never downgraded to fork
      (not (authority/exclusive-capable? a (:failure-model config)))
      (do (authority/close! a)
          {:yin.k/status :yin.k/unsatisfied
           :yin.k/reason :exclusive-uncapable
           :yin.k/policy :yin.k/exclusive
           :yin.k/failure-model (:failure-model config)
           :dao.stream.journal/durability (authority/durability a)})

      :else
      (let [arb (:dao.stream/identity r)
            ticks (log-handle)
            outcome-reader (admission/outcome-reader a)
            c (assoc (bare-composition config)
                     :authority a
                     :identity arb
                     :ticks ticks
                     :outcome-reader outcome-reader
                     :outcome-inbox (inbox-over-outcomes outcome-reader arb)
                     :judge (lease/wire-tick
                              (grant/rebuild-judge
                                a
                                (lease/initial-judge
                                  (merge (grant/judge-config a (:duration config))
                                         {:resolver (fn [source _] source)
                                          :self arb})))
                              ticks (oldest ticks) :ticks))]
        {:yin.k/status :open
         ::composition c
         :dao.stream/identity arb}))))


(defn open!
  "Open the custody composition over `config`:

     :mode             :exclusive or :fork (required; fork is never the
                       default and never a downgrade)
     :failure-model    the exclusive composition's required failure
                       model, :process-crash or :power-loss
     :backend          the authority's dao.stream.journal backend
                       (exclusive only; fork selects no authority)
     :duration         the grant duration map (exclusive only)
     :renewal-interval the renewal interval, strictly below half the
                       duration (both modes: the driver assembly needs it)
     :store            the node's existing content store (a dao.jing
                       byte-store handle): the carrier for bodies and code
     :diagnostics      the composition's diagnostic stream (a writer)
     :medium           the carrier medium name offers carry
     :clock            (fn [] reading), the lease clock
     :units            an optional unit table, dao.lease's default
     :export-version   an optional body-version override of the lift
     :inbound-capacity the default inbound ring capacity
     :journals         the journal substrate (required for :exclusive):
                       `file-journals` over one directory is the
                       production substrate, `memory-journals` over one
                       kept atom the test one.  Every holder's progress
                       journal and every reply journal comes from it, so
                       the same substrate value hands a restarted process
                       the same histories under the same identities.  A
                       fork needs none.

   Answers {:yin.k/status :open ::composition c :dao.stream/identity i},
   or a data refusal.  An exclusive composition over an authority that
   cannot carry its required failure model -- a memory backend, a
   weaker model -- answers :yin.k/unsatisfied with
   :yin.k/reason :exclusive-uncapable and the backend's durability
   declaration beside it; nothing is composed and fork is not offered.
   An exclusive composition whose :journals substrate is nil, empty,
   partial, or declares a failure model that does not cover its own
   answers :yin.k/unsatisfied with :yin.k/reason :journals-uncapable and
   the substrate's durability declaration -- never a silent substitution
   of memory storage.  The substrate's declaration is then checked
   against each backend its suppliers answer at every holder's
   addition: a backend whose own durability does not cover the failure
   model -- memory storage beneath a file declaration -- is a refused
   addition carrying the backend's declaration, every backend the
   substrate opened for that holder released first.

   An exclusive composition is its authority's grantor, and it opens
   through the grantor reopen protocol: over a ledger that already
   stands -- a restarted composition's -- every tenure still live is
   reclaimed (:policy lapses, the epoch raised) before the authority is
   handed out, and the judge is rebuilt from the projection, so the
   holders a `:journals` substrate recovers meet a judge that knows the
   ledger's history."
  [{:keys [mode backend duration] :as config}]
  (let [config (update config :units #(or % lease/default-units))]
    (check! (contains? #{:exclusive :fork} mode)
            ":mode :exclusive or :fork, selected by the caller" {:mode mode})
    (check-common! config)
    (case mode
      :fork (do (check-substrate! config)
                (check! (nil? backend)
                        "no :backend with :mode :fork: fork selects no authority"
                        {:backend backend}))
      :exclusive (do (check! (some? backend) "an authority journal backend" {})
                     (check! (contains? #{:process-crash :power-loss}
                                        (:failure-model config))
                             "a failure model: :process-crash or :power-loss"
                             {:failure-model (:failure-model config)})
                     (check! (some? duration) "a grant duration map" {})))
    (case mode
      :fork {:yin.k/status :open
             ::composition (bare-composition config)
             :yin.k/policy :yin.k/fork}
      :exclusive (or (exclusive-journals-refusal config)
                     (open-exclusive config)))))


(defn close!
  "Retire the composition before its process exits (r3 1.12): the
   authority first, then every journal backend the composition itself
   opened -- each holder's progress journal, each reply journal --
   leaving their directories unlocked for the next open.  The caller's
   media are their owners': the authority's own backend, the store and
   a supplied :inbound are not touched.  A close that fails never
   blocks the others, so the exit path can always run it.
   Idempotent."
  [c]
  (when-some [a (:authority c)]
    (try
      (authority/close! a)
      (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))
  (doseq [entry (concat (vals (:progress c)) (vals (:reply-journals c)))]
    (close-backend! entry))
  nil)


;; =============================================================================
;; The holder side
;; =============================================================================


(defn- holder-media
  "The per-holder media the composition mints (or takes from `config`'s
   :inbound): the inbound stream, the reply journal -- one durable
   substrate the front appends to and the holder reads positionally, its
   backend kept so the composition reopens and closes it -- the
   lease-fact medium, the progress journal with its backend, and the
   inboxes.  Both journals come from the composition's substrate, so a
   holder whose journals already stand reopens them by identity.  An
   exclusive composition validates each backend the substrate answers
   against its failure model before anything is composed over it: a
   backend weaker than the substrate's declaration is a refused
   addition, and the refusal releases the backend the substrate already
   opened for this holder -- never-throw, like `close!`.  A fork holds
   no authority, so its inboxes read inert logs nothing ever appends
   to: the fork path sends and reads nothing."
  [c h config]
  (let [fork? (= :fork (:mode c))
        inbound (or (:inbound config) (ring-handle (:inbound-capacity c)))
        ;; the exclusive composition's failure model, the model each
        ;; answered backend must itself cover; a fork demands nothing
        required (when-not fork? (:failure-model c))
        progress (substrate-journal (get-in c [:journals :progress-backend!])
                                    h :progress required)
        ;; a refused reply journal releases the progress backend the
        ;; substrate already opened for this holder before it refuses
        reply-entry (try
                      (when-not fork?
                        (substrate-journal (get-in c [:journals :reply-backend!])
                                           h :reply required))
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (close-backend! progress)
                        (throw e)))
        reply (if fork? (log-handle) (:journal reply-entry))
        lease (log-handle)]
    {:inbound inbound
     :reply reply
     :reply-backend (some-> reply-entry :backend)
     :lease-medium lease
     :backend (:backend progress)
     :journal (:journal progress)
     :reply-inbox (inbox-over-journal reply (:identity c))
     :outcome-inbox (if fork?
                      (inbox-over-journal (log-handle) nil)
                      (:outcome-inbox c))
     :read-ledger! (if fork?
                     (fn [] nil)
                     (ledger-reader (:authority c) (:identity c)))
     :enroll! (when-not fork? (fn [] (authority/enroll! (:authority c))))}))


(def ^:private holder-config-keys
  "The keys a holder/source config may carry: the checkpoint (bytes and
   address), the source's machine, the arbitration decision
   (arbitration-of's, never the caller's), the protection declaration,
   the receiving machine, the receiver's own attach/observe/serve seams,
   an optional renewal-interval override, and an optional :inbound stream
   (consumed by holder-media, not passed to the driver).  Every other key
   -- :journal, :reply-inbox, :outcome-inbox, :read-ledger!,
   :append-request!, :append-diagnostic!, :clock, :store, :medium, :me,
   :units, :export-version, :enroll!, :journals -- is a composition seam,
   refused at add-holder, never overridable by a holder config."
  [:bytes :address :machine :arbitration :protection :receiver
   :attach! :observe! :serve! :renewal-interval :inbound])


(defn- check-holder-config-keys!
  "Refuse a holder config that carries a key outside holder-config-keys:
   each such key is a composition seam the holder may not supply."
  [h config]
  (let [allowed (set holder-config-keys)]
    (doseq [k (keys config)]
      (check! (contains? allowed k)
              (str "a holder config of only the documented holder keys; "
                   (pr-str k) " is a composition seam")
              {:holder h :key k}))))


(defn- driver-config
  "The landed driver's config for holder `h`: the composition's seams and
   media, then the holder's own keys from `config` (holder-config-keys
   less :inbound) -- a holder config cannot substitute a composition seam,
   because add-holder refuses any key outside holder-config-keys first."
  [c h media config]
  (merge {:me h
          :renewal-interval (:renewal-interval c)
          :units (:units c)
          :export-version (:export-version c)
          :clock (:clock c)
          :store (:store c)
          :medium (:medium c)
          :journal (:journal media)
          :append-request! (fn [request]
                             (stream/append! (:inbound media) request))
          :reply-inbox (:reply-inbox media)
          :outcome-inbox (:outcome-inbox media)
          :read-ledger! (:read-ledger! media)
          :append-diagnostic! (fn [d] (stream/append! (:diagnostics c) d))}
         (select-keys config (disj (set holder-config-keys) :inbound))
         (when-some [enroll! (:enroll! media)]
           {:enroll! enroll!})))


(defn- front-of
  "The holder's front over its media: the resolver names this holder
   alone on its own inbound, and the lease-medium lookup answers this
   holder alone -- one front per holder, as C11 composed it."
  [c h media]
  (front/front {:authority (:authority c)
                :inbound (:inbound media)
                :reply (:reply media)
                :diagnostics (:diagnostics c)
                :resolver (fn [_identity _value] h)
                :store (:store c)
                :lease-media (fn [author]
                               (when (= author h) (:lease-medium media)))}))


(defn- arbitration-of
  "The holder's config with the composition's arbitration decision
   applied: an exclusive composition names its own arbitration medium --
   never the caller's -- and a fork refuses one outright, because a fork
   arbitrates nothing and a caller-supplied medium must not make a
   labelled fork run an exclusive export."
  [c h config]
  (if (= :fork (:mode c))
    (do (check! (nil? (:arbitration config))
                "no :arbitration with :mode :fork: a fork arbitrates nothing"
                {:holder h :arbitration (:arbitration config)})
        config)
    (assoc config :arbitration (arbitration c))))


(defn- add-holder
  [c h config make]
  (check! (map? config) (str "holder " (pr-str h) "'s config") {})
  (check! (not (contains? (:holders c) h))
          "a fresh holder identity" {:holder h})
  (check-holder-config-keys! h config)
  (let [config (arbitration-of c h config)
        media (holder-media c h config)
        cfg (driver-config c h media config)
        ;; the progress journal is the truth of a holder: one that
        ;; already stands is folded by `driver/reopen` -- the recovered
        ;; holder keeps its occurrence, waits and pending brackets --
        ;; and only an empty journal makes a fresh driver.  A restarted
        ;; process composes the same substrate and reopens; it never
        ;; mints a second occurrence over a live history.
        recovered? (pos? (count (driver/journal-records (:journal media))))
        made (if recovered? (driver/reopen cfg) (make cfg))
        ;; a stopped composition receives its holders already stopped:
        ;; the latch is the composition's truth, whatever joined late
        made (if (:stopped? c) (driver/stop made) made)
        c' (-> c
               (assoc-in [:inbounds h] (:inbound media))
               (assoc-in [:lease-media h] (:lease-medium media))
               (assoc-in [:progress h] {:backend (:backend media)
                                        :journal (:journal media)})
               (assoc-in [:reply-journals h] {:backend (:reply-backend media)
                                              :journal (:reply media)})
               (assoc-in [:holder-configs h] cfg)
               (assoc-in [:holders h] made))]
    (if (= :fork (:mode c'))
      c'
      (-> c'
          (assoc-in [:fronts h] (front-of c' h media))
          (update :judge lease/wire-facts
                  (:lease-medium media) (oldest (:lease-medium media)) h)))))


(defn holder
  "Add candidate `h` over the checkpoint the composition fetched:
   `config` carries :bytes and :address (required) beside the holder's
   own seams -- :protection, :receiver, :attach!, :observe!, :serve! --
   and an optional :inbound stream overriding the composition's ring
   (the bounded medium a test or stage E supplies).  The composition
   supplies the rest of the driver's assembly: the inbound appender,
   the reply and outcome inboxes, the ledger reader, the lease clock,
   the progress journal, the content store and the carrier medium.  A
   holder config cannot substitute any of those composition seams: only
   the keys documented here are read from it (see holder-config-keys).  A
   holder whose progress journal already stands on the composition's
   substrate is recovered by reopen at this addition, like `source`."
  [c h config]
  (add-holder c h config driver/initial))


(defn source
  "Add the source half: `config` carries :machine -- the machine at its
   liftable safepoint, holding no custody -- beside the holder seams of
   `holder` (no :bytes and :address: the driver mints those).  An
   exclusive composition offers the export on its own arbitration --
   the composition names it, and a supplied :arbitration is a refused
   assembly; a fork composition (the composition's fork decision,
   labelled :yin.k/fork) lifts without an occurrence, an offer or a
   journal bracket, and the driver answers the bytes.  A source whose
   progress journal already stands on the composition's substrate is
   recovered by reopen, not re-exported: the machine matters only to a
   first export."
  [c h config]
  (add-holder c h config driver/source))


(defn restart
  "Recover holder `h` from its progress journal after a process
   restart: a fresh handle over the same journal backend, folded by
   `driver/reopen` -- a journaled grant never restores execution; it
   releases and re-enters candidacy, and a complete fenced source
   reopens fenced, resending its offer.  A stopped composition
   reapplies the local stop latch: reopen never re-establishes it from
   persisted state."
  [c h]
  (let [journal' (:dao.stream/handle
                   (journal/open! (get-in c [:progress h :backend]) nil))
        reopened (driver/reopen (assoc (get-in c [:holder-configs h])
                                       :journal journal'))]
    (-> c
        (assoc-in [:progress h :journal] journal')
        (assoc-in [:holders h] (if (:stopped? c)
                                 (driver/stop reopened)
                                 reopened)))))


(defn journal-of
  "Holder `h`'s progress journal handle: the whole durable history a
   `restart` folds."
  [c h]
  (get-in c [:progress h :journal]))


(defn hand-off
  "The composition's explicit export of holder `h`'s parked continuation
   (`driver/hand-off`): the exit half arms over the safepoint the next
   control and program steps drive to its report, release and closure."
  [c h]
  (update-in c [:holders h] driver/hand-off))


(defn enroll
  "Holder `h`'s bracketed enrollment (`driver/enroll`) over the
   composition's enrollment seam."
  [c h]
  (update-in c [:holders h] driver/enroll))


(defn abort
  "The composition's abort of holder `h`'s source export
   (`driver/abort`): legal only before any possibly accepted offer
   attempt, or on authoritative evidence none was admitted."
  [c h]
  (update-in c [:holders h] driver/abort))


(defn enroll!
  "Enroll one target on the authority (`authority/enroll!`), the
   enrolled boundary admission appends to.  A fork has no authority and
   answers :yin.k/unsatisfied."
  [c]
  (if-some [a (:authority c)]
    (authority/enroll! a)
    {:yin.k/status :yin.k/unsatisfied :yin.k/reason :fork}))


(defn target-reader
  "The reader of enrolled target `i` (`authority/target-reader`): its
   committed appends at positions dense in ledger t order, blocked at
   the tail, end after its close.  Nil for a target this authority
   never enrolled, and for a fork."
  [c i]
  (when-some [a (:authority c)]
    (authority/target-reader a i)))


(defn outcome-reader
  "The authority's outcome projection reader (`admission/outcome-reader`):
   the :committed outcome of every fenced admission, in ledger t order,
   at positions stable across reopen.  Nil for a fork."
  [c]
  (:outcome-reader c))


;; =============================================================================
;; The steps
;; =============================================================================


(defn- ordered-holders
  [c]
  (sort-by str (keys (:holders c))))


(defn- step-holders
  [c step]
  (reduce (fn [c h] (update-in c [:holders h] step))
          c (ordered-holders c)))


(defn- step-fronts
  [c]
  (reduce (fn [c h] (update-in c [:fronts h] front/step front-budget))
          c (sort-by str (keys (:fronts c)))))


(defn- step-judge
  "One judge pass at the clock's own reading: the tick is appended, then
   the judge runs under the authority's lock, granting, refusing and
   lapsing what its media carried this tick."
  [c]
  (stream/append! (:ticks c) (lease/tick ((:clock c))))
  (assoc c :judge (grant/step! (:authority c) (:judge c))))


(defn control-step
  "One custody control-plane tick: every holder's control step -- the
   renewals, pending releases and request retries of the split driver,
   never a lower, an execution or a program observation -- then the
   judge -- grants, refusals and lapses at the clock's own reading --
   then every front -- each request carried and answered -- in holder
   order, one owner for each component.  The fronts follow the judge so
   a lease fact a front carries reaches the judge one tick later, by
   which time the holder has already read the front's reply: the reply
   is always observable before the transition it evidences, which is
   what keeps a program step's independent binding check (the ledger)
   from racing the control step's carriage evidence (the inbox).  Safe
   to call during hydration and through the bounded shutdown drain: it
   is the whole of custody progress those states allow."
  [c]
  (if (not (map? c))
    c
    (let [c (step-holders c driver/control-step)]
      (if (= :fork (:mode c))
        c
        (-> c step-judge step-fronts)))))


(defn program-step
  "One custody program tick: every holder's `driver/program-step` --
   activation under independently revalidated tenure, execution, replay,
   emission, live observation and export preparation.  The REPL calls
   this only while the shell is admitted and running; a stopped or
   journal-stalled holder performs no program work whatever the caller
   does, and the composition's own latch is the same boundary: once
   `stop` has latched, this is a no-op for every holder, including one
   added after the latch (which `add-holder` receives already
   stopped)."
  [c]
  (if (and (map? c) (not (:stopped? c)))
    (step-holders c driver/program-step)
    c))


(defn step
  "One whole composition tick -- the control plane, then the program
   plane -- for tests and stage E's combined driving.  The REPL uses
   the split entries."
  [c]
  (program-step (control-step c)))


(defn stop
  "Latch the local stop on every holder (`driver/stop`) and the
   composition's own latch: program work ends -- `program-step` is a
   no-op while the latch stands, and a holder added after it arrives
   already stopped -- while control cleanup -- releases, retries, late
   grants answered with a release -- continues on every later
   `control-step`.  Idempotent, and reapplied by `restart` and
   `add-holder` while the composition stays stopped."
  [c]
  (if (map? c)
    (-> c (step-holders driver/stop) (assoc :stopped? true))
    c))


(defn owed-control-write?
  "The aggregate cadence bit (the D15a seam ruling): true while any
   holder owes a control write its protocol has not discharged with
   authenticated evidence -- a proposal, renewal, release, offer or
   report still eligible to be attempted -- or a front may still hold
   unread requests, or the judge an undelivered grant.  Pure and total
   over the composition value."
  [c]
  (boolean
    (and (map? c)
         (or (some driver/owed-control-write? (vals (:holders c)))
             ;; a front that spent its whole read budget may owe more;
             ;; one halted at the tail owes nothing
             (some #(nil? (get % :yin.vm.ucf.authority.front/halted))
                   (vals (:fronts c)))
             (seq (get-in c [:judge :queue]))))))


(defn status
  "The composition's plain summary for the operator and stage E: the
   policy it runs, and each holder's phase, status and detail."
  [c]
  {:yin.k/policy (:yin.k/policy c)
   :holders (into {}
                  (map (fn [[h st]]
                         [h (select-keys st [:phase :status :detail])]))
                  (:holders c))})
