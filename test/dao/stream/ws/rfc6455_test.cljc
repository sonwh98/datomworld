(ns dao.stream.ws.rfc6455-test
  "The pure RFC 6455 framing core: section 5.7 known-answer frames, length
   rules, bounds before allocation, chunk immutability, the control-frame
   and fragmentation rules, incremental UTF-8 failing at the first invalid
   chunk at every split point, and the close-code policy."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.host.bytes :as bytes]
            [dao.stream.ws.rfc6455 :as ws]))


(defn- bv
  [& xs]
  (bytes/from-vector (vec xs)))


(defn- bvec
  [xs]
  (bytes/from-vector (vec xs)))


(defn- throws?
  [f]
  (try
    (f)
    false
    (catch #?(:cljd Object :clj Exception :cljs :default) _ true)))


(defn- feed-all
  "Feed `chunks` (byte arrays) to a fresh parser; `{:events :parser}`."
  [opts chunks]
  (loop [p (ws/parser opts) cs (seq chunks) events []]
    (if-not cs
      {:events events :parser p}
      (let [[p' es] (ws/feed p (first cs))]
        (recur p' (next cs) (into events es))))))


(defn- types
  [events]
  (mapv :type events))


(defn- only-fail
  [events]
  (let [f (filter #(= :fail (:type %)) events)]
    (when (seq f) (select-keys (first f) [:code]))))


(defn- split-at-k
  [bs k]
  [(bytes/slice bs 0 k) (bytes/slice bs k (bytes/length bs))])


(def ^:private hello [0x48 0x65 0x6c 0x6c 0x6f])


(defn- payload-of
  [events]
  (bytes/to-vector (:payload (first events))))


(deftest rfc-5-7-known-answers
  (testing "a single-frame unmasked text message"
    (let [{:keys [events]} (feed-all {:role :client}
                                     [(bvec (concat [0x81 0x05] hello))])]
      (is (= [:message] (types events)))
      (is (= :text (:opcode (first events))))
      (is (= hello (payload-of events)))))
  (testing "a single-frame masked text message"
    (let [wire (bvec (concat [0x81 0x85 0x37 0xfa 0x21 0x3d] [0x7f 0x9f 0x4d 0x51 0x58]))
          {:keys [events]} (feed-all {:role :server} [wire])]
      (is (= hello (payload-of events)))
      (is (= (bytes/to-vector wire)
             (bytes/to-vector
               (ws/encode-frame {:opcode :text :payload (bvec hello)
                                 :mask-key (bv 0x37 0xfa 0x21 0x3d)}))))))
  (testing "a fragmented unmasked text message"
    (let [{:keys [events]} (feed-all {:role :client}
                                     [(bv 0x01 0x03 0x48 0x65 0x6c)
                                      (bv 0x80 0x02 0x6c 0x6f)])]
      (is (= [:message] (types events)))
      (is (= hello (payload-of events)))))
  (testing "ping and masked pong"
    (let [{:keys [events]} (feed-all {:role :client}
                                     [(bvec (concat [0x89 0x05] hello))])]
      (is (= [:ping] (types events)))
      (is (= hello (payload-of events))))
    (let [{:keys [events]} (feed-all {:role :server}
                                     [(bvec (concat [0x8a 0x85 0x37 0xfa 0x21 0x3d]
                                                    [0x7f 0x9f 0x4d 0x51 0x58]))])]
      (is (= [:pong] (types events)))
      (is (= hello (payload-of events)))))
  (testing "256 and 65536 byte binary messages use 16- and 64-bit lengths"
    (let [p256 (bvec (repeat 256 7))
          wire (ws/encode-frame {:opcode :binary :payload p256})]
      (is (= [0x82 0x7e 0x01 0x00] (take 4 (bytes/to-vector wire))))
      (is (= 260 (bytes/length wire)))
      (is (= 256 (bytes/length (:payload (first (:events (feed-all {:role :client} [wire]))))))))
    (let [p64k (bvec (repeat 65536 9))
          wire (ws/encode-frame {:opcode :binary :payload p64k})]
      (is (= [0x82 0x7f 0 0 0 0 0 1 0 0] (take 10 (bytes/to-vector wire))))
      (is (= 65536 (bytes/length (:payload (first (:events (feed-all {:role :client} [wire]))))))))))


(deftest every-split-point-agrees
  (let [wire (ws/encode-frame {:opcode :text :payload (bvec hello)
                               :mask-key (bv 1 2 3 4)})]
    (doseq [k (range 0 (inc (bytes/length wire)))]
      (let [{:keys [events]} (feed-all {:role :server} (split-at-k wire k))]
        (is (= 1 (count events)) (str "split " k))
        (is (= hello (payload-of events)))))
    (let [{:keys [events]} (feed-all {:role :server}
                                     (map #(bytes/slice wire % (inc %))
                                          (range (bytes/length wire))))]
      (is (= [:message] (types events))))))


(deftest length-rules
  (testing "non-minimal lengths are refused"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client}
                                         [(bv 0x81 0x7e 0x00 0x7d)])))))
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client}
                                         [(bv 0x82 0x7f 0 0 0 0 0 0 0xff 0xff)]))))))
  (testing "a length with the top bit set is refused"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client}
                                         [(bv 0x82 0x7f 0x80 0 0 0 0 0 0 0)]))))))
  (testing "a 2^40 length is refused before any payload exists"
    (let [{:keys [events]} (feed-all {:role :client :max-message-bytes 1048576}
                                     [(bv 0x82 0x7f 0 0 0x01 0 0 0 0 0)])]
      (is (= {:code 1009} (only-fail events)))))
  (testing "a length over the default ceiling is refused"
    (is (= {:code 1009}
           (only-fail (:events (feed-all {:role :client}
                                         [(bv 0x82 0x7f 0 0 0 0x01 0 0 0 0)])))))))


