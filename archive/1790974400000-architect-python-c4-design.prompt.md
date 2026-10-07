Created-GMT: 2026-10-02 20:10:00 GMT
Created-Local: 2026-10-03 03:10:00 +07 (+0700)
Coding-Agent: claude
Session-ID: d5143405-c85b-411f-b07a-41492ba519c9

# Task: Design Python phase C4 — imports, the linked prelude, and the REPL frontend catalog/SPI

Role: Lead System Architect

Perform a read-only architecture design for Python phase C4. Author the full design in your response; findings
only — do not edit any file.

Read first (main tree /Users/sto/workspace/datomworld, master 69e58662; read-only — another seat's uncommitted
linker work is in datomworld-linker-hardening / -transfer, judge committed state):
- docs/design/datom.world.md (invariants)
- docs/design/yang.antlr.md — §3 (the frontend SPI), §8.5.1-8.5.4 (the landed rulings), §12 (roadmap; C4's scope:
  imports, linked prelude, REPL frontend catalog/SPI)
- docs/design/yin.vm.linker.md and docs/design/yin.vm.linker.dht.md (the LANDED linker: module stores, install,
  authority, publish by signed name, the DHT composition)
- docs/design/yin.vm.universal-continuation-format.md (module stores, install phases)
- The safepoint design's load-order constraints (yang.antlr.md 8.5.2; the hook prelude loads before the bundled
  base prelude and may only allocate cells/cursors — "available when the prelude becomes a linked module" notes)
- The D7 linker origin/store context notes (slice B/C deferrals in the orchestrator log, 2026-10-01 entries)
- src/cljc/yin/vm/linker/*.cljc, src/cljc/yin/repl/link.cljc (the interpreter box), src/cljc/yang/python/antlr/
  lower.cljc (the prelude bundling at lower.cljc:1094-1097 and the builtin-classes table), the landed
  yin.vm.integer module

Questions the design must answer:
1. Python imports: how `import`/`from ... import` map onto the landed linker (module stores, manifests, publish by
   signed name, the DHT index). Resolution order (sys.modules, the module cache), `__name__`/`__package__`,
   relative imports, `importlib` surface or its refusal, side effects at import time (module body execution under
   the install phases), circular imports, `if __name__ == '__main__'` under a linked module.
2. sys.modules and the module namespace: the mappability ruling made module namespaces heap dicts — how a linked
   module's store backs that dict, and what setattr/del on it means for the linker's store model.
3. The linked prelude: migrate the bundled prelude (and the hook prelude) to linked modules — what breaks (the
   atomicity argument at 8.11 that relies on the prelude being bundled; the safepoint load-order constraint; every
   program's addresses change), what gets simpler (the builtin-classes table, the C3 module, the data module as
   linked modules with authority), the migration sequencing, and whether the naive/bundled path stays selectable.
4. The REPL frontend catalog/SPI (yang.antlr.md §3/§12): the versioned frontend manifest + catalog replacing the
   REPL's closed language dispatcher; what Python's profile pins; how a second language (JavaScript, parked by the
   owner) would install; the parser-worker boundary (§4) as it affects the REPL.
5. Slice sequencing for the engineers, each with acceptance test contracts (setup/action/assertion per host), and
   which slices need owner/mob decisions versus which follow from the landed rulings.
6. Interactions: the float64-carrier slice (in flight), the safepoint slices (tracing/threads pending), C2-S3/S4/S5
   (generators), C3-S3+ — what lands before imports can, and what the linker origin/store deferrals (slice B/C)
   must contribute.

Distinguish architectural defects from implementation gaps or deferred work. Do not edit files.

End with an "Owner decisions" section enumerating every open decision with your recommendation (the owner's mob
authority applies: cross-ruling by gpt-6-astra will follow).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
