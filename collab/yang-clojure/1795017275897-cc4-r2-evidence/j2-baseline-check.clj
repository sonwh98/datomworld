(require '[babashka.process :as p])
(load-file "src/dev/jolt_j2_lane.clj")


(let [source (:out (p/shell {:out :string} "git" "show" "HEAD:src/dev/jolt_j2_lane.clj"))
      reader (java.io.PushbackReader. (java.io.StringReader. source))
      forms (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader)))
      original-included (first (filter #(and (seq? %) (= 'def (first %))
                                             (= 'included (second %))) forms))]
  (binding [*ns* (the-ns 'jolt-j2-lane)]
    (eval original-included)
    (eval (first (filter #(and (seq? %) (= 'defn (first %))
                               (= 'check-manifest! (second %))) forms))))
  (jolt-j2-lane/check-manifest! "test" jolt-j2-lane/included jolt-j2-lane/excluded))
