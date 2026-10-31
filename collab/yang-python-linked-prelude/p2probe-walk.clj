;; C4 slice P2, Step 0 probe (Architect-authored, not yet run: the Architect's
;; session could not execute a JVM). Run from the repository root:
;;
;;   clojure -M collab/p2probe.clj
;;
;; It publishes the current prelude as the linker module `py` under three
;; layouts and prints, per code format, the link outcome and the retained
;; obligations. The spec's section 3 step 0 says what each outcome means.
(require '[yang.python.antlr.prelude :as prelude]
         '[yang.python.antlr.uast :as u]
         '[yin.vm.linker.publish :as publish]
         '[dao.jing.mem :as mem]
         '[yin.vm :as vm]
         '[yin.vm.module :as module]
         '[yin.vm.data :as data]
         '[yin.vm.integer :as integer]
         '[clojure.walk :as walk])


(def reg
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      (prelude/register-integer-module {::integer/max-bits 100000 ::integer/max-digits 4300})))


(defn host-profile
  [sym]
  (let [entry (module/module-entry (:modules reg) (symbol (namespace sym)))]
    (get-in entry [:manifest :yin.module/primitives (symbol (name sym))])))


(println "cell/new profile:" (host-profile 'cell/new))
(println "integer/add profile:" (host-profile 'integer/add))


(let [reg2 (-> (module/empty-registry)
               (prelude/register-integer-module {::integer/max-bits 60 ::integer/max-digits 5}))]
  (println "integer/add profile under small limits (expected equal: limits are not in the profile today):"
           (get-in (module/module-entry (:modules reg2) 'integer) [:manifest :yin.module/primitives 'add])))


(defn free-names
  [ast]
  (letfn [(walk
            [n bound]
            (case (:type n)
              :variable (if (contains? bound (:name n)) #{} #{(:name n)})
              :lambda (walk (:body n) (into bound (:params n)))
              :application (reduce into (walk (:operator n) bound) (map #(walk % bound) (:operands n)))
              :if (into (walk (:test n) bound) (into (walk (:consequent n) bound) (walk (:alternate n) bound)))
              #{}))]
    (walk ast #{})))


(defn def-keys
  [ast]
  (set (keep (fn [n]
               (when (and (= :application (:type n)) (= 'yin/def (:name (:operator n))))
                 (:value (first (:operands n)))))
             (tree-seq map? (fn [n] (filter map? (mapcat #(if (vector? %) % [%]) (vals n)))) ast))))


;; D4: the two keys whose bare name is a primitive name
(def renames '{py/conj py/vconj, py/not py/lnot})


(defn strip
  [sym]
  (let [sym (get renames sym sym)]
    (if (and (symbol? sym) (= "py" (namespace sym))) (symbol (name sym)) sym)))


(defn strip-tree
  [ast]
  (walk/postwalk (fn [x]
                   (cond (and (map? x) (= :variable (:type x))) (update x :name strip)
                         (and (map? x) (= :literal (:type x)) (symbol? (:value x))) (update x :value strip)
                         :else x))
                 ast))


(def all-keys (def-keys prelude/functions-uast))
(def top-keys (set (map first prelude/definitions)))
(def inner-keys (sort-by str (remove top-keys all-keys)))
(println "module-level keys:" (count top-keys) " runtime keys defined inside py/init!:" (count inner-keys))

(def stripped-top (set (map strip top-keys)))


(println "stripped keys colliding with primitives (must be empty after D4):"
         (vec (filter #(contains? vm/primitives %) stripped-top)))


(def free (remove (conj all-keys 'yin/def) (free-names prelude/functions-uast)))
(def host (filter #(contains? prelude/host-names %) free))
(def prims (filter #(contains? vm/primitives %) free))


(println "free host names:" (count host) " free primitives:" (vec (sort prims))
         " other:" (vec (remove (set (concat host prims)) free)))


(println "host-names never read:" (vec (remove (set host) prelude/host-names)))


(def primitives
  (into {}
        (concat (map (fn [s] [s {:yin.k/profile (host-profile s) :yin.k/effects #{}}]) host)
                (map (fn [s] [s (vm/profile-of vm/primitives s)]) prims))))


;; the stripped definition list, placeholders first
(def defs
  (concat (map (fn [k] [k :py/uninit]) inner-keys)
          (map (fn [[k form]] [(strip k) form]) prelude/definitions)))


(defn def-node
  [[k form]]
  (u/def! k (strip-tree (u/sexp->uast form))))


(def chain-layout
  "Today's layout: `then`-sequenced definitions."
  (u/seq-nodes (map def-node defs)))


(def wide-layout
  "D5: one application whose operands are the definitions."
  (let [nodes (map def-node defs)
        params (mapv (fn [i] (symbol (str "%d" i))) (range (count nodes)))]
    (apply u/app (u/lam params (u/lit nil)) nodes)))


(def exports (set (map strip top-keys)))


(defn try-publish
  [label ast]
  (let [store (mem/create-content-mem)
        t0 (System/currentTimeMillis)
        res (publish/publish-module! store {:name 'py :ast ast :exports exports
                                            :requires {} :primitives primitives})]
    (println "----" label (- (System/currentTimeMillis) t0) "ms")
    (if (= :refused (:status res))
      (println "  REFUSED" (dissoc res :walk) "\n  WALK"
               (let [w (:walk res)]
                 (pr-str (select-keys w [:yin.link.closure/outcome :role])
                         (some-> (:defect w) (select-keys [:code]))
                         (some-> (:defect w) :detail :bound))))
      (do (println "  manifest address" (:address res))
          (doseq [[f o] (:links res)]
            (println "  " f (:status o)
                     (if (= :refused (:status o))
                       (select-keys o [:reason :name :kind])
                       {:retained (vec (sort-by str (map :name (:obligations o))))})))))
    res))


(println "rows: chain" (count (:rows (vm/ast->semantic-bytecode chain-layout)))
         " wide" (count (:rows (vm/ast->semantic-bytecode wide-layout)))
         " default-bounds" (deref (resolve 'yin.vm.linker/default-bounds)))


(def wide nil)
(println "==== DIAGNOSTIC ONLY: default-bounds :max-parts raised by with-redefs")
(require 'yin.vm.linker.closure)
(def orig-walk @(resolve 'yin.vm.linker.closure/walk))
(def orig-link-local @(resolve 'yin.vm.linker.publish/link-local))


(defn timed
  [label f]
  (fn [& args]
    (let [t0 (System/currentTimeMillis)
          r (apply f args)]
      (println "   [timing]" label (- (System/currentTimeMillis) t0) "ms") (flush)
      r)))


(with-redefs [yin.vm.linker/default-bounds
              (assoc (deref (resolve 'yin.vm.linker/default-bounds)) :max-parts 1000000)
              yin.vm.linker.closure/walk (timed "closure/walk" orig-walk)
              yin.vm.linker.publish/link-local
              (fn [h a f & more]
                (apply (timed (str "link-local " (:format f)) orig-link-local) h a f more))]
  (let [w (try-publish "wide layout, raised bound" wide-layout)]
    (when (map? (:manifest w))
      (println "exports:" (count (:yin.module/exports (:manifest w)))
               " footprint effects:" (:effects (:yin.module/footprint (:manifest w)))))))


(when (map? (:manifest wide))
  (println "exports:" (count (:yin.module/exports (:manifest wide)))
           " footprint:" (:yin.module/footprint (:manifest wide))))


(shutdown-agents)
