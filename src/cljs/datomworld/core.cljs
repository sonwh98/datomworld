(ns datomworld.core
  (:require [clojure.core.async :as a :include-macros true]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [stigmergy.mercury :as m]
            [stigmergy.wocket.client :as ws :refer [process-msg]]

            ["ol" :as ol]
            ["ol/coordinate" :as ol.coordinate]
            ["ol/interaction" :as ol.interaction]
            ["ol/layer/Tile"  :default ol.layer.Tile]
            ["ol/layer/Vector" :default ol.layer.VectorLayer]
            ["ol/source" :as ol.source]
            ["ol/source/Vector" :default VectorSource]
            ["ol/proj" :as ol.proj :refer [fromLonLat]]
            ["ol/Feature" :default ol.Feature]
            ["ol/geom/Point" :default ol.geom.Point]
            ["ol/style" :refer [Icon Style]]))

(enable-console-print!)

#_(js/navigator.geolocation.watchPosition (fn [pos]
                                            (let [lon (aget pos "coords" "longitude")
                                                  lat (aget pos "coords" "latitude")]
                                              (m/broadcast [:here {:latitude lat
                                                                   :longitude lon}])))
                                          #(prn %)
                                          #js{:enableHighAccuracy true
                                              :timeout 5000
                                              :maximumAge 0})

(def get-lon-lat (let [c (a/chan)]
                   (fn []
                     (.. js/navigator.geolocation
                         (getCurrentPosition (fn [position]
                                               (let [lon (.-longitude position.coords)
                                                     lat (.-latitude position.coords)]
                                                 (a/put! c [lon lat])))))
                     c)))

(def vector-source (VectorSource.))

(defn create-agent [lon-lat]
  (let [pt (ol.geom.Point. (fromLonLat lon-lat))
        feature (ol.Feature. (clj->js {:geometry pt}))
        red-square (Style. #js{:image (Icon. #js{:color "red"
                                                 :crossOrigin "anonymous"
                                                 :imgSize #js[20 20]
                                                 :src "https://openlayers.org/en/latest/examples/data/square.svg"})})]
    (.setStyle feature red-square)
    feature))

(def agents (atom []))

(defn add-agent-to-map [agent]
  (.. vector-source (addFeature agent))
  (swap! agents conj agent))

(defn animate []
  (a/go-loop []
    (a/<! (a/timeout 500))
    (doseq [feature @agents
            :let [coord (.. feature getGeometry getCoordinates)
                  [x y] coord
                  delta-x (rand-int 10000)
                  delta-x (if (even? delta-x)
                            delta-x
                            (- 0 delta-x))
                  delta-y (rand-int 10000)
                  delta-y (if (even? delta-y)
                            delta-y
                            (- 0 delta-y))
                  pt (ol.geom.Point. (clj->js [(+ x delta-x) (+ y delta-y)]))]]

      (.. feature (setGeometry pt)))
    (recur)))

(comment
  (doseq [feature @agents
          :let [coord (.. feature getGeometry getCoordinates)
                [x y] coord

                pt (ol.geom.Point. #js[(+ x 1000) (+ y 1000)])]]
    (.. feature (setGeometry pt))))

;;https://gis.stackexchange.com/questions/214400/dynamically-update-position-of-geolocation-marker-in-openlayers-3
(def init-openlayer (let [full-screen? (atom false)
                          red-square (Style. #js{:image (Icon. #js{:color "red"
                                                                   :crossOrigin "anonymous"
                                                                   :imgSize #js[20 20]
                                                                   :src "https://openlayers.org/en/latest/examples/data/square.svg"})})

;;vector-source (VectorSource.)
                          vector-layer (ol.layer.VectorLayer. #js{:source vector-source})
                          hoian #js[107.3536929 15.8815912]

                          here-pt (ol.geom.Point. (fromLonLat hoian))
                          view (ol/View. (clj->js {:center (ol.proj/fromLonLat hoian)
                                                   :zoom 10}))]

                      #_(m/on :here (fn [[_ {:keys [longitude latitude]} :as msg]]
                                      (prn msg)
                                      (let [lon-lat (clj->js [longitude latitude])
                                            coords (fromLonLat lon-lat)]
                                        (.. here-pt (setCoordinates coords))
                                        (prn "lon-lat=" lon-lat)
                                        (.. view (animate (clj->js {:center coords
                                                                    :duration 500}))))))

                      (fn [{:keys [dom-id]}]
                        (a/go
                          (let [;; current-point (ol.Feature. (clj->js {:geometry here-pt}))
                                ;; _ (.setStyle current-point red-square)

                                param (clj->js {:target dom-id
                                                :layers #js[(ol.layer.Tile. #js{:source (ol.source/OSM.)})
                                                            vector-layer]
                                                :view view
                                                :interactions (ol.interaction/defaults #js{:doubleClickZoom false})})
                                ol-map (ol/Map. param)
                                me (create-agent (clj->js [108.3734454638195 15.879951769807438]))]
                            (add-agent-to-map me)
                            #_(doseq [i (range 100)
                                      :let [r1 (rand 2)
                                            r2 (rand 2)
                                            lon-lat [(+ 107 r1) (+ 15 r2)]
                                            agent (create-agent (clj->js lon-lat))]]
                                (add-agent-to-map agent))

                            (.. ol-map (on "dblclick" (fn []
                                                        (if @full-screen?
                                                          (do
                                                            (js/alert "Exit Full Screen")
                                                            (js/document.exitFullscreen))
                                                          (do
                                                            (js/alert "Enter Full Screen")
                                                            (js/document.documentElement.requestFullscreen)))
                                                        (swap! full-screen? not)))))))))

