(ns yin.vm.linker.publish-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.mem :as mem]
            [dao.jing :as jing]
            [dao.space.index :as space.index]
            [dao.space.query :as query]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.vm :as vm]
            [yin.vm.completion :as completion]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.repl.require-test :as require-test]
            [yin.vm.linker.authority :as authority]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]))


(defn- lit
  [x]
  {:type :literal :value x})


(defn- var-node
  [x]
  {:type :variable :name x})


(defn- app
  [f & xs]
  {:type :application :operator f :operands (vec xs)})


(def module-ast (app (var-node 'yin/def) (lit 'f) (lit 42)))


(deftest undefined-export-writes-nothing
  (let [store (mem/create-content-mem)
        result (publish/publish-module!
                 store {:name 'example :ast module-ast
                        :exports #{'missing} :requires {} :primitives {}})]
    (is (= :yin.link.publish/undefined-export (:reason result)))
    (is (nil? (jing/get store (:root (vm/ast->semantic-bytecode module-ast)) nil)))
    (let [nested {:type :lambda :params [] :body module-ast}
          root (:root (vm/ast->semantic-bytecode nested))
          res (publish/publish-module!
                store {:name 'example :ast nested :exports #{'f}
                       :requires {} :primitives {}})]
      (is (= :yin.link.publish/undefined-export (:reason res)))
      (is (nil? (jing/get store root nil))))))


(deftest malformed-definition-refuses-as-data
  (let [store (mem/create-content-mem)
        malformed (app (assoc (var-node 'yin/def) :source :extra)
                       (lit 'f) (lit 42) (lit 43))
        result (publish/publish-module!
                 store {:name 'bad :ast malformed :exports #{}
                        :requires {} :primitives {}})]
    (is (= :refused (:status result)))
    (is (= :yin.link.publish/malformed-tree (:reason result)))))


(deftest invalid-required-manifest-refuses-before-writing
  (let [store (mem/create-content-mem)
        address (jing/materialize! store {:not :a-manifest})
        root (:root (vm/ast->semantic-bytecode module-ast))
        result (publish/publish-module!
                 store {:name 'bad :ast module-ast :exports #{'f}
                        :requires {'dep address} :primitives {}})]
    (is (= {:status :refused :reason :yin.link.publish/invalid-requirement
            :name 'dep}
           result))
    (is (nil? (jing/get store root nil)))))


(deftest a-required-manifest-with-a-malformed-index-refuses-as-data
  (doseq [bad [42 [1] "bad"]]
    (testing (pr-str bad)
      (let [store (mem/create-content-mem)
            dep (:manifest (publish/publish-module!
                             store {:name 'dep :ast module-ast :exports #{'f}
                                    :requires {} :primitives {}}))
            address (jing/materialize! store (assoc dep :yin.module/index bad))
            ast (app (var-node 'yin/def) (lit 'g) (lit 1))
            root (:root (vm/ast->semantic-bytecode ast))
            result (try (publish/publish-module!
                          store {:name 'app :ast ast :exports #{'g}
                                 :requires {'dep address} :primitives {}})
                        (catch #?(:cljd Object :clj Throwable :cljs :default) e
                          {:threw (str e)}))]
        (is (= {:status :refused :reason :yin.link.publish/invalid-requirement
                :name 'dep}
               result))
        (is (nil? (jing/get store root nil)) "nothing was written")))))


(deftest publishes-four-formats
  (let [store (mem/create-content-mem)
        spec {:name 'example :ast module-ast :exports #{'f}
              :requires {} :primitives {}}
        result (publish/publish-module! store spec)]
    (is (jing/segment-address? (:address result)))
    (is (= #{:yin.ast/code :yin.semantic/code
             :yin.debruijn.code :yin.debruijn.register}
           (set (keys (:links result)))))
    (is (every? #(= :ok (:status %)) (vals (:links result))))
    (is (= (:address result)
           (:address (publish/publish-module! store spec))))))


(deftest footprint-derives-store-effects-and-dependencies
  (let [store (mem/create-content-mem)
        dependency (jing/materialize!
                     store {:yin.module/footprint
                            {:store-keys #{'foreign} :effects #{:remote}}})
        ast (app {:type :lambda :params '[_]
                  :body {:type :vm/store-get :key 'k}}
                 {:type :vm/store-put :key 'k :val (lit 1)})
        own (vm/ast-requirements
              (query/relation
                (vals (:rows (vm/ast->semantic-bytecode ast)))))
        fp (publish/footprint
             store {:ast ast :primitives
                    {'p {:yin.k/profile :profile/p
                         :yin.k/effects #{:primitive}}}
                    :requires {'foreign dependency}})]
    (is (= (:store-keys own) (:store-keys fp)))
    (is (= #{'k} (:store-keys fp)))
    (is (= #{:primitive :remote} (:effects fp)))
    (is (= #{:stream/make}
           (:effects (publish/footprint
                       store {:ast {:type :stream/make :buffer 4}
                              :primitives {} :requires {}}))))))


(deftest footprint-refuses-unrepresentable-requirements-before-writing
  (let [store (mem/create-content-mem)
        cases [[module-ast {'p nil} {}
                :yin.link.publish/unprofiled-primitive]
               [module-ast {} {'dep (jing/segment-key {:missing true})}
                :yin.link.publish/missing-requirement]
               [{:type :dao.stream.apply/call :op :op/echo
                 :operands [(lit 1)]} {} {}
                :yin.link.publish/ffi-op]
               [{:type :vm/resume :parked-id :p1 :val (lit 1)} {} {}
                :yin.link.publish/parked-id]]]
    (doseq [[ast primitives requires reason] cases]
      (let [root (:root (vm/ast->semantic-bytecode ast))
            res (publish/publish-module!
                  store {:name 'example :ast ast :exports #{}
                         :primitives primitives :requires requires})]
        (is (= reason (:reason res)))
        (is (nil? (jing/get store root nil)))))))


(deftest link-results-describe-closed-and-store-corpora
  (let [profile {'+ (vm/profile-of vm/primitives '+)}
        closed (publish/publish-module!
                 (mem/create-content-mem)
                 {:name 'closed :ast module-ast :exports #{'f}
                  :requires {} :primitives {}})
        stored (publish/publish-module!
                 (mem/create-content-mem)
                 {:name 'stored :ast require-test/store-module
                  :exports require-test/store-exports
                  :requires {} :primitives profile})]
    (is (every? #(= :ok (:status %)) (vals (:links closed))))
    (is (= :ok (get-in stored [:links :yin.debruijn.code :status])))
    (is (= :ok (get-in stored [:links :yin.debruijn.register :status])))
    (is (= {:status :refused :reason :undeclared-free :name 'n}
           (get-in stored [:links :yin.ast/code])))
    (is (= :ok (get-in stored [:links :yin.semantic/code :status])))))


(deftest published-footprint-completes-module-discovery
  (let [store (mem/create-content-mem)
        {:keys [address manifest]}
        (publish/publish-module!
          store {:name 'stream :ast module-ast :exports #{'f}
                 :requires {} :primitives {}})
        modules (module/register-stream-module (module/default-registry))
        machine (semantic/load-vector
                  (semantic/create-vm {:modules modules})
                  (:vector (linearize/lower-rows
                             (vm/ast->semantic-bytecode
                               (var-node 'stream/make))))
                  vm/semantic-contract)
        result (completion/complete
                 {:vm machine
                  :modules {'stream
                            {:yin.k/manifest address
                             :yin.k/effects
                             (get-in manifest
                                     [:yin.module/footprint :effects])}}})]
    (is (empty? (get-in result [:yin.k/missing :footprints])))))


;; =============================================================================
;; L4: a module derived from indexed code (yin.vm.linker.dht.md 5.3)
;; =============================================================================

(defn indexed-datoms
  "The `[e a v t m]` datoms of the index a memory-store shell publishes
   after evaluating `lines`."
  [lines]
  (let [state (reduce (fn [s line] (first (repl/eval-input s line)))
                      (repl.frontends/create-state)
                      lines)
        ix (:indexer state)]
    (vec (space.index/read-datoms (:content-store ix) (:manifest-address ix)))))


(defn indexed
  "`indexed-datoms` as a relation."
  [lines]
  (query/relation (indexed-datoms lines)))


(defn module-defs
  "The symbols the tree defines at module level, in evaluation order."
  [ast]
  (letfn [(scan
            [node]
            (when (= :application (:type node))
              (let [own (when (= 'yin/def (get-in node [:operator :name]))
                          [(get-in node [:operands 0 :value])])]
                (concat (mapcat scan (:operands node))
                        own
                        (when (= :lambda (get-in node [:operator :type]))
                          (scan (get-in node [:operator :body])))))))]
    (vec (scan ast))))


(def ^:private base-address
  (jing/segment-key "the manifest the session linked as base"))


(def ^:private derived-lines
  "The require is typed last: with no content source it parks, and a
   parked program is indexed but holds every later line back."
  ["(def h (fn [x] (+ x 1)))"
   "(def unused 5)"
   "(def f (fn [] (h (base/f))))"
   "(def h (fn [x] (+ x 2)))"
   "(require (quote base))"])


(defn- module-requires
  "The modules the tree requires at module level, in evaluation order."
  [ast]
  (letfn [(scan
            [node]
            (when (= :application (:type node))
              (concat (mapcat scan (:operands node))
                      (when (= 'require (get-in node [:operator :name]))
                        [(get-in node [:operands 0 :value])])
                      (when (= :lambda (get-in node [:operator :type]))
                        (scan (get-in node [:operator :body]))))))]
    (vec (scan ast))))


(deftest module-from-index-collects-the-program-requiring-each-linked-module
  (let [spec (publish/module-from-index
               (indexed derived-lines)
               {:name 'my.lib :exports '[f] :primitives vm/primitives
                :modules {'base base-address}})]
    (testing "the separately typed require is collected, once, in t order"
      (is (= '[base] (module-requires (:ast spec))))
      (is (= '[f h] (module-defs (:ast spec)))))
    (testing "no requiring program: refused, naming the module"
      (is (= {:status :refused :reason :yin.link.publish/missing-require-program
              :module 'base}
             (publish/module-from-index
               (indexed (pop derived-lines))
               {:name 'my.lib :exports '[f] :primitives vm/primitives
                :modules {'base base-address}}))))))


(deftest an-empty-export-list-is-the-canonical-no-op-module
  (let [db (indexed ["(def f (fn [] 1))"])
        opts {:name 'empty.lib :exports [] :primitives vm/primitives}
        spec (publish/module-from-index db opts)]
    (testing "zero collected programs: the canonical no-op tree, as data"
      (is (= {:name 'empty.lib :ast publish/no-op-tree :exports #{}
              :requires {} :primitives {}}
             spec)))
    (testing "the same spec from any index: nothing indexed is collected"
      (is (= spec (publish/module-from-index (indexed ["(def g 2)" "(def h 3)"]) opts))))
    (testing "it publishes, and links and runs on all four formats"
      (let [store (mem/create-content-mem)
            res (publish/publish-module! store spec)]
        (is (jing/segment-address? (:address res)) (pr-str res))
        (is (= {:yin.ast/code :ok :yin.semantic/code :ok
                :yin.debruijn.code :ok :yin.debruijn.register :ok}
               (into {} (map (fn [[f o]] [f (:status o)])) (:links res))))
        (is (= #{} (get-in (jing/get store (:address res) nil) [:yin.module/exports])))))))


(deftest module-from-index-collects-defining-programs-in-t-order
  (let [db (indexed derived-lines)
        spec (publish/module-from-index
               db {:name 'my.lib :exports '[f]
                   :primitives vm/primitives
                   :modules {'base base-address}})]
    (is (= 'my.lib (:name spec)))
    (is (= #{'f} (:exports spec)))
    (testing "f's program, then h's latest definition: greatest t, ascending"
      (is (= '[f h] (module-defs (:ast spec)))))
    (testing "the free primitives and the linked module are declared"
      ;; `require` is free in the collected requiring program
      (is (= {'+ (vm/profile-of vm/primitives '+)
              'require (vm/profile-of vm/primitives 'require)}
             (:primitives spec)))
      (is (= {'base base-address} (:requires spec))))
    (testing "a derived module publishes; :links is the linker's verdict (5.3)"
      (let [spec (publish/module-from-index
                   (indexed ["(def k (fn [] 1))" "(def g (fn [] (+ (k) 41)))"])
                   {:name 'closed :exports '[g] :primitives vm/primitives})
            res (publish/publish-module! (mem/create-content-mem) spec)
            statuses (into {} (map (fn [[f o]] [f (or (:reason o) (:status o))]))
                           (:links res))]
        (is (= '[k g] (module-defs (:ast spec))))
        (is (jing/segment-address? (:address res)))
        (is (= {:yin.ast/code :undeclared-free :yin.semantic/code :ok
                :yin.debruijn.code :ok :yin.debruijn.register :ok}
               statuses)
            "the export reads a module-level definition: the tree refuses it")
        (is (= 'k (get-in res [:links :yin.ast/code :name])))))))


(deftest module-from-index-refuses-what-it-cannot-declare
  (let [db (indexed ["(def g (fn [] (mystery 1)))"
                     "(require (quote dao.space.query))"
                     "(def q2 (fn [] (dao.space.query/q 1)))"
                     "(def ok (fn [] 1))"])
        derive (fn [exports]
                 (publish/module-from-index
                   db {:name 'my.lib :exports exports :primitives vm/primitives
                       :modules {} :host-modules #{'dao.space.query}}))]
    (is (= {:status :refused :reason :yin.link.publish/undefined-export
            :export 'nope}
           (derive '[ok nope])))
    (is (= {:status :refused :reason :yin.link.publish/undeclared-free
            :name 'mystery}
           (derive '[g])))
    (is (= {:status :refused :reason :yin.link.publish/host-module
            :name 'dao.space.query/q :module 'dao.space.query}
           (derive '[q2])))
    (is (= :my.lib (keyword (:name (derive '[ok]))))
        "an export with no free name derives")))


;; =============================================================================
;; L4: name envelopes and the derived sequence (6.1, 6.5, 6.6)
;; =============================================================================

(def ^:private k1 (sign/generate))
(def ^:private k2 (sign/generate))


(defn- envelope-db
  "An index of `signed` envelopes, one entity each, as 6.1 writes them."
  [signed]
  (query/relation
    (into []
          (comp (map-indexed (fn [i s]
                               (map (fn [[_ a v]] [(+ 100 i) a v i 1])
                                    (:datoms s))))
                cat)
          signed)))


(deftest the-sequence-is-derived-from-the-publisher-s-own-envelopes
  (let [a (jing/segment-key "manifest a")]
    (is (= 1 (publish/next-seq (envelope-db []) k1)))
    (is (= 4 (publish/next-seq
               (envelope-db [(publish/assertion k1 {:name 'x :manifest a :seq 3})
                             (publish/assertion k2 {:name 'y :manifest a :seq 9})])
               k1))
        "another principal's sequences do not count")))


(deftest republishing-a-name-retracts-then-asserts-and-the-same-manifest-writes-nothing
  (let [a (jing/segment-key "manifest a")
        b (jing/segment-key "manifest b")
        first-time (publish/name-envelopes (envelope-db []) k1 'lib a)
        db1 (envelope-db first-time)
        again (publish/name-envelopes db1 k1 'lib a)
        moved (publish/name-envelopes db1 k1 'lib b)
        principal (sign/principal (:public k1))
        declared {:principals {principal {:proof :yin.module/signature
                                          :key (:public k1)
                                          :verify sign/verify-envelope}}}
        fold (fn [signed]
               (authority/name-environment
                 declared (mapv (fn [s]
                                  {:yin.module/envelope (:envelope s)
                                   :yin.module/proof (:proof s)})
                                signed)))]
    (is (= [{:yin.module/op :assert :yin.module/name 'lib :yin.module/manifest a
             :yin.module/asserted-by principal :yin.module/seq 1}]
           (mapv :envelope first-time)))
    (is (= [] again) "the name already binds that manifest")
    (is (= [[:retract 2 (jing/segment-key (:envelope (first first-time)))]
            [:assert 3 b]]
           (mapv (fn [{e :envelope}]
                   [(:yin.module/op e) (:yin.module/seq e)
                    (or (:yin.module/of e) (:yin.module/manifest e))])
                 moved)))
    (testing "every proof verifies, and the fold resolves the new address"
      (is (= a (get-in (fold first-time) [:names 'lib :address])))
      (is (= b (get-in (fold (into first-time moved)) [:names 'lib :address])))
      (is (empty? (:diagnostics (fold (into first-time moved))))))
    (testing "without the retraction the fold refuses as ambiguous"
      (is (= :ambiguous-name
             (get-in (fold (conj first-time (last moved))) [:names 'lib :reason]))))
    (testing "a datom pair per envelope, one entity, as 6.1 indexes it"
      (is (= [[7 :yin.module/envelope (:envelope (first first-time))]
              [7 :yin.module/proof (:proof (first first-time))]]
             (:datoms (publish/assertion k1 {:name 'lib :manifest a :seq 1 :e 7})))))))
