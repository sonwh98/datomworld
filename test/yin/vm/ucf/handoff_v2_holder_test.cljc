(ns yin.vm.ucf.handoff-v2-holder-test
  "UCF version 2, section 11 rows 9, 10, 11 and 12 over the holder's
   exporting state: the D4-D9 holds in root and child refuse the lift and
   leave the machine as it was; canonical bytes do not depend on map
   insertion order; a version-2 recovery snapshot freezes, rehydrates in
   a fresh receiver with its gates exporting and its bytes reproduced,
   and refuses a restarted abort; and a receiver poisoned with guest
   state behaves exactly as a clean one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.v2-support :as s]))


(defn- served-table
  "A `serve!` answering one stable identity per handle."
  []
  (let [table (atom {})]
    {:table table
     :serve! (fn [h]
               (or (get @table h)
                   (let [served {:dao.stream/identity
                                 (str "s" (count @table))
                                 :dao.stream/channel
                                 {:dao.stream/type :test/channel}}]
                     (swap! table assoc h served)
                     served)))}))


(defn- hold-at
  "`machine` with `f` applied at the root, or at the install child
   `path` names."
  [machine path f]
  (if (empty? path)
    (f machine)
    (update-in machine [:installs (first path) :vm] hold-at (rest path) f)))


(def ^:private holds
  "Each hold: [kind f], `f` turning a machine into one that holds it."
  [[:held #(assoc-in % [:wait-set 0 :yin.k/held] {:state :observed})]
   [:observe #(assoc-in % [:wait-set 0 :reason] :observe)]
   [:unminted-cursor
    #(assoc-in % [:resources :yin/unminted]
               {:stream-id :yin/none :cursor nil :yin.k/unminted true})]
   [:pending-close #(assoc % :yin.k/closes [{:close :pending}])]
   ;; a :link-request wait whose response cursor was never installed
   [:link-cursor-not-installed
    #(update-in % [:wait-set 0]
                (fn [entry]
                  (dissoc (assoc entry :reason :link-request)
                          :cursor)))]])


(deftest every-hold-refuses-the-lift-in-root-and-child
  (doseq [engine s/engines
          [kind f] holds]
    (testing (str (name engine) " " kind)
      (let [reader (first (s/parked-reader engine))
            [installer _] (s/parked-installer engine)
            held-root (hold-at reader [] f)
            held-child (hold-at installer ['host.mod] f)]
        (doseq [[label machine path] [["root" held-root []]
                                      ["child" held-child ['host.mod]]]]
          (let [entered (export/enter machine {:version 2})
                lifted (handoff/export-task machine (:serve! (served-table))
                                            {:version 2})]
            (is (= :yin.k/non-portable (:yin.k/status entered))
                (str label ": " (pr-str (dissoc entered :machine))))
            (is (= kind (:yin.k/hold entered)))
            (is (= path (:yin.k/path entered)))
            (is (not (contains? entered :machine))
                "nothing was fenced: the machine stays as it was")
            (is (= :yin.k/non-portable (:yin.k/status lifted)))
            (is (= kind (:yin.k/hold lifted)))))))))


(deftest identical-data-lifts-to-identical-canonical-bytes
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[m _] (s/parked-reader engine)
            make (fn [store] (assoc m :store store))
            ;; zipmap keeps small-map insertion order on the hosts that
            ;; have it (so a and b iterate differently there) and
            ;; compiles on every host -- array-map does not exist on
            ;; ClojureDart
            a (make (zipmap '[a b c d] [1 2 3 4]))
            b (make (zipmap '[d c b a] [4 3 2 1]))
            lift #(handoff/export-task % (:serve! (served-table))
                                       {:version 2})
            ea (lift a)
            eb (lift b)]
        (is (= :ok (:status ea)) (pr-str ea))
        (is (= (vec (:bytes ea)) (vec (:bytes eb)))
            "map insertion order never reaches the bytes")
        (is (= (:address ea) (:address eb)))
        (is (= (set (keys (:yin.k/code (:body ea))))
               (set (keys (:yin.k/code (:body eb))))))
        (is (= (:yin.k/cells (:body ea)) (:yin.k/cells (:body eb))))))))


(defn- observed
  "Everything a program could see of stream `h`: the outcome and value
   at its first positions.  Equal before and after means no program IO
   appended to it."
  [h]
  (mapv #(select-keys (stream/next h %) [:dao.stream/outcome
                                         :dao.stream/value])
        (range 3)))


(defn- cells-of
  "Each wait of `waits`, resolved through `machine`'s own resources: its
   reason, the stream handle it polls and the cell's position, plus the
   entry's keys but for the recovery's issue mark."
  [machine waits]
  (mapv (fn [e]
          [(:reason e)
           (get-in machine [:resources (:stream-id e)])
           (get-in machine [:resources (get-in e [:cursor-ref :id]) :cursor])
           (disj (set (keys e)) :yin.k/issue)])
        waits))


(defn- resolved
  "`x` with every stream and cursor reference replaced by what it names
   in `machine`'s own resources -- the stream handle, the handle and the
   cell's position -- so records held under receiver-owned ids and seals
   compare by content."
  [machine x]
  (walk/postwalk
    (fn [n]
      (if (and (map? n) (contains? #{:stream-ref :cursor-ref} (:type n)))
        (let [r (get-in machine [:resources (:id n)])]
          (if (= :stream-ref (:type n))
            [:stream r]
            [:cursor (get-in machine [:resources (:stream-id r)]) (:cursor r)]))
        n))
    x))


(defn- sources
  "The machines the recovery row freezes, by label: a reader parked on a
   wait (cells), and an explicit park whose record holds a cursor (a
   parked record).  Each answers [machine stream]."
  [engine]
  [["reader" (s/parked-reader engine)]
   ["explicit park" (let [m (s/parked-explicit engine)]
                      [m (get-in m [:resources (some (fn [[_ r]]
                                                       (:stream-id r))
                                                     (:resources m))])])]])


;; A genuinely fresh process is not portable across the three test
;; hosts, so this row stands in for one in process: the frozen object
;; crosses only as text (lowercase hex of its bytes and the address's
;; string), the receiver is a new machine of the composition that shares
;; nothing with the source, and the one capability it gets -- `attach`,
;; answering a descriptor by its identity -- is what a fresh process's
;; transport would offer.  `rehydrate-fenced` reads nothing else, so
;; what it can reach is exactly what a fresh process could.
(deftest a-version-2-recovery-snapshot-freezes-and-rehydrates-fenced
  (doseq [engine s/engines
          [what [source src]] (sources engine)
          [label header] [["fork" nil]
                          ["exclusive" (s/header 0 nil #{})]]]
    (testing (str (name engine) " " what " " label)
      (let [before (observed src)
            {machine :machine record :record} (export/enter source
                                                            {:version 2})
            {:keys [serve! table]} (served-table)
            serves (atom 0)
            prepared (export/prepare machine record
                                     (fn [h] (swap! serves inc) (serve! h))
                                     header)
            served @serves
            frozen (export/freeze machine (:record prepared))
            wire {:hex (fx/bytes->hex (:bytes frozen))
                  :address (str (:address frozen))}
            by-identity (into {} (map (fn [[handle descriptor]]
                                        [(:dao.stream/identity descriptor)
                                         handle]))
                              @table)
            attached (atom 0)
            restored (export/rehydrate-fenced
                       (s/new-machine engine)
                       (fx/hex->bytes (:hex wire))
                       {:address (keyword (subs (:address wire) 1))
                        :attach (fn [descriptor]
                                  (swap! attached inc)
                                  {:dao.stream/outcome :dao.stream/ok
                                   :dao.stream/handle
                                   (by-identity
                                     (:dao.stream/identity descriptor))})})
            encoded (when (= :ok (:status restored))
                      (export/encode (:machine restored)
                                     (:record restored)))
            body (cbor/decode (:body-bytes frozen))]
        (is (= :ok (:status prepared)) (pr-str prepared))
        (is (= :ok (:status frozen)) (pr-str frozen))
        (is (= 2 (:yin.k/version body))
            "the embedded body is version 2; the recovery's own stays 1")
        (is (= 1 (:yin.k/version (cbor/decode (:bytes frozen)))))
        (is (= :ok (:status restored)) (pr-str restored))
        (is (= :exporting (vm/gate-mode (:machine restored))))
        (is (empty? (:wait-set (:machine restored))))
        (testing "zero program IO during freeze and rehydrate"
          (is (= served @serves) "no stream served after prepare")
          (is (= before (observed src))
              "the task's stream reads exactly as it did before the lift"))
        (testing "the cells and parked records come back whole"
          (is (= (count (:wait-set record))
                 (count (get-in restored [:record :wait-set]))))
          (is (= (cells-of source (:wait-set record))
                 (cells-of (:machine restored)
                           (get-in restored [:record :wait-set])))
              "each wait polls the same stream from the same position")
          (is (= (resolved source (:parked record))
                 (resolved (:machine restored)
                           (get-in restored [:record :parked])))
              "every parked record whole: registers, code and the streams
               and positions its references name")
          (is (= (keys (:parked record))
                 (keys (:parked (:record prepared))))
              "the parked records are the ones the source entered with")
          (is (every? #(and (some? (nth % 1)) (some? (nth % 2)))
                      (cells-of (:machine restored)
                                (get-in restored [:record :wait-set])))
              "each restored wait names a live stream and a position")
          (when (= "explicit park" what)
            (is (seq (:parked record)) "a parked record crosses")
            (is (some #(and (vector? %) (= :cursor (first %))
                            (some? (second %)) (some? (nth % 2)))
                      (tree-seq coll? seq
                                (resolved (:machine restored)
                                          (get-in restored
                                                  [:record :parked]))))
                "the record's cursor resolves to a live stream and position")))
        (is (pos? @attached))
        (is (= (vec (:body-bytes frozen)) (vec (:bytes encoded)))
            "the published bytes are reproduced exactly")
        (is (= :yin.k/refused
               (:yin.k/status (export/abort (:machine restored)
                                            (:record restored) [] nil)))
            "a restarted abort is refused")
        (testing "recovery bytes are no handoff body"
          (let [[r n] (s/read! engine (s/toy) (:bytes frozen) nil)]
            (is (not= :ok (:status r)))
            (is (zero? n))))))))


(def ^:private code-keys
  "Where each profile holds its code: the semantic segment table, the
   walker's rows, the stack and register segment, layout and hash."
  [:code :code-aliases :rows :row-index :row-nodes :segment :images :hash])


(deftest a-poisoned-receiver-behaves-as-a-clean-one
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            [parked src] (s/parked-reader engine)
            export (s/lift parked t peer)
            reference (do (stream/append! src "B")
                          (vm/value (s/drive-local parked)))
            own-code (s/load-ast engine (s/new-machine engine) (s/lit 99))
            ;; the receiver's own code: its loaded program, and under
            ;; stack and register a second image grown onto its layout
            own-code (case engine
                       :stack (stack/attach-image
                                own-code (s/module-image :stack (s/lit 77))
                                vm/stack-contract)
                       :register (register/attach-image
                                   own-code
                                   (s/module-image :register (s/lit 77))
                                   vm/register-contract)
                       own-code)
            poisoned (assoc own-code
                            :store {'poison :wrong}
                            :module-stores {:segment/poison {'x 1}}
                            :parked {:poison {:type :parked-continuation}}
                            :installs {'poison {:phase :running}}
                            :wait-set [{:reason :next :poison true}]
                            :ready-queue []
                            :yin.k/closes [{:close :pending}]
                            :yin.k/issued 99)
            attach (s/attacher t)
            r (handoff/resume-task poisoned (:bytes export) attach
                                   {:address (:address export)})
            recv (:vm r)
            [clean _] (s/read! engine t (:bytes export)
                               {:address (:address export)})
            code-of #(select-keys % code-keys)
            done (s/drive recv peer)]
        (is (= :ok (:status r)) (pr-str r))
        (is (seq (code-of poisoned)) "the receiver held code of its own")
        (is (not= (code-of poisoned) (code-of recv)))
        (is (= (code-of (:vm clean)) (code-of recv))
            "the code the task runs is a clean receiver's: nothing of the
             receiver's own program, layout or segment remains")
        (when (contains? #{:stack :register} engine)
          (is (= 2 (count (:images poisoned))) "a grown stale layout")
          (is (= (:images parked) (:images recv)))
          (is (= (:hash parked) (:hash recv))))
        (is (not (contains? (:store recv) 'poison)))
        (is (empty? (:module-stores recv)))
        (is (not (contains? (:parked recv) :poison)))
        (is (not (contains? (:installs recv) 'poison)))
        (is (empty? (:yin.k/closes recv)))
        (is (= reference (vm/value done)))))))


(deftest an-unknown-required-primitive-is-unsatisfied-before-attachment
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            parked (s/run-ast engine
                              (s/let1 'p (s/v '+)
                                      (s/let1 's {:type :stream/make
                                                  :buffer 4}
                                              (s/next-of
                                                (s/cursor-of (s/v 's))))))
            export (s/lift parked t peer)
            body (cbor/decode (:bytes export))
            markers (filter #(and (map? %)
                                  (= :yin.k/primitive (:yin.k/tag %)))
                            (tree-seq coll?
                                      (fn [n]
                                        (if (map? n)
                                          (concat (keys n) (vals n))
                                          (seq n)))
                                      body))
            unknown (walk/postwalk
                      (fn [x]
                        (if (and (map? x) (= :yin.k/primitive (:yin.k/tag x)))
                          (assoc x :yin.k/name 'no.such/primitive)
                          x))
                      body)
            [r n] (s/read! engine t (cbor/encode unknown) nil)]
        (is (= :ok (:status export)) (pr-str export))
        (is (seq markers) "the primitive travels as a marker")
        (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
        (is (= 'no.such/primitive (:yin.k/name r)))
        (is (zero? n) "refused before one attachment")
        (is (not (contains? r :vm)))))))


(deftest a-name-nothing-answers-refuses-the-lift-in-every-profile
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            parked (s/run-ast engine
                              (s/let1 's {:type :stream/make, :buffer 4}
                                      (s/then (s/next-of (s/cursor-of (s/v 's)))
                                              (s/v 'zz))))
            r (s/lift parked t peer)]
        (is (= :yin.k/unsatisfied (:yin.k/status r)) (pr-str r))
        (is (contains? (get-in r [:yin.k/missing :obligations]) 'zz))))))
