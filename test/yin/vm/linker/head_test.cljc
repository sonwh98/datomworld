(ns yin.vm.linker.head-test
  "The published head trace and its rule (docs/design/
   yin.vm.linker.dht.head.md, slice H0)."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream.cbor :as cbor]
            [dao.stream.memory-log :as memory-log]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.index :as repl.index]
            [yin.repl.store :as store]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]))


(defn- vectors
  []
  (edn/read-string
    #?(:cljd (.readAsStringSync
               (dart-io/File. "test/yin/vm/linker/sign_vectors.edn"))
       :clj (slurp "test/yin/vm/linker/sign_vectors.edn")
       :cljs (.readFileSync (js/require "fs")
                            "test/yin/vm/linker/sign_vectors.edn" "utf8"))))


(defn- hex
  [bytes]
  (apply str (mapcat (fn [b]
                       (let [n (bit-and b 255)
                             digits "0123456789abcdef"]
                         [(nth digits (quot n 16))
                          (nth digits (mod n 16))])) bytes)))


;; UTF-8 of "yin.head/trace:v1\n" and of "yin.module/envelope:v1\n".
(def ^:private head-prefix "79696e2e686561642f74726163653a76310a")
(def ^:private name-prefix "79696e2e6d6f64756c652f656e76656c6f70653a76310a")


(defn- key-of
  [v i]
  (select-keys (get-in v [:rfc i]) [:seed :public]))


(defn- vector-trace
  [v case-key]
  {:yin.head/envelope (get-in v [case-key :envelope])
   :yin.head/proof {:yin.head/signature (get-in v [case-key :signature])}})


(defn- flip-first-bit
  [signature]
  (let [digits "0123456789abcdef"
        n (str/index-of digits (subs signature 0 1))]
    (str (nth digits (bit-xor n 1)) (subs signature 1))))


;; =============================================================================
;; The vectors: RFC 8032 TEST 1's key, byte for byte on every host
;; =============================================================================

(deftest head-trace-vectors
  (let [v (vectors)
        key (key-of v 0)]
    (doseq [case-key [:head :head-wide]]
      (let [{:keys [envelope canonical message signature]} (get v case-key)
            {:yin.head/keys [principal manifest] n :yin.head/seq} envelope
            built (head/trace key manifest n)]
        (testing (str case-key)
          (is (= (sign/principal (:public key)) principal))
          (is (= canonical (hex (jing/canonical-bytes envelope))))
          (is (= message (str head-prefix canonical))
              "the signed message is the head prefix, then the canonical bytes")
          (is (= signature (sign/sign-message (:seed key) message))
              "the primitive is the RFC 8032 Ed25519, unchanged")
          (is (= (vector-trace v case-key) built)
              "trace builds exactly the closed shape of 5.2")
          (is (true? (head/verify built))))))
    (is (< 4294967296 (get-in v [:head-wide :envelope :yin.head/seq]))
        "one vector's sequence is above 2^32")
    (is (zero? (get-in v [:head :envelope :yin.head/seq])))))


(deftest tampering-fails
  (let [v (vectors)
        good (vector-trace v :head)
        env (:yin.head/envelope good)
        sig (get-in good [:yin.head/proof :yin.head/signature])
        other-key (key-of v 1)
        other-principal (sign/principal (:public other-key))
        t (:tamper v)
        with-env (fn [e] (assoc good :yin.head/envelope e))
        with-sig (fn [s] (assoc good :yin.head/proof {:yin.head/signature s}))]
    (is (true? (head/verify good)))
    (doseq [[label bad]
            [["principal" (with-env (assoc env :yin.head/principal
                                           other-principal))]
             ["manifest" (with-env (assoc env :yin.head/manifest (:manifest t)))]
             ["sequence" (with-env (assoc env :yin.head/seq 1))]
             ["a flipped signature bit" (with-sig (flip-first-bit sig))]
             ["another key's signature"
              (with-sig (sign/sign-message (:seed other-key)
                                           (get-in v [:head :message])))]
             ["another key's trace under this principal"
              (with-sig (get-in (head/trace other-key
                                            (:yin.head/manifest env) 0)
                                [:yin.head/proof :yin.head/signature]))]
             ["an extra envelope key" (with-env (assoc env :yin.head/time 1))]
             ["an extra proof key"
              (assoc-in good [:yin.head/proof :yin.head/key] (:public
                                                               (key-of v 0)))]
             ["an extra trace key" (assoc good :yin.head/via "x")]
             ["a name-envelope signature over the same bytes"
              (with-sig (get-in v [:head-tamper :name-domain-signature]))]
             ["a name envelope's own signature"
              (with-sig (get-in v [:assertion :signature]))]
             ["a name envelope and its proof as a trace"
              {:yin.head/envelope (get-in v [:assertion :envelope])
               :yin.head/proof {:yin.head/signature
                                (get-in v [:assertion :signature])}}]]]
      (is (false? (head/verify bad)) label))
    (testing "the name-domain signature is the real one, under the other prefix"
      (is (= (get-in v [:head-tamper :name-domain-signature])
             (sign/sign-message (:seed (key-of v 0))
                                (str name-prefix
                                     (get-in v [:head :canonical]))))))
    (testing "neither proof verifies as the other"
      (is (false? (sign/verify-envelope (:public (key-of v 0))
                                        (jing/canonical-bytes env) sig)))
      (is (false? (sign/verify-trace
                    (:public (key-of v 0))
                    (jing/canonical-bytes (get-in v [:assertion :envelope]))
                    (get-in v [:assertion :signature]))))
      (is (true? (sign/verify-envelope
                   (:public (key-of v 0))
                   (jing/canonical-bytes (get-in v [:assertion :envelope]))
                   (get-in v [:assertion :signature])))
          "name-envelope signing is unchanged"))
    (testing "verify never throws"
      (doseq [x [nil 42 "trace" [] {} {:yin.head/envelope nil}
                 (with-sig "zz")
                 (with-env (assoc env :yin.head/principal "ed25519:zz"))]]
        (is (false? (head/verify x)))))))


