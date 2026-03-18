# DaoDB: Native Tuple Store Design

## Context

The project has one DaoDB implementation in `dao.db.cljc`:
- `DaoDbDataScript` — DataScript-backed (4-tuple datoms, external dependency)

It does not support the full `[e a v t m]` 5-tuple spec, EAVT/AEVT/AVET/VAET indexes, or a native Datalog engine.

This design introduces a full native DaoDB: pure Clojure, ClojureScript, and ClojureDart, no DataScript dependency, proper 5-tuple datoms with `m` as an entity ID, four sorted-set indexes, transaction pipeline, schema, and an embedded `d/q`.

Jank support is planned for a future phase. Jank is a Clojure dialect targeting native code via LLVM. Once jank stabilizes its `#?(:jank ...)` reader conditional, the only expected change is a `:jank` branch in `type-rank` for float detection.

Decisions:
- Scope: full native replacement (not extending DaoDbDataScript)
- `m` field: accept map shorthand in tx-data; auto-create metadata entity
- Query engine: included in DaoDB
- **One new namespace**: comparators, index helpers, schema bootstrap, tx pipeline, and query engine are all cohesive. They live together in `dao.db.native` until internal entropy demands extraction.

---

## File Layout

### New files

| File | Namespace | Role |
|------|-----------|------|
| `src/cljc/dao/db/native.cljc` | `dao.db.native` | Everything: comparators, sorted-set index helpers, schema bootstrap, tx pipeline, Datalog query engine, `NativeDaoDb` record, `empty-db`, `create`, `as-of`, `since` |

### Modified files

- `src/cljc/dao/db.cljc` — add `extend-type NativeDaoDb IDaoDb` + `create-native` factory; keep `IDaoDb` protocol, `DaoDbDataScript`, and existing factories unchanged. Remove DataScript require only in Phase 2.

### Kept unchanged

- `src/cljc/datomworld.cljc` — `Datom` record, `->datom`, `type-rank`, `compare-vals` (read-only)
- `src/cljc/yin/vm.cljc` — `yin.vm/schema` map (read-only)

---

## NativeDaoDb Record

```clojure
(defrecord NativeDaoDb
  [eavt           ; sorted-set of Datom by [e a v t m]
   aevt           ; sorted-set of Datom by [a e v t m]
   avet           ; sorted-set of Datom by [a v e t m] — only indexed/ref attrs
   vaet           ; sorted-set of Datom by [v a e t m] — only :db.type/ref attrs
   schema         ; {attr-kw {:db/valueType ... :db/cardinality ...}} — cache
   next-t         ; integer, monotonic tx counter
   next-eid       ; integer, next perm entity ID (starts at 1025)
   ref-attrs      ; set of keywords with :db.type/ref
   card-many      ; set of keywords with :db.cardinality/many
   unique-attrs   ; set of keywords with :db/unique
   indexed-attrs  ; set of keywords with :db/index true
   ])
```

---

## Index Design

### Heterogeneous value comparator

`type-rank` and `compare-vals` live in `datomworld.cljc`. Each platform has a distinct float predicate:

```clojure
;; In datomworld.cljc
(defn type-rank [x]
  (cond (nil? x)     0
        (boolean? x) 1
        (integer? x) 2
        #?(:clj  (float? x)
           :cljs  (and (number? x) (not (integer? x)))
           :cljd  (dart/is? x double)) 3
        (string? x)  4
        (keyword? x) 5
        (symbol? x)  6
        :else         7))

(defn compare-vals [a b]
  (let [ra (type-rank a) rb (type-rank b)]
    (if (= ra rb) (compare a b) (compare ra rb))))
```

Platform notes:
- CLJ: `float?` covers `Float` and `Double`.
- CLJS: all JS numbers are `number?`; integers pass `integer?` (uses `js/Number.isInteger`).
- CLJD: Dart has a concrete `double` type; `dart/is?` is the CLJD type-test macro.

```clojure
(defn make-comparator [positions]
  (fn [d1 d2]
    (loop [[p & ps] positions]
      (if (nil? p) 0
        (let [c (compare-vals (get d1 p) (get d2 p))]
          (if (zero? c) (recur ps) c))))))

(def eavt-cmp (make-comparator [:e :a :v :t :m]))
(def aevt-cmp (make-comparator [:a :e :v :t :m]))
(def avet-cmp (make-comparator [:a :v :e :t :m]))
(def vaet-cmp (make-comparator [:v :a :e :t :m]))
```

