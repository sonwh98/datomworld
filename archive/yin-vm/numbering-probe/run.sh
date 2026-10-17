#!/bin/sh
# Run the discovery-order numbering prototype (base design, round 4) from the
# probe directory on the launcher's cached classpath (java.args holds it with
# absolute paths; regenerate it from .cpcache/*.cp if deps change). Foreground
# only. `mise exec --` puts the project's JDK on the path.
cd "$(dirname "$0")" || exit 1
mise exec -- java -Xmx2g @java.args clojure.main -e "(require 'probe.discovery) (probe.discovery/-main)"
