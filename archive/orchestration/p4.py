import re

# ---------------------------------------------------------------- handoff
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


rep('''(defn- compose-v2
  "The code space a layout of `images` builds, under `engine`'s kernel."''', '''(defn reachable-parked
  "The ids of the parked records the lift of `vm` would carry, by the
   same census the lift runs: the semantic completion walk, or the
   positional and walker census.  Answers `{:ids #{...}}`, or
   `{:refusal data}` for a walk refusal.  The holder's `enter` moves
   exactly these records into its export record."
  [vm]
  (let [engine (engine-of vm)]
    (if (= :semantic engine)
      (let [walked (completion/complete
                     {:vm vm
                      :cursor-profile (constantly :dao.stream.remote/v1)
                      :modules (module-decls vm)})]
        (if-some [r (first (:yin.k/refusals walked))]
          {:refusal (if (= :yin.k/not-quiescent (:kind r))
                      {:yin.k/status :yin.k/not-quiescent}
                      (assoc (dissoc r :kind)
                             :yin.k/status :yin.k/non-portable
                             :yin.k/kind (:kind r)))}
          {:ids (set (keys (:yin.k/parked (:yin.k/scheduler walked))))}))
      {:ids (set (keys (:yin.k/parked
                         (:yin.k/scheduler (census-v2 engine vm)))))})))


(defn- compose-v2
  "The code space a layout of `images` builds, under `engine`'s kernel."''', "reachable-parked")
rep("(declare pc-profile? site-op site-reasons)", "(declare pc-profile? site-op site-reasons module-decls)", "declare-module-decls")
open(p, 'w').write(s)
