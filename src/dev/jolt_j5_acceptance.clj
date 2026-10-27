;; bb test:jolt-j5 — the J5 local-REPL acceptance run (docs/design/
;; yin.repl-jolt-implementation.md §7.8 J5, §10.1 J5 row), end to end through
;; each host's own REPL entry point and the standard catalog (no hand-built
;; catalog):
;;
;;   1. one Python session transcript fed to the JVM REPL (clojure
;;      -M:clj-yin-repl) and the jolt REPL (-M:jolt:jolt-yin-repl) over stdin:
;;      (lang :yang/python), `1 + 2`, definitions across submissions, a
;;      multiline function (the completeness probe holds the open block until
;;      its blank line), `print` with non-ASCII and astral text, an uncaught
;;      exception that keeps the session, arbitrary-precision integer shifts,
;;      floats, a syntax error, then PHP and Clojure. The jolt output must
;;      equal the JVM's line for line (the banner and the Host line excepted);
;;   2. the multiline probe sequence on its own: the continuation prompts the
;;      REPL prints while a block, a bracket or a triple quote is open must be
;;      the JVM's;
;;   3. a root whose build/native lacks jolt-parser, and one whose library
;;      answers another export layout: the jolt REPL still starts, its banner
;;      names the unavailable parser, (lang :yang/python) answers the
;;      unavailable-parser diagnostic, and Clojure keeps working: never
;;      another parser.
;;
;; Needs bb bootstrap:jolt and bb build:jolt-native. Prints PASS/FAIL and
;; RECORD lines; exits nonzero on any FAIL. Scratch under build/j5-acceptance/.
(ns jolt-j5-acceptance
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def root (str (fs/canonicalize ".")))
(def scratch (fs/path root "build/j5-acceptance"))
(fs/delete-tree scratch)
(fs/create-dirs scratch)


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def ext (if (str/starts-with? plat "darwin") ".dylib" ".so"))


