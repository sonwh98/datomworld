(ns affected-test
  "Tests of the pure functions of src/dev/affected.clj.

   Babashka only (affected.clj reads ns forms with edamame). It lives
   under src/dev, not test/, so the JVM lane's runner never loads it.

   Run with babashka:
     bb -cp src/dev -m affected-test"
  (:require
    [affected :as a]
    [clojure.test :refer [deftest is testing run-tests]]))


(deftest classify-sorts-changed-paths
  (testing "ignored trees and markdown"
    (doseq [p ["docs/design/x.md" "collab/1-a.prompt.md" "build/x.jar"
               "target/node-tests.js" "node_modules/a/index.js"
               "README.md" "src/cljc/yin/README.md"]]
      (is (= :ignored (a/classify p)) p)))
  (testing "a markdown fixture under test/resources is a resource"
    (is (= :resource (a/classify "test/resources/dao/x/errata.md"))))
  (testing "wide changes"
    (doseq [p ["deps.edn" "bb.edn" "shadow-cljs.edn" "tests.edn"
               ".clj-kondo/config.edn" "antlr/python3/manifest.edn"
               "src/dev/cljd_agg.clj" "package.json" "pubspec.yaml"
               "mise.toml" ".cljstyle"]]
      (is (= :wide (a/classify p)) p)))
  (testing "code under src/ and test/"
    (doseq [p ["src/cljc/a/b.cljc" "src/clj/a/b.clj" "src/cljs/a/b.cljs"
               "src/cljd/a/b.cljd" "test/a/b_test.cljc"]]
      (is (= :code (a/classify p)) p)))
  (testing "generated Dart output is ignored"
    (is (= :ignored (a/classify "test/cljd-out/a/b_test.dart"))))
  (testing "anything else is a resource"
    (is (= :resource (a/classify "test/resources/yin/x-v1.txt")))
    (is (= :resource (a/classify "src/shaders/a.frag")))))


(deftest form-deps-reads-every-libspec-shape
  (let [form '(ns foo.bar
                (:refer-clojure :exclude [map])
                (:require
                  a.plain
                  [b.vec]
                  [c.as :as c]
                  [d.refer :refer [x y]]
                  [e.prefix f g.h [i :as i] [j :refer [z]]]
                  ["npm-lib" :as npm]
                  :reload)
                (:use [k.use :only [w]] l.use)
                (:require-macros [m.macros :refer [mac]])
                (:import (java.util UUID)))]
    (is (= 'foo.bar (a/ns-name-of form)))
    (is (= '#{a.plain b.vec c.as d.refer e.prefix.f e.prefix.g.h
              e.prefix.i e.prefix.j k.use l.use m.macros}
           (a/form-deps form)))))


