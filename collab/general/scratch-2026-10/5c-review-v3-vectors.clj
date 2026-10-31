;; Print the v3 vectors of three programs so the reviewer can hand-check golden entries.
(require '[yin.vm :as vm]
         '[yin.vm.linearize :as old-linearize]
         '[yin.vm.semantic-register.corpus :as corpus :refer [app v def! lit]]
         '[clojure.pprint :as pp])


(doseq [[n ast] [[:define-then-call (get (into {} corpus/programs) :define-then-call)]
                 [:if-in-tail (get (into {} corpus/programs) :if-in-tail)]
                 [:use-before-definition (app (v 'list) (v 'n) (def! 'n (lit 1)))]]]
  (println "====" n)
  (pp/pprint (:vector (old-linearize/lower-rows (vm/ast->semantic-bytecode ast)))))


(shutdown-agents)
