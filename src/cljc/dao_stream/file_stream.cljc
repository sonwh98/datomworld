(ns dao-stream.file-stream
  "File-based implementation of DaoStream protocol.

  Stores datoms as append-only log files on the local filesystem.
  Suitable for local-first applications, single-node deployments,
  and development/testing."
  (:require [dao-stream.protocol :as proto]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  #?(:clj (:import [java.io File RandomAccessFile]
                   [java.util UUID Date]
                   [java.nio.file Files Paths]
                   [java.nio.file.attribute FileAttribute])))

;; Stream state management
(defonce ^:private streams (atom {}))

(defn- generate-stream-id []
  "Generate a unique stream ID"
  #?(:clj (str (UUID/randomUUID))
     :cljs (str (random-uuid))))

(defn- now []
  "Get current timestamp"
  #?(:clj (Date.)
     :cljs (js/Date.)))

(defn- ensure-directory [path]
  "Ensure directory exists for the given path"
  #?(:clj
     (let [file (io/file path)]
       (when-not (.exists file)
         (.mkdirs file)))
     :cljs
     nil)) ;; In ClojureScript, we'd need platform-specific implementation

(defn- path->filename [stream-path]
  "Convert stream path to filesystem path"
  (let [sanitized (-> stream-path
                      (str/replace #"^/" "")
                      (str/replace #"/" "_"))]
    (str "data/streams/" sanitized ".edn")))

(defn- read-datoms-from-file [filepath]
  "Read all datoms from file"
  #?(:clj
     (if (.exists (io/file filepath))
       (with-open [rdr (io/reader filepath)]
         (doall (map edn/read-string (line-seq rdr))))
       [])
     :cljs
     [])) ;; Platform-specific implementation needed

(defn- append-datom-to-file [filepath datom]
  "Append a single datom to file"
  #?(:clj
     (do
       (ensure-directory (.getParent (io/file filepath)))
       (with-open [w (io/writer filepath :append true)]
         (.write w (pr-str datom))
         (.write w "\n")))
     :cljs
     nil)) ;; Platform-specific implementation needed

(defn- append-datoms-to-file [filepath datoms]
  "Append multiple datoms to file"
  #?(:clj
     (do
       (ensure-directory (.getParent (io/file filepath)))
       (with-open [w (io/writer filepath :append true)]
         (doseq [datom datoms]
           (.write w (pr-str datom))
           (.write w "\n"))))
     :cljs
     nil)) ;; Platform-specific implementation needed

(defn- count-datoms-in-file [filepath]
  "Count total datoms in file"
  #?(:clj
     (if (.exists (io/file filepath))
       (with-open [rdr (io/reader filepath)]
         (count (line-seq rdr)))
       0)
     :cljs
     0))

