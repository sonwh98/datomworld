(ns yin.vm
  (:refer-clojure :exclude [eval])
  (:require [datascript.core :as d]
            #?@(:cljs [[datascript.db :as db] [datascript.query :as dq]])
            [yin.module :as module]))


;; Fix DataScript query under Closure advanced compilation.
;; ClojureScript uses .v for protocol bitmaps on all types. Under :advanced,
;; the Closure Compiler renames Datom's value field from .v to avoid collision,
;; but DataScript's query engine accesses it via raw JS bracket notation
;; datom["v"], which returns the protocol bitmap (an integer) instead of the
;; actual value. Fix: convert Datom tuples to JS arrays using nth (which goes
;; through IIndexed and resolves the renamed field correctly).
#?(:cljs (let [original-lookup dq/lookup-pattern-db
               prop->idx {"e" 0, "a" 1, "v" 2, "tx" 3}]
           (set! dq/lookup-pattern-db
                 (fn [context db pattern]
                   (let [rel (original-lookup context db pattern)
                         tuples (:tuples rel)]
                     (if (and (seq tuples) (instance? db/Datom (first tuples)))
                       (let [new-attrs (reduce-kv (fn [m k v]
                                                    (assoc m
                                                      k (get prop->idx v v)))
                                                  {}
                                                  (:attrs rel))
                             new-tuples (mapv (fn [d]
                                                (to-array [(nth d 0) (nth d 1)
                                                           (nth d 2) (nth d 3)
                                                           (nth d 4)]))
                                          tuples)]
                         (dq/->Relation new-attrs new-tuples))
                       rel))))))


