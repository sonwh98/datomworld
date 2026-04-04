(ns dao.repl
  (:require
    #?(:cljd ["dart:async" :as async])
    #?(:cljd ["dart:core" :as core])
    #?(:clj [clojure.edn :as edn]
       :cljs [cljs.reader :as edn]
       :cljd [clojure.edn :as edn])
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    ;; The following transport namespaces are required for their side-effects
    ;; (registering transport types) during REPL initialization.
    #?(:cljd [dao.stream.transport.ringbuffer :as ringbuffer])
    #?(:clj [dao.stream.transport.ws])
    #?(:cljs [dao.stream.transport.ws :as ws])
    [yang.clojure :as yang.clojure]
    [yang.php :as yang.php]
    [yang.python :as yang.python]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(def ^:private command-heads
  #{'vm 'lang 'compile 'reset 'connect 'disconnect 'telemetry 'help 'quit})


(def ^:private local-only-command-heads
  #{'connect 'disconnect 'telemetry 'help 'quit})


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
       "  (help)\n"
       "  (quit)")
     :default
     (str
       "Commands:\n"
       "  (vm :semantic | :register | :stack | :ast-walker)\n"
       "  (lang :clojure | :python | :php)\n"
       "  (compile expr)\n"
       "  (reset)\n"
       "  (telemetry)\n"
       "  (help)\n"
       "  (quit)")))


(defn- telemetry-config
  [vm-type stream]
  (when stream
    {:stream stream
     :vm-id (keyword (str "dao.repl/" (name vm-type)))}))


(defn- install-telemetry
  [vm-state vm-type stream]
  (-> vm-state
      (assoc :telemetry (telemetry-config vm-type stream))
      (assoc :vm-model vm-type)
      (update :telemetry-step #(or % 0))
      (update :telemetry-t #(or % 0))))


(defn- make-vm
  ([vm-type] (make-vm vm-type nil))
  ([vm-type telemetry-stream]
   (when-not (contains? vm-constructors vm-type)
     (throw (ex-info "Unknown Dao REPL VM type"
                     {:vm-type vm-type
                      :supported (vec (keys vm-constructors))})))
   (let [constructor (get vm-constructors vm-type)]
     (cond-> (constructor)
       telemetry-stream (install-telemetry vm-type telemetry-stream)))))


(defn create-state
  ([] (create-state {}))
  ([{:keys [lang telemetry-stream vm-type]
     :or {lang :clojure
          vm-type :semantic}}]
   {:lang lang
    :remote-endpoint nil
    :remote-response-cursor {:position 0}
    :request-id 0
    :running? true
    :telemetry-cursor {:position 0}
    :telemetry-mode (when telemetry-stream :stderr)
    :telemetry-stream telemetry-stream
    :vm (make-vm vm-type telemetry-stream)
    :vm-type vm-type}))


(defn- format-value
  [value]
  (pr-str value))


(defn- format-error
  [error]
  (str "Error: " #?(:clj (ex-message error)
                    :cljs (or (ex-message error) (.-message error))
                    :cljd (ex-message error))))


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
  #?(:cljd (ringbuffer/make-ring-buffer-stream nil)
     :default (ds/open! {:transport {:type :ringbuffer
                                     :capacity nil}})))


(defn- open-telemetry-sink
  [url]
  #?(:clj ((requiring-resolve 'dao.stream.transport.ws/connect!)
           (normalize-daostream-url url))
     :cljs (ws/connect! (normalize-daostream-url url))
     :cljd (throw (ex-info "Remote telemetry is not supported on this platform"
                           {:url url}))))


#?(:clj
   (defn- open-remote-endpoint
     [url]
     (let [connect! (requiring-resolve 'dao.stream.transport.ws/connect!)
           stream (connect! (normalize-daostream-url url))]
       {:request-stream stream
        :response-stream stream})))


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
        :stderr #?(:clj (binding [*out* *err*] (println (pr-str datom)))
                   :cljs (*print-err-fn* (pr-str datom))
                   :cljd (binding [*out* *err*] (println (pr-str datom))))
        :stream (ds/put! (:stream telemetry-mode) datom)
        nil))
    state'))


(defn- eval-datoms
  [state datoms]
  (when (= :ast-walker (:vm-type state))
    (throw (ex-info "ASTWalkerVM cannot load raw datom streams directly"
                    {:vm-type (:vm-type state)})))
  (let [root-id (apply max (map first datoms))
        vm' (-> (:vm state)
                (vm/load-program {:node root-id
                                  :datoms datoms})
                (vm/run))]
    [(-> state
         (assoc :vm vm')
         flush-telemetry)
     (format-value (vm/value vm'))]))


(defn- eval-ast
  [state ast]
  (let [vm' (vm/eval (:vm state) ast)]
    [(-> state
         (assoc :vm vm')
         flush-telemetry)
     (format-value (vm/value vm'))]))


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
       (pr-str ast)
       "\n\nDatoms:\n"
       (pr-str (vec (vm/ast->datoms ast)))))


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
                     :cljd (.complete completer [state (format-error err)])))
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
                             :cljd (.complete completer res)))
                        (poll next-cursor (inc attempts))))

                    (= result :blocked)
                    #?(:clj (do (Thread/sleep 10)
                                (poll cursor (inc attempts)))
                       :cljs (js/setTimeout #(poll cursor (inc attempts)) 10)
                       :cljd (-> (async/Future.delayed (core/Duration .milliseconds 10))
                                 (.then (fn [_] (poll cursor (inc attempts))))))

                    :else
                    (let [err (ex-info "Remote Dao REPL stream closed before a response arrived"
                                       {:request-id request-id
                                        :result result})]
                      #?(:clj (throw err)
                         :cljs (@p-resolve [state (format-error err)])
                         :cljd (.complete completer [state (format-error err)])))))))]
      #?(:clj (poll (:remote-response-cursor state) 0)
         :cljs (do (poll (:remote-response-cursor state) 0) p)
         :cljd (do (poll (:remote-response-cursor state) 0) (.future completer))))))


