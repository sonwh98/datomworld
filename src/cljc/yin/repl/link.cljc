(ns yin.repl.link
  "The REPL's linker-side composition (yin.vm.linker.md sections 6.1 and 9,
   M5): the interpreter box of the four-stream topology.

   The shell composes the link pair per VM (`yin.repl/make-session`) and
   the content pair per content source; this namespace owns the second
   and answers what the first receives.  One serve round reads every link
   request the interpreter has not yet seen, resolves each by name
   through the composition's name environment -- the snapshot the host
   supplies, an authority fold (`yin.vm.linker.authority/name-environment`)
   reduced to `name -> manifest address` -- and links the manifest's
   image over the content pair, appending the response the requesting
   task correlates on (section 7.2, steps 5 to 6).

   Step 5b is deferred: the obligations travel on the response and the
   receiving task's engine discharges them against its live state
   (section 7.2, step 7), so the interpreter passes
   `:defer-discharge` to `link-manifest` and discharges nothing here.

   Liveness is the composition's (section 6.3, D6): an attempt serves the
   content pair for at most `attempt-budget` drive rounds and then
   reports the link `:pending` -- nothing is appended, the request is
   re-read and re-attempted on a later round, and `abandon` is the
   shell's.  No clock, no atom, no global: every input is an argument or
   a composition value, and every outcome is plain data.

   A DHT link source (docs/design/yin.vm.linker.dht.md 4.1) holds no
   handle: each serve round receives the shell's `dao.space.dht` node
   and answers the node after it.  A resolved name whose closure is not
   loaded starts its load (`yin.vm.linker.dht/load-module`) and reports
   the link `:pending`, naming the manifest it waits on; the node's own
   step fetches.  A failed load is refused in its section 9 shape and
   forgotten; a loaded one has its dependency bindings checked, then
   links over a local runtime on the node's store, asking no peer."
  (:require [dao.jing.content :as content]
            [dao.space.dht :as dht]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.vm.linker :as linker]
            [yin.vm.linker.dht :as linker.dht]))


(def pair-capacity
  "Declared capacity of each link-pair medium, in elements.  The shell
   appends one request per require, and a response medium a receiver
   never drains answers `gap` (an evicted request is never re-read), so
   the bound is generous and the eviction it guards against is a host
   assembly defect, not an operating condition."
  1024)


(def content-capacity
  "Declared capacity of each content-pair medium, in elements.  One
   attempt's traffic is a handful of requests; the media persist across
   attempts and every attempt mints its cursors at their newest end, so
   an old element is never read again."
  1024)


(def attempt-budget
  "Drive rounds one link attempt may spend before it reports `:pending`.
   A local pair answers within a few rounds per content request, so the
   budget is reached only by a content source that never answers."
  64)


(def serve-steps
  "Server advances one drive round performs at most.  Each answers at
   least one waiting content request, so a local pair drains in a bound
   number of rounds."
  32)


(def serve-budget
  "Link requests one serve round answers, in request order.  It exceeds
   what one evaluation's requires can enqueue, so a round that spends it
   is answering a queue the shell did not write."
  64)


(def formats
  "The six format records a serving composition holds (sections 5 and
   8.1): the four execution formats a requesting kernel names, and the
   two the manifest flow fetches internally."
  (into {}
        (map (fn [r] [(:format r) r]))
        [linker/ast-format linker/semantic-format linker/stack-format
         linker/register-format linker/manifest-format
         linker/record-format]))


(defn- medium
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- newest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn make-pair
  "The link pair of one session (section 6.1): the request and response
   media, and the interpreter's own request cursor, minted before any
   request exists."
  []
  (let [requests (medium pair-capacity)
        responses (medium pair-capacity)]
    {:requests requests
     :responses responses
     :cursor (:dao.stream/cursor
               (stream/cursor requests stream/anchor-oldest))}))


(defn composition
  "The content side of the shell's link composition, from the host's
   creation options.  `content-store` is a `dao.jing` byte-store handle
   served in process over its own ring-buffer content pair (the
   single-process and durable-local rows of section 6.1);
   `content-client` is the linker's content-pair client map
   `{:requests w :answers r :cursor c}` on a connection whose far end
   serves content (the remote-content row), and the drive then only
   steps the linker, the server running elsewhere (section 6.4).  A
   content source is optional: with none, every link stays `:pending`
   and says so.  Supplying both is a composition defect.  `dht?` is the
   DHT link source (yin.vm.linker.dht.md 4.1): it reads content from the
   node each serve round is handed, holds nothing here, and is refused
   together with either content source.

   `principals` are the public keys (64 lowercase hex) whose signed name
   assertions the reader honors (yin.vm.linker.dht.md 7.1), and `key`
   the node's own publisher key, whose principal is declared too (6.5):
   a node resolves the names it published.  Only the public half is
   kept here."
  [{:keys [name-env content-store content-client dht? principals key]}]
  (when (and content-store content-client)
    (throw (ex-info "content-store and content-client are exclusive"
                    {:content-store content-store})))
  (when (and dht? (or content-store content-client))
    (throw (ex-info (str "a dht:<dir> index store is the link source; "
                         "content-store and content-client are refused with it")
                    {:dht? dht?})))
  {:name-env (or name-env {})
   :principals (vec (distinct (cond-> (vec principals)
                                key (conj (:public key)))))
   :content (cond
              dht? {:kind :dht}

              content-store
              {:kind :local
               :store content-store
               :requests (medium content-capacity)
               :responses (medium content-capacity)}

              content-client
              {:kind :remote, :client content-client}

              :else nil)})


