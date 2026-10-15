(ns yin.vm.semantic.safepoint-harness-test
  "Phase 6b: the safepoint harness on the :semantic lane
   (docs/design/yin.vm.semantic.continuation.md §5, §8.2).

   Each row of the §5.2 matrix is built twice under each of the four
   segment wrappers: the reference build is satisfied and run on its own
   machine; the handoff build is lifted with rows/lift-task, lowered with
   rows/lower-task into a fresh receiver over the toy channel, relifted at
   once, then satisfied through the source's streams. Result and effect
   trace equal the reference (law 1); the relift equals the lift by root
   id under rows/canonical-under (law 2); the pending's reason is the
   observed wait's (law 3); every lifted frame passes the validator
   (law 6). Row 20 runs once, outside the wrappers.

   Phase 6c adds the two de Bruijn lanes (§5.3): a world built under
   *lane* :stack or :register runs A's derived image on that kernel and
   lifts and lowers through yin.vm.semantic.layout. Law 4 (one
   root id on all three lanes under canonical-under), law 5 (six ordered
   lowering pairs, each continuing to the reference result) and the
   layout refusals close the file."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.test-slow :as slow]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.ledger :as ledger]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.semantic :as sr]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.parity-test :as b0]
            [yin.vm.semantic.analysis :as analysis]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.corpus :as corpus]
            [yin.vm.semantic.frame :as frame]
            [yin.vm.semantic.frame-test :as ft]
            [yin.vm.semantic.layout :as layout]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as s]))


;; =============================================================================
;; The composition: code by A, the source index, one machine builder
;; =============================================================================

(def source-index
  "The composition's {A #{tree-address}} (§6.1): every vector this
   harness projects is recorded beside the address of its AST."
  (atom {}))


(def code-index
  "{A v}: every vector this harness projects, for the layouts' spaces."
  (atom {}))


(def ^:dynamic *projected*
  "When bound, an atom {A v} of the vectors one world projected: the
   layouts' spaces read it instead of the whole file's code-index, so an
   alpha-equivalent A an unrelated fixture projected widens no lookup."
  nil)