(deftest a-trace-through-the-stream-cbor-codec-verifies
  (let [v (vectors)]
    (doseq [case-key [:head :head-wide]]
      (let [trace (vector-trace v case-key)
            decoded (cbor/decode (cbor/encode trace))]
        (is (= trace decoded) (str case-key))
        (is (= (get-in v [case-key :canonical])
               (hex (jing/canonical-bytes (:yin.head/envelope decoded)))))
        (is (true? (head/verify decoded)) (str case-key))))))


;; =============================================================================
;; judge: every row of 5.5
;; =============================================================================

(deftest judge-produces-every-row
  (let [v (vectors)
        key (key-of v 0)
        p (sign/principal (:public key))
        m1 (get-in v [:head :envelope :yin.head/manifest])
        m2 (get-in v [:tamper :manifest])
        at (fn [n m] (head/trace key m n))
        five (at 5 m1)]
    (testing "malformed: not the closed shape, or a bad sequence"
      (doseq [bad [nil "trace" {}
                   (dissoc five :yin.head/proof)
                   (assoc five :yin.head/via "x")
                   (assoc-in five [:yin.head/envelope :yin.head/time] 1)
                   (assoc-in five [:yin.head/proof :yin.head/extra] 1)
                   (assoc-in five [:yin.head/envelope :yin.head/seq] -1)
                   (assoc-in five [:yin.head/envelope :yin.head/seq] 0.5)
                   (assoc-in five [:yin.head/envelope :yin.head/seq] "5")
                   (assoc-in five [:yin.head/envelope :yin.head/seq] nil)
                   (assoc-in five [:yin.head/envelope :yin.head/manifest] "m")
                   (assoc-in five [:yin.head/envelope :yin.head/principal]
                             (:public key))
                   (assoc-in five [:yin.head/proof :yin.head/signature] "ab")]]
        (is (= :yin.head/malformed (head/judge p nil nil bad)) (pr-str bad))
        (is (= :yin.head/malformed (head/judge p 5 m1 bad)))))
    (testing "a negative or non-integer sequence is malformed even when signed"
      (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
            (head/trace key m1 -1)))
      (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
            (head/trace key m1 0.5))))
    (is (= :yin.head/wrong-principal
           (head/judge (sign/principal (:public (key-of v 1))) nil nil five)))
    (is (= :yin.head/bad-proof
           (head/judge p nil nil
                       (update-in five [:yin.head/proof :yin.head/signature]
                                  flip-first-bit))))
    (is (= :yin.head/bad-proof
           (head/judge p 5 m1
                       (assoc-in five [:yin.head/envelope :yin.head/seq] 6)))
        "a sequence raised past the signature is a bad proof, not a candidate")
    (testing "a nil floor: candidate, whatever the sequence"
      (doseq [n [0 1 5 (get-in v [:head-wide :envelope :yin.head/seq])]]
        (is (= :candidate (head/judge p nil nil (at n m1))))))
    (is (= :yin.head/stale (head/judge p 5 m1 (at 4 m1))))
    (is (= :yin.head/stale (head/judge p 5 m1 (at 4 m2))))
    (is (= :duplicate (head/judge p 5 m1 five)))
    (is (= :yin.head/equivocation (head/judge p 5 m1 (at 5 m2))))
    (is (= :candidate (head/judge p 5 m1 (at 6 m1))))
    (is (= :candidate (head/judge p 5 m1 (at 6 m2))))
    (testing "the vector traces, the wide one above 2^32"
      (is (= :candidate (head/judge p 0 m1 (vector-trace v :head-wide))))
      (is (= :yin.head/stale
             (head/judge p 4294967298 m1 (vector-trace v :head-wide)))))))


