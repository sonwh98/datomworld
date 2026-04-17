(ns dao.repl
  (:require
    #?(:cljd ["dart:async" :as async])
    #?(:cljd ["dart:core" :as core])
    #?(:cljs [cljs.reader :as edn]
       :default [clojure.edn :as edn])
    [clojure.string :as str]
    [dao.pretty :as pretty]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.ws :as ws]
    [yang.clojure :as yang.clojure]
    [yang.php :as yang.php]
    [yang.python :as yang.python]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(def ^:private command-heads
  #{'vm 'lang 'compile 'reset 'connect 'disconnect 'telemetry 'repl-state 'help 'quit})


(def ^:private local-only-command-heads
  #{'connect 'disconnect 'telemetry 'help 'quit 'repl-state})


(def ^:private vm-constructors
  {:ast-walker ast-walker/create-vm
   :semantic semantic/create-vm
   :register register/create-vm
   :stack stack/create-vm})


(def ^:private vm-labels
  {:ast-walker "ASTWalkerVM"
   :semantic "SemanticVM"
   :register "RegisterVM"
   :stack "StackVM"})


(def ^:private lang-labels
  {:clojure "Clojure"
   :python "Python"
   :php "PHP"})


(def ^:private help-text
  #?(:clj
     (str
       "Commands:\n"
       "  (vm :semantic | :register | :stack | :ast-walker)\n"
       "  (lang :clojure | :python | :php)\n"
       "  (compile expr)\n"
       "  (reset)\n"
       "  (connect \"daostream:ws://host:port\")\n"
       "  (disconnect)\n"
       "  (telemetry)\n"
       "  (repl-state)\n"
       "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated values")
     :default
     (str
       "Commands:\n"
       "  (vm :semantic | :register | :stack | :ast-walker)\n"
       "  (lang :clojure | :python | :php)\n"
       "  (compile expr)\n"
       "  (reset)\n"
       "  (telemetry)\n"
       "  (repl-state)\n"
       "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated values")))


(defn- quote-symbols
  [x]
  (cond
    (symbol? x) (list 'quote x)
    (vector? x) (mapv quote-symbols x)
    (map? x) (into {} (map (fn [[k v]] [(quote-symbols k) (quote-symbols v)]) x))
    (or (list? x) (seq? x))
    (if (= 'quote (first x))
      x
      (map quote-symbols x))
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
    (ds/put! output-stream {:type :repl/output
                            :op op
                            :text text}))
  nil)


(defn- make-repl-primitives
  [output-stream]
  (merge vm/primitives
         {'print (fn [& args]
                   (emit-output! output-stream :print (print-text args)))
          'println (fn [& args]
                     (emit-output! output-stream :println (str (print-text args) "\n")))
          'prn (fn [& args]
                 (emit-output! output-stream :prn (prn-text args)))
          'ast->datoms vm/ast->datoms
          'datoms->ast vm/datoms->ast}))


(defn- telemetry-config
  [vm-type stream]
  (when stream
    {:stream stream
     :vm-id (keyword (str "dao.repl/" (name vm-type)))}))


(defn- make-request-scope
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))
     :cljd (str (rand-int 2147483647)
                "-"
                (rand-int 2147483647)
                "-"
                (rand-int 2147483647))))


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
     (throw (ex-info "Unknown Dao REPL VM type"
                     {:vm-type vm-type
                      :supported (vec (keys vm-constructors))})))
   (let [constructor (get vm-constructors vm-type)
         in-stream (make-local-stream)]
     (cond-> (constructor {:primitives (make-repl-primitives output-stream)
                           :in-stream in-stream})
       telemetry-stream (install-telemetry vm-type telemetry-stream)))))


(defn create-state
  ([] (create-state {}))
  ([{:keys [exposed-ports exposed-streams lang output-cursor output-stream request-scope telemetry-stream vm-type]
     :or {exposed-ports {}
          exposed-streams {}
          lang :clojure
          output-cursor {:position 0}
          vm-type :semantic}}]
   (let [output-stream (or output-stream (make-local-stream))]
     {:lang lang
      :exposed-ports exposed-ports
      :exposed-streams exposed-streams
      :last-value nil
      :last-value-2 nil
      :last-value-3 nil
      :output-cursor output-cursor
      :output-stream output-stream
      :remote-endpoint nil
      :remote-response-cursor {:position 0}
      :request-id 0
      :request-scope (or request-scope (make-request-scope))
      :running? true
      :telemetry-cursor {:position 0}
      :telemetry-mode (when telemetry-stream :stderr)
      :telemetry-stream telemetry-stream
      :vm (make-vm vm-type telemetry-stream output-stream)
      :vm-type vm-type})))


