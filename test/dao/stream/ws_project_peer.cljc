(ns dao.stream.ws-project-peer
  "The cross-host peer program of the dao.stream.remote slice-3 socket
   proofs.

   The ws channel composition (dao.stream.ws-project) was proven on one
   JVM over real loopback sockets; the same wire spoken by two hosts is
   a different fact, and only two operating-system processes of two
   different runtimes can falsify it.  This namespace is a whole peer
   program, not a fixture, in two roles over one line-oriented Transit
   command/reply protocol on stdin/stdout:

   * **Dial role** (argv carries the ws descriptor as Transit JSON text)
     -- the dialing end of the spec's section-5 toy.  It composes one
     dial over this host's connect seam, serves \"str-2\" (the single
     value \"backward\", a complete history) through its own mirror, so
     the other direction of the same connection is served by this end,
     and on command attaches a reflection of the served identity
     \"str-1\".  The parent observes only what this program reports.

   * **Serve role** (`--serve`) -- the accepting end of the toy behind
     this host's listener: one endpoint, one handoff slot, the acceptor
     composition, and \"str-1\" (\"hello\", complete history) in the
     mirror table.  It reports the bound port and its adopted sessions.

   One ticker owns the composition's cadence in either role, exactly as
   each host's own driver does; commands only read and report.  A step
   that throws is recorded and reported on the next probe rather than
   silently ending the cadence."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as project]
            ;; `:cljd` first: the ClojureDart host-eval pass also matches
            ;; `:clj`, so a `:clj` branch reached first would pull a JVM-only
            ;; namespace into the Dart build.
            #?@(:cljd [["dart:async" :as async]
                       ["dart:convert" :as convert]
                       ["dart:core" :as dart-core]
                       ["dart:io" :as io]
                       [dao.stream.ws.dart :as host]]
                :clj [[dao.stream.ws.jvm :as host]]
                :cljs [[dao.stream.ws.node :as host]]
                :default [])))


(def ^:private tick-millis
  "Cadence of this peer's own single step owner, in either role."
  5)


(def ^:private path
  "The one served path of the toy's endpoint."
  "/streams/toy")


(def ^:private admission
  {:retention :evict-oldest :capacity 64 :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- served-stream
  "The toy's source: one value, complete history -- the owner's close is
   what makes a later read answer the source's own end."
  [value]
  (let [s (buffer 16)]
    (stream/append! s value)
    (stream/close! s)
    s))


(defn- now-ms
  []
  #?(:cljd (.-millisecondsSinceEpoch (dart-core/DateTime.now))
     :cljs (js/Date.now)
     :clj (System/currentTimeMillis)
     :default 0))


(defn- emit!
  "Write one reply as a single line of Transit JSON on stdout."
  [value]
  (let [text (transit/encode value)]
    #?(;; Dart's stdout is a sink, not a printer: one call writes the line
       ;; and its terminator, which is the whole framing rule of the protocol.
       :cljd (.writeln ^io/Stdout io/stdout text)
       :clj (do (println text) (flush))
       ;; shadow's :node-script target does not install a print-fn that is
       ;; guaranteed to reach stdout unbuffered; write the stream directly.
       :cljs (.write js/process.stdout (str text "\n"))
       :default nil)))


(defn- exit!
  []
  #?(:cljd (.then (.flush ^io/Stdout io/stdout) (fn [_] (io/exit 0)))
     :clj (System/exit 0)
     :cljs (.exit js/process 0)
     :default nil))


(defn- decode
  [line]
  (try (transit/decode line)
       (catch #?(:cljd Object :clj Exception :cljs :default) _
         nil)))


(defn- feed-lines!
  "Split one stdin chunk into whole lines, invoking `on-line` for each
   complete line and returning the unterminated remainder."
  [pending chunk on-line]
  (let [parts (str/split (str pending chunk) #"\n" -1)]
    (doseq [line (butlast parts)
            :when (not (str/blank? line))]
      (on-line line))
    (last parts)))


(defn- read-lines!
  "Consume stdin one line at a time, host idiomatically.  A blocking read
   is safe only on the JVM, where socket callbacks run on host threads:
   Node and Dart have one event loop, so stdin must stay a turn on it or
   the socket callbacks that deposit into the media would never run."
  [on-line on-done]
  #?(:clj (loop [pending ""]
            (if-let [chunk (read-line)]
              (recur (feed-lines! pending (str chunk "\n") on-line))
              (on-done)))
     :cljs (let [pending (atom "")]
             (.setEncoding js/process.stdin "utf8")
             (.on js/process.stdin "data"
                  (fn [chunk]
                    (reset! pending (feed-lines! @pending chunk on-line))))
             (.on js/process.stdin "end" (fn [] (on-done))))
     :cljd (-> io/stdin
               (.transform (.-decoder convert/utf8))
               (.transform (convert/LineSplitter.))
               (.listen (fn [line] (feed-lines! "" (str line "\n") on-line))
                        .onDone (fn [] (on-done))))
     :default nil))


