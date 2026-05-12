(ns dao.mr-clean.runtime
  (:require
    [dao.mr-clean.compiler :as compiler]))


(defn- create-snapshot
  [atoms]
  (into {} (map (fn [a] [a @a]) atoms)))


(defn create-runtime
  "Creates a reactive runtime for mr-clean.
   Inputs:
   - root-form: the hiccup/component at the root of the tree
   - props: root props
   - atoms: a collection of atoms to watch
   - capabilities: capabilities map (e.g. :measure-text)
   - scheduler: a function (fn [task]) that schedules `task` to run on the next tick (e.g. microtask)
   - consumer: a function (fn [frame-program]) called with the compiled ops when ready
   - on-error: optional (fn [exception]) called when compile-ui throws.
     If omitted, the exception bubbles out of the scheduler.

   Returns a map with control fns, e.g. {:dispose (fn [])}."
  [{:keys [root-form props atoms capabilities scheduler consumer on-error]}]
  (let
    [watch-key (gensym "watch")
     pending-compile? (atom false)
     disposed? (atom false)
     ;; Atoms (not dynamic vars) so the guard works across threads —
     ;; a watch firing on a thread other than the compile thread can
     ;; still see that compilation is in progress.
     compiling? (atom false)
     guard-violation (atom nil)
     do-compile
     (fn []
       (when-not @disposed?
         (reset! pending-compile? false)
         (try (let [frame-program
                    (do (reset! guard-violation nil)
                        (reset! compiling? true)
                        (try (let [snapshot (create-snapshot atoms)]
                               (when-let [v @guard-violation] (throw v))
                               (let [result (compiler/compile-ui
                                              root-form
                                              props
                                              snapshot
                                              capabilities)]
                                 (when-let [v @guard-violation] (throw v))
                                 result))
                             (finally (reset! compiling? false))))]
                (when-not @disposed? (consumer frame-program)))
              (catch #?(:clj Throwable
                        :cljs :default
                        :cljd Exception)
                     e
                (if on-error (on-error e) (throw e))))))
     schedule-compile (fn []
                        (when (and
                                (not @disposed?)
                                (compare-and-set! pending-compile? false true))
                          (scheduler do-compile)))
     watch-fn
     (fn [_key ref _old-state _new-state]
       (if @compiling?
         (let
           [e (ex-info
                "Re-entrant mutation: watched atom mutated during compilation."
                {:atom ref, :path compiler/*evaluation-path*})]
           (compare-and-set! guard-violation nil e)
           (throw e))
         (schedule-compile)))]
    (doseq [a atoms] (add-watch a watch-key watch-fn))
    ;; Initial compile
    (schedule-compile)
    {:dispose (fn []
                (reset! disposed? true)
                (doseq [a atoms] (remove-watch a watch-key)))}))