(defn- run-repl
  "host's REPL over stdin lines in dir; answers {:exit :out}."
  [host dir lines]
  (let [opts {:dir dir :out :string :err :string :continue true
              :in (str (str/join "\n" lines) "\n")}
        r (case host
            :jvm (p/shell opts "clojure" "-M:clj-yin-repl" "--no-state")
            :jolt (p/shell opts "sh" "-c"
                           ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt:jolt-yin-repl --no-state"))]
    ;; stdout only: the REPL's answers (error texts included) are printed
    ;; there; stderr carries host loader noise (the JVM's var-replacement
    ;; warning), kept for diagnosis
    {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}))


(defn- transcript
  "The REPL's answers, after the banner: everything from the first prompt
   on, with the prompts removed. The hosts echo prompts differently on a
   piped stdin (the JVM loop prints one, the jolt loop one per read; the J3
   transcript runner normalizes the same way), so the answers and the order
   they arrive in are compared, which is where the completeness probe shows:
   a submission answers only once it is complete. Host-specific banner lines
   are not compared."
  [out]
  (let [i (str/index-of out "yin>")]
    (if i
      (str/replace (subs out i) "yin> " "")
      (str "NO PROMPT: " out))))


(def session
  ["(lang :yang/python)"
   "1 + 2"
   "x = 40"
   "x + 2"
   "def double(n):"
   "    return n * 2"
   ""
   "double(21)"
   "print(\"héllo 😀\")"
   "1 / 0"
   "x"
   "1 << 100"
   "(1 << 100) >> 99"
   "-(1 << 70) // 3"
   "0.1 + 0.2"
   "1.5 * 4"
   "2 ** 64"
   "class C:"
   "    def v(self):"
   "        return [1, (2, 3), {'k': 'v'}]"
   ""
   "C().v()"
   "def broken(:"
   "x"
   "(lang :yang/php)"
   "1 + 2;"
   "(lang :yang/clojure)"
   "(+ 1 2)"])


(let [jvm (run-repl :jvm root session)
      jolt (run-repl :jolt root session)
      a (transcript (:out jvm))
      b (transcript (:out jolt))
      al (str/split-lines a)
      bl (str/split-lines b)
      first-diff (first (keep-indexed (fn [i [x y]] (when (not= x y) [i x y]))
                                      (map vector al bl)))]
  (spit (str (fs/path scratch "jvm.txt")) a)
  (spit (str (fs/path scratch "jolt.txt")) b)
  (println "RECORD session lines jvm" (count al) "jolt" (count bl))
  (println "RECORD jolt session transcript" (pr-str bl))
  (check "J5 acceptance: the Python session transcript (standard catalog, (lang :yang/python), arithmetic, definitions across submissions, a multiline function, print with non-ASCII and astral text, an exception that keeps the session, big integer shifts, floats, a class, a syntax error, then PHP and Clojure) is the JVM's, line for line"
         (and (zero? (:exit jvm)) (zero? (:exit jolt)) (= al bl))
         {:jvm-exit (:exit jvm), :jolt-exit (:exit jolt), :first-diff first-diff}))


;; the completeness probe's sequences: open block, bracket, triple quote, and
;; an early error under an open bracket
(let [lines ["(lang :yang/python)"
             "if True:"
             "    y = 1"
             ""
             "y"
             "z = [1,"
             "     2]"
             "z"
             "s = \"\"\"a"
             "b\"\"\""
             "s"
             "f(1, )+"
             "w = (1"
             "+ )"
             "1"]
      jvm (run-repl :jvm root lines)
      jolt (run-repl :jolt root lines)
      a (str/split-lines (transcript (:out jvm)))
      b (str/split-lines (transcript (:out jolt)))]
  (println "RECORD probe transcript jolt" (pr-str b))
  (check "J5 completeness probe sequences: the continuation prompts and answers for an open block, an open bracket, a triple quote and an early error under an open bracket are the JVM's"
         (and (zero? (:exit jvm)) (zero? (:exit jolt)) (= a b))
         {:first-diff (first (keep-indexed (fn [i [x y]] (when (not= x y) [i x y]))
                                           (map vector a b)))}))


;; missing and incompatible library: a root sharing everything but the parser
(defn- fake-root
  [nm parser-lib]
  (let [dir (fs/path scratch nm)
        native (fs/path dir "build/native" plat)]
    (fs/create-dirs native)
    (doseq [f ["deps.edn" "src" "jolt-toolchain.edn"]]
      (fs/create-sym-link (fs/path dir f) (fs/path root f)))
    (fs/create-sym-link (fs/path dir "build/tools") (fs/real-path (fs/path root "build/tools")))
    (doseq [entry (fs/list-dir (fs/path root "build/native" plat))
            :when (not (#{"jolt-parser" "jolt-parser-build"} (str (fs/file-name entry))))]
      (fs/create-sym-link (fs/path native (fs/file-name entry)) (fs/real-path entry)))
    (when parser-lib
      (fs/create-dirs (fs/path native "jolt-parser/lib"))
      (fs/copy parser-lib (fs/path native "jolt-parser/lib" (str "libjolt_parser" ext))))
    (str dir)))


(def wrong-layout
  (let [c (str (fs/path scratch "wrong_layout.c"))
        lib (str (fs/path scratch (str "wrong_parser" ext)))]
    (spit c (str "__attribute__((visibility(\"default\"))) int "
                 "jolt_parser_export_layout_version(void) { return 99; }\n"))
    (p/shell {:out :string :err :string} "cc" "-shared" "-fPIC" "-o" lib c)
    lib))


(doseq [[label dir] [["missing jolt-parser library" (fake-root "missing" nil)]
                     ["jolt-parser library writing another export layout"
                      (fake-root "wrong" wrong-layout)]]]
  (let [r (run-repl :jolt dir ["(lang :yang/python)" "(+ 1 2)"])
        out (:out r)]
    (println "RECORD" label (pr-str (filterv #(re-find #"python|unavailable|^yin>" %)
                                             (str/split-lines out))))
    (check (str "J5 refusal (" label "): the REPL starts, the banner names the unavailable parser, (lang :yang/python) answers unavailable-parser and Clojure keeps working; never another parser")
           (and (zero? (:exit r))
                (str/includes? out "python: unavailable-parser")
                (str/includes? out "Error: unavailable-parser: Yin REPL language :yang/python is not in the frontend catalog")
                (str/includes? out "yin> 3"))
           {:exit (:exit r)})))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
