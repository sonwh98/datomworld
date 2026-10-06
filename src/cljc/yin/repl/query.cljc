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

   A query that names `$ast` or `$occ` under `:in` is also given the AST
   indexer's relations (`yin.repl.ast-index`), after `$` and in declared
   order: `$ast` rows match `[id tag & slots]` at their exact arity, `$occ`
   tuples `[root path node]`.  They are the same session-to-date relations
   under either view; `:view` selects only the `$` datom view.  They are
   read from the AST indexer the shell holds when it serves, and a lost or
   failed one refuses such a query while datom-only queries still answer.

   Free variables are a query over these two relations: pass
   `yin.vm/occurrence-rules`, the portable data rule set, as the opt-in `%`
   input.  The shell ships no binding for it; the user defines or pastes
   the literal.  The answer is the raw free set, so it includes `yin/def`
   where a definition is present (`yin.vm/free-names` removes it).

   Answers and refusals are portable data.  Refusals use the FFI error
   envelope under a stable code: `::index-unavailable`, `::invalid-input`,
   `::result-limit`, or `::query-failed` for a query the engine rejects.
   Limits are the caller's: `serve` takes the row and encoded-byte bound
   and checks both before it appends a response.

   `(require 'dao.space.dht)` and `(require 'yin.link)` activate two more
   host modules the same way, answered over the shell's DHT node through
   the plain functions of `dao.space.dht` and `yin.vm.linker.dht`
   (yin.vm.linker.dht.md 10): each answer checks its arguments, makes one
   call into the plain API, and responds; a `yin.link/publish` then
   commits the envelopes it signed through the session's indexer.
   `(require 'yin.head)` activates `heads` and `moved`
   (yin.vm.linker.dht.head.md 6), each one call into
   `yin.vm.linker.head`: this namespace holds no rule of the head trace,
   no fold, no verification and no file write."
  (:require #?@(:cljd [["dart:typed_data" :refer [Uint8List]]]
                :default [])
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.space.dht :as dht]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.ringbuffer :as ring]
            [yin.repl.ast-index :as ast-index]
            [yin.repl.dht :as repl.dht]
            [yin.repl.index :as repl.index]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.linker.dht :as linker.dht]
            [yin.vm.linker.head :as head]
            [yin.vm.module :as module]))


