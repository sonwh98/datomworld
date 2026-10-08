(ns yin.vm.ucf.compose-rows-test
  "D16-final: the compose-driven rows of linker-dht 14.2.4 -- rows 1 to
   5, 7 and 8 through `yin.vm.ucf.compose`, same host; row 6's retry
   logic as a unit test; and the stage-D gate's compose halves.

   Every row below drives the composition -- `open!`, `source`,
   `holder`, `hand-off`, `control-step`/`program-step`/`step`, `close!`
   -- never a holder namespace: the protocol halves of these rows are
   D13/D14's driver tests, and the fencing halves are D16-prep's
   `fencing-rows-test`; what this file proves is the same contracts
   over the wired composition, where the judge, the fronts, the
   journals and the crash/reopen substrate are the composition's own.

   Row 1: two encodings of one occurrence, two candidates -- one
   admitted holder, the competitor awaiting then refused.  Row 2: the
   source emits nothing until its own grant, resuming only on it.  Row
   3: a delayed protected effect across a reclaim -- the old effect is
   stale, the new holder commits once, an equal replay answers the
   stored result, and the target never holds both.  Row 4: crash cuts
   around the atomic commitment -- zero or one commit, the recorded
   result redelivered, stable ids, and the at-least-once side effect
   outside the transaction earning no exactly-once.  Row 5: an evicted
   kept-cursor value -- recovery with durable inputs replays the old
   intent, recovery without them re-reads live into the eviction, and
   a divergent intent at the same id is an intent conflict that
   quarantines and commits nothing.  Row 7: crash cuts around
   completion -- successor append, resumed report, release append,
   authoritative closure.  Row 8: the admission-order fixture of UCF
   7.11.1 clause 8's stage-D half, one check at a time through the
   composition's own fronts.  Row 6 (unit): a lost request stream --
   every retry is the identical request, and the judge answers a
   proposal id exactly once.  The final blocks: the wired composition's
   journal cuts around freeze, storage and fencing -- the D14 recovery
   seam's fresh-receiver rehydration rows through the composition --
   and the stage-D gate's two landed halves re-verified beside them."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file-journal]
            [dao.stream.memory-log :as memory-log]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.seam :as seam]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.compose :as compose]
            [yin.vm.ucf.compose-test :as ct]
            [yin.vm.ucf.lift-support :as s]
            [yin.vm.ucf.v2-support :as s2]))


;; =============================================================================
;; Driving and reading
;; =============================================================================


