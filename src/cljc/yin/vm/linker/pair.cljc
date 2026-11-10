(ns yin.vm.linker.pair
  "The link-pair interpreter's generic machinery (yin.vm.linker.md
   sections 6.1, 6.5 and 9): the link pair and the content pair, one link
   attempt over a content source, the serve rounds that answer link
   requests in order, the admission state a signed-name source mints, and
   the retirement of an abandoned run.

   This namespace names no transport and no storage implementation
   (docs/design/yin.repl.dht-composition.md section 2): its content
   sources are a local `dao.jing` handle, a remote content client, a bare
   name environment, and the signed-name source whose content lives on a
   node its own composition is handed.  Serving that last one is the
   composition's (`yin.vm.linker.interpreter`), so an engine that drives
   its own links requires this namespace alone and loads no DHT.

   A serve round meets the signed-name source through `:serve-dht`, the
   answering function its composition installs for the round; with none
   installed the request is reported pending and left unread, which is
   the deferral `round-local` reports as `:deferred?`.  Every input is an
   argument or a composition value, and every outcome is plain data: no
   clock, no atom, no global.

   The shell composes the link pair per VM (`yin.repl/make-session`) and
   the content pair per content source; this namespace owns the second
   and answers what the first receives.  One serve round reads every link
   request the interpreter has not yet seen, resolves each by name
   through the composition's name environment, links the manifest's image
   over the content pair, and appends the response the requesting task
   correlates on (section 7.2, steps 5 to 6).  Step 5b is deferred: the
   obligations travel on the response and the receiving task's engine
   discharges them against its live state, so `attempt` passes
   `:defer-discharge` to `link-manifest` and discharges nothing here.

   Liveness is the composition's (section 6.3, D6): an attempt serves the
   content pair for at most `attempt-budget` drive rounds and then reports
   the link `:pending` -- nothing is appended, the request is re-read and
   re-attempted on a later round, and `abandon` is the shell's."
  (:require [clojure.string :as str]
            [dao.jing.content :as content]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.vm.linker :as linker]
            [yin.vm.linker.admission :as admission]
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


(def pending-body
  "The body a serve round reports for a request nothing this round can
   answer.  A value of this namespace, never appended: the request stays
   unread on the pair and a later round re-attempts it.  The signed-name
   source's own interpreter (`yin.vm.linker.interpreter`) reports it too,
   so both sides of the seam compare one keyword.  Named apart from a
   serve round's own `pending` accumulator, which is a vector of entries."
  ::pending)


