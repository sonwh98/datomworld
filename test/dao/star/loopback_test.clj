(ns dao.star.loopback-test
  "Mode 1 over the real Phase A boundary on loopback sockets: an agent
   receives, answers and resumes end to end, and a closed socket is observed
   as `:http/closed`.  A raw socket client stands in for Datastar.  One pump
   thread drives every stepped part, with the tick stream fed by a counter, so
   no real clock reaches a lease."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]
            [dao.stream.http :as http]
            [dao.stream.http.jvm :as jvm])
  (:import [java.io ByteArrayOutputStream]
           [java.net Socket]))


(def ^:private duration-ms 100000)


(defn- compose
  "Boundary, listener, leases, router and driver over real media; answers the
   parts plus a `:stop!`."
  []
  (let [ingress (h/medium 256)
        egress (h/medium 1024)
        facts (h/facts-log)
        lineage-lease (h/medium 64)
        exchange-lease (h/medium 64)
        begin (h/medium 32)
        ticks {:driver (h/medium 1024) :exchange (h/medium 1024) :lineage (h/medium 1024)}
        boundary (http/make-boundary
                   {:ingress {:dao.stream/handle ingress :dao.stream/surface #{:writer}}
                    :ingress-admission {:retention :evict-oldest :capacity 256
                                        :value-domain :portable-values
                                        :lifetime :outlives-boundary}
                    :egress {:dao.stream/handle egress :dao.stream/surface #{:reader}}
                    :http/max-open 16 :http/max-body-bytes 4096})
        listener (jvm/listen! boundary {:bind-host "127.0.0.1" :bind-port 0})
        interval (dl/renewal-interval h/units {:ms duration-ms} {:ms 30000} {:ms 1})
        drv (driver/make-driver
              {:facts facts :egress egress :begin begin :tick (:driver ticks)
               :lineage-lease lineage-lease :exchange-lease exchange-lease
               :program h/counter-program :project-fn h/project :begin-fn h/begin-page
               :units h/units :lineage-renewal-interval interval
               :exchange-renewal-interval interval
               :session-capacity 64 :lease-capacity 1024
               :facts-declaration (lease/facts-declaration)})
        xl (lease/make-exchange-lease
             {:ingress ingress :egress egress :lease-medium exchange-lease
              :tick (:exchange ticks)
              :open-exchanges #(http/open-exchanges boundary)
              :close-exchange #(http/close-exchange boundary %)
              :units h/units :duration {:ms duration-ms} :cadence {:ms 10}
              :tolerance {:ms 0} :capacity 1024
              :subscription-max {:ms 100000000}})
        ll (lease/make-lineage-lease
             {:facts facts :lease-medium lineage-lease :tick (:lineage ticks)
              :retire! #(driver/retire! drv %) :units h/units
              :duration {:ms duration-ms} :cadence {:ms 10} :tolerance {:ms 0}
              :facts-declaration (lease/facts-declaration)})
        rt (router/make-router
             {:ingress ingress :facts facts :lease lineage-lease :begin begin
              :co-located? true :facts-declaration (lease/facts-declaration)
              :session-medium #(driver/session-medium drv %)})
        running (atom true)
        clock (atom 0)
        pump (doto (Thread.
                     (fn []
                       (while @running
                         (let [now (swap! clock inc)]
                           (doseq [t (vals ticks)]
                             (stream/append! t (dl/tick {:ms now}))))
                         (try
                           (lease/exchange-lease-step! xl)
                           (lease/lineage-lease-step! ll)
                           (router/router-step! rt)
                           (driver/driver-step! drv)
                           (http/egress-step boundary)
                           (catch Throwable t (println "PUMP" t)))
                         (Thread/sleep 2))))
               (.setDaemon true)
               (.start))]
    {:ingress ingress :egress egress :drv drv :port (:http.jvm/port listener) :boundary boundary
     :stop! (fn []
              (reset! running false)
              (.join ^Thread pump 2000)
              (http/stop! boundary)
              (jvm/stop-listening! listener))}))


(defn- read-all
  [^Socket s]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 4096)]
    (try
      (loop []
        (let [n (.read (.getInputStream s) buf)]
          (when (pos? n)
            (.write out buf 0 n)
            (recur))))
      (catch java.net.SocketTimeoutException _ nil))
    (String. (.toByteArray out) "UTF-8")))


(defn- send-request
  [^Socket s method path body keep-alive?]
  (let [bs (.getBytes ^String (or body "") "UTF-8")
        head (str method " " path " HTTP/1.1\r\nHost: localhost\r\n"
                  (when body (str "Content-Length: " (count bs) "\r\n"))
                  (if keep-alive? "\r\n" "Connection: close\r\n\r\n"))]
    (doto (.getOutputStream s)
      (.write (.getBytes head "UTF-8"))
      (.write bs)
      (.flush))))


(defn- roundtrip
  "One request on its own connection; the whole response up to the close."
  [port method path body]
  (with-open [s (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 5000))]
    (send-request s method path body false)
    (read-all s)))


