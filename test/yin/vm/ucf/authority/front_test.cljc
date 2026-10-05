(ns yin.vm.ucf.authority.front-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]))


(def ^:private arb "arb-c11")

(def ^:private duration {:s 30})

(def ^:private occ "0c000000-0000-4000-8000-000000000001")

(def ^:private succ-occ "0c000000-0000-4000-8000-000000000002")


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- fresh-frames
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- auth
  ([frames] (auth frames nil))
  ([frames cut]
   (::authority/authority
     (authority/open! (journal/memory-backend frames cut) nil))))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- park
  "A blocked root of occurrence `o` with counter `n` and origin `org`."
  ([o n] (park o n nil))
  ([o n org]
   (cond-> (assoc (get fx/fixtures "first-park")
                  :yin.k/occurrence o
                  :yin.k/next-op-seq n
                  :yin.k/arbitration {:dao.stream/identity arb
                                      :dao.stream/descriptor
                                      {:dao.stream/type :dao.stream/journal}})
     (some? org) (assoc :yin.k/origin org))))


(defn- enc
  [b]
  (let [bs (cbor/encode b)]
    {:address (fx/segment-address bs) :bytes bs}))


(def ^:private root (enc (park occ 0)))


(defn- successor
  [l]
  (enc (park succ-occ 3 {:yin.k/occurrence occ :dao.lease/lease l
                         :yin.k/emitter "holder-a"})))


(defn- ring
  ([] (ring 64))
  ([n]
   (:dao.stream/handle
     (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                          ringbuffer/capacity-key n}))))


(defn- log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- id-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- read-all
  [h]
  (loop [c (oldest h) vs []]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count vs) 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)))
        vs))))


(defn- world
  "An authority over fresh frames (or `frames` with crash cut `cut`), one
   holder's inbound, reply and lease-fact streams, a diagnostic stream,
   and a front whose resolver attributes the inbound to `author`."
  ([] (world {}))
  ([{:keys [frames cut author resolver]
     :or {author "holder-a"}}]
   (let [frames (or frames (fresh-frames))
         a (auth frames cut)
         in (ring)
         out (ring)
         diag (ring)
         medium (log)
         store (mem/create-content-mem)
         resolver (or resolver
                      (fn [identity _]
                        (when (= identity (id-of in)) author)))]
     {:frames frames
      :a a
      :in in
      :out out
      :diag diag
      :medium medium
      :store store
      :front (atom (front/front {:authority a
                                 :inbound in
                                 :reply out
                                 :diagnostics diag
                                 :resolver resolver
                                 :store store
                                 :lease-media {author medium}}))})))


(defn- send!
  "Put request `r` on the world's inbound and step its front once."
  [w r]
  (stream/append! (:in w) r)
  (swap! (:front w) front/step 1)
  w)


(defn- handled
  [w]
  (::front/handled @(:front w)))


(defn- answer
  "The answer of the world's last reply."
  [w]
  (:yin.k/answer (last (read-all (:out w)))))


(defn- request
  [kind rid m]
  (merge {:yin.k/request kind :yin.k/request-id rid} m))


(defn- offer-req
  ([] (offer-req root))
  ([{:keys [address bytes]}]
   (request :yin.k/offer "r-offer" {:yin.k/id address
                                    :yin.k/bytes bytes
                                    :yin.k/medium "carrier"})))


(defn- grant!
  [a l h]
  (stream/append! (grant/writer a)
                  (lease/grant l (custody/subject occ) h duration
                               {:dao.lease/proposal (str "p-" l)})))


(defn- report-req
  [l]
  (let [{:keys [address bytes]} (successor l)]
    (request :yin.k/resumed "r-report"
             {:yin.k/report (completion/resumed occ l address)
              :yin.k/bytes bytes})))


(def ^:private source {:yin.k/kind :yin.k/read :yin.k/name "in"})


(defn- input-req
  [l k v]
  (request :yin.k/input "r-input"
           {:yin.k/input (input/request occ l 0 k source v)}))


