(ns yin.vm.ucf.handoff-v2-gates-test
  "UCF version 2, section 11 row 1: the profile and version gates.  A
   valid body is mutated -- its outer version, its profile combination,
   a child's version or profile -- and lowered with counting attachments;
   each refusal is the specified one and attaches nothing, assembles no
   machine and runs nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [yin.vm.ucf :as ucf]
            [yin.vm.ucf.v2-support :as s]))


(defn- refused-bytes
  "`bytes` lowered into a fresh `engine` receiver under `opts` refuse with
   `status` and exactly the `data` keys given, attaching nothing and
   assembling no machine."
  [engine t bytes opts status data]
  (let [[r n] (s/read! engine t bytes opts)]
    (is (= status (:yin.k/status r)) (pr-str r))
    (is (= data (select-keys r (keys data))) (pr-str r))
    (is (zero? n) "no stream was attached")
    (is (not (contains? r :vm)) "no machine was assembled")
    r))


(defn- unsupported
  [engine]
  {:yin.k/engine engine :yin.code/contract "nope" :yin.k/version 1})


(deftest the-version-and-profile-gates-refuse-before-any-attachment
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked _] (s/parked-reader engine)
            export (s/lift parked t peer)
            body (s/decoded export)
            other (first (remove #{engine} s/engines))
            enc jing.cbor/encode]
        (testing "an unsupported outer version"
          (refused-bytes engine t (enc (assoc body :yin.k/version 3)) nil
                         :yin.k/profile-mismatch {:yin.k/version 3}))
        (testing "an absent outer version"
          (refused-bytes engine t (enc (dissoc body :yin.k/version)) nil
                         :yin.k/profile-mismatch {:yin.k/version nil}))
        (testing "an unsupported profile combination"
          (refused-bytes engine t
                         (enc (assoc body :yin.k/contract
                                     (unsupported engine)))
                         nil :yin.k/profile-mismatch
                         {:yin.k/path [:yin.k/contract]
                          :yin.k/contract (unsupported engine)}))
        (testing "a missing profile declaration"
          (refused-bytes engine t (enc (dissoc body :yin.k/contract)) nil
                         :yin.k/profile-mismatch
                         {:yin.k/path [:yin.k/contract]}))
        (testing "an integral float is no inner version"
          (refused-bytes engine t
                         (enc (assoc body :yin.k/contract
                                     (assoc (get ucf/profiles engine)
                                            :yin.k/version
                                            (jing.cbor/float64 1))))
                         nil :yin.k/profile-mismatch
                         {:yin.k/path [:yin.k/contract]}))
        (testing "a bad profile and a bad code hash: the profile first"
          (let [bad (update body :yin.k/code
                            (fn [c]
                              (into {} (map (fn [[_ v]] [:segment/bad v])) c)))]
            (refused-bytes engine t
                           (enc (assoc bad :yin.k/contract
                                       (unsupported engine)))
                           nil :yin.k/profile-mismatch
                           {:yin.k/path [:yin.k/contract]})))
        (testing "a supported profile the body's content does not match"
          (let [[r n] (s/read! engine t
                               (enc (assoc body :yin.k/contract
                                           (get ucf/profiles other)))
                               nil)]
            (is (contains? #{:yin.k/profile-mismatch :yin.k/undecodable
                             :yin.k/hash-mismatch}
                           (:yin.k/status r))
                (pr-str r))
            (is (zero? n))
            (is (not (contains? r :vm)))))
        (testing "a body lowered by a receiver of another profile"
          (let [[r n] (s/read! other t (:bytes export)
                               {:address (:address export)})]
            (is (= :yin.k/profile-mismatch (:yin.k/status r)) (pr-str r))
            (is (zero? n))
            (is (not (contains? r :vm)))))))))


(deftest an-install-child-of-another-version-or-profile-refuses
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked _] (s/parked-installer engine)
            export (s/lift parked t peer)
            body (s/decoded export)
            child-path [:yin.k/installs 'host.mod :yin.k/child]
            other (first (remove #{engine} s/engines))
            enc jing.cbor/encode]
        (testing "a child of version 1 is a mixed tree, undecodable"
          (refused-bytes engine t
                         (enc (assoc-in body (conj child-path :yin.k/version)
                                        1))
                         nil :yin.k/undecodable
                         {:yin.k/path (conj child-path :yin.k/version)
                          :yin.k/kind :mixed-version}))
        (testing "a child with an unsupported profile is a profile mismatch"
          (refused-bytes engine t
                         (enc (assoc-in body (conj child-path :yin.k/contract)
                                        (unsupported engine)))
                         nil :yin.k/profile-mismatch
                         {:yin.k/path (conj child-path :yin.k/contract)}))
        (testing "a child of a supported but different profile is undecodable"
          (refused-bytes engine t
                         (enc (assoc-in body (conj child-path :yin.k/contract)
                                        (get ucf/profiles other)))
                         nil :yin.k/undecodable
                         {:yin.k/path (conj child-path :yin.k/contract)
                          :yin.k/kind :mixed-profile}))))))
