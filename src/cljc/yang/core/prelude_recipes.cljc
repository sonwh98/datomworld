(ns yang.core.prelude-recipes
  "Records the landed base emitters; these records do not drive emission.")

(defn base-recipes
  "Build migration records from the published yang.call pin and each
   prelude's declared host-module requirements. The caller supplies both:
   neither publication addresses nor host limits are global constants."
  [call-address host-modules]
  (when-not (and (some? call-address) (map? host-modules)
                 (every? #(map? (get host-modules %)) ['py 'js 'php 'clj 'yang.call]))
    (throw (ex-info "Base recipes require a call pin and host declarations" {})))
  (let [recipe (fn [id module mode uninit rt unshadow trailing c1 refusal]
                 {:yang.prelude/schema 1
                  :yang.prelude/id id
                  :yang.prelude/revision "landed"
                  :yang.prelude/module module
                  :yang.prelude/components (if (= module 'yang.call) [] [['yang.call call-address]])
                  :yang.prelude/host-modules (get host-modules module)
                  :yang.prelude/emitter {:mode mode :uninit uninit :rt-namespaces rt
                                         :unshadow-locals? unshadow
                                         :trailing-requires trailing :finalize-c1? c1
                                         :capture-safe-primitives nil}
                  :yang.prelude/max-effects nil
                  :yang.prelude/refusal-ns refusal})]
    {'py (recipe :yang.python/py-prelude 'py :linear :py/uninit #{"py.rt" "py.b"} false ['yang.call] false "yang.python.antlr")
     'js (recipe :yang.typescript/js-prelude 'js :balanced :js/uninit #{"js.rt"} false [] true "yang.typescript.antlr")
     'php (recipe :yang.php/php-prelude 'php :balanced :php/uninit #{"php.rt"} true [] false "yang.php.antlr")
     'clj (recipe :yang.clojure/clj-prelude 'clj :left-fold :clj/uninit #{"clj.rt"} false [] true "yang.clojure")
     'yang.call (recipe :yang.core/call-prelude 'yang.call :linear nil #{} false [] true "yang.call")}))
