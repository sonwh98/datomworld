(ns dao.jing.dht-test
  "Acceptance tests for the caller-stepped DHT core, slice S2
   (docs/design/dao.jing.dht.md section 10): dao.jing.dht state and step
   over in-memory sockets (dao.jing.dht.mesh), the section 8 cookie
   protocol with the S2 stand-in cookie-for, pending writes and gets, the
   byte-store handle, and solo mode. Requests and answers are driven by the
   unmodified content clients (dao.jing.content.step). Time advances only
   by appended ticks; nothing here sleeps."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.content :as content]
            [dao.jing.content.step :as client]
            [dao.jing.dht :as dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.file :as jing-file]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.cbor :as wire]))


;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- b64
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- put-request
  [r v]
  {:jing/request r, :jing/put (jing/segment-key v), :jing/bytes (b64 v)})


(defn- get-request
  [r v]
  {:jing/request r, :jing/get (jing/segment-key v)})


(defn- hex
  [bs]
  (dht/bytes->hex bs))


(defn- fact-of
  "The facts on composition c whose :dao.jing.dht/fact is kind."
  [c kind]
  (filterv #(= kind (::dht/fact %)) (mesh/facts c)))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- grid
  "Compositions on one net for ports, each bootstrapped to the others."
  [net ports opts]
  (into (sorted-map)
        (map (fn [p]
               [p (mesh/join! net p
                              (merge {::dht/publish? true
                                      ::dht/bootstrap (mapv mesh/contact
                                                            (remove #{p} ports))}
                                     opts))]))
        ports))


(defn- start
  [comps]
  (into (sorted-map) (map (fn [[p c]] [p (dht/state c)])) comps))


(defn- drive
  "Tick every node at reading t, then step all `rounds` rounds."
  [states comps t rounds]
  (mesh/tick-all! comps t)
  (mesh/step-all states rounds))


(defn- throws-data
  "The ex-data of what (f) throws, or ::none."
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) ::no-data))))


(def ^:private big-value
  "A payload whose encoded store message exceeds 300 bytes."
  (apply str (repeat 400 "x")))


;; ---------------------------------------------------------------------------
;; Composition
;; ---------------------------------------------------------------------------

