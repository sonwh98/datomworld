;; J0c round 1, rung R3: call the ANTLR4 C++ Python3 parser from jolt
;; (docs/design/yin.repl-jolt-implementation.md §7.3, §7.8).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_parser_probe.clj
;;
;; Loads build/native/<platform>/jolt-parser/lib/libjolt_parser.dylib|.so
;; (built by `bb build:jolt-native`), parses "1 + 2\n" through a :blocking
;; defcfn, copies the private marshalling buffer into a jolt byte array, frees
;; it through the wrapper, and decodes it here. No native pointer leaves
;; `parse-bytes`. This is the feasibility probe only: no packet export, no
;; corpus parity, no worker caches (later J0c rounds).
(ns jolt-parser-probe
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def platform
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def library
  ;; JOLT_PARSER_PROBE_LIB names another build (the release variant, to show
  ;; the fault checks skip there); otherwise the test build
  (or (System/getenv "JOLT_PARSER_PROBE_LIB")
      (str (System/getProperty "user.dir") "/build/native/" platform
           "/jolt-parser/lib/libjolt_parser" (if (str/starts-with? platform "darwin") ".dylib" ".so"))))


(ffi/load-library library)
(ffi/defcfn c-parse "jolt_parser_parse" [:pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)
(ffi/defcfn c-layout-version "jolt_parser_layout_version" [] :int)


;; A test seam, present only in a -DJOLT_PARSER_TEST_SEAMS=ON build.
(def c-inject-fault
  (when (ffi/find-symbol "jolt_parser_inject_fault")
    (ffi/cfn "jolt_parser_inject_fault" [:int] :int)))


(defn- parse-bytes
  "Parse the UTF-8 bytes of `source`. Answers {:status s :bytes byte-array}:
   the wrapper's buffer copied into jolt memory and already freed."
  [source]
  (let [src (.getBytes ^String source "UTF-8")
        n (alength src)
        in (ffi/alloc (max 1 n))
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)]
    (try
      (when (pos? n) (ffi/write-array in src))
      (let [status (c-parse in n out-cell len-cell)
            p (ffi/read out-cell :pointer)
            len (ffi/read len-cell :size_t)]
        (try
          {:status status
           :bytes (if (or (ffi/null? p) (zero? len)) (byte-array 0) (ffi/read-array p len))}
          (finally (when-not (ffi/null? p) (c-free p)))))
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)))))


;; -- decoding the private layout (little-endian, see jolt_parser.cpp) ---------

(defn- reader
  [^bytes bs]
  (let [pos (volatile! 0)
        u8 (fn [] (let [b (bit-and (aget bs @pos) 0xff)] (vswap! pos inc) b))
        u32 (fn [] (reduce (fn [acc i] (bit-or acc (bit-shift-left (u8) (* 8 i)))) 0 (range 4)))
        text (fn [] (let [n (u32) s (String. bs (int @pos) (int n) "UTF-8")] (vswap! pos + n) s))]
    {:u8 u8 :u32 u32 :text text :pos pos}))


(defn- decode
  [^bytes bs]
  (let [{:keys [u8 u32 text pos]} (reader bs)
        magic (String. bs 0 4 "US-ASCII")
        _ (vreset! pos 4)
        layout (u32)
        status (u32)
        rules (vec (repeatedly (u32) text))
        symbols (vec (repeatedly (u32) text))
        n (u32)
        nodes (vec (repeatedly n (fn []
                                   (if (zero? (u8))
                                     {:kind :rule :rule (rules (u32)) :children (u32)}
                                     (let [t (u32)
                                           t (if (> t 0x7fffffff) (- t 0x100000000) t)]
                                       {:kind :token
                                        ;; ANTLR's EOF is type -1; the packet
                                        ;; contract names it "EOF"
                                        :type (cond (= -1 t) "EOF"
                                                    (< -1 t (count symbols)) (symbols t)
                                                    :else t)
                                        :start (u32) :stop (u32) :text (text)})))))
        errors (vec (repeatedly (u32) (fn [] {:line (u32) :column (u32) :message (text)})))]
    {:magic magic :layout layout :status status :rule-count (count rules)
     :symbol-count (count symbols) :nodes nodes :errors errors
     :consumed @pos :length (alength bs)}))


