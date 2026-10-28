(ns yang.php.antlr.include-test
  "P6 S1/S2: include/require over the linker. The P6 fixtures run linked on
   the three vector VMs (the AST walker cannot link `php` until L-b); the
   pure path rules, the publication order and the two missing stages have
   their own tests."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.linked-harness :as lh]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.programs-p6 :as p6]
    [yang.php.antlr.publication :as publication]
    [yin.vm :as vm]))


(defn- parse-units
  [{:keys [units source], :or {units nil}}]
  (if units
    (update-vals units cst/parse-source)
    {"main.php" (cst/parse-source source)}))


(defn- observed
  "`[out diag uncaught]` of the linked run `value`."
  [value]
  [(h/latin1->text (:php/out value))
   (mapv (juxt :errno :message) (:php/diag value))
   (some-> (:php/uncaught value) (select-keys [:class :message]))])


(defn- run
  [fixture]
  (lh/run-fixture (parse-units fixture) "main.php" {:computed? (:computed fixture)}))


(deftest ^:slow p6-fixtures-linked-on-every-vm-test
  (doseq [{:keys [id out diag uncaught warnings oracle] :as fixture} p6/enabled
          :when (contains? fixture :out)
          :let [r (try (run fixture)
                       (catch Throwable e
                         (println "FIXTURE THREW" id (ex-message e) (pr-str (ex-data e)))
                         {:values {}}))]]
    (testing (name id)
      (is (not= :banner oracle))
      (doseq [[k v] (:values r)]
        (is (= [out (vec diag) uncaught] (observed v)) (str (name id) " on " k)))
      (when warnings
        (is (= warnings (mapv :yang.php/diagnostic (:warnings r))) (name id))))))


