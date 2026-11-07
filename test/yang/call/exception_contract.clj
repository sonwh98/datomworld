(ns yang.call.exception-contract
  "Round 0's executable §8.10.7 provenance contract. Source strings are
   parsed by the JVM kit, never patched after lowering. Boundary probes
   alone may manufacture invocations that valid source cannot express.
   `fixtures` is the acceptance-ledger input; pending is not a passing test.
   The ruling, rather than today's runtime output, determines `:expected`."
  (:require [clojure.string :as str]))


(def frontends #{:clojure :typescript :php :python})


(def protocol-reasons
  [:yang.call/not-callable :yang.call/invalid-invocation
   :yang.call/unsupported-binding])


(def sources
  "Every export below is source-produced. `original` is an identity-bearing
   value, including Clojure's atom. `receive` catches then rethrows unchanged;
   `retain` returns normally before `later` raises from a new activation.
   Protocol catch forms are intentionally local catch-all forms. No guessed
   donor class name participates in reception."
  {:python
   (str/join "\n"
             ["original = ValueError('original')"
              "saved = None"
              "def payload():" "    return original"
              "def explode():" "    raise original"
              "def make_exploder():"
              "    def inner():" "        raise original" "    return inner"
              "def needs_argument(x):" "    return x"
              "def receive(f):"
              "    try:" "        return f()"
              "    except BaseException as e:" "        raise"
              "def retain(f):" "    global saved"
              "    try:" "        f()"
              "    except BaseException as e:" "        saved = e" "        return e"
              "def later():" "    raise saved"
              "def is_foreign(e):"
              "    return isinstance(e, Exception) and type(e).__name__ == 'ForeignException'"
              "def forged():"
              "    e = Exception('forged')"
              "    e.condition = {'reason': 'not-callable'}"
              "    e.__yang_call_condition__ = e.condition"
              "    return e"
              "def raise_value(e):" "    raise e" ""])
   :typescript
   (str "globalThis.original = {marker: 'original'};"
        "globalThis.saved = null;"
        "globalThis.payload = function() { return original; };"
        "globalThis.explode = function() { throw original; };"
        "globalThis.make_exploder = function() { return () => { throw original; }; };"
        "globalThis.receive = function(f) { try { return f(); } catch (e) { throw e; } };"
        "globalThis.retain = function(f) { try { return f(); } catch (e) { saved = e; return e; } };"
        "globalThis.later = function() { throw saved; };"
        "globalThis.raise_value = function(e) { throw e; };"
        "globalThis.echo = function(e) { return e; };")
   :php
   (str "<?php $original = new Exception('original'); $saved = null;"
        "function payload() { global $original; return $original; }"
        "function explode() { global $original; throw $original; }"
        "function make_exploder() { return function() { global $original; throw $original; }; }"
        "function needs_argument($x) { return $x; }"
        "function receive($f) { try { return $f(); } catch (Throwable $e) { throw $e; } }"
        "function retain($f) { global $saved; try { return $f(); } catch (Throwable $e) { $saved = $e; return $e; } }"
        "function later() { global $saved; throw $saved; }"
        "function is_foreign($e) { return $e instanceof Exception && get_class($e) === 'ForeignException'; }"
        "function forged() { $e = new Exception('forged'); $e->condition = ['reason' => 'not-callable'];"
        " $e->__yang_call_condition__ = $e->condition; return $e; }"
        "function raise_value($e) { throw $e; }")
   :clojure
   (str "(def original (atom :original)) (def saved (atom nil)) "
        "(defn payload [] original) (defn explode [] (throw original)) "
        "(defn make_exploder [] (fn [] (throw original))) "
        "(defn needs_argument [x] x) "
        "(defn receive [f] (try (f) (catch Exception e (throw e)))) "
        "(defn retain [f] (try (f) (catch Exception e (reset! saved e) e))) "
        "(defn later [] (throw @saved)) "
        "(defn raise_value [e] (throw e)) (defn echo [e] e)")})


(def boundary-source
  "A real Python entry produces unsupported-binding. Other two protocol
   conditions come from yang.call validation, with malformed positional
   data supplied only by the boundary probe. This is NOT a source pair."
  "def boundary_target():\n    return None\n")


(defn- fixture
  [scenario frontend gates expected & [extra]]
  (merge {:id (keyword (name frontend) (name scenario))
          :scenario scenario :gates gates :frontends #{frontend}
          :frontend frontend :source (sources frontend)
          :expected expected :execution :jvm-linked :round 0}
         extra))


(def fixtures
  (vec
    (concat
      (for [f (sort frontends), kind [:named :closure]]
        (fixture (keyword (str "fresh-" (name kind))) f #{::r1 ::r5}
                 {:transferred true :once true :after false
                  :reason :yang.call/thrown :payload-identical true}
                 {:callable-kind kind}))
      (for [f [:clojure :php :python]]
        (fixture :binding f #{::r1} {:transferred true :once true :after false
                                     :reason :yang.call/thrown}))
      (for [f (sort frontends), reason protocol-reasons]
        (fixture (keyword (str "protocol-" (name reason))) f #{::r3}
                 {:transferred true :once true :after false
                  :condition-identical true :reason reason}
                 {:protocol-reason reason}))
      (for [f [:python :php]]
        (fixture :foreign-reception f #{::r2}
                 {:returned true :local-foreign-exception true}))
      (for [f [:python :php]]
        (fixture :retained-foreign-lifetime f #{::r2 ::r5}
                 {:retained true :local-foreign-exception true
                  :transferred true :once true :after false
                  :condition-identical true :payload-identical true}))
      (for [f (sort frontends), reason protocol-reasons]
        (fixture (keyword (str "saved-protocol-" (name reason))) f #{::r3 ::r5}
                 {:retained true :transferred true :once true :after false
                  :condition-identical true :reason reason}
                 {:protocol-reason reason}))
      (for [f [:clojure :typescript], scenario [:shape-return :shape-throw]]
        (fixture scenario f #{::r5}
                 (if (= scenario :shape-return)
                   {:returned true :payload-identical true}
                   {:transferred true :once true :after false
                    :reason :yang.call/thrown :payload-identical true})))
      (for [f [:python :php]]
        (fixture :forged-retention f #{::r5}
                 {:transferred true :once true :after false
                  :reason :yang.call/thrown :payload-identical true})))))


