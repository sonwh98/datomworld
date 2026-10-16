(ns yin.vm.ucf.holder.export
  "The exporting state of M-next D slice D8 (UCF 7.7.4; r3 1.8 and 1.9),
   over the row carrier since phase 7b: the holder side of a lift, over a
   machine value and data.

   `enter` is the one-step transition into exporting: it sets
   `:yin.k/gate :exporting` on the root and moves the wait set, ordered ready queue and
   every parked record into an export record.
   The machine then has nothing to poll, resume or advance, and the gate
   refuses what remains.  It refuses, as `:yin.k/non-portable` of kind
   `:reason-mismatch`, a task that holds (root or any install child) an
   `:observe` entry, any entry or cell carrying `:yin.k/held`, an
   unminted cursor cell, a pending close, or a `:link-request` entry
   whose response cursor is not installed.

   `prepare` and `encode` are the split of r3 1.8, over `rows/lift-task`.
   Prepare calls the exporter's `serve!` once per stream (recursively for
   install children) and stores every answer in the record, keyed by
   task path and resource id (the lift's `:serve-keyed`); `encode` is a
   pure function of that record and the machine: it allocates and
   publishes nothing, and the same record gives the same canonical pack
   bytes.  The record retains the custody header and the composition's
   source index the lift ran under: code travels by A, and every carried
   A names its trees from that index (continuation §6.1).

   `freeze` writes the export-recovery object, version 2: the published
   pack (its address, its root id, its bytes) beside the holder-local
   engine state the pack does not carry (gate, issue stamps, issued and
   origin counters, per task) and the header, whose enrolled set never
   travels.  The pack already carries the whole store, every parked
   record and every module store the tasks reach, so no second body is
   stored.  `rehydrate-fenced` restores it administratively through
   `rows/lower-task {:fenced true}` and proves the published pack is
   reproduced byte for byte.

   `abort` returns the record to the machine only when the offer cannot
   have been admitted (7.7.4, r3 1.9).

   Nothing here knows a transport, a driver or a journal."
  (:require [yin.vm :as vm]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows.checkpoint :as checkpoint]))


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


;; =============================================================================
;; Entering exporting
;; =============================================================================

(defn enter
  "Enter exporting from `machine`.  Answers
   `{:status :ok :machine m :record r}`: `m` is `machine` with the gate
   `:exporting`, empty wait and ready queues and no parked record,
   and `r` the export record, holding waits and completed outcomes in
   order, every parked record
   (the row carrier carries them all, continuation §2.5), and the gate
   to return to.  Otherwise answers the data outcome of the first
   refusal, leaving `machine` as it was: a `:yin.k/non-portable` of kind
   `:reason-mismatch` (naming the hold and the task path), or
   `:yin.k/refused` for a machine that is already exporting or ended."
  [machine]
  (cond
    (contains? #{:exporting :ended} (vm/gate-mode machine))
    (refused :not-running)

    :else
    (or (some (fn [[path m]]
                (when-some [h (refusal-at m)]
                  (non-portable (assoc h :yin.k/path path))))
              (machines [] machine))
        {:status :ok
         :machine (cond-> (assoc machine :yin.k/gate :exporting :wait-set [] :ready-queue [])
                    (contains? machine :parked) (assoc :parked {}))
         :record {:wait-set (vec (:wait-set machine))
                  :ready-queue (:ready-queue machine)
                  :parked (:parked machine)
                  :gate (vm/gate-mode machine)
                  :served {}}})))


(defn- reinstated
  "`machine`, an exporting machine, carrying `record`'s waits and
   parked records again: the value the lift reads."
  [machine record]
  (-> (reduce-kv (fn [restored module-name child-record]
                   (update-in restored [:installs module-name :vm]
                              reinstated child-record))
                 machine (or (:children record) {}))
      (assoc :wait-set (:wait-set record) :ready-queue (:ready-queue record))
      (update :parked merge (:parked record))))


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(defn- check-header!
  "A header is nil (the fork lift) or a map carrying the arbitration,
   the counter and the enrolled set; anything else is a defect of the
   caller, not a refusal.  The lift judges the rest."
  [header]
  (when (some? header)
    (when-not (and (map? header)
                   (contains? header :yin.k/arbitration)
                   (contains? header :yin.k/next-op-seq)
                   (set? (:yin.k/enrolled header)))
      (throw (ex-info "Prepare of a malformed header"
                      {:yin.k/hint :malformed-header})))))


(defn- lift
  "rows/lift-task of the record over `machine`, under the record's header
   and source index, every stream asked of `keyed`: `{:status :ok :wire
   w}` or the lift's refusal as data. A throw without the refusal
   vocabulary is a defect and propagates."
  [machine record keyed]
  (try
    {:status :ok
     :wire (rows/lift-task (reinstated machine record) nil
                           {:header (:header record)
                            :source-index (:source-index record)
                            :serve-keyed keyed})}
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (let [d (ex-data e)]
        (if (and (map? d) (contains? d :yin.k/status)) d (throw e))))))


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
   still identify.  The row lift serves while it walks, so a refusal it
   decides after the walk (`:op-seq-exhausted`, `:unprotected-pending`,
   an op id on an unenrolled stream, the custody grammar) still finds
   the streams it reached served and retained (phase-7a D4).

   The served table is keyed by `[task-path resource-id]` and holds the
   descriptor `serve!` answered. This table is plain data, but the live
   waits can contain authentic values: `freeze` is the persistence seam.
   A handle is asked at most once, across the calls of one retained
   record, even when two resources hold it.  `header` is nil for the
   fork lift, or the exclusive custody header (`rows/lift-task`'s
   `:header`), kept in the record as `:header`; `source-index` is the
   composition's `{A #{tree-address}}` (continuation §6.1), kept as
   `:source-index`.  Prepare mints nothing: the occurrence was minted by
   the driver before it was called."
  [machine record serve! header source-index]
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
        record (assoc record :header header :source-index source-index)
        r (lift machine record once)]
    (if (= :ok (:status r))
      {:status :ok :record (assoc record :served @table)}
      (assoc r :record (assoc record :served @table)))))


