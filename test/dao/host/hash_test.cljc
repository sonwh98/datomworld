(ns dao.host.hash-test
  "Table D tests for dao.host.hash (docs/design/dao.stream.host-boundary.md):
   NIST SHA-256 short-message test vectors, BLAKE3 vector conformance,
   and argument immutability / return freshness checks."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.host.bytes :as bytes]
    [dao.host.hash :as hash]
    [dao.host.sign :as sign]
    [dao.host.text :as text]
    [dao.jing :as jing]
    [yin.vm.linker.sign :as linker-sign]))


(defn- hex->bytes
  [s]
  (let [n (count s)
        out (bytes/alloc (quot n 2))]
    (dotimes [i (quot n 2)]
      (let [sub (subs s (* i 2) (+ (* i 2) 2))
            b #?(:cljd (int.parse sub :radix 16)
                 :clj (Integer/parseInt sub 16)
                 :cljs (js/parseInt sub 16))]
        (bytes/set! out i b)))
    out))


(defn- bytes->hex
  [bs]
  (let [n (bytes/length bs)
        hex-digits "0123456789abcdef"
        sb #?(:cljd (StringBuffer.)
              :clj (StringBuilder.)
              :cljs (js/Array.))
        append! #?(:cljd (fn [^String s] (.write ^StringBuffer sb s))
                   :clj (fn [^String s] (.append ^StringBuilder sb s))
                   :cljs (fn [s] (.push sb s)))]
    (dotimes [i n]
      (let [b (bytes/get bs i)]
        (append! (str (nth hex-digits (quot b 16))
                      (nth hex-digits (rem b 16))))))
    #?(:cljd (.toString ^StringBuffer sb)
       :clj (.toString ^StringBuilder sb)
       :cljs (.join sb ""))))


(def nist-sha256-vectors
  "Standard NIST FIPS 180-4 and RFC 6234 test vectors for SHA-256."
  [;; Empty message
   [""
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
   ;; "abc"
   ["abc"
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"]
   ;; "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
   ["abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
    "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"]
   ;; 448 bits message: "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" (56 bytes)
   ;; 896 bits message (112 bytes)
   ["abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
    "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"]])


(deftest nist-sha256-test-vectors
  (testing "NIST FIPS 180-4 / RFC 6234 test vectors for SHA-256"
    (doseq [[msg-str expected-hex] nist-sha256-vectors]
      (let [msg-bytes (text/utf8-encode msg-str)
            digest (hash/sha256-bytes msg-bytes)]
        (is (= 32 (bytes/length digest)))
        (is (= expected-hex (bytes->hex digest)))))))


(def blake3-official-vectors
  "Subset of official BLAKE3 test vectors (empty, 3-byte 'abc', and 64-byte sequence)."
  [;; Empty input
   [""
    "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"]
   ;; "abc"
   ["abc"
    "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85"]])


(deftest blake3-test-vectors
  (testing "Official BLAKE3 test vectors"
    (doseq [[msg-str expected-hex] blake3-official-vectors]
      (let [msg-bytes (text/utf8-encode msg-str)
            digest (hash/blake3-bytes msg-bytes)]
        (is (= 32 (bytes/length digest)))
        (is (= expected-hex (bytes->hex digest)))))))


(deftest hash-refusals-and-immutability
  (testing "non-byte values are refused with :unsupported-value"
    (is (= :unsupported-value
           (try (hash/sha256-bytes "not-bytes")
                (catch #?(:cljd Object :clj Exception :cljs :default) e
                  (:dao.host/refusal (ex-data e))))))
    (is (= :unsupported-value
           (try (hash/blake3-bytes 123)
                (catch #?(:cljd Object :clj Exception :cljs :default) e
                  (:dao.host/refusal (ex-data e)))))))
  (testing "arguments are not modified"
    (let [in (bytes/from-vector [1 2 3 4 5])
          in-hex (bytes->hex in)]
      (hash/sha256-bytes in)
      (is (= in-hex (bytes->hex in)))
      (hash/blake3-bytes in)
      (is (= in-hex (bytes->hex in))))))


(deftest sign-primitive-vectors
  (testing "RFC 8032 keypair and sign/verify roundtrip through dao.host.sign"
    (let [seed-bytes (bytes/from-vector (vec (repeat 32 1)))
          pair (sign/keypair-from-seed seed-bytes)
          pub (:public pair)
          msg (text/utf8-encode "hello world")
          sig (sign/sign seed-bytes msg)]
      (is (= 32 (bytes/length pub)))
      (is (= 64 (bytes/length sig)))
      (is (true? (sign/verify pub msg sig)))
      (is (false? (sign/verify pub (text/utf8-encode "wrong msg") sig))))))

(deftest surrogate-refusal-in-consumers
  (testing "Table D: digest-string and message refuse unpaired surrogate string"
    (let [lone-high (text/units->string [0xD800])
          lone-low (text/units->string [0xDFFF])]
      ;; dao.jing/digest-string
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"surrogate|refused"
                            (jing/digest-string :blake3 lone-high)))
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"surrogate|refused"
                            (jing/digest-string :sha256 lone-low)))
      ;; dao.jing/sha256 and blake3
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"surrogate|refused"
                            (jing/sha256 lone-high)))
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"surrogate|refused"
                            (jing/blake3 lone-low)))
      ;; yin.vm.linker.sign message assembly through sign-trace
      (let [seed (apply str (repeat 64 "1"))
            bad-trace {:yin.head/principal (str "ed25519:" (linker-sign/public-of seed))
                       :bad (str "bad" lone-high)}]
        (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                              #"surrogate|refused"
                              (linker-sign/sign-trace seed bad-trace)))))))
