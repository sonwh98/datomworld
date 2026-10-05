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
  (-> machine
      (assoc :wait-set (:wait-set record))
      (update :parked merge (:parked record))))


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(defn prepare
  "Prepare `record`: call `serve!` once per stream of the exporting
   `machine`, install children included, and answer
   `{:status :ok :record r}` with every answer retained in `r`, or the
   data outcome of the lift's refusal with `:record` added, carrying
   what was served before the refusal so a retry never serves a stream
   twice.  The served table is keyed by the stream handle, which is
   what the lift hands `serve!`."
  [machine record serve!]
  (let [table (atom (:served record))
        once (fn [h]
               (let [t @table]
                 (if (contains? t h)
                   (get t h)
                   (let [served (serve! h)]
                     ;; a refusal is not an answer: a retry asks again
                     (when (some? served) (swap! table assoc h served))
                     served))))
        r (handoff/export-task (reinstated machine record) once)]
    (if (= :ok (:status r))
      {:status :ok :record (assoc record :served @table)}
      (assoc r :record (assoc record :served @table)))))


(defn encode
  "The lift of the prepared `record` over `machine`: the answer of
   `handoff/export-task`, from a `serve!` that only reads the record.
   It allocates and publishes nothing.  A stream the record has no
   answer for is a defect of the caller (an unprepared record), not a
   refusal."
  [machine record]
  (let [served (:served record)]
    (handoff/export-task
      (reinstated machine record)
      (fn [h]
        (if (contains? served h)
          (get served h)
          (throw (ex-info "Encode of an unprepared record"
                          {:yin.k/hint :unprepared-stream})))))))


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