(defn- kind-of
  "The lifted task's kind, by the lift's own rule."
  [m]
  (let [value (:value m)]
    (cond
      (some? (:control m)) :running
      (and (map? value) (= :parked-continuation (:type value))
           (contains? (:parked m) (:id value))) :parked
      (or (seq (:wait-set m)) (seq (:ready-queue m))) :blocked
      :else :halted)))


(defn encode
  "The lift of the prepared `record` over `machine`, from a `serve-keyed`
   that only reads the record, under the header and source index the
   record retains: `{:status :ok :bytes b :address a :root B :kind k}`,
   `b` the pack's canonical bytes, `a` the pack address (BLAKE3 of `b`,
   the storage key), `B` the root id inside it; or the lift's refusal as
   data.  It allocates and publishes nothing, and one record gives equal
   bytes every time.  A stream the record has no answer for is a defect
   of the caller (an unprepared record), not a refusal."
  [machine record]
  (let [served (:served record)
        r (lift machine record
                (fn [k _h]
                  (if (contains? served k)
                    (get served k)
                    (throw (ex-info "Encode of an unprepared record"
                                    {:yin.k/hint :unprepared-stream})))))]
    (if (= :ok (:status r))
      (let [wire (:wire r)]
        {:status :ok
         :bytes (cbor/encode wire)
         :address (rows/pack-address wire)
         :root (:yin.k/root wire)
         :kind (kind-of (reinstated machine record))})
      r)))


;; =============================================================================
;; Freeze: the export-recovery object, version 2
;; =============================================================================

(defn- recovery-address
  [bytes]
  (let [algorithm jing/default-hash-algorithm]
    (keyword "segment"
             (str (get-in jing/registry [algorithm :address-id]) "-"
                  (jing/digest-bytes algorithm bytes)))))


(defn- recovery-metadata
  "The holder-local engine state of one task the pack does not carry:
   the gate to return to, each wait's issue stamp in wait order, the
   ready count, issued and origin counters, and each install child's, recursively."
  [machine record]
  {:gate (:gate record)
   :origins (:origins machine)
   :issues (mapv :yin.k/issue (:wait-set record))
   :ready-count (count (:ready-queue record))
   :issued (:yin.k/issued machine)
   :children (into {}
                   (map (fn [[module-name install]]
                          [module-name
                           (recovery-metadata
                             (:vm install)
                             (or (get-in record [:children module-name])
                                 {:gate (vm/gate-mode (:vm install))
                                  :wait-set (:wait-set (:vm install))
                                  :ready-queue (:ready-queue (:vm install))}))]))
                   (:installs machine))})


(defn freeze
  "The export-recovery object of the prepared record, version 2:

     {:yin.k/export-recovery true
      :yin.k/version         2
      :yin.k/pack-address    a        ; the published pack's address
      :yin.k/root            B        ; the root id inside it
      :yin.k/pack-bytes      base64   ; the pack, byte for byte
      :yin.k/header          h        ; nil for a fork; the enrolled set
                                      ; stays here, never in the pack
      :yin.k/state           {:gate :origins :issued :issues :children}}

   Answers `{:status :ok :bytes rb :address ra :pack-bytes b
   :pack-address a :root B :kind k}`, `rb` the recovery object's
   canonical bytes and `ra` their BLAKE3 address, or the encode's
   refusal.  Both objects must be stored durably before a fence is
   recorded."
  [machine prepared-record]
  (let [e (encode machine prepared-record)]
    (if (not= :ok (:status e))
      e
      (let [recovery {:yin.k/export-recovery true
                      :yin.k/version 2
                      :yin.k/pack-address (:address e)
                      :yin.k/root (:root e)
                      :yin.k/pack-bytes (jing/bytes->base64 (:bytes e))
                      :yin.k/header (:header prepared-record)
                      :yin.k/state (recovery-metadata machine prepared-record)}
            bytes (cbor/encode recovery)]
        {:status :ok :bytes bytes :address (recovery-address bytes)
         :pack-bytes (:bytes e) :pack-address (:address e)
         :root (:root e) :kind (:kind e)}))))