;; Primitive operations
(def primitives
  {'+ (fn [a b] (+ a b)),
   '- (fn [a b] (- a b)),
   '* (fn [a b] (* a b)),
   '/ (fn [a b] (/ a b)),
   '= (fn [a b] (= a b)),
   '== (fn [a b] (= a b)),
   '!= (fn [a b] (not= a b)),
   '< (fn [a b] (< a b)),
   '> (fn [a b] (> a b)),
   '<= (fn [a b] (<= a b)),
   '>= (fn [a b] (>= a b)),
   'and (fn [a b] (and a b)),
   'or (fn [a b] (or a b)),
   'not (fn [a] (not a)),
   'nil? (fn [a] (nil? a)),
   'empty? (fn [a] (empty? a)),
   'first (fn [a] (first a)),
   'rest (fn [a] (vec (rest a))),
   'conj (fn [coll x] (conj coll x)),
   'assoc (fn [m k v] (assoc m k v)),
   'get (fn [m k] (get m k)),
   'vec (fn [coll] (vec coll)),
   ;; Definition primitive - returns an effect
   'yin/def (fn [k v] {:effect :vm/store-put, :key k, :val v})})


(def schema
  "DataScript schema for :yin/ AST datoms.
   Declares ref attributes so DataScript resolves tempids during transaction."
  {:yin/body {:db/valueType :db.type/ref},
   :yin/operator {:db/valueType :db.type/ref},
   :yin/operands {:db/valueType :db.type/ref,
                  :db/cardinality :db.cardinality/many},
   :yin/test {:db/valueType :db.type/ref},
   :yin/consequent {:db/valueType :db.type/ref},
   :yin/alternate {:db/valueType :db.type/ref},
   :yin/source {:db/valueType :db.type/ref},
   :yin/target {:db/valueType :db.type/ref},
   :yin/val {:db/valueType :db.type/ref}})


(def ^:private cardinality-many-attrs
  "Attributes with :db.cardinality/many \u2014 their values are vectors of refs
   that must be expanded into individual :db/add assertions."
  #{:yin/operands})


(defn datoms->tx-data
  "Convert [e a v t m] datoms to DataScript tx-data [:db/add e a v].
   Expands cardinality-many vector values into individual assertions."
  [datoms]
  (mapcat (fn [[e a v _t _m]]
            (if (and (contains? cardinality-many-attrs a) (vector? v))
              (map (fn [ref] [:db/add e a ref]) v)
              [[:db/add e a v]]))
    datoms))


(defn transact!
  "Create a DataScript connection, transact datoms, return {:db db :tempids tempids}.
   Encapsulates DataScript as an implementation detail."
  [datoms]
  (let [tx-data (datoms->tx-data datoms)
        conn (d/create-conn schema)
        {:keys [tempids]} (d/transact! conn tx-data)]
    {:db @conn, :tempids tempids}))


(defn q
  "Run a Datalog query against a DataScript db value."
  [query db]
  (d/q query db))


(defn ast->datoms
  "Convert AST map into lazy-seq of datoms. A datom is [e a v t m].

   Entity IDs are tempids (negative integers: -1024, -1025, -1026...) that get resolved
   to actual entity IDs when transacted. The transactor assigns real positive IDs.

   Options:
     :t - transaction ID (default 0)
     :m - metadata entity reference (default 0, nil metadata)"
  ([ast] (ast->datoms ast {}))
  ([ast opts]
   (let [id-counter (atom -1024)
         t (or (:t opts) 0)
         m (or (:m opts) 0)
         gen-id #(swap! id-counter dec)]
     (letfn
       [(emit [e attr val] [e attr val t m])
        (convert [node]
          (let [e (gen-id)
                {:keys [type]} node]
            (case type
              :literal (lazy-seq (list (emit e :yin/type :literal)
                                       (emit e :yin/value (:value node))))
              :variable (lazy-seq (list (emit e :yin/type :variable)
                                        (emit e :yin/name (:name node))))
              :lambda (let [body-datoms (convert (:body node))
                            body-id (first (first body-datoms))]
                        (lazy-cat (list (emit e :yin/type :lambda)
                                        (emit e :yin/params (:params node))
                                        (emit e :yin/body body-id))
                                  body-datoms))
              :application
                (let [op-datoms (convert (:operator node))
                      op-id (first (first op-datoms))
                      operand-results (map convert (:operands node))
                      operand-ids (map #(first (first %)) operand-results)]
                  (lazy-cat (list (emit e :yin/type :application)
                                  (emit e :yin/operator op-id)
                                  (emit e :yin/operands (vec operand-ids)))
                            op-datoms
                            (apply concat operand-results)))
              :if (let [test-datoms (convert (:test node))
                        test-id (first (first test-datoms))
                        cons-datoms (convert (:consequent node))
                        cons-id (first (first cons-datoms))
                        alt-datoms (convert (:alternate node))
                        alt-id (first (first alt-datoms))]
                    (lazy-cat (list (emit e :yin/type :if)
                                    (emit e :yin/test test-id)
                                    (emit e :yin/consequent cons-id)
                                    (emit e :yin/alternate alt-id))
                              test-datoms
                              cons-datoms
                              alt-datoms))
              ;; VM primitives
              :vm/gensym (lazy-seq
                           (list (emit e :yin/type :vm/gensym)
                                 (emit e :yin/prefix (or (:prefix node) "id"))))
              :vm/store-get (lazy-seq (list (emit e :yin/type :vm/store-get)
                                            (emit e :yin/key (:key node))))
              :vm/store-put (lazy-seq (list (emit e :yin/type :vm/store-put)
                                            (emit e :yin/key (:key node))
                                            (emit e :yin/val (:val node))))
              ;; Stream operations
              :stream/make
                (lazy-seq (list (emit e :yin/type :stream/make)
                                (emit e :yin/buffer (or (:buffer node) 1024))))
              :stream/put (let [target-datoms (convert (:target node))
                                target-id (first (first target-datoms))
                                val-datoms (convert (:val node))
                                val-id (first (first val-datoms))]
                            (lazy-cat (list (emit e :yin/type :stream/put)
                                            (emit e :yin/target target-id)
                                            (emit e :yin/val val-id))
                                      target-datoms
                                      val-datoms))
              :stream/take (let [source-datoms (convert (:source node))
                                 source-id (first (first source-datoms))]
                             (lazy-cat (list (emit e :yin/type :stream/take)
                                             (emit e :yin/source source-id))
                                       source-datoms))
              ;; Default
              (throw (ex-info "Unknown AST node type"
                              {:type type, :node node})))))]
       (convert ast)))))