In Clojure, the function returned by `make-comparator` is passed directly to `sorted-set-by`. ClojureScript accepts the same plain fn.

### Range seek via `subseq`

Nil-field sentinel datoms as lower bounds (nil has rank 0 = lowest):

```clojure
(defn seek-e [eavt e]
  (take-while #(= e (:e %)) (subseq eavt >= (->Datom e nil nil nil nil))))

(defn seek-ea [eavt e a]
  (if (= a FREE)
    (seek-e eavt e)
    (take-while #(and (= e (:e %)) (= a (:a %)))
                (subseq eavt >= (->Datom e a nil nil nil)))))

(defn seek-av [avet a v]
  (take-while #(and (= a (:a %)) (= v (:v %)))
              (subseq avet >= (->Datom nil a v nil nil))))

(defn seek-a [aevt a]
  (take-while #(= a (:a %)) (subseq aevt >= (->Datom nil a nil nil nil))))
```

`(empty sorted-set-by-cmp)` preserves the comparator, enabling `as-of`/`since` to rebuild filtered indexes with the same ordering.

---

## Transaction Pipeline

```
run-tx [db tx-data]
  1. parse-tx-data      → seq of op-maps {:op :e :a :v :m-raw}
  2. expand-m-maps      → replace map :m-raw with metadata entity; emit extra datoms
  3. collect-tempids    → find all negative e and ref v values
  4. resolve-tempids    → assign perm IDs starting at next-eid
  5. apply-tempid-map   → rewrite e and ref-v in all ops
  6. assign-t           → t = db.next-t for all datoms
  7. enforce-cardinality → :db.cardinality/one: retract prior [e a ?v] from all indexes
  8. update-indexes     → conj Datom into eavt+aevt; conditionally avet (indexed-attrs+ref), vaet (ref-attrs)
  9. rebuild-schema     → if any datom touches :db/ident/:db/valueType/:db/cardinality/:db/unique, rebuild schema cache + derived sets
  10. return            → {:db db', :tempids {tempid → perm-id}}
```

### Accepted tx-data forms

- `[:db/add e a v]` — m defaults to 0
- `[:db/add e a v m]` — m is integer
- `[:db/add e a v {:key val}]` — m is map; auto-creates metadata entity
- `[:db/retract e a v]` — physical delete from all indexes
- `{:db/id e, :attr val, ...}` — map form expands to :db/add ops

### Map m expansion

```clojure
(defn expand-m-map [op next-eid t]
  (if (map? (:m-raw op))
    (let [meta-eid next-eid
          extra (mapv (fn [[k v]] (->Datom meta-eid k v t 0)) (:m-raw op))]
      [(assoc op :m meta-eid) extra (inc next-eid)])
    [(assoc op :m (:m-raw op)) [] next-eid]))
```

---

## Schema (`dao.db.native`)

Schema is stored as datoms in EAVT and cached in `NativeDaoDb.schema` map. Bootstrap datoms (t=0, m=0) pre-populate system entities 2-13 on `empty-db`:

```clojure
(def bootstrap-datoms
  [(->Datom 2  :db/ident :db/ident           0 0)
   (->Datom 3  :db/ident :db/valueType       0 0)
   (->Datom 4  :db/ident :db/cardinality     0 0)
   (->Datom 5  :db/ident :db/unique          0 0)
   (->Datom 6  :db/ident :db/index           0 0)
   (->Datom 7  :db/ident :db.cardinality/one 0 0)
   (->Datom 8  :db/ident :db.cardinality/many 0 0)
   (->Datom 9  :db/ident :db.type/ref        0 0)
   (->Datom 10 :db/ident :db.type/string     0 0)
   (->Datom 11 :db/ident :db.type/long       0 0)
   (->Datom 12 :db/ident :db.type/boolean    0 0)
   (->Datom 13 :db/ident :db.type/keyword    0 0)])
```

`build-schema-cache [eavt]`:
1. Scan EAVT for `:db/ident` datoms — build `eid → kw` map.
2. For each entity that has `:db/valueType`, `:db/cardinality`, `:db/unique`, or `:db/index` datoms, collect those attribute-value pairs.
3. Key the result by ident-kw: `{:yin/operands {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref}, ...}`.
4. Derive `ref-attrs`, `card-many`, `unique-attrs`, `indexed-attrs` as sets from the cache.

