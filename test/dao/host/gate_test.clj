(ns dao.host.gate-test
  "The H0 freeze gate of docs/design/dao.stream.host-boundary.md (§8 H0,
   §8.1, §3.2, §3.3), over the adopted closure committed by
   src/dev/host_boundary/closure.clj under docs/design/host-boundary/.

   The gate reads every closure file with tools.reader (:read-cond
   :preserve, so every lane's arms are seen; comments, strings and
   docstrings never count) and fails when
   - a file outside list 1 holds a non-exempt host arm (§3.3 exempts:
     ns-form arms, catch-type arms, arms equal but for hints, protocol
     blocks directly inside deftype/defrecord/reify/extend bodies,
     print-method defmethods, exists? tests, (Object.)/(js-obj)
     sentinels); a lane-specific file (.clj .cljs .cljd) is a host arm as
     a whole;
   - a file holds more resolved effect calls of a kind than list 2
     permits (the §3.2 kinds, and calls of the §2.4 M entries).
   Effect calls are resolved lexically: a symbol bound by let, fn, loop,
   for, doseq, catch, letfn or a deftype method, defined in its file, or
   excluded by :refer-clojure, is not the core function; quoted forms and
   (comment ...) are data. Reading zero files, or failing to read one, is
   a scan failure, distinct from a pass.

   JVM lane only: the gate reads source text from disk; what it checks is
   the same text on every lane.

   Also here: the independent second traversal of the closure (a
   reader-form walker that selects reader-conditional arms itself and
   parses libspecs itself, sharing no code with tools.namespace), which
   must agree with the committed closure files."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as rt])
  (:import
    (clojure.lang
      ReaderConditional)))


