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
   is rebound by `siteset/link-env` to the instrumented module derived from
   its verified set and the published tree alone, so a pending signal
   interrupts it across the import boundary; the same import without its
   set is refused at link time, naming the profile and the set, before
   anything is derived or run; and a noninterruptible profile imports the
   module as published."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dao.jing :as jing]
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
            [yin.vm :as vm]
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


(deftest an-scc-member-alias-carries-no-set-test
  (let [store (mem/create-content-mem)
        py (publish/publish-module! store {:name 'py, :ast (u/lit nil),
                                           :exports #{}, :requires {},
                                           :primitives {}})
        result (publication/publish-units!
                 store (h/registry) (:address py)
                 [{:name "ca", :packet programs/ca-packet}
                  {:name "cb", :packet programs/cb-packet}])
        bundle (get (:bundles result) 'pyscc.ca$$cb)
        alias-a (get (:addresses result) "ca")
        bundle-site (get (:sites result) 'pyscc.ca$$cb)]
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
    (testing "a member import under a hook profile is refused, not run
              uninstrumented under the lie of an empty set"
      (let [out (siteset/link-env store (h/registry) {'pym.ca alias-a}
                                  {} requirement)]
        (is (= :refused (:status out)))
        (is (= :yang.python.antlr/missing-site-set (:reason out)))
        (is (= 'pym.ca (:name out)))
        (is (= :py/hooks (:profile out)))))
    (testing "a noninterruptible profile imports the alias as published"
      (is (= {:status :ok, :name-env {'pym.ca alias-a}}
             (siteset/link-env store (h/registry) {'pym.ca alias-a} {}
                               {:profile :py/plain, :noninterruptible true}))))
    (is (some? bundle))))


(deftest a-backend-failure-is-a-refusal-not-a-throw-test
  (let [store (mem/create-content-mem)
        failing (assoc store :put-bytes-fn
                       (fn [& _] (throw (ex-info "disk full" {}))))
        py (publish/publish-module! store {:name 'py, :ast (u/lit nil),
                                           :exports #{}, :requires {},
                                           :primitives {}})
        out (publication/publish-units!
              failing (h/registry) (:address py)
              [{:name "m", :packet programs/m-packet}]
              {}
              (fn [_ spec]
                ;; the module publishes through the healthy store; only the
                ;; site set's blobs reach the failing one
                (publish/publish-module! store spec)))]
    (is (= :refused (:status out)) (pr-str (select-keys out [:status :reason])))
    (is (= :yang.python.antlr/site-set-unpublished (:reason out)))))


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


(defn- link-import
  "Publish `pk` as module `nm` and answer `siteset/link-env` for the name
   environment holding it, under `requirement` (a hook profile gets the
   `pysp` linked profile and address). With `:without-set` the module's
   record is not discovered. `{:link env-or-refusal :publication p}`."
  [pk nm requirement]
  (let [{:keys [store address]} @h/published
        py address
        pysp (:address @h/published-pysp)
        spec (lower/module-spec (h/registry) pk {:name nm, :py-address py,
                                                 :deps {}})
        plain (h/publish-guest-module! spec)
        pub (siteset/publish! store (:address plain) (:ast spec))
        records (if (:without-set requirement)
                  {}
                  {(:address plain) [(:record-address pub)]})]
    {:publication pub,
     :plain (:address plain),
     :link (siteset/link-env store (h/registry)
                             {'py py, 'pysp pysp,
                              (lower/module-symbol nm) (:address plain)}
                             records
                             (assoc requirement
                                    :hooks-profile hooks/linked-profile
                                    :pysp pysp))}))


(defn- importer-on-every-vm
  "The importer `pk` run on every vector VM over the linker source the
   rebound name environment `name-env` configures, under `signals`."
  [pk name-env signals]
  (let [ast (hooks/linked-program (lower/lower-packet pk {:prelude :linked}))
        src (h/source name-env)]
    (into {}
          (map (fn [k]
                 (let [task (h/run-linked k ast {:link-source src,
                                                 :prep (with-signals signals)})]
                   [k [(vm/halted? task) (vm/blocked? task)
                       (render/output (vm/value task))]])))
          (keys h/backends))))


(defn- imports-a-loop-under-hooks
  []
  (testing "a published while-True module imported under a hook profile is
            rebound to the module its verified set instruments, derived
            from the published tree alone: a pending signal interrupts it
            across the import boundary"
    (let [{:keys [link publication plain]}
          (link-import loops/while-true-pass "m" requirement)]
      (is (= :ok (:status link)) (pr-str link))
      (is (seq (:set publication)))
      (is (not= plain (get-in link [:name-env 'pym.m])) "the name is rebound")
      (doseq [[k out] (importer-on-every-vm programs/d-packet (:name-env link)
                                            [2])]
        (is (= [true false {:py/out [],
                            :py/exception {:type "KeyboardInterrupt", :args []}}]
               out)
            (str k))))))


(deftest ^:slow import-with-set-is-instrumented-test
  (slow/guard "import-with-set-is-instrumented-test" imports-a-loop-under-hooks))


(defn- refuses-without-the-set
  []
  (testing "the same import without its set is refused at link time, naming
            the profile and the missing set; no name environment results"
    (let [{:keys [link]}
          (link-import loops/while-true-pass "m"
                       (assoc requirement :without-set true))]
      (is (nil? (:name-env link)))
      (is (= :refused (:status link)))
      (is (= :yang.python.antlr/missing-site-set (:reason link)))
      (is (= :py/hooks (:profile link)))
      (is (= 'pym.m (:name link)))
      (is (re-find #":py/hooks.*site set" (:message link))))))


(deftest ^:slow import-without-set-is-refused-test
  (slow/guard "import-without-set-is-refused-test" refuses-without-the-set))


(defn- noninterruptible-imports-plain
  []
  (testing "an explicitly noninterruptible profile imports the module as
            published, uninstrumented"
    (let [{:keys [link]}
          (link-import programs/m-packet "m"
                       {:profile :py/plain, :noninterruptible true})]
      (is (= :ok (:status link)) (pr-str link))
      (doseq [[k out] (importer-on-every-vm programs/d-packet (:name-env link)
                                            [])]
        (is (= [true false {:py/out ["m-body" "7"], :py/exception nil}] out)
            (str k))))))


(deftest ^:slow noninterruptible-profile-imports-plain-test
  (slow/guard "noninterruptible-profile-imports-plain-test"
              noninterruptible-imports-plain))
