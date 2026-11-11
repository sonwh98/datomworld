(ns yang.safepoint
  "The safepoint stage (Architect safepoint-interpreter design, slice 1): a
   pure rewrite of a canonical program tree `A` into a derived tree `A'` that
   applies a hook at every frontend-marked site the profile maps.
   Language-neutral: it knows the Universal AST row grammar and the
   frontend-metadata side table, nothing about any guest language. The hooks'
   meaning lives in a per-language hook prelude; the evaluator never learns
   what a safepoint is.

   - A site is a `:lambda` occurrence the frontend marked with `:yang/site`
     metadata, which projection puts in the side table as
     `[origin root path :yang/site kind]`. A profile `{kind hook-symbol}`
     selects sites; a kind it omits is not inserted.
   - Insertion prefixes the site's body with an ordinary `:application` of
     the hook, sequenced through a lambda whose one binder is `binder`, in a
     reserved namespace: no new tag, no gensym counter. An exit kind
     (`exit-kinds`: `:return` at `:call` sites) wraps the body instead: its
     hook is applied to the body's value, so it runs on normal exit only.
   - A mark's value is a kind, or a vector `[kind datum ...]`: the data
     (a function's name and line, a statement's line) are literals the
     frontend knows and the derived tree may need. A profile entry is a
     hook symbol, or `{:hook symbol :operands true}` to apply the hook to
     the mark's data. A statement kind (`statement-kinds`: `:line`) marks
     any node, not a lambda; its hook is applied before the node, which
     is sequenced after it.
   - Every tail mark is stripped and recomputed over the whole derived tree
     (`yang.tails`): a wrapped body's old tail calls would otherwise skip
     its wrapper. With no site selected `A'` is `A`, row for row.
   - `A` is never modified. Names, the ledger and publication refer to it;
     the link is a `:derive` record with input `A`, output `A'`, function
     `function`, and a profile pinning the hook map and the address of the
     sorted site set. Insertion is deterministic, so a repeat writes the
     same record.
   - `A'` leaves on the stage's own medium in a projected envelope whose
     batch token is the record's address, so its occurrence origin is an
     ordinary `[:source medium' token 0]`. No side table is copied: an `A'`
     node's facts are a join back to `A` through the site set. The one
     exception is the C1 claim: when the input claims `:yang/call-site`
     coverage, `derive-envelope` recomputes its rows over `A'` (never
     copies them — paths shift) and records the input→output occurrence
     correspondence as data."
  (:require
    [dao.jing :as jing]
    [yang.stage :as stage]
    [yang.tails :as tails]
    [yin.vm :as vm]))


