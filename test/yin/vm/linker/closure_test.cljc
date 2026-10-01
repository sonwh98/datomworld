(ns yin.vm.linker.closure-test
  "Linker over DHT slice L2 (docs/design/yin.vm.linker.dht.md 4.2, 5.2):
   the module closure walker over a `dao.jing` handle, and the publisher
   walking the closure it minted.  The fixtures are public:
   `yin.vm.linker.dht-test` loads the same closures over the DHT."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yin.vm :as vm]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure :as closure]
            [yin.vm.linker.publish :as publish]))


;; =============================================================================
;; The corpus: base, and app requiring it
;; =============================================================================

(defn lit
  [x]
  {:type :literal :value x})


(defn- v
  [n]
  {:type :variable :name n})


(defn- lam
  [params body]
  {:type :lambda :params params :body body})


(defn- app
  [f & xs]
  {:type :application :operator f :operands (vec xs)})


(defn def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(def base-ast
  "`(yin/def f (fn [] (+ 40 2)))`."
  (def! 'f (lam [] (app (v '+) (lit 40) (lit 2)))))


(def app-ast
  "`(yin/def g (+ (base/f) 1))`: calls the export of `base` as it runs."
  (def! 'g (app (v '+) (app (v 'base/f)) (lit 1))))


(def plus
  {'+ (vm/profile-of vm/primitives '+)})


(defn publish-base!
  "Publish `base` (optionally under another `ast`) into `store`."
  ([store] (publish-base! store base-ast))
  ([store ast]
   (publish/publish-module!
     store {:name 'base :ast ast :exports #{'f} :requires {} :primitives plus})))


(defn publish-app!
  "Publish `app` into `store`, pinned to the manifest `base-address`."
  [store base-address]
  (publish/publish-module!
    store {:name 'app :ast app-ast :exports #{'g}
           :requires {'base base-address} :primitives plus}))


(defn entries
  [store]
  ((:entries-fn store)))


(defn- addresses
  [store]
  (set (keys (entries store))))


(defn without
  "A fresh store holding every blob of `store` except `address`."
  [store address]
  (mem/create-content-mem (dissoc (entries store) address)))


(defn tree-rows
  "Every row address of the tree `root` in `store`, with its body."
  [store root]
  (loop [queue [root] seen {}]
    (if-let [a (first queue)]
      (if (contains? seen a)
        (recur (subvec queue 1) seen)
        (let [body (jing/get store a nil)]
          (recur (into (subvec queue 1) ((:parts-fn linker/ast-format) body))
                 (assoc seen a body))))
      seen)))


(defn leaf-and-interior
  "A leaf row (no children) and an interior row (children, not the root)
   of the tree `root`."
  [store root]
  (let [rows (tree-rows store root)
        kids (fn [a] (seq ((:parts-fn linker/ast-format) (get rows a))))]
    {:leaf (some (fn [a] (when-not (kids a) a)) (sort-by str (keys rows)))
     :interior (some (fn [a] (when (and (kids a) (not= a root)) a))
                     (sort-by str (keys rows)))}))


(defn world
  "One store holding base and app: `{:store s :base b :app a}`, each the
   publisher's answer."
  []
  (let [store (mem/create-content-mem)
        base (publish-base! store)
        app-res (publish-app! store (:address base))]
    {:store store :base base :app app-res}))


(defn- with-manifest
  "Materialize `manifest` in `store`; answers its address."
  [store manifest]
  (jing/materialize! store manifest))


(defn invalid-fixtures
  "One store per closed defect code of 4.2, each with the manifest whose
   walk yields it: `[[code store manifest-address] ...]`."
  []
  (let [fresh (fn []
                (let [store (mem/create-content-mem)
                      res (publish-base! store)]
                  [store res]))]
    [(let [[store res] (fresh)
           {:keys [leaf]} (leaf-and-interior store (get-in res [:manifest :yin.module/tree]))
           forged (mem/create-content-mem
                    (assoc (entries store) leaf
                           (jing/canonical-bytes [:not-the-row])))]
       [:address-mismatch forged (:address res)])
     (let [[store res] (fresh)]
       [:manifest-defect store
        (with-manifest store (assoc (:manifest res) :yin.module/schema 2))])
     (let [[store res] (fresh)
           row (jing/materialize! store [:no-such-tag 1])]
       [:row-defect store
        (with-manifest store (assoc (:manifest res) :yin.module/tree row))])
     (let [[store res] (fresh)
           bogus (jing/materialize! store {:not :a-record})]
       [:record-defect store
        (with-manifest store (assoc-in (:manifest res)
                                       [:yin.module/derivations :yin.semantic/code]
                                       bogus))])
     (let [[store res] (fresh)
           other (publish-base! store (def! 'f (lit 7)))]
       [:derivation-mismatch store
        (with-manifest store (assoc-in (:manifest res)
                                       [:yin.module/derivations :yin.debruijn.code]
                                       (get-in other [:manifest :yin.module/derivations
                                                      :yin.debruijn.code])))])
     (let [[store res] (fresh)
           h (get-in res [:identities :yin.debruijn.code])]
       [:index-entry-missing store
        (with-manifest store (update (:manifest res) :yin.module/index dissoc h))])
     (let [[store res] (fresh)
           {:keys [manifest identities]} res
           h (:yin.debruijn.code identities)
           r (:yin.debruijn.register identities)]
       [:identity-mismatch store
        (with-manifest store (assoc-in manifest [:yin.module/index h]
                                       (get-in manifest [:yin.module/index r])))])
     (let [[store res] (fresh)]
       [:parts-limit store (:address res) {:bounds {:max-parts 3}}])]))


(defn deep-fixture
  "`[:parts-limit store manifest-address]`: base's manifest over a tree of
   lambdas nested beyond `yin.vm.linker/default-bounds`' `:max-depth`."
  []
  (let [store (mem/create-content-mem)
        res (publish-base! store)
        deep (reduce (fn [body _] (lam [] body))
                     (lit 1)
                     (range (inc (:max-depth linker/default-bounds))))
        tree (vm/materialize-tree! store (vm/ast->semantic-bytecode deep))]
    [:parts-limit store
     (with-manifest store (assoc (:manifest res) :yin.module/tree tree))]))


;; =============================================================================
;; A complete closure
;; =============================================================================

(deftest a-complete-walk-holds-every-blob-of-both-modules
  (let [{:keys [store base app]} (world)
        w (closure/walk store (:address app))]
    (is (= :complete (:yin.link.closure/outcome w)) (pr-str w))
    (is (= (:address app) (:manifest w)))
    (is (= [(:address app) (:address base)] (:modules w)))
    (is (= (count (entries store)) (:blobs w))
        "the publisher wrote exactly the two closures")
    (is (= [{:module (:address app) :name 'base :manifest (:address base)}]
           (:requires w)))))


(deftest requires-lists-every-requirement-of-every-module-in-walk-order
  (let [store (mem/create-content-mem)
        base (publish-base! store)
        mid (publish/publish-module!
              store {:name 'mid :ast (def! 'h (lam [] (app (v 'base/f))))
                     :exports #{'h} :requires {'base (:address base)}
                     :primitives {}})
        top (publish/publish-module!
              store {:name 'top
                     :ast (def! 't (lam [] (app (v '+) (app (v 'base/f))
                                                (app (v 'mid/h)))))
                     :exports #{'t}
                     :requires {'base (:address base) 'mid (:address mid)}
                     :primitives plus})
        w (closure/walk store (:address top))]
    (is (= :complete (:yin.link.closure/outcome w)) (pr-str w))
    (is (= [(:address top) (:address base) (:address mid)] (:modules w))
        "a manifest already visited is not walked again")
    (is (= [{:module (:address top) :name 'base :manifest (:address base)}
            {:module (:address top) :name 'mid :manifest (:address mid)}
            {:module (:address mid) :name 'base :manifest (:address base)}]
           (:requires w)))))


;; =============================================================================
;; :missing names exactly the absent blob and its role
;; =============================================================================

(deftest removing-any-one-blob-is-missing-naming-it
  (let [{:keys [store base app]} (world)
        m (:address app)
        manifest (:manifest app)
        tree (:yin.module/tree manifest)
        {:keys [leaf interior]} (leaf-and-interior store tree)
        records (:yin.module/derivations manifest)
        {:keys [identities]} app
        h (:yin.debruijn.code identities)
        r (:yin.debruijn.register identities)
        base-m (:address base)
        base-tree (get-in base [:manifest :yin.module/tree])
        cases [[m :manifest [m]]
               [tree :row [m]]
               [leaf :row [m]]
               [interior :row [m]]
               [(:yin.semantic/code records) :record [m]]
               [(:yin.debruijn.code records) :record [m]]
               [(:yin.debruijn.register records) :record [m]]
               [(:yin.semantic/code identities) :image [m]]
               [(get-in manifest [:yin.module/index h]) :image [m]]
               [(get-in manifest [:yin.module/index r]) :image [m]]
               [base-m :require [m base-m]]
               [base-tree :row [m base-m]]]]
    (is (some? leaf))
    (is (some? interior))
    (is (= (count cases) (count (distinct (map first cases)))))
    (doseq [[address role path] cases]
      (testing (str (name role) " " address)
        (is (= {:yin.link.closure/outcome :missing
                :address address :role role :path path}
               (closure/walk (without store address) m)))))))


;; =============================================================================
;; :invalid, one test per closed code
;; =============================================================================

(deftest each-closed-code-is-produced
  (let [fixtures (invalid-fixtures)]
    (is (= #{:address-mismatch :manifest-defect :row-defect :record-defect
             :derivation-mismatch :index-entry-missing :identity-mismatch
             :parts-limit}
           (set (map first fixtures))))
    (doseq [[code store address opts] fixtures]
      (testing (name code)
        (let [w (closure/walk store address opts)]
          (is (= :invalid (:yin.link.closure/outcome w)) (pr-str w))
          (is (= code (get-in w [:defect :code])) (pr-str w)))))))


(def malformed-values
  "Values no manifest key accepts, of every collection and scalar kind."
  [42 [1] "bad" #{1} '(1) :k {1 2} [[1 2]]])


(defn- defect-or-throw
  [manifest]
  (try (linker/manifest-defect manifest)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         {::threw (str e)})))


(deftest the-manifest-validator-is-total-over-every-key
  (let [store (mem/create-content-mem)
        manifest (:manifest (publish-base! store))]
    (is (nil? (linker/manifest-defect manifest)) "the published manifest is valid")
    (doseq [k (sort-by str (conj (set (keys manifest)) :yin.module/index))
            v malformed-values]
      (testing (str k " " (pr-str v))
        (let [bad (assoc manifest k v)
              d (defect-or-throw bad)
              m (jing/materialize! store bad)
              w (try (closure/walk store m)
                     (catch #?(:cljd Object :clj Throwable :cljs :default) e
                       {::threw (str e)}))]
          (is (map? d) (pr-str d))
          (is (not (contains? d ::threw)) (pr-str d))
          (is (= [:invalid m :manifest-defect]
                 [(:yin.link.closure/outcome w) (:address w) (get-in w [:defect :code])])
              (str "the walk is total: " (pr-str w))))))))


(deftest a-malformed-optional-index-is-a-manifest-defect-naming-the-manifest
  (doseq [bad [42 [1] "bad"]]
    (testing (pr-str bad)
      (let [store (mem/create-content-mem)
            res (publish-base! store)
            m (with-manifest store (assoc (:manifest res) :yin.module/index bad))]
        (is (= {:rule :manifest-shape :key :yin.module/index}
               (defect-or-throw (jing/get store m nil))))
        (is (= {:yin.link.closure/outcome :invalid :address m :role :manifest :path [m]
                :defect {:code :manifest-defect
                         :detail {:rule :manifest-shape :key :yin.module/index}}}
               (select-keys (closure/walk store m)
                            [:yin.link.closure/outcome :address :role :path :defect])))))))


(deftest each-bound-is-a-parts-limit
  (let [{:keys [store app]} (world)]
    (doseq [[bounds bound] [[{:max-parts 2} :max-parts]
                            [{:max-depth 1} :max-depth]
                            [{:max-bytes 64} :max-bytes]]]
      (testing (name bound)
        (is (= {:code :parts-limit :detail {:bound bound}}
               (:defect (closure/walk store (:address app) {:bounds bounds}))))))))


(deftest a-forged-row-is-an-address-mismatch-never-a-throw
  (let [[_ forged address] (first (invalid-fixtures))
        w (closure/walk forged address)]
    (is (= :address-mismatch (get-in w [:defect :code])))
    (is (= :row (:role w)))))


;; =============================================================================
;; The publisher walks what it minted (5.2)
;; =============================================================================

(defn- dropping
  "A handle over `store` whose put of `address` answers `:inserted` and
   stores nothing: a backend that loses a write."
  [store address]
  (assoc store :put-bytes-fn
         (fn [a bs]
           (if (= a address) :inserted ((:put-bytes-fn store) a bs)))))


(deftest publish-refuses-unless-the-minted-closure-is-complete
  (let [probe (publish-base! (mem/create-content-mem))
        {:keys [leaf]} (leaf-and-interior
                         (let [s (mem/create-content-mem)] (publish-base! s) s)
                         (get-in probe [:manifest :yin.module/tree]))
        store (mem/create-content-mem)
        res (publish-base! (dropping store leaf))]
    (is (= :refused (:status res)) (pr-str res))
    (is (= :yin.link.publish/incomplete-closure (:reason res)))
    (is (= {:yin.link.closure/outcome :missing :address leaf :role :row
            :path [(:address probe)]}
           (:walk res)))))


(deftest a-requirement-holding-no-valid-manifest-is-refused-by-the-walk
  (let [source (mem/create-content-mem)
        base (publish-base! source)
        forged-manifest (mem/create-content-mem
                          (assoc (entries source) (:address base)
                                 (jing/canonical-bytes
                                   (assoc (:manifest base) :yin.module/name 'other))))
        root (:yin.module/tree (:manifest (publish-app! (mem/create-content-mem
                                                          (entries source))
                                                        (:address base))))]
    (testing "bytes that do not hash to the pinned address"
      (let [before (addresses forged-manifest)
            res (publish-app! forged-manifest (:address base))]
        (is (= {:status :refused :reason :yin.link.publish/invalid-requirement
                :name 'base}
               res))
        (is (= before (addresses forged-manifest)) "nothing was written")
        (is (nil? (jing/get forged-manifest root nil)))))
    (testing "a pinned closure with a blob absent"
      (let [tree (get-in base [:manifest :yin.module/tree])
            partial (without source tree)
            before (addresses partial)
            res (publish-app! partial (:address base))]
        (is (= :yin.link.publish/incomplete-requirement (:reason res)) (pr-str res))
        (is (= 'base (:name res)))
        (is (= {:yin.link.closure/outcome :missing :address tree :role :row
                :path [(:address base)]}
               (:walk res)))
        (is (= before (addresses partial)) "nothing was written")))))