(deftest chunk-bytes-are-unchanged
  (let [wire (ws/encode-frame {:opcode :text :payload (bvec hello)
                               :mask-key (bv 9 8 7 6)})
        before (bytes/to-vector wire)]
    (feed-all {:role :server} [wire])
    (is (= before (bytes/to-vector wire))))
  (let [payload (bvec hello)
        key (bv 1 2 3 4)
        before (bytes/to-vector payload)
        masked (ws/unmask payload key 0)]
    (is (= before (bytes/to-vector payload)))
    (is (not= before (bytes/to-vector masked)))
    (is (= before (bytes/to-vector (ws/unmask masked key 0))))
    (testing "the key rotates with the payload offset"
      (is (= (bytes/to-vector (ws/unmask (bvec (drop 2 hello)) key 2))
             (drop 2 (bytes/to-vector (ws/unmask payload key 0))))))))


(deftest protocol-violations
  (testing "RSV bits"
    (doseq [b0 [0xc1 0xa1 0x91]]
      (is (= {:code 1002} (only-fail (:events (feed-all {:role :client} [(bv b0 0)])))))))
  (testing "reserved opcodes"
    (doseq [op [3 4 5 6 7 11 12 13 14 15]]
      (is (= {:code 1002}
             (only-fail (:events (feed-all {:role :client} [(bv (bit-or 0x80 op) 0)])))))))
  (testing "oversized control frame, even before its payload"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client} [(bv 0x89 0x7e)]))))))
  (testing "fragmented control frame"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client} [(bv 0x09 0)]))))))
  (testing "continuation without a start"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client} [(bv 0x80 0)]))))))
  (testing "a data frame inside a fragmented message"
    (is (= {:code 1002}
           (only-fail (:events (feed-all {:role :client}
                                         [(bv 0x01 0x01 0x61 0x01 0x01 0x62)]))))))
  (testing "mask requirements by role"
    (is (= {:code 1002} (only-fail (:events (feed-all {:role :server} [(bv 0x81 0x00)])))))
    (is (= {:code 1002} (only-fail (:events (feed-all {:role :client} [(bv 0x81 0x80)]))))))
  (testing "a failed parser ignores later input"
    (let [{:keys [parser]} (feed-all {:role :client} [(bv 0xc1 0)])
          [_ events] (ws/feed parser (bvec (concat [0x81 0x05] hello)))]
      (is (empty? events)))))


