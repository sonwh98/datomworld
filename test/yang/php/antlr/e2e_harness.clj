(ns yang.php.antlr.e2e-harness
  "The JVM end-to-end harness: PHP source -> CST packet -> lowering stage ->
   program envelope -> the bundled program on each of the four VMs. Output
   is the unit's bottom buffer, a latin-1 byte carrier, decoded as UTF-8
   here at the boundary."
  (:require
    [dao.host.text :as text]
    [dao.stream :as stream]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.portable-harness :as portable]
    [yin.vm :as vm]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.semantic :as semantic]
    [yin.vm.semantic.derive :as derive]
    [yin.vm.semantic.linearize :as sr-linearize]
    [yin.vm.test-utils :as tu]))


(defn host-registry
  "The registry the PHP compositions run over."
  []
  (portable/host-registry))


(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(defn lower-program
  "`{:ast a :warnings w}` for `source` through the lowering stage, or
   `{:diagnostics d}`. Warning-severity diagnostics do not stop the program."
  [source]
  (let [cst-log (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (stream/append! cst-log (cst/parse-source source))
    (lower/step-stage (lower/open-stage cst-log program diagnostics
                                        {:medium lower/program-medium}))
    (let [{warnings true, problems false}
          (group-by #(= :warning (:severity %)) (tu/drain diagnostics))]
      (if (seq problems)
        {:diagnostics problems}
        (let [{:yin/keys [batch root]} (first (tu/drain program))]
          {:ast (nth batch root), :warnings (vec warnings)})))))


(defn packet->form
  "The nested form of a CST packet, spans dropped: `[:rule child ...]` for a
   rule, `[\"TYPE\" \"text\"]` for a token. The inverse of
   `portable-harness/packet`."
  [{:yang.cst/keys [root nodes]}]
  (letfn [(form
            [id]
            (let [{:keys [kind rule type text children]} (nth nodes id)]
              (if (= :token kind)
                [type text]
                (into [(keyword rule)] (map form) children))))]
    (form root)))


(defn latin1->text
  "The UTF-8 text of the byte carrier `s`."
  [s]
  (text/utf8-decode (.getBytes ^String s "ISO-8859-1")))


(defn render
  "The text a unit prints: its output, then, for an uncaught error, the
   fatal line (no path, plan section 3.9)."
  [{:php/keys [out uncaught]}]
  (str (latin1->text out)
       (when uncaught
         (str "PHP Fatal error:  Uncaught " (:class uncaught) ": " (:message uncaught)))))


(defn run-vms
  "`{vm-key rendered}` of `ast` on the four VMs; a host throw is
   `[:thrown message]`."
  [registry ast]
  (let [opts (assoc base-opts :modules registry)
        session (tu/make-observer-session (tu/create-vm {:modules registry}))
        datoms (vec (vm/ast->datoms ast))
        projected #(:vector (sr-linearize/project ast))
        attempt (fn [f]
                  (try (render (f))
                       (catch Exception e [:thrown (ex-message e)])))]
    (tu/queue-ast! session ast)
    {:ast-walker (attempt #(vm/value (:consumer (tu/run-session session)))),
     :semantic (attempt #(vm/value (vm/run (load-semantic (semantic/create-vm opts)
                                                          datoms)))),
     :stack (attempt #(vm/value (vm/run (dvm/create-vm
                                          (:image (derive/stack-image (projected)))
                                          (assoc opts :contract vm/stack-contract))))),
     :register (attempt #(vm/value (vm/run (rvm/create-vm
                                             (derive/register-image (projected))
                                             (assoc opts
                                                    :contract
                                                    vm/register-contract)))))}))


(defn run-php
  "`{vm-key rendered}` for `source`, or `{:diagnostics d}`."
  [source]
  (let [{:keys [ast diagnostics]} (lower-program source)]
    (if diagnostics {:diagnostics diagnostics} (run-vms (host-registry) ast))))


(defn run-walker
  "The ast-walker's rendering of `source` alone (fast iteration)."
  [source]
  (let [{:keys [ast diagnostics]} (lower-program source)]
    (if diagnostics
      {:diagnostics diagnostics}
      (let [registry (host-registry)
            session (tu/make-observer-session (tu/create-vm {:modules registry}))]
        (tu/queue-ast! session ast)
        (try (render (vm/value (:consumer (tu/run-session session))))
             (catch Exception e [:thrown (ex-message e)]))))))


(defn run-raw
  "The unit's value, `{:php/out :php/diag :php/uncaught}`, on the AST walker."
  [source]
  (let [{:keys [ast diagnostics]} (lower-program source)]
    (if diagnostics
      {:diagnostics diagnostics}
      (let [session (tu/make-observer-session
                      (tu/create-vm {:modules (host-registry)}))]
        (tu/queue-ast! session ast)
        (vm/value (:consumer (tu/run-session session)))))))
