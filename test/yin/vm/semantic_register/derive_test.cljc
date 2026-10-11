(ns yin.vm.semantic-register.derive-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [yin.vm :as vm]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.parity-test :as b0]
            [yin.vm.debruijn :as db]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.debruijn-code :as dc]
            [yin.vm.debruijn-resolve :as dr]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-contract-test :as contract]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.corpus :as corpus :refer [lit v lam app tail if-node def! resume]]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.derive :as derive]))


(def worked-example
  "`((fn [x] (+ x 1)) 10)`, the same worked example `yin.vm.linearize-test`
   uses -- a simple program with no duplicate parameters, no free-variable
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
  [pipeline ast opts]
  (try
    (let [image (:image (if (= pipeline :old)
                          (dl/adapt (vm/ast->datoms ast))
                          (derive/stack-image (projected ast))))
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
      (is (vector? (derive/resolved-addresses (dr/resolve (vm/ast->datoms ast))))))))


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
  ["32d51cde25ba70371814b6dfdb3769af4ec8b5c52a4c2e03a01ee1abb0c0ab84" "2ba41668a2170a3c7ef74182c4e3c81d0b3fdbb7a8a6c96e72511c6f52b82aa8" "f74e6e75631bbd78d6fabb8dd829edc8d149ef29803111f48fefc5b209b39982" "add0178e7de17c8ea2d078834f43898525ec683a3c8f5b8304398d004a09c08b" "7e5eeba449d15077ea0b32bb394138b85d3ef2a8bed68c646973390f75a35e5f" "52791d4a2d0f4649b6d90e0c7e0a9067c96008ed4ecdc6b77f08a15644893d0c" "eb70fa6d6b3a4925bb1163916e2a35b0e030a39cb25c66e49e95c4251112bb3f" "a71ffdee457d4d6c90d704117cb0b5992abb98c7d4952356e018e056eae2cb48" "fe79f9ab7e0ab5c6a90813bf6dd849c9760577684cfc0aeff5408b922dbb866a" "8c80e1d1b1aafa1a3bcd8d17a31098fc80832f34c02e1983a62449acaecdf741"])


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
      (let [old (stack-run :old ast opts) new (stack-run :new ast opts)]
        (is (= old new))
        (is (= [true false (native-normalize expected)] (:observable old)))
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (is (= 26 (count b0/corpus)))
  (doseq [[label ast expected] b0/corpus]
    (testing label
      (let [old (stack-run :old ast {}) new (stack-run :new ast {})]
        (is (= old new))
        (is (= [true false (native-normalize expected)] (:observable new))))))
  (is (= 12 (count (filter #(contains? value-rows (first %)) corpus/programs))))
  (is (= 19 (count (remove #(or (contains? value-rows (first %)) (= :ffi-call-no-args (first %))) corpus/programs))))
  (is (= 1 (count (filter #(= :ffi-call-no-args (first %)) corpus/programs))))
  (doseq [[label ast] corpus/programs]
    (testing (str label)
      (let [opts (if (= label :ffi-call-no-args) {:bridge {:op/ping (fn [] :pong)}} {})
            old (stack-run :old ast opts) new (stack-run :new ast opts)]
        (is (= old new))
        (cond (= label :ffi-call-no-args) (is (= [true false :pong] (:observable new)))
              (contains? value-rows label) (is (= [true false] (subvec (:observable new) 0 2)))
              :else (is (= :thrown (first (:observable new)))))))))


(deftest lexical-address-law-stack
  (doseq [[label ast] C]
    (testing (str label)
      (let [a (projected ast)]
        (is (= (derive/addresses a) (derive/addresses (:vector (derive/resolve a)))
               (derive/addresses (:image (derive/stack-image a)))
               (derive/resolved-addresses (dr/resolve (vm/ast->datoms ast))))))))
  (is (some #(and (= :bound (first %)) (= 1 (second %)))
            (derive/addresses (projected (get (into {} corpus/programs) :nested-lambdas)))))
  (is (some #{[:bound 0 1]} (derive/addresses (projected duplicate-param))))
  ;; Guard the old resolver reader's exclusion of the definition operator.
  (let [ast (get (into {} corpus/programs) :define-then-call)]
    (is (not-any? #{[:free 'yin/def]}
                  (derive/resolved-addresses (dr/resolve (vm/ast->datoms ast)))))))


(deftest register-image-is-not-in-5a
  (is (= {:reason :not-in-slice-5a}
         (try (derive/register-image (projected (lit 1))) nil
              (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))))


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
