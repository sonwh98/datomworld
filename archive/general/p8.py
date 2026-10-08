p = 'src/cljc/yin/vm/ucf/checkpoint.cljc'
s = open(p).read()
old = '''    (when-not fork? (check-root-header! body kind []))
    (baseline body kind
              (tree-ops (cond-> (select-keys body [:yin.k/occurrence
                                                   :yin.k/origin
                                                   :yin.k/next-op-seq])
                          fork? (assoc ::fork true))
                        nil body []))))'''
new = '''    (when-not fork? (check-root-header! body kind []))
    (cond-> (baseline body kind
                      (tree-ops (cond-> (select-keys body [:yin.k/occurrence
                                                           :yin.k/origin
                                                           :yin.k/next-op-seq])
                                  fork? (assoc ::fork true))
                                nil body []))
      ;; a fork is no custody checkpoint: the baseline says so, and an
      ;; authority refuses it as such rather than adding header keys
      fork? (assoc :yin.k/fork true))))'''
assert old in s
s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'src/cljc/yin/vm/ucf/authority/grant.cljc'
s = open(p).read()
old = '''      (cond
        (not (contains? #{:blocked :parked} (:yin.k/kind b)))
        (reply :refused :not-offerable nil)'''
new = '''      (cond
        (or (:yin.k/fork b)
            (not (contains? #{:blocked :parked} (:yin.k/kind b))))
        (reply :refused :not-offerable nil)'''
assert old in s
s = s.replace(old, new, 1)
open(p, 'w').write(s)
