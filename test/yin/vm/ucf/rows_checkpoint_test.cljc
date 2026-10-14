(ns yin.vm.ucf.rows-checkpoint-test
  "Phase 7a: the rows inspector over the shared canonical fixtures. Every
   fixture's bytes are pinned by the pack address
   test/resources/yin/vm/ucf/rows-checkpoint-v3.txt records for it, and
   three anchors are read as frozen bytes, so a rule about integer kind or
   key presence is checked on the bytes, never on a host number type. The
   pack address (storage) and the root id (semantic) are tested apart."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-checkpoint-fixtures :as f]
            [yin.vm.ucf.rows.checkpoint :as checkpoint]))


(defn- inspect
  [n]
  (let [{:keys [address bytes]} (f/fixture n)]
    (checkpoint/inspect address bytes)))


(defn- intent-hex
  [target payload]
  (jing/digest-bytes :blake3 (cbor/encode [:yin.k/append target payload])))


(defn- root-of
  [n]
  (:yin.k/root (f/pack-of n)))


(deftest the-file-holds-exactly-the-declared-bytes
  (is (= (f/render) (fx/read-path f/path))))


;; =============================================================================
;; Two identities: the pack address and the root id
;; =============================================================================

(deftest the-pack-address-is-storage-and-the-root-id-is-semantic
  (doseq [n f/base-names
          :let [{:keys [address bytes]} (f/fixture n)
                wire (cbor/decode bytes)]]
    (testing n
      (is (= address (rows/pack-address wire) (f/segment-address bytes))
          "the pack address is the BLAKE3 of the pack's canonical bytes")
      (is (jing/segment-bytes-match? address bytes) "a content store's put verifies it")
      (is (not= address (:yin.k/root wire)) "the root id is another identity")
      (is (= (:yin.k/root wire)
             (jing/segment-key (vec (rest (first (filter #(= (:yin.k/root wire) (first %)) (:yin.k/rows wire)))))))
          "the root id is the root row's own content address")
      (is (= (:yin.k/root wire) (:yin.k/root (inspect n))) "the baseline names the root id")
      (is (= (mapv first (:yin.k/rows wire)) (sort-by str (mapv first (:yin.k/rows wire))))
          "emit sorts the rows by id")
      (is (= (fx/bytes->hex bytes) (fx/bytes->hex (cbor/encode (rows/emit (rows/decode wire)))))
          "one root, one pack: re-emitting the decoded task gives the same bytes"))))


(deftest a-pack-variant-of-one-root-shares-its-baseline
  (let [base (inspect "successor")]
    (testing "the same rows in another order: another address, the same root and baseline"
      (is (not= (:address (f/fixture "successor")) (:address (f/fixture "variant-equal"))))
      (is (= (root-of "successor") (root-of "variant-equal")))
      (is (= base (inspect "variant-equal"))))
    (testing "another root with the same custody and ids: the baselines differ by the root alone"
      (let [v (inspect "variant-other-root")]
        (is (not= (root-of "successor") (root-of "variant-other-root")))
        (is (= (dissoc base :yin.k/root) (dissoc v :yin.k/root)))
        (is (not= base v))))))


;; =============================================================================
;; Accepted shapes
;; =============================================================================

(deftest a-first-park-has-an-empty-baseline-at-zero
  (is (= {:yin.k/kind :blocked :yin.k/root (root-of "first-park")
          :yin.k/occurrence f/occurrence :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 0 :yin.k/ops {}}
         (inspect "first-park"))))


(deftest a-successor-baseline-maps-each-carried-id-to-its-intent
  (is (= {:yin.k/kind :blocked :yin.k/root (root-of "successor")
          :yin.k/occurrence f/occurrence :yin.k/origin f/origin :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 3
          :yin.k/ops {(f/op-id 0) (intent-hex "s0" [:lit "v"])}}
         (inspect "successor"))))


(deftest variants-compare-by-baseline
  (let [base (inspect "successor")]
    (testing "one id kept, its payload substituted"
      (let [v (inspect "variant-different-intent")]
        (is (= (set (keys (:yin.k/ops base))) (set (keys (:yin.k/ops v)))))
        (is (= (intent-hex "s0" [:lit "w"]) (get-in v [:yin.k/ops (f/op-id 0)])))
        (is (not= base v))))
    (testing "the same ids and intents under another counter"
      (let [v (inspect "variant-counter")]
        (is (= (:yin.k/ops base) (:yin.k/ops v)))
        (is (= 4 (:yin.k/next-op-seq v)))))))


(deftest a-parked-root-carries-the-custody-and-no-id
  (is (= {:yin.k/kind :parked :yin.k/root (root-of "parked")
          :yin.k/occurrence f/occurrence :yin.k/origin f/origin :yin.k/arbitration f/arbitration
          :yin.k/next-op-seq 1 :yin.k/ops {}}
         (inspect "parked"))))


(deftest install-children-draw-ids-in-the-roots-context
  (let [r (inspect "installs")]
    (is (= 6 (:yin.k/next-op-seq r)))
    (is (= {(f/op-id 4) (intent-hex "s0" [:lit "v"])} (:yin.k/ops r))
        "the child's id joins the root's baseline")
    (is (nil? (nth (:slots (f/child (rows/decode (f/pack-of "installs")) 'host.mod)) 16))
        "a child's custody slot is nil")))


(deftest a-halted-root-carries-only-its-origin
  (is (= {:yin.k/kind :halted :yin.k/root (root-of "halted-root") :yin.k/origin f/origin :yin.k/ops {}}
         (inspect "halted-root"))))


(deftest the-counter-bound-is-valid-and-its-last-id-below-it
  (let [r (inspect "counter-at-bound")]
    (is (= f/max-exact (:yin.k/next-op-seq r)))
    (is (contains? (:yin.k/ops r) (f/op-id (dec f/max-exact))))))


(deftest a-fork-names-no-custody
  (let [wire (f/edit-custody (f/base "first-park") (constantly nil))
        {:keys [address bytes]} (f/enc wire)]
    (is (= {:yin.k/kind :blocked :yin.k/root (:yin.k/root wire) :yin.k/ops {} :yin.k/fork true}
           (checkpoint/inspect address bytes)))))


(deftest inspect-pack-agrees-with-inspect
  (doseq [n ["first-park" "successor" "installs" "op-id-at-counter" "custody-epoch"]]
    (is (= (inspect n) (checkpoint/inspect-pack (cbor/decode (:bytes (f/fixture n))))) n)))


;; =============================================================================
;; Refusals: one fixture per class, each data with its path
;; =============================================================================

(deftest each-mutation-is-refused-as-it-names
  (doseq [n f/fixture-names
          :let [expected (f/refusal n)]
          :when expected]
    (let [r (inspect n)]
      (is (= expected (select-keys r (keys expected))) (str n " " (pr-str r))))))


(deftest each-mutation-is-another-pack
  (doseq [n f/fixture-names
          :when (and (f/base-of n) (not (contains? #{"hash-mismatch" "non-canonical"} n)))]
    (is (not= (:address (f/fixture (f/base-of n))) (:address (f/fixture n))) n)))


(deftest bytes-must-hash-to-the-address-and-be-canonical
  (is (= {:yin.k/status :yin.k/hash-mismatch
          :yin.k/address (:address (f/fixture "first-park"))
          :yin.k/computed (:address (f/fixture "successor"))}
         (inspect "hash-mismatch")))
  (testing "the algorithm is the one the address names"
    (let [bs (:bytes (f/fixture "successor"))
          sha (keyword "segment" (str "sha256-" (jing/digest-bytes :sha256 bs)))]
      (is (= (inspect "successor") (checkpoint/inspect sha bs)))))
  (testing "an address that is no segment address is the same refusal"
    (let [{:keys [address bytes]} (f/fixture "successor")]
      (doseq [a [(jing/digest-bytes :blake3 bytes) (str address) nil :segment/md5-00
                 (root-of "successor")]]
        (is (= {:yin.k/status :yin.k/hash-mismatch :yin.k/address a :yin.k/computed address}
               (checkpoint/inspect a bytes))
            (pr-str a)))))
  (is (= {:yin.k/status :yin.k/undecodable :yin.k/path [] :yin.k/kind :bytes :yin.k/refusal :non-canonical}
         (inspect "non-canonical"))))


(deftest every-fixture-has-an-expectation
  (let [refused (into #{"hash-mismatch" "non-canonical"} (filter f/refusal) f/fixture-names)]
    (is (= (set f/fixture-names) (into f/accepted refused)))
    (is (empty? (filter f/accepted refused)))
    (doseq [n f/accepted]
      (is (not (contains? (inspect n) :yin.k/status)) n))))


(deftest refusals-are-data-on-any-input
  (doseq [[a bs] [[nil nil] ["x" "not bytes"] [nil (fx/hex->bytes "")]]]
    (is (= :yin.k/hash-mismatch (:yin.k/status (checkpoint/inspect a bs)))))
  (testing "bytes under their own address that are no CBOR item"
    (let [bs (fx/hex->bytes "ff")
          r (checkpoint/inspect (f/segment-address bs) bs)]
      (is (= :yin.k/undecodable (:yin.k/status r)))
      (is (= :bytes (:yin.k/kind r)))))
  (testing "a CBOR item that is no pack"
    (doseq [x [1 [] {} {:yin.k/handoff true} {:yin.k/handoff true :yin.k/version 3}]]
      (let [bs (cbor/encode x)]
        (is (contains? #{:yin.k/undecodable :yin.k/profile-mismatch}
                       (:yin.k/status (checkpoint/inspect (f/segment-address bs) bs)))
            (pr-str x))))))