(defn- format-error
  [error]
  (str "Error: " #?(:clj (or (ex-message error) (str error))
                    :cljs (or (ex-message error) (.-message error) (str error))
                    :cljd (or (ex-message error) (str error)))))


(defn- stream-status
  [stream]
  (try
    (if (ds/closed? stream) :closed :open)
    (catch #?(:clj Exception :cljs js/Error :cljd Object) _
      :unknown)))


(defn- stream-summary
  [stream]
  {:present? (some? stream)
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
       (map (fn [[stream-name stream]]
              [stream-name (stream-summary stream)]))
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
    {:lang (:lang state)
     :vm {:type (:vm-type state)
          :halted? (try
                     (vm/halted? (:vm state))
                     (catch #?(:clj Exception :cljs js/Error :cljd Object) _
                       :unknown))
          :blocked? (try
                      (vm/blocked? (:vm state))
                      (catch #?(:clj Exception :cljs js/Error :cljd Object) _
                        :unknown))}
     :running? (:running? state)
     :output {:cursor (:output-cursor state)
              :stream (stream-summary (:output-stream state))}
     :telemetry {:enabled? (some? (:telemetry-stream state))
                 :mode (telemetry-mode-summary (:telemetry-mode state))
                 :cursor (:telemetry-cursor state)
                 :stream (stream-summary (:telemetry-stream state))}
     :remote {:connected? (some? remote-endpoint)
              :request-id (:request-id state)
              :response-cursor (:remote-response-cursor state)
              :streams {:request (stream-summary (:request-stream remote-endpoint))
                        :response (stream-summary (:response-stream remote-endpoint))}}
     :streams {:output (stream-summary (:output-stream state))
               :telemetry (stream-summary (:telemetry-stream state))
               :remote/request (stream-summary (:request-stream remote-endpoint))
               :remote/response (stream-summary (:response-stream remote-endpoint))}
     :exposed-streams (exposed-streams-summary (:exposed-streams state))
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
  (and (vector? value)
       (= 5 (count value))))


(defn- datom-stream?
  [value]
  (and (vector? value)
       (seq value)
       (every? datom? value)))


(defn- ast-map?
  [value]
  (and (map? value)
       (keyword? (:type value))))


(defn- read-clojure-forms
  [input-str]
  (edn/read-string (str "[" input-str "]")))


(defn- normalize-daostream-url
  [url]
  (if (str/starts-with? url "daostream:")
    (subs url (count "daostream:"))
    url))


(defn- make-local-stream
  []
  (ds/open! {:type :ringbuffer :capacity nil}))


(defn- open-telemetry-sink
  [url]
  (ws/connect! (normalize-daostream-url url)))


(defn- open-remote-endpoint
  [url]
  (let [stream (ws/connect! (normalize-daostream-url url))]
    {:request-stream stream
     :response-stream stream}))


(defn- attach-remote-endpoint
  [state endpoint]
  (assoc state
         :remote-endpoint endpoint
         :remote-response-cursor {:position 0}))


(defn- detach-remote-endpoint
  [state]
  (when-let [{:keys [request-stream response-stream]} (:remote-endpoint state)]
    (when request-stream
      (ds/close! request-stream))
    (when (and response-stream (not= request-stream response-stream))
      (ds/close! response-stream)))
  (assoc state
         :remote-endpoint nil
         :remote-response-cursor {:position 0}))


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
    (assoc state :vm (update vm :store assoc
                             '*1 v1
                             '*2 v2
                             '*3 v3))))


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
      (throw (ex-info "Datom stream did not form a complete, runnable Yin VM program.
Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
                      {:root-id (:root-id (vm/index-datoms datoms))
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
    (throw (ex-info "Unsupported Dao REPL language"
                    {:lang lang}))))


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
    (throw (ex-info "Unsupported Dao REPL language"
                    {:lang (:lang state)}))))


(defn- render-compile-output
  [ast]
  (str "AST:\n"
       (format-value ast)
       "\n\nDatoms:\n"
       (format-value (vec (vm/ast->datoms ast)))))


(defn- deliver-result
  [val]
  #?(:clj (let [p (promise)] (deliver p val) p)
     :cljs (js/Promise.resolve val)
     :cljd (async/Future.value val)))


(defn- wait-for-response
  [state request-id]
  (let [{:keys [response-stream]} (:remote-endpoint state)
        #?@(:cljs [p-resolve (atom nil)
                   p (js/Promise. (fn [resolve _] (reset! p-resolve resolve)))]
            :cljd [completer (async/Completer.)])]
    (letfn [(poll
              [cursor attempts]
              (if (> attempts 500)
                (let [err (ex-info "Remote Dao REPL request timed out"
                                   {:request-id request-id})]
                  #?(:clj (throw err)
                     :cljs (@p-resolve [state (format-error err)])
                     :cljd (.complete ^async/Completer completer [state (format-error err)])))
                (let [result (dao-apply/next-response response-stream cursor)]
                  (cond
                    (map? result)
                    (let [response (:ok result)
                          next-cursor (:cursor result)]
                      (if (= request-id (dao-apply/response-id response))
                        (let [res [(assoc state :remote-response-cursor next-cursor)
                                   response]]
                          #?(:clj res
                             :cljs (@p-resolve res)
                             :cljd (.complete ^async/Completer completer res)))
                        (poll next-cursor (inc attempts))))
                    (= result :blocked)
                    #?(:clj (do (Thread/sleep 10)
                                (poll cursor (inc attempts)))
                       :cljs (js/setTimeout #(poll cursor (inc attempts)) 10)
                       :cljd (do
                               (.then (async/Future.delayed (core/Duration .milliseconds 10))
                                      (fn [_] (poll cursor (inc attempts))))
                               nil))

                    :else
                    (let [err (ex-info "Remote Dao REPL stream closed before a response arrived"
                                       {:request-id request-id
                                        :result result})]
                      #?(:clj (throw err)
                         :cljs (@p-resolve [state (format-error err)])
                         :cljd (.complete ^async/Completer completer [state (format-error err)])))))))]
      #?(:clj (poll (:remote-response-cursor state) 0)
         :cljs (do (poll (:remote-response-cursor state) 0) p)
         :cljd (do (poll (:remote-response-cursor state) 0) (.-future ^async/Completer completer))))))


