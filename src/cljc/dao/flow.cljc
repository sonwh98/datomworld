(ns dao.flow
  (:require
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.stream :as ds]))


(def hiccup->datoms hiccup/hiccup->datoms)
(def datoms->hiccup hiccup/datoms->hiccup)
(def walk-xf walk/walk-xf)
(def walk-once walk/walk-once)


(defn stream-transduce
  "Bridging dao.stream to standard Clojure transducer machinery.
   Reads inputs from `stream` at the given `cursor` and drives the composed
   `(xf rf)` step function until the stream ends or signals `reduced?`.
   Returns the final accumulator."
  [xf rf init stream cursor]
  (let [step (xf rf)]
    (loop [acc init
           cur cursor]
      (let [res (ds/next stream cur)]
        (cond (= res :blocked) acc
              (= res :end) (step acc)
              (= res :daostream/gap) (throw (ex-info "Stream gap encountered"
                                                     {:cursor cur}))
              :else (let [val (:ok res)
                          next-cur (:cursor res)
                          next-acc (step acc val)]
                      (if (reduced? next-acc)
                        (step @next-acc)
                        (recur next-acc next-cur))))))))
