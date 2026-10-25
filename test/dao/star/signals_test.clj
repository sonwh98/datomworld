(ns dao.star.signals-test
  (:require [clojure.test :refer [deftest is]]
            [dao.star.signals :as signals]))


(defn- bytes-of
  [s]
  (.getBytes ^String s "UTF-8"))


(deftest get-and-delete-read-the-query-parameter
  (is (= {:count 1 :name "a b"}
         (signals/decode {:http/method :get
                          :http/query "x=1&datastar=%7B%22count%22%3A1%2C%22name%22%3A%22a+b%22%7D"})))
  (is (= {:a 2} (signals/decode {:http/method :delete
                                 :http/query "datastar=%7B%22a%22%3A2%7D"}))))


(deftest other-methods-read-the-json-body
  (is (= {:count 1} (signals/decode {:http/method :post :http/body (bytes-of "{\"count\":1}")})))
  (is (= {:count 1} (signals/decode {:http/method :put :http/body "{\"count\":1}"}))))


(deftest decode-is-total
  (doseq [e [{}
             {:http/method :post}
             {:http/method :post :http/body (bytes-of "not json")}
             {:http/method :post :http/body (bytes-of "[1,2]")}
             {:http/method :post :http/body (bytes-of "42")}
             {:http/method :post :http/body (byte-array [(byte -1) (byte -2)])}
             {:http/method :get}
             {:http/method :get :http/query "datastar=%ZZ"}
             {:http/method :get :http/query "datastar={bad"}
             {:http/method :get :http/query "other=1"}
             {:http/method :post :http/body 42}]]
    (is (= {} (signals/decode e)) (pr-str e))))


(deftest a-get-ignores-the-body-and-a-post-ignores-the-query
  (is (= {} (signals/decode {:http/method :get :http/body (bytes-of "{\"a\":1}")})))
  (is (= {} (signals/decode {:http/method :post :http/query "datastar=%7B%22a%22%3A1%7D"}))))
