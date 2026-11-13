Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 2 — strict UTF-8 admission + classified refusal (per astra's binding ruling)

Role: Engineer. Branch eval-bridge (pushed). READ FIRST, binding:
1. collab/yang-php/1794605712000-architect-bytedomain-ruling.findings.md — the contract (A: domain Latin-1-carrier preservation, strict-decode-once at the worker boundary with UTF8(U)=B proof via the existing code-point->UTF-8 offset table, spans/line-starts invariants, PHP-mode lexer start, no normalization/BOM-strip, the classified-refusal diagnostic shape, fixture requirements: multibyte/supplementary/heredoc-nowdoc/interpolated/invalid-sequence/CRLF-CR-line-numbers).
2. The original design (collab/orch-lane/1794400000000-architect-eval-bridge-design.gpt-6.1-sol.stdout.log, committed on this branch) — sections as your brief.

## Implement THIS slice only (the rest of the bridge is later slices)
1. Compiler-worker admission: strict UTF-8 decode-once with UTF8(U)=B verification (reject overlong/surrogate/truncated/>U+10FFFF), the classified-refusal diagnostic exactly per the ruling shape ({:yang.php/diagnostic :yang.php/unsupported :construct :eval :reason :source-encoding :encoding :utf-8 :byte-offset n ...}), routed through the correlated compiler rejection.
2. Byte-domain fixtures (red-first, oracle-pending where real PHP must adjudicate): valid multibyte (2/3/4-byte sequences), supplementary chars in literals+interpolated strings, heredoc/nowdoc, inline HTML around them, ill-formed sequences (continuation-only, overlong, surrogate, >U+10FFFF, truncated) each asserting the classified refusal, NUL/BOM/U+FFFD preserved-and-grammar-classified, CRLF and CR line-number behavior.
3. The eval-unit emission + php/eval-run + code/entry effect: proceed if budget allows after 1-2; otherwise record precisely for the next slice. (The admission slice is the deliverable; the pipeline may defer.)

## Gates (laptop, foreground, explicit timeouts, sequential)
Focused yang.php/lowering/cst/antlr namespaces green; kondo 0/0; cljstyle clean. Do NOT touch exception-ceded prelude regions beyond the admission function you add; suite-scale = orchestrator. Push as you go. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r2.gpt-6.1-terra.findings.md (red-first chronology, per-fixture adoption, ruling-row mapping).
