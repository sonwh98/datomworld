(ns yin.vm.linker.interpreter
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
  (:require [clojure.string :as str]
            [dao.jing.content :as content]
            [dao.space.dht :as dht]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.vm.linker :as linker]
            [yin.vm.linker.admission :as admission]
            [yin.vm.linker.dht :as linker.dht]
            [yin.vm.linker.sign :as sign]))


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
  "The seven format records a serving composition holds (sections 5 and
   8.1): the five execution formats a requesting kernel names, and the
   two the manifest flow fetches internally."
  (into {}
        (map (fn [r] [(:format r) r]))
        [linker/ast-format linker/semantic-format linker/semantic-register-format
         linker/stack-format
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
   media, `capacity` elements each (`pair-capacity` by default), and the
   interpreter's own request cursor, minted before any request exists."
  ([] (make-pair pair-capacity))
  ([capacity]
   (let [requests (medium capacity)
         responses (medium capacity)]
     {:requests requests
      :responses responses
      :cursor (:dao.stream/cursor
                (stream/cursor requests stream/anchor-oldest))})))


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
   kept here.  `follow` are the principal ids (`ed25519:<hex>`) whose
   published heads the DHT source follows (yin.vm.linker.dht.head.md
   5.6): a name that is `:absent` while one of them has no installed head
   is pending on it, not refused.

   `derivation` is the composition's derivation policy (section 8.1) for
   the manifest links it serves from a content source: `:verifying`, the
   default, or `:trusted`, which only a composition that is its own
   publisher asks for. The response names the trust it applied.

   `admission`, optional, is the policy of the in-process admission
   interpreter (yin.vm.linker.dht.md 7.5,
   `yin.vm.linker.admission/policy`):
   `{:trusted #{principal} :verify f :decision d :decision-medium m}`,
   the key's own principal joining it as `:own`.  Without it no
   interpreter runs in process: the questions a DHT source asks are
   answered elsewhere or not at all."
  [{:keys [name-env content-store content-client dht? principals key
           follow derivation admission]}]
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
   :follow (vec follow)
   :derivation (or derivation :verifying)
   :admission (when admission
                (admission/policy
                  (assoc admission
                         :own (when key #{(sign/principal (:public key))}))))
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
   requester's contract and name travel as `link-manifest` expects them,
   under the composition's derivation policy; step 5b is deferred to the
   receiving task.  A lowered format's response carries the derivation
   record and the trust the policy applied."
  [source request address]
  (try
    (let [res (linker/link-manifest (attempt-runtime source)
                                    address
                                    (:yin.link/format request)
                                    {}
                                    {:contract (:yin.link/contract request)
                                     :name (:yin.link/name request)
                                     :derivation (:derivation source)
                                     :defer-discharge true})]
      (if (linker/ok? res)
        (merge {:status :ok
                :image {:value (:value res)}
                :manifest (:manifest res)
                :obligations (:obligations res)}
               (select-keys res [:derivation :trust]))
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


(defn- awaited-heads
  "The followed principals of `source` with no installed head on `node`:
   a name absent now may be asserted by the first head of one of them."
  [source node]
  (let [installed (linker.dht/installed-heads node)]
    (vec (remove #(contains? installed %) (:follow source)))))


(defn- admission-refusal
  "The link refusal an admission refusal `r` (`admission/judge`'s
   shape) of question `q` amounts to: the answer's reason and open keys
   in the link refusal shape."
  [q r]
  (merge (dissoc r :yin.admit/reason)
         {:status :refused
          :reason (:yin.admit/reason r)
          :name (:yin.admit/name q)
          :yin.admit/id (:yin.admit/id q)
          :yin.admit/decision :refuse}))


(defn- admitted
  "A request whose admission question was asked (yin.vm.linker.dht.md
   7.5): pending while unanswered, with no re-resolution; refused as the
   answer says; or, allowed, attempted against the address the question
   was bound to, never a fresh resolution.  An `:ok` response names the
   admission it rode on."
  [node declared request q a]
  (cond
    (nil? a) {:node node, :body ::pending, :asking (:yin.admit/id q)}

    (some? (admission/judge a))
    {:node node, :body (admission-refusal q (admission/judge a))}

    :else
    (let [{:keys [node body waits]}
          (dht-attempt node declared request (:yin.admit/address q))]
      {:node node
       :waits waits
       :asking (when (= ::pending body) (:yin.admit/id q))
       :body (if (= :ok (:status body))
               (assoc body
                      :yin.admit/id (:yin.admit/id q)
                      :yin.admit/decision :allow
                      :yin.admit/asserters (:yin.admit/asserters q)
                      :yin.admit/address (:yin.admit/address q))
               body)})))


(defn- dht-answer
  "The DHT branch of `answer`: a request already asked about goes by
   its question (`admitted`); otherwise its name is resolved.  A direct
   `:name-env` entry, provenance `:composition`, is the composition's
   own and is not asked; a signed name is asked: the question is
   appended and the link parks on it.  With no admission state the
   question cannot be asked, `:yin.admit/unconfigured`."
  [source node admission request]
  (let [declared (authority source)
        name (:yin.link/name request)
        q (some-> admission (admission/asked (:yin.link/id request)))]
    (if q
      (admitted node declared request q (admission/answer-to admission q))
      (let [entry (linker.dht/resolve-name node declared name)]
        (cond
          (not= :ok (:status entry))
          (if (and (= :absent (:reason entry))
                   (seq (awaited-heads source node)))
            {:node node, :body ::pending, :heads (awaited-heads source node)}
            {:node node, :body entry})

          (= :composition (:yin.link/provenance entry))
          (dht-attempt node declared request (:address entry))

          (nil? admission)
          {:node node
           :body {:status :refused
                  :reason :yin.admit/unconfigured
                  :name name}}

          :else
          (let [{:keys [id] :as asked} (admission/ask admission request entry)]
            {:node node
             :body ::pending
             :admission (:admission asked)
             :asking id
             :asked? (some? id)}))))))


(defn- answer
  "The response body for one link request under `:body`, or `::pending`
   when nothing this round can answer it: no content source, an attempt
   that spent its budget, a DHT closure load not yet ended (its
   manifest under `:waits`), a name `:absent` while a followed
   principal has no installed head (those principals under `:heads`,
   yin.vm.linker.dht.head.md 5.6), or an admission question not yet
   answered (its id under `:asking`, `:asked?` when this round appended
   it, the successor admission state under `:admission`).  `:node` is
   the shell's DHT node after the answer.  A request that is not by name
   is refused: the interpreter serves what the shell's own VMs send, the
   by-name manifest requests of section 7.2, and nothing else."
  [source node admission request]
  (let [name (:yin.link/name request)
        dht? (= :dht (get-in source [:content :kind]))]
    (cond
      (nil? (:content source)) {:node node, :body ::pending}

      (nil? name)
      {:node node
       :body {:status :refused, :reason :invalid-request,
              :defect :yin.repl/name-required}}

      dht? (dht-answer source node admission request)

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
   round reports no progress.  A content source links each manifest under
   the composition's `:derivation` policy (`composition`'s option,
   `:verifying` unless the composition asked for `:trusted`), and an
   `:ok` response of a lowered format names its `:trust`.  `dht` is the
   shell's DHT node, which a DHT source reads and advances.  Returns
   `{:pair pair :pending pending :progress? bool :dht node :admission a}`,
   `:progress?` true when at least one response or admission question
   was appended, so the shell knows the round moved something; a
   pending entry a DHT load holds names that load's manifest under
   `:manifest`, one awaiting a first head names those principals under
   `:principals`, and one parked on an admission question names its id
   under `:admission`.

   `admission` is the composition's admission state
   (`yin.vm.linker.admission/make-state`, yin.vm.linker.dht.md 7.5): the
   round first drains the answer medium (`admission/drain`, at most
   `budget` elements), and the successor state is answered under
   `:admission`, with the question and answer of every request whose
   response landed dropped."
  ([comp] (serve comp serve-budget))
  ([{:keys [pair source dht admission]} budget]
   (loop [pair pair
          node dht
          admission (some-> admission (admission/drain budget))
          remaining budget
          pending []
          progress? false]
     (let [done {:pair pair, :pending pending, :progress? progress?,
                 :dht node, :admission admission}]
       (if (zero? remaining)
         done
         (let [r (stream/next (:requests pair) (:cursor pair))]
           (if-not (= :dao.stream/ok (:dao.stream/outcome r))
             ;; blocked, or a gap: nothing more is answerable this round
             done
             (let [request (:dao.stream/value r)
                   link-id (:yin.link/id request)
                   res (answer source node admission request)
                   {:keys [node body waits heads asking asked?]} res
                   admission (get res :admission admission)
                   answered? (and (not= ::pending body)
                                  (respond! pair request body))]
               (if answered?
                 (recur (assoc pair :cursor (:dao.stream/cursor r))
                        node
                        (some-> admission (admission/settle link-id))
                        (dec remaining)
                        pending
                        true)
                 {:pair pair
                  :pending (conj pending
                                 (cond-> {:name (:yin.link/name request)
                                          :yin.link/id link-id}
                                   waits (assoc :manifest waits)
                                   heads (assoc :principals heads)
                                   asking (assoc :admission asking)))
                  :progress? (or progress? (true? asked?))
                  :dht node
                  :admission admission})))))))))


;; =============================================================================
;; Retirement of an abandoned run (yin.vm.linker.dht.md 7.5)
;; =============================================================================

(defn run-descriptor
  "The run an abandon retires: link ids `[origin k]` with `from <= k <
   until` of `:ids`, and every install child origin `origin.m` with
   `from <= m < until` of `:children`, with its descendants.  `base` is
   the VM the run began from, `parked` the VM it was given up as; an
   absent counter is zero.  Both counters mint the current value then
   increment, so each interval is half-open."
  [base parked]
  {:origin (or (:origin parked) :t0)
   :ids [(or (:id-counter base) 0) (or (:id-counter parked) 0)]
   :children [(or (:origins base) 0) (or (:origins parked) 0)]})


(defn- run-owned?
  "Whether link id `id` belongs to `run`: its own origin within `:ids`,
   or an install child origin within `:children` exactly, or one of its
   descendants -- matched on the child's name followed by \".\", never
   a raw prefix, so `:t0.10` is not a descendant of `:t0.1`."
  [id {:keys [origin ids children]}]
  (when (and (vector? id) (= 2 (count id)) (keyword? (first id))
             (integer? (second id)))
    (let [[o k] id
          [from until] ids
          [cfrom cuntil] children
          on (name o)]
      (or (and (= o origin) (<= from k) (< k until))
          (some (fn [m]
                  (let [child (str (name origin) "." m)]
                    (or (= on child)
                        (str/starts-with? on (str child ".")))))
                (range cfrom cuntil))))))


(defn retire
  "Retire the requests of an abandoned `run` (`run-descriptor`) still
   unread on `pair`: walking from the request cursor, each request the
   run owns is passed over with no response -- the engine has already
   retired its id, so a response would be late -- and the first request
   it does not own stops the walk, keeping request order.  Every
   admission question and answer of a retired request is dropped.
   Returns `{:pair p :admission a :retired [link-id ...]}`."
  [{:keys [pair admission]} run]
  (loop [pair pair
         retired []]
    (let [r (stream/next (:requests pair) (:cursor pair))
          id (:yin.link/id (:dao.stream/value r))]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r))
               (run-owned? id run))
        (recur (assoc pair :cursor (:dao.stream/cursor r)) (conj retired id))
        {:pair pair
         :admission (some-> admission (admission/forget retired))
         :retired retired}))))