The existing `yin.vm/schema` (DataScript-format map `{:yin/type {} :yin/operands {...} ...}`) is installable via `(native/create yin.vm/schema)` which converts each entry to `[:db/add tempid :db/ident attr-kw]` plus attribute datoms.

---

## Datalog Query Engine

### Data model

- **Binding**: `{?sym value}` — a map from logic variables to ground values
- **Relation**: `[binding ...]` — a sequence of bindings (the working set)
- **FREE**: `::free` sentinel — matches any value, never bound

### Core functions

```clojure
;; FREE and _ wildcards match anything without binding
(defn unify [binding sym val]
  (cond
    (= sym FREE) binding
    (= sym '_)   binding
    (not (symbol? sym)) (when (= sym val) binding)
    (contains? binding sym) (when (= (get binding sym) val) binding)
    :else (assoc binding sym val)))

;; Select datoms from the best available index
(defn select-datoms [db e a v]
  (cond
    (not= e FREE)                                    (seek-ea (:eavt db) e a)
    (and (not= a FREE) (not= v FREE)
         (contains? (:indexed-attrs db) a))          (seek-av (:avet db) a v)
    (not= a FREE)                                    (seek-a (:aevt db) a)
    :else                                            (seq (:eavt db))))

;; Evaluate one where clause against db, extending each binding in the relation
(defn eval-clause [db clause binding]
  (let [[ce ca cv ct cm] (pad-to-5 clause)
        e-val (resolve-binding binding ce)
        a-val (resolve-binding binding ca)
        v-val (resolve-binding binding cv)
        datoms (select-datoms db e-val a-val v-val)]
    (keep (fn [d]
            (-> binding
                (unify ce (:e d))
                (and-then #(unify % ca (:a d)))
                (and-then #(unify % cv (:v d)))
                (and-then #(unify % ct (:t d)))
                (and-then #(unify % cm (:m d)))))
          datoms)))

;; Entry point
(defn q [query & inputs]
  (let [{:keys [find in where]} (normalize-query query)
        db (first inputs)
        param-pairs (filter (fn [[s _]] (not= '$ s))
                            (map vector (or in ['$]) inputs))
        init-bindings (reduce (fn [bs [sym val]] (mapv #(assoc % sym val) bs))
                              [{}] param-pairs)
        result (eval-where db where init-bindings)]
    (into #{} (map (fn [b] (mapv #(get b %) find)) result))))
```

`:in` bindings: `$` is always the first input (the db); other in-symbols bind scalars as constants injected into every initial binding.

---

## `IDaoDb` Implementation (`dao.db.native`)

`NativeDaoDb` does not require `dao.db` to avoid a circular dependency (`dao.db.cljc` requires `dao.db.native` for the factory). Protocol extension is done via `extend-type` in `dao.db.cljc` after both namespaces are loaded.

```clojure
;; In dao.db.native — plain functions, no protocol reference
(defn native-transact [db tx-data]    (run-tx db tx-data))
(defn native-q [db query inputs]      (apply q query db inputs))
(defn native-datoms [db index]        (seq (get db (keyword (name index)))))
(defn native-entity-attrs [db eid]    ...)
(defn native-find-eids-by-av [db a v] ...)

;; In dao.db.cljc — import class on CLJ only; extend-type uses platform-specific symbol
#?(:clj (:import [dao.db.native NativeDaoDb]))

;; CLJ: NativeDaoDb resolves to the imported Java class.
;; CLJS/CLJD: native/NativeDaoDb resolves via the require alias (no class import needed).
(extend-type #?(:clj NativeDaoDb :cljs native/NativeDaoDb :cljd native/NativeDaoDb)
  IDaoDb
  (transact [db tx-data]    (native/native-transact db tx-data))
  (q [db query inputs]      (native/native-q db query inputs))
  ...)
```

`find-eids-by-av`: uses AVET when `a ∈ indexed-attrs` (O(log n)), else linear scan of AEVT.

---

## History (`as-of` / `since`)

Plain functions, not on the protocol — avoids breaking other `IDaoDb` implementors:

```clojure
(defn as-of [db t]
  (let [keep? #(<= (:t %) t)
        f     #(into (empty %) (filter keep? %))]
    (-> db
        (update :eavt f) (update :aevt f)
        (update :avet f) (update :vaet f))))

(defn since [db t]
  (let [keep? #(> (:t %) t)
        f     #(into (empty %) (filter keep? %))]
    (-> db
        (update :eavt f) (update :aevt f)
        (update :avet f) (update :vaet f))))
```

