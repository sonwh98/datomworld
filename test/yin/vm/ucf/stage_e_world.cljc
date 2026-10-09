(ns yin.vm.ucf.stage-e-world
  "M-next stage E: the world one life of a crash/partition scenario runs
   in, driven only through `yin.vm.ucf.compose` (linker-dht 14.2.4,
   UCF 7.11.1 clause 10 and the composition halves of clauses 3 and 8).

   A scenario is a sequence of lives over one root directory.  Every
   piece of state a later life may read lives on durable file media
   under that root -- the authority's ledger (a dao.stream.journal.file
   backend), every holder's progress and reply journal
   (`compose/file-journals`), the content store (a dao.jing.file
   content file, the node's carrier for bodies and code) and the side
   log of at-least-once external IO -- so a life is a fresh runtime:
   nothing in memory crosses from one life to the next.  The program
   and source streams are outside custody; each life rebuilds its own
   instance of them under their fixed identities from the life's spec,
   the way a receiver attaches a stream by its carried descriptor.

   A life is plain data (`run-life`'s spec) and so is everything it
   reports, so the same life runs in this process (the isolated-runtime
   mode every lane has, Dart's only form) or inside a separate operating
   system process (`yin.vm.ucf.stage-e-peer`, the JVM and Node process
   transport) and its report is compared as data either way.

   Kills.  Every durable medium is wrapped mortal: a named cut (the
   `cuts` table) fires at one exact boundary -- before or after a given
   journal frame, before or after a given content-store put, or after a
   composition tick whose state matches -- and from then on the life is
   dead.  Inside a peer process the cut prints its event and blocks
   until the parent destroys the process (SIGKILL); in this process it
   marks every medium dead (no further durable write happens) and
   unwinds, and the runner releases the locks the dead runtime held --
   the only thing an operating system does for a killed process."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.file :as jing-file]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file-journal]
            [dao.stream.memory-log :as memory-log]
            #?@(:cljd [["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]]
                :clj [[clojure.java.io :as io]])
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.compose :as compose]
            [yin.vm.ucf.lift-support :as s]))


;; =============================================================================
;; Plain data
;; =============================================================================


(defn- host-bytes?
  [x]
  #?(:clj (bytes? x)
     :cljs (instance? js/Uint8Array x)
     :cljd (instance? typed/Uint8List x)))


(defn ->data
  "`x` as plain data every host prints and reads back the same: maps,
   vectors, sets, scalars.  Host bytes become their blake3 digest
   ({::bytes hex}), so two byte strings compare equal exactly when their
   bytes are; anything else that is not data (a handle, a function, a
   machine) becomes {::opaque true}."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
        (number? x) (uuid? x))
    x

    (host-bytes? x) {::bytes (jing/digest-bytes :blake3 x)}
    (map? x) (into {} (map (fn [[k v]] [(->data k) (->data v)])) x)
    (vector? x) (mapv ->data x)
    (set? x) (into #{} (map ->data) x)
    (sequential? x) (mapv ->data x)
    :else {::opaque true}))


(defn digest-of
  "The digest form ->data gives host bytes `bs`."
  [bs]
  {::bytes (jing/digest-bytes :blake3 bs)})


;; =============================================================================
;; Scratch roots
;; =============================================================================


(defn temp-root
  "A fresh scratch root under target/."
  []
  (str "target/stage-e-" (random-uuid)))


(defn cleanup-root!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (letfn [(delete-recursively!
                    [f]
                    (when (.isDirectory f)
                      (doseq [child (.listFiles f)]
                        (delete-recursively! child)))
                    (.delete f))]
            (delete-recursively! (io/file dir)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- ensure-dir!
  [dir]
  #?(:cljd (let [d (dart-io/Directory. dir)]
             (when-not (.existsSync d) (.createSync d .recursive true)))
     :clj (.mkdirs (io/file dir))
     :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true})))


(defn paths
  "The durable media of the world over `root`."
  [root]
  {:authority (str root "/authority")
   :journals (str root "/journals")
   :store (str root "/store.jing")
   :side (str root "/side")})


(defn destroy-authority!
  "Permanently lose the authority's state under `root`: its content file
   is overwritten with bytes that are no content log, the way a lost
   disk leaves it.  No reopen can recover a ledger from it."
  [root]
  (let [path (str (:authority (paths root)) "/" file-journal/content-name)]
    #?(:cljd (.writeAsStringSync (dart-io/File. path) "lost authority state")
       :clj (spit path "lost authority state")
       :cljs (.writeFileSync (js/require "fs") path "lost authority state"))))


;; =============================================================================
;; The life context: events, counters, the cut and the kill
;; =============================================================================


(defn context
  "A life's context.  `emit!` takes each event map as it happens (a peer
   prints it at once); `at-cut` is called when the life's cut fires: a
   peer's never returns -- the parent kills the process inside it -- and
   the in-process runner's returns so the life can unwind dead."
  [{:keys [cut fail emit! at-cut]}]
  {:cut cut
   :fail fail
   :failed (atom nil)
   :emit! (or emit! (fn [_]))
   :at-cut (or at-cut (fn []))
   :dead (atom false)
   :fired (atom nil)
   :hits (atom {})
   :attaches (atom 0)
   :observes (atom 0)
   :opened (atom [])})


(defn killed?
  [e]
  (true? (::killed (ex-data e))))


(defn- dead-throw!
  []
  (throw (ex-info "stage E: a dead runtime wrote nothing" {::killed true})))


(defn- fire!
  [ctx at]
  (reset! (:fired ctx) at)
  (reset! (:dead ctx) true)
  ((:emit! ctx) {:event :cut :cut (:cut ctx) :at at})
  ((:at-cut ctx))
  (throw (ex-info "stage E: killed at a cut" {::killed true :cut (:cut ctx)})))


