Created-GMT: 2026-10-20 19:06:58 GMT
Created-Local: 2026-10-21 02:06:58 +07
Coding-Agent: claude
Model: claude-opus-5-5
Role: QA & Verification engineer
Prompt: /Users/sto/workspace/datomworld/collab/1792523065000-engineer-host-boundary-h0-retag.claude-opus-5-5.prompt.md
Design: /Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md (r7: §8.1 reconciliation paragraph, §3.1 new rows, §3.2 three scopes)
Rationale read: /Users/sto/workspace/datomworld/collab/1792522578000-architect-host-boundary-r7.claude-fable-5-1.findings.md
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0 (branch host-h0, on top of 9f6fc27d)
Status: DONE. Uncommitted edits to 5 tracked files. Nothing staged, committed or pushed.

# H0 retag: the 16 `:h0-unlisted` files, reconciled with r7

## Summary

- No list-1 entry is `:h0-unlisted` any more. Each of the 76 entries now
  carries `{:category :clears :reason?}` as data:
  - 9 entries were retagged to `:migration-source`, cleared by H2. Two of
    these name their behaviour change.
  - 6 entries were retagged to `:dialect-arm` (`:stays`).
  - 1 entry (`rpc.cljc`) was retagged to `:deferred-scope` (`:stays`,
    deferred entropy).
- The gate now **fails if a list-1 file has an unexplained category**, a
  clearing slice its row does not allow, or a closure class of `:none`.
  A probe confirms the failure (below).
- The six names that could not be found are **not** on list 1. They never
  were: the generated list is the observed set, and the test asserts that
  equality.
- Unchanged:
  - totals 142/140/141 with 133 shared
  - the traversal and its independent agreement
  - both negative tests and the scan-failure test
  - the corpus hash set (`--check` exits 0)
  - the 21-site list 2
- `dao.host.gate-test` passes: **10 tests, 293 assertions** (was 10 and 65).
  The +228 are the new per-file checks (76 files × 3). No deftest was
  added.

## What changed, per file (paths relative to the worktree)

- **`test/dao/host/gate_test.clj`**
  - **`list-1-categories` (new data):** the seven §8.1 rows of r7
    (`:migration-source`, `:profile`, `:runtime-prereq`, `:codec-track`,
    `:dialect-arm`, `:seam-driver`, `:deferred-scope`). For each row it
    records the row text and the slices allowed to clear it (`"H2"`,
    `"H3"`, `"H4"` or `:stays`).
  - **`list-1`:** each value is now `{:category :clears :reason}` instead
    of a bare keyword, built with a small `entry` helper.
    - The 16 former `:h0-unlisted` entries carry their site and reason.
    - The original migration sources now record the slice that clears
      them:

      | File | Clears | Reason |
      |---|---|---|
      | `integer/host` | H2 | deleted |
      | `jing/cbor` | H2 | writer H4 |
      | `jing/cbor/boring` | H4 | |
      | `cbor/cljd.cljd` | H4 | |
      | `jing.cljc` | H3 | |
      | `sign` | `:stays` | `generate` |
      | `base64` | H3 | |
      | `debruijn` | H2 | |
      | `debruijn_code` | H2 | |
      | `data` | H2 | |
      | `values` | H2 | |
      | `chunks` | H2 | |
      | `jing/dht` | H2 | |
      | `linker` | H2 | |
      | `datagram` | H2 | |

  - Category counts: migration-source 24 (15 + 9), deferred-scope 16
    (15 + 1), seam-driver 21, dialect-arm 6, codec-track 5,
    runtime-prereq 3, profile 1. That is 76 in total. Clearing slices:
    H2 19, H3 2, H4 2, `:stays` 53.
  - **`list-1-equals-the-observed-arms`** keeps its equality assertion
    and gains a second `testing` block. For every list-1 file it asserts:
    - the category is a known row;
    - the `:clears` value is one that row allows;
    - the committed closure class is not `#{:none}`, which ties list 1 to
      the closure tags.
- **The 16, retagged:**
  - **`:migration-source`, H2:**
    - `yin/vm.cljc:404`, `bytes->str`. Named behaviour change: strict
      UTF-8 on all three hosts.
    - `yang/php.cljc:95,98`. Named behaviour change: `js/parseInt` is
      inexact above 2^53 and becomes exact.
    - `dao/space/index.cljc:78`
    - `yin/repl/query.cljc:460`
    - `yin/vm/linker/closure.cljc:32`
    - `yin/vm/ucf/rows.cljc:282`
    - `yin/vm/debruijn_register_code.cljc:439`
    - `yin/vm/debruijn_register_effects.cljc:130`
    - `yang/antlr/packet.cljc:67`
  - **`:dialect-arm`, `:stays`:**
    - `dao/space/query.cljc:808`, `yang/clojure.cljc:375` and
      `yin/vm/engine.cljc:929`: a runtime type name.
    - `yin/repl/adapter.cljc:58` and `yin/repl/dht.cljc:104`: the EDN
      reader.
    - `dao/stream/ringbuffer.cljc:168`: the in-memory stream's own atom
      test.
  - **`:deferred-scope`, `:stays`:** `dao/stream/rpc.cljc:114`, `rand`,
    deferred entropy (§9.1).
