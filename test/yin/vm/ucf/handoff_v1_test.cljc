(ns yin.vm.ucf.handoff-v1-test
  "M-next D7 (UCF 7.2.1, the r3 reader pipeline with residual 3's
   two-codec split): the version-aware reader over canonical bytes.

   Every version-1 fixture is a real export body -- a machine the engine
   itself parked, lifted by `export-task` -- raised to version 1 with the
   7.2.1 custody header and encoded through the landed jing codec, so
   the reader's decisions are made on canonical bytes, never on host
   number shapes (an integral float version is a float on every host).

   Every refusal asserts the whole observable: the data outcome with its
   path, zero attach calls and no machine.  The D7 reader has no
   proposal path -- the D13 driver owns proposals -- so a refusal that
   attaches nothing and assembles no machine leaves nothing that could
   ever have proposed.

   The scaffolding below is this namespace's own copy of the stage-1
   harness (yin.vm.ucf.handoff-test): the D7 slice keeps its diff to
   handoff.cljc and this file, so nothing was moved out of the stage-1
   suite."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as jing.cbor]
            [dao.jing.cbor-fixtures :as fx]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.cbor :as cbor]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.lift-support :as support]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The composition harness: machines, the toy channel, the served table
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- make-ring-stream
  [capacity]
  (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                       ringbuffer/capacity-key capacity}))


(defn- new-machine
  []
  (semantic/create-vm {:make-stream make-ring-stream
                       :capability-secret tu/secret
                       :modules (module/default-registry)
                       :secret-source (fn [origin]
                                        (str tu/secret "/"
                                             (name origin)))}))


(def ^:private semantic-vector
  (comp :vector linearize/lower-rows vm/ast->semantic-bytecode))


(defn- load-ast
  [machine ast]
  (semantic/load-vector machine (semantic-vector ast)
                        vm/semantic-contract))


