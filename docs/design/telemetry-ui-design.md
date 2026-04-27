# VM Telemetry Viewer UI Design

## Overview

A Reagent browser app that connects to a running `yin.repl` server via WebSockets to display:
- Live telemetry datoms from the VM's execution
- Parsed VM snapshots (control, environment, store, continuation)
- REPL interaction (eval Clojure, view results)
- Raw datom stream explorer

## Architecture

The app uses two bidirectional WebSocket channels:

```
Browser (Reagent)                          Node.js Server (yin.repl)
├── REPL Channel (8090)                    ← ws/listen! (accepting REPL clients)
│   └── dao.stream.apply req/resp           ↓ repl/serve!
│       (eval commands + results)
│
└── Telemetry Channel (8091)               ← ws/listen! (telemetry sink)
    └── raw datom 5-tuples [e a v t m]    ↓ yin.vm.telemetry/emit-snapshot
        (VM state events)                   (on every step)
```

**Why two channels?**
- REPL: `dao.stream.apply` uses request/response pairs. Caller must poll for matching response.
- Telemetry: one-way push of datoms. Server emits, browser consumes without coordination.

Separating them allows each to flow at its own rate without deadlock.

## Server Entry Point

**File:** `src/cljs/yin/vm/telemetry_server/node.cljs`

```clojure
(defn -main [& args]
  (let [telemetry-stream (ws/listen! 8091)
        state-atom       (atom (repl/create-state {:telemetry-stream telemetry-stream}))
        repl-server      (repl/serve! state-atom 8090)]
    (js/console.log "Telemetry server on :8091, REPL on :8090")))
```

Key: `repl/create-state` wires up the VM so that `yin.vm.telemetry/emit-snapshot` calls `ds/put!` on the telemetry stream on every VM step.

## Browser App State

**File:** `src/cljs/yin/vm/telemetry_viewer.cljs`

```clojure
(defonce app-state
  (r/atom {:repl-stream      nil          ; IDaoStreamReader connected to server
           :tel-stream       nil          ; IDaoStreamReader connected to telemetry
           :repl-cursor      {:position 0}
           :tel-cursor       {:position 0}
           :results          []           ; [{:input "..." :output "..."} ...]
           :datoms           []           ; [e a v t m] (last N, circular buffer)
           :snapshots        {}           ; {t {eid {:attr val ...}}} by time
           :status           :disconnected ; :disconnected | :connecting | :connected | :error
           :error-msg        nil
           :repl-port        8090
           :tel-port         8091}))
```

## UI Panels

### 1. Connection Panel

- Two port inputs (REPL, telemetry)
- Connect button
- Status indicator (color-coded)
- Manual disconnect

```clojure
(defn connection-panel []
  [:div.connection-panel
   [:div "REPL Port:  " [:input {:value (:repl-port @state) :on-change ...}]]
   [:div "Tel Port:   " [:input {:value (:tel-port @state) :on-change ...}]]
   [:button {:on-click connect!} "Connect"]
   [:div.status (name (:status @state))]])
```

### 2. REPL Panel

- Textarea for input
- Eval button
- Scrollable history (latest at bottom)
- Each result shows input + output + any error

```clojure
(defn repl-panel []
  [:div.repl
   [:textarea {:placeholder "Enter Clojure..." :value (:input @state) ...}]
   [:button {:on-click eval!} "Eval"]
   [:div.history
    (for [{:keys [input output error]} (:results @state)]
      [:div.result {:key input}
       [:div.input input]
       [:div.output (if error [:span.error error] output)]])]])
```

### 3. Datom Stream Panel

- Display raw datoms as vectors `[e a v t m]`
- Pagination or scrollable list (keep last 200)
- Color code types (nil, boolean, number, string, keyword, symbol, etc.)

```clojure
(defn datom-stream-panel []
  [:div.datoms
   [:h3 (str "Datoms (" (count (:datoms @state)) ")")]
   [:div.datom-list
    (for [[i [e a v t m]] (map-indexed vector (drop (max 0 (- N 50)) (:datoms @state)))]
      [:div.datom {:key i}
       [:code (pr-str [e a v t m])]])]])
```

### 4. VM Snapshot Panel

- Shows latest snapshot grouped by timestamp
- Structure: root entity with `:vm/type = :vm/snapshot`
- Follow refs to control, env, store, continuation
- Render summaries recursively

For each summary entity, display:
- `:vm.summary/type` (`:nil`, `:boolean`, `:number`, `:string`, `:vector`, `:map`, `:sequence`, `:opaque`, etc.)
- `:vm.summary/value` (scalar values)
- `:vm.summary/count` (collection length)
- `:vm.summary/item` or `:vm.summary/entry` (nested summaries)

```clojure
(defn snapshot-panel []
  (let [latest-t (apply max (keys (:snapshots @state)))
        entities (get (:snapshots @state) latest-t)]
    [:div.snapshot
     [:h3 (str "Snapshot (t=" latest-t ")")]
     (when-let [root (find-snapshot-root entities)]
       [:div
        [:div "step: " (:vm/step root)]
        [:div "halted: " (:vm/halted? root)]
        [:div "blocked: " (:vm/blocked? root)]
        [:div "control: " (render-summary entities (:vm/control root))]
        [:div "env: " (render-summary entities (:vm/env root))]
        [:div "store: " (render-summary entities (:vm/store root))]
        [:div "k: " (render-summary entities (:vm/k root))]])]))
```

### 5. Helper Functions

**Index datoms by entity:**
```clojure
(defn index-datoms [datoms]
  "Group datoms by entity; return {eid {attr val ...}}"
  (let [entities (atom {})]
    (doseq [[e a v t m] datoms]
      (swap! entities update e assoc a v))
    @entities))
```

