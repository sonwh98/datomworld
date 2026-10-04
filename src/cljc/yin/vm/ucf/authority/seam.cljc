(ns yin.vm.ucf.authority.seam
  "A substrate test seam of M-next C slice C3, NOT an admission entry
   point: `commit-effect!` commits an effect, its result and its dedup
   record in one authority transition with no binding, tenure or scope
   check.  It exists so the substrate (dedup, poison, crash cuts, the
   target reader) is testable before admission lands in slice C7.  It is
   a public var only because ClojureDart cannot reach private vars;
   nothing outside tests may call it, and there is no `admit!`."
  (:require [dao.jing.cbor :as cbor]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.ledger :as ledger]))


(defn commit-effect!
  "Append `value` to target `i` under operation id `op-id`.  Answers
   `{:yin.k/status :committed :yin.k/result r :dao.space/t t}`, where r
   is ok, or closed after the target's close; `:replayed` with the
   recorded result for an op id already recorded with equal intent;
   `:intent-conflict` for one recorded with other intent, committing
   nothing; `:refused` for an unknown target; and `:suspended` past a
   bound or on a poisoned authority.  The new target position needs no
   check of its own: it is below the effect's entity id, which
   `transition!` bounds."
  [a i op-id value]
  (authority/transition!
    a
    (fn [p]
      (let [target (get-in p [:targets i])
            h (ledger/intent i value)
            recorded (get-in p [:admitted (cbor/content-key op-id)])]
        (cond
          (nil? target)
          {::authority/reply {:yin.k/status :refused
                              :yin.k/reason :unknown-target}}
          recorded
          {::authority/reply
           (if (= h (:yin.k/intent recorded))
             {:yin.k/status :replayed
              :yin.k/result (:yin.k/result recorded)}
             {:yin.k/status :intent-conflict})}
          (:closed? target)
          {::authority/facts [{:yin.k/custody :yin.k/admitted
                               :yin.k/target i
                               :yin.k/op-id op-id
                               :yin.k/intent h
                               :yin.k/result ledger/closed-result}]
           ::authority/reply {:yin.k/status :committed
                              :yin.k/result ledger/closed-result}}
          :else
          {::authority/facts [{:yin.k/custody :yin.k/admitted
                               :yin.k/target i
                               :yin.k/op-id op-id
                               :yin.k/intent h
                               :yin.k/value value
                               :yin.k/result ledger/ok-result}]
           ::authority/reply {:yin.k/status :committed
                              :yin.k/result ledger/ok-result}})))))
