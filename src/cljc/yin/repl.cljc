(ns yin.repl
  (:require #?(:cljd ["dart:async" :as async])
            #?(:cljd ["dart:core" :as core])
            #?(:cljd ["dart:convert" :as convert])
            #?(:cljd ["dart:io" :as io])
            #?(:cljs [cljs.reader :as edn]
               :default [clojure.edn :as edn])
            [clojure.string :as str]
            [dao.pretty :as pretty]
            [dao.stream :as ds]
            [dao.stream.ws :as ws]
            [dao.stream.rpc.client :as rpc-client]
            [dao.stream.rpc.server :as rpc-server]
            [yang.clojure :as yang.clojure]
            [yang.php :as yang.php]
            [yang.python :as yang.python]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.register :as register]
            [yin.vm.semantic :as semantic]
            [yin.vm.stack :as stack]))


(def ^:private command-heads
  #{'vm 'lang 'compile 'reset 'connect 'disconnect 'telemetry 'repl-state 'help
    'quit})


(def ^:private local-only-command-heads
  #{'connect 'disconnect 'telemetry 'help 'quit 'repl-state})


(def ^:private vm-constructors
  {:ast-walker ast-walker/create-vm,
   :semantic semantic/create-vm,
   :register register/create-vm,
   :stack stack/create-vm})


(def ^:private vm-labels
  {:ast-walker "ASTWalkerVM",
   :semantic "SemanticVM",
   :register "RegisterVM",
   :stack "StackVM"})


(def ^:private lang-labels {:clojure "Clojure", :python "Python", :php "PHP"})


(defn connection-status-text
  "Return a human-readable status text for a REPL server based on the number of active clients."
  [client-count]
  (if (pos? client-count) "client connected" "listening"))


(defn- log-repl!
  [message]
  #?(:cljs (js/console.log message)
     :default (println message)))


(def ^:private help-text
  #?(:clj
     (str
       "Commands:\n" "  (vm :semantic | :register | :stack | :ast-walker)\n"
       "  (lang :clojure | :python | :php)\n" "  (compile expr)\n"
       "  (reset)\n" "  (connect \"daostream:ws://host:port\")\n"
       "  (disconnect)\n" "  (telemetry)\n"
       "  (repl-state)\n" "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated values")
     :default
     (str
       "Commands:\n" "  (vm :semantic | :register | :stack | :ast-walker)\n"
       "  (lang :clojure | :python | :php)\n" "  (compile expr)\n"
       "  (reset)\n" "  (telemetry)\n"
       "  (repl-state)\n" "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated values")))


