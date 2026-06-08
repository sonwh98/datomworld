(ns agent.tools
  (:require
    #?@(:cljd [["dart:convert" :as convert]]
        :cljs []
        :clj [[clojure.data.json :as json]])
    #?@(:cljs [[cljs.reader :as edn]]
        :default [[clojure.edn :as edn]])
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.http]
    #?@(:cljd []
        :cljs []
        :clj [[dao.stream.file-input-stream]
              [dao.stream.file-output-stream]])))


#?(:cljd (defn- dart->clj
           [x]
           (cond (dart/is? x Map)
                 (into {} (map (fn [e] [(key e) (dart->clj (val e))])) x)
                 (dart/is? x List) (mapv dart->clj x)
                 :else x)))


(defn- json-decode
  [s]
  #?(:clj (json/read-str s)
     :cljs (js->clj (js/JSON.parse s))
     :cljd (dart->clj (convert/jsonDecode s))))


(def stream-tools
  "Tool definitions for dao.stream access via OpenAI-compatible function calling."
  [{"type" "function",
    "function"
    {"name" "stream_list",
     "description"
     "List all available streams in the registry. Returns a list of stream identifiers. Use this first if you don't know which streams are available.",
     "parameters" {"type" "object", "properties" {}}}}
   {"type" "function",
    "function"
    {"name" "stream_read",
     "description"
     "Read the next value from a named stream at the given cursor position. Returns a value and the next cursor position so you can iterate. For blocked/end/gap, returns a status string.",
     "parameters"
     {"type" "object",
      "properties"
      {"stream_id" {"type" "string",
                    "description"
                    "The identifier of the stream in the registry."},
       "position" {"type" "integer",
                   "description" "The cursor position to read from."}},
      "required" ["stream_id" "position"]}}}
   {"type" "function",
    "function"
    {"name" "stream_write",
     "description"
     "Append a Clojure EDN value to a named stream. Provide the value as a Clojure EDN literal string (e.g. \"42\", \"\\\"hello\\\"\", \"[1 2 3]\", \"{:op/kind :frame/clear :color [0.0 0.0 0.0 1.0]}\").",
     "parameters"
     {"type" "object",
      "properties"
      {"stream_id" {"type" "string",
                    "description"
                    "The identifier of the stream in the registry."},
       "value" {"type" "string",
                "description"
                "The Clojure EDN value to write, as a string."}},
      "required" ["stream_id" "value"]}}}
   {"type" "function",
    "function"
    {"name" "http_fetch",
     "description"
     "Fetch a URL from the web. Opens an HTTP stream and returns the response status and body. Use this to retrieve web pages, API data, or any HTTP resource.",
     "parameters"
     {"type" "object",
      "properties"
      {"url" {"type" "string", "description" "The URL to fetch."},
       "method"
       {"type" "string",
        "description"
        "HTTP method: GET, POST, PUT, DELETE. Defaults to GET."},
       "headers"
       {"type" "string",
        "description"
        "Optional request headers as a JSON object string, e.g. \"{\\\"Accept\\\":\\\"application/json\\\"}\"."},
       "body" {"type" "string",
               "description" "Optional request body string."}},
      "required" ["url"]}}}
   {"type" "function",
    "function"
    {"name" "file_read",
     "description"
     "Read the entire contents of a file from the local filesystem as a UTF-8 string.",
     "parameters" {"type" "object",
                   "properties" {"path" {"type" "string",
                                         "description"
                                         "The path to the file."}},
                   "required" ["path"]}}}
   {"type" "function",
    "function"
    {"name" "file_write",
     "description"
     "Append a UTF-8 string to a file on the local filesystem. Creates the file and parent directories if they don't exist.",
     "parameters"
     {"type" "object",
      "properties"
      {"path" {"type" "string", "description" "The path to the file."},
       "content" {"type" "string",
                  "description" "The string content to write."}},
      "required" ["path" "content"]}}}])


