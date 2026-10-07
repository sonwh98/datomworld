(ns yin.vm.ucf.handoff-v2-custody-test
  "UCF version 2, section 11 row 8: custody.  All four profiles admit
   both root modes.  An exclusive runnable root lowers only with the D10
   inputs: the seven grant checks, protection against enrollment in both
   directions, and an operation id on every enrolled retained write and
   none elsewhere.  The root counter is restored exactly, install
   children carry the running gate and no custody, and an exclusive
   result is ended with neither grant nor custody.  No receiver default
   supplies missing evidence."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.lift-support :as support]
            [yin.vm.ucf.v2-support :as s]))


(defn- lift-exclusive
  "The exclusive lift of `machine` under `header` over a deterministic
   served table: identities s0, s1, ...  Answers the export."
  [machine header]
  (handoff/export-task machine (support/serve-table)
                       {:version 2 :header header}))


(defn- lower!
  "Lower `bytes` into a fresh `engine` receiver over counting local
   attachments, under `opts`.  Answers [outcome attaches]."
  [engine bytes opts]
  (let [attached (atom {})
        attach! (fn [descriptor]
                  (let [handle (or (get @attached descriptor)
                                   (support/one-slot-stream
                                     (str (count @attached))))]
                    (swap! attached assoc descriptor handle)
                    {:dao.stream/outcome :dao.stream/ok
                     :dao.stream/handle handle}))]
    [(handoff/resume-task (s/new-machine engine {:attach-stream attach!})
                          bytes attach! opts)
     (count @attached)]))


(deftest the-seven-grant-checks-refuse-with-nothing-attached
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[parked _] (s/parked-reader engine)
            export (lift-exclusive parked (s/header 3 s/origin #{}))
            bytes (:bytes export)
            opts (s/lower-options bytes)
            occurrence (get-in opts [:grant :evidence :yin.k/binding
                                     :yin.k/occurrence])]
        (is (= :ok (:status export)) (pr-str export))
        (let [[r n] (lower! engine bytes {:address (:address export)
                                          :exclusive true})]
          (is (= :yin.k/awaiting-grant (:yin.k/status r)))
          (is (zero? n)))
        (doseq [[path value]
                [[[:grant :checkpoint] :segment/other]
                 [[:grant :evidence :yin.k/binding :yin.k/occurrence]
                  s/predecessor]
                 [[:grant :evidence :yin.k/binding :dao.lease/lease] "wrong"]
                 [[:grant :evidence :yin.k/binding :dao.lease/holder] "wrong"]
                 [[:grant :evidence :yin.k/binding :yin.k/transaction
                   :yin.k/arbitration] "wrong"]
                 [[:grant :evidence :yin.k/binding :yin.k/epoch] -1]
                 [[:grant :tenure :now] 20]
                 [[:grant :tenure :live] false]
                 [[:grant :evidence :yin.k/prefix :dao.lease/lease] "wrong"]
                 [[:grant :evidence :yin.k/prefix :yin.k/occurrence]
                  s/predecessor]
                 [[:grant :evidence :yin.k/prefix :yin.k/frontier] 1]]]
          (testing (pr-str path)
            (let [[r n] (lower! engine bytes (assoc-in opts path value))]
              (is (= :yin.k/not-holder (:yin.k/status r)) (pr-str r))
              (is (zero? n) "no stream was attached")
              (is (not (contains? r :vm))))))
        (let [[r n] (lower! engine bytes
                            (assoc-in opts [:grant :evidence]
                                      {:yin.k/status :yin.k/unsatisfied
                                       :yin.k/reason :gap}))]
          (is (= :yin.k/unsatisfied (:yin.k/status r)))
          (is (zero? n)))
        (let [[r n] (lower! engine bytes opts)
              recv (:vm r)]
          (is (= :ok (:status r)) (pr-str r))
          (is (pos? n))
          (is (= :running (:yin.k/gate recv)))
          (is (= 3 (get-in recv [:yin.k/custody :yin.k/next-op-seq]))
              "the root counter is restored exactly")
          (is (= occurrence (get-in recv [:yin.k/custody :yin.k/occurrence]))))))))


