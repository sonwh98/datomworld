(ns dao.stream.v2.slice-peer
  "Process B of the DaoStream v2 Phase 5 slice.

   Phase 5 is deliberately two operating-system processes rather than two
   compositions in one: a same-process socket test cannot show that a
   descriptor is self-contained, nor that no accidental shared state carries
   identity across.  This namespace is therefore a whole peer program, not a
   fixture — `dao.stream.v2.slice-test` (JVM), `dao.stream.v2.slice-test`
   (Node) and `dao.stream.v2.slice-test` (Dart) each spawn it as a child
   process and speak to it only over pipes.

   The bootstrap channel is explicit and load-bearing: B receives A's
   descriptor as Transit JSON *text* in `argv`, decodes it through the
   contract's descriptor gate, and never receives a handle, an atom, or any
   other host value from A.  Everything else is a line-oriented Transit
   command/reply protocol on stdin/stdout, so the parent observes B only
   through data B chose to report.

   Two properties of the composition here are the point of Phase 5's fourth
   fact and must not be `refactored' away:

   * the deposit medium and its `:dao.stream/newest` cursor are minted once,
     before the first `attach!`, and survive every attachment; and
   * the cursor is never handed to `attach!` and never used through the
     WebSocket handle, which has no reader surface at all.

   This peer owns its own cadence.  It installs no timer and polls nothing:
   host socket callbacks deposit into the medium on their own threads (JVM) or
   turns (Node and Dart), and the parent asks for what has accumulated with
   `:events`."
  (:require [clojure.string :as str]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.transit :as transit]
            [dao.stream.v2.ws :as ws]
            ;; `:cljd` first: the ClojureDart host-eval pass also matches
            ;; `:clj`, so a `:clj` branch reached first would pull a JVM-only
            ;; namespace into the Dart build.
            #?@(:cljd [["dart:convert" :as convert]
                       ["dart:io" :as io]
                       [dao.stream.v2.ws.dart :as host]]
                :clj [[dao.stream.v2.ws.jvm :as host]]
                :cljs [[dao.stream.v2.ws.node :as host]]
                :default [])))


(def admission
  "The deposit declaration for B's traffic medium.  Declared as configuration
   provenance, never interrogated from the handle."
  {:retention :evict-oldest :capacity 256 :value-domain :portable-values})


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


(defn- buffer
  []
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                     ring/capacity-key 256})))


(defn make-peer
  "Compose B's boundary around one bootstrapped descriptor.

   Ordering matters and is asserted by the parent: the medium exists and its
   cursor is minted before any `attach!`, so no deposited event can outrun the
   position the parent will later resume from."
  [descriptor]
  (let [traffic (buffer)
        minted (stream/cursor traffic stream/anchor-newest)
        attacher (ws/make-attacher
                   {:traffic {:dao.stream/handle traffic
                              :dao.stream/surface #{:writer}}
                    :admission admission
                    :connect! #?(:cljd host/connect! :clj host/connect!
                                 :cljs host/connect! :default nil)})]
    (atom {:descriptor descriptor
           :traffic traffic
           :cursor (:dao.stream/cursor minted)
           :attacher attacher
           :handle nil
           :attach nil})))


(defn- drain
  "Read forward from the kept cursor without consuming: reads are
   non-destructive, so this returns the events and the position after them."
  [state]
  (loop [cursor (:cursor state)
         events []]
    (let [result (stream/next (:traffic state) cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome result))
        (recur (:dao.stream/cursor result) (conj events (:dao.stream/value result)))
        [cursor events (:dao.stream/outcome result)]))))


(defn handle-command!
  "Interpret one parent command and emit exactly one reply."
  [peer command]
  (let [state @peer
        handle (:handle state)]
    (case (:cmd command)
      :attach
      ;; The descriptor is the bootstrapped one every time, so a reattachment
      ;; provably uses the identical portable value, not a repaired copy.
      (let [result ((:attacher state) (:descriptor state))]
        (swap! peer assoc :handle (:dao.stream/handle result) :attach result)
        (emit! {:reply :attach
                :outcome (:dao.stream/outcome result)
                :attachment (:dao.stream/attachment result)}))

      :append
      (emit! {:reply :append
              :outcome (:dao.stream/outcome (stream/append! handle (:value command)))})

      :close
      (emit! {:reply :close
              :outcome (:dao.stream/outcome (stream/close! handle))})

      :surfaces
      (emit! {:reply :surfaces :value (stream/declared-surfaces handle)})

      :handle-descriptor
      (let [result (stream/descriptor handle)]
        (emit! {:reply :handle-descriptor
                :outcome (:dao.stream/outcome result)
                :descriptor (:dao.stream/descriptor result)
                :identity (:dao.stream/identity result)}))

      :events
      (let [[cursor events outcome] (drain state)]
        (swap! peer assoc :cursor cursor)
        (emit! {:reply :events :events events :outcome outcome}))

      :exit
      (do (emit! {:reply :exit}) (exit!))

      (emit! {:reply :unknown :cmd (:cmd command)}))))


(defn- feed!
  "Split a stdin chunk into whole lines, returning the unterminated remainder."
  [peer pending chunk]
  (let [parts (str/split (str pending chunk) #"\n" -1)]
    (doseq [line (butlast parts)
            :when (not (str/blank? line))]
      (handle-command! peer (transit/decode line)))
    (last parts)))


(defn -main
  "Bootstrap from `argv` and serve the parent's commands until told to exit."
  [& args]
  (let [descriptor (transit/decode-descriptor (first args))
        peer (make-peer descriptor)]
    (emit! {:reply :ready :descriptor descriptor})
    #?(:cljd
       ;; One isolate, so stdin is a subscription on the event loop for the
       ;; same reason it is on Node: a blocking read would starve the socket
       ;; callbacks this peer exists to observe.
       (let [pending (atom "")]
         (.listen (.transform io/stdin (.-decoder convert/utf8))
                  (fn [chunk] (reset! pending (feed! peer @pending chunk)))
                  .onDone (fn [] (exit!))))

       :clj
       ;; Blocking on stdin is safe here: every socket callback runs on a host
       ;; thread and deposits into the medium without this loop's help.
       (loop []
         (when-let [line (read-line)]
           (when-not (str/blank? line)
             (handle-command! peer (transit/decode line)))
           (recur)))

       :cljs
       ;; Node has one thread, so stdin must be a turn on the event loop or the
       ;; socket callbacks would never run.
       (let [pending (atom "")]
         (.setEncoding js/process.stdin "utf8")
         (.on js/process.stdin "data"
              (fn [chunk] (reset! pending (feed! peer @pending chunk))))
         (.on js/process.stdin "end" (fn [] (exit!))))

       :default nil)))


#?(:cljd
   ;; The Dart VM starts a program at its top-level `main`; `-main` munges to
   ;; a name the VM will never look for.  This is the whole difference between
   ;; B as a library and B as a process.
   (defn ^{:dart/name main} peer-main
     [args]
     (apply -main (vec args))))