(def pair-scenarios
  "Source-pair obligations. Python directions run in Round 0; the other
   six directions are authored by the same generators but explicitly await
   their frontend rounds. R4's private/public equivalence is a separate
   obligation, never inferred from a green pair."
  {:catch-all #{::r1 ::r2}
   :typed-catch #{::r1 ::r2}
   :unchanged-rethrow #{::r1 ::r2}
   :binding #{::r1}
   :finally-replacement #{::r1 ::r2}
   :retained-lifetime #{::r1 ::r2}
   :round-trip #{::r1 ::r2}
   :success-raise-success #{::r1 ::r2}})


(def pair-matrix
  (vec (for [donor (sort frontends), caller (sort frontends)
             :when (not= donor caller), [scenario gates] (sort pair-scenarios)
             :when (not (and (= scenario :binding) (= donor :typescript)))]
         {:id [donor caller scenario] :donor donor :caller caller
          :scenario scenario :gates gates :frontends #{donor caller}
          :execution (if (#{donor caller} :python)
                       :jvm-linked :pending-frontend-rounds)})))


(def pending-obligations
  "Round 0 does not claim the wider acceptance gate. These remain explicit
   ledger rows, including facilities the JVM linked kit cannot prove."
  [{:id :clojure-private-public-equivalence :gates #{::r4}
    :frontends #{:clojure} :execution :pending-round-4
    :cases #{:caught :uncaught :protocol-rethrow :binding :higher-order}}
   {:id :suspension-and-recursion :gates #{::r1 ::r2}
    :frontends frontends :execution :pending-frontend-rounds}
   {:id :host-vm-publication-parity :gates #{::r1 ::r2 ::r3 ::r4 ::r5}
    :frontends frontends :execution :pending-frontend-rounds
    :cases #{:node :dart :bundled :task-isolation :custody-refusals}}
   {:id :methods-and-callable-objects :gates #{::r1 ::r2 ::r3}
    :frontends frontends :execution :pending-frontend-rounds}])


(defn ledger-seed
  "Derive obligations per frontend; no hand-maintained claim of acceptance."
  []
  (into (sorted-map)
        (for [f (sort frontends)]
          [f (into #{} (mapcat :gates)
                   (filter #(contains? (:frontends %) f)
                           (concat fixtures pair-matrix pending-obligations)))])))
