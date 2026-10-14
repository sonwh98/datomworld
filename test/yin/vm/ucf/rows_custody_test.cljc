(ns yin.vm.ucf.rows-custody-test
  "Phase 7a: custody on rows (phase-7 plan §3.1). The lift under a header
   writes the root's custody slot by kind and refuses :op-seq-exhausted,
   :unprotected-pending and an id on an unenrolled stream; the lower's
   custody stage runs the custody grammar, refuses a fork under
   :exclusive, runs the version-1 grant checks on a blocked or parked
   exclusive root, protection in both directions and op-id presence equal
   to enrollment, restores the counter exactly, lowers a halted exclusive
   root with no grant gated :ended, and every refusal comes before the
   first attach (zero attachments). The version-1 rules these re-pin are
   lift_v1_test's and handoff_v2_custody_test's, kept until phase 8."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-checkpoint-fixtures :as f]
            [yin.vm.ucf.rows-support :as s]))


(defn- custody-of
  [wire]
  (nth (:slots (rows/decode wire)) 16))


(defn- identities
  "Every stream identity of the pack's tree."
  [wire]
  (into #{} (keep (fn [r] (when (= :stream (second r)) (nth r 2)))) (:yin.k/rows wire)))


;; =============================================================================
;; The lift under a header
;; =============================================================================

(deftest the-lift-writes-the-custody-slot-by-kind
  (testing "a first park: the exclusive policy, no origin"
    (is (= {:yin.k/policy :yin.k/exclusive :yin.k/occurrence s/occurrence
            :yin.k/arbitration s/arbitration :yin.k/next-op-seq 0}
           (custody-of (s/lift (s/parked-reader) (s/header 0 nil #{}))))))
  (testing "a successor carries its origin"
    (is (= {:yin.k/policy :yin.k/exclusive :yin.k/occurrence s/occurrence :yin.k/origin s/origin
            :yin.k/arbitration s/arbitration :yin.k/next-op-seq 1}
           (custody-of (s/lift (s/parked-explicit) (s/header 1))))))
  (testing "a halted root carries the origin alone"
    (is (= {:yin.k/origin s/origin} (custody-of (s/lift (s/halted-machine) (s/header 0 s/origin #{}))))))
  (testing "the enrolled set is a lift input that never travels"
    (let [wire (s/lift (s/with-op-id (s/parked-writer) :put (s/op-id 0)) (s/header 3 s/origin #{"s0"}))]
      (is (not (contains? (custody-of wire) :yin.k/enrolled)))))
  (testing "no header is the fork: nil, on every kind"
    (doseq [m [(s/parked-reader) (s/parked-explicit) (s/halted-machine)]]
      (is (nil? (custody-of (s/lift m nil))))))
  (testing "an install child's slot is nil"
    (let [task (rows/decode (f/base "installs"))]
      (is (some? (nth (:slots task) 16)))
      (is (nil? (nth (:slots (f/child task 'host.mod)) 16))))))


(deftest the-lift-refuses-what-custody-forbids
  (testing "a counter at 2^52-1 on a blocked root"
    (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :op-seq-exhausted :yin.k/next-op-seq rows/max-exact}
           (s/lift (s/parked-reader) (s/header rows/max-exact s/origin #{})))))
  (testing "a halted root ignores the counter"
    (is (nil? (:yin.k/status (s/lift (s/halted-machine) (s/header rows/max-exact s/origin #{}))))))
  (testing "a retained write on an enrolled stream without an id"
    (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :unprotected-pending :dao.stream/identity "s0"}
           (s/lift (s/parked-writer) (s/header 3 s/origin #{"s0"})))))
  (testing "an id on a stream that is not enrolled"
    (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
           (s/lift (s/with-op-id (s/parked-writer) :put (s/op-id 0)) (s/header 3 s/origin #{})))))
  (testing "the same rules in an install child"
    (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :vm] (s/parked-writer))]
      (is (= :unprotected-pending (:yin.k/kind (s/lift m (s/header 6 s/origin #{"s0"}))))))
    (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :vm]
                      (s/with-op-id (s/parked-writer) :put (s/op-id 4)))]
      (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
             (s/lift m (s/header 6 s/origin #{}))))))
  (testing "a halted root with a header but no origin is refused, not downgraded"
    (is (= {:yin.k/status :yin.k/undecodable :yin.k/kind :missing-header :yin.k/path [:custody :yin.k/origin]}
           (select-keys (s/lift (s/halted-machine) (s/header 0 nil #{})) [:yin.k/status :yin.k/kind :yin.k/path]))))
  (testing "the lift runs the inspector's grammar before emitting"
    (is (= :op-seq-range (:yin.k/kind (s/lift (s/with-op-id (s/parked-writer) :put (s/op-id 3))
                                              (s/header 3 s/origin #{"s0"})))))
    (is (= :fork-op-id (:yin.k/kind (s/lift (s/with-op-id (s/parked-writer) :put (s/op-id 0)) nil))))))


;; =============================================================================
;; The lower's custody stage
;; =============================================================================

(defn- grant-for
  "A grant whose every part agrees with wire's custody: lease-1 to
   holder-a at epoch 0, live tenure, an empty input prefix."
  [wire enrolled]
  (let [c (custody-of wire)
        o (:yin.k/occurrence c)]
    {:checkpoint (rows/pack-address wire)
     :dao.lease/lease "lease-1" :dao.lease/holder "holder-a"
     :tenure {:live true :now 0 :bound 10}
     :evidence {:yin.k/status :yin.k/ready
                :yin.k/binding {:yin.k/occurrence o :dao.lease/lease "lease-1" :dao.lease/holder "holder-a"
                                :yin.k/transaction {:yin.k/arbitration (get-in c [:yin.k/arbitration :dao.stream/identity])}
                                :yin.k/epoch 0}
                :yin.k/prefix {:yin.k/occurrence o :dao.lease/lease "lease-1" :yin.k/frontier 0 :yin.k/inputs []}
                :yin.k/enrolled enrolled}}))


(defn- protection-for
  [wire enrolled]
  (into {} (map (fn [i] [i (if (contains? enrolled i) :enrolled :at-least-once)])) (identities wire)))


(defn- lowered
  "[machine-or-refusal attaches] of wire lowered into a fresh receiver."
  [wire opts]
  (let [{:keys [attach! count]} (s/attacher)
        r (try (rows/lower-task (s/receiver) wire attach! opts)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 (or (ex-data e) (throw e))))]
    [r @count]))


(defn- exclusive-opts
  [wire enrolled]
  {:grant (grant-for wire enrolled) :protection (protection-for wire enrolled)})


(defn- refused
  "The refusal of wire lowered under opts, asserting zero attachments."
  [wire opts]
  (let [[r n] (lowered wire opts)]
    (is (zero? n) (str "refused before any attachment: " (pr-str (select-keys r [:yin.k/status :yin.k/kind]))))
    r))


(def ^:private successor (delay (f/base "successor")))


(deftest an-exclusive-root-lowers-under-its-grant
  (let [wire @successor
        [m n] (lowered wire (exclusive-opts wire #{"s0"}))]
    (is (pos? n))
    (is (= {:yin.k/occurrence s/occurrence :dao.lease/lease "lease-1" :dao.lease/holder "holder-a"
            :yin.k/epoch 0 :yin.k/arbitration s/arbitration :yin.k/next-op-seq 3
            :protection {"s0" :enrolled}
            :input {:next 0 :prefix (get-in (grant-for wire #{"s0"}) [:evidence :yin.k/prefix])}
            :tenure {:bound 10}}
           (:yin.k/custody m))
        "custody from the evidence, the counter the slot's exactly")
    (is (= :running (:yin.k/gate m)))
    (is (= 1 (:yin.k/issued m)))
    (let [put (first (filter #(= :put (:reason %)) (:wait-set m)))]
      (is (= 0 (:yin.k/issue put)) "retained writes are numbered in carried order")
      (is (= (s/op-id 0) (:op-id put)) "the id travels verbatim"))
    (testing "the restored task relifts to the same root under the same header"
      (is (= (:yin.k/root wire)
             (:yin.k/root (rows/lift-task (dissoc m :yin.k/custody :yin.k/gate :yin.k/issued) (s/serve-table)
                                          (s/lift-opts (s/header 3 s/origin #{"s0"})))))))))


(deftest the-counter-is-restored-exactly
  (doseq [k [0 3 7 (dec rows/max-exact)]]
    (let [wire (f/edit-custody @successor #(assoc % :yin.k/next-op-seq (inc k)))
          wire (f/edit-task wire (fn [t] (f/update-put t #(f/set-op-id % (s/op-id k)))))
          [m _] (lowered wire (exclusive-opts wire #{"s0"}))]
      (is (= (inc k) (get-in m [:yin.k/custody :yin.k/next-op-seq])) (str k)))))


(deftest every-grant-check-refuses-before-any-attachment
  (let [wire @successor
        good (exclusive-opts wire #{"s0"})
        g #(update good :grant %)
        ev #(g (fn [x] (update x :evidence %)))]
    (doseq [[label opts status]
            [["no grant" (dissoc good :grant) :yin.k/awaiting-grant]
             ["no evidence" (g #(dissoc % :evidence)) :yin.k/awaiting-grant]
             ["evidence still awaiting" (ev #(assoc % :yin.k/status :yin.k/awaiting-grant)) :yin.k/awaiting-grant]
             ["evidence unavailable" (ev #(assoc % :yin.k/status :yin.k/unsatisfied)) :yin.k/unsatisfied]
             ["no input prefix" (ev #(dissoc % :yin.k/prefix)) :yin.k/unsatisfied]
             ["an unreadable prefix" (ev #(assoc-in % [:yin.k/prefix :yin.k/status] :yin.k/unsatisfied)) :yin.k/unsatisfied]
             ["another checkpoint: the root id is not the pack address"
              (g #(assoc % :checkpoint (:yin.k/root wire))) :yin.k/not-holder]
             ["a binding of another occurrence" (ev #(assoc-in % [:yin.k/binding :yin.k/occurrence] s/predecessor)) :yin.k/not-holder]
             ["another lease" (g #(assoc % :dao.lease/lease "lease-2")) :yin.k/not-holder]
             ["another holder" (g #(assoc % :dao.lease/holder "holder-b")) :yin.k/not-holder]
             ["another arbitration" (ev #(assoc-in % [:yin.k/binding :yin.k/transaction :yin.k/arbitration] "arb-x")) :yin.k/not-holder]
             ["an inexact epoch" (ev #(assoc-in % [:yin.k/binding :yin.k/epoch] -1)) :yin.k/not-holder]
             ["a lapsed tenure" (g #(assoc-in % [:tenure :live] false)) :yin.k/not-holder]
             ["a tenure past its bound" (g #(assoc-in % [:tenure :now] 10)) :yin.k/not-holder]
             ["a prefix of another occurrence" (ev #(assoc-in % [:yin.k/prefix :yin.k/occurrence] s/predecessor)) :yin.k/not-holder]
             ["a prefix of another lease" (ev #(assoc-in % [:yin.k/prefix :dao.lease/lease] "lease-2")) :yin.k/not-holder]
             ["a frontier beyond its records" (ev #(assoc-in % [:yin.k/prefix :yin.k/frontier] 1)) :yin.k/not-holder]
             ["a misnumbered record" (ev #(assoc % :yin.k/prefix {:yin.k/occurrence s/occurrence :dao.lease/lease "lease-1"
                                                                  :yin.k/frontier 1 :yin.k/inputs [{:yin.k/input-seq 1}]}))
              :yin.k/not-holder]]]
      (testing label
        (is (= status (:yin.k/status (refused wire opts))))))
    (testing "a malformed grant is a defect, not a refusal"
      (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
            (rows/lower-task (s/receiver) wire (:attach! (s/attacher)) (assoc good :grant :x))))
      (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
            (rows/lower-task (s/receiver) wire (:attach! (s/attacher)) (assoc good :protection nil)))))))


(deftest protection-is-checked-in-both-directions
  (let [wire @successor
        good (exclusive-opts wire #{"s0"})]
    (testing "a stream with no class"
      (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
             (refused wire (assoc good :protection {})))))
    (testing "a class saying enrolled for a stream the evidence does not enroll"
      (is (= :yin.k/unsatisfied
             (:yin.k/status (refused wire (assoc-in good [:grant :evidence :yin.k/enrolled] #{}))))))
    (testing "an enrolled stream classed otherwise"
      (is (= :yin.k/unsatisfied (:yin.k/status (refused wire (assoc good :protection {"s0" :fail-stop}))))))
    (testing "the same on a stream no retained write targets, so no op-id check masks it"
      (let [reader (f/base "first-park")]
        (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
               (refused reader (assoc (exclusive-opts reader #{"s0"}) :protection {"s0" :at-least-once}))))
        (is (vm/blocked? (first (lowered reader (exclusive-opts reader #{"s0"}))))
            "and lowers when the class agrees"))))
  (testing "a write carrying no id on an enrolled stream"
    (let [wire (s/lift (s/parked-writer) (s/header 3 s/origin #{}))]
      (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
             (refused wire (exclusive-opts wire #{"s0"}))))))
  (testing "a write carrying an id on a stream not enrolled"
    (let [wire @successor]
      (is (= {:yin.k/status :yin.k/unsatisfied :dao.stream/identity "s0"}
             (refused wire (assoc (exclusive-opts wire #{}) :protection {"s0" :at-least-once})))))))


(deftest the-grammar-runs-before-any-attachment
  (doseq [n ["op-id-at-counter" "custody-epoch" "missing-policy" "fork-op-id" "child-header"]]
    (testing n
      (is (= (select-keys (f/refusal n) [:yin.k/status :yin.k/kind :yin.k/path])
             (select-keys (refused (f/pack-of n) (exclusive-opts (f/pack-of n) #{"s0"}))
                          [:yin.k/status :yin.k/kind :yin.k/path]))))))


(deftest a-halted-exclusive-root-lowers-ended-with-no-grant
  (let [[m n] (lowered (f/base "halted-root") {})]
    (is (zero? n))
    (is (vm/halted? m))
    (is (= 42 (vm/value m)))
    (is (= :ended (:yin.k/gate m)))
    (is (nil? (:yin.k/custody m)))))


(deftest a-fork-is-refused-under-exclusive
  (let [wire (s/lift (s/parked-reader) nil)]
    (is (= {:yin.k/status :yin.k/profile-mismatch :yin.k/policy :yin.k/exclusive}
           (refused wire {:exclusive true})))
    (testing "and lowers as a fork without it: no custody, no gate"
      (let [[m n] (lowered wire {})]
        (is (pos? n))
        (is (vm/blocked? m))
        (is (nil? (:yin.k/custody m)))
        (is (nil? (:yin.k/gate m)))))))


(deftest an-exclusive-blocked-root-under-fenced-skips-the-grant
  (let [[m _] (lowered @successor {:fenced true})]
    (is (= :exporting (:yin.k/gate m)))
    (is (nil? (:yin.k/custody m)))
    (is (empty? (:wait-set m)) "its waits are outside the scheduler")
    (is (= [:put] (mapv :reason (:yin.k/recovery-waits m))))))


(deftest the-install-response-is-verified-before-any-attachment
  ;; continuation §11 Q3 (owner, 2026-10-13: lands in 7b): the response's
  ;; link id is the row's parent, and its image is the one the manifest
  ;; names, through the manifest's :yin.semantic/code derivation record
  (let [m (s/parked-installer)
        response (get-in m [:installs 'host.mod :response])
        other (s/linked-response (s/def! 'f (s/lam [] (s/lit 7))) 'host.mod #{'f})
        lift-with (fn [f] (s/lift (update-in m [:installs 'host.mod] f) nil))
        kind (fn [inst-f]
               (let [r (refused (lift-with inst-f) {})]
                 [(:yin.k/status r) (:yin.k/kind r) (:yin.k/name r)]))]
    (testing "an honest response lowers"
      (let [[r n] (lowered (s/lift m nil) {})]
        (is (pos? n))
        (is (vm/blocked? r))))
    (testing "a response of another link"
      (is (= [:yin.k/undecodable :install-link-id 'host.mod]
             (kind #(assoc-in % [:response :yin.link/id] [:other 9])))))
    (testing "no parent"
      (is (= [:yin.k/undecodable :install-link-id 'host.mod]
             (kind #(assoc % :parent nil)))))
    (testing "no derivation record"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(update % :response dissoc :derivation)))))
    (testing "a derivation record the manifest does not name"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(assoc-in % [:response :derivation] (:derivation other))))))
    (testing "a changed record with the honest input and output"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(assoc-in % [:response :derivation :yin.ledger/profile :x] 1)))))
    (testing "a manifest and record of another image than the one delivered"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(update % :response merge (select-keys other [:manifest :derivation]))))))
    (testing "a record from another tree than the manifest's"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(assoc-in % [:response :manifest :yin.module/tree]
                              (get-in other [:manifest :yin.module/tree]))))))
    (testing "the image the record names, delivered under another vector"
      (is (= [:yin.k/undecodable :install-image 'host.mod]
             (kind #(assoc-in % [:response :image] (:image other))))))
    (is (= response (get-in m [:installs 'host.mod :response])))))


(deftest install-store-addresses-verify-under-their-own-algorithms
  (doseq [[image-algorithm record-algorithm] [[:sha256 :blake3] [:blake3 :sha256] [:sha256 :sha256]]]
    (let [m (s/parked-installer)
          response (get-in m [:installs 'host.mod :response])
          image-address (jing/segment-key (get-in response [:image :value]) {:algorithm image-algorithm})
          record (assoc (:derivation response) :yin.ledger/output image-address)
          record-address (jing/segment-key record {:algorithm record-algorithm})
          response (-> response
                       (assoc :derivation record)
                       (assoc-in [:manifest :yin.module/derivations :yin.semantic/code] record-address))
          wire (s/lift (assoc-in m [:installs 'host.mod :response] response) nil)
          [back n] (lowered wire {})]
      (is (pos? n))
      (is (vm/blocked? back))
      (is (= response (get-in back [:installs 'host.mod :response])))
      (let [bad (s/lift (assoc-in m [:installs 'host.mod :response]
                                  (assoc-in response [:derivation :yin.ledger/output]
                                            (jing/segment-key [[:lit :wrong]] {:algorithm image-algorithm}))) nil)]
        (is (= :install-image (:yin.k/kind (refused bad {}))))))))


(deftest install-children-lower-under-their-roots-custody
  (let [wire (f/base "installs")
        [m n] (lowered wire (exclusive-opts wire #{"s0"}))
        child (get-in m [:installs 'host.mod :vm])]
    (is (pos? n) (pr-str (select-keys m [:yin.k/status :yin.k/kind])))
    (is (= 6 (get-in m [:yin.k/custody :yin.k/next-op-seq])))
    (is (= :running (:yin.k/gate child)))
    (is (nil? (:yin.k/custody child)) "the child holds no custody of its own")
    (is (= [(s/op-id 4)] (keep :op-id (:wait-set child))))))
