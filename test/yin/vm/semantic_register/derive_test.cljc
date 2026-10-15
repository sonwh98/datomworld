(ns yin.vm.semantic-register.derive-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [dao.test-slow :as slow]
            [yang.python.antlr.import-programs :as programs]
            [yang.python.antlr.linked-harness :as h]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.prelude :as prelude]
            [yang.python.antlr.safepoint :as safepoint]
            [yang.python.antlr.render :as render]
            [yang.python.antlr.uast :as u]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm :as vm]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.parity-test :as b0]
            [yin.vm.debruijn :as db]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.debruijn-code :as dc]

            [yin.vm.debruijn-register-code-test :as contract]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.corpus :as corpus :refer [lit v lam app tail if-node def! resume]]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.derive :as derive]))


(def worked-example
  "`((fn [x] (+ x 1)) 10)`, a simple program with no duplicate parameters, no free-variable
   surprises beyond the primitive `+`, and one level of closure nesting."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def duplicate-param
  "`(fn [x x] x)`: `resolve-name`'s rightmost-wins duplicate-parameter
   case, the design's own named example for the lift law."
  (lam '[x x] (tail (v 'x))))


(def free-variable
  "A body that references a name no enclosing closure binds: `y` lowers
   to `:load-free`, never `:load-bound`."
  (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))))


