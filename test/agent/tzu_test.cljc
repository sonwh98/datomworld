(ns agent.tzu-test
  (:require
    [agent.tzu :as tzu]
    [clojure.test :refer [deftest is testing]]
    #?(:clj [dao.stream :as ds])))


;; `prompt` blocks on the stream (ds/take!!), so it is JVM-only; mock the
;; DeepSeek
;; IO with-redefs (unavailable on ClojureDart).
#?(:clj (defn- response-stream
          [resp]
          (doto (ds/open! {:type :ringbuffer, :capacity 1})
            (ds/put! resp)
            ds/close!)))


#?(:clj (deftest prompt-returns-content-test
          (testing
            "prompt reads the stream, decodes JSON, returns message content"
            (let [resp (response-stream
                         {:status 200,
                          :body
                          (str "{\"choices\":[{\"message\":{\"content\":"
                               "\"answer\"},\"finish_reason\":\"stop\"}]}")})]
              (with-redefs [ds/open! (constantly resp)]
                (is (= "answer" (tzu/prompt "question" "fake-key"))))))))


#?(:clj (deftest prompt-throws-test
          (testing "transport error, non-200, and truncated responses all throw"
            (let [cut (str "{\"choices\":[{\"message\":{\"content\":\"part\"},"
                           "\"finish_reason\":\"length\"}]}")]
              (with-redefs [ds/open! (constantly (response-stream
                                                   {:status 0,
                                                    :error {:kind :timeout}}))]
                (is (thrown? Exception (tzu/prompt "q" "k"))))
              (with-redefs [ds/open! (constantly (response-stream {:status 500,
                                                                   :body
                                                                   "nope"}))]
                (is (thrown? Exception (tzu/prompt "q" "k"))))
              (with-redefs [ds/open! (constantly (response-stream {:status 200,
                                                                   :body cut}))]
                (is (thrown? Exception (tzu/prompt "q" "k"))))))))


#?(:clj (deftest text-to-datoms-reads-edn-test
          (testing "text extraction parses the EDN the model returns"
            (let [calls (atom [])]
              (with-redefs [tzu/prompt (fn [p]
                                         (swap! calls conj p)
                                         "```edn\n[[-1 :name \"Linda\"]]\n```")]
                (is (= [[-1 :name "Linda"]]
                       (tzu/text->datoms "Linda exists." 500)))
                (is (= 1 (count @calls)))
                (is (string? (first @calls))))))))


#?(:clj (deftest datoms-to-text-test
          (testing "prose reconstruction returns the model content"
            (with-redefs [tzu/prompt (fn [_] "Linda exists.")]
              (is (= "Linda exists."
                     (tzu/datoms->text [[-1 :name "Linda"]])))))))


#?(:clj (deftest prompt-to-frames-reads-edn-test
          (testing "frame generation parses the EDN the model returns"
            (let [frames [[{:op/kind :frame/clear, :color [0.0 0.0 0.0 1.0]}]]]
              (with-redefs [tzu/prompt
                            (fn [_]
                              (str "```clojure\n" (pr-str frames) "\n```"))]
                (is (= frames (tzu/prompt->frames "black frame"))))))))


(deftest datoms-to-tx-data-test
  (testing "plain datom triples become dao.db transaction ops"
    (is (= [[:db/add -1 :name "Linda"] [:db/add -1 :created-in-year 1986]]
           (tzu/datoms->tx-data [[-1 :name "Linda"]
                                 [-1 :created-in-year 1986]])))))
