(ns dao.jing.file
  "Content-addressed segment store backed by an append-only log stream.

   Each log record is `[address payload]`, where address is a content address
   keyword returned by `(jing/segment-key payload)` whose segment hash must
   match the payload's content hash. Writes are serialized through a write
   lock, acknowledged only after the log is flushed, and replayed on open to
   rebuild the in-memory content map.

   Returns a plain map handle `{:path :log :state :write-lock
   :put-content-fn :get-content-fn :close-fn}`."
  (:require #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]])
            [clojure.edn :as edn]
            [dao.jing :as jing]
            [dao.stream :as ds]
            [dao.stream.log]))


;; =============================================================================
;; Serialization / Byte Conversion
;; =============================================================================

(defn ->bytes
  "Convert a string or serializable payload into UTF-8 bytes."
  [payload]
  #?(:clj (.getBytes ^String payload "UTF-8")
     :cljs (js/Buffer.from payload "utf8")
     :cljd (typed/Uint8List.fromList (convert/utf8.encode payload))))


(defn bytes->str
  "Convert UTF-8 bytes back to a string."
  [b]
  #?(:clj (String. ^bytes b "UTF-8")
     :cljs (.toString ^js b "utf8")
     :cljd (convert/utf8.decode b)))


(defn encode-record
  "Encode an [address payload] tuple into a UTF-8 byte array."
  [address payload]
  (->bytes (pr-str [address payload])))


(defn decode-record
  "Decode a byte array or UTF-8 payload back into its EDN value.
   Returns the parsed value as-is; throws on malformed EDN."
  [b]
  (let [s (if (string? b) b (bytes->str b))] (edn/read-string s)))


;; =============================================================================
;; Record Validation
;; =============================================================================

(defn- validate-address-payload!
  [address payload]
  (if (and (jing/segment-address? address)
           (= (jing/segment-hash address) (jing/content-hash payload)))
    [address payload]
    (throw (ex-info "Segment address does not match payload"
                    {:address address, :payload payload}))))


(defn- validate-record!
  [record]
  (if (and (vector? record) (= 2 (count record)))
    (validate-address-payload! (first record) (second record))
    (throw (ex-info "Record is not a 2-element [address payload] vector"
                    {:record record}))))


;; =============================================================================
;; Replay
;; =============================================================================

(defn- replay-log
  "Replay an append-only log into its content map."
  [log]
  (loop [cursor {:position 0}
         content {}]
    (let [res (ds/next log cursor)]
      (cond
        (and (map? res) (contains? res :ok) (contains? res :cursor))
        (let [[address payload] (validate-record! (decode-record (:ok res)))]
          (if (contains? content address)
            (if (= (clojure.core/get content address) payload)
              (recur (:cursor res) content)
              (throw (ex-info
                       "Collision: address already holds a different payload"
                       {:address address})))
            (recur (:cursor res) (assoc content address payload))))
        (and (map? res) (contains? res :ok))
        (throw
          (ex-info
            "Malformed stream response: :ok without :cursor while replaying log"
            {:response res, :position cursor}))
        (= res :end) content
        (= res :blocked) content
        (= res :daostream/gap)
        (throw (ex-info "Stream gap encountered while replaying log"
                        {:position cursor}))
        :else (throw (ex-info "Unexpected stream response while replaying log"
                              {:response res, :position cursor}))))))


;; =============================================================================
;; Write Lock & Flush
;; =============================================================================

#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- flush-log!
  [log]
  #?(:clj (.sync (.getFD (:raf log)))
     :cljs (.fsyncSync ^js (:fs log) (:fd log))
     :cljd (.flushSync ^dart-io/RandomAccessFile (:raf log))))


;; =============================================================================
;; Handle Functions
;; =============================================================================

(defn- make-put
  [state log lock]
  (fn content-put!
    [address payload]
    (validate-address-payload! address payload)
    (with-lock
      lock
      (fn []
        (let [{:keys [closed? content]} @state]
          (when closed?
            (throw (ex-info "Content file is closed" {:address address})))
          (if (contains? content address)
            (if (= (clojure.core/get content address) payload)
              :present
              (throw (ex-info
                       "Collision: address already holds a different payload"
                       {:address address})))
            (let [res (ds/append! log (encode-record address payload))]
              (if (and (map? res) (= :ok (:result res)))
                (do (flush-log! log)
                    (swap! state assoc-in [:content address] payload)
                    :inserted)
                (throw (ex-info "Failed to append record to log"
                                {:address address, :response res}))))))))))


(defn- make-get
  [state]
  (fn content-get
    [address not-found]
    (let [{:keys [closed? content]} @state]
      (when closed?
        (throw (ex-info "Content file is closed" {:address address})))
      (clojure.core/get content address not-found))))


(defn- make-close
  [state log lock]
  (fn content-close!
    []
    (with-lock lock
      (fn []
        (when-not (:closed? @state)
          (ds/close! log)
          (swap! state assoc :closed? true))
        nil))))


;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-content-file
  "Open or create a content file at path, replaying the append-only log into
   an in-memory content map. Returns a plain map handle with :path, :log,
   :state, :write-lock and :put-content-fn, :get-content-fn, :close-fn
   functions."
  [path]
  (let [log (ds/open! {:dao.stream/type :append-log, :path path})]
    (try (let [recovered (replay-log log)
               state (atom {:closed? false, :content recovered})
               lock #?(:clj (Object.)
                       :cljs (js-obj)
                       :cljd (Object.))]
           {:path path,
            :log log,
            :state state,
            :write-lock lock,
            :put-content-fn (make-put state log lock),
            :get-content-fn (make-get state),
            :close-fn (make-close state log lock)})
         (catch #?(:clj Exception
                   :cljs :default
                   :cljd Object)
                e
           (try (ds/close! log)
                (catch #?(:clj Exception
                          :cljs :default
                          :cljd Object)
                       _
                  nil))
           (throw e)))))