;; FileStream record
(defrecord FileStream []
  proto/IStream

  (open [this path-config]
    (let [{:keys [path offset timeout mode]
           :or {offset 0
                timeout 5000
                mode :read-write}} path-config
          stream-id (generate-stream-id)
          filepath (path->filename path)
          stream-state {:stream-id stream-id
                        :path path
                        :filepath filepath
                        :offset offset
                        :mode mode
                        :status :connected
                        :created-at (now)
                        :position offset}]

      ;; Ensure file exists
      (ensure-directory "data/streams")
      #?(:clj
         (when-not (.exists (io/file filepath))
           (.createNewFile (io/file filepath))))

      ;; Store stream state
      (swap! streams assoc stream-id stream-state)

      {:stream-id stream-id
       :status :connected
       :path path
       :offset offset
       :error nil}))

  (read [this stream-id]
    (proto/read this stream-id {}))

  (read [this stream-id opts]
    (if-let [stream-state (get @streams stream-id)]
      (let [{:keys [filepath offset]} stream-state
            {:keys [limit timeout from-time to-time]
             :or {limit nil
                  timeout :infinity}} opts
            all-datoms (read-datoms-from-file filepath)
            filtered-datoms (cond->> all-datoms
                              ;; Skip to offset
                              (pos? offset)
                              (drop offset)

                              ;; Filter by from-time
                              from-time
                              (filter #(let [t (proto/time %)]
                                        (and t (>= (.getTime t) (.getTime from-time)))))

                              ;; Filter by to-time
                              to-time
                              (filter #(let [t (proto/time %)]
                                        (and t (<= (.getTime t) (.getTime to-time)))))

                              ;; Apply limit
                              limit
                              (take limit))]

        ;; Update position
        (swap! streams update stream-id assoc :position (+ offset (count filtered-datoms)))

        ;; Return lazy sequence
        filtered-datoms)
      (throw (ex-info "Stream not found" {:stream-id stream-id}))))

  (write [this stream-id datom]
    (proto/write this stream-id [datom]))

  (write [this stream-id datoms]
    (if-let [stream-state (get @streams stream-id)]
      (let [{:keys [filepath mode]} stream-state]
        (when (#{:write-only :read-write} mode)
          (let [;; Ensure datoms have timestamps
                timestamped-datoms (map (fn [d]
                                          (if (proto/time d)
                                            d
                                            (assoc (vec d) 3 (now))))
                                        datoms)
                _ (append-datoms-to-file filepath timestamped-datoms)
                new-count (count-datoms-in-file filepath)]

            ;; Update stream position
            (swap! streams update stream-id assoc :position new-count)

            {:status :written
             :count (count datoms)
             :time (now)
             :offset new-count
             :error nil})))
      (throw (ex-info "Stream not found" {:stream-id stream-id}))))

  (close [this stream-id]
    (if-let [stream-state (get @streams stream-id)]
      (do
        ;; Update status to closed
        (swap! streams update stream-id assoc :status :closed)
        ;; Remove from active streams after a delay (for cleanup)
        (future
          (Thread/sleep 1000)
          (swap! streams dissoc stream-id))

        {:status :closed
         :stream-id stream-id
         :error nil})
      (throw (ex-info "Stream not found" {:stream-id stream-id}))))

  (status [this stream-id]
    (if-let [stream-state (get @streams stream-id)]
      (let [{:keys [status path position mode created-at filepath]} stream-state
            total (count-datoms-in-file filepath)
            lag (- total position)]
        {:status status
         :stream-id stream-id
         :path path
         :position position
         :lag lag
         :total total
         :mode mode
         :created-at created-at
         :peers [] ;; File streams are local, no peers
         :error nil})
      (throw (ex-info "Stream not found" {:stream-id stream-id})))))

;; Convenience constructor
(defn create-file-stream
  "Create a new FileStream instance"
  []
  (->FileStream))

(comment
  ;; Usage examples

  ;; Create a file stream
  (def fs (create-file-stream))

  ;; Open a stream
  (def result (proto/open fs {:path "/user/42/email"}))
  (def sid (:stream-id result))
  ;; => {:stream-id "abc-123", :status :connected, :path "/user/42/email", ...}

  ;; Write some datoms
  (proto/write fs sid
               (proto/datom :user/42 :email/subject "Hello World" nil {:origin "test"}))

  (proto/write fs sid
               [(proto/datom :user/42 :email/to :user/99 nil {})
                (proto/datom :user/42 :email/body "This is a test email" nil {})])

  ;; Read datoms
  (proto/read fs sid)
  ;; => ([:user/42 :email/subject "Hello World" #inst "2025-11-30" {:origin "test"}]
  ;;     [:user/42 :email/to :user/99 #inst "2025-11-30" {}]
  ;;     [:user/42 :email/body "This is a test email" #inst "2025-11-30" {}])

  ;; Read with limit
  (proto/read fs sid {:limit 1})
  ;; => ([:user/42 :email/subject "Hello World" #inst "2025-11-30" {:origin "test"}])

  ;; Check status
  (proto/status fs sid)
  ;; => {:status :connected,
  ;;     :stream-id "abc-123",
  ;;     :position 3,
  ;;     :lag 0,
  ;;     :total 3,
  ;;     ...}

  ;; Filter datoms client-side
  (->> (proto/read fs sid)
       (filter #(= (proto/attribute %) :email/subject))
       (map proto/value))
  ;; => ("Hello World")

  ;; Close stream
  (proto/close fs sid)
  ;; => {:status :closed, :stream-id "abc-123"}


  ;; Example: Create multiple streams
  (def email-stream (proto/open fs {:path "/user/42/email"}))
  (def calendar-stream (proto/open fs {:path "/user/42/calendar"}))

  ;; Write to email stream
  (proto/write fs (:stream-id email-stream)
               (proto/datom :user/42 :email/subject "Meeting Tomorrow" nil {:origin "mobile"}))

  ;; Write to calendar stream
  (proto/write fs (:stream-id calendar-stream)
               (proto/datom :event/777 :event/time #inst "2025-12-01T15:00:00" nil {}))

  ;; Each stream maintains its own file
  ;; data/streams/_user_42_email.edn
  ;; data/streams/_user_42_calendar.edn
  )
