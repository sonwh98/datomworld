;; J0c round 4, step B: measure the nesting budget of the native parser per
;; grammar shape and per parsing thread, in child processes
;; (docs/design/yin.repl-jolt-implementation.md §7.6).
;;
;;   bb src/dev/jolt_j0c_stack.clj [shape ...]
;;
;; Needs bb build:jolt-native (the test build: jolt_parser_thread_info).
;; Every parse runs in a child jolt process (src/dev/jolt_j0c_stack_child.clj)
;; so a stack overflow kills the child, never this harness. Per shape and per
;; thread (the jolt main thread; a thread jolt starts with Thread.):
;;
;;   1. one child parses doubling depths natively until it dies or the
;;      ladder ends;
;;   2. one child parses 16 even steps between the last depth that passed and
;;      the one that died;
;;   3. a FRESH child (cold caches) parses the last passing depth through the
;;      full jolt path (native parse, decode, shared exporter); it must pass.
;;
;; The measured result is the bracket [last passing depth, first failing
;; depth], the failing child's exit (128+signal) and its stderr tail. A shape
;; whose single parse passes 20 s before it overflows is reported as
;; time-limited instead ({:time-limited ...}: the depth and its time). No
;; bound is chosen or enforced here; that is J5's.
;; Writes build/j0c/r4/stack-budget.edn and prints a table.
(ns jolt-j0c-stack
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.pprint :as pprint]
    [clojure.string :as str]))


(def jolt (str (System/getenv "JOLT_HOME") "/bin/jolt"))

(def shapes ["parens" "list" "call" "unary" "not" "power" "lambda" "ternary" "indent"])


;; indent grows quadratically in bytes; stop its ladder lower
(def ladder-max {"indent" 8192})


;; per-parse time cap, ms
(def max-ms 20000)


(defn- child
  [shape where mode depths]
  (let [r (apply p/shell {:out :string :err :string :continue true}
                 jolt "-Srepro" "-M:jolt" "src/dev/jolt_j0c_stack_child.clj"
                 shape where mode (str max-ms) (map str depths))
        lines (str/split-lines (:out r))
        info (some #(when (str/starts-with? % "INFO ") (read-string (subs % 5))) lines)
        oks (keep #(when (str/starts-with? % "OK ")
                     (let [[_ d outcome ms] (str/split % #" ")]
                       {:depth (parse-long d), :outcome outcome, :ms (parse-long ms)}))
                  lines)
        errs (keep #(when (str/starts-with? % "ERR ") %) lines)
        slow (some #(when (str/starts-with? % "SLOW ")
                      (let [[_ d ms] (str/split % #" ")] {:depth (parse-long d), :ms (parse-long ms)}))
                   lines)]
    {:exit (:exit r), :info info, :oks (vec oks), :errs (vec errs), :slow slow,
     :stderr-tail (str/join "\n" (take-last 3 (str/split-lines (:err r))))}))


(defn- measure
  [shape where]
  (let [top (get ladder-max shape 65536)
        ladder (vec (take-while #(<= % top) (iterate #(* 2 %) 8)))
        r1 (child shape where "native" ladder)
        last-ok (or (:depth (peek (:oks r1))) 0)
        fail (first (filter #(> % last-ok) ladder))]
    (cond
      (:slow r1)
      (let [under (last (filter #(<= (:ms %) max-ms) (:oks r1)))]
        {:shape shape, :thread where,
         :stack-bytes (get-in r1 [:info :stack-bytes]),
         :time-limited (:slow r1),
         :passes (:depth under), :passes-ms (:ms under),
         :times (mapv (juxt :depth :ms) (:oks r1))})

      (or (nil? fail) (and (zero? (:exit r1)) (empty? (:errs r1))))
      {:shape shape, :thread where, :info (:info r1), :no-failure-up-to last-ok}

      :else
      (let [step (max 1 (quot (- fail last-ok) 16))
            fine (vec (range (+ last-ok step) fail step))
            r2 (child shape where "native" fine)
            last-ok2 (or (:depth (peek (:oks r2))) last-ok)
            fail2 (or (first (filter #(> % last-ok2) fine)) fail)
            dying (if (seq fine) r2 r1)
            r3 (child shape where "full" [last-ok2])]
        {:shape shape, :thread where,
         :stack-bytes (get-in r1 [:info :stack-bytes]),
         :main-thread (get-in r1 [:info :main-thread]),
         :passes last-ok2, :fails fail2,
         :fail-exit (:exit dying), :fail-errs (:errs dying),
         :fail-stderr (:stderr-tail dying),
         :bytes-per-level (when (pos? last-ok2)
                            (long (/ (get-in r1 [:info :stack-bytes]) fail2))),
         :fresh-full-at-passes (if (and (zero? (:exit r3)) (= [last-ok2] (mapv :depth (:oks r3))))
                                 {:ok (:outcome (first (:oks r3))), :ms (:ms (first (:oks r3)))}
                                 {:failed (:exit r3), :errs (:errs r3)})}))))


(let [only (seq *command-line-args*)
      results (vec (for [shape (or only shapes)
                         where ["main" "thread"]]
                     (let [r (measure shape where)]
                       (println (pr-str r))
                       (flush)
                       r)))]
  (fs/create-dirs "build/j0c/r4")
  (spit "build/j0c/r4/stack-budget.edn" (with-out-str (pprint/pprint results)))
  (pprint/print-table [:shape :thread :stack-bytes :passes :fails :fail-exit :bytes-per-level
                       :time-limited :fresh-full-at-passes]
                      results)
  (println "wrote build/j0c/r4/stack-budget.edn")
  (System/exit (if (every? #(or (:no-failure-up-to %) (:time-limited %) (:ok (:fresh-full-at-passes %))) results) 0 1)))
