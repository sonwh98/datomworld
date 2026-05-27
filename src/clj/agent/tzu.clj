(ns agent.tzu
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [org.httpkit.client :as http])
  (:import
    (org.jsoup
      Jsoup)))


(defn- api-key
  []
  (or
    (System/getenv "DEEPSEEK_API_KEY")
    (throw
      (ex-info
        "DEEPSEEK_API_KEY is not set in this process's environment. Export it in the shell that launched the REPL/CLI."
        {}))))


(defn ask*
  [prompt]
  (let [{:keys [status body error]}
        @(http/post "https://api.deepseek.com/chat/completions"
                    {:headers {"Authorization" (str "Bearer " (api-key)),
                               "Content-Type" "application/json"},
                     :body (json/write-str {:model "deepseek-chat",
                                            :max_tokens 8192,
                                            :messages [{:role "user",
                                                        :content prompt}]})})]
    (when error (throw (ex-info "DeepSeek request failed" {:error error})))
    (when (not= 200 status)
      (throw (ex-info (str "DeepSeek HTTP " status)
                      {:status status, :body body})))
    (let [choice (-> body
                     (json/read-str :key-fn keyword)
                     (get-in [:choices 0]))]
      {:content (get-in choice [:message :content]),
       :finish-reason (:finish_reason choice)})))


(defn ask
  [prompt]
  (:content (ask* prompt)))


(def ^:private datom-prompt
  (str
    "EXHAUSTIVELY extract every factual claim from the input as a Clojure EDN vector of [e a v] triples.\n"
    "Do NOT summarize. Do NOT pick representative facts. Emit a datom for EVERY instance you find in EVERY category below.\n"
    "\n"
    "Extraction checklist — for each category, scan the entire input and emit a datom for every occurrence:\n"
    "  1. IDENTITY: name, aliases, also-known-as, kind/type, category, part-of-speech.\n"
    "  2. DEFINITION: what the subject IS, what it DOES, what it MEANS, defining properties.\n"
    "  3. PEOPLE: inventors, authors, founders, contributors, critics, opponents — every named person.\n"
    "  4. ORGANIZATIONS: companies, universities, labs, projects, standards bodies — every named org.\n"
    "  5. PLACES: cities, countries, regions, venues — every named location.\n"
    "  6. DATES & TIMES: years, decades, centuries, durations, intervals — every temporal reference.\n"
    "  7. RELATIONSHIPS: created-by, derived-from, implements, extends, replaces, depends-on, supersedes, influenced-by, member-of, part-of, opposed-to.\n"
    "  8. PROPERTIES: numeric values, sizes, speeds, capacities, versions, prices, counts, ratings.\n"
    "  9. EXAMPLES: every example, instance, implementation, variant, or use-case mentioned.\n"
    "  10. EVENTS: launches, releases, mergers, discoveries, publications, awards, controversies.\n"
    "  11. WORKS: books, papers, songs, films, products, standards, specifications — every named work.\n"
    "  12. CAUSATION: what caused what, what led to what, what resulted from what.\n"
    "  13. CRITICISM & RECEPTION: praise, criticism, controversies, debates, reviews.\n"
    "  14. COMPARISONS: similar-to, different-from, better-than, alternative-to.\n"
    "  15. MECHANISMS: how it works — steps, components, inputs, outputs, side-effects.\n"
    "\n"
    "Coverage standard: if a sentence in the input contains a fact and you have NOT emitted a corresponding datom, you have failed the task. Re-read each sentence and ask: 'have I captured every fact here?'\n"
    "\n"
    "What to skip:\n"
    "  - Document metadata: URLs, ISBNs, page titles, revision IDs, edit history.\n"
    "  - Bibliographic references, citation lists, 'see also' links, category tags, navigation cruft.\n"
    "  - Vague meta-attributes like :type \"concept\" or :domain \"computing\" that add no information.\n"
    "\n" "Triple format:\n"
    "  e = entity (negative integer tempid; reuse the same id for facts about the same entity)\n"
    "  a = attribute (simple lowercase keyword with hyphens; use specific predicates like :invented-by :released-in :implements, NOT generic ones like :mentioned :related :includes)\n"
    "  v = value: string, number, boolean, simple keyword, or a negative integer referencing another entity\n"
    "\n"
    "Rules:\n" "  - Use the subject of the article as entity -1.\n"
    "  - CONNECTEDNESS: every entity you introduce (-2, -3, ...) MUST be the value of at least one datom whose entity is another tempid. No orphan entities. If you create [-7 :name \"JavaSpaces\"] you must also emit a datom like [-1 :has-implementation -7] that links it into the graph.\n"
    "  - PROMOTE TO ENTITIES: any person, organization, place, or named work you encounter must be its own tempid linked into the graph, not just a string value duplicated across datoms.\n"
    "  - Before finalizing, mentally trace every tempid: can it be reached from -1 by following datom values? If not, either add the linking datom or drop the entity.\n"
    "  - Multi-word values, proper nouns, and anything with '/', '.', '?', '!', or digits must be strings, not keywords.\n"
    "  - Prefer specific, verb-derived attributes over generic ones.\n"
    "  - If a relationship is between two entities, reference them by tempid rather than duplicating their names as strings.\n"
    "  - When the article lists items of the same kind (e.g. implementations in many languages), promote each to its own entity and link it back, rather than emitting many string-valued datoms on -1.\n"
    "\n"
    "Return ONLY the EDN vector, no prose, no code fences.\n" "\n"
    "Example input: \"The Linda coordination language was created by David Gelernter at Yale in 1986. It introduced tuple spaces, a form of associative shared memory used for parallel programming.\"\n"
    "Example output: [[-1 :name \"Linda\"] [-1 :kind \"coordination language\"] [-1 :created-by -2] [-1 :created-at -3] [-1 :created-in-year 1986] [-1 :introduced -4] [-2 :name \"David Gelernter\"] [-3 :name \"Yale University\"] [-4 :name \"tuple space\"] [-4 :kind \"associative shared memory\"] [-4 :used-for \"parallel programming\"]]\n\n"
    "FINAL REMINDER: extract EVERY fact, not a representative sample. If your output is shorter than roughly 70% of the input's character count, you are summarizing — go back and emit more datoms until coverage is complete.\n\n"
    "Input: "))


