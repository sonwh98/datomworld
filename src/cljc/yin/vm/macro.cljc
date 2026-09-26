(ns yin.vm.macro
  "The row-native macro expander (`docs/design/yin.vm.macro.md`, Phase 1).

   A process between two media: it observes `program-in` batches
   `[:yin.program/batch trees run-index declaration-rows harvest-rows]`,
   rewrites the selected tree to a fixpoint, and appends one canonical tree
   packet `[root rows]` to `program-out`, with one event row per attempt on
   an optional log medium. Evaluators know nothing of any of this (decision
   1): a macro call is an ordinary `:application` row, macro-ness is an
   occurrence declaration beside the tree, and definitions leave as ordinary
   syntax or as a literal naming the lowered macro.

   Everything here is a value threaded through the step: the macro store,
   attempt counter, batch coordinate, and incarnation token live in `ctx`
   (§3.1). Expansion failure is data (decision 12); a throw is a defect or a
   terminal stream outcome."
  (:refer-clojure :exclude [flush load])
  (:require [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.engine :as engine]))


;; =============================================================================
;; Grammar, rows, and packets (§1)
;; =============================================================================

(def ^:private standin-tag :yin.macro/defined)


(def ^:private working-grammar
  "The canonical §2.3 table plus the one working row the expander interns
   and lowers itself: `[S :yin.macro/defined name k]` (§3.2 step 3). It is
   admitted only in operand and result packets, never in input or output."
  (assoc vm/semantic-bytecode-grammar standin-tag [[:name :sym] [:k :int]]))


(defn- grammar
  [working?]
  (if working? working-grammar vm/semantic-bytecode-grammar))


(defn- row-tag
  [row]
  (nth row 1))


(defn- intern-row
  "The full row for `body`: its content address prepended (§1.1)."
  [body]
  (into [(jing/segment-key body)] body))


(defn- slot-kinds
  "`[[position kind] ...]` for a tag's grammar slots, positions being full-row
   positions (address 0, tag 1, first slot 2)."
  [tag]
  (map-indexed (fn [i [_ kind]] [(+ i 2) kind]) (get working-grammar tag)))


(defn- child-places
  "`[[coordinate child-address] ...]` of one row in grammar order: a `node`
   slot's coordinate is its full-row position, a `nodes` child's is
   `[position i]` (§2.1)."
  [row]
  (mapcat (fn [[pos kind]]
            (case kind
              :node [[pos (nth row pos)]]
              :nodes (map-indexed (fn [i c] [[pos i] c]) (nth row pos))
              nil))
          (slot-kinds (row-tag row))))


(defn- with-child
  "`row`'s body with the child at coordinate `coord` replaced by `address`."
  [row coord address]
  (let [body (subvec row 1)]
    (if (vector? coord)
      (let [[pos i] coord]
        (assoc body (dec pos) (assoc (nth row pos) i address)))
      (assoc body (dec coord) address))))


(defn- reachable
  "Addresses reachable from `root` through `index`, first-encounter preorder
   in grammar order. Assumes the index is closed and acyclic."
  [index root]
  (loop [stack (list root)
         seen #{}
         order []]
    (if-let [a (first stack)]
      (if (contains? seen a)
        (recur (rest stack) seen order)
        (recur (reduce conj (rest stack) (reverse (map second (child-places (get index a)))))
               (conj seen a)
               (conj order a)))
      order)))


