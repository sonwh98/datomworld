(ns dao.stream.transit-test
  (:require
    #?@(:cljd [["dart:core" StringBuffer]])
    [clojure.test :refer [deftest is testing]]
    [dao.stream.apply :as dao-apply]
    [dao.stream.transit :as transit]))


(defrecord Point
  [x y])


(defn- write-str
  ([v] (write-str v nil))
  ([v opts]
   #?(:clj (let [out (java.io.ByteArrayOutputStream.)]
             (transit/write (transit/writer out :json opts) v)
             (.toString out "UTF-8"))
      :cljd (let [out (StringBuffer.)]
              (transit/write (transit/writer out :json opts) v)
              (.toString out)))))


(defn- read-str
  ([s] (read-str s nil))
  ([s opts]
   #?(:clj (let [in (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))]
             (transit/read (transit/reader in :json opts)))
      :cljd (transit/read (transit/reader s :json opts) s))))


(deftest basic-write-scalars-test
  (testing "top-level scalars are quoted transit values"
    (is (= "[\"~#'\",null]" (write-str nil)))
    (is (= "[\"~#'\",true]" (write-str true)))
    (is (= "[\"~#'\",42]" (write-str 42)))
    (is (= "[\"~#'\",3.5]" (write-str 3.5)))
    (is (= "[\"~#'\",\"\"]" (write-str "")))))


(deftest basic-write-escape-test
  (testing "strings with transit prefixes are escaped"
    (is (= "[\"~#'\",\"~~foo\"]" (write-str "~foo")))
    (is (= "[\"~#'\",\"~^foo\"]" (write-str "^foo")))
    (is (= "[\"~#'\",\"~`foo\"]" (write-str "`foo")))))


(deftest basic-write-keyword-symbol-test
  (testing "keywords and symbols use transit scalar tags"
    (is (= "[\"~#'\",\"~:foo/bar\"]" (write-str :foo/bar)))
    (is (= "[\"~#'\",\"~$foo/bar\"]" (write-str 'foo/bar)))))


(deftest basic-write-collection-test
  (testing "collections use the expected transit shapes"
    (is (= "[1,2]" (write-str [1 2])))
    (is (= "[\"^ \",\"~:foo\",\"bar\"]" (write-str {:foo "bar"})))
    (is (= "[\"~#set\",[]]" (write-str #{})))
    (is (= "[\"~#list\",[1,2]]" (write-str '(1 2))))))


(deftest roundtrip-default-handlers-test
  (testing "plain transit data round-trips"
    (doseq [v [nil true false 0 42 3.5 "" :foo/bar 'foo/bar [1 2] '(1 2)
               {:foo "bar", :baz [1 2]} #{}]]
      (is (= v (read-str (write-str v)))
          (str "round-trip failed for " (pr-str v))))))


(deftest custom-handler-test
  (testing "custom write and read handlers compose"
    (let [p (->Point 10 20)
          handler-key #?(:clj Point
                         :cljd (str (.-runtimeType p)))
          encoded (write-str p
                             {:handlers {handler-key (transit/write-handler
                                                       (fn [_] "point")
                                                       (fn [pt]
                                                         [(:x pt)
                                                          (:y pt)]))}})
          decoded (read-str encoded
                            {:handlers {"point" (transit/read-handler
                                                  (fn [[x y]]
                                                    (->Point x y)))}})]
      (is (= "[\"~#point\",[10,20]]" encoded))
      (is (= p decoded)))))


(deftest cache-reference-test
  (testing "cached key references decode correctly"
    (is (= [{:foo 1} {:foo 2}]
           (read-str "[[\"^ \",\"~:foo\",1],[\"^ \",\"^0\",2]]")))))


(deftest unknown-tag-fallback-test
  (testing "unknown tags fall back to tagged values"
    (let [v (read-str "[\"~#mystery\",42]")]
      (is (= (transit/tagged-value "mystery" 42) v)))))


(deftest write-meta-test
  (testing "metadata can be preserved through the write-meta transform"
    (let [v (with-meta [1 2] {:origin :test})
          encoded (write-str v {:transform transit/write-meta})
          decoded (read-str encoded)]
      (is (= "[\"~#with-meta\",[[1,2],[\"^ \",\"~:origin\",\"~:test\"]]]"
             encoded))
      (is (= [1 2] decoded))
      (is (= {:origin :test} (meta decoded))))))


(deftest dao-apply-response-cross-runtime-shape-test
  (testing
    "a JVM-encoded dao.stream.apply response with a composite id decodes intact"
    (let
      [encoded
       "[\"^ \",\"~:dao.stream.apply/id\",[\"~:yin.repl/request\",\"scope\",1],\"~:dao.stream.apply/value\",\"ok\"]"
       decoded (read-str encoded)]
      (is (= (dao-apply/response [:yin.repl/request "scope" 1] "ok") decoded))
      (is (dao-apply/response? decoded))
      (is (= [:yin.repl/request "scope" 1] (dao-apply/response-id decoded)))
      (is (= "ok" (dao-apply/response-value decoded))))))


(deftest nested-cached-dao-apply-responses-cross-runtime-test
  (testing
    "cached keyword refs inside a JVM sync-response preserve nested dao.stream.apply responses"
    (let
      [encoded
       "[\"^ \",\"~:type\",\"~:datom/sync-response\",\"~:datoms\",[[\"^ \",\"~:dao.stream.apply/id\",\"yin.repl/request/scope/0\",\"~:dao.stream.apply/value\",\"CLOSURE\"],[\"^ \",\"^3\",\"yin.repl/request/scope/1\",\"^4\",\"1\"]],\"~:from-pos\",0,\"~:to-pos\",2]"
       decoded (read-str encoded)]
      (is (= {:type :datom/sync-response,
              :datoms [(dao-apply/response "yin.repl/request/scope/0" "CLOSURE")
                       (dao-apply/response "yin.repl/request/scope/1" "1")],
              :from-pos 0,
              :to-pos 2}
             decoded))
      (is (= [(dao-apply/response "yin.repl/request/scope/0" "CLOSURE")
              (dao-apply/response "yin.repl/request/scope/1" "1")]
             (:datoms decoded))))))
