(ns yang.php.antlr.permutations
  "The permutation corpus of docs/design/yang.php.md section 4.6 source 3:
   from `$a = [1, 2]; $r = &$a[0];` every sequence of the alphabet below,
   printing `var_dump($a, $b, $c, $q, $r, $s)`; once over a flat array and once
   over the nested path `$a['x'][0]`. Data and generator only: the quick tier
   runs `quick-sample`, the slow tier `full`, and `src/dev/yang/php/corpus.clj`
   records the oracle's output for every program on shiva.")


(def alphabet
  "Letter to the statement it stands for, flat and nested."
  [[\C "$b = $a;" "$b = $a;"]
   [\U "unset($r);" "unset($r);"]
   [\W "$b[0] = 2;" "$b['x'][0] = 2;"]
   [\X "unset($b[0]);" "unset($b['x'][0]);"]
   [\A "$a[0] = 3;" "$a['x'][0] = 3;"]
   [\D "unset($b);" "unset($b);"]
   [\N "$c = $b; $c[1] = 9;" "$c = $b; $c['x'][1] = 9;"]
   [\B "$s = &$b[1]; $s = 9;" "$s = &$b['x'][1]; $s = 9;"]
   [\Z "$z = &$a; unset($z);" "$z = &$a; unset($z);"]
   [\T "$a = 0;" "$a = 0;"]
   [\L "$q = [&$r]; unset($r);" "$q = [&$r]; unset($r);"]
   [\G "f($a);" "f($a);"]
   [\E "$a = &$a;" "$a = &$a;"]])


(def letters (mapv first alphabet))


(def ^:private preamble
  {:flat "<?php function f($p) { $p[0] = 7; } $a = [1, 2]; $r = &$a[0];",
   :nested "<?php function f($p) { $p['x'][0] = 7; } $a = ['x' => [1, 2]]; $r = &$a['x'][0];"})


(defn program
  "The PHP source of sequence `letters-str` (a string over `letters`) in
   `variant` (`:flat` or `:nested`)."
  [variant letters-str]
  (let [idx (if (= variant :flat) 1 2)
        stmts (into {} (map (fn [row] [(first row) (nth row idx)])) alphabet)]
    (str (get preamble variant)
         " "
         (apply str (interpose " " (map stmts letters-str)))
         " var_dump($a, $b, $c, $q, $r, $s);")))


(defn sequences
  "Every string of length `n` over the alphabet, in lexicographic letter order."
  [n]
  (if (zero? n)
    [""]
    (for [prefix (sequences (dec n))
          l letters]
      (str prefix l))))


(defn- lcg
  "A deterministic stream of non-negative ints below `bound` from `seed`."
  [seed bound]
  (rest (map #(mod (quot % 65536) bound)
             (iterate #(mod (+ (* % 1103515245) 12345) 2147483648) seed))))


(defn sampled-sequences
  "`k` sequences of length `n` drawn with a fixed seed (the same list on every run)."
  [n k seed]
  (->> (lcg seed (count letters))
       (partition n)
       (map #(apply str (map letters %)))
       distinct
       (take k)
       vec))


(def full-ids
  "Every sequence of length 2 and 3, plus 300 of length 4 and 300 of length 5."
  (vec (concat (sequences 2) (sequences 3) (sampled-sequences 4 300 4) (sampled-sequences 5 300 5))))


(def quick-ids
  "A fixed sample for the quick tier: all of length 2 (169), 40 of length 3
   and 25 of lengths 4 and 5 each."
  (vec (concat (sequences 2) (sampled-sequences 3 40 3) (sampled-sequences 4 25 14)
               (sampled-sequences 5 25 15))))


(defn full
  "`[{:id [variant letters] :source s}]` for the full corpus, both variants."
  []
  (vec (for [v [:flat :nested]
             ids full-ids]
         {:id [v ids], :source (program v ids)})))


(defn quick
  []
  (vec (for [v [:flat :nested]
             ids quick-ids]
         {:id [v ids], :source (program v ids)})))
