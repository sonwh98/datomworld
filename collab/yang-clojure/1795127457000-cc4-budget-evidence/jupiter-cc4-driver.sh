#!/usr/bin/env bash
# cc4 budget jupiter driver (system java 21.0.13.1 / clojure 1.12.0.1495)
set -u
cd "$HOME/ci/datomworld-fixorder" || exit 9
OUT="$HOME/ci/cc4-budget"
rm -rf "$OUT"; mkdir -p "$OUT/pre" "$OUT/post"
git archive 8afd82f5e99d0f2b2d38a5f53b292105fdead688 | tar -x -C "$OUT/pre" || exit 8
git archive 2175252d | tar -x -C "$OUT/post" || exit 8
for t in pre post; do
  mkdir -p "$OUT/$t/test/yang"
  cp "$HOME/ci/cc4-in/cc4_corpus.clj" "$OUT/$t/test/yang/cc4_corpus.clj"
  cp "$HOME/ci/cc4-in/cc4_budget_runner.clj" "$OUT/$t/test/yang/cc4_budget_runner.clj"
done
cd "$OUT"
for t in pre post; do
  ( cd "$OUT/$t" && clojure -M:antlr-gen > "$OUT/$t-antlr.log" 2>&1       && clojure -Spath -A:test > "$OUT/$t-cp.txt" 2>/dev/null ) \
    || { echo "SETUP-FAIL $t"; exit 7; }
done
run_one () {
  local tree=$1 cfg=$2 ex=$3 tag=$4
  local dir="$OUT/$tree"
  ( cd "$dir" && java -Xss64m -cp "$(cat "$OUT/$tree-cp.txt")" \
      clojure.main -m yang.cc4-budget-runner "$cfg" "$ex" \
      > "$OUT/$tag.log" 2>&1 ) || { echo "RUN-FAIL $tag"; return 1; }
  echo "DONE $tag"
}
run_one post B EX=lower-unit post7-B || exit 6
run_one post S EX=lower-unit post7-S || exit 5
run_one pre  B EX=lower-unit pre7-B  || exit 4
run_one pre  S EX=lower-unit pre7-S  || exit 3
run_one post B EX=none post8-B || exit 2
run_one post S EX=none post8-S || exit 1
echo ALL-DONE