(deftest unit-path-rules-test
  (testing "normalization"
    (is (= {:path "x.php"} (lower/normalize-unit-path "./x.php")))
    (is (= {:path "a/b.php"} (lower/normalize-unit-path "a//./c/../b.php")))
    (is (= {:error :include-path} (lower/normalize-unit-path "../x.php")))
    (is (= {:error :include-path} (lower/normalize-unit-path "/etc/x.php")))
    (is (= {:error :unit-path} (lower/normalize-unit-path "a+b.php")))
    (is (= {:error :unit-path} (lower/normalize-unit-path "a$b.php"))))
  (testing "the module name is injective"
    (is (= 'phpm.src$lib$util$$php (lower/path-module-symbol "src/lib/util.php")))
    (is (not= (lower/path-module-symbol "a/b.php") (lower/path-module-symbol "a/b")))
    (is (= 'phpm.a$$b$$php (lower/path-module-symbol "a.b.php")))))


(defn- edges
  [source path]
  (lower/imported-unit-names (cst/parse-source source) {:path path}))


(deftest folded-include-edges-test
  (is (= ["lib/a.php"] (edges "<?php include __DIR__ . '/lib/a.php';" "main.php")))
  (is (= ["lib/b.php"] (edges "<?php require_once dirname(__FILE__) . '/b.php';" "lib/a.php")))
  (is (= ["x.php" "y.php"] (edges "<?php include 'x.php'; if (1) { include_once \"y.php\"; }" "main.php")))
  (is (= [] (edges "<?php include $p; include 'a.php' . $q;" "main.php")))
  (is (= ["lib/b.php"] (edges "<?php include dirname(__DIR__) . '/lib/b.php';" "sub/a.php"))))


(deftest include-refusals-test
  (testing "a bundled program cannot include"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)include"
          (lower/lower-program (cst/parse-source "<?php include 'a.php';")))))
  (testing "an escaping or absolute literal path"
    (doseq [src ["<?php include '../x.php';" "<?php include '/etc/x.php';"]]
      (is (= :include-path
             (try (lower/lower-program (cst/parse-source src) {:prelude :linked})
                  (catch clojure.lang.ExceptionInfo e (:construct (ex-data e)))))
          src)))
  (testing "an include inside a function waits for the dynamic-scope profile"
    (is (= :include-in-function
           (try (lower/lower-program (cst/parse-source "<?php function f() { include 'a.php'; }")
                                     {:prelude :linked})
                (catch clojure.lang.ExceptionInfo e (:construct (ex-data e))))))))


(deftest computed-path-warning-test
  (let [{:keys [warnings]} (lower/lower-program (cst/parse-source "<?php include $p;") {:prelude :linked})]
    (is (= [:yang.php/computed-include-path] (mapv :yang.php/diagnostic warnings)))
    (is (= [:warning] (mapv :severity warnings)))))


(deftest ^:slow linked-profile-matches-bundled-test
  (let [src "<?php function f($n) { return $n < 2 ? $n : f($n - 1) + f($n - 2); } echo f(10); var_dump([1, 'a' => 2.5]);"
        bundled (h/run-php src)
        linked (lh/run-fixture {"main.php" (cst/parse-source src)})]
    (doseq [[k v] (:values linked)]
      (is (= (:semantic bundled) (h/render v)) (str k)))))


(deftest publication-order-test
  (let [u (fn [name src] {:name name, :packet (cst/parse-source src)})
        units [(u "main.php" "<?php include 'a.php'; include 'c.php';")
               (u "a.php" "<?php include 'b.php';")
               (u "b.php" "<?php echo 1;")
               (u "c.php" "<?php echo 2;")]]
    (testing "dependency first"
      (let [order (:order (publication/publication-order units {}))]
        (is (< (.indexOf order "b.php") (.indexOf order "a.php") (.indexOf order "main.php")))))
    (testing "a cyclic pair is one step"
      (let [cyc [(u "a.php" "<?php include 'b.php';") (u "b.php" "<?php include 'a.php';")]]
        (is (= [["a.php" "b.php"]] (:order (publication/publication-order cyc {}))))))
    (testing "an unpublished edge is refused by name and nothing is written"
      (let [bad [(u "main.php" "<?php include 'missing.php';")]
            r (publication/publication-order bad {})]
        (is (= :refused (:status r)))
        (is (= :yang.php/unpublished-import (:reason r)))
        (is (= "missing.php" (:module r)))
        (is (= "main.php" (:imported-by r)))))))


(defn- unserved-source
  "A serving composition whose store lacks the module published for `path`:
   the name resolves, the content is absent."
  [result path]
  (let [env (dissoc (lh/name-env result) (lower/path-module-symbol path))]
    (lh/source (assoc env (lower/path-module-symbol path) "no-such-address"))))


(deftest ^:slow missing-stage-refusals-are-data-test
  (testing "all four kinds against a pinned-but-unserved target"
    (doseq [[kind src texts diag-n err?]
            [["include" "<?php var_dump(include 'a.php');" "bool(false)\n" 2 false]
             ["include_once" "<?php var_dump(include_once 'a.php');" "bool(false)\n" 2 false]
             ["require" "<?php try { require 'a.php'; } catch (Error $e) { echo get_class($e); }" "Error" 1 true]
             ["require_once" "<?php try { require_once 'a.php'; } catch (Error $e) { echo get_class($e); }" "Error" 1 true]]]
      (let [units {"main.php" (cst/parse-source src), "a.php" (cst/parse-source "<?php echo 'a';")}
            result (lh/publish-units! [{:name "a.php", :packet (get units "a.php")}])
            {:keys [ast]} (lower/lower-program (get units "main.php") {:prelude :linked})
            src' (unserved-source result "a.php")]
        (doseq [k (keys lh/backends)
                :let [[out diag] (observed (vm/value (lh/run-linked k ast src')))]]
          (is (= texts out) (str kind " " k))
          (is (= diag-n (count diag)) (str kind " " k))
          (is (every? #(.startsWith ^String (second %) (str kind "(")) diag) (str kind " " k " names the kind"))
          (when err? (is (= 1 (count diag))))))))
  (testing "a refusal is never marked: the retry warns again"
    (let [units {"main.php" (cst/parse-source (str "<?php try { require_once 'a.php'; } catch (Error $e) { echo 'E'; }"
                                                   " var_dump(include_once 'a.php');")),
                 "a.php" (cst/parse-source "<?php echo 'a';")}
          result (lh/publish-units! [{:name "a.php", :packet (get units "a.php")}])
          {:keys [ast]} (lower/lower-program (get units "main.php") {:prelude :linked})
          [out diag] (observed (vm/value (lh/run-linked :semantic ast (unserved-source result "a.php"))))]
      (is (= "Ebool(false)\n" out))
      (is (= 3 (count diag))))))
