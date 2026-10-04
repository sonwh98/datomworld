(ns yang.frontend
  "The language frontend catalog (docs/design/yang.antlr.md section 3,
   section 8.5.6 slice F1): manifest validation, `install`, and `select`.

   A catalog is a plain immutable value. There is no global registry and
   no namespace-load registration: `install` returns a new catalog and
   never changes its argument, so an in-flight request that pinned a
   catalog snapshot keeps its behavior (section 3.3).

   A manifest is portable data only: no functions, host handles, records,
   or other host objects anywhere in it (section 3.1). The installed
   binding is the host's own value, kept beside the manifest and never
   inspected here; its shape is the composition's (slice F2).

   Revisions of one frontend id coexist (section 3.7): an entry is keyed by
   `[id revision]`, and a revision is immutable once installed.

   Every refusal is a returned data outcome,
   `{:status :refused, :reason :yang.frontend/<reason>}` plus its evidence;
   a selection is `{:status :ok, :manifest m, :binding b}`.")


(def supported-spi
  "The SPI revisions (`:yang.frontend/spi`) this catalog admits."
  #{1})


(def refusal-reasons
  "Every reason a catalog refusal may carry."
  #{:yang.frontend/malformed :yang.frontend/non-portable
    :yang.frontend/incompatible-spi :yang.frontend/revision-conflict
    :yang.frontend/unknown :yang.frontend/ambiguous
    :yang.frontend/incompatible})


(defn refused
  "A qualified refusal: `{:status :refused, :reason reason}` merged with
   the evidence `data`."
  ([reason] (refused reason {}))
  ([reason data]
   (merge data {:status :refused, :reason reason})))


(defn refused?
  "True when `res` is a catalog refusal."
  [res]
  (and (map? res) (= :refused (:status res))))


(defn ok?
  "True when `res` is a successful selection."
  [res]
  (and (map? res) (= :ok (:status res))))


;; =============================================================================
;; Manifest validation (sections 3.1, 3.7)
;; =============================================================================