(deftest protection-and-enrollment-agree-in-both-directions
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[parked _] (s/parked-reader engine)
            export (lift-exclusive parked (s/header 3 s/origin #{}))
            bytes (:bytes export)
            opts (s/lower-options bytes)
            identity (first (keys (:protection opts)))]
        (testing "a stream with no declared protection class"
          (let [[r n] (lower! engine bytes
                              (update opts :protection dissoc identity))]
            (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
            (is (zero? n))))
        (testing "a class enrolled for a stream the evidence does not enroll"
          (let [[r n] (lower! engine bytes
                              (assoc-in opts [:protection identity] :enrolled))]
            (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
            (is (zero? n))))
        (testing "an enrolled stream the declaration does not enroll"
          (let [[r n] (lower! engine bytes
                              (assoc-in opts [:grant :evidence
                                              :yin.k/enrolled]
                                        #{identity}))]
            (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
            (is (zero? n))))))))


(deftest an-operation-id-rides-exactly-the-enrolled-retained-writes
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[parked _] (s/parked-writer engine)
            id {:yin.k/occurrence s/predecessor :yin.k/seq 1}
            carrying (support/with-op-id parked :put id)
            enrolled (lift-exclusive carrying (s/header 3 s/origin #{"s0"}))
            plain (lift-exclusive parked (s/header 3 s/origin #{}))
            id-on-unenrolled (lift-exclusive carrying
                                             (s/header 3 s/origin #{}))
            unprotected (lift-exclusive parked
                                        (s/header 3 s/origin #{"s0"}))]
        (is (= :ok (:status enrolled)) (pr-str enrolled))
        (is (= id (:yin.k/op-id (s/frame-pending enrolled))))
        (is (= :ok (:status plain)) (pr-str plain))
        (is (not (contains? (s/frame-pending plain) :yin.k/op-id)))
        (is (= :yin.k/unsatisfied (:yin.k/status id-on-unenrolled))
            "an id on an unenrolled target is refused")
        (is (= :yin.k/non-portable (:yin.k/status unprotected)))
        (is (= :unprotected-pending (:yin.k/kind unprotected)))
        (testing "the enrolled write lowers carrying its id"
          (let [opts (-> (s/lower-options (:bytes enrolled))
                         (assoc-in [:protection "s0"] :enrolled)
                         (assoc-in [:grant :evidence :yin.k/enrolled]
                                   #{"s0"}))
                [r _] (lower! engine (:bytes enrolled) opts)]
            (is (= :ok (:status r)) (pr-str r))
            (is (= id (:op-id (first (:wait-set (:vm r))))))))
        (testing "protection that disagrees with the id refuses"
          (let [opts (s/lower-options (:bytes enrolled))
                [r n] (lower! engine (:bytes enrolled) opts)]
            (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
            (is (zero? n))))))))


(deftest install-children-carry-the-running-gate-and-no-custody
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[parked _] (s/parked-installer engine)
            export (lift-exclusive parked (s/header 0 nil #{}))
            [r _] (lower! engine (:bytes export)
                          (s/lower-options (:bytes export)))
            recv (:vm r)
            child (get-in recv [:installs 'host.mod :vm])]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :ok (:status r)) (pr-str r))
        (is (some? (:yin.k/custody recv)))
        (is (= :running (:yin.k/gate child)))
        (is (nil? (:yin.k/custody child)))
        (is (not-any? #(contains? (get-in export [:body :yin.k/installs
                                                  'host.mod :yin.k/child])
                                  %)
                      [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                       :yin.k/origin :yin.k/next-op-seq]))))))


(deftest an-exclusive-result-is-ended-with-no-grant-or-custody
  (doseq [engine s/engines]
    (testing (name engine)
      (let [halted (s/halted-with engine (s/lit 42))
            export (lift-exclusive halted {:yin.k/origin s/origin
                                           :yin.k/occurrence s/occurrence
                                           :yin.k/arbitration s/arbitration
                                           :yin.k/next-op-seq 3
                                           :yin.k/enrolled #{}})
            body (:body export)
            [r n] (lower! engine (:bytes export)
                          {:address (:address export) :exclusive true})
            recv (:vm r)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= #{:yin.k/origin}
               (set (filter #(contains? body %)
                            [:yin.k/policy :yin.k/occurrence
                             :yin.k/arbitration :yin.k/origin
                             :yin.k/next-op-seq]))))
        (is (= :ok (:status r)) (pr-str r))
        (is (= :ended (:yin.k/gate recv)))
        (is (nil? (:yin.k/custody recv)))
        (is (= 42 (:value recv)))
        (is (some? n))))))
