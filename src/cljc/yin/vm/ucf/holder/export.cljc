(ns yin.vm.ucf.holder.export
  "The exporting state of M-next D slice D8 (UCF 7.7.4; r3 1.8 and 1.9):
   the holder side of a lift, over a machine value and data.

   `enter` is the one-step transition into exporting: it sets
   `:yin.k/gate :exporting` on the root, requires an empty ready queue,
   and moves the wait set and the reachable parked records into an
   export record.  The machine then has nothing to poll, resume or
   advance, and the gate refuses what remains.  It refuses, as
   `:yin.k/non-portable` of kind `:reason-mismatch`, a task that holds
   (root or any install child) an `:observe` entry, any entry or cell
   carrying `:yin.k/held`, an unminted cursor cell, a pending close, or
   a `:link-request` entry whose response cursor is not installed.

   `prepare` and `encode` are the split of r3 1.8.  Prepare calls the
   exporter's `serve!` once per stream (recursively for install
   children) and stores every answer in the record; `encode` is a pure
   function of that record: it allocates and publishes nothing, and the
   same record gives the same canonical bytes.

   `abort` returns the record to the machine only when the offer
   cannot have been admitted (7.7.4, r3 1.9).

   Nothing here knows a transport, a driver or a journal."
  (:require [yin.vm :as vm]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.completion :as completion]
            [yin.vm.module :as module]
            [yin.vm.ucf.handoff :as handoff]))


;; =============================================================================
;; Refusal data
;; =============================================================================

(defn- non-portable
  [data]
  (assoc data
         :yin.k/status :yin.k/non-portable
         :yin.k/kind :reason-mismatch))


(defn- refused
  [reason]
  {:yin.k/status :yin.k/refused :yin.k/reason reason})


;; =============================================================================
;; The tree of machines: the root and every install child
;; =============================================================================

(defn- machines
  "`[path machine]` of `vm` and, recursively, each install child, in
   install-name order of the map's own iteration."
  [path vm]
  (cons [path vm]
        (mapcat (fn [[m inst]]
                  (when (map? (:vm inst))
                    (machines (conj path m) (:vm inst))))
                (:installs vm))))


(defn- held-entry?
  [x]
  (and (map? x) (contains? x :yin.k/held)))


