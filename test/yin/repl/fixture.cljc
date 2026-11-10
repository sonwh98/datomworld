(ns yin.repl.fixture
  "Step 1 non-DHT composition fixture (docs/design/yin.repl.dht-composition.md
   sections 8-10, Gate B): the common test constructor the retained engine
   suite is parameterized over.  It injects an explicit `dao.jing` byte
   handle into `yin.repl.frontends/create-state` -- never a store spec that
   could resolve to DHT -- in two backend selections:

   - `:memory` -- `dao.jing.mem/create-content-mem`, ephemeral.  Local
     publication completion is acknowledged without claiming persistence
     (spec section 4: `:durability :memory`).
   - `:file` -- `dao.space.store/open` over a scratch directory, which
     wraps `dao.jing.file/create-content-file` and supplies locking, the
     atomic HEAD write (`:head-fn`), and validated recovery.  Local
     publication is durable only when that HEAD write succeeds
     (`:durability :persistent`).

   The fixture owns the publication interpreter the spec assigns to
   composition: `observed-backend` / `failable-backend` wrap the handle's
   `:head-fn` -- the durable-publication request the engine emits today
   through `yin.repl.index`'s `:after-publish`, after content materialized
   and read back -- and record each call as an explicit section 4 result
   record (`{:op :publication-result :id :round :manifest :status
   :durability}`; `:status` is `:published` only when the HEAD write
   returned, `:refused` with `:reason` when it threw).  Today's seam
   carries only the manifest, so the fixture mints `:id` / `:round` as
   the call sequence in emission order; Step 2's request record
   (`{:op :publish :id :round :manifest}`) will carry the engine's own
   identifiers and the fixture will check them instead of minting them.
   The frontier expectations pinned through this fixture are stated so
   they survive that move unchanged.

   This namespace requires no DHT namespace (`dao.jing.dht`,
   `dao.space.dht`, `yin.repl.dht`, `yin.vm.linker.dht`): engine tests
   built on it construct no DHT node, socket, follower, or repair
   scheduler.  (`yin.repl` itself still loads the DHT composition today;
   removing that load is Step 2, gated by Gate A.)"
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.store :as durable]
            [dao.stream :as stream]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.index :as repl.index]
            [yin.repl.publication :as publication]))


(def backend-kinds
  "The non-DHT backend selections every parameterized engine test runs."
  [:memory :file])


(defn backend-durability
  "The local durability a successful publication claims on `kind`
   (spec section 4): the memory composition never claims persistence."
  [kind]
  (case kind
    :memory :memory
    :file :persistent))


;; =============================================================================
;; Publication-result protocol (spec section 4): the Step 2 oracle
;;
;; The engine prepares a frontier, emits `{:op :publish :id :round
;; :manifest}`, and promotes that frontier only for the matching local
;; result `{:op :publication-result :id :round :manifest :status
;; :durability}`.  Identifiers are unique within the session and survive
;; evaluation rollback; stream append success means accepted, never
;; completed; network acknowledgement is a distinct outcome and never
;; promotes.  `publication-result-expectations` states every case's
;; required frontier effect; `accept-result` is the test-local model of
;; `yin.repl/accept-publication-result`'s frontier rule that Step 2 must
;; satisfy through the real stream seam without changing these outcomes.
;; Cases marked `:step-2-oracle` cannot be delivered through today's
;; synchronous `:head-fn` seam at all; the synchronous pins exercise only
;; `:synchronous` cases.
;; =============================================================================

(defn publication-request
  "The section 4 publication request for one prepared frontier."
  [id round manifest]
  {:op :publish :id id :round round :manifest manifest})


(defn publication-result
  "The section 4 local publication result answering `request`.
   `status` is `:published` or `:refused`; a refusal carries `:reason`.
   `durability` is the composition's local claim only (`:memory` or
   `:persistent`), never a network acknowledgement."
  [request status durability & [reason]]
  (cond-> {:op :publication-result
           :id (:id request)
           :round (:round request)
           :manifest (:manifest request)
           :status status
           :durability durability}
    reason (assoc :reason reason)))


