(ns yin.vm.ucf.checkpoint-test
  "M-next C slice C4: the pure checkpoint inspector over the shared
   canonical fixtures.  Every fixture's bytes are pinned by the segment
   address test/resources/yin/vm/ucf/checkpoint-v1.txt records for it on
   each host, and three anchors are read as frozen bytes, so a rule
   about integer kind or key presence is checked on the bytes, never on
   a host number type (UCF 7.11.1, clauses 1, 2, 4 and 9)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.checkpoint-fixtures :as f]))


(defn- inspect
  [n]
  (let [{:keys [address bytes]} (f/fixture n)]
    (checkpoint/inspect address bytes)))


(defn- intent-hex
  "The BLAKE3 hex of the canonical intent bytes."
  [target payload]
  (jing/digest-bytes :blake3 (cbor/encode [:yin.k/append target payload])))


(defn- literal
  [& entries]
  {:yin.k/tag :yin.k/literal :yin.k/entries (vec entries)})


(deftest the-file-holds-exactly-the-declared-bytes
  (is (= (f/render) (fx/read-path f/path))))


;; =============================================================================
;; Accepted shapes
;; =============================================================================

(deftest a-first-park-has-an-empty-baseline-at-zero
  (is (= {:yin.k/kind :blocked
          :yin.k/occurrence f/occurrence
          :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 0
          :yin.k/ops {}}
         (inspect "first-park"))))