(defn- refusal-at
  "The first thing machine `m` holds that has no wire form, as the
   data the refusal carries, or nil."
  [m]
  (let [waits (:wait-set m)
        cells (filter map? (vals (:resources m)))]
    (cond
      (some #(= :observe (:reason %)) waits)
      {:yin.k/hold :observe}

      (or (some held-entry? waits) (some held-entry? cells))
      {:yin.k/hold :held}

      (some :yin.k/unminted cells)
      {:yin.k/hold :unminted-cursor}

      (seq (:yin.k/closes m))
      {:yin.k/hold :pending-close}

      (some #(and (= :link-request (:reason %)) (not (contains? % :cursor)))
            waits)
      {:yin.k/hold :link-cursor-not-installed})))


(defn- first-refusal
  [vm]
  (some (fn [[path m]]
          (when (seq (:ready-queue m))
            {:yin.k/status :yin.k/not-quiescent
             :yin.k/path path
             :yin.k/ready (count (:ready-queue m))}))
        (machines [] vm)))


;; =============================================================================
;; Entering exporting
;; =============================================================================

(defn- reachable-parked
  "The ids of the parked records the lift would carry, by the same
   completion walk the lift runs; a walk refusal answers data."
  [vm]
  (let [walked (completion/complete
                 {:vm vm
                  :cursor-profile (constantly :dao.stream.remote/v1)
                  :modules (into {}
                                 (map (fn [[m entry]]
                                        [m {:yin.k/manifest (:address entry)}]))
                                 (module/module-entries (:modules vm)))})]
    (if-some [r (first (:yin.k/refusals walked))]
      {::refusal (if (= :yin.k/not-quiescent (:kind r))
                   {:yin.k/status :yin.k/not-quiescent}
                   (assoc (dissoc r :kind)
                          :yin.k/status :yin.k/non-portable
                          :yin.k/kind (:kind r)))}
      {::ids (set (keys (:yin.k/parked (:yin.k/scheduler walked))))})))


(defn enter
  "Enter exporting from `machine`.  Answers
   `{:status :ok :machine m :record r}`: `m` is `machine` with the gate
   `:exporting`, an empty wait set and no reachable parked record, and
   `r` the export record, holding the wait set in order, the parked
   records, and the gate to return to.  Otherwise answers the data
   outcome of the first refusal, leaving `machine` as it was:
   `:yin.k/not-quiescent` for queued work in the root or a child, a
   `:yin.k/non-portable` of kind `:reason-mismatch` (naming the hold
   and the task path) or the lift's own, or `:yin.k/refused` for a
   machine that is already exporting or ended."
  [machine]
  (cond
    (contains? #{:exporting :ended} (vm/gate-mode machine))
    (refused :not-running)

    :else
    (or (first-refusal machine)
        (some (fn [[path m]]
                (when-some [h (refusal-at m)]
                  (non-portable (assoc h :yin.k/path path))))
              (machines [] machine))
        (let [walk (reachable-parked machine)]
          (or (::refusal walk)
              (let [ids (::ids walk)
                    parked (:parked machine)
                    taken (select-keys parked ids)]
                {:status :ok
                 :machine (-> machine
                              (assoc :yin.k/gate :exporting
                                     :wait-set [])
                              (assoc :parked (apply dissoc parked ids)))
                 :record {:wait-set (vec (:wait-set machine))
                          :parked taken
                          :gate (vm/gate-mode machine)
                          :served {}}}))))))


(defn- reinstated
  "`machine`, an exporting machine, carrying `record`'s waits and
   parked records again: the value the lift reads."
  [machine record]
  (-> (reduce-kv (fn [restored module-name child-record]
                   (update-in restored [:installs module-name :vm]
                              reinstated child-record))
                 machine (or (:children record) {}))
      (assoc :wait-set (:wait-set record))
      (update :parked merge (:parked record))))


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(defn- check-header!
  "A header is nil (the version-0 fork lift) or a map carrying the
   arbitration, the counter and the enrolled set; anything else is a
   defect of the caller, not a refusal.  The lift judges the rest."
  [header]
  (when (some? header)
    (when-not (and (map? header)
                   (contains? header :yin.k/arbitration)
                   (contains? header :yin.k/next-op-seq)
                   (set? (:yin.k/enrolled header)))
      (throw (ex-info "Prepare of a malformed header"
                      {:yin.k/hint :malformed-header})))))


(defn- seeded-by-handle
  "handle -> descriptor for every key the retained `table` already
   answers, resolved through `machine`'s own resource bindings at each
   key's task path: the alias reuse an interrupted prepare had already
   proved, reconstructed before any new key is served, so a retry never
   serves a handle the record already answers under another alias.
   Handles are never persisted; this mapping lives within the one
   call."
  [machine table]
  (let [at (into {} (machines [] machine))]
    (into {}
          (keep (fn [[[path id] descriptor]]
                  (when-some [h (get-in (get at path) [:resources id])]
                    [h descriptor])))
          table)))


(defn prepare
  "Prepare `record`: call `serve!` once per stream of the exporting
   `machine`, install children included, and answer
   `{:status :ok :record r}` with every answer retained in `r`, or the
   data outcome of the lift's refusal with `:record` added, carrying
   what was served before the refusal so a retry never serves a stream
   twice -- not even under a resource alias the refused attempt never
   reached, whose handle the retained table and the machine's bindings
   still identify.

   The served table is keyed by `[task-path resource-id]` and holds the
   descriptor `serve!` answered. This table is plain data, but the live
   waits can contain authentic values: `freeze` is the persistence seam.
   A handle is asked at most once, across the calls
   of one retained record, even when two resources hold it.  `header`
   is nil for the version-0 fork lift, or the version-1 custody header
   (`handoff/export-task`), kept in the record as `:header`.  Prepare
   mints nothing: the occurrence was minted by the driver before it was
   called."
  [machine record serve! header]
  (check-header! header)
  (let [table (atom (:served record))
        by-handle (atom (seeded-by-handle machine (:served record)))
        once (fn [k h]
               (let [t @table]
                 (cond
                   (contains? t k)
                   (let [served (get t k)]
                     ;; an alias of an already-served key asks again for
                     ;; nothing: the handle is known served from here on
                     (swap! by-handle assoc h served)
                     served)
                   (contains? @by-handle h)
                   (let [served (get @by-handle h)]
                     ;; a handle already served under another resource id
                     ;; is the one stream: no second call, and the table
                     ;; must hold this key too or the record is unprepared
                     ;; for a resource it reached
                     (swap! table assoc k served)
                     served)
                   :else
                   (let [served (serve! h)]
                     ;; a refusal is not an answer: a retry asks again
                     (when (some? served)
                       (swap! table assoc k served)
                       (swap! by-handle assoc h served))
                     served))))
        record (assoc record :header header)
        r (handoff/export-task (reinstated machine record) nil
                               {:header header :serve-keyed once
                                :yin.vm.ucf.handoff/recovery true})]
    (if (= :ok (:status r))
      {:status :ok :record (assoc record :served @table)}
      (assoc r :record (assoc record :served @table)))))


(defn encode
  "The lift of the prepared `record` over `machine`: the answer of
   `handoff/export-task`, from a `serve-keyed` that only reads the
   record, under the header the record retains.  It allocates and
   publishes nothing, and one record gives equal bytes every time.  A
   stream the record has no answer for is a defect of the caller (an
   unprepared record), not a refusal."
  [machine record]
  (let [served (:served record)]
    (handoff/export-task
      (reinstated machine record)
      nil
      {:header (:header record)
       :serve-keyed (fn [k _h]
                      (if (contains? served k)
                        (get served k)
                        (throw (ex-info "Encode of an unprepared record"
                                        {:yin.k/hint :unprepared-stream}))))})))


(defn- recovery-address
  [bytes]
  (let [algorithm jing/default-hash-algorithm]
    (keyword "segment"
             (str (get-in jing/registry [algorithm :address-id]) "-"
                  (jing/digest-bytes algorithm bytes)))))


(defn- recovery-metadata
  [machine record]
  {:gate (:gate record)
   :origins (:origins machine)
   :issues (mapv :yin.k/issue (:wait-set record))
   :issued (:yin.k/issued machine)
   :children (into {}
                   (map (fn [[module-name install]]
                          [module-name
                           (recovery-metadata
                             (:vm install)
                             (or (get-in record [:children module-name])
                                 {:gate (vm/gate-mode (:vm install))
                                  :wait-set (:wait-set (:vm install))}))]))
                   (:installs machine))})


(defn freeze
  "Canonical complete export recovery, distinct from the published body.
   Both returned objects must be stored durably before recording a fence."
  [machine prepared-record]
  (let [body (encode machine prepared-record)
        snapshot (when (= :ok (:status body))
                   (handoff/export-task
                     (reinstated machine prepared-record) nil
                     {:header (:header prepared-record)
                      :yin.vm.ucf.handoff/recovery true
                      :serve-keyed (fn [key _handle] (get (:served prepared-record) key))}))
        inspected (when (= :ok (:status snapshot))
                    (handoff/inspect-recovery-body (:bytes snapshot) (:address snapshot)
                                                   (:header prepared-record)))]
    (if (or (not= :ok (:status body)) (not= :ok (:status snapshot))
            (not= :ok (:status inspected)))
      (cond (not= :ok (:status body)) body
            (not= :ok (:status snapshot)) snapshot
            :else inspected)
      (let [recovery {:yin.k/export-recovery true
                      :yin.k/version 1
                      :yin.k/body-bytes (jing/bytes->base64 (:bytes body))
                      :yin.k/body-address (:address body)
                      :yin.k/snapshot-bytes (jing/bytes->base64 (:bytes snapshot))
                      :yin.k/snapshot-address (:address snapshot)
                      :yin.k/header (:header prepared-record)
                      :yin.k/descriptors (into #{}
                                               (map #(assoc (select-keys % [:dao.stream/identity
                                                                            :dao.stream/channel])
                                                            :dao.stream/type :dao.stream/remote))
                                               (vals (:served prepared-record)))
                      :yin.k/state (recovery-metadata machine prepared-record)}
            bytes (cbor/encode recovery)]
        {:status :ok :bytes bytes :address (recovery-address bytes)
         :body-bytes (:bytes body) :body-address (:address body)
         :kind (:kind body)}))))


