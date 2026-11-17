(ns yang.correspondence-claims
  "Build a scoped query plan from supplied inventory and carrier data.
   This does not independently establish that either input is correct."
  (:require [clojure.string :as str]))


(defn- arity?
  [x]
  (or (nat-int? x)
      (and (vector? x) (= 2 (count x)) (= :variadic (first x)) (nat-int? (second x)))))


(defn- guest-name
  [s]
  (symbol (str "gate-" (last (str/split (namespace s) #"\.")) "-" (name s))))


(defn claim-plan!
  "Create one query per supplied admitted upstream symbol and arity. Carrier
   triples must be scoped to an admitted pair and use a supported rule. The
   plan is not evidence that the supplied producer-derived carriers are true."
  [inventory carriers]
  (when-not (and (sequential? inventory) (map? carriers)
                 (every? (fn [entry]
                           (and (map? entry) (qualified-symbol? (:symbol entry))
                                (keyword? (:disposition entry))
                                (or (nil? (:arities entry))
                                    (and (sequential? (:arities entry))
                                         (every? arity? (:arities entry))
                                         (= (count (:arities entry)) (count (set (:arities entry))))))))
                         inventory)
                 (= (count inventory) (count (set (map :symbol inventory)))))
    (throw (ex-info "Malformed claim inventory" {:stage :claims})))
  (let [pairs (set (for [{:keys [symbol disposition arities]} inventory
                         :when (= :admitted disposition), arity arities]
                     [symbol arity]))
        valid-carrier? (fn [x]
                         (and (vector? x) (= 3 (count x)) (symbol? (nth x 0))
                              (arity? (nth x 1))
                              (contains? #{:ambient-delegation :context-shift :context-copy} (nth x 2))))]
    (when-not (and (seq pairs)
                   (every? (fn [[pair xs]]
                             (and (contains? pairs pair) (set? xs) (every? valid-carrier? xs))) carriers))
      (throw (ex-info "Invalid or unscoped carrier relation" {:stage :claims})))
    (mapv (fn [[symbol arity]]
            {:upstream-symbol symbol :guest-symbol (guest-name symbol)
             :requested-arity arity :carriers (vec (sort-by pr-str (get carriers [symbol arity] #{})))})
          (sort-by pr-str pairs))))


(defn- clauses
  [form]
  (let [header (drop-while #(not (or (vector? %) (seq? %))) (nnext form))]
    (cond
      (vector? (first header)) [header]
      (and (seq header) (every? #(and (seq? %) (vector? (first %))) header)) header
      :else (throw (ex-info "Malformed callable" {:stage :carrier-input})))))


(defn derive-carriers!
  "Independently interpret supplied upstream and guest forms. `resolutions`
   is an explicit map from [source-ns symbol] to its resolved
   qualified symbol or nil; missing entries refuse rather than guessing."
  [inventory upstream context guest-forms refusal-forms integration adapters ambient resolutions]
  (let [program (concat context guest-forms refusal-forms)
        ctx-names (set (map second context))
        guest-names (set (map second guest-forms))
        program-clauses (fn [name]
                          (when-let [form (some #(when (= name (second %)) %) program)]
                            (clauses form)))
        resolve! (fn [ns-id symbol]
                   (let [k [ns-id symbol]]
                     (when-not (contains? resolutions k)
                       (throw (ex-info "Missing symbol resolution" {:stage :carrier-input :key k})))
                     (get resolutions k)))]
    (reduce
      (fn [all {:keys [ns name symbol kind disposition]}]
        (if-not (and (= :admitted disposition) (contains? #{'defn 'defn-} kind))
          all
          (let [source (get upstream [ns name])
                _ (when-not source (throw (ex-info "Missing upstream form" {:stage :carrier-input :symbol symbol})))
                upstream-clauses (clauses source)
                arities (set (map #(count (first %)) upstream-clauses))
                integrator (get integration symbol)
                guest (guest-name symbol)
                has-arity? (fn [g n]
                             (some #(= n (count (first %))) (program-clauses g)))
                carrier (fn [n]
                          (or (when (has-arity? integrator n) integrator)
                              (when (and (contains? adapters name) (has-arity? name n)) name)
                              (when (has-arity? guest n) guest)))
                delegations
                (into #{}
                      (keep (fn [clause]
                              (let [params (first clause)
                                    body (rest clause)
                                    call (first body)]
                                (when (and (= 1 (count body)) (seq? call)
                                           (symbol? (first call))
                                           (= symbol (resolve! ns (first call))))
                                  (let [args (rest call)
                                        external (filter #(and (symbol? %) (nil? (namespace %))
                                                               (not (contains? (set params) %)))
                                                         (tree-seq coll? seq args))
                                        ambient? (some #(contains? ambient (resolve! ns %)) external)
                                        g (carrier (count args))]
                                    (when (and ambient? g)
                                      [(count params) [g (count args) :ambient-delegation]]))))))
                      upstream-clauses)
                delegated-arities (set (map first delegations))
                candidates (distinct (remove nil? [integrator
                                                   (when (contains? ctx-names name) name)
                                                   (when (and (contains? guest-names guest)
                                                              (= 'kernel (ffirst (program-clauses guest)))) guest)]))
                shifts
                (into #{}
                      (for [g candidates clause (program-clauses g)
                            :let [params (first clause)]
                            :when (and (seq params) (= 'kernel (first params)))
                            :let [n (count params)
                                  replaced (some (fn [u]
                                                   (let [p (first u)]
                                                     (when (and (= n (count p))
                                                                (contains? '#{def-env env} (first p))) n)))
                                                 upstream-clauses)
                                  prior (dec n)
                                  added (when (and (contains? arities prior)
                                                   (not-any? (fn [u]
                                                               (let [p (first u)]
                                                                 (and (= prior (count p))
                                                                      (contains? '#{def-env env} (first p)))))
                                                             upstream-clauses)) prior)
                                  upstream-arity (or replaced added)]
                            :when (and upstream-arity (not (contains? delegated-arities upstream-arity)))]
                        [upstream-arity [g n :context-shift]]))
                shifted-arities (set (map first shifts))
                copies
                (into #{}
                      (for [g [integrator] :when g, clause (program-clauses g)
                            :let [params (first clause) n (count params)]
                            :when (and (seq params) (not= 'kernel (first params))
                                       (contains? arities n) (not (contains? delegated-arities n))
                                       (not (contains? shifted-arities n)))]
                        [n [g n :context-copy]]))]
            (reduce (fn [result [arity path]]
                      (update result [symbol arity] (fnil conj #{}) path))
                    all (concat delegations shifts copies)))))
      {} inventory)))


(defn check-carriers!
  "Refuse any difference between independently derived and claimed carriers."
  [derived claimed]
  (when-not (= derived claimed)
    (throw (ex-info "Carrier relation mismatch" {:stage :carrier-declarations
                                                 :missing (set (remove (set claimed) derived))
                                                 :extra (set (remove (set derived) claimed))})))
  true)