- **`src/dev/host_boundary/closure.clj`** (the class-row data only; the
  traversal is untouched). This aligns the class rows with r7 §3.1:
  - **new `:profile-consumer` row** ("H2"), holding the nine namespaces
    above;
  - **new `:dialect-arm` row**, holding the five namespaces of r7's
    dialect row;
  - **seams row:**
    - `dao.stream.waitset.driver` moved into it, which removes the
      separate `lane-rows` special case;
    - `dao.stream.ringbuffer` added, as r7 puts `ringbuffer:168` there;
    - `dao.stream.udp` and `dao.stream.ws.browser` dropped;
  - **entropy row:** `dao.await` and `yin.vm.ffi.remote-serve` dropped;
  - **timers row:** `dao.jing.content.async` dropped;
  - **runtime-prereq row:** `dao.space.schema` dropped.

  None of the dropped names was in the closure, so dropping them changes
  no tag.
- **`docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`:** regenerated
  by the tool (32 changed lines each).
  - The legend gains `:dialect-arm` and `:profile-consumer`.
  - Each of the 16 files now has its r7 class. The nine moved from
    `:none` (or, for `yin.vm` and `yang.php`, from
    `:consumer`/`:deferred-gensym` alone) to include `:profile-consumer`.
    The dialect files gained `:dialect-arm`.
  - `ringbuffer` gained `:external-stream-seam`.
  - Totals, shared count and file lists are byte-for-byte unchanged.
- **F2 count wording.** The gate's `list-2` docstring already reads "21
  inside the closure (dao.await and yin.vm.ffi.remote-serve are outside
  it)". This matches r7 §3.2: the gate uses the transitive closure, and
  the five sites outside it are inputs to the deferred design. The data
  is unchanged and the comment already says this; I made no further
  wording edit.

## Results (all run in the foreground in the worktree)

```
$ clojure -M:test -n dao.host.gate-test
Ran 10 tests containing 293 assertions.
0 failures, 0 errors.

$ clojure -Sdeps '{:aliases {:hb ...}}' -M:hb -m host-boundary.closure --check   # before regenerating
STALE: docs/design/host-boundary/closure-clj.edn ...cljs.edn ...cljd.edn    (expected: tags changed)
$ ... -m host-boundary.closure            # regenerate
clj   total 142 shared 133 lane-specific 9
cljs  total 140 shared 133 lane-specific 7
cljd  total 141 shared 133 lane-specific 8
shared (all three lanes): 133
$ ... -m host-boundary.closure --check    -> exit 0

$ bb src/dev/host_boundary/corpus_hashes.clj --check
18 files
corpus identical to docs/design/host-boundary/corpus-sha256.edn   -> exit 0
```

clj-kondo reports 0 errors and 0 warnings. cljstyle check is clean.

**The new check fails when it should.** The probe is
`target/h0/probe/retag_neg.clj`. It uses `with-redefs` on `list-1` and
runs `list-1-equals-the-observed-arms`:

- With `yin/vm.cljc` set to `:h0-unlisted`, the run gives
  `{:pass 227, :fail 2}`. The two failures name the file, the unknown
  category, and the slice that category cannot allow.
- With `yin/vm.cljc` set to `:clears "H9"`, the run gives
  `{:pass 228, :fail 1}`.

**JVM lane count.** I did not re-run the full lane; r7 says it need not be
re-run beyond the gate. No deftest was added, so the tests stay at
**4131**. Assertions should rise by exactly +228, from 286103 to
**286331**. That figure is derived, not measured.

## r7 points I could not implement literally, or that disagree with the code

1. **"No tool change" vs "retag the committed closure class tags".** The
   closure files never held `:h0-unlisted`; that tag was only in the test.
   The class tags come from the tool's `class-rows` data. Adding r7's two
   §3.1 rows and keeping `--check` green therefore required editing that
   data. I changed only data; the traversal and output format are
   untouched. Leaving the tool alone would have meant either hand-editing
   generated files, which `--check` would then flag, or leaving the nine
   tagged `:none`, which the new list-1 check now refuses.
2. **`ringbuffer.cljc:168` is placed differently in two sections.** §3.1
   puts it in the seams row; §8.1 puts it in the "dialect arms" row. I
   followed each section in its own artefact: closure class
   `:external-stream-seam` (§3.1) and list-1 category `:dialect-arm`
   (§8.1). Both say "retained, no slice", so nothing behaves differently,
   but the Architect may want one name.
3. **r7 §3.2 lists the 13 print calls in `main.cljc` as "`println`,
   `print`, `flush`, `js/console.log`".** In the code they are 8
   `println`, 1 `print` and 4 `js/console.log`. The gate does not count
   `flush`; `main.cljc` has one `(flush)`. The count of 13 is right; the
   word `flush` is not.
4. **`yang/php.cljc` line numbers.** §3.1 and §8.1 cite `:95,98`, which
   are the conditional sites the gate reports. The behaviour-change text
   cites `js/parseInt` at `:100`, which is the cljs arm inside the `:98`
   conditional. These are consistent, just two granularities.
5. **The six names.** As r7 says, none was ever on the generated list 1,
   and the equality test keeps it that way. The tool's class rows also no
   longer name them. The §8.3 codec row still names
   `dao.stream.ws.browser`, because §8.3 itself does ("four ws
   adapters"); it matches no closure file.

Not done:
- no design-doc edit;
- no `src/` behaviour change;
- no `deps.edn` or `bb.edn` change;
- no full-lane re-run;
- no stage, commit or push.

Report: /Users/sto/workspace/datomworld/collab/1792523065000-engineer-host-boundary-h0-retag.claude-opus-5-5.findings.md
