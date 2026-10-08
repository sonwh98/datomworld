;; L-f section 3.4 measurement, never staged. Run:
;;   clojure -M:test -d collab -n lf-measure
(ns lf-measure (:require [clojure.test :refer [deftest]]))
(require '[yin.repl.link :as link] '[dao.stream :as stream])


(require '[dao.jing :as jing]
         '[dao.jing.mem :as mem]
         '[dao.space.query :as query]
         '[yin.vm :as vm]
         '[yin.vm.linearize :as lin]
         '[yin.vm.debruijn-linearize :as dl]
         '[yin.vm.debruijn-register-compile :as rc]
         '[yin.vm.linker :as linker]
         '[yin.vm.linker.closure :as closure]
         '[yin.vm.linker.publish :as publish])


(defmacro timed
  [label & body]
  `(let [t0# (System/nanoTime)
         r# (do ~@body)
         ms# (/ (- (System/nanoTime) t0#) 1e6)]
     (println (format "%-48s %10.1f ms" ~label ms#))
     r#))


(defn v
  [s]
  {:type :variable :name s})


(defn lit
  [x]
  {:type :literal :value x})


(defn lam
  [ps b]
  {:type :lambda :params ps :body b})


(defn app
  [f & xs]
  {:type :application :operator f :operands (vec xs)})


(defn wide-module
  [n]
  (let [f (fn [i] (symbol (str "f" (mod i n))))
        d (fn [i]
            (app (v 'yin/def) (lit (f i))
                 (lam [] (app (v '+) (v (f (+ i 1)))
                              (app (v '*) (v (f (+ i 2))) (v (f (+ i 3))))))))]
    {:ast (apply app (lam (mapv #(symbol (str "p" %)) (range n)) (lit nil))
                 (mapv d (range n)))
     :exports (set (map f (range n)))}))


(deftest measure
  (def n (Long/parseLong (or (System/getenv "LF_N") "1000")))
  (def m (wide-module n))
  (def tree (vm/ast->semantic-bytecode (:ast m)))
  (println "definitions" n "rows" (count (:rows tree))
           "occurrences" (count (vm/occurrences tree)))

  (def spec
    {:name 'wide :ast (:ast m) :exports (:exports m) :requires {}
     :primitives {'+ (get vm/primitives '+) '* (get vm/primitives '*)}})

  (def store (mem/create-content-mem))
  (def res (timed "publish-module! (total)" (publish/publish-module! store spec)))
  (def address (:address res))
  (timed "closure/walk" (closure/walk store address))
  (doseq [f publish/code-formats]
    (let [r (timed (str "link-local " (:format f))
                   (publish/link-local store address f))]
      (println "   " (:status r) (:reason r) (:name r)
               (when (:obligations r) (set (map :name (:obligations r)))))))

  ;; the scanners and the join over each format's own value
  (def values
    {:yin.ast/code tree
     :yin.semantic/code (:vector (lin/lower-rows tree))
     :yin.debruijn.code (:image (dl/adapt (vm/ast->datoms (:ast m))))
     :yin.debruijn.register (:image (rc/adapt (second (vm/ast->datoms-with-root (:ast m)))))})

  (doseq [f publish/code-formats]
    (let [x (get values (:format f))
          k (:format f)
          o (timed (str "  " k " obligations-fn") ((:obligations-fn f) x))
          d (timed (str "  " k " definitions-fn") ((:definitions-fn f) x))
          a (timed (str "  " k " applications-fn") ((:applications-fn f) x))]
      (timed (str "  " k " undischarged") (#'linker/undischarged o d a))))

  (doseq [policy [:verifying :trusted]]
    (let [source (link/composition {:content-store store :name-env {'wide address}
                                    :derivation policy})
          pair (link/make-pair)
          c0 (:dao.stream/cursor (stream/cursor (:responses pair) stream/anchor-oldest))]
      (stream/append! (:requests pair)
                      {:yin.link/id [:t0 0] :yin.link/name 'wide
                       :yin.link/format :yin.debruijn.code
                       :yin.link/contract vm/stack-contract})
      (let [served (timed (str "yin.repl.link serve " policy)
                          (link/serve {:pair pair :source source}))
            r (stream/next (:responses pair) c0)]
        (println "   pending" (:pending served) "response"
                 (select-keys (:dao.stream/value r) [:status :reason :trust])))))
  (when (System/getenv "LF_ORACLE")
    (timed "Datalog oracle free-name query (the old scanner)"
           (count (query/collect
                    (query/q '[:find ?name ?path :in $ast $occ % ?root
                               :where [$occ ?root ?path ?v] [$ast ?v :variable ?name]
                               (not (occ-bound? ?root ?path ?name))]
                             (query/relation (vals (:rows tree)))
                             (query/relation (vm/occurrences tree))
                             vm/occurrence-rules (:root tree)))))))
