(ns yang.antlr.export
  "The host-free half of a CST exporter (docs/design/yang.antlr.md §5.5): the
   byte-offset table, the token and rule records, the synthetic-token rule
   and the error record. A host's `yang.antlr.cst` walks its own parse-tree
   objects through a small `ops` map and gets back the portable nodes:

     {:token?    (fn [node] bool)           ; a terminal node
      :token     (fn [node] {:type-name s :start i :stop i :text s})
      :rule-name (fn [ctx] string)
      :children  (fn [ctx] vector)
      :start     (fn [ctx] start-index or nil)}

   Indexes are code-point indexes into the source, the unit all three
   targets use."
  (:require
    [yang.antlr.packet :as packet]))


(defn byte-offsets
  "Byte offset of each code point index of `cps`, plus the total at index n."
  [cps]
  (packet/byte-offsets cps))


(defn offset-at
  [offsets i]
  (nth offsets (max 0 (min i (dec (count offsets))))))


(defn token-span
  "The byte span of a token's code point range [start, stop]; a token whose
   stop precedes its start is zero-width."
  [offsets start stop]
  (let [s (offset-at offsets start)]
    [s (if (< stop start) s (offset-at offsets (inc stop)))]))


(defn syntax-error
  [message line column span]
  {:kind :syntax-error, :message (str message), :line line, :column column,
   :span span})


(defn- synthetic-token?
  "Whether a token is synthetic, decided on its type by
   `packet/synthetic-policy`. DEDENT and EOF always are. A NEWLINE is only
   when the source disagrees with the helper (its text is not the source at
   its range, or its range overlaps the previous real token) and it sits at
   the end of the source; every other type is never synthetic, so a token
   the policy forbids stays real and the validator rejects its text."
  [cps offsets last-stop {:keys [type-name start stop text]}]
  (let [n (dec (count offsets))
        policy (get packet/synthetic-policy type-name)]
    (case (:when policy)
      :always true
      :at-end (and (or (not= (when (and (<= 0 start) (<= start stop) (< stop n))
                               (subvec cps start (inc stop)))
                             (some-> text packet/code-points))
                       (<= start last-stop))
                   (>= stop (dec n)))
      false)))


(defn- token-record
  "`last-stop` is the stop index of the previous real token: a NEWLINE that
   overlaps it was produced by a helper, not read from the source. A
   synthetic token is zero-width and carries its type's canonical text."
  [cps offsets last-stop id {:keys [type-name start stop text], :as tok}]
  (if (synthetic-token? cps offsets last-stop tok)
    (let [p (offset-at offsets (inc stop))]
      {:id id,
       :kind :token,
       :type type-name,
       :text (get-in packet/synthetic-policy [type-name :text] text),
       :span [p p],
       :synthetic true})
    {:id id,
     :kind :token,
     :type type-name,
     :text text,
     :span [(offset-at offsets start) (offset-at offsets (inc stop))]}))


(defn export-nodes
  "Preorder records for the parse tree under `root`; a record's id is its
   index."
  [ops cps offsets root]
  (let [out (volatile! [])
        last-stop (volatile! -1)]
    (letfn [(walk
              [t]
              (let [id (count @out)]
                (if ((:token? ops) t)
                  (let [tok ((:token ops) t)
                        record (token-record cps offsets @last-stop id tok)]
                    (when-not (:synthetic record)
                      (vreset! last-stop (:stop tok)))
                    (vswap! out conj record))
                  (do
                    (vswap! out conj nil)
                    (let [kids (mapv walk ((:children ops) t))
                          records @out
                          ;; a rule covers its real text: synthetic tokens
                          ;; and empty rules do not stretch it
                          child-spans (keep (fn [k]
                                              (let [r (nth records k)
                                                    [a b] (:span r)]
                                                (when (and (not (:synthetic r))
                                                           (< a b))
                                                  [a b])))
                                            kids)
                          start ((:start ops) t)
                          s (if start (offset-at offsets start) 0)
                          span (if (seq child-spans)
                                 [(apply min (map first child-spans))
                                  (apply max (map second child-spans))]
                                 [s s])]
                      (vswap! out assoc id
                              {:id id,
                               :kind :rule,
                               :rule ((:rule-name ops) t),
                               :children kids,
                               :span span}))))
                id))]
      (walk root)
      @out)))


(defn result
  "The packet for a parse: an error packet when `errors` is non-empty."
  [profile unit errors nodes]
  (let [base (packet/base (:grammar-id profile) unit)]
    (if (seq errors)
      (assoc base
             :yang.cst/outcome :yang.cst/syntax-error
             :yang.cst/errors errors)
      (assoc base
             :yang.cst/outcome :yang.cst/ok
             :yang.cst/root 0
             :yang.cst/nodes (nodes)))))


(defn failure-kind
  "Classify a parse's first failure. `failure` is nil (the parse had none),
   :lexical (a lexer error, which has no token), or the index of the failing
   token; `real-tokens` are the buffered non-EOF tokens as
   {:index :start :stop}. End-of-input tokens are EOF and the zero-width ones
   after the last token with source text (the lexer's EOF can carry a span,
   so it is excluded by type). Only a failure there is mendable by more
   input: :end; any earlier failure is :early."
  [failure real-tokens]
  (let [last-real (reduce max -1
                          (keep (fn [{:keys [index start stop]}]
                                  (when (<= start stop) index))
                                real-tokens))]
    (cond
      (nil? failure) nil
      (and (integer? failure) (> failure last-real)) :end
      :else :early)))
