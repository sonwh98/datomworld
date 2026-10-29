(ns yang.php.antlr.zz-probe-test
  "Throwaway probe: run P6 fixtures of the groups in PROBE_GROUPS on the
   semantic VM and print out/expected. Deleted before the report."
  (:require
    [clojure.test :refer [deftest is]]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.linked-harness :as lh]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.programs-p6 :as p6]
    [yin.vm :as vm]))


(defn- observed
  [value]
  [(h/latin1->text (:php/out value))
   (mapv (juxt :errno :message) (:php/diag value))
   (some-> (:php/uncaught value) (select-keys [:class :message]))])


(defn- dump
  [pk n d]
  (println (apply str (repeat d "  ")) (or (:rule n) (str (:type n) " " (pr-str (:text n)))))
  (doseq [k (yang.antlr.packet/children pk n)] (dump pk k (inc d))))


(deftest dump-test
  (let [pk (cst/parse-source "<?php namespace A; use B\\C as D; echo 1;")]
    (dump pk (yang.antlr.packet/root pk) 0)))


(deftest probe
  (let [groups (set (map keyword (re-seq #"[a-z0-9-]+" "namespaces-v1 constants-v1 autoload-v1")))
        ids (some-> (System/getenv "PROBE_IDS") (clojure.string/split #",") (->> (map keyword) set))]
    (doseq [{:keys [id group units source out diag] :as fx} p6/all-fixtures
            :when (and (contains? groups group) (or (nil? ids) (contains? ids id)))]
      (let [packets (if units (update-vals units cst/parse-source) {"main.php" (cst/parse-source source)})
            r (try
                (let [others (mapv (fn [[path packet]] {:name path, :packet packet}) (dissoc packets "main.php"))
                      result (lh/publish-units! others)
                      {:keys [ast warnings]} (lower/lower-program (get packets "main.php")
                                                                  {:prelude :linked,
                                                                   :path "main.php",
                                                                   :paths (when (:computed fx) (keys (:addresses result)))})
                      src (lh/source (lh/name-env result))]
                  {:obs (observed (vm/value (lh/run-linked :semantic ast src))), :warnings warnings})
                (catch Throwable e {:threw (str (ex-message e) " " (pr-str (ex-data e)))}))]
        (println "==" id (if (and (:obs r) (= out (first (:obs r)))) "OK" "DIFF"))
        (when-not (and (:obs r) (= out (first (:obs r))))
          (println "   expected:" (pr-str out) (pr-str diag))
          (println "   actual:  " (pr-str (or (:obs r) (:threw r)))))
        (is true)))))
