(ns yin.repl.admission-doc-test
  "The executable check of slice AD-0 (yin.vm.linker.dht.md section 7.5,
   \"Pinned by test\"): the section and the code must not diverge.  It
   reads the section and asserts that every reason the admission seam
   refuses with is named in it, that it names `serve-budget` and
   `pair-capacity` with the values the vars hold, and that its retire
   rule spells the delimited prefix `origin + \".\"`."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yin.repl.admission :as admission]
            [yin.repl.link :as link]))


(def ^:private spec-path
  "docs/design/yin.vm.linker.dht.md")


(defn- read-text!
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when (.existsSync f) (.readAsStringSync f)))
     :clj (let [f (java.io.File. ^String path)]
            (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path)
               (.readFileSync fs path "utf8")))))


(defn section-7-5
  "The text of section 7.5 of `text`, its subsections included, from its
   heading to the next heading of level three or higher, or nil."
  [text]
  (let [lines (str/split-lines (or text ""))
        heading? #(re-find #"^#{1,3} " %)
        start (first (keep-indexed (fn [i l]
                                     (when (re-find #"^### 7\.5 " l) i))
                                   lines))]
    (when start
      (str/join "\n" (cons (nth lines start)
                           (take-while (complement heading?)
                                       (drop (inc start) lines)))))))


(defn- value-named
  "The digits the section gives in parentheses after the code name
   `word`, as in `serve-budget` (64), or nil."
  [section word]
  (second (re-find (re-pattern (str word "`? \\(([0-9]+)\\)")) section)))


(defn divergences
  "Every way section 7.5 of `text` and the code diverge, as data: empty
   when they agree."
  [text]
  (let [section (section-7-5 text)]
    (if (nil? section)
      [{:missing :section-7.5}]
      (cond-> (vec (keep (fn [r]
                           (when-not (str/includes? section (str r))
                             {:missing r}))
                         (sort-by str admission/reasons)))
        (not= (str link/serve-budget) (value-named section "serve-budget"))
        (conj {:serve-budget (value-named section "serve-budget")
               :code link/serve-budget})

        (not= (str link/pair-capacity) (value-named section "pair-capacity"))
        (conj {:pair-capacity (value-named section "pair-capacity")
               :code link/pair-capacity})

        (not (str/includes? section "origin + \".\""))
        (conj {:missing "origin + \".\""})))))


(deftest admission-spec-test
  (testing (str "section 7.5 of " spec-path " and the code agree")
    (is (= [] (divergences (read-text! spec-path))))))