(deftest composition-defects-throw-and-perform-no-stream-operation
  (let [net (mesh/mesh)
        c (mesh/join! net 1)]
    (testing "ack-peers floor 2 and ceiling k"
      (is (map? (throws-data #(dht/state (assoc c ::dht/ack-peers 1)))))
      (is (map? (throws-data #(dht/state (assoc c ::dht/ack-peers 21)))))
      (is (= 2 (::dht/ack-peers (dht/state (assoc c ::dht/ack-peers 2)))))
      (is (= 20 (::dht/ack-peers (dht/state (assoc c ::dht/ack-peers 20)))))
      (is (= 2 (::dht/ack-peers (dht/state c))) "default 2"))
    (testing "missing handles and a half-composed socket"
      (is (map? (throws-data #(dht/state (dissoc c :local)))))
      (is (map? (throws-data #(dht/state (dissoc c :answers)))))
      (is (map? (throws-data #(dht/state (dissoc c :datagrams)))))
      (is (map? (throws-data #(dht/state (dissoc c :traffic)))))
      (is (map? (throws-data #(dht/state (assoc c ::dht/id "not-hex"))))))
    (testing "publish? defaults to false"
      (is (false? (::dht/publish? (dht/state c)))))
    (testing "state performs no stream operation"
      (dht/state c)
      (is (zero? (mesh/log-size net)))
      (is (= [] (mesh/facts c) (mesh/answers c))))))


;; ---------------------------------------------------------------------------
;; Section 8: cookies, the gate, the size rule, padding
;; ---------------------------------------------------------------------------

(deftest cookie-for-is-the-s2-stand-in
  (let [c (dht/cookie-for 3 "127.0.0.1" 4100)]
    (is (= 16 (mesh/byte-count c)))
    (is (= (subs (jing/sha256 "3|127.0.0.1|4100") 0 32) (hex c))
        "first 16 bytes of SHA-256(epoch | host | port)")
    (is (not= (hex c) (hex (dht/cookie-for 4 "127.0.0.1" 4100))))
    (is (not= (hex c) (hex (dht/cookie-for 3 "127.0.0.1" 4101))))))


(deftest need-cookie-reply-never-exceeds-first-contact-bytes
  (is (= 256 dht/first-contact-bytes))
  (let [largest {::dht/v 1, :op :reply, :q 9007199254740991,
                 :id (dht/hex->bytes (mesh/node-id 1)),
                 :cookie (dht/cookie-for 999999999999 "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" 65535),
                 :need-cookie true}]
    (is (<= (mesh/byte-count (wire/encode largest)) dht/first-contact-bytes))))


(defn- inject!
  "Deposit raw message m on composition c's traffic as if sent from
   mesh port `from`."
  [c from m]
  (stream/append! (:traffic c)
                  {:dao.stream.datagram/socket "test"
                   :dao.stream.datagram/source {:dao.stream.datagram/host mesh/host
                                                :dao.stream.datagram/port from}
                   :dao.stream.datagram/bytes (jing/bytes->base64
                                                (wire/encode m))}))


(defn- zeros
  [n]
  (dht/hex->bytes (apply str (repeat (* 2 n) "0"))))


(deftest the-gate-answers-need-cookie-or-silence
  (let [net (mesh/mesh)
        b (mesh/join! net 2 {::dht/publish? true})
        _ (mesh/join! net 9)
        s (dht/state b)
        v [:gate 1]
        address (jing/segment-key v)
        base {::dht/v 1, :q 7, :id (dht/hex->bytes (mesh/node-id 9))}]
    (mesh/tick! b 0)
    (testing "a short cookie-less ping is answered with silence"
      (inject! b 9 (assoc base :op :ping))
      (let [before (mesh/log-size net)
            s (dht/step s 64)]
        (is (= before (mesh/log-size net)))
        (is (empty? (dht/routing-entries s)))
        (testing "a padded ping gets exactly the need-cookie reply"
          (inject! b 9 (assoc base :op :ping :pad (zeros 300)))
          (let [s (dht/step s 64)
                [reply & more] (drop before (mesh/sent net))
                m (:message reply)]
            (is (nil? more))
            (is (= [mesh/host 9] (:to reply)))
            (is (= #{::dht/v :op :q :id :cookie :need-cookie} (set (keys m))))
            (is (= [1 :reply 7 true] [(::dht/v m) (:op m) (:q m) (:need-cookie m)]))
            (is (= (mesh/node-id 2) (hex (:id m))))
            (is (= (hex (dht/cookie-for 0 mesh/host 9)) (hex (:cookie m))))
            (is (<= (:size reply) dht/first-contact-bytes))
            (is (empty? (dht/routing-entries s)) "no routing entry")
            (testing "a store with a missing or wrong cookie causes no work"
              (let [bs (jing/canonical-bytes v)
                    store (assoc base :op :store :address address :bytes bs
                                 :pad (zeros 300))
                    before (mesh/log-size net)
                    _ (inject! b 9 store)
                    _ (inject! b 9 (assoc store :cookie (zeros 16)))
                    s (dht/step s 64)
                    replies (map :message (drop before (mesh/sent net)))]
                (is (= [true true] (map :need-cookie replies)))
                (is (every? #(= #{::dht/v :op :q :id :cookie :need-cookie}
                                (set (keys %)))
                            replies))
                (is (nil? (jing/get (:local b) address nil)) "nothing stored")
                (is (empty? (dht/routing-entries s)))
                (testing "the same store with the valid cookie is served"
                  (let [before (mesh/log-size net)
                        _ (inject! b 9 (-> store
                                           (dissoc :pad)
                                           (assoc :cookie (dht/cookie-for
                                                            0 mesh/host 9))))
                        s (dht/step s 64)
                        [m] (map :message (drop before (mesh/sent net)))]
                    (is (= true (:ok m)))
                    (is (= v (jing/get (:local b) address nil)))
                    (is (= [{:id (mesh/node-id 9), :host mesh/host, :port 9}]
                           (dht/routing-entries s)))))))))))))


(deftest first-contact-is-a-padded-ping
  (let [net (mesh/mesh)
        comps (grid net [1 2] {})
        a (comps 1)
        states (start comps)]
    (mesh/request! a (put-request "p" [:first-contact]))
    (mesh/tick-all! comps 0)
    (dht/step (states 1) 64)
    (let [first-sent (first (mesh/sent net))
          m (:message first-sent)]
      (is (= :ping (:op m)))
      (is (not (contains? m :cookie)))
      (is (contains? m :pad))
      (is (>= (:size first-sent) dht/first-contact-bytes)))))


;; ---------------------------------------------------------------------------
;; Section 4: writes and the acknowledgement
;; ---------------------------------------------------------------------------

(defn- warm
  "Four published nodes; node 1 has written :warm-up and been
   acknowledged, so its table is warm and fresh at reading 0."
  []
  (let [net (mesh/mesh)
        comps (grid net [1 2 3 4] {})
        a (comps 1)
        _ (mesh/request! a (put-request "warm" [:warm-up]))
        states (drive (start comps) comps 0 6)]
    {:net net, :comps comps, :states states}))


(deftest warm-table-write-is-acknowledged-in-the-step-that-reads-it
  (let [{:keys [net comps states]} (warm)
        a (comps 1)
        v [:second 2]
        address (jing/segment-key v)]
    (is (= 1 (count (fact-of a ::dht/sent))) "the warm-up was acknowledged")
    (let [before (mesh/log-size net)
          _ (mesh/request! a (put-request "w2" v))
          s (dht/step (states 1) 64)
          earlier (take before (mesh/sent net))
          stores (filter #(and (= :store (get-in % [:message :op]))
                               (= address (get-in % [:message :address])))
                         (drop before (mesh/sent net)))]
      (is (= 2 (count stores)) "exactly ack-peers counted sends")
      (is (= {::dht/fact ::dht/sent, ::dht/address address, ::dht/peers 2}
             (last (fact-of a ::dht/sent))))
      (is (= {:jing/request "w2", :jing/result :inserted}
             (last (mesh/answers a))))
      (testing "every counted peer got the cookie its fresh reply carried"
        (doseq [st stores]
          (let [peer (:to st)
                reply (last (filter #(and (= peer (:from %))
                                          (= [mesh/host 1] (:to %))
                                          (= :reply (get-in % [:message :op]))
                                          (not (get-in % [:message :need-cookie])))
                                    earlier))]
            (is (some? reply))
            (is (= (hex (get-in reply [:message :cookie]))
                   (hex (get-in st [:message :cookie]))))
            (is (= (hex (dht/cookie-for 0 mesh/host 1))
                   (hex (get-in st [:message :cookie])))))))
      (testing "replication continues after the acknowledgement"
        (let [states (mesh/step-all (assoc states 1 s) 6)
              replicated (filter #(= address (::dht/address %))
                                 (fact-of a ::dht/replicated))]
          (is (= [{::dht/fact ::dht/replicated, ::dht/address address,
                   ::dht/peers 3, ::dht/confirmed 3}]
                 replicated))
          (is (every? #(= v (jing/get (:local (comps %)) address nil)) [2 3 4]))
          (is (empty? (:writes (states 1)))))))))


(deftest a-stale-peer-is-pinged-before-it-counts
  (let [{:keys [net comps states]} (warm)
        a (comps 1)
        v [:stale 1]
        address (jing/segment-key v)]
    (mesh/tick-all! comps 60000)
    (let [before (mesh/log-size net)
          _ (mesh/request! a (put-request "s" v))
          s (dht/step (states 1) 64)
          fresh (drop before (mesh/sent net))]
      (is (empty? (filter #(= :store (get-in % [:message :op])) fresh))
          "no peer replied within cookie-epoch-ticks: nothing is sent yet")
      (is (seq (filter #(= :ping (get-in % [:message :op])) fresh)))
      (let [states (mesh/step-all (assoc states 1 s) 4)]
        (is (= address (::dht/address (last (fact-of a ::dht/sent)))))
        (is (some? states))))))


(deftest cold-bootstrap-reaches-too-few-peers-then-succeeds
  (let [net (mesh/mesh)
        a (mesh/join! net 1 {::dht/publish? true
                             ::dht/bootstrap [(mesh/contact 2)
                                              (mesh/contact 3)]})
        v [:cold 1]
        address (jing/segment-key v)
        bs (jing/canonical-bytes v)
        c0 (client/client-state (:requests a) (:answers a) (oldest (:answers a)))
        {c :state, id :id} (client/request-put-bytes c0 address bs)]
    (mesh/tick! a 0)
    (let [s (dht/step (dht/state a) 64)
          _ (mesh/tick! a 4999)
          s (dht/step s 64)]
      (is (empty? (fact-of a ::dht/unacknowledged)) "not before the deadline")
      (mesh/tick! a 5000)
      (let [s (dht/step s 64)
            {:keys [completions]} (client/step c 16)]
        (is (= [{::dht/fact ::dht/unacknowledged, ::dht/address address,
                 ::dht/reason ::dht/too-few-peers, ::dht/peers 0,
                 ::dht/local :inserted}]
               (fact-of a ::dht/unacknowledged)))
        (is (= [{:id id
                 :error {:code :dao.jing.content/unacknowledged
                         :reason ::dht/too-few-peers}}]
               completions))
        (is (empty? (:writes s)) "the pending write is forgotten")
        (is (= v (jing/get (:local a) address nil)) "the local copy stays")
        (testing "once peers are proven the same write is acknowledged"
          (let [comps {1 a
                       2 (mesh/join! net 2 {::dht/publish? true})
                       3 (mesh/join! net 3 {::dht/publish? true})}
                states {1 s
                        2 (dht/state (comps 2))
                        3 (dht/state (comps 3))}]
            (mesh/request! a {::dht/replicate address})
            (drive states comps 6000 6)
            (is (= {::dht/fact ::dht/sent, ::dht/address address,
                    ::dht/peers 2}
                   (last (fact-of a ::dht/sent))))))))))


(defn- at-port?
  [port]
  #(= [mesh/host port] [(:host %) (:port %)]))


(deftest a-query-that-times-out-is-retried-then-its-peer-is-dead
  (let [net (mesh/mesh)
        comps (grid net [1 2 3] {})
        a (comps 1)
        _ (mesh/request! a (put-request "w1" [:proves 2 3]))
        states (drive (start comps) comps 0 6)]
    (is (some (at-port? 3) (dht/routing-entries (states 1))) "3 is proven")
    ;; node 3 goes silent; at 60000 no peer is fresh, so both are pinged
    (mesh/leave! net 3)
    (mesh/request! a (put-request "t" [:timeout]))
    ;; time advances 100 ticks per round, so node 2's replies arrive within
    ;; their query deadline (a reply read after it is dropped)
    (let [tick-rounds (fn [states from to]
                        (reduce (fn [states t] (drive states comps t 1))
                                states
                                (range from to 100)))
          states (tick-rounds states 60000 61100)]
      (is (some (at-port? 3) (dht/routing-entries (states 1)))
          "two timed-out tries: still an entry")
      (let [states (tick-rounds states 61100 61600)]
        (is (not-any? (at-port? 3) (dht/routing-entries (states 1)))
            "the third timed-out try: dead, removed from the table")
        (is (some (at-port? 2) (dht/routing-entries (states 1))))
        (drive states comps 65000 1)
        (is (= [{::dht/fact ::dht/unacknowledged,
                 ::dht/address (jing/segment-key [:timeout]),
                 ::dht/reason ::dht/too-few-peers, ::dht/peers 1,
                 ::dht/local :inserted}]
               (fact-of a ::dht/unacknowledged)))))))


(deftest every-at-once-unacknowledged-reason
  (testing "solo: answered in the step that reads the request"
    (let [c (mesh/solo)
          v [:solo 1]
          address (jing/segment-key v)
          missing (jing/segment-key [:nowhere])]
      (is (not (contains? c :traffic)))
      (is (not (contains? c :datagrams)))
      (mesh/request! c (put-request "r1" v))
      (mesh/request! c {:jing/request "r2", :jing/get missing})
      (mesh/request! c {::dht/replicate address})
      (mesh/request! c (get-request "r3" v))
      (let [s (dht/step (dht/state c) 64)]
        (is (= [{:jing/request "r1", :jing/unacknowledged ::dht/solo}
                {:jing/request "r2", :jing/found? false, :jing/bytes nil}
                {:jing/request "r3", :jing/found? true, :jing/bytes (b64 v)}]
               (mesh/answers c)))
        (is (= [{::dht/fact ::dht/unacknowledged, ::dht/address address,
                 ::dht/reason ::dht/solo, ::dht/peers 0, ::dht/local :inserted}
                {::dht/fact ::dht/miss, ::dht/address missing,
                 ::dht/reason ::dht/solo}
                {::dht/fact ::dht/unacknowledged, ::dht/address address,
                 ::dht/reason ::dht/solo, ::dht/peers 0, ::dht/local nil}]
               (mesh/facts c)))
        (testing "solo holds no pending work"
          (is (= [{} {} {}] [(:writes s) (:gets s) (:queries s)])))
        (is (= v (jing/get (:local c) address nil))))))
  (testing "unpublished: answered at once, nothing sent"
    (let [net (mesh/mesh)
          c (mesh/join! net 1 {::dht/bootstrap [(mesh/contact 2)]})
          _ (mesh/join! net 2 {::dht/publish? true})
          v [:unpublished 1]]
      (mesh/request! c (put-request "u" v))
      (let [s (dht/step (dht/state c) 64)]
        (is (= [{:jing/request "u", :jing/unacknowledged ::dht/unpublished}]
               (mesh/answers c)))
        (is (= ::dht/unpublished
               (::dht/reason (first (fact-of c ::dht/unacknowledged)))))
        (is (zero? (mesh/log-size net)))
        (is (= {} (:writes s))))))
  (testing "oversize: refused before the local insert, in every mode"
    (doseq [c [(mesh/solo {::dht/max-message-bytes 300})
               (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                          ::dht/max-message-bytes 300})]]
      (let [address (jing/segment-key big-value)]
        (mesh/request! c (put-request "o" big-value))
        (dht/step (dht/state c) 64)
        (is (= [{:jing/request "o", :jing/unacknowledged ::dht/oversize}]
               (mesh/answers c)))
        (is (= [{::dht/fact ::dht/unacknowledged, ::dht/address address,
                 ::dht/reason ::dht/oversize, ::dht/peers 0, ::dht/local nil}]
               (mesh/facts c)))
        (is (nil? (jing/get (:local c) address nil)) "no local insert"))))
  (testing "absent: a replicate names an address :local does not hold"
    (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true})
          address (jing/segment-key [:absent])]
      (mesh/request! c {::dht/replicate address})
      (dht/step (dht/state c) 64)
      (is (= [{::dht/fact ::dht/unacknowledged, ::dht/address address,
               ::dht/reason ::dht/absent, ::dht/peers 0, ::dht/local nil}]
             (mesh/facts c)))))
  (testing "busy: max-pending-writes reached"
    (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                       ::dht/max-pending-writes 1
                                       ::dht/bootstrap [(mesh/contact 2)]})]
      (mesh/request! c (put-request "b1" [:busy 1]))
      (mesh/request! c (put-request "b2" [:busy 2]))
      (let [s (dht/step (dht/state c) 64)]
        (is (= [{:jing/request "b2", :jing/unacknowledged ::dht/busy}]
               (mesh/answers c)))
        (is (= [(jing/segment-key [:busy 1])] (keys (:writes s))))))))


(deftest time-advances-only-by-appended-ticks
  (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                     ::dht/bootstrap [(mesh/contact 2)]})
        _ (mesh/request! c (put-request "t" [:no-ticks]))
        s (reduce (fn [s _] (dht/step s 64)) (dht/state c) (range 50))]
    (is (empty? (mesh/facts c)) "no tick observed: nothing expires")
    (is (= 1 (count (:writes s))))
    (mesh/tick! c 0)
    (let [s (dht/step s 64)]
      (is (empty? (mesh/facts c)) "the write began at the first reading")
      (mesh/tick! c 5000)
      (dht/step s 64))
    (is (= ::dht/too-few-peers
           (::dht/reason (first (fact-of c ::dht/unacknowledged)))))))


(deftest an-unpublished-peer-is-sent-to-but-stores-nothing
  (let [net (mesh/mesh)
        comps {1 (mesh/join! net 1 {::dht/publish? true
                                    ::dht/bootstrap [(mesh/contact 2)
                                                     (mesh/contact 3)]})
               2 (mesh/join! net 2)
               3 (mesh/join! net 3)}
        a (comps 1)
        v [:refused 1]
        address (jing/segment-key v)]
    (mesh/request! a (put-request "r" v))
    (drive (start comps) comps 0 8)
    (is (= {::dht/fact ::dht/sent, ::dht/address address, ::dht/peers 2}
           (first (fact-of a ::dht/sent)))
        "acknowledged: sent to 2, never stored by 2")
    (is (= {::dht/fact ::dht/replicated, ::dht/address address,
            ::dht/peers 2, ::dht/confirmed 0}
           (first (fact-of a ::dht/replicated))))
    (is (nil? (jing/get (:local (comps 2)) address nil)))))


;; ---------------------------------------------------------------------------
;; Section 4.5: reads
;; ---------------------------------------------------------------------------

(defn- get-through-client
  "Issue a :jing/get for v from node 1 through the unmodified stepped
   client, drive every node, and return [completions states]."
  [net comps v]
  (let [a (comps 1)
        c0 (client/client-state (:requests a) (:answers a) (oldest (:answers a)))
        {c :state} (client/request-get c0 (jing/segment-key v))
        states (drive (start comps) comps 0 10)]
    (is (some? net))
    [(:completions (client/step c 16)) states]))


(deftest a-remote-read-is-verified-and-cached
  (let [net (mesh/mesh)
        comps (grid net [1 2] {})
        v {:remote "value"}
        address (jing/segment-key v)]
    (jing/materialize! (:local (comps 2)) v)
    (let [[completions] (get-through-client net comps v)]
      (is (= [true v] ((juxt :found? :value) (first completions))))
      (is (= v (jing/get (:local (comps 1)) address nil)) "cached in :local"))))


(deftest a-forged-payload-is-discarded
  (let [v {:honest true}
        address (jing/segment-key v)
        forged {address (jing/canonical-bytes {:forged true})}]
    (testing "only a forger: not found, exhausted"
      (let [net (mesh/mesh)
            comps (grid net [1 2] {})
            comps (assoc comps 2 (assoc (comps 2) :local (mem/create-content-mem forged)))
            [completions] (get-through-client net comps v)]
        (is (= [{:id (:id (first completions)), :found? false, :value nil}]
               completions))
        (is (= [{::dht/fact ::dht/miss, ::dht/address address,
                 ::dht/reason ::dht/exhausted}]
               (fact-of (comps 1) ::dht/miss)))
        (is (nil? (jing/get (:local (comps 1)) address nil)))))
    (testing "a forger and an honest peer: found"
      (let [net (mesh/mesh)
            comps (grid net [1 2 3] {})
            comps (assoc comps 2 (assoc (comps 2) :local (mem/create-content-mem forged)))
            _ (jing/materialize! (:local (comps 3)) v)
            [completions] (get-through-client net comps v)]
        (is (= [true v] ((juxt :found? :value) (first completions))))))))


(deftest an-unpublished-peer-serves-nothing
  (let [net (mesh/mesh)
        comps (grid net [1 2] {::dht/publish? false})
        v {:private true}]
    (jing/materialize! (:local (comps 2)) v)
    (let [[completions] (get-through-client net comps v)]
      (is (false? (:found? (first completions))))
      (is (= ::dht/exhausted (::dht/reason (first (fact-of (comps 1) ::dht/miss))))))))


(deftest read-deadline-and-busy
  (testing "no candidate answers before get-ticks: not found, /deadline"
    (let [c (mesh/join! (mesh/mesh) 1 {::dht/bootstrap [(mesh/contact 2)]})
          address (jing/segment-key [:far])]
      (mesh/request! c {:jing/request "g", :jing/get address})
      (mesh/tick! c 0)
      (let [s (dht/step (dht/state c) 64)]
        (is (= 1 (count (:gets s))))
        (mesh/tick! c 5000)
        (let [s (dht/step s 64)]
          (is (= [{:jing/request "g", :jing/found? false, :jing/bytes nil}]
                 (mesh/answers c)))
          (is (= [{::dht/fact ::dht/miss, ::dht/address address,
                   ::dht/reason ::dht/deadline}]
                 (mesh/facts c)))
          (is (= {} (:gets s)))))))
  (testing "max-pending-gets reached: not found, /busy"
    (let [c (mesh/join! (mesh/mesh) 1 {::dht/max-pending-gets 1
                                       ::dht/bootstrap [(mesh/contact 2)]})]
      (mesh/request! c (get-request "g1" [:g 1]))
      (mesh/request! c (get-request "g2" [:g 2]))
      (dht/step (dht/state c) 64)
      (is (= [{:jing/request "g2", :jing/found? false, :jing/bytes nil}]
             (mesh/answers c)))
      (is (= [{::dht/fact ::dht/miss, ::dht/address (jing/segment-key [:g 2]),
               ::dht/reason ::dht/busy}]
             (mesh/facts c))))))


;; ---------------------------------------------------------------------------
;; The content clients drive a DHT
;; ---------------------------------------------------------------------------

(deftest the-unmodified-content-client-materializes-through-a-dht
  (let [{:keys [comps states]} (warm)
        a (comps 1)
        v {:materialize [1 2 3]}
        address (jing/segment-key v)
        c0 (client/client-state (:requests a) (:answers a) (oldest (:answers a)))
        {c :state, id :id} (client/request-materialize c0 v)
        states (mesh/step-all states 2)
        r0 (client/step c 64)]
    (is (= [{:id id, :materialized? true, :address address, :result :inserted}]
           (:completions r0)))
    (testing "a second materialize answers :present after the verify read"
      (let [{c :state, id :id} (client/request-materialize (:state r0) v)
            states (mesh/step-all states 2)
            ;; :present routes the record to its verify read, which the
            ;; client's next step issues and the one after completes
            r1 (client/step c 64)
            r2 (client/step (:state r1) 64)
            _ (mesh/step-all states 2)
            r3 (client/step (:state r2) 64)]
        (is (= [{:id id, :materialized? true, :address address, :result :present}]
               (mapcat :completions [r1 r2 r3])))))))


(deftest dht-routes-both-algorithms
  (let [net (mesh/mesh)
        comps (grid net [1 2 3] {})
        v {:algo :sha256}
        address (jing/segment-key v {:algorithm :sha256})]
    (mesh/request! (comps 1) {:jing/request "p", :jing/put address, :jing/bytes (b64 v)})
    (let [states (drive (start comps) comps 0 6)]
      (is (= address (::dht/address (first (fact-of (comps 1) ::dht/sent)))))
      (mesh/request! (comps 3) {:jing/request "g", :jing/get address})
      (mesh/step-all states 1)
      (is (= {:jing/request "g", :jing/found? true, :jing/bytes (b64 v)}
             (last (mesh/answers (comps 3))))))))


;; ---------------------------------------------------------------------------
;; Section 5.1: the byte-store handle
;; ---------------------------------------------------------------------------

(def ^:private w-mismatch
  "A payload whose bytes do not hash to {:published \"manifest\"}."
  {:not "it"})


(deftest the-store-handle-returns-the-local-verdict-at-once
  (let [net (mesh/mesh)
        comps (grid net [1 2 3] {})
        a (comps 1)
        h (dht/store-handle {:local (:local a), :requests (:requests a)})
        v {:published "manifest"}
        address (jing/segment-key v)]
    (testing "put-bytes-fn performs no lookup and no peer wait"
      (is (= :inserted ((:put-bytes-fn h) address (jing/canonical-bytes v))))
      (is (= :present ((:put-bytes-fn h) address (jing/canonical-bytes v))))
      (is (zero? (mesh/log-size net)))
      (is (= [{::dht/replicate address} {::dht/replicate address}]
             (mesh/values (:requests a)))))
    (testing "the publish/readback round completes against :local"
      (let [w {:round 2}
            a2 (jing/materialize! h w)]
        (is (= w (jing/get h a2 nil)))
        (is (= ::missing (jing/get h (jing/segment-key [:nowhere]) ::missing)))
        (is (zero? (mesh/log-size net)))))
    (testing "the handle is a valid serve-step handle"
      (let [req (mesh/ring)
            ans (mesh/ring)]
        (stream/append! req (get-request "g" v))
        (content/serve-step h req (oldest req) ans 8)
        (is (= [{:jing/request "g", :jing/found? true, :jing/bytes (b64 v)}]
               (mesh/values ans)))))
    (testing "the acknowledgement arrives later on :facts"
      (drive (start comps) comps 0 6)
      (is (some #(= address (::dht/address %)) (fact-of a ::dht/sent))))
    (testing "invalid and oversize puts throw before any insert"
      (let [small (dht/store-handle {:local (mem/create-content-mem)
                                     :requests (mesh/ring)
                                     :max-message-bytes 300})
            big (jing/segment-key big-value)]
        (is (= ::dht/oversize
               (:reason (throws-data
                          #((:put-bytes-fn small) big
                                                  (jing/canonical-bytes big-value))))))
        (is (nil? (jing/get small big nil)))
        (is (map? (throws-data #((:put-bytes-fn small) address
                                                       (jing/canonical-bytes w-mismatch)))))))))


(defn- temp-path
  []
  (str "target/test-dht-local-" (random-uuid) ".log"))


(deftest a-file-backed-local-verdict-survives-restart
  (let [path (temp-path)
        v {:durable true}
        address (jing/segment-key v)
        bs (jing/canonical-bytes v)
        h1 (dht/store-handle {:local (jing-file/create-content-file path)
                              :requests (mesh/ring)})]
    (is (= :inserted ((:put-bytes-fn h1) address bs)))
    (jing/close! h1)
    (let [h2 (dht/store-handle {:local (jing-file/create-content-file path)
                                :requests (mesh/ring)})]
      (is (= :present ((:put-bytes-fn h2) address bs)))
      (is (= v (jing/get h2 address nil)))
      (jing/close! h2))))


;; ---------------------------------------------------------------------------
;; Section 2: gaps
;; ---------------------------------------------------------------------------

(deftest a-gap-adopts-the-recovery-cursor-and-says-so
  (let [requests (mesh/ring 2)
        c (mesh/solo {:requests requests, :requests-cursor (oldest requests)})]
    (doseq [i (range 4)] (mesh/request! c (get-request (str "g" i) [:gap i])))
    (dht/step (dht/state c) 64)
    (is (= {::dht/fact ::dht/gap, ::dht/stream :requests}
           (first (mesh/facts c))))
    (is (= ["g2" "g3"] (map :jing/request (mesh/answers c)))
        "requests lost to the gap are never answered")))


;; ---------------------------------------------------------------------------
;; Sign-off fix round 1
;; ---------------------------------------------------------------------------

(deftest the-advance-stage-is-bounded-by-the-budget
  ;; section 2: every stage of a step is bounded by budget, the advance of
  ;; pending work included; the pending operations are taken round robin
  (let [net (mesh/mesh)
        c (mesh/join! net 1 {::dht/publish? true
                             ::dht/bootstrap [(mesh/contact 2)]})]
    (doseq [i (range 5)]
      (mesh/request! c (put-request (str "w" i) [:bounded i])))
    (mesh/tick! c 0)
    (let [s (dht/step (dht/state c) 16)]
      (is (= 5 (count (:writes s))))
      (is (= 5 (mesh/log-size net)) "each write pinged the one contact")
      ;; every query expires at 500, so every write owes a new ping
      (mesh/tick! c 500)
      (let [counts (loop [s s
                          acc []]
                     (if (= 3 (count acc))
                       acc
                       (let [before (mesh/log-size net)
                             s (dht/step s 2)]
                         (recur s (conj acc (- (mesh/log-size net) before))))))]
        (is (= [2 2 1] counts)
            "at most budget operations advance per step, round robin")))))


(defn- need-cookie-pair
  "Node 1 has sent its padded first ping to node 2, and node 2 has answered
   need-cookie: returns {:net :sa :sb} with that reply waiting for node 1."
  []
  (let [net (mesh/mesh)
        a (mesh/join! net 1 {::dht/publish? true
                             ::dht/bootstrap [(mesh/contact 2)]})
        b (mesh/join! net 2 {::dht/publish? true})]
    (mesh/tick! a 0)
    (mesh/tick! b 0)
    (mesh/request! a (put-request "n" [:need-cookie]))
    (let [sa (dht/step (dht/state a) 64)
          sb (dht/step (dht/state b) 64)]
      {:net net, :sa sa, :sb sb})))


(deftest query-expiry-is-bounded-by-the-budget
  ;; expiring a query is pending work too: only the operations a step
  ;; advances have their queries expired in that step
  (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                     ::dht/bootstrap [(mesh/contact 2)]})
        expired (fn [s] (count (filter #(seq (:failures %)) (vals (:writes s)))))]
    (doseq [i (range 5)]
      (mesh/request! c (put-request (str "e" i) [:expiry i])))
    (mesh/tick! c 0)
    (let [s (dht/step (dht/state c) 16)]
      (is (= [0 5] [(expired s) (count (:queries s))]))
      (mesh/tick! c 500)
      (let [s1 (dht/step s 2)
            s2 (dht/step s1 2)
            s3 (dht/step s2 2)]
        (is (= [2 4 5] (map expired [s1 s2 s3]))
            "each budget-2 step expires the queries of two operations")))))


(deftest a-reply-after-its-query-deadline-is-dropped
  ;; a write skipped by a budget-1 step keeps its expired queries pending
  ;; until its own advance; a reply arriving meanwhile must not count
  (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                     ::dht/bootstrap [(mesh/contact 2)
                                                      (mesh/contact 3)]})]
    (mesh/request! c (put-request "x" [:late 1]))
    (mesh/request! c (put-request "y" [:late 2]))
    (mesh/tick! c 0)
    (let [s (dht/step (dht/state c) 16)
          _ (is (= 4 (count (:queries s))) "each write pinged 2 and 3")
          _ (mesh/tick! c 600)
          s (dht/step s 1)
          late (filter #(= 0 (:sent (val %))) (:queries s))]
      (is (= 2 (count late)) "the skipped write's queries: expired, pending")
      (doseq [[[_ port q] _] late]
        (inject! c port {::dht/v 1, :op :reply, :q q,
                         :id (dht/hex->bytes (mesh/node-id port)),
                         :cookie (dht/cookie-for 0 mesh/host 1),
                         :ok true}))
      ;; budget 2: both late replies are read, and the round robin advances
      ;; the skipped write first
      (let [s (dht/step s 2)]
        (is (empty? (fact-of c ::dht/sent)) "no acknowledgement from a late reply")
        (is (empty? (dht/routing-entries s)) "the late reply proves no peer")
        (is (empty? (:seen s)) "and freshens none")
        (is (= 2 (count (filter #(seq (:failures %)) (vals (:writes s)))))
            "the skipped write recorded its failed tries in its own advance")))))


(defn- queries-by-owner
  [s]
  (reduce (fn [m [qkey query]] (update m (:owner query) (fnil conj #{}) qkey))
          {}
          (:queries s)))


(deftest queries-are-indexed-by-owner
  ;; an operation visits only its own queries: the index must always equal
  ;; the grouping of :queries by owner, and forget finished operations
  (let [net (mesh/mesh)
        comps (grid net [1 2 3] {})]
    (mesh/request! (comps 1) (put-request "i" [:indexed]))
    (mesh/request! (comps 2) (get-request "j" [:nowhere-indexed]))
    (mesh/tick-all! comps 0)
    (let [states (loop [states (start comps)
                        n 0]
                   (doseq [[p s] states]
                     (is (= (queries-by-owner s) (:query-index s))
                         (str "node " p ", round " n)))
                   (if (< n 12) (recur (mesh/step-all states 1) (inc n)) states))]
      (is (= [{} {}] [(:writes (states 1)) (:gets (states 2))]) "all finished")
      (is (every? #(= {} (:query-index %)) (vals states)))))
  (testing "an operation dropped with queries pending leaves no index entry"
    ;; query-ticks beyond ack-ticks: the query is still pending at give-up
    (let [c (mesh/join! (mesh/mesh) 1 {::dht/publish? true
                                       ::dht/query-ticks 10000
                                       ::dht/bootstrap [(mesh/contact 9)]})]
      (mesh/request! c (put-request "d" [:dropped]))
      (mesh/tick! c 0)
      (let [s (dht/step (dht/state c) 16)]
        (is (= 1 (count (:queries s))))
        (is (= (queries-by-owner s) (:query-index s)))
        (mesh/tick! c 5000)
        (let [s (dht/step s 16)]
          (is (= ::dht/too-few-peers
                 (::dht/reason (first (fact-of c ::dht/unacknowledged)))))
          (is (= [{} {} {}] [(:writes s) (:queries s) (:query-index s)])))))))


(deftest a-need-cookie-cookie-is-untrusted-until-a-full-reply
  (let [address (jing/segment-key [:need-cookie])
        pkey [mesh/host 2]]
    (testing "a need-cookie reply's cookie rides the resend, and no further"
      (let [{:keys [sa sb]} (need-cookie-pair)
            sa2 (dht/step sa 64)]
        (is (not (contains? (:cookies sa2) pkey)))
        (is (= 1 (count (:queries sa2))) "the resend is pending")
        (dht/step sb 64)
        (is (contains? (:cookies (dht/step sa2 64)) pkey)
            "trusted after the full reply")))
    (testing "a resend the socket refuses is a failed send"
      (let [{:keys [net sa]} (need-cookie-pair)]
        (swap! net assoc :refuse?
               (fn [from to] (= [[mesh/host 1] pkey] [from to])))
        (let [sa2 (dht/step sa 64)]
          (is (not (contains? (:cookies sa2) pkey)))
          (is (empty? (:queries sa2)) "not left pending")
          (is (contains? (get-in sa2 [:writes address :dead]) pkey)
              "the peer is not tried again for this write"))))))


(deftest an-unpublished-node-still-routes-fetches-and-caches
  ;; owner decision 6, fetch-only: publish? false refuses inbound stores
  ;; and serves nothing it holds, yet still routes and caches what it asks for
  (let [net (mesh/mesh)
        comps {1 (mesh/join! net 1 {::dht/bootstrap [(mesh/contact 2)]})
               2 (mesh/join! net 2 {::dht/publish? true
                                    ::dht/bootstrap [(mesh/contact 3)]})
               3 (mesh/join! net 3 {::dht/publish? true})}
        v {:fetch-only true}
        address (jing/segment-key v)
        states (start comps)]
    (is (false? (::dht/publish? (states 1))) "unpublished by default")
    (jing/materialize! (:local (comps 3)) v)
    ;; node 2 proves node 3 by asking it for something neither holds
    (mesh/request! (comps 2) (get-request "warm" [:elsewhere]))
    (let [states (drive states comps 0 8)
          _ (is (some (at-port? 3) (dht/routing-entries (states 2))))
          _ (mesh/request! (comps 1) (get-request "g" v))
          states (mesh/step-all states 8)]
      (is (= {:jing/request "g", :jing/found? true, :jing/bytes (b64 v)}
             (last (mesh/answers (comps 1))))
          "fetched on a remote miss")
      (is (= v (jing/get (:local (comps 1)) address nil)) "cached in :local")
      (is (some (at-port? 3) (dht/routing-entries (states 1)))
          "the holder was reached through node 2's :find hints")
      (testing "it answers :find for others"
        (mesh/request! (comps 2) (get-request "via-1" [:unknown 2]))
        (mesh/step-all states 8)
        (is (some #(and (= :reply (get-in % [:message :op]))
                        (contains? (:message %) :peers))
                  (mesh/sent-from net 1)))))))