(defn execute-tool-call
  "Resolve a single tool_call from the LLM against the stream-registry.
   Returns a tool response message map compatible with the chat API."
  [tool-call stream-registry]
  (let [call-id (get tool-call "id")
        {:strs [name arguments]} (get tool-call "function")
        args (json-decode arguments)]
    (try
      (case name
        "stream_list" {"role" "tool",
                       "tool_call_id" call-id,
                       "content" (pr-str (vec (sort (keys stream-registry))))}
        "stream_read"
        (let [stream (get stream-registry (get args "stream_id"))
              pos (get args "position")
              result (ds/next stream {:position pos})]
          (cond (map? result)
                {"role" "tool",
                 "tool_call_id" call-id,
                 "content" (pr-str {:value (:ok result),
                                    :next-position
                                    (get-in result [:cursor :position])})}
                (= result :blocked)
                {"role" "tool",
                 "tool_call_id" call-id,
                 "content" "(blocked: stream open, no value available)"}
                (= result :end) {"role" "tool",
                                 "tool_call_id" call-id,
                                 "content" "(end of stream)"}
                (= result :daostream/gap)
                {"role" "tool",
                 "tool_call_id" call-id,
                 "content" "(gap: position has been evicted)"}
                :else {"role" "tool",
                       "tool_call_id" call-id,
                       "content" (str "unexpected result: " result)}))
        "stream_write"
        (let [stream (get stream-registry (get args "stream_id"))
              val-str (get args "value")
              val (try (edn/read-string val-str)
                       (catch #?(:clj Exception
                                 :cljs js/Error
                                 :cljd Exception)
                              _
                         ::invalid-edn))]
          (if (= val ::invalid-edn)
            {"role" "tool",
             "tool_call_id" call-id,
             "content" "(error: malformed EDN)"}
            (let [result (ds/put! stream val)]
              (case (:result result)
                :ok {"role" "tool", "tool_call_id" call-id, "content" "ok"}
                :full {"role" "tool",
                       "tool_call_id" call-id,
                       "content" "(full: retry later)"}
                {"role" "tool",
                 "tool_call_id" call-id,
                 "content" (pr-str result)}))))
        "file_read"
        #?(:clj (let [path (get args "path")
                      s (ds/open! {:type :file-input-stream, :path path})
                      chunks (loop [acc []
                                    pos 0]
                               (let [res (ds/next s {:position pos})]
                                 (if (map? res)
                                   (recur (conj acc (:ok res))
                                          (get-in res [:cursor :position]))
                                   acc)))
                      total-size (reduce + (map alength chunks))
                      result-bytes (byte-array total-size)]
                  (loop [cs chunks
                         offset 0]
                    (when (seq cs)
                      (let [c (first cs)
                            len (alength c)]
                        (System/arraycopy c 0 result-bytes offset len)
                        (recur (rest cs) (+ offset len)))))
                  {"role" "tool",
                   "tool_call_id" call-id,
                   "content" (pr-str {:status 200,
                                      :body (String. result-bytes "UTF-8")})})
           :default {"role" "tool",
                     "tool_call_id" call-id,
                     "content" "file_read is only supported on the JVM"})
        "file_write"
        #?(:clj (let [path (get args "path")
                      content (get args "content")
                      s (ds/open! {:type :file-output-stream, :path path})
                      res (ds/put! s (.getBytes ^String content "UTF-8"))]
                  (ds/close! s)
                  {"role" "tool",
                   "tool_call_id" call-id,
                   "content" (if (= (:result res) :ok) "ok" (pr-str res))})
           :default {"role" "tool",
                     "tool_call_id" call-id,
                     "content" "file_write is only supported on the JVM"})
        "http_fetch" (let [url (get args "url")
                           method (some-> (get args "method")
                                          str/lower-case
                                          keyword)
                           hdrs (when-let [h (get args "headers")]
                                  (json-decode h))
                           body (get args "body")
                           descriptor (cond-> {:type :http, :url url}
                                        method (assoc :method method)
                                        hdrs (assoc :headers hdrs)
                                        body (assoc :body body))
                           resp (ds/take!! (ds/open! descriptor))]
                       (if (:error resp)
                         {"role" "tool",
                          "tool_call_id" call-id,
                          "content" (pr-str {:status (:status resp),
                                             :error (:error resp)})}
                         {"role" "tool",
                          "tool_call_id" call-id,
                          "content" (pr-str {:status (:status resp),
                                             :body (:body resp)})}))
        {"role" "tool",
         "tool_call_id" call-id,
         "content" (str "unknown tool: " name)})
      (catch #?(:clj Exception
                :cljs js/Error
                :cljd Exception)
             e
        {"role" "tool", "tool_call_id" call-id, "content" (str "error: " e)}))))