(defn- fetch
  [url]
  (:body @(http/get url
                    {:headers {"User-Agent" "Mozilla/5.0 (agent.tzu)"},
                     :follow-redirects true})))


(defn- strip-html
  [^String s]
  (.text (Jsoup/parse s "")))


(defn- strip-fences
  [s]
  (-> s
      clojure.string/trim
      (clojure.string/replace #"(?s)^```(?:edn|clojure)?\s*" "")
      (clojure.string/replace #"```\s*$" "")
      clojure.string/trim))


(defn- catalog-str
  [entities]
  (if (empty? entities)
    "(none yet)"
    (->> entities
         (sort-by key >)
         (map (fn [[tid name]] (str "  " tid " = " (pr-str name))))
         (clojure.string/join "\n"))))


(defn- context-preamble
  [next-tid entities]
  (str
    "Entities already assigned in earlier chunks (REUSE these tempids when the same entity appears here; do NOT renumber them):\n"
    (catalog-str entities)
    "\n\nNext available tempid for NEW entities: "
    next-tid
    " (then "
    (dec next-tid)
    ", "
    (- next-tid 2)
    ", ...). Do NOT start at -1.\n\n"))


(defn- entities-from
  [datoms]
  (into {} (keep (fn [[e a v]] (when (= :name a) [e v])) datoms)))


(defn- min-tempid
  [datoms]
  (reduce min
          0
          (mapcat (fn [[e _ v]]
                    (cond-> []
                      (and (integer? e) (neg? e)) (conj e)
                      (and (integer? v) (neg? v)) (conj v)))
                  datoms)))


(defn- extract
  [s next-tid entities]
  (let [preamble (context-preamble next-tid entities)
        {:keys [content finish-reason]} (ask* (str datom-prompt preamble s))]
    (when (= "length" finish-reason)
      (throw
        (ex-info
          "LLM output truncated — chunk too large for 8K-token response. Reduce :chunk-size."
          {:chunk-preview (subs s 0 (min 200 (count s)))})))
    (-> content
        strip-fences
        edn/read-string)))


(defn- chunk-text
  [^String s chunk-size]
  (let [paragraphs (clojure.string/split s #"\n\n+|(?<=\. )(?=[A-Z])")]
    (loop [ps paragraphs
           buf ""
           acc []]
      (cond (empty? ps) (cond-> acc (seq buf) (conj buf))
            (> (+ (count buf) (count (first ps))) chunk-size)
            (recur ps "" (conj acc buf))
            :else (recur (rest ps) (str buf (first ps) " ") acc)))))


(defn text->datoms
  ([s] (text->datoms s 1500))
  ([s chunk-size]
   (let [chunks (chunk-text s chunk-size)]
     (loop [chunks chunks
            next-tid -1
            entities {}
            acc []]
       (if (empty? chunks)
         acc
         (let [datoms (extract (first chunks) next-tid entities)
               new-entities (merge entities (entities-from datoms))
               new-next (dec (min-tempid (concat acc datoms)))]
           (recur (rest chunks) new-next new-entities (into acc datoms))))))))


(def ^:private text-prompt
  (str
    "You are given a vector of [e a v] datoms extracted from a text. "
    "Reconstruct natural-language prose that expresses every fact in the datoms.\n"
    "\n"
    "NO HALLUCINATION: use ONLY information present in the datoms. Do NOT add facts, qualifiers, examples, dates, or context that are not explicitly asserted by some datom, even if the prose would read more naturally with them. If a datom does not assert it, it does not appear.\n"
    "\n"
    "FULL COVERAGE: every datom must appear as an assertion in the output. Do not omit, merge, or summarize datoms. After drafting, audit your prose against the datom list: every triple must be traceable to at least one sentence.\n"
    "\n" "Rules:\n"
    "  - Resolve negative-integer tempids to the entity's :name (or another identifying attribute on that entity) when generating prose; never leave the raw integer in the output.\n"
    "  - Group facts about the same entity into coherent sentences, but never at the cost of dropping a datom.\n"
    "  - Use the attribute keyword as a guide for the verb (e.g. :created-by -> \"was created by\", :released-in -> \"was released in\"). Do not invent stronger verbs than the attribute warrants.\n"
    "  - Return ONLY the prose. No headers, no commentary, no markdown, no code fences.\n"
    "\n" "Datoms:\n"))


(defn datoms->text
  [datoms]
  (let [{:keys [content finish-reason]} (ask* (str text-prompt
                                                   (pr-str datoms)))]
    (when (= "length" finish-reason)
      (throw
        (ex-info
          "LLM output truncated — datom set too large for 8K-token response. Reduce input or paginate."
          {:datom-count (count datoms)})))
    content))


(defn datoms->tx-data
  "Wrap [e a v] triples as [:db/add e a v] ops suitable for (dao.db/transact db ...)."
  [datoms]
  (mapv (fn [[e a v]] [:db/add e a v]) datoms))


(defn -main
  [& args]
  (let [input (cond (= "-url" (first args)) (strip-html (fetch (second args)))
                    (seq args) (clojure.string/join " " args)
                    :else (slurp *in*))]
    (clojure.pprint/pprint (text->datoms input))))


(comment
  (def d
    (-> "https://www.galactanet.com/oneoff/theegg.html"
        fetch
        strip-html
        text->datoms))
  (def o (datoms->text d)))
