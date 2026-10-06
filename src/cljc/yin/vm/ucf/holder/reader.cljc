(ns yin.vm.ucf.holder.reader
  "The recorded reader of M-next D slice D12 (UCF 7.7.5, 7.7.7, 7.7.8;
   r3 1.4; linker-dht 14.2.2): the holder side of every observation a
task under custody makes.

  An observation is one of: a `:next` read through a cursor cell, a
`:poll` read (the engine's `:observe` entry), the mint of an unminted
cursor cell (`:cursor`, origin `:dao.stream/oldest`), the mint of a
link response cursor (`:cursor`, origin `:dao.stream/newest`), one read
of an FFI response cell (`:ffi-result`), or one read of a link response
stream (`:link-result`).  The source of every observation is portable
data inside `:yin.k/name`, `{:yin.k/kind :yin.k/read |
:yin.k/ffi-result | :yin.k/link-result, :yin.k/name {:yin.k/op ...
:yin.k/task path ...}}`, where `path` is `[]` for the root and the
install names down to a child.  A read source carries the stream
identity and the portable position read at; a `:cursor` source carries
the requested origin instead -- never the resulting position, so
replay never mints a live cursor to discover which source to match.  A
`:ffi-result` source carries the call id, a `:link-result` the link id.
No cell id, host cursor or sealed reference appears in a source or an
observed value.

  Live (`step`): observe the handle through the composition-supplied
observer, hold the exact observation on the entry or cell under
`:yin.k/held`, and send one `:yin.k/input` request through the
composition-supplied appender.  The four retained states ride that map:
`:observed` (1, the request never left, a `full` inbound), `:requested`
(2, acceptance unknown, a `transport-error` included), `:acknowledged`
(3, the answer arrived under a closed gate and can no longer apply),
and applied (4, the map is gone and the input sequence has advanced).
One observation is in flight at a time: while one is held no other is
observed, so the first acknowledged read advances a shared cell before
the next entry's source is computed.  The live scan advances
deterministically over DISTINCT source groups: each applied
observation records its source as `:yin.k/scan` in the input state,
and the next step observes from the first candidate of the next
distinct source group -- candidates sharing a source (two waiters on
one cursor, two unminted cells on one stream) are one group, so a
blocked alias pair cannot recapture the scan, a blocked reader on an
empty stream cannot starve a later stream, children included, and
within a group the first candidate in canonical order is always the
one taken, exactly the one replay's first matching source selects.  No
wait entry is ever reordered.
`settle` applies an acknowledgment only after `:recorded` or
`:replayed`, through the engine's public applies; `:input-conflict`
and `:stale` end the run, and an input conflict is never translated to
`:intent-conflict` and quarantines nothing -- an input is evidence,
not an effect.

  A failed mint is one disposition, live and replay alike: a `:cursor`
observation whose outcome is not a successful position -- the failure
family `:closed`, `:refused`, `:invalid-anchor`, `:transport-error`,
or a malformed success the stream contract's own table rejects -- is
durably recorded and acknowledged, installs nothing, and leaves the
cell with its `:yin.k/unminted` marker and the link entry with no
`:cursor`, so neither becomes readable nor sendable; the candidate is
simply observed again on a later step, as a fresh observation at a new
k.

  Replay (`replay`): while k is below the custody prefix's frontier
nothing is observed live; record k is applied to the selected entry --
candidates ordered by task path, then wait-set order, mints before
reads (unminted cells by creation `:seq`), the first whose source
matches -- and the recorded position of a `:cursor` record is applied
with `apply-mint` or `apply-link-cursor` without minting.  A record
that matches nothing ends the run as divergence only when the whole
task tree is quiet: every ready queue empty, no fenced write awaiting
its outcome, no held observation, and no control request outstanding
(the one fact the machine cannot see, supplied by the driver).  A read
on a stream with a close pending defers to the step after the driver's
writer resolves the close, so a reader sees `end` or data as it would
ungated.

  A custody map whose prefix carries no frontier -- absent, or bearing
a status -- is missing evidence, never an empty prefix: both halves
fail closed and answer `:yin.k/unsatisfied`, `:no-prefix`, observing
nothing.

  No transport is known here: the input appender and the handle
observer are composition-supplied functions, mirroring the writer's
seam, and the only handle the reader names is the machine's own
resource."
  (:require [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.custody :as custody]))


