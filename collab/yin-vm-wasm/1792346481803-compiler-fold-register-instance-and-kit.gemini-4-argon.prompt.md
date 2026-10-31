Created-GMT: 2026-10-18 18:01:21 GMT
Created-Local: 2026-10-19 01:01:21 +07

# Task: fold register-wasm into register.md; dissolve conformance-kit.md along its seam

Owner rulings (2026-10-19 01:0x +07):
1. `yin.vm.register-wasm.md` must not exist — the name implies a VM namespace in the
   `yin.vm.*` family, and no such VM exists. Its content folds into
   `yin.vm.debruijn.register.md` as a dated section ("Option 1", owner-approved).
2. `yin.vm.conformance-kit.md` has the same defect, and is NOT purely
   register-specific — owner challenge confirmed. It dissolves along its seam:
   - the METHOD (what conformance means on this stack: oracle export from the
     reference implementation, differential lanes, trace comparison, B0-normalized
     comparison, cross-embedder determinism gates, snapshot-isolation gates) moves
     to the `yin.vm.engine.md` extension, beside its existing guest-agnostic
     acceptance properties — it becomes that extension's acceptance section;
   - the register FIXTURES and register lane gates (canonical images, R, live sets,
     opcode coverage, validator-rule refusals, register W-slice acceptance rows)
     move to the new register.md wasm-instance section.

Normative content FROZEN; move and generalize at seams only; tensions to Open
items. No src/ or test/ changes.

## Deliverables

### 1. AMEND docs/design/yin.vm.debruijn.register.md — new dated section(s)

Append "Wasm execution instance (2026-10-19, design only)" containing, from
yin.vm.register-wasm.md: the register field set (the eight materialized fields as
the register INSTANCE of the engine extension's machine-state-as-data pattern —
cross-linked, pattern not duplicated), the sparse continuation payload/live-set
representation on wasm, and the register fixtures + register lane gates from
conformance-kit.md. Also fold in the kit's register fixture schema (canonical
image, R, B0 result, boundary traces, validator refusals, descriptor hash, scalar
vectors, atomicity and tail fixtures). Follow register.md's own amendment
conventions (dated, precedence-stated like the existing A-identity amendment);
place after existing content; no existing line may move.

### 2. AMEND docs/design/yin.vm.engine.md — extension gains the method

Move conformance-kit.md's METHOD content into the extension as its acceptance
section: oracle-export discipline, differential lane procedure (CLJS reference,
Rust interpreter, wasm embedders, LLVM sibling), trace-not-just-values comparison,
B0-normalized comparison, determinism gates, snapshot isolation. Reconcile with the
existing guest-agnostic acceptance properties (they were written as pointers to the
kit; they now own the method — merge, do not duplicate).

### 3. DELETE docs/design/yin.vm.register-wasm.md and docs/design/yin.vm.conformance-kit.md

All their normative content must be accounted for in register.md and engine.md.
Double-check nothing is dropped: list every section of both dying files in your
reply with its new home.

### 4. REPAIR all cross-references

yang.wasm.md (host references now point at engine.md extension; register-lane gate
references at register.md), rust-kernel.md (K0 references the register.md kit
section + engine.md method; line coordinates 1–1334 must not shift; footer note
updated), engine.md and register.md internal links. Relative links must resolve.

### 5. Provenance

Dated notes on each touched file: owner-directed fold, 2026-10-19; the five-round
consensus lineage preserved in the notes.

When done, reply with: files written/deleted, the section→home table for both dead
files, and every cross-reference changed.
