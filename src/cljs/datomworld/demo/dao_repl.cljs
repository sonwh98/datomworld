(ns datomworld.demo.dao-repl
  (:require
    ["@codemirror/state" :refer [EditorState]]
    ["@codemirror/theme-one-dark" :refer [oneDark]]
    ["@codemirror/view" :refer [EditorView keymap]]
    ["@nextjournal/lang-clojure" :refer [clojure]]
    ["codemirror" :refer [basicSetup]]
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.ws :as ws]
    [reagent.core :as r]))


(def default-source
  "(help)")


(defn location->repl-url
  [{:keys [protocol hostname]} port]
  (str (if (= protocol "https:") "wss" "ws")
       "://"
       (or hostname "localhost")
       ":"
       port))


(defn current-location
  []
  (let [location (some-> js/globalThis .-window .-location)]
    {:protocol (or (some-> location .-protocol) "http:")
     :hostname (or (some-> location .-hostname) "localhost")}))


(defn initial-state
  []
  {:active-request nil
   :editor-source default-source
   :error-msg nil
   :history []
   :request-seq 0
   :response-cursor {:position 0}
   :status :disconnected
   :stream nil
   :url (location->repl-url (current-location) 8080)})


(defn queue-request
  [state input]
  (let [request-id (str "browser/request/" (:request-seq state))]
    [(-> state
         (assoc :active-request request-id)
         (update :request-seq inc)
         (update :history conj {:id request-id
                                :input input
                                :output nil
                                :status :pending}))
     request-id]))


(defn update-history-entry
  [history request-id attrs]
  (mapv (fn [entry]
          (if (= request-id (:id entry))
            (merge entry attrs)
            entry))
        history))


(defn resolve-request
  [state request-id status output]
  (-> state
      (assoc :active-request nil)
      (update :history update-history-entry request-id {:output output
                                                        :status status})))


(defn fail-active-request
  [state output]
  (if-let [request-id (:active-request state)]
    (resolve-request state request-id :error output)
    state))


(defonce app-state
  (r/atom (initial-state)))


(defn codemirror-editor
  [{:keys [on-change on-submit read-only value]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "dao-repl-codemirror-editor"
       :component-did-mount
       (fn [_]
         (when-let [node @el-ref]
           (let [theme (.theme EditorView
                               #js {"&" #js {:height "100%"
                                             :fontSize "15px"}
                                    ".cm-scroller" #js {:overflow "auto"}
                                    ".cm-content" #js {:padding "16px"}})
                 submit-binding #js [{:key "Mod-Enter"
                                      :run (fn []
                                             (when on-submit
                                               (on-submit))
                                             true)}
                                     {:key "Shift-Enter"
                                      :run (fn []
                                             (when on-submit
                                               (on-submit))
                                             true)}]
                 extensions
                 (cond-> #js [basicSetup
                              (clojure)
                              oneDark
                              theme
                              (.of keymap submit-binding)]
                   read-only (.concat #js [(.of (.-editable EditorView)
                                                false)])
                   on-change
                   (.concat
                     #js [(.of
                            (.-updateListener EditorView)
                            (fn [^js update]
                              (when (and (.-docChanged update) on-change)
                                (on-change (.. update -state -doc toString)))))]))
                 state (.create EditorState
                                #js {:doc (or value "")
                                     :extensions extensions})
                 view (new EditorView #js {:state state
                                           :parent node})]
             (reset! view-ref view))))
       :component-did-update
       (fn [this _]
         (let [{:keys [value]} (r/props this)]
           (when-let [^js view @view-ref]
             (let [next-value (or value "")
                   current-value (.. view -state -doc toString)]
               (when (not= next-value current-value)
                 (.dispatch view
                            #js {:changes #js {:from 0
                                               :to (.. view -state -doc -length)
                                               :insert next-value}}))))))
       :component-will-unmount
       (fn [_]
         (when-let [^js view @view-ref]
           (.destroy view)
           (reset! view-ref nil)))
       :reagent-render
       (fn [{:keys [style]}]
         [:div
          {:ref #(reset! el-ref %)
           :style (merge {:border "1px solid #2d3b55"
                          :border-radius "12px"
                          :height "100%"
                          :overflow "hidden"
                          :width "100%"}
                         style)}])})))


