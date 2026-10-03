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
     node's facts are a join back to `A` through the site set."
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


(defn sites
  "`{path kind}` of every site marked in the frontend-metadata relation
   `metadata` at occurrence `origin` of tree `root`."
  [metadata origin root]
  (into {}
        (keep (fn [[o r path k v]]
                (when (and (= o origin) (= r root) (= k site-key))
                  [path v])))
        metadata))


(defn site-set
  "The selected sites as a sorted vector of `[path kind]`, in an order every
   host computes alike."
  [selected]
  (vec (sort-by pr-str (map vec selected))))


(def exit-kinds
  "Exit kind -> the entry kind whose sites it wraps. An exit hook is applied
   to the value of the site's body, so it runs after a normal exit and its
   answer is the body's value; an escape out of the body skips it."
  {:return :call})


(defn- site-hooks
  "`{:enter hook :exit hook}` (either absent) for a site of `kind` under
   `profile`."
  [profile kind]
  (let [exit (some (fn [[x entry]] (when (= entry kind) (get profile x)))
                   exit-kinds)]
    (cond-> {}
      (get profile kind) (assoc :enter (get profile kind))
      exit (assoc :exit exit))))


(defn- hook-application
  [hook operands]
  {:type :application,
   :operator {:type :variable, :name hook},
   :operands operands})


(defn- prefix-hook
  "Lambda `node` with `hook` applied before its body runs."
  [node hook]
  (assoc node
         :body {:type :application,
                :operator {:type :lambda, :params [binder], :body (:body node)},
                :operands [(hook-application hook [])]}))


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
                (if-let [{:keys [enter exit]} (get hooks path)]
                  (do (when-not (= :lambda (:type node))
                        (throw (ex-info "Safepoint site is not a lambda"
                                        {:rule :site-not-lambda, :path path,
                                         :type (:type node)})))
                      (vswap! seen conj path)
                      (cond-> node
                        exit (wrap-hook exit)
                        enter (prefix-hook enter)))
                  node)))]
      (let [out (walk ast [])
            missing (remove @seen (keys hooks))]
        (when (seq missing)
          (throw (ex-info "Safepoint site names no node"
                          {:rule :site-missing, :paths (vec (sort-by pr-str missing))})))
        out))))


(defn insert
  "Pure: the canonical row set `tree` (`{:root id :rows {id row}}`) with
   each of `sites` (`{path kind}`) that `profile` (`{kind hook}`) maps a
   hook to, by its kind or by an exit kind of it, rewritten: the entry hook
   prefixed, the exit hook wrapped around the body. Returns
   `{:tree A' :sites selected}`. With nothing selected `A'` is `tree`
   itself."
  [tree sites profile]
  (let [hooks (into {}
                    (keep (fn [[path kind]]
                            (let [h (site-hooks profile kind)]
                              (when (seq h) [path h]))))
                    sites)
        selected (into {}
                       (filter (fn [[path _]] (contains? hooks path)))
                       sites)]
    {:tree (if (empty? hooks)
             tree
             (vm/ast->semantic-bytecode
               (tails/remark-tails
                 (rewrite (vm/semantic-bytecode->ast tree) hooks))))
     :sites selected}))


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
   is `A`; `e'` carries `A'` alone, on `medium`, with token `a`."
  [envelope profile medium]
  (let [{:yin/keys [source-medium batch-token batch root frontend-metadata]}
        envelope
        tree (nth batch root)
        origin (vm/source-origin source-medium batch-token root)
        {derived :tree selected :sites}
        (insert tree (sites frontend-metadata origin (:root tree)) profile)
        record (derive-record (:root tree) (:root derived) profile selected)
        address (jing/segment-key record)]
    (vm/source-origin medium address 0)
    {:envelope {:yin/source-medium medium,
                :yin/batch-token address,
                :yin/batch [derived],
                :yin/root 0,
                :yin/source-positions [],
                :yin/frontend-metadata [],
                :yin/entity-occurrences []},
     :record record,
     :record-address address}))


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