;; =============================================================================
;; The named cuts
;; =============================================================================


(defn- record-is
  [journal-kind action]
  (fn [r]
    (and (= journal-kind (:yin.k/journal r))
         (or (nil? action) (= action (:yin.k/action r))))))


(defn- projection-of
  [c]
  (when-some [a (:authority c)]
    (authority/projection a)))


(defn- journal-records-of
  "Holder `h`'s whole progress journal, read through a fresh open of its
   backend (the restart's view)."
  [c h]
  (let [backend (get-in c [:progress h :backend])
        j (if (some? backend)
            (:dao.stream/handle (journal/open! backend nil))
            (compose/journal-of c h))]
    (if (nil? j)
      []
      (loop [cursor (:dao.stream/cursor (stream/cursor j :dao.stream/oldest))
             records []]
        (let [answer (stream/next j cursor)]
          (if (= :dao.stream/ok (:dao.stream/outcome answer))
            (recur (:dao.stream/cursor answer) (conj records (:dao.stream/value answer)))
            records))))))


(defn- holder-records-match?
  [c h pred]
  (boolean (some pred (journal-records-of c h))))


(defn- exit-occurrence
  [c h]
  (get-in c [:holders h :exit :occurrence]))


(defn- read-values
  [h]
  (loop [cursor (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         values []]
    (let [r (stream/next h cursor)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count values) 1000))
        (recur (:dao.stream/cursor r) (conj values (:dao.stream/value r)))
        values))))


(defn inbound-of
  "The requests of `kind` (every request when nil) that reached holder
   `h`'s inbound in this life, in stream order."
  [c h kind]
  (if-some [inbound (get-in c [:inbounds h])]
    (filterv #(or (nil? kind) (= kind (:yin.k/request %))) (read-values inbound))
    []))


(defn- admission-commit?
  "Whether ledger record `r` is the transaction that commits an admitted
   effect: one of its datoms is the :yin.k/admitted custody fact."
  [r]
  (boolean (some (fn [d]
                   (and (sequential? d)
                        (= :yin.k/custody (nth d 1 nil))
                        (= :yin.k/admitted (nth d 2 nil))))
                 (get-in r [:dao.space/transaction :datoms]))))


(def cuts
  "Every named cut a scenario may ask for: where it fires and when.
   :site is a journal ([:progress h] or [:reply h]), :authority, :store
   or :tick; :when is :before or :after the matched write (a tick cut
   fires after the tick whose composition matches); :match is a
   predicate over the decoded journal record, the put ({:address a}) or
   the composition; :nth counts matches (default 1)."
  {;; ---- row 7: completion
   :row7/after-successor-append
   {:site :tick
    :match (fn [c]
             (let [s (exit-occurrence c "h1")]
               (and (some? s)
                    (holder-records-match?
                      c "h1" #(and ((record-is :yin.k/attempt :yin.k/offer) %)
                                   (= s (:yin.k/occurrence %)))))))}
   :row7/after-resumed-report
   {:site :tick
    :match (fn [c]
             (let [l (get-in c [:holders "h1" :lease])]
               (and (some? l)
                    (some? (get-in (projection-of c) [:leases l :yin.k/result])))))}
   :row7/after-release-append
   {:site :tick
    :match (fn [c]
             (holder-records-match?
               c "h1" #(and ((record-is :yin.k/attempt :yin.k/release) %)
                            (= :dao.stream/ok (:yin.k/append %)))))}
   :row7/after-closure
   {:site :tick
    :match (fn [c]
             (let [o (get-in c [:holders "h1" :occurrence])]
               (and (some? o)
                    (some? (get-in (projection-of c) [:occurrences o :yin.k/closed])))))}
   :row7/while-running
   {:site :tick
    :match (fn [c] (= :running (get-in c [:holders "h1" :phase])))}

   ;; ---- row 4 and clause 8: around the atomic effect commitment (the
   ;; ledger transaction that records the admitted effect)
   :row4/before-commit
   {:site :authority :when :before :match admission-commit?}
   :row4/after-commit
   {:site :authority :when :after :match admission-commit?}
   :row4/before-result-append
   {:site [:reply "h1"] :when :before
    :match (fn [r] (= :yin.k/admit (:yin.k/reply r)))}
   :row4/admit-uncarried
   {:site :tick
    :match (fn [c] (seq (inbound-of c "h1" :yin.k/admit)))}

   ;; ---- row 5: the input record
   :row5/input-uncarried
   {:site :tick
    :match (fn [c] (seq (inbound-of c "h1" :yin.k/input)))}
   :row5/input-carried
   {:site [:reply "h1"] :when :after
    :match (fn [r] (= :yin.k/input (:yin.k/reply r)))}

   ;; ---- row 2 and the D14 journal brackets: the export's phases
   :export/after-minted
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/minted nil)}
   :export/after-serve-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/serve)}
   :export/after-serve-ack
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/ack :yin.k/serve)}
   :export/after-store-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/store)}
   :export/store-put-before
   {:site :store :when :before :match (fn [_] true)}
   :export/store-put-after
   {:site :store :when :after :match (fn [_] true)}
   :export/after-store-ack
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/ack :yin.k/store)}
   :export/before-fenced
   {:site [:progress "h1"] :when :before :match (record-is :yin.k/fenced nil)}
   :export/after-fenced
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/fenced nil)}
   :export/after-offer-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/offer)}
   :export/after-offer-attempt
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/attempt :yin.k/offer)}
   :export/after-offer-ack
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/ack :yin.k/offer)}

   ;; ---- the inbox retention machine (D15a) and the grant bracket
   :inbox/before-retention
   {:site [:progress "h1"] :when :before :match (record-is :yin.k/inbox nil)}
   :inbox/after-retention
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/inbox nil)}
   :journal/before-proposal-attempt
   {:site [:progress "h1"] :when :before :match (record-is :yin.k/attempt :yin.k/proposal)}
   :journal/after-proposal-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/proposal)}
   :journal/after-grant-ack
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/ack :yin.k/grant)}
   :journal/after-resumed-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/resumed)}
   :journal/after-release-intent
   {:site [:progress "h1"] :when :after :match (record-is :yin.k/intent :yin.k/release)}

   ;; ---- clause 8: after the first commit's reply stands
   :clause8/committed
   {:site :tick
    :match (fn [c]
             (some #(and (= :yin.k/admit (:yin.k/reply %))
                         (= :committed (get-in % [:yin.k/answer :yin.k/admission])))
                   (if-some [j (get-in c [:reply-journals "h1" :journal])]
                     (read-values j)
                     [])))}

   ;; ---- row 6: a lost reply (the process dies before the front's
   ;; append of the proposal's reply)
   :row6/proposal-reply-lost
   {:site [:reply "h1"] :when :before
    :match (fn [r] (= :yin.k/proposal (:yin.k/reply r)))}

   ;; ---- the freeze's second stored object (the body)
   :export/body-put-before
   {:site :store :when :before :match (fn [_] true) :nth 2}
   :export/body-put-after
   {:site :store :when :after :match (fn [_] true) :nth 2}

   ;; ---- clause 3: between assign and discharge
   :seq/after-assign
   {:site :tick
    :match (fn [c] (seq (inbound-of c "h1" :yin.k/admit)))}})