;; =============================================================================
;; The task tree
;; =============================================================================

(defn- walk
  "The `{:ap assoc-path :tp task-path :m machine}` of the root and,
   recursively, each install child, children in install-name order:
   `ap` is the assoc-in path of the child inside the root, `tp` the
   install names a source carries."
  [root]
  (letfn [(go
            [ap tp m]
            (concat [{:ap ap :tp tp :m m}]
                    (mapcat (fn [name]
                              (when-some [child (:vm (get (:installs m) name))]
                                (go (into ap [:installs name :vm])
                                    (into tp [name])
                                    child)))
                            (sort-by str (keys (:installs m))))))]
    (go [] [] root)))


(defn- ended
  "`machine` gated `:ended` at the root and at every install child: the
   root owns the mode, and nothing applies a late result after it."
  [root]
  (reduce (fn [m {:keys [ap]}] (assoc-in m (conj ap :yin.k/gate) :ended))
          (assoc root :yin.k/gate :ended)
          (walk root)))


(defn- swap-entry
  "`root` with the wait entry equal to `entry` (at `ap`) replaced by
   `(f entry)`.  An entry the wait set no longer holds is a torn walk, a
   defect of the caller, not a refusal."
  [root ap entry f]
  (let [ws (get-in root (into ap [:wait-set]))
        i (first (keep-indexed (fn [n e] (when (= entry e) n)) ws))]
    (when (nil? i)
      (throw (ex-info "No such wait entry" {:yin.k/hint :torn-walk})))
    (assoc-in root (into ap [:wait-set i]) (f (nth ws i)))))


