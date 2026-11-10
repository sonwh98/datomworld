(ns yang.call.exception-legs
  "Real-source directed exception legs. All twelve directions share these
   source generators; no language-pair runtime wrapper or call-node rewrite.
   Python-containing directions run on JVM in R0. The remaining directions
   are authored but pending their rounds (exception-contract/pair-matrix)."
  (:require [clojure.string :as str]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]))


(defn- define
  [frontend name params body]
  (case frontend
    :python (str "def " name "(" (str/join ", " params) "):\n"
                 (str/join "\n" (map #(str "    " %) (str/split-lines body))) "\n")
    :typescript (str "globalThis." name " = function(" (str/join ", " params)
                     ") { " body " };\n")
    :php (str "function " name "(" (str/join ", " (map #(str "$" %) params))
              ") { " body " }\n")
    :clojure (str "(defn " name " [" (str/join " " params) "] " body ")\n")))


(defn donor-exports
  "Only exports the donor actually defines. JS's absent required-argument
   helper is never read/projected: that source capability has no binding row."
  [frontend]
  (cond-> ["donor_raise" "payload" "success"]
    (not= frontend :typescript) (conj "needs_argument")))


(defn donor-source
  [frontend]
  (str (contract/sources frontend)
       (define frontend "success" []
         (case frontend :python "return True" :clojure "true" "return true;"))
       (define frontend "home" ["f" "done"]
         (case frontend
           :python (str "try:\n    f()\nexcept ValueError as e:\n"
                        "    return e is original and done()\nreturn False")
           :typescript (str "try { f(); } catch(e) { return e === original && done(); }"
                            " return false;")
           :php (str "global $original; try { $f(); } catch(Exception $e) {"
                     " return $e === $original && $done(); } return false;")
           :clojure "(try (f) false (catch Exception e (and (= e original) (done))))"))))


(def catch-all-body
  {:python "try:\n    donor_raise()\nexcept:\n    return True\nreturn False"
   :typescript "try { donor_raise(); } catch(e) { return true; } return false;"
   :php "try { donor_raise(); } catch(Throwable $e) { return true; } return false;"
   :clojure "(try (donor_raise) false (catch Exception e true))"})


(defn caller-source
  [frontend scenario]
  (let [header (case frontend :php "<?php " "")
        probe #(define frontend "probe" [] %)
        ;; `done` checks exactly-once cleanup, before the home runtime
        ;; recognizes its original exception again. Clojure uses its
        ;; admitted catch surface, and does not acquire finally syntax.
        counter (case frontend
                  :python "cleaned = 0\n"
                  :typescript "globalThis.cleaned = 0;\n"
                  :php "$cleaned = 0;\n"
                  :clojure "(def cleaned (cell/new 0))\n")
        done (define frontend "done" []
               (case frontend
                 :python "return cleaned == 1"
                 :typescript "return cleaned === 1;"
                 :php "global $cleaned; return $cleaned === 1;"
                 :clojure "(= (cell/get cleaned) 1)"))]
    (str header
         (case scenario
           :catch-all (probe (catch-all-body frontend))
           :typed-catch
           (probe (case frontend
                    :python (str "try:\n    donor_raise()\nexcept BaseException as e:\n"
                                 "    return type(e).__name__ == 'ForeignException'\nreturn False")
                    :php "try { donor_raise(); } catch(ForeignException $e) { return true; } return false;"
                    ;; JS has no typed syntax; Clojure admits exactly this
                    ;; restricted catch. Neither row claims class matching.
                    (catch-all-body frontend)))
           :unchanged-rethrow
           (str (case frontend :python "caught = 0\n"
                      :typescript "globalThis.caught = 0;\n"
                      :php "$caught = 0;\n"
                      :clojure "(def caught (cell/new 0))\n")
                (define frontend "caught_once" []
                  (case frontend :python "return caught == 1"
                        :typescript "return caught === 1;"
                        :php "global $caught; return $caught === 1;"
                        :clojure "(= (cell/get caught) 1)"))
                (probe (case frontend
                         :python "global caught\ntry:\n    donor_raise()\nexcept BaseException:\n    caught += 1\n    raise"
                         :typescript "try { donor_raise(); } catch(e) { caught += 1; throw e; }"
                         :php "global $caught; try { donor_raise(); } catch(Throwable $e) { $caught += 1; throw $e; }"
                         :clojure "(try (donor_raise) (catch Exception e (cell/set! caught (+ (cell/get caught) 1)) (throw e)))")))
           :binding (probe (str/replace (catch-all-body frontend) "donor_raise" "needs_argument"))
           :success-raise-success
           (probe (case frontend
                    :python (str "if not success():\n    return False\n"
                                 "try:\n    donor_raise()\nexcept:\n    return success()\nreturn False")
                    :typescript "if (!success()) return false; try { donor_raise(); } catch(e) { return success(); } return false;"
                    :php "if (!success()) return false; try { donor_raise(); } catch(Throwable $e) { return success(); } return false;"
                    :clojure "(and (success) (try (donor_raise) false (catch Exception e (success))))"))
           :finally-replacement
           (do
             (when (= frontend :clojure)
               (throw (ex-info "Clojure finally remains an explicit source refusal"
                               {:frontend frontend :scenario scenario})))
             (str
               (case frontend
                 :python "replacement = ValueError('replacement')\n"
                 :typescript "globalThis.replacement = {marker: 'replacement'};\n"
                 :php "$replacement = new Exception('replacement');\n")
               (define frontend "replacement_payload" []
                 (case frontend :python "return replacement"
                       :typescript "return replacement;"
                       :php "global $replacement; return $replacement;"))
               (probe (case frontend
                        :python "try:\n    donor_raise()\nfinally:\n    raise replacement"
                        :typescript "try { donor_raise(); } finally { throw replacement; }"
                        :php "global $replacement; try { donor_raise(); } finally { throw $replacement; }"))))
           :retained-lifetime
           (str
             (case frontend :python "saved = None\n" :typescript "globalThis.saved = null;\n"
                   :php "$saved = null;\n" :clojure "(def saved (cell/new nil))\n")
             (define frontend "save_foreign" []
               (case frontend
                 :python "global saved\ntry:\n    donor_raise()\nexcept BaseException as e:\n    saved = e\n    return e"
                 :typescript "try { donor_raise(); } catch(e) { saved = e; return e; }"
                 :php "global $saved; try { donor_raise(); } catch(Throwable $e) { $saved = $e; return $e; }"
                 :clojure "(try (donor_raise) (catch Exception e (cell/set! saved e) e))"))
             (define frontend "saved_local" []
               (case frontend
                 :python "return isinstance(saved, BaseException) and type(saved).__name__ == 'ForeignException'"
                 :php "global $saved; return $saved instanceof Throwable && get_class($saved) === 'ForeignException';"
                 :typescript "return true;" :clojure "true"))
             (probe (case frontend :python "raise saved" :typescript "throw saved;"
                          :php "global $saved; throw $saved;" :clojure "(throw (cell/get saved))")))
           :round-trip
           (str counter done
                (probe (case frontend
                         :python "global cleaned\ntry:\n    donor_raise()\nfinally:\n    cleaned += 1"
                         :typescript "try { donor_raise(); } finally { cleaned += 1; }"
                         :php "global $cleaned; try { donor_raise(); } finally { $cleaned += 1; }"
                         :clojure "(try (donor_raise) (catch Exception e (cell/set! cleaned (+ (cell/get cleaned) 1)) (throw e)))")))))))


(defn- call
  [frontend export args]
  (kit/boundary-call-form (kit/export-form frontend export)
                          (kit/observation-vector-form args) []))


(defn observation-form
  [{:keys [donor caller scenario probe-export]}]
  (let [result 'leg.result
        original 'leg.original
        transfer {:transferred (list '= :raise (list 'get result :route))
                  :once (list '= 1 (list 'get result :transfers))
                  :after (list 'get result :after)
                  :reason (list 'get (list 'get result :condition) :yang.call/reason)
                  :payload-identical
                  (list '= original (list 'get (list 'get result :condition) :yang.call/value))}
        probe-export (or probe-export "probe")
        simple {:returned (list '= :return (list 'get result :route))
                :value (list 'get result :value)}]
    (kit/guard-exceptions-form
      [donor caller]
      (case scenario
        (:catch-all :typed-catch :binding :success-raise-success)
        (list 'let [result (call caller probe-export [])] (kit/observation-map-form simple))
        :round-trip
        (list 'let [result (call donor "home" [(kit/export-form caller probe-export)
                                               (kit/export-form caller "done")])]
              (kit/observation-map-form simple))
        :retained-lifetime
        (list 'let [original (list 'get (call donor "payload" []) :value)
                    'leg.saved (call caller "save_foreign" [])
                    'leg.local (call caller "saved_local" [])
                    result (call caller probe-export [])]
              (kit/observation-map-form
                (assoc transfer :retained (list '= :return (list 'get 'leg.saved :route))
                       :local (list 'get 'leg.local :value))))
        (:unchanged-rethrow :finally-replacement)
        (list 'let [original (list 'get (if (= scenario :finally-replacement)
                                          (call caller "replacement_payload" [])
                                          (call donor "payload" [])) :value)
                    result (call caller probe-export [])]
              (kit/observation-map-form (cond-> transfer
                                          (= scenario :unchanged-rethrow)
                                          (assoc :caught (list 'get (call caller "caught_once" []) :value)))))))))


(defn expected
  [{:keys [scenario]}]
  (if (#{:catch-all :typed-catch :binding :success-raise-success :round-trip} scenario)
    {:returned true :value true}
    (cond-> {:transferred true :once true :after false
             :reason :yang.call/thrown :payload-identical true}
      (= scenario :unchanged-rethrow) (assoc :caught true)
      (= scenario :retained-lifetime) (assoc :retained true :local true))))


(def ^:private published-donors
  (into {} (for [frontend contract/frontends]
             [frontend (delay (kit/publish-donor! frontend (donor-source frontend)))])))


(defn run-leg
  [{:keys [donor caller scenario] :as leg}]
  (try
    (let [published @(get published-donors donor)]
      (kit/run-exception-program
        published
        (kit/compose-exception published
                               (donor-exports donor)
                               caller (caller-source caller scenario)
                               (observation-form leg))))
    (catch Throwable e
      (kit/error-results e))))


(defn- probe-name
  [scenario]
  (str "probe_" (str/replace (name scenario) "-" "_")))


(defn batch-caller-source
  "One parsed caller unit with distinct named probes. Renaming happens in
   generated SOURCE, before parsing; no lowered call nodes are changed."
  [caller pair-legs]
  (str (when (= caller :php) "<?php ")
       (apply str
              (for [{:keys [scenario]} pair-legs]
                (-> (caller-source caller scenario)
                    (str/replace #"^<\?php " "")
                    (str/replace #"\bprobe\b" (probe-name scenario)))))))


(defn run-pair
  "All authored scenarios for a directed pair, on each vector VM. Each
   probe enters through its own public entry. Guards report unbridged
   escapes; full context snapshot proof remains a frontend gate."
  [donor caller]
  (let [pair-legs (filter #(and (= donor (:donor %)) (= caller (:caller %)))
                          contract/pair-matrix)]
    (try
      (let [published @(get published-donors donor)
            observations (reduce
                           (fn [acc {:keys [scenario] :as leg}]
                             (list 'assoc acc scenario
                                   (observation-form
                                     (assoc leg :probe-export (probe-name scenario)))))
                           {} pair-legs)
            results (kit/run-exception-program
                      published
                      (kit/compose-exception published
                                             (donor-exports donor)
                                             caller (batch-caller-source caller pair-legs)
                                             observations))]
        (if (some #(instance? Throwable %) (vals results))
          (into {} (for [{:keys [id] :as leg} pair-legs]
                     [id (run-leg leg)]))
          (into {} (for [{:keys [id scenario]} pair-legs]
                     [id (update-vals results #(get % scenario))]))))
      (catch Throwable e
        (let [err (kit/error-results e)]
          (into {} (for [{:keys [id]} pair-legs]
                     [id err])))))))
