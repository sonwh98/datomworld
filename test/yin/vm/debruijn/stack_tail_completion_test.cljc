(ns yin.vm.debruijn.stack-tail-completion-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]))


(defn- parked-call
  [tail?]
  (vm/run
    (stack/create-vm
      [[:load-free 'stream/next!] [:stream-make 4] [:stream-cursor]
       [:call 1 tail?] [:push] [:const :layout] [:halt]]
      {:primitives vm/primitives
       :make-stream tu/make-stream :capability-secret tu/secret
       :modules (module/register-stream-module (module/default-registry))
       :contract vm/stack-contract})))


(deftest tail-park-is-marked
  (let [parked (parked-call true)]
    (is (vm/blocked? parked))
    (is (= :return-result (:resume-mode (first (:wait-set parked)))))))


(deftest empty-k-halts-with-result
  (let [parked (parked-call true)
        entry (first (:wait-set parked))]
    (stream/append! (get (:resources parked) (:stream-id entry)) nil)
    (let [done (vm/run parked)]
      (is (vm/halted? done))
      (is (= [nil] (:stack done)))
      (is (nil? (vm/value done)))
      (is (nil? (:store-of done))))))


(defn- frame-entry
  [parked]
  (assoc (first (:wait-set parked))
         :stack [:outer :caller :dead]
         :frames [[:dead]] :store-of :dead-module
         :continuation [{:return-pc 6 :frames [[:outer]] :stack-base 1}
                        {:return-pc 4 :frames [[:caller]] :stack-base 2
                         :store-of :caller-module}]))


(deftest return-pops-only-inner-frame
  (let [parked (parked-call true)
        done (stack/stack-restore parked (frame-entry parked) :result)]
    (is (= 1 (count (:continuation done))))
    (is (= 4 (:pc done)))
    (is (false? (:halted? done)))))


(deftest return-truncates-at-caller-base
  (let [parked (parked-call true)
        done (stack/stack-restore parked (frame-entry parked) nil)]
    (is (= [:outer :caller nil] (:stack done)))))


(deftest return-restores-caller-environment
  (let [parked (parked-call true)
        done (stack/stack-restore parked (frame-entry parked) :result)]
    (is (= [[:caller]] (:frames done)))
    (is (= :caller-module (:store-of done)))))


(deftest tail-in-operand-position-skips-layout
  (let [image [[:const :outer] [:closure 0 5] [:call 0 false] [:halt]
               [:halt] [:load-free '+] [:load-free 'stream/next!]
               [:stream-make 4] [:stream-cursor] [:call 1 true]
               [:push] [:const 100] [:call 2 false] [:return]]
        parked (vm/run (stack/create-vm image
                                        {:primitives vm/primitives
                                         :make-stream tu/make-stream
                                         :capability-secret tu/secret
                                         :modules (module/register-stream-module
                                                    (module/default-registry))
                                         :contract vm/stack-contract}))
        entry (first (:wait-set parked))]
    (is (= 10 (:pc entry)))
    (is (= [:outer] (subvec (:stack entry) 0 1)))
    (stream/append! (get (:resources parked) (:stream-id entry)) 7)
    (let [done (vm/run parked)]
      (is (= 7 (vm/value done)))
      (is (= [:outer 7] (:stack done))))))


(deftest non-tail-park-keeps-successor
  (let [parked (parked-call false)
        entry (first (:wait-set parked))]
    (is (not (contains? entry :resume-mode)))
    (stream/append! (get (:resources parked) (:stream-id entry)) :result)
    (let [done (vm/run parked)]
      (is (= [:result :layout] (:stack done)))
      (is (= :layout (vm/value done))))))


(deftest retained-ffi-reparks-before-return
  (let [parked (parked-call true)
        entry (assoc (frame-entry parked) :request-sent true :call-id :call-1)
        done (stack/stack-restore parked entry :append-ack)
        reader (peek (:wait-set done))]
    (is (vm/blocked? done))
    (is (= :return-result (:resume-mode reader)))
    (is (= (:continuation entry) (:continuation reader)))
    (is (not (contains? reader :request-sent)))))


(deftest ffi-response-decodes-before-return
  (let [parked (parked-call true)
        entry (assoc (frame-entry parked) :call-id :call-1)
        done (stack/stack-restore (assoc parked :parked {:call-1 entry})
                                  entry (apply2/success-response :call-1 nil))]
    (is (= [:outer :caller nil] (:stack done)))
    (is (empty? (:parked done)))))
