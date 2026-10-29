;; J5 child legs, run by src/dev/jolt_j5_run.clj in their own jolt process so
;; a native crash ends only this process and the driver keeps the report:
;;
;;   jolt -Srepro -M:jolt:jolt-test src/dev/jolt_j5_child.clj <mode> [arg]
;;
;;   inject <1|2|3>   the C++ test seam makes the next parses throw
;;                    (bad_alloc; a non-std exception; bad_alloc with a
;;                    failing handler malloc): the parse throws, no packet,
;;                    and the same worker parses again after the seam clears
;;   abort            prints REPORT lines, then calls libc abort(): the
;;                    driver must classify the crash as a failure and keep
;;                    the report (the §7.8 harness property)
;;   unavailable      in a root whose parser library is missing or wrong:
;;                    Python is not in the catalog and answers
;;                    unavailable-parser; Clojure and PHP evaluate in the same
;;                    session
;;   require          with the library present: requiring loaded nothing; the
;;                    first rule-names call loads it (C8)
;;   retry <lib>      starts unavailable, then the library appears at the
;;                    expected path (copied from <lib>): the next selection
;;                    loads it and evaluates Python
;; Prints `RESULT <edn>` lines.
(ns jolt-j5-child
  (:require
    [clojure.string :as str]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]
    [yang.python.antlr.parser :as parser]
    [yin.repl :as repl]
    [yin.repl.frontends :as frontends]))


(defn- result
  [m]
  (println "RESULT" (pr-str m))
  (flush))


(defn- session
  [lines]
  (second (reduce (fn [[st acc] line]
                    (let [[st' text] (repl/eval-input st line)] [st' (conj acc text)]))
                  [(frontends/create-state {}) []]
                  lines)))


(let [[mode arg] *command-line-args*]
  (case mode
    "inject"
    (let [inject (ffi/cfn "jolt_parser_inject_fault" [:int] :int)
          w (parser/make-worker)
          _ (inject (parse-long arg))
          r (try (parser/parse-source w [:unit 0] "x = 1\n") :no-throw
                 (catch Exception e (or (ex-data e) (ex-message e))))
          _ (inject 0)
          again (:yang.cst/outcome (parser/parse-source w [:unit 0] "x = 1\n"))]
      (parser/close-worker w)
      (result {:mode (parse-long arg), :threw r, :again again}))

    "abort"
    (let [abort (ffi/cfn "abort" [] :void)]
      (println "REPORT before-abort parsed" (:yang.cst/outcome (parser/parse-source "x = 1\n")))
      (flush)
      (abort))

    "unavailable"
    (result {:available (parser/available?)
             :texts (session ["(lang :yang/python)" "(+ 1 2)" "(lang :yang/php)" "1 + 2;"
                              "(lang :yang/clojure)" "(* 6 7)"])})

    "require"
    ;; in a root whose library exists: requiring the parser, the frontends
    ;; and the REPL (this namespace's requires) loaded nothing
    (result {:loaded-after-require @@(resolve 'yang.antlr.cst/loaded?)
             :names (first (parser/rule-names))
             :loaded-after-ask @@(resolve 'yang.antlr.cst/loaded?)})

    "retry"
    (let [loaded @@(resolve 'yang.antlr.cst/loaded?)
          names-before (try (first (parser/rule-names))
                            (catch Exception e (:diagnostic (ex-data e))))
          before (parser/available?)
          t1 (session ["(lang :yang/python)"])
          dest (java.io.File. ^String cst/library)]
      (.mkdirs (.getParentFile dest))
      (let [r (.start (ProcessBuilder. ^java.util.List ["cp" arg (.getPath dest)]))]
        (.waitFor r))
      (result {:loaded-after-require loaded, :names-before names-before,
               :before before, :first t1, :after (parser/available?),
               :then (session ["(lang :yang/python)" "1 + 2"])
               :names-after (first (parser/rule-names))}))))


(System/exit 0)