(def formats
  "The six format records a serving composition holds (sections 5 and
   8.1): the four execution formats a requesting kernel names, and the
   two the manifest flow fetches internally."
  (into {}
        (map (fn [r] [(:format r) r]))
        [linker/ast-format linker/semantic-format
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
   signed-name link source (yin.vm.linker.dht.md 4.1): it reads content
   from the node each serve round is handed, holds nothing here, and is
   refused together with either content source.  This namespace builds
   its record and serves it not; its composition does
   (`yin.vm.linker.interpreter`).

   `principals` are the public keys (64 lowercase hex) whose signed name
   assertions the reader honors (yin.vm.linker.dht.md 7.1), and `key`
   the node's own publisher key, whose principal is declared too (6.5):
   a node resolves the names it published.  Only the public half is
   kept here.  `follow` are the principal ids (`ed25519:<hex>`) whose
   published heads the signed-name source follows
   (yin.vm.linker.dht.head.md 5.6): a name that is `:absent` while one of
   them has no installed head is pending on it, not refused.

   `derivation` is the composition's derivation policy (section 8.1) for
   the manifest links it serves from a content source: `:verifying`, the
   default, or `:trusted`, which only a composition that is its own
   publisher asks for. The response names the trust it applied.

   `admission`, optional, is the policy of the in-process admission
   interpreter (yin.vm.linker.dht.md 7.5,
   `yin.vm.linker.admission/policy`):
   `{:trusted #{principal} :verify f :decision d :decision-medium m}`,
   the key's own principal joining it as `:own`.  Without it no
   interpreter runs in process: the questions a signed-name source asks
   are answered elsewhere or not at all. `:holder` is the context
   subject, a principal id or a token understood by verify; it defaults
   to the own key's principal.

   `stage`, optional, is the link-time stage (yang.antlr.md 8.5.2)
   applied at delivery: `(stage ctx name address)` answers `{:status :ok
   :address a' ...}` (`a'` may equal `address`), `{:status :refused
   ...}` or `{:status :missing :address a}`, an address the store does not
   hold: the stage reports the first it meets and stops, and never
   fetches.  A signed-name source fetches what is reported missing (a
   staged load, `yin.vm.linker.dht/load-stage`) and runs the stage again;
   a local content store fails closed, refused `:absent`.  `ctx` is plain
   data, `{:store handle :datoms [...] :principals [...]}`: the store the
   stage may write its output into, the index datoms the source has
   loaded (none for a name-env source), and the declared principals'
   public keys.  The interpreter applies it after resolution and trust
   admission and before the attempt, links `a'` in place of `address`
   under the same derivation policy, pin checks and profile checks (a
   stage never relaxes them), and never interprets what the stage did.  A
   substituted `:ok` response names the resolved address under
   `:yin.link/canonical` and the stage's `:derivation`, if any, under
   `:yin.link/derivation`; a refusal is the stage's, with `:name` and
   `:yin.link/canonical`.  Its reasons are the composition's, as the
   admission interpreter's are.  Refused with `content-client`."
  [{:keys [name-env content-store content-client dht? principals key
           follow derivation admission stage] :as opts}]
  (when (and content-store content-client)
    (throw (ex-info "content-store and content-client are exclusive"
                    {:content-store content-store})))
  (when (and stage content-client)
    (throw (ex-info (str "a stage writes its output into a local store: "
                         "content-client is refused with it")
                    {:stage stage})))
  (when (and dht? (or content-store content-client))
    (throw (ex-info (str "a dht:<dir> index store is the link source; "
                         "content-store and content-client are refused with it")
                    {:dht? dht?})))
  {:name-env (or name-env {})
   :principals (vec (distinct (cond-> (vec principals)
                                key (conj (:public key)))))
   :holder (:yin.admit/subject (admission/context
                                 (select-keys opts [:key :holder])))
   :own (when key #{(sign/principal (:public key))})
   :follow (vec follow)
   :stage stage
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
  "Link one resolved name to its manifest image, or `pending-body`.  The
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
      pending-body)))


(defn decision
  "The shaping of a stage answer `r` for the request: `{:refused body}`
   with the stage's own `:name` kept (the request's is the default) and
   the resolved address under `:yin.link/canonical`, or `{:address a'}`
   with `:extra`, the annotation of a substituted address."
  [request address r]
  (if (= :refused (:status r))
    {:refused (-> r
                  (update :name #(or % (:yin.link/name request)))
                  (assoc :yin.link/canonical address))}
    {:address (:address r)
     :extra (when-not (= address (:address r))
              (cond-> {:yin.link/canonical address}
                (:derivation r) (assoc :yin.link/derivation
                                       (:derivation r))))}))


(defn- stage-decision
  "The link-time stage's decision for `address` over a content store
   (`composition`'s `:stage`): `decision`'s shape.  A stage over a local
   store that reports an address `:missing` has nothing to wait on and
   fails closed, refused `:absent`.  With no stage, the address as
   resolved."
  [source ctx request address]
  (if-let [stage (:stage source)]
    (let [r (stage ctx (:yin.link/name request) address)]
      (if (= :missing (:status r))
        {:refused {:status :refused
                   :reason :absent
                   :address (:address r)
                   :name (:yin.link/name request)
                   :yin.link/canonical address}}
        (decision request address r)))
    {:address address}))


(defn annotated
  "An `:ok` response `body` with the stage's `extra` annotation."
  [body extra]
  (if (and extra (= :ok (:status body))) (merge body extra) body))


(defn admission-refusal
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


(defn- answer
  "The response body for one link request under `:body`, or
   `pending-body` when nothing this round can answer it: no content
   source, an attempt that spent its budget, or -- for the signed-name
   source -- whatever its own interpreter reports (a closure load not yet
   ended, a name `:absent` while a followed principal has no installed
   head, an admission question not yet answered).  `serve-dht` is that
   interpreter's answering function, installed per round by the
   composition that owns the source; with none installed a signed-name
   request stays unread, which is the deferral `round-local` reports.
   `node` is the composition's node, which this namespace neither reads
   nor interprets: it is threaded to `serve-dht` and answered back.  A
   request that is not by name is refused: the interpreter serves what
   the shell's own VMs send, the by-name manifest requests of section
   7.2, and nothing else."
  [source node admission request serve-dht]
  (let [name (:yin.link/name request)
        dht? (= :dht (get-in source [:content :kind]))]
    (cond
      (nil? (:content source)) {:node node, :body pending-body}

      (nil? name)
      {:node node
       :body {:status :refused, :reason :invalid-request,
              :defect :yin.repl/name-required}}

      dht? (if serve-dht
             (serve-dht source node admission request)
             {:node node, :body pending-body})

      :else
      {:node node
       :body (if-let [address (get (:name-env source) name)]
               (let [d (stage-decision
                         source
                         {:store (get-in source [:content :store]),
                          :datoms [],
                          :principals (:principals source)}
                         request address)]
                 (if (:refused d)
                   (:refused d)
                   (annotated (attempt source request (:address d))
                              (:extra d))))
               {:status :refused, :reason :absent, :name name})})))


(defn- respond!
  "Append `body` under the request's id on the response stream.  Returns
   true when it landed, so the interpreter's cursor advances past the
   request only then; a refused append leaves it to be re-read."
  [pair request body]
  (let [o (stream/append! (:responses pair)
                          (assoc body :yin.link/id (:yin.link/id request)))]
    (= :dao.stream/ok (:dao.stream/outcome o))))


(defn serve-requests
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
   composition's node, which the signed-name source reads and advances,
   and `serve-dht` its interpreter's answering function (`answer`).
   Returns
   `{:pair pair :pending pending :progress? bool :dht node :admission a}`,
   `:progress?` true when at least one response or admission question
   was appended, so the shell knows the round moved something; a
   pending entry a closure load holds names that load's manifest under
   `:manifest`, one awaiting a first head names those principals under
   `:principals`, and one parked on an admission question names its id
   under `:admission`.

   `admission` is the composition's admission state
   (`yin.vm.linker.admission/make-state`, yin.vm.linker.dht.md 7.5): the
   successor state is answered under `:admission`, with the question
   and answer of every request whose response landed dropped."
  [{:keys [pair source dht admission serve-dht]} budget]
  (loop [pair pair
         node dht
         admission admission
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
                  res (answer source node admission request serve-dht)
                  {:keys [node body waits heads asking asked?]} res
                  admission (get res :admission admission)
                  answered? (and (not= pending-body body)
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
                 :admission admission}))))))))