;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn read-forms
  "Every top-level form of text, reader conditionals preserved."
  [text]
  (binding [reader/*read-eval* false
            ;; ::alias/kw needs no real namespace: keywords never count
            reader/*alias-map* (fn [sym] sym)
            reader/*default-data-reader-fn* (fn [tag v] (tagged-literal tag v))]
    (let [rdr (rt/indexing-push-back-reader text 1)]
      (loop [acc []]
        (let [form (reader/read {:read-cond :preserve :eof ::eof} rdr)]
          (if (= ::eof form) acc (recur (conj acc form))))))))


(defn rc?
  [x]
  (instance? ReaderConditional x))


(defn rc-arms
  "[[feature form] ...] of a reader conditional."
  [^ReaderConditional rc]
  (map vec (partition 2 (.-form rc))))


(defn splicing?
  [^ReaderConditional rc]
  (boolean (.-splicing rc)))


(defn head
  [x]
  (when (and (seq? x) (symbol? (first x))) (first x)))


(defn line-of
  [x]
  (:line (meta x)))


;; ---------------------------------------------------------------------------
;; The independent closure traversal (traversal B)
;; ---------------------------------------------------------------------------

(def closure-root 'yin.repl.main)
(def lanes [:clj :cljs :cljd])
(def lane-features {:clj #{:clj}, :cljs #{:cljs}, :cljd #{:cljd :clj}})


(defn select-arms
  "form with every reader conditional replaced by its lane arm: the first
   arm whose feature is in features or is :default; a splicing arm's
   elements spliced into the enclosing collection; no arm, nothing."
  [features form]
  (let [pick (fn [rc]
               (some (fn [[k v]] (when (or (features k) (= :default k)) [v]))
                     (rc-arms rc)))
        expand (fn [xs]
                 (mapcat (fn [x]
                           (if (rc? x)
                             (when-let [[v] (pick x)]
                               (if (splicing? x)
                                 (map #(select-arms features %) v)
                                 [(select-arms features v)]))
                             [(select-arms features x)]))
                         xs))]
    (cond
      (rc? form) (some->> (pick form) first (select-arms features))
      (seq? form) (apply list (expand form))
      (vector? form) (vec (expand form))
      (map? form) (into {} (map vec (partition 2 (expand (mapcat identity form)))))
      :else form)))


(defn libspec-deps
  [spec]
  (cond
    (symbol? spec) [spec]
    (or (string? spec) (keyword? spec) (nil? spec)) []
    (sequential? spec)
    (let [[a b] spec
          opts (rest spec)]
      (cond
        (string? a) []
        (and (symbol? a) (or (nil? b) (keyword? b)))
        (if (and (= :as-alias b) (not-any? #{:as :refer :refer-macros :include-macros} opts))
          []
          [a])
        (symbol? a)
        (mapcat (fn [s]
                  (map #(symbol (str a "." %)) (libspec-deps s)))
                (remove nil? (rest spec)))
        :else []))
    :else []))


(defn ns-deps
  "The namespaces an ns form requires on a lane: :require, :use and
   :require-macros clauses."
  [features ns-form]
  (let [f (select-arms features ns-form)]
    (->> (rest f)
         (filter seq?)
         (filter #(#{:require :use :require-macros} (first %)))
         (mapcat rest)
         (mapcat libspec-deps)
         set)))


(defn lane-file
  [lane ns-sym]
  (let [p (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (some #(when (.isFile (io/file %)) %)
          [(str "src/" (name lane) "/" p "." (name lane))
           (str "src/cljc/" p ".cljc")])))


(defn traverse
  "The closure files of lane from closure-root, by traversal B."
  [lane]
  (loop [todo [closure-root] seen #{} files #{}]
    (if-let [n (first todo)]
      (if (seen n)
        (recur (rest todo) seen files)
        (if-let [f (lane-file lane n)]
          (let [ns-form (first (filter #(= 'ns (head %)) (read-forms (slurp f))))]
            (recur (concat (rest todo) (sort (ns-deps (lane-features lane) ns-form)))
                   (conj seen n)
                   (conj files f)))
          (recur (rest todo) (conj seen n) files)))
      files)))


;; ---------------------------------------------------------------------------
;; The committed closure (traversal A, src/dev/host_boundary/closure.clj)
;; ---------------------------------------------------------------------------

(defn committed-closure
  [lane]
  (edn/read-string (slurp (str "docs/design/host-boundary/closure-" (name lane) ".edn"))))


(defn closure-files
  "The union of the three lanes' committed closure files."
  []
  (into (sorted-set) (mapcat #(map :file (:files (committed-closure %))) lanes)))


;; ---------------------------------------------------------------------------
;; Host arms (§3.3 exemptions)
;; ---------------------------------------------------------------------------

(def deftype-heads
  '#{deftype defrecord reify extend-type extend-protocol specify specify!})


(def sentinels
  '#{(Object.) (js-obj) (js/Object.) (java.lang.Object.)})


(defn exemption
  "The §3.3 pattern that exempts rc, given its ancestors (outermost first),
   or nil."
  [rc ancestors]
  (let [parent (peek ancestors)
        arms (map second (rc-arms rc))
        catch-form? #(and (seq? %) (= 'catch (first %)))]
    (cond
      (some #(= 'ns (head %)) ancestors) :ns-require-arm
      (and (catch-form? parent) (identical? rc (second parent))) :catch-type
      (and (< 1 (count arms)) (apply = arms)) :hint-only
      (and (splicing? rc) (deftype-heads (head parent))) :protocol-block
      (some #(and (= 'defmethod (head %)) (= 'print-method (second %))) ancestors) :print-method
      (every? #(and (seq? %) (= 'defmethod (first %)) (= 'print-method (second %))) arms)
      :print-method
      (every? #(= 'exists? (head %)) arms) :exists
      (every? sentinels arms) :sentinel
      :else nil)))


(defn non-exempt-arms
  "[line ...] of the non-exempt reader conditionals in forms."
  [forms]
  (let [acc (atom [])
        walk (fn walk
               [form ancestors]
               (let [down (conj ancestors form)]
                 (cond
                   (rc? form)
                   (do (when-not (exemption form ancestors)
                         (swap! acc conj (or (some (comp line-of second) (rc-arms form))
                                             (some line-of (rseq ancestors)))))
                       (doseq [[_ arm] (rc-arms form)]
                         (if (splicing? form)
                           (doseq [x arm] (walk x down))
                           (walk arm down))))
                   (and (seq? form) (= 'comment (first form))) nil
                   (map? form) (doseq [[k v] form] (walk k down) (walk v down))
                   (coll? form) (doseq [x form] (walk x down))
                   :else nil)))]
    (doseq [f forms] (walk f []))
    @acc))


;; ---------------------------------------------------------------------------
;; Resolved effect calls (§3.2 kinds, §2.4 M entries)
;; ---------------------------------------------------------------------------

(def core-ns #{nil "clojure.core" "cljs.core" "cljd.core"})


(defn effect-kind
  "The §3.2 kind of a symbol that names a host effect, or nil. core? is
   false when an unqualified name is shadowed in its file."
  [sym core?]
  (let [n (name sym)
        q (namespace sym)
        core (and (contains? core-ns q) (or q core?))]
    (cond
      (and core (= n "random-uuid")) :random-uuid
      (and core (#{"rand" "rand-int" "rand-nth" "shuffle" "random-sample"} n)) :rand
      (or (str/includes? n "SecureRandom")
          (= n "Random.secure")
          (= n "randomUUID")
          (and (= q "js") (str/starts-with? n "crypto"))
          (#{"getRandomValues" "randomBytes" "generateKey"} n)) :secure-random
      (and core (= n "gensym")) :gensym
      (or (and (#{"System" "java.lang.System"} q) (#{"currentTimeMillis" "nanoTime"} n))
          (and (= q "js") (or (str/starts-with? n "Date") (str/starts-with? n "performance")))
          (str/starts-with? n "DateTime.now")
          (and q (str/ends-with? q "Instant") (= n "now"))) :clock
      (or (and (#{"System" "java.lang.System"} q) (#{"getenv" "getProperty" "getProperties"} n))
          (and (= q "js") (str/starts-with? n "process"))
          (str/starts-with? n "Platform.")) :ambient
      (or (and core (#{"println" "print" "prn" "pr" "printf" "newline"} n))
          (and (= q "js") (str/starts-with? n "console"))) :print
      (or (and (= q "js") (#{"setTimeout" "setInterval" "setImmediate" "queueMicrotask"} n))
          (str/starts-with? n "Timer")
          (and (#{"Thread" "java.lang.Thread"} q) (= n "sleep"))
          (str/starts-with? n "Future.delayed")) :timer
      :else nil)))


(def m-entry-nss
  "The namespaces holding §2.4's M entries, now and after H1."
  '#{dao.data.arrays dao.host.bytes})


(def m-entry-names #{"aset" "acopy" "asort" "set!" "copy!"})


(def scan-features
  "The feature sets the effect scan resolves a file under, by its lane:
   one set per lane, and for :cljd both the traversal's {:cljd :clj}
   (the host-eval pass, where :clj may be picked first) and :cljd alone
   (so a :cljd arm behind a :clj arm is seen too)."
  {:clj [#{:clj}], :cljs [#{:cljs}], :cljd [#{:cljd :clj} #{:cljd}]})


(defn file-lanes
  "The lanes a file is read on: its own for a lane-specific file, every
   lane for .cljc."
  [file]
  (condp #(str/ends-with? %2 %1) file
    ".clj" [:clj]
    ".cljs" [:cljs]
    ".cljd" [:cljd]
    lanes))


(defn file-scope
  "{:aliases {alias ns} :refers {sym ns} :shadowed #{sym}} of a file's
   forms as one lane reads them (arms already selected): its ns form,
   its top-level defs, its :refer-clojure excludes."
  [forms]
  (let [ns-form (first (filter #(= 'ns (head %)) forms))
        specs (->> (rest ns-form)
                   (filter seq?)
                   (filter #(#{:require :use} (first %)))
                   (mapcat rest)
                   (filter vector?))
        opts (fn [spec] (apply hash-map (rest (if (even? (count spec)) (butlast spec) spec))))
        excluded (->> (rest ns-form)
                      (filter #(and (seq? %) (= :refer-clojure (first %))))
                      (mapcat #(:exclude (apply hash-map (rest %)))))
        defs (->> forms
                  (mapcat (fn [f] (if (= 'do (head f)) (rest f) [f])))
                  (keep (fn [f]
                          (when (#{'def 'defn 'defn- 'defmacro 'defonce 'declare
                                   'defprotocol 'defmulti} (head f))
                            (second f)))))]
    {:aliases (into {} (keep (fn [s] (when-let [a (:as (opts s))] [a (first s)])) specs))
     :refers (into {} (mapcat (fn [s]
                                (let [r (:refer (opts s))]
                                  (when (sequential? r) (map (fn [x] [x (first s)]) r))))
                              specs))
     :shadowed (set (filter symbol? (concat excluded defs)))}))


(defn binding-syms
  "Every symbol in a binding form (destructuring included)."
  [b]
  (cond
    (symbol? b) (if (= '& b) [] [b])
    (map? b) (let [b (dissoc b :or)] (mapcat binding-syms (concat (keys b) (vals b))))
    (coll? b) (mapcat binding-syms b)
    :else []))


(defn binding-exprs
  "The :or default expressions in a binding form, which are evaluated."
  [b]
  (cond
    (map? b) (concat (vals (:or b)) (mapcat binding-exprs (keys (dissoc b :or))))
    (vector? b) (mapcat binding-exprs b)
    :else []))


(def let-heads
  '#{let let* loop loop* when-let if-let when-some if-some when-first
     with-open dotimes for doseq})


(def fn-heads '#{fn fn* defn defn- defmacro defmethod})


(defn- arity?
  [x]
  (and (seq? x) (vector? (first x))))


(defn lane-effect-calls
  "[[kind line column] ...] for the resolved effect calls in forms as a
   reader with features reads them: arms selected, then names resolved
   against that lane's ns form, defs and locals only."
  [features forms]
  (let [forms (keep #(select-arms features %) forms)
        {:keys [aliases refers shadowed]} (file-scope forms)
        acc (atom [])
        hit! (fn [kind sym] (swap! acc conj [kind (line-of sym) (:column (meta sym))]))
        resolve-m (fn [sym]
                    (if-let [q (namespace sym)]
                      (get aliases (symbol q) (symbol q))
                      (get refers sym)))]
    (letfn [(walk
              [form bound]
              (cond
                (symbol? form)
                (when-not (and (nil? (namespace form)) (bound form))
                  (if-let [k (effect-kind form (not (shadowed form)))]
                    (hit! k form)
                    (when (and (m-entry-nss (resolve-m form)) (m-entry-names (name form)))
                      (hit! :m-entry form))))

                (seq? form)
                (let [h (head form)]
                  (cond
                    (#{'quote 'comment 'ns} h) nil
                    (let-heads h) (walk-let (second form) (nnext form) bound)
                    (= 'letfn h) (let [fns (second form)
                                       b (into bound (map first fns))]
                                   (doseq [f fns] (walk-arities (rest f) b))
                                   (walk-all (nnext form) b))
                    (fn-heads h) (walk-fn h (rest form) bound)
                    (= 'catch h) (walk-all (drop 3 form) (conj bound (nth form 2 nil)))
                    (deftype-heads h) (let [fields (filter vector? (take 3 form))
                                            b (into bound (mapcat binding-syms fields))]
                                        ;; specify's target is an expression
                                        ;; evaluated in the enclosing scope
                                        (when (#{'specify 'specify!} h) (walk (second form) bound))
                                        (doseq [x (drop 2 form)]
                                          (if (and (seq? x) (symbol? (first x))
                                                   (or (vector? (second x)) (arity? (second x))))
                                            (walk-arities (rest x) b)
                                            (walk x b))))
                    :else (walk-all form bound)))

                (map? form) (doseq [[k v] form] (walk k bound) (walk v bound))
                (coll? form) (walk-all form bound)
                :else nil))
            (walk-all [xs bound] (doseq [x xs] (walk x bound)))
            (walk-let
              [bvec body bound]
              (let [b (reduce (fn [b [lhs rhs]]
                                (case lhs
                                  :let (do (walk-let rhs nil b) (into b (binding-syms (take-nth 2 rhs))))
                                  (:when :while) (do (walk rhs b) b)
                                  (do (walk rhs b)
                                      (walk-all (binding-exprs lhs) b)
                                      (into b (binding-syms lhs)))))
                              bound
                              (partition 2 (if (vector? bvec) bvec [])))]
                (walk-all body b)))
            (walk-fn
              [h tail bound]
              ;; tail: name? [defmethod: dispatch] doc? attrs? then one
              ;; arity ([params] body...) or several (([params] body...) ...)
              (let [nm (when (symbol? (first tail)) (first tail))
                    tail (if nm (rest tail) tail)
                    tail (if (= 'defmethod h) (do (walk (first tail) bound) (rest tail)) tail)]
                (walk-arities (drop-while #(or (string? %) (map? %)) tail)
                              (if nm (conj bound nm) bound))))
            (walk-arities
              [tail bound]
              (if (vector? (first tail))
                (walk-arity tail bound)
                (doseq [x tail]
                  (if (arity? x) (walk-arity x bound) (walk x bound)))))
            (walk-arity
              ;; each arity's parameters bind in its own body only
              [[params & body] bound]
              (let [b (into bound (binding-syms params))]
                (walk-all (binding-exprs params) b)
                (walk-all body b)))]
      (doseq [f forms] (walk f #{})))
    @acc))


(defn effect-calls
  "[[kind line] ...] for the resolved effect calls in forms, read on
   lanes (default all): each lane resolves its own arms, ns form, defs and
   locals, and a source site any lane resolves to an effect counts once."
  ([forms] (effect-calls lanes forms))
  ([ls forms]
   (->> (for [l ls, fs (scan-features l)] fs)
        (distinct)
        (mapcat #(lane-effect-calls % forms))
        (distinct)
        (mapv (fn [[k line _]] [k line])))))


;; ---------------------------------------------------------------------------
;; List 1 and list 2
;; ---------------------------------------------------------------------------

(defn lane-specific?
  [file]
  (not (str/ends-with? file ".cljc")))


(def list-1-categories
  "The §8.1 rows (r9): what the row means, the slices that may clear its
   files from list 1 (\"H2\" \"H3\" \"H4\": removed by that slice; :stays:
   retained by design; :pending: H2 migrates the file but a JVM arm
   survives until the owner decides OQ11, §13), and the §3.1 closure
   classes a file in the row may carry (:classes, §8.1's category-to-class
   table). A file must carry at least one of them on every lane whose
   closure holds it."
  {:migration-source {:row "H1-H4 migration sources (incl. H0's profile consumers)"
                      :clears #{"H2" "H3" "H4" :stays :pending}
                      :classes #{:profile-internal-pure :profile-hash-sign :consumer :profile-consumer}}
   :profile {:row "Profile: the arms belong here by design"
             :clears #{:stays}
             :classes #{:profile-internal-pure}}
   :runtime-prereq {:row "Retained runtime prerequisites (table C) and display"
                    :clears #{:stays}
                    :classes #{:runtime-prereq :internal-pure-display :deferred-storage}}
   :codec-track {:row "Codec-track files, untouched by this track"
                 :clears #{:stays}
                 :classes #{:codec-track}}
   :dialect-arm {:row "Dialect arms (H0 finding, retained): diagnostic type names, the dialect's EDN reader"
                 :clears #{:stays}
                 :classes #{:dialect-arm}}
   :seam-driver {:row "Seams, stream implementations and driver"
                 :clears #{:stays}
                 :classes #{:external-stream-seam :driver}}
   :deferred-scope {:row "Deferred-scope files (§9)"
                    :clears #{:stays}
                    :classes #{:deferred-storage :deferred-entropy :deferred-timers
                               :deferred-stage :deferred-gensym}}})


(def list-1-exceptions
  "The §8.1 bounded exceptions (r10): the eight files whose §3.1 closure
   class the category-to-class table does not admit for the §8.1 row that
   places them. {file {:category c :class k :reason r}}. An exception
   admits k for that file only while its list-1 category is c; it widens
   no other category, file or class. An exception the bare table makes
   unnecessary, or whose file is not in category c, fails (the table
   cannot go stale silently). A new row here is a §8.1 amendment."
  {"src/cljc/dao/space/transactor.cljc"
   {:category :deferred-scope :class :runtime-prereq
    :reason "§8.1 deferred row (locking); §3.1 table C"}
   "src/cljc/yin/vm/ucf/authority.cljc"
   {:category :deferred-scope :class :runtime-prereq
    :reason "§8.1 deferred row (locking); §3.1 table C"}
   "src/clj/dao/jing/content/driver.clj"
   {:category :seam-driver :class :deferred-timers
    :reason "§8.1 seams row takes every lane file; §3.1 deferred §9.1"}
   "src/clj/yang/antlr/cst.clj"
   {:category :seam-driver :class :deferred-stage
    :reason "§8.1 seams row takes every lane file; §3.1 deferred §9.3"}
   "src/cljs/yang/antlr/cst.cljs"
   {:category :seam-driver :class :deferred-stage
    :reason "§8.1 seams row takes every lane file; §3.1 deferred §9.3"}
   "src/cljd/yang/antlr/cst.cljd"
   {:category :seam-driver :class :deferred-stage
    :reason "§8.1 seams row takes every lane file; §3.1 deferred §9.3"}
   "src/cljd/dao/stream/cbor/cljd.cljd"
   {:category :seam-driver :class :codec-track
    :reason "§8.1 seams row takes every lane file; §3.1 codec track (§8.3)"}
   "src/cljd/dao/stream/transit/cljd.cljd"
   {:category :seam-driver :class :codec-track
    :reason "§8.1 seams row takes every lane file; §3.1 codec track (§8.3)"}})


(defn list-1-problems
  "Every way list-1 disagrees with list-1-categories (plus the §8.1
   bounded exceptions, default list-1-exceptions, each admitting its one
   class only for its file in its category) and the per-lane closure
   classes {lane {file classes}}: [kind file detail]. Lanes are checked
   one at a time; a lane's tags are never merged with another's. An
   exception whose file is not in list-1 under its category is
   :exception-category, so the table is enforced, not merely consulted."
  ([list-1 lane-classes] (list-1-problems list-1 lane-classes list-1-exceptions))
  ([list-1 lane-classes exceptions]
   (vec
     (concat
       (for [[f {:keys [category clears]}] list-1
             problem (cond
                       (not (contains? list-1-categories category))
                       [[:unknown-category f category]]
                       (not (contains? (get-in list-1-categories [category :clears]) clears))
                       [[:slice-not-allowed f [category clears]]]
                       :else [])]
         problem)
       (for [[f {:keys [category]}] list-1
             :when (contains? list-1-categories category)
             :let [ex (exceptions f)
                   allowed (cond-> (:classes (list-1-categories category))
                             (and ex (= (:category ex) category)) (conj (:class ex)))
                   on-lanes (keep (fn [[l m]] (when (contains? m f) [l (get m f)])) lane-classes)]
             problem (if (empty? on-lanes)
                       [[:in-no-lane f nil]]
                       (for [[l cs] (sort-by first on-lanes)
                             :let [kind (cond (empty? cs) :empty-classes
                                              (= #{:none} (set cs)) :class-none
                                              (not-any? allowed cs) :class-not-in-category
                                              :else nil)]
                             :when kind]
                         [kind f [l category (set cs)]]))]
         problem)
       (for [[f {:keys [category]}] exceptions
             :when (not= category (get-in list-1 [f :category]))]
         [:exception-category f [category (get-in list-1 [f :category])]])))))


(defn committed-lane-classes
  []
  (into {} (map (fn [l] [l (into {} (map (juxt :file :classes) (:files (committed-closure l))))])
                lanes)))


(def profile-additions
  "§8.1's planned additions that have landed: the dao.host profile files,
   permitted host arms by design (list 1, :profile). Nothing in the
   closure requires them until H2 migrates their consumers, so the gate
   scans them beside the closure files and gives them the closure class
   §8.1 maps :profile to, on every lane that reads them. Once a lane's
   committed closure holds one, its entry here is stale and
   `profile-additions-are-scanned-until-the-closure-reaches-them` fails:
   delete it, and the closure's own class governs. H3 adds hash and sign."
  ;; H2: the closure reaches all five H1 files, so their entries are
  ;; retired and closure.clj's class-rows classes them
  {})


(def profile-root
  "Where the profile files live; every source file under it is scanned."
  "src/cljc/dao/host")


(defn lane-classes
  "committed-lane-classes plus each profile addition on every lane that
   reads it and whose closure does not hold it."
  []
  (reduce (fn [lc [f {:keys [classes]}]]
            (reduce (fn [lc l]
                      (if (contains? (get lc l) f) lc (assoc-in lc [l f] classes)))
                    lc
                    (file-lanes f)))
          (committed-lane-classes)
          profile-additions))


(defn gate-files
  "The files the gate reads: the closure and the profile additions."
  []
  (into (closure-files) (keys profile-additions)))


(defn- entry
  ([category clears] {:category category :clears clears})
  ([category clears reason] {:category category :clears clears :reason reason}))


(def list-1
  "The closure files permitted non-exempt host arms (§8.1 r7), each with
   its row, the slice that clears it, and for H0's 16 findings the site
   and reason. Generated from the H0 tree by the gate; r7 categorised the
   16 files H0 first found unlisted (no file is uncategorised)."
  {;; H1-H4 migration sources
   "src/cljc/dao/jing/cbor.cljc" (entry :migration-source :stays "H4 migrated writer to dao.host.*; retained: number-model/runtime compatibility arms (§8.1 r11 row; :91 Dart runtimeType in refusal text, :217 JS unsafe-integral Number, :227 float64? (no dao.host.number predicate), :1026 Dart with-meta nil")
   "src/cljc/dao/jing.cljc" (entry :migration-source "H3" "hashes")
   "src/cljc/yin/vm/linker/sign.cljc" (entry :migration-source :stays "H3 migrates; generate stays (deferred entropy)")
   "src/cljc/yin/vm/debruijn.cljc" (entry :migration-source :stays "retained host number-model arms (Architect H2 review, §8.1 r11 row)")
   "src/cljc/yin/vm/debruijn_code.cljc" (entry :migration-source :stays "retained host number-model arms (Architect H2 review, §8.1 r11 row); :213 int64 hex ported in H2b")
   "src/cljc/yin/vm/data.cljc" (entry :migration-source :pending "H2b: :388 float-value over the profile")
   "src/cljc/yin/vm/values.cljc" (entry :migration-source :pending "H2b: category move to :runtime-prereq (table C print laws at :116,122)")
   "src/cljc/dao/stream/base64.cljc" (entry :migration-source "H3" "base64 made portable")
   ;; Profile
   "src/cljc/dao/data/arrays.cljc" (entry :profile :stays)
   "src/cljc/dao/host/integer.cljc" (entry :profile :stays "H1 addition (§8.1)")
   "src/cljc/dao/host/float.cljc" (entry :profile :stays "H1 addition (§8.1)")
   "src/cljc/dao/host/number.cljc" (entry :profile :stays "H1 addition (§8.1)")
   "src/cljc/dao/host/bytes.cljc" (entry :profile :stays "H1 addition (§8.1)")
   "src/cljc/dao/host/text.cljc" (entry :profile :stays "H1 addition (§8.1)")
   "src/cljc/dao/host/hash.cljc" (entry :profile :stays "H3 addition (§8.1)")
   "src/cljc/dao/host/sign.cljc" (entry :profile :stays "H3 addition (§8.1)")
   ;; Retained runtime prerequisites (table C) and display
   "src/cljc/dao/data/btree.cljc" (entry :runtime-prereq :stays "atomic flag, weak/soft references")
   "src/cljc/dao/data/btree/storage.cljc" (entry :runtime-prereq :stays "promise facade, deferred §9.1")
   "src/cljc/dao/pretty.cljc" (entry :runtime-prereq :stays "display only")
   ;; Codec-track files
   "src/cljc/dao/stream/ws.cljc" (entry :codec-track :stays)
   "src/cljc/dao/stream/transit.cljc" (entry :codec-track :stays)
   "src/cljc/dao/stream/cbor.cljc" (entry :codec-track :stays)
   "src/cljc/dao/stream/cbor/boring.cljc" (entry :codec-track :stays)
   "src/cljc/dao/stream/codec.cljc" (entry :codec-track :stays ":45 after C4")
   "src/cljc/yin/repl/connect.cljc" (entry :codec-track :stays)
   ;; H0 finding, retained dialect arms (r7 §8.1 "dialect arms" row)
   "src/cljc/dao/space/query.cljc" (entry :dialect-arm :stays ":808 runtime type name in diagnostic text")
   "src/cljc/yang/clojure.cljc" (entry :dialect-arm :stays ":375 runtime type name in diagnostic text")
   "src/cljc/yin/vm/engine.cljc" (entry :dialect-arm :stays ":929 runtime type name in diagnostic text")
   "src/cljc/yin/repl/adapter.cljc" (entry :dialect-arm :stays ":58 the dialect's EDN reader")
   "src/cljc/yin/repl/dht.cljc" (entry :dialect-arm :stays ":104 the dialect's EDN reader")
   ;; Seams and driver: every lane-specific file, the selector, the driver
   "src/clj/dao/jing/content/driver.clj" (entry :seam-driver :stays)
   "src/clj/dao/stream/datagram/jvm.clj" (entry :seam-driver :stays)
   "src/clj/dao/stream/waitset/driver.clj" (entry :seam-driver :stays)
   "src/clj/dao/stream/ws/jvm.clj" (entry :seam-driver :stays)
   "src/clj/yang/antlr/cst.clj" (entry :seam-driver :stays)
   "src/clj/yin/repl/host/jvm.clj" (entry :seam-driver :stays)
   "src/cljs/dao/stream/datagram/node.cljs" (entry :seam-driver :stays)
   "src/cljs/dao/stream/waitset/driver.cljs" (entry :seam-driver :stays)
   "src/cljs/dao/stream/ws/node.cljs" (entry :seam-driver :stays)
   "src/cljs/yang/antlr/cst.cljs" (entry :seam-driver :stays)
   "src/cljs/yin/repl/host.cljs" (entry :seam-driver :stays)
   "src/cljd/dao/stream/cbor/cljd.cljd" (entry :seam-driver :stays)
   "src/cljd/dao/stream/transit/cljd.cljd" (entry :seam-driver :stays)
   "src/cljd/dao/stream/datagram/dart.cljd" (entry :seam-driver :stays)
   "src/cljd/dao/stream/waitset/driver.cljd" (entry :seam-driver :stays)
   "src/cljd/dao/stream/ws/dart.cljd" (entry :seam-driver :stays)
   "src/cljd/yang/antlr/cst.cljd" (entry :seam-driver :stays)
   "src/cljd/yin/repl/host.cljd" (entry :seam-driver :stays)
   "src/cljc/dao/stream/ringbuffer.cljc" (entry :seam-driver :stays ":168 the in-memory stream implementation's own host atom test (§4.1, implementation-private)")
   "src/cljc/yin/repl/main.cljc" (entry :seam-driver :stays)
   "src/cljc/yin/repl/host.cljc" (entry :seam-driver :stays)
   "src/cljc/yin/repl/state.cljc" (entry :seam-driver :stays)
   ;; Deferred-scope files (§9)
   "src/cljc/dao/jing/file.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/space/store/fs.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/space/store.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/stream/journal/file.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/jing/coordinate.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/space/dht.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/stream/rpc.cljc" (entry :deferred-scope :stays ":114 rand, deferred entropy (§9.1); H0 finding")
   "src/cljc/yin/repl.cljc" (entry :deferred-scope :stays)
   "src/cljc/yang/python/antlr/parser.cljc" (entry :deferred-scope :stays)
   "src/cljc/yang/python/antlr/lower.cljc" (entry :deferred-scope :stays)
   "src/cljc/yang/python/antlr/prelude.cljc" (entry :deferred-scope :stays)
   "src/cljc/yang/python/antlr/render.cljc" (entry :deferred-scope :stays)
   "src/cljc/yang/python/antlr/repl_probe.cljc" (entry :deferred-scope :stays)
   "src/cljc/yin/repl/frontends.cljc" (entry :deferred-scope :stays)
   "src/cljc/yin/repl/store.cljc" (entry :deferred-scope :stays)
   "src/cljc/dao/stream/journal.cljc" (entry :deferred-scope :stays "locking")
   "src/cljc/dao/space/transactor.cljc" (entry :deferred-scope :stays)
   "src/cljc/yin/vm/ucf/authority.cljc" (entry :deferred-scope :stays "locking")})


(def list-2
  "The resolved effect calls permitted, {file {kind count}}: the §3.2
   sites inside the adopted closure and the callers of §2.4's M entries.
   A count is a ceiling: one more call of that kind in that file fails."
  {;; §3.2 random-uuid: 21 inside the closure (dao.await and
   ;; yin.vm.ffi.remote-serve are outside it)
   "src/cljc/dao/stream/ringbuffer.cljc" {:random-uuid 2}
   "src/cljc/dao/stream/memory_log.cljc" {:random-uuid 1}
   "src/cljc/dao/stream/ws.cljc" {:random-uuid 1}
   "src/cljc/dao/stream/remote.cljc" {:random-uuid 1}
   "src/cljc/dao/stream/remote_pair.cljc" {:random-uuid 1}
   "src/cljc/dao/stream/remote_route.cljc" {:random-uuid 1}
   "src/cljc/dao/stream/journal.cljc" {:random-uuid 1}
   "src/cljc/dao/jing/content/step.cljc" {:random-uuid 1}
   "src/cljc/dao/lease.cljc" {:random-uuid 1}
   "src/cljc/dao/space/store/fs.cljc" {:random-uuid 2, :ambient 4}
   "src/cljc/yin/vm/linker.cljc" {:random-uuid 1}
   "src/cljc/yin/vm/ucf/custody.cljc" {:random-uuid 1}
   "src/cljc/yin/vm/ucf/holder/driver.cljc" {:random-uuid 1}
   "src/cljc/yin/vm/telemetry.cljc" {:random-uuid 1}
   "src/cljc/yin/repl.cljc" {:random-uuid 4, :ambient 3}
   "src/cljc/yin/repl/dht.cljc" {:random-uuid 1}
   ;; §3.2 rand, secure generators (sign :79 is jvm-pair's deterministic
   ;; seed-replay SecureRandom proxy, §2.5), gensym
   "src/cljc/dao/stream/rpc.cljc" {:rand 3}
   "src/cljc/dao/jing/dht.cljc" {:rand 1}
   "src/cljc/dao/space/dht.cljc" {:secure-random 3}
   "src/cljc/yin/vm/linker/sign.cljc" {:secure-random 2}
   ;; yang.clojure.cljc left the gensym list in slice 7: the library's gensym
   ;; uses an explicit supply and the and/or expansion temps are dsg__-prefixed,
   ;; so the file no longer calls host gensym at all.
   "src/cljc/yang/php.cljc" {:gensym 1}
   "src/cljc/dao/space/transact.cljc" {:gensym 1}
   ;; §3.2 clocks, ambient reads, printing, timers: the driver and the seams
   "src/cljc/yin/repl/main.cljc" {:ambient 6, :clock 4, :print 14}
   "src/clj/dao/jing/content/driver.clj" {:clock 2, :timer 1}
   "src/clj/dao/stream/datagram/jvm.clj" {:timer 1}
   "src/clj/dao/stream/ws/jvm.clj" {:clock 1}
   "src/cljs/dao/stream/ws/node.cljs" {:clock 1}
   "src/cljs/dao/stream/waitset/driver.cljs" {:timer 2}
   "src/cljd/dao/stream/ws/dart.cljd" {:clock 1}
   "src/cljd/dao/stream/waitset/driver.cljd" {:timer 1}
   ;; §2.4 M-entry callers (H2 audit): btree's owned node arrays, never
   ;; appended; chunks and cbor each fill an array they just allocated,
   ;; before returning it; sign fills the provider's nextBytes buffer
   ;; from a fresh seed copy (never a published value)
   "src/cljc/dao/data/btree.cljc" {:m-entry 33}
   "src/cljc/dao/stream/chunks.cljc" {:m-entry 1}
   "src/cljc/dao/jing/cbor.cljc" {:m-entry 8}
   "src/cljc/dao/host/sign.cljc" {:secure-random 1, :m-entry 11}})


;; ---------------------------------------------------------------------------
;; The gate
;; ---------------------------------------------------------------------------

(defn scan
  "{file {:arms [line ...] :effects {kind [line ...]}}} for sources
   {file text}, or {file {:error msg}} for a file that cannot be read."
  [sources]
  (into (sorted-map)
        (map (fn [[file text]]
               [file (try
                       (let [forms (cond
                                     (string? text) (read-forms text)
                                     (:read-error text) (throw (ex-info (str "cannot read file: " (:read-error text))
                                                                        {:file file}))
                                     :else (throw (ex-info "cannot read file" {:file file})))]
                         {:arms (if (lane-specific? file)
                                  [:whole-file]
                                  (non-exempt-arms forms))
                          :effects (reduce (fn [m [k line]] (update m k (fnil conj []) line))
                                           {}
                                           (effect-calls (file-lanes file) forms))})
                       (catch Exception e {:error (or (ex-message e) (str e))}))]))
        sources))


(defn gate
  "The gate over sources {file text}: {:verdict :pass|:fail|:scan-failure
   :scanned n :errors [...] :arm-violations [...] :effect-violations [...]}."
  [sources list-1 list-2]
  (let [scanned (scan sources)
        errors (vec (keep (fn [[f r]] (when (:error r) [f (:error r)])) scanned))
        ok (remove (comp :error val) scanned)
        arm-violations (vec (for [[f r] ok
                                  :when (and (seq (:arms r)) (not (contains? list-1 f)))]
                              {:file f :lines (:arms r)}))
        effect-violations (vec (for [[f r] ok
                                     [k lines] (:effects r)
                                     :let [allowed (get-in list-2 [f k] 0)]
                                     :when (> (count lines) allowed)]
                                 {:file f :kind k :count (count lines)
                                  :allowed allowed :lines lines}))]
    {:verdict (cond (or (empty? scanned) (seq errors)) :scan-failure
                    (or (seq arm-violations) (seq effect-violations)) :fail
                    :else :pass)
     :scanned (count ok)
     :errors errors
     :arm-violations arm-violations
     :effect-violations effect-violations
     :observed scanned}))


(defn read-sources
  "{file text} for files. A missing file maps to nil text and a file whose
   read throws to {:read-error msg}; the gate reports both as scan
   failures, naming the file."
  [files]
  (into {} (map (fn [f]
                  [f (when (.isFile (io/file f))
                       (try (slurp f)
                            (catch Exception e {:read-error (or (ex-message e) (str e))})))]))
        files))


(def current
  (delay (gate (read-sources (gate-files)) list-1 list-2)))


;; ---------------------------------------------------------------------------
;; Library mode: the host-library gate (§8.2, built in H2)
;; ---------------------------------------------------------------------------

(def library-names
  "§8.2's host-library names by slice, each with the pattern a symbol's or
   a libspec string's text must match to name it. H3 adds the hash and
   signature libraries."
  {"BigInteger" {:slice "H2" :pattern #"(^|[./])BigInteger($|[./])"}
   "BigDecimal" {:slice "H2" :pattern #"(^|[./])BigDecimal($|[./])"}
   "clojure.lang.Ratio" {:slice "H2" :pattern #"^clojure\.lang\.Ratio($|[./])"}
   "java.text" {:slice "H2" :pattern #"^java\.text($|[./])"}
   "unorm" {:slice "H2" :pattern #"(^|[./:])unorm"}
   "MessageDigest" {:slice "H3" :pattern #"(^|[./])MessageDigest($|[./])"}
   "Blake3" {:slice "H3" :pattern #"(^|[./])Blake3($|[./])"}
   "noble" {:slice "H3" :pattern #"(^|[./:@])noble"}
   "blake3_dart" {:slice "H3" :pattern #"(^|[./:])blake3_dart"}
   "package:crypto" {:slice "H3" :pattern #"package:crypto"}
   "ed25519_edwards" {:slice "H3" :pattern #"(^|[./:])ed25519_edwards"}
   "goog.crypt.base64" {:slice "H3" :pattern #"goog\.crypt\.base64"}})


(def library-allowed-root
  "Only the profile may name a host library (§8.2)."
  "src/cljc/dao/host/")


(defn library-scope
  "The closure files library mode scans: every gate file outside the
   profile root and outside the codec track (whose arms are that track's,
   §8.2)."
  [files list-1]
  (into (sorted-set)
        (remove #(or (str/starts-with? % library-allowed-root)
                     (= :codec-track (get-in list-1 [% :category]))))
        files))


(defn- ns-library-texts
  "The texts an ns form names libraries by: its string libspecs, and each
   import's full class name (`(java.text Normalizer)` names
   java.text.Normalizer)."
  [ns-form]
  (let [clauses (filter #(and (seq? %) (#{:require :import :use :require-macros} (first %)))
                        (rest ns-form))]
    (concat
      (->> clauses
           (mapcat rest)
           (mapcat (fn [spec] (if (sequential? spec) spec [spec])))
           (filter string?))
      (for [c clauses
            :when (= :import (first c))
            spec (rest c)
            :when (and (sequential? spec) (symbol? (first spec)))
            cls (rest spec)
            :when (symbol? cls)]
        (str (first spec) "." cls)))))


(defn library-references
  "[[name line] ...]: every reference in forms, as a reader with features
   reads them, that names a host library of library-names. A reference is
   a symbol (a call, a class name, an import, a type hint), a string
   libspec or an imported class name in the ns form; strings elsewhere, docstrings included,
   keywords, comments, quoted forms and (comment ...) never count."
  ([features forms] (library-references nil features forms))
  ([file features forms]
   (let [forms (keep #(select-arms features %) forms)
         acc (atom [])
         hit! (fn [text line]
                (doseq [[n {:keys [pattern]}] library-names
                        :when (re-find pattern text)]
                  (swap! acc conj [n line])))
         walk (fn walk
                [form line]
                (let [line (or (line-of form) line)]
                  (when-let [tag (:tag (meta form))]
                    (when (symbol? tag) (hit! (str tag) line)))
                  (cond
                    (symbol? form) (hit! (str form) line)
                    (and (seq? form) (#{'quote 'comment} (head form))) nil
                    (map? form) (doseq [[k v] form] (walk k line) (walk v line))
                    (coll? form) (doseq [x form] (walk x line))
                    :else nil)))]
     (doseq [f forms]
       (when (= 'ns (head f))
         (doseq [s (ns-library-texts f)] (hit! s (line-of f))))
       (walk f nil))
     (if (= file "src/cljc/yin/vm/linker/sign.cljc")
       (vec (remove (fn [[n _]] (= n "ed25519_edwards")) @acc))
       @acc))))


(defn library-gate
  "Library mode over sources {file text} (the files already in scope):
   {:verdict :pass|:fail|:scan-failure :scanned n :errors [...]
   :hits [{:file f :name n :lines [...]} ...]}. An empty source set or an
   unreadable file is a scan failure, never a pass."
  ([sources] (library-gate sources (set (keys library-names))))
  ([sources names]
   (let [scanned (into (sorted-map)
                       (map (fn [[file text]]
                              [file (try
                                      (when-not (string? text)
                                        (throw (ex-info (str "cannot read file"
                                                             (when-let [e (:read-error text)] (str ": " e)))
                                                        {:file file})))
                                      (let [forms (read-forms text)]
                                        {:refs (->> (for [l (file-lanes file)
                                                          fs (scan-features l)]
                                                      fs)
                                                    distinct
                                                    (mapcat #(library-references file % forms))
                                                    distinct
                                                    (filter (comp names first))
                                                    vec)})
                                      (catch Exception e {:error (or (ex-message e) (str e))}))]))
                       sources)
         errors (vec (keep (fn [[f r]] (when (:error r) [f (:error r)])) scanned))
         hits (vec (for [[f r] scanned
                         :when (seq (:refs r))
                         [n refs] (group-by first (:refs r))]
                     {:file f :name n :lines (vec (sort (distinct (keep second refs))))}))]
     {:verdict (cond (or (empty? scanned) (seq errors)) :scan-failure
                     (seq hits) :fail
                     :else :pass)
      :scanned (- (count scanned) (count errors))
      :errors errors
      :hits hits})))


(def library-current
  (delay (library-gate (read-sources (library-scope (gate-files) list-1)))))


;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest closure-totals-are-the-design-totals
  (testing "§3.1 after H4, the yang.core L2 regeneration and J6b's ws parser/upgrade: 155 / 153 / 154 files, 147 shared"
    (let [files (into {} (map (fn [l] [l (set (map :file (:files (committed-closure l))))]) lanes))]
      (is (= 155 (count (files :clj))))
      (is (= 153 (count (files :cljs))))
      (is (= 154 (count (files :cljd))))
      (is (= 147 (count (apply set/intersection (vals files)))))
      (doseq [l lanes]
        (is (= (count (files l)) (:total (committed-closure l))))
        (is (every? #(seq (:classes %)) (:files (committed-closure l))))))))


(deftest independent-traversal-agrees-with-the-committed-closure
  (doseq [l lanes]
    (testing (str "lane " l)
      (let [committed (set (map :file (:files (committed-closure l))))
            walked (traverse l)]
        (is (= #{} (set/difference committed walked)) "committed, not walked")
        (is (= #{} (set/difference walked committed)) "walked, not committed")))))


(deftest gate-passes-on-the-current-tree
  (let [r @current]
    (is (= 168 (count (closure-files))) "the union of the three lanes")
    (is (= 168 (count (gate-files))) "no profile addition outside the closure after H3")
    (is (= (count (gate-files)) (:scanned r)))
    (is (= [] (:errors r)))
    (is (= [] (:arm-violations r)))
    (is (= [] (:effect-violations r)))
    (is (= :pass (:verdict r)))))


(deftest list-1-equals-the-observed-arms
  (testing "every list-1 file is in the closure and still holds a host arm"
    (let [observed (set (keep (fn [[f r]] (when (seq (:arms r)) f)) (:observed @current)))]
      (is (= (set (keys list-1)) observed))))
  (testing "every list-1 file has a known category, an allowed slice and, on every lane, a class its category admits"
    (is (= [] (list-1-problems list-1 (lane-classes)))))
  (testing "each check fails on a deliberately wrong entry (in memory)"
    (let [lc (lane-classes)
          f "src/cljc/yin/vm/data.cljc"
          kinds (fn [l1 lc] (set (map first (list-1-problems l1 lc))))]
      (is (= #{:unknown-category} (kinds (assoc-in list-1 [f :category] :h0-unlisted) lc)))
      (is (= #{:slice-not-allowed} (kinds (assoc-in list-1 [f :clears] "H9") lc)))
      (is (= #{:slice-not-allowed}
             (kinds (assoc-in list-1 ["src/cljc/dao/pretty.cljc" :clears] :pending) lc))
          "the OQ11 retention is allowed only in the migration-source row")
      (is (= #{:class-not-in-category}
             (kinds (assoc-in list-1 ["src/cljc/dao/stream/ringbuffer.cljc" :category] :dialect-arm) lc)))
      (is (= [[:class-none f [:cljs :migration-source #{:none}]]]
             (list-1-problems list-1 (assoc-in lc [:cljs f] #{:none})))
          "a :none on one lane is not masked by the other lanes")
      (is (= [[:empty-classes f [:cljd :migration-source #{}]]]
             (list-1-problems list-1 (assoc-in lc [:cljd f] #{}))))
      (is (= #{:in-no-lane}
             (kinds (assoc list-1 "src/cljc/no/such.cljc" (entry :dialect-arm :stays)) lc)))))
  (testing "the §8.1 bounded exceptions: exactly the eight files, each needed, each bound to its category"
    (let [lc (lane-classes)
          bare (list-1-problems list-1 lc {})]
      (is (= (set (keys list-1-exceptions)) (set (map second bare)))
          "the bare table fails on the exception files and no others")
      (is (= #{:class-not-in-category} (set (map first bare))))
      (is (= 12 (count bare)) "eight files, twelve lane rows (§8.1)")
      (doseq [[f {:keys [category]}] list-1-exceptions]
        (is (= category (get-in list-1 [f :category])) f)
        (is (seq (list-1-problems list-1 lc (dissoc list-1-exceptions f))) f)
        (is (= #{:class-not-in-category :exception-category}
               (set (map first (list-1-problems (assoc-in list-1 [f :category] :dialect-arm) lc))))
            "an exception does not travel with the file to another category")
        (let [ps (list-1-problems list-1 lc (assoc-in list-1-exceptions [f :category] :profile))]
          (is (some #{[:exception-category f [:profile category]]} ps) f)
          (is (= #{:class-not-in-category :exception-category} (set (map first ps)))
              "an exception bound to a category the file is not in admits nothing"))))))


(deftest profile-additions-are-scanned-until-the-closure-reaches-them
  (let [on-disk (into (sorted-set)
                      (comp (filter #(.isFile ^java.io.File %))
                            (map #(str/replace (.getPath ^java.io.File %) "\\" "/"))
                            (filter #(re-find #"\.clj[cs]?$|\.cljd$" %)))
                      (file-seq (io/file profile-root)))
        closure (closure-files)]
    (testing "every profile file is a closure file or a listed addition, so none escapes the gate"
      (is (seq on-disk))
      (is (= #{} (set/difference on-disk closure (set (keys profile-additions))))))
    (testing "each addition exists, is outside every lane's closure (else delete the entry), and is on list 1 as :profile"
      (doseq [[f {:keys [classes]}] profile-additions]
        (is (contains? on-disk f) f)
        (is (not (contains? closure f)) (str f ": the closure reaches it; delete its profile-additions entry"))
        (is (= :profile (get-in list-1 [f :category])) f)
        (is (= #{:profile-internal-pure} classes) f)))
    (testing "the gate scanned them and found their arms"
      (doseq [f (keys profile-additions)]
        (is (seq (get-in @current [:observed f :arms])) f)
        (is (= {} (get-in @current [:observed f :effects])) f)))))


(deftest list-2-equals-the-observed-calls
  (testing "every permitted count is the observed count (no slack)"
    (let [observed (into {} (keep (fn [[f r]]
                                    (when (seq (:effects r))
                                      [f (update-vals (:effects r) count)]))
                                  (:observed @current)))]
      (is (= list-2 observed)))))


(defn- outside
  "The first closure .cljc file outside m with no arms and no effect calls."
  [m]
  (first (for [[f r] (:observed @current)
               :when (and (not (lane-specific? f)) (not (contains? m f))
                          (empty? (:arms r)) (empty? (:effects r)))]
           f)))


(deftest an-injected-host-arm-outside-list-1-fails-the-gate
  (let [target (outside list-1)
        sources (update (read-sources (closure-files)) target str
                        "\n(def injected #?(:clj (java.lang.Math/abs -1) :cljs 1))\n")
        r (gate sources list-1 list-2)]
    (is (some? target))
    (is (= :fail (:verdict r)))
    (is (= [target] (map :file (:arm-violations r))))
    (is (= [] (:effect-violations r)))))


(deftest an-injected-random-uuid-outside-list-2-fails-the-gate
  (let [target (outside list-2)
        sources (update (read-sources (closure-files)) target str
                        "\n(def injected (random-uuid))\n")
        r (gate sources list-1 list-2)]
    (is (some? target))
    (is (= :fail (:verdict r)))
    (is (= [{:file target :kind :random-uuid :count 1 :allowed 0}]
           (map #(dissoc % :lines) (:effect-violations r))))
    (is (= [] (:arm-violations r)))))


(defn- inject-effect
  "The gate over the closure with text appended to the first closure .cljc
   file outside list 2."
  [text]
  (let [target (outside list-2)]
    [target (gate (update (read-sources (closure-files)) target str text) list-1 list-2)]))


(deftest effect-calls-escape-neither-body-vectors-nor-other-scopes
  (testing "H0-R1: a call inside a function-body vector"
    (let [[target r] (inject-effect "\n(defn injected [] [(random-uuid)])\n")]
      (is (= :fail (:verdict r)))
      (is (= [{:file target :kind :random-uuid :count 1 :allowed 0}]
             (map #(dissoc % :lines) (:effect-violations r))))
      (is (= [] (:arm-violations r)))))
  (testing "H0-R2: one arity's parameter does not shadow core in another arity"
    (let [[target r] (inject-effect "\n(defn injected\n  ([random-uuid] random-uuid)\n  ([] (random-uuid)))\n")]
      (is (= :fail (:verdict r)))
      (is (= [{:file target :kind :random-uuid :count 1 :allowed 0}]
             (map #(dissoc % :lines) (:effect-violations r))))
      (is (= [] (:arm-violations r)))))
  (testing "H0-R2: a :cljs definition does not shadow core on :clj"
    (let [[target r] (inject-effect "\n#?(:cljs (defn random-uuid [] 1))\n(defn injected [] (random-uuid))\n")]
      (is (= :fail (:verdict r)))
      (is (= [{:file target :kind :random-uuid :count 1 :allowed 0}]
             (map #(dissoc % :lines) (:effect-violations r))))
      (is (= [target] (map :file (:arm-violations r))) "the definition's arm is a host arm too")))
  (testing "H0-R5: specify!'s target expression is walked"
    (let [[target r] (inject-effect "\n(defn injected [] (specify! (do (random-uuid) (js-obj)) Object (toString [this] \"x\")))\n")]
      (is (= :fail (:verdict r)))
      (is (= [{:file target :kind :random-uuid :count 1 :allowed 0}]
             (map #(dissoc % :lines) (:effect-violations r))))
      (is (= [] (:arm-violations r))))))


(deftest a-scan-failure-is-not-a-pass
  (testing "zero files read"
    (let [r (gate {} list-1 list-2)]
      (is (= :scan-failure (:verdict r)))
      (is (= 0 (:scanned r)))))
  (testing "a closure file that cannot be read"
    (let [r (gate (assoc (read-sources (closure-files)) "src/cljc/no/such.cljc" nil)
                  list-1 list-2)]
      (is (= :scan-failure (:verdict r)))
      (is (= ["src/cljc/no/such.cljc"] (map first (:errors r))))))
  (testing "H0-R3: a closure file whose read throws"
    (let [f "src/cljc/dao/data.cljc"
          slurp' slurp
          sources (with-redefs [slurp (fn [x & opts]
                                        (if (= f (str x))
                                          (throw (java.io.IOException. "injected read failure"))
                                          (apply slurp' x opts)))]
                    (read-sources (closure-files)))
          r (gate sources list-1 list-2)]
      (is (= :scan-failure (:verdict r)))
      (is (= [[f "cannot read file: injected read failure"]] (:errors r)))
      (is (= (dec (count (closure-files))) (:scanned r))))))


(defn- sha-256
  [file]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.digest d (java.nio.file.Files/readAllBytes (.toPath (io/file file))))))))


(deftest corpus-bytes-match-the-frozen-hash-set
  (testing "H0 (d): the Jing corpus and codec wire fixtures, byte-identical"
    (let [{:keys [roots files]} (edn/read-string (slurp "docs/design/host-boundary/corpus-sha256.edn"))
          on-disk (into (sorted-set)
                        (for [r roots
                              f (file-seq (io/file r))
                              :when (.isFile ^java.io.File f)]
                          (str/replace (.getPath ^java.io.File f) "\\" "/")))]
      (is (= 18 (count files)))
      (is (= (set (keys files)) on-disk))
      (doseq [[f h] files]
        (is (= h (sha-256 f)) f)))))


(deftest exemptions-and-resolution
  (testing "§3.3 patterns are exempt; a value arm is not"
    (is (= [] (non-exempt-arms (read-forms "(try 1 (catch #?(:clj Exception :cljs :default) e e))"))))
    (is (= [] (non-exempt-arms (read-forms "(ns a (:require #?(:clj [x :as y] :cljs [z :as y])))"))))
    (is (= [] (non-exempt-arms (read-forms "(def s #?(:clj (Object.) :cljs (js-obj)))"))))
    (is (= 1 (count (non-exempt-arms (read-forms "(def n #?(:clj 1 :cljs 2))"))))))
  (testing "locals, file defs and quoted forms are not core calls"
    (is (= [] (effect-calls (read-forms "(defn f [pr] (pr 1))"))))
    (is (= [] (effect-calls (read-forms "(let [gensym inc] (gensym 1))"))))
    (is (= [] (effect-calls (read-forms "(declare gensym) (gensym)"))))
    (is (= [] (effect-calls (read-forms "(def code '(println 1))"))))
    (is (= [:random-uuid :print] (map first (effect-calls (read-forms "(random-uuid) (println 1)"))))))
  (testing "arities scope their own parameters; bodies, defaults and dispatch values are walked"
    (is (= [] (effect-calls (read-forms "(defn f ([pr] (pr 1)) ([pr x] (pr x)))"))))
    (is (= [:print] (map first (effect-calls (read-forms "(fn ([pr] (pr 1)) ([] (pr 2)))")))))
    (is (= [:rand] (map first (effect-calls (read-forms "(fn [] [1 (rand)])")))))
    (is (= [:rand] (map first (effect-calls (read-forms "(fn [{:keys [a] :or {a (rand)}}] a)")))))
    (is (= [:rand] (map first (effect-calls (read-forms "(let [{:keys [a] :or {a (rand)}} {}] a)")))))
    (is (= [] (effect-calls (read-forms "(defmethod m [:a] [rand] (rand))"))))
    (is (= [:rand] (map first (effect-calls (read-forms "(extend-type T P (m ([x] x) ([x rand] (rand)) ([y] (rand))))")))))
    (is (= [:rand :rand] (map first (effect-calls (read-forms "(specify (rand) P (m [x] (rand)))"))))
        "specify's target and its method body")
    (is (= [] (effect-calls (read-forms "(fn [rand] (specify! (rand) P (m [x] (rand))))")))
        "the target sees the enclosing locals")
    (is (= [:rand] (map first (effect-calls (read-forms "(specify! (rand) P (m [rand] (rand)))"))))
        "a method parameter shadows only within its method"))
  (testing "each lane resolves its own arms and definitions"
    (is (= [:gensym] (map first (effect-calls (read-forms "#?(:clj (declare gensym)) (gensym)")))))
    (is (= [] (effect-calls [:clj] (read-forms "#?(:clj (declare gensym)) (gensym)"))))
    (is (= [:gensym] (map first (effect-calls (read-forms "(ns a (:refer-clojure #?@(:cljs [:exclude [gensym]]))) (gensym)")))))
    (is (= [:random-uuid] (map first (effect-calls (read-forms "#?(:clj 1 :cljd (random-uuid))"))))
        "a :cljd arm behind a :clj arm is read on the cljd lane")
    (is (= [[:rand 1] [:rand 1]] (effect-calls (read-forms "#?(:clj (rand) :cljs (rand))")))
        "two source sites count twice")
    (is (= 1 (count (effect-calls (read-forms "(rand)")))) "one site read on three lanes counts once")))


(deftest library-mode-finds-no-host-library-outside-the-profile
  (testing "§8.2 (H2 and H3 names): within the adopted closure, no executable reference to BigInteger, BigDecimal, clojure.lang.Ratio, java.text, unorm, MessageDigest, Blake3, noble, blake3_dart, package:crypto, ed25519_edwards, or goog.crypt.base64 outside dao/host/ (and ed25519_edwards permitted in sign.cljc)"
    (let [r @library-current
          scope (library-scope (gate-files) list-1)]
      (is (seq scope))
      (is (not-any? #(str/starts-with? % library-allowed-root) scope))
      (is (not-any? #(= :codec-track (get-in list-1 [% :category])) scope))
      (is (= (count scope) (:scanned r)))
      (is (= [] (:errors r)))
      (is (= [] (:hits r)))
      (is (= :pass (:verdict r)))))
  (testing "the profile itself names them (so the scope exclusion is what passes it)"
    (let [profile (filter #(str/starts-with? % library-allowed-root) (gate-files))
          r (library-gate (read-sources profile))]
      (is (= :fail (:verdict r)))
      (is (= (disj (set (keys library-names)) "goog.crypt.base64")
             (set (map :name (:hits r))))))))


(deftest library-mode-negative-fixtures
  (let [fixture "src/cljc/fixture/library.cljc"]
    (testing "an executable BigInteger reference outside the allowlist fails"
      (doseq [text ["(ns fixture.library)\n(defn f [x] (BigInteger/valueOf x))\n"
                    "(ns fixture.library (:import (java.math BigInteger)))\n(defn f [] 1)\n"
                    "(ns fixture.library)\n(defn f [^java.math.BigInteger x] (.signum x))\n"
                    "(ns fixture.library)\n(def n #?(:clj (java.math.BigInteger. \"1\") :cljs 1))\n"
                    "(ns fixture.library (:require [\"unorm\" :as u]))\n(defn f [s] (u/nfc s))\n"
                    "(ns fixture.library)\n(defn f [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))\n"]]
        (let [r (library-gate {fixture text})]
          (is (= :fail (:verdict r)) text)
          (is (= [fixture] (distinct (map :file (:hits r)))) text))))
    (testing "BigInteger only in a docstring, a string, a comment, a keyword or a quoted form passes"
      (let [r (library-gate {fixture (str "(ns fixture.library\n  \"Uses no BigInteger; see java.text and unorm.\")\n"
                                          "(defn f\n  \"BigInteger BigDecimal clojure.lang.Ratio\"\n  []\n"
                                          "  ;; BigInteger in a comment\n"
                                          "  [:BigInteger :rgba8unorm \"java.math.BigInteger\" '(BigInteger/ONE)])\n"
                                          "(comment (BigInteger/valueOf 1))\n")})]
        (is (= :pass (:verdict r)))
        (is (= [] (:hits r)))
        (is (= 1 (:scanned r)))))
    (testing "a scan failure is distinct from no hits"
      (is (= :scan-failure (:verdict (library-gate {}))))
      (let [r (library-gate {fixture nil})]
        (is (= :scan-failure (:verdict r)))
        (is (= [fixture] (map first (:errors r)))))
      (let [r (library-gate {fixture "(ns fixture.library) (defn f [] "})]
        (is (= :scan-failure (:verdict r)))))))
