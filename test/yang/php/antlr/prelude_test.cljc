(ns yang.php.antlr.prelude-test
  "The PHP prelude's static contract on every host: the bundled program is
   closed over its own definitions, the declared host names and the
   primitives; it never reaches `dao.host.*`; the lowered program names no
   runtime key; the module emitter's exports and manifest cover the same
   list."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.packets :as packets]
    [yang.php.antlr.portable-harness :as h]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]))


(def ^:private closed-names
  (into (conj (prelude/defined-keys prelude/uast) 'yin/def)
        (concat prelude/host-names (keys vm/primitives))))


(deftest bundled-prelude-is-closed-test
  (is (empty? (remove closed-names (prelude/free-names prelude/uast)))))


(deftest prelude-reaches-no-dao-host-test
  (is (not-any? #(str/starts-with? (str %) "dao.host") (prelude/free-names prelude/uast)))
  (is (every? #(contains? #{"cell" "data" "integer"} (namespace %))
              prelude/host-names)))


(deftest lowered-program-names-no-runtime-key-test
  (doseq [[id form] packets/forms
          :let [free (prelude/free-names (lower/lower-module-body (h/packet form)))]]
    (is (empty? (remove closed-names free)) (name id))
    (is (not-any? #(= "php.rt" (namespace %)) free) (name id))))


(deftest module-exports-cover-the-definitions-test
  (is (contains? prelude/module-exports 'init!))
  (is (contains? prelude/module-exports 'echo))
  (is (not-any? #(= "php.rt" (namespace %)) prelude/module-exports))
  (is (every? symbol? prelude/runtime-keys)))


(deftest admit-refuses-a-registry-without-the-host-names-test
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljd Object)
        (prelude/admit {}))))


(deftest module-spec-declares-every-free-name-test
  (testing "over the real host registry the manifest builds, or refuses"
    (let [spec (prelude/module-spec (h/host-registry))]
      (is (= 'php (:name spec)))
      (is (= prelude/module-exports (:exports spec)))
      (is (every? (:primitives spec) (filter #(= "integer" (namespace %)) prelude/host-names))))))
