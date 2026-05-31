(ns dao.await
  "Async-style syntax for sequential stream programs that run on the existing
   Yin VM and Dao runtime stack.

   See docs/design/dao.await.md for the full design.

   V1 surface:
     (await/go body...)
     (await/go {:env {'sym val ...}} body...)
     (await/cursor stream)
     (await/<! cursor)
     (await/>! stream value)

   await/cursor / <! / >! are registered as Yin module functions that return
   stream effect descriptors. Calls inside a go body are compiled by
   yang.clojure as ordinary applications; the Yin VM resolves them via the
   module registry at runtime and the engine dispatches the resulting
   :stream/cursor, :stream/next, :stream/put effects.

   await/go is a thin syntax+macro layer. It does not introduce a new
   interpreter, scheduler, or continuation model."
  (:require
    [dao.stream :as ds]
    [yang.clojure :as yang]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.engine :as engine]))


;; =============================================================================
;; Stream effect descriptors: cursor, <!, >!
;; =============================================================================
;;
;; Each function returns an effect descriptor consumed by the Yin engine.
;; They are also registered as Yin module functions so calls inside a go
;; body resolve through the module system at runtime.
;;
;; Registered under 'await (matches the doc's recommended :as await alias) and
;; 'dao.await for fully-qualified callers.

(defn cursor
  "Stream cursor effect descriptor. Inside (await/go ...) this produces a
   :stream/cursor effect handled by the Yin engine."
  [stream-ref]
  {:effect :stream/cursor, :stream stream-ref})


(defn <!
  "Stream-read effect descriptor. Inside (await/go ...) this produces a
   :stream/next effect; the engine returns the next value at the cursor or
   parks the continuation if the stream is empty and open."
  [cursor-ref]
  {:effect :stream/next, :cursor cursor-ref})


(defn >!
  "Stream-write effect descriptor. Inside (await/go ...) this produces a
   :stream/put effect; the engine appends val to the stream, parking the
   continuation if the stream is bounded and full."
  [stream-ref val]
  {:effect :stream/put, :stream stream-ref, :val val})


(def ^:private await-bindings {'cursor cursor, '<! <!, '>! >!})


(module/register-module! 'await await-bindings)
(module/register-module! 'dao.await await-bindings)


;; =============================================================================
;; Process construction
;; =============================================================================

(defonce ^:private compile-cache (atom {}))


(defn- get-compiled
  [forms]
  (if-let [cached (get @compile-cache forms)]
    cached
    (let [body-form (cond (empty? forms) nil
                          (= 1 (count forms)) (first forms)
                          :else (cons 'do forms))
          ast (yang/compile body-form)
          datoms (vec (vm/ast->datoms ast))
          compiled {:ast ast, :datoms datoms}]
      (swap! compile-cache assoc forms compiled)
      compiled)))


(defn go*
  "Build a process descriptor from quoted body forms and an optional env map.

   forms : seq of unevaluated Clojure forms (as data).
   env   : map of symbol → runtime value. Values that are dao.stream instances
           are materialized into the VM store at run time; other values are
           passed through to the VM environment unchanged.

   Returns:
     {:type   :dao.await/process
      :ast    <Universal AST>
      :datoms <semantic datoms>
      :env    <user env, untouched>
      :forms  <original forms>}"
  ([forms] (go* forms {}))
  ([forms env]
   (let [{:keys [ast datoms]} (get-compiled forms)]
     {:type :dao.await/process,
      :ast ast,
      :datoms datoms,
      :env (or env {}),
      :forms (vec forms)})))


#?(:clj
   (defmacro go
     "Compile body forms into a Yin-backed process descriptor.

      An optional first form {:env {'sym val ...}} captures host lexical values.
      The macro itself does not analyze free variables — V1 requires the
      explicit env path per the design doc to avoid silently compiling wrong
      programs. The :env values are evaluated at the host call site; the body
      forms are passed unevaluated to yang.clojure.

      Example:
        (let [in some-stream out other-stream]
          (await/go {:env {'in in 'out out}}
            (let [c (await/cursor in) x (await/<! c)]
              (await/>! out x))))"
     [& body]
     (let [[opts forms] (if (and (map? (first body))
                                 (contains? (first body) :env))
                          [(first body) (rest body)]
                          [{} body])]
       `(go* '~(vec forms) ~(:env opts)))))


;; =============================================================================
;; Runtime: prepare env, drive the VM
;; =============================================================================

(defn- host-stream?
  "True when v is a dao.stream transport instance (reader or writer)."
  [v]
  (or (satisfies? ds/IDaoStreamReader v) (satisfies? ds/IDaoStreamWriter v)))


(defn- prepare-env
  "Walk the user env. For each value that is a host stream instance, allocate
   a fresh store key, replace the env value with a :stream-ref pointing at
   that key, and record the stream in :store-updates so the caller can splice
   it into the VM store. Non-stream values pass through unchanged."
  [env]
  (reduce-kv (fn [acc sym v]
               (if (host-stream? v)
                 (let [id (keyword (str "await-stream-" (name (gensym ""))))]
                   (-> acc
                       (assoc-in [:env sym] {:type :stream-ref, :id id})
                       (assoc-in [:store-updates id] v)))
                 (assoc-in acc [:env sym] v)))
             {:env {}, :store-updates {}}
             env))


(defn- result-map
  [proc vm-state]
  {:type :dao.await/result,
   :vm vm-state,
   :value (vm/value vm-state),
   :blocked? (vm/blocked? vm-state),
   :proc proc})


(defn run
  "Drive a process on a fresh Yin VM (using ast-walker) until it returns a
   value or blocks.

   Returns a result map:
     {:type :dao.await/result
      :vm   <vm state>
      :value <result-value | :yin/blocked>
      :blocked? <bool>
      :proc <original process descriptor>}

   To resume a blocked result after the host has put data on a waitable
   stream, call (await/resume result {:woke woke}) with the :woke vector
   returned by ds/put!."
  ([proc] (run proc {}))
  ([proc _opts]
   (let [{:keys [env store-updates]} (prepare-env (:env proc))
         vm (-> (ast-walker/create-vm {:env env})
                (update :store merge store-updates))
         after (vm/eval vm (:ast proc))]
     (result-map proc after))))


(defn resume
  "Resume a blocked process. The optional :woke argument is the vector of
   woken entries returned by ds/put! on a waitable stream that the program
   was parked on. Those entries are spliced into the VM's ready-queue so the
   engine can wake the parked task on the next run pass."
  ([blocked] (resume blocked nil))
  ([blocked {:keys [woke]}]
   (let [vm (:vm blocked)
         vm' (if (seq woke)
               (update vm
                       :ready-queue
                       (fnil into [])
                       (engine/make-woken-run-queue-entries vm woke))
               vm)
         after (vm/eval vm' nil)]
     (result-map (:proc blocked) after))))
