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


;; =============================================================================
;; chat-completion tests
;; =============================================================================


#?(:clj (deftest chat-completion-returns-parsed-response-test
          (testing "chat-completion sends messages and returns parsed JSON body"
            (let [resp (response-stream
                         {:status 200,
                          :body (str
                                  "{\"choices\":[{\"message\":{\"content\":"
                                  "\"hello\"},\"finish_reason\":\"stop\"}]}")})]
              (with-redefs [ds/open! (constantly resp)]
                (is (= "hello"
                       (get-in (tzu/chat-completion [{"role" "user",
                                                      "content" "hi"}]
                                                    :key
                                                    "fake")
                               ["choices" 0 "message" "content"]))))))))


#?(:clj (deftest chat-completion-includes-tools-test
          (testing "chat-completion includes tools in the request body"
            (let [sent-body (atom nil)
                  resp (response-stream
                         {:status 200,
                          :body (str "{\"choices\":[{\"message\":{\"content\":"
                                     "\"ok\"},\"finish_reason\":\"stop\"}]}")})]
              (with-redefs [ds/open!
                            (fn [desc] (reset! sent-body (:body desc)) resp)]
                (tzu/chat-completion [{"role" "user", "content" "hi"}]
                                     :tools [{"type" "function",
                                              "function" {"name" "test_tool"}}]
                                     :key "fake")
                (is (.contains ^String @sent-body "test_tool"))
                (is (.contains ^String @sent-body "\"tools\"")))))))


;; =============================================================================
;; run-agent / tool execution tests
;; =============================================================================


#?(:clj (deftest execute-tool-call-stream-write-test
          (testing "stream_write tool call invokes ds/put! on the right stream"
            (let [s (ds/open! {:type :ringbuffer, :capacity 5})
                  registry {"target" s}
                  tool-call
                  {"id" "call_1",
                   "type" "function",
                   "function"
                   {"name" "stream_write",
                    "arguments"
                    "{\"stream_id\":\"target\",\"value\":\"42\"}"}}
                  result (tzu/execute-tool-call tool-call registry)]
              (is (= {"role" "tool", "tool_call_id" "call_1", "content" "ok"}
                     result))
              (let [next-result (ds/next s {:position 0})]
                (is (map? next-result))
                (is (= 42 (:ok next-result))))))))


#?(:clj (deftest execute-tool-call-stream-read-test
          (testing "stream_read tool call reads from the right stream"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 5})
                      (ds/put! :hello))
                  registry {"input" s}
                  tool-call {"id" "call_2",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"input\",\"position\":0}"}}
                  result (tzu/execute-tool-call tool-call registry)]
              (is (= {"role" "tool",
                      "tool_call_id" "call_2",
                      "content" (pr-str {:value :hello, :next-position 1})}
                     result))))))


#?(:clj (deftest execute-tool-call-unknown-tool-test
          (testing "unknown tool returns error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 1})
                  tool-call {"id" "call_3",
                             "type" "function",
                             "function" {"name" "nonexistent",
                                         "arguments" "{}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_3",
                      "content" "unknown tool: nonexistent"}
                     result))))))


#?(:clj
   (deftest run-agent-writes-to-stream-test
     (testing "agent writes to stream when LLM requests stream_write"
       (let [s (ds/open! {:type :ringbuffer, :capacity 5})
             registry {"render" s}
             call-count (atom 0)]
         (with-redefs
           [tzu/chat-completion
            (fn [_messages & _opts]
              (let [n (swap! call-count inc)]
                (case n
                  1 {"choices"
                     [{"message"
                       {"content" nil,
                        "tool_calls"
                        [{"id" "call_1",
                          "type" "function",
                          "function"
                          {"name" "stream_write",
                           "arguments"
                           "{\"stream_id\":\"render\",\"value\":\"[1 2 3]\"}"}}]},
                       "finish_reason" "tool_calls"}]}
                  2 {"choices" [{"message" {"content" "frames written"},
                                 "finish_reason" "stop"}]})))]
           (let [result (tzu/run-agent "write frames" registry "fake-key")]
             (is (= "frames written" (:content result)))
             (is (= 2 @call-count))
             (let [next-result (ds/next s {:position 0})]
               (is (map? next-result))
               (is (= [1 2 3] (:ok next-result))))))))))


#?(:clj
   (deftest run-agent-reads-from-stream-test
     (testing "agent reads from stream when LLM requests stream_read"
       (let [s (doto (ds/open! {:type :ringbuffer, :capacity 5})
                 (ds/put! :hello))
             registry {"input" s}
             call-count (atom 0)]
         (with-redefs
           [tzu/chat-completion
            (fn [_messages & _opts]
              (let [n (swap! call-count inc)]
                (case n
                  1 {"choices"
                     [{"message"
                       {"content" nil,
                        "tool_calls"
                        [{"id" "call_1",
                          "type" "function",
                          "function"
                          {"name" "stream_read",
                           "arguments"
                           "{\"stream_id\":\"input\",\"position\":0}"}}]},
                       "finish_reason" "tool_calls"}]}
                  2 {"choices" [{"message" {"content" "found hello"},
                                 "finish_reason" "stop"}]})))]
           (let [result (tzu/run-agent "read input" registry "fake-key")]
             (is (= "found hello" (:content result)))
             (is (= 2 @call-count))))))))


