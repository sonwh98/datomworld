(ns host-boundary.closure
  "The adopted closure of dao.stream.host-boundary.md §3.1, per lane.

   A require-form traversal from yin.repl.main over the ns declarations,
   parsed by clojure.tools.namespace.parse (read-ns-decl with the lane's
   reader features, deps-from-ns-decl for :require, :use and
   :require-macros; :as-alias and string (npm, dart:) libspecs are not
   dependencies). Lane features: :clj #{:clj}, :cljs #{:cljs},
   :cljd #{:cljd :clj} (the cljd build also reads :clj arms; the first
   matching arm wins, as in tools.reader). A namespace resolves to
   src/<lane>/<path>.<lane> first (the shadow: yin.repl.host is
   host.cljc on the JVM, host.cljs and host.cljd elsewhere), then
   src/cljc/<path>.cljc; a namespace with neither (clojure.core, a
   library) is outside the closure.

   It is not a compiler-resolved call graph: it over-includes a namespace
   required but unused, and does not see dynamic loading.

   Each file is tagged with its §3.1 classes (a set: a file can sit in
   several rows of the §3.1 table; :none means no host contact beyond the
   §3.3 exempt syntax).

   Run from the repo root:
     clojure -Sdeps '{:aliases {:hb {:extra-paths [\"src/dev\"]
                      :extra-deps {org.clojure/tools.namespace
                                   {:mvn/version \"1.5.0\"}}}}}' \\
       -M:hb -m host-boundary.closure [--check]
   writes docs/design/host-boundary/closure-{clj,cljs,cljd}.edn and
   prints the totals; --check writes nothing and exits 1 when a committed
   file differs from the computed closure."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.namespace.parse :as parse]
    [clojure.walk :as walk]))