(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [n]
  {:type :variable, :name n})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn- then
  [first-ast second-ast]
  (app (lam ['_] second-ast) first-ast))


(defn- cursor-of
  [source]
  {:type :stream/cursor, :source source})


(defn- next-of
  [source]
  {:type :stream/next, :source source})


(defn- let1
  [param init body]
  (app (lam [param] body) init))


(defn- toy
  []
  (let [ab (ring 64)
        ba (ring 64)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "ucf-handoff-toy"}]
    {:channel cd, :ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn- server
  "The exporter's `serve!`: a stable identity per handle, entering it in
   a fresh table under the toy channel."
  [table chan]
  (fn [h]
    (if-let [existing (some (fn [[id e]] (when (= h (:handle e)) id))
                            @table)]
      {:dao.stream/identity existing, :dao.stream/channel chan}
      (let [id (str (gensym "seg"))]
        (swap! table assoc id {:handle h})
        {:dao.stream/identity id, :dao.stream/channel chan}))))


(defn- attacher
  [t]
  (remote/attacher
    {:dao.stream.remote/channels {(:channel t) (:a-end t)}}))


;; =============================================================================
;; The parked machines every fixture body is raised from
;; =============================================================================

(defn- parked-reader
  "A real machine parked on the second read of the stream it makes, A
   consumed: one :next frame over one cell."
  []
  (let [m0 (vm/run (load-ast (new-machine)
                             (let1 's {:type :stream/make, :buffer 4}
                                   (let1 'c (cursor-of (v 's))
                                         (then (next-of (v 'c))
                                               (next-of (v 'c)))))))
        entry (first (:wait-set m0))
        src (get (:resources m0) (:stream-id entry))]
    (stream/append! src "A")
    (vm/run m0)))


(defn- linking-machine
  [request response]
  (semantic/create-vm
    {:make-stream make-ring-stream,
     :capability-secret tu/secret,
     :modules (module/default-registry),
     :secret-source (fn [origin] (str tu/secret "/" (name origin))),
     :link-request request,
     :link-response response}))


(defn- module-response
  []
  {:status :ok,
   :image {:value (semantic-vector
                    (let1 's {:type :stream/make, :buffer 4}
                          (then (next-of (cursor-of (v 's)))
                                (def! 'f (lam [] (lit 42))))))},
   :manifest {:yin.module/name 'host.mod,
              :yin.module/exports #{'f}},
   :obligations []})


(defn- parked-installer
  "A real machine parked at :install with a live blocked child."
  []
  (let [request (ring 64)
        response (ring 64)
        m0 (vm/run (load-ast (linking-machine request response)
                             (then (app (v 'require) (lit 'host.mod))
                                   (app (v 'host.mod/f)))))
        entry (first (:wait-set m0))]
    (stream/append! response
                    (assoc (module-response)
                           :yin.link/id (:link-id entry)))
    (vm/run m0)))


(defn- halted-machine
  []
  (vm/run (load-ast (new-machine) (lit 42))))


(defn- export-of
  "The export of `parked` over a fresh toy's served table."
  [parked]
  (let [t (toy)]
    (handoff/export-task parked (server (atom {}) (:channel t)))))


;; =============================================================================
;; The reader under test, and the refusal assertions
;; =============================================================================

(declare lower-options)


(defn- read!
  "The version-aware reader over a counting attach!, on a receiving task
   that composes the attach seam an install child's spawn resolves its
   streams through.  Answers [outcome attaches]."
  ([bytes] (read! bytes nil))
  ([bytes opts]
   (let [opts (if (= ::granted opts) (lower-options bytes) opts)
         attaches (atom 0)
         t (toy)
         base (attacher t)
         attach! (fn [descriptor]
                   (swap! attaches inc)
                   (base descriptor))]
     [(handoff/resume-task (assoc (new-machine)
                                  :attach-stream attach!)
                           bytes attach! opts)
      @attaches])))


(defn- refused
  "`bytes` (under `opts`) refuse with `status` and exactly the `data`
   keys given, attaching nothing and assembling no machine."
  ([bytes status data] (refused bytes nil status data))
  ([bytes opts status data]
   (let [[r n] (read! bytes opts)]
     (is (= status (:yin.k/status r)) (pr-str r))
     (is (= data (select-keys r (keys data))) (pr-str r))
     (is (zero? n) "no stream was attached")
     (is (not (contains? r :vm)) "no machine was assembled")
     r)))


;; =============================================================================
;; Custody parts and the version-1 fixture bases
;; =============================================================================

(def occurrence "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")
(def predecessor "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02")


(def origin
  {:yin.k/occurrence predecessor
   :dao.lease/lease "lease-7"
   :yin.k/emitter "peer-a"})


(def arbitration
  {:dao.stream/identity "arbitration-1"
   :dao.stream/descriptor {:dao.stream/type :remote :peer "peer-b"}})


(defn- op-id
  ([n] (op-id predecessor n))
  ([occ n] {:yin.k/occurrence occ :yin.k/seq n}))


(defn- with-header
  "A real export body raised to version 1 under the 7.2.1 custody header
   with counter `n`; `o` nil is the first park."
  ([b n] (with-header b n origin))
  ([b n o]
   (cond-> (assoc b :yin.k/version 1
                  :yin.k/policy :yin.k/exclusive
                  :yin.k/occurrence occurrence
                  :yin.k/arbitration arbitration
                  :yin.k/next-op-seq n)
     (some? o) (assoc :yin.k/origin o))))


(def ^:private blocked-export (delay (export-of (parked-reader))))
(def ^:private install-export (delay (export-of (parked-installer))))
(def ^:private halted-export (delay (export-of (halted-machine))))


(defn- blocked-v0
  []
  (:body @blocked-export))


(defn- blocked-v1
  []
  (with-header (blocked-v0) 3))


(defn- install-v0
  []
  (:body @install-export))


(defn- install-child
  "The install fixture's child body, at version 1 like its root."
  []
  (assoc-in (install-v0) [:yin.k/installs 'host.mod :yin.k/child :yin.k/version]
            1))


(defn- install-v1
  []
  (with-header (install-child) 6))


(defn- halted-v1
  []
  (assoc (:body @halted-export)
         :yin.k/version 1 :yin.k/origin origin))


(defn- put-pending
  "A :put pending over the blocked body's own first cell stream,
   carrying `id` when some."
  [id]
  (cond-> {:yin.k/reason :put
           :yin.k/stream (get-in (blocked-v0)
                                 [:yin.k/cells :yin.k/c-0 :yin.k/stream])
           :yin.k/value "x"}
    (some? id) (assoc :yin.k/op-id id)))


(defn- segment-address
  [bs]
  (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bs))))


(defn lower-options
  [bytes]
  (let [body (jing.cbor/decode bytes)
        identities (into #{}
                         (comp (filter #(and (map? %)
                                             (= :yin.k/stream (:yin.k/tag %))))
                               (map :dao.stream/identity))
                         (tree-seq coll? seq body))
        address (segment-address bytes)
        occurrence (:yin.k/occurrence body)]
    {:address address
     :protection (zipmap identities (repeat :at-least-once))
     :grant {:checkpoint address
             :dao.lease/lease "lease-new"
             :dao.lease/holder "holder-new"
             :tenure {:now 10 :bound 20 :live true}
             :evidence {:yin.k/status :yin.k/ready
                        :yin.k/binding
                        {:yin.k/occurrence occurrence
                         :dao.lease/lease "lease-new"
                         :dao.lease/holder "holder-new"
                         :yin.k/epoch 2
                         :yin.k/transaction
                         {:yin.k/arbitration (get-in body [:yin.k/arbitration
                                                           :dao.stream/identity])}}
                        :yin.k/enrolled #{}
                        :yin.k/prefix {:yin.k/occurrence occurrence
                                       :dao.lease/lease "lease-new"
                                       :yin.k/frontier 0
                                       :yin.k/inputs []}}}}))


(deftest v1-grant-boundary-and-consistency
  (let [bytes (jing.cbor/encode (blocked-v1))
        opts (lower-options bytes)
        occurrence (get-in opts [:grant :evidence :yin.k/binding :yin.k/occurrence])]
    (refused bytes :yin.k/awaiting-grant {})
    (doseq [[path value]
            [[[:grant :checkpoint] :segment/other]
             [[:grant :evidence :yin.k/binding :yin.k/occurrence] predecessor]
             [[:grant :evidence :yin.k/binding :dao.lease/lease] "wrong"]
             [[:grant :evidence :yin.k/binding :dao.lease/holder] "wrong"]
             [[:grant :evidence :yin.k/binding :yin.k/transaction
               :yin.k/arbitration] "wrong"]
             [[:grant :evidence :yin.k/binding :yin.k/epoch] -1]
             [[:grant :evidence :yin.k/binding :yin.k/epoch] (jing.cbor/float64 1)]
             [[:grant :evidence :yin.k/binding :yin.k/epoch] 4503599627370496]
             [[:grant :tenure :now] 20]
             [[:grant :tenure :live] false]
             [[:grant :evidence :yin.k/prefix :dao.lease/lease] "wrong"]
             [[:grant :evidence :yin.k/prefix :yin.k/occurrence] predecessor]
             [[:grant :evidence :yin.k/prefix :yin.k/frontier] 1]
             [[:grant :evidence :yin.k/prefix] {:yin.k/occurrence occurrence :dao.lease/lease "lease-new" :yin.k/frontier 2 :yin.k/inputs [{:yin.k/input-seq 0} {:yin.k/input-seq 0}]}]
             [[:grant :evidence :yin.k/prefix] {:yin.k/occurrence occurrence :dao.lease/lease "lease-new" :yin.k/frontier 2 :yin.k/inputs [{:yin.k/input-seq 0} {:yin.k/input-seq 2}]}]
             [[:grant :evidence :yin.k/prefix] {:yin.k/occurrence occurrence :dao.lease/lease "lease-new" :yin.k/frontier 2 :yin.k/inputs [{:yin.k/input-seq 1} {:yin.k/input-seq 0}]}]]]
      (testing (pr-str path)
        (refused bytes (assoc-in opts path value) :yin.k/not-holder {})))
    (refused bytes (assoc-in opts [:grant :evidence]
                             {:yin.k/status :yin.k/unsatisfied
                              :yin.k/reason :gap})
             :yin.k/unsatisfied {:yin.k/reason :gap})
    (refused bytes (assoc-in opts [:grant :evidence :yin.k/prefix]
                             {:yin.k/status :suspended :yin.k/reason :unavailable})
             :yin.k/unsatisfied {:yin.k/reason :unavailable})))


(deftest malformed-lower-options-are-caller-defects
  (let [bytes (jing.cbor/encode (blocked-v1))
        opts (lower-options bytes)]
    (doseq [[path value] [[[:grant] []]
                          [[:grant :evidence] []]
                          [[:grant :evidence :yin.k/binding] []]
                          [[:grant :tenure] []]
                          [[:protection] {"stream" :unknown}]]]
      (is (try (read! bytes (assoc-in opts path value)) false
               (catch #?(:cljd Object :clj Exception :cljs :default) exception
                 (nil? (:yin.k/status (ex-data exception)))))))))


(deftest v1-restores-custody-counter-prefix-and-ended-result
  (doseq [counter [3 4503599627370495]]
    (let [bytes (jing.cbor/encode (assoc (blocked-v1) :yin.k/next-op-seq counter))
          opts (assoc-in (lower-options bytes) [:grant :evidence :yin.k/prefix]
                         {:yin.k/occurrence occurrence :dao.lease/lease "lease-new"
                          :yin.k/frontier 1
                          :yin.k/inputs [{:yin.k/input-seq 0
                                          :yin.k/source {} :yin.k/observed 42}]})
          [out] (read! bytes opts)
          machine (:vm out)]
      (is (= :ok (:status out)))
      (is (= :running (:yin.k/gate machine)))
      (is (= counter (get-in machine [:yin.k/custody :yin.k/next-op-seq])))
      (is (= {:next 0 :prefix (get-in opts [:grant :evidence :yin.k/prefix])}
             (get-in machine [:yin.k/custody :input])))
      (is (= {:bound 20} (get-in machine [:yin.k/custody :tenure])))))
  (let [[out] (read! (jing.cbor/encode (halted-v1)))]
    (is (= :ended (:yin.k/gate (:vm out))))
    (is (not (contains? (:vm out) :yin.k/custody)))))


(deftest v1-protection-and-retained-put
  (doseq [id [nil (op-id 1)]
          class [:enrolled :at-least-once :fail-stop]]
    (let [base (with-header (:body (export-of (support/parked-writer))) 3)
          body (cond-> base id (assoc-in [:yin.k/frames 0 :yin.k/pending :yin.k/op-id] id))
          bytes (jing.cbor/encode body)
          target (get-in body [:yin.k/frames 0 :yin.k/pending :yin.k/stream :dao.stream/identity])
          opts (-> (lower-options bytes)
                   (assoc-in [:protection target] class)
                   (assoc-in [:grant :evidence :yin.k/enrolled]
                             (if (= class :enrolled) #{target} #{})))]
      (if (= (some? id) (= class :enrolled))
        (let [[out] (read! bytes opts)]
          (is (= :ok (:status out)))
          (is (= id (:op-id (first (:wait-set (:vm out))))))
          (is (= class (get-in out [:vm :yin.k/custody :protection target]))))
        (refused bytes opts :yin.k/unsatisfied {:dao.stream/identity target}))))
  (let [bytes (jing.cbor/encode (blocked-v1))
        opts (lower-options bytes)
        target (first (keys (:protection opts)))]
    (refused bytes (update opts :protection dissoc target)
             :yin.k/unsatisfied {:dao.stream/identity target})
    (refused bytes (assoc-in opts [:protection target] :enrolled)
             :yin.k/unsatisfied {:dao.stream/identity target})
    (refused bytes (assoc-in opts [:grant :evidence :yin.k/enrolled] #{target})
             :yin.k/unsatisfied {:dao.stream/identity target})))


(deftest v1-restored-puts-have-stamped-issues-and-clear-receiver-state
  (let [child-base (assoc (:body (export-of (support/parked-writer))) :yin.k/version 1)
        frame (first (:yin.k/frames child-base))
        child-base (assoc child-base :yin.k/frames [frame frame])
        body (assoc-in (install-v1) [:yin.k/installs 'host.mod :yin.k/child] child-base)
        bytes (jing.cbor/encode body)
        target (get-in child-base [:yin.k/frames 0 :yin.k/pending :yin.k/stream :dao.stream/identity])
        opts (assoc-in (lower-options bytes) [:protection target] :at-least-once)
        recv (assoc (new-machine)
                    :yin.k/issued 999
                    :yin.k/closes [{:stream-id "fake" :yin.k/issue 998}])
        out (handoff/resume-task recv bytes (attacher (toy)) opts)
        machine (:vm out)
        child (get-in machine [:installs 'host.mod :vm])]
    (is (= :ok (:status out)))

    (testing "Receiver-local close/issue state is removed"
      (is (not (contains? machine :yin.k/closes)))
      (is (not (contains? child :yin.k/closes))))

    (testing "Rebuilt next counter and wait order for child"
      (let [[wait1 wait2] (:wait-set child)]
        (is (= 0 (:yin.k/issue wait1)) "First restored put pinned in wait order")
        (is (= 1 (:yin.k/issue wait2)) "Second restored put pinned in wait order")
        (is (= 2 (:yin.k/issued child)) "Rebuilt next counter is 2")
        (let [issue-number @#'engine/issue-number
              [close-issue child'] (issue-number child)
              [put-issue _] (issue-number child')]
          (is (= 2 close-issue) "new close ordering")
          (is (= 3 put-issue) "new put ordering"))))

    (testing "Rebuilt next counter for root without puts"
      (is (= 0 (:yin.k/issued machine)))
      (let [issue-number @#'engine/issue-number
            [close-issue _] (issue-number machine)]
        (is (= 0 close-issue))))))


(deftest v1-child-state-and-receiver-isolation
  (let [bytes (jing.cbor/encode (install-v1))
        [out] (read! bytes (lower-options bytes))
        child (get-in out [:vm :installs 'host.mod :vm])]
    (is (= :ok (:status out)))
    (is (= :running (:yin.k/gate child)))
    (is (not (contains? child :yin.k/custody)))
    (is (= :next (:reason (first (:wait-set child)))))
    (is (= [] (:ready-queue child))))
  (let [bytes (jing.cbor/encode (blocked-v1))
        out (handoff/resume-task
              (assoc (new-machine) :store {'receiver-only 99}
                     :module-stores {'receiver.mod {'x 99}}
                     :modules (module/assoc-module (module/default-registry)
                                                   'receiver.mod
                                                   {:manifest {:yin.module/name 'receiver.mod}
                                                    :address :segment/receiver
                                                    :slice {'x 99} :bindings {'x 99}}))
              bytes (attacher (toy)) (lower-options bytes))]
    (is (= :ok (:status out)))
    (is (not (contains? (:store (:vm out)) 'receiver-only)))
    (is (not (contains? (:module-stores (:vm out)) 'receiver.mod)))
    (is (nil? (module/resolve-module (:modules (:vm out)) 'receiver.mod)))))


(deftest v1-ffi-and-link-ids-and-kept-cursors
  (let [call-in (support/one-slot-stream "call-in")
        call-out (ring 8)
        _ (stream/append! call-in :warmed)
        caller (semantic/create-vm
                 {:make-stream make-ring-stream :capability-secret tu/secret
                  :modules (module/default-registry)
                  :call-in call-in :call-out call-out
                  :call-out-cursor (vm/mint-oldest call-out :test)})
        ffi (vm/run (load-ast caller
                              {:type :dao.stream.apply/call :op :op/echo
                               :operands [(lit "hello")]}))
        request (support/one-slot-stream "link-in")
        _ (stream/append! request :warmed)
        link (vm/run (load-ast (linking-machine request (ring 8))
                               (app (v 'require) (lit 'host.mod))))]
    (doseq [[machine reason] [[ffi :ffi-request] [link :link-request]]]
      (let [body (-> (:body (export-of machine))
                     (with-header 3)
                     (assoc-in [:yin.k/frames 0 :yin.k/pending :yin.k/op-id]
                               (op-id 1)))
            pending (get-in body [:yin.k/frames 0 :yin.k/pending])
            target (get-in pending [:yin.k/request :dao.stream/identity])
            bytes (jing.cbor/encode body)
            opts (-> (lower-options bytes)
                     (assoc-in [:protection target] :enrolled)
                     (assoc-in [:grant :evidence :yin.k/enrolled] #{target}))
            [out] (read! bytes opts)
            entry (first (:wait-set (:vm out)))]
        (is (= reason (:yin.k/reason pending)))
        (is (= :ok (:status out)) (pr-str (dissoc out :vm)))
        (is (= (op-id 1) (:op-id entry)))
        (when (= reason :link-request)
          (is (= (get-in body [:yin.k/cells (:yin.k/cell pending) :yin.k/position])
                 (:cursor entry))))))))


(deftest v1-child-protection-refuses-before-root-attachment
  (let [body (install-v1)
        bytes (jing.cbor/encode body)
        opts (lower-options bytes)
        target (get-in body [:yin.k/installs 'host.mod :yin.k/child
                             :yin.k/cells :yin.k/c-0 :yin.k/stream
                             :dao.stream/identity])]
    (refused bytes (update opts :protection dissoc target)
             :yin.k/unsatisfied {:dao.stream/identity target}))
  (doseq [id [nil (op-id 1)]]
    (let [child (cond-> (assoc (:body (export-of (support/parked-writer)))
                               :yin.k/version 1)
                  id (assoc-in [:yin.k/frames 0 :yin.k/pending :yin.k/op-id] id))
          body (assoc-in (install-v1) [:yin.k/installs 'host.mod :yin.k/child] child)
          bytes (jing.cbor/encode body)
          target (get-in child [:yin.k/frames 0 :yin.k/pending :yin.k/stream
                                :dao.stream/identity])
          opts (cond-> (lower-options bytes)
                 (nil? id) (assoc-in [:protection target] :enrolled)
                 (nil? id) (assoc-in [:grant :evidence :yin.k/enrolled] #{target}))]
      (refused bytes opts :yin.k/unsatisfied {:dao.stream/identity target}))))


(deftest v1-child-initialization-is-not-replayed
  (let [request (ring 8)
        response (ring 8)
        child-image (semantic-vector
                      (then {:type :dao.stream.apply/call :op :op/init
                             :operands [(lit 1)]}
                            (next-of (cursor-of {:type :stream/make :buffer 4}))))
        parent (semantic/create-vm
                 {:make-stream make-ring-stream :capability-secret tu/secret
                  :secret-source (fn [origin] (str tu/secret "/" (name origin)))
                  :modules (module/default-registry)
                  :link-request request :link-response response})
        waiting (vm/run (load-ast parent (app (v 'require) (lit 'host.mod))))
        _ (stream/append! response
                          {:yin.link/id (:link-id (first (:wait-set waiting)))
                           :status :ok :image {:value child-image}
                           :manifest {:yin.module/name 'host.mod
                                      :yin.module/exports #{}} :obligations []})
        initializing (vm/run waiting)
        child (get-in initializing [:installs 'host.mod :vm])
        effects (get (:resources child) vm/call-in-stream-key)
        init-call (first (:wait-set child))
        _ (stream/append! (get (:resources child) vm/call-out-stream-key)
                          (apply2/success-response (:call-id init-call) 1))
        completed-child (vm/run child)]
    (is (= 1 (count (tu/drain effects))))
    (let [saved (assoc-in initializing [:installs 'host.mod :vm]
                          (vm/run completed-child))
          body (-> (:body (export-of saved))
                   (with-header 3)
                   (assoc-in [:yin.k/installs 'host.mod :yin.k/child :yin.k/version] 1))
          bytes (jing.cbor/encode body)
          [out] (read! bytes (lower-options bytes))]
      (is (= :ok (:status out)) (pr-str (dissoc out :vm)))
      (is (= :next (:reason (first (get-in out [:vm :installs 'host.mod :vm :wait-set])))))
      (is (= 1 (count (tu/drain effects))))
      (is (= 1 (count (tu/drain request)))))))


(deftest v1-lower-supported-kernel-boundary
  (let [opts {:make-stream make-ring-stream :capability-secret tu/secret
              :modules (module/default-registry)}
        ast (next-of (cursor-of {:type :stream/make :buffer 4}))
        kernels
        [[:walker #(walker/create-vm opts)
          #(walker/vm-load-rows % (vm/ast->semantic-bytecode ast) vm/ast-contract)]
         [:semantic #(semantic/create-vm opts) #(load-ast % ast)]
         [:stack #(stack/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                   (assoc opts :contract vm/stack-contract)) identity]
         [:register #(register/create-vm (:image (rc/adapt (vm/ast->datoms ast)))
                                         (assoc opts :contract vm/register-contract)) identity]]]
    (doseq [[kernel create load] kernels]
      (testing (name kernel)
        (let [source (vm/run (load (create)))
              exported (export-of source)]
          (if (= kernel :semantic)
            (let [body (with-header (:body exported) 9)
                  bytes (jing.cbor/encode body)
                  out (handoff/resume-task (create) bytes (attacher (toy))
                                           (lower-options bytes))]
              (is (= :ok (:status out)) (pr-str (dissoc out :vm)))
              (is (= 9 (get-in out [:vm :yin.k/custody :yin.k/next-op-seq])))
              (is (= :running (get-in out [:vm :yin.k/gate])))
              (is (= :next (:reason (first (:wait-set (:vm out)))))))
            (do
              (is (= :yin.k/non-portable (:yin.k/status exported)))
              (is (= :unaddressed-segment (:yin.k/kind exported))))))))))


(deftest d9-blocked-and-parked-fixtures-restore-with-explicit-grants
  (doseq [source [support/parked-explicit support/parked-two-cursors
                  #(support/with-op-id (support/parked-writer) :put (op-id 1))]]
    (let [machine (source)
          carried? (:op-id (first (:wait-set machine)))
          lifted (support/lift machine (support/header 3 support/origin
                                                       (if carried? #{"s0"} #{})))
          _ (is (= :ok (:status lifted)) (pr-str (dissoc lifted :record :body :bytes)))
          bytes (:bytes lifted)
          body (:body lifted)
          target (get-in body [:yin.k/frames 0 :yin.k/pending :yin.k/stream
                               :dao.stream/identity])
          opts (cond-> (lower-options bytes)
                 carried? (assoc-in [:protection target] :enrolled)
                 carried? (assoc-in [:grant :evidence :yin.k/enrolled] #{target}))
          attached (atom {})
          attach! (fn [descriptor]
                    (let [handle (or (get @attached descriptor)
                                     (support/one-slot-stream (str (count @attached))))]
                      (swap! attached assoc descriptor handle)
                      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle handle}))
          out (handoff/resume-task (assoc (new-machine) :attach-stream attach!)
                                   bytes attach! opts)
          receiver (:vm out)
          references (filter #(and (map? %) (= :cursor-ref (:type %)))
                             (tree-seq coll? seq [(:store receiver)
                                                  (:module-stores receiver)]))]
      (is (= :ok (:status out)) (pr-str (dissoc out :vm)))
      (is (= (:kind lifted) (:kind out)))
      (is (= :running (:yin.k/gate receiver)))
      (is (every? #(engine/authentic-ref? receiver :cursor-ref %) references))
      (is (= (count (:yin.k/cells body))
             (count (filter #(and (map? %) (contains? % :cursor))
                            (vals (apply dissoc (:resources receiver)
                                         [vm/call-out-cursor-key]))))))
      (when carried?
        (is (= (op-id 1) (:op-id (first (:wait-set receiver)))))))))


;; =============================================================================
;; The two-codec split: which codec accepted decides the version
;; =============================================================================

(deftest the-accepting-codec-decides-the-body-version
  (testing "a version-1 body rides jing canonical bytes end to end"
    (let [[r n] (read! (jing.cbor/encode (blocked-v1)) ::granted)]
      (is (= :ok (:status r)) (pr-str r))
      (is (pos? n) "the valid body attached its streams")
      (is (= :blocked (:kind r)))))
  (testing "the same body in stream-codec bytes is a profile mismatch"
    (refused (cbor/encode (blocked-v1))
             :yin.k/profile-mismatch
             {:yin.k/version 1 :yin.k/supported #{0}}))
  (testing "a version-0 body in jing bytes is a profile mismatch,
            never retried through the other codec"
    (refused (jing.cbor/encode (blocked-v0))
             :yin.k/profile-mismatch
             {:yin.k/version 0 :yin.k/supported #{1 2}}))
  (testing "a version-1 structural failure is not reinterpreted as
            version 0: the custody refusal stands"
    (refused (jing.cbor/encode (assoc (blocked-v1) :yin.k/policy :yin.k/fork))
             :yin.k/undecodable
             {:yin.k/path [:yin.k/policy]}))
  (testing "bytes neither codec decodes"
    (refused (fx/hex->bytes "d86301")
             :yin.k/undecodable
             {:yin.k/kind :bytes}))
  (testing "a valid version-0 body still lowers as a fork on a reader
            that speaks both, and is refused when exclusive is required"
    (let [[r] (read! (:bytes @blocked-export))]
      (is (= :ok (:status r)) (pr-str r)))
    (refused (:bytes @blocked-export)
             {:exclusive true}
             :yin.k/profile-mismatch
             {:yin.k/version 0 :yin.k/supported #{1 2}
              :yin.k/policy :yin.k/exclusive})))


;; =============================================================================
;; The version gate: before the address check, on the integer kind
;; =============================================================================

(deftest the-version-gate-precedes-the-address-check
  (let [wrong (segment-address (jing.cbor/encode :other))]
    (testing "an unsupported version wins over a wrong address"
      (refused (jing.cbor/encode (assoc (blocked-v1) :yin.k/version 2))
               {:address wrong}
               :yin.k/profile-mismatch
               {:yin.k/path [:yin.k/contract]}))
    (testing "an absent version"
      (refused (jing.cbor/encode (dissoc (blocked-v1) :yin.k/version))
               :yin.k/profile-mismatch
               {:yin.k/version nil :yin.k/supported #{1 2}}))
    (testing "an integral float is no version, on any host"
      (let [[r n] (read! (jing.cbor/encode
                           (assoc (blocked-v1)
                                  :yin.k/version (jing.cbor/float64 1))))]
        (is (= :yin.k/profile-mismatch (:yin.k/status r)) (pr-str r))
        (is (= #{1 2} (:yin.k/supported r)))
        (is (jing.cbor/float64? (:yin.k/version r)))
        (is (zero? n))
        (is (not (contains? r :vm)))))
    (testing "a supported version under a wrong address"
      (let [bytes (jing.cbor/encode (blocked-v1))
            [r n] (read! bytes {:address wrong})]
        (is (= :yin.k/hash-mismatch (:yin.k/status r)) (pr-str r))
        (is (= wrong (:yin.k/address r)))
        (is (= (segment-address bytes) (:yin.k/computed r)))
        (is (zero? n))
        (is (not (contains? r :vm)))))))


;; =============================================================================
;; The mixed tree
;; =============================================================================

(deftest a-version-0-child-in-a-version-1-root-is-undecodable
  (refused (jing.cbor/encode
             (assoc-in (install-v1)
                       [:yin.k/installs 'host.mod :yin.k/child :yin.k/version]
                       0))
           :yin.k/undecodable
           {:yin.k/path [:yin.k/installs 'host.mod :yin.k/child
                         :yin.k/version]
            :yin.k/kind :mixed-version}))


;; =============================================================================
;; The custody header, through the reader
;; =============================================================================

(deftest custody-header-breachs-refuse-undecodable-naming-the-path
  (let [child-path [:yin.k/installs 'host.mod :yin.k/child]
        cases [["missing-policy" blocked-v1
                #(dissoc % :yin.k/policy)
                {:yin.k/path [:yin.k/policy] :yin.k/kind :missing-header}]
               ["missing-occurrence" blocked-v1
                #(dissoc % :yin.k/occurrence)
                {:yin.k/path [:yin.k/occurrence] :yin.k/kind :missing-header}]
               ["missing-arbitration" blocked-v1
                #(dissoc % :yin.k/arbitration)
                {:yin.k/path [:yin.k/arbitration]
                 :yin.k/kind :missing-header}]
               ["missing-next-op-seq" blocked-v1
                #(dissoc % :yin.k/next-op-seq)
                {:yin.k/path [:yin.k/next-op-seq]
                 :yin.k/kind :missing-header}]
               ["fork-policy" blocked-v1
                #(assoc % :yin.k/policy :yin.k/fork)
                {:yin.k/path [:yin.k/policy]}]
               ["nil-occurrence" blocked-v1
                #(assoc % :yin.k/occurrence nil)
                {:yin.k/path [:yin.k/occurrence]
                 :yin.k/kind :nil-occurrence}]
               ["origin-is-self" blocked-v1
                #(assoc-in % [:yin.k/origin :yin.k/occurrence] occurrence)
                {:yin.k/path [:yin.k/origin :yin.k/occurrence]
                 :yin.k/kind :origin-is-self}]
               ["child-header" install-v1
                #(assoc-in % (conj child-path :yin.k/occurrence) occurrence)
                {:yin.k/path (conj child-path :yin.k/occurrence)
                 :yin.k/kind :child-header}]
               ["halted-root-header" halted-v1
                #(assoc % :yin.k/occurrence occurrence)
                {:yin.k/path [:yin.k/occurrence]
                 :yin.k/kind :halted-header}]
               ["halted-root-without-origin" halted-v1
                #(dissoc % :yin.k/origin)
                {:yin.k/path [:yin.k/origin]
                 :yin.k/kind :missing-header}]]]
    (doseq [[name base f expected] cases]
      (testing name
        (refused (jing.cbor/encode (f (base)))
                 :yin.k/undecodable
                 expected)))))


(deftest a-halted-root-with-its-origin-alone-validates
  (let [[r n] (read! (jing.cbor/encode (halted-v1)))]
    (is (= :ok (:status r)) (pr-str r))
    (is (zero? n) "a halted body names no stream")
    (is (= :halted (:kind r)))))


(deftest a-halted-body-forbids-frames
  (let [tampered (-> (blocked-v1)
                     (assoc :yin.k/kind :halted)
                     (assoc :yin.k/origin origin)
                     (dissoc :yin.k/policy :yin.k/occurrence
                             :yin.k/arbitration :yin.k/next-op-seq))]
    (refused (jing.cbor/encode tampered)
             :yin.k/undecodable
             {:yin.k/path [:yin.k/frames]})))


(deftest a-halted-install-child-carries-no-custody-of-its-own
  (let [halted-child (fn [o]
                       (let [base (-> (get-in (install-child)
                                              [:yin.k/installs 'host.mod
                                               :yin.k/child])
                                      (dissoc :yin.k/frames :yin.k/cells)
                                      (assoc :yin.k/kind :halted
                                             :yin.k/result 42))]
                         (if (some? o) (assoc base :yin.k/origin o) base)))
        at #(assoc-in (install-v1)
                      [:yin.k/installs 'host.mod :yin.k/child] %)]
    (testing "an origin on the child refuses"
      (refused (jing.cbor/encode (at (halted-child origin)))
               :yin.k/undecodable
               {:yin.k/path [:yin.k/installs 'host.mod :yin.k/child
                             :yin.k/origin]
                :yin.k/kind :child-header}))
    (testing "without one, the halted child validates and the whole
              body lowers"
      (let [[r] (read! (jing.cbor/encode (at (halted-child nil))) ::granted)]
        (is (= :ok (:status r)) (pr-str r))
        (is (contains? (:installs (:vm r)) 'host.mod)
            "the child lowered beside its root")))))


;; =============================================================================
;; Carried ids, checked in the root's context
;; =============================================================================

(deftest carried-ids-refuse-undecodable-in-the-roots-context
  (testing "an id on :next, a variant that carries none"
    (refused (jing.cbor/encode
               (assoc-in (blocked-v1)
                         [:yin.k/frames 0 :yin.k/pending :yin.k/op-id]
                         (op-id 1)))
             :yin.k/undecodable
             {:yin.k/path [:yin.k/frames 0 :yin.k/pending :yin.k/op-id]
              :yin.k/kind :op-id-on-variant}))
  (testing "a sequence at the counter"
    (refused (jing.cbor/encode
               (assoc-in (blocked-v1) [:yin.k/frames 0 :yin.k/pending]
                         (put-pending (op-id 3))))
             :yin.k/undecodable
             {:yin.k/path [:yin.k/frames 0 :yin.k/pending :yin.k/op-id
                           :yin.k/seq]
              :yin.k/kind :op-seq-range}))
  (testing "a duplicate id"
    (let [twice (fn [b id]
                  (let [frame (fn [pending]
                                (assoc-in (first (:yin.k/frames b))
                                          [:yin.k/pending] pending))]
                    (assoc b :yin.k/frames [(frame (put-pending id))
                                            (frame (put-pending id))])))]
      (refused (jing.cbor/encode (twice (blocked-v1) (op-id 1)))
               :yin.k/undecodable
               {:yin.k/path [:yin.k/frames 1 :yin.k/pending :yin.k/op-id]
                :yin.k/kind :duplicate-op-id})))
  (testing "an id in a body without an origin"
    (refused (jing.cbor/encode
               (assoc-in (with-header (blocked-v0) 0 nil)
                         [:yin.k/frames 0 :yin.k/pending]
                         (put-pending (op-id 0))))
             :yin.k/undecodable
             {:yin.k/path [:yin.k/frames 0 :yin.k/pending :yin.k/op-id]
              :yin.k/kind :op-id-without-origin}))
  (testing "an id naming the body's own occurrence"
    (refused (jing.cbor/encode
               (assoc-in (blocked-v1) [:yin.k/frames 0 :yin.k/pending]
                         (put-pending (op-id occurrence 0))))
             :yin.k/undecodable
             {:yin.k/path [:yin.k/frames 0 :yin.k/pending :yin.k/op-id]
              :yin.k/kind :op-id-own-occurrence}))
  (testing "a child's id against the root's counter"
    (let [cells (get-in (install-v1)
                        [:yin.k/installs 'host.mod :yin.k/child
                         :yin.k/cells])]
      (refused (jing.cbor/encode
                 (assoc-in (install-v1)
                           [:yin.k/installs 'host.mod :yin.k/child
                            :yin.k/frames 0 :yin.k/pending]
                           {:yin.k/reason :put
                            :yin.k/stream (get-in cells
                                                  [:yin.k/c-0
                                                   :yin.k/stream])
                            :yin.k/value 1
                            :yin.k/op-id (op-id 6)}))
               :yin.k/undecodable
               {:yin.k/path [:yin.k/installs 'host.mod :yin.k/child
                             :yin.k/frames 0 :yin.k/pending :yin.k/op-id
                             :yin.k/seq]
                :yin.k/kind :op-seq-range}))))


;; =============================================================================
;; Clause 5 on both versions: reasons, install entries, phases
;; =============================================================================

(deftest clause-five-refuses-on-both-versions
  (testing "a :park reason is no wire reason, on either version"
    (doseq [[n encode base] [["version 0" cbor/encode blocked-v0]
                             ["version 1" jing.cbor/encode blocked-v1]]
            reason [:park :call-effect]]
      (testing (str n " " reason)
        (refused (encode (assoc-in (base) [:yin.k/frames 0 :yin.k/pending]
                                   {:yin.k/reason reason}))
                 :yin.k/undecodable
                 {:yin.k/reason reason}))))
  (testing "an install waiter without its entry, on either version"
    (doseq [[n encode base] [["version 0" cbor/encode install-v0]
                             ["version 1" jing.cbor/encode install-v1]]]
      (testing n
        (refused (encode (dissoc (base) :yin.k/installs))
                 :yin.k/undecodable
                 {:yin.k/name 'host.mod
                  :yin.k/kind :incomplete-install}))))
  (testing "a phase outside :running and :parked, on either version"
    (doseq [[n encode base] [["version 0" cbor/encode install-v0]
                             ["version 1" jing.cbor/encode install-v1]]]
      (testing n
        (refused (encode (assoc-in (base)
                                   [:yin.k/installs 'host.mod :yin.k/phase]
                                   :linked))
                 :yin.k/undecodable
                 {:yin.k/phase :linked
                  :yin.k/path [:yin.k/installs 'host.mod :yin.k/phase]}))))
  (testing "version 1 requires phase and parent outright"
    (refused (jing.cbor/encode
               (update-in (install-v1) [:yin.k/installs 'host.mod]
                          dissoc :yin.k/phase))
             :yin.k/undecodable
             {:yin.k/kind :install-header
              :yin.k/path [:yin.k/installs 'host.mod :yin.k/phase]})
    (refused (jing.cbor/encode
               (update-in (install-v1) [:yin.k/installs 'host.mod]
                          dissoc :yin.k/parent))
             :yin.k/undecodable
             {:yin.k/kind :install-header
              :yin.k/path [:yin.k/installs 'host.mod :yin.k/parent]}))
  (testing "a runnable child is grammar here: the reader refuses
            nothing the grammar does not (the lift side is D9's)"
    (let [[r] (read! (jing.cbor/encode
                       (assoc-in (install-v1)
                                 [:yin.k/installs 'host.mod :yin.k/phase]
                                 :running)) ::granted)]
      (is (= :ok (:status r)) (pr-str r)))))


(deftest v1-public-install-child-option-cannot-bypass-grant
  (let [bytes (jing.cbor/encode (blocked-v1))
        opts (assoc (lower-options bytes) :yin.vm.ucf.handoff/install-child true)
        opts (dissoc opts :grant)
        attached (atom 0)
        attach! (fn [_] (swap! attached inc))
        out (handoff/resume-task (new-machine) bytes attach! opts)]
    (is (= :yin.k/awaiting-grant (:yin.k/status out)) (pr-str out))
    (is (zero? @attached))))
