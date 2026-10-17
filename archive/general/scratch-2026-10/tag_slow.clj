;; One-off: tag the deftests listed in collab/slow-deftests-5s.tsv ^:slow and,
;; when the deftest is reachable on Node/Dart in a .cljc file, wrap its body in
;; (slow/guard "name" (fn [] ...)). Usage: bb collab/tag_slow.clj
(require '[clojure.string :as str]
         '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n])


(def rows
  (->> (str/split-lines (slurp "collab/slow-deftests-5s.tsv"))
       (map #(str/split % #"\t"))
       (map (fn [[_ ns t]] [ns t]))))


(defn ns->file
  [ns]
  (let [base (str "test/" (-> ns (str/replace "." "/") (str/replace "-" "_")))]
    (first (filter #(.exists (java.io.File. %)) [(str base ".cljc") (str base ".clj")]))))


(defn deftest-name-loc
  "loc of the name (token or meta node) when loc is a (deftest name ...) list."
  [loc]
  (when (= :list (z/tag loc))
    (let [h (z/down loc)]
      (when (and h (= 'deftest (z/sexpr h)))
        (z/right h)))))


(defn bare-name
  [name-loc]
  (if (= :meta (z/tag name-loc))
    (-> name-loc z/down z/rightmost z/sexpr)
    (z/sexpr name-loc)))


(defn clj-only?
  "True when loc sits in the :clj branch of a reader conditional."
  [loc]
  (loop [l loc]
    (if-let [p (z/up l)]
      (if (and (= :list (z/tag p)) (some-> p z/up z/tag (= :reader-macro)))
        ;; l is a branch value inside the conditional list: key is left sibling
        (if (= :clj (z/sexpr (z/left l))) true (recur p))
        (recur p))
      false)))


(defn- ends-line?
  [node]
  (str/ends-with? (n/string node) "\n"))


(defn- indent-seq
  "Shift every line that starts inside `nodes` right by `k` spaces."
  [nodes k]
  (let [nodes (vec nodes)]
    (vec (mapcat (fn [i node]
                   (let [node (if (n/inner? node)
                                (n/replace-children node (indent-seq (n/children node) k))
                                node)
                         nxt (get nodes (inc i))]
                     (if (and (ends-line? node) nxt (not= :newline (n/tag nxt)))
                       [node (n/spaces k)]
                       [node])))
                 (range) nodes))))


(defn wrap-body
  [name-loc test-name col]
  ;; children of the deftest list after the name; col is the deftest's column
  (let [list-loc (z/up name-loc)
        children (n/children (z/node list-loc))
        name-node (z/node name-loc)
        idx (.indexOf children name-node)
        after (drop (inc idx) children)
        lead (take-while n/whitespace-or-comment? after)
        body (drop (count lead) after)
        ;; lead ends in the newline + indent before the body's first form
        guard (n/list-node
                (concat [(n/token-node 'slow/guard) (n/newlines 1) (n/spaces (+ col 4))
                         (n/string-node test-name) (n/newlines 1) (n/spaces (+ col 4))
                         (n/list-node (concat [(n/token-node 'fn) (n/spaces 1)
                                               (n/vector-node []) (n/newlines 1)
                                               (n/spaces (+ col 6))]
                                              (indent-seq body 4)))]))]
    (-> list-loc
        (z/replace (n/list-node (concat (take (inc idx) children) lead [guard])))
        z/down z/right)))


(defn tag
  [name-loc]
  (if (= :meta (z/tag name-loc))
    (throw (ex-info "name already has metadata" {:name (z/string name-loc)}))
    (z/replace name-loc (n/meta-node (n/keyword-node :slow) (z/node name-loc)))))


(defn process-file
  [file names]
  (let [cljc? (str/ends-with? file ".cljc")
        guarded (atom [])
        found (atom #{})
        src (:out (babashka.process/shell {:out :string} "git" "show" (str "HEAD:" file)))
        zloc (z/of-string src)
        result
        (loop [loc zloc]
          (if (z/end? loc)
            (z/root-string loc)
            (if-let [nl (deftest-name-loc loc)]
              (let [nm (str (bare-name nl))]
                (if (contains? names nm)
                  (let [guard? (and cljc? (not (clj-only? loc)))
                        col (dec (:col (meta (z/node loc))))
                        nl (tag nl)
                        nl (if guard? (wrap-body nl nm col) nl)]
                    (swap! found conj nm)
                    (when guard? (swap! guarded conj nm))
                    (recur (z/next (z/up nl))))
                  (recur (z/next loc))))
              (recur (z/next loc)))))]
    (spit file result)
    {:file file :missing (remove @found names) :guarded @guarded
     :tagged (count @found)}))


(doseq [[ns ts] (group-by first rows)]
  (prn ns (process-file (ns->file ns) (set (map second ts)))))
