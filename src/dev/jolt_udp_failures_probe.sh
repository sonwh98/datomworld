#!/bin/sh
# J7 UDP failure-injection probe runner, macOS arm64 and Linux x86_64. POSIX sh.
dir=$(cd "$(dirname "$0")" && pwd)

if [ -z "$JOLT_CHEZ" ] && [ -f build/tools/jolt-env.sh ]; then
  . build/tools/jolt-env.sh
fi
if [ -z "$JOLT" ]; then
  if [ -x build/tools/jolt/bin/jolt ]; then
    JOLT=build/tools/jolt/bin/jolt
  else
    JOLT=build/jolt-spike/jolt/bin/jolt
  fi
fi
if [ -z "$JOLT_CHEZ" ]; then
  mise_chez="$HOME/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme"
  [ -x "$mise_chez" ] && JOLT_CHEZ=$mise_chez
fi
[ -n "$JOLT_CHEZ" ] && export JOLT_CHEZ

"$JOLT" -Srepro -M:jolt "$dir/jolt_udp_failures_probe.clj"
rc=$?
echo "exit: $rc"
exit $rc
