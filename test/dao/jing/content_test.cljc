(ns dao.jing.content-test
  "Tests for dao.jing.content, the request and response content service,
   and its JVM blocking driver dao.jing.content.driver.

   The serve-step tests run on every host over two ring buffers, the
   interpreter answering raw requests a hand-turned client appends. The
   driver tests are JVM-only (the driver is JVM-only) and run their
   bodies there, asserting trivially elsewhere, over a served ring pair
   a daemon ticker advances."

  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.content :as content]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            ;; :cljd first: the driver and the client-state requires have
            ;; no Dart twins; the driver's every def is JVM-only.
            #?@(:cljd [["dart:typed_data" :as typed]]
                :clj [[dao.jing.content.driver :as driver]
                      [dao.jing.content.step :as step]])))


(defn- ring-handle
  "A fresh ring-buffer stream handle for the in-process media below."
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   :dao.stream.ringbuffer/capacity capacity})))


(defn- ring-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- answers-of
  "Every value on medium `handle`, oldest first."
  [handle]
  (loop [cursor (ring-cursor handle), acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- b64
  "The wire form of a value: Base64 of its canonical bytes."
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- host-bytes
  "A host byte object from a seq of ints 0-255."
  [ints]
  #?(:clj (byte-array (mapv unchecked-byte ints))
     :cljs (js/Buffer.from (clj->js (vec ints)))
     :cljd (typed/Uint8List.fromList ints)))


;; =============================================================================
;; serve-step: the interpreter over raw requests (portable)
;; =============================================================================


(deftest serve-step-answers-reads-and-writes
  (let [store (mem/create-content-mem)
        requests (ring-handle 8)
        answers (ring-handle 8)
        payload {:hello "world"}
        address (jing/segment-key payload)]
    (try
      (stream/append! requests {:jing/request "r1"
                                :jing/put address
                                :jing/bytes (b64 payload)})
      (stream/append! requests {:jing/request "r2", :jing/get address})
      (stream/append! requests {:jing/request "r3"
                                :jing/get (jing/segment-key {:no "such"})})
      (is (= #:dao.stream.ringbuffer{:position 3}
             (select-keys
               (content/serve-step store requests (ring-cursor requests)
                                   answers 8)
               [:dao.stream.ringbuffer/position]))
          "the successor cursor advanced past the three requests")
      (is (= [{:jing/request "r1", :jing/result :inserted}
              {:jing/request "r2", :jing/found? true
               :jing/bytes (b64 payload)}
              {:jing/request "r3", :jing/found? false, :jing/bytes nil}]
             (answers-of answers)))
      ;; A duplicate put answers :present through the same interpreter.
      (stream/append! requests {:jing/request "r4"
                                :jing/put address
                                :jing/bytes (b64 payload)})
      (content/serve-step store requests
                          (:dao.stream/cursor
                            (stream/next requests (ring-cursor requests)))
                          answers 8)
      (is (= {:jing/request "r4", :jing/result :present}
             (peek (answers-of answers))))
      (finally (jing/close! store)))))


(deftest serve-step-drops-malformed-requests
  ;; Malformed wire input is silence: the cursor advances, no answer.
  (let [store (mem/create-content-mem)
        requests (ring-handle 8)
        answers (ring-handle 8)
        address (jing/segment-key {:x 1})]
    (try
      (doseq [v [{:jing/request "r1"}                        ; no op
                 {:jing/get address}                          ; no id
                 {:jing/request "r2", :jing/get :not/an-address}
                 {:jing/request "r3", :jing/get address, :jing/extra 1}
                 "not-a-map"
                 nil]]
        (stream/append! requests v))
      (content/serve-step store requests (ring-cursor requests) answers 8)
      (is (= [] (answers-of answers)))
      (finally (jing/close! store)))))


(deftest serve-step-refuses-a-put-whose-bytes-fail-ingress
  ;; The one ingress canonicality check on every put: a hash mismatch,
  ;; non-strict Base64 and a hash-valid non-canonical payload are all
  ;; the server's silence, the cursor advancing past them.
  (let [store (mem/create-content-mem)
        requests (ring-handle 8)
        answers (ring-handle 8)
        address (jing/segment-key {:x 1})
        other (jing/segment-key {:x 2})
        noncanonical (host-bytes [0x18 0x01])
        algo jing/default-hash-algorithm
        reg (get jing/registry algo)
        nc-address (keyword "segment"
                            (str (:address-id reg) "-"
                                 (jing/digest-bytes algo noncanonical)))]
    (try
      (let [refused [["r1" address (b64 {:x 2})]
                     ["r2" other "not base64!"]
                     ["r3" nc-address
                      (jing/bytes->base64 noncanonical)]]
            append-refused!
            (fn [[id target bytes]]
              (stream/append! requests {:jing/request id
                                        :jing/put target
                                        :jing/bytes bytes}))]
        (doseq [row refused]
          (append-refused! row)))
      (content/serve-step store requests (ring-cursor requests) answers 8)
      (is (= [] (answers-of answers))
          "every refused put was dropped unanswered")
      ;; ...and nothing was stored by a refused put.
      (is (= ::miss (jing/get store address ::miss)))
      (is (= ::miss (jing/get store nc-address ::miss)))
      (finally (jing/close! store)))))


(deftest serve-step-throws-on-an-invalid-backend-verdict
  (let [requests (ring-handle 8)
        answers (ring-handle 8)
        store {:put-bytes-fn (fn [_ _] :bogus)
               :get-bytes-fn (fn [_ not-found] not-found)}
        address (jing/segment-key {:x 1})]
    (stream/append! requests {:jing/request "r1"
                              :jing/put address
                              :jing/bytes (b64 {:x 1})})
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (content/serve-step store requests (ring-cursor requests)
                              answers 8))
        "a backend verdict outside #{:inserted :present} throws")))