#?(:clj
   (deftest run-agent-max-iterations-test
     (testing "run-agent throws after exceeding max iterations"
       (let [s (ds/open! {:type :ringbuffer, :capacity 5})
             registry {"s" s}]
         (with-redefs
           [tzu/chat-completion
            (fn [_messages & _]
              {"choices"
               [{"message"
                 {"content" nil,
                  "tool_calls"
                  [{"id" "call_1",
                    "type" "function",
                    "function"
                    {"name" "stream_write",
                     "arguments"
                     "{\"stream_id\":\"s\",\"value\":\"1\"}"}}]},
                 "finish_reason" "tool_calls"}]})]
           (is (thrown? Exception
                 (tzu/run-agent "loop" registry "fake-key"))))))))


;; =============================================================================
;; execute-tool-call edge cases
;; =============================================================================


#?(:clj (deftest execute-tool-call-stream-id-missing-test
          (testing "stream_id not in registry returns error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 1})
                  tool-call
                  {"id" "call_4",
                   "type" "function",
                   "function"
                   {"name" "stream_read",
                    "arguments"
                    "{\"stream_id\":\"nonexistent\",\"position\":0}"}}
                  result (tzu/execute-tool-call tool-call {"other" s})]
              (is (= {"role" "tool", "tool_call_id" "call_4"}
                     (select-keys result ["role" "tool_call_id"])))
              (is (.startsWith ^String (get result "content") "error:"))))))


#?(:clj (deftest execute-tool-call-stream-read-blocked-test
          (testing "stream_read on empty open stream returns blocked message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 5})
                  tool-call {"id" "call_5",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_5",
                      "content" "(blocked: stream open, no value available)"}
                     result))))))


#?(:clj (deftest execute-tool-call-stream-read-end-test
          (testing "stream_read on closed empty stream returns end message"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 5}) ds/close!)
                  tool-call {"id" "call_6",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_6",
                      "content" "(end of stream)"}
                     result))))))


#?(:clj (deftest execute-tool-call-stream-read-gap-test
          (testing "stream_read on evicted position returns gap message"
            (let [s (doto (ds/open! {:type :ringbuffer,
                                     :capacity 1,
                                     :eviction-policy :evict-oldest})
                      (ds/put! :a)
                      (ds/put! :b))
                  tool-call {"id" "call_7",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_7",
                      "content" "(gap: position has been evicted)"}
                     result))))))


#?(:clj (deftest execute-tool-call-stream-write-full-test
          (testing "stream_write on a full stream returns full message"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 1})
                      (ds/put! :a))
                  tool-call {"id" "call_8",
                             "type" "function",
                             "function"
                             {"name" "stream_write",
                              "arguments"
                              "{\"stream_id\":\"s\",\"value\":\":b\"}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_8",
                      "content" "(full: retry later)"}
                     result))))))


#?(:clj (deftest execute-tool-call-stream-write-malformed-edn-test
          (testing "stream_write with malformed EDN returns an error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 5})
                  tool-call
                  {"id" "call_9",
                   "type" "function",
                   "function"
                   {"name" "stream_write",
                    "arguments"
                    "{\"stream_id\":\"s\",\"value\":\"{:unclosed\"}"}}
                  result (tzu/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_9",
                      "content" "(error: malformed EDN)"}
                     result))))))


#?(:clj (deftest execute-tool-call-stream-list-test
          (testing "stream_list returns the keys of the registry"
            (let [s1 (ds/open! {:type :ringbuffer, :capacity 1})
                  s2 (ds/open! {:type :ringbuffer, :capacity 1})
                  tool-call {"id" "call_10",
                             "type" "function",
                             "function" {"name" "stream_list",
                                         "arguments" "{}"}}
                  result (tzu/execute-tool-call tool-call {"foo" s1, "bar" s2})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_10",
                      "content" (pr-str ["bar" "foo"])}
                     result))))))


;; =============================================================================
;; backward-compat: prompt delegates to chat-completion
;; =============================================================================


#?(:clj
   (deftest text-to-datoms-uses-chat-completion-test
     (testing
       "text->datoms calls prompt which delegates to chat-completion — exercises the new path end-to-end"
       (with-redefs [tzu/chat-completion
                     (fn [_messages & _]
                       {"choices" [{"message"
                                    {"content"
                                     "[[-1 :schema/name \"Linda\"]]"},
                                    "finish_reason" "stop"}]})]
         (is (= [[-1 :schema/name "Linda"]]
                (tzu/text->datoms "Linda exists." 500)))))))
