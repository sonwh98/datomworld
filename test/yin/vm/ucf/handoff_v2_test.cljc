(ns yin.vm.ucf.handoff-v2-test
  "UCF version 2 (docs/design/yin.vm.universal-continuation-format.
   v2-amendment.md, section 11): lift and lower of the four execution
   profiles over real source machines, fork and exclusive.  Each row is
   setup, action, assertion over canonical bytes; refusals assert the
   whole observable -- the data outcome with its path, zero attach
   calls and no machine."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.ucf :as ucf]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.v2-support :as s]))


(def ^:private engines s/engines)
(def ^:private lift s/lift)
(def ^:private read! s/read!)
(def ^:private deliver-next s/deliver-next)
(def ^:private frame-pending s/frame-pending)


(deftest a-fork-blocked-reader-round-trips-in-every-profile
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked src] (s/parked-reader engine)
            export (lift parked t peer)
            body (:body export)
            reference (do (stream/append! src "B")
                          (vm/value (s/drive-local parked)))
            [r attaches] (read! engine t (:bytes export)
                                {:address (:address export)})]
        (is (= :ok (:status export)) (pr-str export))
        (is (= 2 (:yin.k/version body)))
        (is (= (get ucf/profiles engine) (:yin.k/contract body)))
        (is (not-any? #(contains? body %)
                      [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                       :yin.k/origin :yin.k/next-op-seq])
            "a fork carries no custody header key")
        (is (= :ok (:status r)) (pr-str r))
        (is (pos? attaches))
        (is (= reference (vm/value (s/drive (:vm r) peer)))
            "the lowered task answers what the source's own run did")))))


(deftest an-exclusive-blocked-reader-needs-its-grant-and-then-runs
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked src] (s/parked-reader engine)
            export (lift parked t peer (s/header))
            body (:body export)
            reference (do (stream/append! src "B")
                          (vm/value (s/drive-local parked)))
            options (s/lower-options (:bytes export))
            [missing n0] (read! engine t (:bytes export)
                                {:address (:address export)
                                 :exclusive true})
            [r _] (read! engine t (:bytes export) options)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :yin.k/exclusive (:yin.k/policy body)))
        (is (= :yin.k/awaiting-grant (:yin.k/status missing)))
        (is (zero? n0) "nothing was attached before the grant")
        (is (= :ok (:status r)) (pr-str r))
        (is (= :running (:yin.k/gate (:vm r))))
        (is (= reference (vm/value (deliver-next (:vm r) "B")))
            "a controlled engine apply and normal scheduling continue the run")))))


(deftest a-fork-is-refused-by-an-exclusive-only-receiver
  (doseq [engine engines]
    (let [t (s/toy)
          peer (s/served-peer t)
          [parked _] (s/parked-reader engine)
          export (lift parked t peer)
          [r n] (read! engine t (:bytes export)
                       {:address (:address export) :exclusive true})]
      (is (= :yin.k/profile-mismatch (:yin.k/status r)) (pr-str r))
      (is (zero? n))
      (is (not (contains? r :vm))))))


(deftest an-explicit-park-and-a-halt-round-trip
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            parked (s/parked-explicit engine)
            export (lift parked t peer)
            halted (lift (s/halted-with engine (s/lit 42)) t peer)
            [rp _] (read! engine t (:bytes export)
                          {:address (:address export)})
            [rh _] (read! engine t (:bytes halted)
                          {:address (:address halted)})]
        (is (= :parked (:kind export)) (pr-str export))
        (is (= :ok (:status rp)) (pr-str rp))
        (is (= :parked (:kind rp)))
        (is (= :halted (:kind halted)) (pr-str halted))
        (is (= 42 (vm/value (:vm rh))))))))


(deftest a-blocked-write-retries-its-retained-value-in-every-profile
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked w] (s/parked-writer engine)
            export (lift parked t peer nil #{:reader :writer})
            reference (do (stream/next w 0)
                          (vm/value (s/drive-local parked)))
            [r _] (read! engine t (:bytes export)
                         {:address (:address export)})
            done (s/drive (:vm r) peer)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :put (:yin.k/reason (frame-pending export))))
        (is (= "v" (:yin.k/value (frame-pending export))))
        (is (= :ok (:status r)) (pr-str r))
        (is (= reference (vm/value done))
            "the retry appends the retained value through the reflection")
        (is (= "v" (:dao.stream/value (stream/next w 0))))))))