(deftest serve-step-requires-a-content-handle
  (let [requests (ring-handle 8)
        answers (ring-handle 8)]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (content/serve-step {} requests (ring-cursor requests)
                              answers 8))
        "a handle without the byte-store effects is a composition
         defect")))


(deftest serve-step-honors-its-budget
  ;; At most budget requests are answered per advance, and a zero
  ;; budget answers nothing while leaving the cursor where it was.
  (let [store (mem/create-content-mem)
        requests (ring-handle 8)
        answers (ring-handle 8)
        address (jing/segment-key {:x 1})]
    (try
      (doseq [n (range 5)]
        (stream/append! requests {:jing/request (str "r" n)
                                  :jing/get address}))
      (let [after-two (content/serve-step store requests
                                          (ring-cursor requests) answers 2)]
        (is (= 2 (count (answers-of answers))))
        (is (= (ring-cursor requests)
               (content/serve-step store requests (ring-cursor requests)
                                   answers 0))
            "a zero budget answers nothing")
        (content/serve-step store requests after-two answers 8)
        (is (= 5 (count (answers-of answers)))
            "a later advance answers the rest"))
      (finally (jing/close! store)))))


;; =============================================================================
;; The JVM blocking driver over a served ring pair
;; =============================================================================


#?(:cljd nil
   :clj
   (defn- served-pair
     "A blocking driver over a ring pair a daemon ticker serves from the
     store. Returns the driver handle and a stop! for the ticker."
     ([store] (served-pair store {}))
     ([store opts]
      (let [requests (ring-handle 64)
            answers (ring-handle 64)
            server-cursor (atom (ring-cursor requests))
            running (atom true)
            ticker (Thread.
                     (fn []
                       (while @running
                         (swap! server-cursor
                                (fn [c]
                                  (content/serve-step store requests c
                                                      answers 32)))
                         (Thread/sleep 1))))]
        (doto ticker
          (.setDaemon true)
          (.setName "content-test-server")
          (.start))
        {:handle (driver/driver
                   (step/client-state requests answers
                                      (ring-cursor answers))
                   opts)
         :stop! (fn [] (reset! running false) nil)}))))


#?(:cljd nil
   :clj
   (defn- with-pair
     "Run f over a served driver pair, stopping the ticker after."
     ([f] (with-pair (mem/create-content-mem) {} f))
     ([store opts f]
      (let [{:keys [handle stop!]} (served-pair store opts)]
        (try (f handle)
             (finally (stop!) (jing/close! store)))))))