(defn- packet-of
  "The self-contained tree packet `[root rows]` rooted at `root` (§1.2). Row
   order is preorder, which is deterministic but carries no meaning."
  [index root]
  [root (mapv #(get index %) (reachable index root))])


(defn tree-packet
  "Convert a projected row set `{:root id :rows {id row}}` (the shape
   `yin.vm/ast->semantic-bytecode` returns) to a tree packet `[root rows]`."
  [{:keys [root rows]}]
  (packet-of rows root))


(defn packet->row-set
  "Convert a canonical tree packet to `{:root id :rows {id row}}`, the shape
   the evaluator loaders take."
  [[root rows]]
  {:root root, :rows (into {} (map (fn [r] [(first r) r])) rows)})


(defn ast->packet
  "Project a map AST to its canonical tree packet."
  [ast]
  (tree-packet (vm/ast->semantic-bytecode ast)))


(defn- same-value?
  "`=` that also compares metadata at every depth: the transitional address
   encoder does not hash scalar metadata, so two bodies may share an address
   while differing there, which is an address conflict (§5.3, §7.2)."
  [a b]
  (and (= a b)
       (= (meta a) (meta b))
       (or (nil? (meta a)) (same-value? (meta a) (meta b)))
       (cond (map? a) (every? (fn [[k v]]
                                (let [[k' v'] (find b k)]
                                  (and (same-value? k k') (same-value? v v'))))
                              a)
             (set? a) (every? (fn [e] (same-value? e (some #(when (= e %) %) b))) a)
             (sequential? a) (every? true? (map same-value? a b))
             :else true)))


;; =============================================================================
;; Packet validation (§7.1, §7.2)
;; =============================================================================

(defn- standin-shaped?
  "A vector that reads as a working stand-in row or body."
  [x]
  (and (vector? x)
       (or (= standin-tag (first x))
           (and (< 1 (count x)) (= standin-tag (nth x 1))))))


(defn- marker-in?
  "True when a stand-in-shaped vector appears anywhere inside a value."
  [x]
  (or (standin-shaped? x)
      (and (map? x) (some (fn [[k v]] (or (marker-in? k) (marker-in? v))) x))
      (and (coll? x) (not (map? x)) (some marker-in? x))
      (and (some? (meta x)) (marker-in? (meta x)))))


(defn- slot-shape-ok?
  "Slot kind check. `data`/`key` admit any value here; plain-ness is the
   separate `:host-value` rule."
  [kind v]
  (case kind
    :node (jing/segment-address? v)
    :nodes (and (vector? v) (every? jing/segment-address? v))
    (:data :key) true
    :sym (symbol? v)
    :syms (and (vector? v) (every? symbol? v))
    :kw (keyword? v)
    :str (string? v)
    :int (and (integer? v) (not (neg? v)))
    :bool (or (true? v) (false? v))))


(defn- value-slots
  [row]
  (keep (fn [[pos kind]] (when (contains? #{:data :key} kind) (nth row pos)))
        (slot-kinds (row-tag row))))


(defn- first-defect
  "Run `check` over `addresses` in order; the first non-nil result."
  [check addresses]
  (some check addresses))


(defn- cycle-defect
  [index addresses]
  (letfn [(visit
            [a on-path path done]
            (cond (contains? on-path a) [{:reason :cyclic, :address a, :path path} done]
                  (contains? done a) [nil done]
                  :else (let [[err done']
                              (reduce (fn [[_ d] [coord c]]
                                        (let [[e d'] (visit c (conj on-path a)
                                                            (conj path coord) d)]
                                          (if e (reduced [e d']) [nil d'])))
                                      [nil done]
                                      (child-places (get index a)))]
                          [err (conj done' a)])))]
    (loop [as addresses
           done #{}]
      (when-let [a (first as)]
        (let [[err done'] (visit a #{} [] done)]
          (or err (recur (rest as) done')))))))


(defn valid-tree?
  "Validate a tree packet `[root rows]` (§1.2, §7.1). Returns nil when the
   packet is a closed, self-contained, acyclic tree of well-formed rows whose
   addresses match their bodies, else the first error as
   `{:kind :malformed-input :reason r :address a ...}`.

   Rules run in this order, each over addresses in canonical byte order and
   slots in grammar order: packet and row shape (`:packet-shape`,
   `:row-shape`), `:unknown-tag`, `:row-shape` (arity and slot kinds),
   `:host-value`, `:marker-in-payload`, `:address-mismatch` (after the plain
   check, so no host value is ever hashed), `:address-conflict` (one address,
   two metadata-distinct bodies), `:dangling-child`, `:cyclic`, and
   `:unreachable-row`.

   `opts`: `:working?` admits working stand-in rows (operand and result
   packets); canonical input and output never do."
  ([packet] (valid-tree? packet {}))
  ([packet {:keys [working?]}]
   (let [g (grammar working?)
         err (fn [reason & kvs]
               (merge {:kind :malformed-input, :reason reason}
                      (apply hash-map kvs)))]
     (if-not (and (vector? packet) (= 2 (count packet))
                  (jing/segment-address? (nth packet 0))
                  (sequential? (nth packet 1)))
       (err :packet-shape)
       (let [[root rows] packet]
         (or
           (some (fn [row]
                   (when-not (and (vector? row) (<= 2 (count row))
                                  (jing/segment-address? (nth row 0)))
                     (err :row-shape :address (when (vector? row) (first row)))))
                 rows)
           (let [by-addr (group-by first rows)
                 addresses (sort-by name (keys by-addr))
                 index (into {} (map (fn [[a rs]] [a (first rs)])) by-addr)]
             (or
               (first-defect (fn [a]
                               (when-not (contains? g (row-tag (get index a)))
                                 (err :unknown-tag :address a)))
                             addresses)
               (first-defect
                 (fn [a]
                   (let [row (get index a)
                         slots (get g (row-tag row))]
                     (when-not (and (= (count row) (+ 2 (count slots)))
                                    (every? (fn [[i [_ kind]]]
                                              (slot-shape-ok? kind (nth row (+ i 2))))
                                            (map-indexed vector slots)))
                       (err :row-shape :address a))))
                 addresses)
               (first-defect (fn [a]
                               (when-not (every? vm/plain-data? (get by-addr a))
                                 (err :host-value :address a)))
                             addresses)
               (first-defect (fn [a]
                               (when (some marker-in? (value-slots (get index a)))
                                 (err :marker-in-payload :address a)))
                             addresses)
               (first-defect (fn [a]
                               (when-not (every? (fn [row]
                                                   (jing/segment-matches?
                                                     a
                                                     (subvec row 1)))
                                                 (get by-addr a))
                                 (err :address-mismatch :address a)))
                             addresses)
               (first-defect (fn [a]
                               (let [[r & more] (get by-addr a)]
                                 (when-not (every? #(same-value? r %) more)
                                   (err :address-conflict :address a))))
                             addresses)
               (when-not (contains? index root)
                 (err :dangling-child :address nil :child root))
               (first-defect (fn [a]
                               (some (fn [[_ c]]
                                       (when-not (contains? index c)
                                         (err :dangling-child :address a :child c)))
                                     (child-places (get index a))))
                             addresses)
               (when-let [{:keys [address path]} (cycle-defect index (cons root addresses))]
                 (err :cyclic :address address :path path))
               (let [seen (set (reachable index root))]
                 (first-defect (fn [a]
                                 (when-not (contains? seen a)
                                   (err :unreachable-row :address a)))
                               addresses))))))))))


(defn- packet-index
  [[_ rows]]
  (into {} (map (fn [r] [(first r) r])) rows))


;; =============================================================================
;; Paths and definitions (§2.1, §2.2)
;; =============================================================================

(defn- step-path
  "The child address at one path coordinate of `row`, or nil when the
   coordinate names no `node`/`nodes` child of it."
  [row coord]
  (let [kinds (into {} (slot-kinds (row-tag row)))]
    (cond (integer? coord)
          (when (= :node (get kinds coord)) (nth row coord))

          (and (vector? coord) (= 2 (count coord)))
          (let [[pos i] coord]
            (when (and (integer? pos) (integer? i) (= :nodes (get kinds pos)))
              (get (nth row pos) i)))

          :else nil)))


(defn- resolve-path
  "The address at `path` from `root`, or nil when the path does not resolve."
  [index root path]
  (when (vector? path)
    (reduce (fn [a coord]
              (or (step-path (get index a) coord) (reduced nil)))
            root
            path)))


(defn- definition-at
  "`{:name sym :value address}` when the row at `address` is the §2.2 shape
   `(yin/def <literal-symbol> value)`, else nil."
  [index address]
  (let [row (get index address)]
    (when (= :application (row-tag row))
      (let [op (get index (nth row 2))
            operands (nth row 3)]
        (when (and (= [:variable 'yin/def] (subvec op 1))
                   (= 2 (count operands)))
          (let [name-row (get index (first operands))]
            (when (and (= :literal (row-tag name-row))
                       (symbol? (nth name-row 2)))
              {:name (nth name-row 2), :value (second operands)})))))))


(defn- definition-paths
  "Every definition occurrence path of one tree, in preorder. Paths, not
   addresses: a shared definition row at two places is two occurrences."
  [index root]
  (letfn [(walk
            [a path]
            (concat (when (definition-at index a) [path])
                    (mapcat (fn [[coord c]] (walk c (conj path coord)))
                            (child-places (get index a)))))]
    (vec (walk root []))))


;; =============================================================================
;; Admission (§2.1, §7.1)
;; =============================================================================

(defn- malformed
  [reason & kvs]
  (merge {:kind :malformed-input, :reason reason} (apply hash-map kvs)))


(defn- batch-shape-ok?
  [batch]
  (and (vector? batch)
       (= 5 (count batch))
       (= :yin.program/batch (nth batch 0))
       (let [[_ trees run-index decls harvest] batch]
         (and (vector? trees) (seq trees)
              (integer? run-index) (<= 0 run-index) (< run-index (count trees))
              (vector? decls)
              (every? #(and (vector? %) (= 3 (count %))
                            (= :yin.macro/definition (first %)))
                      decls)
              (vector? harvest)
              (every? #(and (vector? %) (= 4 (count %))
                            (= :yin.macro/harvest (first %)))
                      harvest)))))


(defn- merge-indexes
  "Union the trees' indexes into one working index, verifying that one
   address denotes one body across trees (each tree is already internally
   consistent). `{:index m}`, or `{:error e}` naming the first later tree, in
   tree order and then canonical address order, whose row is
   metadata-distinct from an earlier tree's row at the same address."
  [indexes]
  (reduce (fn [{:keys [index]} [j tree-index]]
            (or (some (fn [a]
                        (let [prior (get index a)]
                          (when (and prior (not (same-value? prior (get tree-index a))))
                            (reduced {:error (malformed :address-conflict
                                                        :tree j :address a)}))))
                      (sort-by name (keys tree-index)))
                {:index (merge index tree-index)}))
          {:index {}}
          (map-indexed vector indexes)))


(defn- admit
  "Admit one batch: `{:admitted {...}}` or `{:error error-data}`. The first
   error is deterministic: batch shape, then trees by index, then
   cross-tree address conflicts, then catalogue problems by ordinal, then
   declarations in batch order."
  [batch]
  (if-not (batch-shape-ok? batch)
    {:error (malformed :batch-shape)}
    (let [[_ trees run-index decls harvest] batch]
      (or
        (some (fn [[j tree]]
                (when-let [e (valid-tree? tree)]
                  {:error (assoc e :tree j)}))
              (map-indexed vector trees))
        (let [indexes (mapv packet-index trees)
              merged (merge-indexes indexes)
              roots (mapv first trees)
              catalogue-error (fn [h & kvs] {:error (apply malformed :harvest-catalogue :ordinal h kvs)})
              entries
              (reduce
                (fn [acc [i [_ h j path]]]
                  (let [a (when (and (integer? j) (< -1 j (count trees)))
                            (resolve-path (nth indexes j) (nth roots j) path))
                        d (when a (definition-at (nth indexes j) a))]
                    (cond (not= i h) (reduced (catalogue-error h))
                          (nil? d) (reduced (catalogue-error h :tree j :path path))
                          (contains? (:seen acc) [j path])
                          (reduced (catalogue-error h :tree j :path path))
                          :else (-> acc
                                    (update :seen conj [j path])
                                    (update :entries conj
                                            (assoc d :ordinal h, :tree j, :path path
                                                   :address a))))))
                {:seen #{}, :entries []}
                (map-indexed vector harvest))]
          (cond
            (:error merged) merged

            (:error entries) entries

            :else
            (let [{:keys [seen entries]} entries]
              (or
                (some (fn [j]
                        (some (fn [p]
                                (when-not (contains? seen [j p])
                                  (catalogue-error nil :tree j :path p)))
                              (definition-paths (nth indexes j) (nth roots j))))
                      (range (count trees)))
                (let [by-occurrence (into {} (map (fn [e] [[(:tree e) (:path e)] e])) entries)]
                  (or
                    (some (fn [[_ j path]]
                            (let [e (get by-occurrence [j path])]
                              (when-not (and e (= :lambda (row-tag (get (nth indexes j)
                                                                        (:value e)))))
                                {:error (malformed :stray-macro-declaration :tree j :path path)})))
                          decls)
                    (let [declared (set (map (fn [[_ j path]] [j path]) decls))]
                      {:admitted
                       {:trees trees,
                        :indexes indexes,
                        :index (:index merged),
                        :run-index run-index,
                        :definitions (mapv #(assoc % :declared?
                                                   (contains? declared [(:tree %) (:path %)]))
                                           entries)}})))))))))))


(defn definitions
  "The ordered definition records of a batch, in harvest order:
   `{:ordinal h :tree j :path p :address a :name sym :value v :declared? b}`.
   Throws `ex-info` carrying the admission error when the batch is invalid."
  [batch]
  (let [{:keys [admitted error]} (admit batch)]
    (when error
      (throw (ex-info "Invalid macro input batch" error)))
    (:definitions admitted)))


;; =============================================================================
;; Workspace: interning and immutable path rebuilding (§3.3)
;; =============================================================================

(defn- intern-body
  "Intern `body` into the working index: `[index' address]`. An address
   already holding a different body is a defect of the encoder, not input."
  [index body]
  (let [row (intern-row body)
        a (first row)
        prior (get index a)]
    (when (and prior (not (same-value? prior row)))
      (throw (ex-info "Working index address conflict" {:address a})))
    [(if prior index (assoc index a row)) a]))


(defn- rebuild-paths
  "Replace the occurrences at every path in `replacements` (`{path address}`)
   below `root`, interning each changed ancestor once: replacements below one
   parent are combined before the parent is interned. Shallower replacements
   subsume deeper ones, which is the result of applying them deepest path
   first. Content sharing never merges occurrences: only the named paths'
   ancestor chains change. Returns `[index' root']`."
  [index root replacements]
  (letfn [(go
            [index a reps]
            (if-let [[_ r] (find reps [])]
              [index r]
              (let [by-coord (group-by (comp first key) reps)
                    row (get index a)
                    [index row']
                    (reduce (fn [[index row'] coord]
                              (let [child (step-path row coord)
                                    sub (into {} (map (fn [[p r]] [(subvec p 1) r]))
                                              (get by-coord coord))
                                    [index c'] (go index child sub)]
                                [index (into [a] (with-child row' coord c'))]))
                            [index row]
                            ;; deterministic order; the result is order-free
                            (sort-by pr-str (keys by-coord)))]
                (if (= row' row)
                  [index a]
                  (intern-body index (subvec row' 1))))))]
    (go index root replacements)))


(defn- rebuild-row
  "Intern `row` with the children at `places` (a subsequence of its
   `child-places`, all of them by default) replaced by `children'`, in
   order; `[index address]`, unchanged when no child changed."
  ([index row children'] (rebuild-row index row (child-places row) children'))
  ([index row places children']
   (if (= (map second places) children')
     [index (first row)]
     (intern-body index (subvec (reduce (fn [r [[coord _] c]]
                                          (into [(first r)] (with-child r coord c)))
                                        row
                                        (map vector places children'))
                                1)))))


;; =============================================================================
;; Bounded body runner and row-native prelude (§5)
;; =============================================================================

(defn- throw-prelude
  [msg data]
  (throw (ex-info msg (assoc data :yin.macro/prelude true))))


(defn- packet?
  [x]
  (and (vector? x) (= 2 (count x)) (jing/segment-address? (first x))
       (vector? (second x))))


(defn- need-packet
  [who x]
  (when-not (packet? x)
    (throw-prelude (str who " expects a tree packet") {:op who}))
  x)


(defn- merge-packets
  "Union the rows of `packets` into one index, verifying that one address
   denotes one body (§5.3)."
  [packets]
  (reduce (fn [index row]
            (let [prior (get index (first row))]
              (cond (nil? prior) (assoc index (first row) row)
                    (same-value? prior row) index
                    :else (throw-prelude "Packet address conflict"
                                         {:address (first row)}))))
          {}
          (mapcat second packets)))


(defn- construct
  "Intern `body` over the rows of its child `packets`; a closed packet."
  [body packets]
  (let [index (merge-packets packets)
        [index a] (intern-body index body)]
    (packet-of index a)))


(defn- root-row
  [[root rows]]
  (some #(when (= root (first %)) %) rows))


(defn- literal-packet
  [v]
  (construct [:literal v] []))


(defn- application-packet
  [operator operands]
  (construct [:application (first operator) (mapv first operands) false]
             (cons operator operands)))


(defn- lambda-packet
  [params body]
  (when-not (and (sequential? params) (every? symbol? params))
    (throw-prelude "yin/lambda params must be symbols" {:op 'yin/lambda}))
  (construct [:lambda (vec params) (first body)] [body]))


(defn- name-of
  [tree]
  (let [row (root-row (need-packet 'yin/name-of tree))
        v (nth row 2 nil)]
    (cond (= :variable (row-tag row)) v
          (and (= :literal (row-tag row)) (symbol? v)) v
          (and (= :literal (row-tag row)) (string? v)) (symbol v)
          :else (throw-prelude "yin/name-of reads a variable, literal symbol, or literal string"
                               {:op 'yin/name-of}))))


(defn- sequence-body
  "The reference `do` lowering: `(do a b c)` is `((fn [_] ((fn [_] c) b)) a)`;
   one form is itself and none is the literal nil."
  [trees]
  (let [trees (vec trees)]
    (doseq [t trees] (need-packet 'yin/sequence-body t))
    (case (count trees)
      0 (literal-packet nil)
      1 (first trees)
      (application-packet (lambda-packet ['_] (sequence-body (subvec trees 1)))
                          [(first trees)]))))


(defn- slot-of
  [tree n]
  (let [row (root-row (need-packet 'yin/slot tree))
        kinds (into {} (slot-kinds (row-tag row)))
        index (packet-index tree)]
    (when-not (and (integer? n) (contains? kinds n))
      (throw-prelude "yin/slot position names no grammar slot"
                     {:op 'yin/slot, :position n}))
    (case (get kinds n)
      :node (packet-of index (nth row n))
      :nodes (mapv #(packet-of index %) (nth row n))
      (nth row n))))


(defn prelude
  "The closed body environment (§5.3): the pure standard primitives (no
   `require`, no stream or FFI constructor) and the row-native constructors,
   each consuming and returning self-contained tree packets. `gensym` is the
   invocation-local `yin/gensym-sym`."
  [gensym]
  (merge
    (dissoc vm/primitives 'require 'bytes->str)
    {'yin/tag (fn [tree] (row-tag (root-row (need-packet 'yin/tag tree)))),
     'yin/slot slot-of,
     'yin/literal literal-packet,
     'yin/variable (fn [sym]
                     (when-not (symbol? sym)
                       (throw-prelude "yin/variable expects a symbol" {:op 'yin/variable}))
                     (construct [:variable sym] [])),
     'yin/lambda (fn [params body] (lambda-packet params (need-packet 'yin/lambda body))),
     'yin/application (fn [operator operands]
                        (need-packet 'yin/application operator)
                        (doseq [o operands] (need-packet 'yin/application o))
                        (application-packet operator (vec operands))),
     'yin/if (fn [t c a]
               (doseq [x [t c a]] (need-packet 'yin/if x))
               (construct [:if (first t) (first c) (first a)] [t c a])),
     'yin/sequence-body sequence-body,
     'yin/make-lambda (fn [params-tree body]
                        (let [row (root-row (need-packet 'yin/make-lambda params-tree))]
                          (when-not (and (= :literal (row-tag row))
                                         (vector? (nth row 2))
                                         (every? symbol? (nth row 2)))
                            (throw-prelude "yin/make-lambda reads a literal parameter vector"
                                           {:op 'yin/make-lambda}))
                          (lambda-packet (nth row 2) (need-packet 'yin/make-lambda body)))),
     'yin/make-def (fn [name-tree value-tree]
                     (application-packet (construct [:variable 'yin/def] [])
                                         [(literal-packet (name-of name-tree))
                                          (need-packet 'yin/make-def value-tree)])),
     'yin/name-of name-of,
     'yin/gensym-sym gensym}))


(def ^:private suspending-tags
  #{:vm/park :vm/resume :dao.stream.apply/call})


(def ^:private effect-tags
  #{:stream/make :stream/put :stream/cursor :stream/next :stream/close})


(defn- catch-message
  [e]
  (or (ex-message e) (str e)))


(defn bounded-row-evaluator
  "The default body runner (§5.2): a fresh throwaway ast-walker loaded from
   the lambda body's rows, its parameters bound in `env`, the closed
   `prelude` as its only primitives, no store, no module resolver, no stream
   constructor, and no FFI bridge. It counts VM transitions.

   Returns `{:value v}`, or `{:error e}` with `e` one of
   `{:kind :fuel-guard :steps n}`, `{:kind :suspended}` (a park, resume,
   bridge call, blocked read, or work scheduled outside the body
   continuation), `{:kind :effect-guard :tag t}` (a stream constructor), or
   `{:kind :body-error :message s}` (the body threw).

   `contract` is the packet's own AST stamp, carried from its store entry
   by `invoke`, which has verified it; the body rows load under that stamp,
   never one this runner supplies, and a missing or old stamp is refused
   (thrown) before anything runs."
  [{:keys [macro-tree contract env prelude max-steps]}]
  (vm/check-contract! vm/ast-contract contract)
  (let [index (packet-index macro-tree)
        body (nth (get index (first macro-tree)) 3)
        body-set {:root body, :rows (into {} (map (fn [a] [a (get index a)]))
                                          (reachable index body))}]
    (try
      (loop [v (ast-walker/vm-load-rows
                 (ast-walker/create-vm {:env env, :primitives prelude,
                                        :primitive-profiles {}})
                 body-set
                 contract)
             steps 0]
        (let [tag (:type (:control v))]
          (cond (or (:blocked? v) (seq (:wait-set v)) (seq (:parked v)))
                {:error {:kind :suspended}}

                (engine/halted-with-empty-queue? v) {:value (:value v)}

                (:halted? v) {:error {:kind :suspended}}

                (>= steps max-steps) {:error {:kind :fuel-guard, :steps steps}}

                (contains? suspending-tags tag) {:error {:kind :suspended}}

                (contains? effect-tags tag) {:error {:kind :effect-guard, :tag tag}}

                :else (recur (vm/step v) (inc steps)))))
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        {:error {:kind :body-error, :message (catch-message e)}}))))


(defn- bind-arguments
  "Positional binding of operand packets to lambda parameters; `[a & rest]`
   binds `rest` to the vector of remaining packets. `{:env m}` or
   `{:expected n :got m}`."
  [params args]
  (let [[fixed [amp rest-sym]] (split-with #(not= '& %) params)
        fixed (vec fixed)
        n (count fixed)
        m (count args)]
    (if amp
      (if (< m n)
        {:expected n, :got m}
        {:env (assoc (zipmap fixed args) rest-sym (vec (drop n args)))})
      (if (not= m n)
        {:expected n, :got m}
        {:env (zipmap fixed args)}))))


(defn- attempt-id
  [ctx counter]
  [(get-in ctx [:incarnation :yin.expander/token]) counter])


(defn macro-entry
  "A macro-store value: a self-contained lambda tree `packet` with the AST
   `contract` it was produced under. The store holds only these. The
   transformer runner verifies the contract before it executes the packet
   (Rule R: a persistent or supplied code packet is never relabelled), and
   the expander stamps only what it harvests itself, under
   `vm/ast-contract`."
  [packet contract]
  {:yin.macro/tree packet, :yin.macro/contract contract})


(defn- verified-tree
  "The packet of a macro-store value after its contract is verified
   against `vm/ast-contract`: `:contract-missing` for a bare packet or an
   entry without one, `:contract-mismatch` for an old stamp."
  [entry]
  (vm/check-contract! vm/ast-contract
                      (when (map? entry) (:yin.macro/contract entry)))
  (:yin.macro/tree entry))


(defn- check-store!
  "A supplied macro store: no reserved name is a macro (Rule R), and every
   entry carries the current AST contract. Returns `store`."
  [store]
  (vm/check-bindings! :macro store)
  (doseq [entry (vals store)] (verified-tree entry))
  store)


(defn- invoke*
  "Run one macro body. `entry` is a macro-store value (`macro-entry`),
   verified before anything runs. `counter` is the attempt counter, which
   gensyms read. `{:tree packet}` or `{:error e}`."
  [entry operands ctx counter]
  (let [macro-tree (verified-tree entry)
        macro-root (first macro-tree)
        params (nth (root-row macro-tree) 2)
        {:keys [env expected got]} (bind-arguments params operands)]
    (if-not env
      {:error {:kind :arity, :macro macro-root, :expected expected, :got got}}
      (let [n (volatile! 0)
            token (get-in ctx [:incarnation :yin.expander/token])
            gensym (fn [prefix]
                     (let [i @n]
                       (vswap! n inc)
                       (symbol (str (if (symbol? prefix) (name prefix) prefix)
                                    "__" token "_" counter "_" i))))
            run (or (:eval ctx) bounded-row-evaluator)
            {:keys [value error]} (run {:macro-tree macro-tree,
                                        :contract (:yin.macro/contract entry),
                                        :env env,
                                        :prelude (prelude gensym),
                                        :max-steps (get-in ctx [:guards :max-steps])})]
        (if error
          {:error (assoc error :macro macro-root)}
          {:tree value})))))


(def default-guards
  {:max-depth 100,
   :max-rows-per-expansion 10000,
   :max-rows-per-batch nil,
   :max-steps 100000})


(defn invoke
  "Run `entry`, a macro-store value (`macro-entry`: a self-contained lambda
   packet and its AST contract), on `operand-trees` and return its result
   tree packet, unvalidated. The contract is verified first
   (`:contract-missing`, `:contract-mismatch`, thrown). Gensyms use
   `(:attempt ctx)` as the attempt counter. Throws `ex-info` carrying the
   error data on an arity, guard, suspension, or body failure."
  [entry operand-trees ctx]
  (let [ctx (update ctx :guards #(merge default-guards %))
        {:keys [tree error]} (invoke* entry (vec operand-trees) ctx
                                      (:attempt ctx))]
    (when error
      (throw (ex-info "Macro invocation failed" error)))
    tree))


;; =============================================================================
;; Tail marking and lowering (§6, §3.2 steps 6-7)
;; =============================================================================

(defn- retail
  "Recompute every `tail?` slot below `root`, in context `tail?`, over a
   working index; `[index' root']`. Tail-ness is contextual, so one shared
   row in two contexts becomes two rows."
  [index root]
  (let [memo (volatile! {})]
    (letfn [(go
              [index a tail?]
              (if-let [hit (get @memo [a tail?])]
                [index hit]
                (let [row (get index a)
                      tag (row-tag row)
                      ctxs (case tag
                             :lambda [true]
                             :if [false tail? tail?]
                             (repeat false))
                      [index children']
                      (reduce (fn [[index acc] [[_ c] t]]
                                (let [[index c'] (go index c t)]
                                  [index (conj acc c')]))
                              [index []]
                              (map vector (child-places row) ctxs))
                      [index a'] (rebuild-row index row children')
                      [index a'] (if (and (= :application tag)
                                          (not= tail? (nth (get index a') 4)))
                                   (intern-body index (assoc (subvec (get index a') 1) 3 tail?))
                                   [index a'])]
                  (vswap! memo assoc [a tail?] a')
                  [index a'])))]
      (go index root true))))


(defn mark-tail
  "Recompute all observable tail slots of a tree packet, setting and
   clearing marks (§6): the root is in tail context, an `:if` test is
   non-tail and its branches inherit, application operators and operands are
   non-tail, a lambda body is tail, and every other child is non-tail."
  [packet]
  (let [[index root] (retail (packet-index packet) (first packet))]
    (packet-of index root)))


(defn- lower-standins
  "Replace every stand-in by the canonical literal of its name."
  [index root]
  (let [memo (volatile! {})]
    (letfn [(go
              [index a]
              (if-let [hit (get @memo a)]
                [index hit]
                (let [row (get index a)
                      [index a']
                      (if (= standin-tag (row-tag row))
                        (intern-body index [:literal (nth row 2)])
                        (let [[index children']
                              (reduce (fn [[index acc] [_ c]]
                                        (let [[index c'] (go index c)]
                                          [index (conj acc c')]))
                                      [index []]
                                      (child-places row))]
                          (rebuild-row index row children')))]
                  (vswap! memo assoc a a')
                  [index a'])))]
      (go index root))))


;; =============================================================================
;; Harvest and post-harvest (§3.2 steps 2, 3, 5; §3.5)
;; =============================================================================

(defn- harvest
  "Initial harvest: iterate definitions in ordinal order; a declared one
   installs its lambda packet, a plain one removes the name. Last wins.
   A reserved name is never a definition key or a macro name (Rule R)."
  [store index-of definitions]
  (reduce (fn [store {:keys [tree name value declared?]}]
            (when (vm/reserved-name? name)
              (vm/refuse-reserved! :definition-key name))
            (if declared?
              (assoc store name
                     (macro-entry (packet-of (index-of tree) value)
                                  vm/ast-contract))
              (dissoc store name)))
          store
          definitions))


(defn- post-harvest
  "The next batch's store: start from the store before initial harvest and
   walk the final occurrence tree in declaration order — for an application
   whose operator is a lambda, operands left to right then the lambda body;
   otherwise child slots in grammar order. A plain definition removes its
   name; a stand-in whose `k` exists and whose catalogue name matches
   installs that entry; later occurrences win."
  [store index root catalogue]
  (letfn [(children
            [row]
            (let [places (child-places row)]
              (if (and (= :application (row-tag row))
                       (= :lambda (row-tag (get index (nth row 2)))))
                (concat (map second (rest places))
                        [(nth (get index (nth row 2)) 3)])
                (map second places))))
          (walk
            [store a]
            (let [row (get index a)
                  store (cond
                          (= standin-tag (row-tag row))
                          (let [[_ _ nm k] row
                                entry (get catalogue k)]
                            (if (and entry (= nm (:name entry)))
                              (assoc store nm
                                     (macro-entry (:macro-tree entry)
                                                  vm/ast-contract))
                              store))

                          :else
                          (if-let [{:keys [name]} (definition-at index a)]
                            (dissoc store name)
                            store))]
              (reduce walk store (children row))))]
    (walk store root)))


;; =============================================================================
;; Events (§8)
;; =============================================================================

(defn- event-row
  [attempt source-batch origin parent input-root call-path macro-root output-root error]
  (intern-row [:yin.macro/expand attempt source-batch origin parent input-root
               call-path macro-root output-root error]))


(defn- source-origin
  [ctx source-batch tree-index]
  [:source (:source-medium ctx) source-batch tree-index])


;; =============================================================================
;; Expansion (§4)
;; =============================================================================

(defn- fail
  [st error]
  (assoc st :error error))


(defn- allocate-attempt
  [st]
  (let [counter (get-in st [:ctx :attempt])]
    [(update-in st [:ctx :attempt] inc) counter]))


(defn- macro-of
  "The stored macro packet for an operator occurrence: a `:variable` naming
   a store entry that no enclosing lambda parameter shadows."
  [st operator shadow]
  (let [row (get (:index st) operator)]
    (when (and (= :variable (row-tag row))
               (not (contains? shadow (nth row 2))))
      (get (:store st) (nth row 2)))))


(defn- record-event
  [st event-row*]
  (update st :events conj event-row*))


(defn- guard-batch-rows
  [st]
  (let [limit (get-in st [:ctx :guards :max-rows-per-batch])
        n (count (:index st))]
    (when (and limit (> n limit))
      {:kind :row-guard, :scope :batch, :rows n})))


(defn- merge-output
  "Merge a validated result packet into the working index; an address whose
   working body differs is an `:address-conflict`."
  [st [_ rows]]
  (reduce (fn [st row]
            (let [prior (get (:index st) (first row))]
              (cond (nil? prior) (assoc-in st [:index (first row)] row)
                    (same-value? prior row) st
                    :else (reduced (assoc st ::conflict (first row))))))
          st
          rows))


(declare expand-node)


(defn- expand-call
  "One recognized call: allocate an attempt, begin its event before any
   guard or invocation, run the transformer on the operand packets, validate
   and merge the result, finish the event, and reconsider the result at the
   same occurrence with depth + 1."
  [st address entry {:keys [path rel frame shadow depth]}]
  (let [[st counter] (allocate-attempt st)
        ctx (:ctx st)
        macro-root (first (:yin.macro/tree entry))
        begin (fn [output error]
                (event-row (attempt-id ctx counter)
                           (:source-batch st)
                           (when-not (:parent frame)
                             (source-origin ctx (:source-batch st) (:tree-index frame)))
                           (:parent frame)
                           (:root frame)
                           rel
                           macro-root
                           output
                           error))
        failed (fn [st error]
                 (-> st (record-event (begin nil error)) (fail error)))
        guards (:guards ctx)]
    (if (>= depth (:max-depth guards))
      [(failed st {:kind :depth-guard, :depth depth, :path path}) address]
      (let [row (get (:index st) address)
            operands (mapv #(packet-of (:index st) %) (nth row 3))
            {:keys [tree error]} (invoke* entry operands ctx counter)
            invalid (fn [reason]
                      {:kind :invalid-output, :macro macro-root, :path path,
                       :reason reason})]
        (if error
          [(failed st error) address]
          (if-let [e (valid-tree? tree {:working? true})]
            [(failed st (invalid (:reason e))) address]
            (let [n (count (set (map first (second tree))))]
              (if (> n (:max-rows-per-expansion guards))
                [(failed st {:kind :row-guard, :scope :expansion, :rows n}) address]
                (let [st' (merge-output st tree)]
                  (if (::conflict st')
                    [(failed st (invalid :address-conflict)) address]
                    (if-let [e (guard-batch-rows st')]
                      [(failed st' e) address]
                      (let [out (first tree)
                            ev (begin out nil)
                            st' (-> st'
                                    (record-event ev)
                                    (update :produced
                                            (fn [p]
                                              (if (some #(= out (first %)) p)
                                                p
                                                (conj p (packet-of (:index st') out))))))]
                        (expand-node st' out
                                     {:path path,
                                      :rel [],
                                      :frame {:parent (first ev), :root out},
                                      :shadow shadow,
                                      :depth (inc depth)})))))))))))))


(defn- expand-children
  "Expand `row`'s children in order `places` under per-child shadows;
   `[st address']`, rebuilding the row when any child changed."
  [st row places shadow-of {:keys [path rel] :as loc}]
  (let [[st children']
        (reduce (fn [[st acc] [coord c]]
                  (if (:error st)
                    (reduced [st acc])
                    (let [[st c'] (expand-node st c
                                               (assoc loc
                                                      :path (conj path coord)
                                                      :rel (conj rel coord)
                                                      :shadow (shadow-of coord)))]
                      [st (conj acc c')])))
                [st []]
                places)]
    (if (:error st)
      [st (first row)]
      (let [[index a'] (rebuild-row (:index st) row places children')]
        [(assoc st :index index) a']))))


(defn- expand-node
  "§4.2. `loc` carries the absolute `path` in the batch's run tree, the
   `rel` path within the current `frame` (`{:root r :parent event|nil
   :tree-index j}`), the lexical `shadow` set, and the expansion `depth`.
   Returns `[st address']`; a failure sets `(:error st)`."
  [st address {:keys [shadow] :as loc}]
  (if (:error st)
    [st address]
    (let [row (get (:index st) address)
          tag (row-tag row)]
      (cond
        (and (= :application tag) (macro-of st (nth row 2) shadow))
        (expand-call st address (macro-of st (nth row 2) shadow) loc)

        (= :application tag)
        (let [op (nth row 2)
              [st op'] (expand-node st op (-> loc
                                              (update :path conj 2)
                                              (update :rel conj 2)))]
          (if (:error st)
            [st address]
            (let [[index app'] (if (= op op')
                                 [(:index st) address]
                                 (intern-body (:index st) (assoc (subvec row 1) 1 op')))
                  st (assoc st :index index)
                  row' (get index app')]
              (if (macro-of st op' shadow)
                ;; recognition is not expansion: depth is unchanged
                (expand-node st app' loc)
                (expand-children st row' (rest (child-places row'))
                                 (constantly shadow) loc)))))

        :else
        (let [shadow' (if (= :lambda tag) (into shadow (nth row 2)) shadow)]
          (expand-children st row (child-places row)
                           (constantly shadow') loc))))))


;; =============================================================================
;; The batch (§3.1, §3.2)
;; =============================================================================

(defn- log-packet
  [st]
  [:yin.macro/log (:events st) (:produced st)])


(defn- with-default-guards
  [ctx]
  (update ctx :guards #(merge default-guards %)))


(defn expand-batch
  "Expand one input batch against `ctx` (§3.2). Returns
   `{:status :ok :tree packet :log log :ctx ctx'}` or
   `{:status :error :error e :log log :ctx ctx'}`. Either way `ctx'` has
   consumed the batch (`:t` + 1) and every attempt it allocated; only
   success changes the store, to the post-harvest store. A context store
   binding a reserved name, or holding an entry without the current AST
   contract, is refused (Rule R): it is a composition defect, so it throws
   rather than returning an error."
  [batch ctx]
  (check-store! (:store ctx))
  (let [ctx (with-default-guards ctx)
        source-batch (:t ctx)
        base {:ctx ctx, :source-batch source-batch, :events [], :produced []}
        finish (fn [st]
                 (let [ctx' (update (:ctx st) :t inc)]
                   (if-let [e (:error st)]
                     {:status :error, :error e, :log (log-packet st), :ctx ctx'}
                     {:status :ok, :tree (:tree st), :log (log-packet st),
                      :ctx (assoc ctx' :store (:next-store st))})))
        {:keys [admitted error]} (admit batch)]
    (if error
      (let [[st counter] (allocate-attempt base)]
        (finish (-> st
                    (record-event (event-row (attempt-id ctx counter) source-batch
                                             (source-origin ctx source-batch nil)
                                             nil nil nil nil nil error))
                    (fail error))))
      (let [{:keys [trees indexes index run-index definitions]} admitted
            store0 (or (:store ctx) {})
            store (harvest store0 #(nth indexes %) definitions)
            catalogue (into []
                            (comp (filter :declared?)
                                  (map (fn [{:keys [name tree path value]}]
                                         {:name name,
                                          :macro-tree (packet-of (nth indexes tree) value),
                                          :source [tree path]})))
                            definitions)
            ;; only the run tree is expanded, so only its declarations are
            ;; replaced; every catalogue entry still resolves by index
            [index standins]
            (reduce (fn [[index acc] [k {:keys [name source]}]]
                      (if (= run-index (first source))
                        (let [[index s] (intern-body index [standin-tag name k])]
                          [index (assoc acc (second source) s)])
                        [index acc]))
                    [index {}]
                    (map-indexed vector catalogue))
            [index root] (rebuild-paths index (first (nth trees run-index)) standins)
            st (assoc base :index index :store store)]
        (if-let [e (guard-batch-rows st)]
          (finish (fail st e))
          (let [[st root'] (expand-node st root
                                        {:path [], :rel [],
                                         :frame {:parent nil, :root root,
                                                 :tree-index run-index},
                                         :shadow #{}, :depth 0})]
            (if (:error st)
              (finish st)
              (let [next-store (post-harvest store0 (:index st) root' catalogue)
                    [index root'] (retail (:index st) root')
                    [index root'] (lower-standins index root')
                    tree (packet-of index root')]
                (if-let [e (valid-tree? tree)]
                  (throw (ex-info "Expander produced a non-canonical tree" e))
                  (finish (assoc st :tree tree :next-store next-store)))))))))))


(defn expand
  "`expand-batch` returning the tree packet, or throwing `ex-info` carrying
   the error data."
  [batch ctx]
  (let [{:keys [status tree error]} (expand-batch batch ctx)]
    (if (= :ok status)
      tree
      (throw (ex-info "Macro expansion failed" error)))))


(defn make-ctx
  "An expander context. The composition supplies the incarnation `token`
   and the opaque `source-medium` identity (S3.1); the store may be
   seeded with `macro-entry` values. A seeded store binding a reserved
   name, a macro named `yin/def`, is refused (Rule R), and so is any entry
   whose AST contract is absent (`:contract-missing`) or old
   (`:contract-mismatch`)."
  [{:keys [token source-medium store guards], eval-fn :eval}]
  (cond-> {:store (check-store! (or store {})),
           :incarnation {:yin.expander/token token},
           :attempt 0,
           :source-medium source-medium,
           :t 0,
           :guards (merge default-guards guards)}
    eval-fn (assoc :eval eval-fn)))


;; =============================================================================
;; Standard forms (§5.4)
;; =============================================================================

(def ^:private defn-lambda-ast
  "`(fn [fn-name fn-params & body]
      (let [b (yin/sequence-body body)
            l (yin/make-lambda fn-params b)]
        (yin/make-def fn-name l)))`, lowered as `let` lowers."
  (let [v (fn [s] {:type :variable, :name s})
        app (fn [op & args] {:type :application, :operator op, :operands (vec args)})]
    {:type :lambda,
     :params '[fn-name fn-params & body],
     :body (app {:type :lambda,
                 :params '[b],
                 :body (app {:type :lambda,
                             :params '[l],
                             :body (app (v 'yin/make-def) (v 'fn-name) (v 'l))}
                            (app (v 'yin/make-lambda) (v 'fn-params) (v 'b)))}
                (app (v 'yin/sequence-body) (v 'body)))}))


(def stdlib-forms
  "The standard forms as a row-native batch: one tree defining `defn`,
   declared and harvested. Seed a store by expanding it (`seed-store`)."
  (let [def-ast {:type :application,
                 :operator {:type :variable, :name 'yin/def},
                 :operands [{:type :literal, :value 'defn} defn-lambda-ast]}]
    [:yin.program/batch
     [(ast->packet def-ast)]
     0
     [[:yin.macro/definition 0 []]]
     [[:yin.macro/harvest 0 0 []]]]))


(defn seed-store
  "Fold validated input batches in order through `expand-batch`, returning
   the resulting store. Throws on a batch that fails."
  [ctx batches]
  (:store (reduce (fn [ctx batch]
                    (let [{:keys [status error] :as r} (expand-batch batch ctx)]
                      (when-not (= :ok status)
                        (throw (ex-info "Store seeding batch failed" error)))
                      (:ctx r)))
                  ctx
                  batches)))


;; =============================================================================
;; Expander observer (§9)
;; =============================================================================

(defn make-expander
  "The observer consumer: `ctx`, the `program-out` writer, and an optional
   log writer, with nothing staged."
  ([ctx out] (make-expander ctx out nil))
  ([ctx out log]
   {:ctx ctx, :out out, :out-staged nil, :log log, :log-staged nil,
    :forwarded 0, :errors []}))


(defn ready?
  "Ready only when both staged slots are empty."
  [expander]
  (and (nil? (:out-staged expander)) (nil? (:log-staged expander))))


(defn load
  "Expand one observed batch once: keep the returned context, stage the tree
   only on success, stage the log when a log writer exists, and accumulate
   the error of a failed expansion. Expansion failure is data, so the
   cursor advances past it."
  [expander batch]
  (let [{:keys [status tree log error ctx]} (expand-batch batch (:ctx expander))]
    (cond-> (assoc expander :ctx ctx)
      (= :ok status) (assoc :out-staged tree)
      (:log expander) (assoc :log-staged log)
      (= :error status) (update :errors conj error))))


(defn- flush-slot
  [expander writer-key slot-key on-ok]
  (if-let [payload (get expander slot-key)]
    (let [outcome (:dao.stream/outcome (stream/append! (get expander writer-key) payload))]
      (case outcome
        :dao.stream/ok (on-ok (assoc expander slot-key nil))
        :dao.stream/full expander
        (throw (ex-info "Expander could not publish a staged value"
                        {:dao.stream/outcome outcome,
                         :destination slot-key,
                         :consumer expander}))))
    expander))


(defn flush
  "Append each staged payload to its own destination (§9): `ok` clears only
   that slot (and counts a forwarded program), `full` retains the exact
   payload for retry, anything else throws with the outcome and the
   consumer's remaining staged slots under `:consumer`."
  [expander]
  ;; A log failure after a published program carries the cleared program
  ;; slot, so recovery never republishes it.
  (-> expander
      (flush-slot :out :out-staged #(update % :forwarded inc))
      (flush-slot :log :log-staged identity)))


(defn step
  "Drive one expander session `{:observer o :consumer expander}` over
   `program-in` until it blocks, ends, or cannot flush."
  [session]
  (observer/run-on-stream session ready? load flush))


(defn drain-errors
  "Read and reset `:errors` and `:forwarded`. Accepts a session or the bare
   consumer and returns `[same-shape' {:errors errors :forwarded n}]`."
  [x]
  (let [session? (contains? x :consumer)
        c (if session? (:consumer x) x)
        summary {:errors (:errors c), :forwarded (:forwarded c)}
        c' (assoc c :errors [] :forwarded 0)]
    [(if session? (assoc x :consumer c') c') summary]))