(def publication-result-expectations
  "Expected frontier outcome for every publication-result case, stated
   against a prior promoted frontier F, a prepared pending request
   P = {:id i :round r :manifest m} covering round r, and a result R.
   `:frontier` `:promote` means F becomes exactly P's frontier (manifest,
   published count, and verified cursor together, exactly once);
   `:preserve` means F is identical afterwards; `:ignore` means F is
   identical and P stays pending.  `:pinned` separates what today's
   synchronous seam can deliver from the Step 2 stream oracle."
  [{:case :matching-success
    :given "R matches P (id, round, manifest) with :status :published"
    :frontier :promote :pending-after :cleared :pinned :synchronous}
   {:case :refusal
    :given "R matches P with :status :refused and a :reason"
    :frontier :preserve :pending-after :superseded :pinned :synchronous
    :note "P's prepared manifest is never promoted; the file HEAD still names F; a later round's success promotes the whole suffix."}
   {:case :delayed-completion
    :given "R matches P with :status :published, delivered after idle steps or after newer transactions committed but before any newer promotion"
    :frontier :promote :pending-after :cleared :pinned :step-2-oracle
    :note "No promotion and no settled report before R arrives; on arrival exactly P promotes. If a newer round already promoted, R is stale instead."}
   {:case :wrong-id
    :given "R's :id differs from P's (round and manifest may match)"
    :frontier :ignore :pending-after :intact :pinned :step-2-oracle}
   {:case :wrong-round
    :given "R's :round differs from P's (id and manifest may match)"
    :frontier :ignore :pending-after :intact :pinned :step-2-oracle}
   {:case :wrong-manifest
    :given "R's :manifest differs from P's (id and round match)"
    :frontier :ignore :pending-after :intact :pinned :step-2-oracle
    :note "Neither R's manifest nor P's frontier promotes."}
   {:case :duplicate
    :given "The matching :published R is delivered a second time after it promoted"
    :frontier :ignore :pending-after :intact :pinned :step-2-oracle
    :note "Frontier, counts, and cursor are identical; no second promotion and no new request. Re-stepping an idle indexer is not this case."}
   {:case :stale
    :given "R names an already-promoted or superseded round (older than the published frontier)"
    :frontier :ignore :pending-after :intact :pinned :step-2-oracle
    :note "A stale result cannot promote another round and cannot regress the frontier, even across rollback."}])


(defn accept-result
  "Test-local model of the section 4 frontier rule: `[frontier pending
   result]`, where `pending` is `{:request req :promotes frontier'}` or
   nil.  Answers `{:frontier f :pending p :effect e}` with `e` in
   `:promoted` / `:preserved` / `:ignored`.  Only an exact id/round/
   manifest match settles P; a mismatch leaves P intact, a refusal
   supersedes P without touching the frontier, and a result with no
   matching pending (duplicate or stale) changes nothing."
  [frontier pending result]
  (let [request (:request pending)]
    (cond
      (nil? pending)
      {:frontier frontier :pending nil :effect :ignored}

      (not (and (= (:id result) (:id request))
                (= (:round result) (:round request))
                (= (:manifest result) (:manifest request))))
      {:frontier frontier :pending pending :effect :ignored}

      (= :refused (:status result))
      {:frontier frontier :pending nil :effect :preserved}

      (= :published (:status result))
      {:frontier (:promotes pending) :pending nil :effect :promoted}

      :else
      {:frontier frontier :pending pending :effect :ignored})))


;; =============================================================================
;; The publication medium a test composes (spec section 4, Step 2 form)
;;
;; The engine no longer takes an `:after-publish` callback: it emits
;; `{:op :publish :id :round :manifest}` on the medium's request side and
;; promotes the prepared frontier only for the matching
;; `{:op :publication-result ...}` the result side carries.  These helpers
;; build the medium a test composes over its own HEAD writer, so the
;; frontier expectations pinned in Step 1 hold against the real stream
;; seam rather than a synchronous hook.
;; =============================================================================

(defn head-media
  "A publication medium pair whose requests are serviced locally through
   `head-fn`, the composition's durable HEAD writer: `:published` when it
   returns, `:refused` under its message when it throws.  `durability` is
   the local claim the result carries (`:persistent` by default; a memory
   composition passes `:memory` and never claims persistence)."
  ([head-fn] (head-media head-fn :persistent))
  ([head-fn durability]
   (publication/local-media {:head-fn head-fn :durability durability})))


(defn refusing-media
  "A publication medium whose every request is refused by a HEAD writer
   that throws `message`: the prepared frontier is preserved, never
   promoted, and the round reports the refusal as its failure."
  ([] (refusing-media "HEAD refused"))
  ([message]
   (head-media (fn [_manifest] (throw (ex-info message {}))))))


(defn no-head-media
  "A publication medium with no durable HEAD writer: an ephemeral
   composition that acknowledges local completion in memory only."
  []
  (head-media nil :memory))


(defn engine-media
  "The engine's view of `media`: the request and result sides with the
   composition's durable HEAD writer stripped off, which is what an indexer
   holds.  `make-indexer` strips a supplied medium itself; this is for a
   test that replaces an indexer's medium in place, so the invariant that
   no writer sits inside the indexer holds on that path too."
  [media]
  (publication/engine-media media))


