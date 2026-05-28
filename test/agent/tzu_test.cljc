(ns agent.tzu-test
  (:require
    [agent.tzu :as tzu]
    [clojure.test :refer [deftest is testing]]))


(defn- completion
  [responses]
  (let [calls (atom [])]
    {:calls calls,
     :client (fn [prompt]
               (let [idx (count @calls)
                     response (nth responses idx)]
                 (swap! calls conj prompt)
                 response))}))


(deftest ask-uses-explicit-completion-client-test
  (testing "completion IO is supplied as data at the runtime boundary"
    (let [{:keys [calls client]} (completion [{:content "answer"}])]
      (binding [tzu/*completion-client* client]
        (is (= {:content "answer"} (tzu/ask* "question")))
        (is (= ["question"] @calls))))))


(deftest text-to-datoms-reads-completion-edn-test
  (testing
    "text extraction keeps prompt construction separate from completion IO"
    (let [{:keys [calls client]}
          (completion [{:content "```edn\n[[-1 :name \"Linda\"]]\n```"}])]
      (binding [tzu/*completion-client* client]
        (is (= [[-1 :name "Linda"]] (tzu/text->datoms "Linda exists." 500)))
        (is (= 1 (count @calls)))
        (is (string? (first @calls)))))))


(deftest datoms-to-text-uses-completion-client-test
  (testing "prose reconstruction consumes the same explicit completion stream"
    (let [{:keys [client]} (completion [{:content "Linda exists."}])]
      (binding [tzu/*completion-client* client]
        (is (= "Linda exists." (tzu/datoms->text [[-1 :name "Linda"]])))))))


(deftest prompt-to-frames-reads-completion-edn-test
  (testing "frame generation parses EDN returned by the completion stream"
    (let [frames [[{:op/kind :frame/clear, :color [0.0 0.0 0.0 1.0]}]]
          {:keys [client]} (completion [{:content (str "```clojure\n"
                                                       (pr-str frames)
                                                       "\n```")}])]
      (binding [tzu/*completion-client* client]
        (is (= frames (tzu/prompt->frames "black frame")))))))


(deftest datoms-to-tx-data-test
  (testing "plain datom triples become dao.db transaction ops"
    (is (= [[:db/add -1 :name "Linda"] [:db/add -1 :created-in-year 1986]]
           (tzu/datoms->tx-data [[-1 :name "Linda"]
                                 [-1 :created-in-year 1986]])))))
