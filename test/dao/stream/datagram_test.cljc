(ns dao.stream.datagram-test
  "Portable, host-free tests for dao.stream.datagram
   (docs/design/dao.stream.datagram.md): the value shapes of section 2, the
   IP-literal and port rules, the descriptor of section 3, and every
   append!/close! outcome of the writer handle of section 4 over a scripted
   host seam. The seam contract itself ({:send! f :close! f}, a deposit
   writer and nothing else) is what the host seam tests of
   dao.stream.datagram.{jvm,node,dart} drive over real sockets; everything
   here proves the portable layer's own decisions without a host."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.datagram :as datagram]
            [dao.stream.ringbuffer :as ringbuffer])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- values
  "Every retained value of `h`, oldest first."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- octets->bytes
  [octets]
  #?(:cljd (Uint8List.fromList (vec octets))
     :clj (byte-array (mapv unchecked-byte octets))
     :cljs (js/Uint8Array.from (to-array (vec octets)))))


(defn- octets-of
  "Any byte payload as a vector of unsigned octets, the one shape that
   compares equal on every host."
  [bs]
  (mapv (fn [b] (bit-and 0xff (int b))) (seq bs)))


(defn- destination
  [host port]
  {:dao.stream.datagram/destination {:dao.stream.datagram/host host
                                     :dao.stream.datagram/port port}})


(defn- outbound
  "One outbound value of section 2."
  [host port octets]
  (assoc (destination host port)
         :dao.stream.datagram/bytes
         (base64/encode (octets->bytes octets))))


(defn- scripted-seam
  "A fake host seam: `:seam` is the {:send! f :close! f} of section 3.
   `:sends` records every datagram the seam was handed as
   [host port bytes]; setting `:fail` to a positive n makes the next n
   sends fail synchronously, depositing the send-failed lifecycle event the
   real seams deposit for every send failure they observe; `:closes`
   counts close!s. The deposit side of the seam is the composition's ring,
   which the test drives directly."
  [deposit & {:keys [identity]}]
  (let [sends (atom [])
        fail (atom 0)
        closes (atom 0)
        id (or identity "sock-x")
        seam {:send!
              (fn [host port bytes]
                (if (pos? @fail)
                  (do (swap! fail dec)
                      (stream/append!
                        deposit (datagram/send-failed-event id "scripted refusal"))
                      {:dao.stream/outcome :dao.stream/transport-error
                       :dao.stream.datagram/reason "scripted refusal"})
                  (do (swap! sends conj [host port bytes])
                      {:dao.stream/outcome :dao.stream/ok})))
              :close! (fn []
                        (swap! closes inc)
                        (stream/append! deposit (datagram/closed-event id))
                        nil)}]
    {:seam seam :sends sends :fail fail :closes closes}))


(defn- compose-bound-writer
  "The composition's second move of section 3, host-free: the bound event
   is already on the deposit ring (the scripted composition deposited it),
   and the writer is composed from the seam and the complete descriptor it
   reports."
  [deposit seam & {:keys [identity max-bytes]}]
  (let [id (or identity "sock-x")]
    (stream/append! deposit (datagram/bound-event id "127.0.0.1" 41234))
    (datagram/writer seam
                     {:dao.stream/type :dao.stream/datagram
                      :dao.stream/identity id
                      :dao.stream.datagram/bind-host "127.0.0.1"
                      :dao.stream.datagram/bind-port 41234}
                     (or max-bytes datagram/default-max-bytes))))


;; =============================================================================
;; IP literals and ports (section 2)
;; =============================================================================


