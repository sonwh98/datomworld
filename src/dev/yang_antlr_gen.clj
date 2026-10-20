(ns yang-antlr-gen
  "Build-time generation of the grammars-v4 parsers for every host target
   (docs/design/yang.antlr.md §4.2). Reproducible from clean:

     clj -M:antlr-gen [--grammar g] [--target java|javascript|dart|cpp]
                                 ; or: bb gen:antlr

   1. Read the vendored grammars (antlr/<grammar>/*.g4, MIT, committed) and
      fetch the remaining pinned files at the manifest's revision (cached
      under build/, re-fetched only when absent); refuse any digest mismatch.
   2. For each manifest :targets entry (:java, :javascript, :dart), stage the
      grammars and that target's helpers, applying the recorded patches (the
      Java helpers also get their package clause prepended).
   3. Run the pinned ANTLR tool with the target's flags, then apply the
      target's recorded :output-patches to the files it wrote.
   4. Java only: compile with the host javac into build/antlr/<g>/classes,
      which deps.edn puts on the classpath.
   5. Digest every generated source file of the target and compare with the
      target's recorded :generated digests; a mismatch fails the build.

   `clj -M:antlr-gen discover` prints the head commit of the grammar path, for
   deliberately moving the pin. Nothing here runs at admission time."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str])
  (:import
    (io.github.rctcwyvrn.blake3
      Blake3)
    (java.net
      URI)
    (java.net.http
      HttpClient
      HttpClient$Redirect
      HttpRequest
      HttpResponse$BodyHandlers)
    (javax.tools
      ToolProvider)))


(def ^:dynamic *grammar* "python3")


(defn- manifest-path
  []
  (str "antlr/" *grammar* "/manifest.edn"))


(defn- build-dir
  []
  (str "build/antlr/" *grammar*))


(defn- blake3
  [^bytes bs]
  (let [b (Blake3/newInstance)]
    (.update b bs)
    (.hexdigest b)))


(defn- http-get-bytes
  ^bytes [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/NORMAL)
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "User-Agent" "datomworld-yang-antlr-gen")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofByteArray))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info "Fetch failed" {:url url, :status (.statusCode resp)})))
    (.body resp)))


(defn- read-manifest
  []
  (edn/read-string (slurp (manifest-path))))


(defn- raw-url
  [{:keys [repo revision path]} file]
  (str "https://raw.githubusercontent.com/" repo "/" revision "/" path "/" file))


(defn- read-bytes
  ^bytes [f]
  (java.nio.file.Files/readAllBytes (.toPath (io/file f))))


(defn- source-bytes!
  "A pinned file's bytes: a vendored file from the repository, otherwise
   the build cache, fetched from the pinned revision when absent."
  [grammar file {:keys [vendored]} target]
  (cond
    vendored (let [bs (read-bytes vendored)]
               (io/copy bs target)
               bs)
    (.exists target) (read-bytes target)
    :else (let [bs (http-get-bytes (raw-url grammar file))]
            (io/copy bs target)
            bs)))


(defn- fetch-grammar!
  "Each pinned file, vendored or fetched, digest-checked. A vendored file
   must also be ASCII (§4.5). A mismatching fetched file leaves the cache; a
   vendored file is never deleted."
  [{:keys [grammar]}]
  (let [src-dir (io/file (build-dir) "upstream")]
    (.mkdirs src-dir)
    (let [mismatches
          (vec (for [[file {expected :blake3, :as entry}]
                     (sort-by key (:files grammar))
                     :let [target (io/file src-dir (str/replace file "/" "__"))
                           bs (source-bytes! grammar file entry target)
                           actual (blake3 bs)
                           ascii? (every? #(<= 0 % 127) bs)]
                     :when (or (println (if (:vendored entry) "vendored" "fetched")
                                        file actual)
                               (not= expected actual)
                               (and (:vendored entry) (not ascii?)))]
                 (do (.delete target)
                     {:file file, :expected expected, :actual actual,
                      :ascii ascii?})))]
      (when (seq mismatches)
        (throw (ex-info "Grammar digest or ASCII check failed"
                        {:mismatches mismatches}))))
    src-dir))


(defn- delete-tree!
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (delete-tree! c)))
    (.delete f)))


(defn- apply-patch
  "One recorded exact-text replacement; the old text must occur exactly once."
  [file text [old new]]
  (let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote old))
                         text))]
    (when-not (= 1 n)
      (throw (ex-info "Patch does not apply" {:file file, :old old, :matches n})))
    (str/replace text old new)))


(defn- helper-ext
  [{:keys [helpers]}]
  (let [base (last (str/split (first helpers) #"/"))]
    (subs base (str/last-index-of base "."))))


(defn- target-exts
  "The source extensions a target keeps and digests: its :exts when given
   (C++ keeps headers beside sources), else its helpers' extension."
  [target]
  (or (:exts target) [(helper-ext target)]))


(defn- target-dir
  "Where a target's generated files (and copied helpers) land."
  ^java.io.File [{:keys [out package]}]
  (if package
    (io/file out (str/replace package "." "/"))
    (io/file out)))


(defn- stage-sources!
  "Copy the grammars and the target's helpers into one generation directory,
   applying the recorded patches: the package clause for the Java helpers,
   then the target's exact-text replacements."
  [{:keys [grammar]} {:keys [package patches helpers]} src-dir]
  (let [gen-in (io/file (build-dir) "grammar")]
    (delete-tree! gen-in)
    (.mkdirs gen-in)
    (doseq [file (sort (concat (filter #(not (str/includes? % "/"))
                                       (keys (:files grammar)))
                               helpers))]
      (let [text (slurp (io/file src-dir (str/replace file "/" "__")))
            base (last (str/split file #"/"))
            text (if (and package (str/ends-with? base ".java"))
                   (str "package " package ";\n" text)
                   text)
            text (reduce (partial apply-patch file)
                         text
                         (get patches file))]
        (spit (io/file gen-in base) text)))
    gen-in))


(defn- run-antlr!
  [{:keys [grammar]} {:keys [flags helpers output-patches] :as target} gen-in]
  (let [out (target-dir target)
        exts (target-exts target)]
    (delete-tree! (io/file (:out target)))
    (.mkdirs out)
    (let [tool (org.antlr.v4.Tool.
                 (into-array String
                             (concat flags
                                     ["-Xexact-output-dir" "-o" (.getPath out)
                                      "-lib" (.getPath gen-in)]
                                     (map #(.getPath (io/file gen-in %))
                                          (:generate grammar)))))]
      (.processGrammarsOnCommandLine tool)
      (when (pos? (.getNumErrors tool))
        (throw (ex-info "ANTLR generation failed"
                        {:errors (.getNumErrors tool)}))))
    (doseq [[file ps] output-patches
            :let [f (io/file out file)]]
      (spit f (reduce (partial apply-patch file) (slurp f) ps)))
    (doseq [h helpers
            :let [f (io/file gen-in (last (str/split h #"/")))]]
      (io/copy f (io/file out (.getName f))))
    (doseq [f (.listFiles out)
            :when (not-any? #(str/ends-with? (.getName f) %) exts)]
      (.delete f))
    out))


(defn- generated-digests
  [out exts]
  (into (sorted-map)
        (for [f (.listFiles (io/file out))
              :when (some #(str/ends-with? (.getName f) %) exts)]
          [(.getName f)
           (blake3 (java.nio.file.Files/readAllBytes (.toPath f)))])))


(defn- non-ascii-files
  "§4.5: generated textual sources stay ASCII."
  [out]
  (vec (for [f (.listFiles (io/file out))
             :when (some #(> (int %) 127) (slurp f))]
         (.getName f))))


(defn- compile-java!
  [pkg-dir]
  (let [classes (io/file (build-dir) "classes")
        compiler (ToolProvider/getSystemJavaCompiler)
        sources (sort (map #(.getPath %)
                           (filter #(str/ends-with? (.getName %) ".java")
                                   (.listFiles (io/file pkg-dir)))))]
    (delete-tree! classes)
    (.mkdirs classes)
    (when-not compiler
      (throw (ex-info "No system Java compiler (a JDK is required)" {})))
    (let [rc (.run compiler nil nil nil
                   (into-array String
                               (concat ["-nowarn" "--release" "11" "-cp"
                                        (System/getProperty "java.class.path")
                                        "-d" (.getPath classes)]
                                       sources)))]
      (when-not (zero? rc)
        (throw (ex-info "javac failed" {:exit rc}))))
    classes))


(defn- generate-target!
  [m src-dir [tname target]]
  (let [gen-in (stage-sources! m target src-dir)
        out (run-antlr! m target gen-in)
        digests (generated-digests out (target-exts target))
        non-ascii (non-ascii-files out)]
    (spit (io/file out "generated-digests.edn")
          (with-out-str (pprint/pprint digests)))
    (when (seq non-ascii)
      (throw (ex-info "Non-ASCII generated source" {:target tname
                                                    :files non-ascii})))
    (when-let [expected (:generated target)]
      (when-not (= expected digests)
        (throw (ex-info "Generated sources differ from the recorded digests"
                        {:target tname, :expected expected, :actual digests}))))
    (when (= :java tname) (compile-java! out))
    (println "generated" (count digests) (name tname) "files in" (.getPath out))
    (when-not (:generated target)
      (println "no :generated digests recorded yet for" tname "; record these:")
      (pprint/pprint digests))))


(defn generate!
  [only-target]
  (let [m (read-manifest)
        src-dir (fetch-grammar! m)]
    (doseq [t (sort-by key (:targets m))
            :when (or (nil? only-target) (= only-target (name (key t))))]
      (generate-target! m src-dir t))))


(defn discover!
  []
  (let [{:keys [grammar]} (read-manifest)
        url (str "https://api.github.com/repos/" (:repo grammar)
                 "/commits?per_page=1&path=" (:path grammar))
        body (String. (http-get-bytes url) "UTF-8")]
    (println (re-find #"\"sha\":\s*\"[0-9a-f]{40}\"" body))
    (println (re-find #"\"date\":\s*\"[^\"]+\"" body))
    (doseq [dir [(:path grammar) (str (:path grammar) "/Java")]]
      (let [url (str "https://api.github.com/repos/" (:repo grammar)
                     "/contents/" dir
                     (when (:revision grammar)
                       (str "?ref=" (:revision grammar))))
            listing (String. (http-get-bytes url) "UTF-8")]
        (println dir (mapv second (re-seq #"\"path\":\s*\"([^\"]+)\""
                                          listing)))))))


(defn- arg-value
  [args flag]
  (second (drop-while #(not= flag %) args)))


(defn -main
  [& args]
  (let [only-target (arg-value args "--target")
        grammars (if-let [g (arg-value args "--grammar")]
                   [g]
                   (sort (for [d (.listFiles (io/file "antlr"))
                               :when (.exists (io/file d "manifest.edn"))]
                           (.getName d))))]
    (doseq [g grammars]
      (binding [*grammar* g]
        (if (= "discover" (first args)) (discover!) (generate! only-target)))))
  (shutdown-agents))
