(ns affected
  "The test namespaces a change can affect, and a runner for them.

   The changed files are `git diff --name-only <base>` plus the staged and
   untracked files; base defaults to `git merge-base HEAD master`. Each
   .clj .cljc .cljs .cljd file under src/ and test/ (not test/cljd-out) is
   read for its first ns form, once per host feature (:clj :cljs :cljd),
   so every reader-conditional branch counts. The affected tests are the
   test namespaces (under test/, named *-test or holding a deftest) in the
   reverse transitive closure of the changed namespaces, plus:
   - a changed src file's conventional test (src/cljc/a/b.cljc selects
     test/a/b_test.*), required or not;
   - for a non-code file (a fixture under test/resources, a src resource),
     the closure of every namespace whose text mentions its path or
     basename: fixtures are read by path, not required;
   - for any changed file, the closure of every namespace that walks its
     tree with (file-seq (io/file \"<root>\")).
   Ignored: docs/ collab/ build/ target/ node_modules/ cljd-out and *.md
   (but a .md under test/resources is a fixture). Wide: deps.edn bb.edn
   shadow-cljs.edn tests.edn package*.json pubspec.* .clj-kondo/ antlr/
   src/dev/; a wide change selects every test and runs nothing.

   Run with babashka:
     bb src/dev/affected.clj [--list] [--base REF] [--lane clj|cljs|cljd]
                             [--changed FILE...]
   --list prints the selection and each changed file's reason; no run.
   --lane restricts to one lane (repeatable; default all three).
   --changed replaces the git query with the given files.
   Without --list the selected lanes run in parallel, each logging to
   target/profile/changed-<lane>.log, and a summary table follows.
   Exit: 0 pass (or nothing selected), 1 a lane failed or bad input,
   2 wide change: run bb test."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [edamame.core :as e]))


(def code-exts #{"clj" "cljc" "cljs" "cljd"})
(def lanes [:clj :cljs :cljd])
(def log-dir "target/profile")
(def wide-exit 2)


(def ignored-prefixes
  ["docs/" "collab/" "build/" "target/" "node_modules/" "test/cljd-out/"
   "lib/cljd-out/"])


(def wide-files
  #{"deps.edn" "bb.edn" "shadow-cljs.edn" "tests.edn" "package.json"
    "package-lock.json" "pubspec.yaml" "pubspec.lock" "mise.toml"
    ".cljstyle"})


(def wide-prefixes [".clj-kondo/" "antlr/" "src/dev/"])


(defn- starts-with-any?
  [path prefixes]
  (some #(str/starts-with? path %) prefixes))


(defn classify
  "One of :ignored :wide :code :resource for the repo-relative `path`."
  [path]
  (cond (starts-with-any? path ignored-prefixes) :ignored
        (str/starts-with? path "test/resources/") :resource
        (str/ends-with? path ".md") :ignored
        (or (wide-files path) (starts-with-any? path wide-prefixes)) :wide
        (and (starts-with-any? path ["src/" "test/"])
             (code-exts (fs/extension path))) :code
        :else :resource))


;; ---------------------------------------------------------------------------
;; ns forms
;; ---------------------------------------------------------------------------

(def ^:private dep-clauses #{:require :use :require-macros :use-macros})


(defn ns-name-of
  [form]
  (second form))


(defn- libspec-deps
  "Namespaces named by one libspec: a symbol, [lib & opts] or a prefix
   list [prefix suffix [suffix & opts] ...]. Strings (npm) name none."
  [spec]
  (cond (symbol? spec) [spec]
        (sequential? spec)
        (let [[head & more] spec]
          (cond (not (symbol? head)) []
                (or (empty? more) (keyword? (first more))) [head]
                :else (->> more
                           (filter (some-fn symbol? sequential?))
                           (mapcat libspec-deps)
                           (map #(symbol (str head "." %))))))
        :else []))


(defn form-deps
  "Namespaces an ns form loads through :require :use :require-macros
   and :use-macros."
  [form]
  (->> (rest form)
       (filter seq?)
       (filter (comp dep-clauses first))
       (mapcat rest)
       (mapcat libspec-deps)
       set))


(defn- read-opts
  [feature]
  {:all true
   :read-cond :allow
   :features #{feature}
   :auto-resolve (fn [k] (if (= :current k) 'user k))
   :readers (fn [_tag] identity)
   :eof ::eof})


(defn- first-ns-form
  [text feature]
  (let [rdr (e/reader text)
        opts (e/normalize-opts (read-opts feature))]
    (loop []
      (let [form (e/parse-next rdr opts)]
        (cond (= ::eof form) nil
              (and (seq? form) (= 'ns (first form))) form
              :else (recur))))))


(defn ns-info
  "{:ns name :deps #{...}} of the first ns form of `text`, the deps the
   union over the :clj :cljs and :cljd readings and a reading no branch
   but :default matches."
  [text]
  (let [forms (if (str/includes? text "#?")
                (keep #(first-ns-form text %) (conj lanes ::none))
                (keep identity [(first-ns-form text :clj)]))]
    {:ns (some ns-name-of forms)
     :deps (into #{} (mapcat form-deps) forms)}))


(def ^:private code-path
  #"^(?:src/(?:clj|cljc|cljs|cljd|dev)/|test/)(.+)\.(?:clj|cljc|cljs|cljd)$")


(defn path->ns
  "The namespace a code path declares by convention (for deleted files)."
  [path]
  (when-let [[_ rel] (re-find code-path path)]
    (symbol (-> rel (str/replace "/" ".") (str/replace "_" "-")))))


(defn convention-test-paths
  "test/a/b_test.<ext> candidates for src/<host>/a/b.<ext>."
  [path]
  (when-let [[_ rel] (re-find #"^src/[^/]+/(.+)\.(?:clj|cljc|cljs|cljd)$"
                              path)]
    (mapv #(str "test/" rel "_test." %) ["clj" "cljc" "cljs" "cljd"])))


(defn mentions?
  "True when `text` mentions `path` or its basename."
  [text path]
  (or (str/includes? text path)
      (str/includes? text (str (fs/file-name path)))))


(def ^:private walk-call
  #"file-seq|fs/glob|fs/list-dir|fs/walk-file-tree|\.listFiles")


(def ^:private path-call
  #"\((?:io/file|fs/file|fs/path|fs/glob|java\.io\.File\.|File\.)\s")


(defn- string-end
  "Index of the quote closing the string literal opened at `i`."
  [^String text i]
  (loop [j (inc i)]
    (cond (>= j (count text)) j
          (= \\ (.charAt text j)) (recur (+ j 2))
          (= \" (.charAt text j)) j
          :else (recur (inc j)))))


(defn- literal-args
  "The string literals that are direct arguments of the call whose open
   paren is at `start` in `text`. Nested forms, comments and character
   literals are skipped."
  [^String text start]
  (loop [i (inc start)
         depth 1
         acc []]
    (if (or (zero? depth) (>= i (count text)))
      acc
      (let [c (.charAt text i)]
        (cond (= \" c) (let [end (string-end text i)]
                         (recur (inc end) depth
                                (cond-> acc
                                  (= 1 depth)
                                  (conj (subs text (inc i)
                                              (min end (count text)))))))
              (= \\ c) (recur (+ i 2) depth acc)
              (= \; c) (recur (or (some-> (str/index-of text "\n" i) inc)
                                  (count text))
                              depth acc)
              (#{\( \[ \{} c) (recur (inc i) (inc depth) acc)
              (#{\) \] \}} c) (recur (inc i) (dec depth) acc)
              :else (recur (inc i) depth acc))))))


(defn- trim-root
  [root]
  (let [r (-> root
              (str/replace #"^(?:\./)+" "")
              (str/replace #"/+$" ""))]
    (if (str/blank? r) "." r)))


(defn scanner-roots
  "The roots a text that walks a directory (file-seq, fs/glob,
   fs/list-dir, fs/walk-file-tree, .listFiles) may walk: every string
   literal argument of every io/file, fs/file, fs/path, fs/glob,
   java.io.File. or File. call in it, so a let-bound root counts. A root
   built at run time (str, a computed path) does not."
  [text]
  (if-not (re-find walk-call text)
    []
    (let [m (re-matcher path-call text)]
      (->> (loop [acc []]
             (if (.find m)
               (recur (into acc (literal-args text (.start m))))
               acc))
           (map trim-root)
           distinct
           vec))))


(defn under-root?
  "True when `path` is `root` or lies in its tree; \".\" holds every
   path."
  [root path]
  (or (= "." root) (= root path) (str/starts-with? path (str root "/"))))


(defn index-entry
  "What the selection needs to know about one code file."
  [path text]
  (let [{:keys [ns deps]} (ns-info text)]
    {:path path
     :ext (fs/extension path)
     :ns ns
     :deps deps
     :text text
     :roots (scanner-roots text)
     :test? (boolean
              (and ns
                   (str/starts-with? path "test/")
                   (or (str/ends-with? (name ns) "-test")
                       (re-find #"\((?:[\w.-]+/)?deftest\s" text))))}))


;; ---------------------------------------------------------------------------
;; Selection
;; ---------------------------------------------------------------------------

(defn reverse-closure
  "`roots` and every node of `graph` {node #{dep}} that reaches one."
  [graph roots]
  (let [rdeps (reduce-kv (fn [m n deps]
                           (reduce #(update %1 %2 (fnil conj #{}) n) m deps))
                         {}
                         graph)]
    (loop [seen (set roots)
           todo (vec roots)]
      (if-let [n (peek todo)]
        (let [new (remove seen (rdeps n))]
          (recur (into seen new) (into (pop todo) new)))
        seen))))


(defn- forward-closure
  [graph roots]
  (loop [seen (set roots)
         todo (vec roots)]
    (if-let [n (peek todo)]
      (let [new (remove seen (graph n))]
        (recur (into seen new) (into (pop todo) new)))
      seen)))


(defn- ns-graph
  [index]
  (reduce (fn [g {:keys [ns deps]}] (update g ns (fnil into #{}) deps))
          {}
          (filter :ns index)))


(defn- show
  [syms]
  (let [names (sort (map str syms))]
    (if (> (count names) 4)
      (str (str/join ", " (take 4 names)) ", ... (" (count names) ")")
      (str/join ", " names))))


(defn select
  "{:wide [path] :reasons {path {:why s :tests #{}}} :tests #{}} for the
   `changed` paths over `index` (seq of index-entry)."
  [index changed]
  (let [by-path (into {} (map (juxt :path identity)) index)
        graph (ns-graph index)
        test-nses (into #{} (comp (filter :test?) (map :ns)) index)
        tests-of #(into #{} (filter test-nses) (reverse-closure graph %))
        scanners (fn [path]
                   (into #{}
                         (comp (filter (fn [{:keys [roots]}]
                                         (some #(under-root? % path)
                                               roots)))
                               (map :ns))
                         index))
        reason (fn [path]
                 (let [kind (classify path)
                       scan (scanners path)
                       scan-why (when (seq scan)
                                  (str "; tree walked by " (show scan)))]
                   (case kind
                     :ignored {:why "ignored" :tests #{}}
                     :wide {:why "wide change" :tests #{}}
                     :code
                     (let [entry (by-path path)
                           ns (or (:ns entry) (path->ns path))
                           conv (into #{}
                                      (comp (keep by-path)
                                            (filter :test?)
                                            (map :ns))
                                      (convention-test-paths path))]
                       {:why (str "ns " ns (when-not entry " (deleted)")
                                  (when (seq conv)
                                    (str "; convention test " (show conv)))
                                  scan-why)
                        :tests (into (tests-of (cond-> scan ns (conj ns)))
                                     conv)})
                     :resource
                     (let [readers (into #{}
                                         (comp (filter #(mentions? (:text %)
                                                                   path))
                                               (map :ns))
                                         index)]
                       {:why (str (if (seq readers)
                                    (str "mentioned by " (show readers))
                                    "no source mentions it")
                                  scan-why)
                        :tests (tests-of (into readers scan))}))))
        reasons (into (sorted-map) (map (juxt identity reason)) changed)
        wide (filterv #(= :wide (classify %)) changed)]
    {:wide wide
     :reasons reasons
     :tests (if (seq wide)
              test-nses
              (into #{} (mapcat :tests) (vals reasons)))}))

(def subsystems-config-path "src/dev/subsystems.edn")


(defn load-subsystems-config
  ([] (load-subsystems-config subsystems-config-path))
  ([path]
   (when (fs/exists? path)
     (edn/read-string (slurp path)))))


(defn match-subsystem
  "Maps a namespace symbol to its canonical subsystem map via longest prefix match."
  [sub-cfg ns-sym]
  (let [ns-str (str ns-sym)
        matches (for [s (:subsystems sub-cfg)
                      p (:prefixes s)
                      :when (or (= ns-str p)
                                (str/starts-with? ns-str (str p "."))
                                (str/starts-with? ns-str (str p "-")))]
                  {:sub s :prefix p :len (count p)})]
    (:sub (first (sort-by (comp - :len) matches)))))


(defn select-subsystems
  "Returns set of test namespaces belonging to `sub-names`."
  [index sub-cfg sub-names]
  (let [valid-names (into #{} (map :name) (:subsystems sub-cfg))
        unknown (remove valid-names sub-names)]
    (when (seq unknown)
      (throw (ex-info (str "unknown subsystem: " (str/join ", " unknown))
                      {:unknown unknown})))
    (let [target-subs (set sub-names)
          test-nses (into #{} (comp (filter :test?) (map :ns)) index)]
      (into #{}
            (filter (fn [ns-sym]
                      (when-let [s (match-subsystem sub-cfg ns-sym)]
                        (target-subs (:name s)))))
            test-nses))))


(defn subsystem-seams
  "Computes Class B seams and dependent subsystems for `sub-names`."
  [index sub-cfg sub-names]
  (let [graph (ns-graph index)
        sub-by-name (into {} (map (juxt :name identity)) (:subsystems sub-cfg))
        target-subs (into #{} (keep sub-by-name) sub-names)
        source-nses (into #{} (comp (remove :test?) (keep :ns)) index)
        test-nses (into #{} (comp (filter :test?) (keep :ns)) index)
        target-tests (filter (fn [t]
                               (when-let [s (match-subsystem sub-cfg t)]
                                 (some #(= (:name s) (:name %)) target-subs)))
                             test-nses)
        class-b (for [t target-tests
                      dep (forward-closure graph #{t})
                      :when (source-nses dep)
                      :let [dep-sub (match-subsystem sub-cfg dep)
                            t-sub (match-subsystem sub-cfg t)]
                      :when (and dep-sub t-sub
                                 (number? (:layer dep-sub))
                                 (number? (:layer t-sub))
                                 (> (:layer dep-sub) (:layer t-sub)))]
                  [t dep (:name dep-sub)])
        target-sources (into #{}
                             (filter (fn [s-ns]
                                       (when-let [s (match-subsystem sub-cfg s-ns)]
                                         (some #(= (:name s) (:name %)) target-subs))))
                             source-nses)
        other-tests (remove (fn [t]
                              (when-let [s (match-subsystem sub-cfg t)]
                                (some #(= (:name s) (:name %)) target-subs)))
                            test-nses)
        dependent-tests (for [t other-tests
                              :let [reaches (forward-closure graph #{t})]
                              :when (some target-sources reaches)
                              :let [s (match-subsystem sub-cfg t)]
                              :when s]
                          (:name s))]
    {:class-b (distinct class-b)
     :dependents (into (sorted-set) dependent-tests)}))


(defn- lane-file?
  [lane {:keys [ext path]}]
  (case lane
    :clj (#{"clj" "cljc"} ext)
    :cljs (#{"cljs" "cljc"} ext)
    ;; cljd_agg.clj compiles only *_test.cljc and *_test.cljd files.
    :cljd (re-find #"_test\.clj[cd]$" path)))


(defn lane-tests
  "The `tests` namespaces with a source file `lane` runs, sorted."
  [index tests lane]
  (->> index
       (filter #(and (:test? %) (tests (:ns %)) (lane-file? lane %)))
       (map :ns)
       distinct
       (sort-by str)
       vec))


(defn ns-regexp
  "An anchored alternation of `nses`, dots escaped for an EDN string, as
   cljd_agg.clj --slow-regex prints it for shadow-cljs --config-merge."
  [nses]
  (str "^(" (str/join "|" (map #(str/replace (str %) "." "\\\\.") nses))
       ")$"))


(defn- sum-of
  [re text]
  (let [ms (re-seq re text)]
    (when (seq ms)
      (reduce + (map #(reduce + (map parse-long (rest %))) ms)))))


(defn parse-summary
  "{:tests n :failures n} from a lane log; nil values when unreadable.
   Dart counts the last flutter progress line: tests are passed plus
   failed, failures the failed."
  [lane text]
  (if (= :cljd lane)
    (if-let [[_ ok _skip bad] (last (re-seq #"\+(\d+)(?: ~(\d+))?(?: -(\d+))?:"
                                            text))]
      (let [bad (if bad (parse-long bad) 0)]
        {:tests (+ (parse-long ok) bad) :failures bad})
      {:tests nil :failures nil})
    {:tests (sum-of #"Ran (\d+) tests" text)
     :failures (sum-of #"(\d+) failures, (\d+) errors" text)}))


(defn- reached-text
  "The source texts of `tests` and everything they require."
  [index tests]
  (let [nses (forward-closure (ns-graph index) tests)]
    (keep #(when (nses (:ns %)) (:text %)) index)))


(defn prerequisites
  "The bb build tasks the per-lane selection `selected` {lane [ns]} needs:
   a task is needed when a selected test, or a namespace it requires,
   names the artifact the task builds. ANTLR classes are JVM only."
  [index selected]
  (let [all (into #{} (mapcat val) selected)
        texts (reached-text index all)
        clj-texts (reached-text index (set (:clj selected)))
        any? (fn [texts re] (some #(re-find re %) texts))]
    (cond-> #{}
      (any? texts #"yin-repl\.js") (conj :build:yin-repl-node)
      (any? texts #"yin-repl-peer") (conj :build:yin-repl-peer)
      (any? clj-texts #"Python3Parser|Python3Lexer|build/antlr")
      (conj :gen:python-antlr))))


(defn serialized-pairs
  "Lane pairs that must not overlap: a JVM test that runs the cljd
   compiler rewrites lib/cljd-out under the Dart lane. Any mention of
   clojuredart in the JVM selection's reach counts."
  [index selected]
  (if (and (seq (:clj selected)) (seq (:cljd selected))
           (some #(str/includes? % "clojuredart")
                 (reached-text index (set (:clj selected)))))
    #{[:clj :cljd]}
    #{}))


(defn parse-args
  [args]
  (loop [opts {:list? false :base nil :lanes [] :changed nil
               :subsystems [] :slow? false}
         [a & more :as args] args]
    (cond (empty? args)
          (update opts :lanes #(if (empty? %) lanes (vec (distinct %))))
          (= "--list" a) (recur (assoc opts :list? true) more)
          (= "--slow" a) (recur (assoc opts :slow? true) more)
          (= "--base" a) (recur (assoc opts :base (first more)) (rest more))
          (= "--lane" a)
          (let [lane (keyword (first more))]
            (when-not (some #{lane} lanes)
              (throw (ex-info (str "unknown lane: " (first more)) {})))
            (recur (update opts :lanes conj lane) (rest more)))
          (= "--subsystem" a)
          (if (or (empty? more) (str/starts-with? (first more) "--"))
            (throw (ex-info "--subsystem needs a subsystem name" {}))
            (recur (update opts :subsystems conj (first more)) (rest more)))
          (= "--changed" a)
          (let [[files rest-args] (split-with #(not (str/starts-with? % "--"))
                                              more)]
            (when (empty? files)
              (throw (ex-info "--changed needs at least one file" {})))
            (recur (assoc opts :changed (vec files)) rest-args))
          :else (throw (ex-info (str "unknown option: " a) {})))))


(defn normalize-path
  "`path` relative to the absolute `root`, or nil when it lies outside.
   Accepts root-relative, ./-prefixed and absolute paths."
  [root path]
  (let [root (fs/normalize (fs/path root))
        full (fs/normalize (if (fs/absolute? path)
                             (fs/path path)
                             (fs/path root path)))]
    (when (and (.startsWith full root) (not= full root))
      (str (fs/relativize root full)))))


(defn input-errors
  "Reasons to refuse a run that would pass vacuously: an empty index (the
   wrong cwd), an explicit empty change list, or an explicit path that
   exists nowhere (a typo). That is stricter than selecting nothing: a
   mistyped src path still selects the tests that walk src/. `exists?`
   tests a path on disk or in git's base tree (a deleted file);
   git-derived paths are real by construction."
  [{:keys [index changed explicit? exists?]}]
  (let [indexed (into #{} (map :path) index)
        typo? (fn [path] (not (or (indexed path) (exists? path))))]
    (cond-> []
      (empty? index)
      (conj "read 0 code files under src/ and test/: run from the repo root")
      (and explicit? (empty? changed))
      (conj "--changed needs at least one file")
      explicit?
      (into (map #(str "no such file, selects nothing: " %))
            (filter typo? changed)))))


;; ---------------------------------------------------------------------------
;; IO
;; ---------------------------------------------------------------------------

(defn- code-files
  []
  (->> (concat (fs/glob "src" "**.{clj,cljc,cljs,cljd}")
               (fs/glob "test" "**.{clj,cljc,cljs,cljd}"))
       (map str)
       (remove #(str/includes? % "cljd-out"))
       sort))


(defn scan-index
  "Index every code file; exits 1 naming each file that does not read."
  []
  (let [results (map (fn [path]
                       (try (index-entry path (slurp path))
                            (catch Exception ex
                              {:error (str path ": " (ex-message ex))})))
                     (code-files))
        errors (keep :error results)]
    (when (seq errors)
      (run! println errors)
      (System/exit 1))
    (vec results)))


(defn- git-lines
  [& args]
  (let [{:keys [exit out err]} (apply p/sh "git" args)]
    (when-not (zero? exit)
      (println "git" (str/join " " args) "failed:" err)
      (System/exit 1))
    (remove str/blank? (str/split-lines out))))


(defn- base-ref
  [base]
  (or base (first (git-lines "merge-base" "HEAD" "master"))))


(defn- in-base-tree?
  "True when `path` exists in the base commit (a file the change deleted)."
  [base path]
  (zero? (:exit (p/sh "git" "cat-file" "-e"
                      (str (base-ref base) ":" path)))))


(defn changed-files
  [base]
  (let [base (base-ref base)]
    (->> (concat (git-lines "diff" "--name-only" base)
                 (git-lines "diff" "--name-only" "--cached" base)
                 (git-lines "ls-files" "--others" "--exclude-standard"))
         distinct
         sort
         vec)))


(defn- print-selection
  [index {:keys [wide reasons tests]} selected]
  (let [total (count (into #{} (comp (filter :test?) (map :ns)) index))]
    (println (format "Read %d code files under src/ and test/."
                     (count index)))
    (println "Changed files:")
    (doseq [[path {:keys [why] :as r}] reasons]
      (println (format "  %s: %s -> %d tests" path why (count (:tests r)))))
    (doseq [lane (keys selected)]
      (let [nses (selected lane)]
        (println (format "%s: %d namespaces" (name lane) (count nses)))
        (doseq [n nses] (println "  " n))))
    (println (format "Selected %d of %d test namespaces." (count tests)
                     total))
    (when (seq wide)
      (println "wide change: run bb test"))))


(defn- lane-command
  ([lane nses] (lane-command lane nses false))
  ([lane nses slow?]
   (case lane
     :clj (into ["clojure" "-M:test" (if slow? "-i" "-e") ":slow"]
                (mapcat #(vector "-n" (str %)) nses))
     :cljs ["clj" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "compile" "test"
            "--config-merge" (str "{:ns-regexp \"" (ns-regexp nses) "\"}")]
     :cljd ["bb" "src/dev/cljd_agg.clj" "--only" (str/join "," nses)])))


(defn- log-file
  ([lane] (log-file lane "changed-"))
  ([lane prefix]
   (str log-dir "/" prefix (name lane) ".log")))


(defn- run-lane!
  ([lane nses] (run-lane! lane nses "changed-" false))
  ([lane nses prefix slow?]
   (let [log (log-file lane prefix)
         env (cond-> (into {} (System/getenv))
               slow? (assoc "DATOM_SLOW_TESTS" "1")
               (and (not slow?) (= :cljs lane)) (dissoc "DATOM_SLOW_TESTS"))
         start (System/nanoTime)
         _ (println (format "%s: %d namespaces, log %s" (name lane)
                            (count nses) log))
         exit (-> (p/process (lane-command lane nses slow?)
                             {:out (fs/file log) :err :out :env env})
                  deref
                  :exit)
         secs (/ (- (System/nanoTime) start) 1e9)
         summary (parse-summary lane (slurp log))]
     (println (format "%s: done, exit %d, %.0f s" (name lane) exit secs))
     (assoc summary :lane lane :namespaces (count nses) :exit exit
            :seconds secs
            :status (if (and (zero? exit) (= 0 (:failures summary)))
                      "pass" "FAIL")))))


(defn- run-lanes!
  "Run the non-empty lanes in parallel; each pair in `serial` runs in
   order inside one thread."
  ([selected serial] (run-lanes! selected serial "changed-" false))
  ([selected serial prefix slow?]
   (let [groups (reduce (fn [gs [a b]]
                          (if (and (selected a) (selected b))
                            (-> (remove #(some #{a b} %) gs)
                                vec
                                (conj [a b]))
                            gs))
                        (mapv vector (keys selected))
                        serial)]
     (->> groups
          (mapv (fn [group]
                  (future (mapv #(run-lane! % (selected %) prefix slow?) group))))
          (mapcat deref)
          (sort-by (comp #(.indexOf ^java.util.List lanes %) :lane))))))


(defn- print-table
  [rows]
  (println)
  (println (format "%-5s %10s %6s %8s %7s  %s" "lane" "namespaces" "tests"
                   "failures" "seconds" "status"))
  (doseq [{:keys [lane namespaces tests failures seconds status]} rows]
    (println (format "%-5s %10d %6s %8s %7s  %s" (name lane) namespaces
                     (or tests "-") (or failures "-")
                     (if seconds (format "%.0f" seconds) "-") status))))


(defn- print-subsystem-selection
  [index sub-names tests selected seams]
  (let [total (count (into #{} (comp (filter :test?) (map :ns)) index))]
    (println (format "Read %d code files under src/ and test/." (count index)))
    (println "Subsystems:" (str/join ", " sub-names))
    (doseq [lane (keys selected)]
      (let [nses (selected lane)]
        (println (format "%s: %d namespaces" (name lane) (count nses)))
        (doseq [n nses] (println "  " n))))
    (println (format "Selected %d of %d test namespaces." (count tests) total))
    (when seams
      (println)
      (println "Class B seams (test reaches higher-layer source):")
      (if (empty? (:class-b seams))
        (println "  none")
        (doseq [[t dep s] (sort-by (juxt (comp str first) (comp str second))
                                   (:class-b seams))]
          (println (format "  %s -> %s (%s)" t dep s))))
      (println)
      (println "Dependent subsystems (tests in other subsystems that reach this source):")
      (if (empty? (:dependents seams))
        (println "  none")
        (doseq [s (:dependents seams)]
          (println "  " s))))))


(defn -main
  [& args]
  (let [{:keys [list? base changed subsystems slow?] :as opts}
        (try (parse-args args)
             (catch clojure.lang.ExceptionInfo ex
               (println (ex-message ex))
               (System/exit 1)))
        index (scan-index)]
    (if (seq subsystems)
      (let [sub-cfg (load-subsystems-config)
            _ (when-not sub-cfg
                (println "could not read" subsystems-config-path)
                (System/exit 1))
            tests (try (select-subsystems index sub-cfg subsystems)
                       (catch clojure.lang.ExceptionInfo ex
                         (println (ex-message ex))
                         (System/exit 1)))
            selected (into (array-map)
                           (map (juxt identity #(lane-tests index tests %)))
                           (:lanes opts))
            seams (when list? (subsystem-seams index sub-cfg subsystems))]
        (print-subsystem-selection index subsystems tests selected seams)
        (when-not list?
          (let [run (into (array-map) (filter (comp seq val)) selected)
                skipped (for [[lane nses] selected :when (empty? nses)]
                          {:lane lane :namespaces 0 :status "skipped"})
                prereqs (prerequisites index run)
                serial (serialized-pairs index run)]
            (doseq [task [:build:yin-repl-peer :build:yin-repl-node
                          :gen:python-antlr]]
              (if (prereqs task)
                (do (println "Prerequisite" (name task)
                             "(a selected test or its requires name its output)")
                    (let [{:keys [exit]} (p/shell {:continue true} "bb"
                                                  (name task))]
                      (when-not (zero? exit)
                        (println (name task) "failed, exit" exit)
                        (System/exit 1))))
                (println "Skipping" (name task)
                         "(no selected test reaches its output)")))
            (doseq [[a b] serial]
              (println "Serializing" (name a) "then" (name b)
                       "(a JVM test runs the cljd compiler)"))
            (fs/create-dirs log-dir)
            (let [rows (sort-by (comp #(.indexOf ^java.util.List lanes %) :lane)
                                (concat (run-lanes! run serial "sub-" slow?) skipped))]
              (print-table rows)
              (System/exit (if (some #(= "FAIL" (:status %)) rows) 1 0))))))
      (let [explicit? (some? changed)
            root (str (fs/canonicalize
                        (first (git-lines "rev-parse" "--show-toplevel"))))
            given (vec changed)
            changed (if explicit?
                      (mapv #(normalize-path root (if (fs/absolute? %)
                                                    (str (fs/canonicalize %))
                                                    %))
                            given)
                      (changed-files base))
            outside (keep (fn [[g c]] (when-not c g)) (map vector given changed))
            changed (vec (remove nil? changed))
            errors (into (mapv #(str "outside the repo: " %) outside)
                         (input-errors
                           {:index index
                            :changed changed
                            :explicit? explicit?
                            :exists? (fn [path]
                                       (or (fs/exists? path)
                                           (in-base-tree? base path)))}))
            _ (when (seq errors)
                (run! println errors)
                (System/exit 1))
            {:keys [wide tests] :as sel} (select index changed)
            selected (into (array-map)
                           (map (juxt identity #(lane-tests index tests %)))
                           (:lanes opts))]
        (print-selection index sel selected)
        (when (seq wide)
          (System/exit wide-exit))
        (when-not list?
          (let [run (into (array-map) (filter (comp seq val)) selected)
                skipped (for [[lane nses] selected :when (empty? nses)]
                          {:lane lane :namespaces 0 :status "skipped"})
                prereqs (prerequisites index run)
                serial (serialized-pairs index run)]
            (doseq [task [:build:yin-repl-peer :build:yin-repl-node
                          :gen:python-antlr]]
              (if (prereqs task)
                (do (println "Prerequisite" (name task)
                             "(a selected test or its requires name its output)")
                    (let [{:keys [exit]} (p/shell {:continue true} "bb"
                                                  (name task))]
                      (when-not (zero? exit)
                        (println (name task) "failed, exit" exit)
                        (System/exit 1))))
                (println "Skipping" (name task)
                         "(no selected test reaches its output)")))
            (doseq [[a b] serial]
              (println "Serializing" (name a) "then" (name b)
                       "(a JVM test runs the cljd compiler)"))
            (fs/create-dirs log-dir)
            (let [rows (sort-by (comp #(.indexOf ^java.util.List lanes %) :lane)
                                (concat (run-lanes! run serial "changed-" slow?) skipped))]
              (print-table rows)
              (System/exit (if (some #(= "FAIL" (:status %)) rows) 1 0)))))))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