(deftest ns-info-reads-every-reader-conditional-branch
  (let [text (str ";; a leading comment\n"
                  "(ns ^:no-doc ^{:author \"x\"} p.q-test\n"
                  "  \"doc\"\n"
                  "  (:require\n"
                  "    [clojure.test :refer [deftest]]\n"
                  "    #?(:clj [only.clj] :cljs [only.cljs]"
                  " :cljd [only.cljd] :default [dflt])\n"
                  "    #?@(:clj [[spliced.a] [spliced.b :as b]])\n"
                  "    [kw.user :as-alias ku]))\n"
                  "(def x ::ku/k)\n"
                  "(defn r [] #\"re\")\n")
        info (a/ns-info text)]
    (is (= 'p.q-test (:ns info)))
    (is (= '#{clojure.test only.clj only.cljs only.cljd dflt
              spliced.a spliced.b kw.user}
           (:deps info))))
  (testing "a file with no ns form"
    (is (nil? (:ns (a/ns-info "(println 1)\n"))))))


(deftest path->ns-derives-a-namespace-from-a-path
  (is (= 'yin.vm.integer (a/path->ns "src/cljc/yin/vm/integer.cljc")))
  (is (= 'a.b-c-test (a/path->ns "test/a/b_c_test.clj")))
  (is (= 'dao.x (a/path->ns "src/cljd/dao/x.cljd")))
  (is (nil? (a/path->ns "examples/a.clj"))))


(deftest convention-test-paths-maps-src-to-test
  (is (= ["test/yin/vm/integer_test.clj" "test/yin/vm/integer_test.cljc"
          "test/yin/vm/integer_test.cljs" "test/yin/vm/integer_test.cljd"]
         (a/convention-test-paths "src/cljc/yin/vm/integer.cljc")))
  (is (nil? (a/convention-test-paths "test/a/b_test.cljc"))))


(deftest reverse-closure-follows-dependents-transitively
  (let [graph '{t1 #{m} t2 #{n} m #{core} n #{other} core #{} x #{t1}}]
    (is (= '#{core m t1 x} (a/reverse-closure graph '#{core})))
    (is (= '#{n t2} (a/reverse-closure graph '#{n})))
    (testing "a cycle terminates"
      (is (= '#{p q} (a/reverse-closure '{p #{q} q #{p}} '#{p}))))
    (testing "an unknown root is still in its own closure"
      (is (= '#{gone} (a/reverse-closure graph '#{gone}))))))


(deftest mentions-matches-a-path-or-basename
  (is (a/mentions? "(def p \"test/resources/yin/x/ledger-v9.txt\")"
                   "test/resources/yin/x/ledger-v9.txt"))
  (is (a/mentions? "(io/resource \"x/ledger-v9.txt\")"
                   "test/resources/yin/x/ledger-v9.txt"))
  (is (not (a/mentions? "(def p \"ledger-v10.txt\")"
                        "test/resources/yin/x/ledger-v9.txt"))))


(deftest scanner-roots-finds-file-seq-roots
  (is (= ["src"] (a/scanner-roots "(file-seq (io/file \"src\"))")))
  (is (= ["test" "src/dev"]
         (a/scanner-roots (str "(concat (file-seq (io/file \"test\"))"
                               " (file-seq (fs/file \"src/dev\")))"))))
  (is (empty? (a/scanner-roots "(io/file \"src\")"))))


(deftest scanner-roots-finds-every-walker-shape
  (testing "the java.io.File. constructor (dao.gui.event.fixture-test)"
    (is (= ["test/a/fixtures"]
           (a/scanner-roots
             "(file-seq (java.io.File. \"test/a/fixtures\"))"))))
  (testing "a let-bound root (dao.jing.hash-registry-contract-test)"
    (is (= ["src/cljc"]
           (a/scanner-roots (str "(let [d (io/file \"src/cljc\")]\n"
                                 "  (filter f (file-seq d)))")))))
  (testing "every literal argument, nested calls skipped"
    (is (= ["src" "cljc" "x.edn"]
           (a/scanner-roots (str "(file-seq (io/file \"src\" \"cljc\"))"
                                 " (io/file (str a \")\") \"x.edn\")")))))
  (testing "fs/glob, fs/path, File. and .listFiles walk too"
    (is (= ["test/g" "*.edn"]
           (a/scanner-roots "(fs/glob \"test/g\" \"*.edn\")"))
        "a pattern is a harmless root: no path starts with it")
    (is (= ["test/p"] (a/scanner-roots "(fs/list-dir (fs/path \"test/p\"))")))
    (is (= ["test/l"] (a/scanner-roots "(.listFiles (File. \"test/l\"))"))))
  (testing "./ and trailing slashes are trimmed"
    (is (= ["test/t" "."]
           (a/scanner-roots
             "(file-seq (io/file \"./test/t/\")) (io/file \".\")")))))


(deftest under-root-matches-a-path-in-a-tree
  (is (a/under-root? "src/cljc" "src/cljc/yin/vm/integer.cljc"))
  (is (a/under-root? "test/x.edn" "test/x.edn"))
  (is (a/under-root? "." "anything/at/all"))
  (is (not (a/under-root? "src/clj" "src/cljc/a.cljc"))))


(defn- entry
  [path text]
  (a/index-entry path text))


(def ^:private corpus
  [(entry "src/cljc/core/a.cljc" "(ns core.a)")
   (entry "src/cljc/mid/b.cljc" "(ns mid.b (:require [core.a]))")
   (entry "src/clj/lone/c.clj" "(ns lone.c)")
   (entry "src/cljs/web/d.cljs" "(ns web.d (:require [mid.b]))")
   (entry "test/mid/b_test.cljc"
          "(ns mid.b-test (:require [mid.b] [clojure.test]))")
   (entry "test/lone/c_test.clj" "(ns lone.c-test)\n(deftest t)")
   (entry "test/web/d_test.cljs" "(ns web.d-test (:require [web.d]))")
   (entry "test/fix/data.cljc"
          "(ns fix.data)\n(def p \"test/resources/fix/pins-v9.txt\")")
   (entry "test/fix/data_test.cljc"
          "(ns fix.data-test (:require [fix.data]))")
   (entry "test/odd/checks.clj"
          "(ns odd.checks)\n(deftest scan (file-seq (io/file \"src\")))")
   (entry "test/odd/helper.clj" "(ns odd.helper (:require [core.a]))")])


(deftest index-entry-marks-test-namespaces
  (let [by-path (into {} (map (juxt :path identity)) corpus)]
    (is (:test? (by-path "test/mid/b_test.cljc")))
    (is (:test? (by-path "test/odd/checks.clj")) "a deftest, no -test")
    (is (not (:test? (by-path "test/odd/helper.clj"))))
    (is (not (:test? (by-path "test/fix/data.cljc"))))
    (is (not (:test? (by-path "src/cljc/mid/b.cljc"))))))


(deftest select-takes-the-closure-to-test-namespaces
  (testing "a core change reaches every dependent test, and the scanner"
    (let [{:keys [tests wide]} (a/select corpus ["src/cljc/core/a.cljc"])]
      (is (empty? wide))
      (is (= '#{mid.b-test web.d-test odd.checks} tests))))
  (testing "an unrequired src ns selects its conventional test"
    (is (= '#{lone.c-test odd.checks}
           (:tests (a/select corpus ["src/clj/lone/c.clj"])))))
  (testing "a fixture read by path selects through its readers"
    (is (= '#{fix.data-test}
           (:tests (a/select corpus ["test/resources/fix/pins-v9.txt"])))))
  (testing "a deleted file still selects by its derived ns"
    (is (= '#{mid.b-test web.d-test odd.checks}
           (:tests (a/select corpus ["src/cljc/mid/b.cljc"]))))
    (is (= '#{mid.b-test web.d-test odd.checks}
           (:tests (a/select (remove #(= "src/cljc/mid/b.cljc" (:path %))
                                     corpus)
                             ["src/cljc/mid/b.cljc"])))))
  (testing "ignored files select nothing"
    (let [{:keys [tests wide]} (a/select corpus ["docs/a.md"])]
      (is (empty? tests))
      (is (empty? wide))))
  (testing "a wide change selects every test namespace"
    (let [{:keys [tests wide]} (a/select corpus ["bb.edn" "docs/a.md"])]
      (is (= ["bb.edn"] wide))
      (is (= '#{mid.b-test lone.c-test web.d-test fix.data-test odd.checks}
             tests))))
  (testing "every changed file gets a reason"
    (let [{:keys [reasons]} (a/select corpus ["docs/a.md"
                                              "src/cljc/core/a.cljc"])]
      (is (= #{"docs/a.md" "src/cljc/core/a.cljc"} (set (keys reasons))))
      (is (every? (comp string? :why) (vals reasons))))))


(deftest lane-tests-filters-by-host
  (let [tests '#{mid.b-test lone.c-test web.d-test odd.checks}]
    (is (= '[lone.c-test mid.b-test odd.checks]
           (a/lane-tests corpus tests :clj)))
    (is (= '[mid.b-test web.d-test] (a/lane-tests corpus tests :cljs)))
    (testing "Dart runs only *_test.cljc and *_test.cljd files"
      (is (= '[mid.b-test] (a/lane-tests corpus tests :cljd))))))


(deftest ns-regexp-escapes-dots-for-edn
  (is (= "^(a\\\\.b-test|c-test)$" (a/ns-regexp '[a.b-test c-test]))))


(deftest parse-summary-reads-each-runner
  (is (= {:tests 12 :failures 3}
         (a/parse-summary :clj (str "Ran 12 tests containing 40 assertions.\n"
                                    "2 failures, 1 errors.\n"))))
  (is (= {:tests 7 :failures 0}
         (a/parse-summary :cljs (str "Ran 7 tests containing 9 assertions.\n"
                                     "0 failures, 0 errors.\n"))))
  (is (= {:tests 33 :failures 2}
         (a/parse-summary :cljd (str "00:01 +3: a\n00:09 +31 ~1 -2: "
                                     "Some tests failed.\n"))))
  (is (= {:tests 5 :failures 0}
         (a/parse-summary :cljd "00:03 +5: All tests passed!\n")))
  (is (= {:tests nil :failures nil} (a/parse-summary :clj "boom"))))


(deftest prerequisites-follow-the-forward-closure
  (let [idx [(entry "src/clj/r/spawn.clj"
                    "(ns r.spawn)\n(def js \"target/yin-repl.js\")")
             (entry "src/clj/r/parse.clj"
                    "(ns r.parse (:import (x Python3Parser)))")
             (entry "test/r/spawn_test.clj"
                    "(ns r.spawn-test (:require [r.spawn]))")
             (entry "test/r/parse_test.clj"
                    "(ns r.parse-test (:require [r.parse]))")
             (entry "test/r/peer_test.cljc"
                    "(ns r.peer-test)\n(def e \"build/yin-repl-peer\")")
             (entry "test/r/plain_test.cljc" "(ns r.plain-test)")]]
    (is (= #{:build:yin-repl-node}
           (a/prerequisites idx {:clj '[r.spawn-test]})))
    (is (= #{:gen:python-antlr}
           (a/prerequisites idx {:clj '[r.parse-test]})))
    (is (= #{} (a/prerequisites idx {:cljs '[r.parse-test]}))
        "the ANTLR classes are a JVM-lane need only")
    (is (= #{:build:yin-repl-peer}
           (a/prerequisites idx {:cljd '[r.peer-test]})))
    (is (= #{} (a/prerequisites idx {:clj '[r.plain-test]
                                     :cljs '[r.plain-test]})))))


(deftest serialized-pairs-detects-a-jvm-cljd-build
  (let [idx [(entry "test/r/build_test.clj"
                    (str "(ns r.build-test)\n(def c [\"clojure\""
                         " \"-M:clojuredart:cljd\" \"compile\"])"))
             (entry "test/r/plain_test.cljc" "(ns r.plain-test)")]]
    (is (= #{[:clj :cljd]}
           (a/serialized-pairs idx {:clj '[r.build-test]
                                    :cljd '[r.plain-test]})))
    (is (= #{} (a/serialized-pairs idx {:clj '[r.build-test]})))
    (is (= #{} (a/serialized-pairs idx {:clj '[r.plain-test]
                                        :cljd '[r.plain-test]})))
    (testing "any clojuredart mention, e.g. inside a shell string"
      (let [idx [(entry "test/r/shell_test.clj"
                        (str "(ns r.shell-test)\n(def c \"clojure"
                             " -M:clojuredart:cljd compile x\")"))
                 (entry "test/r/plain_test.cljc" "(ns r.plain-test)")]]
        (is (= #{[:clj :cljd]}
               (a/serialized-pairs idx {:clj '[r.shell-test]
                                        :cljd '[r.plain-test]})))))))


(def ^:private walkers
  [(entry "src/cljc/core/a.cljc" "(ns core.a)")
   (entry "src/cljc/far/z.cljc" "(ns far.z)")
   (entry "test/g/fixture_test.cljc"
          (str "(ns g.fixture-test)\n(deftest t (file-seq"
               " (java.io.File. \"test/g/fixtures\")))"))
   (entry "test/h/lint_test.cljc"
          (str "(ns h.lint-test (:require [core.a]))\n"
               "(deftest t (let [d (io/file \"src/cljc\")]"
               " (file-seq d)))"))])


(deftest select-finds-walkers-of-every-shape
  (testing "a fixture walked through java.io.File."
    (is (= '#{g.fixture-test}
           (:tests (a/select walkers ["test/g/fixtures/one.edn"])))))
  (testing "a src file outside the walker's closure, root let-bound"
    (is (= '#{h.lint-test}
           (:tests (a/select walkers ["src/cljc/far/z.cljc"]))))))


(deftest parse-args-reads-the-options
  (is (= {:list? true :base "HEAD" :lanes [:cljs] :changed nil}
         (a/parse-args ["--list" "--base" "HEAD" "--lane" "cljs"])))
  (is (= {:list? false :base nil :lanes [:clj :cljs :cljd]
          :changed ["a.clj" "b.edn"]}
         (a/parse-args ["--changed" "a.clj" "b.edn"])))
  (is (thrown? clojure.lang.ExceptionInfo
        (a/parse-args ["--lane" "jvm"])))
  (testing "an empty --changed list is an error, not an empty change"
    (is (thrown? clojure.lang.ExceptionInfo (a/parse-args ["--changed"])))
    (is (thrown? clojure.lang.ExceptionInfo
          (a/parse-args ["--changed" "--list"])))))


(deftest normalize-path-makes-paths-repo-relative
  (is (= "src/a.clj" (a/normalize-path "/r/repo" "src/a.clj")))
  (is (= "src/a.clj" (a/normalize-path "/r/repo" "./src/a.clj")))
  (is (= "src/a.clj" (a/normalize-path "/r/repo" "/r/repo/src/a.clj")))
  (is (= "src/a.clj" (a/normalize-path "/r/repo" "/r/repo/x/../src/a.clj")))
  (is (nil? (a/normalize-path "/r/repo" "/r/other/src/a.clj")))
  (is (nil? (a/normalize-path "/r/repo" "/r/repository/a.clj")))
  (is (nil? (a/normalize-path "/r/repo" "../a.clj"))))


(deftest input-errors-refuse-a-vacuous-green
  (let [sel (fn [changed] (a/select corpus changed))
        ;; on disk, or in git's base tree (a file the change deleted)
        on-disk #{"docs/a.md" "examples/demo.clj" "src/cljc/gone/x.cljc"}
        errors (fn [changed explicit?]
                 (a/input-errors {:index corpus
                                  :changed changed
                                  :explicit? explicit?
                                  :exists? on-disk}))]
    (testing "an empty index: the wrong cwd"
      (is (seq (a/input-errors {:index [] :changed ["docs/a.md"]
                                :explicit? false
                                :exists? on-disk}))))
    (testing "an explicit empty change list"
      (is (seq (errors [] true))))
    (testing "a typo exists nowhere, even when a tree walker selects it"
      (is (seq (:tests (sel ["src/cljc/core/aa.cljc"]))))
      (is (seq (errors ["src/cljc/core/aa.cljc" "docs/a.md"] true)))
      (is (seq (errors ["docs/typo.md"] true))))
    (testing "a real change that legitimately selects nothing is fine"
      (is (empty? (errors ["docs/a.md"] true)))
      (is (empty? (errors ["examples/demo.clj"] true))))
    (testing "a deleted file is fine: indexed, or in the base tree"
      (is (empty? (errors ["src/cljc/mid/b.cljc"] true)))
      (is (empty? (errors ["src/cljc/gone/x.cljc"] true))))
    (testing "git-derived paths are real by construction"
      (is (empty? (errors ["docs/gone.md"] false))))))


(defn -main
  [& _]
  (let [{:keys [fail error]} (run-tests 'affected-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
