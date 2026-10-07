p = 'src/cljc/yin/vm/ucf/handoff.cljc'
s = open(p).read()


def rep(old, new, tag):
    global s
    if old not in s:
        if new in s:
            print("already", tag)
            return
        raise Exception("missing " + tag)
    s = s.replace(old, new, 1)
    print("applied", tag)


rep('''         (when (= 1 (:yin.k/version body))
           (custody-inspect! bytes {:address address}))
         (when-not (jing/segment-bytes-match? address bytes)
           (refuse! :yin.k/hash-mismatch {}))
         (validate-body body)''', '''         (when (or (v2-body? body) (= 1 (:yin.k/version body)))
           (custody-inspect! bytes {:address address}))
         (when-not (jing/segment-bytes-match? address bytes)
           (refuse! :yin.k/hash-mismatch {}))
         (validate-body body)''', "recovery-inspect")
rep('''         (when-not (and (= ucf/contract-stamp (:yin.k/contract body))
                        (= (some? header) (= 1 (:yin.k/version body)))''', '''         (when-not (and (if (v2-body? body)
                          (some? (ucf/profile-engine (:yin.k/contract body)))
                          (= ucf/contract-stamp (:yin.k/contract body)))
                        (= (some? header)
                           (if (v2-body? body)
                             (boolean (some #(contains? body %)
                                            [:yin.k/policy :yin.k/occurrence
                                             :yin.k/arbitration :yin.k/origin
                                             :yin.k/next-op-seq]))
                             (= 1 (:yin.k/version body))))''', "recovery-header")
open(p, 'w').write(s)

# ------------------------------------------------------------ holder export
p = 'src/cljc/yin/vm/ucf/holder/export.cljc'
s = open(p).read()

rep('''(defn- reachable-parked
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
      {::ids (set (keys (:yin.k/parked (:yin.k/scheduler walked))))})))''', '''(defn- reachable-parked
  "The ids of the parked records the lift would carry, by the same
   census the lift runs (`handoff/reachable-parked`); a walk refusal
   answers data."
  [vm]
  (let [r (handoff/reachable-parked vm)]
    (if (contains? r :refusal)
      {::refusal (:refusal r)}
      {::ids (:ids r)})))''', "reachable-parked")

rep('''(defn enter
  "Enter exporting from `machine`.  Answers''', '''(declare enter*)


(defn enter
  "Enter exporting from `machine`.  Answers''', "enter-declare")
rep('''  [machine]
  (cond
    (contains? #{:exporting :ended} (vm/gate-mode machine))
    (refused :not-running)''', '''  ([machine] (enter* machine nil))
  ([machine opts] (enter* machine opts)))


(defn- enter*
  [machine opts]
  (cond
    (contains? #{:exporting :ended} (vm/gate-mode machine))
    (refused :not-running)''', "enter-arity")
rep('''                 :record {:wait-set (vec (:wait-set machine))
                          :parked taken
                          :gate (vm/gate-mode machine)
                          :served {}}}))))))''', '''                 :record (cond-> {:wait-set (vec (:wait-set machine))
                                  :parked taken
                                  :gate (vm/gate-mode machine)
                                  :served {}}
                           (some? (:version opts))
                           (assoc :version (:version opts)))}))))))''', "enter-record")
rep('''        r (handoff/export-task (reinstated machine record) nil
                               {:header header :serve-keyed once
                                :yin.vm.ucf.handoff/recovery true})]''', '''        r (handoff/export-task (reinstated machine record) nil
                               (cond-> {:header header :serve-keyed once
                                        :yin.vm.ucf.handoff/recovery true}
                                 (:version record)
                                 (assoc :version (:version record))))]''', "prepare-version")
rep('''    (handoff/export-task
      (reinstated machine record)
      nil
      {:header (:header record)
       :serve-keyed (fn [k _h]
                      (if (contains? served k)
                        (get served k)
                        (throw (ex-info "Encode of an unprepared record"
                                        {:yin.k/hint :unprepared-stream}))))})))''', '''    (handoff/export-task
      (reinstated machine record)
      nil
      (cond-> {:header (:header record)
               :serve-keyed (fn [k _h]
                              (if (contains? served k)
                                (get served k)
                                (throw (ex-info "Encode of an unprepared record"
                                                {:yin.k/hint
                                                 :unprepared-stream}))))}
        (:version record) (assoc :version (:version record))))))''', "encode-version")
rep('''                   (handoff/export-task
                     (reinstated machine prepared-record) nil
                     {:header (:header prepared-record)
                      :yin.vm.ucf.handoff/recovery true
                      :serve-keyed (fn [key _handle] (get (:served prepared-record) key))}))''', '''                   (handoff/export-task
                     (reinstated machine prepared-record) nil
                     (cond-> {:header (:header prepared-record)
                              :yin.vm.ucf.handoff/recovery true
                              :serve-keyed (fn [key _handle]
                                             (get (:served prepared-record) key))}
                       (:version prepared-record)
                       (assoc :version (:version prepared-record)))))''', "freeze-version")
rep('''(defn- extract-recovered
  [machine metadata]''', '''(defn- extract-recovered
  [machine metadata version]''', "extract-sig")
rep('''(extract-recovered
                                 (:vm install)
                                 (get-in metadata [:children module-name]))]))''', '''(extract-recovered
                                 (:vm install)
                                 (get-in metadata [:children module-name])
                                 version)]))''', "extract-child")
rep('''        record {:wait-set waits :parked (:parked machine) :recovered? true
                :gate (:gate metadata) :served {}''', '''        record {:wait-set waits :parked (:parked machine) :recovered? true
                :gate (:gate metadata) :served {}''', "noop")
rep('''            (let [{:keys [machine record]} (extract-recovered
                                             (:vm restored) (:yin.k/state recovery))''', '''            (let [version (when (= 2 (:yin.k/version (:body inspected)))
                            2)
                  {:keys [machine record]} (extract-recovered
                                             (:vm restored)
                                             (:yin.k/state recovery)
                                             version)''', "rehydrate-extract")
rep('''                  prepared (prepare machine record by-handle (:yin.k/header recovery))''', '''                  prepared (prepare machine
                                    (cond-> record
                                      (some? version) (assoc :version version))
                                    by-handle (:yin.k/header recovery))''', "rehydrate-prepare")
open(p, 'w').write(s)
