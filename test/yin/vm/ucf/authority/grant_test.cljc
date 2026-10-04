(ns yin.vm.ucf.authority.grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]))


(def ^:private arb "arb-c5")

(def ^:private occ fx/occurrence)

(def ^:private duration {:s 30})


(defn- fresh-frames
  "A journal whose header names `arb`, so bodies can name the authority
   before it opens."
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- auth
  ([frames] (auth frames nil))
  ([frames cut]
   (::authority/authority
     (authority/open! (journal/memory-backend frames cut)))))


(defn- body
  "Fixture `n` naming this test's authority."
  ([n] (body n identity))
  ([n f]
   (f (assoc (get fx/fixtures n)
             :yin.k/arbitration {:dao.stream/identity arb
                                 :dao.stream/descriptor
                                 {:dao.stream/type :dao.stream/journal}}))))


(defn- bytes-of
  [b]
  (let [bs (cbor/encode b)]
    {:address (fx/segment-address bs) :bytes bs}))


(defn- offer!
  ([a store b] (offer! a store b "carrier"))
  ([a store b medium]
   (let [{:keys [address bytes]} (bytes-of b)]
     (grant/offer! a store address bytes medium))))


(defn- status
  [r]
  (:yin.k/status r))


(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- records
  "Every transaction record of the journal's frames."
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- facts
  "The facts of kind `k` (custody or lease status) across the records."
  [frames k]
  (for [r (records frames)
        :let [ds (get-in r [:dao.space/transaction :datoms])
              es (set (keep (fn [[e a v]]
                              (when (and (#{:yin.k/custody :dao.lease/status}
                                          a)
                                         (= k v))
                                e))
                            ds))]
        e (sort es)]
    (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))


(defn- attributed
  "The journal's records, each attributed to the authority."
  [frames]
  (mapv (fn [r] [arb r]) (records frames)))


(defn- a-grant
  ([] (a-grant "lease-1" "holder-a"))
  ([l h]
   (lease/grant l (custody/subject occ) h duration
                {:dao.lease/proposal "p-1"})))


(defn- offered
  "A fresh authority with the successor fixture offered: the frames,
   the authority and the store."
  []
  (let [frames (fresh-frames)
        a (auth frames)
        store (mem/create-content-mem)]
    (offer! a store (body "successor"))
    [frames a store]))


;; =============================================================================
;; Offers
;; =============================================================================

(deftest an-offer-stores-the-body-then-records-its-baseline
  (let [frames (fresh-frames)
        a (auth frames)
        store (mem/create-content-mem)
        {:keys [address bytes]} (bytes-of (body "successor"))]
    (is (= {:yin.k/status :committed :yin.k/occurrence occ :dao.space/t 0}
           (grant/offer! a store address bytes "carrier")))
    (is (= (body "successor") (jing/get store address ::absent))
        "the accepted body is in the content store")
    (is (= [(custody/offer occ address "carrier"
                           (checkpoint/inspect address bytes))]
           (facts frames :yin.k/offered))
        "the offer fact carries the inspector's baseline")
    (is (= {:yin.k/baseline (checkpoint/inspect address bytes)
            :yin.k/variants #{address}
            :yin.k/epoch 0
            :dao.lease/lease nil}
           (get-in (authority/projection a) [:occurrences occ])))))


(deftest a-duplicate-offer-is-idempotent
  (let [[frames a store] (offered)
        before @frames]
    (is (= {:yin.k/status :replayed :yin.k/occurrence occ}
           (offer! a store (body "successor"))))
    (is (= {:yin.k/status :replayed :yin.k/occurrence occ}
           (offer! a store (body "successor") "another-carrier"))
        "by occurrence and snapshot address, whatever the carrier")
    (is (= {:yin.k/status :replayed :yin.k/occurrence occ}
           (offer! (auth frames) store (body "successor")))
        "after reopen too")
    (is (= before @frames) "nothing was written")))


(deftest an-equal-baseline-variant-joins-its-occurrence
  (let [[frames a store] (offered)
        {:keys [address]} (bytes-of (body "variant-equal"))]
    (is (= :committed (status (offer! a store (body "variant-equal")))))
    (is (= 2 (count (facts frames :yin.k/offered))))
    (is (contains? (get-in (authority/projection a)
                           [:occurrences occ :yin.k/variants])
                   address))
    (is (some? (jing/get store address nil)))))


(deftest a-conflicting-variant-is-refused
  (doseq [[n reason] [["variant-different-intent" :variant-conflict]
                      ["variant-counter" :variant-conflict]]]
    (testing n
      (let [[frames a store] (offered)
            before @frames
            {:keys [address]} (bytes-of (body n))]
        (is (= {:yin.k/status :refused :yin.k/reason reason
                :yin.k/occurrence occ}
               (offer! a store (body n))))
        (is (= before @frames))
        (is (nil? (jing/get store address nil))
            "a refused variant's body is not stored")))))


(deftest a-known-occurrence-with-another-origin-is-refused
  (let [[frames a store] (offered)
        before @frames
        other (body "successor"
                    #(assoc-in % [:yin.k/origin :dao.lease/lease] "lease-9"))]
    (is (= {:yin.k/status :refused :yin.k/reason :occurrence-conflict
            :yin.k/occurrence occ}
           (offer! a store other)))
    (is (= before @frames))))


(deftest an-uninspectable-body-commits-nothing-and-stores-nothing
  (doseq [[label address bs]
          (let [{:keys [address bytes]} (bytes-of (body "successor"))
                v2 (bytes-of (body "version-2"))]
            [["undecodable version" (:address v2) (:bytes v2)]
             ["hash mismatch" (fx/segment-address (cbor/encode :x)) bytes]
             ["not bytes" address :x]])]
    (testing label
      (let [frames (fresh-frames)
            a (auth frames)
            store (mem/create-content-mem)
            r (grant/offer! a store address bs "carrier")]
        (is (= :refused (status r)))
        (is (= :uninspectable (:yin.k/reason r)))
        (is (contains? #{:yin.k/undecodable :yin.k/profile-mismatch
                         :yin.k/hash-mismatch}
                       (get-in r [:yin.k/inspection :yin.k/status])))
        (is (= 1 (count @frames)) "only the header")
        (is (empty? (mem/entry-bytes store)))))))


(deftest offers-the-authority-cannot-hold-are-refused
  (doseq [[label b reason]
          [["a halted root" (get fx/fixtures "halted-root") :not-offerable]
           ["another authority" (get fx/fixtures "successor")
            :foreign-arbitration]
           ["an occurrence outside the form: the inspector refuses it"
            (body "successor" #(assoc % :yin.k/occurrence "O"))
            :uninspectable]]]
    (testing label
      (let [frames (fresh-frames)
            store (mem/create-content-mem)
            r (offer! (auth frames) store b)]
        (is (= :refused (status r)))
        (is (= reason (:yin.k/reason r)))
        (is (= 1 (count @frames)))
        (is (empty? (mem/entry-bytes store)))))))


(deftest an-unwritable-store-suspends-the-offer
  (let [frames (fresh-frames)
        a (auth frames)
        store {:put-bytes-fn (fn [_ _] (throw (ex-info "disk full" {})))
               :get-bytes-fn (fn [_ nf] nf)}]
    (is (= {:yin.k/status :suspended :yin.k/reason :content-unavailable}
           (offer! a store (body "successor"))))
    (is (= 1 (count @frames)) "the ledger never references missing content")
    (is (some? (authority/projection a)) "nothing was poisoned")))


;; =============================================================================
;; Grant and binding
;; =============================================================================

(deftest a-grant-and-its-binding-commit-in-one-transaction
  (let [[frames a] (offered)
        w (grant/writer a)]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (stream/append! w (a-grant)))
        "the judge's writer sees a plain stream outcome")
    (let [[r] (drop 1 (records frames))
          ds (get-in r [:dao.space/transaction :datoms])]
      (is (= [(a-grant) (custody/bound occ "lease-1" "holder-a" 0)]
             [(first (facts frames :dao.lease/accepted))
              (first (facts frames :yin.k/bound))]))
      (is (= #{:dao.lease/accepted :yin.k/bound}
             (set (keep (fn [[_ a v]]
                          (when (#{:yin.k/custody :dao.lease/status} a) v))
                        ds)))
          "one record holds both"))
    (is (= {:yin.k/transaction {:yin.k/arbitration arb :dao.space/t 1}
            :yin.k/occurrence occ
            :dao.lease/lease "lease-1"
            :dao.lease/holder "holder-a"
            :yin.k/epoch 0}
           (custody/binding-evidence arb (attributed frames) "lease-1"))
        "the first grant binds epoch 0")))


(deftest the-writer-refuses-what-it-cannot-record
  (let [[frames a] (offered)
        w (grant/writer a)]
    (is (= :dao.stream/ok (outcome (stream/append! w (a-grant)))))
    (let [before @frames]
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (a-grant "lease-2" "holder-b")))
          "one live lease per occurrence")
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (assoc (a-grant "lease-3" "holder-b")
                                      :dao.lease/subject
                                      (custody/subject
                                        fx/predecessor))))
          "an occurrence never offered")
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (lease/lapsed "lease-1" :policy)))
          "a lapse is slice C6's")
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (lease/refusal "p-2"))))
      (is (= {:dao.stream/outcome :dao.stream/invalid-value}
             (stream/append! w (assoc (a-grant "lease-4" "holder-b")
                                      :yin.k/extra 1)))
          "an attribute outside the published order")
      (is (= {:dao.stream/outcome :dao.stream/ok}
             (stream/append! w (a-grant)))
          "the recorded grant again is a replay")
      (is (= before @frames) "nothing more was written"))
    (authority/close! a)
    (is (= {:dao.stream/outcome :dao.stream/transport-error}
           (stream/append! w (a-grant "lease-5" "holder-b"))))))