(defn- with-current-stream
  [stream f]
  (swap! app-state
         (fn [state]
           (if (= stream (:stream state))
             (f state)
             state))))


(defn disconnect!
  []
  (when-let [stream (:stream @app-state)]
    (ds/close! stream))
  (swap! app-state
         (fn [state]
           (-> state
               (assoc :active-request nil
                      :error-msg nil
                      :status :disconnected
                      :stream nil)
               (fail-active-request "Connection closed")))))


(declare poll-response!)


(defn connect!
  []
  (let [url (str/trim (:url @app-state))]
    (when (seq url)
      (when-let [stream (:stream @app-state)]
        (ds/close! stream))
      (let [stream (ws/connect! url
                                {:on-open
                                 (fn [current-stream _event]
                                   (with-current-stream current-stream
                                     (fn [state]
                                       (assoc state
                                              :error-msg nil
                                              :status :connected))))
                                 :on-close
                                 (fn [current-stream _event]
                                   (with-current-stream current-stream
                                     (fn [state]
                                       (-> state
                                           (assoc :status :disconnected
                                                  :stream nil)
                                           (fail-active-request "Connection closed")))))
                                 :on-error
                                 (fn [current-stream _event]
                                   (with-current-stream current-stream
                                     (fn [state]
                                       (assoc state
                                              :error-msg "WebSocket error"
                                              :status :error))))})]
        (swap! app-state assoc
               :error-msg nil
               :status :connecting
               :stream stream)))))


(defn poll-response!
  [request-id cursor attempts]
  (let [{:keys [status stream]} @app-state]
    (when (and stream (not= status :disconnected))
      (cond
        (> attempts 500)
        (swap! app-state
               #(-> %
                    (resolve-request request-id :error "Timed out waiting for remote Dao REPL")
                    (assoc :status :error
                           :error-msg "Timed out waiting for response")))

        :else
        (let [result (dao-apply/next-response stream cursor)]
          (cond
            (map? result)
            (let [response (:ok result)
                  next-cursor (:cursor result)]
              (if (= request-id (dao-apply/response-id response))
                (swap! app-state
                       #(-> %
                            (assoc :response-cursor next-cursor)
                            (resolve-request request-id
                                             :ok
                                             (str (dao-apply/response-value response)))))
                (poll-response! request-id next-cursor (inc attempts))))

            (= result :blocked)
            (js/setTimeout #(poll-response! request-id cursor (inc attempts)) 20)

            (= result :daostream/gap)
            (js/setTimeout #(poll-response! request-id {:position 0} (inc attempts)) 0)

            :else
            (swap! app-state
                   #(-> %
                        (resolve-request request-id :error "Remote stream closed")
                        (assoc :status :error
                               :error-msg (str "Remote stream ended with " result))))))))))


(defn eval!
  []
  (let [{:keys [active-request editor-source status stream]} @app-state
        input (str/trim editor-source)]
    (cond
      (str/blank? input)
      nil

      active-request
      nil

      (not= status :connected)
      (swap! app-state assoc :error-msg "Connect to a remote Dao REPL first")

      (nil? stream)
      (swap! app-state assoc :error-msg "Connect to a remote Dao REPL first")

      :else
      (let [[state request-id] (queue-request @app-state editor-source)]
        (reset! app-state state)
        (dao-apply/put-request! stream request-id :op/eval [editor-source])
        (poll-response! request-id (:response-cursor state) 0)))))


(defn clear-history!
  []
  (swap! app-state assoc :history []))


(defn status-chip
  [status]
  [:span
   {:style {:background (case status
                          :connected "#12381f"
                          :connecting "#3f2f11"
                          :error "#4a1d1d"
                          "#151b33")
            :border "1px solid rgba(255,255,255,0.08)"
            :border-radius "999px"
            :color "#f8fbff"
            :font-size "12px"
            :font-weight "600"
            :letter-spacing "0.04em"
            :padding "6px 10px"
            :text-transform "uppercase"}}
   (name status)])