(defn- cut-spec
  [ctx]
  (when-some [k (:cut ctx)]
    (or (get cuts k)
        (throw (ex-info "stage E: unknown cut" {:cut k})))))


(defn- hit!
  "Whether the life's cut matches this write at `site` (counted against
   :nth), answering :before, :after or nil."
  [ctx site subject]
  (let [spec (cut-spec ctx)]
    (when (and (some? spec)
               (nil? @(:fired ctx))
               (= site (:site spec))
               ((:match spec) subject))
      (let [n (get (swap! (:hits ctx) update site (fnil inc 0)) site)]
        (when (= n (or (:nth spec) 1))
          (or (:when spec) :after))))))


;; =============================================================================
;; Mortal media
;; =============================================================================


(defn- frame-record
  [bs]
  (try
    (::journal/value (cbor/decode bs))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- fail-hit!
  "Whether the life's uncertain-append failure ({:like cut-name
   :persist? bool}) matches this write at `site`: the first match only."
  [ctx site subject]
  (when-some [{:keys [like]} (:fail ctx)]
    (let [spec (get cuts like)]
      (when (and (nil? @(:failed ctx))
                 (= site (:site spec))
                 ((:match spec) subject))
        (reset! (:failed ctx) {:site site :like like})
        true))))


(defn mortal-backend
  "Journal backend `b` at `site`, mortal under `ctx`: once the life is
   dead every frame write throws having written nothing, and the life's
   cut, when it names this site, fires before or after its frame."
  [ctx site b]
  (swap! (:opened ctx) conj [:backend b])
  (let [write! (::journal/write-frame! b)
        truncate! (::journal/truncate! b)]
    (assoc b
           ::journal/write-frame!
           (fn [bs]
             (when @(:dead ctx) (dead-throw!))
             (when (fail-hit! ctx site (frame-record bs))
               ;; an uncertain append: the frame lands or not, and the
               ;; writer is answered a throw either way -- the process
               ;; lives on, its journal's answer unknown
               (when (:persist? (:fail ctx)) (write! bs))
               ((:emit! ctx) {:event :uncertain :site site
                              :persisted? (boolean (:persist? (:fail ctx)))
                              :record (->data (frame-record bs))})
               (throw (ex-info "stage E: an uncertain append" {::uncertain true})))
             (let [hit (hit! ctx site (frame-record bs))]
               (when (= :before hit)
                 (fire! ctx {:site site :when :before
                             :record (->data (frame-record bs))}))
               (let [r (write! bs)]
                 (when (= :after hit)
                   (fire! ctx {:site site :when :after
                               :record (->data (frame-record bs))}))
                 r)))
           ::journal/truncate!
           (fn [n]
             (when @(:dead ctx) (dead-throw!))
             (truncate! n)))))


(defn mortal-store
  "Content store `base`, mortal under `ctx`, its puts cut at :store; its
   gets answer the default for every address in `unreadable` (an
   accepted checkpoint whose bytes cannot be read)."
  [ctx base unreadable]
  (swap! (:opened ctx) conj [:store base])
  (let [put! (:put-bytes-fn base)
        get! (:get-bytes-fn base)]
    (assoc base
           :put-bytes-fn
           (fn [address bs]
             (when @(:dead ctx) (dead-throw!))
             (let [hit (hit! ctx :store {:address address})]
               (when (= :before hit)
                 (fire! ctx {:site :store :when :before :address address}))
               (let [r (put! address bs)]
                 (when (= :after hit)
                   (fire! ctx {:site :store :when :after :address address}))
                 r)))
           :get-bytes-fn
           (fn
             ([address] (get! address))
             ([address default]
              (if (contains? unreadable address)
                default
                (get! address default)))))))


(defn- open-file-backend!
  [dir]
  (ensure-dir! dir)
  (let [r (file-journal/backend! dir)]
    (or (::file-journal/backend r)
        (throw (ex-info "stage E: a durable journal directory refused"
                        {:dir dir :answer r})))))


(defn- mortal-journals
  "compose/file-journals over `root`, every backend it opens mortal at
   [:progress h] or [:reply h]."
  [ctx root]
  (let [base (compose/file-journals root)]
    (assoc base
           :progress-backend!
           (fn [h] (mortal-backend ctx [:progress h] ((:progress-backend! base) h)))
           :reply-backend!
           (fn [h] (mortal-backend ctx [:reply h] ((:reply-backend! base) h))))))


