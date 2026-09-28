(ns yin.vm.ucf.remote
  "The UCF facade through `dao.stream.remote` descriptors: lifts a
   parked semantic machine's pending wait
   (`docs/design/yin.vm.universal-continuation-format.md` S7.4.3) into
   UCF data whose cells and stream markers name `dao.stream.remote`
   descriptors under the `:dao.stream.remote/v1` cursor profile
   (S7.5.3), and lowers that data back into a resumable local wait
   through a reflection (`docs/design/dao.stream.remote.md` S2.2,
   S2.4).

   The local side is the engine's own parked machinery, not an
   invented shape. A pending entry is what `yin.vm` parks: the
   machine registers beside `:stream-id` and `:cursor-ref`
   (`yin.vm.semantic` S3.5's park entries, `response-wait-entry`;
   `yin.vm.engine`'s wait-set sweep), every handle and cursor
   resolved out of the machine's private `:resources` table -- the
   lift reads the table, and no entry ever carries a live handle.
   The lower mirrors it: fresh resource entries, one per stream
   reflection and one per cursor cell (two waiters sharing one cell
   share the one entry, S7.5.3), and wait-set entries that merge the
   caller's register context verbatim, so a lowered wait parks under
   the engine's own sweep exactly as an unmigrated one does.

   Where the engine polls a wait at one of its own fixed resource
   keys, the lower parks the wait there: a link wait's pair under
   `yin.vm.module`'s link-request-resource and
   link-response-resource, reflections and entry fields alike, and a
   retained request as the engine's own `:request-sent` shape on the
   fixed local call-in key -- the receiver's own pair serves the
   retried request and the restored response wait reads the
   receiver's own call-out, so the fixed keys serve the resumed
   program's calls alone. Every response route travels as its cell
   and lowers under the cell's own fresh key (S7.5.3) -- distinct
   cells stay distinct, shared cells stay shared -- never at a fixed
   key, so a migrated route can never misroute a new call.

   `lift-pending` and `lower-pending` are plain-data functions,
   parameterized by two composition callbacks rather than coupled to
   `yin.vm` or `dao.stream.remote` internals:

     serve!  : handle -> {:dao.stream/identity id
                          :dao.stream/channel chan} | nil
               the exporter's own table entry
               (`dao.stream.remote.md` S2.3): nil when the handle
               cannot be served -- no table to enter it in, no
               channel to the resumer -- which refuses the lift as
               `:yin.k/unsatisfied` before minting the value
               (S7.4.3).

     attach! : remote-descriptor -> {:dao.stream/outcome
                                     :dao.stream/ok
                                     :dao.stream/handle reflection}
               | outcome-map
               the receiver's own `:dao.stream/attach` dispatch for
               `:dao.stream/remote` (`dao.stream.remote.md` S2.4): an
               outcome other than `ok` -- including a reflection
               already marked gone -- refuses the lower as
               `:yin.k/unsatisfied` naming the stream identity.

   The `:dao.stream.remote/v1` claim is checked, not declared: a cell
   is published only after its kept cursor survives a round trip
   through the channel codec -- encoded, decoded back, equal -- which
   is what plain data in the channel's portable domain means
   (S7.4.3; `dao.stream.remote.md` S2.3's plain-data basis).
   `cursor-codec` is the default codec; a composition whose channel
   speaks another passes its own `{:encode f :decode f}` to
   `lift-pending` or `lift-frame`. A cursor that does not round trip
   -- a host object inside is the case S2.3 names -- refuses the lift
   as `:yin.k/unsatisfied` before any value is minted.

   Every pending variant of S7.4.3 lifts and lowers: `:next`, `:put`,
   `:ffi`, `:ffi-request`, and the linker's `:link-request`,
   `:link-response` and `:install` (`yin.vm.linker.md` S7.2-7.3).
   `:install` names no stream and passes through unchanged.

   Scope: `lift-frame` and `lower-frame` are the frame-level entry
   points -- cells are minted and remapped across the WHOLE entry
   set, so two waiters on one engine cursor cell lift to one UCF
   cell and lower back into one shared cursor entry, and independent
   cells stay independent (S7.5.3, and the sharing rule of the S7.11
   pending-state blocker). `lift-pending` and `lower-pending` lift or
   lower one entry into a self-contained table."
  (:require [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.module :as module]))


(def cursor-profiles
  "The one portable cursor profile S7.4.3 names in :yin.k/requires: a
   cursor is plain data in the channel's portable domain and the
   source honors it after serialization, cursor identity, position and
   `gap` outcomes included."
  #{:dao.stream.remote/v1})


(def cursor-codec
  "The channel codec a lift round-trips kept cursors through when the
   composition supplies none: the DaoStream v2 wire codec, whose
   portable domain is the domain the profile claim names."
  {:encode cbor/encode, :decode cbor/decode})


(def ^:private requires
  {:yin.k/cursor-profiles cursor-profiles})


;; =============================================================================
;; Markers (S7.5.1), through a remote descriptor
;; =============================================================================

(defn- local-identity
  "The identity `h` itself declares, when its descriptor answers ok;
   nil otherwise -- the best name a refusal can give when `serve!` has
   not minted one."
  [h]
  (let [d (when (stream/descriptor? h) (stream/descriptor h))]
    (when (= :dao.stream/ok (:dao.stream/outcome d))
      (:dao.stream/identity d))))


(defn- unsatisfied
  ([] (unsatisfied nil))
  ([identity]
   (cond-> {:yin.k/status :yin.k/unsatisfied}
     (some? identity) (assoc :dao.stream/identity identity))))


(defn- portable-cursor
  "The kept cursor when it survives a round trip through the channel
   codec -- encoded, decoded back, and equal -- which is what the
   `:dao.stream.remote/v1` claim means: plain data in the channel's
   portable domain, honored after serialization. nil when it does
   not, so no cell is ever published over an unpublishable cursor."
  [codec cursor]
  (try (let [w (:encode codec)
             b (w cursor)
             r ((:decode codec) b)]
         (when (= cursor r) cursor))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn lift-marker
  "The UCF stream marker (S7.5.1) for local handle `h`, its descriptor
   the remote descriptor `serve!` mints by entering `h` in the
   exporter's table (`dao.stream.remote.md` S2.2). nil when `serve!`
   refuses -- the source cannot be entered, or cannot honor the
   profile -- so a caller mints `:yin.k/unsatisfied` before any value
   is produced (S7.4.3)."
  [serve! h]
  (when-let [served (serve! h)]
    (let [id (:dao.stream/identity served)]
      {:yin.k/tag :yin.k/stream,
       :dao.stream/identity id,
       :dao.stream/descriptor
       {:dao.stream/type :dao.stream/remote,
        :dao.stream/identity id,
        :dao.stream/channel (:dao.stream/channel served)}})))


(defn lower-marker
  "The local handle `attach!` gives for UCF stream marker `m` -- a
   reflection on the original logical stream -- or nil when the attach
   does not answer ok: a reflection marked gone, or any other refusal,
   is unsatisfied at the caller."
  [attach! m]
  (let [result (attach! (:dao.stream/descriptor m))]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result))))


;; =============================================================================
;; The pending wait (S7.4.3)
;; =============================================================================
;;
;; Local pending-entry shapes this namespace lifts, and lowers back to
;; -- the engine's own wait-set shapes, plain data resolved through
;; the machine's `:resources` table:
;;
;;   {:reason :next          :cursor-ref {:type :cursor-ref, :id cid}
;;                           :stream-id sid}          ; blocked read
;;   {:reason :next          :call-id id :op op        ; a sent call's
;;                           :cursor-ref {:type :cursor-ref, :id cid}
;;                           :stream-id sid}          ; response wait
;;   {:reason :put           :stream-id sid            ; blocked write,
;;                           :datom v}                 ; :datom the retry
;;   {:reason :put           :request-sent true        ; a retained FFI
;;                           :call-id id :op op        ; request; the
;;                           :stream-id sid :datom env}; fixed call-in
;;   {:reason :link-request  :link-id id :name m :envelope e
;;                           :request rid :response rid' :cursor kept}
;;   {:reason :link-response :link-id id :name m
;;                           :request rid :response rid' :cursor kept}
;;   {:reason :install       :name m}
;;
;; `cid` names a `:resources` cursor entry `{:stream-id sid :cursor
;; kept}` (`vm/cursor-entry`); `sid` and `rid` name stream handles in
;; the same table; `env` is the retained `dao.stream.apply` envelope.
;; On a real entry the retained request's `sid` is the engine's fixed
;; `vm/call-in-stream-key`, and a link wait's `rid`/`rid'` are the
;; fixed `module/link-request-resource` and
;; `module/link-response-resource` -- the keys the engine's own retry
;; and poll paths resolve.
;; The registers every parked entry also carries -- :segment :pc :env
;; :stack :k -- are the machine's own context: the lift ignores them
;; (they travel in the frame's continuation slice, not the pending
;; wait), and the lower stamps the caller's context back onto the
;; entry it builds.


;; =============================================================================
;; Cell minting and remapping (S7.5.3), across the whole frame set
;; =============================================================================

(defn- mint-cell
  "One mint per distinct cursor cell across the WHOLE frame set:
   `found` accumulates the remapping {cell key -> UCF cell id} beside
   the cells themselves as entries lift, so two waiters on one
   engine cursor cell lift to one UCF cell, and independent cells
   stay independent, whatever their positions (S7.5.3). `k` is the
   cell's identity -- the engine cursor resource id a `:next` waiter
   names, or the response stream and kept cursor a link wait names.
   The cell carries the marker `serve!` mints for the cell's stream
   and its kept cursor, published only after the cursor survives the
   codec round trip; nil -- refuse -- when it does not, or when
   `serve!` refuses the stream."
  [serve! found]
  (fn [codec k handle kept]
    (or (get-in @found [:of k])
        (let [portable (portable-cursor codec kept)
              marker (when portable (lift-marker serve! handle))]
          (when (and portable marker)
            (let [cid (keyword "yin.k" (str "c-" (count (:cells @found))))
                  cell {:yin.k/stream marker, :yin.k/position portable}]
              (swap! found #(-> % (assoc-in [:of k] cid)
                                (assoc-in [:cells cid] cell)))
              cid))))))


(defn- cursor-cell
  "The engine cursor cell a `:next` entry waits on: `:cursor-ref`'s
   id into `:resources`, the cursor entry carrying the stream id and
   the kept cursor (`vm/cursor-entry`). nil when it names nothing."
  [resources entry]
  (let [cid (get-in entry [:cursor-ref :id])
        cell (get resources cid)]
    (when (and (some? cid) (map? cell) (contains? cell :stream-id))
      {:id cid, :stream-id (:stream-id cell), :cursor (:cursor cell)})))


(defn- cell-identity
  "The stream identity a `:yin.k/cells` entry names, for a refusal
   that names it even when the cell itself never lowered."
  [cells cell-id]
  (get-in cells [cell-id :yin.k/stream :dao.stream/identity]))


;; =============================================================================
;; Lift: one variant each
;; =============================================================================

(defn- lift-read
  "S7.4.3's blocked read: the cell `mint` gives for the entry's
   cursor cell, at its kept position."
  [resources mint codec entry]
  (if-let [cell (cursor-cell resources entry)]
    (if-let [cid (mint codec (:id cell)
                       (get resources (:stream-id cell))
                       (:cursor cell))]
      {:pending {:yin.k/reason :next, :yin.k/cell cid}, :cell? true}
      (unsatisfied (local-identity (get resources (:stream-id cell)))))
    (unsatisfied)))


(defn- lift-response-wait
  "S7.4.3's sent call: a `:next` entry carrying `:call-id`, the
   response wait `semantic-restore` rebuilds -- the call's
   correlation beside the response cell at its kept position, so
   round-order on the cell is preserved on the resumer (S7.4.3).
   The call's `:op` rides when the entry carries it (the engine's
   own response wait does not; a caller assembling the frame takes
   it from the call's parked record)."
  [resources mint codec entry]
  (if-let [cell (cursor-cell resources entry)]
    (if-let [cid (mint codec (:id cell)
                       (get resources (:stream-id cell))
                       (:cursor cell))]
      {:pending (cond-> {:yin.k/reason :ffi,
                         :yin.k/call-id (:call-id entry),
                         :yin.k/cell cid}
                  (contains? entry :op) (assoc :yin.k/op (:op entry))),
       :cell? true}
      (unsatisfied (local-identity (get resources (:stream-id cell)))))
    (unsatisfied)))


(defn- lift-write
  "S7.4.3's blocked write: the target stream's marker and the
   retained `:datom` -- the value a retry appends, without which the
   wait is not re-establishable. A put names a stream, never a
   cursor cell."
  [serve! resources entry]
  (let [h (get resources (:stream-id entry))]
    (if-let [marker (lift-marker serve! h)]
      {:pending {:yin.k/reason :put,
                 :yin.k/stream marker,
                 :yin.k/value (:datom entry)}}
      (unsatisfied (local-identity h)))))


(defn- call-out-cell
  "The emitter's call-out cursor cell -- the response route the
   retained call had on the emitter: the cursor entry under
   `vm/call-out-cursor-key`, reading the call-out stream under
   `vm/call-out-stream-key` at its kept position. nil when the
   composition holds no call pair, which no real retained request
   does -- the pair is checked before the call parked."
  [resources]
  (let [cursor (get resources vm/call-out-cursor-key)
        handle (get resources vm/call-out-stream-key)]
    (when (and (map? cursor) (contains? cursor :stream-id) handle)
      {:id vm/call-out-cursor-key,
       :stream-id vm/call-out-stream-key,
       :cursor (:cursor cursor)})))


(defn- lift-retained-request
  "S7.4.3's retained call: a `:put` entry carrying `:request-sent`,
   the request the engine holds unsent. Both endpoints are served --
   the request stream's marker and the response stream's marker,
   `call-out-cell`'s -- and the retained envelope rides VERBATIM
   (`:yin.k/request-envelope`) beside the kept response cell
   (`:yin.k/response-cell`), its op and args carried only as
   derived views (`:request-op`, `:request-args`; the op is the
   source of the lowered entry's `:op`), so a resumer re-establishes
   the carried pair exactly as the sender held it: retry through the
   request reflection, wait for the response through the response
   reflection at the kept position. The envelope is the whole truth:
   the request protocol is open to keys beyond op and args
   (`dao.stream.apply`'s `request?`), so the lower must never
   rebuild it from the views. Either endpoint unservable refuses as
   `:yin.k/unsatisfied` naming that endpoint's identity, before any
   value is minted."
  [serve! resources mint codec entry]
  (let [h (get resources (:stream-id entry))
        envelope (:datom entry)
        route (call-out-cell resources)
        resp-h (and route (get resources (:stream-id route)))]
    (if-let [marker (lift-marker serve! h)]
      (if-let [resp-marker (and resp-h (lift-marker serve! resp-h))]
        (if-let [cid (mint codec (:id route) resp-h (:cursor route))]
          {:pending {:yin.k/reason :ffi-request,
                     :yin.k/call-id (:call-id entry),
                     :yin.k/request-envelope envelope,
                     :yin.k/request-op (or (apply2/request-op envelope)
                                           (:op entry)),
                     :yin.k/request-args (apply2/request-args envelope),
                     :yin.k/request marker,
                     :yin.k/response resp-marker,
                     :yin.k/response-cell cid},
           :cell? true}
          (unsatisfied (local-identity resp-h)))
        (unsatisfied (local-identity resp-h)))
      (unsatisfied (local-identity h)))))


(defn- lift-link
  "S7.4.3's link variants: the link pair name the engine's own fixed
   link keys on a real entry (`module/link-request-resource` and
   `module/link-response-resource`), resolved into `:resources`, and
   the kept `:cursor` on the response stream
   (`module/require-handler` mints it before the envelope is appended)
   is the response cell -- a cell like any other, at its kept
   position. Both pair markers travel, the engine's `:link-response`
   entry retaining its `:request` beside the response side; the
   envelope rides verbatim on the request-in-hand variant."
  [serve! resources mint codec entry]
  (let [reason (:reason entry)
        resp-h (get resources (:response entry))
        req-h (get resources (:request entry))
        kept (:cursor entry)]
    (if-let [cid (mint codec [(:response entry) kept] resp-h kept)]
      (let [resp (lift-marker serve! resp-h)
            req (lift-marker serve! req-h)]
        (if (and resp req)
          {:pending (cond-> {:yin.k/reason reason,
                             :yin.k/link-id (:link-id entry),
                             :yin.k/name (:name entry),
                             :yin.k/request req,
                             :yin.k/response resp,
                             :yin.k/cell cid}
                      (= :link-request reason)
                      (assoc :yin.k/envelope (:envelope entry))),
           :cell? true}
          (unsatisfied (local-identity (or req-h resp-h)))))
      (unsatisfied (local-identity resp-h)))))


(defn- lift-one
  "One pending entry of S7.4.3, lifted into `{:pending data,
   :cell? bool}` -- `:cell?` true when the variant names a cursor
   cell, which is what puts `:dao.stream.remote/v1` in
   `:yin.k/cursor-profiles` -- or an outcome carrying
   `:yin.k/status`: `:yin.k/unsatisfied` when a stream cannot be
   served or a kept cursor does not survive the codec round trip;
   `:yin.k/undecodable`, naming the reason, when the entry is no
   variant at all."
  [serve! resources mint codec entry]
  (case (:reason entry)
    :next
    (if (:call-id entry)
      (lift-response-wait resources mint codec entry)
      (lift-read resources mint codec entry))

    :put
    (if (:request-sent entry)
      (lift-retained-request serve! resources mint codec entry)
      (lift-write serve! resources entry))

    :link-request (lift-link serve! resources mint codec entry)

    :link-response (lift-link serve! resources mint codec entry)

    :install
    {:pending {:yin.k/reason :install, :yin.k/name (:name entry)}}

    {:yin.k/status :yin.k/undecodable, :yin.k/reason (:reason entry)}))


;; =============================================================================
;; Lift: the frame and the single entry
;; =============================================================================

(defn lift-frame
  "A whole frame set of S7.4.3 pending entries, lifted together:
   `{:yin.k/pending [data ...], :yin.k/cells {cell-id cell},
   :yin.k/requires requires}` on success -- the cells minted across
   the WHOLE set, so two waiters on one engine cursor cell lift to
   one UCF cell (S7.5.3) and `:dao.stream.remote/v1` rides in
   `:yin.k/cursor-profiles` when any entry names a cell; the S7.9
   `:yin.k/unsatisfied` outcome, naming the stream identity where one
   is known, when any entry's stream cannot be served or its kept
   cursor does not survive the codec round trip; the S7.9
   `:yin.k/undecodable` outcome when any entry names no variant.
   Failure is total (S7.5.4): a refused leaf refuses the frame."
  ([serve! resources entries]
   (lift-frame serve! resources entries cursor-codec))
  ([serve! resources entries codec]
   (let [found (atom {:cells {}, :of {}})
         mint (mint-cell serve! found)
         lifted (mapv #(lift-one serve! resources mint codec %) entries)]
     (if-let [refusal (some #(when (contains? % :yin.k/status) %) lifted)]
       refusal
       {:yin.k/pending (mapv :pending lifted),
        :yin.k/cells (:cells @found),
        :yin.k/requires (if (some :cell? lifted) requires {})}))))


(defn lift-pending
  "One pending entry of S7.4.3, lifted into its own self-contained
   cell table: `{:yin.k/pending data, :yin.k/cells {cell-id cell},
   :yin.k/requires requires}` on success, with the same `:yin.k/
   unsatisfied` and `:yin.k/undecodable` outcomes `lift-frame`
   gives. Frame assembly that shares cells across entries is
   `lift-frame`'s."
  ([serve! resources entry]
   (lift-pending serve! resources entry cursor-codec))
  ([serve! resources entry codec]
   (let [r (lift-frame serve! resources [entry] codec)]
     (if (contains? r :yin.k/status)
       r
       (assoc r :yin.k/pending (first (:yin.k/pending r)))))))


;; =============================================================================
;; Lower: back into resources and a wait-set entry
;; =============================================================================

(def ^:private fixed-resource-keys
  "The VM's fixed private resource keys -- the FFI call pair and the
   link pair. Batch-allocated keys never collide with them, and the
   lower installs nothing at them but what the engine's own dispatch
   reads there: the link pair, and a retained request's fixed local
   call-in key."
  #{vm/call-in-stream-key vm/call-out-stream-key
    vm/call-out-cursor-key module/link-request-resource
    module/link-response-resource})


(defn- fresh-key
  "The next private resource key `used` does not hold, claiming it:
   `:yin.k/k-1`, `:yin.k/k-2`, ... regenerating on any collision
   with the receiver's existing resources, the VM's fixed keys, or
   keys this batch already allocated."
  [used]
  (loop [n (inc (count @used))]
    (let [k (keyword "yin.k" (str "k-" n))]
      (if (contains? @used k)
        (recur (inc n))
        (do (swap! used conj k) k)))))


(defn- pending-markers
  "Every stream marker `pending` names beside its cells' -- the
   target of a put, both endpoints of a retained call, the pair of
   a link request."
  [pending]
  (case (:yin.k/reason pending)
    :put [(:yin.k/stream pending)]
    :ffi-request [(:yin.k/request pending)
                  (:yin.k/response pending)]
    (:link-request :link-response)
    [(:yin.k/request pending)
     (:yin.k/response pending)]
    []))


(defn- attach-all
  "One attach per distinct stream identity. The first attach that
   does not answer ok -- a reflection marked gone included -- refuses
   the lower as `:yin.k/unsatisfied` naming that identity. Returns
   {:handles {identity handle}}; `lower-frame` allocates each
   reflection its own collision-free resource key."
  [attach! markers]
  (let [by-id (reduce (fn [m mkr]
                        (update m (:dao.stream/identity mkr) #(or % mkr)))
                      {} markers)]
    (reduce-kv (fn [acc identity mkr]
                 (if-let [h (lower-marker attach! mkr)]
                   (assoc-in acc [:handles identity] h)
                   (reduced (unsatisfied identity))))
               {:handles {}}
               by-id)))


(defn- cell-resources
  "The fresh cursor entry each UCF cell lowers to, under the key
   `cell-keys` allocated for it: its carried position, on the
   reflection of the stream the cell names. Two references to one
   cell share the one entry, and one ref per cell keeps its own
   (S7.5.3); equal cell ids across separately lowered frames got
   different keys, so no two frames ever share an entry. A response
   route is no exception: it lowers under its own fresh key, never
   at a fixed key, so distinct routes stay distinct and no migrated
   route can ever misroute a new call."
  [streams cells cell-keys]
  (into {}
        (map (fn [[cid cell]]
               [(cell-keys cid)
                {:stream-id (streams (cell-identity cells cid)),
                 :cursor (:yin.k/position cell)}]))
        cells))


(defn- link-pair-resources
  "The engine's fixed link keys (`module/link-request-resource`,
   `module/link-response-resource`) mapped onto the reflections a
   lowered link wait uses: the engine's sweep retries a
   `:link-request` by appending at the fixed request key
   (`module/append-link-request`) and polls a `:link-response` at the
   fixed response key (`poll-link-response`), so a lowered link wait
   parks where the engine looks. One pair per task: the identity of
   each side rides the pending's own markers."
  [streams resources pendings]
  (reduce (fn [acc pending]
            (if (contains? #{:link-request :link-response}
                           (:yin.k/reason pending))
              (let [req-id (get-in pending [:yin.k/request
                                            :dao.stream/identity])
                    resp-id (get-in pending [:yin.k/response
                                             :dao.stream/identity])
                    req (get resources (streams req-id))
                    resp (get resources (streams resp-id))]
                (if (and req resp)
                  (assoc acc
                         module/link-request-resource req,
                         module/link-response-resource resp)
                  acc))
              acc))
          {}
          pendings))


(defn- lower-one
  "One pending of S7.4.3, lowered: `{:entry e}` -- the wait-set
   entry the receiver parks, the caller's register context `ctx`
   merged verbatim under the stream and cell fields the data
   rebuilds -- or an outcome carrying `:yin.k/status`, naming the
   stream identity where one is known. Every wait polls the key
   `cell-keys` allocated for its cell, reading the stream key
   `streams` allocated for the cell's reflection: migrated waits
   poll routes no new call shares. A retained request's retry
   writes through the request reflection's allocated key and stamps
   the carried response route (`:response-cursor`,
   `:response-stream`) for its restore; its `:datom` is the carried
   envelope itself, never a rebuild from the op/args views (the
   request protocol is open to further keys) -- a `:ffi-request`
   pend without `:yin.k/request-envelope` is malformed and refuses
   as `:yin.k/unsatisfied` naming the request identity."
  [pending ctx streams cells cell-keys]
  (case (:yin.k/reason pending)
    :next
    (let [cid (:yin.k/cell pending)]
      (if-let [sid (streams (cell-identity cells cid))]
        {:entry (merge ctx
                       {:reason :next,
                        :stream-id sid,
                        :cursor-ref {:type :cursor-ref,
                                     :id (cell-keys cid)}})}
        (unsatisfied (cell-identity cells cid))))

    :put
    (if-let [sid (streams (get-in pending [:yin.k/stream
                                           :dao.stream/identity]))]
      {:entry (merge ctx
                     {:reason :put,
                      :stream-id sid,
                      :datom (:yin.k/value pending)})}
      (unsatisfied (get-in pending [:yin.k/stream :dao.stream/identity])))

    :ffi
    (let [cid (:yin.k/cell pending)]
      (if-let [sid (streams (cell-identity cells cid))]
        {:entry (merge ctx
                       (cond-> {:reason :next,
                                :call-id (:yin.k/call-id pending),
                                :stream-id sid,
                                :cursor-ref {:type :cursor-ref,
                                             :id (cell-keys cid)}}
                         (contains? pending :yin.k/op)
                         (assoc :op (:yin.k/op pending))))}
        (unsatisfied (cell-identity cells cid))))

    :ffi-request
    (let [req-id (get-in pending [:yin.k/request
                                  :dao.stream/identity])
          resp-id (get-in pending [:yin.k/response
                                   :dao.stream/identity])
          envelope (:yin.k/request-envelope pending)]
      (if (some? envelope)
        (if-let [req (streams req-id)]
          (if-let [resp (streams resp-id)]
            {:entry (merge ctx
                           {:reason :put,
                            :request-sent true,
                            :call-id (:yin.k/call-id pending),
                            :op (:yin.k/request-op pending),
                            :stream-id req,
                            :datom envelope,
                            :response-cursor
                            (cell-keys (:yin.k/response-cell pending)),
                            :response-stream resp})}
            (unsatisfied resp-id))
          (unsatisfied req-id))
        (unsatisfied req-id)))

    (:link-request :link-response)
    (let [reason (:yin.k/reason pending)
          cid (:yin.k/cell pending)
          sid (streams (cell-identity cells cid))
          req (streams (get-in pending [:yin.k/request
                                        :dao.stream/identity]))]
      (if (and sid req)
        {:entry (merge ctx
                       (cond-> {:reason reason,
                                :link-id (:yin.k/link-id pending),
                                :name (:yin.k/name pending),
                                :request module/link-request-resource,
                                :response module/link-response-resource,
                                :cursor (:yin.k/position (get cells cid))}
                         (= :link-request reason)
                         (assoc :envelope (:yin.k/envelope pending))))}
        (unsatisfied (cell-identity cells cid))))

    :install
    {:entry (merge ctx
                   {:reason :install, :name (:yin.k/name pending)})}

    {:yin.k/status :yin.k/undecodable,
     :yin.k/reason (:yin.k/reason pending)}))


(defn- occupied-keys
  "The resource keys `receiver-resources` already holds: its own keys
   when it is the receiver's resource table, itself when it is a
   plain collection of keys."
  [receiver-resources]
  (if (map? receiver-resources)
    (keys receiver-resources)
    receiver-resources))


(defn lower-frame
  "A whole frame set of S7.4.3 pendings, lowered together as one
   batch: `{:entries [e ...], :resources table}` -- one private
   resource key allocated per distinct served identity and one per
   distinct UCF cell, two waiters on one cell lowering back into one
   shared entry and independent cells staying independent (S7.5.3);
   equal cell ids across separately lowered frames get different
   keys. `receiver-resources` is the receiver's existing resource
   table (or any collection of the resource keys it already holds):
   every allocated key is checked against it, against the VM's fixed
   resource keys, and against keys already allocated in the batch,
   regenerating on collision -- a cross-frame collision is
   structurally impossible, the lower cannot run without it. With
   the same `:yin.k/unsatisfied` and `:yin.k/undecodable` outcomes
   `lift-frame` gives. Where the engine resolves a wait at one of its
   own fixed resource keys, the lower parks the wait there: a link
   wait's pair under `module/link-request-resource` and
   `module/link-response-resource`. A retained request's retry
   writes through its carried request reflection's allocated key and
   stamps the carried response route (`:response-cursor`,
   `:response-stream`) for its restore. A `:ffi-request` pend
   without its verbatim envelope is malformed: the whole frame
   refuses before ANY attachment -- a refusal must mint no
   reflection. `contexts`, parallel to `pendings`, is each entry's
   register context, merged verbatim."
  [attach! pendings cells contexts receiver-resources]
  (if-let [refusal (some (fn [pending]
                           (when (and (= :ffi-request (:yin.k/reason pending))
                                      (nil? (:yin.k/request-envelope pending)))
                             (unsatisfied
                               (get-in pending
                                       [:yin.k/request :dao.stream/identity]))))
                         pendings)]
    refusal
    (let [ms (distinct (remove nil?
                               (concat (map :yin.k/stream (vals cells))
                                       (mapcat pending-markers pendings))))
          a (attach-all attach! ms)]
      (if (contains? a :yin.k/status)
        a
        (let [used (atom (into fixed-resource-keys
                               (occupied-keys receiver-resources)))
              handles (:handles a)
              streams (into {}
                            (map (fn [identity]
                                   [identity (fresh-key used)]))
                            (sort-by str (keys handles)))
              resources (into {}
                              (map (fn [[identity sid]]
                                     [sid (handles identity)]))
                              streams)
              cell-keys (into {}
                              (map (fn [cid] [cid (fresh-key used)]))
                              (sort-by str (keys cells)))
              lowered (mapv #(lower-one %1 %2 streams cells cell-keys)
                            pendings contexts)]
          (if-let [refusal (some #(when (contains? % :yin.k/status) %)
                                 lowered)]
            refusal
            {:entries (mapv :entry lowered),
             :resources (merge resources
                               (cell-resources streams cells cell-keys)
                               (link-pair-resources streams resources
                                                    pendings))}))))))


(defn lower-pending
  "One pending entry of S7.4.3, lowered: `{:entry e, :resources
   table}` -- the wait-set entry `ctx`'s registers ride on, and the
   fresh resource entries it polls through -- with the same
   `:yin.k/unsatisfied` and `:yin.k/undecodable` outcomes
   `lower-frame` gives. `receiver-resources` is the receiver's
   existing resource table (or key collection), as `lower-frame`'s:
   batch allocation regenerates against what is already installed.
   Frame assembly that shares cursor entries across entries is
   `lower-frame`'s."
  [attach! pending cells ctx receiver-resources]
  (let [r (lower-frame attach! [pending] cells [ctx] receiver-resources)]
    (if (contains? r :yin.k/status)
      r
      (-> r
          (assoc :entry (first (:entries r)))
          (dissoc :entries)))))
