#!/usr/bin/env bash
# rverify.sh <host> <scratch-clone> <git-ref> <name> <files-list|-> <cmd>...
# Applies uncommitted files (paths relative to the repo root, one per line in <files-list>) over <git-ref> in the
# scratch clone ~/ci/<scratch-clone> on <host>, then runs each <cmd> under the mise env in tmux session <name>.
# Results: ~/ci/<name>-logs/{rc,cmdN.log,head,DONE}. The clone is locked while the job runs (one job per clone);
# a previous overlay is removed before the checkout; setup failures are recorded in rc and abort the job.
# Poll: rverify.sh poll <host> <name>     Dedicated clone per concurrent job (README).
set -eu
die() { echo "rverify.sh: $*" >&2; exit 2; }
if [ "${1:-}" = poll ]; then
  [ $# -eq 3 ] || die "usage: rverify.sh poll <host> <name>"
  case $2 in *[!A-Za-z0-9._@-]*|"") die "bad host";; esac
  case $3 in *[!A-Za-z0-9._-]*|"") die "bad name";; esac
  ssh -o ConnectTimeout=15 "$2" "L=~/ci/$3-logs; if [ -f \$L/DONE ]; then echo STATE=DONE; elif tmux has-session -t =$3 2>/dev/null; then echo STATE=RUNNING; else echo STATE=NOT-STARTED-OR-DIED; fi; cat \$L/rc 2>/dev/null"; exit; fi
[ $# -ge 6 ] || die "usage: rverify.sh <host> <scratch-clone> <git-ref> <name> <files-list|-> <cmd>..."
host=$1 clone=$2 ref=$3 name=$4 files=$5; shift 5
case $name in *[!A-Za-z0-9._-]*|"") die "bad name '$name'";; esac
for v in "$clone" "$ref" "$name"; do case $v in *[!A-Za-z0-9._/-]*|*..*|/*|"") die "bad argument '$v'";; esac; done
case $host in *[!A-Za-z0-9._@-]*|"") die "bad host";; esac
for c in "$@"; do [ -n "$c" ] || die "empty command"; done
export COPYFILE_DISABLE=1
if [ "$files" != - ]; then
  while IFS= read -r f; do case $f in /*|*..*) die "files-list path must be relative: $f";; esac; done < "$files"
  tar czf "/tmp/$name.tgz" -T "$files"; scp -q "/tmp/$name.tgz" "$host:ci/$name.tgz"; cp "$files" "/tmp/$name.files"; scp -q "/tmp/$name.files" "$host:ci/$name.files"
fi
ssh -o ConnectTimeout=15 "$host" "tmux has-session -t =$name 2>/dev/null" && die "tmux session $name already exists (finish or kill it yourself)"
b='export PATH=$HOME/.local/bin:/snap/bin:$PATH; L=~/ci/'$name'-logs; rm -rf $L; mkdir -p $L
fail() { echo "setup FAILED: $1" > $L/rc; echo DONE > $L/DONE; exit 1; }
LOCK=~/ci/'$clone'.lock; mkdir $LOCK 2>/dev/null || fail "clone '$clone' is busy (lock held)"; trap "rmdir $LOCK" EXIT HUP INT TERM
cd ~/ci/'$clone' || fail "no scratch clone"
if [ -f ~/ci/'$clone'.overlay ]; then while IFS= read -r f; do rm -f -- "$f"; done < ~/ci/'$clone'.overlay; rm -f ~/ci/'$clone'.overlay; fi
mise trust -q . || fail "mise trust"; mise install -q >/dev/null 2>&1 || fail "mise install"
git fetch -q origin '$ref' || fail "fetch"; git checkout -q -f --detach FETCH_HEAD || fail "checkout"
[ "$(git rev-parse HEAD)" = "$(git rev-parse FETCH_HEAD)" ] || fail "HEAD != FETCH_HEAD"'
[ "$files" != - ] && b="$b
cp ~/ci/$name.files ~/ci/$clone.overlay; tar xzf ~/ci/$name.tgz || fail \"overlay\""
b="$b
eval \"\$(mise env -s bash)\"; git rev-parse HEAD > \$L/head"
i=0; for c in "$@"; do i=$((i+1)); lab=$(printf '%s' "$c" | tr -c '[:alnum:] ._/:=-' '?' | cut -c1-80)
  b="$b
t0=\$(date +%s); ( $c ) > \$L/cmd$i.log 2>&1; echo \"cmd$i rc=\$? \$((\$(date +%s)-t0))s :: $lab\" >> \$L/rc"; done
b="$b
echo DONE > \$L/DONE"
printf '%s\n' "$b" > "/tmp/$name.sh"; scp -q "/tmp/$name.sh" "$host:ci/$name.sh"
ssh -o ConnectTimeout=15 "$host" "tmux new-session -d -s $name 'bash ~/ci/$name.sh'; tmux ls | cut -d: -f1 | tr '\n' ' '"