(def root 'yin.repl.main)
(def lanes [:clj :cljs :cljd])
(def out-dir "docs/design/host-boundary")


(def lane-features
  {:clj #{:clj}, :cljs #{:cljs}, :cljd #{:cljd :clj}})


(defn ns->path
  [ns-sym]
  (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")))


(defn resolve-file
  "The repo-relative file for ns-sym on lane, or nil."
  [lane ns-sym]
  (let [p (ns->path ns-sym)
        lane-dir (name lane)]
    (some (fn [f] (when (.isFile (io/file f)) f))
          [(str "src/" lane-dir "/" p "." lane-dir)
           (str "src/cljc/" p ".cljc")])))


(defn strip-nils
  "Drops the nil a lane's arm leaves in a libspec position
   (`#?(:cljd nil :clj [x])` reads as nil on :cljd); deps-from-ns-decl
   refuses nil as unparsable, the compilers ignore it."
  [form]
  (walk/postwalk (fn [x]
                   (cond (vector? x) (vec (remove nil? x))
                         (seq? x) (apply list (remove nil? x))
                         :else x))
                 form))


(defn ns-decl
  [lane file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (strip-nils (parse/read-ns-decl r {:read-cond :allow
                                       :features (lane-features lane)}))))


(defn lane-closure
  "{file ns-sym} for every file reachable from root on lane."
  [lane]
  (loop [todo [root]
         seen #{}
         files {}]
    (if-let [n (first todo)]
      (if (seen n)
        (recur (rest todo) seen files)
        (let [seen (conj seen n)]
          (if-let [f (resolve-file lane n)]
            (let [decl (ns-decl lane f)
                  deps (sort (parse/deps-from-ns-decl decl))]
              (when-not (= n (parse/name-from-ns-decl decl))
                (throw (ex-info "ns name does not match its file"
                                {:lane lane :ns n :file f
                                 :declared (parse/name-from-ns-decl decl)})))
              (recur (concat (rest todo) deps) seen (assoc files f n)))
            (recur (rest todo) seen files))))
      files)))


;; ---------------------------------------------------------------------------
;; §3.1 classes. Keys are namespaces; a row's namespaces are named exactly as
;; the §3.1 table names them, expanded where the table abbreviates (".datagram"
;; = dao.stream.datagram, "the seams" = the lane-specific ws/datagram files).
;; Files of the §3.2 random-uuid/rand list carry :deferred-entropy.
;; ---------------------------------------------------------------------------

(def class-rows
  [[:profile-internal-pure "H1-H2 (writer H4)"
    '[yin.vm.integer.host dao.jing.cbor dao.jing.cbor.boring dao.jing.cbor.cljd
      yin.vm.debruijn yin.vm.debruijn-code yin.vm.data yin.vm.values
      dao.stream.chunks dao.stream.base64 dao.jing.dht yin.vm.linker
      dao.stream.datagram dao.data.arrays]]
   [:profile-hash-sign "H3" '[dao.jing yin.vm.linker.sign]]
   [:consumer "H2 (require change only)"
    '[yin.vm yin.vm.engine yin.vm.values yin.vm.data yin.vm.integer]]
   [:external-stream-seam "none (existing streams)"
    '[dao.stream.ws dao.stream.datagram dao.stream.udp
      dao.stream.ws.jvm dao.stream.ws.node dao.stream.ws.dart
      dao.stream.ws.browser dao.stream.datagram.jvm dao.stream.datagram.node
      dao.stream.datagram.dart yin.repl.host yin.repl.host.jvm
      yin.repl.host.common]]
   [:deferred-storage "deferred §9.2"
    '[dao.jing.file dao.space.store.fs dao.space.store
      dao.stream.journal.file dao.data.btree.storage]]
   [:deferred-entropy "deferred §9.1"
    '[dao.stream.ringbuffer dao.stream.memory-log dao.stream.ws
      dao.stream.remote dao.stream.remote-pair dao.stream.remote-route
      dao.stream.journal dao.jing.content.step dao.lease dao.await
      dao.space.store.fs yin.vm.linker yin.vm.ucf.custody
      yin.vm.ucf.holder.driver yin.vm.ffi.remote-serve yin.vm.telemetry
      yin.repl yin.repl.dht dao.stream.rpc dao.jing.dht dao.space.dht
      yin.vm.linker.sign]]
   [:deferred-timers "deferred §9.1"
    '[dao.jing.content.async dao.jing.coordinate dao.jing.content.driver
      dao.data.btree.storage]]
   [:driver "driver (ambient reads deferred §9.1)"
    '[yin.repl.main yin.repl yin.repl.state]]
   [:deferred-stage "deferred §9.3"
    '[yang.antlr.cst yang.frontend yang.stage yin.repl.frontends]]
   [:deferred-gensym "deferred §9.1"
    '[yang.clojure yang.php dao.space.transact]]
   [:runtime-prereq "table C, no slice"
    '[dao.data.btree dao.jing.file dao.stream.journal dao.space.transactor
      dao.space.schema yin.vm.ucf.authority]]
   ;; §3.1 names transit, cbor and codec; the rest are the codec track's
   ;; files by §8.3 (and ws-project by §8.1's codec row) and the lane twins.
   [:codec-track "codec track, untouched (§8.3)"
    '[dao.stream.transit dao.stream.cbor dao.stream.codec
      dao.stream.cbor.boring dao.stream.cbor.cljd dao.stream.transit.cljd
      dao.stream.ws dao.stream.remote-channel dao.stream.ws-project
      dao.jing.stream dao.data dao.stream.ws.jvm dao.stream.ws.node
      dao.stream.ws.dart dao.stream.ws.browser yin.repl.serve
      yin.repl.connect]]
   [:internal-pure-display "none (list 1, display only)" '[dao.pretty]]])


(def prefix-rows
  "Rows the §3.1 table names by prefix (`yang.python.antlr.*`)."
  [[:deferred-stage "yang.python.antlr."]])


(def lane-rows
  "Lane-specific files the §3.1 table does not name in a row but §3.1's
   lane-specific list and §8.1's \"Seams and driver\" row place: the
   waitset timer drivers (setTimeout, Timer) are seams of dao.stream.waitset."
  '{dao.stream.waitset.driver #{:external-stream-seam}})


(defn classes-of
  [ns-sym]
  (let [s (name ns-sym)
        cs (into (set (keep (fn [[c _ nss]] (when (some #{ns-sym} nss) c))
                            class-rows))
                 (keep (fn [[c pre]] (when (str/starts-with? s pre) c))
                       prefix-rows))
        cs (into cs (lane-rows ns-sym))]
    (if (seq cs) cs #{:none})))


(def class-legend
  (into (sorted-map) (map (fn [[c slice _]] [c slice]) class-rows)))


(defn lane-report
  [lane files shared]
  {:lane lane
   :root root
   :features (lane-features lane)
   :total (count files)
   :shared (count shared)
   :lane-specific (count (remove shared (keys files)))
   :class-legend (assoc class-legend :none "no host contact beyond §3.3")
   :files (vec (for [[f n] (sort-by key files)]
                 {:file f
                  :ns n
                  :shared (contains? shared f)
                  :classes (into (sorted-set) (classes-of n))}))})


(defn compute
  []
  (let [closures (into {} (map (fn [l] [l (lane-closure l)]) lanes))
        shared (apply set/intersection (map (comp set keys) (vals closures)))]
    {:shared shared
     :reports (into {} (map (fn [[l fs]] [l (lane-report l fs shared)])
                            closures))}))


(defn render
  [report]
  (str ";; Generated by src/dev/host_boundary/closure.clj (host-boundary H0).\n"
       ";; Do not edit by hand; regenerate with the command in that file's ns doc.\n"
       (with-out-str
         (binding [*print-namespace-maps* false]
           (pp/pprint report)))))


(defn out-file
  [lane]
  (str out-dir "/closure-" (name lane) ".edn"))


(defn -main
  [& args]
  (let [check? (some #{"--check"} args)
        {:keys [shared reports]} (compute)
        stale (atom [])]
    (doseq [l lanes]
      (let [r (reports l)
            text (render r)
            f (io/file (out-file l))]
        (println (format "%-5s total %d shared %d lane-specific %d"
                         (name l) (:total r) (:shared r) (:lane-specific r)))
        (if check?
          (when-not (and (.isFile f) (= text (slurp f)))
            (swap! stale conj (out-file l)))
          (do (io/make-parents f)
              (spit f text)))))
    (println "shared (all three lanes):" (count shared))
    (when (seq @stale)
      (println "STALE:" (str/join " " @stale))
      (System/exit 1))
    (shutdown-agents)))