**Parse snapshots by time:**
```clojure
(defn parse-snapshots [datoms]
  "Group indexed entities by t. Return {t {eid {...}}}"
  (let [by-time (atom {})]
    (doseq [[e a v t m] datoms]
      (swap! by-time update t (fn [es] (-> es (or {}) (update e assoc a v)))))
    @by-time))
```

**Find snapshot root:**
```clojure
(defn find-snapshot-root [entities]
  (first (filter (fn [[_eid entity]]
                   (= :vm/snapshot (:vm/type entity)))
                 entities)))
```

**Render a summary entity recursively:**
```clojure
(defn render-summary [entities eid]
  (when (integer? eid)
    (let [entity (get entities eid)]
      (case (:vm.summary/type entity)
        :nil       "nil"
        :boolean   (:vm.summary/value entity)
        :number    (:vm.summary/value entity)
        :string    (pr-str (:vm.summary/value entity))
        :keyword   (:vm.summary/value entity)
        :symbol    (:vm.summary/value entity)
        :vector    [:span
                    "["
                    (for [item-eid (:vm.summary/item entity)]
                      (render-summary entities item-eid))
                    "]"]
        :map       [:span
                    "{"
                    (for [entry-eid (:vm.summary/entry entity)]
                      (let [entry (get entities entry-eid)
                            val-eid (:vm.summary/value-ref entry)]
                        [:span
                         (:vm.summary/key entry) " "
                         (render-summary entities val-eid)]))
                    "}"]
        :opaque    "<opaque>"
        nil))))
```

## Polling Strategy

### REPL Polling
On eval, store request-id → atom. Polling loop checks for matching response every 10ms.

```clojure
(defn eval! [input-str]
  (let [req-id (keyword (gensym "req-"))
        response-atom (atom nil)]
    (dao-apply/put-request! (:repl-stream @state) req-id :op/eval [input-str])
    (letfn [(poll-response [cursor attempts]
              (if (>= attempts 500)
                (swap! state assoc-in [:results] ...)
                (let [result (dao-apply/next-response (:repl-stream @state) cursor)]
                  (if (map? result)
                    (if (= req-id (dao-apply/response-id (:ok result)))
                      (reset! response-atom (:ok result))
                      (poll-response (:cursor result) (inc attempts)))
                    (js/setTimeout #(poll-response cursor (inc attempts)) 10)))))]
      (poll-response (:repl-cursor @state) 0))))
```

### Telemetry Polling
Every 50ms, drain available datoms from telemetry stream, update state.

```clojure
(defn drain-telemetry! []
  (letfn [(loop-drain [cursor datoms]
            (let [result (ds/next (:tel-stream @state) cursor)]
              (if (map? result)
                (loop-drain (:cursor result) (conj datoms (:ok result)))
                datoms)))]
    (let [new-datoms (loop-drain (:tel-cursor @state) [])]
      (swap! state (fn [s] (-> s
                              (update :datoms #(vec (take-last 500 (concat % new-datoms))))
                              (update :snapshots merge (parse-snapshots new-datoms))
                              (assoc :tel-cursor ...))))
      (js/setTimeout drain-telemetry! 50))))
```

## HTML Entry Point

**File:** `public/telemetry-viewer.html`

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link href="/css/datomworld.css" rel="stylesheet">
  <title>VM Telemetry Viewer</title>
</head>
<body>
  <div id="app"></div>
  <script src="/js/telemetry-viewer/main.js"></script>
</body>
</html>
```

## Shadow-cljs Configuration

Add to `shadow-cljs.edn`:

```edn
:telemetry-server
{:target     :node-script
 :main       yin.vm.telemetry-server.node/-main
 :output-to  "target/telemetry-server.js"}

:telemetry-viewer
{:target   :browser
 :devtools {:use-document-protocol true}
 :closure-defines
 {yin.vm.telemetry-viewer/REPL_PORT 8090
  yin.vm.telemetry-viewer/TEL_PORT  8091}
 :modules  {:main {:init-fn yin.vm.telemetry-viewer/init
                   :output-dir "public/js/telemetry-viewer"}}}
```

## Build & Run

```bash
# Build both
npx shadow-cljs compile telemetry-server telemetry-viewer

# Run server in one terminal
node target/telemetry-server.js

# Serve browser in another
npx shadow-cljs watch telemetry-viewer &
# Then open http://localhost:9000/telemetry-viewer.html
```

## Testing Strategy (TDD)

**File:** `test/yin/vm/telemetry_viewer_test.cljs`

```clojure
(deftest test-index-datoms
  (is (= {1 {:a 10, :b 20}, 2 {:c 30}}
         (index-datoms [[1 :a 10 0 0] [1 :b 20 0 0] [2 :c 30 0 0]]))))

(deftest test-parse-snapshots
  (let [datoms [[1 :vm/type :vm/snapshot 5 0]
                [2 :vm.summary/type :number 5 0]
                [3 :vm/type :vm/snapshot 6 0]]
        parsed (parse-snapshots datoms)]
    (is (= 5 (apply max (keys parsed))))
    (is (= {:vm/type :vm/snapshot} (get-in parsed [5 1])))))

(deftest test-find-snapshot-root
  (let [entities {1 {:vm/type :vm/snapshot :vm/step 0}
                  2 {:vm.summary/type :number}}]
    (is (= [1 {:vm/type :vm/snapshot :vm/step 0}]
           (find-snapshot-root entities)))))

(deftest test-render-summary
  (let [entities {1 {:vm.summary/type :number :vm.summary/value 42}
                  2 {:vm.summary/type :vector :vm.summary/item [1]}}]
    (is (= 42 (render-summary entities 1)))
    (is (some? (render-summary entities 2)))))
```

Run: `clj -M:test test/yin/vm/telemetry_viewer_test.cljs`
