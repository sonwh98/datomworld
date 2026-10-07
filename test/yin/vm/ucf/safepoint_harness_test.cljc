(ns yin.vm.ucf.safepoint-harness-test
  "D16-prep: the safepoint conformance harness (UCF 7.4.1; 7.11.1's
   safepoint-reconstruction row and its acceptance blocker; linker-dht
   14.1.1 and 14.1.3's second row).

   Every corpus segment is run to every liftable row of 7.4.1 -- explicit
   park, blocked read, blocked write, sent FFI, retained FFI, ordinary and
   tail effectful calls, halt -- on both fork body versions: version 0
   (the semantic profile, the only one it speaks) and version 2 (the four
   profiles).  The corpus wraps each row's parking expression in four
   segments: bare, as an operand, three ordinary activations deep, and
   inside a closure called with a captured environment.

   Each case builds the same program twice.  The reference build is
   satisfied and run on its own machine; the handoff build is lifted to
   canonical bytes, lowered into a fresh machine over the toy channel,
   satisfied through the source's streams and run through the
   reflections.  Result and effect trace must agree; `lift(lower(frame))`
   must reproduce the lifted body; the frame's reason is the observed
   wait's.  An explicit park is checked through its parked record: no
   frame, the record by its own id, the controlled `:vm/resume`.  Halt is
   a result, not a frame.

   Beside the matrix: one pc reached by two activations of different
   depth and captured environment, handed off at each; a nonempty ready
   queue; a kept FFI response cursor shared by two waiters; and a real
   held immediate (the D4 `:observe` entry), which refuses export."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.lift-support :as support]
            [yin.vm.ucf.v2-support :as s]))


(def ^:private lanes
  "Every [profile version] pair: version 0 speaks the semantic profile
   only; version 2 all four."
  (into [[:semantic 0]] (map (fn [e] [e 2])) s/engines))


(defn- lift
  "The fork lift of `m` at `version` over `peer`'s served table."
  [m version t peer surfaces]
  (handoff/export-task m (s/server peer (:channel t) surfaces)
                       (when (= 2 version) {:version 2})))


(defn- drive-peers
  "Run `m` through its reflections, stepping every peer's mirror between
   rounds, until it stops blocking."
  [m peers rounds]
  (loop [m (vm/run m), n rounds]
    (if (or (not (vm/blocked? m)) (zero? n))
      m
      (do (run! s/serve-mirror! peers)
          (recur (vm/run m) (dec n))))))


;; =============================================================================
;; The corpus: four segments around one parking expression
;; =============================================================================

(defn- with-cat
  "`ast` with `cat`, a closure conj-ing its two arguments, in scope.  The
   corpus combines through it, not through the `conj` primitive: the
   register profile cannot resume a wait whose live registers hold a
   host primitive (`:continuation-registers`, reported with D16-prep),
   and a closure keeps that defect out of every other row's evidence."
  [ast]
  (s/let1 'cat (s/lam ['p 'q] (s/app (s/v 'conj) (s/v 'p) (s/v 'q))) ast))