(defn- eval-remote-input
  [state input-str]
  (let [request-id (keyword (str "dao.repl/request-" (:request-id state)))
        endpoint (:remote-endpoint state)
        _ (dao-apply/put-request! (:request-stream endpoint)
                                  request-id
                                  :op/eval
                                  [input-str])
        p-or-res (wait-for-response (update state :request-id inc)
                                    request-id)]
    #?(:clj (let [[state' response] p-or-res]
              [state' (str (dao-apply/response-value response))])
       :cljs (.then p-or-res
                    (fn [[state' response]]
                      [state' (str (dao-apply/response-value response))]))
       :cljd (.then p-or-res
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
                :vm (make-vm vm-type (:telemetry-stream state)))
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
      [(assoc state :vm (make-vm (:vm-type state) (:telemetry-stream state)))
       (str (get vm-labels (:vm-type state)) " reset")]

      connect
      #?(:clj
         (let [endpoint (open-remote-endpoint (first args))]
           [(attach-remote-endpoint state endpoint)
            (str "Connected to " (normalize-daostream-url (first args)))])
         :cljs
         (throw (ex-info "Remote Dao REPL connections are not yet supported on Node.js"
                         {:url (first args)}))
         :cljd
         (throw (ex-info "Remote Dao REPL connections are not supported on this platform"
                         {:url (first args)})))

      disconnect
      [(detach-remote-endpoint state)
       "Disconnected from remote shell"]

      telemetry
      (let [arg (first args)]
        (cond
          arg
          (let [telemetry-stream (or (:telemetry-stream state)
                                     (make-local-stream))
                sink-stream (open-telemetry-sink arg)]
            [(set-telemetry-stream state
                                   telemetry-stream
                                   {:type :stream
                                    :stream sink-stream})
             (str "Telemetry routed to " (normalize-daostream-url arg))])

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
        (deliver-result [state (format-error error)])))))


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
       :cljd (.then res-p (fn [[state' result]]
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
            :cljd (.then res-p (fn [[state' response]]
                                 (let [put-result (dao-apply/put-response! response-stream response)]
                                   (reset! state-atom state')
                                   {:request request
                                    :response response
                                    :cursor (:cursor read-result)
                                    :put-result put-result})))))
       (deliver-result read-result)))))


#?(:clj
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [stream (ds/open! {:transport {:type :websocket
                                          :mode :listen
                                          :port port}})
            running? (atom true)
            worker (future
                     (loop [cursor {:position 0}]
                       (if @running?
                         (let [result @(serve-once! state-atom
                                                    stream
                                                    stream
                                                    cursor)]
                           (case result
                             :blocked (do (Thread/sleep sleep-ms)
                                          (recur cursor))
                             :end nil
                             :daostream/gap (recur {:position 0})
                             (recur (:cursor result))))
                         nil)))]
        {:port port
         :stream stream
         :worker worker
         :stop! (fn []
                  (ds/close! stream)
                  (reset! running? false)
                  (try
                    (deref worker 100 nil)
                    (catch Exception _)))})))

   :cljs
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [stream (ds/open! {:transport {:type :websocket
                                          :mode :listen
                                          :port port}})
            running? (atom true)
            worker (fn loop-fn
                     [cursor]
                     (when @running?
                       (.then (serve-once! state-atom stream stream cursor)
                              (fn [result]
                                (case result
                                  :blocked (js/setTimeout #(loop-fn cursor) sleep-ms)
                                  :end nil
                                  :daostream/gap (loop-fn {:position 0})
                                  (loop-fn (:cursor result)))))))]
        (worker {:position 0})
        {:port port
         :stream stream
         :stop! (fn []
                  (ds/close! stream)
                  (reset! running? false))})))

   :cljd
   (defn serve!
     ([state-atom port]
      (serve! state-atom port {}))
     ([state-atom port {:keys [sleep-ms]
                        :or {sleep-ms 10}}]
      (let [stream (ds/open! {:transport {:type :websocket
                                          :mode :listen
                                          :port port}})
            running? (atom true)
            worker (fn loop-fn
                     [cursor]
                     (when @running?
                       (.then (serve-once! state-atom stream stream cursor)
                              (fn [result]
                                (case result
                                  :blocked (-> (async/Future.delayed (core/Duration .milliseconds sleep-ms))
                                               (.then (fn [_] (loop-fn cursor))))
                                  :end nil
                                  :daostream/gap (loop-fn {:position 0})
                                  (loop-fn (:cursor result)))))))]
        (worker {:position 0})
        {:port port
         :stream stream
         :stop! (fn []
                  (ds/close! stream)
                  (reset! running? false))}))))