(def nested-closure
  "Two levels of nesting, an inner body reaching two frames outward, and a
   duplicate binder at the outer level to exercise both features in one
   corpus program."
  (app (lam '[a a]
            (tail (lam '[b]
                       (tail (app (v '+) (v 'a) (v 'b))))))
       (lit 1)))


(def stack-corpus
  "Respelled from debruijn_linearize_test.cljc's corpus."
  {:worked-example worked-example,
   ;; Rule R: a definition lowers to `:define`, its operator never loaded
   :definition (app (lam '[x] (tail (app (v 'yin/def) (lit 'k) (v 'x))))
                    (lit 5)),
   :duplicate-param duplicate-param,
   :free-variable free-variable,
   :nested-closure nested-closure,
   :literal (lit 42),
   :zero-arity-call (app (lam [] (lit 1))),
   :if-in-tail (app (lam '[n] (if-node (app (v '<) (v 'n) (lit 1))
                                       (lit :done)
                                       (tail (app (v '-) (v 'n) (lit 1)))))
                    (lit 3)),
   :streams {:type :stream/put,
             :target {:type :stream/make, :buffer 4},
             :val {:type :stream/next,
                   :source {:type :stream/cursor,
                            :source {:type :stream/close,
                                     :source (lit nil)}}}},
   :ffi {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 1) (lit 2)]},
   :ffi-no-args {:type :dao.stream.apply/call, :op :op/ping, :operands []},
   :default-gensym {:type :vm/gensym},
   :default-buffer {:type :stream/make},
   :store-and-control (app (lam '[a b c d]
                                (tail (app (v 'vector) (v 'a) (v 'b) (v 'c) (v 'd))))
                           {:type :vm/store-put, :key 'k, :val 5}
                           {:type :vm/store-get, :key 'k}
                           {:type :vm/gensym, :prefix "g"}
                           {:type :vm/current-continuation}),
   :park-resume (if-node (lit false)
                         {:type :vm/park}
                         {:type :vm/resume, :parked-id :p1, :val (lit 7)})})


(def r2-lift-programs
  {:store-get {:type :vm/store-get, :key :k}
   :store-put {:type :vm/store-put, :key :k, :val 42}
   :gensym {:type :vm/gensym, :prefix "g"}
   :stream-make {:type :stream/make, :buffer 8}
   :stream-put {:type :stream/put, :target (lit :s), :val (lit 1)}
   :stream-cursor {:type :stream/cursor, :source (lit :s)}
   :stream-next {:type :stream/next, :source (lit :c)}
   :stream-close {:type :stream/close, :source (lit :s)}
   :ffi {:type :dao.stream.apply/call,
         :op :math/add,
         :operands [(lit 1) (lit 2)]}
   :park {:type :vm/park}
   :current-continuation {:type :vm/current-continuation}
   :resume {:type :vm/resume, :parked-id :p1, :val (lit 42)}})


(def live-fixtures
  [[:live-a (app (v '+) (lit 1) (lit 2))]
   [:live-b (app (v '+) (v 'a) (if-node (v 'test) (app (v 'f) (lit 1)) (lit 0)))]
   [:live-c (if-node (v 'test) (app (v 'g) (app (v 'f) (lit 1)) (v 'x)) (app (v 'k) (lit 2)))]
   [:live-d (app (v 'f) (app (v 'g) (app (v 'h) (lit 1))))]
   [:live-e (lam '[x] (tail (app (v 'f) (v 'x))))]])


(def C
  (vec (concat (map (fn [[label ast _]] [label ast]) b0/corpus)
               corpus/programs (sort-by first stack-corpus)
               (sort-by first contract/address-law-extra-fixtures)
               live-fixtures (sort-by first r2-lift-programs))))


(defn native-normalize
  [x]
  (cond
    (values/continuation? x) :continuation
    (values/closure? x) :closure
    (values/host-typed? x) (native-normalize (values/payload x))
    (and (map? x) (= :closure (:type x))) :closure
    (and (map? x) (= :continuation (:type x))) :continuation
    (and (map? x) (contains? #{:stream-ref :cursor-ref} (:type x))) (select-keys x [:type :id])
    (fn? x) :host-fn
    (map? x) (into {} (map (fn [[k v]] [(native-normalize k) (native-normalize v)]) x))
    (vector? x) (mapv native-normalize x)
    (set? x) (into #{} (map native-normalize x))
    (seq? x) (mapv native-normalize x)
    :else x))


(defn projected
  [ast]
  (:vector (linearize/project ast)))


(defn stack-run
  [ast opts]
  (try
    (let [image (:image (derive/stack-image (projected ast)))
          task (vm/run (dvm/create-vm image
                                      (merge {:primitives (assoc vm/primitives 'list (fn [& xs] (apply list xs))
                                                                 'vector (fn [& xs] (vec xs)) 'inc inc)
                                              :make-stream tu/make-stream
                                              :capability-secret tu/secret :contract vm/stack-contract} opts)))]
      {:observable [(vm/halted? task) (vm/blocked? task) (native-normalize (vm/value task))]
       :store (native-normalize (vm/store task))})
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      {:observable [:thrown (ex-message e)]})))


(deftest resolve-rewrites-only-vars-and-closures
  (is (= 94 (count C)))
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast) {rv :vector names :names} (derive/resolve a)]
        (is (= (count a) (count rv)))
        (is (= (set (keys names))
               (set (keep-indexed (fn [pc t] (when (= :load-bound (first t)) pc)) rv))))
        (doseq [pc (range (count a))]
          (let [[op rd x body :as t] (nth a pc) r (nth rv pc)]
            (case op
              :var (do (is (= rd (nth r 1)))
                       (is (contains? #{:load-bound :load-free} (first r)))
                       (if (= :load-bound (first r)) (is (= x (get names pc)))
                           (is (= x (nth r 2)))))
              :closure (is (= [:closure rd (count x) body] r))
              (is (= t r)))))))))


(deftest resolution-is-resolve-names
  (is (= [[:bound 0 1]] (derive/addresses (projected duplicate-param))))
  (is (= '[[:free +] [:bound 1 1] [:bound 0 0]]
         (derive/addresses (projected nested-closure))))
  (is (= '[[:free +] [:bound 1 0] [:bound 0 0]]
         (derive/addresses (projected (get (into {} corpus/programs) :nested-lambdas)))))
  (is (= '[[:free +] [:bound 0 0] [:free y]]
         (derive/addresses (projected free-variable)))))


(deftest resolve-is-a-stage-value
  ;; Host reflection is only a JVM audit; the data law runs on every host.
  #?(:cljd nil :clj
     (is (empty? (filter (fn [n]
                           (or (re-find #"hash|key|identity|digest" (str n))
                               (contains? #{'address 'code-address} n)))
                         (keys (ns-publics 'yin.vm.semantic-register.derive))))))
  (doseq [[_ ast] C]
    (let [r (derive/resolve (projected ast))]
      (is (= r (edn/read-string (pr-str r))))
      (is (vector? (derive/addresses (:vector r))))
      (is (vector? (derive/addresses (:vector (derive/resolve (projected ast)))))))))


(def golden-asts
  [(app (v '+) (lit 1) (lit 2))
   (app (v 'f) (app (v 'g) (v 'x)) (v 'y))
   (app (app (v 'f)))
   (if-node (v 'c) (lit 1) (lit 2))
   (app (v 'f) (if-node (v 'c) (lit 1) (lit 2)) (v 'y))
   worked-example
   (def! 'x (lit 5))
   (def! 'y (app (v '+) (lit 1) (lit 2)))
   (app (v 'f) (resume :p (v 'x)) (v 'y))
   (app (v 'f) (tail (app (v 'g) (v 'x))) (v 'y))])


(def stack-golden-vectors
  '[;; §6.3 rows 1–9; row 7 contains two images.
    [[:load-free +] [:push] [:const 1] [:push] [:const 2] [:push] [:call 2 false] [:halt]]
    [[:load-free f] [:push] [:load-free g] [:push] [:load-free x] [:push] [:call 1 false] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]
    [[:load-free f] [:push] [:call 0 false] [:push] [:call 0 false] [:halt]]
    [[:load-free c] [:branch-false 4] [:const 1] [:jump 5] [:const 2] [:halt]]
    [[:load-free f] [:push] [:load-free c] [:branch-false 6] [:const 1] [:jump 7] [:const 2] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]
    [[:closure 1 6] [:push] [:const 10] [:push] [:call 1 false] [:halt] [:load-free +] [:push] [:load-bound 0 0] [:push] [:const 1] [:push] [:call 2 true] [:return]]
    [[:const 5] [:define x] [:halt]]
    [[:load-free +] [:push] [:const 1] [:push] [:const 2] [:push] [:call 2 false] [:define y] [:halt]]
    [[:load-free f] [:push] [:load-free x] [:resume :p] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]
    [[:load-free f] [:push] [:load-free g] [:push] [:load-free x] [:push] [:call 1 true] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]])


(def stack-golden-hashes
  ["0a1b29b833c6f54bfa3f7e9530f0a318c44dba06537a06597f7aea59f25d33fe" "6f64ba722c0e41f3b825a05b538351350bfc1827aad47d043f9b9cb47e060606" "c4413a954f8a9145b6f205c0630f6d2748b3b9285f0a53c361e051d8693f450b" "1bf1ac57333ebac7906ce6e2c9f347152ae222732df718017df3967e5351b76a" "d20861bf40f622a86bbd63dde4ce8a5407f6ddf68a0c7abe780e8cc515b857a5" "d07cb2e9a4172480d29e550cf94aa1771603d488ecca8e87f03576285c23872c" "f284fcb321a3f33ea57832c4bd7579ce759992c9764122fd4951082c81a71a06" "172bc86cd0131cbb22084d246003a43920a549989e1d88c0011ef562116b6fd1" "f35503a87fbd9761d43660299850ec4228742ce1ef5db7cc221c5fd5d5277f74" "83a461659f6459daa944684e30033fd3017f5fba607e2d9550e5b4e2c8b325cb"])


(deftest stack-goldens
  (is (= 10 (count stack-golden-vectors)))
  (doseq [[ast golden hash] (map vector golden-asts stack-golden-vectors stack-golden-hashes)]
    (is (= hash (dc/image-hash golden)))
    (is (= golden (:image (derive/stack-image (projected ast)))))
    (is (nil? (dc/image-defect golden)))))


(deftest stack-operand-goldens
  ;; Hand-derived from §3.2: push each FFI operand; for stream-put,
  ;; push the target before evaluating the value. Main ends with halt.
  (is (= [[:const 1] [:push] [:const 2] [:push] [:ffi-call :op/echo 2] [:halt]]
         (:image (derive/stack-image
                   (projected {:type :dao.stream.apply/call :op :op/echo
                               :operands [(lit 1) (lit 2)]})))))
  (is (= [[:const :s] [:push] [:const 1] [:stream-put] [:halt]]
         (:image (derive/stack-image
                   (projected {:type :stream/put :target (lit :s) :val (lit 1)}))))))


(deftest stack-images-validate
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast) {:keys [image side-table]} (derive/stack-image a)
            bodies (code/parse a)]
        (is (nil? (code/well-formed? a)))
        (is (nil? (dc/image-defect image)))
        ;; The format validator does not require :return at a lambda end.
        ;; Check the derivation's body layout independently, in discovery order.
        (let [starts (into [0] (keep (fn [t] (when (= :closure (first t)) (nth t 2)))) image)
              ends (conj (subvec starts 1) (count image))]
          (is (= (count bodies) (count starts)))
          (is (apply < (conj starts (count image))))
          (doseq [[i start end] (map vector (range) starts ends)]
            (is (= [(if (zero? i) :halt :return)] (nth image (dec end))))
            (doseq [[pc {:keys [source]}] side-table :when (<= start pc (dec end))]
              (is (= i (code/body-index bodies source))))))
        (doseq [[pc {:keys [kind params source]}] side-table]
          (is (= (if (= kind :closure) :closure :var) (first (nth a source))))
          (when (= kind :closure)
            (is (= params (nth (nth a source) 2)))
            (is (= (count params) (nth (nth image pc) 1)))))
        ;; Independently reconstruct binder chains for the emitted references.
        (doseq [[pc {:keys [kind source]}] side-table :when (= kind :var)]
          (let [t (nth image pc)]
            (when (= :load-bound (first t))
              (let [frames (loop [bi (code/body-index bodies source) out []]
                             (if-let [owner (:owner (nth bodies bi))]
                               (recur (code/body-index bodies owner)
                                      (conj out (nth (nth a owner) 2))) out))
                    [_ d q] t]
                (is (< d (count frames)))
                (is (< q (count (nth frames d))))
                (is (= {:bound [d q]} (db/resolve-name frames (nth (nth a source) 2))))))))))))


(deftest stack-determinism
  (doseq [[_ ast] C]
    (let [a (projected ast) h (:image (derive/stack-image a)) h2 (:image (derive/stack-image a))]
      (is (= h h2))
      (is (= (vec (dc/encode-image h)) (vec (dc/encode-image h2))))
      (is (= (dc/image-hash h) (dc/image-hash h2)))))
  (let [shared (assoc (app (v 'g) (lit 1)) :eid -90)]
    (doseq [[a b] [[(lam '[x] (v 'x)) (assoc (lam '[x] (v 'x)) :macro? true)]
                   [(app (v '+) shared shared) (app (v '+) (dissoc shared :eid) (dissoc shared :eid))]
                   [(app (v '+) (lit 1)) (app (v '+) (assoc (lit 1) :tail? false))]]]
      (let [pa (linearize/project a) pb (linearize/project b)
            ha (:image (derive/stack-image (:vector pa))) hb (:image (derive/stack-image (:vector pb)))]
        (is (= (:vector pa) (:vector pb))) (is (= (:address pa) (:address pb)))
        (is (= ha hb)) (is (= (dc/image-hash ha) (dc/image-hash hb)))))))


(def value-rows
  #{:literal :define :define-call :gensym :store-ops :lambda-application :nested-lambdas
    :if-in-test :define-then-call :stream-make-default :body-queue-order :closure-in-arm-in-body})


(def runtime-extra-rows
  [[:ffi-two-operands
    {:type :dao.stream.apply/call :op :op/echo :operands [(lit 1) (lit 2)]}
    {:bridge {:op/echo (fn [a b] [a b])}} [1 2]]
   [:put-on-made-stream
    {:type :stream/put :target {:type :stream/make :buffer 4} :val (lit 1)}
    {} 1]])


(deftest stack-execution-oracle
  (is (= 2 (count runtime-extra-rows)))
  (is (= 60 (+ (count b0/corpus) (count corpus/programs) (count runtime-extra-rows))))
  (doseq [[label ast opts expected] runtime-extra-rows]
    (testing (str label)
      (let [new (stack-run ast opts)]
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (is (= 26 (count b0/corpus)))
  (doseq [[label ast expected] b0/corpus]
    (testing label
      (let [new (stack-run ast {})]
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (is (= 12 (count (filter #(contains? value-rows (first %)) corpus/programs))))
  (is (= 19 (count (remove #(or (contains? value-rows (first %)) (= :ffi-call-no-args (first %))) corpus/programs))))
  (is (= 1 (count (filter #(= :ffi-call-no-args (first %)) corpus/programs))))
  (doseq [[label ast] corpus/programs]
    (testing (str label)
      (let [opts (if (= label :ffi-call-no-args) {:bridge {:op/ping (fn [] :pong)}} {})
            new (stack-run ast opts)]
        (cond (= label :ffi-call-no-args) (is (= [true false :pong] (:observable new)))
              (contains? value-rows label) (is (= [true false] (subvec (:observable new) 0 2)))
              :else (is (= :thrown (first (:observable new)))))))))


(deftest lexical-address-law-stack
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast)]
        (is (= (derive/addresses a) (derive/addresses (:vector (derive/resolve a)))
               (derive/addresses (:image (derive/stack-image a))))))))
  (is (some #(and (= :bound (first %)) (= 1 (second %)))
            (derive/addresses (projected (get (into {} corpus/programs) :nested-lambdas)))))
  (is (some #{[:bound 0 1]} (derive/addresses (projected duplicate-param))))
  ;; Definition syntax never becomes a free variable load.
  (let [ast (get (into {} corpus/programs) :define-then-call)]
    (is (not-any? #{[:free 'yin/def]}
                  (derive/addresses (:vector (derive/resolve (projected ast))))))))


;; First numeric verification includes every stated L(p), not only H labels.
(deftest hand-derived-liveness
  (let [pins [[#{} #{1} #{1 2} #{1 2 3} #{0}]
              [nil #{1} #{1 3} #{1 3 4} #{1 2} #{1 2 5} #{0}]
              [#{} #{2} #{1} #{0}]
              [#{} #{1} #{} #{0} #{} #{0}]
              [#{} #{1} #{1 3} #{1} #{1 2} #{1} #{1 2} #{1 2 4} #{0}]
              [nil #{1} #{1 2} #{0} #{} #{1} #{1 2} #{1 2 3} #{0}]
              [nil #{1} #{0}]
              [nil #{2} #{2 3} #{2 3 4} #{1} #{0}]
              [#{} #{} #{3} #{1 2} #{1 2 4} #{0}]
              [#{} #{} #{3} #{3 4} #{1 2} #{1 2 5} #{0}]]]
    (doseq [[row ast expected] (map vector (range 1 11) golden-asts pins)]
      (let [actual (:live (analysis/analyze (projected ast)))]
        (doseq [[pc want] (map-indexed vector expected) :when (some? want)]
          (is (= want (nth actual pc)) (str "§6.3 image " row " L(" pc ")"))))))
  (let [a (projected (if-node (v 'c) (resume :p (lit 1)) (lit 2)))
        live (:live (analysis/analyze a))]
    (is (= #{0} (nth live 4) (nth live 6)))
    (is (= #{4 5 6} (into #{5} (keep-indexed (fn [pc s] (when (contains? s 0) pc))) live)))))


(def register-golden-images
  '[{:bodies [{:locals 0 :registers 4 :start 0 :end 4}]
     :instructions [[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:halt 3]]}
    {:bodies [{:locals 0 :registers 4 :start 0 :end 6}]
     :instructions [[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] false [0]] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]}
    {:bodies [{:locals 0 :registers 2 :start 0 :end 3}]
     :instructions [[:load-free 0 f] [:call 1 0 [] false []] [:call 0 1 [] false []] [:halt 0]]}
    {:bodies [{:locals 0 :registers 1 :start 0 :end 5}]
     :instructions [[:load-free 0 c] [:branch-false 0 4] [:const 0 1] [:jump 5] [:const 0 2] [:halt 0]]}
    {:bodies [{:locals 0 :registers 4 :start 0 :end 8}]
     :instructions [[:load-free 0 f] [:load-free 1 c] [:branch-false 1 5] [:const 1 1] [:jump 6] [:const 1 2] [:load-free 2 y] [:call 3 0 [1 2] false []] [:halt 3]]}
    {:bodies [{:locals 0 :registers 3 :start 0 :end 3} {:locals 1 :registers 5 :start 4 :end 8}]
     :instructions [[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2] [:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]]}
    {:bodies [{:locals 0 :registers 2 :start 0 :end 2}]
     :instructions [[:const 0 5] [:define 1 x 0] [:halt 1]]}
    {:bodies [{:locals 0 :registers 4 :start 0 :end 5}]
     :instructions [[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:define 0 y 3] [:halt 0]]}
    {:bodies [{:locals 0 :registers 4 :start 0 :end 5}]
     :instructions [[:load-free 0 f] [:load-free 1 x] [:resume :p 1] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]}
    {:bodies [{:locals 0 :registers 4 :start 0 :end 6}]
     :instructions [[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] true []] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]}])


(def register-golden-hashes
  ["e2fac210d2939f0f3139bf57b941eab65c55c3c4373eac6fc4c937faaa4f3515"
   "07de8d305555a3c4a802cb302e53f06a0a90a632eb3255356a1413d6c5dbcbf8"
   "babe1615ccf93067a915fbdf99b0d102e6d6f7e9912a27dfd44f8ef2c10f1133"
   "d078be3a34c1016daff53f3d681bc2999fd92e1d2d85fe5c7986b09cae92463c"
   "7b2f6135e68056104117b05b5673787a0941c04a7ce192e377ad7d129f278adb"
   "bc06914564a0c780833da62637ed3c3863108a1b007afef03695b7dd9e384030"
   "668cb6b1431bad2af226f9ac3542f8b4d0c1faf5ce54dba5c73e39c401f2435b"
   "eaeda8e420db6331a9945808f017f8dd38136701ada3982f34e20afecc669309"
   "09e8c987e3b720fdeefc87ccbcec53ee6981c0209339f2318055a9cc39e0fdcd"
   "2e3a44ccf973e9a9e0198b4a5806a72be5af713ac6a0109f552b135efb753ec9"])


(deftest register-goldens
  (is (= 10 (count register-golden-images)))
  (doseq [[i ast golden] (map vector (range) golden-asts register-golden-images)]
    (testing (str "§6.3 image " (inc i))
      (is (= golden (derive/register-image (projected ast))))
      (is (= (nth register-golden-hashes i) (rcode/register-hash golden)))
      (is (nil? (rcode/register-image-defect golden))))))


(defn register-run
  [ast opts]
  (try
    (let [image (derive/register-image (projected ast))
          task (vm/run (rvm/create-vm image
                                      (merge {:primitives (assoc vm/primitives 'list (fn [& xs] (apply list xs))
                                                                 'vector (fn [& xs] (vec xs)) 'inc inc)
                                              :make-stream tu/make-stream
                                              :capability-secret tu/secret :contract vm/register-contract} opts)))]
      {:observable [(vm/halted? task) (vm/blocked? task) (native-normalize (vm/value task))]
       :store (native-normalize (vm/store task))})
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      {:observable [:thrown (ex-message e)]})))


(defn register-operands
  [t]
  (into [] (mapcat (fn [[[_ kind] x]]
                     (case kind :reg [x] :regs x [])))
        (map vector (get rcode/opcode-table (first t)) (rest t))))


(defn slot-map
  "Reconstruct the mapping independently from paired instruction operands,
   rather than asking the allocator for its private state."
  [a image start end]
  (reduce (fn [m pc]
            (let [t (nth a pc) r (nth (:instructions image) pc)
                  vs (into (if-let [rd (code/rd t)] [rd] []) (code/uses t))
                  rs (register-operands r)]
              (reduce (fn [m [v r]]
                        (when (contains? m v) (is (= (get m v) r)))
                        (assoc m v r)) m (map vector vs rs))))
          {} (range start end)))


(deftest register-images-validate
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast) image (derive/register-image a) bodies (code/parse a)]
        (is (nil? (rcode/register-image-defect image)))
        (is (= (count a) (count (:instructions image))))
        (is (= (count bodies) (count (:bodies image))))
        (doseq [[body descriptor] (map vector bodies (:bodies image))]
          (let [{:keys [owner start end]} body {:keys [locals registers]} descriptor
                frames (loop [bi (code/body-index bodies start) out []]
                         (if-let [owner (:owner (nth bodies bi))]
                           (recur (code/body-index bodies owner) (conj out (nth (nth a owner) 2))) out))]
            (is (= (select-keys descriptor [:start :end]) {:start start :end (dec end)}))
            (is (= locals (if (nil? owner) 0 (count (nth (nth a owner) 2)))))
            (is (= (if (nil? owner) :halt :return) (first (nth (:instructions image) (dec end)))))
            (doseq [pc (range start end)]
              (let [t (nth (:instructions image) pc)]
                (is (not= :move (first t)))
                (doseq [r (register-operands t)] (is (<= locals r (dec registers))))
                (when (= :load-bound (first t))
                  (let [[_ _ d q] t]
                    (is (< d (count frames)))
                    (is (< q (count (nth frames d))))))))))))))


(deftest two-liveness-analyses-agree
  (doseq [[label ast] (conj C [:tail-operand (last golden-asts)])]
    (testing (str label)
      (let [a (projected ast) image (derive/register-image a) facts (analysis/analyze a)]
        (doseq [{:keys [start end]} (code/body-ranges a)]
          (let [slots (slot-map a image start end)]
            (doseq [pc (range start end)
                    :let [t (nth a pc) op (first t)]
                    :when (contains? rcode/boundary-opcodes op)]
              (let [out (reduce into #{} (map #(analysis/live facts %) (analysis/successors a pc)))
                    expected (vec (sort (map slots (disj out (code/rd t)))))
                    actual (nth (nth (:instructions image) pc) (rcode/live-slot-index op))]
                (is (= expected actual))
                (when (code/tail-call? t) (is (= [] expected actual)))))))))))


(deftest structural-slot
  (doseq [label [:resume-operand :all-terminal-arms :resume-lambda-body]]
    (let [a (projected (get (into {} corpus/programs) label)) image (derive/register-image a)]
      (doseq [[{:keys [start end]} {:keys [locals registers]}]
              (map vector (code/body-ranges a) (:bodies image))]
        (let [defined (into #{} (keep #(code/rd (nth a %))) (range start end))
              slots (slot-map a image start end)
              missing (remove defined (keys slots))
              structural (dec registers)
              peak (inc (reduce max -1 (map #(- (get slots %) locals) defined)))
              reachable (analysis/reachable a start)]
          (when (seq missing)
            (is (= registers (+ locals peak 1)))
            (doseq [r missing] (is (= structural (get slots r))))
            (doseq [pc (range start end)]
              (let [t (nth (:instructions image) pc)]
                (when (some #{structural} (register-operands t)) (is (not (contains? reachable pc))))
                (when (and (contains? reachable pc) (contains? rcode/boundary-opcodes (first t)))
                  (is (not (some #{structural} (nth t (rcode/live-slot-index (first t)))))))))))))))


(deftest worked-example-slots
  (let [image (derive/register-image (projected (second golden-asts)))]
    (is (= (second register-golden-images) image))
    (is (= [:call 3 1 [2] false [0]] (nth (:instructions image) 3)))))


(deftest register-operand-goldens
  (is (= {:bodies [{:locals 0 :registers 3 :start 0 :end 3}]
          :instructions [[:const 0 1] [:const 1 2] [:ffi-call 2 :op/echo [0 1] []] [:halt 2]]}
         (derive/register-image (projected (second (first runtime-extra-rows))))))
  (is (= {:bodies [{:locals 0 :registers 3 :start 0 :end 3}]
          :instructions [[:stream-make 0 4] [:const 1 1] [:stream-put 2 0 1 []] [:halt 2]]}
         (derive/register-image (projected (second (second runtime-extra-rows)))))))


(deftest register-execution-oracle
  (is (= 26 (count b0/corpus)))
  (is (= 12 (count (filter #(contains? value-rows (first %)) corpus/programs))))
  (is (= 19 (count (remove #(or (contains? value-rows (first %)) (= :ffi-call-no-args (first %))) corpus/programs))))
  (is (= 1 (count (filter #(= :ffi-call-no-args (first %)) corpus/programs))))
  (doseq [[label ast opts expected] runtime-extra-rows]
    (testing (str label)
      (let [new (register-run ast opts)]
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (doseq [[label ast expected] b0/corpus]
    (testing label
      (let [new (register-run ast {})]
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (doseq [[label ast] corpus/programs]
    (testing (str label)
      (let [opts (if (= label :ffi-call-no-args) {:bridge {:op/ping (fn [] :pong)}} {})
            new (register-run ast opts)]
        (cond (= label :ffi-call-no-args) (is (= [true false :pong] (:observable new)))
              (contains? value-rows label) (is (= [true false] (subvec (:observable new) 0 2)))
              :else (is (= :thrown (first (:observable new)))))))))


(deftest lexical-address-law
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast)]
        (is (= (derive/addresses a) (derive/addresses (:vector (derive/resolve a)))
               (derive/addresses (:instructions (derive/register-image a)))
               (derive/addresses (:image (derive/stack-image a)))))))))


(deftest register-determinism
  (doseq [[_ ast] C]
    (let [a (projected ast) r (derive/register-image a) r2 (derive/register-image a)]
      (is (= r r2))
      (is (= (vec (rcode/encode-register-image r)) (vec (rcode/encode-register-image r2))))
      (is (= (rcode/register-hash r) (rcode/register-hash r2)))))
  (let [shared (assoc (app (v 'g) (lit 1)) :eid -90)]
    (doseq [[a b] [[(lam '[x] (v 'x)) (assoc (lam '[x] (v 'x)) :macro? true)]
                   [(app (v '+) shared shared) (app (v '+) (dissoc shared :eid) (dissoc shared :eid))]
                   [(app (v '+) (lit 1)) (app (v '+) (assoc (lit 1) :tail? false))]]]
      (let [pa (linearize/project a) pb (linearize/project b)
            ra (derive/register-image (:vector pa)) rb (derive/register-image (:vector pb))]
        (is (= (:vector pa) (:vector pb))) (is (= (:address pa) (:address pb)))
        (is (= ra rb)) (is (= (rcode/register-hash ra) (rcode/register-hash rb)))))))


(def c4-root-rows
  [["o" programs/o-packet ["a" "m-body" "b"]]
   ["q" programs/q-packet ["m-body" "done"]]
   ["d" programs/d-packet ["m-body" "7"]]
   ["c" programs/c-packet ["m-body" "5" "absent" "('gone', 5)"]]
   ["r" programs/r-packet ["w-once" "cleaned" "w-once" "again"]]
   ["x" programs/x-packet ["caught-e"]]
   ["nn" programs/nn-packet ["n"]]
   ["main" nil ["__main__"]]
   ["s" programs/s-packet ["m-body" "True" "True"]]
   ["tt" programs/tt-packet ["k-body" "t-sees 3" "t-done"]]])


(def c4-backends
  {:stack (fn [ast opts]
            (dvm/create-vm (:image (derive/stack-image (projected ast)))
                           (assoc opts :contract vm/stack-contract)))
   :register (fn [ast opts]
               (rvm/create-vm (derive/register-image (projected ast))
                              (assoc opts :contract vm/register-contract)))})


(defn c4-task
  [family ast source]
  (let [pair (link/make-pair)
        task ((get c4-backends family) ast (h/composition (h/registry) pair))]
    (first (h/drive task pair source))))


(deftest ^:slow c4-execution-oracle
  (slow/guard
    "c4-execution-oracle"
    (fn []
      (println "C4 preparing published py module")
      (let [reg (h/registry) py (:address @h/published)
            guests (reduce (fn [out [name packet deps]]
                             (println "C4 publishing guest" name)
                             (let [spec (lower/module-spec reg packet
                                                           {:name name :py-address py
                                                            :deps (into {} (map (fn [n] [n (:address (get out n))])) deps)})
                                   result (h/publish-guest-module! spec)]
                               (assoc out name {:address (:address result) :ast (:ast spec)})))
                           {} [["k" programs/k-packet []] ["m" programs/m-packet []]
                               ["t" programs/t-packet ["k"]] ["w" programs/w-packet []]
                               ["e" programs/e-packet []] ["n" programs/n-packet []]])
            source (h/source (into {'py py} (map (fn [[n x]] [(lower/module-symbol n) (:address x)])) guests))
            modules (into [["py" prelude/module-uast]
                           ["pysp" (:ast (safepoint/module-spec reg py))]]
                          (map (fn [n] [n (:ast (get guests n))])) ["k" "m" "t" "w" "e" "n"])
            main (u/mark-tails (u/seq-nodes
                                 [(u/sexp->uast '(require (quote py)))
                                  (u/sexp->uast '(require (quote pym.n)))
                                  (u/sexp->uast '(py/init!))
                                  (u/app (u/v 'py/run-main) (u/v 'pym.n/body))]))]
        (is (= 10 (count c4-root-rows)))
        (is (= 8 (count modules)))
        (doseq [[label packet output] c4-root-rows]
          (let [ast (if packet (lower/lower-packet packet {:prelude :linked}) main)]
            (doseq [family [:stack :register]]
              (println "C4 root" label family)
              (testing (str "root " label " " family)
                (let [observe (fn []
                                (let [task (c4-task family ast source)]
                                  [(vm/halted? task) (vm/blocked? task) (render/output (vm/value task))]))
                      new (observe)]
                  (is (= [true false {:py/out output :py/exception nil}] new)))))))
        (doseq [[label ast] modules family [:stack :register]]
          (println "C4 module" label family)
          (testing (str "module " label " " family)
            (let [new (c4-task family ast source)]
              (is (= [true false] [(vm/halted? new) (vm/blocked? new)]))
              (is (= (prelude/defined-keys ast) (set (keys (vm/store new))))))))
        (println "C4 completed: 10 root programs; 8 module bodies; both kernel families")
        ;; Install-child execution over newly published images is phase 8.
        ))))


(deftest widened-layout-interval
  ;; §6.3's numeric-note image; widening does not change this allocation.
  (let [a (projected (if-node (v 'c) (resume :p (lit 1)) (lit 2)))
        image (derive/register-image a)]
    (is (= '{:bodies [{:locals 0 :registers 1 :start 0 :end 6}]
             :instructions [[:load-free 0 c] [:branch-false 0 5] [:const 0 1]
                            [:resume :p 0] [:jump 6] [:const 0 2] [:halt 0]]}
           image)))
  (testing "§2.2 widened start reserves T0 before the else-arm temporaries"
    ;; L(4)={0} widens v0 to [4,8], ahead of v3:[5,7] and v4:[6,7].
    (let [a (projected (if-node (v 'c) (resume :p (lit 1)) (app (v 'g) (lit 2))))]
      (is (= '[[:var 1 c] [:branch-false 1 5] [:const 2 1] [:resume :p 2]
               [:jump 8] [:var 3 g] [:const 4 2] [:call 0 3 [4] false] [:halt 0]]
             a))
      (is (= '{:bodies [{:locals 0 :registers 3 :start 0 :end 8}]
               :instructions [[:load-free 0 c] [:branch-false 0 5] [:const 0 1]
                              [:resume :p 0] [:jump 8] [:load-free 1 g] [:const 2 2]
                              [:call 0 1 [2] false []] [:halt 0]]}
             (derive/register-image a))))))


(deftest hand-derived-register-intervals
  (let [pins [{0 [3 4] 1 [0 3] 2 [1 3] 3 [2 3]}
              {0 [5 6] 1 [0 5] 2 [3 5] 3 [1 3] 4 [2 3] 5 [4 5]}
              {0 [2 3] 1 [1 2] 2 [0 1]}
              {0 [2 5] 1 [0 1]}
              {0 [7 8] 1 [0 7] 2 [3 7] 3 [1 2] 4 [6 7]}
              {0 [2 3] 1 [0 2] 2 [1 2]}
              {0 [1 2] 1 [0 1]}
              {0 [4 5] 1 [3 4] 2 [0 3] 3 [1 3] 4 [2 3]}
              {0 [4 5] 1 [0 4] 3 [1 2] 4 [3 4]}
              {0 [5 6] 1 [0 5] 2 [3 5] 3 [1 3] 4 [2 3] 5 [4 5]}]]
    (doseq [[i ast expected] (map vector (range) golden-asts pins)]
      (let [a (projected ast) facts (analysis/analyze a)
            interval (fn [start end]
                       (let [defined (into #{} (keep #(code/rd (nth a %))) (range start end))]
                         (into {} (map (fn [r]
                                         (let [points (keep (fn [pc]
                                                              (when (or (= r (code/rd (nth a pc)))
                                                                        (contains? (analysis/live facts pc) r)) pc))
                                                            (range start end))]
                                           [r [(apply min points) (apply max points)]]))) defined)))
            end (:end (first (code/body-ranges a)))]
        (is (= expected (interval 0 end)) (str "§6.3 image " (inc i)))
        (when (= i 5)
          (is (= {0 [7 8] 1 [4 7] 2 [5 7] 3 [6 7]} (interval 4 9))))))))


(deftest resolution-preserves-scalars-and-contexts
  (let [a (projected (lit 42))]
    (is (= [:const 0 42] (first (:vector (derive/resolve a))))))
  (is (= '#{[:free +] [:free y]}
         (into #{} (filter #(= :free (first %)))
               (derive/addresses (projected free-variable)))))
  (doseq [[label expected]
          [[:shared-variable-under-two-contexts '[[:free list] [:bound 0 0] [:free x]]]
           [:shared-lambda-under-two-contexts '[[:free list] [:free +] [:bound 0 0] [:bound 1 1]
                                                [:free +] [:bound 0 0] [:free q]]]]]
    (is (= expected (derive/addresses (projected (get contract/address-law-extra-fixtures label)))))))


(defn tail-countdown-ast
  "Self-passing tail-recursive countdown from `n` to 0: loop throughput,
   tail-call dispatch, and frame reuse."
  [n]
  (let [self-fn (lam '[self n]
                     (tail (if-node (app (v '<) (v 'n) (lit 1))
                                    (lit 0)
                                    (tail (app (v 'self)
                                               (v 'self)
                                               (app (v '-) (v 'n) (lit 1)))))))]
    (tail (app self-fn self-fn (lit n)))))


(defn fibonacci-ast
  "Self-passing doubly recursive fib: call/return overhead, frame
   allocation, and binary arithmetic."
  [n]
  (let [rec (fn [k]
              (app (v 'self) (v 'self) (app (v '-) (v 'n) (lit k))))
        self-fn (lam '[self n]
                     (tail (if-node (app (v '<) (v 'n) (lit 2))
                                    (v 'n)
                                    (tail (app (v '+) (rec 1) (rec 2))))))]
    (tail (app self-fn self-fn (lit n)))))


(defn lexical-binding-ast
  "`depth` nested let-shaped lambda applications binding x0..x(depth-1),
   whose innermost body sums every binding: bound-variable access at
   every lexical depth."
  [depth]
  (let [names (mapv #(symbol (str "x" %)) (range depth))
        sum (reduce (fn [acc sym] (app (v '+) acc (v sym)))
                    (v (first names))
                    (rest names))]
    (reduce (fn [body i]
              (tail (app (lam [(nth names i)] body) (lit (inc i)))))
            (tail sum)
            (range (dec depth) -1 -1))))


(deftest native-workload-values-and-step-parity
  (doseq [[label ast expected] [[:tail-countdown (tail-countdown-ast 500) 0]
                                [:fibonacci (fibonacci-ast 12) 144]
                                [:lexical-binding (lexical-binding-ast 8) 36]]
          family [:stack :register]]
    (testing (str label " " family)
      (let [a (projected ast)
            opts {:primitives vm/primitives :make-stream tu/make-stream :capability-secret tu/secret}
            task (case family
                   :stack (dvm/create-vm (:image (derive/stack-image a)) (assoc opts :contract vm/stack-contract))
                   :register (rvm/create-vm (derive/register-image a) (assoc opts :contract vm/register-contract)))
            stepped (loop [t task steps 0]
                      (cond
                        (or (vm/halted? t) (vm/blocked? t)) [t steps]
                        (>= steps 1000000) (throw (ex-info "Workload exceeded step cap" {:label label :family family}))
                        :else (recur (vm/step t) (inc steps))))]
        (is (pos? (second stepped)))
        (is (= expected (vm/value (vm/run task)) (vm/value (first stepped))))))))


(deftest native-corpus-covers-opcodes
  (is (= dc/mnemonics
         (into #{} (mapcat (fn [[_ ast]]
                             (map first (:image (derive/stack-image (projected ast)))))) C)))
  (is (= (disj (set (keys rcode/opcode-table)) :move)
         (into #{} (mapcat (fn [[_ ast]]
                             (map first (:instructions (derive/register-image (projected ast)))))) C))))


(deftest native-effect-operands-are-preserved
  ;; Ignore physical registers here: the format and liveness tests pin those.
  ;; These are the data operands the retired R2 exact-image tests protected.
  (doseq [[label ast] r2-lift-programs
          :let [a (projected ast)
                stack (:image (derive/stack-image a))
                register (:instructions (derive/register-image a))
                op (case label
                     :ffi :ffi-call
                     :store-get :store-get :store-put :store-put
                     :gensym :gensym :stream-make :stream-make
                     :stream-put :stream-put :stream-cursor :stream-cursor
                     :stream-next :stream-next :stream-close :stream-close
                     :park :park :current-continuation :current-continuation
                     :resume :resume)
                st (first (filter #(= op (first %)) stack))
                rt (first (filter #(= op (first %)) register))]]
    (testing (name label)
      (is (some? st))
      (is (some? rt))
      (case label
        (:store-get :store-put :gensym :stream-make)
        (do (is (= (subvec st 1) (subvec rt 2)))
            (is (= (case label :store-get [:k] :store-put [:k 42]
                         :gensym ["g"] :stream-make [8])
                   (subvec st 1))))
        :ffi (do (is (= :math/add (nth st 1) (nth rt 2)))
                 (is (= 2 (nth st 2) (count (nth rt 3)))))
        :resume (is (= :p1 (nth st 1) (nth rt 1)))
        nil)
      (is (= (into [] (keep #(when (= :const (first %)) (peek %))) stack)
             (into [] (keep #(when (= :const (first %)) (peek %))) register))))))
