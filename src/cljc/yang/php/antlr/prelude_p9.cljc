(ns ^{:clj-kondo/config '{:lint-as {yang.php.antlr.quoted/defquoted clojure.core/def}}}
 yang.php.antlr.prelude-p9
  "P9 string, array, JSON and math builtins, written as guest code."
  (:require
    [yang.php.antlr.quoted :refer [defquoted]]))


(defquoted ^:private forms
  "P9 definitions in dependency order."
  '[[php/p9-string
     (fn [v] (php/to-string v))]
    [php/p9-substr
     (fn [s start length]
       (let [n (data/str-length s)
             a (if (< start 0) (if (> (+ n start) 0) (+ n start) 0) start)
             a (if (> a n) n a)
             b (if (= length :php/null)
                 n
                 (if (< length 0) (+ n length) (+ a length)))
             b (if (< b a) a (if (> b n) n b))]
         (if (>= a n) "" (data/substring s a b))))]
    [php/bi-substr
     (fn [args]
       (do (php/arity-between "substr" args 2 3 "at least 2")
           (php/p9-substr (php/p9-string (get args 0)) (php/to-int (get args 1))
                          (php/arg-or args 2 :php/null))))]
    [php/p9-index-of
     (fn [h needle from insensitive]
       (let [h (if insensitive (php/lc h) h) needle (if insensitive (php/lc needle) needle)
             nh (data/str-length h) nn (data/str-length needle)]
         (php/p9-index-loop h needle from (- nh nn) nn)))]
    [php/p9-index-loop
     (fn [h needle i last nn]
       (if (> i last)
         false
         (if (= nn 0) i
             (if (= (data/substring h i (+ i nn)) needle)
               i (php/p9-index-loop h needle (+ i 1) last nn)))))]
    [php/p9-strpos
     (fn [name args insensitive]
       (do (php/arity-between name args 2 3 "at least 2")
           (php/p9-index-of (php/p9-string (get args 0)) (php/p9-string (get args 1))
                            (php/to-int (php/arg-or args 2 0)) insensitive)))]
    [php/bi-strpos (fn [args] (php/p9-strpos "strpos" args false))]
    [php/bi-stripos (fn [args] (php/p9-strpos "stripos" args true))]
    [php/p9-rindex-loop
     (fn [h needle i nn]
       (if (< i 0) false
           (if (= (data/substring h i (+ i nn)) needle) i
               (php/p9-rindex-loop h needle (- i 1) nn))))]
    [php/bi-strrpos
     (fn [args]
       (do (php/arity-between "strrpos" args 2 3 "at least 2")
           (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))
                 nn (data/str-length n) off (php/to-int (php/arg-or args 2 0))]
             (php/p9-rindex-loop h n (- (data/str-length h) nn off) nn))))]
    [php/p9-replace-loop
     (fn [s old new i n out]
       (if (< i n)
         (if (= old "") out
             (if (if (<= (+ i (data/str-length old)) n)
                   (= (data/substring s i (+ i (data/str-length old))) old)
                   false)
               (php/p9-replace-loop s old new (+ i (data/str-length old)) n
                                    (data/str-concat out new))
               (php/p9-replace-loop s old new (+ i 1) n
                                    (data/str-concat out (data/substring s i (+ i 1))))))
         out))]
    [php/p9-replace
     (fn [s old new insensitive]
       (if insensitive
         (php/p9-ireplace s old new 0 (data/str-length s) "")
         (php/p9-replace-loop s old new 0 (data/str-length s) "")))]
    [php/p9-ireplace
     (fn [s old new i n out]
       (if (< i n)
         (let [k (data/str-length old)
               part (if (<= (+ i k) n) (data/substring s i (+ i k)) "")]
           (if (= (php/lc part) (php/lc old))
             (php/p9-ireplace s old new (+ i k) n (data/str-concat out new))
             (php/p9-ireplace s old new (+ i 1) n
                              (data/str-concat out (data/substring s i (+ i 1))))))
         out))]
    [php/bi-str-replace
     (fn [args]
       (do (php/arity-between "str_replace" args 3 4 "at least 3")
           (php/p9-replace (php/p9-string (get args 2)) (php/p9-string (get args 0))
                           (php/p9-string (get args 1)) false)))]
    [php/bi-str-ireplace
     (fn [args]
       (do (php/arity-between "str_ireplace" args 3 4 "at least 3")
           (php/p9-replace (php/p9-string (get args 2)) (php/p9-string (get args 0))
                           (php/p9-string (get args 1)) true)))]
    [php/bi-str-contains
     (fn [args]
       (do (php/arity-between "str_contains" args 2 2 "exactly 2")
           (not (= (php/p9-index-of (php/p9-string (get args 0)) (php/p9-string (get args 1)) 0 false) false))))]
    [php/bi-str-starts-with
     (fn [args]
       (do (php/arity-between "str_starts_with" args 2 2 "exactly 2")
           (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))]
             (= n (php/p9-substr h 0 (data/str-length n))))))]
    [php/bi-str-ends-with
     (fn [args]
       (do (php/arity-between "str_ends_with" args 2 2 "exactly 2")
           (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))
                 nn (data/str-length n)]
             (= n (php/p9-substr h (- (data/str-length h) nn) nn)))))]
    [php/p9-space?
     (fn [c] (if (= c 32) true (if (= c 9) true (if (= c 10) true (if (= c 13) true (if (= c 0) true (= c 11)))))))]
    [php/p9-trim-left
     (fn [cs i n]
       (if (< i n) (if (php/p9-space? (get cs i)) (php/p9-trim-left cs (+ i 1) n) i) i))]
    [php/p9-trim-right
     (fn [cs i]
       (if (>= i 0) (if (php/p9-space? (get cs i)) (php/p9-trim-right cs (- i 1)) (+ i 1)) 0))]
    [php/p9-trim
     (fn [s mode]
       (let [cs (data/str->code-points s) n (data/count cs)
             a (if (= mode :right) 0 (php/p9-trim-left cs 0 n))
             b (if (= mode :left) n (php/p9-trim-right cs (- n 1)))]
         (if (> a b) "" (data/code-points->str (data/subvec cs a b)))))]
    [php/bi-trim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :both))]
    [php/bi-ltrim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :left))]
    [php/bi-rtrim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :right))]
    [php/p9-upper-cp (fn [c] (if (>= c 97) (if (<= c 122) (- c 32) c) c))]
    [php/p9-case-loop
     (fn [cs upper i n out]
       (if (< i n) (php/p9-case-loop cs upper (+ i 1) n
                                     (php/vconj out (if upper (php/p9-upper-cp (get cs i)) (php/lower-cp (get cs i))))) out))]
    [php/p9-case
     (fn [s upper]
       (let [cs (data/str->code-points s)]
         (data/code-points->str (php/p9-case-loop cs upper 0 (data/count cs) []))))]
    [php/bi-strtolower (fn [args] (php/p9-case (php/p9-string (get args 0)) false))]
    [php/bi-strtoupper (fn [args] (php/p9-case (php/p9-string (get args 0)) true))]
    [php/p9-repeat
     (fn [s n out] (if (> n 0) (php/p9-repeat s (- n 1) (data/str-concat out s)) out))]
    [php/bi-str-repeat
     (fn [args]
       (let [n (php/to-int (get args 1))]
         (if (< n 0) (php/throw-error "ValueError" "str_repeat(): Argument #2 ($times) must be greater than or equal to 0")
             (php/p9-repeat (php/p9-string (get args 0)) n ""))))]
    [php/p9-pad-right (fn [out pad n] (if (< (data/str-length out) n) (php/p9-pad-right (data/str-concat out pad) pad n) out))]
    [php/bi-str-pad
     (fn [args]
       (let [s (php/p9-string (get args 0)) n (php/to-int (get args 1))
             pad (php/p9-string (php/arg-or args 2 " ")) type (php/to-int (php/arg-or args 3 1))
             needed (- n (data/str-length s))
             full (php/p9-pad-right "" pad needed)
             right (php/p9-substr full 0 needed)
             both (php/p9-substr full 0 (get (integer/quot-rem needed 2) 0))]
         (if (= type 0) (data/str-concat right s)
             (if (= type 1) (php/p9-substr (php/p9-pad-right s pad n) 0 n)
                 (data/str-concat both s (php/p9-substr right 0 (- n (data/str-length s) (data/str-length both))))))))]
    [php/p9-rev
     (fn [cs i out] (if (>= i 0) (php/p9-rev cs (- i 1) (php/vconj out (get cs i))) (data/code-points->str out)))]
    [php/bi-strrev (fn [args] (let [cs (data/str->code-points (php/p9-string (get args 0)))] (php/p9-rev cs (- (data/count cs) 1) [])))]
    [php/p9-strcmp
     (fn [args ci]
       (let [a (php/p9-string (get args 0)) b (php/p9-string (get args 1))]
         (data/str-compare (if ci (php/lc a) a) (if ci (php/lc b) b))))]
    [php/bi-strcmp (fn [args] (php/p9-strcmp args false))]
    [php/bi-strcasecmp (fn [args] (php/p9-strcmp args true))]
    [php/bi-strncmp
     (fn [args]
       (let [n (php/to-int (get args 2)) a (php/p9-substr (php/p9-string (get args 0)) 0 n)
             b (php/p9-substr (php/p9-string (get args 1)) 0 n)] (data/str-compare a b)))]
    [php/bi-strstr
     (fn [args]
       (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))
             i (php/p9-index-of h n 0 false)]
         (if (= i false) false
             (if (php/to-bool (php/arg-or args 2 false)) (php/p9-substr h 0 i) (php/p9-substr h i :php/null)))))]
    [php/bi-strrchr
     (fn [args]
       (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))
             i (php/p9-rindex-loop h n (- (data/str-length h) (data/str-length n)) (data/str-length n))]
         (if (= i false) false (php/p9-substr h i :php/null))))]
    [php/p9-count-sub
     (fn [h n i count]
       (if (> (+ i (data/str-length n)) (data/str-length h)) count
           (if (= (data/substring h i (+ i (data/str-length n))) n)
             (php/p9-count-sub h n (+ i (data/str-length n)) (+ count 1))
             (php/p9-count-sub h n (+ i 1) count))))]
    [php/bi-substr-count
     (fn [args]
       (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))]
         (if (= n "") (php/throw-error "ValueError" "substr_count(): Argument #2 ($needle) must not be empty")
             (php/p9-count-sub h n 0 0))))]
    [php/bi-nl2br
     (fn [args] (php/p9-replace (php/p9-replace (php/p9-string (get args 0)) "\r\n" "<br />\n" false) "\n" "<br />\n" false))]
    [php/bi-htmlspecialchars
     (fn [args]
       (let [s (php/p9-string (get args 0)) s (php/p9-replace s "&" "&amp;" false)
             s (php/p9-replace s "<" "&lt;" false) s (php/p9-replace s ">" "&gt;" false)
             s (php/p9-replace s "\"" "&quot;" false)] (php/p9-replace s "'" "&#039;" false)))]
    [php/bi-html-entity-decode
     (fn [args]
       (let [s (php/p9-string (get args 0)) s (php/p9-replace s "&lt;" "<" false)
             s (php/p9-replace s "&gt;" ">" false) s (php/p9-replace s "&quot;" "\"" false)
             s (php/p9-replace s "&#039;" "'" false)] (php/p9-replace s "&amp;" "&" false)))]
    [php/bi-addslashes
     (fn [args]
       (let [s (php/p9-string (get args 0)) s (php/p9-replace s "\\" "\\\\" false)
             s (php/p9-replace s "'" "\\'" false)] (php/p9-replace s "\"" "\\\"" false)))]
    [php/bi-stripslashes
     (fn [args]
       (let [s (php/p9-string (get args 0)) s (php/p9-replace s "\\'" "'" false)
             s (php/p9-replace s "\\\"" "\"" false)] (php/p9-replace s "\\\\" "\\" false)))]
    [php/bi-ord (fn [args] (let [s (php/p9-string (get args 0))] (if (= s "") 0 (get (data/str->code-points s) 0))))]
    [php/bi-chr (fn [args] (data/code-points->str (php/vconj [] (integer/bit-and (php/to-int (get args 0)) 255))))]
    [php/p9-hex-digit (fn [n] (data/substring "0123456789abcdef" n (+ n 1)))]
    [php/p9-hex-loop (fn [cs i out] (if (< i (data/count cs)) (let [c (get cs i) qr (integer/quot-rem c 16)] (php/p9-hex-loop cs (+ i 1) (data/str-concat out (php/p9-hex-digit (get qr 0)) (php/p9-hex-digit (get qr 1))))) out))]
    [php/bi-bin2hex (fn [args] (php/p9-hex-loop (data/str->code-points (php/p9-string (get args 0))) 0 ""))]
    [php/p9-hex-val
     (fn [c]
       (if (if (>= c 48) (<= c 57) false) (- c 48)
           (if (if (>= c 65) (<= c 70) false) (- c 55)
               (if (if (>= c 97) (<= c 102) false) (- c 87) 0))))]
    [php/p9-unhex-loop (fn [cs i out] (if (< i (data/count cs)) (php/p9-unhex-loop cs (+ i 2) (php/vconj out (+ (* (php/p9-hex-val (get cs i)) 16) (php/p9-hex-val (get cs (+ i 1)))))) (data/code-points->str out)))]
    [php/bi-hex2bin (fn [args] (let [cs (data/str->code-points (php/p9-string (get args 0)))] (if (= (get (integer/quot-rem (data/count cs) 2) 1) 0) (php/p9-unhex-loop cs 0 []) false)))]

    ;; arrays II
    [php/bi-explode
     (fn [args]
       (let [sep (php/p9-string (get args 0)) s (php/p9-string (get args 1))]
         (if (= sep "") (php/throw-error "ValueError" "explode(): Argument #1 ($separator) must not be empty")
             (php/p9-explode s sep 0 (php/empty-array)))))]
    [php/p9-explode
     (fn [s sep from out]
       (let [i (php/p9-index-of s sep from false)]
         (if (= i false) (php/array-append out (php/p9-substr s from :php/null))
             (php/p9-explode s sep (+ i (data/str-length sep))
                             (php/array-append out (php/p9-substr s from (- i from)))))))]
    [php/p9-str-split
     (fn [s n i out]
       (if (< i (data/str-length s)) (php/p9-str-split s n (+ i n) (php/array-append out (data/substring s i (if (> (+ i n) (data/str-length s)) (data/str-length s) (+ i n))))) out))]
    [php/bi-str-split (fn [args] (let [s (php/p9-string (get args 0)) n (php/to-int (php/arg-or args 1 1))] (if (< n 1) (php/throw-error "ValueError" "str_split(): Argument #2 ($length) must be greater than 0") (php/p9-str-split s n 0 (php/empty-array)))))]
    [php/bi-array-reverse
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_reverse" 1 "array") preserve (php/to-bool (php/arg-or args 1 false))]
         (php/p9-reverse a (- (php/array-count a) 1) preserve (php/empty-array))))]
    [php/p9-reverse
     (fn [a i preserve out]
       (if (>= i 0) (let [k (get (php/array-order a) i) v (php/array-get a k)]
                      (php/p9-reverse a (- i 1) preserve
                                      (if (if preserve true (not (php/int? k))) (php/array-put out k v) (php/array-append out v)))) out))]
    [php/bi-array-product
     (fn [args] (php/p9-product (php/arg-array (get args 0) "array_product" 1 "array") 0 1))]
    [php/p9-product
     (fn [a i out] (if (< i (php/array-count a)) (php/p9-product a (+ i 1) (php/mul out (php/array-get a (get (php/array-order a) i)))) out))]
    [php/bi-array-search
     (fn [args]
       (let [a (php/arg-array (get args 1) "array_search" 2 "haystack")]
         (php/p9-search a (get args 0) (php/to-bool (php/arg-or args 2 false)) 0)))]
    [php/p9-search
     (fn [a needle strict i]
       (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) v (php/array-get a k)] (if (if strict (php/identical v needle) (php/loose-eq v needle)) k (php/p9-search a needle strict (+ i 1)))) false))]
    [php/bi-array-is-list
     (fn [args] (php/p9-is-list (php/arg-array (get args 0) "array_is_list" 1 "array") 0))]
    [php/p9-is-list (fn [a i] (if (< i (php/array-count a)) (if (= (get (php/array-order a) i) i) (php/p9-is-list a (+ i 1)) false) true))]
    [php/bi-array-fill
     (fn [args] (php/p9-fill (php/to-int (get args 0)) (php/to-int (get args 1)) (get args 2) 0 (php/empty-array)))]
    [php/p9-fill (fn [start n v i out] (if (< i n) (php/p9-fill start n v (+ i 1) (php/array-put out (+ start i) (php/addref v))) out))]
    [php/bi-array-fill-keys
     (fn [args] (php/p9-fill-keys (php/arg-array (get args 0) "array_fill_keys" 1 "keys") (get args 1) 0 (php/empty-array)))]
    [php/p9-fill-keys (fn [keys v i out] (if (< i (php/array-count keys)) (php/p9-fill-keys keys v (+ i 1) (php/array-put out (php/array-key (php/array-get keys (get (php/array-order keys) i))) (php/addref v))) out))]
    [php/bi-array-combine
     (fn [args]
       (let [ks (php/arg-array (get args 0) "array_combine" 1 "keys") vs (php/arg-array (get args 1) "array_combine" 2 "values")]
         (if (= (php/array-count ks) (php/array-count vs)) (php/p9-combine ks vs 0 (php/empty-array)) (php/throw-error "ValueError" "array_combine(): arguments must have the same number of elements"))))]
    [php/p9-combine (fn [ks vs i out] (if (< i (php/array-count ks)) (php/p9-combine ks vs (+ i 1) (php/array-put out (php/array-key (php/array-get ks (get (php/array-order ks) i))) (php/addref (php/array-get vs (get (php/array-order vs) i))))) out))]
    [php/bi-array-flip
     (fn [args] (php/p9-flip (php/arg-array (get args 0) "array_flip" 1 "array") 0 (php/empty-array)))]
    [php/p9-flip (fn [a i out] (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) v (php/array-get a k)] (if (if (php/int? v) true (= (php/kind v) :string)) (php/p9-flip a (+ i 1) (php/array-put out (php/array-key v) k)) (php/p9-flip a (+ i 1) out))) out))]
    [php/bi-array-pad
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_pad" 1 "array") n (php/to-int (get args 1)) v (get args 2)]
         (if (< n 0) (php/p9-pad-left a (- n) v) (php/p9-pad-right-array a n v))))]
    [php/p9-pad-right-array (fn [a n v] (if (< (php/array-count a) n) (php/p9-pad-right-array (php/array-append a (php/addref v)) n v) a))]
    [php/p9-pad-left (fn [a n v] (let [head (php/p9-fill 0 (- n (php/array-count a)) v 0 (php/empty-array))] (php/spread-loop head a 0 (php/array-count a))))]
    [php/bi-array-chunk
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_chunk" 1 "array") n (php/to-int (get args 1)) preserve (php/to-bool (php/arg-or args 2 false))]
         (if (< n 1) (php/throw-error "ValueError" "array_chunk(): Argument #2 ($length) must be greater than 0") (php/p9-chunk a n preserve 0 (php/empty-array) (php/empty-array)))))]
    [php/p9-chunk
     (fn [a n preserve i out chunk]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i) v (php/array-get a k)
               c (if preserve (php/array-put chunk k v) (php/array-append chunk v))]
           (if (= (php/array-count c) n) (php/p9-chunk a n preserve (+ i 1) (php/array-append out c) (php/empty-array)) (php/p9-chunk a n preserve (+ i 1) out c)))
         (if (> (php/array-count chunk) 0) (php/array-append out chunk) out)))]
    [php/bi-array-column
     (fn [args] (php/p9-column (php/arg-array (get args 0) "array_column" 1 "array") (get args 1) (php/arg-or args 2 :php/null) 0 (php/empty-array)))]
    [php/p9-column
     (fn [rows col index i out]
       (if (< i (php/array-count rows))
         (let [r (php/array-get rows (get (php/array-order rows) i)) v (php/array-get r (php/array-key col))]
           (if (= index :php/null) (php/p9-column rows col index (+ i 1) (php/array-append out v)) (php/p9-column rows col index (+ i 1) (php/array-put out (php/array-key (php/array-get r (php/array-key index))) v)))) out))]
    [php/bi-array-slice
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_slice" 1 "array") n (php/array-count a)
             off (php/to-int (get args 1)) start (if (< off 0) (+ n off) off) start (if (< start 0) 0 start)
             len (php/arg-or args 2 :php/null) end (if (= len :php/null) n (if (< (php/to-int len) 0) (+ n (php/to-int len)) (+ start (php/to-int len))))
             preserve (php/to-bool (php/arg-or args 3 false))]
         (php/p9-slice a start (if (> end n) n end) preserve 0 (php/empty-array))))]
    [php/p9-slice
     (fn [a start end preserve i out]
       (if (< start end) (let [k (get (php/array-order a) start) v (php/array-get a k)] (php/p9-slice a (+ start 1) end preserve (+ i 1) (if (if preserve true (not (php/int? k))) (php/array-put out k v) (php/array-append out v)))) out))]
    [php/bi-array-splice
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_splice" 1 "array") n (php/array-count a) off (php/to-int (get args 1)) start (if (< off 0) (+ n off) off)
             len (php/arg-or args 2 :php/null) length (if (= len :php/null) (- n start) (php/to-int len)) replacement (php/arg-or args 3 [])]
         (php/p9-splice a start length replacement 0 (php/empty-array) (php/empty-array))))]
    [php/p9-splice
     (fn [a start len repl i out removed]
       (let [n (php/array-count a)
             removed (php/p9-slice a start (+ start len) false 0 (php/empty-array))
             head (php/p9-slice a 0 start false 0 (php/empty-array))
             tail (php/p9-slice a (+ start len) n false 0 (php/empty-array))
             r (if (= (php/kind repl) :array) repl (php/array-append (php/empty-array) repl))
             merged (php/spread-loop (php/spread-loop head r 0 (php/array-count r)) tail 0 (php/array-count tail))]
         (php/out1 removed merged)))]
    [php/bi-array-map
     (fn [args]
       (let [cb (get args 0) a (php/arg-array (get args 1) "array_map" 2 "array")]
         (php/p9-map cb a 0 (php/empty-array))))]
    [php/p9-map (fn [cb a i out] (if (< i (php/array-count a)) (php/p9-map cb a (+ i 1) (php/array-append out (php/call-value cb (php/vconj [] (php/array-get a (get (php/array-order a) i)))))) out))]
    [php/bi-array-filter
     (fn [args]
       (let [a (php/arg-array (get args 0) "array_filter" 1 "array") cb (php/arg-or args 1 :php/null)] (php/p9-filter a cb 0 (php/empty-array))))]
    [php/p9-filter
     (fn [a cb i out]
       (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) v (php/array-get a k) keep (if (= cb :php/null) (php/to-bool v) (php/to-bool (php/call-value cb (php/vconj [] v))))] (php/p9-filter a cb (+ i 1) (if keep (php/array-put out k v) out))) out))]
    [php/bi-array-reduce
     (fn [args] (let [a (php/arg-array (get args 0) "array_reduce" 1 "array")] (php/p9-reduce a (get args 1) (php/arg-or args 2 :php/null) 0)))]
    [php/p9-reduce (fn [a cb acc i] (if (< i (php/array-count a)) (php/p9-reduce a cb (php/call-value cb (php/pair acc (php/array-get a (get (php/array-order a) i)))) (+ i 1)) acc))]
    [php/bi-array-unique
     (fn [args] (php/p9-unique (php/arg-array (get args 0) "array_unique" 1 "array") 0 (php/empty-array)))]
    [php/p9-unique
     (fn [a i out]
       (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) v (php/array-get a k)] (php/p9-unique a (+ i 1) (if (= (php/p9-search out v false 0) false) (php/array-put out k v) out))) out))]
    [php/p9-diff
     (fn [a b intersect i out]
       (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) v (php/array-get a k) has (not (= (php/p9-search b v false 0) false))] (php/p9-diff a b intersect (+ i 1) (if (= has intersect) (php/array-put out k v) out))) out))]
    [php/bi-array-diff (fn [args] (php/p9-diff (get args 0) (get args 1) false 0 (php/empty-array)))]
    [php/bi-array-intersect (fn [args] (php/p9-diff (get args 0) (get args 1) true 0 (php/empty-array)))]
    [php/p9-diff-key
     (fn [a b intersect i out]
       (if (< i (php/array-count a)) (let [k (get (php/array-order a) i) has (php/array-has? b k)] (php/p9-diff-key a b intersect (+ i 1) (if (= has intersect) (php/array-put out k (php/array-get a k)) out))) out))]
    [php/bi-array-diff-key (fn [args] (php/p9-diff-key (get args 0) (get args 1) false 0 (php/empty-array)))]
    [php/bi-array-intersect-key (fn [args] (php/p9-diff-key (get args 0) (get args 1) true 0 (php/empty-array)))]
    [php/bi-range
     (fn [args]
       (let [a (get args 0) b (get args 1) step (php/arg-or args 2 1)]
         (if (if (php/int? a) true (php/float? a)) (php/p9-range-num a b step) (php/p9-range-string (php/to-string a) (php/to-string b) step))))]
    [php/p9-range-num (fn [a b step] (if (<= a b) (php/p9-range-up a b (php/abs step) (php/empty-array)) (php/p9-range-down a b (php/abs step) (php/empty-array))))]
    [php/p9-range-up (fn [a b step out] (if (<= a b) (php/p9-range-up (php/add a step) b step (php/array-append out a)) out))]
    [php/p9-range-down (fn [a b step out] (if (>= a b) (php/p9-range-down (php/sub a step) b step (php/array-append out a)) out))]
    [php/p9-range-string (fn [a b step] (let [ca (get (data/str->code-points a) 0) cb (get (data/str->code-points b) 0) nums (php/p9-range-num ca cb step)] (php/p9-range-chars nums 0 (php/empty-array))))]
    [php/p9-range-chars (fn [a i out] (if (< i (php/array-count a)) (php/p9-range-chars a (+ i 1) (php/array-append out (data/code-points->str (php/vconj [] (php/array-get a (get (php/array-order a) i)))))) out))]

    ;; JSON: the decoder state is [code-points index assoc? throw?].
    [php/json-fail
     (fn [st message]
       (if (get (cell/get st) 3) (php/throw-error "JsonException" message) :php/null))]
    [php/json-index!
     (fn [st i] (cell/set! st (assoc (cell/get st) 1 i)))]
    [php/json-space
     (fn [st]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (< i (data/count cs))
           (let [c (get cs i)]
             (if (if (= c 32) true (if (= c 9) true (if (= c 10) true (= c 13))))
               (do (php/json-index! st (+ i 1)) (php/json-space st))
               st))
           st)))]
    [php/json-escape-cp
     (fn [c]
       (if (= c 34) "\\\""
           (if (= c 92) "\\\\"
               (if (= c 8) "\\b" (if (= c 9) "\\t" (if (= c 10) "\\n" (if (= c 12) "\\f" (if (= c 13) "\\r" :php/null))))))))]
    [php/json-escape-loop
     (fn [cs i out]
       (if (< i (data/count cs))
         (let [c (get cs i) esc (php/json-escape-cp c)]
           (php/json-escape-loop cs (+ i 1)
                                 (data/str-concat out
                                                  (if (= esc :php/null)
                                                    (if (< c 32) "?" (data/code-points->str (php/vconj [] c))) esc))))
         (data/str-concat "\"" (data/str-concat out "\""))))]
    [php/json-string (fn [s] (php/json-escape-loop (data/str->code-points s) 0 ""))]
    [php/json-key (fn [k] (php/json-string (php/to-string k)))]
    [php/json-indent
     (fn [n out] (if (> n 0) (php/json-indent (- n 1) (data/str-concat out "    ")) out))]
    [php/json-array-values
     (fn [a pretty depth i out]
       (if (< i (php/array-count a))
         (let [sep (if (= i 0) "" ",") br (if pretty (data/str-concat "\n" (php/json-indent (+ depth 1) "")) "")]
           (php/json-array-values a pretty depth (+ i 1)
                                  (data/str-concat out sep br (php/json-encode (php/array-get a (get (php/array-order a) i)) pretty (+ depth 1)))))
         (data/str-concat out (if (if pretty (> i 0) false) (data/str-concat "\n" (php/json-indent depth "")) "") "]")))]
    [php/json-array-pairs
     (fn [a pretty depth i out]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i) sep (if (= i 0) "" ",")
               br (if pretty (data/str-concat "\n" (php/json-indent (+ depth 1) "")) "")
               colon (if pretty ": " ":")]
           (php/json-array-pairs a pretty depth (+ i 1)
                                 (data/str-concat out sep br (php/json-key k) colon
                                                  (php/json-encode (php/array-get a k) pretty (+ depth 1)))))
         (data/str-concat out (if (if pretty (> i 0) false) (data/str-concat "\n" (php/json-indent depth "")) "") "}")))]
    [php/json-encode
     (fn [v pretty depth]
       (let [k (php/kind v)]
         (if (= k :null) "null"
             (if (= k :bool) (if v "true" "false")
                 (if (= k :string) (php/json-string v)
                     (if (= k :int) (integer/format v)
                         (if (= k :float)
                           (let [h (php/h v)] (if (php/nan? h) (php/throw-error "JsonException" "Inf and NaN cannot be JSON encoded") (php/float-text v 17)))
                           (if (= k :array)
                             (if (php/p9-is-list v 0)
                               (data/str-concat "[" (php/json-array-values v pretty depth 0 ""))
                               (data/str-concat "{" (php/json-array-pairs v pretty depth 0 "")))
                             (php/throw-error "JsonException" "Type is not supported")))))))))]
    [php/bi-json-encode
     (fn [args]
       (do (php/arity-between "json_encode" args 1 3 "at least 1")
           (php/json-encode (get args 0) (= (integer/bit-and (php/to-int (php/arg-or args 1 0)) 128) 128) 0)))]
    [php/json-literal
     (fn [st text value]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) n (data/str-length text)]
         (if (if (<= (+ i n) (data/count cs)) (= text (data/code-points->str (data/subvec cs i (+ i n)))) false)
           (do (php/json-index! st (+ i n)) value)
           (php/json-fail st "Syntax error"))))]
    [php/json-read-string-loop
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (>= i (data/count cs)) (php/json-fail st "Unterminated string")
             (let [c (get cs i)]
               (do (php/json-index! st (+ i 1))
                   (if (= c 34) out
                       (if (= c 92) (php/json-read-escape st out)
                           (php/json-read-string-loop st (php/vconj out c)))))))))]
    [php/json-read-escape
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
         (do (php/json-index! st (+ i 1))
             (if (= c 117) (php/json-read-unicode st out)
                 (php/json-read-string-loop
                   st (php/vconj out (if (= c 110) 10 (if (= c 114) 13 (if (= c 116) 9 (if (= c 98) 8 (if (= c 102) 12 c)))))))))))]
    [php/json-read-unicode
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) cp (php/json-hex4 cs i 0 0)]
         (do (php/json-index! st (+ i 4))
             (php/json-read-string-loop st (data/into out (php/json-utf8 cp))))))]
    [php/json-hex4
     (fn [cs i n cp]
       (if (< n 4) (php/json-hex4 cs (+ i 1) (+ n 1) (+ (* cp 16) (php/p9-hex-val (get cs i)))) cp))]
    [php/json-utf8
     (fn [cp]
       (if (< cp 128) [cp]
           (if (< cp 2048) [(+ 192 (get (integer/quot-rem cp 64) 0)) (+ 128 (get (integer/quot-rem cp 64) 1))]
               [(+ 224 (get (integer/quot-rem cp 4096) 0))
                (+ 128 (get (integer/quot-rem (get (integer/quot-rem cp 4096) 1) 64) 0))
                (+ 128 (get (integer/quot-rem cp 64) 1))])))]
    [php/json-read-string
     (fn [st] (data/code-points->str (php/json-read-string-loop st [])))]
    [php/json-number
     (fn [st]
       (let [cs (get (cell/get st) 0) start (get (cell/get st) 1) i (php/json-number-end cs start)]
         (do (php/json-index! st i)
             (let [s (data/code-points->str (data/subvec cs start i))]
               (if (= (php/numeric-string s) :php/null) (php/json-fail st "Syntax error")
                   (let [r (php/numeric-string s)] (if (= (get r 0) :int) (get r 1) (php/to-float s))))))))]
    [php/json-number-end
     (fn [cs i]
       (if (< i (data/count cs)) (let [c (get cs i)] (if (if (>= c 48) (<= c 57) false) (php/json-number-end cs (+ i 1)) (if (if (= c 45) true (if (= c 43) true (if (= c 46) true (if (= c 101) true (= c 69))))) (php/json-number-end cs (+ i 1)) i))) i))]
    [php/json-array-read
     (fn [st out]
       (do (php/json-space st)
           (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
             (if (= c 93) (do (php/json-index! st (+ i 1)) out)
                 (let [v (php/json-value st) a (php/array-append out v)]
                   (do (php/json-space st) (php/json-array-next st a)))))))]
    [php/json-array-next
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
         (do (php/json-index! st (+ i 1))
             (if (= c 44) (php/json-array-read st out) (if (= c 93) out (php/json-fail st "Syntax error"))))))]
    [php/json-object-read
     (fn [st out]
       (do (php/json-space st)
           (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
             (if (= c 125)
               (do (php/json-index! st (+ i 1)) out)
               (do (php/json-index! st (+ i 1))
                   (let [k (php/json-read-string st)]
                     (do (php/json-space st)
                         (php/json-index! st (+ (get (cell/get st) 1) 1))
                         (let [v (php/json-value st) a (php/array-put out k v)]
                           (do (php/json-space st) (php/json-object-next st a))))))))))]
    [php/json-object-next
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
         (do (php/json-index! st (+ i 1))
             (if (= c 44) (php/json-object-read st out) (if (= c 125) out (php/json-fail st "Syntax error"))))))]
    [php/json-value
     (fn [st]
       (do (php/json-space st)
           (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) c (get cs i)]
             (do (php/json-index! st (+ i 1))
                 (if (= c 34) (php/json-read-string st)
                     (if (= c 123) (php/json-object-read st (php/empty-array))
                         (if (= c 91) (php/json-array-read st (php/empty-array))
                             (if (= c 116) (php/json-literal st "rue" true)
                                 (if (= c 102) (php/json-literal st "alse" false)
                                     (if (= c 110) (php/json-literal st "ull" :php/null)
                                         (do (php/json-index! st i) (php/json-number st))))))))))))]
    [php/bi-json-decode
     (fn [args]
       (do (php/arity-between "json_decode" args 1 4 "at least 1")
           (let [flags (php/to-int (php/arg-or args 3 0)) assoc (php/to-bool (php/arg-or args 1 false))
                 st (cell/new
                      (php/vconj
                        (php/vconj
                          (php/vconj
                            (php/vconj [] (data/str->code-points (php/p9-string (get args 0))))
                            0)
                          assoc)
                        (= (integer/bit-and flags 4194304) 4194304)))
                 v (php/json-value st)]
             (do (php/json-space st)
                 (if (= (get (cell/get st) 1) (data/count (get (cell/get st) 0))) v (php/json-fail st "Syntax error"))))))]

    ;; math II
    [php/abs (fn [x] (if (php/int? x) (if (< x 0) (integer/neg x) x) (php/f (php/abs-h (php/fl x)))))]
    [php/bi-abs (fn [args] (php/abs (get args 0)))]
    [php/bi-ceil
     (fn [args] (let [x (php/to-float (get args 0)) h (php/h x) i (integer/from-float h)] (php/f (if (> h (integer/to-float i)) (integer/to-float (integer/add i 1)) (integer/to-float i)))))]
    [php/bi-floor
     (fn [args] (let [x (php/to-float (get args 0)) h (php/h x) i (integer/from-float h)] (php/f (if (< h (integer/to-float i)) (integer/to-float (integer/sub i 1)) (integer/to-float i)))))]
    [php/bi-round
     (fn [args] (let [h (php/h (php/to-float (get args 0))) a (php/abs-h h) i (integer/from-float a) f (- a (integer/to-float i)) r (if (>= f (data/float-value 0.5)) (integer/add i 1) i)] (php/f (if (< h 0) (php/neg-h (integer/to-float r)) (integer/to-float r)))))]
    [php/p9-sqrt-loop (fn [x guess i] (if (< i 40) (php/p9-sqrt-loop x (/ (+ guess (/ x guess)) (data/float-value 2)) (+ i 1)) guess))]
    [php/bi-sqrt (fn [args] (let [x (php/h (php/to-float (get args 0)))] (if (< x 0) (php/nan-const) (if (= x 0) (php/f x) (php/f (php/p9-sqrt-loop x (if (> x 1) x 1) 0))))))]
    [php/bi-pow (fn [args] (php/pow (get args 0) (get args 1)))]
    [php/p9-minmax (fn [args i best want-max] (if (< i (data/count args)) (let [v (get args i) c (php/cmp v best)] (php/p9-minmax args (+ i 1) (if (if want-max (> c 0) (< c 0)) v best) want-max)) best))]
    [php/bi-min (fn [args] (php/p9-minmax args 1 (get args 0) false))]
    [php/bi-max (fn [args] (php/p9-minmax args 1 (get args 0) true))]
    [php/bi-is-finite (fn [args] (let [h (php/h (php/to-float (get args 0)))] (if (php/nan? h) false (not (= (php/abs-h h) (php/inf))))))]
    [php/bi-is-infinite (fn [args] (= (php/abs-h (php/h (php/to-float (get args 0)))) (php/inf)))]
    [php/bi-array-multisort (fn [args] (php/throw-error "Error" "array_multisort is a prefer-ref builtin and is not supported (corner C6)"))]])


(def definitions
  (vec forms))