(defn- concurrently
  [thunks]
  #?(:cljd (mapv #(%) thunks)
     :clj (mapv deref (mapv #(future (%)) thunks))
     :cljs (mapv #(%) thunks)))


(deftest two-grants-race-for-one-occurrence
  (let [[frames a] (offered)
        w (grant/writer a)
        rs (concurrently
             (mapv (fn [n]
                     #(stream/append! w (a-grant (str "lease-" n)
                                                 (str "holder-" n))))
                   (range 8)))]
    (is (= {:dao.stream/ok 1 :dao.stream/invalid-value 7}
           (frequencies (map outcome rs))))
    (is (= 1 (count (facts frames :dao.lease/accepted))))
    (is (= 1 (count (facts frames :yin.k/bound))))))


;; =============================================================================
;; The judge
;; =============================================================================

(defn- log
  [& vs]
  (let [h (:dao.stream/handle
            (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
    (doseq [v vs] (stream/append! h v))
    h))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- judge
  [a & proposers]
  (reduce (fn [j [who h]] (lease/wire-facts j h (oldest h) who))
          (let [ticks (log (lease/tick {:s 1}))]
            (lease/wire-tick
              (lease/initial-judge
                (merge (grant/judge-config a duration)
                       {:resolver (fn [source _] source) :self arb}))
              ticks (oldest ticks) :ticks))
          proposers))


(deftest two-candidates-one-grant
  (let [[frames a] (offered)
        propose #(log (lease/proposal % (custody/subject occ)))
        j (judge a ["holder-a" (propose "p-a")] ["holder-b" (propose "p-b")])
        j1 (grant/step! a j)
        [g] (facts frames :dao.lease/accepted)]
    (is (nil? (:abort j1)))
    (is (= 1 (count (facts frames :dao.lease/accepted))) "exactly one grant")
    (is (= "holder-a" (:dao.lease/holder g)))
    (is (= "p-a" (:dao.lease/proposal g)))
    (is (= duration (:dao.lease/duration g)))
    (is (= [(:dao.lease/lease g)] (keys (:ledger j1)))
        "the judge seeded the one lease the ledger recorded")
    (is (= 0 (:yin.k/epoch (custody/binding-evidence
                             arb (attributed frames) (:dao.lease/lease g)))))
    (grant/step! a j1)
    (is (= 1 (count (facts frames :dao.lease/accepted)))
        "a later pass grants nothing more")))


(deftest the-reclaim-callback-is-a-readiness-check
  (let [[frames a] (offered)
        ready? (:reclaim (grant/judge-config a duration))
        before @frames]
    (is (true? (ready? (custody/subject occ))))
    (is (= before @frames) "it revokes nothing")
    (authority/close! a)
    (is (false? (ready? (custody/subject occ))))))


(deftest a-judge-config-needs-a-valid-duration
  (let [[_ a] (offered)]
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (grant/judge-config a {:s 0})))))


;; =============================================================================
;; Crash cuts on the grant transition
;; =============================================================================

(deftest crash-at-each-cut-commits-the-grant-zero-or-one-times
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames a] (offered)
            _ (authority/close! a)
            a (auth frames cut)
            w (grant/writer a)]
        (is (= {:dao.stream/outcome :dao.stream/transport-error}
               (stream/append! w (a-grant))))
        (is (= {:dao.stream/outcome :dao.stream/transport-error}
               (stream/append! w (a-grant)))
            "a poisoned authority grants nothing")
        (let [a2 (auth frames)
              w2 (grant/writer a2)]
          (is (= persisted (count (facts frames :dao.lease/accepted)))
              "reopen shows zero or one grant")
          (is (= persisted (count (facts frames :yin.k/bound))))
          (is (= {:dao.stream/outcome :dao.stream/ok}
                 (stream/append! w2 (a-grant)))
              "a retry after reopen finds the grant or commits it")
          (is (= 1 (count (facts frames :dao.lease/accepted))))
          (is (= 0 (:yin.k/epoch (custody/binding-evidence
                                   arb (attributed frames) "lease-1")))
              "never a grant without its binding in one record"))))))


