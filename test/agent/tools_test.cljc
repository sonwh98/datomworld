(ns agent.tools-test
  (:require #?@(:cljd [["dart:io" :as io]]
                :clj [[clojure.java.io :as io]])
            [agent.tools :as tools]
            [clojure.test :refer [deftest is testing]]
            #?(:clj [dao.stream :as ds])
            #?(:clj [dao.stream.ringbuffer])))


;; =============================================================================
;; execute-tool-call tests
;; =============================================================================


#?(:cljd nil
   :clj
   (deftest execute-tool-call-stream-write-test
     (testing "stream_write tool call invokes ds/append! on the right stream"
       (let [s (ds/open! {:type :ringbuffer, :capacity 5})
             registry {"target" s}
             tool-call {"id" "call_1",
                        "type" "function",
                        "function"
                        {"name" "stream_write",
                         "arguments"
                         "{\"stream_id\":\"target\",\"value\":\"42\"}"}}
             result (tools/execute-tool-call tool-call registry)]
         (is (= {"role" "tool", "tool_call_id" "call_1", "content" "ok"}
                result))
         (let [next-result (ds/next s {:position 0})]
           (is (map? next-result))
           (is (= 42 (:ok next-result))))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-read-test
          (testing "stream_read tool call reads from the right stream"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 5})
                      (ds/append! :hello))
                  registry {"input" s}
                  tool-call {"id" "call_2",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"input\",\"position\":0}"}}
                  result (tools/execute-tool-call tool-call registry)]
              (is (= {"role" "tool",
                      "tool_call_id" "call_2",
                      "content" (pr-str {:value :hello, :next-position 1})}
                     result))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-unknown-tool-test
          (testing "unknown tool returns error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 1})
                  tool-call {"id" "call_3",
                             "type" "function",
                             "function" {"name" "nonexistent",
                                         "arguments" "{}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_3",
                      "content" "unknown tool: nonexistent"}
                     result))))))


;; =============================================================================
;; execute-tool-call edge cases
;; =============================================================================


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-id-missing-test
          (testing "stream_id not in registry returns error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 1})
                  tool-call
                  {"id" "call_4",
                   "type" "function",
                   "function"
                   {"name" "stream_read",
                    "arguments"
                    "{\"stream_id\":\"nonexistent\",\"position\":0}"}}
                  result (tools/execute-tool-call tool-call {"other" s})]
              (is (= {"role" "tool", "tool_call_id" "call_4"}
                     (select-keys result ["role" "tool_call_id"])))
              (is (.startsWith ^String (get result "content") "error:"))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-read-blocked-test
          (testing "stream_read on empty open stream returns blocked message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 5})
                  tool-call {"id" "call_5",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_5",
                      "content" "(blocked: stream open, no value available)"}
                     result))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-read-end-test
          (testing "stream_read on closed empty stream returns end message"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 5}) ds/close!)
                  tool-call {"id" "call_6",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_6",
                      "content" "(end of stream)"}
                     result))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-read-gap-test
          (testing "stream_read on evicted position returns gap message"
            (let [s (doto (ds/open! {:type :ringbuffer,
                                     :capacity 1,
                                     :eviction-policy :evict-oldest})
                      (ds/append! :a)
                      (ds/append! :b))
                  tool-call {"id" "call_7",
                             "type" "function",
                             "function"
                             {"name" "stream_read",
                              "arguments"
                              "{\"stream_id\":\"s\",\"position\":0}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_7",
                      "content" "(gap: position has been evicted)"}
                     result))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-write-full-test
          (testing "stream_write on a full stream returns full message"
            (let [s (doto (ds/open! {:type :ringbuffer, :capacity 1})
                      (ds/append! :a))
                  tool-call {"id" "call_8",
                             "type" "function",
                             "function"
                             {"name" "stream_write",
                              "arguments"
                              "{\"stream_id\":\"s\",\"value\":\":b\"}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_8",
                      "content" "(full: retry later)"}
                     result))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-stream-write-malformed-edn-test
          (testing "stream_write with malformed EDN returns an error message"
            (let [s (ds/open! {:type :ringbuffer, :capacity 5})
                  tool-call
                  {"id" "call_9",
                   "type" "function",
                   "function"
                   {"name" "stream_write",
                    "arguments"
                    "{\"stream_id\":\"s\",\"value\":\"{:unclosed\"}"}}
                  result (tools/execute-tool-call tool-call {"s" s})]
              (is (= {"role" "tool",
                      "tool_call_id" "call_9",
                      "content" "(error: malformed EDN)"}
                     result))))))


#?(:cljd nil
   :clj
   (deftest execute-tool-call-stream-list-test
     (testing "stream_list returns the keys of the registry"
       (let [s1 (ds/open! {:type :ringbuffer, :capacity 1})
             s2 (ds/open! {:type :ringbuffer, :capacity 1})
             tool-call {"id" "call_10",
                        "type" "function",
                        "function" {"name" "stream_list", "arguments" "{}"}}
             result (tools/execute-tool-call tool-call {"foo" s1, "bar" s2})]
         (is (= {"role" "tool",
                 "tool_call_id" "call_10",
                 "content" (pr-str ["bar" "foo"])}
                result))))))


;; =============================================================================
;; http_fetch tool tests
;; =============================================================================


#?(:cljd nil
   :clj (deftest execute-tool-call-http-fetch-test
          (testing
            "http_fetch tool call opens an HTTP stream and returns the body"
            (let [orig-open ds/open!
                  fake-resp {:status 200,
                             :body "<html>hello world</html>",
                             :headers {"content-type" "text/html"}}
                  tool-call {"id" "call_http_1",
                             "type" "function",
                             "function" {"name" "http_fetch",
                                         "arguments"
                                         "{\"url\":\"https://example.com\"}"}}
                  result (with-redefs [ds/open! (fn [desc]
                                                  (if (= :http (:type desc))
                                                    (doto (orig-open
                                                            {:type :ringbuffer,
                                                             :capacity 1})
                                                      (ds/append! fake-resp)
                                                      ds/close!)
                                                    (orig-open desc)))]
                           (tools/execute-tool-call tool-call {}))]
              (is (= "call_http_1" (get result "tool_call_id")))
              (is (= "tool" (get result "role")))
              (let [content (get result "content")]
                (is (.contains ^String content "200"))
                (is (.contains ^String content "hello world")))))))


