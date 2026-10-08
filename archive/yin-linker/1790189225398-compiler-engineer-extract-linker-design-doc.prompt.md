Created-GMT: 2026-09-23 18:47:05 GMT
Created-Local: 2026-09-24 01:47:05 +0700
Coding-Agent: claude
Session-ID: 53d6f7ea-2cd0-4dba-b2d7-a28e13add857

# Task: Extract B6 into docs/design/yin.vm.debruijn.linker.md

Role: Yang Compiler Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 01:47:05 +0700 | Status: active | Rationale: Extract and expand B6 linker design into its own document

## Context

The B6 linker specification currently lives as one section (### B6) inside
`docs/design/yin.vm.debruijn.stack.md`. The owner has directed that it be
extracted into its own standalone design document.

Additionally, the R5 section in `docs/design/yin.vm.debruijn.register.md`
specifies how R5 plugs into the same shared linker function. Since the two
are designed as one unit ("R5 is built together with B6"), the new document
should cover both B6 and R5's linker participation.

## What to Read First

1. `docs/design/datom.world.md` -- governing invariants
2. `docs/design/yin.vm.debruijn.stack.md` -- read the full document;
   B6 is at section "### B6: committed closed-image linker over dao.jing"
   (approximately line 678). Also read the topology section (7.2) and any
   other cross-references to B6 throughout the document.
3. `docs/design/yin.vm.debruijn.register.md` -- read the full document;
   R5 is at section "### R5: linker integration over dao.jing"
   (approximately line 1011). Note the explicit coupling to B6.
4. `docs/design/dao.stream.md` -- for stream substrate context
5. Look at the existing `dao.jing` source to understand what is already
   implemented:
   - `src/cljc/dao/jing.cljc`
   - `src/cljc/dao/jing/dht.cljc` (if it exists)
   - `src/cljc/dao/jing/remote.cljc` (if it exists)

## What to Produce

Create `docs/design/yin.vm.debruijn.linker.md` -- a standalone design
document for the B6 + R5 linker.

## Document Requirements

### Format

- Pure Markdown (`.md`)
- Lines <= 80 columns
- Pure ASCII only (no Unicode, no curly quotes, no em-dashes)
- Section headers use `##` and `###`
- Code blocks use triple-backtick fences with a language tag
- ASCII box-drawing tables (spaces/hyphens/pipes)
- No HTML

### Content Requirements

The document must be a faithful extraction and expansion of the B6 and R5
linker material. It must:

1. **Stand alone** -- a reader who has not read the stack or register design
   docs should understand what the linker does, why it exists, and how it
   works from this document alone. Add necessary context from the governing
   documents, but do not duplicate large sections verbatim -- summarize and
   cross-reference.

2. **Cover these topics (minimum):**

   a. **Purpose and context** -- why a linker is needed; closed-image fetch
      and verification over dao.jing; the key design decision that the
      linker is one function parameterised by a format record, not two
      separate linkers for H and R.

   b. **Relation to dao.jing** -- what dao.jing already provides (DHT,
      fetch-by-content-address, verify-before-trust, DaoStream transport);
      what B6 adds (format record, fetch function, H index, qualified
      refusal vocabulary); what B6 explicitly does not add (no new
      transport, no peer lookup, no cache, no responder).

   c. **The address/H distinction** -- why H is not the dao.jing address,
      what segment-key is, and why the two checks (step 2 and step 3 of
      the fetch function) are different and both necessary.

   d. **The fetch function** -- the six-step fetch protocol (index lookup,
      address verification, H verification, validation, closure check,
      return), the format record shape, and the qualified refusal outcomes
      at each step.

   e. **Format records** -- the two concrete format records:
      - Stack (H): `{:format :yin.debruijn.code :hash-fn image-hash
        :validate-fn image-defect :free-names-fn ...}`
      - Register (R): `{:format :yin.debruijn.register
        :hash-fn register-hash :validate-fn ... :free-names-fn ...}`
      Make clear that R5 contributes the register record; the fetch
      function itself is B6's.

   f. **The H index and R index** -- what they are, who owns them, how
      they are recorded (as datoms), and the B7 deferral for a full name
      environment with ledger and trust.

   g. **Same-root pairing (H and R)** -- the R5 rule for H-to-R pairing
      datoms, the trust model, the verifying fallback path
      (`:pairing-mismatch`), and why a bare H-to-R entry is not used.

   h. **Dependency risk: transitional content-hash** -- why B6 tests pin
      H values only and never a Jing address as a golden; what happens
      when the CBOR encoding lands.

   i. **File box** -- new files for B6 and R5:
      - B6: `src/cljc/yin/vm/debruijn_linker.cljc`,
             `test/yin/vm/debruijn_linker_test.cljc`
      - R5: `src/cljc/yin/vm/debruijn_register_linker.cljc`,
             `test/yin/vm/debruijn_register_linker_test.cljc`
      Must-not-change list for each.

   j. **Completion criteria** -- the full completion list for B6 (from the
      stack design doc) and for R5 (from the register design doc), merged
      into a coherent single checklist covering both formats.

   k. **Non-goals** -- what the linker explicitly does not do (no new
      transport, no JIT, no `:call-hash` emission, no B7 name-environment).

3. **Update the source documents** after writing the new doc:
   - In `docs/design/yin.vm.debruijn.stack.md`, replace the full `### B6`
     section body with a short paragraph that says B6 is now specified in
     `docs/design/yin.vm.debruijn.linker.md` and cross-references it.
     Preserve the section header and the file-box/depends-on/must-not-change
     table so the phase list remains complete in the stack doc.
   - In `docs/design/yin.vm.debruijn.register.md`, do the same for the
     `### R5` section body -- replace it with a short cross-reference
     paragraph, keeping the header and file-box table.

## Non-Negotiable Invariants

- Lines <= 80 columns throughout
- Pure ASCII only
- No logic changes -- documentation only; no `.cljc` files touched
- The source documents (stack.md and register.md) must not lose their
  phase-list structure; only the body prose is replaced with a
  cross-reference

## Deliverable

1. `docs/design/yin.vm.debruijn.linker.md` -- new standalone linker doc
2. Updated `docs/design/yin.vm.debruijn.stack.md` -- B6 body replaced
   with cross-reference
3. Updated `docs/design/yin.vm.debruijn.register.md` -- R5 body replaced
   with cross-reference

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700 ICT>
Session-ID: 53d6f7ea-2cd0-4dba-b2d7-a28e13add857

Then confirm the three files written, the line count of the new doc, and
that no .cljc files were touched.