(defn- env
  [l n v]
  {:yin.k/envelope :yin.k/fenced-v1
   :yin.k/incarnation l
   :yin.k/epoch 0
   :yin.k/op-id {:yin.k/occurrence occ :yin.k/seq n}
   :yin.k/value v})


(defn- admit-req
  [i l n v]
  (request :yin.k/admit "r-admit"
           {:yin.k/target i :yin.k/fenced-envelope (env l n v)}))


(defn- proposal-req
  [pid]
  (request :yin.k/proposal "r-propose"
           {:dao.lease/proposal pid :yin.k/occurrence occ}))


(defn- release-req
  [l]
  (request :yin.k/release "r-release" {:dao.lease/lease l}))


(defn- renewal-req
  [l]
  (request :yin.k/renewal "r-renew" {:dao.lease/lease l}))


(defn- judge
  "A judge over authority `a` reading `medium` as holder-a's lease facts."
  [a medium]
  (let [ticks (log)]
    (stream/append! ticks (lease/tick {:s 1}))
    (-> (lease/initial-judge
          (merge (grant/judge-config a duration)
                 {:resolver (fn [source _] source) :self arb}))
        (lease/wire-tick ticks (oldest ticks) :ticks)
        (lease/wire-facts medium (oldest medium) "holder-a"))))