(defn- answer-or-thrown
  "`(f)`, or `[::threw e]` when it throws, so a throw is an assertion
   failure, never an error that hides which shape caused it."
  [f]
  (try (f)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         [::threw e])))


(deftest no-shape-makes-judge-or-verify-throw
  (let [v (vectors)
        good (vector-trace v :head)
        p (get-in good [:yin.head/envelope :yin.head/principal])
        m1 (get-in good [:yin.head/envelope :yin.head/manifest])
        sig (get-in good [:yin.head/proof :yin.head/signature])
        shapes [["an odd-length list" (list 1 2 3)]
                ["an even-length list" (list 1 2 3 4)]
                ["a vector" [1 2 3]]
                ["a number" 42]
                ["a string" "envelope"]
                ["nil" nil]
                ["a set" #{1 2 3}]
                ["a keyword" :yin.head/envelope]]
        cases (concat
                (for [[label x] shapes]
                  [(str "an envelope that is " label)
                   {:yin.head/envelope x
                    :yin.head/proof {:yin.head/signature sig}}])
                (for [[label x] shapes]
                  [(str "a proof that is " label)
                   (assoc good :yin.head/proof x)])
                [["a nil trace" nil]
                 ["a number trace" 42]
                 ["a vector trace" [good]]
                 ["a list trace" (list :yin.head/envelope
                                       (:yin.head/envelope good))]
                 ["a string trace" "trace"]])]
    (is (= :duplicate (head/judge p 0 m1 good)) "the good trace still judges")
    (doseq [[label trace] cases]
      (doseq [[floor installed] [[nil nil] [0 m1]]]
        (is (= :yin.head/malformed
               (answer-or-thrown #(head/judge p floor installed trace)))
            (str "judge: " label)))
      (is (false? (answer-or-thrown #(head/verify trace)))
          (str "verify: " label)))
    (testing "the case that threw before the fix: an odd-length list envelope"
      (is (= :yin.head/malformed
             (answer-or-thrown
               #(head/judge p nil nil
                            {:yin.head/envelope (list 1 2 3)
                             :yin.head/proof {:yin.head/signature sig}})))))))


(deftest sequence-zero
  (let [v (vectors)
        key (key-of v 0)
        p (sign/principal (:public key))
        m1 (get-in v [:head :envelope :yin.head/manifest])
        m2 (get-in v [:tamper :manifest])]
    (is (= :candidate (head/judge p nil nil (head/trace key m1 0)))
        "with a nil floor a trace of sequence 0 is a candidate")
    (is (= :candidate (head/judge p 0 m1 (head/trace key m1 1))))
    (is (= :duplicate (head/judge p 0 m1 (head/trace key m1 0))))
    (is (= :yin.head/equivocation (head/judge p 0 m1 (head/trace key m2 0))))
    (doseq [n [-1 0.5]]
      (is (= :yin.head/malformed
             (head/judge p 0 m1 (assoc-in (head/trace key m1 0)
                                          [:yin.head/envelope :yin.head/seq]
                                          n)))))
    (testing "seq-of one transaction is 0, the transactor's first t"
      (let [log (:dao.stream/handle
                  (memory-log/create! {:dao.stream/type
                                       memory-log/transport-type}))
            tx (transactor/create! {:local-stream log
                                    :intake-pool [(:dao.stream/handle
                                                    (memory-log/create!
                                                      {:dao.stream/type
                                                       memory-log/transport-type}))]})
            receipt (transactor/transact! tx [{:db/id 100 :test/a 1}
                                              {:db/id 101 :test/a 2}])
            receipt' (transactor/transact! tx [{:db/id 100 :test/a 3}])]
        (is (= 0 (:dao.space/t receipt)))
        (is (= 0 (head/seq-of (:dao.space/datoms receipt))))
        (is (= 1 (head/seq-of (concat (:dao.space/datoms receipt)
                                      (:dao.space/datoms receipt')))))))
    (is (nil? (head/seq-of [])) "no datoms, no sequence")
    (is (nil? (head/seq-of nil)))))


#?(:cljd nil
   :clj
   (deftest judge-admits-no-source-and-no-candidate
     (is (= '([principal floor installed trace])
            (:arglists (meta #'head/judge))))
     (let [v (vectors)
           key (key-of v 0)
           trace (head/trace key (get-in v [:head :envelope :yin.head/manifest])
                             0)]
       (is (thrown? clojure.lang.ArityException
             (apply head/judge [(sign/principal (:public key)) nil nil
                                trace :source]))))))


;; =============================================================================
;; seq-of across the REPL's HEAD moves (5.2, H0's last criterion)
;; =============================================================================

(defn- temp-store-dir
  []
  (str "target/test-head-seq-" (random-uuid)))


(defn- cleanup-store-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- recording
  "`opened`, its HEAD writes recorded in `heads` after each succeeds."
  [opened heads]
  (assoc opened :head-fn (fn [m]
                           ((:head-fn opened) m)
                           (swap! heads conj m))))


(defn- evaluate
  [state lines]
  (reduce (fn [state line] (first (repl/eval-input state line)))
          state
          lines))


(defn- seqs
  "The sequence of each recorded HEAD, read from the store it names."
  [content-store heads]
  (mapv #(head/seq-of (index/read-datoms content-store %)) heads))


(defn- strictly-increasing?
  [xs]
  (every? (fn [[a b]] (< a b)) (partition 2 1 xs)))


(deftest seq-of-across-head-moves
  (let [dir (temp-store-dir)
        heads (atom [])
        key (sign/generate)]
    (try
      (let [opened (store/open {:type :file :dir dir})
            state (evaluate (repl.frontends/create-state
                              {:index-store (recording opened heads)})
                            ["(def a 1)" "(def b 2)"])
            _ (testing "each round moves HEAD once, one t above the last"
                (is (= [0 1] (seqs opened @heads))))
            signed [(publish/assertion key {:name 'lib :seq 1
                                            :manifest (first @heads)})]
            state (assoc state :indexer
                         (repl.index/commit-names (:indexer state) signed 3))
            _ (testing "a name transaction raises it"
                (is (= [0 1 2] (seqs opened @heads))))
            [state message] (repl/eval-input state "(reset)")
            _ (is (str/ends-with? message "reset"))
            state (evaluate state ["(def c 3)"])
            _ (testing "(reset) continues t; the next round raises it"
                (is (= [0 1 2 3] (seqs opened @heads))))
            before (head/seq-of (index/read-datoms
                                  opened
                                  (get-in state [:indexer :manifest-address])))]
        (store/close! opened)
        (let [reopened (store/open {:type :file :dir dir})]
          (try
            (let [recovery (:recovery reopened)
                  state (repl.frontends/create-state
                          {:index-store (recording reopened heads)})]
              (testing "a rehydrated index has the sequence it had"
                (is (= (last @heads) (:manifest recovery)))
                (is (= before (head/seq-of (:datoms recovery))))
                (is (= before (head/seq-of
                                (index/read-datoms reopened
                                                   (:manifest recovery))))))
              (evaluate state ["(def d 4)"])
              (testing "a further round after the restart raises it"
                (is (= [0 1 2 3 4] (seqs reopened @heads))))
              (testing "no two HEAD writes of the directory share a sequence"
                (is (= 5 (count @heads)))
                (is (strictly-increasing? (seqs reopened @heads)))))
            (finally (store/close! reopened)))))
      (finally
        (cleanup-store-dir! dir)))))


(deftest seq-of-across-the-unwritten-row-recovery
  (let [broken? (atom true)
        heads (atom [])
        inner (mem/create-content-mem)
        content (assoc inner
                       :put-bytes-fn (fn [address bs]
                                       (when @broken?
                                         (throw (ex-info "store refused" {})))
                                       ((:put-bytes-fn inner) address bs))
                       :head-fn (fn [m] (swap! heads conj m)))
        state (evaluate (repl.frontends/create-state {:index-store content})
                        ["(def a 4101)" "(def b 4102)"])]
    (is (= [] @heads) "no HEAD moves while rows are unwritten")
    (reset! broken? false)
    (let [signed [(publish/assertion (sign/generate)
                                     {:name 'lib :seq 1
                                      :manifest (jing/segment-key "m")})]
          state (assoc state :indexer
                       (repl.index/commit-names (:indexer state) signed 3))
          _ (testing "the name transaction writes the rows, then one HEAD"
              (is (= [2] (seqs content @heads))))
          _ (evaluate state ["(def c 4103)"])]
      (testing "the next round raises it; no two HEADs share it"
        (is (= [2 3] (seqs content @heads)))))))