`(empty sorted-set)` preserves the comparator in both Clj and Cljs, so the filtered copy is still a properly ordered sorted-set.

---

## Circular Dependency Resolution

`dao.db.cljc` requires `dao.db.native` (for the `create-native` factory).
`dao.db.native` must NOT require `dao.db` (would create a cycle).

Solution: define all protocol-dispatching logic as plain public functions in `dao.db.native`, then call `extend-type` in `dao.db.cljc`:

```
datomworld           (Datom, type-rank, compare-vals)
dao.db.native   ←  datomworld   (indexes, schema, tx, query, NativeDaoDb record)
dao.db          ←  dao.db.native  (extend-type here; keeps datascript in Phase 1)
```

---

## ClojureDart Platform Notes

`dao.db.native` is designed to compile on all three platforms without CLJD-specific guards in the implementation itself. The only platform-specific points are in the two call sites that touch the host type system:

### `type-rank` (in `datomworld.cljc`)

Float detection requires a platform branch. Add `:cljd (dart/is? x double)` alongside the existing `:clj`/`:cljs` branches. All other `cond` arms (`nil?`, `boolean?`, `integer?`, `string?`, `keyword?`, `symbol?`) work unchanged on Dart.

### `extend-type` (in `dao.db.cljc`)

CLJ resolves the record as a Java class via `(:import [dao.db.native NativeDaoDb])`. CLJS and CLJD both use the namespace-qualified symbol `native/NativeDaoDb` — no import needed:

```clojure
(extend-type #?(:clj NativeDaoDb :cljs native/NativeDaoDb :cljd native/NativeDaoDb)
  IDaoDb ...)
```

### Core primitives

`sorted-set-by`, `subseq`, `compare`, and persistent data structures (`{}`, `[]`, `#{}`) are all available in ClojureDart. No guards are needed in the comparators, seek helpers, tx pipeline, or query engine.

### Object arrays

`dao.db.native` does not use object arrays. If the semantic VM's array-backed node index (`make-semantic-object-array`) is ever ported to run inside ClojureDart, it must follow the existing pattern from `semantic.cljc`:

```clojure
#?(:clj  (object-array n)
   :cljs  (js/Array. n)
   :cljd  (object-array (int n)))
```

### sha256 (in `datomworld.cljc`)

`sha256` currently has `:clj` and `:cljs` branches only. For full CLJD support, a `:cljd` branch using the Dart `crypto` package would be needed — this is out of scope for the native DaoDB but should be added when content-addressing features are used on Dart.

---

## Migration Path

**Phase 1 (this design):** Add `create-native` to `dao.db.cljc`. All new tests use `NativeDaoDb`. Existing callers of `dao.db/create` still get DataScript.

**Phase 2:** Replace `dao.db/create` to delegate to `NativeDaoDb`. Remove DataScript require.

**Phase 3:** Delete `DaoDbDataScript` record after all tests pass.

---

## Tests

One test file: `test/dao/db/native_test.cljc`.

- **Tx pipeline**: 4-tuple, 5-tuple, map tx-data forms; map-m → metadata entity; tempid resolution; cardinality-one replaces; cardinality-many accumulates; `:db/retract` removes from all four indexes
- **Query engine**: simple pattern, two-variable join, multi-clause join, `:in $ ?x` scalar binding, empty result, `_` wildcard
- **Record**: `empty-db` has bootstrap schema entities; schema install via `yin.vm/schema`; `entity-attrs` including card-many vectors; `find-eids-by-av` using AEVT scan; `datoms` sort order; `as-of` / `since` correctness
- **Integration**: load `yin.vm/schema`, transact AST datoms for `(+ 1 2)`, run SemanticVM to completion

---

## Critical Files

- `src/cljc/dao/db/native.cljc` — **new**: all implementation
- `src/cljc/dao/db.cljc` — **modified**: `extend-type NativeDaoDb IDaoDb` + `create-native`
- `src/cljc/datomworld.cljc` — **modified**: added `type-rank`, `compare-vals`
- `src/cljc/yin/vm.cljc` — `yin.vm/schema` map — read-only
- `test/dao/db/native_test.cljc` — **new**: all tests