(deftest ip-literal-rule
  (testing "a host is always an IP literal in its textual form"
    (doseq [valid ["127.0.0.1" "0.0.0.0" "203.0.113.7" "255.255.255.255"
                   "8.8.8.8" "192.0.2.1"
                   "::" "::1" "fe80::1" "2001:db8::8a2e:370:7334"
                   "1:2:3:4:5:6:7:8" "::ffff:192.0.2.128"
                   "64:ff9b::1.2.3.4" "1:2:3:4:5:6:1.2.3.4"]]
      (is (datagram/ip-literal? valid) (pr-str valid)))
    (doseq [invalid ["localhost"                     ; a name, not a literal
                     "example.com"
                     "127.1"                         ; three octets
                     "1.2.3" "1.2.3.4.5"             ; wrong octet count
                     "256.1.1.1" "1.2.3.256"         ; out of range
                     "01.2.3.4" "1.2.3.04"           ; leading zeros
                     "1..2.3" ".1.2.3" "1.2.3.4."
                     "-1.2.3.4" "1.2.3.-4"
                     "1.2.3.4 " " 1.2.3.4"           ; whitespace
                     " ::1" " ::" ":: " "\n::" "1::\t"
                     "0x1.2.3.4"
                     "" "1:2::3::4"                  ; two compressions
                     ":1:2::" "1:2::3:"              ; stray single colons
                     "::1::" "1:::2"
                     "12345::"                       ; group over 4 hex
                     "1:2:3:4:5:6:7:8:9"             ; nine groups
                     "1:2:3:4:5:6:7:8::"             ; eight groups plus compression
                     "fe80::1%eth0"                  ; zone id
                     "::ffff:1.2.3.4.5"              ; bad embedded IPv4
                     "1:2:3:4:5:6:7:1.2.3.4"         ; 7 groups + IPv4 = 9
                     "banana"]]
      (is (not (datagram/ip-literal? invalid)) (pr-str invalid)))))


(deftest port-rule
  (doseq [valid [1 80 4100 65535]]
    (is (datagram/valid-port? valid) (pr-str valid)))
  (doseq [invalid [0 -1 65536 1.5 "80" nil :x]]
    (is (not (datagram/valid-port? invalid)) (pr-str invalid))))


;; =============================================================================
;; Event and descriptor shapes (sections 2 and 3)
;; =============================================================================


(deftest lifecycle-events-are-exact-shapes
  (testing "one event per host fact, failures carrying text reasons"
    (is (= {:dao.stream.datagram/socket "s1"
            :dao.stream.datagram/event :dao.stream.datagram/bound
            :dao.stream.datagram/local {:dao.stream.datagram/host "127.0.0.1"
                                        :dao.stream.datagram/port 41234}}
           (datagram/bound-event "s1" "127.0.0.1" 41234)))
    (is (= {:dao.stream.datagram/socket "s1"
            :dao.stream.datagram/event :dao.stream.datagram/bind-failed
            :dao.stream.datagram/reason "port in use"}
           (datagram/bind-failed-event "s1" "port in use")))
    (is (= {:dao.stream.datagram/socket "s1"
            :dao.stream.datagram/event :dao.stream.datagram/send-failed
            :dao.stream.datagram/reason "network unreachable"}
           (datagram/send-failed-event "s1" "network unreachable")))
    (is (= {:dao.stream.datagram/socket "s1"
            :dao.stream.datagram/event :dao.stream.datagram/closed}
           (datagram/closed-event "s1")))))


(deftest datagram-event-encodes-exact-payload
  (let [octets [1 2 3 250 0]
        event (datagram/datagram-event "s1" "198.51.100.2" 53122
                                       (octets->bytes octets))]
    (is (= {:dao.stream.datagram/socket "s1"
            :dao.stream.datagram/source {:dao.stream.datagram/host
                                         "198.51.100.2"
                                         :dao.stream.datagram/port 53122}
            :dao.stream.datagram/bytes (base64/encode (octets->bytes octets))}
           (select-keys event
                        [:dao.stream.datagram/socket
                         :dao.stream.datagram/source
                         :dao.stream.datagram/bytes])))
    (is (datagram/datagram-event? event))
    (is (datagram/lifecycle-event? (datagram/closed-event "s1")))
    (is (not (datagram/datagram-event? (datagram/closed-event "s1"))))
    (is (not (datagram/lifecycle-event? event)))
    (testing "a value carrying neither bytes+source nor an event is not
              this layer's"
      (is (not (datagram/datagram-event? {:dao.stream.datagram/socket "s1"})))
      (is (not (datagram/datagram-event? "banana"))))))