(defn authority
  "The reader's authority of a link source (yin.vm.linker.dht.md 7.1):
   its declared principals and its direct name entries, as the plain
   `yin.vm.linker.dht/authority` composes them."
  [source]
  (linker.dht/authority (select-keys source [:principals :name-env])))


;; =============================================================================
;; One link attempt
;; =============================================================================

(defn- budget-spent?
  "Whether `e` is the pending signal an exhausted attempt budget throws."
  [e]
  (true? (get (ex-data e) ::budget)))


(defn- budget!
  [st]
  (when (>= (or (::round st) 0) attempt-budget)
    (throw (ex-info "Link attempt budget exhausted" {::budget true}))))


(defn- drain-server
  "Answer what is waiting on the content pair from the store, in at most
   `serve-steps` serve-step advances, and return the successor server
   cursor."
  [store requests responses cursor]
  (loop [cursor cursor
         left serve-steps]
    (if (zero? left)
      cursor
      (let [cursor' (content/serve-step store requests cursor
                                        responses serve-steps)]
        (if (= cursor cursor')
          cursor
          (recur cursor' (dec left)))))))


(defn- attempt-runtime
  "The link runtime of one attempt (section 6.4): `:state` over the
   content pair, `:drive` the composition's driver.  Both of the pair's
   cursors are minted before the attempt's first append -- `:newest`
   observes next arrival -- so nothing of an earlier attempt is read
   again and nothing of this one is skipped.  The drive counts its
   rounds and holds the server cursor on the linker state itself, so an
   attempt that exhausts `attempt-budget` throws the pending signal and
   the caller reports the link `:pending` -- nothing on the pair was
   concluded."
  [source]
  (let [content (:content source)]
    (case (:kind content)
      :local
      (let [requests (:requests content)
            responses (:responses content)
            server0 (newest requests)]
        {:state (linker/link-state
                  {:content {:requests requests
                             :answers responses
                             :cursor (newest responses)}
                   :formats formats})
         :drive (fn [st]
                  (budget! st)
                  (assoc st
                         ::round (inc (or (::round st) 0))
                         ::server (drain-server (:store content) requests
                                                responses
                                                (or (::server st)
                                                    server0))))})

      :remote
      {:state (linker/link-state {:content (:client content)
                                  :formats formats})
       :drive (fn [st]
                (budget! st)
                (assoc st ::round (inc (or (::round st) 0))))})))


(defn- attempt
  "Link one resolved name to its manifest image, or `::pending`.  The
   requester's contract and name travel as `link-manifest` expects them;
   step 5b is deferred to the receiving task."
  [source request address]
  (try
    (let [res (linker/link-manifest (attempt-runtime source)
                                    address
                                    (:yin.link/format request)
                                    {}
                                    {:contract (:yin.link/contract request)
                                     :name (:yin.link/name request)
                                     :defer-discharge true})]
      (if (linker/ok? res)
        {:status :ok
         :image {:value (:value res)}
         :manifest (:manifest res)
         :obligations (:obligations res)}
        res))
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (when-not (budget-spent? e) (throw e))
      ::pending)))


;; =============================================================================
;; The DHT link source (yin.vm.linker.dht.md 4.1)
;; =============================================================================

(defn- dht-link
  "Link the loaded closure of `address` over a local runtime on the
   node's store (`yin.vm.linker/local-runtime`), `:verifying`, discharge
   deferred.  A local store answers every content request at once, so no
   attempt budget applies; an `:absent` refusal after the load is a
   walker defect, `:yin.link.dht/closure-incomplete`."
  [node request address]
  (let [res (linker/link-manifest
              (linker/local-runtime (dht/store node) {:formats formats})
              address
              (:yin.link/format request)
              {}
              {:contract (:yin.link/contract request)
               :name (:yin.link/name request)
               :derivation :verifying
               :defer-discharge true})]
    (cond
      (linker/ok? res) {:status :ok
                        :image {:value (:value res)}
                        :manifest (:manifest res)
                        :obligations (:obligations res)}
      (= :absent (:reason res)) {:status :refused
                                 :reason :yin.link.dht/closure-incomplete
                                 :address (:address res)}
      :else res)))


(defn- dht-attempt
  "One attempt against the DHT source for the resolved `address`:
   `{:node n :body b}`, `b` `::pending` with `:waits` the manifest whose
   load the link waits on.  No record starts the load; a record of
   another kind, whatever its status, is refused
   `:dao.space.dht/kind-conflict` and left to its owner, neither
   forgotten, waited on nor linked; `:loading` waits; `:failed` is
   refused and forgotten, so a later require loads again; `:loaded`
   checks the dependency bindings, the first that is not `:ok` refusing
   before any link, then links."
  [node authority request address]
  (let [status (linker.dht/module-status node address)]
    (cond
      (nil? status) {:node (linker.dht/load-module node address)
                     :body ::pending
                     :waits address}

      (not= linker.dht/module-kind (:kind status))
      {:node node
       :body {:status :refused
              :reason :dao.space.dht/kind-conflict
              :address address
              :recorded (:kind status)}}

      (= :loading (:status status)) {:node node, :body ::pending, :waits address}

      (= :failed (:status status)) {:node (dht/forget node address)
                                    :body (linker.dht/load-refusal status)}

      :else
      (let [bindings (linker.dht/dependency-bindings node authority address)
            unbound (if (map? bindings)
                      bindings
                      (some (fn [b]
                              (when-not (= :ok (:binding b))
                                (merge {:status :refused
                                        :reason :yin.link.dht/dependency-binding}
                                       b)))
                            bindings))]
        {:node node
         :body (or unbound (dht-link node request address))}))))


(defn- answer
  "The response body for one link request under `:body`, or `::pending`
   when nothing this round can answer it: no content source, an attempt
   that spent its budget, or a DHT closure load not yet ended (its
   manifest under `:waits`).  `:node` is the shell's DHT node after the
   answer.  A request that is not by name is refused: the interpreter
   serves what the shell's own VMs send, the by-name manifest requests
   of section 7.2, and nothing else."
  [source node request]
  (let [name (:yin.link/name request)
        dht? (= :dht (get-in source [:content :kind]))]
    (cond
      (nil? (:content source)) {:node node, :body ::pending}

      (nil? name)
      {:node node
       :body {:status :refused, :reason :invalid-request,
              :defect :yin.repl/name-required}}

      dht?
      (let [declared (authority source)
            entry (linker.dht/resolve-name node declared name)]
        (if (= :ok (:status entry))
          (dht-attempt node declared request (:address entry))
          {:node node, :body entry}))

      :else
      {:node node
       :body (if-let [address (get (:name-env source) name)]
               (attempt source request address)
               {:status :refused, :reason :absent, :name name})})))


(defn- respond!
  "Append `body` under the request's id on the response stream.  Returns
   true when it landed, so the interpreter's cursor advances past the
   request only then; a refused append leaves it to be re-read."
  [pair request body]
  (let [o (stream/append! (:responses pair)
                          (assoc body :yin.link/id (:yin.link/id request)))]
    (= :dao.stream/ok (:dao.stream/outcome o))))


(defn serve
  "One serve round of the interpreter over the link pair: read every
   request `pair`'s cursor has not yet consumed and answer each in
   request order, at most `budget` of them.  The first request nothing
   can answer yet stops the round -- the requests behind it keep their
   order -- and is reported under `:pending`; its cursor stays, so a
   later round re-reads and re-attempts it.  A response the response
   medium refuses reports its request pending the same way, and the
   round reports no progress.  `dht` is the shell's DHT node, which a DHT
   source reads and advances.  Returns
   `{:pair pair :pending pending :progress? bool :dht node}`,
   `:progress?` true when at least one response was appended, so the
   shell knows the round moved something; a pending entry a DHT load
   holds names that load's manifest under `:manifest`."
  ([comp] (serve comp serve-budget))
  ([{:keys [pair source dht]} budget]
   (loop [pair pair
          node dht
          remaining budget
          pending []
          progress? false]
     (if (zero? remaining)
       {:pair pair, :pending pending, :progress? progress?, :dht node}
       (let [r (stream/next (:requests pair) (:cursor pair))]
         (if-not (= :dao.stream/ok (:dao.stream/outcome r))
           ;; blocked, or a gap: nothing more is answerable this round
           {:pair pair, :pending pending, :progress? progress?, :dht node}
           (let [request (:dao.stream/value r)
                 {:keys [node body waits]} (answer source node request)
                 answered? (and (not= ::pending body)
                                (respond! pair request body))]
             (if answered?
               (recur (assoc pair :cursor (:dao.stream/cursor r))
                      node
                      (dec remaining)
                      pending
                      true)
               {:pair pair
                :pending (conj pending
                               (cond-> {:name (:yin.link/name request)
                                        :yin.link/id (:yin.link/id request)}
                                 waits (assoc :manifest waits)))
                :progress? progress?
                :dht node}))))))))
