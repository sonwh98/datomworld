(ns yang-antlr-gen
  "Build-time generation of the grammars-v4 Python3 parser for the JVM host
   (docs/design/yang.antlr.md §4.2). Reproducible from clean:

     clj -M:antlr-gen            ; or: bb gen:python-antlr

   1. Read the vendored grammars (antlr/python3/*.g4, MIT, committed) and
      fetch the remaining pinned files at the manifest's revision (cached
      under build/, re-fetched only when absent); refuse any digest mismatch.
   2. Apply the recorded patch: the upstream Java base helpers carry no
      package clause; the generated recognizers are put in
      `yang.python.antlr.gen`, so the helpers get that one line prepended.
   3. Run the pinned ANTLR tool (-Dlanguage=Java, no listener, no visitor).
   4. Compile with the host javac into build/antlr/python3/classes, which
      deps.edn puts on the classpath.
   5. Digest every generated .java file and compare with the manifest's
      recorded generated digests; a mismatch fails the build.

   `clj -M:antlr-gen discover` prints the head commit of the grammar path, for
   deliberately moving the pin. Nothing here runs at admission time."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str])
  (:import
    (java.net
      URI)
    (java.net.http
      HttpClient
      HttpClient$Redirect
      HttpRequest
      HttpResponse$BodyHandlers)
    (java.security
      MessageDigest)
    (javax.tools
      ToolProvider)))


(def manifest-path "antlr/python3/manifest.edn")

(def build-dir "build/antlr/python3")


(defn- sha256
  [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))


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
  (edn/read-string (slurp manifest-path)))


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
  (let [src-dir (io/file build-dir "upstream")]
    (.mkdirs src-dir)
    (let [mismatches
          (vec (for [[file {expected :sha256, :as entry}]
                     (sort-by key (:files grammar))
                     :let [target (io/file src-dir (str/replace file "/" "__"))
                           bs (source-bytes! grammar file entry target)
                           actual (sha256 bs)
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


(defn- stage-sources!
  "Copy grammars and helpers into one generation directory, applying the
   recorded patches: the package clause for the Java helpers, then the
   manifest's exact-text replacements."
  [{:keys [grammar package patches]} src-dir]
  (let [gen-in (io/file build-dir "grammar")]
    (delete-tree! gen-in)
    (.mkdirs gen-in)
    (doseq [file (sort (keys (:files grammar)))]
      (let [text (slurp (io/file src-dir (str/replace file "/" "__")))
            base (last (str/split file #"/"))
            text (if (str/ends-with? base ".java")
                   (str "package " package ";\n" text)
                   text)
            text (reduce (partial apply-patch file)
                         text
                         (get patches file))]
        (spit (io/file gen-in base) text)))
    gen-in))


(defn- run-antlr!
  [{:keys [package grammar]} gen-in]
  (let [java-out (io/file build-dir "java")
        pkg-dir (io/file java-out (str/replace package "." "/"))]
    (delete-tree! java-out)
    (.mkdirs pkg-dir)
    (let [tool (org.antlr.v4.Tool.
                 (into-array String
                             (concat ["-Dlanguage=Java" "-package" package
                                      "-no-listener" "-no-visitor"
                                      "-Xexact-output-dir" "-o"
                                      (.getPath pkg-dir) "-lib"
                                      (.getPath gen-in)]
                                     (map #(.getPath (io/file gen-in %))
                                          (:generate grammar)))))]
      (.processGrammarsOnCommandLine tool)
      (when (pos? (.getNumErrors tool))
        (throw (ex-info "ANTLR generation failed"
                        {:errors (.getNumErrors tool)}))))
    (doseq [f (.listFiles gen-in)
            :when (str/ends-with? (.getName f) ".java")]
      (io/copy f (io/file pkg-dir (.getName f))))
    pkg-dir))


(defn- generated-digests
  [pkg-dir]
  (into (sorted-map)
        (for [f (.listFiles (io/file pkg-dir))
              :when (str/ends-with? (.getName f) ".java")]
          [(.getName f)
           (sha256 (java.nio.file.Files/readAllBytes (.toPath f)))])))


(defn- non-ascii-files
  "§4.5: generated textual sources stay ASCII."
  [pkg-dir]
  (vec (for [f (.listFiles (io/file pkg-dir))
             :when (some #(> (int %) 127) (slurp f))]
         (.getName f))))


(defn- compile-java!
  [pkg-dir]
  (let [classes (io/file build-dir "classes")
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


(defn generate!
  []
  (let [m (read-manifest)
        src-dir (fetch-grammar! m)
        gen-in (stage-sources! m src-dir)
        pkg-dir (run-antlr! m gen-in)
        digests (generated-digests pkg-dir)
        non-ascii (non-ascii-files pkg-dir)]
    (spit (io/file build-dir "generated-digests.edn")
          (with-out-str (pprint/pprint digests)))
    (when (seq non-ascii)
      (throw (ex-info "Non-ASCII generated source" {:files non-ascii})))
    (when-let [expected (:generated m)]
      (when-not (= expected digests)
        (throw (ex-info "Generated sources differ from the recorded digests"
                        {:expected expected, :actual digests}))))
    (compile-java! pkg-dir)
    (println "generated" (count digests) "java files; classes in"
             (str build-dir "/classes"))
    (when-not (:generated m)
      (println "no :generated digests recorded yet; record these:")
      (pprint/pprint digests))))


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


(defn -main
  [& args]
  (case (first args)
    "discover" (discover!)
    (generate!))
  (shutdown-agents))
