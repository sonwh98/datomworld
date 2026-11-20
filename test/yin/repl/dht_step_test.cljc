(ns yin.repl.dht-step-test
  "The shipped shell's owner drives a polled datagram seam without a
   receive thread. No host sockets or durable directories are needed."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            #?@(:jolt [[dao.stream.udp :as udp]])
            [yin.repl.driver :as driver]
            [yin.repl.main :as main]
            [yin.shell.dht :as shell.dht]
            [yin.shell.main :as shell]
            [yin.shell.session :as session]))


#?(:jolt
   (deftest jolt-default-binder-provides-the-polled-seam
     (is (identical? udp/bind! (dht/default-bind)))))


(deftest shell-owner-pumps-once-before-binding-and-a-pending-require-fetch
  (let [turns (atom 0)
        sent (atom [])
        bound? (atom false)
        bind! (fn [{:keys [identity deposit]}]
                {:step! (fn []
                          (swap! turns inc)
                          (when (compare-and-set! bound? false true)
                            (stream/append! deposit
                                            (datagram/bound-event
                                              identity "127.0.0.1" 41002))))
                 :send! (fn [host port bytes]
                          (swap! sent conj [host port bytes])
                          {:dao.stream/outcome :dao.stream/ok})
                 :close! (fn [] nil)})
        blob (jing/segment-key :not-local)
        node (dht/join {:local (mem/create-content-mem)
                        :peers [{:host "127.0.0.1" :port 41001}]
                        :bind! bind!})
        source (shell.dht/link-source {:type :dht :dir "unused"}
                                      {:name-env {'my.lib blob}})
        composition (assoc shell/composition
                           :composition-spec? #{::test}
                           :open (fn [_ _]
                                   {:index-store (dht/store node)
                                    :engine-inputs {:link-source source}
                                    :session (session/transport-state node)}))
        state (main/boot {:index-store-spec ::test} composition)]
    (try
      (driver/submit-line! (:input state) "(require (quote my.lib))")
      (let [[state _ _] (main/step-all state nil 0)
            _ (is (= 1 @turns) "one seam turn per engine round")
            _ (is (some? (get-in state [:session :node :dht]))
                  "the pump deposits bound before the node reads traffic")
            _ (is (some? (get-in state [:repl :pending-run]))
                  "the require parks while its module is fetched")
            [state _ _] (main/step-all state nil 10)
            _ (is (= 2 @turns))
            [state _ _] (main/step-all state nil 20)
            _ (is (seq @sent) "an outstanding load emits a DHT query")
            _ (is (every? #(= ["127.0.0.1" 41001] (subvec % 0 2)) @sent))]
        (is (= 3 @turns) "a waiting load still gets one bounded seam turn")
        (is (some? (get-in state [:repl :pending-run])))
        (is (= :loading (:status (dht/load-status
                                   (get-in state [:session :node]) blob)))))
      (finally (dht/close! node)))))


(deftest piped-eof-quits-before-a-pending-require-can-fetch
  ;; The host deposits (quit) on EOF. The old live probe piped require,
  ;; call, EOF together; quitting is a local command even during a require.
  (let [sent (atom [])
        bind! (fn [{:keys [identity deposit]}]
                (stream/append! deposit (datagram/bound-event
                                          identity "127.0.0.1" 41002))
                {:send! (fn [_ _ bytes]
                          (swap! sent conj (jing.dht/decode-message bytes))
                          {:dao.stream/outcome :dao.stream/ok})
                 :close! (fn [] nil)})
        blob (jing/segment-key :not-local)
        node (dht/join {:local (mem/create-content-mem)
                        :peers [{:host "127.0.0.1" :port 41001}]
                        :bind! bind!})
        source (shell.dht/link-source {:type :dht :dir "unused"}
                                      {:name-env {'my.lib blob}})
        composition (assoc shell/composition
                           :composition-spec? #{::test}
                           :open (fn [_ _]
                                   {:index-store (dht/store node)
                                    :engine-inputs {:link-source source}
                                    :session (session/transport-state node)}))
        state (main/boot {:index-store-spec ::test} composition)]
    (try
      (doseq [line ["(println :REQ (require (quote my.lib)))"
                    "(println :CALL-RESULT (f 1))"
                    "(quit)"]]
        (driver/submit-line! (:input state) line))
      (let [[state _ lines] (main/step-all state nil 0)]
        (is (false? (:running? state)) "EOF requests shutdown, not another network round")
        (is (some? (get-in state [:repl :pending-run])))
        (is (some #(re-find #"Bye" %) lines))
        (is (empty? @sent) "no UDP ping was sent before the quit")
        (let [stopping (main/with-session
                         state (shell.dht/stop! (main/session-of state)))
              [state _ _ stopped?] (main/shutdown-tick stopping nil 25)]
          (is (true? stopped?) "shutdown drains endpoints; it does not await the require")
          (is (some? (get-in state [:repl :pending-run])))
          (is (<= (count @sent) 1) "at most an initial ping in the final drain tick")))
      (finally (dht/close! node)))))