;; =============================================================================
;; Rehydrate: the fenced recovery
;; =============================================================================

(defn- invalid!
  [data]
  (throw (ex-info "Invalid export recovery" (merge {:yin.k/status :yin.k/undecodable} data))))


(defn- header-agrees?
  "The recovery's header is the custody the pack's root slot carries:
   nil exactly for a fork; otherwise the slot's keys by kind (running,
   blocked or parked: occurrence, arbitration, counter and origin; halted: origin),
   a nil header value being an absent key, as the lift writes it."
  [header baseline]
  (if (nil? header)
    (true? (:yin.k/fork baseline))
    (let [ks (if (= :halted (:yin.k/kind baseline))
               [:yin.k/origin]
               [:yin.k/occurrence :yin.k/arbitration :yin.k/next-op-seq :yin.k/origin])]
      (and (not (:yin.k/fork baseline))
           (= (into {} (filter (comp some? val)) (select-keys header ks))
              (select-keys baseline ks))))))


(defn- counter?
  [x]
  (or (nil? x) (and (integer? x) (<= 0 x))))


(defn- valid-state?
  "The recovery state fits the decoded task: one issue stamp per carried
   wait, the ready count, and one child state per install, recursively."
  [task state]
  (let [ss (:slots task)
        installs (nth ss 12)
        waits (nth ss 13)]
    (and (map? state)
         (contains? #{nil :running :exporting :ended} (:gate state))
         (vector? (:issues state))
         (= (count (:issues state)) (count waits))
         (= (:ready-count state) (count (nth ss 18)))
         (every? counter? (:issues state))
         (counter? (:issued state))
         (counter? (:origins state))
         (map? (:children state))
         (= (set (keys (:children state))) (into #{} (map #(first (:slots %))) installs))
         (every? (fn [inst]
                   (let [[mn _ _ _ child] (:slots inst)]
                     (valid-state? child (get-in state [:children mn]))))
                 installs))))


(defn- carried-trees
  "{A #{tree}} every task of the decoded tree carries: the composition's
   index as the prepare that published the pack saw it, restricted to the
   carried segments, which is all a relift of the same tree reads."
  [task]
  (let [ss (:slots task)]
    (reduce (fn [acc inst] (merge acc (carried-trees (nth (:slots inst) 4))))
            (or (:yin.k/trees (nth ss 10)) {})
            (nth ss 12))))


(defn- extract-recovered
  "The fenced machine and its export record from the lower's fenced
   restore of one task: the waits and parked records the lower kept out
   of the scheduler move into the record, the waits restamped with the
   recovered issues; the holder-local counters come from `state`."
  [machine state]
  (let [children (into {}
                       (map (fn [[module-name install]]
                              [module-name
                               (extract-recovered (:vm install)
                                                  (get-in state [:children module-name]))]))
                       (:installs machine))
        waits (mapv (fn [entry issue]
                      (if (some? issue)
                        (assoc entry :yin.k/issue issue)
                        (dissoc entry :yin.k/issue)))
                    (:yin.k/recovery-waits machine)
                    (:issues state))
        record {:wait-set waits
                :ready-queue (vec (:yin.k/recovery-ready machine))
                :parked (or (:yin.k/recovery-parked machine) {})
                :recovered? true
                :gate (:gate state)
                :served {}
                :children (into {} (map (fn [[module-name recovered]]
                                          [module-name (:record recovered)]))
                                children)}
        fenced (reduce-kv (fn [parent module-name recovered]
                            (assoc-in parent [:installs module-name :vm]
                                      (:machine recovered)))
                          (cond-> (-> machine
                                      (dissoc :yin.k/recovery-waits :yin.k/recovery-ready :yin.k/recovery-parked
                                              :yin.k/custody :yin.k/closes :yin.k/issued :origins)
                                      (assoc :wait-set [] :ready-queue [] :parked {} :yin.k/gate :exporting))
                            (some? (:origins state)) (assoc :origins (:origins state))
                            (some? (:issued state)) (assoc :yin.k/issued (:issued state)))
                          children)]
    {:machine fenced :record record}))


(defn rehydrate-fenced
  "Restore a version-2 export-recovery object into fresh receiver-owned
   values (UCF 7.7.4).  Before any attachment: the object's tag and
   version (`:yin.k/profile-mismatch`), its bytes against `address`
   (`:yin.k/hash-mismatch`), the pack through the rows inspector (its
   address against its bytes, the carrier, the custody grammar), the pack
   root against the object's, the header against the root's custody
   slot, and the state against the carried tree (any of these
   `:yin.k/undecodable`, or the inspector's own refusal).  Then
   `rows/lower-task {:fenced true}` over `receiver`, `attach` once per
   stream identity: every task stays exporting, its waits and parked
   records outside the scheduler and in the record, its id counter
   exact; no program code runs, no stream is read, written, minted or
   closed, no install child initializes.  Never restores tenure,
   custody, scheduler waits or a runnable gate.  Last, the recovered
   record is prepared again from what was attached and must encode the
   published pack byte for byte, or the answer is `:yin.k/undecodable`
   with `:yin.k/reason :recovery-inconsistent`.  Answers `{:status :ok
   :machine m :record r}`, `r` marked `:recovered?` (abort refuses it).
   opts :yin.k/admitted-modules is the receiver composition's name/address map
   for carried install children, passed to the lower (default empty)."
  [receiver recovery-bytes {:keys [address attach] :as opts}]
  (try
    (let [recovery (cbor/decode recovery-bytes)]
      (cond
        (not (and (map? recovery)
                  (true? (:yin.k/export-recovery recovery))
                  (integer? (:yin.k/version recovery))
                  (= 2 (:yin.k/version recovery))))
        {:yin.k/status :yin.k/profile-mismatch}

        (not (jing/segment-bytes-match? address recovery-bytes))
        {:yin.k/status :yin.k/hash-mismatch}

        :else
        (let [pack-bytes (jing/base64->bytes (:yin.k/pack-bytes recovery))
              header (:yin.k/header recovery)
              state (:yin.k/state recovery)
              baseline (checkpoint/inspect (:yin.k/pack-address recovery) pack-bytes)
              _ (when (:yin.k/status baseline) (invalid! baseline))
              wire (cbor/decode pack-bytes)
              task (rows/decode wire)
              _ (when-not (= (:yin.k/root recovery) (:yin.k/root baseline))
                  (invalid! {:yin.k/reason :root}))
              _ (check-header! header)
              _ (when-not (header-agrees? header baseline)
                  (invalid! {:yin.k/reason :header}))
              _ (when-not (valid-state? task state)
                  (invalid! {:yin.k/reason :state}))
              attached (atom {})
              restored (rows/lower-task
                         receiver wire
                         (fn [descriptor]
                           (let [identity (:dao.stream/identity descriptor)]
                             (if (contains? @attached identity)
                               (:answer (get @attached identity))
                               (let [answer (attach descriptor)]
                                 (swap! attached assoc identity {:answer answer :descriptor descriptor})
                                 answer))))
                         (assoc (select-keys opts [:yin.k/admitted-modules]) :fenced true))
              {:keys [machine record]} (extract-recovered restored state)
              ;; each attached handle answers the descriptor it was served under
              by-handle (into {}
                              (map (fn [{:keys [answer descriptor]}]
                                     [(:dao.stream/handle answer)
                                      (select-keys descriptor [:dao.stream/identity
                                                               :dao.stream/channel])]))
                              (vals @attached))
              prepared (prepare machine record #(get by-handle %) header (carried-trees task))
              reproduced (when (= :ok (:status prepared))
                           (encode machine (:record prepared)))]
          (if (and (= :ok (:status reproduced))
                   (= (vec pack-bytes) (vec (:bytes reproduced))))
            {:status :ok :machine machine :record (assoc (:record prepared) :recovered? true)}
            {:yin.k/status :yin.k/undecodable
             :yin.k/reason :recovery-inconsistent}))))
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
   an authority's refusal of one attempt, leaves the source fenced.  A
   recovered record (`rehydrate-fenced`) is refused `:restarted`: a
   restart never hands execution back.

   A machine under custody (`:yin.k/custody`) is a holder exporting a
   successor: it may abort only with `tenure`, `{:now n :bound b :live
   true}`: `n` before `b` in one unit, and `:live` the ledger reader's
   evidence that this lease is the occurrence's active lease at its
   epoch.  A clock reading alone is not tenure.  After the lease has
   ended its old local machine is never restored.

   Answers `{:status :ok :machine m}` or `:yin.k/refused` data with
   reason `:restarted`, `:not-exporting`, `:tenure-ended` (at or past
   the bound), `:tenure-unconfirmed` (no ledger evidence) or
   `:offer-possibly-accepted`."
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
