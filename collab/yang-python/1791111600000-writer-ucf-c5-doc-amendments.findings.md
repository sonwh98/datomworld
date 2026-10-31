Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# Writer: UCF and DHT doc amendments forced by C5

Summary: six of the seven amendments are written or already in place.
Amendment 7 (the C6 refusal note) has no obvious spot in 14.2.x, so I
did not write it. Docs only, ASCII only, no git writes. Files edited:
UCF, ucf-revisions, and dao.stream.journal.md (one sentence, rewrapped).
yin.vm.linker.dht.md is unchanged.

## Amendments

1. **UCF 7.2.1, the occurrence form.** At
   `docs/design/yin.vm.universal-continuation-format.md:298-303`, I
   appended to the occurrence bullet without rewording the existing
   text. It says: a lowercase-hex UUID string with one spelling, so
   canonical-byte equality is string equality. Any other form is
   `:malformed-occurrence` in the root, its origin, and every carried
   op id. A reuse with equal occurrence, origin and baseline is
   admitted as a snapshot variant.
   Checked against the code: `custody.cljc:42-55` (`mint-occurrence`,
   `occurrence?`) and `checkpoint.cljc:165,197,261`. Ruling (a) has
   already landed in the inspector, so the sentence about where the
   form is refused is true of the code.

2. **UCF 7.7.2, the grantor's facts.** At UCF:1604-1618, after the
   paragraph that ends "only grantor-authored facts establish terms".
   - A second table lists the admitted offer, the grant and the grant
     binding, each authored by the authority. Each row gives the keys
     in `ledger.cljc:62-74` `attribute-order`, in that order.
   - One paragraph follows. The admitted offer has the same dispatch
     value as the emitter's offer. It differs in author and in
     `:yin.k/baseline`, and it records one admitted variant. The
     emitter's offer stays evidence. A grant and its binding are one
     transaction.
   - I used a separate table instead of adding rows to the existing
     one. The existing paragraph says "Both `:yin.k/custody` facts are
     evidence", and extra rows would make that wrong without a reword.
   - The table rows are longer than 80 columns. The existing 7.7.2
     table rows are too, and Markdown table rows cannot wrap.

3. **UCF 7.7.8, "the only binding for L" and the author rule.** At
   UCF:1995-2005, as a new bullet right after the binding-validity
   bullet. The existing bullet is unchanged.
   - It uses ruling Q5's wording. "Oldest anchor" is written as
     `:dao.stream/oldest`, the anchor named in `dao.stream.md` around
     line 503.
   - It uses ruling Q6's identity-equality sentence, plus "descriptors
     are never compared".
   - Checked against the code: `custody.cljc` `binding-evidence`
     filters on `(= arbitration author)`, and `grant.cljc`
     `offer-decision` compares only
     `[:yin.k/arbitration :dao.stream/identity]`.

4. **UCF 7.7.8, the offer order.** At UCF:2115-2120, appended to the
   *Snapshot variants* paragraph (the offer paragraph). It gives the
   order: inspect; decide purely under the lock; store only what the
   decision will commit; commit. Refused, replayed and uninspectable
   offers store nothing. A failed store answers `:suspended` and
   commits nothing. An orphan body after a failed commit is harmless.
   - Checked against the code: `grant.cljc` `offer!` refuses
     `:uninspectable` before taking the lock. Under `authority/locked`
     it stores only when `(seq facts)`, and otherwise answers
     `{:yin.k/status :suspended :yin.k/reason :content-unavailable}`.
   - Not done: the plan sentence "inspect, store, then commit" lives
     only in collab `1791097400000-...-plan-r2` (slice C5). I was told
     not to touch collab/, so that file still has the old order.
     Published docs never had that sentence; UCF 7.7.8 now carries
     the executed order.

5. **Memory durability declaration.** This was already in place before
   my edit, at `docs/design/dao.stream.journal.md:160-164`. The C5
   engineer added it as "backend `:memory`, failure model `:none`,
   lock kind `:none`, `persisted` `#{}`", next to the file backend's
   `:process-crash` table at lines 193-203. That file is where the
   journal declaration is described. yin.vm.linker.dht.md has no
   durability-declaration text, so I added nothing there.
   - My only edit rewrapped that sentence's line, which was 100
     columns, to 80. The wording is unchanged.
   - Checked against the code: `journal.cljc:99-106`
     `memory-durability` and `journal/file.cljc:29-38`.

6. **ucf-revisions status line.** At
   `docs/design/yin.vm.ucf-revisions.md:362-367`, as a `Status,
   2026-10-05:` line in section 6, right after "Still pending: ...
   remain owed". It uses the same register as the existing "Status:"
   line at old line 370.
   - It lists C1 to C5 with their hashes. I checked each hash with
     `git log --no-walk`; each subject names its slice.
   - It lists the amendments that landed, and says C6 to C12, D and E
     remain.
   - It notes `handoff-version` is still 0 (`handoff.cljc:60-64`).
   - The published "Still pending: all implementation" line is not
     reworded. The new line now qualifies it.

7. **C6 refusal note: not written.** 14.2.x has no plan-facing text
   about judge reconstruction or refusals. 14.2.2 and 14.2.3 step 7
   describe restart only in general terms, so any note there would be
   new schema in the wrong place. Ruling (b)'s requirement for C6
   therefore remains only in the glm findings: the ledger refusal must
   carry the proposer as well as the proposal id, because dao.lease's
   refusal carries only `:dao.lease/proposal`. The C6 brief should
   carry it.

## Where the code differs from the glm text

The code was followed in every case below.

- The glm text and the plan talk about storing in `content.jing`. The
  code puts the body through a caller-supplied dao.jing byte store
  (`:put-bytes-fn`, `grant.cljc` `store!`). The docs name no file.
- The glm text implies the authority's offer fact is a new fact kind.
  In code, the admitted offer reuses the emitter offer's dispatch,
  `:yin.k/custody :yin.k/offered` (`custody.cljc` `offer`). The two
  are told apart by author and by `:yin.k/baseline`, and 7.7.2 now
  says exactly that.
- The glm text calls ruling (a)'s inspector change an amendment still
  to come. It has already landed in `checkpoint.cljc` (lines 16, 165,
  197, 261), so 7.2.1 states the refusal as current behavior.

## Not anchored

- Amendment 7, as explained above.
- The plan's C5 offer-order sentence, which is only in collab.
