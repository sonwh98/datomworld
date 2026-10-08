p = 'src/cljc/yin/vm/ucf/handoff.cljc'
s = open(p).read()
old = '''(defn- validate-code-v2
  "Every code entry hashes to its own key and is admissible under the
   profile's own validator."
  [engine code]
  (doseq [[a v] code]
    (case engine'''
new = '''(defn- guarded-hash
  "`(f image)`, or nil when the image is so malformed that hashing it
   throws: a code entry that cannot be hashed is not an image."
  [f image]
  (try (f image)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- validate-code-v2
  "Every code entry hashes to its own key and is admissible under the
   profile's own validator."
  [engine code]
  (doseq [[a v] code]
    (when (and (contains? #{:stack :register} engine)
               (nil? (guarded-hash (if (= :stack engine)
                                     dcode/image-hash
                                     rcode/register-hash)
                                   v)))
      (undecodable! {:yin.k/segment a :yin.k/kind :code}))
    (case engine'''
assert old in s
s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'test/yin/vm/ucf/handoff_v2_layout_test.cljc'
t = open(p).read()
old = '''          (refused (enc (update-in body [:yin.k/code b]
                                   (fn [image]
                                     (if (vector? image)
                                       (conj image [:halt])
                                       (update image :instructions
                                               conj [:halt])))))
                   :yin.k/hash-mismatch))'''
new = '''          (refused (enc (update-in body [:yin.k/code b] tamper-image))
                   :yin.k/hash-mismatch))'''
assert old in t
t = t.replace(old, new, 1)
t = t.replace('''(deftest a-corrupted-image-or-image-identity-refuses-before-attachment''', '''(defn- tamper-image
  "`image` with the value of its first `:const` changed: still a valid
   image, no longer the one its key names."
  [image]
  (let [insts (if (vector? image) image (:instructions image))
        i (first (keep-indexed #(when (= :const (first %2)) %1) insts))
        insts' (update insts i
                       (fn [inst] (assoc inst (dec (count inst)) "tampered")))]
    (if (vector? image)
      insts'
      (assoc image :instructions insts'))))


(deftest a-corrupted-image-or-image-identity-refuses-before-attachment''', 1)
old = '''                                          (s/then (s/next-of (s/v 'c))
                                                  (s/app (s/lam ['v] (s/v 'k))
                                                         (s/lit 0)))))))))'''
new = '''                                          (s/then (s/next-of (s/v 'c))
                                                  {:type :if
                                                   :test (s/app (s/v '=)
                                                                (s/v 'k)
                                                                (s/lit 7))
                                                   :consequent (s/v 'k)
                                                   :alternate (s/app (s/v 'k)
                                                                     (s/lit 7))}))))))'''
assert old in t
t = t.replace(old, new, 1)
t = t.replace('''            reference (do (stream/append! src "B")
                          (s/drive-local m))''', '''            reference (do (stream/append! src "B")
                          (stream/append! src "C")
                          (s/drive-local m))''', 1)
open(p, 'w').write(t)
