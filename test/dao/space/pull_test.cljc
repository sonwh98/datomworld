(ns dao.space.pull-test
  "Contract tests for dao.space.pull: declarative entity projection.
  Pattern surface: attr names, wildcard, nested maps, reverse refs,
  options (:default, :limit, :as)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.pull :as pull]
            [dao.space.query :as query]))


;; ---------------------------------------------------------------------------
;; Increment 1: Parser
;; ---------------------------------------------------------------------------

(deftest parse-pattern-attrs-test
  (testing "simple attr list"
    (is (= {:attrs [:name :age], :wildcard? false, :nested {}}
           (pull/parse-pattern [:name :age]))))
  (testing "empty pattern"
    (is (= {:attrs [], :wildcard? false, :nested {}} (pull/parse-pattern [])))))


(deftest parse-pattern-wildcard-test
  (testing "wildcard ' [*] "
    (is (= {:attrs [], :wildcard? true, :nested {}}
           (pull/parse-pattern '[*])))))


(deftest parse-pattern-nested-test
  (testing "nested map spec"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (pull/parse-pattern [{:friend [:name]}])))))


(deftest parse-pattern-reverse-test
  (testing "reverse ref :_attr"
    (is (= {:attrs [:_friend], :wildcard? false, :nested {}}
           (pull/parse-pattern [:_friend]))))
  (testing "reverse ref with nested"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:_friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (pull/parse-pattern [{:_friend [:name]}])))))


(deftest parse-pattern-options-test
  (testing "attr with :default"
    (is (= {:attrs [{:attr :age, :default 0}], :wildcard? false, :nested {}}
           (pull/parse-pattern [[:age :default 0]]))))
  (testing "attr with :limit"
    (is (= {:attrs [{:attr :tags, :limit 5}], :wildcard? false, :nested {}}
           (pull/parse-pattern [[:tags :limit 5]]))))
  (testing "attr with :as"
    (is (= {:attrs [{:attr :name, :as :label}], :wildcard? false, :nested {}}
           (pull/parse-pattern [[:name :as :label]])))))


(deftest parse-pattern-malformed-test
  (testing "non-keyword/symbol/map/vector throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (pull/parse-pattern [123]))))
  (testing "vector with non-keyword first element throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (pull/parse-pattern [[123 :default 0]])))))


;; ---------------------------------------------------------------------------
;; Increment 2: Flat pull
;; ---------------------------------------------------------------------------

(def sample-datoms
  [[1 :name "Alice" 1 1] [1 :age 30 1 1] [1 :tag "dev" 1 1] [1 :tag "admin" 2 1]
   [2 :name "Bob" 1 1]])


(deftest pull-flat-attrs-test
  (testing "simple attr list returns scalar for single-valued, vector for multi"
    (let [result (pull/pull sample-datoms 1 [:name :age :tag])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result))))))
  (testing "missing attr is omitted"
    (is (= {:db/id 2, :name "Bob"} (pull/pull sample-datoms 2 [:name :age])))))


(deftest pull-flat-wildcard-test
  (testing "wildcard includes all attrs"
    (let [result (pull/pull sample-datoms 1 '[*])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result)))))))


(deftest pull-flat-options-test
  (testing ":default for missing attr"
    (is (= {:db/id 2, :name "Bob", :age 0}
           (pull/pull sample-datoms 2 [:name [:age :default 0]]))))
  (testing ":limit bounds multi-valued results"
    (let [result (pull/pull sample-datoms 1 [[:tag :limit 1]])]
      (is (= 1 (:db/id result)))
      (is (= 1 (count (:tag result))))
      (is (contains? #{"dev" "admin"} (first (:tag result))))))
  (testing ":limit on single-valued attribute does not force vector wrapping"
    (is (= {:db/id 1, :name "Alice"}
           (pull/pull sample-datoms 1 [[:name :limit 5]]))))
  (testing ":as renames output key"
    (is (= {:db/id 1, :label "Alice"}
           (pull/pull sample-datoms 1 [[:name :as :label]])))))


(deftest pull-flat-nil-for-absent-test
  (testing "entity with no datoms returns nil"
    (is (nil? (pull/pull sample-datoms 999 [:name])))))


;; ---------------------------------------------------------------------------
;; Increment 3: Nested maps (forward navigation)
;; ---------------------------------------------------------------------------

(def nested-datoms
  [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 2 1] [2 :name "Bob" 1 1]
   [2 :friend 3 1 1] [3 :name "Charlie" 1 1]])


(deftest pull-many-test
  (testing "pull-many folds once, maps over eids"
    (is (= [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"} nil]
           (pull/pull-many sample-datoms [1 2 999] [:name]))))
  (testing "pull-many supports nested and reverse specs"
    (let [res (pull/pull-many nested-datoms [1] [:name {:friend [:name]}])]
      (is (= [{:db/id 1,
               :name "Alice",
               :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}]
             (mapv (fn [item] (update item :friend #(sort-by :db/id %))) res))))
    (let [res (pull/pull-many nested-datoms [3] [:name {:_friend [:name]}])]
      (is (= [{:db/id 3,
               :name "Charlie",
               :_friend [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}]}]
             (mapv (fn [item] (update item :_friend #(sort-by :db/id %)))
                   res))))))


(deftest pull-nested-test
  (testing "nested map spec navigates forward refs"
    (is (= {:db/id 1,
            :name "Alice",
            :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}
           (let [result (pull/pull nested-datoms 1 [:name {:friend [:name]}])]
             (update result :friend #(sort-by :db/id %))))))
  (testing "value addressing no datoms is omitted"
    (is (= {:db/id 3, :name "Charlie"}
           (pull/pull nested-datoms 3 [:name {:friend [:name]}]))))
  (testing "nesting depth > 2"
    (let [result (pull/pull nested-datoms
                            1
                            [:name {:friend [:name {:friend [:name]}]}])
          friends (sort-by :db/id (:friend result))]
      (is (= 2 (count friends)))
      (is (= "Bob" (:name (first friends))))
      ;; Bob has one friend (Charlie), so scalar per entity-attrs
      ;; convention
      (is (= {:db/id 3, :name "Charlie"} (:friend (first friends)))))))


;; ---------------------------------------------------------------------------
;; Increment 4: Reverse refs
;; ---------------------------------------------------------------------------

(deftest pull-reverse-test
  (testing "reverse ref :_attr returns vector of entities pointing here"
    (let [result (pull/pull nested-datoms 2 [:name :_friend])]
      (is (= "Bob" (:name result)))
      ;; Entity 1 has friend 2, so :_friend should include entity 1
      (is (= [{:db/id 1}] (mapv #(select-keys % [:db/id]) (:_friend result))))))
  (testing "reverse ref with nested"
    (let [result (pull/pull nested-datoms 3 [:name {:_friend [:name]}])
          friends (sort-by :db/id (:_friend result))]
      ;; Entities 1 and 2 both have friend 3
      (is (= 2 (count friends)))
      (is (= "Alice" (:name (first friends))))
      (is (= "Bob" (:name (second friends))))))
  (testing "reverse ref supports :default option"
    (is (= {:db/id 1, :name "Alice", :_friend []}
           (pull/pull nested-datoms 1 [:name [:_friend :default []]])))))


;; A reverse-ref pull against a raw datom vector only exercises the
;; in-memory index that `fold` builds fresh each call. This test
;; publishes first (JVM-only: persistent-sorted-set durability) so the
;; reverse probe reaches `restored-indexes`' lazily-restored AVET psset
;; instead — the only way to prove the persisted-index path answers
;; `:_attr` correctly, not just the eager in-memory one.
#?(:cljd nil
   :clj (deftest pull-reverse-reaches-the-published-index
          (let [store (jing/create-kv-mem)]
            (jing/cas! store index/default-datoms-key 0 {:datoms nested-datoms})
            (index/publish-index! store)
            (let [result (pull/pull store 3 [:name {:_friend [:name]}])
                  friends (sort-by :db/id (:_friend result))]
              (is (= "Charlie" (:name result)))
              (is (= 2 (count friends)))
              (is (= "Alice" (:name (first friends))))
              (is (= "Bob" (:name (second friends)))))
            (let [flat (pull/pull store 2 [:name :_friend])]
              (is (= "Bob" (:name flat)))
              (is (= [{:db/id 1}]
                     (mapv #(select-keys % [:db/id]) (:_friend flat))))))))


;; ---------------------------------------------------------------------------
;; Increment 6: (pull ?e pattern) as a q find element
;; ---------------------------------------------------------------------------
;;
;; `dao.space.pull` requires `dao.space.query` (for `fold`/`datoms`), so
;; `query.cljc` cannot require `dao.space.pull` back — that would be a
;; require cycle. `q` stays ignorant of pull.cljc's existence and is
;; handed a `:pull-fn` in the query opts instead, the same
;; dependency-injection shape `:fns` already uses for caller-supplied
;; predicates. `:pull-fn` is called as `(pull-fn source eid pattern)`
;; with the *original* `$`-bound source `q` was called with (`pull/pull`
;; folds internally, so this is exactly its signature — no new pull.cljc
;; API needed). Pull find elements bind to `$` only in this pass.


(deftest pull-find-element-basic-test
  (testing "(pull ?e pattern) as a find element projects each row through pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :age 30}] [{:db/id 2, :name "Bob"}]}
             (set (query/q '[:find (pull ?e [:name :age]) :where [?e :name _]]
                           datoms
                           {:pull-fn pull/pull}))))))
  (testing "pull find element composes with a plain var in the same relation"
    (let [datoms [[1 :name "Alice" 1 1]]]
      (is (= #{[1 {:db/id 1, :name "Alice"}]}
             (set (query/q '[:find ?e (pull ?e [:name]) :where [?e :name _]]
                           datoms
                           {:pull-fn pull/pull})))))))


(deftest pull-find-element-nested-and-reverse-test
  (testing
    "pull find element pattern supports nested/reverse specs, same as direct pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :friend {:db/id 2, :name "Bob"}}]}
             (set (query/q '[:find (pull ?e [:name {:friend [:name]}]) :where
                             [?e :name "Alice"]]
                           datoms
                           {:pull-fn pull/pull})))))))


(deftest pull-find-element-scalar-spec-test
  (testing "pull composes with the scalar find spec"
    (is (= {:db/id 1, :name "Alice"}
           (query/q '[:find (pull ?e [:name]) . :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]]
                    {:pull-fn pull/pull})))))


(deftest pull-find-element-coll-spec-test
  (testing "pull composes with the collection find spec"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}}
             (set (query/q '[:find [(pull ?e [:name]) ...] :where [?e :name _]]
                           datoms
                           {:pull-fn pull/pull})))))))


(deftest pull-find-element-tuple-spec-test
  (testing "pull composes with the tuple find spec, alongside a plain var"
    (is (= [1 {:db/id 1, :name "Alice"}]
           (query/q '[:find [?e (pull ?e [:name])] :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]]
                    {:pull-fn pull/pull})))))


(deftest pull-find-element-rejects-aggregate-test
  (testing "an aggregate cannot wrap a pull find element"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Pp]ull"
          (query/q '[:find (count (pull ?e [:name])) :where
                     [?e :name _]]
                   [[1 :name "Alice" 1 1]]
                   {:pull-fn pull/pull})))))


(deftest pull-find-element-missing-pull-fn-test
  (testing "no :pull-fn supplied throws a clear error, mirroring :fns"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #":pull-fn"
          (query/q '[:find (pull ?e [:name]) :where
                     [?e :name _]]
                   [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-with-aggregate-test
  (testing "pull find element composes with aggregates (acts as grouping var)"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 1 1]
                  [2 :name "Bob" 1 1] [2 :friend 3 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice"} 2] [{:db/id 2, :name "Bob"} 1]}
             (set (query/q '[:find (pull ?e [:name]) (count ?friend) :where
                             [?e :name _] [?e :friend ?friend]]
                           datoms
                           {:pull-fn pull/pull})))))))
