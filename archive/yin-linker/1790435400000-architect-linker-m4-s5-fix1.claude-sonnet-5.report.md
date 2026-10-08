# Report: M4 S5 fix1 (UCF amendments)

Edited only `docs/design/yin.vm.universal-continuation-format.md` in
`/Users/sto/workspace/datomworld-m4-s5`. Nothing committed.

1. 7.5.4 closed kind set now also lists `:forged-resource-reference`,
   `:missing-module-store`, `:unrooted-body`, with a linker M4
   cross-reference (`yin.vm.linker.md` 7.3).
2. All seven (linker M4) blocks carry "Specified by yin.vm.linker.md;
   lands with M4 slices S3 to S4". The first block (Linker safepoints)
   holds the full statement: private resources table, sealed references
   and install child land with S3 to S4 (S3: r8-r11, install child,
   link-module; S4: manifests). It says nothing is implemented yet and
   that UCF lift/lower of parked link and install entries stays future.
3. Closure-marker paragraph: `:binding-mismatch` is a lower-side refusal
   (linker 7.3), not a `:yin.k/non-portable` kind, so it is not added to
   7.5.4.
4. Linker pending variants block: a wait entry's `:cursor` (the kept
   cursor) is UCF's `:yin.k/cell`; the cell id stands for the cursor and
   its kept position rides in the 7.5.3 cells table.

Notes: line numbers in the prompt (linker.md ~588, ~994-1026, ~1333) did
not match the current file exactly; I located the referenced content by
search. The new text is ASCII; the one edited kind-set sentence keeps
the doc's existing section sign. No line-length or test check was run
(docs only).
