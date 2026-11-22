(ns yang.clojure.core-impl
  "Stateless guest operations for the portable Clojure standard library."
  (:require [clojure.walk :as walk]
            [yang.clojure.prelude :as clj]
            [yang.core.call :as call]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as admission]
            [yang.core.uast :as u]
            [yang.rt.int :as int-rt]))


(def module-name 'clojure.core.impl)


(def ^:private helpers
  '[[impl-cell-ref?
     (fn [x]
       (if (data/map? x)
         (if (= (get x :type) :cell-ref) (cell/authentic? x) false)
         false))]
    [impl-map? (fn [x] (if (data/map? x) (not (impl-cell-ref? x)) false))]
    [impl-coll? (fn [x] (if (data/map? x) (not (impl-cell-ref? x)) (kind/coll? x)))]
    [impl-sequential?
     (fn [x] (if (data/map? x) false (if (kind/coll? x) (not (kind/set? x)) false)))]
    [impl-seq? (fn [x] (if (impl-sequential? x) (not (data/vector? x)) false))]
    [impl-error
     (fn [message data]
       (clj/raise (assoc {} :yin.k/message message :yin.k/data data)))]
    [impl-int-result
     (fn [value] (yrt.int/check-limit (clj/rt-ctx) value))]
    [impl-lookup-view
     (fn [x]
       (if (if (data/map? x) (= :cell-ref (get x :type)) false)
         (clj/condition-view x)
         x))]
    [impl-get-default
     (fn [coll k default]
       (clj/prim-get-default coll (if (data/vector? coll) k (clj/collection-key k)) default))]
    [impl-assoc-one
     (fn [coll k v]
       (assoc coll (if (data/vector? coll) k (clj/collection-key k)) v))]
    [impl-get-path
     (fn [coll path i n default]
       (if (< i n)
         (let [k (data/nth path i)
               indexed? (data/vector? coll)
               map? (impl-map? coll)
               key (if map? (clj/collection-key k) k)]
           (if (if indexed? (data/contains? coll key)
                 (if map? (data/contains? coll key) false))
             (impl-get-path (clj/prim-get coll key) path (+ i 1) n default)
             default))
         coll))]
    [impl-assoc-path
     (fn [coll path i n value]
       (let [k (if (< i n) (data/nth path i) nil)]
         (impl-assoc-one coll k
           (if (< (+ i 1) n)
             (impl-assoc-path (impl-get-default coll k nil) path (+ i 1) n value)
             value))))]
    [impl-assoc-more
     (fn [coll args i n]
       (if (< i n)
         (impl-assoc-more (impl-assoc-one coll (data/nth args i) (data/nth args (+ i 1)))
                          args (+ i 2) n)
         coll))]
    [impl-vec
     (fn [xs]
       (if (data/vector? xs) xs
         (if (data/map? xs)
           (if (impl-cell-ref? xs)
             (impl-error "Cannot traverse a cell reference" {})
             (data/canonical-entries xs))
           (if (kind/set? xs) (data/canonical-entries xs) (vec xs)))))]
    [impl-first
     (fn [xs]
       (if (if (data/map? xs) true (kind/set? xs))
         (first (impl-vec xs))
         (first xs)))]
    [impl-prepend-reversed
     (fn [xs i acc]
       (if (< i 0)
         acc
         (impl-prepend-reversed xs (- i 1) (conj acc (data/nth xs i)))))]
    [impl-list-from (fn [xs] (impl-prepend-reversed xs (- (data/count xs) 1) (quote ())))]
    [impl-rest (fn [xs] (impl-list-from (rest (impl-vec xs))))]
    [impl-reverse-at
     (fn [xs i n acc]
       (if (< i n)
         (impl-reverse-at xs (+ i 1) n (conj acc (data/nth xs i)))
         acc))]
    [impl-reverse
     (fn [xs] (let [v (impl-vec xs)] (impl-reverse-at v 0 (data/count v) (quote ()))))]
    [impl-seq
     (fn [xs]
       (if (nil? xs) nil
         (if (impl-coll? xs)
           (if (empty? xs) nil (if (impl-seq? xs) xs (impl-list-from (impl-vec xs))))
           (impl-error "seq of a non-collection is not supported" (assoc {} :value xs)))))]
    [impl-nth (fn [xs i] (if (nil? xs) nil (data/nth (impl-vec xs) i)))]
    [impl-contains?
     (fn [xs k] (data/contains? xs (if (data/vector? xs) k (clj/collection-key k))))]
    [impl-into-at
     (fn [to xs i n]
       (if (< i n)
         (impl-into-at (impl-conj-one to (data/nth xs i)) xs (+ i 1) n)
         to))]
    [impl-into
     (fn [to from] (let [xs (impl-vec from)] (impl-into-at to xs 0 (data/count xs))))]
    [impl-conj-one
     (fn [to x]
       (if (kind/set? to)
         (conj to (clj/collection-key x))
         (if (impl-map? to)
           (if (impl-map? x)
             (impl-into to x)
             (if (if (data/vector? x) (= 2 (data/count x)) false)
               (impl-assoc-one to (data/nth x 0) (data/nth x 1))
               (if (impl-seq? x)
                 (impl-into to x)
                 (impl-error "Map conj requires an entry or a collection of entries" {}))))
           (if (impl-cell-ref? to)
             (impl-error "Cannot conjoin onto a cell reference" {})
             (conj to x)))))]
    [impl-conj-more
     (fn [to args i n]
       (if (< i n) (impl-conj-more (impl-conj-one to (data/nth args i)) args (+ i 1) n) to))]
    [impl-disj (fn [s x] (data/disj s (clj/collection-key x)))]
    [impl-difference-at
     (fn [s xs i n]
       (if (< i n) (impl-difference-at (impl-disj s (data/nth xs i)) xs (+ i 1) n) s))]
    [impl-named
     (fn [x]
       (let [ns (kind/namespace x)]
         (if (nil? ns) (kind/name x) (data/str-concat ns "/" (kind/name x)))))]
    [impl-render-string-at
     (fn [cs i n out]
       (if (< i n)
         (let [c (data/code-points->str (conj [] (data/nth cs i)))
               escaped (if (= c "\\") "\\\\"
                         (if (= c "\"") "\\\""
                           (if (= c "\n") "\\n"
                             (if (= c "\r") "\\r"
                               (if (= c "\t") "\\t"
                                 (if (= c "\b") "\\b" (if (= c "\f") "\\f" c)))))))]
           (impl-render-string-at cs (+ i 1) n (conj out escaped)))
         (data/str-join "" (conj out "\""))))]
    [impl-render-at
     (fn [xs i n out map-entries?]
       (if (< i n)
         (let [x (data/nth xs i)
               shown (if map-entries?
                       (data/str-concat (impl-render (data/nth x 0) true) " "
                                        (impl-render (data/nth x 1) true))
                       (impl-render x true))]
           (impl-render-at xs (+ i 1) n
             (data/str-concat out (if (= i 0) "" (if map-entries? ", " " ")) shown)
             map-entries?))
         out))]
    [impl-render-coll
     (fn [xs open close map-entries?]
       (let [v (impl-vec xs)]
         (data/str-concat (impl-render-at v 0 (data/count v) open map-entries?) close)))]
    [impl-render
     (fn [x nested?]
       (if (nil? x) (if nested? "nil" "")
         (if (integer/integer? x) (impl-int-result (integer/format x))
           (if (data/float64? x) (float/format x)
             (if (kind/keyword? x) (data/str-concat ":" (impl-named x))
               (if (kind/symbol? x) (impl-named x)
                 (if (impl-cell-ref? x) "#<fn>"
                   (if (data/string? x)
                     (if nested?
                       (let [cs (data/str->code-points x)]
                         (impl-render-string-at cs 0 (data/count cs) ["\""]))
                       x)
                     (if (data/vector? x) (impl-render-coll x "[" "]" false)
                       (if (data/map? x) (impl-render-coll x "{" "}" true)
                         (if (kind/set? x) (impl-render-coll x "#{" "}" false)
                           (if (impl-seq? x) (impl-render-coll x "(" ")" false)
                             (if (= x true) "true"
                               (if (= x false) "false" "#<value>"))))))))))))))]
    [impl-str-at
     (fn [xs i n out]
       (if (< i n)
         (impl-str-at xs (+ i 1) n (data/str-concat out (impl-render (data/nth xs i) false)))
         out))]
    [impl-reduced?
     (fn [x] (if (impl-map? x) (= true (get x :yin.k/reduced)) false))]
    [impl-reduce-at
     (fn [f acc xs i n]
       (if (< i n)
         (let [r (clj/invoke-callable f (conj [] acc (data/nth xs i)) [])]
           (if (impl-reduced? r) (get r :yin.k/value)
             (impl-reduce-at f r xs (+ i 1) n)))
         acc))]
    [impl-map-at
     (fn [f xs ys i n acc]
       (if (< i n)
         (let [x (data/nth xs i)
               args (if (nil? ys) (conj [] x) (conj [] x (data/nth ys i)))]
           (impl-map-at f xs ys (+ i 1) n (conj acc (clj/invoke-callable f args []))))
         acc))]
    [impl-map-one
     (fn [f coll] (let [xs (impl-vec coll)] (impl-map-at f xs nil 0 (data/count xs) [])))]
    [impl-map-two
     (fn [f a b]
       (let [xs (impl-vec a) ys (impl-vec b)
             nx (data/count xs) ny (data/count ys)]
         (impl-map-at f xs ys 0 (if (< nx ny) nx ny) [])))]
    [impl-filter-at
     (fn [f xs keep? i n acc]
       (if (< i n)
         (let [x (data/nth xs i)
               result (clj/invoke-callable f (conj [] x) [])]
           (impl-filter-at f xs keep? (+ i 1) n
             (if (if result keep? (not keep?)) (conj acc x) acc)))
         (impl-list-from acc)))]
    [impl-append
     (fn [to from] (data/into to (impl-vec from)))]
    [impl-select-at
     (fn [xs slot i acc]
       (if (< i 0) acc
         (impl-select-at xs slot (- i 1) (conj acc (data/nth (data/nth xs i) slot)))))]
    [impl-merge-two
     (fn [a b]
       (if (nil? b) a
         (if (nil? a) (kind/with-meta (impl-into {} b) (kind/meta b))
           (impl-into a b))))]
    [impl-fold-at
     (fn [f args i n acc]
       (if (< i n)
         (impl-fold-at f args (+ i 1) n (f acc (data/nth args i)))
         acc))]
    [impl-comp-two
     (fn [f g]
       (clj/make-callable {:clj/type :function}
         (fn [args tail]
           (let [n (data/count args)]
             (if (if (<= 1 n) (<= n 3) false)
               (clj/invoke-callable f (conj [] (clj/invoke-callable g args [])) [])
               (clj/raise (assoc {:yin.vm.data/reason :arity :yin.k/kind :anonymous} :yin.k/argc n)))))))]
    [impl-partial
     (fn [f prefix]
       (clj/make-callable {:clj/type :function}
         (fn [args tail]
           (let [n (data/count args)]
             (if (<= n 3)
               (clj/invoke-callable f (data/into prefix args) [])
               (clj/raise (assoc {:yin.vm.data/reason :arity :yin.k/kind :anonymous} :yin.k/argc n)))))))]])


