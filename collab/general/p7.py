p = 'src/cljc/yin/vm/ucf/handoff.cljc'
s = open(p).read()


def rep(old, new, tag):
    global s
    if old not in s:
        if new in s:
            print("already", tag)
            return
        raise Exception("missing " + tag)
    s = s.replace(old, new, 1)
    print("applied", tag)


rep('''             kind (:yin.k/kind body)
             ;; a version-2 tree is a fork exactly when its root carries no''', '''             _ (when v2?
                 (primitives-satisfied! recv body))
             kind (:yin.k/kind body)
             ;; a version-2 tree is a fork exactly when its root carries no''', "call")

rep('''(defn- resume-task*''', '''(defn- primitives-satisfied!
  "Every primitive any task of the tree names exists in `recv`'s own
   registry under an equal profile, or the lower is `:yin.k/unsatisfied`
   naming it: judged on the bytes' markers before one attachment is made,
   never found missing at decode time."
  [recv body]
  (doseq [task (task-bodies body)
          root (reachable-values task)
          marker (tagged-of :yin.k/primitive root)]
    (let [n (:yin.k/name marker)
          entry (get (:primitives recv) n)]
      (when-not (and (some? entry)
                     (= (:yin.k/profile marker)
                        (:yin.k/profile (vm/profile-of (:primitives recv) n))))
        (refuse! :yin.k/unsatisfied {:yin.k/name n})))))


(defn- resume-task*''', "fn")
open(p, 'w').write(s)