;; =============================================================================
;; Streams outside custody
;; =============================================================================


(defn scenario-stream
  "A stream outside custody, rebuilt by every life under its fixed
   `identity`: `values` at integer positions from zero, positions below
   `floor` evicted (their reads answer gap with the floor as the
   successor cursor).  Integer positions make a position lifted from one
   life's instance a valid cursor on the next life's."
  [identity values floor]
  (let [vs (atom (vec values))]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor floor})

      (next
        [_ cursor]
        (let [values @vs]
          (cond
            (not (and (integer? cursor) (<= 0 cursor (count values))))
            {:dao.stream/outcome :dao.stream/invalid-cursor}
            (< cursor floor)
            {:dao.stream/outcome :dao.stream/gap :dao.stream/cursor floor}
            (< cursor (count values))
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/value (nth values cursor)
             :dao.stream/cursor (inc cursor)}
            :else {:dao.stream/outcome :dao.stream/blocked})))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (swap! vs conj x)
        {:dao.stream/outcome :dao.stream/ok}))))


(defn journal-stream
  "A durable stream over journal handle `j` under the fixed `identity`:
   integer positions over the journal's own, so every life reads the
   whole history any life appended -- the side log of at-least-once
   external IO."
  [identity j]
  (let [jid (:dao.stream/identity (stream/descriptor j))]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

      (next
        [_ cursor]
        (if-not (and (integer? cursor) (<= 0 cursor))
          {:dao.stream/outcome :dao.stream/invalid-cursor}
          (let [r (stream/next j {:dao.stream.memory-log/identity jid
                                  :dao.stream.memory-log/position cursor})]
            (case (:dao.stream/outcome r)
              :dao.stream/ok {:dao.stream/outcome :dao.stream/ok
                              :dao.stream/value (:dao.stream/value r)
                              :dao.stream/cursor (inc cursor)}
              {:dao.stream/outcome :dao.stream/blocked}))))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (stream/append! j x)))))


(defn- answering-inbound
  "A log-shaped inbound whose appends answer `@outcome`: :dao.stream/ok
   lands the request; any other outcome lands nothing (the carrier full,
   refusing, or failing under the send)."
  [identity outcome]
  (let [values (atom [])]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

      (next
        [_ cursor]
        (let [vs @values]
          (cond
            (not (and (integer? cursor) (<= 0 cursor (count vs))))
            {:dao.stream/outcome :dao.stream/invalid-cursor}
            (< cursor (count vs))
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/value (nth vs cursor)
             :dao.stream/cursor (inc cursor)}
            :else {:dao.stream/outcome :dao.stream/blocked})))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (let [o @outcome]
          (when (= :dao.stream/ok o) (swap! values conj x))
          {:dao.stream/outcome o})))))


(defn- held-inbound
  "A log-shaped inbound whose reads answer :blocked while `held` (an
   atom) is truthy: the holder's requests land, but the authority's
   front cannot read them -- the holder partitioned from its
   authority.  Healed, the front reads every request that landed."
  [identity held]
  (let [values (atom [])]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

      (next
        [_ cursor]
        (if @held
          {:dao.stream/outcome :dao.stream/blocked}
          (let [vs @values]
            (cond
              (not (and (integer? cursor) (<= 0 cursor (count vs))))
              {:dao.stream/outcome :dao.stream/invalid-cursor}
              (< cursor (count vs))
              {:dao.stream/outcome :dao.stream/ok
               :dao.stream/value (nth vs cursor)
               :dao.stream/cursor (inc cursor)}
              :else {:dao.stream/outcome :dao.stream/blocked}))))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (swap! values conj x)
        {:dao.stream/outcome :dao.stream/ok}))))


