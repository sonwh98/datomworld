(ns world.datom.ollama
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.client :as http]))


(def default-url "http://localhost:11434")
(def default-model "deepseek-r1:14b")
(def default-timeout 300000) ; 5 minutes in milliseconds

(def extraction-system-prompt
  "You are a Datom extraction engine.
   Convert the user's text into Datoms.
   
   Structure: [entity attribute value transaction metadata]
   
   Rules:
   1. Return ONLY a Clojure vector of vectors.
   2. Do not use Markdown formatting (no ```clojure blocks).
   3. Entity: Use unique integer IDs (e.g., 1, 2) for each distinct entity. Use the same integer ID whenever referring to the same entity.
   4. Transaction: Use an integer timestamp (e.g., 1736792345678).
   5. Metadata: Use nil.
   6. Do not include any explanations, only the data structure.
   
   Example Input: Alice works for Acme Corp.
   Example Output:
   [[1 :person/name \"Alice\" 1736792345678 nil]
    [2 :company/name \"Acme Corp\" 1736792345678 nil]
    [1 :person/employer 2 1736792345678 nil]]")


(defn list-models
  "Returns a list of downloaded models."
  [& {:keys [url timeout], :or {url default-url, timeout default-timeout}}]
  (let [endpoint (str url "/api/tags")
        {:keys [status body error]} @(http/get endpoint {:timeout timeout})]
    (if error
      (throw (ex-info "Ollama request failed" {:error error}))
      (let [response (json/read-str body :key-fn keyword)]
        (if (= 200 status)
          (:models response)
          (throw (ex-info "Ollama API Error"
                          {:status status, :body response})))))))


(defn generate
  "Sends a prompt to the specified model and returns the response text.
   Options:
   :model   - Defaults to 'deepseek-r1:14b'
   :url     - Defaults to 'http://localhost:11434'
   :timeout - Defaults to 300000 (5 minutes)
   :stream  - Defaults to false"
  [prompt &
   {:keys [model url timeout stream],
    :or {model default-model,
         url default-url,
         timeout default-timeout,
         stream false}}]
  (let [endpoint (str url "/api/generate")
        body (json/write-str {:model model, :prompt prompt, :stream stream})
        options {:headers {"Content-Type" "application/json"},
                 :body body,
                 :timeout timeout}
        {:keys [status body error]} @(http/post endpoint options)]
    (if error
      (throw (ex-info "Ollama request failed" {:error error}))
      (let [response (json/read-str body :key-fn keyword)]
        (if (= 200 status)
          (:response response)
          (throw (ex-info "Ollama API Error"
                          {:status status, :body response})))))))


(defn chat
  "Sends a chat message history to the specified model.
   messages format: [{:role \"user\" :content \"...\"} ...]
   Returns the message object from the response: {:role \"assistant\" :content \"...\"}"
  [messages &
   {:keys [model url timeout stream],
    :or {model default-model,
         url default-url,
         timeout default-timeout,
         stream false}}]
  (let [endpoint (str url "/api/chat")
        body (json/write-str {:model model, :messages messages, :stream stream})
        options {:headers {"Content-Type" "application/json"},
                 :body body,
                 :timeout timeout}
        {:keys [status body error]} @(http/post endpoint options)]
    (if error
      (throw (ex-info "Ollama request failed" {:error error}))
      (let [response (json/read-str body :key-fn keyword)]
        (if (= 200 status)
          (:message response)
          (throw (ex-info "Ollama API Error"
                          {:status status, :body response})))))))


(defn- clean-response
  [text]
  ;; Remove <think>...</think> blocks from DeepSeek
  (let [cleaned (str/replace text #"(?s)<think>.*?</think>" "")]
    ;; Remove potential markdown code blocks if the model ignores the
    ;; prompt
    (-> cleaned
        (str/replace #"```clojure" "")
        (str/replace #"```" "")
        (str/trim))))


(defn- remap-entities
  [datoms]
  (let [now (System/currentTimeMillis)
        unique-entities (->> datoms
                             (map first)
                             (filter string?)
                             (distinct))
        mapping (zipmap unique-entities (iterate inc 1))] ; Starts from 1
    (mapv (fn [[e a v tx m]] [(get mapping e e) a (get mapping v v) ; Also
                              ;; remap the value if it's a known entity
                              ;; string
                              now m])
      datoms)))


(defn text->datoms
  "Extracts datoms from natural language text using Ollama.
   Remaps temporary string IDs (e1, e2) to monotonic integers."
  [text & {:keys [model], :or {model default-model}}]
  (let [response (chat [{:role "system", :content extraction-system-prompt}
                        {:role "user", :content text}]
                       :model
                       model)
        content (clean-response (:content response))]
    (try
      (let [raw-datoms (edn/read-string content)] (remap-entities raw-datoms))
      (catch Exception e
        (println "Raw response:" content)
        (throw (ex-info "Failed to parse EDN" {:error e, :content content}))))))


(defn ps
  "Returns a list of currently running models."
  [& {:keys [url], :or {url default-url}}]
  (let [endpoint (str url "/api/ps")
        {:keys [status body error]} @(http/get endpoint)]
    (if error
      (throw (ex-info "Ollama request failed" {:error error}))
      (let [response (json/read-str body :key-fn keyword)]
        (if (= 200 status)
          (:models response)
          (throw (ex-info "Ollama API Error"
                          {:status status, :body response})))))))


(comment
  (text->datoms "Sonny lives in Vietnam and works for Acme Inc")
  (ps))
