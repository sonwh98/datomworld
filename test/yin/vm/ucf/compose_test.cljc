(ns yin.vm.ucf.compose-test
  "D15: the composition (r3 1.5, 1.11 and the D15 test contract).  A
   file-backed journal substrate -- backends whose declaration covers
   :process-crash -- carries every exclusive row, beside the file-backed
   authority that passes `authority/exclusive-capable?`; the memory
   substrate stays the fork's, and the substrate gate rows refuse every
   exclusive composition that would journal to less than its declared
   failure model.  Every authority, judge, front and holder below is the
   landed one, composed; this namespace requires no yin.repl namespace,
   which the subprocess row proves on the JVM for load and drive alike."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file-journal]
            [dao.stream.memory-log :as memory-log]
            #?@(:cljd [["dart:io" :as dart-io]]
                :clj [[clojure.java.io :as io]])
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.compose :as compose]
            [yin.vm.ucf.lift-support :as s]))


;; =============================================================================
;; The substrate: integer-position streams, attach tables, machines
;; =============================================================================


(defn- int-stream
  "A stream whose values sit in an atom and whose cursors are plain
   integer positions, so a position lifted from the source's instance is
   a valid cursor on the receiving composition's own instance."
  [identity]
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
        (swap! values conj x)
        {:dao.stream/outcome :dao.stream/ok}))))