(defn history-entry
  [{:keys [id input output status]}]
  [:div
   {:key id
    :style {:background "#0e1428"
            :border "1px solid #2d3b55"
            :border-radius "12px"
            :padding "14px"
            :display "flex"
            :flex-direction "column"
            :gap "10px"}}
   [:div {:style {:display "flex"
                  :justify-content "space-between"
                  :align-items "center"}}
    [:span {:style {:color "#8b9ab8"
                    :font-size "12px"
                    :font-weight "600"
                    :letter-spacing "0.04em"
                    :text-transform "uppercase"}}
     "Input"]
    [status-chip status]]
   [:pre {:style {:margin 0
                  :white-space "pre-wrap"
                  :font-family "monospace"
                  :font-size "13px"
                  :color "#f8fbff"}}
    input]
   [:div {:style {:height "1px"
                  :background "#24304b"}}]
   [:span {:style {:color "#8b9ab8"
                   :font-size "12px"
                   :font-weight "600"
                   :letter-spacing "0.04em"
                   :text-transform "uppercase"}}
    "Output"]
   [:pre {:style {:margin 0
                  :min-height "1.5em"
                  :white-space "pre-wrap"
                  :font-family "monospace"
                  :font-size "13px"
                  :color (case status
                           :error "#ff9b9b"
                           "#dce7ff")}}
    (case status
      :pending "Waiting for response..."
      (or output ""))]])


