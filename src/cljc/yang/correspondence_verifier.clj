(ns yang.correspondence-verifier
  "Independent static interpretation of stamped oracle call expressions.
   This namespace does not establish D1 verification or authorize reliance."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [yin.vm :as vm]
            [dao.jing :as jing]))


(defn- var-node?
  [node name]
  (and (= :variable (:type node)) (= name (:name node))))


(defn- intra?
  [node]
  (and (= :application (:type node))
       (var-node? (:operator node) 'dsg__intra_call)
       (= 2 (count (:operands node)))))


(defn- stamp
  [node]
  (when (and (intra? node) (var-node? (first (:operands node)) 'gate-site!))
    (let [args (second (:operands node))
          operands (:operands args)]
      (when-not (and (= :application (:type args)) (var-node? (:operator args) 'conj)
                     (= 3 (count operands)) (= {:type :literal :value []} (first operands))
                     (= :literal (:type (second operands)))
                     (integer? (:value (second operands))) (pos? (:value (second operands))))
        (throw (ex-info "Malformed oracle stamp" {:rule :verifier/stamp})))
      [(:value (second operands)) (nth operands 2)])))


(defn- args-of
  [node]
  (let [node (if-let [[_ original] (stamp node)] original node)]
    (cond
      (= :literal (:type node)) (:value node)
      (and (= :application (:type node)) (var-node? (:operator node) 'conj)
           (= :literal (:type (first (:operands node))))) (rest (:operands node))
      :else nil)))


(defn- routing?
  [node]
  (boolean (some #(and (map? %) (= :variable (:type %))
                       (contains? '#{dsg__admitted? yang.call/invoke yang.call/invoke-with}
                                  (:name %)))
                 (tree-seq coll? seq node))))


(defn- routed-inner
  [node]
  (let [op (:operator node)
        inner (:body op)
        nested (:operator inner)]
    (when (and (= :application (:type node)) (= :lambda (:type op))
               (= 1 (count (:params op))) (= 1 (count (:operands node)))
               (= :application (:type inner)) (= :lambda (:type nested))
               (= 1 (count (:params nested))) (= 1 (count (:operands inner)))
               (routing? (:body nested))
               (or (= :literal (:type (first (:operands inner))))
                   (some? (args-of (first (:operands inner))))))
      inner)))


(defn- shape
  [node bindings params constructors depth]
  (cond
    (or (nil? node) (> depth 8)) :computed
    (= :literal (:type node)) :literal
    (not (coll? node)) :literal
    (= :variable (:type node))
    (if (contains? params (:name node)) :variable
        (if-let [value (get bindings (:name node))]
          (shape value bindings params constructors (inc depth)) :variable))
    (and (intra? node) (contains? constructors (get-in node [:operands 0 :name])))
    (let [args (args-of (second (:operands node)))
          first-arg (first args)]
      (cond
        (and (= 'kernel-context (get-in node [:operands 0 :name]))
             (= :literal (:type first-arg)) (= [{} {}] (:value first-arg))) :ambient-literal
        (and (sequential? args) (= 1 (count args))
             (= :ambient-literal (shape first-arg bindings params constructors (inc depth)))) :ambient-literal
        :else :computed))
    (and (= :application (:type node)) (var-node? (:operator node) 'data/nth)
         (= 2 (count (:operands node)))
         (= :literal (:type (second (:operands node))))
         (number? (:value (second (:operands node))))
         (pos? (:value (second (:operands node)))))
    (shape (first (:operands node)) bindings params constructors (inc depth))
    :else :computed))


(defn site-declarations
  "Derive declarations from stamp IDs and call argument expressions, not rows.
   `constructors` is the profile's set of context-state constructor names."
  [ast constructors]
  (let [rows (atom {})]
    (letfn [(record!
              [id callee args owner bindings params]
              (when (contains? @rows id)
                (throw (ex-info "Duplicate oracle site" {:rule :verifier/duplicate-site :site id})))
              (swap! rows assoc id {:site id :callee callee
                                    :arg-shapes (if (= :dynamic callee) [:dynamic-callee]
                                                    (mapv #(shape % bindings params constructors 0)
                                                          (or (args-of args) [])))
                                    :def owner :delegation? (and (symbol? callee) (= callee owner))}))
            (visit
              [node owner bindings params]
              (cond
                (map? node)
                (let [op (:operator node)
                      operands (:operands node)
                      direct (when (and (intra? node)
                                        (= :variable (:type (first operands))))
                               (stamp (second operands)))
                      routed (routed-inner node)
                      indirect (when routed (stamp (first (:operands routed))))]
                  (cond
                    (and (= :application (:type node)) (var-node? op 'yin/def)
                         (= :literal (:type (first operands))))
                    (doseq [child operands] (visit child (:value (first operands)) bindings params))
                    direct
                    (let [[id args] direct]
                      (visit args owner bindings params)
                      (record! id (:name (first operands)) args owner bindings params))
                    indirect
                    (let [[id args] indirect]
                      (record! id :dynamic args owner bindings params)
                      (visit args owner bindings params)
                      (visit (:body (:operator routed)) owner bindings params)
                      (doseq [child operands] (visit child owner bindings params)))
                    (and (= :application (:type node)) (= :lambda (:type op))
                         (= 1 (count (:params op))) (= 1 (count operands)))
                    (let [init (first operands)]
                      (visit init owner bindings params)
                      (visit (:body op) owner (assoc bindings (first (:params op)) init) params))
                    (= :lambda (:type node))
                    (visit (:body node) owner bindings (into params (:params node)))
                    :else (doseq [value (vals node) :when (coll? value)]
                            (visit value owner bindings params))))
                (sequential? node) (doseq [child node] (visit child owner bindings params))
                :else nil))]
      (visit ast nil {} #{})
      @rows)))


(defn check-declarations!
  "Refuse a table that differs from declarations interpreted from the AST."
  [ast constructors expected]
  (let [actual (site-declarations ast constructors)]
    (when-not (= expected actual)
      (throw (ex-info "Oracle declarations disagree" {:rule :verifier/site-declarations})))
    true))


(defn source-hash
  "Independently interpret the oracle's first-occurrence gensym hash rule."
  [forms call]
  (let [input [forms call]
        generated? #(and (symbol? %)
                         (re-matches #"^[a-zA-Z0-9_.-]+__\d+(?:__auto__)?#?$" (name %)))
        names (distinct (filter generated? (tree-seq coll? seq input)))
        replacements (into {} (map-indexed (fn [i sym] [sym (symbol (str "$gensym_" (inc i)))]) names))
        canonical (walk/postwalk #(get replacements % %) input)
        text (binding [*print-length* nil *print-level* nil] (pr-str canonical))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 255 %)) digest))))


(defn context-constructors
  "Derive the profile's state constructor names from retained context forms."
  [forms]
  (into '#{kernel-context}
        (comp (map second)
              (filter #(or (str/starts-with? (str %) "install-")
                           (str/starts-with? (str %) "register-")
                           (str/starts-with? (str %) "unregister-"))))
        forms))


(defn declaration-differences
  [expected actual]
  (let [ek (set (keys expected)) ak (set (keys actual))]
    {:missing (vec (sort (remove ak ek)))
     :extra (vec (sort (remove ek ak)))
     :different (vec (sort (filter #(not= (get expected %) (get actual %))
                                   (filter ak ek))))}))


(defn- structural-correspondence
  [before after]
  (letfn [(bad!
            [before-path after-path]
            (throw (ex-info "Stamp transformation differs from source tree"
                            {:rule :verifier/transformation :before before-path :after after-path})))
          (step
            [a b ap bp paths]
            (if-let [[_ unwrapped] (stamp b)]
              (do (when (stamp a) (bad! ap bp))
                  (step a unwrapped ap (conj bp [3 1] [3 2]) paths))
              (if (and (map? a) (map? b) (:type a) (= (:type a) (:type b)))
                (let [grammar (get vm/semantic-bytecode-grammar (:type a))]
                  (when-not grammar (bad! ap bp))
                  (reduce (fn [acc [j [field kind]]]
                            (let [x (get a field) y (get b field) pos (+ 2 j)]
                              (case kind
                                :node (if (or (map? x) (map? y))
                                        (step x y (conj ap pos) (conj bp pos) acc)
                                        (if (= x y) acc (bad! ap bp)))
                                :nodes (if (= (count x) (count y))
                                         (reduce (fn [inner i]
                                                   (step (nth x i) (nth y i)
                                                         (conj ap [pos i]) (conj bp [pos i]) inner))
                                                 acc (range (count x)))
                                         (bad! ap bp))
                                (if (= x y) acc (bad! ap bp)))))
                          (assoc paths bp ap) (map-indexed vector grammar)))
                (if (= a b) paths (bad! ap bp)))))]
    (step before after [] [] {})))


(defn- syntax-places
  [node path]
  (when (and (map? node) (:type node))
    (cons [path node]
          (mapcat (fn [[j [field kind]]]
                    (let [pos (+ j 2) value (get node field)]
                      (case kind
                        :node (syntax-places value (conj path pos))
                        :nodes (mapcat (fn [[i child]] (syntax-places child (conj path [pos i])))
                                       (map-indexed vector value))
                        [])))
                  (map-indexed vector (get vm/semantic-bytecode-grammar (:type node)))))))


(defn reconstruct-bindings
  "Independently translate stamped call sites to the source row occurrence."
  [before stamped origin]
  (when-not (vm/occurrence-origin? origin)
    (throw (ex-info "Missing retained admission" {:rule :verifier/origin})))
  (let [tree (vm/ast->semantic-bytecode before)
        root (:root tree)
        eligible (set (vm/application-occurrences tree))
        paths (structural-correspondence before stamped)
        sites (keep (fn [[path node]]
                      (when (= :application (:type node))
                        (let [direct (stamp (second (:operands node)))
                              routed (routed-inner node)
                              indirect (when routed (stamp (first (:operands routed))))]
                          (when-let [[id _] (or direct indirect)]
                            (let [target (get paths path ::missing)]
                              (when-not (contains? eligible [root target])
                                (throw (ex-info "Stamp has no source application" {:rule :verifier/binding :site id})))
                              [id [origin root target]])))))
                    (syntax-places stamped []))
        ids (map first sites)
        targets (map second sites)]
    (when (or (not= (count ids) (count (set ids)))
              (not= (count targets) (count (set targets))))
      (throw (ex-info "Ambiguous stamp binding" {:rule :verifier/binding})))
    (into {} sites)))


(defn attributed?
  "Query one already-scoped fixture's table, bindings and complete lane traces.
   This query does not validate those inputs or authorize reliance on them."
  [{:keys [site-table bindings lanes origin root guest-symbol requested-arity carriers]}]
  (letfn [(eligible?
            [entry]
            (when (and (vector? entry) (= 3 (count entry)))
              (let [[function arity site] entry
                    row (get site-table site)
                    key (get bindings site)]
                (and row (= site (:site row)) (vector? key) (= 3 (count key))
                     (vm/occurrence-origin? origin) (= origin (first key))
                     (jing/segment-address? root) (= root (second key)) (vector? (nth key 2))
                     (not= :gate-site/none site)
                     (or (and (= function guest-symbol) (= arity requested-arity))
                         (some (fn [[carrier carrier-arity mode]]
                                 (and (contains? #{:ambient-delegation :context-shift :context-copy} mode)
                                      (= function carrier) (= arity carrier-arity)
                                      (= carrier (:callee row)) (not (:delegation? row))
                                      (or (not= :ambient-delegation mode)
                                          (= :ambient-literal (first (:arg-shapes row))))))
                               carriers))))))]
    (every? (fn [lane-id]
              (let [lane (get lanes lane-id)]
                (and (= :completed (:status lane)) (true? (:complete? lane))
                     (some eligible? (:executed-calls lane)))))
            [:c-walker :c-semantic])))


(defn check-fixture!
  "Run independent static checks for one supplied compilation. This does not
   establish admission authenticity, executable-image derivations, trace
   completeness, durable retrieval, or D1 reliance."
  [{:keys [fixture before stamped origin constructors forms call source-hash
           site-table bindings metadata]}]
  (letfn [(require!
            [condition stage]
            (when-not condition
              (throw (ex-info "Independent fixture check refused"
                              {:rule :verifier/fixture :fixture fixture :stage stage}))))]
    (require! (and fixture (vm/occurrence-origin? origin) (map? before)
                   (map? stamped) (set? constructors) (some? forms) (some? call)
                   (string? source-hash) (map? site-table) (map? bindings)
                   (vector? metadata)) :inputs)
    (let [actual-hash (try (yang.correspondence-verifier/source-hash forms call)
                           (catch Exception e
                             (throw (ex-info "Source hash reconstruction failed"
                                             {:rule :verifier/fixture :fixture fixture :stage :source-hash} e))))]
      (require! (= source-hash actual-hash) :source-hash))
    (let [actual-sites (try (site-declarations stamped constructors)
                            (catch Exception e
                              (throw (ex-info "Site reconstruction failed"
                                              {:rule :verifier/fixture :fixture fixture :stage :site-declarations} e))))]
      (require! (= site-table actual-sites) :site-declarations))
    (let [actual-bindings (try (reconstruct-bindings before stamped origin)
                               (catch Exception e
                                 (throw (ex-info "Binding reconstruction failed"
                                                 {:rule :verifier/fixture :fixture fixture :stage :bindings} e))))]
      (require! (= bindings actual-bindings) :bindings))
    (let [tree (vm/ast->semantic-bytecode before)
          markers (filterv #(and (vector? %) (= 5 (count %))
                                 (= :yang/call-site (nth % 3))) metadata)
          keys (mapv #(subvec % 0 3) markers)
          expected (into #{} (map (fn [[root path]] [origin root path]))
                         (vm/application-occurrences tree))]
      (require! (and (nil? (vm/validate-rows tree))
                     (every? #(and (vector? %) (= 5 (count %))) metadata)
                     (every? #(= 1 (nth % 4)) markers)
                     (= (count keys) (count (set keys)))
                     (= expected (set keys))) :metadata))
    {:fixture fixture :status :static-pass}))


(defn fetch-tree!
  "Fetch a bounded complete canonical row closure; refuse missing or invalid rows."
  [handle root max-rows]
  (when-not (and (jing/segment-address? root) (integer? max-rows) (pos? max-rows))
    (throw (ex-info "Invalid row retrieval request" {:rule :verifier/tree-request})))
  (loop [pending [root] rows {}]
    (if-let [address (peek pending)]
      (if (contains? rows address)
        (recur (pop pending) rows)
        (do
          (when (>= (count rows) max-rows)
            (throw (ex-info "Row closure exceeds bound" {:rule :verifier/tree-bound :limit max-rows})))
          (let [body (jing/get handle address ::missing)
                grammar (when (and (vector? body) (seq body))
                          (get vm/semantic-bytecode-grammar (first body)))]
            (when-not (and grammar (= (count body) (inc (count grammar))))
              (throw (ex-info "Missing or malformed row" {:rule :verifier/tree-row :address address})))
            (let [row (into [address] body)
                  children (into []
                                 (mapcat (fn [[j [_ kind]]]
                                           (let [v (nth row (+ j 2))]
                                             (case kind :node [v] :nodes v []))))
                                 (map-indexed vector grammar))]
              (when-not (every? jing/segment-address? children)
                (throw (ex-info "Malformed child reference" {:rule :verifier/tree-row :address address})))
              (recur (into (pop pending) children) (assoc rows address row))))))
      (let [tree {:root root :rows rows}]
        (when-let [defect (vm/validate-rows tree)]
          (throw (ex-info "Invalid canonical row closure" {:rule :verifier/tree :defect defect})))
        tree))))


(defn check-replay-root!
  "Refuse if a newly compiled replay does not name the retained root."
  [fixture replayed-ast expected-root]
  (when-not (and (jing/segment-address? expected-root)
                 (= expected-root (:root (vm/ast->semantic-bytecode replayed-ast))))
    (throw (ex-info "Recompiled root differs from retained root"
                    {:rule :verifier/fixture :fixture fixture :stage :root})))
  true)
