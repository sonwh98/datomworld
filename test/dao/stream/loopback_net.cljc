(ns dao.stream.loopback-net
  "An in-process loopback net: a stand-in for the host listener and
   connect seams of the `yin.repl.host` shape -- `listen-on` is a
   `:bind!`, `unbind-on` an `:unbind!`, `connect-on` a `:connect!` of the
   `dao.stream.ws/make-attacher` shape -- carrying each encoded frame
   between the two ends' `dao.stream.ws/adapter`s through a queue the
   test pumps, so every host runs the same composition
   deterministically.  `blackhole!` drops the frames toward one side;
   `flood!` delivers unrelated value frames to the clients."
  (:require [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]))


(defn loopback-net
  "Listeners by port, live connections, the queue of frames and
   lifecycle calls in flight, and the blackholed sides; `:dialed`
   records every `[host port]` dialed."
  []
  (atom {:listeners {} :queue [] :conns [] :blackholed #{}}))


(defn enqueue!
  "Put `f` in flight; nil, as a host send! that accepted the frame
   answers."
  [net f]
  (swap! net update :queue conj f)
  nil)


(defn pump!
  "Run everything in flight, including what running it puts in flight."
  [net]
  (loop []
    (let [q (:queue @net)]
      (when (seq q)
        (swap! net assoc :queue [])
        (doseq [f q] (f))
        (recur)))))


(defn- deliver!
  [adapter payload]
  (if (string? payload)
    ((:message! adapter) payload)
    ((:binary! adapter) payload)))


(defn- blackholed?
  [net side]
  (contains? (:blackholed @net) side))


(defn blackhole!
  "From now on, frames toward `side` (:client or :server) are accepted
   by the sender's send! and dropped."
  [net side]
  (swap! net update :blackholed conj side)
  nil)


(defn listen-on
  "The `:bind!` seam over `net`: a port already listened on throws, as
   a host bind does."
  [net]
  (fn [{:keys [bind-host bind-port accept! deposit!]}]
    (when (contains? (:listeners @net) bind-port)
      (throw (ex-info "address in use" {:port bind-port})))
    (swap! net assoc-in [:listeners bind-port]
           {:accept! accept! :deposit! deposit!})
    (deposit! :bind-succeeded {:host bind-host :port bind-port})
    {:dao.stream/outcome :dao.stream/ok :port bind-port}))


(defn unbind-on
  "The `:unbind!` seam over `net`: the listener is released at once and
   its close completion deposits `:stopped` once the net is pumped.
   Connections it accepted are the composition's to close."
  [net]
  (fn [listener deposit!]
    (swap! net update :listeners dissoc (:port listener))
    (enqueue! net #(deposit! :stopped {:port (:port listener)}))
    {:dao.stream/outcome :dao.stream/ok}))


(defn close-conn!
  [conn code reason]
  (when-not @(:closed? conn)
    (reset! (:closed? conn) true)
    (reset! (:close-code conn) code)
    (when-some [s @(:server conn)] ((:closed! s) code reason))
    ((:closed! (:client conn)) code reason)))


(defn unlisten!
  "The host listener at `port` goes away under the composition: every
   connection it accepted closes and the listener deposits `:stopped`."
  [net port]
  (let [conns (filterv #(= port (:port %)) (:conns @net))
        l (get-in @net [:listeners port])]
    (swap! net update :listeners dissoc port)
    (doseq [c conns] (close-conn! c 1001 "going away"))
    (when l
      ((:deposit! l) :stopped {:port port}))))


(defn connect-on
  "The `:connect!` seam over `net`; each dial's `[host port]` is
   recorded under `:dialed`."
  [net]
  (fn [descriptor client]
    (let [conn {:port (:ws/port descriptor)
                :client client
                :server (atom nil)
                :closed? (atom false)
                :close-code (atom nil)}]
      (swap! net update :conns conj conn)
      (swap! net update :dialed (fnil conj [])
             [(:ws/host descriptor) (:ws/port descriptor)])
      (enqueue! net
                (fn []
                  (if-some [l (get-in @net [:listeners (:ws/port descriptor)])]
                    (let [socket {:send! (fn [p]
                                           (enqueue! net
                                                     #(when-not (or @(:closed? conn)
                                                                    (blackholed? net :client))
                                                        (deliver! client p))))
                                  :close! (fn [code reason]
                                            (enqueue! net
                                                      #(close-conn!
                                                         conn code reason)))}
                          r ((:accept! l) (:ws/path descriptor) socket 0)]
                      (when-some [h (:ws/handle r)]
                        (reset! (:server conn) (ws/adapter h))
                        ((:opened! client))))
                    (close-conn! conn 1006 "connection refused"))))
      {:send! (fn [p]
                (enqueue! net #(when-not (or @(:closed? conn)
                                             (blackholed? net :server))
                                 (when-some [s @(:server conn)]
                                   (deliver! s p)))))
       :close! (fn [code reason]
                 (enqueue! net #(close-conn! conn code reason)))})))


(defn flood!
  "Deliver `n` unrelated value frames to the client of every live
   connection, at once, past any blackhole."
  [net n]
  (doseq [conn (:conns @net)
          :when (not @(:closed? conn))
          i (range n)]
    (deliver! (:client conn)
              (transit/encode {:ws/frame :ws/value :ws/value {:junk i}}))))
