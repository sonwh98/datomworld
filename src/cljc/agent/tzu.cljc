(ns agent.tzu
  (:require
    #?@(:cljd [["dart:convert" :as convert]]
        :cljs []
        :clj [[clojure.pprint :as pprint] [clojure.data.json :as json]])
    [agent.tools :as tools]
    #?@(:cljs [[cljs.reader :as edn]]
        :default [[clojure.edn :as edn]])
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.http]
    #?(:clj [dao.stream.ringbuffer])
    [dao.stream.ws]))


(defn api-key
  []
  #?(:clj (System/getenv "OPENAI_API_KEY")
     :cljs (some-> js/process
                   .-env
                   .-OPENAI_API_KEY)
     :cljd (throw (ex-info "API key must be provided via binding on CLJD" {}))))


(defn- json-encode
  [x]
  #?(:clj (json/write-str x)
     :cljs (js/JSON.stringify (clj->js x))
     :cljd (convert/jsonEncode x)))


#?(:cljd (defn- dart->clj
           [x]
           (cond (dart/is? x Map)
                 (into {} (map (fn [e] [(key e) (dart->clj (val e))])) x)
                 (dart/is? x List) (mapv dart->clj x)
                 :else x)))


(defn- json-decode
  [s]
  #?(:clj (json/read-str s)
     :cljs (js->clj (js/JSON.parse s))
     :cljd (dart->clj (convert/jsonDecode s))))


(defn chat-completion
  "Send messages vector to an OpenAI-compatible chat/completions endpoint.
   Optional :tools vector of tool definitions enables function calling. Returns
   the full parsed response body as Clojure data.

   Throws on transport error or non-200 status."
  [messages & {:keys [tools key]}]
  (let [k (or key (api-key))
        url (or #?(:clj (System/getenv "OPENAI_BASE_URL")
                   :cljs (some-> js/process
                                 .-env
                                 .-OPENAI_BASE_URL)
                   :cljd nil)
                "https://api.openai.com/v1/chat/completions")
        model (or #?(:clj (System/getenv "OPENAI_MODEL")
                     :cljs (some-> js/process
                                   .-env
                                   .-OPENAI_MODEL)
                     :cljd nil)
                  "gpt-4o")
        body-map (cond-> {"model" model, "max_tokens" 8192, "messages" messages}
                   tools (assoc "tools" tools))
        {:keys [status body error]}
        (ds/take!! (ds/open! {:type :http,
                              :url url,
                              :method :post,
                              :headers {"Authorization" (str "Bearer " k),
                                        "Content-Type" "application/json"},
                              :body (json-encode body-map)}))]
    (when error (throw (ex-info "LLM API request failed" {:error error})))
    (when (not= 200 status)
      (throw (ex-info (str "LLM API HTTP " status)
                      {:status status, :body body})))
    (json-decode body)))


