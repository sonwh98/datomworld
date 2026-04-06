(ns yin.vm.telemetry-viewer
  (:require
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.transport.ws :as ws]
    [reagent.core :as r]
    [reagent.dom :as rdom]))


;; =============================================================================
;; App State
;; =============================================================================

(defonce app-state
  (r/atom {:repl-stream nil ; IDaoStreamReader connected to server
           :tel-stream nil ; IDaoStreamReader connected to telemetry
           :repl-cursor {:position 0}
           :tel-cursor {:position 0}
           :results [] ; [{:input "..." :output "..."} ...]
           :datoms [] ; [e a v t m] (last N, circular buffer)
           :snapshots {} ; {t {eid {:attr val ...}}} by time
           :status :disconnected ; :disconnected | :connecting | :connected | :error
           :error-msg nil
           :repl-port 8090
           :tel-port 8091
           :input ""}))


;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn index-datoms
  [datoms]
  "Group datoms by entity; return {eid {attr val ...}}"
  (let [entities (atom {})]
    (doseq [[e a v t m] datoms]
      (if (contains? #{:vm.summary/item :vm.summary/entry} a)
        (swap! entities update-in [e a] (fn [old] (conj (or old []) v)))
        (swap! entities update e assoc a v)))
    @entities))


(defn parse-snapshots
  [datoms]
  "Group indexed entities by t. Return {t {eid {...}}}"
  (let [by-time (atom {})]
    (doseq [[e a v t m] datoms]
      (swap! by-time update t
             (fn [es]
               (let [es (or es {})]
                 (if (contains? #{:vm.summary/item :vm.summary/entry} a)
                   (update-in es [e a] (fn [old] (conj (or old []) v)))
                   (assoc-in es [e a] v))))))
    @by-time))


(defn find-snapshot-root
  [entities]
  (first (filter (fn [[_eid entity]]
                   (= :vm/snapshot (:vm/type entity)))
                 entities)))


(defn render-summary
  [entities eid]
  (if (integer? eid)
    (let [entity (get entities eid)]
      (case (:vm.summary/type entity)
        :nil "nil"
        :boolean (str (:vm.summary/value entity))
        :number (str (:vm.summary/value entity))
        :string (pr-str (:vm.summary/value entity))
        :keyword (str (:vm.summary/value entity))
        :symbol (str (:vm.summary/value entity))
        :host-fn "<host-fn>"
        :dao-stream "<dao-stream>"
        :cursor (str "cursor:" (:vm.summary/stream-id entity) "@" (:vm.summary/position entity))
        (:vector :sequence)
        [:span
         (if (= (:vm.summary/type entity) :vector) "[" "(")
         (let [items (:vm.summary/item entity)
               items (if (vector? items) items [items])]
           (for [[i item-eid] (map-indexed vector items)]
             ^{:key i} [:span (when (pos? i) " ") [render-summary entities item-eid]]))
         (when (:vm.summary/truncated? entity) " ...")
         (if (= (:vm.summary/type entity) :vector) "]" ")")]
        :map [:span
              "{"
              (let [entries (:vm.summary/entry entity)
                    entries (if (vector? entries) entries [entries])]
                (for [[i entry-eid] (map-indexed vector entries)]
                  (let [entry (get entities entry-eid)
                        val-eid (:vm.summary/value-ref entry)]
                    ^{:key i}
                    [:span
                     (when (pos? i) ", ")
                     (str (:vm.summary/key entry)) " "
                     [render-summary entities val-eid]])))
              (when (:vm.summary/truncated? entity) " ...")
              "}"]
        :opaque "<opaque>"
        (str "ref:" eid)))
    (pr-str eid)))


;; =============================================================================
;; Polling & Actions
;; =============================================================================

(defn merge-snapshots
  [old-snapshots new-snapshots]
  (merge-with (fn [old-entities new-entities]
                (merge-with (fn [old-entity new-entity]
                              (merge-with (fn [v1 v2]
                                            (if (and (vector? v1) (vector? v2))
                                              (vec (concat v1 v2))
                                              v2))
                                          old-entity
                                          new-entity))
                            old-entities
                            new-entities))
              old-snapshots
              new-snapshots))


(defn drain-telemetry!
  []
  (when (= (:status @app-state) :connected)
    (letfn [(loop-drain
              [cursor datoms]
              (let [result (ds/next (:tel-stream @app-state) cursor)]
                (if (map? result)
                  (loop-drain (:cursor result) (conj datoms (:ok result)))
                  (do
                    (when (not= result :blocked)
                      ;; Handle :daostream/gap or :end if needed
                      nil)
                    {:datoms datoms :cursor cursor}))))]
      (let [drain-result (loop-drain (:tel-cursor @app-state) [])
            new-datoms (:datoms drain-result)
            new-cursor (:cursor drain-result)]
        (when (seq new-datoms)
          (swap! app-state (fn [s]
                             (-> s
                                 (update :datoms #(vec (take-last 500 (concat % new-datoms))))
                                 (update :snapshots merge-snapshots (parse-snapshots new-datoms))
                                 (assoc :tel-cursor new-cursor)))))
        (js/setTimeout drain-telemetry! 50)))))


(defn poll-response
  [req-id cursor attempts]
  (if (>= attempts 500)
    (swap! app-state update :results conj {:input (:input @app-state) :error "Timeout waiting for response"})
    (let [result (dao-apply/next-response (:repl-stream @app-state) cursor)]
      (if (map? result)
        (let [resp (:ok result)]
          (if (= req-id (dao-apply/response-id resp))
            (swap! app-state (fn [s]
                               (-> s
                                   (update :results conj {:input (:input s) :output (pr-str (dao-apply/response-value resp))})
                                   (assoc :repl-cursor (:cursor result)))))
            (poll-response req-id (:cursor result) (inc attempts))))
        (js/setTimeout #(poll-response req-id cursor (inc attempts)) 10)))))


(defn eval!
  []
  (let [input (:input @app-state)
        req-id (keyword (gensym "req-"))]
    (dao-apply/put-request! (:repl-stream @app-state) req-id :op/eval [input])
    (poll-response req-id (:repl-cursor @app-state) 0)))


(defn connect!
  []
  (let [repl-port (:repl-port @app-state)
        tel-port (:tel-port @app-state)]
    (swap! app-state assoc :status :connecting)
    (try
      (let [repl-stream (ws/connect! (str "ws://" js/window.location.hostname ":" repl-port))
            tel-stream (ws/connect! (str "ws://" js/window.location.hostname ":" tel-port))]
        (swap! app-state assoc
               :repl-stream repl-stream
               :tel-stream tel-stream
               :status :connected)
        (drain-telemetry!))
      (catch js/Error e
        (swap! app-state assoc :status :error :error-msg (.-message e))))))


(defn disconnect!
  []
  (when-let [s (:repl-stream @app-state)] (ds/close! s))
  (when-let [s (:tel-stream @app-state)] (ds/close! s))
  (swap! app-state assoc :status :disconnected :repl-stream nil :tel-stream nil))


;; =============================================================================
;; UI Components
;; =============================================================================

(defn connection-panel
  []
  (let [state @app-state]
    [:div.connection-panel {:style {:padding "10px" :border-bottom "1px solid #ccc"}}
     [:div "REPL Port:  " [:input {:value (:repl-port state)
                                   :on-change #(swap! app-state assoc :repl-port (.. % -target -value))}]]
     [:div "Tel Port:   " [:input {:value (:tel-port state)
                                   :on-change #(swap! app-state assoc :tel-port (.. % -target -value))}]]
     (if (= (:status state) :connected)
       [:button {:on-click disconnect!} "Disconnect"]
       [:button {:on-click connect!} "Connect"])
     [:div.status {:style {:color (case (:status state)
                                    :connected "green"
                                    :connecting "orange"
                                    :error "red"
                                    "black")}}
      (name (:status state))
      (when (:error-msg state) (str ": " (:error-msg state)))]]))


(defn repl-panel
  []
  (let [state @app-state]
    [:div.repl {:style {:padding "10px" :flex "1" :display "flex" :flex-direction "column"}}
     [:h3 "REPL"]
     [:textarea {:placeholder "Enter Clojure..."
                 :value (:input state)
                 :style {:height "100px"}
                 :on-change #(swap! app-state assoc :input (.. % -target -value))}]
     [:button {:on-click eval!} "Eval"]
     [:div.history {:style {:overflow-y "auto" :flex "1" :margin-top "10px" :border "1px solid #eee"}}
      (for [[i {:keys [input output error]}] (map-indexed vector (:results state))]
        ^{:key i}
        [:div.result {:style {:padding "5px" :border-bottom "1px solid #eee"}}
         [:div.input {:style {:color "#666"}} (str "> " input)]
         [:div.output (if error [:span.error {:style {:color "red"}} error] output)]])]]))


(defn datom-stream-panel
  []
  (let [state @app-state
        datoms (:datoms state)]
    [:div.datoms {:style {:padding "10px" :flex "1" :display "flex" :flex-direction "column"}}
     [:h3 (str "Datoms (" (count datoms) ")")]
     [:div.datom-list {:style {:overflow-y "auto" :flex "1" :font-family "monospace" :font-size "12px"}}
      (for [[i [e a v t m]] (map-indexed vector datoms)]
        ^{:key i}
        [:div.datom [:code (pr-str [e a v t m])]])]]))


(defn snapshot-panel
  []
  (let [state @app-state
        snapshots (:snapshots state)]
    [:div.snapshot {:style {:padding "10px" :flex "1" :display "flex" :flex-direction "column"}}
     [:h3 "VM Snapshot"]
     (if (empty? snapshots)
       [:div "No snapshots yet."]
       (let [latest-t (apply max (keys snapshots))
             entities (get snapshots latest-t)]
         [:div
          [:h4 (str "t = " latest-t)]
          (if-let [[root-eid root] (find-snapshot-root entities)]
            [:div
             [:div [:strong "step: "] (:vm/step root)]
             [:div [:strong "halted: "] (str (:vm/halted? root))]
             [:div [:strong "blocked: "] (str (:vm/blocked? root))]
             [:div [:strong "control: "] [render-summary entities (:vm/control root)]]
             [:div [:strong "env: "] [render-summary entities (:vm/env root)]]
             [:div [:strong "store: "] [render-summary entities (:vm/store root)]]
             [:div [:strong "k: "] [render-summary entities (:vm/k root)]]]
            [:div "Snapshot root not found in entities."])]))]))


(defn main-panel
  []
  [:div.telemetry-viewer {:style {:display "flex" :flex-direction "column" :height "100vh" :font-family "sans-serif"}}
   [connection-panel]
   [:div {:style {:display "flex" :flex "1" :overflow "hidden"}}
    [:div {:style {:display "flex" :flex-direction "column" :width "30%" :border-right "1px solid #ccc"}}
     [repl-panel]]
    [:div {:style {:display "flex" :flex-direction "column" :width "30%" :border-right "1px solid #ccc"}}
     [datom-stream-panel]]
    [:div {:style {:display "flex" :flex-direction "column" :width "40%"}}
     [snapshot-panel]]]])


;; =============================================================================
;; Entry Point
;; =============================================================================

(defn ^:export init
  []
  (let [app (js/document.getElementById "app")]
    (when app
      (rdom/render [main-panel] app))))
