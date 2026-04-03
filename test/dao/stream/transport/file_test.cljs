(ns dao.stream.transport.file-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.transport.file]
    [fs :as fs]
    [path :as path]))


(deftest node-file-stream-basic-test
  (let [temp-path (path/join "target" "daostream-test-node.log")]
    (try
      (let [s (ds/open! {:transport {:type :file :path temp-path}})]

        (testing "initial status"
          (is (false? (ds/closed? s))))

        (testing "put! and next (immediate)"
          (ds/put! s {:a 1})
          (ds/put! s {:b 2})
          (let [r1 (ds/next s {:position 0})
                v1 (:ok r1)
                c1 (:cursor r1)
                r2 (ds/next s c1)
                v2 (:ok r2)
                c2 (:cursor r2)]
            (is (= {:a 1} v1))
            (is (= {:b 2} v2))
            (is (number? (:position c1)))
            (is (number? (:position c2)))
            (is (= :blocked (ds/next s c2)))))

        (testing "persistence (re-opening file)"
          (ds/close! s)
          (is (true? (ds/closed? s)))
          (let [s2 (ds/open! {:transport {:type :file :path temp-path}})]
            (is (= {:a 1} (:ok (ds/next s2 {:position 0}))))
            (ds/close! s2)))

        (testing "error after close"
          (ds/close! s)
          (is (thrown? js/Error (ds/put! s {:c 3})))))

      (finally
        (when (fs/existsSync temp-path)
          (fs/unlinkSync temp-path))))))
