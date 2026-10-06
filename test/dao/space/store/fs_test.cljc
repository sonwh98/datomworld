(ns dao.space.store.fs-test
  "The host file reads of dao.space.store.fs a caller bounds and decodes
   itself: `read-bounded` (absence, the bound, a regular file only) and
   `decode-utf8` (strict UTF-8), on every host.  `heads.edn`'s use of
   them is yin.repl.dht-head-test."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.space.store.fs :as fs])
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(defn- temp-dir
  []
  (str "target/test-store-fs-" (random-uuid)))


(defn- mkdirs!
  [dir]
  #?(:cljd (.createSync (dart-io/Directory. dir) .recursive true)
     :clj (.mkdirs (java.io.File. ^String dir))
     :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true})))


(defn- write-bytes!
  [path codes]
  #?(:cljd (.writeAsBytesSync (dart-io/File. path) (Uint8List.fromList codes))
     :clj (java.nio.file.Files/write
            (java.nio.file.Paths/get path (make-array String 0))
            ^bytes (byte-array (map unchecked-byte codes))
            ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
     :cljs (.writeFileSync (js/require "fs") path (js/Buffer.from (clj->js codes)))))


(defn- symlink!
  "A symlink at `path` to `target`, relative to the link's directory."
  [path target]
  #?(:cljd (.createSync (dart-io/Link. path) target)
     :clj (java.nio.file.Files/createSymbolicLink
            (java.nio.file.Paths/get path (make-array String 0))
            (java.nio.file.Paths/get target (make-array String 0))
            (make-array java.nio.file.attribute.FileAttribute 0))
     :cljs (.symlinkSync (js/require "fs") target path)))


(defn- thrown-message
  [thunk]
  (try (thunk) nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-message e) (str e)))))


(defn- file-with
  "A fresh directory's file `f` holding `codes`: its path."
  [codes]
  (let [dir (temp-dir)
        path (str dir "/f")]
    (mkdirs! dir)
    (write-bytes! path codes)
    path))


(deftest read-bounded-reads-at-most-one-byte-past-the-bound
  (testing "no entry is nil, not a refusal"
    (let [dir (temp-dir)]
      (mkdirs! dir)
      (is (nil? (fs/read-bounded (str dir "/absent") 8)))))
  (testing "a file within the bound is read whole"
    (let [bs (fs/read-bounded (file-with [104 105]) 8)]
      (is (= 2 (fs/byte-length bs)))
      (is (= "hi" (fs/decode-utf8 bs)))))
  (testing "a file at the bound is read whole"
    (is (= 8 (fs/byte-length (fs/read-bounded (file-with (vec (repeat 8 120)))
                                              8)))))
  (testing "a larger file is read to one byte past the bound, no further"
    (is (= 9 (fs/byte-length (fs/read-bounded (file-with (vec (repeat 100 120)))
                                              8)))))
  (testing "an empty file is no bytes, not absence"
    (let [bs (fs/read-bounded (file-with []) 8)]
      (is (some? bs))
      (is (zero? (fs/byte-length bs))))))


(deftest read-bounded-opens-only-a-regular-file
  (testing "a directory is refused"
    (let [dir (temp-dir)]
      (mkdirs! (str dir "/d"))
      (is (= "it is not a regular file"
             (thrown-message #(fs/read-bounded (str dir "/d") 8))))))
  (testing "a symlink to a regular file is followed"
    (let [target (file-with [104 105])
          link (str target "-link")]
      (symlink! link "f")
      (is (= "hi" (fs/decode-utf8 (fs/read-bounded link 8))))))
  (testing "a dangling symlink is refused, never read as absence"
    (let [dir (temp-dir)]
      (mkdirs! dir)
      (symlink! (str dir "/link") "missing")
      (is (= "it is not a regular file"
             (thrown-message #(fs/read-bounded (str dir "/link") 8)))))))


(deftest decode-utf8-is-strict
  (testing "valid UTF-8, multi-byte included, decodes"
    (is (= "aéb" (fs/decode-utf8 (fs/read-bounded
                                   (file-with [97 0xc3 0xa9 98]) 8)))))
  (doseq [[label bad] [["an invalid byte" [0xff]]
                       ["a truncated sequence" [0xc3]]
                       ["an overlong encoding" [0xc0 0xaf]]
                       ["an encoded surrogate" [0xed 0xa0 0x80]]]]
    (testing label
      (let [bs (fs/read-bounded (file-with (into [97] (conj bad 98))) 8)]
        (is (some? (thrown-message #(fs/decode-utf8 bs)))
            "throws, never a replacement character")))))
