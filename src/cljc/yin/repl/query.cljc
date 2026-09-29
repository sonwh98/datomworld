(ns yin.repl.query
  "The shell's query bridge: `(require 'dao.space.query)` activates
   `dao.space.query/q` over the session's code index
   (docs/design/yin.repl.dao.space-index.md, \"Query surface\").

   `dao.space.query` is a session-scoped host module that is absent from
   the VM's registry until the user requires it.  The session's registry
   answers `:module/require` for that one name by installing the module
   into the requiring VM's own registry value, and hands every other name
   to `yin.vm.module/require-handler` unchanged.  Before the require, the
   qualified export fails name resolution like any unknown name.  A session
   rebuild (`(reset)`, `(vm ...)`) builds a fresh registry, which drops the
   module.

   The export `q` is a data constructor: it returns a `::call` effect
   carrying its arguments, and the session registry's handler for that
   effect parks the calling continuation as an ordinary FFI call on the
   VM's `dao.stream.apply` call pair.  The call uses the VM's own park id,
   caller-scoped call id and response reader (`yin.vm.ffi`), so the engine's
   FFI response router wakes the call and `ffi/call-result` unwraps the
   answer or raises its error, exactly as for a `dao.stream.apply/call`
   node.  The request carries only data: the query, its `:in` inputs, and
   an optional final options map.

   The shell owns the other end of the pair.  `serve` reads each request
   and answers it from the indexer the shell holds when it serves, never
   from state captured when the session was built: the snapshot is the
   latest manifest the indexer published and read back.  A lost indexer,
   a recorded failure, or committed code not yet published refuses the
   call, because an older manifest must not be presented as complete.  A
   session that has committed nothing is a valid empty database.

   Answers and refusals are portable data.  Refusals use the FFI error
   envelope under a stable code: `::index-unavailable`, `::invalid-input`,
   `::result-limit`, or `::query-failed` for a query the engine rejects.
   Limits are the caller's: `serve` takes the row and encoded-byte bound
   and checks both before it appends a response."
  (:require #?@(:cljd [["dart:typed_data" :refer [Uint8List]]]
                :default [])
            [dao.jing.cbor :as cbor]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.ringbuffer :as ring]
            [yin.repl.index :as repl.index]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]))


