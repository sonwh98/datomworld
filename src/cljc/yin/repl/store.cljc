(ns yin.repl.store
  "Startup selection of the code index's store
   (docs/design/yin.repl.dao.space-index.md; the durable-store startup
   contract, slices 1-3).

   The store is chosen once, before the shell composes: `mem` — a fresh
   in-memory `dao.jing` store, today's behaviour — or `file:<dir>`, the
   durable directory store (dao.space.store).  This namespace parses the
   `--index-store` value, validates the `:index-store-spec` an embedder
   may hand `yin.repl/create-state`, and opens the store the resolved
   spec names.  Every bad spec — a missing value, an unknown scheme, an
   empty directory, a directory that cannot be opened, a host build
   without file support — is refused with its reason; nothing falls back
   to memory silently.

   What it answers is a generic `dao.jing` content handle and nothing
   more: no transport state, no node, no storage implementation the
   engine could reach through it.  A storage implementation past `:mem`
   and `{:type :file :dir dir}` is a composition's, never this
   namespace's (docs/design/yin.repl.dht-composition.md sections 2, 3 and
   6).  Such a composition opens its own store and hands the engine the
   prepared handle as `:index-store`; it may also register its own
   `--index-store` scheme here, as plain data (`parse-arg`'s second
   argument), so the engine's parser knows a prefix and a label and never
   a backend.

   The durable directory itself — the exclusive lock, HEAD, the validated
   snapshot, `:recovery` — is dao.space.store's; this namespace is one of
   its consumers.  The shell installs the recovery before it admits any
   evaluation (`yin.repl.index/rehydrate`), and a store whose index
   outlives the process also carries it across `(reset)` and VM
   selection; the memory store keeps today's empty rebuild.  That
   retention is a declared policy, not something the engine derives from
   a backend's own state (`retain-on-reset?`)."
  (:require [clojure.string :as str]
            [dao.jing.mem :as jing.mem]
            [dao.space.store :as durable]))


(def file-scheme
  "The `--index-store` scheme prefix naming a durable file store."
  "file:")


(defn- supported-text
  "The supported `--index-store` forms as one comma-separated string:
   this namespace's own, then every registered scheme's label in order."
  [extra-schemes]
  (str/join ", "
            (into ["mem" (str file-scheme "<dir>")]
                  (map :label)
                  extra-schemes)))


(defn parse-arg
  "The `--index-store` value as a spec: `:mem`, or `{:type :file :dir dir}`
   for `file:<dir>`.  A missing value, an unknown scheme, and an empty
   directory are refused here — with the supported forms named — so the
   arguments parser can refuse a bad flag before anything composes.

   `extra-schemes` registers the schemes a storage composition adds, each
   `{:prefix \"<scheme>:\" :label \"<as documented>\" :parse (fn [dir]
   spec)}`.  They are plain data: this namespace matches a prefix and
   calls `:parse` with the directory, and never learns what the answer
   means.  A composition's own checked spec then validates it
   (`checked-spec` refuses a type it does not know, so a registered
   scheme whose spec reaches the engine unchecked is still refused)."
  ([value] (parse-arg value nil))
  ([value extra-schemes]
   (let [supported (supported-text extra-schemes)
         extra (when (string? value)
                 (some (fn [{:keys [prefix parse]}]
                         (when (str/starts-with? value prefix)
                           (let [dir (subs value (count prefix))]
                             (if (str/blank? dir)
                               (throw (ex-info (str "--index-store " prefix
                                                    "<dir> needs a directory")
                                               {:value value
                                                :supported supported}))
                               (parse dir)))))
                       extra-schemes))]
     (cond
       (nil? value)
       (throw (ex-info (str "--index-store needs a value: " supported)
                       {:value value
                        :supported supported}))

       (some? extra)
       extra

       (= value "mem")
       :mem

       (str/starts-with? value file-scheme)
       (let [dir (subs value (count file-scheme))]
         (if (str/blank? dir)
           (throw (ex-info "--index-store file:<dir> needs a directory"
                           {:value value
                            :supported supported}))
           {:type :file :dir dir}))

       :else
       (throw (ex-info (str "Unknown --index-store " (pr-str value)
                            "; supported: " supported)
                       {:value value
                        :supported supported}))))))


(defn checked-spec
  "Validate an `:index-store-spec` as `yin.repl/create-state` receives it:
   nil means the `mem` default, `:mem` names it, and a file store is the
   map `{:type :file :dir dir}` with a non-empty directory string.
   Anything else is refused with the supported forms named.

   A spec of a type this namespace does not know is refused here rather
   than opened: a storage composition checks its own spec, and reaches
   the engine as a prepared `:index-store` handle, so `create-state`
   never resolves a scheme past the two above
   (docs/design/yin.repl.dht-composition.md sections 2 and 6)."
  [spec]
  (cond
    (nil? spec) :mem
    (= :mem spec) :mem
    (and (map? spec)
         (= :file (:type spec))
         (string? (:dir spec))
         (not (str/blank? (:dir spec))))
    spec

    :else
    (throw (ex-info (str "Unknown :index-store-spec " (pr-str spec)
                         "; supported: :mem, {:type :file :dir <dir>}. "
                         "A store of any other type is opened by its own "
                         "composition, which injects the prepared handle "
                         "as :index-store")
                    {:spec spec
                     :supported [:mem {:type :file :dir "<dir>"}]}))))


(defn open
  "Open the store `spec` names and keep it plain: `:mem` (or nil) is a
   fresh in-memory `dao.jing` store; `{:type :file :dir dir}` is the
   durable directory store at `dir` (dao.space.store/open): exclusive,
   validating, its HEAD writer, recovery and lock on the handle.  A spec
   this build cannot open is refused with its reason.

   The answer is a generic `dao.jing` content handle: whatever a
   composition composes over a store of its own crosses to the engine as
   a prepared `:index-store`, never through here."
  [spec]
  (let [checked (checked-spec spec)]
    (case (if (map? checked) (:type checked) checked)
      :mem (jing.mem/create-content-mem)
      :file (durable/open (:dir checked)))))


(defn durable?
  "True when `store` is a durable directory store: its published index
   outlives the process (dao.space.store/durable?)."
  [store]
  (durable/durable? store))


(defn retain-on-reset?
  "True when a session rebuild — `(reset)` or `(vm ...)` — continues the
   store's published index instead of starting an empty one.

   The policy is declared, not derived: a handle that states
   `:retain-on-reset?` is answered by that statement alone, and only a
   handle that declares nothing falls back to the durable directory
   store's own fact (`durable?`).  A composition that owns a storage
   implementation past the two this namespace opens states the policy
   explicitly when it prepares the handle, so the engine never reaches
   into a backend's private state to choose reset behaviour
   (docs/design/yin.repl.dht-composition.md section 6)."
  [store]
  (if-some [declared (:retain-on-reset? store)]
    (boolean declared)
    (durable? store)))


(defn close!
  "Release the store's lifecycle resources (dao.space.store/close!): the
   content log and, in durable mode, the directory lock.  Idempotent.

   Invocation is the composition's, not the engine's: for an injected
   store the composition that opened it owns closing it, and closes it
   once (docs/design/yin.repl.dht-composition.md sections 3 and 6)."
  [store]
  (durable/close! store))
