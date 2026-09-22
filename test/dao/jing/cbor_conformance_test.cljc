(ns dao.jing.cbor-conformance-test
  "The three-host conformance gate for the DaoJing canonical CBOR codec (J3).

   `dao.jing.cbor-test` proves each host against the frozen corpus. This
   namespace proves the three hosts agree with each other and with an
   independent reading of the bytes, so three internally consistent but
   mutually incompatible codecs cannot pass:

   1. Every host builds a manifest keyed by fixture id (canonical hex, SHA-256
      and a semantic signature) and checks it against the resource. Each host
      then digests the same set of cases and asserts the digest equals the
      value derived from the resource (and a pinned literal), and prints it,
      so a disagreement between any two hosts fails a lane.
   2. A codec-independent CBOR item walker (no `dao.jing.cbor` call, no
      decoder) inspects the bytes each host produces.
   3. Pairwise injectivity and the equivalence groups are re-asserted over the
      manifest.
   4. The resource is pinned by the SHA-256 of its own bytes, and no source
      file that names it may write files.

   Skips are documented by id (README Obligations, cbor-v1.errata.md E1, E2,
   E6) and the union across hosts is checked so that every case runs on at
   least one host."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [dao.jing.cbor-test :as cbor-test]
            #?@(:cljd [] :clj [[clojure.java.io :as io]])))


(def host
  #?(:cljd :dart :clj :jvm :cljs :node))


;; ==========================================================================
;; Small portable helpers (ASCII text as bytes, per-test failure lists)
;; ==========================================================================

(def ^:private hex-digits "0123456789abcdef")


(defn- byte-hex
  [n]
  (str (subs hex-digits (quot n 16) (inc (quot n 16)))
       (subs hex-digits (rem n 16) (inc (rem n 16)))))


(defn- code-at
  [^String s i]
  #?(:cljd (.codeUnitAt s i)
     :clj (int (.charAt s (int i)))
     :cljs (.charCodeAt s i)))


(defn- ascii?
  [s]
  (every? #(< (code-at s %) 128) (range (count s))))


(defn- ascii-hex
  [s]
  (apply str (map #(byte-hex (code-at s %)) (range (count s)))))


(defn- ascii-bytes
  [s]
  (fx/hex->bytes (ascii-hex s)))


(defn- text-item
  "The CBOR text item (short strings only) for an ASCII string."
  [s]
  (str (byte-hex (+ 96 (count s))) (ascii-hex s)))


(defn- refusal-of
  "The refusal class keyword f throws, ::none, or [::other message]."
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (cbor/refusal e)
             [::other #?(:cljd (str e)
                         :clj (str (type e) " " (ex-message e))
                         :cljs (str e))]))))


(defn- check!
  "Record [id what] in failures unless ok (a failing `is` throws on Dart, so
   corpus loops never call `is` per case)."
  [failures id ok what]
  (when-not ok
    (swap! failures conj [id what])))


(defn- guarded
  "Run one case; an unexpected throw is recorded as that case's failure."
  [failures id f]
  (try (f)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (swap! failures conj
                [id (str "threw " #?(:cljd (str e) :default (ex-message e)))]))))


(defn- assert-none!
  [failures label]
  (is (empty? @failures)
      (str label ": " (count @failures) " failing; first: "
           (pr-str (take 8 @failures)))))


(defn- assert-none-of
  "assert-none! over an immutable collection of failures."
  [failures label]
  (is (empty? failures)
      (str label ": " (count failures) " failing; first: "
           (pr-str (take 8 failures)))))


;; ==========================================================================
;; The independent CBOR item walker (RFC 8949 wire format only)
;;
;; It reads the bytes as hex text, shares nothing with `dao.jing.cbor`, never
;; calls a Jing decoder, and reports what it sees: definite lengths, shortest
;; heads, no native floats, tag 39 or unknown tags, the tag 27 frame shapes,
;; the profile's numeric tag shapes, and unsigned bytewise ordering of map
;; keys and tag 258 elements. Encodings compare as lowercase hex text, which
;; orders exactly as the unsigned bytes do (a proper prefix sorts first). It
;; also enforces the ratified policy: the decimal exponent window (E3) and
;; the nesting depth limit of 128 (E4).
;;
;; Known limit: ratio reduction is checked only when numerator and denominator
;; are major type 0/1 integers below 2^32. The walker has no big integers, so
;; a ratio whose numerator or denominator is a tag 2/3 bignum is checked for
;; shape only; the frozen digests pin the reduction of those.
;; ==========================================================================

(def ^:private frame-kinds
  {"dao.jing/list" :list
   "dao.jing/keyword" :keyword
   "dao.jing/symbol" :symbol
   "dao.jing/float64" :float64
   "clojure/with-meta" :with-meta})


(def ^:private frame-kind-by-body
  (into {} (map (fn [[n k]] [(ascii-hex n) k])) frame-kinds))


(def ^:private reader-position-bodies
  (set (map ascii-hex ["line" "column" "end-line" "end-column"])))


(def ^:private max-depth
  "Errata E4: the top-level item is depth 1, each array, map or tag one more."
  128)


(defn- decimal-exponent-ok?
  "Errata E3: the exponent lies in [-2147483647, 2147483648]. A negative
   integer node holds -1 - arg."
  [e]
  (and (number? (:arg e))
       (case (:t e)
         :uint (<= (:arg e) 2147483648)
         :nint (<= (:arg e) 2147483646)
         false)))


(defn- stop!
  [msg]
  (throw (ex-info msg {::walk true})))


(defn- arg-of
  [xs]
  (reduce (fn [a x] (+ (* a 256) x)) 0 xs))


(defn- read-head
  "The head at offset i: major type, argument (a number, or :big for 2^32 and
   beyond), whether it is the shortest form, and where its bytes end."
  [{:keys [bs]} i]
  (when (>= i (count bs))
    (stop! (str "truncated before a head byte at " i)))
  (let [b (nth bs i)
        major (quot b 32)
        ai (rem b 32)
        width (case ai 24 1 25 2 26 4 27 8 0)]
    (when (<= 28 ai)
      (stop! (str "indefinite length, break or reserved head at " i)))
    (when (> (+ i 1 width) (count bs))
      (stop! (str "truncated head argument at " i)))
    (let [ab (subvec bs (inc i) (+ i 1 width))
          arg (cond (< ai 24) ai
                    (= ai 27) (if (zero? (arg-of (subvec ab 0 4)))
                                (arg-of (subvec ab 4 8))
                                :big)
                    :else (arg-of ab))]
      {:major major
       :ai ai
       :arg arg
       :end (+ i 1 width)
       :argtxt (if (number? arg) (str arg) (str "0x" (apply str (map byte-hex ab))))
       :shortest? (case ai
                    24 (>= arg 24)
                    25 (>= arg 256)
                    26 (>= arg 65536)
                    27 (= :big arg)
                    true)})))


(defn- int-node?
  [n]
  (or (contains? #{:uint :nint} (:t n))
      (and (= :tag (:t n)) (contains? #{2 3} (:n n)))))


(defn- null-node?
  [n]
  (and (= :simple (:t n)) (= 22 (:n n))))


(defn- frame-kind
  "The frame kind of a tag 27 node whose name is one of the five, else nil."
  [n]
  (when (and (= :tag (:t n)) (= 27 (:n n)))
    (let [c (:content n)
          nm (first (:items c))]
      (when (and (= :array (:t c)) (= :text (:t nm)))
        (get frame-kind-by-body (:body nm))))))


(defn- strictly-ascending?
  [hexes]
  (every? (fn [[a b]] (neg? (compare a b))) (partition 2 1 hexes)))


(defn- magnitude
  "The absolute value of a small integer node (major 0 or 1), else nil."
  [n]
  (when (number? (:arg n))
    (case (:t n) :uint (:arg n) :nint (inc (:arg n)) nil)))


(defn- magnitudes
  [a b]
  (let [x (magnitude a)
        y (magnitude b)]
    (when (and x y) [x y])))


(defn- gcd
  [a b]
  (if (zero? b) a (recur b (rem a b))))


(defn- check-frame!
  [err! node]
  (let [c (:content node)
        items (:items c)]
    (if-not (and (= :array (:t c))
                 (= 2 (count items))
                 (= :text (:t (first items)))
                 (contains? frame-kind-by-body (:body (first items))))
      (err! "tag 27 is not [name-string payload] with one of the five names")
      (let [kind (get frame-kind-by-body (:body (first items)))
            p (second items)
            pitems (:items p)]
        (case kind
          :list (when-not (= :array (:t p))
                  (err! "list payload is not an array"))
          (:keyword :symbol)
          (when-not (and (= :array (:t p))
                         (= 2 (count pitems))
                         (or (null-node? (first pitems))
                             (= :text (:t (first pitems))))
                         (= :text (:t (second pitems))))
            (err! (str (name kind) " payload is not [ns-or-null name-text]")))
          :float64
          (if-not (and (= :bytes (:t p)) (= 16 (count (:body p))))
            (err! "float64 payload is not an 8 byte string")
            (let [hx (:body p)
                  exp-ones? (and (contains? #{"7f" "ff"} (subs hx 0 2))
                                 (= "f" (subs hx 2 3)))
                  infinity? (= (apply str (repeat 13 "0")) (subs hx 3))]
              (when (and exp-ones? (not infinity?) (not= "7ff8000000000000" hx))
                (err! "a NaN that is not the canonical 7ff8000000000000"))))
          :with-meta
          (if-not (and (= :array (:t p))
                       (= 2 (count pitems))
                       (= :map (:t (first pitems))))
            (err! "with-meta payload is not [meta-map value]")
            (let [m (first pitems)
                  v (second pitems)]
              (when (empty? (:entries m))
                (err! "empty metadata was not omitted"))
              (when-not (or (contains? #{:array :map} (:t v))
                            (and (= :tag (:t v)) (= 258 (:n v)))
                            (contains? #{:list :symbol} (frame-kind v)))
                (err! "with-meta value is not a collection or symbol"))
              (doseq [[k _] (:entries m)
                      :when (= :keyword (frame-kind k))
                      :let [kp (second (:items (:content k)))
                            [kns kname] (:items kp)]]
                (when (and (null-node? kns)
                           (contains? reader-position-bodies (:body kname)))
                  (err! "a reader-position key was not stripped from metadata"))))))))))


(defn- check-tag!
  [err! node]
  (let [n (:n node)
        c (:content node)]
    (cond
      (= 39 n) (err! "tag 39 is never produced")
      (= 27 n) (check-frame! err! node)
      (contains? #{2 3} n)
      (when-not (and (= :bytes (:t c))
                     (>= (count (:body c)) 18)
                     (not= "00" (subs (:body c) 0 2)))
        (err! "bignum content is not a minimal magnitude beyond 64 bits"))
      (= 4 n)
      (cond
        (not (and (= :array (:t c))
                  (= 2 (count (:items c)))
                  (every? int-node? (:items c))))
        (err! "decimal is not [exponent mantissa]")
        (not (decimal-exponent-ok? (first (:items c))))
        (err! "decimal exponent is outside [-2147483647, 2147483648]"))
      (= 30 n)
      (let [[num den] (:items c)]
        (when-not (and (= :array (:t c))
                       (= 2 (count (:items c)))
                       (int-node? num)
                       (or (and (= :uint (:t den)) (not= "u0" (:diag den)))
                           (and (= :tag (:t den)) (= 2 (:n den)))))
          (err! "ratio is not [numerator positive-denominator]"))
        (when-let [[a b] (and (= :array (:t c)) (= 2 (count (:items c)))
                              (magnitudes num den))]
          (when-not (= 1 (gcd a b))
            (err! "ratio is not reduced"))))
      (= 258 n)
      (if-not (= :array (:t c))
        (err! "set content is not an array")
        (when-not (strictly-ascending? (map :hex (:items c)))
          (err! "set elements are not strictly ascending by unsigned bytes")))
      :else (err! (str "tag " n " is outside the profile")))))


(defn- read-item
  "[node end] for the item at offset i. Non-fatal findings go to (:errs ctx);
   a structural failure stops the walk."
  [{:keys [bs hex errs] :as ctx} i depth]
  (when (> depth max-depth)
    (stop! (str "nesting deeper than " max-depth)))
  (let [{:keys [major ai arg end argtxt shortest?]} (read-head ctx i)
        enc #(subs hex (* 2 i) (* 2 %))
        err! (fn [m] (swap! errs conj (str "@" i " " m)))
        room (- (count bs) end)
        read-n (fn [n from]
                 (when (or (= :big n) (> n room))
                   (stop! (str "length " argtxt " beyond the input at " i)))
                 (loop [j from k n acc []]
                   (if (zero? k)
                     [acc j]
                     (let [[node e] (read-item ctx j (inc depth))]
                       (recur e (dec k) (conj acc node))))))]
    (when-not shortest?
      (err! "head is not the shortest form"))
    (case major
      0 [{:t :uint :arg arg :hex (enc end) :diag (str "u" argtxt)} end]
      1 [{:t :nint :arg arg :hex (enc end) :diag (str "n" argtxt)} end]
      (2 3) (do (when (or (= :big arg) (> arg room))
                  (stop! (str "string length " argtxt " beyond the input at " i)))
                (let [e (+ end arg)
                      body (subs hex (* 2 end) (* 2 e))]
                  [{:t (if (= 2 major) :bytes :text)
                    :hex (enc e)
                    :body body
                    :diag (str (if (= 2 major) "h'" "t'") body "'")}
                   e]))
      4 (let [[items e] (read-n arg end)]
          [{:t :array
            :hex (enc e)
            :items items
            :diag (str "[" (str/join "," (map :diag items)) "]")}
           e])
      5 (let [[flat e] (when-not (= :big arg) (read-n (* 2 arg) end))
              _ (when (= :big arg) (stop! (str "map length beyond the input at " i)))
              entries (mapv vec (partition 2 flat))
              node {:t :map
                    :hex (enc e)
                    :entries entries
                    :diag (str "{"
                               (str/join "," (map (fn [[k v]] (str (:diag k) ":" (:diag v)))
                                                  entries))
                               "}")}]
          (when-not (strictly-ascending? (map (comp :hex first) entries))
            (err! "map keys are not strictly ascending by unsigned bytes"))
          [node e])
      6 (do (when (= :big arg)
              (stop! (str "tag number beyond 2^32 at " i)))
            (let [[content e] (read-item ctx end (inc depth))
                  node {:t :tag
                        :n arg
                        :content content
                        :hex (enc e)
                        :diag (str "T" arg "(" (:diag content) ")")}]
              (check-tag! err! node)
              [node e]))
      7 (do (cond
              (<= 25 ai 27) (err! "native CBOR float")
              (contains? #{20 21 22} ai) nil
              :else (err! (str "unsupported simple value, head additional info " ai)))
            [{:t :simple
              :n ai
              :hex (enc end)
              :diag (case ai 20 "false" 21 "true" 22 "null" (str "simple" ai))}
             end]))))


(defn walk
  "Walk one hex-encoded CBOR item: {:errors [..] :diag \"..\"}. :errors is
   empty when the bytes are one well-formed item of the DaoJing profile."
  [hex]
  (let [errs (atom [])
        bs (fx/hex->ints hex)
        ctx {:bs bs :hex hex :errs errs}]
    (try
      (let [[node end] (read-item ctx 0 1)]
        (when (< end (count bs))
          (swap! errs conj (str "trailing bytes after the item at " end)))
        {:errors @errs :diag (:diag node)})
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        {:errors (conj @errs (str "stopped: " #?(:cljd (str e) :default (ex-message e))))
         :diag nil}))))


;; ==========================================================================
;; What is documented as skipped, by id
;; ==========================================================================

(def not-buildable
  "Cases a host cannot build at all (README Obligations; cbor-v1.errata.md E2
   for the ClojureScript slash-crossed keys). Pinned here so a silent new skip
   in the per-host runner fails this gate."
  {:jvm #{"num/js-unsafe-integer"}
   :node #{"host/character" "host/nested-in-vector" "coll/map-both-slash-keywords"}
   :dart #{"host/character" "host/nested-in-vector" "num/js-unsafe-integer"}})


(def errata-path "test/resources/dao/jing/cbor-v1.errata.md")


(defn- errata-rows
  "The [section id column ...] cells of every collapse table row, in file
   order. A row is a line starting with a backticked coll/ id; its cells are
   the non-empty text between the pipes. The section is :e1 before the
   errata's E6 heading and :e6 after it, up to the next `## ` heading, after
   which rows belong to no section the gate reads (:after)."
  [text]
  (loop [lines (re-seq #"[^\n]+" text)
         section :e1
         rows []]
    (if-let [line (first lines)]
      (cond
        (str/starts-with? line "## E6") (recur (rest lines) :e6 rows)
        (and (= :e6 section) (str/starts-with? line "## "))
        (recur (rest lines) :after rows)
        (str/starts-with? line "| `coll/")
        (recur (rest lines)
               section
               (conj rows
                     (into [section]
                           (map #(str/replace (str/trim %) "`" ""))
                           (re-seq #"[^|]+" line))))
        :else (recur (rest lines) section rows))
      rows)))


(defn errata-merged
  "{host #{ids}} of the N/A (merged) rows of cbor-v1.errata.md: E1 (JVM and
   Node columns) and E6 (the Dart column), read from the errata itself."
  []
  (let [rows (errata-rows (fx/read-path errata-path))
        rows1 (filter #(= :e1 (first %)) rows)
        rows6 (filter #(= :e6 (first %)) rows)
        na? #(and (string? %) (str/starts-with? % "N/A"))]
    {:jvm (set (for [[_ id jvm _] rows1 :when (na? jvm)] id))
     :node (set (for [[_ id _ node] rows1 :when (na? node)] id))
     :dart (set (for [[_ id dart] rows6 :when (na? dart)] id))
     :rows [(count rows1) (count rows6)]}))


(def ^:private errata* (delay (errata-merged)))


(defn documented-skips
  "The ids a host is documented to skip: not-buildable plus N/A (merged)."
  [h]
  (into (get not-buildable h) (get @errata* h)))


(defn- skipped-anywhere
  []
  (reduce into #{} (map documented-skips [:jvm :node :dart])))


;; ==========================================================================
;; The per-host manifest
;; ==========================================================================

(defn- refusal-name
  [got]
  (if (keyword? got) (name got) (str got)))


(defn- canonical-entry
  [failures c]
  (let [id (get c "id")
        v (fx/input->value (get c "input"))
        bs (cbor/encode v)
        hex (fx/bytes->hex bs)
        sha (jing/sha256-bytes bs)
        back (cbor/decode (fx/hex->bytes (get c "hex")))
        rt? (= hex (fx/bytes->hex (cbor/encode back)))
        w (walk hex)]
    (check! failures id (= (get c "hex") hex) "hex differs from the resource")
    (check! failures id (= (get c "sha256") sha) "SHA-256 differs from the resource")
    (check! failures id rt? "decoding the frozen hex then encoding differs")
    {:kind "canonical"
     :hex hex
     :sha sha
     :sig (str "ok|rt=" (if rt? 1 0) "|" (:diag w))
     :walk-errors (:errors w)}))


(defn- encode-refusal-entry
  "An entry, or {:skip :merged} when the host merged the members before the
   codec saw them, or {:skip :not-constructible}."
  [failures c]
  (let [id (get c "id")
        input (get c "input")
        want (keyword (get c "refusal"))
        built (refusal-of (fn [] (fx/input->value input)))
        v (when (= ::none built) (fx/input->value input))
        n (fx/dsl-member-count input)
        entry (fn [got]
                (check! failures id (= want got) [:refused got :wanted want])
                {:kind "encode-refusal"
                 :hex "-"
                 :sha "-"
                 :sig (str "refuse|" (refusal-name got))})]
    (cond
      (not= ::none built) (entry built)
      (not (fx/constructible? v)) {:skip :not-constructible}
      (and n (< (count v) n)) {:skip :merged}
      :else (entry (refusal-of (fn [] (cbor/encode v)))))))


(defn- decode-refusal-entry
  [failures c]
  (let [id (get c "id")
        bs (fx/hex->bytes (get c "hex"))
        want (keyword (get c "refusal"))
        got (refusal-of (fn [] (cbor/decode bs)))
        sha (jing/sha256-bytes bs)]
    (check! failures id (= want got) [:refused got :wanted want])
    (check! failures id (= (get c "sha256") sha) "SHA-256 of the offered bytes differs")
    {:kind "decode-refusal"
     :hex (get c "hex")
     :sha sha
     :sig (str "refuse|" (refusal-name got))}))


(defn build-manifest
  "{:entries {id entry} :skips {id reason} :failures [[id what] ..]}: every
   case this host can run, run against the shared resource."
  []
  (let [failures (atom [])
        entries (atom {})
        skips (atom {})]
    (doseq [c (fx/cases)
            :let [id (get c "id")
                  kind (get c "kind")]]
      (if (contains? (get not-buildable host) id)
        (swap! skips assoc id :not-buildable)
        (guarded failures id
                 #(let [r (case kind
                            "canonical" (canonical-entry failures c)
                            "encode-refusal" (encode-refusal-entry failures c)
                            "decode-refusal" (decode-refusal-entry failures c))]
                    (if-let [why (:skip r)]
                      (swap! skips assoc id why)
                      (swap! entries assoc id r))))))
    {:entries @entries :skips @skips :failures @failures}))


(def ^:private manifest* (delay (build-manifest)))


(defn manifest
  []
  @manifest*)


;; ==========================================================================
;; The manifest digests
;; ==========================================================================

(defn- resource-entry
  "The entry the resource implies, read by the independent walker."
  [c]
  (case (get c "kind")
    "canonical" {:hex (get c "hex")
                 :sha (get c "sha256")
                 :sig (str "ok|rt=1|" (:diag (walk (get c "hex"))))}
    "encode-refusal" {:hex "-" :sha "-" :sig (str "refuse|" (get c "refusal"))}
    "decode-refusal" {:hex (get c "hex")
                      :sha (get c "sha256")
                      :sig (str "refuse|" (get c "refusal"))}))


(defn- common-ids
  "Ids of one case kind that every host runs: all of the kind minus the union
   of documented skips."
  [kind]
  (let [skipped (skipped-anywhere)]
    (vec (sort (remove skipped (map #(get % "id") (fx/cases-of-kind kind)))))))


(defn- digest-lines
  [entries ids]
  (str/join "\n"
            (map (fn [id]
                   (let [e (get entries id)]
                     (str id "\t" (:hex e) "\t" (:sha e) "\t" (:sig e))))
                 (sort ids))))


(defn- digest-of
  [entries ids]
  (let [text (digest-lines entries ids)]
    (when (ascii? text)
      (jing/sha256-bytes (ascii-bytes text)))))


(defn- resource-entries
  []
  (into {} (map (fn [c] [(get c "id") (resource-entry c)])) (fx/cases)))


(def canonical-digest-pin
  "Pinned literal of the canonical manifest digest (derived from the resource
   by the walker; see the printed CONFORMANCE-DIGEST lines)."
  "6d1c64719d96c11f79d575bd3122ad1bba1ba9efcc56dce63b195423575cf2cb")


(def refusal-digest-pin
  "Pinned literal of the refusal manifest digest."
  "1b929147af85f703c700a1f0d9cfbe74c55e5e28302a862086516fe749ec1b82")


;; ==========================================================================
;; Tests: the manifest against the resource
;; ==========================================================================

(deftest manifest-agrees-with-the-resource-for-every-case-this-host-runs
  (assert-none-of (:failures (manifest)) "manifest against the resource"))


(deftest every-case-is-run-or-skipped-by-a-documented-id
  (let [failures (atom [])
        m (manifest)
        ids (map #(get % "id") (fx/cases))
        run (set (keys (:entries m)))
        skipped (set (keys (:skips m)))
        merged (set (keep (fn [[id why]] (when (= :merged why) id)) (:skips m)))
        unbuildable (set (keep (fn [[id why]] (when (not= :merged why) id)) (:skips m)))]
    (check! failures "ids" (= 372 (count ids) (count (set ids)))
            "372 distinct case ids")
    (check! failures "coverage" (= (set ids) (into run skipped))
            "no case is silently dropped")
    (check! failures "disjoint" (empty? (filter run skipped))
            "run and skipped are disjoint")
    (check! failures "skips" (= (documented-skips host) skipped)
            [:observed (sort skipped) :documented (sort (documented-skips host))])
    (check! failures "merged" (= (get @errata* host) merged)
            [:merged-not-the-errata-na-column (sort merged)])
    (check! failures "not-buildable" (= (get not-buildable host) unbuildable)
            [:skipped-not-buildable (sort unbuildable)])
    (println (str "CONFORMANCE " (name host)
                  " cases=" (count ids)
                  " run=" (count run)
                  " skipped=" (pr-str (vec (sort skipped)))))
    (assert-none! failures "skip accounting")))


(deftest documented-skips-match-the-errata-and-leave-no-case-unrun
  (let [failures (atom [])
        errata @errata*
        ids (set (map #(get % "id") (fx/cases)))
        kinds (into {} (map (fn [c] [(get c "id") (get c "kind")])) (fx/cases))
        never-run (set (filter (fn [id]
                                 (every? #(contains? (documented-skips %) id)
                                         [:jvm :node :dart]))
                               ids))
        twin (fx/case-by-id "mal/collapse-vector-list-set")]
    ;; the errata tables have the nine rows E1 and E6 list
    (check! failures "errata rows" (= [9 9] (:rows errata)) (:rows errata))
    ;; the N/A columns are exactly the errata E1 and E6 columns
    (check! failures "jvm column"
            (= #{"coll/set-signed-zero-collapse" "coll/set-decimal-scale-collapse"
                 "coll/set-vector-list-collapse" "coll/map-integer-width-duplicate"}
               (:jvm errata))
            (:jvm errata))
    (check! failures "node column"
            (= #{"coll/set-vector-list-collapse" "coll/set-nan-payload-duplicate"
                 "coll/map-nan-payload-duplicate"}
               (:node errata))
            (:node errata))
    (check! failures "dart column"
            (= #{"coll/set-signed-zero-collapse" "coll/set-vector-list-collapse"}
               (:dart errata))
            (:dart errata))
    ;; the per-host runner's tables equal the documented tables
    (doseq [h [:jvm :node :dart]]
      (check! failures (name h)
              (= (get not-buildable h) (set (keys (get cbor-test/skip-by-id h))))
              :runner-skip-by-id-differs)
      (check! failures (name h)
              (= (get errata h) (get cbor-test/collapse-not-applicable h))
              :runner-collapse-table-differs))
    ;; the E2 slash-crossed skip is documented in the errata
    (check! failures "E2"
            (str/includes? (fx/read-path errata-path) "coll/map-both-slash-keywords")
            :e2-not-in-the-errata)
    ;; every documented id is a corpus case, and the merged ones are encode refusals
    (doseq [h [:jvm :node :dart]
            id (documented-skips h)]
      (check! failures id (contains? ids id) :documented-id-not-in-the-corpus))
    (check! failures "merged kinds"
            (every? #(= "encode-refusal" (get kinds %))
                    (reduce into #{} (vals (select-keys errata [:jvm :node :dart]))))
            :merged-id-is-not-an-encode-refusal)
    ;; E1 and E6 list coll/set-vector-list-collapse as N/A (merged) on the
    ;; JVM, Node and Dart alike; its decode-side twin covers the same refusal
    ;; on every host, so it is the one case that runs nowhere
    (check! failures "never run" (= #{"coll/set-vector-list-collapse"} never-run)
            (sort never-run))
    (check! failures "twin kind" (= "decode-refusal" (get twin "kind")) :twin-kind)
    (check! failures "twin class" (= "equality-collapse" (get twin "refusal"))
            :twin-class)
    (check! failures "case class"
            (= "equality-collapse"
               (get (fx/case-by-id "coll/set-vector-list-collapse") "refusal"))
            :case-class)
    ;; the union of skips leaves the expected common cases
    (check! failures "canonical count" (= 209 (count (fx/cases-of-kind "canonical")))
            :canonical-count)
    (check! failures "canonical skips"
            (= #{"coll/map-both-slash-keywords"}
               (set (filter #(= "canonical" (get kinds %)) (skipped-anywhere))))
            :canonical-skip-union)
    (check! failures "canonical common" (= 208 (count (common-ids "canonical")))
            :canonical-cases-run-on-all-three-hosts)
    (check! failures "encode-refusal count"
            (= 31 (count (fx/cases-of-kind "encode-refusal")))
            :encode-refusal-count)
    (check! failures "decode-refusal count"
            (= 132 (count (fx/cases-of-kind "decode-refusal")))
            :decode-refusal-count)
    (assert-none! failures "documented skips")))


;; ==========================================================================
;; Tests: the digests that compare the hosts with each other
;; ==========================================================================

(deftest canonical-manifest-digest-equals-the-value-derived-from-the-resource
  (let [ids (common-ids "canonical")
        m (manifest)
        mine (digest-of (:entries m) ids)
        derived (digest-of (resource-entries) ids)]
    (println (str "CONFORMANCE-DIGEST " (name host) " canonical n=" (count ids)
                  " " mine))
    (is (every? #(contains? (:entries m) %) ids)
        "this host ran every common canonical case")
    (is (string? derived))
    (is (= derived mine) "this host's manifest digest equals the resource-derived digest")
    (is (= canonical-digest-pin mine) "the pinned literal")))


(deftest refusal-manifest-digest-equals-the-value-derived-from-the-resource
  (let [ids (into (common-ids "encode-refusal") (common-ids "decode-refusal"))
        m (manifest)
        mine (digest-of (:entries m) ids)
        derived (digest-of (resource-entries) ids)]
    (println (str "CONFORMANCE-DIGEST " (name host) " refusal n=" (count ids)
                  " " mine))
    (is (every? #(contains? (:entries m) %) ids)
        "this host ran every common refusal case")
    (is (= derived mine) "this host's refusal digest equals the resource-derived digest")
    (is (= refusal-digest-pin mine) "the pinned literal")))


;; ==========================================================================
;; Tests: the independent raw CBOR-tree inspection
;; ==========================================================================

(deftest produced-bytes-pass-the-raw-cbor-inspection
  (let [failures (atom [])]
    (doseq [[id e] (:entries (manifest))
            :when (= "canonical" (:kind e))]
      (check! failures id (empty? (:walk-errors e)) (:walk-errors e)))
    (assert-none! failures "raw inspection of the bytes this host produced")))


(deftest the-frozen-canonical-bytes-pass-the-raw-cbor-inspection
  (let [failures (atom [])]
    (doseq [c (fx/cases-of-kind "canonical")
            :let [w (walk (get c "hex"))]]
      (check! failures (get c "id") (empty? (:errors w)) (:errors w)))
    (assert-none! failures "raw inspection of the frozen bytes")))


(defn- flagged?
  [hex]
  (boolean (seq (:errors (walk hex)))))


(deftest the-walker-flags-what-it-must
  (let [bad [["indefinite array" "9f01ff"]
             ["non-shortest uint" "1800"]
             ["non-shortest length" "9800"]
             ["native float16" "f93c00"]
             ["native float32" "fa3f800000"]
             ["native float64" "fb3ff0000000000000"]
             ["tag 39" "d82760"]
             ["tag 5" "c500"]
             ["undefined" "f7"]
             ["simple 255" "f8ff"]
             ["trailing bytes" "0000"]
             ["truncated head" "18"]
             ["truncated string" "6261"]
             ["map keys descending" "a202020101"]
             ["map duplicate keys" "a201010102"]
             ["set descending" "d9010282" "0201"]
             ["frame name unknown" (str "d81b82" (text-item "x") "80")]
             ["frame arity" "d81b81" "80"]
             ["frame not an array" "d81b01"]
             ["float64 wrong length"
              (str "d81b82" (text-item "dao.jing/float64") "43000000")]
             ["float64 non-canonical NaN"
              (str "d81b82" (text-item "dao.jing/float64") "487ff8000000000001")]
             ["keyword ns not text or null"
              (str "d81b82" (text-item "dao.jing/keyword") "820161" "61")]
             ["with-meta on a keyword"
              (str "d81b82" (text-item "clojure/with-meta") "82a1" "01" "01"
                   "d81b82" (text-item "dao.jing/keyword") "82f66161")]
             ["bignum with leading zero" "c2490000000000000001"]
             ["bignum that fits 64 bits" "c2480000000000000001"]
             ["decimal exponent 2^31 + 1" "c4821a8000000101"]
             ["decimal exponent -2^31" "c4823a7fffffff01"]
             ["decimal exponent is a bignum" "c482c24901000000000000000001"]
             ["ratio not reduced" "d81e820406"]
             ["nesting one past the limit" (str (apply str (repeat 128 "81")) "80")]]
        good [["empty vector" "80"]
              ["decimal exponent 2^31" "c4821a8000000001"]
              ["decimal exponent -(2^31 - 1)" "c4823a7ffffffe01"]
              ["ratio reduced" "d81e820203"]
              ["nesting exactly at the limit" (str (apply str (repeat 127 "81")) "80")]
              ["empty set" "d9010280"]
              ["sorted map" "a201010202"]
              ["sorted set" "d901028201" "02"]
              ["float64 one"
               (str "d81b82" (text-item "dao.jing/float64") "483ff0000000000000")]]
        failures (atom [])]
    (doseq [[what & parts] bad
            :let [hex (apply str parts)]]
      (check! failures what (flagged? hex) [:not-flagged hex]))
    (doseq [[what & parts] good
            :let [hex (apply str parts)]]
      (check! failures what (not (flagged? hex)) [:flagged hex (walk hex)]))
    (assert-none! failures "walker self-test")))


(deftest the-walker-independently-flags-the-refusal-classes-it-can-see
  ;; Each class below is a wire-level defect the walker checks without the
  ;; codec; every frozen decode-refusal case of the class must be flagged.
  (let [seen #{"native-float" "identifier-tag-39" "unknown-frame-name" "unknown-tag"
               "trailing-data" "unsupported-simple" "non-canonical" "duplicate-key"
               "duplicate-element"}
        failures (atom [])]
    (doseq [c (fx/cases-of-kind "decode-refusal")
            :when (contains? seen (get c "refusal"))]
      (check! failures (get c "id") (flagged? (get c "hex"))
              [:walker-accepts (get c "refusal")]))
    (assert-none! failures "walker against the frozen decode refusals")))


;; ==========================================================================
;; Tests: pairwise injectivity and equivalence groups on produced bytes
;; ==========================================================================

(deftest injectivity-and-groups-hold-on-the-bytes-this-host-produced
  (let [failures (atom [])
        entries (:entries (manifest))
        cases (filter #(contains? entries (get % "id")) (fx/cases-of-kind "canonical"))
        hex-of #(get-in entries [(get % "id") :hex])
        sha-of #(get-in entries [(get % "id") :sha])]
    (testing "same bytes if and only if the same equivalence group"
      (doseq [[hx members] (group-by hex-of cases)
              :let [groups (set (map #(get % "equivalence") members))]]
        (check! failures hx
                (or (= 1 (count members))
                    (and (= 1 (count groups)) (every? some? groups)))
                [:same-bytes-in-different-groups (map #(get % "id") members)]))
      (doseq [[g members] (group-by #(get % "equivalence") cases)
              :when g]
        (check! failures g (= 1 (count (set (map hex-of members))))
                :equivalence-group-differs)))
    (testing "retained-distinction groups differ pairwise in bytes and address"
      (doseq [[g members] (reduce (fn [acc c]
                                    (reduce #(update %1 %2 (fnil conj []) c)
                                            acc (get c "distinct")))
                                  {} cases)]
        (check! failures g (= (count members) (count (set (map hex-of members))))
                :distinction-group-bytes-collide)
        (check! failures g (= (count members) (count (set (map sha-of members))))
                :distinction-group-addresses-collide)))
    (testing "the address is a function of the bytes"
      (doseq [[hx members] (group-by hex-of cases)]
        (check! failures hx (= 1 (count (set (map sha-of members)))) :one-address-per-bytes)))
    (assert-none! failures "injectivity and groups")))


;; ==========================================================================
;; Tests: the resource is immutable
;; ==========================================================================

(def resource-sha256
  "SHA-256 of cbor-v1.json's bytes: the resource frozen at commit f5f71e95."
  "c8f5ef38124110bca48d0c7e5596eef00028ecc66c7d032e4e12bb1477f97351")


(deftest the-resource-bytes-are-the-frozen-bytes
  (let [text (fx/read-text)]
    (is (ascii? text) "the corpus is pure ASCII, so its text is its bytes")
    (is (= resource-sha256 (jing/sha256-bytes (ascii-bytes text)))
        "cbor-v1.json is byte-identical to the digest pinned at freeze")))


#?(:cljd nil
   :clj
   (do
     (def ^:private write-markers
       ;; Spelled in pieces so this file is not itself a match.
       [(str "sp" "it") (str "write" "AsString") (str "write" "AsBytes")
        (str "write" "File") (str "append" "File") (str "open" "Write")
        (str "io/" "writer") (str "io/" "output-stream") (str "File" "Writer")
        (str "File" "OutputStream") (str "Files/" "write") (str "Files/" "delete")
        (str "Files/" "move") (str "Files/" "copy") (str "delete" "-file")
        (str "delete" "Sync") (str "rename" "To") (str "io/" "copy")])


     (def ^:private python-write-markers
       [(str "os." "remove") (str "os." "unlink") (str "os." "rename")
        (str "os." "replace") (str "write" "_text") (str "write" "_bytes")
        (str "shu" "til")])


     (def ^:private shell-write-markers
       ;; A redirect, tee, cp, mv or rm on a line that names the corpus.
       [">" "tee" "cp " "mv " "rm "])


     (def ^:private resource-names
       [(str "cbor" "-v1") (str "cbor" "-fixtures") (str "cbor" "_fixtures")])


     (defn- scan-files
       []
       (->> (concat (file-seq (io/file "test")) (file-seq (io/file "src/dev"))
                    [(io/file "bb.edn")])
            (filter #(.isFile ^java.io.File %))
            (map str)
            (remove #(str/includes? % "cljd-out"))
            (remove #(str/includes? % "test/resources/"))
            (filter #(re-find #"\.(clj|cljc|cljd|cljs|edn|py|sh)$" %))))


     (defn- shell-like?
       [f]
       (or (str/ends-with? f ".sh") (str/ends-with? f "bb.edn")))


     (defn- offences
       "[file marker] pairs: a file that names the corpus and carries a write
        marker for its language; for shell files and bb.edn, a single line that
        names the corpus together with a shell write marker."
       [f]
       (let [text (slurp f)
             names? #(some (fn [n] (str/includes? % n)) resource-names)
             markers (concat write-markers
                             (when (str/ends-with? f ".py") python-write-markers))]
         (concat
           (when (names? text)
             (for [m markers :when (str/includes? text m)] [f m]))
           (when (shell-like? f)
             (for [line (str/split-lines text)
                   :when (names? line)
                   m shell-write-markers
                   :when (str/includes? line m)]
               [f m line])))))


     (deftest no-source-file-that-names-the-resource-writes-files
       (let [files (scan-files)
             offenders (mapcat offences files)]
         (is (seq files) "the scan saw source files")
         (is (some #(str/ends-with? % "dao/jing/cbor_fixtures.cljc") files)
             "the scan reaches the fixture loader")
         (is (some #(str/ends-with? % "bb.edn") files) "the scan reaches bb.edn")
         (is (empty? offenders) (pr-str offenders))))


     (deftest the-scan-flags-write-attempts-it-must-catch
       ;; Self-test of `offences` on throwaway files outside the repository.
       (let [dir (io/file (System/getProperty "java.io.tmpdir") "cbor-scan-selftest")
             probe (fn [name body]
                     (.mkdirs dir)
                     (let [f (io/file dir name)]
                       (doto (java.io.PrintWriter. f)
                         (.print ^String body)
                         (.close))
                       (try (boolean (seq (offences (str f))))
                            (finally (.delete f)))))
             corpus (str "test/resources/dao/jing/" "cbor" "-v1.json")]
         (is (probe "a.py" (str "import os\nos." "remove(\"" corpus "\")\n")))
         (is (probe "b.py" (str "p = \"" corpus "\"\np.write" "_text(\"x\")\n")))
         (is (probe "c.sh" (str "cp x " corpus "\n")))
         (is (probe "d.sh" (str "echo x > " corpus "\n")))
         (is (probe "e.clj" (str "(sp" "it \"" corpus "\" \"x\")\n")))
         (is (not (probe "f.sh" "echo hello > /dev/null\n"))
             "a shell line that does not name the corpus is fine")
         (is (not (probe "g.sh" (str "cat " corpus "\n")))
             "reading the corpus is fine")))


     (deftest the-generator-cannot-overwrite-the-resource
       (let [text (slurp "test/resources/dao/jing/cbor-v1.generate.py")]
         (is (nil? (re-find #"open\([^)]*[\"'][wax+]+b?[\"']" text))
             "the generator opens no file for writing")
         (is (empty? (filter #(str/includes? text %) python-write-markers))
             "the generator calls no python file mutation")
         (is (not (str/includes? text "pathlib")))
         (is (str/includes? text "sys.stdout.write(text)")
             "a new corpus goes to stdout")))


     (defn- resource-pin-holds?
       []
       (let [text (fx/read-text)]
         (and (ascii? text)
              (= resource-sha256 (jing/sha256-bytes (ascii-bytes text))))))


     ;; One run cannot both modify the resource and pass: after every test of
     ;; this namespace has run, the pin is asserted again. The JVM is the one
     ;; host where a test in the same process could write the file; the other
     ;; hosts read it through read-only calls and have no fixture hook here.
     (clojure.test/use-fixtures
       :once
       (fn [run-tests]
         (run-tests)
         (is (resource-pin-holds?)
             "cbor-v1.json is still the frozen resource after the whole run")))))
