(ns dao.stream.ws.rfc6455
  "The pure RFC 6455 framing core for hosts that own their sockets
   (docs/design/yin.repl-jolt-implementation.md section 9).

   No I/O, no clock, no entropy: bytes in, values out.  A parser is a value;
   `feed` takes it with one received chunk and answers `[parser' events]`.
   The chunk is never mutated and never retained: every payload piece is
   copied (and unmasked) into fresh storage.  Chunks may split anywhere.

   Events (maps with `:type`):
   * `:message` -- `:opcode` `:text`|`:binary`, `:payload` bytes (a text
     payload is valid UTF-8, validated incrementally as it arrives);
   * `:ping`, `:pong` -- `:payload` bytes;
   * `:close` -- `:code` (nil for an empty close payload) and `:reason`;
   * `:fail` -- `:code` (1002 protocol error, 1007 invalid UTF-8, 1009
     too big) and `:reason`; the parser is then terminal and ignores input.

   The first invalid byte decides: a frame that breaks a rule fails at the
   chunk holding the first offending byte, not at the end of the message.
   A declared length is checked against the message bound before any
   storage for it exists.

   Roles follow RFC 6455 section 5.1: a `:server` parser requires masked
   frames, a `:client` parser refuses masked frames.  The profile is the
   no-extension profile: a set RSV bit is a protocol error."
  (:require [dao.host.bytes :as bytes]
            [dao.host.text :as text]))


(def absolute-ceiling
  "The largest message a host byte array can be asked to hold here, 2^31-1;
   the bound when the caller supplies none."
  2147483647)


(def opcodes
  {:continuation 0 :text 1 :binary 2 :close 8 :ping 9 :pong 10})


(defn accepted-close-code?
  "The jolt host row of docs/design/dao.stream.ws.md: an inbound close code
   is accepted in 1000-1003, 1007-1014 and 3000-4999; anything else is
   answered with a 1002 close."
  [code]
  (and (integer? code)
       (or (<= 1000 code 1003)
           (<= 1007 code 1014)
           (<= 3000 code 4999))))


;; =============================================================================
;; Incremental UTF-8 validation (RFC 3629, strict)
;; =============================================================================

(def utf8-initial
  "A validator state at a character boundary: `[remaining lo hi]`, the count
   of continuation bytes still owed and the range of the next one."
  [0 0x80 0xBF])


(defn utf8-step
  "The state after byte `b`, or nil when `b` is invalid there."
  [state b]
  (let [[remaining lo hi] state]
    (if (zero? remaining)
      (cond
        (< b 0x80) state
        (<= 0xC2 b 0xDF) [1 0x80 0xBF]
        (= b 0xE0) [2 0xA0 0xBF]
        (= b 0xED) [2 0x80 0x9F]
        (<= 0xE1 b 0xEF) [2 0x80 0xBF]
        (= b 0xF0) [3 0x90 0xBF]
        (<= 0xF1 b 0xF3) [3 0x80 0xBF]
        (= b 0xF4) [3 0x80 0x8F]
        :else nil)
      (when (<= lo b hi)
        [(dec remaining) 0x80 0xBF]))))


(defn utf8-feed
  "The state after all bytes of `bs`, or nil at the first invalid byte."
  [state bs]
  (let [n (bytes/length bs)]
    (loop [i 0 st state]
      (cond
        (nil? st) nil
        (>= i n) st
        :else (recur (inc i) (utf8-step st (bytes/get bs i)))))))


(defn utf8-complete?
  "True when `state` ends on a character boundary."
  [state]
  (and (some? state) (zero? (first state))))


;; =============================================================================
;; Mask, serialize
;; =============================================================================