#?(:cljd nil
   :clj
   (deftest execute-tool-call-http-fetch-with-method-headers-test
     (testing "http_fetch passes method, headers, and body to the HTTP stream"
       (let
         [orig-open ds/open!
          captured-desc (atom nil)
          fake-resp {:status 201, :body "{\"ok\":true}", :headers {}}
          tool-call
          {"id" "call_http_2",
           "type" "function",
           "function"
           {"name" "http_fetch",
            "arguments"
            (str
              "{\"url\":\"https://api.example.com/data\","
              "\"method\":\"POST\","
              "\"headers\":\"{\\\"Content-Type\\\":\\\"application/json\\\"}\","
              "\"body\":\"{\\\"key\\\":\\\"val\\\"}\"}")}}
          result (with-redefs [ds/open! (fn [desc]
                                          (if (= :http (:type desc))
                                            (do (reset! captured-desc desc)
                                                (doto (orig-open
                                                        {:type :ringbuffer,
                                                         :capacity 1})
                                                  (ds/append! fake-resp)
                                                  ds/close!))
                                            (orig-open desc)))]
                   (tools/execute-tool-call tool-call {}))]
         (is (= :post (:method @captured-desc)))
         (is (= "https://api.example.com/data" (:url @captured-desc)))
         (is (= {"Content-Type" "application/json"}
                (:headers @captured-desc)))
         (is (= "{\"key\":\"val\"}" (:body @captured-desc)))
         (let [content (get result "content")]
           (is (.contains ^String content "201")))))))


#?(:cljd nil
   :clj (deftest execute-tool-call-http-fetch-error-test
          (testing "http_fetch returns error details on transport failure"
            (let [orig-open ds/open!
                  fake-resp {:status 0,
                             :error {:kind :timeout, :message "timed out"}}
                  tool-call {"id" "call_http_3",
                             "type" "function",
                             "function"
                             {"name" "http_fetch",
                              "arguments"
                              "{\"url\":\"https://example.com/slow\"}"}}
                  result (with-redefs [ds/open! (fn [desc]
                                                  (if (= :http (:type desc))
                                                    (doto (orig-open
                                                            {:type :ringbuffer,
                                                             :capacity 1})
                                                      (ds/append! fake-resp)
                                                      ds/close!)
                                                    (orig-open desc)))]
                           (tools/execute-tool-call tool-call {}))]
              (let [content (get result "content")]
                (is (.contains ^String content "timeout")))))))


;; =============================================================================
;; file_read and file_write tool tests
;; =============================================================================


#?(:cljd nil
   :clj (deftest execute-tool-call-file-read-write-test
          (testing "file_write and file_read tool calls work together"
            (let [path "target/test-file.txt"
                  _ (when (.exists (io/file path)) (io/delete-file path))
                  content "hello from Agent Tzu"
                  write-call {"id" "call_file_1",
                              "type" "function",
                              "function" {"name" "file_write",
                                          "arguments" (str "{\"path\":\""
                                                           path
                                                           "\",\"content\":\""
                                                           content
                                                           "\"}")}}
                  write-result (tools/execute-tool-call write-call {})]
              (is (= "ok" (get write-result "content")))
              (let [read-call {"id" "call_file_2",
                               "type" "function",
                               "function" {"name" "file_read",
                                           "arguments"
                                           (str "{\"path\":\"" path "\"}")}}
                    read-result (tools/execute-tool-call read-call {})]
                (is (= (pr-str {:status 200, :body content})
                       (get read-result "content"))))))))