(defn- record-facts
  [rec]
  (let [ds (get-in rec [:dao.space/transaction :datoms])]
    (vec (for [e (distinct (map first ds))]
           (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))))


(defn- facts
  [frames k]
  (filter #(= k (or (:yin.k/custody %) (:dao.lease/status %)))
          (mapcat record-facts (records frames))))


(defn- granted
  "A world whose occurrence is offered and granted as lease-1 to
   holder-a, directly, with one enrolled target under `:i`."
  ([] (granted {}))
  ([opts]
   (let [w (world opts)
         {:keys [address bytes]} root]
     (grant/offer! (:a w) (:store w) address bytes "carrier")
     (grant! (:a w) "lease-1" "holder-a")
     (assoc w :i (:yin.k/target (authority/enroll! (:a w)))))))


(defn- poisoned
  "A granted world reopened with a crash cut armed and poisoned by a
   direct input that hit it."
  []
  (let [w (granted)]
    (authority/close! (:a w))
    (let [w2 (world {:frames (:frames w) :cut :before-frame})]
      (input/record-input! (:a w2) "holder-a" (input/request occ "lease-1" 0 0
                                                             source :cut))
      (assoc w2 :i (:i w)))))


;; =============================================================================
;; Each request kind round-trips like the direct function
;; =============================================================================

(deftest an-offer-round-trips-like-grant-offer
  (let [w (world)
        d (world)
        {:keys [address bytes]} root
        direct (grant/offer! (:a d) (:store d) address bytes "carrier")
        w (send! w (offer-req))]
    (is (= :committed (:yin.k/status direct)))
    (is (= direct (answer w)) "the reply is the direct answer")
    (is (= (records (:frames d)) (records (:frames w)))
        "the ledger records are the direct ones")
    (is (= [{:yin.k/reply :yin.k/offer
             :yin.k/request-id "r-offer"
             :yin.k/answer direct}]
           (read-all (:out w)))
        "one reply naming the request kind and echoing its id")))


(deftest a-report-round-trips-like-completion-report
  (let [w (granted)
        d (granted)
        r (report-req "lease-1")
        direct (completion/report! (:a d) "holder-a" (:yin.k/report r)
                                   (:yin.k/bytes r))
        w (send! w r)]
    (is (= :committed (:yin.k/status direct)))
    (is (= direct (answer w)))
    (is (= (records (:frames d)) (records (:frames w))))))


(deftest an-input-round-trips-like-record-input
  (let [w (granted)
        d (granted)
        r (input-req "lease-1" 0 :seen)
        direct (input/record-input! (:a d) "holder-a" (:yin.k/input r))
        w (send! w r)]
    (is (= :recorded (:yin.k/status direct)))
    (is (= direct (answer w)))
    (is (= (records (:frames d)) (records (:frames w))))))


(deftest an-admit-round-trips-like-admission-admit
  (let [w (granted)
        d (granted)
        r (admit-req (:i w) "lease-1" 0 :v)
        direct (admission/admit! (:a d) (:store d) (:i d) "holder-a"
                                 (:yin.k/fenced-envelope r) (:diag d))
        w (send! w r)]
    (is (= :committed (:yin.k/admission direct)))
    (is (= direct (answer w)))
    (is (= (records (:frames d)) (records (:frames w))))))


(deftest a-proposal-is-carried-to-the-judge
  (let [w (world)
        {:keys [address bytes]} root
        _ (grant/offer! (:a w) (:store w) address bytes "carrier")
        w (send! w (proposal-req "p-a"))]
    (is (= {:yin.k/status :carried} (answer w)))
    (is (= [(lease/proposal "p-a" (custody/subject occ))]
           (read-all (:medium w)))
        "exactly the proposal the holder would append itself")
    (grant/step! (:a w) (judge (:a w) (:medium w)))
    (let [[g] (facts (:frames w) :dao.lease/accepted)]
      (is (= "holder-a" (:dao.lease/holder g)) "the judge granted it")
      (is (= "p-a" (:dao.lease/proposal g))))))


(deftest a-release-completes-through-the-judge
  (let [w (world)
        {:keys [address bytes]} root
        _ (grant/offer! (:a w) (:store w) address bytes "carrier")
        w (send! w (proposal-req "p-a"))
        j (grant/step! (:a w) (judge (:a w) (:medium w)))
        l (:dao.lease/lease (first (facts (:frames w) :dao.lease/accepted)))
        w (send! w (report-req l))
        _ (is (= :committed (:yin.k/status (answer w))))
        w (send! w (release-req l))]
    (is (= {:yin.k/status :carried} (answer w)))
    (is (= (lease/release l) (last (read-all (:medium w)))))
    (grant/step! (:a w) j)
    (is (= [(completion/succeeded occ succ-occ)]
           (facts (:frames w) :yin.k/succeeded))
        "the release path of C8 completed the occurrence")))


;; =============================================================================
;; Renewal
;; =============================================================================

(deftest a-renewal-is-carried-and-counted-by-the-judge
  (let [w (world)
        {:keys [address bytes]} root
        _ (grant/offer! (:a w) (:store w) address bytes "carrier")
        w (send! w (proposal-req "p-a"))
        j (grant/step! (:a w) (judge (:a w) (:medium w)))
        l (:dao.lease/lease (first (facts (:frames w) :dao.lease/accepted)))
        w (send! w (renewal-req l))]
    (is (= {:yin.k/status :carried} (answer w)))
    (is (= (lease/renewal l) (last (read-all (:medium w)))))
    (let [j2 (grant/step! (:a w) j)]
      (is (not-any? #(contains? (:leases %) l) (:facts j))
          "the grant alone registered the lease on no medium")
      (is (some #(contains? (:leases %) l) (:facts j2))
          "only the counted renewal registered the lease on the medium"))))


(deftest an-unresolved-renewal-author-is-wrong-author
  (let [w (send! (world {:resolver (fn [_ _] nil)}) (renewal-req "lease-1"))
        [d] (read-all (:diag w))]
    (is (= [] (read-all (:medium w))) "nothing carried")
    (is (= [] (read-all (:out w))) "no reply")
    (is (= :wrong-author (:yin.k/defect d)))
    (is (= {:yin.k/request :yin.k/renewal :yin.k/request-id "r-renew"}
           (:yin.k/claimed d)))))


;; =============================================================================
;; Defective requests
;; =============================================================================

(def ^:private defective
  [42
   {}
   {:yin.k/request :yin.k/nope :yin.k/request-id "r"}
   (dissoc (offer-req) :yin.k/request-id)
   (dissoc (offer-req) :yin.k/bytes)
   (dissoc (offer-req) :yin.k/id)
   (dissoc (offer-req) :yin.k/medium)
   (request :yin.k/proposal "r" {:dao.lease/proposal "p"
                                 :yin.k/occurrence "O"})
   (request :yin.k/proposal "r" {:yin.k/occurrence occ})
   (request :yin.k/release "r" {})
   (request :yin.k/renewal "r" {})
   (request :yin.k/resumed "r" {:yin.k/bytes (:bytes root)})
   (request :yin.k/input "r" {})
   (request :yin.k/admit "r" {:yin.k/fenced-envelope (env "lease-1" 0 :v)})])


(deftest unknown-kinds-and-malformed-values-are-diagnostics
  (doseq [v defective]
    (testing (pr-str v)
      (let [w (granted)
            before @(:frames w)
            w (send! w v)
            [h] (handled w)]
        (is (= before @(:frames w)) "nothing committed")
        (is (= [] (read-all (:out w))) "no reply")
        (is (= [] (read-all (:medium w))) "nothing carried")
        (is (= 1 (count (read-all (:diag w)))) "exactly one diagnostic")
        (is (= :malformed (get-in h [::front/diagnostic :yin.k/defect])))
        (is (= :dao.stream/ok
               (get-in h [::front/appended :dao.stream/outcome])))))))


(deftest the-diagnostic-shape
  (let [w (send! (granted) (dissoc (offer-req) :yin.k/bytes))
        [d] (read-all (:diag w))]
    (is (= {:yin.k/diagnostic :yin.k/defective-request
            :yin.k/defect :malformed
            :yin.k/inbound (id-of (:in w))
            :yin.k/author "holder-a"
            :yin.k/claimed {:yin.k/request :yin.k/offer
                            :yin.k/request-id "r-offer"}}
           d)
        "claims nested, the payload never echoed")
    (is (not (contains? d :yin.k/status)))
    (is (not (contains? d :yin.k/admission)))))


(deftest no-resolved-author-is-wrong-author
  (doseq [[what resolver] [["no attribution" (fn [_ _] nil)]
                           ["a throwing resolver"
                            (fn [_ _] (throw (ex-info "boom" {})))]]]
    (testing what
      (let [w (granted {:resolver resolver})
            before @(:frames w)
            w (send! w (assoc (report-req "lease-1")
                              :yin.k/author "holder-a"))
            [h] (handled w)
            [d] (read-all (:diag w))]
        (is (= before @(:frames w)) "nothing committed")
        (is (= [] (read-all (:out w))) "no reply")
        (is (= :wrong-author (:yin.k/defect d)))
        (is (not (contains? d :yin.k/author)))
        (is (= d (::front/diagnostic h))))))
  (testing "nothing is carried without attribution"
    (let [w (send! (world {:resolver (fn [_ _] nil)}) (proposal-req "p"))]
      (is (= [] (read-all (:medium w))))
      (is (= :wrong-author (:yin.k/defect (first (read-all (:diag w))))))))
  (testing "an author field in the request is never consulted"
    (let [w (send! (granted) (assoc (report-req "lease-1")
                                    :yin.k/author "mallory"))]
      (is (= :committed (:yin.k/status (answer w)))))))


(deftest a-failed-diagnostic-append-is-data
  (testing "a closed diagnostic stream"
    (let [w (granted)
          _ (stream/close! (:diag w))
          before @(:frames w)
          w (send! w 42)
          [h] (handled w)]
      (is (= before @(:frames w)))
      (is (= :dao.stream/closed
             (get-in h [::front/appended :dao.stream/outcome])))))
  (testing "a throwing diagnostic stream"
    (let [w (granted)
          _ (swap! (:front w) assoc ::front/diagnostics
                   (reify stream/IDaoStreamWriter
                     (append! [_ _] (throw (ex-info "boom" {})))))
          w (send! w 42)
          [h] (handled w)]
      (is (= {::front/threw true} (::front/appended h))))))


(deftest a-non-holder-is-refused-as-by-the-direct-function
  (let [w (granted {:author "holder-b"})
        d (granted {:author "holder-b"})
        r (report-req "lease-1")
        i (input-req "lease-1" 0 :seen)
        e (admit-req (:i w) "lease-1" 0 :v)]
    (is (= (completion/report! (:a d) "holder-b" (:yin.k/report r)
                               (:yin.k/bytes r))
           (answer (send! w r))))
    (is (= :not-holder (:yin.k/reason (answer w))))
    (is (= (input/record-input! (:a d) "holder-b" (:yin.k/input i))
           (answer (send! w i))))
    (is (= :wrong-author (:yin.k/reason (answer w))))
    (let [direct (admission/admit! (:a d) (:store d) (:i d) "holder-b"
                                   (:yin.k/fenced-envelope e) (:diag d))
          w (send! w e)]
      (is (= :wrong-author (get-in direct [::admission/diagnostic
                                           :yin.k/defect])))
      (is (= (read-all (:diag d)) (read-all (:diag w)))
          "admission's own diagnostic, and no reply for it")
      (is (= 2 (count (read-all (:out w))))))
    (is (= (records (:frames d)) (records (:frames w))))))


;; =============================================================================
;; The bounded step
;; =============================================================================

(deftest the-step-is-bounded-and-resumable
  (let [w (granted)
        fr @(:front w)
        reqs (mapv #(input-req "lease-1" % (keyword (str "v" %))) (range 5))]
    (doseq [r reqs] (stream/append! (:in w) r))
    (let [f1 (front/step fr 2)]
      (is (= 2 (count (::front/handled f1))) "at most two read")
      (is (= 2 (count (read-all (:out w)))))
      (is (nil? (::front/halted f1)))
      (let [f2 (front/step f1 2)]
        (is (= 4 (count (read-all (:out w)))) "resumed from the held cursor")
        (let [f3 (front/step f2 10)]
          (is (= 1 (count (::front/handled f3))))
          (is (= :dao.stream/blocked (::front/halted f3))
              "never past the tail")
          (is (= [0 1 2 3 4]
                 (mapv #(get-in % [:yin.k/answer :yin.k/input-seq])
                       (read-all (:out w)))))
          (let [f4 (front/step f3 10)]
            (is (= [] (::front/handled f4)))
            (is (= (::front/cursor f3) (::front/cursor f4)))))
        (is (= [] (::front/handled (front/step f1 0))) "zero reads nothing")
        (is (= (::front/cursor f1) (::front/cursor (front/step f1 0))))))))


(deftest a-gap-on-the-inbound-is-one-read
  (let [w (granted)
        in (ring 2)
        f (front/front {:authority (:a w) :inbound in :reply (:out w)
                        :diagnostics (:diag w)
                        :resolver (fn [_ _] "holder-a")
                        :store (:store w) :lease-media {}})]
    (doseq [k (range 4)] (stream/append! in (input-req "lease-1" k :v)))
    (let [f1 (front/step f 1)
          [h] (::front/handled f1)]
      (is (contains? h ::front/gap) "the lost requests are a gap")
      (is (= [] (read-all (:out w))))
      (let [f2 (front/step f1 5)]
        (is (= 2 (count (::front/handled f2))))
        (is (= :dao.stream/blocked (::front/halted f2)))))))


(deftest a-closed-inbound-halts-with-end
  (let [w (granted)]
    (stream/close! (:in w))
    (let [f (front/step @(:front w) 5)]
      (is (= [] (::front/handled f)))
      (is (= :dao.stream/end (::front/halted f))))))


(deftest a-throwing-inbound-read-halts-as-data
  (let [w (granted)
        f (assoc @(:front w) ::front/inbound
                 (reify stream/IDaoStreamReader
                   (cursor [_ _anchor] (throw (ex-info "boom" {})))

                   (next [_ _c] (throw (ex-info "boom" {})))))
        f1 (front/step f 5)]
    (is (= [] (::front/handled f1)))
    (is (= {::front/threw true} (::front/halted f1)))
    (is (= (::front/cursor f) (::front/cursor f1)))))


;; =============================================================================
;; Gate follow-ups: carriage answers, reply failures, silent admissions
;; =============================================================================

(defn- offered
  "A world whose occurrence is offered, its front's lease-fact streams
   replaced by `media`."
  [media]
  (let [w (world)]
    (grant/offer! (:a w) (:store w) (:address root) (:bytes root) "carrier")
    (swap! (:front w) assoc ::front/lease-media media)
    w))


(defn- throwing-writer
  []
  (reify stream/IDaoStreamWriter
    (append! [_ _v] (throw (ex-info "boom" {})))))


(deftest a-holder-without-a-lease-medium-is-refused
  (let [w (send! (offered {}) (proposal-req "p-a"))]
    (is (= {:yin.k/status :refused :yin.k/reason :no-lease-medium}
           (answer w)))
    (is (= [] (read-all (:diag w))))))


(deftest an-uncarried-fact-suspends
  (doseq [[what medium] [["a closed lease-fact stream"
                          (doto (ring) stream/close!)]
                         ["a throwing lease-fact stream" (throwing-writer)]]]
    (testing what
      (let [w (send! (offered {"holder-a" medium}) (release-req "lease-1"))]
        (is (= {:yin.k/status :suspended :yin.k/reason :uncarried}
               (answer w)))
        (is (= [] (read-all (:diag w))))))))


(deftest a-throwing-lease-media-function-is-uncarried
  (let [w (send! (offered (fn [_] (throw (ex-info "boom" {}))))
                 (proposal-req "p-a"))]
    (is (= {:yin.k/status :suspended :yin.k/reason :uncarried} (answer w))
        "answered as data, not diagnosed as a defective request")
    (is (= [] (read-all (:diag w))))))


(deftest a-failed-reply-append-is-data-after-the-commit
  (testing "a closed reply stream"
    (let [w (granted)
          d (granted)
          r (input-req "lease-1" 0 :seen)
          direct (input/record-input! (:a d) "holder-a" (:yin.k/input r))
          _ (stream/close! (:out w))
          [h] (handled (send! w r))]
      (is (= direct (::front/answer h)))
      (is (= :dao.stream/closed
             (get-in h [::front/replied :dao.stream/outcome])))
      (is (= (records (:frames d)) (records (:frames w)))
          "the ledger effect committed as on the direct path")))
  (testing "a throwing reply stream"
    (let [w (granted)
          _ (swap! (:front w) assoc ::front/reply (throwing-writer))
          [h] (handled (send! w (input-req "lease-1" 0 :seen)))]
      (is (= :recorded (get-in h [::front/answer :yin.k/status])))
      (is (= {::front/threw true} (::front/replied h))))))


(defn- inheriting
  "A granted world where R completed under lease-1 into S1, whose body
   carries R's ids 0 to 2, and S1 is offered and granted as lease-2 to
   holder-a, all in the front's own store."
  []
  (let [{:keys [a store] :as w} (granted)
        {:keys [address bytes]}
        (enc (reduce (fn [b k]
                       (assoc-in b [:yin.k/frames k :yin.k/pending
                                    :yin.k/op-id]
                                 {:yin.k/occurrence occ :yin.k/seq k}))
                     (assoc (get fx/fixtures "successor")
                            :yin.k/occurrence succ-occ
                            :yin.k/origin {:yin.k/occurrence occ
                                           :dao.lease/lease "lease-1"
                                           :yin.k/emitter "holder-a"}
                            :yin.k/arbitration
                            {:dao.stream/identity arb
                             :dao.stream/descriptor
                             {:dao.stream/type :dao.stream/journal}})
                     (range 3)))]
    (completion/report! a "holder-a" (completion/resumed occ "lease-1" address)
                        bytes)
    (stream/append! (grant/writer a) (lease/lapsed "lease-1" :release))
    (grant/offer! a store address bytes "carrier")
    (stream/append! (grant/writer a)
                    (lease/grant "lease-2" (custody/subject succ-occ)
                                 "holder-a" duration
                                 {:dao.lease/proposal "p-lease-2"}))
    w))


(deftest an-inherited-admission-reads-the-fronts-store
  (testing "the front's own store holds the accepted checkpoint"
    (let [w (inheriting)]
      (is (= :committed
             (:yin.k/admission
               (answer (send! w (admit-req (:i w) "lease-2" 0 :v))))))))
  (testing "another store cannot verify the inherited id"
    (let [w (inheriting)]
      (swap! (:front w) assoc ::front/store (mem/create-content-mem))
      (is (= :suspended
             (:yin.k/admission
               (answer (send! w (admit-req (:i w) "lease-2" 0 :v)))))))))


(deftest an-admit-at-an-unenrolled-boundary-is-silent
  (let [w (granted)
        before @(:frames w)
        [h] (handled (send! w (admit-req "no-such-target" "lease-1" 0 :v)))]
    (is (= [] (read-all (:out w))) "no reply")
    (is (= [] (read-all (:diag w))) "and no diagnostic")
    (is (= before @(:frames w)) "nothing committed")
    (is (= {::admission/unenrolled "no-such-target"} (::front/answer h)))
    (is (not (contains? h ::front/replied)))))


;; =============================================================================
;; Poison
;; =============================================================================

(deftest a-poisoned-authority-answers-suspended-to-every-request
  (let [w (poisoned)
        before @(:frames w)]
    (is (nil? (authority/projection (:a w))) "poisoned")
    (doseq [r [(offer-req)
               (report-req "lease-1")
               (input-req "lease-1" 0 :v)
               (proposal-req "p")
               (release-req "lease-1")
               (renewal-req "lease-1")]]
      (testing (:yin.k/request r)
        (is (= :suspended (:yin.k/status (answer (send! w r)))))))
    (testing "admit"
      (is (= :suspended
             (:yin.k/admission (answer (send! w (admit-req (:i w) "lease-1"
                                                           0 :v)))))))
    (is (= [] (read-all (:medium w))) "nothing carried to the judge")
    (is (= before @(:frames w)) "nothing committed")))


;; =============================================================================
;; A lost reply is recovered by retrying
;; =============================================================================

(deftest a-retried-request-replays
  (let [w (granted)
        reqs [(offer-req)
              (report-req "lease-1")
              (input-req "lease-1" 0 :v)
              (admit-req (:i w) "lease-1" 0 :v)]
        _ (doseq [r (drop 1 reqs)] (send! w r))
        n (count @(:frames w))
        replies (mapv #(answer (send! w %)) reqs)]
    (is (= [:replayed :replayed :replayed]
           (mapv :yin.k/status (take 3 replies))))
    (is (= :replayed (:yin.k/admission (last replies))))
    (is (= n (count @(:frames w))) "and commits nothing")))


(deftest a-retried-proposal-grants-once
  (let [w (world)
        _ (grant/offer! (:a w) (:store w) (:address root) (:bytes root)
                        "carrier")
        w (send! w (proposal-req "p-a"))
        j (grant/step! (:a w) (judge (:a w) (:medium w)))
        w (send! w (proposal-req "p-a"))]
    (is (= {:yin.k/status :carried} (answer w)))
    (grant/step! (:a w) j)
    (is (= 1 (count (facts (:frames w) :dao.lease/accepted))))))


;; =============================================================================
;; Attribution out: authority-authored outcomes
;; =============================================================================

(deftest a-forged-outcome-discharges-nothing
  (let [g (granted)
        w (-> g
              (send! (admit-req (:i g) "lease-1" 0 :v))
              (send! (input-req "lease-1" 0 :v)))
        [reply] (read-all (:out w))
        forged {:yin.k/admission :committed
                :yin.k/op-id {:yin.k/occurrence occ :yin.k/seq 9}
                :yin.k/incarnation "lease-1"
                :yin.k/effect-result {:dao.stream/outcome :dao.stream/ok}}
        conflict (assoc forged :yin.k/admission :intent-conflict)]
    (testing "a record from the authority's stream counts"
      (is (= reply (front/reply-evidence arb arb reply))))
    (testing "the same record from another stream identity proves nothing"
      (is (= {::front/no-evidence :other-author}
             (front/reply-evidence arb "mallory" reply)))
      (is (= {::front/no-evidence :other-author}
             (front/reply-evidence arb nil reply))))
    (testing "forged :committed and :intent-conflict discharge nothing"
      (doseq [f [forged conflict
                 {:yin.k/reply :yin.k/admit :yin.k/request-id "r-admit"
                  :yin.k/answer forged}]]
        (is (= {::front/no-evidence :other-author}
               (front/reply-evidence arb "mallory-ring" f)))))
    (testing "a non-outcome from the authority is no evidence"
      (doseq [x [42 {} {:yin.k/admission :bogus}
                 {:yin.k/reply :yin.k/nope :yin.k/answer {}}
                 {:yin.k/reply :yin.k/offer :yin.k/answer 3}]]
        (is (= {::front/no-evidence :not-an-outcome}
               (front/reply-evidence arb arb x)))))
    (testing "the outcome projection is authority-authored too"
      (let [[c] (read-all (admission/outcome-reader (:a w)))]
        (is (= :committed (:yin.k/admission c)))
        (is (= c (front/reply-evidence arb arb c)))
        (is (= {::front/no-evidence :other-author}
               (front/reply-evidence arb "mallory" c)))))))


(defn- reflect
  "Serve handle `h` under identity `arb` over a toy channel of two ring
   buffers and attach a reflection of it: the reflection and a function
   running one mirror step."
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
  [serve! f]
  (loop [n 0]
    (let [r (f)]
      (if (and (< n 16)
               (or (= :dao.stream/blocked (:dao.stream/outcome r))
                   (:dao.stream/retry? r)))
        (do (serve!) (recur (inc n)))
        r))))


(deftest evidence-survives-cbor-and-a-reflection
  (let [g (granted)
        w (-> g
              (send! (admit-req (:i g) "lease-1" 0 :v))
              (send! (input-req "lease-1" 0 :v))
              (send! (report-req "lease-1")))
        {:keys [reflection serve!]} (reflect (:out w))
        author (:dao.stream/identity (stream/descriptor reflection))
        read (loop [c (:dao.stream/cursor
                        (ask serve! #(stream/cursor reflection
                                                    :dao.stream/oldest)))
                    acc []]
               (let [r (ask serve! #(stream/next reflection c))]
                 (if (= :dao.stream/ok (:dao.stream/outcome r))
                   (recur (:dao.stream/cursor r)
                          (conj acc (:dao.stream/value r)))
                   acc)))
        direct (read-all (:out w))]
    (is (= arb author) "the reflection carries the authority's identity")
    (is (= 3 (count read)))
    (is (= direct (mapv #(front/reply-evidence arb author %) read)))
    (is (= direct (mapv #(front/reply-evidence arb arb %)
                        (cbor/decode (cbor/encode read))))
        "and the canonical codec after it")
    (is (every? #(= {::front/no-evidence :other-author}
                    (front/reply-evidence arb "mallory" %))
                read))))


;; =============================================================================
;; Vocabulary guard
;; =============================================================================

#?(:cljd nil
   :clj
   (deftest the-front-names-no-transport-concept
     (let [source (slurp (.getResource (clojure.lang.RT/baseLoader)
                                       "yin/vm/ucf/authority/front.cljc"))
           forbidden
           #"(?i)rpc|transport|apply|remote|client|server|socket|wire|network"]
       (is (< 1000 (count source)) "the namespace text is actually read")
       (is (nil? (re-find forbidden source))
           "the front speaks of inbound and reply streams only"))))


(deftest the-request-set-is-closed
  (is (= #{:yin.k/offer :yin.k/proposal :yin.k/resumed :yin.k/release
           :yin.k/renewal :yin.k/input :yin.k/admit}
         front/requests)))