(defn- step-until
  "Whole composition ticks until `pred` holds of the composition itself
  or `n` are spent; asserts the predicate held.  Answers the stepped
  composition."
  ([c pred n] (step-until c pred n compose/step))
  ([c pred n tick]
   (loop [c c k 0]
     (let [c' (tick c)]
       (if (or (pred c') (>= (inc k) n))
         (do (is (pred c') (pr-str (into {} (map (fn [[h st]] [h (select-keys st [:phase :status :detail])]) (:holders c')))))
             c')
         (recur c' (inc k)))))))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- read-all
  "Every value a reader holds, in order, up to a bound."
  [h]
  (loop [cursor (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         values []]
    (let [r (stream/next h cursor)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count values) 1000))
        (recur (:dao.stream/cursor r) (conj values (:dao.stream/value r)))
        values))))


(defn- inbound-of
  "The requests of `kind` that reached holder `h`'s inbound, in stream
  order."
  [c h kind]
  (ct/inbound-requests (get-in c [:inbounds h]) kind))


(defn- replies-of
  "The replies of `kind` the composition's front wrote into holder `h`'s
  reply journal, in order."
  [c h kind]
  (let [j (get-in c [:reply-journals h :journal])]
    (loop [cursor (:dao.stream/cursor (stream/cursor j :dao.stream/oldest))
           out []]
      (let [r (stream/next j cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (:dao.stream/cursor r)
                 (if (= kind (get-in r [:dao.stream/value :yin.k/reply]))
                   (conj out (:dao.stream/value r))
                   out))
          out)))))


(defn- journal-has?
  "Whether holder `h`'s progress journal holds a record matching `pred`."
  [c h pred]
  (some pred (ct/journal-records-of c h)))


(defn- journal-acks
  "The grant acknowledgments of holder `h`'s journal -- one per accepted
  grant."
  [c h]
  (filterv #(and (= :yin.k/ack (:yin.k/journal %))
                 (= :yin.k/grant (:yin.k/action %)))
           (ct/journal-records-of c h)))


(defn- checkpoint-of
  "The occurrence, address and bytes of the world source's export, read
  from the holder's journal and the content store."
  [c store h]
  (let [fence (some (fn [r] (when (= :yin.k/fenced (:yin.k/journal r)) r))
                    (ct/journal-records-of c h))]
    (when (some? fence)
      {:occurrence (:yin.k/occurrence fence)
       :address (:yin.k/address fence)
       :bytes ((:get-bytes-fn store) (:yin.k/address fence) ::absent)})))


(defn- target-values
  "The committed values of the rows world's enrolled target."
  [w]
  (read-all (:reader w)))


(defn- crash!
  "The process dies: the composition is retired and dropped with its
  authority, and only the caller's stable configuration survives -- the
  authority directory, the journal substrate, the store, the clock and
  the world's own streams."
  [w c]
  (compose/close! c)
  (file-journal/close! (:backend w)))


(defn- held-inbound
  "A log-shaped inbound whose reads answer :blocked while `gate` (an
  atom) is truthy: the holder's requests land, but the composition's
  front cannot read them -- the lost request stream of row 6 and the
  delayed effect of row 3.  Answers [handle sent-atom]; the atom holds
  every request that landed, in order, readable while the gate stands."
  [identity gate]
  (let [values (atom [])]
    [(reify
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
         (if @gate
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
         {:dao.stream/outcome :dao.stream/ok}))
     values]))


;; =============================================================================
;; Holder seams
;; =============================================================================


(defn- attach-over
  "A receiving composition's attach dispatch over a fixed table of
  identity to handle."
  [table]
  (fn [descriptor]
    (if-some [h (get table (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


(defn- counting-attach
  "The attach seam, counting every call."
  [counter base]
  (fn [d]
    (swap! counter inc)
    (base d)))


(defn- counting-observe
  "The handle observer, counting every live observation."
  [counter]
  (fn [h op arg]
    (swap! counter inc)
    (case op
      :next (stream/next h arg)
      :cursor (stream/cursor h arg))))


(defn- same-id-server
  "The exporter's serve! over a world of fixed handles: each handle is
  entered under its own identity, so a lowered read or write targets
  the identity itself (the fencing rows' server)."
  []
  (fn [h]
    (let [id (identity-of h)]
      {:dao.stream/identity id
       :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                            :dao.stream/identity id}})))


(defn- candidate-seams
  "A candidate holder's seams over the exclusive world's program
  stream, its attaches counted by the world's own counter."
  [w]
  (let [p (:source-id w)
        base (attach-over {p (:prog w)})]
    {:protection {p :at-least-once}
     :receiver (s/new-machine)
     :attach! (counting-attach (:attaches w) base)
     :observe! (counting-observe (atom 0))
     :serve! (same-id-server)}))


;; =============================================================================
;; The rows world: an enrolled target, a reading-writer source
;; =============================================================================


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


(defn- rows-config
  "The source holder's config over the world's `table` (identity to
  handle) and `protection`: the receiver and the counting attach and
  observe seams beside the machine."
  [w table protection]
  {:protection protection
   :receiver (s/new-machine)
   :attach! (counting-attach (:attaches w) (attach-over table))
   :observe! (counting-observe (:observes w))
   :serve! (same-id-server)
   :machine (reading-writer (get table (:source-id w))
                            (get table (:target w))
                            (get table (:side-id w)))})


(defn- open-rows
  "Open the rows world's composition over the world's stable pieces and
  `backend`."
  [w backend]
  (let [opened (compose/open! {:mode :exclusive
                               :failure-model :process-crash
                               :backend backend
                               :duration {:s 30}
                               :renewal-interval {:s 10}
                               :store (:store w)
                               :diagnostics (:diagnostics w)
                               :medium "compose-rows"
                               :clock (fn [] @(:clock w))
                               :journals (:journals w)})
        c (get opened ::compose/composition)]
    (is (= :open (:yin.k/status opened)) (pr-str opened))
    c))


(defn rows-world
  "An exclusive composition over a file-backed authority with one
  enrolled target and one source holder whose machine reads the source
  ring (holding \"A\" at its origin), writes the value it read to the
  enrolled target, then :side to the side log: the shape of 14.2.4
  rows 3, 4, 5 and 8.  `:inbound` overrides the source's inbound (the
  held medium the delay rows supply, a [handle sent-atom] pair);
  `:journals` overrides the substrate; `:backend` overrides the
  authority backend (the poisonable backend of row 8's first check)."
  [& {:keys [inbound journals backend]}]
  (let [dir (ct/temp-dir)
        jroot (ct/temp-dir)
        journals (or journals (compose/file-journals jroot))
        authority-backend (or backend
                              (get (file-journal/backend! dir)
                                   ::file-journal/backend))
        source (s2/ring 8)
        _ (stream/append! source "A")
        side (:dao.stream/handle
               (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
        w {:dir dir
           :jroot jroot
           :journals journals
           :backend authority-backend
           :clock (atom {:s 1})
           :store (mem/create-content-mem)
           :diagnostics (ct/log-writer)
           :attaches (atom 0)
           :observes (atom 0)
           :source source
           :side side
           :source-id (identity-of source)
           :side-id (identity-of side)}
        c (open-rows w authority-backend)
        enrolled (compose/enroll! c)
        i (:yin.k/target enrolled)
        _ (is (some? i) "the composition enrolled the boundary target")
        reader (compose/target-reader c i)
        table {(:source-id w) source
               i reader
               (:side-id w) side}
        protection {(:source-id w) :at-least-once
                    i :enrolled
                    (:side-id w) :at-least-once}
        ;; the world the source's config reads, its target known
        w (assoc w :target i :reader reader :table table
                 :protection protection)
        config (rows-config w table protection)
        c (compose/source c "h1"
                          (cond-> config
                            inbound (assoc :inbound (nth inbound 0))))]
    (assoc w
           :target i
           :reader reader
           :protection protection
           :table table
           :sent (when inbound (nth inbound 1))
           :source-config config
           :c c)))


(defn- rebuild-rows
  "A fresh composition over the rows world's stable configuration --
  the same authority directory, journal substrate, store, clock and
  streams -- the reconstruction a restarted process performs.  The
  source holder is recovered by reopen at its addition (its journal
  already stands); nothing of the crashed composition survives."
  [w]
  (let [backend (get (file-journal/backend! (:dir w)) ::file-journal/backend)
        c (open-rows w backend)
        reader (compose/target-reader c (:target w))
        table (assoc (:table w) (:target w) reader)
        c (compose/source c "h1" (rows-config w table (:protection w)))]
    (assoc w :backend backend :reader reader :table table :c c)))


(defn- candidate-over
  "Holder `h` added over the world's current table and the checkpoint
  `cp` -- a second candidate over the same occurrence."
  [w c cp h]
  (compose/holder c h (assoc (rows-config w (:table w) (:protection w))
                             :bytes (:bytes cp)
                             :address (:address cp))))


(defn- candidate-over-seams
  "A candidate's config over the rows world's seams and checkpoint
  `cp` (the config `candidate-over` adds, without the holder)."
  [w cp]
  (assoc (rows-config w (:table w) (:protection w))
         :bytes (:bytes cp)
         :address (:address cp)))


(defn- variant-of
  "A second encoding of the occurrence whose body stands at `address`:
  the same baseline in different bytes, the way the variant fixtures
  mutate a base."
  [store address]
  (let [body (cbor/decode ((:get-bytes-fn store) address ::absent))
        bytes (cbor/encode (assoc body :yin.k/id-counter 9))
        v-address (keyword "segment"
                           (str "blake3-" (jing/digest-bytes :blake3 bytes)))]
    (is (not= address v-address) "the second encoding is different bytes")
    ((:put-bytes-fn store) v-address bytes)
    {:bytes bytes :address v-address}))


(defn- run-to-write
  "Drive the rows world's source holder from its export to the moment
  its enrolled write has left the machine: running, the read recorded,
  the admit request standing in the inbound -- carried by nobody, for
  the emit rides the program half and the front reads only on the next
  control tick.  Answers the composition at that cut."
  [c]
  (let [c (step-until c #(= :running (get-in % [:holders "h1" :phase])) 60)]
    (step-until c #(seq (inbound-of % "h1" :yin.k/admit)) 40)))


(defn- admit-request
  "The front's closed :yin.k/admit request shape, in the writer's
  envelope grammar, for the authored admissions of rows 3, 5 and 8."
  [rid target envelope]
  {:yin.k/request :yin.k/admit
   :yin.k/request-id rid
   :yin.k/target target
   :yin.k/fenced-envelope envelope})


(defn- envelope-of
  "The fenced envelope of a write of `value` at op id `op-id` under
  `lease` at `epoch`."
  [lease epoch op-id value]
  {:yin.k/envelope :yin.k/fenced-v1
   :yin.k/incarnation lease
   :yin.k/epoch epoch
   :yin.k/op-id op-id
   :yin.k/value value})


(defn- last-admit-reply
  "The answer of holder `h`'s latest :yin.k/admit reply."
  [c h]
  (-> (replies-of c h :yin.k/admit) peek :yin.k/answer))


(defn- admissions-of
  "The admission family answers of holder `h`'s :yin.k/admit replies."
  [c h]
  (mapv (comp :yin.k/admission :yin.k/answer) (replies-of c h :yin.k/admit)))


;; =============================================================================
;; The row harnesses: one world, one teardown
;; =============================================================================


(defn- safe
  "Run `f`, swallowing a failure: teardown must never block."
  [f]
  (try
    (f)
    (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- exclusive-row
  "Run `f` over a fresh exclusive world (the parked-reader source of
  rows 1, 2, 6 and 7), retiring whatever stands at the end.  `make`
  builds the world (the default exclusive world; row 6 passes its own,
  over the held inbound)."
  ([f] (exclusive-row f (fn [] (ct/exclusive-world))))
  ([f make]
   (let [w (assoc (make) :source-id "prog-c")]
     (try
       (f w)
       (finally
         (safe #(compose/close! (:c w)))
         (safe #(file-journal/close! (:backend w)))
         (ct/cleanup-dir! (:dir w))
         (ct/cleanup-dir! (:jroot w)))))))


(defn- rows-row
  "Run `f` over an atom holding a fresh rows world, retiring whatever
  composition stands in it at the end -- the crash rows swap the atom's
  world for its rebuild.  `make` builds the world (rows-world by
  default; the delay rows pass their own)."
  ([f] (rows-row f rows-world))
  ([f make]
   (let [w (atom (make))]
     (try
       (f w)
       (finally
         (safe #(compose/close! (:c @w)))
         (safe #(file-journal/close! (:backend @w)))
         (ct/cleanup-dir! (:dir @w))
         (ct/cleanup-dir! (:jroot @w)))))))


(defn- crash-rows!
  "The rows world's process dies; the atom takes the rebuild."
  [w c]
  (crash! @w c)
  (reset! w (rebuild-rows @w)))


(defn- rebuilt-exclusive
  "The exclusive world's reconstruction after a crash: a fresh
  composition over the same authority directory, journal substrate,
  store, clock and source config."
  [w]
  (ct/reopened-world (select-keys w
                                  [:dir :store :clock :journals
                                   :source-config])))


(defn- crash-exclusive!
  "The process dies with the exclusive world's composition."
  [w c]
  (compose/close! c)
  (file-journal/close! (:backend w)))


;; =============================================================================
;; Row 1: two encodings of one occurrence, two candidates
;; =============================================================================


(deftest row-1-one-admitted-holder-over-two-encodings-test
  (exclusive-row
    (fn [w]
      (let [c0 (step-until (:c w)
                           #(seq (inbound-of % "h1" :yin.k/offer)) 30)
            cp (checkpoint-of c0 (:store w) "h1")
            _ (is (some? (:occurrence cp)) "the source minted and fenced")
            v (variant-of (:store w) (:address cp))
            _ (stream/append! (get-in c0 [:inbounds "h1"])
                              {:yin.k/request :yin.k/offer
                               :yin.k/request-id
                               [:compose-rows/variant (:occurrence cp)]
                               :yin.k/id (:address v)
                               :yin.k/bytes (:bytes v)
                               :yin.k/medium "compose-test"})
            c0 (compose/holder c0 "h2"
                               (assoc (candidate-seams w)
                                      :bytes (:bytes v)
                                      :address (:address v)))
            c (step-until c0 #(= :running (get-in % [:holders "h1" :phase]))
                          60)
            projection (authority/projection (:authority c))]
        (is (= #{(:address cp) (:address v)}
               (get-in projection [:occurrences (:occurrence cp)
                                   :yin.k/variants]))
            "both encodings of the one occurrence are admitted variants")
        (let [loser (get-in c [:holders "h2"])]
          (is (= :yin.k/not-holder (:status loser))
              (pr-str (select-keys loser [:phase :status :detail])))
          (is (= "h1" (:dao.lease/holder (:detail loser)))
              "the refusal names the holder that won")
          (is (nil? (:machine loser))
              "no machine was ever exposed to the refused candidate")
          (is (= 1 (count (distinct (map :dao.lease/proposal
                                         (inbound-of c "h2"
                                                     :yin.k/proposal)))))
              "it proposes no further while the occurrence is held")
          (is (empty? (inbound-of c "h2" :yin.k/release))
              "the loser acquired nothing and released nothing"))
        (is (= 1 (count (filter some? (map :dao.lease/lease
                                           (vals (:occurrences
                                                   projection))))))
            "the occurrence is held by exactly one lease")
        (reset! (:clock w) {:s 100})
        (let [c' (step-until c #(= :running (get-in % [:holders "h2" :phase]))
                             90)
              winner (get-in c' [:holders "h2"])]
          (is (= :running (:phase winner)))
          (is (= (:occurrence cp) (:occurrence winner))
              "the second encoding holds the same occurrence")
          (is (= 2 (count (distinct (map :dao.lease/proposal
                                         (inbound-of c' "h2"
                                                     :yin.k/proposal)))))
              "the fresh step proposed a fresh id, never the answered
               one"))))))


;; =============================================================================
;; Row 2: the source emits nothing until its own grant
;; =============================================================================


(deftest row-2-the-source-emits-nothing-until-its-own-grant-test
  (exclusive-row
    (fn [w]
      (let [c0 (loop [c (:c w) k 0]
                 (if (or (seq (inbound-of c "h1" :yin.k/offer)) (>= k 8))
                   c
                   (recur (compose/program-step c) (inc k))))
            cp (checkpoint-of c0 (:store w) "h1")
            _ (is (some? (:occurrence cp)) "the export fenced and offered")
            c0 (compose/holder c0 "h0"
                               (assoc (candidate-seams w)
                                      :bytes (:bytes cp)
                                      :address (:address cp)))
            source-machine (get-in w [:source-config :machine])]
        (is (zero? @(:attaches w)) "before any grant nothing was attached")
        (is (vm/blocked? source-machine)
            "the source's own local machine stays parked where it was
             built, emitting nothing")
        (is (= :exporting
               (vm/gate-mode (get-in c0 [:holders "h1" :export :m])))
            "the fenced copy the driver holds is fenced, never running")
        (is (empty? (:wait-set (get-in c0 [:holders "h1" :export :m]))))
        (let [c (step-until c0 #(= :running (get-in % [:holders "h0" :phase]))
                            60)
              st (get-in c [:holders "h1"])]
          (is (= :yin.k/not-holder (:status st))
              (pr-str (select-keys st [:phase :status :detail])))
          (is (= "h0" (:dao.lease/holder (:detail st))))
          (is (= 1 @(:attaches w))
              "only the winner's activation attached a stream")
          (is (vm/blocked? source-machine)
              "a grant to another never woke the source's local machine")
          (reset! (:clock w) {:s 100})
          (let [c' (step-until c #(= :running (get-in % [:holders "h1" :phase]))
                               90)
                st' (get-in c' [:holders "h1"])]
            (is (= :running (:phase st')))
            (is (= :running (vm/gate-mode (:machine st'))))
            (is (not= source-machine (:machine st'))
                "the resumed task is the lowered body, never the
                 source's own local machine")
            (is (= 2 @(:attaches w))
                "the source's own activation is the second attach")
            (is (vm/blocked? source-machine))
            (stream/append! (:prog w) "B")
            (let [c'' (ct/drive c' "h1" (ct/phase-of :safepoint) 12)]
              (is (= :safepoint (get-in c'' [:holders "h1" :phase]))
                  "the run reads its program stream and halts only under
                   its own grant"))))))))


;; =============================================================================
;; Row 6 (unit): a lost request stream, identical retries, one answer
;; =============================================================================


(deftest row-6-a-lost-request-stream-retries-unchanged-requests-test
  (let [gate (atom true)
        [inbound sent] (held-inbound "row-6-inbound" gate)
        offers-of (fn [rs]
                    (filterv #(= :yin.k/offer (:yin.k/request %)) rs))
        proposals-of (fn [rs]
                       (filterv #(= :yin.k/proposal (:yin.k/request %)) rs))]
    (exclusive-row
      (fn [w]
        (let [c (step-until (:c w) (fn [_] (seq (offers-of @sent))) 8)
              c (reduce (fn [c _] (compose/step c)) c (range 4))
              offers (offers-of @sent)]
          (is (< 1 (count offers))
              "the bracket resent the offer across control ticks")
          (is (apply = (map :yin.k/request-id offers))
              "every retry is the identical request")
          (is (apply = (map :yin.k/id offers))
              "over the identical body")
          (reset! gate false)
          (let [c (step-until c #(= :proposing (get-in % [:holders "h1" :phase]))
                              20)
                projection (authority/projection (:authority c))
                o (:occurrence (get-in c [:holders "h1"]))]
            (is (= 1 (count (get-in projection [:occurrences o
                                                :yin.k/variants])))
                "however many identical copies the front read, one
                 offered occurrence with one variant stands")
            (reset! gate true)
            (let [c (step-until c (fn [_] (seq (proposals-of @sent))) 12)
                  c (reduce (fn [c _] (compose/step c)) c (range 4))
                  proposals (proposals-of @sent)]
              (is (< 1 (count proposals))
                  "the proposal resent while its carriage was lost")
              (is (apply = (map :yin.k/request-id proposals))
                  "every retry is the identical proposal")
              (is (= 1 (count (distinct (map :dao.lease/proposal
                                             proposals))))
                  "one proposal id, never a second")
              (reset! gate false)
              (let [c' (step-until c #(= :running (get-in % [:holders "h1" :phase]))
                                   40)
                    projection' (authority/projection (:authority c'))
                    pid (:dao.lease/proposal (peek proposals))]
                (is (= :running (get-in c' [:holders "h1" :phase])))
                (is (= 1 (count (filter #(= ["h1" pid] %)
                                        (keys (:answered projection')))))
                    "the judge answered the proposal id exactly once")
                (is (= 1 (count (filter some? (vals (:leases projection')))))
                    "exactly one grant, so exactly one lease")
                (is (= 1 (count (journal-acks c' "h1")))
                    "one accepted grant in the journal"))))))
      (fn [] (ct/exclusive-world :inbound inbound)))))


;; =============================================================================
;; Row 3: a delayed protected effect across a reclaim serializes
;; =============================================================================


(deftest row-3-a-delayed-effect-across-a-reclaim-serializes-test
  (let [gate (atom false)
        [inbound sent] (held-inbound "row-3-inbound" gate)]
    (rows-row
      (fn [w]
        (let [c (run-to-write (:c @w))
              o (:occurrence (get-in c [:holders "h1"]))
              id {:yin.k/occurrence o :yin.k/seq 0}
              _ (is (= 1 (count (inbound-of c "h1" :yin.k/admit)))
                    "the write's admit left the machine")
              _ (reset! gate true)
              _ (reset! (:clock @w) {:s 100})
              c (reduce (fn [c _] (compose/step c)) c (range 6))
              projection (authority/projection (:authority c))
              _ (is (nil? (get-in projection
                                  [:occurrences o :dao.lease/lease]))
                    "the reclaim ended the tenure")
              _ (is (= 1 (get-in projection [:occurrences o :yin.k/epoch]))
                    "the reclaim raised the epoch once")
              c (candidate-over @w c (checkpoint-of c (:store @w) "h1") "h2")
              c (step-until c #(= :safepoint (get-in % [:holders "h2" :phase]))
                            80)
              h2 (get-in c [:holders "h2"])
              h2-lease (:lease h2)
              h2-epoch (get-in h2 [:evidence :yin.k/binding :yin.k/epoch])
              _ (is (some? h2-lease) "the regrant is live")]
          (is (= :safepoint (:phase h2)))
          (is (= ["A"] (target-values @w))
              "one commit in total on the enrolled target")
          (is (some #(= :committed %) (admissions-of c "h2"))
              "the regranted write committed at the checkpoint's id")
          (is (every? #{:committed :replayed} (admissions-of c "h2"))
              "any resend replays the stored result")
          (let [n (count (replies-of c "h1" :yin.k/admit))
                _ (reset! gate false)
                c (step-until c #(> (count (replies-of % "h1" :yin.k/admit))
                                    n)
                              20)
                answer (last-admit-reply c "h1")]
            (is (= :stale (:yin.k/admission answer)) (pr-str answer))
            (is (= ["A"] (target-values @w))
                "the old effect, delivered after the reclaim, committed
                 nothing"))
          (let [replies (replies-of c "h2" :yin.k/admit)
                _ (stream/append!
                    (get-in c [:inbounds "h2"])
                    (admit-request [:yin.k/admit h2-lease id] (:target @w)
                                   (envelope-of h2-lease h2-epoch id "A")))
                c (step-until c #(> (count (replies-of % "h2" :yin.k/admit))
                                    (count replies))
                              20)
                answers (mapv :yin.k/answer
                              (replies-of c "h2" :yin.k/admit))]
            (is (= :replayed (:yin.k/admission (peek answers)))
                (pr-str (peek answers)))
            (is (= (get-in (first answers) [:yin.k/result])
                   (get-in (peek answers) [:yin.k/result]))
                "the new holder's equal id and intent answers the stored
                 result")
            (is (= ["A"] (target-values @w))
                "the replay committed nothing: never both"))))
      (fn [] (rows-world :inbound [inbound sent])))))


;; =============================================================================
;; Row 4: crash cuts around the atomic commitment
;; =============================================================================


(deftest row-4-cut-before-the-commit-test
  (testing "cut before the commit: the regrant retries the same id and
            commits once"
    (rows-row
      (fn [w]
        (let [c (run-to-write (:c @w))
              ;; the crash: the admit was never carried, so nothing
              ;; committed; the process dies between the emit and the
              ;; carriage
              _ (crash-rows! w c)
              ;; the recovered holder releases, re-proposes and is
              ;; regranted: its run replays the durable input and
              ;; retries the write at the same id
              c' (step-until (:c @w)
                             #(= :safepoint (get-in % [:holders "h1" :phase]))
                             90)]
          (is (= ["A"] (target-values @w)) "exactly one commit")
          (is (= 1 @(:observes @w))
              "the first tenure observed the read live; the regrant
               replayed it, observing nothing")
          (is (some #(= :committed %) (admissions-of c' "h1"))
              "the regranted write committed")
          (is (every? #{:committed :replayed} (admissions-of c' "h1"))
              "at the stable id, zero or one commit"))))))


(deftest row-4-cut-after-the-commit-before-the-result-test
  (testing "cut after the commit, before the result: the recorded result
            is redelivered and no side effect repeats"
    (rows-row
      (fn [w]
        (let [c (run-to-write (:c @w))
              ;; one control step carries the admit and commits it at
              ;; the front's landed admission; the holder's inbox
              ;; drained before the fronts ran, so the outcome is read
              ;; by nobody
              c (compose/control-step c)
              _ (is (= 1 (count (replies-of c "h1" :yin.k/admit)))
                    "the commit's reply stands")
              _ (crash-rows! w c)
              c' (step-until (:c @w)
                             #(= :safepoint (get-in % [:holders "h1" :phase]))
                             90)
              answers (mapv :yin.k/answer
                            (replies-of c' "h1" :yin.k/admit))]
          (is (< 1 (count answers))
              "the first tenure's commit and the regrant's retry")
          (is (= :committed (:yin.k/admission (first answers))))
          (is (= :replayed (:yin.k/admission (peek answers)))
              "the retry at the same id and intent replays")
          (is (= 1 (count (distinct (mapv :yin.k/result answers))))
              "the recorded result is redelivered")
          (is (= ["A"] (target-values @w))
              "zero or one commit: never a duplicate side effect"))))))


(deftest row-4-external-io-earns-no-exactly-once-test
  (testing "external IO outside the transaction earns no exactly-once"
    (rows-row
      (fn [w]
        (let [c (run-to-write (:c @w))
              c (step-until c #(= :safepoint (get-in % [:holders "h1" :phase]))
                            40)
              _ (is (= [:side] (read-all (:side @w)))
                    "the first tenure's side effect landed")
              _ (crash-rows! w c)
              _ (step-until (:c @w)
                            #(= :safepoint (get-in % [:holders "h1" :phase]))
                            90)]
          (is (= ["A"] (target-values @w))
              "the enrolled write stays exactly-once inside the
               transaction")
          (is (= [:side :side] (read-all (:side @w)))
              "the at-least-once side effect outside it repeats across
               the regrant: no exactly-once is earned there"))))))


;; =============================================================================
;; Row 5: an evicted kept-cursor value
;; =============================================================================


(defn- evict!
  "Append junk past the source ring's capacity, evicting every value
  the checkpoint's kept cursor still names."
  [w]
  (let [origin (:dao.stream/cursor (stream/cursor (:source w)
                                                  :dao.stream/oldest))]
    (dotimes [_ 9] (stream/append! (:source w) :junk))
    (is (= :dao.stream/gap
           (:dao.stream/outcome (stream/next (:source w) origin)))
        "the kept position was evicted at the source")))


(deftest row-5-an-evicted-kept-cursor-value-recovers-with-inputs-test
  (rows-row
    (fn [w]
      (let [c (run-to-write (:c @w))
            _ (is (= 1 @(:observes @w)) "the first tenure read live once")
            _ (crash-rows! w c)
            _ (evict! @w)
            c' (step-until (:c @w)
                           #(= :safepoint (get-in % [:holders "h1" :phase]))
                           90)]
        (is (= ["A"] (target-values @w))
            "replay reproduced the old intent: the recorded value, not
             the live eviction")
        (is (= 1 @(:observes @w))
            "the regrant replayed its durable input, observing nothing
             live through the evicted position")
        (is (some #(= :committed %) (admissions-of c' "h1"))
            "the replayed input drove the retried write")))))


(deftest row-5-an-evicted-kept-cursor-value-without-inputs-test
  (rows-row
    (fn [w]
      (let [c (step-until (:c @w)
                          #(= :running (get-in % [:holders "h1" :phase]))
                          60)
            c (step-until c #(seq (inbound-of % "h1" :yin.k/input)) 20)
            _ (is (empty? (replies-of c "h1" :yin.k/input))
                  "the input record was carried by nobody")
            _ (crash-rows! w c)
            _ (evict! @w)
            c' (step-until (:c @w) (fn [_] (< 1 @(:observes @w))) 90)
            st (get-in c' [:holders "h1"])]
        (is (< 1 @(:observes @w))
            "the recovering tenure re-read live: no durable input to
             replay")
        (is (= [] (target-values @w))
            (str "the recovery committed nothing through the evicted "
                 "re-read; the holder stands at "
                 (pr-str (select-keys st [:phase :status :detail]))))))))


(deftest row-5-a-divergent-intent-at-the-same-id-is-intent-conflict-test
  (rows-row
    (fn [w]
      (let [c (run-to-write (:c @w))
            c (step-until c #(seq (replies-of % "h1" :yin.k/admit)) 40)
            st (get-in c [:holders "h1"])
            o (:occurrence st)
            id {:yin.k/occurrence o :yin.k/seq 0}
            lease (:lease st)
            epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
            _ (is (= :committed (first (admissions-of c "h1")))
                  "the tenure's write committed at the id")
            n (count (replies-of c "h1" :yin.k/admit))
            _ (stream/append!
                (get-in c [:inbounds "h1"])
                (admit-request [:yin.k/admit lease id] (:target @w)
                               (envelope-of lease epoch id
                                            :the-evicted-value)))
            c (step-until c #(> (count (replies-of % "h1" :yin.k/admit)) n)
                          20)
            answer (last-admit-reply c "h1")
            projection (authority/projection (:authority c))]
        (is (= :intent-conflict (:yin.k/admission answer)) (pr-str answer))
        (is (= id (:yin.k/op-id answer)))
        (is (= ["A"] (target-values @w))
            "the divergent write committed nothing")
        (is (true? (get-in projection [:occurrences o :yin.k/quarantined]))
            "the authoritative conflict quarantined the occurrence")))))


;; =============================================================================
;; Row 7: crash cuts around completion
;; =============================================================================


(defn- exit-world
  "The exclusive world's source run to its safepoint with the exit
  armed: the continuation's hand-off, ready for the cuts."
  [w]
  (let [c (ct/drive (:c w) "h1" (ct/phase-of :running) 60)
        c (compose/hand-off c "h1")]
    (is (= :safepoint (get-in c [:holders "h1" :phase])))
    c))


(defn- successor-checkpoint
  "The exit's successor occurrence and its body's address and bytes,
  read from the holder's journal and the store."
  [c store h]
  (let [records (ct/journal-records-of c h)
        mint (some (fn [r]
                     (when (and (= :yin.k/minted (:yin.k/journal r))
                                (= :yin.k/successor (:yin.k/role r)))
                       r))
                   records)
        fence (some (fn [r]
                      (when (and (= :yin.k/fenced (:yin.k/journal r))
                                 (= (:yin.k/occurrence mint)
                                    (:yin.k/occurrence r)))
                        r))
                    records)]
    (when (some? fence)
      {:occurrence (:yin.k/occurrence mint)
       :address (:yin.k/address fence)
       :bytes ((:get-bytes-fn store) (:yin.k/address fence) ::absent)})))


(deftest row-7-cut-after-the-successor-append-test
  (exclusive-row
    (fn [w]
      (let [c (step-until (exit-world w)
                          #(journal-has? % "h1"
                                         (fn [r]
                                           (and (= :yin.k/attempt (:yin.k/journal r))
                                                (= :yin.k/offer (:yin.k/action r))
                                                (some? (get-in % [:holders "h1" :exit :occurrence]))
                                                (= (get-in % [:holders "h1" :exit :occurrence])
                                                   (:yin.k/occurrence r)))))
                          40)
            origin (:occurrence (get-in c [:holders "h1"]))
            successor (successor-checkpoint c (:store w) "h1")]
        (is (some? successor))
        (is (empty? (inbound-of c "h1" :yin.k/resumed)))
        (crash-exclusive! w c)
        (let [rebuilt (rebuilt-exclusive w)]
          (try
            (let [c2 (step-until (:c rebuilt)
                                 #(= :running (get-in % [:holders "h1" :phase]))
                                 90)
                  p (authority/projection (:authority c2))]
              (is (= origin (get-in c2 [:holders "h1" :occurrence]))
                  "an uncommitted report recovers the last recorded checkpoint")
              (is (nil? (get-in p [:occurrences origin :yin.k/closed])))
              (is (nil? (get-in p [:occurrences (:occurrence successor)]))
                  "before closure the attempted successor is ineligible"))
            (finally (ct/close-reconstruction! rebuilt))))))))


(defn- transaction-facts
  [record]
  (let [datoms (get-in record [:dao.space/transaction :datoms])]
    (mapv (fn [entity]
            (into {} (keep (fn [[candidate attribute value]]
                             (when (= entity candidate) [attribute value]))) datoms))
          (distinct (map first datoms)))))


(defn- completion-transactions
  [composition origin-lease]
  (filterv #(some (fn [fact]
                    (and (= :dao.lease/lapsed (:dao.lease/status fact))
                         (= origin-lease (:dao.lease/lease fact)))) %)
           (mapv transaction-facts (read-all (:journal (:authority composition))))))


(defn- assert-completion-transaction
  [composition origin origin-lease address successor cause]
  (let [projection (authority/projection (:authority composition))]
    (is (= [[(lease/lapsed origin-lease cause)
             (completion/completed origin origin-lease)
             (if successor (completion/succeeded origin successor)
                 (completion/terminated origin address))
             (custody/reclaimed origin origin-lease 1)]]
           (completion-transactions composition origin-lease)))
    (is (= cause (get-in projection [:leases origin-lease :dao.lease/cause])))
    (is (= address (get-in projection [:leases origin-lease :yin.k/result])))
    (is (= origin-lease (get-in projection [:occurrences origin :yin.k/closed :dao.lease/lease])))
    (is (= 1 (get-in projection [:occurrences origin :yin.k/epoch])))
    (is (nil? (get-in projection [:occurrences origin :dao.lease/lease])))))


(deftest row-7-cut-after-the-resumed-report-test
  (testing "cut after the resumed report"
    (exclusive-row
      (fn [w]
        (let [c (step-until (exit-world w)
                            #(some? (get-in (authority/projection (:authority %))
                                            [:leases (get-in % [:holders "h1" :lease]) :yin.k/result]))
                            60)
              first-resumed (inbound-of c "h1" :yin.k/resumed)
              origin (get-in c [:holders "h1" :occurrence])
              origin-lease (get-in c [:holders "h1" :lease])
              successor (successor-checkpoint c (:store w) "h1")
              _ (is (= 1 (count (distinct (map :yin.k/request-id
                                               first-resumed)))))
              _ (is (empty? (inbound-of c "h1" :yin.k/release))
                    "the accepted report cut precedes the first release send")
              _ (crash-exclusive! w c)
              rebuilt (rebuilt-exclusive w)
              c2 (ct/drive (:c rebuilt) "h1" (ct/phase-of :exited) 90)
              resumed (into first-resumed
                            (inbound-of c2 "h1" :yin.k/resumed))]
          (is (= :exited (get-in c2 [:holders "h1" :phase])))
          (is (= 1 (count (distinct (map :yin.k/request-id resumed))))
              "the recovered holder resent the identical report")
          (is (= first-resumed resumed)
              "an acknowledged report needs no speculative resend")
          (assert-completion-transaction c2 origin origin-lease (:address successor)
                                         (:occurrence successor) :policy)
          (is (some? (get-in (authority/projection (:authority c2))
                             [:occurrences (:occurrence successor)])))
          (is (empty? (inbound-of c2 "h1" :yin.k/release)))
          (is (not (journal-has? c2 "h1" #(and (= :yin.k/ack (:yin.k/journal %))
                                               (= :yin.k/release (:yin.k/action %))))))
          (ct/close-reconstruction! rebuilt))))))


(deftest row-7-cut-after-the-release-append-test
  (testing "cut after the release append"
    (exclusive-row
      (fn [w]
        (let [c (step-until (exit-world w)
                            #(journal-has? % "h1"
                                           (fn [r]
                                             (and (= :yin.k/attempt
                                                     (:yin.k/journal r))
                                                  (= :yin.k/release
                                                     (:yin.k/action r))
                                                  (= :dao.stream/ok
                                                     (:yin.k/append r)))))
                            60)
              first-released (inbound-of c "h1" :yin.k/release)
              origin (get-in c [:holders "h1" :occurrence])
              origin-lease (get-in c [:holders "h1" :lease])
              successor (successor-checkpoint c (:store w) "h1")
              _ (is (pos? (count first-released)) "the release left")
              _ (crash-exclusive! w c)
              rebuilt (rebuilt-exclusive w)
              c2 (ct/drive (:c rebuilt) "h1" (ct/phase-of :exited) 90)
              released (into first-released
                             (inbound-of c2 "h1" :yin.k/release))]
          (is (= :exited (get-in c2 [:holders "h1" :phase])))
          (is (= 1 (count (distinct (map :yin.k/request-id released))))
              "the recovered holder resent the identical release")
          (assert-completion-transaction c2 origin origin-lease (:address successor)
                                         (:occurrence successor) :policy)
          (is (empty? (inbound-of c2 "h1" :yin.k/release)))
          (is (not (true? (get-in c2 [:holders "h1" :release :carried]))))
          (ct/close-reconstruction! rebuilt))))))


(deftest completion-recovery-terminal-report-needs-no-release-test
  (exclusive-row
    (fn [world]
      (let [running (ct/drive (:c world) "h1" (ct/phase-of :running) 60)
            _ (stream/append! (:prog world) :input)
            composition (step-until running #(journal-has? % "h1"
                                                           (fn [record]
                                                             (and (= :yin.k/ack (:yin.k/journal record))
                                                                  (= :yin.k/resumed (:yin.k/action record))))) 80)
            state (get-in composition [:holders "h1"])
            _ (is (= :yin.k/result (get-in state [:exit :role])))
            _ (crash-exclusive! world composition)
            rebuilt (rebuilt-exclusive world)]
        (try
          (let [recovered (:c rebuilt)
                finished (ct/drive recovered "h1" (ct/phase-of :exited) 30)]
            (assert-completion-transaction finished (:occurrence state) (:lease state)
                                           (get-in state [:exit :address]) nil :policy)
            (is (nil? (get-in finished [:holders "h1" :machine])))
            (is (empty? (inbound-of finished "h1" :yin.k/offer)))
            (is (empty? (inbound-of finished "h1" :yin.k/release)))
            (is (not (true? (get-in finished [:holders "h1" :release :carried])))))
          (finally (ct/close-reconstruction! rebuilt)))))))


(deftest completion-recovery-historical-policy-without-closure-ends-test
  (exclusive-row
    (fn [world]
      (let [composition (step-until (exit-world world)
                                    #(journal-has? % "h1" (fn [record]
                                                            (and (= :yin.k/ack (:yin.k/journal record))
                                                                 (= :yin.k/resumed (:yin.k/action record))))) 60)
            state (get-in composition [:holders "h1"])
            successor (successor-checkpoint composition (:store world) "h1")
            committed (authority/transition!
                        (:authority composition)
                        (fn [_]
                          {::authority/facts [(lease/lapsed (:lease state) :policy)
                                              (custody/reclaimed (:occurrence state) (:lease state) 1)]
                           ::authority/reply {:yin.k/status :committed}}))
            _ (is (= :committed (:yin.k/status committed)))
            _ (crash-exclusive! world composition)
            rebuilt (rebuilt-exclusive world)]
        (try
          (let [finished (ct/drive (:c rebuilt) "h1" (ct/phase-of :failed) 20)
                projection (authority/projection (:authority finished))
                diagnostics (read-all (:diagnostics finished))]
            (is (= :incomplete-completion-history
                   (get-in finished [:holders "h1" :detail :cause])))
            (is (nil? (get-in projection [:occurrences (:occurrence state) :yin.k/closed])))
            (is (nil? (get-in projection [:occurrences (:occurrence successor)])))
            (is (empty? (inbound-of finished "h1" :yin.k/release)))
            (is (= 1 (count (filter #(= :yin.k/run-ended (:yin.k/diagnostic %)) diagnostics))))
            (is (= (get-in finished [:holders "h1"])
                   (get-in (compose/step finished) [:holders "h1"]))))
          (finally (ct/close-reconstruction! rebuilt)))))))


(declare reopen-composition-with)


(deftest completion-recovery-does-not-treat-unavailable-history-as-closure-test
  (exclusive-row
    (fn [world]
      (let [composition (step-until (exit-world world)
                                    #(journal-has? % "h1" (fn [record]
                                                            (and (= :yin.k/ack (:yin.k/journal record))
                                                                 (= :yin.k/resumed (:yin.k/action record))))) 60)
            _ (crash-exclusive! world composition)
            observes (atom 0)
            source-config (assoc (:source-config world) :observe!
                                 (fn [_ _ _]
                                   (swap! observes inc)
                                   (throw (ex-info "unexpected recovered program IO" {}))))
            backend (get (file-journal/backend! (:dir world)) ::file-journal/backend)
            opened (reopen-composition-with world backend)
            opened-composition (::compose/composition opened)
            available (atom false)
            original (:journal (:authority opened-composition))
            transport (reify stream/IDaoStreamReader
                        (cursor
                          [_ anchor]
                          (if @available (stream/cursor original anchor)
                              {:dao.stream/outcome :dao.stream/transport-error}))

                        (next
                          [_ cursor]
                          (if @available (stream/next original cursor)
                              {:dao.stream/outcome :dao.stream/transport-error}))


                        stream/IDaoStreamWriter

                        (append! [_ value] (stream/append! original value)))
            recovered (compose/source (assoc-in opened-composition [:authority :journal] transport)
                                      "h1" source-config)
            rebuilt {:c recovered :backend backend}]
        (try
          (let [waiting (compose/step recovered)]
            (is (= :exiting (get-in waiting [:holders "h1" :phase])))
            (is (= :yin.k/unsatisfied (get-in waiting [:holders "h1" :status])))
            (is (nil? (get-in waiting [:holders "h1" :machine])))
            (is (empty? (inbound-of waiting "h1" :yin.k/release)))
            (is (zero? @observes))
            (reset! available true)
            (let [finished (ct/drive waiting "h1" (ct/phase-of :exited) 40)]
              (is (nil? (get-in finished [:holders "h1" :machine])))
              (is (zero? @observes))))
          (finally (ct/close-reconstruction! rebuilt)))))))


(defn- reopen-composition-with
  [world backend]
  (compose/open! {:mode :exclusive :failure-model :process-crash
                  :backend backend :duration {:s 30} :renewal-interval {:s 10}
                  :store (:store world) :diagnostics (ct/log-writer)
                  :medium "compose-test" :clock (fn [] @(:clock world))
                  :journals (:journals world)}))


(deftest completion-recovery-unknown-policy-transaction-is-reconciled-once-test
  (doseq [cut [:before :after]]
    (exclusive-row
      (fn [world]
        (let [composition (step-until (exit-world world)
                                      #(journal-has? % "h1" (fn [record]
                                                              (and (= :yin.k/ack (:yin.k/journal record))
                                                                   (= :yin.k/resumed (:yin.k/action record))))) 60)
              state (get-in composition [:holders "h1"])
              successor (successor-checkpoint composition (:store world) "h1")
              _ (crash-exclusive! world composition)
              backend (get (file-journal/backend! (:dir world)) ::file-journal/backend)
              write! (:dao.stream.journal/write-frame! backend)
              cut-backend (assoc backend :dao.stream.journal/write-frame!
                                 (fn [bytes]
                                   (when (= :after cut) (write! bytes))
                                   (throw (ex-info "unknown policy transaction acceptance" {:cut cut}))))
              refused (reopen-composition-with world cut-backend)]
          (is (= :yin.k/unsatisfied (:yin.k/status refused)))
          (is (= :authority-refused (:yin.k/reason refused)))
          (file-journal/close! backend)
          (let [reader-backend (get (file-journal/backend! (:dir world)) ::file-journal/backend)
                opened (authority/open! reader-backend)
                reader-authority (::authority/authority opened)
                projection (authority/projection reader-authority)]
            (is (= :open (:yin.k/status opened)))
            (is (= (if (= :after cut) :policy nil)
                   (get-in projection [:leases (:lease state) :dao.lease/cause])))
            (is (= (if (= :after cut) 1 0)
                   (get-in projection [:occurrences (:occurrence state) :yin.k/epoch])))
            (is (= (= :after cut)
                   (some? (get-in projection [:occurrences (:occurrence state) :yin.k/closed]))))
            (authority/close! reader-authority)
            (file-journal/close! reader-backend))
          (let [rebuilt (rebuilt-exclusive world)
                finished (ct/drive (:c rebuilt) "h1" (ct/phase-of :exited) 40)]
            (assert-completion-transaction finished (:occurrence state) (:lease state)
                                           (:address successor) (:occurrence successor) :policy)
            (is (empty? (inbound-of finished "h1" :yin.k/release)))
            (ct/close-reconstruction! rebuilt)
            (let [again (rebuilt-exclusive world)]
              (try
                (assert-completion-transaction (:c again) (:occurrence state) (:lease state)
                                               (:address successor) (:occurrence successor) :policy)
                (finally (ct/close-reconstruction! again))))))))))


(deftest completion-recovery-without-an-accepted-report-abandons-successor-test
  (doseq [mode [:none :intent :queued]]
    (let [root (ct/temp-dir)
          substrate (compose/file-journals root)
          open-progress! (:progress-backend! substrate)
          journals (if (= :intent mode)
                     (assoc substrate :progress-backend!
                            (fn [holder]
                              (let [backend (open-progress! holder)
                                    write! (:dao.stream.journal/write-frame! backend)]
                                (assoc backend :dao.stream.journal/write-frame!
                                       (fn [bytes]
                                         (let [record (:dao.stream.journal/value (cbor/decode bytes))
                                               answer (write! bytes)]
                                           (when (and (= :yin.k/intent (:yin.k/journal record))
                                                      (= :yin.k/resumed (:yin.k/action record)))
                                             (throw (ex-info "report intent persisted before send" {})))
                                           answer))))))
                     substrate)
          gate (atom false)
          [inbound _] (held-inbound "uncommitted-report" gate)]
      (try
        (exclusive-row
          (fn [world]
            (let [ready (step-until (exit-world world)
                                    #(some? (get-in % [:holders "h1" :exit :address])) 40)
                  _ (when (= :queued mode) (reset! gate true))
                  composition (if (= :none mode) ready
                                  (step-until ready
                                              #(if (= :intent mode)
                                                 (= :stalled (get-in % [:holders "h1" :phase]))
                                                 (journal-has? % "h1" (fn [record]
                                                                        (and (= :yin.k/attempt (:yin.k/journal record))
                                                                             (= :yin.k/resumed (:yin.k/action record)))))) 40))
                  state (get-in composition [:holders "h1"])
                  successor (successor-checkpoint composition (:store world) "h1")
                  _ (is (nil? (get-in (authority/projection (:authority composition))
                                      [:leases (:lease state) :yin.k/result])))
                  _ (crash-exclusive! world composition)
                  rebuilt (rebuilt-exclusive (assoc world :journals substrate))]
              (try
                (let [running (ct/drive (:c rebuilt) "h1" (ct/phase-of :running) 80)
                      projection (authority/projection (:authority running))]
                  (is (= (:occurrence state) (get-in running [:holders "h1" :occurrence])))
                  (is (= 1 (get-in projection [:occurrences (:occurrence state) :yin.k/epoch])))
                  (is (nil? (get-in projection [:occurrences (:occurrence state) :yin.k/closed])))
                  (is (nil? (get-in projection [:occurrences (:occurrence successor)]))))
                (finally (ct/close-reconstruction! rebuilt)))))
          (fn [] (ct/exclusive-world :journals journals :inbound inbound)))
        (finally (ct/cleanup-dir! root))))))


(deftest completion-recovery-quarantine-is-terminal-and-successor-ineligible-test
  (exclusive-row
    (fn [world]
      (let [composition (step-until (exit-world world)
                                    #(journal-has? % "h1" (fn [record]
                                                            (and (= :yin.k/ack (:yin.k/journal record))
                                                                 (= :yin.k/resumed (:yin.k/action record))))) 60)
            state (get-in composition [:holders "h1"])
            successor (successor-checkpoint composition (:store world) "h1")
            target (:yin.k/target (authority/enroll! (:authority composition)))
            op-id {:yin.k/occurrence (:occurrence state) :yin.k/seq 0}
            _ (seam/commit-effect! (:authority composition) target op-id :first)
            quarantine (authority/transition! (:authority composition)
                                              (fn [_]
                                                {::authority/facts [{:yin.k/custody :yin.k/quarantined
                                                                     :yin.k/occurrence (:occurrence state)
                                                                     :yin.k/op-id op-id}]
                                                 ::authority/reply {:yin.k/status :committed}}))
            _ (is (= :committed (:yin.k/status quarantine)))
            _ (crash-exclusive! world composition)
            rebuilt (rebuilt-exclusive world)]
        (try
          (let [finished (ct/drive (:c rebuilt) "h1" (ct/phase-of :failed) 20)
                projection (authority/projection (:authority finished))]
            (is (= :quarantined-occurrence (get-in finished [:holders "h1" :detail :cause])))
            (is (nil? (get-in projection [:occurrences (:occurrence state) :yin.k/closed])))
            (is (nil? (get-in projection [:occurrences (:occurrence successor)])))
            (is (empty? (inbound-of finished "h1" :yin.k/release))))
          (finally (ct/close-reconstruction! rebuilt)))))))


(deftest completion-recovery-requires-the-exact-origin-lease-body-and-successor-test
  (doseq [mismatch [:lease :body :successor]]
    (exclusive-row
      (fn [world]
        (let [composition (step-until (exit-world world)
                                      #(some? (get-in % [:holders "h1" :exit :address])) 40)
              state (get-in composition [:holders "h1"])
              successor (successor-checkpoint composition (:store world) "h1")
              body (cbor/decode (:bytes successor))
              origin-lease (if (= :lease mismatch) "other-closing-lease" (:lease state))
              body (case mismatch
                     :lease (assoc-in body [:yin.k/origin :dao.lease/lease] origin-lease)
                     :body (assoc body :yin.k/id-counter 9)
                     :successor (assoc body :yin.k/occurrence (str (random-uuid))))
              bytes (cbor/encode body)
              address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bytes)))
              _ (when (= :lease mismatch)
                  (is (= :dao.stream/ok (:dao.stream/outcome
                                          (stream/append! (grant/writer (:authority composition))
                                                          (lease/lapsed (:lease state) :policy)))))
                  (is (= :dao.stream/ok (:dao.stream/outcome
                                          (stream/append! (grant/writer (:authority composition))
                                                          (lease/grant origin-lease (custody/subject (:occurrence state))
                                                                       "h1" {:s 30}
                                                                       {:dao.lease/proposal "other-proposal"})))))
                  nil)
              report (completion/report! (:authority composition) "h1"
                                         (completion/resumed (:occurrence state) origin-lease address) bytes)
              _ (is (= :committed (:yin.k/status report)))
              _ (crash-exclusive! world composition)
              rebuilt (rebuilt-exclusive world)]
          (try
            (let [finished (ct/drive (:c rebuilt) "h1" (ct/phase-of :failed) 20)
                  projection (authority/projection (:authority finished))]
              (is (= :completion-mismatch (get-in finished [:holders "h1" :detail :cause])))
              (is (nil? (get-in projection [:occurrences (:occurrence successor)])))
              (is (empty? (inbound-of finished "h1" :yin.k/release)))
              (is (= address (get-in projection [:leases origin-lease :yin.k/result]))))
            (finally (ct/close-reconstruction! rebuilt))))))))


(deftest row-7-cut-after-the-authoritative-closure-test
  (testing "cut after the authoritative closure"
    (exclusive-row
      (fn [w]
        (let [c0 (exit-world w)
              o (:occurrence (get-in c0 [:holders "h1"]))
              c (step-until c0
                            #(some? (get-in (authority/projection
                                              (:authority %))
                                            [:occurrences o :yin.k/closed]))
                            80)
              original (checkpoint-of c (:store w) "h1")
              _ (crash-exclusive! w c)
              rebuilt (rebuilt-exclusive w)
              c2 (:c rebuilt)
              successor (successor-checkpoint c2 (:store w) "h1")
              c2 (ct/drive c2 "h1" (ct/phase-of :exited) 60)
              projection (authority/projection (:authority c2))]
          (is (= :exited (get-in c2 [:holders "h1" :phase]))
              "the recovered holder observed the closure and finished")
          (is (some? (get-in projection [:occurrences o :yin.k/closed])))
          (let [c3 (compose/holder c2 "h8"
                                   (assoc (candidate-seams w)
                                          :bytes (:bytes original)
                                          :address (:address original)))
                c3 (reduce (fn [c _] (compose/step c)) c3 (range 8))
                h8 (get-in c3 [:holders "h8"])]
            (is (= :yin.k/not-holder (:status h8))
                (pr-str (select-keys h8 [:phase :status :detail])))
            (is (nil? (:lease h8))
                "the closed occurrence never grants again"))
          (let [c3 (compose/holder c2 "h9"
                                   (assoc (candidate-seams w)
                                          :bytes (:bytes successor)
                                          :address (:address successor)))
                c3 (step-until c3 #(= :running (get-in % [:holders "h9" :phase]))
                               60)]
            (is (= (:occurrence successor)
                   (:occurrence (get-in c3 [:holders "h9"])))
                "the recorded successor is eligible once"))
          (ct/close-reconstruction! rebuilt))))))


;; =============================================================================
;; Row 8: the admission-order fixture through the composition's fronts
;; =============================================================================


(defn- poisonable-backend
  "File backend `b` whose frame writes throw while `armed` (an atom) is
  truthy: the authority's journal answers a transport error, and the
  authority serves no projection."
  [b armed]
  (let [write! (:dao.stream.journal/write-frame! b)]
    (assoc b :dao.stream.journal/write-frame!
           (fn [bs]
             (if @armed
               (throw (ex-info "the authority journal is unavailable" {}))
               (write! bs))))))


(defn- diagnostics-of
  "The defective-envelope diagnostics of `defect` the authority
  appended to the world's diagnostic stream."
  [w defect]
  (filterv #(= defect (:yin.k/defect %)) (read-all (:diagnostics @w))))


(deftest row-8-an-unreadable-authority-suspends-test
  (testing "1. an unreadable authority suspends before anything else"
    (let [armed (atom false)
          root (ct/temp-dir)
          backend (poisonable-backend
                    (get (file-journal/backend! root) ::file-journal/backend)
                    armed)]
      (rows-row
        (fn [w]
          (let [c (run-to-write (:c @w))
                st (get-in c [:holders "h1"])
                id {:yin.k/occurrence (:occurrence st) :yin.k/seq 0}
                lease (:lease st)
                epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
                n (count (target-values @w))
                _ (reset! armed true)
                _ (stream/append!
                    (get-in c [:inbounds "h1"])
                    (admit-request [:compose-rows/poisoned (:occurrence st)]
                                   (:target @w)
                                   (envelope-of lease epoch id :v)))
                c (compose/control-step c)
                answer (last-admit-reply c "h1")]
            (is (= :suspended (:yin.k/admission answer)) (pr-str answer))
            (is (= n (count (target-values @w))) "nothing committed")))
        (fn [] (rows-world :backend backend))))))


(deftest row-8-the-admission-order-fixture-through-the-front-test
  (rows-row
    (fn [w]
      (let [c (compose/control-step (run-to-write (:c @w)))
            st (get-in c [:holders "h1"])
            o (:occurrence st)
            id0 {:yin.k/occurrence o :yin.k/seq 0}
            lease (:lease st)
            epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
            ask! (fn [c rid envelope h]
                   (let [n (count (replies-of c h :yin.k/admit))]
                     (stream/append! (get-in c [:inbounds h])
                                     (admit-request rid (:target @w)
                                                    envelope))
                     (if (= h "h7")
                       (compose/control-step c)
                       (step-until c #(> (count (replies-of % h :yin.k/admit))
                                         n)
                                   20 compose/control-step))))]
        (testing "2. a wrong author is diagnosed and commits nothing"
          (let [cp (checkpoint-of c (:store @w) "h1")
                c (compose/holder c "h7" (candidate-over-seams @w cp))
                n (count (target-values @w))
                c (ask! c [:compose-rows/wrong-author o]
                        (envelope-of lease epoch id0 :v) "h7")]
            (is (empty? (replies-of c "h7" :yin.k/admit))
                "a diagnosed request is never replied")
            (is (= 1 (count (diagnostics-of w :wrong-author))))
            (is (= n (count (target-values @w)))
                "the wrong author's envelope committed nothing")))
        (testing "6. a foreign op id is diagnosed and commits nothing"
          (let [n (count (target-values @w))
                replies (count (replies-of c "h1" :yin.k/admit))
                _ (stream/append!
                    (get-in c [:inbounds "h1"])
                    (admit-request
                      [:compose-rows/foreign o] (:target @w)
                      (envelope-of lease epoch
                                   {:yin.k/occurrence (str (random-uuid))
                                    :yin.k/seq 0} :v)))
                c (compose/control-step c)]
            (is (= replies (count (replies-of c "h1" :yin.k/admit)))
                "a diagnosed request is never replied")
            (is (= 1 (count (diagnostics-of w :foreign-op-id))))
            (is (= n (count (target-values @w)))
                "the foreign id's envelope committed nothing")))
        (let [c (step-until c #(seq (replies-of % "h1" :yin.k/admit)) 40 compose/control-step)
              committed (last-admit-reply c "h1")]
          (is (= :committed (:yin.k/admission committed))
              "the machine's own write committed at the id")
          (let [c (ask! c [:compose-rows/replay id0]
                        (envelope-of lease epoch id0 "A") "h1")
                answer (last-admit-reply c "h1")]
            (is (= :replayed (:yin.k/admission answer)) (pr-str answer))
            (is (= (get-in committed [:yin.k/result])
                   (get-in answer [:yin.k/result]))
                "5. equal intent at the recorded id answers the stored
                 result"))
          (let [id1 {:yin.k/occurrence o :yin.k/seq 1}
                c (ask! c [:compose-rows/fresh id1]
                        (envelope-of lease epoch id1 :v2) "h1")
                answer (last-admit-reply c "h1")]
            (is (= :committed (:yin.k/admission answer))
                "10. a fresh id commits")))
        (testing "9. a divergent intent at a recorded id conflicts, and
                  the quarantine suspends what follows"
          (reset! (:clock @w) {:s 100})
          (let [c (compose/holder c "h2"
                                  (candidate-over-seams
                                    @w (checkpoint-of c (:store @w) "h1")))
                c (step-until c #(= :running (get-in % [:holders "h2" :phase]))
                              90)
                h2 (get-in c [:holders "h2"])
                h2-lease (:lease h2)
                h2-epoch (get-in h2 [:evidence :yin.k/binding :yin.k/epoch])
                _ (is (some? h2-lease) "the regrant is live")
                c (ask! c [:compose-rows/conflict id0]
                        (envelope-of h2-lease h2-epoch id0 :the-other) "h2")
                answer (last-admit-reply c "h2")
                projection (authority/projection (:authority c))]
            (is (= :intent-conflict (:yin.k/admission answer))
                (pr-str answer))
            (is (true? (get-in projection
                               [:occurrences o :yin.k/quarantined]))
                "the conflict quarantined the occurrence")
            (let [id2 {:yin.k/occurrence o :yin.k/seq 2}
                  c (ask! c [:compose-rows/suspended id2]
                          (envelope-of h2-lease h2-epoch id2 :v3) "h2")
                  answer (last-admit-reply c "h2")]
              (is (= :suspended (:yin.k/admission answer))
                  (pr-str answer))
              (is (= ["A" :v2] (target-values @w))
                  "the quarantine admitted nothing after the conflict"))))))))


(deftest row-8-stale-binding-precedes-the-record-test
  (rows-row
    (fn [w]
      (let [c (compose/control-step (run-to-write (:c @w)))
            st (get-in c [:holders "h1"])
            id {:yin.k/occurrence (:occurrence st) :yin.k/seq 0}
            old-lease (:lease st)
            old-epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
            _ (reset! (:clock @w) {:s 100})
            c (candidate-over @w c (checkpoint-of c (:store @w) "h1") "h2")
            c (step-until c #(= :running (get-in % [:holders "h2" :phase])) 90)
            new-lease (get-in c [:holders "h2" :lease])]
        (doseq [lease [new-lease old-lease]]
          (stream/append! (get-in c [:inbounds "h2"])
                          (admit-request [:compose-rows/stale lease] (:target @w)
                                         (envelope-of lease old-epoch id "A")))
          (let [c2 (compose/control-step c)
                answer (last-admit-reply c2 "h2")]
            (is (= :stale (:yin.k/admission answer)))
            (is (= (get-in (authority/projection (:authority c2))
                           [:occurrences (:occurrence st) :yin.k/epoch])
                   (:yin.k/observed-epoch answer)))
            (is (= ["A"] (target-values @w)))))))))


(deftest row-8-a-closed-occurrence-is-stale-with-the-closure-named-test
  (rows-row
    (fn [w]
      (let [c (run-to-write (:c @w))
            c (step-until c #(= :safepoint (get-in % [:holders "h1" :phase]))
                          40)
            st (get-in c [:holders "h1"])
            o (:occurrence st)
            id0 {:yin.k/occurrence o :yin.k/seq 0}
            lease (:lease st)
            epoch (get-in st [:evidence :yin.k/binding :yin.k/epoch])
            c (ct/drive c "h1" (ct/phase-of :exited) 90)
            _ (is (some? (get-in (authority/projection (:authority c))
                                 [:occurrences o :yin.k/closed])))
            n (count (replies-of c "h1" :yin.k/admit))
            _ (stream/append!
                (get-in c [:inbounds "h1"])
                (admit-request [:compose-rows/closed o] (:target @w)
                               (envelope-of lease epoch id0 :v)))
            c (step-until c #(> (count (replies-of % "h1" :yin.k/admit)) n)
                          20)
            answer (last-admit-reply c "h1")]
        (is (= :stale (:yin.k/admission answer)) (pr-str answer))
        (is (true? (:yin.k/closed answer))
            "the stale answer names the closure")))))


;; =============================================================================
;; The stage-D gate's compose halves: the journal cuts around
;; freeze, storage and fencing (the D14 recovery seam's rehydration
;; rows, through the composition)
;; =============================================================================


(defn- cut-file-journals
  "file-journals over `root` whose progress backend's frame writes
  suffer a cut at the `position`-th write: `:before` writes nothing and
  throws, `:after` writes the frame and throws anyway -- the two ways a
  crash mid-append leaves the journal's answer uncertain.  The reply
  backend is untouched."
  [root position cut]
  (let [base (compose/file-journals root)]
    (assoc base
           :progress-backend!
           (fn [holder]
             (let [b ((:progress-backend! base) holder)
                   write! (:dao.stream.journal/write-frame! b)
                   armed (atom 0)]
               (assoc b :dao.stream.journal/write-frame!
                      (fn [bs]
                        (let [n (swap! armed inc)]
                          (when-not (and (= n position) (= :before cut))
                            (write! bs))
                          (if (= n position)
                            (throw (ex-info "the journal cut"
                                            {:position position}))
                            {:dao.stream/outcome :dao.stream/ok})))))))))


(deftest the-wired-compositions-journal-cuts-around-freeze-storage-and-fencing-test
  (doseq [position (range 2 9)
          cut [:before :after]]
    (testing (pr-str [position cut])
      (let [root (ct/temp-dir)
            w (ct/exclusive-world :journals (cut-file-journals
                                              root position cut))]
        (try
          (let [c (loop [c (:c w) k 0]
                    (if (or (= :stalled (get-in c [:holders "h1" :phase]))
                            (>= k 12))
                      c
                      (recur (compose/step c) (inc k))))
                st (get-in c [:holders "h1"])
                frames (ct/journal-records-of c "h1")
                minted (filter #(= :yin.k/minted (:yin.k/journal %)) frames)
                fenced? (some #(= :yin.k/fenced (:yin.k/journal %)) frames)]
            (is (contains? #{:stalled :exporting} (:phase st))
                (pr-str (select-keys st [:phase :status :detail])))
            (is (<= (count minted) 1)
                "one occurrence, minted at most once across the cut")
            (compose/close! c)
            (file-journal/close! (:backend w))
            (let [rebuilt (ct/reopened-world
                            (assoc (select-keys
                                     w
                                     [:dir :store :clock :source-config])
                                   :journals (compose/file-journals root)))
                  c2 (:c rebuilt)
                  st2 (get-in c2 [:holders "h1"])
                  frames2 (ct/journal-records-of c2 "h1")]
              (if fenced?
                (do (is (= :exporting (:phase st2))
                        "a complete fenced snapshot reopens fenced")
                    (is (= :exporting
                           (vm/gate-mode (get-in st2 [:export :m])))
                        "the rehydrated machine is fenced, never runnable")
                    (is (empty? (get-in st2 [:export :m :wait-set])))
                    (is (= 1 (count (get-in st2 [:export :record :wait-set])))
                        "the record holds the one authentic wait")
                    (is (= 1 (count (filter #(= :yin.k/minted (:yin.k/journal %))
                                            frames2)))
                        "the occurrence was never re-minted")
                    (let [fence (some (fn [r]
                                        (when (= :yin.k/fenced
                                                 (:yin.k/journal r))
                                          r))
                                      frames2)
                          stored ((:get-bytes-fn (:store w))
                                  (:yin.k/address fence) ::absent)
                          c2 (loop [c c2 k 0]
                               (if (or (seq (inbound-of c "h1"
                                                        :yin.k/offer))
                                       (>= k 12))
                                 c
                                 (recur (compose/step c) (inc k))))
                          offers (inbound-of c2 "h1" :yin.k/offer)]
                      (is (= (vec stored)
                             (vec (get-in (first offers) [:yin.k/bytes])))
                          "the resent offer reproduces the stored handoff
                           bytes exactly")
                      (is (zero? @(:attaches w))
                          "no program IO ran through the crash and the
                           recovery: nothing was ever attached")))
                (do (is (or (and (= :exporting (:phase st2))
                                 (nil? (:machine st2)))
                            (= :stalled (:phase st2))
                            (= :lifted (:phase st2))
                            (= :proposing (:phase st2)))
                        (str "an incomplete preparation recovers non-runnable: "
                             (pr-str (select-keys st2
                                                  [:phase :status :detail]))))
                    (is (<= (count (filter #(= :yin.k/minted (:yin.k/journal %))
                                           frames2))
                            1)
                        "the recovery minted no second occurrence")))
              (ct/close-reconstruction! rebuilt)))
          (finally
            (ct/cleanup-dir! (:dir w))
            (ct/cleanup-dir! (:jroot w))
            (ct/cleanup-dir! root)))))))


;; =============================================================================
;; The stage-D gate's landed halves, verified beside the rows
;; =============================================================================


(deftest the-exclusivity-gate-and-fork-label-hold-test
  "The stage-D gate's first two compose halves -- exclusive refused on a
   memory authority, fork labelled fork -- are D15's landed rows in
   compose-test; this row re-verifies them beside the D16 rows so the
   gate's evidence stands in one run."
  (let [opened (compose/open! {:mode :exclusive
                               :failure-model :process-crash
                               :backend (journal/memory-backend
                                          (atom []) nil)
                               :duration {:s 30}
                               :renewal-interval {:s 10}
                               :store (mem/create-content-mem)
                               :diagnostics (ct/log-writer)
                               :medium "compose-rows"
                               :clock (fn [] {:s 1})
                               :journals (compose/file-journals
                                           (str (ct/temp-dir) "/unused"))})]
    (is (= :yin.k/unsatisfied (:yin.k/status opened)))
    (is (= :exclusive-uncapable (:yin.k/reason opened)))
    (is (nil? (::compose/composition opened))
        "nothing is composed: no silent fork"))
  (let [c (get (ct/fork-world) ::compose/composition)
        c (compose/source c "h1"
                          {:protection {"x" :at-least-once}
                           :receiver (s/new-machine)
                           :attach! (attach-over {})
                           :observe! (counting-observe (atom 0))
                           :serve! (same-id-server)
                           :machine (reading-writer (s2/ring 8) (s2/ring 8)
                                                    (s2/ring 8))})]
    (is (= :yin.k/fork (:yin.k/policy c)) "the fork is labelled fork")
    (is (nil? (compose/arbitration c)) "and arbitrates nothing")))
