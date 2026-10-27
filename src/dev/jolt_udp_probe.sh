#!/bin/sh
# J0b probe runner (foreground), macOS arm64 and Linux x86_64. POSIX sh
# (Ubuntu's /bin/sh is dash). Run from the repository root.
#
#   JOLT       jolt binary. Default: build/tools/jolt/bin/jolt (J0a layout),
#              else build/jolt-spike/jolt/bin/jolt (spike layout).
#   JOLT_CHEZ  threaded Chez 10.x. Default: build/tools/jolt-env.sh's value when
#              that file exists (J0a layout), else the mise 10.4.1 install path
#              when present; otherwise left unset and jolt searches PATH.
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

jolt_home=$(cd "$(dirname "$JOLT")/.." 2>/dev/null && pwd)
echo "jolt: $JOLT"
echo "jolt-sha: $(git -C "$jolt_home" rev-parse HEAD 2>/dev/null) dirty=$(git -C "$jolt_home" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
if [ -n "$JOLT_CHEZ" ]; then
  echo "chez: $JOLT_CHEZ $("$JOLT_CHEZ" --version 2>&1)"
else
  echo "chez: JOLT_CHEZ unset (jolt searches PATH)"
fi

case "$(uname -s)" in
  Darwin)
    echo "uname: $(uname -sm) macOS $(sw_vers -productVersion 2>/dev/null)"
    echo "sysctl: $(sysctl -n net.inet.udp.maxdgram net.inet.udp.recvspace kern.ipc.maxsockbuf 2>/dev/null | tr '\n' ' ')(maxdgram recvspace maxsockbuf)"
    echo "lo0 mtu: $(ifconfig lo0 2>/dev/null | sed -n 's/.*mtu \([0-9]*\).*/\1/p' | head -1)"
    ;;
  Linux)
    os=$(sed -n 's/^PRETTY_NAME="\(.*\)"$/\1/p' /etc/os-release 2>/dev/null)
    echo "uname: $(uname -sm) $os"
    sys=""
    for k in core/wmem_default core/wmem_max core/rmem_default core/rmem_max ipv4/udp_wmem_min ipv4/udp_rmem_min ipv4/udp_mem; do
      sys="$sys net.$(echo $k | tr / .)=$(tr '\t' ' ' < /proc/sys/net/$k 2>/dev/null)"
    done
    echo "sysctl:$sys"
    echo "lo mtu: $(cat /sys/class/net/lo/mtu 2>/dev/null)"
    ;;
  *)
    echo "uname: $(uname -sm) (unsupported platform for this probe)"
    ;;
esac

"$JOLT" "$dir/jolt_udp_probe.clj"
rc=$?
echo "exit: $rc"
exit $rc