(defn prompt
  "Prompt an OpenAI-compatible LLM and return the completion content (a string).
   Wraps chat-completion with a single user message. Throws on transport error,
   non-200 status, or a response truncated by the token cap.

   The JVM and JS read the key from OPENAI_API_KEY via `api-key`; any runtime
   may pass it explicitly through the 2-arity (required on Dart)."
  ([prompt-txt] (prompt prompt-txt (api-key)))
  ([prompt-txt key]
   (when-not key (throw (ex-info "OPENAI_API_KEY not set" {})))
   (let [resp (chat-completion [{"role" "user", "content" prompt-txt}] :key key)
         choice (get-in resp ["choices" 0])]
     (when (= "length" (get choice "finish_reason"))
       (throw
         (ex-info
           "LLM output truncated — response hit the token cap; reduce input"
           {:prompt-preview (subs prompt-txt 0 (min 200 (count prompt-txt)))})))
     (get-in choice ["message" "content"]))))


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
    "\n" "What to skip:\n"
    "  - Document metadata: URLs, ISBNs, page titles, revision IDs, edit history.\n"
    "  - Bibliographic references, citation lists, 'see also' links, category tags, navigation cruft.\n"
    "  - Vague meta-attributes like :type \"concept\" or :domain \"computing\" that add no information.\n"
    "\n"
    "Triple format:\n"
    "  e = entity (negative integer tempid; reuse the same id for facts about the same entity)\n"
    "  a = attribute: a Schema.org property as a namespaced keyword in Schema.org's camelCase, :schema/<property> (e.g. :schema/name, :schema/author, :schema/creator, :schema/birthDate, :schema/datePublished, :schema/publisher, :schema/location, :schema/memberOf). Pick the most specific Schema.org property that fits the fact. If NO Schema.org property expresses the relation, fall back to a plain hyphenated keyword WITHOUT the schema/ namespace (e.g. :influenced-by, :implements) so non-standard predicates stay distinguishable.\n"
    "  v = value: string, number, boolean, simple keyword, or a negative integer referencing another entity\n"
    "\n" "Rules:\n"
    "  - Use the subject of the article as entity -1.\n"
    "  - TYPE EVERY ENTITY: give each entity its own :schema/type datom whose value is the most specific Schema.org Type name as a string (e.g. \"Person\", \"Organization\", \"Place\", \"CollegeOrUniversity\", \"CreativeWork\", \"Book\", \"SoftwareApplication\", \"Event\"); default to \"Thing\" when none fits.\n"
    "  - CONNECTEDNESS: every entity you introduce (-2, -3, ...) MUST be the value of at least one datom whose entity is another tempid. No orphan entities. If you create [-7 :schema/name \"JavaSpaces\"] you must also emit a datom like [-1 :has-implementation -7] that links it into the graph.\n"
    "  - PROMOTE TO ENTITIES: any person, organization, place, or named work you encounter must be its own tempid linked into the graph, not just a string value duplicated across datoms.\n"
    "  - Before finalizing, mentally trace every tempid: can it be reached from -1 by following datom values? If not, either add the linking datom or drop the entity.\n"
    "  - Multi-word values, proper nouns, and anything with '/', '.', '?', '!', or digits must be strings, not keywords.\n"
    "  - Prefer the most specific Schema.org property; avoid generic ones like :schema/about or :schema/subjectOf when a precise property applies.\n"
    "  - If a relationship is between two entities, reference them by tempid rather than duplicating their names as strings.\n"
    "  - When the article lists items of the same kind (e.g. implementations in many languages), promote each to its own entity and link it back, rather than emitting many string-valued datoms on -1.\n"
    "\n"
    "Return ONLY the EDN vector, no prose, no code fences.\n" "\n"
    "Example input: \"The Linda coordination language was created by David Gelernter at Yale in 1986. It introduced tuple spaces, a form of associative shared memory used for parallel programming.\"\n"
    "Example output: [[-1 :schema/name \"Linda\"] [-1 :schema/type \"ComputerLanguage\"] [-1 :schema/creator -2] [-1 :schema/dateCreated \"1986\"] [-1 :introduced -4] [-2 :schema/name \"David Gelernter\"] [-2 :schema/type \"Person\"] [-2 :schema/affiliation -3] [-3 :schema/name \"Yale University\"] [-3 :schema/type \"CollegeOrUniversity\"] [-4 :schema/name \"tuple space\"] [-4 :schema/type \"Thing\"] [-4 :schema/description \"associative shared memory\"] [-4 :used-for \"parallel programming\"]]\n\n"
    "FINAL REMINDER: extract EVERY fact, not a representative sample. If your output is shorter than roughly 70% of the input's character count, you are summarizing — go back and emit more datoms until coverage is complete.\n\n"
    "Input: "))


