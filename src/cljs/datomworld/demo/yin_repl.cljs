(ns datomworld.demo.yin-repl
  "The browser Yin REPL demo, ported to the DaoStream v2 wire (D3/U4 of
   `yin.vm.v1-retirement.implementation-plan.md`).

   The v1 demo (`datomworld.demo.yin-repl`) polled explicit `:op/eval`
   request/response datoms itself.  This one composes the browser WebSocket
   adapter directly (`dao.stream.ws.browser`) as `:host` rather than
   calling `(yin.repl.host/websocket)`, because that call would hand the
   REPL the wrong adapter here: `yin.repl.host`'s cljs shadow always
   answers with Node's `ws` composition, whichever build requires it, since
   the shadow selects per build rather than per platform.

   That said, requiring `yin.repl.driver` below still pulls in
   `yin.repl.host` transitively (`driver.cljc` requires it unconditionally
   for its own default), so this browser bundle carries the whole Node `ws`
   require chain regardless -- `browser-adapter` only decides which map this
   demo *hands to* `driver/create-state`, not what gets bundled.  The bundle
   stays working only because npm `ws`'s own `package.json` remaps to a
   `browser.js` stub that throws if actually called, and nothing on this path
   calls it.  A future host adapter without an equally polite browser stub
   would break this bundle at runtime with no compile-time signal from
   anything in this codebase.

   Drives `yin.repl.driver` exactly as `yin.repl.cljc`'s Node host
   does: one `setInterval` owns the driver state, calls `repl-step` once per
   tick, and drains `take-outbox` into a transcript.  A line producer --
   Eval, Connect, Disconnect -- only appends to the driver's input medium and
   returns."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@codemirror/view" :refer [EditorView keymap]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup]]
            [clojure.string :as str]
            [dao.stream.ws.browser :as browser]
            [datomworld.demo.responsive :as responsive]
            [reagent.core :as r]
            [yin.repl.driver :as driver]))


(def default-source "(help)")


;; The Node host (`yin.repl.cljc`) ticks at `yin.repl/tick-millis`.
;; That namespace is not required here -- it requires `yin.repl.host`,
;; whose cljs shadow is Node's `ws` composition -- so the cadence is mirrored
;; as a local constant instead.
(def tick-millis 25)


(def browser-adapter
  "The v2 REPL client boundary's `:host`, composed directly from the browser
   WebSocket adapter rather than from `(yin.repl.host/websocket)` -- that
   call would answer with Node's `ws` adapter here too, since the shadow
   selects per build, not per platform.  This choice picks the right adapter
   to *use*; it does not keep Node's `ws` require chain out of this bundle,
   which `yin.repl.driver` already pulls in transitively regardless (see
   the namespace docstring)."
  {:connect! browser/connect!})


(defn location->repl-url
  [{:keys [hostname]} port]
  ;; `yin.repl.connect/parse-url` has no settled `wss://` descriptor form
  ;; in this slice, so the URL is always `ws://` regardless of the page's own
  ;; protocol.
  (str "daostream:ws://" (or hostname "localhost") ":" port))


(defn current-location
  []
  (let [location (some-> js/globalThis .-window .-location)]
    {:hostname (or (some-> location .-hostname) "localhost")}))


(defn initial-state
  []
  {:driver (driver/create-state {:host browser-adapter})
   :editor-source default-source
   :history []
   :url (location->repl-url (current-location) 8080)})


(defonce app-state (r/atom (initial-state)))


(defonce ticker (atom nil))


(defn- entry->row
  [entry]
  {:id (str (random-uuid))
   :kind (get entry driver/event-key)
   :text (get entry driver/text-key)})


