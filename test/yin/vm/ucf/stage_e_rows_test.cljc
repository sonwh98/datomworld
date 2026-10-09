(ns yin.vm.ucf.stage-e-rows-test
  "M-next stage E, the isolated-runtime form on every lane: every
   scenario of yin.vm.ucf.stage-e-scenarios run in this process, each
   life a fresh runtime over the scenario's durable root, each kill the
   life's mortal media going dead at its named cut.  On Dart this is the
   whole of stage E's evidence (its process transport is recorded
   separately); on the JVM and Node it is the same-runtime control
   beside the separate-process matrix of
   yin.vm.ucf.stage-e-process-test.

   Full 13-row acceptance matrix mapping (linker-dht 14.2.4 & UCF 7.11):
   - Row 1: candidates and encodings (row-1-candidates-and-encodings-test)
   - Row 2: export phases & carrier failures (row-2-export-phases-test, row-2-carrier-failures-test)
   - Row 3: reclaim (row-3-reclaim-test)
   - Row 4: kills around result delivery (row-4-kills-around-result-delivery-test)
   - Row 5: replay divergence (row-5-replay-divergence-test)
   - Row 6: partition (row-6-partition-test)
   - Row 7: kills around completion & failure-lower (row-7-kills-around-completion-test, row-7-failure-lower-and-lost-authority-test)
   - Clause 8: admission order & cross-target conflict (clause-8-admission-order-test)
   - Clause 3: sequence and regrant (clause-3-sequence-and-regrant-test)
   - Fork: fork per host (fork-per-host-test)
   - Inbox & Journal: positional inboxes & crash recovery (inbox-and-journal-cuts-test)
   - Row 10 canonical-byte agreement: exercised cross-host in stage-e-process-test
     (ordered JVM/Node pairs over file media)."
  (:require [clojure.test :refer [deftest]]
            [yin.vm.ucf.stage-e-scenarios :as sc]))


(defn- run-all
  [scenarios]
  (doseq [s scenarios]
    (sc/run-in-process s)))


(deftest fork-per-host-test
  (run-all [sc/fork-row]))


(deftest row-7-kills-around-completion-test
  (run-all (map sc/row7 sc/row7-cuts)))


(deftest row-7-failure-lower-and-lost-authority-test
  (run-all [sc/row7-failure-lower sc/row7-lost-authority]))


(deftest row-4-kills-around-result-delivery-test
  (run-all (map sc/row4 sc/row4-cuts)))


(deftest row-3-reclaim-test
  (run-all (map sc/row3 sc/row3-variants)))


(deftest row-5-replay-divergence-test
  (run-all (map sc/row5 sc/row5-variants)))


(deftest row-2-export-phases-test
  (run-all (map sc/row2 (concat sc/row2-pre-fence-cuts sc/row2-post-fence-cuts))))


(deftest row-2-carrier-failures-test
  (run-all (map sc/row2-carrier sc/row2-carriers)))


(deftest row-1-candidates-and-encodings-test
  (run-all [sc/row1]))


(deftest row-6-partition-test
  (run-all [sc/row6-request-partition sc/row6-consumer-partition sc/row6-lost-reply]))


(deftest clause-8-admission-order-test
  (run-all (concat (map sc/clause8 sc/clause8-variants) [sc/clause8-closed])))


(deftest clause-3-sequence-and-regrant-test
  (run-all [sc/clause3-regrant sc/clause3-carried]))


(deftest inbox-and-journal-cuts-test
  (run-all (concat (map #(sc/journal-row {:cut %}) sc/journal-cuts)
                   (map #(sc/journal-row {:fail %}) sc/uncertain-retentions))))