(deftest control-frames-interleave
  (let [{:keys [events]} (feed-all {:role :client}
                                   [(bv 0x01 0x01 0x61)
                                    (bv 0x89 0x00)
                                    (bv 0x80 0x01 0x62)])]
    (is (= [:ping :message] (types events)))
    (is (= [0x61 0x62] (bytes/to-vector (:payload (second events)))))))


(deftest message-bound
  (testing "assembly refuses at the bound across fragments"
    (let [{:keys [events]} (feed-all {:role :client :max-message-bytes 4}
                                     [(bv 0x02 0x03 1 2 3) (bv 0x80 0x02 4 5)])]
      (is (= {:code 1009} (only-fail events)))))
  (testing "exactly the bound passes"
    (let [{:keys [events]} (feed-all {:role :client :max-message-bytes 5}
                                     [(bv 0x02 0x03 1 2 3) (bv 0x80 0x02 4 5)])]
      (is (= [:message] (types events))))))


(deftest utf8-validator
  (let [valid? #(ws/utf8-complete? (ws/utf8-feed ws/utf8-initial (bvec %)))]
    (is (valid? [0x41 0xc3 0xa9 0xe2 0x82 0xac 0xf0 0x9f 0x98 0x80]))
    (is (valid? [0xf4 0x8f 0xbf 0xbf]))
    (is (not (valid? [0xc0 0x80])))
    (is (not (valid? [0xed 0xa0 0x80])))
    (is (not (valid? [0xf4 0x90 0x80 0x80])))
    (is (not (valid? [0xe2 0x82])) "ends inside a character")
    (is (nil? (ws/utf8-feed ws/utf8-initial (bv 0x80))))
    (testing "state carries across chunks"
      (let [s (ws/utf8-feed ws/utf8-initial (bv 0xe2 0x82))]
        (is (not (ws/utf8-complete? s)))
        (is (ws/utf8-complete? (ws/utf8-feed s (bv 0xac))))))))


(deftest invalid-utf8-fails-at-the-first-invalid-chunk
  (let [prefix [0xce 0xba 0xe1 0xbd 0xb9 0xcf 0x83 0xce 0xbc 0xce 0xb5]
        payload (concat prefix [0xf4 0x90 0x80 0x80])
        ;; the first invalid byte is the 0x90 after 0xf4; wire index
        ;; = 2 header bytes + prefix + 1
        invalid-at (+ 2 (count prefix) 1)
        wire (ws/encode-frame {:opcode :text :payload (bvec payload)})]
    (doseq [k (range 1 (bytes/length wire))]
      (let [[a b] (split-at-k wire k)
            [p1 e1] (ws/feed (ws/parser {:role :client}) a)
            [_ e2] (ws/feed p1 b)]
        (is (= (> k invalid-at) (boolean (only-fail e1)))
            (str "split " k ": fails in the first chunk iff it holds the bad byte"))
        (is (= {:code 1007} (only-fail (into e1 e2))))))
    (testing "one byte at a time stops at the bad byte"
      (loop [p (ws/parser {:role :client}) i 0]
        (let [[p' es] (ws/feed p (bytes/slice wire i (inc i)))]
          (if (seq es)
            (is (= invalid-at i))
            (recur p' (inc i))))))
    (testing "fragmented text fails in the fragment holding the bad byte"
      (let [{:keys [events]} (feed-all {:role :client}
                                       [(bv 0x01 0x02 0xce 0xba)
                                        (bv 0x00 0x02 0xf4 0x90)
                                        (bv 0x80 0x00)])]
        (is (= {:code 1007} (only-fail events)))))
    (testing "text ending inside a character fails at the final frame"
      (is (= {:code 1007}
             (only-fail (:events (feed-all {:role :client} [(bv 0x81 0x02 0xe2 0x82)]))))))
    (testing "binary frames are not validated"
      (is (= [:message]
             (types (:events (feed-all {:role :client} [(bv 0x82 0x02 0xff 0xfe)]))))))))


(deftest close-frames
  (let [close (fn [code reason]
                (:events (feed-all {:role :client}
                                   [(ws/encode-frame
                                      {:opcode :close
                                       :payload (ws/close-payload code reason)})])))]
    (testing "accepted codes"
      (doseq [c [1000 1001 1002 1003 1007 1008 1009 1010 1011 1012 1013 1014 3000 4999]]
        (is (ws/accepted-close-code? c))
        (is (= [:close] (types (close c "bye"))))
        (is (= c (:code (first (close c "bye")))))))
    (testing "refused codes answer 1002, tagged as close failures"
      (doseq [c [0 999 1004 1005 1006 1015 2999 5000 65535]]
        (is (not (ws/accepted-close-code? c)))
        (let [events (:events (feed-all {:role :client}
                                        [(bv 0x88 0x02 (quot c 256) (mod c 256))]))]
          (is (= {:code 1002} (only-fail events)) c)
          (is (true? (:close? (first events))) c))))
    (testing "an empty payload is a valid close with an absent status"
      (let [e (first (close nil nil))]
        (is (= :close (:type e)))
        (is (true? (:absent? e)))
        (is (nil? (:code e)))
        (is (not (:close? e))))
      (is (not (:absent? (first (close 1000 ""))))))
    (testing "a one-byte payload is a tagged 1002"
      (let [events (:events (feed-all {:role :client} [(bv 0x88 0x01 0x03)]))]
        (is (= {:code 1002} (only-fail events)))
        (is (true? (:close? (first events))))))
    (testing "an invalid UTF-8 reason is a tagged 1002"
      (let [events (:events (feed-all {:role :client}
                                      [(bv 0x88 0x04 0x03 0xe8 0xff 0xfe)]))]
        (is (= {:code 1002} (only-fail events)))
        (is (true? (:close? (first events))))))
    (testing "a valid code with a valid multi-byte reason is a close"
      (let [e (first (close 1000 "h\u00e9"))]
        (is (= :close (:type e)))
        (is (= "h\u00e9" (:reason e)))))
    (testing "a framing failure is not tagged as a close failure"
      (is (not (:close? (first (:events (feed-all {:role :client} [(bv 0xc1 0)])))))))
    (testing "the parser accepts nothing after a close"
      (let [{:keys [parser]} (feed-all {:role :client} [(bv 0x88 0x00)])
            [_ events] (ws/feed parser (bv 0x81 0x00))]
        (is (empty? events))))))


(deftest serializer
  (testing "control frames over 125 bytes and non-final control frames throw"
    (is (throws? #(ws/encode-frame {:opcode :ping :payload (bvec (repeat 126 0))})))
    (is (throws? #(ws/encode-frame {:opcode :ping :fin? false :payload (bv)}))))
  (testing "length encodings at the boundaries"
    (doseq [[n head] [[125 [0x82 125]] [126 [0x82 0x7e 0 126]]
                      [65535 [0x82 0x7e 0xff 0xff]]]]
      (let [wire (ws/encode-frame {:opcode :binary :payload (bvec (repeat n 1))})]
        (is (= head (take (count head) (bytes/to-vector wire))))
        (is (= n (bytes/length (:payload (first (:events (feed-all {:role :client} [wire]))))))))))
  (testing "the payload is not modified by masking"
    (let [payload (bvec hello)
          before (bytes/to-vector payload)]
      (ws/encode-frame {:opcode :text :payload payload :mask-key (bv 5 5 5 5)})
      (is (= before (bytes/to-vector payload)))))
  (testing "close payloads"
    (is (= [0x03 0xe8 0x62 0x79 0x65] (bytes/to-vector (ws/close-payload 1000 "bye"))))
    (is (= [] (bytes/to-vector (ws/close-payload nil nil))))
    (is (throws? #(ws/close-payload 1000 (apply str (repeat 124 "a")))))
    (testing "1005 and 1006 are never serialized"
      (is (throws? #(ws/close-payload 1005 "")))
      (is (throws? #(ws/close-payload 1006 "x")))
      (is (= [0x03 0xe9] (bytes/to-vector (ws/close-payload 1001 nil)))))))


(defn- ping-wire
  [i]
  (ws/encode-frame {:opcode :ping :payload (bv i i)}))


(defn- concat-bytes
  [parts]
  (bvec (mapcat bytes/to-vector parts)))


(defn- parse-by-frame
  "Parse `chunk` one frame per call; `[events offsets parser end-offset]`."
  [p chunk]
  (loop [p p offset 0 events [] offsets []]
    (let [[event p2 offset2] (ws/parse-frame p chunk offset)]
      (if event
        (recur p2 offset2 (conj events event) (conj offsets offset2))
        [events offsets p2 offset2]))))


(deftest parse-frame-resumes-one-frame-at-a-time
  (testing "ten pings in one chunk parse to ten frames with the right offsets"
    (let [chunk (concat-bytes (map ping-wire (range 10)))
          before (bytes/to-vector chunk)
          [events offsets _ end] (parse-by-frame (ws/parser {:role :client}) chunk)]
      (is (= (range 10) (map #(first (bytes/to-vector (:payload %))) events)))
      (is (every? #(= :ping (:type %)) events))
      (is (= (map #(* 4 (inc %)) (range 10)) offsets))
      (is (= 40 end))
      (is (= before (bytes/to-vector chunk)) "input bytes never mutated")))
  (testing "stopping after frame k and resuming gives the whole-chunk result"
    (let [chunk (concat-bytes (map ping-wire (range 10)))
          whole (:events (feed-all {:role :client} [chunk]))]
      (doseq [k (range 1 10)]
        (let [[head p2 offset]
              (loop [p (ws/parser {:role :client}) offset 0 got []]
                (if (= k (count got))
                  [got p offset]
                  (let [[e p2 o2] (ws/parse-frame p chunk offset)]
                    (recur p2 o2 (conj got e)))))
              [tail] (parse-by-frame p2 (bytes/slice chunk offset 40))]
          (is (= (* 4 k) offset) (str "stopped after " k))
          (is (= (mapv (comp bytes/to-vector :payload) whole)
                 (mapv (comp bytes/to-vector :payload) (into head tail)))
              (str "resumed after " k))))))
  (testing "the same chunk resumed by offset, not re-sliced"
    (let [chunk (concat-bytes (map ping-wire (range 3)))
          [e1 p1 o1] (ws/parse-frame (ws/parser {:role :client}) chunk 0)
          [e2 _ o2] (ws/parse-frame p1 chunk o1)]
      (is (= [0 0] (bytes/to-vector (:payload e1))))
      (is (= [1 1] (bytes/to-vector (:payload e2))))
      (is (= [4 8] [o1 o2]))))
  (testing "a ping split octet-wise parses to one ping"
    (let [wire (ping-wire 7)
          result (reduce (fn [{:keys [p events]} i]
                           (let [[e p2 o] (ws/parse-frame p (bytes/slice wire i (inc i)) 0)]
                             (is (= 1 o))
                             {:p p2 :events (if e (conj events e) events)}))
                         {:p (ws/parser {:role :client}) :events []}
                         (range (bytes/length wire)))]
      (is (= 1 (count (:events result))))
      (is (= [7 7] (bytes/to-vector (:payload (first (:events result))))))))
  (testing "an exhausted chunk answers nil and the end offset, holding the partial frame"
    (let [wire (ping-wire 5)
          [e p o] (ws/parse-frame (ws/parser {:role :client}) (bytes/slice wire 0 3) 0)]
      (is (nil? e))
      (is (= 3 o))
      (let [[e2 _ o2] (ws/parse-frame p wire 3)]
        (is (= :ping (:type e2)))
        (is (= 4 o2)))))
  (testing "a failure stops at the offending byte and later calls answer nil"
    (let [chunk (bvec [0x89 0x00 0xc1 0x00 0x89 0x00])
          [e1 p1 o1] (ws/parse-frame (ws/parser {:role :client}) chunk 0)
          [e2 p2 o2] (ws/parse-frame p1 chunk o1)
          [e3 _ o3] (ws/parse-frame p2 chunk o2)]
      (is (= :ping (:type e1)))
      (is (= :fail (:type e2)))
      (is (= 4 o2) "stops after the header byte that decides it")
      (is (nil? e3))
      (is (= 4 o3)))))