(defn- tick!
  []
  (swap! app-state
         (fn [state]
           (let [stepped (driver/repl-step (:driver state) (js/Date.now))
                 [entries driver'] (driver/take-outbox stepped)]
             (-> state
                 (assoc :driver driver')
                 (update :history into (map entry->row entries)))))))


(defn start-ticker!
  []
  (when-not @ticker
    (reset! ticker (js/setInterval tick! tick-millis))))


(defn stop-ticker!
  []
  (when-let [id @ticker]
    (js/clearInterval id)
    (reset! ticker nil)))


;; The one non-overlapping ticker that owns the driver state, started once
;; when this namespace loads rather than on component mount: the driver
;; composition (and any live remote connection) survives the operator
;; switching to another demo card and back, exactly as `defonce app-state`
;; does.
(defonce ticker-started? (start-ticker!))


(defn- submit!
  [line]
  (driver/submit-line! (:input (:driver @app-state)) line))


(defn eval!
  []
  (let [line (str/trim (:editor-source @app-state))]
    (when (seq line)
      (submit! line))))


(defn connect!
  []
  (let [url (str/trim (:url @app-state))]
    (when (seq url)
      (submit! (str "(connect " (pr-str url) ")")))))


(defn disconnect!
  []
  (submit! "(disconnect)"))


(defn clear-history!
  []
  (swap! app-state assoc :history []))


(defn codemirror-editor
  [{:keys [on-change on-submit read-only value]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "yin-repl-codemirror-editor",
       :component-did-mount
       (fn [_]
         (when-let [node @el-ref]
           (let [theme (.theme EditorView
                               #js {"&" #js {:height "100%",
                                             :fontSize "15px"},
                                    ".cm-scroller" #js {:overflow "auto"},
                                    ".cm-content" #js {:padding "16px"}})
                 submit-binding
                 #js [{:key "Mod-Enter",
                       :run (fn [] (when on-submit (on-submit)) true)}
                      {:key "Shift-Enter",
                       :run (fn [] (when on-submit (on-submit)) true)}]
                 extensions
                 (cond-> #js [basicSetup (clojure) oneDark theme
                              (.of keymap submit-binding)]
                   read-only (.concat #js [(.of (.-editable EditorView)
                                                false)])
                   on-change
                   (.concat
                     #js [(.of
                            (.-updateListener EditorView)
                            (fn [^js update]
                              (when (and (.-docChanged update) on-change)
                                (on-change
                                  (.. update -state -doc toString)))))]))
                 state (.create EditorState
                                #js {:doc (or value ""),
                                     :extensions extensions})
                 view (new EditorView #js {:state state, :parent node})]
             (reset! view-ref view)))),
       :component-did-update
       (fn [this _]
         (let [{:keys [value]} (r/props this)]
           (when-let [^js view @view-ref]
             (let [next-value (or value "")
                   current-value (.. view -state -doc toString)]
               (when (not= next-value current-value)
                 (.dispatch view
                            #js {:changes #js
                                          {:from 0,
                                           :to (.. view -state -doc -length),
                                           :insert next-value}})))))),
       :component-will-unmount (fn [_]
                                 (when-let [^js view @view-ref]
                                   (.destroy view)
                                   (reset! view-ref nil))),
       :reagent-render
       (fn [{:keys [style]}]
         [:div
          {:ref #(reset! el-ref %),
           :style (merge {:border "1px solid #2d3b55",
                          :border-radius "12px",
                          :height "100%",
                          :overflow "hidden",
                          :width "100%"}
                         style)}])})))


(defn status-chip
  [status]
  [:span
   {:style {:background (case status
                          :established "#12381f"
                          :connecting "#3f2f11"
                          (:transport-error :not-found :ended) "#4a1d1d"
                          "#151b33"),
            :border "1px solid rgba(255,255,255,0.08)",
            :border-radius "999px",
            :color "#f8fbff",
            :font-size "12px",
            :font-weight "600",
            :letter-spacing "0.04em",
            :padding "6px 10px",
            :text-transform "uppercase"}} (name (or status :disconnected))])


(defn transcript-row
  [{:keys [id kind text]}]
  [:pre
   {:key id,
    :style {:margin 0,
            :white-space "pre-wrap",
            :font-family "monospace",
            :font-size "13px",
            :color (case kind
                     :yin.repl.driver/result "#f8fbff"
                     :yin.repl.driver/response "#f8fbff"
                     :yin.repl.driver/diagnostic "#ff9b9b"
                     "#8b9ab8")}}
   text])