(defn- eval-remote-input
  [state input-str]
  (let [request-id (str "dao.repl/request/"
                        (:request-scope state)
                        "/"
                        (:request-id state))
        endpoint (:remote-endpoint state)
        _ (dao-apply/put-request! (:request-stream endpoint)
                                  request-id
                                  :op/eval
                                  [input-str])
        p-or-res (wait-for-response (update state :request-id inc)
                                    request-id)]
    #?(:clj (deliver-result
              (let [[state' response] p-or-res]
                [state' (str (dao-apply/response-value response))]))
       :cljs (.then p-or-res
                    (fn [[state' response]]
                      [state' (str (dao-apply/response-value response))]))
       :cljd (.then ^async/Future p-or-res
                    (fn [[state' response]]
                      [state' (str (dao-apply/response-value response))])))))


(declare eval-input)


(defn- handle-command
  [state form]
  (let [[command & args] form]
    (case command
      vm
      (let [vm-type (first args)]
        (when-not (contains? vm-constructors vm-type)
          (throw (ex-info "Unknown Dao REPL VM type"
                          {:vm-type vm-type
                           :supported (vec (keys vm-constructors))})))
        [(assoc state
                :vm-type vm-type
                :vm (make-vm vm-type
                             (:telemetry-stream state)
                             (:output-stream state)))
         (str "Switched to " (get vm-labels vm-type) " (store cleared)")])

      lang
      (let [lang (first args)]
        (when-not (contains? lang-labels lang)
          (throw (ex-info "Unknown Dao REPL language"
                          {:lang lang})))
        [(assoc state :lang lang)
         (str "Switched to " (get lang-labels lang))])

      compile
      (let [ast (compile-command-ast state (first args))]
        [state (render-compile-output ast)])

      reset
      [(assoc state :vm (make-vm (:vm-type state)
                                 (:telemetry-stream state)
                                 (:output-stream state)))
       (str (get vm-labels (:vm-type state)) " reset")]

      connect
      #?(:clj
         (let [endpoint (open-remote-endpoint (first args))
               state (detach-remote-endpoint state)]
           [(attach-remote-endpoint state endpoint)
            (str "Connected to " (normalize-daostream-url (first args)))])
         :cljs
         (throw (ex-info "Remote Dao REPL connections are not yet supported on Node.js"
                         {:url (first args)}))
         :cljd
         (let [endpoint (open-remote-endpoint (first args))
               state (detach-remote-endpoint state)]
           [(attach-remote-endpoint state endpoint)
            (str "Connected to " (normalize-daostream-url (first args)))]))

      disconnect
      [(detach-remote-endpoint state)
       "Disconnected from remote shell"]

      telemetry
      (let [arg (first args)]
        (cond
          arg
          (let [telemetry-stream (or (:telemetry-stream state)
                                     (make-local-stream))
                telemetry-url (normalize-daostream-url arg)
                _ (close-telemetry-sink! state)
                sink-stream (open-telemetry-sink arg)]
            [(set-telemetry-stream state
                                   telemetry-stream
                                   {:type :stream
                                    :stream sink-stream
                                    :url telemetry-url})
             (str "Telemetry routed to " telemetry-url)])

          (:telemetry-stream state)
          (do
            (close-telemetry-sink! state)
            [(set-telemetry-stream state nil nil)
             "Telemetry disabled"])

          :else
          (let [telemetry-stream (make-local-stream)]
            [(set-telemetry-stream state telemetry-stream :stderr)
             "Telemetry enabled (stderr)"])))

      help
      [state help-text]

      repl-state
      [state (format-value (repl-state state))]

      quit
      [(assoc state :running? false) "Bye"]

      (throw (ex-info "Unknown Dao REPL command"
                      {:command command})))))


(defn eval-input
  [state input-str]
  (let [trimmed (str/trim input-str)
        parsed (try
                 {:forms (read-clojure-forms trimmed)}
                 (catch #?(:clj Exception :cljs js/Error :cljd Object) error
                   {:error error}))]
    (try
      (cond
        (and (:forms parsed)
             (= 1 (count (:forms parsed)))
             (local-only-command-form? (first (:forms parsed))))
        (deliver-result (handle-command state (first (:forms parsed))))

        (:remote-endpoint state)
        (eval-remote-input state input-str)

        (and (:forms parsed)
             (= 1 (count (:forms parsed)))
             (shell-command-form? (first (:forms parsed))))
        (deliver-result (handle-command state (first (:forms parsed))))

        (and (:forms parsed)
             (= 1 (count (:forms parsed)))
             (datom-stream? (first (:forms parsed))))
        (deliver-result (eval-datoms state (first (:forms parsed))))

        (and (:forms parsed)
             (= 1 (count (:forms parsed)))
             (ast-map? (first (:forms parsed))))
        (deliver-result (eval-ast state (first (:forms parsed))))

        (:forms parsed)
        (deliver-result (if (= :clojure (:lang state))
                          (eval-clojure-forms state (:forms parsed))
                          (eval-source state input-str)))

        (= :clojure (:lang state))
        (deliver-result [state (format-error (:error parsed))])

        :else
        (deliver-result (eval-source state input-str)))
      (catch #?(:clj Exception :cljs js/Error :cljd Object) error
        (let [[state' output-text] (collect-output state)]
          (deliver-result [state' (str output-text (format-error error))]))))))


(defn handle-request
  [state request]
  (let [request-id (dao-apply/request-id request)
        op (dao-apply/request-op request)
        args (dao-apply/request-args request)
        res-p (case op
                :op/eval
                (eval-input state (first args))

                :op/vm
                (deliver-result (handle-command state (list* 'vm args)))

                :op/lang
                (deliver-result (handle-command state (list* 'lang args)))

                :op/reset
                (deliver-result (handle-command state '(reset)))

                :op/help
                (deliver-result [state help-text])

                (deliver-result
                  [state
                   (str "Error: Unsupported Dao REPL request op " op)]))]
    #?(:clj (deliver-result
              (let [[state' result] @res-p]
                [state' (dao-apply/response request-id result)]))
       :cljs (.then res-p (fn [[state' result]]
                            [state' (dao-apply/response request-id result)]))
       :cljd (.then ^async/Future res-p (fn [[state' result]]
                                          [state' (dao-apply/response request-id result)])))))


(defn serve-once!
  ([state-atom request-stream response-stream]
   (serve-once! state-atom request-stream response-stream {:position 0}))
  ([state-atom request-stream response-stream cursor]
   (let [read-result (dao-apply/next-request request-stream cursor)]
     (if (map? read-result)
       (let [request (:ok read-result)
             res-p (handle-request @state-atom request)]
         #?(:clj (deliver-result
                   (let [[state' response] @res-p
                         put-result (dao-apply/put-response! response-stream response)]
                     (reset! state-atom state')
                     {:request request
                      :response response
                      :cursor (:cursor read-result)
                      :put-result put-result}))
            :cljs (.then res-p (fn [[state' response]]
                                 (let [put-result (dao-apply/put-response! response-stream response)]
                                   (reset! state-atom state')
                                   {:request request
                                    :response response
                                    :cursor (:cursor read-result)
                                    :put-result put-result})))
            :cljd (.then ^async/Future res-p (fn [[state' response]]
                                               (let [put-result (dao-apply/put-response! response-stream response)]
                                                 (reset! state-atom state')
                                                 {:request request
                                                  :response response
                                                  :cursor (:cursor read-result)
                                                  :put-result put-result})))))
       (deliver-result read-result)))))


(defn- expose-repl-server
  [state port server-handle]
  (-> state
      (assoc-in [:exposed-ports :repl] {:transport :websocket
                                        :port port
                                        :server server-handle})))


(defn- unexpose-repl-server
  [state]
  (-> state
      (update :exposed-ports #(dissoc (or % {}) :repl))))


#?(:clj
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [running? (atom true)
            workers (atom #{})
            server-handle
            (ds/open! {:type :websocket
                       :mode :listen
                       :port port
                       :on-connect (fn [stream]
                                     (let [worker (future
                                                    (loop [cursor {:position 0}]
                                                      (when (and @running? (not (ds/closed? stream)))
                                                        (let [result @(serve-once! state-atom stream stream cursor)]
                                                          (case result
                                                            :blocked (do (Thread/sleep sleep-ms)
                                                                         (recur cursor))
                                                            :end (do (Thread/sleep sleep-ms)
                                                                     (recur cursor))
                                                            :daostream/gap (recur {:position 0})
                                                            (recur (:cursor result)))))))]
                                       (swap! workers conj worker)))})
            _ (swap! state-atom expose-repl-server port server-handle)]
        {:port port
         :server server-handle
         :workers workers
         :stop! (fn []
                  (reset! running? false)
                  (when-let [stop-fn (:stop-fn server-handle)]
                    (stop-fn))
                  (doseq [stream @(:conns server-handle)]
                    (ds/close! stream))
                  (swap! state-atom unexpose-repl-server)
                  (doseq [worker @workers]
                    (try
                      (deref worker 100 nil)
                      (catch Exception _))))})))

   :cljs
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [running? (atom true)
            server-handle
            (ds/open! {:type :websocket
                       :mode :listen
                       :port port
                       :on-connect (fn [stream]
                                     (let [worker (fn loop-fn
                                                    [cursor]
                                                    (when (and @running? (not (ds/closed? stream)))
                                                      (.then (serve-once! state-atom stream stream cursor)
                                                             (fn [result]
                                                               (case result
                                                                 :blocked (js/setTimeout #(loop-fn cursor) sleep-ms)
                                                                 :end (js/setTimeout #(loop-fn cursor) sleep-ms)
                                                                 :daostream/gap (loop-fn {:position 0})
                                                                 (loop-fn (:cursor result)))))))]
                                       (worker {:position 0})))})
            _ (swap! state-atom expose-repl-server port server-handle)]
        {:port port
         :server server-handle
         :stop! (fn []
                  (reset! running? false)
                  (when-let [stop-fn (:stop-fn server-handle)]
                    (stop-fn))
                  (doseq [stream @(:conns server-handle)]
                    (ds/close! stream))
                  (swap! state-atom unexpose-repl-server))})))

   :cljd
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [running? (atom true)
            server-handle
            (ds/open! {:type :websocket
                       :mode :listen
                       :port port
                       :on-connect (fn [stream]
                                     (let [worker (fn loop-fn
                                                    [cursor]
                                                    (when (and @running? (not (ds/closed? stream)))
                                                      (.then ^async/Future (serve-once! state-atom stream stream cursor)
                                                             (fn [result]
                                                               (case result
                                                                 :blocked (do
                                                                            (.then ^async/Future (async/Future.delayed (core/Duration .milliseconds sleep-ms))
                                                                                   (fn [_] (loop-fn cursor)))
                                                                            nil)
                                                                 :end (do
                                                                        (.then ^async/Future (async/Future.delayed (core/Duration .milliseconds sleep-ms))
                                                                               (fn [_] (loop-fn cursor)))
                                                                        nil)
                                                                 :daostream/gap (loop-fn {:position 0})
                                                                 (loop-fn (:cursor result)))))))]
                                       (worker {:position 0})))})
            _ (swap! state-atom expose-repl-server port server-handle)]
        {:port port
         :server server-handle
         :stop! (fn []
                  (reset! running? false)
                  (when-let [stop-fn (:stop-fn server-handle)]
                    (stop-fn))
                  (doseq [stream @(:conns server-handle)]
                    (ds/close! stream))
                  (swap! state-atom unexpose-repl-server))}))))
