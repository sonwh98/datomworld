(ns yang.correspondence-baseline
  "Prepare a trace-free comparison program from retained oracle inputs."
  (:require [yang.correspondence-text :as text]))


(defn untraced-program!
  "Remove the verified oracle trace prelude and replace verified traced forms
   with captured original forms. Preserve the already-assembled library prefix.
   Refuse any unexpected assembly, rather than guessing which forms to strip."
  [program forms prelude traced call]
  (let [suffix (vec (concat prelude traced [call]))
        n (- (count program) (count suffix))]
    (when-not (and (vector? program) (sequential? forms) (sequential? traced)
                   (= (count forms) (count traced)) (<= 0 n)
                   (let [actual (subvec program n)
                         bootstrap (quote (def gate-execution-trace (stream/make 1000000)))
                         strip? (and (= bootstrap (first suffix)) (= bootstrap (first actual)))]
                     (if strip?
                       (text/faithful? (subvec suffix 1) (subvec actual 1))
                       (text/faithful? suffix actual))))
      (throw (ex-info "Unexpected retained program assembly" {:stage :baseline-assembly})))
    (into (subvec program 0 n) (concat forms [call]))))


(defn two-field-trace-form
  "The body-entry instrumentation from oracle revision f2d7df87, applied to
   the current captured forms for a measured historical-tracer comparison."
  [form]
  (if (#{'defn 'defn-} (first form))
    (let [[op fname & header] form
          header (drop-while #(not (or (vector? %) (seq? %))) header)
          trace-arity (fn [[params & body]]
                        (let [n (if (some #{'&} params)
                                  [:variadic (.indexOf params '&)] (count params))]
                          (list* params
                                 (list 'stream/put! 'gate-execution-trace (list 'quote [fname n]))
                                 body)))]
      (list* op fname (if (vector? (first header))
                        (trace-arity header) (map trace-arity header))))
    form))