(def ^:private operations
  "Each source name followed by its fixed arities and their guest bodies."
  '[[identity ([x] x)]
    [inc ([x] (if (data/float64? x)
                (float/add x (integer/to-float-boxed 1))
                (impl-int-result (integer/add x 1))))]
    [dec ([x] (if (data/float64? x)
                (float/sub x (integer/to-float-boxed 1))
                (impl-int-result (integer/sub x 1))))]
    [rem ([a b]
          (if (if (integer/integer? a) (integer/integer? b) false)
            (if (= 0 (integer/compare b 0))
              (clj/perform-policy :math/div-zero (assoc {} :a a))
              (data/nth (impl-int-result (integer/quot-rem a b)) 1))
            (clj/perform-policy :math/rem-type (assoc {} :a a :b b))))]
    [int? ([x] (integer/integer? x))]
    [not= ([a b] (not (data/equiv a b)))]
    [boolean? ([x] (if (= x true) true (= x false)))]
    [fn? ([x] (clj/admitted-callable? x))]
    [count ([x] (data/count x))]
    [contains? ([xs k] (impl-contains? xs k))]
    [get ([xs k] (impl-get-default (impl-lookup-view xs) k nil))
         ([xs k default] (impl-get-default (impl-lookup-view xs) k default))]
    [update ([m k f] (impl-assoc-one m k (clj/invoke-callable f (conj [] (impl-get-default m k nil)) [])))
            ([m k f a] (impl-assoc-one m k (clj/invoke-callable f (conj [] (impl-get-default m k nil) a) [])))
            ([m k f a b] (impl-assoc-one m k (clj/invoke-callable f (conj [] (impl-get-default m k nil) a b) [])))
            ([m k f a b c] (impl-assoc-one m k (clj/invoke-callable f (conj [] (impl-get-default m k nil) a b c) [])))]
    [get-in ([xs ks] (let [path (impl-vec ks)] (impl-get-path xs path 0 (data/count path) nil)))
            ([xs ks default]
             (let [path (impl-vec ks)] (impl-get-path xs path 0 (data/count path) default)))]
    [assoc-in ([xs ks value]
               (let [path (impl-vec ks)] (impl-assoc-path xs path 0 (data/count path) value)))]
    [nil? ([x] (nil? x))]
    [empty? ([x] (if (impl-cell-ref? x) false (empty? x)))]
    [symbol? ([x] (kind/symbol? x))]
    [keyword? ([x] (kind/keyword? x))]
    [meta ([x] (kind/meta x))]
    [name ([x] (kind/name x))]
    [namespace ([x] (kind/namespace x))]
    [symbol ([n] (kind/symbol n)) ([ns n] (kind/symbol ns n))]
    [keyword ([n] (kind/keyword n)) ([ns n] (kind/keyword ns n))]
    [fresh-symbol ([s prefix]
                   (let [n (get s :counter)]
                     (conj [] (kind/symbol (data/str-concat "G__" prefix (impl-render n false)))
                           (assoc s :counter (impl-int-result (integer/add n 1))))))]
    [gensym ([n]
             (if (integer/integer? n)
               (kind/symbol (data/str-concat "G__" (impl-render n false)))
               (impl-error "gensym requires an explicit fresh-name supply"
                           (assoc {} :prefix n :required (quote fresh-symbol)))))
            ([prefix n]
             (if (impl-map? prefix)
               (let [i (get prefix :counter)]
                 (conj [] (kind/symbol (data/str-concat "G__" n (impl-render i false)))
                       (assoc prefix :counter (impl-int-result (integer/add i 1)))))
               (kind/symbol (data/str-concat prefix (impl-render n false)))))]
    [last ([xs] (data/peek (impl-vec xs)))]
    [butlast ([xs]
              (let [v (impl-vec xs) n (data/count v)]
                (if (< 1 n) (impl-list-from (data/subvec v 0 (- n 1))) nil)))]
    [nth ([xs n] (impl-nth xs n))
         ([xs n missing]
          (let [v (impl-vec xs)]
            (if (data/contains? v n) (data/nth v n) missing)))]
    [string? ([x] (data/string? x))]
    [with-meta ([x m] (kind/with-meta x m))]
    [coll? ([x] (impl-coll? x))]
    [map? ([x] (impl-map? x))]
    [vector? ([x] (data/vector? x))]
    [set? ([x] (kind/set? x))]
    [sequential? ([x] (impl-sequential? x))]
    [seq? ([x] (impl-seq? x))]
    [list? ([x] (impl-seq? x))]
    [first ([x] (impl-first x))]
    [second ([x] (impl-first (impl-rest x)))]
    [rest ([x] (impl-rest x))]
    [vec ([x] (impl-vec x))]
    [seq ([x] (impl-seq x))]
    [reverse ([x] (impl-reverse x))]
    [cons ([x xs] (conj (impl-list-from (impl-vec xs)) x))]
    [reduced ([x] (assoc {} :yin.k/reduced true :yin.k/value x))]
    [reduced? ([x] (impl-reduced? x))]
    [unreduced ([x] (if (impl-reduced? x) (get x :yin.k/value) x))]
    [reduce ([f coll]
             (let [xs (impl-vec coll) n (data/count xs)]
               (if (= n 0) (clj/invoke-callable f [] [])
                 (impl-reduce-at f (data/nth xs 0) xs 1 n))))
            ([f init coll]
             (let [xs (impl-vec coll)] (impl-reduce-at f init xs 0 (data/count xs))))]
    [map ([f xs] (impl-list-from (impl-map-one f xs)))
         ([f a b] (impl-list-from (impl-map-two f a b)))]
    [mapv ([f xs] (impl-map-one f xs)) ([f a b] (impl-map-two f a b))]
    [filter ([f xs] (let [v (impl-vec xs)] (impl-filter-at f v true 0 (data/count v) [])))]
    [remove ([f xs] (let [v (impl-vec xs)] (impl-filter-at f v false 0 (data/count v) [])))]
    [into ([to from] (impl-into to from))]
    [keys ([m] (let [v (impl-vec m)] (impl-select-at v 0 (- (data/count v) 1) (quote ()))))]
    [vals ([m] (let [v (impl-vec m)] (impl-select-at v 1 (- (data/count v) 1) (quote ()))))]
    [disj ([s x] (impl-disj s x))]
    [dissoc ([m k] (data/dissoc m (clj/collection-key k)))]
    [difference ([a b]
                 (let [xs (impl-vec b)] (impl-difference-at a xs 0 (data/count xs))))]
    [partial ([f a] (impl-partial f (conj [] a)))
             ([f a b] (impl-partial f (conj [] a b)))
             ([f a b c] (impl-partial f (conj [] a b c)))]])


