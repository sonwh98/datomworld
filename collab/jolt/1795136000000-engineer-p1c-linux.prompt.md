Created-GMT: 2026-11-20 02:01:18 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1c-linux — fix the Linux heap overflow in the compiled Yin boot path

Role: VM Runtime Engineer (Linux leg continuation)

## Diagnosis (orchestrator, live on shiva at e28f40b6)

The yin.repl slice works on Linux up to the compiled-boot load, then:

- `make gambityinrepl` on Linux: rc=2, silently exits right after
  "boot: backend target -> :gambit" (transcript empty of replies).
- Manual run with the built boot (build/gambit-yin/boot-*/kernel.o1, 31.5 MB,
  compiled by Linux gsc — the build itself succeeds): rc=70 with
  `*** ERROR IN ##cte-lookup -- Heap overflow`.
- With a larger heap request the failure signature changes (exit 2, help text
  collision — flag syntax must be `gsi -:h4000 ...` not `-h 4000`).
- Same compiled-boot path PASSES on macOS arm64 (gambityinrepl PASS).
- Related precedent: the MT experiment recorded Linux default-parallelism heap
  overflows at startup (build/p1c-r3 notes; MT findings).

## Task

Fix the Linux heap sizing for the compiled-boot load path. Likely: the
generated boot needs explicit minimum-heap runtime options (Gambit `:-h`
with an adequate floor, or a tail-call/cte-lookup-path fix if the overflow is
a genuine unbounded growth — DIAGNOSE which: instrument the load to find
where memory explodes vs a too-small floor). Choose per evidence:
- If a fixed floor suffices: bake the runtime options into the launcher
  (yin-repl-native.ss re-exec, or build-yin-boot.py emitting a wrapper) with
  the floor sized from the measured requirement, documented.
- If genuinely unbounded: that is a Linux-specific blocker — record it as a
  P1 finding with the instrumentation evidence and STOP (the macOS gate
  evidence stands; the Linux leg is a qualification gap, not a hidden bug to
  paper over).
Verify on macOS the darwin path is unchanged. Commit on gambit-ffi-p1c; never
push. The orchestrator re-runs the Linux matrix with your fix and reports the
verdict.

Return: Completed-* header + diagnosis (bounded floor vs unbounded growth,
with the instrumented evidence) + what changed + macOS regression line.