(defn- render
  "The preorder records as an indented tree, for the eye."
  [nodes]
  (let [out (StringBuilder.)]
    (loop [nodes nodes stack []]
      (when-let [{:keys [kind] :as node} (first nodes)]
        (let [depth (count stack)]
          (.append out (apply str (repeat depth "  ")))
          (.append out (if (= :rule kind)
                         (:rule node)
                         (str (:type node) " " (pr-str (:text node)) " [" (:start node) "," (:stop node) "]")))
          (.append out "\n")
          (let [stack (if (and (= :rule kind) (pos? (:children node)))
                        (conj stack (:children node))
                        stack)
                stack (if (= :rule kind) stack (loop [s stack]
                                                 (if (empty? s) s
                                                     (let [s (update s (dec (count s)) dec)]
                                                       (if (zero? (peek s)) (recur (pop s)) s)))))
                ;; a rule with no children closes immediately too
                stack (if (and (= :rule kind) (zero? (:children node)))
                        (loop [s stack]
                          (if (empty? s) s
                              (let [s (update s (dec (count s)) dec)]
                                (if (zero? (peek s)) (recur (pop s)) s))))
                        stack)]
            (recur (rest nodes) stack)))))
    (str out)))


(println "library" library)
(check "layout version entry answers 1" (= 1 (c-layout-version)) (c-layout-version))


(let [t0 (System/nanoTime)
      {:keys [status bytes]} (parse-bytes "1 + 2\n")
      ms (/ (- (System/nanoTime) t0) 1e6)
      tree (decode bytes)
      tokens (filterv #(= :token (:kind %)) (:nodes tree))]
  (println "RECORD parse \"1 + 2\\n\":" {:status status :buffer-bytes (alength bytes) :ms ms})
  (println "RECORD first 32 buffer bytes:" (vec (take 32 bytes)))
  (print (render (:nodes tree)))
  (check "jolt_parser_parse returned 0 and a JPT1 layout-1 buffer, consumed exactly"
         (and (= 0 status) (= "JPT1" (:magic tree)) (= 1 (:layout tree))
              (= (:consumed tree) (:length tree)))
         (select-keys tree [:magic :layout :status :consumed :length]))
  (check "no syntax errors" (and (zero? (:status tree)) (empty? (:errors tree))) (:errors tree))
  (check "root is file_input" (= "file_input" (:rule (first (:nodes tree)))) (first (:nodes tree)))
  (check "tokens are NUMBER 1, ADD +, NUMBER 2, NEWLINE, EOF with code-point spans"
         (= [["NUMBER" "1" 0 0] ["ADD" "+" 2 2] ["NUMBER" "2" 4 4] ["NEWLINE" "\n" 5 5]]
            (mapv (juxt :type :text :start :stop) (butlast tokens)))
         (mapv (juxt :type :text :start :stop) tokens))
  (check "last token is EOF" (= "EOF" (:type (last tokens))) (last tokens)))


(let [{:keys [status bytes]} (parse-bytes "1 +\n")
      tree (decode bytes)]
  (check "malformed \"1 +\\n\" answers status 0 with a syntax-error buffer (no exception crosses)"
         (and (= 0 status) (= 1 (:status tree)) (seq (:errors tree)))
         (:errors tree)))


;; Containment of the failure paths (review F1), forced through the test
;; seam: each must answer its status to jolt, with the process still running.
(defn- fault
  [mode]
  (c-inject-fault mode)
  (try (parse-bytes "1 + 2\n") (finally (c-inject-fault 0))))


(if-not c-inject-fault
  (println "SKIP the 4 fault-injection checks: this library has no jolt_parser_inject_fault (built without JOLT_PARSER_TEST_SEAMS)")
  (do
    (let [{:keys [status bytes]} (fault 1)]
      (check "[injected] std::bad_alloc inside the parse -> status -2 with the message copied out"
             (and (= -2 status) (= "std::bad_alloc" (String. ^bytes bytes "UTF-8")))
             {:status status :message (String. ^bytes bytes "UTF-8")}))
    (let [{:keys [status bytes]} (fault 2)]
      (check "[injected] non-std exception -> status -3 \"unknown exception\""
             (and (= -3 status) (= "unknown exception" (String. ^bytes bytes "UTF-8")))
             {:status status :message (String. ^bytes bytes "UTF-8")}))
    (let [{:keys [status bytes]} (fault 3)]
      (check "[injected] bad_alloc and the handler's own malloc fails -> status -2, no buffer, no throw"
             (and (= -2 status) (zero? (alength ^bytes bytes)))
             {:status status :buffer-bytes (alength ^bytes bytes)}))
    (let [{:keys [status bytes]} (parse-bytes "1 + 2\n")
          tree (decode bytes)]
      (check "after the injected failures the same process parses \"1 + 2\\n\" again"
             (and (= 0 status) (= "JPT1" (:magic tree)) (empty? (:errors tree))
                  (= (:consumed tree) (:length tree)))
             {:status status :buffer-bytes (alength ^bytes bytes)}))))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