(deftest a-sent-ffi-call-keeps-its-cell-and-correlation-in-every-profile
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked call-in call-out] (s/parked-sent-caller engine)
            export (lift parked t peer)
            call-id (:yin.k/call-id (frame-pending export))
            reference (do (stream/append! call-out
                                          (apply2/success-response
                                            call-id "echo!"))
                          (vm/value (s/drive-local parked)))
            after-reference (count (tu/drain call-in))
            own-in (s/ring 8)
            own-out (s/ring 8)
            attaches (atom 0)
            base (s/attacher t)
            attach! (fn [d] (swap! attaches inc) (base d))
            r (handoff/resume-task
                (s/calling-machine engine own-in own-out)
                (:bytes export) attach! {:address (:address export)})
            done (when (:vm r) (s/drive (:vm r) peer))]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :ffi (:yin.k/reason (frame-pending export))))
        (is (some? call-id))
        (is (= :ok (:status r)) (pr-str r))
        (is (= reference (vm/value done)))
        (is (= after-reference (count (tu/drain call-in)))
            "no sent request is ever reissued")))))


(deftest a-retained-ffi-request-retries-its-envelope-verbatim-in-every-profile
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked call-in _] (s/parked-retained-caller engine)
            entry (first (:wait-set parked))
            export (lift parked t peer nil #{:reader :writer})
            _ (do (stream/next call-in 0)
                  (s/drive-local parked))
            [r _] (read! engine t (:bytes export)
                         {:address (:address export)})
            done (s/drive (:vm r) peer)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :ffi-request (:yin.k/reason (frame-pending export))))
        (is (= :ok (:status r)) (pr-str r))
        (is (vm/blocked? done)
            "the retry stays parked: the emitter's bridge never answered")
        (let [landed (stream/next call-in 0)]
          (is (= :dao.stream/ok (:dao.stream/outcome landed)))
          (is (= (:datom entry) (:dao.stream/value landed))
              "the identical envelope, appended through the reflection"))))))


(deftest link-waits-cross-in-every-profile
  (doseq [engine engines
          [label make reason] [["retained request" s/parked-link-request
                                :link-request]
                               ["response poll" s/parked-link-response
                                :link-response]]]
    (testing (str (name engine) " " label)
      (let [t (s/toy)
            peer (s/served-peer t)
            parked (make engine)
            export (lift parked t peer nil #{:reader :writer})
            [r _] (when (= :ok (:status export))
                    (read! engine t (:bytes export)
                           {:address (:address export)}))]
        (is (= :ok (:status export)) (pr-str export))
        (is (= reason (:yin.k/reason (frame-pending export))))
        (is (= :ok (:status r)) (pr-str r))
        (is (= reason (:reason (first (:wait-set (:vm r))))))))))


(deftest an-install-waiter-crosses-beside-its-whole-child-in-every-profile
  (doseq [engine engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked child-stream] (s/parked-installer engine)
            export (lift parked t peer)
            attaches (atom 0)
            base (s/attacher t)
            attach! (fn [d] (swap! attaches inc) (base d))
            r (handoff/resume-task
                (s/new-machine engine {:attach-stream attach!})
                (:bytes export) attach! {:address (:address export)})
            recv (:vm r)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :install (:yin.k/reason (frame-pending export))))
        (let [child (get-in export [:body :yin.k/installs 'host.mod])]
          (is (= 2 (:yin.k/version (:yin.k/child child))))
          (is (= (get-in export [:body :yin.k/contract])
                 (get-in child [:yin.k/child :yin.k/contract]))
              "the child rides the root's profile")
          (is (= :blocked (:yin.k/kind (:yin.k/child child)))))
        (is (= :ok (:status r)) (pr-str r))
        (is (contains? (:installs recv) 'host.mod))
        (stream/append! child-stream "go")
        (let [done (s/drive recv peer)]
          (is (not (vm/blocked? done)) (pr-str (:wait-set done)))
          (is (= 42 (vm/value done)))
          (is (empty? (:installs done))))))))
