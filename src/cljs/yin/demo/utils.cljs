(ns yin.demo.utils
  (:require
    [cljs.pprint :as pprint]))


(defn pretty-print
  "Pretty-print data to a string with a fixed right margin."
  [data]
  (binding [pprint/*print-right-margin* 60]
    (with-out-str (pprint/pprint data))))
