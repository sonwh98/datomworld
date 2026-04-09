(ns dao.pretty
  (:require
    #?@(:cljd [[clojure.string :as str]]
        :clj [[clojure.pprint :as pprint]]
        :cljs [[cljs.pprint :as pprint]])))


#?(:clj
   (defn pp-str
     "Pretty-print a value to a string using clojure.pprint"
     ([value] (pp-str value nil))
     ([value _depth]
      (let [writer (java.io.StringWriter.)]
        (pprint/pprint value writer)
        (.toString writer)))))


#?(:cljs
   (defn pp-str
     "Pretty-print a value to a string using cljs.pprint"
     ([value] (pp-str value nil))
     ([value _depth]
      (with-out-str (pprint/pprint value)))))


#?(:cljd
   (do
     ;; Custom implementation for ClojureDart
     (def ^:private indent-width 2)
     (def ^:private line-length-budget 60)

     (defn- indent-str
       [depth]
       (str/join (repeat (* depth indent-width) " ")))

     (defn- escape-string
       [s]
       (str/escape s
                   {\\ "\\\\"
                    \" "\\\""
                    \newline "\\n"
                    \return "\\r"
                    \tab "\\t"}))

     (defn- format-string
       [s]
       (str "\"" (escape-string s) "\""))

     (defn- format-primitive
       [value]
       (cond
         (nil? value) "nil"
         (boolean? value) (str value)
         (number? value) (str value)
         (string? value) (format-string value)
         (keyword? value) (str value)
         (symbol? value) (str value)
         :else (pr-str value)))

     (declare pp-str)

     (defn- fits-on-line?
       [items open close depth]
       "Check if items fit on one line within budget"
       (let [formatted (map #(pp-str % (inc depth)) items)
             inline (str open (str/join " " formatted) close)
             available-width (+ line-length-budget (* depth indent-width))]
         (<= (count inline) available-width)))

     (defn- pp-seq
       [items depth open close]
       "Pretty-print a sequence with given open/close brackets"
       (if (empty? items)
         (str open close)
         (if (fits-on-line? items open close depth)
           (str open (str/join " " (map #(pp-str % (inc depth)) items)) close)
           (let [items-str (str/join (str " ")
                                     (map #(pp-str % (inc depth)) items))]
             (str open " " items-str " " close)))))

     (defn- pp-map
       [m depth]
       "Pretty-print a map"
       (if (empty? m)
         "{}"
         (let [entries (map (fn [[k v]]
                              (str (pp-str k (inc depth)) " " (pp-str v (inc depth))))
                            m)
               inline-form (str "{" (str/join ", " entries) "}")
               available-width (+ line-length-budget (* depth indent-width))]
           (if (<= (count inline-form) available-width)
             inline-form
             (str "{" (first entries)
                  (if (seq (rest entries))
                    (str "\n" (indent-str (inc depth))
                         (str/join (str "\n" (indent-str (inc depth))) (rest entries)))
                    "")
                  "}")))))

     (defn pp-str
       "Pretty-print a value to a string"
       ([value] (pp-str value 0))
       ([value depth]
        (cond
          (or (nil? value)
              (boolean? value)
              (number? value)
              (keyword? value)
              (symbol? value)
              (string? value))
          (format-primitive value)

          (vector? value)
          (pp-seq value depth "[" "]")

          (list? value)
          (pp-seq value depth "(" ")")

          (seq? value)
          (pp-seq value depth "(" ")")

          (set? value)
          (pp-seq (seq value) depth "#{" "}")

          (map? value)
          (pp-map value depth)

          :else (pr-str value))))))
