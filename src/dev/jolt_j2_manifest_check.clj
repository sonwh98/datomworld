;; bb src/dev/jolt_j2_manifest_check.clj (or bb test:jolt-j2-manifest)
;; All fixtures live in a temporary test tree inside this worktree.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.test :refer [deftest is run-tests]])


(load-file "src/dev/jolt_j2_lane.clj")


(deftest manifest-check
  (let [scratch (fs/create-temp-dir {:dir "." :prefix ".j2-manifest-"})
        root (fs/path scratch "test")
        check (resolve 'jolt-j2-lane/check-manifest!)
        portable (fs/path root "dao/data/unusual_filename_test.cljc")
        jvm (fs/path root "dao/host/gate_test.clj")
        dart (fs/path root "dao/stream/ws/dart_test.cljd")
        driver (str (fs/absolutize "src/dev/jolt_j2_lane.clj"))
        included ["dao.data.actual-name-test"]
        excluded [{:ns "dao.host.gate-test"} {:ns "dao.stream.ws.dart-test"}]
        failure (fn [inc exc]
                  (try (check root inc exc) nil
                       (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (try
      (doseq [file [portable jvm dart]] (fs/create-dirs (fs/parent file)))
      (spit (str portable)
            "(ns dao.data.actual-name-test #?@(:clj [(:require [clojure.test])]))")
      (spit (str jvm) "(ns dao.host.gate-test)")
      (spit (str dart) "(ns dao.stream.ws.dart-test)")
      (is (= 3 (:namespaces (check root included excluded))))
      (let [message (failure [] excluded)]
        (is (and message (re-find #"UNLISTED dao.data.actual-name-test" message))))
      (let [message (failure (conj included "dao.data.removed-test") excluded)]
        (is (and message (re-find #"STALE dao.data.removed-test" message))))
      (doseq [mode ["list" "core" "probe" "jvm"]]
        (let [{:keys [exit err]} (p/shell {:dir (str scratch) :continue true
                                           :out :string :err :string}
                                          "bb" driver mode)]
          (is (= 1 exit) (str mode " exits before lane work"))
          (is (re-find #"UNLISTED dao.data.actual-name-test" err))
          (is (re-find #"STALE dao.host.bytes-test" err))))
      (is (not (fs/exists? (fs/path scratch "build"))))
      (finally (fs/delete-tree scratch)))))


(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