(defn unmask
  "Fresh bytes: `bs` XOR the four-byte `key`, the key rotated by `offset`
   (the payload position of `bs`'s first byte).  Masking is the same
   operation.  `bs` is left unchanged."
  [bs key offset]
  (let [n (bytes/length bs)
        out (bytes/alloc n)
        k (bytes/to-vector key)]
    (loop [i 0]
      (when (< i n)
        (bytes/set! out i (bit-xor (bytes/get bs i)
                                   (nth k (mod (+ offset i) 4))))
        (recur (inc i))))
    out))


(defn- opcode-int
  [opcode]
  (if (keyword? opcode) (get opcodes opcode) opcode))


(defn- length-bytes
  [n]
  (cond
    (< n 126) [n]
    (< n 65536) [126 (quot n 256) (mod n 256)]
    :else (let [hi (quot n 4294967296)
                lo (mod n 4294967296)]
            (into [127]
                  (concat (map #(mod (quot hi %) 256) [16777216 65536 256 1])
                          (map #(mod (quot lo %) 256) [16777216 65536 256 1]))))))


(defn encode-frame
  "The wire bytes of one frame: `{:opcode :payload}` plus optional `:fin?`
   (default true) and `:mask-key` (four bytes; present for client frames).
   Header and masked payload are written into one fresh array; `:payload`
   is left unchanged.  A control frame over 125 bytes or not final throws."
  [{:keys [opcode payload mask-key fin?] :or {fin? true}}]
  (let [op (opcode-int opcode)
        n (bytes/length payload)]
    (when (and (>= op 8) (or (> n 125) (not fin?)))
      (throw (ex-info "control frames are final and at most 125 bytes"
                      {:opcode op :length n})))
    (let [lenb (length-bytes n)
          mask? (some? mask-key)
          head (into [(bit-or (if fin? 0x80 0) op)
                      (bit-or (if mask? 0x80 0) (first lenb))]
                     (concat (rest lenb) (when mask? (bytes/to-vector mask-key))))
          hn (count head)
          out (bytes/alloc (+ hn n))]
      (loop [i 0]
        (when (< i hn)
          (bytes/set! out i (nth head i))
          (recur (inc i))))
      (if mask?
        (bytes/copy! (unmask payload mask-key 0) 0 out hn n)
        (bytes/copy! payload 0 out hn n))
      out)))


(defn close-payload
  "The payload of a close frame: the two-byte `code` then the UTF-8 `reason`
   (nil `code` is an empty payload).  A reason over 123 bytes throws, and so
   do 1005 and 1006, which are reported locally and never put on the wire."
  [code reason]
  (when (contains? #{1005 1006} code)
    (throw (ex-info "close codes 1005 and 1006 are never serialized" {:code code})))
  (if (nil? code)
    (bytes/alloc 0)
    (let [r (text/utf8-encode (or reason ""))
          rn (bytes/length r)]
      (when (> rn 123)
        (throw (ex-info "close reason over 123 bytes" {:length rn})))
      (let [out (bytes/alloc (+ 2 rn))]
        (bytes/set! out 0 (quot code 256))
        (bytes/set! out 1 (mod code 256))
        (bytes/copy! r 0 out 2 rn)
        out))))


;; =============================================================================
;; Parser
;; =============================================================================

(defn parser
  "A fresh parser.  `:role` is `:server` or `:client` (whose frames it
   parses are the peer's); `:max-message-bytes` bounds an assembled message
   (default `absolute-ceiling`)."
  [{:keys [role max-message-bytes]}]
  {:role role
   :max (min absolute-ceiling (or max-message-bytes absolute-ceiling))
   :phase :header
   :hdr []
   :frame nil
   :msg nil})


(defn- fail
  [state code reason]
  [(assoc state :phase :done)
   [{:type :fail :code code :reason reason}]])


(defn- header-length
  "Total header bytes the first two bytes `b0 b1` promise."
  [b1]
  (+ 2
     (case (bit-and b1 127) 126 2 127 8 0)
     (if (pos? (bit-and b1 128)) 4 0)))


(defn- check-first-two
  "A failure `[code reason]` decidable from the first two header bytes."
  [state b0 b1]
  (let [opcode (bit-and b0 15)
        control? (>= opcode 8)
        masked? (pos? (bit-and b1 128))]
    (cond
      (pos? (bit-and b0 0x70)) [1002 "reserved bits set"]
      (or (<= 3 opcode 7) (> opcode 10)) [1002 "reserved opcode"]
      (and control? (zero? (bit-and b0 0x80))) [1002 "fragmented control frame"]
      (and control? (> (bit-and b1 127) 125)) [1002 "oversized control frame"]
      (and (zero? opcode) (nil? (:msg state))) [1002 "continuation without a start"]
      (and (#{1 2} opcode) (some? (:msg state))) [1002 "data frame inside a fragmented message"]
      (and (= :server (:role state)) (not masked?)) [1002 "unmasked client frame"]
      (and (= :client (:role state)) masked?) [1002 "masked server frame"]
      :else nil)))


(defn- payload-length
  "The declared payload length of a complete header `hdr`, or `[code reason]`
   when it is not minimal or sets the top bit."
  [hdr]
  (let [l7 (bit-and (nth hdr 1) 127)]
    (cond
      (< l7 126) l7
      (= l7 126) (let [n (+ (* 256 (nth hdr 2)) (nth hdr 3))]
                   (if (< n 126) [1002 "non-minimal length"] n))
      :else (let [hi (+ (* 16777216 (nth hdr 2)) (* 65536 (nth hdr 3))
                        (* 256 (nth hdr 4)) (nth hdr 5))
                  lo (+ (* 16777216 (nth hdr 6)) (* 65536 (nth hdr 7))
                        (* 256 (nth hdr 8)) (nth hdr 9))]
              (cond
                (>= hi 2147483648) [1002 "length with the top bit set"]
                :else (let [n (+ (* hi 4294967296) lo)]
                        (if (< n 65536) [1002 "non-minimal length"] n)))))))


(defn- concat-chunks
  [chunks total]
  (let [out (bytes/alloc total)]
    (loop [cs (seq chunks) at 0]
      (when cs
        (let [c (first cs)
              cn (bytes/length c)]
          (bytes/copy! c 0 out at cn)
          (recur (next cs) (+ at cn)))))
    out))


(defn- begin-frame
  "State after a complete header, or a failure."
  [state hdr]
  (let [b0 (nth hdr 0)
        b1 (nth hdr 1)
        len (payload-length hdr)]
    (if (vector? len)
      (fail state (first len) (second len))
      (let [opcode (bit-and b0 15)
            control? (>= opcode 8)
            masked? (pos? (bit-and b1 128))
            key (when masked? (bytes/from-vector (subvec hdr (- (count hdr) 4))))
            size (+ len (if control? 0 (:size (:msg state) 0)))]
        (if (and (not control?) (> size (:max state)))
          (fail state 1009 "message too big")
          (let [msg (cond
                      control? (:msg state)
                      (zero? opcode) (:msg state)
                      :else {:opcode (if (= opcode 1) :text :binary)
                             :chunks [] :size 0 :utf8 utf8-initial})]
            [(assoc state
                    :phase :payload
                    :hdr []
                    :msg msg
                    :frame {:fin? (pos? (bit-and b0 0x80)) :opcode opcode
                            :key key :len len :got 0 :chunks []})
             []]))))))


(defn- parse-close
  "Classify a close payload.  Absent (length 0) is a valid close with no
   status: `:absent? true`, `:code nil`.  Invalid -- length 1, a code outside
   the accepted set, or a reason that is not UTF-8 -- is a 1002 failure
   tagged `:close? true`, so the caller can tell it from a framing failure."
  [payload]
  (let [n (bytes/length payload)
        invalid (fn [reason] {:type :fail :close? true :code 1002 :reason reason})]
    (cond
      (zero? n) {:type :close :absent? true :code nil :reason ""}
      (= n 1) (invalid "close payload of one byte")
      :else
      (let [code (+ (* 256 (bytes/get payload 0)) (bytes/get payload 1))]
        (cond
          (not (accepted-close-code? code)) (invalid "close code not accepted")
          (not (utf8-complete? (utf8-feed utf8-initial (bytes/slice payload 2 n))))
          (invalid "close reason is not UTF-8")
          :else {:type :close :code code
                 :reason (text/utf8-decode payload 2 (- n 2))})))))


(defn- finish-frame
  [state]
  (let [{:keys [fin? opcode chunks len]} (:frame state)
        base (assoc state :phase :header :frame nil)]
    (if (>= opcode 8)
      (let [payload (concat-chunks chunks len)]
        (case opcode
          9 [base [{:type :ping :payload payload}]]
          10 [base [{:type :pong :payload payload}]]
          [(assoc base :phase :done) [(parse-close payload)]]))
      (if-not fin?
        [base []]
        (let [msg (:msg state)]
          (if (and (= :text (:opcode msg)) (not (utf8-complete? (:utf8 msg))))
            [(assoc base :phase :done)
             [{:type :fail :code 1007 :reason "text message ends inside a character"}]]
            [(assoc base :msg nil)
             [{:type :message :opcode (:opcode msg)
               :payload (concat-chunks (:chunks msg) (:size msg))}]]))))))


(defn- take-payload
  "Consume `take` bytes of `chunk` from `i` into the current frame."
  [state chunk i take]
  (let [{:keys [key got opcode]} (:frame state)
        piece (let [s (bytes/slice chunk i (+ i take))]
                (if key (unmask s key got) s))
        state (-> state
                  (update-in [:frame :got] + take)
                  (update-in [:frame :chunks] conj piece))]
    (if (>= opcode 8)
      [state nil]
      (let [msg (:msg state)
            utf8 (if (= :text (:opcode msg))
                   (utf8-feed (:utf8 msg) piece)
                   (:utf8 msg))]
        (if (nil? utf8)
          [state [1007 "invalid UTF-8 in a text message"]]
          [(-> state
               (update-in [:msg :chunks] conj piece)
               (update-in [:msg :size] + take)
               (assoc-in [:msg :utf8] utf8)
               (update-in [:frame :chunks] (constantly [])))
           nil])))))


(defn parse-frame
  "Resume parsing `chunk` at `offset` until one event is produced or the
   chunk is exhausted: `[event-or-nil parser' offset']`.  `offset'` is the
   first byte not consumed, so a caller that stops after an event keeps the
   rest of its chunk by offset and resumes later with the same chunk and
   `offset'`; nothing is copied or re-read.  With a nil event `offset'` is the
   chunk length and any partial frame is held in `parser'`.  An event is a
   completed control frame, a completed message (non-final data frames
   produce none), or a failure; a done parser answers `[nil parser offset]`.
   The chunk is never mutated."
  [state chunk offset]
  (let [n (bytes/length chunk)]
    (loop [st state i offset]
      (cond
        (= :done (:phase st)) [nil st i]
        (>= i n) [nil st n]

        (= :header (:phase st))
        (let [hdr (conj (:hdr st) (bytes/get chunk i))
              c (count hdr)
              st (assoc st :hdr hdr)
              early (when (= c 2) (check-first-two st (nth hdr 0) (nth hdr 1)))]
          (cond
            early (let [[s es] (fail st (first early) (second early))]
                    [(first es) s (inc i)])
            (and (>= c 2) (= c (header-length (nth hdr 1))))
            (let [[st' es] (begin-frame st hdr)]
              (cond
                (seq es) [(first es) st' (inc i)]
                (and (= :payload (:phase st')) (zero? (:len (:frame st'))))
                (let [[s es2] (finish-frame st')]
                  (if (seq es2)
                    [(first es2) s (inc i)]
                    (recur s (inc i))))
                (= :done (:phase st'))
                [nil st' (inc i)]
                :else (recur st' (inc i))))
            :else (recur st (inc i))))

        :else
        (let [{:keys [len got]} (:frame st)
              take (min (- len got) (- n i))
              [st' failure] (take-payload st chunk i take)]
          (cond
            failure (let [[s es] (fail st' (first failure) (second failure))]
                      [(first es) s (+ i take)])
            (= (+ got take) len)
            (let [[s es] (finish-frame st')]
              (if (seq es)
                [(first es) s (+ i take)]
                (recur s (+ i take))))
            :else (recur st' (+ i take))))))))


(defn feed
  "Feed one received `chunk`; `[parser' events]`: `parse-frame` to
   exhaustion."
  [state chunk]
  (loop [st state offset 0 events []]
    (let [[event st' offset'] (parse-frame st chunk offset)]
      (if event
        (recur st' offset' (conj events event))
        [st' events]))))
