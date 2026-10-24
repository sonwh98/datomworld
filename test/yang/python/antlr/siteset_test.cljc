(ns yang.python.antlr.siteset-test
  "C4 slice I7 (docs/design/yang.antlr.md 8.5.6, ruling 14): published site
   sets for imported modules.

   The fast tests pin the set's content addressing and the consumer's
   decisions over a stand-in manifest (the record pins a manifest's
   `:yin.module/tree`, so no publication is forced): a set is the same
   address for the same code and another for other code, a record
   verifies, every pin that moves is a mismatch, a missing set is refused
   naming the profile, and a noninterruptible profile imports without one.

   The slow tests run the acceptance on the three vector VMs: a published
   `while True` module imported under a hook profile is instrumented from
   its verified set, so a pending signal interrupts it across the import
   boundary; the same import without its set is refused before anything
   runs; and a noninterruptible profile imports an uninstrumented module."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.test-slow :as slow]
            [yang.python.antlr.import-programs :as programs]
            [yang.python.antlr.linked-harness :as h]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.render :as render]
            [yang.python.antlr.safepoint :as hooks]
            [yang.python.antlr.safepoint-programs :as loops]
            [yang.python.antlr.siteset :as siteset]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
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


(deftest the-set-is-content-addressed-test
  (let [wt (module-ast loops/while-true-pass "w")
        [_ _ a] (published wt)
        [_ _ b] (published wt)
        [_ _ other] (published (module-ast programs/m-packet "m"))]
    (testing "the same code is the same set address"
      (is (= (:set-address a) (:set-address b)))
      (is (= (:record-address a) (:record-address b))))
    (testing "other code is another set"
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


(deftest the-association-verifies-test
  (let [[store manifest p] (published (module-ast loops/while-true-pass "w"))]
    (is (nil? (siteset/verify store manifest (:record p))))
    (is (= {:status :ok, :instrumented? true, :set (:set p)}
           (select-keys (siteset/admit store manifest [(:record p)] requirement)
                        [:status :instrumented? :set])))))


(deftest every-moved-pin-is-a-mismatch-test
  (let [[store manifest p] (published (module-ast loops/while-true-pass "w"))
        r (:record p)]
    (doseq [[defect tampered] {:root (assoc r :yang.site/root :segment/other),
                               :revision (assoc r :yang.site/revision "other"),
                               :profile (assoc r :yang.site/profile :segment/other),
                               :set-absent (assoc r :yang.site/set :segment/other)}]
      (testing (name defect)
        (is (= defect (:defect (siteset/verify store manifest tampered))))
        (let [out (siteset/admit store manifest [tampered] requirement)]
          (is (= :refused (:status out)))
          (is (= :yang.python.antlr/site-set-mismatch (:reason out)))
          (is (= :py/hooks (:profile out))))))
    (testing "a record for another manifest is not discovered"
      (is (= :yang.python.antlr/missing-site-set
             (:reason (siteset/admit store manifest
                                     [(assoc r :yang.site/manifest :other)]
                                     requirement)))))))


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


;; =============================================================================
;; The acceptance: imports on the three vector VMs
;; =============================================================================

(defn- with-signals
  [signals]
  (fn [task]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref task] (engine/attach-resource task handle)]
        (assoc-in task [:store hooks/signals-key] ref)))))


(defn- import-module!
  "Publish `pk` as module `nm` under profile `requirement` and `records`:
   `{:admission a :address address-or-nil}`, the address the importer's
   name resolves to. A hook profile imports the module its verified set
   instruments."
  [pk nm requirement]
  (let [py (:address @h/published)
        pysp (:address @h/published-pysp)
        spec (lower/module-spec (h/registry) pk {:name nm, :py-address py, :deps {}})
        plain (h/publish-guest-module! spec)
        pub (siteset/publish! (:store @h/published) (:address plain) (:ast spec))
        records (if (:without-set requirement) [] [(:record pub)])
        adm (siteset/admit (:store @h/published) (:address plain) records requirement)]
    (cond
      (= :refused (:status adm)) {:admission adm}
      (not (:instrumented? adm)) {:admission adm, :address (:address plain)}
      :else
      (let [a' (siteset/instrumented-ast (:ast spec) (:set adm) hooks/linked-profile)
            derived (h/publish-guest-module!
                      (-> spec
                          (assoc :ast a')
                          (assoc-in [:requires 'pysp] pysp)))]
        {:admission adm, :address (:address derived)}))))


(defn- importer-on-every-vm
  "The importer `pk` run on every vector VM, `pym.<nm>` resolving to
   `address`, under `signals`."
  [pk nm address signals]
  (let [ast (hooks/linked-program (lower/lower-packet pk {:prelude :linked}))
        src (h/source {'py (:address @h/published),
                       'pysp (:address @h/published-pysp),
                       (lower/module-symbol nm) address})]
    (into {}
          (map (fn [k]
                 (let [task (h/run-linked k ast {:link-source src,
                                                 :prep (with-signals signals)})]
                   [k [(vm/halted? task) (vm/blocked? task)
                       (render/output (vm/value task))]])))
          (keys h/backends))))


(defn- imports-a-loop-under-hooks
  []
  (testing "a published while-True module under a hook profile, with its
            set, is instrumented: a pending signal interrupts it across the
            import boundary"
    (let [{:keys [admission address]} (import-module! loops/while-true-pass "m"
                                                      requirement)]
      (is (= :ok (:status admission)))
      (is (true? (:instrumented? admission)))
      (doseq [[k out] (importer-on-every-vm programs/d-packet "m" address [2])]
        (is (= [true false {:py/out [],
                            :py/exception {:type "KeyboardInterrupt", :args []}}]
               out)
            (str k))))))


(deftest ^:slow import-with-set-is-instrumented-test
  (slow/guard "import-with-set-is-instrumented-test" imports-a-loop-under-hooks))


(defn- refuses-without-the-set
  []
  (testing "the same import without its set is refused, naming the profile
            and the missing set; nothing is published to run"
    (let [{:keys [admission address]}
          (import-module! loops/while-true-pass "m"
                          (assoc requirement :without-set true))]
      (is (nil? address))
      (is (= :refused (:status admission)))
      (is (= :yang.python.antlr/missing-site-set (:reason admission)))
      (is (= :py/hooks (:profile admission)))
      (is (re-find #":py/hooks.*site set" (:message admission))))))


(deftest ^:slow import-without-set-is-refused-test
  (slow/guard "import-without-set-is-refused-test" refuses-without-the-set))


(defn- noninterruptible-imports-plain
  []
  (testing "an explicitly noninterruptible profile imports the module
            uninstrumented, and the admission says so"
    (let [{:keys [admission address]}
          (import-module! programs/m-packet "m"
                          {:profile :py/plain, :noninterruptible true})]
      (is (= :ok (:status admission)))
      (is (false? (:instrumented? admission)))
      (is (re-find #"without a site set" (:note admission)))
      (doseq [[k out] (importer-on-every-vm programs/d-packet "m" address [])]
        (is (= [true false {:py/out ["m-body" "7"], :py/exception nil}] out)
            (str k))))))


(deftest ^:slow noninterruptible-profile-imports-plain-test
  (slow/guard "noninterruptible-profile-imports-plain-test"
              noninterruptible-imports-plain))
