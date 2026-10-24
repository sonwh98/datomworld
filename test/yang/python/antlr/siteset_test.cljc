(ns yang.python.antlr.siteset-test
  "C4 slice I7 (docs/design/yang.antlr.md 8.5.6, ruling 14): published site
   sets for imported modules.

   The fast tests pin the set's content addressing and the consumer's
   decisions over a stand-in manifest (the record pins a manifest's
   `:yin.module/tree`, so no publication is forced): a set is the same
   address for the same marks (content-addressed over the marks, so two
   modules that mark alike share one), a record verifies by its own
   address, every pin that moves, an empty set and a malformed entry are a
   mismatch, a record is discovered only from a declared principal's signed
   index entry, a missing set is refused naming the profile, a
   noninterruptible profile imports without one, and an SCC member alias,
   which carries no marks, is refused under a hook profile while its
   bundle verifies.

   The slow tests run the acceptance on the three vector VMs through the
   linker: a published `while True` module imported under a hook profile
   is delivered by the linker composition's `:stage` (`siteset/stage`) as
   the instrumented module derived from its verified set and the published
   tree alone, the name environment holding canonical addresses only, so a
   pending signal interrupts it across the import boundary; the same import
   without its set is refused at link time, naming the profile and the set,
   before anything is derived or run; a noninterruptible profile imports the
   module as published; and an import chain derives bottom-up, the outer
   instrumented module pinning the inner one it links."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.test-slow :as slow]
            [yang.python.antlr.import-programs :as programs]
            [yang.python.antlr.linked-harness :as h]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.publication :as publication]
            [yang.python.antlr.render :as render]
            [yang.python.antlr.safepoint :as hooks]
            [yang.python.antlr.safepoint-programs :as loops]
            [yang.python.antlr.siteset :as siteset]
            [yang.python.antlr.uast :as u]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.engine :as engine]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The fast tests
;; =============================================================================

(defn- module-ast
  [pk nm]
  (lower/module-packet pk {:name nm, :package? false}))


(defn- published
  "A store holding a stand-in manifest of `ast` and its site set:
   `[store manifest publication]`."
  [ast]
  (let [store (mem/create-content-mem)
        tree (:tree (siteset/site-set-of ast))
        manifest (jing/materialize! store {:yin.module/tree (:root tree)})]
    [store manifest (siteset/publish! store manifest ast)]))


(def ^:private requirement
  {:profile :py/hooks, :hooks true})


(def ^:private key-a
  (let [seed (apply str (repeat 32 "a1"))]
    {:seed seed, :public (sign/public-of seed)}))


(def ^:private key-b
  (let [seed (apply str (repeat 32 "b2"))]
    {:seed seed, :public (sign/public-of seed)}))


(deftest the-set-is-content-addressed-test
  (let [wt (module-ast loops/while-true-pass "w")
        [_ _ a] (published wt)
        [_ _ b] (published wt)
        [_ _ other] (published (module-ast programs/m-packet "m"))]
    (testing "the same code is the same set address"
      (is (= (:set-address a) (:set-address b)))
      (is (= (:record-address a) (:record-address b))))
    (testing "other marks are another set"
      (is (not= (:set a) (:set other)))
      (is (not= (:set-address a) (:set-address other))))
    (testing "a loop module's set marks its loop"
      (is (some (fn [[_ mark]] (= :loop mark)) (:set a))))
    (testing "the marks stay out of the published tree's rows"
      (is (not-any? #(= :yang/site %)
                    (tree-seq coll? seq (:rows (:tree (siteset/site-set-of wt)))))))
    (testing "the record pins root, revision, profile and set"
      (is (= (:yang.site/root (:record a))
             (:root (:tree (siteset/site-set-of wt)))))
      (is (= siteset/frontend-revision (:yang.site/revision (:record a))))
      (is (= siteset/profile-address
             (:yang.site/profile (:record a)))))))