;; =============================================================================
;; The sources (UCF 7.7.7's request grammar, r3 1.4)
;; =============================================================================

(defn- read-source
  [op task stream position]
  {:yin.k/kind :yin.k/read
   :yin.k/name {:yin.k/op op
                :yin.k/task task
                :yin.k/stream stream
                :yin.k/position position}})


(defn- cursor-source
  [task stream origin]
  {:yin.k/kind :yin.k/read
   :yin.k/name {:yin.k/op :cursor
                :yin.k/task task
                :yin.k/stream stream
                :yin.k/origin origin}})


(defn- ffi-source
  [task call-id]
  {:yin.k/kind :yin.k/ffi-result
   :yin.k/name {:yin.k/op :ffi-result
                :yin.k/task task
                :yin.k/call-id call-id}})


(defn- link-source
  [task link-id]
  {:yin.k/kind :yin.k/link-result
   :yin.k/name {:yin.k/op :link-result
                :yin.k/task task
                :yin.k/link-id link-id}})


(defn- stream-identity
  "The portable identity of the stream at resource `id` of `machine`."
  [machine id]
  (when-some [handle (get (:resources machine) id)]
    (:dao.stream/identity (stream/descriptor handle))))


(defn- cell-of
  "The cursor cell a wait entry reads through, and its stream identity."
  [machine entry]
  (when (map? (:cursor-ref entry))
    (let [cell (get (:resources machine) (:id (:cursor-ref entry)))]
      (when (and (map? cell) (nil? (:yin.k/unminted cell)) (:cursor cell))
        {:cell cell :stream (stream-identity machine (:stream-id cell))}))))


;; =============================================================================
;; The candidates, in the selection order
;; =============================================================================

(defn- cell-candidates
  "The mint candidates of `machine`: every unminted cursor cell in
   creation `:seq` order (D5's ruling -- mints precede reads on them)."
  [ap tp machine]
  (into []
        (keep (fn [[cid cell]]
                (when (and (map? (:yin.k/unminted cell))
                           (nil? (:yin.k/held cell)))
                  (when-some [sid (stream-identity machine (:stream-id cell))]
                    {:kind :mint
                     :ap ap
                     :tp tp
                     :cell-id cid
                     :origin (:origin (:yin.k/unminted cell))
                     :source (cursor-source tp sid
                                            (:origin (:yin.k/unminted cell)))}))))
        (sort-by (fn [[_cell-id cell]] (:seq (:yin.k/unminted cell)))
                 (:resources machine))))


(defn- close-pending?
  "True when `machine` holds a pending close on the stream of cursor
   `cell` (the D5 close ruling's D12 half): a read on a stream follows
   the closes pending on it in the same step, so the driver's writer
   resolves the close first and the reader observes afterwards -- a
   reader then sees `end` or data from the handle, as it would
   ungated.  FFI and link streams take no program close, so only cell
   reads defer."
  [machine cell]
  (some #(= (:stream-id cell) (:stream-id %)) (or (:yin.k/closes machine) [])))


(defn- entry-candidate
  "The observation candidate of wait entry `e`, or nil when the driver
   owes it nothing."
  [ap tp machine e]
  (when (nil? (:yin.k/held e))
    (case (:reason e)
      :observe
      (when (= :poll (:op e))
        (when-some [{:keys [cell stream]} (cell-of machine e)]
          (when-not (close-pending? machine cell)
            {:kind :poll :ap ap :tp tp :entry e
             :source (read-source :poll tp stream (:cursor cell))})))
      :next
      (if-some [call (ffi/response-call-id e)]
        {:kind :ffi :ap ap :tp tp :entry e :call-id call
         :source (ffi-source tp call)}
        (when-some [{:keys [cell stream]} (cell-of machine e)]
          (when-not (close-pending? machine cell)
            {:kind :next :ap ap :tp tp :entry e
             :source (read-source :next tp stream (:cursor cell))})))
      :link-request
      (when (not (contains? e :cursor))
        (when-some [sid (stream-identity machine module/link-response-resource)]
          {:kind :link-mint :ap ap :tp tp :entry e
           :source (cursor-source tp sid :dao.stream/newest)}))
      :link-response
      {:kind :link-read :ap ap :tp tp :entry e :link-id (:link-id e)
       :source (link-source tp (:link-id e))}
      nil)))


(defn candidates
  "The observation candidates of `root`, in the one selection order live
   and replay share: task path (the root, then children in install-name
   order); within a machine, unminted cursor cells in creation `:seq`
   order, then the wait set in order.  A held entry or cell is not a
   candidate, a read through an unminted cell waits for that cell's
   mint, and a read on a stream with a close pending defers to the step
   after the driver's writer resolves it."
  [root]
  (into []
        (mapcat (fn [{:keys [ap tp m]}]
                  (concat (cell-candidates ap tp m)
                          (keep #(entry-candidate ap tp m %) (:wait-set m)))))
        (walk root)))


;; =============================================================================
;; Observe and apply, per kind
;; =============================================================================

(defn- observe-candidate
  "The live observation of `candidate`, through `observe!` over the
   machine's own handle: `(observe! handle :next cursor)` for a read,
   `(observe! handle :cursor origin)` for a mint."
  [root candidate observe!]
  (let [m (get-in root (:ap candidate))
        res (:resources m)
        read-cell (fn [entry]
                    (let [cell (:cell (cell-of m entry))]
                      (observe! (get res (:stream-id cell)) :next (:cursor cell))))]
    (case (:kind candidate)
      :mint (observe! (get res (:stream-id (get res (:cell-id candidate))))
                      :cursor (:origin candidate))
      :link-mint (observe! (get res module/link-response-resource)
                           :cursor :dao.stream/newest)
      (:poll :next) (read-cell (:entry candidate))
      :ffi (let [cell (get res (:id (:cursor-ref (:entry candidate))))]
             (observe! (get res (:stream-id cell)) :next (:cursor cell)))
      :link-read (observe! (get res module/link-response-resource)
                           :next (:cursor (:entry candidate))))))


(defn- mint-position
  "The position of a successful cursor mint in `observed`, or nil when
   `observed` is not one: the `:cursor` operation's failure family --
   `:closed`, `:refused`, `:invalid-anchor`, `:transport-error` -- or a
   malformed success the contract's own table rejects (an `:ok` without
   its required position).  A failed mint applies nothing: the cell
   keeps its `:yin.k/unminted` marker and the link entry keeps no
   `:cursor`, so neither becomes readable nor sendable, and the
   observation is still durably recorded and acknowledged -- the next
   observation of the same candidate is a fresh one at a new k."
  [observed]
  (when (and (stream/valid-outcome? :cursor observed)
             (= :dao.stream/ok (:dao.stream/outcome observed)))
    (:dao.stream/cursor observed)))


(defn- apply-candidate
  "`root` with `candidate`'s observation `observed` applied, through the
   engine's public applies, and the live scan's resume point advanced
   past the candidate's source.  The mint positions come out of the
   observed outcome's own cursor, never a live one; a mint outcome with
   no successful position installs nothing, leaving cell or link entry
   exactly as it was."
  [root candidate observed]
  (let [ap (:ap candidate)
        m (get-in root ap)
        applied (case (:kind candidate)
                  :mint (if-some [position (mint-position observed)]
                          (engine/apply-mint m (:cell-id candidate) position)
                          m)
                  :link-mint (if-some [position (mint-position observed)]
                               (engine/apply-link-cursor m
                                                         (:link-id (:entry candidate))
                                                         position)
                               m)
                  :next (engine/apply-next m (:entry candidate) observed)
                  :poll (engine/apply-observation m (:entry candidate) observed)
                  :ffi (engine/apply-ffi-read m (:call-id candidate) observed)
                  :link-read (engine/apply-link-read m (:link-id candidate)
                                                     observed))
        root' (if (seq ap) (assoc-in root ap applied) applied)]
    (assoc-in root' [:yin.k/custody :input :yin.k/scan] (:source candidate))))


;; =============================================================================
;; The held observation and its four states
;; =============================================================================

(defn- held-map
  [k source observed state]
  {:yin.k/input-seq k
   :yin.k/source source
   :yin.k/observed observed
   :yin.k/state state})


(defn- hold
  "`root` with `candidate` holding its observation in `state` (1 or 2):
   on the entry, or on the cell for a mint (beside `:yin.k/unminted`)."
  [root candidate held]
  (if (= :mint (:kind candidate))
    (assoc-in root (into (:ap candidate)
                         [:resources (:cell-id candidate) :yin.k/held])
              held)
    (swap-entry root (:ap candidate) (:entry candidate)
                #(assoc % :yin.k/held held))))


(defn- rewrite-held
  "`root` with every carrier of the held observation `held` (by value)
   replaced by `(f carrier)` -- cell, wait entries and ready entries
   alike, for only one observation is ever held."
  [root held f]
  (reduce (fn [r {:keys [ap]}]
            (let [rewrite
                  (fn [m]
                    (let [g (fn [x] (if (= held (:yin.k/held x)) (f x) x))]
                      (-> m
                          (update :resources
                                  (fn [res]
                                    (into {} (map (fn [[k v]] [k (g v)]))
                                          (or res {}))))
                          (update :wait-set #(mapv g (or % [])))
                          (update :ready-queue #(mapv g (or % []))))))]
              (if (seq ap) (update-in r ap rewrite) (rewrite r))))
          root
          (walk root)))


(defn- unhold
  "State 4: the held observation is gone from every cell, wait set and
   ready queue, and the input sequence has advanced past its k."
  [root held k]
  (-> root
      (rewrite-held held #(dissoc % :yin.k/held))
      (assoc-in [:yin.k/custody :input :next] (inc k))))


(defn- kind-of-entry
  [e]
  (case (:reason e)
    :observe :poll
    :next (if (ffi/response-call-id e) :ffi :next)
    :link-request :link-mint
    :link-response :link-read
    nil))


(defn- held-location
  "The one held observation's `[ap candidate held]`, or nil.  The
   candidate is rebuilt from where the held sits -- kind, entry or cell,
   and the ids its apply needs -- for the source and observed value
   travel inside the held map itself."
  [root]
  (some (fn [{:keys [ap tp m]}]
          (or (some (fn [[cid cell]]
                      (when-some [held (:yin.k/held cell)]
                        [ap {:kind :mint :ap ap :tp tp :cell-id cid
                             :origin (:origin (:yin.k/unminted cell))}
                         held]))
                    (:resources m))
              (some (fn [e]
                      (when-some [held (:yin.k/held e)]
                        (when-some [kind (kind-of-entry e)]
                          [ap {:kind kind
                               :ap ap
                               :tp tp
                               :entry e
                               :call-id (ffi/response-call-id e)
                               :link-id (:link-id e)}
                           held])))
                    (:wait-set m))))
        (walk root)))


;; =============================================================================
;; The input state and the request
;; =============================================================================

(defn- next-k
  "The input sequence the next observation takes."
  [custody]
  (or (get-in custody [:input :next]) 0))


(defn- frontier
  "The frontier of the custody prefix, or nil when the prefix is absent
   or carries a status: missing evidence is never frontier zero."
  [custody]
  (let [p (get-in custody [:input :prefix])]
    (when (and (map? p) (not (contains? p :yin.k/status)))
      (let [n (:yin.k/frontier p)]
        (when (custody/exact? n) n)))))


(defn- unsatisfied
  []
  {:yin.k/status :yin.k/unsatisfied :yin.k/reason :no-prefix})


(defn input-request-id
  "The tagged vector correlation handle of the input request for
   sequence `k`: the request kind, the lease, the k.  Canonical data, so
   a resend is the identical request on every host; it names no dedup --
   the authority's (occurrence, k, content) comparison is, and a
   regrant changes the lease, so the id."
  [custody k]
  [:yin.k/input (:dao.lease/lease custody) k])


(defn input-request
  "The `:yin.k/input` front request of the observation `source`/`observed`
   at sequence `k` under `custody`, in the front's closed request shape."
  [custody k source observed]
  {:yin.k/request :yin.k/input
   :yin.k/request-id (input-request-id custody k)
   :yin.k/input (input/request (:yin.k/occurrence custody)
                               (:dao.lease/lease custody)
                               (:yin.k/epoch custody)
                               k
                               source
                               observed)})


(defn- send-held
  "(Re)send the request of the held observation `held` through
   `append-input!`, moving it to state 2 when the request may have
   left, and answering `[machine' appended]`."
  [machine custody held append-input!]
  (let [k (:yin.k/input-seq held)
        r (input-request custody k (:yin.k/source held) (:yin.k/observed held))
        a (append-input! r)
        state (if (= :dao.stream/full (:dao.stream/outcome a))
                :observed :requested)
        m' (rewrite-held machine held
                         #(assoc-in % [:yin.k/held :yin.k/state] state))]
    [m' {:request r :append a}]))


;; =============================================================================
;; Step: the live half
;; =============================================================================

(defn- distinct-sources
  "The distinct candidate sources in canonical candidate order,
   compared by content as records are.  Candidates sharing a source --
   two waiters on one cursor cell, two unminted cells on one stream --
   form one scan group."
  [candidates]
  (reduce (fn [acc c]
            (if (some #(cbor/content= (:source c) %) acc)
              acc
              (conj acc (:source c))))
          []
          candidates))


(defn- scan-start
  "The candidate index the live half observes from: the first candidate
   of the DISTINCT source group after the marker's, in canonical
   candidate order, wrapping.  Advancing by group, not by candidate, is
   what keeps two aliases on one cursor from recapturing the scan -- an
   acknowledged blocked read leaves both aliases with the same source,
   and one candidate past the first would re-enter the same group, live
   and replay then disagreeing on which continuation a later success
   wakes.  Within the selected group the first candidate in canonical
   order is always the one taken, which is exactly the candidate
   replay's first matching source selects for a record of that group's
   source.  0 when the marker is absent or its group has left.  No
   wait entry is ever reordered."
  [candidates marker]
  (if (nil? marker)
    0
    (let [order (distinct-sources candidates)
          n (count order)]
      (if (zero? n)
        0
        (let [i (first (keep-indexed (fn [j s] (when (cbor/content= marker s) j))
                                     order))]
          (if (nil? i)
            0
            (let [next (nth order (mod (inc i) n))]
              (first (keep-indexed (fn [j c]
                                     (when (cbor/content= next (:source c)) j))
                                   candidates)))))))))


(defn step
  "One live reader step over `machine`, a task under custody.

   `append-input!` is the composition-supplied appender of the holder's
   inbound stream, `(fn [request] append-outcome)`.  `observe!` is the
   composition-supplied handle observer, `(fn [handle op arg]
   outcome)` with op `:next` (arg the cursor) or `:cursor` (arg the
   origin anchor), over the machine's own resource handles.

   One observation is in flight at a time.  A held observation (states
   1 or 2) is resent as the identical request and nothing new is
   observed; otherwise the scan's candidate -- the first, or the one
   after the last applied observation's source -- is observed once,
   held on its entry or cell, and its request sent.  Below the
   frontier nothing is observed live (`::replay-pending`); a custody
   prefix carrying no frontier fails closed (`:unsatisfied`).

   Answers `{:machine m :appended [{:request r :append a}] :observation
   {...} | nil ::replay-pending true | :unsatisfied {...} | nil}`."
  [machine append-input! observe!]
  (when-not (map? (:yin.k/custody machine))
    (throw (ex-info "Reader step over a task that holds no custody"
                    {:yin.k/hint :no-custody})))
  (if (not= :running (vm/gate-mode machine))
    {:machine machine :appended [] :observation nil}
    (if (nil? (frontier (:yin.k/custody machine)))
      {:machine machine :appended [] :observation nil :unsatisfied (unsatisfied)}
      (let [custody (:yin.k/custody machine)]
        (if (< (next-k custody) (frontier custody))
          {:machine machine :appended [] :observation nil ::replay-pending true}
          (if-some [[_ap _candidate held] (held-location machine)]
            (let [[m' appended] (send-held machine custody held append-input!)]
              {:machine m' :appended [appended] :observation nil})
            (let [cs (candidates machine)
                  candidate (get cs (scan-start cs
                                                (get-in custody
                                                        [:input :yin.k/scan])))]
              (if (nil? candidate)
                {:machine machine :appended [] :observation nil}
                (let [observed (observe-candidate machine candidate observe!)
                      k (next-k (:yin.k/custody machine))
                      held (held-map k (:source candidate) observed :observed)
                      m1 (hold machine candidate held)
                      ;; the held map rides the machine now; rebuild the
                      ;; request from it so a resend is this one exactly
                      [_ap _c held'] (held-location m1)
                      [m' appended] (send-held m1 custody held' append-input!)]
                  {:machine m'
                   :appended [appended]
                   :observation {:task (:tp candidate)
                                 :kind (:kind candidate)
                                 :source (:source candidate)
                                 :observed observed}})))))))))


;; =============================================================================
;; Settle: one acknowledgment record, matched and applied
;; =============================================================================

(defn- retained
  [machine reason]
  {:machine machine ::retain reason})


(defn- arbitration-id
  [custody]
  (:dao.stream/identity (:yin.k/arbitration custody)))


(defn- settle-answer
  "The answer half of `settle`: the matched acknowledgment `answer` of
   the held observation `held` (located at `ap` as `candidate`)."
  [machine ap candidate held answer]
  (let [k (:yin.k/input-seq held)]
    (case (get answer :yin.k/status)
      (:recorded :replayed)
      (if (not= k (get answer :yin.k/input-seq))
        (retained machine :input-seq)
        (if-not (= :running (vm/gate-mode machine))
          ;; state 3: acknowledged, and no late result applies
          (retained (rewrite-held
                      machine held
                      #(assoc-in % [:yin.k/held :yin.k/state] :acknowledged))
                    :not-running)
          (let [observed (:yin.k/observed held)
                m' (unhold (apply-candidate machine
                                            (assoc candidate
                                                   :ap ap
                                                   :source (:yin.k/source held))
                                            observed)
                           held
                           k)]
            {:machine m'
             :applied {:task (:tp candidate)
                       :kind (:kind candidate)
                       :input-seq k
                       :source (:yin.k/source held)
                       :observed observed}})))
      :refused
      (if (= :input-conflict (get answer :yin.k/reason))
        {:machine (ended machine)
         :run-end {:cause :input-conflict
                   :yin.k/input-seq k
                   :yin.k/recorded-input (:yin.k/recorded-input answer)
                   :yin.k/observed-input (:yin.k/observed-input answer)}}
        (retained machine (or (get answer :yin.k/reason) :refused)))
      :stale
      {:machine (ended machine)
       :run-end {:cause :stale :yin.k/input-seq k}}
      (retained machine (or (get answer :yin.k/reason)
                            (get answer :yin.k/status)
                            :unknown-answer)))))


(defn settle
  "Acknowledge `record`, read from a stream the composition attributed
   to `author`, against `machine`'s held observation.  The record
   counts only when `front/reply-evidence` authenticates it -- the
   author is the arbitration identity -- and it is a `:yin.k/input`
   reply whose request id is the held observation's current one, with
   the answer naming the same k.  Anything else, from any other author
   a forged `:recorded` included, applies nothing.

   `:recorded` and `:replayed` apply the held observation through the
   engine's public applies, drop the held state (4) and advance the
   input sequence; under a closed gate the acknowledgment is retained
   in state 3 instead, for no late result applies.  An authenticated
   `:input-conflict` or `:stale` answer ends the run -- the machine is
   gated `:ended` -- and an input conflict quarantines nothing and is
   never an `:intent-conflict`.

   Answers `{:machine m :applied {...} | nil :run-end {...} | nil}`
   with `::retain reason` naming why nothing was applied.  A machine
   that holds no custody map is a caller defect."
  [machine author record]
  (when-not (map? (:yin.k/custody machine))
    (throw (ex-info "Settle over a task that holds no custody"
                    {:yin.k/hint :no-custody})))
  (let [custody (:yin.k/custody machine)
        evidence (front/reply-evidence (arbitration-id custody) author record)]
    (if (contains? evidence ::front/no-evidence)
      (retained machine (::front/no-evidence evidence))
      (let [rid (get record :yin.k/request-id)]
        (cond
          (not= :yin.k/input (get record :yin.k/reply))
          (retained machine :kind)

          (not (and (vector? rid) (= 3 (count rid))
                    (= :yin.k/input (nth rid 0))
                    (custody/exact? (nth rid 2))))
          (retained machine :request-id)

          :else
          (if-some [[ap candidate held] (held-location machine)]
            (let [k (:yin.k/input-seq held)]
              (if (or (not= k (nth rid 2))
                      (not= rid (input-request-id custody k)))
                (retained machine :request-id)
                (settle-answer machine ap candidate held
                               (get record :yin.k/answer))))
            (retained machine :no-held)))))))


;; =============================================================================
;; Drain: the composition's acknowledgment reader
;; =============================================================================

(defn drain
  "Fold `settle` over `read-ack!`, the composition-supplied
   acknowledgment reader: each call answers the next `[author record]`
   -- a `:yin.k/input` reply of the front, attributed by the
   composition's resolver -- or nil at the tail.  Answers `{:machine m
   :applied [...] :retained [reasons] :run-end {...} | nil}`; a record
   that retains changes nothing, and draining continues past a run
   end, for the reader is read dry either way."
  [machine read-ack!]
  (loop [m machine
         applied []
         retained-reasons []
         run-end nil]
    (if-some [pair (read-ack!)]
      (let [r (settle m (first pair) (second pair))]
        (recur (:machine r)
               (if-some [a (:applied r)] (conj applied a) applied)
               (if-some [x (::retain r)] (conj retained-reasons x) retained-reasons)
               (or run-end (:run-end r))))
      {:machine m
       :applied applied
       :retained retained-reasons
       :run-end run-end})))


;; =============================================================================
;; Replay: the prefix, applied without observing anything live
;; =============================================================================

(defn- quiet?
  "The divergence condition's machine half: every ready queue in the
   task tree empty, no fenced write awaiting its outcome (an assigned
   op id still waiting), and no held observation anywhere."
  [root]
  (every? (fn [{:keys [m]}]
            (and (empty? (:ready-queue m))
                 (not-any? :op-id (:wait-set m))
                 (not-any? :yin.k/held (concat (:wait-set m)
                                               (vals (:resources m))))))
          (walk root)))


(defn replay
  "Apply the custody prefix's records while the input sequence is below
   the frontier, observing nothing live: record k is applied to the
   selected candidate -- `candidates`' order, the first whose source
   matches, so the first applied read advances a shared cell before the
   next entry's source is computed -- with a `:cursor` record's
   position applied through `apply-mint` or `apply-link-cursor` and
   never minted.  `opts` may carry `:control-outstanding?`, the one
   divergence fact the machine cannot see; the driver supplies it.

   A record that matches no candidate ends the run as
   `{:cause :divergence ...}` only when the task tree is quiet and no
   control request is outstanding; otherwise it stops at `:unmatched`
   and the driver steps the machine and calls again, for all permitted
   internal computation must be exhausted first.  A prefix carrying no
   frontier fails closed (`:unsatisfied`), never as an empty one.

   Answers `{:machine m :applied [...] :run-end {...} | nil :unmatched
   k | nil :unsatisfied {...} | nil}`."
  ([machine] (replay machine nil))
  ([machine opts]
   (when-not (map? (:yin.k/custody machine))
     (throw (ex-info "Replay over a task that holds no custody"
                     {:yin.k/hint :no-custody})))
   (if (not= :running (vm/gate-mode machine))
     {:machine machine :applied [] :run-end nil :unmatched nil}
     (if (nil? (frontier (:yin.k/custody machine)))
       {:machine machine :applied [] :run-end nil :unmatched nil
        :unsatisfied (unsatisfied)}
       (let [prefix (get-in machine [:yin.k/custody :input :prefix])
             records (:yin.k/inputs prefix)]
         (loop [m machine
                k (next-k (:yin.k/custody machine))
                applied []]
           (if (>= k (frontier (:yin.k/custody m)))
             {:machine m :applied applied :run-end nil :unmatched nil}
             (if (or (nil? records) (>= k (count records)))
               ;; a prefix shorter than its own frontier is malformed
               ;; evidence, and missing evidence is never replayed past
               {:machine m :applied applied :run-end nil :unmatched nil
                :unsatisfied (unsatisfied)}
               (let [record (nth records k)
                     source (:yin.k/source record)
                     observed (:yin.k/observed record)
                     selected (some (fn [c]
                                      (when (cbor/content= source (:source c))
                                        c))
                                    (candidates m))]
                 (if (nil? selected)
                   (if (and (quiet? m) (not (:control-outstanding? opts)))
                     {:machine (ended m)
                      :applied applied
                      :run-end {:cause :divergence
                                :yin.k/input-seq k
                                :yin.k/source source}
                      :unmatched nil}
                     {:machine m :applied applied :run-end nil :unmatched k})
                   (recur (assoc-in (apply-candidate m selected observed)
                                    [:yin.k/custody :input :next]
                                    (inc k))
                          (inc k)
                          (conj applied {:task (:tp selected)
                                         :kind (:kind selected)
                                         :input-seq k
                                         :source source
                                         :observed observed}))))))))))))