(defn- quote-symbols
  [x]
  (cond (symbol? x) (list 'quote x)
        (vector? x) (mapv quote-symbols x)
        (map? x)
        (into {} (map (fn [[k v]] [(quote-symbols k) (quote-symbols v)]) x))
        (or (list? x) (seq? x))
        (if (= 'quote (first x)) x (map quote-symbols x))
        :else x))


(defn- format-value
  [value]
  (str/trimr (pretty/pp-str (quote-symbols value))))


(defn- print-arg
  [value]
  (if (string? value) value (format-value value)))


(defn- print-text
  [args]
  (str/join " " (map print-arg args)))


(defn- prn-text
  [args]
  (str (str/join " " (map format-value args)) "\n"))


(defn- emit-output!
  [output-stream op text]
  (when output-stream
    (ds/put! output-stream {:type :repl/output, :op op, :text text}))
  nil)


(defn- make-repl-primitives
  [output-stream]
  (merge
    vm/primitives
    {'print (fn [& args] (emit-output! output-stream :print (print-text args))),
     'println
     (fn [& args]
       (emit-output! output-stream :println (str (print-text args) "\n"))),
     'prn (fn [& args] (emit-output! output-stream :prn (prn-text args))),
     'ast->datoms vm/ast->datoms,
     'datoms->ast vm/datoms->ast}))


(defn- telemetry-config
  [vm-type stream]
  (when stream
    {:stream stream, :vm-id (keyword (str "yin.repl/" (name vm-type)))}))


(defn- make-request-scope
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))
     :cljd (str (rand-int 2147483647)
                "-" (rand-int 2147483647)
                "-" (rand-int 2147483647))))


(defn- install-telemetry
  [vm-state vm-type stream]
  (-> vm-state
      (assoc :telemetry (telemetry-config vm-type stream))
      (assoc :vm-model vm-type)
      (update :telemetry-step #(or % 0))
      (update :telemetry-t #(or % 0))))


(declare make-local-stream)


(defn- make-vm
  ([vm-type] (make-vm vm-type nil nil))
  ([vm-type telemetry-stream] (make-vm vm-type telemetry-stream nil))
  ([vm-type telemetry-stream output-stream]
   (when-not (contains? vm-constructors vm-type)
     (throw (ex-info "Unknown Yin REPL VM type"
                     {:vm-type vm-type,
                      :supported (vec (keys vm-constructors))})))
   (let [constructor (get vm-constructors vm-type)
         in-stream (make-local-stream)]
     (cond-> (constructor {:primitives (make-repl-primitives output-stream),
                           :in-stream in-stream})
       telemetry-stream (install-telemetry vm-type telemetry-stream)))))


(defn create-state
  ([] (create-state {}))
  ([{:keys [exposed-ports exposed-streams lang output-cursor output-stream
            request-scope telemetry-stream vm-type],
     :or {exposed-ports {},
          exposed-streams {},
          lang :clojure,
          output-cursor {:position 0},
          vm-type :semantic}}]
   (let [output-stream (or output-stream (make-local-stream))]
     {:lang lang,
      :exposed-ports exposed-ports,
      :exposed-streams exposed-streams,
      :last-value nil,
      :last-value-2 nil,
      :last-value-3 nil,
      :output-cursor output-cursor,
      :output-stream output-stream,
      :remote-endpoint nil,
      :remote-response-cursor {:position 0},
      :request-id 0,
      :request-scope (or request-scope (make-request-scope)),
      :running? true,
      :telemetry-cursor {:position 0},
      :telemetry-mode (when telemetry-stream :stderr),
      :telemetry-stream telemetry-stream,
      :vm (make-vm vm-type telemetry-stream output-stream),
      :vm-type vm-type})))


(defn- format-error
  [error]
  (str "Error: "
       #?(:clj (or (ex-message error) (str error))
          :cljs (or (ex-message error) (.-message error) (str error))
          :cljd (or (ex-message error) (str error)))))


(defn- stream-status
  [stream]
  (try (if (ds/closed? stream) :closed :open)
       (catch #?(:clj Exception
                 :cljs js/Error
                 :cljd Object)
              _
         :unknown)))


(defn- stream-summary
  [stream]
  {:present? (some? stream),
   :status (if stream (stream-status stream) :absent)})


(defn- telemetry-mode-summary
  [telemetry-mode]
  (if (map? telemetry-mode)
    (cond-> {:type (:type telemetry-mode)}
      (:url telemetry-mode) (assoc :url (:url telemetry-mode)))
    telemetry-mode))


(defn- exposed-streams-summary
  [streams]
  (->> streams
       (sort-by (comp str key))
       (map (fn [[stream-name stream]] [stream-name (stream-summary stream)]))
       (into {})))


(defn- exposed-ports-summary
  [ports]
  (->> ports
       (sort-by (comp str key))
       (mapv (fn [[port-name {:keys [transport port stream url]}]]
               (cond-> {:name port-name}
                 transport (assoc :transport transport)
                 (some? port) (assoc :port port)
                 stream (assoc :stream stream)
                 url (assoc :url url))))))


(defn repl-state
  "Return a serializable summary of shell state, streams, and exposed ports."
  [state]
  (let [remote-endpoint (:remote-endpoint state)]
    {:lang (:lang state),
     :vm {:type (:vm-type state),
          :halted? (try (vm/halted? (:vm state))
                        (catch #?(:clj Exception
                                  :cljs js/Error
                                  :cljd Object)
                               _
                          :unknown)),
          :blocked? (try (vm/blocked? (:vm state))
                         (catch #?(:clj Exception
                                   :cljs js/Error
                                   :cljd Object)
                                _
                           :unknown))},
     :running? (:running? state),
     :output {:cursor (:output-cursor state),
              :stream (stream-summary (:output-stream state))},
     :telemetry {:enabled? (some? (:telemetry-stream state)),
                 :mode (telemetry-mode-summary (:telemetry-mode state)),
                 :cursor (:telemetry-cursor state),
                 :stream (stream-summary (:telemetry-stream state))},
     :remote
     {:connected? (some? remote-endpoint),
      :request-id (if remote-endpoint @(:request-id-atom remote-endpoint) 0),
      :response-cursor (if remote-endpoint
                         @(:response-cursor-atom remote-endpoint)
                         {:position 0}),
      :streams {:request (stream-summary (when remote-endpoint
                                           (:stream remote-endpoint))),
                :response (stream-summary (when remote-endpoint
                                            (:stream remote-endpoint)))}},
     :streams {:output (stream-summary (:output-stream state)),
               :telemetry (stream-summary (:telemetry-stream state)),
               :remote/request (stream-summary (when remote-endpoint
                                                 (:stream remote-endpoint))),
               :remote/response (stream-summary (when remote-endpoint
                                                  (:stream remote-endpoint)))},
     :exposed-streams (exposed-streams-summary (:exposed-streams state)),
     :ports (exposed-ports-summary (:exposed-ports state))}))


(defn- shell-command-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? command-heads (first form))))


(defn- local-only-command-form?
  [form]
  (and (shell-command-form? form)
       (contains? local-only-command-heads (first form))))


(defn- datom?
  [value]
  (and (vector? value) (= 5 (count value))))


(defn- datom-stream?
  [value]
  (and (vector? value) (seq value) (every? datom? value)))


(defn- ast-map?
  [value]
  (and (map? value) (keyword? (:type value))))


(defn- read-clojure-forms
  [input-str]
  ;; Use the full Clojure reader (not EDN) so that reader macros like
  ;; ' (quote), # (dispatch), etc. are supported in REPL input.
  #?(:clj (clojure.core/read-string (str "[" input-str "]"))
     :cljs (cljs.reader/read-string (str "[" input-str "]"))
     :cljd (edn/read-string (str "[" input-str "]"))))


