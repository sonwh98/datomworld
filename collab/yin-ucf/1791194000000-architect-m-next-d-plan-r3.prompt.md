Created-GMT: 2026-10-05 09:34:00 GMT
Created-Local: 2026-10-05 16:34:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: fold the confirmation residuals into D-plan r3 (read-only; the revised plan is the deliverable)
Role: Architect

The second architect confirmed r2 with four residuals
(collab/1791192700000-architect-m-next-d-plan-r2-confirm.gpt-6-astra
.findings.md) and ruled r2 sufficient to implement with them folded.
Produce r3: keep r2's structure, fold exactly these four corrections,
and mark each with the residual number it answers. Nothing else changes.

1. Cursor-creation sources (1.4): for `:next` and `:poll` the source
   carries the existing portable position; for `:cursor` the source
   carries the requested cursor origin (e.g. `:dao.stream/oldest`),
   not the resulting position. The resulting portable position belongs
   only to the recorded observation. Replay constructs the cursor from
   that observation without live minting.
2. Release wording (1.6): "Release never clears quarantine or completes
   an occurrence without accepted completion evidence. A quarantined
   occurrence remains ungrantable. For other failed runs, release
   follows the ordinary reclaim/regrant policy; the ending driver does
   not itself authorize recovery." (The reviewer's replacement text.)
3. Decoder resolution (1.7): the two codecs are deliberately different
   profiles (dao.stream.cbor tag 39; dao.jing.cbor's decoder refuses
   tag 39; the version-0 lower uses the stream codec and must keep it).
   Two decoding paths in D7; retain which codec accepted the bytes and
   enforce its body-version contract before address and restoration
   checks. Replace the "Neither decoding is :yin.k/undecodable"
   sentence with: "If neither supported codec decodes the bytes, return
   `:yin.k/undecodable`. Retain which codec accepted the bytes and
   enforce its body-version contract before address and restoration
   checks."
4. REPL shutdown (1.12 and D15): JVM exits the loop when :running?
   turns false then drains and closes (main.cljc about 981); Node and
   Dart switch into stop-tick which no longer calls step-all (about
   1124); moved? tracks shell/server progress only (about 849). D15
   must continue control-plane stepping during bounded shutdown
   draining, or durably retain outstanding intents for restart before
   exit; program execution stays stopped. Integrate with the existing
   tick owner; no second timer.

Also update the D12 test contract for residual 1 (cursor replay without
live minting), D13/D14 where residual 2's wording lands, D7 for residual
3 (two decode paths, codec identity retained), and D15 for residual 4.
State in one line at the top that r3 differs from r2 only by residuals
1 to 4. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
