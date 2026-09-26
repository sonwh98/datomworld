(ns yin.vm.linker-authority-test
  "M4 entry criterion (docs/design/yin.vm.linker.md section 9): the
   section 8.2 assertion-policy tests, one per line of the M4 entry
   list, plus the envelope-shape discard of slice A1. Every test
   asserts the exact discard kind or outcome, so each can fail.
   Signatures stand on a deterministic blake3 digest of the envelope's
   canonical bytes, so a signature valid for one envelope fails for
   any other; no real cryptographic primitive is exercised. Plain data
   only, so the same tests pass on the JVM, Node, and Dart."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [yin.vm.linker.authority :as authority]))


;; =============================================================================
;; Fixtures: a deterministic signature stand-in and envelope builders
;; =============================================================================

(defn- addr
  "A distinct manifest address for a distinct payload."
  [payload]
  (jing/segment-key payload))


(defn- fake-sign
  "The 'signature' over an envelope: the blake3 digest of its
   canonical bytes, as a plain hex string."
  [env]
  (jing/content-hash env))


(defn- fake-verify
  "The composition-supplied verify stand-in: recompute the digest from
   exactly the bytes the linker hands over."
  [_key bs sig]
  (= sig (jing/digest-bytes :blake3 bs)))


(defn- sig-decl
  "A declared signature principal; the sequence floor defaults to 0."
  ([principal] (sig-decl principal 0))
  ([principal floor]
   {:proof :yin.module/signature
    :key [:key principal]
    :verify fake-verify
    :seq-floor floor}))


(defn- attested-decl
  [log-id]
  {:proof :yin.module/attested :dao.stream/identity log-id})


(defn- assertion-env
  [principal name-sym manifest n]
  {:yin.module/op :assert
   :yin.module/name name-sym
   :yin.module/manifest manifest
   :yin.module/asserted-by principal
   :yin.module/seq n})


(defn- retraction-env
  [principal asserted n]
  {:yin.module/op :retract
   :yin.module/of (authority/assertion-id asserted)
   :yin.module/asserted-by principal
   :yin.module/seq n})


(defn- signed
  [env]
  {:yin.module/envelope env
   :yin.module/proof {:yin.module/signature (fake-sign env)}})


(defn- unsigned
  [env]
  {:yin.module/envelope env})


(defn- attested
  "An event whose proof attests `declared` and whose carrier stream is
   `carrier`."
  [env declared carrier]
  {:yin.module/envelope env
   :dao.stream/identity carrier
   :yin.module/proof {:yin.module/attested declared}})


(defn- discards
  "The diagnostics carrying one discard kind."
  [snapshot kind]
  (filter (fn [d] (= kind (:kind d))) (:diagnostics snapshot)))


(defn- entry
  [snapshot name-sym]
  (get (:names snapshot) name-sym))


;; =============================================================================
;; The M4 entry list (section 9), one test per line
;; =============================================================================

(deftest signed-assertion-by-declared-principal-resolves
  (let [a (addr [:manifest 'my.lib 1])
        snap (authority/name-environment
               {:snapshot :snap/entry-1
                :principals {'alice (sig-decl 'alice)}}
               [(signed (assertion-env 'alice 'my.lib a 1))])]
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= a (:address (entry snap 'my.lib))))
    (is (= ['alice]
           (-> (entry snap 'my.lib)
               :yin.link/provenance :yin.module/asserted-by)))
    (is (= [:yin.module/signature]
           (-> (entry snap 'my.lib)
               :yin.link/provenance :yin.link/proof-kind)))
    (is (= :snap/entry-1
           (-> (entry snap 'my.lib)
               :yin.link/provenance :yin.link/snapshot)))
    (is (= {'alice 1} (:honored-seq snap)))
    (is (empty? (:diagnostics snap)))))


(deftest bare-asserted-by-without-proof-is-unauthenticated
  (let [snap (authority/name-environment
               {:snapshot :snap/entry-2
                :principals {'alice (sig-decl 'alice)}}
               [(unsigned (assertion-env 'alice 'my.lib (addr [:m 1]) 1))])]
    (is (= :refused (:status (entry snap 'my.lib))))
    (is (= :absent (:reason (entry snap 'my.lib))))
    (is (= 1 (count (discards snap :no-proof))))
    (is (= :unauthenticated (:reason (first (discards snap :no-proof)))))))


(deftest bad-signature-is-unauthenticated
  (let [a (addr [:m 'my.lib 1])
        env (assertion-env 'alice 'my.lib a 1)
        ;; a signature minted over a different envelope
        sig-of-other (fake-sign
                       (assertion-env 'alice 'other.lib (addr [:m 2]) 9))
        snap (authority/name-environment
               {:snapshot :snap/entry-3
                :principals {'alice (sig-decl 'alice)}}
               [{:yin.module/envelope env
                 :yin.module/proof {:yin.module/signature sig-of-other}}])]
    (is (= :absent (:reason (entry snap 'my.lib))))
    (is (= 1 (count (discards snap :bad-proof))))
    (is (= :unauthenticated (:reason (first (discards snap :bad-proof)))))))


(deftest attested-assertion-copied-onto-another-stream-is-unauthenticated
  (let [env (assertion-env 'reporter 'log.lib (addr [:m 1]) 1)
        auth {:snapshot :snap/entry-4
              :principals {'reporter (attested-decl :dao.stream/log-1)}}]
    (testing "read from the declared log, it proves the reporter"
      (let [snap (authority/name-environment
                   auth
                   [(attested env :dao.stream/log-1 :dao.stream/log-1)])]
        (is (= :ok (:status (entry snap 'log.lib))))
        (is (= [:yin.module/attested]
               (-> (entry snap 'log.lib)
                   :yin.link/provenance :yin.link/proof-kind)))))
    (testing "the same envelope copied onto another stream carries no proof"
      (let [snap (authority/name-environment
                   auth
                   [(attested env :dao.stream/log-1 :dao.stream/log-2)])]
        (is (= :absent (:reason (entry snap 'log.lib))))
        (is (= 1 (count (discards snap :bad-proof))))))))


(deftest undeclared-principal-is-ignored
  (let [mine (addr [:m 'my.lib :alice])
        snap (authority/name-environment
               {:snapshot :snap/entry-5
                :principals {'alice (sig-decl 'alice)}}
               [(signed (assertion-env 'mallory
                                       'my.lib (addr [:m 'my.lib :mallory]) 1))
                (signed (assertion-env 'mallory
                                       'ghost.lib (addr [:m 'ghost 1]) 2))
                (signed (assertion-env 'alice 'my.lib mine 1))])]
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= mine (:address (entry snap 'my.lib))))
    (is (= :absent (:reason (entry snap 'ghost.lib))))
    (is (= 2 (count (discards snap :undeclared-principal))))))


(deftest principal-without-proof-kind-cannot-assert
  (let [snap (authority/name-environment
               {:snapshot :snap/no-proof-kind
                ;; declared, but with neither proof kind
                :principals {'alice {:seq-floor 0}}}
               [(signed (assertion-env 'alice 'my.lib (addr [:m 1]) 1))])]
    (is (= 1 (count (discards snap :no-proof-kind))))
    (is (= :undeclared-principal
           (:reason (first (discards snap :no-proof-kind)))))
    (is (empty? (:honored-seq snap)))
    (is (= :absent (:reason (entry snap 'my.lib))))))


(deftest signed-retraction-removes-only-the-assertion-it-names
  (let [removed (addr [:m 'my.lib 1])
        same-name (addr [:m 'my.lib 3])
        other (addr [:m 'other.lib 2])
        doomed (assertion-env 'alice 'my.lib removed 1)
        snap (authority/name-environment
               {:snapshot :snap/entry-6
                :principals {'alice (sig-decl 'alice)}}
               [(signed doomed)
                (signed (assertion-env 'alice 'other.lib other 2))
                (signed (assertion-env 'alice 'my.lib same-name 3))
                (signed (retraction-env 'alice doomed 4))])]
    ;; the retracted id is gone; the same-named assertion survives
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= same-name (:address (entry snap 'my.lib))))
    (is (= other (:address (entry snap 'other.lib))))
    (is (= {'alice 4} (:honored-seq snap)))
    (is (empty? (discards snap :dangling-retraction)))))


(deftest dangling-retractions-are-discarded
  (let [a (addr [:m 'my.lib 1])
        asserted (assertion-env 'alice 'my.lib a 1)
        never-asserted (assertion-env 'alice 'no.lib (addr [:m 'no.lib 9]) 9)
        snap (authority/name-environment
               {:snapshot :snap/entry-7
                :principals {'alice (sig-decl 'alice)
                             'bob (sig-decl 'bob)}}
               [(signed asserted)
                ;; names no assertion at the snapshot
                (signed (retraction-env 'alice never-asserted 2))
                ;; signed by another principal
                (signed (retraction-env 'bob asserted 1))])]
    (is (= 2 (count (discards snap :dangling-retraction))))
    ;; the retraction by another principal removes nothing
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= a (:address (entry snap 'my.lib))))))


(deftest exact-duplicate-envelope-is-honored-once
  (let [a (addr [:m 'my.lib 1])
        ev (signed (assertion-env 'alice 'my.lib a 1))
        snap (authority/name-environment
               {:snapshot :snap/entry-8
                :principals {'alice (sig-decl 'alice)}}
               [ev ev ev])]
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= a (:address (entry snap 'my.lib))))
    (is (= {'alice 1} (:honored-seq snap)))
    (is (empty? (discards snap :equivocation)))
    (is (empty? (:diagnostics snap)))))


(deftest replayed-sequence-is-discarded
  ;; the principal is re-declared with floor 5: an old claim under a
  ;; fresh carrier does not exceed it
  (let [snap (authority/name-environment
               {:snapshot :snap/entry-9
                :principals {'alice (sig-decl 'alice 5)}}
               [(signed (assertion-env 'alice 'my.lib (addr [:m 1]) 3))
                (signed (assertion-env 'alice 'my.lib (addr [:m 2]) 5))])]
    (is (= :absent (:reason (entry snap 'my.lib))))
    (is (= 2 (count (discards snap :replay))))
    (is (empty? (:honored-seq snap)))))


(deftest equivocating-pair-is-discarded
  (let [snap (authority/name-environment
               {:snapshot :snap/entry-10
                :principals {'alice (sig-decl 'alice)}}
               [(signed (assertion-env 'alice 'my.lib (addr [:m 1]) 2))
                ;; a distinct claim under the same sequence
                (signed (assertion-env 'alice 'my.lib (addr [:m 2]) 2))
                ;; a later envelope of the same principal
                (signed (assertion-env 'alice 'other.lib (addr [:m 3]) 3))])]
    (is (= :absent (:reason (entry snap 'my.lib))))
    (is (= :absent (:reason (entry snap 'other.lib))))
    ;; the pair and the later envelope all go, as equivocation
    (is (= 3 (count (discards snap :equivocation))))
    (is (empty? (discards snap :replay)))))


(deftest two-proven-assertions-refuse-ambiguous-name
  (let [a1 (addr [:m 'my.lib :alice])
        a2 (addr [:m 'my.lib :bob])
        snap (authority/name-environment
               {:snapshot :snap/entry-11
                :principals {'alice (sig-decl 'alice)
                             'bob (sig-decl 'bob)}}
               [(signed (assertion-env 'alice 'my.lib a1 1))
                (signed (assertion-env 'bob 'my.lib a2 1))])
        e (entry snap 'my.lib)]
    (is (= :refused (:status e)))
    (is (= :ambiguous-name (:reason e)))
    (is (= #{a1 a2} (set (:addresses e))))
    (is (= #{'alice 'bob} (set (:asserters e))))
    (is (nil? (:address e)))))


(deftest two-asserters-of-one-address-resolve-together
  (let [a (addr [:m 'shared.lib 1])
        snap (authority/name-environment
               {:snapshot :snap/shared
                :principals {'alice (sig-decl 'alice)
                             'bob (sig-decl 'bob)
                             'carol (attested-decl :dao.stream/log-1)}}
               [(signed (assertion-env 'alice 'shared.lib a 1))
                (signed (assertion-env 'bob 'shared.lib a 1))
                (attested (assertion-env 'carol 'shared.lib a 1)
                          :dao.stream/log-1 :dao.stream/log-1)])
        e (entry snap 'shared.lib)]
    ;; one distinct address resolves the name, with every asserter
    (is (= :ok (:status e)))
    (is (= a (:address e)))
    (is (= ['alice 'bob 'carol]
           (-> e :yin.link/provenance :yin.module/asserted-by)))
    ;; two signatures collapse to one proof kind by distinct
    (is (= [:yin.module/signature :yin.module/attested]
           (-> e :yin.link/provenance :yin.link/proof-kind)))))


(deftest snapshot-advance-rebuilds-never-rereads
  (let [old (addr [:m 1])
        new (addr [:m 2])
        first-assertion (assertion-env 'alice 'my.lib old 1)
        snap1 (authority/name-environment
                {:snapshot :snap/index-1
                 :principals {'alice (sig-decl 'alice)}}
                [(signed first-assertion)])
        ;; the composition advances: new snapshot, floor = the highest
        ;; sequence honored at the first
        snap2 (authority/name-environment
                {:snapshot :snap/index-2
                 :principals
                 {'alice (sig-decl 'alice (get (:honored-seq snap1)
                                               'alice))}}
                [(signed first-assertion)
                 (signed (assertion-env 'alice 'your.lib new 2))])]
    (is (= :ok (:status (entry snap1 'my.lib))))
    ;; the first snapshot is untouched by the second build
    (is (= :ok (:status (entry snap1 'my.lib))))
    (is (= :snap/index-1
           (-> (entry snap1 'my.lib)
               :yin.link/provenance :yin.link/snapshot)))
    ;; at the advanced snapshot the old claim is a replay, not re-honored
    (is (= :absent (:reason (entry snap2 'my.lib))))
    (is (= 1 (count (discards snap2 :replay))))
    (is (= new (:address (entry snap2 'your.lib))))
    (is (= :snap/index-2
           (-> (entry snap2 'your.lib)
               :yin.link/provenance :yin.link/snapshot)))
    (is (= {'alice 2} (:honored-seq snap2)))))


;; =============================================================================
;; Envelope shape (slice A1)
;; =============================================================================

(deftest malformed-envelope-is-discarded
  (let [snap (authority/name-environment
               {:snapshot :snap/shape
                :principals {'alice (sig-decl 'alice)}}
               [(signed {:yin.module/op :assert
                         ;; no :yin.module/name
                         :yin.module/manifest (addr [:m 1])
                         :yin.module/asserted-by 'alice
                         :yin.module/seq 1})
                (signed {:yin.module/op :retract
                         :yin.module/of :not-a-segment
                         :yin.module/asserted-by 'alice
                         :yin.module/seq 2})])]
    (is (= 2 (count (discards snap :malformed-envelope))))
    (is (= {} (:names snap)))))


(deftest fake-segment-address-is-malformed
  (let [real (addr [:m 'real.lib 1])
        snap (authority/name-environment
               {:snapshot :snap/shape-fake
                :principals {'alice (sig-decl 'alice)}}
               [(signed (assertion-env 'alice 'my.lib :segment/fake 1))
                (signed {:yin.module/op :retract
                         :yin.module/of :segment/fake
                         :yin.module/asserted-by 'alice
                         :yin.module/seq 2})
                ;; a real segment key still passes the shape gate
                (signed (assertion-env 'alice 'real.lib real 3))])]
    (is (= 2 (count (discards snap :malformed-envelope))))
    (is (= :ok (:status (entry snap 'real.lib))))
    (is (= real (:address (entry snap 'real.lib))))
    (is (not (contains? (:names snap) 'my.lib)))))