(deftest descriptor-gate
  (let [base {:dao.stream/type :dao.stream/datagram
              :dao.stream/identity "s1"
              :dao.stream.datagram/bind-host "127.0.0.1"
              :dao.stream.datagram/bind-port 41234}]
    (is (datagram/descriptor? base))
    (is (not (datagram/descriptor? (assoc base :dao.stream.datagram/bind-port 0)))
        "the port actually bound, never 0")
    (is (not (datagram/descriptor? (assoc base :dao.stream.datagram/bind-host
                                          "localhost"))))
    (is (not (datagram/descriptor? (assoc base :dao.stream/type :dao.stream/udp))))))


;; =============================================================================
;; The writer handle (section 4)
;; =============================================================================


(defn- writer-fixture
  [& {:keys [max-bytes]}]
  (let [deposit (ring 64)
        {:keys [seam sends]} (scripted-seam deposit :identity "w")]
    {:deposit deposit
     :sends sends
     :writer (compose-bound-writer deposit seam :identity "w"
                                   :max-bytes max-bytes)}))


(deftest writer-hands-the-decoded-datagram-to-the-socket
  (let [{:keys [sends writer]} (writer-fixture)
        r (stream/append! writer (outbound "203.0.113.7" 4100 [1 2 3 250]))]
    (is (= {:dao.stream/outcome :dao.stream/ok} r)
        "ok: handed to the socket, asserting nothing about the wire")
    (is (= 1 (count @sends)) "exactly one host datagram")
    (let [[host port bytes] (first @sends)]
      (is (= "203.0.113.7" host))
      (is (= 4100 port))
      (is (= [1 2 3 250] (octets-of bytes))
          "the Base64 text rode the value, the bytes rode the wire"))))


(deftest writer-sends-a-zero-length-datagram-as-the-empty-string
  (let [{:keys [sends writer]} (writer-fixture)]
    (is (= {:dao.stream/outcome :dao.stream/ok}
           (stream/append! writer (assoc (destination "203.0.113.7" 4100)
                                         :dao.stream.datagram/bytes ""))))
    (is (= 1 (count @sends)))
    (is (= 0 (count (octets-of (nth (first @sends) 2)))))))


(deftest writer-refuses-invalid-values-without-sending
  (let [{:keys [sends writer]} (writer-fixture)
        cases {"hostname destination"
               (outbound "example.invalid" 4100 [1 2 3])

               "bad Base64 bytes"
               (assoc (destination "203.0.113.7" 4100)
                      :dao.stream.datagram/bytes "not base64!")

               "unpadded Base64"
               (assoc (destination "203.0.113.7" 4100)
                      :dao.stream.datagram/bytes "Zm9vYmF")

               "URL-safe alphabet"
               (assoc (destination "203.0.113.7" 4100)
                      :dao.stream.datagram/bytes "-_8=")

               "port 0"
               (outbound "203.0.113.7" 0 [1 2 3])

               "port over 65535"
               (outbound "203.0.113.7" 65536 [1 2 3])

               "non-integer port"
               (outbound "203.0.113.7" 4.5 [1 2 3])

               "destination not a map"
               {:dao.stream.datagram/destination "203.0.113.7:4100"
                :dao.stream.datagram/bytes "AAAA"}

               "missing destination"
               {:dao.stream.datagram/bytes "AAAA"}

               "missing bytes"
               (destination "203.0.113.7" 4100)

               "not a map"
               [:dao.stream.datagram/destination {}]

               "bytes not text"
               (assoc (destination "203.0.113.7" 4100)
                      :dao.stream.datagram/bytes :keyword)}]
    (doseq [[label v] cases]
      (let [r (stream/append! writer v)]
        (is (= :dao.stream/invalid-value (:dao.stream/outcome r)) label)
        (is (string? (:dao.stream.datagram/reason r))
            (str label ": the refusal names why"))))
    (is (= [] @sends) "nothing was sent for any of them")))


