(ns yang.python.antlr.c3-gate-test
  "The C3 integration gate (slice S7, ruling 14): every program of
   `yang.python.antlr.c3-programs`, lowered from its CST packet and run on
   all four VMs, prints exactly the stdout c3-corpus-v1 holds for it,
   on every host this runs on. A `cpython` program's stdout is CPython
   3.9.6's (c3-corpus-v1.generate.py); a `hand` program's is this
   support profile's, pinned, because CPython has no such limits."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.jing.cbor-fixtures :as cfx]
    [dao.test-slow :as slow]
    [yang.python.antlr.c3-programs :as programs]
    [yang.python.antlr.int-ops-test :as ops]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.render :as render]))


(def path "test/resources/yang/python/c3-corpus-v1.txt")


(defn- program-header
  [line]
  (let [[word name origin profile :as fields] (str/split line #" ")]
    (when-not (and (= 4 (count fields)) (= "program" word)
                   (contains? #{"cpython" "hand"} origin)
                   (contains? #{"wide" "small"} profile))
      (throw (ex-info "bad corpus program line" {:line line})))
    {:name name, :origin origin, :profile profile, :source [], :stdout []}))


(defn read-corpus
  "The corpus as `[{:name :origin :profile :source :stdout} ...]`, in file
   order: `:source` the program text, `:stdout` its printed lines. Blank
   lines are ignored (Dart keeps a trailing one)."
  []
  (let [[magic version & lines] (remove str/blank?
                                        (str/split-lines (cfx/read-path path)))]
    (when-not (and (= "c3-corpus-v1" magic) (= "CPython 3.9.6" version))
      (throw (ex-info "wrong C3 corpus" {:magic magic})))
    (mapv (fn [p] (update p :source #(str (str/join "\n" %) "\n")))
          (reduce (fn [acc line]
                    (cond
                      (str/starts-with? line "program ")
                      (conj acc (program-header line))
                      (str/starts-with? line "> ")
                      (update-in acc [(dec (count acc)) :source]
                                 conj (subs line 2))
                      (str/starts-with? line "| ")
                      (update-in acc [(dec (count acc)) :stdout]
                                 conj (subs line 2))
                      :else (throw (ex-info "bad corpus line" {:line line}))))
                  []
                  lines))))


(defn program
  "The corpus entry named `name`."
  [name]
  (some #(when (= name (:name %)) %) (read-corpus)))


(def ^:private profiles
  "Each profile's runners and decimal digit budget: `wide` the ordinary
   Python composition, `small` 60 bits and 5 digits."
  {"wide" [ops/runners 4300], "small" [ops/small-runners 5]})


(defn run-program
  "`{vm-key rendered-value}` of packet `pk` lowered and run under the
   named profile; a throw becomes `[:thrown message]`."
  [pk profile]
  (let [[runners budget] (get profiles profile)
        ast (lower/lower-packet
              (assoc pk :yang.python.antlr/max-digits budget))]
    (into {}
          (map (fn [[k run]]
                 [k (try (render/output (run ast))
                         (catch #?(:cljd Object :clj Exception :cljs :default) e
                           [:thrown (ex-message e)]))]))
          runners)))


(deftest the-corpus-names-every-program-test
  (let [corpus (read-corpus)]
    (is (= (map first programs/programs) (map :name corpus)))
    (testing "the limit programs are hand pins, every other is CPython's"
      (is (= {"limits" "hand", "small" "hand"}
             (into {}
                   (keep #(when (= "hand" (:origin %))
                            [(:name %) (:origin %)]))
                   corpus)))
      (is (= ["small"] (keep #(when (= "small" (:profile %)) (:name %))
                             corpus))))))


(defn- gate
  []
  (doseq [[name pk] programs/programs]
    (let [{:keys [profile stdout]} (program name)]
      (doseq [[k out] (run-program pk profile)]
        (is (= {:py/out stdout, :py/exception nil} out)
            (str name " " k))))))


(deftest ^:slow c3-gate-test
  (slow/guard "c3-gate-test" gate))