(defn- wait-for
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))


(deftest boundary-reclaim-ends-the-response-and-deposits-http-closed
  ;; http-kit never reports a client-initiated close of an idle streaming
  ;; exchange (reproduced without any dao code: no on-close after FIN or RST
  ;; and further writes), so the socket here is closed from the boundary's side
  ;; by the reclaim procedure, and the test asserts what the composition then
  ;; observes: the client reads the end of the chunked response (the
  ;; terminator, not a TCP EOF: http-kit keeps the connection alive), the ingress carries :http/closed for that
  ;; exchange, and the driver reads it as data and drops the exchange.
  (let [{:keys [port ingress boundary drv stop!]} (compose)]
    (try
      (let [page (roundtrip port "GET" "/" nil)
            s (second (re-find #"data-root=\"([^\"]+)\"" page))]
        (with-open [sock (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 5000))]
          (send-request sock "GET" (str "/s/" s) nil true)
          (let [in (.getInputStream sock)
                buf (byte-array 4096)
                seen (StringBuilder.)]
            (is (wait-for 5000
                          #(do (when (pos? (.available in))
                                 (let [n (.read in buf)]
                                   (.append seen (String. buf 0 n "UTF-8"))))
                               (str/includes? (str seen) "data-occ"))))
            (let [id (:http/exchange
                       (wait-for 3000
                                 #(first (filter (fn [e]
                                                   (and (= :http/request (:http/event e))
                                                        (= (str "/s/" s) (:http/path e))))
                                                 (h/values ingress)))))]
              (is (some? id))
              (is (contains? (driver/subscriptions drv s) id))
              (is (true? (http/close-exchange boundary id)))
              (is (wait-for 3000
                            #(some (fn [e]
                                     (and (= id (:http/exchange e))
                                          (= :http/closed (:http/event e))))
                                   (h/values ingress)))
                  "the closed socket is deposited as :http/closed")
              (is (not (contains? (http/open-exchanges boundary) id)))
              (is (wait-for 3000
                            #(do (when (pos? (.available in))
                                   (let [n (.read in buf)]
                                     (.append seen (String. buf 0 n "UTF-8"))))
                                 (str/ends-with? (str seen) "0\r\n\r\n")))
                  "the client reads the response to its end")
              (is (wait-for 3000 #(not (contains? (driver/subscriptions drv s) id)))
                  "the driver drops the exchange")))))
      (finally (stop!)))))


(deftest agent-receives-answers-and-resumes-over-the-real-boundary
  (let [{:keys [port stop!]} (compose)]
    (try
      (let [page (roundtrip port "GET" "/" nil)
            s (second (re-find #"data-root=\"([^\"]+)\"" page))
            r1 (roundtrip port "POST" (str "/k/" s) "{\"inc\":5}")
            o1 (h/occ-of r1)
            r2 (roundtrip port "POST" (str "/k/" o1) "{\"inc\":2}")]
        (is (some? s))
        (is (str/includes? page "200"))
        (is (str/includes? r1 "text/event-stream"))
        (is (str/includes? r1 ">5<"))
        (is (some? o1))
        (is (str/includes? r2 ">7<") "the second answer reflects the first")
        (is (not= o1 (h/occ-of r2))))
      (finally (stop!)))))
