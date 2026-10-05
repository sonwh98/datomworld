(ns yin.vm.ucf.lift-v1-test
  "M-next D9: the version-1 lift as a pure encode (r3 1.8; the header
   ruling).  Every machine is parked by the real engine and lifted
   through enter / prepare / encode (`yin.vm.ucf.lift-support`); the
   assertions are on the bytes, on the two grammars that must accept
   them, and on the refusals the lift answers as data."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [dao.stream.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.lift-support :as s]
            [yin.vm.values :as values]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- same-bytes?
  [a b]
  (= (vec a) (vec b)))


(defn- ok!
  [r]
  (is (= :ok (:status r)) (pr-str (dissoc r :record)))
  r)


(defn- accepted-by-both!
  "The lift's own bytes pass the inspector and `validate-body` again,
   run here from outside the lift."
  [r]
  (let [i (checkpoint/inspect (:address r) (:bytes r))]
    (is (not (contains? i :yin.k/status)) (pr-str i))
    (is (jing.cbor/equiv
          (:body r)
          (handoff/validate-body (jing.cbor/decode (:bytes r)))))))


;; =============================================================================
;; The version switch: no header is the fork lift, a header is version 1
;; =============================================================================

(deftest a-nil-header-is-the-version-0-lift-unchanged-test
  (let [m (s/parked-reader)
        direct (handoff/export-task m (s/serve-table))
        lifted (s/lift m nil)]
    (ok! lifted)
    (is (= 0 (:yin.k/version (:body lifted))))
    (is (same-bytes? (:bytes direct) (:bytes lifted)))
    (is (= (:address direct) (:address lifted)))
    (is (= (:body lifted) (cbor/decode (:bytes lifted)))
        "the stream codec's bytes")
    (is (not-any? #(contains? (:body lifted) %)
                  [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                   :yin.k/next-op-seq :yin.k/origin]))))


