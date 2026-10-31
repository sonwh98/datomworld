#!/usr/bin/env python3 -I
"""Run one foreground clojure test command in the worktree, log it, print the tail.

usage: run.py <log-name> <heap e.g. 256m|none> <test args...>
"""
import subprocess, sys, time, os, re

WT = "/Users/sto/workspace/datomworld-srvm-p6a"
log_name, heap, *args = sys.argv[1:]
cmd = ["clojure"]
if heap != "none":
    cmd.append("-J-Xmx" + heap)
cmd += ["-M:test"] + args
t0 = time.time()
with open(os.path.join(WT, "collab", log_name), "w") as log:
    log.write("$ " + " ".join(cmd) + "\n")
    log.flush()
    p = subprocess.run(cmd, cwd=WT, stdout=log, stderr=subprocess.STDOUT, timeout=1500)
dt = time.time() - t0
text = open(os.path.join(WT, "collab", log_name)).read()
print("$", " ".join(cmd))
print("exit=%d  %.1fs" % (p.returncode, dt))
for line in text.splitlines():
    if re.search(r"R3PROBE|^Ran |tests?, .*assertions|FAIL|ERROR|Exception|OutOfMemory|expected:|actual:", line):
        print(line[:400])