;; =============================================================================
;; Evidence through a dao.stream.remote reflection
;; =============================================================================

(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity 64})))


(defn- reflect
  "Serve journal handle `h` under identity `arb` over a toy channel of
   two ring buffers and attach a reflection of it.  Answers the
   reflection and a serve function running one mirror step."
  [h]
  (let [ab (ring)
        ba (ring)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "toy-channel"}
        table {arb {:handle h :surface #{:reader}}}
        mirror (atom (oldest ab))
        attach! (remote/attacher
                  {:dao.stream.remote/channels {cd {:reader ba :writer ab}}})]
    {:reflection (:dao.stream/handle
                   (attach! {:dao.stream/type :dao.stream/remote
                             :dao.stream/identity arb
                             :dao.stream/channel cd}))
     :serve! #(swap! mirror (fn [c] (remote/mirror-step table ab c ba)))}))


(defn- ask
  "Ask `f` of the reflection until it answers other than blocked or a
   retryable transport error, serving between asks."
  [serve! f]
  (loop [n 0]
    (let [r (f)]
      (if (and (< n 16)
               (or (= :dao.stream/blocked (outcome r))
                   (:dao.stream/retry? r)))
        (do (serve!) (recur (inc n)))
        r))))


(deftest evidence-survives-a-remote-reflection
  (let [[frames a] (offered)
        _ (stream/append! (grant/writer a) (a-grant))
        {:keys [reflection serve!]} (reflect (:journal a))
        author (:dao.stream/identity (stream/descriptor reflection))
        read (loop [c (:dao.stream/cursor
                        (ask serve! #(stream/cursor reflection
                                                    :dao.stream/oldest)))
                    acc []]
               (let [r (ask serve! #(stream/next reflection c))]
                 (if (= :dao.stream/ok (outcome r))
                   (recur (:dao.stream/cursor r)
                          (conj acc [author (:dao.stream/value r)]))
                   acc)))
        direct (custody/binding-evidence arb (attributed frames) "lease-1")]
    (is (= arb author) "the reflection carries the authority's identity")
    (is (= (records frames) (mapv second read)))
    (is (= direct (custody/binding-evidence arb read "lease-1")))
    (is (= direct (custody/binding-evidence
                    arb (cbor/decode (cbor/encode read)) "lease-1"))
        "and the canonical codec after it")
    (is (= :no-binding
           (::custody/no-evidence
             (custody/binding-evidence
               arb (mapv (fn [[_ r]] ["mallory" r]) read) "lease-1")))
        "the same records attributed to another author prove nothing")))