(defn init-materialize-ui []
  (js/M.AutoInit)
  #_(js/document.addEventListener "DOMContentLoaded"
                                  (fn []
                                    (let [elements (js/document.querySelectorAll ".sidenav")]
                                      (.init js/M.Sidenav elements)))))

(defn nav-bar []
  [:div
   [:nav
    [:div {:class "nav-wrapper"}

     [:a {:href "#", :data-target "mobile-demo", :class "sidenav-trigger show-on-large"}
      [:i {:class "material-icons"} "menu"]]
     [:ul {:class "right hide-on-med-and-down"}
      [:li
       [:a {:href "sass.html"} "Sass"]]
      [:li
       [:a {:href "badges.html"} "Components"]]
      [:li
       [:a {:href "collapsible.html"} "Javascript"]]
      [:li
       [:a {:href "mobile.html"} "Mobile"]]]]]

   [:ul {:class "sidenav", :id "mobile-demo"}
    [:li
     [:a {:href "sass.html"} "Sass"]]
    [:li
     [:a {:href "badges.html"} "Components"]]
    [:li
     [:a {:href "collapsible.html"} "Javascript"]]
    [:li
     [:a {:href "mobile.html"} "Mobile"]]]])

(def map-view (r/create-class {:component-did-mount (fn [this-component]

                                                      (init-openlayer {:dom-id "map"})
                                                      (init-materialize-ui)
                                                      #_(animate))
                               :reagent-render (fn []
                                                 [:div {:style {:width "100%" :height "100%"}}
                                                  ;;[nav-bar]

                                                  [:div#map {:style {:width "100%"  :height "100%" :margin-top 1}}]])}))

(goog-define WEBSOCKET_PORT 8099)

(defn init []
  (ws/connect-to-websocket-server {:uri "/api/ws" :port WEBSOCKET_PORT})
  (let [app (js/document.getElementById "app")]
    (rdom/render [map-view] app)))

(defmethod process-msg :move-me [[_ coordinates]]
  (let [lon-lat (clj->js (mapv js/parseFloat (take 2 coordinates)))
        ;;lon-lat (clj->js [108.24019629,16.05132689])
        pt (fromLonLat lon-lat)
        agent (first @agents)]
    (prn {:lon-lat lon-lat})
    (.. agent getGeometry (setCoordinates pt))))

(comment
  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])

  (defn create-agents [agent-csv family-network-csv & [num-of-agent]]
    (let [num-of-agent (when num-of-agent
                         (inc num-of-agent))
          family-network-lines (future (with-open [family-reader (io/reader family-network-csv)]
                                         (prn "read family")
                                         (if num-of-agent
                                           (doall (take num-of-agent (csv/read-csv family-reader)))
                                           (doall (csv/read-csv family-reader)))))
          agent-lines (future (with-open [agent-reader (io/reader agent-csv)]
                                (prn "read agent")
                                (if num-of-agent
                                  (doall (take num-of-agent (csv/read-csv agent-reader)))
                                  (doall (csv/read-csv agent-reader)))))

          family-network-header (first @family-network-lines)
          family-network-data (vec (rest @family-network-lines))
          _ (prn "done reading family")

          agent-header (first @agent-lines)
          agent-data (rest @agent-lines)
          _ (prn "done reading agent")
          ;;agent-data (take num-of-agent agent-data)
          i-agent-data (map-indexed (fn [i agent]
                                      [i agent])
                                    agent-data)
          num-of-partition 10000
          i-agent-data-partitions (partition-all num-of-partition i-agent-data)
          _ (prn "partition-count " (count i-agent-data-partitions))
          agent-partitions (pmap (fn [i-agent-data-partition]
                                   (map (fn [[i [urban age province sex migrant schooling group :as agent]]]
                                          {:id i
                                           :urban urban
                                           :household (first (nth family-network-data i))
                                           :age age
                                           :province province
                                           :sex sex
                                           :migrant migrant
                                           :schooling schooling
                                           :group group})
                                        i-agent-data-partition))
                                 i-agent-data-partitions)]
      (flatten agent-partitions)))

  (def my-agents (create-agents "agent.csv" "family_network.csv" 100))
  (def my-agents (create-agents "agent.csv" "family_network.csv"))

  (nth my-agents 2)
  (last my-agents))
