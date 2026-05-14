(ns dao.gui.runtime
  (:require
    [dao.gui.compiler :as compiler]))


(defn- create-snapshot
  [atoms]
  (into {} (map (fn [a] [a @a]) atoms)))


(defn create-runtime
  "Creates a reactive runtime for dao.gui.
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
  (let [watch-key (gensym "watch")
        pending-compile? (atom false)
        disposed? (atom false)
        ;; Atoms (not dynamic vars) so the guard works across threads —
        ;; a watch firing on a thread other than the compile thread can
        ;; still see that compilation is in progress.
        compiling? (atom false)
        guard-violation (atom nil)]
    (letfn
      [(schedule-compile
         []
         (when (and (not @disposed?)
                    (compare-and-set! pending-compile? false true))
           (scheduler do-compile)))
       (do-compile
         []
         (when-not @disposed?
           (reset! pending-compile? false)
           (reset! guard-violation nil)
           (reset! compiling? true)
           (let [outcome (try (let [snapshot (create-snapshot atoms)]
                                ;; A watch firing during snapshot assembly
                                ;; (same
                                ;; thread or another) records a violation.
                                ;; Abort before the partial snapshot
                                ;; reaches the root.
                                (when-let [v @guard-violation] (throw v))
                                (let [result (compiler/compile-ui root-form
                                                                  props
                                                                  snapshot
                                                                  capabilities)]
                                  ;; A watch firing while compile-ui ran
                                  ;; also
                                  ;; trips the guard; do not deliver a
                                  ;; frame built from an inconsistent
                                  ;; state.
                                  (when-let [v @guard-violation] (throw v))
                                  (when-not @disposed? (consumer result))
                                  ::ok))
                              (catch #?(:clj Throwable
                                        :cljs :default
                                        :cljd Exception)
                                     e
                                {:error e})
                              (finally (reset! compiling? false)))]
             ;; A tripped guard means the world changed under us; queue
             ;; exactly one follow-up compile. schedule-compile's CAS
             ;; on pending-compile? deduplicates if multiple watches
             ;; fired. on-error must NOT suppress this recovery.
             (when @guard-violation (schedule-compile))
             (when (and (map? outcome) (:error outcome))
               (let [e (:error outcome)]
                 (if on-error (on-error e) (throw e)))))))]
      (let
        [watch-fn
         (fn [_key ref _old-state _new-state]
           (if @compiling?
             (let
               [e (ex-info
                    "Re-entrant mutation: watched atom mutated during compilation."
                    {:atom ref, :path compiler/*evaluation-path*})]
               ;; Record the violation; do-compile reads this to decide
               ;; whether to skip the consumer and queue a recovery
               ;; compile. Throwing keeps the existing contract that the
               ;; mutating thread sees the rejection synchronously.
               (compare-and-set! guard-violation nil e)
               (throw e))
             (schedule-compile)))]
        (doseq [a atoms] (add-watch a watch-key watch-fn))
        (schedule-compile)
        {:dispose (fn []
                    (reset! disposed? true)
                    (doseq [a atoms] (remove-watch a watch-key)))}))))