(defn main-view
  []
  (let [{:keys [active-request editor-source error-msg history status url]} @app-state]
    [:div
     {:style {:background "radial-gradient(circle at top, #122047 0%, #060817 58%)"
              :color "#f8fbff"
              :font-family "system-ui, sans-serif"
              :min-height "100vh"
              :padding "96px 24px 32px"}}
     [:div
      {:style {:max-width "1400px"
               :margin "0 auto"
               :display "flex"
               :flex-direction "column"
               :gap "20px"}}
      [:div
       {:style {:display "flex"
                :flex-wrap "wrap"
                :justify-content "space-between"
                :gap "16px"
                :align-items "end"}}
       [:div {:style {:max-width "760px"}}
        [:div {:style {:color "#7bc1ff"
                       :font-size "12px"
                       :font-weight "700"
                       :letter-spacing "0.12em"
                       :text-transform "uppercase"
                       :margin-bottom "10px"}}
         "Browser Dao REPL"]
        [:h1 {:style {:margin "0 0 10px"
                      :font-size "clamp(2rem, 4vw, 3.5rem)"
                      :line-height "1.05"}}
         "CodeMirror speaks Clojure, dao.repl stays remote"]
        [:p {:style {:margin 0
                     :color "#b7c7e6"
                     :font-size "16px"
                     :line-height "1.6"}}
         "This browser demo opens a websocket client to a remote "
         [:code "dao.repl"]
         " server and sends explicit "
         [:code ":op/eval"]
         " request datoms. The browser owns the editor, the remote runtime owns evaluation."]]
       [:div
        {:style {:background "rgba(8,12,25,0.82)"
                 :border "1px solid #2d3b55"
                 :border-radius "16px"
                 :padding "16px"
                 :min-width "320px"
                 :max-width "420px"
                 :display "flex"
                 :flex-direction "column"
                 :gap "10px"}}
        [:div {:style {:font-size "12px"
                       :font-weight "700"
                       :letter-spacing "0.08em"
                       :text-transform "uppercase"
                       :color "#8b9ab8"}}
         "Remote server"]
        [:code {:style {:display "block"
                        :white-space "pre-wrap"
                        :font-size "12px"
                        :line-height "1.5"
                        :color "#dce7ff"}}
         "clj -M:dao-repl --port 8080 --headless"]
        [:div {:style {:font-size "13px"
                       :color "#b7c7e6"
                       :line-height "1.5"}}
         "Use "
         [:code "Ctrl-Enter"]
         " or "
         [:code "Cmd-Enter"]
         " to send the current editor buffer to the remote REPL."]]]
      [:div
       {:style {:display "grid"
                :grid-template-columns "minmax(360px, 1.1fr) minmax(320px, 0.9fr)"
                :gap "20px"}}
       [:section
        {:style {:background "rgba(8,12,25,0.82)"
                 :border "1px solid #2d3b55"
                 :border-radius "18px"
                 :padding "18px"
                 :display "flex"
                 :flex-direction "column"
                 :gap "16px"
                 :min-height "70vh"}}
        [:div
         {:style {:display "flex"
                  :flex-wrap "wrap"
                  :gap "12px"
                  :align-items "center"}}
         [:div
          {:style {:display "flex"
                   :flex "1"
                   :min-width "240px"
                   :gap "10px"}}
          [:input
           {:value url
            :on-change #(swap! app-state assoc :url (.. % -target -value))
            :placeholder "ws://localhost:8080"
            :style {:flex "1"
                    :background "#0b1120"
                    :border "1px solid #2d3b55"
                    :border-radius "12px"
                    :color "#f8fbff"
                    :font-size "14px"
                    :padding "12px 14px"}}]
          (if (= status :connected)
            [:button
             {:on-click disconnect!
              :style {:background "#1b243f"
                      :border "1px solid #36486f"
                      :border-radius "12px"
                      :color "#f8fbff"
                      :cursor "pointer"
                      :padding "12px 16px"}}
             "Disconnect"]
            [:button
             {:on-click connect!
              :style {:background "#1f6feb"
                      :border "1px solid #388bfd"
                      :border-radius "12px"
                      :color "#f8fbff"
                      :cursor "pointer"
                      :padding "12px 16px"}}
             "Connect"])]
         [status-chip status]]
        (when error-msg
          [:div
           {:style {:background "rgba(117, 24, 24, 0.45)"
                    :border "1px solid #7a2f2f"
                    :border-radius "12px"
                    :color "#ffd4d4"
                    :padding "12px 14px"}}
           error-msg])
        [:div
         {:style {:display "flex"
                  :justify-content "space-between"
                  :align-items "center"}}
         [:div
          [:div {:style {:font-size "12px"
                         :font-weight "700"
                         :letter-spacing "0.08em"
                         :text-transform "uppercase"
                         :color "#8b9ab8"}}
           "Editor"]
          [:div {:style {:color "#b7c7e6"
                         :font-size "14px"}}
           "Send any Dao REPL form or command to the remote runtime."]]
         [:button
          {:on-click eval!
           :disabled (or active-request (not= status :connected))
           :style {:background (if (or active-request (not= status :connected))
                                 "#24304b"
                                 "#2ea043")
                   :border "1px solid rgba(255,255,255,0.08)"
                   :border-radius "12px"
                   :color "#f8fbff"
                   :cursor (if (or active-request (not= status :connected))
                             "not-allowed"
                             "pointer")
                   :padding "12px 18px"}}
          (if active-request "Waiting..." "Eval")]]
        [:div
         {:style {:flex "1"
                  :min-height "420px"}}
         [codemirror-editor
          {:value editor-source
           :on-change #(swap! app-state assoc :editor-source %)
           :on-submit eval!
           :style {:height "100%"}}]]]
       [:section
        {:style {:background "rgba(8,12,25,0.82)"
                 :border "1px solid #2d3b55"
                 :border-radius "18px"
                 :padding "18px"
                 :display "flex"
                 :flex-direction "column"
                 :gap "16px"
                 :min-height "70vh"}}
        [:div
         {:style {:display "flex"
                  :justify-content "space-between"
                  :align-items "center"}}
         [:div
          [:div {:style {:font-size "12px"
                         :font-weight "700"
                         :letter-spacing "0.08em"
                         :text-transform "uppercase"
                         :color "#8b9ab8"}}
           "Transcript"]
          [:div {:style {:color "#b7c7e6"
                         :font-size "14px"}}
           "Request and response datoms rendered as a REPL history."]]
         [:button
          {:on-click clear-history!
           :style {:background "#1b243f"
                   :border "1px solid #36486f"
                   :border-radius "12px"
                   :color "#f8fbff"
                   :cursor "pointer"
                   :padding "10px 14px"}}
          "Clear"]]
        [:div
         {:style {:display "flex"
                  :flex-direction "column"
                  :gap "12px"
                  :overflow "auto"
                  :flex "1"}}
         (if (seq history)
           (for [entry history]
             ^{:key (:id entry)}
             [history-entry entry])
           [:div
            {:style {:border "1px dashed #31415f"
                     :border-radius "12px"
                     :padding "18px"
                     :color "#8b9ab8"}}
            "No requests yet. Connect to a remote Dao REPL and evaluate the editor buffer."])]]]]]))