(def module-name
  "The module `(require 'dao.space.query)` activates."
  'dao.space.query)


(def op
  "The `dao.stream.apply` operation a `q` call requests."
  ::q)


(def views
  "The options map's `:view` values and the `dao.space.query` interpreter
   each names.  `:current` is the default."
  {:current query/current
   :history query/history})


;; =============================================================================
;; The module and its call
;; =============================================================================

(defn- q
  "The export: a data constructor.  The effect it returns is answered by
   `call-handler`, which parks the caller on the call pair."
  [& args]
  {:effect ::call, :args (vec args)})


(def ^:private exports
  {'q q})


(def ^:private profiles
  {'q (vm/primitive-profile 'q :effectful [1 2 :variadic] #{::call} :none)})


(defn activate
  "`registry` with the `dao.space.query` host module installed."
  [registry]
  (module/register-host-module registry module-name exports profiles))


(defn- require-handler
  "`:module/require` for a session with the query bridge: the first
   require of `dao.space.query` installs the module into the requiring
   VM's registry value and answers at once; every other require, and a
   repeated one, is `module/require-handler`'s."
  [state effect opts]
  (if (and (= module-name (:module effect))
           (nil? (module/resolve-module (:modules state) module-name)))
    {:value module-name,
     :state (update state :modules activate),
     :blocked? false}
    (module/require-handler state effect opts)))


(defn- blocked
  [state entry]
  {:state (-> state
              (update :wait-set (fnil conj []) entry)
              (assoc :value :yin/blocked
                     :blocked? true
                     :halted? false)),
   :value :yin/blocked,
   :blocked? true})


(defn- call-handler
  "Park the continuation of a `q` call as an FFI call and append its
   request.  The continuation's registers come from the VM's own
   `:module/require` park builder, the parking point every kernel supplies
   for an effectful primitive.  The walker's response reader nests its
   continuation in an `eval-call` frame (`ffi/call-response-wait-entry`);
   the positional kernels wait on their register payload
   (`ffi/response-wait-entry`).  The pair's call-in is a ring buffer, which
   never answers `full`, so any outcome but `ok` fails the call."
  [state effect {:keys [park-entry-fns]}]
  (let [build (get park-entry-fns :module/require)
        _ (when-not build
            (throw (ex-info "dao.space.query/q cannot park at this call site"
                            {:op op})))
        {:keys [call-in]} (ffi/require-call-pair! (:resources state) op)
        regs (build state effect nil)
        call-id (ffi/call-id state (engine/park-id state))
        walker? (= :ast-walker (:vm-model state))
        parked (engine/park-continuation
                 state
                 (if walker?
                   {:k {:type :dao.stream.apply/eval-call,
                        :next (:k regs),
                        :env (:env regs)},
                    :env (:env regs)}
                   regs)
                 call-id)
        outcome (:dao.stream/outcome
                  (apply2/put-request! call-in
                                       (apply2/request call-id op
                                                       (vec (:args effect)))))]
    (when-not (= :dao.stream/ok outcome)
      (throw (ex-info "dao.space.query/q request could not be appended"
                      {:op op, :outcome outcome})))
    (blocked parked
             (if walker?
               (ffi/call-response-wait-entry call-id (:k regs) (:env regs))
               (ffi/response-wait-entry regs call-id)))))


(defn register
  "`registry` with the bridge's effect handlers: the session's
   `:module/require`, and the `q` call.  The module itself is not
   installed."
  [registry]
  (-> registry
      (module/register-effect-handler :module/require require-handler)
      (module/register-effect-handler ::call call-handler)))


(defn waiting?
  "True while `vm` waits on a query call: a response reader or a retained
   request writer of the call pair."
  [vm]
  (boolean (some #(or (some? (ffi/response-call-id %))
                      (some? (ffi/request-call-id %)))
                 (:wait-set vm))))


;; =============================================================================
;; The call pair
;; =============================================================================

(defn- medium
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn discard-answers
  "`vm`, rolled back to a base that has no call outstanding, reading its
   call-out from the newest position of `pair`'s call-out.  The base's
   own cursor stopped where its round began; every response since
   answers a call of the abandoned run, which no call of the base can
   claim, and a failed round that made more calls than the medium holds
   would otherwise leave that cursor behind evicted responses, so the
   next call would read a gap.  The call ids stay unique: the rollback
   carries the abandoned run's id counter forward.  A VM without the
   call-out cursor is returned unchanged."
  [vm pair]
  (if (contains? (:resources vm) vm/call-out-cursor-key)
    (assoc-in vm
              [:resources vm/call-out-cursor-key]
              (vm/cursor-entry vm/call-out-stream-key
                               (newest (:call-out pair))))
    vm))


(defn abandon-requests
  "`pair` with the interpreter's cursor past every request on its
   call-in: the calls of a run the shell has abandoned are not answered."
  [pair]
  (assoc pair :cursor (newest (:call-in pair))))


(defn make-pair
  "The session's query call pair: `:call-in` and `:call-out` media of
   `capacity` elements, `:out-cursor` the call-out cursor the VM is
   constructed with (the pair is supplied, so its cursor is too), and
   `:cursor` the interpreter's own call-in cursor, both minted before any
   request exists."
  [capacity]
  (let [call-in (medium capacity)
        call-out (medium capacity)]
    {:call-in call-in
     :call-out call-out
     :out-cursor (vm/mint-oldest call-out :yin.repl/query)
     :cursor (vm/mint-oldest call-in :yin.repl/query)}))


;; =============================================================================
;; Answering one request
;; =============================================================================

(defn- refusal
  [code message data]
  (merge (apply2/error code (str message " (" code ")")) data))


(defn- refused?
  [x]
  (and (map? x) (contains? x apply2/code-key)))


(defn- unavailable
  [reason]
  (refusal ::index-unavailable
           (str "the code index is unavailable: " reason
                "; (reset) rebuilds it")
           nil))


(defn- invalid
  [message]
  (refusal ::invalid-input message nil))


(defn- snapshot
  "The database a call reads, or a refusal: the latest manifest the
   indexer published, read back from its store.  Committed code must all
   be published, so no older manifest stands in for the current one."
  [indexer]
  (let [{:keys [lost? failure published? transactions]}
        (repl.index/status indexer)]
    (cond
      lost?
      (unavailable "a program batch was lost before it was indexed")

      failure
      (unavailable (str "round " (:round failure) " was not indexed ("
                        (name (:stage failure)) " failed)"))

      (zero? transactions)
      (query/relation [])

      (not published?)
      (unavailable "committed code is not published")

      :else
      (try
        (query/relation (index/read-datoms (:content-store indexer)
                                           (:manifest-address indexer)))
        (catch #?(:cljd Object :clj Exception :cljs :default) e
          (unavailable (str "the published index could not be read: "
                            (or (ex-message e) (str e)))))))))


(defn- byte-length
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :default (alength bs)))


(defn- encoded-size
  "The canonical CBOR size of `x`, or nil when `x` is not portable data."
  [x]
  (try
    (byte-length (cbor/encode x))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ nil)))


(defn- in-patterns
  "The patterns `query` declares under `:in`, nil when it declares none."
  [query]
  (if (map? query)
    (some-> (:in query) vec)
    (let [[marker & after] (drop-while #(not= :in %) query)]
      (when marker (vec (take-while (complement keyword?) after))))))


(defn- caller-patterns
  "The `:in` patterns the caller's inputs fill: every declared pattern but
   the implicit index `$`."
  [query]
  (vec (remove #{'$} (in-patterns query))))


(defn- split-args
  "`[inputs options]` of the arguments after the query: one input per
   pattern of `caller-patterns`, a map among them included, then at most
   one options map.  A refusal when the arguments do not fit."
  [query args]
  (let [n (count (caller-patterns query))
        [inputs more] (split-at n args)]
    (if (and (= n (count inputs))
             (or (empty? more) (and (= 1 (count more)) (map? (first more)))))
      [(vec inputs) (first more)]
      (refusal ::query-failed
               (str "query failed: query input arity must match :in, " n
                    " :in inputs and an optional options map expected, got "
                    (count args) " arguments")
               nil))))


(defn- with-index
  "`query` with the index bound to `$`, the first database input it names.
   The index is implicit: a query's `:in` names only the caller's inputs,
   with or without `$` among them, and those inputs fill the other
   patterns in order.  A query without `:in` takes none."
  [query]
  (let [declared (in-patterns query)
        in (into ['$] (caller-patterns query))]
    (cond (nil? declared) query
          (map? query) (assoc query :in in)
          :else (let [[before [_ & after]] (split-with #(not= :in %) query)]
                  (-> (vec before)
                      (conj :in)
                      (into in)
                      (into (drop-while (complement keyword?) after)))))))


(defn- view-of
  "The view the options name, or a refusal."
  [options]
  (cond
    (nil? options) (:current views)

    (not= #{:view} (set (keys options)))
    (invalid (str "q options must be {:view :current} or {:view :history}, got "
                  (pr-str options)))

    (contains? views (:view options)) (get views (:view options))

    :else (invalid (str "q :view must be :current or :history, got "
                        (pr-str (:view options))))))


(defn- row-count
  "Rows of a collected result: a relation or collection counts its
   members, a scalar or tuple is one."
  [result]
  (if (or (set? result) (sequential? result) (nil? result))
    (count result)
    1))


(defn- evaluate
  [db view query inputs {:keys [row-limit byte-limit]}]
  (let [result (try
                 {:value (query/collect
                           (apply query/q query (view db) inputs))}
                 (catch #?(:cljd Object :clj Exception :cljs :default) e
                   {:refusal (refusal ::query-failed
                                      (str "query failed: "
                                           (or (ex-message e) (str e)))
                                      nil)}))]
    (if-let [r (:refusal result)]
      r
      (let [value (:value result)
            rows (row-count value)
            size (when (<= rows row-limit) (encoded-size value))]
        (cond
          (> rows row-limit)
          (refusal ::result-limit
                   (str "the query result has " rows
                        " rows, over the limit of " row-limit)
                   {::limit {:rows row-limit}})

          (nil? size)
          (refusal ::query-failed
                   "the query result is not portable Yin data"
                   nil)

          (> size byte-limit)
          (refusal ::result-limit
                   (str "the query result encodes to " size
                        " bytes, over the limit of " byte-limit)
                   {::limit {:bytes byte-limit}})

          :else {:ok value})))))


(defn answer
  "The response to one call-pair `request`, answered from `indexer`
   under `limits` (`{:row-limit n :byte-limit n}`)."
  [indexer limits request]
  (let [id (apply2/request-id request)
        [query & more] (apply2/request-args request)
        answer (cond
                 (not= op (apply2/request-op request))
                 (refusal :dao.stream.apply/unknown-operation
                          "No handler for operation"
                          nil)

                 (nil? (encoded-size (apply2/request-args request)))
                 (invalid (str "q takes portable Yin data only: nil, booleans,"
                               " numbers, strings, keywords, symbols, and"
                               " vectors, lists, sets and maps of them"))

                 (not (or (vector? query) (map? query)))
                 (invalid (str "q expects a query vector or map, got "
                               (pr-str query)))

                 :else
                 (let [split (split-args query more)
                       [inputs options] (when-not (refused? split) split)
                       view (when-not (refused? split) (view-of options))
                       db (when-not (or (refused? split) (refused? view))
                            (snapshot indexer))]
                   (cond
                     (refused? split) split
                     (refused? view) view
                     (refused? db) db
                     :else (evaluate db view (with-index query) inputs
                                     limits))))]
    (if (refused? answer)
      {apply2/id-key id, apply2/error-key answer}
      (apply2/success-response id (:ok answer)))))


;; =============================================================================
;; The interpreter
;; =============================================================================

(defn serve
  "One serve round of the query interpreter over `pair`: answer every
   request its cursor has not consumed, from `indexer` as it stands now,
   at most `budget` of them.  A response is appended before the cursor
   moves past its request; one the call-out refuses leaves the request to
   be re-read.  A value that is not a request envelope is consumed
   unanswered: no call made it.  Returns
   `{:pair pair :progress? bool :answered n}`, `n` the responses
   appended."
  [{:keys [pair indexer limits budget]}]
  (loop [pair pair
         remaining budget
         progress? false
         answered 0]
    (let [r (when (pos? remaining)
              (stream/next (:call-in pair) (:cursor pair)))]
      (if-not (= :dao.stream/ok (:dao.stream/outcome r))
        {:pair pair, :progress? progress?, :answered answered}
        (let [request (:dao.stream/value r)
              response (when (apply2/request? request)
                         (answer indexer limits request))
              landed? (or (nil? response)
                          (= :dao.stream/ok
                             (:dao.stream/outcome
                               (apply2/put-response! (:call-out pair)
                                                     response))))]
          (if landed?
            (recur (assoc pair :cursor (:dao.stream/cursor r))
                   (dec remaining)
                   true
                   (cond-> answered response inc))
            {:pair pair, :progress? progress?, :answered answered}))))))
