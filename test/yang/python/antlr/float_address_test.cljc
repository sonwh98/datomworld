(ns yang.python.antlr.float-address-test
  "Float kind is the producer's to carry (float-address mob ruling): the
   lowering builds Jing float64 content while the literal syntax still says
   float, and the bundled prelude holds no integral float literal, so a
   float-bearing program and the full prelude have one canonical form on
   every host. The goldens below were computed on the JVM; Node and Dart
   run the same assertions, so these tests passing on every lane is the
   cross-host byte and address identity the ruling's release gate asks
   for. Also: kind survives projection and decoding, 1 and 1.0 stay
   distinct, a zero keeps its sign, and the program runs on all four VMs,
   decoded or not."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [dao.jing.cbor :as cbor]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.lower-portable-test :refer [packet]]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.safepoint :as hooks]
    [yang.python.antlr.uast :as u]
    [yang.safepoint :as safepoint]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as walker]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.encoder :as encoder]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]
    [yin.vm.values :as values]))


;; =============================================================================
;; Programs: parser CSTs as nested forms
;; =============================================================================

(defn- test-chain
  "`test` down to `expr`, a single-child chain."
  [expr]
  [:test [:or_test [:and_test [:not_test [:comparison expr]]]]])


(defn- num-expr
  [text]
  [:expr [:atom_expr [:atom ["NUMBER" text]]]])


(defn- binary
  [a op-type op b]
  [:expr a [op-type op] b])


(defn- print-call
  "`print(arg, ...)` as a statement."
  [exprs]
  [:stmt [:simple_stmts
          [:simple_stmt
           [:expr_stmt
            [:testlist_star_expr
             (test-chain
               [:expr [:atom_expr [:atom [:name ["NAME" "print"]]]
                       [:trailer ["OPEN_PAREN" "("]
                        (into [:arglist]
                              (interpose ["COMMA" ","]
                                         (map (fn [e]
                                                [:argument (test-chain e)])
                                              exprs)))
                        ["CLOSE_PAREN" ")"]]]])]]]
          ["NEWLINE" "\n"]]])


(def ^:private float-program
  "while False:\n    pass\nprint(2.0, 1 + 2.0, 4 / 2, -0.0, 0.5, 1)\n:
   the loop is a safepoint site, so A' differs from A"
  (packet
    [:file_input
     [:stmt [:compound_stmt
             [:while_stmt ["WHILE" "while"]
              (test-chain [:expr [:atom_expr [:atom ["FALSE" "False"]]]])
              ["COLON" ":"]
              [:block ["NEWLINE" "\n"] ["INDENT" "    "]
               [:stmt [:simple_stmts [:simple_stmt [:pass_stmt ["PASS" "pass"]]]
                       ["NEWLINE" "\n"]]]
               ["DEDENT" ""]]]]]
     (print-call [(num-expr "2.0")
                  (binary (num-expr "1") "ADD" "+" (num-expr "2.0"))
                  (binary (num-expr "4") "DIV" "/" (num-expr "2"))
                  [:expr ["MINUS" "-"] (num-expr "0.0")]
                  (num-expr "0.5")
                  (num-expr "1")])
     ["EOF" "<EOF>"]]))


(def ^:private printed
  "What `float-program` prints, as CPython prints it."
  "2.0 3.0 2.0 -0.0 0.5 1")


(defn- tree-of
  [e]
  (nth (:yin/batch e) (:yin/root e)))


(defn- projected
  "`A`: the lowering's program batch, projected to rows."
  []
  (let [[_ [[port e]]] (lower/lower-transform {} float-program)]
    (assert (= :program port))
    (encoder/project e)))


(defn- b64
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- neg-zero
  "-0.0, built by multiplication: ClojureDart reads one-argument `-` as
   (0 - x), which is +0.0 for x = 0.0."
  []
  (* -1.0 0.0))


;; =============================================================================
;; Literal kind
;; =============================================================================