(defn- extract-recovered
  [machine metadata]
  (let [children (into {}
                       (map (fn [[module-name install]]
                              [module-name
                               (extract-recovered
                                 (:vm install)
                                 (get-in metadata [:children module-name]))]))
                       (:installs machine))
        waits (mapv (fn [entry issue]
                      (cond-> entry
                        (some? issue) (assoc :yin.k/issue issue)))
                    (:yin.k/recovery-waits machine)
                    (:issues metadata))
        record {:wait-set waits :parked (:parked machine) :recovered? true
                :gate (:gate metadata) :served {}
                :children (into {} (map (fn [[module-name recovered]]
                                          [module-name (:record recovered)])) children)}
        fenced (reduce-kv (fn [parent module-name recovered]
                            (assoc-in parent [:installs module-name :vm]
                                      (:machine recovered)))
                          (-> machine
                              (dissoc :yin.k/recovery-waits :yin.k/custody)
                              (assoc :wait-set [] :parked {} :yin.k/gate :exporting
                                     :origins (:origins metadata) :yin.k/issued (:issued metadata)))
                          children)]
    {:machine fenced :record record}))


(defn- valid-recovery-state?
  [body state]
  (and (map? state)
       (contains? #{nil :running :exporting :ended} (:gate state))
       (vector? (:issues state))
       (= (count (:issues state)) (count (:yin.k/frames body)))
       (every? #(or (nil? %) (and (integer? %) (<= 0 %))) (:issues state))
       (or (nil? (:issued state))
           (and (integer? (:issued state)) (<= 0 (:issued state))))
       (or (nil? (:origins state))
           (and (integer? (:origins state)) (<= 0 (:origins state))))
       (map? (:children state))
       (= (set (keys (:children state))) (set (keys (:yin.k/installs body))))
       (every? (fn [[module-name install]]
                 (valid-recovery-state? (:yin.k/child install)
                                        (get-in state [:children module-name])))
               (:yin.k/installs body))))


(defn- snapshot-extends-body?
  [body snapshot]
  (and (= (dissoc body :yin.k/module-stores :yin.k/cells :yin.k/code
                  :yin.k/requires :yin.k/installs)
          (dissoc snapshot :yin.k/module-stores :yin.k/cells :yin.k/code
                  :yin.k/requires :yin.k/installs))
       (every? (fn [field]
                 (every? (fn [[key value]]
                           (= value (get-in snapshot [field key])))
                         (get body field)))
               [:yin.k/module-stores :yin.k/cells :yin.k/code])
       (every? (fn [field]
                 (every? #(contains? (get-in snapshot [:yin.k/requires field]) %)
                         (get-in body [:yin.k/requires field])))
               [:yin.k/segments :yin.k/cursor-profiles])
       (= (set (keys (:yin.k/installs body))) (set (keys (:yin.k/installs snapshot))))
       (every? (fn [[module-name install]]
                 (let [other (get-in snapshot [:yin.k/installs module-name])]
                   (and (= (dissoc install :yin.k/child) (dissoc other :yin.k/child))
                        (snapshot-extends-body? (:yin.k/child install) (:yin.k/child other)))))
               (:yin.k/installs body))))


(defn rehydrate-fenced
  "Restore a complete recovery object into fresh receiver-owned values.
   Attachment reconstructs resources only; it must not perform program IO.
   Never restores tenure, custody, scheduler waits or a runnable gate."
  [receiver recovery-bytes {:keys [address attach]}]
  (try
    (let [recovery (cbor/decode recovery-bytes)]
      (cond
        (not (and (true? (:yin.k/export-recovery recovery))
                  (integer? (:yin.k/version recovery))
                  (= 1 (:yin.k/version recovery))))
        {:yin.k/status :yin.k/profile-mismatch}

        (not (jing/segment-bytes-match? address recovery-bytes))
        {:yin.k/status :yin.k/hash-mismatch}

        :else
        (let [body-bytes (jing/base64->bytes (:yin.k/body-bytes recovery))
              body-address (:yin.k/body-address recovery)
              snapshot-bytes (jing/base64->bytes (:yin.k/snapshot-bytes recovery))
              snapshot-address (:yin.k/snapshot-address recovery)
              published (handoff/inspect-recovery-body body-bytes body-address
                                                       (:yin.k/header recovery) false)
              inspected (handoff/inspect-recovery-body snapshot-bytes snapshot-address
                                                       (:yin.k/header recovery))
              _ (when-not (and (= :ok (:status published))
                               (= :ok (:status inspected))
                               (snapshot-extends-body? (:body published) (:body inspected))
                               (= (:descriptors inspected) (:yin.k/descriptors recovery))
                               (valid-recovery-state? (:body inspected) (:yin.k/state recovery)))
                  (throw (ex-info "Invalid export recovery"
                                  (or (when (:yin.k/status inspected) inspected)
                                      {:yin.k/status :yin.k/undecodable}))))
              handles (atom {})
              restored (handoff/rehydrate-fenced-body
                         receiver snapshot-bytes
                         (fn [descriptor]
                           (let [identity (:dao.stream/identity descriptor)]
                             (if (contains? @handles identity)
                               (get @handles identity)
                               (let [answer (attach descriptor)]
                                 (swap! handles assoc identity answer)
                                 answer))))
                         snapshot-address)]
          (if (not= :ok (:status restored))
            restored
            (let [{:keys [machine record]} (extract-recovered
                                             (:vm restored) (:yin.k/state recovery))
                  by-handle (into {}
                                  (map (fn [descriptor]
                                         [(get-in @handles [(:dao.stream/identity descriptor)
                                                            :dao.stream/handle]) descriptor]))
                                  (:yin.k/descriptors recovery))
                  prepared (prepare machine record by-handle (:yin.k/header recovery))
                  reproduced (when (= :ok (:status prepared))
                               (encode machine (:record prepared)))]
              (if (and (= :ok (:status reproduced))
                       (= (vec body-bytes) (vec (:bytes reproduced))))
                {:status :ok :machine machine :record (assoc (:record prepared) :recovered? true)}
                {:yin.k/status :yin.k/undecodable
                 :yin.k/reason :recovery-inconsistent}))))))
    (catch #?(:cljd Object :clj Throwable :cljs :default) failure
      (or (when (:yin.k/status (ex-data failure)) (ex-data failure))
          {:yin.k/status :yin.k/undecodable}))))


;; =============================================================================
;; Abort
;; =============================================================================

(defn- not-appended?
  "True when `outcome`, the answer of an offer attempt's append, proves
   the value was not appended.  `dao.stream`'s closed append outcome
   set is {ok full invalid-value closed refused transport-error}: the
   four refusals prove it; `ok` proves the opposite, and
   `transport-error` stays the unknown-delivery refusal, as does no
   answer."
  [outcome]
  (contains? #{:dao.stream/full :dao.stream/invalid-value
               :dao.stream/closed :dao.stream/refused}
             outcome))


(defn abort
  "Return the exporting `machine` to local execution from `record`.

   `attempts` is the offer-attempt history the composition persisted:
   one map per attempt whose intent was durable before the send, with
   `:append` the outcome its append answered (absent when none did).
   Abort is legal when there is no attempt, or when every attempt's
   append proves the value was not appended; anything else, including
   an authority's refusal of one attempt, leaves the source fenced.

   A machine under custody (`:yin.k/custody`) is a holder exporting a
   successor: it may abort only with `tenure`, `{:now n :bound b :live
   true}`: `n` before `b` in one unit, and `:live` the ledger reader's
   evidence that this lease is the occurrence's active lease at its
   epoch.  A clock reading alone is not tenure.  After the lease has
   ended its old local machine is never restored.

   Answers `{:status :ok :machine m}` or `:yin.k/refused` data with
   reason `:tenure-ended` (at or past the bound), `:tenure-unconfirmed`
   (no ledger evidence) or `:offer-possibly-accepted`."
  [machine record attempts tenure]
  (cond
    (:recovered? record)
    (refused :restarted)

    (not= :exporting (vm/gate-mode machine))
    (refused :not-exporting)

    (and (contains? machine :yin.k/custody)
         (some? (:now tenure))
         (some? (:bound tenure))
         (>= (:now tenure) (:bound tenure)))
    (refused :tenure-ended)

    (and (contains? machine :yin.k/custody)
         (not (and (some? (:now tenure))
                   (some? (:bound tenure))
                   (true? (:live tenure)))))
    (refused :tenure-unconfirmed)

    (not (every? (comp not-appended? :append) attempts))
    (refused :offer-possibly-accepted)

    :else
    (let [m (reinstated machine record)
          prior (:gate record)]
      {:status :ok
       :machine (if (some? prior)
                  (assoc m :yin.k/gate prior)
                  (dissoc m :yin.k/gate))})))
