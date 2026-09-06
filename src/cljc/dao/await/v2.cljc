(ns dao.await.v2
  "Async-style syntax for sequential stream programs that run entirely on
   yin.vm.v2 and dao.stream.v2.

   See docs/design/dao.await.md for the full design. This is v1's surface,
   ported; what changed is everything underneath it:

   - The module registry is a value. v1 registered 'await and 'dao.await
     into a global at go* time; here `registry` builds the same bindings as
     an ordinary yin.vm.v2.module registry value that `run` supplies to the
     VM. Nothing registers itself at load time.
   - Resumption is polling. v1's `resume` spliced the :woke vector a v1
     append returned into the ready queue. No v2 transport is waitable and
     v2 appends wake nothing, so `resume` simply re-runs the VM: the
     scheduler polls each parked entry against its transport and continues
     the continuations the poll resolves.
   - Cursors are opaque and outcomes are closed sets, so a blocked read
     answers `blocked`, a full write answers `full`, and a read parked on a
     stream that closes resumes to `end` (nil) from its own next poll.

   V2 surface (as v1):
     (await/go body...)
     (await/go {:env {'sym val ...}} body...)
     (await/cursor stream)
     (await/<! cursor)
     (await/>! stream value)

   await/cursor / <! / >! are module bindings that return stream effect
   descriptors. Calls inside a go body are compiled by yang.clojure as
   ordinary applications; the VM resolves them through the registry value
   at runtime and the engine dispatches the resulting :stream/cursor,
   :stream/next, :stream/put effects.

   await/go is a thin syntax+macro layer. It does not introduce a new
   interpreter, scheduler, or continuation model."
  (:require [dao.stream.v2 :as ds]
            [yang.clojure :as yang]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.module :as module]))


;; =============================================================================
;; Stream effect descriptors: cursor, <!, >!
;; =============================================================================
;;
;; Each function returns an effect descriptor consumed by the yin.vm.v2
;; engine. They are also registered as module bindings so calls inside a go
;; body resolve through the registry value at runtime.
;;
;; Registered under 'await (matches the doc's recommended :as await alias)
;; and 'dao.await.v2 for fully-qualified callers.

(defn cursor
  "Stream cursor effect descriptor. Inside (await/go ...) this produces a
   :stream/cursor effect handled by the yin.vm.v2 engine."
  [stream-ref]
  {:effect :stream/cursor, :stream stream-ref})


(defn <!
  "Stream-read effect descriptor. Inside (await/go ...) this produces a
   :stream/next effect; the engine returns the value at the cursor or parks
   the continuation when the transport answers blocked."
  [cursor-ref]
  {:effect :stream/next, :cursor cursor-ref})


(defn >!
  "Stream-write effect descriptor. Inside (await/go ...) this produces a
   :stream/put effect; the engine appends val to the stream, parking the
   continuation when the transport answers full."
  [stream-ref val]
  {:effect :stream/put, :stream stream-ref, :val val})


(def ^:private await-bindings {'cursor cursor, '<! <!, '>! >!})


(defn registry
  "A module registry value carrying the await bindings under 'await and
   'dao.await.v2, on top of base (default: yin.vm.v2.module's built-in
   effect handlers). Compositions that want await names resolvable inside a
   larger registry hand their registry here."
  ([] (registry nil))
  ([base]
   (-> (or base (module/default-registry))
       (module/register-module 'await await-bindings)
       (module/register-module 'dao.await.v2 await-bindings))))


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
   env   : map of symbol → runtime value. Values that are dao.stream.v2
           handles are materialized into the VM store at run time; other
           values are passed through to the VM environment unchanged.

   Returns:
     {:type   :dao.await.v2/process
      :ast    <Universal AST>
      :datoms <semantic datoms>
      :env    <user env, untouched>
      :forms  <original forms>}"
  ([forms] (go* forms {}))
  ([forms env]
   (let [{:keys [ast datoms]} (get-compiled forms)]
     {:type :dao.await.v2/process,
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
  "True when v is a dao.stream.v2 handle (declares a reader or writer
   surface)."
  [v]
  (or (ds/reader? v) (ds/writer? v)))


(defn- prepare-env
  "Walk the user env. For each value that is a v2 handle, allocate a fresh
   store key, replace the env value with a :stream-ref pointing at that key,
   and record the handle in :store-updates so the caller can splice it into
   the VM store. Non-handle values pass through unchanged."
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
  {:type :dao.await.v2/result,
   :vm vm-state,
   :value (vm/value vm-state),
   :blocked? (vm/blocked? vm-state),
   :proc proc})


(defn run
  "Drive a process on a fresh yin.vm.v2 ast-walker VM until it returns a
   value or parks on a stream effect.

   opts are host-composition options passed to yin.vm.v2.ast-walker/create-vm
   (:make-stream, :primitives, :call-in/:call-out, :bridge). :env always
   comes from the process, and :modules is the caller's registry with the
   await bindings layered on top, so await names always resolve.

   Returns a result map:
     {:type :dao.await.v2/result
      :vm   <vm state>
      :value <result-value | :yin/blocked>
      :blocked? <bool>
      :proc <original process descriptor>}

   A blocked process is resumed with (await/resume result) once the host has
   appended to (or closed) the stream a parked entry is waiting on."
  ([proc] (run proc {}))
  ([proc opts]
   (let [{:keys [env store-updates]} (prepare-env (:env proc))
         vm (-> (ast-walker/create-vm
                  (assoc opts
                         :modules (registry (:modules opts))
                         :env env))
                (update :store merge store-updates))
         after (vm/eval vm (:ast proc))]
     (result-map proc after))))


(defn resume
  "Resume a blocked process by polling the wait set.

   There is no wake list to hand over (v1's :woke came from dao.stream
   waiters, which no v2 transport has): resumption belongs to
   dao.runtime.v2's polling wait set, so resume just re-runs the VM. Its
   scheduler polls each parked entry against its transport — next for a
   parked read, append! for a parked writer — and continues the continuation
   of every entry the poll resolves. Entries the poll does not resolve stay
   parked, so the result may still be blocked."
  [blocked]
  (result-map (:proc blocked) (vm/eval (:vm blocked) nil)))
