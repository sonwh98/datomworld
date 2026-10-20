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


(defn all-arms
  "form with every reader conditional replaced by all its arms, so every
   lane's text is seen: a splicing conditional's elements are spliced into
   the enclosing collection; a non-splicing one becomes (do arm ...), or,
   with splice-all?, is spliced too (for ns clauses, where position does
   not bind)."
  ([form] (all-arms false form))
  ([splice-all? form]
   (let [expand (fn [xs]
                  (mapcat (fn [x]
                            (if (and (rc? x) (or splice-all? (splicing? x)))
                              (map #(all-arms splice-all? %)
                                   (mapcat (fn [[_ v]] (if (splicing? x) v [v])) (rc-arms x)))
                              [(all-arms splice-all? x)]))
                          xs))]
     (cond
       (rc? form) (cons 'do (map #(all-arms splice-all? %)
                                 (mapcat (fn [[_ v]] (if (splicing? form) v [v]))
                                         (rc-arms form))))
       (seq? form) (with-meta (apply list (expand form)) (meta form))
       (vector? form) (vec (expand form))
       (map? form) (into {} (map (fn [[k v]] [(all-arms splice-all? k) (all-arms splice-all? v)])
                                 form))
       :else form))))


(defn file-scope
  "{:aliases {alias ns} :refers {sym ns} :shadowed #{sym}} of a file:
   every lane's ns arms, its top-level defs, its :refer-clojure excludes."
  [forms]
  (let [ns-form (all-arms true (first (filter #(= 'ns (head %)) forms)))
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
                  (map all-arms)
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
    (map? b) (mapcat binding-syms (concat (keys b) (vals b)))
    (coll? b) (mapcat binding-syms b)
    :else []))


(def let-heads
  '#{let let* loop loop* when-let if-let when-some if-some when-first
     with-open dotimes for doseq})


(def fn-heads '#{fn fn* defn defn- defmacro defmethod})


(defn effect-calls
  "[[kind line] ...] for the resolved effect calls in forms."
  [forms]
  (let [{:keys [aliases refers shadowed]} (file-scope forms)
        acc (atom [])
        hit! (fn [kind sym] (swap! acc conj [kind (line-of sym)]))
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
                                   (doseq [f fns] (walk-fn (rest f) b))
                                   (walk-all (nnext form) b))
                    (fn-heads h) (walk-fn (rest form) bound)
                    (= 'catch h) (walk-all (drop 3 form) (conj bound (nth form 2 nil)))
                    (deftype-heads h) (let [fields (filter vector? (take 3 form))
                                            b (into bound (mapcat binding-syms fields))]
                                        (doseq [x (drop 2 form)]
                                          (if (and (seq? x) (vector? (second x)))
                                            (walk-fn x b)
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
                                  (do (walk rhs b) (into b (binding-syms lhs)))))
                              bound
                              (partition 2 (if (vector? bvec) bvec [])))]
                (walk-all body b)))
            (walk-fn
              [tail bound]
              ;; tail: [name? doc? attrs? params body...] or [name? (params body...)...]
              (let [params (concat (filter vector? tail)
                                   (keep #(when (and (seq? %) (vector? (first %))) (first %)) tail))
                    b (into (into bound (filter symbol? (take 1 tail)))
                            (mapcat binding-syms params))]
                (walk-all (remove #(or (vector? %) (symbol? %)
                                       (and (seq? %) (vector? (first %))))
                                  tail)
                          b)
                (doseq [x tail :when (and (seq? x) (vector? (first x)))]
                  (walk-all (rest x) b))))]
      (doseq [f forms] (walk (all-arms f) #{})))
    @acc))


;; ---------------------------------------------------------------------------
;; List 1 and list 2
;; ---------------------------------------------------------------------------

(def list-1
  "The closure files permitted non-exempt host arms (§8.1), by category.
   Generated from the H0 tree by the gate and reconciled against the §8.1
   baseline; :h0-unlisted marks the files with real arms that §8.1 does
   not name (an H0 finding, to resolve before H1)."
  {;; H1-H4 migration sources
   "src/cljc/yin/vm/integer/host.cljc" :migration-source
   "src/cljc/dao/jing/cbor.cljc" :migration-source
   "src/cljc/dao/jing/cbor/boring.cljc" :migration-source
   "src/cljd/dao/jing/cbor/cljd.cljd" :migration-source
   "src/cljc/dao/jing.cljc" :migration-source
   "src/cljc/yin/vm/linker/sign.cljc" :migration-source
   "src/cljc/yin/vm/debruijn.cljc" :migration-source
   "src/cljc/yin/vm/debruijn_code.cljc" :migration-source
   "src/cljc/yin/vm/data.cljc" :migration-source
   "src/cljc/yin/vm/values.cljc" :migration-source
   "src/cljc/dao/stream/chunks.cljc" :migration-source
   "src/cljc/dao/stream/base64.cljc" :migration-source
   "src/cljc/dao/jing/dht.cljc" :migration-source
   "src/cljc/yin/vm/linker.cljc" :migration-source
   "src/cljc/dao/stream/datagram.cljc" :migration-source
   ;; Profile
   "src/cljc/dao/data/arrays.cljc" :profile
   ;; Retained runtime prerequisites (table C) and display
   "src/cljc/dao/data/btree.cljc" :runtime-prereq
   "src/cljc/dao/data/btree/storage.cljc" :runtime-prereq
   "src/cljc/dao/pretty.cljc" :runtime-prereq
   ;; Codec-track files
   "src/cljc/dao/stream/ws.cljc" :codec-track
   "src/cljc/dao/stream/transit.cljc" :codec-track
   "src/cljc/dao/stream/cbor.cljc" :codec-track
   "src/cljc/dao/stream/cbor/boring.cljc" :codec-track
   "src/cljc/dao/stream/codec.cljc" :codec-track
   ;; Seams and driver: every lane-specific file, the selector, the driver
   "src/clj/dao/jing/content/driver.clj" :seam-driver
   "src/clj/dao/stream/datagram/jvm.clj" :seam-driver
   "src/clj/dao/stream/waitset/driver.clj" :seam-driver
   "src/clj/dao/stream/ws/jvm.clj" :seam-driver
   "src/clj/yang/antlr/cst.clj" :seam-driver
   "src/clj/yin/repl/host/jvm.clj" :seam-driver
   "src/cljs/dao/stream/datagram/node.cljs" :seam-driver
   "src/cljs/dao/stream/waitset/driver.cljs" :seam-driver
   "src/cljs/dao/stream/ws/node.cljs" :seam-driver
   "src/cljs/yang/antlr/cst.cljs" :seam-driver
   "src/cljs/yin/repl/host.cljs" :seam-driver
   "src/cljd/dao/stream/cbor/cljd.cljd" :seam-driver
   "src/cljd/dao/stream/transit/cljd.cljd" :seam-driver
   "src/cljd/dao/stream/datagram/dart.cljd" :seam-driver
   "src/cljd/dao/stream/waitset/driver.cljd" :seam-driver
   "src/cljd/dao/stream/ws/dart.cljd" :seam-driver
   "src/cljd/yang/antlr/cst.cljd" :seam-driver
   "src/cljd/yin/repl/host.cljd" :seam-driver
   "src/cljc/yin/repl/main.cljc" :seam-driver
   "src/cljc/yin/repl/host.cljc" :seam-driver
   "src/cljc/yin/repl/state.cljc" :seam-driver
   ;; Deferred-scope files (§9)
   "src/cljc/dao/jing/file.cljc" :deferred-scope
   "src/cljc/dao/space/store/fs.cljc" :deferred-scope
   "src/cljc/dao/space/store.cljc" :deferred-scope
   "src/cljc/dao/stream/journal/file.cljc" :deferred-scope
   "src/cljc/dao/jing/coordinate.cljc" :deferred-scope
   "src/cljc/dao/space/dht.cljc" :deferred-scope
   "src/cljc/yin/repl.cljc" :deferred-scope
   "src/cljc/yang/python/antlr/parser.cljc" :deferred-scope
   "src/cljc/yang/python/antlr/lower.cljc" :deferred-scope
   "src/cljc/yang/python/antlr/prelude.cljc" :deferred-scope
   "src/cljc/yang/python/antlr/render.cljc" :deferred-scope
   "src/cljc/yang/python/antlr/repl_probe.cljc" :deferred-scope
   "src/cljc/dao/stream/journal.cljc" :deferred-scope
   "src/cljc/dao/space/transactor.cljc" :deferred-scope
   "src/cljc/yin/vm/ucf/authority.cljc" :deferred-scope
   ;; H0 finding: real host arms in closure files §8.1 does not name
   "src/cljc/dao/space/index.cljc" :h0-unlisted ; :78 host numeric predicates
   "src/cljc/dao/space/query.cljc" :h0-unlisted ; :808 runtime type name
   "src/cljc/dao/stream/ringbuffer.cljc" :h0-unlisted ; :168 host atom type
   "src/cljc/dao/stream/rpc.cljc" :h0-unlisted ; :114 rand (entropy, §9.1)
   "src/cljc/yang/antlr/packet.cljc" :h0-unlisted ; :67 code points
   "src/cljc/yang/clojure.cljc" :h0-unlisted ; :375 runtime type name
   "src/cljc/yang/php.cljc" :h0-unlisted ; :95,98 number parsing
   "src/cljc/yin/repl/adapter.cljc" :h0-unlisted ; :58 edn reader
   "src/cljc/yin/repl/dht.cljc" :h0-unlisted ; :104 edn reader
   "src/cljc/yin/repl/query.cljc" :h0-unlisted ; :460 byte length
   "src/cljc/yin/vm.cljc" :h0-unlisted ; :404 bytes->str decoding
   "src/cljc/yin/vm/debruijn_register_code.cljc" :h0-unlisted ; :439 safe integer
   "src/cljc/yin/vm/debruijn_register_effects.cljc" :h0-unlisted ; :130 safe integer
   "src/cljc/yin/vm/engine.cljc" :h0-unlisted ; :929 runtime type name
   "src/cljc/yin/vm/linker/closure.cljc" :h0-unlisted ; :32 byte length
   "src/cljc/yin/vm/ucf/rows.cljc" :h0-unlisted ; :282 byte length
   })


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
   "src/cljc/yin/vm/linker/sign.cljc" {:secure-random 3}
   "src/cljc/yang/clojure.cljc" {:gensym 2}
   "src/cljc/yang/php.cljc" {:gensym 1}
   "src/cljc/dao/space/transact.cljc" {:gensym 1}
   ;; §3.2 clocks, ambient reads, printing, timers: the driver and the seams
   "src/cljc/yin/repl/main.cljc" {:ambient 6, :clock 5, :print 13}
   "src/clj/dao/jing/content/driver.clj" {:clock 2, :timer 1}
   "src/clj/dao/stream/datagram/jvm.clj" {:timer 1}
   "src/clj/dao/stream/ws/jvm.clj" {:clock 1}
   "src/cljs/dao/stream/ws/node.cljs" {:clock 1}
   "src/cljs/dao/stream/waitset/driver.cljs" {:timer 2}
   "src/cljd/dao/stream/ws/dart.cljd" {:clock 1}
   "src/cljd/dao/stream/waitset/driver.cljd" {:timer 1}
   ;; §2.4 M-entry callers resolved today (dao.data.arrays); chunks, cbor
   ;; and sign mutate host arrays directly and call no M entry yet
   "src/cljc/dao/data/btree.cljc" {:m-entry 33}})


;; ---------------------------------------------------------------------------
;; The gate
;; ---------------------------------------------------------------------------

(defn lane-specific?
  [file]
  (not (str/ends-with? file ".cljc")))


(defn scan
  "{file {:arms [line ...] :effects {kind [line ...]}}} for sources
   {file text}, or {file {:error msg}} for a file that cannot be read."
  [sources]
  (into (sorted-map)
        (map (fn [[file text]]
               [file (try
                       (let [forms (if text
                                     (read-forms text)
                                     (throw (ex-info "cannot read file" {:file file})))]
                         {:arms (if (lane-specific? file)
                                  [:whole-file]
                                  (non-exempt-arms forms))
                          :effects (reduce (fn [m [k line]] (update m k (fnil conj []) line))
                                           {}
                                           (effect-calls forms))})
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
  "{file text} for files; an unreadable file maps to nil text, which the
   gate reports as a scan failure."
  [files]
  (into {} (map (fn [f] [f (when (.isFile (io/file f)) (slurp f))])) files))


(def current
  (delay (gate (read-sources (closure-files)) list-1 list-2)))


;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest closure-totals-are-the-design-totals
  (testing "§3.1: 142 / 140 / 141 files, 133 shared"
    (let [files (into {} (map (fn [l] [l (set (map :file (:files (committed-closure l))))]) lanes))]
      (is (= 142 (count (files :clj))))
      (is (= 140 (count (files :cljs))))
      (is (= 141 (count (files :cljd))))
      (is (= 133 (count (apply set/intersection (vals files)))))
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
    (is (= 155 (count (closure-files))) "the union of the three lanes")
    (is (= (count (closure-files)) (:scanned r)))
    (is (= [] (:errors r)))
    (is (= [] (:arm-violations r)))
    (is (= [] (:effect-violations r)))
    (is (= :pass (:verdict r)))))


(deftest list-1-equals-the-observed-arms
  (testing "every list-1 file is in the closure and still holds a host arm"
    (let [observed (set (keep (fn [[f r]] (when (seq (:arms r)) f)) (:observed @current)))]
      (is (= (set (keys list-1)) observed)))))


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


(deftest a-scan-failure-is-not-a-pass
  (testing "zero files read"
    (let [r (gate {} list-1 list-2)]
      (is (= :scan-failure (:verdict r)))
      (is (= 0 (:scanned r)))))
  (testing "a closure file that cannot be read"
    (let [r (gate (assoc (read-sources (closure-files)) "src/cljc/no/such.cljc" nil)
                  list-1 list-2)]
      (is (= :scan-failure (:verdict r)))
      (is (= ["src/cljc/no/such.cljc"] (map first (:errors r)))))))


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
    (is (= [:random-uuid :print] (map first (effect-calls (read-forms "(random-uuid) (println 1)")))))))
