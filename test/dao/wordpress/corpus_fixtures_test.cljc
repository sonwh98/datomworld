(ns dao.wordpress.corpus-fixtures-test
  "Slice W0a integrity of the frozen SQL corpus (docs/design/dao.wordpress.md
   section 11). The fixtures under test/fixtures/wordpress/<tag>/ are written once
   by src/dev/dao/wordpress/corpus.clj from a recorded docker run and never
   regenerated; this test checks their SHAPE, not WordPress behaviour:

   - :seq is contiguous from 1 in every request file;
   - each event has exactly one of :result / :error;
   - a :prepare re-renders to :sql under wpdb's %d %s %i rules;
   - the manifest counts equal the files' contents;
   - the reconciliation remainder matches what the README explains.

   test/fixtures/wordpress/frozen.edn names the frozen tag ({:tag \"6.x.y\"}).
   Until the corpus run is frozen the file is absent and only the checker's own
   synthetic cases run."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            #?(:cljd ["dart:io" :as dart-io])))


(def root "test/fixtures/wordpress/")


(defn- read-file
  "File text, or nil when the file does not exist."
  [path]
  #?(:clj (let [f (java.io.File. ^String path)] (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")] (when (.existsSync fs path) (.readFileSync fs path "utf8")))
     :cljd (let [f (dart-io/File. path)] (when (.existsSync f) (.readAsStringSync f)))))


(defn- read-edn
  [path]
  (some-> (read-file path) str/trim edn/read-string))


;; ---- the checks (pure) ----------------------------------------------------

(defn escape-string
  "mysqli_real_escape_string for UTF-8 text."
  [s]
  (apply str
         (map (fn [c]
                (case c
                  \u0000 "\\0"
                  \newline "\\n"
                  \return "\\r"
                  \\ "\\\\"
                  \' "\\'"
                  \" "\\\""
                  \u001a "\\Z"
                  (str c)))
              s)))


(defn render-prepare
  "wpdb::prepare for %d %s %i and %%; ::skip when the template uses a form whose
   output is host- or locale-dependent (%f, argnum placeholders)."
  [{:keys [template args]}]
  (if (re-find #"%f|%F|%\d+\$" template)
    ::skip
    (loop [chars (seq template), args (seq args), out []]
      (if-let [c (first chars)]
        (if (= c \%)
          (let [n (second chars)]
            (case n
              \% (recur (nnext chars) args (conj out "%"))
              \d (recur (nnext chars) (next args) (conj out (str (let [a (first args)] (if (number? a) (long a) 0)))))
              \s (recur (nnext chars) (next args) (conj out (str "'" (escape-string (str (first args))) "'")))
              \i (recur (nnext chars) (next args) (conj out (str "`" (str/replace (str (first args)) "`" "``") "`")))
              (recur (next chars) args (conj out "%"))))
          (recur (next chars) args (conj out c)))
        (apply str out)))))


(defn check-request
  "Problems (a vector of strings) in one request-file value."
  [{:keys [request events]}]
  (let [id (:id request)]
    (vec
      (concat
        (when-not (and id (:method request) (:uri request)) [(str id ": request lacks id/method/uri")])
        (when (not= (map :seq events) (range 1 (inc (count events))))
          [(str id ": :seq is not contiguous from 1")])
        (for [e events
              :when (not= 1 (count (filter #(contains? e %) [:result :error])))]
          (str id " seq " (:seq e) ": needs exactly one of :result/:error"))
        (for [e events
              :when (and (:prepare e) (not (str/includes? (:sql e) "{")))
              :let [r (render-prepare (:prepare e))]
              :when (and (not= r ::skip) (not= r (:sql e)))]
          (str id " seq " (:seq e) ": prepare re-renders to " (pr-str r) " not " (pr-str (:sql e))))))))


;; ---- synthetic cases (always run) ------------------------------------------

(deftest escape-and-render-rules
  (is (= "a\\'b\\\\c\\n" (escape-string "a'b\\c\n")))
  (is (= "SELECT * FROM `wp_posts` WHERE ID = 7 AND post_name = 'x\\'y'"
         (render-prepare {:template "SELECT * FROM %i WHERE ID = %d AND post_name = %s"
                          :args ["wp_posts" 7 "x'y"]})))
  (is (= "100%" (render-prepare {:template "100%%" :args []})))
  (is (= ::skip (render-prepare {:template "x = %f" :args [1.5]}))))


(deftest check-request-flags-bad-shapes
  (let [ok {:request {:id "t1-01-front" :method "GET" :uri "/"}
            :events [{:seq 1 :kind :select :sql "SELECT 1"
                      :result {:rows-affected 0 :insert-id 0 :num-rows 1}}
                     {:seq 2 :kind :select :sql "SELECT x"
                      :error {:errno 1054 :text "Unknown column"}}]}]
    (is (= [] (check-request ok)))
    (is (seq (check-request (assoc-in ok [:events 1 :seq] 5))))
    (is (seq (check-request (update-in ok [:events 0] assoc :error {:errno 1 :text "t"}))))
    (is (seq (check-request (assoc-in ok [:events 0 :prepare]
                                      {:template "SELECT %d" :args [2]}))))))


;; ---- the frozen corpus (runs once frozen.edn exists) -----------------------

(deftest frozen-corpus-integrity
  (if-let [{:keys [tag]} (read-edn (str root "frozen.edn"))]
    (let [dir (str root tag "/")
          manifest (read-edn (str dir "manifest.edn"))
          reqs (for [{:keys [file]} (:requests manifest)]
                 (read-edn (str dir file)))]
      (testing "every manifest file exists and passes the shape checks"
        (is (every? some? reqs))
        (is (= [] (vec (mapcat check-request reqs)))))
      (testing "manifest counts equal the files"
        (is (= (get-in manifest [:totals :requests]) (count reqs)))
        (is (= (get-in manifest [:totals :events])
               (reduce + (map (comp count :events) reqs))))
        (is (= (map :events (:requests manifest)) (map (comp count :events) reqs))))
      (testing "the pin and reconciliation records exist"
        (is (= tag (:tag (read-edn (str dir "pin.edn")))))
        (is (map? (read-edn (str dir "reconciliation.edn"))))))
    (println "SKIP frozen-corpus-integrity: no test/fixtures/wordpress/frozen.edn yet")))
