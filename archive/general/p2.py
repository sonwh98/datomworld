p = 'test/yin/vm/ucf/handoff_v2_layout_test.cljc'
t = open(p).read()

old = t[t.index("                                          (s/then (s/next-of (s/v 'c))"):t.index("(defn- link-through")]
new = '''                                          (s/then (s/next-of (s/v 'c))
                                                  (s/lit :done)))))))))


'''
t = t.replace(old, new, 1)

old = t[t.index("(deftest a-captured-continuation-returns-across-images-as-the-source-does"):t.index("(defn- tamper-image")]
new = '''(defn- restored
  "The registers continuation value `k` restores to under `engine`'s
   own restore, delivering `val`, over machine `m`."
  [engine m k val]
  (let [payload (values/payload k)
        r (case engine
            :stack (stack/stack-restore m payload val)
            :register (register/register-restore m payload val))]
    (select-keys r [:pc :frames :stack :continuation :registers :store-of])))


(deftest a-captured-continuation-returns-across-images-as-the-source-does
  (doseq [engine profiles]
    (testing (name engine)
      (let [m (grown-machine engine)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            src (s/stream-of m)
            k-src (get (:store m) 'k)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})
            recv (:vm r)
            k-recv (get (:store recv) 'k)
            reference (do (stream/append! src "B")
                          (s/drive-local m))
            done (s/drive recv peer)]
        (is (= :ok (:status r)) (pr-str r))
        (is (values/continuation? k-src))
        (is (values/continuation? k-recv))
        (is (= (restored engine m k-src 7) (restored engine recv k-recv 7))
            "the cross-image return frames restore to the same registers")
        (is (= (vm/blocked? reference) (vm/blocked? done)))
        (is (= (vm/value reference) (vm/value done)) "the task runs on")))))


'''
t = t.replace(old, new, 1)

t = t.replace('''          (refuse! (enc (update-in body [:yin.k/code b] tamper-image))''', 'XX')
t = t.replace("(refused (enc (update-in body [:yin.k/code b] tamper-image))", "(refused (enc (update-in body [:yin.k/code a] tamper-image))")
open(p, 'w').write(t)