(defn- attach-over
  "A receiving composition's attach dispatch over a fixed table of
  identity to handle."
  [table]
  (fn [descriptor]
    (if-some [h (get table (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


(defn- serve-as
  "The exporter's `serve!`, answering one fixed identity for every
  handle it is asked about."
  [identity]
  (fn [_h]
    {:dao.stream/identity identity
     :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                          :dao.stream/identity identity}}))


(defn- observe-over
  "The handle observer the driver's live reads run through."
  []
  (fn [h op arg]
    (case op
      :next (stream/next h arg)
      :cursor (stream/cursor h arg))))


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


(defn- log-writer
  "The composition's diagnostic stream."
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


;; =============================================================================
;; Host scratch directories for the file-backed rows
;; =============================================================================


(defn- temp-dir
  []
  (str "target/test-compose-" (random-uuid)))


(defn- cleanup-dir!
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


;; =============================================================================
;; Driving
;; =============================================================================


(defn phase-of
  "The holder-state predicate for one `phase`."
  [phase]
  (fn [st] (= phase (:phase st))))


(defn drive
  "Whole composition ticks (control then program) until `pred` holds of
  holder `h`'s state or `n` steps are spent; asserts the predicate
  held."
  [c h pred n]
  (loop [c c k 0]
    (let [c' (compose/step c)
          st (get-in c' [:holders h])]
      (if (or (pred st) (>= (inc k) n))
        (do (is (pred st) (pr-str (select-keys st [:phase :status :detail])))
            c')
        (recur c' (inc k))))))


(defn drive-control
  "Control-only composition ticks -- what a hydrating or draining shell
  gives custody -- until `pred` holds of holder `h`'s state or `n` steps
  are spent; asserts the predicate held."
  [c h pred n]
  (loop [c c k 0]
    (let [c' (compose/control-step c)
          st (get-in c' [:holders h])]
      (if (or (pred st) (>= (inc k) n))
        (do (is (pred st) (pr-str (select-keys st [:phase :status :detail])))
            c')
        (recur c' (inc k))))))


(defn gated-stream
  "A readable integer-position stream whose appends answer :full while
  `gate` (an atom) is truthy and behave as an unbounded log once it
  clears: the inbound that holds a holder's requests back, then takes
  them, without changing identity."
  [identity gate]
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
        (if @gate
          {:dao.stream/outcome :dao.stream/full}
          (do (swap! values conj x)
              {:dao.stream/outcome :dao.stream/ok}))))))


;; =============================================================================
;; The worlds
;; =============================================================================


(defn- holder-seams
  "The holder's own seams over program identity `p`."
  [p attach-table]
  {:protection {p :at-least-once}
   :receiver (s/new-machine)
   :attach! (attach-over attach-table)
   :observe! (observe-over)
   :serve! (serve-as p)})


(defn fork-world
  "A fork composition (the caller's selection) over one source machine.
  No authority, no judge, no fronts: the composition's own decision.
  Public: the no-yin.repl subprocess drives it."
  []
  (compose/open! {:mode :fork
                  :renewal-interval {:s 10}
                  :store (mem/create-content-mem)
                  :diagnostics (log-writer)
                  :medium "compose-test"
                  :clock (fn [] {:s 1})}))


(defn driven-fork-composition
  "A fork composition whose single source has run to its :lifted answer:
  the whole drive a fresh process performs in the no-yin.repl row."
  []
  (let [c (get (fork-world) ::compose/composition)
        c (compose/source c "h1"
                          (merge (holder-seams "prog-f" {"prog-f" (int-stream "prog-f")})
                                 {:machine (parked-reader-over (int-stream "prog-f"))}))]
    (drive c "h1" (phase-of :lifted) 8)))


(defn exclusive-world
  "An exclusive composition over a file-backed authority (the backend
  that passes the gate for :process-crash) in a fresh scratch directory,
  one source holder over a real parked machine, its journals on a
  file-backed substrate over a scratch root of its own.  The receiver
  attaches its own instance of the program stream, the instance the run
  reads; `:attaches` counts its attach calls.  `:inbound` overrides the
  composition's ring -- the bounded medium the drain rows supply;
  `:journals` overrides the composition's journal substrate (the stable
  recovery configuration the restart rows reopen; file-journals over
  the world's own scratch root by default).  Public: the REPL wiring
  rows drive this world."
  [& {:keys [inbound journals]}]
  (let [dir (temp-dir)
        jroot (temp-dir)
        journals (or journals (compose/file-journals jroot))
        backend (get (file-journal/backend! dir) ::file-journal/backend)
        clock (atom {:s 1})
        store (mem/create-content-mem)
        prog (int-stream "prog-c")
        opened (compose/open! {:mode :exclusive
                               :failure-model :process-crash
                               :backend backend
                               :duration {:s 30}
                               :renewal-interval {:s 10}
                               :store store
                               :diagnostics (log-writer)
                               :medium "compose-test"
                               :clock (fn [] @clock)
                               :journals journals})
        c (get opened ::compose/composition)
        attaches (atom 0)
        attach! (fn [descriptor]
                  (swap! attaches inc)
                  ((attach-over {"prog-c" prog}) descriptor))
        source-config (merge (holder-seams "prog-c" {})
                             {:machine (parked-reader-over (int-stream "prog-c"))
                              :attach! attach!
                              :inbound inbound})
        c (compose/source c "h1" source-config)]
    {:dir dir
     :jroot jroot
     :backend backend
     :store store
     :clock clock
     :prog prog
     :attaches attaches
     :journals journals
     :source-config source-config
     :c c
     :inbound (get-in c [:inbounds "h1"])}))


(defn reopened-world
  "A fresh composition over the same stable configuration -- the same
  authority directory, the same journal substrate, the same store,
  clock and holder config: the reconstruction a restarted process
  performs.  Nothing of the crashed composition survives into it -- no
  composition value, no journal handle, no holder config -- only the
  caller's own configuration and the substrate the frames or
  directories stand in.  The caller closes the answer
  (`close-reconstruction!`)."
  [{:keys [dir store clock journals source-config]}]
  (let [backend (get (file-journal/backend! dir) ::file-journal/backend)
        opened (compose/open! {:mode :exclusive
                               :failure-model :process-crash
                               :backend backend
                               :duration {:s 30}
                               :renewal-interval {:s 10}
                               :store store
                               :diagnostics (log-writer)
                               :medium "compose-test"
                               :clock (fn [] @clock)
                               :journals journals})
        c (compose/source (get opened ::compose/composition) "h1"
                          (dissoc source-config :inbound))]
    {:backend backend :c c}))


(defn close-reconstruction!
  "Retire a `reopened-world` answer: its composition, then its freshly
  locked authority backend."
  [{:keys [c backend]}]
  (compose/close! c)
  (file-journal/close! backend))


(defn cleanup-world!
  [{:keys [c dir jroot backend]}]
  (compose/close! c)
  (file-journal/close! backend)
  (cleanup-dir! dir)
  (cleanup-dir! jroot))


(defn inbound-requests
  "The requests of `kind` that reached handle `h`, in stream order."
  [h kind]
  (loop [cursor (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         requests []]
    (let [answer (stream/next h cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome answer))
        (recur (:dao.stream/cursor answer)
               (if (= kind (get-in answer [:dao.stream/value :yin.k/request]))
                 (conj requests (:dao.stream/value answer))
                 requests))
        requests))))


(defn journal-records-of
  "The holder's whole progress journal, decoded.  Read through a fresh
   open of the same backend, so a threaded composition value's own
   handle never limits what is visible: this is the restart's view."
  [c h]
  (let [backend (get-in c [:progress h :backend])
        j (if (some? backend)
            (:dao.stream/handle (journal/open! backend nil))
            (compose/journal-of c h))
        origin (stream/cursor j :dao.stream/oldest)]
    (loop [cursor (:dao.stream/cursor origin) records []]
      (let [answer (stream/next j cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome answer))
          (recur (:dao.stream/cursor answer)
                 (conj records (:dao.stream/value answer)))
          records)))))


;; =============================================================================
;; The exclusivity gate
;; =============================================================================


(deftest exclusive-on-a-memory-authority-is-refused-not-forked-test
  (let [opened (compose/open! {:mode :exclusive
                               :failure-model :process-crash
                               :backend (journal/memory-backend (atom []) nil)
                               :duration {:s 30}
                               :renewal-interval {:s 10}
                               :store (mem/create-content-mem)
                               :diagnostics (log-writer)
                               :medium "compose-test"
                               :clock (fn [] {:s 1})
                               ;; a substrate in order, so the refusal
                               ;; below is the authority gate's own
                               :journals (compose/file-journals
                                           (str (temp-dir) "/unused"))})]
    (is (= :yin.k/unsatisfied (:yin.k/status opened)))
    (is (= :exclusive-uncapable (:yin.k/reason opened)))
    (is (= :yin.k/exclusive (:yin.k/policy opened))
        "the refusal names what was asked, not a downgrade")
    (is (nil? (::compose/composition opened))
        "nothing is composed: there is no composition to drive, forked or not")
    (is (= :memory (get-in opened [:dao.stream.journal/durability
                                   :dao.stream.journal/backend]))
        "the declaration that failed the gate travels with the refusal")
    (is (= :none (get-in opened [:dao.stream.journal/durability
                                 :dao.stream.journal/failure-model])))
    (testing "a stronger required model is refused the same way -- at the substrate no backend covers"
      (let [power (compose/open! {:mode :exclusive
                                  :failure-model :power-loss
                                  :backend (journal/memory-backend
                                             (atom []) nil)
                                  :duration {:s 30}
                                  :renewal-interval {:s 10}
                                  :store (mem/create-content-mem)
                                  :diagnostics (log-writer)
                                  :medium "compose-test"
                                  :clock (fn [] {:s 1})
                                  :journals (compose/file-journals
                                              (str (temp-dir) "/unused"))})]
        (is (= :yin.k/unsatisfied (:yin.k/status power)))
        (is (= :journals-uncapable (:yin.k/reason power))
            "the file substrate declares :process-crash alone, so the
             stronger model is refused at the journals gate, before the
             authority is ever asked")))))


;; =============================================================================
;; Fork
;; =============================================================================


(deftest fork-runs-when-selected-and-is-labelled-fork-test
  (let [c (get (fork-world) ::compose/composition)
        c (compose/source c "h1"
                          (merge (holder-seams "prog-f" {"prog-f" (int-stream "prog-f")})
                                 {:machine (parked-reader-over (int-stream "prog-f"))}))
        c' (drive c "h1" (phase-of :lifted) 8)
        st (get-in c' [:holders "h1"])]
    (testing "the composition is labelled with the policy it runs"
      (is (= :yin.k/fork (:yin.k/policy c)))
      (is (= :yin.k/fork (:yin.k/policy (compose/status c'))))
      (is (nil? (compose/arbitration c'))
          "a fork arbitrates nothing"))
    (testing "the lift ran and answered the bytes"
      (is (= :lifted (:phase st)))
      (is (= :yin.k/ok (:status st)))
      (is (some? (get-in st [:detail :bytes])))
      (is (some? (get-in st [:detail :yin.k/address]))))
    (testing "no custody protocol ran: nothing minted, offered or sent"
      (is (= [] (journal-records-of c' "h1"))
          "no journal bracket: no occurrence, no offer")
      (is (= [] (inbound-requests (get-in c' [:inbounds "h1"]) :yin.k/offer))
          "no request ever left the holder")
      (is (= 0 (count (keys (:fronts c'))))
          "a fork composes no fronts")
      (is (nil? (compose/outcome-reader c'))))))


;; =============================================================================
;; The whole exclusive handoff, on a file-backed authority
;; =============================================================================


(deftest a-whole-exclusive-handoff-runs-on-a-file-backed-authority-test
  (let [w (exclusive-world)]
    (try
      (let [c (:c w)
            _ (is (= :yin.k/exclusive (:yin.k/policy c))
                  "the file-backed authority passed the gate")
            c (drive c "h1" (phase-of :running) 60)
            st (get-in c [:holders "h1"])
            o (:occurrence st)
            l (:lease st)]
        (is (= :running (:phase st)))
        (is (= :running (vm/gate-mode (:machine st))))
        (is (= o (get-in st [:machine :yin.k/custody :yin.k/occurrence])))
        (testing "the run reads its program stream and halts"
          (stream/append! (:prog w) "B")
          (let [c (drive c "h1" (phase-of :safepoint) 12)]
            (is (= :safepoint (:phase (get-in c [:holders "h1"]))))))
        (testing "the exit completes: report, release, closure, result body"
          (let [c (drive c "h1" (phase-of :exited) 60)
                st (get-in c [:holders "h1"])
                projection (authority/projection (:authority c))
                closed (get-in projection [:occurrences o :yin.k/closed])]
            (is (= :exited (:phase st)))
            (is (= :yin.k/ok (:status st)))
            (is (some? (:yin.k/result (:detail st))))
            (is (not= ::absent
                      ((:get-bytes-fn (:store w))
                       (:yin.k/result (:detail st)) ::absent))
                "the result body stands in the content store")
            (is (= l (:dao.lease/lease closed)))
            (is (= (:yin.k/result (:detail st)) (:yin.k/result closed))
                "the closure's edge is terminal to the result's address")
            (is (nil? (get-in projection [:occurrences o :dao.lease/lease]))
                "the occurrence is closed and unheld")
            (is (= 1 (count (inbound-requests (:inbound w) :yin.k/resumed)))
                "one resumed report, carried by the front")
            (is (pos? (count (inbound-requests (:inbound w) :yin.k/release)))
                "the release was sent")
            (is (some #(and (= :yin.k/ack (:yin.k/journal %))
                            (= :yin.k/resumed (:yin.k/action %)))
                      (journal-records-of c "h1"))
                "the report's authenticated acknowledgment stands"))))
      (finally
        (cleanup-world! w)))))


;; =============================================================================
;; The chain continues: a parked continuation hands off to a successor a
;;  fresh candidate runs (compose/holder over admitted bytes)
;; =============================================================================


(deftest a-parked-continuation-hands-off-and-a-candidate-runs-the-successor-test
  (let [w (exclusive-world)]
    (try
      (let [c (drive (:c w) "h1" (phase-of :running) 60)
            c (compose/hand-off c "h1")
            _ (is (= :safepoint (get-in c [:holders "h1" :phase])))
            c (drive c "h1" (phase-of :exited) 60)
            st (get-in c [:holders "h1"])
            successor (get-in st [:detail :yin.k/successor])
            address (get-in st [:detail :yin.k/address])
            bytes ((:get-bytes-fn (:store w)) address ::absent)]
        (is (= :exited (:phase st)))
        (is (some? successor))
        (is (not= ::absent bytes) "the successor's body stands in the store")
        (testing "a second holder runs the successor over the same authority"
          (let [c' (compose/holder c "h2" (merge (holder-seams "prog-c" {"prog-c" (:prog w)})
                                                 {:bytes bytes :address address}))
                c' (drive c' "h2" (phase-of :running) 60)
                run (get-in c' [:holders "h2"])]
            (is (= :running (:phase run)))
            (is (= successor (:occurrence run))
                "the candidate holds the successor's own occurrence")
            (is (= #{"h1" "h2"} (set (keys (:fronts c'))))
                "the second holder has its own front, one owner each"))))
      (finally
        (cleanup-world! w)))))


;; =============================================================================
;; The fix-round rows (the D15 gate r1 and the architect's sign-off r1):
;; the fork's arbitration hole, the composition stop latch, and the
;; durable journal substrate a restart reopens.
;; =============================================================================


(deftest a-fork-refuses-a-caller-supplied-arbitration-test
  (let [c (get (fork-world) ::compose/composition)]
    (is (thrown-with-msg?
          #?(:cljd Object :clj Exception :cljs js/Error)
          #"fork arbitrates nothing"
          (compose/source c "h1"
                          (merge (holder-seams "prog-f" {})
                                 {:machine (parked-reader-over (int-stream "prog-f"))
                                  :arbitration
                                  {:dao.stream/identity "someone-elses-ledger"
                                   :dao.stream/descriptor
                                   {:dao.stream/type :dao.stream/journal}}})))
        "a caller-supplied :arbitration must not make a labelled fork run
         an exclusive export: the composition's fork decision is the only
         one that runs")))


(deftest a-holder-config-cannot-substitute-the-compositions-seams-test
  (let [w (exclusive-world)]
    (try
      (let [c (:c w)
            smuggled {:me "someone-else"
                      :journal ::a-journal-the-holder-did-not-mint
                      :reply-inbox {:version 1 :identity "a-ring"
                                    :read-at! (fn [_] {:status :empty})}
                      :outcome-inbox {:version 1 :identity "a-ring"
                                      :read-at! (fn [_] {:status :empty})}
                      :read-ledger! (fn [] nil)
                      :append-request! (fn [_request]
                                         {:dao.stream/outcome
                                          :dao.stream/ok})
                      :append-diagnostic! (fn [_d] nil)
                      :enroll! (fn [] nil)
                      :clock (fn [] {:s 9999})
                      :store {:put-bytes-fn (fn [_ _bs] :inserted)
                              :get-bytes-fn (fn [_ _bs] nil)}
                      :medium "smuggled-medium"
                      :units {}
                      :export-version 99
                      :journals (compose/memory-journals (atom {}))}]
        (doseq [[k v] smuggled]
          (is (thrown-with-msg?
                #?(:cljd Object :clj Exception :cljs js/Error)
                (re-pattern (str (pr-str k) " is a composition seam"))
                (compose/holder c "h2" {k v}))
              (str "the holder config's " k)))
        (is (= #{"h1"} (set (keys (:holders c))))
            "no smuggled config added a holder")
        (testing "the composition's seams are the ones a holder runs on"
          (let [c' (compose/source c "h9"
                                   (merge (holder-seams "prog-c" {})
                                          {:machine (parked-reader-over
                                                      (int-stream "prog-c"))}))]
            (is (= "h9" (get-in c' [:holder-configs "h9" :me])))
            (is (= (:clock c) (get-in c' [:holder-configs "h9" :clock])))
            (is (= (:store c) (get-in c' [:holder-configs "h9" :store])))
            (is (= (:units c) (get-in c' [:holder-configs "h9" :units])))
            (is (= (:renewal-interval c)
                   (get-in c' [:holder-configs "h9" :renewal-interval])))
            (is (= (:medium c) (get-in c' [:holder-configs "h9" :medium]))))))
      (finally
        (cleanup-world! w)))))


(deftest a-stopped-composition-receives-its-holders-stopped-and-runs-no-program-test
  (let [w (exclusive-world)]
    (try
      (let [c (drive (:c w) "h1" (phase-of :running) 60)
            c (compose/hand-off c "h1")
            c (drive c "h1" (phase-of :exited) 60)
            st (get-in c [:holders "h1"])
            address (get-in st [:detail :yin.k/address])
            bytes ((:get-bytes-fn (:store w)) address ::absent)
            c (compose/stop c)]
        (is (= c (compose/program-step c))
            "the composition's own latch: program stepping is a no-op once
             stop has latched")
        (testing "a holder added after the latch arrives stopped"
          (let [c' (compose/holder c "h2" (merge (holder-seams "prog-c" {"prog-c" (:prog w)})
                                                 {:bytes bytes :address address}))]
            (is (true? (get-in c' [:holders "h2" :stopped?]))
                "the late holder was latched at its addition")
            (let [stepped (reduce (fn [c _] (compose/step c)) c' (range 5))]
              (is (= :validating (:phase (get-in stepped [:holders "h2"])))
                  "five whole ticks later it never validated: no program
                   work ran for it")
              (is (= :exited (:phase (get-in stepped [:holders "h1"])))))))
        (testing "a source added after the latch arrives stopped"
          (let [c' (compose/source c "h3" (merge (holder-seams "prog-c" {})
                                                 {:machine (parked-reader-over
                                                             (int-stream "prog-c"))}))]
            (is (true? (get-in c' [:holders "h3" :stopped?]))
                "the late source was latched at its addition")
            (let [stepped (reduce (fn [c _] (compose/step c)) c' (range 5))]
              (is (= :exporting (:phase (get-in stepped [:holders "h3"])))
                  "five whole ticks later its export never began: no
                   program work ran for it")))))
      (finally
        (cleanup-world! w)))))


(deftest a-journal-substrate-is-checked-at-open-test
  (let [open! (fn [journals]
                (compose/open! {:mode :fork
                                :renewal-interval {:s 10}
                                :store (mem/create-content-mem)
                                :diagnostics (log-writer)
                                :medium "compose-test"
                                :clock (fn [] {:s 1})
                                :journals journals}))
        exclusive! (fn [journals]
                     (compose/open! {:mode :exclusive
                                     :failure-model :process-crash
                                     :backend (journal/memory-backend
                                                (atom []) nil)
                                     :duration {:s 30}
                                     :renewal-interval {:s 10}
                                     :store (mem/create-content-mem)
                                     :diagnostics (log-writer)
                                     :medium "compose-test"
                                     :clock (fn [] {:s 1})
                                     :journals journals}))
        memory (fn [_holder] (journal/memory-backend (atom []) nil))]
    (is (thrown-with-msg?
          #?(:cljd Object :clj Exception :cljs js/Error)
          #"a journal substrate"
          (open! 42)))
    (is (thrown-with-msg?
          #?(:cljd Object :clj Exception :cljs js/Error)
          #"a journal substrate"
          (open! {:progress-backend! 7}))
        "the substrate's suppliers are (fn [holder] backend)")
    (testing "an exclusive composition requires the substrate: nil, empty, partial and undeclared each refuse at open"
      (let [refused {:yin.k/status :yin.k/unsatisfied
                     :yin.k/reason :journals-uncapable
                     :yin.k/policy :yin.k/exclusive}
            refuse (fn [journals]
                     (select-keys (exclusive! journals)
                                  [:yin.k/status :yin.k/reason :yin.k/policy]))]
        (is (= refused (refuse nil))
            "nil -- the fork default -- never silently mints the exclusive
             composition's memory journals")
        (is (= refused (refuse {}))
            "an empty substrate supplies no supplier at all")
        (is (= refused (refuse {:progress-backend! memory}))
            "a partial substrate would journal the replies to fresh memory
             the substrate never supplied")
        (is (= refused (refuse {:reply-backend! memory})))
        (is (= refused (refuse {:progress-backend! memory
                                :reply-backend! memory}))
            "a substrate that declares nothing cannot be validated against
             the failure model")
        (is (nil? (::compose/composition
                    (exclusive! {:progress-backend! memory
                                 :reply-backend! memory})))
            "nothing is composed and no holder was activated")))
    (testing "memory journals stay the fork's -- and the fork still opens without a substrate"
      (is (= :open (:yin.k/status (open! (compose/memory-journals (atom {}))))))
      (is (= :open (:yin.k/status (open! nil)))))))


(deftest an-exclusive-composition-refuses-substrate-journals-that-cannot-cover-its-failure-model-test
  (let [open! (fn [failure-model journals]
                (compose/open! {:mode :exclusive
                                :failure-model failure-model
                                :backend (journal/memory-backend (atom []) nil)
                                :duration {:s 30}
                                :renewal-interval {:s 10}
                                :store (mem/create-content-mem)
                                :diagnostics (log-writer)
                                :medium "compose-test"
                                :clock (fn [] {:s 1})
                                :journals journals}))]
    (testing "a memory substrate covers no failure model"
      (let [opened (open! :process-crash (compose/memory-journals (atom {})))]
        (is (= :yin.k/unsatisfied (:yin.k/status opened)))
        (is (= :journals-uncapable (:yin.k/reason opened)))
        (is (= :yin.k/exclusive (:yin.k/policy opened))
            "the refusal names what was asked, never a downgrade to fork
             or a silent memory fallback")
        (is (nil? (::compose/composition opened))
            "nothing is composed and no holder was activated")
        (is (= :memory (get-in opened [:dao.stream.journal/durability
                                       :dao.stream.journal/backend]))
            "the declaration that failed the gate travels with the refusal")
        (is (= :none (get-in opened [:dao.stream.journal/durability
                                     :dao.stream.journal/failure-model])))))
    (testing "a file substrate covers :process-crash only"
      (let [opened (open! :power-loss
                          (compose/file-journals (str (temp-dir) "/unused")))]
        (is (= :yin.k/unsatisfied (:yin.k/status opened)))
        (is (= :journals-uncapable (:yin.k/reason opened)))
        (is (= :process-crash
               (get-in opened [:dao.stream.journal/durability
                               :dao.stream.journal/failure-model]))
            "the file declaration names :process-crash alone, and the
             refusal carries it")))))


(deftest an-exclusive-composition-refuses-backends-weaker-than-their-substrates-declaration-test
  "The open gate reads the substrate's declaration; the addition reads
   the backend each supplier answers.  A substrate may declare file
   durability and still supply a memory backend -- the declaration and
   the storage disagree -- and each supplier is pinned independently:
   the refused addition releases every backend the substrate opened
   before the refusal, the way close! does."
  (let [authority-root (temp-dir)
        authority (get (file-journal/backend! authority-root)
                       ::file-journal/backend)
        open! (fn [journals]
                (compose/open! {:mode :exclusive
                                :failure-model :process-crash
                                :backend authority
                                :duration {:s 30}
                                :renewal-interval {:s 10}
                                :store (mem/create-content-mem)
                                :diagnostics (log-writer)
                                :medium "compose-test"
                                :clock (fn [] {:s 1})
                                :journals journals}))
        ;; the lying substrate: its declaration -- what the open gate
        ;; reads -- is file durability, whatever its suppliers answer
        lying (fn [progress! reply!]
                {:dao.stream.journal/durability file-journal/durability
                 :progress-backend! progress!
                 :reply-backend! reply!})
        file-backend! (fn [dir]
                        (get (file-journal/backend! dir) ::file-journal/backend))
        add-source (fn [c]
                     (try
                       (compose/source c "h1"
                                       {:machine (parked-reader-over
                                                   (int-stream "prog-c"))})
                       nil
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         e)))]
    (try
      (testing "a memory progress backend beneath the declaration is a refused addition"
        (let [reply-opens (atom 0)
              opened (open! (lying (fn [_holder]
                                     (journal/memory-backend
                                       (atom []) nil))
                                   (fn [_holder]
                                     (swap! reply-opens inc)
                                     (file-backend! (str (temp-dir)
                                                         "/h1-reply")))))]
          (is (= :open (:yin.k/status opened))
              "the declaration passes the open gate: only the addition
               can see what the supplier answers")
          (let [c (get opened ::compose/composition)
                failure (add-source c)
                d (:dao.stream.journal/durability (ex-data failure))]
            (is (some? failure) "the weaker backend is a refusal")
            (is (re-find #"a progress journal backend whose declared durability"
                         (ex-message failure)))
            (is (= :assembly (:yin.k/hint (ex-data failure))))
            (is (= :journals-uncapable (:yin.k/reason (ex-data failure))))
            (is (= :yin.k/exclusive (:yin.k/policy (ex-data failure))))
            (is (= :process-crash (:yin.k/failure-model (ex-data failure))))
            (is (= :memory (:dao.stream.journal/backend d))
                "the backend's own declaration -- not the substrate's --
                 travels with the refusal")
            (is (= :none (:dao.stream.journal/failure-model d)))
            (is (= 0 @reply-opens)
                "the reply supplier was never called: the progress
                 backend's refusal precedes it")
            (is (empty? (:holders c)) "no holder was activated")
            (compose/close! c))))
      (testing "a memory reply backend beneath the declaration releases the
                progress backend the substrate opened"
        (let [progress-root (temp-dir)
              opened (open! (lying (fn [holder]
                                     (file-backend! (str progress-root "/"
                                                         holder "-progress")))
                                   (fn [_holder]
                                     (journal/memory-backend
                                       (atom []) nil))))
              c (get opened ::compose/composition)
              failure (add-source c)]
          (is (some? failure))
          (is (re-find #"a reply journal backend whose declared durability"
                       (ex-message failure)))
          (let [reopened (file-journal/backend! (str progress-root
                                                     "/h1-progress"))]
            (is (= :dao.stream/ok (:dao.stream/outcome reopened))
                "the refusal closed the backend the substrate opened
                 before it refused: its directory is unlocked, the way
                 close! leaves it")
            (when-some [b (::file-journal/backend reopened)]
              (file-journal/close! b)))
          (is (empty? (:holders c)) "no holder was activated")
          (compose/close! c)
          (cleanup-dir! progress-root)))
      (testing "a backend that declares nothing is never capable"
        (let [opened (open! (lying (fn [_holder]
                                     (dissoc (journal/memory-backend
                                               (atom []) nil)
                                             :dao.stream.journal/durability))
                                   (fn [_holder]
                                     (journal/memory-backend
                                       (atom []) nil))))
              c (get opened ::compose/composition)
              failure (add-source c)]
          (is (some? failure))
          (is (re-find #"a progress journal backend whose declared durability"
                       (ex-message failure)))
          (is (nil? (:dao.stream.journal/durability (ex-data failure)))
              "the refusal carries the absent declaration")
          (compose/close! c)))
      (finally
        (file-journal/close! authority)
        (cleanup-dir! authority-root)))))


(deftest a-file-substrate-composition-reopens-its-journals-and-recovers-its-holder-test
  (let [root (temp-dir)
        fresh-reply (temp-dir)
        w (exclusive-world :journals (compose/file-journals root))]
    (try
      (let [c (drive (:c w) "h1" (phase-of :running) 60)
            reply-id (get-in c [:holder-configs "h1" :reply-inbox :identity])
            records (journal-records-of c "h1")
            ;; the process dies: the composition is retired and dropped,
            ;; and only the caller's stable configuration survives
            _ (compose/close! c)
            _ (file-journal/close! (:backend w))
            rebuilt (reopened-world w)
            c2 (:c rebuilt)
            st (get-in c2 [:holders "h1"])]
        (is (= reply-id (get-in c2 [:holder-configs "h1" :reply-inbox :identity]))
            "the substrate reopened the same reply journal by identity: a
             restarted process never mints the fresh journal the driver's
             assembly refuses")
        (is (= records (journal-records-of c2 "h1"))
            "the reopened progress journal carries the crashed history,
             whole and unchanged")
        (is (= :releasing (:phase st))
            "a journaled grant never restores execution")
        (is (some? (:release st)) "the pending release stands")
        (is (= 1 (count (filter #(= :yin.k/minted (:yin.k/journal %))
                                (journal-records-of c2 "h1"))))
            "the occurrence was never re-minted")
        (testing "the recovered holder sends the release"
          (let [occ (get-in st [:detail :yin.k/occurrence])
                lease (get-in st [:detail :dao.lease/lease])
                released (drive-control c2 "h1"
                                        (fn [st] (not= :releasing (:phase st)))
                                        40)
                ;; the release's ledger processing rides the next judge and
                ;; front ticks
                c2' (reduce (fn [c _] (compose/control-step c)) released
                            (range 8))
                attempts (filterv #(and (= :yin.k/attempt (:yin.k/journal %))
                                        (= :yin.k/release (:yin.k/action %)))
                                  (journal-records-of c2' "h1"))
                projection (authority/projection (:authority c2'))]
            (is (some #(= :dao.stream/ok (:yin.k/append %)) attempts)
                "the recovered holder sent the release over the rebuilt
                 composition's own front")
            (is (some? (get-in projection [:leases lease :dao.lease/cause]))
                "the crashed tenure is ended on the same authority the
                 crash interrupted -- reclaimed at the reopened
                 composition's handout, the release confirming it")
            (is (not= lease
                      (get-in projection [:occurrences occ :dao.lease/lease]))
                "whatever holds the occurrence now is a fresh tenure,
                 never the crashed one")))
        (close-reconstruction! rebuilt)
        (testing "a restart that mints a fresh reply journal is refused"
          (let [backend' (get (file-journal/backend! (:dir w)) ::file-journal/backend)
                opened (compose/open! {:mode :exclusive
                                       :failure-model :process-crash
                                       :backend backend'
                                       :duration {:s 30}
                                       :renewal-interval {:s 10}
                                       :store (:store w)
                                       :diagnostics (log-writer)
                                       :medium "compose-test"
                                       :clock (fn [] @(:clock w))
                                       ;; the progress frames reopen; the
                                       ;; reply journal is fresh -- the
                                       ;; restart that lost its substrate
                                       :journals (assoc (compose/file-journals root)
                                                        :reply-backend!
                                                        (fn [_holder]
                                                          (get (file-journal/backend!
                                                                 (str fresh-reply
                                                                      "/h1-reply"))
                                                               ::file-journal/backend)))})
                failure (try
                          (compose/source (get opened ::compose/composition) "h1"
                                          (dissoc (:source-config w) :inbound))
                          nil
                          (catch #?(:cljd Object :clj Exception :cljs :default) e e))]
            (is (some? failure) "the changed reply identity is a refusal")
            (is (re-find #"Invalid holder inbox" (str (ex-message failure))))
            (is (= :inbox-identity
                   (:yin.vm.ucf.holder.inbox/refusal (ex-data failure))))
            (file-journal/close! backend'))))
      (finally
        (cleanup-world! w)
        (cleanup-dir! root)
        (cleanup-dir! fresh-reply)))))


(deftest a-file-substrate-composition-recovers-from-a-read-before-retention-crash-test
  (let [root (temp-dir)
        w (exclusive-world :journals (compose/file-journals root))]
    (try
      (let [c (drive (:c w) "h1" (phase-of :running) 60)
            ;; one control tick sends the due renewal and -- the fronts
            ;; follow the judge inside the same tick -- appends its reply;
            ;; the holder retains that reply only on the next tick.  The
            ;; process dies between the two: the reply is read by nobody,
            ;; retained by nothing, standing in the reply journal.
            _ (reset! (:clock w) {:s 20})
            c (compose/control-step c)
            records (journal-records-of c "h1")
            renewal-rid (some (fn [r]
                                (when (and (= :yin.k/intent (:yin.k/journal r))
                                           (= :yin.k/renewal (:yin.k/action r)))
                                  (get-in r [:yin.k/request :yin.k/request-id])))
                              records)
            retained-rids (set (map #(get-in % [:yin.k/record :yin.k/request-id])
                                    (filter #(= :yin.k/inbox (:yin.k/journal %)) records)))
            _ (is (some? renewal-rid) "the renewal left this tick")
            _ (is (not (contains? retained-rids renewal-rid))
                  "its reply stands unretained at the crash")
            reply-id (get-in c [:holder-configs "h1" :reply-inbox :identity])
            _ (compose/close! c)
            _ (file-journal/close! (:backend w))
            rebuilt (reopened-world w)
            c2 (:c rebuilt)
            st (get-in c2 [:holders "h1"])]
        (is (= reply-id (get-in c2 [:holder-configs "h1" :reply-inbox :identity]))
            "the file substrate reopened the same reply journal")
        (is (= :releasing (:phase st)) "the grant releases, never re-executes")
        (testing "the unretained reply is retained exactly once"
          (let [c2' (compose/control-step c2)
                receipts (filter #(and (= :yin.k/inbox (:yin.k/journal %))
                                       (= renewal-rid
                                          (get-in % [:yin.k/record :yin.k/request-id])))
                                 (journal-records-of c2' "h1"))]
            (is (= 1 (count receipts))
                "the recovered holder re-read the reply from its retained
                 position and journaled it once")))
        (testing "the pending release is sent"
          (let [occ (get-in st [:detail :yin.k/occurrence])
                lease (get-in st [:detail :dao.lease/lease])
                released (drive-control c2 "h1"
                                        (fn [st] (not= :releasing (:phase st)))
                                        40)
                c2' (reduce (fn [c _] (compose/control-step c)) released
                            (range 8))
                attempts (filterv #(and (= :yin.k/attempt (:yin.k/journal %))
                                        (= :yin.k/release (:yin.k/action %)))
                                  (journal-records-of c2' "h1"))
                projection (authority/projection (:authority c2'))]
            (is (some #(= :dao.stream/ok (:yin.k/append %)) attempts)
                "the recovered holder sent the release")
            (is (some? (get-in projection [:leases lease :dao.lease/cause]))
                "the crashed tenure is ended on the same authority the
                 crash interrupted")
            (is (not= lease
                      (get-in projection [:occurrences occ :dao.lease/lease]))
                "whatever holds the occurrence now is a fresh tenure,
                 never the crashed one")))
        (close-reconstruction! rebuilt))
      (finally
        (cleanup-world! w)
        (cleanup-dir! root)))))


(deftest close!-releases-the-journal-backends-the-composition-opened-test
  (let [root (temp-dir)
        w (exclusive-world :journals (compose/file-journals root))]
    (try
      (let [c (drive (:c w) "h1" (phase-of :running) 60)]
        (compose/close! c)
        (doseq [kind ["progress" "reply"]]
          (let [reopened (file-journal/backend! (str root "/h1-" kind))]
            (is (= :dao.stream/ok (:dao.stream/outcome reopened))
                (str "the " kind
                     " journal's directory is unlocked after close"))
            (file-journal/close! (get reopened ::file-journal/backend))))
        (is (nil? (compose/close! c)) "close is idempotent"))
      (finally
        (cleanup-world! w)
        (cleanup-dir! root)))))


#?(:cljd nil
   :clj
   (deftest the-plain-composition-loads-and-drives-with-no-yin-repl-test
     (let [script (io/file "target/compose-no-repl.clj")
           _ (do (.mkdirs (.getParentFile script))
                 (spit script
                       (str "(require '[yin.vm.ucf.compose-test :as ct])\n"
                            "(let [c (ct/driven-fork-composition)]\n"
                            "  (println \"PHASE\" (name (get-in c [:holders \"h1\" :phase])))\n"
                            "  (println \"REPL-NAMESPACES\"\n"
                            "           (pr-str (vec (sort (filter #(re-find #\"^yin\\\\.repl\" (str %))\n"
                            "                                      (map str (loaded-libs))))))))\n")))
           command [(str (System/getProperty "java.home") "/bin/java")
                    "-cp" (System/getProperty "java.class.path")
                    "clojure.main" (.getAbsolutePath script)]
           process (let [pb (doto (ProcessBuilder. ^java.util.List command)
                              (.redirectErrorStream true))]
                     (.start pb))
           output (with-open [r (io/reader (.getInputStream process))]
                    (doall (line-seq r)))
           _ (.waitFor process)
           lines (->> output
                      (filterv #(or (re-find #"^PHASE " %)
                                    (re-find #"^REPL-NAMESPACES " %))))]
       (is (= ["PHASE lifted" "REPL-NAMESPACES []"] lines)
           (str "a fresh process loads and drives the composition with "
                "no yin.repl namespace; its output was: "
                (pr-str (vec output)))))))
