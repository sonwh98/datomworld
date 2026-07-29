(ns datomworld.media.web-terminal-test
  (:require
    [cljs.test :refer-macros [async deftest is]]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.media.format :as media-format]
    [datomworld.media.web-terminal :as terminal]))


(defn- stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(defn- stream-values
  [s]
  (vec (ds/->seq nil s)))


(defn- mock-media-element
  []
  (let [listeners (atom {})
        calls (atom [])
        buffered #js {:length 1
                      :start (fn [_] 0)
                      :end (fn [_] 12)}]
    {:element
     #js {:src ""
          :currentTime 0
          :duration 60
          :volume 1
          :muted false
          :playbackRate 1
          :videoWidth 1280
          :videoHeight 720
          :buffered buffered
          :error nil
          :addEventListener
          (fn [kind handler] (swap! listeners assoc kind handler))
          :removeEventListener
          (fn [kind _handler] (swap! listeners dissoc kind))
          :load (fn [] (swap! calls conj :load))
          :play (fn [] (swap! calls conj :play) nil)
          :pause (fn [] (swap! calls conj :pause))
          :removeAttribute (fn [attr] (swap! calls conj [:remove attr]))}
     :listeners listeners
     :calls calls}))


(deftest source-registry-keeps-files-out-of-event-stream-test
  (let [events (stream)
        created (atom [])
        revoked (atom [])
        registry (terminal/create-source-registry!
                   {:event-stream events
                    :create-object-url! (fn [file]
                                          (swap! created conj file)
                                          "blob:local")
                    :revoke-object-url! #(swap! revoked conj %)})
        file #js {:name "movie.mp4" :type "video/mp4" :size 2048}
        selected ((:register-file! registry) file)
        event (first (stream-values events))]
    (is (= "blob:local" (:url ((:resolve-source registry)
                                (:media/source-id selected)))))
    (is (= "movie.mp4" (:media/name event)))
    (is (= "video/mp4" (:media/type event)))
    (is (nil? (:file event)))
    (is (nil? (:url event)))
    ((:dispose! registry))
    (is (= ["blob:local"] @revoked))))


(deftest datom-container-wraps-and-restores-native-payload-test
  (async done
    (let [file (js/Blob. #js ["native-payload"]
                         #js {:type "video/mp4"})
          _ (js/Object.defineProperty file "name"
                                      #js {:value "clip.mp4"})
          converted (terminal/create-datom-container
                      file
                      {:media/duration-seconds 3.5})]
      (is (= "clip.datmov" (:download-name converted)))
      (is (= :movie (:kind converted)))
      (is (media-format/valid-manifest? (:manifest converted)))
      (is (> (.-size (:blob converted)) (.-size file)))
      (-> (terminal/parse-datom-container! (:blob converted))
          (.then
            (fn [{:keys [payload metadata]}]
              (is (= "video/mp4" (.-type payload)))
              (is (= (.-size file) (.-size payload)))
              (is (= :datmov (:media/container metadata)))
              (done)))
          (.catch
            (fn [error]
              (is false (str error))
              (done)))))))


(deftest media-terminal-interprets-command-stream-test
  (let [commands (stream)
        events (stream)
        {:keys [element calls]} (mock-media-element)
        runtime (terminal/bind-media!
                  {:media-element element
                   :command-stream commands
                   :event-stream events
                   :resolve-source (fn [id]
                                     (when (= id "source-1")
                                       {:url "blob:one"}))})]
    (doseq [command
            [{:media.command/id 0
              :media.command/kind :load-source
              :media/source-id "source-1"}
             {:media.command/id 1 :media.command/kind :play}
             {:media.command/id 2 :media.command/kind :pause}
             {:media.command/id 3
              :media.command/kind :seek
              :media/position-seconds 14.5}
             {:media.command/id 4
              :media.command/kind :set-volume
              :media/volume 0.4}
             {:media.command/id 5
              :media.command/kind :set-muted
              :media/muted? true}
             {:media.command/id 6
              :media.command/kind :set-rate
              :media/rate 1.5}]]
      (ds/put! commands command))
    ((:process-pending! runtime))
    (is (= "blob:one" (.-src element)))
    (is (= [:load :play :pause] @calls))
    (is (= 14.5 (.-currentTime element)))
    (is (= 0.4 (.-volume element)))
    (is (true? (.-muted element)))
    (is (= 1.5 (.-playbackRate element)))
    ((:dispose! runtime))))


(deftest media-terminal-normalizes-observations-and-throttles-time-test
  (let [commands (stream)
        events (stream)
        now (atom 1000)
        {:keys [element listeners]} (mock-media-element)
        runtime (terminal/bind-media!
                  {:media-element element
                   :command-stream commands
                   :event-stream events
                   :now-ms-fn #(deref now)
                   :resolve-source (constantly {:url "blob:one"})})]
    (ds/put! commands {:media.command/id 0
                       :media.command/kind :load-source
                       :media/source-id "source-1"})
    ((:process-pending! runtime))
    ((get @listeners "loadedmetadata") nil)
    ((get @listeners "timeupdate") nil)
    (reset! now 1100)
    ((get @listeners "timeupdate") nil)
    (reset! now 1300)
    ((get @listeners "timeupdate") nil)
    (let [values (stream-values events)
          kinds (mapv :media.event/kind values)]
      (is (= 1 (count (filter #{:loaded-metadata} kinds))))
      (is (= 2 (count (filter #{:time} kinds))))
      (is (= "source-1" (:media/source-id (last values))))
      (is (= [[0 12]] (:media/buffered (last values)))))
    ((:dispose! runtime))
    (is (empty? @listeners))))


(deftest settings-terminal-persists-only-preferences-test
  (let [commands (stream)
        events (stream)
        saved (atom nil)
        storage #js {:getItem
                     (fn [_] "{\"volume\":0.25,\"muted\":true,\"rate\":1.5}")
                     :setItem (fn [_ value] (reset! saved value))}
        runtime (terminal/create-settings-terminal!
                  {:command-stream commands
                   :event-stream events
                   :storage storage})]
    ((:load! runtime))
    (is (= {:player.event/kind :settings-loaded
            :settings/value {:player/volume 0.25
                             :player/muted? true
                             :player/rate 1.5}}
           (first (stream-values events))))
    (ds/put! commands {:settings.command/kind :save
                       :settings/value {:player/volume 0.8
                                        :player/muted? false
                                        :player/rate 2.0
                                        :player/source {:secret true}}})
    ((:process-pending! runtime))
    (is (= {:volume 0.8 :muted false :rate 2}
           (js->clj (js/JSON.parse @saved) :keywordize-keys true)))))
