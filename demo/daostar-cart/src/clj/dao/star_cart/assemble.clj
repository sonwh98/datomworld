(ns dao.star-cart.assemble
  "The one composition function.  `assemble` wires an HTTP boundary, the
   exchange and lineage judges, the router, the driver, every medium and the
   tick streams for one app; the shop and the grid are two calls with different
   programs, projections and begin pages.  Session facts are a complete-history
   memory log; subscription exchanges carry the required `:dao.lease/max`; the
   renewal intervals come from `lease/renewal-interval`, never by hand."
  (:require [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]
            [dao.stream.http :as http]
            [dao.stream.http.jvm :as jvm]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ring]))


(def units {:ms 1})


(def default-timing
  "Durations and the tick period, in milliseconds.  Each renewal interval is
   sized against its duration and the tick period by `lease/renewal-interval`."
  {:tick-ms 50
   :exchange-duration 60000
   :exchange-renew 20000
   :lineage-duration 1800000
   :lineage-renew 600000
   :subscription-max 3600000})


(defn- ring!
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                                     :dao.stream.ringbuffer/capacity capacity})))


(defn- log!
  []
  (:dao.stream/handle (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn assemble
  "Wire one app.  Config: `:program`, `:project-fn` `(fn [occurrence model])`,
   `:begin-fn` `(fn [root])`, and optional `:timing` merged over
   `default-timing`.  Answers
     {:handler   the ring handler for this app's boundary
      :boundary  the boundary
      :driver :router
      :step!     (fn [now-ms]) one pass of every stepped part, ticks included
      :stop!     (fn [])}"
  [{:keys [program project-fn begin-fn timing]}]
  (let [{:keys [tick-ms exchange-duration exchange-renew lineage-duration
                lineage-renew subscription-max]} (merge default-timing timing)
        period {:ms tick-ms}
        ex-dur {:ms exchange-duration}
        lin-dur {:ms lineage-duration}
        ex-interval (dl/renewal-interval units ex-dur {:ms exchange-renew} period)
        lin-interval (dl/renewal-interval units lin-dur {:ms lineage-renew} period)
        ingress (ring! 1024)
        egress (ring! 4096)
        facts (log!)
        lineage-lease (ring! 1024)
        exchange-lease (ring! 1024)
        begin (ring! 256)
        ticks {:driver (ring! 1024) :exchange (ring! 1024) :lineage (ring! 1024)}
        boundary (http/make-boundary
                   {:ingress {:dao.stream/handle ingress :dao.stream/surface #{:writer}}
                    :ingress-admission {:retention :evict-oldest :capacity 1024
                                        :value-domain :portable-values
                                        :lifetime :outlives-boundary}
                    :egress {:dao.stream/handle egress :dao.stream/surface #{:reader}}
                    :http/max-open 256 :http/max-body-bytes 65536})
        decl (lease/facts-declaration)
        drv (driver/make-driver
              {:facts facts :egress egress :begin begin :tick (:driver ticks)
               :lineage-lease lineage-lease :exchange-lease exchange-lease
               :program program :project-fn project-fn :begin-fn begin-fn
               :units units :lineage-renewal-interval lin-interval
               :exchange-renewal-interval ex-interval
               :session-capacity 256 :lease-capacity 1024 :facts-declaration decl})
        xl (lease/make-exchange-lease
             {:ingress ingress :egress egress :lease-medium exchange-lease
              :tick (:exchange ticks)
              :open-exchanges #(http/open-exchanges boundary)
              :close-exchange #(http/close-exchange boundary %)
              :units units :duration ex-dur :cadence period :tolerance {:ms 0}
              :capacity 4096 :subscription-max {:ms subscription-max}})
        ll (lease/make-lineage-lease
             {:facts facts :lease-medium lineage-lease :tick (:lineage ticks)
              :retire! #(driver/retire! drv %) :units units :duration lin-dur
              :cadence period :tolerance {:ms 0} :facts-declaration decl})
        rt (router/make-router
             {:ingress ingress :facts facts :lease lineage-lease :begin begin
              :co-located? true :facts-declaration decl
              :session-medium #(driver/session-medium drv %)})]
    {:handler (jvm/handler boundary)
     :boundary boundary
     :driver drv
     :router rt
     :ticks ticks
     :step! (fn [now-ms]
              (doseq [t (vals ticks)]
                (stream/append! t (dl/tick {:ms (max 1 now-ms)})))
              (lease/exchange-lease-step! xl)
              (lease/lineage-lease-step! ll)
              (router/router-step! rt)
              (driver/driver-step! drv)
              (http/egress-step boundary))
     :stop! (fn [] (http/stop! boundary))}))