(defn- arity-error [name]
  (list 'clj/raise
        (list 'assoc {:yin.vm.data/reason :arity} :yin.k/argc 'n
              :yin.k/fn (list 'quote name))))


(defn- operation-body [[name & arities]]
  [name
   (list 'fn '[args]
         (list 'let '[n (data/count args)]
               (reduce (fn [otherwise [params body]]
                         (list 'if (list '= 'n (count params))
                               (list 'let
                                     (into [] (mapcat (fn [p i] [p (list 'data/nth 'args i)])
                                                       params (range)))
                                     body)
                               otherwise))
                       (arity-error name) (reverse arities))))])


(defn- bounded-body [name max-arity form]
  [name (list 'fn '[args] (list 'let '[n (data/count args)]
                               (list 'if (list '<= 'n max-arity) form (arity-error name))))])


(def ^:private entries
  (into (mapv operation-body operations)
        [(bounded-body 'str 6 '(impl-str-at args 0 n ""))
         (bounded-body 'list 6 '(impl-list-from args))
         (bounded-body 'vector 6 'args)
         (bounded-body 'concat 4 '(impl-list-from (impl-fold-at impl-append args 0 n [])))
         (bounded-body 'union 6 '(if (= 0 n) #{}
                                   (impl-fold-at impl-into args 1 n (data/nth args 0))))
         (bounded-body 'merge 4 '(impl-fold-at impl-merge-two args 0 n nil))
         (operation-body
           '[comp ([] (clj/make-callable {:clj/type :function}
                        (fn [args tail]
                          (let [n (data/count args)]
                            (if (= 1 n)
                              (data/nth args 0)
                              (clj/raise (assoc {:yin.vm.data/reason :arity}
                                               :yin.k/fn (quote identity) :yin.k/argc n)))))))
                  ([f] f)
                  ([f g] (impl-comp-two f g))
                  ([f g h] (impl-comp-two f (impl-comp-two g h)))])
         ['update-in '(fn [args]
                        (let [n (data/count args)]
                          (if (<= 3 n)
                            (let [xs (data/nth args 0)
                                  path (impl-vec (data/nth args 1))
                                  path (if (empty? path) [nil] path)
                                  depth (data/count path)
                                  f (data/nth args 2)
                                  value (impl-get-path xs path 0 depth nil)
                                  call-args (data/into (conj [] value) (data/subvec args 3 n))]
                              (impl-assoc-path xs path 0 depth (clj/invoke-callable f call-args [])))
                            (clj/raise (assoc {:yin.vm.data/reason :arity} :yin.k/fn (quote update-in)
                                             :yin.k/argc n)))))]
         ['assoc '(fn [args]
                    (let [n (data/count args)]
                      (if (if (<= 3 n) (= 1 (data/nth (integer/quot-rem n 2) 1)) false)
                        (impl-assoc-more (data/nth args 0) args 1 n)
                        (clj/raise (assoc {:yin.vm.data/reason :arity} :yin.k/fn (quote assoc) :yin.k/argc n)))))]
         ['conj '(fn [args]
                   (let [n (data/count args)]
                     (if (< 0 n)
                       (impl-conj-more (data/nth args 0) args 1 n)
                       (clj/raise (assoc {:yin.vm.data/reason :arity} :yin.k/fn (quote conj) :yin.k/argc n)))))]]))


(defn callable-entry [name]
  (symbol (str module-name) (str "impl-" name)))


(def public-names (mapv first entries))


(def ^:private definitions
  (let [qualify (into {} (map (fn [[name _]] [name (symbol (str module-name) (str "impl-op-" (subs (str name) 5)))])) helpers)]
    (into (mapv (fn [[name form]] [(get qualify name) (walk/postwalk-replace qualify form)]) helpers)
          (map (fn [[name form]] [(callable-entry name)
                                 (walk/postwalk-replace qualify form)]))
          entries)))



(def module-exports
  (set (map (comp #(symbol (name %)) callable-entry) public-names)))


(defn bundled-uast
  "Emit only the implementation entries and their transitive helpers used by a unit."
  [names]
  (let [by-name (into {} definitions)
        needed (loop [todo (map callable-entry names), seen #{}]
                 (if-let [name (first todo)]
                   (if (contains? seen name)
                     (recur (rest todo) seen)
                     (recur (concat (rest todo)
                                    (filter by-name (core/free-names (u/sexp->uast (by-name name)))))
                            (conj seen name)))
                   seen))
        impl (filterv #(contains? needed (first %)) definitions)
        ;; Only this subset of yrt.int is called by the bundled library.
        numeric (when (some #(contains? (core/free-names (u/sexp->uast (second %)))
                                        'yrt.int/check-limit) impl)
                  (filterv #(contains? '#{yrt.int/check-limit yrt.int/_perform} (first %))
                           int-rt/definitions))]
    (core/protect-primitives (core/balanced-definitions->uast (into (vec numeric) impl)))))


(def module-uast
  (u/annotate-call-sites
    (core/module-tree (core/module-definitions (str module-name) nil [] definitions)
                      (mapv u/sexp->uast
                            '[(require 'yang.call) (require 'clj) (require 'yrt.int)]))))


(def ^:private free-names (core/free-name-vec module-uast))


(defn module-spec [registry call-address clj-address int-address]
  (let [requires {call/module-name call-address clj/module-name clj-address
                  int-rt/module-name int-address}]
    (admission/module-spec
      {:module-name module-name :ast module-uast :exports module-exports
       :requires requires :free-names free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry :message "clojure.core.impl reads an undeclared name"
       :refusal-ns "yang.clojure"})))
