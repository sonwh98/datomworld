(ns dao.pretty-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [dao.pretty :as pretty]))


(deftest a-quoted-symbol-prints-in-reader-form-on-every-host
  (is (= "'mod" (str/trim (pretty/pp-str '(quote mod))))))


(deftest a-quote-form-nested-in-a-collection-keeps-reader-form
  (is (= "['a 1]" (str/trim (pretty/pp-str ['(quote a) 1])))))


(deftest a-longer-list-headed-by-quote-is-still-a-list
  (is (= "(quote a b)" (str/trim (pretty/pp-str '(quote a b))))))