(defn- non-portable-path
  "The path to the first value in `x` that is not portable data, or nil.
   A set member has no stable position, so its path ends at the set, and a
   map key's path ends at its map. With several defects the first reported
   follows host iteration order."
  [x path]
  (cond (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
            (number? x) (char? x))
        nil
        (record? x) path
        (map? x) (some (fn [[k v]]
                         (or (non-portable-path k path)
                             (non-portable-path v (conj path k))))
                       x)
        (or (vector? x) (seq? x))
        (some (fn [[i v]] (non-portable-path v (conj path i)))
              (map-indexed vector x))
        (set? x) (when (some #(non-portable-path % path) x) path)
        :else path))


(defn- address?
  "A content address: a `:segment/...` key or a non-empty string."
  [x]
  (or (keyword? x) (and (string? x) (seq x))))


(def ^:private manifest-checks
  "`[key valid?]` for every key section 3.7 pins per request. The spi is
   `int?`, which on JavaScript also admits an integral float such as 1.0."
  [[:yang.frontend/id qualified-keyword?]
   [:yang.frontend/spi int?]
   [:yang.frontend/revision #(and (string? %) (seq %))]
   [:yang.frontend/language keyword?]
   [:yang.frontend/grammar map?]
   [:yang.frontend/options-schema address?]
   [:yang.frontend/lowering-profile address?]
   [:yang.frontend/runtime-profile address?]
   [:yang.frontend/support-profile address?]])


(def ^:private grammar-checks
  "`[key valid?]` for the grammar package. The lexer and parser names are
   ANTLR's and optional, but a non-empty string when present: the SPI does
   not require ANTLR (section 3.6)."
  [[:yang.grammar/package address?]
   [:yang.grammar/entries #(and (map? %) (seq %))]
   [:yang.grammar/export-profile keyword?]])


(defn- optional-name?
  [m k]
  (or (not (contains? m k))
      (let [v (get m k)] (and (string? v) (seq v)))))


(defn- first-defect
  [m checks path]
  (some (fn [[k valid?]]
          (let [v (get m k)]
            (when-not (valid? v)
              (refused :yang.frontend/malformed
                       {:path (conj path k),
                        :defect (if (contains? m k) :invalid :missing)}))))
        checks))


(defn validate
  "`{:status :ok, :manifest m}` when `m` is a portable, versioned manifest
   this catalog admits, else the refusal naming the first defect."
  [m]
  (if-not (map? m)
    (refused :yang.frontend/malformed {:path [], :defect :invalid})
    (if-let [path (non-portable-path m [])]
      (refused :yang.frontend/non-portable {:path path})
      (or (first-defect m manifest-checks [])
          (first-defect (:yang.frontend/grammar m)
                        grammar-checks
                        [:yang.frontend/grammar])
          (some (fn [k]
                  (when-not (optional-name? (:yang.frontend/grammar m) k)
                    (refused :yang.frontend/malformed
                             {:path [:yang.frontend/grammar k],
                              :defect :invalid})))
                [:yang.grammar/lexer :yang.grammar/parser])
          (when-not (contains? supported-spi (:yang.frontend/spi m))
            (refused :yang.frontend/incompatible-spi
                     {:spi (:yang.frontend/spi m),
                      :supported supported-spi}))
          {:status :ok, :manifest m}))))


;; =============================================================================
;; Catalog (section 3.3)
;; =============================================================================

(def empty-catalog
  "The catalog with no frontend installed."
  {:yang.frontend/entries {}})


(defn- entry-key
  [m]
  [(:yang.frontend/id m) (:yang.frontend/revision m)])


(defn install
  "`catalog'`: `catalog` plus `manifest` bound to the host's
   `binding`, or the refusal when `manifest` is invalid or its
   `[id revision]` is already installed with a different manifest or
   binding. Re-installing an identical entry is the identity."
  [catalog manifest binding]
  (let [res (validate manifest)]
    (if (refused? res)
      res
      (let [k (entry-key manifest)
            entry {:manifest manifest, :binding binding}
            prior (get-in catalog [:yang.frontend/entries k])]
        (cond (nil? prior) (assoc-in catalog [:yang.frontend/entries k] entry)
              (= prior entry) catalog
              :else (refused :yang.frontend/revision-conflict
                             {:id (first k), :revision (second k)}))))))


(defn revisions
  "The installed revisions of frontend `id`, sorted."
  [catalog id]
  (->> (keys (:yang.frontend/entries catalog))
       (keep (fn [[i r]] (when (= i id) r)))
       sort
       vec))


(defn select
  "Select an installed frontend by `request`: `:yang.frontend/id`, and
   optionally `:yang.frontend/revision` and any other manifest key
   section 3.7 pins (SPI, language, grammar, profiles). Answers
   `{:status :ok, :manifest m, :binding b}`, or a refusal:
   `:yang.frontend/unknown` when no revision of the id (or the named
   revision) is installed, `:yang.frontend/incompatible` when installed
   candidates exist but none matches the pins, and
   `:yang.frontend/ambiguous` with the matching `:revisions` when more
   than one matches: the catalog never silently chooses (section 3.3)."
  [catalog request]
  (let [id (:yang.frontend/id request)
        revision (:yang.frontend/revision request)
        pins (dissoc request :yang.frontend/id :yang.frontend/revision)
        candidates (cond->> (revisions catalog id)
                     revision (filter #{revision}))
        matching (filter (fn [r]
                           (let [m (get-in catalog
                                           [:yang.frontend/entries [id r]
                                            :manifest])]
                             (= pins (select-keys m (keys pins)))))
                         candidates)]
    (cond (empty? candidates)
          (refused :yang.frontend/unknown {:id id, :revision revision})
          (empty? matching)
          (refused :yang.frontend/incompatible
                   {:id id, :revisions (vec candidates), :pins pins})
          (next matching)
          (refused :yang.frontend/ambiguous {:id id, :revisions (vec matching)})
          :else (let [k [id (first matching)]]
                  (merge {:status :ok}
                         (get-in catalog [:yang.frontend/entries k]))))))
