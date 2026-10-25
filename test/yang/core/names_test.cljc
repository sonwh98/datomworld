(ns yang.core.names-test
  "Canonical renaming of generated names (yang.typescript.md §6.3)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.core.names :as names]
    [yang.core.uast :as u]))


(defn- tree
  [a b]
  (u/lam [a]
         (u/app (u/lam [b] (u/app (u/v a) (u/v b) (u/v 'x)))
                (u/v a))))


(deftest renames-by-first-occurrence-test
  (is (= (tree '%0 '%1)
         (names/canonicalize (tree '%brk17 '%w42)))))


(deftest ids-do-not-leak-test
  (is (= (names/canonicalize (tree '%brk17 '%w42))
         (names/canonicalize (tree '%brk3 '%w9)))))


(deftest idempotent-test
  (let [once (names/canonicalize (tree '%brk17 '%w42))]
    (is (= once (names/canonicalize once)))))


(deftest guest-and-reserved-names-stay-test
  (testing "discard, safepoint binder and ordinary names are untouched"
    (let [t (u/lam ['%_ 'yang.safepoint/_ 'x 'py/call] (u/v 'x))]
      (is (= t (names/canonicalize t))))))


(deftest literals-and-metadata-test
  (let [t (vary-meta (u/lam ['%g1] (u/lit '%g1)) assoc :yang/site :loop)
        out (names/canonicalize t)]
    (is (= (u/lit '%g1) (:body out)) "quoted data is not a binder")
    (is (= [(symbol "%0")] (:params out)))
    (is (= :loop (:yang/site (meta out))))))