(defn- normalize-daostream-url
  [url]
  (if (str/starts-with? url "daostream:") (subs url (count "daostream:")) url))


(defn- make-local-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(defn- open-telemetry-sink
  [url]
  (ws/connect! (normalize-daostream-url url)))


(defn- attach-remote-endpoint
  [state client]
  (assoc state :remote-endpoint client))


(defn- detach-remote-endpoint
  [state]
  (when-let [client (:remote-endpoint state)] (rpc-client/close! client))
  (assoc state :remote-endpoint nil))


(defn- close-telemetry-sink!
  [state]
  (when-let [telemetry-mode (:telemetry-mode state)]
    (when (= :stream (:type telemetry-mode))
      (ds/close! (:stream telemetry-mode)))))


(defn- set-telemetry-stream
  [state telemetry-stream telemetry-mode]
  (-> state
      (assoc :telemetry-stream telemetry-stream)
      (assoc :telemetry-mode telemetry-mode)
      (assoc :telemetry-cursor {:position 0})
      (update :vm install-telemetry (:vm-type state) telemetry-stream)))


(defn- collect-telemetry
  [state]
  (if-not (:telemetry-stream state)
    [state []]
    (loop [cursor (:telemetry-cursor state)
           datoms []]
      (let [result (ds/next (:telemetry-stream state) cursor)]
        (if (map? result)
          (recur (:cursor result) (conj datoms (:ok result)))
          [(assoc state :telemetry-cursor cursor) datoms])))))


(defn- flush-telemetry
  [state]
  (let [[state' datoms] (collect-telemetry state)
        telemetry-mode (:telemetry-mode state')]
    (doseq [datom datoms]
      (case (if (map? telemetry-mode) (:type telemetry-mode) telemetry-mode)
        :stderr #?(:clj (binding [*out* *err*] (println (format-value datom)))
                   :cljs (*print-err-fn* (format-value datom))
                   :cljd (binding [*out* *err*] (println (format-value datom))))
        :stream (ds/put! (:stream telemetry-mode) datom)
        nil))
    state'))


(defn- collect-output
  [state]
  (if-not (:output-stream state)
    [state ""]
    (loop [cursor (:output-cursor state)
           chunks []]
      (let [result (ds/next (:output-stream state) cursor)]
        (if (map? result)
          (let [event (:ok result)
                text (if (map? event) (:text event) (str event))]
            (recur (:cursor result) (conj chunks text)))
          [(assoc state :output-cursor cursor) (apply str chunks)])))))


(defn- format-eval-result
  [state value]
  (let [[state' output-text] (collect-output state)]
    [state' (str output-text (format-value value))]))


(defn- inject-last-value
  [state]
  (let [vm (:vm state)
        v1 (:last-value state)
        v2 (:last-value-2 state)
        v3 (:last-value-3 state)]
    (assoc state :vm (update vm :store assoc '*1 v1 '*2 v2 '*3 v3))))


(defn- record-last-value
  [state state-after value]
  (assoc state-after
         :last-value value
         :last-value-2 (:last-value state)
         :last-value-3 (:last-value-2 state)))


(defn- finalize-eval
  [state vm']
  (let [value (vm/value vm')
        [state' result-str] (format-eval-result (-> state
                                                    (assoc :vm vm')
                                                    flush-telemetry)
                                                value)]
    [(record-last-value state state' value) result-str]))


(defn- eval-datoms
  [state datoms]
  (let [state' (inject-last-value state)
        vm0 (:vm state')
        _ (ds/put! (:in-stream vm0) (vec datoms))
        vm' (vm/run vm0)]
    (if (vm/halted? vm')
      (finalize-eval state' vm')
      (throw
        (ex-info
          "Datom stream did not form a complete, runnable Yin VM program.
Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
          {:root-id (:root-id (vm/index-datoms datoms)),
           :datom-count (count datoms)})))))


(defn- eval-ast
  [state ast]
  (let [state' (inject-last-value state)
        vm' (vm/eval (:vm state') ast)]
    (finalize-eval state' vm')))


(defn- compile-clojure-forms
  [forms]
  (if (= 1 (count forms))
    (yang.clojure/compile (first forms))
    (yang.clojure/compile-program forms)))


(defn- compile-source
  [lang input-str]
  (case lang
    :clojure (compile-clojure-forms (read-clojure-forms input-str))
    :python (yang.python/compile input-str)
    :php (yang.php/compile input-str)
    (throw (ex-info "Unsupported Yin REPL language" {:lang lang}))))


(defn- eval-clojure-forms
  [state forms]
  (eval-ast state (compile-clojure-forms forms)))


(defn- eval-source
  [state input-str]
  (eval-ast state (compile-source (:lang state) input-str)))


(defn- compile-command-ast
  [state arg]
  (case (:lang state)
    :clojure (if (string? arg)
               (compile-source :clojure arg)
               (yang.clojure/compile arg))
    :python (if (string? arg)
              (compile-source :python arg)
              (throw (ex-info "Python compile command expects a source string"
                              {:arg arg})))
    :php (if (string? arg)
           (compile-source :php arg)
           (throw (ex-info "PHP compile command expects a source string"
                           {:arg arg})))
    (throw (ex-info "Unsupported Yin REPL language" {:lang (:lang state)}))))


(defn- render-compile-output
  [ast]
  (str "AST:\n" (format-value ast)
       "\n\nDatoms:\n" (format-value (vec (vm/ast->datoms ast)))))


(defn- deliver-result
  [val]
  #?(:clj (let [p (promise)]
            (deliver p val)
            p)
     :cljs (js/Promise.resolve val)
     :cljd (async/Future.value val)))


(defn- eval-remote-input
  [state input-str]
  (let [client (:remote-endpoint state)]
    #?(:clj (deliver-result
              (try [state (rpc-client/call! client :op/eval [input-str])]
                   (catch Exception e [state (format-error e)])))
       :cljs (-> (rpc-client/call! client :op/eval [input-str])
                 (.then (fn [res] [state res]))
                 (.catch (fn [err] [state (format-error err)])))
       :cljd (-> ^async/Future (rpc-client/call! client :op/eval [input-str])
                 (.then (fn [res] [state res]))
                 (.catchError (fn [err] [state (format-error err)]))))))


(declare eval-input)


(defn- handle-command
  [state form]
  (let [[command & args] form]
    (case command
      vm (let [vm-type (first args)]
           (when-not (contains? vm-constructors vm-type)
             (throw (ex-info "Unknown Yin REPL VM type"
                             {:vm-type vm-type,
                              :supported (vec (keys vm-constructors))})))
           [(assoc state
                   :vm-type vm-type
                   :vm (make-vm vm-type
                                (:telemetry-stream state)
                                (:output-stream state)))
            (str "Switched to " (get vm-labels vm-type) " (store cleared)")])
      lang (let [lang (first args)]
             (when-not (contains? lang-labels lang)
               (throw (ex-info "Unknown Yin REPL language" {:lang lang})))
             [(assoc state :lang lang)
              (str "Switched to " (get lang-labels lang))])
      compile (let [ast (compile-command-ast state (first args))]
                [state (render-compile-output ast)])
      reset [(assoc state
                    :vm (make-vm (:vm-type state)
                                 (:telemetry-stream state)
                                 (:output-stream state)))
             (str (get vm-labels (:vm-type state)) " reset")]
      connect
      #?(:clj (let [url (normalize-daostream-url (first args))
                    client (rpc-client/connect! url)
                    state' (detach-remote-endpoint state)]
                [(attach-remote-endpoint state' client)
                 (str "Connected to " url)])
         :cljs (let [url (normalize-daostream-url (first args))
                     p (rpc-client/connect! url)]
                 (-> p
                     (.then (fn [client]
                              (let [state' (detach-remote-endpoint state)]
                                [(attach-remote-endpoint state' client)
                                 (str "Connected to " url)])))
                     (.catch (fn [err] [state (format-error err)]))))
         :cljd (let [url (normalize-daostream-url (first args))
                     f (rpc-client/connect! url)]
                 (-> ^async/Future f
                     (.then (fn [client]
                              (let [state' (detach-remote-endpoint state)]
                                [(attach-remote-endpoint state' client)
                                 (str "Connected to " url)])))
                     (.catchError (fn [err] [state (format-error err)])))))
      disconnect [(detach-remote-endpoint state)
                  "Disconnected from remote shell"]
      telemetry
      (let [arg (first args)]
        (cond arg (let [telemetry-stream (or (:telemetry-stream state)
                                             (make-local-stream))
                        telemetry-url (normalize-daostream-url arg)
                        _ (close-telemetry-sink! state)
                        sink-stream (open-telemetry-sink arg)]
                    [(set-telemetry-stream state
                                           telemetry-stream
                                           {:type :stream,
                                            :stream sink-stream,
                                            :url telemetry-url})
                     (str "Telemetry routed to " telemetry-url)])
              (:telemetry-stream state)
              (do (close-telemetry-sink! state)
                  [(set-telemetry-stream state nil nil)
                   "Telemetry disabled"])
              :else (let [telemetry-stream (make-local-stream)]
                      [(set-telemetry-stream state telemetry-stream :stderr)
                       "Telemetry enabled (stderr)"])))
      help [state help-text]
      repl-state [state (format-value (repl-state state))]
      quit [(assoc state :running? false) "Bye"]
      (throw (ex-info "Unknown Yin REPL command" {:command command})))))


(defn- bracket-balance
  "Count open minus close brackets in s, ignoring string literals and ; comments.
   Positive → input is incomplete (unclosed brackets).
   Zero     → balanced.
   Negative → extra closers."
  [s]
  (loop [chars (seq s)
         depth 0
         in-str? false]
    (if (empty? chars)
      depth
      (let [ch (first chars)
            tail (rest chars)]
        (cond in-str? (cond (= ch \\) (recur (rest tail) depth true)
                            (= ch \") (recur tail depth false)
                            :else (recur tail depth true))
              (= ch \") (recur tail depth true)
              (= ch \;) (recur (drop-while #(not= % \newline) tail) depth false)
              (#{\( \[ \{} ch) (recur tail (inc depth) false)
              (#{\) \] \}} ch) (recur tail (dec depth) false)
              :else (recur tail depth false))))))


(defn- eval-input-sync-preparsed
  [state' trimmed parsed]
  (try (cond (and (:forms parsed)
                  (= 1 (count (:forms parsed)))
                  (local-only-command-form? (first (:forms parsed))))
             (handle-command state' (first (:forms parsed)))
             (and (:forms parsed)
                  (= 1 (count (:forms parsed)))
                  (shell-command-form? (first (:forms parsed))))
             (handle-command state' (first (:forms parsed)))
             (and (:forms parsed)
                  (= 1 (count (:forms parsed)))
                  (datom-stream? (first (:forms parsed))))
             (eval-datoms state' (first (:forms parsed)))
             (and (:forms parsed)
                  (= 1 (count (:forms parsed)))
                  (ast-map? (first (:forms parsed))))
             (eval-ast state' (first (:forms parsed)))
             (:forms parsed) (if (= :clojure (:lang state'))
                               (eval-clojure-forms state' (:forms parsed))
                               (eval-source state' trimmed))
             (= :clojure (:lang state')) [state' (format-error (:error parsed))]
             :else (eval-source state' trimmed))
       (catch #?(:clj Exception
                 :cljs js/Error
                 :cljd Object)
              error
         (let [[state'' output-text] (collect-output state')]
           [state'' (str output-text (format-error error))]))))


(defn- eval-input-sync
  [state input-str]
  (let [pending (:pending-input state "")
        combined (if (str/blank? pending)
                   (str/trim input-str)
                   (str pending "\n" (str/trim input-str)))
        balance (bracket-balance combined)]
    (if (pos? balance)
      [(assoc state :pending-input combined) ""]
      (let [state' (dissoc state :pending-input)
            trimmed combined
            parsed (try {:forms (read-clojure-forms trimmed)}
                        (catch #?(:clj Exception
                                  :cljs js/Error
                                  :cljd Object)
                               error
                          {:error error}))]
        (eval-input-sync-preparsed state' trimmed parsed)))))


(defn eval-input
  [state input-str]
  (let [pending (:pending-input state "")
        combined (if (str/blank? pending)
                   (str/trim input-str)
                   (str pending "\n" (str/trim input-str)))
        balance (bracket-balance combined)]
    (if (pos? balance)
      (deliver-result [(assoc state :pending-input combined) ""])
      (let [state' (dissoc state :pending-input)
            trimmed combined
            parsed (try {:forms (read-clojure-forms trimmed)}
                        (catch #?(:clj Exception
                                  :cljs js/Error
                                  :cljd Object)
                               error
                          {:error error}))
            local-only? (and (:forms parsed)
                             (= 1 (count (:forms parsed)))
                             (local-only-command-form? (first (:forms
                                                                parsed))))]
        (if (and (:remote-endpoint state') (not local-only?))
          (eval-remote-input state' trimmed)
          (let [res (eval-input-sync-preparsed state' trimmed parsed)]
            (if #?(:cljs (and res (.-then res))
                   :cljd (instance? async/Future res)
                   :default false)
              res
              (deliver-result res))))))))


(defn make-handlers
  [state-atom]
  {:op/eval (fn [input-str]
              (let [state @state-atom]
                (if (:remote-endpoint state)
                  (let [res (eval-input state input-str)]
                    #?(:clj (let [[state' result] @res]
                              (reset! state-atom state')
                              result)
                       :cljs (.then res
                                    (fn [[state' result]]
                                      (reset! state-atom state')
                                      result))
                       :cljd (.then ^async/Future res
                                    (fn [[state' result]]
                                      (reset! state-atom state')
                                      result))))
                  (let [res (eval-input-sync state input-str)]
                    (if #?(:cljs (and res (.-then res))
                           :cljd (instance? async/Future res)
                           :default false)
                      #?(:cljs (.then res
                                      (fn [[state' result]]
                                        (reset! state-atom state')
                                        result))
                         :cljd (.then ^async/Future res
                                      (fn [[state' result]]
                                        (reset! state-atom state')
                                        result))
                         :default nil)
                      (let [[state' result] res]
                        (reset! state-atom state')
                        result))))))})


(defn- expose-repl-server
  [state port server-handle]
  (-> state
      (assoc-in [:exposed-ports :repl]
                {:transport :websocket, :port port, :server server-handle})))


(defn- unexpose-repl-server
  [state]
  (-> state
      (update :exposed-ports #(dissoc (or % {}) :repl))))


(defn serve!
  ([state-atom port] (serve! state-atom port {}))
  ([state-atom port opts]
   (let [host (or (:host opts) "127.0.0.1")
         handlers (make-handlers state-atom)
         server-handle (rpc-server/start!
                         handlers
                         port
                         (assoc opts
                                :host host
                                :on-connect
                                (fn [stream]
                                  (log-repl!
                                    (str "Yin REPL client connected on ws://" host
                                         ":" port))
                                  (when-let [on-connect (:on-connect opts)]
                                    (on-connect stream)))))]
     (swap! state-atom expose-repl-server port (:server server-handle))
     (log-repl! (str "Yin REPL server listening on ws://" host ":" port))
     {:port port,
      :server (:server server-handle),
      :conns (:conns server-handle),
      :stop! (fn []
               (rpc-server/stop! server-handle)
               (swap! state-atom unexpose-repl-server))})))


(defn- parse-args
  [args]
  (loop [args (seq args)
         opts {:headless? false,
               :host nil,
               :port nil,
               :telemetry? false,
               :telemetry-stream nil}]
    (if-let [arg (first args)]
      (case arg
        "--port" (let [port-str (second args)]
                   (recur (nnext args)
                          (assoc opts
                                 :port (if port-str
                                         #?(:clj (Long/parseLong port-str)
                                            :cljs (js/parseInt port-str 10)
                                            :cljd (int/parse port-str))
                                         nil))))
        "--host" (recur (nnext args) (assoc opts :host (second args)))
        "--telemetry" (recur (next args) (assoc opts :telemetry? true))
        "--telemetry-stream"
        (recur (nnext args) (assoc opts :telemetry-stream (second args)))
        "--headless" (recur (next args) (assoc opts :headless? true))
        (recur (next args) (update opts :extra (fnil conj []) arg)))
      opts)))


#?(:clj (do (defn- configure-state
              [state opts]
              (let [[state telemetry-msg]
                    @(cond (:telemetry-stream opts)
                           (eval-input state
                                       (str "(telemetry "
                                            (pr-str (:telemetry-stream
                                                      opts))
                                            ")"))
                           (:telemetry? opts) (eval-input state "(telemetry)")
                           :else (deliver (promise) [state nil]))]
                (when telemetry-msg (println telemetry-msg))
                state))
            (defn- print-prompt!
              []
              (print "yin> ") (flush))
            (defn- run-cli!
              [state-atom]
              (loop []
                (when (:running? @state-atom)
                  (print-prompt!)
                  (when-let [line (read-line)]
                    (when-not (str/blank? line)
                      (let [[state result] @(eval-input @state-atom line)]
                        (reset! state-atom state)
                        (when (some? result) (println result))))
                    (recur)))))
            (defn -main
              [& args]
              (let [opts (parse-args args)
                    state-atom (atom (configure-state (create-state) opts))
                    server (when-let [port (:port opts)]
                             (serve! state-atom port {:host (:host opts)}))]
                (try (if (:headless? opts)
                       (when server @(promise))
                       (run-cli! state-atom))
                     (finally (when server ((:stop! server)))))
                (.halt (Runtime/getRuntime) 0)))))


#?(:cljs
   (do
     (defn- configure-state-cljs
       [state opts]
       (let [p (cond (:telemetry-stream opts)
                     (eval-input state
                                 (str "(telemetry "
                                      (pr-str (:telemetry-stream opts))
                                      ")"))
                     (:telemetry? opts) (eval-input state "(telemetry)")
                     :else (js/Promise.resolve [state nil]))]
         (.then p
                (fn [[state telemetry-msg]]
                  (when telemetry-msg (js/console.log telemetry-msg))
                  state))))
     (defn- run-cli-cljs!
       [state-atom]
       (let [readline (js/require "readline")
             rl (.createInterface readline
                                  #js {:input (.-stdin js/process),
                                       :output (.-stdout js/process),
                                       :prompt "yin> "})]
         (.prompt rl)
         (.on rl
              "line"
              (fn [line]
                (when-not (str/blank? line)
                  (-> (eval-input @state-atom line)
                      (.then (fn [[state result]]
                               (reset! state-atom state)
                               (when (some? result) (js/console.log result))
                               (if (:running? @state-atom)
                                 (.prompt rl)
                                 (.close rl))))))))
         (.on rl "close" (fn [] (js/process.exit 0)))))
     (defn -main
       [& args]
       (let [opts (parse-args args)]
         (-> (configure-state-cljs (create-state) opts)
             (.then
               (fn [state]
                 (let [state-atom (atom state)
                       server
                       (when-let [port (:port opts)]
                         (serve! state-atom port {:host (:host opts)}))]
                   (when (and (:headless? opts) (not server))
                     (js/console.error "Error: --headless requires --port")
                     (js/process.exit 1))
                   (when-not (:headless? opts)
                     (run-cli-cljs! state-atom))))))))))


#?(:cljd
   (do
     (defn- write-out!
       [message]
       (print message))
     (defn- write-err!
       [message]
       (binding [*out* *err*] (println message)))
     (defn- print-prompt!
       []
       (.write io/stdout "yin> ") (.flush io/stdout))
     (defn- print-continuation-prompt!
       []
       (.write io/stdout "yin.. ")
       (.flush io/stdout))
     (defn- clean-cljd-input
       "Works around a CLJD bug where spaces before closing delimiters cause reader errors."
       [s]
       (let [res (reduce
                   (fn [{:keys [state], :as acc} char]
                     (case state
                       :normal (case char
                                 \" (-> acc
                                        (update :result conj char)
                                        (assoc :state :string))
                                 \; (-> acc
                                        (update :result conj char)
                                        (assoc :state :comment))
                                 (\space \tab \newline \return)
                                 (assoc acc :state :whitespace)
                                 (update acc :result conj char))
                       :string (if (= char \")
                                 (-> acc
                                     (update :result conj char)
                                     (assoc :state :normal))
                                 (if (= char \\)
                                   (-> acc
                                       (update :result conj char)
                                       (assoc :state :string-escape))
                                   (update acc :result conj char)))
                       :string-escape (-> acc
                                          (update :result conj char)
                                          (assoc :state :string))
                       :comment (if (= char \newline)
                                  (-> acc
                                      (update :result conj char)
                                      (assoc :state :normal))
                                  (update acc :result conj char))
                       :whitespace (case char
                                     (\] \} \)) (-> acc
                                                    (update :result conj char)
                                                    (assoc :state :normal))
                                     (\space \tab \newline \return) acc
                                     \" (-> acc
                                            (update :result conj \space)
                                            (update :result conj char)
                                            (assoc :state :string))
                                     \; (-> acc
                                            (update :result conj \space)
                                            (update :result conj char)
                                            (assoc :state :comment))
                                     (-> acc
                                         (update :result conj \space)
                                         (update :result conj char)
                                         (assoc :state :normal)))))
                   {:state :normal, :result []}
                   s)]
         (apply str (:result res))))
     (defn- balanced?
       [s]
       (let [counts (reduce (fn [counts char]
                              (case char
                                \( (update counts :paren inc)
                                \) (update counts :paren dec)
                                \[ (update counts :bracket inc)
                                \] (update counts :bracket dec)
                                \{ (update counts :brace inc)
                                \} (update counts :brace dec)
                                counts))
                            {:paren 0, :bracket 0, :brace 0}
                            s)]
         (every? (fn [v] (<= v 0)) (vals counts))))
     (defn- complete-form?
       [input-str]
       (let [trimmed (str/trim input-str)]
         (if (or (str/blank? trimmed) (str/starts-with? trimmed ";"))
           true
           (balanced? input-str))))
     (defn- configure-state-cljd
       [state opts]
       (let [p (cond (:telemetry-stream opts)
                     (eval-input state
                                 (str "(telemetry "
                                      (pr-str (:telemetry-stream opts))
                                      ")"))
                     (:telemetry? opts) (eval-input state "(telemetry)")
                     :else (async/Future.value [state nil]))]
         (.then ^async/Future p
                (fn [[state telemetry-msg]]
                  (when telemetry-msg (write-out! telemetry-msg))
                  state))))
     (defn- run-cli-cljd!
       [state-atom]
       (let [acc (atom "")]
         (letfn
           [(show-prompt!
              []
              (if (empty? @acc) (print-prompt!) (print-continuation-prompt!)))
            (handle-line!
              [line]
              (let [new-acc (str @acc "\n" line)]
                (if (complete-form? new-acc)
                  (if (str/blank? (str/trim new-acc))
                    (do (reset! acc "") (show-prompt!))
                    (let [cleaned (clean-cljd-input new-acc)]
                      (reset! acc "")
                      (-> ^async/Future (eval-input @state-atom cleaned)
                          (.then (fn [[state result]]
                                   (reset! state-atom state)
                                   (when (some? result)
                                     (write-out! result)
                                     (println))
                                   (when (:running? @state-atom)
                                     (show-prompt!))))
                          (.catchError (fn [e]
                                         (write-err! (str "Error: " e))
                                         (when (:running? @state-atom)
                                           (show-prompt!)))))))
                  (do (reset! acc new-acc) (show-prompt!)))))]
           (show-prompt!)
           (-> io/stdin
               (.transform (.-decoder convert/utf8))
               (.transform (convert/LineSplitter.))
               (.listen handle-line!
                        .onDone
                        (fn []
                          (when (:running? @state-atom)
                            (println)
                            (swap! state-atom assoc :running? false))))))))
     (defn -main
       [& args]
       (let [opts (parse-args args)]
         (-> ^async/Future (configure-state-cljd (create-state) opts)
             (.then
               (fn [state]
                 (let [state-atom (atom state)
                       server
                       (when-let [port (:port opts)]
                         (serve! state-atom port {:host (:host opts)}))]
                   (if (and (:headless? opts) (not server))
                     (do (write-err! "Error: --headless requires --port")
                         (throw (ex-info "Error: --headless requires --port"
                                         {})))
                     (when-not (:headless? opts)
                       (run-cli-cljd! state-atom)))))))))
     (defn run-main
       [args]
       (apply -main args))))
