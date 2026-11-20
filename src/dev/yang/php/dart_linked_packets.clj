;; Regenerate after `bb gen:antlr --grammar php --target java`:
;; clojure -Sdeps '{:aliases {:dartleg {:extra-paths ["test"]}}}' -M:dartleg src/dev/yang/php/dart_linked_packets.clj
;; Packets retain the pinned parser output; the Dart test never parses source.
(require '[yang.php.antlr.cst :as cst] '[yang.php.antlr.programs-p6 :as p6] '[clojure.string :as str])
(def ids #{:inc-mutual-once :const-declare-and-read :const-redeclare-warns :const-undefined-error :const-define-redefinition})
(def extras [[:uncaught "<?php echo NOPE; echo 'after';" "" [] {:class "Error" :message "Undefined constant \"NOPE\""} 255]
 [:caught-finally "<?php try { echo NOPE; } catch (Error $e) { echo 'caught'; } finally { echo 'finally'; }" "caughtfinally" [] nil 0]
 [:values "<?php define('X', 7); echo X; define('F', false); define('Z', 0); define('N', null); var_dump(F, Z, N);" "7bool(false)\nint(0)\nNULL\n" [] nil 0]
 [:read-before-declaration "<?php echo LATER; const LATER = 1;" "" [] {:class "Error" :message "Undefined constant \"LATER\""} 255]
 [:fresh-request "<?php echo X;" "" [] {:class "Error" :message "Undefined constant \"X\""} 255]])
(def fixtures (filter #(or (ids (:id %)) (= :dynscope-v1 (:group %))) p6/enabled))
(def base (first (filter #(= :inc-mutual-once (:id %)) fixtures)))
(def active (-> base (assoc :id :inc-mutual-active) (update :units #(reduce (fn [m p] (update m p str/replace-first "<?php" "<?php declare(ticks=1);")) % ["a.php" "b.php"]))))
(defn packets [fixture] (let [units (or (:units fixture) {"main.php" (:source fixture)})] (-> fixture (dissoc :source :units) (assoc :packets (update-vals units cst/parse-source)))))
(def rows (mapv packets (concat fixtures [active] (map (fn [[id source out diag uncaught status]] {:id id :source source :out out :diag diag :uncaught uncaught :status status}) extras))))
(spit "test/yang/php/antlr/dart_linked_packets.cljc" (str ";; Generated from programs-p6 and constant-admission sources using the pinned PHP parser.\n(ns yang.php.antlr.dart-linked-packets)\n\n(def rows\n  '" (pr-str rows) ")\n"))
(println (mapv :id rows))
