(ns yin.repl.shell-session-test
  "The process corpus is also a JVM test, sharing the exact fixtures and
   deadline runner used by bb test:jolt-shell."
  (:require [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is]]))


(deftest local-shell-process-corpus
  (let [{:keys [exit out err]}
        (sh/sh "bb" "src/dev/jolt_shell_session.clj" "--host" "jvm")]
    (is (zero? exit) (str out "\n" err))))
