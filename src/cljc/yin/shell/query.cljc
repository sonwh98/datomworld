(ns yin.shell.query
  "Query and name services, composition-side
   (docs/design/yin.repl.dht-composition.md sections 6 and 7).

   The three host modules a signed-name composition serves through —
   `dao.space.dht`, `yin.link` and `yin.head` — are registered here and
   answered here, over the node the composition owns.  The engine's own
   query surface (`yin.repl.query`) keeps the local `dao.space.query`
   bridge and its bounded request/response semantics, reads no node, and
   leaves a request under one of these operations unread on the shared
   pair (`yin.repl.query/foreign-request?`) for this namespace to answer.

   `registrations` is what a composition hands `yin.repl.query/register`,
   the generic module-registration mechanism; `serve-services` is the
   section 7 adapter, and it answers a local query too, so a composition
   that holds the pair's cursor never leaves a request behind it.

   This namespace holds no rule of the head trace, no fold, no
   verification and no file write: each answer checks its arguments, makes
   one call into the plain API of the implementation it composes, and
   responds.  A `yin.link/publish` additionally commits the envelopes it
   signed through the session's indexer, which is the generic commit the
   engine owns, and then awaits that commit's local publication
   completion: it services the publication medium the composition owns
   (`yin.repl/serve-publication`) and answers only once the durable HEAD
   write the publication asked for has settled, so a refusal reaches the
   caller instead of a success nobody earned
   (docs/design/yin.repl.dht-composition.md section 7)."
  (:require [dao.jing :as jing]
            [dao.space.dht :as dht]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.repl :as repl]
            [yin.repl.index :as repl.index]
            [yin.repl.query :as query]
            [yin.shell.registry :as shell.registry]
            [yin.vm :as vm]
            [yin.vm.linker.dht :as linker.dht]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.module :as module]
            ;; the follower accessor lives in the composition module that
            ;; section 6 relocates to yin.shell.dht; this require moves
            ;; with it
            [yin.repl.dht :as repl.dht]))