(deftest a-header-is-a-version-1-body-both-grammars-accept-test
  (testing "a first export"
    (let [r (ok! (s/lift (s/parked-reader) (s/header 0 nil #{})))
          b (:body r)]
      (is (= 1 (:yin.k/version b)))
      (is (= :yin.k/exclusive (:yin.k/policy b)))
      (is (= s/occurrence (:yin.k/occurrence b)))
      (is (= s/arbitration (:yin.k/arbitration b)))
      (is (= 0 (:yin.k/next-op-seq b)))
      (is (not (contains? b :yin.k/origin)))
      (is (not (contains? b :yin.k/enrolled)) "the enrolled set never travels")
      (accepted-by-both! r)))
  (testing "a successor carrying an id on an enrolled target"
    (let [m (s/with-op-id (s/parked-writer) :put (s/op-id 0))
          r (ok! (s/lift m (s/header 3 s/origin #{"s0"})))
          pending (get-in r [:body :yin.k/frames 0 :yin.k/pending])]
      (is (= s/origin (get-in r [:body :yin.k/origin])))
      (is (= (s/op-id 0) (:yin.k/op-id pending)))
      (accepted-by-both! r)))
  (testing "a parked root"
    (let [r (ok! (s/lift (s/parked-explicit) (s/header 1)))]
      (is (= :parked (:kind r)))
      (accepted-by-both! r)))
  (testing "install children carry phase, parent and no header"
    (let [r (ok! (s/lift (s/parked-installer) (s/header 6)))
          inst (get-in r [:body :yin.k/installs 'host.mod])
          child (:yin.k/child inst)]
      (is (contains? #{:running :parked} (:yin.k/phase inst)))
      (is (contains? inst :yin.k/parent))
      (is (= 1 (:yin.k/version child)))
      (is (not-any? #(contains? child %)
                    [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                     :yin.k/next-op-seq :yin.k/origin]))
      (accepted-by-both! r))))


(deftest a-halted-root-takes-its-origin-and-nothing-else-test
  (testing "with an origin"
    (let [r (ok! (s/lift (s/halted-machine) (s/header 0 s/origin #{})))
          b (:body r)]
      (is (= s/origin (:yin.k/origin b)))
      (is (not-any? #(contains? b %)
                    [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                     :yin.k/next-op-seq]))
      (accepted-by-both! r)))
  (testing "without one the self-check refuses, and nothing is answered"
    (let [r (s/lift (s/halted-machine) (s/header 0 nil #{}))]
      (is (= :yin.k/undecodable (:yin.k/status r)) (pr-str r))
      (is (= [:yin.k/origin] (:yin.k/path r)))
      (is (not (contains? r :bytes)))))
  (testing "a first-export halt is a version-0 result, lifted with no header"
    (let [r (ok! (s/lift (s/halted-machine) nil))]
      (is (= 0 (:yin.k/version (:body r)))))))


;; =============================================================================
;; The three refusals of the lift
;; =============================================================================

(deftest an-exhausted-counter-refuses-test
  (let [h (s/header checkpoint/max-exact s/origin #{})
        r (s/lift (s/parked-reader) h)]
    (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
    (is (= :op-seq-exhausted (:yin.k/kind r)))
    (testing "one below it still lifts, though the next id would be the last"
      (ok! (s/lift (s/parked-reader)
                   (s/header (dec checkpoint/max-exact) s/origin #{}))))))


(deftest an-unprotected-pending-refuses-in-the-root-and-in-a-child-test
  (testing "the root"
    (let [r (s/lift (s/parked-writer) (s/header 0 nil #{"s0"}))]
      (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
      (is (= :unprotected-pending (:yin.k/kind r)))
      (is (= "s0" (:dao.stream/identity r)))))
  (testing "on every export, not only the first"
    (let [r (s/lift (s/parked-writer) (s/header 5 s/origin #{"s0"}))]
      (is (= :unprotected-pending (:yin.k/kind r)))))
  (testing "an install child's retained write"
    (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :vm]
                      (s/parked-writer))
          r (s/lift m (s/header 0 nil #{"s0"}))]
      (is (= :unprotected-pending (:yin.k/kind r)) (pr-str r)))))


(deftest an-id-on-an-unenrolled-target-is-unsatisfied-test
  (let [m (s/with-op-id (s/parked-writer) :put (s/op-id 0))
        r (s/lift m (s/header 3 s/origin #{}))]
    (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
    (is (= "s0" (:dao.stream/identity r))))
  (testing "an install child's id on an unenrolled target"
    (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :vm]
                      (s/with-op-id (s/parked-writer) :put (s/op-id 0)))
          r (s/lift m (s/header 3 s/origin #{}))]
      (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
      (is (= "s0" (:dao.stream/identity r))))))


(deftest a-transient-install-phase-refuses-test
  (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :phase]
                    :starting)
        r (s/lift m (s/header 6))]
    (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
    (is (= :incomplete-install (:yin.k/kind r)))
    (is (= 'host.mod (:yin.k/name r)))))


(deftest a-held-observation-refuses-in-the-root-and-in-a-child-test
  (let [held (fn [m path]
               (assoc-in m (into (vec (mapcat (fn [n] [:installs n :vm]) path))
                                 [:wait-set 0 :yin.k/held])
                         {:state :observed}))]
    (testing "the root"
      (let [r (s/lift (held (s/parked-reader) []) (s/header 0 nil #{}))]
        (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
        (is (= :held (:yin.k/hold r)))))
    (testing "a child"
      (let [r (s/lift (held (s/parked-installer) ['host.mod]) (s/header 6))]
        (is (= :held (:yin.k/hold r)) (pr-str r))
        (is (= ['host.mod] (:yin.k/path r)))))
    (testing "the lift itself refuses them too, before lift-pending, in
              either version, even when entering exporting was bypassed"
      (doseq [opts [nil {:header (s/header 0 nil #{})}]]
        (let [r (handoff/export-task (held (s/parked-reader) [])
                                     (s/serve-table) opts)]
          (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str opts))
          (is (= :reason-mismatch (:yin.k/kind r)))
          (is (= :held (:yin.k/hold r)))))
      (let [r (handoff/export-task (held (s/parked-installer) ['host.mod])
                                   (s/serve-table))]
        (is (= :held (:yin.k/hold r)) (pr-str r))))
    (testing "an :observe entry, the machine-only reason, refuses the same"
      (let [r (handoff/export-task
                (assoc-in (s/parked-reader) [:wait-set 0 :reason] :observe)
                (s/serve-table))]
        (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
        (is (= :observe (:yin.k/hold r)))))))


(deftest a-cursorless-link-entry-refuses-the-lift-test
  (testing "the lift itself, entering exporting bypassed"
    (let [r (handoff/export-task (s/parked-linker) (s/serve-table))]
      (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
      (is (= :reason-mismatch (:yin.k/kind r)))
      (is (= :link-cursor-not-installed (:yin.k/hold r)))))
  (testing "an install child's cursorless entry refuses the root's lift"
    (let [m (assoc-in (s/parked-installer) [:installs 'host.mod :vm]
                      (s/parked-linker))
          r (handoff/export-task m (s/serve-table))]
      (is (= :link-cursor-not-installed (:yin.k/hold r)) (pr-str r)))))


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(deftest a-malformed-header-is-a-caller-defect-test
  (let [{m :machine record :record} (export/enter (s/parked-reader))]
    (doseq [h [:x {} (dissoc (s/header 0) :yin.k/arbitration)
               (dissoc (s/header 0) :yin.k/next-op-seq)
               (dissoc (s/header 0) :yin.k/enrolled)]]
      (is (throws? #(export/prepare m record (s/serve-table) h))
          (pr-str h)))))


(deftest two-encodes-of-one-prepared-record-give-equal-bytes-test
  (doseq [[n machine h] [["reader" (s/parked-reader) (s/header 0 nil #{})]
                         ["installer" (s/parked-installer) (s/header 6)]
                         ["explicit" (s/parked-explicit) (s/header 1)]
                         ["version 0" (s/parked-reader) nil]]]
    (let [{m :machine p :prepared} (s/prepared machine h)
          record (:record p)
          a (export/encode m record)
          b (export/encode m record)]
      (ok! p)
      (is (same-bytes? (:bytes a) (:bytes b)) n)
      (is (= (:address a) (:address b)) n))))


(deftest the-served-table-is-plain-data-keyed-by-path-and-resource-test
  (let [{p :prepared} (s/prepared (s/parked-installer) (s/header 6))
        served (:served (:record p))]
    (is (seq served))
    (is (every? (fn [[[path id] descriptor]]
                  (and (vector? path) (some? id)
                       (string? (:dao.stream/identity descriptor))))
                served))
    (is (some (fn [[[path _] _]] (= ['host.mod] path)) served)
        "the child's streams are keyed by its path"))
  (testing "one handle under two resource ids: served once, keyed twice"
    (let [calls (atom 0)
          serve! (fn [_]
                   (swap! calls inc) {:dao.stream/identity "s0"
                                      :dao.stream/channel
                                      {:dao.stream/type
                                       :dao.stream.test/channel}})
          {m :machine record :record} (export/enter (s/parked-writer-shared))
          p (export/prepare m record serve! (s/header 3 s/origin #{}))
          e (export/encode m (:record p))]
      (is (= :ok (:status p)) (pr-str (dissoc p :record)))
      (is (= 1 @calls) "the one handle asked once")
      (is (= 2 (count (:served (:record p)))) "both resource keys retained")
      (is (= :ok (:status e)) (pr-str e))
      (is (throws? #(export/encode
                      m (update (:record p) :served
                                dissoc (ffirst (:served (:record p))))))
          "a key the record lacks is still a defect"))))


(deftest a-retry-serves-no-already-served-handle-test
  (let [served {:dao.stream/identity "s0"
                :dao.stream/channel {:dao.stream/type
                                     :dao.stream.test/channel}}
        seen (atom #{})
        ;; answers the first distinct handle it is asked about, and that
        ;; one whenever it is asked again; every other handle gets no
        ;; answer, so the prepare refuses once the walk reaches the
        ;; independent third stream, after the shared handle's aliases
        first-only (fn [h]
                     (when (or (contains? @seen h) (empty? @seen))
                       (swap! seen conj h)
                       served))
        {m :machine record :record} (export/enter
                                      (s/parked-writer-shared-late))
        refused (export/prepare m record first-only (s/header 3 s/origin #{}))]
    (is (= :yin.k/unsatisfied (:yin.k/status refused)) (pr-str refused))
    (is (= 1 (count @seen)) "exactly one handle ever answered")
    (is (every? #(= served %) (vals (:served (:record refused))))
        "what was served is retained")
    (testing "the retry resolves the shared handle's every alias from the
              retained table and the machine's bindings: the handle is
              not served again"
      (let [calls (atom 0)
            table (atom {})
            counting (fn [h]
                       (swap! calls inc)
                       (if-some [d (get @table h)]
                         d
                         (let [d {:dao.stream/identity
                                  (str "s" (count @table))}]
                           (swap! table assoc h d)
                           d)))
            p (export/prepare m (:record refused) counting
                              (s/header 3 s/origin #{}))
            e (export/encode m (:record p))]
        (is (= :ok (:status p)) (pr-str (dissoc p :record)))
        (is (= 1 @calls) "only the never-served third stream is asked")
        (is (= 3 (count (:served (:record p)))) "every resource key")
        (is (= 2 (count (distinct (vals (:served (:record p))))))
            "one identity for the shared handle's two keys")
        (is (= :ok (:status e)) (pr-str e))
        (is (= 1 (count @seen)) "the first attempt served once")))
    (testing "a retry over a machine whose bindings moved -- a fresh
              resource alias of the served handle reached first"
      (let [[path id :as retained] (first (keys (:served (:record refused))))
            h (get-in m (into path [:resources id]))
            _ (is (some? retained) "one key is retained")
            _ (is (some? h) "the retained key resolves to its handle")
            [alias m2] (engine/attach-resource m h)
            ;; prepare reads the record's waits, so the fresh alias rides
            ;; the record's first wait entry's environment
            record' (assoc-in (:record refused) [:wait-set 0 :env 'aa]
                              alias)
            calls (atom 0)
            counting (fn [_] (swap! calls inc) served)
            p (export/prepare m2 record' counting (s/header 3 s/origin #{}))]
        (is (= :ok (:status p)) (pr-str (dissoc p :record)))
        (is (= 1 @calls) "only the never-served third stream is asked")
        (is (= 4 (count (:served (:record p))))
            "the fresh alias, the retained alias, the pending's and the
             third stream's")
        (is (= :ok (:status (export/encode m2 (:record p))))
            "the record encodes with no serving")))))


(deftest the-record-round-trips-the-canonical-codec-test
  (let [{m :machine p :prepared} (s/prepared (s/parked-explicit) (s/header 1))
        record (:record p)
        back (jing.cbor/decode (jing.cbor/encode record))]
    (ok! p)
    (is (same-bytes? (jing.cbor/encode record) (jing.cbor/encode back)))
    (is (same-bytes? (:bytes (export/encode m record))
                     (:bytes (export/encode m back)))
        "the stored record encodes to the same bytes")))


;; =============================================================================
;; Determinism and what the program holds
;; =============================================================================

(def ^:private odd-values
  {:f (jing.cbor/float64 1.5)
   :zero (jing.cbor/float64 0.0)
   :neg-zero (jing.cbor/float64 (* -1.0 0.0))
   :nan (jing.cbor/float64 ##NaN)
   :nested {:b {:d [1 2 {:e 3}] :a #{3 1 2}} :a 1 :c {:z 1 :y 2}}})


(defn- literal-entries
  "The `[k v]` pairs of a wrapped literal map."
  [lit]
  (is (= :yin.k/literal (:yin.k/tag lit)) "wrapped, never a bare map")
  (into {} (map vec) (partition 2 (:yin.k/entries lit))))


(deftest floats-signed-zero-nan-and-nested-maps-lift-canonically-test
  (let [h (s/header 0 s/origin #{})
        r (ok! (s/lift (s/halted-with odd-values) h))
        again (s/lift (s/halted-with odd-values) h)
        result (get-in r [:body :yin.k/result])
        entries (literal-entries result)]
    (accepted-by-both! r)
    (is (same-bytes? (:bytes r) (:bytes again)) "a fresh machine, same bytes")
    (is (= 5 (count entries)))
    (is (jing.cbor/float64? (get entries :neg-zero)))
    (is (jing.cbor/float64? (get entries :nan)))
    (testing "signed zero is kept: the two zeros are different bytes"
      (is (not (same-bytes? (jing.cbor/encode (get entries :zero))
                            (jing.cbor/encode (get entries :neg-zero))))))
    (testing "literal entries are ordered by their canonical key bytes"
      (let [ks (take-nth 2 (:yin.k/entries result))]
        (is (= ks (sort jing.cbor/encoded-compare ks)))))))


(deftest an-envelope-shaped-program-value-survives-as-payload-test
  (let [envelope {:dao.stream.apply/id :call-1
                  :dao.stream.apply/op :op/add
                  :dao.stream.apply/args [1 2]}
        r (ok! (s/lift (s/halted-with envelope) (s/header 0 s/origin #{})))
        entries (literal-entries (get-in r [:body :yin.k/result]))]
    (accepted-by-both! r)
    (is (= envelope entries) "the value is in the body, as data")))


(deftest cells-are-shared-and-numbered-in-walk-order-test
  (let [r (ok! (s/lift (s/parked-explicit) (s/header 1)))
        cells (get-in r [:body :yin.k/cells])]
    (is (= #{:yin.k/c-0} (set (keys cells))))
    (is (= 1 (count (vals cells))))))


(deftest the-version-0-wire-is-still-emitted-for-a-halted-first-export-test
  (let [r (ok! (s/lift (s/halted-machine) nil))]
    (is (= 0 (:yin.k/version (:body r))))
    (is (vm/halted? (s/halted-machine)))))


(deftest cell-numbering-never-depends-on-map-iteration-test
  (let [m (s/parked-two-cursors)
        ;; a small map rebuilt from its reversed entries keeps that order
        ;; on the JVM and JavaScript; ClojureDart's maps iterate
        ;; canonically, so no store order can be flipped there and the
        ;; row degrades to the same-bytes proof over one order
        reversed #?(:cljd m :default (update m :store #(into {} (reverse %))))
        h (s/header 0 nil #{})
        a (ok! (s/lift m h))
        b (ok! (s/lift reversed h))]
    (is (= 2 (count (get-in a [:body :yin.k/cells]))) "one cell shared, one not")
    #?(:cljd nil
       :default (is (not= (seq (:store m)) (seq (:store reversed)))
                    "the order really differs"))
    (is (same-bytes? (:bytes a) (:bytes b)))
    (is (= (:address a) (:address b)))
    (accepted-by-both! a)))


(deftest cursor-referenced-store-keys-order-nothing-test
  (let [m (s/parked-cursor-keyed)
        reversed #?(:cljd m
                    :default (update-in m [:store 'mapped]
                                        #(into {} (reverse %))))
        h (s/header 0 nil #{})
        a (ok! (s/lift m h))
        b (ok! (s/lift reversed h))
        cells-a (get-in a [:body :yin.k/cells])
        cells-b (get-in b [:body :yin.k/cells])]
    (is (= 2 (count cells-a)) "two distinct cursors, two cells")
    (is (= 2 (count cells-b)))
    (is (= (set (vals cells-a)) (set (vals cells-b)))
        "distinct cell identity kept: the same two streams at the same
         positions under either numbering")
    (is (same-bytes? (:bytes a) (:bytes b))
        "the numbering and the entry order follow canonical key bytes,
         never the comparison's own traversal")
    (is (= (:address a) (:address b)))
    (accepted-by-both! a)))


(deftest module-store-cursor-values-order-nothing-test
  (let [m (s/parked-module-store-cursors)
        store-at [:module-stores 'host.mod]]
    (is (= 2 (count (get-in m store-at)))
        "the module store holds two cursor references")
    (let [reversed #?(:cljd m
                      :default (update-in m store-at #(into {} (reverse %))))
          h (s/header 0 nil #{})
          a (ok! (s/lift m h))
          b (ok! (s/lift reversed h))
          counted (fn [r]
                    (into {} (map (fn [[m' s']] [m' (count s')]))
                          (get-in r [:body :yin.k/module-stores])))]
      (is (= {'host.mod 2} (counted a) (counted b))
          "both snapshots carry the module store's two entries")
      (is (same-bytes? (:bytes a) (:bytes b))
          "the module-store walk is canonically ordered, like the task
           store's")
      (is (= (:address a) (:address b)))
      (accepted-by-both! a))))


;; =============================================================================
;; A halted result is encoded before the dependencies are finalized
;; =============================================================================

(defn- restored!
  "The emitted bytes lowered through `resume-task` into a fresh receiver
   whose attach seam answers one stream per distinct descriptor,
   supplying the version-1 address.  Answers the resumed machine."
  [v r]
  (let [table (atom {})
        attach! (fn [descriptor]
                  (let [h (or (get @table descriptor)
                              (let [h (s/one-slot-stream
                                        (str "r" (count @table)))]
                                (swap! table assoc descriptor h)
                                h))]
                    {:dao.stream/outcome :dao.stream/ok
                     :dao.stream/handle h}))
        out (handoff/resume-task
              (assoc (s/new-machine) :attach-stream attach!)
              (:bytes r) attach!
              (when (= :v1 v) {:address (:address r)}))]
    (is (= :ok (:status out)) (pr-str out))
    (:vm out)))


(defn- accepted-for!
  "Version 1: both grammars, from outside the lift.  Version 0: the
   body validates from its own stream-codec bytes.  Both: the bytes
   restore into a fresh receiver."
  [v r]
  (if (= :v1 v)
    (accepted-by-both! r)
    (is (= (:body r) (handoff/validate-body (cbor/decode (:bytes r))))))
  (restored! v r))


(defn- complete-declarations!
  "Every cursor reference the result holds names a declared cell, and
   the profiles cover them."
  [r]
  (let [body (:body r)
        cells (:yin.k/cells body)
        named (into #{}
                    (comp (filter #(and (map? %)
                                        (= :yin.k/cursor-ref
                                           (:yin.k/tag %))))
                          (map :yin.k/cell))
                    (tree-seq coll? seq (:yin.k/result body)))]
    (is (seq named))
    (is (every? #(map? (get cells %)) named) "every named cell declared")
    (is (contains? (get-in body [:yin.k/requires :yin.k/cursor-profiles])
                   :dao.stream.remote/v1))))


(defn- halted-versions
  "Both lifts of one halted machine: version 0 (no header) and 1."
  [machine-fn]
  [[:v0 (s/lift (machine-fn) nil)]
   [:v1 (s/lift (machine-fn) (s/header 0 s/origin #{}))]])


(deftest a-result-only-cursor-is-declared-in-both-versions-test
  (doseq [[v r] (halted-versions
                  #(s/halted-with-cursors (fn [c1 _] [c1])))]
    (testing v
      (ok! r)
      (is (= 1 (count (get-in r [:body :yin.k/cells]))))
      (complete-declarations! r)
      (let [out (accepted-for! v r)]
        (is (= 1 (count (:value out))))
        (is (every? #(engine/authentic-ref? out :cursor-ref %)
                    (:value out)))))))


(deftest a-result-with-repeated-aliases-is-one-cell-test
  (doseq [[v r] (halted-versions
                  #(s/halted-with-cursors (fn [c1 _] [c1 c1])))]
    (testing v
      (ok! r)
      (is (= 1 (count (get-in r [:body :yin.k/cells]))))
      (complete-declarations! r)
      (let [out (accepted-for! v r)]
        (is (= 2 (count (:value out))))
        (is (= 1 (count (distinct (map :id (:value out)))))
            "aliases stay one cursor")
        (is (every? #(engine/authentic-ref? out :cursor-ref %)
                    (:value out)))))))


(deftest a-result-with-distinct-cursors-is-two-cells-test
  (doseq [[v r] (halted-versions
                  #(s/halted-with-cursors (fn [c1 c2] [c1 c2])))]
    (testing v
      (ok! r)
      (is (= 2 (count (get-in r [:body :yin.k/cells]))))
      (complete-declarations! r)
      (let [out (accepted-for! v r)]
        (is (= 2 (count (distinct (map :id (:value out)))))
            "distinct cursors stay distinct")
        (is (every? #(engine/authentic-ref? out :cursor-ref %)
                    (:value out)))))))


(deftest a-result-only-module-closure-completes-its-dependencies-test
  (doseq [[v r] (halted-versions s/halted-result-closure)]
    (testing v
      (ok! r)
      (is (= #{'host.mod}
             (set (keys (get-in r [:body :yin.k/module-stores]))))
          "the module store the result names travels")
      (is (= 2 (count (get-in r [:body :yin.k/module-stores 'host.mod]))))
      (is (= 2 (count (get-in r [:body :yin.k/cells]))))
      (is (seq (get-in r [:body :yin.k/requires :yin.k/segments])))
      (is (= (set (get-in r [:body :yin.k/requires :yin.k/segments]))
             (set (keys (get-in r [:body :yin.k/code])))))
      (let [out (accepted-for! v r)
            store (get (:module-stores out) 'host.mod)
            refs (vals store)]
        (is (= 2 (count store)) "the module store is restored")
        (is (every? #(engine/authentic-ref? out :cursor-ref %) refs))
        (is (= 2 (count (distinct (map :id refs)))))
        (is (values/closure? (:value out)))))))


(deftest a-halted-result-lift-is-repeatable-test
  (doseq [f [#(s/halted-with-cursors (fn [c1 c2] [c1 c2 c1]))
             s/halted-result-closure]]
    (let [[[_ a0] [_ a1]] (halted-versions f)
          [[_ b0] [_ b1]] (halted-versions f)]
      (is (same-bytes? (:bytes a0) (:bytes b0)))
      (is (same-bytes? (:bytes a1) (:bytes b1))))))