(defn serve-publication
  "The composition's half of a publication round over `media`: service
   every request `indexer` emitted and no result has settled through the
   medium's own durable HEAD writer, appending each result to the medium's
   result side, then settle those results into the indexer
   (`yin.repl.publication/service-local!`,
   `yin.repl.index/settle-publication`).

   Step 2 moved this out of the indexer, which now emits the request and
   holds the prepared frontier pending without calling any writer, so a
   test that drives the indexer directly drives the composition's service
   too — exactly what a composition does at its evaluation boundary
   (spec sections 3-4)."
  [media indexer]
  (doseq [request (repl.index/pending-publications indexer)]
    (publication/service-local! media request))
  (repl.index/settle-publication indexer))


(defn step
  "One index round, then the composition's local publication service over
  `media`: the round emits its request, the service writes the durable HEAD
  and appends the result, and the indexer promotes on the matching result.
  The production seam, driven from the side that owns the writer."
  [media indexer round]
  (serve-publication media (repl.index/step indexer round)))


(defn commit-names
  "`yin.repl.index/commit-names` with the composition's local publication
   service over `media` afterwards: a signed envelope commit's publication
   is settled the same way a round's is, so its promotion — or the refusal
   its caller must hear — is decided before the commit answers."
  [media indexer signed round]
  (serve-publication media (repl.index/commit-names indexer signed round)))


;; =============================================================================
;; Scratch directories (host-portable, as in yin.repl.store-test)
;; =============================================================================

(defn temp-dir
  "A fresh scratch directory path for a `:file` backend."
  []
  (str "target/test-fixture-" (random-uuid)))


(defn cleanup-dir!
  "Best-effort recursive removal of a scratch directory."
  [dir]
  (when dir
    #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                  (catch Object _ nil))
       :clj (let [f (java.io.File. ^String dir)]
              (when (.isDirectory f)
                (doseq [child (.listFiles f)]
                  (.delete ^java.io.File child))
                (.delete f)))
       :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                  (catch :default _ nil)))))


;; =============================================================================
;; Backends: open / close / call-with
;; =============================================================================

(defn open-backend
  "Open the non-DHT backend `kind` (`:memory` or `:file`) as a backend
   value `{:kind :handle :dir :durability}`.  `opts` may carry
   `:publish-opts` (the immutable generic publication policy, spec
   section 5 -- threaded onto the handle where `yin.repl/make-session`
   reads it) and, for `:file`, an explicit `:dir`.

   The handle is a plain `dao.jing` byte handle in both selections; the
   file selection additionally carries `:head-fn` and `:recovery` from
   `dao.space.store/open`.  No selection constructs DHT state."
  ([kind] (open-backend kind {}))
  ([kind {:keys [publish-opts dir]}]
   (case kind
     :memory (let [handle (cond-> (mem/create-content-mem)
                            publish-opts (assoc :publish-opts publish-opts))]
               {:kind :memory :handle handle :dir nil
                :durability :memory})
     :file (let [dir (or dir (temp-dir))
                 opened (durable/open dir)
                 handle (cond-> opened
                          publish-opts (assoc :publish-opts publish-opts))]
             {:kind :file :handle handle :dir dir
              :durability :persistent}))))