(deftest driver-materializes-and-gets-test
  #?(:clj
     (with-pair
       (fn [client]
         (let [payload {:hello "world"}
               address (jing/materialize! client payload)]
           (is (= (jing/segment-key payload) address)
               "materialize! must return the payload's segment address")
           (is (= payload (jing/get client address ::miss)))
           (is (= :present ((:put-bytes-fn client)
                            address (jing/canonical-bytes payload)))
               "duplicate content must report :present over the pair"))))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest driver-nil-opaque-and-absent-test
  #?(:clj
     (with-pair
       (fn [client]
         (let [nil-address (jing/materialize! client nil)]
           (is (= (jing/segment-key nil) nil-address)
               "materialize! must mint the address of a nil payload")
           (is (nil? (jing/get client nil-address ::miss))
               "a stored nil must be returned as nil, not as the absent
                sentinel"))
         (let [opaque [1 2 3 {:nested true}]
               opaque-address (jing/materialize! client opaque)]
           (is (= opaque (jing/get client opaque-address ::miss))
               "opaque values must round-trip"))
         (is (= ::miss
                (jing/get client (jing/segment-key {:never "written"})
                          ::miss))
             "an absent address must return the not-found sentinel")
         (is (thrown? Exception (jing/get client :anything-at-all ::miss))
             "client get must reject an arbitrary keyword address")))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest driver-close-is-once-and-ops-throw-after
  #?(:clj
     (with-pair
       (fn [client]
         (jing/close! client)
         (jing/close! client)
         (is (true? @(:closed-atom client))
             "the client reports itself closed")
         (is (thrown? Exception (jing/materialize! client {:x 1}))
             "materialize! after close must throw")
         (is (thrown? Exception
               (jing/get client (jing/segment-key {:x 1}) ::miss))
             "get after close must throw")))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest a-hash-valid-noncanonical-payload-is-refused-at-ingress
  ;; An address minted over bytes that decode as one payload but are
  ;; not its canonical encoding, served by a store holding exactly
  ;; those bytes. The digest matches the minted address, so only the
  ;; one ingress check can refuse the reply.
  #?(:clj
     (let [bs (byte-array (mapv unchecked-byte [0x18 0x01]))
           algo jing/default-hash-algorithm
           reg (get jing/registry algo)
           address (keyword "segment"
                            (str (:address-id reg)
                                 "-" (jing/digest-bytes algo bs)))
           store {:put-bytes-fn (fn [_ _] :present)
                  :get-bytes-fn (fn [_ _] bs)}]
       (with-pair store {}
         (fn [client]
           (let [error (try
                         (jing/get client address ::miss)
                         (is false "a noncanonical found reply must refuse")
                         nil
                         (catch Exception e e))]
             (is (= {:code :dao.jing.content/integrity-failure
                     :message (str "the remote content does not hash "
                                   "to its content address")}
                    (:error (ex-data error))))))))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest a-request-timeout-retires-the-call-and-the-driver-recovers
  ;; A pair nothing serves: the call spends its deadline, throws its
  ;; own timeout with its own id, and the stored client state owes
  ;; nothing -- so a later call over a served pair works.
  #?(:clj
     (let [store (mem/create-content-mem)
           requests (ring-handle 64)
           answers (ring-handle 64)
           client (driver/driver
                    (step/client-state requests answers
                                       (ring-cursor answers))
                    {:request-timeout-ms 50})]
       (dotimes [_n 2]
         (let [error (try
                       ((:get-bytes-fn client)
                        (jing/segment-key {:x 1}) ::miss)
                       (is false "an unanswered call must throw")
                       nil
                       (catch Exception e e))]
           (is (= 50 (:timeout-ms (ex-data error)))
               "the deadline is the whole of the claim")
           (is (string? (:request-id (ex-data error))))))
       (jing/close! client)
       ;; And the driver is usable over a pair that does answer.
       (with-pair store {}
         (fn [working]
           (let [payload {:back "up"}
                 address (jing/materialize! working payload)]
             (is (= payload (jing/get working address ::miss)))))))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest an-interrupted-call-retires-and-permits-no-id-reuse
  ;; The interrupt flag is set on the test's own thread before the
  ;; call: Thread/sleep throws on entry when the flag is already set,
  ;; so the await loop leaves through its interrupted exit at the first
  ;; sleep -- deterministically, with no second thread and no race.
  #?(:clj
     (let [requests (ring-handle 64)
           answers (ring-handle 64)
           client (driver/driver
                    (step/client-state requests answers
                                       (ring-cursor answers)))
           address (jing/segment-key {:x 1})]
       (try
         (.interrupt (Thread/currentThread))
         (let [error (try
                       ((:get-bytes-fn client) address ::miss)
                       (is false "an interrupted call must throw")
                       nil
                       (catch Exception e
                         (let [preserved? (Thread/interrupted)]
                           (is (true? preserved?)
                               "the interrupt flag is preserved for the
                                caller -- re-asserted by the exit before
                                it throws -- and this read clears it")
                           e)))]
           (is (= :dao.jing.content/interrupted (:reason (ex-data error)))
               "the interruption surfaces as data, after the
                retirement")
           (is (string? (:request-id (ex-data error)))
               "the retired call's id is reported"))
         (let [state @(:client client)]
           (is (= {} (:outstanding state))
               "the interrupted call is retired, so its late answer
                will be dropped as unsolicited")
           (is (= [] (:completed state)))
           (is (= [] (:diagnostics state))))
         (finally
           (Thread/interrupted)              ; never leak the flag
           (jing/close! client))))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))


(deftest invalid-timing-options-throw-before-the-wire
  ;; The driver validates its options at construction, before anything
  ;; is submitted: the stored client state is untouched and nothing
  ;; reaches the pair.
  #?(:clj
     (let [requests (ring-handle 8)
           answers (ring-handle 8)
           state (step/client-state requests answers
                                    (ring-cursor answers))]
       (doseq [broken [{:request-timeout-ms :soon}
                       {:poll-interval-ms -5}
                       {:request-timeout-ms (inc driver/max-timing-ms)}
                       {:request-timeout-ms Long/MAX_VALUE}]]
         (is (thrown-with-msg? Exception
                               #"must be an integer of milliseconds"
               (driver/driver state broken))
             (str "rejected at construction: " (pr-str broken))))
       (is (empty? (answers-of requests))
           "no request crossed the writer for a rejected value")
       ;; At the bound the option is supported and the deadline
       ;; arithmetic is safe.
       (is (map? (driver/driver state
                                {:request-timeout-ms
                                 driver/max-timing-ms}))))
     :cljd (is true "the blocking driver is JVM-only")
     :cljs (is true "the blocking driver is JVM-only")))