(defn- log-writer
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


;; =============================================================================
;; Machines and holder seams
;; =============================================================================


(def prog-id "prog-c")
(def source-id "src-s")
(def side-id "side-x")


(defn- parked-reader-over
  "A real gated machine over `source`, parked on its first read: one
   more delivered value and it halts with :done."
  [source]
  (let [m0 (s/load-ast (s/new-machine)
                       (s/then {:type :stream/next, :source (s/v 'c)}
                               (s/lit :done)))
        [sref m1] (engine/attach-resource m0 source)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)]
    (vm/run (assoc m2 :store {'c cref} :yin.k/gate :running))))


(defn- reading-writer
  "A real gated machine over `source`, parked on its first read: the
   value it reads is written to the enrolled `target`, then :side to
   `side`, then it halts with :done."
  [source target side]
  (let [m0 (s/load-ast (s/new-machine)
                       (s/let1 'v (s/next-of (s/v 'c))
                               (s/then {:type :stream/put, :target (s/v 'w),
                                        :val (s/v 'v)}
                                       (s/then {:type :stream/put,
                                                :target (s/v 'x),
                                                :val (s/lit :side)}
                                               (s/lit :done)))))
        [sref m1] (engine/attach-resource m0 source)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
        [wref m3] (engine/attach-resource m2 target)
        [xref m4] (engine/attach-resource m3 side)]
    (vm/run (assoc m4 :store {'c cref, 'w wref, 'x xref}
                   :yin.k/gate :running))))


(defn- attach-over
  [ctx table]
  (fn [descriptor]
    (swap! (:attaches ctx) inc)
    (if-some [h (get table (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


(defn- observe-over
  [ctx]
  (fn [h op arg]
    (swap! (:observes ctx) inc)
    (case op
      :next (stream/next h arg)
      :cursor (stream/cursor h arg))))


(defn- same-id-server
  []
  (fn [h]
    (let [id (:dao.stream/identity (stream/descriptor h))]
      {:dao.stream/identity id
       :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                            :dao.stream/identity id}})))


;; =============================================================================
;; Opening a life's world
;; =============================================================================


(defn- composition-config
  [w mode]
  (cond-> {:mode mode
           :renewal-interval {:s 10}
           :store (:store w)
           :diagnostics (:diagnostics w)
           :medium "stage-e"
           :clock (fn [] {:s @(:clock w)})}
    (= :exclusive mode) (assoc :failure-model :process-crash
                               :backend (:authority-backend w)
                               :duration {:s 30}
                               :journals (:journals w))))


(defn- world-table
  "The holder table and protection of the life's world kind."
  [w]
  (case (:kind w)
    :parked {:table (if (= :none (:attach w)) {} {prog-id (:prog w)})
             :protection {prog-id :at-least-once}}
    :rows {:table (cond-> {source-id (:source w) side-id (:side w)}
                    (:target w) (assoc (:target w) (:reader w)))
           :protection (cond-> {source-id :at-least-once side-id :at-least-once}
                         (:target w) (assoc (:target w) :enrolled))}))


(defn- holder-seams
  [ctx w]
  (let [{:keys [table protection]} (world-table w)]
    {:protection protection
     :receiver (s/new-machine)
     :attach! (attach-over ctx table)
     :observe! (observe-over ctx)
     :serve! (if (= :parked (:kind w))
               (fn [_h]
                 {:dao.stream/identity prog-id
                  :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                                       :dao.stream/identity prog-id}})
               (same-id-server))}))


(defn- source-machine
  [w]
  (case (:kind w)
    :parked (parked-reader-over (scenario-stream prog-id [] 0))
    :rows (reading-writer (:source w) (:reader w) (:side w))))


(defn open-world!
  "Open the life's world over `spec`: the durable media, mortal under
   `ctx`, and the composition over them.  Answers the world map with
   :c the open composition, or :refusal the composition's data refusal."
  [ctx spec]
  (let [{:keys [root kind mode]} spec
        mode (or mode :exclusive)
        p (paths root)
        _ (ensure-dir! root)
        partitioned (atom false)
        authority-backend (when (= :exclusive mode)
                            (let [b (mortal-backend ctx :authority
                                                    (open-file-backend! (:authority p)))
                                  write! (::journal/write-frame! b)]
                              ;; the protected consumer partitioned from
                              ;; its authority: every frame write fails
                              ;; while the flag stands
                              (assoc b ::journal/write-frame!
                                     (fn [bs]
                                       (if @partitioned
                                         (throw (ex-info "stage E: the authority is partitioned" {}))
                                         (write! bs))))))
        store (mortal-store ctx (jing-file/create-content-file (:store p))
                            (set (:unreadable spec)))
        side-backend (when (= :rows kind)
                       (mortal-backend ctx :side (open-file-backend! (:side p))))
        side (when side-backend
               (journal-stream side-id (:dao.stream/handle (journal/open! side-backend nil))))
        w {:root root
           :kind kind
           :mode mode
           :attach (:attach spec)
           :clock (atom (or (:clock spec) 1))
           :store store
           :diagnostics (log-writer)
           :authority-backend authority-backend
           :side-backend side-backend
           :journals (if (= :exclusive mode)
                       (mortal-journals ctx (:journals p))
                       (compose/memory-journals (atom {})))
           :prog (scenario-stream prog-id (or (:prog spec) []) 0)
           :source (scenario-stream source-id (or (:source spec) ["A"])
                                    (or (:evict-floor spec) 0))
           :side side
           :partitioned partitioned
           :carrier (atom :dao.stream/ok)
           :held (atom false)
           :inbound (:inbound spec)}
        opened (compose/open! (composition-config w mode))
        c (:yin.vm.ucf.compose/composition opened)]
    (if (nil? c)
      (assoc w :refusal (->data opened))
      (let [w (assoc w :c c)]
        (if (= :rows kind)
          (let [i (or (:target spec)
                      (let [e (compose/enroll! c)]
                        ((:emit! ctx) {:event :enrolled :target (:yin.k/target e)})
                        (:yin.k/target e)))]
            (assoc w :target i :reader (compose/target-reader c i)))
          w)))))


(defn release-dead!
  "What the operating system does for a killed process, done for a life
   killed in this process: every file backend and the store it opened
   released, nothing written."
  [ctx]
  (doseq [[kind x] @(:opened ctx)]
    (try
      (case kind
        :backend (when (contains? x ::file-journal/close!) (file-journal/close! x))
        :store ((:close-fn x)))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn close-world!
  "Release everything a live world holds: the composition (its journal
   backends), the authority backend, the side log and the store."
  [w]
  (let [safe (fn [f]
               (try (f) (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))]
    (when-some [c (:c w)] (safe #(compose/close! c)))
    (when-some [b (:authority-backend w)] (safe #(file-journal/close! b)))
    (when-some [b (:side-backend w)] (safe #(file-journal/close! b)))
    (when-some [close! (get-in w [:store :close-fn])] (safe close!))
    nil))


;; =============================================================================
;; Reading a live world
;; =============================================================================


(defn- journal-values
  [j]
  (if (nil? j)
    []
    (read-values j)))


(defn replies-of
  "Every reply the composition's front wrote into holder `h`'s reply
   journal (both tenures': the journal is durable)."
  [c h]
  (journal-values (get-in c [:reply-journals h :journal])))


(defn holder-summary
  [st]
  (->data (merge (select-keys st [:phase :status :detail :occurrence :lease])
                 {:epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
                  :next-op-seq (get-in st [:machine :yin.k/custody :yin.k/next-op-seq])
                  :stopped? (:stopped? st)
                  :machine? (some? (:machine st))})))


(defn snapshot
  "Everything a check may read of the live world, as data."
  [ctx w]
  (let [c (:c w)
        hs (sort (keys (:holders c)))]
    {:holders (into {} (map (fn [h] [h (holder-summary (get-in c [:holders h]))])) hs)
     :policy (:yin.k/policy c)
     :projection (->data (projection-of c))
     :journals (into {} (map (fn [h] [h (->data (journal-records-of c h))])) hs)
     :replies (into {} (map (fn [h] [h (->data (replies-of c h))])) hs)
     :inbound (into {} (map (fn [h] [h (->data (inbound-of c h nil))])) hs)
     :targets (if-some [i (:target w)]
                {:target (->data i)
                 :values (->data (read-values (compose/target-reader c i)))}
                {})
     :outcomes (if-some [r (compose/outcome-reader c)]
                 (->data (read-values r))
                 [])
     :side (if-some [side (:side w)] (->data (read-values side)) [])
     :fences (into {}
                   (keep (fn [r]
                           (when (= :yin.k/fenced (:yin.k/journal r))
                             (let [a (:yin.k/address r)
                                   bs ((:get-bytes-fn (:store w)) a nil)]
                               [a (when bs (digest-of bs))]))))
                   (mapcat #(journal-records-of c %) hs))
     :diagnostics (->data (read-values (:diagnostics w)))
     :arbitration (->data (compose/arbitration c))
     :counts {:attaches @(:attaches ctx) :observes @(:observes ctx)}
     :clock @(:clock w)}))


;; =============================================================================
;; The life's program: plain-data operations over the composition
;; =============================================================================


(defn- durable-records
  "Holder `h`'s progress journal: through the composition when this life
   composes `h`, else read from its durable directory (a holder a
   previous life ran)."
  [w h]
  (if (contains? (get-in w [:c :holders]) h)
    (journal-records-of (:c w) h)
    (let [b (open-file-backend! (str (:journals (paths (:root w))) "/" h "-progress"))]
      (try
        (journal-values (:dao.stream/handle (journal/open! b nil)))
        (finally (file-journal/close! b))))))


(defn- checkpoint-from
  "The checkpoint a candidate is added over, read from the durable
   journal of `of` and the store: :fence the source's export, :successor
   the exit's successor, or an explicit {:address a}."
  [w {:keys [kind of address]}]
  (let [records (durable-records w of)
        fence (fn [o]
                (some (fn [r]
                        (when (and (= :yin.k/fenced (:yin.k/journal r))
                                   (or (nil? o) (= o (:yin.k/occurrence r))))
                          r))
                      records))
        address (case kind
                  :fence (:yin.k/address (fence nil))
                  :successor (let [mint (last (filter
                                                (fn [r]
                                                  (and (= :yin.k/minted (:yin.k/journal r))
                                                       (= :yin.k/successor (:yin.k/role r))))
                                                records))]
                               (:yin.k/address (fence (:yin.k/occurrence mint))))
                  :variant (:address (:variant w))
                  :address address)
        bs (when address ((:get-bytes-fn (:store w)) address nil))]
    (when (nil? bs)
      (throw (ex-info "stage E: the checkpoint is absent from the store"
                      {:checkpoint {:kind kind :of of :address (->data address)}})))
    {:address address :bytes bs}))


(defn- inbound-for
  "Holder `h`'s inbound, when the life's spec names one: :answering, a
   carrier whose appends answer the life's carrier outcome; :held, a
   stream the front cannot read while the life holds the partition."
  [w h]
  (case (get (:inbound w) h)
    :answering {:inbound (answering-inbound (str "inbound-" h) (:carrier w))}
    :held {:inbound (held-inbound (str "inbound-" h) (:held w))}
    nil))


(defn- offer-variant!
  "A second encoding of the occurrence holder `h` fenced: the same body
   in different canonical bytes (another id counter), put in the store
   and offered through `h`'s own inbound.  Answers {:address :bytes}."
  [w h]
  (let [{:keys [address bytes]} (checkpoint-from w {:kind :fence :of h})
        body (cbor/decode bytes)
        o (get-in body [:yin.k/custody :yin.k/occurrence])
        v-bytes (cbor/encode (assoc body :yin.k/id-counter 9))
        v-address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 v-bytes)))]
    ((:put-bytes-fn (:store w)) v-address v-bytes)
    (stream/append! (get-in w [:c :inbounds h])
                    {:yin.k/request :yin.k/offer
                     :yin.k/request-id [:stage-e/variant (str address)]
                     :yin.k/id v-address
                     :yin.k/bytes v-bytes
                     :yin.k/medium "stage-e"})
    {:address v-address :bytes v-bytes :occurrence o}))


(defn- holder-config
  [ctx w extra]
  (merge (holder-seams ctx w) extra))


(defn- add-source
  [ctx w h]
  (update w :c compose/source h (holder-config ctx w (merge {:machine (source-machine w)}
                                                            (inbound-for w h)))))


(defn- add-candidate
  [ctx w h checkpoint opts]
  (let [{:keys [address bytes]} (checkpoint-from w checkpoint)
        seams (holder-config ctx w (merge {:bytes bytes :address address}
                                          (inbound-for w h)))
        seams (if (= :none (:attach opts))
                (assoc seams :attach! (fn [_] {:dao.stream/outcome :dao.stream/not-found}))
                seams)]
    (update w :c compose/holder h seams)))


(defn- dead?
  [ctx]
  @(:dead ctx))


(defn- tick!
  "One tick of `f` over the world's composition; the life's tick cut is
   checked after it.  A dead life ends here."
  [ctx w f]
  (let [c' (f (:c w))
        w (assoc w :c c')]
    (when (dead? ctx) (dead-throw!))
    (let [spec (cut-spec ctx)]
      (when (and (some? spec) (= :tick (:site spec)) (nil? @(:fired ctx))
                 ((:match spec) c'))
        (fire! ctx {:site :tick
                    :holders (into {} (map (fn [[h st]] [h (holder-summary st)])) (:holders c'))
                    :inbound (into {} (map (fn [h] [h (->data (inbound-of c' h nil))]))
                                   (keys (:holders c')))})))
    w))


(def ^:private planes
  {:step compose/step
   :control compose/control-step
   :program compose/program-step})


(defn- phase?
  [w h phase]
  (= phase (get-in w [:c :holders h :phase])))


(defn- until
  "Ticks of `plane` until `pred` holds of the world or `n` are spent;
   answers [world held?]."
  [ctx w plane pred n]
  (loop [w w k 0]
    (if (pred w)
      [w true]
      (if (>= k n)
        [w false]
        (recur (tick! ctx w (get planes plane)) (inc k))))))


(defn- admit-request
  [rid target envelope]
  {:yin.k/request :yin.k/admit
   :yin.k/request-id rid
   :yin.k/target target
   :yin.k/fenced-envelope envelope})


(defn- envelope
  [lease epoch op-id value]
  {:yin.k/envelope :yin.k/fenced-v1
   :yin.k/incarnation lease
   :yin.k/epoch epoch
   :yin.k/op-id op-id
   :yin.k/value value})


(defn- holder-binding
  [w h]
  (let [st (get-in w [:c :holders h])]
    {:lease (:lease st)
     :epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
     :occurrence (:occurrence st)}))


(defn- resolve-arg
  "Operation arguments may name a holder's live binding: [:binding h k]."
  [w x]
  (if (and (vector? x) (= :binding (first x)))
    (get (holder-binding w (nth x 1)) (nth x 2))
    x))


(defn- admit!
  "Append an authored admit to holder `h`'s inbound and run control ticks
   until its reply lands (or `n` are spent)."
  [ctx w h {:keys [rid target lease epoch op-id value await?]}]
  (let [target (cond (= :other target) (:other w)
                     (some? target) (resolve-arg w target)
                     :else (:target w))
        lease (resolve-arg w lease)
        epoch (resolve-arg w epoch)
        op-id (into {} (map (fn [[k v]] [k (resolve-arg w v)])) op-id)
        n (count (replies-of (:c w) h))]
    (stream/append! (get-in w [:c :inbounds h])
                    (admit-request rid target (envelope lease epoch op-id value)))
    (if (false? await?)
      (tick! ctx w compose/control-step)
      (first (until ctx w :control #(> (count (replies-of (:c %) h)) n) 20)))))


(defn- run-op
  "One operation of a life's program; answers [world report-additions]."
  [ctx w [op & args]]
  (case op
    :source [(add-source ctx w (first args)) nil]
    :candidate (let [[h checkpoint opts] args]
                 [(add-candidate ctx w h checkpoint opts) nil])
    :until (let [[h phase n plane] args
                 [w held?] (until ctx w (or plane :step) #(phase? % h phase) n)]
             [w {:until [[h phase held?]]}])
    :until-status (let [[h status n] args
                        [w held?] (until ctx w :step
                                         #(= status (get-in % [:c :holders h :status])) n)]
                    [w {:until [[h status held?]]}])
    :until-inbound (let [[h kind n plane] args
                         [w held?] (until ctx w (or plane :step)
                                          #(seq (inbound-of (:c %) h kind)) n)]
                     [w {:until [[h kind held?]]}])
    :until-reply (let [[h kind n plane] args
                       [w held?] (until ctx w (or plane :step)
                                        #(some (fn [r] (= kind (:yin.k/reply r)))
                                               (replies-of (:c %) h))
                                        n)]
                   [w {:until [[h kind held?]]}])
    :until-some (let [[phase n] args
                      [w held?] (until ctx w :step
                                       (fn [w]
                                         (some #(= phase (:phase %))
                                               (vals (get-in w [:c :holders]))))
                                       n)]
                  [w {:until [[:some phase held?]]}])
    :until-journal (let [[h kind action n plane] args
                         [w held?] (until ctx w (or plane :step)
                                          #(holder-records-match?
                                             (:c %) h (record-is kind action))
                                          n)]
                     [w {:until [[h kind action held?]]}])
    :ticks (let [[n plane] args]
             [(reduce (fn [w _] (tick! ctx w (get planes (or plane :step)))) w (range n)) nil])
    :hand-off [(update w :c compose/hand-off (first args)) nil]
    :abort [(update w :c compose/abort (first args)) nil]
    :clock (do (reset! (:clock w) (first args)) [w nil])
    :append-prog (do (stream/append! (:prog w) (first args)) [w nil])
    :admit (let [[h req] args] [(admit! ctx w h req) nil])
    :enroll-other (let [e (compose/enroll! (:c w))]
                    [(assoc w :other (:yin.k/target e)) {:other (->data (:yin.k/target e))}])
    :snapshot (let [snap (snapshot ctx w)]
                ((:emit! ctx) {:event :snapshot :key (first args) :snapshot snap})
                [w {:snapshots {(first args) snap}}])
    :binding (let [b (->data (holder-binding w (first args)))]
               ((:emit! ctx) {:event :binding :holder (first args) :binding b})
               [w {:bindings {(first args) b}}])
    :inject (let [[h request] args
                  n (count (replies-of (:c w) h))]
              (stream/append! (get-in w [:c :inbounds h]) request)
              [(first (until ctx w :control #(> (count (replies-of (:c %) h)) n) 20)) nil])
    :offer-variant (let [[h] args
                         v (offer-variant! w h)]
                     ((:emit! ctx) {:event :variant :address (:address v)})
                     [(assoc w :variant v) {:variant (->data (:address v))}])
    :carrier (do (reset! (:carrier w) (first args)) [w nil])
    :hold (do (reset! (:held w) (first args)) [w nil])
    :partition-authority (do (reset! (:partitioned w) (first args)) [w nil])
    :die (fire! ctx {:site :explicit :at (first args)})
    :at-open (let [snap (snapshot ctx w)]
               ((:emit! ctx) {:event :snapshot :key :at-open :snapshot snap})
               [w {:at-open snap}])))


(defn- merge-report
  [report additions]
  (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) (into a b)))
              report additions))


(defn- fork-row
  "A fork per host: fork runs when selected and is labelled fork, and an
   exclusive requirement over a memory authority is refused, never
   downgraded."
  [ctx spec]
  (let [refused (compose/open! {:mode :exclusive
                                :failure-model :process-crash
                                :backend (journal/memory-backend (atom []) nil)
                                :duration {:s 30}
                                :renewal-interval {:s 10}
                                :store (jing-file/create-content-file
                                         (str (:root spec) "/refused.jing"))
                                :diagnostics (log-writer)
                                :medium "stage-e"
                                :clock (fn [] {:s 1})
                                :journals (compose/file-journals
                                            (str (:root spec) "/refused"))})
        w (open-world! ctx (assoc spec :mode :fork :kind :parked))]
    (try
      (let [w (add-source ctx w "h1")
            [w held?] (until ctx w :step #(phase? % "h1" :lifted) 12)]
        {:refused (->data (dissoc refused :yin.vm.ucf.compose/composition))
         :refused-composed? (some? (:yin.vm.ucf.compose/composition refused))
         :fork {:held? held?
                :policy (:yin.k/policy (:c w))
                :status-policy (:yin.k/policy (compose/status (:c w)))
                :arbitration (->data (compose/arbitration (:c w)))
                :holder (holder-summary (get-in w [:c :holders "h1"]))
                :address (->data (get-in w [:c :holders "h1" :detail :yin.k/address]))
                :journal (->data (journal-records-of (:c w) "h1"))
                :offers (->data (inbound-of (:c w) "h1" :yin.k/offer))
                :fronts (count (:fronts (:c w)))
                :outcome-reader? (some? (compose/outcome-reader (:c w)))}})
      (finally (close-world! w)))))


(defn run-life
  "Run one life of `spec` under `ctx` and answer its report (plain data).

   spec:
     :root     the scenario root (the durable media)
     :kind     :parked (the parked-reader source of rows 1, 2, 6, 7) or
               :rows (the reading-writer source over an enrolled target
               of rows 3, 4, 5, 8)
     :target   the enrolled target a :rows life reuses (absent: enroll,
               emitting {:event :enrolled})
     :clock    the life's starting clock reading, in seconds
     :prog     the program stream's values this life sees
     :source   the source stream's values; :evict-floor its eviction
     :unreadable addresses the store cannot read
     :attach   :none for an attach table that holds nothing
     :ops      the life's program, a vector of operations (run-op)
     :lose-authority true to destroy the authority's state first
     :fork-row true for the per-host fork row instead

   The cut, when any, is the context's.  A dead life throws ::killed
   out of here; the caller (peer or in-process runner) decides what a
   kill means."
  [ctx spec]
  (when (:lose-authority spec)
    (destroy-authority! (:root spec)))
  (cond
    (:fork-row spec)
    (fork-row ctx spec)

    ;; a lost ledger the durable backend itself refuses to open never
    ;; reaches the composition: fail stop, reported as data
    (and (:lose-authority spec)
         (not= :dao.stream/ok
               (:dao.stream/outcome
                 (let [r (file-journal/backend! (:authority (paths (:root spec))))]
                   (when-some [b (::file-journal/backend r)] (file-journal/close! b))
                   r))))
    {:refusal {:stage :authority-backend
               :answer (->data (file-journal/backend! (:authority (paths (:root spec)))))}}

    :else
    (let [w (open-world! ctx spec)]
      (if-some [refusal (:refusal w)]
        (do (close-world! w)
            {:refusal refusal})
        (let [result (atom w)]
          (try
            (let [[w report] (reduce (fn [[w report] op]
                                       (let [[w additions] (run-op ctx w op)]
                                         (reset! result w)
                                         [w (merge-report report additions)]))
                                     [w {}]
                                     (:ops spec))]
              (reset! result w)
              (assoc report :final (snapshot ctx w)))
            (finally
              (when-not (dead? ctx)
                (close-world! @result)))))))))


;; =============================================================================
;; Inspection: the durable state after every life, read without mutating it
;; =============================================================================


(defn inspect
  "Read the durable state under `root` without mutating it: the
   authority's projection through `authority/open!` (no reclaim, unlike
   the grantor's reopen), the enrolled `targets`' committed values, the
   outcome projection, each holder's progress and reply journal, and the
   side log."
  [{:keys [root holders targets]}]
  (let [p (paths root)
        backend (::file-journal/backend (file-journal/backend! (:authority p)))
        opened (if backend (authority/open! backend nil) {:yin.k/status :backend-refused})
        a (::authority/authority opened)
        open-journal (fn [dir]
                       (let [b (open-file-backend! dir)
                             j (:dao.stream/handle (journal/open! b nil))]
                         [b j]))
        read-dir (fn [dir]
                   (let [[b j] (open-journal dir)]
                     (try (->data (journal-values j))
                          (finally (file-journal/close! b)))))]
    (try
      {:status (:yin.k/status opened)
       :projection (->data (when a (authority/projection a)))
       :targets (into {}
                      (map (fn [i]
                             [(->data i) (if-some [r (when a (authority/target-reader a i))]
                                           (->data (read-values r))
                                           :absent)]))
                      targets)
       :outcomes (if a (->data (read-values (admission/outcome-reader a))) [])
       :journals (into {} (map (fn [h] [h (read-dir (str (:journals p) "/" h "-progress"))]))
                       holders)
       :replies (into {} (map (fn [h] [h (read-dir (str (:journals p) "/" h "-reply"))]))
                      holders)
       :side (read-dir (:side p))}
      (finally
        (when a (authority/close! a))
        (when backend (file-journal/close! backend))))))
