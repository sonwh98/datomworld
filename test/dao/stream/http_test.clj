(ns dao.stream.http-test
  "The DaoStream HTTP boundary: core state machine against scripted sockets,
   plus one real loopback round trip through the http-kit listener."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.http :as http]
            [dao.stream.http.jvm :as jvm]
            [dao.stream.ringbuffer :as ring])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))


(defn- medium
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                   :dao.stream.ringbuffer/capacity capacity})))


(def ^:private admission
  {:retention :evict-oldest :capacity 8 :value-domain :portable-values
   :lifetime :outlives-boundary})


(defn- boundary
  ([] (boundary {}))
  ([{:keys [ingress egress open body inc] :or {open 4 body 16}}]
   (let [ingress (or ingress (medium 16))
         egress (or egress (medium 16))]
     {:ingress ingress
      :egress egress
      :b (http/make-boundary
           (cond-> {:ingress {:dao.stream/handle ingress :dao.stream/surface #{:writer}}
                    :ingress-admission admission
                    :egress {:dao.stream/handle egress :dao.stream/surface #{:reader}}
                    :http/max-open open
                    :http/max-body-bytes body}
             inc (assoc :http/incarnation inc)))})))


(defn- scripted
  "A socket seam recording its calls into `trace`; `fail` names the call that
   fails."
  ([trace] (scripted trace nil))
  ([trace fail]
   (letfn [(rec
             [k]
             (fn [& args]
               (swap! trace conj (into [k] args))
               (not= k fail)))]
     {:head! (rec :head) :write! (rec :write) :end! (rec :end) :close! (rec :close)})))


(defn- drain
  "Every value currently readable from `handle`."
  [handle]
  (loop [c (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)) out []]
    (let [r (stream/next handle c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj out (:dao.stream/value r)))
        out))))


(defn- req
  ([] (req {}))
  ([m] (merge {:method :POST :path "/action" :query "a=1"
               :headers {"Accept" "text/event-stream"} :body (.getBytes "{}")}
              m)))


(defn- open!
  "Accept a request on a scripted socket; [id trace]."
  ([b] (open! b (req)))
  ([b r]
   (let [trace (atom [])
         res (http/accept! b r (scripted trace))]
     [(:http/exchange res) trace res])))


(defn- third
  [[_ _ x]]
  x)


(defn- effect!
  [{:keys [b egress]} e]
  (stream/append! egress e)
  (http/egress-step b))