(def binder
  "The parameter of every inserted sequencing lambda."
  'yang.safepoint/_)


(def function
  "The `:yin.ledger/function` of a safepoint derivation."
  :yang.safepoint/insert)


(def site-key
  "The frontend-metadata key a site mark travels under."
  :yang/site)


(def call-site-key
  "The frontend-metadata key of the call-site occurrence contract C1,
   recomputed over a derived tree beside the safepoint site marks."
  :yang/call-site)


(defn sites
  "`{path mark}` (a kind, or `[kind datum ...]`) of every site marked in the frontend-metadata relation
   `metadata` at occurrence `origin` of tree `root`."
  [metadata origin root]
  (into {}
        (keep (fn [[o r path k v]]
                (when (and (= o origin) (= r root) (= k site-key))
                  [path v])))
        metadata))


(defn site-set
  "The selected sites as a sorted vector of `[path mark]`, a mark a kind or `[kind datum ...]`, in an order every
   host computes alike."
  [selected]
  (vec (sort-by pr-str (map vec selected))))


(def exit-kinds
  "Exit kind -> the entry kind whose sites it wraps. An exit hook is applied
   to the value of the site's body, so it runs after a normal exit and its
   answer is the body's value; an escape out of the body skips it."
  {:return :call})


(def statement-kinds
  "Kinds whose sites are any node, not a lambda: the hook runs before the
   node, and the node's value is the site's."
  #{:line})


(defn- mark-kind
  [mark]
  (if (vector? mark) (first mark) mark))


(defn- entry-hook
  "The `{:hook sym :operands [literal ...]}` of profile entry `entry` at a
   site marked `mark`, or nil."
  [entry mark]
  (when entry
    (if (map? entry)
      {:hook (:hook entry),
       :operands (when (:operands entry) (vec (rest mark)))}
      {:hook entry})))


(defn- site-hooks
  "`{:enter h :exit h}` (either absent), each an `entry-hook`, for a site
   marked `mark` under `profile`."
  [profile mark]
  (let [kind (mark-kind mark)
        exit (some (fn [[x entry]] (when (= entry kind) (get profile x)))
                   exit-kinds)]
    (cond-> {}
      (get profile kind) (assoc :enter (entry-hook (get profile kind) mark))
      (and (map? (get profile kind)) (:sequence (get profile kind)))
      (assoc :sequence? true)
      exit (assoc :exit (entry-hook exit mark)))))


(defn- hook-application
  [{:keys [hook operands]} args]
  {:type :application,
   :operator {:type :variable, :name hook},
   :operands (into (mapv (fn [x] {:type :literal, :value x}) operands) args)})


(defn- sequence-hook
  "`node` evaluated after `hook` is applied."
  [node hook]
  {:type :application,
   :operator {:type :lambda, :params [binder], :body node},
   :operands [(hook-application hook [])]})


(defn- prefix-hook
  "Lambda `node` with `hook` applied before its body runs."
  [node hook]
  (assoc node
         :body (sequence-hook (:body node) hook)))


(defn- wrap-hook
  "Lambda `node` with `hook` applied to its body's value."
  [node hook]
  (assoc node :body (hook-application hook [(:body node)])))


(defn- rewrite
  "The map AST `ast` with each `{path {:enter h :exit h}}` site's exit hook
   wrapped around its body and its entry hook prefixed. Throws when a site
   names no node, or a node that is not a lambda."
  [ast hooks]
  (let [seen (volatile! #{})]
    (letfn [(walk
              [node path]
              (let [slots (get vm/semantic-bytecode-grammar (:type node))
                    node (reduce
                           (fn [n [j [field kind]]]
                             (let [pos (+ j 2)]
                               (case kind
                                 :node (update n field walk (conj path pos))
                                 :nodes (update n field
                                                (fn [children]
                                                  (vec (map-indexed
                                                         (fn [i child]
                                                           (walk child (conj path [pos i])))
                                                         children))))
                                 n)))
                           node
                           (map-indexed vector slots))]
                (if-let [{:keys [enter exit statement? sequence?]} (get hooks path)]
                  (do (vswap! seen conj path)
                      (if (or statement? sequence?)
                        (sequence-hook node enter)
                        (do (when-not (= :lambda (:type node))
                              (throw (ex-info "Safepoint site is not a lambda"
                                              {:rule :site-not-lambda, :path path,
                                               :type (:type node)})))
                            (cond-> node
                              exit (wrap-hook exit)
                              enter (prefix-hook enter)))))
                  node)))]
      (let [out (walk ast [])
            missing (remove @seen (keys hooks))]
        (when (seq missing)
          (throw (ex-info "Safepoint site names no node"
                          {:rule :site-missing, :paths (vec (sort-by pr-str missing))})))
        out))))


(defn- selected-hooks
  "`{path {:enter h :exit h}}` of the sites `profile` selects."
  [sites profile]
  (into {}
        (keep (fn [[path mark]]
                (let [h (site-hooks profile mark)]
                  (when (seq h)
                    [path (cond-> h
                            (contains? statement-kinds (mark-kind mark))
                            (assoc :statement? true))]))))
        sites))


(defn insert
  "Pure: the canonical row set `tree` (`{:root id :rows {id row}}`) with
   each of `sites` (`{path mark}`, a mark a kind or `[kind datum ...]`) that
   `profile` (`{kind hook}`, a hook a symbol or `{:hook sym :operands true}`)
   maps a hook to, by its kind or by an exit kind of it, rewritten: the entry hook
   prefixed, the exit hook wrapped around the body. Returns
   `{:tree A' :sites selected}`. With nothing selected `A'` is `tree`
   itself."
  [tree sites profile]
  (let [hooks (selected-hooks sites profile)
        selected (into {}
                       (filter (fn [[path _]] (contains? hooks path)))
                       sites)]
    {:tree (if (empty? hooks)
             tree
             (vm/ast->semantic-bytecode
               (tails/remark-tails
                 (rewrite (vm/semantic-bytecode->ast tree) hooks))))
     :sites selected}))


(defn- track-occurrences
  "Path arithmetic over the input map AST `ast` under the selected
   `hooks`: `{:surviving {in-path out-path} :injected #{out-path}}` for
   every `:application` occurrence. Wrapping never drops, duplicates, or
   reorders a subtree — it only prefixes hook applications — so each
   input application has exactly one output place, derived here from
   the rewrite's own insertions, never matched by content. A sequencing
   insertion (`sequence-hook`) puts its node under `[2 3]` beneath the
   injected sequencing application; a lambda site's body shifts under
   the entry sequencing application and the exit hook application."
  [ast hooks]
  (let [surviving (atom {})
        injected (atom #{})]
    (letfn [(walk2
                [node in-path out-path]
                (let [site (get hooks in-path)]
                  (cond
                    (and site (or (:statement? site) (:sequence? site)))
                    (do (swap! injected conj out-path (conj out-path [3 0]))
                        (walk-self2 node in-path (conj out-path 2 3)))

                    (and site (= :lambda (:type node)))
                    (let [{:keys [enter exit]} site
                          ;; hook construction prepends the mark's literal
                          ;; operands, so the wrapped body is the exit
                          ;; hook's operand at index (count operands)
                          body-operand (count (:operands exit))
                          body-out (into out-path
                                         (cond
                                           (and enter exit)
                                           [3 2 3 [3 body-operand]]
                                           enter [3 2 3]
                                           exit [3 [3 body-operand]]
                                           :else [3]))]
                      (when enter
                        (swap! injected conj (conj out-path 3)
                               (into out-path [3 [3 0]])))
                      (when exit
                        (swap! injected conj
                               (if enter (conj out-path 3 2 3) (conj out-path 3))))
                      (walk2 (:body node) (conj in-path 3) body-out))

                    :else (walk-self2 node in-path out-path))))
              (walk-self2
                [node in-path out-path]
                (when (= :application (:type node))
                  (swap! surviving assoc in-path out-path))
                (doseq [[j [field kind]] (map-indexed vector
                                                      (get vm/semantic-bytecode-grammar
                                                           (:type node)))
                        :let [pos (+ j 2)
                              value (get node field)]]
                  (case kind
                    :node (walk2 value (conj in-path pos) (conj out-path pos))
                    :nodes (doseq [[i child] (map-indexed vector (or value []))]
                             (walk2 child (conj in-path [pos i])
                                    (conj out-path [pos i])))
                    nil)))]
        (walk2 ast [] []))
      {:surviving @surviving :injected @injected}))


(defn- derive-call-sites
  "Recompute the C1 rows of the derived tree from the input tree's own
   rows: `{:metadata rows :correspondence c}`, or nil when the input
   carries no C1 claim for `tree`. A surviving occurrence keeps its
   input row's origin at its tracked output path. An injected
   occurrence is claimed only under `injected-origin`, the admission
   origin the caller supplies; without one it is recorded as injected
   and left unclaimed. An input row whose occurrence is claimed by two
   origins, or that names no application occurrence of the input tree,
   is refused attribution (`:unmapped`) — never guessed. Every mapped
   output place is verified against the derived tree's own application
   occurrences; a place that is not an application there (the exit
   hook's literal operands, say) is likewise `:unmapped`
   (`:output-not-an-application`), never emitted as a row."
  [frontend-metadata tree derived hooks injected-origin]
  (let [rows (filterv (fn [row]
                        (and (sequential? row) (= 5 (count row))
                             (= (:root tree) (nth row 1))
                             (= call-site-key (nth row 3))))
                      frontend-metadata)]
    (when (seq rows)
      (let [{:keys [surviving injected]}
            (track-occurrences (vm/semantic-bytecode->ast tree) hooks)
            eligible (into #{} (map second) (vm/application-occurrences tree))
            derived-eligible (into #{}
                                   (map second)
                                   (vm/application-occurrences derived))
            by-path (group-by #(nth % 2) rows)
            sorted (fn [xs] (vec (sort-by pr-str xs)))
            parts (reduce
                    (fn [acc [path rs]]
                      (let [origins (distinct (map first rs))]
                        (cond
                          (< 1 (count origins))
                          (update acc :unmapped into
                                  (map (fn [o]
                                         {:kind :unmapped
                                          :input [o (:root tree) path]
                                          :reason :ambiguous})
                                       origins))

                          (not (contains? eligible path))
                          (update acc :unmapped conj
                                  {:kind :unmapped
                                   :input [(first (first rs)) (:root tree) path]
                                   :reason :no-such-occurrence})

                          :else
                          (let [o (first (first rs))
                                out (get surviving path)]
                            (if (contains? derived-eligible out)
                              (-> acc
                                  (update :entries conj
                                          {:kind :surviving
                                           :input [o (:root tree) path]
                                           :output [o (:root derived) out]})
                                  (update :rows into
                                          (map (fn [r]
                                                 [o (:root derived) out
                                                  call-site-key (nth r 4)])
                                               rs)))
                              (update acc :unmapped conj
                                      {:kind :unmapped
                                       :input [o (:root tree) path]
                                       :reason :output-not-an-application}))))))
                    {:entries [] :rows [] :unmapped []}
                    (sort-by (comp pr-str key) by-path))
            verified-injected (filterv derived-eligible (sorted injected))
            refused-injected (map (fn [p]
                                    {:kind :unmapped :path p
                                     :reason :output-not-an-application})
                                  (remove derived-eligible (sorted injected)))
            injected-entries (map (fn [p]
                                    (cond-> {:kind :safepoint/injected :path p}
                                      injected-origin
                                      (assoc :origin injected-origin)))
                                  verified-injected)
            injected-rows (when injected-origin
                            (map (fn [p]
                                   [injected-origin (:root derived) p
                                    call-site-key 1])
                                 verified-injected))]
        {:metadata (vec (sort jing/canonical-compare
                              (distinct (into (:rows parts) injected-rows))))
         :correspondence {:input-root (:root tree)
                          :output-root (:root derived)
                          :entries (into (sorted (:entries parts))
                                         (into injected-entries
                                               (sorted (into (:unmapped parts)
                                                             refused-injected))))}}))))


(defn derive-record
  "The `:derive` record linking canonical root `input` to derived root
   `output`: function `function`, profile pinning the hook map and the
   address of the sorted site set."
  [input output profile selected]
  {:yin.ledger/op :derive,
   :yin.ledger/input input,
   :yin.ledger/output output,
   :yin.ledger/function function,
   :yin.ledger/profile {:yang.safepoint/hooks profile,
                        :yang.safepoint/sites (jing/segment-key
                                                (site-set selected))}})


(defn derive-envelope
  "One projected envelope from the row medium to the derived one:
   `{:envelope e' :record r :record-address a}`. The run member of the input
   is `A`; `e'` carries `A'` alone, on `medium`, with token `a`.

   When the input envelope's frontend-metadata claims C1 for the run
   member, `e'` carries the recomputed rows of `A'` and the answer
   carries `:correspondence`, the explicit input→output occurrence
   correspondence (also on `e'` as `:yang.safepoint/correspondence`).
   `opts` may supply `:injected-origin`, the caller's admission origin
   for the injected hook applications; without one they stay
   unclaimed. No origin is ever derived from the trees' structure."
  ([envelope profile medium]
   (derive-envelope envelope profile medium nil))
  ([envelope profile medium {:keys [injected-origin]}]
   (when (and injected-origin (not (vm/occurrence-origin? injected-origin)))
     (throw (ex-info "Malformed injected call-site origin"
                     {:rule :origin, :origin injected-origin})))
   (let [{:yin/keys [source-medium batch-token batch root frontend-metadata]}
         envelope
         tree (nth batch root)
         origin (vm/source-origin source-medium batch-token root)
         input-sites (sites frontend-metadata origin (:root tree))
         {derived :tree selected :sites}
         (insert tree input-sites profile)
         {:keys [metadata correspondence]}
         (derive-call-sites frontend-metadata tree derived
                            (selected-hooks input-sites profile)
                            injected-origin)
         record (derive-record (:root tree) (:root derived) profile selected)
         address (jing/segment-key record)]
     (vm/source-origin medium address 0)
     (cond-> {:envelope (cond-> {:yin/source-medium medium,
                                 :yin/batch-token address,
                                 :yin/batch [derived],
                                 :yin/root 0,
                                 :yin/source-positions [],
                                 :yin/frontend-metadata (or metadata []),
                                 :yin/entity-occurrences []}
                          correspondence
                          (assoc :yang.safepoint/correspondence correspondence)),
              :record record,
              :record-address address}
       correspondence (assoc :correspondence correspondence)))))


(defn transform
  "The stage's transform over projected envelopes: `A'` on port `:program`,
   its `:derive` record on port `:ledger`. State is `{:profile p :medium m}`
   and never changes."
  [{:keys [profile medium], :as state} envelope]
  (let [{:keys [envelope record]} (derive-envelope envelope profile medium)]
    [state [[:program envelope] [:ledger record]]]))


(defn open-stage
  "A safepoint stage reading projected envelopes from `rows`, writing derived
   envelopes to `program` (logical identity `medium`) and records to
   `ledger`."
  [rows program ledger profile medium]
  (stage/open rows
              {:program program, :ledger ledger}
              {:profile profile, :medium medium}))


(defn step-stage
  [s]
  (stage/step s transform))
