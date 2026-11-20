#!/usr/bin/env bash
# cc4 budget jupiter REDO: the S configurations with the fixed corpus
# (annotated? gate); the B runs and setup are unchanged and stand.
set -u
OUT="$HOME/ci/cc4-budget"
for t in pre post; do
  cp "$HOME/ci/cc4-in/cc4_corpus.clj" "$OUT/$t/test/yang/cc4_corpus.clj"
  cp "$HOME/ci/cc4-in/cc4_budget_runner.clj" "$OUT/$t/test/yang/cc4_budget_runner.clj"
done
run_one () {
  local tree=$1 cfg=$2 ex=$3 tag=$4
  ( cd "$OUT/$tree" && java -Xss64m -cp "$(cat "$OUT/$tree-cp.txt")" \
      clojure.main -m yang.cc4-budget-runner "$cfg" "$ex" \
      > "$OUT/$tag.log" 2>&1 ) || { echo "RUN-FAIL $tag"; return 1; }
  echo "DONE $tag"
}
run_one post S EX=lower-unit post7-S || exit 6
run_one pre  S EX=lower-unit pre7-S  || exit 4
run_one post S EX=none post8-S || exit 1
echo ALL-DONE
