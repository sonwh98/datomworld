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
            [clojure.set :as set]
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


(def portable-stream-primitives
  "Name the corpus's effect constructors in the composition so intermediate
   operator values have the existing :prim wire form."
  (merge vm/primitives
         (into {} (map (fn [[n f]]
                         [(symbol "stream" (name n))
                          (assoc (get module/stream-profiles n) :yin.k/function f)]))
               module/stream-module)))


(defn- composition
  [opts]
  (merge {:make-stream tu/make-stream :capability-secret tu/secret
          :secret-source (fn [origin] (str tu/secret "/" (name origin)))
          :modules stream-modules :primitives portable-stream-primitives}
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


(def install-lower-opts
  "Receiver admission of this composition's fixed blocking-body module."
  {:yin.k/admitted-modules {'host.mod (jing/segment-key
                                        (:manifest (module-response blocking-body #{'f})))}})


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
        m (if (= :running phase)
            (let [child (get-in m1 [:installs 'host.mod :vm])
                  _ (stream/append! (s/stream-of child) :fed)
                  active (sr/run-fuel child 1)]
              (assoc-in m1 [:installs 'host.mod] (assoc (get-in m1 [:installs 'host.mod]) :phase :running :vm active)))
            m1)
        child-stream (s/stream-of (get-in m1 [:installs 'host.mod :vm]))]
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
  (vm/run (load-program (if (= :semantic (lane-of m)) (assoc m :env (:free-env m)) m) ast)))


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
        recv (rows/lower-task (machine (merge {:attach-stream attach!} (:opts receiver))) wire attach! install-lower-opts)
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
            recv (rows/lower-task (machine {:attach-stream attach!}) wire attach! install-lower-opts)
            inst (get-in recv [:installs 'host.mod])
            lowered (:vm inst)]
        (is (= (count (stream-identities wire)) @n) "one attach per distinct stream identity of the tree")
        (is (= 'host.mod mn))
        (is (= phase carried-phase) "the child's phase as the source held it")
        (is (= (if (= :running phase) :running :blocked) (slot child 2)) "the child's kind")
        (is (= :child (slot child 3)))
        (is (= [:install 'host.mod false] (:slots (first-pending (root-task wire)))))
        (is (= phase (:phase inst)))
        (is (= (some? (:control (:vm (get-in (:machine world) [:installs 'host.mod]))))
               (some? (:control lowered))) "lower preserves control without running")
        (is (= (if (= :running phase) [] [:next]) (mapv :reason (:wait-set lowered))) "the child waits where it was lifted")
        (is (= (not= :running phase) (vm/blocked? lowered)))
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
        r (try (rows/lower-task (machine {:attach-stream attach!}) wire attach! install-lower-opts)
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
      (fn [task] (update-at task [13 0 1] (constantly (rows/node :pending [:install 'bogus false]))))]
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
          m0 (load-program (machine) (s/app (s/v 'stream/poll!) (s/v 'c)))
          [sref m1] (engine/attach-resource m0 (s/ring 8))
          [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
          held (vm/run (assoc m2 :store {'c cref} :yin.k/gate :running))]
      (is (= [:observe] (mapv :reason (:wait-set held))))
      (doseq [[label m status kind] [["a held :observe" held :yin.k/non-portable :reason-mismatch]]]
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


(deftest a-nonempty-ready-queue-is-carried
  (let [[parked src] (parked-reader)
        _ (stream/append! src "B")
        woken (engine/check-wait-set parked)
        world {:machine woken :steps [(fn [] nil)] :trace (atom [])}
        got (handed-off world)]
    (is (= 1 (count (:ready-queue woken))))
    (is (= 1 (count (slot (root-task (:wire got)) 18))))
    (is (= "B" (:value got)))
    (is (= (rows/canonical-under (:wire got) (:corr got))
           (rows/canonical-under (:relift got) (:corr2 got))))))


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
        recv (rows/lower-task (machine {:attach-stream attach!}) wire attach! install-lower-opts)
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
      (rows/lower-task scratch wire attach! install-lower-opts)
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


(deftest unlowerable-retained-ffi-is-refused
  (doseq [[from to] ordered-pairs
          :when (contains? unlowerable [4 to])]
    (testing (str (name from) " -> " (name to))
      (scoped
        (fn []
          (let [world (on-lane from #(retained-ffi identity))
                {:keys [toy serve!]} (served (:surfaces world))
                wire (lane-lift (:machine world) serve!)
                {:keys [attach!] n :count} (recording-attacher toy)
                receiver ((:receiver world))
                d (error #(lane-lower to (merge {:attach-stream attach!} (:opts receiver)) wire attach!))]
            (is (= {:yin.k/status :yin.k/unsupported :yin.k/kind :retained-ffi} d))
            (is (zero? @n) "row 4 is gated before attachment")))))))


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


;; =============================================================================
;; 6e: laws 4 and 5 at every aligned boundary (plan v2.1 §3.9 6e, the
;; any-pc extension of §5.3; §5.7's driver on all three lanes)
;; =============================================================================

(declare fuel-drive active-tree?)


(defn kernel-active?
  "A de Bruijn kernel's activation is live: not blocked, not halted —
   its pc is the instruction about to execute (a run state)."
  [k]
  (engine/active-continuation? k))


(defn stack-push-at?
  "Whether stack kernel k's pc is at a `:push` stutter."
  [k]
  (and (= :stack (lane-of k))
       (kernel-active? k)
       (= :push (first (try (nth (:segment k) (:pc k))
                            (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))))


(def ^:dynamic *on-push*
  "When bound, called with a stack kernel stopped at a `:push` stutter
   before `kernel-tick` steps past it: law 4 lifts the stutter and
   compares it with its successor."
  nil)


(defn kernel-tick
  "One A-boundary of kernel k: `vm/step` once — an instruction, or a
   scheduler round that sweeps and resumes one entry — stepping once
   more past a `:push` stutter, whose state is its successor's but for
   pc (`stack.cljc`: a `:push` only advances pc)."
  [k]
  (let [k1 (vm/step k)]
    (if (and (kernel-active? k1) (stack-push-at? k1))
      (do (when *on-push* (*on-push* k1))
          (vm/step k1))
      k1)))


(defn lane-drive
  "The stepping driver of §5.7 on any lane: `run-fuel 1` boundaries on
   the reference lane, one A-boundary per tick on the kernels —
   re-dispatched on the machine it is handed, so an observer that
   replaces the machine with another lane's receiver (the isolated
   law-1 replays) keeps driving on the receiver's own runner."
  [m observe]
  (loop [m (observe m) n 0]
    (when (> n 20000) (throw (ex-info "Lane boundary driver failed to stop" {:steps n})))
    (let [next (observe (if (= :semantic (lane-of m)) (sr/run-fuel m 1) (kernel-tick m)))]
      (if (if (= :semantic (lane-of next))
            (active-tree? next)
            (or (kernel-active? next) (seq (:ready-queue next))))
        (recur next (inc n))
        next))))


(defn running-boundary?
  "Whether m is at a running boundary: control on the reference lane, a
   live activation on a kernel."
  [m]
  (if (= :semantic (lane-of m)) (some? (:control m)) (kernel-active? m)))


(defn boundary-site
  "`[A pc]` a running boundary stops at: the control's on the reference
   lane, the running lift's on a kernel (a `:push` stutter relabelled by
   `h->a`)."
  [m]
  (if (= :semantic (lane-of m))
    (when-let [{:keys [segment pc]} (:control m)]
      (let [v (get-in m [:code segment :vector])]
        (when (and v (< pc (count v))) [(jing/segment-key v) pc])))
    (let [run (layout/lift-running
                (layout/lift-context m (kernel-space m) (:owner m))
                (select-keys m (if (= :stack (lane-of m))
                                 [:pc :stack :frames :continuation :store-of]
                                 [:pc :registers :frames :continuation :store-of])))]
      [(:segment run) (:pc run)])))


(defn boundary-pc
  "The A pc of `boundary-site`."
  [m]
  (second (boundary-site m)))


(defn boundary-marker
  "The alignment marker of a stopped boundary."
  [m]
  (cond (vm/halted? m) :halt (seq (:wait-set m)) :park :else :inactive))


(defn boundary-record
  "One returned boundary of m, lifted: `{:tag :root :root id :serves n
   :site [A p]}` or `{:tag :refusal :refusal {status kind} :serves n
   :site [A p]}` at a running boundary, else a park/halt marker
   `{:tag :park}`. A running lift that refuses before naming its site
   records the refusal with `:site nil`: the lanes' alignment then fails
   loudly, rather than the driver throwing."
  [m]
  (if (running-boundary? m)
    (let [{:keys [serve! peer]} (served #{:reader :writer})
          n (atom 0)
          [site w d] (try (let [site (boundary-site m)]
                            (try [site (lane-lift m (fn [x] (swap! n inc) (serve! x))) nil]
                                 (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                   [site nil (ex-data e)])))
                          (catch #?(:cljd Object :clj Throwable :cljs :default) e
                            [nil nil (ex-data e)]))]
      (if (some? d)
        {:tag :refusal :refusal (select-keys d [:yin.k/status :yin.k/kind]) :serves @n :site site}
        {:tag :root :root (task-root (rows/decode w) m peer) :serves @n :site site}))
    {:tag (boundary-marker m)}))


;; :cljd first — cljd lacks with-redefs; the aligned-boundaries deftests
;; are cljd-gated, so no cljd caller reaches the real body
#?(:cljd (defn lane-root-records
           [& _]
           nil)
   :default
   (defn lane-root-records
     "Law 4's input: one lane's world driven under the stepping driver,
     every returned boundary lifted (`boundary-record`). On the stack
     lane each `:push` stutter the driver steps past is lifted as well
     and compared with the boundary that follows it, its successor: the
     relabel must give the same site and the same root. Answers
     [reference-run records relabels], each relabel `{:site same? :root
     same?}`."
     [lane build]
     (scoped
       (fn []
         (let [records (atom [])
               pending (atom nil)
               relabels (atom [])
               observe (fn [m]
                         (let [r (boundary-record m)]
                           (when-let [p @pending]
                             (swap! relabels conj {:site (= (:site p) (:site r)) :root (= (:root p) (:root r))})
                             (reset! pending nil))
                           (swap! records conj r))
                         m)
               ref (binding [*on-push* #(reset! pending (boundary-record %))]
                     (with-redefs [vm/run #(lane-drive % observe)]
                       (reference (on-lane lane build))))]
           (is (nil? @pending) "every stutter was followed by its successor")
           [ref @records @relabels])))))


(defn alignment-sequence
  "The comparable form of one lane's records: the `[A pc]` at each
   running boundary, the marker at each stopped one."
  [records]
  (mapv (fn [r] (if (contains? r :site) (:site r) (:tag r))) records))


(defn branch-coverage
  "`{:taken n :untaken n}` over one lane's records: a running boundary
   at a `:branch-false` whose next boundary is its fall-through
   successor is untaken (the test held), any other next site is taken."
  [records]
  (reduce (fn [acc [r r']]
            (let [[a pc] (:site r)
                  t (when a (get-in @code-index [a pc]))]
              (if (and t (= :branch-false (nth t 0)) (:site r'))
                (update acc (if (= [a (inc pc)] (:site r')) :untaken :taken) inc)
                acc)))
          {:taken 0 :untaken 0}
          (partition 2 1 records)))


(defn lane-transfer-opts
  "`direct-transfer`'s receiver opts off boundary machine m, lane-aware:
   a kernel source's plain primitive table would not name the carrier's
   :prim rows in the semantic scratch, so the portable table does (a
   kernel receiver takes `vm/primitives` by `kernel-opts` regardless)."
  [m]
  (merge (select-keys m [:modules :primitive-profiles :primitive-canonical-names
                         :make-stream :capability-secret :secret-source])
         {:primitives (if (= :semantic (lane-of m)) (:primitives m) portable-stream-primitives)
          :call-in (get (:resources m) vm/call-in-stream-key)
          :call-out (get (:resources m) vm/call-out-stream-key)
          :call-out-cursor (:cursor (get (:resources m) vm/call-out-cursor-key))
          :link-request (get (:resources m) module/link-request-resource)
          :link-response (get (:resources m) module/link-response-resource)}))


(defn lane-direct-transfer
  "`direct-transfer`, lane-generic: lift m on its own lane over a fresh
   serve-table whose handles the receiver re-attaches, lower into a
   fresh receiver of lane `to`, relift at once (law 2, byte equality).
   Answers {:keys [recv wire relift defect attaches]}; `defect` is the
   lift's or the lower's refusal ex-data, with zero attaches."
  [m to]
  (let [handles (atom {})
        base (s/serve-table)
        serve! (fn [h]
                 (let [d (base h)]
                   (swap! handles assoc (:dao.stream/identity d) h) d))
        n (atom 0)
        attach! (fn [d]
                  (swap! n inc)
                  {:dao.stream/outcome :dao.stream/ok
                   :dao.stream/handle (get @handles (:dao.stream/identity d))})
        [wire lift-defect] (try [(lane-lift m serve!)]
                                (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                  [nil (ex-data e)]))]
    (if (some? lift-defect)
      {:defect lift-defect :attaches @n}
      (try (let [recv (lane-lower to (lane-transfer-opts m) wire attach!)
                 relift (lane-lift recv serve!)]
             {:recv recv :wire wire :relift relift :attaches @n})
           (catch #?(:cljd Object :clj Throwable :cljs :default) e
             {:defect (ex-data e) :wire wire :attaches @n})))))


;; — §3.6's refusal oracle: an independent traversal over the decoded
;; task, written here rather than shared with layout/lower-defect —

(defn- oracle-lexical-names
  "The names the chain of the body holding pc binds (a test-local copy
   of the static rule; the free part of an env is the free environment,
   never payload data)."
  [v pc]
  (let [bodies (code/body-ranges v)]
    (loop [b (code/body-index bodies pc) out #{}]
      (if-let [owner (:owner (nth bodies b))]
        (recur (code/body-index bodies owner) (into out (nth (nth v owner) 2)))
        out))))


(declare oracle-state-prim)


(defn- oracle-value-prim
  "The path to the first :prim row value x reaches through guest
   collections, a closure's lexical bindings or a reified state; or nil."
  [code x path]
  (when (and (vector? x) (= :row (first x)))
    (let [n (second x) ss (:slots n)]
      (case (:kind n)
        :prim path
        (:vec :list :set :map) (some (fn [[i y]] (oracle-value-prim code y (conj path i)))
                                     (map-indexed vector ss))
        :closure (let [[a entry _ bindings] ss
                       v (get code a)
                       owner (when v (some (fn [p]
                                             (let [t (nth v p)]
                                               (when (and (= :closure (first t)) (= entry (nth t 3))) p)))
                                           (range (count v))))
                       names (if owner (oracle-lexical-names v owner) #{})]
                   (some (fn [[s y]] (when (contains? names s) (oracle-value-prim code y (conj path s))))
                         (partition 2 (:slots bindings))))
        :reified (oracle-state-prim code (first ss) (conj path 0))
        nil))))


(defn- oracle-frame-prim
  [code a pc e w path]
  (let [names (oracle-lexical-names (get code a) pc)]
    (or (some (fn [[r y]] (oracle-value-prim code y (conj path :window r))) (partition 2 (:slots w)))
        (some (fn [[sym y]] (when (contains? names sym) (oracle-value-prim code y (conj path :env sym))))
              (partition 2 (:slots (first (:slots e))))))))


(defn- oracle-state-prim
  [code n path]
  (let [kframes (fn [k]
                  (some (fn [[i f]]
                          (let [[a pc e w] (:slots f)]
                            (oracle-frame-prim code a pc e w (conj path :k i))))
                        (map-indexed vector (:slots k))))
        [a pc e w k] (:slots n)]
    (case (:kind n)
      (:act :run) (or (oracle-frame-prim code a pc e w path) (kframes k))
      :tail (kframes e)
      nil)))


(defn oracle-refusal
  "§3.6's oracle: the `[:yin.k/unsupported :kind]` a receiver on lane
   `to` owes for decoded task `task`, or nil when it transfers. A de
   Bruijn receiver refuses an outstanding retained FFI request (the
   documented limit); a register receiver also refuses a primitive
   function reachable from the run row's window or lexical bindings, any
   K frame's, any wait's or ready's state, a ready outcome or refusal
   value, a value-bearing completion field, or a parked state. The
   reference lane refuses none of these."
  [task to]
  (let [slots (:slots task)
        code (nth slots 11)
        prim-path (fn []
                    (or (some (fn [[i r]] (oracle-state-prim code r [:run i]))
                              (map-indexed vector (nth slots 17)))
                        (some (fn [[i w]] (oracle-state-prim code (first (:slots w)) [:frames i]))
                              (map-indexed vector (nth slots 13)))
                        (some (fn [[i r]]
                                (let [[st c v _ _ refusal] (:slots r)]
                                  (or (oracle-state-prim code st [:ready i :state])
                                      (oracle-value-prim code v [:ready i :value])
                                      (when refusal (oracle-value-prim code refusal [:ready i :refusal]))
                                      (some (fn [[j d x]]
                                              (when (= :V d) (oracle-value-prim code x [:ready i :completion j])))
                                            (map vector (range) (into [:D] (get rows/completion-table (first (:slots c))))
                                                 (:slots c))))))
                              (map-indexed vector (nth slots 18)))
                        (some (fn [[p st]] (oracle-state-prim code st [:parked p]))
                              (partition 2 (:slots (nth slots 8))))))]
    (cond
      (= :semantic to) nil

      (some (fn [w] (= :ffi-request (first (:slots (second (:slots w))))))
            (nth slots 13))
      [:yin.k/unsupported :retained-ffi]

      (and (= :register to) (prim-path)) [:yin.k/unsupported :continuation-registers]

      :else nil)))


(defn aligned-law-4
  "Law 4 at every aligned boundary of one case: the three lanes'
   boundary sequences align (the stack lane's `:push` stutters
   relabelled by the lift), one root id at every measured boundary,
   every refusal a leading discharge refusal shared by all three lanes
   with zero serves, each lane's own run answering the reference, and
   every stack `:push` stutter lifting to its successor's site and root.
   Answers `{:boundaries n :refusals n :relabels n :branches {:taken n
   :untaken n}}`, the reference lane's counts (the stack lane's for the
   relabels)."
  [build expected]
  (let [runs (mapv #(lane-root-records % build) lanes)
        recs (mapv second runs)
        seqs (mapv alignment-sequence recs)
        relabels (nth (nth runs 1) 2)]
    (doseq [[lane ref] (map vector lanes (mapv first runs))]
      (is (:halted ref) (str (name lane) " completes"))
      (when (some? expected) (is (= expected (:value ref)) (str (name lane) " answers the reference"))))
    (is (pos? (count (first seqs))) "the driver observed boundaries")
    (is (apply = seqs)
        "the three lanes' boundaries align, the stack lane's :push stutters relabelled")
    (is (every? #(= {:site true :root true} %) relabels)
        "every :push stutter lifts to its successor's site and root")
    (is (= (mapv branch-coverage recs) (repeat 3 (branch-coverage (first recs))))
        "the lanes take and leave the same branches")
    (dotimes [i (count (first seqs))]
      (let [rs (mapv #(nth % i) recs)
            tags (mapv :tag rs)]
        (cond
          (every? #(= :refusal %) tags)
          (do (is (apply = (mapv :refusal rs)) "the three lanes refuse together")
              (is (every? #(zero? (:serves %)) rs) "a leading refusal serves nothing")
              (is (every? #(= :refusal (:tag %)) (map #(nth (first recs) %) (range i)))
                  "the refusals form a contiguous leading run"))

          (every? #(= :root %) tags)
          (is (apply = (mapv :root rs))
              (str "law 4: one root id on every lane at boundary " i))

          (apply = tags) nil ; a shared park/halt/inactive marker: aligned

          :else (is false (str "boundary " i " disagrees in kind across lanes: "
                               (pr-str tags))))))
    {:boundaries (count (first recs))
     :refusals (count (filter #(= :refusal (:tag %)) (first recs)))
     :relabels (count relabels)
     :branches (branch-coverage (first recs))}))


(defn aligned-transfer-observer
  "The boundary observer of the law-5 replay: at boundary index `target`
   it transfers the machine into lane `to`, judged against §3.6's
   oracle — the exact refusal with zero attaches, or a byte-equal
   relift, one attach per distinct stream identity — records the
   outcome in `outcome` (`:transfer` or the refusal's kind) and the
   drive continues on the receiver (its world feeds it through the
   re-attached handles), or on the source after a refusal."
  [target to outcome]
  (let [i (atom -1)]
    (fn [m]
      (if (not= target (swap! i inc))
        m
        (let [{:keys [recv wire relift defect attaches]} (lane-direct-transfer m to)
              expected (when wire (oracle-refusal (rows/decode wire) to))]
          (is (some? wire) "the source lifts at every measured boundary")
          (cond
            (some? defect)
            (do (is (= expected [(:yin.k/status defect) (:yin.k/kind defect)])
                    (pr-str {:defect defect :expected expected :to to}))
                (is (zero? attaches) "the refusal attached nothing")
                (reset! outcome (:yin.k/kind defect))
                m)

            (some? expected)
            (do (is false (str "the oracle expected " (pr-str expected)
                               " but the " (name to) " receiver transferred"))
                (reset! outcome :unexpected-transfer)
                m)

            :else
            (do (is (= wire relift) (str "law 2: immediate relift, " (name to) " receiver"))
                (is (= (count (stream-identities wire)) attaches)
                    "one attach per distinct stream identity")
                (reset! outcome :transfer)
                recv)))))))


;; :cljd first — cljd lacks with-redefs; the aligned-boundaries deftests
;; are cljd-gated, so no cljd caller reaches the real body
#?(:cljd (defn aligned-transfer-run
           [& _]
           nil)
   :default
   (defn aligned-transfer-run
     "One replay of the case built on `from` (its reference outcome `ref`
     computed once per lane), transferring boundary `target` into lane
     `to` and continuing to the reference outcome in the same world.
     Answers the transfer's outcome (`:transfer` or the refusal's kind)."
     [from to build target ref]
     (scoped
       (fn []
         (let [outcome (atom nil)
               observe (aligned-transfer-observer target to outcome)
               got (with-redefs [vm/run #(lane-drive % observe)]
                     (reference (on-lane from build)))]
           (is (some? @outcome) (str "boundary " target " was reached on " (name from)))
           (is (= (select-keys ref [:halted :value :trace]) (select-keys got [:halted :value :trace]))
               (str "law 5: the transferred run continues to the reference, "
                    (name from) " boundary " target))
           @outcome)))))


(defn aligned-law-5
  "Law 5 at every boundary of one case: every measured boundary index
   runs one replay per ordered pair — the six pairs of §5.3 — under the
   full-payload oracle. Answers `{[from to] {outcome n}}`, the outcomes
   counted per direction."
  [build]
  (let [recs (second (lane-root-records :semantic build))
        measured (keep-indexed (fn [i r] (when (not= :refusal (:tag r)) i)) recs)]
    (into {}
          (map (fn [[from to]]
                 (let [ref (scoped (fn [] (reference (on-lane from build))))]
                   [[from to] (frequencies (mapv #(aligned-transfer-run from to build % ref) measured))])))
          ordered-pairs)))


(defn law-5-pin
  "The `{[from to] {outcome n}}` a case's pin implies: `measured`
   boundaries per direction, `refused` `{to {kind n}}` of them refused
   into `to` whichever lane they leave, every other one a transfer."
  [measured refused]
  (into {}
        (map (fn [[from to]]
               (let [r (get refused to {})]
                 [[from to] (cond-> r
                              (< (reduce + (vals r)) measured)
                              (assoc :transfer (- measured (reduce + (vals r)))))])))
        ordered-pairs))


(defn push-relabel-root
  "The root of stack kernel k's lift, stopped wherever it is (a `:push`
   stutter included), under the logical names of its own served toy."
  [k]
  (let [{:keys [serve! peer]} (served #{:reader :writer})]
    (task-root (rows/decode (lane-lift k serve!)) k peer)))


(deftest a-push-stutter-relabels-to-its-successor
  ;; the one-row pin of §4.3's relabel: a stack kernel stopped at a
  ;; `:push` (its state its successor's but for pc) lifts to the same
  ;; root as after executing the push, and its boundary pc is the
  ;; successor's A pc — no guest semantics run
  (scoped
    (fn []
      (let [k (loop [k (on-lane :stack #(load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1))))
                     n 0]
                (cond (> n 1000) (throw (ex-info "no :push reached" {}))
                      (stack-push-at? k) k
                      :else (recur (vm/step k) (inc n))))
            pc-at-stop (boundary-pc k)
            k' (vm/step k)
            pc-after (boundary-pc k')]
        (is (stack-push-at? k) "the kernel is stopped at a :push")
        (is (= pc-at-stop pc-after) "the stutter's A pc is its successor's")
        (is (not= (:pc k) (:pc k')) "the raw kernel pcs differ (nothing ran but the pc)")
        (is (= (push-relabel-root k) (push-relabel-root k'))
            "the lift relabels the stutter without running anything")))))


(defn aligned-case
  "Laws 4 and 5 at every aligned boundary of one case. The law-5 outcomes
   are judged exactly: per direction, every measured boundary transfers
   or refuses as the oracle says, and a boundary's outcome into `to` is
   the same whichever lane it leaves. Answers the case's measured pin
   `{:boundaries :refusals :relabels :branches :refused {to {kind n}}}`
   (`refusals` the leading discharge refusals, never transferred)."
  [build expected]
  (let [law-4 (aligned-law-4 build expected)
        law-5 (aligned-law-5 build)
        refused (into {}
                      (keep (fn [to]
                              (let [r (dissoc (get law-5 [:semantic to] (get law-5 [:stack to])) :transfer)]
                                (when (seq r) [to r]))))
                      lanes)
        measured (- (:boundaries law-4) (:refusals law-4))]
    (is (= (law-5-pin measured refused) law-5)
        "law 5: exact refusals and exact transfers per direction")
    (assoc law-4 :refused refused)))


(defn check-aligned-cases
  "Every case through `aligned-case`, each measured pin equal to `pins`'
   entry for `[row label]`. Answers the measured pins."
  [cases pins]
  (reduce (fn [acc [row label expected build]]
            (testing (str "row " row " " (name label))
              (let [got (aligned-case build expected)]
                (is (= (get pins [row label]) got) (pr-str [[row label] got]))
                (assoc acc [row label] got))))
          {}
          cases))


(defn- pin
  [boundaries refusals relabels [taken untaken] refused]
  {:boundaries boundaries :refusals refusals :relabels relabels
   :branches {:taken taken :untaken untaken} :refused refused})


(def aligned-quick-pins
  "The quick tier's measured pins (JVM): `[row label]` → `aligned-case`:
   boundaries, leading discharge refusals, `:push` relabels, [taken
   untaken] branches, and the exact refusals per receiving lane (every
   other measured boundary transfers, in all six directions)."
  {[1 :bare] (pin 42 0 20 [0 0] {:register {:continuation-registers 6}})
   [2 :bare] (pin 11 0 3 [0 0] {})
   [3 :bare] (pin 10 0 3 [0 0] {})
   [4 :bare] (pin 36 0 3 [0 0] {:stack {:retained-ffi 2} :register {:retained-ffi 2}})
   [5 :bare] (pin 28 0 14 [0 0] {:register {:continuation-registers 5}})
   [6 :bare] (pin 23 0 10 [0 0] {:register {:continuation-registers 2}})
   [13 :bare] (pin 10 0 2 [0 0] {})
   [14 :bare] (pin 39 0 20 [0 0] {:register {:continuation-registers 6}})
   [15 :bare] (pin 41 0 19 [1 1] {:register {:continuation-registers 6}})
   [16 :bare] (pin 51 0 28 [0 0] {:register {:continuation-registers 9}})
   [18 :bare] (pin 14 0 4 [0 0] {})
   [19 :bare] (pin 7 0 2 [0 0] {})
   [21 :bare] (pin 31 0 12 [0 0] {})
   [22 :bare] (pin 44 0 23 [0 0] {:register {:continuation-registers 6}})
   [24 :bare] (pin 10 0 3 [0 0] {})
   [20 :unwrapped] (pin 16 3 4 [0 0] {:register {:continuation-registers 2}})})


;; :cljd first — the aligned driver needs with-redefs, which ClojureDart
;; lacks; cljd keeps the park-boundary laws (bare-rows, cross-engine-*)
;; and the layout refusals
#?(:cljd nil
   :default
   (deftest aligned-boundaries-canonical-equality-bare
     ;; the quick-tier slice: every lane row once, bare, laws 4 and 5 at
     ;; every aligned boundary; row 20 included
     (let [cases (into (mapv (fn [[row r build]] [row :bare r #(build identity)]) lane-rows)
                       [[20 :unwrapped "B" tail-effect-halt]])
           got (check-aligned-cases cases aligned-quick-pins)
           total #(reduce + (map % (vals got)))]
       (is (= 16 (count cases)))
       (is (pos? (total (comp :taken :branches))) "a branch is taken")
       (is (pos? (total (comp :untaken :branches))) "a branch is left untaken")
       (is (pos? (total :relabels)) "the stack lane's stutters are relabelled")
       (is (pos? (total #(get-in % [:refused :register :continuation-registers] 0)))
           "the oracle's register refusals occur"))))


(def repeated-operand-ast
  "((fn [g] ((fn [x] (g x x)) 7)) (fn [a b] b)): one value in two
   operand positions of one call (two registers: linearize loads x
   twice)."
  (s/app (s/lam ['g] (s/app (s/lam ['x] (s/app (s/v 'g) (s/v 'x) (s/v 'x))) (s/lit 7)))
         (s/lam ['a 'b] (s/v 'b))))


(def operand-tail-ast
  "An operand-position tail call whose enclosing application it abandons:
   ((fn [f] (f (tail (f 1)) 2)) (fn [z] z)) — the operator value pushed
   for the abandoned application is a dead prefix below the callee's
   region."
  (s/app (s/lam ['f]
                (s/app (s/v 'f) (tail (s/app (s/v 'f) (s/lit 1))) (s/lit 2)))
         (s/lam ['z] (s/v 'z))))


(def operand-tail-frame-ast
  "operand-tail-ast with a callee that makes a non-tail call:
   ((fn [f] (f (tail (f 1)) 2)) (fn [z] ((fn [w] w) z))) — the dead
   prefix lies in the return frame's slice while the inner call runs."
  (s/app (s/lam ['f]
                (s/app (s/v 'f) (tail (s/app (s/v 'f) (s/lit 1))) (s/lit 2)))
         (s/lam ['z] (s/app (s/lam ['w] (s/v 'w)) (s/v 'z)))))


(defn halting-world
  "A world that runs ast to its halt."
  [ast]
  (let [m (vm/run (load-program (machine) ast))]
    {:machine m :kind :halted :trace (atom [])}))


(def aligned-special-pins
  "The measured pins of the hand-built aligned cases (JVM)."
  {[:repeated-operand :hand-built] (pin 16 0 7 [0 0] {})
   [:operand-tail :hand-built] (pin 11 0 5 [0 0] {})
   [:operand-tail-frame :hand-built] (pin 15 0 7 [0 0] {})})


#?(:cljd nil
   :default
   (deftest aligned-hand-built-cases
     ;; laws 4 and 5 at every aligned boundary of the hand-built programs:
     ;; ordered operand multiplicity, and the dead-prefix rule end to end
     ;; (the stack lift drops the prefix, the lower produces none, the
     ;; three lanes agree at every boundary inside the callee, every pair
     ;; continues to the reference)
     (check-aligned-cases [[:repeated-operand :hand-built 7 #(halting-world repeated-operand-ast)]
                           [:operand-tail :hand-built 1 #(halting-world operand-tail-ast)]
                           [:operand-tail-frame :hand-built 1 #(halting-world operand-tail-frame-ast)]]
                          aligned-special-pins)))


(defn running-site-where
  "The first running boundary of lane's run of ast whose A instruction
   satisfies pred, or nil."
  [lane ast pred]
  (loop [m (on-lane lane #(load-program (machine) ast)) n 0]
    (let [[a pc] (when (running-boundary? m) (boundary-site m))]
      (cond (> n 1000) nil
            (and a (pred (get-in @code-index [a pc]))) m
            (running-boundary? m) (recur (if (= :semantic lane) (sr/run-fuel m 1) (kernel-tick m)) (inc n))
            :else nil))))


(deftest ordered-operand-multiplicity
  ;; law 4's ordered-multiplicity pin: at the call reading one value from
  ;; two operand registers, every lane's run window holds both registers
  ;; with agreeing values, the stack region holds the value twice, and
  ;; the three lanes lift to one root
  (scoped
    (fn []
      (let [three-operand-call? #(and (= :call (nth % 0)) (= 3 (count (code/uses %))))
            ms (mapv #(running-site-where % repeated-operand-ast three-operand-call?) lanes)
            roots (mapv push-relabel-root ms)]
        (is (every? some? ms) "every lane reaches the call")
        (doseq [[lane m] (map vector lanes ms)]
          (testing (name lane)
            (let [[a pc] (boundary-site m)
                  [_ r1 r2] (code/uses (get-in @code-index [a pc]))
                  {:keys [serve!]} (served #{:reader})
                  run (first (slot (root-task (lane-lift m serve!)) 17))
                  w (apply hash-map (:slots (nth (:slots run) 3)))]
              (is (not= r1 r2) "two operand registers")
              (is (contains? w r1))
              (is (contains? w r2))
              (is (= (get w r1) (get w r2)) "both positions hold the one value")
              (when (= :stack lane)
                (is (= [7 7] (vec (take-last 2 (:stack m)))) "the region holds the value twice")))))
        (is (apply = roots) "law 4: one root id on every lane")))))


(defn- crr
  "`{:register {:continuation-registers n}}`, the register receiver's
   primitive refusals, beside any other per-lane refusals."
  ([n] (crr n {}))
  ([n other] (assoc-in other [:register :continuation-registers] n)))


(def ^:private rffi
  {:stack {:retained-ffi 2} :register {:retained-ffi 2}})


(deftest backend-ready-root-refusal
  ;; §3.6's queued-outcome case on the backend lanes: no aligned corpus
  ;; boundary carries a ready entry (the driver resumes the queue in the
  ;; round that fills it), so a de Bruijn source is swept post-put, before
  ;; resume, and its completed :put's datom — the completion descriptor's
  ;; :V slot — holds the only unsupported primitive. Into the register
  ;; lane it refuses through the ready root with zero attaches; into the
  ;; other two lanes it transfers, byte-equal on relift; the oracle
  ;; predicts each outcome
  (doseq [from [:stack :register]]
    (testing (name from)
      (scoped
        (fn []
          (let [world (on-lane from #(writer identity))
                _ ((first (:steps world)))
                swept (engine/check-wait-set (:machine world))
                plus (vm/primitive-function (get vm/primitives '+))
                m (assoc-in swept [:ready-queue 0 :datom] {:nested [[plus]]})
                outcomes (into {}
                               (map (fn [to]
                                      (let [{:keys [wire relift defect attaches]} (lane-direct-transfer m to)
                                            task (rows/decode (or wire (lane-lift m (s/serve-table))))]
                                        [to {:expected (oracle-refusal task to)
                                             :refused (when defect [(:yin.k/status defect) (:yin.k/kind defect)])
                                             :path (:yin.k/path defect)
                                             :attaches attaches
                                             :relift-equal (when-not defect (= wire relift))
                                             :ready (count (slot task 18))}])))
                               lanes)]
            (is (= from (lane-of m)) "a de Bruijn source")
            (is (= 1 (count (:ready-queue m))) "one queued outcome, before resume")
            (is (empty? (:wait-set m)) "the put's wait completed into the queue")
            (doseq [[to o] outcomes]
              (testing (str "-> " (name to))
                (is (= 1 (:ready o)) "the queued outcome travels in slot 18")
                (is (= (:expected o) (:refused o)) "the oracle predicts the outcome")))
            (is (= [:yin.k/unsupported :continuation-registers] (get-in outcomes [:register :refused])))
            (is (= [:ready 0 :completion] (take 3 (get-in outcomes [:register :path])))
                "refused through the ready root's completion field")
            (is (zero? (get-in outcomes [:register :attaches])) "refused before any attach")
            (is (= [true true] (mapv #(get-in outcomes [% :relift-equal]) [:semantic :stack]))
                "the other lanes transfer, byte-equal on relift")))))))


(def aligned-slow-pins
  "The slow tier's measured pins (JVM): `[row label]` → `aligned-case`,
   as `aligned-quick-pins`."
  {[1 :bare] (pin 42 0 20 [0 0] (crr 6))
   [1 :operand] (pin 50 0 26 [0 0] (crr 9))
   [1 :depth-3] (pin 78 0 44 [0 0] (crr 15))
   [1 :captured] (pin 78 0 44 [0 0] (crr 15))
   [2 :bare] (pin 11 0 3 [0 0] {})
   [2 :operand] (pin 19 0 9 [0 0] (crr 3))
   [2 :depth-3] (pin 47 0 27 [0 0] (crr 9))
   [2 :captured] (pin 47 0 27 [0 0] (crr 9))
   [3 :bare] (pin 10 0 3 [0 0] {})
   [3 :operand] (pin 18 0 9 [0 0] (crr 3))
   [3 :depth-3] (pin 46 0 27 [0 0] (crr 9))
   [3 :captured] (pin 46 0 27 [0 0] (crr 9))
   [4 :bare] (pin 36 0 3 [0 0] rffi)
   [4 :operand] (pin 44 0 9 [0 0] (crr 3 rffi))
   [4 :depth-3] (pin 72 0 27 [0 0] (crr 9 rffi))
   [4 :captured] (pin 72 0 27 [0 0] (crr 9 rffi))
   [5 :bare] (pin 28 0 14 [0 0] (crr 5))
   [5 :operand] (pin 36 0 20 [0 0] (crr 8))
   [5 :depth-3] (pin 64 0 38 [0 0] (crr 14))
   [5 :captured] (pin 64 0 38 [0 0] (crr 14))
   [6 :bare] (pin 23 0 10 [0 0] (crr 2))
   [6 :operand] (pin 31 0 16 [0 0] (crr 5))
   [6 :depth-3] (pin 59 0 34 [0 0] (crr 11))
   [6 :captured] (pin 59 0 34 [0 0] (crr 11))
   [13 :bare] (pin 10 0 2 [0 0] {})
   [13 :operand] (pin 18 0 8 [0 0] (crr 3))
   [13 :depth-3] (pin 46 0 26 [0 0] (crr 9))
   [13 :captured] (pin 46 0 26 [0 0] (crr 9))
   [14 :bare] (pin 39 0 20 [0 0] (crr 6))
   [14 :operand] (pin 47 0 26 [0 0] (crr 9))
   [14 :depth-3] (pin 75 0 44 [0 0] (crr 15))
   [14 :captured] (pin 75 0 44 [0 0] (crr 15))
   [15 :bare] (pin 41 0 19 [1 1] (crr 6))
   [15 :operand] (pin 49 0 25 [1 1] (crr 9))
   [15 :depth-3] (pin 77 0 43 [1 1] (crr 15))
   [15 :captured] (pin 77 0 43 [1 1] (crr 15))
   [16 :bare] (pin 51 0 28 [0 0] (crr 9))
   [16 :operand] (pin 59 0 34 [0 0] (crr 12))
   [16 :depth-3] (pin 87 0 52 [0 0] (crr 18))
   [16 :captured] (pin 87 0 52 [0 0] (crr 18))
   [18 :bare] (pin 14 0 4 [0 0] {})
   [18 :operand] (pin 22 0 10 [0 0] (crr 3))
   [18 :depth-3] (pin 50 0 28 [0 0] (crr 9))
   [18 :captured] (pin 50 0 28 [0 0] (crr 9))
   [19 :bare] (pin 7 0 2 [0 0] {})
   [19 :operand] (pin 15 0 8 [0 0] (crr 3))
   [19 :depth-3] (pin 43 0 26 [0 0] (crr 9))
   [19 :captured] (pin 43 0 26 [0 0] (crr 9))
   [21 :bare] (pin 31 0 12 [0 0] {})
   [21 :operand] (pin 39 0 18 [0 0] (crr 3))
   [21 :depth-3] (pin 67 0 36 [0 0] (crr 9))
   [21 :captured] (pin 67 0 36 [0 0] (crr 9))
   [22 :bare] (pin 44 0 23 [0 0] (crr 6))
   [22 :operand] (pin 52 0 29 [0 0] (crr 9))
   [22 :depth-3] (pin 80 0 47 [0 0] (crr 15))
   [22 :captured] (pin 80 0 47 [0 0] (crr 15))
   [24 :bare] (pin 10 0 3 [0 0] {})
   [24 :operand] (pin 18 0 9 [0 0] (crr 3))
   [24 :depth-3] (pin 46 0 27 [0 0] (crr 9))
   [24 :captured] (pin 46 0 27 [0 0] (crr 9))
   [20 :unwrapped] (pin 16 3 4 [0 0] (crr 2))})


#?(:cljd nil
   :default
   (deftest ^:slow aligned-boundaries-canonical-equality
     ;; acceptance: laws 4 and 5 at every aligned boundary of §5.3's shared
     ;; fragment, 61 cases (15 lane rows x 4 segments + row 20)
     (slow/guard
       "aligned-boundaries-canonical-equality"
       (fn []
         (let [cases (lane-cases)]
           (is (= 61 (count cases)))
           (is (= 61 (count (check-aligned-cases cases aligned-slow-pins)))))))))


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
  (testing "a kernel stepped to a non-parking pc lifts as :running on both lanes"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (scoped
          (fn []
            (let [m (on-lane lane #(vm/step (load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1)))))
                  n (atom 0)
                  {:keys [serve!]} (served #{:reader})
                  w (lane-lift m (fn [h] (swap! n inc) (serve! h)))
                  t (root-task w)
                  run (first (slot t 17))]
              (is (not (vm/halted? m)))
              (is (not (vm/blocked? m)) "the kernel is between instructions")
              (is (zero? @n) "the program holds no stream: nothing was served")
              (is (= :running (slot t 2)) "kind :running")
              (is (= 1 (count (slot t 17))) "exactly one run row")
              (is (nil? (rows/validate-frames t)) "law 6 on the reconstructed run")
              (is (pos? (nth (:slots run) 1)) "the run row's pc is past the body's entry")
              (is (= (boundary-pc m) (nth (:slots run) 1))
                  "the row's pc is the A pc the kernel stopped at (a :push relabelled)")))))))
  (testing "a hand-built entry at a pc no delivery state of A occupies refuses :yin.k/not-at-safepoint"
    (scoped
      (fn []
        (let [m (on-lane :stack #(load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1))))
              sp (kernel-space m)
              [[_a seg]] (sort-by (comp str key) (:segments sp))
              v (:vector seg)
              body-starts (mapv :start (code/body-ranges v))
              edge (second body-starts)
              off (layout/offset :stack m seg)
              cx (layout/lift-context m sp (:owner m))
              entry (assoc (select-keys m [:pc :frames :stack :continuation])
                           :pc (+ off (nth (:a->h seg) edge))
                           :frames [[nil]]
                           :stack (vec (repeat (count (layout/stack-layout seg edge)) nil)))
              d (error #(layout/lift-state cx entry))
              run (layout/lift-running cx entry)]
          (is (< 1 (count body-starts)) "the fixture has a closure body")
          (is (pos? edge))
          (is (= :yin.k/not-at-safepoint (:yin.k/status d)) (pr-str d))
          (is (= edge (:yin.k/pc d)) "the refusing pc, named")
          (testing ", the same pc lifts as a run state (any pc inside a body)"
            (is (= edge (:pc run)))
            (is (map? (:window run)))
            (is (= (count (layout/stack-layout seg edge)) (count (:window run)))
                "the run window over the edge pc's live set"))))))
  (testing "a running kernel at a pc outside every body refuses :yin.k/not-at-safepoint, serving nothing"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (scoped
          (fn []
            (let [m0 (on-lane lane #(load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1))))
                  [[_a seg]] (seq (:segments (kernel-space m0)))
                  end (+ (layout/offset lane m0 seg)
                         (count (if (= :stack lane) (:stack seg) (:instructions (:register seg)))))
                  m (assoc m0 :pc end)
                  n (atom 0)
                  {:keys [serve!]} (served #{:reader})
                  d (error #(lane-lift m (fn [h] (swap! n inc) (serve! h))))]
              (is (kernel-active? m) "the hand-built kernel is between instructions")
              (is (= :yin.k/not-at-safepoint (:yin.k/status d)) (pr-str d))
              (is (zero? @n) "nothing was served")))))))
  (testing "a delivery entry whose parking instruction would be a :push refuses: the relabel is the run mode's only"
    (scoped
      (fn []
        (let [m (on-lane :stack #(load-program (machine) (s/app (s/lam ['p] (s/lit 42)) (s/lit 1))))
              sp (kernel-space m)
              [[_a seg]] (seq (:segments sp))
              push (first (filter #(= :push (nth (nth (:stack seg) %) 0)) (range (count (:stack seg)))))
              cx (layout/lift-context m sp (:owner m))
              entry (assoc (select-keys m [:frames :stack :continuation])
                           :pc (+ (layout/offset :stack m seg) push 1))
              d (error #(layout/lift-state cx entry))]
          (is (some? push) "the image holds a :push")
          (is (= :yin.k/not-at-safepoint (:yin.k/status d)) (pr-str d))
          (is (= push (:yin.k/pc d)) "the :push's own pc, named")))))
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


(defn try-wrapper
  [try?]
  (if try? #(s/let1 'require (s/v 'try-require) %) identity))


(deftest link-refusal-keeps-each-return-contract-after-handoff
  (doseq [try? [true false] tail? [false true] retained? [false true]]
    (testing (pr-str {:try? try? :tail? tail? :retained? retained?})
      (let [world (linking (try-wrapper try?) tail? retained?)
            m (:machine world)
            entry (first (:wait-set m))
            response (get (:resources m) module/link-response-resource)
            refusal {:yin.link/id (:link-id entry) :status :refused :reason :absent}
            answer! #(stream/append! response refusal)
            world (assoc world :steps (if retained? [(first (:steps world)) answer!] [answer!]))]
        (is (= try? (boolean (:try? entry))) "the source waiter has the requested contract")
        (if try?
          (let [{:keys [halted value recv wire]} (handed-off world)]
            (is halted)
            (is (= (assoc refusal :module 'host.mod) value))
            (is (true? (:try? (first (:wait-set recv)))))
            (is (= wire (rows/emit (rows/decode wire))) "pack round trip includes the flag"))
          (let [failure (error #(handed-off world))]
            (is (= :absent (:reason failure)))
            (is (= 'host.mod (:link-module failure)))))))))


(deftest install-return-contract-and-link-flag-validation
  (doseq [try? [true false]]
    (let [world (installing (try-wrapper try?) false nil)
          {:keys [toy serve!]} (served #{:reader :writer})
          wire (rows/lift-task (:machine world) serve! (lift-opts))
          pending (first-pending (root-task wire))
          attach! (s/remote-attacher toy)
          recv (rows/lower-task (machine) wire attach! install-lower-opts)
          abandoned (engine/abandon-installs recv :test-abandoned)]
      (is (= [:install 'host.mod try?] (:slots pending)))
      (is (= try? (:try? (first (:wait-set recv)))))
      (is (= wire (rows/emit (rows/decode wire))))
      (if try?
        (is (= {:status :lost :reason :test-abandoned}
               (select-keys (vm/value (vm/run abandoned)) [:status :reason])))
        (is (= :test-abandoned (:reason (error #(vm/run abandoned))))))))
  (doseq [build [#(linking identity false true)
                 #(linking identity false false)
                 #(installing identity false nil)]
          malformed [nil :true 1 "true"]]
    (let [{:keys [serve!]} (served #{:reader :writer})
          wire (rows/lift-task (:machine (build)) serve! (lift-opts))
          bad (rows/emit (update-at (root-task wire) [13 0 1]
                                    #(update % :slots (fn [xs] (assoc xs (dec (count xs)) malformed)))))
          attached (atom 0)
          result (error #(rows/lower-task (machine) bad (fn [_] (swap! attached inc))))]
      (is (= :yin.k/undecodable (:yin.k/status result)))
      (is (= :pending-try (:yin.k/kind result)))
      (is (zero? @attached) "malformed flags are refused before resource attachment"))))


(deftest park-beside-a-live-reader-wait
  (let [[reader src] (parked-reader)
        parked (run-beside (assoc reader :env (:free-env reader)) {:type :vm/park})
        pid (:id (vm/value parked))
        {:keys [toy peer serve!]} (served #{:reader})
        wire (rows/lift-task parked serve! (lift-opts))
        {:keys [attach!]} (recording-attacher toy)
        recv (rows/lower-task (machine {:attach-stream attach!}) wire attach!)
        resumed (run-beside recv {:type :vm/resume :parked-id pid :val (s/lit "unparked")})]
    (is (= 1 (count (:wait-set recv))) "the reader survives beside the park")
    (is (contains? (:parked recv) pid) "the explicit park survives independently")
    (is (= "unparked" (vm/value resumed)))
    (is (not (contains? (:parked resumed) pid)))
    (is (= 1 (count (:wait-set resumed))) "resuming the park does not consume the reader")
    (stream/append! src "B")
    (let [woken (sweep resumed [peer])
          done (vm/run woken)]
      (is (empty? (:wait-set done)))
      (is (vm/halted? done))
      (is (= "B" (vm/value done))))))


(deftest noise-before-the-correlated-ffi-response
  (let [call-in (s/ring 8) call-out (s/ring 8)
        m (caller identity call-in call-out)
        id (ffi/response-call-id (first (:wait-set m)))
        {:keys [toy peer serve!]} (served #{:reader})
        wire (rows/lift-task m serve! (lift-opts))
        {:keys [attach!]} (recording-attacher toy)
        own-in (s/ring 8) own-out (s/ring 8)
        recv (rows/lower-task (machine {:attach-stream attach! :call-in own-in
                                        :call-out own-out :call-out-cursor (vm/mint-oldest own-out :test)})
                              wire attach!)]
    (stream/append! call-out (apply2/success-response :unrelated "noise"))
    (let [waiting (drive-peers recv [peer])]
      (is (vm/blocked? waiting) "an unrelated response cannot wake this call")
      (is (= 1 (count (:wait-set waiting))))
      (is (= id (ffi/response-call-id (first (:wait-set waiting)))))
      (stream/append! call-out (apply2/success-response id "echo!"))
      (let [done (drive-peers waiting [peer])]
        (is (empty? (:wait-set done)))
        (is (vm/halted? done))
        (is (= "echo!" (vm/value done)))
        (is (empty? (tu/drain own-in)) "the carried request is not sent again")))))


(defn receiver-admission
  [m]
  {:yin.k/admitted-modules
   (into {} (mapcat (fn collect
                      [m]
                      (mapcat (fn [[n inst]]
                                (cons [n (jing/segment-key (get-in inst [:response :manifest]))]
                                      (collect (:vm inst)))) (:installs m)))) [m])})


(defn check-window-tampers
  "Law 6: independently corrupt a carried window, then require refusal
   before any stream attachment. Empty windows have no removable live key."
  [task m]
  (let [states (atom [])]
    (letfn [(visit
              [x code-map]
              (let [code-map (if (= :task (:kind x)) (slot x 11) code-map)]
                (when (contains? #{:run :act :kframe} (:kind x))
                  (swap! states conj [x code-map]))
                (cond
                  (:slots x) (doseq [y (:slots x)] (visit y code-map))
                  (vector? x) (doseq [y x] (visit y code-map)))))]
      (visit task nil))
    (doseq [[state code-map] @states
            :let [[a pc _ w] (:slots state)
                  v (get code-map a)
                  body (nth (code/parse v) (code/body-index (code/body-ranges v) pc))
                  prefix (if (= :kframe (:kind state)) "return-" "")]
            [tamper expected]
            (cond-> [[#(update % :slots into [(:registers body) [:lit :extra]])
                      (keyword (str prefix "window-scope"))]]
              (seq (:slots w))
              (conj [#(update % :slots (fn [xs] (subvec xs 2)))
                     (keyword (str prefix "window-missing"))]))]
      (let [changed (replace-nodes task #(identical? % state)
                                   #(update-in % [:slots 3] tamper))
            attaches (atom 0)
            d (rows/validate-frames changed)
            lower-refusal (error #(rows/lower-task (machine {:modules (:modules m)}) (rows/emit changed)
                                                   (fn [_] (swap! attaches inc)) (receiver-admission m)))]
        (is (= expected (:yin.k/kind d)) (pr-str d))
        (is (some? lower-refusal) "frame corruption cannot attach, even if census validation wins")
        (is (zero? @attaches))))))


(defn boundary-round-trip
  "Snapshot without execution, retaining the peer for later continuation."
  [m]
  (let [{:keys [toy peer serve!]} (served #{:reader :writer})
        wire (rows/lift-task m serve! (lift-opts))
        {:keys [attach! origin] n :count} (recording-attacher toy)
        recv (rows/lower-task (machine {:attach-stream attach! :modules (:modules m) :primitives (:primitives m)})
                              wire attach! (receiver-admission m))
        again (served #{:reader :writer})
        relift (rows/lift-task recv (:serve! again) (lift-opts))
        corr (into {} (map (fn [[id _]] [id id])) @(:table peer))
        corr2 (into {} (map (fn [[id e]] [id (get @origin (:handle e))])) @(:table (:peer again)))]
    (is (= (rows/canonical-under wire corr) (rows/canonical-under relift corr2)))
    (is (nil? (rows/validate-frames (root-task wire))))
    {:wire wire :recv recv :peer peer :origin @origin :attaches @n}))


(deftest completed-link-and-install-outcomes
  (doseq [install? [false true] try? [false true] success? [false true]]
    (testing (pr-str [install? try? success?])
      (let [world (if install? (installing (try-wrapper try?) false nil)
                      (linking (try-wrapper try?) false false))
            m (:machine world)
            completed (if install?
                        (if success?
                          (do ((first (:steps world))) (engine/check-wait-set m))
                          (engine/abandon-installs m :test-abandoned))
                        (let [entry (first (:wait-set m))
                              resp (get (:resources m) module/link-response-resource)]
                          (if success?
                            ((first (:steps world)))
                            (stream/append! resp {:yin.link/id (:link-id entry) :status :refused :reason :absent}))
                          (engine/check-wait-set m)))
            {:keys [recv peer wire]} (boundary-round-trip completed)
            source-outcome (try {:value (normalize (vm/value (drive-local completed)))}
                                (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                  {:error (ex-data e)}))
            received-outcome (try {:value (normalize (vm/value (drive-peers recv [peer])))}
                                  (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                    {:error (ex-data e)}))]
        (is (empty? (:installs completed)))
        (is (seq (:ready-queue completed)))
        (is (= source-outcome received-outcome))
        (is (= 1 (count (slot (root-task wire) 18))))))))


(deftest task-products-and-ready-validation
  (doseq [both? [false true] active? [false true]]
    (let [[m call-out id] (two-callers)
          _ (if both? (answer-both! call-out id)
                (stream/append! call-out (apply2/success-response id "first")))
          swept (engine/check-wait-set m)
          m (if active? (sr/run-fuel swept 1) swept)
          {:keys [wire]} (boundary-round-trip m)
          t (root-task wire)]
      (is (= (if active? :running :blocked) (slot t 2)))
      (is (= (if both? 0 1) (count (slot t 13))))
      (is (= (if active? 1 0) (count (slot t 17))))
      (is (= (- (if both? 2 1) (if active? 1 0)) (count (slot t 18))))))
  (let [[m call-out id] (two-callers)
        _ (answer-both! call-out id)
        completed (engine/check-wait-set m)
        {:keys [wire]} (boundary-round-trip completed)
        t (root-task wire)]
    (is (= 1 (quot (count (:slots (slot t 9))) 2)) "one shared cursor identity")
    (is (= ["second" "first"] (mapv #(apply2/response-ok (:value %)) (:ready-queue completed))))
    (doseq [[mutate kind]
            [[#(assoc-in % [:slots 18 0 :slots 3] "invalid") :ready-outcome]
             [#(assoc-in % [:slots 18 0 :slots 5] [:lit :unexpected]) :ready-outcome]
             [#(assoc-in % [:slots 18 0 :slots 1 :slots 0] :unknown) :row-shape]
             [#(assoc-in % [:slots 18 0 :slots 4] (rows/node :cursor-cells [:missing (second (:slots (slot t 9)))])) :slot-kind]]]
      (let [n (atom 0)
            d (error #(rows/lower-task (machine) (rows/emit-numbered (mutate t) {}) (fn [_] (swap! n inc))))]
        (is (= kind (:yin.k/kind d)) (pr-str d))
        (is (zero? @n))))))


(defn active-tree?
  [m]
  (or (:control m) (seq (:ready-queue m))
      (some (fn [[_ inst]] (or (= :running (:phase inst)) (active-tree? (:vm inst)))) (:installs m))))


(defn fuel-drive
  "Drive to the next guest stop, observing and optionally replacing each boundary."
  [m observe]
  (loop [m (observe m) n 0]
    (when (> n 20000) (throw (ex-info "Boundary driver failed to stop" {:steps n})))
    (let [next (observe (sr/run-fuel m 1))]
      (if (active-tree? next)
        (recur next (inc n)) next))))


(defn direct-transfer
  "Transfer within one fresh replay world. Its handles are never used by
   another reference or replay world; only the abandoned source shares them."
  [m]
  (let [handles (atom {})
        base (s/serve-table)
        serve! (fn [h]
                 (let [d (base h)]
                   (swap! handles assoc (:dao.stream/identity d) h) d))
        wire (rows/lift-task m serve! (lift-opts))
        attach! (fn [d]
                  {:dao.stream/outcome :dao.stream/ok
                   :dao.stream/handle (get @handles (:dao.stream/identity d))})
        opts (merge (select-keys m [:modules :primitives :primitive-profiles :primitive-canonical-names
                                    :make-stream :capability-secret :secret-source])
                    {:call-in (get (:resources m) vm/call-in-stream-key)
                     :call-out (get (:resources m) vm/call-out-stream-key)
                     :call-out-cursor (:cursor (get (:resources m) vm/call-out-cursor-key))
                     :link-request (get (:resources m) module/link-request-resource)
                     :link-response (get (:resources m) module/link-response-resource)})
        recv (rows/lower-task (machine opts) wire attach! (receiver-admission m))
        relift (rows/lift-task recv serve! (lift-opts))]
    (is (= wire relift) "law 2: immediate relift, before any receiver execution")
    (is (nil? (rows/validate-frames (root-task wire))) "law 6")
    recv))


(defn boundary-description
  [m]
  (let [{seg :segment pc :pc} (:control m)]
    {:pc pc :body-start (when seg (let [v (get-in m [:code seg :vector])]
                                    (:start (nth (code/body-ranges v) (code/body-index (code/body-ranges v) pc)))))
     :instruction (when seg (nth (get-in m [:code seg :vector]) pc))
     ;; a linked module's store is keyed by its manifest address, the registry
     ;; entry's :address (engine/lift-slice, receive-module); an installing
     ;; module's exports answer discharge as well (rows/discharge!)
     :store-keys (into (into (set (keys (:store m)))
                             (mapcat (fn [[mn entry]]
                                       (map #(symbol (str mn) (str %))
                                            (keys (get (:module-stores m) (:address entry))))))
                             (module/module-entries (:modules m)))
                       (mapcat (fn [[mn inst]]
                                 (map #(symbol (name mn) (name %))
                                      (get-in inst [:response :manifest :yin.module/exports]))))
                       (:installs m))
     :active-plus-waits (and (:control m) (seq (:wait-set m)))
     :active-plus-ready (and (:control m) (seq (:ready-queue m)))
     :running-install-child (some :control (map :vm (vals (:installs m))))
     :stop (cond (:control m) :running
                 (seq (:parked m)) :parked
                 (seq (:wait-set m)) :waiting
                 (vm/halted? m) :halted :else :inactive)}))


(defn boundary-coverage
  [points]
  (into #{}
        (mapcat (fn [[p next]]
                  (let [t (:instruction p) op (first t)]
                    (cond-> []
                      (and (:pc p) (= (:pc p) (:body-start p))) (conj :body-entry)
                      (= op :branch-false) (conj (if (= (:pc next) (nth t 2)) :taken-branch :untaken-branch))
                      (= op :jump) (conj :jump)
                      (and (= op :call) (not (code/tail-call? t))) (conj :non-tail-call)
                      (= op :return) (conj :return)
                      (code/tail-call? t) (conj :tail-transition)
                      (= op :current-continuation) (conj :capture)
                      (= op :resume) (conj :resume)
                      (= op :park) (conj :park)
                      (= op :ffi-call) (conj :ffi-park)
                      (:active-plus-waits p) (conj :active-plus-waits)
                      (:active-plus-ready p) (conj :active-plus-ready)
                      (:running-install-child p) (conj :running-install-child)))))
        (map vector points (concat (rest points) [nil]))))


(defn structural-sample
  [points]
  (let [first-at (fn [p] (first (keep-indexed #(when (and (not (:excluded? %2)) (p %2)) %1) points)))]
    {:body-entry (first-at #(= (:pc %) (:body-start %)))
     :after-primitive (first (keep-indexed
                               (fn [i _p]
                                 (when (and (pos? i)
                                            (= :var (first (:instruction (nth points (dec i)))))
                                            (contains? portable-stream-primitives
                                                       (nth (:instruction (nth points (dec i))) 2))) i)) points))
     :before-park (first-at #(parking-site? (:instruction %)))
     :branch (first-at #(contains? #{:jump :branch-false} (first (:instruction %))))
     :last-return (last (keep-indexed #(when (= :return (first (:instruction %2))) %1) points))}))


;; :cljd first — cljd compiler NPE on multi-arity + destructuring; stub — JVM/cljs
;; use the real body; no cljd caller (gated)
#?(:cljd (defn check-returned-boundaries
           [& _]
           nil)
   :default
   (defn check-returned-boundaries
     ([cases] (check-returned-boundaries cases :all))
     ([cases execution]
      (mapv
        (fn [[row segment _ build]]
          (let [points (atom []) successful? (atom false)
                observe (fn [m]
                          (let [serves (atom 0)
                                d (error #(rows/lift-task m (fn [_] (swap! serves inc) nil) (lift-opts)))
                                ;; Use a working server to distinguish discharge refusals
                                ;; from the deliberately unavailable server above.
                                result (try {:snapshot (boundary-round-trip m)}
                                            (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                              {:refusal (ex-data e)}))
                                refusal (:refusal result)
                                excluded? (= [:yin.k/unsatisfied :incomplete]
                                             [(:yin.k/status refusal) (:yin.k/discovery refusal)])]
                            (when refusal
                              (is (and (not @successful?) excluded?) (pr-str refusal))
                              (when-not (and (not @successful?) excluded?)
                                (throw (ex-info "Refusal after first successful lift" refusal)))
                              (is (zero? @serves) "leading refusal serves nothing")
                              (is (= refusal d)))
                            (when-not refusal
                              (reset! successful? true)
                              (check-window-tampers (root-task (get-in result [:snapshot :wire])) m))
                            (swap! points conj (assoc (boundary-description m)
                                                      :excluded? (boolean refusal)
                                                      :obligations (get-in refusal [:yin.k/missing :obligations])))
                            m))
                expected (reference (build))
                result (with-redefs [vm/run #(fuel-drive % observe)] (reference (build)))
                excluded (filterv :excluded? @points)
                measured (keep-indexed #(when-not (:excluded? %2) %1) @points)]
            (is (= expected result) (str row " " segment))
            (doseq [[i p] (map-indexed vector @points) :when (:excluded? p)]
              (is (every? :excluded? (take (inc i) @points)))
              (is (seq (:obligations p)))
              (is (every? (apply set/union #{} (map :store-keys (drop (inc i) @points)))
                          (:obligations p)) "obligations are definitions later in this case"))
            (when (seq excluded)
              (let [first-measured (first (remove :excluded? @points))]
                (is (every? (:store-keys first-measured) (apply set/union (map :obligations excluded)))
                    "lifts from the first boundary at which its definitions are answered")
                (doseq [p excluded]
                  (is (not-any? (:store-keys p) (:obligations p)) "unanswered at the excluded boundary"))))
            (if (= row 20)
              ;; The ruling predicted [S0]; measured, the setup batch's first
              ;; s/def! stores s only after pcs 0-2 (conservative-prefixes-
              ;; refuse-before-serving pins the same three prefixes).
              (is (= [{:pc 0 :obligations #{'s}} {:pc 1 :obligations #{'s}} {:pc 2 :obligations #{'s}}]
                     (mapv #(select-keys % [:pc :obligations]) excluded)))
              (if (= row 17)
                (is (every? #(= #{'host.mod/f} (:obligations %)) excluded))
                (is (empty? excluded))))
            (when execution
              (doseq [target (if (and (= :sample execution) (not (contains? #{1 20} row)))
                               (sort (set (remove nil? (vals (structural-sample @points))))) measured)
                      :when (not (:excluded? (nth @points target)))]
                (let [i (atom -1)
                      replay (with-redefs [vm/run #(fuel-drive % (fn [m]
                                                                   (if (= target (swap! i inc))
                                                                     (direct-transfer m) m)))]
                               (reference (build)))]
                  (is (= expected replay) (str "isolated law 1 " [row segment target])))))
            (let [measurement {:row row :segment segment :measured (count measured)
                               :samples (structural-sample @points)
                               :coverage (boundary-coverage @points)
                               :excluded-leading (mapv #(select-keys % [:pc :obligations]) excluded)
                               :to-first-park (first (keep-indexed #(when (contains? #{:parked :waiting} (:stop %2)) (inc %1)) @points))
                               :to-final-halt (last (keep-indexed #(when (= :halted (:stop %2)) (inc %1)) @points))
                               :park (count (filter #(= :parked (:stop %)) @points))
                               :halt (count (filter #(= :halted (:stop %)) @points))}]
              (println "6d-a measured case:" (pr-str measurement))
              measurement))) cases))))


(def quick-boundary-pins
  "[row segment] -> [measured excluded-leading park halt], measured on JVM
   2026-10-16 (6d-a); coverage is pinned beside it."
  {[1 :bare] [42 0 0 1] [2 :bare] [11 0 0 1] [3 :bare] [10 0 2 1] [4 :bare] [36 0 28 1]
   [5 :bare] [28 0 0 1] [6 :bare] [23 0 0 1] [7 :bare] [39 0 0 1] [8 :bare] [13 0 0 1]
   [9 :bare] [16 0 0 1] [10 :bare] [21 0 0 1] [11 :bare] [20 0 0 1] [12 :bare] [24 0 0 1]
   [13 :bare] [10 0 1 1] [14 :bare] [39 0 0 1] [15 :bare] [41 0 0 1] [16 :bare] [51 0 0 1]
   [17 :bare] [18 16 0 1] [18 :bare] [14 0 1 1] [19 :bare] [7 0 0 1] [21 :bare] [31 0 0 1]
   [22 :bare] [44 0 0 1] [24 :bare] [10 0 2 1] [20 :unwrapped] [13 3 0 2]})


(def quick-boundary-coverage
  #{:body-entry :taken-branch :untaken-branch :jump :non-tail-call :return :tail-transition
    :capture :resume :park :ffi-park :running-install-child})


(defn running-beside-a-reader
  "Active control beside an outstanding reader wait. The beside activation
   blocks on its own stream before either answer arrives, so both waits
   wake in one sweep and no boundary halts beside an outstanding wait (that
   normalization is pinned separately)."
  []
  (let [src (s/ring 4) other (s/ring 4)
        [sref m] (engine/attach-resource (machine) src)
        [cref m] (engine/handle-cursor m {:stream sref} :reader-cursor)
        [oref m] (engine/attach-resource m other)
        [dref m] (engine/handle-cursor m {:stream oref} :beside-cursor)
        reader (vm/run (load-program (assoc m :store {'c cref 'd dref}) (s/then read-op read-op)))
        _ (stream/append! src "A")
        reader (vm/run reader)]
    {:machine (load-program (assoc reader :env (:free-env reader))
                            (s/then (s/app (s/v '+) (s/lit 1) (s/lit 2)) (s/next-of (s/v 'd))))
     :steps [(fn [] nil) #(do (stream/append! src "B") (stream/append! other "C"))] :trace (atom [])}))


(defn two-woken-callers
  "Two waits on one continuation, both answered: the first resume runs
   beside the second's queued outcome."
  []
  (let [call-in (s/ring 8) call-out (s/ring 8)
        m (vm/run (load-program (machine {:call-in call-in :call-out call-out
                                          :call-out-cursor (vm/mint-oldest call-out :test)})
                                (s/call-node :op/echo "hello")))
        e1 (first (:wait-set m))]
    {:machine (assoc m :wait-set [e1 (assoc e1 :call-id :parked-9)])
     :steps [#(answer-both! call-out (:call-id e1))] :trace (atom [])}))


(def product-driver-cases
  "Constructed starts the stepping driver runs from, for the §3.6 classes
   no corpus row reaches: [id segment expected build]."
  [[:active-plus-waits :product nil running-beside-a-reader]
   [:active-plus-ready :product nil two-woken-callers]])


(deftest product-returned-boundaries
  ;; :cljd first — the driver observes vm/run through with-redefs, which
  ;; ClojureDart lacks; cljd keeps the constructed products
  ;; (task-products-and-ready-validation, running-plus-ready-fenced-recovery)
  #?(:cljd nil
     :default
     (let [counts (check-returned-boundaries product-driver-cases :all)]
       (is (= {:active-plus-waits [49 0 0 1] :active-plus-ready [8 0 3 1]}
              (into {} (map (fn [c] [(:row c) [(:measured c) (count (:excluded-leading c)) (:park c) (:halt c)]]))
                    counts)))
       (doseq [c counts]
         (is (contains? (:coverage c) (:row c)) (pr-str c))))))


(deftest sampled-returned-boundaries
  ;; :cljd first — the driver needs with-redefs; cljd keeps the park-boundary
  ;; laws (bare-rows-match-the-reference) and conservative-prefixes-refuse-before-serving
  #?(:cljd nil
     :default
     (let [cases (filterv #(or (= :bare (second %)) (= 20 (first %))) (matrix-cases))
           counts (check-returned-boundaries cases :sample)]
       (is (= 23 (count counts)))
       (is (= quick-boundary-pins
              (into {} (map (fn [c]
                              [[(:row c) (:segment c)]
                               [(:measured c) (count (:excluded-leading c)) (:park c) (:halt c)]]))
                    counts)))
       (is (= quick-boundary-coverage (apply set/union (map :coverage counts)))
           "driver coverage; active-plus-waits/-ready are constructed products (§3.6)")
       (is (every? pos? (map :measured counts)))
       (is (every? #(= #{:body-entry :after-primitive :before-park :branch :last-return}
                       (set (keys (:samples %)))) counts))
       (println "6d-a quick boundary counts JVM:" (pr-str counts)))))


(deftest ^:slow every-returned-boundary
  ;; :cljd first — the driver needs with-redefs; cljd keeps the slow
  ;; every-row-every-segment-matches-the-reference park-boundary laws
  (slow/guard "every-returned-boundary"
              (fn []
                #?(:cljd nil
                   :default
                   (let [counts (check-returned-boundaries (matrix-cases))
                         got (into {} (map (fn [c]
                                             [[(:row c) (:segment c)]
                                              [(:measured c) (count (:excluded-leading c)) (:park c) (:halt c)]]))
                                   counts)]
                     (is (= 89 (count counts)))
                     ;; Measured on JVM 2026-10-16 (6d-a): every wrapper adds a fixed
                     ;; prefix to the bare row; only row 17 (host.mod/f before the
                     ;; link) and row 20 (s before the setup batch) exclude.
                     (is (= quick-boundary-pins (select-keys got (keys quick-boundary-pins))))
                     (is (= {[1 :operand] [50 0 0 1] [1 :depth-3] [78 0 0 1] [1 :captured] [78 0 0 1]
                             [2 :operand] [19 0 0 1] [2 :depth-3] [47 0 0 1] [2 :captured] [47 0 0 1]
                             [3 :operand] [18 0 2 1] [3 :depth-3] [46 0 2 1] [3 :captured] [46 0 2 1]
                             [4 :operand] [44 0 28 1] [4 :depth-3] [72 0 28 1] [4 :captured] [72 0 28 1]
                             [5 :operand] [36 0 0 1] [5 :depth-3] [64 0 0 1] [5 :captured] [64 0 0 1]
                             [6 :operand] [31 0 0 1] [6 :depth-3] [59 0 0 1] [6 :captured] [59 0 0 1]
                             [7 :operand] [47 0 0 1] [7 :depth-3] [75 0 0 1] [7 :captured] [75 0 0 1]
                             [8 :operand] [21 0 0 1] [8 :depth-3] [49 0 0 1] [8 :captured] [49 0 0 1]
                             [9 :operand] [24 0 0 1] [9 :depth-3] [52 0 0 1] [9 :captured] [52 0 0 1]
                             [10 :operand] [29 0 0 1] [10 :depth-3] [57 0 0 1] [10 :captured] [57 0 0 1]
                             [11 :operand] [28 0 0 1] [11 :depth-3] [56 0 0 1] [11 :captured] [56 0 0 1]
                             [12 :operand] [32 0 0 1] [12 :depth-3] [60 0 0 1] [12 :captured] [60 0 0 1]
                             [13 :operand] [18 0 1 1] [13 :depth-3] [46 0 1 1] [13 :captured] [46 0 1 1]
                             [14 :operand] [47 0 0 1] [14 :depth-3] [75 0 0 1] [14 :captured] [75 0 0 1]
                             [15 :operand] [49 0 0 1] [15 :depth-3] [77 0 0 1] [15 :captured] [77 0 0 1]
                             [16 :operand] [59 0 0 1] [16 :depth-3] [87 0 0 1] [16 :captured] [87 0 0 1]
                             [17 :operand] [24 18 0 1] [17 :depth-3] [39 31 0 1] [17 :captured] [27 43 0 1]
                             [18 :operand] [22 0 1 1] [18 :depth-3] [50 0 1 1] [18 :captured] [50 0 1 1]
                             [19 :operand] [15 0 0 1] [19 :depth-3] [43 0 0 1] [19 :captured] [43 0 0 1]
                             [21 :operand] [39 0 0 1] [21 :depth-3] [67 0 0 1] [21 :captured] [67 0 0 1]
                             [22 :operand] [52 0 0 1] [22 :depth-3] [80 0 0 1] [22 :captured] [80 0 0 1]
                             [24 :operand] [18 0 2 1] [24 :depth-3] [46 0 2 1] [24 :captured] [46 0 2 1]}
                            (apply dissoc got (keys quick-boundary-pins))))
                     (is (= quick-boundary-coverage (apply set/union (map :coverage counts))))
                     (println "6d-a returned-boundary counts JVM:" (pr-str counts)))))))


(deftest completed-put-history-and-shape-only-ids
  (doseq [op-id [nil (s/op-id 0)]]
    (let [world (writer identity)
          _ ((first (:steps world)))
          m (engine/check-wait-set (:machine world))
          m (cond-> m op-id (assoc-in [:ready-queue 0 :op-id] op-id))
          {:keys [wire recv peer]} (boundary-round-trip m)
          t (root-task wire)]
      (is (= {} (:yin.k/ops (rows/custody-baseline t))))
      (is (= "v" (vm/value (drive-peers recv [peer]))))
      (doseq [bad-id [{} (assoc (s/op-id 0) :yin.k/seq -1)]]
        (let [bad (assoc-in t [:slots 18 0 :slots 1 :slots 3] bad-id)
              {:keys [attach!] n :count} (s/attacher)
              d (error #(rows/lower-task (machine) (rows/emit bad) attach!))]
          (is (= (if (empty? bad-id) :malformed-op-id :inexact) (:yin.k/kind d)))
          (is (= [:ready 0 :op-id] (take 3 (:yin.k/path d))))
          (is (zero? @n)))))))


(deftest ready-value-portability-and-independent-update-body
  (let [[m out id] (two-callers)
        _ (answer-both! out id)
        swept (engine/check-wait-set m)
        plus (vm/primitive-function (get (:primitives swept) '+))
        nonportable (assoc-in swept [:ready-queue 0 :value] {:box [plus]})
        {:keys [wire]} (boundary-round-trip nonportable)
        task (root-task wire)
        {:keys [attach!] n :count} (s/attacher)
        d (error #(layout/lower-task (machine) (rvm/create-vm {:bodies [] :instructions []}) wire attach!))]
    (is (= :continuation-registers (:yin.k/kind d)))
    (is (= [:ready 0 :value] (take 3 (:yin.k/path d))))
    (is (zero? @n))
    (let [portable (assoc-in task [:slots 18 0 :slots 2] [:lit {:box [:portable]}])]
      (is (nil? (layout/lower-defect portable :register))))
    (let [original (first (:slots (slot task 9)))
          body (second (:slots (slot task 9)))
          distinct-body (assoc-in body [:slots 1] 0)
          changed (assoc-in task [:slots 18 0 :slots 4] (rows/node :cursor-cells [original distinct-body]))
          decoded (rows/decode (rows/emit changed))
          update-row (nth (:slots (first (slot decoded 18))) 4)]
      (is (= (first (:slots (slot decoded 9))) (first (:slots update-row))))
      (is (not= (second (:slots (slot decoded 9))) (second (:slots update-row)))))))


(deftest halted-with-outstanding-waits-is-normalized-separately
  (let [[m _] (parked-reader)
        source (assoc m :halted? true :blocked? false :value 42)
        {:keys [recv wire]} (boundary-round-trip source)]
    (is (= 42 (vm/value source)))
    (is (= :blocked (slot (root-task wire) 2)))
    (is (nil? (slot (root-task wire) 15)))
    (is (:blocked? recv))
    (is (false? (:halted? recv)))
    (is (= :yin/blocked (:value recv)))))


(deftest completion-try-and-update-refusals
  (let [world (installing identity false nil)
        m (engine/abandon-installs (:machine world) :abandoned)
        {:keys [wire]} (boundary-round-trip m)
        t (root-task wire)]
    (doseq [flag [nil :true 1 "true"]]
      (let [bad (assoc-in t [:slots 18 0 :slots 1 :slots 2] flag)
            {:keys [attach!] n :count} (s/attacher)
            d (error #(rows/lower-task (machine) (rows/emit bad) attach!))]
        (is (= :pending-try (:yin.k/kind d)))
        (is (zero? @n)))))
  (let [[m out id] (two-callers)
        _ (answer-both! out id)
        {:keys [wire]} (boundary-round-trip (engine/check-wait-set m))
        t (root-task wire)
        c (first (:slots (slot t 9)))
        body (second (:slots (slot t 9)))
        numbering (assoc (rows/canonical-numbering t) :missing :yin.k/cc-999)]
    (doseq [[updates kind] [[[:missing body] :missing-cursor-cell]
                            [[c body c body] :duplicate-key]]]
      (let [bad (assoc-in t [:slots 18 0 :slots 4] (rows/node :cursor-cells updates))
            {:keys [attach!] n :count} (s/attacher)
            d (error #(rows/lower-task (machine) (rows/emit-numbered bad numbering) attach!))]
        (is (= kind (:yin.k/kind d)) (pr-str d))
        (is (zero? @n))))))


(deftest completed-ffi-append-becomes-a-response-wait
  (let [world (retained-ffi identity)
        source (:machine world)
        _ ((first (:steps world)))
        completed (engine/check-wait-set source)
        request (get (:resources completed) vm/call-in-stream-key)
        {:keys [wire recv peer]} (boundary-round-trip completed)
        sent (stream/next request 0)
        waiting (drive-peers recv [peer])]
    (is (= :ffi-request (first (:slots (second (:slots (first (slot (root-task wire) 18))))))))
    (is (= :dao.stream/ok (:dao.stream/outcome sent)))
    (is (= 1 (count (:wait-set waiting))))
    (is (= :next (:reason (first (:wait-set waiting)))))
    (is (= (:call-id (first (:wait-set source))) (:call-id (first (:wait-set waiting)))))
    (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next request 0))) "the request was not appended again")))


(deftest terminal-completed-write-raises-the-same-outcome
  (doseq [status [:dao.stream/closed :dao.stream/invalid-value :dao.stream/transport-error]]
    (let [world (writer identity)
          source (:machine world)
          completed (engine/apply-put (assoc source :yin.k/gate :running)
                                      (first (:wait-set source)) {:dao.stream/outcome status})
          {:keys [recv peer origin]} (boundary-round-trip completed)
          a (error #(vm/run completed))
          b (error #(vm/run recv))
          source-identities (into {} (map (fn [[id e]] [(:handle e) id])) @(:table peer))
          source-id (get source-identities (get (:resources completed) (:stream-id a)))
          receiver-id (get origin (get (:resources recv) (:stream-id b)))]
      (is (= status (:outcome a)))
      (is (= (:outcome a) (:outcome b)))
      (is (some? source-id))
      (is (= source-id receiver-id))
      (is (= (assoc a :stream-id source-id) (assoc b :stream-id receiver-id))))))


(deftest ready-site-mismatch-and-halted-product
  (let [[m src] (parked-reader)
        _ (stream/append! src "B")
        completed (engine/check-wait-set m)
        {:keys [wire]} (boundary-round-trip (assoc completed :halted? true :blocked? false :value 42))
        t (root-task wire)
        bad (assoc-in t [:slots 18 0 :slots 1] (rows/node :completion [:install 'retired false]))
        {:keys [attach!] n :count} (s/attacher)
        d (error #(rows/lower-task (machine) (rows/emit bad) attach!))]
    (is (= :blocked (slot t 2)))
    (is (nil? (slot t 15)))
    (is (= :reason-mismatch (:yin.k/kind d)))
    (is (zero? @n))))


(deftest pre-branch-ghost-is-conservatively-unsatisfied
  (let [ast (corpus/if-node (s/lit true) (s/lit 1) (s/v 'ghost))
        m (sr/run-fuel (load-program (machine) ast) 1)
        served (atom 0)
        d (error #(rows/lift-task m (fn [_] (swap! served inc)) (lift-opts)))]
    (is (= :branch-false (first (nth (get-in m [:code (get-in m [:control :segment]) :vector]) (get-in m [:control :pc])))))
    (is (= :yin.k/unsatisfied (:yin.k/status d)))
    (is (= #{'ghost} (get-in d [:yin.k/missing :obligations])))
    (is (zero? @served))))


(deftest running-plus-ready-fenced-recovery
  (let [[m out id] (two-callers)
        _ (answer-both! out id)
        active (sr/run-fuel (engine/check-wait-set m) 1)
        handles (atom {})
        base (s/serve-table)
        serve! (fn [h] (let [d (base h)] (swap! handles assoc (:dao.stream/identity d) h) d))
        wire (rows/lift-task active serve! (lift-opts))
        attach! (fn [d]
                  {:dao.stream/outcome :dao.stream/ok
                   :dao.stream/handle (get @handles (:dao.stream/identity d))})
        fenced (rows/lower-task (machine) wire attach! {:fenced true})]
    (is (some? (:control active)))
    (is (= 1 (count (:ready-queue active))))
    (is (empty? (:ready-queue fenced)))
    (is (= 1 (count (:yin.k/recovery-ready fenced))))
    (doseq [gate [:exporting :ended]
            entry [#(sr/vm-hot % 1) vm/step vm/run #(sr/run-fuel % 1)]]
      (let [closed (assoc fenced :yin.k/gate gate)
            counters (atom {:guest 0 :scheduler 0 :bridge 0})
            ;; :cljd first — no with-redefs or private var-quote there: the
            ;; refusal and unchanged state are checked, the work counters are not
            result #?(:cljd (error #(entry closed))
                      :default
                      (let [hot @#'sr/vm-hot*
                            round engine/scheduler-round-fuel
                            bridge ffi/bridge-step]
                        (with-redefs [sr/vm-hot* (fn [& args] (swap! counters update :guest inc) (apply hot args))
                                      engine/scheduler-round-fuel (fn [& args] (swap! counters update :scheduler inc) (apply round args))
                                      ffi/bridge-step (fn [& args] (swap! counters update :bridge inc) (apply bridge args))]
                          (error #(entry closed)))))]
        (is (= :gate-closed (:rule result)))
        #?(:cljd nil :default (is (= {:guest 0 :scheduler 0 :bridge 0} @counters)))
        (is (= (select-keys active [:control :window :env :k])
               (select-keys closed [:control :window :env :k])))
        (is (= wire (rows/lift-task closed serve! (lift-opts))))))))


(deftest illegal-task-product-matrix
  (let [running (load-program (machine) (s/lit 42))
        blocked (:machine (reader identity read-op))
        parked (vm/run (load-program (machine) {:type :vm/park}))
        halted (vm/run running)]
    (doseq [m [running blocked parked halted]]
      (let [{:keys [serve!]} (served #{:reader :writer})
            t (root-task (rows/lift-task m serve! (lift-opts)))
            kind (slot t 2)]
        (doseq [[label mutate]
                (concat
                  [[:unknown #(assoc-in % [:slots 2] :alien)]]
                  (when (not= kind :halted) [[:result #(assoc-in % [:slots 15] [:lit :illegal])]])
                  (when (not= kind :parked) [[:parked-id #(assoc-in % [:slots 14] :alien)]])
                  (when (= kind :running) [[:no-run #(assoc-in % [:slots 17] [])]
                                           [:two-runs #(update-in % [:slots 17] into (slot t 17))]])
                  (when (= kind :blocked) [[:empty-blocked #(-> % (assoc-in [:slots 13] [])
                                                                (assoc-in [:slots 9] (rows/node :cursor-cells [])))]])
                  (when (= kind :halted) [[:no-result #(assoc-in % [:slots 15] nil)]])
                  (when (= kind :parked) [[:absent-id #(assoc-in % [:slots 14] :alien)]]))]
          (let [n (atom 0)
                d (error #(rows/lower-task (machine) (rows/emit (mutate t)) (fn [_] (swap! n inc))))]
            (is (contains? #{:kind :slot-kind :parked-id} (:yin.k/kind d)) (pr-str [kind label d]))
            (is (zero? @n))))))))


(deftest shared-cursor-independent-continuations
  (let [prepare (fn []
                  (let [[m out id] (two-callers)]
                    (answer-both! out id)
                    (engine/check-wait-set m)))
        finish (fn [m]
                 (loop [m (vm/run m) n 0]
                   (when (> n 100) (throw (ex-info "Shared-cursor continuation did not halt" {})))
                   (if (vm/halted? m)
                     {:halted true :value (normalize (vm/value m))
                      :trace (tu/drain (get (:resources m) vm/call-in-stream-key))}
                     (do
                       (doseq [e (:wait-set m)]
                         (stream/append! (get (:resources m) (:stream-id e))
                                         (apply2/success-response (:call-id e) :answered)))
                       (recur (vm/run m) (inc n))))))
        source (prepare)
        receiver-source (prepare)
        receiver (direct-transfer receiver-source)
        serves (s/serve-table)
        t (root-task (rows/lift-task receiver-source serves (lift-opts)))
        census (slot t 9)
        ready (slot t 18)
        source-updates (mapv #(mapv :cursor (vals (:resource-updates %))) (:ready-queue receiver-source))
        wire-updates (mapv #(mapv (comp second :slots second) (partition 2 (:slots (slot % 4)))) ready)]
    (is (= 1 (quot (count (:slots census)) 2)))
    (is (= source-updates wire-updates))
    (is (= (mapv :cursor (filter #(and (map? %) (contains? % :stream-id)) (vals (:resources receiver-source))))
           [(slot (second (:slots census)) 1)]))
    (is (= ["second" "first"] (mapv #(apply2/response-ok (:value %)) (:ready-queue receiver))))
    (let [a (finish source) b (finish receiver)]
      (is (:halted a))
      (is (:halted b))
      (is (= a b)))))


(deftest completed-link-request-and-active-completions
  (doseq [try? [false true] active? [false true]]
    (let [prepare (fn []
                    (let [world (linking (try-wrapper try?) false true)
                          m (:machine world)
                          done (engine/abandon-link m (:link-id (first (:wait-set m))) :gone)]
                      (if active? (load-program (assoc done :env (:free-env done)) (s/lit :beside)) done)))
          source (prepare)
          recv (direct-transfer (prepare))
          appends (atom 0)
          outcome (fn [m]
                    (try {:value (normalize (vm/value (drive-local m)))}
                         (catch #?(:cljd Object :clj Throwable :cljs :default) e {:error (ex-data e)})))
          a (outcome source)
          ;; :cljd first — no with-redefs there: outcome equality only, no append counter
          b #?(:cljd (outcome recv)
               :default (let [append stream/append!]
                          (with-redefs [stream/append! (fn [h v] (swap! appends inc) (append h v))] (outcome recv))))]
      (is (= :link-request (:reason (first (:ready-queue recv)))))
      (is (= (boolean active?) (boolean (:control recv))))
      (is (empty? (:wait-set recv)))
      (is (empty? (:installs recv)))
      (is (= a b))
      #?(:cljd nil :default (is (zero? @appends) "retired envelope is never reconstructed or appended")))))


(deftest completion-outcome-slot-matrix
  (doseq [prepare [#(let [[m src] (parked-reader)]
                      (stream/append! src "B") (engine/check-wait-set m))
                   #(let [[m out id] (two-callers)] (answer-both! out id) (engine/check-wait-set m))
                   #(let [world (writer identity)] ((first (:steps world))) (engine/check-wait-set (:machine world)))
                   #(let [world (retained-ffi identity)] ((first (:steps world))) (engine/check-wait-set (:machine world)))
                   #(let [m (:machine (linking identity false true))]
                      (engine/abandon-link m (:link-id (first (:wait-set m))) :gone))
                   #(let [m (:machine (linking identity false false))]
                      (engine/abandon-link m (:link-id (first (:wait-set m))) :gone))
                   #(engine/abandon-installs (:machine (installing identity false nil)) :gone)]]
    (let [m (prepare)
          wire (rows/lift-task m (s/serve-table) (lift-opts))
          t (root-task wire)
          tag (slot (slot (first (slot t 18)) 1) 0)]
      (doseq [[status refusal] [[false nil] [1 nil] ["ok" nil]
                                [nil [:lit :unexpected]] [:dao.stream/ok [:lit :unexpected]]
                                [:link-refused nil]]]
        (let [bad (-> t (assoc-in [:slots 18 0 :slots 3] status)
                      (assoc-in [:slots 18 0 :slots 5] refusal))
              n (atom 0)
              d (error #(rows/lower-task (machine {:modules (:modules m)}) (rows/emit bad)
                                         (fn [_] (swap! n inc)) (receiver-admission m)))]
          (is (= :ready-outcome (:yin.k/kind d)) (pr-str [tag status refusal d]))
          (is (zero? @n)))))))


(deftest ready-portability-root-oracle
  (doseq [root [:state :outcome :descriptor] unsupported? [false true]]
    (let [world (if (= root :descriptor) (writer identity)
                    (reader #(cat-of (s/lit [:ambient]) %) read-op))
          _ ((first (:steps world)))
          swept (engine/check-wait-set (:machine world))
          plus (vm/primitive-function (get (:primitives swept) '+))
          leaf (if unsupported? {:nested [[plus]]} {:nested [[:portable]]})
          wkeys (keys (get-in swept [:ready-queue 0 :window]))
          ;; trailing nil: cljd compiles a value-position `is` to a void expect
          _ (when (= root :state) (is (seq wkeys)) nil)
          m (case root
              :state (assoc-in swept [:ready-queue 0 :window (first wkeys)] leaf)
              :outcome (assoc-in swept [:ready-queue 0 :value] leaf)
              :descriptor (assoc-in swept [:ready-queue 0 :datom] leaf))
          wire (rows/lift-task m (s/serve-table) (lift-opts))
          task (root-task wire)
          ;; Independent oracle: inspect the constructed guest root, never
          ;; state-prim/value-prim or a result returned by lower-defect.
          guest-root (case root :state (get-in m [:ready-queue 0 :window (first wkeys)])
                           :outcome (get-in m [:ready-queue 0 :value])
                           :descriptor (get-in m [:ready-queue 0 :datom]))
          oracle (boolean (some fn? (tree-seq coll? seq guest-root)))
          d (layout/lower-defect task :register)
          attaches (atom 0)
          refused (when oracle
                    (error #(layout/lower-task (machine) (rvm/create-vm {:bodies [] :instructions []}) wire
                                               (fn [_] (swap! attaches inc)))))]
      (is (= unsupported? oracle))
      (is (= oracle (some? d)))
      (when oracle
        (is (= :continuation-registers (:yin.k/kind refused)))
        (is (= [:ready 0 (case root :state :state :outcome :value :descriptor :completion)]
               (take 3 (:yin.k/path d))))
        (is (zero? @attaches))))))


(defn task-machines
  [m]
  (cons m (mapcat #(task-machines (:vm %)) (vals (:installs m)))))


(deftest long-and-nested-initializer-transfers
  (doseq [nested? [false true]]
    (let [long-body (s/then (apply s/app (s/v '+) (map s/lit (range 1100))) plain-body)
          outer (if nested? (s/then (s/app (s/v 'require) (s/lit 'child.mod)) plain-body) long-body)
          prepare (fn []
                    (let [w (linking identity false false)
                          m (:machine w)
                          response (get (:resources m) module/link-response-resource)
                          id (:link-id (first (:wait-set m)))]
                      (stream/append! response (assoc (module-response outer #{'f}) :yin.link/id id))
                      (let [started (sr/run-fuel m 5)
                            answered (atom #{})
                            started (if-not nested? started
                                            (loop [m started n 0]
                                              (when (> n 100) (throw (ex-info "Nested require not reached" {})))
                                              (if-let [e (first (for [t (task-machines m) e (:wait-set t)
                                                                      :when (= 'child.mod (:name e))] e))]
                                                (do
                                                  (swap! answered conj (:link-id e))
                                                  (stream/append! response
                                                                  (-> (module-response long-body #{'f})
                                                                      (assoc-in [:manifest :yin.module/name] 'child.mod)
                                                                      (assoc :yin.link/id (:link-id e))))
                                                  (sr/run-fuel m 5))
                                                (recur (sr/run-fuel m 5) (inc n)))))]
                        {:vm started :response response :answered answered})))
          finish (fn [{:keys [vm response answered]}]
                   (loop [m vm rounds 0]
                     (when (> rounds 1000) (throw (ex-info "Initializer did not finish" {:nested nested?})))
                     (doseq [t (task-machines m) e (:wait-set t)
                             :when (and (= 'child.mod (:name e)) (not (contains? @answered (:link-id e))))]
                       (swap! answered conj (:link-id e))
                       (stream/append! response (-> (module-response long-body #{'f})
                                                    (assoc-in [:manifest :yin.module/name] 'child.mod)
                                                    (assoc :yin.link/id (:link-id e)))))
                     (if (vm/halted? m) m
                         (let [counts (atom {})
                               ;; :cljd first — no with-redefs or private var-quote
                               ;; there: cljd drives to the result, the per-task
                               ;; instruction bound is counted on JVM/JS only
                               next #?(:cljd (sr/run-fuel m 5)
                                       :default
                                       (let [raw @#'sr/vm-hot*]
                                         (with-redefs [sr/vm-hot*
                                                       (fn [t n]
                                                         (loop [t t i 0]
                                                           (if (or (= i n) (nil? (:control t))) t
                                                               (do (swap! counts update (:owner t) (fnil inc 0))
                                                                   (recur (raw t 1) (inc i))))))]
                                           (sr/run-fuel m 5))))]
                           #?(:cljd nil
                              :default
                              (let [tree-size (count (set (map :owner (concat (task-machines m) (task-machines next)))))]
                                (is (every? #(<= % 5) (vals @counts)) "at most one budget per visited task")
                                (is (<= (reduce + 0 (vals @counts)) (* 5 tree-size)))))
                           (recur next (inc rounds))))))
          source (prepare)
          receiver-source (prepare)
          _ (is (= (if nested? 3 2) (count (task-machines (:vm source)))))
          _ (is (some :control (rest (task-machines (:vm source)))))
          recv (direct-transfer (:vm receiver-source))
          a (finish source)
          b (finish (assoc receiver-source :vm recv))]
      (is (vm/halted? a))
      (is (vm/halted? b))
      (is (empty? (:installs b)))
      (is (= 'host.mod (vm/value a) (vm/value b)))
      (is (some? (module/resolve-module (:modules b) 'host.mod)))
      (when nested?
        (is (some? (module/resolve-module (:modules b) 'child.mod)))))))


(deftest install-child-phase-kind-matrix
  (doseq [phase [:parked :running]]
    (let [m (:machine (installing identity false (when (= phase :running) phase)))
          t (root-task (rows/lift-task m (s/serve-table) (lift-opts)))
          wanted (if (= phase :running) :running :blocked)]
      (doseq [wrong (disj #{:running :blocked :parked :halted} wanted)]
        (let [bad (assoc-in t [:slots 12 0 :slots 4 :slots 2] wrong)
              attaches (atom 0)
              d (error #(rows/lower-task (machine) (rows/emit bad) (fn [_] (swap! attaches inc))
                                         (receiver-admission m)))]
          (is (= :install-child (:yin.k/kind d)) (pr-str [phase wrong d]))
          (is (zero? @attaches)))))))


(deftest conservative-prefixes-refuse-before-serving
  (let [setup (load-program (machine) (s/then (s/def! 's {:type :stream/make :buffer 4})
                                              (s/def! 'c (s/cursor-of (s/v 's)))))]
    (doseq [n [0 1 2]]
      (let [m (if (zero? n) setup (sr/run-fuel setup n))
            serves (atom 0)
            d (error #(rows/lift-task m (fn [_] (swap! serves inc) nil) (lift-opts)))]
        (is (= n (get-in m [:control :pc])))
        (is (= [:yin.k/unsatisfied :incomplete #{'s}]
               [(:yin.k/status d) (:yin.k/discovery d) (get-in d [:yin.k/missing :obligations])]))
        (is (zero? @serves))))))


(deftest completed-poll-observation-is-an-ordinary-read-outcome
  (let [m (machine)
        [sref m] (engine/attach-resource m (s/ring 4))
        [cref m] (engine/handle-cursor m {:stream sref} :poll-cursor)
        m (vm/run (load-program (assoc m :store {'c cref} :yin.k/gate :running)
                                (s/app (s/v 'stream/poll!) (s/v 'c))))
        e (first (:wait-set m))
        completed (engine/apply-observation m e {:dao.stream/outcome :dao.stream/blocked})
        recv (direct-transfer completed)
        reads (atom 0)
        ;; :cljd first — no with-redefs there: the delivered outcome is checked,
        ;; the read counter is not
        got #?(:cljd (vm/run recv)
               :default (let [next stream/next]
                          (with-redefs [stream/next (fn [h c] (swap! reads inc) (next h c))] (vm/run recv))))]
    (is (= :observe (:reason e)))
    (is (nil? (:reason (first (:ready-queue completed)))))
    (is (= :dao.stream/blocked (vm/value (vm/run completed)) (vm/value got)))
    (is (empty? (:wait-set got)))
    #?(:cljd nil :default (is (zero? @reads) "completed observation is delivered without another read"))))
