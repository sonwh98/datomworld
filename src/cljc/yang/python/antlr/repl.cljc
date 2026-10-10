(ns yang.python.antlr.repl
  "The Python ANTLR frontend's REPL lowering (docs/design/yang.antlr.md
   section 8.5.6, slice F3). Portable: the parser is JVM-only, but a host
   that holds a CST packet lowers it here.

   One REPL submission lowers to a program that runs the packet's module body
   against the session's `__main__` namespace: the module object and its
   globals dict live in `sys.modules` (ruling 18), so a name bound by one
   submission is read by the next. `(reset)` rebuilds the VM, and with it the
   runtime state.

   The bundled prelude defines the runtime in the VM's store and `py/init!`
   allocates its task state; running it twice would discard that state. The
   first lowering of a session therefore carries the prelude and returns the
   session marker `:primed`; later lowerings of the same session omit it. The
   shell keeps the marker (`:frontend-session`) and clears it with the VM.

   Printed lines and an uncaught exception are rendered by the host
   primitive `py-report` (`report`) and written with the shell's own
   `println`, so they arrive on the output medium in order."
  (:require
    [clojure.string :as str]
    [yang.antlr.packet :as p]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.uast :as u]))


(def report-primitive
  "The primitive name the lowered program calls."
  'py-report)


(defn report
  "The text of one submission: each `print` call's line, then the uncaught
   exception as `Type: args`, newline separated. Empty when it did neither."
  [out exception]
  (str/join
    "\n"
    (cond-> (mapv render/line out)
      exception (conj (str (:type exception)
                           (when (seq (:args exception))
                             (str ": " (str/join " "
                                                 (map render/py-str
                                                      (:args exception))))))))))


(def primitives
  "The host primitives the lowered program needs, for a shell's `:primitives`."
  {report-primitive report})


(def ^:private run-form
  '(do (cell/set! py.rt/out [])
       (cell/set! py.rt/ctx (py/ctx nil 0 0 nil))
       (let [hit (py/modules-ref (py/str "__main__"))
             m (if (= hit :py/missing)
                 (let [fresh (py/module-new (py/str "__main__") :py/None)]
                   (do (py/modules-put! (py/str "__main__") fresh)
                       fresh))
                 hit)
             d (py/module-dict m)
             exc (py/try (fn []
                           (do (%repl-body d (py/globals-fn d))
                               :py/None))
                         (fn [e] e)
                         (fn [] :py/None))
             text (py-report
                    (cell/get py.rt/out)
                    (if (= exc :py/None) nil (py/snapshot-exc exc)))]
         (if (= text "") nil (println text)))))


(defn- ok-packet!
  [packet]
  (if (= :yang.cst/ok (:yang.cst/outcome packet))
    packet
    (throw (ex-info
             (let [e (first (:yang.cst/errors packet))]
               (str "SyntaxError: " (:message e)
                    (when (:line e) (str " (line " (:line e) ")"))))
             {:yang.python.antlr/diagnostic :yang.python.antlr/syntax
              :errors (:yang.cst/errors packet)}))))


(defn lower-session
  "`[ast session']` for one ok CST `packet` under `session` (nil on a fresh
   session, `:primed` once the prelude has run)."
  [packet session]
  (let [packet (ok-packet! packet)
        packet (cond-> packet
                 (not (contains? packet :yang.python.antlr/max-digits))
                 (assoc :yang.python.antlr/max-digits 4300))
        _ (p/validate! packet)
        body (lower/lower-module-body packet)
        main (u/let1 '%repl-body
                     (u/lam [lower/globals-sym lower/globals-fn-sym] body)
                     (u/sexp->uast run-form))
        ast (u/mark-tails (if (= :primed session)
                            main
                            (u/then prelude/uast main)))]
    [ast :primed]))


(defn lower
  "The stateless lowering: a program that carries the prelude."
  [packet]
  (first (lower-session packet nil)))