(def module-name
  "The module `(require 'dao.space.query)` activates."
  'dao.space.query)


(def op
  "The `dao.stream.apply` operation a `q` call requests."
  ::q)


(def dht-module-name
  "The module `(require 'dao.space.dht)` activates: the plain Clojure DHT
   path (`dao.space.dht`) as host functions over the shell's own DHT node."
  'dao.space.dht)


(def dht-ops
  "The `dao.stream.apply` operations the `dao.space.dht` module requests:
   `load-index`, `load-status` and `q`, each answered by the
   `dao.space.dht` function of that name, `retry` and `cancel`,
   answered by `retry!` and `cancel!`, and `load-module` and
   `module-status`, answered by the `yin.vm.linker.dht` function of that
   name (yin.vm.linker.dht.md 10)."
  {'load-index ::dht-load-index
   'load-status ::dht-load-status
   'q ::dht-q
   'retry ::dht-retry
   'cancel ::dht-cancel
   'load-module ::dht-load-module
   'module-status ::dht-module-status})


(def link-module-name
  "The module `(require 'yin.link)` activates: publishing a module from
   the session's indexed code and reading the reader's name environment
   (yin.vm.linker.dht.md 10), over the shell's own DHT node."
  'yin.link)


(def link-ops
  "The `dao.stream.apply` operations the `yin.link` module requests:
   `publish`, answered by `yin.vm.linker.dht/publish-name!`, and `names`,
   answered by `yin.vm.linker.dht/names`."
  {'publish ::link-publish
   'names ::link-names})


(def head-module-name
  "The module `(require 'yin.head)` activates: the follower's status and
   the moved names (yin.vm.linker.dht.head.md 5.6, 5.7), each one call
   into `yin.vm.linker.head`."
  'yin.head)


(def head-ops
  "The `dao.stream.apply` operations the `yin.head` module requests:
   `heads`, answered by `yin.vm.linker.head/heads` of the shell's
   follower, and `moved`, by `yin.vm.linker.head/moved`."
  {'heads ::head-heads
   'moved ::head-moved})


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
  (module/make-effect ::call {:args (vec args)}))


(def ^:private exports
  {'q q})


(def ^:private profiles
  {'q (vm/primitive-profile 'q :effectful [1 2 :variadic] #{::call} :none)})


(defn activate
  "`registry` with the `dao.space.query` host module installed."
  [registry]
  (module/register-host-module registry module-name exports profiles))


(defn- dht-export
  "A `dao.space.dht` export: a data constructor whose effect parks the
   caller on the call pair under the operation `dht-ops` names."
  [sym]
  (fn [& args]
    (module/make-effect ::call {:op (get dht-ops sym), :args (vec args)})))


(def ^:private dht-arities
  {'load-index [1] 'load-status [1] 'q [2 :variadic] 'retry [1] 'cancel [1]
   'load-module [1] 'module-status [1]})


(defn activate-dht
  "`registry` with the `dao.space.dht` host module installed."
  [registry]
  (module/register-host-module
    registry dht-module-name
    (into {} (map (fn [sym] [sym (dht-export sym)])) (keys dht-ops))
    (into {} (map (fn [[sym arity]]
                    [sym (vm/primitive-profile sym :effectful arity #{::call}
                                               :none)]))
          dht-arities)))


(defn activate-link
  "`registry` with the `yin.link` host module installed."
  [registry]
  (module/register-host-module
    registry link-module-name
    (into {} (map (fn [[sym op]]
                    [sym (fn [& args]
                           (module/make-effect ::call {:op op, :args (vec args)}))]))
          link-ops)
    {'publish (vm/primitive-profile 'publish :effectful [2] #{::call} :none)
     'names (vm/primitive-profile 'names :effectful [0] #{::call} :none)}))


(defn activate-head
  "`registry` with the `yin.head` host module installed."
  [registry]
  (module/register-host-module
    registry head-module-name
    (into {} (map (fn [[sym op]]
                    [sym (fn [& args]
                           (module/make-effect ::call {:op op, :args (vec args)}))]))
          head-ops)
    {'heads (vm/primitive-profile 'heads :effectful [0] #{::call} :none)
     'moved (vm/primitive-profile 'moved :effectful [0] #{::call} :none)}))


(def ^:private host-modules
  {module-name activate
   dht-module-name activate-dht
   link-module-name activate-link
   head-module-name activate-head})


(defn- require-handler
  "`:module/require` for a session with the query bridge: the first
   require of `dao.space.query` or `dao.space.dht` installs that module
   into the requiring VM's registry value and answers at once; every
   other require, and a repeated one, is `module/require-handler`'s."
  [state effect opts]
  (let [activate (get host-modules (:module effect))]
    (if (and activate
             (nil? (module/resolve-module (:modules state) (:module effect))))
      {:value (:module effect),
       :state (update state :modules activate),
       :blocked? false}
      (module/require-handler state effect opts))))


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
                                       (apply2/request call-id
                                                       (:op effect op)
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


(defn- ast-relations
  "The relations `patterns` name, in order, from `ast-indexer` as it
   stands, or a refusal when it is absent, lost or failed.  They are the
   same session-to-date relations under either view."
  [ast-indexer patterns]
  (if (empty? patterns)
    []
    (if-let [{:keys [ast occ]} (some-> ast-indexer ast-index/relations)]
      (mapv {'$ast (query/relation ast), '$occ (query/relation occ)} patterns)
      (let [{:keys [lost? failure]} (some-> ast-indexer ast-index/status)]
        (refusal ::index-unavailable
                 (str "$ast and $occ are unavailable: "
                      (cond (nil? ast-indexer) "the session has no AST index"
                            lost? "a program batch was lost before it was indexed"
                            :else (str "a program was refused ("
                                       (name (or (:reason failure)
                                                 (:stage failure)))
                                       ")"))
                      "; (reset) rebuilds them")
                 nil)))))


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


(def ^:private ast-sources
  "The AST relations a query names under `:in` to have the shell supply
   them (yin.repl.ast-index)."
  #{'$ast '$occ})


(defn- caller-patterns
  "The `:in` patterns the caller's inputs fill: every declared pattern but
   the session's sources, the implicit index `$` and the AST relations."
  [query]
  (vec (remove #(or (= '$ %) (contains? ast-sources %)) (in-patterns query))))


(defn- ast-patterns
  "The AST relations `query` names under `:in`, in declared order."
  [query]
  (filterv #(contains? ast-sources %) (in-patterns query)))


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
  "`query` with the index bound to `$`, the first database input it names,
   then the AST relations it names, in declared order.  The index is
   implicit: a query's `:in` names only the caller's inputs, with or
   without `$` among them, and those inputs fill the other patterns in
   order.  A query without `:in` takes none."
  [query]
  (let [declared (in-patterns query)
        in (-> ['$] (into (ast-patterns query)) (into (caller-patterns query)))]
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


(defn- bounded
  "The collected result `run` answers, `{:ok value}`, or a refusal: a
   query the engine rejects, a result over `limits`, or one that is not
   portable Yin data."
  [run {:keys [row-limit byte-limit]}]
  (let [result (try
                 {:value (run)}
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


(defn- evaluate
  [db view query inputs limits]
  (bounded #(query/collect (apply query/q query (view db) inputs)) limits))


(defn answer
  "The response to one call-pair `request`, answered from `indexer`, and
   from `ast-indexer` for a query naming `$ast` or `$occ`, under `limits`
   (`{:row-limit n :byte-limit n}`).  Without an AST indexer such a query
   is refused."
  ([indexer limits request]
   (answer indexer nil limits request))
  ([indexer ast-indexer limits request]
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
                             (snapshot indexer))
                        sources (when-not (or (refused? split) (refused? view)
                                              (refused? db))
                                  (ast-relations ast-indexer
                                                 (ast-patterns query)))]
                    (cond
                      (refused? split) split
                      (refused? view) view
                      (refused? db) db
                      (refused? sources) sources
                      :else (evaluate db view (with-index query)
                                      (into sources inputs) limits))))]
     (if (refused? answer)
       {apply2/id-key id, apply2/error-key answer}
       (apply2/success-response id (:ok answer))))))


(defn- host-status
  "A load status as the host answers it: the status without its loaded
   value, which an index load counts under `:datoms` instead."
  [status]
  (when status
    (cond-> (dissoc status :value)
      (and (= :loaded (:status status)) (= dht/index-kind (:kind status)))
      (assoc :datoms (count (:value status))))))


(defn- refusable-call
  "`[node' answer]` of the plain function `f` on `node` and `manifest`:
   `(ok node')` answers; a `:dao.space.dht/refused` of any code is
   answered under its own code, the node unchanged."
  [node manifest f ok]
  (try (let [node (f node manifest)] [node {:ok (ok node)}])
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (if-let [code (:dao.space.dht/refused (ex-data e))]
           [node (refusal code (ex-message e) nil)]
           (throw e)))))


(defn- publication-call
  "`[node' answer]` of `retry!` or `cancel!` on `manifest`: `ok` answers
   `ok`; the plain function's refusal is answered under its own code."
  [node manifest f ok]
  (refusable-call node manifest f (constantly ok)))


(defn- dht-answer
  "The response to one `dao.space.dht` call, answered from `node` — the
   shell's DHT node — by the `dao.space.dht` function the operation
   names, and the node after it: `load-index` starts a load and answers
   its status at once, never waiting, or the plain function's refusal
   under its own code (`load-module` likewise); `load-status` answers the load's
   status map; `q` answers `dao.space.dht/q` under `limits`, refused
   until the index is loaded; `retry` and `cancel` answer `:retrying`
   and `:cancelled`, or the plain function's refusal; `load-module`
   starts a closure load and answers its status, and `module-status`
   answers it without the loaded closure.  Answers
   `[node response]`."
  [node limits request]
  (let [id (apply2/request-id request)
        [manifest & more] (apply2/request-args request)
        [node answer]
        (cond
          (nil? node)
          [node (unavailable (str "the shell has no DHT node; start it with "
                                  "--index-store dht:<dir> and --dht-peer"))]

          (nil? (encoded-size (apply2/request-args request)))
          [node (invalid "dao.space.dht takes portable Yin data only")]

          (not (jing/segment-address? manifest))
          [node (invalid (str "dao.space.dht expects a manifest address, got "
                              (pr-str manifest)))]

          :else
          (case (apply2/request-op request)
            ::dht-load-index
            (refusable-call node manifest dht/load-index
                            #(:status (dht/load-status % manifest)))

            ::dht-load-status
            [node {:ok (host-status (dht/load-status node manifest))}]

            ::dht-load-module
            (refusable-call node manifest linker.dht/load-module
                            #(:status (linker.dht/module-status % manifest)))

            ::dht-module-status
            [node {:ok (host-status (linker.dht/module-status node manifest))}]

            ::dht-retry
            (publication-call node manifest dht/retry! :retrying)

            ::dht-cancel
            (publication-call node manifest dht/cancel! :cancelled)

            ::dht-q
            (let [status (dht/load-status node manifest)
                  [query & inputs] more]
              [node (if (= :loaded (:status status))
                      (bounded #(apply dht/q node manifest query inputs) limits)
                      (refusal ::index-unavailable
                               (str "the index " manifest " is not loaded ("
                                    (if status (name (:status status)) "never asked")
                                    "); (dao.space.dht/load-index " manifest
                                    ") loads it")
                               nil))])))]
    [node (if (refused? answer)
            {apply2/id-key id, apply2/error-key answer}
            (apply2/success-response id (:ok answer)))]))


(defn- dht-op?
  [request]
  (contains? (set (vals dht-ops)) (apply2/request-op request)))


(defn- head-op?
  [request]
  (contains? (set (vals head-ops)) (apply2/request-op request)))


(defn- link-op?
  [request]
  (contains? (set (vals link-ops)) (apply2/request-op request)))


(defn- session-modules
  "`[linked hosts]` of the requesting VM's module `registry`: the modules
   it linked through the linker, `{name manifest-address}` -- a linked
   entry carries its manifest's address -- and the names of its host
   modules, which carry none."
  [registry]
  (let [entries (module/module-entries registry)]
    [(into {} (keep (fn [[n e]] (when (some? (:address e)) [n (:address e)])))
           entries)
     (set (keep (fn [[n e]] (when (nil? (:address e)) n)) entries))]))


(defn- link-answer
  "The response to one `yin.link` call, from `node` -- the shell's DHT
   node -- and `indexer`, the session's code indexer, under the session
   composition `ctx` (`:authority`, `:key`, `:primitives`, `:modules`,
   `:round`): `names` answers `yin.vm.linker.dht/names`; `publish` answers
   `yin.vm.linker.dht/publish-name!` over the published index, whose
   name envelopes the indexer then commits and publishes as one
   transaction, the HEAD write announcing the round's publication.  A
   refusal is answered under its own reason.  Answers `[node indexer
   response]`."
  [node indexer ctx request]
  (let [id (apply2/request-id request)
        args (apply2/request-args request)
        [indexer answer]
        (cond
          (nil? node)
          [indexer (unavailable (str "the shell has no DHT node; start it with "
                                     "--index-store dht:<dir>"))]

          (nil? (encoded-size args))
          [indexer (invalid "yin.link takes portable Yin data only")]

          (= ::link-names (apply2/request-op request))
          [indexer {:ok (linker.dht/names node (:authority ctx))}]

          :else
          (let [[name exports] args
                db (when (and (symbol? name) (sequential? exports)
                              (every? symbol? exports))
                     (snapshot indexer))]
            (cond
              (nil? db)
              [indexer (invalid (str "yin.link/publish expects a module name "
                                     "and a vector of export names, got "
                                     (pr-str args)))]

              (refused? db) [indexer db]

              :else
              (let [[linked hosts] (session-modules (:modules ctx))
                    res (linker.dht/publish-name!
                          node db {:name name
                                   :exports (vec exports)
                                   :key (:key ctx)
                                   :primitives (:primitives ctx)
                                   :modules linked
                                   :host-modules hosts})
                    indexer' (if (seq (:envelopes res))
                               (repl.index/commit-names indexer (:envelopes res)
                                                        (:round ctx))
                               indexer)]
                (cond
                  (= :refused (:status res))
                  [indexer (refusal (:reason res)
                                    (str "yin.link/publish refused: "
                                         (pr-str (dissoc res :status :reason)))
                                    nil)]

                  (not= (:failure indexer) (:failure indexer'))
                  [indexer' (unavailable "the name assertion was not indexed")]

                  :else
                  [indexer'
                   {:ok {:module name
                         :address (:address res)
                         :links (into {}
                                      (map (fn [[f o]]
                                             [f (if (= :ok (:status o))
                                                  :ok
                                                  (:reason o))]))
                                      (:links res))}}])))))]
    [node indexer (if (refused? answer)
                    {apply2/id-key id, apply2/error-key answer}
                    (apply2/success-response id (:ok answer)))]))


(defn- head-answer
  "The response to one `yin.head` call, from `node`, the shell's DHT
   node, under the session composition `ctx`: `heads` answers
   `yin.vm.linker.head/heads` of the node's follower (`{}` when it
   follows nothing), `moved` answers `yin.vm.linker.head/moved` of the
   node's names against the requesting VM's linked modules.  It holds no
   rule: each is one call."
  [node ctx request]
  (let [id (apply2/request-id request)
        answer (cond
                 (nil? node)
                 (unavailable (str "the shell has no DHT node; start it with "
                                   "--index-store dht:<dir>"))

                 (= ::head-heads (apply2/request-op request))
                 {:ok (if-some [f (repl.dht/follower node)] (head/heads f) {})}

                 :else
                 {:ok (head/moved (linker.dht/names node (:authority ctx))
                                  (first (session-modules (:modules ctx))))})]
    (if (refused? answer)
      {apply2/id-key id, apply2/error-key answer}
      (apply2/success-response id (:ok answer)))))


;; =============================================================================
;; The interpreter
;; =============================================================================

(defn serve
  "One serve round of the query interpreter over `pair`: answer every
   request its cursor has not consumed, from `indexer` and `ast-indexer`
   as they stand now, at most `budget` of them.  A response is appended before the cursor
   moves past its request; one the call-out refuses leaves the request to
   be re-read.  A value that is not a request envelope is consumed
   unanswered: no call made it.  A `dao.space.dht` call is answered from
   `dht`, the shell's DHT node, which a `load-index` advances.  A
   `yin.link` call is answered from `dht` and `indexer` under `link`, the
   session composition `link-answer` reads; a publish advances both.  A
   `yin.head` call reads the follower `dht` carries.  Returns `{:pair pair :dht node :indexer indexer :progress? bool
   :answered n}`, `n` the responses appended."
  [{:keys [pair indexer ast-indexer dht limits budget link]}]
  (loop [pair pair
         node dht
         indexer indexer
         remaining budget
         progress? false
         answered 0]
    (let [r (when (pos? remaining)
              (stream/next (:call-in pair) (:cursor pair)))
          done (fn [pair node indexer progress? answered]
                 {:pair pair, :dht node, :indexer indexer,
                  :progress? progress?, :answered answered})]
      (if-not (= :dao.stream/ok (:dao.stream/outcome r))
        (done pair node indexer progress? answered)
        (let [request (:dao.stream/value r)
              [node' indexer' response]
              (cond
                (not (apply2/request? request)) [node indexer nil]
                (dht-op? request) (let [[n response] (dht-answer node limits request)]
                                    [n indexer response])
                (link-op? request) (link-answer node indexer link request)
                (head-op? request) [node indexer (head-answer node link request)]
                :else [node indexer (answer indexer ast-indexer limits request)])
              landed? (or (nil? response)
                          (= :dao.stream/ok
                             (:dao.stream/outcome
                               (apply2/put-response! (:call-out pair)
                                                     response))))]
          (if landed?
            (recur (assoc pair :cursor (:dao.stream/cursor r))
                   node'
                   indexer'
                   (dec remaining)
                   true
                   (cond-> answered response inc))
            (done pair node indexer progress? answered)))))))