(defn- events
  [ingress id]
  (filterv #(= id (:http/exchange %)) (drain ingress)))


(defn- terminal-events
  [ingress id]
  (filterv #(#{:http/ended :http/closed} (:http/event %)) (events ingress id)))


(deftest assembly
  (testing "a reject-mode or malformed composition is refused at assembly"
    (let [i (medium 4) e (medium 4)
          good {:ingress {:dao.stream/handle i :dao.stream/surface #{:writer}}
                :ingress-admission admission
                :egress {:dao.stream/handle e :dao.stream/surface #{:reader}}
                :http/max-open 1 :http/max-body-bytes 1}]
      (is (some? (http/make-boundary good)))
      (is (thrown? Exception (http/make-boundary
                               (assoc good :ingress-admission (assoc admission :retention :reject)))))
      (is (thrown? Exception (http/make-boundary
                               (assoc good :ingress-admission (assoc admission :value-domain :host-values)))))
      (is (thrown? Exception (http/make-boundary
                               (assoc good :ingress-admission (dissoc admission :lifetime)))))
      (is (thrown? Exception (http/make-boundary (dissoc good :http/max-open))))
      (is (thrown? Exception (http/make-boundary (assoc good :http/max-body-bytes 0)))))))


(deftest request-event-shape
  (let [{:keys [b ingress]} (boundary)
        [id _ res] (open! b)]
    (is (= :http/accepted (:http/status res)))
    (let [[ev] (events ingress id)]
      (is (= :http/request (:http/event ev)))
      (is (= :post (:http/method ev)))
      (is (= "/action" (:http/path ev)))
      (is (= "a=1" (:http/query ev)))
      (is (= {"accept" "text/event-stream"} (:http/headers ev)))
      (is (bytes? (:http/body ev)))
      (is (vector? (:http/exchange ev))))
    (testing "query and body absent when none"
      (let [[id2] (open! b (req {:query nil :body nil}))
            [ev2] (events ingress id2)]
        (is (not (contains? ev2 :http/query)))
        (is (not (contains? ev2 :http/body)))))
    (testing "an empty query and body are none"
      (let [[id3] (open! b (req {:query "" :body (byte-array 0)}))
            [ev3] (events ingress id3)]
        (is (not (contains? ev3 :http/query)))
        (is (not (contains? ev3 :http/body)))))))


(deftest admission-limits
  (testing "over :http/max-open answered below the transform, never deposited"
    (let [{:keys [b ingress]} (boundary {:open 1})
          [id] (open! b)
          [id2 _ res] (open! b)]
      (is (some? id))
      (is (nil? id2))
      (is (= :http/full (:http/status res)))
      (is (= 1 (count (filter #(= :http/request (:http/event %)) (drain ingress)))))
      (testing "a slot returns when the exchange ends"
        (http/close-exchange b id)
        (is (= :http/accepted (:http/status (third (open! b))))))))
  (testing "over :http/max-body-bytes answered below the transform"
    (let [{:keys [b ingress]} (boundary {:body 4})
          [id _ res] (open! b (req {:body (byte-array 5)}))]
      (is (nil? id))
      (is (= :http/too-large (:http/status res)))
      (is (empty? (drain ingress)))
      (is (= :http/accepted (:http/status (third (open! b (req {:body (byte-array 4)})))))))))


(deftest evict-oldest-capacity
  (let [{:keys [b ingress]} (boundary {:ingress (medium 2) :open 10})]
    (dotimes [_ 3] (open! b))
    (is (= 2 (count (drain ingress))) "oldest evicted, deposit still admitted")
    (is (= 3 (count (http/open-exchanges b))))))


(deftest exchange-ids
  (let [{:keys [b]} (boundary {:inc 7 :open 10})
        [a] (open! b) [c] (open! b)]
    (is (= [7 1] a))
    (is (= [7 2] c))
    (http/close-exchange b a)
    (let [[d] (open! b)]
      (is (= [7 3] d) "never reused after close")))
  (testing "incarnation distinguishes a restarted boundary"
    (let [egress (medium 16)
          {b1 :b} (boundary {:egress egress})
          {b2 :b} (boundary {:egress egress})
          [id1] (open! b1)
          [id2] (open! b2)]
      (is (not= id1 id2))
      (stream/append! egress {:http/exchange id1 :http/effect :http/head :http/status 200})
      (http/egress-step b2)
      (is (= #{id2} (http/open-exchanges b2)))
      (is (= :new (get-in @(:state b2) [:open id2 :phase])) "stale effect never matches"))))


(deftest terminal-rule
  (testing "peer close"
    (let [{:keys [b ingress]} (boundary)
          [id] (open! b)]
      (http/peer-closed! b id)
      (http/peer-closed! b id)
      (is (= [:http/request :http/closed] (mapv :http/event (events ingress id))))))
  (testing "write failure"
    (let [{:keys [b ingress egress] :as bd} (boundary)
          trace (atom [])
          id (:http/exchange (http/accept! b (req) (scripted trace :head)))]
      (effect! bd {:http/exchange id :http/effect :http/head :http/status 200})
      (is (= [:http/request :http/closed] (mapv :http/event (events ingress id))))
      (is (empty? (http/open-exchanges b)))
      (is (some #{[:close]} @trace))
      (is egress)))
  (testing "out-of-order effect"
    (let [{:keys [b ingress] :as bd} (boundary)
          [id trace] (open! b)]
      (effect! bd {:http/exchange id :http/effect :http/chunk :http/body "x"})
      (is (= [:http/request :http/closed] (mapv :http/event (events ingress id))))
      (is (some #{[:close]} @trace))))
  (testing "close-exchange is idempotent"
    (let [{:keys [b ingress]} (boundary)
          [id] (open! b)]
      (is (true? (http/close-exchange b id)))
      (is (false? (http/close-exchange b id)))
      (is (false? (http/close-exchange b [:nope 1])))
      (is (= 1 (count (terminal-events ingress id))))))
  (testing "boundary stop"
    (let [{:keys [b ingress]} (boundary)
          [a] (open! b) [c] (open! b)
          stopped (atom 0)]
      (http/on-stop! b #(swap! stopped inc))
      (http/stop! b)
      (http/stop! b)
      (is (= [:http/closed] (mapv :http/event (terminal-events ingress a))))
      (is (= [:http/closed] (mapv :http/event (terminal-events ingress c))))
      (is (= :http/stopped (:http/status (third (open! b)))))
      (is (pos? @stopped)))))


(deftest effects
  (let [{:keys [b ingress] :as bd} (boundary)
        [id trace] (open! b)]
    (effect! bd {:http/exchange id :http/effect :http/head :http/status 200
                 :http/headers {"content-type" "text/plain"}})
    (effect! bd {:http/exchange id :http/effect :http/chunk :http/body "héllo"})
    (effect! bd {:http/exchange id :http/effect :http/chunk :http/body (.getBytes "b")})
    (is (= #{id} (http/open-exchanges b)))
    (effect! bd {:http/exchange id :http/effect :http/end})
    (is (= [:head 200 {"content-type" "text/plain"}] (first @trace)))
    (is (= "héllo" (second (second @trace))))
    (is (= [:end] (last @trace)))
    (is (= [:http/request :http/ended] (mapv :http/event (events ingress id))))
    (is (empty? (http/open-exchanges b)))
    (testing "values after the end, or for unknown exchanges, are ignored"
      (let [n (count @trace)]
        (effect! bd {:http/exchange id :http/effect :http/chunk :http/body "late"})
        (effect! bd {:http/exchange [9 9] :http/effect :http/end})
        (effect! bd {:something :else})
        (effect! bd 42)
        (is (= n (count @trace)))
        (is (= 1 (count (terminal-events ingress id))))))))


(deftest head-order
  (testing "a second head closes the exchange"
    (let [{:keys [b ingress] :as bd} (boundary)
          [id] (open! b)]
      (effect! bd {:http/exchange id :http/effect :http/head :http/status 200})
      (effect! bd {:http/exchange id :http/effect :http/head :http/status 200})
      (is (= :http/closed (:http/event (last (events ingress id)))))))
  (testing "end before head closes the exchange"
    (let [{:keys [b ingress] :as bd} (boundary)
          [id] (open! b)]
      (effect! bd {:http/exchange id :http/effect :http/end})
      (is (= :http/closed (:http/event (last (events ingress id))))))))


(deftest egress-lifecycle
  (testing "effects retained before the cursor are not performed"
    (let [egress (medium 16)
          _ (stream/append! egress {:http/exchange [1 1] :http/effect :http/end})
          {:keys [b]} (boundary {:egress egress})]
      (http/egress-step b)
      (is (empty? (http/open-exchanges b)))))
  (testing "an egress gap closes every open exchange and adopts the recovery cursor"
    (let [{:keys [b ingress egress] :as bd} (boundary {:egress (medium 2)})
          [a] (open! b) [c] (open! b)]
      (dotimes [_ 5] (stream/append! egress {:other true}))
      (http/egress-step b)
      (is (empty? (http/open-exchanges b)))
      (is (= :http/closed (:http/event (last (events ingress a)))))
      (is (= :http/closed (:http/event (last (events ingress c)))))
      (let [[d] (open! b)]
        (effect! bd {:http/exchange d :http/effect :http/head :http/status 200})
        (is (= :streaming (get-in @(:state b) [:open d :phase]))))))
  (testing "egress end closes every open exchange and the listener"
    (let [{:keys [b ingress egress]} (boundary)
          [a] (open! b)
          stopped (atom 0)]
      (http/on-stop! b #(swap! stopped inc))
      (stream/close! egress)
      (http/egress-step b)
      (is (= :http/closed (:http/event (last (events ingress a)))))
      (is (= 1 @stopped)))))


(deftest observably-gone
  (testing "a non-ok ingress deposit closes the socket with no response"
    (let [full (medium 1)
          closed (do (stream/close! full) full)
          {:keys [b]} (boundary {:ingress closed})
          [id trace res] (open! b)]
      (is (= :http/gone (:http/status res)))
      (is (some? id))
      (is (= [[:close]] @trace))
      (is (empty? (http/open-exchanges b)))))
  (testing "the reclaim procedure deposits the terminal event"
    (let [{:keys [b ingress]} (boundary)
          [id trace] (open! b)]
      (is (= #{id} (http/open-exchanges b)))
      (http/close-exchange b id)
      (is (= [[:close]] @trace))
      (is (= :http/closed (:http/event (last (events ingress id))))))))


;; -----------------------------------------------------------------------------
;; Loopback
;; -----------------------------------------------------------------------------

(defn- wait-for
  "Poll `f` up to `ms` milliseconds for a truthy value."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))


(deftest loopback-round-trip
  (let [{:keys [b ingress egress]} (boundary {:body 1024})
        {:http.jvm/keys [port] :as listener} (jvm/listen! b {:bind-host "127.0.0.1" :bind-port 0})
        client (HttpClient/newHttpClient)
        uri (URI/create (str "http://127.0.0.1:" port "/action?a=1"))
        request (-> (HttpRequest/newBuilder uri)
                    (.header "Accept" "text/event-stream")
                    (.POST (HttpRequest$BodyPublishers/ofString "{\"count\":1}"))
                    (.build))
        response (.sendAsync client request (HttpResponse$BodyHandlers/ofString))]
    (try
      (let [ev (wait-for 3000 #(first (drain ingress)))]
        (is (= :http/request (:http/event ev)))
        (is (= :post (:http/method ev)))
        (is (= "/action" (:http/path ev)))
        (is (= "a=1" (:http/query ev)))
        (is (= "text/event-stream" (get-in ev [:http/headers "accept"])))
        (is (= "{\"count\":1}" (String. ^bytes (:http/body ev) "UTF-8")))
        (let [id (:http/exchange ev)]
          (stream/append! egress {:http/exchange id :http/effect :http/head
                                  :http/status 201 :http/headers {"content-type" "text/plain"}})
          (stream/append! egress {:http/exchange id :http/effect :http/chunk :http/body "héllo "})
          (stream/append! egress {:http/exchange id :http/effect :http/chunk
                                  :http/body (.getBytes "world" "UTF-8")})
          (stream/append! egress {:http/exchange id :http/effect :http/end})
          (http/egress-step b)
          (let [resp (.get response 3 java.util.concurrent.TimeUnit/SECONDS)]
            (is (= 201 (.statusCode resp)))
            (is (= "héllo world" (.body resp))))
          (is (wait-for 3000 #(= :http/ended (:http/event (last (events ingress id))))))
          (is (= 1 (count (terminal-events ingress id))))))
      (testing "an over-limit body is answered 413 and not deposited"
        (let [before (count (drain ingress))
              big (-> (HttpRequest/newBuilder uri)
                      (.POST (HttpRequest$BodyPublishers/ofByteArray (byte-array 4096)))
                      (.build))
              resp (.send client big (HttpResponse$BodyHandlers/ofString))]
          (is (= 413 (.statusCode resp)))
          (is (= before (count (drain ingress))))))
      (finally
        (http/stop! b)
        (jvm/stop-listening! listener)))))


;; -----------------------------------------------------------------------------
;; Competing claims (review r2, findings 1 and 2)
;; -----------------------------------------------------------------------------

(defn- race
  "Run every fn in `fs` on its own thread released together; wait for all."
  [fs]
  (let [start (java.util.concurrent.CountDownLatch. 1)
        threads (mapv (fn [f]
                        (doto (Thread. (fn [] (.await start) (f)))
                          (.start)))
                      fs)]
    (.countDown start)
    (doseq [^Thread t threads] (.join t 5000))))


(deftest competing-closers-produce-exactly-one-terminal-event
  (dotimes [_ 200]
    (let [{:keys [b ingress]} (boundary)
          [id trace] (open! b)
          wins (atom 0)]
      (race (repeat 4 #(when (http/close-exchange b id) (swap! wins inc))))
      (is (= 1 @wins) "exactly one competing claim owns the exchange")
      (is (= [:http/request :http/closed] (mapv :http/event (events ingress id))))
      (is (= 1 (count (filter #{[:close]} @trace))) "the socket is closed once"))))


(deftest a-terminal-during-the-request-deposit-lands-after-the-request
  (testing "deterministic: the peer closes while the request event is being deposited"
    (let [ring (medium 16)
          b (atom nil)
          id-seen (atom nil)
          ingress (reify
                    stream/IDaoStreamWriter
                    (append!
                      [_ v]
                      (when (and (= :http/request (:http/event v)) (nil? @id-seen))
                        (reset! id-seen (:http/exchange v))
                        (http/peer-closed! @b (:http/exchange v)))
                      (stream/append! ring v)))
          egress (medium 16)]
      (reset! b (http/make-boundary
                  {:ingress {:dao.stream/handle ingress :dao.stream/surface #{:writer}}
                   :ingress-admission admission
                   :egress {:dao.stream/handle egress :dao.stream/surface #{:reader}}
                   :http/max-open 4 :http/max-body-bytes 16}))
      (let [res (http/accept! @b (req) (scripted (atom [])))
            id (:http/exchange res)]
        (is (= :http/accepted (:http/status res)))
        (is (= [:http/request :http/closed] (mapv :http/event (events ring id)))
            "request first, then exactly one terminal")
        (is (empty? (http/open-exchanges @b))))))
  (testing "stress: a closer races the accepting call"
    (dotimes [_ 200]
      (let [{:keys [b ingress]} (boundary)
            trace (atom [])
            res (atom nil)
            closer (fn []
                     ;; the id is [incarnation n]; close whatever is open
                     (doseq [id (http/open-exchanges b)] (http/close-exchange b id)))]
        (race [#(reset! res (http/accept! b (req) (scripted trace))) closer closer])
        (let [id (:http/exchange @res)
              evs (mapv :http/event (events ingress id))]
          (is (= :http/request (first evs)))
          (is (<= (count (filter #{:http/closed} evs)) 1) "never a duplicate terminal")
          (when (empty? (http/open-exchanges b))
            (is (= [:http/request :http/closed] evs) "a closed exchange has its terminal")))))))
