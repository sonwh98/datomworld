;; Reviewer probe: does a tail-call park's :resume-mode marker survive the
;; UCF version-2 lift/lower of a stack-kernel task?  Read-only probe; no
;; tracked file is touched.
(require '[yin.vm :as vm]
         '[yin.vm.module :as module]
         '[yin.vm.ucf.handoff :as handoff]
         '[yin.vm.ucf.v2-support :as s]
         '[dao.stream :as stream])


(def ast
  ;; (let [s (stream/make 4)] (let [c (cursor s)] (stream/next! c)))
  ;; the host call is in tail position of the inner lambda: K non-empty.
  (s/let1 's {:type :stream/make, :buffer 4}
          (s/let1 'c (s/cursor-of (s/v 's))
                  (assoc (s/app (s/v 'stream/next!) (s/v 'c)) :tail? true))))


(def opts {:modules (module/register-stream-module (module/default-registry))})

(def m0 (vm/run (s/load-ast :stack (s/new-machine :stack opts) ast)))
(def e0 (first (:wait-set m0)))


(println "source blocked?" (vm/blocked? m0)
         "| site" (nth (:segment m0) (dec (:pc e0)))
         "| resume-mode" (:resume-mode e0)
         "| K depth" (count (:continuation e0)))


(def t (s/toy))
(def peer (s/served-peer t))
(def export (s/lift m0 t peer))
(println "export status" (:status export))
(def body (:body export))
(def root-regs (:yin.k/registers (:yin.k/root body)))
(println "carried root register keys:" (sort (keys (or root-regs {}))))
(def attach! (s/attacher t))


(def r
  (handoff/resume-task (s/new-machine :stack (assoc opts :attach-stream attach!))
                       (:bytes export) attach! {:address (:address export)}))


(println "resume status" (:status r))
(def m1 (or (:vm r) (:machine r) (:task r)))
(def e1 (when (map? m1) (first (:wait-set m1))))
(println "resumed entry keys" (when e1 (sort (keys e1))))
(println "RESULT marker-before" (:resume-mode e0) "marker-after" (:resume-mode e1))
(shutdown-agents)