(deftest writer-refuses-decoded-length-over-max-bytes
  (let [{:keys [sends writer]} (writer-fixture :max-bytes 8)]
    (is (= :dao.stream/invalid-value
           (:dao.stream/outcome
             (stream/append! writer (outbound "203.0.113.7" 4100 (range 12))))))
    (is (= [] @sends))
    (testing "the bound is on the decoded length, not the Base64 text
              length: eight bytes ride twelve characters of text"
      (is (= {:dao.stream/outcome :dao.stream/ok}
             (stream/append! writer (outbound "203.0.113.7" 4100 (range 8))))))))


(deftest closed-socket-refuses-and-close-is-idempotent
  (let [{:keys [deposit writer]} (writer-fixture)]
    (is (= {:dao.stream/outcome :dao.stream/ok} (stream/close! writer)))
    (is (= {:dao.stream/outcome :dao.stream/ok} (stream/close! writer))
        "close! is idempotent")
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (stream/append! writer (outbound "203.0.113.7" 4100 [1 2 3])))
        "the socket was closed, nothing was sent")
    (testing "descriptor answers ok with both projections, after close"
      (is (stream/descriptor-identity-consistent? (stream/descriptor writer)))
      (is (= :dao.stream/datagram
             (:dao.stream/type
               (:dao.stream/descriptor (stream/descriptor writer))))))
    (is (some #(= :dao.stream.datagram/closed (:dao.stream.datagram/event %))
              (values deposit))
        "the seam deposited the closed event when it still could")))


(deftest writer-declares-exactly-writer-and-closable
  (let [{:keys [writer]} (writer-fixture)]
    (is (= #{:writer :closable} (stream/declared-surfaces writer)))))


(deftest writer-clamps-an-excluded-seam-outcome
  (let [seam {:send! (fn [_ _ _] {:dao.stream/outcome :dao.stream/full})
              :close! (fn [] nil)}
        writer (datagram/writer seam
                                {:dao.stream/type :dao.stream/datagram
                                 :dao.stream/identity "clamp"
                                 :dao.stream.datagram/bind-host "127.0.0.1"
                                 :dao.stream.datagram/bind-port 4100})]
    (is (= :dao.stream/transport-error
           (:dao.stream/outcome
             (stream/append! writer (outbound "127.0.0.1" 4101 [1])))))))


(deftest writer-answers-synchronous-send-failure-cleanly
  (let [deposit (ring 64)
        {:keys [seam fail]} (scripted-seam deposit :identity "w")
        writer (compose-bound-writer deposit seam :identity "w")]
    (reset! fail 1)
    (let [r (stream/append! writer (outbound "203.0.113.7" 4100 [1 2 3]))]
      (is (= :dao.stream/transport-error (:dao.stream/outcome r))
          "the host send failed synchronously, cleanly")
      (is (string? (:dao.stream.datagram/reason r)))
      (is (some #(= :dao.stream.datagram/send-failed
                    (:dao.stream.datagram/event %))
                (values deposit))
          "the failure is also a lifecycle event for the traffic stream's
           other observers: no function was invoked, the deposit writer is
           the seam's only channel"))))


;; =============================================================================
;; The attacher (section 3): composition-kept sockets only
;; =============================================================================


(deftest attach-resolves-only-what-the-composition-kept
  (let [{:keys [writer]} (writer-fixture)
        d (:dao.stream/descriptor (stream/descriptor writer))
        attach! (datagram/make-attacher (fn [id]
                                          (when (= "w" id) writer)))]
    (is (= :dao.stream/ok (:dao.stream/outcome (attach! d))))
    (is (identical? writer (:dao.stream/handle (attach! d))))
    (is (= :dao.stream/not-found
           (:dao.stream/outcome
             (attach! (assoc d :dao.stream/identity "never-kept")))))
    (is (= :dao.stream/invalid-descriptor
           (:dao.stream/outcome (attach! {:dao.stream/type :dao.stream/ws}))))
    (is (= :dao.stream/not-found
           (:dao.stream/outcome (stream/host-dispatch-attach! {} d)))
        "no host dispatch entry: this transport creates no stream and
         resolves nothing ambiently")))
