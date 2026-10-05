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
            [dao.stream.cbor :as cbor]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.ucf.handoff :as handoff]
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

(defn- read!
  "The version-aware reader over a counting attach!, on a receiving task
   that composes the attach seam an install child's spawn resolves its
   streams through.  Answers [outcome attaches]."
  ([bytes] (read! bytes nil))
  ([bytes opts]
   (let [attaches (atom 0)
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


;; =============================================================================
;; The two-codec split: which codec accepted decides the version
;; =============================================================================

(deftest the-accepting-codec-decides-the-body-version
  (testing "a version-1 body rides jing canonical bytes end to end"
    (let [[r n] (read! (jing.cbor/encode (blocked-v1)))]
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
             {:yin.k/version 0 :yin.k/supported #{1}}))
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
             {:yin.k/version 0 :yin.k/supported #{1}
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
               {:yin.k/version 2 :yin.k/supported #{1}}))
    (testing "an absent version"
      (refused (jing.cbor/encode (dissoc (blocked-v1) :yin.k/version))
               :yin.k/profile-mismatch
               {:yin.k/version nil :yin.k/supported #{1}}))
    (testing "an integral float is no version, on any host"
      (let [[r n] (read! (jing.cbor/encode
                           (assoc (blocked-v1)
                                  :yin.k/version (jing.cbor/float64 1))))]
        (is (= :yin.k/profile-mismatch (:yin.k/status r)) (pr-str r))
        (is (= #{1} (:yin.k/supported r)))
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
      (let [[r] (read! (jing.cbor/encode (at (halted-child nil))))]
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
                                 :running)))]
      (is (= :ok (:status r)) (pr-str r)))))