(deftest lowering-builds-float64-content-test
  (testing "a float literal's payload is float64 content on every host:
            on JS the carrier, never the bare Number, which is an integer"
    (let [body (lower/lower-module-body float-program)
          floats (->> (tree-seq coll? seq body)
                      (filter #(and (map? %) (= :literal (:type %))
                                    (map? (:value %))
                                    (contains? (:value %) :py/float)))
                      (map #(:py/float (:value %))))]
      (is (= [(cbor/float64 2.0) (cbor/float64 2.0) (cbor/float64 0.0)
              (cbor/float64 0.5)]
             (vec floats)))
      (is (every? cbor/float64? floats)))))


(deftest canonical-bytes-test
  (testing "byte-identical on every host (JVM goldens)"
    (is (= "odgbgnBkYW8uamluZy9rZXl3b3JkgmJweWVmbG9hdNgbgnBkYW8uamluZy9mbG9hdDY0SEAAAAAAAAAA"
           (b64 {:py/float (cbor/float64 2.0)})))
    (is (= "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP/AAAAAAAAA="
           (b64 (cbor/float64 1))))
    (is (= "AQ==" (b64 1)))
    (is (= "2BuCcGRhby5qaW5nL2Zsb2F0NjRIgAAAAAAAAAA="
           (b64 (cbor/float64 (neg-zero))))))
  (testing "1 and 1.0 are different identities, numerically equal or not"
    (is (not= (jing/segment-key 1) (jing/segment-key (cbor/float64 1))))
    (is (not= (jing/segment-key {:py/float 1})
              (jing/segment-key {:py/float (cbor/float64 1)}))))
  (testing "a zero keeps its sign"
    (is (not= (jing/segment-key (cbor/float64 0))
              (jing/segment-key (cbor/float64 (neg-zero)))))
    (is (= "-0.0"
           (render/float-repr
             (cbor/decode (jing/canonical-bytes (cbor/float64 (neg-zero))))))))
  (testing "decoding keeps the kind"
    (let [v {:py/float (cbor/float64 2.0)}]
      (is (= v (cbor/decode (jing/canonical-bytes v)))))))


;; =============================================================================
;; The prelude
;; =============================================================================

(defn- float-literal-defects
  "Literal values in `ast` that break the prelude's float discipline: an
   integral float anywhere (on JS it would already be an integer, so only a
   host that tells them apart sees it), or any float outside an operand of
   `data/float-value`."
  [ast]
  (letfn [(floats-in
            [v]
            (filter cbor/float64? (tree-seq coll? seq v)))
          (integral-float?
            [x]
            #?(:cljd (and (dart/is? x double) (.-isFinite ^double x)
                          (== x (.roundToDouble ^double x)))
               :clj (and (instance? Double x) (Double/isFinite x)
                         (== x (Math/rint x)))
               ;; JS has read an integral float as an integer by now
               :cljs (and (number? x) false)))
          (walk
            [node bridged?]
            (cond
              (and (map? node) (= :literal (:type node)))
              (concat (filter integral-float? (floats-in (:value node)))
                      (when-not bridged? (floats-in (:value node))))
              (and (map? node) (= :application (:type node)))
              (let [bridge? (= {:type :variable, :name 'data/float-value}
                               (select-keys (:operator node) [:type :name]))]
                (concat (walk (:operator node) false)
                        (mapcat #(walk % bridge?) (:operands node))))
              (map? node) (mapcat #(walk % false) (vals node))
              (coll? node) (mapcat #(walk % false) node)
              :else nil))]
    (vec (walk ast false))))


(deftest prelude-float-discipline-test
  (testing "no integral float literal in the base or hook prelude, and every
            float literal is a `data/float-value` operand"
    (is (= [] (float-literal-defects prelude/uast)))
    (is (= [] (float-literal-defects hooks/uast)))))


(deftest prelude-addresses-test
  (testing "the bundled prelude and the hook prelude: one address on every
            host (JVM goldens)"
    (is (= :segment/blake3-583cb25028f56f2c4f22881641c847287b35abffce0824f97fb445e1f030c473
           (:root (vm/ast->semantic-bytecode prelude/uast))))
    (is (= :segment/blake3-2d60190887dc1ca388d7a7b75467757c3f0e6c4b2d909603735c9a9852b0e3e3
           (:root (vm/ast->semantic-bytecode hooks/uast))))))


;; =============================================================================
;; A float-bearing program: A, A' and the derivation record
;; =============================================================================

(deftest program-addresses-test
  (let [p (projected)
        a (tree-of p)
        {:keys [envelope record-address]}
        (safepoint/derive-envelope p hooks/profile :test/derived)
        a' (tree-of envelope)
        ;; the root is `(then prelude run)`: its one operand is the prelude
        prelude-id (fn [{:keys [root rows]}] (first (nth (get rows root) 3)))]
    (is (nil? (vm/validate-rows a')))
    (testing "A, A' and the record: one address on every host (JVM goldens)"
      (is (= :segment/blake3-a9678aef1f8b7d6638195cdad9579ef7cc4a6a2f4d05c12554fa082698ba47ee
             (:root a)))
      (is (= :segment/blake3-aff24c9fce6b4e5657f17432d0da99149fea0b17fd9a976439005bd135c28955
             (:root a')))
      (is (= :segment/blake3-6d96d27e7f17e8968b3643b80fa31484a48251a9ce3499b5e7888e21f5c6a270
             record-address)))
    (testing "the bundled prelude is the same subtree in A and A'"
      (is (= (prelude-id a) (prelude-id a')))
      (is (= :segment/blake3-535f1cbe009fcfa62067baa2e1be6a4031d86e15b3a4d7e222760557b7f0d535
             (prelude-id a))))
    (testing "decoding A and projecting it again keeps every address"
      (let [decoded (cbor/decode (jing/canonical-bytes a))]
        (is (= (:root a) (:root decoded)))
        (is (= (jing/segment-key a) (jing/segment-key decoded)))
        ;; addresses, not `=`: on JS a bare 0.5 decodes to the carrier,
        ;; the same float64 content, which a bare Number is never `=` to
        (is (= (jing/segment-key a)
               (jing/segment-key (vm/ast->semantic-bytecode
                                   (vm/semantic-bytecode->ast decoded)))))))))


;; =============================================================================
;; Execution
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (integer/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300}))})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  {:ast-walker (fn [ast] (vm/value (vm/eval (tu/create-vm opts) ast))),
   :semantic (fn [ast]
               (vm/value (vm/run (load-semantic-ast (semantic/create-vm opts)
                                                    (vm/ast->datoms ast))))),
   :stack (fn [ast]
            (vm/value
              (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                     (assoc opts
                                            :contract vm/stack-contract))))),
   :register (fn [ast]
               (vm/value
                 (vm/run
                   (rvm/create-vm
                     (:image (rc/adapt (vm/ast->datoms ast)))
                     (assoc opts :contract vm/register-contract)))))})


(defn- outputs
  [ast]
  (into {}
        (map (fn [[k run]]
               [k (try (render/output (run ast))
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e)]))]))
        runners))


(deftest runs-on-every-vm-test
  (let [a (tree-of (projected))]
    (testing "A as projected"
      (doseq [[k out] (outputs (vm/semantic-bytecode->ast a))]
        (is (= {:py/out [printed], :py/exception nil} out) (str k))))
    (testing "A decoded from its canonical bytes: the float64 content the
              decoder hands back crosses into arithmetic through
              data/float-value"
      (doseq [[k out] (outputs (vm/semantic-bytecode->ast
                                 (cbor/decode (jing/canonical-bytes a))))]
        (is (= {:py/out [printed], :py/exception nil} out) (str k))))))


(defn- refusal-in
  "The dao.jing.cbor refusal class in `e` or any of its causes, else nil."
  [e]
  (loop [e e]
    (when e
      (or (cbor/refusal e) (recur (ex-cause e))))))


(deftest decoded-float-under-bare-plus-test
  ;; The documented non-portability: generic yin has no float seam. A
  ;; decoded 2.0 that meets the standard `+` throws Jing's carrier-coercion
  ;; refusal on Node (the carrier refuses numeric coercion) and computes 3.0
  ;; on the JVM and Dart, where float64 content is a host double. Only a
  ;; profile with an explicit seam, Python's data/float-value, computes on
  ;; floats portably.
  (let [ast (vm/semantic-bytecode->ast
              (cbor/decode
                (jing/canonical-bytes
                  (vm/ast->semantic-bytecode
                    (u/mark-tails
                      (u/app (u/lam '[acc]
                                    (u/app (u/v '+) (u/v 'acc) (u/lit 1)))
                             (u/lit (cbor/float64 2.0))))))))]
    (doseq [[k run] runners]
      (let [r (try (run ast)
                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                     [:thrown (or (refusal-in e) (ex-message e))]))]
        #?(:cljs (is (= [:thrown :carrier-coercion] r) (str k))
           :default (is (= 3.0 r) (str k)))))))


(deftest dict-keys-test
  (testing "1, 1.0 and True key one entry, -0.0 keys as 0, and 0.5 keys as
            1/2: ruling-6 decimal-string keys, the same bytes on every host"
    (let [run (fn [form]
                (into {}
                      (map (fn [[k r]]
                             [k (try (r (u/mark-tails
                                          (u/then prelude/functions-uast
                                                  (u/sexp->uast form))))
                                     (catch #?(:cljd Object :clj Exception
                                               :cljs :default) e
                                       [:thrown (ex-message e)]))]))
                      runners))
          form (list 'py/conj
                     (list 'py/conj
                           (list 'py/conj
                                 (list 'py/conj [] '(py/key true))
                                 (list 'py/key {:py/float (cbor/float64 1)}))
                           (list 'py/key {:py/float (cbor/float64 (neg-zero))}))
                     (list 'py/key {:py/float (cbor/float64 0.5)}))]
      (doseq [[k result] (run form)]
        (is (= [[:py.numeric/finite "1" "1"] [:py.numeric/finite "1" "1"]
                [:py.numeric/finite "0" "1"] [:py.numeric/finite "1" "2"]]
               result)
            (str k))
        (is (= (str "hIPYG4JwZGFvLmppbmcva2V5d29yZIJqcHkubnVtZXJpY2ZmaW5pdGVh"
                    "MWExg9gbgnBkYW8uamluZy9rZXl3b3JkgmpweS5udW1lcmljZmZpbml0"
                    "ZWExYTGD2BuCcGRhby5qaW5nL2tleXdvcmSCanB5Lm51bWVyaWNmZmlu"
                    "aXRlYTBhMYPYG4JwZGFvLmppbmcva2V5d29yZIJqcHkubnVtZXJpY2Zm"
                    "aW5pdGVhMWEy")
               (b64 result))
            (str k))))))


;; =============================================================================
;; NaN
;; =============================================================================

(def ^:private nan-program
  "print(1e309 - 1e309, 1e309, -1e309)\n: an overflowing literal is inf,
   and inf - inf is NaN"
  (packet
    [:file_input
     (print-call [(binary (num-expr "1e309") "MINUS" "-" (num-expr "1e309"))
                  (num-expr "1e309")
                  [:expr ["MINUS" "-"] (num-expr "1e309")]])
     ["EOF" "<EOF>"]]))


(deftest nan-test
  (let [nan (cbor/float64 ##NaN)
        ;; inf - inf: a NaN the host computed, whatever sign and payload
        ;; bits it carries
        computed (cbor/float64 (- ##Inf ##Inf))]
    (testing "repr, and one quiet NaN in the canonical bytes (JVM golden)"
      (is (= "nan" (render/float-repr nan)))
      (is (= "nan" (render/float-repr computed)))
      (is (= "nan" (render/float-repr
                     (cbor/decode (jing/canonical-bytes nan)))))
      (is (= "2BuCcGRhby5qaW5nL2Zsb2F0NjRIf/gAAAAAAAA=" (b64 nan)))
      (is (= (b64 nan) (b64 computed)))
      (is (not= (jing/segment-key nan) (jing/segment-key (cbor/float64 1)))))
    (testing "py/finite? on every VM: false for NaN and both infinities"
      (let [form '(py/conj
                    (py/conj
                      (py/conj (py/conj [] (py/finite? 1.5))
                               (py/finite? (data/float-value ##Inf)))
                      (py/finite? (data/float-value ##-Inf)))
                    (py/finite? (- (data/float-value ##Inf)
                                   (data/float-value ##Inf))))]
        (doseq [[k run] runners]
          (is (= [true false false false]
                 (try (run (u/mark-tails (u/then prelude/functions-uast
                                                 (u/sexp->uast form))))
                      (catch #?(:cljd Object :clj Exception :cljs :default) e
                        [:thrown (ex-message e)])))
              (str k)))))
    (testing "a program that computes NaN prints nan on every VM"
      (let [[_ [[_ e]]] (lower/lower-transform {} nan-program)
            ast (vm/semantic-bytecode->ast (tree-of (encoder/project e)))]
        (doseq [[k out] (outputs ast)]
          (is (= {:py/out ["nan inf -inf"], :py/exception nil} out)
              (str k)))))))


;; =============================================================================
;; Carrier lifecycle: lift, pin, heap, closure and continuation images
;; =============================================================================

(defn- specials
  "Float64 content the lifecycle must keep byte for byte: integral, both
   zeros, NaN and both infinities."
  []
  [(cbor/float64 1) (cbor/float64 2.0) (cbor/float64 0)
   (cbor/float64 (neg-zero)) (cbor/float64 ##NaN) (cbor/float64 ##Inf)
   (cbor/float64 ##-Inf)])


(defn- cell-ref
  [id]
  {:type :cell-ref, :id id, :seal :s})


(deftest lift-admits-float64-content-test
  (let [fs (specials)
        names (mapv #(symbol (str "x" %)) (range (count fs)))
        child {:store (assoc (zipmap names fs) 'v fs)}
        lifted (engine/lift-slice child :segment/own (conj names 'v))]
    (doseq [[n f] (map vector names fs)]
      (is (= (b64 f) (b64 (get-in lifted [:slice n]))) (str n)))
    (is (= (b64 fs) (b64 (get-in lifted [:slice 'v]))))
    (is (= {} (:cells lifted)) "no cell")))


(deftest pin-and-heap-admit-float64-content-test
  (let [fs (specials)
        state {:heap {:c1 {:value fs, :seal :s},
                      :c2 {:value [(first fs) (cell-ref :c1)], :seal :s},
                      :dead {:value (second fs), :seal :s}},
               :store {'a (cell-ref :c2), 'n (nth fs 3)}}]
    (testing "a float is a leaf of the pin walk"
      (doseq [f fs]
        (is (identical? state (engine/pin-refs state f))))
      (is (= #{:c1}
             (get-in (engine/pin-refs state {:n (nth fs 4), :r (cell-ref :c1)})
                     [:gc :pinned]))))
    (testing "the heap trace keeps reachable float cells, bytes unchanged"
      (let [swept (engine/collect state [])]
        (is (= #{:c1 :c2} (set (keys (:heap swept)))))
        (is (= (b64 fs) (b64 (get-in swept [:heap :c1 :value]))))))))


(def ^:private kernels
  "Per VM: compile an AST to the image the kernel loads, and load it."
  {:ast-walker {:image vm/ast->semantic-bytecode,
                :load (fn [image o]
                        (walker/vm-load-rows (walker/create-vm o) image
                                             vm/ast-contract))},
   :semantic {:image (fn [ast]
                       (:vector (linearize/lower-rows
                                  (vm/ast->semantic-bytecode ast)))),
              :load (fn [image o]
                      (semantic/load-vector (semantic/create-vm o) image
                                            vm/semantic-contract))},
   :stack {:image (fn [ast] (:image (dl/adapt (vm/ast->datoms ast)))),
           :load (fn [image o]
                   (dvm/create-vm image
                                  (assoc o :contract vm/stack-contract)))},
   :register {:image (fn [ast]
                       (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                   ast))))),
              :load (fn [image o]
                      (rvm/create-vm image
                                     (assoc o :contract
                                            vm/register-contract)))}})


(defn- define
  [sym val]
  (u/app (u/v 'yin/def) (u/lit sym) val))


(deftest closure-and-continuation-images-keep-float64-content-test
  (testing "a continuation whose environment holds every special float:
            the machine payload admits it and keeps their bytes (a reified
            continuation does not lift: UCF refuses it as
            :non-canonicalizable, whatever it holds)"
    (doseq [[k {:keys [image load]}] kernels]
      (let [capture-in-env (u/app (u/lam '[c]
                                         {:type :vm/current-continuation})
                                  (u/lit (specials)))
            a (vm/run (load (image (define 'k capture-in-env)) opts))
            c (get (:store a) 'k)]
        (is (values/continuation? c) (str k))
        (is (vm/machine-data? c) (str k))
        (is (= (set (map b64 (specials)))
               (set (map b64 (filter cbor/float64?
                                     (tree-seq coll? seq (values/payload c))))))
            (str k)))))
  (testing "a closure whose environment holds every special float: the
            machine payload admits it, a lift keeps their bytes, and the
            closure lowered into another task returns them unchanged, on
            every VM"
    (let [fs (specials)
          task-a (assoc opts :capability-secret "task-a")
          task-b (assoc opts :capability-secret "task-b")
          module-ast (define 'f (u/app (u/lam '[c] (u/lam [] (u/v 'c)))
                                       (u/lit fs)))]
      (doseq [[k {:keys [image load]}] kernels]
        (let [module-image (image module-ast)
              a (vm/run (load module-image task-a))
              f (get (:store a) 'f)]
          (is (values/closure? f) (str k))
          (is (vm/machine-data? f) (str k))
          (let [lifted (engine/lift-slice a :segment/own ['f])
                floats (filter cbor/float64? (tree-seq coll? seq lifted))]
            (is (= (set (map b64 fs)) (set (map b64 floats))) (str k))
            (let [b (load (image (u/app (u/v 'f))) task-b)
                  received (engine/receive-module
                             b 'm (assoc lifted :images
                                         {(module/image-identity
                                            a module-image)
                                          module-image}))
                  b (assoc-in received [:store 'f]
                              (get-in (module/resolve-module
                                        (:modules received) 'm)
                                      [:bindings 'f]))]
              (is (= (b64 fs) (b64 (vm/value (vm/run b)))) (str k)))))))))