(def dht-module-name
  "The module `(require 'dao.space.dht)` activates: the plain Clojure DHT
   path (`dao.space.dht`) as host functions over the composition's node."
  'dao.space.dht)


(def dht-ops
  "The `dao.stream.apply` operations the `dao.space.dht` module requests:
   `load-index`, `load-status` and `q`, each answered by the
   `dao.space.dht` function of that name, `retry` and `cancel`, answered
   by `retry!` and `cancel!`, and `load-module` and `module-status`,
   answered by the `yin.vm.linker.dht` function of that name
   (yin.vm.linker.dht.md 10)."
  {'load-index ::dht-load-index
   'load-status ::dht-load-status
   'q ::dht-q
   'retry ::dht-retry
   'cancel ::dht-cancel
   'load-module ::dht-load-module
   'module-status ::dht-module-status})


(def dht-arities
  "The primitive profile arity each `dao.space.dht` export is registered
   under."
  {'load-index [1] 'load-status [1] 'q [2 :variadic] 'retry [1] 'cancel [1]
   'load-module [1] 'module-status [1]})


(def link-module-name
  "The module `(require 'yin.link)` activates: publishing a module from
   the session's indexed code and reading the reader's name environment
   (yin.vm.linker.dht.md 10), over the composition's node."
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
   `heads`, answered by `yin.vm.linker.head/heads` of the composition's
   follower, and `moved`, by `yin.vm.linker.head/moved`."
  {'heads ::head-heads
   'moved ::head-moved})


;; =============================================================================
;; The module registrations (yin.repl.query/register's generic mechanism)
;; =============================================================================

(defn- effect-export
  "An export of a module this namespace serves: a data constructor whose
   effect parks the caller on the session's query call pair, under the
   operation `op` names.  The effect key is the engine's
   (`yin.repl.query/call-effect`), so the one handler the session
   registers parks these calls beside its own `q`."
  [op]
  (fn [& args]
    (module/make-effect query/call-effect {:op op, :args (vec args)})))


(defn- dht-export
  "A `dao.space.dht` export, parking under the operation `dht-ops` names
   for `sym`."
  [sym]
  (effect-export (get dht-ops sym)))


(defn activate-dht
  "`registry` with the `dao.space.dht` host module installed."
  [registry]
  (module/register-host-module
    registry dht-module-name
    (into {} (map (fn [sym] [sym (dht-export sym)])) (keys dht-ops))
    (into {} (map (fn [[sym arity]]
                    [sym (vm/primitive-profile
                           sym :effectful arity #{query/call-effect} :none)]))
          dht-arities)))


(defn activate-link
  "`registry` with the `yin.link` host module installed."
  [registry]
  (module/register-host-module
    registry link-module-name
    (into {} (map (fn [[sym op]] [sym (effect-export op)])) link-ops)
    {'publish (vm/primitive-profile 'publish :effectful [2]
                                    #{query/call-effect} :none)
     'names (vm/primitive-profile 'names :effectful [0]
                                  #{query/call-effect} :none)}))


(defn activate-head
  "`registry` with the `yin.head` host module installed."
  [registry]
  (module/register-host-module
    registry head-module-name
    (into {} (map (fn [[sym op]] [sym (effect-export op)])) head-ops)
    {'heads (vm/primitive-profile 'heads :effectful [0]
                                  #{query/call-effect} :none)
     'moved (vm/primitive-profile 'moved :effectful [0]
                                  #{query/call-effect} :none)}))


(def registrations
  "The host module registrations a signed-name composition supplies to
   `yin.repl.query/register`.  A composition that serves none of them
   supplies none, and its sessions never see these names.

   Each entry is the record `yin.repl.query/register` reads: `:activate`
   installs the module into a requiring VM's registry, and `:ops` names
   the `dao.stream.apply` operations this namespace answers for it.  The
   engine defers exactly those operations to this composition
   (`yin.repl.query/registered-ops`, `serve`'s `:registered-ops`) and
   refuses an operation nobody registered, so a call under one of these
   reaches `serve-services` unread instead of being consumed and refused
   engine-side (docs/design/yin.repl.dht-composition.md sections 6-7)."
  {dht-module-name {:activate activate-dht
                    :ops (set (vals dht-ops))}
   link-module-name {:activate activate-link
                     :ops (set (vals link-ops))}
   head-module-name {:activate activate-head
                     :ops (set (vals head-ops))}})


;; =============================================================================
;; Answering one call
;; =============================================================================

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
           [node (query/refusal code (ex-message e) nil)]
           (throw e)))))


(defn- publication-call
  "`[node' answer]` of `retry!` or `cancel!` on `manifest`: `ok` answers
   `ok`; the plain function's refusal is answered under its own code."
  [node manifest f ok]
  (refusable-call node manifest f (constantly ok)))


(defn- no-node
  "The refusal a session with no transport answers every one of these
   calls with."
  [module-name hint]
  (query/unavailable (str "the shell has no " module-name " node; start it "
                          "with " hint)))


(defn- dht-answer
  "The response to one `dao.space.dht` call, answered from `node` — the
   composition's node — by the `dao.space.dht` function the operation
   names, and the node after it: `load-index` starts a load and answers
   its status at once, never waiting, or the plain function's refusal
   under its own code (`load-module` likewise); `load-status` answers the
   load's status map; `q` answers `dao.space.dht/q` under `limits`, refused
   until the index is loaded; `retry` and `cancel` answer `:retrying` and
   `:cancelled`, or the plain function's refusal; `load-module` starts a
   closure load and answers its status, and `module-status` answers it
   without the loaded closure.  Answers `[node response]`."
  [node limits request]
  (let [id (apply2/request-id request)
        [manifest & more] (apply2/request-args request)
        [node answer]
        (cond
          (nil? node)
          [node (no-node "DHT" "--index-store dht:<dir> and --dht-peer")]

          (nil? (query/encoded-size (apply2/request-args request)))
          [node (query/invalid "dao.space.dht takes portable Yin data only")]

          (not (jing/segment-address? manifest))
          [node (query/invalid
                  (str "dao.space.dht expects a manifest address, got "
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
                  [q & inputs] more]
              [node (if (= :loaded (:status status))
                      (query/bounded
                        #(apply dht/q node manifest q inputs) limits)
                      (query/refusal
                        :yin.repl.query/index-unavailable
                        (str "the index " manifest " is not loaded ("
                             (if status (name (:status status)) "never asked")
                             "); (dao.space.dht/load-index " manifest
                             ") loads it")
                        nil))])))]
    [node (query/response id answer)]))


(def ^:private parked
  "The answer `link-answer` gives for a call this round cannot settle: a
   signed publication whose local completion has not arrived.  It is never
   appended to the call pair — the request stays unread for a later round
   to answer from its correlation — so it is a value of this namespace,
   not a response shape."
  ::parked)


(defn- publication-refusal
  "The refusal a settled publication of `round` left on `indexer`, or nil:
   a `:publish` failure of that round is the composition's refusal or its
   failed durable HEAD write, and a signed publication's caller must hear
   it rather than be answered success
   (docs/design/yin.repl.dht-composition.md section 7)."
  [indexer round]
  (let [failure (:failure indexer)]
    (when (and (= :publish (:stage failure)) (= round (:round failure)))
      (query/unavailable
        (str "the name assertion was not published: "
             (or (:message failure) "the publication was refused"))))))


(defn- outstanding-publication?
  "Whether `indexer` still owes a publication result for `round`: the
   request is on its outstanding queue, so the composition has not
   delivered the local durable-publication outcome yet."
  [indexer round]
  (boolean (some #(= round (:round %))
                 (repl.index/pending-publications indexer))))


(defn- link-answer
  "The response to one `yin.link` call, from `node` — the composition's
   node — and `indexer`, the session's code indexer, under the session
   context `ctx` (`:authority`, `:key`, `:primitives`, `:modules`,
   `:round`): `names` answers `yin.vm.linker.dht/names`; `publish` answers
   `yin.vm.linker.dht/publish-name!` over the published index, whose name
   envelopes the indexer then commits and publishes as one transaction,
   the composition's durable HEAD write announcing the round's
   publication.  A refusal is answered under its own reason.

   `publish` awaits that publication's completion rather than reporting
   success over a request nobody has serviced: the commit emits
   `{:op :publish ...}` on the medium, the composition services and
   settles it (`serve-services`), and while a request of the commit's
   round is still outstanding this answers `parked` — the request left
   unread, the committed indexer and the call's correlation retained on
   `awaiting`.  A later round answers from that correlation, so the
   envelopes are committed exactly once and a refusal that arrives after
   the commit still reaches the caller
   (docs/design/yin.repl.dht-composition.md section 7).

   Answers `[node indexer awaiting response]`; `response` is
   `parked` for a call this round cannot settle, else the call's
   response envelope."
  [node indexer ctx awaiting request]
  (let [id (apply2/request-id request)
        args (apply2/request-args request)
        committed (get awaiting id)
        [indexer awaiting answer]
        (cond
          ;; a publish whose envelopes are already committed: answer from
          ;; the settlement of the publication it correlated to, and never
          ;; commit them a second time
          committed
          (if (outstanding-publication? indexer (:round committed))
            [indexer awaiting parked]
            [indexer (dissoc awaiting id)
             (or (publication-refusal indexer (:round committed))
                 (:answer committed))])

          (nil? node)
          [indexer awaiting (no-node "DHT" "--index-store dht:<dir>")]

          (nil? (query/encoded-size args))
          [indexer awaiting (query/invalid "yin.link takes portable Yin data only")]

          (= ::link-names (apply2/request-op request))
          [indexer awaiting {:ok (linker.dht/names node (:authority ctx))}]

          :else
          (let [[name exports] args
                db (when (and (symbol? name) (sequential? exports)
                              (every? symbol? exports))
                     (query/snapshot indexer))]
            (cond
              (nil? db)
              [indexer awaiting
               (query/invalid
                 (str "yin.link/publish expects a module name and a "
                      "vector of export names, got " (pr-str args)))]

              (query/refused? db) [indexer awaiting db]

              :else
              (let [[linked hosts] (shell.registry/session-modules
                                     (:modules ctx))
                    res (linker.dht/publish-name!
                          node db {:name name
                                   :exports (vec exports)
                                   :key (:key ctx)
                                   :primitives (:primitives ctx)
                                   :modules linked
                                   :call-site-tables (:call-site-tables ctx)
                                   :call-site-admissions (:call-site-admissions ctx)
                                   :host-modules hosts})
                    round (:round ctx)
                    indexer' (if (seq (:envelopes res))
                               (repl.index/commit-names
                                 indexer (:envelopes res) round)
                               indexer)
                    ok {:module name
                        :address (:address res)
                        :links (into {}
                                     (map (fn [[f o]]
                                            [f (if (= :ok (:status o))
                                                 :ok
                                                 (:reason o))]))
                                     (:links res))}]
                (cond
                  (= :refused (:status res))
                  [indexer awaiting
                   (query/refusal
                     (:reason res)
                     (str "yin.link/publish refused: "
                          (pr-str (dissoc res :status :reason)))
                     nil)]

                  (not= (:failure indexer) (:failure indexer'))
                  [indexer' awaiting
                   (query/unavailable "the name assertion was not indexed")]

                  (and (seq (:envelopes res))
                       (outstanding-publication? indexer' round))
                  [indexer'
                   (assoc awaiting id {:answer {:ok ok} :round round})
                   parked]

                  :else
                  [indexer' awaiting
                   (if (seq (:envelopes res))
                     (or (publication-refusal indexer' round) {:ok ok})
                     {:ok ok})])))))]
    [node indexer awaiting
     (cond
       (nil? answer) nil
       (= parked answer) parked
       :else (query/response id answer))]))


(defn- head-answer
  "The response to one `yin.head` call, from `node`, the composition's
   node, under the session context `ctx`: `heads` answers
   `yin.vm.linker.head/heads` of the node's follower (`{}` when it follows
   nothing), `moved` answers `yin.vm.linker.head/moved` of the node's
   names against the requesting VM's linked modules.  It holds no rule:
   each is one call."
  [node ctx request]
  (let [id (apply2/request-id request)
        answer (cond
                 (nil? node)
                 (no-node "DHT" "--index-store dht:<dir>")

                 (= ::head-heads (apply2/request-op request))
                 {:ok (if-some [f (repl.dht/follower node)] (head/heads f) {})}

                 :else
                 {:ok (head/moved
                        (linker.dht/names node (:authority ctx))
                        (first (shell.registry/session-modules
                                 (:modules ctx))))})]
    (query/response id answer)))


;; =============================================================================
;; The adapter (docs/design/yin.repl.dht-composition.md section 7)
;; =============================================================================

(def default-budget
  "Requests one `serve-services` round answers.  It matches the engine's
   own query serve budget, so a composition holding the pair's cursor
   drains a round exactly as far as the engine would."
  64)


(defn default-limits
  "The row and encoded-byte bounds of a composition that declares none of
   its own: the engine's, so a call answered composition-side is bounded
   exactly as one answered engine-side."
  []
  {:row-limit repl/query-row-limit
   :byte-limit repl/query-byte-limit})


(defn- op-in?
  [ops request]
  (contains? (set (vals ops)) (apply2/request-op request)))


(defn- install-served
  "`composition` with one `serve-round`'s successors installed: the
   successor node, the publication correlations a signed publish left
   behind, and the engine's query cursor and indexer.  Nothing else of
   either value is touched, and no node crosses inward."
  [composition served]
  (-> composition
      (assoc :node (:node served)
             :awaiting (:awaiting served))
      (update :engine assoc
              :query-pair (:pair served)
              :indexer (:indexer served))))


(defn- serve-round
  "One round over the session's query call pair, answering the three
   modules this namespace serves and delegating a local
   `dao.space.query` call to the engine's own interpreter
   (`yin.repl.query/answer`), so a composition that holds the cursor never
   leaves a request behind it.

   `awaiting` is the correlation a signed publication left on an earlier
   round (`{call-id {:answer … :round r}}`): a call it holds is answered
   from it once its publication settled, and never committed again.  A
   call this round cannot settle — a publish whose local publication
   completion has not arrived — leaves its request unread and its
   indexer's committed envelopes retained, exactly as a link the
   interpreter cannot answer leaves its request, and the round reports no
   progress for it.

   Answers `{:pair :node :indexer :awaiting :progress? :answered}`."
  [{:keys [pair indexer ast-indexer node limits budget link awaiting]}]
  (loop [pair pair
         node node
         indexer indexer
         awaiting (or awaiting {})
         remaining budget
         progress? false
         answered 0]
    (let [done (fn [node indexer awaiting progress? answered]
                 {:pair pair, :node node, :indexer indexer,
                  :awaiting awaiting, :progress? progress?,
                  :answered answered})
          r (when (pos? remaining)
              (stream/next (:call-in pair) (:cursor pair)))]
      (if-not (= :dao.stream/ok (:dao.stream/outcome r))
        (done node indexer awaiting progress? answered)
        (let [request (:dao.stream/value r)
              [node' indexer' awaiting' response]
              (cond
                (not (apply2/request? request)) [node indexer awaiting nil]
                (op-in? dht-ops request)
                (let [[n response] (dht-answer node limits request)]
                  [n indexer awaiting response])
                (op-in? link-ops request)
                (link-answer node indexer link awaiting request)
                (op-in? head-ops request)
                [node indexer awaiting (head-answer node link request)]
                :else [node indexer awaiting
                       (query/answer indexer ast-indexer limits request)])]
          (if (= parked response)
            ;; a signed publication awaiting its local completion: the
            ;; request stays unread, what the commit already concluded
            ;; survives, and the composition services the publication
            ;; before it serves the pair again
            (done node' indexer' awaiting' progress? answered)
            (let [landed? (or (nil? response)
                              (= :dao.stream/ok
                                 (:dao.stream/outcome
                                   (apply2/put-response! (:call-out pair)
                                                         response))))]
              (if landed?
                (recur (assoc pair :cursor (:dao.stream/cursor r))
                       node'
                       indexer'
                       awaiting'
                       (dec remaining)
                       true
                       (cond-> answered response inc))
                (done node indexer awaiting progress? answered)))))))))


(defn service-context
  "The context a signed service call is answered under: the explicit
   request data the engine supplies (`yin.repl/service-context` — the
   requesting VM's primitives, its module registry and the round) with the
   two halves only a composition may hold added, the reader's authority
   derived from the link source and the publisher's key.  The engine
   carries neither."
  [engine publisher-key]
  (merge (repl/service-context engine (:vm engine))
         {:authority (link/authority (:link-source engine))
          :key publisher-key}))


(defn serve-services
  "One composition round of query and name serving, the section 7
   `serve-services`: interpret the calls the session's VMs appended to the
   shared query pair, and answer `{:composition c' :progress? bool
   :answered n}`.

   `composition` is `{:engine <the engine state> :node <the transport's
   node or nil> :key <the publisher's key or nil> :limits {:row-limit n
   :byte-limit n} :awaiting <the publication correlations, or nil>}`.
   `c'` carries the successor node, the successor engine — whose query
   cursor and indexer this round moved — and the correlations a signed
   publication still awaits; nothing else of the engine is touched, and no
   node crosses inward.

   A round that left a signed publication outstanding services the
   composition's local publication medium and serves the pair once more,
   so a publish whose HEAD write completes locally is answered in the
   round that made it, and one that does not stays parked on its
   correlation (docs/design/yin.repl.dht-composition.md sections 3-4, 7)."
  ([composition] (serve-services composition default-budget))
  ([composition budget]
   (let [serve (fn [c]
                 (serve-round
                   {:pair (:query-pair (:engine c))
                    :indexer (get-in c [:engine :indexer])
                    :ast-indexer (get-in c [:engine :ast-indexer])
                    :node (:node c)
                    :limits (or (:limits c) (default-limits))
                    :budget budget
                    :awaiting (:awaiting c)
                    :link (service-context (:engine c) (:key c))}))
         first (serve composition)
         after (install-served composition first)
         ;; the composition owns the local publication service: a signed
         ;; publish left a request outstanding, so it is serviced and
         ;; settled here, and the parked call is then answered from its
         ;; correlation in a second serve of the same pair
         pumped (when (seq (:awaiting first)) (repl/serve-publication after))
         second (when pumped (serve pumped))
         served (if second (install-served pumped second) after)]
     {:composition served
      :progress? (boolean (or (:progress? first) (:progress? second)))
      :answered (+ (:answered first 0) (:answered second 0))})))
