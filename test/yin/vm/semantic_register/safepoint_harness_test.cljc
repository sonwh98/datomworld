(ns yin.vm.semantic-register.safepoint-harness-test
  "Phase 6b: the safepoint harness on the :semantic-register lane
   (docs/design/yin.vm.semantic-register-vm.continuation.md §5, §8.2).

   Each row of the §5.2 matrix is built twice under each of the four
   segment wrappers: the reference build is satisfied and run on its own
   machine; the handoff build is lifted with rows/lift-task, lowered with
   rows/lower-task into a fresh receiver over the toy channel, relifted at
   once, then satisfied through the source's streams. Result and effect
   trace equal the reference (law 1); the relift equals the lift by root
   id under rows/canonical-under (law 2); the pending's reason is the
   observed wait's (law 3); every lifted frame passes the validator
   (law 6). Row 20 runs once, outside the wrappers."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.test-slow :as slow]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.frame :as frame]
            [yin.vm.semantic-register.frame-test :as ft]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.ucf.lift-support :as support]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.v2-support :as s]))


;; =============================================================================
;; The composition: code by A, the source index, one machine builder
;; =============================================================================

(def source-index
  "The composition's {A #{tree-address}} (§6.1): every vector this
   harness projects is recorded beside the address of its AST."
  (atom {}))


(defn project
  [ast]
  (let [v (:vector (linearize/project ast))]
    (swap! source-index update (jing/segment-key v) (fnil conj #{}) (jing/segment-key [:yin/tree ast]))
    v))


(def stream-modules (module/register-stream-module (module/default-registry)))


(defn machine
  ([] (machine {}))
  ([opts]
   (sr/create-vm (merge {:make-stream tu/make-stream :capability-secret tu/secret
                         :secret-source (fn [origin] (str tu/secret "/" (name origin)))
                         :modules stream-modules}
                        opts))))


(defn load-program
  [m ast]
  (sr/load-vector m (project ast) code/contract))


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
        w (support/one-slot-stream "w")
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
  (let [trace (atom []) call-in (support/one-slot-stream "call-in") call-out (s/ring 8)
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
  [body exports]
  {:status :ok :image {:value (project body)}
   :manifest {:yin.module/name 'host.mod :yin.module/exports exports} :obligations []})


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
        request (if retained? (support/one-slot-stream "link-request") (s/ring 64))
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
        m (vm/run (load-program (machine) (with-cat (wrap (s/app (s/lam ['p] {:type :vm/park}) (s/lit 1))))))
        cursor (fn [m]
                 (let [[ref m] (engine/attach-resource m ring) [id m] (engine/gensym m "cursor")]
                   (engine/handle-cursor m {:stream ref} id)))
        [c1 m] (cursor m)
        [c2 m] (cursor m)
        [seg image] (first (:code m))
        closure (first (filter #(= :closure (first %)) (:vector image)))
        clo (values/closure (:owner m) {:type :closure :params (nth closure 2) :entry (nth closure 3)
                                        :segment seg :env {engine/store-of-key 'host.mod}})]
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
  (let [base (s/attacher t) origin (atom {}) n (atom 0)]
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
        {:keys [attach! origin]} (recording-attacher toy)
        recv (rows/lower-task (machine (merge {:attach-stream attach!} (:opts receiver))) wire attach!)
        again (served surfaces)
        relift (rows/lift-task recv (:serve! again) (lift-opts))
        corr2 (into {} (map (fn [[id e]] [id (get @origin (:handle e))])) @(:table (:peer again)))
        done (satisfy world recv #(drive-peers % [peer]))
        _ (s/serve-mirror! peer)]
    (when-let [f (:finish world)] (f))
    (when-let [f (:finish receiver)] (f))
    {:wire wire :recv recv :relift relift :corr corr :corr2 corr2
     :halted (vm/halted? done) :value (normalize (vm/value done)) :trace @(:trace world)}))


(defn root-task
  [wire]
  (rows/decode wire))


(defn slot
  [task i]
  (nth (:slots task) i))


(defn first-pending
  [task]
  (second (:slots (first (slot task 13)))))


(defn laws
  "Laws 1, 2, 3 and 6 for one world; answers the run."
  [world expected]
  (let [{:keys [wire relift corr corr2] :as got} (handed-off world)
        task (root-task wire)]
    (is (= (:kind world) (slot task 2)))
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
  (doseq [[n r build] rows]
    (testing (str "row " n)
      (let [ref (reference (build identity))]
        (when (some? r) (is (= r (:value ref))))
        (laws (build identity) ref)))))


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
            recv (rows/lower-task (machine {:attach-stream (s/attacher toy)}) wire (s/attacher toy))
            inst (get-in recv [:installs 'host.mod])
            lowered (:vm inst)]
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
        recv (rows/lower-task (machine {:attach-stream (s/attacher toy) :call-in (s/ring 8) :call-out own-out
                                        :call-out-cursor (vm/mint-oldest own-out :test)})
                              wire (s/attacher toy))
        [w1 w2] (:wait-set recv)
        _ (answer-both! call-out id)
        swept (sweep recv [peer])]
    (is (= [:ffi :ffi] (mapv #(first (:slots %)) pendings)))
    (is (= [id :parked-9] (mapv #(nth (:slots %) 2) pendings)))
    (is (= 1 (quot (count (:slots (slot task 9))) 2)) "the two waiters name one cursor cell")
    (is (= (nth (:slots (first pendings)) 1) (nth (:slots (second pendings)) 1)))
    (is (= (:cursor-ref w1) (:cursor-ref w2)) "the lower gives both waiters one fresh cursor entry")
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