(deftest the-association-verifies-by-address-test
  (let [[store manifest p] (published (module-ast loops/while-true-pass "w"))]
    (is (nil? (siteset/verify store manifest (:record-address p))))
    (is (= {:status :ok, :instrumented? true, :set (:set p),
            :set-address (:set-address p)}
           (select-keys (siteset/admit store manifest [(:record-address p)]
                                       requirement)
                        [:status :instrumented? :set :set-address])))
    (testing "an address the store does not hold has no record"
      (is (= :record-absent
             (:defect (siteset/verify store manifest :segment/other)))))))


(deftest every-moved-pin-is-a-mismatch-test
  (let [[store manifest p] (published (module-ast loops/while-true-pass "w"))
        r (:record p)
        empty-set (jing/materialize! store [])
        bad-entry (jing/materialize! store [[[0] :loop] [:oops]])
        bad-kind (jing/materialize! store [[[0] :nonsense]])
        at (fn [record] (jing/materialize! store record))]
    (doseq [[defect tampered]
            {:root (assoc r :yang.site/root :segment/other),
             :revision (assoc r :yang.site/revision "other"),
             :profile (assoc r :yang.site/profile :segment/other),
             :set-absent (assoc r :yang.site/set :segment/other),
             :set-empty (assoc r :yang.site/set empty-set),
             :kind (assoc r :yang.site/set bad-kind),
             :manifest (assoc r :yang.site/manifest :other)}]
      (testing (name defect)
        (let [address (at tampered)]
          (is (= defect (:defect (siteset/verify store manifest address))))
          (let [out (siteset/admit store manifest [address] requirement)]
            (is (= :refused (:status out)))
            (is (= :yang.python.antlr/site-set-mismatch (:reason out)))
            (is (= :py/hooks (:profile out)))))))
    (testing "a non-pair element is a defect, not a throw"
      (is (= :kind (:defect (siteset/verify
                              store manifest
                              (at (assoc r :yang.site/set bad-entry)))))))
    (testing "the mismatch names the set address"
      (let [out (siteset/admit store manifest
                               [(at (assoc r :yang.site/set empty-set))]
                               requirement)]
        (is (= empty-set (:set out)))
        (is (str/includes? (:message out) (str empty-set)))))))