(deftest a-successor-baseline-maps-each-carried-id-to-its-intent
  (is (= {:yin.k/kind :blocked
          :yin.k/occurrence f/occurrence
          :yin.k/origin f/origin
          :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 3
          :yin.k/ops
          {(f/op-id 0) (intent-hex "out" (literal :a 1))
           (f/op-id 1) (intent-hex "call-out"
                                   (literal :dao.stream.apply/id :call-7
                                            :dao.stream.apply/op :op/add))
           (f/op-id 2) (intent-hex "link-out"
                                   (literal :yin.link/id [:t0 7]
                                            :yin.link/name 'foo))}}
         (inspect "successor"))))


(deftest snapshot-variants-compare-by-baseline
  (let [base (inspect "successor")]
    (testing "equal intents: a different body, the same baseline"
      (is (not= (:address (f/fixture "successor"))
                (:address (f/fixture "variant-equal"))))
      (is (= base (inspect "variant-equal"))))
    (testing "one id kept, its payload substituted"
      (let [v (inspect "variant-different-intent")]
        (is (= (set (keys (:yin.k/ops base))) (set (keys (:yin.k/ops v)))))
        (is (not= (get-in base [:yin.k/ops (f/op-id 0)])
                  (get-in v [:yin.k/ops (f/op-id 0)])))
        (is (= (intent-hex "out" (literal :a 2))
               (get-in v [:yin.k/ops (f/op-id 0)])))
        (is (not= base v))))
    (testing "the same ids and intents under another counter"
      (let [v (inspect "variant-counter")]
        (is (= (:yin.k/ops base) (:yin.k/ops v)))
        (is (= 4 (:yin.k/next-op-seq v)))
        (is (not= base v))))))


(deftest a-parked-root-carries-the-header-and-no-wait
  (is (= {:yin.k/kind :parked
          :yin.k/occurrence f/occurrence
          :yin.k/origin f/origin
          :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 1
          :yin.k/ops {}}
         (inspect "parked"))))


(deftest install-children-draw-ids-in-the-roots-context
  (let [r (inspect "installs")]
    (is (= 6 (:yin.k/next-op-seq r)))
    (is (= {(f/op-id 0) (intent-hex "out" 1)
            (f/op-id 4) (intent-hex "child-out" :x)
            (f/op-id 5) (intent-hex "grandchild-out" :y)}
           (:yin.k/ops r))
        "the child's and the grandchild's ids join the root's baseline")))


(deftest a-halted-root-carries-only-its-origin
  (is (= {:yin.k/kind :halted :yin.k/origin f/origin :yin.k/ops {}}
         (inspect "halted-root"))))


(deftest the-counter-bound-is-valid-and-its-last-id-below-it
  (let [r (inspect "counter-at-bound")]
    (is (= f/max-exact (:yin.k/next-op-seq r)))
    (is (contains? (:yin.k/ops r) (f/op-id (dec f/max-exact))))))


(deftest inspect-body-agrees-with-inspect
  (doseq [n ["first-park" "successor" "installs" "op-id-at-counter"]]
    (is (= (inspect n)
           (checkpoint/inspect-body (cbor/decode (:bytes (f/fixture n)))))
        n)))


;; =============================================================================
;; Refusals: one fixture per class, each data with its path
;; =============================================================================

(def ^:private child-path [:yin.k/installs 'foo :yin.k/child])
(def ^:private put-id [:yin.k/frames 0 :yin.k/pending :yin.k/op-id])


(def ^:private undecodable
  "Fixture name to [path kind]: the expected :yin.k/undecodable."
  {"no-tag" [[] :body]
   "bad-kind" [[:yin.k/kind] :running]
   "mixed-child" [(conj child-path :yin.k/version) :mixed-version]
   "missing-policy" [[:yin.k/policy] :missing-header]
   "missing-occurrence" [[:yin.k/occurrence] :missing-header]
   "missing-arbitration" [[:yin.k/arbitration] :missing-header]
   "missing-next-op-seq" [[:yin.k/next-op-seq] :missing-header]
   "fork-policy" [[:yin.k/policy] nil]
   "nil-occurrence" [[:yin.k/occurrence] :nil-occurrence]
   "occurrence-not-uuid" [[:yin.k/occurrence] :malformed-occurrence]
   "origin-occurrence-not-uuid" [[:yin.k/origin] :malformed-origin]
   "malformed-arbitration" [[:yin.k/arbitration] :malformed-arbitration]
   "origin-is-self" [[:yin.k/origin :yin.k/occurrence] :origin-is-self]
   "malformed-origin" [[:yin.k/origin] :malformed-origin]
   "origin-nil-emitter" [[:yin.k/origin] :malformed-origin]
   "counter-float" [[:yin.k/next-op-seq] :inexact]
   "counter-over-bound" [[:yin.k/next-op-seq] :inexact]
   "counter-negative" [[:yin.k/next-op-seq] :inexact]
   "first-export-counter" [[:yin.k/next-op-seq] :first-export-counter]
   "halted-root-header" [[:yin.k/occurrence] :halted-header]
   "halted-root-without-origin" [[:yin.k/origin] :missing-header]
   "child-header" [(conj child-path :yin.k/occurrence) :child-header]
   "halted-child-origin" [[:yin.k/installs 'baz :yin.k/child :yin.k/origin]
                          :child-header]
   "install-without-entry" [[:yin.k/frames 1 :yin.k/pending]
                            :incomplete-install]
   "op-id-on-next" [[:yin.k/frames 1 :yin.k/pending :yin.k/op-id]
                    :op-id-on-variant]
   "op-id-at-counter" [(conj put-id :yin.k/seq) :op-seq-range]
   "child-op-id-out-of-range"
   [(conj child-path :yin.k/frames 1 :yin.k/pending :yin.k/op-id :yin.k/seq)
    :op-seq-range]
   "duplicate-op-id"
   [(conj child-path :yin.k/frames 1 :yin.k/pending :yin.k/op-id)
    :duplicate-op-id]
   "op-id-without-origin" [put-id :op-id-without-origin]
   "op-id-own-occurrence" [put-id :op-id-own-occurrence]
   "op-id-malformed" [put-id :malformed-op-id]
   "op-id-occurrence-not-uuid" [put-id :malformed-op-id]
   "op-id-seq-float" [(conj put-id :yin.k/seq) :inexact]
   "op-id-seq-negative" [(conj put-id :yin.k/seq) :inexact]
   "park-reason" [[:yin.k/frames 1 :yin.k/pending] nil]})


(deftest each-grammar-breach-is-undecodable-naming-its-path
  (doseq [[n [path kind]] undecodable]
    (let [r (inspect n)]
      (is (= :yin.k/undecodable (:yin.k/status r)) n)
      (is (= path (:yin.k/path r)) n)
      (is (= kind (:yin.k/kind r)) n))))


(deftest the-version-gate-checks-the-integer-kind
  (doseq [[n found] [["version-2" 2] ["version-absent" nil]
                     ["version-0" 0]]]
    (is (= {:yin.k/status :yin.k/profile-mismatch
            :yin.k/version found
            :yin.k/supported #{1}}
           (inspect n))
        n))
  (testing "an integral float is no version, on every host"
    (let [r (inspect "version-float")]
      (is (= :yin.k/profile-mismatch (:yin.k/status r)))
      (is (= #{1} (:yin.k/supported r)))
      (is (cbor/float64? (:yin.k/version r))))))


(deftest bytes-must-hash-to-the-address-and-be-canonical
  (is (= {:yin.k/status :yin.k/hash-mismatch
          :yin.k/address (:address (f/fixture "first-park"))
          :yin.k/computed (:address (f/fixture "successor"))}
         (inspect "hash-mismatch")))
  (testing "the algorithm is the one the address names"
    (let [bs (:bytes (f/fixture "successor"))
          sha (keyword "segment" (str "sha256-" (jing/digest-bytes :sha256
                                                                   bs)))]
      (is (= (inspect "successor") (checkpoint/inspect sha bs)))))
  (testing "an address that is no segment address is the same refusal"
    (let [{:keys [address bytes]} (f/fixture "successor")]
      (doseq [a [(jing/digest-bytes :blake3 bytes) (str address) nil
                 :segment/md5-00]]
        (is (= {:yin.k/status :yin.k/hash-mismatch
                :yin.k/address a
                :yin.k/computed address}
               (checkpoint/inspect a bytes))
            (pr-str a)))))
  (is (= {:yin.k/status :yin.k/undecodable :yin.k/path []
          :yin.k/kind :bytes :yin.k/refusal :non-canonical}
         (inspect "non-canonical"))))


(deftest every-fixture-has-an-expectation
  (let [accepted #{"first-park" "successor" "variant-equal"
                   "variant-different-intent" "variant-counter" "parked"
                   "installs" "halted-root" "counter-at-bound"}
        refused (into #{"hash-mismatch" "non-canonical" "version-float"
                        "version-2" "version-absent" "version-0"}
                      (keys undecodable))]
    (is (= (set f/fixture-names) (into accepted refused)))
    (doseq [n accepted]
      (is (not (contains? (inspect n) :yin.k/status)) n))))


(deftest refusals-are-data-on-any-input
  (doseq [[a bs] [[nil nil] ["x" "not bytes"]
                  [nil (fx/hex->bytes "")]]]
    (is (= :yin.k/hash-mismatch (:yin.k/status (checkpoint/inspect a bs)))))
  (testing "bytes under their own address that are no CBOR item"
    (let [bs (fx/hex->bytes "ff")
          r (checkpoint/inspect (f/segment-address bs) bs)]
      (is (= :yin.k/undecodable (:yin.k/status r)))
      (is (= :bytes (:yin.k/kind r)))
      (is (keyword? (:yin.k/refusal r)))))
  (doseq [b [nil 1 [] {} {:yin.k/handoff true}]]
    (is (contains? #{:yin.k/undecodable :yin.k/profile-mismatch}
                   (:yin.k/status (checkpoint/inspect-body b)))
        (pr-str b))))