(defn- cat-of
  [p q]
  (s/app (s/v 'cat) p q))


(defn- nest
  "`op` inside `d` ordinary (non-tail) closure activations."
  [d op]
  (if (zero? d)
    op
    (cat-of (s/lit [d]) (s/app (s/lam ['x] (nest (dec d) op)) (s/lit d)))))


(def ^:private corpus
  "Each segment binds `cat` around its whole body, so a row's parking
   expression may combine through it too."
  (mapv
    (fn [[label seg]] [label (comp with-cat seg)])
    [[:bare identity]
     [:operand (fn [op] (cat-of (s/lit [:operand]) op))]
     [:depth-3 (fn [op] (nest 3 op))]
     [:captured
      (fn [op]
        (s/let1 'k (s/lit :captured)
                (s/let1 'f (s/lam ['y] (cat-of (cat-of (cat-of (s/lit []) (s/v 'k))
                                                       (s/v 'y))
                                               op))
                        (s/app (s/v 'f) (s/lit :arg)))))]]))


;; =============================================================================
;; The rows: one builder per 7.4.1 row
;; =============================================================================
;;
;; A builder answers the parked machine and how to satisfy its wait: the
;; steps run in order, each followed by a drive; `:finish` and the
;; receiver's own `:finish` record the effect trace into `:trace`.

(defn- read-row
  "A blocked read at a kept position: A consumed, B unread."
  [engine seg]
  (let [m0 (vm/run (s/load-ast
                     engine (s/new-machine engine)
                     (s/let1 's {:type :stream/make, :buffer 4}
                             (s/let1 'c (s/cursor-of (s/v 's))
                                     (seg (s/let1 'a (s/next-of (s/v 'c))
                                                  (cat-of (cat-of (s/lit []) (s/v 'a))
                                                          (s/next-of (s/v 'c)))))))))
        src (s/stream-of m0)]
    (stream/append! src "A")
    {:machine (vm/run m0)
     :kind :blocked
     :reason :next
     :steps [#(stream/append! src "B")]
     :trace (atom [])}))


(defn- write-row
  "A blocked write: the one-slot target is warm, the value retained."
  [engine seg]
  (let [trace (atom [])
        w (support/one-slot-stream "w")
        _ (stream/append! w :warmed)
        m0 (s/load-ast engine (s/new-machine engine)
                       (seg {:type :stream/put, :target (s/v 'w),
                             :val (s/lit "v")}))
        [ref m1] (engine/attach-resource m0 w)
        take! #(swap! trace conj (:dao.stream/value (stream/next w 0)))]
    {:machine (vm/run (assoc m1 :store {'w ref}))
     :kind :blocked
     :reason :put
     :surfaces #{:reader :writer}
     :steps [take!]
     :finish take!
     :trace trace}))


(defn- own-call-pair
  "The receiver's own FFI pair: a subsequent call uses it, so the trace
   records what it carried."
  [trace]
  (let [own-in (s/ring 8)
        own-out (s/ring 8)]
    {:opts {:call-in own-in
            :call-out own-out
            :call-out-cursor (vm/mint-oldest own-out :test)}
     :finish #(swap! trace into (tu/drain own-in))}))


(defn- sent-ffi-row
  "A sent FFI call awaiting its correlated response."
  [engine seg]
  (let [trace (atom [])
        call-in (s/ring 8)
        call-out (s/ring 8)
        m (vm/run (s/load-ast engine (s/calling-machine engine call-in call-out)
                              (seg (s/call-node :op/echo "hello"))))
        id (ffi/response-call-id (first (:wait-set m)))]
    {:machine m
     :kind :blocked
     :reason :ffi
     :steps [#(stream/append! call-out (apply2/success-response id "echo!"))]
     :finish #(swap! trace into (tu/drain call-in))
     :receiver #(own-call-pair trace)
     :trace trace}))


(defn- retained-ffi-row
  "An FFI request retained behind a full call-in; once the slot frees,
   the retry lands and the response correlates."
  [engine seg]
  (let [trace (atom [])
        call-in (support/one-slot-stream "call-in")
        call-out (s/ring 8)
        _ (stream/append! call-in :warmed)
        m (vm/run (s/load-ast engine (s/calling-machine engine call-in call-out)
                              (seg (s/call-node :op/echo "hello"))))
        id (ffi/request-call-id (first (:wait-set m)))]
    {:machine m
     :kind :blocked
     :reason :ffi-request
     :surfaces #{:reader :writer}
     :steps [#(stream/next call-in 0)
             (fn []
               (swap! trace conj (:dao.stream/value (stream/next call-in 0)))
               (stream/append! call-out (apply2/success-response id "echo!")))]
     :receiver #(own-call-pair trace)
     :trace trace}))


(defn- plain-module-response
  "The `:ok` link response for `host.mod` exporting `f`, whose
   initialization blocks on nothing."
  [engine]
  {:status :ok
   :image {:value (s/module-image engine (s/def! 'f (s/lam [] (s/lit 42))))}
   :manifest {:yin.module/name 'host.mod
              :yin.module/exports #{'f}}
   :obligations []})


(defn- require-row
  "An effectful call of `require` -- ordinary, or `tail?` in the tail
   position of a closure -- parked polling for its link response."
  [tail?]
  (fn [engine seg]
    (let [trace (atom [])
          request (s/ring 64)
          response (s/ring 64)
          call (cond-> (s/require-program 'host.mod) tail? (assoc :tail? true))
          op (if tail? (s/app (s/lam ['u] call) (s/lit 0)) call)
          m (vm/run (s/load-ast engine (s/linking-machine engine request response)
                                (seg op)))
          id (:link-id (first (:wait-set m)))]
      {:machine m
       :kind :blocked
       :reason :link-response
       :steps [#(stream/append! response
                                (assoc (plain-module-response engine)
                                       :yin.link/id id))]
       :finish #(swap! trace into (tu/drain request))
       :receiver (fn []
                   (let [rq (s/ring 64)]
                     {:opts {:link-request rq :link-response (s/ring 64)}
                      :finish #(swap! trace into (tu/drain rq))}))
       :trace trace})))


(def ^:private stream-modules
  (module/register-stream-module (module/default-registry)))


(defn- stream-call-row
  "An effectful call of the stream module's `next!` -- ordinary, or
   `tail?` in the tail position of a closure -- parked on its read."
  [tail?]
  (fn [engine seg]
    (let [call (cond-> (s/app (s/v 'stream/next!) (s/v 'c)) tail? (assoc :tail? true))
          op (if tail?
               (s/app (s/lam ['u] call) (s/lit 0))
               (cat-of (s/lit [:ordinary]) call))
          m (vm/run (s/load-ast engine (s/new-machine engine {:modules stream-modules})
                                (s/let1 's {:type :stream/make, :buffer 4}
                                        (s/let1 'c (s/cursor-of (s/v 's))
                                                (seg op)))))
          src (s/stream-of m)]
      {:machine m
       :kind :blocked
       :reason :next
       :steps [#(stream/append! src "B")]
       :receiver (constantly {:opts {:modules stream-modules}})
       :trace (atom [])})))


(defn- park-row
  "An explicit park: resumed by its record's id, not by a wait."
  [engine seg]
  (let [m (vm/run (s/load-ast engine (s/new-machine engine) (seg {:type :vm/park})))
        pid (:id (vm/value m))]
    {:machine m
     :kind :parked
     :resume (fn [m']
               (s/run-beside engine m' {:type :vm/resume, :parked-id pid,
                                        :val (s/lit "back")}))
     :trace (atom [])}))


(defn- halt-row
  [engine seg]
  {:machine (vm/run (s/load-ast engine (s/new-machine engine) (seg (s/lit 42))))
   :kind :halted
   :trace (atom [])})


(def ^:private rows
  "The liftable rows of 7.4.1, by name: every row runs on every lane."
  [[:explicit-park park-row]
   [:blocked-read read-row]
   [:blocked-write write-row]
   [:sent-ffi sent-ffi-row]
   [:retained-ffi retained-ffi-row]
   [:ordinary-require (require-row false)]
   [:tail-require (require-row true)]
   [:ordinary-stream-call (stream-call-row false)]
   [:tail-stream-call (stream-call-row true)]
   [:halt halt-row]])


;; =============================================================================
;; Open defects: production behavior the harness found, pinned as canaries
;; =============================================================================

(def ^:private open-defects
  "Production defects this harness found (D16-prep findings D1 to D4),
   by [profile row-or-check] and the checks each breaks.  A listed check
   asserts the defect is still there, so the fix fails its canary and the
   entry is removed with it; every unlisted check holds.

   D1 :export -- the semantic lift censuses the stream module's host
   footprint as undeclared and refuses `:yin.k/unsatisfied`
   (`:incomplete`), so its effectful calls cannot lift there.
   D2 :relift -- a lowered retained FFI request relifts with the
   receiver's own call-out position as its response cell, not the
   carried one.
   D3 :result -- on stack and register, the retried request of a
   lowered retained FFI call then waits on the receiver's own call-out,
   so the emitter's correlated response never wakes it.
   D4 :source-acceptance -- a lowered blocked writer completes on the
   reflection's outbound acceptance while the source target is still
   full, and the retained value never reaches it (14.1.1).
   D0 :live-primitive -- the register profile refuses to resume any wait
   whose live registers hold a host primitive, with no handoff at all
   (`:continuation-registers`); the corpus combines through a closure
   for that reason."
  {[:register :live-primitive] #{:resume}
   [:semantic :ordinary-stream-call] #{:export}
   [:semantic :tail-stream-call] #{:export}
   [:semantic :retained-ffi] #{:relift}
   [:stack :retained-ffi] #{:relift :result}
   [:register :retained-ffi] #{:relift :result}
   [:semantic :writer] #{:source-acceptance}
   [:stack :writer] #{:source-acceptance}
   [:register :writer] #{:source-acceptance}
   [:walker :writer] #{:source-acceptance}})


(defn- check
  "Assert `ok?` -- or, for an open defect of `engine` and `row`, that the
   defect still holds."
  [engine row k ok? msg]
  (if (contains? (get open-defects [engine row]) k)
    (is (not ok?) (str "open defect " k " of " (name engine) " " (name row)
                       " no longer reproduces: remove it from open-defects"))
    (is ok? msg)))


;; =============================================================================
;; The two runs
;; =============================================================================

(defn- satisfy
  "Run the row's steps over `m`, each followed by `drive`, or its
   controlled resume."
  [world m drive]
  (if-let [resume (:resume world)]
    (resume m)
    (reduce (fn [m step] (step) (drive m)) m (:steps world))))


(defn- reference
  [world]
  (let [done (satisfy world (:machine world) #(s/drive-local % 6))]
    (when-let [f (:finish world)] (f))
    {:halted (vm/halted? done), :value (vm/value done), :trace @(:trace world)}))


(defn- handed-off
  "Lift the row's machine, lower it into a fresh receiver, relift that
   receiver at once, then satisfy the wait through the source's streams.
   The mirror is stepped once more after the run: a lowered writer's
   append reaches the source only through the mirror (see D4)."
  [engine version world]
  (let [t (s/toy)
        peer (s/served-peer t)
        surfaces (or (:surfaces world) #{:reader})
        export (lift (:machine world) version t peer surfaces)
        receiver ((or (:receiver world) (constantly nil)))
        attach! (s/attacher t)
        r (when (= :ok (:status export))
            (handoff/resume-task
              (s/new-machine engine (merge {:attach-stream attach!}
                                           (:opts receiver)))
              (:bytes export) attach! {:address (:address export)}))
        recv (:vm r)
        t2 (s/toy)
        relift (when recv (lift recv version t2 (s/served-peer t2) surfaces))
        done (when recv (satisfy world recv #(drive-peers % [peer] 6)))
        _ (s/serve-mirror! peer)]
    (when-let [f (:finish world)] (f))
    (when-let [f (:finish receiver)] (f))
    {:export export, :lower r, :recv recv, :relift relift,
     :halted (some-> done vm/halted?), :value (some-> done vm/value),
     :trace @(:trace world)}))


(defn- frame-pending
  [export]
  (get-in export [:body :yin.k/frames 0 :yin.k/pending]))


(defn- kind-checks
  "The row kind's own observables: a blocked frame's reason is the
   observed wait's; an explicit park is its parked record; a halt is a
   result."
  [world expected export recv]
  (let [body (:body export)]
    (case (:kind world)
      :blocked
      (is (= (:reason world) (:yin.k/reason (frame-pending export)))
          "the frame's reason is the observed wait's")
      :parked
      (let [pid (:id (vm/value (:machine world)))]
        (is (empty? (:yin.k/frames body))
            "an explicit park mints no pending wait")
        (is (contains? (:yin.k/parked body) pid))
        (is (= pid (:id (vm/value recv)))
            "the active park is the source's record, by its own id")
        (is (= (vm/value recv) (get (:parked recv) pid))))
      :halted
      (do (is (not (contains? body :yin.k/frames)))
          (is (= (:value expected) (:yin.k/result body))
              "halt carries the result")
          (is (vm/halted? recv))
          (is (empty? (:wait-set recv)))))))


(deftest every-corpus-segment-to-every-row-matches-the-reference
  (doseq [[engine version] lanes
          [row build] rows
          [seg wrap] corpus]
    (testing (str (name engine) " v" version " " (name row) " " (name seg))
      (let [expected (reference (build engine wrap))
            world (build engine wrap)
            {:keys [export lower recv relift] :as got}
            (handed-off engine version world)
            body (:body export)]
        (is (:halted expected) "the reference run completes")
        (check engine row :export (= :ok (:status export)) (pr-str export))
        (when (= :ok (:status export))
          (is (= (:kind world) (:kind export)))
          (is (= version (:yin.k/version body)))
          (kind-checks world expected export recv)
          (is (= :ok (:status lower)) (pr-str lower))
          (is (= :ok (:status relift)) (pr-str relift))
          (check engine row :relift
                 (= (s/stream-blind body) (s/stream-blind (:body relift)))
                 "lift(lower(frame)) reproduces the canonical body")
          (check engine row :result
                 (= (select-keys expected [:halted :value :trace])
                    (select-keys got [:halted :value :trace]))
                 "result and effect trace equal the reference run"))))))


(deftest a-wait-over-a-live-host-primitive-resumes-on-every-profile
  ;; no handoff: the reference machine alone, `conj` pending as an operand
  (doseq [engine s/engines]
    (testing (name engine)
      (let [m0 (vm/run (s/load-ast
                         engine (s/new-machine engine)
                         (s/let1 's {:type :stream/make, :buffer 4}
                                 (s/let1 'c (s/cursor-of (s/v 's))
                                         (s/app (s/v 'conj) (s/lit [])
                                                (s/next-of (s/v 'c)))))))
            _ (stream/append! (s/stream-of m0) "A")
            done (try (vm/run m0)
                      (catch #?(:cljd Object :clj Exception :cljs :default) _
                        nil))]
        (check engine :live-primitive :resume
               (and (some? done) (= ["A"] (vm/value done)))
               "the read wakes and the primitive applies")))))


;; =============================================================================
;; Ordinary and tail effectful calls are what they claim
;; =============================================================================

(deftest the-tail-rows-park-in-a-tail-call
  (doseq [[row build] [[:ordinary-require (require-row false)]
                       [:tail-require (require-row true)]
                       [:ordinary-stream-call (stream-call-row false)]
                       [:tail-stream-call (stream-call-row true)]]
          [seg wrap] corpus]
    (testing (str (name row) " " (name seg))
      (let [m (:machine (build :semantic wrap))
            entry (first (:wait-set m))
            site (get-in m [:code (:segment entry) :code (dec (:pc entry))])]
        (is (= (if (#{:tail-require :tail-stream-call} row)
                 (:tailcall vm/opcode-table)
                 (:call vm/opcode-table))
               (first site))
            (str "the parking instruction " (pr-str site)))))))


;; =============================================================================
;; One pc, two activations: distinct depth and captured environment
;; =============================================================================

(defn- two-activations
  "One reading closure body, reached twice: through `g1` (captured
   `:one`) as an operand, then through `g2` (captured `:two`) two
   ordinary activations deeper.  Answers [parked source]."
  [engine]
  (let [reader (s/lam ['k]
                      (s/lam ['y]
                             (cat-of (cat-of (cat-of (s/lit []) (s/v 'k)) (s/v 'y))
                                     (s/next-of (s/v 'c)))))
        body (cat-of (cat-of (s/lit []) (s/app (s/v 'g1) (s/lit 1)))
                     (nest 2 (s/app (s/v 'g2) (s/lit 2))))
        m (vm/run
            (s/load-ast
              engine (s/new-machine engine)
              (with-cat
                (s/let1 's {:type :stream/make, :buffer 4}
                        (s/let1 'c (s/cursor-of (s/v 's))
                                (s/let1 'mk reader
                                        (s/let1 'g1 (s/app (s/v 'mk) (s/lit :one))
                                                (s/let1 'g2 (s/app (s/v 'mk) (s/lit :two))
                                                        body))))))))]
    [m (s/stream-of m)]))


(defn- registers
  [export]
  (get-in export [:body :yin.k/frames 0 :yin.k/registers]))


(deftest one-pc-reached-at-two-depths-hands-off-at-each
  (doseq [[engine version] lanes]
    (testing (str (name engine) " v" version)
      (let [[ref-m ref-src] (two-activations engine)
            _ (stream/append! ref-src "B")
            ref-hop (s/drive-local ref-m 6)
            _ (stream/append! ref-src "C")
            expected (vm/value (s/drive-local ref-hop 6))
            [m src] (two-activations engine)
            t1 (s/toy)
            p1 (s/served-peer t1)
            e1 (lift m version t1 p1 #{:reader})
            r1 (:vm (handoff/resume-task
                      (s/new-machine engine {:attach-stream (s/attacher t1)})
                      (:bytes e1) (s/attacher t1) {:address (:address e1)}))
            _ (stream/append! src "B")
            hop (drive-peers r1 [p1] 6)
            t2 (s/toy)
            p2 (s/served-peer t2)
            e2 (lift hop version t2 p2 #{:reader})
            r2 (:vm (handoff/resume-task
                      (s/new-machine engine {:attach-stream (s/attacher t2)})
                      (:bytes e2) (s/attacher t2) {:address (:address e2)}))
            _ (stream/append! src "C")
            done (drive-peers r2 [p2 p1] 8)
            regs1 (registers e1)
            regs2 (registers e2)]
        (is (= :ok (:status e1)) (pr-str e1))
        (is (= :ok (:status e2)) (pr-str e2))
        (is (= [:next :next] (mapv (comp :yin.k/reason frame-pending) [e1 e2])))
        ;; the walker's frame is its environment and continuation alone:
        ;; it has no pc to compare
        (when-not (= :walker engine)
          (is (some? (:yin.k/pc regs1)))
          (is (= (select-keys regs1 [:yin.k/pc :yin.k/segment :yin.k/image])
                 (select-keys regs2 [:yin.k/pc :yin.k/segment :yin.k/image]))
              "both hand-offs park at one pc of one segment"))
        (is (not= regs1 regs2)
            "with a different activation: depth and captured environment")
        (is (= [[:one 1 "B"] [2 [1 [:two 2 "C"]]]] expected))
        (is (vm/halted? done))
        (is (= expected (vm/value done))
            "two hand-offs answer what the source's own run did")))))


;; =============================================================================
;; Queued work, a shared response cursor, a held immediate, the writer
;; =============================================================================

(defn- counting-server
  [t peer surfaces]
  (let [n (atom 0)
        serve! (s/server peer (:channel t) surfaces)]
    [(fn [h] (swap! n inc) (serve! h)) n]))


(deftest a-nonempty-ready-queue-is-not-quiescent
  (doseq [[engine version] lanes]
    (testing (str (name engine) " v" version)
      (let [[parked src] (s/parked-reader engine)
            _ (stream/append! src "B")
            woken (engine/check-wait-set parked)
            t (s/toy)
            [serve! n] (counting-server t (s/served-peer t) #{:reader})
            r (handoff/export-task woken serve! (when (= 2 version) {:version 2}))]
        (is (seq (:ready-queue woken)) "the sweep queued the woken reader")
        (is (= :yin.k/not-quiescent (:yin.k/status r)) (pr-str r))
        (is (not (contains? r :bytes)) "nothing is published")
        (is (zero? @n) "no stream was served")))))


(defn- two-callers
  "A sent caller whose response cell is shared by a second outstanding
   call: the real waiter, and a second waiter built from it under its own
   call id -- the construction of the stage-1 alias row.  Answers
   [machine call-out]."
  [engine]
  (let [[parked _ call-out] (s/parked-sent-caller engine)
        e1 (first (:wait-set parked))
        ;; the walker keeps the call id in its continuation as well
        e2 (cond-> (assoc e1 :call-id :parked-9)
             (contains? (:k e1) :call-id) (assoc-in [:k :call-id] :parked-9))]
    [(assoc parked :wait-set [e1 e2]) call-out]))


(defn- answer-both!
  "The second call's response first, then the first's."
  [call-out]
  (stream/append! call-out (apply2/success-response :parked-9 "second"))
  (stream/append! call-out (apply2/success-response :parked-0 "first")))


(defn- sweep
  [m peers]
  (loop [m (engine/check-wait-set m) n 12]
    (if (or (zero? n) (empty? (:wait-set m)))
      m
      (do (run! s/serve-mirror! peers)
          (recur (engine/check-wait-set m) (dec n))))))


(deftest a-response-cursor-shared-by-two-waiters-crosses-as-one-cell
  (doseq [[engine version] lanes]
    (testing (str (name engine) " v" version)
      (let [[ref-m ref-out] (two-callers engine)
            _ (answer-both! ref-out)
            expected (mapv :value (:ready-queue (sweep ref-m [])))
            [m call-out] (two-callers engine)
            t (s/toy)
            peer (s/served-peer t)
            export (lift m version t peer #{:reader})
            pendings (mapv :yin.k/pending (get-in export [:body :yin.k/frames]))
            own-out (s/ring 8)
            recv (:vm (handoff/resume-task
                        (s/new-machine engine {:attach-stream (s/attacher t)
                                               :call-in (s/ring 8)
                                               :call-out own-out
                                               :call-out-cursor
                                               (vm/mint-oldest own-out :test)})
                        (:bytes export) (s/attacher t)
                        {:address (:address export)}))
            [w1 w2] (:wait-set recv)
            _ (answer-both! call-out)
            swept (sweep recv [peer])]
        (is (= :ok (:status export)) (pr-str export))
        (is (= [:ffi :ffi] (mapv :yin.k/reason pendings)))
        (is (= [:parked-0 :parked-9] (mapv :yin.k/call-id pendings)))
        (is (= 1 (count (get-in export [:body :yin.k/cells])))
            "the two waiters name one cell")
        (is (= (:cursor-ref w1) (:cursor-ref w2))
            "the lower gives both waiters one fresh cursor entry")
        (is (= [(apply2/success-response :parked-9 "second")
                (apply2/success-response :parked-0 "first")]
               expected)
            "the reference: each waiter woke on its own correlated response")
        (is (empty? (:wait-set swept)))
        (is (= expected (mapv :value (:ready-queue swept)))
            "the receiver wakes the same waiters with the same responses")))))


(deftest a-held-immediate-refuses-export
  (doseq [[engine version] lanes]
    (testing (str (name engine) " v" version)
      (let [m0 (s/load-ast engine (s/new-machine engine {:modules stream-modules})
                           (s/app (s/v 'stream/poll!) (s/v 'c)))
            [sref m1] (engine/attach-resource m0 (s/ring 8))
            [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
            held (vm/run (assoc m2 :store {'c cref} :yin.k/gate :running))
            t (s/toy)
            [serve! n] (counting-server t (s/served-peer t) #{:reader})
            r (handoff/export-task held serve! (when (= 2 version) {:version 2}))]
        (is (= [:observe] (mapv :reason (:wait-set held)))
            "the gated poll is held as the D4 :observe entry")
        (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
        (is (= :reason-mismatch (:yin.k/kind r)))
        (is (= :observe (:yin.k/hold r)))
        (is (not (contains? r :bytes)))
        (is (zero? @n) "refused before any stream was served")))))


(deftest a-lowered-writer-completes-only-on-source-acceptance
  (doseq [[engine version] lanes]
    (testing (str (name engine) " v" version)
      (let [[parked w] (s/parked-writer engine)
            t (s/toy)
            peer (s/served-peer t)
            export (lift parked version t peer #{:reader :writer})
            recv (:vm (handoff/resume-task
                        (s/new-machine engine {:attach-stream (s/attacher t)})
                        (:bytes export) (s/attacher t)
                        {:address (:address export)}))
            ;; the source target stays full through the first drive
            done (drive-peers recv [peer] 6)
            _ (s/serve-mirror! peer)
            held (stream/next w 0)
            later (drive-peers done [peer] 6)
            _ (s/serve-mirror! peer)
            landed (stream/next w 0)]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :warmed (:dao.stream/value held)))
        (check engine :writer :source-acceptance (vm/blocked? done)
               "the retry stays parked while the source refuses it")
        (check engine :writer :source-acceptance
               (and (vm/halted? later) (= "v" (:dao.stream/value landed)))
               "once the source accepts, the retained value lands")))))