(defn- strip-fences
  [s]
  (-> s
      str/trim
      (str/replace #"^```(?:edn|clojure)?\s*" "")
      (str/replace #"```\s*$" "")
      str/trim))


(defn- catalog-str
  [entities]
  (if (empty? entities)
    "(none yet)"
    (->> entities
         (sort-by key >)
         (map (fn [[tid name]] (str "  " tid " = " (pr-str name))))
         (str/join "\n"))))


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
  (into {} (keep (fn [[e a v]] (when (= :schema/name a) [e v])) datoms)))


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
  (-> (prompt (str datom-prompt (context-preamble next-tid entities) s))
      strip-fences
      edn/read-string))


(defn- chunk-text
  [^String s chunk-size]
  (let [paragraphs (str/split s #"\n\n+|(?<=\. )(?=[A-Z])")]
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
    "  - Resolve negative-integer tempids to the entity's :schema/name (or another identifying attribute on that entity) when generating prose; never leave the raw integer in the output.\n"
    "  - Group facts about the same entity into coherent sentences, but never at the cost of dropping a datom.\n"
    "  - Use the attribute keyword as a guide for the verb (e.g. :created-by -> \"was created by\", :released-in -> \"was released in\"). Do not invent stronger verbs than the attribute warrants.\n"
    "  - Return ONLY the prose. No headers, no commentary, no markdown, no code fences.\n"
    "\n" "Datoms:\n"))


(defn datoms->text
  [datoms]
  (prompt (str text-prompt (pr-str datoms))))


(defn datoms->tx-data
  "Wrap [e a v] triples as [:db/add e a v] ops suitable for (dao.db/transact db ...)."
  [datoms]
  (mapv (fn [[e a v]] [:db/add e a v]) datoms))


(def ^:private frames-prompt
  (str
    "Generate a dao.postgraphics animation from the user's prompt.\n" "\n"
    "OUTPUT SHAPE: a Clojure EDN vector of frames. Each frame is a vector of op-maps. A static scene is a one-element vector; an animation has many frames (default ~24). Return ONLY the EDN, no prose, no code fences.\n"
    "\n"
    "Top-level form:\n"
    "  [[<ops for frame 0>] [<ops for frame 1>] ... [<ops for frame N-1>]]\n"
    "\n"
    "Each op is a map with a :op/kind key and op-specific fields. SUPPORTED OPS (2D only):\n"
    "  {:op/kind :frame/clear        :color [r g b a]}\n"
    "  {:op/kind :draw/fill-rect     :rect [x y w h] :color [r g b a]}\n"
    "  {:op/kind :draw/stroke-rect   :rect [x y w h] :color [r g b a] :stroke-width 1.5}\n"
    "  {:op/kind :draw/fill-circle   :center [x y] :radius r :color [r g b a]}\n"
    "  {:op/kind :draw/stroke-circle :center [x y] :radius r :color [r g b a] :stroke-width 1.0}\n"
    "  {:op/kind :draw/path          :segments [[:move-to x y] [:line-to x y] [:close]] :fill [r g b a] :stroke [r g b a]}\n"
    "  {:op/kind :draw/text          :text \"...\" :position [x y] :color [r g b a] :font-size 13 :align :center}\n"
    "  {:op/kind :transform/push     :translate [tx ty] :rotate radians :scale [sx sy]}\n"
    "  {:op/kind :transform/pop}\n"
    "  {:op/kind :clip/push-rect     :rect [x y w h]}\n"
    "  {:op/kind :clip/pop}\n" "\n"
    "CONVENTIONS:\n"
    "  - Canvas is 400x400 unless the prompt implies otherwise. Origin is top-left, x grows right, y grows down. Units are pixels.\n"
    "  - Colors are [r g b a] floats in [0.0, 1.0]. Use 1.0 for full alpha unless transparency is needed.\n"
    "  - Numbers must be plain Clojure literals (e.g. 200, 0.5, -1.2). No expressions, no math operators.\n"
    "  - For animations, derive each frame's values from the frame index. Compute the values yourself and emit the resulting literal numbers; do NOT emit code or symbols.\n"
    "  - Every frame should begin with :frame/clear so prior frames do not ghost.\n"
    "  - :transform/push and :transform/pop must balance within a frame. Same for :clip/push-rect and :clip/pop.\n"
    "  - Use ONLY the op kinds listed above. Do not invent new ones.\n"
    "  - No 3D ops, no lighting, no render targets, no cameras in this iteration.\n"
    "\n"
    "EXAMPLE prompt: \"a red circle moving from left to right across 3 frames\"\n"
    "EXAMPLE output:\n"
    "[[{:op/kind :frame/clear :color [0.0 0.0 0.0 1.0]}\n"
    "  {:op/kind :draw/fill-circle :center [80 200] :radius 30 :color [1.0 0.0 0.0 1.0]}]\n"
    " [{:op/kind :frame/clear :color [0.0 0.0 0.0 1.0]}\n"
    "  {:op/kind :draw/fill-circle :center [200 200] :radius 30 :color [1.0 0.0 0.0 1.0]}]\n"
    " [{:op/kind :frame/clear :color [0.0 0.0 0.0 1.0]}\n"
    "  {:op/kind :draw/fill-circle :center [320 200] :radius 30 :color [1.0 0.0 0.0 1.0]}]]\n"
    "\n" "User prompt: "))


(defn prompt->frames
  "Generate a sequence of dao.postgraphics frames from a natural-language prompt.
   Returns [[ops-for-frame-0] [ops-for-frame-1] ...]. A static scene is a
   one-element vector; an animation has many."
  [prompt-txt]
  (-> (prompt (str frames-prompt prompt-txt))
      strip-fences
      edn/read-string))


(def ^:private illustrate-prompt
  (str
    "You illustrate a user-supplied story on a Flutter canvas by streaming dao.postgraphics frames over WebSocket. Follow this protocol exactly.\n"
    "\n"
    "STEP 1: Call ws_open with stream_id=\"flutter\" and the URL given below.\n"
    "STEP 2: Emit one stream_write call per frame, every call with stream_id=\"flutter\" (never \"io\"). Batch as many stream_write calls as you can into a single assistant turn (aim for 8-10 frames per turn, never fewer than 4) so the whole animation finishes in a handful of turns. Do NOT pause to narrate or explain between frames. After the final frame, reply \"done\".\n"
    "\n"
    "Each stream_write value MUST be the EDN string of a VECTOR (it starts with \"[\" and ends with \"]\"). Each element is an op-map keyed by :op/kind.\n"
    "\n"
    "Allowed op kinds (use these spellings exactly):\n"
    "  {:op/kind :frame/clear        :color [r g b a]}            background fill, REQUIRED as first op of every frame\n"
    "  {:op/kind :draw/fill-rect     :rect [x y w h] :color [r g b a]}\n"
    "  {:op/kind :draw/stroke-rect   :rect [x y w h] :color [r g b a] :stroke-width 1.5}\n"
    "  {:op/kind :draw/fill-circle   :center [x y] :radius r :color [r g b a]}\n"
    "  {:op/kind :draw/stroke-circle :center [x y] :radius r :color [r g b a] :stroke-width 1.0}\n"
    "  {:op/kind :draw/path          :segments [[:move-to x y] [:line-to x y] [:close]] :fill [r g b a] :stroke [r g b a]}\n"
    "  {:op/kind :draw/text          :text \"...\" :position [x y] :color [r g b a] :font-size 13 :align :center}\n"
    "  {:op/kind :transform/push     :translate [tx ty] :rotate radians :scale [sx sy]}\n"
    "  {:op/kind :transform/pop}\n"
    "  {:op/kind :clip/push-rect     :rect [x y w h]}\n"
    "  {:op/kind :clip/pop}\n"
    "\n"
    "Forbidden op kinds (the validator rejects them): :frame/circle :frame/rect :frame/line :frame/path :draw/line :draw/image :target/push :target/pop. Do not invent new op kinds.\n"
    "\n" "Conventions:\n"
    "  - Canvas is 400x400, origin top-left, x grows right, y grows down. Units are pixels.\n"
    "  - Colors are 4-tuples [r g b a] of floats in [0,1]. Numbers must be plain literals (no math, no expressions, no symbols).\n"
    "  - Every :transform/push must be matched by a :transform/pop in the same frame; same for :clip/push-rect and :clip/pop.\n"
    "  - Reach for :draw/path for organic silhouettes, :transform/push with :rotate or :scale for motion, :clip/push-rect for reveal effects. A static circle with a text label is the FLOOR of what this vocabulary can express, not the ceiling.\n"
    "\n"
    "Depicting people (IMPORTANT): NEVER represent a human as a bare circle. A lone :draw/fill-circle is a head, never a whole person. Every human MUST be a STICK FIGURE made of TWO ops drawn together: a small :draw/fill-circle (radius ~9) for the head, AND a :draw/path whose segments trace the spine (neck to hips) and branch into two arms and two legs as separate :move-to/:line-to strokes. Give the path :stroke and :stroke-width ~3 so the limbs read clearly; do not give the limbs a :fill. Bend the joints and shift them between frames to convey posture, gesture, walking, reaching, or falling; a standing, a sitting, and a running figure must have visibly different limb angles.\n"
    "\n"
    "Stick-figure template (copy this shape for EVERY person, then reposition and angle the limbs):\n"
    "  {:op/kind :draw/fill-circle :center [200 120] :radius 9 :color [0.1 0.1 0.1 1.0]}\n"
    "  {:op/kind :draw/path :stroke [0.1 0.1 0.1 1.0] :stroke-width 3 :segments [[:move-to 200 129] [:line-to 200 175] [:move-to 200 142] [:line-to 178 162] [:move-to 200 142] [:line-to 222 162] [:move-to 200 175] [:line-to 184 210] [:move-to 200 175] [:line-to 216 210]]}\n"
    "(head, then spine + left arm + right arm + left leg + right leg as five strokes in one path.)\n"
    "\n"
    "Realism and scene composition:\n"
    "  - Build a setting, not just a subject: include a ground line or floor (:draw/fill-rect), a horizon or sky gradient (stacked rects of shifting color), and a few context props (trees, doorways, furniture, stars) drawn with paths and rects.\n"
    "  - Use light and depth: darker colors and larger scale for foreground, lighter/desaturated colors and smaller scale for background. Add a soft shadow under figures with a low-alpha dark ellipse-like circle.\n"
    "  - Keep proportions human: head much smaller than torso, limbs roughly torso-length. Avoid lollipop figures.\n"
    "  - Let the scene evolve frame to frame: characters move across the canvas, gesture, and react, while the background stays coherent so the eye reads continuous space.\n"
    "\n"))


(defn- build-illustrate-prompt
  [url story]
  (str illustrate-prompt
       "WebSocket URL: " url
       "\n" "\n"
       "Story to illustrate: " story))


(defn run-agent
  "Execute an autonomous Agent Tzu loop with access to a registry of streams.
   The agent can call stream_read, stream_write, ws_open, etc. via OpenAI-
   compatible function calling. Returns {:content string :messages vector
   :stream-registry atom}.

   stream-registry is an atom holding a map of string id -> dao.stream
   instance. ws_open mutates this atom; the same atom must persist across
   iterations so newly opened connections survive between user turns.

   history is an optional vector of previous message maps.
   The loop runs until the agent stops (finish_reason \"stop\"); there is no
   iteration cap, so a multi-frame illustration streams every frame however
   many round-trips that takes."
  ([prompt-txt stream-registry]
   (run-agent prompt-txt stream-registry (api-key) nil))
  ([prompt-txt stream-registry api-key]
   (run-agent prompt-txt stream-registry api-key nil))
  ([prompt-txt stream-registry api-key history]
   (when-not api-key (throw (ex-info "OPENAI_API_KEY not set" {})))
   (prn :tzu/run-agent-enter
        {:prompt prompt-txt, :history-len (count (or history []))})
   (loop [messages (conj (or history []) {"role" "user", "content" prompt-txt})
          iter 0]
     (prn :tzu/iter {:iter iter, :messages-len (count messages)})
     (let [resp
           (chat-completion messages :tools tools/stream-tools :key api-key)
           choice (get-in resp ["choices" 0])
           finish-reason (get choice "finish_reason")]
       (prn :tzu/llm-response {:iter iter, :finish-reason finish-reason})
       (case finish-reason
         "stop"
         (let [content (get-in choice ["message" "content"])
               assistant-msg (get choice "message")]
           (prn :tzu/stop
                {:content-preview
                 (when content (subs content 0 (min 80 (count content))))})
           {:content content,
            :messages (conj messages
                            (select-keys assistant-msg ["role" "content"])),
            :stream-registry stream-registry})
         "tool_calls"
         (let [assistant-msg (get choice "message")
               tool-calls (get assistant-msg "tool_calls")
               _ (prn :tzu/tool-calls
                      {:iter iter,
                       :count (count tool-calls),
                       :names (mapv #(get-in % ["function" "name"])
                                    tool-calls)})
               tool-results (mapv #(tools/execute-tool-call % stream-registry)
                                  tool-calls)]
           (recur (-> messages
                      (conj (select-keys assistant-msg
                                         ["role" "content" "tool_calls"]))
                      (into tool-results))
                  (inc iter)))
         "length"
         (throw
           (ex-info
             "LLM output truncated — response hit the token cap; reduce input"
             {}))
         (throw (ex-info (str "Unexpected finish_reason: " finish-reason)
                         {:response resp})))))))


(defn illustrate!
  "Drive an agent.tzu run that connects to the Flutter Story Demo and
   streams a postgraphics illustration of `story`. The agent calls
   ws_open then emits one stream_write per frame.

   stream-registry is an atom holding the same id->stream map run-agent
   expects; pass (atom {}) for a fresh registry.

   url defaults to ws://localhost:8765 (the Story Demo default port)."
  ([story stream-registry]
   (illustrate! story stream-registry "ws://localhost:8765"))
  ([story stream-registry url]
   (run-agent (build-illustrate-prompt url story) stream-registry)))


#?(:cljd nil
   :cljs nil
   :clj (defn -main
          [& _args]
          ;; Force System.out to autoflush so prn traces appear in real
          ;; time when stdout is a pipe (e.g. Emacs M-x shell), not a TTY.
          ;; Under a pipe the JVM block-buffers ~8 KB; autoflush makes each
          ;; print visible immediately.
          (System/setOut (java.io.PrintStream. System/out true))
          (let [registry (atom {"io" (ds/open! {:type :ringbuffer,
                                                :capacity 10})})]
            (println "Agent Tzu REPL. Type your prompt, or /exit to quit.")
            (println "Available streams in registry: " (keys @registry))
            (loop [history []]
              (print "\ntzu> ")
              (flush)
              (let [input (read-line)
                    cmd (when input (str/trim input))]
                (when (and cmd
                           (not (contains? #{"exit" "quit" "/exit" "/quit"}
                                           (str/lower-case cmd))))
                  (let [new-history
                        (try
                          (let [result
                                (run-agent cmd registry (api-key) history)]
                            (println "\nAgent:" (:content result))
                            (:messages result))
                          (catch Exception e
                            (println "\nError:" (ex-message e))
                            (when-let [edata (ex-data e)]
                              (pprint/pprint edata))
                            history))]
                    (recur new-history))))))))


(comment
  (def t (prompt "what is symmetry?"))
  (def d (text->datoms t))
  (def t2 (datoms->text d))
  (prompt->frames
    "a single static red circle in the center of a 400x400 canvas")
  (illustrate! "the egg by andy weir, 30 frames" (atom {})))