(defn- start-ticker!
  "One timer owns the peer's step, at the cadence the host's own driver
   uses.  Returns the stop function."
  [step!]
  #?(:clj (let [running (atom true)]
            (doto (Thread. (fn []
                             (while @running
                               (step!)
                               (Thread/sleep (long tick-millis))))
                           "ws-project-peer")
              (.setDaemon true)
              (.start))
            (fn [] (reset! running false)))
     :cljs (let [timer (js/setInterval step! tick-millis)]
             (fn [] (js/clearInterval timer)))
     :cljd (let [timer (async/Timer.periodic
                         (dart-core/Duration .milliseconds tick-millis)
                         (fn [_timer] (step!)))]
             (fn [] (.cancel timer)))
     :default (fn [] nil)))


(defn- guarded
  "Run one composition step, recording a throw instead of ending the
   cadence: a probe can then tell a live ticker from a dead one."
  [box step!]
  (try (step!)
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (swap! box assoc :ticker-error (str e)))))


;; =============================================================================
;; The dial role
;; =============================================================================


(defn- confirmation-surface
  "The surface of the attach probe's confirmation, once the link has
   filed it: the served stream's declared surface, learned from the
   probe's answer rather than assumed."
  [events]
  (loop [c (:dao.stream/cursor (stream/cursor events stream/anchor-oldest))]
    (let [r (stream/next events c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (or (:dao.stream.remote/surface (:dao.stream/value r))
            (recur (:dao.stream/cursor r)))
        nil))))


(defn- read-command!
  "Answer one read command against the box's reflection.  The box owns
   the reading cursor: :begin mints it, :next advances it only on the
   source's own ok, and the reply carries the outcome as the reflection
   answered it -- the parent's retry is the cadence, exactly as the
   one-JVM toy's settle! was."
  [box command]
  (case (:op command)
    :begin
    (let [r (stream/cursor (:reflection @box) stream/anchor-oldest)]
      (when (= :dao.stream/ok (:dao.stream/outcome r))
        (swap! box assoc :cursor (:dao.stream/cursor r)))
      (emit! {:reply :read :op :begin
              :outcome (:dao.stream/outcome r)}))
    :next
    (let [r (stream/next (:reflection @box) (:cursor @box))]
      (when (= :dao.stream/ok (:dao.stream/outcome r))
        (swap! box assoc :cursor (:dao.stream/cursor r)))
      (emit! (cond-> {:reply :read :op :next
                      :outcome (:dao.stream/outcome r)}
               (contains? r :dao.stream/value)
               (assoc :value (:dao.stream/value r))
               (contains? r :dao.stream.remote/reason)
               (assoc :reason (:dao.stream.remote/reason r))))
      nil)
    (emit! {:reply :unknown :op (:op command)})))


(defn- dial-command!
  "Interpret one parent command and emit exactly one reply.  Returns
   false only for :quit, which stops the ticker and exits."
  [box descriptor dial stop command]
  (case (:cmd command)
    :attach
    (let [r (project/dial-attach!
              dial
              {:dao.stream/type :dao.stream/remote
               :dao.stream/identity "str-1"
               :dao.stream/channel descriptor})]
      (swap! box assoc :reflection (:dao.stream/handle r))
      (emit! {:reply :attach
              :outcome (:dao.stream/outcome r)
              :attachment (:dao.stream/attachment r)})
      true)

    :probe
    (let [h (:reflection @box)
          d (when h (stream/descriptor h))]
      (emit! {:reply :probe
              :outcome (some-> d :dao.stream/outcome)
              :identity (some-> d :dao.stream/identity)
              :surface (confirmation-surface (:events @box))
              :ticker-error (:ticker-error @box)})
      true)

    :read
    (do (read-command! box command) true)

    :quit
    (do (stop)
        (emit! {:reply :quit})
        (exit!)
        false)

    (do (emit! {:reply :unknown :cmd (:cmd command)}) true)))


(defn- dial-role!
  "The dialing end of the toy over this host's connect seam.  The
   traffic medium and its :dao.stream/newest cursor are minted before
   any attach!, so no deposited event can outrun the position this end
   later reads from."
  [descriptor]
  (let [backward (served-stream "backward")
        traffic (buffer 64)
        events (buffer 64)
        dial (project/dial
               {:attach! (ws/make-attacher
                           {:traffic {:dao.stream/handle traffic
                                      :dao.stream/surface #{:writer}}
                            :admission admission
                            :connect! host/connect!})
                :traffic {:dao.stream/handle traffic
                          :dao.stream/surface #{:writer}}
                :cursor (newest traffic)
                :ring (buffer 64)
                :table {"str-2" {:handle backward :surface #{:reader}}}
                :dao.stream.remote/events events})
        box (atom {:reflection nil :cursor nil :events events
                   :ticker-error nil})
        stop (start-ticker! (fn [] (guarded box #(project/dial-step! dial))))]
    (emit! {:reply :ready :descriptor descriptor})
    (read-lines!
      (fn [line]
        (when-some [command (decode line)]
          (dial-command! box descriptor dial stop command)))
      (fn []
        (stop)
        (exit!)))))


;; =============================================================================
;; The serve role
;; =============================================================================


(defn- listen!
  "Bind this host's listener behind `endpoint`, reporting the assigned
   port through `on-port`.  The bound port is learned from the host's
   own bind report, never guessed."
  [endpoint on-port]
  #?(:clj
     (host/listen!
       {:bind-host "127.0.0.1"
        :bind-port 0
        :accept! (fn [target socket now]
                   (ws/accept-connection! endpoint target socket now))
        :deposit! (fn [kind data]
                    (when (= :bind-succeeded kind)
                      (on-port (:port data))))})
     :cljs
     (host/listen! endpoint
                   {:host "127.0.0.1"
                    :port 0
                    :on-listening (fn [address]
                                    (on-port (.-port address)))})
     :cljd
     (host/bind!
       {:bind-host "127.0.0.1"
        :bind-port 0
        :accept! (fn [target socket now]
                   (ws/accept-connection! endpoint target socket now))
        :deposit! (fn [kind data]
                    (when (= :bind-succeeded kind)
                      (on-port (:port data))))})
     :default nil))


(defn- stop-listener!
  [listener]
  #?(:clj (host/stop-listening! listener (fn [] nil))
     :cljs (host/stop-listening! listener)
     :cljd (host/unbind! listener (fn [_kind _data] nil))
     :default nil))


(defn- serve-command!
  "Interpret one parent command and emit exactly one reply."
  [box acceptor listener stop command]
  (case (:cmd command)
    :sessions
    (emit! {:reply :sessions
            :count (count (project/sessions acceptor))
            :ticker-error (:ticker-error @box)})

    :quit
    (do (stop)
        (stop-listener! listener)
        (emit! {:reply :quit})
        (exit!)
        false)

    (do (emit! {:reply :unknown :cmd (:cmd command)}) true)))


(defn- serve-role!
  "The accepting end of the toy behind this host's listener: the ws
   endpoint with one acceptance-handoff slot, the acceptor composition,
   and the toy's served table.  The bound port is the parent's
   readiness signal."
  []
  (let [hello (served-stream "hello")
        descriptor {:dao.stream/type :dao.stream/ws
                    :dao.stream/identity "str-1"
                    :ws/host "127.0.0.1"
                    :ws/port 1
                    :ws/path path}
        offers (buffer 1)
        acks (buffer 1)
        endpoint (ws/make-endpoint
                   {:descriptor descriptor
                    :control {:dao.stream/handle (buffer 16)
                              :dao.stream/surface #{:writer}}
                    :control-admission admission
                    :slots [{:offer {:dao.stream/handle offers
                                     :dao.stream/surface #{:writer}}
                             :offer-admission handoff-admission
                             :ack {:dao.stream/handle acks
                                   :dao.stream/surface #{:writer}}
                             :ack-admission handoff-admission
                             :ack-cursor (newest acks)}]
                    :expiry-ms nil})
        acceptor (project/make-acceptor
                   {:endpoint endpoint
                    :slots [{:offer-reader offers
                             :offer-cursor (newest offers)
                             :ack-writer {:dao.stream/handle acks
                                          :dao.stream/surface #{:writer}}}]
                    :table {"str-1" {:handle hello :surface #{:reader}}}
                    :make-media (fn [_offer]
                                  (let [traffic (buffer 64)
                                        channel (buffer 64)]
                                    {:traffic
                                     {:dao.stream/handle traffic
                                      :dao.stream/surface #{:writer}}
                                     :admission admission
                                     :reader traffic
                                     :cursor (newest traffic)
                                     :ring channel}))})
        box (atom {:ticker-error nil})
        stop (start-ticker!
               (fn [] (guarded box #(project/accept-step! acceptor (now-ms)))))
        listener (atom nil)]
    (reset! listener
            (listen! endpoint
                     (fn [port]
                       ;; On the JVM the bind deposit arrives inside listen!
                       ;; itself; on Node it arrives on a later event-loop
                       ;; turn.  Either way the pipe holds the reply until
                       ;; the parent reads it.
                       (emit! {:reply :serving :port port :path path}))))
    (read-lines!
      (fn [line]
        (when-some [command (decode line)]
          (serve-command! box acceptor @listener stop command)))
      (fn []
        (stop)
        (stop-listener! @listener)
        (exit!)))))


(defn -main
  "Serve role with `--serve`; dial role with a descriptor argument."
  [& args]
  (if (and (seq args) (= "--serve" (first args)))
    (serve-role!)
    (dial-role! (transit/decode-descriptor (first args)))))


#?(:cljd
   ;; The Dart VM starts a program at its top-level `main`; `-main` munges
   ;; to a name the VM will never look for.  This is the whole difference
   ;; between this peer as a library and as a process.
   (defn ^{:dart/name main} peer-main
     [args]
     (apply -main (vec args))))