(defn close-backend!
  "Close a backend's handle (releasing a file backend's directory lock)
   and remove its scratch directory.  Idempotent in effect: a second
   close of a content handle is refused by nothing here."
  [{:keys [handle dir]}]
  (when-let [close! (:close-fn handle)]
    (try (close!) (catch #?(:cljd Object :clj Exception :cljs :default) _ nil)))
  (cleanup-dir! dir)
  nil)


(defn call-with-backend
  "Open backend `kind`, call `(f backend)`, and close the backend even
   when `f` throws.  Answers `f`'s value; anything a test needs from the
   store must be read inside `f`, before the close."
  ([kind f] (call-with-backend kind {} f))
  ([kind opts f]
   (let [backend (open-backend kind opts)]
     (try (f backend)
          (finally (close-backend! backend))))))


;; =============================================================================
;; The common test constructor (Gate B's injection point)
;; =============================================================================

(defn create-state
  "A shell over `backend`'s explicitly injected `dao.jing` handle, plus
   `opts` for `yin.repl.frontends/create-state` (`:vm-type`,
   `:content-store`, `:name-env`, ...).  This is Gate B's constructor:
   the proof never relies on the default memory construction, and no
   `:index-store-spec` -- the only path that can name DHT -- is used."
  ([backend] (create-state backend {}))
  ([backend opts]
   (repl.frontends/create-state (merge {:index-store (:handle backend)} opts))))


(defn call-with-state
  "Open backend `kind`, build a shell over it with `opts`, call
   `(f state backend)`, and close the backend afterwards."
  ([kind f] (call-with-state kind {} f))
  ([kind opts f]
   (call-with-backend kind (fn [backend] (f (create-state backend opts) backend)))))


;; =============================================================================
;; Publication observation: the composition-side interpreter (Step 1 form)
;; =============================================================================

(defn- recording-head-fn
  "The Step 1 composition interpreter over one backend's `:head-fn`:
   each call mints the next section 4 correlation (`:id` and `:round`
   are the call sequence, because today's seam carries only the
   manifest) and appends exactly one result record to `log` -- after
   the HEAD write's outcome is known, so a refusal is never recorded
   as a success.  While `refuse?` holds true the write is refused
   without delegating.  The write's exception always propagates."
  [log sequence inner durability refuse?]
  (fn [manifest-address]
    (let [n (swap! sequence inc)
          request (publication-request n n manifest-address)]
      (if (and refuse? @refuse?)
        (do (swap! log conj (publication-result request :refused
                                                durability "HEAD refused"))
            (throw (ex-info "HEAD refused" {})))
        (try
          (when inner (inner manifest-address))
          (swap! log conj (publication-result request :published durability))
          (catch #?(:cljd Object :clj Exception :cljs :default) e
            (swap! log conj (publication-result
                              request :refused durability
                              (or (ex-message e) "HEAD refused")))
            (throw e)))))))


(defn publication-requests
  "The section 4 requests a result `log` answers, oldest first: each
   result's `:id` / `:round` / `:manifest` projected back to its
   `{:op :publish ...}` request."
  [log-values]
  (mapv #(publication-request (:id %) (:round %) (:manifest %)) log-values))


(defn observed-backend
  "Backend whose durable-publication results are recorded in `log` (an
   atom of vectors): each `:head-fn` call appends one
   `{:op :publication-result :id :round :manifest :status :durability}`
   record (`:refused` results also carry `:reason`).  A memory backend
   has no `:head-fn` today; observing one installs a recording writer
   that acknowledges local completion in memory only -- it never makes
   the ephemeral store claim persistence."
  [backend log]
  (let [handle (:handle backend)]
    (assoc backend :handle
           (assoc handle :head-fn
                  (recording-head-fn log (atom 0) (:head-fn handle)
                                     (:durability backend) nil)))))


(defn failable-backend
  "Like `observed-backend`, but while `fail?` (an atom) holds true the
   HEAD write is refused instead of delegating: the log gains a
   `:refused` result and the caller sees the refusal throw.  Tests read
   attempts and outcomes from the same log: `:status` distinguishes
   them, and only `:published` manifests may appear as the promoted
   frontier."
  [backend log fail?]
  (let [handle (:handle backend)]
    (assoc backend :handle
           (assoc handle :head-fn
                  (recording-head-fn log (atom 0) (:head-fn handle)
                                     (:durability backend) fail?)))))


;; =============================================================================
;; Frontier reading (spec section 8, Frontier gate)
;; =============================================================================

(defn stream-values
  "Every value currently on stream `s`, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn transactions
  "The transaction records an indexer committed, oldest first."
  [indexer]
  (mapv :dao.space/transaction (stream-values (:local indexer))))


(defn expected-branching-factor
  "The count cap `indexer` publishes under: its declared publication
   policy's `:branching-factor`, defaulting to 512 as the engine does.
   A manifest is count-cap compatible only when its own
   `:branching-factor` equals this (spec sections 5 and 8)."
  [indexer]
  (:branching-factor (:publish-opts indexer) 512))


(defn frontier
  "The indexer's verified published frontier as plain data, with the
   manifest read back from its content store: everything the Frontier
   gate (spec section 8) requires to agree -- manifest identity,
   published transaction count, count-cap compatibility, cursor
   ownership, and coverage.  `:manifest` is nil before any publication."
  [indexer]
  (let [address (:manifest-address indexer)
        manifest (when address
                   (index/read-manifest (:content-store indexer) address))]
    {:manifest-address address
     :manifest manifest
     :manifest-identity (when manifest (jing/segment-key manifest))
     :count (:count manifest)
     :branching-factor (:branching-factor manifest)
     :expected-branching-factor (expected-branching-factor indexer)
     :published (:published indexer)
     :transactions (:transactions indexer)
     :materialization (:materialization indexer)
     :materialized-transactions (get-in indexer [:materialization :transactions])
     :cursor (get-in indexer [:materialization :cursor])
     :newest-cursor (:dao.stream/cursor
                      (stream/cursor (:local indexer) :dao.stream/newest))
     :status (repl.index/status indexer)}))
