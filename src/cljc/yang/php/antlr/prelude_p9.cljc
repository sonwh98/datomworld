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
     ;; the rightmost match at or above lo, at or below the starting i
     (fn [h needle i nn lo]
       (if (< i lo) false
           (if (= (data/substring h i (+ i nn)) needle) i
               (php/p9-rindex-loop h needle (- i 1) nn lo))))]
    [php/bi-strrpos
     ;; a non-negative offset is the search's lower bound (searching back
     ;; from the end); a negative one starts that many characters from the
     ;; end and searches back from there; out-of-range offsets are a
     ;; ValueError, like PHP 8
     (fn [args]
       (do (php/arity-between "strrpos" args 2 3 "at least 2")
           (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))
                 nn (data/str-length n) len (data/str-length h)
                 off (php/to-int (php/arg-or args 2 0))]
             (if (= n "")
               (php/throw-error "ValueError" "strrpos(): Argument #2 ($needle) must not be empty")
               (if (if (> off len) true (< off (integer/neg len)))
                 (php/throw-error "ValueError" "strrpos(): Argument #3 ($offset) must be contained in argument #1 ($haystack)")
                 (if (>= off 0)
                   (php/p9-rindex-loop h n (- len nn) nn off)
                   (let [hi (+ len off) top (- len nn)]
                     (php/p9-rindex-loop h n (if (> hi top) top hi) nn 0))))))))]
    [php/p9-replace-loop
     ;; answers [output replacements]; an empty search never matches (PHP
     ;; returns the subject unchanged), so the loop always advances
     (fn [s old new i n out c]
       (if (< i n)
         (if (if (<= (+ i (data/str-length old)) n)
               (= (data/substring s i (+ i (data/str-length old))) old)
               false)
           (php/p9-replace-loop s old new (+ i (data/str-length old)) n
                                (data/str-concat out new) (+ c 1))
           (php/p9-replace-loop s old new (+ i 1) n
                                (data/str-concat out (data/substring s i (+ i 1))) c))
         (php/pair out c)))]
    [php/p9-ireplace
     (fn [s old new i n out c]
       (if (< i n)
         (let [k (data/str-length old)
               part (if (<= (+ i k) n) (data/substring s i (+ i k)) "")]
           (if (= (php/lc part) (php/lc old))
             (php/p9-ireplace s old new (+ i k) n (data/str-concat out new) (+ c 1))
             (php/p9-ireplace s old new (+ i 1) n
                              (data/str-concat out (data/substring s i (+ i 1))) c)))
         (php/pair out c)))]
    [php/p9-replace-one
     ;; one search string over a subject string: [output replacements]
     (fn [s old new insensitive]
       (if (= old "")
         (php/pair s 0)
         (if insensitive
           (php/p9-ireplace s old new 0 (data/str-length s) "" 0)
           (php/p9-replace-loop s old new 0 (data/str-length s) "" 0))))]
    [php/p9-replace
     (fn [s old new insensitive]
       (get (php/p9-replace-one s old new insensitive) 0))]
    [php/p9-replace-each
     ;; each search in turn over one subject string: [output replacements]
     (fn [s searches replaces insensitive i n out c]
       (if (< i n)
         (let [r (php/p9-replace-one out (get searches i)
                                     (if (< i (data/count replaces)) (get replaces i) "")
                                     insensitive)]
           (php/p9-replace-each s searches replaces insensitive (+ i 1) n
                                (get r 0) (+ c (get r 1))))
         (php/pair out c)))]
    [php/p9-replace-subject
     ;; the search list over every subject: [result replacements]
     (fn [subject searches replaces insensitive]
       (if (= (php/kind subject) :array)
         (let [r (php/p9-replace-each-array subject searches replaces insensitive
                                            0 (php/array-count subject) (php/empty-array) 0)]
           (php/pair (get r 0) (get r 1)))
         (php/p9-replace-each (php/p9-string subject) searches replaces insensitive
                              0 (data/count searches) (php/p9-string subject) 0)))]
    [php/p9-replace-each-array
     (fn [subject searches replaces insensitive i n out c]
       (if (< i n)
         (let [k (get (php/array-order subject) i)
               r (php/p9-replace-each (php/p9-string (php/array-get subject k))
                                      searches replaces insensitive
                                      0 (data/count searches)
                                      (php/p9-string (php/array-get subject k)) 0)]
           (php/p9-replace-each-array subject searches replaces insensitive (+ i 1) n
                                      (php/array-put out k (get r 0)) (+ c (get r 1))))
         (php/pair out c)))]
    [php/p9-as-vec
     ;; a search/replacement operand as a vector of strings
     (fn [v]
       (if (= (php/kind v) :array)
         (php/p9-vec-strings v 0 (php/array-count v) [])
         (php/vconj [] (php/p9-string v))))]
    [php/p9-vec-strings
     (fn [a i n out]
       (if (< i n)
         (php/p9-vec-strings a (+ i 1) n
                             (php/vconj out (php/p9-string (php/array-get a (get (php/array-order a) i)))))
         out))]
    [php/p9-fill-vec
     ;; a scalar replacement applies to every search, so it is replicated
     (fn [v n out]
       (if (< (data/count out) n) (php/p9-fill-vec v n (php/vconj out v)) out))]
    [php/p9-repl-vec
     (fn [v n]
       (if (= (php/kind v) :array) (php/p9-as-vec v)
           (php/p9-fill-vec (php/p9-string v) n [])))]
    [php/bi-str-replace
     (fn [args]
       (do (php/arity-between "str_replace" args 3 4 "at least 3")
           (let [searches (php/p9-as-vec (get args 0))
                 r (php/p9-replace-subject (get args 2) searches
                                           (php/p9-repl-vec (get args 1) (data/count searches)) false)]
             (php/pair (get r 0) (php/vconj [] (get r 1))))))]
    [php/bi-str-ireplace
     (fn [args]
       (do (php/arity-between "str_ireplace" args 3 4 "at least 3")
           (let [searches (php/p9-as-vec (get args 0))
                 r (php/p9-replace-subject (get args 2) searches
                                           (php/p9-repl-vec (get args 1) (data/count searches)) true)]
             (php/pair (get r 0) (php/vconj [] (get r 1))))))]
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
    [php/p9-mask-chars
     ;; the character list as code points, expanding a..z ranges
     (fn [cs i n out]
       (if (< i n)
         (let [c (get cs i)]
           (if (if (= c 46) (if (< (+ i 2) n) (= (get cs (+ i 1)) 46) false) false)
             (php/p9-mask-range cs (get cs (- i 1)) (get cs (+ i 2)) out i n)
             (php/p9-mask-chars cs (+ i 1) n (php/vconj out c))))
         out))]
    [php/p9-mask-range
     (fn [cs lo hi out i n]
       (php/p9-mask-chars cs (+ i 3) n (php/p9-mask-fill lo hi out)))]
    [php/p9-mask-fill
     (fn [lo hi out]
       (if (<= lo hi) (php/p9-mask-fill (+ lo 1) hi (php/vconj out lo)) out))]
    [php/p9-mask-has?
     (fn [mask i n c]
       (if (< i n) (if (= (get mask i) c) true (php/p9-mask-has? mask (+ i 1) n c)) false))]
    [php/p9-trim-at?
     (fn [c mask has-mask]
       (if has-mask (php/p9-mask-has? mask 0 (data/count mask) c) (php/p9-space? c)))]
    [php/p9-trim-left
     (fn [cs mask has-mask i n]
       (if (< i n) (if (php/p9-trim-at? (get cs i) mask has-mask) (php/p9-trim-left cs mask has-mask (+ i 1) n) i) i))]
    [php/p9-trim-right
     (fn [cs mask has-mask i]
       (if (>= i 0) (if (php/p9-trim-at? (get cs i) mask has-mask) (php/p9-trim-right cs mask has-mask (- i 1)) (+ i 1)) 0))]
    [php/p9-trim
     (fn [s mode mask]
       (let [cs (data/str->code-points s) n (data/count cs)
             has-mask (not (= mask :php/null))
             mcs (if has-mask (php/p9-mask-chars (data/str->code-points (php/p9-string mask)) 0 (data/str-length (php/p9-string mask)) []) [])
             a (if (= mode :right) 0 (php/p9-trim-left cs mcs has-mask 0 n))
             b (if (= mode :left) n (php/p9-trim-right cs mcs has-mask (- n 1)))]
         (if (> a b) "" (data/code-points->str (data/subvec cs a b)))))]
    [php/bi-trim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :both (php/arg-or args 1 :php/null)))]
    [php/bi-ltrim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :left (php/arg-or args 1 :php/null)))]
    [php/bi-rtrim (fn [args] (php/p9-trim (php/p9-string (get args 0)) :right (php/arg-or args 1 :php/null)))]
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
     ;; over-length input is returned unchanged; an empty pad string with
     ;; padding to do is a ValueError
     (fn [args]
       (do (php/arity-between "str_pad" args 2 4 "at least 2")
           (let [s (php/p9-string (get args 0)) n (php/to-int (get args 1))
                 pad (php/p9-string (php/arg-or args 2 " ")) type (php/to-int (php/arg-or args 3 1))
                 needed (- n (data/str-length s))]
             (if (<= needed 0) s
                 (if (= pad "") (php/throw-error "ValueError" "str_pad(): Argument #3 ($pad_string) must not be empty")
                     (let [full (php/p9-pad-right "" pad needed)
                           right (php/p9-substr full 0 needed)
                           both (php/p9-substr full 0 (get (integer/quot-rem needed 2) 0))]
                       (if (= type 0) (data/str-concat right s)
                           (if (= type 1) (data/str-concat s right)
                               (data/str-concat both s (php/p9-substr right 0 (- n (data/str-length s) (data/str-length both))))))))))))]
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
             i (php/p9-rindex-loop h n (- (data/str-length h) (data/str-length n)) (data/str-length n) 0)]
         (if (= i false) false (php/p9-substr h i :php/null))))]
    [php/p9-count-sub
     (fn [h n i count]
       (if (> (+ i (data/str-length n)) (data/str-length h)) count
           (if (= (data/substring h i (+ i (data/str-length n))) n)
             (php/p9-count-sub h n (+ i (data/str-length n)) (+ count 1))
             (php/p9-count-sub h n (+ i 1) count))))]
    [php/bi-substr-count
     (fn [args]
       (do (php/arity-between "substr_count" args 2 4 "at least 2")
           (let [h (php/p9-string (get args 0)) n (php/p9-string (get args 1))]
             (if (= n "") (php/throw-error "ValueError" "substr_count(): Argument #2 ($needle) must not be empty")
                 (let [len (data/str-length h)
                       off (php/to-int (php/arg-or args 2 0))
                       start (if (< off 0) (+ len off) off)
                       start (if (< start 0) 0 (if (> start len) len start))
                       l (php/arg-or args 3 :php/null)
                       end (if (= l :php/null) len (let [ll (php/to-int l)] (if (< ll 0) (+ len ll) (+ start ll))))
                       end (if (< end start) start (if (> end len) len end))]
                   (php/p9-count-sub (php/p9-substr h start (- end start)) n 0 0))))))]
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
     ;; bounds are normalized per PHP; an omitted replacement is an empty
     ;; PHP array, a scalar one a single element
     (fn [args]
       (do (php/arity-between "array_splice" args 2 4 "at least 2")
           (let [a (php/arg-array (get args 0) "array_splice" 1 "array") n (php/array-count a)
                 off (php/to-int (get args 1))
                 start (if (< off 0) (+ n off) off)
                 start (if (< start 0) 0 (if (> start n) n start))
                 l (php/arg-or args 2 :php/null)
                 length (if (= l :php/null) (- n start)
                            (let [ll (php/to-int l)]
                              (if (< ll 0) (+ (- n start) ll) ll)))
                 length (if (< length 0) 0 (if (> length (- n start)) (- n start) length))
                 replacement (if (< 3 (data/count args)) (get args 3) (php/empty-array))]
             (php/p9-splice a start length replacement
                            0 (php/empty-array) (php/empty-array)))))]
    [php/p9-splice
     (fn [a start len repl i out removed]
       (let [n (php/array-count a)
             removed (php/p9-slice a start (+ start len) false 0 (php/empty-array))
             head (php/p9-slice a 0 start false 0 (php/empty-array))
             tail (php/p9-slice a (+ start len) n false 0 (php/empty-array))
             r (if (= (php/kind repl) :array)
                 (php/p9-splice-repl repl 0 (php/array-count repl) (php/empty-array))
                 (if (= repl :php/null) (php/empty-array) (php/array-append (php/empty-array) repl)))
             merged (php/spread-loop (php/spread-loop head r 0 (php/array-count r)) tail 0 (php/array-count tail))]
         (php/out1 removed merged)))]
    [php/p9-splice-repl
     ;; the replacement is a plain list: string keys are discarded and an
     ;; explicit null is already the empty array by the time we get here
     (fn [r i n out]
       (if (< i n)
         (php/p9-splice-repl r (+ i 1) n
                             (php/array-append out (php/array-get r (get (php/array-order r) i))))
         out))]
    [php/bi-array-map
     ;; a single array keeps its keys; further arrays make it variadic (the
     ;; result is a list); a null callback zips the arrays instead
     (fn [args]
       (do (php/arity-between "array_map" args 2 1000000 "at least 2")
           (let [cb (get args 0) a (php/arg-array (get args 1) "array_map" 2 "array")]
             (if (> (data/count args) 2)
               (let [arrs (php/p9-map-arrays args 1 (data/count args) [])]
                 (php/p9-map-multi cb arrs 0
                                   (php/p9-longest arrs 0 (data/count arrs) 0)
                                   (php/empty-array)))
               (if (= cb :php/null) a (php/p9-map-single cb a 0 (php/empty-array)))))))]
    [php/p9-map-single
     (fn [cb a i out]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i)]
           (php/p9-map-single cb a (+ i 1)
                              (php/array-put out k (php/call-value cb (php/vconj [] (php/array-get a k))))))
         out))]
    [php/p9-map-arrays
     (fn [args i n out]
       (if (< i n)
         (php/p9-map-arrays args (+ i 1) n
                            (php/vconj out (php/arg-array (get args i) "array_map" (+ i 1) "array")))
         out))]
    [php/p9-longest
     (fn [arrs i n best]
       (if (< i n)
         (php/p9-longest arrs (+ i 1) n
                         (if (> (php/array-count (get arrs i)) best) (php/array-count (get arrs i)) best))
         best))]
    [php/p9-map-multi
     (fn [cb arrs i n out]
       (if (< i n)
         (php/p9-map-multi cb arrs (+ i 1) n
                           (php/array-append out (php/p9-map-row cb arrs i 0 (data/count arrs))))
         out))]
    [php/p9-map-row
     (fn [cb arrs i j m]
       (let [cell (php/p9-map-cell arrs i j m [])]
         (if (= cb :php/null) (php/p9-row-array cell 0 (data/count cell) (php/empty-array))
             (php/call-value cb cell))))]
    [php/p9-map-cell
     (fn [arrs i j m out]
       (if (< j m)
         (let [a (get arrs j)]
           (php/p9-map-cell arrs i (+ j 1) m
                            (php/vconj out (if (< i (php/array-count a))
                                             (php/array-get a (get (php/array-order a) i))
                                             :php/null))))
         out))]
    [php/p9-row-array
     (fn [cell i n out]
       (if (< i n) (php/p9-row-array cell (+ i 1) n (php/array-append out (get cell i))) out))]
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
     ;; the default is SORT_STRING (2): string comparison, so "1.0" and
     ;; "1" are distinct; SORT_REGULAR (0) compares loosely, SORT_NUMERIC
     ;; (1) numerically, anything else as strings
     (fn [args]
       (do (php/arity-between "array_unique" args 1 2 "at least 1")
           (php/p9-unique (php/arg-array (get args 0) "array_unique" 1 "array")
                          (php/to-int (php/arg-or args 1 2)) 0 (php/empty-array))))]
    [php/p9-unique-eq?
     (fn [flags a b]
       (if (= flags 0) (php/loose-eq a b)
           (if (= flags 1) (= (php/to-float a) (php/to-float b))
               (= (php/p9-string a) (php/p9-string b)))))]
    [php/p9-unique-seen?
     (fn [out v flags i]
       (if (< i (php/array-count out))
         (if (php/p9-unique-eq? flags (php/array-get out (get (php/array-order out) i)) v)
           true (php/p9-unique-seen? out v flags (+ i 1)))
         false))]
    [php/p9-unique
     (fn [a flags i out]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i) v (php/array-get a k)]
           (php/p9-unique a flags (+ i 1)
                          (if (php/p9-unique-seen? out v flags 0) out (php/array-put out k v))))
         out))]
    [php/p9-diff-str-has?
     ;; string comparison, like PHP: "1.0" and "1" are different strings
     (fn [a v i]
       (if (< i (php/array-count a))
         (if (= (php/p9-string (php/array-get a (get (php/array-order a) i))) (php/p9-string v))
           true (php/p9-diff-str-has? a v (+ i 1)))
         false))]
    [php/p9-diff-others
     ;; found in any (diff) or in every (intersect) further array
     (fn [v others j m all]
       (if (< j m)
         (let [found (php/p9-diff-str-has? (get others j) v 0)]
           (if all
             (if found (php/p9-diff-others v others (+ j 1) m all) false)
             (if found true (php/p9-diff-others v others (+ j 1) m all))))
         all))]
    [php/p9-diff
     (fn [a others intersect i out]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i) v (php/array-get a k)
               has (php/p9-diff-others v others 0 (data/count others) intersect)]
           (php/p9-diff a others intersect (+ i 1)
                        (if (= has intersect) (php/array-put out k v) out)))
         out))]
    [php/p9-diff-args
     (fn [name args i n out]
       (if (< i n)
         (php/p9-diff-args name args (+ i 1) n
                           (php/vconj out (php/arg-array (get args i) name (+ i 1) "array")))
         out))]
    [php/bi-array-diff
     (fn [args]
       (do (php/arity-between "array_diff" args 2 1000000 "at least 2")
           (php/p9-diff (php/arg-array (get args 0) "array_diff" 1 "array")
                        (php/p9-diff-args "array_diff" args 1 (data/count args) [])
                        false 0 (php/empty-array))))]
    [php/bi-array-intersect
     (fn [args]
       (do (php/arity-between "array_intersect" args 2 1000000 "at least 2")
           (php/p9-diff (php/arg-array (get args 0) "array_intersect" 1 "array")
                        (php/p9-diff-args "array_intersect" args 1 (data/count args) [])
                        true 0 (php/empty-array))))]
    [php/p9-diff-key-has?
     (fn [k others j m all]
       (if (< j m)
         (let [found (php/array-has? (get others j) k)]
           (if all
             (if found (php/p9-diff-key-has? k others (+ j 1) m all) false)
             (if found true (php/p9-diff-key-has? k others (+ j 1) m all))))
         all))]
    [php/p9-diff-key
     (fn [a others intersect i out]
       (if (< i (php/array-count a))
         (let [k (get (php/array-order a) i)
               has (php/p9-diff-key-has? k others 0 (data/count others) intersect)]
           (php/p9-diff-key a others intersect (+ i 1)
                            (if (= has intersect) (php/array-put out k (php/array-get a k)) out)))
         out))]
    [php/bi-array-diff-key
     (fn [args]
       (do (php/arity-between "array_diff_key" args 2 1000000 "at least 2")
           (php/p9-diff-key (php/arg-array (get args 0) "array_diff_key" 1 "array")
                            (php/p9-diff-args "array_diff_key" args 1 (data/count args) [])
                            false 0 (php/empty-array))))]
    [php/bi-array-intersect-key
     (fn [args]
       (do (php/arity-between "array_intersect_key" args 2 1000000 "at least 2")
           (php/p9-diff-key (php/arg-array (get args 0) "array_intersect_key" 1 "array")
                            (php/p9-diff-args "array_intersect_key" args 1 (data/count args) [])
                            true 0 (php/empty-array))))]
    [php/bi-range
     (fn [args]
       (do (php/arity-between "range" args 2 3 "at least 2")
           (let [a (get args 0) b (get args 1) step (php/arg-or args 2 1)]
             (if (= (php/to-float step) (data/float-value 0))
               (php/throw-error "ValueError" "range(): Argument #3 ($step) must not be 0")
               (if (if (php/int? a) true (php/float? a)) (php/p9-range-num a b step) (php/p9-range-string (php/to-string a) (php/to-string b) step))))))]
    [php/p9-range-num (fn [a b step] (if (<= a b) (php/p9-range-up a b (php/abs step) (php/empty-array)) (php/p9-range-down a b (php/abs step) (php/empty-array))))]
    [php/p9-range-up (fn [a b step out] (if (<= a b) (php/p9-range-up (php/add a step) b step (php/array-append out a)) out))]
    [php/p9-range-down (fn [a b step out] (if (>= a b) (php/p9-range-down (php/sub a step) b step (php/array-append out a)) out))]
    [php/p9-range-string (fn [a b step] (let [ca (get (data/str->code-points a) 0) cb (get (data/str->code-points b) 0) nums (php/p9-range-num ca cb step)] (php/p9-range-chars nums 0 (php/empty-array))))]
    [php/p9-range-chars (fn [a i out] (if (< i (php/array-count a)) (php/p9-range-chars a (+ i 1) (php/array-append out (data/code-points->str (php/vconj [] (php/array-get a (get (php/array-order a) i)))))) out))]

    ;; JSON: the decoder state is [code-points index assoc? throw?].
    [php/json-fail
     ;; JSON_THROW_ON_ERROR throws JsonException; otherwise the failure
     ;; escapes the whole parse with null (continuing would operate on the
     ;; failure value, so the state alone cannot carry it)
     (fn [st message]
       (let [s (cell/get st)]
         (do (cell/set! st (assoc s 6 true))
             (if (get s 3) (php/throw-error "JsonException" message)
                 (let [esc (get s 7)]
                   (if (= esc :php/null) :php/null (esc :php/null)))))))]
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
    [php/json-escape-ctrl
     ;; any other control byte is \u00XX, never a "?" placeholder
     (fn [c]
       (let [qr (integer/quot-rem c 16)]
         (data/str-concat "\\u00" (data/str-concat (php/p9-hex-digit (get qr 0)) (php/p9-hex-digit (get qr 1))))))]
    [php/json-escape-loop
     (fn [cs i out]
       (if (< i (data/count cs))
         (let [c (get cs i) esc (php/json-escape-cp c)]
           (php/json-escape-loop cs (+ i 1)
                                 (data/str-concat out
                                                  (if (= esc :php/null)
                                                    (if (< c 32) (php/json-escape-ctrl c) (data/code-points->str (php/vconj [] c))) esc))))
         (data/str-concat "\"" (data/str-concat out "\""))))]
    [php/json-string (fn [s] (php/json-escape-loop (data/str->code-points s) 0 ""))]
    [php/json-key (fn [k] (php/json-string (php/to-string k)))]
    [php/json-indent
     (fn [n out] (if (> n 0) (php/json-indent (- n 1) (data/str-concat out "    ")) out))]
    [php/json-encode-fail
     ;; without JSON_THROW_ON_ERROR an encoding failure answers false with
     ;; no warning, like PHP; throw mode throws JsonException
     (fn [throw? message]
       (if throw? (php/throw-error "JsonException" message) false))]
    [php/json-array-values
     ;; a failed child (false) fails the whole encoding, like PHP
     (fn [a pretty depth maxdepth throw? i out]
       (if (< i (php/array-count a))
         (let [child (php/json-encode (php/array-get a (get (php/array-order a) i)) pretty (+ depth 1) maxdepth throw?)]
           (if (= (php/kind child) :string)
             (let [sep (if (= i 0) "" ",") br (if pretty (data/str-concat "\n" (php/json-indent (+ depth 1) "")) "")]
               (php/json-array-values a pretty depth maxdepth throw? (+ i 1)
                                      (data/str-concat out sep br child)))
             child))
         (data/str-concat out (if (if pretty (> i 0) false) (data/str-concat "\n" (php/json-indent depth "")) "") "]")))]
    [php/json-array-pairs
     (fn [a pretty depth maxdepth throw? i out]
       (if (< i (php/array-count a))
         (let [child (php/json-encode (php/array-get a (get (php/array-order a) i)) pretty (+ depth 1) maxdepth throw?)]
           (if (= (php/kind child) :string)
             (let [k (get (php/array-order a) i) sep (if (= i 0) "" ",")
                   br (if pretty (data/str-concat "\n" (php/json-indent (+ depth 1) "")) "")
                   colon (if pretty ": " ":")]
               (php/json-array-pairs a pretty depth maxdepth throw? (+ i 1)
                                     (data/str-concat out sep br (php/json-key k) colon child)))
             child))
         (data/str-concat out (if (if pretty (> i 0) false) (data/str-concat "\n" (php/json-indent depth "")) "") "}")))]
    [php/json-encode-value
     ;; a nested failure answers false (throw mode already threw); callers
     ;; must not concatenate the delimiter onto it, which the host refuses
     (fn [s open]
       (if (= (php/kind s) :string) (data/str-concat open s) s))]
    [php/json-encode
     (fn [v pretty depth maxdepth throw?]
       (let [k (php/kind v)]
         (if (= k :null) "null"
             (if (= k :bool) (if v "true" "false")
                 (if (= k :string) (php/json-string v)
                     (if (= k :int) (integer/format v)
                         (if (= k :float)
                           (let [h (php/h v)]
                             (if (if (php/nan? h) true (= (php/abs-h h) (php/inf)))
                               (php/json-encode-fail throw? "Inf and NaN cannot be JSON encoded")
                               (php/float-text v 17)))
                           (if (= k :array)
                             ;; the top-level container is depth level 1, so
                             ;; [1] at depth 1 is permitted and only a
                             ;; deeper container fails, like PHP
                             (if (> (+ depth 1) maxdepth)
                               (php/json-encode-fail throw? "Maximum stack depth exceeded")
                               (if (php/p9-is-list v 0)
                                 (php/json-encode-value (php/json-array-values v pretty depth maxdepth throw? 0 "") "[")
                                 (php/json-encode-value (php/json-array-pairs v pretty depth maxdepth throw? 0 "") "{")))
                             (php/json-encode-fail throw? "Type is not supported")))))))))]
    [php/bi-json-encode
     (fn [args]
       (do (php/arity-between "json_encode" args 1 3 "at least 1")
           (let [flags (php/to-int (php/arg-or args 1 0))
                 depth (php/to-int (php/arg-or args 2 512))]
             (if (< depth 1)
               (php/throw-error "ValueError" "json_encode(): Argument #3 ($depth) must be greater than 0")
               (if (not (= (integer/bit-and flags -4194433) 0))
                 (php/throw-error "Error" "json_encode(): Unsupported flag given, only JSON_PRETTY_PRINT and JSON_THROW_ON_ERROR are supported")
                 (php/json-encode (get args 0) (= (integer/bit-and flags 128) 128) 0 depth
                                  (= (integer/bit-and flags 4194304) 4194304)))))))]
    [php/json-literal
     (fn [st text value]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1) n (data/str-length text)]
         (if (if (<= (+ i n) (data/count cs)) (= text (data/code-points->str (data/subvec cs i (+ i n)))) false)
           (do (php/json-index! st (+ i n)) value)
           (php/json-fail st "Syntax error"))))]
    [php/json-read-string-loop
     ;; a raw control byte inside a string is a syntax error, never content
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (>= i (data/count cs)) (php/json-fail st "Unterminated string")
             (let [c (get cs i)]
               (if (< c 32) (php/json-fail st "Syntax error")
                   (do (php/json-index! st (+ i 1))
                       (if (= c 34) out
                           (if (= c 92) (php/json-read-escape st out)
                               (php/json-read-string-loop st (php/vconj out c))))))))))]
    [php/json-read-escape
     ;; only the grammar's escapes are accepted; anything else is a syntax
     ;; error, never a silent literal
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (>= i (data/count cs)) (php/json-fail st "Syntax error")
             (let [c (get cs i)]
               (do (php/json-index! st (+ i 1))
                   (if (= c 117) (php/json-read-unicode st out)
                       (if (if (= c 34) true (if (= c 92) true (if (= c 47) true (if (= c 98) true (if (= c 102) true (if (= c 110) true (if (= c 114) true (= c 116))))))))
                         (php/json-read-string-loop
                           st (php/vconj out (if (= c 110) 10 (if (= c 114) 13 (if (= c 116) 9 (if (= c 98) 8 (if (= c 102) 12 (if (= c 47) 47 c))))))))
                         (php/json-fail st "Syntax error"))))))))]
    [php/json-hex?
     (fn [c]
       (if (if (>= c 48) (<= c 57) false) true
           (if (if (>= c 65) (<= c 70) false) true
               (if (if (>= c 97) (<= c 102) false) true false))))]
    [php/json-hex4-ok?
     (fn [cs i n]
       (if (< n 4)
         (if (< (+ i n) (data/count cs))
           (if (php/json-hex? (get cs (+ i n))) (php/json-hex4-ok? cs i (+ n 1)) false)
           false)
         true))]
    [php/json-read-unicode
     (fn [st out]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (php/json-hex4-ok? cs i 0)
           (let [cp (php/json-hex4 cs i 0 0)]
             (do (php/json-index! st (+ i 4))
                 (php/json-read-string-loop st (data/into out (php/json-utf8 cp)))))
           (php/json-fail st "Syntax error"))))]
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
    [php/json-lead-bad?
     ;; the token forms the JSON grammar forbids: a leading plus (+1) and
     ;; a leading zero followed by another digit (01, -01)
     (fn [s]
       (let [cs (data/str->code-points s) n (data/count cs)]
         (if (= n 0) true
             (if (= (get cs 0) 43) true
                 (let [i (if (= (get cs 0) 45) 1 0)]
                   (if (< (+ i 1) n)
                     (if (= (get cs i) 48) (php/digit? (get cs (+ i 1))) false)
                     false))))))]
    [php/json-dot-bad-at
     ;; true when the point at cs[i] lacks a digit on either side; the
     ;; JSON grammar needs digits both before and after the point, so
     ;; .1 and 1. are syntax errors, never numbers
     (fn [cs i n]
       (if (>= i n) false
           (if (= (get cs i) 46)
             (let [p (if (= i 0) -1 (get cs (- i 1))) q (if (< (+ i 1) n) (get cs (+ i 1)) -1)]
               (if (if (php/digit? p) (php/digit? q) false) (php/json-dot-bad-at cs (+ i 1) n) true))
             (php/json-dot-bad-at cs (+ i 1) n))))]
    [php/json-dot-bad?
     (fn [s] (let [cs (data/str->code-points s)] (php/json-dot-bad-at cs 0 (data/count cs))))]
    [php/json-number
     ;; no number characters at all (a bare ] or end of input) is a syntax
     ;; error, never a zero; the token must be a whole number in the JSON
     ;; grammar (1e+ is truncated text, never a prefix value) with no
     ;; leading plus, no leading zero, and digits on both sides of any
     ;; point (.1 and 1. are syntax errors, like PHP)
     (fn [st]
       (let [cs (get (cell/get st) 0) start (get (cell/get st) 1) i (php/json-number-end cs start)]
         (if (= start i) (php/json-fail st "Syntax error")
             (do (php/json-index! st i)
                 (let [s (data/code-points->str (data/subvec cs start i)) r (php/numeric-string s)]
                   (if (if (php/full-num? r) (if (not (php/json-lead-bad? s)) (not (php/json-dot-bad? s)) false) false)
                     (if (= (get r 0) :int) (get r 1) (php/to-float s))
                     (php/json-fail st "Syntax error")))))))]
    [php/json-number-end
     ;; the scan admits the exponent sign too, so 1e+2 is one token;
     ;; validation rejects a leading plus and leading-zero forms
     (fn [cs i]
       (if (< i (data/count cs)) (let [c (get cs i)] (if (if (>= c 48) (<= c 57) false) (php/json-number-end cs (+ i 1)) (if (if (= c 45) true (if (= c 43) true (if (= c 46) true (if (= c 101) true (= c 69))))) (php/json-number-end cs (+ i 1)) i))) i))]
    [php/json-at
     ;; the code point at the cursor, or -1 past the end
     (fn [st]
       (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
         (if (< i (data/count cs)) (get cs i) -1)))]
    [php/json-enter
     ;; true on entry; on a limit breach the failure (throw or null)
     (fn [st]
       (let [s (cell/get st)]
         (if (>= (get s 5) (get s 4))
           (do (php/json-fail st "Maximum stack depth exceeded") false)
           (do (cell/set! st (assoc s 5 (+ (get s 5) 1))) true))))]
    [php/json-leave
     (fn [st]
       (let [s (cell/get st)]
         (do (cell/set! st (assoc s 5 (- (get s 5) 1))) :php/null)))]
    [php/json-array-elem
     ;; one array element after a comma: a value is required, so a trailing
     ;; comma is a syntax error rather than an empty slot
     (fn [st out]
       (do (php/json-space st)
           (let [v (php/json-value st) a (php/array-append out v)]
             (do (php/json-space st) (php/json-array-next st a)))))]
    [php/json-array-read
     (fn [st out]
       (do (php/json-space st)
           (let [i (get (cell/get st) 1) c (php/json-at st)]
             (if (= c 93) (do (php/json-index! st (+ i 1)) (php/json-leave st) out)
                 (php/json-array-elem st out)))))]
    [php/json-array-next
     (fn [st out]
       (let [i (get (cell/get st) 1) c (php/json-at st)]
         (do (php/json-index! st (+ i 1))
             (if (= c 44) (php/json-array-elem st out)
                 (if (= c 93) (do (php/json-leave st) out) (php/json-fail st "Syntax error"))))))]
    [php/json-object-elem
     ;; one member after a comma or brace: a quoted key, a colon, a value
     (fn [st out]
       (do (php/json-space st)
           (let [i (get (cell/get st) 1) c (php/json-at st)]
             (if (= c 34)
               (do (php/json-index! st (+ i 1))
                   (let [k (php/json-read-string st)]
                     (do (php/json-space st)
                         (let [j (get (cell/get st) 1)]
                           (if (= (php/json-at st) 58)
                             (do (php/json-index! st (+ j 1))
                                 (do (php/json-space st)
                                     (let [v (php/json-value st) a (php/array-put out k v)]
                                       (do (php/json-space st) (php/json-object-next st a)))))
                             (php/json-fail st "Syntax error"))))))
               (php/json-fail st "Syntax error")))))]
    [php/json-object-read
     (fn [st out]
       (do (php/json-space st)
           (let [i (get (cell/get st) 1) c (php/json-at st)]
             (if (= c 125) (do (php/json-index! st (+ i 1)) (php/json-leave st) out)
                 (php/json-object-elem st out)))))]
    [php/json-object-next
     (fn [st out]
       (let [i (get (cell/get st) 1) c (php/json-at st)]
         (do (php/json-index! st (+ i 1))
             (if (= c 44) (php/json-object-elem st out)
                 (if (= c 125) (do (php/json-leave st) out) (php/json-fail st "Syntax error"))))))]
    [php/json-value
     (fn [st]
       (do (php/json-space st)
           (let [cs (get (cell/get st) 0) i (get (cell/get st) 1)]
             (if (>= i (data/count cs)) (php/json-fail st "Syntax error")
                 (let [c (get cs i)]
                   (do (php/json-index! st (+ i 1))
                       (if (= c 34) (php/json-read-string st)
                           (if (= c 123) (if (php/json-enter st) (php/json-object-read st (php/empty-array)) :php/null)
                               (if (= c 91) (if (php/json-enter st) (php/json-array-read st (php/empty-array)) :php/null)
                                   (if (= c 116) (php/json-literal st "rue" true)
                                       (if (= c 102) (php/json-literal st "alse" false)
                                           (if (= c 110) (php/json-literal st "ull" :php/null)
                                               (do (php/json-index! st i) (php/json-number st))))))))))))))]
    [php/bi-json-decode
     (fn [args]
       (do (php/arity-between "json_decode" args 1 4 "at least 1")
           (let [flags (php/to-int (php/arg-or args 3 0)) assoc (php/to-bool (php/arg-or args 1 false))
                 depth (php/to-int (php/arg-or args 2 512))]
             (if (< depth 1) (php/throw-error "ValueError" "json_decode(): Argument #3 ($depth) must be greater than 0")
                 (if (not (= (integer/bit-and flags -4194306) 0))
                   (php/throw-error "Error" "json_decode(): Unsupported flag given, only JSON_OBJECT_AS_ARRAY and JSON_THROW_ON_ERROR are supported")
                   (php/call-ec
                     (fn [esc]
                       (let [st (cell/new
                                  (php/vconj
                                    (php/vconj
                                      (php/vconj
                                        (php/vconj
                                          (php/vconj
                                            (php/vconj
                                              (php/vconj
                                                (php/vconj [] (data/str->code-points (php/p9-string (get args 0))))
                                                0)
                                              assoc)
                                            (= (integer/bit-and flags 4194304) 4194304))
                                          depth)
                                        1)
                                      false)
                                    esc))
                             v (php/json-value st)]
                         (do (php/json-space st)
                             (if (= (get (cell/get st) 1) (data/count (get (cell/get st) 0)))
                               (if (get (cell/get st) 6) (esc :php/null) v)
                               (php/json-fail st "Syntax error")))))))))))]

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
