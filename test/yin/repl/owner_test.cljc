(ns yin.repl.owner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [yin.repl.driver :as driver]
            [yin.repl.main :as main]
            #?@(:cljd [] :jolt [[dao.stream.waitset.driver :as wake]] :default [])))


(deftest serial-owner-backs-off-publishes-once-and-exits
  (let [state (main/boot {})
        sleeps (atom []) exit (atom nil)
        output (with-out-str
                 (driver/submit-line! (:input state) "(do (println \"once\") 42)")
                 (main/run-owner!
                   state nil false (constantly 0)
                   (fn [ms]
                     (swap! sleeps conj ms)
                     (when (= 8 (count @sleeps))
                       (driver/submit-line! (:input state) "(quit)")))
                   #(reset! exit %)))]
    (is (= 0 @exit))
    (is (= 1 (count (re-seq #"once" output))))
    (is (= 1 (count (re-seq #"42" output))))
    (is (= [25 25 50 100 200 200 200 200] @sleeps))
    (is (str/includes? output "Bye"))))


#?(:jolt
   (deftest wake-tokens-coalesce-and-the-deadline-expires
     (let [w (wake/make-wake)]
       (try
         (dotimes [_ 100] (wake/nudge! w))
         (is (true? (wake/sleep! w 100)))
         (is (false? (wake/sleep! w 10)))
         (finally (wake/close! w))))))