(defn project
  [ast]
  (let [v (:vector (linearize/project ast))
        a (jing/segment-key v)]
    (swap! source-index update a (fnil conj #{}) (jing/segment-key [:yin/tree ast]))
    (swap! code-index assoc a v)
    (when *projected* (swap! *projected* assoc a v))
    v))


(defn scoped
  "f's result, every vector it projects recorded in a fresh *projected*."
  [f]
  (binding [*projected* (atom {})] (f)))


(defn file-wide
  "f's result, the layouts' spaces reading every vector the file ever
   projected: only the fixtures that show that contamination."
  [f]
  (binding [*projected* code-index] (f)))


(def stream-modules (module/register-stream-module (module/default-registry)))


(def ^:dynamic *lane*
  "The kernel a world is built on: :semantic (the reference
   lane), or :stack or :register, which run A's derived image and lift
   and lower through the layouts (§5.3)."
  :semantic)


(def lanes [:semantic :stack :register])


(defn- composition
  [opts]
  (merge {:make-stream tu/make-stream :capability-secret tu/secret
          :secret-source (fn [origin] (str tu/secret "/" (name origin)))
          :modules stream-modules}
         opts))


(defn- kernel-opts
  "A de Bruijn kernel's options for the same composition: the full
   primitive table, and the initial environment as :free-env."
  [opts]
  (cond-> (assoc (dissoc (composition opts) :env) :primitives vm/primitives)
    (contains? opts :env) (assoc :free-env (:env opts))))


(defn machine
  ([] (machine {}))
  ([opts]
   (case *lane*
     :semantic (sr/create-vm (composition opts))
     :stack (dvm/create-vm [] (kernel-opts opts))
     :register (rvm/create-vm (assoc {} :bodies [] :instructions []) (kernel-opts opts)))))


(defn lane-of
  [m]
  (let [f (:format (module/link-format m))]
    (cond (= dvm/format-tag f) :stack
          (= rvm/format-tag f) :register
          :else :semantic)))


(def segment-of
  "layout/segment, once per A."
  (memoize layout/segment))


(defn kernel-space
  "The layout space of the A vectors whose images de Bruijn kernel k
   holds, among those this world projected (`scoped`), or the file ever
   projected (`file-wide`). Unbound, it throws: an independent world's
   lookups never read another fixture's vectors."
  [k]
  (when-not *projected*
    (throw (ex-info "kernel-space outside scoped or file-wide" {})))
  (let [id (if (= :stack (lane-of k)) :stack-id :register-id)
        held (into #{} (map first) (:images k))]
    (layout/space (filter #(contains? held (id %)) (map segment-of (vals @*projected*))))))


(defn- kernel-load
  "Attach v's derived image and start it at its row, beside whatever the
   kernel already holds: parked records keep naming their own rows."
  [k v]
  (let [kind (lane-of k)
        sg (segment-of v)
        k (if (= :stack kind)
            (dvm/attach-image k (:stack sg) vm/stack-contract)
            (rvm/attach-image k (:register sg) vm/register-contract))
        k (assoc k :pc (layout/offset kind k sg) :frames [] :continuation [] :store-of nil
                 :halted? false :blocked? false :value nil)]
    (if (= :stack kind)
      (assoc k :stack [])
      (assoc k :registers (vec (repeat (:registers (first (:bodies (:register sg)))) nil))))))


(defn load-program
  [m ast]
  (if (= :semantic (lane-of m))
    (sr/load-vector m (project ast) code/contract)
    (kernel-load m (project ast))))


(defn lane-closure
  "Canonical closure payload c, its :segment an A, as machine m's value."
  [m c]
  (if (= :semantic (lane-of m))
    (values/closure (:owner m) (update c :segment #(get (:code-aliases m) %)))
    (layout/positional-value (layout/lower-context m (kernel-space m)) (values/closure (:owner m) c))))


(defn lift-opts
  []
  {:source-index @source-index})


(defn error
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


;; =============================================================================
;; The corpus: four segments around one parking expression
;; =============================================================================

(defn cat-of
  [p q]
  (s/app (s/v 'cat) p q))


(defn with-cat
  [ast]
  (s/let1 'cat (s/lam ['p 'q] (s/app (s/v 'conj) (s/v 'p) (s/v 'q))) ast))


(defn nest
  [d op]
  (if (zero? d) op (cat-of (s/lit [d]) (s/app (s/lam ['x] (nest (dec d) op)) (s/lit d)))))


(def segments
  "[label wrap expected-of-bare-result] (§5.2)."
  [[:bare identity identity]
   [:operand #(cat-of (s/lit [:operand]) %) (fn [r] [:operand r])]
   [:depth-3 #(nest 3 %) (fn [r] [3 [2 [1 r]]])]
   [:captured #(s/let1 'k (s/lit :captured)
                       (s/let1 'f (s/lam ['y] (cat-of (cat-of (cat-of (s/lit []) (s/v 'k)) (s/v 'y)) %))
                               (s/app (s/v 'f) (s/lit :arg))))
    (fn [r] [:captured :arg r])]])


(defn with-stream
  [body]
  (s/let1 's {:type :stream/make :buffer 4} (s/let1 'c (s/cursor-of (s/v 's)) body)))


(defn tail
  [ast]
  (assoc ast :tail? true))


(defn normalize
  [x]
  (cond
    (values/continuation? x) :continuation
    (values/closure? x) :closure
    (vector? x) (mapv normalize x)
    (seq? x) (apply list (map normalize x))
    (set? x) (into #{} (map normalize) x)
    (map? x) (into {} (map (fn [[k v]] [(normalize k) (normalize v)])) x)
    :else x))


;; =============================================================================
;; The rows of §5.2: one builder each, answering the parked machine
;; =============================================================================
;;
;; A world: :machine, :kind, :reason (blocked rows), :steps (each followed by
;; a drive) or :resume, :finish (records the effect trace), :trace, and
;; :receiver (fresh opts and a finish for the receiver's own composition).

(defn reader
  "A reader parked on (next-of c); :pre values are appended and the
   machine re-run first."
  ([wrap op] (reader wrap op [] "B" {}))
  ([wrap op pre value opts]
   (let [m0 (vm/run (load-program (machine opts) (with-stream (with-cat (wrap op)))))
         src (s/stream-of m0)
         m (reduce (fn [m x] (stream/append! src x) (vm/run m)) m0 pre)]
     {:machine m :kind :blocked :reason :next :steps [#(stream/append! src value)] :trace (atom [])})))


(defn writer
  [wrap]
  (let [trace (atom [])
        w (s/one-slot-stream "w")
        _ (stream/append! w :warmed)
        m0 (load-program (machine) (with-cat (wrap {:type :stream/put :target (s/v 'w) :val (s/lit "v")})))
        [ref m1] (engine/attach-resource m0 w)
        take! #(swap! trace conj (:dao.stream/value (stream/next w 0)))]
    {:machine (vm/run (assoc m1 :store {'w ref})) :kind :blocked :reason :put :surfaces #{:reader :writer}
     :steps [take!] :finish take! :trace trace}))


(defn own-call-pair
  [trace]
  (let [own-in (s/ring 8) own-out (s/ring 8)]
    {:opts {:call-in own-in :call-out own-out :call-out-cursor (vm/mint-oldest own-out :test)}
     :finish #(swap! trace into (tu/drain own-in))}))


(defn caller
  [wrap call-in call-out]
  (vm/run (load-program (machine {:call-in call-in :call-out call-out
                                  :call-out-cursor (vm/mint-oldest call-out :test)})
                        (with-cat (wrap (s/call-node :op/echo "hello"))))))


(defn sent-ffi
  [wrap]
  (let [trace (atom []) call-in (s/ring 8) call-out (s/ring 8)
        m (caller wrap call-in call-out)
        id (ffi/response-call-id (first (:wait-set m)))]
    {:machine m :kind :blocked :reason :ffi :call-id id
     :steps [#(stream/append! call-out (apply2/success-response id "echo!"))]
     :finish #(swap! trace into (tu/drain call-in))
     :receiver #(own-call-pair trace) :trace trace}))


(defn retained-ffi
  [wrap]
  (let [trace (atom []) call-in (s/one-slot-stream "call-in") call-out (s/ring 8)
        _ (stream/append! call-in :warmed)
        m (caller wrap call-in call-out)
        id (ffi/request-call-id (first (:wait-set m)))]
    {:machine m :kind :blocked :reason :ffi-request :surfaces #{:reader :writer}
     :steps [#(stream/next call-in 0)
             (fn []
               (swap! trace conj (:dao.stream/value (stream/next call-in 0)))
               (stream/append! call-out (apply2/success-response id "echo!")))]
     :receiver #(own-call-pair trace) :trace trace}))


(defn module-response
  "A link response as the linker answers one (yin.vm.linker/link-manifest):
   the image, the manifest naming its tree and its :yin.semantic/code
   derivation record, and that record, from the tree to the image's A."
  [body exports]
  (let [v (project body)
        tree (jing/segment-key [:yin/tree body])
        record (ledger/derive-record tree (jing/segment-key v))]
    {:status :ok :image {:value v}
     :manifest {:yin.module/name 'host.mod :yin.module/exports exports
                :yin.module/tree tree :yin.module/derivations {:yin.semantic/code (jing/segment-key record)}}
     :derivation record :obligations []}))


(def plain-body (s/def! 'f (s/lam [] (s/lit 42))))


(def blocking-body
  "host.mod's body reads a stream nothing feeds yet, then defines f. No
   binding holds the stream, so f's E does not capture it."
  (s/then (s/next-of (s/cursor-of {:type :stream/make :buffer 4})) (s/def! 'f (s/lam [] (s/lit 42)))))


(defn require-op
  [tail?]
  (let [call (s/app (s/v 'require) (s/lit 'host.mod))]
    (if tail? (s/app (s/lam ['u] (tail call)) (s/lit 0)) call)))


(defn link-receiver
  [trace]
  (fn []
    (let [rq (s/ring 64)]
      {:opts {:link-request rq :link-response (s/ring 64)} :finish #(swap! trace into (tu/drain rq))})))


(defn linking
  "A require parked in :link-response (or :link-request behind a full,
   warmed request stream when retained?)."
  [wrap tail? retained?]
  (let [trace (atom [])
        request (if retained? (s/one-slot-stream "link-request") (s/ring 64))
        response (s/ring 64)
        _ (when retained? (stream/append! request :warmed))
        m (vm/run (load-program (machine {:link-request request :link-response response})
                                (with-cat (wrap (require-op tail?)))))
        id (:link-id (first (:wait-set m)))
        answer! #(stream/append! response (assoc (module-response plain-body #{'f}) :yin.link/id id))]
    {:machine m :kind :blocked :reason (if retained? :link-request :link-response)
     :surfaces #{:reader :writer} :trace trace
     :steps (if retained? [#(stream/next request 0) answer!] [answer!])
     :finish (if retained?
               #(swap! trace conj (:dao.stream/value (stream/next request 0)))
               #(swap! trace into (tu/drain request)))
     :receiver (link-receiver trace)}))


(defn installing
  "A require parked on :install beside its blocked child."
  [wrap tail? phase]
  (let [trace (atom [])
        request (s/ring 64) response (s/ring 64)
        m0 (vm/run (load-program (machine {:link-request request :link-response response})
                                 (with-cat (wrap (require-op tail?)))))
        id (:link-id (first (:wait-set m0)))
        _ (stream/append! response (assoc (module-response blocking-body #{'f}) :yin.link/id id))
        m1 (vm/run m0)
        m (if phase (assoc-in m1 [:installs 'host.mod :phase] phase) m1)
        child (get-in m [:installs 'host.mod :vm])
        child-stream (s/stream-of child)]
    {:machine m :kind :blocked :reason :install :trace trace
     :steps [#(stream/append! child-stream :fed)]
     :finish #(swap! trace into (tu/drain request))
     :receiver (link-receiver trace)}))


(defn module-context
  "Blocked inside host.mod's exported closure, whose E names store-of host.mod."
  [wrap]
  (let [request (s/ring 64) response (s/ring 64)
        body (s/def! 'f (s/lam ['c] (s/app (s/v 'stream/next!) (s/v 'c))))
        m0 (vm/run (load-program (machine {:link-request request :link-response response})
                                 (with-stream (with-cat (wrap (s/then (s/app (s/v 'require) (s/lit 'host.mod))
                                                                      (s/app (s/v 'host.mod/f) (s/v 'c))))))))
        id (:link-id (first (:wait-set m0)))
        _ (stream/append! response (assoc (module-response body #{'f}) :yin.link/id id))
        m (vm/run m0)
        src (s/stream-of m)]
    ;; The carrier holds module stores, not registry entries: the receiver's
    ;; composition links host.mod itself, so its relift discharges host.mod/f.
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])
     :receiver (constantly {:opts {:modules (:modules m)}})}))


(defn run-beside
  [m ast]
  (vm/run (load-program m ast)))


(defn parking
  [m]
  (let [pid (:id (vm/value m))]
    {:machine m :kind :parked :pid pid :trace (atom [])
     :resume #(run-beside % {:type :vm/resume :parked-id pid :val (s/lit "back")})}))


(defn module-store-cursors
  "An explicit park whose module store holds two cursor references nothing
   else reaches, named by a module closure in the task store."
  [wrap]
  (let [ring (s/ring 4)
        ast (with-cat (wrap (s/app (s/lam ['p] {:type :vm/park}) (s/lit 1))))
        v (project ast)
        m (vm/run (load-program (machine) ast))
        cursor (fn [m]
                 (let [[ref m] (engine/attach-resource m ring) [id m] (engine/gensym m "cursor")]
                   (engine/handle-cursor m {:stream ref} id)))
        [c1 m] (cursor m)
        [c2 m] (cursor m)
        closure (first (filter #(= :closure (first %)) v))
        clo (lane-closure m {:type :closure :params (nth closure 2) :entry (nth closure 3)
                             :segment (jing/segment-key v) :env {engine/store-of-key 'host.mod}})]
    (parking (assoc m :store {'mod clo} :module-stores {'host.mod {'c c1 'd c2}}))))


(defn free-env-world
  [wrap env]
  (reader wrap (s/let1 'f (s/lam [] (s/v 'x)) (s/app (s/lam ['x] (s/app (s/v 'f) (s/next-of (s/v 'c)))) (s/lit 1)))
          [] "B" {:env env}))


(def read-op (s/next-of (s/v 'c)))


(def capture {:type :vm/current-continuation})


(def rows
  "[row bare-result build]: rows 1-19, 21, 22, 24 of §5.2. A nil result
   compares with the reference only."
  [[1 ["A" "B"] #(reader % (s/let1 'a read-op (cat-of (cat-of (s/lit []) (s/v 'a)) read-op)) ["A"] "B" {})]
   [2 nil writer]
   [3 "echo!" sent-ffi]
   [4 "echo!" retained-ffi]
   [5 [:ordinary "B"] #(reader % (cat-of (s/lit [:ordinary]) (s/app (s/v 'stream/next!) (s/v 'c))))]
   [6 "B" #(reader % (s/app (s/lam ['u] (tail (s/app (s/v 'stream/next!) (s/v 'c)))) (s/lit 0)))]
   [7 'host.mod #(linking % false true)]
   [8 'host.mod #(linking % false false)]
   [9 'host.mod #(linking % true false)]
   [10 'host.mod #(installing % false nil)]
   [11 'host.mod #(installing % false :running)]
   [12 'host.mod #(installing % true nil)]
   [13 "back" #(parking (vm/run (load-program (machine) (with-cat (% {:type :vm/park})))))]
   [14 [:continuation "B"] #(reader % (s/let1 'k capture (cat-of (cat-of (s/lit []) (s/v 'k)) read-op)))]
   [15 :done #(reader % (s/let1 'k capture {:type :if :test (s/app (s/v '=) (s/v 'k) (s/lit :second))
                                            :consequent (s/lit :done)
                                            :alternate (s/let1 'r read-op (s/app (s/v 'k) (s/lit :second)))})
                      [] "x" {})]
   [16 [:continuation :continuation "B"]
    #(reader % (s/let1 'k1 capture (s/let1 'k2 capture (cat-of (cat-of (cat-of (s/lit []) (s/v 'k1)) (s/v 'k2)) read-op))))]
   [17 "B" module-context]
   [18 "back" module-store-cursors]
   [19 42 #(let [m (vm/run (load-program (machine) (with-cat (% (s/lit 42)))))]
             {:machine m :kind :halted :trace (atom [])})]
   [21 99 #(free-env-world % {'x 99})]
   [22 [nil "B"] #(reader % (s/let1 'pair (s/lam ['p 'q] (cat-of (cat-of (s/lit []) (s/v 'p)) (s/v 'q)))
                                    (s/app (s/v 'pair) (s/lit nil) read-op)))]
   [24 "echo!" sent-ffi]])


(defn tail-effect-halt
  "Row 20, outside the wrappers: a setup batch to halt, then the root tail
   call (stream/next! c) with an empty K."
  []
  (let [setup (vm/run (load-program (machine) (s/then (s/def! 's {:type :stream/make :buffer 4})
                                                      (s/def! 'c (s/cursor-of (s/v 's))))))
        m (run-beside setup (tail (s/app (s/v 'stream/next!) (s/v 'c))))
        src (s/stream-of m)]
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])}))


;; =============================================================================
;; The two runs
;; =============================================================================

(defn drive-local
  [m]
  (loop [m (vm/run m) n 12]
    (if (or (not (vm/blocked? m)) (zero? n)) m (recur (vm/run m) (dec n)))))


(defn drive-peers
  [m peers]
  (loop [m (vm/run m) n 12]
    (if (or (not (vm/blocked? m)) (zero? n))
      m
      (do (run! s/serve-mirror! peers) (recur (vm/run m) (dec n))))))


(defn satisfy
  [world m drive]
  (if-let [resume (:resume world)]
    (resume m)
    (reduce (fn [m step] (step) (drive m)) m (:steps world))))


(defn reference
  [world]
  (let [done (satisfy world (:machine world) drive-local)]
    (when-let [f (:finish world)] (f))
    {:halted (vm/halted? done) :value (normalize (vm/value done)) :trace @(:trace world)}))


(defn served
  "A served table over a fresh toy: [serve! correspondence-fn]."
  [surfaces]
  (let [t (s/toy) peer (s/served-peer t)]
    {:toy t :peer peer :serve! (s/server peer (:channel t) surfaces)}))


(defn recording-attacher
  "The receiver's attach!, counting attaches and recording each
   reflection's source identity."
  [t]
  (let [base (s/remote-attacher t) origin (atom {}) n (atom 0)]
    {:attach! (fn [d]
                (swap! n inc)
                (let [r (base d)]
                  (when-let [h (:dao.stream/handle r)] (swap! origin assoc h (:dao.stream/identity d)))
                  r))
     :origin origin :count n}))


(defn handed-off
  "Lift, lower into a fresh receiver, relift at once, then satisfy through
   the source's streams."
  [world]
  (let [surfaces (or (:surfaces world) #{:reader})
        {:keys [toy peer serve!]} (served surfaces)
        wire (rows/lift-task (:machine world) serve! (lift-opts))
        corr (into {} (map (fn [[id _]] [id id])) @(:table peer))
        receiver ((or (:receiver world) (constantly nil)))
        {:keys [attach! origin] n :count} (recording-attacher toy)
        recv (rows/lower-task (machine (merge {:attach-stream attach!} (:opts receiver))) wire attach!)
        attaches @n
        again (served surfaces)
        relift (rows/lift-task recv (:serve! again) (lift-opts))
        corr2 (into {} (map (fn [[id e]] [id (get @origin (:handle e))])) @(:table (:peer again)))
        done (satisfy world recv #(drive-peers % [peer]))
        _ (s/serve-mirror! peer)]
    (when-let [f (:finish world)] (f))
    (when-let [f (:finish receiver)] (f))
    {:wire wire :recv recv :relift relift :corr corr :corr2 corr2 :attaches attaches
     :halted (vm/halted? done) :value (normalize (vm/value done)) :trace @(:trace world)}))


(defn root-task
  [wire]
  (rows/decode wire))


(defn stream-identities
  "The distinct stream identities of the whole carrier, install children
   included: one row table serves the tree."
  [wire]
  (into #{} (keep (fn [r] (when (= :stream (second r)) (nth r 2)))) (:yin.k/rows wire)))


(defn rows-in
  "Every row node the decoded tree x reaches, once per path."
  [x]
  (cond
    (and (map? x) (contains? x :kind) (contains? x :slots)) (cons x (rows-in (:slots x)))
    (sequential? x) (mapcat rows-in x)
    :else nil))


(defn stream-occurrences
  "How many times the decoded tree reaches a :stream row."
  [x]
  (count (filter #(= :stream (:kind %)) (rows-in x))))


(defn slot
  [task i]
  (nth (:slots task) i))


(defn first-pending
  [task]
  (second (:slots (first (slot task 13)))))


(defn laws
  "Laws 1, 2, 3 and 6 for one world; answers the run."
  [world expected]
  (let [{:keys [wire relift corr corr2 attaches] :as got} (handed-off world)
        task (root-task wire)]
    (is (= (:kind world) (slot task 2)))
    (is (= (count (stream-identities wire)) attaches) "one attach per distinct stream identity")
    (when (= :blocked (:kind world))
      (is (= (:reason world) (first (:slots (first-pending task)))) "law 3: the observed wait's reason"))
    (is (nil? (rows/validate-frames task)) "law 6: every lifted frame validates")
    (is (= (:yin.k/root (rows/canonical-under wire corr)) (:yin.k/root (rows/canonical-under relift corr2)))
        "law 2: lift(lower(S)) = S by root id")
    (is (:halted expected) "the reference run completes")
    (is (= (select-keys expected [:halted :value :trace]) (select-keys got [:halted :value :trace]))
        "law 1: result and effect trace equal the reference")
    got))


(defn matrix-cases
  "22 wrapped rows x 4 segments, plus row 20 once: [row segment expected build]."
  []
  (conj (into [] (mapcat (fn [[n r build]]
                           (mapv (fn [[label wrap expected-of]]
                                   ;; nest's own parameter is x: under :depth-3,
                                   ;; row 21's (fn [] x) closes over the wrapper's
                                   ;; x = 1, not the free x = 99 (§5.2 assumes 99)
                                   (let [r (if (and (= 21 n) (= :depth-3 label)) 1 r)]
                                     [n label (when (some? r) (expected-of r)) #(build wrap)]))
                                 segments)))
              rows)
        [20 :unwrapped "B" tail-effect-halt]))


(defn bare
  [n]
  (let [[_ _ build] (first (filter #(= n (first %)) rows))]
    (build identity)))


;; =============================================================================
;; Acceptance 1: 89 runs
;; =============================================================================

(deftest ^:slow every-row-every-segment-matches-the-reference
  (slow/guard
    "every-row-every-segment-matches-the-reference"
    (fn []
      (let [cases (matrix-cases) runs (atom 0)]
        (is (= 89 (count cases)) "22 wrapped rows x 4 segments + row 20")
        (doseq [[n label expected build] cases]
          (testing (str "row " n " " (name label))
            (let [ref (reference (build))]
              (when (some? expected)
                (is (= expected (:value ref)) "the reference answers §5.2's result"))
              (laws (build) ref)
              (swap! runs inc))))
        (is (= 89 @runs))))))


(deftest bare-rows-match-the-reference
  ;; The quick-tier slice of acceptance 1: every row once, bare.
  (let [counted (atom [0 0])]
    (doseq [[n r build] rows]
      (testing (str "row " n)
        (let [ref (reference (build identity))
              {:keys [wire attaches]} (laws (build identity) ref)]
          (when (some? r) (is (= r (:value ref))))
          (swap! counted (fn [[a i]] [(+ a attaches) (+ i (count (stream-identities wire)))])))))
    ;; outside laws, so deleting its count assertion leaves one standing
    (is (pos? (first @counted)) "the corpus attaches streams")
    (is (apply = @counted) "attaches = distinct stream identities over the corpus")))


;; =============================================================================
;; Acceptance 2-6
;; =============================================================================

(defn wait-state
  [wire]
  (first (:slots (first (slot (root-task wire) 13)))))


(deftest tail-states-carry-no-window
  (doseq [[label world] [[6 (bare 6)] [9 (bare 9)] [12 (bare 12)] [20 (tail-effect-halt)]]]
    (testing (str "row " label)
      (let [{:keys [serve!]} (served #{:reader :writer})
            wire (rows/lift-task (:machine world) serve! (lift-opts))
            state (wait-state wire)
            row (first (filter #(= :tail (second %)) (:yin.k/rows wire)))]
        (is (= :tail (:kind state)))
        (is (= 3 (count (:slots state))) "segment, site, K: no env, window or pc")
        (is (= 5 (count row)) "[id :tail A site k]")
        (is (not-any? #(= :window (second %))
                      (filter #(= (first %) (second (:slots state))) (:yin.k/rows wire))))
        (let [entry (first (:wait-set (:machine world)))]
          (is (= {:deliver :return} (:deliver entry)))
          (is (not-any? #(contains? entry %) [:window :env :pc])))))))


(deftest tail-states-lower-to-through-k-delivery
  (doseq [n [6 9 12]]
    (testing (str "row " n)
      (let [ref (reference (bare n))
            {:keys [recv] :as got} (laws (bare n) ref)
            entry (first (:wait-set recv))]
        (is (= {:deliver :return} (:deliver entry)))
        (is (contains? entry :site))
        (is (not-any? #(contains? entry %) [:window :env :pc]) "the lowered tail carries no window")
        (is (= (:value ref) (:value got))))))
  (let [ref (reference (tail-effect-halt))
        {:keys [recv] :as got} (laws (tail-effect-halt) ref)]
    (is (= [] (:k (first (:wait-set recv)))) "row 20: an empty K")
    (is (= "B" (:value got)) "a tail effect with no frame to pop halts with the value")))


(deftest install-rows-carry-the-child
  (doseq [[n phase] [[10 :parked] [11 :running] [12 :parked]]]
    (testing (str "row " n)
      (let [world (bare n)
            {:keys [serve! toy]} (served #{:reader})
            wire (rows/lift-task (:machine world) serve! (lift-opts))
            [install] (slot (root-task wire) 12)
            [mn carried-phase _ _ child] (:slots install)
            {:keys [attach!] n :count} (recording-attacher toy)
            recv (rows/lower-task (machine {:attach-stream attach!}) wire attach!)
            inst (get-in recv [:installs 'host.mod])
            lowered (:vm inst)]
        (is (= (count (stream-identities wire)) @n) "one attach per distinct stream identity of the tree")
        (is (= 'host.mod mn))
        (is (= phase carried-phase) "the child's phase as the source held it")
        (is (= :blocked (slot child 2)) "the child's kind")
        (is (= :child (slot child 3)))
        (is (= [:install 'host.mod] (:slots (first-pending (root-task wire)))))
        (is (= phase (:phase inst)))
        (is (nil? (:control lowered)) "no initialization on lower: nothing runs")
        (is (= [:next] (mapv :reason (:wait-set lowered))) "the child waits where it was lifted")
        (is (vm/blocked? lowered))
        (is (= (count (slot child 11)) (count (:code-aliases lowered))) "the child's code by A")))))


(deftest captures
  (doseq [n [14 15 16]]
    (testing (str "row " n)
      (let [ref (reference (bare n))]
        (laws (bare n) ref))))
  (let [world (bare 15)
        {:keys [recv] :as got} (laws world (reference (bare 15)))
        entry (first (:wait-set recv))
        k (get-in entry [:env 'k])
        p (values/payload k)]
    (is (values/continuation? k) "the capture travels in the closure's E")
    (is (not (contains? (:window p) (get-in p [:deliver :rd]))) "nothing is delivered into the captured rd on lower")
    (is (= :done (:value got)) "the invocation after lower delivers once and re-enters")))


(deftest free-env-shadowing
  (let [ref (reference (bare 21))
        got (laws (bare 21) ref)]
    (is (= 99 (:value got)) "the lowered f resolves x through the carried free environment"))
  (testing "the explicit :free-env overrides the kernel's retained field"
    (let [world (bare 21)
          m (assoc (:machine world) :free-env {'x 5})
          {:keys [serve! toy]} (served #{:reader})]
      (is (= :inconsistent-state (:yin.k/kind (error #(rows/lift-task m serve! (lift-opts)))))
          "a free environment no E agrees with is refused")
      (let [wire (rows/lift-task m serve! (assoc (lift-opts) :free-env {'x 99}))
            free (slot (root-task wire) 5)
            {:keys [attach!]} (recording-attacher toy)
            recv (rows/lower-task (machine {:attach-stream attach!}) wire attach!)]
        (is (= ['x [:lit 99]] (:slots free)))
        (is (= {'x 99} (:free-env recv)))
        (is (= {'x 99} (:env recv))))))
  (testing "neither the argument nor the kernel field: :missing-free-env"
    (let [m (assoc (:machine (bare 21)) :free-env nil)
          d (error #(rows/lift-task m (:serve! (served #{:reader})) (lift-opts)))]
      (is (= [:yin.k/non-portable :missing-free-env] [(:yin.k/status d) (:yin.k/kind d)])))))


(deftest module-store-snapshot
  (let [{:keys [wire recv]} (laws (bare 18) (reference (bare 18)))
        task (root-task wire)
        {:strs [c d]} (into {} (map (fn [[k v]] [(name k) v])) (get-in recv [:module-stores 'host.mod]))]
    (is (= 1 (count (slot task 7))) "one :module-store row")
    (is (= 2 (quot (count (:slots (slot task 9))) 2)) "two cursor cells")
    (is (= :cursor-ref (:type c) (:type d)))
    (is (not= (:id c) (:id d)) "the two cursor cells stay two")
    (is (= :closure (normalize (get-in recv [:store 'mod]))))))


(deftest synthetic-tail-bookkeeping
  ;; Row 23: a hand-built parked tail; no primitive returns an FFI effect
  ;; through a tail :call today, so it is judged by frame/defect only.
  (let [m (:machine (bare 6))
        entry (first (:wait-set m))
        f (dissoc (ft/canonical m entry false) :yin.k/pending)
        code-map (ft/machine-code m)
        v (get code-map (:yin.k/segment f))
        non-tail (first (keep-indexed (fn [pc t] (when (and (= :call (first t)) (false? (nth t 4))) pc)) v))
        ctx {:free-env {} :ffi-call-ids #{:call-7} :parked-key :call-7}]
    (is (= :tail (:yin.k/state f)))
    (is (nil? (frame/defect f code-map :parked ctx)) "a tail record under a live FFI call id")
    (is (= :parked-site (:yin.k/kind (frame/defect f code-map :parked (assoc ctx :parked-key :call-8))))
        "the same record under a key absent from :ffi-call-ids")
    (is (some? non-tail))
    (is (= :site (:yin.k/kind (frame/defect (assoc f :yin.k/site non-tail) code-map :parked ctx)))
        "T1 runs before P4")))


;; =============================================================================
;; Acceptance 7: refusals, before any attachment
;; =============================================================================

(defn update-at
  [x path f]
  (if (empty? path)
    (f x)
    (if (map? x)
      (update-in x [:slots (first path)] update-at (rest path) f)
      (update x (first path) update-at (rest path) f))))


(defn replace-nodes
  "Every row node matching pred, anywhere under x, replaced by (f node)."
  [x pred f]
  (cond
    (and (map? x) (contains? x :kind) (contains? x :slots))
    (if (pred x) (f x) (update x :slots (fn [ss] (mapv #(replace-nodes % pred f) ss))))
    (vector? x) (mapv #(replace-nodes % pred f) x)
    :else x))


(defn replace-row
  "The wire with row id's body replaced and every ancestor rehashed,
   without canonicalizing slots: a tamper below the abstract emitter."
  [wire id body]
  (let [index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
        memo (atom {})]
    (letfn [(rewrite
              [x]
              (cond
                (contains? index x)
                (or (get @memo x)
                    (let [b (mapv rewrite (if (= x id) body (subvec (get index x) 1)))
                          nid (jing/segment-key b)]
                      (swap! memo assoc x nid)
                      nid))
                (vector? x) (mapv rewrite x)
                :else x))]
      (let [root (rewrite (:yin.k/root wire))
            rs (mapv (fn [[old nid]]
                       (into [nid] (mapv rewrite (if (= old id) body (subvec (get index old) 1)))))
                     @memo)]
        (assoc wire :yin.k/root root :yin.k/rows rs)))))


(defn pairs-of
  [n]
  (mapv vec (partition 2 (:slots n))))


(defn rebuild
  [n f]
  (rows/node (:kind n) (mapcat identity (f (pairs-of n)))))


(defn lowered
  "Lower wire into a fresh receiver: [outcome attaches]."
  [toy wire]
  (let [{:keys [attach!] n :count} (recording-attacher toy)
        r (try (rows/lower-task (machine {:attach-stream attach!}) wire attach!)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e)))]
    [r @n]))


(defn free-register
  "A register of the act's body that is neither in its window nor its rd."
  [task act]
  (let [[a pc _ w _ rd] (:slots act)
        v (get (slot task 11) a)
        bodies (code/parse v)
        k (:registers (nth bodies (code/body-index bodies pc)))
        taken (into #{rd} (map first) (pairs-of w))]
    (first (remove taken (range k)))))


(def wait-act [13 0 0])


(defn tamper-cases
  []
  (let [act-of (fn [task] (get-in task [:slots 13 0 :slots 0]))]
    [["window with an extra live key" 1 :window-extra
      (fn [task] (update-at task (conj wait-act 3) #(rebuild % (fn [ps] (conj ps [(free-register task (act-of task)) [:lit :x]])))))]
     ["a live key removed" 1 :window-missing
      (fn [task] (update-at task (conj wait-act 3) #(rebuild % (fn [ps] (vec (rest ps))))))]
     ["rd added" 1 :window-self
      (fn [task]
        (update-at task (conj wait-act 3)
                   #(rebuild % (fn [ps] (conj ps [(nth (:slots (act-of task)) 5) [:lit :x]])))))]
     ["rd replaced by another register" 1 :destination
      (fn [task] (update-at task (conj wait-act 5) inc))]
     ["a reason the site does not admit" 1 :reason-mismatch
      (fn [task] (update-at task [13 0 1] (constantly (rows/node :pending [:install 'bogus]))))]
     ["the [:lit nil] of row 22 dropped" 22 :window-missing
      (fn [task] (update-at task (conj wait-act 3) #(rebuild % (fn [ps] (vec (remove (fn [[_ v]] (= [:lit nil] v)) ps))))))]
     ["a :reified act whose pc-1 is not a capture" 14 :captured-site
      (fn [task]
        (let [act (act-of task)]
          (replace-nodes task #(= :reified (:kind %)) (constantly (rows/node :reified [act])))))]
     ["a parked act whose pc-1 is not :park" 1 :parked-site
      (fn [task] (update-at task [8] (constantly (rows/node :parked-table [:bogus (act-of task)]))))]
     ["the root free-env row given a reserved key" 1 :reserved-name
      (fn [task] (update-at task [5] #(rebuild % (fn [ps] (conj ps ['yin/def [:lit 1]])))))]]))


(defn return-site-case
  "A return frame's pc moved after a tail call (K1); falls back to a pc
   after any non-call when no tail call has a successor in its body."
  [task]
  (let [kpath [13 0 0 2 0]
        kf (get-in task [:slots 13 0 :slots 0 :slots 2 :slots 0])
        [a] (:slots kf)
        v (get (slot task 11) a)
        bodies (code/parse v)
        same-body? (fn [p] (= (code/body-index bodies (dec p)) (code/body-index bodies p)))
        tail-call? (fn [t] (and (= :call (first t)) (true? (nth t 4))))
        after-tail (first (filter #(and (tail-call? (nth v (dec %))) (same-body? %)) (range 1 (count v))))
        after-other (first (filter #(and (not= :call (first (nth v (dec %)))) (same-body? %)) (range 1 (count v))))]
    [(if after-tail :after-tail-call :after-non-call)
     (update-at task (conj kpath 1) (constantly (or after-tail after-other)))]))


(defn lift-bare
  [n]
  (let [world (bare n)
        {:keys [serve! toy]} (served #{:reader :writer})]
    [toy (rows/lift-task (:machine world) serve! (lift-opts))]))


(deftest refusals
  (doseq [[label n kind tamper] (tamper-cases)]
    (testing label
      (let [[toy wire] (lift-bare n)
            [r attaches] (lowered toy (rows/emit-numbered (tamper (root-task wire)) {}))]
        (is (= [:yin.k/undecodable kind] [(:yin.k/status r) (:yin.k/kind r)]) (pr-str r))
        (is (zero? attaches) "refused before any attachment"))))
  (testing "a segment with no tree association (§6.1): unsatisfied, before any attachment"
    (let [[toy wire] (lift-bare 1)
          task (update-at (root-task wire) [10] #(assoc % :yin.k/trees {}))
          [r attaches] (lowered toy (rows/emit-numbered task {}))]
      (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
      (is (= :incomplete (:yin.k/discovery r)))
      (is (seq (get-in r [:yin.k/missing :yin.k/trees])))
      (is (zero? attaches)))
    (let [world (bare 1)
          d (error #(rows/lift-task (:machine world) (:serve! (served #{:reader})) {:source-index {}}))]
      (is (= [:yin.k/unsatisfied :incomplete] [(:yin.k/status d) (:yin.k/discovery d)]) "the lift refuses it too")))
  (testing "a return frame's pc moved after a tail call"
    (let [[toy wire] (lift-bare 6)
          [how task] (return-site-case (root-task wire))
          [r attaches] (lowered toy (rows/emit-numbered task {}))]
      (is (= :after-tail-call how))
      (is (= [:yin.k/undecodable :return-site] [(:yin.k/status r) (:yin.k/kind r)]) (pr-str r))
      (is (zero? attaches))))
  (testing "a :tail row given a fourth slot is refused at structure, before frames"
    (let [[toy wire] (lift-bare 6)
          [id & body] (first (filter #(= :tail (second %)) (:yin.k/rows wire)))
          [r attaches] (lowered toy (replace-row wire id (conj (vec body) 0)))]
      (is (= :yin.k/undecodable (:yin.k/status r)))
      (is (= :row-shape (:yin.k/kind r)) "the codec's structure step (2) runs before the frames step (7)")
      (is (zero? attaches))))
  (testing "E2 over row 21 with the free environment {x 99 y 7}"
    (let [world (free-env-world identity {'x 99 'y 7})
          {:keys [serve! toy]} (served #{:reader})
          wire (rows/lift-task (:machine world) serve! (lift-opts))
          task (root-task wire)
          env-path (conj wait-act 2 0)
          edit (fn [f] (rows/emit-numbered (update-at task env-path #(rebuild % (fn [ps] (f (into {} ps))))) {}))]
      (is (= [:lit 1] (second (first (filter #(= 'x (first %)) (pairs-of (get-in task [:slots 13 0 :slots 0 :slots 2 :slots 0]))))))
          "the lexical x is 1 at the read")
      (doseq [[label f] [["the unshadowed y changed" #(assoc % 'y [:lit 8])]
                         ["the lexical x removed" #(dissoc % 'x)]]]
        (testing label
          (let [[r attaches] (lowered toy (edit f))]
            (is (= [:yin.k/undecodable :free-env-mismatch] [(:yin.k/status r) (:yin.k/kind r)]) (pr-str r))
            (is (zero? attaches)))))
      (testing "the lexical x changed trips nothing"
        (let [[r] (lowered toy (edit #(assoc % 'x [:lit 98])))]
          (is (vm/blocked? r) (pr-str r))))))
  (testing "lift refusals, before any stream is served"
    (let [counting (fn []
                     (let [n (atom 0) {:keys [serve!]} (served #{:reader})]
                       [(fn [h] (swap! n inc) (serve! h)) n]))
          stepped (vm/step (load-program (machine) (s/lit 42)))
          m0 (load-program (machine) (s/app (s/v 'stream/poll!) (s/v 'c)))
          [sref m1] (engine/attach-resource m0 (s/ring 8))
          [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
          held (vm/run (assoc m2 :store {'c cref} :yin.k/gate :running))]
      (is (= [:observe] (mapv :reason (:wait-set held))))
      (doseq [[label m status kind] [["not at a safepoint" stepped :yin.k/not-at-safepoint nil]
                                     ["a held :observe" held :yin.k/non-portable :reason-mismatch]]]
        (testing label
          (let [[serve! n] (counting)
                d (error #(rows/lift-task m serve! (lift-opts)))]
            (is (= status (:yin.k/status d)) (pr-str d))
            (is (= kind (:yin.k/kind d)))
            (is (zero? @n))))))))


(deftest first-defect-order
  (let [[toy wire] (lift-bare 18)
        task (root-task wire)
        state-path [8 1]
        state (get-in task [:slots 8 :slots 1])
        ;; the park's window: rd added
        tampered (update-at task (conj state-path 3)
                            #(rebuild % (fn [ps] (conj ps [(nth (:slots state) 5) [:lit :x]]))))
        census (mapv first (pairs-of (slot task 9)))
        swapped (zipmap census (reverse census))]
    (is (= 2 (count census)))
    (is (= :window-self (:yin.k/kind (first (lowered toy (rows/emit-numbered tampered {}))))))
    (let [[r attaches] (lowered toy (rows/emit-numbered tampered swapped))]
      (is (= [:yin.k/undecodable :noncanonical-cursor-cells] [(:yin.k/status r) (:yin.k/kind r)]) (pr-str r))
      (is (zero? attaches)))))


;; =============================================================================
;; Acceptance 8: the two ported tests
;; =============================================================================

(defn parked-reader
  []
  (let [m0 (vm/run (load-program (machine) (with-stream (s/then read-op read-op))))
        src (s/stream-of m0)]
    (stream/append! src "A")
    [(vm/run m0) src]))


(deftest a-nonempty-ready-queue-is-not-quiescent
  (let [[parked src] (parked-reader)
        _ (stream/append! src "B")
        woken (engine/check-wait-set parked)
        n (atom 0)
        {:keys [serve!]} (served #{:reader})
        d (error #(rows/lift-task woken (fn [h] (swap! n inc) (serve! h)) (lift-opts)))]
    (is (seq (:ready-queue woken)) "the sweep queued the woken reader")
    (is (= :yin.k/not-quiescent (:yin.k/status d)) (pr-str d))
    (is (zero? @n) "no stream was served")))


(defn two-callers
  []
  (let [call-in (s/ring 8) call-out (s/ring 8)
        m (vm/run (load-program (machine {:call-in call-in :call-out call-out
                                          :call-out-cursor (vm/mint-oldest call-out :test)})
                                (s/then (s/call-node :op/echo "hello") (s/call-node :op/echo "world"))))
        e1 (first (:wait-set m))]
    [(assoc m :wait-set [e1 (assoc e1 :call-id :parked-9)]) call-out (:call-id e1)]))


(defn answer-both!
  [call-out first-id]
  (stream/append! call-out (apply2/success-response :parked-9 "second"))
  (stream/append! call-out (apply2/success-response first-id "first")))


(defn sweep
  [m peers]
  (loop [m (engine/check-wait-set m) n 12]
    (if (or (zero? n) (empty? (:wait-set m)))
      m
      (do (run! s/serve-mirror! peers) (recur (engine/check-wait-set m) (dec n))))))


(deftest a-response-cursor-shared-by-two-waiters-crosses-as-one-cursor-cell
  (let [[ref-m ref-out id] (two-callers)
        _ (answer-both! ref-out id)
        expected (mapv :value (:ready-queue (sweep ref-m [])))
        [m call-out id] (two-callers)
        {:keys [serve! toy peer]} (served #{:reader})
        wire (rows/lift-task m serve! (lift-opts))
        task (root-task wire)
        pendings (mapv #(second (:slots %)) (slot task 13))
        own-out (s/ring 8)
        {:keys [attach!] n :count} (recording-attacher toy)
        recv (rows/lower-task (machine {:attach-stream attach! :call-in (s/ring 8) :call-out own-out
                                        :call-out-cursor (vm/mint-oldest own-out :test)})
                              wire attach!)
        [w1 w2] (:wait-set recv)
        _ (answer-both! call-out id)
        swept (sweep recv [peer])]
    (is (= [:ffi :ffi] (mapv #(first (:slots %)) pendings)))
    (is (= [id :parked-9] (mapv #(nth (:slots %) 2) pendings)))
    (is (= 1 (quot (count (:slots (slot task 9))) 2)) "the two waiters name one cursor cell")
    (is (= (nth (:slots (first pendings)) 1) (nth (:slots (second pendings)) 1)))
    (is (= (:cursor-ref w1) (:cursor-ref w2)) "the lower gives both waiters one fresh cursor entry")
    (is (= (count (stream-identities wire)) @n) "one attach per distinct stream identity")
    (is (= [(apply2/success-response :parked-9 "second") (apply2/success-response id "first")] expected))
    (is (empty? (:wait-set swept)))
    (is (= expected (mapv :value (:ready-queue swept)))
        "the receiver wakes the same waiters with the same responses")))


(deftest act-ffi-bookkeeping
  ;; Row 24: row 3's bookkeeping record travels in the parked table under
  ;; its call id, and the validator accepts it in context :parked.
  (let [world (bare 24)
        {:keys [serve!]} (served #{:reader})
        task (root-task (rows/lift-task (:machine world) serve! (lift-opts)))
        [p state] (:slots (slot task 8))]
    (is (= (:call-id world) p))
    (is (= :act (:kind state)))
    (is (= [:ffi (:call-id world)] [(first (:slots (first-pending task))) (nth (:slots (first-pending task)) 2)]))
    (is (nil? (rows/validate-frames task)))))


(deftest a-stream-shared-across-the-tree-attaches-once
  ;; Row 10 with the link-request stream named from the root's store and
  ;; from the install child's: one identity, reached by two tasks.
  (let [m (:machine (bare 10))
        child (get-in m [:installs 'host.mod :vm])
        ref-in (fn [x] (engine/issue-ref x :stream-ref module/link-request-resource))
        m (-> m
              (update :store assoc 'rq (ref-in m))
              (assoc-in [:installs 'host.mod :vm] (update child :store assoc 'rq (ref-in child))))
        {:keys [serve! toy]} (served #{:reader :writer})
        wire (rows/lift-task m serve! (lift-opts))
        task (root-task wire)
        {:keys [attach!] n :count} (recording-attacher toy)
        recv (rows/lower-task (machine {:attach-stream attach!}) wire attach!)
        lowered-child (get-in recv [:installs 'host.mod :vm])
        handle (fn [x] (get (:resources x) (:id (get-in x [:store 'rq]))))]
    (is (< (count (stream-identities wire)) (stream-occurrences task)) "the tree reaches one identity twice")
    (is (= (count (stream-identities wire)) @n) "one attach per distinct stream identity")
    (is (some? (handle recv)))
    (is (identical? (handle recv) (handle lowered-child)) "both tasks hold the one attached stream")))


;; =============================================================================
;; Carrier defects the restore would meet: refused before any attachment
;; =============================================================================

(defn refused-before-attach
  "Lower row n's carrier after (edit task): [status kind data]; asserts
   no stream was attached."
  [n edit]
  (let [[toy wire] (lift-bare n)
        [r attaches] (lowered toy (rows/emit-numbered (edit (root-task wire)) {}))]
    (is (zero? attaches) (str "refused before any attachment: "
                              (pr-str (select-keys r [:yin.k/status :yin.k/kind :rule :reason :defect]))))
    [(:yin.k/status r) (:yin.k/kind r) r]))


(def install-path [12 0])


(defn with-store-key
  [k]
  (fn [s] (rebuild s (fn [ps] (conj ps [k [:lit 42]])))))


(defn closures
  [f]
  (fn [task] (replace-nodes task #(= :closure (:kind %)) f)))


(deftest carrier-defects-refuse-before-attachment
  (testing "a reserved root store key"
    (let [[status kind r] (refused-before-attach 1 #(update-at % [6] (with-store-key 'yin/def)))]
      (is (= [:yin.k/undecodable :reserved-name :store-key] [status kind (:yin.k/role r)]))))
  (testing "a reserved module store key"
    (let [[status kind r] (refused-before-attach 18 #(update-at % [7 0 1] (with-store-key 'yin/def)))]
      (is (= [:yin.k/undecodable :reserved-name :store-key] [status kind (:yin.k/role r)]))))
  (testing "a closure whose entry names no closure of its code"
    (is (= [:yin.k/undecodable :closure-origin]
           (take 2 (refused-before-attach 1 (closures #(assoc-in % [:slots 1] 999999999)))))))
  (testing "a closure whose binders are not its tuple's"
    (is (= [:yin.k/undecodable :closure-binders]
           (take 2 (refused-before-attach 1 (closures #(update-in % [:slots 2] (fn [ps] (conj (vec (butlast ps)) 'zz)))))))))
  (testing "an install whose retained response is {}"
    (is (= [:yin.k/undecodable :install-response]
           (take 2 (refused-before-attach 10 #(update-at % (conj install-path 3) (constantly {})))))))
  (testing "an install whose response image does not load"
    (is (= [:yin.k/undecodable :install-response]
           (take 2 (refused-before-attach 10 #(update-at % (conj install-path 3) (fn [r] (assoc-in r [:image :value] []))))))))
  (testing "an install whose manifest names another module"
    (is (= [:yin.k/undecodable :install-response]
           (take 2 (refused-before-attach 10 #(update-at % (conj install-path 3)
                                                         (fn [r] (assoc-in r [:manifest :yin.module/name] 'other.mod))))))))
  (testing "an install in a phase the engine never carries"
    (is (= [:yin.k/undecodable :install-phase]
           (take 2 (refused-before-attach 10 #(update-at % (conj install-path 1) (constantly :bogus)))))))
  (testing "an install whose child is not blocked"
    (is (= [:yin.k/undecodable :install-child]
           (take 2 (refused-before-attach 10 #(update-at % (conj install-path 4 2) (constantly :halted))))))))


(defn exclusive
  [task]
  (update-at task [16] (constantly {:custody :exclusive})))


(defn without-trees
  [path]
  (fn [task] (update-at task path #(assoc % :yin.k/trees {}))))


(deftest carrier-steps-run-in-order-over-the-tree
  (testing "custody (10) alone: a slot outside the custody grammar (phase 7a)"
    (is (= [:yin.k/undecodable :custody-key] (take 2 (refused-before-attach 1 exclusive)))))
  (testing "the dependency closure (9) before custody (10)"
    (let [[status _ r] (refused-before-attach 1 (comp exclusive (without-trees [10])))]
      (is (= :yin.k/unsatisfied status) (pr-str r))
      (is (seq (get-in r [:yin.k/missing :yin.k/trees])))))
  (testing "the child's dependency closure before the root's custody"
    (let [[status _ r] (refused-before-attach 10 (comp exclusive (without-trees (conj install-path 4 10))))]
      (is (= :yin.k/unsatisfied status) (pr-str r))
      (is (seq (get-in r [:yin.k/missing :yin.k/trees])))))
  (testing "an install record (8) before the root's dependency closure (9)"
    (is (= [:yin.k/undecodable :install-phase]
           (take 2 (refused-before-attach 10 (comp #(update-at % (conj install-path 1) (constantly :bogus))
                                                   (without-trees [10]))))))))


;; =============================================================================
;; The dependency closure: each free occurrence in its own context (§7.1)
;; =============================================================================

(defn halted-on
  [ast]
  (vm/run (load-program (machine) ast)))


(def ghost-fn (s/lam [] (s/v 'ghost)))


(defn lift-outcome
  "Lift m: [the wire or the refusal's ex-data, serves]."
  [m]
  (let [n (atom 0)
        {:keys [serve!]} (served #{:reader})
        r (try (rows/lift-task m (fn [h] (swap! n inc) (serve! h)) (lift-opts))
               (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e)))]
    [r @n]))


(defn missing-obligations
  [m]
  (let [[d] (lift-outcome m)]
    (when (= [:yin.k/unsatisfied :incomplete] [(:yin.k/status d) (:yin.k/discovery d)])
      (get-in d [:yin.k/missing :obligations]))))


(defn routed
  "closure c, its E naming module store mid."
  [m c mid]
  (values/closure (:owner m) (assoc-in (values/payload c) [:env engine/store-of-key] mid)))


(deftest free-occurrences-are-answered-in-their-own-context
  (let [m (halted-on ghost-fn)
        g (vm/value m)]
    (testing "a carried closure whose only free obligation is missing"
      (is (= #{'ghost} (missing-obligations m))))
    (testing "a module store the closure does not route to answers nothing"
      (is (= #{'ghost} (missing-obligations (assoc m :module-stores {'unrelated {'ghost 42}})))))
    (testing "a routed closure reads its module store, never the root store"
      (is (= #{'ghost} (missing-obligations (assoc m :value (routed m g 'scoped) :store {'ghost 1}
                                                   :module-stores {'scoped {}})))))
    (testing "the root store answers a top-level closure"
      (is (nil? (missing-obligations (assoc m :store {'ghost 1})))))
    (testing "reachable only through the store"
      (is (= #{'ghost} (missing-obligations (assoc m :value 1 :store {'k g})))))
    (testing "another closure's E answers nothing"
      (let [bound (values/closure (:owner m) (assoc-in (values/payload g) [:env 'ghost] 1))]
        (is (nil? (missing-obligations (assoc m :value bound))) "its own E answers")
        (is (= #{'ghost} (missing-obligations (assoc m :value 1 :store {'a bound 'k g}))))))
    (testing "reachable only through a module store a routed closure reaches"
      ;; the holder runs in outer, where ghost is 2; k, found in outer,
      ;; runs at top level, where nothing answers ghost
      (is (= #{'ghost} (missing-obligations (assoc m :value 1 :store {'mod (routed m g 'outer)}
                                                   :module-stores {'outer {'ghost 2 'k g}}))))))
  (testing "reachable only through another closure's E"
    (is (= #{'ghost} (missing-obligations (halted-on (s/let1 'h ghost-fn (s/lam [] (s/app (s/v 'h)))))))))
  (testing "a routed module store answers, travels alone, and the lowered closure reads it"
    (let [m0 (halted-on ghost-fn)
          m (assoc m0 :value (routed m0 (vm/value m0) 'scoped) :module-stores {'scoped {'ghost 42} 'unrelated {'other 1}})
          [wire serves] (lift-outcome m)
          {:keys [toy]} (served #{:reader})
          [recv attaches] (lowered toy wire)]
      (is (contains? wire :yin.k/rows) (pr-str wire))
      (is (zero? serves))
      (is (zero? attaches))
      (is (= ['scoped] (mapv #(first (:slots %)) (slot (root-task wire) 7))) "only the routed module store travels")
      (is (= 42 (vm/value (run-beside (assoc recv :store {'f (vm/value recv)}) (s/app (s/v 'f)))))))))


(def fresh-read (s/next-of (s/cursor-of {:type :stream/make :buffer 4})))


(defn fed-73
  "Lift m, lower it into a fresh receiver, then feed the source's stream
   73 and run both."
  [m]
  (let [{:keys [serve! toy peer]} (served #{:reader})
        wire (rows/lift-task m serve! (lift-opts))
        [recv attaches] (lowered toy wire)]
    (stream/append! (s/stream-of m) 73)
    {:source (vm/value (vm/run m)) :receiver (vm/value (drive-peers recv [peer])) :attaches attaches}))


(deftest discharge-follows-control-flow-from-the-resume-pc
  (let [past (fn [consequent]
               (halted-on {:type :if :test (s/lit false) :consequent consequent :alternate fresh-read}))]
    (testing "a free read behind the resume pc is no obligation"
      (doseq [m [(past (s/v 'ghost))
                 (assoc (past (s/v 'ghost)) :module-stores {'unrelated {'ghost 42}})
                 (past ghost-fn)]]
        (is (= {:source 73 :receiver 73 :attaches 1} (fed-73 m))))))
  (testing "a free read after the resume pc is an obligation"
    (is (= #{'ghost} (missing-obligations (halted-on (s/app (s/lam ['p 'q] (s/lit 1)) fresh-read (s/v 'ghost)))))))
  (testing "a closure reachable code can still construct is an obligation"
    (is (= #{'ghost} (missing-obligations (halted-on (s/app (s/lam ['p 'q] (s/lit 1)) fresh-read ghost-fn))))))
  (testing "a backward jump makes earlier code live"
    ;; §3.1 admits no backward jump, so no carried vector has one; live-pcs
    ;; follows the stated edges, never pc order
    (let [v [[:const 1 false] [:branch-false 1 4] [:var 0 'ghost] [:jump 6] [:const 0 1] [:jump 2] [:halt 0]]]
      (is (contains? (rows/live-pcs v 4) 2))
      (is (not (contains? (rows/live-pcs v 3) 2))))))


;; =============================================================================
;; Every store-of names a module store of its own task (§2.6)
;; =============================================================================

(defn routed-beside-stream
  "ast's closure routed to module store scoped, with an authentic stream
   in the root store so that an attachment is observable."
  [ast stores]
  (let [m0 (halted-on ast)
        [ref m] (engine/attach-resource m0 (s/ring 4))]
    (assoc m :value (routed m (vm/value m) 'scoped) :module-stores stores :store {'stream ref})))


(defn without-module-stores
  [path]
  (fn [task] (update-at task path (constantly []))))


(defn module-store-ids
  [task]
  (mapv #(first (:slots %)) (slot task 7)))


(deftest every-store-of-names-a-module-store-of-its-own-task
  (doseq [[label ast stores value] [["a scoped store" ghost-fn {'scoped {'ghost 42}} 42]
                                    ["a present empty store" (s/lam [] (s/lit 7)) {'scoped {}} 7]]]
    (testing label
      (let [{:keys [serve! toy]} (served #{:reader})
            wire (rows/lift-task (routed-beside-stream ast stores) serve! (lift-opts))
            [recv attaches] (lowered toy wire)
            [r none] (lowered toy (rows/emit ((without-module-stores [7]) (root-task wire))))]
        (is (= ['scoped] (module-store-ids (root-task wire))))
        (is (= 1 attaches))
        (is (= value (vm/value (run-beside (assoc recv :store {'f (vm/value recv)}) (s/app (s/v 'f))))))
        (testing ", its rows removed"
          (is (zero? none) "refused before any attachment")
          (is (= [:yin.k/unsatisfied #{'scoped}] [(:yin.k/status r) (get-in r [:yin.k/missing :yin.k/stores])]))))))
  (testing "a closure inside a module store's value naming another module store"
    ;; k lives only in outer's value and is routed to inner; stripping inner
    ;; alone must refuse, else k attaches and fails reading ghost
    (let [m0 (halted-on ghost-fn)
          g (vm/value m0)
          [ref m] (engine/attach-resource m0 (s/ring 4))
          m (assoc m :value (routed m g 'outer) :store {'stream ref}
                   :module-stores {'outer {'ghost 1 'k (routed m g 'inner)} 'inner {'ghost 42}})
          {:keys [serve! toy]} (served #{:reader})
          wire (rows/lift-task m serve! (lift-opts))
          strip (fn [task] (update-at task [7] (fn [ns] (vec (remove #(= 'inner (first (:slots %))) ns)))))
          [recv attaches] (lowered toy wire)
          [r none] (lowered toy (rows/emit (strip (root-task wire))))]
      (is (= #{'outer 'inner} (set (module-store-ids (root-task wire)))))
      (is (= 1 attaches))
      (is (= 42 (vm/value (run-beside (assoc recv :store {'f (get-in recv [:module-stores 'outer 'k])}) (s/app (s/v 'f))))))
      (is (zero? none) "refused before any attachment")
      (is (= [:yin.k/unsatisfied #{'inner}] [(:yin.k/status r) (get-in r [:yin.k/missing :yin.k/stores])]))))
  (testing "a frame's E naming a store its task does not carry"
    (let [[status _ r] (refused-before-attach 17 (without-module-stores [7]))]
      (is (= :yin.k/unsatisfied status))
      (is (= 1 (count (get-in r [:yin.k/missing :yin.k/stores]))))))
  (testing "a child does not read its parent's store of the same key"
    (let [scope (fn [x]
                  (-> x
                      (update-in [:wait-set 0 :env] assoc engine/store-of-key 'scoped)
                      (assoc :module-stores {'scoped {}})))
          m (:machine (bare 10))
          m (-> (scope m) (update-in [:installs 'host.mod :vm] scope))
          {:keys [serve! toy]} (served #{:reader :writer})
          wire (rows/lift-task m serve! (lift-opts))
          child-path (conj install-path 4)
          [ok n] (lowered toy wire)
          [r none] (lowered toy (rows/emit ((without-module-stores (conj child-path 7)) (root-task wire))))]
      (is (= ['scoped] (module-store-ids (root-task wire))) "the parent carries scoped")
      (is (= ['scoped] (module-store-ids (slot (first (slot (root-task wire) 12)) 4))) "and so does the child")
      (is (vm/blocked? ok) (pr-str (when (map? ok) (select-keys ok [:yin.k/status :yin.k/kind]))))
      (is (= (count (stream-identities wire)) n))
      (is (zero? none) "refused before any attachment")
      (is (= [:yin.k/unsatisfied #{'scoped}] [(:yin.k/status r) (get-in r [:yin.k/missing :yin.k/stores])])))))


;; =============================================================================
;; Phase 6c: the cross-engine lanes (§5.3, §5.4 laws 4 and 5, §8.3 10-12)
;; =============================================================================

(def link-rows
  "Rows the de Bruijn lanes do not carry (§5.3, the shared fragment): a
   de Bruijn kernel links a native image while module responses carry A,
   and §4 defines no layout for install children (canonical-task refuses
   :installs). The reference lane runs the whole matrix; these lanes are
   removed with their kernels at phase 8."
  #{7 8 9 10 11 12 17})


(def lane-rows
  "The wrapped rows every lane executes: 15 of §5.2's 22."
  (filterv #(not (contains? link-rows (first %))) rows))


(defn lane-cases
  "§5.3's shared fragment: 15 lane rows x 4 segments, plus row 20 once:
   [row segment expected build]."
  []
  (filterv #(not (contains? link-rows (first %))) (matrix-cases)))


(defn on-lane
  [lane build]
  (binding [*lane* lane] (build)))


(defn lane-lift
  "The lift of §7.1 on m's own lane."
  [m serve!]
  (if (= :semantic (lane-of m))
    (rows/lift-task m serve! (lift-opts))
    (layout/lift-task m (kernel-space m) (on-lane :semantic machine) serve! (lift-opts))))


(defn lane-lower
  "The lower of §7.2 into a fresh receiver on lane, of composition opts."
  [lane opts wire attach!]
  (let [scratch (on-lane :semantic #(machine opts))]
    (if (= :semantic lane)
      (rows/lower-task scratch wire attach!)
      (layout/lower-task scratch (on-lane lane #(machine opts)) wire attach!))))


(defn injective
  "names, refusing when two identities share a logical name (§5.5)."
  [names]
  (when-not (= (count names) (count (distinct (vals names))))
    (throw (ex-info "The correspondence is not injective" {:law-4 :injective :names names})))
  names)


(defn logical-names
  "§5.5's explicit correspondence: each served identity to the resource
   key the lifted machine holds that handle under. The fixture builds the
   same program on every lane, so one key names one logical stream."
  [m peer]
  (injective
    (into {} (map (fn [[id e]]
                    [id (or (some (fn [[k h]] (when (identical? h (:handle e)) k)) (:resources m))
                            (throw (ex-info "A served handle the machine does not hold" {:id id})))]))
          @(:table peer))))


(defn ring-names
  "{stream identity -> resource key} over every stream m holds."
  [m]
  (injective
    (into {} (keep (fn [[k h]]
                     (when (satisfies? stream/IDaoStreamDescriptor h)
                       [(:dao.stream/identity (stream/descriptor h)) k])))
          (:resources m))))


(defn logical-positions
  "The task with each cursor position's embedded stream identity renamed
   to its logical name. A ring buffer cursor carries its stream's
   identity, which §5.5's marker substitution does not reach, so the
   cross-lane comparison renames only that field, under the same fixture
   correspondence; every other field, the offset included, is kept."
  [task m]
  (let [names (ring-names m)
        k :dao.stream.ringbuffer/identity]
    (replace-nodes task #(= :cursor-cell-body (:kind %))
                   (fn [n]
                     (update-in n [:slots 1]
                                (fn [p]
                                  (if (and (map? p) (contains? names (get p k)))
                                    (assoc p k (get names (get p k)))
                                    p)))))))


(defn rename-identities
  "x with every :dao.stream/identity value that names maps renamed."
  [x names]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [k (if (and (= :dao.stream/identity k) (contains? names v))
                                  (get names v)
                                  (rename-identities v names))]))
                   x)
    (vector? x) (mapv #(rename-identities % names) x)
    :else x))


(defn logical-descriptors
  "§5.5: {logical name -> stream descriptor, identities renamed} over every
   stream m holds. canonical-under erases a marker's descriptor, so the
   lanes' descriptors are shown equivalent on these, separately."
  [m]
  (let [names (ring-names m)]
    (into {} (keep (fn [[k h]]
                     (when (satisfies? stream/IDaoStreamDescriptor h)
                       [k (rename-identities (stream/descriptor h) names)])))
          (:resources m))))


(defn descriptors-equivalent?
  "Each lane's logical-descriptors, one per lane: equal maps."
  [ds]
  (apply = ds))


(defn identities-in
  "The members of ids anywhere in x."
  [x ids]
  (cond
    (contains? ids x) #{x}
    (map? x) (into #{} (mapcat #(identities-in % ids)) (concat (keys x) (vals x)))
    (coll? x) (into #{} (mapcat #(identities-in % ids)) x)
    :else #{}))


(defn task-root
  "§5.5 over decoded task: positions renamed, re-emitted, then
   canonical-under the served correspondence; no lane identity survives."
  [task m peer]
  (let [names (logical-names m peer)
        canonical (rows/canonical-under (rows/emit (logical-positions task m)) names)]
    (is (empty? (identities-in (:yin.k/rows canonical) (into (set (keys names)) (keys (ring-names m)))))
        "every lane identity was renamed")
    (:yin.k/root canonical)))


(defn logical-root
  "§5.5: the lift's root id with every lane identity replaced by the
   fixture's logical name."
  [wire m peer]
  (task-root (rows/decode wire) m peer))


(defn lane-root
  "Build on lane, lift: {:root under the logical names, :reference the
   lane's own run, :lane the kernel the world ran on, :descriptors, and
   :task :machine :peer for the controls}. A world's :served replaces
   `served`."
  [lane build]
  (scoped
    (fn []
      (let [world (on-lane lane build)
            m (:machine world)
            {:keys [serve! peer]} ((or (:served world) served) (or (:surfaces world) #{:reader}))
            task (rows/decode (lane-lift m serve!))]
        (is (nil? (rows/validate-frames task)) "law 6 on every lane")
        {:root (task-root task m peer)
         :reference (reference (on-lane lane build))
         :lane (lane-of m) :descriptors (logical-descriptors m)
         :task task :machine m :peer peer}))))


(defn canonical-equality
  "Law 4 for one case: exactly the three distinct kernel lanes lift to one
   root, their streams' descriptors are equivalent under the
   correspondence (else it throws, :law-4 :descriptors), and each lane's
   own run answers the reference."
  [build expected]
  (let [roots (mapv #(lane-root % build) lanes)]
    (is (= 3 (count roots)))
    (is (= [:semantic :stack :register] (mapv :lane roots)) "three distinct kernel lanes")
    (is (apply = (map :root roots)) "law 4: one root id on every lane")
    (when-not (descriptors-equivalent? (mapv :descriptors roots))
      (throw (ex-info "The descriptors differ under the correspondence"
                      {:law-4 :descriptors :descriptors (mapv :descriptors roots)})))
    (doseq [{:keys [lane reference]} roots]
      (is (:halted reference) (str lane " completes"))
      (when (some? expected) (is (= expected (:value reference)) (str lane " answers the reference"))))
    roots))


(defn control-root
  "A lane's root with its decoded task changed by f first."
  [{:keys [task machine peer]} f]
  (let [task' (f task)]
    (is (not= task task') "the control changed the task")
    (task-root task' machine peer)))


(defn first-binding
  "Pairs node n with the value of its first pair whose key satisfies pred
   replaced by v."
  [n pred v]
  (let [ps (pairs-of n)
        i (first (keep-indexed (fn [i [k _]] (when (pred k) i)) ps))]
    (if i (rows/node (:kind n) (mapcat identity (assoc-in ps [i 1] v))) n)))


(deftest law-4-controls
  ;; §5.5 (D1): a changed position offset, descriptor field, saved value or
  ;; lexical value changes what law 4 compares
  (let [roots (canonical-equality #(bare 1) ["A" "B"])
        [sr stack-lane register-lane] roots
        base (:root sr)]
    (testing "the three lanes agree"
      (is (= base (:root stack-lane) (:root register-lane))))
    (testing "a cursor position's offset"
      (is (not= base (control-root stack-lane
                                   (fn [t]
                                     (replace-nodes t #(= :cursor-cell-body (:kind %))
                                                    (fn [n] (update-in n [:slots 1 :dao.stream.ringbuffer/position] inc))))))))
    (testing "a stream only the register lane holds: one root, the descriptors differ"
      (let [build #(cond-> (bare 1)
                     (= :register *lane*) (update-in [:machine :resources] assoc :control/extra (s/ring 4)))
            d (error #(canonical-equality build ["A" "B"]))]
        (is (= :descriptors (:law-4 d)) (pr-str d))
        (is (= [false false true] (mapv (fn [ds] (contains? ds :control/extra)) (:descriptors d))))))
    (testing "two served identities under one logical name"
      (let [twin (fn [surfaces]
                   (let [{:keys [peer] :as sv} (served surfaces)]
                     (update sv :serve!
                             (fn [serve!]
                               (fn [h]
                                 (let [r (serve! h)
                                       id (:dao.stream/identity r)]
                                   (swap! (:table peer) assoc (str id "-twin") (get @(:table peer) id))
                                   r))))))
            build #(cond-> (bare 1) (= :stack *lane*) (assoc :served twin))
            d (error #(canonical-equality build ["A" "B"]))]
        (is (= :injective (:law-4 d)) (pr-str d))
        (is (< (count (distinct (vals (:names d)))) (count (:names d))))))
    (testing "a saved window value"
      (is (not= base (control-root register-lane
                                   (fn [t]
                                     (replace-nodes t #(= :window (:kind %))
                                                    (fn [n] (first-binding n some? [:lit :control]))))))))
    (testing "a lexical value"
      (is (not= base (control-root sr
                                   (fn [t]
                                     (replace-nodes t #(= :env (:kind %))
                                                    (fn [n] (update-in n [:slots 0] first-binding #{'a} [:lit :control]))))))))))


(deftest cross-engine-canonical-equality-bare
  ;; the quick-tier slice of acceptance 10: every lane row once, bare;
  ;; §5.3's shared fragment, 16 cases over three distinct kernel lanes
  (let [n (atom 0)]
    (doseq [[row r build] lane-rows]
      (testing (str "row " row)
        (canonical-equality #(build identity) r)
        (swap! n inc)))
    (testing "row 20"
      (canonical-equality tail-effect-halt "B")
      (swap! n inc))
    (is (= 16 @n))))


(deftest ^:slow cross-engine-canonical-equality
  ;; acceptance 10: law 4 over §5.3's shared fragment, 61 cases, each over
  ;; exactly three distinct kernel lanes
  (slow/guard
    "cross-engine-canonical-equality"
    (fn []
      (let [cases (lane-cases) n (atom 0)]
        (is (= 61 (count cases)) "15 lane rows x 4 segments + row 20")
        (doseq [[row label expected build] cases]
          (testing (str "row " row " " (name label))
            (canonical-equality build expected)
            (swap! n inc)))
        (is (= 61 @n))))))


(def ordered-pairs
  "Law 5's six ordered pairs of distinct lanes."
  (into [] (mapcat (fn [from] (keep (fn [to] (when (not= from to) [from to])) lanes))) lanes))


(def unlowerable
  "[row receiver] pairs a de Bruijn receiver cannot continue (§5.3): row
   4 is lifted from a de Bruijn source but not lowered into a de Bruijn
   receiver, whose re-park names its own call pair
   (ffi/response-wait-entry), not the carried response cursor cell the
   semantic kernel restores. Emptied when the retained-FFI seam
   (§4, 6c-post-D2) lands, raising law 5 to 366 and 96."
  #{[4 :stack] [4 :register]})


(defn crossed
  "Lift on from, lower into a fresh receiver on to, satisfy through the
   source's streams: the run's observables and attaches."
  [from to build]
  (scoped
    (fn []
      (let [world (on-lane from build)
            {:keys [toy peer serve!]} (served (or (:surfaces world) #{:reader}))
            wire (lane-lift (:machine world) serve!)
            receiver ((or (:receiver world) (constantly nil)))
            {:keys [attach!] n :count} (recording-attacher toy)
            recv (lane-lower to (merge {:attach-stream attach!} (:opts receiver)) wire attach!)
            _ (is (= to (lane-of recv)))
            attaches @n
            done (satisfy world recv #(drive-peers % [peer]))
            _ (s/serve-mirror! peer)]
        (when-let [f (:finish world)] (f))
        (when-let [f (:finish receiver)] (f))
        {:attaches attaches :wire wire
         :halted (vm/halted? done) :value (normalize (vm/value done)) :trace @(:trace world)}))))


(defn cross-lowering
  "Law 5 for one case: every admitted ordered pair continues to the
   reference. Answers the pairs that ran."
  [row build]
  (let [ref (reference (on-lane :semantic build))]
    (is (:halted ref))
    (reduce (fn [ran [from to]]
              (if (contains? unlowerable [row to])
                ran
                (testing (str (name from) " -> " (name to))
                  (let [got (crossed from to build)]
                    (is (= (count (stream-identities (:wire got))) (:attaches got)))
                    (is (= (select-keys ref [:halted :value :trace]) (select-keys got [:halted :value :trace]))
                        "law 5: the lowered run continues to the reference")
                    (conj ran [from to])))))
            []
            ordered-pairs)))


(deftest cross-engine-lowering-bare
  ;; the quick-tier slice of acceptance 11: every lane row once, bare;
  ;; §5.3's shared fragment, 92 transfers (96 once unlowerable is emptied)
  (is (= 6 (count ordered-pairs)))
  (let [ran (atom 0)]
    (doseq [[row _ build] lane-rows]
      (testing (str "row " row)
        (let [pairs (cross-lowering row #(build identity))]
          (is (= (if (= 4 row) 2 6) (count pairs)) "every admitted ordered pair ran")
          (swap! ran + (count pairs)))))
    (testing "row 20"
      (is (= 6 (count (cross-lowering 20 tail-effect-halt))))
      (swap! ran + 6))
    (is (= 92 @ran) "16 cases x 6 pairs, less row 4's 4 into a de Bruijn receiver")))


(deftest ^:slow cross-engine-lowering
  ;; acceptance 11: law 5, six ordered pairs over §5.3's shared fragment,
  ;; 350 transfers (366 once unlowerable is emptied)
  (slow/guard
    "cross-engine-lowering"
    (fn []
      (let [ran (atom 0) cases (lane-cases)]
        (is (= 61 (count cases)))
        (doseq [[row label _ build] cases]
          (testing (str "row " row " " (name label))
            (swap! ran + (count (cross-lowering row build)))))
        (is (= 350 @ran) "61 cases x 6 pairs, less row 4's 4 per segment")))))


(defn register-judge
  "The frame validator's verdict on register entry `entry` of m,
   reconstructed through the layout."
  [m entry]
  (let [sp (kernel-space m)
        p (layout/lift-state (layout/lift-context m sp (:owner m)) entry)
        code-map (into {} (map (fn [[a sg]] [a (:vector sg)])) (:segments sp))
        as-sr {:code (into {} (map (fn [a] [a {:address a}])) (keys code-map))}]
    (:yin.k/kind (frame/defect (ft/canonical as-sr p true) code-map :wait {:free-env {} :ffi-call-ids #{}}))))


(defn spare-register
  "A physical register of the entry's body outside its live set and its
   destination that R gives exactly one virtual."
  [m entry]
  (let [sg (val (first (:segments (kernel-space m))))
        site (- (:site-pc entry) (layout/offset :register m sg))
        body (nth (:phys sg) (code/body-index (:bodies sg) site))
        unique (keep (fn [[s xs]] (when (= 1 (count xs)) s)) (group-by body (keys body)))]
    (first (remove (conj (set (:live entry)) (:dest entry)) (sort unique)))))


(def layout-corpus
  "C, [label ast]: the B0 corpus, the phase-3 corpus, then a tail call in
   operand position, whose dead layout syntax reads registers other than
   the call's own (phase-5 §6.3's last golden)."
  (conj (into (mapv (fn [[label ast _]] [label ast]) b0/corpus) corpus/programs)
        [:tail-operand (corpus/app (corpus/v 'f) (corpus/tail (corpus/app (corpus/v 'g) (corpus/v 'x))) (corpus/v 'y))]))


(defn- parking-site?
  "Rule A1: a non-tail parking instruction."
  [t]
  (or (contains? #{:stream-next :stream-put :ffi-call :park :current-continuation} (nth t 0))
      (and (= :call (nth t 0)) (not (code/tail-call? t)))))


(defn ambiguous-dead-slots
  "[v p s] over C: a reachable parking pc p and a physical slot s of its
   body that R gives two or more virtuals, outside live-out*(p)'s image
   and p's destination."
  []
  (into []
        (mapcat (fn [[_ ast]]
                  (let [v (:vector (linearize/project ast))
                        sg (segment-of v)
                        reach (into #{} (mapcat #(analysis/reachable v (:start %))) (code/body-ranges v))]
                    (mapcat (fn [p]
                              (let [rd (code/rd (nth v p))
                                    body (nth (:phys sg) (code/body-index (:bodies sg) p))
                                    used (into #{} (map #(layout/phys sg p %))
                                               (cond-> (layout/live-out sg p) (some? rd) (conj rd)))]
                                (keep (fn [[s xs]] (when (and (< 1 (count xs)) (not (contains? used s))) [v p s]))
                                      (sort-by key (group-by body (keys body))))))
                            (filter #(parking-site? (nth v %)) (sort reach))))))
        layout-corpus))


(defn register-entry
  "A register kernel k's wait entry parked after pc p of v, its window the
   live registers holding :v, plus slot extra holding :x when given."
  [k v p extra]
  (let [sg (segment-of v)
        off (layout/offset :register k sg)
        rd (code/rd (nth v p))
        regs (vec (sort-by first (cond-> (mapv (fn [r] [r :v]) (layout/register-live sg p))
                                   (some? extra) (conj [extra :x]))))]
    {:site-pc (+ off p) :pc (+ off p 1) :regs regs :live (mapv first regs)
     :dest (when (some? rd) (layout/phys sg p rd)) :continuation [] :resume-mode :write-result
     :frames (mapv (fn [c] (vec (repeat (count (nth (nth v c) 2)) nil))) (layout/chain sg (inc p)))}))


(deftest layout-refusals
  (testing "a kernel stepped to a non-safepoint pc"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (scoped
          (fn []
            (let [m (on-lane lane #(vm/step (load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1)))))
                  n (atom 0)
                  {:keys [serve!]} (served #{:reader})
                  d (error #(lane-lift m (fn [h] (swap! n inc) (serve! h))))]
              (is (not (vm/halted? m)))
              (is (= :yin.k/not-at-safepoint (:yin.k/status d)) (pr-str d))
              (is (zero? @n) "no stream was served")
              (testing ", its registers read as a payload"
                (let [entry (if (= :stack lane)
                              (select-keys m [:pc :frames :stack :continuation])
                              (assoc (select-keys m [:pc :frames :continuation]) :regs [] :resume-mode :write-result))
                      cx (layout/lift-context m (kernel-space m) (:owner m))]
                  (is (= :yin.k/not-at-safepoint (:yin.k/status (error #(layout/lift-state cx entry)))))))))))))
  (testing "a register entry whose live was tampered"
    (scoped
      (fn []
        (let [m (:machine (on-lane :register #(bare 1)))
              entry (first (:wait-set m))
              live (:live entry)
              spare (spare-register m entry)]
          (is (nil? (register-judge m entry)) "the untampered entry reconstructs a valid frame")
          (is (seq live))
          (is (= :window-missing (register-judge m (assoc entry :live (vec (rest live)) :regs (vec (rest (:regs entry))))))
              "a live register dropped")
          (is (some? spare))
          (is (= :window-extra (register-judge m (assoc entry :live (vec (sort (conj live spare)))
                                                        :regs (vec (sort-by first (conj (:regs entry) [spare :x]))))))
              "a dead register added")))))
  (testing "a register entry tampered with an ambiguous dead preimage: the layout refuses"
    (let [fixtures (ambiguous-dead-slots)]
      (is (seq fixtures) "C holds one: the allocator reuses slots")
      (doseq [[v p s] fixtures]
        (let [sp (layout/space [v])
              k (layout/attach-space (on-lane :register machine) sp)
              cx (layout/lift-context k sp (:owner k))
              d (error #(layout/lift-state cx (register-entry k v p s)))]
          (is (map? (layout/lift-state cx (register-entry k v p nil))) "untampered, the entry lifts")
          (is (= [:yin.k/non-portable :register-layout] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
          (is (= {:yin.k/pc p :yin.k/register s} (select-keys d [:yin.k/pc :yin.k/register])))))))
  (testing "a primitive in a window, a lexical binding, a closure's capture or a window's guest collection: the register kernel cannot hold it, refused before any attach"
    (doseq [[label op expected path?]
            [[:window (s/app (s/v 'conj) (s/lit []) read-op) ["B"]
              #(= [:frames 0 :window] (subvec % 0 3))]
             ;; the read under cat, whose closure captures no primitive: s/then
             ;; would save its own closure, which captures the binding too
             [:lexical (s/app (s/lam ['q] (cat-of (cat-of (s/lit []) read-op) (s/app (s/v 'q) (s/lit []) (s/lit 1))))
                              (s/v 'conj))
              ["B" [1]] #(= '[:frames 0 :env q] %)]
             [:captured (s/let1 'g (s/lam ['h] (s/lam ['x] (s/app (s/v 'h) (s/lit []) (s/v 'x))))
                                (s/let1 'k (s/app (s/v 'g) (s/v 'conj))
                                        (cat-of (cat-of (s/lit []) read-op) (s/app (s/v 'k) (s/lit 2)))))
              ["B" [2]] #(= '[:frames 0 :env k h] %)]
             [:nested (s/app (s/lam ['w 'r] (s/v 'r)) (cat-of (s/lit []) (s/v 'conj)) read-op) "B"
              #(and (= 5 (count %)) (= [:frames 0 :window] (subvec % 0 3)) (= 0 (peek %)))]]]
      (testing (str " " (name label))
        (let [build #(reader % op)
              world (build identity)
              {:keys [serve! toy]} (served #{:reader})
              wire (rows/lift-task (:machine world) serve! (lift-opts))
              {:keys [attach!] n :count} (recording-attacher toy)
              d (error #(lane-lower :register {:attach-stream attach!} wire attach!))]
          (is (= [:yin.k/unsupported :continuation-registers] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
          (is (path? (:yin.k/path d)) (pr-str (:yin.k/path d)))
          (is (zero? @n) "refused before any attachment")
          (testing ", the stack kernel holds it"
            (let [ref (reference (build identity))
                  got (crossed :semantic :stack #(build identity))]
              (is (= expected (:value ref)))
              (is (= (:value ref) (:value got))))))))))
