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
  (let [groups (set (map keyword (re-seq #"[a-z0-9-]+" (slurp "test/yang/php/antlr/zz_probe1.cfg"))))
        ids (some-> nil (clojure.string/split #",") (->> (map keyword) set))]
    (doseq [{:keys [id group units source out diag] :as fx} p6/all-fixtures
            :when (and (contains? groups group) (or (nil? ids) (contains? ids id)))]
      (let [packets (if units (update-vals units cst/parse-source) {"main.php" (cst/parse-source source)})
            r (try
                (let [others (cond-> (mapv (fn [[path packet]] {:name path, :packet packet}) (dissoc packets "main.php"))
                               (:class-map fx) (conj (assoc (:class-map fx) :name lower/classmap-name)))
                      result (lh/publish-units! others)
                      {:keys [ast warnings]} (lower/lower-program (get packets "main.php")
                                                                  {:prelude :linked,
                                                                   :path "main.php",
                                                                   :class-map (boolean (:class-map fx))
                                                                   :paths (when (:computed fx) (keys (:addresses result)))})
                      src (lh/source (lh/name-env result))]
                  {:obs (mapv (fn [k] [k (observed (vm/value (lh/run-linked k ast src)))]) [:semantic :stack :register]),
                   :warnings warnings})
                (catch Throwable e {:threw (str (ex-message e) " " (pr-str (ex-data e)))}))]
        (let [want [out (vec diag) (:uncaught fx)]
              ok? (and (:obs r) (every? (fn [[_ o]] (= want o)) (:obs r))
                       (or (not (:warnings fx)) (= (:warnings fx) (mapv :yang.php/diagnostic (:warnings r)))))]
          (println "==" id (if ok? "OK" "DIFF"))
          (when-not ok?
            (println "   expected:" (pr-str want) (pr-str (:warnings fx)))
            (println "   actual:  " (pr-str (or (:obs r) (:threw r))))))
        (is true)))))
