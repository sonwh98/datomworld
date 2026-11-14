(ns yang.correspondence-text
  "JVM text capture pilot. Acceptance still requires a pinned dependency closure
   and per-compilation replay of every claimed artifact."
  (:require [clojure.edn :as edn]))


(def profile
  {:yang.correspondence.text/schema 1
   :host :jvm
   :clojure-version (clojure-version)
   :java-version (System/getProperty "java.version")
   :java-vendor (System/getProperty "java.vendor")
   :encoding :utf-8
   :normalization :none
   :printer 'clojure.core/pr-str
   :printer-options {:meta true :length nil :level nil :readably true
                     :dup false :namespace-maps false}
   :reader 'clojure.edn/read
   :reader-options {:values 1 :eof-required true :ambient-readers false
                    :tags :unsupported}
   :domain :edn-with-readable-metadata
   :fidelity-rule :fixed-jvm-integer-wrappers-v1})


(defn- kind
  [x]
  (cond (map? x) :map (vector? x) :vector (set? x) :set
        (seq? x) :list (sequential? x) :sequence :else :scalar))


(def ^:private fixed-integer-classes #{Byte Short Integer Long})


(def ^:private supported-numeric-classes
  #{Byte Short Integer Long clojure.lang.BigInt java.math.BigInteger
    java.math.BigDecimal clojure.lang.Ratio Double Float})


(defn- numeric-faithful?
  [a b]
  (and (number? a) (number? b)
       (let [ca (class a) cb (class b)]
         (cond
           (and (contains? fixed-integer-classes ca)
                (contains? fixed-integer-classes cb)) (= (long a) (long b))
           (and (= ca cb) (contains? supported-numeric-classes ca)) (.equals a b)
           :else false))))


(defn- mismatch
  [a b path]
  (cond
    (not= (kind a) (kind b)) {:path path :reason :kind :actual (kind a) :read (kind b)}
    (not= (some? (meta a)) (some? (meta b))) {:path path :reason :metadata-presence}
    :else
    (or (when (some? (meta a)) (mismatch (meta a) (meta b) (conj path :metadata)))
        (case (kind a)
          :map (if (not= (count a) (count b))
                 {:path path :reason :count}
                 (some identity
                       (map-indexed (fn [i [[ak av] [bk bv]]]
                                      (or (mismatch ak bk (conj path [:key i]))
                                          (mismatch av bv (conj path [:value i ak]))))
                                    (map vector a b))))
          (:vector :list :sequence :set)
          (if (not= (count a) (count b))
            {:path path :reason :count}
            (some identity (map-indexed (fn [i [x y]] (mismatch x y (conj path i)))
                                        (map vector a b))))
          :scalar (when-not (if (or (number? a) (number? b))
                              (numeric-faithful? a b)
                              (= a b))
                    {:path path :reason :scalar :actual (str (class a)) :read (str (class b))})))))


(defn faithful?
  "Compare syntax kind, collection iteration order, scalar numeric type and
   metadata recursively. This is deliberately stricter than Clojure equality."
  [a b]
  (nil? (mismatch a b [])))


(defn- refusal
  [compilation stage cause]
  (ex-info "Text reconstruction refused"
           {:rule :correspondence/text :compilation compilation :stage stage} cause))


(defn read-one!
  "Read one EDN value, refusing all tagged values and any trailing value."
  [compilation source]
  (try
    (let [reader (java.io.PushbackReader. (java.io.StringReader. source))
          eof (Object.)
          reject-tag (fn [tag _] (throw (ex-info "Unsupported tag" {:tag tag})))
          reject-builtin (fn [_] (throw (ex-info "Unsupported tag" {})))
          options {:eof eof :readers {'inst reject-builtin 'uuid reject-builtin}
                   :default reject-tag}
          value (edn/read options reader)
          trailing (edn/read options reader)]
      (when (or (identical? eof value) (not (identical? eof trailing)))
        (throw (ex-info "Expected exactly one value" {})))
      value)
    (catch Exception e (throw (refusal compilation :read e)))))


(defn capture!
  "Print and immediately read back an actual input, refusing lossy captures."
  [compilation value]
  (let [source (try (binding [*print-meta* true *print-length* nil *print-level* nil
                              *print-readably* true *print-dup* false
                              *print-namespace-maps* false]
                      (pr-str value))
                    (catch Exception e (throw (refusal compilation :print e))))
        restored (read-one! compilation source)]
    (when-let [difference (mismatch value restored [])]
      (throw (ex-info "Text capture loses input fidelity"
                      {:rule :correspondence/text :compilation compilation
                       :stage :fidelity :difference difference})))
    source))