(defn serve
  "Serve requests over the legacy composition, draining answers once."
  ([comp] (serve comp serve-budget))
  ([comp budget]
   (serve-requests
     (update comp :admission #(some-> % (admission/drain budget)))
     budget)))


(defn make
  "Mint one linker value over `source`, with optional holder `context`.
   Only signed-name sources mint an admission pair. A configured policy
   attaches its in-process interpreter; external policy attaches none."
  ([source] (make source {}))
  ([source context]
   (let [signed? (= :dht (get-in source [:content :kind]))
         adm (when signed? (admission/make-state pair-capacity))
         policy (:admission source)]
     (cond-> {:pair (make-pair) :source source}
       adm (assoc :admission adm)
       (and adm policy)
       (assoc :interpreter (admission/interpreter adm policy)
              :context (admission/context
                         (merge (select-keys source [:holder]) context)))))))


(defn reconfigure
  "Replace the admission policy without changing protocol state.
   External attachment stays external. Without an admission pair this
   is a no-op. Config is the composition's admission option map.
   Own principals and holder context remain composition-owned."
  [linker config]
  (if-not (:admission linker)
    linker
    (let [before (get-in linker [:source :admission])
          after (when (:interpreter linker)
                  (admission/policy
                    (assoc config :own (get-in linker [:source :own]))))]
      (cond-> (assoc-in linker [:source :admission] after)
        (:interpreter linker)
        (update :interpreter admission/reconfigure-interpreter
                before after)))))


(defn round-with
  "Drain once, serve requests to the first pending, then answer in
   process.  `serve-dht` is the signed-name source's answering function
   (`serve-requests`): a composition installs its own, an engine that
   drives its links itself installs none and a signed-name request stays
   unread for it.  Always return the successor, even when nothing
   reports progress."
  [linker node serve-dht budget]
  (let [before (:admission linker)
        drained (some-> before (admission/drain budget))
        matched? (not= (:answers before) (:answers drained))
        served (serve-requests (assoc linker
                                      :dht node
                                      :serve-dht serve-dht
                                      :admission drained)
                               budget)
        successor (cond-> (assoc linker :pair (:pair served))
                    before (assoc :admission (:admission served)))
        answered (when (:interpreter successor)
                   (admission/serve
                     {:interpreter (:interpreter successor)
                      :policy (get-in successor [:source :admission])
                      :context (:context successor)}
                     budget))
        successor (cond-> successor
                    answered (assoc :interpreter (:interpreter answered)))]
    {:linker successor
     :dht (:dht served)
     :pending (:pending served)
     :answered (:answered answered 0)
     :progress? (boolean (or matched? (:progress? served)
                             (:progress? answered)))}))


(defn signed-source?
  "Whether `linker`'s source is the signed-name one, whose content lives
   on a node its composition is handed rather than one it holds.  Plain
   data over the source's `:kind`; serving such a source is its
   composition's (docs/design/yin.repl.dht-composition.md section 7)."
  [linker]
  (= :dht (get-in linker [:source :content :kind])))


(defn round-local
  "One serve round over a link source this namespace serves itself: a
   local `dao.jing` content store, a remote content client, or a bare
   name environment.  It drives no node, so an engine that serves its own
   links never names one and never carries one.

   A signed-name source is its composition's to drive (`serve-links`,
   docs/design/yin.repl.dht-composition.md section 7), so this reports no
   progress and answers the linker unchanged: the requesting VM stays
   parked on its link until the composition moves it, which is the
   pending state the engine already reports.  `:deferred?` says the round
   was the composition's to serve."
  ([linker] (round-local linker serve-budget))
  ([linker budget]
   (if (signed-source? linker)
     {:linker linker, :pending [], :answered 0, :progress? false,
      :deferred? true}
     (assoc (dissoc (round-with linker nil nil budget) :dht)
            :deferred? false))))


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
   Returns the successor value with `:retired [link-id ...]`."
  [{:keys [pair admission] :as linker} run]
  (loop [pair pair
         retired []]
    (let [r (stream/next (:requests pair) (:cursor pair))
          id (:yin.link/id (:dao.stream/value r))]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r))
               (run-owned? id run))
        (recur (assoc pair :cursor (:dao.stream/cursor r)) (conj retired id))
        (cond-> (assoc linker :pair pair :retired retired)
          admission (assoc :admission (admission/forget admission retired)))))))
