(ns dao.jing.dht.mesh
  "In-memory sockets for the dao.jing.dht tests (docs/design/dao.jing.dht.md
   section 10, S2): peers wired through ring buffers carrying raw datagram
   values (docs/design/dao.stream.datagram.md section 2). No socket, no
   thread, no clock.

   A mesh is an atom {:traffic {[host port] ring} :log [...] :drop? f}.
   Each node's :datagrams writer is a MeshSocket: its append! takes one
   outbound datagram value, logs it, and deposits the inbound event, with
   the sender's address as the observed source, on the destination's
   traffic ring. A destination with no ring swallows the datagram, as a
   network does; `:drop?` over [from to] loses chosen datagrams the same
   way. append! answers :dao.stream/ok for every well-formed value: ok says
   handed to the socket, nothing about the wire. `:refuse?` over [from to]
   makes the socket refuse instead (:dao.stream/transport-error, nothing
   sent or logged), as a host send that fails synchronously does."
  (:require [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]))


(def host
  "Every mesh node's IP literal."
  "127.0.0.1")


(defn ring
  "A fresh evicting ring handle (owner: reader, writer, closable)."
  ([] (ring 4096))
  ([capacity]
   (:dao.stream/handle
     (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                          ringbuffer/capacity-key capacity}))))


(defn mesh
  "A fresh, empty in-memory network."
  []
  (atom {:traffic {}, :log [], :drop? nil, :refuse? nil}))


(deftype MeshSocket
  [net from-host from-port]

  stream/IDaoStreamWriter

  (append!
    [_ value]
    (let [destination (:dao.stream.datagram/destination value)
          to-host (:dao.stream.datagram/host destination)
          to-port (:dao.stream.datagram/port destination)
          b64 (:dao.stream.datagram/bytes value)]
      (cond
        (not (and (map? value) (string? to-host) (integer? to-port)
                  (string? b64)))
        {:dao.stream/outcome :dao.stream/invalid-value}

        (when-let [f (:refuse? @net)] (f [from-host from-port] [to-host to-port]))
        {:dao.stream/outcome :dao.stream/transport-error}

        :else
        (let [from [from-host from-port]
              to [to-host to-port]
              m (swap! net update :log conj {:from from, :to to, :bytes b64})
              dropped? (when-let [f (:drop? m)] (f from to))
              traffic (get-in m [:traffic to])]
          (when (and traffic (not dropped?))
            (stream/append! traffic
                            {:dao.stream.datagram/socket (str to-host ":"
                                                              to-port)
                             :dao.stream.datagram/source
                             {:dao.stream.datagram/host from-host
                              :dao.stream.datagram/port from-port}
                             :dao.stream.datagram/bytes b64}))
          {:dao.stream/outcome :dao.stream/ok})))))


(defn node-id
  "A test node id for port: 64 lowercase hex characters."
  [port]
  (jing/sha256 (str "dht-test-node-" port)))


(defn contact
  "A bootstrap contact for a mesh port."
  [port]
  {:host host, :port port})


(defn solo
  "A solo composition: no :traffic, no :datagrams."
  ([] (solo {}))
  ([opts]
   (merge {:local (mem/create-content-mem)
           :requests (ring)
           :answers (ring)
           :facts (ring)
           :ticks (ring)
           ::dht/id (node-id 0)}
          opts)))


(defn join!
  "A socket composition for `port` on net: registers the node's traffic
   ring, so datagrams to it arrive from now on."
  ([net port] (join! net port {}))
  ([net port opts]
   (let [traffic (ring)]
     (swap! net assoc-in [:traffic [host port]] traffic)
     (merge (solo {::dht/id (node-id port)})
            {:traffic traffic
             :datagrams (->MeshSocket net host port)}
            opts))))


(defn leave!
  "Unregister port: datagrams to it vanish from now on."
  [net port]
  (swap! net update :traffic dissoc [host port]))


(defn tick!
  "Append one lease tick reading to a composition's tick stream."
  [composition reading]
  (stream/append! (:ticks composition)
                  {:dao.lease/event :dao.lease/tick
                   :dao.lease/reading reading}))


(defn values
  "Every value retained on ring `handle`, oldest first."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest))
         acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn facts
  [composition]
  (values (:facts composition)))


(defn answers
  [composition]
  (values (:answers composition)))


(defn request!
  "Append one request value to a composition's request stream."
  [composition value]
  (stream/append! (:requests composition) value))


(defn byte-count
  "The length of host bytes bs."
  [bs]
  #?(:cljd (.-length bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn sent
  "Every datagram the net logged, decoded: [{:from :to :message :size}]."
  [net]
  (mapv (fn [{:keys [from to bytes]}]
          (let [bs (jing/base64->bytes bytes)]
            {:from from
             :to to
             :size (byte-count bs)
             :bytes bs
             :message (dht/decode-message bs)}))
        (:log @net)))


(defn log-size
  "How many datagrams the net has logged so far."
  [net]
  (count (:log @net)))


(defn step-all
  "Step every node of `states` (port -> state) `rounds` times, in port
   order, returning the successor map."
  [states rounds]
  (reduce (fn [states _]
            (reduce (fn [states port]
                      (update states port dht/step 64))
                    states
                    (sort (keys states))))
          states
          (range rounds)))


(defn tick-all!
  "Append reading to every composition's tick stream."
  [compositions reading]
  (doseq [c (vals compositions)] (tick! c reading)))


(defn sent-from
  "The decoded datagrams the net logged from mesh port `port`."
  [net port]
  (filterv #(= [host port] (:from %)) (sent net)))