(deftest a-missing-set-is-refused-by-profile-test
  (let [[store manifest _] (published (module-ast loops/while-true-pass "w"))
        out (siteset/admit store manifest [] requirement)]
    (is (= :refused (:status out)))
    (is (= :yang.python.antlr/missing-site-set (:reason out)))
    (is (= :py/hooks (:profile out)))
    (is (re-find #":py/hooks" (:message out)))
    (is (re-find #"site set" (:message out)))))


(deftest a-noninterruptible-profile-needs-no-set-test
  (let [[store manifest _] (published (module-ast programs/m-packet "m"))
        out (siteset/admit store manifest []
                           {:profile :py/plain, :noninterruptible true})]
    (is (= :ok (:status out)))
    (is (false? (:instrumented? out)))
    (is (re-find #"noninterruptible" (:note out)))))


(deftest a-profile-must-declare-hooks-or-noninterruptible-test
  (let [[store manifest p] (published (module-ast programs/m-packet "m"))
        out (siteset/admit store manifest [(:record-address p)]
                           {:profile :py/odd})]
    (is (= :refused (:status out)))
    (is (= :yang.python.antlr/profile-undeclared (:reason out)))))


(deftest a-record-is-discovered-from-a-declared-principals-entry-test
  (let [[store manifest p] (published (module-ast loops/while-true-pass "w"))
        entry (siteset/signed-entry key-a manifest (:record-address p))
        datoms (siteset/entry-datoms [entry])]
    (is (= [(:record-address p)]
           (siteset/discover datoms [(:public key-a)] manifest)))
    (testing "an undeclared principal's entry is not discovered"
      (is (= [] (siteset/discover datoms [(:public key-b)] manifest))))
    (testing "another module's record is not discovered"
      (is (= [] (siteset/discover datoms [(:public key-a)] :other))))
    (testing "a forged entry (record swapped after signing) is not discovered"
      (let [forged (assoc-in entry [:envelope :yang.site/record] :segment/other)]
        (is (= [] (siteset/discover (siteset/entry-datoms [forged])
                                    [(:public key-a)] manifest)))))
    (testing "an entry with no proof is not discovered"
      (is (= [] (siteset/discover (take 1 datoms) [(:public key-a)] manifest))))
    (testing "a discovered record admits"
      (is (= :ok (:status (siteset/admit
                            store manifest
                            (siteset/discover datoms [(:public key-a)] manifest)
                            requirement)))))))


(defn- stub-store
  "A store holding stand-in `py` and `pysp` modules (the stage reads the
   hook module's manifest for the derived footprint): `[store py pysp]`."
  []
  (let [store (mem/create-content-mem)
        stub (fn [nm]
               (:address (publish/publish-module!
                           store {:name nm, :ast (u/lit nil), :exports #{},
                                  :requires {}, :primitives {}})))]
    [store (stub 'py) (stub 'pysp)]))


(defn- hook-requirement
  [pysp]
  (assoc requirement
         :hooks-profile hooks/linked-profile
         :pysp pysp))


(defn- records-of
  "`{manifest [record-address]}` for the publication `result`."
  [result]
  (into {}
        (map (fn [[n s]]
               [(get (:addresses result) n) [(:record-address s)]]))
        (:sites result)))


(def ^:private no-datoms
  {:datoms [], :principals []})


(deftest an-scc-member-alias-carries-no-set-test
  (let [[store py pysp] (stub-store)
        result (publication/publish-units!
                 store (h/registry) py
                 [{:name "ca", :packet programs/ca-packet}
                  {:name "cb", :packet programs/cb-packet}])
        bundle (get (:bundles result) 'pyscc.ca$$cb)
        alias-a (get (:addresses result) "ca")
        bundle-site (get (:sites result) 'pyscc.ca$$cb)
        ctx (assoc no-datoms :store store)]
    (is (= :ok (:status result)) (pr-str result))
    (testing "the marks are in the bundle, which carries the set"
      (is (seq (:set bundle-site)))
      (is (nil? (siteset/verify store bundle (:record-address bundle-site))))
      (is (= :ok (:status (siteset/admit store bundle
                                         [(:record-address bundle-site)]
                                         requirement)))))
    (testing "an alias has no marks and no set"
      (is (not (contains? (:sites result) "ca")))
      (is (not (contains? (:sites result) "cb"))))
    (testing "a member import under a hook profile is refused by the stage,
              not run uninstrumented under the lie of an empty set"
      (let [out ((siteset/stage (h/registry) (hook-requirement pysp) {})
                 ctx 'pym.ca alias-a)]
        (is (= :refused (:status out)))
        (is (= :yang.python.antlr/missing-site-set (:reason out)))
        (is (contains? siteset/reasons (:reason out)))
        (is (= 'pym.ca (:name out)))
        (is (= :py/hooks (:profile out)))))
    (testing "a noninterruptible profile imports the alias as published"
      (is (= {:status :ok, :address alias-a}
             (select-keys ((siteset/stage (h/registry)
                                          {:profile :py/plain,
                                           :noninterruptible true}
                                          {})
                           ctx 'pym.ca alias-a)
                          [:status :address]))))
    (is (some? bundle))))


(deftest the-stage-answers-the-derive-record-test
  (let [[store py pysp] (stub-store)
        result (publication/publish-units!
                 store (h/registry) py
                 [{:name "w", :packet loops/while-true-pass}])
        manifest (get (:addresses result) "w")
        ctx (assoc no-datoms :store store)
        out ((siteset/stage (h/registry) (hook-requirement pysp)
                            (records-of result))
             ctx 'pym.w manifest)
        record (jing/get store (:derivation out) nil)
        selected (filter (fn [[_ mark]]
                           (#{:loop :call} (if (vector? mark) (first mark) mark)))
                         (:set (get (:sites result) "w")))
        sites-address (jing/segment-key (safepoint/site-set selected))]
    (is (= :ok (:status out)) (pr-str out))
    (is (not= manifest (:address out)))
    (testing "the :derive record is materialized and answered"
      (is (= :derive (:yin.ledger/op record)))
      (is (= :yang.safepoint/insert (:yin.ledger/function record)))
      (is (= (:yin.module/tree (jing/get store manifest nil))
             (:yin.ledger/input record)))
      (is (= (:yin.module/tree (jing/get store (:address out) nil))
             (:yin.ledger/output record)))
      (is (= {:yang.safepoint/hooks hooks/linked-profile,
              :yang.safepoint/sites sites-address}
             (:yin.ledger/profile record))))))


(deftest a-dependency-with-no-set-refuses-the-chain-test
  (let [[store py pysp] (stub-store)
        result (publication/publish-units!
                 store (h/registry) py
                 [{:name "k", :packet programs/k-packet}
                  {:name "t", :packet programs/t-packet}])
        records (records-of result)
        t (get (:addresses result) "t")
        k (get (:addresses result) "k")
        stage (siteset/stage (h/registry) (hook-requirement pysp)
                             (dissoc records k))
        out (stage (assoc no-datoms :store store) 'pym.t t)]
    (is (= :ok (:status ((siteset/stage (h/registry) (hook-requirement pysp)
                                        records)
                         (assoc no-datoms :store store) 'pym.t t))))
    (is (= :refused (:status out)))
    (is (= :yang.python.antlr/missing-site-set (:reason out)))
    (is (= 'pym.k (:name out)))
    (is (= t (:via out)))))


;; =============================================================================
;; The acceptance: imports on the three vector VMs, through the linker
;; =============================================================================

(defn- with-signals
  [signals]
  (fn [task]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref task] (engine/attach-resource task handle)]
        (assoc-in task [:store hooks/signals-key] ref)))))


(defn- publish!
  "`units` published into the harness store: `publish-units!`'s answer."
  [units]
  (let [{:keys [store address]} @h/published
        result (publication/publish-units! store (h/registry) address units)]
    (is (= :ok (:status result)) (pr-str result))
    result))


(defn- name-env
  "The canonical name environment of publication `result`: `py`, `pysp` and
   each unit's published address, the only addresses a stage-composed
   source is configured with."
  [result]
  (into {'py (:address @h/published),
         'pysp (:address @h/published-pysp)}
        (map (fn [[n a]] [(lower/module-symbol n) a]))
        (:addresses result)))


(defn- staged-run
  "The importer `pk` on backend `k` over the linker source composed with
   `stage` over `env` (canonical addresses only), under `signals`:
   `{:outcome [halted? blocked? output] :responses [...]}`, every response
   the link pair carried."
  [k pk env stage signals]
  (let [src (link/composition {:content-store (:store @h/published),
                               :name-env env,
                               :derivation :trusted,
                               :stage stage})
        pair (link/make-pair)
        ast (hooks/linked-program (lower/lower-packet pk {:prelude :linked}))
        task ((get h/backends k) ast (h/composition (h/registry) pair))
        [task pair] (h/drive ((with-signals signals) task) pair src)]
    {:outcome [(vm/halted? task) (vm/blocked? task)
               (render/output (vm/value task))],
     :responses (mesh/values (:responses pair))}))


(defn- staged-on-every-vm
  [pk env stage signals]
  (into {}
        (map (fn [k] [k (staged-run k pk env stage signals)]))
        (keys h/backends)))


(def ^:private interrupted
  [true false {:py/out [],
               :py/exception {:type "KeyboardInterrupt", :args []}}])


(defn- imports-a-loop-under-hooks
  []
  (testing "a published while-True module imported under a hook profile
            through a stage-composed linker source (canonical addresses
            only) is delivered as the module its verified set instruments:
            a pending signal interrupts it across the import boundary"
    (let [result (publish! [{:name "m", :packet loops/while-true-pass}])
          plain (get (:addresses result) "m")
          stage (siteset/stage (h/registry)
                               (hook-requirement
                                 (:address @h/published-pysp))
                               (records-of result))]
      (doseq [[k {:keys [outcome responses]}]
              (staged-on-every-vm programs/d-packet (name-env result) stage
                                  [2])]
        (is (= interrupted outcome) (str k))
        (let [rebound (filter :yin.link/canonical responses)]
          (is (= 1 (count rebound)) (str k))
          (is (= plain (:yin.link/canonical (first rebound))) (str k))
          (is (some? (:yin.link/derivation (first rebound))) (str k)))))))


(deftest ^:slow import-with-set-is-instrumented-test
  (slow/guard "import-with-set-is-instrumented-test" imports-a-loop-under-hooks))


(defn- refuses-without-the-set
  []
  (testing "the same import without its set is refused at link time, naming
            the profile and the missing set; the importer ends with
            ImportError and nothing of the module runs"
    (let [result (publish! [{:name "m", :packet loops/while-true-pass}])
          stage (siteset/stage (h/registry)
                               (hook-requirement
                                 (:address @h/published-pysp))
                               {})]
      (doseq [[k {:keys [outcome responses]}]
              (staged-on-every-vm programs/d-packet (name-env result) stage
                                  [2])]
        (let [[halted? blocked? output] outcome
              refusal (some #(when (= :refused (:status %)) %) responses)]
          (is (true? halted?) (str k))
          (is (false? blocked?) (str k))
          (is (= "ImportError" (get-in output [:py/exception :type]))
              (str k))
          (is (= :yang.python.antlr/missing-site-set (:reason refusal))
              (str k))
          (is (= :py/hooks (:profile refusal)) (str k))
          (is (= 'pym.m (:name refusal)) (str k))
          (is (re-find #":py/hooks.*site set" (:message refusal)) (str k)))))))


(deftest ^:slow import-without-set-is-refused-test
  (slow/guard "import-without-set-is-refused-test" refuses-without-the-set))


(defn- noninterruptible-imports-plain
  []
  (testing "an explicitly noninterruptible profile delivers the module as
            published, uninstrumented"
    (let [result (publish! [{:name "m", :packet programs/m-packet}])
          stage (siteset/stage (h/registry)
                               {:profile :py/plain, :noninterruptible true}
                               {})]
      (doseq [[k {:keys [outcome responses]}]
              (staged-on-every-vm programs/d-packet (name-env result) stage
                                  [])]
        (is (= [true false {:py/out ["m-body" "7"], :py/exception nil}]
               outcome)
            (str k))
        (is (not-any? :yin.link/canonical responses) (str k))))))


(deftest ^:slow noninterruptible-profile-imports-plain-test
  (slow/guard "noninterruptible-profile-imports-plain-test"
              noninterruptible-imports-plain))


(defn- an-import-chain-derives-bottom-up
  []
  (testing "t imports k, which spins: both are rebound, t pinning the
            instrumented k it will link, so no dependency pin mismatches
            and a signal interrupts k's loop through t's import"
    (let [result (publish! [{:name "k", :packet loops/while-true-pass}
                            {:name "t", :packet programs/t-packet}])
          stage (siteset/stage (h/registry)
                               (hook-requirement
                                 (:address @h/published-pysp))
                               (records-of result))]
      (doseq [[k {:keys [outcome responses]}]
              (staged-on-every-vm programs/tt-packet (name-env result) stage
                                  [2])]
        (is (= interrupted outcome) (str k))
        (is (= 2 (count (filter :yin.link/canonical responses))) (str k))
        (is (not-any? #(= :refused (:status %)) responses) (str k))
        (is (not-any? #(= :dependency-pin-mismatch (:reason %)) responses)
            (str k))))))


(deftest ^:slow an-import-chain-derives-bottom-up-test
  (slow/guard "an-import-chain-derives-bottom-up-test"
              an-import-chain-derives-bottom-up))
