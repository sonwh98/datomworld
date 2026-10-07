p = 'test/yin/vm/ucf/handoff_v2_layout_test.cljc'
t = open(p).read()
old = '''    (select-keys r [:pc :frames :stack :continuation :registers :store-of])))'''
new = '''    (walk/postwalk (fn [x]
                     (if (and (map? x) (contains? #{:stream-ref :cursor-ref}
                                                  (:type x)))
                       (:type x)
                       x))
                   (select-keys r [:pc :frames :stack :continuation
                                   :registers :store-of]))))'''
assert old in t
t = t.replace(old, new, 1)
t = t.replace("            [clojure.set :as set]", "            [clojure.set :as set]\n            [clojure.walk :as walk]", 1)
open(p, 'w').write(t)
