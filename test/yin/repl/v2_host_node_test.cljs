(ns yin.repl.v2-host-node-test
  "Real-loopback checks for the Node composition of the Yin REPL host seam."
  (:require [cljs.test :refer [async deftest is]]
            [yin.repl.v2.host :as host]))


(defn- wait-for
  [pred timeout-ms then]
  (let [started (.now js/Date)
        timer (atom nil)
        poll (fn poll []
               (if-let [value (pred)]
                 (do (js/clearInterval @timer) (then value))
                 (when (>= (- (.now js/Date) started) timeout-ms)
                   (js/clearInterval @timer)
                   (then nil))))]
    (reset! timer (js/setInterval poll 10))))


(deftest websocket-exposes-the-complete-node-seam
  (let [adapter (host/websocket)]
    (is (host/adapter? adapter))
    (is (host/binder? adapter))
    (is (fn? (:connect! adapter)))
    (is (fn? (:bind! adapter)))
    (is (fn? (:unbind! adapter)))))


(deftest node-seam-deposits-plain-bind-accept-and-stop-lifecycle
  (async done
    (let [adapter (host/websocket)
          lifecycle (atom [])
          accepted (atom [])
          deposit! (fn [kind value]
                     (swap! lifecycle conj [kind value]))
          resources ((:bind! adapter)
                     {:endpoint {}
                      :bind-host "127.0.0.1"
                      :bind-port 0
                      :path "/repl"
                      :accept! (fn [path socket now]
                                 (swap! accepted conj [path socket now])
                                 nil)
                      :deposit! deposit!})]
      (wait-for #(some (fn [[kind value]]
                         (when (= :bind-succeeded kind) value))
                       @lifecycle)
                2000
                (fn [{:keys [host port] :as bound}]
                  (if-not bound
                    (do
                      (is false "Node listener did not bind")
                      ((:unbind! adapter) resources deposit!)
                      (done))
                    (let [client-connect ((:connect! adapter)
                                          {:dao.stream/type :dao.stream/ws
                                           :dao.stream/identity "host-seam-test"
                                           :ws/host host
                                           :ws/port port
                                           :ws/path "/repl"}
                                          {:message! (fn [_] nil)
                                           :closed! (fn [& _] nil)
                                           :error! (fn [] nil)})]
                      (wait-for #(first @accepted) 2000
                                (fn [[path socket now :as offer]]
                                  (is (some? offer) "upgrade did not reach accept!")
                                  (is (= "/repl" path))
                                  (is (fn? (:send! socket)))
                                  (is (fn? (:close! socket)))
                                  (is (number? now))
                                  ;; The real listener owns the EventEmitter;
                                  ;; its callback must reduce a host Error to
                                  ;; qualified plain data after binding.
                                  (.emit ^js (:ws.node/server resources)
                                         "error" (js/Error. "test-listener-error"))
                                  (is (= [:listener-error
                                          {:code :yin.repl.v2.host/listener-error
                                           :message "the Node WebSocket listener failed"}]
                                         (last @lifecycle)))
                                  ((:close! client-connect) 1000 "test-complete")
                                  ((:unbind! adapter) resources deposit!)
                                  (wait-for #(some (fn [[kind value]]
                                                     (when (= :stopped kind) value))
                                                   @lifecycle)
                                            2000
                                            (fn [stopped]
                                              (is (= {:reason :requested} stopped))
                                              (is (= #{:host :port} (set (keys bound))))
                                              (is (string? host))
                                              (is (number? port))
                                              (is (every? (fn [[kind value]]
                                                            (and (keyword? kind)
                                                                 (or (map? value)
                                                                     (nil? value))))
                                                          @lifecycle))
                                              (done))))))))))))


(deftest an-asynchronous-node-bind-failure-is-plain-lifecycle-data
  (async done
    (let [adapter (host/websocket)
          owner-events (atom [])
          owner-deposit! (fn [kind value]
                           (swap! owner-events conj [kind value]))
          owner ((:bind! adapter)
                 {:endpoint {}
                  :bind-host "127.0.0.1"
                  :bind-port 0
                  :accept! (fn [& _] nil)
                  :deposit! owner-deposit!})]
      (wait-for #(some (fn [[kind value]]
                         (when (= :bind-succeeded kind) value))
                       @owner-events)
                2000
                (fn [{:keys [port] :as bound}]
                  (if-not bound
                    (do
                      (is false "port owner did not bind")
                      ((:unbind! adapter) owner owner-deposit!)
                      (done))
                    (let [failed-events (atom [])
                          failed-deposit! (fn [kind value]
                                            (swap! failed-events conj [kind value]))]
                      ((:bind! adapter)
                       {:endpoint {}
                        :bind-host "127.0.0.1"
                        :bind-port port
                        :accept! (fn [& _] nil)
                        :deposit! failed-deposit!})
                      (wait-for #(some (fn [[kind value]]
                                         (when (= :bind-failed kind) value))
                                       @failed-events)
                                2000
                                (fn [failure]
                                  (is (= {:code :yin.repl.v2.host/bind-failed
                                          :message "the Node WebSocket listener failed to bind"}
                                         failure))
                                  (is (not-any? #(= :bind-succeeded (first %))
                                                @failed-events))
                                  ((:unbind! adapter) owner owner-deposit!)
                                  (wait-for #(some (fn [[kind value]]
                                                     (when (= :stopped kind) value))
                                                   @owner-events)
                                            2000
                                            (fn [stopped]
                                              (is (= {:reason :requested} stopped))
                                              (done))))))))))))
