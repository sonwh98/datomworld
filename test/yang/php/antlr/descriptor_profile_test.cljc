(ns yang.php.antlr.descriptor-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [yang.core.semantics :as semantics]
            [yang.core.uast :as u]
            [yang.frontend :as frontend]
            [yang.php.antlr.contract :as contract]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.profiles :as profiles]
            [yang.php.antlr.linked-harness :as linked]
            [yang.php.antlr.portable-harness :as portable]
            #?@(:cljd [] :jolt []
                :default [[yang.php.antlr.cst :as cst]
                          [yin.repl :as repl]
                          [yin.repl.frontends :as frontends]])
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.semantic :as semantic]))

(def ^:private composition
  (delay (let [store (mem/create-content-mem)
               prepared (profiles/prepare! store [contract/clojure-id])]
           (assoc prepared :source
                  (link/composition {:content-store store :name-env (:pins prepared)
                                     :derivation :trusted})))))

(defn- descriptor [id] (:descriptor (profiles/builtin id)))

(defn- refusal [f]
  (try (f) nil
       (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))

(deftest validates-frozen-entry-and-exact-roles
  (let [{:keys [artifacts registry contract]} @composition
        legacy (descriptor contract/legacy-id)
        persistent (descriptor contract/clojure-id)
        validate #(semantics/validate-descriptor %1 %2 artifacts registry)]
    (is (= legacy (:descriptor (profiles/verify-builtin! contract/legacy-id))))
    (is (= persistent (validate persistent contract)))
    (is (= 'php/run-main (get-in legacy [:yang.semantics/hooks :unit/run-main])))
    (is (= #{2 4} (get-in contract [:entry-lowering contract/legacy-id :call-shapes])))
    (doseq [target ['add 'clj.php/neg 'clj.php/sub 'cell/dsg__new-provenance
                    'clojure.core.impl/impl-get 'php/add 'clj.php/echo]]
      (is (= :refused
             (:status (refusal #(validate (assoc-in persistent [:yang.semantics/hooks :op/add] target)
                                          contract))))
          (str target)))
    (is (= [:body-fn :request-stream] (contract/entry-operands contract/legacy-id 2)))
    (is (= [:body-fn :request-stream :response-stream :diagnostics-stream]
           (contract/entry-operands contract/legacy-id 4)))
    (is (= {:defect :arity :call-shape 3}
           (select-keys (refusal #(contract/entry-operands contract/legacy-id 3))
                        [:defect :call-shape])))))

(defn- new-stream [] (:dao.stream/handle (portable/make-stream)))

(defn- drain [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)), records []]
    (let [{:dao.stream/keys [outcome cursor value]} (stream/next handle cursor)]
      (if (= :dao.stream/ok outcome)
        (recur cursor (conj records value))
        records))))

(defn- entry-application [ast export]
  (some #(when (= (u/v export) (:operator %)) %)
        (tree-seq #(#{:application :lambda :if} (:type %))
                  (fn [{:keys [type operator operands body condition then else]}]
                    (case type :application (cons operator operands)
                          :lambda [body] :if [condition then else]))
                  ast)))

(deftest portable-packet-lowers-and-emits-in-both-revisions
  (let [number (fn [n] [:expression [:constant [:literalConstant [:numericConstant ["Decimal" n]]]]])
        expr [:expression (number "1") ["Plus" "+"] (number "2")]
        packet (portable/packet
                 [:htmlDocument [:phpBlock [:topStatement [:statement
                    [:echoStatement ["Echo" "echo"] [:expressionList expr] ["SemiColon" ";"]]]]]
                  ["EOF" "<EOF>"]])]
    (doseq [id [contract/legacy-id contract/clojure-id]]
      (let [v (portable/create-vm {:modules (profiles/registry)})
            response (new-stream)
            [ref v] (engine/attach-resource v response)
            ast (lower/lower-packet packet {:descriptor (descriptor id)
                                            :response-node (portable/operand-node ref)})
            result (vm/value (vm/eval v ast))]
        (is (= "3" (:php/out result)))
        (is (= [{:php.response/body "3"}] (drain response)))))
    (let [legacy (descriptor contract/legacy-id)
          request (u/lit :request-stream)
          response (u/lit :response-stream)
          diag (u/lit :diagnostic-stream)]
      (doseq [[opts trailing] [[{} [request]]
                               [{:response-node response :diag-node diag} [request response diag]]]]
        (let [ast (lower/lower-packet packet
                                      (merge {:prelude :linked :descriptor legacy :request-node request} opts))
              entry (entry-application ast 'php/run-main)]
          (is (= trailing (subvec (:operands entry) 1))))))))

#?(:cljd nil :jolt nil :default
   (do
(deftest capability-refusals-have-no-program
  (doseq [[cap source] [[:oo/classes "class C {}"]
                        [:control/generators "function f() { yield 1; }"]
                        [:control/except-typed "try {} catch (Exception $e) {}"]
                        [:calls/defaults "function f($x = 1) {}"]
                        [:calls/kwargs "f(x: 1);"]
                        [:calls/kwstar "f(...[1]);"]
                        [:calls/references "function f(&$x) {}"]
                        [:mutation/subscript "$a[0] = 1;"]
                        [:mutation/attribute "$a->p = 1;"]
                        [:strings/binary "echo \"\\xFF\";"]
                        [:mutation/delete "unset($a);"]
                        [:destructuring/unpack "[$x] = [1];"]
                        [:modules/import "include 'lib.php';"]]]
    (testing source
      (let [packet (cst/parse-source (str "<?php " source))
            [_ outputs] (lower/lower-transform {:descriptor (descriptor contract/clojure-id)} packet)
            [[_ diagnostic]] outputs]
        (is (= [:diagnostics] (mapv first outputs)))
        (is (= {:status :refused :reason :yang.semantics/unsupported-capability
                :capability cap :semantics-id contract/clojure-id}
               (select-keys diagnostic [:status :reason :capability :semantics-id])))
        (is (int? (:node diagnostic)))))))

(deftest unset-call-refuses-when-delete-capability-is-admitted
  (let [admitted (assoc-in (descriptor contract/clojure-id)
                           [:yang.semantics/capabilities :mutation/delete] :supported)
        packet (cst/parse-source "<?php echo unset($a);")
        [_ outputs] (lower/lower-transform {:descriptor admitted} packet)
        [[_ diagnostic]] outputs]
    (is (= [:diagnostics] (mapv first outputs)))
    (is (= {:yang.php/diagnostic :yang.php/unsupported :construct :unset}
           (select-keys diagnostic [:yang.php/diagnostic :construct])))))

(def ^:private programs
  [["echo 1 + 2, '|', 7 - 4, '|', 2 * 3, '|', 5 / 2, '|', -7 % 3, '|', 2 ** 10, '|', 2 ** -2, '|', 1.5 + 2, '|', 0x20000000000000 + 1;"
    "3|3|6|2.5|-1|1024|0.25|3.5|9007199254740993"]
   ["echo 1 == 1.0, '|', 1 === 1, '|', true == 1, '|', [0.0] == [-0.0], '|', 9007199254740993 > 9007199254740992.0, '|', [NAN] == [NAN];"
    "false|true|false|true|true|false"]
   ["$m = ['z' => 'Z', null => 'nil', 0 => 'zero', 'a' => 'A']; echo str(keys($m)), '|', str($m), '|', $m[null];"
    "(0 \"a\" \"z\" nil)|{0 \"zero\", \"a\" \"A\", \"z\" \"Z\", nil \"nil\"}|nil"]
   ["function count($x) { return $x + 1; } function f($n, ...$xs) { $n += get($xs, 0); return count($n); } $factor = 2; $double = fn($x) => $x * $factor; $factor = 9; echo f(2, 3), '|', $double(4), '|', is_null(null);"
    "6|8|true"]
   ["$x = 'captured'; $f = function() use ($x) { function inner() { return $x; } echo $x; }; $f(); echo '|', is_null(inner());"
    "captured|true"]
   ["$n = null; $f = false; $z = 0; $s = ''; echo isset($missing), '|', isset($n), '|', isset($f, $z, $s), '|'; function local_isset($x) { $n = null; return [isset($missing), isset($n), isset($x)]; } echo str(local_isset(false));"
    "false|false|true|[false false true]"]
   ["$m = [keyword('k') => 'π', 9007199254740992 => 'int']; echo $m[keyword('k')], '|', $m[0x20000000000000], '|', \"λ\";"
    "π|int|λ"]
   ["try { echo 'body'; throw 'boom'; } finally { echo '-finally'; }"
    "body-finally" "boom"]
   ["echo [1.0 => 'bad'];" "" {:clj.php/error :unsupported-key}]
   ["$a = [1 => 'int']; echo $a[1.0];" "" {:clj.php/error :unsupported-key}]
   ["echo 1 / 0;" "" {:clj.php/error :zero-division}]
   ["echo 1 + true;" "" {:clj.php/error :type-error}]])

(defn- compile-program
  ([mode source] (compile-program mode source {}))
  ([mode source opts]
   (lower/lower-packet (cst/parse-source (str "<?php " source))
                       (merge {:prelude mode :descriptor (descriptor contract/clojure-id)} opts))))

(defn- run-program [backend mode ast]
  (let [{:keys [registry source]} @composition
        pair (link/make-pair)
        task ((linked/backends backend) ast (linked/composition registry pair))]
    (vm/value (if (= :linked mode) (first (linked/drive task pair source)) (vm/run task)))))

(deftest persistent-profile-end-to-end
  (doseq [[source out uncaught] programs
          mode [:bundled :linked]
          :let [ast (compile-program mode source)]
          backend [:semantic :stack :register]]
    (testing (str mode " " backend " " source)
      (let [result (run-program backend mode ast)]
        (is (= out (:php/out result)))
        (is (= (if (= "" out) [] [{:php.response/body out}]) (:php/response result)))
        (is (= [] (:php/diag result)))
        (is (= uncaught (cond-> (:php/uncaught result)
                          (map? uncaught) (select-keys (keys uncaught)))))))))

(deftest emits-body-to-an-attached-response-stream
  (let [{:keys [registry source]} @composition]
    (doseq [mode [:bundled :linked]
            printed ["'observable'" "''"]]
      (let [pair (link/make-pair)
            task (semantic/create-vm (linked/composition registry pair))
            response (new-stream)
            diagnostics (new-stream)
            [response-ref task] (engine/attach-resource task response)
            [diag-ref task] (engine/attach-resource task diagnostics)
            ast (compile-program mode (str "echo " printed ";")
                                 {:response-node (portable/operand-node response-ref)
                                  :diag-node (portable/operand-node diag-ref)})
            task (semantic/load-ast task (vm/ast->datoms ast) vm/ast-contract)
            [done _] (linked/drive task pair source)
            expected (if (= printed "''") [] [{:php.response/body "observable"}])]
        (is (= expected (drain response)))
        (is (= expected (:php/response (vm/value done))))
        (is (= [] (drain diagnostics)))))))

(deftest normal-entry-selects-the-revision-from-its-catalog-snapshot
  (let [catalog (frontends/standard)
        state (repl/create-state {:frontends catalog :lang :yang/php})
        request (:binding (frontend/select catalog {:yang.frontend/id :yang/php}))
        switched (assoc-in catalog [:yang.frontend/preferred :yang/php] "php-v1")
        explicit (:binding (frontend/select catalog {:yang.frontend/id :yang/php
                                                     :yang.frontend/revision "php-v1"}))
        source "<?php echo 1 == 1.0;"
        legacy-ast ((:lower explicit) ((:parse explicit) source))
        legacy-vm (portable/create-vm {:modules (portable/host-registry)})
        [compiled result] (repl/eval-input state source)
        derivation (val (first (:frontend-derivations compiled)))]
    (is (= ["clojure-core-v1" "php-v1"] (frontend/revisions catalog :yang/php)))
    (is (= "false" result))
    (is (= "1" (:php/out (vm/value (vm/eval legacy-vm legacy-ast)))))
    (is (= "php-v1" (get-in (frontend/select switched {:yang.frontend/id :yang/php})
                            [:manifest :yang.frontend/revision])))
    (is (= "false" (second (repl/eval-input compiled source))))
    (is (= "false" (:php/out (run-program :semantic :bundled
                                          ((:lower request) ((:parse request) source))))))
    (is (= "clojure-core-v1" (:yang.frontend/revision derivation)))
    (is (= (:closure (profiles/builtin contract/clojure-id))
           (:yang.frontend/runtime-closure derivation)))
    (is (every? jing/segment-address?
                (map derivation [:yang.frontend/lowering-profile :yang.frontend/runtime-profile
                                 :yang.frontend/input :yang.frontend/output
                                 :yang.frontend/ambient-resolver])))))
))