(defn main-view
  []
  (let [{:keys [editor-source history url driver]} @app-state
        status (get-in driver [:connection :status])]
    [:div
     {:style {:background
              "radial-gradient(circle at top, #122047 0%, #060817 58%)",
              :color "#f8fbff",
              :font-family "system-ui, sans-serif",
              :min-height "100vh",
              :padding "96px 20px 32px"}}
     [:div
      {:style {:max-width "1400px",
               :margin "0 auto",
               :display "flex",
               :flex-direction "column",
               :gap "20px"}}
      [:div
       {:style {:display "flex",
                :flex-wrap "wrap",
                :justify-content "space-between",
                :gap "16px",
                :align-items "end"}}
       [:div {:style {:max-width "760px"}}
        [:div
         {:style {:color "#7bc1ff",
                  :font-size "12px",
                  :font-weight "700",
                  :letter-spacing "0.12em",
                  :text-transform "uppercase",
                  :margin-bottom "10px"}} "Browser Yin REPL (v2)"]
        [:h1
         {:style {:margin "0 0 10px",
                  :font-size "clamp(2rem, 4vw, 3.5rem)",
                  :line-height "1.05"}}
         "CodeMirror speaks Clojure, yin.repl stays remote"]
        [:p
         {:style {:margin 0,
                  :color "#b7c7e6",
                  :font-size "16px",
                  :line-height "1.6"}}
         "This browser demo opens a DaoStream v2 WebSocket client to a remote "
         [:code "yin.repl"] " server. " [:code "(connect \"daostream:ws://...\")"]
         " attaches, " [:code "(disconnect)"] " detaches, and every other line is Yin source."]]
       [:div
        {:style {:background "rgba(8,12,25,0.82)",
                 :border "1px solid #2d3b55",
                 :border-radius "16px",
                 :padding "16px",
                 :width "100%",
                 :max-width "420px",
                 :display "flex",
                 :flex-direction "column",
                 :gap "10px"}}
        [:div
         {:style {:font-size "12px",
                  :font-weight "700",
                  :letter-spacing "0.08em",
                  :text-transform "uppercase",
                  :color "#8b9ab8"}} "Remote server"]
        [:code
         {:style {:display "block",
                  :white-space "pre-wrap",
                  :font-size "12px",
                  :line-height "1.5",
                  :color "#dce7ff"}}
         "clj -M:clj-yin-repl --port 8080 --headless"]
        [:div {:style {:font-size "13px", :color "#b7c7e6", :line-height "1.5"}}
         "Use " [:code "Ctrl-Enter"] " or " [:code "Cmd-Enter"]
         " to send the current editor buffer as one Yin REPL line."]]]
      [:div
       {:style {:display "grid",
                :grid-template-columns (responsive/auto-fit-grid 360),
                :gap "20px"}}
       [:section
        {:style {:background "rgba(8,12,25,0.82)",
                 :border "1px solid #2d3b55",
                 :border-radius "18px",
                 :padding "18px",
                 :display "flex",
                 :flex-direction "column",
                 :gap "16px",
                 :min-height (responsive/fluid-height 380 70 860)}}
        [:div
         {:style {:display "flex",
                  :flex-wrap "wrap",
                  :gap "12px",
                  :align-items "center"}}
         [:div
          {:style {:display "flex",
                   :flex "1 1 260px",
                   :flex-wrap "wrap",
                   :gap "10px"}}
          [:input
           {:value url,
            :on-change #(swap! app-state assoc :url (.. % -target -value)),
            :placeholder "daostream:ws://localhost:8080",
            :style {:flex "1",
                    :min-width "0",
                    :background "#0b1120",
                    :border "1px solid #2d3b55",
                    :border-radius "12px",
                    :color "#f8fbff",
                    :font-size "14px",
                    :padding "12px 14px"}}]
          (if (= status :established)
            [:button
             {:on-click disconnect!,
              :style {:background "#1b243f",
                      :border "1px solid #36486f",
                      :border-radius "12px",
                      :color "#f8fbff",
                      :cursor "pointer",
                      :padding "12px 16px"}} "Disconnect"]
            [:button
             {:on-click connect!,
              :style {:background "#1f6feb",
                      :border "1px solid #388bfd",
                      :border-radius "12px",
                      :color "#f8fbff",
                      :cursor "pointer",
                      :padding "12px 16px"}} "Connect"])] [status-chip status]]
        [:div
         {:style {:display "flex",
                  :justify-content "space-between",
                  :align-items "center",
                  :flex-wrap "wrap",
                  :gap "12px"}}
         [:div
          [:div
           {:style {:font-size "12px",
                    :font-weight "700",
                    :letter-spacing "0.08em",
                    :text-transform "uppercase",
                    :color "#8b9ab8"}} "Editor"]
          [:div {:style {:color "#b7c7e6", :font-size "14px"}}
           "Send any Yin REPL form or command to the remote runtime."]]
         [:button
          {:on-click eval!,
           :style {:background "#2ea043",
                   :border "1px solid rgba(255,255,255,0.08)",
                   :border-radius "12px",
                   :color "#f8fbff",
                   :cursor "pointer",
                   :padding "12px 18px"}} "Eval"]]
        [:div
         {:style {:flex "1", :min-height (responsive/fluid-height 300 48 620)}}
         [codemirror-editor
          {:value editor-source,
           :on-change #(swap! app-state assoc :editor-source %),
           :on-submit eval!,
           :style {:height "100%"}}]]]
       [:section
        {:style {:background "rgba(8,12,25,0.82)",
                 :border "1px solid #2d3b55",
                 :border-radius "18px",
                 :padding "18px",
                 :display "flex",
                 :flex-direction "column",
                 :gap "16px",
                 :min-height (responsive/fluid-height 360 68 860)}}
        [:div
         {:style {:display "flex",
                  :justify-content "space-between",
                  :align-items "center",
                  :flex-wrap "wrap",
                  :gap "12px"}}
         [:div
          [:div
           {:style {:font-size "12px",
                    :font-weight "700",
                    :letter-spacing "0.08em",
                    :text-transform "uppercase",
                    :color "#8b9ab8"}} "Transcript"]
          [:div {:style {:color "#b7c7e6", :font-size "14px"}}
           "Lines published by the v2 REPL driver, in order."]]
         [:button
          {:on-click clear-history!,
           :style {:background "#1b243f",
                   :border "1px solid #36486f",
                   :border-radius "12px",
                   :color "#f8fbff",
                   :cursor "pointer",
                   :padding "10px 14px"}} "Clear"]]
        [:div
         {:style {:display "flex",
                  :flex-direction "column",
                  :gap "6px",
                  :overflow "auto",
                  :flex "1"}}
         (if (seq history)
           (for [row history] ^{:key (:id row)} [transcript-row row])
           [:div
            {:style {:border "1px dashed #31415f",
                     :border-radius "12px",
                     :padding "18px",
                     :color "#8b9ab8"}}
            "No output yet. Connect to a remote Yin REPL and evaluate the editor buffer."])]]]]]))
