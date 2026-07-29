(ns datomworld.media.web-terminal
  "Browser capability terminals for local media and local player settings."
  (:require
    [clojure.string :as str]
    [dao.stream :as ds]
    [datomworld.media.format :as media-format]))


(defn- put!
  [stream value]
  (when stream (ds/put! stream value)))


(defn- sanitize-name
  [name]
  (-> (or name "Untitled media")
      (str/split #"[\\/]")
      last
      (str/replace #"[\u0000-\u001f\u007f]" "")))


(defn- container-file?
  [file]
  (let [name (str/lower-case (or (.-name file) ""))]
    (or (str/ends-with? name ".datmov")
        (str/ends-with? name ".datmus")
        (contains? #{"application/vnd.datomworld.datmov"
                     "application/vnd.datomworld.datmus"}
                   (.-type file)))))


(defn- container-content-type
  [kind]
  (str "application/vnd.datomworld."
       (if (= kind :movie) "datmov" "datmus")))


(defn create-datom-container
  "Wrap a native media File/Blob in a d5 manifest without transcoding it."
  [file metadata]
  (let [name (sanitize-name (.-name file))
        type (or (.-type file) "")
        kind (media-format/kind-for-type type)]
    (when-not kind
      (throw (ex-info "Only native audio and video can be converted"
                      {:media/error :unsupported-format})))
    (let [manifest (media-format/manifest-datoms
                     (merge {:media/name name
                             :media/type type
                             :media/size (.-size file)}
                            metadata))
          manifest-bytes (.encode (js/TextEncoder.)
                                  (media-format/encode-manifest manifest))
          manifest-length (.-byteLength manifest-bytes)
          header (js/Uint8Array. media-format/header-size)
          view (js/DataView. (.-buffer header))
          magic-bytes (.encode (js/TextEncoder.)
                               (media-format/magic-for-kind kind))
          base-name (let [stripped (str/replace name #"\.[^.]*$" "")]
                      (if (str/blank? stripped) "media" stripped))
          _magic (.set header magic-bytes 0)
          _length (.setUint32 view 8 manifest-length true)
          blob (js/Blob.
                 #js [header manifest-bytes file]
                 #js {:type (container-content-type kind)})]
      (when (> manifest-length media-format/maximum-manifest-bytes)
        (throw (ex-info "Datom manifest exceeds the format limit"
                        {:media/error :manifest-too-large})))
      {:blob blob
       :kind kind
       :manifest manifest
       :download-name (str base-name
                           (media-format/extension-for-kind kind))})))


(defn parse-datom-container!
  "Read only the bounded header and manifest, then expose the payload as a
   Blob slice for the native media element."
  [file]
  (if (< (.-size file) media-format/header-size)
    (js/Promise.reject
      (ex-info "Truncated datom media header"
               {:media/error :invalid-datom-media}))
    (-> (.arrayBuffer (.slice file 0 media-format/header-size))
        (.then
          (fn [header-buffer]
            (let [header (js/Uint8Array. header-buffer)
                  magic (.decode (js/TextDecoder.) (.slice header 0 8))
                  kind (cond
                         (= magic media-format/movie-magic) :movie
                         (= magic media-format/music-magic) :music
                         :else nil)
                  manifest-length
                  (.getUint32 (js/DataView. header-buffer) 8 true)
                  payload-offset (+ media-format/header-size
                                    manifest-length)]
              (when-not kind
                (throw (ex-info "Unknown datom media signature"
                                {:media/error :invalid-datom-media})))
              (when (or (> manifest-length
                           media-format/maximum-manifest-bytes)
                        (> payload-offset (.-size file)))
                (throw (ex-info "Invalid datom media manifest length"
                                {:media/error :invalid-datom-media})))
              (-> (.arrayBuffer
                    (.slice file media-format/header-size payload-offset))
                  (.then
                    (fn [manifest-buffer]
                      (let [manifest-text
                            (.decode (js/TextDecoder.) manifest-buffer)
                            manifest
                            (media-format/decode-manifest manifest-text)
                            metadata
                            (media-format/manifest->metadata manifest)
                            payload-size (- (.-size file) payload-offset)]
                        (when (or (nil? metadata)
                                  (not= kind (:media/kind metadata))
                                  (not= payload-size (:media/size metadata)))
                          (throw
                            (ex-info "Datom media manifest is inconsistent"
                                     {:media/error
                                      :invalid-datom-media})))
                        {:manifest manifest
                         :metadata metadata
                         :payload
                         (.slice file
                                 payload-offset
                                 (.-size file)
                                 (:media/type metadata))}))))))))))


(defn- download-blob!
  [blob filename]
  (let [url (.createObjectURL js/URL blob)
        anchor (.createElement js/document "a")]
    (set! (.-href anchor) url)
    (set! (.-download anchor) filename)
    (.click anchor)
    (js/setTimeout #(.revokeObjectURL js/URL url) 0)))


(defn create-source-registry!
  [{:keys [event-stream create-object-url! revoke-object-url!
           download-blob-fn]
    :or {create-object-url! #(.createObjectURL js/URL %)
         revoke-object-url! #(.revokeObjectURL js/URL %)
         download-blob-fn download-blob!}}]
  (let [entries (atom {})
        active-id (atom nil)
        next-id (atom 0)
        revoke! (fn [source-id]
                  (when-let [{:keys [url]} (get @entries source-id)]
                    (revoke-object-url! url)
                    (swap! entries dissoc source-id)))
        activate!
        (fn [file playback-blob metadata]
          (when-let [previous @active-id] (revoke! previous))
          (let [source-id (str "source-" (swap! next-id inc))
                url (create-object-url! playback-blob)
                selected
                (merge {:player.event/kind :source-selected
                        :media/source-id source-id}
                       metadata)]
            (swap! entries assoc source-id
                   {:url url
                    :file file
                    :media/container (:media/container metadata)})
            (reset! active-id source-id)
            (put! event-stream selected)
            selected))]
    {:register-file!
     (fn [file]
       (if (container-file? file)
         (-> (parse-datom-container! file)
             (.then
               (fn [{:keys [payload metadata]}]
                 (activate! file payload metadata)))
             (.catch
               (fn [_]
                 (put! event-stream
                       {:media.event/kind :error
                        :media/error :invalid-datom-media}))))
         (activate!
           file
           file
           {:media/name (sanitize-name (.-name file))
            :media/type (or (.-type file) "")
            :media/size (or (.-size file) 0)
            :media/container :native})))
     :resolve-source #(get @entries %)
     :convert-source!
     (fn [source-id metadata]
       (when-let [{:keys [file media/container]} (get @entries source-id)]
         (when (= container :native)
           (let [converted (create-datom-container file metadata)
                 container (if (= :movie (:kind converted))
                             :datmov
                             :datmus)]
             (download-blob-fn (:blob converted)
                               (:download-name converted))
             (put! event-stream
                   {:player.event/kind :file-converted
                    :media/source-id source-id
                    :media/container container
                    :media/name (:download-name converted)
                    :media/size (.-size (:blob converted))})
             (dissoc converted :blob)))))
     :revoke-source! revoke!
     :dispose!
     (fn []
       (doseq [source-id (keys @entries)] (revoke! source-id))
       (reset! active-id nil))}))


(defn- buffered-ranges
  [media]
  (let [ranges (.-buffered media)
        length (or (some-> ranges .-length) 0)]
    (mapv (fn [idx] [(.start ranges idx) (.end ranges idx)])
          (range length))))


(defn- media-error
  [media]
  (case (some-> media .-error .-code)
    1 :aborted
    2 :network
    3 :decode
    4 :unsupported-format
    :media-error))


(defn bind-media!
  [{:keys [media-element command-stream event-stream resolve-source
           fullscreen-target now-ms-fn]
    :or {now-ms-fn #(.now js/Date)}}]
  (let [command-position (atom 0)
        active-source-id (atom nil)
        last-time-ms (atom nil)
        open? (atom true)
        target (or fullscreen-target media-element)
        emit-media!
        (fn [kind values]
          (put! event-stream
                (merge {:media.event/kind kind
                        :media/source-id @active-source-id}
                       values)))
        emit-time!
        (fn []
          (let [now (now-ms-fn)
                last-time @last-time-ms]
            (when (or (nil? last-time) (>= (- now last-time) 250))
              (reset! last-time-ms now)
              (emit-media!
                :time
                {:media/position-seconds (or (.-currentTime media-element) 0)
                 :media/duration-seconds
                 (let [duration (.-duration media-element)]
                   (if (js/Number.isFinite duration) duration 0))
                 :media/buffered (buffered-ranges media-element)}))))
        listeners
        {"loadedmetadata"
         (fn [_]
           (emit-media!
             :loaded-metadata
             {:media/duration-seconds
              (let [duration (.-duration media-element)]
                (if (js/Number.isFinite duration) duration 0))
              :media/width (or (.-videoWidth media-element) 0)
              :media/height (or (.-videoHeight media-element) 0)}))
         "playing" (fn [_] (emit-media! :playing {}))
         "pause" (fn [_] (emit-media! :pause {}))
         "waiting" (fn [_] (emit-media! :waiting {}))
         "seeking" (fn [_] (emit-media! :seeking {}))
         "seeked" (fn [_] (emit-media! :seeked {}))
         "ended" (fn [_] (emit-media! :ended {}))
         "timeupdate" (fn [_] (emit-time!))
         "progress" (fn [_] (emit-time!))
         "volumechange"
         (fn [_]
           (emit-media! :volume-change
                        {:media/volume (.-volume media-element)
                         :media/muted? (boolean (.-muted media-element))}))
         "ratechange"
         (fn [_]
           (emit-media! :rate-change
                        {:media/rate (.-playbackRate media-element)}))
         "error"
         (fn [_] (emit-media! :error {:media/error
                                      (media-error media-element)}))}
        fullscreen-listener
        (fn [_]
          (emit-media!
            :fullscreen-change
            {:media/fullscreen?
             (boolean (and (exists? js/document)
                           (.-fullscreenElement js/document)))}))
        fail-command!
        (fn [command error]
          (emit-media!
            :command-failed
            {:media.command/id (:media.command/id command)
             :media.command/kind (:media.command/kind command)
             :media/error error}))
        handle-command!
        (fn [command]
          (case (:media.command/kind command)
            :load-source
            (if-let [{:keys [url]}
                     (resolve-source (:media/source-id command))]
              (do
                (reset! active-source-id (:media/source-id command))
                (reset! last-time-ms nil)
                (set! (.-src media-element) url)
                (.load media-element))
              (fail-command! command :source-unavailable))

            :play
            (try
              (when-let [result (.play media-element)]
                (when (fn? (.-catch result))
                  (.catch result
                          (fn [_] (fail-command! command
                                                :autoplay-blocked)))))
              (catch :default _
                (fail-command! command :autoplay-blocked)))

            :pause (.pause media-element)
            :seek (set! (.-currentTime media-element)
                        (:media/position-seconds command))
            :set-volume (set! (.-volume media-element)
                              (:media/volume command))
            :set-muted (set! (.-muted media-element)
                             (boolean (:media/muted? command)))
            :set-rate (set! (.-playbackRate media-element)
                            (:media/rate command))

            :request-fullscreen
            (if-let [request (.-requestFullscreen target)]
              (try
                (let [result (.call request target)]
                  (when (and result (fn? (.-catch result)))
                    (.catch result
                            (fn [_] (fail-command! command
                                                  :fullscreen-denied)))))
                (catch :default _
                  (fail-command! command :fullscreen-denied)))
              (fail-command! command :fullscreen-unsupported))

            :exit-fullscreen
            (if (and (exists? js/document) (.-exitFullscreen js/document))
              (.exitFullscreen js/document)
              (fail-command! command :fullscreen-unsupported))

            :close-source
            (do
              (.pause media-element)
              (.removeAttribute media-element "src")
              (.load media-element)
              (reset! active-source-id nil)
              (put! event-stream {:player.event/kind :source-closed}))
            nil))
        process-pending!
        (fn []
          (loop []
            (when @open?
              (let [result (ds/next command-stream
                                    {:position @command-position})]
                (when (map? result)
                  (handle-command! (:ok result))
                  (swap! command-position inc)
                  (recur))))))]
    (doseq [[kind handler] listeners]
      (.addEventListener media-element kind handler))
    (when (exists? js/document)
      (.addEventListener js/document "fullscreenchange" fullscreen-listener))
    {:process-pending! process-pending!
     :perform-gesture!
     (fn [kind]
       (handle-command! {:media.command/id -1
                         :media.command/kind kind}))
     :active-source-id #(deref active-source-id)
     :dispose!
     (fn []
       (reset! open? false)
       (doseq [[kind handler] listeners]
         (.removeEventListener media-element kind handler))
       (when (exists? js/document)
         (.removeEventListener js/document
                               "fullscreenchange"
                               fullscreen-listener)))}))


(def settings-storage-key "datomworld.media.settings.v1")


(defn create-settings-terminal!
  [{:keys [command-stream event-stream storage]
    :or {storage (when (exists? js/localStorage) js/localStorage)}}]
  (let [position (atom 0)
        encode
        (fn [value]
          (js/JSON.stringify
            (clj->js
              {:volume (:player/volume value)
               :muted (boolean (:player/muted? value))
               :rate (:player/rate value)})))
        decode
        (fn [raw]
          (try
            (let [value (js->clj (js/JSON.parse raw)
                                 :keywordize-keys true)]
              {:player/volume (double (or (:volume value) 1.0))
               :player/muted? (boolean (:muted value))
               :player/rate (double (or (:rate value) 1.0))})
            (catch :default _ nil)))]
    {:load!
     (fn []
       (let [raw (when storage (.getItem storage settings-storage-key))
             value (when raw (decode raw))]
         (put! event-stream
               {:player.event/kind :settings-loaded
                :settings/value (or value
                                    {:player/volume 1.0
                                     :player/muted? false
                                     :player/rate 1.0})})))
     :process-pending!
     (fn []
       (loop []
         (let [result (ds/next command-stream {:position @position})]
           (when (map? result)
             (let [command (:ok result)]
               (when (and storage
                          (= :save (:settings.command/kind command)))
                 (.setItem storage
                           settings-storage-key
                           (encode (:settings/value command)))))
             (swap! position inc)
             (recur)))))}))
