Created-GMT: 2026-09-21 21:20:00 GMT
Created-Local: 2026-09-22 04:20:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J0 corpus turn)
# Task: jing-cbor-j0-fix: apply the reviewers' agreed findings to the J0 corpus
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 04:20:00 +07 | Status: active | Rationale: same author fixes its own corpus (resume rule); Claude pool refreshed at 04:00 +07; reviewers were deepseek-v4-pro and qwen3.8-max (different families)

Work ONLY in /Users/sto/workspace/worktree-jing-cbor (your launch directory).
Do NOT stage, commit, merge, or push. File box: the same NEW files as before
(test/resources/dao/jing/cbor-v1.*, test/dao/jing/cbor_fixtures*.cljc). Nothing else.
Same Bash notes as your first brief (Kondo, cljstyle, Java-17 and CLJD lanes are run
by the orchestrator; do not retry denied commands; the generator writes to stdout
only, so redirect it to the JSON file in ONE simple command if allowed, else say
exactly what was denied and stop).

## Inputs (inside this worktree's collab/)

- 1790023590463-ref-review-qwen.findings.md
- 1790023590463-ref-review-deepseek.findings.md
Read both in full. They agree on every byte (no disagreements); both are READY WITH
CHANGES. The orchestrator recorded the ambiguity rulings (both reviewers converge):
- A4 keep: with-meta wraps the dao.jing/list frame; EXTEND the README text to symbols.
- A5 keep: strip only unqualified :line :column :end-line :end-column, at the top
  level of every metadata map (metadata carried by metadata is handled the same way).
- A6 keep sequential equality (list = vector), so a set holding [1] and (1) is refused.
- A8 accept empty name (provisional; keep the case, optionally add symbol empty name).
- A9 REJECT: validate before strip. A lone surrogate under a STRIPPED reader key in
  metadata is refused with unpaired-surrogate. ADD a fixture for it (decode side, and
  an encode-refusal if the DSL can express it).
- A11 accept denominator 1 (keep the two cases).

## Do (all of it, in order)

1. qwen P2-1: in the generator's WITH_META handling, a metadata map that itself carries
   metadata is Refusal("malformed-frame", ...) instead of an AssertionError. Add the
   decode-refusal fixture `meta/decode-meta-with-own-meta`. Record the ruling under
   A3/A10 in the README.
2. Self-tests: add `malformed-number` and `unsupported-value` to the required
   refusals, and `unsupported-values` to the required categories (in BOTH the test and
   the generator's REQUIRED_CATEGORIES); the required refusal set must then equal all
   16 classes.
3. One `duplicate-key` encode-refusal for an integer-width duplicate introduced by
   normalization (the same value as a small and as a big integer in one set/map).
4. One canonical case for a sorted-map (or sorted-set) carrying metadata, pinning that
   the normalizations compose.
5. A float-versus-decimal (or int-versus-float) collapse case if the reviewers'
   findings named one and it is constructible; otherwise say why not.
6. README: in the ambiguities or a notes section, state which collapse refusals are
   N/A (not constructible) on which hosts (qwen P3-4) and A4's extension to symbols;
   list every ruling above so a later J1 implementer has one place to read them.
7. Symbol slash-cross identifiers: add the two symbol crossings mirroring the keyword
   ones (qwen P3-6). Make the test's frame-name hex match byte-aligned (qwen P3-7).
8. Apply every other agreed P3 from BOTH reviews that is a corpus or test change and
   is not covered above; for anything you decline, give the finding and the reason.
9. Regenerate the JSON with the generator (stdout redirect), run the generator
   `--check`, and run the focused JVM test:
   `clojure -M:test -n dao.jing.cbor-fixtures-test` (Java 21 default is fine for it).
   Report the counts you observe. Do not report anything you did not run.

The corpus is still uncommitted, so this is a pre-freeze edit, not a v1 correction:
just make the corpus right. Keep files pure ASCII, no em dashes, 80 columns in the
README, cljstyle-style Clojure (blank lines between top-level forms).

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
then: per finding (agree / declined with reason), files changed, new case ids,
counts by kind, what you ran with exact counts, what you could not run.
